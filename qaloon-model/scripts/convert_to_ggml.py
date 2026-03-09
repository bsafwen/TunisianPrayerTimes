#!/usr/bin/env python3
"""
Convert fine-tuned HuggingFace Whisper model to whisper.cpp GGML format.

Based on whisper.cpp/models/convert-h5-to-ggml.py (v1.8.3), adapted to be
self-contained (uses HF feature_extractor for mel filters instead of requiring
the openai/whisper repo).

Usage:
    python convert_to_ggml.py --model models/whisper-qaloon/final
    python convert_to_ggml.py --model models/whisper-qaloon/final --f16
"""

import argparse
import struct
import json
import sys
from pathlib import Path

import numpy as np
import torch
from transformers import WhisperForConditionalGeneration, WhisperProcessor

# Exact name mapping from HF to whisper.cpp (from vendored convert-h5-to-ggml.py)
conv_map = {
    'self_attn.k_proj'              : 'attn.key',
    'self_attn.q_proj'              : 'attn.query',
    'self_attn.v_proj'              : 'attn.value',
    'self_attn.out_proj'            : 'attn.out',
    'self_attn_layer_norm'          : 'attn_ln',
    'encoder_attn.q_proj'           : 'cross_attn.query',
    'encoder_attn.v_proj'           : 'cross_attn.value',
    'encoder_attn.out_proj'         : 'cross_attn.out',
    'encoder_attn_layer_norm'       : 'cross_attn_ln',
    'fc1'                           : 'mlp.0',
    'fc2'                           : 'mlp.2',
    'final_layer_norm'              : 'mlp_ln',
    'encoder.layer_norm.bias'       : 'encoder.ln_post.bias',
    'encoder.layer_norm.weight'     : 'encoder.ln_post.weight',
    'encoder.embed_positions.weight': 'encoder.positional_embedding',
    'decoder.layer_norm.bias'       : 'decoder.ln.bias',
    'decoder.layer_norm.weight'     : 'decoder.ln.weight',
    'decoder.embed_positions.weight': 'decoder.positional_embedding',
    'decoder.embed_tokens.weight'   : 'decoder.token_embedding.weight',
    'proj_out.weight'               : 'decoder.proj.weight',
}


def bytes_to_unicode():
    """GPT-2 byte-to-unicode mapping for BPE tokenizer."""
    bs = list(range(ord("!"), ord("~")+1))+list(range(ord("¡"), ord("¬")+1))+list(range(ord("®"), ord("ÿ")+1))
    cs = bs[:]
    n = 0
    for b in range(2**8):
        if b not in bs:
            bs.append(b)
            cs.append(2**8+n)
            n += 1
    cs = [chr(n) for n in cs]
    return dict(zip(bs, cs))


def rename_hf_to_ggml(src_name):
    """Map a HuggingFace state_dict key to the whisper.cpp GGML tensor name."""
    # Strip "model." prefix
    nn = src_name.split(".")
    if nn[0] == "model":
        nn = nn[1:]

    if nn[1] == "layers":
        nn[1] = "blocks"
        sub_key = ".".join(nn[3:-1])
        if sub_key == "encoder_attn.k_proj":
            mapped = "cross_attn.key"
        elif sub_key in conv_map:
            mapped = conv_map[sub_key]
        else:
            mapped = sub_key
        return ".".join(nn[:3] + [mapped] + nn[-1:])
    else:
        flat = ".".join(nn)
        return conv_map.get(flat, flat)


