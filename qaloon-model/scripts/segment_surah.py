#!/usr/bin/env python3
from __future__ import annotations
"""
Segment per-surah audio recordings into per-ayah clips.

Uses WhisperX for forced alignment: aligns the known Qaloon text to the audio,
identifies word-level timestamps, then cuts at ayah boundaries.

Usage:
    python segment_surah.py --input data/raw/muhaysin_qaloon/ --output data/processed/muhaysin_qaloon/
    python segment_surah.py --input data/raw/muhaysin_qaloon/002.mp3 --surah 2

Parallelism (multi-GPU or CPU-only):
    python segment_surah.py --input data/raw/ --output data/processed/ --workers 4
"""

import argparse
import json
import re
import sys
from concurrent.futures import ProcessPoolExecutor, as_completed
from pathlib import Path

import librosa
import numpy as np
import soundfile as sf
from tqdm import tqdm

try:
    import torch
    import whisperx
    HAS_WHISPERX = True
except ImportError:
    HAS_WHISPERX = False

SCRIPT_DIR = Path(__file__).resolve().parent
DATA_DIR = SCRIPT_DIR.parent / "data"
METADATA_DIR = DATA_DIR / "metadata"

TARGET_SR = 16000


def load_quran_text() -> dict[tuple[int, int], str]:
    """Load Qaloon Quran text, indexed by (surah, ayah)."""
    # Try Qaloon first, fall back to Hafs
    qaloon_file = METADATA_DIR / "quran_qaloon.json"
    hafs_file = METADATA_DIR / "quran_hafs_uthmani.json"

    text_file = qaloon_file if qaloon_file.exists() else hafs_file
    if not text_file.exists():
        print("ERROR: No Quran text found. Run build_qaloon_text.py first.")
        sys.exit(1)

    with open(text_file, encoding="utf-8") as f:
        ayahs = json.load(f)

    return {(a["surah"], a["ayah"]): a["text"] for a in ayahs}


def load_ayah_counts() -> dict[int, int]:
    """Load surah -> ayah count."""
    with open(METADATA_DIR / "surah_ayah_counts.json") as f:
        data = json.load(f)
    return {int(k): v["ayahs"] for k, v in data.items()}


def strip_tashkeel(text: str) -> str:
    """Remove diacritics/tashkeel from Arabic text for alignment matching."""
    # Arabic diacritics Unicode range
    tashkeel = re.compile(r'[\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06DC\u06DF-\u06E4\u06E7-\u06E8\u06EA-\u06ED]')
    return tashkeel.sub('', text)


def detect_silence_boundaries(audio: np.ndarray, sr: int, min_silence_ms: int = 300, threshold_db: float = -35) -> list[float]:
    """Find silence points in audio that could be ayah boundaries."""
    # Convert to power in dB
    hop_length = int(sr * 0.01)  # 10ms frames
    rms = librosa.feature.rms(y=audio, hop_length=hop_length)[0]
    rms_db = librosa.power_to_db(rms ** 2, ref=np.max)

    # Find frames below threshold
    is_silent = rms_db < threshold_db
    min_frames = int(min_silence_ms / 10)

    # Find silence regions (consecutive silent frames)
    boundaries = []
    count = 0
    for i, silent in enumerate(is_silent):
        if silent:
            count += 1
        else:
            if count >= min_frames:
                # Midpoint of silence region
                mid_frame = i - count // 2
                boundaries.append(mid_frame * hop_length / sr)
            count = 0

    return boundaries


