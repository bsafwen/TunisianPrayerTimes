#!/usr/bin/env python3
"""
Libya's cities held out on a whole year now (fits/mosque_years.py, owner 2026-10-17): the owner's mosques that follow
a city's table of the General Authority of Awqaf on every official day at hand, their 2026 calendars scored on the
other days at the city's zone (official-zones/ly_awqaf.json), as the app computes it (A8). The table is perpetual, so
each official row stands for its month and day of 2026; the profile is fit.py's own, refitted here on Tripoli's rows.
The official days are fit.py's rows and the daily collector's (official/ly), which the fit does not read yet.

Usage: python scripts/prayer_formula/fits/ly_awqaf/mosque_year.py [--write]   (run fit.py first)
"""

from __future__ import annotations

import datetime
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))
import common as C  # noqa: E402
import fit as F  # noqa: E402  (the official rows and the profile)
import mosque_years as M  # noqa: E402

ASSET = C.ASSETS / "ly_awqaf.json"


COLLECTED = C.LOCAL_DATA / "official" / "ly"
KEYS = {"FAJR": "fajr", "SUNRISE": "shorouk", "DHUHR": "dohr", "ASR": "aser", "MAGHRIB": "magreb", "ISHA": "esha"}


def collected_rows() -> dict:
    """The daily collector's rows (scripts/collectors, official/ly/<yyyy>/<mm>/ly_awqaf_<date>_<city>.json), as
    load_rows gives them: city -> date -> {event: local minute}."""
    out: dict = {}
    for path in sorted(COLLECTED.glob("*/*/ly_awqaf_*_*.json")):
        _, _, day, city = path.stem.split("_", 3)
        for row in json.loads(path.read_text(encoding="utf-8")).get("data", []):
            d = datetime.date.fromisoformat(day)
            if (int(row["month"]), int(row["day"])) != (d.month, d.day):
                continue
            out.setdefault(city, {})[d] = {e: F.minute(row[k]) for e, k in KEYS.items()}
    return out


def at_2026(rows: dict) -> dict:
    """A city's perpetual rows {date: {event: local minute}} as {date of YEAR: {event: UTC epoch minute}} (no 29 February)."""
    out = {}
    for d, times in rows.items():
        if (d.month, d.day) == (2, 29):
            continue
        day = datetime.date(M.YEAR, d.month, d.day)
        out[day] = {e: C.local_to_epoch(F.TZ, day, f"{t // 60}:{t % 60:02d}") // 60 for e, t in times.items()}
    return out


def main(argv: list[str]) -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    rows = F.load_rows()
    trip = rows["Tripoli"]
    by_md = {}
    for d in sorted(trip):
        by_md.setdefault((d.month, d.day), (d, trip[d]))
    centres = F.load_centres()
    first_capture = min(d for dates in rows.values() for d in dates).year
    final = F.fit_tripoli(list(by_md.values()), (centres["Tripoli"]["lat"], centres["Tripoli"]["lng"]), first_capture,
                          say=lambda *_: None)
    method = F.method_of(final["p"])
    for city, days in collected_rows().items():     # the collector's days: the follower test only, not the fit's
        for d, times in days.items():
            if rows.setdefault(city, {}).setdefault(d, times) != times:
                raise SystemExit(f"{city} {d}: the collector's row differs from official_rows.csv")
    official = {code: at_2026(r) for code, r in rows.items()}
    zones = {z["id"]: z for z in json.loads(ASSET.read_text(encoding="utf-8"))["zones"]}
    return M.run(argv, asset_path=ASSET, countries=["LY"], tz=F.TZ, official=official,
                 compute_for=lambda code: M.asset_computer(method, zones, code, F.TZ, "LY"))


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
