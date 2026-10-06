#!/usr/bin/env python3
"""
Reconstruction of the prayer-time formula used by INM (meteo.tn) for every
Tunisian delegation. Reproduces every published minute we have checked: the
full bundled 2026 year and meteo.tn samples of 2020-2025 (fitted on 2026 only).

Sun: Meeus, "Astronomical Formulae for Calculators" (1900 epoch), with the
apparent longitude (nutation + aberration) and the apparent obliquity.

Per delegation: INM's published 3-decimal latitude/longitude and an integer
elevation h in metres. The horizon dip is acos(R / (R + h)) with R = 6378137 m,
where INM evaluates the quotient in single precision (float32).

  Dhuhr   = solar noon + 7 min            sun at 0h UT of the date
  Asr     = shadow ratio 1 (Maliki/Shafi'i/Hanbali), same 0h UT sun
  Fajr    = sun at -(18 deg + dip)        morning
  Sunrise = sun at -(0.83 deg + dip)      morning
  Maghrib = sunset at -(0.83 deg + dip) + 2 min
  Isha    = sun at -(18 deg + dip)        evening

Fajr, Sunrise, Maghrib and Isha start from the sun at 0h UT and re-evaluate it
five times at the event's apparent solar time (12h -/+ hour angle) taken as a UT
hour of the date. Time zone UTC+1, no DST; every time is rounded half-up to the
minute.

`overrides` holds quirks of INM's own yearly tables: its 2026 Zeriba sunrise
uses elevation 15.6 m instead of 156 m (its 2020-2025 sunrises use 156 m).

Usage:
  python scripts/prayer_formula/inm_prayer_times.py --verify
  python scripts/prayer_formula/inm_prayer_times.py --delegation 615 --year 2027 --month 3
"""

from __future__ import annotations

import argparse
import calendar
import datetime
import json
import math
import struct
from pathlib import Path

D2R = math.pi / 180
TZ = 1.0
DHUHR_OFFSET_MIN = 7
MAGHRIB_OFFSET_MIN = 2
TWILIGHT_ANGLE = 18.0
HORIZON_ANGLE = 0.83
EARTH_RADIUS_M = 6378137.0
ITERATIONS = 5

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
PARAMS_PATH = REPO / "data/prayer-formula/delegation_params.json"
CSV_DIR = REPO / "docs/csv"  # meteo.tn tables scraped for the website
COLUMNS = ["Fajr", "Shuruk", "Duhr", "Asr", "Maghrib", "Isha"]
_FLOAT32 = struct.Struct("f")


def julian_day(date: datetime.date) -> float:
    """Julian day at 0h UT."""
    a = (14 - date.month) // 12
    y = date.year + 4800 - a
    m = date.month + 12 * a - 3
    jdn = date.day + (153 * m + 2) // 5 + 365 * y + y // 4 - y // 100 + y // 400 - 32045
    return jdn - 0.5


def sun(jd: float) -> tuple[float, float]:
    """Meeus 1900-epoch solar position: (declination in degrees, equation of time in minutes)."""
    t = (jd - 2415020.0) / 36525
    mean_long = (279.69668 + 36000.76892 * t + 0.0003025 * t * t) % 360
    anomaly = 358.47583 + 35999.04975 * t - 0.000150 * t * t - 0.0000033 * t ** 3
    e = 0.01675104 - 0.0000418 * t - 0.000000126 * t * t
    center = ((1.919460 - 0.004789 * t - 0.000014 * t * t) * math.sin(anomaly * D2R)
              + (0.020094 - 0.000100 * t) * math.sin(2 * anomaly * D2R)
              + 0.000293 * math.sin(3 * anomaly * D2R))
    omega = 259.18 - 1934.142 * t
    apparent_long = mean_long + center - 0.00569 - 0.00479 * math.sin(omega * D2R)
    obliquity = (23.452294 - 0.0130125 * t - 0.00000164 * t * t + 0.000000503 * t ** 3
                 + 0.00256 * math.cos(omega * D2R))
    decl = math.asin(math.sin(obliquity * D2R) * math.sin(apparent_long * D2R)) / D2R
    y = math.tan(obliquity / 2 * D2R) ** 2
    eot = (y * math.sin(2 * mean_long * D2R) - 2 * e * math.sin(anomaly * D2R)
           + 4 * e * y * math.sin(anomaly * D2R) * math.cos(2 * mean_long * D2R)
           - 0.5 * y * y * math.sin(4 * mean_long * D2R) - 1.25 * e * e * math.sin(2 * anomaly * D2R))
    return decl, 4 * eot / D2R


