#!/usr/bin/env python3
"""
Fits `ye_awqaf`, the General Authority of Awqaf and Guidance in Sanaa (plan 8.1, 8.2, 8.7, 8.8; milestone AM2, 9.4),
and writes its official-zone asset, android-app/app/src/main/assets/official-zones/ye_awqaf.json (schema 1).

Data, read at run time from test-data-local and never copied (D13, A7): the authority's daily JSON for its 31
locations (awqaf.gov.ye, the "Tebyan" panel's get_page_data?fetch_date=...), one file per date:
- official/ye/d2026MMDD.json: the backfill of 2026-10-11, 134 dates from 2026-05-21 (the endpoint's first date) to
  2026-10-11 (10 dates answered empty); official/ye/MANIFEST.tsv gives each file's URL and SHA-256;
- research-2026-10-08/YE/ye/api_2026-*.json and YE/ye_skeptic/data/d2026*.json: 16 of the same dates fetched on
  2026-10-08, read too (a date fetched twice must agree).
Times are 12-hour "H:MM:00" with no AM/PM: Asr, Maghrib and Isha take 12 h (Dhuhr where it reads before 06:00).

Method (8.2): Fajr 18 deg, Isha 16 deg, Dhuhr = transit + 10, Asr factor 1 with the noon shadow at the declination
of 00:00 local time (E4 LOCAL_MIDNIGHT_SHADOW), sunrise and sunset on the pressure horizon at the location's
elevation (E2 Pressure), Maghrib = sunset + 6 at or below 650 m and + 10 at or above 1,100 m (E10), nearest minute.
Stages (9.4), each by windows (a published minute bounds the unrounded time to a minute; the value most windows
cover wins; within that stretch a conventional value first):
1. Dhuhr: the sun model (E14), by one longitude per location from the Dhuhr minutes alone (transit at the event
   instant; the other evaluations are reported).
2. Points, elevations and classes: for each location, a latitude grid (coarse 0.01 deg, then 0.001 deg); at each
   latitude the longitude windows of Fajr, Dhuhr, Asr and Isha, and at each candidate longitude the horizon-altitude
   windows of sunrise and sunset, under either Maghrib class (+ 6 below 1,100 m, + 10 above 650 m, as E10 allows);
   the point is the cell of the best region nearest its centroid (the town where the town is in it), the elevation
   the middle of its altitude stretch through E2's pressure law.
3. Asr: the evaluation (E4) and the geometric or apparent sun (E13), by Dhuhr and Asr windows together at each
   location's latitude.
4. Fajr and Isha angles, the Dhuhr minutes and each class's Maghrib minutes, by windows at the fitted points.
Held out: a month split (fit on May, July and September, score June, August and October, and the reverse), every
stage refitted on each half. Scores come from common.compute (the reference engine, which the Kotlin engine
matches); the fast solver of the search loops (the same iteration on a sun tabulated hourly) agrees with it to
0.001 s.

Usage: python scripts/prayer_formula/fits/ye_awqaf/fit.py [--no-guard]
"""

from __future__ import annotations

import argparse
import datetime
import glob
import json
import math
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import common  # noqa: E402

ref = common.ref
EV = common.EVENTS
AUTHORITY = "ye_awqaf"
TZ = "Asia/Aden"                        # UTC+3, no summer time
TZH = 3.0
D2R = math.pi / 180
TOL = 0.1 / 3600                        # S3: 0.1 s, in hours
MODELS = ("MEEUS", "VSOP87")
LOW_MAX, HIGH_MIN = 650, 1100           # E10's limits (m)
CLASS_MIN = {6: 6, 10: 10}              # E10's minutes
FAJR_DEG, ISHA_DEG, DHUHR_MIN = 18.0, 16.0, 10
H_RANGE = (-200.0, 3500.0)              # elevations a location may take (m)
ASR_MODES = ("LOCAL_MIDNIGHT_SHADOW", "EVENT_INSTANT", "DAILY_0H_UT", "KACST")
DATA = (common.LOCAL_DATA / "official" / "ye", common.LOCAL_DATA / "research-2026-10-08" / "YE")
SOURCE = "awqaf.gov.ye Tebyan panel JSON (31 locations)"
SOURCE_LINE = ("Yemen's General Authority of Awqaf and Guidance (Sanaa), awqaf.gov.ye: daily tables of its 31 locations, "
               "Aden-held areas included, since 2026-05-21")
