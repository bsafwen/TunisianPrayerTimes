#!/usr/bin/env python3
"""
Fits `si_islamska_skupnost`, the Islamic Community in Slovenia's vaktija.si (plan 7.4, 7.5, 8.8; milestone M3, 9.4),
on the months its feed has served so far, and writes its official-zone asset,
android-app/app/src/main/assets/official-zones/si_islamska_skupnost.json (schema 1).

Data, read at run time and never copied (D13, A7): vaktija.si's feed (https://www.vaktija.si/api/v2/feed: Ljubljana's
times for the current month and the next two, and one fixed offset per time for five places), as collected:
test-data-local/official/si/feed_<yyyy-mm>.json (the monthly collector) and the research copy of 2026-10-11
(research-2026-10-11/BA/si_vaktija/feed_2026-10-11.json). Copies of the same day must agree.

Ljubljana's table: stages of 9.4 by windows from the preset (19 / 18, sunrise -5, Dhuhr +2, Maghrib +6, the sun at
the event instant, NEAREST) at Ljubljana's centre, for each sun model of E14 (MEEUS unless another covers clearly
more windows over the six times); an alternative must cover more than 0.5 % and at least 2 more windows; one
evaluation for all six (E4) until six months are collected; the latitude leaves the centre only where the fits on both
halves of the months move it to the same side:
0. One evaluation for all six (E4).
1. Dhuhr: rounding, whole-minute offset and longitude (within 0.25 deg of the centre).
2. The latitude (within 0.5 deg) from sunrise, Maghrib and Fajr (not Isha: it is Sarajevo's, below); then sunrise's
   and Maghrib's rounding and offset.
3. Fajr and Isha: rounding, and the angle (to 0.1 deg, offset 0) or the preset's angle with a whole-minute offset.
4. Asr: shadow factor, rounding, offset.
Held out: month-split (fitted on the odd months, scored on the even ones, and the reverse), with common.compute.

The summer form (HighLatitudeRule.SummerForm: 24 April to 23 May, Fajr no earlier than sunrise - 105 and Isha no
later than Maghrib + 90, from the Community's Ramadan 2020 sheet) is not refitted: the feed has served no summer
month yet (8.8); the monthly collector reads them from April 2027.

Usage: python scripts/prayer_formula/fits/si_islamska_skupnost/fit.py
"""

from __future__ import annotations

import datetime
import json
import math
import sys
import time
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import common  # noqa: E402

ref = common.ref
EVENTS = common.EVENTS
AUTHORITY = "si_islamska_skupnost"
TZ = "Europe/Ljubljana"
FEEDS = [common.LOCAL_DATA / "research-2026-10-11" / "BA" / "si_vaktija" / "feed_2026-10-11.json"] + sorted(
    (common.LOCAL_DATA / "official" / "si").glob("feed_*.json"))
OLD_SARAJEVO = common.LOCAL_DATA / "research-2026-10-11" / "BA" / "iz_bih" / "vaktija_499a8ec.json"
CATALOGUE = common.ROOT / "android-app" / "app" / "src" / "main" / "assets" / "places" / "europe-cities.tsv"
BASE = "ljubljana"
CENTRE = (46.05108, 14.50513)              # Ljubljana, GeoNames 3196359 (the catalogue's row)
BOX_LNG, BOX_LAT, LAT_STEP = 0.25, 0.5, 0.005
EVALS = ("EVENT_INSTANT", "DAILY_0H_UT", "DAILY_10H_UT", "KACST")
ASR_EVALS = EVALS + ("LOCAL_MIDNIGHT_SHADOW",)
WINDOW = {"NEAREST": (-30.0, 30.0), "UP": (-60.0, 0.0), "DOWN": (0.0, 60.0)}
MODES = ("NEAREST", "UP", "DOWN")
CLEARLY, MIN_GAIN = 1.005, 2
PER_TIME_EVALUATION_MONTHS = 6
PRESET = {"FAJR": 0, "SUNRISE": -5, "DHUHR": 2, "ASR": 0, "MAGHRIB": 6, "ISHA": 0}   # EuropeanPresets (7.5)
PRESET_ANGLES = {"FAJR": 19.0, "ISHA": 18.0}
PRESET_EVAL = "EVENT_INSTANT"
SUMMER = {"type": "summer_form", "fromMonth": 4, "fromDay": 24, "toMonth": 5, "toDay": 23, "fajrBeforeSunrise": 105,
          "ishaAfterMaghrib": 90}
