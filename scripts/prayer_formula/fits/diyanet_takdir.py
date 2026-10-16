#!/usr/bin/env python3
"""
Re-derives Diyanet's takdir (plan 7.2, 7.4) from IGGÖ's 2026 city tables, kept locally in
test-data-local/research-2026-10-11/AT/iggo2026.csv (official tables stay local, D13).

diyanet_eu computes 18° / 16° with the sun once a day at 0h UT (E4 DAILY_0H_UT), sunrise − 7, Dhuhr + 5,
Asr + 4, Maghrib + 7, to the nearest minute. Above 46°N its summer Fajr and Isha follow an effective angle
k·D, D being the sun's depression at solar midnight, wherever k·D is below the angle.

1. Each city's point is fitted on the days its table shows no takdir (Dhuhr for the longitude, then sunrise,
   Asr and Maghrib for the latitude), counting exact minutes.
2. On the takdir days, each published Fajr and Isha minute is inverted into a window of effective angles,
   divided by D: the k values every day allows. The report gives, per city, the k range all days share.

Usage: python scripts/prayer_formula/fits/diyanet_takdir.py [path/to/iggo2026.csv]
"""

from __future__ import annotations

import csv
import datetime
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import prayer_engine_reference as ref  # noqa: E402

ROOT = Path(__file__).resolve().parents[3]
DEFAULT = ROOT.parent.parent.parent / "test-data-local" / "research-2026-10-11" / "AT" / "iggo2026.csv"

# City centres (OpenStreetMap), the starting points of the point fit.
CENTRES = {
    "bregenz": (47.5031, 9.7471), "eisenstadt": (47.8457, 16.5233), "graz": (47.0707, 15.4395),
    "innsbruck": (47.2692, 11.4041), "klagenfurt": (46.6249, 14.3050), "linz": (48.3069, 14.2858),
    "salzburg": (47.8095, 13.0550), "st-poelten": (48.2047, 15.6256), "wien": (48.2082, 16.3738),
}
FAJR, ISHA = 18.0, 16.0


def austria_offset(day: datetime.date) -> int:
    """CET/CEST in hours: summer time from the last Sunday of March to the last Sunday of October."""
    def last_sunday(month: int) -> datetime.date:
        d = datetime.date(day.year, month, 31)
        return d - datetime.timedelta(days=(d.weekday() + 1) % 7)
    return 2 if last_sunday(3) <= day < last_sunday(10) else 1


def fixed_sun(day: datetime.date):
    jd0 = ref.julian_day0(day)
    decl, eot, _ = ref.meeus_sun(jd0)
    return jd0, decl, eot


def local_minutes(hours_utc: float, day: datetime.date) -> float:
    return (hours_utc + austria_offset(day)) * 60


def times(lat: float, lng: float, day: datetime.date) -> dict[str, float | None]:
    """diyanet_eu's unrounded local minutes at (lat, lng) without takdir, with the sun at 0h UT."""
    _, decl, eot = fixed_sun(day)
    noon = 12 - lng / 15 - eot / 60

    def h(alt: float) -> float | None:
        c = (ref.sind(alt) - ref.sind(lat) * ref.sind(decl)) / (ref.cosd(lat) * ref.cosd(decl))
        return None if abs(c) > 1 else ref.acosd(c) / 15

    asr_alt = ref.atand(1 / (1 + ref.tand(abs(lat - decl))))
    out = {}
    for name, alt, side, offset in (("fajr", -FAJR, -1, 0), ("sunrise", -0.8333, -1, -7), ("asr", asr_alt, 1, 4),
                                    ("maghrib", -0.8333, 1, 7), ("isha", -ISHA, 1, 0)):
        hh = h(alt)
        out[name] = None if hh is None else local_minutes(noon + side * hh, day) + offset
    out["dhuhr"] = local_minutes(noon, day) + 5
    return out


