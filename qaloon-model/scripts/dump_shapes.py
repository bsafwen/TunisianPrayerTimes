#!/usr/bin/env python3
"""Dump all tensor names and shapes from a GGML model file."""
import struct, sys

f = open(sys.argv[1], 'rb')
magic = struct.unpack('<I', f.read(4))[0]
hparams = struct.unpack('<11i', f.read(44))
n_mels = hparams[9]
n_fft = 201

# Skip mel filters
f.seek(4 + 44 + 8 + n_mels * n_fft * 4)

# Skip tokens
n_vocab = struct.unpack('<I', f.read(4))[0]
for i in range(n_vocab):
    tlen = struct.unpack('<I', f.read(4))[0]
    f.read(tlen)

print(f"Tensors start at offset: {f.tell()}")

count = 0
while True:
    data = f.read(4)
    if len(data) < 4:
        break
    n_dims = struct.unpack('<i', data)[0]
    s_len = struct.unpack('<i', f.read(4))[0]
    ftype = struct.unpack('<i', f.read(4))[0]
    dims = [struct.unpack('<i', f.read(4))[0] for _ in range(n_dims)]
    name = f.read(s_len).decode()
    
    nelements = 1
    for d in dims:
        nelements *= d
    bpe = 2 if ftype == 1 else 4
    f.read(nelements * bpe)
    
    print(f"  {name}: n_dims={n_dims} dims={dims} ftype={ftype}")
    count += 1

print(f"\nTotal tensors: {count}")
f.close()
