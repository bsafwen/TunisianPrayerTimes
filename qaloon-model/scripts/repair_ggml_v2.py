#!/usr/bin/env python3
"""
Repair GGML model file v2 — fixes mel filter transpose + all original bugs.
Replaces mel filters with correctly-shaped data from HuggingFace.
"""
import struct
import sys
import numpy as np
from pathlib import Path


def bytes_to_unicode():
    bs = list(range(ord("!"), ord("~")+1)) + list(range(ord("\u00a1"), ord("\u00ac")+1)) + list(range(ord("\u00ae"), ord("\u00ff")+1))
    cs = bs[:]
    n = 0
    for b in range(2**8):
        if b not in bs:
            bs.append(b)
            cs.append(2**8 + n)
            n += 1
    cs = [chr(n) for n in cs]
    return dict(zip(bs, cs))


CONV_MAP = {
    'self_attn.k_proj': 'attn.key', 'self_attn.q_proj': 'attn.query',
    'self_attn.v_proj': 'attn.value', 'self_attn.out_proj': 'attn.out',
    'self_attn_layer_norm': 'attn_ln',
    'encoder_attn.q_proj': 'cross_attn.query', 'encoder_attn.v_proj': 'cross_attn.value',
    'encoder_attn.out_proj': 'cross_attn.out', 'encoder_attn_layer_norm': 'cross_attn_ln',
    'fc1': 'mlp.0', 'fc2': 'mlp.2', 'final_layer_norm': 'mlp_ln',
    'encoder.layer_norm.bias': 'encoder.ln_post.bias',
    'encoder.layer_norm.weight': 'encoder.ln_post.weight',
    'encoder.embed_positions.weight': 'encoder.positional_embedding',
    'decoder.layer_norm.bias': 'decoder.ln.bias',
    'decoder.layer_norm.weight': 'decoder.ln.weight',
    'decoder.embed_positions.weight': 'decoder.positional_embedding',
    'decoder.embed_tokens.weight': 'decoder.token_embedding.weight',
    'proj_out.weight': 'decoder.proj.weight',
}


def hf_name_to_ggml(src_name):
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
    new_name = name.replace("model.", "")
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


def build_name_fix_map():
    hf_names = []
    for conv in ["conv1", "conv2"]:
        for p in ["weight", "bias"]:
            hf_names.append(f"model.encoder.{conv}.{p}")
    hf_names.append("model.encoder.embed_positions.weight")
    for i in range(12):
        pfx = f"model.encoder.layers.{i}"
        for proj in ["q_proj", "k_proj", "v_proj", "out_proj"]:
            for p in ["weight", "bias"]:
                hf_names.append(f"{pfx}.self_attn.{proj}.{p}")
        for ln in ["self_attn_layer_norm", "final_layer_norm"]:
            for p in ["weight", "bias"]:
                hf_names.append(f"{pfx}.{ln}.{p}")
        for fc in ["fc1", "fc2"]:
            for p in ["weight", "bias"]:
                hf_names.append(f"{pfx}.{fc}.{p}")
    for p in ["weight", "bias"]:
        hf_names.append(f"model.encoder.layer_norm.{p}")
    hf_names.append("model.decoder.embed_tokens.weight")
    hf_names.append("model.decoder.embed_positions.weight")
    for i in range(12):
        pfx = f"model.decoder.layers.{i}"
        for proj in ["q_proj", "k_proj", "v_proj", "out_proj"]:
            for p in ["weight", "bias"]:
                hf_names.append(f"{pfx}.self_attn.{proj}.{p}")
        for p in ["weight", "bias"]:
            hf_names.append(f"{pfx}.self_attn_layer_norm.{p}")
        for proj in ["q_proj", "k_proj", "v_proj", "out_proj"]:
            for p in ["weight", "bias"]:
                hf_names.append(f"{pfx}.encoder_attn.{proj}.{p}")
        for p in ["weight", "bias"]:
            hf_names.append(f"{pfx}.encoder_attn_layer_norm.{p}")
        for fc in ["fc1", "fc2"]:
            for p in ["weight", "bias"]:
                hf_names.append(f"{pfx}.{fc}.{p}")
        for p in ["weight", "bias"]:
            hf_names.append(f"{pfx}.final_layer_norm.{p}")
    for p in ["weight", "bias"]:
        hf_names.append(f"model.decoder.layer_norm.{p}")
    hf_names.append("proj_out.weight")

    fix = {}
    for hf in hf_names:
        b = broken_name_mapping(hf)
        c = hf_name_to_ggml(hf)
        if b != c:
            fix[b] = c
    return fix


