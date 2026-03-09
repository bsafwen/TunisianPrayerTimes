#!/usr/bin/env python3
"""Fix positional embedding types from F16 to F32 in GGML model file.
whisper.cpp allocates positional embeddings as GGML_TYPE_F32.
"""
import struct, sys, numpy as np

inp = sys.argv[1]
out = sys.argv[2] if len(sys.argv) > 2 else inp.replace('.bin', '-fixed2.bin')

with open(inp, 'rb') as f:
    data = f.read()
data = bytearray(data)

print(f"Input: {inp} ({len(data)} bytes)")

# Parse header (4 magic + 44 hparams)
magic = struct.unpack_from('<I', data, 0)[0]
assert magic == 0x67676d6c
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

# Rebuild tensor section
output = bytearray(data[:offset])

TENSORS_TO_CONVERT = {
    'encoder.positional_embedding',
    'decoder.positional_embedding',
}

fixes = 0
count = 0
pos = offset
while pos < len(data):
    if pos + 12 > len(data):
        break
    n_dims = struct.unpack_from('<i', data, pos)[0]
    s_len = struct.unpack_from('<i', data, pos + 4)[0]
    ftype = struct.unpack_from('<i', data, pos + 8)[0]

    dpos = pos + 12
    dims = []
    for i in range(n_dims):
        dims.append(struct.unpack_from('<i', data, dpos)[0])
        dpos += 4

    name = data[dpos:dpos + s_len].decode()
    dpos += s_len

    nelements = 1
    for d in dims:
        nelements *= d
    bpe = 2 if ftype == 1 else 4
    tensor_bytes = data[dpos:dpos + nelements * bpe]
    dpos += nelements * bpe

    if name in TENSORS_TO_CONVERT and ftype == 1:
        print(f"  Converting {name}: F16 -> F32 ({nelements} elements, {nelements*2} -> {nelements*4} bytes)")
        # Convert F16 data to F32
        f16_array = np.frombuffer(bytes(tensor_bytes), dtype=np.float16)
        f32_array = f16_array.astype(np.float32)
        tensor_bytes = f32_array.tobytes()
        ftype = 0  # F32
        fixes += 1

    # Write tensor
    output += struct.pack('<i', n_dims)
    output += struct.pack('<i', s_len)
    output += struct.pack('<i', ftype)
    for d in dims:
        output += struct.pack('<i', d)
    output += name.encode()
    output += tensor_bytes

    count += 1
    pos = dpos

print(f"Converted {fixes} tensors out of {count} total")
print(f"Output: {out} ({len(output)} bytes, delta=+{len(output)-len(data)})")

with open(out, 'wb') as f:
    f.write(output)
