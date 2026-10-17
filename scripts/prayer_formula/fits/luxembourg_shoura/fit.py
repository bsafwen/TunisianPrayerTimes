#!/usr/bin/env python3
"""
Fits `luxembourg_shoura`, the Shoura of Luxembourg's 2026 table (plan 7.2, 7.5, Q37; milestone M3, 9.2, 9.4), and
writes its official-zone asset, android-app/app/src/main/assets/official-zones/luxembourg_shoura.json (schema 1):
one NATIONAL zone «لوكسمبورغ» whose point is Luxembourg City's centre and whose fitted computing point is its E6
morning and evening point (6.5).

Data, read at run time from test-data-local and never copied (D13, A7):
- research-2026-10-11/LU/shoura2026.csv: the Shoura's 2026 table (shoura.lu), six clock times a day, Luxembourg
  clock time (summer time on its dates); no February page; 10 November missing; the rows of 18 and 19 November
  are printed (and transcribed) as October and are read as November (their values are November's).
- research-2026-10-17/LU/shoura2026_february.csv (if present): February, read from page 2 of the Shoura's year PDF
  (Prayer-Timetable-2026-EN.pdf, uploaded 2026-09), every minute within 1 min of the profile fitted without it.

Documented misprints (8.3), counted apart: Maghrib on 27 and 28 March and Fajr on 25 October carry the summer-time
hour on the wrong side of its change (one hour off), Dhuhr on 2 October is printed 13:34 between 13:25 and 13:24,
and Asr from 2 to 23 August is printed one row late (QUIRKS).

The table is for 2026, so it is fitted and scored at 2026 dates (no reference year, E5).

Stages (9.4), each by windows (a published minute bounds the unrounded time to a half-open minute under E1; the
value most windows cover wins), for each sun model (E14):
0. One evaluation for all six times (E4) at the city centre with 13 deg / 13 deg, no offsets, NEAREST: every later
   stage's default.
1. Dhuhr: evaluation, rounding, whole-minute offset and longitude (within 0.1 deg of the centre).
2. Sunrise and sunset (standard horizon): latitude (within 0.1 deg), then each one's evaluation, rounding, offset.
3. Fajr and Isha: evaluation, rounding and angle (offset 0), the angle kept to 0.1 deg.
4. Asr: shadow factor, evaluation, rounding and offset.
An alternative to the default (stage 0's evaluation, NEAREST, offset 0) must cover more than 0.5 % more windows;
VSOP87 replaces MEEUS only where it covers more than 0.5 % more windows over the four stages.
Held out: month-split (stages refitted on the odd months and scored on the even ones, and the reverse), every
held-out day scored with common.compute (the reference engine) at 2026.

Usage: python scripts/prayer_formula/fits/luxembourg_shoura/fit.py
"""

from __future__ import annotations

import csv
import datetime
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import common  # noqa: E402

ref = common.ref
EVENTS = common.EVENTS
AUTHORITY = "luxembourg_shoura"
TZ = "Europe/Luxembourg"
DATA = common.LOCAL_DATA / "research-2026-10-11" / "LU" / "shoura2026.csv"
# February, which the 2026-10-11 transcription lacks, read on 2026-10-17 from the Shoura's year PDF (shoura.lu,
# wp-content/uploads/2026/09/Prayer-Timetable-2026-EN.pdf, page 2); read if present.
FEBRUARY = common.LOCAL_DATA / "research-2026-10-17" / "LU" / "shoura2026_february.csv"
YEAR = 2026
CENTRE = (49.61167, 6.13)              # Luxembourg City, GeoNames 2960316 (the plan's "Luxembourg City's centre")
BOX = 0.1                              # degrees around the centre searched for the table's point
LAT_STEP = 0.005
MODELS = ("MEEUS", "VSOP87")
EVALS = ("EVENT_INSTANT", "DAILY_0H_UT", "DAILY_10H_UT", "KACST")
ASR_EVALS = EVALS + ("LOCAL_MIDNIGHT_SHADOW",)
WINDOW = {"NEAREST": (-30.0, 30.0), "UP": (-60.0, 0.0), "DOWN": (0.0, 60.0)}
MODES = ("NEAREST", "UP", "DOWN")
CLEARLY = 1.005
PLAN_ANGLE = 13.0
COLUMNS = ("fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha")
QUIRKS = {(datetime.date(YEAR, 3, 27), "MAGHRIB"), (datetime.date(YEAR, 3, 28), "MAGHRIB"),
          (datetime.date(YEAR, 10, 2), "DHUHR"), (datetime.date(YEAR, 10, 25), "FAJR")}