# location id -> (GeoNames id, town lat, town lng, GeoNames dem): the town the location names (a governorate's entry:
# its capital: Al Hazm for الجوف, Al Jabin for ريمة, Ataq for شبوة, Al Ghaydah for المهرة, Hadibu for سقطرى, Al Hawtah
# for لحج). GeoNames cities1000 and the YE extract (Seiyun), read 2026-10-15.
TOWNS = {
    "1": (71137, 15.35452, 44.20646, 2253), "2": (415189, 12.77957, 45.03852, 7), "3": (71334, 16.94021, 43.76393, 1876),
    "4": (78754, 14.54248, 49.12424, 39), "401": (70981, 15.94333, 48.79333, 649), "402": (70160, 16.05694, 48.99889, 617),
    "5": (70225, 13.57952, 44.02091, 1316), "6": (78428, 15.6594, 43.94385, 2239), "7": (74477, 13.96667, 44.18333, 1931),
    "701": (76154, 13.83446, 44.11469, 1857), "8": (79415, 14.79781, 42.95452, 10), "801": (69500, 14.1951, 43.31518, 114),
    "802": (78909, 14.83069, 43.15391, 77), "803": (76991, 14.51635, 43.32446, 151), "804": (77408, 15.05898, 43.28731, 191),
    "9": (72968, 15.46253, 45.32581, 1095), "10": (75337, 15.69425, 43.60582, 1795), "1001": (70357, 16.00295, 43.19725, 194),
    "11": (78977, 15.47007, 43.54481, 2124), "12": (76184, 14.54274, 44.40514, 2421), "13": (79836, 13.98523, 45.57272, 2005),
    "1301": (71491, 14.4151, 44.83709, 2125), "14": (79455, 16.16406, 44.77692, 1116), "15": (401043, 14.70389, 43.59917, 2427),
    "16": (80384, 13.69572, 44.73137, 1517), "17": (73560, 13.05667, 44.88194, 136), "18": (77726, 14.53767, 46.83187, 1148),
    "19": (69426, 13.12871, 45.38073, 20), "1901": (74368, 13.223, 45.30559, 71), "20": (79700, 16.20787, 52.17605, 17),
    "21": (75427, 12.64881, 54.01895, 13),
}
# The validity guard's cases (6.5, 8.8, 9.6), a side check: place, lat, lng, GeoNames dem, the plan's verdict (True:
# keeps its location's table, False: own point, None: near the threshold). GeoNames points.
GUARD_PLACES = (("Al Mahabishah", 15.94758, 43.43587, 1570, True), ("Yarim", 14.29804, 44.37795, 2626, True),
                ("Mocha", 13.31814, 43.24991, 1, None), ("Rumah", 17.45532, 50.57789, 697, False),
                ("Kilmia (Abd al-Kuri)", 12.18576, 52.23332, 10, False))


# ------------------------------------------------------------------------------------------------------------
# Data

def clock(text: str, pm: bool) -> int:
    h, m, _s = (int(x) for x in text.split(":"))
    if pm and h < 12:
        h += 12
    return h * 60 + m


def load() -> tuple[dict, dict, dict]:
    """date -> location id -> six published minutes; the locations' Arabic names; date -> the `season` field."""
    files = sorted(glob.glob(str(DATA[0] / "d20*.json")))  # the collector adds 2027 and later dates (scripts/collectors/ye.py)
    files += sorted(glob.glob(str(DATA[1] / "ye" / "api_2026-*.json")))
    files += sorted(glob.glob(str(DATA[1] / "ye_skeptic" / "data" / "d2026*.json")))
    days, names, seasons = {}, {}, {}
    for f in files:
        msg = json.loads(Path(f).read_text(encoding="utf-8")).get("message") or {}
        doc = msg.get("doc")
        if not doc:
            continue                    # the endpoint's empty answers
        day = datetime.date.fromisoformat(doc["fetch_date"])
        rows = {}
        for loc in msg["locations"]:
            lid = str(loc["location_id"])
            dhuhr = clock(loc["duhur"], False)
            rows[lid] = {"FAJR": clock(loc["fajr"], False), "SUNRISE": clock(loc["sunrise"], False),
                         "DHUHR": dhuhr + (720 if dhuhr < 360 else 0), "ASR": clock(loc["asr"], True),
                         "MAGHRIB": clock(loc["maghrib"], True), "ISHA": clock(loc["isha"], True)}
            names[lid] = loc["location_name"].strip()
        if day in days and days[day] != rows:
            raise SystemExit(f"{day}: two different answers")
        if set(rows) != set(TOWNS):
            raise SystemExit(f"{day}: locations {sorted(rows)}")
        days[day] = rows
        seasons[day] = doc.get("season")
    return days, names, seasons


# ------------------------------------------------------------------------------------------------------------
# Fast solver for the search loops: S3's iteration on a sun tabulated hourly (linear interpolation).

class SunTable:
    def __init__(self, model: str, day: datetime.date):
        fn = ref.SUN_MODELS[model]
        self.fn = fn
        self.jd0 = ref.julian_day0(day)
        self.v = [fn(self.jd0 + h / 24) for h in range(-4, 27)]

    def at(self, t: float) -> tuple[float, float]:
        i = math.floor(t)
        f = t - i
        a, b = self.v[i + 4], self.v[i + 5]
        return a[0] + f * (b[0] - a[0]), a[1] + f * (b[1] - a[1])


def transit(tab: SunTable, lng: float) -> float:
    m = 12 - lng / 15
    t = m
    for _ in range(10):
        tn = m - tab.at(t)[1] / 60
        if abs(tn - t) < TOL:
            return tn
        t = tn
    return t


def event(tab: SunTable, lat: float, lng: float, alt: float, side: int, t0: float) -> tuple[float, float, float]:
    """(hours UT, declination there, hour angle in degrees) of the event at altitude [alt] on [side]."""
    m = 12 - lng / 15
    t = t0
    sa, sp, cp = math.sin(alt * D2R), math.sin(lat * D2R), math.cos(lat * D2R)
    d = h = 0.0
    for _ in range(10):
        d, e = tab.at(t)
        c = (sa - sp * math.sin(d * D2R)) / (cp * math.cos(d * D2R))
        h = math.acos(max(-1.0, min(1.0, c))) / D2R
        tn = m - e / 60 + side * h / 15
        if abs(tn - t) < TOL:
            return tn, d, h
        t = tn
    return t, d, h


