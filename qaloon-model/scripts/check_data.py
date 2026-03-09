#!/usr/bin/env python3
"""Check audio data for corruption, NaN values, or other issues."""
import json
import soundfile as sf
import numpy as np
from pathlib import Path

DATA_DIR = Path(__file__).resolve().parent.parent / "data"
manifest = DATA_DIR / "manifest_train.jsonl"

bad_files = []
nan_features = []
total = 0
checked = 0
max_check = 500

with open(manifest) as f:
    for line in f:
        sample = json.loads(line.strip())
        audio_path = DATA_DIR / sample["audio"]
        total += 1
        if checked >= max_check:
            continue  # keep counting total
        try:
            audio, sr = sf.read(str(audio_path))
            if np.any(np.isnan(audio)) or np.any(np.isinf(audio)):
                nan_features.append(str(audio_path))
            if len(audio) == 0:
                bad_files.append(("empty", str(audio_path)))
            if sr != 16000:
                bad_files.append(("bad_sr", str(audio_path), sr))
            checked += 1
        except Exception as e:
            bad_files.append(("error", str(audio_path), str(e)))
            checked += 1

print(f"Checked {checked}/{total} files")
print(f"Bad files: {len(bad_files)}")
for b in bad_files[:20]:
    print(f"  {b}")
print(f"NaN/Inf audio: {len(nan_features)}")
for n in nan_features[:20]:
    print(f"  {n}")

# Also check mel spectrogram computation for a few samples
from transformers import WhisperProcessor
processor = WhisperProcessor.from_pretrained("openai/whisper-small")

nan_mels = 0
with open(manifest) as f:
    for i, line in enumerate(f):
        if i >= 50:
            break
        sample = json.loads(line.strip())
        audio_path = DATA_DIR / sample["audio"]
        audio, sr = sf.read(str(audio_path))
        if len(audio) > sr * 30:
            audio = audio[:sr * 30]
        features = processor.feature_extractor(audio, sampling_rate=sr, return_tensors="np").input_features[0]
        if np.any(np.isnan(features)) or np.any(np.isinf(features)):
            nan_mels += 1
            print(f"  NaN mel: {audio_path}, audio_len={len(audio)}, audio_range=[{audio.min():.4f}, {audio.max():.4f}]")

print(f"\nNaN mel spectrograms: {nan_mels}/50")
