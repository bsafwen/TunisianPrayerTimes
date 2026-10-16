#!/usr/bin/env python3
"""
Fits `ps_dar_al_ifta`, the Palestinian Dar al-Ifta's perpetual Al-Aqsa table (plan 8.1, 8.2, 8.7, 8.8, Q1;
milestone AM10, 9.4), and writes its official-zone asset, android-app/app/src/main/assets/official-zones/ps_dar_al_ifta.json.

The table «توقيت الصلاة الدهري حسب توقيت المسجد الأقصى المبارك» (Jerusalem 1433/2012, a reprint of an older table,
still served on darifta.ps) gives 366 rows keyed by month and day in winter time (UTC+2) all year, and, on its page 2,
the whole-minute differences of the places outside Al-Aqsa. It is perpetual: the fit reads each row at a reference year
(E5, harness only) and every held-out score is computed for the actual year, as the app shows it (A8).

Data, read at run time from test-data-local and never copied (D13, A7):
- research-2026-10-08/PS/ps/dahri2012.txt: the table's 366 rows (parsed from ssalah2012.pdf pages 4-27; an independent
  parse is identical), 'MM-DD fajr sunrise dhuhr asr maghrib isha', 24 h, UTC+2.
- research-2026-10-08/PS/pdf2012/p02.png (read by eye): the differences; the 16 Palestinian places are written below.

Stages (9.4), with a fast special-purpose model of the reference engine, each by windows (a published minute bounds
the unrounded time to a half-open minute; here a widened window of one minute either side counts the times within 1
minute, the tier-B gate, and the value covered by the most of those, then by the most exact windows, wins):
1. Dhuhr: the longitude (rounding DOWN, no minutes: the table truncates the transit, 8.2) for each sun model and
   reference year; years 1980-2016, MEEUS and VSOP87.
2. The point's latitude, the year and the sun model by the joint score of all six times, each time's own parameters
   fitted at every candidate: Fajr's and Isha's angle and rounding; sunrise's and Maghrib's horizon (E2 Fixed),
   whole-minute offset (-4..0 and 0..6) and rounding; Asr's sun (E13) and rounding. Maghrib offsets above 6 min
   gain a few minutes only with a horizon raised above the standard one, a seasonal bend rather than a rule.
3. Each time's parameters at the chosen point: the published 18 deg, and the standard horizon, where the best
   windows hold them.
Held out: the table split by month (fitted on the odd months, scored on the even ones, and the reverse), each held-out
row computed for 2026 (the 29 February row for 2028) through common.compute, the reference engine.

Usage: python scripts/prayer_formula/fits/ps_dar_al_ifta/fit.py [--workers N]
"""

from __future__ import annotations

import argparse
import datetime
import math
import os
import sys
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import common  # noqa: E402

ref = common.ref
EVENTS = common.EVENTS
AUTHORITY = "ps_dar_al_ifta"
TZ = "Asia/Hebron"                      # the West Bank's clock; the table itself is UTC+2 all year
TABLE_OFFSET_H = 2.0
D2R = math.pi / 180
TABLE = common.LOCAL_DATA / "research-2026-10-08" / "PS" / "ps" / "dahri2012.txt"
AQSA = (31.7761, 35.2358)               # Al-Aqsa Mosque, the table's place
YEARS = tuple(range(1980, 2017))
MODELS = ("MEEUS", "VSOP87")
ACTUAL_YEAR = 2026                      # held-out rows are scored as the app shows them in 2026 (A8)
ROUNDINGS = {"NEAREST": (-0.5, 0.5), "UP": (-1.0, 0.0), "DOWN": (0.0, 1.0)}
OFFSETS = {"SUNRISE": (-4, -3, -2, -1, 0), "MAGHRIB": (0, 1, 2, 3, 4, 5, 6), "DHUHR": (0, 1)}
SOURCE_LINE = ("The Palestinian Dar al-Ifta's perpetual Al-Aqsa table (darifta.ps, ssalah2012.pdf, winter time) with the "
               "published differences of its 16 Palestinian places, computed for the actual year (A8)")
