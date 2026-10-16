#!/usr/bin/env python3
"""
Fits `ly_awqaf`, Libya's General Authority of Awqaf (plan 8.1, 8.2, 8.7, 8.8; milestone AM2, 9.4), and writes its
official-zone asset, android-app/app/src/main/assets/official-zones/ly_awqaf.json (schema 1).

Data, read at run time from test-data-local and never copied (D13, A7):
- research-2026-10-11/LY/official_rows.csv: the 107 official rows of the 2026-10-11 recovery: Tripoli on 62 dates
  (awqaf.gov.ly homepage captures of 2016-2018 and 2025-2026; api.ifta.ly, which gives the Awqaf's values, on
  2026-10-08 to 10-18), Benghazi on 5 (captures of 2014 and 2016; 2026-10-08 and 10-11) and the 20 other cities
  on 2026-10-08 and 10-11 (the Awqaf's city endpoint).
- research-2026-10-11/LY/cities.csv: each city's town centre and where it comes from.
- research-2026-10-08/LY/ly/awqaf_home.html: the site's city selector, its codes and Arabic labels in its order.
- research-2026-10-11/HIJRI/icop_ghc_1428_1450.csv: ICOP's Hijri months, only to count held-out Ramadan days.

The table is perpetual, keyed by month and day (a month-day is identical in 2016, 2018 and 2025). The search loops
read each row at the reference year (E5) with the fast closed-form code below (the profile takes the sun once a
day); every score printed and every FitStats comes from common.compute (the reference engine, which the Kotlin
engine matches) at the row's actual date, as the app shows it (A8).

Stages (9.4), each by windows (a published minute bounds the unrounded time to a minute under E1, and the value
most windows cover wins), on Tripoli, the only city with rows across the year:
1. Dhuhr: the sun model (E14), from the Dhuhr column alone (it bounds offset - 4 x longitude).
2. The evaluation (E4), then the reference year, by the joint count of all six times over a grid of points.
3. The minutes on Dhuhr and Maghrib (sunrise and Asr take none, 8.2), the twilight latitude (E17) and the
   rounding: up, down and nearest give the same table at points 0.125 deg of longitude apart, so the rounding
   is the one that puts Tripoli nearest the town.
4. Tripoli's point and the twilight angle, common to Fajr and Isha: the grid point nearest the centre of the
   region that reproduces the most minutes, and the middle of the angle window there.
Held out: Tripoli by month-split (stages 1-4 refitted on the odd months and scored on the even ones, and the
reverse). The other 21 cities never enter the profile. Each keeps its town's latitude (two October days hardly fix
one), and its town centre where that reproduces as many of the city's minutes as any longitude within 0.5 deg does,
each minute within the model's precision (the median distance by which Tripoli's misses at its own point fall
outside their minute); otherwise it takes the middle of the longitudes that do. An event the town centre misses by
2 min or more on every day takes an integer zone offset (Jalo's and Awjila's Asr). Held out by day-split
(Benghazi: month-split): the point and offset fitted on one part and scored on the other.

Usage: python scripts/prayer_formula/fits/ly_awqaf/fit.py [--no-guard]
"""

from __future__ import annotations

import argparse
import csv
import datetime
import math
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import common  # noqa: E402

ref = common.ref
EVENTS = common.EVENTS
FIXED = ("SUNRISE", "DHUHR", "ASR", "MAGHRIB")
TWILIGHT = ("FAJR", "ISHA")
KEYS = dict(zip(EVENTS, ("fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha")))
AUTHORITY = "ly_awqaf"
TZ = "Africa/Tripoli"                   # UTC+2 without summer time since 2013-10-25 (checked on every row)
LY = common.LOCAL_DATA / "research-2026-10-11" / "LY"
HOME = common.LOCAL_DATA / "research-2026-10-08" / "LY" / "ly" / "awqaf_home.html"
GHC = common.LOCAL_DATA / "research-2026-10-11" / "HIJRI" / "icop_ghc_1428_1450.csv"
E17 = 0.99330562                        # 1 - e^2
D2R = math.pi / 180
MODELS = ("TEXTBOOK", "MEEUS", "VSOP87")
MODES = ("DAILY_10H_UT", "DAILY_0H_UT", "KACST", "EVENT_INSTANT")
FIRST_YEAR = 1980                       # reference years tried: from here to the table's first capture
EAST_SHIFT = {"DOWN": 0.0, "NEAREST": 0.125, "UP": 0.25}  # the same table at a point this much further east
START_OFFSETS = {"DHUHR": 4, "MAGHRIB": 4}                  # 8.2's minutes; stage 3 checks them
NAME_AR = {"Ojala": "أوجلة"}            # the selector labels Awjila «اجدابيا» like Ajdabiya; the app says أوجلة (8.8)
SOURCE_LINE = ("Libya's General Authority of Awqaf (awqaf.gov.ly): 22 city tables from one perpetual table keyed by "
               "month and day, computed for the actual year (A8); Dar al-Ifta's Tripoli API gives the same values")
