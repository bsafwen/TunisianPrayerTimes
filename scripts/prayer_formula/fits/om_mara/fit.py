#!/usr/bin/env python3
"""
Fits `om_mara`, Oman's Ministry of Endowments and Religious Affairs (plan 8.1, 8.2, 8.7, 8.8; milestone AM2, 9.4),
and writes its official-zone asset, android-app/app/src/main/assets/official-zones/om_mara.json (schema 1).

Data, read at run time and never copied (D13, A7):
- test-data-local/research-2026-10-08/OM/omv/om_check.csv: the ministry's monthly tables (calendar_page2.asp) for
  every day of 2026 at six zones: CityID 0 Muscat, 49 Saiq, 52 Sohar, 56 Salalah, 73 Musandam, 75 Masirah.
- test-data-local/research-2026-10-08/OM/om/yrs/y2021_m*.html and y2031_m*.html: Muscat's calendar_page2 for March,
  June, September and December of 2021 and of 2031 (244 days), held out.
- test-data-local/research-2026-10-08/OM/om/cal_p4.html and en_p4.html: calendar_page4.asp, all 86 zones on
  2026-10-08, in Arabic and in English (the English names of the README).
- calendar_page4 pages, one HTML file per date named p4_YYYY_MM_DD.html (all 86 zones; the date is read from the
  page's own selectors): 25 dates of 2026, two a month (fitting), and 6 dates of 2027, 2029 and 2031 (held out),
  collected on 2026-10-11. Read from test-data-local/research-2026-10-11/OM/page4/ (any depth) or --pages DIR.
  Without them only 2026-10-08 is at hand for the 80 other zones, which fixes no latitude: fit.py stops and does
  not write the asset.
- test-data-local/research-2026-10-17/OM/page2/p2_2026_MM_Z.html: calendar_page2 for every month of 2026 at the 80
  other zones (owner's OK, 2026-10-17; MANIFEST.tsv one level up), each page checked against its own selectors and
  against page4's rows. A zone with all twelve months is fitted and held out on its year like the six (its point
  only: the profile stays the six zones'); without them it keeps the 25 page4 dates.
- Town centres (TOWNS below): GeoNames cities1000 (geonames.org, read 2026-10-15) where the town is listed there,
  otherwise OpenStreetMap places (Overpass, read 2026-10-11). They are not official data.

Method (9.4). Each published minute bounds the unrounded time to a minute (E1, nearest), so a parameter has a
window per minute, and the value the most windows hold wins. With E4 `DAILY_0H_UT` the sun is fixed for the day,
so every time has a closed form (the search loops below); every score printed as held out, and every FitStats,
comes from common.compute (the reference engine, which the Kotlin engine matches to the millisecond).
1. Dhuhr: the sun model (E14) and evaluation (E4) by the Dhuhr windows one longitude per zone covers (the six
   full-year zones), so a constant shift or an offset, which a longitude absorbs, does not decide it.
2. All six times at the six full-year zones: zone points at whole arc-minutes (the ministry's points are truncated
   to whole arc-minutes, 8.8), then a constant shift of every time by windows (an E18 constant, as for KACST's
   second), then each plan value checked by its own scan with the points refitted: the offsets (Dhuhr, Asr and
   Maghrib + 5, the rest 0), the rounding, the horizon, the Fajr and Isha angles, the Asr rule.
3. The 86 zone points at whole arc-minutes: the six from all of 2026, the 80 others from the 25 page4 dates.
Held out (FitStats, per zone): a month-split of 2026 (the shift and the point refitted on the odd months and scored
on the even ones, and the reverse), the 6 page4 dates of 2027-2031 (points fitted on 2026), and for Muscat its
2021 and 2031 monthly pages. Only the six full-year zones have 7 or more held-out days in every month (9.5).

The suns tried are the engine's (E14): MEEUS, VSOP87, TEXTBOOK and ALMANAC, the Astronomical Almanac's low-precision
sun with PrayTimes.js's constants, which reproduces the tables (99.9 % with + 0.86 s). With a reference that lacks
ALMANAC, fit.py computes it itself, reports it beside the others and ships the best sun the reference offers.

Usage: python scripts/prayer_formula/fits/om_mara/fit.py [--pages DIR]
"""

from __future__ import annotations

import argparse
import calendar
import csv
import datetime
import html
import math
import re
import statistics
import sys
import time
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import common  # noqa: E402

ref = common.ref
EV = common.EVENTS
AUTHORITY = "om_mara"
TZ = "Asia/Muscat"                      # UTC+4 all year
OM = common.LOCAL_DATA / "research-2026-10-08" / "OM"
PAGES = common.LOCAL_DATA / "research-2026-10-11" / "OM" / "page4"
FULL_YEAR_CSV = OM / "omv" / "om_check.csv"
ZONE_PAGES = common.LOCAL_DATA / "research-2026-10-17" / "OM" / "page2"
SOURCE_LINE = ("Oman's Ministry of Endowments and Religious Affairs (mara.gov.om): 86 zone tables, each computed at "
               "a zone point fitted at whole arc-minutes from the ministry's 2026 tables")
# FitSample sources (6.3): short ids, explained in README-part.md.
FULL_SOURCE = "om_mara_page2_2026"              # calendar_page2, every day of 2026 (six zones)
PAGE_SOURCE = "om_mara_page4_2026"              # calendar_page4, 25 dates of 2026
HELD_PAGE_SOURCE = "om_mara_page4_2027_2031"    # calendar_page4, 6 dates of 2027, 2029 and 2031
YEARS_SOURCE = "om_mara_page2_muscat_2021_2031"  # calendar_page2, Muscat, 4 months of 2021 and of 2031
ZONE_YEAR_SOURCE = "om_mara_page2_2026_zones"   # calendar_page2, every day of 2026 (the 80 other zones)
BASE_OFFSETS = (0, 0, 5, 5, 5, 0)       # Fajr, sunrise, Dhuhr, Asr, Maghrib, Isha (plan 8.2)
AREA_KM = 10.0                          # a named town's own area (6.5, 8.8)
MODES = ("DAILY_0H_UT", "DAILY_10H_UT", "KACST", "EVENT_INSTANT")
D2R = math.pi / 180