PLACES = {   # the feed's cityid: (catalogue name, Arabic name where the catalogue has none)
    "celje": ("Celje", None), "jesenice": ("Jesenice", None), "koper": ("Koper", None), "maribor": ("Maribor", None),
    "novo_mesto": ("Novo Mesto", None),
}
SOURCE_LINE = ("The Islamic Community in Slovenia (vaktija.si): Ljubljana's table and the published offsets of Celje, "
               "Jesenice, Koper, Maribor and Novo mesto")


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


# ---- data -------------------------------------------------------------------------------------------------------

def load_feeds():
    """(date -> Ljubljana's six clock times, cityid -> its six offsets): every collected feed, copies agreeing."""
    days, offsets = {}, {}
    for path in FEEDS:
        feed = json.loads(path.read_text(encoding="utf-8"))
        for block in feed["times"]:
            for x in block["days"]:
                day = datetime.date(block["year"], block["month"], x["day"])
                times = [x[k] for k in ("fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha")]
                assert days.setdefault(day, times) == times, (path, day)
        for c in feed["cities"]:
            value = [c[k] for k in ("fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha")]
            assert offsets.setdefault(c["cityid"], value) == value, (path, c["cityid"])
    return days, offsets


def catalogue(names) -> dict[str, tuple[float, float, str, str]]:
    lines = CATALOGUE.read_text(encoding="utf-8").splitlines()
    header = lines[0].split("\t")
    out = {}
    for line in lines[1:]:
        r = dict(zip(header, line.split("\t")))
        if r["country"] == "SI" and r["name"] in names and (r["name"] not in out or int(r["population"] or 0) > out[r["name"]][4]):
            out[r["name"]] = (int(r["latE5"]) / 1e5, int(r["lngE5"]) / 1e5, r["arabic"], r["id"], int(r["population"] or 0))
    return out


# ---- fast raw events (the reference's own primitives) ------------------------------------------------------------

_SUNS: dict = {}
MODELS = ("MEEUS", "VSOP87", "TEXTBOOK", "ALMANAC")   # E14; MEEUS unless another covers clearly more windows
MODEL = {"name": "MEEUS"}                              # the model the stages run with


def sun_of(day, mode):
    key = (day, mode, MODEL["name"])
    if key not in _SUNS:
        cyc = ref.Cycle(day)
        _SUNS[key] = (cyc, ref.SunFunction(ref.SUN_MODELS[MODEL["name"]], mode if mode != "LOCAL_MIDNIGHT_SHADOW" else "EVENT_INSTANT", cyc.jd0))
    return _SUNS[key]


def transit_at(day, lng, mode):
    cyc, sun = sun_of(day, mode)
    return cyc, sun, ref.transit(cyc, sun, lng)


def event_at(day, lat, lng, alt, side, mode):
    cyc, sun, t0 = transit_at(day, lng, mode)
    return cyc.instant(ref.event(cyc, sun, lat, lng, alt, side, t0))


def asr_at(day, lat, lng, mode, factor):
    cyc, sun, t0 = transit_at(day, lng, mode)
    if mode == "LOCAL_MIDNIGHT_SHADOW":
        decl = ref.SUN_MODELS[MODEL["name"]](ref.jd_of_instant(common.local_to_epoch(TZ, day, "0:00")))[0]
    else:
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

def fit(rows: dict[datetime.date, list[str]], say=lambda *_: None) -> dict:
    """The stages for each sun model (E14); MEEUS unless another covers clearly more windows over all six times."""
    results = {}
    for model in MODELS:
        MODEL["name"] = model
        results[model] = fit_model(rows, lambda *_: None)
        results[model]["model"] = model
    totals = {m: sum(r["counts"].values()) for m, r in results.items()}
    best = max(MODELS, key=lambda m: (clearly(totals[m], totals["MEEUS"]) or m == "MEEUS", totals[m]))
    say("  sun models, windows over the six times: " + ", ".join(f"{m} {n}" for m, n in totals.items()) + f": {best}")
    MODEL["name"] = best
    p = fit_model(rows, say)
    p["model"] = best
    MODEL["name"] = "MEEUS"
    return p