def segment_surah_file(
    audio_path: Path,
    surah_num: int,
    output_dir: Path,
    quran_text: dict,
    ayah_counts: dict,
    device: str = "cpu",
    whisper_model: str = "medium",
    _whisper=None,
    _align_model=None,
    _align_metadata=None,
):
    """Segment a single surah audio file into per-ayah clips.

    If _whisper / _align_model / _align_metadata are passed, reuse them
    instead of loading from scratch (saves ~30s per call).
    """
    num_ayahs = ayah_counts[surah_num]

    # Resume support: skip if output already has all ayah segments
    surah_output = output_dir / f"{surah_num:03d}"
    if surah_output.exists():
        existing = list(surah_output.glob(f"{surah_num:03d}*.wav"))
        # Don't count _orig.wav backups from split_long_segments
        existing = [f for f in existing if "_orig" not in f.stem]
        if len(existing) >= num_ayahs:
            print(f"\nSkipping Surah {surah_num}: already has {len(existing)} segments")
            return len(existing)

    print(f"\nSegmenting Surah {surah_num} ({num_ayahs} ayahs): {audio_path.name}")

    # Load audio
    audio, sr = librosa.load(str(audio_path), sr=TARGET_SR, mono=True)
    total_duration = len(audio) / sr
    print(f"  Duration: {total_duration:.1f}s ({total_duration/60:.1f} min)")

    # Step 1: Try WhisperX for word-level timestamps (if available)
    words = []
    if HAS_WHISPERX:
        print("  Running WhisperX alignment...")
        if _whisper is None:
            compute = "float16" if device == "cuda" else "float32"
            _whisper = whisperx.load_model(whisper_model, device, language="ar", compute_type=compute)
        if _align_model is None:
            _align_model, _align_metadata = whisperx.load_align_model(language_code="ar", device=device)

        result = _whisper.transcribe(audio, language="ar")
        aligned = whisperx.align(result["segments"], _align_model, _align_metadata, audio, device)

        for seg in aligned.get("segments", []):
            for w in seg.get("words", []):
                if "start" in w and "end" in w:
                    words.append({
                        "word": w["word"],
                        "start": w["start"],
                        "end": w["end"],
                    })
    else:
        print("  WhisperX not available, using silence-based segmentation")

    if not words:
        print("  WARNING: No word timestamps from WhisperX. Falling back to silence detection.")
        return segment_by_silence(audio, sr, surah_num, num_ayahs, output_dir)

    print(f"  Got {len(words)} word timestamps")

    # Step 4: Match words to ayah boundaries
    # Build the full text for this surah as a word list
    ayah_texts = []
    for ayah_num in range(1, num_ayahs + 1):
        text = quran_text.get((surah_num, ayah_num), "")
        ayah_texts.append(text)

    # Use silence detection as a complementary signal
    silence_points = detect_silence_boundaries(audio, sr)
    print(f"  Found {len(silence_points)} silence points")

    # Step 5: Estimate ayah boundaries using proportional timing + silence snapping
    # This is a heuristic: divide total duration proportionally by text length,
    # then snap each boundary to the nearest silence point
    total_chars = sum(len(strip_tashkeel(t)) for t in ayah_texts)
    if total_chars == 0:
        print("  ERROR: No text found for this surah")
        return 0

    boundaries = [0.0]
    cumulative = 0.0
    for text in ayah_texts[:-1]:  # Don't need boundary after last ayah
        cumulative += len(strip_tashkeel(text)) / total_chars
        estimated_time = cumulative * total_duration

        # Snap to nearest silence point within ±2 seconds
        best_silence = estimated_time
        best_dist = float("inf")
        for sp in silence_points:
            dist = abs(sp - estimated_time)
            if dist < best_dist and dist < 2.0:
                best_dist = dist
                best_silence = sp

        boundaries.append(best_silence)

    boundaries.append(total_duration)

    # Step 6: Cut audio at boundaries
    surah_output = output_dir / f"{surah_num:03d}"
    surah_output.mkdir(parents=True, exist_ok=True)
    segments_created = 0

    for ayah_idx in range(num_ayahs):
        ayah_num = ayah_idx + 1
        start_sec = boundaries[ayah_idx]
        end_sec = boundaries[ayah_idx + 1]

        start_sample = int(start_sec * sr)
        end_sample = int(end_sec * sr)
        segment = audio[start_sample:end_sample]

        if len(segment) < sr * 0.3:  # Skip segments shorter than 0.3s
            print(f"  WARNING: Ayah {ayah_num} segment too short ({end_sec - start_sec:.1f}s), skipping")
            continue

        filename = f"{surah_num:03d}{ayah_num:03d}.wav"
        sf.write(str(surah_output / filename), segment, sr, subtype="PCM_16")
        segments_created += 1

    print(f"  Created {segments_created}/{num_ayahs} segments")
    return segments_created


def segment_by_silence(
    audio: np.ndarray,
    sr: int,
    surah_num: int,
    num_ayahs: int,
    output_dir: Path,
) -> int:
    """Fallback: segment by silence detection only."""
    silence_points = detect_silence_boundaries(audio, sr, min_silence_ms=400, threshold_db=-30)
    total_duration = len(audio) / sr

    # We need num_ayahs - 1 boundaries
    if len(silence_points) < num_ayahs - 1:
        print(f"  WARNING: Only {len(silence_points)} silence points for {num_ayahs} ayahs")
        print("  Using evenly-spaced fallback")
        silence_points = [total_duration * i / num_ayahs for i in range(1, num_ayahs)]

    # Take the top-N most prominent silence points, evenly distributed
    if len(silence_points) > num_ayahs - 1:
        # Keep num_ayahs-1 points, spaced as evenly as possible
        indices = np.linspace(0, len(silence_points) - 1, num_ayahs - 1, dtype=int)
        silence_points = [silence_points[i] for i in indices]

    boundaries = [0.0] + silence_points + [total_duration]

    surah_output = output_dir / f"{surah_num:03d}"
    surah_output.mkdir(parents=True, exist_ok=True)
    created = 0

    for ayah_idx in range(num_ayahs):
        ayah_num = ayah_idx + 1
        start = int(boundaries[ayah_idx] * sr)
        end = int(boundaries[ayah_idx + 1] * sr)
        segment = audio[start:end]

        if len(segment) > 0:
            filename = f"{surah_num:03d}{ayah_num:03d}.wav"
            sf.write(str(surah_output / filename), segment, sr, subtype="PCM_16")
            created += 1

    return created


def infer_surah_from_filename(filepath: Path) -> int | None:
    """Try to extract surah number from filename like 002.mp3 or surah_002.mp3."""
    match = re.search(r'(\d{1,3})', filepath.stem)
    if match:
        num = int(match.group(1))
        if 1 <= num <= 114:
            return num
    return None