# Zone id (the ministry's CityID: its row order) -> the town the zone is named after: (label, source, lat, lng).
# None: an oil or gas field, a desert station, an island group, or a name no source places (8.8). Three tables are
# computed far from their town (8.8: «الحمرة» 74 km, «رخيوت» 55 km, «قرن العلم» 66 km); the town still names them.
GN, OSM = "GeoNames cities1000", "OpenStreetMap"
TOWNS = {
    0: ("Muscat (GeoNames 287286)", GN, 23.58413, 58.40778), 1: ("Ibra (287832)", GN, 22.69057, 58.53337),
    2: ("Adam (289433)", GN, 22.37934, 57.52718), 3: ("Izki (287814)", GN, 22.93333, 57.76667),
    4: ("Al Ashkhara", OSM, 21.85, 59.57), 5: ("Al Buraimi (289317)", GN, 24.25088, 55.79312), 6: None,
    7: ("Jamma", OSM, 23.55910, 57.56031), 8: ("Al Hashman", OSM, 18.45970, 53.09823), 9: None,
    10: ("Al Hamra", OSM, 23.11833, 57.29374), 11: ("Al Khaburah (289199)", GN, 23.97144, 57.09313), 12: None,
    13: ("Lekhwair", OSM, 22.83565, 55.34598), 14: ("Duqm", OSM, 19.62304, 57.63400),
    15: ("Rustaq (289011)", GN, 23.39083, 57.42444), 16: ("Al Awabi (412084)", GN, 23.30324, 57.52459),
    17: ("Al Qabil (289142)", GN, 22.571, 58.69472), 18: None, 19: ("Al Kamil", OSM, 22.21816, 59.20058),
    20: ("Al Mudaybi", OSM, 22.56965, 58.11502), 21: ("Al Mamurah", OSM, 17.05981, 54.22969), 22: None,
    23: ("Al Wasit", OSM, 24.2, 56.27), 24: ("Al Wasil", OSM, 22.50443, 58.74913), 25: ("Bukha", OSM, 26.14460, 56.14623),
    26: ("Bidbid (288721)", GN, 23.40787, 58.1283), 27: ("Badiyah (288902)", GN, 22.45, 58.8),
    28: ("Barka (288789)", GN, 23.67872, 57.88605), 29: ("Bahla (288899)", GN, 22.97886, 57.3047),
    30: ("Thumrait (286140)", GN, 17.67, 54.03333), 31: None, 32: None, 33: None,
    34: ("Habrut", OSM, 17.28590, 52.73868), 35: ("Hijj", OSM, 20.76225, 58.29101), 36: None,
    37: ("Dibba (11764763)", GN, 25.65056, 56.26516), 38: ("Ras al Hadd", OSM, 22.53602, 59.79572),
    39: ("Ras Madrakah", OSM, 18.98089, 57.78060), 40: ("Rakhyut", OSM, 16.74849, 53.41952), 41: None,
    42: ("Raysut", OSM, 16.94973, 53.99797), 43: ("Sadah", OSM, 17.05191, 55.07113),
    44: ("Samail (286590)", GN, 23.29838, 57.9531), 45: ("Samad ash Shan", OSM, 22.82095, 58.16024),
    46: ("Sinaw", OSM, 22.50444, 58.03324), 47: ("As Suwayq (288955)", GN, 23.84944, 57.43861), 48: None,
    49: ("Saiq", OSM, 23.06832, 57.64252), 50: ("Shalim", OSM, 18.10731, 55.65013),
    51: ("Shinas (286402)", GN, 24.7426, 56.46698), 52: ("Sohar (286282)", GN, 24.34745, 56.70937),
    53: ("Saham (286647)", GN, 24.17222, 56.88861), 54: None, 55: ("Sarfait", OSM, 16.68257, 53.11576),
    56: ("Salalah (286621)", GN, 17.01505, 54.09237), 57: ("Sur (286245)", GN, 22.56667, 59.52889),
    58: ("Dank", OSM, 23.55219, 56.24398), 59: ("Taqah", OSM, 17.04401, 54.39136), 60: None,
    61: ("Ibri (287830)", GN, 23.22573, 56.51572), 62: None, 63: ("Qarn Alam", OSM, 21.32508, 57.09252),
    64: ("Quriyat (286839)", GN, 23.26298, 58.92014), 65: None, 66: ("Liwa (289174)", GN, 24.53077, 56.563),
    67: ("Mahdah", OSM, 24.40042, 55.96785), 68: None, 69: ("Madha (13440142)", GN, 25.28357, 56.33196),
    70: ("Mirbat", OSM, 16.98207, 54.69326), 71: None, 72: ("Marmul", OSM, 18.13092, 55.26997),
    73: ("Khasab (287614), Musandam's seat", GN, 26.17993, 56.24774), 74: ("Al Musanaah", OSM, 23.73957, 57.64539),
    75: ("Masirah (11814212)", GN, 20.65063, 58.87277), 76: ("Muqshin", OSM, 19.56478, 54.89107),
    77: ("Manah", OSM, 22.78765, 57.61113), 78: ("Nakhal", OSM, 23.39547, 57.82894),
    79: ("Nizwa (286987)", GN, 22.93333, 57.53333), 80: ("Nimr", OSM, 18.54013, 55.88115), 81: None,
    82: ("Haima (287939)", GN, 19.95931, 56.27575), 83: ("Wadi Bani Khalid", OSM, 22.46983, 59.11062),
    84: ("Wadi Hibi", OSM, 24.01, 56.54), 85: ("Yanqul (411849)", GN, 23.58645, 56.53969),
}


# ------------------------------------------------------------------------------------------------ data

def clock(text: str, k: int) -> int:
    """A 12-hour hh:mm without AM/PM (the ministry's tables) as minutes: Asr, Maghrib and Isha are PM, and Dhuhr when
    its hour is below 11."""
    h, m = (int(x) for x in text.strip().split(":"))
    if k >= 3 or (k == 2 and h < 11):
        h += 12
    return 60 * h + m


def cells(tr: str) -> list[str]:
    return [html.unescape(re.sub(r"<[^>]+>", "", c)).strip() for c in re.findall(r"<t[dh].*?</t[dh]>", tr, re.S)]


def full_year() -> dict[int, dict[datetime.date, list[int]]]:
    out: dict[int, dict[datetime.date, list[int]]] = {}
    with open(FULL_YEAR_CSV, encoding="utf-8", newline="") as fh:
        for r in csv.DictReader(fh):
            day = datetime.date(int(r["y"]), int(r["m"]), int(r["d"]))
            out.setdefault(int(r["zone"]), {})[day] = [clock(r[k], i) for i, k in enumerate(
                ("fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha"))]
    return out


