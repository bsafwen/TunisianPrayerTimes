#!/usr/bin/env python3
"""
Fits `bh_calendar`, the Bahraini calendar that the Supreme Council for Islamic Affairs issues (plan 8.1, 8.2, 8.7,
8.8, Q7; milestone AM10, harness 9.4), and writes its official-zone asset,
android-app/app/src/main/assets/official-zones/bh_calendar.json (schema 1).

Data, read at run time and never copied (D13, A7), in test-data-local/research-2026-10-11/BH (README.md there):
- bh1448.csv: the 355 days of the 1448 calendar (2026-06-16 to 2027-06-05), from the book PDF's text layer.
- almajles_calendar.html: the SCIA's calendar page, which states the method: Isha at 18 degrees; Fajr and sunrise at
  the eastern tip of Muharraq (Dilmunia), 26.27 N 50.68 E; Dhuhr, Asr, Maghrib and Isha at the western tip of Bahrain
  island (Salman Town), 26.22 N 50.44 E; Dhuhr once the whole disc has crossed the meridian.
- test-data-local/research-2026-10-11/GEONAMES/cities1000.zip: Manama's point, the national zone's `point`.

The search loops use an interpolated sun table fed to the reference engine's own solver functions; every score
printed and every FitStats comes from common.compute, the reference engine.

Stages (9.4), each by windows (a published minute bounds the unrounded time to a minute, so each day bounds the
parameter; the value most days allow wins, read to the data's resolution: among the values within TOLERANCE of the
best count, a conventional value first, then the fewest decimals). The stated longitudes are kept; the latitudes are
scanned (LATITUDES), each point's latitude being the one where its times' picked values cover the most days.
1. Dhuhr: the sun model and the rounding, and the offset after the transit at the stated western longitude, in
   minutes (E7 `dhuhrAfterTransitMin`).
2. Sunrise and Maghrib: rounding and horizon altitude (E2), at each point's latitude.
3. Fajr and Isha: rounding and angle.
4. Asr: factor 1 on the geometric sun, with the noon shadow at the declination of the Asr instant itself (E4
   SHADOW_AT_EVENT), and its rounding; the other evaluations and the apparent sun are compared in the report.
Held out: the one calendar in hand, by month-split (fold A fits on the odd calendar months and scores the even ones,
fold B the reverse). The asset takes the values fitted on all 355 days; its FitStats are the folds' held-out scores.

The values are fixed (FIRST_FIT): the stages above ran on 2026-10-15 with Asr on DAILY_10H_UT, and when Asr moved to
SHADOW_AT_EVENT every other value, the folds' own included, was kept (coordinator's decision), so a run reproduces
them and only rescores; --refit runs the stages again (for a new calendar).

Usage: python scripts/prayer_formula/fits/bh_calendar/fit.py [--refit]
"""

from __future__ import annotations

import argparse
import csv
import datetime
import functools
import html
import math
import re
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import common  # noqa: E402

ref = common.ref
EVENTS = common.EVENTS
AUTHORITY = "bh_calendar"
TZ = "Asia/Bahrain"                       # +03 without summer time since 1972
DATA = common.LOCAL_DATA / "research-2026-10-11" / "BH"
GEONAMES = common.LOCAL_DATA / "research-2026-10-11" / "GEONAMES" / "cities1000.zip"
MANAMA_GEONAMES_ID = "290340"
SOURCE = "bh_scia_calendar_1448"
SOURCE_LINE = ("The Supreme Council for Islamic Affairs's Bahraini calendar (almajles.gov.bh), 1448 (2026-06-16 to "
               "2027-06-05): one national table from two stated points")
