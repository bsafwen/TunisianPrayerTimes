#!/usr/bin/env python3
from __future__ import annotations
"""
Build train/val/test manifest files (JSONL) from processed audio.

Each line in the manifest maps an audio file to its transcript text.
Split strategy: split by reciter to test generalization to unseen voices.

Usage:
    python build_manifest.py --processed-dir data/processed/ --output-dir data/
    python build_manifest.py --processed-dir data/processed/ --test-reciter husary_qaloon
"""

import argparse
import json
import random
import re
import sys
from pathlib import Path

import soundfile as sf
from tqdm import tqdm

SCRIPT_DIR = Path(__file__).resolve().parent
DATA_DIR = SCRIPT_DIR.parent / "data"
METADATA_DIR = DATA_DIR / "metadata"

# Tashkeel (Arabic diacritic) Unicode ranges to strip
_TASHKEEL_RE = re.compile(
    "[\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06DC\u06DF-\u06E4\u06E7\u06E8\u06EA-\u06ED]"
)


def normalize_uthmani(text: str) -> str:
    """Convert Uthmani Quran script to standard Arabic for Whisper training.

    Whisper's BPE tokenizer was pretrained on standard Arabic, so training
    labels should match that orthography.
    """
    # Uthmani: وٰ (waw + superscript alif) → ا  (e.g. الصلوٰة → الصلاة)
    text = text.replace("\u0648\u0670", "\u0627")
    # Superscript alif over consonant → ا  (e.g. رزقنٰهم → رزقناهم)
    text = text.replace("\u0670", "\u0627")
    # Alif wasla → regular alif  (e.g. ٱلذين → الذين)
    text = text.replace("\u0671", "\u0627")
    # Strip tashkeel (diacritics)
    text = _TASHKEEL_RE.sub("", text)
    # Hamza normalizations
    text = text.replace("\u0622", "\u0627")  # آ → ا
    text = text.replace("\u0623", "\u0627")  # أ → ا
    text = text.replace("\u0625", "\u0627")  # إ → ا
    # Taa marbuta → haa
    text = text.replace("\u0629", "\u0647")  # ة → ه
    # Collapse whitespace
    text = " ".join(text.split())
    return text.strip()


def load_quran_text() -> dict[str, str]:
    """Load Quran text indexed by 'SSS_AAA' key."""
    qaloon_file = METADATA_DIR / "quran_qaloon.json"
    hafs_file = METADATA_DIR / "quran_hafs_uthmani.json"
    text_file = qaloon_file if qaloon_file.exists() else hafs_file

    if not text_file.exists():
        print("ERROR: No Quran text found. Run build_qaloon_text.py first.")
        sys.exit(1)

    with open(text_file, encoding="utf-8") as f:
        ayahs = json.load(f)

    return {f"{a['surah']:03d}_{a['ayah']:03d}": normalize_uthmani(a["text"]) for a in ayahs}


def parse_ayah_filename(filename: str) -> tuple[int, int, int] | None:
    """Parse surah, ayah, and part from filename.

    Supports:
      002005.wav        → (2, 5, 0)   original full ayah
      002005_01.wav     → (2, 5, 1)   split sub-segment part 1
      002005_orig.wav   → None         backup of split original (skip)
    """
    stem = Path(filename).stem
    # Skip backup files from split_long_segments.py
    if stem.endswith("_orig"):
        return None
    # Sub-segment: SSSAAA_PP
    m = re.match(r"^(\d{3})(\d{3})_(\d{2})$", stem)
    if m:
        surah, ayah, part = int(m.group(1)), int(m.group(2)), int(m.group(3))
        if 1 <= surah <= 114 and ayah >= 0:
            return (surah, ayah, part)
    # Original: SSSAAA
    if len(stem) == 6 and stem.isdigit():
        surah = int(stem[:3])
        ayah = int(stem[3:])
        if 1 <= surah <= 114 and ayah >= 0:
            return (surah, ayah, 0)
    return None


def scan_processed_dir(processed_dir: Path) -> dict[str, list[dict]]:
    """Scan processed directory and build per-reciter file lists."""
    reciters = {}

    for reciter_dir in sorted(processed_dir.iterdir()):
        if not reciter_dir.is_dir() or reciter_dir.name.startswith("."):
            continue

        reciter_name = reciter_dir.name
        files = []

        # Look for WAV files in surah subdirectories or directly
        wav_files = sorted(reciter_dir.rglob("*.wav"))

        # Load split text sidecars (from split_long_segments.py)
        split_texts = {}  # stem -> text
        for sidecar in reciter_dir.rglob("split_texts.json"):
            with open(sidecar, encoding="utf-8") as f:
                raw = json.load(f)
                split_texts.update({k: normalize_uthmani(v) for k, v in raw.items()})

        for wav_path in wav_files:
            parsed = parse_ayah_filename(wav_path.name)
            if parsed is None:
                continue

            surah, ayah, part = parsed
            if ayah == 0:  # Skip bismillah files
                continue

            try:
                info = sf.info(str(wav_path))
                duration = info.duration
            except Exception:
                duration = 0.0

            entry = {
                "audio": str(wav_path.relative_to(DATA_DIR)),
                "surah": surah,
                "ayah": ayah,
                "duration": round(duration, 2),
                "reciter": reciter_name,
            }
            # For split sub-segments, embed text directly (from sidecar)
            stem = wav_path.stem
            if part > 0 and stem in split_texts:
                entry["text"] = split_texts[stem]

            files.append(entry)

        if files:
            reciters[reciter_name] = files
            print(f"  {reciter_name}: {len(files)} files")

    return reciters