def read_int(f):
    d = f.read(4)
    return struct.unpack("i", d)[0] if len(d) == 4 else None


def repair(input_path, output_path):
    name_fixes = build_name_fix_map()
    print(f"Name fixes: {len(name_fixes)}")

    # Get correct tokens
    from transformers import WhisperProcessor
    print("Loading whisper-small tokenizer...")
    proc = WhisperProcessor.from_pretrained("openai/whisper-small")
    vocab = proc.tokenizer.get_vocab()
    byte_decoder = {v: k for k, v in bytes_to_unicode().items()}
    sorted_tokens = sorted(vocab.items(), key=lambda x: x[1])
    correct_tokens = [bytes(bytearray([byte_decoder[c] for c in tok_str])) for tok_str, _ in sorted_tokens]
    print(f"  {len(correct_tokens)} tokens loaded")

    # Get correct mel filters (transposed from HF shape)
    mel_hf = np.array(proc.feature_extractor.mel_filters, dtype=np.float32)  # (201, 80)
    mel_correct = mel_hf.T  # (80, 201) — what whisper.cpp expects
    print(f"  Mel filters: HF {mel_hf.shape} -> correct {mel_correct.shape}")

    # Parse broken file
    with open(input_path, "rb") as fin:
        magic = read_int(fin)
        assert magic == 0x67676d6c

        hparams = [read_int(fin) for _ in range(11)]
        n_vocab = hparams[0]
        print(f"  n_vocab={n_vocab}")

        # Skip broken mel filters
        mel_r = read_int(fin)
        mel_c = read_int(fin)
        fin.read(mel_r * mel_c * 4)
        print(f"  Skipped broken mel: {mel_r}x{mel_c}")

        # Skip broken tokens (no count prefix in broken file)
        for _ in range(n_vocab):
            tlen = read_int(fin)
            fin.read(tlen)

        # Read tensors
        tensors = []
        while True:
            n_dims = read_int(fin)
            if n_dims is None:
                break
            name_len = read_int(fin)
            ftype = read_int(fin)
            dims = [read_int(fin) for _ in range(n_dims)]
            name = fin.read(name_len).decode("utf-8")

            # Skip alignment padding from broken script
            pos = fin.tell()
            padding = (32 - pos % 32) % 32
            if padding > 0:
                fin.read(padding)

            total = 1
            for d in dims:
                total *= d
            bpe = 2 if ftype == 1 else 4
            data = fin.read(total * bpe)

            fixed_name = name_fixes.get(name, name)
            tensors.append({"n_dims": n_dims, "name": fixed_name, "ftype": ftype, "dims": dims, "data": data})

        print(f"  Read {len(tensors)} tensors")

    # Write corrected file
    print(f"Writing to {output_path}...")
    with open(output_path, "wb") as fout:
        fout.write(struct.pack("i", 0x67676d6c))
        for v in hparams:
            fout.write(struct.pack("i", v))

        # Correct mel filters
        fout.write(struct.pack("i", mel_correct.shape[0]))  # 80
        fout.write(struct.pack("i", mel_correct.shape[1]))  # 201
        for i in range(mel_correct.shape[0]):
            for j in range(mel_correct.shape[1]):
                fout.write(struct.pack("f", mel_correct[i][j]))

        # Correct tokens with count prefix
        fout.write(struct.pack("i", len(correct_tokens)))
        for tok in correct_tokens:
            fout.write(struct.pack("i", len(tok)))
            fout.write(tok)

        # Tensors — no padding
        for t in tensors:
            enc = t["name"].encode("utf-8")
            fout.write(struct.pack("iii", t["n_dims"], len(enc), t["ftype"]))
            for d in t["dims"]:
                fout.write(struct.pack("i", d))
            fout.write(enc)
            fout.write(t["data"])

    out_size = Path(output_path).stat().st_size
    print(f"Done! {out_size / 1e6:.1f} MB")


if __name__ == "__main__":
    repair(Path(sys.argv[1]), Path(sys.argv[2]))