def muscat_years() -> dict[datetime.date, list[int]]:
    out = {}
    for f in sorted((OM / "om" / "yrs").glob("y*_m*.html")):
        for tr in re.findall(r"<tr.*?</tr>", f.read_text(encoding="utf-8", errors="replace"), re.S):
            c = cells(tr)
            if len(c) == 7 and re.fullmatch(r"\d{1,2}/\d{1,2}/\d{4}", c[0]):
                d, m, y = (int(x) for x in c[0].split("/"))
                out[datetime.date(y, m, d)] = [clock(x, i) for i, x in enumerate(c[1:])]
    return out


def zone_years(folder: Path) -> dict[int, dict[datetime.date, list[int]]]:
    """The calendar_page2 pages of the other zones (p2_2026_MM_Z.html): zone -> {date: six minutes}, a zone only with
    all twelve months; each page's selectors (year, month, CityID) must name the page's file."""
    out: dict[int, dict[datetime.date, list[int]]] = {}
    months: dict[int, set[int]] = {}
    for f in sorted(folder.glob("p2_2026_*_*.html")) if folder.is_dir() else []:
        _, year, month, zone = f.stem.split("_")
        text = f.read_text(encoding="utf-8", errors="replace")
        selected = [int(x) for x in re.findall(r"<option value =(\d+) Selected>", text, re.I)[:3]]
        if selected != [int(year), int(month), int(zone)]:
            raise SystemExit(f"{f.name}: the page selects {selected}")
        rows = {}
        for tr in re.findall(r"<tr.*?</tr>", text, re.S):
            c = cells(tr)
            if len(c) == 7 and re.fullmatch(r"\d{1,2}/\d{1,2}/\d{4}", c[0]):
                d, m, y = (int(x) for x in c[0].split("/"))
                rows[datetime.date(y, m, d)] = [clock(x, i) for i, x in enumerate(c[1:])]
        expected = calendar.monthrange(int(year), int(month))[1]
        if len(rows) != expected or any((d.year, d.month) != (int(year), int(month)) for d in rows):
            raise SystemExit(f"{f.name}: {len(rows)} rows, not the month's {expected}")
        out.setdefault(int(zone), {}).update(rows)
        months.setdefault(int(zone), set()).add(int(month))
    return {z: days for z, days in out.items() if len(months[z]) == 12}


def page4(path: Path) -> tuple[datetime.date, list[tuple[str, list[int]]]]:
    """One calendar_page4 page: its date (the selected year, month and day) and its rows (zone name, six minutes)."""
    text = path.read_text(encoding="utf-8", errors="replace")
    y, m, d = (int(x) for x in re.findall(r"<option value =(\d+) Selected>", text, re.I)[:3])
    rows = []
    for tr in re.findall(r"<tr.*?</tr>", text, re.S):
        c = cells(tr)
        if len(c) == 7 and re.fullmatch(r"\d{1,2}:\d{2}", c[1]):
            rows.append((c[0], [clock(x, i) for i, x in enumerate(c[1:])]))
    return datetime.date(y, m, d), rows


def all_zone_pages(folder: Path) -> tuple[list[str], dict[datetime.date, dict[int, list[int]]]]:
    files = sorted(folder.rglob("p4_*.html")) if folder.is_dir() else []
    files.append(OM / "om" / "cal_p4.html")
    names, out = None, {}
    for f in files:
        day, rows = page4(f)
        if len(rows) != 86:
            raise SystemExit(f"{f}: {len(rows)} rows, not 86")
        n = [r[0] for r in rows]
        if names is None:
            names = n
        if n != names:
            raise SystemExit(f"{f}: the zone list differs")
        new = {i: r[1] for i, r in enumerate(rows)}
        if day in out and out[day] != new:
            raise SystemExit(f"{f}: two different pages for {day}")
        out[day] = new
    return names, out


def english_names(arabic_day: dict[int, list[int]]) -> dict[int, str]:
    """English zone names: the English page4 lists the zones in another order, so each row is matched by its six
    times on 2026-10-08, in order among identical rows."""
    _, rows = page4(OM / "om" / "en_p4.html")
    left = list(rows)
    out = {}
    for z in range(86):
        i = next(k for k, r in enumerate(left) if r[1] == arabic_day[z])
        out[z] = left.pop(i)[0]
    return out


# ------------------------------------------------------------------------------------------------ suns

def almanac_sun(jd: float) -> tuple[float, float, float]:
    """The Astronomical Almanac's low-precision sun, with PrayTimes.js's constants: (declination, equation of time
    in minutes, distance)."""
    d = jd - 2451545.0
    g = (357.529 + 0.98560028 * d) % 360
    q = (280.459 + 0.98564736 * d) % 360
    lam = q + 1.915 * math.sin(g * D2R) + 0.020 * math.sin(2 * g * D2R)
    e = 23.439 - 0.00000036 * d
    ra = math.atan2(math.cos(e * D2R) * math.sin(lam * D2R), math.cos(lam * D2R)) / D2R
    eot = ((q - ra + 180) % 360 - 180) * 4
    return math.asin(math.sin(e * D2R) * math.sin(lam * D2R)) / D2R, eot, 1.00014 - 0.01671 * math.cos(g * D2R)


SUNS = dict(ref.SUN_MODELS)
SUNS.setdefault("ALMANAC", almanac_sun)
ENGINE = [m for m in ("MEEUS", "VSOP87", "TEXTBOOK", "ALMANAC") if m in ref.SUN_MODELS]
_sun: dict = {}


def daily(model: str, mode: str, day: datetime.date) -> tuple[float, float]:
    """(declination, equation of time) of a daily evaluation mode (S4); Oman's cycle date is the civil date."""
    key = (model, mode, day)
    if key not in _sun:
        fn, jd0 = SUNS[model], ref.julian_day0(day)
        if mode == "DAILY_0H_UT":
            v = fn(jd0)[:2]
        elif mode == "DAILY_10H_UT":
            v = fn(jd0 + 10 / 24)[:2]
        elif mode == "KACST":
            v = (fn(jd0 + 21 / 24)[0], fn(jd0 + 9 / 24)[1])
        else:
            raise ValueError(mode)
        _sun[key] = v
    return _sun[key]


