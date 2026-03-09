#!/usr/bin/env python3
from __future__ import annotations
"""
Validate segmented audio files against expected ayah counts.

Checks:
- Correct number of files per surah
- No zero-byte or corrupt files
- Duration sanity (not too short or too long)
- Audio can be loaded without errors
- Optional: spot-check random ayahs for manual review

Usage:
    python validate_segments.py --dir data/processed/husary_qaloon/
    python validate_segments.py --dir data/processed/ --spot-check 10
"""

import argparse
import json
import random
import sys
from pathlib import Path

import soundfile as sf
from tqdm import tqdm

SCRIPT_DIR = Path(__file__).resolve().parent
DATA_DIR = SCRIPT_DIR.parent / "data"
METADATA_DIR = DATA_DIR / "metadata"


def load_ayah_counts() -> dict[int, int]:
    with open(METADATA_DIR / "surah_ayah_counts.json") as f:
        data = json.load(f)
    return {int(k): v["ayahs"] for k, v in data.items()}


def validate_reciter_dir(reciter_dir: Path, ayah_counts: dict[int, int]) -> dict:
    """Validate a single reciter's processed audio directory."""
    issues = []
    stats = {
        "reciter": reciter_dir.name,
        "total_files": 0,
        "valid_files": 0,
        "corrupt_files": 0,
        "zero_byte_files": 0,
        "too_short": 0,
        "too_long": 0,
        "missing_surahs": [],
        "wrong_count_surahs": [],
    }

    # Check each surah directory
    for surah_num in range(1, 115):
        surah_dir = reciter_dir / f"{surah_num:03d}"
        expected_count = ayah_counts[surah_num]

        if not surah_dir.exists():
            # Check if files are directly in reciter_dir with surah prefix
            direct_files = sorted(reciter_dir.glob(f"{surah_num:03d}*.wav"))
            if not direct_files:
                stats["missing_surahs"].append(surah_num)
                continue
            wav_files = direct_files
        else:
            wav_files = sorted(surah_dir.glob("*.wav"))

        # Filter to actual ayah files (not bismillah 000)
        ayah_files = [f for f in wav_files if not f.stem.endswith("000")]
        stats["total_files"] += len(ayah_files)

        if len(ayah_files) != expected_count:
            stats["wrong_count_surahs"].append({
                "surah": surah_num,
                "expected": expected_count,
                "got": len(ayah_files),
            })

        # Validate each file
        for f in ayah_files:
            if f.stat().st_size == 0:
                stats["zero_byte_files"] += 1
                issues.append(f"Zero-byte file: {f}")
                continue

            try:
                info = sf.info(str(f))
                duration = info.duration

                if duration < 0.3:
                    stats["too_short"] += 1
                    issues.append(f"Too short ({duration:.1f}s): {f}")
                elif duration > 60:
                    stats["too_long"] += 1
                    issues.append(f"Too long ({duration:.1f}s): {f}")
                else:
                    stats["valid_files"] += 1

            except Exception as e:
                stats["corrupt_files"] += 1
                issues.append(f"Corrupt/unreadable: {f} — {e}")

    stats["issues"] = issues
    return stats


