#!/usr/bin/env python3
"""
Fits `sy_awqaf`, Syria's Ministry of Awqaf (plan 8.1, 8.2, 8.7, 8.8, Q12; milestone AM10, 9.4), and writes its
official-zone asset, android-app/app/src/main/assets/official-zones/sy_awqaf.json (schema 1).

The ministry's mosque screens (tv.masjidak.mow.gov.sy) and its app «مسجدك» show, for each region, a year table of 366
rows that aladhan.com's PrayerTimes computed (the PHP port of PrayTimes.js that the API runs; found 2026-10-18): MWL
18°/17°, the Astronomical Almanac's low-precision sun once per time at PrayTimes' default hours of local mean time on
the date (E4 PRAYTIMES, S1 ALMANAC), the 0.833° horizon, Dhuhr + 1 and Maghrib + 2 (the request's tune), each time to
the nearest minute, UTC+3. aladhan's Asr reads its noon shadow's declination at gregoriantojd + the API server clock's
day fraction + 13/24, so each table keeps the time of day it was fetched (the zone's `asrShadowHourUt`). A screen
shows row (the date's day in a leap year - 1) of its table (masjidi.min.js, getDayIndex with LEAD_MONTHS_DAYS
[31, 29, ...]), so each table's year and row layout decide what shows on a date (A8 at the zone: `referenceYear`,
`tableRows`):
- LEAP_ROWS_FEB28 or LEAP_ROWS_MAR1: the year's dates, row 59 (29 February) copying 28 February or 1 March;
- COMMON_ROWS (the north-east and the coast's last four, added 2026-08-20): the year's 365 days without 29 February,
  then 31 December again; the screens then show the next day's row from 1 March to 30 December, as the app does.

Data, read at run time and never copied (D13, A7):
- test-data-local/research-2026-10-18/SY/masjidak/region_<id>.json, ids 761-870 (README and MANIFEST there).
- test-data-local/research-2026-10-18/SY/masjidak/mosques.tsv: sampled screens (mosque id, region, city, address,
  name) from their configuration pages, which tie regions to towns.
- test-data-local/research-2026-10-08/LB/lb/region2.json: the Beirut table that tv.masjidi.app serves; region 763 is
  that table (checked here), so it is no Syrian zone.
- test-data-local/research-2026-10-08/SY/sy/dam.txt and raq1446.txt: the ministry's printed Ramadan tables, scored as a
  cross-check only (another product of the ministry).
- android-app/app/src/main/assets/places/arab-cities.tsv: the place catalogue, which names the zones.

Per distinct table (identical regions share a zone), by windows (a published minute bounds the unrounded time to a
minute; the value most windows hold wins) with the fast closed form below, which is the reference engine's PRAYTIMES
computation (checked here on every day of every zone):
1. The row layout from the file (row 59 copies row 58: FEB28; row 60: MAR1; else every layout is tried).
2. The year (2000-2032) by Dhuhr's windows, the best few then by the point's score.
3. The point: latitude scanned (32.2-37.4°, then finer), longitude by the windows of Fajr, sunrise, Dhuhr, Maghrib and
   Isha; the middle of the best latitudes.
4. Asr's shadow hour: 25 h + 24 h × f, f scanned by minutes, the middle of the best run.
Held out (FitStats, 9.4): month split, steps 2-4 fitted on the odd months and scored on the even ones and the reverse
(the layout is the file's); scores from common.compute, the reference engine, on the screens' dates of 2028 (a leap
year, so that every row shows).

Usage: python scripts/prayer_formula/fits/sy_awqaf/fit.py [--workers N]
"""

from __future__ import annotations

import argparse
import datetime
import json
import math
import os
import sys
from collections import Counter, defaultdict
from multiprocessing import Pool
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import common  # noqa: E402

ref = common.ref
EVENTS = common.EVENTS
AUTHORITY = "sy_awqaf"
TZ = "Asia/Damascus"                      # UTC+3 all year since 2022; every table is on UTC+3
TZ_MIN = 180
MJ = common.LOCAL_DATA / "research-2026-10-18" / "SY" / "masjidak"
MOSQUES = MJ / "mosques.tsv"
BEIRUT = common.LOCAL_DATA / "research-2026-10-08" / "LB" / "lb" / "region2.json"
SY = common.LOCAL_DATA / "research-2026-10-08" / "SY" / "sy"
CATALOGUE = common.ROOT / "android-app" / "app" / "src" / "main" / "assets" / "places" / "arab-cities.tsv"
KEYS = ("Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha")
ANGLES = {"FAJR": 18.0, "ISHA": 17.0}
TUNE = {"DHUHR": 1, "MAGHRIB": 2}
HORIZON = 0.833
SIDE = {"FAJR": -1, "SUNRISE": -1, "MAGHRIB": 1, "ISHA": 1, "ASR": 1}
D2R = math.pi / 180
FOLDS = (("A", (1, 3, 5, 7, 9, 11)), ("B", (2, 4, 6, 8, 10, 12)))   # the months each fold fits on
YEARS = range(2000, 2033)
LAYOUTS = ("LEAP_ROWS_FEB28", "LEAP_ROWS_MAR1", "COMMON_ROWS")
SCREEN_YEAR = 2028                        # a leap year: each of its dates shows one row
SCREEN_DAYS = [datetime.date(SCREEN_YEAR, 1, 1) + datetime.timedelta(days=i) for i in range(366)]
BEIRUT_ID = 763
SOURCE_LINE = ("Syria's Ministry of Awqaf: the year tables of its mosque screens and app «مسجدك» "
               "(tv.masjidak.mow.gov.sy), each region's as the screens show it")