TRIPOLI_SOURCE = "awqaf.gov.ly homepage captures (2016-2018, 2025-2026) and api.ifta.ly (2026-10-08 to 10-18): Tripoli"
CITY_SOURCE = "awqaf.gov.ly city endpoint (2026-10-08, 10-11)"
CITIES_SOURCE = CITY_SOURCE + " and Benghazi homepage captures (2014, 2016)"
# The validity guard's clear cases (6.5, 9.6), as a side check: Sabratha and Ghat at ZoneMappingTest's points, the
# others at approximate points (GeoNames-like, not verified). True = the plan expects the nearest table kept.
GUARD_PLACES = (("Sabratha", 32.7933, 12.4885, True), ("Zuwara", 32.9312, 12.0820, True), ("Nalut", 31.8685, 10.9812, False),
                ("Wazin", 31.9700, 10.7100, False), ("Tazirbu", 25.6650, 21.1200, False), ("Ghat", 24.9647, 10.1728, False))


# ------------------------------------------------------------------------------------------------------------
# Data

def minute(text: str) -> int:
    h, m = text.split(":")
    return int(h) * 60 + int(m)


def load_rows() -> dict[str, dict[datetime.date, dict[str, int]]]:
    """City code -> date -> the six published minutes (local clock)."""
    out: dict[str, dict[datetime.date, dict[str, int]]] = {}
    with open(LY / "official_rows.csv", encoding="utf-8-sig", newline="") as fh:
        for r in csv.DictReader(fh):
            day = datetime.date.fromisoformat(r["date"])
            if common.utc_offset(TZ, day) != 7200:
                raise SystemExit(f"{r['city']} {day}: not UTC+2")
            times = {e: minute(r[KEYS[e]]) for e in EVENTS}
            old = out.setdefault(r["city"], {}).get(day)
            if old is not None and old != times:
                raise SystemExit(f"{r['city']} {day}: two different rows")
            out[r["city"]][day] = times
    return out


def load_selector() -> list[tuple[str, str]]:
    """The site's city selector: (code, Arabic label), in its order."""
    html = HOME.read_text(encoding="utf-8", errors="replace")
    return [(code, label.strip()) for code, label in re.findall(r"value='([A-Za-z]+)'[^>]*>([^<]+)<", html)
            if any(0x0600 <= ord(ch) <= 0x06FF for ch in label)]


def load_centres() -> dict[str, dict]:
    with open(LY / "cities.csv", encoding="utf-8-sig", newline="") as fh:
        return {r["city"]: {"lat": float(r["lat"]), "lng": float(r["lon"]), "en": r["name_en"], "label": r["name_ar_on_site"],
                            "src": r["coord_source"]} for r in csv.DictReader(fh)}


def ramadan_test():
    """Whether a date is in Ramadan by ICOP's calendar for the west (held-out Ramadan days only; no E8 rule here)."""
    starts = {}
    with open(GHC, encoding="utf-8-sig", newline="") as fh:
        for r in csv.DictReader(fh):
            starts[(int(r["year"]), r["month"].strip())] = datetime.date.fromisoformat(r["west_start"])
    spans = [(s, starts[(y, "شوال")]) for (y, m), s in starts.items() if m == "رمضان" and (y, "شوال") in starts]
    return lambda day: any(a <= day < b for a, b in spans)


def at_year(day: datetime.date, year: int | None) -> datetime.date:
    """The table's row for [day]'s month and day computed at [year] (E5); None keeps the actual date."""
    if year is None:
        return day
    return datetime.date(year, day.month, 28 if (day.month, day.day) == (2, 29) else day.day)


# ------------------------------------------------------------------------------------------------------------
# Fast model for the search loops (S2-S7 with the sun of S4; Libya's cycle date is the civil date)

_SUN: dict = {}


def sun(day: datetime.date, p: dict, local_minute: int) -> tuple[float, float]:
    """(declination, equation of time) the profile's sun function gives on [day] for an event near [local_minute]."""
    mode = p["eval"]
    key = (day, p["sun"], mode, local_minute if mode == "EVENT_INSTANT" else None)
    v = _SUN.get(key)
    if v is None:
        fn = ref.SUN_MODELS[p["sun"]]
        jd0 = ref.julian_day0(day)
        if mode == "DAILY_10H_UT":
            v = fn(jd0 + 10 / 24)[:2]
        elif mode == "DAILY_0H_UT":
            v = fn(jd0)[:2]
        elif mode == "KACST":
            v = (fn(jd0 + 21 / 24)[0], fn(jd0 + 9 / 24)[1])
        else:
            v = fn(jd0 + (local_minute / 60 - 2) / 24)[:2]
        _SUN[key] = v
    return v


def hour_angle(decl: float, lat: float, alt: float) -> float:
    c = (math.sin(alt * D2R) - math.sin(lat * D2R) * math.sin(decl * D2R)) / (math.cos(lat * D2R) * math.cos(decl * D2R))
    return math.acos(c) / D2R if -1 <= c <= 1 else float("nan")