def instant_eot(model: str, day: datetime.date, lng: float) -> float:
    """EVENT_INSTANT's equation of time at the transit at lng (S3)."""
    key = (model, "TRANSIT", day, round(lng, 2))
    if key not in _sun:
        fn, jd0 = SUNS[model], ref.julian_day0(day)
        t = 12 - lng / 15
        for _ in range(10):
            t_next = 12 - lng / 15 - fn(jd0 + t / 24)[1] / 60
            if abs(t_next - t) < 1e-6:
                break
            t = t_next
        _sun[key] = fn(jd0 + t / 24)[1]
    return _sun[key]


# ------------------------------------------------------------------------------------------------ fast times

def profile(**kw) -> dict:
    p = {"model": "MEEUS", "mode": "DAILY_0H_UT", "offsets": BASE_OFFSETS, "shift": 0.0, "horizon": -0.8333,
         "fajr": 18.0, "isha": 18.0, "asr": "shadow", "rounding": "NEAREST"}
    p.update(kw)
    return p


def unrounded(day: datetime.date, lat: float, lng: float, p: dict) -> list[float]:
    """The six unrounded local minutes, offsets and shift included (S3-S9 for a daily sun)."""
    decl, eot = daily(p["model"], p["mode"], day)
    sl, cl, sd, cd = math.sin(lat * D2R), math.cos(lat * D2R), math.sin(decl * D2R), math.cos(decl * D2R)
    noon = (16 - lng / 15 - eot / 60) * 60 + p["shift"] / 60

    def half(alt: float) -> float:
        return math.acos((math.sin(alt * D2R) - sl * sd) / (cl * cd)) / D2R * 4

    zenith = abs(lat - decl)
    k = 2 if p["asr"] == "shadow2" else 1
    alt = math.atan(1 / (k + math.tan(zenith * D2R))) / D2R
    if p["asr"] == "apparent":
        h0 = 90 - zenith
        a_app = math.atan(1 / (k + math.tan((90 - (h0 + ref.bennett(h0, 1010.0, 10.0))) * D2R))) / D2R
        alt = a_app - ref.bennett(a_app, 1010.0, 10.0)
    o = p["offsets"]
    h = half(p["horizon"])
    return [noon - half(-p["fajr"]) + o[0], noon - h + o[1], noon + o[2], noon + half(alt) + o[3], noon + h + o[4],
            noon + half(-p["isha"]) + o[5]]


def rounded(u: float, mode: str) -> int:
    if mode == "UP":
        return math.ceil(u - 1e-5)
    if mode == "DOWN":
        return math.floor(u + 1e-5)
    return math.floor(u + 0.5)


def count(days, lat: float, lng: float, p: dict, per: bool = False):
    n = [0] * 6
    mode = p["rounding"]
    for day, pub in days:
        u = unrounded(day, lat, lng, p)
        for i in range(6):
            n[i] += rounded(u[i], mode) == pub[i]
    return n if per else sum(n)


def best_point(days, seed: tuple[int, int], p: dict, span: int = 4) -> tuple[int, int, int]:
    """(count, lat, lng) at the whole arc-minute that reproduces the most minutes, searched around seed (arc-minutes)
    and recentred while the best lies on the edge; a tie goes to the point nearest the middle of the tied points."""
    la0, lo0 = seed
    for _ in range(8):
        res = [(count(days, (la0 + i) / 60, (lo0 + j) / 60, p), la0 + i, lo0 + j)
               for i in range(-span, span + 1) for j in range(-span, span + 1)]
        top = max(r[0] for r in res)
        tied = [r for r in res if r[0] == top]
        if all(abs(r[1] - la0) < span and abs(r[2] - lo0) < span for r in tied):
            break
        la0, lo0 = tied[0][1], tied[0][2]
    mla = sum(r[1] for r in tied) / len(tied)
    mlo = sum(r[2] for r in tied) / len(tied)
    return min(tied, key=lambda r: ((r[1] - mla) ** 2 + (r[2] - mlo) ** 2, r[1], r[2]))


def seed_point(days, p: dict) -> tuple[int, int]:
    """A start: the longitude from Dhuhr, then the latitude whose sunrise, sunset and twilight times fit best."""
    lngs = []
    for day, pub in days:
        _, eot = daily(p["model"], p["mode"], day)
        lngs.append(15 * (16 - eot / 60 - (pub[2] - p["offsets"][2] - p["shift"] / 60) / 60))
    lng = statistics.median(lngs)

    def misfit(lat: float) -> float:
        total = 0.0
        for day, pub in days:
            u = unrounded(day, lat, lng, p)
            total += sum((u[i] - pub[i]) ** 2 for i in (0, 1, 4, 5))
        return total

    lat = min((15.0 + 0.25 * i for i in range(53)), key=misfit)
    lat = min((lat + 0.02 * i for i in range(-13, 14)), key=misfit)
    return round(lat * 60), round(lng * 60)


def stab(intervals) -> tuple[int, tuple[float, float]]:
    """The most half-open intervals [lo, hi) one value lies in, and the stretch where it does."""
    marks = sorted([(lo, 1) for lo, _ in intervals] + [(hi, -1) for _, hi in intervals], key=lambda m: (m[0], m[1]))
    best, cur, span = 0, 0, (math.nan, math.nan)
    for i, (x, s) in enumerate(marks):
        cur += s
        if cur > best:
            best, span = cur, (x, marks[i + 1][0] if i + 1 < len(marks) else x)
    return best, span


def shift_windows(zone_days, points, p: dict, events=range(6)):
    """Windows of the constant shift (seconds) that keep each published minute, at the zones' points."""
    out = []
    for z, days in zone_days.items():
        la, lo = points[z]
        q = dict(p, shift=0.0)
        for day, pub in days:
            u = unrounded(day, la / 60, lo / 60, q)
            for i in events:
                out.append(((pub[i] - 0.5 - u[i]) * 60, (pub[i] + 0.5 - u[i]) * 60))
    return out


def fit_shift(zone_days, points, p: dict) -> tuple[float, int, tuple[float, float]]:
    n, (a, b) = stab(shift_windows(zone_days, points, p))
    return round((a + b) / 2, 2), n, (a, b)


def fit_points(zone_days, seeds, p: dict, span: int = 4) -> tuple[dict, int]:
    pts, total = {}, 0
    for z, days in zone_days.items():
        n, la, lo = best_point(days, seeds[z], p, span)
        pts[z] = (la, lo)
        total += n
    return pts, total


def converge(zone_days, seeds, p: dict, rounds: int = 3) -> tuple[dict, dict, int]:
    """Points and shift in turn until neither moves."""
    pts = dict(seeds)
    for _ in range(rounds):
        pts, total = fit_points(zone_days, pts, p)
        shift, _, _ = fit_shift(zone_days, pts, p)
        if shift == p["shift"]:
            break
        p = dict(p, shift=shift)
    pts, total = fit_points(zone_days, pts, p)
    return p, pts, total