SOURCE = "Ministry of Awqaf screen table (tv.masjidak.mow.gov.sy), region {ids}"
METHOD = common.method_json(
    id=AUTHORITY, fajr={"type": "angle", "degrees": ANGLES["FAJR"]}, isha={"type": "angle", "degrees": ANGLES["ISHA"]},
    offsets=dict(TUNE), sunModel="ALMANAC", sunEvaluation={e: "PRAYTIMES" for e in EVENTS},
    horizon={"sunrise": {"type": "fixed", "altitudeDeg": -HORIZON}, "sunset": {"type": "fixed", "altitudeDeg": -HORIZON}},
    highLatitude={"type": "angle_based"}, evidence={"kind": "FROM_TABLES", "source": "fit"})
SCREEN_KM = 120.0                         # a screen's town is matched within this distance of its table's computing point
SHADOW_KM = 1.0                           # an unseen table this near another zone's main town is left to manual picks
NAMED_KM = 3.0                            # a table's computing point names the place this near it
MAIN_SHARE = 0.25                         # the screens' first town is a zone's main town from this share of its screens
LIVE_MIN, LIVE_RATIO = 3, 2               # a town is live from 3 live screens, twice as many as on region tables
LIVE = common.LOCAL_DATA / "research-2026-10-18" / "SY" / "aladhan_live"   # the API's calendars for the live towns
LIVE_YEAR = 2024                          # masjidi.min.js requests calendar/2024
LIVE_SHADOW_HOUR = 37.0                   # a live screen's fetch time is unknown: the middle of the day's (f = 0.5)
LIVE_SOURCE = ("aladhan.com's API, calendar 2024 at {name} (the live screens' request: the town's point, no method; "
               "its default there, {method}), fetched 2026-10-18")


# ------------------------------------------------------------------------------------------------------------
# Data

def minute(text: str) -> int:
    h, m = text.strip().split(":")[:2]
    return int(h) * 60 + int(m)


def load_table(path: Path) -> list[dict[str, int]]:
    raw = json.loads(path.read_text(encoding="utf-8-sig"))
    if len(raw) != 366:
        raise SystemExit(f"{path.name}: {len(raw)} rows")
    return [{e: minute(r[k]) for e, k in zip(EVENTS, KEYS)} for r in raw]


def load_tables() -> dict[int, list[dict[str, int]]]:
    out = {}
    for rid in range(761, 871):
        path = MJ / f"region_{rid}.json"
        if path.exists():
            out[rid] = load_table(path)
    return out


def live_zone(town: dict, name: str, calendar: dict) -> tuple[dict, list[dict[str, int]], str]:
    """A live town's zone from the API's answer for its point: the default method's angles and minutes as angles and
    minutes on this method (its Dhuhr + 1 and Maghrib + 2), the 2024 calendar as the table (A8, CALENDAR rows), and Asr's
    shadow at the middle of the day's fetch times; with the answer's 366 rows and the method's name."""
    days = [d for month in sorted(calendar["data"], key=int) for d in calendar["data"][month]]
    meta = days[0]["meta"]
    params, tune = meta["method"]["params"], meta["offset"]
    maghrib = params.get("Maghrib", "0 min")
    if not (isinstance(params["Fajr"], (int, float)) and isinstance(params["Isha"], (int, float)) and str(maghrib).endswith("min")):
        raise SystemExit(f"{name}: the API's method {meta['method']} is not angles with Maghrib by minutes")
    offsets = {"FAJR": tune["Fajr"], "SUNRISE": tune["Sunrise"], "DHUHR": tune["Dhuhr"] - TUNE["DHUHR"], "ASR": tune["Asr"],
               "MAGHRIB": float(str(maghrib).split()[0]) + tune["Maghrib"] - TUNE["MAGHRIB"], "ISHA": tune["Isha"]}
    zone = common.zone_json(f"live{town['id']}", town["lat"], town["lng"], nameAr=name, referenceYear=LIVE_YEAR,
                            offsets={e: float(v) for e, v in offsets.items() if v}, asrShadowHourUt=LIVE_SHADOW_HOUR,
                            fajrAngleDeg=None if params["Fajr"] == ANGLES["FAJR"] else float(params["Fajr"]),
                            ishaAngleDeg=None if params["Isha"] == ANGLES["ISHA"] else float(params["Isha"]))
    zone.pop("authority", None)
    rows = [{e: minute(d["timings"][k].split()[0]) for e, k in zip(EVENTS, KEYS)} for d in days]
    if len(rows) != 366:
        raise SystemExit(f"{name}: {len(rows)} days")
    return zone, rows, f"{meta['method']['name']} (id {meta['method']['id']})"


def file_layouts(rows) -> tuple[str, ...]:
    """The layouts the file allows: its row 59 copies row 58 or row 60, else any."""
    if rows[59] == rows[58]:
        return ("LEAP_ROWS_FEB28", "COMMON_ROWS")
    if rows[59] == rows[60]:
        return ("LEAP_ROWS_MAR1", "COMMON_ROWS")
    return LAYOUTS


