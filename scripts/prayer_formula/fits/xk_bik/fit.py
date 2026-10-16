#!/usr/bin/env python3
"""
Fits `xk_bik`, the Presidency of the Islamic Community of Kosovo's Takvim (BIK; plan 7.5, 8.8, E18; milestone M3,
9.4), and writes its official-zone asset, android-app/app/src/main/assets/official-zones/xk_bik.json (schema 1).

Data, read at run time from test-data-local/official/xk/takvim/ and never copied (D13, A7):
- takvimi2026vaktet.pdf: the Takvim 2026's calendar part («Pjesa kalendarike», dituriaislame.com, fetched 2026-10-16),
  365 rows of Imsaku, Sabahu, sunrise, Dreka, Ikindia, Akshami, Jacia and day length, local clock time (summer time
  on its dates), read by takvim_pdf.py.
- takvimi2025.pdf: the 2025 edition, read only to show the table is perpetual (equal by date, summer time removed).

The Takvim (page 4) is computed at 42.5 N 21 E with a 1.5 deg (6 min) temkin; page 6 («Diferenca e vendeve») shifts
seven towns on all times: Sharr +2, Ferizaj, Gjilan, Prishtina, Podujevë and Vushtrri -1, Preševo -2. Its Fajr prayer
is «Sabahu», Imsaku + 20 (page 4), which the app shows as Fajr (A14). No angle, point or horizon reproduces the
table, so xk_bik computes the six plain events at 42.5 N 21 E (Fajr 18 deg, sunrise, transit, Asr factor 1, sunset,
Isha 17 deg) and adds a seasonal offset per time (E18: 36 values a year, interpolated linearly by the day of the year,
added before the single NEAREST rounding).

1. The table is perpetual, so like bosnia_iz's older columns it is fitted on the years the app computes, 2026-2029
   pooled, read by date (A8; the instants are the same whatever the summer time).
2. Per time, the 36 values by windows (9.4): each printed minute bounds the day's interpolated value to a half-open
   minute (and to a minute either side for "within 1"); each knot in turn takes the value covering the most weight,
   a minute within 1 weighing 100 and an exact one 1 (both tiers need 100 % within 1), the middle of that plateau, from
   a start at each knot's median window, until none moves. One evaluation for all six (E4): the sun at the event
   instant unless the sun at 0h UT covers clearly more windows (0.5 %).
3. Held out: alternate rows (the 36 values fitted on the table's odd days score its even days in every year, and
   the reverse), scored with common.compute (the reference engine) at the base zone.
4. The asset: the base zone «كوسوفو» at 42.5 N 21 E; the seven towns with their published shifts, each reaching as
   far as its own boundary (fits/town_extents.py: the farthest vertex of the town's OpenStreetMap boundary from its
   point, rounded up to 0.5 km; Podujevë, a point only in OpenStreetMap, the median of the other Kosovo towns); and
   the Preševo Valley's three towns, unshifted, each reaching over its own municipality (the radius that leaves the
   least area wrong: its municipality outside the disc plus the neighbouring Serbian municipalities inside it).

Usage: python scripts/prayer_formula/fits/xk_bik/fit.py
"""

from __future__ import annotations

import datetime
import math
import statistics
import sys
import time
from pathlib import Path
from zoneinfo import ZoneInfo

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))
import common  # noqa: E402
import takvim_pdf  # noqa: E402
import town_extents  # noqa: E402

ref = common.ref
EVENTS = common.EVENTS
AUTHORITY = "xk_bik"
TZ = "Europe/Belgrade"                 # Kosovo's clock (tzdata has no zone of its own)
POINT = (42.5, 21.0)                   # the Takvim's point, page 4
DATA = common.LOCAL_DATA / "official" / "xk" / "takvim"
PDF_2026, PDF_2025 = DATA / "takvimi2026vaktet.pdf", DATA / "takvimi2025.pdf"
APP_YEARS = (2026, 2027, 2028, 2029)
ANGLES = {"FAJR": 18.0, "ISHA": 17.0}  # EuropeanPresets.XK_BIK
COLUMN = {"FAJR": 1, "SUNRISE": 2, "DHUHR": 3, "ASR": 4, "MAGHRIB": 5, "ISHA": 6}   # Sabahu ... Jacia
POINTS = 36                            # PrayerMethod.SEASONAL_POINTS
EVALS = ("EVENT_INSTANT", "DAILY_0H_UT")
CLEARLY = 1.005
DECIMALS = 2                           # the 36 values in minutes, to 0.01
SOURCE = "BIK Takvim 2026 (perpetual: the 2025 edition is equal by date), read by date in 2026-2029"
SOURCE_LINE = ("The Presidency of the Islamic Community of Kosovo (BIK): its Takvim 2026, one table for Kosovo and the "
               "Preševo Valley at 42.5 N 21 E, and its seven towns' published shifts («Diferenca e vendeve»)")
