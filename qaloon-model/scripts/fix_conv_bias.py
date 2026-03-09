#!/usr/bin/env python3
"""Fix conv bias tensor shapes from 1D [768] to 2D [1, 768] in GGML model file.
whisper.cpp expects conv biases as 2D tensors with shape [1, n_audio_state].
"""
import struct, sys, shutil

inp = sys.argv[1]
out = sys.argv[2] if len(sys.argv) > 2 else inp.replace('.bin', '-fixed.bin')

with open(inp, 'rb') as f:
    data = bytearray(f.read())

print(f"Input: {inp} ({len(data)} bytes)")

# Parse header
magic = struct.unpack_from('<I', data, 0)[0]
assert magic == 0x67676d6c, f"Bad magic: {hex(magic)}"
hparams = struct.unpack_from('<11i', data, 4)
n_mels = hparams[9]
n_fft = 201

# Skip mel filters
offset = 4 + 44 + 8 + n_mels * n_fft * 4

# Skip tokens
n_vocab = struct.unpack_from('<I', data, offset)[0]
offset += 4
for i in range(n_vocab):
    tlen = struct.unpack_from('<I', data, offset)[0]
    offset += 4 + tlen

print(f"Tensors start at offset: {offset}")

# Find conv bias tensors and fix them
# We need to rebuild the tensor section because changing n_dims from 1 to 2
# adds 4 bytes per tensor (the extra dimension value)
output = bytearray(data[:offset])  # Copy everything up to tensors

fixes = 0
count = 0
pos = offset
while pos < len(data):
    if pos + 12 > len(data):
        break
    n_dims = struct.unpack_from('<i', data, pos)[0]
    s_len = struct.unpack_from('<i', data, pos + 4)[0]
    ftype = struct.unpack_from('<i', data, pos + 8)[0]
    
    dims = []
    dpos = pos + 12
    for i in range(n_dims):
        dims.append(struct.unpack_from('<i', data, dpos)[0])
        dpos += 4
    
    name = data[dpos:dpos + s_len].decode()
    dpos += s_len
    
    nelements = 1
    for d in dims:
        nelements *= d
    bpe = 2 if ftype == 1 else 4
    tensor_data = data[dpos:dpos + nelements * bpe]
    dpos += nelements * bpe
    
    # Fix conv biases: change from 1D [768] to 2D [1, 768]
    if name in ('encoder.conv1.bias', 'encoder.conv2.bias'):
        print(f"  Fixing {name}: {n_dims}D {dims} -> 2D [1, {dims[0]}]")
        new_dims = [1, dims[0]]
        n_dims = 2
        dims = new_dims
        fixes += 1
    
    # Write tensor header + data
    output += struct.pack('<i', n_dims)
    output += struct.pack('<i', s_len)
    output += struct.pack('<i', ftype)
    for d in dims:
        output += struct.pack('<i', d)
    output += name.encode()
    output += tensor_data
    
    count += 1
    pos = dpos

print(f"Fixed {fixes} tensors out of {count} total")
print(f"Output: {out} ({len(output)} bytes, delta={len(output)-len(data)})")

with open(out, 'wb') as f:
    f.write(output)
