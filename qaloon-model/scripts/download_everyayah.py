#!/usr/bin/env python3
from __future__ import annotations
"""
Download per-ayah Quran audio from EveryAyah.com.

Each file on EveryAyah is a single ayah, named as {surah:03d}{ayah:03d}.mp3.
Ayah 000 is the bismillah for that surah (except surah 1 and 9).

Usage:
    python download_everyayah.py --reciter Husary_Qaloon_128kbps
    python download_everyayah.py --reciter Husary_Qaloon_128kbps --surah 1 2 3
    python download_everyayah.py --list-reciters
"""

import argparse
import json
import os
import sys
import time
from pathlib import Path
from urllib.parse import quote

import requests
from tqdm import tqdm

BASE_URL = "https://everyayah.com/data"
SCRIPT_DIR = Path(__file__).resolve().parent
DATA_DIR = SCRIPT_DIR.parent / "data"
METADATA_DIR = DATA_DIR / "metadata"

# Known Qaloon reciters on EveryAyah.com
# Update this list after checking https://everyayah.com/data/status.php
KNOWN_QALOON_RECITERS = [
    "Husary_Qaloon_128kbps",
]


def load_surah_ayah_counts() -> dict[int, int]:
    """Load surah -> ayah count mapping."""
    counts_file = METADATA_DIR / "surah_ayah_counts.json"
    with open(counts_file) as f:
        data = json.load(f)
    return {int(k): v["ayahs"] for k, v in data.items()}


def check_url_exists(url: str, session: requests.Session) -> bool:
    """HEAD request to check if a URL exists."""
    try:
        resp = session.head(url, timeout=10, allow_redirects=True)
        return resp.status_code == 200
    except requests.RequestException:
        return False


def download_file(url: str, dest: Path, session: requests.Session, retries: int = 3) -> bool:
    """Download a file with retry logic. Returns True on success."""
    if dest.exists() and dest.stat().st_size > 0:
        return True  # Already downloaded

    for attempt in range(retries):
        try:
            resp = session.get(url, timeout=30, stream=True)
            resp.raise_for_status()

            dest.parent.mkdir(parents=True, exist_ok=True)
            with open(dest, "wb") as f:
                for chunk in resp.iter_content(chunk_size=8192):
                    f.write(chunk)

            if dest.stat().st_size > 0:
                return True
            else:
                dest.unlink(missing_ok=True)

        except requests.RequestException as e:
            if attempt < retries - 1:
                time.sleep(2 ** attempt)
            else:
                print(f"  FAILED after {retries} attempts: {url} — {e}", file=sys.stderr)
                dest.unlink(missing_ok=True)
                return False

    return False


def list_reciters():
    """Print known Qaloon reciters."""
    print("Known Qaloon reciters on EveryAyah.com:")
    for r in KNOWN_QALOON_RECITERS:
        print(f"  - {r}")
    print()
    print("To discover more, visit: https://everyayah.com/data/status.php")
    print("and look for reciters with 'Qaloon' in the name.")


def download_reciter(
    reciter: str,
    output_dir: Path,
    surahs: list[int] | None = None,
    delay: float = 0.3,
):
    """Download all ayahs for a reciter."""
    ayah_counts = load_surah_ayah_counts()
    surah_list = surahs or list(range(1, 115))

    # Count total files to download
    total_files = 0
    for s in surah_list:
        # ayah 000 (bismillah) exists for all surahs except 1 and 9
        has_bismillah = s not in (1, 9)
        total_files += ayah_counts[s] + (1 if has_bismillah else 0)

    session = requests.Session()
    session.headers.update({
        "User-Agent": "QaloonModelDataCollector/1.0 (academic research)"
    })

    # Verify reciter folder exists with a test file
    test_url = f"{BASE_URL}/{quote(reciter)}/001001.mp3"
    if not check_url_exists(test_url, session):
        print(f"ERROR: Reciter folder '{reciter}' not found at {BASE_URL}/")
        print(f"Tested URL: {test_url}")
        print("Run with --list-reciters to see known Qaloon reciters.")
        sys.exit(1)

    print(f"Downloading {reciter}")
    print(f"  Surahs: {surah_list[0]}-{surah_list[-1]} ({len(surah_list)} surahs)")
    print(f"  Total files: ~{total_files}")
    print(f"  Output: {output_dir}")
    print()

    downloaded = 0
    skipped = 0
    failed = 0

    with tqdm(total=total_files, unit="file", desc="Downloading") as pbar:
        for surah in surah_list:
            surah_dir = output_dir / f"{surah:03d}"
            num_ayahs = ayah_counts[surah]

            # Determine ayah range: include 000 (bismillah) for surahs != 1, 9
            start_ayah = 0 if surah not in (1, 9) else 1

            for ayah in range(start_ayah, num_ayahs + 1):
                filename = f"{surah:03d}{ayah:03d}.mp3"
                url = f"{BASE_URL}/{quote(reciter)}/{filename}"
                dest = surah_dir / filename

                if dest.exists() and dest.stat().st_size > 0:
                    skipped += 1
                    pbar.update(1)
                    continue

                if download_file(url, dest, session):
                    downloaded += 1
                else:
                    failed += 1

                pbar.update(1)
                time.sleep(delay)  # Be respectful to the server

    print()
    print(f"Done! Downloaded: {downloaded}, Skipped (existing): {skipped}, Failed: {failed}")

    # Write a summary
    summary = {
        "reciter": reciter,
        "downloaded": downloaded,
        "skipped": skipped,
        "failed": failed,
        "surahs": surah_list,
        "output_dir": str(output_dir),
    }
    summary_file = output_dir / "download_summary.json"
    with open(summary_file, "w") as f:
        json.dump(summary, f, indent=2)


def main():
    parser = argparse.ArgumentParser(
        description="Download per-ayah Quran audio from EveryAyah.com"
    )
    parser.add_argument(
        "--reciter",
        type=str,
        default="Husary_Qaloon_128kbps",
        help="Reciter folder name on EveryAyah.com (default: Husary_Qaloon_128kbps)",
    )
    parser.add_argument(
        "--output",
        type=str,
        default=None,
        help="Output directory (default: data/raw/<reciter>/)",
    )
    parser.add_argument(
        "--surah",
        type=int,
        nargs="+",
        default=None,
        help="Download specific surahs only (e.g., --surah 1 2 3)",
    )
    parser.add_argument(
        "--delay",
        type=float,
        default=0.3,
        help="Delay between requests in seconds (default: 0.3)",
    )
    parser.add_argument(
        "--list-reciters",
        action="store_true",
        help="List known Qaloon reciters and exit",
    )

    args = parser.parse_args()

    if args.list_reciters:
        list_reciters()
        return

    output_dir = Path(args.output) if args.output else DATA_DIR / "raw" / args.reciter
    download_reciter(args.reciter, output_dir, args.surah, args.delay)


if __name__ == "__main__":
    main()
