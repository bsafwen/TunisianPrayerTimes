#!/usr/bin/env python3
"""
Fits `sa_umm_al_qura`, KACST's Umm al-Qura prayer times (plan 8.7, 8.8, 9.4; milestone AM2), and writes its
official-zone asset, android-app/app/src/main/assets/official-zones/sa_umm_al_qura.json (schema 1).

Data, read at run time and never copied (D13, A7): KACST's year tables in test-data-local/research-2026-10-08/SA,
found through that folder's MANIFEST.tsv, whose source URL gives each table's query point and year. Every table
was asked for at one of the 173 places of KACST's own list (sa/web/cities.json, regions in sa/web/regions.json),
and KACST's service computes any point and any year with today's rule.

Held out by year: fold A fits on the 2026 tables and scores the 2025 and 2027 ones, fold B the reverse. The
asset is written only when both folds pick the same values, so every table is scored by values fitted without
it. Medina's 2000 and 2008 tables, outside the app's years (2020-2100), are scored as a side check only.

Stages (9.4), each by windows: a published minute bounds the unrounded time to a minute (E1), which bounds the
parameter, and the value most windows cover wins, read to the data's resolution (TOLERANCE): within it, a
conventional value first, then the fewest decimals, then the simpler configuration.
1. Dhuhr: the sun model and the evaluation (E4), then the offset under nearest rounding: its half-minute part
   gives the rounding (nearest after half a minute is up or down), and what is left, to 0.1 s, is a shift of
   KACST's clock against the engine's sun.
2. Sunrise and sunset: with or without that shift, the evaluation, the rounding and the horizon altitude (E2).
3. Fajr: the same with the angle. Isha: its interval from the published Maghrib (E8).
4. Asr: the same with the geometric or apparent sun (E13) and the shadow factor.
A shift is written as a constant E18 value (seasonalOffsets) on the times that take it. The scores come from
common.compute (the reference engine, which the Kotlin engine matches). Quirk days (8.3, 8.7, A8): rows on Hijri
days 2-6 whose Fajr, sunrise, Dhuhr, Asr and Maghrib all equal the profile's times for the previous civil date,
and not all five its own date's, are counted apart.

Usage: python scripts/prayer_formula/fits/sa_umm_al_qura/fit.py
"""

from __future__ import annotations

import csv
import datetime
import functools
import json
import math
import re
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import common  # noqa: E402

ref = common.ref
EVENTS = common.EVENTS
FIVE = EVENTS[:5]                       # the computed times; Isha follows Maghrib
KEYS = dict(zip(EVENTS, ("fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha")))
AUTHORITY = "sa_umm_al_qura"
TZ = "Asia/Riyadh"                      # +03 all year since 1947
RESEARCH = common.LOCAL_DATA / "research-2026-10-08"
SA_DIR = RESEARCH / "SA"
APP_YEARS = range(2020, 2101)
REACH_KM = 25.0                         # 6.5, 8.8: every KACST entry, whatever its type, to start
SOURCE = "kacst_api"
SOURCE_LINE = ("KACST's Umm al-Qura calendar (ummulqura.org.sa, API umqserv.kacst.gov.sa): its 173 places with "
               "their published coordinates; KACST computes any other point")
EVIDENCE = "Re-derived from KACST's tables and its prayer-times service"
MODELS = ("MEEUS", "VSOP87", "TEXTBOOK")
MODES = ("EVENT_INSTANT", "DAILY_0H_UT", "DAILY_10H_UT", "KACST")
ASR_MODES = MODES + ("LOCAL_MIDNIGHT_SHADOW",)
ASR_SUNS = ("GEOMETRIC", "APPARENT")
ROUNDINGS = ("NEAREST", "UP", "DOWN")
EDGES = {"NEAREST": (-30, 30), "UP": (-60, 0), "DOWN": (0, 60)}   # E1: seconds around the published minute
STANDARD_ALTITUDE = -0.8333
HALF_MINUTES = tuple(sorted(range(-300, 301, 30), key=lambda x: (abs(x), x)))   # Dhuhr offsets of plain rounding
# Values covered by at least the best count minus this share of the windows are read as equally good: below about
# 0.45 % the folds pick different last digits (0.1 s of shift, 0.002° of altitude); above about 1.1 % MEEUS and
# the standard sunrise would pass. Every tolerance between gives the same values; this is the middle.
TOLERANCE = 0.0075
FOLDS = (("A", (2026,), (2025, 2027)), ("B", (2025, 2027), (2026,)))
CURRENT = {"sunModel": "MEEUS", "shift": 0.0, "DHUHR": ("EVENT_INSTANT", "UP", 0, False),   # the profile before AM2
           "SUNRISE": ("EVENT_INSTANT", "DOWN", -0.83, False), "MAGHRIB": ("EVENT_INSTANT", "UP", STANDARD_ALTITUDE, False),
           "FAJR": ("KACST", "UP", 18.5, False), "ASR": ("KACST", "GEOMETRIC", "UP", 1.0, False), "ISHA": (90, 120)}