def pressure_alt(h: float) -> float:
    """E2 Pressure: −(0.2667 + 0.5667·e^(−h/8434))."""
    return -(0.2667 + 0.5667 * math.exp(-h / 8434))


def elevation_of(alt: float) -> float:
    return -8434 * math.log((-alt - 0.2667) / 0.5667)


A_REF = pressure_alt(1000.0)


def asr_declination(tab: SunTable, mode: str, t0: float) -> float:
    if mode == "LOCAL_MIDNIGHT_SHADOW":
        return tab.at(-TZH)[0]          # 00:00 local of D = 21:00 UT of D − 1
    if mode == "DAILY_0H_UT":
        return tab.v[4][0]
    if mode == "KACST":
        return tab.fn(tab.jd0 + 21 / 24)[0]
    return tab.at(t0)[0]


def day_minutes(tab: SunTable, lat: float, lng: float, asr_mode: str = "LOCAL_MIDNIGHT_SHADOW",
                asr_sun: str = "GEOMETRIC") -> dict:
    """Unrounded local minutes at (lat, lng): Fajr, Dhuhr + 10, Asr, Isha, and sunrise and sunset at A_REF with
    their slopes dT/da (min per degree of horizon altitude)."""
    t0 = transit(tab, lng)
    dn = asr_declination(tab, asr_mode, t0)
    zen = abs(lat - dn)
    if asr_sun == "APPARENT":
        h0 = 90 - zen
        h0a = h0 + ref.bennett(h0, 1010.0, 10.0)
        aa = math.atan(1 / (1 + math.tan((90 - h0a) * D2R))) / D2R
        a_asr = aa - ref.bennett(aa, 1010.0, 10.0)
    else:
        a_asr = math.atan(1 / (1 + math.tan(zen * D2R))) / D2R
    if asr_mode in ("DAILY_0H_UT", "KACST"):
        asr = fixed_event(tab, asr_mode, lat, lng, a_asr)
    else:
        asr = event(tab, lat, lng, a_asr, 1, t0)[0]
    sr, dr, hr = event(tab, lat, lng, A_REF, -1, t0)
    ss, ds, hs = event(tab, lat, lng, A_REF, 1, t0)
    cosa = math.cos(A_REF * D2R)
    cp = math.cos(lat * D2R)
    return {"FAJR": (event(tab, lat, lng, -FAJR_DEG, -1, t0)[0] + TZH) * 60, "DHUHR": (t0 + TZH) * 60 + DHUHR_MIN,
            "ASR": (asr + TZH) * 60, "ISHA": (event(tab, lat, lng, -ISHA_DEG, 1, t0)[0] + TZH) * 60,
            "SR": (sr + TZH) * 60, "GR": 4 * cosa / (cp * math.cos(dr * D2R) * math.sin(hr * D2R)),
            "SS": (ss + TZH) * 60, "GS": -4 * cosa / (cp * math.cos(ds * D2R) * math.sin(hs * D2R))}


def fixed_event(tab: SunTable, mode: str, lat: float, lng: float, alt: float) -> float:
    """Asr with the whole day's sun fixed (DAILY_0H_UT, KACST: δ at 21:00 UT and E at 09:00 UT)."""
    if mode == "DAILY_0H_UT":
        d, e = tab.v[4][0], tab.v[4][1]
    else:
        d, e = tab.fn(tab.jd0 + 21 / 24)[0], tab.fn(tab.jd0 + 9 / 24)[1]
    c = (math.sin(alt * D2R) - math.sin(lat * D2R) * math.sin(d * D2R)) / (math.cos(lat * D2R) * math.cos(d * D2R))
    return 12 - lng / 15 - e / 60 + math.acos(c) / D2R / 15


# ------------------------------------------------------------------------------------------------------------
# Windows

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


def count_at(intervals, x: float) -> int:
    return sum(lo <= x < hi for lo, hi in intervals)


def pick(stretches, near: float, conventional=()) -> float:
    """In the stretch nearest [near]: a conventional value first, then the fewest decimals nearest its middle."""
    a, b = min(stretches, key=lambda s: 0 if s[0] <= near < s[1] else min(abs(s[0] - near), abs(s[1] - near)))
    for v in conventional:
        if a <= v < b:
            return v
    mid = (a + b) / 2
    for decimals in range(0, 6):
        q = 10 ** decimals
        cands = [k / q for k in range(math.ceil(a * q), math.floor(b * q) + 1) if a <= k / q < b]
        if cands:
            return min(cands, key=lambda v: abs(v - mid))
    return mid


def choose(windows, conventional: float, whole: bool = False) -> tuple[float, int, int]:
    """(value, its windows, the best count): the conventional value unless another covers more windows than the data's
    resolution allows (0.2 % of them, at least 1); a whole number where the parameter is one (E10's and Dhuhr's minutes)."""
    best, stretches = cover(windows)
    at_conv = count_at(windows, conventional)
    if at_conv >= best - max(1, int(0.002 * len(windows))):
        return conventional, at_conv, best
    if whole:
        k = max(range(int(conventional) - 5, int(conventional) + 6), key=lambda v: count_at(windows, v))
        return float(k), count_at(windows, k), best
    v = pick(stretches, conventional)
    return v, count_at(windows, v), best


