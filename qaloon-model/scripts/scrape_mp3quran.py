#!/usr/bin/env python3
from __future__ import annotations
"""
Download per-surah Quran audio from MP3Quran.net.

MP3Quran.net has a public API and hosts many Qaloon reciters with per-surah files.
API docs: https://mp3quran.net/api/v3/

Usage:
    python scrape_mp3quran.py --list-reciters
    python scrape_mp3quran.py --reciter 12 --output data/raw/muhaysin_qaloon/
    python scrape_mp3quran.py --reciter 12 --surah 1 2 3
"""

import argparse
import json
import sys
import time
from pathlib import Path

import requests
from tqdm import tqdm

API_BASE = "https://mp3quran.net/api/v3"
SCRIPT_DIR = Path(__file__).resolve().parent
DATA_DIR = SCRIPT_DIR.parent / "data"

# Qaloon rewaya ID on MP3Quran.net (may need verification)
QALOON_REWAYA_ID = 2  # 1=Hafs, 2=Qaloon (verify via API)


def get_reciters(language: str = "ar") -> list[dict]:
    """Fetch list of reciters from MP3Quran.net API."""
    url = f"{API_BASE}/reciters"
    params = {"language": language, "rewaya": QALOON_REWAYA_ID}
    resp = requests.get(url, params=params, timeout=30)
    resp.raise_for_status()
    data = resp.json()
    return data.get("reciters", [])


def list_reciters():
    """Print available Qaloon reciters."""
    print("Fetching Qaloon reciters from MP3Quran.net...")
    print()

    reciters = get_reciters("ar")
    if not reciters:
        # Try fetching all and filtering
        print("No reciters found with rewaya filter. Trying all reciters...")
        url = f"{API_BASE}/reciters"
        resp = requests.get(url, params={"language": "eng"}, timeout=30)
        resp.raise_for_status()
        all_reciters = resp.json().get("reciters", [])
        # Show all and let user identify Qaloon ones
        for r in all_reciters:
            moshaf_list = r.get("moshaf", [])
            for m in moshaf_list:
                name = m.get("name", "")
                if "قالون" in name or "qaloon" in name.lower() or "Qaloon" in name:
                    print(f"  ID: {r['id']:<6} Name: {r['name']}")
                    print(f"         Moshaf: {m['name']} (ID: {m['id']})")
                    print(f"         Server: {m.get('server', 'N/A')}")
                    print(f"         Surahs: {m.get('surah_list', 'N/A')}")
                    print()
        return

    print(f"Found {len(reciters)} Qaloon reciter(s):")
    print()
    for r in reciters:
        print(f"  ID: {r['id']:<6} Name: {r['name']}")
        for m in r.get("moshaf", []):
            print(f"         Moshaf: {m.get('name', 'N/A')} (ID: {m.get('id', 'N/A')})")
            print(f"         Server: {m.get('server', 'N/A')}")
            surah_list = m.get("surah_list", "")
            count = len(surah_list.split(",")) if surah_list else 0
            print(f"         Surahs available: {count}")
            print()


def download_file(url: str, dest: Path, session: requests.Session, retries: int = 3) -> bool:
    """Download a file with retry logic."""
    if dest.exists() and dest.stat().st_size > 0:
        return True

    for attempt in range(retries):
        try:
            resp = session.get(url, timeout=60, stream=True)
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
                print(f"  FAILED: {url} — {e}", file=sys.stderr)
                dest.unlink(missing_ok=True)
                return False

    return False


