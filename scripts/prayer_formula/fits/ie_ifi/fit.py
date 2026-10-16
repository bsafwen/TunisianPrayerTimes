#!/usr/bin/env python3
"""
Fits `ie_ifi`, the Islamic Foundation of Ireland's perpetual Dublin table (plan 7.2, 7.5, Q23; milestone M3), and
writes its official-zone asset, android-app/app/src/main/assets/official-zones/ie_ifi.json (schema 1): the IFI's 33
towns as NEAREST zones (6.5), each computed at its own fitted point with Dublin's seasonal intervals.

Data, read at run time from test-data-local and never copied (D13, A7), fetched by fetch.py:
- official/ie/ifi/timetable.json: the perpetual table of islamicfoundation.ie's timetable app (month -> day -> six
  [hour, minute] pairs: Fajr, Shurooq, Dhuhr, Asr, Maghrib, Isha). Its clock is Irish summer time from 1 April to
  31 October and GMT otherwise (the table jumps by an hour between 31 March and 1 April, and between 31 October and
  1 November), not the summer-time Sundays; the fit reads every row in UTC with that clock, so the days its clock
  lags the legal one (29-31 March and 25-31 October 2026) are the table's own instants (the app shows them in the
  legal clock).
- official/ie/ifi/ramadan-2026/<town>_ramadan_timetable_2026.pdf: the IFI's Ramadan 2026/1447 tables for Dublin and
  32 other towns (19 February to 20 March 2026, GMT), read with ramadan_pdf.py.
- research-2026-10-11/GEONAMES/cities1000.zip: the towns' centres (the zones' `point`, for the mapping).

The method (7.5): Fajr = sunrise - n + E18, Isha = Maghrib + 90 + E18 (never under 90: the Isha values are kept at
0 or more), and E18 seasonal offsets (36 values a year, linear between them; README S9) on sunrise, Dhuhr, Asr and
Maghrib; standard horizon, MEEUS, the sun at the event instant, NEAREST rounding. The table is perpetual: it is fitted
at 2026 dates (E5: the year of the Ramadan tables) and also scored as the app shows 2027-2029, since the app computes
the actual year (A8).

Stages (9.4), by windows: a published minute bounds the unrounded time to a half-open minute; per time, the 36 knots
are set one at a time to the value most of its days' windows cover, the others held, sweeping until nothing moves
(Isha after Maghrib, since it counts from Maghrib's unrounded instant). Held out: month-split (the knots refitted on
the odd months and scored on the even ones, and the reverse; a knot with no fitted day in reach takes the line
between its nearest fitted neighbours), every held-out day scored with common.compute (the reference engine).

The towns: each town's computing point is fitted on its own 30 Ramadan days with Dublin's profile (longitude from
Dhuhr, then latitude from sunrise and Maghrib, by windows), and scored with common.compute: in-sample, and held out by
a day-split (the point fitted on the odd Ramadan days and scored on the even ones, and the reverse).

Usage: python scripts/prayer_formula/fits/ie_ifi/fit.py
"""

from __future__ import annotations

import datetime
import json
import math
import sys
import time
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))
import common  # noqa: E402
import ramadan_pdf  # noqa: E402

ref = common.ref
EVENTS = common.EVENTS
AUTHORITY = "ie_ifi"
TZ = "Europe/Dublin"
YEAR = 2026
YEARS = (YEAR,)                           # the fit year (E5): the year of the Ramadan tables, which equal the table
SCORED_YEARS = (2026, 2027, 2028, 2029)   # as the app shows them (A8)
DATA = common.LOCAL_DATA / "official" / "ie" / "ifi"
GEONAMES = common.LOCAL_DATA / "research-2026-10-11" / "GEONAMES" / "cities1000.zip"
MODEL = "MEEUS"
FAJR_BEFORE = 100              # n of MinutesBeforeSunrise; the E18 Fajr values carry the season
ISHA_AFTER = 90                # MinutesAfter(90): the table's floor (plan 7.2)
POINTS = 36
SOURCE = "The IFI's perpetual Dublin table (islamicfoundation.ie timetable app)"
SOURCE_LINE = ("The Islamic Foundation of Ireland: its perpetual Dublin timetable and its Ramadan 2026 tables for Dublin "
               "and 32 other towns (islamicfoundation.ie)")