# August's Asr from the 2nd to the 23rd is printed one row late: 17:49 twice (1 and 2 August), 17:29 never (17:30 on
# the 23rd, 17:28 on the 24th), each printed minute the one before's (the PDF read on 2026-10-17 shows it so, and
# three mosques that print the table everywhere else print the unslipped column, as the profile computes it).
QUIRKS |= {(datetime.date(YEAR, 8, day), "ASR") for day in range(2, 24)}
SOURCE = "The Shoura's 2026 table (shoura.lu)"
SOURCE_LINE = "The Shoura of Luxembourg's 2026 prayer timetable (shoura.lu): one table for the Grand Duchy"


def log(text: str = "") -> None:
    print(text, flush=True)


# ---- data -------------------------------------------------------------------------------------------------------

def parse(path: Path) -> dict[datetime.date, list[str]]:
    """date -> the six printed clock times; the November rows printed as October are read as November."""
    rows = {}
    for r in csv.DictReader(path.open(encoding="utf-8")):
        day = datetime.date.fromisoformat(r["date"])
        times = [r[c] for c in COLUMNS]
        if day.month == 10 and day.day in (18, 19) and int(r["maghrib"][:2]) < 18:   # November's values
            day = day.replace(month=11)
        assert day not in rows, day
        rows[day] = times
    return rows


def published(day: datetime.date, hhmm: str) -> int:
    return common.local_to_epoch(TZ, day, hhmm)


# ---- fast raw events (the reference's own primitives, S2-S7) ------------------------------------------------------

_SUNS: dict = {}


def sun_of(day, model, mode):
    key = (day, model, mode)
    if key not in _SUNS:
        cyc = ref.Cycle(day)                    # U = D in Luxembourg
        _SUNS[key] = (cyc, ref.SunFunction(ref.SUN_MODELS[model], mode if mode != "LOCAL_MIDNIGHT_SHADOW" else "EVENT_INSTANT", cyc.jd0))
    return _SUNS[key]


def transit_at(day, lng, model, mode):
    cyc, sun = sun_of(day, model, mode)
    return cyc, sun, ref.transit(cyc, sun, lng)


def event_at(day, lat, lng, alt, side, model, mode):
    cyc, sun, t0 = transit_at(day, lng, model, mode)
    return cyc.instant(ref.event(cyc, sun, lat, lng, alt, side, t0))


def asr_at(day, lat, lng, model, mode, factor):
    cyc, sun, t0 = transit_at(day, lng, model, mode)
    if mode == "LOCAL_MIDNIGHT_SHADOW":
        decl = ref.SUN_MODELS[model](ref.jd_of_instant(common.local_to_epoch(TZ, day, "0:00")))[0]
    else:
        decl = sun(cyc.jd0 + t0 / 24)[0]
    alt = ref.atand(1 / (factor + ref.tand(abs(lat - decl))))
    return cyc.instant(ref.event(cyc, sun, lat, lng, alt, 1, t0))


def depression(day, lat, lng, t, model, mode):
    cyc, sun = sun_of(day, model, mode)
    return -ref.altitude(cyc, sun, lat, lng, cyc.hours(t))


# ---- windows -----------------------------------------------------------------------------------------------------

def best_cover(intervals, lo, hi, prefer):
    """(count, value): the most half-open intervals covering one value within [lo, hi]; the value is `prefer` where it
    lies in a best plateau, else the middle of the best plateau nearest to it."""
    points = []
    for a, b in intervals:
        a, b = max(a, lo), min(b, hi)
        if a < b:
            points += [(a, 1), (b, -1)]
    points.sort()
    best, cur, plateaus = 0, 0, []
    for i, (x, d) in enumerate(points):
        cur += d
        nxt = points[i + 1][0] if i + 1 < len(points) else None
        if nxt is None or nxt <= x:
            continue
        if cur > best:
            best, plateaus = cur, [[x, nxt]]
        elif cur == best and best > 0:
            if abs(plateaus[-1][1] - x) < 1e-12:
                plateaus[-1][1] = nxt
            else:
                plateaus.append([x, nxt])
    if not plateaus:
        return 0, prefer
    for a, b in plateaus:
        if a <= prefer < b:
            return best, prefer
    a, b = min(plateaus, key=lambda p: min(abs(p[0] - prefer), abs(p[1] - prefer)))
    return best, (a + b) / 2