EVIDENCE = "The Supreme Council for Islamic Affairs's calendar, 1448: its stated two points and rules, fitted"
KEYS = dict(zip(EVENTS, ("fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha")))
MODELS = ("VSOP87", "MEEUS", "TEXTBOOK")
ASR_MODES = ("EVENT_INSTANT", "DAILY_0H_UT", "DAILY_10H_UT", "KACST", "SHADOW_AT_EVENT")   # compared (report)
ASR_MODE = "SHADOW_AT_EVENT"              # E4, shipped: the noon shadow at the declination of the Asr instant itself
# The values of the fit of 2026-10-15 (all days, and each month-split fold's own), kept when Asr moved from
# DAILY_10H_UT to SHADOW_AT_EVENT (coordinator's decision, 2026-10-15): a rerun reproduces them; --refit re-picks.
# Angles in degrees, altitudes in degrees, Dhuhr in minutes after the transit; each with its rounding (E1).
FIRST_FIT = {
    "all": {"model": "VSOP87", "morning_lat": 26.205, "evening_lat": 26.175, "fajr": (18.01, "DOWN"),
            "sunrise": (-0.83, "DOWN"), "dhuhr": (1.07, "DOWN"), "asr": "DOWN", "maghrib": (-0.8333, "NEAREST"),
            "isha": (17.82, "DOWN")},
    "A": {"model": "VSOP87", "morning_lat": 26.215, "evening_lat": 26.18, "fajr": (18.008, "DOWN"),
          "sunrise": (-0.8333, "DOWN"), "dhuhr": (1.1, "DOWN"), "asr": "DOWN", "maghrib": (-0.95, "DOWN"),
          "isha": (17.81, "DOWN")},
    "B": {"model": "VSOP87", "morning_lat": 26.205, "evening_lat": 26.165, "fajr": (18.01, "DOWN"),
          "sunrise": (-0.8333, "DOWN"), "dhuhr": (1.07, "DOWN"), "asr": "DOWN", "maghrib": (-0.8333, "NEAREST"),
          "isha": (17.825, "DOWN")},
}
ASR_SUNS = ("GEOMETRIC", "APPARENT")
ROUNDINGS = ("NEAREST", "DOWN", "UP")
EDGES = {"NEAREST": (-30, 30), "UP": (-60, 0), "DOWN": (0, 60)}   # E1: seconds around the published minute
STANDARD_ALTITUDE = -0.8333
LATITUDES = tuple(round(26.10 + 0.005 * i, 3) for i in range(41))   # 26.100 .. 26.300
TOLERANCE = 0.0075                        # values within 0.75 % of the best count are read as equally good
FOLDS = (("A", (1, 3, 5, 7, 9, 11)), ("B", (2, 4, 6, 8, 10, 12)))   # the months each fold fits on
EPOCH = datetime.date(1970, 1, 1)


def say(text: str) -> None:
    print(text, flush=True)


# --- Data ------------------------------------------------------------------------------------------------------

@dataclass
class Row:
    day: datetime.date
    hijri: str
    published: dict[str, int]             # epoch seconds of each published minute

    @functools.cached_property
    def cycle(self) -> ref.Cycle:
        return ref.Cycle(self.day)        # U = D at every Bahraini point (S2)


def calendar_rows() -> list[Row]:
    rows = []
    with open(DATA / "bh1448.csv", encoding="utf-8", newline="") as fh:
        for r in csv.DictReader(fh):
            day = datetime.date.fromisoformat(r["date"])
            rows.append(Row(day, r["hijri"], {e: common.local_to_epoch(TZ, day, r[KEYS[e]]) for e in EVENTS}))
    days = [r.day for r in rows]
    if len(rows) != 355 or days != sorted(set(days)) or (days[-1] - days[0]).days != 354:
        raise SystemExit("bh1448.csv: expected 355 consecutive days")
    for r in rows:
        t = [r.published[e] for e in EVENTS]
        if t != sorted(t):
            raise SystemExit(f"{r.day}: times out of order")
    return rows


def stated_points() -> tuple[tuple[float, float], tuple[float, float]]:
    """The two points the SCIA's page states (the eastern longitude is printed 68.50 for 50.68)."""
    text = html.unescape(re.sub(r"<[^>]+>", " ", (DATA / "almajles_calendar.html").read_text(encoding="utf-8")))
    east = re.search(r"دلمونيا[^0-9]*?(26\.\d+)[^0-9]+?(\d\d\.\d\d)", text)
    west = re.search(r"مدينة سلمان[^0-9]*?(26\.\d+)[^0-9]+?(\d\d\.\d\d)", text)
    if not east or not west:
        raise SystemExit("almajles_calendar.html: the two stated points not found")
    e_lng = float(east.group(2))
    if e_lng > 60:                        # printed "68.50": the digits of 50.68 in the wrong order
        e_lng = float(".".join(reversed(east.group(2).split("."))))
    return (float(east.group(1)), e_lng), (float(west.group(1)), float(west.group(2)))


def geonames_point(geonames_id: str) -> tuple[float, float, str]:
    with zipfile.ZipFile(GEONAMES) as z, z.open("cities1000.txt") as fh:
        for line in fh:
            v = line.decode("utf-8").rstrip("\n").split("\t")
            if v[0] == geonames_id:
                return float(v[4]), float(v[5]), v[1]
    raise SystemExit(f"GeoNames {geonames_id} not in {GEONAMES}")


