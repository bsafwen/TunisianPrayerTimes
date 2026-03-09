#!/usr/bin/env python3
from __future__ import annotations
"""
Download voice contributions via the Worker API and prepare them for training.

No AWS CLI or S3 credentials needed — uses plain HTTP against the Worker.

Usage:
    python download_contributions.py
    python download_contributions.py --output data/contributions/ --dry-run
"""

import argparse
import json
import re
import sys
import urllib.request
import urllib.error
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
DATA_DIR = SCRIPT_DIR.parent / "data"
DEFAULT_OUTPUT = DATA_DIR / "contributions"
WORKER_URL = "https://mawaqittn.safwen-baroudi.workers.dev"


def list_contributions() -> list[dict]:
    """Fetch all contribution entries from the Worker, handling pagination."""
    items: list[dict] = []
    cursor = None

    while True:
        url = f"{WORKER_URL}/api/contribute/list?limit=1000"
        if cursor:
            url += f"&cursor={cursor}"

        with urllib.request.urlopen(url) as resp:
            data = json.loads(resp.read())

        items.extend(data["items"])
        cursor = data.get("cursor")
        if not cursor:
            break

    return items


def download_contributions(items: list[dict], output_dir: Path, dry_run: bool = False) -> int:
    """Download WAV files that don't already exist locally."""
    output_dir.mkdir(parents=True, exist_ok=True)
    downloaded = 0

    for item in items:
        key = item["key"]
        local_path = output_dir / key
        if local_path.exists():
            continue

        if dry_run:
            print(f"  Would download: {key}")
            downloaded += 1
            continue

        local_path.parent.mkdir(parents=True, exist_ok=True)
        url = f"{WORKER_URL}/api/contribute/download?key={urllib.request.quote(key)}"
        urllib.request.urlretrieve(url, local_path)
        downloaded += 1

    print(f"  {'Would download' if dry_run else 'Downloaded'}: {downloaded} files ({len(items)} total in bucket)")
    return downloaded


def build_contribution_manifest(
    items: list[dict],
    contributions_dir: Path,
    output_path: Path,
):
    """
    Build a JSONL manifest from the Worker's list response.
    Text is already stored in R2 metadata — no need to parse filenames.
    Falls back to quran_qaloon.json if text metadata is missing.
    """
    # Fallback Quran text
    qaloon_file = DATA_DIR / "metadata" / "quran_qaloon.json"
    hafs_file = DATA_DIR / "metadata" / "quran_hafs_uthmani.json"
    text_file = qaloon_file if qaloon_file.exists() else hafs_file

    quran_text: dict[tuple[int, int], str] = {}
    if text_file.exists():
        with open(text_file, encoding="utf-8") as f:
            ayahs = json.load(f)
        quran_text = {(a["surah"], a["ayah"]): a["text"] for a in ayahs}

    count = 0
    with open(output_path, "w", encoding="utf-8") as f:
        for item in items:
            surah = int(item.get("surah") or 0)
            ayah = int(item.get("ayah") or 0)
            if surah < 1 or surah > 114 or ayah < 1:
                continue

            text = item.get("text") or quran_text.get((surah, ayah), "")
            if not text:
                continue

            local_path = contributions_dir / item["key"]
            if not local_path.exists():
                continue

            key_parts = item["key"].split("/")
            contributor = key_parts[0] if len(key_parts) >= 1 else "unknown"

            entry = {
                "audio": str(local_path),
                "surah": surah,
                "ayah": ayah,
                "text": text,
                "contributor": contributor,
                "source": "user_contribution",
            }
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")
            count += 1

    print(f"  Manifest: {count} samples → {output_path}")
    return count


def main():
    parser = argparse.ArgumentParser(
        description="Download and prepare user voice contributions"
    )
    parser.add_argument(
        "--output", type=str, default=str(DEFAULT_OUTPUT),
        help=f"Output directory for downloaded WAVs (default: {DEFAULT_OUTPUT})",
    )
    parser.add_argument(
        "--dry-run", action="store_true",
        help="Show what would be downloaded without downloading",
    )
    parser.add_argument(
        "--manifest-only", action="store_true",
        help="Skip download, just rebuild manifest from existing files",
    )
    args = parser.parse_args()

    output_dir = Path(args.output)

    if not args.manifest_only:
        print("Listing contributions from Worker API...")
        items = list_contributions()
        print(f"  Found {len(items)} contributions")
        download_contributions(items, output_dir, args.dry_run)
    else:
        items = list_contributions()

    if not args.dry_run:
        manifest_path = DATA_DIR / "manifest_contributions.jsonl"
        build_contribution_manifest(items, output_dir, manifest_path)
        print(f"\nTo include in training, concatenate manifests:")
        print(f"  cat data/manifest_train.jsonl data/manifest_contributions.jsonl > data/manifest_combined.jsonl")


if __name__ == "__main__":
    main()
