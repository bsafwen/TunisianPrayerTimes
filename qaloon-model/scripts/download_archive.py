#!/usr/bin/env python3
from __future__ import annotations
"""
Download Qaloon Quran recitations from Archive.org.

Searches for and downloads complete Qaloon mushaf recordings.
Uses the Internet Archive's search API.

Usage:
    python download_archive.py --search
    python download_archive.py --identifier <id> --output data/raw/archive_reciter/
"""

import argparse
import json
import os
import sys
import time
from pathlib import Path

import requests
from tqdm import tqdm

SCRIPT_DIR = Path(__file__).resolve().parent
DATA_DIR = SCRIPT_DIR.parent / "data"

SEARCH_URL = "https://archive.org/advancedsearch.php"
METADATA_URL = "https://archive.org/metadata"
DOWNLOAD_URL = "https://archive.org/download"


def search_qaloon(query: str = "quran qaloon", max_results: int = 50) -> list[dict]:
    """Search Archive.org for Qaloon recitations."""
    params = {
        "q": query,
        "fl[]": ["identifier", "title", "description", "mediatype", "item_size"],
        "rows": max_results,
        "output": "json",
    }
    resp = requests.get(SEARCH_URL, params=params, timeout=30)
    resp.raise_for_status()
    data = resp.json()
    return data.get("response", {}).get("docs", [])


def get_item_files(identifier: str) -> list[dict]:
    """Get list of files in an Archive.org item."""
    url = f"{METADATA_URL}/{identifier}/files"
    resp = requests.get(url, timeout=30)
    resp.raise_for_status()
    data = resp.json()
    return data.get("result", [])


def download_file(url: str, dest: Path, session: requests.Session, retries: int = 3) -> bool:
    """Download a file with retry logic."""
    if dest.exists() and dest.stat().st_size > 0:
        return True

    for attempt in range(retries):
        try:
            resp = session.get(url, timeout=120, stream=True)
            resp.raise_for_status()

            total = int(resp.headers.get("content-length", 0))
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
                print(f"  FAILED: {url} — {e}", file=sys.stderr)
                dest.unlink(missing_ok=True)
                return False

    return False


def do_search():
    """Search and display Qaloon recordings on Archive.org."""
    queries = [
        "quran qaloon",
        "القرآن قالون",
        "mushaf qaloon",
        "qaloon an nafi",
    ]

    seen_ids = set()
    all_results = []

    for query in queries:
        print(f"Searching: '{query}'...")
        results = search_qaloon(query)
        for r in results:
            if r["identifier"] not in seen_ids:
                seen_ids.add(r["identifier"])
                all_results.append(r)

    print(f"\nFound {len(all_results)} unique items:\n")

    for i, item in enumerate(all_results, 1):
        size_mb = item.get("item_size", 0) / (1024 * 1024)
        print(f"  [{i}] {item['identifier']}")
        print(f"      Title: {item.get('title', 'N/A')}")
        desc = item.get("description", "N/A")
        if isinstance(desc, list):
            desc = desc[0] if desc else "N/A"
        # Truncate long descriptions
        if len(str(desc)) > 120:
            desc = str(desc)[:120] + "..."
        print(f"      Desc:  {desc}")
        print(f"      Size:  {size_mb:.0f} MB")
        print(f"      URL:   https://archive.org/details/{item['identifier']}")
        print()

    print("To download, run:")
    print("  python download_archive.py --identifier <identifier> --output data/raw/<name>/")


def do_download(identifier: str, output_dir: Path, extensions: list[str], delay: float):
    """Download audio files from an Archive.org item."""
    print(f"Fetching file list for: {identifier}")
    files = get_item_files(identifier)

    # Filter to audio files
    audio_files = [
        f for f in files
        if any(f.get("name", "").lower().endswith(ext) for ext in extensions)
    ]

    if not audio_files:
        print(f"No audio files found with extensions {extensions}")
        print(f"Available files ({len(files)}):")
        for f in files[:20]:
            print(f"  {f.get('name', '?')} ({f.get('format', '?')})")
        if len(files) > 20:
            print(f"  ... and {len(files) - 20} more")
        return

    # Sort by name for consistent ordering
    audio_files.sort(key=lambda f: f.get("name", ""))

    print(f"Found {len(audio_files)} audio files")
    total_bytes = sum(int(f.get("size", 0)) for f in audio_files)
    print(f"Total size: {total_bytes / (1024 * 1024):.0f} MB")
    print(f"Output: {output_dir}")
    print()

    session = requests.Session()
    session.headers.update({
        "User-Agent": "QaloonModelDataCollector/1.0 (academic research)"
    })

    downloaded = 0
    skipped = 0
    failed = 0

    with tqdm(total=len(audio_files), unit="file", desc="Downloading") as pbar:
        for f in audio_files:
            filename = f["name"]
            url = f"{DOWNLOAD_URL}/{identifier}/{requests.utils.quote(filename)}"
            dest = output_dir / filename

            if dest.exists() and dest.stat().st_size > 0:
                skipped += 1
            elif download_file(url, dest, session):
                downloaded += 1
            else:
                failed += 1

            pbar.update(1)
            time.sleep(delay)

    print()
    print(f"Done! Downloaded: {downloaded}, Skipped: {skipped}, Failed: {failed}")

    summary = {
        "source": "archive.org",
        "identifier": identifier,
        "url": f"https://archive.org/details/{identifier}",
        "downloaded": downloaded,
        "skipped": skipped,
        "failed": failed,
        "files": [f["name"] for f in audio_files],
    }
    summary_file = output_dir / "download_summary.json"
    output_dir.mkdir(parents=True, exist_ok=True)
    with open(summary_file, "w") as f:
        json.dump(summary, f, indent=2, ensure_ascii=False)


def main():
    parser = argparse.ArgumentParser(
        description="Download Qaloon Quran recitations from Archive.org"
    )
    parser.add_argument(
        "--search",
        action="store_true",
        help="Search for Qaloon recordings and display results",
    )
    parser.add_argument(
        "--identifier",
        type=str,
        default=None,
        help="Archive.org item identifier to download",
    )
    parser.add_argument(
        "--output",
        type=str,
        default=None,
        help="Output directory",
    )
    parser.add_argument(
        "--extensions",
        type=str,
        nargs="+",
        default=[".mp3", ".ogg", ".wav", ".flac", ".m4a"],
        help="Audio file extensions to download (default: .mp3 .ogg .wav .flac .m4a)",
    )
    parser.add_argument(
        "--delay",
        type=float,
        default=0.5,
        help="Delay between downloads in seconds (default: 0.5)",
    )

    args = parser.parse_args()

    if args.search:
        do_search()
        return

    if args.identifier is None:
        print("ERROR: Use --search to find items, then --identifier <id> to download.")
        sys.exit(1)

    output = Path(args.output) if args.output else DATA_DIR / "raw" / f"archive_{args.identifier}"
    do_download(args.identifier, output, args.extensions, args.delay)


if __name__ == "__main__":
    main()