def rounded(x: float | None) -> int | None:
    return None if x is None else math.floor(x + 0.5)


def hm(text: str) -> int:
    h, m = text.split(":")
    return int(h) * 60 + int(m)


def load(path: Path) -> dict[str, list[tuple[datetime.date, dict[str, int]]]]:
    rows: dict[str, list] = {}
    with path.open(encoding="utf-8") as f:
        for r in csv.DictReader(f):
            rows.setdefault(r["city"], []).append(
                (datetime.date.fromisoformat(r["date"]), {k: hm(r[k]) for k in ("fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha")}))
    return rows


def takdir_free(day: datetime.date) -> bool:
    return day.month in (1, 2, 3, 4, 10, 11, 12) and not (day.month == 4 and day.day > 15)


def fit_point(city: str, rows) -> tuple[float, float, int, int]:
    lat0, lng0 = CENTRES[city]
    days = [(d, t) for d, t in rows if takdir_free(d)]
    best_lng, best = lng0, -1
    for i in range(-120, 121):
        lng = lng0 + i * 0.002
        score = sum(rounded(times(lat0, lng, d)["dhuhr"]) == t["dhuhr"] for d, t in days)
        if score > best:
            best, best_lng = score, lng
    best_lat, best2 = lat0, -1
    for i in range(-120, 121):
        lat = lat0 + i * 0.002
        score = 0
        for d, t in days:
            c = times(lat, best_lng, d)
            score += sum(rounded(c[k]) == t[k] for k in ("sunrise", "asr", "maghrib", "fajr", "isha"))
        if score > best2:
            best2, best_lat = score, lat
    total = sum(sum(rounded(times(best_lat, best_lng, d)[k]) == t[k] for k in t) for d, t in days)
    return best_lat, best_lng, total, 6 * len(days)


def k_window(lat: float, lng: float, day: datetime.date, published: int, side: int) -> tuple[float, float] | None:
    """The k values for which k·D, as Fajr (side −1) or Isha (+1), rounds to the published minute."""
    _, decl, eot = fixed_sun(day)
    depression = 90 - abs(lat) - decl if lat >= 0 else 90 - abs(lat) + decl
    noon_local = local_minutes(12 - lng / 15 - eot / 60, day)
    bounds = []
    for m in (published - 0.5, published + 0.5):
        hh = side * (m - noon_local) / 60 * 15  # hour angle in degrees
        sin_alt = ref.sind(lat) * ref.sind(decl) + ref.cosd(lat) * ref.cosd(decl) * ref.cosd(hh)
        bounds.append(-ref.asind(max(-1, min(1, sin_alt))))
    lo, hi = sorted(bounds)
    return lo / depression, hi / depression


def main() -> int:
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT
    rows = load(path)
    print("city        lat       lng       exact (days without takdir)   k_F window          k_I window          takdir days F/I")
    for city in sorted(rows):
        lat, lng, exact, total = fit_point(city, rows[city])
        kf = [0.0, 9.0]
        ki = [0.0, 9.0]
        nf = ni = 0
        for d, t in rows[city]:
            plain = times(lat, lng, d)
            if plain["fajr"] is None or t["fajr"] > rounded(plain["fajr"]) + 1:
                w = k_window(lat, lng, d, t["fajr"], -1)
                kf = [max(kf[0], w[0]), min(kf[1], w[1])]
                nf += 1
            if plain["isha"] is None or t["isha"] < rounded(plain["isha"]) - 1:
                w = k_window(lat, lng, d, t["isha"], 1)
                ki = [max(ki[0], w[0]), min(ki[1], w[1])]
                ni += 1
        print(f"{city:11s} {lat:8.4f} {lng:9.4f}   {exact:4d}/{total:<4d}                   "
              f"[{kf[0]:.4f}, {kf[1]:.4f}]   [{ki[0]:.4f}, {ki[1]:.4f}]   {nf}/{ni}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