def lng_window(u: float, p: int, lng0: float) -> tuple[float, float]:
    """The longitudes at which a time computed as [u] at [lng0] rounds to the published minute [p]."""
    return lng0 + (u - p - 0.5) / 4, lng0 + (u - p + 0.5) / 4


def horizon_windows(rows, dlng: float, k: int) -> list:
    """Per day, the horizon altitudes for sunrise, and for sunset with Maghrib + k, at a longitude shift [dlng]."""
    lo_h, hi_h = (H_RANGE[0], HIGH_MIN - 1e-6) if k == 6 else (LOW_MAX + 1e-6, H_RANGE[1])
    a_lo, a_hi = pressure_alt(lo_h), pressure_alt(hi_h)
    out = []
    for p, m in rows:
        t = m["SR"] - 4 * dlng
        x1, x2 = A_REF + (p["SUNRISE"] - 0.5 - t) / m["GR"], A_REF + (p["SUNRISE"] + 0.5 - t) / m["GR"]
        lo, hi = max(min(x1, x2), a_lo), min(max(x1, x2), a_hi)
        if hi > lo:
            out.append((lo, hi))
        t = m["SS"] - 4 * dlng + k
        x1, x2 = A_REF + (p["MAGHRIB"] - 0.5 - t) / m["GS"], A_REF + (p["MAGHRIB"] + 0.5 - t) / m["GS"]
        lo, hi = max(min(x1, x2), a_lo), min(max(x1, x2), a_hi)
        if hi > lo:
            out.append((lo, hi))
    return out


# ------------------------------------------------------------------------------------------------------------
# Stage 2: a location's point, elevation and class

def evaluate_lat(lid: str, lat: float, lng0: float, pub: list, tabs: dict, opts: dict, lng_step: float, slack: int):
    """At one latitude: the best cells (count, lng, k, altitude stretches) among longitudes near the best of the
    horizon-free times."""
    rows = [(p, day_minutes(tabs[d], lat, lng0, opts["asr_mode"], opts["asr_sun"])) for d, p in pub]
    ws = [lng_window(m[e], p[e], lng0) for p, m in rows for e in ("FAJR", "DHUHR", "ASR", "ISHA")]
    best_base, stretches = cover(ws)
    marks = sorted([(lo, 1) for lo, hi in ws] + [(hi, -1) for lo, hi in ws])
    # longitude candidates: where the horizon-free count is within [slack] of its best
    cands = []
    cur = 0
    for i, (x, s) in enumerate(marks):
        cur += s
        nxt = marks[i + 1][0] if i + 1 < len(marks) else x
        if nxt > x and cur >= best_base - slack:
            n = max(1, int((nxt - x) / lng_step))
            cands += [x + (j + 0.5) * (nxt - x) / n for j in range(n)]
    out = []
    for lng in cands:
        base = count_at(ws, lng)
        for k in (6, 10):
            hw = horizon_windows(rows, lng - lng0, k)
            c, st = cover(hw) if hw else (0, [])
            out.append((base + c, lng, k, st))
    return out


def fit_location(lid: str, pub: list, tabs: dict, opts: dict) -> dict:
    g, tlat, tlng, dem = TOWNS[lid]
    best = None
    for i in range(-15, 16):                                   # coarse: ±0.15 deg of latitude
        lat = tlat + i * 0.01
        for c, lng, k, st in evaluate_lat(lid, lat, tlng, pub, tabs, opts, 0.002, 6):
            if best is None or c > best[0]:
                best = (c, lat, lng)
    cells = []
    for i in range(-12, 13):                                   # fine: ±0.012 deg around the best
        lat = round(best[1] + i * 0.001, 6)
        for c, lng, k, st in evaluate_lat(lid, lat, best[2], pub, tabs, opts, 0.0005, 3):
            cells.append((c, lat, lng, k, st))
    top = max(c[0] for c in cells)
    region = [c for c in cells if c[0] == top]
    clat = sum(c[1] for c in region) / len(region)
    clng = sum(c[2] for c in region) / len(region)
    kx = math.cos(clat * D2R)
    c, lat, lng, k, st = min(region, key=lambda c: (c[1] - clat) ** 2 + (kx * (c[2] - clng)) ** 2)
    # The town itself, where it reaches the best count.
    town_rows = [(p, day_minutes(tabs[d], tlat, tlng, opts["asr_mode"], opts["asr_sun"])) for d, p in pub]
    town_ws = [lng_window(m[e], p[e], tlng) for p, m in town_rows for e in ("FAJR", "DHUHR", "ASR", "ISHA")]
    town_best = max((count_at(town_ws, tlng) + (cover(horizon_windows(town_rows, 0.0, kk)) or (0,))[0], kk) for kk in (6, 10))
    at_town = town_best[0] >= top
    if at_town:
        lat, lng, k = tlat, tlng, town_best[1]
        st = cover(horizon_windows(town_rows, 0.0, k))[1]
    a_dem = pressure_alt(dem)
    a, b = min(st, key=lambda s: 0 if s[0] <= a_dem < s[1] else min(abs(s[0] - a_dem), abs(s[1] - a_dem)))
    h = int(round(elevation_of((a + b) / 2)))
    return {"lat": round(lat, 5), "lng": round(lng, 5), "k": k, "h": h, "h_range": (elevation_of(a), elevation_of(b)),
            "count": top, "of": 6 * len(pub), "at_town": at_town, "region": len(region),
            "lat_span": (min(c[1] for c in region), max(c[1] for c in region)),
            "lng_span": (min(c[2] for c in region), max(c[2] for c in region))}