def convert_to_ggml(model_dir: Path, output_path: Path, use_f16: bool = False):
    """Convert HuggingFace Whisper model to whisper.cpp GGML format."""
    print(f"Loading model from {model_dir}...")
    processor = WhisperProcessor.from_pretrained(str(model_dir))
    model = WhisperForConditionalGeneration.from_pretrained(str(model_dir))
    model.eval()

    config = model.config

    # Read hparams from config.json (same field names as vendored script)
    hparams = json.load((model_dir / "config.json").open("r", encoding="utf8"))
    max_length = hparams.get("max_length") or hparams.get("max_target_positions", 448)
    if not isinstance(max_length, int):
        max_length = 448

    print(f"  vocab_size={config.vocab_size} d_model={config.d_model} "
          f"enc_layers={config.encoder_layers} dec_layers={config.decoder_layers} "
          f"n_mels={config.num_mel_bins}")

    # --- Mel filters from HF feature_extractor ---
    mel_np = np.array(processor.feature_extractor.mel_filters, dtype=np.float32)
    filters = torch.from_numpy(mel_np)

    # --- Tokenizer: use vocab.json with GPT-2 byte decoder (same as vendored script) ---
    vocab_path = model_dir / "vocab.json"
    tokens_raw = json.load(vocab_path.open("r", encoding="utf8"))
    byte_encoder = bytes_to_unicode()
    byte_decoder = {v: k for k, v in byte_encoder.items()}
    tokens_sorted = sorted(tokens_raw.items(), key=lambda x: x[1])

    # --- Write GGML ---
    print(f"Writing GGML model to {output_path}...")
    with open(output_path, "wb") as f:
        # Magic
        f.write(struct.pack("i", 0x67676d6c))

        # Hyperparameters (12 ints, same order as vendored script)
        f.write(struct.pack("i", hparams["vocab_size"]))
        f.write(struct.pack("i", hparams["max_source_positions"]))
        f.write(struct.pack("i", hparams["d_model"]))
        f.write(struct.pack("i", hparams["encoder_attention_heads"]))
        f.write(struct.pack("i", hparams["encoder_layers"]))
        f.write(struct.pack("i", max_length))
        f.write(struct.pack("i", hparams["d_model"]))
        f.write(struct.pack("i", hparams["decoder_attention_heads"]))
        f.write(struct.pack("i", hparams["decoder_layers"]))
        f.write(struct.pack("i", hparams["num_mel_bins"]))
        f.write(struct.pack("i", use_f16))

        # Mel filters (element-by-element, matching vendored format)
        f.write(struct.pack("i", filters.shape[0]))
        f.write(struct.pack("i", filters.shape[1]))
        for i in range(filters.shape[0]):
            for j in range(filters.shape[1]):
                f.write(struct.pack("f", filters[i][j]))

        # Token count + token data (vendored writes count first!)
        f.write(struct.pack("i", len(tokens_sorted)))
        for key, _idx in tokens_sorted:
            text = bytearray([byte_decoder[c] for c in key])
            f.write(struct.pack("i", len(text)))
            f.write(text)

        # Tensors
        state_dict = model.state_dict()
        for src_name in state_dict.keys():
            if src_name == "proj_out.weight":
                print(f"  Skipping {src_name}")
                continue

            ggml_name = rename_hf_to_ggml(src_name)
            data = state_dict[src_name].squeeze().numpy().astype(np.float16)

            # Reshape conv bias from [n] to [n, 1]
            if ggml_name in ["encoder.conv1.bias", "encoder.conv2.bias"]:
                data = data.reshape(data.shape[0], 1)
                print(f"  Reshaped {ggml_name} to {data.shape}")

            n_dims = len(data.shape)

            # ftype: 0=float32, 1=float16
            ftype = 1
            if use_f16:
                if n_dims < 2 or \
                        ggml_name == "encoder.conv1.bias" or \
                        ggml_name == "encoder.conv2.bias" or \
                        ggml_name == "encoder.positional_embedding" or \
                        ggml_name == "decoder.positional_embedding":
                    data = data.astype(np.float32)
                    ftype = 0
            else:
                data = data.astype(np.float32)
                ftype = 0

            print(f"  {src_name} -> {ggml_name}  {n_dims}D {data.shape}")

            # Header: n_dims, name_length, ftype
            encoded_name = ggml_name.encode("utf-8")
            f.write(struct.pack("iii", n_dims, len(encoded_name), ftype))

            # Dimensions (reversed)
            for i in range(n_dims):
                f.write(struct.pack("i", data.shape[n_dims - 1 - i]))

            # Name
            f.write(encoded_name)

            # Data (NO alignment padding — vendored format writes data immediately)
            data.tofile(f)

    file_size = output_path.stat().st_size
    print(f"\nDone. Output: {output_path} ({file_size / 1e6:.1f} MB)")
    return output_path


def main():
    parser = argparse.ArgumentParser(description="Convert HF Whisper to GGML for whisper.cpp")
    parser.add_argument("--model", type=str, required=True, help="Path to HuggingFace model directory")
    parser.add_argument("--output", type=str, default=None, help="Output GGML file path")
    parser.add_argument("--f16", action="store_true", help="Use float16 for 2D+ tensors (smaller)")
    args = parser.parse_args()

    model_dir = Path(args.model)
    if not model_dir.exists():
        print(f"ERROR: Model directory not found: {model_dir}")
        sys.exit(1)

    if args.output:
        output_path = Path(args.output)
    else:
        suffix = "-f16" if args.f16 else "-f32"
        output_path = model_dir.parent / f"ggml-model{suffix}.bin"

    convert_to_ggml(model_dir, output_path, use_f16=args.f16)


if __name__ == "__main__":
    main()
