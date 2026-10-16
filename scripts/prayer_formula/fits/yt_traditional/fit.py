#!/usr/bin/env python3
"""
Writes the official-zone asset of `yt_traditional`, Mayotte's traditional table (plan 6.5, 7.6, A1, Q36; milestone M3),
android-app/app/src/main/assets/official-zones/yt_traditional.json (schema 1). Nothing is fitted or scored here.

No table is at hand: test-data-local holds no Mayotte table (the 2026 table of horaires-priere-mayotte.fr, the only
published one, read on 2026-10-11, was not saved locally) and no Moroni table either (research-2026-10-08/SD-SO-DJ-KM/km
holds only a script); the owner's mosque dataset is never a source (5.6). So the profile stays the plan's (the Moroni
table less 3 min: Fajr = sunrise - 76, Dhuhr + 1, Maghrib = sunset, Isha = Maghrib + 74, Asr = Maghrib - 168) and the
asset is the plan's single zone: every time from Moroni's point, the point of `km_fitted` (plan 8.7: -11.70, 43.25),
named «مايوت» so that the zone line never names Moroni, mapped NATIONAL, with the sunrise row at the user's own point
(`sunriseAtPlace`, 6.5). It carries no FitStats, so the chip stays «مستخلصة من جداول» (Q36).

Once the site's 2026 table is saved under test-data-local, this script is where it gets scored (9.2: within 2 min on at
least 90 % of days per prayer, never more than 4 off; its sunrise column, the site's own, is not compared).

Usage: python scripts/prayer_formula/fits/yt_traditional/fit.py
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import common  # noqa: E402

AUTHORITY = "yt_traditional"
MORONI = (-11.70, 43.25)                 # km_fitted's national point (plan 8.7), the Moroni table's
SOURCE_LINE = ("Mayotte's traditional table, the Moroni table less 3 min (horaires-priere-mayotte.fr's 2026 table and "
               "mosques, plan 7.6); one zone at Moroni's point, sunrise at the user's point")


def main() -> int:
    candidates = [p for p in (common.LOCAL_DATA / "research-2026-10-11" / "FR").glob("*")
                  if any(k in p.name.lower() for k in ("mayotte", "yt_", "mamoudzou", "koungou"))]
    print(f"yt_traditional: Mayotte tables in test-data-local: {len(candidates)} (nothing to fit or score)")
    zone = {"id": "mayotte", "nameAr": "مايوت", "point": common.point(*MORONI), "sunriseAtPlace": True}
    path = common.write_asset(AUTHORITY, AUTHORITY, "YT", "NATIONAL", [zone], source=SOURCE_LINE)
    print(f"wrote {path.relative_to(common.ROOT)} (one zone, no FitStats)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
