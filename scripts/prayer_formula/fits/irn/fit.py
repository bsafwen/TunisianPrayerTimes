#!/usr/bin/env python3
"""
Fits `irn`, Islamic Council Norway's 2026 tables (plan 7.4, 7.5, 6.5 ADMIN_AREA; milestones M2 and M3), and writes
its official-zone asset, android-app/app/src/main/assets/official-zones/irn.json (schema 1): IRN's 358 Norwegian
entries (357 municipalities and Svalbard; its Strömstad entry, in Sweden, is left out), mapped ADMIN_AREA by the
place's municipality (`admin2`), else the nearest entry, each at its own published point (with a fitted E6 computing
point where it reproduces clearly more of the table, stage 3).

Data, read at run time from test-data-local and never copied (D13, A7), fetched by fetch.py from bonnetid.info's own
table requests: official/no/irn/location_t.json (the entries, with their published points) and
official/no/irn/2026/<location_iso>.json (each entry's 2026 table: the prayer columns fajr, fajr_endtime, duhr,
shadow_1x, shadow_2x, maghrib, isha, and the astronomical columns IRN prints beside them: istiwa_noon, shuruq_sunrise,
ghrub_sunset, fajr_sadiq (16 deg), shafaqal_ahmar_end_redlight (15 deg), shafaqal_abyadh_end_whitelight (18 deg)).

Stages (9.4):
1. Conventions, on the astronomical columns of every tenth southern entry at its published point: per column, the
   sun model, the sun's evaluation (E4: the engine's EVENT_INSTANT and DAILY_10H_UT, and the two IRN uses that the
   engine lacks, emulated by modes.py: DAILY_LOCAL_NOON and EVENT_DECLINATION) and the rounding.
2. Offsets: the prayer columns against the astronomical ones in whole minutes over every entry and day (sunrise - 2,
   Maghrib + 5, Dhuhr + 5 or + 10 and its two switch dates).
3. Points: each entry's published point against a point fitted by windows on the transit, sunrise and sunset columns
   (kept unless the fitted point reproduces more than 0.5 % more of the astronomical columns' minutes).
4. South of 63.56 deg (255 entries): each entry's switch days as its table shows them (Isha off its 15 deg column,
   Fajr off its 16 deg column), against the engine's rule (README H7) and the rule the tables follow.
5. From 63.56 deg (103 entries): each entry's season days read from its table with north.py's model.
6. The engine's E18 approximation of the Dhuhr step (36 points, linear between them).

Scores, with common.compute (the reference engine) for the current engine, and with the proposed rules for the
proposal: south of 63.56 deg common.compute with modes.py's emulated evaluations, a dated Dhuhr step and the held
Fajr's spring clock through 21 June (the Isha switch days the tables' own, which the proposed Isha rule gives at every
entry); from 63.56 deg north.py. Held out by a month-split (E18's Dhuhr points refitted on the odd months and scored on
the even ones, and the reverse); the other values are the tables' own per-entry days and offsets, read rather than
fitted from the scored minutes, so the proposal's scores are in-sample in that respect.

Usage: python scripts/prayer_formula/fits/irn/fit.py [--proposed]   (--proposed writes the proposal's records)
"""

from __future__ import annotations

import datetime
import json
import math
import sys
import time
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))
import common  # noqa: E402
import modes  # noqa: E402
import names_ar  # noqa: E402
import north  # noqa: E402

ref = common.ref
EVENTS = common.EVENTS
AUTHORITY = "irn"
TZ = "Europe/Oslo"
YEAR = 2026
DATA = common.LOCAL_DATA / "official" / "no" / "irn"
CATALOGUE = common.ROOT / "android-app" / "app" / "src" / "main" / "assets" / "places" / "europe-cities.tsv"
SOUTH_LIMIT = 63.56
COL = {"FAJR": "fajr", "SUNRISE": "fajr_endtime", "DHUHR": "duhr", "ASR": "shadow_1x", "MAGHRIB": "maghrib", "ISHA": "isha"}
CLEARLY = 1.005
SOURCE = "IRN's 2026 tables (bonnetid.info)"
SOURCE_LINE = ("Islamic Council Norway's 2026 prayer-time tables for Norway's 357 municipalities and Svalbard "
               "(bonnetid.info, IRN's national prayer-time project)")
CURRENT_EVAL = {"SUNRISE": "DAILY_10H_UT", "MAGHRIB": "DAILY_10H_UT", "ASR": "DAILY_10H_UT"}
PROPOSED_EVAL = {e: f"DAILY_LOCAL_NOON@{TZ}" for e in ("SUNRISE", "DHUHR", "ASR", "MAGHRIB")}
PROPOSED_EVAL.update({"FAJR": f"EVENT_DECLINATION@{TZ}", "ISHA": f"EVENT_DECLINATION@{TZ}"})


def log(text: str = "") -> None:
    print(text, flush=True)


# ---- data ---------------------------------------------------------------------------------------------------------

def entries() -> list[dict]:
    rows = json.loads((DATA / "location_t.json").read_text(encoding="utf-8"))
    return sorted((r for r in rows if r["location_iso"].startswith("NO")), key=lambda r: r["location_iso"])