def dip_from_elevation(elevation_m: float) -> float:
    """Horizon dip in degrees; INM computes R / (R + h) in single precision."""
    ratio = _FLOAT32.unpack(_FLOAT32.pack(EARTH_RADIUS_M / (EARTH_RADIUS_M + elevation_m)))[0]
    return math.acos(ratio) / D2R


def hour_angle(altitude: float, lat: float, decl: float) -> float:
    """Hours between solar noon and the moment the sun reaches `altitude`."""
    cos_h = (math.sin(altitude * D2R) - math.sin(lat * D2R) * math.sin(decl * D2R)) / (
        math.cos(lat * D2R) * math.cos(decl * D2R))
    return math.acos(max(-1.0, min(1.0, cos_h))) / D2R / 15


def horizon_event(date: datetime.date, lat: float, lng: float, altitude: float, sign: int) -> float:
    """Local time in hours; sign -1 = morning, +1 = evening."""
    jd0 = julian_day(date)
    decl, eot = sun(jd0)
    for _ in range(ITERATIONS):
        decl, eot = sun(jd0 + (12 + sign * hour_angle(altitude, lat, decl)) / 24)
    return 12 + sign * hour_angle(altitude, lat, decl) - eot / 60 - lng / 15 + TZ


def prayer_minutes(date: datetime.date, lat: float, lng: float, elevation_m: float,
                   sunrise_elevation_m: float | None = None) -> list[float]:
    """Unrounded minutes after local midnight: Fajr, Sunrise, Dhuhr, Asr, Maghrib, Isha."""
    decl0, eot0 = sun(julian_day(date))
    noon = 12 - eot0 / 60 - lng / 15 + TZ
    asr_altitude = math.atan(1 / (1 + math.tan(abs(lat - decl0) * D2R))) / D2R
    asr = noon + hour_angle(asr_altitude, lat, decl0)
    dip = dip_from_elevation(elevation_m)
    sunrise_dip = dip if sunrise_elevation_m is None else dip_from_elevation(sunrise_elevation_m)
    return [
        horizon_event(date, lat, lng, -(TWILIGHT_ANGLE + dip), -1) * 60,
        horizon_event(date, lat, lng, -(HORIZON_ANGLE + sunrise_dip), -1) * 60,
        noon * 60 + DHUHR_OFFSET_MIN,
        asr * 60,
        horizon_event(date, lat, lng, -(HORIZON_ANGLE + dip), +1) * 60 + MAGHRIB_OFFSET_MIN,
        horizon_event(date, lat, lng, -(TWILIGHT_ANGLE + dip), +1) * 60,
    ]


def prayer_times(date: datetime.date, params: dict) -> list[str]:
    override = params.get("overrides", {}).get(str(date.year), {})
    minutes = prayer_minutes(date, params["lat"], params["lng"], params["elevation_m"],
                             override.get("sunrise_elevation_m"))
    return [f"{m // 60:02d}:{m % 60:02d}" for m in (math.floor(x + 0.5) for x in minutes)]


def load_params() -> dict[int, dict]:
    return {int(k): v for k, v in json.loads(PARAMS_PATH.read_text(encoding="utf-8")).items()}


def verify(year: int) -> None:
    params = load_params()
    total = mismatches = 0
    per_column = [0] * 6
    for did, p in sorted(params.items()):
        for month in range(1, 13):
            path = CSV_DIR / str(did) / str(year) / f"{month:02d}.csv"
            if not path.exists():
                continue
            for line in path.read_text().strip().splitlines()[1:]:
                cells = line.split(",")
                if not cells[1]:
                    continue
                got = prayer_times(datetime.date(year, month, int(cells[0])), p)
                for k in range(6):
                    total += 1
                    if got[k] != cells[k + 1]:
                        mismatches += 1
                        per_column[k] += 1
    print(f"{len(params)} delegations, {total} times, {total - mismatches} exact "
          f"({100 * (total - mismatches) / total:.3f}%)")
    print("mismatches per column:", dict(zip(COLUMNS, per_column)))


def print_month(did: int, year: int, month: int) -> None:
    p = load_params()[did]
    print("Day," + ",".join(COLUMNS))
    for day in range(1, calendar.monthrange(year, month)[1] + 1):
        print(f"{day:02d}," + ",".join(prayer_times(datetime.date(year, month, day), p)))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--verify", action="store_true", help="compare against the bundled CSVs")
    parser.add_argument("--year", type=int, default=2026)
    parser.add_argument("--delegation", type=int, help="delegation id (e.g. 615 = Tunis)")
    parser.add_argument("--month", type=int, help="print one month as CSV")
    args = parser.parse_args()
    if args.verify:
        verify(args.year)
    elif args.delegation:
        for month in [args.month] if args.month else range(1, 13):
            print_month(args.delegation, args.year, month)
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
