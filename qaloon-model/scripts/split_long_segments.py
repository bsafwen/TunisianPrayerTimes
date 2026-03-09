#!/usr/bin/env python3
from __future__ import annotations
"""
Split per-ayah segments that exceed MAX_DURATION into sub-segments.

For each WAV file longer than MAX_DURATION (default: 30s):
  1. Detect silence boundaries within the audio
  2. Split at silence points so each sub-segment is ≤ MAX_DURATION
  3. Assign a proportional slice of the ayah text to each sub-segment
  4. Write sub-segment files as SSSAAA_01.wav, SSSAAA_02.wav, etc.
  5. Keep the original file (renamed SSSAAA_orig.wav) as a backup

This recovers the ~10% of training data that would otherwise be dropped
because Whisper's 30s input window cannot handle them.

Usage:
    python split_long_segments.py --segments-dir data/segments/
    python split_long_segments.py --segments-dir data/segments/ --max-duration 25
    python split_long_segments.py --segments-dir data/segments/ --dry-run
"""

import argparse
import json
import re
import sys
from pathlib import Path

import librosa
import numpy as np
import soundfile as sf

SCRIPT_DIR = Path(__file__).resolve().parent
DATA_DIR = SCRIPT_DIR.parent / "data"
METADATA_DIR = DATA_DIR / "metadata"

TARGET_SR = 16000
DEFAULT_MAX_DURATION = 30  # Whisper's input window


def load_quran_text() -> dict[str, str]:
    """Load Quran text indexed by 'SSS_AAA' key."""
    qaloon_file = METADATA_DIR / "quran_qaloon.json"
    hafs_file = METADATA_DIR / "quran_hafs_uthmani.json"
    text_file = qaloon_file if qaloon_file.exists() else hafs_file

    if not text_file.exists():
        print("ERROR: No Quran text found.")
        sys.exit(1)

    with open(text_file, encoding="utf-8") as f:
        ayahs = json.load(f)

    return {f"{a['surah']:03d}_{a['ayah']:03d}": a["text"] for a in ayahs}


def detect_silence_points(audio: np.ndarray, sr: int,
                          min_silence_ms: int = 200,
                          threshold_db: float = -35) -> list[float]:
    """Find silence midpoints in audio (in seconds)."""
    hop_length = int(sr * 0.01)  # 10ms frames
    rms = librosa.feature.rms(y=audio, hop_length=hop_length)[0]
    rms_db = librosa.power_to_db(rms ** 2, ref=np.max)

    is_silent = rms_db < threshold_db
    min_frames = int(min_silence_ms / 10)

    points = []
    count = 0
    for i, silent in enumerate(is_silent):
        if silent:
            count += 1
        else:
            if count >= min_frames:
                mid_frame = i - count // 2
                points.append(mid_frame * hop_length / sr)
            count = 0
    return points


def find_split_points(duration: float, silence_points: list[float],
                      max_dur: float) -> list[float]:
    """
    Choose split points from silence_points so that every resulting
    sub-segment is ≤ max_dur. Uses a greedy approach: push the boundary
    as late as possible while staying within the limit.
    """
    splits = []
    current_start = 0.0

    while current_start + max_dur < duration:
        # Find the latest silence point that keeps this chunk ≤ max_dur
        deadline = current_start + max_dur
        candidates = [sp for sp in silence_points
                      if current_start + 1.0 < sp <= deadline]

        if candidates:
            best = candidates[-1]  # latest silence before deadline
        else:
            # No silence found — hard-cut at max_dur (rare for Quran recitation)
            best = deadline

        splits.append(best)
        current_start = best

    return splits


def split_text_proportionally(text: str, boundaries: list[float]) -> list[str]:
    """
    Split Arabic text into N parts proportional to the duration of each
    sub-segment. Cuts at word boundaries.
    """
    words = text.split()
    n_parts = len(boundaries) - 1
    if n_parts <= 1:
        return [text]

    total_dur = boundaries[-1] - boundaries[0]
    if total_dur <= 0:
        return [text]

    parts = []
    word_idx = 0
    for i in range(n_parts):
        seg_dur = boundaries[i + 1] - boundaries[i]
        frac = seg_dur / total_dur
        # Words to assign to this segment (at least 1)
        n_words = max(1, round(frac * len(words)))

        if i == n_parts - 1:
            # Last segment gets remaining words
            part_words = words[word_idx:]
        else:
            part_words = words[word_idx:word_idx + n_words]
            word_idx += n_words

        parts.append(" ".join(part_words))

    return parts


