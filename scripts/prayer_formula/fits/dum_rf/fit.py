#!/usr/bin/env python3
"""
Fits `dum_rf`, the monthly Moscow tables of the Spiritual Administration of Muslims of the Russian Federation (DUM RF;
plan 7.4, 7.5, 6.5, 9.2, Q22; milestone M3: "its rounding per event, 7.4, and its Moscow zone's point, 6.5"), and
writes its official-zone asset, android-app/app/src/main/assets/official-zones/dum_rf.json (schema 1): one NATIONAL
zone «موسكو» for Moscow and Moscow Oblast (GeoNames admin1 48 and 47), as its sheet («по Москве и Московской области»)
says.

Data, read at run time from test-data-local/research-2026-10-16/RU/dum_rf/ and never copied (D13, A7), fetched by
fetch.py; each sheet is an image, transcribed by eye into a JSON beside it (checked on enlarged crops of each half of
the table and for smooth day-to-day steps in every column):
- live/namaz_MM_live-2026-10-16.jpg: dumrf.ru/img/namaz/MM.jpg on 2026-10-16: March to August, October and November
  2026 (01, 02, 09 and 12 answer 404). Six of them have the same bytes as the Wayback Machine's 2026 captures.
- wayback/namaz_MM_<capture>.jpg: the Wayback Machine's copies (id_, original bytes, SHA-1 equal to its CDX listing,
  wayback/cdx_img_namaz.txt): January and February 2026; February, June, July, August, October, November and December
  2025 (December twice: the second a smaller export of the same sheet, left out).

What the tables are. One perpetual table: every 2025 sheet in hand equals the 2026 sheet of its month on every time
(only weekdays and Hijri dates change), except the Isha of July and of 1-2 August, where DUM RF set its summer ramp
by hand in each year. The reference-year scan (E5) finds the 2025 sun, clearly: the table was computed for 2025 and
printed again for 2026. So the stages run at 2025 and every score is computed for the actual year of the sheet, as the
app shows it (A8): the 2026 sheets differ from the 2026 sun by up to a minute in spring and autumn.

Stages (9.4) at the reference year at Moscow's centre (GeoNames 524901), by windows; an alternative to the default
(the preset's value; NEAREST and offset 0 for a rounding) must cover more than 0.5 % and at least 2 more windows:
0. One evaluation for all six (E4).
1. Dhuhr: rounding, whole-minute offset, longitude (within 0.1 deg).
2. Sunrise and sunset: the horizon (the standard -0.8333 deg, each one's best fixed altitude reported), the latitude
   jointly with Fajr's and Isha's angle windows (within 0.1 deg; it leaves the centre only where the fits on both halves
   of the months move it to the same side), then each one's rounding and offset.
3. Fajr and Isha on the nights DUM RF's caps leave alone: rounding, and the angle the windows give.
4. Asr: shadow factor, evaluation, the sun (geometric or apparent, E13), rounding, offset.
5. The caps of 7.4 (Fajr at 0.30 of the night, Isha at DUM RF's 2026 ramp), checked with the engine's nights
   (N(D - 1) for Fajr, N(D) for Isha) and with the night the tables use, 24 h less the date's own day (a proposed
   engine change, scored only with --proposed-night; the asset is always written with the engine as it is).
Held out: the stages refitted on the odd months score the even months, and the reverse (a month split), every sheet
(2025 and 2026) at its own date, with common.compute (the reference engine).

Usage: python scripts/prayer_formula/fits/dum_rf/fit.py [--proposed-night | --no-asset]
(then python scripts/places/match_named_places.py, which fills the named place's catalogue row)
"""

from __future__ import annotations

import argparse
import datetime
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import common  # noqa: E402