def print_report(stats: dict):
    """Print validation report for a reciter."""
    print(f"\n{'='*60}")
    print(f"  Reciter: {stats['reciter']}")
    print(f"{'='*60}")
    print(f"  Total files:      {stats['total_files']}")
    print(f"  Valid:            {stats['valid_files']}")
    print(f"  Corrupt:          {stats['corrupt_files']}")
    print(f"  Zero-byte:        {stats['zero_byte_files']}")
    print(f"  Too short (<0.3s):{stats['too_short']}")
    print(f"  Too long (>60s):  {stats['too_long']}")
    print(f"  Missing surahs:   {len(stats['missing_surahs'])}")

    if stats["wrong_count_surahs"]:
        print(f"\n  Surahs with wrong ayah count ({len(stats['wrong_count_surahs'])}):")
        for s in stats["wrong_count_surahs"][:10]:
            print(f"    Surah {s['surah']:3d}: expected {s['expected']}, got {s['got']}")
        if len(stats["wrong_count_surahs"]) > 10:
            print(f"    ... and {len(stats['wrong_count_surahs']) - 10} more")

    if stats["issues"]:
        print(f"\n  Issues ({len(stats['issues'])}):")
        for issue in stats["issues"][:20]:
            print(f"    - {issue}")
        if len(stats["issues"]) > 20:
            print(f"    ... and {len(stats['issues']) - 20} more")

    # Pass/fail
    total_issues = stats["corrupt_files"] + stats["zero_byte_files"]
    if total_issues == 0 and not stats["missing_surahs"]:
        print(f"\n  ✅ PASS — All files valid")
    else:
        print(f"\n  ❌ ISSUES FOUND — {total_issues} file issues, {len(stats['missing_surahs'])} missing surahs")


def spot_check(processed_dir: Path, count: int = 10):
    """Select random ayah files for manual listening review."""
    all_wavs = sorted(processed_dir.rglob("*.wav"))
    if not all_wavs:
        print("No WAV files found for spot checking.")
        return

    sample = random.sample(all_wavs, min(count, len(all_wavs)))

    print(f"\n{'='*60}")
    print(f"  Spot Check — {len(sample)} random files for manual review")
    print(f"{'='*60}")
    print(f"  Play these files and verify they match the expected ayah:\n")

    quran_text = {}
    qaloon_file = METADATA_DIR / "quran_qaloon.json"
    hafs_file = METADATA_DIR / "quran_hafs_uthmani.json"
    text_file = qaloon_file if qaloon_file.exists() else hafs_file
    if text_file.exists():
        with open(text_file, encoding="utf-8") as f:
            for a in json.load(f):
                quran_text[f"{a['surah']:03d}{a['ayah']:03d}"] = a["text"]

    for i, wav in enumerate(sample, 1):
        try:
            info = sf.info(str(wav))
            dur = info.duration
        except Exception:
            dur = 0

        key = wav.stem  # e.g., "002005"
        text = quran_text.get(key, "(text not found)")
        reciter = wav.parent.parent.name if wav.parent.name.isdigit() else wav.parent.name

        print(f"  [{i:2d}] {wav}")
        print(f"       Reciter: {reciter}, Duration: {dur:.1f}s")
        print(f"       Expected: {text[:80]}{'...' if len(text) > 80 else ''}")
        print()


def main():
    parser = argparse.ArgumentParser(description="Validate segmented audio files")
    parser.add_argument(
        "--dir", type=str, required=True,
        help="Directory to validate (reciter dir or parent processed dir)",
    )
    parser.add_argument(
        "--spot-check", type=int, default=0,
        help="Number of random files to select for manual review",
    )

    args = parser.parse_args()
    target_dir = Path(args.dir)
    ayah_counts = load_ayah_counts()

    if not target_dir.exists():
        print(f"ERROR: {target_dir} not found")
        sys.exit(1)

    # Check if this is a reciter dir (has surah subdirs) or a parent dir
    surah_dirs = [d for d in target_dir.iterdir() if d.is_dir() and d.name.isdigit()]

    if surah_dirs:
        # This is a single reciter dir
        stats = validate_reciter_dir(target_dir, ayah_counts)
        print_report(stats)
    else:
        # This is the parent processed/ dir — validate each reciter
        reciter_dirs = [d for d in target_dir.iterdir() if d.is_dir() and not d.name.startswith(".")]
        if not reciter_dirs:
            print("No reciter directories found.")
            return

        for rd in sorted(reciter_dirs):
            stats = validate_reciter_dir(rd, ayah_counts)
            print_report(stats)

    if args.spot_check > 0:
        spot_check(target_dir, args.spot_check)


if __name__ == "__main__":
    main()
