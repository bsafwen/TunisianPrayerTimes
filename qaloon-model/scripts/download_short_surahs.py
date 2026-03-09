#!/usr/bin/env python3
"""Download short surahs (50-114) from top reciters for quick dataset building."""
from __future__ import annotations

import json
import subprocess
import time
from pathlib import Path

from tqdm import tqdm

SCRIPT_DIR = Path(__file__).resolve().parent
DATA_DIR = SCRIPT_DIR.parent / "data"
METADATA_DIR = DATA_DIR / "metadata"


def download_file(url: str, dest: Path, retries: int = 3) -> bool:
    """Download a file using curl (handles SSL/connection issues better)."""
    if dest.exists() and dest.stat().st_size > 0:
        return True

    dest.parent.mkdir(parents=True, exist_ok=True)
    for attempt in range(retries):
        try:
            result = subprocess.run(
                ["curl", "-s", "-L", "-o", str(dest), "--connect-timeout", "15",
                 "--max-time", "600", "--retry", "2", url],
                capture_output=True, timeout=660,
            )
            if result.returncode == 0 and dest.exists() and dest.stat().st_size > 0:
                return True
            dest.unlink(missing_ok=True)
        except subprocess.TimeoutExpired:
            dest.unlink(missing_ok=True)
        if attempt < retries - 1:
            time.sleep(2 ** attempt)
    return False


def main():
    with open(METADATA_DIR / "reciters.json") as f:
        data = json.load(f)

    # Top 3 reciters by priority
    reciters = sorted(data["reciters"], key=lambda r: r.get("priority", 99))[:3]

    for reciter in reciters:
        name = reciter["name"]
        server = reciter["server"].rstrip("/")
        folder = name.lower().replace(" ", "_").replace("-", "_").replace("'", "")
        out_dir = DATA_DIR / "raw" / folder
        out_dir.mkdir(parents=True, exist_ok=True)

        downloaded = 0
        skipped = 0
        failed = 0

        for surah in tqdm(range(50, 115), desc=name[:20], unit="surah"):
            dest = out_dir / f"{surah:03d}.mp3"
            if dest.exists() and dest.stat().st_size > 0:
                skipped += 1
                continue

            url = f"{server}/{surah:03d}.mp3"
            if download_file(url, dest):
                downloaded += 1
            else:
                failed += 1
            time.sleep(0.2)

        print(f"  {name}: downloaded={downloaded}, skipped={skipped}, failed={failed}")
        print()


if __name__ == "__main__":
    main()