def exact(t, offset_min, mode, pub) -> bool:
    return ref.round_instant(t + 60 * offset_min, mode) == pub


def pick(candidates, default_key):
    """The candidate with the most windows; the default (its key) unless another covers clearly more."""
    best = max(candidates, key=lambda c: c[0])
    default = next(c for c in candidates if c[1] == default_key)
    return default if best[0] <= default[0] * CLEARLY else best


# ---- the stages ---------------------------------------------------------------------------------------------------

def fit(rows: dict[datetime.date, list[str]], log_lines: bool = False) -> dict:
    """Stages 0-4 on the given rows, for each sun model."""
    P = {e: [(d, published(d, times[k])) for d, times in sorted(rows.items()) if (d, e) not in QUIRKS]
         for k, e in enumerate(EVENTS)}
    results = []
    for model in MODELS:
        p = {"model": model, "evals": {}, "modes": {}, "offsets": {}, "counts": {}}
        lat0, lng0 = CENTRE
        say = log if log_lines else (lambda *_: None)
        # Stage 0: one evaluation for all six times.
        totals = {}
        for ev in EVALS:
            n = 0
            for e in EVENTS:
                for day, pb in P[e]:
                    if e == "DHUHR":
                        cyc, _, t0 = transit_at(day, lng0, model, ev)
                        t = cyc.instant(t0)
                    elif e == "ASR":
                        t = asr_at(day, lat0, lng0, model, ev, 1)
                    else:
                        alt, side = {"FAJR": (-PLAN_ANGLE, -1), "SUNRISE": (-0.8333, -1), "MAGHRIB": (-0.8333, 1),
                                     "ISHA": (-PLAN_ANGLE, 1)}[e]
                        t = event_at(day, lat0, lng0, alt, side, model, ev)
                    n += exact(t, 0, "NEAREST", pb)
            totals[ev] = n
        dev = max(EVALS, key=lambda ev: (totals[ev] > totals["EVENT_INSTANT"] * CLEARLY or ev == "EVENT_INSTANT", totals[ev]))
        p["stage0"] = dev
        say(f"  {model} stage 0: {dev} (" + ", ".join(f"{ev} {n}" for ev, n in totals.items()) + ")")
        # Stage 1: Dhuhr -> longitude.
        cands = []
        for ev in EVALS:
            t0s = []
            for day, pb in P["DHUHR"]:
                cyc, _, t0 = transit_at(day, lng0, model, ev)
                t0s.append((cyc.instant(t0), pb))
            for mode in MODES:
                lo, hi = WINDOW[mode]
                for off in (-1, 0, 1):
                    ivs = [(lng0 + (t + 60 * off - pb - hi) / 240, lng0 + (t + 60 * off - pb - lo) / 240) for t, pb in t0s]
                    n, lng = best_cover(ivs, lng0 - BOX, lng0 + BOX, lng0)
                    cands.append((n, (ev, mode, off), lng))
        n, (ev, mode, off), lng = pick(cands, (dev, "NEAREST", 0))
        p["evals"]["DHUHR"], p["modes"]["DHUHR"], p["offsets"]["DHUHR"], p["counts"]["DHUHR"] = ev, mode, off, n
        p["lng"] = round(lng, 5)
        say(f"  {model} stage 1, Dhuhr: {ev} {mode} {off:+d}, longitude {p['lng']:.5f} ({n} of {len(P['DHUHR'])})")

        # Stage 2: latitude from sunrise and sunset (each at its best rule), then each one's rule there.
        def horizon_cands(e, side, lat):
            out = []
            for ev in EVALS:
                ts = [(event_at(day, lat, p["lng"], -0.8333, side, model, ev), pb) for day, pb in P[e]]
                for mode in MODES:
                    for off in (-2, -1, 0, 1, 2):
                        out.append((sum(1 for t, pb in ts if exact(t, off, mode, pb)), (ev, mode, off)))
            return out

        best_lat = None
        steps = int(round(BOX / LAT_STEP))
        for k in range(-steps, steps + 1):
            lat = lat0 + k * LAT_STEP
            total = max(c[0] for c in horizon_cands("SUNRISE", -1, lat)) + max(c[0] for c in horizon_cands("MAGHRIB", 1, lat))
            key = (total, -abs(lat - lat0))
            if best_lat is None or key > best_lat[0]:
                best_lat = (key, lat)
        p["lat"] = round(best_lat[1], 5)
        for e, side in (("SUNRISE", -1), ("MAGHRIB", 1)):
            n, (ev, mode, off) = pick(horizon_cands(e, side, p["lat"]), (dev, "NEAREST", 0))
            p["evals"][e], p["modes"][e], p["offsets"][e], p["counts"][e] = ev, mode, off, n
        say(f"  {model} stage 2: latitude {p['lat']:.5f}; sunrise {p['evals']['SUNRISE']} {p['modes']['SUNRISE']} "
            f"{p['offsets']['SUNRISE']:+d} ({p['counts']['SUNRISE']}), Maghrib {p['evals']['MAGHRIB']} {p['modes']['MAGHRIB']} "
            f"{p['offsets']['MAGHRIB']:+d} ({p['counts']['MAGHRIB']})")
        # Stage 3: Fajr and Isha -> evaluation, rounding, angle.
        for e, side in (("FAJR", -1), ("ISHA", 1)):
            cands = []
            for ev in EVALS:
                for mode in MODES:
                    lo, hi = WINDOW[mode]
                    ivs = []
                    for day, pb in P[e]:
                        da = depression(day, p["lat"], p["lng"], pb + lo, model, ev)
                        db = depression(day, p["lat"], p["lng"], pb + hi, model, ev)
                        ivs.append((min(da, db), max(da, db)))
                    n, angle = best_cover(ivs, 11.0, 15.0, PLAN_ANGLE)
                    cands.append((n, (ev, mode), (angle, ivs)))
            n, (ev, mode), (angle, ivs) = pick(cands, (dev, "NEAREST"))
            rounded = round(angle, 1)
            p["evals"][e], p["modes"][e], p["offsets"][e] = ev, mode, 0
            p["counts"][e] = sum(1 for a, b in ivs if a <= rounded < b)
            p[e.lower()] = rounded
            p["window_" + e] = f"{angle:.3f} ({n})"
        say(f"  {model} stage 3: Fajr {p['evals']['FAJR']} {p['modes']['FAJR']} {p['fajr']} ({p['counts']['FAJR']}; windows "
            f"{p['window_FAJR']}), Isha {p['evals']['ISHA']} {p['modes']['ISHA']} {p['isha']} ({p['counts']['ISHA']}; "
            f"windows {p['window_ISHA']})")
        # Stage 4: Asr -> factor, evaluation, rounding, offset.
        cands = []
        for factor in (1, 2):
            for ev in ASR_EVALS:
                ts = [(asr_at(day, p["lat"], p["lng"], model, ev, factor), pb) for day, pb in P["ASR"]]
                for mode in MODES:
                    for off in (-2, -1, 0, 1, 2):
                        cands.append((sum(1 for t, pb in ts if exact(t, off, mode, pb)), (factor, ev, mode, off)))
        n, (factor, ev, mode, off) = pick(cands, (1, dev, "NEAREST", 0))
        p["factor"] = factor
        p["evals"]["ASR"], p["modes"]["ASR"], p["offsets"]["ASR"], p["counts"]["ASR"] = ev, mode, off, n
        say(f"  {model} stage 4, Asr: factor {factor} {ev} {mode} {off:+d} ({n} of {len(P['ASR'])})")
        p["total"] = sum(p["counts"].values())
        say(f"  {model}: windows covered {p['total']} of {sum(len(v) for v in P.values())}")
        results.append(p)
    default, other = results
    return other if other["total"] > default["total"] * CLEARLY else default


