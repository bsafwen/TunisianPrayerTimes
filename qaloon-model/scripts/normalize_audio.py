#!/usr/bin/env python3
from __future__ import annotations
"""
Normalize audio files to a consistent format for model training.

Target format: 16kHz, mono, 16-bit PCM WAV, peak-normalized.

Usage:
    python normalize_audio.py --input data/raw/husary_qaloon/ --output data/processed/husary_qaloon/
    python normalize_audio.py --input data/raw/ --output data/processed/ --recursive
"""

import argparse
import json
import sys
from pathlib import Path
from concurrent.futures import ProcessPoolExecutor, as_completed

import librosa
import numpy as np
import soundfile as sf
from tqdm import tqdm

SCRIPT_DIR = Path(__file__).resolve().parent
DATA_DIR = SCRIPT_DIR.parent / "data"

TARGET_SR = 16000
AUDIO_EXTENSIONS = {".mp3", ".wav", ".ogg", ".flac", ".m4a", ".wma"}


def normalize_single(input_path: Path, output_path: Path) -> dict:
    """Normalize a single audio file. Returns metadata dict."""
    try:
        # Load and resample to 16kHz mono
        audio, sr = librosa.load(str(input_path), sr=TARGET_SR, mono=True)

        # Peak normalize to -1 dB (0.891)
        peak = np.abs(audio).max()
        if peak > 0:
            audio = audio * (0.891 / peak)

        # Trim leading/trailing silence (threshold: -40 dB)
        audio_trimmed, trim_indices = librosa.effects.trim(audio, top_db=40)

        # Don't trim if it removes too much (> 50% of audio)
        if len(audio_trimmed) < len(audio) * 0.5:
            audio_trimmed = audio

        duration = len(audio_trimmed) / TARGET_SR

        # Write as 16-bit PCM WAV
        output_path.parent.mkdir(parents=True, exist_ok=True)
        sf.write(str(output_path), audio_trimmed, TARGET_SR, subtype="PCM_16")

        return {
            "input": str(input_path),
            "output": str(output_path),
            "duration_sec": round(duration, 2),
            "original_sr": sr,
            "status": "ok",
        }

    except Exception as e:
        return {
            "input": str(input_path),
            "output": str(output_path),
            "status": "error",
            "error": str(e),
        }


def find_audio_files(input_dir: Path, recursive: bool = True) -> list[Path]:
    """Find all audio files in a directory."""
    if recursive:
        files = []
        for ext in AUDIO_EXTENSIONS:
            files.extend(input_dir.rglob(f"*{ext}"))
        return sorted(files)
    else:
        return sorted(
            f for f in input_dir.iterdir()
            if f.suffix.lower() in AUDIO_EXTENSIONS
        )


def main():
    parser = argparse.ArgumentParser(
        description="Normalize audio files to 16kHz mono WAV"
    )
    parser.add_argument(
        "--input", type=str, required=True,
        help="Input directory containing audio files",
    )
    parser.add_argument(
        "--output", type=str, required=True,
        help="Output directory for normalized WAV files",
    )
    parser.add_argument(
        "--recursive", action="store_true", default=True,
        help="Search input directory recursively (default: True)",
    )
    parser.add_argument(
        "--workers", type=int, default=4,
        help="Number of parallel workers (default: 4)",
    )
    parser.add_argument(
        "--skip-existing", action="store_true", default=True,
        help="Skip files that already exist in output (default: True)",
    )

    args = parser.parse_args()
    input_dir = Path(args.input)
    output_dir = Path(args.output)

    if not input_dir.exists():
        print(f"ERROR: Input directory not found: {input_dir}")
        sys.exit(1)

    # Find all audio files
    audio_files = find_audio_files(input_dir, args.recursive)
    print(f"Found {len(audio_files)} audio files in {input_dir}")

    if not audio_files:
        print("No audio files found.")
        return

    # Build list of (input, output) pairs
    tasks = []
    for f in audio_files:
        rel = f.relative_to(input_dir)
        out = output_dir / rel.with_suffix(".wav")
        if args.skip_existing and out.exists():
            continue
        tasks.append((f, out))

    print(f"Files to process: {len(tasks)} (skipping {len(audio_files) - len(tasks)} existing)")
    print(f"Output: {output_dir}")
    print()

    if not tasks:
        print("Nothing to do.")
        return

    # Process with parallel workers
    results = []
    with ProcessPoolExecutor(max_workers=args.workers) as executor:
        futures = {executor.submit(normalize_single, inp, out): (inp, out) for inp, out in tasks}

        with tqdm(total=len(tasks), unit="file", desc="Normalizing") as pbar:
            for future in as_completed(futures):
                result = future.result()
                results.append(result)
                if result["status"] == "error":
                    tqdm.write(f"  ERROR: {result['input']} — {result['error']}")
                pbar.update(1)

    # Summary
    ok = [r for r in results if r["status"] == "ok"]
    errors = [r for r in results if r["status"] == "error"]
    total_duration = sum(r.get("duration_sec", 0) for r in ok)

    print()
    print(f"Done! Processed: {len(ok)}, Errors: {len(errors)}")
    print(f"Total audio duration: {total_duration / 3600:.1f} hours")

    # Save processing log
    log_file = output_dir / "normalize_log.json"
    output_dir.mkdir(parents=True, exist_ok=True)
    with open(log_file, "w") as f:
        json.dump({
            "total_files": len(results),
            "successful": len(ok),
            "errors": len(errors),
            "total_duration_hours": round(total_duration / 3600, 2),
            "target_sample_rate": TARGET_SR,
            "error_files": [r["input"] for r in errors],
        }, f, indent=2)


if __name__ == "__main__":
    main()
