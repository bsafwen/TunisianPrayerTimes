#!/usr/bin/env python3
from __future__ import annotations
"""
Download all Qaloon reciters from MP3Quran.net using known server URLs.

This is a streamlined batch downloader that reads reciters.json and downloads
per-surah MP3 files for all (or selected) reciters.

Usage:
    python download_all_mp3quran.py                     # Download all reciters
    python download_all_mp3quran.py --max-reciters 3    # Download top 3 only
    python download_all_mp3quran.py --reciter-ids 118 74 201  # Specific ones
"""

import argparse
import json
import sys
import time
from pathlib import Path

import requests
from tqdm import tqdm

SCRIPT_DIR = Path(__file__).resolve().parent
DATA_DIR = SCRIPT_DIR.parent / "data"
METADATA_DIR = DATA_DIR / "metadata"


def download_file(url: str, dest: Path, session: requests.Session, retries: int = 3) -> bool:
    """Download a file with retries. Skip if already exists."""
    if dest.exists() and dest.stat().st_size > 0:
        return True

    for attempt in range(retries):
        try:
            resp = session.get(url, timeout=(15, 1800), stream=True)
            resp.raise_for_status()
            dest.parent.mkdir(parents=True, exist_ok=True)
            with open(dest, "wb") as f:
                for chunk in resp.iter_content(chunk_size=65536):
                    f.write(chunk)
            if dest.stat().st_size > 0:
                return True
            dest.unlink(missing_ok=True)
        except requests.RequestException as e:
            if attempt < retries - 1:
                time.sleep(2 ** attempt)
            else:
                tqdm.write(f"  FAILED: {url} — {e}")
                dest.unlink(missing_ok=True)
                return False
    return False


def download_reciter(reciter: dict, session: requests.Session, delay: float = 0.3) -> dict:
    """Download all 114 surahs for a single reciter."""
    name = reciter["name"]
    server = reciter["server"].rstrip("/")
    num_surahs = reciter["surahs"]

    # Clean name for folder
    folder_name = name.lower().replace(" ", "_").replace("-", "_").replace("'", "")
    output_dir = DATA_DIR / "raw" / folder_name

    print(f"\n{'='*60}")
    print(f"  Downloading: {name}")
    print(f"  Server: {server}")
    print(f"  Output: {output_dir}")
    print(f"{'='*60}")

    downloaded = 0
    skipped = 0
    failed = 0

    with tqdm(total=num_surahs, unit="surah", desc=name[:20]) as pbar:
        for surah in range(1, num_surahs + 1):
            filename = f"{surah:03d}.mp3"
            url = f"{server}/{filename}"
            dest = output_dir / filename

            if dest.exists() and dest.stat().st_size > 0:
                skipped += 1
            elif download_file(url, dest, session):
                downloaded += 1
            else:
                failed += 1

            pbar.update(1)
            if downloaded > 0:  # Only delay on actual downloads (not skips)
                time.sleep(delay)

    result = {
        "name": name,
        "folder": folder_name,
        "downloaded": downloaded,
        "skipped": skipped,
        "failed": failed,
    }

    # Save summary
    summary_file = output_dir / "download_summary.json"
    output_dir.mkdir(parents=True, exist_ok=True)
    with open(summary_file, "w") as f:
        json.dump(result, f, indent=2, ensure_ascii=False)

    print(f"  Done: {downloaded} downloaded, {skipped} skipped, {failed} failed")
    return result


def main():
    parser = argparse.ArgumentParser(description="Download all Qaloon reciters from MP3Quran.net")
    parser.add_argument("--max-reciters", type=int, default=None, help="Max reciters to download (by priority)")
    parser.add_argument("--reciter-ids", type=int, nargs="+", default=None, help="Specific reciter IDs")
    parser.add_argument("--delay", type=float, default=0.3, help="Delay between downloads (seconds)")
    args = parser.parse_args()

    reciters_file = METADATA_DIR / "reciters.json"
    with open(reciters_file) as f:
        data = json.load(f)

    all_reciters = data["reciters"]

    if args.reciter_ids:
        reciters = [r for r in all_reciters if r["id"] in args.reciter_ids]
    elif args.max_reciters:
        reciters = sorted(all_reciters, key=lambda r: r.get("priority", 99))[:args.max_reciters]
    else:
        reciters = sorted(all_reciters, key=lambda r: r.get("priority", 99))

    print(f"Downloading {len(reciters)} Qaloon reciters from MP3Quran.net")
    print(f"Total surahs to download: {sum(r['surahs'] for r in reciters)}")

    session = requests.Session()
    session.headers.update({
        "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko)"
    })

    results = []
    for reciter in reciters:
        result = download_reciter(reciter, session, args.delay)
        results.append(result)

    # Final summary
    total_downloaded = sum(r["downloaded"] for r in results)
    total_skipped = sum(r["skipped"] for r in results)
    total_failed = sum(r["failed"] for r in results)

    print(f"\n{'='*60}")
    print(f"  ALL DONE")
    print(f"  Reciters: {len(results)}")
    print(f"  Downloaded: {total_downloaded}")
    print(f"  Skipped: {total_skipped}")
    print(f"  Failed: {total_failed}")
    print(f"{'='*60}")


if __name__ == "__main__":
    main()