# --- The fast sun and the windows --------------------------------------------------------------------------------

class TableSun:
    """The model's (declination, equation of time, distance) on a 30-minute grid, linearly interpolated (errors
    under 1e-6 degree and 1e-4 s); only the search loops use it."""

    def __init__(self, model: str, first: datetime.date, last: datetime.date, step: float = 1 / 48):
        self.jd0 = ref.julian_day0(first) - 1
        self.step = step
        f = ref.SUN_MODELS[model]
        n = int((ref.julian_day0(last) + 2 - self.jd0) / step) + 3
        self.values = [f(self.jd0 + i * step) for i in range(n)]

    def __call__(self, jd: float) -> tuple[float, float, float]:
        x = (jd - self.jd0) / self.step
        i = int(x)
        w = x - i
        a, b = self.values[i], self.values[i + 1]
        return a[0] + (b[0] - a[0]) * w, a[1] + (b[1] - a[1]) * w, a[2] + (b[2] - a[2]) * w


class FixedSun:
    """S4's daily modes and KACST: the sun of one instant of the cycle's date, whatever JD."""

    def __init__(self, sun, jd0: float, mode: str):
        if mode == "DAILY_0H_UT":
            self.fixed = sun(jd0)
        elif mode == "DAILY_10H_UT":
            self.fixed = sun(jd0 + 10 / 24)
        elif mode == "KACST":
            self.fixed = (sun(jd0 + 21 / 24)[0],) + tuple(sun(jd0 + 9 / 24)[1:])
        else:
            raise ValueError(mode)

    def __call__(self, jd: float):
        return self.fixed


def sweep(windows: list[tuple[float, float]]) -> tuple[int, list[tuple[float, float]]]:
    """The largest number of windows [lo, hi) that share a value, and the intervals where they do."""
    points = sorted([(lo, 1) for lo, _ in windows] + [(hi, -1) for _, hi in windows])
    best, count, spans = 0, 0, []
    for i, (x, step) in enumerate(points):
        count += step
        if i + 1 == len(points) or points[i + 1][0] == x:
            continue
        nxt = points[i + 1][0]
        if count > best:
            best, spans = count, [(x, nxt)]
        elif count == best and best > 0:
            spans.append((x, nxt))
    return best, spans


def spans_from(windows: list[tuple[float, float]], threshold: int) -> list[tuple[float, float]]:
    points = sorted([(lo, 1) for lo, _ in windows] + [(hi, -1) for _, hi in windows])
    count, spans = 0, []
    for i, (x, step) in enumerate(points):
        count += step
        if i + 1 == len(points) or points[i + 1][0] == x:
            continue
        if count >= threshold:
            if spans and spans[-1][1] == x:
                spans[-1] = (spans[-1][0], points[i + 1][0])
            else:
                spans.append((x, points[i + 1][0]))
    return spans


def decimals_in(spans: list[tuple[float, float]], d: int) -> list[float]:
    out, scale = [], 10 ** d
    for lo, hi in spans:
        k_lo, k_hi = math.ceil(lo * scale - 1e-9), math.ceil(hi * scale - 1e-9) - 1
        out += [round(k / scale, d) for k in range(k_lo, min(k_hi, k_lo + 199) + 1)]
    return out


def covering(windows: list[tuple[float, float]], v: float) -> int:
    return sum(lo <= v < hi for lo, hi in windows)


@dataclass
class Pick:
    config: tuple
    value: float
    at_value: int                         # the days the chosen configuration and value reproduce
    best: int                             # the most days any configuration and value reproduces
    n: int


def pick(windows: dict[tuple, list], idx: list[int], keys: list[tuple], prefer=(), scale: float = 1.0) -> Pick:
    """The value most windows cover, read to the data's resolution: among the configurations and values covered by
    at least the best count minus TOLERANCE of the days, a preferred value first, then the fewest decimals (of
    value / scale), then the earlier configuration, then the most days."""
    subs = {key: [windows[key][i] for i in idx] for key in keys}
    sweeps = {key: sweep(sub) for key, sub in subs.items()}
    best = max(count for count, _ in sweeps.values())
    threshold = best - math.ceil(TOLERANCE * len(idx))
    eligible = [(rank, key, spans_from(subs[key], threshold)) for rank, key in enumerate(keys) if sweeps[key][0] >= threshold]
    levels = [lambda spans: [v for v in prefer if any(lo <= v < hi for lo, hi in spans)]]
    levels += [functools.partial(lambda spans, d: [x * scale for x in decimals_in([(lo / scale, hi / scale) for lo, hi in spans], d)], d=d)
               for d in range(0, 7)]
    for values_of in levels:
        found = [(rank, -covering(subs[key], v), key, v) for rank, key, spans in eligible for v in values_of(spans)]
        if found:
            rank, minus, key, value = min(found)
            return Pick(key, value, -minus, best, len(idx))
    raise SystemExit("no value found")