# Town file names as the IFI spells them -> (catalogue name, GeoNames id, Arabic name). Arabic: the place catalogue's
# label where it has one (scripts/places), else a transliteration.
TOWNS = {
    "athlone": ("Athlone", "3313472", "أثلون"), "ballina": ("Ballina", "2966778", "بالينا"),
    "ballinasloe": ("Ballinasloe", "2966753", "بالينسلو"), "ballyhaunis": ("Ballyhaunis", "2966566", "باليهونيس"),
    "carlow": ("Carlow", "2965768", "كارلو"), "castlebar": ("Castlebar", "2965654", "كاسلبار"),
    "cavan": ("Cavan", "2965535", "كافان"), "clonmel": ("Clonmel", None, "كلونميل"), "cork": ("Cork", "2965140", "كورك"),
    "donegal": ("Donegal", "2964752", "دونيغال"), "drogheda": ("Drogheda", "2964661", "دروهيدا"),
    "dublin": ("Dublin", "2964574", "دبلن"), "dundalk": ("Dundalk", "2964540", "دوندالك"), "ennis": ("Ennis", "2964405", "إينيس"),
    "galwau": ("Galway", "2964180", "غالواي"), "kildare": ("Kildare", "2963436", "كيلدير"),
    "kilkenny": ("Kilkenny", "2963398", "كيلكيني"), "letterkenny": ("Letterkenny", "2962961", "لتركني"),
    "limerick": ("Limerick", "2962943", "ليمريك"), "longford": ("Longford", "2962840", "لونغفورد"),
    "monaghan": ("Monaghan", "2962568", "موناهان"), "mullingar": ("Mullingar", None, "مولينغار"),
    "naas": ("Naas", "2962334", "ناس"), "navan": ("Navan", "2962308", "نافان"), "portlaoise": ("Portlaoise", "2962026", "بورتلاويس"),
    "roscommon": ("Roscommon", "2961732", "روسكومون"), "sligo": ("Sligo", "2961423", "سليجو"),
    "thurles": ("Thurles", "2961214", "ثورلز"), "tipperary": ("Tipperary", "2961192", "تيبيراري"),
    "tralee": ("Tralee", "2961123", "ترالي"), "tullamore": ("Tullamore", "2961086", "تولامور"),
    "waterford": ("Waterford", "2960992", "وترفورد"), "wexford": ("Wexford", "2960964", "ويكسفورد"),
}
LAT_STEP = 0.0025
BOX = 0.25
# Documented misprints (8.3), counted apart: Tralee's Isha of 14 March is printed 12 min after its neighbours' line.
QUIRKS = {"tralee": {(datetime.date(2026, 3, 14), "ISHA")}}


def log(text: str = "") -> None:
    print(text, flush=True)


# ---- data ---------------------------------------------------------------------------------------------------------

def table_rows(year: int = YEAR) -> dict[datetime.date, list[int]]:
    """date (of [year]) -> six UTC instants (epoch seconds) of the perpetual table, with the table's own clock."""
    t = json.loads((DATA / "timetable.json").read_text(encoding="utf-8"))
    rows = {}
    for month, days in t.items():
        for dd, times in days.items():
            if int(month) == 2 and int(dd) == 29 and ref.days_in_year(year) == 365:
                continue
            day = datetime.date(year, int(month), int(dd))
            summer = datetime.date(year, 4, 1) <= day <= datetime.date(year, 10, 31)
            midnight = (day - datetime.date(1970, 1, 1)).days * 86400
            rows[day] = [midnight + ((h - (1 if summer else 0)) * 60 + m) * 60 for h, m in times]
    return rows


