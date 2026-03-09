#!/usr/bin/env python3
from __future__ import annotations
"""
Generate statistics about the collected dataset.

Usage:
    python dataset_stats.py
    python dataset_stats.py --manifest data/manifest_train.jsonl
    python dataset_stats.py --audio-dir data/processed/
"""

import argparse
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np

SCRIPT_DIR = Path(__file__).resolve().parent
DATA_DIR = SCRIPT_DIR.parent / "data"


def stats_from_manifest(manifest_path: Path):
    """Compute stats from a JSONL manifest file."""
    samples = []
    with open(manifest_path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                samples.append(json.loads(line))

    if not samples:
        print(f"No samples in {manifest_path}")
        return

    durations = [s.get("duration", 0) for s in samples]
    reciters = Counter(s.get("reciter", "unknown") for s in samples)
    surahs = Counter(s["surah"] for s in samples)
    text_lengths = [len(s.get("text", "")) for s in samples]

    print(f"\n{'='*60}")
    print(f"  Manifest: {manifest_path.name}")
    print(f"{'='*60}")
    print(f"  Samples:          {len(samples)}")
    print(f"  Total duration:   {sum(durations)/3600:.1f} hours ({sum(durations):.0f} sec)")
    print(f"  Avg duration:     {np.mean(durations):.1f}s")
    print(f"  Min duration:     {np.min(durations):.1f}s")
    print(f"  Max duration:     {np.max(durations):.1f}s")
    print(f"  Median duration:  {np.median(durations):.1f}s")
    print(f"  Std duration:     {np.std(durations):.1f}s")
    print()
    print(f"  Avg text length:  {np.mean(text_lengths):.0f} chars")
    print(f"  Surahs covered:   {len(surahs)}/114")
    print()
    print(f"  Reciters ({len(reciters)}):")
    for name, count in reciters.most_common():
        reciter_dur = sum(s["duration"] for s in samples if s.get("reciter") == name)
        print(f"    {name}: {count} samples, {reciter_dur/3600:.1f}h")

    # Duration distribution
    print()
    print("  Duration distribution:")
    bins = [0, 1, 2, 5, 10, 15, 20, 30, 60, float('inf')]
    labels = ["<1s", "1-2s", "2-5s", "5-10s", "10-15s", "15-20s", "20-30s", "30-60s", ">60s"]
    for i in range(len(bins)-1):
        count = sum(1 for d in durations if bins[i] <= d < bins[i+1])
        bar = "█" * (count * 40 // len(durations)) if durations else ""
        print(f"    {labels[i]:>6s}: {count:5d} {bar}")

    # Flag potential issues
    print()
    short = [s for s in samples if s.get("duration", 0) < 0.5]
    long = [s for s in samples if s.get("duration", 0) > 45]
    empty_text = [s for s in samples if not s.get("text", "").strip()]

    if short:
        print(f"  ⚠️  Very short samples (<0.5s): {len(short)}")
    if long:
        print(f"  ⚠️  Very long samples (>45s): {len(long)}")
    if empty_text:
        print(f"  ⚠️  Samples with empty text: {len(empty_text)}")

    return {
        "file": str(manifest_path),
        "samples": len(samples),
        "total_hours": round(sum(durations) / 3600, 2),
        "avg_duration": round(np.mean(durations), 2),
        "reciters": dict(reciters),
        "surahs_covered": len(surahs),
    }


def stats_from_audio_dir(audio_dir: Path):
    """Quick stats by scanning audio files directly."""
    try:
        import soundfile as sf
    except ImportError:
        print("ERROR: soundfile not installed. Run: pip install soundfile")
        sys.exit(1)

    wav_files = sorted(audio_dir.rglob("*.wav"))
    if not wav_files:
        print(f"No WAV files found in {audio_dir}")
        return

    print(f"\nScanning {len(wav_files)} WAV files in {audio_dir}...")

    durations = []
    errors = 0
    by_reciter = defaultdict(list)

    for f in wav_files:
        try:
            info = sf.info(str(f))
            durations.append(info.duration)
            # Guess reciter from path: processed/<reciter>/<surah>/file.wav
            parts = f.relative_to(audio_dir).parts
            if parts:
                by_reciter[parts[0]].append(info.duration)
        except Exception:
            errors += 1

    print(f"\n{'='*60}")
    print(f"  Audio directory: {audio_dir}")
    print(f"{'='*60}")
    print(f"  Files:            {len(wav_files)}")
    print(f"  Readable:         {len(durations)}")
    print(f"  Errors:           {errors}")
    print(f"  Total duration:   {sum(durations)/3600:.1f} hours")
    print(f"  Avg duration:     {np.mean(durations):.1f}s")

    if by_reciter:
        print(f"\n  By reciter:")
        for name in sorted(by_reciter.keys()):
            durs = by_reciter[name]
            print(f"    {name}: {len(durs)} files, {sum(durs)/3600:.1f}h")


def main():
    parser = argparse.ArgumentParser(description="Dataset statistics")
    parser.add_argument("--manifest", type=str, nargs="*", help="Manifest JSONL file(s)")
    parser.add_argument("--audio-dir", type=str, help="Processed audio directory to scan")
    parser.add_argument("--all", action="store_true", help="Show stats for all manifests in data/")

    args = parser.parse_args()

    if args.all or (not args.manifest and not args.audio_dir):
        # Show stats for all manifests
        manifests = sorted(DATA_DIR.glob("manifest_*.jsonl"))
        if manifests:
            all_stats = []
            for m in manifests:
                s = stats_from_manifest(m)
                if s:
                    all_stats.append(s)

            if all_stats:
                total_samples = sum(s["samples"] for s in all_stats)
                total_hours = sum(s["total_hours"] for s in all_stats)
                print(f"\n{'='*60}")
                print(f"  TOTAL: {total_samples} samples, {total_hours:.1f} hours")
                print(f"{'='*60}")
        else:
            print("No manifest files found. Run build_manifest.py first.")

        # Also show audio dir stats if it exists
        processed = DATA_DIR / "processed"
        if processed.exists():
            stats_from_audio_dir(processed)

    if args.manifest:
        for m in args.manifest:
            stats_from_manifest(Path(m))

    if args.audio_dir:
        stats_from_audio_dir(Path(args.audio_dir))


if __name__ == "__main__":
    main()