def table(iso: str) -> list[dict]:
    rows = json.loads((DATA / str(YEAR) / f"{iso}.json").read_text(encoding="utf-8"))
    assert [r["date"] for r in rows] == [(datetime.date(YEAR, 1, 1) + datetime.timedelta(days=k)).isoformat() for k in range(len(rows))], iso
    return rows


def common_km(a: tuple[float, float], b: tuple[float, float]) -> float:
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * 6371.0088 * math.asin(min(1.0, math.sqrt(h)))


def published(day: datetime.date, hhmm: str | None) -> int | None:
    return None if hhmm is None else common.local_to_epoch(TZ, day, hhmm[:5])


def catalogue_labels() -> dict[str, str]:
    out = {}
    if CATALOGUE.exists():
        for line in CATALOGUE.read_text(encoding="utf-8").splitlines()[1:]:
            c = line.split("\t")
            if len(c) > 7 and c[1] == "NO" and c[7]:
                out.setdefault(c[5], c[7])
    return out


# ---- stage 1: conventions -----------------------------------------------------------------------------------------

BASE = (("istiwa_noon", None, 0), ("shuruq_sunrise", -0.8333, -1), ("ghrub_sunset", -0.8333, 1), ("fajr_sadiq", -16.0, -1),
        ("shafaqal_ahmar_end_redlight", -15.0, 1), ("shadow_1x", "asr", 1), ("shadow_2x", "asr", 2))


def base_instant(cyc, sun, lat, lng, alt, side):
    t0 = ref.transit(cyc, sun, lng)
    if alt is None:
        return cyc.instant(t0)
    if alt == "asr":
        z = abs(lat - sun(cyc.jd0 + t0 / 24)[0])
        if z >= 90:
            return None
        return cyc.instant(ref.event(cyc, sun, lat, lng, ref.atand(1 / (side + ref.tand(z))), 1, t0))
    return cyc.instant(ref.event(cyc, sun, lat, lng, alt, side, t0))


def sun_of(model_name: str, evaluation: str, cyc):
    model = ref.SUN_MODELS[model_name]
    if evaluation == "DAILY_LOCAL_NOON":
        return modes.local_noon_sun(model, cyc, TZ)
    if evaluation == "EVENT_DECLINATION":
        return modes.event_declination_sun(model, cyc, TZ)
    return ref.SunFunction(model, evaluation, cyc.jd0)


def conventions(sample: list[dict], vsop_sample: list[dict]) -> dict:
    """Exact minutes per column, model, evaluation and rounding: MEEUS with the four evaluations on [sample], VSOP87
    with IRN's two on [vsop_sample] (VSOP87 at the event instant is slow in pure Python)."""
    counts = defaultdict(Counter)
    plan = [(loc, "MEEUS", ("EVENT_INSTANT", "DAILY_10H_UT", "DAILY_LOCAL_NOON", "EVENT_DECLINATION")) for loc in sample]
    plan += [(loc, "VSOP87", ("DAILY_LOCAL_NOON", "EVENT_DECLINATION")) for loc in vsop_sample]
    for loc, model, evs in plan:
        lat, lng = float(loc["lat_n_s"]), float(loc["long_e_w"])
        for r in table(loc["location_iso"]):
            day = datetime.date.fromisoformat(r["date"])
            cyc = ref.Cycle(day)
            for ev in evs:
                sun = sun_of(model, ev, cyc)
                for col, alt, side in BASE:
                    p = published(day, r[col])
                    if p is None:
                        continue
                    t = base_instant(cyc, sun, lat, lng, alt, side)
                    if t is None:
                        continue
                    if t - p > 43200:
                        p += 86400
                    key = (col, model, ev)
                    counts[key]["n"] += 1
                    for mode in ("NEAREST", "UP", "DOWN"):
                        counts[key][mode] += ref.round_instant(t, mode) == p
    best = {}
    for col, _, _ in BASE:
        cands = [(c[mode] / c["n"], model, ev, mode, c[mode], c["n"]) for (cc, model, ev), c in counts.items() if cc == col
                 for mode in ("NEAREST", "UP", "DOWN")]
        best[col] = max(cands)[1:]
    return {"counts": counts, "best": best}


# ---- stage 2: offsets ---------------------------------------------------------------------------------------------

def offsets(locs: list[dict], tables: dict) -> dict:
    by_day = defaultdict(Counter)
    rise, magh = Counter(), Counter()
    for loc in locs:
        south = float(loc["lat_n_s"]) < SOUTH_LIMIT
        for r in tables[loc["location_iso"]]:
            day = datetime.date.fromisoformat(r["date"])
            m = lambda c: published(day, r[c]) // 60
            by_day[day][m("duhr") - m("istiwa_noon")] += 1
            if south:
                rise[m("fajr_endtime") - m("shuruq_sunrise")] += 1
                magh[m("maghrib") - m("ghrub_sunset")] += 1
    days10 = sorted(d for d, c in by_day.items() if c.most_common(1)[0][0] == 10)
    uniform = all(len(c) == 1 for c in by_day.values())
    return {"dhuhr10": (days10[0], days10[-1]), "dhuhr_uniform": uniform, "sunrise": rise.most_common(3),
            "maghrib": magh.most_common(3)}