# «Diferenca e vendeve» (page 6): minutes on all six times. Town points: OpenStreetMap's (town_extents.py), since
# GeoNames puts Dragash 4 km south of the town; Kotlin's Preševo Valley points (CountryMethodDefaults) for the Valley.
# Preševo's town zone sits on its Valley zone's point and comes first, so that in town the shifted zone wins the tie of
# LISTED_PLACES (the nearest zone in reach; minByOrNull keeps the first of equal distances).
PRESEVO = (42.3092, 21.6497)
TOWNS = {   # id: (town_extents key, shift, country, Arabic name, point or None for OpenStreetMap's)
    "sharr": ("dragash", 2, "XK", "شار (دراغاش)", None), "ferizaj": ("ferizaj", -1, "XK", "فيريزاي", None),
    "gjilan": ("gjilan", -1, "XK", "جيلان", None), "prishtina": ("prishtina", -1, "XK", "بريشتينا", None),
    "podujeve": ("podujeve", -1, "XK", "بودوييفو", None), "vushtrri": ("vushtrri", -1, "XK", "فوشتري", None),
    "presevo": ("presevo", -2, "RS", "بريشيفو", PRESEVO),
}
VALLEY = {  # id: (municipality key, Kotlin's point)
    "valley-presevo": ("presevo-municipality", PRESEVO),
    "valley-bujanovac": ("bujanovac-municipality", (42.4598, 21.7660)),
    "valley-medvedja": ("medvedja-municipality", (42.8433, 21.5853)),
}
NEIGHBOURS = ("vranje-city", "trgoviste-municipality", "lebane-municipality", "bojnik-municipality", "kursumlija-municipality")
VALLEY_NAME = "وادي بريشيفو"


def log(text: str = "") -> None:
    print(text, flush=True)


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")


# ---- the table ---------------------------------------------------------------------------------------------------

