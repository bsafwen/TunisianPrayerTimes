#!/usr/bin/env python3
"""Validate a GGML model file header and structure."""
import struct
import sys

path = sys.argv[1]
with open(path, "rb") as f:
    magic = struct.unpack("i", f.read(4))[0]
    print(f"Magic: {hex(magic)}")

    names = ["n_vocab","n_audio_ctx","n_audio_state","n_audio_head","n_audio_layer",
             "n_text_ctx","n_text_state","n_text_head","n_text_layer","n_mels","ftype"]
    hparams = {}
    for name in names:
        v = struct.unpack("i", f.read(4))[0]
        hparams[name] = v
        print(f"  {name}: {v}")

    mel_rows = struct.unpack("i", f.read(4))[0]
    mel_cols = struct.unpack("i", f.read(4))[0]
    print(f"Mel filters: {mel_rows} x {mel_cols}")
    f.read(mel_rows * mel_cols * 4)

    n_tokens = struct.unpack("i", f.read(4))[0]
    print(f"Token count: {n_tokens}")
    for i in range(min(5, n_tokens)):
        tlen = struct.unpack("i", f.read(4))[0]
        tok = f.read(tlen)
        print(f"  Token {i}: len={tlen} data={tok[:20]}")
    for i in range(5, n_tokens):
        tlen = struct.unpack("i", f.read(4))[0]
        f.read(tlen)
    print(f"Tokens end at offset: {f.tell()}")

    # Read first 5 tensors
    for t in range(5):
        data = f.read(4)
        if len(data) < 4:
            print("EOF")
            break
        n_dims = struct.unpack("i", data)[0]
        name_len = struct.unpack("i", f.read(4))[0]
        ftype = struct.unpack("i", f.read(4))[0]
        dims = [struct.unpack("i", f.read(4))[0] for _ in range(n_dims)]
        name = f.read(name_len).decode("utf-8")
        total = 1
        for d in dims:
            total *= d
        bpe = 2 if ftype == 1 else 4
        data_size = total * bpe
        f.read(data_size)
        print(f"  Tensor {t}: '{name}' dims={dims} ftype={ftype} size={data_size}")
