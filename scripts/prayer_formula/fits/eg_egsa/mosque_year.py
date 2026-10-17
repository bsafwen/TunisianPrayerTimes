#!/usr/bin/env python3
"""
Egypt's cities held out on a whole year (fits/mosque_years.py, owner 2026-10-17): the owner's mosques that follow an
EGSA city's table on every official day of 2026 at hand, their 2026 calendars scored on the other days at the city's
zone (official-zones/eg_egsa.json), as the app computes it. The official days are fit.py's pages (the Wayback copies
and the 2026-10-08 page) and the daily collector's (official/eg/praytimes), which the fit does not read yet; a mosque
needs 20 of them (mosque_years.MIN_OVERLAP), which the collector gives from early November 2026.

Usage: python scripts/prayer_formula/fits/eg_egsa/mosque_year.py [--write]   (run fit.py first)
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))
import common as C  # noqa: E402
import fit as F  # noqa: E402  (EGSA's pages and the profile)
import mosque_years as M  # noqa: E402

ASSET = C.ASSETS / "eg_egsa.json"
COLLECTED = C.LOCAL_DATA / "official" / "eg" / "praytimes"
PROFILE = {"model": "VSOP87", "horizon": -0.8435, "fajr": 19.5, "isha": 17.5, "asr": "shadow"}   # fit.py's, as shipped


def main(argv: list[str]) -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    days, _ = F.official_days(F.WAYBACK)
    for path in sorted(COLLECTED.glob("praytimes_*.html")):
        for day, rows in F.page_rows(path).items():
            days.setdefault(day, {}).update(rows)
    menu = F.city_menu()
    official: dict = {}
    for day, rows in days.items():
        for name, minutes in rows.items():
            if name in menu:
                official.setdefault(str(menu.index(name)), {})[day] = {
                    e: C.local_to_epoch(F.TZ, day, f"{m // 60}:{m % 60:02d}") // 60 for e, m in zip(C.EVENTS, minutes)}
    zones = {z["id"]: z for z in json.loads(ASSET.read_text(encoding="utf-8"))["zones"]}
    method = F.method_of(PROFILE)
    return M.run(argv, asset_path=ASSET, countries=["EG"], tz=F.TZ, official=official,
                 compute_for=lambda zid: M.asset_computer(method, zones, zid, F.TZ, "EG"))


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