def pick_at(windows: dict[tuple, list], idx: list[int], keys: list[tuple], values: tuple[float, ...]) -> Pick:
    """A parameter the engine holds to fixed values (the Asr factor, 1 or 2): the configuration and value covering
    the most days; ties go to the earlier configuration and value."""
    best_any = max(sweep([windows[key][i] for i in idx])[0] for key in keys)
    found = [(-sum(windows[key][i][0] <= v < windows[key][i][1] for i in idx), rank, j, key, v)
             for rank, key in enumerate(keys) for j, v in enumerate(values)]
    minus, _, _, key, v = min(found)
    return Pick(key, v, -minus, best_any, len(idx))


def altitude(row: Row, sun, lat: float, lng: float, instant: float) -> float:
    return ref.altitude(row.cycle, sun, lat, lng, row.cycle.hours(instant))


def bennett(a: float) -> float:
    return ref.bennett(a, 1010.0, 10.0)   # a NATIONAL zone without pressure or temperature: 1010 hPa, 10 C (S5)


def shadow_factor(a: float, zenith: float, asr_sun: str) -> float:
    """S7 inverted: the factor k whose Asr altitude is the geometric altitude a, with the noon zenith distance."""
    if asr_sun == "GEOMETRIC":
        return 1 / ref.tand(a) - ref.tand(zenith)
    h0_app = 90 - zenith + bennett(90 - zenith)
    a_app = a
    for _ in range(30):
        a_app = a + bennett(a_app)
    return 1 / ref.tand(a_app) - ref.tand(90 - h0_app)


def angle_windows(rows: list[Row], sun, lat: float, lng: float, event: str, side: int) -> dict[tuple, list]:
    """(event, rounding): per row, the altitude (sunrise, Maghrib) or the angle below the horizon (Fajr, Isha) that
    puts the unrounded time in the published minute."""
    out = {(event, r): [] for r in ROUNDINGS}
    for row in rows:
        p = row.published[event]
        alt = {d: altitude(row, sun, lat, lng, p + d) for d in (-60, -30, 0, 30, 60)}
        for r, (a, b) in EDGES.items():
            x, y = (alt[a], alt[b]) if event in ("SUNRISE", "MAGHRIB") else (-alt[b], -alt[a])
            out[(event, r)].append((min(x, y), max(x, y)))
    return out


def asr_windows(rows: list[Row], sun, lat: float, lng: float) -> dict[tuple, list]:
    """(ASR, mode, asr sun, rounding): per row, the shadow factor that puts Asr in the published minute. With
    SHADOW_AT_EVENT the converged Asr takes the declination of its own instant for the noon shadow, so each edge of
    the minute uses the declination at that edge."""
    out = {}
    for row in rows:
        for mode in ASR_MODES:
            s = sun if mode in ("EVENT_INSTANT", "SHADOW_AT_EVENT") else FixedSun(sun, row.cycle.jd0, mode)
            t0 = ref.transit(row.cycle, s, lng)
            zenith = abs(lat - s(row.cycle.jd0 + t0 / 24)[0])
            edges = {d: row.published["ASR"] + d for d in (-60, -30, 0, 30, 60)}
            alt = {d: altitude(row, s, lat, lng, t) for d, t in edges.items()}
            if mode == "SHADOW_AT_EVENT":
                zen = {d: abs(lat - s(ref.jd_of_instant(t))[0]) for d, t in edges.items()}
            else:
                zen = dict.fromkeys(edges, zenith)
            for asr_sun in ASR_SUNS:
                k = {d: shadow_factor(a, zen[d], asr_sun) for d, a in alt.items()}
                for r, (a, b) in EDGES.items():
                    out.setdefault(("ASR", mode, asr_sun, r), []).append((min(k[a], k[b]), max(k[a], k[b])))
    return out


def dhuhr_windows(rows: list[Row], suns: dict, lng: float) -> dict[tuple, list]:
    """(model, rounding): per row, the offset after the transit (s) that puts Dhuhr in the published minute."""
    out = {}
    for model, sun in suns.items():
        for row in rows:
            transit = row.cycle.instant(ref.transit(row.cycle, sun, lng))
            for r, (a, b) in EDGES.items():
                out.setdefault((model, r), []).append((row.published["DHUHR"] + a - transit, row.published["DHUHR"] + b - transit))
    return out