# Dar al-Ifta's places in Palestine and their differences from Al-Aqsa on all six times (table page 2): 0 for
# Jerusalem, Ramallah, Bethlehem, Jenin and Nablus; -1 Jericho; +1 Hebron, Idhna, Dura, Beit Awwa, Tulkarm; +1.5
# Qalqilya, applied as +2 (owner rule, 8.8: the only mosque of its group in the dataset calls +2); +3 Gaza; +4 Rafah,
# Khan Younis, Deir al-Balah. Its 13 places in Israel are not used (8.8). Town points: GeoNames (geonames.org, read
# 2026-10-15) where an id is given, else approximate (M5 matches the catalogue rows).
PLACES = (
    ("quds", "القدس", 0, 7303419, 31.7834, 35.2339),
    ("ramallah", "رام الله", 0, 282239, 31.8996, 35.2042),
    ("bethlehem", "بيت لحم", 0, 284315, 31.7049, 35.2038),
    ("jenin", "جنين", 0, 283506, 32.4594, 35.3009),
    ("nablus", "نابلس", 0, 282615, 32.2211, 35.2544),
    ("jericho", "أريحا", -1, 284899, 31.8667, 35.4500),
    ("hebron", "الخليل", 1, 285066, 31.5293, 35.0938),
    ("idhna", "إذنا", 1, None, 31.5586, 34.9758),
    ("dura", "دورا", 1, 284011, 31.5078, 35.0293),
    ("beit_awwa", "بيت عوا", 1, None, 31.5086, 34.9461),
    ("tulkarm", "طولكرم", 1, 281577, 32.3116, 35.0269),
    ("qalqilya", "قلقيلية", 2, 282457, 32.1897, 34.9706),
    ("gaza", "غزة", 3, 281133, 31.5016, 34.4667),
    ("rafah", "رفح", 4, 281102, 31.2972, 34.2436),
    ("khan_younis", "خان يونس", 4, 281124, 31.3402, 34.3063),
    ("deir_al_balah", "دير البلح", 4, 281141, 31.4183, 34.3493),
)
BASE = "quds"


# ------------------------------------------------------------------------------------------------------------
# Data

def load_table() -> list[tuple[tuple[int, int], dict[str, int]]]:
    out = []
    with open(TABLE, encoding="utf-8") as fh:
        for line in fh:
            if not line.strip():
                continue
            md, *clocks = line.split()
            m, d = (int(x) for x in md.split("-"))
            out.append(((m, d), {e: int(c[:2]) * 60 + int(c[3:]) for e, c in zip(EVENTS, clocks)}))
    if len(out) != 366:
        raise SystemExit(f"{TABLE.name}: {len(out)} rows, not 366")
    return out


def leap(y: int) -> bool:
    return y % 4 == 0 and (y % 100 != 0 or y % 400 == 0)


def at_year(md: tuple[int, int], y: int) -> datetime.date | None:
    """The row's date in year y (the reference year, E5); None for 29 February in a common year."""
    if md == (2, 29) and not leap(y):
        return None
    return datetime.date(y, *md)


def as_shown(md: tuple[int, int]) -> datetime.date:
    """The date the app computes a held-out row for: in ACTUAL_YEAR, the 29 February row in the next leap year."""
    y = ACTUAL_YEAR
    while md == (2, 29) and not leap(y):
        y += 1
    return datetime.date(y, *md)


# ------------------------------------------------------------------------------------------------------------
# Fast model of the reference engine (README S1-S7): the sun of a date as a quadratic through 0h, 12h and 24h UT

class Sun:
    __slots__ = ("a", "b", "c")

    def __init__(self, model: str, day: datetime.date):
        jd0 = ref.julian_day0(day)
        f = ref.SUN_MODELS[model]
        y0, y1, y2 = f(jd0), f(jd0 + 0.5), f(jd0 + 1.0)
        self.a = y0
        self.b = tuple(-3 * u + 4 * v - w for u, v, w in zip(y0, y1, y2))
        self.c = tuple(2 * u - 4 * v + 2 * w for u, v, w in zip(y0, y1, y2))

    def at(self, hours: float):
        x = hours / 24
        a, b, c = self.a, self.b, self.c
        return a[0] + x * (b[0] + x * c[0]), a[1] + x * (b[1] + x * c[1]), a[2] + x * (b[2] + x * c[2])


_SUNS: dict = {}


def sun_of(model: str, day: datetime.date) -> Sun:
    key = (model, day)
    s = _SUNS.get(key)
    if s is None:
        s = _SUNS[key] = Sun(model, day)
    return s


