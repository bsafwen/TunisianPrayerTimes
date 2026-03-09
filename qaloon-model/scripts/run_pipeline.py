#!/usr/bin/env python3
from __future__ import annotations
"""
End-to-end pipeline: download → normalize → segment → manifest → stats.

Runs the full data preparation pipeline on available data.

Usage:
    python run_pipeline.py                   # Process all available data
    python run_pipeline.py --skip-download   # Skip download, process existing data
    python run_pipeline.py --reciter mahmoud_khalil_al_hussary  # Process single reciter
"""

import argparse
import json
import subprocess
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT_DIR = SCRIPT_DIR.parent
DATA_DIR = ROOT_DIR / "data"
VENV_PYTHON = ROOT_DIR / ".venv" / "bin" / "python"


def run_step(name: str, cmd: list[str]) -> bool:
    """Run a pipeline step and report status."""
    print(f"\n{'='*60}")
    print(f"  STEP: {name}")
    print(f"  CMD:  {' '.join(cmd)}")
    print(f"{'='*60}\n")

    result = subprocess.run(cmd)
    if result.returncode != 0:
        print(f"\n  FAILED: {name} (exit code {result.returncode})")
        return False
    print(f"\n  OK: {name}")
    return True


def main():
    parser = argparse.ArgumentParser(description="Run the full data preparation pipeline")
    parser.add_argument("--skip-download", action="store_true", help="Skip the download step")
    parser.add_argument("--skip-normalize", action="store_true", help="Skip normalization")
    parser.add_argument("--skip-segment", action="store_true", help="Skip segmentation")
    parser.add_argument("--reciter", type=str, default=None, help="Process only this reciter")
    parser.add_argument("--workers", type=int, default=4, help="Parallel workers for normalization")
    parser.add_argument("--device", type=str, default=None, help="Device for segmentation (cpu/cuda, auto-detected)")
    args = parser.parse_args()

    py = str(VENV_PYTHON) if VENV_PYTHON.exists() else sys.executable

    # Auto-detect device for segmentation
    if args.device is None:
        try:
            import torch
            device = "cuda" if torch.cuda.is_available() else "cpu"
        except ImportError:
            device = "cpu"
    else:
        device = args.device
    print(f"Segmentation device: {device}")

    # Step 1: Ensure Qaloon text exists
    qaloon_text = DATA_DIR / "metadata" / "quran_qaloon.json"
    if not qaloon_text.exists():
        print("Generating Qaloon text from Hafs + diffs...")
        if not run_step("Build Qaloon text", [py, str(SCRIPT_DIR / "build_qaloon_text.py"), "--apply-diffs"]):
            return

    # Step 2: Check what raw audio is available
    raw_dir = DATA_DIR / "raw"
    if not raw_dir.exists():
        print("ERROR: No raw audio data found. Run download scripts first.")
        sys.exit(1)

    if args.reciter:
        reciter_dirs = [raw_dir / args.reciter]
        if not reciter_dirs[0].exists():
            print(f"ERROR: Reciter directory not found: {reciter_dirs[0]}")
            sys.exit(1)
    else:
        reciter_dirs = sorted([d for d in raw_dir.iterdir() if d.is_dir()])

    print(f"\nFound {len(reciter_dirs)} reciter(s) in {raw_dir}")
    for d in reciter_dirs:
        mp3s = list(d.glob("*.mp3"))
        print(f"  {d.name}: {len(mp3s)} files")

    # Step 3: Normalize audio
    if not args.skip_normalize:
        for reciter_dir in reciter_dirs:
            name = reciter_dir.name
            proc_dir = DATA_DIR / "processed" / name
            if not run_step(
                f"Normalize {name}",
                [py, str(SCRIPT_DIR / "normalize_audio.py"),
                 "--input", str(reciter_dir),
                 "--output", str(proc_dir),
                 "--workers", str(args.workers)],
            ):
                print(f"  WARNING: Normalization failed for {name}, continuing...")

    # Step 4: Segment into per-ayah clips
    if not args.skip_segment:
        for reciter_dir in reciter_dirs:
            name = reciter_dir.name
            proc_dir = DATA_DIR / "processed" / name
            seg_dir = DATA_DIR / "segments" / name

            # Only segment if we have normalized files
            if not proc_dir.exists():
                continue

            if not run_step(
                f"Segment {name}",
                [py, str(SCRIPT_DIR / "segment_surah.py"),
                 "--input", str(proc_dir),
                 "--output", str(seg_dir),
                 "--device", device],
            ):
                print(f"  WARNING: Segmentation failed for {name}, continuing...")

    # Step 5: Build manifests
    seg_dir = DATA_DIR / "segments"
    if seg_dir.exists() and any(seg_dir.iterdir()):
        run_step(
            "Build manifests",
            [py, str(SCRIPT_DIR / "build_manifest.py"),
             "--processed-dir", str(seg_dir),
             "--output-dir", str(DATA_DIR)],
        )

    # Step 6: Dataset stats
    if (DATA_DIR / "manifest_train.jsonl").exists():
        run_step(
            "Dataset stats",
            [py, str(SCRIPT_DIR / "dataset_stats.py"), "--all"],
        )

    # Final summary
    print(f"\n{'='*60}")
    print("  PIPELINE COMPLETE")
    print(f"{'='*60}")

    for name in ["manifest_train.jsonl", "manifest_val.jsonl", "manifest_test.jsonl"]:
        path = DATA_DIR / name
        if path.exists():
            lines = sum(1 for _ in open(path))
            print(f"  {name}: {lines} samples")

    summary = DATA_DIR / "manifest_summary.json"
    if summary.exists():
        with open(summary) as f:
            s = json.load(f)
        print(f"\n  Total: {s.get('total_samples', 0)} samples, {s.get('total_duration_hours', 0):.1f} hours")
        print(f"  Train: {s.get('train_samples', 0)} | Val: {s.get('val_samples', 0)} | Test: {s.get('test_samples', 0)}")

    print(f"\nNext: python scripts/train_whisper.py --manifest-dir data/ --output-dir models/whisper-qaloon")


if __name__ == "__main__":
    main()
