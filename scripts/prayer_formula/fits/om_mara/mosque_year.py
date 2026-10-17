#!/usr/bin/env python3
"""
Oman's zones held out on a whole year now (fits/mosque_years.py, owner 2026-10-17): the owner's mosques that follow a
zone's table on every official day of 2026 at hand (calendar_page4's dates for all 86 zones, calendar_page2's whole
year for six), their 2026 calendars scored on the other days at the zone (official-zones/om_mara.json), as the app
computes it. The profile is fit.py's (the ALMANAC sun once a day at 0 h UT, + 0.86 s, README).

Usage: python scripts/prayer_formula/fits/om_mara/mosque_year.py [--write]   (run fit.py first)
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))
import common as C  # noqa: E402
import fit as F  # noqa: E402  (the ministry's pages and the profile)
import mosque_years as M  # noqa: E402

ASSET = C.ASSETS / "om_mara.json"
PROFILE = F.profile(model="ALMANAC", shift=0.86)       # fit.py's final profile, as shipped


def main(argv: list[str]) -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    official: dict = {}

    def add(zid: int, day, minutes: list[int]):
        official.setdefault(str(zid), {})[day] = {
            e: C.local_to_epoch(F.TZ, day, f"{m // 60}:{m % 60:02d}") // 60 for e, m in zip(C.EVENTS, minutes)}
    _, pages = F.all_zone_pages(F.PAGES)
    for day, rows in pages.items():
        for zid, minutes in rows.items():
            add(zid, day, minutes)
    for zid, days in F.full_year().items():
        for day, minutes in days.items():
            add(zid, day, minutes)
    zones = {z["id"]: z for z in json.loads(ASSET.read_text(encoding="utf-8"))["zones"]}
    method = F.method_of(PROFILE)
    return M.run(argv, asset_path=ASSET, countries=["OM"], tz=F.TZ, official=official,
                 compute_for=lambda zid: M.asset_computer(method, zones, zid, F.TZ, "OM"))


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