def transit(sun: Sun, lng: float) -> float:
    mean_noon = 12 - lng / 15
    t = mean_noon
    for _ in range(10):
        nxt = mean_noon - sun.at(t)[1] / 60
        if abs(nxt - t) < 0.1 / 3600:
            return nxt
        t = nxt
    return t


def event(sun: Sun, lat: float, lng: float, alt: float, side: int, t0: float) -> float:
    mean_noon = 12 - lng / 15
    sl, cl, sa = math.sin(lat * D2R), math.cos(lat * D2R), math.sin(alt * D2R)
    t = t0
    for _ in range(10):
        decl, eot, _ = sun.at(t)
        c = (sa - sl * math.sin(decl * D2R)) / (cl * math.cos(decl * D2R))
        nxt = mean_noon - eot / 60 + side * math.acos(max(-1.0, min(1.0, c))) / D2R / 15
        if abs(nxt - t) < 0.1 / 3600:
            return nxt
        t = nxt
    return t


def asr_altitude(lat: float, decl_noon: float, apparent: bool) -> float:
    """E13 at standard air (1010 hPa, 10 C: the zone stores none) or the geometric sun."""
    zenith = abs(lat - decl_noon)
    if apparent:
        h0 = 90 - zenith
        h0a = h0 + ref.bennett(h0, 1010.0, 10.0)
        aa = math.atan(1 / (1 + math.tan((90 - h0a) * D2R))) / D2R
        return aa - ref.bennett(aa, 1010.0, 10.0)
    return math.atan(1 / (1 + math.tan(zenith * D2R))) / D2R


def altitude_at(sun: Sun, lat: float, lng: float, th: float) -> float:
    decl, eot, _ = sun.at(th)
    mean_noon = 12 - lng / 15
    s = (math.sin(lat * D2R) * math.sin(decl * D2R)
         + math.cos(lat * D2R) * math.cos(decl * D2R) * math.cos((th - mean_noon + eot / 60) * 15 * D2R))
    return math.asin(max(-1.0, min(1.0, s))) / D2R


# ------------------------------------------------------------------------------------------------------------
# Windows