# ------------------------------------------------------------------------------------------------------------
# The profile's stages

def dhuhr_cover(days: dict, dates, model: str, mode: str = "EVENT_INSTANT") -> tuple[int, float]:
    """Stage 1: the Dhuhr windows one longitude per location covers, summed; and the summed widths of the best
    stretches (a tie-break: the wider, the more robust)."""
    fn = ref.SUN_MODELS[model]
    total, width = 0, 0.0
    for lid, (g, lat, lng, dem) in TOWNS.items():
        ws = []
        for d in dates:
            if mode == "EVENT_INSTANT":
                t0 = transit(TABLES[model][d], lng)
            else:
                e = fn(ref.julian_day0(d) + {"DAILY_0H_UT": 0, "DAILY_10H_UT": 10, "KACST": 9}[mode] / 24)[1]
                t0 = 12 - lng / 15 - e / 60
            ws.append(lng_window((t0 + TZH) * 60 + DHUHR_MIN, days[d][lid]["DHUHR"], lng))
        c, st = cover(ws)
        total += c
        width += max(b - a for a, b in st)
    return total, width


def asr_cover(days: dict, dates, fits: dict, model: str, mode: str, asr_sun: str) -> int:
    """Stage 3: Dhuhr and Asr windows together, one longitude per location at its fitted latitude."""
    total = 0
    for lid in TOWNS:
        lat, lng = fits[lid]["lat"], fits[lid]["lng"]
        ws = []
        for d in dates:
            m = day_minutes(TABLES[model][d], lat, lng, mode, asr_sun)
            ws += [lng_window(m["DHUHR"], days[d][lid]["DHUHR"], lng), lng_window(m["ASR"], days[d][lid]["ASR"], lng)]
        total += cover(ws)[0]
    return total


def angle_windows(days: dict, dates, fits: dict, model: str, event_name: str, deg: float) -> list:
    """Stage 4: the twilight angles that give each published Fajr or Isha minute at the fitted points."""
    side = -1 if event_name == "FAJR" else 1
    ws = []
    for lid in TOWNS:
        lat, lng = fits[lid]["lat"], fits[lid]["lng"]
        for d in dates:
            tab = TABLES[model][d]
            t0 = transit(tab, lng)
            t, dd, hh = event(tab, lat, lng, -deg, side, t0)
            u = (t + TZH) * 60
            slope = 4 * side * math.cos(-deg * D2R) / (math.cos(lat * D2R) * math.cos(dd * D2R) * math.sin(hh * D2R))
            p = days[d][lid][event_name]
            # dT/d(altitude) = -side*4*cos(a)/(...); the angle is -altitude
            x1, x2 = deg + (p - 0.5 - u) / slope, deg + (p + 0.5 - u) / slope
            ws.append((min(x1, x2), max(x1, x2)))
    return ws


def offset_windows(days: dict, dates, fits: dict, model: str, which: str) -> dict:
    """Stage 4: the Dhuhr minutes after transit, and each class's Maghrib minutes after its pressure sunset."""
    out = {}
    for lid in TOWNS:
        f = fits[lid]
        for d in dates:
            m = day_minutes(TABLES[model][d], f["lat"], f["lng"])
            p = days[d][lid]
            if which == "DHUHR":
                u = m["DHUHR"] - DHUHR_MIN
                key = "DHUHR"
                pp = p["DHUHR"]
            else:
                u = m["SS"] + m["GS"] * (pressure_alt(f["h"]) - A_REF)
                key = f["k"]
                pp = p["MAGHRIB"]
            out.setdefault(key, []).append((pp - 0.5 - u, pp + 0.5 - u))
    return out


TABLES: dict = {}