def fit_model(rows: dict[datetime.date, list[str]], say=lambda *_: None) -> dict:
    P = {e: [(d, common.local_to_epoch(TZ, d, times[k])) for d, times in sorted(rows.items())] for k, e in enumerate(EVENTS)}
    p = {"evals": {}, "modes": {}, "offsets": {}, "counts": {}}
    lat0, lng0 = CENTRE
    totals = {}
    for ev in EVALS:
        n = 0
        for e in EVENTS:
            for day, pb in P[e]:
                if e == "DHUHR":
                    cyc, _, t0 = transit_at(day, lng0, ev)
                    t = cyc.instant(t0)
                elif e == "ASR":
                    t = asr_at(day, lat0, lng0, ev, 1)
                else:
                    alt, side = {"FAJR": (-PRESET_ANGLES["FAJR"], -1), "SUNRISE": (-0.8333, -1), "MAGHRIB": (-0.8333, 1),
                                 "ISHA": (-PRESET_ANGLES["ISHA"], 1)}[e]
                    t = event_at(day, lat0, lng0, alt, side, ev)
                n += exact(t, PRESET[e], "NEAREST", pb)
        totals[ev] = n
    dev = max(EVALS, key=lambda ev: (clearly(totals[ev], totals[PRESET_EVAL]) or ev == PRESET_EVAL, totals[ev]))
    n_months = len({(d.year, d.month) for d in rows})
    evals = EVALS if n_months >= PER_TIME_EVALUATION_MONTHS else (dev,)
    asr_evals = ASR_EVALS if n_months >= PER_TIME_EVALUATION_MONTHS else (dev,)
    p["stage0"] = dev
    say(f"  stage 0, one evaluation for all six (the preset at Ljubljana's centre): {dev} ("
        + ", ".join(f"{ev} {n}" for ev, n in totals.items()) + f" of {sum(len(v) for v in P.values())})"
        + ("" if len(evals) > 1 else f"; {n_months} month(s): kept for every time"))
    # Stage 1: Dhuhr.
    cands = []
    for ev in evals:
        t0s = []
        for day, pb in P["DHUHR"]:
            cyc, _, t0 = transit_at(day, lng0, ev)
            t0s.append((cyc.instant(t0), pb))
        for mode in MODES:
            lo, hi = WINDOW[mode]
            for off in range(-2, 7):
                ivs = [(lng0 + (t + 60 * off - pb - hi) / 240, lng0 + (t + 60 * off - pb - lo) / 240) for t, pb in t0s]
                n, lng = best_cover(ivs, lng0 - BOX_LNG, lng0 + BOX_LNG, lng0)
                cands.append((n, (ev, mode, off), lng))
    n, (ev, mode, off), lng = pick(cands, (dev, "NEAREST", PRESET["DHUHR"]))
    p["evals"]["DHUHR"], p["modes"]["DHUHR"], p["offsets"]["DHUHR"], p["counts"]["DHUHR"] = ev, mode, off, n
    p["lng"] = round(lng, 5)
    say(f"  stage 1, Dhuhr: {ev} {mode} {off:+d}, longitude {p['lng']:.5f} ({n} of {len(P['DHUHR'])})")

    # Stage 2.
    def horizon_cands(e, side, offs, lat):
        out = []
        for ev in evals:
            ts = [(event_at(day, lat, p["lng"], -0.8333, side, ev), pb) for day, pb in P[e]]
            for mode in MODES:
                for off in offs:
                    out.append((sum(1 for t, pb in ts if exact(t, off, mode, pb)), (ev, mode, off)))
        return out

    def twilight_best(e, side, lat):
        best = 0
        for ev in evals:
            for mode in MODES:
                lo, hi = WINDOW[mode]
                ivs = []
                for day, pb in P[e]:
                    da = depression(day, lat, p["lng"], pb + lo, ev)
                    db = depression(day, lat, p["lng"], pb + hi, ev)
                    ivs.append((min(da, db), max(da, db)))
                best = max(best, best_cover(ivs, PRESET_ANGLES[e] - 3, PRESET_ANGLES[e] + 3, PRESET_ANGLES[e])[0])
        return best

    offs_sr, offs_mg = range(-10, 0), range(1, 11)

    def lat_total(lat):
        # Isha is left out: it is Sarajevo's pre-2025 Isha + 18 min (printed by main), not a twilight at this point.
        return (max(c[0] for c in horizon_cands("SUNRISE", -1, offs_sr, lat)) + max(c[0] for c in horizon_cands("MAGHRIB", 1, offs_mg, lat))
                + twilight_best("FAJR", -1, lat))

    best_lat = None
    for k in range(-round(BOX_LAT / LAT_STEP), round(BOX_LAT / LAT_STEP) + 1, 2):
        lat = lat0 + k * LAT_STEP
        key = (lat_total(lat), -abs(lat - lat0))
        if best_lat is None or key > best_lat[0]:
            best_lat = (key, lat)
    for k in (-1, 1):                     # the odd steps around the best
        lat = best_lat[1] + k * LAT_STEP
        key = (lat_total(lat), -abs(lat - lat0))
        if key > best_lat[0]:
            best_lat = (key, lat)
    at_centre = lat_total(lat0)
    p["lat"] = round(best_lat[1], 5) if clearly(best_lat[0][0], at_centre) and LATITUDE["free"] else lat0
    p["lat_windows"] = f"{best_lat[0][0]} at {best_lat[1]:.5f}, {at_centre} at the centre"
    for e, side, offs in (("SUNRISE", -1, offs_sr), ("MAGHRIB", 1, offs_mg)):
        n, (ev, mode, off) = pick(horizon_cands(e, side, offs, p["lat"]), (dev, "NEAREST", PRESET[e]))
        p["evals"][e], p["modes"][e], p["offsets"][e], p["counts"][e] = ev, mode, off, n
    say(f"  stage 2: latitude {p['lat']:.5f} (sunrise, Maghrib and Fajr windows: {p['lat_windows']}); sunrise "
        f"{p['evals']['SUNRISE']} {p['modes']['SUNRISE']} {p['offsets']['SUNRISE']:+d} ({p['counts']['SUNRISE']}), Maghrib "
        f"{p['evals']['MAGHRIB']} {p['modes']['MAGHRIB']} {p['offsets']['MAGHRIB']:+d} ({p['counts']['MAGHRIB']})")
    # Stage 3.
    for e, side in (("FAJR", -1), ("ISHA", 1)):
        cands = []
        for ev in evals:
            for mode in MODES:
                lo, hi = WINDOW[mode]
                ivs = []
                for day, pb in P[e]:
                    da = depression(day, p["lat"], p["lng"], pb + lo, ev)
                    db = depression(day, p["lat"], p["lng"], pb + hi, ev)
                    ivs.append((min(da, db), max(da, db)))
                _, angle = best_cover(ivs, PRESET_ANGLES[e] - 3, PRESET_ANGLES[e] + 3, PRESET_ANGLES[e])
                rounded = round(angle, 1)
                cands.append((sum(1 for a, b in ivs if a <= rounded < b), (ev, mode, rounded, 0)))
                ts = [(event_at(day, p["lat"], p["lng"], -PRESET_ANGLES[e], side, ev), pb) for day, pb in P[e]]
                for off in range(-6, 7):
                    cands.append((sum(1 for t, pb in ts if exact(t, off, mode, pb)), (ev, mode, PRESET_ANGLES[e], off)))
        default = (dev, "NEAREST", PRESET_ANGLES[e], 0)
        best = max(cands, key=lambda c: (c[0], c[1][3] == 0, c[1][2] == PRESET_ANGLES[e]))
        n0 = next(c[0] for c in cands if c[1] == default)
        n, (ev, mode, angle, off) = best if clearly(best[0], n0) else (n0, default)
        p["evals"][e], p["modes"][e], p["offsets"][e], p["counts"][e] = ev, mode, off, n
        p[e.lower()] = angle
        say(f"  stage 3, {e.lower()}: {ev} {mode} {angle} deg {off:+d} ({n} of {len(P[e])}; the preset {n0})")
    # Stage 4.
    cands = []
    for factor in (1, 2):
        for ev in asr_evals:
            ts = [(asr_at(day, p["lat"], p["lng"], ev, factor), pb) for day, pb in P["ASR"]]
            for mode in MODES:
                for off in range(-4, 5):
                    cands.append((sum(1 for t, pb in ts if exact(t, off, mode, pb)), (factor, ev, mode, off)))
    n, (factor, ev, mode, off) = pick(cands, (1, dev, "NEAREST", PRESET["ASR"]))
    p["factor"] = factor
    p["evals"]["ASR"], p["modes"]["ASR"], p["offsets"]["ASR"], p["counts"]["ASR"] = ev, mode, off, n
    say(f"  stage 4, Asr: factor {factor} {ev} {mode} {off:+d} ({n} of {len(P['ASR'])})")
    return p


