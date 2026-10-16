#!/usr/bin/env python3
"""
Astronomy fixtures for the shared engine's tests (plan 9.1, milestone M1), fetched once from two public
services of the US government (public domain) and kept in test-data/engine-reference/:

  horizons_sun.tsv   the Sun's apparent right ascension, declination and distance from the Earth's centre,
                     from JPL Horizons (DE441), at TT epochs every 37 days of 2020-2100
  usno_sun.tsv       sunrise, transit, sunset and civil twilight in UT, to the minute, from the US Naval
                     Observatory's API, for 14 places on 6 dates

The Kotlin test AstronomyFixturesTest compares the engine's suns and events with them.

Usage:
  python scripts/prayer_formula/astronomy_fixtures.py --horizons
  python scripts/prayer_formula/astronomy_fixtures.py --usno
"""

from __future__ import annotations

import argparse
import json
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "test-data" / "engine-reference"
HORIZONS = "https://ssd.jpl.nasa.gov/api/horizons.api"
USNO = "https://aa.usno.navy.mil/api/rstt/oneday"

PLACES = [
    ("Tunis", 36.8065, 10.1815), ("Paris", 48.8566, 2.3522), ("London", 51.5074, -0.1278),
    ("Mecca", 21.4225, 39.8262), ("Cairo", 30.0444, 31.2357), ("Rabat", 34.0209, -6.8416),
    ("Muscat", 23.588, 58.3829), ("Dakar", 14.7167, -17.4677), ("Oslo", 59.9139, 10.7522),
    ("Helsinki", 60.1699, 24.9384), ("Nairobi", -1.2921, 36.8219), ("Johannesburg", -26.2041, 28.0473),
    ("Lagos", 6.5244, 3.3792), ("Moscow", 55.7558, 37.6173),
]
DATES = ["2026-01-15", "2026-03-20", "2026-06-21", "2026-09-23", "2026-12-21", "2099-06-21"]
EVENTS = {"Begin Civil Twilight": "civil_begin", "Rise": "rise", "Upper Transit": "transit", "Set": "set",
          "End Civil Twilight": "civil_end"}


def get(url: str) -> str:
    with urllib.request.urlopen(url, timeout=120) as response:
        return response.read().decode("utf-8")


def horizons() -> None:
    query = {
        "format": "text", "COMMAND": "'10'", "OBJ_DATA": "'NO'", "MAKE_EPHEM": "'YES'", "EPHEM_TYPE": "'OBSERVER'",
        "CENTER": "'500@399'", "START_TIME": "'2020-01-01 03:17'", "STOP_TIME": "'2100-12-31'", "STEP_SIZE": "'37 d'",
        "TIME_TYPE": "'TT'", "QUANTITIES": "'2,20'", "ANG_FORMAT": "'DEG'", "EXTRA_PREC": "'YES'", "CSV_FORMAT": "'YES'",
        "CAL_FORMAT": "'JD'",
    }
    text = get(HORIZONS + "?" + urllib.parse.urlencode(query, safe="'"))
    body = text.split("$$SOE")[1].split("$$EOE")[0]
    lines = ["# JPL Horizons (DE441), the Sun from the Earth's centre: TT Julian day, apparent RA (deg), apparent Dec (deg),",
             "# distance (AU). Fetched by astronomy_fixtures.py --horizons; public domain (NASA/JPL)."]
    for row in body.strip().splitlines():
        cells = [c.strip() for c in row.split(",")]
        jd, ra, dec, delta = cells[0], cells[3], cells[4], cells[5]
        lines.append(f"{jd}\t{ra}\t{dec}\t{delta}")
    (OUT / "horizons_sun.tsv").write_text("\n".join(lines) + "\n", encoding="ascii", newline="\n")
    print(f"{len(lines) - 2} epochs")


def usno() -> None:
    lines = ["# US Naval Observatory, rise/set/transit of the Sun in UT (tz=0), to the minute, '-' when it does not occur:",
             "# place, latitude, longitude, UT date, civil twilight begins, rise, transit, set, civil twilight ends.",
             "# Fetched by astronomy_fixtures.py --usno; public domain (USNO)."]
    for name, lat, lng in PLACES:
        for date in DATES:
            query = {"date": date, "coords": f"{lat},{lng}", "tz": "0"}
            data = json.loads(get(USNO + "?" + urllib.parse.urlencode(query)))
            times = {EVENTS[e["phen"]]: e["time"] for e in data["properties"]["data"]["sundata"] if e["phen"] in EVENTS}
            row = [times.get(key, "-") for key in ("civil_begin", "rise", "transit", "set", "civil_end")]
            lines.append("\t".join([name, f"{lat}", f"{lng}", date] + row))
            time.sleep(0.5)
    (OUT / "usno_sun.tsv").write_text("\n".join(lines) + "\n", encoding="ascii", newline="\n")
    print(f"{len(lines) - 3} place-days")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--horizons", action="store_true")
    parser.add_argument("--usno", action="store_true")
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    if args.horizons:
        horizons()
    if args.usno:
        usno()
    if not (args.horizons or args.usno):
        parser.print_help()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
