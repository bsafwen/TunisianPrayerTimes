#!/usr/bin/env python3
"""Download remaining surahs for existing reciters and add new reciters."""
from __future__ import annotations

import subprocess
import time
from pathlib import Path

DATA_DIR = Path(__file__).resolve().parent.parent / "data"

RECITERS = [
    {
        "name": "mahmoud_khalil_al_hussary",
        "server": "https://server13.mp3quran.net/husr/Rewayat-Qalon-A-n-Nafi",
    },
    {
        "name": "ali_alhuthaifi",
        "server": "https://server9.mp3quran.net/huthifi_qalon",
    },
    {
        "name": "ahmed_al_trabulsi",
        "server": "https://server10.mp3quran.net/trablsi",
    },
]


def download_file(url: str, dest: Path) -> bool:
    if dest.exists() and dest.stat().st_size > 1000:
        return True
    dest.parent.mkdir(parents=True, exist_ok=True)
    try:
        result = subprocess.run(
            ["curl", "-s", "-L", "-o", str(dest),
             "--connect-timeout", "30", "--max-time", "1800",
             "--retry", "3", "--retry-delay", "5",
             "-C", "-",  # resume partial downloads
             url],
            capture_output=True, timeout=1860,
        )
        if result.returncode == 0 and dest.exists() and dest.stat().st_size > 1000:
            return True
        dest.unlink(missing_ok=True)
    except subprocess.TimeoutExpired:
        dest.unlink(missing_ok=True)
    return False


def main():
    for reciter in RECITERS:
        name = reciter["name"]
        server = reciter["server"].rstrip("/")
        out_dir = DATA_DIR / "raw" / name
        out_dir.mkdir(parents=True, exist_ok=True)

        existing = {int(f.stem) for f in out_dir.glob("*.mp3") if f.stat().st_size > 1000}
        missing = [s for s in range(1, 115) if s not in existing]

        if not missing:
            print(f"{name}: all 114 surahs present, skipping")
            continue

        print(f"{name}: downloading {len(missing)} missing surahs...")
        ok = 0
        fail = 0
        for s in missing:
            dest = out_dir / f"{s:03d}.mp3"
            url = f"{server}/{s:03d}.mp3"
            if download_file(url, dest):
                ok += 1
                sz = dest.stat().st_size // 1024
                print(f"  {s:03d}.mp3 OK ({sz}KB)")
            else:
                fail += 1
                print(f"  {s:03d}.mp3 FAILED")
            time.sleep(0.3)

        print(f"  Done: {ok} downloaded, {fail} failed\n")

    print("All downloads complete!")


if __name__ == "__main__":
    main()
