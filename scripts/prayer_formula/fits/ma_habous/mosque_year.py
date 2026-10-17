#!/usr/bin/env python3
"""
Morocco's localities held out on a whole year now (fits/mosque_years.py, owner 2026-10-17): the owner's mosques that
follow a Habous locality's table on every collected day (Habous's GMT week of 2026-09-13 to 09-19 left out), their 2026
calendars scored on the other days at the zone as the app computes it (official-zones/ma_habous.json), as fit.py's
held-out records are.

A mosque is tried against its three nearest localities and kept for the one it follows best. Of a locality's
followers, the one with the most official days is scored. With --write, each such zone's record gains that year as a
held-out body (`source` "mosque_follower"); the profile's record is left as fit.py wrote it. Run fit.py first.

Usage: python scripts/prayer_formula/fits/ma_habous/mosque_year.py [--write]
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))
import common as C  # noqa: E402
import fit as F  # noqa: E402  (the Habous tables and the profile)
import mosque_years as M  # noqa: E402

ASSET = C.ASSETS / "ma_habous.json"


def main(argv: list[str]) -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    tables, _, _ = F.load_tables()
    official = {vid: {d: {e: p[e] // 60 for e in C.EVENTS} for d, p in zip(t.days, t.pub)
                      if not (F.GMT_ROWS[0] <= d <= F.GMT_ROWS[1])}
                for vid, t in tables.items()}
    zones = {z["id"]: z for z in json.loads(ASSET.read_text(encoding="utf-8"))["zones"]}
    method = F.method_of(F.CURRENT)
    return M.run(argv, asset_path=ASSET, countries=["MA", "EH"], tz=F.TZ, official=official,
                 compute_for=lambda vid: M.asset_computer(method, zones, vid, F.TZ, "MA"))


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
