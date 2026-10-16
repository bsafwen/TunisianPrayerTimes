#!/usr/bin/env python3
"""
Fits `hr_miz_zagreb`, MIZ Zagreb's «Zagrebačka vaktija» (plan 7.4, 7.5, 8.8; milestone M3, 9.4), and writes its
official-zone asset, android-app/app/src/main/assets/official-zones/hr_miz_zagreb.json (schema 1): one zone «زغرب»
(`NATIONAL`, 6.5) at Zagreb's centre, the fitted computing point as its E6 morning and evening point.

Data, read at run time and never copied (D13, A7):
- test-data-local/research-2026-10-11/BA/hr_miz_zagreb/zagreb_sheets.json: MIZ Zagreb's Ramadan 1446 sheet (1-29 March
  2025) and its September 2021 sheet, transcribed by eye from the saved images (IMG_20250227_182704.jpg,
  vaktija9_2021.jpg) on 2026-10-16; the Ramadan rows equal the research transcription of 2026-10-11 on all 29 days.
- mosque-dataset/mosques.db (support only, 5.6): the Islamic Cultural Centre Zagreb's calendar (mosque 4179), which
  equals the Ramadan 2025 sheet on 28 of 29 days (the 29th is the summer-time day), for the summer form's dates.

1. The current rules are the 2025 sheet's: Isha is Maghrib + 80 on all 29 days. Stages of 9.4 by windows on it, from
   the preset (Fajr 19 deg, sunrise -7, Dhuhr +1, Maghrib +6, the sun at the event instant, NEAREST) at Zagreb's
   centre; an alternative must cover more than 0.5 % and at least 2 more windows; with one month, one evaluation for
   all six (E4) and the sun model MEEUS unless another is clearly better: 0, the evaluation; 1, Dhuhr (rounding, offset,
   longitude within 0.25 deg); 2, the latitude (within 0.25 deg, never on the search's edge) from sunrise, Maghrib and
   Fajr of both sheets, each sheet with its own roundings and offsets (the point MIZ computes for is one, its minutes
   changed between 2021 and 2025), leaving Zagreb's latitude only where the fits on both halves of the days move it to
   the same side; then sunrise's and Maghrib's rounding and offset on the 2025 sheet; 3, Fajr (rounding, and the
   angle or the preset angle with an offset); 4, Asr.
   Held out: alternate days (the stages refitted on the odd days score the even ones, and the reverse).
2. The September 2021 sheet is scored with the 2025 rules (a different year and its own, older rules: printed apart).
3. The summer form (7.4): Fajr no earlier than sunrise - F from one date to another. The mosque's calendar prints Fajr
   at its sunrise - 105 from late March, and from 2 September prints the angle time again (106 min); F is the 105
   printed minutes in the engine's terms (sunrise before its offset, Fajr before its own): F = 105 + Fajr offset -
   sunrise offset. The window is the first and last day the clamp acts in 2026-2029 under the fitted profile, ended
   no later than 1 September (the mosque's 2 September is not clamped).

Usage: python scripts/prayer_formula/fits/hr_miz_zagreb/fit.py
"""

from __future__ import annotations

import datetime
import json
import math
import sqlite3
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import common  # noqa: E402

ref = common.ref
EVENTS = common.EVENTS
AUTHORITY = "hr_miz_zagreb"
TZ = "Europe/Zagreb"
SHEETS = common.LOCAL_DATA / "research-2026-10-11" / "BA" / "hr_miz_zagreb" / "zagreb_sheets.json"
MOSQUES = common.ROOT.parent.parent.parent / "mosque-dataset" / "mosques.db" if (common.ROOT.parent.parent.parent / "mosque-dataset").exists() \
    else common.ROOT / "mosque-dataset" / "mosques.db"