def base_minute(e: str, day: datetime.date, lat: float, t: dict, p: dict, alpha: float | None) -> float:
    """The unrounded local minute of event [e] at longitude 0, before any offset."""
    decl, eot = sun(day, p, t[e])
    noon = 840 - eot                    # 12:00 UT + 2 h, less the equation of time
    if e == "DHUHR":
        return noon
    if e == "ASR":
        noon_decl = sun(day, p, t["DHUHR"])[0]
        alt = math.atan(1 / (1 + math.tan(abs(lat - noon_decl) * D2R))) / D2R
        return noon + 4 * hour_angle(decl, lat, alt)
    h = hour_angle(decl, lat, -0.8333) if e in ("SUNRISE", "MAGHRIB") else hour_angle(decl, lat * p["scale"], -alpha)
    return noon + (4 * h if e in ("MAGHRIB", "ISHA") else -4 * h)


def round_minute(u: float, mode: str) -> int:
    if mode == "DOWN":
        return math.floor(u + 1e-3 / 60)
    if mode == "UP":
        return math.ceil(u - 1e-3 / 60)
    return math.floor(u + 0.5)


def bounds(m: int, mode: str) -> tuple[float, float]:
    """The unrounded minutes [lo, hi) that a published minute allows under a rounding (E1)."""
    return {"DOWN": (m, m + 1), "NEAREST": (m - 0.5, m + 0.5), "UP": (m - 1, m)}[mode]


def shift(e: str, p: dict, zone_off: dict) -> float:
    return p["off"].get(e, 0) + zone_off.get(e, 0)


def cover(intervals) -> tuple[int, list[tuple[float, float]]]:
    """The largest number of intervals [lo, hi) with a common point, and the stretches where it is reached."""
    marks = sorted([(lo, 1) for lo, hi in intervals] + [(hi, -1) for lo, hi in intervals])
    best, cur, stretches = 0, 0, []
    for i, (x, s) in enumerate(marks):
        cur += s
        nxt = marks[i + 1][0] if i + 1 < len(marks) else x
        if nxt <= x:
            continue
        if cur > best:
            best, stretches = cur, [(x, nxt)]
        elif cur == best and best > 0:
            if stretches and stretches[-1][1] == x:
                stretches[-1] = (stretches[-1][0], nxt)
            else:
                stretches.append((x, nxt))
    return best, stretches


def angle_window(e: str, day, lat: float, lng: float, t: dict, p: dict, zone_off: dict) -> tuple[float, float]:
    """The twilight angles that give [e]'s published minute at this point."""
    decl, eot = sun(day, p, t[e])
    noon = 840 - eot - 4 * lng + shift(e, p, zone_off)
    lo, hi = bounds(t[e], p["rnd"])
    h1, h2 = ((noon - hi) / 4, (noon - lo) / 4) if e == "FAJR" else ((lo - noon) / 4, (hi - noon) / 4)
    phi = lat * p["scale"] * D2R

    def depression(h: float) -> float:
        s = math.cos(h * D2R) * math.cos(phi) * math.cos(decl * D2R) + math.sin(phi) * math.sin(decl * D2R)
        return -math.asin(max(-1.0, min(1.0, s))) / D2R
    return depression(h1), depression(h2)


def joint(days, lat: float, lng: float, p: dict, alpha: float | None = None, zone_off: dict | None = None):
    """Exact minutes at a point (days at their reference dates): (count, angle window), with the best common angle
    or at [alpha]."""
    zone_off = zone_off or {}
    n = 0
    wins = []
    for day, t in days:
        for e in FIXED:
            n += round_minute(base_minute(e, day, lat, t, p, None) - 4 * lng + shift(e, p, zone_off), p["rnd"]) == t[e]
        for e in TWILIGHT:
            wins.append(angle_window(e, day, lat, lng, t, p, zone_off))
    if alpha is not None:
        return n + sum(a <= alpha < b for a, b in wins), (alpha, alpha)
    c, stretches = cover(wins)
    return n + c, (stretches[0] if stretches else (float("nan"), float("nan")))


def steps(centre: float, half: float, step: float) -> list[float]:
    n = int(round(half / step))
    return [round(centre + i * step, 6) for i in range(-n, n + 1)]


def region(days, p: dict, lats, lngs):
    """The grid points with the most exact minutes: (count, picked lat, picked lng, all best points)."""
    res = [(joint(days, la, lo, p)[0], la, lo) for la in lats for lo in lngs]
    top = max(r[0] for r in res)
    best = [r for r in res if r[0] == top]
    clat = sum(r[1] for r in best) / len(best)
    clng = sum(r[2] for r in best) / len(best)
    k = math.cos(clat * D2R)
    pick = min(best, key=lambda r: (r[1] - clat) ** 2 + (k * (r[2] - clng)) ** 2)
    return top, pick[1], pick[2], best


def with_year(days, year):
    return [(at_year(d, year), t) for d, t in days]