def load_screens(path: Path = MOSQUES, live: bool = False) -> list[tuple[int, str, str]]:
    """The sampled screens with a region table: (region, town, address); with [live], those on aladhan's live API
    instead, with region 0."""
    out = []
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines()[1:]:
            parts = line.split("\t")
            if len(parts) < 4:
                continue
            if live and parts[1] == "-":
                out.append((0, parts[2].strip(), parts[3].strip()))
            elif not live and parts[1].isdigit():
                out.append((int(parts[1]), parts[2].strip(), parts[3].strip()))
    return out


def live_towns(live: list, tables: list, catalogue: list[dict]) -> list[tuple[dict, int, int, str]]:
    """The catalogue towns whose sampled screens run aladhan's API live: at least LIVE_MIN of them, and LIVE_RATIO times
    as many as screens on a region table there. A screen's town (else its address) is the catalogue place of that Arabic
    name, the most populous where several are; (town, live screens, table screens, the screens' own spelling)."""
    by_key = defaultdict(list)
    for c in catalogue:
        for k in c["keys"]:
            by_key[k].append(c)
    counts: dict[int, list[int]] = defaultdict(lambda: [0, 0])
    spelled: dict[int, Counter] = defaultdict(Counter)
    rows = {c["id"]: c for c in catalogue}
    for screens, which in ((live, 0), (tables, 1)):
        for _, town, address in screens:
            hits = by_key.get(arabic_key(town)) or by_key.get(arabic_key(address))
            if hits:
                c = max(hits, key=lambda c: c["population"])
                counts[c["id"]][which] += 1
                if which == 0:
                    spelled[c["id"]][town] += 1
    out = [(rows[cid], n_live, n_table, spelled[cid].most_common(1)[0][0]) for cid, (n_live, n_table) in counts.items()
           if n_live >= LIVE_MIN and n_live >= LIVE_RATIO * n_table]
    return sorted(out, key=lambda t: (-t[1], t[0]["id"]))


def load_catalogue() -> list[dict]:
    lines = CATALOGUE.read_text(encoding="utf-8").splitlines()
    header = lines[0].split("\t")
    rows = []
    for line in lines[1:]:
        c = dict(zip(header, line.split("\t")))
        if c["country"] != "SY" or "n" in c.get("flags", ""):
            continue
        names = [c["arabic"]] + [a for a in c.get("aliases", "").split("|") if a and re_arabic(a)]
        rows.append({"id": int(c["id"]), "arabic": c["arabic"], "name": c["name"], "lat": int(c["latE5"]) / 1e5,
                     "lng": int(c["lngE5"]) / 1e5, "population": int(c["population"] or 0),
                     "keys": {arabic_key(n) for n in names if arabic_key(n)}})
    return rows


def re_arabic(text: str) -> bool:
    return any("؀" <= ch <= "ۿ" for ch in text)


# ------------------------------------------------------------------------------------------------------------
# The fast closed form (aladhan's PrayerTimes): local minutes on UTC+3 before rounding

_SUN: dict = {}


def sun(jd: float) -> tuple[float, float, float]:
    v = _SUN.get(jd)
    if v is None:
        if len(_SUN) > 400_000:
            _SUN.clear()
        v = _SUN[jd] = ref.almanac_sun(jd)
    return v


def unrounded(e: str, day: datetime.date, lat: float, lng: float, shadow_hour: float | None = None,
              angles: dict | None = None, extra: dict | None = None) -> float:
    """[angles]: a zone's own Fajr and Isha angles; [extra]: its minutes (a live town's)."""
    jd0 = ref.julian_day0(day)
    decl, eot, _ = sun(jd0 - lng / 360 + ref.PRAYTIMES_HOURS[e] / 24)
    noon = 720 + TZ_MIN - 4 * lng - eot
    minutes = TUNE.get(e, 0) + (extra or {}).get(e, 0)
    if e == "DHUHR":
        return noon + minutes
    if e == "ASR":
        noon_decl = decl if shadow_hour is None else sun(jd0 + shadow_hour / 24)[0]
        alt = math.atan(1 / (1 + math.tan(abs(lat - noon_decl) * D2R))) / D2R
    else:
        alt = -{**ANGLES, **(angles or {})}.get(e, HORIZON)
    c = (math.sin(alt * D2R) - math.sin(lat * D2R) * math.sin(decl * D2R)) / (math.cos(lat * D2R) * math.cos(decl * D2R))
    return noon + SIDE[e] * 4 * math.acos(max(-1.0, min(1.0, c))) / D2R + minutes


def rounded(u: float) -> int:
    return math.floor(u + 0.5)


def cover(windows) -> tuple[int, float | None, tuple[float, float] | None]:
    """The most windows any value holds, the middle of the widest such stretch, and that stretch."""
    marks = sorted([(a, 1) for a, _ in windows] + [(b, -1) for _, b in windows])
    best, cur, stretch = 0, 0, None
    for i, (x, s) in enumerate(marks):
        cur += s
        nxt = marks[i + 1][0] if i + 1 < len(marks) else x
        if nxt <= x:
            continue
        if cur > best or (cur == best and stretch is not None and nxt - x > stretch[1] - stretch[0]):
            best, stretch = cur, (x, nxt)
    return best, None if stretch is None else (stretch[0] + stretch[1]) / 2, stretch