# ---- a diagnostic: the sun taken once a day at another hour -------------------------------------------------------

class _Fixed:
    def __init__(self, value):
        self.value = value

    def __call__(self, jd):
        return self.value


def fixed_hour_scan(rows, lat, lng, hours) -> list[str]:
    """Per hour h: the windows each time covers with the sun taken once a day at h UT (one continuous offset; Fajr and
    Isha by angle). Not an engine mode except h = 0 and 10: it shows where the table's evening times sit."""
    model = ref.SUN_MODELS["MEEUS"]
    out = []
    for h in hours:
        parts = []
        for e in EVENTS:
            k = EVENTS.index(e)
            ivs = []
            for d, times in sorted(rows.items()):
                if (d, e) in QUIRKS:
                    continue
                cyc = ref.Cycle(d)
                sun = ref.SunFunction(model, "EVENT_INSTANT", cyc.jd0) if h is None else _Fixed(model(cyc.jd0 + h / 24))
                t0 = ref.transit(cyc, sun, lng)
                pub = published(d, times[k])
                if e in ("FAJR", "ISHA"):
                    a = -ref.altitude(cyc, sun, lat, lng, cyc.hours(pub - 30))
                    b = -ref.altitude(cyc, sun, lat, lng, cyc.hours(pub + 30))
                    ivs.append((min(a, b), max(a, b)))
                    continue
                if e == "DHUHR":
                    t = cyc.instant(t0)
                elif e == "ASR":
                    alt = ref.atand(1 / (1 + ref.tand(abs(lat - sun(cyc.jd0 + t0 / 24)[0]))))
                    t = cyc.instant(ref.event(cyc, sun, lat, lng, alt, 1, t0))
                else:
                    t = cyc.instant(ref.event(cyc, sun, lat, lng, -0.8333, -1 if e == "SUNRISE" else 1, t0))
                x = (pub - t) / 60
                ivs.append((x - 0.5, x + 0.5))
            if e in ("FAJR", "ISHA"):
                n, a = best_cover(ivs, 11.0, 15.0, PLAN_ANGLE)
                parts.append(f"{e[:4]} {n} ({a:.2f} deg)")
            else:
                n, o = best_cover(ivs, -10.0, 10.0, 0.0)
                parts.append(f"{e[:4]} {n}")
        out.append(("event instant" if h is None else f"{h:4.1f} h UT") + ": " + ", ".join(parts))
    return out


