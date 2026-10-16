"""
Runs the official-table collectors of plan 9.3 and prints one line per collector; exits 1 when any failed, so the
scheduled task's history shows the gap. A failure in one collector never stops the others.

    python scripts/collectors/run.py --cadence daily      # the owner's daily scheduled task
    python scripts/collectors/run.py --cadence monthly    # the monthly one, on the first days of each month
    python scripts/collectors/run.py --only ly,eg         # chosen collectors, whatever their cadence
    python scripts/collectors/run.py --list

Each collector is a module of this folder with NAME, CC, CADENCE ("daily", "monthly" or "yearly"), DESCRIPTION and
collect(session, today) returning the number of files it saved (README.md lists them).
"""

from __future__ import annotations

import argparse
import datetime as dt
import importlib
import sys
import traceback
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402

COLLECTORS = ["ly", "eg", "ma", "qa", "gaza", "ye", "iq", "mk", "bg", "si"]
# Collectors that submit a site's form wait for the owner's go-ahead (9.3): a scheduled --cadence run skips them and
# --only runs them on purpose. Bulgaria's city and month form got it on 2026-10-16, so the set is empty for now.
NEEDS_GO_AHEAD: set[str] = set()


def load(name: str):
    module = importlib.import_module(name)
    for attribute in ("NAME", "CC", "CADENCE", "DESCRIPTION", "collect"):
        if not hasattr(module, attribute):
            raise AttributeError(f"collector {name} has no {attribute}")
    return module


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--cadence", choices=("daily", "monthly", "yearly"))
    parser.add_argument("--only", help="comma-separated collector names")
    parser.add_argument("--list", action="store_true")
    parser.add_argument("--date", type=dt.date.fromisoformat, help="the day to collect as (default: today, UTC)")
    args = parser.parse_args(argv)
    # A scheduled task's redirected output may be cp1252: never let a name or a traceback stop the run.
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(errors="backslashreplace")

    names = COLLECTORS if not args.only else [n.strip() for n in args.only.split(",") if n.strip()]
    unknown = [n for n in names if n not in COLLECTORS]
    if unknown:
        print("unknown collectors: " + ", ".join(unknown), file=sys.stderr)
        return 2
    if args.list:
        for name in names:
            module = load(name)
            gate = " (go-ahead first)" if name in NEEDS_GO_AHEAD else ""
            print(f"{module.NAME:6} {module.CC:3} {module.CADENCE:8} {module.DESCRIPTION}{gate}")
        return 0
    if not args.cadence and not args.only:
        parser.error("give --cadence, --only or --list")

    today = args.date or dt.datetime.now(dt.timezone.utc).date()
    failed = 0
    for name in names:
        try:
            module = load(name)
            # The monthly run also takes the yearly collectors: they ask only for what is due (Iraq from January 2027).
            due = module.CADENCE == args.cadence or (args.cadence == "monthly" and module.CADENCE == "yearly")
            if args.cadence and (not due or (name in NEEDS_GO_AHEAD and not args.only)):
                continue
            count = module.collect(common.Session(), today)
            print(f"{name}: {count} file(s) saved")
        except Exception:  # noqa: BLE001 - one source's failure must not stop the others
            failed += 1
            print(f"{name}: FAILED", file=sys.stderr)
            traceback.print_exc()
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