EPOCH = datetime.date(1970, 1, 1)
ONE_DAY = datetime.timedelta(days=1)


# --- Data ------------------------------------------------------------------------------------------------------

@dataclass(frozen=True)
class Place:
    id: str
    name_ar: str
    name_en: str
    published: tuple[float, float]      # KACST's coordinates, as listed
    lat: float                          # the same at the asset's 1e-5 degree
    lng: float
    region: str                         # KACST's region code (regions.json)


@dataclass
class Row:
    place: Place
    day: datetime.date
    hijri: tuple[int, int, int]         # KACST's own Umm al-Qura date of the row (Java's HijrahDate on every row)
    published: dict[str, int]           # epoch seconds of each published minute

    @property
    def ramadan(self) -> bool:
        return self.hijri[1] == 9

    @functools.cached_property
    def cycle(self) -> ref.Cycle:
        """S2: the cycle's UT date U (D itself at every Saudi place)."""
        noon = (self.day - EPOCH).days * 86400 + 43200 - common.standard_offset(TZ, self.day)
        t = noon + 3600 * self.place.lng / 15
        return ref.Cycle(EPOCH + datetime.timedelta(days=math.floor(t / 86400)))


@dataclass
class Table:
    path: str
    year: int
    place: Place
    rows: list[Row]


def kacst_places() -> list[Place]:
    regions = {r["id"]: r["code"] for r in json.loads((SA_DIR / "sa/web/regions.json").read_text(encoding="utf-8"))}
    out = []
    for c in json.loads((SA_DIR / "sa/web/cities.json").read_text(encoding="utf-8")):
        lat, lng = c["latitude"], c["longitude"]
        out.append(Place(str(c["id"]), c["name_ar"].strip(), c["name_en"], (lat, lng), common.e5(lat) / 1e5,
                         common.e5(lng) / 1e5, regions[c["region_id"]]))
    return out


def kacst_tables(places: list[Place]) -> list[Table]:
    """Every KACST year table the manifest lists, at the KACST place its query point is."""
    by_point = {p.published: p for p in places}
    out = []
    with open(RESEARCH / "MANIFEST.tsv", encoding="utf-8", newline="") as fh:
        for r in csv.DictReader(fh, delimiter="\t"):
            if r["group"] != "SA" or "GetPrayerByYear" not in r["source"]:
                continue
            query = dict(re.findall(r"[?&](\w+)=([-\w.]+)", r["source"]))
            place = by_point.get((float(query["lat"]), float(query["lon"])))
            if place is None or query.get("zone") != "3":
                raise SystemExit(f"{r['path']}: query point not one of KACST's places, or zone not +3")
            year, rows = int(query["yg"]), []
            for o in json.loads((SA_DIR / r["path"]).read_text(encoding="utf-8")):
                g, h = o["gregorianDate"], o["hijriDate"]
                day = datetime.date(g["year"], g["month"], g["day"])
                rows.append(Row(place, day, (h["year"], h["month"], h["day"]),
                                {e: common.local_to_epoch(TZ, day, o["prayerTimes"][KEYS[e]]) for e in EVENTS}))
            if {row.day.year for row in rows} != {year} or len({row.day for row in rows}) != len(rows) \
                    or len(rows) not in (365, 366):
                raise SystemExit(f"{r['path']}: not one whole year {year}")
            out.append(Table(r["path"], year, place, rows))
    return sorted(out, key=lambda t: (t.year, t.path))


# --- Windows (9.4) ---------------------------------------------------------------------------------------------