def process_segments_dir(segments_dir: Path, max_duration: float,
                         dry_run: bool = False) -> dict:
    """Scan all segments, split those exceeding max_duration."""
    quran_text = load_quran_text()

    stats = {"scanned": 0, "long": 0, "split_into": 0, "errors": 0}

    for reciter_dir in sorted(segments_dir.iterdir()):
        if not reciter_dir.is_dir() or reciter_dir.name.startswith("."):
            continue

        for surah_dir in sorted(reciter_dir.iterdir()):
            if not surah_dir.is_dir():
                continue

            # Find original ayah WAVs (skip already-split files like 002001_01.wav)
            wav_files = sorted(
                f for f in surah_dir.glob("*.wav")
                if re.match(r"^\d{6}\.wav$", f.name)
            )

            for wav_path in wav_files:
                stats["scanned"] += 1
                try:
                    info = sf.info(str(wav_path))
                except Exception:
                    continue

                if info.duration <= max_duration:
                    continue

                stats["long"] += 1
                stem = wav_path.stem  # e.g. "002275"
                surah = int(stem[:3])
                ayah = int(stem[3:])
                key = f"{surah:03d}_{ayah:03d}"
                text = quran_text.get(key, "")

                if dry_run:
                    n_parts = max(2, int(info.duration / max_duration) + 1)
                    stats["split_into"] += n_parts
                    print(f"  [DRY] {reciter_dir.name}/{stem}.wav "
                          f"({info.duration:.1f}s) → ~{n_parts} parts")
                    continue

                # Load audio
                audio, sr = sf.read(str(wav_path))
                duration = len(audio) / sr

                # Find split points
                silence_pts = detect_silence_points(audio, sr)
                splits = find_split_points(duration, silence_pts, max_duration)

                if not splits:
                    # Somehow no splits needed (rounding)
                    continue

                boundaries = [0.0] + splits + [duration]
                n_parts = len(boundaries) - 1

                # Split text
                text_parts = split_text_proportionally(text, boundaries)

                # Write sub-segments and record text mapping
                split_text_map = {}
                for part_idx in range(n_parts):
                    start_sample = int(boundaries[part_idx] * sr)
                    end_sample = int(boundaries[part_idx + 1] * sr)
                    sub_audio = audio[start_sample:end_sample]

                    if len(sub_audio) < sr * 0.3:
                        continue

                    part_name = f"{stem}_{part_idx + 1:02d}.wav"
                    sf.write(str(surah_dir / part_name), sub_audio, sr,
                             subtype="PCM_16")
                    # Map filename to its text slice
                    part_stem = f"{stem}_{part_idx + 1:02d}"
                    if part_idx < len(text_parts):
                        split_text_map[part_stem] = text_parts[part_idx]

                # Save text mapping as sidecar JSON
                sidecar = surah_dir / "split_texts.json"
                existing = {}
                if sidecar.exists():
                    with open(sidecar, encoding="utf-8") as f:
                        existing = json.load(f)
                existing.update(split_text_map)
                with open(sidecar, "w", encoding="utf-8") as f:
                    json.dump(existing, f, ensure_ascii=False, indent=2)

                stats["split_into"] += n_parts

                # Rename original to _orig so build_manifest doesn't pick it up
                orig_name = surah_dir / f"{stem}_orig.wav"
                wav_path.rename(orig_name)

                print(f"  {reciter_dir.name}/{stem}.wav "
                      f"({duration:.1f}s) → {n_parts} parts")

    return stats


def main():
    parser = argparse.ArgumentParser(
        description="Split audio segments longer than Whisper's 30s window"
    )
    parser.add_argument(
        "--segments-dir", type=str,
        default=str(DATA_DIR / "segments"),
        help="Directory containing per-reciter/surah segments",
    )
    parser.add_argument(
        "--max-duration", type=float, default=DEFAULT_MAX_DURATION,
        help=f"Maximum segment duration in seconds (default: {DEFAULT_MAX_DURATION})",
    )
    parser.add_argument(
        "--dry-run", action="store_true",
        help="Report what would be split without modifying files",
    )
    args = parser.parse_args()

    segments_dir = Path(args.segments_dir)
    if not segments_dir.exists():
        print(f"ERROR: {segments_dir} not found")
        sys.exit(1)

    print(f"Scanning {segments_dir} for segments > {args.max_duration}s ...")
    stats = process_segments_dir(segments_dir, args.max_duration, args.dry_run)

    print(f"\nDone. Scanned: {stats['scanned']}, "
          f"Long (>{args.max_duration}s): {stats['long']}, "
          f"Split into: {stats['split_into']} sub-segments")
    if stats["errors"]:
        print(f"Errors: {stats['errors']}")


if __name__ == "__main__":
    main()