MOSQUE_KEY = 4179                          # Islamski kulturni centar Zagreb
CENTRE = (45.81444, 15.97798)              # Zagreb, GeoNames 3186886 (the catalogue's row)
BOX_LNG, BOX_LAT, LAT_STEP = 0.25, 0.25, 0.005
MODELS = ("MEEUS", "VSOP87", "TEXTBOOK", "ALMANAC")
EVALS = ("EVENT_INSTANT", "DAILY_0H_UT", "DAILY_10H_UT", "KACST")
WINDOW = {"NEAREST": (-30.0, 30.0), "UP": (-60.0, 0.0), "DOWN": (0.0, 60.0)}
MODES = ("NEAREST", "UP", "DOWN")
CLEARLY, MIN_GAIN = 1.005, 2
PRESET = {"FAJR": 0, "SUNRISE": -7, "DHUHR": 1, "ASR": 0, "MAGHRIB": 6, "ISHA": 0}   # EuropeanPresets (7.5)
PRESET_FAJR = 19.0
ISHA_AFTER = 80
PRINTED_SUMMER_GAP = 105                   # the mosque's sunrise - Fajr in the summer form
LAST_SUMMER_DAY = (9, 1)                   # the mosque's 2 September is the angle time again
PLAN_SUMMER_START = (3, 25)                # EuropeanPresets' start, kept where the fitted profile allows it
APP_YEARS = (2026, 2027, 2028, 2029)
SOURCE_LINE = "MIZ Zagreb (Medžlis Islamske zajednice Zagreb): «Zagrebačka vaktija», the vaktija for the Zagreb area"


def log(text: str = "") -> None:
    print(text, flush=True)


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")


def clearly(best: int, default: int) -> bool:
    return best > default * CLEARLY and best >= default + MIN_GAIN


# The point's latitude leaves the centre only where the fits on both halves of the data move it to the same side.
LATITUDE = {"free": True}


def same_side(a: float, b: float, centre: float) -> bool:
    return (a - centre) * (b - centre) > 0


def pick(candidates, default_key):
    best = max(candidates, key=lambda c: c[0])
    default = next(c for c in candidates if c[1] == default_key)
    return best if clearly(best[0], default[0]) else default


def load_sheets():
    data = json.loads(SHEETS.read_text(encoding="utf-8"))
    return ({datetime.date.fromisoformat(k): v for k, v in data["ramadan_2025"].items()},
            {datetime.date.fromisoformat(k): v for k, v in data["september_2021"].items()})


def minutes(day, hhmm) -> int:
    return common.local_to_epoch(TZ, day, hhmm) // 60


# ---- fast raw events ---------------------------------------------------------------------------------------------

_SUNS: dict = {}
MODEL = {"name": "MEEUS"}


def sun_of(day, mode):
    key = (day, mode, MODEL["name"])
    if key not in _SUNS:
        cyc = ref.Cycle(day)
        _SUNS[key] = (cyc, ref.SunFunction(ref.SUN_MODELS[MODEL["name"]], mode, cyc.jd0))
    return _SUNS[key]


def transit_at(day, lng, mode):
    cyc, sun = sun_of(day, mode)
    return cyc, sun, ref.transit(cyc, sun, lng)


def event_at(day, lat, lng, alt, side, mode):
    cyc, sun, t0 = transit_at(day, lng, mode)
    return cyc.instant(ref.event(cyc, sun, lat, lng, alt, side, t0))


def asr_at(day, lat, lng, mode, factor):
    cyc, sun, t0 = transit_at(day, lng, mode)
    decl = sun(cyc.jd0 + t0 / 24)[0]
    alt = ref.atand(1 / (factor + ref.tand(abs(lat - decl))))
    return cyc.instant(ref.event(cyc, sun, lat, lng, alt, 1, t0))


def depression(day, lat, lng, t, mode):
    cyc, sun = sun_of(day, mode)
    return -ref.altitude(cyc, sun, lat, lng, cyc.hours(t))


def best_cover(intervals, lo, hi, prefer):
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


def exact(t, offset_min, mode, pub_epoch) -> bool:
    return t is not None and ref.round_instant(t + 60 * offset_min, mode) == pub_epoch


# ---- the stages ---------------------------------------------------------------------------------------------------