# ---- stage 3: points ----------------------------------------------------------------------------------------------

def _cover(intervals, prefer):
    """(count, value): the most half-open intervals covering one value; [prefer] where it lies in a best plateau,
    else the middle of the best plateau nearest to it."""
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
    a, b = min(plat, key=lambda q: min(abs(q[0] - prefer), abs(q[1] - prefer)))
    return best, (a + b) / 2


POINT_COLUMNS = (("istiwa_noon", None, 0, "DAILY_LOCAL_NOON"), ("shuruq_sunrise", -0.8333, -1, "DAILY_LOCAL_NOON"),
                 ("ghrub_sunset", -0.8333, 1, "DAILY_LOCAL_NOON"), ("fajr_sadiq", -16.0, -1, "EVENT_DECLINATION"),
                 ("shafaqal_ahmar_end_redlight", -15.0, 1, "EVENT_DECLINATION"))


def exact_count(rows: list[dict], lat: float, lng: float) -> int:
    """Minutes of the astronomical columns reproduced at a point (IRN's evaluations, rounded down)."""
    n = 0
    for r in rows:
        day = datetime.date.fromisoformat(r["date"])
        cyc = ref.Cycle(day)
        for col, alt, side, ev in POINT_COLUMNS:
            p = published(day, r[col])
            if p is None:
                continue
            t = base_instant(cyc, sun_of("MEEUS", ev, cyc), lat, lng, alt, side)
            if t is None:
                continue
            if t - p > 43200:
                p += 86400
            n += ref.round_instant(t, "DOWN") == p
    return n


def point_check(loc: dict, rows: list[dict]) -> dict:
    """The published point against a point fitted by windows (longitude from the transit, then latitude from sunrise
    and sunset); the fitted one is kept as the zone's computing point (E6) only where it reproduces more than 0.5 %
    more of the astronomical columns."""
    lat0, lng0 = float(loc["lat_n_s"]), float(loc["long_e_w"])
    lng_ivs = []
    for r in rows:
        day = datetime.date.fromisoformat(r["date"])
        cyc = ref.Cycle(day)
        t = cyc.instant(ref.transit(cyc, modes.local_noon_sun(ref.SUN_MODELS["MEEUS"], cyc, TZ), lng0))
        p = published(day, r["istiwa_noon"])
        lng_ivs.append((lng0 + (t - p - 59.999) / 240, lng0 + (t - p + 0.001) / 240))
    _, lng = _cover(lng_ivs, lng0)
    lat_ivs = []
    for r in rows:
        day = datetime.date.fromisoformat(r["date"])
        cyc = ref.Cycle(day)
        sun = modes.local_noon_sun(ref.SUN_MODELS["MEEUS"], cyc, TZ)
        t0 = ref.transit(cyc, sun, lng)
        for col, side in (("shuruq_sunrise", -1), ("ghrub_sunset", 1)):
            h = ref.event(cyc, sun, lat0, lng, -0.8333, side, t0)
            h2 = ref.event(cyc, sun, lat0 + 0.01, lng, -0.8333, side, t0)
            pp = published(day, r[col])
            if h is None or h2 is None or pp is None:
                continue
            a, b = cyc.instant(h), cyc.instant(h2)
            slope = (b - a) / 0.01
            if abs(slope) > 1e-6:
                x, y = lat0 + (pp - 0.001 - a) / slope, lat0 + (pp + 59.999 - a) / slope
                lat_ivs.append((min(x, y), max(x, y)))
    _, lat = _cover(lat_ivs, lat0)
    lat, lng = round(lat, 5), round(lng, 5)
    at_pub = exact_count(rows, lat0, lng0)
    at_fit = exact_count(rows, lat, lng) if (lat, lng) != (lat0, lng0) else at_pub
    return {"published": at_pub, "fitted": at_fit, "point": (lat, lng), "keep": at_fit <= at_pub * CLEARLY}


# ---- stage 4: southern switch days -------------------------------------------------------------------------------

def table_days(rows: list[dict]) -> tuple:
    """(Isha first, Isha last, Fajr first, Fajr last), common day numbers of the days off 15 deg / 16 deg."""
    out = []
    for off in ([r["shafaqal_ahmar_end_redlight"] is None or r["isha"] != r["shafaqal_ahmar_end_redlight"] for r in rows],
                [r["fajr_sadiq"] is None or r["fajr"] != r["fajr_sadiq"] for r in rows]):
        days = [k + 1 for k, x in enumerate(off) if x]
        if not days:
            out += [None, None]
            continue
        assert len(days) == days[-1] - days[0] + 1, "a season with a gap"
        out += [ref.common_day_number(datetime.date(YEAR, 1, 1) + datetime.timedelta(days=days[0] - 1)),
                ref.common_day_number(datetime.date(YEAR, 1, 1) + datetime.timedelta(days=days[-1] - 1))]
    return tuple(out)