def middle(values):
    """The middle of tied values (the lower one for an even count): a neutral tie-break."""
    v = sorted(values)
    return v[(len(v) - 1) // 2]


def miss_distances(days, lat: float, lng: float, p: dict, zone_off: dict | None = None) -> list[float]:
    """How far beyond its minute window each missed minute falls, in minutes (days at their reference dates)."""
    zone_off = zone_off or {}
    out = []
    for d, t in days:
        for e in EVENTS:
            u = base_minute(e, d, lat, t, p, p["alpha"]) - 4 * lng + shift(e, p, zone_off)
            lo, hi = bounds(t[e], p["rnd"])
            if not lo <= u < hi:
                out.append(lo - u if u < lo else u - hi)
    return out


# ------------------------------------------------------------------------------------------------------------
# Stages 1-4 on Tripoli

def dhuhr_cover(days, model: str, mode: str, year) -> tuple[int, float]:
    """Stage 1: Dhuhr windows of x = offset - 4 x longitude (DOWN); (most windows, the middle of their stretch)."""
    p = {"sun": model, "eval": mode}
    wins = []
    for day, t in days:
        _, eot = sun(at_year(day, year), p, t["DHUHR"])
        lo = t["DHUHR"] - (840 - eot)
        wins.append((lo, lo + 1))
    c, stretches = cover(wins)
    a, b = stretches[0]
    return c, (a + b) / 2


def fit_tripoli(days, centre: tuple[float, float], last_year: int, say=print) -> dict:
    """Stages 1-4 on one row per month-day of Tripoli; the profile values and Tripoli's point. Reference years run
    to [last_year], the year of the table's first capture: a perpetual table is computed before it is shown (and
    the evaluation hour trades with the year: 0h UT in 2029 is 10h UT in 2006 to within 20 min)."""
    clat, clng = centre
    years_range = range(FIRST_YEAR, last_year + 1)
    s1 = {m: max(dhuhr_cover(days, m, mode, y)[0] for mode in MODES for y in years_range) for m in MODELS}
    models = [m for m in MODELS if s1[m] == max(s1.values())]     # a tie goes on to stage 2
    say("  stage 1, Dhuhr windows (of %d): %s" % (len(days), ", ".join(f"{m} {s1[m]}" for m in MODELS)))

    def coarse(p, year, half_lat=0.06, half_lng=0.02):
        x = dhuhr_cover(days, p["sun"], p["eval"], year)[1]
        lng0 = (p["off"]["DHUHR"] - x) / 4 + EAST_SHIFT[p["rnd"]]
        q = dict(p, year=year)
        top, la, lo, _ = region(with_year(days, year), q, steps(clat, half_lat, 0.01), steps(lng0, half_lng, 0.005))
        return top, la, lo

    s2 = {}
    for model in models:
        for mode in MODES:
            q = {"sun": model, "eval": mode, "off": dict(START_OFFSETS), "scale": E17, "rnd": "DOWN"}
            counts = {y: coarse(q, y) for y in years_range}
            s2[(model, mode)] = (max(v[0] for v in counts.values()), counts)
    model, mode = max(s2, key=lambda k: s2[k][0])
    say("  stage 2, joint count at the best year (of %d): %s" % (
        6 * len(days), ", ".join(f"{m}/{e} {s2[(m, e)][0]}" for m, e in s2)))
    p = {"sun": model, "eval": mode, "off": dict(START_OFFSETS), "scale": E17, "rnd": "DOWN"}
    years = s2[(model, mode)][1]
    top_year = max(v[0] for v in years.values())
    tied = [y for y in years_range if years[y][0] == top_year]
    if len(tied) > 1:                   # a finer grid, then the middle year: no year is preferred beforehand
        fine = {y: region(with_year(days, y), dict(p, year=y), steps(years[y][1], 0.02, 0.002), steps(years[y][2], 0.01, 0.002))[0]
                for y in tied}
        tied = [y for y in tied if fine[y] == max(fine.values())]
    year = middle(tied)
    runner = sorted(((v[0], y) for y, v in years.items() if y != year), reverse=True)[:3]
    say(f"  stage 2, reference year {year} ({top_year}); next: " + ", ".join(f"{y} {c}" for c, y in runner))
    p["year"] = year
    rows = with_year(days, year)
    _, lat, lng = years[year]

    # Stage 3: integer minutes on Dhuhr and Maghrib at the coarse point, the twilight latitude, the rounding.
    for e in ("DHUHR", "MAGHRIB"):
        counts = {k: sum(round_minute(base_minute(e, d, lat, t, p, None) - 4 * lng + k, "DOWN") == t[e] for d, t in rows)
                  for k in range(0, 9)}
        p["off"][e] = max(counts, key=lambda k: (counts[k], -k))
    scales = {}
    for sc in (1.0, E17):
        scales[sc] = coarse(dict(p, scale=sc), year)[0]
    p["scale"] = max(scales, key=lambda s: (scales[s], s == 1.0))
    rounding = {}
    for rnd in EAST_SHIFT:
        top, la, lo = coarse(dict(p, rnd=rnd), year)
        rounding[rnd] = (top, lo)
    best_r = max(v[0] for v in rounding.values())
    p["rnd"] = min((r for r in rounding if rounding[r][0] == best_r), key=lambda r: abs(rounding[r][1] - clng))
    say("  stage 3, offsets %s; E17 %d against %d without; rounding %s" % (
        p["off"], scales[E17], scales[1.0],
        ", ".join(f"{r} {rounding[r][0]} at {rounding[r][1]:.3f}E" for r in EAST_SHIFT)) + f" -> {p['rnd']}")

    # Stage 4: the point on a 0.001 deg grid (recentred until the region is inside it), then the angle there.
    top, lat, lng = coarse(p, year)
    for _ in range(4):
        lats, lngs = steps(round(lat, 3), 0.02, 0.001), steps(round(lng, 3), 0.012, 0.001)
        top, lat, lng, best = region(rows, p, lats, lngs)
        if all(lats[0] < r[1] < lats[-1] and lngs[0] < r[2] < lngs[-1] for r in best):
            break
    _, (a, b) = joint(rows, lat, lng, p)
    alpha = round((a + b) / 2, 3)
    if not a <= alpha < b:
        alpha = round((a + b) / 2, 4)
    p["alpha"] = alpha
    sep = {e: cover([angle_window(e, d, lat, lng, t, p, {}) for d, t in rows]) for e in TWILIGHT}
    lat_span = (min(r[1] for r in best), max(r[1] for r in best))
    lng_span = (min(r[2] for r in best), max(r[2] for r in best))
    say("  stage 4, point %.3f N %.3f E (%d of %d; region %.3f-%.3f N, %.3f-%.3f E), angle %.3f (window %.4f-%.4f);"
        " Fajr alone %d at %.4f-%.4f, Isha alone %d at %.4f-%.4f" % (
            lat, lng, top, 6 * len(rows), *lat_span, *lng_span, alpha, a, b,
            sep["FAJR"][0], *sep["FAJR"][1][0], sep["ISHA"][0], *sep["ISHA"][1][0]))
    return {"p": p, "point": (lat, lng), "count": top, "of": 6 * len(rows), "lat_span": lat_span, "lng_span": lng_span,
            "window": (a, b)}


# ------------------------------------------------------------------------------------------------------------
# The other cities

def zone_offsets(days, centre, p) -> dict[str, float]:
    """An integer zone offset, by windows, for an event the town centre misses by 2 min or more on every day."""
    out = {}
    lat, lng = centre
    for e in EVENTS:
        wins, far = [], True
        for d, t in days:
            u = base_minute(e, d, lat, t, p, p["alpha"]) - 4 * lng + p["off"].get(e, 0)
            far &= abs(round_minute(u, p["rnd"]) - t[e]) >= 2
            lo, hi = bounds(t[e], p["rnd"])
            wins.append((lo - u, hi - u))
        if far:
            a, b = cover(wins)[1][0]
            out[e] = float(math.ceil(a) if math.ceil(a) < b else round((a + b) / 2))
    return out


def lng_windows(days, lat: float, p: dict, zone_off: dict):
    """Per published minute, the longitudes at [lat] that give it, each window widened by the model's precision."""
    tol = p.get("tolerance", 0.0)
    out = []
    for d, t in days:
        for e in EVENTS:
            u = base_minute(e, d, lat, t, p, p["alpha"]) + shift(e, p, zone_off)
            lo, hi = bounds(t[e], p["rnd"])
            out.append(((u - hi - tol) / 4, (u - lo + tol) / 4))
    return out


def city_point(days, centre, p, zone_off) -> tuple[tuple[float, float], bool, int, int]:
    """(point, fitted?, the most minutes a longitude reproduces at the town's latitude, those the town centre
    reproduces), each minute within the model's precision. A few October days hardly fix a latitude (it moves
    sunrise and sunset by under 1 min per degree then), so the town's latitude stays and only the longitude is
    fitted, within 0.5 deg of the town's: the middle of the longitudes that reproduce the most minutes, nearest the
    town's, when the town centre reproduces fewer."""
    clat, clng = centre
    wins = lng_windows(days, clat, p, zone_off)
    at_centre = sum(a <= clng < b for a, b in wins)
    top, stretches = cover([w for w in ((max(a, clng - 0.5), min(b, clng + 0.5)) for a, b in wins) if w[1] > w[0]])
    if at_centre >= top:
        return centre, False, top, at_centre
    a, b = min(stretches, key=lambda s: min(abs(s[0] - clng), abs(s[1] - clng)))
    return (clat, round((a + b) / 2, 5)), True, top, at_centre


def fit_city(dates, rows, centre, p):
    days = with_year([(d, rows[d]) for d in dates], p["year"])
    zone_off = zone_offsets(days, centre, p)
    point, fitted, top, at_centre = city_point(days, centre, p, zone_off)
    return {"point": point, "fitted": fitted, "offsets": zone_off, "top": top, "at_centre": at_centre, "of": 6 * len(days)}


def folds(dates):
    """Month-split where the dates cover odd and even months, else leave one date out."""
    odd = [d for d in dates if d.month % 2]
    even = [d for d in dates if not d.month % 2]
    if odd and even:
        return [(odd, even), (even, odd)], True
    return [([x for x in dates if x != d], [d]) for d in dates], False


# ------------------------------------------------------------------------------------------------------------
# Scores with the reference engine

def method_of(p: dict) -> dict:
    return common.method_json(
        id=AUTHORITY, fajr={"type": "angle", "degrees": p["alpha"]}, isha={"type": "angle", "degrees": p["alpha"]},
        offsets=dict(p["off"]), rounding={e: p["rnd"] for e in EVENTS}, sunModel=p["sun"],
        sunEvaluation={e: p["eval"] for e in EVENTS}, twilightLatitudeScale=p["scale"], highLatitude={"type": "angle_based"},
        evidence={"kind": "FROM_TABLES", "source": "fit"})


def zone_of(zid: str, point, offsets: dict) -> dict:
    return common.zone_json(zid, point[0], point[1], offsets={e: float(v) for e, v in offsets.items()})


def table_minutes(method: dict, zone: dict, day: datetime.date) -> dict[str, int]:
    lat, lng = zone["point"]["latE5"] / 1e5, zone["point"]["lngE5"] / 1e5
    got = common.compute(common.case(method, lat, lng, TZ, day, zone=zone, country="LY"))
    off = common.utc_offset(TZ, day)
    return {e: (got[e][1] + off) // 60 % 1440 for e in EVENTS}


def score_into(score: common.Score, method: dict, zone: dict, items, year=None):
    """Adds each (date, published times) as the app shows it (year None: the actual date) or at a reference year."""
    for d, t in items:
        got = table_minutes(method, zone, at_year(d, year))
        for e in EVENTS:
            score.add(e, d, got[e], t[e])


def merge(scores) -> common.Score:
    out = common.Score()
    for s in scores:
        for e in EVENTS:
            out.exact[e] += s.exact[e]
            out.within1[e] += s.within1[e]
            out.total[e] += s.total[e]
            for m in range(12):
                out.months[e][m] += s.months[e][m]
        out.worst = max(out.worst, s.worst)
        out.misses += s.misses
    return out


def shares(score: common.Score) -> str:
    return " ".join(f"{e[:3]} {score.exact[e]}/{score.total[e]}" for e in EVENTS)


# ------------------------------------------------------------------------------------------------------------
# LCRSSS's Benghazi Ramadan 1440 table, a side check outside FitStats

def lcrsss_check(method: dict, zone: dict, centre_zone: dict, p: dict, say=print):
    """The Libyan Center for Remote Sensing and Space Science's Benghazi table of 2019-05-06 to 06-04 (another
    publisher, likely the Awqaf table's computer; five times in a 12-hour clock), from the hand transcription in the
    research's bgz.py (its only local copy, read as text, never run)."""
    path = common.LOCAL_DATA / "research-2026-10-08" / "LY" / "ly" / "bgz.py"
    found = re.search(r'rows="""(.*?)"""', path.read_text(encoding="utf-8"), re.S) if path.exists() else None
    if not found:
        return
    five = ("FAJR", "DHUHR", "ASR", "MAGHRIB", "ISHA")
    items = []
    for line in found.group(1).strip().splitlines():
        month, day, *clock = line.split()
        pub = {}
        for e, text in zip(five, clock):
            h, m = (int(x) for x in text.split(":"))
            pub[e] = (h + (12 if e != "FAJR" and h < 12 else 0)) * 60 + m
        items.append((datetime.date(2019, int(month), int(day)), pub))
    for label, z in (("zone point", zone), ("town centre", centre_zone)):
        out = []
        for year_label, year in (("2019", None), (str(p["year"]), p["year"])):
            n = sum(table_minutes(method, z, at_year(d, year))[e] == pub[e] for d, pub in items for e in five)
            out.append(f"{n}/{5 * len(items)} computed for {year_label}")
        say(f"  LCRSSS Benghazi, Ramadan 1440, at the {label}: " + ", ".join(out))


# ------------------------------------------------------------------------------------------------------------
# The validity guard's clear cases (6.5), a side check

def km(lat1, lng1, lat2, lng2) -> float:
    a = (math.sin((lat2 - lat1) * D2R / 2) ** 2
         + math.cos(lat1 * D2R) * math.cos(lat2 * D2R) * math.sin((lng2 - lng1) * D2R / 2) ** 2)
    return 2 * 6371.0 * math.asin(math.sqrt(a))


def guard_check(method: dict, zones: list[dict], year: int = 2026, say=print):
    days = [datetime.date(year, 1, 1) + datetime.timedelta(days=i) for i in range(365)]

    def run(lat, lng, zone=None):
        return [common.compute(common.case(method, lat, lng, TZ, d, zone=zone, country="LY")) for d in days]

    def leads(r_days, t_days):
        out = {e: [] for e in EVENTS}
        for r, t in zip(r_days, t_days):
            for e in EVENTS:
                lead = (r[e][0] - t[e][1]) / 60
                out[e].append(-lead if e == "SUNRISE" else lead)
        return out

    margins = {}
    for name, lat, lng, expect in GUARD_PLACES:
        z = min(zones, key=lambda z: km(lat, lng, z["lat"], z["lng"]))
        if z["id"] not in margins:
            zone = zone_of(z["id"], (z["lat"], z["lng"]), z["offsets"])
            t_days = run(z["lat"], z["lng"], zone)
            margins[z["id"]] = (t_days, {e: max(v) for e, v in leads(run(z["lat"], z["lng"]), t_days).items()})
        t_days, margin = margins[z["id"]]
        excess = {e: max(v) - margin[e] for e, v in leads(run(lat, lng), t_days).items()}
        worst = max(excess, key=excess.get)
        keeps = excess[worst] <= 4
        say("  %-8s -> %-10s %4.0f km: worst %s %+.1f min beyond the margin: %s%s" % (
            name, z["id"], km(lat, lng, z["lat"], z["lng"]), worst, excess[worst],
            "keeps the table" if keeps else "own point", "" if keeps == expect else "  (plan: the other verdict)"))


# ------------------------------------------------------------------------------------------------------------

def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--no-guard", action="store_true", help="skip the validity-guard side check")
    args = ap.parse_args(argv)
    sys.stdout.reconfigure(encoding="utf-8")

    rows = load_rows()
    centres = load_centres()
    selector = load_selector()
    in_ramadan = ramadan_test()
    codes = [c for c, _ in selector]
    if sorted(codes) != sorted(rows) or sorted(codes) != sorted(centres):
        raise SystemExit(f"cities differ: selector {sorted(codes)}, rows {sorted(rows)}, centres {sorted(centres)}")
    labels = dict(selector)
    print(f"{sum(len(v) for v in rows.values())} official rows, {len(rows)} cities; Tripoli {len(rows['Tripoli'])} dates")

    # Tripoli: one row per month-day for the fit (repeated month-days must be identical: a perpetual table).
    trip = rows["Tripoli"]
    by_md: dict[tuple[int, int], tuple[datetime.date, dict]] = {}
    for d in sorted(trip):
        prev = by_md.get((d.month, d.day))
        if prev is not None and prev[1] != trip[d]:
            raise SystemExit(f"Tripoli {d}: differs from {prev[0]}, not a perpetual table")
        by_md.setdefault((d.month, d.day), (d, trip[d]))
    trip_centre = (centres["Tripoli"]["lat"], centres["Tripoli"]["lng"])
    first_capture = min(d for dates in rows.values() for d in dates).year

    print(f"Tripoli, all rows (reference years {FIRST_YEAR}-{first_capture}, the first capture):")
    final = fit_tripoli(list(by_md.values()), trip_centre, first_capture)
    p = final["p"]
    method = method_of(p)
    # The model's precision: how far Tripoli's misses at its own point fall beyond their minute; the cities' windows
    # are widened by the median, so that a town centre is not moved for a miss of a second or two.
    misses = sorted(miss_distances(with_year(list(by_md.values()), p["year"]), *final["point"], p))
    p["tolerance"] = round(middle(misses), 3) if misses else 0.0
    print(f"  precision: Tripoli's {len(misses)} misses fall " + ", ".join(f"{m:.3f}" for m in misses)
          + f" min beyond their minute; median {p['tolerance']}")

    # Held out: Tripoli by month-split.
    trip_scores = []
    for label, keep in (("odd", 1), ("even", 0)):
        print(f"Tripoli, {label} months:")
        part = fit_tripoli([v for k, v in by_md.items() if k[0] % 2 == keep], trip_centre, first_capture)
        s = common.Score()
        score_into(s, method_of(part["p"]), zone_of("Tripoli", part["point"], {}),
                   [(d, trip[d]) for d in sorted(trip) if d.month % 2 != keep])
        trip_scores.append(s)
        print(f"  held out on the {'even' if keep else 'odd'} months: " + shares(s))
    trip_held = merge(trip_scores)

    # The other cities: points and zone offsets with the final profile; held out by day-split or month-split.
    fits, held, fold_kind = {}, {}, {}
    for code in codes:
        if code == "Tripoli":
            continue
        centre = (centres[code]["lat"], centres[code]["lng"])
        dates = sorted(rows[code])
        fits[code] = fit_city(dates, rows[code], centre, p)
        parts, by_month = folds(dates)
        fold_kind[code] = by_month
        s = common.Score()
        for fit_on, score_on in parts:
            f = fit_city(fit_on, rows[code], centre, p)
            score_into(s, method, zone_of(code, f["point"], f["offsets"]), [(d, rows[code][d]) for d in score_on])
        held[code] = s

    # Zones, FitStats and the asset.
    first, last = min(trip), max(trip)
    trip_sample = common.sample(TRIPOLI_SOURCE, first, last, len(trip), 1)
    trip_split = common.sample(TRIPOLI_SOURCE, first, last, len(trip), 1, month_split=True)
    zones, guard_zones = [], []
    city_dates = [d for code in fits for d in rows[code]]
    for code in codes:
        c = centres[code]
        if code == "Tripoli":
            point, offsets, kind = final["point"], {}, "fitted"
            fitted_on, held_out, score = [trip_sample], [trip_split], trip_held
            ram = sum(in_ramadan(d) for d in trip)
        else:
            f = fits[code]
            point, offsets, kind = f["point"], f["offsets"], ("fitted" if f["fitted"] else "town centre")
            dates = sorted(rows[code])
            where = CITY_SOURCE + (" and homepage captures (2014, 2016)" if dates[0].year < 2026 else "") + f": {code}"
            fitted_on = [trip_sample, common.sample(where, dates[0], dates[-1], len(dates), 1)]
            held_out = [common.sample(where + (", month-split" if fold_kind[code] else ", day-split"),
                                      dates[0], dates[-1], len(dates), 1, month_split=fold_kind[code])]
            score = held[code]
            ram = sum(in_ramadan(d) for d in dates)
        # The zone's point is its town, which the nearest-city mapping measures from (plan 8.8); a fitted
        # computing point goes into the morning and evening points (E6, README S6), so every time is still
        # computed there.
        centre = common.point(c["lat"], c["lng"])
        computing = common.point(*point)
        zones.append({
            "id": code, "nameAr": NAME_AR.get(code, labels[code]), "point": centre,
            "morningPoint": computing if computing != centre else None,
            "eveningPoint": computing if computing != centre else None,
            "offsets": {e: v for e, v in offsets.items()},
            "namedPlaces": [{"centre": centre, "catalogueIds": [], "areaKm": 0.0}],
            "fit": common.fit_stats(fitted_on, held_out, score, ramadan_days=ram),
        })
        guard_zones.append({"id": code, "lat": computing["latE5"] / 1e5, "lng": computing["lngE5"] / 1e5,
                            "offsets": offsets, "kind": kind})
    all_held = merge([trip_held] + list(held.values()))
    profile_fit = common.fit_stats(
        [trip_sample],
        [trip_split, common.sample(CITIES_SOURCE + ": the 21 other cities, day-split (Benghazi month-split)",
                                   min(city_dates), max(city_dates), len(city_dates), len(fits))],
        all_held, ramadan_days=sum(in_ramadan(d) for d in trip) + sum(in_ramadan(d) for d in city_dates))
    path = common.write_asset(AUTHORITY, AUTHORITY, "LY", "NEAREST", zones, fit=profile_fit, source=SOURCE_LINE)

    # Summary.
    print("Profile: Fajr and Isha %.3f deg at %.8f x latitude, Dhuhr %+d, Maghrib %+d, %s, %s sun at %s, reference year %d" % (
        p["alpha"], p["scale"], p["off"]["DHUHR"], p["off"]["MAGHRIB"], p["rnd"], p["sun"], p["eval"], p["year"]))
    print("Cities (point; held-out exact of 6 per day):")
    for z, g in zip(zones, guard_zones):
        if z["id"] == "Tripoli":
            continue
        f, s = fits[z["id"]], held[z["id"]]
        moved = km(g["lat"], g["lng"], centres[z["id"]]["lat"], centres[z["id"]]["lng"])
        print("  %-10s %-11s %.4f %.4f (%4.1f km)%s  fit %d/%d (centre %d)  held out %d/%d" % (
            z["id"], g["kind"], g["lat"], g["lng"], moved, f" zone {f['offsets']}" if f["offsets"] else "",
            f["top"], f["of"], f["at_centre"], sum(s.exact.values()), sum(s.total.values())))
    final_score, ref_score, phases = common.Score(), common.Score(), {}
    for z, g in zip(zones, guard_zones):
        zone = zone_of(z["id"], (g["lat"], g["lng"]), g["offsets"])
        for d, t in sorted(rows[z["id"]].items()):
            score_into(final_score, method, zone, [(d, t)])
            score_into(phases.setdefault(d.year % 4, common.Score()), method, zone, [(d, t)])
            score_into(ref_score, method, zone, [(d, t)], year=p["year"])
    print("Held out (FitStats), exact per time: " + shares(all_held) + f"; within 1 min {min(all_held.within1_shares().values()):.0%},"
          f" worst {all_held.worst} min")
    print("Final asset on every row, as the app shows it: " + shares(final_score))
    print(f"  by year mod 4 (the reference year {p['year']} is {p['year'] % 4}): " + ", ".join(
        f"{k}: {sum(s.exact.values())}/{sum(s.total.values())}" for k, s in sorted(phases.items())))
    print(f"Final asset on every row, at the reference year {p['year']}: " + shares(ref_score))
    bz = next(g for g in guard_zones if g["id"] == "Benghazi")
    lcrsss_check(method, zone_of("Benghazi", (bz["lat"], bz["lng"]), {}),
                 zone_of("Benghazi", (centres["Benghazi"]["lat"], centres["Benghazi"]["lng"]), {}), p)
    if not args.no_guard:
        print("Validity guard, 2026 (side check):")
        guard_check(method, guard_zones)
    print(f"Wrote {path.relative_to(common.ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
