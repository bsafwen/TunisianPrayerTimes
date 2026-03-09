#!/usr/bin/env python3
"""Extract all tensor names from a GGML model file."""
import struct, sys

path = sys.argv[1]
with open(path, "rb") as f:
    f.read(4)  # magic
    f.read(44) # 11 hparams
    mel_r = struct.unpack("i", f.read(4))[0]
    mel_c = struct.unpack("i", f.read(4))[0]
    f.read(mel_r * mel_c * 4)
    n_tok = struct.unpack("i", f.read(4))[0]
    for _ in range(n_tok):
        tl = struct.unpack("i", f.read(4))[0]
        f.read(tl)

    names = []
    while True:
        d = f.read(4)
        if len(d) < 4:
            break
        n_dims = struct.unpack("i", d)[0]
        name_len = struct.unpack("i", f.read(4))[0]
        ftype = struct.unpack("i", f.read(4))[0]
        dims = [struct.unpack("i", f.read(4))[0] for _ in range(n_dims)]
        name = f.read(name_len).decode("utf-8")
        total = 1
        for dd in dims:
            total *= dd
        bpe = 2 if ftype == 1 else 4
        f.read(total * bpe)
        names.append(name)

for n in sorted(names):
    print(n)
print(f"\nTotal: {len(names)} tensors")