def engine_rule_days(lat: float, lng: float, method: dict, computing: tuple | None = None) -> tuple:
    zone = common.zone_json("x", lat, lng, authority=AUTHORITY)
    if computing is not None:
        zone["morningPoint"] = zone["eveningPoint"] = common.point(*computing)
    eng = ref.CaseEngine(common.case(method, lat, lng, TZ, datetime.date(YEAR, 6, 1), zone=zone, country="NO"))
    site = ref.zone_site(zone)
    day = datetime.date(YEAR, 6, 1)
    i, f = eng.switch_days(site, day, "ISHA"), eng.switch_days(site, day, "FAJR")
    return (i[0], i[1]) if i else (None, None), (f[0], f[1]) if f else (None, None)


def tables_isha_rule(lat: float, lng: float, method: dict, computing: tuple | None = None) -> tuple:
    """The Isha rule the tables follow: the day after the first day (32..172) whose 15 deg Isha is absent or at or
    after 20:00 UTC, to the day before the last such day (172..365), each side on its own."""
    zone = common.zone_json("x", lat, lng, authority=AUTHORITY)
    if computing is not None:
        zone["morningPoint"] = zone["eveningPoint"] = common.point(*computing)
    eng = ref.CaseEngine(common.case(method, lat, lng, TZ, datetime.date(YEAR, 6, 1), zone=zone, country="NO"))
    site = ref.zone_site(zone)

    def late(n):
        a = eng.raw_day(site, ref.date_of_day_number(n, YEAR)).angle_event("ISHA", 15.0)
        return a is None or ref.utc_hours(a) >= 20
    first = next((n for n in range(32, 173) if late(n)), None)
    last = next((n for n in range(365, 171, -1) if late(n)), None)
    return (None if first is None else first + 1, None if last is None else last - 1)


def fajr_windows(lat: float, lng: float, method: dict, first: int, last: int, computing: tuple | None = None) -> tuple:
    """The 16 deg Fajr's distance to sunrise (min) on the days around the table's Fajr switch days."""
    zone = common.zone_json("x", lat, lng, authority=AUTHORITY)
    if computing is not None:
        zone["morningPoint"] = zone["eveningPoint"] = common.point(*computing)
    eng = ref.CaseEngine(common.case(method, lat, lng, TZ, datetime.date(YEAR, 6, 1), zone=zone, country="NO"))
    site = ref.zone_site(zone)

    def gap(n):
        d = ref.date_of_day_number(n, YEAR)
        f = eng.angle_on(site, d, "FAJR")
        return None if f is None else (eng.day_steps(site, d).sunrise - f) / 60
    return gap(first - 1), gap(first), gap(last), gap(last + 1)


# ---- stage 6: the E18 Dhuhr points for the current engine ------------------------------------------------------------

def dhuhr_knots(locs: list[dict], tables: dict, months=None) -> list[float]:
    """36 E18 values: 0 outside the step and 5 inside, the two values on each side of each switch date chosen to
    reproduce the most Dhuhr minutes (the engine's transit at the event instant, + 5, rounded down)."""
    first, last = dates_of_step(tables)
    k_spring = math.floor(36 * (first.timetuple().tm_yday - 1) / 365)
    k_autumn = math.floor(36 * (last.timetuple().tm_yday - 1) / 365)
    base = [5.0 if k_spring + 1 <= k <= k_autumn else 0.0 for k in range(36)]
    samples = []
    for loc in locs[::4]:
        lng = float(loc["long_e_w"])
        for r in tables[loc["location_iso"]]:
            day = datetime.date.fromisoformat(r["date"])
            if months is not None and day.month not in months:
                continue
            k, _ = knot(day)
            if not any(abs(k - kk) <= 1 for kk in (k_spring, k_autumn)):
                continue
            cyc = ref.Cycle(day)
            t = cyc.instant(ref.transit(cyc, ref.SunFunction(ref.SUN_MODELS["MEEUS"], "EVENT_INSTANT", cyc.jd0), lng))
            samples.append((day, t + 300, published(day, r["duhr"])))
    for kk in (k_spring, k_autumn):
        # v[kk] and v[kk + 1] reach the days of knot intervals kk - 1, kk and kk + 1
        pre = []
        for day, t, p in samples:
            k, w = knot(day)
            if abs(k - kk) <= 1:
                pre.append((k - kk, w, (p - t) / 60, (p + 60 - t) / 60))

        def count(a, b):
            n = 0
            for side, w, lo, hi in pre:
                if side < 0:
                    s = base[kk - 1] * (1 - w) + a * w
                elif side == 0:
                    s = a * (1 - w) + b * w
                else:
                    s = b * (1 - w) + base[(kk + 2) % 36] * w
                n += lo <= s < hi
            return n
        best = None
        for grid in ([x / 2 for x in range(-4, 17)], None):
            if grid is None:
                a0, b0 = best[1], best[2]
                cand = [(a0 + i / 10, b0 + j / 10) for i in range(-5, 6) for j in range(-5, 6)]
            else:
                cand = [(a, b) for a in grid for b in grid]
            for a, b in cand:
                key = (count(a, b), -abs(a - base[kk]) - abs(b - base[kk + 1]))
                if best is None or key > best[0]:
                    best = (key, round(a, 2), round(b, 2))
        base[kk], base[kk + 1] = best[1], best[2]
    return base


