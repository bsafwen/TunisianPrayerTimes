#!/usr/bin/env python3
"""
Austria's tier-0 capitals held out on a whole year (fits/mosque_years.py, owner 2026-10-17): the owner's mosques that
follow one of IGGÖ's nine 2026 city tables (April-December) on every day of it, their 2026 calendars scored on the
other days (January-March) at the capital's zone (official-zones/diyanet_eu_at.json), as the app computes it, with
fit.py's convention: Fajr and Isha from May to August (the takdir, 9.2) are not scored. Diyanet's tier-1 entries
already hold all of 2027.

Usage: python scripts/prayer_formula/fits/diyanet_eu_at/mosque_year.py [--write]   (run fit.py first)
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))
import common as C  # noqa: E402
import fit as F  # noqa: E402  (IGGÖ's tables and the method)
import mosque_years as M  # noqa: E402

ASSET = C.ASSETS / "diyanet_eu_at.json"


def main(argv: list[str]) -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    official = {city: M.table_minutes(F.TZ, rows) for city, rows in F.load(F.DATA).items()}
    zones = {z["id"]: z for z in json.loads(ASSET.read_text(encoding="utf-8"))["zones"]}
    method = F.method_json()

    def compute_for(city):
        compute_day = M.asset_computer(method, zones, city, F.TZ, "AT")

        def outside_takdir(day):
            out = compute_day(day)
            if day.month in F.TAKDIR_MONTHS:
                out = {e: v for e, v in out.items() if e not in ("FAJR", "ISHA")}
            return out
        return outside_takdir
    return M.run(argv, asset_path=ASSET, countries=["AT"], tz=F.TZ, official=official, compute_for=compute_for)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
