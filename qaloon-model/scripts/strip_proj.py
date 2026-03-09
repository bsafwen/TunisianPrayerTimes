#!/usr/bin/env python3
"""Remove decoder.proj.weight from an already-fixed GGML model file."""
import struct
import sys
import os

path_in = sys.argv[1]
path_out = sys.argv[2]

with open(path_in, "rb") as f:
    # Header: magic + 11 hparams = 48 bytes
    header = f.read(48)

    # Mel filters
    mel_header = f.read(8)
    mel_r, mel_c = struct.unpack("ii", mel_header)
    mel_data = f.read(mel_r * mel_c * 4)

    # Token count + tokens
    n_tok_bytes = f.read(4)
    n_tok = struct.unpack("i", n_tok_bytes)[0]
    tok_chunks = [n_tok_bytes]
    for _ in range(n_tok):
        tl_bytes = f.read(4)
        tl = struct.unpack("i", tl_bytes)[0]
        td = f.read(tl)
        tok_chunks.append(tl_bytes + td)
    tok_data = b"".join(tok_chunks)

    # Read tensors, skip decoder.proj.weight
    tensor_chunks = []
    skip_count = 0
    keep_count = 0

    while True:
        d = f.read(4)
        if len(d) < 4:
            break
        n_dims = struct.unpack("i", d)[0]
        name_len_b = f.read(4)
        name_len = struct.unpack("i", name_len_b)[0]
        ftype_b = f.read(4)
        ftype = struct.unpack("i", ftype_b)[0]

        dims_bytes = b""
        total = 1
        for _ in range(n_dims):
            db = f.read(4)
            dims_bytes += db
            total *= struct.unpack("i", db)[0]

        name_bytes = f.read(name_len)
        name = name_bytes.decode("utf-8")

        bpe = 2 if ftype == 1 else 4
        tensor_data = f.read(total * bpe)

        if name == "decoder.proj.weight":
            print(f"SKIPPING: {name} ({total * bpe} bytes)")
            skip_count += 1
            continue

        chunk = d + name_len_b + ftype_b + dims_bytes + name_bytes + tensor_data
        tensor_chunks.append(chunk)
        keep_count += 1

print(f"Kept {keep_count} tensors, skipped {skip_count}")

with open(path_out, "wb") as fout:
    fout.write(header)
    fout.write(mel_header)
    fout.write(mel_data)
    fout.write(tok_data)
    for chunk in tensor_chunks:
        fout.write(chunk)

print(f"Output: {os.path.getsize(path_out) / 1e6:.1f} MB")