def town_rows(name: str) -> dict[datetime.date, list[int]]:
    """A town's Ramadan rows as UTC instants (GMT, all before 29 March); afternoon columns printed in the 12-hour clock
    (Athlone's Maghrib) are read in the 24-hour clock."""
    raw = ramadan_pdf.rows(DATA / "ramadan-2026" / f"{name}_ramadan_timetable_2026.pdf")
    out = {}
    for day, times in raw.items():
        midnight = (day - datetime.date(1970, 1, 1)).days * 86400
        vals = []
        for k, hhmm in enumerate(times):
            h, m = (int(x) for x in hhmm.split(":"))
            if k >= 2 and h < 10:
                h += 12
            vals.append(midnight + (h * 60 + m) * 60)
        out[day] = vals
    return out


def geonames(ids: set[str], names: set[str]) -> tuple[dict[str, tuple[float, float]], dict[str, tuple[float, float]]]:
    """Points by GeoNames id, and by name (the most populous IE row whose name or alternate names hold it)."""
    by_id, by_name, pop = {}, {}, {}
    with zipfile.ZipFile(GEONAMES) as z, z.open("cities1000.txt") as fh:
        for line in fh:
            v = line.decode("utf-8").rstrip("\n").split("\t")
            if v[8] != "IE":
                continue
            if v[0] in ids:
                by_id[v[0]] = (float(v[4]), float(v[5]))
            alts = set(v[3].split(",")) | {v[1], v[2]}
            for n in names & alts:
                p = int(v[14] or 0)
                if p > pop.get(n, -1):
                    pop[n], by_name[n] = p, (float(v[4]), float(v[5]))
    return by_id, by_name


# ---- fast raw events (the reference's own primitives) ---------------------------------------------------------------

_CACHE: dict = {}


def raw(day: datetime.date, lat: float, lng: float) -> dict[str, float | None]:
    """Unrounded raw events in epoch seconds: sunrise, transit, factor-1 and factor-2 Asr, sunset."""
    key = (day, round(lat, 6), round(lng, 6))
    if key not in _CACHE:
        cyc = ref.Cycle(day)
        sun = ref.SunFunction(ref.SUN_MODELS[MODEL], "EVENT_INSTANT", cyc.jd0)
        t0 = ref.transit(cyc, sun, lng)
        decl = sun(cyc.jd0 + t0 / 24)[0]
        z = abs(lat - decl)
        out = {"SUNRISE": cyc.instant(ref.event(cyc, sun, lat, lng, -0.8333, -1, t0)), "DHUHR": cyc.instant(t0),
               "MAGHRIB": cyc.instant(ref.event(cyc, sun, lat, lng, -0.8333, 1, t0))}
        for k in (1, 2):
            out[f"ASR{k}"] = cyc.instant(ref.event(cyc, sun, lat, lng, ref.atand(1 / (k + ref.tand(z))), 1, t0))
        _CACHE[key] = out
    return _CACHE[key]


# ---- E18 by windows -------------------------------------------------------------------------------------------------

def knot_of(day: datetime.date) -> tuple[int, float]:
    n = day.timetuple().tm_yday
    p = POINTS * (n - 1) / ref.days_in_year(day.year)
    k = math.floor(p)
    return k, p - k


def season_value(v: list[float], day: datetime.date) -> float:
    k, w = knot_of(day)
    return v[k] * (1 - w) + v[(k + 1) % POINTS] * w