# ---- profile, zone and scores -------------------------------------------------------------------------------------

def method_of(p: dict | None) -> dict:
    """luxembourg_shoura's PrayerMethod JSON: the fitted rules, or (p None) the preset as it stood before this fit."""
    high = {"type": "angle_based_absent_nights"}
    if p is None:
        return common.method_json(id=AUTHORITY, fajr={"type": "angle", "degrees": 13.0}, isha={"type": "angle", "degrees": 13.0},
                                  highLatitude=high, evidence={"kind": "FROM_TABLES", "source": "preset"})
    return common.method_json(
        id=AUTHORITY,
        fajr={"type": "angle", "degrees": p["fajr"]}, isha={"type": "angle", "degrees": p["isha"]},
        offsets={e: p["offsets"][e] for e in EVENTS if p["offsets"][e]},
        rounding={e: p["modes"][e] for e in EVENTS},
        sunEvaluation={e: p["evals"][e] for e in EVENTS if p["evals"][e] != "EVENT_INSTANT"},
        sunModel=p["model"], asrShadow=p["factor"], highLatitude=high,
        evidence={"kind": "FROM_TABLES", "source": "The Shoura's 2026 table"},
    )


def zone_of(p: dict | None) -> dict:
    """The one zone, id "lu": Luxembourg City's centre as its point, the fitted point as its E6 points."""
    fields = {"authority": AUTHORITY}
    if p is not None:
        fields["morningPoint"] = common.point(p["lat"], p["lng"])
        fields["eveningPoint"] = common.point(p["lat"], p["lng"])
    return common.zone_json("lu", CENTRE[0], CENTRE[1], **fields)


