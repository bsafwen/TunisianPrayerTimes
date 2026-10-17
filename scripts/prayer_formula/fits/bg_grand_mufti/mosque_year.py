#!/usr/bin/env python3
"""
Bulgaria's cities held out on a whole year now (fits/mosque_years.py, owner 2026-10-17): the owner's mosques that
follow a city's table of the Chief Mufti's Office on every collected day, their 2026 calendars scored on the other
days at the city's zone (Sofia's profile and the city's shift, official-zones/bg_grand_mufti.json), as fit.py's
held-out records are. The profile is fit.py's own, refitted here as fit.py does (the halves decide the latitude).

Usage: python scripts/prayer_formula/fits/bg_grand_mufti/mosque_year.py [--write]   (run fit.py first)
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))
import common as C  # noqa: E402
import fit as F  # noqa: E402  (the city tables and the profile)
import mosque_years as M  # noqa: E402

ASSET = C.ASSETS / "bg_grand_mufti.json"


def profile(base_rows: dict) -> dict:
    """fit.py's final profile: the halves first, which decide whether the latitude may leave Sofia's centre."""
    days = sorted(base_rows)
    halves = [{d: base_rows[d] for d in days if d.day % 2 == k} for k in (1, 0)]
    odd, even = (F.fit(h) for h in halves)
    F.LATITUDE["free"] = F.same_side(odd["lat"], even["lat"], F.CENTRE[0])
    return F.fit(base_rows)


def main(argv: list[str]) -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    tables = F.load_tables()
    official = {slug: M.table_minutes(F.TZ, rows) for slug, rows in tables.items()}
    zones = {z["id"]: z for z in json.loads(ASSET.read_text(encoding="utf-8"))["zones"]}
    method = F.method_of(profile(tables[F.BASE]))
    return M.run(argv, asset_path=ASSET, countries=["BG"], tz=F.TZ, official=official,
                 compute_for=lambda slug: M.asset_computer(method, zones, slug, F.TZ, "BG"))


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