def best_cover(intervals, prefer):
    pts = []
    for a, b in intervals:
        if a < b:
            pts += [(a, 1), (b, -1)]
    pts.sort()
    best, cur, plat = 0, 0, []
    for i, (x, d) in enumerate(pts):
        cur += d
        nxt = pts[i + 1][0] if i + 1 < len(pts) else None
        if nxt is None or nxt <= x:
            continue
        if cur > best:
            best, plat = cur, [[x, nxt]]
        elif cur == best and best > 0:
            if abs(plat[-1][1] - x) < 1e-12:
                plat[-1][1] = nxt
            else:
                plat.append([x, nxt])
    if not plat:
        return 0, prefer
    for a, b in plat:
        if a <= prefer < b:
            return best, prefer
    a, b = min(plat, key=lambda p: min(abs(p[0] - prefer), abs(p[1] - prefer)))
    return best, (a + b) / 2


def fit_knots(windows: dict[datetime.date, tuple[float, float]], floor: float | None = None) -> list[float]:
    """36 knot values (minutes) covering the most day windows [lo, hi) of the E18 value; knots without a day in reach
    take the line between their nearest fitted neighbours."""
    reach = {k: [] for k in range(POINTS)}
    for day, (lo, hi) in windows.items():
        k, w = knot_of(day)
        reach[k].append((day, 1 - w))
        reach[(k + 1) % POINTS].append((day, w))
    v = [0.0] * POINTS
    # A knot is fitted only where some day lies nearer to it than to its neighbours; the others take the line
    # between their nearest fitted neighbours (a month-split leaves about three knots of each held-out month unfitted).
    fitted = [any(w >= 0.5 for _, w in reach[k]) for k in range(POINTS)]
    for k in range(POINTS):
        mids = [(windows[d][0] + windows[d][1]) / 2 for d, w in reach[k] if w >= 0.5]
        if mids:
            mids.sort()
            v[k] = mids[len(mids) // 2]
    _fill(v, fitted)
    for _ in range(30):
        moved = 0.0
        for k in range(POINTS):
            if not fitted[k]:
                continue
            ivs = []
            for day, w in reach[k]:
                if w < 1e-9:
                    continue
                lo, hi = windows[day]
                kk, ww = knot_of(day)
                other = v[(kk + 1) % POINTS] * ww if kk == k else v[kk] * (1 - ww)
                ivs.append(((lo - other) / w, (hi - other) / w))
            # within the best plateau, the value nearest the line through its neighbours (smoothness)
            smooth = (v[(k - 1) % POINTS] + v[(k + 1) % POINTS]) / 2
            _, val = best_cover(ivs, smooth)
            if floor is not None:
                val = max(val, floor)
            moved = max(moved, abs(val - v[k]))
            v[k] = val
        if moved < 1e-6:
            break
    _fill(v, fitted)
    return v


def _fill(v: list[float], fitted: list[bool]) -> None:
    idx = [k for k in range(POINTS) if fitted[k]]
    if not idx:
        return
    for k in range(POINTS):
        if fitted[k]:
            continue
        before = max((i for i in idx if i < k), default=idx[-1] - POINTS)
        after = min((i for i in idx if i > k), default=idx[0] + POINTS)
        a, b = v[before % POINTS], v[after % POINTS]
        v[k] = a + (b - a) * (k - before) / (after - before)


# ---- the stages ---------------------------------------------------------------------------------------------------

def window(t: float, pub: float) -> tuple[float, float]:
    """The E18 value (minutes) that rounds t + 60 v to pub (NEAREST)."""
    return (pub - t - 30.0) / 60, (pub - t + 30.0 - 1e-6) / 60


def fit(rows: dict[datetime.date, list[int]], lat: float, lng: float, asr_factor: int) -> dict[str, list[float]]:
    days = sorted(rows)
    knots = {}
    for e, k in (("SUNRISE", 1), ("DHUHR", 2), ("ASR", 3), ("MAGHRIB", 4)):
        name = f"ASR{asr_factor}" if e == "ASR" else e
        knots[e] = fit_knots({d: window(raw(d, lat, lng)[name], rows[d][k]) for d in days if raw(d, lat, lng)[name] is not None})
    knots["FAJR"] = fit_knots({d: window(raw(d, lat, lng)["SUNRISE"] - 60 * FAJR_BEFORE, rows[d][0]) for d in days})
    maghrib = {d: raw(d, lat, lng)["MAGHRIB"] + 60 * season_value(knots["MAGHRIB"], d) for d in days}
    knots["ISHA"] = fit_knots({d: window(maghrib[d] + 60 * ISHA_AFTER, rows[d][5]) for d in days}, floor=0.0)
    return {e: [round(x, 2) for x in knots[e]] for e in EVENTS}


def method_of(knots: dict[str, list[float]] | None, asr_factor: int = 1) -> dict:
    """ie_ifi's PrayerMethod JSON: the fitted E18 values, or (None) the preset before this fit."""
    if knots is None:
        return common.method_json(id=AUTHORITY, fajr={"type": "before_sunrise", "minutes": 100},
                                  isha={"type": "after", "minutes": 90, "from": "MAGHRIB", "ramadan": None},
                                  offsets={"DHUHR": 2}, evidence={"kind": "FROM_TABLES", "source": "preset"})
    return common.method_json(
        id=AUTHORITY, fajr={"type": "before_sunrise", "minutes": FAJR_BEFORE},
        isha={"type": "after", "minutes": ISHA_AFTER, "from": "MAGHRIB", "ramadan": None},
        seasonalOffsets={e: knots[e] for e in EVENTS}, asrShadow=asr_factor, sunModel=MODEL,
        evidence={"kind": "FROM_TABLES", "source": SOURCE})


def score(method: dict, rows, lat, lng, zone=None, months=None, days=None, quirks=frozenset()) -> common.Score:
    sc = common.Score()
    for d in sorted(rows):
        if months is not None and d.month not in months:
            continue
        if days is not None and d not in days:
            continue
        out = common.compute(common.case(method, lat, lng, TZ, d, zone=zone, country="IE"))
        for k, e in enumerate(EVENTS):
            if (d, e) in quirks:
                continue
            sc.add(e, d, out[e][1] // 60, rows[d][k] // 60)
    return sc


def merge(into: common.Score, sc: common.Score) -> None:
    for e in EVENTS:
        into.exact[e] += sc.exact[e]
        into.within1[e] += sc.within1[e]
        into.total[e] += sc.total[e]
        for m in range(12):
            into.months[e][m] += sc.months[e][m]
    into.worst = max(into.worst, sc.worst)
    into.misses += sc.misses


def shares(sc: common.Score) -> str:
    ex, w1 = sc.shares(), sc.within1_shares()
    return " ".join(f"{e[:4]} {100 * ex[e]:5.1f}/{100 * w1[e]:5.1f}" for e in EVENTS) + f"  max {sc.worst}"


def fit_town_point(rows, knots, lat0, lng0, asr_factor) -> tuple[float, float, int]:
    """Longitude from Dhuhr, then latitude from sunrise and Maghrib (and Asr), with Dublin's E18 values."""
    days = sorted(rows)
    ivs = []
    for d in days:
        t = raw(d, lat0, lng0)["DHUHR"] + 60 * season_value(knots["DHUHR"], d)
        lo, hi = window(t, rows[d][2])
        # the transit moves 4 min per degree west: t(lng) = t - 240 (lng - lng0); E18 value v in [lo, hi)
        # means t - 240 (lng - lng0) + 60 v rounds to pub with v = 0 -> lng in ((t - pub - 30)/240, (t - pub + 30)/240]
        ivs.append((lng0 + (t - rows[d][2] - 30) / 240, lng0 + (t - rows[d][2] + 30) / 240))
    _, lng = best_cover(ivs, lng0)
    best = None
    steps = int(BOX / LAT_STEP)
    for j in range(-steps, steps + 1):
        lat = lat0 + j * LAT_STEP
        n = 0
        for d in days:
            r = raw(d, lat, lng)
            for e, k, name in (("SUNRISE", 1, "SUNRISE"), ("MAGHRIB", 4, "MAGHRIB"), ("ASR", 3, f"ASR{asr_factor}")):
                t = r[name] + 60 * season_value(knots[e], d)
                n += ref.round_instant(t, "NEAREST") == rows[d][k]
        key = (n, -abs(j))
        if best is None or key > best[0]:
            best = (key, lat)
    return round(best[1], 5), round(lng, 5), best[0][0]


def zone_of(name: str, centre: tuple[float, float], computing: tuple[float, float] | None) -> dict:
    fields = {"authority": AUTHORITY}
    if computing is not None and common.point(*computing) != common.point(*centre):
        fields["morningPoint"] = common.point(*computing)
        fields["eveningPoint"] = common.point(*computing)
    return common.zone_json(name, centre[0], centre[1], **fields)


def main() -> int:
    started = time.time()
    by_year = {y: table_rows(y) for y in SCORED_YEARS}
    rows = {d: v for y in YEARS for d, v in by_year[y].items()}
    ids = {g for _, g, _ in TOWNS.values() if g}
    by_id, by_name = geonames(ids, {n for n, _, _ in TOWNS.values()})
    centres = {k: by_id.get(g) or by_name.get(n) for k, (n, g, _) in TOWNS.items()}
    missing = [k for k, v in centres.items() if v is None]
    if missing:
        raise SystemExit(f"no GeoNames point for {missing}")
    dublin = centres["dublin"]
    log(f"ie_ifi: the perpetual table ({len(by_year[2028])} days) fitted at {', '.join(map(str, YEARS))}, {len(rows)} days; "
        f"Dublin at {dublin}")

    # Asr factor: the factor whose Asr minute, at a constant offset, covers more days.
    counts = {}
    for f in (1, 2):
        ivs = [window(raw(d, *dublin)[f"ASR{f}"], rows[d][3]) for d in rows]
        counts[f] = best_cover(ivs, 0.0)[0]
    asr_factor = max(counts, key=counts.get)
    log(f"Asr factor: {asr_factor} (one offset covers {counts})")

    halves = {"odd": [m for m in range(1, 13) if m % 2], "even": [m for m in range(2, 13, 2)]}
    fitted = {}
    for name, ms in halves.items():
        fitted[name] = fit({d: v for d, v in rows.items() if d.month in ms}, *dublin, asr_factor)
    final = fit(rows, *dublin, asr_factor)
    log(f"knots fitted ({time.time() - started:.0f} s)")
    zone_dublin = zone_of("dublin", dublin, None)
    held = common.Score()
    for name, other in (("odd", "even"), ("even", "odd")):
        merge(held, score(method_of(fitted[other], asr_factor), rows, *dublin, zone=zone_dublin, months=halves[name]))
    log("Dublin held out (month-split), exact/within 1 (%):")
    log("  " + shares(held))
    misses = sorted(held.misses)
    if misses:
        log(f"  held-out misses beyond 1 min: {len(misses)}: " + ", ".join(f"{d} {e[:4]} {x:+d}" for e, d, x in misses[:30]))
    method = method_of(final, asr_factor)
    in_sc = score(method, rows, *dublin, zone=zone_dublin)
    log(f"final profile in-sample ({', '.join(map(str, YEARS))}): " + shares(in_sc))
    for y in SCORED_YEARS:
        log(f"  as the app shows {y}: " + shares(score(method, by_year[y], *dublin, zone=zone_dublin)))
    pre = score(method_of(None), by_year[YEAR], *dublin)
    log("the preset before this fit (sunrise - 100, Maghrib + 90, Dhuhr + 2): " + shares(pre))
    lag = [d for d in by_year[YEAR] if (d.month == 3 and d.day >= 29) or (d.month == 10 and d.day >= 25)]
    lag_sc = score(method, by_year[YEAR], *dublin, zone=zone_dublin, days=set(lag))
    log(f"the {len(lag)} days the table's clock lags the legal clock (scored in UTC): " + shares(lag_sc))
    for e in EVENTS:
        log(f"  E18 {e}: " + ", ".join(f"{x:.2f}" for x in final[e]))

    # The towns.
    log("towns (point fitted on the town's Ramadan days with Dublin's profile; held out by day-split):")
    zones = []
    town_held_all = common.Score()
    for key in sorted(TOWNS):
        name, gid, name_ar = TOWNS[key]
        trows = town_rows(key)
        centre = centres[key]
        quirks = QUIRKS.get(key, set())
        lat, lng, n = fit_town_point({d: v for d, v in trows.items() if (d, "ISHA") not in quirks}, final, *centre, asr_factor)
        days = sorted(trows)
        odd = {d for i, d in enumerate(days) if i % 2 == 0}
        even = set(days) - odd
        held_t = common.Score()
        for score_days, fit_days in ((odd, even), (even, odd)):
            la, ln, _ = fit_town_point({d: trows[d] for d in fit_days}, final, *centre, asr_factor)
            merge(held_t, score(method, trows, *centre, zone=zone_of(key, centre, (la, ln)), days=score_days, quirks=quirks))
        in_t = score(method, trows, *centre, zone=zone_of(key, centre, (lat, lng)), quirks=quirks)
        merge(town_held_all, held_t)
        dist = common_km(centre, (lat, lng))
        log(f"  {name:12s} centre {centre[0]:.4f},{centre[1]:.4f} fitted {lat:.4f},{lng:.4f} ({dist:4.1f} km): "
            f"held out {shares(held_t)}; in-sample {shares(in_t)}")
        bad = sorted(held_t.misses)
        if bad:
            log("    held-out misses: " + ", ".join(f"{d} {e[:4]} {x:+d}" for e, d, x in bad[:12]))
        stats = common.fit_stats(
            [common.sample(f"The IFI's Ramadan 2026 table for {name}", min(days), max(days), len(days), 1)],
            [common.sample(f"The IFI's Ramadan 2026 table for {name}, held out by day (odd and even days)", min(days), max(days), len(days), 1)],
            held_t, ramadan_days=len(days), quirk_days=len({d for d, _ in quirks}))
        z = {k2: v for k2, v in zone_of(key, centre, (lat, lng)).items() if k2 != "authority"}
        if key == "dublin":
            # Dublin's zone: the perpetual table's own record (month-split over the year), at the city centre.
            z.pop("morningPoint", None), z.pop("eveningPoint", None)
            stats = common.fit_stats(
                [common.sample(SOURCE, min(rows), max(rows), len(rows), 1)],
                [common.sample(SOURCE + ", held out by month", min(rows), max(rows), len(rows), 1, month_split=True)], held)
        z.update({"id": key if key != "galwau" else "galway", "nameAr": name_ar,
                  "namedPlaces": [{"centre": common.point(*centre), "catalogueIds": [], "areaKm": 0.0}], "fit": stats})
        zones.append(z)
    log("all towns, held out (day-split): " + shares(town_held_all))
    profile = common.fit_stats(
        [common.sample(SOURCE, min(rows), max(rows), len(rows), 1)],
        [common.sample(SOURCE + ", held out by month", min(rows), max(rows), len(rows), 1, month_split=True)], held)
    path = common.write_asset(AUTHORITY, AUTHORITY, "IE", "NEAREST", zones, fit=profile, source=SOURCE_LINE)
    log(f"wrote {path.relative_to(common.ROOT)} in {time.time() - started:.0f} s")
    return 0


def common_km(a: tuple[float, float], b: tuple[float, float]) -> float:
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * 6371.0088 * math.asin(min(1.0, math.sqrt(h)))


if __name__ == "__main__":
    sys.exit(main())