def standard_minutes(rows) -> dict[tuple[int, int], list[int]]:
    """(month, day) -> the six times' minutes after midnight in standard time (CET), summer time removed."""
    tz = ZoneInfo(TZ)
    out = {}
    for day, values in rows.items():
        dst = int(datetime.datetime(day.year, day.month, day.day, 12, tzinfo=tz).dst().total_seconds() // 60)
        out[(day.month, day.day)] = [values[COLUMN[e]][0] * 60 + values[COLUMN[e]][1] - dst for e in EVENTS]
    return out


def official_instant(day: datetime.date, minutes: int) -> int:
    """The printed minute's instant (epoch seconds) on a date: CET minutes, whatever the summer time."""
    return int((datetime.datetime(day.year, day.month, day.day) - datetime.datetime(1970, 1, 1)).total_seconds()) - 3600 + 60 * minutes


def dates(table, years):
    for year in years:
        for (m, d), mins in sorted(table.items()):
            yield datetime.date(year, m, d), (m, d), mins


# ---- raw events (the reference's own primitives) -----------------------------------------------------------------

_RAW: dict = {}


def raw(day: datetime.date, mode: str) -> list[float]:
    """The six unrounded instants before E18 at the Takvim's point."""
    key = (day, mode)
    if key not in _RAW:
        lat, lng = POINT
        cyc = ref.Cycle(day)
        sun = ref.SunFunction(ref.SUN_MODELS["MEEUS"], mode, cyc.jd0)
        t0 = ref.transit(cyc, sun, lng)
        decl = sun(cyc.jd0 + t0 / 24)[0]
        asr = ref.atand(1 / (1 + ref.tand(abs(lat - decl))))

        def ev(alt, side):
            return cyc.instant(ref.event(cyc, sun, lat, lng, alt, side, t0))

        _RAW[key] = [ev(-ANGLES["FAJR"], -1), ev(-0.8333, -1), cyc.instant(t0), ev(asr, 1), ev(-0.8333, 1),
                     ev(-ANGLES["ISHA"], 1)]
    return _RAW[key]


def knot(day: datetime.date) -> tuple[int, float]:
    """E18's place of a date: the knot before it and the weight of the next (prayer_engine_reference.seasonal_value)."""
    p = POINTS * (day.timetuple().tm_yday - 1) / ref.days_in_year(day.year)
    k = math.floor(p)
    return k, p - k


# ---- the 36 values by windows ------------------------------------------------------------------------------------

def windows(table, years, rows_kept, mode):
    """Per time: (knot, weight, lo, hi) for each kept row and year: the day's value in minutes lies in [lo, hi)."""
    out = {e: [] for e in EVENTS}
    for day, md, mins in dates(table, years):
        if md not in rows_kept:
            continue
        k, w = knot(day)
        r = raw(day, mode)
        for j, e in enumerate(EVENTS):
            pub = official_instant(day, mins[j])
            out[e].append((k, w, (pub - 30 - r[j]) / 60, (pub + 30 - r[j]) / 60))
    return out


def plateau(intervals, lo, hi, prefer):
    """(weight, value): the value in [lo, hi] covered by the most weight of half-open intervals (a, b, weight); the
    middle of the best plateau nearest [prefer]."""
    points = []
    for a, b, weight in intervals:
        a, b = max(a, lo), min(b, hi)
        if a < b:
            points += [(a, weight), (b, -weight)]
    points.sort()
    best, cur, plats = 0, 0, []
    for i, (x, d) in enumerate(points):
        cur += d
        nxt = points[i + 1][0] if i + 1 < len(points) else None
        if nxt is None or nxt <= x:
            continue
        if cur > best:
            best, plats = cur, [[x, nxt]]
        elif cur == best and best > 0:
            if abs(plats[-1][1] - x) < 1e-12:
                plats[-1][1] = nxt
            else:
                plats.append([x, nxt])
    if not plats:
        return 0, prefer
    a, b = min(plats, key=lambda p: 0 if p[0] <= prefer < p[1] else min(abs(p[0] - prefer), abs(p[1] - prefer)))
    return best, (a + b) / 2


def covered(values, wins, slack: float = 0.0) -> int:
    """The windows the values meet; with slack 1, the minutes they meet within 1."""
    n = 0
    for k, w, lo, hi in wins:
        s = values[k] * (1 - w) + values[(k + 1) % POINTS] * w
        n += lo - slack <= s < hi + slack
    return n


WITHIN1_WEIGHT = 100   # a minute within 1 outweighs any number of exact ones on the same knot (both tiers need 100 %)


def fit_values(wins) -> list[float]:
    """The 36 values: each knot's median window to start, then each knot set in turn to the value covering the most
    weight (within-1 windows first, exact windows next) until none moves."""
    by_knot = [[] for _ in range(POINTS)]
    for k, w, lo, hi in wins:
        by_knot[k if w < 0.5 else (k + 1) % POINTS].append((lo + hi) / 2)
    values = [statistics.median(v) if v else 0.0 for v in by_knot]
    touching = [[] for _ in range(POINTS)]
    for item in wins:
        k, w = item[0], item[1]
        touching[k].append((item, 0))
        touching[(k + 1) % POINTS].append((item, 1))
    for _ in range(60):
        moved = 0.0
        for j in range(POINTS):
            ivs = []
            for (k, w, lo, hi), side in touching[j]:
                c = (1 - w) if side == 0 else w
                if c < 1e-6:
                    continue
                other = values[(k + 1) % POINTS] * w if side == 0 else values[k] * (1 - w)
                ivs.append(((lo - other) / c, (hi - other) / c, 1))
                ivs.append(((lo - 1 - other) / c, (hi + 1 - other) / c, WITHIN1_WEIGHT))
            _, v = plateau(ivs, values[j] - 6, values[j] + 6, values[j])
            v = round(v, DECIMALS)
            moved = max(moved, abs(v - values[j]))
            values[j] = v
        if moved < 0.005:
            break
    return values


def fit(table, years, rows_kept, mode) -> tuple[dict[str, list[float]], int, int, int]:
    """(values per time, minutes met within 1, minutes met exactly, minutes), by the windows."""
    wins = windows(table, years, rows_kept, mode)
    values = {e: fit_values(wins[e]) for e in EVENTS}
    total = sum(len(v) for v in wins.values())
    return (values, sum(covered(values[e], wins[e], 1.0) for e in EVENTS), sum(covered(values[e], wins[e]) for e in EVENTS),
            total)


# ---- method, zones, scores ---------------------------------------------------------------------------------------

def method_of(values: dict[str, list[float]] | None, mode: str = "EVENT_INSTANT") -> dict:
    """xk_bik's PrayerMethod JSON: 18 / 17, Asr factor 1, NEAREST, and the fitted E18 values."""
    return common.method_json(
        id=AUTHORITY, fajr={"type": "angle", "degrees": ANGLES["FAJR"]}, isha={"type": "angle", "degrees": ANGLES["ISHA"]},
        seasonalOffsets={} if values is None else {e: values[e] for e in EVENTS},
        sunEvaluation={} if mode == "EVENT_INSTANT" else {e: mode for e in EVENTS},
        evidence={"kind": "FROM_TABLES", "source": "BIK's 2026 Takvim (fits/xk_bik)"})


def base_zone() -> dict:
    return common.zone_json("kosovo", POINT[0], POINT[1], authority=AUTHORITY)


def score(method, table, years, rows_kept=None) -> common.Score:
    sc = common.Score()
    zone = base_zone()
    for day, md, mins in dates(table, years):
        if rows_kept is not None and md not in rows_kept:
            continue
        out = common.compute(common.case(method, POINT[0], POINT[1], TZ, day, zone=zone, country="XK"))
        for j, e in enumerate(EVENTS):
            sc.add(e, day, out[e][1] // 60, official_instant(day, mins[j]) // 60)
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


# ---- radii -------------------------------------------------------------------------------------------------------

def spans(rings, lat: float) -> list[float]:
    """The longitudes where the rings' edges cross a parallel, sorted (even-odd: inside between pairs)."""
    xs = []
    for ring in rings:
        for i in range(len(ring)):
            (y1, x1), (y2, x2) = ring[i - 1], ring[i]
            if (y1 > lat) != (y2 > lat):
                xs.append(x1 + (lat - y1) * (x2 - x1) / (y2 - y1))
    return sorted(xs)


STEP_KM = 0.25


def grid_cells(towns, centre, reach_km: float):
    """The cells of a STEP_KM grid within reach_km of a town: (distance, the municipality index holding the cell or
    None, in a neighbouring Serbian municipality), each cell classified once by scanlines."""
    import bisect
    kx, ky = town_extents.KM_PER_DEG * math.cos(math.radians(centre[0])), town_extents.KM_PER_DEG
    valley = [towns[VALLEY[v][0]]["rings"] for v in VALLEY]
    neighbours = [towns[n]["rings"] for n in NEIGHBOURS]
    cells = []
    lat = centre[0] - reach_km / ky
    while lat <= centre[0] + reach_km / ky:
        v_spans = [spans(r, lat) for r in valley]
        n_spans = [spans(r, lat) for r in neighbours]
        lng = centre[1] - reach_km / kx
        while lng <= centre[1] + reach_km / kx:
            d = math.hypot((lng - centre[1]) * kx, (lat - centre[0]) * ky)
            if d <= reach_km:
                muni = next((i for i, s in enumerate(v_spans) if bisect.bisect(s, lng) % 2), None)
                cells.append((d, muni, muni is None and any(bisect.bisect(s, lng) % 2 for s in n_spans)))
            lng += STEP_KM / kx
        lat += STEP_KM / ky
    return cells


def disc_errors(cells, own: int, radius: float, own_area_km2: float) -> tuple[float, float]:
    """(the town's own municipality outside its disc, neighbouring Serbian municipalities inside it), km2."""
    cell = STEP_KM * STEP_KM
    inside_own = sum(1 for d, muni, _ in cells if d <= radius and muni == own) * cell
    extra = sum(1 for d, _, neighbour in cells if d <= radius and neighbour) * cell
    return max(own_area_km2 - inside_own, 0.0), extra


def valley_radii(towns) -> tuple[list[float], list[dict]]:
    """Per Valley town, the radius (0.5 km steps, from 5 km to its municipality's farthest point) that leaves the
    least area wrong: its own municipality outside the disc plus the neighbouring Serbian municipalities inside it (the
    other Valley municipalities and Kosovo or North Macedonia count as neither: they have their own defaults, 6.4);
    ties to the radius nearest 12 km, the plan's start."""
    radii, report = [], []
    for i, (vid, (muni, centre)) in enumerate(VALLEY.items()):
        e = town_extents.extent(centre, towns[muni]["rings"])
        cap = town_extents.round_up(e["circumscribed_km"])
        cells = grid_cells(towns, centre, cap + 0.5)
        best = None
        for r in [x / 2 for x in range(10, int(cap * 2) + 1)]:
            missed, extra = disc_errors(cells, i, r, e["area_km2"])
            key = (round(missed + extra, 6), abs(r - 12.0))
            if best is None or key < best[0]:
                best = (key, r, missed, extra)
        at12 = disc_errors(cells, i, 12.0, e["area_km2"])
        radii.append(best[1])
        report.append({"id": vid, "area": e["area_km2"], "equal_area": e["equal_area_km"], "far": e["circumscribed_km"],
                       "missed": best[2], "extra": best[3], "missed12": at12[0], "extra12": at12[1]})
    return radii, report


# ---- main --------------------------------------------------------------------------------------------------------

def main() -> int:
    started = time.time()
    rows26 = takvim_pdf.read_takvim(PDF_2026, 2026)
    rows25 = takvim_pdf.read_takvim(PDF_2025, 2025)
    table, table25 = standard_minutes(rows26), standard_minutes(rows25)
    assert len(table) == 365 and table == table25, "the Takvim is not equal by date in 2025 and 2026"
    sabah = sum(1 for r in rows26.values() if (r[1][0] * 60 + r[1][1]) - (r[0][0] * 60 + r[0][1]) == 20)
    length = sum(1 for r in rows26.values() if (r[5][0] * 60 + r[5][1]) - (r[2][0] * 60 + r[2][1]) == r[7][0] * 60 + r[7][1])
    log(f"xk_bik: BIK's Takvim 2026, {len(rows26)} rows; equal by date to the 2025 edition on all six times (perpetual); "
        f"Sabahu = Imsaku + 20 on {sabah} rows, day length = Akshami - sunrise on {length}")

    all_rows = set(table)
    ordered = sorted(all_rows)
    halves = {"odd": {md for i, md in enumerate(ordered) if i % 2 == 0}, "even": {md for i, md in enumerate(ordered) if i % 2 == 1}}

    # Stage 0: one evaluation for all six (E4).
    results = {}
    for mode in EVALS:
        values, n1, n0, total = fit(table, APP_YEARS, all_rows, mode)
        results[mode] = (values, WITHIN1_WEIGHT * n1 + n0)
        log(f"  {mode}: the fitted values meet {n1} of {total} printed minutes within 1 and {n0} exactly (2026-2029)")
    mode = "DAILY_0H_UT" if results["DAILY_0H_UT"][1] > results["EVENT_INSTANT"][1] * CLEARLY else "EVENT_INSTANT"
    final = results[mode][0]
    log(f"  evaluation: {mode}  ({time.time() - started:.0f} s)")

    # Held out: alternate rows.
    held = common.Score()
    per_year = {y: common.Score() for y in APP_YEARS}
    fitted = {}
    for name in ("odd", "even"):
        fitted[name], n1, n0, total = fit(table, APP_YEARS, halves[name], mode)
        log(f"  fitted on the {name} rows: {n1} of {total} within 1, {n0} exactly")
    for name, other in (("odd", "even"), ("even", "odd")):
        m = method_of(fitted[other], mode)
        for year in APP_YEARS:
            sc = score(m, table, (year,), halves[name])
            merge(held, sc)
            merge(per_year[year], sc)
    log(f"held out (alternate rows), 2026-2029, exact/within 1 (%):")
    log("  all years: " + shares(held))
    for year in APP_YEARS:
        log(f"  {year}: " + shares(per_year[year]))
    method = method_of(final, mode)
    log("in-sample (the shipped values), exact/within 1 (%):")
    for year in APP_YEARS:
        log(f"  {year}: " + shares(score(method, table, (year,))))
    log("without E18 (18 / 17 at 42.5 N 21 E, the preset before this fit), 2026: " + shares(score(method_of(None, mode), table, (2026,))))
    for e in EVENTS:
        log(f"  {e:8} E18 values (min): " + " ".join(f"{v:.2f}" for v in final[e]))
    log(f"  ({time.time() - started:.0f} s)")

    held_days = held.total["DHUHR"]
    stats = common.fit_stats(
        [common.sample(SOURCE, datetime.date(APP_YEARS[0], 1, 1), datetime.date(APP_YEARS[-1], 12, 31), 365, 1)],
        [common.sample(SOURCE + ", held out by alternate rows", datetime.date(APP_YEARS[0], 1, 1),
                       datetime.date(APP_YEARS[-1], 12, 31), held_days, 1, month_split=False)],
        held)

    # The asset.
    towns = town_extents.load()
    kosovo_extents = []
    for tid, (key, shift, cc, name, at) in TOWNS.items():
        t = towns[key]
        e = town_extents.extent(at or t["centre"], t["rings"])
        if cc == "XK" and e["circumscribed_km"] is not None:
            kosovo_extents.append(e["circumscribed_km"])
    estimate = statistics.median(kosovo_extents)
    zones = []
    z = {k: v for k, v in base_zone().items() if k != "authority"}
    z.update({"nameAr": "كوسوفو", "fit": stats})
    zones.append(z)
    log("towns («Diferenca e vendeve»), reach = the farthest vertex of the town's own boundary from its point:")
    for tid, (key, shift, cc, name, at) in TOWNS.items():
        t = towns[key]
        centre = at or t["centre"]
        e = town_extents.extent(centre, t["rings"])
        far = e["circumscribed_km"] if e["circumscribed_km"] is not None else estimate
        reach = town_extents.round_up(far)
        how = (f"{far:.2f} km ({t['osm']}, {e['area_km2']:.1f} km2)" if e["circumscribed_km"] is not None
               else f"no boundary in OpenStreetMap ({t['osm']}): the median of the other Kosovo towns, {estimate:.2f} km")
        log(f"  {tid}: {shift:+d} min, point {centre[0]:.5f} {centre[1]:.5f} ({town_extents.km(centre, t['centre']):.1f} km from "
            f"OpenStreetMap's), reach {reach:.1f} km ({how})")
        zone = {"id": tid, "nameAr": name, "point": common.point(*centre), "offsets": {ev: float(shift) for ev in EVENTS},
                "baseZoneId": "kosovo", "reachKm": reach,
                "namedPlaces": [{"centre": common.point(*centre), "catalogueIds": [], "areaKm": 0.0}], "fit": stats}
        if cc != "XK":
            zone["country"] = cc
        zones.append(zone)
    radii, report = valley_radii(towns)
    log("Preševo Valley, each town's disc against its own municipality (own area left outside + neighbouring Serbian "
        "municipalities' area inside, km2):")
    for (vid, (muni, centre)), r, rep in zip(VALLEY.items(), radii, report):
        log(f"  {vid}: reach {r:.1f} km: {rep['missed']:.1f} + {rep['extra']:.1f} km2 (12 km: {rep['missed12']:.1f} + "
            f"{rep['extra12']:.1f}); the municipality {rep['area']:.0f} km2, equal-area radius {rep['equal_area']:.1f} km, "
            f"farthest point {rep['far']:.1f} km from the town")
        zones.append({"id": vid, "nameAr": VALLEY_NAME, "point": common.point(*centre), "country": "RS", "baseZoneId": "kosovo",
                      "reachKm": r, "namedPlaces": [{"centre": common.point(*centre), "catalogueIds": [], "areaKm": 0.0}],
                      "fit": stats})
    path = common.write_asset(AUTHORITY, AUTHORITY, "XK", "LISTED_PLACES", zones, fit=stats, source=SOURCE_LINE)
    log(f"wrote {path.relative_to(common.ROOT)} ({len(zones)} zones) in {time.time() - started:.0f} s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