def build_manifests(
    reciters: dict[str, list[dict]],
    quran_text: dict[str, str],
    output_dir: Path,
    test_reciter: str | None = None,
    val_ratio: float = 0.05,
    seed: int = 42,
):
    """Build train/val/test JSONL manifests."""
    random.seed(seed)

    train_samples = []
    val_samples = []
    test_samples = []

    for reciter_name, files in reciters.items():
        # Add text to each file entry (split segments already have text)
        for f in files:
            if "text" not in f:
                key = f"{f['surah']:03d}_{f['ayah']:03d}"
                text = quran_text.get(key, "")
                if not text:
                    continue
                f["text"] = text

        valid_files = [f for f in files if "text" in f]

        if test_reciter and reciter_name == test_reciter:
            # This entire reciter goes to test
            test_samples.extend(valid_files)
            print(f"  {reciter_name} → TEST ({len(valid_files)} samples)")
        else:
            # Split into train/val
            random.shuffle(valid_files)
            val_count = max(1, int(len(valid_files) * val_ratio))
            val_samples.extend(valid_files[:val_count])
            train_samples.extend(valid_files[val_count:])
            print(f"  {reciter_name} → TRAIN ({len(valid_files) - val_count}) + VAL ({val_count})")

    # If no test reciter specified, take 5% of all data for test
    if not test_reciter and train_samples:
        random.shuffle(train_samples)
        test_count = max(1, int(len(train_samples) * 0.05))
        test_samples = train_samples[:test_count]
        train_samples = train_samples[test_count:]

    # Write manifests
    output_dir.mkdir(parents=True, exist_ok=True)

    for name, samples in [("train", train_samples), ("val", val_samples), ("test", test_samples)]:
        manifest_path = output_dir / f"manifest_{name}.jsonl"
        with open(manifest_path, "w", encoding="utf-8") as f:
            for s in samples:
                f.write(json.dumps(s, ensure_ascii=False) + "\n")
        print(f"\n  {manifest_path.name}: {len(samples)} samples")

    # Summary
    total = len(train_samples) + len(val_samples) + len(test_samples)
    total_duration = sum(s.get("duration", 0) for s in train_samples + val_samples + test_samples)

    summary = {
        "total_samples": total,
        "train_samples": len(train_samples),
        "val_samples": len(val_samples),
        "test_samples": len(test_samples),
        "total_duration_hours": round(total_duration / 3600, 2),
        "train_duration_hours": round(sum(s.get("duration", 0) for s in train_samples) / 3600, 2),
        "val_duration_hours": round(sum(s.get("duration", 0) for s in val_samples) / 3600, 2),
        "test_duration_hours": round(sum(s.get("duration", 0) for s in test_samples) / 3600, 2),
        "reciters": list(reciters.keys()),
        "test_reciter": test_reciter,
    }

    with open(output_dir / "manifest_summary.json", "w") as f:
        json.dump(summary, f, indent=2)

    print(f"\nTotal: {total} samples, {total_duration / 3600:.1f} hours")


def main():
    parser = argparse.ArgumentParser(
        description="Build train/val/test manifests from processed audio"
    )
    parser.add_argument(
        "--processed-dir", type=str,
        default=str(DATA_DIR / "segments"),
        help="Directory containing per-ayah audio segments (per-reciter/surah subdirs)",
    )
    parser.add_argument(
        "--output-dir", type=str,
        default=str(DATA_DIR),
        help="Output directory for manifest JSONL files",
    )
    parser.add_argument(
        "--test-reciter", type=str, default=None,
        help="Hold out this reciter entirely for testing",
    )
    parser.add_argument(
        "--val-ratio", type=float, default=0.05,
        help="Fraction of train reciters' data for validation (default: 0.05)",
    )
    parser.add_argument(
        "--seed", type=int, default=42,
        help="Random seed for reproducibility",
    )

    args = parser.parse_args()
    processed_dir = Path(args.processed_dir)
    output_dir = Path(args.output_dir)

    if not processed_dir.exists():
        print(f"ERROR: {processed_dir} not found. Run normalize_audio.py first.")
        sys.exit(1)

    print("Scanning processed audio...")
    reciters = scan_processed_dir(processed_dir)

    if not reciters:
        print("No processed audio found.")
        return

    print(f"\nFound {len(reciters)} reciters, {sum(len(v) for v in reciters.values())} total files")

    quran_text = load_quran_text()
    print(f"Loaded {len(quran_text)} ayah transcripts\n")

    print("Building manifests...")
    build_manifests(reciters, quran_text, output_dir, args.test_reciter, args.val_ratio, args.seed)


if __name__ == "__main__":
    main()