def fit_fold(days: dict, dates, say=print, label: str = "") -> tuple[dict, dict, dict]:
    """Stages 1-4 on [dates]; returns the profile values, each location's fit and a report."""
    rep = {}
    s1 = {m: dhuhr_cover(days, dates, m) for m in MODELS}
    model = max(MODELS, key=lambda m: (s1[m][0], s1[m][1]))
    rep["dhuhr"] = s1
    rep["dhuhr_modes"] = {mode: dhuhr_cover(days, dates, model, mode)[0] for mode in ("EVENT_INSTANT", "DAILY_0H_UT", "DAILY_10H_UT", "KACST")}
    opts = {"asr_mode": "LOCAL_MIDNIGHT_SHADOW", "asr_sun": "GEOMETRIC"}
    t = time.perf_counter()
    fits = {}
    for i, lid in enumerate(sorted(TOWNS, key=int)):
        fits[lid] = fit_location(lid, [(d, days[d][lid]) for d in dates], TABLES[model], opts)
    say(f"  {label}stage 2: 31 locations fitted in {time.perf_counter() - t:.0f} s")
    rep["asr"] = {(mode, sun): asr_cover(days, dates, fits, model, mode, sun)
                  for mode in ASR_MODES for sun in ("GEOMETRIC", "APPARENT")}
    best_asr = max(rep["asr"], key=lambda k: (rep["asr"][k], k == ("LOCAL_MIDNIGHT_SHADOW", "GEOMETRIC")))
    if best_asr != ("LOCAL_MIDNIGHT_SHADOW", "GEOMETRIC"):
        say(f"  {label}stage 3 prefers {best_asr}: refitting the points")
        opts = {"asr_mode": best_asr[0], "asr_sun": best_asr[1]}
        fits = {lid: fit_location(lid, [(d, days[d][lid]) for d in dates], TABLES[model], opts) for lid in TOWNS}
    ang = {e: angle_windows(days, dates, fits, model, e, deg) for e, deg in (("FAJR", FAJR_DEG), ("ISHA", ISHA_DEG))}
    dh = offset_windows(days, dates, fits, model, "DHUHR")["DHUHR"]
    mag = offset_windows(days, dates, fits, model, "MAGHRIB")
    rep["angles"] = {e: (cover(ws), choose(ws, FAJR_DEG if e == "FAJR" else ISHA_DEG)) for e, ws in ang.items()}
    rep["dhuhr_offset"] = (cover(dh), choose(dh, DHUHR_MIN, whole=True))
    rep["maghrib"] = {k: (cover(v), choose(v, k, whole=True)) for k, v in mag.items()}
    values = {"model": model, "asr_mode": opts["asr_mode"], "asr_sun": opts["asr_sun"],
              "fajr": rep["angles"]["FAJR"][1][0], "isha": rep["angles"]["ISHA"][1][0],
              "dhuhr": int(rep["dhuhr_offset"][1][0]), "maghrib": {k: int(v[1][0]) for k, v in rep["maghrib"].items()}}
    return values, fits, rep


# ------------------------------------------------------------------------------------------------------------
# The profile, the zones and the scores (reference engine)

def method(values: dict) -> dict:
    return common.method_json(
        id=AUTHORITY, fajr={"type": "angle", "degrees": values["fajr"]}, isha={"type": "angle", "degrees": values["isha"]},
        offsets={"DHUHR": int(values["dhuhr"])}, sunModel=values["model"], asrSun=values["asr_sun"],
        horizon={"sunrise": {"type": "pressure"}, "sunset": {"type": "pressure"}},
        sunEvaluation={"ASR": values["asr_mode"]} if values["asr_mode"] != "EVENT_INSTANT" else {},
        maghribTerrain={"lowMaxM": LOW_MAX, "lowMinutes": int(values["maghrib"].get(6, 6)), "highMinM": HIGH_MIN,
                        "highMinutes": int(values["maghrib"].get(10, 10))},
        evidence={"kind": "FROM_TABLES", "source": "fit"})


def terrain_class(f: dict) -> str | None:
    if LOW_MAX < f["h"] < HIGH_MIN:
        return "LOW" if f["k"] == 6 else "HIGH"
    return None


def zone_of(lid: str, f: dict) -> dict:
    g, lat, lng, dem = TOWNS[lid]
    z = common.zone_json(lid, lat, lng, elevationM=f["h"], terrainClass=terrain_class(f))
    if not f["at_town"]:
        z["morningPoint"] = z["eveningPoint"] = common.point(f["lat"], f["lng"])
    return z