def middle_of_best(scan: list[tuple[float, int]]) -> tuple[float, int, float, float]:
    """The middle of the longest run of the best count in an evenly stepped scan: (value, count, run start, run end)."""
    top = max(n for _, n in scan)
    runs, cur = [], []
    for v, n in scan:
        if n == top:
            cur.append(v)
        elif cur:
            runs.append(cur)
            cur = []
    if cur:
        runs.append(cur)
    run = max(runs, key=len)
    return run[len(run) // 2], top, run[0], run[-1]


# ------------------------------------------------------------------------------------------------------------
# The fit of one table

def shown(day: datetime.date, year: int, layout: str) -> datetime.date:
    return ref.table_date(day, year, layout)


def lng_windows(cases, lat: float, lng0: float):
    """Per published minute of the five times, the longitudes at [lat] that give it (u falls 4 min per degree east)."""
    out = []
    for day, t in cases:
        for e in ("FAJR", "SUNRISE", "DHUHR", "MAGHRIB", "ISHA"):
            u0 = unrounded(e, day, lat, lng0)
            out.append((lng0 + (u0 - (t[e] + 0.5)) / 4, lng0 + (u0 - (t[e] - 0.5)) / 4))
    return out


def point_fit(cases, lat_lo: float, lat_hi: float, step: float, lng0: float = 38.0):
    """Latitude scanned from lat_lo to lat_hi, the longitude by windows at each; the best count's middle latitude."""
    scan = []
    n_steps = int(round((lat_hi - lat_lo) / step))
    for k in range(n_steps + 1):
        lat = round(lat_lo + k * step, 6)
        n, lng, _ = cover(lng_windows(cases, lat, lng0))
        scan.append((lat, n, lng))
    lat, top, a, b = middle_of_best([(lat, n) for lat, n, _ in scan])
    lng = next(g for x, n, g in scan if x == lat)
    return top, lat, lng, (a, b)


def fit_point(cases, lat_hint: float | None = None):
    """Coarse, fine and finest latitude scans (the longitude re-centred each time)."""
    if lat_hint is None:
        n, lat, lng, _ = point_fit(cases, 32.2, 37.4, 0.02)
    else:
        n, lat, lng, _ = point_fit(cases, lat_hint - 0.06, lat_hint + 0.06, 0.004)
    n, lat, lng, _ = point_fit(cases, lat - 0.024, lat + 0.024, 0.001, lng)
    n, lat, lng, run = point_fit(cases, lat - 0.0012, lat + 0.0012, 0.00005, lng)
    n, _, lng, _ = point_fit(cases, lat, lat, 1.0, lng)                # the longitude at that latitude, re-centred
    return n, lat, lng, run


def fit_shadow(cases, lat: float, lng: float) -> tuple[float, int, float, float]:
    """Asr's shadow hour 25 + 24 f, f by minutes over [0, 1.05): the middle of the longest best run."""
    scan = []
    for k in range(0, 1512):
        hour = 25 + k / 60
        scan.append((hour, sum(rounded(unrounded("ASR", day, lat, lng, hour)) == t["ASR"] for day, t in cases)))
    return middle_of_best(scan)


def fit_table(job):
    """One table on the screen days of [months]: layout (given), year, point, shadow hour."""
    key, rows, layouts, months = job
    days = [i for i, d in enumerate(SCREEN_DAYS) if d.month in months]
    # Year and layout by Dhuhr's windows (the longitude absorbs the constant), then the point for the best few.
    ranked = []
    for layout in layouts:
        for year in YEARS:
            wins = []
            for i in days:
                u = unrounded("DHUHR", shown(SCREEN_DAYS[i], year, layout), 34.8, 38.0)
                wins.append((38.0 + (u - (rows[i]["DHUHR"] + 0.5)) / 4, 38.0 + (u - (rows[i]["DHUHR"] - 0.5)) / 4))
            ranked.append((cover(wins)[0], -abs(year - 2025), year, layout))
    ranked.sort(reverse=True)
    best, lat_hint = None, None
    for n_dhuhr, _, year, layout in ranked[:4]:
        cases = [(shown(SCREEN_DAYS[i], year, layout), rows[i]) for i in days]
        n, lat, lng, run = fit_point(cases, lat_hint)
        lat_hint = lat
        cand = (n, -abs(year - 2025), year, layout, lat, lng, run, cases)
        if best is None or cand[:2] > best[:2]:
            best = cand
    n, _, year, layout, lat, lng, run, cases = best
    hour, n_asr, h0, h1 = fit_shadow(cases, lat, lng)
    exact = {e: sum(rounded(unrounded(e, day, lat, lng, hour)) == t[e] for day, t in cases) for e in EVENTS}
    return {"key": key, "months": months, "year": year, "layout": layout, "lat": round(lat, 5), "lng": round(lng, 5),
            "latRun": run, "shadowHour": round(hour, 4), "shadowRun": (h0, h1), "exact": exact, "days": len(days),
            "dhuhrTop": [(r[0], r[2], r[3]) for r in ranked[:6]]}


# ------------------------------------------------------------------------------------------------------------
# Scoring through the reference engine

def zone_of(zid: str, name: str, town: tuple[float, float], p: dict) -> dict:
    z = common.zone_json(zid, town[0], town[1], nameAr=name, referenceYear=p["year"], tableRows=p["layout"],
                         asrShadowHourUt=p["shadowHour"])
    computing = common.point(p["lat"], p["lng"])
    if computing != z["point"]:
        z["morningPoint"] = z["eveningPoint"] = computing
    z.pop("authority", None)
    return z


def score_job(job):
    """The reference's minutes of a zone on the given screen days against its table: (key, [(day, event, got, want)])."""
    key, zone, rows, day_indexes = job
    lat, lng = zone["point"]["latE5"] / 1e5, zone["point"]["lngE5"] / 1e5
    out = []
    for i in day_indexes:
        day = SCREEN_DAYS[i]
        got = common.compute(common.case(METHOD, lat, lng, TZ, day, zone=zone, country="SY"))
        for e in EVENTS:
            out.append((i, e, (got[e][1] + TZ_MIN * 60) // 60 % 1440, rows[i][e]))
    return key, out


def score_of(results) -> common.Score:
    s = common.Score()
    for i, e, got, want in results:
        s.add(e, SCREEN_DAYS[i], got, want)
    return s


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


def pct(score: common.Score) -> str:
    return " ".join(f"{e[:3]} {100 * score.exact[e] / score.total[e]:.2f}%/{100 * score.within1[e] / score.total[e]:.1f}%"
                    for e in EVENTS if score.total[e])


def passes(score: common.Score) -> bool:
    return all(score.exact[e] >= 0.97 * score.total[e] and score.within1[e] == score.total[e]
               and min(score.months[e]) >= 7 for e in EVENTS)


# ------------------------------------------------------------------------------------------------------------
# Names and towns

def km(lat1, lng1, lat2, lng2) -> float:
    a = (math.sin((lat2 - lat1) * D2R / 2) ** 2
         + math.cos(lat1 * D2R) * math.cos(lat2 * D2R) * math.sin((lng2 - lng1) * D2R / 2) ** 2)
    return 2 * 6371.0 * math.asin(math.sqrt(a))


def arabic_key(text: str) -> str:
    """Arabic folded for matching a screen's town with the catalogue: hamzas, alif maqsura, ta marbuta, «ال», spaces."""
    t = text.strip()
    for a, b in (("أ", "ا"), ("إ", "ا"), ("آ", "ا"), ("ى", "ي"), ("ة", "ه"), ("ـ", "")):
        t = t.replace(a, b)
    words = [w[2:] if w.startswith("ال") and len(w) > 3 else w for w in t.split()]
    return "".join(words)


def assign_towns(zones_in: dict, computing: dict, screens, catalogue: list[dict], live: dict[int, tuple[str, str]] | None = None) -> dict:
    """Each zone's towns and mapping (6.5, 8.8): a sampled screen's town (else its address) is the catalogue place of
    that Arabic name nearest the zone's computing point, within SCREEN_KM; a place goes to the zone with the most
    screens there. A zone takes its main town (most screens) as `point` and name, and all its towns as named places.
    A zone without matched screens keeps its computing point, named after the nearest catalogue place no other zone
    holds; one without any sampled screen that lies within SHADOW_KM of another zone's town is left to manual picks
    (tier 1), since that town's screens show the other table. [live]: the live towns' catalogue ids, each with its live
    zone's id and name, which no table zone holds."""
    live = live or {}
    by_key = defaultdict(list)
    for c in catalogue:
        for k in c["keys"]:
            by_key[k].append(c)
    rows = {c["id"]: c for c in catalogue}
    zone_of_region = {rid: zid for zid, (rids, _) in zones_in.items() for rid in rids}
    counts: dict[int, Counter] = defaultdict(Counter)
    spelled: dict[int, Counter] = defaultdict(Counter)      # the screens' own Arabic spelling of each matched place
    sampled, matched = Counter(), Counter()
    for rid, town, address in screens:
        zid = zone_of_region.get(rid)
        if zid is None:
            continue
        sampled[zid] += 1
        lat, lng = computing[zid]
        for text in (town, address):
            hits = [c for c in by_key.get(arabic_key(text), []) if km(lat, lng, c["lat"], c["lng"]) <= SCREEN_KM]
            if hits:
                c = min(hits, key=lambda c: km(lat, lng, c["lat"], c["lng"]))
                if c["id"] not in live:
                    counts[c["id"]][zid] += 1
                    spelled[c["id"]][text.strip()] += 1
                    matched[zid] += 1
                break

    def arabic_name(c: dict) -> str | None:
        """A place's Arabic name: the screens' own spelling, else the catalogue's where it is Arabic."""
        if spelled[c["id"]]:
            return spelled[c["id"]].most_common(1)[0][0]
        return c["arabic"] if re_arabic(c["arabic"]) else None
    towns: dict[str, Counter] = defaultdict(Counter)
    for cid, by_zone in counts.items():
        top = max(by_zone.values())
        c = rows[cid]
        zid = min((z for z, n in by_zone.items() if n == top), key=lambda z: km(*computing[z], c["lat"], c["lng"]))
        towns[zid][cid] = by_zone[zid]
    held = {cid: zid for zid, t in towns.items() for cid in t}

    def nearest_to(zid):
        lat, lng = computing[zid]
        return sorted(((km(lat, lng, c["lat"], c["lng"]), c) for c in catalogue), key=lambda x: x[0])

    # Main towns: the computing point's own town (within NAMED_KM, held by no other zone) where the screens hold it or
    # name no town of their own often (under MAIN_SHARE of the sampled screens); else the screens' first town.
    # Zones claim their main towns in order of their sampled screens, so that no town is two zones' main town.
    mains: dict[str, dict] = {}
    claimed: set[int] = set(live)
    for zid in sorted(zones_in, key=lambda z: (-sampled[z], int(z[1:]))):
        d0, c0 = nearest_to(zid)[0]
        own = c0 if d0 <= NAMED_KM and held.get(c0["id"], zid) == zid and c0["id"] not in claimed else None
        ranked = sorted(towns[zid].items(), key=lambda kv: (-kv[1], -rows[kv[0]]["population"]))
        if own is not None and (own["id"] in towns[zid] or not ranked or ranked[0][1] < MAIN_SHARE * sampled[zid]):
            mains[zid] = own
        elif ranked:
            mains[zid] = rows[ranked[0][0]]
        if zid in mains:
            claimed.add(mains[zid]["id"])
    main_ids = {c["id"]: zid for zid, c in mains.items()} | {cid: z for cid, (z, _) in live.items()}
    out, used_names = {}, {name for _, name in live.values()}
    for zid in sorted(zones_in, key=lambda z: (z not in mains, int(z[1:]))):
        lat, lng = computing[zid]
        ranked = sorted(towns[zid].items(), key=lambda kv: (-kv[1], -rows[kv[0]]["population"]))
        screens_note = (", ".join(f"{arabic_name(rows[cid]) or rows[cid]['name']} {n}" for cid, n in ranked)
                        or "no town matched") + f" of {sampled[zid]} sampled screens"
        near = [(d, c) for d, c in nearest_to(zid) if arabic_name(c)]
        if zid in mains:
            main = mains[zid]
            name = arabic_name(main) or arabic_name(near[0][1])
            named = [main] + [rows[cid] for cid, _ in ranked if cid != main["id"]]
            point, how = (main["lat"], main["lng"]), f"town {name}; {screens_note}"
        else:
            # No main town: the computing point, named after its nearest place; a table without sampled screens at
            # another zone's main town, or at another table's computing point, is left to manual picks.
            d0, nearest = near[0]
            twin = next((z for z in out if km(lat, lng, *computing[z]) < 0.1), None)
            shadowed = sampled[zid] == 0 and ((nearest["id"] in main_ids and d0 <= SHADOW_KM) or twin is not None)
            free = d0 <= NAMED_KM and not shadowed and nearest["id"] not in held and nearest["id"] not in main_ids
            name, named, point = arabic_name(nearest), ([nearest] if free else []), (lat, lng)
            how = f"computing point, {d0:.1f} km from {name}; {screens_note}"
            if shadowed:
                how += f"; manual pick only ({'computed at ' + twin + chr(39) + 's point' if twin else main_ids[nearest['id']] + chr(39) + 's town'})"
        if name in used_names:
            name = f"{name} ({zid[1:]})"
        used_names.add(name)
        out[zid] = {"point": point, "name": name, "named": named, "tier": 1 if zid not in mains and shadowed else 0, "how": how}
    return {zid: out[zid] for zid in zones_in}


# ------------------------------------------------------------------------------------------------------------
# The validity guard (side check)

# Places of 8.8's guard list and the live screens' towns, at approximate town points (GeoNames, not verified).
GUARD_PLACES = (("Tabqa", 35.8361, 38.5481), ("Zabadani", 33.7250, 36.0972), ("Khan Shaykhun", 35.4417, 36.6500),
                ("Al-Tanf", 33.4975, 38.6733), ("Ras al-Ayn", 36.8503, 40.0706), ("Sukhnah", 34.8964, 38.8706),
                ("Tell Abyad", 36.6975, 38.9567), ("Palmyra", 34.5603, 38.2672), ("Malikiyah", 37.1806, 42.1394),
                ("Qamishli", 37.0522, 41.2311), ("Nabek", 34.0233, 36.7281), ("Deir ez-Zor", 35.3359, 40.1408),
                ("Mayadin", 35.0207, 40.4518), ("Abu Kamal", 34.4536, 40.9367), ("Quneitra", 33.1260, 35.8243))


def mapped(lat: float, lng: float, zones: list[dict]) -> dict:
    """The zone NEAREST with namedFirst gives a place that is no catalogue pick: the nearest named centre or point, in
    the lowest tier (ZoneMapping.nearestWithRules)."""
    tier = min(z.get("mappingTier", 0) for z in zones)

    def distance(z):
        centres = [n["centre"] for n in z.get("namedPlaces", [])] + [z["point"]]
        return min(km(lat, lng, c["latE5"] / 1e5, c["lngE5"] / 1e5) for c in centres)
    return min((z for z in zones if z.get("mappingTier", 0) == tier), key=distance)


def guard_job(job):
    """A place's verdict (6.5): the zone table's leads over the method at the place's own point (unrounded, a sunrise
    late counting as a lead), beyond the table's own margin at its zone point, in minutes per time, over 2026."""
    name, lat, lng, zone = job
    days = [datetime.date(2026, 1, 1) + datetime.timedelta(days=i) for i in range(365)]
    zlat, zlng = zone["point"]["latE5"] / 1e5, zone["point"]["lngE5"] / 1e5

    def run(a, b, z=None):
        return [common.compute(common.case(METHOD, a, b, TZ, d, zone=z, country="SY")) for d in days]

    def leads(own, table):
        out = {e: [] for e in EVENTS}
        for r, t in zip(own, table):
            for e in EVENTS:
                lead = (r[e][0] - t[e][1]) / 60
                out[e].append(-lead if e == "SUNRISE" else lead)
        return out
    table = run(zlat, zlng, zone)
    margin = {e: max(v) for e, v in leads(run(zlat, zlng), table).items()}
    excess = {e: max(v) - margin[e] for e, v in leads(run(lat, lng), table).items()}
    worst = max(excess, key=excess.get)
    return name, zone["id"], zone["nameAr"], km(lat, lng, zlat, zlng), worst, excess[worst]


# ------------------------------------------------------------------------------------------------------------

def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--workers", type=int, default=max(1, min(12, (os.cpu_count() or 2) - 2)))
    ap.add_argument("--screens", default=str(MOSQUES), help="the sampled screens (default: the research folder's)")
    ap.add_argument("--no-guard", action="store_true", help="skip the validity-guard side check")
    args = ap.parse_args(argv)
    sys.stdout.reconfigure(encoding="utf-8")

    tables = load_tables()
    beirut = load_table(BEIRUT) if BEIRUT.exists() else None
    same = beirut is not None and tables.get(BEIRUT_ID) == beirut
    print(f"{len(tables)} region tables; region {BEIRUT_ID} "
          + ("is the Beirut table of tv.masjidi.app on all 366 rows: left out" if same else "left out (Beirut's, by the earlier check)"))
    tables.pop(BEIRUT_ID, None)
    groups: dict[tuple, list[int]] = defaultdict(list)
    for rid, rows in sorted(tables.items()):
        groups[tuple(tuple(r[e] for e in EVENTS) for r in rows)].append(rid)
    zones_in = {f"r{rids[0]}": (rids, tables[rids[0]]) for rids in groups.values()}
    print(f"{len(zones_in)} distinct tables: " + ", ".join("/".join(map(str, r)) for r, _ in zones_in.values() if len(r) > 1)
          + " are identical")

    jobs = []
    for zid, (rids, rows) in zones_in.items():
        layouts = file_layouts(rows)
        for name, months in FOLDS:
            jobs.append(((zid, name), rows, layouts, months))
        jobs.append(((zid, "all"), rows, layouts, tuple(range(1, 13))))
    with Pool(args.workers) as pool:
        fitted = {r["key"]: r for r in pool.map(fit_table, jobs, chunksize=1)}

    # The towns from the sampled screens (the layout is the file's: where a fold's differs, the whole year's is scored).
    screens = load_screens(Path(args.screens))
    catalogue = load_catalogue()
    computing = {zid: (fitted[(zid, "all")]["lat"], fitted[(zid, "all")]["lng"]) for zid in zones_in}
    # The live towns: a zone each from the API's answer for the town (fetched once into LIVE; a town without it is left out).
    lives = []
    for town, n_live, n_table, name in live_towns(load_screens(Path(args.screens), live=True), screens, catalogue):
        path = LIVE / f"{town['id']}.json"
        if not path.exists():
            print(f"  live town {name}: no API answer in {LIVE.name}, left out")
            continue
        zone, rows, method_name = live_zone(town, name, json.loads(path.read_text(encoding="utf-8")))
        zone["namedPlaces"] = [{"centre": zone["point"], "catalogueIds": [], "areaKm": 0.0}]
        lives.append((zone, rows, method_name, n_live, n_table))
    towns = assign_towns(zones_in, computing, screens, catalogue, live={int(z["id"][4:]): (z["id"], z["nameAr"]) for z, *_ in lives})
    print(f"{len(screens)} sampled screens on region tables ({MOSQUES.name if Path(args.screens) == MOSQUES else args.screens}); "
          f"{len(lives)} live towns")

    # Held out: each fold's values scored on the other months; in-sample: the shipped values on every day.
    score_jobs = []
    for zid, (rids, rows) in zones_in.items():
        t = towns[zid]
        for name, months in FOLDS:
            p = dict(fitted[(zid, name)], layout=fitted[(zid, "all")]["layout"])
            held = [i for i, d in enumerate(SCREEN_DAYS) if d.month not in months]
            score_jobs.append(((zid, name), zone_of(zid, t["name"], t["point"], p), rows, held))
        p = fitted[(zid, "all")]
        score_jobs.append(((zid, "all"), zone_of(zid, t["name"], t["point"], p), rows, list(range(366))))
    for zone, rows, *_ in lives:                       # nothing is fitted on a live town: its whole answer is held out
        score_jobs.append(((zone["id"], "all"), zone, rows, list(range(366))))
    with Pool(args.workers) as pool:
        scored = dict(pool.map(score_job, score_jobs, chunksize=1))

    zones, held_scores, in_scores, tier_a = [], [], [], 0
    print("\nzone   regions          layout           year  computing point     shadow h  name (how)  | held out exact / within 1")
    for zid, (rids, rows) in zones_in.items():
        p = fitted[(zid, "all")]
        t = towns[zid]
        held = merge(score_of(scored[(zid, name)]) for name, _ in FOLDS)
        inside = score_of(scored[(zid, "all")])
        held_scores.append(held)
        in_scores.append(inside)
        z = zone_of(zid, t["name"], t["point"], p)
        z["namedPlaces"] = [{"centre": common.point(c["lat"], c["lng"]), "catalogueIds": [], "areaKm": 0.0} for c in t["named"]]
        if t["tier"]:
            z["mappingTier"] = t["tier"]
        ids = ", ".join(map(str, rids))
        sample = common.sample(SOURCE.format(ids=ids), SCREEN_DAYS[0], SCREEN_DAYS[-1], 366, 1)
        split = common.sample(SOURCE.format(ids=ids) + "; month split", SCREEN_DAYS[0], SCREEN_DAYS[-1], 366, 1, month_split=True)
        z["fit"] = common.fit_stats([sample], [split], held)
        zones.append(z)
        ok = passes(held)
        tier_a += ok
        folds = " ".join(f"{name}:{fitted[(zid, name)]['year']}" for name, _ in FOLDS)
        print(f"{zid:<6} {'/'.join(map(str, rids)):<16} {p['layout']:<16} {p['year']}  {p['lat']:.5f} {p['lng']:.5f}  "
              f"{p['shadowHour']:7.3f}  {t['name']} ({t['how']}) | {pct(held)}"
              + ("" if ok else "  [not tier A]") + f"  folds {folds}"
              + (f"  misses {held.misses[:6]}" if held.misses else ""))
    all_held = merge(held_scores)
    all_in = merge(in_scores)
    print("\nLive towns (aladhan's API at the town; every day held out, Asr's fetch time unknown):")
    live_scores = []
    for zone, rows, method_name, n_live, n_table in lives:
        score = score_of(scored[(zone["id"], "all")])
        live_scores.append(score)
        source = LIVE_SOURCE.format(name=zone["nameAr"], method=method_name)
        zone["fit"] = common.fit_stats([], [common.sample(source, SCREEN_DAYS[0], SCREEN_DAYS[-1], 366, 1)], score)
        zones.append(zone)
        settings = ", ".join(f"{e.lower()} {v:+g}" for e, v in zone["offsets"].items())
        angles = "".join(f", {k[:4]} {zone[k]:g}°" for k in ("fajrAngleDeg", "ishaAngleDeg") if zone.get(k) is not None)
        print(f"  {zone['id']:<12} {zone['nameAr']} ({n_live} live screens, {n_table} on tables): {method_name}; {settings}{angles} | "
              f"{pct(score)}" + ("" if passes(score) else "  [not tier A]"))
    if lives:
        print(f"  all live towns: {pct(merge(live_scores))}; worst {merge(live_scores).worst} min")
    ids = sorted(rid for rids, _ in zones_in.values() for rid in rids)
    # The profile's record is the region tables' (the live towns keep their own).
    profile_fit = common.fit_stats(
        [common.sample(SOURCE.format(ids=f"{ids[0]}-{ids[-1]} ({len(ids)} regions)"), SCREEN_DAYS[0], SCREEN_DAYS[-1],
                       366 * len(zones_in), len(zones_in))],
        [common.sample(SOURCE.format(ids=f"{ids[0]}-{ids[-1]}") + "; month split", SCREEN_DAYS[0], SCREEN_DAYS[-1],
                       366 * len(zones_in), len(zones_in), month_split=True)],
        all_held)
    path = common.write_asset(AUTHORITY, AUTHORITY, "SY", "NEAREST", zones, fit=profile_fit, source=SOURCE_LINE, named_first=True)
    print(f"\nHeld out (month split, FitStats), {len(zones_in)} table zones: {pct(all_held)}; worst {all_held.worst} min; "
          f"{tier_a} of {len(zones_in)} at tier A")
    print(f"In-sample (the shipped values): {pct(all_in)}; worst {all_in.worst} min")
    layouts = Counter(fitted[(z, 'all')]["layout"] for z in zones_in)
    years = Counter(fitted[(z, 'all')]["year"] for z in zones_in)
    print("Layouts: " + ", ".join(f"{k} {v}" for k, v in layouts.items()) + "; years: "
          + ", ".join(f"{k} {v}" for k, v in sorted(years.items())))
    if not args.no_guard:
        print("Validity guard, 2026 (side check; approximate town points; the guard acts beyond 4 min):")
        with Pool(args.workers) as pool:
            for name, zid, zname, d, worst, excess in pool.map(
                    guard_job, [(n, a, b, mapped(a, b, zones)) for n, a, b in GUARD_PLACES], chunksize=1):
                print(f"  {name:<14} -> {zid} «{zname}» {d:5.0f} km: worst {worst.lower()} {excess:+.1f} min beyond the margin: "
                      + ("keeps the table" if excess <= 4 else "its own point"))
    print(f"Wrote {path.relative_to(common.ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