def fit(rows, say=lambda *_: None, other_sheet=None) -> dict:
    """The stages for each sun model; [other_sheet] (the 2021 sheet) joins the latitude stage only, with its own
    roundings and offsets: the point MIZ computes for is one, its minutes may have changed."""
    results = {}
    for model in MODELS:
        MODEL["name"] = model
        results[model] = fit_model(rows, other_sheet=other_sheet)
    totals = {m: sum(r["counts"].values()) for m, r in results.items()}
    best = max(MODELS, key=lambda m: (clearly(totals[m], totals["MEEUS"]) or m == "MEEUS", totals[m]))
    say("  sun models, windows over five times (Isha follows Maghrib): " + ", ".join(f"{m} {n}" for m, n in totals.items()) + f": {best}")
    MODEL["name"] = best
    p = fit_model(rows, say, other_sheet)
    p["model"] = best
    MODEL["name"] = "MEEUS"
    return p


def fit_model(rows, say=lambda *_: None, other_sheet=None) -> dict:
    events = ("FAJR", "SUNRISE", "DHUHR", "ASR", "MAGHRIB")
    P = {e: [(d, common.local_to_epoch(TZ, d, times[EVENTS.index(e)])) for d, times in sorted(rows.items())] for e in events}
    P_other = {e: [(d, common.local_to_epoch(TZ, d, times[EVENTS.index(e)])) for d, times in sorted((other_sheet or {}).items())]
               for e in events}
    p = {"evals": {}, "modes": {}, "offsets": {}, "counts": {}}
    lat0, lng0 = CENTRE
    totals = {}
    for ev in EVALS:
        n = 0
        for e in events:
            for day, pb in P[e]:
                if e == "DHUHR":
                    cyc, _, t0 = transit_at(day, lng0, ev)
                    t = cyc.instant(t0)
                elif e == "ASR":
                    t = asr_at(day, lat0, lng0, ev, 1)
                else:
                    alt, side = {"FAJR": (-PRESET_FAJR, -1), "SUNRISE": (-0.8333, -1), "MAGHRIB": (-0.8333, 1)}[e]
                    t = event_at(day, lat0, lng0, alt, side, ev)
                n += exact(t, PRESET[e], "NEAREST", pb)
        totals[ev] = n
    ev = max(EVALS, key=lambda x: (clearly(totals[x], totals["EVENT_INSTANT"]) or x == "EVENT_INSTANT", totals[x]))
    p["stage0"] = ev
    say(f"  stage 0, one evaluation for all six (the preset at Zagreb's centre): {ev} ("
        + ", ".join(f"{x} {n}" for x, n in totals.items()) + ")")
    # Stage 1: Dhuhr.
    cands = []
    t0s = []
    for day, pb in P["DHUHR"]:
        cyc, _, t0 = transit_at(day, lng0, ev)
        t0s.append((cyc.instant(t0), pb))
    for mode in MODES:
        lo, hi = WINDOW[mode]
        for off in range(-1, 4):
            ivs = [(lng0 + (t + 60 * off - pb - hi) / 240, lng0 + (t + 60 * off - pb - lo) / 240) for t, pb in t0s]
            n, lng = best_cover(ivs, lng0 - BOX_LNG, lng0 + BOX_LNG, lng0)
            cands.append((n, (ev, mode, off), lng))
    n, (_, mode, off), lng = pick(cands, (ev, "NEAREST", PRESET["DHUHR"]))
    p["evals"]["DHUHR"], p["modes"]["DHUHR"], p["offsets"]["DHUHR"], p["counts"]["DHUHR"] = ev, mode, off, n
    p["lng"] = round(lng, 5)
    say(f"  stage 1, Dhuhr: {mode} {off:+d}, longitude {p['lng']:.5f} ({n} of {len(P['DHUHR'])})")

    def horizon_cands(e, side, offs, lat, sheet=None):
        out = []
        ts = [(event_at(day, lat, p["lng"], -0.8333, side, ev), pb) for day, pb in (sheet or P)[e]]
        for mode in MODES:
            for off in offs:
                out.append((sum(1 for t, pb in ts if exact(t, off, mode, pb)), (ev, mode, off)))
        return out

    def fajr_best(lat, sheet=None):
        best = 0
        for mode in MODES:
            lo, hi = WINDOW[mode]
            ivs = []
            for day, pb in (sheet or P)["FAJR"]:
                da, db = depression(day, lat, p["lng"], pb + lo, ev), depression(day, lat, p["lng"], pb + hi, ev)
                ivs.append((min(da, db), max(da, db)))
            best = max(best, best_cover(ivs, PRESET_FAJR - 3, PRESET_FAJR + 3, PRESET_FAJR)[0])
        return best

    offs_sr, offs_mg = range(-11, -2), range(3, 11)

    def lat_total(lat):
        total = 0
        for sheet in (P, P_other) if other_sheet else (P,):
            total += (max(c[0] for c in horizon_cands("SUNRISE", -1, offs_sr, lat, sheet))
                      + max(c[0] for c in horizon_cands("MAGHRIB", 1, offs_mg, lat, sheet)) + fajr_best(lat, sheet))
        return total

    best_lat = None
    for k in range(-round(BOX_LAT / LAT_STEP), round(BOX_LAT / LAT_STEP) + 1):
        lat = lat0 + k * LAT_STEP
        key = (lat_total(lat), -abs(lat - lat0))
        if best_lat is None or key > best_lat[0]:
            best_lat = (key, lat)
    at_centre = lat_total(lat0)
    on_edge = abs(best_lat[1] - lat0) >= BOX_LAT - LAT_STEP / 2      # an optimum on the search's edge is no point
    p["lat"] = round(best_lat[1], 5) if clearly(best_lat[0][0], at_centre) and not on_edge and LATITUDE["free"] else lat0
    p["lat_windows"] = f"{best_lat[0][0]} at {best_lat[1]:.5f}{' (the edge)' if on_edge else ''}, {at_centre} at the centre"
    for e, side, offs in (("SUNRISE", -1, offs_sr), ("MAGHRIB", 1, offs_mg)):
        n, (_, mode, off) = pick(horizon_cands(e, side, offs, p["lat"]), (ev, "NEAREST", PRESET[e]))
        p["evals"][e], p["modes"][e], p["offsets"][e], p["counts"][e] = ev, mode, off, n
    say(f"  stage 2: latitude {p['lat']:.5f} (sunrise, Maghrib and Fajr windows: {p['lat_windows']}); sunrise "
        f"{p['modes']['SUNRISE']} {p['offsets']['SUNRISE']:+d} ({p['counts']['SUNRISE']}), Maghrib {p['modes']['MAGHRIB']} "
        f"{p['offsets']['MAGHRIB']:+d} ({p['counts']['MAGHRIB']})")
    # Stage 3: Fajr.
    cands = []
    for mode in MODES:
        lo, hi = WINDOW[mode]
        ivs = []
        for day, pb in P["FAJR"]:
            da, db = depression(day, p["lat"], p["lng"], pb + lo, ev), depression(day, p["lat"], p["lng"], pb + hi, ev)
            ivs.append((min(da, db), max(da, db)))
        _, angle = best_cover(ivs, PRESET_FAJR - 3, PRESET_FAJR + 3, PRESET_FAJR)
        rounded = round(angle, 1)
        cands.append((sum(1 for a, b in ivs if a <= rounded < b), (ev, mode, rounded, 0)))
        ts = [(event_at(day, p["lat"], p["lng"], -PRESET_FAJR, -1, ev), pb) for day, pb in P["FAJR"]]
        for off in range(-4, 5):
            cands.append((sum(1 for t, pb in ts if exact(t, off, mode, pb)), (ev, mode, PRESET_FAJR, off)))
    default = (ev, "NEAREST", PRESET_FAJR, 0)
    best = max(cands, key=lambda c: (c[0], c[1][3] == 0, c[1][2] == PRESET_FAJR))
    n0 = next(c[0] for c in cands if c[1] == default)
    n, (_, mode, angle, off) = best if clearly(best[0], n0) else (n0, default)
    p["evals"]["FAJR"], p["modes"]["FAJR"], p["offsets"]["FAJR"], p["counts"]["FAJR"] = ev, mode, off, n
    p["fajr"] = angle
    say(f"  stage 3, Fajr: {mode} {angle} deg {off:+d} ({n} of {len(P['FAJR'])}; the preset {n0})")
    # Stage 4: Asr.
    cands = []
    for factor in (1, 2):
        ts = [(asr_at(day, p["lat"], p["lng"], ev, factor), pb) for day, pb in P["ASR"]]
        for mode in MODES:
            for off in range(-3, 4):
                cands.append((sum(1 for t, pb in ts if exact(t, off, mode, pb)), (factor, ev, mode, off)))
    n, (factor, _, mode, off) = pick(cands, (1, ev, "NEAREST", PRESET["ASR"]))
    p["factor"] = factor
    p["evals"]["ASR"], p["modes"]["ASR"], p["offsets"]["ASR"], p["counts"]["ASR"] = ev, mode, off, n
    say(f"  stage 4, Asr: factor {factor} {mode} {off:+d} ({n} of {len(P['ASR'])})")
    return p