def knot(day: datetime.date) -> tuple[int, float]:
    p = 36 * (day.timetuple().tm_yday - 1) / ref.days_in_year(day.year)
    k = math.floor(p)
    return k, p - k


def season(v: list[float], day: datetime.date) -> float:
    k, w = knot(day)
    return v[k] * (1 - w) + v[(k + 1) % 36] * w


_STEP: tuple | None = None


def dates_of_step(tables: dict) -> tuple[datetime.date, datetime.date]:
    global _STEP
    if _STEP is None:
        any_rows = next(iter(tables.values()))
        tens = [datetime.date.fromisoformat(r["date"]) for r in any_rows
                if published(datetime.date.fromisoformat(r["date"]), r["duhr"]) // 60
                - published(datetime.date.fromisoformat(r["date"]), r["istiwa_noon"]) // 60 == 10]
        _STEP = (tens[0], tens[-1])
    return _STEP


# ---- methods and zones --------------------------------------------------------------------------------------------

def method_current(dhuhr: list[float]) -> dict:
    return common.method_json(
        id=AUTHORITY, fajr={"type": "angle", "degrees": 16.0}, isha={"type": "angle", "degrees": 15.0},
        offsets={"SUNRISE": -2, "DHUHR": 5, "MAGHRIB": 5}, seasonalOffsets={"DHUHR": [round(x, 2) for x in dhuhr]},
        rounding={e: "DOWN" for e in EVENTS}, sunEvaluation=dict(CURRENT_EVAL), sunModel="MEEUS",
        highLatitude={"type": "irn"}, polar="IRN_MECCA_CYCLE", evidence={"kind": "FROM_TABLES", "source": SOURCE})


def method_proposed(day: datetime.date | None = None, step=None) -> dict:
    """The proposal: the emulated evaluations and the Dhuhr step as a dated window (no E18)."""
    dhuhr = 5
    if day is not None and step is not None and step[0] <= day.replace(year=step[0].year) <= step[1]:
        dhuhr = 10
    return common.method_json(
        id=AUTHORITY, fajr={"type": "angle", "degrees": 16.0}, isha={"type": "angle", "degrees": 15.0},
        offsets={"SUNRISE": -2, "DHUHR": dhuhr, "MAGHRIB": 5}, rounding={e: "DOWN" for e in EVENTS},
        sunEvaluation=dict(PROPOSED_EVAL), sunModel="MEEUS", highLatitude={"type": "irn"}, polar="IRN_MECCA_CYCLE",
        evidence={"kind": "FROM_TABLES", "source": SOURCE})


def zone_for_scoring(loc: dict, days: tuple | None, computing: tuple | None = None) -> dict:
    lat, lng = float(loc["lat_n_s"]), float(loc["long_e_w"])
    z = common.zone_json(loc["kommune_no"], lat, lng, authority=AUTHORITY)
    if computing is not None:
        z["morningPoint"] = common.point(*computing)
        z["eveningPoint"] = common.point(*computing)
    if days is not None and days[0] is not None:
        z["switchDays"] = {"ISHA": {"first": days[0], "last": days[1]}, "FAJR": {"first": days[2], "last": days[3]}}
    return z


# ---- scoring ------------------------------------------------------------------------------------------------------

def minute_diff(computed: int, pub: int) -> int:
    return min(((computed - pub - k * 86400) // 60 for k in (-1, 0, 1)), key=abs)


def score_engine(loc: dict, rows: list[dict], zone: dict, method_of_day, months=None, quirks=frozenset()) -> tuple:
    lat, lng = float(loc["lat_n_s"]), float(loc["long_e_w"])
    sc, q = common.Score(), 0
    for r in rows:
        day = datetime.date.fromisoformat(r["date"])
        if months is not None and day.month not in months:
            continue
        out = common.compute(common.case(method_of_day(day), lat, lng, TZ, day, zone=zone, country="NO"))
        for e in EVENTS:
            if (day, e) in quirks:
                q += 1
                continue
            d = minute_diff(out[e][1], published(day, r[COL[e]]))
            sc.add(e, day, d, 0)
    return sc, q


def north_entry(loc: dict, computing: tuple | None) -> north.Entry:
    lat, lng = computing if computing is not None else (float(loc["lat_n_s"]), float(loc["long_e_w"]))
    return north.Entry(lat, lng, YEAR)


def score_north_model(loc: dict, rows: list[dict], seasons: dict, months=None, quirks=frozenset(), computing=None) -> tuple:
    entry = north_entry(loc, computing)
    sval = loc["kommune_no"] == "2100"
    sc, q = common.Score(), 0
    for n, r in enumerate(rows, start=1):
        day = datetime.date.fromisoformat(r["date"])
        if months is not None and day.month not in months:
            continue
        p = north.minutes(entry, n, seasons, sval)
        for e in EVENTS:
            if (day, e) in quirks:
                q += 1
                continue
            sc.add(e, day, minute_diff(p[e], published(day, r[COL[e]])), 0)
    return sc, q


def north_quirks(loc: dict, rows: list[dict], seasons: dict, computing=None) -> set:
    """Single days the table jumps to Mecca's day in mid-season (all four of Fajr, sunrise, Maghrib and Isha over
    30 min off the model while both neighbours fit): counted apart."""
    entry = north_entry(loc, computing)
    sval = loc["kommune_no"] == "2100"
    bad = []
    for n, r in enumerate(rows, start=1):
        day = datetime.date.fromisoformat(r["date"])
        p = north.minutes(entry, n, seasons, sval)
        far = sum(abs(minute_diff(p[e], published(day, r[COL[e]]))) > 30 for e in ("FAJR", "SUNRISE", "MAGHRIB", "ISHA"))
        bad.append(far == 4)
    out = set()
    for i, b in enumerate(bad):
        if b and not (i > 0 and bad[i - 1]) and not (i + 1 < len(bad) and bad[i + 1]):
            day = datetime.date(YEAR, 1, 1) + datetime.timedelta(days=i)
            out |= {(day, e) for e in EVENTS}
    return out


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


def passes(sc: common.Score) -> bool:
    ex, w1 = sc.shares(), sc.within1_shares()
    return all(ex[e] >= 0.97 and w1[e] >= 1.0 and min(sc.months[e]) >= 7 for e in EVENTS)


# ---- main -----------------------------------------------------------------------------------------------------------

def main(argv: list[str]) -> int:
    proposed_records = "--proposed" in argv
    started = time.time()
    locs = entries()
    tables = {loc["location_iso"]: table(loc["location_iso"]) for loc in locs}
    south = [loc for loc in locs if float(loc["lat_n_s"]) < SOUTH_LIMIT]
    northern = [loc for loc in locs if float(loc["lat_n_s"]) >= SOUTH_LIMIT]
    log(f"irn: {len(locs)} entries ({len(south)} south of {SOUTH_LIMIT} deg, {len(northern)} from it), {len(tables[locs[0]['location_iso']])} days each")

    # Stage 1.
    conv = conventions(south[::10], south[5::130])
    log(f"stage 1, conventions on {len(south[::10])} southern entries (VSOP87 on {len(south[5::130])}), exact minutes:")
    for col, (model, ev, mode, n, total) in conv["best"].items():
        others = ", ".join(f"{m2} {e2} {100 * c['DOWN'] / c['n']:.1f}" for (cc, m2, e2), c in sorted(conv["counts"].items())
                           if cc == col and not (m2 == model and e2 == ev))
        log(f"  {col:30s} {model} {ev} {mode}: {n}/{total} ({100 * n / total:.1f} %); rounded down otherwise: {others}")

    # Stage 2.
    off = offsets(locs, tables)
    step = off["dhuhr10"]
    log(f"stage 2, offsets: Dhuhr = transit + 10 from {step[0]} to {step[1]}, + 5 otherwise "
        f"({'the same days at every entry' if off['dhuhr_uniform'] else 'NOT uniform'}); south: sunrise column - shuruq "
        f"{off['sunrise']}, Maghrib - ghrub {off['maghrib']}")

    # Stage 3.
    pts = {loc["location_iso"]: point_check(loc, tables[loc["location_iso"]]) for loc in locs}
    kept = sum(p["keep"] for p in pts.values())
    pub = sum(p["published"] for p in pts.values())
    fit_ = sum(p["published"] if p["keep"] else p["fitted"] for p in pts.values())
    log(f"stage 3, points: the published point kept at {kept} of {len(locs)} entries, a fitted computing point (E6) at "
        f"{len(locs) - kept}; the astronomical columns reproduced: {pub} minutes at the published points, {fit_} with "
        f"the fitted ones")
    moved = sorted(((common_km((float(l["lat_n_s"]), float(l["long_e_w"])), pts[l["location_iso"]]["point"]), l["location_iso"])
                    for l in locs if not pts[l["location_iso"]]["keep"]), reverse=True)
    if moved:
        log(f"  fitted points lie {moved[-1][0]:.2f} to {moved[0][0]:.2f} km from the published ones (farthest: {moved[0][1]})")
    computing = {iso: (None if p["keep"] else p["point"]) for iso, p in pts.items()}

    # Stage 6 (needed by the current engine's method in stage 4).
    knots = dhuhr_knots(locs, tables)
    log("stage 6, E18 Dhuhr values for the current engine: " + ", ".join(f"{x:g}" for x in knots))
    m_cur = method_current(knots)
    modes.install()

    # Stage 4.
    days, hits = {}, Counter()
    fajr_win = []
    store = {}
    for loc in south:
        lat, lng = float(loc["lat_n_s"]), float(loc["long_e_w"])
        td = table_days(tables[loc["location_iso"]])
        days[loc["location_iso"]] = td
        cp = computing[loc["location_iso"]]
        cur_i, cur_f = engine_rule_days(lat, lng, m_cur, cp)
        new_i, new_f = engine_rule_days(lat, lng, method_proposed(), cp)
        tab_i = tables_isha_rule(lat, lng, method_proposed(), cp)
        hits["current ISHA"] += cur_i == td[:2]
        hits["current FAJR"] += cur_f == td[2:]
        hits["proposed-modes ISHA (current rule)"] += new_i == td[:2]
        hits["proposed-modes FAJR (current rule)"] += new_f == td[2:]
        hits["tables' Isha rule"] += tab_i == td[:2]
        need = {}
        if cur_i != td[:2] or tab_i != td[:2]:
            need["ISHA"] = {"first": td[0], "last": td[1]}
        if cur_f != td[2:] or new_f != td[2:]:
            need["FAJR"] = {"first": td[2], "last": td[3]}
        store[loc["location_iso"]] = need
        fajr_win.append(fajr_windows(lat, lng, method_proposed(), td[2], td[3], cp))
    log(f"stage 4, southern switch days against the {len(south)} tables:")
    for k, v in hits.items():
        log(f"  {k}: {v}/{len(south)}")
    sums_i = Counter(days[l["location_iso"]][0] + days[l["location_iso"]][1] for l in south)
    sums_f = Counter(days[l["location_iso"]][2] + days[l["location_iso"]][3] for l in south)
    log(f"  table sums first + last: Isha {sorted(sums_i.items())}, Fajr {sorted(sums_f.items())}")
    lo = max(w[0] for w in fajr_win if w[0] is not None)
    hi = min(w[1] for w in fajr_win if w[1] is not None)
    best_thr = max(((sum(1 for w in fajr_win if (w[0] is None or w[0] < g / 20) and g / 20 <= w[1]), g / 20) for g in range(2960, 3200)))
    log(f"  Fajr spring: the 16 deg Fajr's distance to sunrise on the day before the switch reaches {lo:.2f} min and on "
        f"the switch day falls to {hi:.2f}, so no single threshold fits every table (the best, {best_thr[1]:.2f} min, "
        f"fits {best_thr[0]}); autumn: back at 16 deg at {min(w[3] for w in fajr_win if w[3]):.1f} to "
        f"{max(w[3] for w in fajr_win if w[3]):.1f} min")
    n_isha = sum(1 for s in store.values() if "ISHA" in s)
    n_fajr = sum(1 for s in store.values() if "FAJR" in s)
    log(f"  stored switchDays: ISHA at {n_isha} entries, FAJR at {n_fajr}")

    # Stage 5.
    seasons, quirks = {}, {}
    for loc in northern:
        cp = computing[loc["location_iso"]]
        entry = north_entry(loc, cp)
        rows = tables[loc["location_iso"]]
        s = north.read_seasons(entry, rows, loc["kommune_no"] == "2100")
        seasons[loc["location_iso"]] = s
        quirks[loc["location_iso"]] = north_quirks(loc, rows, s, cp)
    kinds = Counter((s.get("autumn") or ("NONE",))[0] + "/" + (s.get("spring") or ("NONE",))[0] for s in seasons.values() if s)
    log(f"stage 5, northern seasons read from {len(northern)} tables: winter halves (autumn/spring) {dict(kinds)}; "
        f"single-day Mecca jumps counted apart at " + ", ".join(f"{iso} {min(d for d, _ in q)}" for iso, q in quirks.items() if q))
    log(f"  ({time.time() - started:.0f} s)")

    # Scores.
    halves = {"odd": [m for m in range(1, 13) if m % 2], "even": [m for m in range(2, 13, 2)]}
    knots_half = {name: dhuhr_knots(locs, tables, ms) for name, ms in halves.items()}
    records = {}
    profile = {"current": common.Score(), "proposed": common.Score(), "current_in": common.Score(), "proposed_in": common.Score()}
    groups = defaultdict(lambda: {"current": common.Score(), "proposed": common.Score()})
    quirk_days = 0
    for loc in locs:
        iso = loc["location_iso"]
        rows = tables[iso]
        is_south = float(loc["lat_n_s"]) < SOUTH_LIMIT
        zone = zone_for_scoring(loc, days.get(iso), computing[iso])
        q = quirks.get(iso, set())
        quirk_days += len({d for d, _ in q})
        held_cur = common.Score()
        modes.SOLSTICE_FIX = False
        for name, other in (("odd", "even"), ("even", "odd")):
            mc = method_current(knots_half[other])
            sc, _ = score_engine(loc, rows, zone, lambda d, mc=mc: mc, halves[name], q)
            merge(held_cur, sc)
        if is_south:
            modes.SOLSTICE_FIX = True
            held_new, _ = score_engine(loc, rows, zone, lambda d: method_proposed(d, step), None, q)
            modes.SOLSTICE_FIX = False
        else:
            held_new, _ = score_north_model(loc, rows, seasons[iso], None, q, computing[iso])
        merge(profile["current"], held_cur)
        merge(profile["proposed"], held_new)
        band = "south" if is_south else ("Svalbard" if loc["kommune_no"] == "2100" else "63.56-66.33" if float(loc["lat_n_s"]) < 66.33 else "from 66.33")
        merge(groups[band]["current"], held_cur)
        merge(groups[band]["proposed"], held_new)
        records[iso] = {"current": held_cur, "proposed": held_new, "quirks": len({d for d, _ in q})}
    log("held out (month-split; the proposal's rules have no value fitted from minutes), exact/within 1 (%):")
    log("  current engine:  " + shares(profile["current"]))
    log("  proposed rules:  " + shares(profile["proposed"]))
    for band, g in groups.items():
        log(f"  {band:12s} current  {shares(g['current'])}")
        log(f"  {band:12s} proposed {shares(g['proposed'])}")
    tier_cur = sum(passes(r["current"]) for r in records.values())
    tier_new = sum(passes(r["proposed"]) for r in records.values())
    log(f"zones passing tier A (97 % exact and all within 1 minute per time, every month): current engine {tier_cur} of "
        f"{len(records)}, proposed rules {tier_new} of {len(records)}")
    names = {l["location_iso"]: l["location_name"] for l in locs}
    for iso, r in records.items():
        sc = r["proposed"]
        if not passes(sc):
            ex, w1 = sc.shares(), sc.within1_shares()
            short = ", ".join(f"{e[:4]} {100 * ex[e]:.1f}/{100 * w1[e]:.1f}" for e in EVENTS if ex[e] < 0.97 or w1[e] < 1.0)
            log(f"  proposal below the gate: {iso} {names[iso]}: {short}; beyond 1 min: "
                + ", ".join(f"{d} {e[:4]} {x:+d}" for e, d, x in sorted(sc.misses)[:4]))
    log(f"  ({time.time() - started:.0f} s)")

    # The asset.
    labels = catalogue_labels()
    key = "proposed" if proposed_records else "current"
    arabic = {l["location_iso"]: names_ar.arabic(l["location_name"], l["kommune_name"], labels) for l in locs}
    seen = Counter(arabic.values())
    for l in locs:
        if seen[arabic[l["location_iso"]]] > 1:
            arabic[l["location_iso"]] += f" ({names_ar.county(l['fylke_name'])})"
    zones = []
    for loc in locs:
        iso = loc["location_iso"]
        kno = loc["kommune_no"]
        lat, lng = float(loc["lat_n_s"]), float(loc["long_e_w"])
        stats = common.fit_stats(
            [common.sample(f"IRN's 2026 table for {loc['kommune_name']}", datetime.date(YEAR, 1, 1), datetime.date(YEAR, 12, 31), len(tables[iso]), 1)],
            [common.sample(f"IRN's 2026 table for {loc['kommune_name']}, held out by month", datetime.date(YEAR, 1, 1),
                           datetime.date(YEAR, 12, 31), len(tables[iso]), 1, month_split=True)],
            records[iso][key], quirk_days=records[iso]["quirks"])
        switch = dict(store.get(iso, {}))
        s = seasons.get(iso)
        if s is not None:
            if kno == "2100":
                switch["MAGHRIB"] = {"first": 1, "last": 365}
            else:
                a, b = s["summer"]
                switch["MAGHRIB"] = {"first": a + 1, "last": b - 1}
                au, sp = s.get("autumn"), s.get("spring")
                if (au and au[0] == "MECCA") or (sp and sp[0] == "MECCA"):
                    switch["SUNRISE"] = {"first": au[1] + 1 if au and au[0] == "MECCA" else 0,
                                         "last": sp[2] - 1 if sp and sp[0] == "MECCA" else 0}
                if (au and au[0] == "FLOOR") or (sp and sp[0] == "FLOOR"):
                    switch["DHUHR"] = {"first": au[1] if au and au[0] == "FLOOR" else 0,
                                       "last": sp[2] if sp and sp[0] == "FLOOR" else 0}
        admin2 = [kno] + (["2111"] if kno == "2100" else [])
        cp = computing[iso]
        zones.append({"id": kno, "nameAr": arabic[iso],
                      "point": common.point(lat, lng),
                      "morningPoint": None if cp is None else common.point(*cp),
                      "eveningPoint": None if cp is None else common.point(*cp),
                      "admin2": admin2, "switchDays": switch,
                      "namedPlaces": [{"centre": common.point(lat, lng), "catalogueIds": [], "areaKm": 0.0}], "fit": stats})
    prof = common.fit_stats(
        [common.sample(SOURCE, datetime.date(YEAR, 1, 1), datetime.date(YEAR, 12, 31), len(tables[locs[0]["location_iso"]]), len(locs))],
        [common.sample(SOURCE + ", held out by month", datetime.date(YEAR, 1, 1), datetime.date(YEAR, 12, 31),
                       len(tables[locs[0]["location_iso"]]), len(locs), month_split=True)],
        profile[key], quirk_days=quirk_days)
    path = common.write_asset(AUTHORITY, AUTHORITY, "NO", "ADMIN_AREA", zones, fit=prof, source=SOURCE_LINE)
    log(f"wrote {path.relative_to(common.ROOT)} ({key} records) in {time.time() - started:.0f} s")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