# --- Stages ----------------------------------------------------------------------------------------------------

@dataclass
class Fitted:
    model: str
    dhuhr: Pick                            # (model, rounding), offset in s
    morning_lat: float
    evening_lat: float
    picks: dict[str, Pick]                 # FAJR, SUNRISE, MAGHRIB, ISHA, ASR at the chosen latitudes
    by_lat: dict[str, list]                # per latitude, the summed picked counts (report)


class Windows:
    """Every window the stages read, computed once for all rows; a fold subsets them by row index."""

    def __init__(self, rows: list[Row], suns: dict, east: tuple[float, float], west: tuple[float, float]):
        self.rows = rows
        self.dhuhr = dhuhr_windows(rows, {m: suns[m] for m in MODELS}, west[1])
        self.morning: dict[tuple[str, float], dict] = {}
        self.evening: dict[tuple[str, float], dict] = {}
        for model in ("VSOP87", "MEEUS"):
            for lat in LATITUDES:
                w = angle_windows(rows, suns[model], lat, east[1], "FAJR", -1)
                w.update(angle_windows(rows, suns[model], lat, east[1], "SUNRISE", -1))
                self.morning[(model, lat)] = w
                w = angle_windows(rows, suns[model], lat, west[1], "MAGHRIB", 1)
                w.update(angle_windows(rows, suns[model], lat, west[1], "ISHA", 1))
                w.update(asr_windows(rows, suns[model], lat, west[1]))
                self.evening[(model, lat)] = w
            say(f"  windows for {model}: {len(LATITUDES)} latitudes x 2 points")


def fit(win: Windows, idx: list[int], stated: tuple[tuple[float, float], tuple[float, float]]) -> Fitted:
    (east_lat, _), (west_lat, _) = stated
    best = None
    for model in ("VSOP87", "MEEUS"):
        morning, evening = [], []
        for lat in LATITUDES:
            w = win.morning[(model, lat)]
            f = pick(w, idx, [("FAJR", r) for r in ROUNDINGS], (18.0,))
            s = pick(w, idx, [("SUNRISE", r) for r in ROUNDINGS], (STANDARD_ALTITUDE,))
            morning.append((f.at_value + s.at_value, -abs(lat - east_lat), lat, {"FAJR": f, "SUNRISE": s}))
            w = win.evening[(model, lat)]
            m = pick(w, idx, [("MAGHRIB", r) for r in ROUNDINGS], (STANDARD_ALTITUDE,))
            i = pick(w, idx, [("ISHA", r) for r in ROUNDINGS], (18.0,))
            a = pick_at(w, idx, [("ASR", ASR_MODE, "GEOMETRIC", r) for r in ROUNDINGS], (1.0,))
            evening.append((m.at_value + i.at_value + a.at_value, -abs(lat - west_lat), lat, {"MAGHRIB": m, "ISHA": i, "ASR": a}))
        mo, ev = max(morning), max(evening)
        # 1. Dhuhr: the rounding and the offset after the transit at the stated western longitude, in minutes. All
        # three roundings fit alike (nearest after a shift of half a minute is down); DOWN is tried first because only
        # its window holds the stated rule, the whole disc past the meridian (16' at 15" a second: 64 s).
        dh_model = pick(win.dhuhr, idx, [(model, r) for r in ("DOWN", "NEAREST", "UP")], scale=60.0)
        total = mo[0] + ev[0] + dh_model.at_value
        if best is None or total > best[0]:
            best = (total, model, dh_model, mo, ev, {"morning": morning, "evening": evening})
    _, model, dh_model, mo, ev, by_lat = best
    return Fitted(model, dh_model, mo[2], ev[2], {**mo[3], **ev[3]}, by_lat)