# ------------------------------------------------------------------------------------------------ stages

def stage1(full: dict, seeds: dict) -> list[tuple[int, str, str, float]]:
    """Dhuhr windows one longitude per zone covers, per sun model and evaluation (offset and shift absorbed)."""
    out = []
    for model in SUNS:
        for mode in MODES:
            covered, slack = 0, 0.0
            for z, days in full.items():
                lng0 = seeds[z][1] / 60
                wins = []
                for day, pub in days.items():
                    eot = instant_eot(model, day, lng0) if mode == "EVENT_INSTANT" else daily(model, mode, day)[1]
                    k = (16 - eot / 60) * 60 + 5     # Dhuhr = k - 4 lng, nearest: pub - 0.5 <= k - 4 lng < pub + 0.5
                    wins.append(((k - pub[2] - 0.5) / 4, (k - pub[2] + 0.5) / 4))
                n, (a, b) = stab(wins)
                covered += n
                if n == len(wins):
                    slack += b - a
            out.append((covered, model, mode, slack))
    return sorted(out, key=lambda r: (-r[0], -r[3]))


def windows_of(full, pts, p, key, values) -> dict:
    return {v: sum(count(sorted(full[z].items()), pts[z][0] / 60, pts[z][1] / 60, dict(p, **{key: v})) for z in full)
            for v in values}


def angle_window(full, pts, p, which: str) -> tuple[int, tuple[float, float]]:
    """The twilight angles that keep each published Fajr or Isha minute at the points."""
    i = 0 if which == "fajr" else 5
    side = -1 if which == "fajr" else 1
    wins = []
    for z, days in full.items():
        lat, lng = pts[z][0] / 60, pts[z][1] / 60
        for day, pub in days.items():
            decl, eot = daily(p["model"], p["mode"], day)
            noon = (16 - lng / 15 - eot / 60) * 60 + p["shift"] / 60 + p["offsets"][i]
            lo_h, hi_h = sorted(((pub[i] - 0.5 - noon) * side / 4, (pub[i] + 0.5 - noon) * side / 4))
            sd, cd, sl, cl = math.sin(decl * D2R), math.cos(decl * D2R), math.sin(lat * D2R), math.cos(lat * D2R)

            def depression(h):
                return -math.asin(max(-1.0, min(1.0, math.cos(h * D2R) * cl * cd + sl * sd))) / D2R
            wins.append(tuple(sorted((depression(lo_h), depression(hi_h)))))
    return stab(wins)


def horizon_window(full, pts, p) -> tuple[int, tuple[float, float]]:
    """The horizon altitudes that keep each published sunrise and Maghrib minute at the points."""
    wins = []
    for z, days in full.items():
        lat, lng = pts[z][0] / 60, pts[z][1] / 60
        for day, pub in days.items():
            decl, eot = daily(p["model"], p["mode"], day)
            noon = (16 - lng / 15 - eot / 60) * 60 + p["shift"] / 60
            sd, cd, sl, cl = math.sin(decl * D2R), math.cos(decl * D2R), math.sin(lat * D2R), math.cos(lat * D2R)

            def altitude(h):
                return math.asin(max(-1.0, min(1.0, math.cos(h * D2R) * cl * cd + sl * sd))) / D2R
            for i, side in ((1, -1), (4, 1)):
                base = noon + p["offsets"][i]
                h1, h2 = (pub[i] - 0.5 - base) * side / 4, (pub[i] + 0.5 - base) * side / 4
                wins.append(tuple(sorted((altitude(h1), altitude(h2)))))
    return stab(wins)


# ------------------------------------------------------------------------------------------------ scores

def method_of(p: dict) -> dict:
    o = p["offsets"]
    return common.method_json(
        id=AUTHORITY, fajr={"type": "angle", "degrees": p["fajr"]}, isha={"type": "angle", "degrees": p["isha"]},
        offsets={e: o[i] for i, e in enumerate(EV) if o[i]}, sunModel=p["model"],
        sunEvaluation={e: p["mode"] for e in EV}, rounding={e: p["rounding"] for e in EV},
        seasonalOffsets={e: [p["shift"] / 60] * 36 for e in EV} if p["shift"] else {},
        asrShadow=2 if p["asr"] == "shadow2" else 1, asrSun="APPARENT" if p["asr"] == "apparent" else "GEOMETRIC",
        evidence={"kind": "FROM_TABLES", "source": "The Ministry of Awqaf's zone tables"})


def zone_of(z: int, computing: tuple[float, float], town) -> dict:
    """The zone as the asset holds it: the town as its point, the fitted computing point as E6 points (8.8)."""
    if town is None:
        return common.zone_json(str(z), computing[0], computing[1], authority=AUTHORITY)
    pt = common.point(computing[0], computing[1])
    return common.zone_json(str(z), town[2], town[3], authority=AUTHORITY, morningPoint=pt, eveningPoint=pt)