# ---- profile, zone, scores ----------------------------------------------------------------------------------------

def summer_form(p: dict | None, window=((3, 25), (8, 25)), gap=105) -> dict:
    (fm, fd), (tm, td) = window
    return {"type": "summer_form", "fromMonth": fm, "fromDay": fd, "toMonth": tm, "toDay": td, "fajrBeforeSunrise": gap}


def method_of(p: dict | None, summer: dict | None = None) -> dict:
    if p is None:
        return common.method_json(id=AUTHORITY, fajr={"type": "angle", "degrees": PRESET_FAJR},
                                  isha={"type": "after", "from": "MAGHRIB", "minutes": ISHA_AFTER},
                                  offsets={e: v for e, v in PRESET.items() if v}, highLatitude=summer_form(None),
                                  evidence={"kind": "FROM_TABLES", "source": "preset"})
    return common.method_json(
        id=AUTHORITY, fajr={"type": "angle", "degrees": p["fajr"]},
        isha={"type": "after", "from": "MAGHRIB", "minutes": ISHA_AFTER},
        offsets={e: p["offsets"][e] for e in EVENTS if e != "ISHA" and p["offsets"][e]},
        rounding={e: p["modes"].get(e, "NEAREST") for e in EVENTS},
        sunEvaluation={e: p["evals"][e] for e in p["evals"] if p["evals"][e] != "EVENT_INSTANT"},
        asrShadow=p["factor"], sunModel=p.get("model", "MEEUS"), highLatitude=summer or summer_form(p),
        evidence={"kind": "FROM_TABLES", "source": "MIZ Zagreb's sheets (fits/hr_miz_zagreb)"})


