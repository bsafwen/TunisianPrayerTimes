#!/usr/bin/env python3
"""
Fetches APBIF's public pages and tables for the `apbif` fit (plan 7.5, 9.2; milestone M3), apbif.fr's own and the
Wayback Machine's copies (its CDX listing and its id_ captures), through the collectors' Session
(scripts/collectors/common.py: one request every 3 s, the system proxy, TLS always verified), and saves each file's
raw bytes under test-data-local/research-2026-10-16/FR/apbif/ with a MANIFEST.tsv row (file, bytes, sha256, fetched,
source, note), as the collectors do. Nothing fetched is committed (D13, A7). apbif.fr answers 429 after about 40
pages in a row: fetch in small lists.

    python scripts/prayer_formula/fits/apbif/fetch.py URL NAME [--note NOTE]
    python scripts/prayer_formula/fits/apbif/fetch.py --list urls.tsv        # lines: URL <tab> NAME [<tab> NOTE]

The same bytes already saved under a name give no new row; a run waits 3 s first, so separate runs stay paced too.
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / "scripts" / "collectors"))
import common as collectors  # noqa: E402

FOLDER = ("FR", "apbif")      # under test-data-local/research-2026-10-16/


def session() -> collectors.Session:
    return collectors.Session(root=collectors.LOCAL_DATA / "research-2026-10-16" / FOLDER[0])


def fetch_all(jobs: list[tuple[str, str, str]]) -> int:
    s = session()
    time.sleep(collectors.PAUSE_SECONDS)
    failed = 0
    for url, name, note in jobs:
        try:
            content = s.fetch(url)
        except Exception as error:  # a missing page is recorded and the run goes on
            print(f"FAILED {url}: {error}", flush=True)
            failed += 1
            continue
        path = s.save(FOLDER[1], name, content, url, note)
        print(f"{len(content):>9} bytes  {url} -> {path.name if path else name + ' (unchanged)'}", flush=True)
    return 1 if failed else 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("url", nargs="?")
    parser.add_argument("name", nargs="?")
    parser.add_argument("--note", default="")
    parser.add_argument("--list", type=Path)
    args = parser.parse_args(argv)
    jobs = []
    if args.list:
        for line in args.list.read_text(encoding="utf-8").splitlines():
            if line.strip() and not line.startswith("#"):
                cells = line.split("\t")
                jobs.append((cells[0], cells[1], cells[2] if len(cells) > 2 else ""))
    if args.url:
        jobs.append((args.url, args.name, args.note))
    if not jobs:
        parser.error("give URL NAME or --list")
    return fetch_all(jobs)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