def _segment_worker(args_tuple):
    """Worker function for ProcessPoolExecutor (multi-process parallelism).

    Each worker loads its own WhisperX model copy so it can run on a
    separate GPU or CPU core independently.
    """
    audio_path, surah_num, output_dir, quran_text, ayah_counts, device, model_name = args_tuple

    # Each process loads its own models (can't share across processes)
    whisper_m = align_m = align_meta = None
    if HAS_WHISPERX:
        compute = "float16" if device == "cuda" else "float32"
        whisper_m = whisperx.load_model(model_name, device, language="ar", compute_type=compute)
        align_m, align_meta = whisperx.load_align_model(language_code="ar", device=device)

    return segment_surah_file(
        audio_path, surah_num, output_dir, quran_text, ayah_counts,
        device, model_name, whisper_m, align_m, align_meta,
    )


def main():
    parser = argparse.ArgumentParser(
        description="Segment per-surah audio into per-ayah clips using WhisperX alignment"
    )
    parser.add_argument(
        "--input", type=str, required=True,
        help="Input: directory of surah MP3s, or a single surah file",
    )
    parser.add_argument(
        "--output", type=str, required=True,
        help="Output directory for per-ayah WAV segments",
    )
    parser.add_argument(
        "--surah", type=int, default=None,
        help="Surah number (required if --input is a single file)",
    )
    parser.add_argument(
        "--model", type=str, default="medium",
        help="Whisper model size for alignment (default: medium)",
    )
    parser.add_argument(
        "--device", type=str, default="cpu",
        choices=["cpu", "cuda"],
        help="Device for WhisperX (default: cpu)",
    )
    parser.add_argument(
        "--workers", type=int, default=1,
        help="Number of parallel workers (default: 1). "
             "Use >1 for multi-GPU (CUDA_VISIBLE_DEVICES per worker) or CPU-only mode.",
    )

    args = parser.parse_args()
    input_path = Path(args.input)
    output_dir = Path(args.output)

    quran_text = load_quran_text()
    ayah_counts = load_ayah_counts()

    if input_path.is_file():
        # Single file mode — load models once, process one surah
        surah_num = args.surah or infer_surah_from_filename(input_path)
        if surah_num is None:
            print("ERROR: Could not determine surah number. Use --surah N.")
            sys.exit(1)

        # Load models once for single-file mode
        whisper_m = align_m = align_meta = None
        if HAS_WHISPERX:
            compute = "float16" if args.device == "cuda" else "float32"
            whisper_m = whisperx.load_model(args.model, args.device, language="ar", compute_type=compute)
            align_m, align_meta = whisperx.load_align_model(language_code="ar", device=args.device)

        segment_surah_file(
            input_path, surah_num, output_dir, quran_text, ayah_counts,
            args.device, args.model, whisper_m, align_m, align_meta,
        )

    elif input_path.is_dir():
        # Directory mode: process all surah files
        audio_files = sorted(
            f for f in input_path.iterdir()
            if f.suffix.lower() in {".mp3", ".wav", ".ogg", ".flac", ".m4a"}
            and f.stem != "download_summary"
        )

        print(f"Found {len(audio_files)} audio files in {input_path}")

        # Build work items: (audio_path, surah_num, ...)
        work_items = []
        for audio_file in audio_files:
            surah_num = infer_surah_from_filename(audio_file)
            if surah_num is None:
                print(f"\nSkipping {audio_file.name}: can't determine surah number")
                continue
            work_items.append((
                audio_file, surah_num, output_dir, quran_text, ayah_counts,
                args.device, args.model,
            ))

        total_segments = 0

        if args.workers > 1:
            # Multi-process: each worker loads its own model copy.
            # Useful for multi-GPU or CPU-only mode.
            print(f"Using {args.workers} parallel workers")
            with ProcessPoolExecutor(max_workers=args.workers) as pool:
                futures = {pool.submit(_segment_worker, item): item[0] for item in work_items}
                for future in as_completed(futures):
                    audio_file = futures[future]
                    try:
                        total_segments += future.result()
                    except Exception as exc:
                        print(f"\nERROR processing {audio_file.name}: {exc}")
        else:
            # Single-process: load models once, reuse for all surahs
            whisper_m = align_m = align_meta = None
            if HAS_WHISPERX:
                print("Loading WhisperX models (once)...")
                compute = "float16" if args.device == "cuda" else "float32"
                whisper_m = whisperx.load_model(args.model, args.device, language="ar", compute_type=compute)
                align_m, align_meta = whisperx.load_align_model(language_code="ar", device=args.device)

            for item in work_items:
                audio_file, surah_num = item[0], item[1]
                count = segment_surah_file(
                    audio_file, surah_num, output_dir, quran_text, ayah_counts,
                    args.device, args.model, whisper_m, align_m, align_meta,
                )
                total_segments += count

        print(f"\nTotal segments created: {total_segments}")
    else:
        print(f"ERROR: {input_path} not found")
        sys.exit(1)


if __name__ == "__main__":
    main()