def fixed(win: Windows, idx: list[int], v: dict) -> Fitted:
    """FIRST_FIT's values as a Fitted, each with the days its windows reproduce on idx (report)."""
    model = v["model"]

    def made(windows: dict, key: tuple, value: float) -> Pick:
        sub = [windows[key][i] for i in idx]
        return Pick(key, value, covering(sub, value), sweep(sub)[0], len(idx))

    wm, we = win.morning[(model, round(v["morning_lat"], 3))], win.evening[(model, round(v["evening_lat"], 3))]
    picks = {e: made(wm if e in ("FAJR", "SUNRISE") else we, (e, v[e.lower()][1]), v[e.lower()][0])
             for e in ("FAJR", "SUNRISE", "MAGHRIB", "ISHA")}
    picks["ASR"] = made(we, ("ASR", ASR_MODE, "GEOMETRIC", v["asr"]), 1.0)
    dhuhr = made(win.dhuhr, (model, v["dhuhr"][1]), v["dhuhr"][0] * 60)
    return Fitted(model, dhuhr, v["morning_lat"], v["evening_lat"], picks, {})


def describe(f: Fitted) -> str:
    p = f.picks
    asr = p["ASR"].config
    return (f"{f.model}; Dhuhr {f.dhuhr.config[1]} + {f.dhuhr.value / 60:g} min ({f.dhuhr.at_value}/{f.dhuhr.n}); "
            f"morning {f.morning_lat:.3f} N: Fajr {p['FAJR'].value:g} {p['FAJR'].config[1]} ({p['FAJR'].at_value}), "
            f"sunrise {p['SUNRISE'].value:g} {p['SUNRISE'].config[1]} ({p['SUNRISE'].at_value}); "
            f"evening {f.evening_lat:.3f} N: Asr k {p['ASR'].value:g} {asr[1]} {asr[2]} {asr[3]} ({p['ASR'].at_value}), "
            f"Maghrib {p['MAGHRIB'].value:g} {p['MAGHRIB'].config[1]} ({p['MAGHRIB'].at_value}), "
            f"Isha {p['ISHA'].value:g} {p['ISHA'].config[1]} ({p['ISHA'].at_value})")


def horizon_of(altitude_deg: float) -> dict:
    return {"type": "standard"} if altitude_deg == STANDARD_ALTITUDE else {"type": "fixed", "altitudeDeg": altitude_deg}


def method_of(f: Fitted) -> dict:
    p = f.picks
    _, asr_mode, asr_sun, asr_rounding = p["ASR"].config
    k = p["ASR"].value
    if k not in (1.0, 2.0):
        raise SystemExit(f"Asr shadow factor {k} is neither 1 nor 2")
    rounding = {"FAJR": p["FAJR"].config[1], "SUNRISE": p["SUNRISE"].config[1], "DHUHR": f.dhuhr.config[1],
                "ASR": asr_rounding, "MAGHRIB": p["MAGHRIB"].config[1], "ISHA": p["ISHA"].config[1]}
    return common.method_json(
        id=AUTHORITY, fajr={"type": "angle", "degrees": p["FAJR"].value}, isha={"type": "angle", "degrees": p["ISHA"].value},
        dhuhrAfterTransitMin=round(f.dhuhr.value / 60, 6), asrShadow=int(k), asr={"type": "shadow"}, asrSun=asr_sun,
        sunModel=f.model, rounding=rounding,
        horizon={"sunrise": horizon_of(p["SUNRISE"].value), "sunset": horizon_of(p["MAGHRIB"].value)},
        sunEvaluation={} if asr_mode == "EVENT_INSTANT" else {"ASR": asr_mode},
        evidence={"kind": "FROM_TABLES", "source": EVIDENCE},
    )


def zone_of(f: Fitted, stated, manama) -> dict:
    (_, east_lng), (_, west_lng) = stated
    return {"id": "bahrain", "nameAr": "البحرين", "point": common.point(manama[0], manama[1]),
            "morningPoint": common.point(f.morning_lat, east_lng), "eveningPoint": common.point(f.evening_lat, west_lng)}


# --- Scores ----------------------------------------------------------------------------------------------------

def computed(method: dict, zone: dict, day: datetime.date) -> dict[str, tuple]:
    lat, lng = zone["point"]["latE5"] / 1e5, zone["point"]["lngE5"] / 1e5
    z = common.zone_json(zone["id"], lat, lng, authority=AUTHORITY, morningPoint=zone["morningPoint"],
                         eveningPoint=zone["eveningPoint"])
    return common.compute(common.case(method, lat, lng, TZ, day, zone=z, country="BH"))