def app_minutes(method: dict, zone: dict, day: datetime.date) -> list[int]:
    """The reference engine's six local minutes at a zone."""
    lat, lng = zone["point"]["latE5"] / 1e5, zone["point"]["lngE5"] / 1e5
    out = common.compute(common.case(method, lat, lng, TZ, day, zone=zone, country="OM"))
    origin = ref.epoch_days(day) * 86400 - common.utc_offset(TZ, day)
    got = []
    for e in EV:
        if out[e][2] != "COMPUTED":
            raise ValueError(f"{zone['id']} {day} {e}: {out[e][2]}")
        got.append((out[e][1] - origin) // 60)
    return got


def score_days(method: dict, zone: dict, items, score: common.Score) -> None:
    for day, pub in items:
        for e, g, w in zip(EV, app_minutes(method, zone, day), pub):
            score.add(e, day, g, w)


def merged(scores) -> common.Score:
    out = common.Score()
    for s in scores:
        for e in EV:
            out.exact[e] += s.exact[e]
            out.within1[e] += s.within1[e]
            out.total[e] += s.total[e]
            out.months[e] = [a + b for a, b in zip(out.months[e], s.months[e])]
        out.worst = max(out.worst, s.worst)
        out.misses += s.misses
    return out


def shares(score: common.Score) -> str:
    n, ex = sum(score.total.values()), sum(score.exact.values())
    per = " ".join(f"{e[:3].lower()} {100 * score.exact[e] / score.total[e]:.1f}" for e in EV if score.total[e])
    return f"{ex}/{n} exact ({100 * ex / n:.2f} %; {per}), within 1: {100 * sum(score.within1.values()) / n:.2f} %, max {score.worst}"


def tier_a(stats: dict) -> list[str]:
    """FitStats.passesTierA (9.5), as the failures it finds (no E8 rule for om_mara)."""
    failures = []
    for e in EV:
        if stats["exact"][e] < 0.97:
            failures.append(f"{e} {stats['exact'][e]:.1%}")
        if stats["within1"][e] < 1.0:
            failures.append(f"{e} {stats['within1'][e]:.1%} within 1")
    low = min(min(v) for v in stats["daysPerMonth"].values())
    if low < 7:
        failures.append(f"{low} held-out days in some month")
    return failures


def km(a, b) -> float:
    return math.hypot((a[0] - b[0]) * 111.2, (a[1] - b[1]) * 111.2 * math.cos(math.radians((a[0] + b[0]) / 2)))


def dm(arcmin: int) -> str:
    return f"{arcmin // 60}°{arcmin % 60:02d}'"


# ------------------------------------------------------------------------------------------------ main

def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--pages", type=Path, default=PAGES, help="folder of calendar_page4 pages (p4_YYYY_MM_DD.html)")
    args = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    started = time.time()

    full = full_year()
    years = muscat_years()
    names, pages = all_zone_pages(args.pages)
    english = english_names(pages[datetime.date(2026, 10, 8)])
    fit_dates = sorted(d for d in pages if d.year == 2026)
    held_dates = sorted(d for d in pages if d.year != 2026)
    zone_year = {z: days for z, days in zone_years(ZONE_PAGES).items() if z not in full}
    for z, days in list(full.items()) + list(zone_year.items()):     # the page rows are the CityIDs: check them
        for d in pages:
            if d in days and days[d] != pages[d][z]:
                raise SystemExit(f"zone {z} on {d}: page4 row differs from calendar_page2")
    print(f"Data: {sum(len(v) for v in full.values())} zone-days of 2026 at {len(full)} zones (calendar_page2); "
          f"{len(years)} Muscat days of 2021 and 2031; calendar_page4 on {len(pages)} dates "
          f"({len(fit_dates)} of 2026, {len(held_dates)} later), 86 zones; calendar_page2's whole 2026 at "
          f"{len(zone_year)} more zones")
    if len(fit_dates) < 12:
        print(f"Only {len(fit_dates)} page4 date(s) of 2026 found (expected in {args.pages}); the 80 other zones' "
              "latitudes need dates across the year. The asset is not written.")
        return 1

    full_items = {z: sorted(days.items()) for z, days in full.items()}
    base = profile()
    seeds = {z: seed_point(full_items[z], base) for z in full}

    # Stage 1: the sun model and evaluation, by Dhuhr alone.
    s1 = stage1(full, seeds)
    print("Stage 1, Dhuhr windows one longitude per zone covers (of %d): " % sum(len(v) for v in full.values())
          + ", ".join(f"{m}/{e} {n}" for n, m, e, _ in s1[:8]))
    engine_rows = [r for r in s1 if r[1] in ENGINE]
    mode = engine_rows[0][2]
    tied = [r[1] for r in engine_rows if r[0] == engine_rows[0][0] and r[2] == mode]
    print(f"  best in the engine: {'/'.join(tied)} at {mode} (tied on Dhuhr; stage 2 decides); best overall: "
          f"{s1[0][1]}/{s1[0][2]}")

    # Stage 2: points, shift and each plan value, on the six full-year zones.
    candidates = {}
    for m in dict.fromkeys(tied + [r[1] for r in s1 if r[2] == mode]):
        p, pts, total = converge(full_items, seeds, profile(model=m, mode=mode))
        candidates[m] = (p, pts, total)
        _, n_shift, (a, b) = fit_shift(full_items, pts, p)
        own = {e: stab(shift_windows(full_items, pts, p, [i])) for i, e in enumerate(EV)}
        print(f"Stage 2, {m}/{mode}: {total}/{6 * sum(len(v) for v in full.values())} minutes at whole arc-minute "
              f"points with a shift of {p['shift']:+.2f} s (window {a:+.2f}..{b:+.2f} s, {n_shift} minutes); each time "
              "alone wants " + ", ".join(f"{e[:3].lower()} {(w[1][0] + w[1][1]) / 2:+.2f}" for e, w in own.items())
              + "; without the shift " + str(fit_points(full_items, pts, dict(p, shift=0.0))[1]))
    model = max(tied, key=lambda m: candidates[m][2])
    p, pts, total = candidates[model]
    print(f"  shipped sun: {model} (the engine's best on all six times); points: "
          + ", ".join(f"{z} {dm(la)}N {dm(lo)}E" for z, (la, lo) in sorted(pts.items())))

    def refit_total(q):
        return fit_points(full_items, pts, q, span=3)[1]
    checks = []
    for i, e in enumerate(EV):
        o = list(p["offsets"])
        row = []
        for k in (-1, 0, 1):
            o2 = list(o)
            o2[i] += k
            row.append((o2[i], refit_total(dict(p, offsets=tuple(o2)))))
        checks.append(f"{e[:3].lower()} " + "/".join(f"{v:+d}:{n}" for v, n in row))
    print("  offsets (value:minutes, points refitted): " + "; ".join(checks))
    print("  rounding: " + ", ".join(f"{r} {refit_total(dict(p, rounding=r))}" for r in ("NEAREST", "UP", "DOWN")))
    print("  Asr: " + ", ".join(f"{r} {refit_total(dict(p, asr=r))}" for r in ("shadow", "apparent", "shadow2")))
    hz = horizon_window(full, pts, p)
    fw, iw = angle_window(full, pts, p, "fajr"), angle_window(full, pts, p, "isha")
    n_days = sum(len(v) for v in full.values())
    print(f"  horizon window {hz[1][0]:+.4f}..{hz[1][1]:+.4f} deg ({hz[0]}/{2 * n_days}; -0.8333 "
          f"{windows_of(full, pts, p, 'horizon', [-0.8333])[-0.8333]}); Fajr {fw[1][0]:.3f}..{fw[1][1]:.3f} deg "
          f"({fw[0]}/{n_days}); Isha {iw[1][0]:.3f}..{iw[1][1]:.3f} deg ({iw[0]}/{n_days})")
    other_modes = {md: refit_total(dict(p, mode=md)) for md in ("DAILY_10H_UT", "KACST")}
    print("  other daily evaluations at these points: " + ", ".join(f"{k} {v}" for k, v in other_modes.items()))

    # Stage 3: the 86 points.
    page_items = {z: [(d, pages[d][z]) for d in fit_dates] for z in range(86)}
    year_items = {z: sorted(days.items()) for z, days in zone_year.items()}
    points = {}
    for z in range(86):
        if z in full:
            points[z] = pts[z]
        elif z in year_items:
            points[z] = best_point(year_items[z], seed_point(page_items[z], p), p)[1:]
        else:
            points[z] = best_point(page_items[z], seed_point(page_items[z], p), p)[1:]
    in_sample = {z: count(page_items[z], points[z][0] / 60, points[z][1] / 60, p) for z in range(86)}
    print(f"Stage 3: 86 points ({len(year_items)} of them on their whole 2026); on the 25 dates of 2026 "
          f"{sum(in_sample.values())}/{86 * 6 * len(fit_dates)} minutes (lowest zone {min(in_sample.values())}/{6 * len(fit_dates)})")
    if year_items:
        on_year = {z: count(year_items[z], points[z][0] / 60, points[z][1] / 60, p) for z in year_items}
        print(f"  the {len(year_items)} zones on their whole 2026: {sum(on_year.values())}/"
              f"{6 * sum(len(v) for v in year_items.values())} minutes (lowest zone {min(on_year.values())})")

    # Month-split folds of the six full-year zones: one shift for the six and a point per zone, fitted on half the
    # months (the odd ones, then the even ones).
    folds = {}
    for keep in (1, 0):
        part = {z: [x for x in full_items[z] if x[0].month % 2 == keep] for z in full}
        q, part_pts, _ = converge(part, pts, p, rounds=2)
        folds[keep] = (q, part_pts)
        print(f"  fold fitted on the {'odd' if keep else 'even'} months: shift {q['shift']:+.2f} s, points "
              + ", ".join(f"{z} {dm(la)}/{dm(lo)}" for z, (la, lo) in sorted(part_pts.items())))

    # Held out, with the reference engine.
    method = method_of(p)
    zone_scores, samples, notes = {}, {}, {}
    print("Held-out scoring with the reference engine ...", flush=True)
    for z in range(86):
        town = TOWNS[z]
        parts, held, fitted = [], [], []
        if z in full:
            for keep in (1, 0):                 # fitted on odd months, scored on even; then the reverse
                q, part_pts = folds[keep]
                s = common.Score()
                score_days(method_of(q), zone_of(z, (part_pts[z][0] / 60, part_pts[z][1] / 60), town),
                           [x for x in full_items[z] if x[0].month % 2 != keep], s)
                parts.append(s)
            d0, d1 = min(full[z]), max(full[z])
            fitted.append(common.sample(FULL_SOURCE, d0, d1, len(full[z]), 1))
            held.append(common.sample(FULL_SOURCE + "_month_split", d0, d1, len(full[z]), 1, month_split=True))
        elif z in year_items:                   # the zone's point on half the months, scored on the other half
            for keep in (1, 0):
                part = [x for x in year_items[z] if x[0].month % 2 == keep]
                _, la, lo = best_point(part, points[z], p)
                s = common.Score()
                score_days(method, zone_of(z, (la / 60, lo / 60), town), [x for x in year_items[z] if x[0].month % 2 != keep], s)
                parts.append(s)
            d0, d1 = year_items[z][0][0], year_items[z][-1][0]
            fitted.append(common.sample(ZONE_YEAR_SOURCE, d0, d1, len(year_items[z]), 1))
            held.append(common.sample(ZONE_YEAR_SOURCE + "_month_split", d0, d1, len(year_items[z]), 1, month_split=True))
        else:
            for keep in (1, 0):
                part = [x for x in page_items[z] if x[0].month % 2 == keep]
                _, la, lo = best_point(part, points[z], p)
                s = common.Score()
                score_days(method, zone_of(z, (la / 60, lo / 60), town), [x for x in page_items[z] if x[0].month % 2 != keep], s)
                parts.append(s)
            fitted.append(common.sample(PAGE_SOURCE, fit_dates[0], fit_dates[-1], len(fit_dates), 1))
            held.append(common.sample(PAGE_SOURCE + "_month_split", fit_dates[0], fit_dates[-1], len(fit_dates), 1,
                                      month_split=True))
        computing = (points[z][0] / 60, points[z][1] / 60)
        s = common.Score()
        score_days(method, zone_of(z, computing, town), [(d, pages[d][z]) for d in held_dates], s)
        parts.append(s)
        notes.setdefault("later", []).append(s)
        notes.setdefault("split", []).extend(parts[:2])
        held.append(common.sample(HELD_PAGE_SOURCE, held_dates[0], held_dates[-1], len(held_dates), 1))
        if z == 0:
            s = common.Score()
            score_days(method, zone_of(z, computing, town), sorted(years.items()), s)
            parts.append(s)
            notes["years"] = s
            held.append(common.sample(YEARS_SOURCE, min(years), max(years), len(years), 1))
        zone_scores[z] = merged(parts)
        samples[z] = (fitted, held)
        if z % 10 == 9:
            print(f"  {z + 1}/86 zones, {time.time() - started:.0f} s", flush=True)

    # Report.
    six = merged([zone_scores[z] for z in full])
    yearz = [z for z in range(86) if z in year_items]
    rest = [z for z in range(86) if z not in full and z not in year_items]
    allz = merged(zone_scores.values())
    print(f"Held out, the six full-year zones (month-split + 6 later dates; Muscat + 2021/2031): {shares(six)}")
    if yearz:
        print(f"Held out, the {len(yearz)} zones with calendar_page2's year (month-split + 6 later dates): "
              f"{shares(merged([zone_scores[z] for z in yearz]))}")
    if rest:
        print(f"Held out, the {len(rest)} other zones (month-split of the 25 dates + 6 later dates): "
              f"{shares(merged([zone_scores[z] for z in rest]))}")
    print(f"  Muscat's 2021 and 2031 months alone: {shares(notes['years'])}")
    print(f"  the 6 later page4 dates alone (86 zones): {shares(merged(notes['later']))}")
    print(f"  the month-splits alone (86 zones): {shares(merged(notes['split']))}")
    zone_stats, passing = {}, []
    for z in range(86):
        fitted, held = samples[z]
        stats = common.fit_stats(fitted, held, zone_scores[z])
        zone_stats[z] = stats
        if not tier_a(stats):
            passing.append(z)
    print(f"Tier A (9.5): {len(passing)} zones pass: {passing}")
    for z in full:
        f = tier_a(zone_stats[z])
        print(f"  zone {z} {english[z]}: " + ", ".join(f"{e[:3].lower()} {100 * zone_stats[z]['exact'][e]:.1f}" for e in EV)
              + ("; passes" if not f else "; fails: " + "; ".join(f)))
    weakest = sorted(range(86), key=lambda z: min(zone_stats[z]["exact"].values()))[:6]
    print("  weakest zones (lowest held-out time): " + ", ".join(
        f"{z} {english[z]} {min(zone_stats[z]['exact'], key=zone_stats[z]['exact'].get)[:3].lower()} "
        f"{100 * min(zone_stats[z]['exact'].values()):.1f}" for z in weakest))
    low80 = Counter(sum(1 for e in EV if zone_stats[z]["exact"][e] < 0.97) for z in range(86) if z not in full)
    print("  the 80 zones by the number of times below 97 %: " + ", ".join(f"{k}: {v}" for k, v in sorted(low80.items())))

    # ALMANAC, outside the engine: the same stages with the search code, held out the same way.
    if "ALMANAC" not in ENGINE:
        qa, pa, ta = candidates["ALMANAC"]
        alm = {"six": [0, 0], "rest": [0, 0]}
        alm_folds = {}
        for keep in (1, 0):
            part = {z: [x for x in full_items[z] if x[0].month % 2 == keep] for z in full}
            alm_folds[keep] = converge(part, pa, qa, rounds=2)[:2]
        for z in range(86):
            if z in full:
                for keep in (1, 0):
                    q, pp = alm_folds[keep]
                    test = [x for x in full_items[z] if x[0].month % 2 != keep]
                    alm["six"][0] += count(test, pp[z][0] / 60, pp[z][1] / 60, q)
                    alm["six"][1] += 6 * len(test)
                key, point = "six", pa[z]
            else:
                point = best_point(page_items[z], points[z], qa)[1:]
                for keep in (1, 0):
                    part = [x for x in page_items[z] if x[0].month % 2 == keep]
                    _, la, lo = best_point(part, point, qa)
                    test = [x for x in page_items[z] if x[0].month % 2 != keep]
                    alm["rest"][0] += count(test, la / 60, lo / 60, qa)
                    alm["rest"][1] += 6 * len(test)
                key = "rest"
            later_items = [(d, pages[d][z]) for d in held_dates]
            alm[key][0] += count(later_items, point[0] / 60, point[1] / 60, qa)
            alm[key][1] += 6 * len(later_items)
        yr = count(sorted(years.items()), pa[0][0] / 60, pa[0][1] / 60, qa)
        print(f"ALMANAC (not in the engine) with a shift of {qa['shift']:+.2f} s, held out the same way (search code): "
              f"six zones {alm['six'][0]}/{alm['six'][1]} ({100 * alm['six'][0] / alm['six'][1]:.2f} %), 80 zones "
              f"{alm['rest'][0]}/{alm['rest'][1]} ({100 * alm['rest'][0] / alm['rest'][1]:.2f} %), Muscat 2021/2031 "
              f"{yr}/{6 * len(years)}")

    # The asset.
    zones = []
    far = []
    for z in range(86):
        computing = (points[z][0] / 60, points[z][1] / 60)
        town = TOWNS[z]
        zone = {"id": str(z), "nameAr": names[z]}
        if town is None:
            zone["point"] = common.point(*computing)
        else:
            centre = common.point(town[2], town[3])
            zone["point"] = centre
            zone["morningPoint"] = zone["eveningPoint"] = common.point(*computing)
            zone["namedPlaces"] = [{"centre": centre, "catalogueIds": [], "areaKm": AREA_KM}]
            far.append((km(computing, town[2:4]), z))
        zone["fit"] = zone_stats[z]
        zones.append(zone)
    far.sort(reverse=True)
    print(f"Towns: {sum(1 for z in range(86) if TOWNS[z])} zones named after a town ({sum(1 for z in range(86) if TOWNS[z] and TOWNS[z][1] == GN)} "
          f"GeoNames, the rest OSM), {sum(1 for z in range(86) if not TOWNS[z])} without; computing point to town: "
          f"{sum(1 for d, _ in far if d <= 7)} within 7 km; farthest " + ", ".join(f"{english[z]} {d:.0f} km" for d, z in far[:6]))
    first, last = fit_dates[0], fit_dates[-1]
    y0, y1 = datetime.date(2026, 1, 1), datetime.date(2026, 12, 31)
    profile_fitted = [common.sample(FULL_SOURCE, y0, y1, 365, len(full)), common.sample(PAGE_SOURCE, first, last, len(fit_dates), 86)]
    profile_held = [common.sample(FULL_SOURCE + "_month_split", y0, y1, 365, len(full), month_split=True)]
    if yearz:
        profile_fitted.append(common.sample(ZONE_YEAR_SOURCE, y0, y1, 365, len(yearz)))
        profile_held.append(common.sample(ZONE_YEAR_SOURCE + "_month_split", y0, y1, 365, len(yearz), month_split=True))
    if rest:
        profile_held.append(common.sample(PAGE_SOURCE + "_month_split", first, last, len(fit_dates), len(rest), month_split=True))
    profile_held += [common.sample(HELD_PAGE_SOURCE, held_dates[0], held_dates[-1], len(held_dates), 86),
                     common.sample(YEARS_SOURCE, min(years), max(years), len(years), 1)]
    profile_stats = common.fit_stats(profile_fitted, profile_held, allz)
    path = common.write_asset(AUTHORITY, AUTHORITY, "OM", "NEAREST", zones, fit=profile_stats, named_first=True,
                              source=SOURCE_LINE)
    print(f"Profile: Fajr {p['fajr']} deg, Isha {p['isha']} deg, offsets {dict(zip(EV, p['offsets']))}, {p['rounding']}, "
          f"{p['model']} sun at {p['mode']}, shift {p['shift']:+.2f} s on every time (E18 constant {p['shift'] / 60:.6f} min)")
    print(f"Profile held out: {shares(allz)}")
    print(f"Wrote {path.relative_to(common.ROOT).as_posix()} ({len(zones)} zones) in {time.time() - started:.0f} s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