def download_reciter(
    reciter_id: int,
    output_dir: Path,
    surahs: list[int] | None = None,
    delay: float = 0.5,
):
    """Download all surah files for a reciter."""
    print(f"Fetching reciter info for ID {reciter_id}...")

    # Get reciter details
    url = f"{API_BASE}/reciters"
    resp = requests.get(url, params={"language": "eng"}, timeout=30)
    resp.raise_for_status()
    all_reciters = resp.json().get("reciters", [])

    reciter = None
    for r in all_reciters:
        if r["id"] == reciter_id:
            reciter = r
            break

    if not reciter:
        print(f"ERROR: Reciter ID {reciter_id} not found.", file=sys.stderr)
        sys.exit(1)

    # Find the Qaloon moshaf
    qaloon_moshaf = None
    for m in reciter.get("moshaf", []):
        name = m.get("name", "")
        if "قالون" in name or "qaloon" in name.lower() or "Qaloon" in name:
            qaloon_moshaf = m
            break

    # If no explicit Qaloon moshaf found, use the first one (user specified this reciter)
    if not qaloon_moshaf and reciter.get("moshaf"):
        qaloon_moshaf = reciter["moshaf"][0]
        print(f"  Warning: No explicit Qaloon moshaf found, using: {qaloon_moshaf.get('name', 'default')}")

    if not qaloon_moshaf:
        print(f"ERROR: No moshaf found for reciter {reciter['name']}", file=sys.stderr)
        sys.exit(1)

    server = qaloon_moshaf.get("server", "").rstrip("/")
    available_surahs = qaloon_moshaf.get("surah_list", "")
    available_list = [int(s) for s in available_surahs.split(",") if s.strip()]

    if surahs:
        download_list = [s for s in surahs if s in available_list]
        if len(download_list) < len(surahs):
            missing = set(surahs) - set(download_list)
            print(f"  Warning: Surahs {missing} not available for this reciter")
    else:
        download_list = available_list

    print(f"Downloading: {reciter['name']}")
    print(f"  Moshaf: {qaloon_moshaf.get('name', 'N/A')}")
    print(f"  Server: {server}")
    print(f"  Surahs: {len(download_list)}")
    print(f"  Output: {output_dir}")
    print()

    session = requests.Session()
    session.headers.update({
        "User-Agent": "QaloonModelDataCollector/1.0 (academic research)"
    })

    downloaded = 0
    skipped = 0
    failed = 0

    with tqdm(total=len(download_list), unit="surah", desc="Downloading") as pbar:
        for surah_num in download_list:
            filename = f"{surah_num:03d}.mp3"
            file_url = f"{server}/{filename}"
            dest = output_dir / filename

            if dest.exists() and dest.stat().st_size > 0:
                skipped += 1
            elif download_file(file_url, dest, session):
                downloaded += 1
            else:
                failed += 1

            pbar.update(1)
            time.sleep(delay)

    print()
    print(f"Done! Downloaded: {downloaded}, Skipped: {skipped}, Failed: {failed}")

    summary = {
        "reciter_id": reciter_id,
        "reciter_name": reciter["name"],
        "moshaf": qaloon_moshaf.get("name", ""),
        "server": server,
        "downloaded": downloaded,
        "skipped": skipped,
        "failed": failed,
        "surahs": download_list,
    }
    summary_file = output_dir / "download_summary.json"
    output_dir.mkdir(parents=True, exist_ok=True)
    with open(summary_file, "w") as f:
        json.dump(summary, f, indent=2, ensure_ascii=False)


def main():
    parser = argparse.ArgumentParser(
        description="Download per-surah Quran audio from MP3Quran.net"
    )
    parser.add_argument(
        "--reciter",
        type=int,
        default=None,
        help="Reciter ID on MP3Quran.net (use --list-reciters to find IDs)",
    )
    parser.add_argument(
        "--output",
        type=str,
        default=None,
        help="Output directory (default: data/raw/mp3quran_<reciter_id>/)",
    )
    parser.add_argument(
        "--surah",
        type=int,
        nargs="+",
        default=None,
        help="Download specific surahs only",
    )
    parser.add_argument(
        "--delay",
        type=float,
        default=0.5,
        help="Delay between requests in seconds (default: 0.5)",
    )
    parser.add_argument(
        "--list-reciters",
        action="store_true",
        help="List available Qaloon reciters and exit",
    )

    args = parser.parse_args()

    if args.list_reciters:
        list_reciters()
        return

    if args.reciter is None:
        print("ERROR: --reciter ID is required. Use --list-reciters to find IDs.")
        sys.exit(1)

    output = Path(args.output) if args.output else DATA_DIR / "raw" / f"mp3quran_{args.reciter}"
    download_reciter(args.reciter, output, args.surah, args.delay)


if __name__ == "__main__":
    main()
