#!/usr/bin/env python3
"""
Repair a broken GGML model file produced by the old convert_to_ggml.py.

Fixes:
  1. Missing token count before token data
  2. Wrong tokenizer encoding (decode-then-encode vs raw byte tokens)
  3. Incorrect tensor name mapping (double attn. prefix, wrong decoder.ln)
  4. Unwanted 32-byte alignment padding after tensor names

Usage:
    python repair_ggml.py INPUT_GGML OUTPUT_GGML
"""

import struct
import sys
import json
import numpy as np
from pathlib import Path


def bytes_to_unicode():
    """GPT-2 byte-to-unicode mapping used by whisper tokenizer."""
    bs = list(range(ord("!"), ord("~")+1)) + list(range(ord("¡"), ord("¬")+1)) + list(range(ord("®"), ord("ÿ")+1))
    cs = bs[:]
    n = 0
    for b in range(2**8):
        if b not in bs:
            bs.append(b)
            cs.append(2**8 + n)
            n += 1
    cs = [chr(n) for n in cs]
    return dict(zip(bs, cs))


# Correct name mapping from vendored convert-h5-to-ggml.py
CONV_MAP = {
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


def hf_name_to_ggml(src_name):
    """Map HF state_dict key (without 'model.' prefix) to whisper.cpp name."""
    nn = src_name.split(".")
    if nn[0] == "model":
        nn = nn[1:]

    if nn[1] == "layers":
        nn[1] = "blocks"
        sub_key = ".".join(nn[3:-1])
        if sub_key == "encoder_attn.k_proj":
            mapped = "cross_attn.key"
        elif sub_key in CONV_MAP:
            mapped = CONV_MAP[sub_key]
        else:
            mapped = sub_key
        return ".".join(nn[:3] + [mapped] + nn[-1:])
    else:
        flat = ".".join(nn)
        return CONV_MAP.get(flat, flat)


def broken_name_mapping(name):
    """Reproduce the old (broken) name mapping to understand what names are in the broken file."""
    new_name = name
    new_name = new_name.replace("model.", "")
    new_name = new_name.replace("encoder.layers.", "encoder.blocks.")
    new_name = new_name.replace("decoder.layers.", "decoder.blocks.")
    new_name = new_name.replace("self_attn.", "attn.")
    new_name = new_name.replace("encoder_attn.", "cross_attn.")
    new_name = new_name.replace("self_attn_layer_norm.", "attn_ln.")
    new_name = new_name.replace("encoder_attn_layer_norm.", "cross_attn_ln.")
    new_name = new_name.replace("final_layer_norm.", "mlp_ln.")
    new_name = new_name.replace("fc1.", "mlp.0.")
    new_name = new_name.replace("fc2.", "mlp.2.")
    if "layer_norm" in name and "layers" not in name:
        new_name = new_name.replace("layer_norm.", "ln_post.")
    else:
        new_name = new_name.replace("layer_norm.", "ln.")
    new_name = new_name.replace("embed_positions.weight", "positional_embedding")
    new_name = new_name.replace("embed_tokens.", "token_embedding.")
    if "self_attn" in name:
        new_name = new_name.replace("q_proj.", "attn.query.")
        new_name = new_name.replace("k_proj.", "attn.key.")
        new_name = new_name.replace("v_proj.", "attn.value.")
        new_name = new_name.replace("out_proj.", "attn.out.")
    else:
        new_name = new_name.replace("q_proj.", "cross_attn.query.")
        new_name = new_name.replace("k_proj.", "cross_attn.key.")
        new_name = new_name.replace("v_proj.", "cross_attn.value.")
        new_name = new_name.replace("out_proj.", "cross_attn.out.")
    new_name = new_name.replace("proj_out.", "proj.")
    return new_name


def build_broken_to_correct_map():
    """Build mapping from broken tensor names to correct GGML names.

    Enumerates all expected HF model parameter names for whisper-small
    and maps broken_name → correct_name.
    """
    # Build all expected HF names for whisper-small (12 enc layers, 12 dec layers)
    hf_names = []

    # Encoder conv layers
    for conv in ["conv1", "conv2"]:
        for param in ["weight", "bias"]:
            hf_names.append(f"model.encoder.{conv}.{param}")

    # Encoder positional embedding
    hf_names.append("model.encoder.embed_positions.weight")

    # Encoder blocks
    for i in range(12):
        prefix = f"model.encoder.layers.{i}"
        # Self attention
        for proj in ["q_proj", "k_proj", "v_proj", "out_proj"]:
            for param in ["weight", "bias"]:
                hf_names.append(f"{prefix}.self_attn.{proj}.{param}")
        # Layer norms
        for ln in ["self_attn_layer_norm", "final_layer_norm"]:
            for param in ["weight", "bias"]:
                hf_names.append(f"{prefix}.{ln}.{param}")
        # MLP
        for fc in ["fc1", "fc2"]:
            for param in ["weight", "bias"]:
                hf_names.append(f"{prefix}.{fc}.{param}")

    # Encoder final layer norm
    for param in ["weight", "bias"]:
        hf_names.append(f"model.encoder.layer_norm.{param}")

    # Decoder embed
    hf_names.append("model.decoder.embed_tokens.weight")
    hf_names.append("model.decoder.embed_positions.weight")

    # Decoder blocks
    for i in range(12):
        prefix = f"model.decoder.layers.{i}"
        # Self attention
        for proj in ["q_proj", "k_proj", "v_proj", "out_proj"]:
            for param in ["weight", "bias"]:
                hf_names.append(f"{prefix}.self_attn.{proj}.{param}")
        # Self attention layer norm
        for param in ["weight", "bias"]:
            hf_names.append(f"{prefix}.self_attn_layer_norm.{param}")
        # Cross attention
        for proj in ["q_proj", "k_proj", "v_proj", "out_proj"]:
            for param in ["weight", "bias"]:
                hf_names.append(f"{prefix}.encoder_attn.{proj}.{param}")
        # Cross attention layer norm
        for param in ["weight", "bias"]:
            hf_names.append(f"{prefix}.encoder_attn_layer_norm.{param}")
        # MLP
        for fc in ["fc1", "fc2"]:
            for param in ["weight", "bias"]:
                hf_names.append(f"{prefix}.{fc}.{param}")
        # Final layer norm
        for param in ["weight", "bias"]:
            hf_names.append(f"{prefix}.final_layer_norm.{param}")

    # Decoder final layer norm
    for param in ["weight", "bias"]:
        hf_names.append(f"model.decoder.layer_norm.{param}")

    # Projection
    hf_names.append("proj_out.weight")

    broken_to_correct = {}
    for hf_name in hf_names:
        broken = broken_name_mapping(hf_name)
        correct = hf_name_to_ggml(hf_name)
        if broken != correct:
            broken_to_correct[broken] = correct

    return broken_to_correct


def get_correct_tokens():
    """Get correctly encoded tokens for whisper-small using vocab.json from HuggingFace."""
    from transformers import WhisperProcessor

    print("Loading whisper-small tokenizer from HuggingFace...")
    processor = WhisperProcessor.from_pretrained("openai/whisper-small")
    tokenizer = processor.tokenizer

    # Get vocab.json equivalent
    vocab = tokenizer.get_vocab()

    byte_encoder = bytes_to_unicode()
    byte_decoder = {v: k for k, v in byte_encoder.items()}

    # Sort by token index
    sorted_tokens = sorted(vocab.items(), key=lambda x: x[1])

    tokens = []
    for token_str, _idx in sorted_tokens:
        text = bytearray([byte_decoder[c] for c in token_str])
        tokens.append(bytes(text))

    return tokens


def read_int(f):
    data = f.read(4)
    if len(data) < 4:
        return None
    return struct.unpack("i", data)[0]


def repair(input_path, output_path):
    broken_to_correct = build_broken_to_correct_map()
    print(f"Name fixes to apply: {len(broken_to_correct)}")
    for b, c in sorted(broken_to_correct.items()):
        print(f"  {b} → {c}")

    correct_tokens = get_correct_tokens()
    print(f"Loaded {len(correct_tokens)} correct tokens")

    with open(input_path, "rb") as fin:
        # Read header (12 ints = 48 bytes)
        magic = read_int(fin)
        assert magic == 0x67676d6c, f"Bad magic: {hex(magic)}"

        n_vocab = read_int(fin)
        n_audio_ctx = read_int(fin)
        n_audio_state = read_int(fin)
        n_audio_head = read_int(fin)
        n_audio_layer = read_int(fin)
        n_text_ctx = read_int(fin)
        n_text_state = read_int(fin)
        n_text_head = read_int(fin)
        n_text_layer = read_int(fin)
        n_mels = read_int(fin)
        ftype = read_int(fin)

        print(f"Header: vocab={n_vocab} audio_ctx={n_audio_ctx} audio_state={n_audio_state} "
              f"audio_head={n_audio_head} audio_layer={n_audio_layer} text_ctx={n_text_ctx} "
              f"text_state={n_text_state} text_head={n_text_head} text_layer={n_text_layer} "
              f"n_mels={n_mels} ftype={ftype}")

        # Read mel filters
        mel_rows = read_int(fin)
        mel_cols = read_int(fin)
        mel_data = fin.read(mel_rows * mel_cols * 4)
        print(f"Mel filters: {mel_rows} x {mel_cols} ({len(mel_data)} bytes)")

        # Skip broken tokens (n_vocab tokens, no count prefix)
        print(f"Skipping {n_vocab} broken tokens...")
        for i in range(n_vocab):
            tlen = read_int(fin)
            fin.read(tlen)

        # Read all tensors
        tensors = []
        tensor_count = 0
        while True:
            n_dims = read_int(fin)
            if n_dims is None:
                break

            name_len = read_int(fin)
            tensor_ftype = read_int(fin)

            dims = []
            for _ in range(n_dims):
                dims.append(read_int(fin))

            name = fin.read(name_len).decode("utf-8")

            # Skip alignment padding (broken script padded to 32 bytes)
            pos = fin.tell()
            padding = (32 - pos % 32) % 32
            if padding > 0:
                fin.read(padding)

            # Calculate data size
            # dims are stored reversed (GGML convention)
            total_elements = 1
            for d in dims:
                total_elements *= d

            if tensor_ftype == 0:  # float32
                data_size = total_elements * 4
            elif tensor_ftype == 1:  # float16
                data_size = total_elements * 2
            else:
                raise ValueError(f"Unknown ftype {tensor_ftype} for tensor {name}")

            tensor_data = fin.read(data_size)
            if len(tensor_data) != data_size:
                print(f"  WARNING: Expected {data_size} bytes for {name}, got {len(tensor_data)}")

            # Fix the tensor name
            fixed_name = broken_to_correct.get(name, name)
            if name != fixed_name:
                print(f"  Fixing name: {name} → {fixed_name}")

            tensors.append({
                "n_dims": n_dims,
                "name": fixed_name,
                "ftype": tensor_ftype,
                "dims": dims,
                "data": tensor_data,
            })
            tensor_count += 1

        print(f"Read {tensor_count} tensors")

    # Write corrected file
    print(f"\nWriting corrected GGML to {output_path}...")
    with open(output_path, "wb") as fout:
        # Header (same values)
        fout.write(struct.pack("i", 0x67676d6c))
        fout.write(struct.pack("i", n_vocab))
        fout.write(struct.pack("i", n_audio_ctx))
        fout.write(struct.pack("i", n_audio_state))
        fout.write(struct.pack("i", n_audio_head))
        fout.write(struct.pack("i", n_audio_layer))
        fout.write(struct.pack("i", n_text_ctx))
        fout.write(struct.pack("i", n_text_state))
        fout.write(struct.pack("i", n_text_head))
        fout.write(struct.pack("i", n_text_layer))
        fout.write(struct.pack("i", n_mels))
        fout.write(struct.pack("i", ftype))

        # Mel filters (already in correct format)
        fout.write(struct.pack("i", mel_rows))
        fout.write(struct.pack("i", mel_cols))
        fout.write(mel_data)

        # Token count + tokens (FIXED: add count, use correct encoding)
        fout.write(struct.pack("i", len(correct_tokens)))
        for tok_bytes in correct_tokens:
            fout.write(struct.pack("i", len(tok_bytes)))
            fout.write(tok_bytes)

        # Tensors (FIXED: correct names, NO alignment padding)
        for t in tensors:
            encoded_name = t["name"].encode("utf-8")
            fout.write(struct.pack("iii", t["n_dims"], len(encoded_name), t["ftype"]))
            for d in t["dims"]:
                fout.write(struct.pack("i", d))
            fout.write(encoded_name)
            # NO padding — data immediately follows name
            fout.write(t["data"])

    out_size = Path(output_path).stat().st_size
    in_size = Path(input_path).stat().st_size
    print(f"\nDone! {input_path}: {in_size/1e6:.1f} MB → {output_path}: {out_size/1e6:.1f} MB")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("Usage: python repair_ggml.py INPUT_GGML OUTPUT_GGML")
        sys.exit(1)
    repair(Path(sys.argv[1]), Path(sys.argv[2]))