def zone_of(p: dict | None) -> dict:
    fields = {"authority": AUTHORITY}
    if p is not None and (p["lat"], p["lng"]) != CENTRE:
        fields["morningPoint"] = common.point(p["lat"], p["lng"])
        fields["eveningPoint"] = common.point(p["lat"], p["lng"])
    return common.zone_json("zagreb", CENTRE[0], CENTRE[1], **fields)


def score(method, zone, rows, days=None) -> common.Score:
    sc = common.Score()
    for d, times in sorted(rows.items()):
        if days is not None and d not in days:
            continue
        out = common.compute(common.case(method, CENTRE[0], CENTRE[1], TZ, d, zone=zone, country="HR"))
        for k, e in enumerate(EVENTS):
            sc.add(e, d, out[e][1] // 60, minutes(d, times[k]))
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
    return " ".join(f"{e[:4]} {100 * ex[e]:5.1f}/{100 * w1[e]:5.1f}" for e in EVENTS if sc.total[e]) + f"  max {sc.worst}"


def describe(p: dict) -> str:
    return (f"{p.get('model', 'MEEUS')} point {p['lat']:.5f},{p['lng']:.5f} Fajr {p['fajr']} Isha Maghrib + {ISHA_AFTER}, "
            f"Asr factor {p['factor']}; " + ", ".join(f"{e[:4]} {p['evals'][e]} {p['modes'][e]} {p['offsets'][e]:+d}"
                                                    for e in ("FAJR", "SUNRISE", "DHUHR", "ASR", "MAGHRIB")))


def km(a, b) -> float:
    return math.hypot((a[0] - b[0]) * 111.2, (a[1] - b[1]) * 111.2 * math.cos(math.radians(b[0])))


def mosque_calendar() -> dict[tuple[int, int], list[str]]:
    con = sqlite3.connect(f"file:{MOSQUES.as_posix()}?mode=ro", uri=True)
    rows = con.execute("select month, day, fajr, sunrise, dhuhr, asr, maghrib, isha from calendar_days where mosque_key=?",
                       (MOSQUE_KEY,)).fetchall()
    return {(m, d): list(t) for m, d, *t in rows}


def clamp_days(method: dict, zone: dict, year: int) -> list[datetime.date]:
    """The days the angle-time Fajr comes earlier than the summer form's bound (the form acts there if in its window)."""
    out = []
    day = datetime.date(year, 1, 1)
    plain = dict(method, highLatitude={"type": "angle_based"})
    while day.year == year:
        a = common.compute(common.case(plain, CENTRE[0], CENTRE[1], TZ, day, zone=zone, country="HR"))
        fajr, sunrise = a["FAJR"][0], a["SUNRISE"][0]
        gap = method["highLatitude"]["fajrBeforeSunrise"]
        # The bound: sunrise before its offset - gap, then the Fajr offset; compared before rounding.
        bound = sunrise - 60 * method["offsets"].get("SUNRISE", 0) - 60 * gap + 60 * method["offsets"].get("FAJR", 0)
        if fajr < bound - 0.5:
            out.append(day)
        day += datetime.timedelta(days=1)
    return out


def main() -> int:
    started = time.time()
    r25, r21 = load_sheets()
    isha_gap = {minutes(d, v[5]) - minutes(d, v[4]) for d, v in r25.items()}
    log(f"hr_miz_zagreb: the Ramadan 2025 sheet, {len(r25)} days (Isha - Maghrib: {sorted(isha_gap)} min); the September "
        f"2021 sheet, {len(r21)} days (Isha - Maghrib: {sorted({minutes(d, v[5]) - minutes(d, v[4]) for d, v in r21.items()})})")
    assert isha_gap == {ISHA_AFTER}
    days = sorted(r25)
    halves = {"odd": {d for d in days if d.day % 2 == 1}, "even": {d for d in days if d.day % 2 == 0}}
    fitted = {}
    for name, part in halves.items():
        log(f"fit on the {name} days of the 2025 sheet (the 2021 sheet in the latitude stage):")
        fitted[name] = fit({d: r25[d] for d in part}, log, r21)
        log("  " + describe(fitted[name]))
    LATITUDE["free"] = same_side(fitted["odd"]["lat"], fitted["even"]["lat"], CENTRE[0])
    log(f"the halves' latitudes {fitted['odd']['lat']:.5f} and {fitted['even']['lat']:.5f}: "
        + ("the latitude may leave the centre" if LATITUDE["free"] else "not on one side of the centre, which stays"))
    log("fit on the whole 2025 sheet (the 2021 sheet in the latitude stage):")
    final = fit(r25, log, r21)
    log("  " + describe(final))

    held = common.Score()
    for name, other in (("odd", "even"), ("even", "odd")):
        merge(held, score(method_of(fitted[other]), zone_of(fitted[other]), r25, halves[name]))
    log("held out (alternate days of the 2025 sheet), exact/within 1 (%):")
    log("  " + shares(held))
    method, zone = method_of(final), zone_of(final)
    log("in-sample, the 2025 sheet: " + shares(score(method, zone, r25)))
    log("the preset before this fit (at Zagreb's centre), 2025 sheet: " + shares(score(method_of(None), None, r25)))
    log("the September 2021 sheet with the 2025 rules (another year and older rules): " + shares(score(method, zone, r21)))
    p21 = fit(r21)
    log("  the 2021 sheet fitted on its own (Isha there is not Maghrib + 80; its Isha angle below): " + describe(p21))
    MODEL["name"] = p21["model"]
    ivs = []
    for d, v in r21.items():
        pb = common.local_to_epoch(TZ, d, v[5])
        a, b = depression(d, p21["lat"], p21["lng"], pb - 30, p21["evals"]["MAGHRIB"]), depression(d, p21["lat"], p21["lng"], pb + 30, p21["evals"]["MAGHRIB"])
        ivs.append((min(a, b), max(a, b)))
    n, angle = best_cover(ivs, 14.0, 19.0, 17.0)
    MODEL["name"] = "MEEUS"
    log(f"  the 2021 sheet's Isha: {angle:.2f} deg at its point ({n} of {len(r21)} days, NEAREST)")

    # The summer form: F in the engine's terms, and its window from the clamp days under the fitted profile.
    gap = PRINTED_SUMMER_GAP + final["offsets"]["FAJR"] - final["offsets"]["SUNRISE"]
    probe = method_of(final, summer_form(final, ((1, 1), (12, 31)), gap))
    clamps = {year: clamp_days(probe, zone, year) for year in APP_YEARS}
    firsts, lasts = [], []
    for year in APP_YEARS:
        cl = [d for d in clamps[year] if 3 <= d.month <= 9]
        firsts.append((cl[0].month, cl[0].day))
        lasts.append((cl[-1].month, cl[-1].day))
        log(f"  {year}: the angle time comes before sunrise - {PRINTED_SUMMER_GAP} (printed) from {cl[0]} to {cl[-1]}")
    start = min(firsts)
    end = min(max(lasts), LAST_SUMMER_DAY)
    # The form acts only where the angle time comes earlier, so any start after the winter days that need it and up to
    # the first summer day that does gives the same times: the plan's late-March start stays where it lies in that span.
    winter_days = [(d.month, d.day) for year in APP_YEARS for d in clamps[year] if d.month <= 2]
    winter = max(winter_days) if winter_days else None
    start_md = PLAN_SUMMER_START if (winter is None or PLAN_SUMMER_START > winter) and PLAN_SUMMER_START <= start else start
    log(f"  the last winter day (month, day) the angle time comes before sunrise - {PRINTED_SUMMER_GAP}: {winter}; any start "
        f"after it and up to {start} gives the same times in 2026-2029")
    window = (start_md, end)
    summer = summer_form(final, window, gap)
    method = method_of(final, summer)
    log(f"summer form: {window[0][1]} {['', 'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep'][window[0][0]]} to "
        f"{window[1][1]} {['', 'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep'][window[1][0]]}, "
        f"fajrBeforeSunrise {gap} (printed sunrise - {PRINTED_SUMMER_GAP})")

    # Support (5.6): the mosque's calendar, the 2025 vaktija by date, read as 2026 (its own typos counted as misses).
    cal = mosque_calendar()
    sup = common.Score()
    for (m, d), t in sorted(cal.items()):
        if (m, d) == (2, 29):
            continue
        day = datetime.date(2026, m, d)
        out = common.compute(common.case(method, CENTRE[0], CENTRE[1], TZ, day, zone=zone, country="HR"))
        for k, e in enumerate(EVENTS):
            sup.add(e, day, out[e][1] // 60, minutes(day, t[k]))
    log("support: the Zagreb mosque's 2026 calendar (MIZ Zagreb's yearly vaktija by date), the final profile: " + shares(sup))
    by_month = {}
    for e, d, x in sup.misses:
        by_month.setdefault(e, {}).setdefault(int(d[5:7]), 0)
        by_month[e][int(d[5:7])] += 1
    for e in EVENTS:
        log(f"  {e:8} misses beyond 1 min by month: " + " ".join(f"{by_month.get(e, {}).get(m, 0):2d}" for m in range(1, 13)))

    stats = common.fit_stats(
        [common.sample("MIZ Zagreb's Ramadan 2025 sheet", days[0], days[-1], len(days), 1)],
        [common.sample("MIZ Zagreb's Ramadan 2025 sheet, held out by alternate days", days[0], days[-1], len(days), 1)],
        held)
    z = {k: v for k, v in zone.items() if k != "authority"}
    z.update({"nameAr": "زغرب", "namedPlaces": [{"centre": common.point(*CENTRE), "catalogueIds": [], "areaKm": 0.0}], "fit": stats})
    if (final["lat"], final["lng"]) != CENTRE:
        log(f"Zagreb's computing point (E6): {final['lat']:.5f} {final['lng']:.5f}, {km((final['lat'], final['lng']), CENTRE):.1f} km "
            f"from the centre")
    path = common.write_asset(AUTHORITY, AUTHORITY, "HR", "NATIONAL", [z], fit=stats, source=SOURCE_LINE)
    log(f"wrote {path.relative_to(common.ROOT)} in {time.time() - started:.0f} s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