ref = common.ref
EVENTS = common.EVENTS
AUTHORITY = "dum_rf"
TZ = "Europe/Moscow"
DATA = common.LOCAL_DATA / "research-2026-10-16" / "RU" / "dum_rf"
CENTRE = (55.75204, 37.61781)               # Moscow, GeoNames 524901 (the place catalogue's row)
ADMIN1 = ["48", "47"]                       # GeoNames admin1: Moscow city, Moscow Oblast (CountryMethodDefaults)
BOX, LAT_STEP = 0.1, 0.005
MODES = ("NEAREST", "UP", "DOWN")
WINDOW = {"NEAREST": (-30.0, 30.0), "UP": (-60.0, 0.0), "DOWN": (0.0, 60.0)}
CLEARLY, MIN_GAIN = 1.005, 2
EVALS = ("EVENT_INSTANT", "DAILY_0H_UT", "DAILY_10H_UT", "KACST")
ASR_EVALS = EVALS + ("SHADOW_AT_DHUHR", "SHADOW_AT_EVENT")
YEARS = (2017, 2021, 2022, 2023, 2024, 2025, 2026, 2027, 2029)
DUPLICATE = "namaz_12_20251203025307.json"   # the same December 2025 sheet, a smaller export
PRESET = {
    "fajr": 18.0, "isha": 15.0, "factor": 1, "asr_sun": "GEOMETRIC",
    "evals": {e: "EVENT_INSTANT" for e in EVENTS},
    "offsets": {"FAJR": 0, "SUNRISE": -5, "DHUHR": 5, "ASR": 0, "MAGHRIB": 5, "ISHA": 0},
    "rounding": {"FAJR": "UP", "SUNRISE": "UP", "DHUHR": "NEAREST", "ASR": "NEAREST", "MAGHRIB": "DOWN", "ISHA": "DOWN"},
    "lat": CENTRE[0], "lng": CENTRE[1],
}
SOURCE_LINE = ("The Spiritual Administration of Muslims of the Russian Federation (dumrf.ru), «Время намазов по Москве "
               "и Московской области»: one monthly table for Moscow and Moscow Oblast")
PROPOSED = {"night": False}


def log(text: str = "") -> None:
    print(text, flush=True)


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")


# ---- the proposed engine change (scored only with --proposed-night) --------------------------------------------------

_engine_rule = ref.CaseEngine.high_latitude


def _high_latitude(self, rule, site, day, name, t, maghrib):
    """DumRf with the night the tables use: 24 h less the date's own day (sunset(D) - sunrise(D)), for Fajr and Isha."""
    if PROPOSED["night"] and rule["type"] == "dum_rf":
        steps = self.day_steps(site, day)
        if steps.sunrise is None or steps.sunset is None:
            return t
        night = 86400 - (steps.sunset - steps.sunrise)
        if name == "ISHA":
            return ref.earlier(t, steps.sunset + ref.dum_rf_share(day) * night)
        return ref.later(t, steps.sunrise - 0.30 * night)
    return _engine_rule(self, rule, site, day, name, t, maghrib)


ref.CaseEngine.high_latitude = _high_latitude


# ---- data -------------------------------------------------------------------------------------------------------

def load_sheets() -> dict[tuple[int, int], dict]:
    """(year, month) -> {"rows": date -> six printed times, "file": the transcription}."""
    out = {}
    for path in sorted(DATA.glob("*/*.json")):
        if path.name == DUPLICATE:
            continue
        d = json.loads(path.read_text(encoding="utf-8"))
        assert (path.parent / d["image"]).exists(), path
        key = (d["year"], d["month"])
        assert key not in out, key
        out[key] = {"rows": {datetime.date(d["year"], d["month"], r[0]): r[1:] for r in d["rows"]}, "file": path}
    return out


def published(day: datetime.date, event: str, hhmm: str) -> int:
    return common.local_to_epoch(TZ, day, hhmm) // 60


def smoothness(sheets: dict) -> list[str]:
    """Columns whose day-to-day step jumps against both neighbours (a misread digit would); the ramp's blocks expected."""
    flags = []
    for (y, mo), s in sorted(sheets.items()):
        rows = [s["rows"][d] for d in sorted(s["rows"])]
        for k, e in enumerate(EVENTS):
            mins = [int(t[k][:2]) * 60 + int(t[k][3:]) for t in rows]
            steps = [b - a for a, b in zip(mins, mins[1:])]
            for i in range(1, len(steps) - 1):
                if abs(steps[i] - steps[i - 1]) >= 2 and abs(steps[i] - steps[i + 1]) >= 2:
                    flags.append(f"{y}-{mo:02d} {e[:4]} day {i + 2}")
    return flags