# ---- profile, zones, scores ---------------------------------------------------------------------------------------

def method_of(p: dict | None) -> dict:
    if p is None:
        return common.method_json(id=AUTHORITY, fajr={"type": "angle", "degrees": 19.0}, isha={"type": "angle", "degrees": 18.0},
                                  offsets={e: v for e, v in PRESET.items() if v}, highLatitude=SUMMER,
                                  evidence={"kind": "FROM_TABLES", "source": "preset"})
    return common.method_json(
        id=AUTHORITY, fajr={"type": "angle", "degrees": p["fajr"]}, isha={"type": "angle", "degrees": p["isha"]},
        offsets={e: p["offsets"][e] for e in EVENTS if p["offsets"][e]}, rounding={e: p["modes"][e] for e in EVENTS},
        sunEvaluation={e: p["evals"][e] for e in EVENTS if p["evals"][e] != "EVENT_INSTANT"}, asrShadow=p["factor"],
        sunModel=p.get("model", "MEEUS"), highLatitude=SUMMER,
        evidence={"kind": "FROM_TABLES", "source": "vaktija.si (fits/si_islamska_skupnost)"})


def base_zone(p: dict | None) -> dict:
    fields = {"authority": AUTHORITY}
    if p is not None and (p["lat"], p["lng"]) != CENTRE:
        fields["morningPoint"] = common.point(p["lat"], p["lng"])
        fields["eveningPoint"] = common.point(p["lat"], p["lng"])
    return common.zone_json(BASE, CENTRE[0], CENTRE[1], **fields)