def sweep(windows: list[tuple[float, float]]) -> tuple[int, list[tuple[float, float]]]:
    """The largest number of windows [lo, hi) that share a value, and the maximal intervals where they do."""
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
    """The intervals [lo, hi) where at least threshold windows meet."""
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
    """The values with d decimals inside the spans (at most 200)."""
    out, scale = [], 10 ** d
    for lo, hi in spans:
        k_lo, k_hi = math.ceil(lo * scale - 1e-9), math.ceil(hi * scale - 1e-9) - 1
        out += [round(k / scale, d) for k in range(k_lo, min(k_hi, k_lo + 199) + 1)]
    return out


def covering(windows: list[tuple[float, float]], v: float) -> int:
    return sum(lo <= v < hi for lo, hi in windows)


def altitude(row: Row, sun, instant: float) -> float:
    return ref.altitude(row.cycle, sun, row.place.lat, row.place.lng, row.cycle.hours(instant))


def bennett(a: float) -> float:
    return ref.bennett(a, 1010.0, 10.0)


def shadow_factor(a: float, zenith: float, asr_sun: str) -> float:
    """S7 inverted: the factor k whose Asr altitude is the geometric altitude a, with the noon zenith distance."""
    if asr_sun == "GEOMETRIC":
        return 1 / ref.tand(a) - ref.tand(zenith)
    h0 = 90 - zenith
    h0_app = h0 + bennett(h0)
    a_app = a
    for _ in range(30):
        a_app = a + bennett(a_app)
    return 1 / ref.tand(a_app) - ref.tand(90 - h0_app)


def sun_of(row: Row, model: str, mode: str) -> ref.SunFunction:
    return ref.SunFunction(ref.SUN_MODELS[model], "EVENT_INSTANT" if mode == "LOCAL_MIDNIGHT_SHADOW" else mode, row.cycle.jd0)


def noon_declination(row: Row, model: str, mode: str, sun: ref.SunFunction) -> float:
    """S7's δn: at the transit, the fixed δ of a daily mode or KACST, or 00:00 of D in the zone."""
    if mode == "LOCAL_MIDNIGHT_SHADOW":
        midnight = (row.day - EPOCH).days * 86400 - common.utc_offset(TZ, row.day, 0)
        return ref.SUN_MODELS[model](ref.jd_of_instant(midnight))[0]
    if mode == "EVENT_INSTANT":
        return sun(row.cycle.jd0 + ref.transit(row.cycle, sun, row.place.lng) / 24)[0]
    return sun.fixed[0]


def dhuhr_windows(rows: list[Row]) -> dict[tuple, list[tuple[float, float]]]:
    """(model, mode): per row, the offset x (seconds) after the transit that nearest rounding allows."""
    out: dict[tuple, list] = {}
    for model in MODELS:
        for mode in MODES:
            ws = []
            for row in rows:
                transit = row.cycle.instant(ref.transit(row.cycle, sun_of(row, model, mode), row.place.lng))
                p = row.published["DHUHR"]
                ws.append((p - 30 - transit, p + 30 - transit))
            out[(model, mode)] = ws
    return out


_EVENT_WINDOWS: dict[tuple, dict] = {}


def event_windows(rows: tuple[Row, ...], model: str, shift: float) -> dict[tuple, list[tuple[float, float]]]:
    """Per row, with KACST's times taken as the engine's plus [shift] seconds:
    (SUNRISE | MAGHRIB, mode, rounding): the horizon altitude; (FAJR, mode, rounding): the angle;
    (ASR, mode, sun, rounding): the shadow factor. Cached per rows, model and shift."""
    key = (id(rows), model, shift)
    if key not in _EVENT_WINDOWS:
        _EVENT_WINDOWS[key] = _event_windows(rows, model, shift)
    return _EVENT_WINDOWS[key]