# ---- the profile and the engine ------------------------------------------------------------------------------------

def method_of(p: dict) -> dict:
    return common.method_json(
        id=AUTHORITY, fajr={"type": "angle", "degrees": p["fajr"]}, isha={"type": "angle", "degrees": p["isha"]},
        offsets={e: v for e, v in p["offsets"].items() if v}, rounding=dict(p["rounding"]),
        sunEvaluation={e: v for e, v in p["evals"].items() if v != "EVENT_INSTANT"},
        asrShadow=p["factor"], asrSun=p["asr_sun"], highLatitude={"type": "dum_rf"},
        evidence={"kind": "FROM_TABLES", "source": "DUM RF's monthly Moscow tables (fits/dum_rf)"},
    )


def zone_of(p: dict) -> dict | None:
    """The one zone: Moscow's centre as its point, the fitted point (when it moved) as its E6 points."""
    fields = {"authority": AUTHORITY}
    if (round(p["lat"], 5), round(p["lng"], 5)) != CENTRE:
        fields["morningPoint"] = common.point(p["lat"], p["lng"])
        fields["eveningPoint"] = common.point(p["lat"], p["lng"])
    return common.zone_json("moscow", CENTRE[0], CENTRE[1], **fields)


def with_(p: dict, **changes) -> dict:
    q = {**p, "offsets": dict(p["offsets"]), "rounding": dict(p["rounding"]), "evals": dict(p["evals"])}
    for k, v in changes.items():
        if k in ("offsets", "rounding", "evals"):
            q[k].update(v)
        else:
            q[k] = v
    return q


def computed(p: dict, day: datetime.date, year: int | None, zone=True) -> dict:
    """The reference's day at [year]'s date (the sheet's own when None), its instants moved to [day]."""
    other = day if year is None else day.replace(year=year)
    z = zone_of(p) if zone else None
    lat, lng = (CENTRE if zone else (p["lat"], p["lng"]))
    out = common.compute(common.case(method_of(p), lat, lng, TZ, other, zone=z, country="RU"))
    shift = 86400 * (day - other).days
    return {e: (None if raw is None else raw + shift, None if mi is None else mi + shift, st) for e, (raw, mi, st) in out.items()}


def unrounded(p: dict, rows: dict, year: int | None) -> list[tuple]:
    out = []
    for day, times in sorted(rows.items()):
        res = computed(p, day, year)
        for k, e in enumerate(EVENTS):
            out.append((day, e, res[e][0], res[e][2], published(day, e, times[k])))
    return out


def clearly(best: int, default: int) -> bool:
    return best > default * CLEARLY and best >= default + MIN_GAIN


def pick(cands: list[tuple], default_key) -> tuple:
    best = max(cands, key=lambda c: c[0])
    default = next(c for c in cands if c[1] == default_key)
    return best if clearly(best[0], default[0]) else default