def score(method, zone, base, point, rows, months=None, shift=(0,) * 6) -> common.Score:
    sc = common.Score()
    for d, times in sorted(rows.items()):
        if months is not None and d.month not in months:
            continue
        out = common.compute(common.case(method, point[0], point[1], TZ, d, zone=zone, base_zone=base, country="SI"))
        for k, e in enumerate(EVENTS):
            sc.add(e, d, out[e][1] // 60, common.local_to_epoch(TZ, d, times[k]) // 60 + shift[k])
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


def describe(p: dict) -> str:
    return (f"{p.get('model', 'MEEUS')} point {p['lat']:.5f},{p['lng']:.5f} Fajr {p['fajr']} Isha {p['isha']} Asr factor {p['factor']}; "
            + ", ".join(f"{e[:4]} {p['evals'][e]} {p['modes'][e]} {p['offsets'][e]:+d}" for e in EVENTS))


def km(a, b) -> float:
    return math.hypot((a[0] - b[0]) * 111.2, (a[1] - b[1]) * 111.2 * math.cos(math.radians(b[0])))


def main() -> int:
    started = time.time()
    rows, offsets = load_feeds()
    months = sorted({(d.year, d.month) for d in rows})
    log(f"si_islamska_skupnost: {len(rows)} days of Ljubljana from {len(FEEDS)} feed copies "
        f"({', '.join(f'{y}-{m:02d}' for y, m in months)}); places: "
        + ", ".join(f"{c} {v}" for c, v in sorted(offsets.items())))
    # Isha against the IZ BiH's Sarajevo table before its 2024-12-30 change (vaktija.ba, CET), summer time added.
    old = json.loads(OLD_SARAJEVO.read_text(encoding="utf-8"))
    from zoneinfo import ZoneInfo
    tz = ZoneInfo(TZ)
    gaps = Counter()
    for d, times in rows.items():
        dst = int(datetime.datetime(d.year, d.month, d.day, 12, tzinfo=tz).dst().total_seconds() // 60)
        sarajevo_isha = old["vaktija"]["months"][d.month - 1]["days"][d.day - 1]["vakat"][5] // 60 + dst
        gaps[int(times[5][:2]) * 60 + int(times[5][3:]) - sarajevo_isha] += 1
    log(f"Isha minus the pre-2025 Sarajevo Isha (vaktija.ba, no town difference): {sorted(gaps.items(), key=lambda t: -t[1])}")

    month_numbers = sorted({d.month for d in rows})
    halves = {"odd": [m for m in month_numbers if m % 2 == 1], "even": [m for m in month_numbers if m % 2 == 0]}
    fitted = {}
    for name, part in halves.items():
        log(f"fit on the {name} months {part}:")
        fitted[name] = fit({d: v for d, v in rows.items() if d.month in part}, log)
        log("  " + describe(fitted[name]))
    LATITUDE["free"] = same_side(fitted["odd"]["lat"], fitted["even"]["lat"], CENTRE[0])
    log(f"the halves' latitudes {fitted['odd']['lat']:.5f} and {fitted['even']['lat']:.5f}: "
        + ("the latitude may leave the centre" if LATITUDE["free"] else "not on one side of the centre, which stays"))
    log("fit on all months:")
    final = fit(rows, log)
    log("  " + describe(final))
    log(f"  ({time.time() - started:.0f} s)")

    held = common.Score()
    for name, other in (("odd", "even"), ("even", "odd")):
        merge(held, score(method_of(fitted[other]), base_zone(fitted[other]), None, CENTRE, rows, halves[name]))
    log("held out (month-split), Ljubljana, exact/within 1 (%):")
    log("  " + shares(held))
    method, zone = method_of(final), base_zone(final)
    log("in-sample, Ljubljana: " + shares(score(method, zone, None, CENTRE, rows)))
    log("the preset before this fit (at Ljubljana's centre): " + shares(score(method_of(None), None, None, CENTRE, rows)))
    misses = sorted(held.misses)
    log(f"  held-out misses beyond 1 min: {len(misses)}" + (": " + ", ".join(f"{d} {e[:4]} {x:+d}" for e, d, x in misses[:12]) if misses else ""))

    # The five places: Ljubljana's times plus their offsets (the zones), scored against Ljubljana's table plus the offset.
    cat = catalogue({v[0] for v in PLACES.values()} | {"Ljubljana"})
    first, last = min(rows), max(rows)
    source = f"vaktija.si's feed, {', '.join(f'{y}-{m:02d}' for y, m in months)}"
    stats = common.fit_stats([common.sample(source + ": Ljubljana", first, last, len(rows), 1)],
                             [common.sample(source + ": Ljubljana, held out by month", first, last, len(rows), 1, month_split=True)],
                             held)
    zones = []
    z = {k: v for k, v in zone.items() if k != "authority"}
    z.update({"nameAr": cat["Ljubljana"][2] or "ليوبليانا", "namedPlaces": [{"centre": common.point(*CENTRE), "catalogueIds": [], "areaKm": 0.0}],
              "fit": stats})
    zones.append(z)
    profile = common.Score()
    merge(profile, held)
    for cid, (cname, arabic) in PLACES.items():
        lat, lng, ar, gid, _ = cat[cname]
        shift = offsets[cid]
        zone_place = common.zone_json(cid.replace("_", "-"), lat, lng, authority=AUTHORITY, baseZoneId=BASE,
                                      offsets={e: float(v) for e, v in zip(EVENTS, shift) if v})
        for name, other in (("odd", "even"), ("even", "odd")):
            merge(profile, score(method_of(fitted[other]), zone_place, base_zone(fitted[other]), (lat, lng), rows, halves[name], shift))
        place = {k: v for k, v in zone_place.items() if k != "authority"}
        place.update({"nameAr": ar or arabic, "namedPlaces": [{"centre": common.point(lat, lng), "catalogueIds": [], "areaKm": 0.0}],
                      "fit": stats})
        zones.append(place)
        log(f"  {cid}: {', '.join(f'{e[:4]} {v:+d}' for e, v in zip(EVENTS, shift))}; point {lat:.5f} {lng:.5f} (GeoNames {gid}), "
            f"{km((lat, lng), CENTRE):.0f} km from Ljubljana")
    log("profile (Ljubljana and the five places, held out by month): " + shares(profile))
    profile_stats = common.fit_stats(
        [common.sample(source + ": Ljubljana and the five places' offsets", first, last, len(rows), len(zones))],
        [common.sample(source + ": every place, held out by month", first, last, len(rows), len(zones), month_split=True)],
        profile)
    if (final["lat"], final["lng"]) != CENTRE:
        log(f"Ljubljana's computing point (E6): {final['lat']:.5f} {final['lng']:.5f}, {km((final['lat'], final['lng']), CENTRE):.1f} km "
            f"from the centre")
    path = common.write_asset(AUTHORITY, AUTHORITY, "SI", "LISTED_PLACES", zones, fit=profile_stats, source=SOURCE_LINE)
    log(f"wrote {path.relative_to(common.ROOT)} ({len(zones)} zones) in {time.time() - started:.0f} s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