def score(method: dict, zone: dict | None, rows, months=None) -> tuple[common.Score, int, list]:
    """The app's minutes (common.compute) against the rows; documented misprints counted apart."""
    sc, estimated, quirks = common.Score(), 0, []
    for d, times in sorted(rows.items()):
        if months is not None and d.month not in months:
            continue
        out = common.compute(common.case(method, CENTRE[0], CENTRE[1], TZ, d, zone=zone, country="LU"))
        for k, e in enumerate(EVENTS):
            _, minute, status = out[e]
            estimated += status == "ESTIMATED"
            if (d, e) in QUIRKS:
                quirks.append((d.isoformat(), e, minute // 60 - published(d, times[k]) // 60))
                continue
            sc.add(e, d, minute // 60, published(d, times[k]) // 60)
    return sc, estimated, quirks


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


def describe(p: dict) -> str:
    return (f"{p['model']} point {p['lat']:.5f},{p['lng']:.5f} Fajr {p['fajr']} Isha {p['isha']} Asr factor {p['factor']}; "
            + ", ".join(f"{e[:4]} {p['evals'][e]} {p['modes'][e]} {p['offsets'][e]:+d}" for e in EVENTS)
            + f"; windows' own angles: Fajr {p['window_FAJR']}, Isha {p['window_ISHA']}")


def main() -> int:
    started = time.time()
    rows = parse(DATA)
    if FEBRUARY.exists():
        february = parse(FEBRUARY)
        assert not set(february) & set(rows), "February twice"
        rows.update(february)
    months_present = sorted({d.month for d in rows})
    log(f"luxembourg_shoura: {len(rows)} rows of the Shoura's 2026 table ({DATA.name}"
        + (f", {FEBRUARY.name}" if FEBRUARY.exists() else "") + f"), months {months_present}")
    halves = {"odd": [m for m in months_present if m % 2 == 1], "even": [m for m in months_present if m % 2 == 0]}
    fitted = {}
    for name, months in halves.items():
        log(f"fit on the {name} months:")
        fitted[name] = fit({d: v for d, v in rows.items() if d.month in months}, log_lines=True)
        log("  " + describe(fitted[name]))
    log("fit on all months:")
    final = fit(rows, log_lines=True)
    log("  " + describe(final))
    log(f"  ({time.time() - started:.0f} s)")

    held = common.Score()
    for name, other in (("odd", "even"), ("even", "odd")):
        sc, _, _ = score(method_of(fitted[other]), zone_of(fitted[other]), rows, halves[name])
        merge(held, sc)
    log(f"held out (month-split), {YEAR}, exact/within 1 (%):")
    log("  " + shares(held))
    method, zone = method_of(final), zone_of(final)
    in_sc, est, quirks = score(method, zone, rows)
    log("final profile in-sample:")
    log("  " + shares(in_sc) + f"  ESTIMATED {est}")
    log("  misprinted rows (app minus print, min): " + ", ".join(f"{d} {e[:4]} {x:+d}" for d, e, x in quirks))
    pre, _, _ = score(method_of(None), None, rows)
    log("the preset before this fit (13/13, no offsets, NEAREST, at Luxembourg City's centre):")
    log("  " + shares(pre))
    misses = sorted(held.misses)
    if misses:
        log(f"  held-out misses beyond 1 min: {len(misses)}: " + ", ".join(f"{d} {e[:4]} {x:+d}" for e, d, x in misses[:20]))
    log("diagnostic: the sun taken once a day at h UT, at the city centre (windows covered, one continuous offset):")
    for line in fixed_hour_scan(rows, CENTRE[0], CENTRE[1], (None, 0.0, 3.0, 5.0, 6.0, 8.0, 10.0, 12.0)):
        log("  " + line)

    days = len(rows)
    source = f"{SOURCE}, {len(months_present)} months"
    stats = common.fit_stats(
        [common.sample(source, min(rows), max(rows), days, 1)],
        [common.sample(source + ", held out by month", min(rows), max(rows), days, 1, month_split=True)],
        held, quirk_days=len({d for d, _ in QUIRKS}))
    asset_zone = {k: v for k, v in zone.items() if k != "authority"}
    asset_zone.update({"nameAr": "لوكسمبورغ", "namedPlaces": [{"centre": common.point(*CENTRE), "catalogueIds": [], "areaKm": 0.0}],
                       "fit": stats})
    path = common.write_asset(AUTHORITY, AUTHORITY, "LU", "NATIONAL", [asset_zone], fit=stats, source=SOURCE_LINE)
    log(f"wrote {path.relative_to(common.ROOT)} in {time.time() - started:.0f} s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