def round_offset(times: list[tuple], e: str, base_offset: int, default: tuple, offsets=range(-2, 3), only=None) -> tuple:
    cands = []
    for mode in MODES:
        for k in offsets:
            n = sum(1 for d, ev, t, st, pb in times if ev == e and t is not None and (only is None or only(d, st))
                    and ref.round_instant(t + 60 * k, mode) // 60 == pb)
            cands.append((n, (mode, base_offset + k)))
    return pick(cands, default), cands


# ---- windows with the reference's primitives ------------------------------------------------------------------------

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
    a, b = min(plateaus, key=lambda q: min(abs(q[0] - prefer), abs(q[1] - prefer)))
    return best, (a + b) / 2


_SUNS: dict = {}


def sun_on(day: datetime.date, year: int, mode: str = "EVENT_INSTANT"):
    key = (day, year, mode)
    if key not in _SUNS:
        other = day.replace(year=year)
        cyc = ref.Cycle(other)  # U = D at Moscow
        _SUNS[key] = (cyc, ref.SunFunction(ref.SUN_MODELS["MEEUS"], mode, cyc.jd0), 86400 * (day - other).days)
    return _SUNS[key]


def dhuhr_longitude(rows: dict, year: int, p: dict) -> tuple:
    """(windows, longitude, windows at the centre's longitude)."""
    lo, hi = WINDOW[p["rounding"]["DHUHR"]]
    lng0 = CENTRE[1]
    ivs = []
    for day, times in rows.items():
        cyc, sun, shift = sun_on(day, year, p["evals"]["DHUHR"])
        t = cyc.instant(ref.transit(cyc, sun, lng0)) + shift + 60 * p["offsets"]["DHUHR"]
        pb = published(day, "DHUHR", times[2]) * 60
        ivs.append((lng0 + (t - pb - hi) / 240, lng0 + (t - pb - lo) / 240))
    n, lng = best_cover(ivs, lng0 - BOX, lng0 + BOX, lng0)
    return n, lng, sum(1 for a, b in ivs if a <= lng0 < b)


def latitude_windows(rows: dict, year: int, p: dict, uncapped: dict, lat: float) -> int:
    """Windows of sunrise, Maghrib and the uncapped Fajr and Isha at a latitude, at p's longitude."""
    lng = p["lng"]
    n = 0
    for day, times in rows.items():
        cyc, sun, shift = sun_on(day, year)
        t0 = ref.transit(cyc, sun, lng)
        for e, alt, side, k in (("SUNRISE", -0.8333, -1, 1), ("MAGHRIB", -0.8333, 1, 4), ("FAJR", -p["fajr"], -1, 0),
                                ("ISHA", -p["isha"], 1, 5)):
            if e in ("FAJR", "ISHA") and not uncapped[(day, e)]:
                continue
            h = ref.event(cyc, sun, lat, lng, alt, side, t0)
            if h is None:
                continue
            t = cyc.instant(h) + shift + 60 * p["offsets"][e]
            n += ref.round_instant(t, p["rounding"][e]) // 60 == published(day, e, times[k])
    return n


def horizon_best(rows: dict, year: int, p: dict) -> dict:
    out = {}
    for e, side, k in (("SUNRISE", -1, 1), ("MAGHRIB", 1, 4)):
        scores = []
        for i in range(0, 31):
            alt = round(-0.70 - 0.01 * i, 2)
            n = 0
            for day, times in rows.items():
                cyc, sun, shift = sun_on(day, year)
                t0 = ref.transit(cyc, sun, p["lng"])
                t = cyc.instant(ref.event(cyc, sun, p["lat"], p["lng"], alt, side, t0)) + shift + 60 * p["offsets"][e]
                n += ref.round_instant(t, p["rounding"][e]) // 60 == published(day, e, times[k])
            scores.append((n, alt))
        out[e] = max(scores, key=lambda s: (s[0], -abs(s[1] + 0.8333)))
    return out


def angle_windows(rows: dict, year: int, p: dict, e: str, mode: str, uncapped: dict) -> tuple:
    lo, hi = WINDOW[mode]
    k = EVENTS.index(e)
    ivs = []
    for day, times in rows.items():
        if not uncapped[(day, e)]:
            continue
        cyc, sun, shift = sun_on(day, year)
        pb = published(day, e, times[k]) * 60 - shift - 60 * p["offsets"][e]
        a = -ref.altitude(cyc, sun, p["lat"], p["lng"], cyc.hours(pb + lo))
        b = -ref.altitude(cyc, sun, p["lat"], p["lng"], cyc.hours(pb + hi))
        ivs.append((min(a, b), max(a, b)))
    n, angle = best_cover(ivs, p[e.lower()] - 2, p[e.lower()] + 2, p[e.lower()])
    return n, angle, len(ivs)


# ---- the stages ---------------------------------------------------------------------------------------------------

def fit(rows: dict, year: int, say=lambda *_: None, latitude_free: bool = True) -> dict:
    p = with_(PRESET, rounding={e: "NEAREST" for e in EVENTS})
    defaults = {e: ("NEAREST", PRESET["offsets"][e]) for e in EVENTS}
    # Stage 0: one evaluation for all six.
    totals = {}
    for ev in EVALS:
        times = unrounded(with_(p, evals={e: ev for e in EVENTS}), rows, year)
        totals[ev] = sum(1 for d, e, t, st, pb in times if t is not None and ref.round_instant(t, "NEAREST") // 60 == pb)
    dev = max(EVALS, key=lambda ev: (clearly(totals[ev], totals["EVENT_INSTANT"]) or ev == "EVENT_INSTANT", totals[ev]))
    p["evals"] = {e: dev for e in EVENTS}
    say(f"    stage 0: {dev} (" + ", ".join(f"{ev} {n}" for ev, n in totals.items()) + f" of {6 * len(rows)})")
    times = unrounded(p, rows, year)
    # Stage 1: Dhuhr.
    (n, (mode, off)), _ = round_offset(times, "DHUHR", p["offsets"]["DHUHR"], defaults["DHUHR"])
    p["rounding"]["DHUHR"], p["offsets"]["DHUHR"] = mode, off
    n_lng, lng, at_centre = dhuhr_longitude(rows, year, p)
    p["lng_best"] = round(lng, 5)
    p["lng"] = p["lng_best"] if clearly(n_lng, at_centre) else CENTRE[1]
    say(f"    stage 1, Dhuhr: {mode} {off:+d} ({n} of {len(rows)}), longitude {p['lng']:.5f} (windows {n_lng} at "
        f"{p['lng_best']:.5f}, {at_centre} at the centre)")
    # Which Fajr and Isha are the angle's (the caps leave them alone) under the preset rule at this year.
    uncapped = {(d, e): st == "COMPUTED" for d, e, t, st, pb in times if e in ("FAJR", "ISHA")}
    # Stage 2: latitude jointly from sunrise, Maghrib and the uncapped Fajr and Isha; then the rules of sunrise and Maghrib.
    for e in ("SUNRISE", "MAGHRIB"):
        (n, (mode, off)), _ = round_offset(times, e, p["offsets"][e], defaults[e])
        p["rounding"][e], p["offsets"][e] = mode, off
    steps = int(round(BOX / LAT_STEP))
    lats = [(latitude_windows(rows, year, p, uncapped, CENTRE[0] + k * LAT_STEP), CENTRE[0] + k * LAT_STEP) for k in range(-steps, steps + 1)]
    best_n, best_lat = max(lats, key=lambda c: (c[0], -abs(c[1] - CENTRE[0])))
    at_centre = next(n for n, lat in lats if abs(lat - CENTRE[0]) < 1e-9)
    p["lat_best"] = round(best_lat, 5)
    p["lat"] = p["lat_best"] if latitude_free and clearly(best_n, at_centre) else CENTRE[0]
    times = unrounded(p, rows, year)
    hz = horizon_best(rows, year, p)
    say(f"    stage 2: latitude {p['lat']:.5f} (windows {best_n} at {best_lat:.5f}, {at_centre} at the centre); "
        f"sunrise {p['rounding']['SUNRISE']} {p['offsets']['SUNRISE']:+d}, Maghrib {p['rounding']['MAGHRIB']} "
        f"{p['offsets']['MAGHRIB']:+d}; best fixed horizons: sunrise {hz['SUNRISE'][1]:+.2f} ({hz['SUNRISE'][0]}), "
        f"Maghrib {hz['MAGHRIB'][1]:+.2f} ({hz['MAGHRIB'][0]}); the standard -0.8333 kept")
    # Stage 3: Fajr and Isha on uncapped nights.
    for e in ("FAJR", "ISHA"):
        (n, (mode, off)), _ = round_offset(times, e, 0, ("NEAREST", 0), offsets=(0,), only=lambda d, st, e=e: uncapped[(d, e)])
        p["rounding"][e] = mode
        nw, angle, total = angle_windows(rows, year, p, e, mode, uncapped)
        say(f"    stage 3, {e.lower()}: {mode} at {p[e.lower()]} deg ({n} of {total} uncapped); the windows' own angle {angle:.3f} ({nw})")
    # Stage 4: Asr.
    cands = []
    for factor in (1, 2):
        for asr_sun in ("GEOMETRIC", "APPARENT"):
            for ev in ASR_EVALS:
                q = with_(p, factor=factor, asr_sun=asr_sun, evals={"ASR": ev})
                t_asr = [r for r in unrounded(q, rows, year) if r[1] == "ASR"]
                for mode in MODES:
                    for k in (-1, 0, 1):
                        n = sum(1 for _, _, t, _, pb in t_asr if t is not None and ref.round_instant(t + 60 * k, mode) // 60 == pb)
                        cands.append((n, (factor, asr_sun, ev, mode, p["offsets"]["ASR"] + k)))
    n, (factor, asr_sun, ev, mode, off) = pick(cands, (1, "GEOMETRIC", dev, "NEAREST", 0))
    p.update(factor=factor, asr_sun=asr_sun)
    p["evals"]["ASR"], p["rounding"]["ASR"], p["offsets"]["ASR"] = ev, mode, off
    say(f"    stage 4, Asr: factor {factor} {asr_sun} {ev} {mode} {off:+d} ({n} of {len(rows)})")
    return p


def caps_check(rows: dict, year: int, p: dict) -> list[str]:
    """Stage 5: exact Fajr and Isha on capped nights with the engine's nights and with the date's own night."""
    out = []
    for proposed in (False, True):
        PROPOSED["night"], saved = proposed, PROPOSED["night"]
        n = {"FAJR": [0, 0], "ISHA": [0, 0]}
        for day, times in sorted(rows.items()):
            res = computed(p, day, year)
            for e, k in (("FAJR", 0), ("ISHA", 5)):
                if res[e][2] == "ESTIMATED":
                    n[e][1] += 1
                    n[e][0] += res[e][1] // 60 == published(day, e, times[k])
        PROPOSED["night"] = saved
        label = "the night 24 h less the date's day" if proposed else "the engine's nights N(D - 1) and N(D)"
        out.append(f"{label}: Fajr {n['FAJR'][0]} of {n['FAJR'][1]}, Isha {n['ISHA'][0]} of {n['ISHA'][1]} capped nights exact")
    return out


# ---- scores, asset ------------------------------------------------------------------------------------------------

def score(p: dict, sheets: dict, months=None, year: int | None = None) -> common.Score:
    sc = common.Score()
    for (y, mo), s in sorted(sheets.items()):
        if months is not None and mo not in months:
            continue
        for day, times in sorted(s["rows"].items()):
            res = computed(p, day, year)
            for k, e in enumerate(EVENTS):
                sc.add(e, day, res[e][1] // 60, published(day, e, times[k]))
    return sc


def merge(into: common.Score, sc: common.Score) -> None:
    for e in EVENTS:
        into.exact[e] += sc.exact[e]
        into.within1[e] += sc.within1[e]
        into.total[e] += sc.total[e]
        for k in range(12):
            into.months[e][k] += sc.months[e][k]
    into.worst = max(into.worst, sc.worst)
    into.misses += sc.misses


def shares(sc: common.Score) -> str:
    ex, w1 = sc.shares(), sc.within1_shares()
    return " ".join(f"{e[:4]} {100 * ex[e]:5.1f}/{100 * w1[e]:5.1f}" for e in EVENTS) + f"  max {sc.worst}"


def describe(p: dict) -> str:
    return (f"point {p['lat']:.5f},{p['lng']:.5f} Fajr {p['fajr']} Isha {p['isha']} Asr factor {p['factor']} {p['asr_sun']}; "
            + ", ".join(f"{e[:4]} {p['evals'][e]} {p['rounding'][e]} {p['offsets'][e]:+d}" for e in EVENTS))


def kotlin(p: dict) -> str:
    changed = []
    if p["rounding"] != PRESET["rounding"]:
        extra = [f"TimeEvent.{e} to Rounding.{m}" for e, m in p["rounding"].items() if m != "NEAREST"]
        changed.append("rounding = " + ("Rounding.ALL_NEAREST" + (" + mapOf(" + ", ".join(extra) + ")" if extra else "")))
    evals = {e: v for e, v in p["evals"].items() if v != "EVENT_INSTANT"}
    if evals:
        changed.append("sunEvaluation = mapOf(" + ", ".join(f"TimeEvent.{e} to SunEvaluation.{v}" for e, v in evals.items()) + ")")
    if p["offsets"] != PRESET["offsets"]:
        changed.append("offsets = mapOf(" + ", ".join(f"TimeEvent.{e} to {v}" for e, v in p["offsets"].items() if v) + ")")
    if p["asr_sun"] != PRESET["asr_sun"]:
        changed.append(f"asrSun = AsrSun.{p['asr_sun']}")
    if p["factor"] != PRESET["factor"]:
        changed.append(f"asrShadow = {p['factor']}")
    return "; ".join(changed) or "(no change)"


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--proposed-night", action="store_true", help="score with the proposed DumRf night (no asset written)")
    parser.add_argument("--no-asset", action="store_true", help="report only; leave the asset as it is")
    args = parser.parse_args(argv)
    started = time.time()
    sheets = load_sheets()
    log("dum_rf: " + ", ".join(f"{y}-{m:02d}" for y, m in sorted(sheets)) + f" ({sum(len(s['rows']) for s in sheets.values())} days)")
    flags = smoothness(sheets)
    log("transcriptions: every column steps smoothly but " + (", ".join(flags) if flags else "none")
        + " (Isha: the 3-day blocks of DUM RF's 2026 ramp)")
    # The 2025 sheets against the 2026 sheets of the same month.
    for (y, mo), s in sorted(sheets.items()):
        if y != 2025 or (2026, mo) not in sheets:
            continue
        other = sheets[(2026, mo)]["rows"]
        diff = [(d.day, EVENTS[k][:4]) for d, t in s["rows"].items() for k in range(6) if t[k] != other[d.replace(year=2026)][k]]
        log(f"  {y}-{mo:02d} against 2026-{mo:02d}: " + ("equal on every time" if not diff else
                                                       f"{len(diff)} times differ: " + ", ".join(f"{d}/{e}" for d, e in diff[:12])
                                                       + (" ..." if len(diff) > 12 else "")))
    distinct = {k: s for k, s in sheets.items() if k[0] == 2026 or (2026, k[1]) not in sheets}
    rows = {}
    for s in distinct.values():
        rows.update(s["rows"])
    log(f"the distinct sheets: " + ", ".join(f"{y}-{m:02d}" for y, m in sorted(distinct)) + f" ({len(rows)} days)")

    # The reference year (E5): the preset's angles, offsets and caps, each time at its best rounding.
    PROPOSED["night"] = args.proposed_night
    totals = []
    for year in YEARS:
        times = unrounded(with_(PRESET, rounding={e: "NEAREST" for e in EVENTS}), rows, year)
        n = 0
        for e in EVENTS:
            n += max(sum(1 for d, ev, t, st, pb in times if ev == e and t is not None and ref.round_instant(t, mode) // 60 == pb)
                     for mode in MODES)
        totals.append((n, year))
    ref_year = max(totals)[1]
    log("reference year (windows of the six times, each at its best rounding): "
        + ", ".join(f"{y} {n}" for n, y in sorted(totals, reverse=True)))

    halves = {"odd": [m for m in range(1, 13) if m % 2], "even": [m for m in range(1, 13) if not m % 2]}
    fitted = {}
    for name, months in halves.items():
        log(f"stages at {ref_year} on the {name} months:")
        fitted[name] = fit({d: v for d, v in rows.items() if d.month in months}, ref_year, log)
    free = (fitted["odd"]["lat_best"] - CENTRE[0]) * (fitted["even"]["lat_best"] - CENTRE[0]) > 0
    log(f"the halves' best latitudes {fitted['odd']['lat_best']:.5f} and {fitted['even']['lat_best']:.5f}: "
        + ("on one side of the centre" if free else "not on one side of the centre, which stays"))
    log(f"stages at {ref_year} on all months:")
    final = fit(rows, ref_year, log, latitude_free=free)
    log("  " + describe(final))
    again = []
    for year in YEARS:
        times = unrounded(final, rows, year)
        again.append((sum(1 for d, e, t, st, pb in times if t is not None and ref.round_instant(t, final["rounding"][e]) // 60 == pb), year))
    log("  the reference year again, with the fitted profile (exact of the six times): "
        + ", ".join(f"{y} {n}" for n, y in sorted(again, reverse=True)))
    log("  stage 5, the caps at the reference year:")
    for line in caps_check(rows, ref_year, final):
        log("    " + line)
    log(f"  ({time.time() - started:.0f} s)")

    # Held out: month split, every sheet at its own date (A8).
    held = common.Score()
    for name, other in (("odd", "even"), ("even", "odd")):
        merge(held, score(fitted[other], sheets, halves[name]))
    held_2026 = common.Score()
    for name, other in (("odd", "even"), ("even", "odd")):
        merge(held_2026, score(fitted[other], {k: s for k, s in sheets.items() if k[0] == 2026}, halves[name]))
    log("held out (month split), each sheet computed for its own year (exact / within 1, %):")
    log("  all sheets:      " + shares(held))
    log("  the 2026 sheets: " + shares(held_2026))
    groups: dict = {}
    for e, d, x in held.misses:
        groups.setdefault((d[:7], e), []).append(x)
    log(f"  beyond 1 min: {len(held.misses)}: " + ", ".join(
        f"{month} {e[:4]} {len(xs)} ({min(xs):+d}..{max(xs):+d})" for (month, e), xs in sorted(groups.items())))
    log("final profile, per sheet, computed for the sheet's year:")
    for key in sorted(sheets):
        sc = score(final, {key: sheets[key]})
        log(f"  {key[0]}-{key[1]:02d} {shares(sc)}")
    log(f"final profile at the reference year {ref_year}, every sheet: {shares(score(final, sheets, year=ref_year))}")
    log(f"the preset before this fit, every sheet at its own year: {shares(score(PRESET, sheets))}")
    log(f"proposed Kotlin change (EuropeanPresets.DUM_RF): {kotlin(final)}")
    if args.proposed_night or args.no_asset:
        log("(scored with the proposed DumRf night; no asset written)" if args.proposed_night else "(no asset written)")
        return 0

    # The asset, scored with the engine as it is.
    days = sorted(d for s in sheets.values() for d in s["rows"])
    source = "DUM RF's monthly Moscow sheets (dumrf.ru and the Wayback Machine), " + ", ".join(f"{y}-{m:02d}" for y, m in sorted(sheets))
    stats = common.fit_stats(
        [common.sample(source + f": the distinct sheets read at the reference year {ref_year}", min(rows), max(rows), len(rows), 1)],
        [common.sample(source + ", held out by month, each at its own year", days[0], days[-1], len(days), 1, month_split=True)],
        held)
    zone = {k: v for k, v in zone_of(final).items() if k != "authority"}
    zone.update({"nameAr": "موسكو", "admin1": ADMIN1,
                 "namedPlaces": [{"centre": common.point(*CENTRE), "catalogueIds": [], "areaKm": 0.0}], "fit": stats})
    path = common.write_asset(AUTHORITY, AUTHORITY, "RU", "NATIONAL", [zone], fit=stats, source=SOURCE_LINE)
    log(f"wrote {path.relative_to(common.ROOT)} in {time.time() - started:.0f} s")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
