"""
The weekly gap check of plan 9.3: for each daily collector, the days of the last N (default 14) on which nothing was
saved, read from test-data-local/official/<cc>/MANIFEST.tsv. A page byte-identical to an earlier copy adds no row, so
it shows as a gap too (rare: a daily page carries the day's times). Most daily sources show only today, so a gap is
usually lost for good. Tripoli is the exception: each api.ifta.ly answer holds today and the next 7 days, so a missed
day is still covered by a run in the 7 days before it (the Libya collector prints the Tripoli days no answer holds).

    python scripts/collectors/gaps.py [--days 14]
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402
import run  # noqa: E402


def fetched_days(manifest: Path) -> set[dt.date]:
    """The UTC days that have at least one manifest row."""
    if not manifest.exists():
        return set()
    with open(manifest, encoding="utf-8", newline="") as f:
        rows = csv.DictReader(f, delimiter="\t")
        return {dt.date.fromisoformat(row["fetched"][:10]) for row in rows if row.get("fetched")}


def gaps(days: set[dt.date], today: dt.date, window: int) -> list[dt.date]:
    return [today - dt.timedelta(days=k) for k in range(window, 0, -1) if today - dt.timedelta(days=k) not in days]


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--days", type=int, default=14)
    args = parser.parse_args(argv)
    today = dt.datetime.now(dt.timezone.utc).date()
    found = 0
    for name in run.COLLECTORS:
        module = run.load(name)
        if module.CADENCE != "daily":
            continue
        missing = gaps(fetched_days(common.OFFICIAL / module.CC.lower() / "MANIFEST.tsv"), today, args.days)
        found += len(missing)
        print(f"{name}: " + ("no gap" if not missing else f"{len(missing)} day(s) missing: " + ", ".join(d.isoformat() for d in missing)))
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