def _event_windows(rows: tuple[Row, ...], model: str, shift: float) -> dict[tuple, list[tuple[float, float]]]:
    out: dict[tuple, list] = {}
    for row in rows:
        for mode in ASR_MODES:
            sun = sun_of(row, model, mode)
            alt = {}
            for event in ("FAJR", "SUNRISE", "MAGHRIB", "ASR") if mode in MODES else ("ASR",):
                p = row.published[event] - shift
                alt[event] = {d: altitude(row, sun, p + d) for d in (-60, -30, 0, 30, 60)}
            for event in ("FAJR", "SUNRISE", "MAGHRIB"):
                if event not in alt:
                    continue
                for rounding, (a, b) in EDGES.items():
                    x, y = (-alt[event][b], -alt[event][a]) if event == "FAJR" else (alt[event][a], alt[event][b])
                    out.setdefault((event, mode, rounding), []).append((min(x, y), max(x, y)))
            zenith = abs(row.place.lat - noon_declination(row, model, mode, sun))
            for asr_sun in ASR_SUNS:
                k = {d: shadow_factor(a, zenith, asr_sun) for d, a in alt["ASR"].items()}
                for rounding, (a, b) in EDGES.items():
                    out.setdefault(("ASR", mode, asr_sun, rounding), []).append((min(k[a], k[b]), max(k[a], k[b])))
    return out


# --- Stages ----------------------------------------------------------------------------------------------------

@dataclass
class Pick:
    """One time's fitted configuration and value: the windows covering it, the most any value gets, and where."""
    config: tuple
    value: float
    at_value: int
    best: int                           # the most windows any configuration and value gets
    span: tuple[float, float]           # where the chosen configuration gets its most
    n: int

    def text(self, unit: str = "", digits: int = 4) -> str:
        lo, hi = self.span
        config = [f"shift {c} s" if isinstance(c, float) else c for c in self.config if c not in EVENTS]
        return (f"{', '.join(config)}: {self.value:g}{unit} on {self.at_value}/{self.n} windows (best {self.best}; "
                f"this configuration's most on [{lo:.{digits}f}, {hi:.{digits}f}))")


def pick(windows: dict, idx: list[int], keys: list[tuple], prefer=()) -> Pick:
    """9.4's value most windows cover, read to the data's resolution: among the configurations and values covered
    by at least the best count minus TOLERANCE of the windows, a preferred value first (plain rounding, the
    standard altitude, an integer factor), then the fewest decimals, then the earlier configuration (the
    simpler one), then the most windows."""
    subs = {key: [windows[key][i] for i in idx] for key in keys}
    sweeps = {key: sweep(sub) for key, sub in subs.items()}
    best = max(count for count, _ in sweeps.values())
    threshold = best - math.ceil(TOLERANCE * len(idx))
    eligible = [(rank, key, spans_from(subs[key], threshold)) for rank, key in enumerate(keys) if sweeps[key][0] >= threshold]
    choice = None
    for level, values_of in [(-1, lambda spans: [v for v in prefer if any(lo <= v < hi for lo, hi in spans)])] + \
            [(d, functools.partial(decimals_in, d=d)) for d in range(0, 9)]:
        found = [(rank, -covering(subs[key], v), key, v) for rank, key, spans in eligible for v in values_of(spans)]
        if found:
            choice = min(found)
            break
    _, minus_count, key, value = choice
    count, spans = sweeps[key]
    span = max(spans, key=lambda s: s[1] - s[0])
    return Pick(key, value, -minus_count, best, span, len(idx))


