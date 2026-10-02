#!/usr/bin/env python3
"""Fetch original Hosary Qaloun timestamp tables through the required proxy."""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import subprocess
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("work/hosary/timings"))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)

    def fetch(number: int) -> None:
        output = args.output / f"{number:03d}.json"
        subprocess.run([
            "curl.exe", "--proxy", "http://127.0.0.1:8888", "--noproxy", "",
            "--location", "--fail", "--silent", "--show-error", "--retry", "2", "--max-time", "40",
            f"https://www.mp3quran.net/api/v3/ayat_timing?surah={number}&read=270", "-o", str(output)
        ], check=True)
        data = json.loads(output.read_text(encoding="utf-8"))
        assert isinstance(data, list) and data and "start_time" in data[0], number

    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as executor:
        list(executor.map(fetch, range(1, 115)))
    print(f"Fetched all 114 Hosary Qaloun chapter timestamp tables into {args.output}.")


if __name__ == "__main__":
    main()