def minutes(m: dict, zone: dict | None, lat: float, lng: float, day: datetime.date, elevation: int | None = None) -> dict:
    got = common.compute(common.case(m, lat, lng, TZ, day, zone=zone, elevation_m=elevation, country="YE"))
    return {e: (got[e][1] // 60 + 180) % 1440 for e in EV}


def score_zone(m: dict, lid: str, f: dict, pub: list) -> common.Score:
    z = zone_of(lid, f)
    g, lat, lng, dem = TOWNS[lid]
    s = common.Score()
    for d, p in pub:
        got = minutes(m, z, lat, lng, d)
        for e in EV:
            s.add(e, d, got[e], p[e])
    return s


def merge(scores) -> common.Score:
    out = common.Score()
    for s in scores:
        for e in EV:
            out.exact[e] += s.exact[e]
            out.within1[e] += s.within1[e]
            out.total[e] += s.total[e]
            for k in range(12):
                out.months[e][k] += s.months[e][k]
        out.worst = max(out.worst, s.worst)
        out.misses += s.misses
    return out


def shares(s: common.Score) -> str:
    return (" ".join(f"{e[:3]} {100 * s.exact[e] / s.total[e]:.1f}" for e in EV)
            + f" % of {s.total['FAJR']}; within 1: {100 * min(s.within1_shares().values()):.1f} %, worst {s.worst}")


def stretch(st) -> str:
    return " ".join(f"[{a:.4f}, {b:.4f})" for a, b in st[:2])


# ------------------------------------------------------------------------------------------------------------
# The validity guard (6.5), a side check

def km(lat1, lng1, lat2, lng2) -> float:
    r = math.pi / 180
    a = math.sin((lat2 - lat1) * r / 2) ** 2 + math.cos(lat1 * r) * math.cos(lat2 * r) * math.sin((lng2 - lng1) * r / 2) ** 2
    return 2 * 6371.0 * math.asin(math.sqrt(a))


def place_class(h: float, near_k: int | None) -> int:
    if h <= LOW_MAX:
        return 6
    if h >= HIGH_MIN:
        return 10
    return near_k if near_k is not None else (6 if h - LOW_MAX <= HIGH_MIN - h else 10)


def attach(lat: float, lng: float, dem: float, fits: dict) -> str:
    """E10's NEAREST: the nearest location of the place's own class within 60 km, else the nearest location."""
    by_dist = sorted(TOWNS, key=lambda lid: km(lat, lng, TOWNS[lid][1], TOWNS[lid][2]))
    own = place_class(dem, fits[by_dist[0]]["k"])
    for lid in by_dist:
        if km(lat, lng, TOWNS[lid][1], TOWNS[lid][2]) > 60:
            break
        if fits[lid]["k"] == own:
            return lid
    return by_dist[0]


def guard_check(m: dict, fits: dict, year: int = 2026, say=print):
    days = [datetime.date(year, 1, 1) + datetime.timedelta(days=i) for i in range(365)]

    def run(lat, lng, zone=None, elevation=None):
        return [common.compute(common.case(m, lat, lng, TZ, d, zone=zone, elevation_m=elevation, country="YE")) for d in days]

    def leads(r_days, t_days):
        out = {e: [] for e in EV}
        for r, t in zip(r_days, t_days):
            for e in EV:
                lead = (r[e][0] - t[e][1]) / 60
                out[e].append(-lead if e == "SUNRISE" else lead)
        return out

    cache = {}
    for name, lat, lng, dem, expect in GUARD_PLACES:
        lid = attach(lat, lng, dem, fits)
        if lid not in cache:
            g, tlat, tlng, tdem = TOWNS[lid]
            z = zone_of(lid, fits[lid])
            t_days = run(tlat, tlng, z)
            # The reference at the table's own place (6.5, item 3): the method at the zone's point with its elevation
            # and its own class, as a zone at the town with no E6 point and no offsets.
            own = common.zone_json(lid, tlat, tlng, elevationM=fits[lid]["h"], terrainClass=terrain_class(fits[lid]))
            r0 = run(tlat, tlng, own)
            cache[lid] = (t_days, {e: max(v) for e, v in leads(r0, t_days).items()})
        t_days, margin = cache[lid]
        excess = {e: max(v) - margin[e] for e, v in leads(run(lat, lng, None, dem), t_days).items()}
        worst = max(excess, key=excess.get)
        verdict = "keeps the table" if excess[worst] <= 4 else "own point"
        plan = {True: "keeps", False: "own point", None: "near the threshold"}[expect]
        say(f"  {name:20} -> {lid:>4} {km(lat, lng, TOWNS[lid][1], TOWNS[lid][2]):4.0f} km: worst {worst} {excess[worst]:+.1f} min "
            f"beyond the margin: {verdict} (plan: {plan})")


# ------------------------------------------------------------------------------------------------------------

def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--no-guard", action="store_true", help="skip the validity-guard side check")
    args = ap.parse_args(argv)
    sys.stdout.reconfigure(encoding="utf-8")
    start = time.perf_counter()

    days, names, seasons = load()
    dates = sorted(days)
    print(f"{AUTHORITY}: {len(dates)} dates ({dates[0]} to {dates[-1]}), {len(TOWNS)} locations; season field "
          + ", ".join(f"{s}: {sum(v == s for v in seasons.values())}" for s in sorted(set(seasons.values()))))
    for model in MODELS:
        TABLES[model] = {d: SunTable(model, d) for d in dates}

    # Held out by month: each half refitted from scratch and scored on the other.
    folds = (("A", [d for d in dates if d.month % 2 == 1], [d for d in dates if d.month % 2 == 0]),
             ("B", [d for d in dates if d.month % 2 == 0], [d for d in dates if d.month % 2 == 1]))
    held = {lid: common.Score() for lid in TOWNS}
    for name, fit_dates, score_dates in folds:
        values, fits, rep = fit_fold(days, fit_dates, label=f"fold {name}, ")
        m = method(values)
        part = {lid: score_zone(m, lid, fits[lid], [(d, days[d][lid]) for d in score_dates]) for lid in TOWNS}
        for lid in TOWNS:
            held[lid] = merge([held[lid], part[lid]])
        print(f"  fold {name}: fitted on {len(fit_dates)} dates ({values['model']}, Asr {values['asr_mode']} {values['asr_sun']}, "
              f"Fajr {values['fajr']}, Isha {values['isha']}, Dhuhr +{values['dhuhr']}, Maghrib {values['maghrib']}); "
              f"held out {len(score_dates)}: {shares(merge(part.values()))}  [{time.perf_counter() - start:.0f} s]")

    # The final fit on every date.
    values, fits, rep = fit_fold(days, dates, label="all dates, ")
    m = method(values)
    print("All dates:")
    print("  1 Dhuhr windows, one longitude per location: " + ", ".join(f"{k} {v[0]}" for k, v in rep["dhuhr"].items())
          + f" of {len(dates) * len(TOWNS)} -> {values['model']}; its evaluations: "
          + ", ".join(f"{k} {v}" for k, v in rep["dhuhr_modes"].items()))
    print("  3 Dhuhr and Asr windows together: " + ", ".join(f"{a}/{b[:3]} {v}" for (a, b), v in rep["asr"].items())
          + f" of {2 * len(dates) * len(TOWNS)} -> {values['asr_mode']} {values['asr_sun']}")
    n_all = len(dates) * len(TOWNS)
    for e in ("FAJR", "ISHA"):
        (c, st), (v, at_v, _b) = rep["angles"][e]
        print(f"  4 {e.title()} angle: best {c}/{n_all} at {stretch(st)}; {v} covers {at_v} -> {v}")
    (c, st), (v, at_v, _b) = rep["dhuhr_offset"]
    print(f"    Dhuhr after transit: best {c}/{n_all} at {stretch(st)} min; +{int(v)} covers {at_v} -> +{values['dhuhr']}")
    for k, ((c, st), (v, at_v, _b)) in sorted(rep["maghrib"].items()):
        n = sum(1 for lid in TOWNS if fits[lid]["k"] == k) * len(dates)
        print(f"    Maghrib after the pressure sunset, class +{k}: best {c}/{n} at {stretch(st)} min; +{int(v)} covers {at_v}"
              f" -> +{values['maghrib'][k]}")
    lows = sorted(fits[lid]["h"] for lid in TOWNS if fits[lid]["k"] == 6)
    highs = sorted(fits[lid]["h"] for lid in TOWNS if fits[lid]["k"] == 10)
    print(f"  E10: + 6 at {len(lows)} locations, fitted elevations {lows[0]}..{lows[-1]} m; + 10 at {len(highs)}, {highs[0]}..{highs[-1]} m")

    # Zones and FitStats.
    first, last = dates[0], dates[-1]
    zones = []
    for lid in sorted(TOWNS, key=int):
        g, lat, lng, dem = TOWNS[lid]
        f = fits[lid]
        z = zone_of(lid, f)
        fitted_on = [common.sample(SOURCE + f": {names[lid]}", first, last, len(dates), 1)]
        held_out = [common.sample(SOURCE + f": {names[lid]}, months split (May, July, September / June, August, October)",
                                  first, last, len(dates), 1, month_split=True)]
        zones.append({
            "id": lid, "nameAr": names[lid], "point": common.point(lat, lng),
            "morningPoint": z.get("morningPoint"), "eveningPoint": z.get("eveningPoint"),
            "elevationM": f["h"], "terrainClass": terrain_class(f),
            "namedPlaces": [{"centre": common.point(lat, lng), "catalogueIds": [g]}],
            "fit": common.fit_stats(fitted_on, held_out, held[lid]),
        })
    all_held = merge(held.values())
    profile_fit = common.fit_stats(
        [common.sample(SOURCE, first, last, len(dates), len(TOWNS))],
        [common.sample(SOURCE + ", months split (May, July, September / June, August, October)", first, last, len(dates),
                       len(TOWNS), month_split=True)], all_held)
    path = common.write_asset(AUTHORITY, AUTHORITY, "YE", "NEAREST", zones, fit=profile_fit, source=SOURCE_LINE,
                              terrain_class_km=60.0)

    print("Held out (FitStats, month split): " + shares(all_held))
    insample = merge(score_zone(m, lid, fits[lid], [(d, days[d][lid]) for d in dates]) for lid in TOWNS)
    print("Final asset on every date (in-sample): " + shares(insample))
    print("Zones: point (distance from the town), elevation (GeoNames dem), class; held-out exact %:")
    for z in zones:
        lid = z["id"]
        f = fits[lid]
        g, lat, lng, dem = TOWNS[lid]
        moved = "town" if f["at_town"] else f"{km(lat, lng, f['lat'], f['lng']):.1f} km"
        h = held[lid]
        print(f"  {lid:>4} {names[lid]:22} {moved:>7}  {f['h']:5d} m ({dem:4d})  +{f['k']:<2} {z['terrainClass'] or '':4}  "
              + " ".join(f"{e[:3]} {100 * h.exact[e] / h.total[e]:3.0f}" for e in EV)
              + f"  in-sample {f['count']}/{f['of']}")
    big = [(lid, e, d, diff) for lid in TOWNS for e, d, diff in held[lid].misses]
    print(f"  misses beyond 1 min held out: {len(big)}" + (f" ({big[:5]})" if big else ""))
    reach = [z["id"] for z in zones if all(z["fit"]["exact"][e] >= 0.97 and z["fit"]["within1"][e] >= 1.0 for e in EV)]
    covered = [z["id"] for z in zones if all(min(z["fit"]["daysPerMonth"][e]) >= 7 for e in EV)]
    print(f"Tier A (9.5): {len(reach)} of {len(zones)} zones reach the shares (97 % exact on each time, all within 1 minute): "
          f"{', '.join(reach)}; {len(covered)} have 7 held-out days in every month, so {len(set(reach) & set(covered))} pass")
    if not args.no_guard:
        print("Validity guard, 2026 (side check, E10's attachment):")
        guard_check(m, fits)
    print(f"Proposed profile: Fajr {values['fajr']}, Isha {values['isha']}, Dhuhr +{values['dhuhr']}, Maghrib "
          f"+{values['maghrib'].get(6)} / +{values['maghrib'].get(10)} (E10 {LOW_MAX} / {HIGH_MIN} m), Asr {values['asr_mode']} "
          f"{values['asr_sun']}, Pressure horizons, {values['model']}")
    print(f"Wrote {path.relative_to(common.ROOT)}  [{time.perf_counter() - start:.0f} s]")
    return 0


if __name__ == "__main__":
    sys.exit(main())