def stage1(dw: dict, idx: list[int]) -> tuple[Pick, str, int, float, dict[str, int]]:
    """Dhuhr: the sun model and evaluation, the rounding and whole-minute offset, and KACST's shift (s)."""
    dhuhr = pick(dw, idx, list(dw), HALF_MINUTES)
    grid = 30 * round(dhuhr.value / 30)                 # the half-minute part: the rounding and whole minutes
    shift = round(dhuhr.value - grid, 1)                # what is left: KACST's clock against the engine's sun
    if grid % 60 == 0:
        rounding, offset = "NEAREST", grid // 60
    else:
        rounding, offset = ("UP", (grid - 30) // 60) if grid > 0 else ("DOWN", (grid + 30) // 60)
    per_model = {m: max(sweep([dw[(m, e)][i] for i in idx])[0] for e in MODES) for m in MODELS}
    return dhuhr, rounding, int(offset), shift, per_model


def fit_fold(rows: tuple[Row, ...], dw: dict, idx: list[int]) -> tuple[dict, dict[str, Pick], dict]:
    """Stages 1-4 on the rows idx: the profile's values, the picks, and what the report prints."""
    dhuhr, dhuhr_rounding, dhuhr_offset, shift, per_model = stage1(dw, idx)
    x = dhuhr.value
    model, dhuhr_mode = dhuhr.config
    shifts = (shift, 0.0) if shift else (0.0,)          # one shift for every time first: the simpler reading
    windows = {(s,) + key: ws for s in shifts for key, ws in event_windows(rows, model, s).items()}
    picks = {"DHUHR": dhuhr}
    for event in ("SUNRISE", "MAGHRIB"):
        picks[event] = pick(windows, idx, [(s, event, e, r) for s in shifts for e in MODES for r in ROUNDINGS],
                            (STANDARD_ALTITUDE,))
    picks["FAJR"] = pick(windows, idx, [(s, "FAJR", e, r) for s in shifts for e in MODES for r in ROUNDINGS])
    picks["ASR"] = pick(windows, idx, [(s, "ASR", e, a, r) for s in shifts for e in ASR_MODES for a in ASR_SUNS
                                       for r in ROUNDINGS], (1.0, 2.0))
    gaps = {False: Counter(), True: Counter()}
    for i in idx:
        gaps[rows[i].ramadan][(rows[i].published["ISHA"] - rows[i].published["MAGHRIB"]) // 60] += 1
    isha = {flag: c.most_common(1)[0][0] for flag, c in gaps.items() if c}

    def shifted(p: Pick) -> bool:
        return p.config[0] != 0.0

    values = {
        "sunModel": model, "shift": shift,
        "DHUHR": (dhuhr_mode, dhuhr_rounding, dhuhr_offset, bool(shift)),
        "SUNRISE": (picks["SUNRISE"].config[2], picks["SUNRISE"].config[3], picks["SUNRISE"].value, shifted(picks["SUNRISE"])),
        "MAGHRIB": (picks["MAGHRIB"].config[2], picks["MAGHRIB"].config[3], picks["MAGHRIB"].value, shifted(picks["MAGHRIB"])),
        "FAJR": (picks["FAJR"].config[2], picks["FAJR"].config[3], picks["FAJR"].value, shifted(picks["FAJR"])),
        "ASR": (picks["ASR"].config[2], picks["ASR"].config[3], picks["ASR"].config[4], picks["ASR"].value, shifted(picks["ASR"])),
        "ISHA": (isha[False], isha.get(True)),
    }
    report = {"x": x, "per_model": per_model,
              "isha": {flag: (n, gaps[flag][n], sum(gaps[flag].values())) for flag, n in isha.items()}}
    return values, picks, report


def horizon_of(altitude_deg: float) -> dict:
    return {"type": "standard"} if altitude_deg == STANDARD_ALTITUDE else {"type": "fixed", "altitudeDeg": altitude_deg}


def profile_of(v: dict) -> dict:
    """The fitted values as the PrayerMethod JSON the reference engine reads (Kotlin @SerialName values)."""
    asr_mode, asr_sun, asr_rounding, k, _ = v["ASR"]
    if k not in (1.0, 2.0):
        raise SystemExit(f"Asr shadow factor {k} is neither 1 nor 2")
    rounding = {"FAJR": v["FAJR"][1], "SUNRISE": v["SUNRISE"][1], "DHUHR": v["DHUHR"][1], "ASR": asr_rounding,
                "MAGHRIB": v["MAGHRIB"][1], "ISHA": "NEAREST"}     # an Isha by interval takes Maghrib's (E1)
    modes = {"FAJR": v["FAJR"][0], "SUNRISE": v["SUNRISE"][0], "DHUHR": v["DHUHR"][0], "ASR": asr_mode,
             "MAGHRIB": v["MAGHRIB"][0]}
    minutes, ramadan = v["ISHA"]
    shift_min = v["shift"] / 60                         # 1.0 / 60, as Kotlin writes it
    return common.method_json(
        id=AUTHORITY, fajr={"type": "angle", "degrees": v["FAJR"][2]},
        isha={"type": "after", "minutes": int(minutes), "from": "MAGHRIB",
              "ramadan": None if ramadan in (None, minutes) else int(ramadan)},
        offsets={"DHUHR": int(v["DHUHR"][2])} if v["DHUHR"][2] else {}, asrShadow=int(k), asr={"type": "shadow"},
        asrSun=asr_sun, sunModel=v["sunModel"], highLatitude={"type": "angle_based"}, rounding=rounding,
        horizon={"sunrise": horizon_of(v["SUNRISE"][2]), "sunset": horizon_of(v["MAGHRIB"][2])},
        sunEvaluation={e: m for e, m in modes.items() if m != "EVENT_INSTANT"}, ramadanDays="UMM_AL_QURA_COMPUTED",
        seasonalOffsets={e: [shift_min] * 36 for e in FIVE if v[e][-1] and shift_min},
        evidence={"kind": "FROM_TABLES", "source": EVIDENCE},
    )


def own_shifts(rows: list[Row], v: dict) -> dict[str, tuple[float, int, int]]:
    """For the report: the shift (s) each time alone wants with the fitted values, its windows' most-covered
    middle and count."""
    model = v["sunModel"]
    windows = {e: [] for e in FIVE}
    for row in rows:
        lat, lng = row.place.lat, row.place.lng
        for event in FIVE:
            mode, rounding = v[event][0], v[event][1] if event != "ASR" else v[event][2]
            sun = sun_of(row, model, mode)
            t0 = ref.transit(row.cycle, sun, lng)
            if event == "DHUHR":
                t = t0
            elif event == "ASR":
                k, zenith = v["ASR"][3], abs(lat - noon_declination(row, model, mode, sun))
                if v["ASR"][1] == "APPARENT":
                    h0_app = 90 - zenith + bennett(90 - zenith)
                    a_app = ref.atand(1 / (k + ref.tand(90 - h0_app)))
                    a = a_app - bennett(a_app)
                else:
                    a = ref.atand(1 / (k + ref.tand(zenith)))
                t = ref.event(row.cycle, sun, lat, lng, a, 1, t0)
            else:
                side, alt = {"FAJR": (-1, -v["FAJR"][2]), "SUNRISE": (-1, v["SUNRISE"][2]), "MAGHRIB": (1, v["MAGHRIB"][2])}[event]
                t = ref.event(row.cycle, sun, lat, lng, alt, side, t0)
            t = row.cycle.instant(t) + 60 * (v["DHUHR"][2] if event == "DHUHR" else 0)
            lo, hi = EDGES[rounding]
            p = row.published[event]
            windows[event].append((p + lo - t, p + hi - t))
    out = {}
    for event, ws in windows.items():
        count, spans = sweep(ws)
        lo, hi = max(spans, key=lambda s: s[1] - s[0])
        out[event] = ((lo + hi) / 2, count, len(ws))
    return out


# --- Scores ----------------------------------------------------------------------------------------------------

def computed(method: dict, place: Place, day: datetime.date, ramadan: bool) -> tuple[dict[str, int], set[str]]:
    """The app's minutes (epoch seconds) at a KACST zone, and the events not COMPUTED."""
    zone = common.zone_json(place.id, place.lat, place.lng, authority=AUTHORITY)
    out = common.compute(common.case(method, place.lat, place.lng, TZ, day, zone=zone, country="SA", ramadan=ramadan))
    return {e: v[1] for e, v in out.items()}, {e for e, v in out.items() if v[2] != "COMPUTED"}


@dataclass
class TableScore:
    table: Table
    score: common.Score
    quirk_days: list[datetime.date]
    ramadan_days: int
    worst: int                          # quirk days included
    statuses: Counter


def score_table(method: dict, table: Table) -> TableScore:
    score, quirks, ramadan, worst, statuses = common.Score(), [], 0, 0, Counter()
    for row in table.rows:
        own, odd = computed(method, row.place, row.day, row.ramadan)
        statuses.update(odd)
        diff = {e: (own[e] - row.published[e]) // 60 for e in EVENTS}
        worst = max(worst, *(abs(d) for d in diff.values()))
        ramadan += row.ramadan
        if 2 <= row.hijri[2] <= 6 and any(diff[e] for e in FIVE):
            previous, _ = computed(method, row.place, row.day - ONE_DAY, False)
            if all(previous[e] + 86400 == row.published[e] for e in FIVE):
                quirks.append(row.day)
                continue
        for e in EVENTS:
            score.add(e, row.day, own[e] // 60, row.published[e] // 60)
    return TableScore(table, score, quirks, ramadan, worst, statuses)


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


def year_samples(tables: list[Table]) -> list[dict]:
    out = []
    for year in sorted({t.year for t in tables}):
        of_year = [t for t in tables if t.year == year]
        days = sorted({row.day for t in of_year for row in t.rows})
        out.append(common.sample(SOURCE, days[0], days[-1], len(days), len(of_year)))
    return out


def stats_of(fitted_on: list[Table], held_out: list[TableScore], regions: bool = False) -> dict:
    stats = common.fit_stats(year_samples(fitted_on), year_samples([s.table for s in held_out]),
                             merged([s.score for s in held_out]),
                             ramadan_days=sum(s.ramadan_days for s in held_out),
                             quirk_days=sum(len(s.quirk_days) for s in held_out),
                             regions=sorted({s.table.place.region for s in held_out}) if regions else None)
    stats["maxDiffMin"] = max(s.worst for s in held_out)
    return stats


def tier_a(stats: dict, all_regions: int) -> list[str]:
    """FitStats.passesTierA (9.5) for sa_umm_al_qura, as the failures it finds."""
    failures = []
    if not stats["heldOut"]:
        failures.append("no held-out data")
    for e in EVENTS:
        if stats["exact"][e] < 0.97:
            failures.append(f"{e} {stats['exact'][e]:.2%} exact")
        if stats["within1"][e] < 1.0:
            failures.append(f"{e} {stats['within1'][e]:.2%} within 1 min")
        if min(stats["daysPerMonth"][e]) < 7:
            failures.append(f"{e} under 7 days in a month")
    if stats["heldOutRamadanDays"] < 29:
        failures.append("no whole held-out Ramadan")
    if len(stats.get("regions", [])) < all_regions:
        failures.append(f"held-out points in {len(stats.get('regions', []))} of {all_regions} regions")
    return failures


def shares(scores: list[TableScore]) -> str:
    s = merged([x.score for x in scores]).shares()
    return ", ".join(f"{e} {s[e]:.2%}" for e in EVENTS)


# --- Main ------------------------------------------------------------------------------------------------------

def main() -> int:
    places = kacst_places()
    region_names = {r["code"]: r["name_en"] for r in json.loads((SA_DIR / "sa/web/regions.json").read_text(encoding="utf-8"))}
    every = kacst_tables(places)
    tables = [t for t in every if t.year in APP_YEARS]
    side = [t for t in every if t.year not in APP_YEARS]
    print(f"{AUTHORITY}: {len(places)} KACST places in {len(region_names)} regions; {len(tables)} year tables at "
          f"{len({t.place.id for t in tables})} of them ("
          + ", ".join(f"{y}: {sum(t.year == y for t in tables)}" for y in sorted({t.year for t in tables}))
          + f"), plus {len(side)} outside {APP_YEARS.start}-{APP_YEARS.stop - 1} (side check)")

    rows = tuple(row for t in tables for row in t.rows)
    dw = dhuhr_windows(list(rows))
    fold_values = {}
    for name, fit_years, _ in FOLDS:
        idx = [i for i, row in enumerate(rows) if row.day.year in fit_years]
        values, picks, report = fit_fold(rows, dw, idx)
        fold_values[name] = values
        print(f"fold {name}, fitted on {'+'.join(map(str, fit_years))} ({len(idx)} days):")
        print("  1 Dhuhr, most windows per sun model: " + ", ".join(f"{m} {n}" for m, n in report["per_model"].items()))
        print(f"    offset after the transit under nearest rounding, {picks['DHUHR'].text(' s', 2)}")
        print(f"    -> {values['DHUHR'][1]} + {values['DHUHR'][2]} min, and a shift of {values['shift']} s")
        for stage, event, unit in ((2, "SUNRISE", " deg"), (2, "MAGHRIB", " deg"), (3, "FAJR", " deg"), (4, "ASR", "")):
            print(f"  {stage} {event.title()}, {picks[event].text(unit)}")
        print("  3 Isha - Maghrib: " + ", ".join(f"{'Ramadan' if flag else 'other days'} {n} min on {c}/{t}"
                                               for flag, (n, c, t) in sorted(report["isha"].items())))
    if fold_values["A"] != fold_values["B"]:
        print("folds disagree:", {k: (fold_values["A"][k], fold_values["B"][k]) for k in fold_values["A"]
                                  if fold_values["A"][k] != fold_values["B"][k]})
        return 1
    values = fold_values["A"]
    method = profile_of(values)
    print("folds agree: " + json.dumps({k: method[k] for k in ("fajr", "isha", "offsets", "asrShadow", "asrSun", "sunModel",
                                                               "rounding", "horizon", "sunEvaluation")})
          + f"; seasonalOffsets {sorted(method['seasonalOffsets'])} = {values['shift'] / 60:g} min")
    print("  each time alone wants a shift of (s): " + ", ".join(
        f"{e} {mid:+.2f} ({n}/{total})" for e, (mid, n, total) in own_shifts(list(rows), values).items()))

    scores = {id(t): score_table(method, t) for t in tables}
    zone_fit = {}
    for place_id in sorted({t.place.id for t in tables}):
        own = [t for t in tables if t.place.id == place_id]
        fit_years = {y for _, fit, held in FOLDS if any(t.year in held for t in own) for y in fit}
        zone_fit[place_id] = stats_of([t for t in tables if t.year in fit_years], [scores[id(t)] for t in own])
    profile = stats_of(tables, list(scores.values()), regions=True)
    total = merged([s.score for s in scores.values()]).total
    print("held out, exact (quirk days left out): " + ", ".join(f"{e} {profile['exact'][e]:.2%}" for e in EVENTS)
          + f" of {total['FAJR']} days")
    print("  within 1 minute: " + ", ".join(f"{e} {profile['within1'][e]:.2%}" for e in EVENTS)
          + f"; max {profile['maxDiffMin']} min with quirk days, {max(s.score.worst for s in scores.values())} without")
    for name, _, held_years in FOLDS:
        print(f"  fold {name} ({'+'.join(map(str, held_years))}): "
              + shares([s for s in scores.values() if s.table.year in held_years]))
    before = [score_table(profile_of(CURRENT), t) for t in tables]
    print(f"  the profile before this fit (MEEUS, no shift) on the same tables: {shares(before)}")
    per_table = sorted(len(s.quirk_days) for s in scores.values())
    statuses = sum((s.statuses for s in scores.values()), Counter())
    print(f"quirk days: {profile['quirkDays']} ({per_table[0]}-{per_table[-1]} per table); held-out Ramadan days: "
          f"{profile['heldOutRamadanDays']}; fewest days in a month: {min(min(v) for v in profile['daysPerMonth'].values())}; "
          f"times not COMPUTED: {dict(statuses) or 'none'}")
    worst_zone = min(zone_fit, key=lambda z: min(zone_fit[z]["exact"].values()))
    print(f"zones with their own record: {len(zone_fit)}; lowest exact share "
          f"{min(zone_fit[worst_zone]['exact'].values()):.2%} ({next(p.name_en for p in places if p.id == worst_zone)}); "
          f"all within 1 minute: {all(min(z['within1'].values()) == 1.0 for z in zone_fit.values())}")
    missing = sorted(set(region_names) - set(profile["regions"]))
    failures = tier_a(profile, len(region_names))
    print(f"regions held out: {len(profile['regions'])} of {len(region_names)}"
          + (f" (missing {', '.join(f'{c} {region_names[c]}' for c in missing)})" if missing else "")
          + f"; tier A: {'passes' if not failures else 'fails: ' + '; '.join(failures)}")
    for t in side:
        s = score_table(method, t)
        print(f"side check {t.place.name_en} {t.year}: " + ", ".join(f"{e} {s.score.shares()[e]:.1%}" for e in EVENTS)
              + f" ({len(s.quirk_days)} quirk days, max {s.worst} min)")

    zones = []
    for p in places:
        zone = {"id": p.id, "nameAr": p.name_ar, "point": common.point(p.lat, p.lng), "admin1": [p.region],
                "reachKm": REACH_KM}
        if p.id in zone_fit:
            zone["fit"] = zone_fit[p.id]
        zones.append(zone)
    path = common.write_asset(AUTHORITY, AUTHORITY, "SA", "NEAREST_LOCAL", zones, fit=profile, source=SOURCE_LINE)
    print(f"wrote {path.relative_to(common.ROOT).as_posix()}: {len(zones)} zones, {len(zone_fit)} with their own record")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