def score(method: dict, zone: dict, rows: list[Row]) -> tuple[common.Score, dict]:
    s = common.Score()
    statuses = {}
    for row in rows:
        out = computed(method, zone, row.day)
        for e in EVENTS:
            unrounded, minute, status = out[e]
            if status != "COMPUTED":
                statuses[e] = statuses.get(e, 0) + 1
            s.add(e, row.day, minute // 60, row.published[e] // 60)
    return s, statuses


def merged(scores: list[common.Score]) -> common.Score:
    out = common.Score()
    for s in scores:
        for e in EVENTS:
            out.exact[e] += s.exact[e]
            out.within1[e] += s.within1[e]
            out.total[e] += s.total[e]
            out.months[e] = [a + b for a, b in zip(out.months[e], s.months[e])]
        out.worst = max(out.worst, s.worst)
        out.misses += s.misses
    return out


def shares(s: common.Score) -> str:
    return ", ".join(f"{e} {s.exact[e]}/{s.total[e]} ({s.exact[e] / s.total[e]:.1%})" for e in EVENTS)


def tier_a(stats: dict) -> list[str]:
    """FitStats.passesTierA (9.5), as the failures it finds (Bahrain is tier B; reported for comparison)."""
    out = []
    for e in EVENTS:
        if stats["exact"][e] < 0.97:
            out.append(f"{e} {stats['exact'][e]:.1%} exact")
        if stats["within1"][e] < 1.0:
            out.append(f"{e} {stats['within1'][e]:.1%} within 1 min")
        if min(stats["daysPerMonth"][e]) < 7:
            out.append(f"{e} under 7 days in a month")
    return out


# --- Main ------------------------------------------------------------------------------------------------------

def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="Fits bh_calendar and writes its official-zone asset.")
    parser.add_argument("--refit", action="store_true", help="re-pick every value by the stages instead of FIRST_FIT")
    refit = parser.parse_args().refit
    rows = calendar_rows()
    stated = stated_points()
    manama = geonames_point(MANAMA_GEONAMES_ID)
    say(f"{AUTHORITY}: {len(rows)} days {rows[0].day}..{rows[-1].day} (Hijri year {rows[0].hijri[-4:]}); "
        f"stated points: east {stated[0]}, west {stated[1]}; zone point {manama[2]} {manama[0]:.5f} N {manama[1]:.5f} E; "
        + ("re-picking every value (--refit)" if refit else "the fixed values of FIRST_FIT, Asr by SHADOW_AT_EVENT"))
    suns = {m: TableSun(m, rows[0].day, rows[-1].day) for m in MODELS}
    win = Windows(rows, suns, *stated)

    folds = {}
    for name, months in FOLDS:
        idx = [i for i, r in enumerate(rows) if r.day.month in months]
        folds[name] = (idx, fit(win, idx, stated) if refit else fixed(win, idx, FIRST_FIT[name]))
        say(f"fold {name} ({'fitted' if refit else 'fixed values fitted'} on months {','.join(map(str, months))}, "
            f"{len(idx)} days): {describe(folds[name][1])}")
    every = list(range(len(rows)))
    final = fit(win, every, stated) if refit else fixed(win, every, FIRST_FIT["all"])
    say(f"all {len(rows)} days: {describe(final)}")
    per_model = {m: max(sweep(win.dhuhr[(m, r)])[0] for r in ROUNDINGS) for m in MODELS}
    down = sweep(win.dhuhr[(final.model, "DOWN")])
    say("  1 Dhuhr, most windows per sun model at the stated 50.44 E: " + ", ".join(f"{m} {n}" for m, n in per_model.items())
        + f"; {final.model} DOWN offset window [{down[1][0][0]:.1f}, {down[1][-1][1]:.1f}) s; at 66 s (1.1 min) "
        + f"{covering(win.dhuhr[(final.model, 'DOWN')], 66.0)} days")
    if final.by_lat:
        say("  2-4 summed picked counts per latitude (morning: Fajr + sunrise; evening: Asr + Maghrib + Isha):")
        for side in ("morning", "evening"):
            ranked = sorted(final.by_lat[side], reverse=True)[:5]
            say(f"    {side}: " + "; ".join(f"{lat:.3f} N {n}" for n, _, lat, _ in ranked)
                + f"; at the stated latitude {next(n for n, _, lat, _ in final.by_lat[side] if abs(lat - (stated[0][0] if side == 'morning' else stated[1][0])) < 1e-9)}")
    evening = win.evening[(final.model, round(final.evening_lat, 3))]
    modes = {}
    for mode in ASR_MODES:
        for s_ in ASR_SUNS:
            modes[(mode, s_)] = max(covering(evening[("ASR", mode, s_, r)], 1.0) for r in ROUNDINGS)
    say("    Asr at k = 1, best rounding, per evaluation and sun: " + ", ".join(f"{m} {s_[0]} {n}" for (m, s_), n in modes.items()))
    stated_isha = max(covering(evening[("ISHA", r)], 18.0) for r in ROUNDINGS)
    say(f"    Isha at the stated 18 degrees: {stated_isha}/{len(rows)} days at the fitted evening latitude")
    p = final.picks
    own = {}
    for e, side, key in (("FAJR", "morning", ("FAJR", p["FAJR"].config[1])), ("SUNRISE", "morning", ("SUNRISE", p["SUNRISE"].config[1])),
                         ("ASR", "evening", p["ASR"].config), ("MAGHRIB", "evening", ("MAGHRIB", p["MAGHRIB"].config[1])),
                         ("ISHA", "evening", ("ISHA", p["ISHA"].config[1]))):
        table = win.morning if side == "morning" else win.evening
        own[e] = max((covering(table[(final.model, lat)][key], p[e].value), lat) for lat in LATITUDES)
    say("    each time alone, at its fitted value and the stated longitude, prefers: "
        + ", ".join(f"{e} {lat:.3f} N ({n})" for e, (n, lat) in own.items())
        + " (the reduced latitudes of the stated points, tan u = (b/a) tan phi, are 26.194 and 26.144)")

    method = method_of(final)
    zone = zone_of(final, stated, manama)
    held = []
    asr_daily = 0
    for name, (idx, f) in folds.items():
        scored = [rows[i] for i in range(len(rows)) if i not in set(idx)]
        s, statuses = score(method_of(f), zone_of(f, stated, manama), scored)
        held.append(s)
        former = dict(method_of(f), sunEvaluation={"ASR": "DAILY_10H_UT"})
        asr_daily += score(former, zone_of(f, stated, manama), scored)[0].exact["ASR"]
        say(f"fold {name} held out ({len(scored)} days): {shares(s)}; worst {s.worst} min; not COMPUTED: {statuses or 'none'}")
    total = merged(held)
    say(f"held-out Asr {total.exact['ASR']}/{len(rows)} with the noon shadow at the declination of the Asr instant "
        f"(SHADOW_AT_EVENT, shipped); at the same fold points with DAILY_10H_UT {asr_daily}/{len(rows)}")
    in_sample, statuses = score(method, zone, rows)
    say(f"held out, both folds ({total.total['FAJR']} days): {shares(total)}; within 1 min: "
        + ", ".join(f"{e} {total.within1[e] / total.total[e]:.1%}" for e in EVENTS) + f"; worst {total.worst} min")
    say(f"the asset's values on all days (in-sample): {shares(in_sample)}; worst {in_sample.worst} min")
    plan = dict(method)
    plan.update(fajr={"type": "angle", "degrees": 18.0}, isha={"type": "angle", "degrees": 17.8}, dhuhrAfterTransitMin=1.1,
                sunEvaluation={}, horizon={"sunrise": {"type": "standard"}, "sunset": {"type": "standard"}},
                rounding={e: ("NEAREST" if e == "MAGHRIB" else "DOWN") for e in EVENTS}, sunModel="VSOP87", asrSun="GEOMETRIC")
    plan_zone = dict(zone, morningPoint=common.point(26.22, 50.68), eveningPoint=common.point(26.22, 50.44))
    before, _ = score(plan, plan_zone, rows)
    say(f"the plan's profile before this fit (26.22 N both points, 18/17.8, Dhuhr + 1.1): {shares(before)}; worst {before.worst} min")

    sample = common.sample(SOURCE, rows[0].day, rows[-1].day, len(rows), 1)
    split = common.sample(SOURCE, rows[0].day, rows[-1].day, len(rows), 1, month_split=True)
    stats = common.fit_stats([sample], [split], total)
    failures = tier_a(stats)
    say(f"tier A (9.5; Bahrain is tier B): {'passes' if not failures else 'fails: ' + '; '.join(failures)}; "
        f"tier B (every time within 1 min): {'passes' if all(v == 1.0 for v in stats['within1'].values()) else 'fails'}")
    zone["fit"] = stats
    path = common.write_asset(AUTHORITY, AUTHORITY, "BH", "NATIONAL", [zone], fit=stats, source=SOURCE_LINE)
    say(f"wrote {path.relative_to(common.ROOT).as_posix()}: 1 zone; method {method['fajr']}, {method['isha']}, "
        f"dhuhrAfterTransitMin {method['dhuhrAfterTransitMin']}, rounding {method['rounding']}, horizon {method['horizon']}, "
        f"sunEvaluation {method['sunEvaluation']}, asrSun {method['asrSun']}, sunModel {method['sunModel']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