def best_value(pairs):
    """pairs: per published minute, (exact window, within-1 window), each [lo, hi). The value with the most within-1
    windows, then the most exact ones: (within-1 count, exact count, the middle of that best stretch)."""
    marks = []
    for (lo, hi), (lo1, hi1) in pairs:
        marks += [(lo1, 0, 1), (hi1, 0, -1), (lo, 1, 1), (hi, 1, -1)]
    marks.sort()
    w1 = ex = 0
    best, stretches = (-1, -1), []
    for i, (x, kind, s) in enumerate(marks):
        if kind == 0:
            w1 += s
        else:
            ex += s
        nxt = marks[i + 1][0] if i + 1 < len(marks) else x
        if nxt <= x:
            continue
        key = (w1, ex)
        if key > best:
            best, stretches = key, [(x, nxt)]
        elif key == best:
            if stretches and stretches[-1][1] == x:
                stretches[-1] = (stretches[-1][0], nxt)
            else:
                stretches.append((x, nxt))
    a, b = stretches[(len(stretches) - 1) // 2]
    return best[0], best[1], (a + b) / 2, stretches


def within(value: float, stretches) -> bool:
    return any(a <= value < b for a, b in stretches)


MARKS = {"FAJR": (-4, 4), "ISHA": (-4, 4), "SUNRISE": (-4, 12), "MAGHRIB": (-16, 4)}   # half-minutes around the minute


def altitude_marks(rows, model, lat, lng, e):
    """The sun's altitude at every half-minute mark around each published minute of e (MARKS), at the point."""
    j0, j1 = MARKS[e]
    out = []
    for day, t in rows:
        sun = sun_of(model, day)
        out.append([altitude_at(sun, lat, lng, (t[e] + j / 2) / 60 - TABLE_OFFSET_H) for j in range(j0, j1 + 1)])
    return out


def mark_pairs(marks, e, rnd, k=0, depression=False):
    """Per day, (exact window, within-1 window) of the altitude (or depression) that puts e, k minutes added, on its
    published minute under rounding rnd, and within 1 minute of it, from the precomputed half-minute marks."""
    j0 = MARKS[e][0]
    lo_b, hi_b = ROUNDINGS[rnd]
    idx = [int(round(2 * (x - k))) - j0 for x in (lo_b, hi_b, lo_b - 1, hi_b + 1)]
    if min(idx) < 0 or max(idx) > MARKS[e][1] - j0:
        raise ValueError(f"{e}: marks {MARKS[e]} do not cover offset {k} under {rnd}")
    out = []
    for alts in marks:
        a = [alts[i] for i in idx]
        if depression:
            out.append(((-max(a[0], a[1]), -min(a[0], a[1])), (-max(a[2], a[3]), -min(a[2], a[3]))))
        else:
            out.append(((min(a[0], a[1]), max(a[0], a[1])), (min(a[2], a[3]), max(a[2], a[3]))))
    return out


def rounded(u: float, rnd: str) -> int:
    if rnd == "DOWN":
        return math.floor(u + 1e-6)
    if rnd == "UP":
        return math.ceil(u - 1e-6)
    return math.floor(u + 0.5)


def dhuhr_lng(rows, model) -> tuple[float, int]:
    """Stage 1: the longitude whose truncated transit (DOWN, no minutes) gives the most Dhuhr minutes."""
    pairs = []
    for day, t in rows:
        eot = sun_of(model, day).at(10.0)[1]
        base = (12 + TABLE_OFFSET_H) * 60 - eot            # Dhuhr = base - 4 lng; DOWN: t <= u < t + 1
        pairs.append((((base - t["DHUHR"] - 1) / 4, (base - t["DHUHR"]) / 4),
                      ((base - t["DHUHR"] - 2) / 4, (base - t["DHUHR"] + 1) / 4)))
    w1, ex, lng, _ = best_value(pairs)
    return lng, ex


def fit_events(rows, model, lat, lng) -> dict:
    """Each time's own parameters at a point: {event: (within-1, exact, params)}."""
    out = {}
    for e in ("FAJR", "ISHA"):
        marks = altitude_marks(rows, model, lat, lng, e)
        best = None
        for rnd in ROUNDINGS:
            w1, ex, value, st = best_value(mark_pairs(marks, e, rnd, 0, depression=True))
            if within(18.0, st):
                value = 18.0
            cand = (w1, ex, {"degrees": round(value, 3), "rounding": rnd})
            if best is None or cand[:2] > best[:2]:
                best = cand
        out[e] = best
    for e in ("SUNRISE", "MAGHRIB"):
        marks = altitude_marks(rows, model, lat, lng, e)
        best = None
        for rnd in ROUNDINGS:
            for k in OFFSETS[e]:
                w1, ex, value, st = best_value(mark_pairs(marks, e, rnd, k))
                if within(-0.8333, st):
                    value = -0.8333
                cand = (w1, ex, {"altitude": round(value, 4), "offset": k, "rounding": rnd})
                if best is None or cand[:2] > best[:2]:
                    best = cand
        out[e] = best
    transits = [(transit(sun_of(model, day), lng), day, t) for day, t in rows]
    best = None
    for rnd in ROUNDINGS:
        for k in OFFSETS["DHUHR"]:
            w1 = ex = 0
            for tr, day, t in transits:
                d = rounded((tr + TABLE_OFFSET_H) * 60 + k, rnd) - t["DHUHR"]
                ex += d == 0
                w1 += abs(d) <= 1
            cand = (w1, ex, {"offset": k, "rounding": rnd})
            if best is None or cand[:2] > best[:2]:
                best = cand
    out["DHUHR"] = best
    best = None
    for apparent in (False, True):
        asr = []
        for tr, day, t in transits:
            sun = sun_of(model, day)
            asr.append(((event(sun, lat, lng, asr_altitude(lat, sun.at(tr)[0], apparent), 1, tr) + TABLE_OFFSET_H) * 60, t["ASR"]))
        for rnd in ROUNDINGS:
            w1 = ex = 0
            for u, pub in asr:
                d = rounded(u, rnd) - pub
                ex += d == 0
                w1 += abs(d) <= 1
            cand = (w1, ex, {"apparent": apparent, "rounding": rnd})
            if best is None or cand[:2] > best[:2]:
                best = cand
    out["ASR"] = best
    return out


def total(fe: dict) -> tuple[int, int]:
    return sum(v[0] for v in fe.values()), sum(v[1] for v in fe.values())


def frange(a: float, b: float, step: float) -> list[float]:
    n = int(round((b - a) / step))
    return [round(a + i * step, 6) for i in range(n + 1)]


def rows_at(table, year, months=None):
    out = []
    for md, t in table:
        if months is not None and md[0] not in months:
            continue
        day = at_year(md, year)
        if day is not None:
            out.append((day, t))
    return out


def _scan_job(args):
    table, model, year, months, lats = args
    rows = rows_at(table, year, months)
    lng, nd = dhuhr_lng(rows, model)
    out = []
    for lat in lats:
        fe = fit_events(rows, model, lat, lng)
        out.append((total(fe), model, year, lat, lng, fe))
    return out


def fit(table, months, workers, say=print, years=YEARS, models=MODELS) -> dict:
    """Stages 1-3 on the rows of [months] (None: all): the point, the year, the sun model, each time's parameters."""
    coarse_lats = frange(31.66, 31.96, 0.02)
    jobs = [(table, m, y, months, coarse_lats) for m in models for y in years]
    results = []
    if workers > 1:
        with ProcessPoolExecutor(max_workers=workers) as ex:
            for part in ex.map(_scan_job, jobs):
                results += part
    else:
        for j in jobs:
            results += _scan_job(j)
    results.sort(key=lambda r: r[0], reverse=True)
    (score, model, year, lat0, lng, _) = results[0]
    by_year = {}
    for r in results:
        k = (r[1], r[2])
        by_year[k] = max(by_year.get(k, (-1, -1)), r[0])
    rows = rows_at(table, year, months)
    fine = []
    for lat in frange(lat0 - 0.02, lat0 + 0.02, 0.002):
        fe = fit_events(rows, model, lat, lng)
        fine.append((total(fe), lat, fe))
    top = max(f[0] for f in fine)
    lats = [f[1] for f in fine if f[0] == top]
    lat = lats[(len(lats) - 1) // 2]
    fe = next(f[2] for f in fine if f[1] == lat)
    return {"model": model, "year": year, "lat": lat, "lng": lng, "events": fe, "score": top, "by_year": by_year,
            "lat_span": (lats[0], lats[-1])}


# ------------------------------------------------------------------------------------------------------------
# The reference engine

def method_of(f: dict) -> dict:
    ev = f["events"]
    offsets = {}
    for e in ("SUNRISE", "MAGHRIB", "DHUHR"):
        if ev[e][2]["offset"]:
            offsets[e] = ev[e][2]["offset"]
    return common.method_json(
        id=AUTHORITY, fajr={"type": "angle", "degrees": ev["FAJR"][2]["degrees"]},
        isha={"type": "angle", "degrees": ev["ISHA"][2]["degrees"]}, offsets=offsets,
        rounding={"FAJR": ev["FAJR"][2]["rounding"], "SUNRISE": ev["SUNRISE"][2]["rounding"], "DHUHR": ev["DHUHR"][2]["rounding"],
                  "ASR": ev["ASR"][2]["rounding"], "MAGHRIB": ev["MAGHRIB"][2]["rounding"], "ISHA": ev["ISHA"][2]["rounding"]},
        horizon={"sunrise": {"type": "fixed", "altitudeDeg": ev["SUNRISE"][2]["altitude"]},
                 "sunset": {"type": "fixed", "altitudeDeg": ev["MAGHRIB"][2]["altitude"]}},
        asrSun="APPARENT" if ev["ASR"][2]["apparent"] else "GEOMETRIC", sunModel=f["model"],
        highLatitude={"type": "angle_based"}, evidence={"kind": "FROM_TABLES", "source": "fit"})


def zones_of(f: dict) -> dict[str, dict]:
    out = {}
    for pid, _, off, _, lat, lng in PLACES:
        if pid == BASE:
            out[pid] = common.zone_json(pid, lat, lng, morningPoint=common.point(f["lat"], f["lng"]),
                                        eveningPoint=common.point(f["lat"], f["lng"]))
        else:
            out[pid] = common.zone_json(pid, lat, lng, baseZoneId=BASE, offsetRule="PUBLISHED",
                                        offsets={e: float(off) for e in EVENTS})
    return out


def _run(batch):
    method, zones, items = batch
    out = []
    for key, zid, day in items:
        zone = zones[zid]
        lat, lng = zone["point"]["latE5"] / 1e5, zone["point"]["lngE5"] / 1e5
        base = zones.get(zone.get("baseZoneId")) if zone.get("baseZoneId") else None
        got = common.compute(common.case(method, lat, lng, TZ, day, zone=zone, base_zone=base, country="PS"))
        out.append((key, {e: (got[e][1] + int(TABLE_OFFSET_H * 3600)) // 60 % 1440 for e in EVENTS}))
    return out


def run_reference(jobs, workers: int) -> dict:
    """jobs: (method, zones, items); items (key, zone id, date). Returns key -> event -> minute at UTC+2."""
    batches = []
    for method, zones, items in jobs:
        n = max(1, min(len(items), workers * 2))
        batches += [(method, zones, items[i::n]) for i in range(n)]
    out = {}
    if workers <= 1:
        for b in batches:
            out.update(_run(b))
        return out
    with ProcessPoolExecutor(max_workers=workers) as ex:
        for part in ex.map(_run, batches):
            out.update(part)
    return out


def shares(score: common.Score) -> str:
    return " ".join(f"{e[:3]} {score.exact[e]}/{score.total[e]} ({score.within1[e]})" for e in EVENTS)


def describe(f: dict) -> str:
    ev = f["events"]
    return (f"{f['model']} sun, reference year {f['year']}, point {f['lat']:.3f} N {f['lng']:.4f} E; "
            f"Fajr {ev['FAJR'][2]['degrees']} deg {ev['FAJR'][2]['rounding']}, "
            f"sunrise {ev['SUNRISE'][2]['altitude']} deg {ev['SUNRISE'][2]['offset']:+d} min {ev['SUNRISE'][2]['rounding']}, "
            f"Dhuhr {ev['DHUHR'][2]['offset']:+d} {ev['DHUHR'][2]['rounding']}, "
            f"Asr {'apparent' if ev['ASR'][2]['apparent'] else 'geometric'} {ev['ASR'][2]['rounding']}, "
            f"Maghrib {ev['MAGHRIB'][2]['altitude']} deg {ev['MAGHRIB'][2]['offset']:+d} min {ev['MAGHRIB'][2]['rounding']}, "
            f"Isha {ev['ISHA'][2]['degrees']} deg {ev['ISHA'][2]['rounding']}")


# ------------------------------------------------------------------------------------------------------------

def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--workers", type=int, default=min(16, os.cpu_count() or 1))
    args = ap.parse_args(argv)
    sys.stdout.reconfigure(encoding="utf-8")
    table = load_table()
    print(f"{len(table)} rows of the perpetual table", flush=True)

    # Stage 1: Dhuhr alone, the leap phase.
    print("Stage 1, Dhuhr (truncated transit) by sun model and reference year (of 366, 365 in common years):", flush=True)
    for model in MODELS:
        counts = {y: dhuhr_lng(rows_at(table, y), model)[1] for y in YEARS}
        top = sorted(counts, key=lambda y: -counts[y])[:4]
        print(f"  {model}: " + ", ".join(f"{y} {counts[y]}" for y in top)
              + f"; leap years at most {max(c for y, c in counts.items() if leap(y))}", flush=True)

    # Stages 2-3 on the whole table.
    final = fit(table, None, args.workers)
    print("Stage 2, best joint score (within 1 min, exact) by sun model and year, all six times:", flush=True)
    for model in MODELS:
        tops = sorted(((v, y) for (m, y), v in final["by_year"].items() if m == model), reverse=True)[:5]
        print(f"  {model}: " + ", ".join(f"{y} {v[0]}/{v[1]}" for v, y in tops), flush=True)
    print(f"Stage 3: {describe(final)}; latitudes {final['lat_span'][0]:.3f}-{final['lat_span'][1]:.3f} tie", flush=True)
    for e in EVENTS:
        w1, ex, p = final["events"][e]
        print(f"  {e:8s} at the reference year: exact {ex}/366, within 1 min {w1}/366, {p}", flush=True)

    # Held out: month-split.
    halves = {}
    for keep in (1, 0):
        months = {m for m in range(1, 13) if m % 2 == keep}
        halves[keep] = fit(table, months, args.workers)
        print(f"Fitted on the {'odd' if keep else 'even'} months: {describe(halves[keep])}", flush=True)

    # The reference engine: the base zone at the reference year (in-sample), held out as shown (2026), and every
    # zone's own record; 2025-2028 as a side check of the leap phase.
    m_final = method_of(final)
    z_final = zones_of(final)
    jobs = []
    items = [(("ref", md), BASE, at_year(md, final["year"])) for md, _ in table if at_year(md, final["year"])]
    for y in (2025, 2026, 2027, 2028):
        items += [((y, md), BASE, at_year(md, y)) for md, _ in table if at_year(md, y)]
    jobs.append((m_final, z_final, items))
    for keep in (1, 0):
        held = [(md, t) for md, t in table if md[0] % 2 != keep]
        zs = zones_of(halves[keep])
        jobs.append((method_of(halves[keep]), zs, [(("held", pid, md), pid, as_shown(md)) for pid, *_ in PLACES for md, _ in held]))
    print(f"Reference engine: {sum(len(j[2]) for j in jobs)} cases on {args.workers} workers ...", flush=True)
    got = run_reference(jobs, args.workers)

    pub = dict(table)
    s_ref = common.Score()
    for md, t in table:
        day = at_year(md, final["year"])
        if day:
            for e in EVENTS:
                s_ref.add(e, day, got[("ref", md)][e], t[e])
    print(f"In-sample at the reference year {final['year']}: " + shares(s_ref), flush=True)
    for y in (2025, 2026, 2027, 2028):
        s = common.Score()
        for md, t in table:
            day = at_year(md, y)
            if day:
                for e in EVENTS:
                    s.add(e, day, got[(y, md)][e], t[e])
        print(f"  computed for {y} (the app): " + shares(s) + f"; worst {s.worst}", flush=True)
    zone_scores = {}
    for pid, name_ar, off, *_ in PLACES:
        s = common.Score()
        for md, t in table:
            if ("held", pid, md) in got:
                day = as_shown(md)
                for e in EVENTS:
                    mine = got[("held", pid, md)][e]
                    target = t[e] + (1.5 if pid == "qalqilya" else off)
                    lo, hi = math.floor(target), math.ceil(target)
                    s.add(e, day, mine, lo if abs(mine - lo) <= abs(mine - hi) else hi)
        zone_scores[pid] = s
    held = zone_scores[BASE]
    print(f"Held out (month-split, computed for {ACTUAL_YEAR}), Al-Aqsa: " + shares(held) + f"; worst {held.worst}", flush=True)
    print("  each place against Al-Aqsa's row plus its printed difference (Qalqilya +1.5: either neighbouring minute): "
          + ", ".join(f"{pid} {sum(zone_scores[pid].exact.values())}/{sum(zone_scores[pid].total.values())}" for pid, *_ in PLACES),
          flush=True)

    # FitStats and the asset.
    first, last = as_shown((1, 1)), as_shown((12, 31))
    src = "darifta_perpetual_table (ssalah2012.pdf, pages 4-27)"
    zones_out = []
    for pid, name_ar, off, gid, lat, lng in PLACES:
        fitted_on = [common.sample(f"{src}, read at the reference year {final['year']} (E5)", datetime.date(final["year"], 1, 1),
                                   datetime.date(final["year"], 12, 31), 366, 1)]
        diff = "" if pid == BASE else f" plus {name_ar}'s printed difference ({'+1.5' if pid == 'qalqilya' else f'{off:+d}'})"
        held_out = [common.sample(f"{src}{diff}: months split odd/even, each row computed for {ACTUAL_YEAR} (A8)",
                                  first, last, 366, 1, month_split=True)]
        zone = {"id": pid, "nameAr": name_ar, "point": common.point(lat, lng),
                "namedPlaces": [{"centre": common.point(lat, lng), "catalogueIds": [], "areaKm": 0.0}],
                "fit": common.fit_stats(fitted_on, held_out, zone_scores[pid])}
        if pid == BASE:
            zone["morningPoint"] = common.point(final["lat"], final["lng"])
            zone["eveningPoint"] = common.point(final["lat"], final["lng"])
        else:
            zone["baseZoneId"] = BASE
            zone["offsets"] = {e: float(off) for e in EVENTS}
        zones_out.append(zone)
    profile_fit = common.fit_stats(
        [common.sample(f"{src}, read at the reference year {final['year']} (E5)", datetime.date(final["year"], 1, 1),
                       datetime.date(final["year"], 12, 31), 366, 1)],
        [common.sample(f"{src}: months split odd/even, each row computed for {ACTUAL_YEAR} (A8)", first, last, 366, 1, month_split=True)],
        held)
    path = common.write_asset(AUTHORITY, AUTHORITY, "PS", "LISTED_PLACES", zones_out, fit=profile_fit, source=SOURCE_LINE)
    print(f"Profile: {describe(final)}")
    print(f"Wrote {path.relative_to(common.ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
