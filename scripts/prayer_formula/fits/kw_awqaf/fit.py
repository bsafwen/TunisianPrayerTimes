#!/usr/bin/env python3
"""
Fits `kw_awqaf`, the Kuwaiti Ministry of Awqaf's perpetual timetable for «مدينة الكويت وضواحيها», computed on Dr Saleh
Al-Ojairi's calendar (plan 8.1, 8.2, 8.7, 8.8, Q15; milestone AM10, harness 9.4), and writes its official-zone
asset, android-app/app/src/main/assets/official-zones/kw_awqaf.json (schema 1).

Data, read at run time and never copied (D13, A7):
- test-data-local/research-2026-10-08/KW/kw/data.txt: March, June, October, November and December, transcribed from
  the ministry's sheets (photos on awgatalsalat.com/calendar/MM.jpg).
- test-data-local/research-2026-10-08/KW/kw_skeptic/all.txt: June, October and December transcribed again
  independently; its October is used (it reads 22 October's Dhuhr 11:33, as the crop rows20_25.png shows, where
  data.txt has 11:32); June and December agree with data.txt.
- test-data-local/research-2026-10-11/KW/feb.txt: February, with the sheet's own 29 February row.
- test-data-local/research-2026-10-11/GEONAMES/cities1000.zip: the points of Kuwait City, Ahmadi, Wafra, Jahra,
  Abdali (Mazari' al 'Abdali) and Failaka (its village, Az Zawr); the other six (islands and border places) are
  GeoNames points read from geonames.org on 2026-10-15 (PLACES below).

The sheet is perpetual (by month and day, no year): the fit reads each row at a reference year (E5), and every
held-out score is computed at the row's month and day in 2026, as the app shows it (A8). The sheet's 29 February
row is read at the leap day after the reference year, and 29 February and 1 March (where the printed Fajr and
sunrise go out of sequence) are quirk days, left out of the fit and counted apart (Q15).

The search loops use per-day interpolated sun tables fed to the reference engine's own solver functions; every
score printed and every FitStats comes from common.compute, the reference engine.

Stages (9.4), each by windows (a published minute bounds the unrounded time to a minute, so each day bounds the
parameter; the value most days allow wins, read to the data's resolution):
0. The sun model and reference year: for each, the six times' best window counts, each time with its own free
   parameter, summed over the point grid's best point.
1. Dhuhr: the rounding and the offset after the transit, which fix the computing longitude.
2. The computing point: the latitude by the conventional values' counts (Fajr 18, Isha 17.5, the standard horizon,
   Asr factor 1), one point for all six times or (E6) one for Fajr and sunrise and one for the rest; the two-point
   reading is kept only where the month-split held-out scores gain more than TWO_POINT_GAIN.
3. Sunrise, Maghrib, Fajr, Isha and Asr: rounding, horizon, angle, geometric or apparent sun, at that point.
Held out: by month-split (fold A fits on the odd months the sheets cover, March and November, and scores the even
ones, February, June, October and December; fold B the reverse).

The 11 other places (the sheet's footer, «فرق التوقيت بالدقائق على حساب الباحث الفلكي د. صالح العجيري») are zones with
`baseZoneId` Kuwait City and the published offset on all six times (Q15).

Usage: python scripts/prayer_formula/fits/kw_awqaf/fit.py
"""

from __future__ import annotations

import datetime
import functools
import math
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import common  # noqa: E402

ref = common.ref
EVENTS = common.EVENTS
AUTHORITY = "kw_awqaf"
TZ = "Asia/Kuwait"                        # +03 without summer time (a link to Asia/Riyadh)
KW = common.LOCAL_DATA / "research-2026-10-08" / "KW"
FEB = common.LOCAL_DATA / "research-2026-10-11" / "KW" / "feb.txt"
GEONAMES = common.LOCAL_DATA / "research-2026-10-11" / "GEONAMES" / "cities1000.zip"
SOURCE = "kw_awqaf_perpetual_sheets"
SOURCE_LINE = ("The Ministry of Awqaf's perpetual timetable for Kuwait City and its suburbs (Mosques Sector, on Dr Saleh "
               "Al-Ojairi's calendar), with the footer's minute offsets for 11 places; shown as printed, at its reference year 2011")
EVIDENCE = "The Ministry of Awqaf's perpetual timetable, as printed (reference year 2011)"
MODELS = ("MEEUS", "VSOP87", "TEXTBOOK")
YEARS = range(1990, 2027)                 # reference years tried, to the year of the edition awgatalsalat names
SCORE_YEAR = 2026                         # the held-out rows are scored at their month and day in this year (A8)
ROUNDINGS = ("NEAREST", "DOWN", "UP")
EDGES = {"NEAREST": (-30, 30), "UP": (-60, 0), "DOWN": (0, 60)}
STANDARD_ALTITUDE = -0.8333
TOLERANCE = 0.0075
TWO_POINT_GAIN = 0.005                    # E6's two points only where they gain more than 0.5 % held out
LATITUDES = tuple(round(29.25 + 0.005 * i, 3) for i in range(29))     # 29.250 .. 29.390
LONGITUDES = tuple(round(47.95 + 0.005 * i, 3) for i in range(13))    # 47.950 .. 48.010
FOLDS = (("A", (3, 11)), ("B", (2, 6, 10, 12)))
QUIRKS = ((2, 29), (3, 1))
EPOCH = datetime.date(1970, 1, 1)
# The sheet's places: zone id, Arabic name as the footer prints it, published offset (minutes, all six times), and
# where the point comes from: a cities1000 row (its GeoNames id), or a GeoNames point read from geonames.org on
# 2026-10-15 (id, latitude, longitude). Failaka takes its village, Az Zawr; an uninhabited island its own point.
PLACES = (
    ("kuwait_city", "مدينة الكويت", 0, "285787"),
    ("ahmadi", "الأحمدي", 0, "285839"),
    ("wafra", "الوفرة", 0, "285760"),
    ("failaka", "فيلكا", -2, "285673"),
    ("bubiyan", "بوبيان", -1, ("285659", 29.76972, 48.25556)),          # Bubiyan Island, N 29 46 11 E 48 15 20
    ("jahra", "الجهراء", 2, "285799"),
    ("nuwaiseeb", "النويصيب", -2, ("387854", 28.54694, 48.39917)),     # An Nuwaysib, locality
    ("salmi", "السالمي", 6, ("285459", 29.1, 46.67917)),               # Markaz Hudud as Salmi, N 29 06 00 E 46 40 45
    ("abdali", "العبدلي", 2, "11579278"),
    ("kubbar", "كبر", -2, ("285571", 29.07222, 48.49333)),             # Kubbar Island, N 29 04 20 E 48 29 36
    ("qaruh", "قاروه", -3, ("285494", 28.8175, 48.77611)),             # Qaruh Island, N 28 49 03 E 48 46 34
    ("umm_al_maradim", "أم المرادم", -3, ("285420", 28.68, 48.65167)),  # Umm al Maradim Island, N 28 40 48 E 48 39 06
)


def say(text: str) -> None:
    print(text, flush=True)


# --- Data ------------------------------------------------------------------------------------------------------

def read_sheet(path: Path, months: set[int] | None = None) -> dict[tuple[int, int], list[int]]:
    """month, day -> the six printed times in minutes after midnight (Asr, Maghrib and Isha PM; Dhuhr 11:xx or 12:xx)."""
    out = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("#") or not line.strip():
            continue
        p = line.split()
        mo, d = int(p[0]), int(p[1])
        if months and mo not in months:
            continue
        minutes = []
        for k, s in enumerate(p[2:8]):
            h, m = (int(x) for x in s.split(":"))
            if (k == 2 and h < 11) or (k >= 3 and h < 12):
                h += 12
            minutes.append(60 * h + m)
        out[(mo, d)] = minutes
    return out


def sheets() -> tuple[dict, list[str]]:
    a = read_sheet(KW / "kw" / "data.txt")
    b = read_sheet(KW / "kw_skeptic" / "all.txt")
    notes = [f"{mo:02d}-{d:02d}: data.txt {a.get((mo, d))} against {b[(mo, d)]}" for (mo, d) in sorted(b) if a.get((mo, d)) != b[(mo, d)]]
    sheet = dict(a)
    sheet.update(b)
    sheet.update(read_sheet(FEB))
    for (mo, d), v in sheet.items():
        if v != sorted(v):
            raise SystemExit(f"{mo:02d}-{d:02d}: times out of order")
    return dict(sorted(sheet.items())), notes


def leap_after(year: int) -> int:
    y = year
    while not (y % 4 == 0 and (y % 100 != 0 or y % 400 == 0)):
        y += 1
    return y


@dataclass
class Row:
    month: int
    day_of_month: int
    minutes: list[int]

    @property
    def quirk(self) -> bool:
        return (self.month, self.day_of_month) in QUIRKS

    def date(self, year: int) -> datetime.date:
        return datetime.date(leap_after(year) if (self.month, self.day_of_month) == (2, 29) else year, self.month, self.day_of_month)

    def published(self, year: int) -> dict[str, int]:
        d = self.date(year)
        midnight = (d - EPOCH).days * 86400 - common.utc_offset(TZ, d, 0)
        return {e: midnight + 60 * m for e, m in zip(EVENTS, self.minutes)}


def geonames(ids: set[str]) -> dict[str, tuple[float, float, str]]:
    out = {}
    with zipfile.ZipFile(GEONAMES) as z, z.open("cities1000.txt") as fh:
        for line in fh:
            v = line.decode("utf-8").rstrip("\n").split("\t")
            if v[0] in ids:
                out[v[0]] = (float(v[4]), float(v[5]), v[1])
    missing = ids - set(out)
    if missing:
        raise SystemExit(f"GeoNames rows not in cities1000: {sorted(missing)}")
    return out


# --- The fast sun and the windows --------------------------------------------------------------------------------

class DaySun:
    """The model's sun on a 30-minute grid over each needed day's cycle (linear interpolation; only the search
    loops use it)."""

    def __init__(self, model: str, days: list[datetime.date]):
        f = ref.SUN_MODELS[model]
        self.tables = {}
        for d in days:
            jd0 = ref.julian_day0(d)       # each table covers [jd0 - 0.5, jd0 + 1.5)
            self.tables[int(jd0 + 0.5)] = (jd0 - 0.5, [f(jd0 - 0.5 + k / 48) for k in range(0, 97)])

    def __call__(self, jd: float):
        k = int(math.floor(jd + 0.5))
        start, values = self.tables.get(k) or self.tables.get(k + 1) or self.tables[k - 1]
        x = (jd - start) * 48
        i = min(max(int(x), 0), len(values) - 2)
        w = x - i
        a, b = values[i], values[i + 1]
        return a[0] + (b[0] - a[0]) * w, a[1] + (b[1] - a[1]) * w, a[2] + (b[2] - a[2]) * w


def sweep(windows: list[tuple[float, float]]) -> tuple[int, list[tuple[float, float]]]:
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
    at_value: int
    best: int
    n: int


def pick(windows: dict[tuple, list], idx: list[int], keys: list[tuple], prefer=(), scale: float = 1.0) -> Pick:
    """Among the configurations and values covered by at least the best count minus TOLERANCE of the days: a
    preferred value first, then the fewest decimals (of value / scale), then the earlier configuration."""
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
    best_any = max(sweep([windows[key][i] for i in idx])[0] for key in keys)
    found = [(-sum(windows[key][i][0] <= v < windows[key][i][1] for i in idx), rank, j, key, v)
             for rank, key in enumerate(keys) for j, v in enumerate(values)]
    minus, _, _, key, v = min(found)
    return Pick(key, v, -minus, best_any, len(idx))


def bennett(a: float) -> float:
    return ref.bennett(a, 1010.0, 10.0)   # a zone without pressure or temperature: 1010 hPa, 10 C (S5)


def shadow_factor(a: float, zenith: float, asr_sun: str) -> float:
    """S7 inverted: the factor k whose Asr altitude is the geometric altitude a, with the noon zenith distance."""
    if asr_sun == "GEOMETRIC":
        return 1 / ref.tand(a) - ref.tand(zenith)
    h0_app = 90 - zenith + bennett(90 - zenith)
    a_app = a
    for _ in range(8):                    # a_app = a + R(a_app): converged to 1e-10 degree
        a_app = a + bennett(a_app)
    return 1 / ref.tand(a_app) - ref.tand(90 - h0_app)


def windows_at(rows: list[Row], year: int, sun, lat: float, lng: float) -> dict[tuple, list]:
    """Every time's windows at one point: (DHUHR, rounding) the offset after the transit (s); (SUNRISE | MAGHRIB,
    rounding) the altitude; (FAJR | ISHA, rounding) the angle; (ASR, sun, rounding) the shadow factor."""
    out: dict[tuple, list] = {}
    for row in rows:
        d = row.date(year)
        p = row.published(year)
        cy = ref.Cycle(d)
        t0 = ref.transit(cy, sun, lng)
        transit = cy.instant(t0)
        zenith = abs(lat - sun(cy.jd0 + t0 / 24)[0])
        for r, (a, b) in EDGES.items():
            out.setdefault(("DHUHR", r), []).append((p["DHUHR"] + a - transit, p["DHUHR"] + b - transit))
        for e in ("FAJR", "SUNRISE", "ASR", "MAGHRIB", "ISHA"):
            alt = {k: ref.altitude(cy, sun, lat, lng, cy.hours(p[e] + k)) for k in (-60, -30, 0, 30, 60)}
            if e == "ASR":
                for s in ("GEOMETRIC", "APPARENT"):
                    kf = {k: shadow_factor(a, zenith, s) for k, a in alt.items()}
                    for r, (a, b) in EDGES.items():
                        out.setdefault(("ASR", s, r), []).append((min(kf[a], kf[b]), max(kf[a], kf[b])))
                continue
            for r, (a, b) in EDGES.items():
                x, y = (alt[a], alt[b]) if e in ("SUNRISE", "MAGHRIB") else (-alt[b], -alt[a])
                out.setdefault((e, r), []).append((min(x, y), max(x, y)))
    return out


# --- Stages ----------------------------------------------------------------------------------------------------

CONVENTIONAL = {"FAJR": 18.0, "SUNRISE": STANDARD_ALTITUDE, "MAGHRIB": STANDARD_ALTITUDE, "ISHA": 17.5}


@dataclass
class Fitted:
    model: str
    year: int
    morning: tuple[float, float]
    evening: tuple[float, float]
    picks: dict[str, Pick]
    report: dict


class Grid:
    """Windows of every grid point for one model and reference year, all rows; folds subset them."""

    def __init__(self, rows: list[Row], model: str, year: int, points):
        days = sorted({row.date(year) for row in rows})
        sun = DaySun(model, days)
        self.at = {(lat, lng): windows_at(rows, year, sun, lat, lng) for lat, lng in points}


def point_counts(w: dict, idx: list[int]) -> dict[str, int]:
    """The conventional values' counts at one point (best rounding each): what decides the point."""
    out = {e: max(covering([w[(e, r)][i] for i in idx], v) for r in ROUNDINGS) for e, v in CONVENTIONAL.items()}
    out["DHUHR"] = max(covering([w[("DHUHR", r)][i] for i in idx], 0.0) for r in ROUNDINGS)
    out["ASR"] = max(covering([w[("ASR", s, r)][i] for i in idx], 1.0) for s in ("GEOMETRIC", "APPARENT") for r in ROUNDINGS)
    return out


def free_counts(w: dict, idx: list[int]) -> int:
    """Stage 0: each time's best window count with its own parameter free."""
    total = 0
    for e in ("FAJR", "SUNRISE", "MAGHRIB", "ISHA", "DHUHR"):
        total += max(sweep([w[(e, r)][i] for i in idx])[0] for r in ROUNDINGS)
    total += max(covering([w[("ASR", s, r)][i] for i in idx], 1.0) for s in ("GEOMETRIC", "APPARENT") for r in ROUNDINGS)
    return total


def choose_points(grid: Grid, idx: list[int], two: bool) -> tuple[tuple[float, float], tuple[float, float], dict]:
    counts = {pt: point_counts(w, idx) for pt, w in grid.at.items()}
    morning_events, evening_events = ("FAJR", "SUNRISE"), ("DHUHR", "ASR", "MAGHRIB", "ISHA")
    if not two:
        best = max(counts, key=lambda pt: (sum(counts[pt].values()), -abs(pt[0] - 29.33)))
        return best, best, counts
    mo = max(counts, key=lambda pt: (sum(counts[pt][e] for e in morning_events), -abs(pt[0] - 29.33)))
    ev = max(counts, key=lambda pt: (sum(counts[pt][e] for e in evening_events), -abs(pt[0] - 29.33)))
    return mo, ev, counts


def fit(rows: list[Row], idx: list[int], model: str, year: int, grid: Grid, two: bool) -> Fitted:
    morning, evening, counts = choose_points(grid, idx, two)
    wm, we = grid.at[morning], grid.at[evening]
    picks = {
        "DHUHR": pick(we, idx, [("DHUHR", r) for r in ROUNDINGS], (0.0,), scale=60.0),
        "FAJR": pick(wm, idx, [("FAJR", r) for r in ROUNDINGS], (18.0,)),
        "SUNRISE": pick(wm, idx, [("SUNRISE", r) for r in ROUNDINGS], (STANDARD_ALTITUDE,)),
        "MAGHRIB": pick(we, idx, [("MAGHRIB", r) for r in ROUNDINGS], (STANDARD_ALTITUDE,)),
        "ISHA": pick(we, idx, [("ISHA", r) for r in ROUNDINGS], (17.5,)),
        "ASR": pick_at(we, idx, [("ASR", s, r) for s in ("GEOMETRIC", "APPARENT") for r in ROUNDINGS], (1.0, 2.0)),
    }
    return Fitted(model, year, morning, evening, picks, {"counts": counts})


def horizon_of(a: float) -> dict:
    return {"type": "standard"} if a == STANDARD_ALTITUDE else {"type": "fixed", "altitudeDeg": a}


def method_of(f: Fitted) -> dict:
    p = f.picks
    _, asr_sun, asr_rounding = p["ASR"].config
    offset = p["DHUHR"].value / 60
    whole = round(offset)
    rounding = {"FAJR": p["FAJR"].config[1], "SUNRISE": p["SUNRISE"].config[1], "DHUHR": p["DHUHR"].config[1],
                "ASR": asr_rounding, "MAGHRIB": p["MAGHRIB"].config[1], "ISHA": p["ISHA"].config[1]}
    return common.method_json(
        id=AUTHORITY, fajr={"type": "angle", "degrees": p["FAJR"].value}, isha={"type": "angle", "degrees": p["ISHA"].value},
        offsets={"DHUHR": whole} if whole else {}, dhuhrAfterTransitMin=round(offset - whole, 6),
        asrShadow=int(p["ASR"].value), asr={"type": "shadow"}, asrSun=asr_sun, sunModel=f.model, rounding=rounding,
        horizon={"sunrise": horizon_of(p["SUNRISE"].value), "sunset": horizon_of(p["MAGHRIB"].value)}, referenceYear=f.year,
        evidence={"kind": "FROM_TABLES", "source": EVIDENCE},
    )


def describe(f: Fitted) -> str:
    p = f.picks
    return (f"{f.model} at {f.year}; morning {f.morning}, evening {f.evening}; "
            + ", ".join(f"{e} {p[e].config[1:] if e != 'DHUHR' else p[e].config[1]} {p[e].value:g} ({p[e].at_value}/{p[e].n}, best {p[e].best})"
                        for e in EVENTS))


# --- Zones and scores --------------------------------------------------------------------------------------------

def base_zone(f: Fitted, city: tuple[float, float]) -> dict:
    z = common.zone_json("kuwait_city", city[0], city[1], authority=AUTHORITY)
    if f.morning != city or f.evening != city:
        z["morningPoint"] = common.point(*f.morning)
        z["eveningPoint"] = common.point(*f.evening)
    return z


def place_zone(zone_id: str, lat: float, lng: float, offset: int) -> dict:
    return common.zone_json(zone_id, lat, lng, authority=AUTHORITY, baseZoneId="kuwait_city",
                            offsets={e: float(offset) for e in EVENTS} if offset else {})


def score(method: dict, base: dict, rows: list[Row], year: int, place: dict | None = None, offset: int = 0):
    """The app's minutes against the printed minute (+ the place's published offset); quirk rows counted apart."""
    s = common.Score()
    quirks = 0
    for row in rows:
        d = row.date(year)
        p = row.published(year)
        z = place or base
        lat, lng = z["point"]["latE5"] / 1e5, z["point"]["lngE5"] / 1e5
        out = common.compute(common.case(method, lat, lng, TZ, d, zone=z, base_zone=base if place else None, country="KW"))
        if row.quirk:
            quirks += 1
            s.worst = max(s.worst, *(abs(out[e][1] // 60 - (p[e] // 60 + offset)) for e in EVENTS))
            continue
        for e in EVENTS:
            s.add(e, d, out[e][1] // 60, p[e] // 60 + offset)
    return s, quirks


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
    return ", ".join(f"{e} {s.exact[e]}/{s.total[e]} ({s.exact[e] / max(1, s.total[e]):.1%})" for e in EVENTS)


def overall(s: common.Score) -> float:
    return sum(s.exact[e] for e in EVENTS) / max(1, sum(s.total[e] for e in EVENTS))


# --- Main ------------------------------------------------------------------------------------------------------

def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    sheet, notes = sheets()
    rows = [Row(mo, d, v) for (mo, d), v in sheet.items()]
    say(f"{AUTHORITY}: {len(rows)} sheet rows, months {sorted({r.month for r in rows})}; transcriptions differ at "
        + ("; ".join(notes) or "no row") + " (the second reading kept)")
    ids = {p[3] for p in PLACES if isinstance(p[3], str)}
    pts = geonames(ids)
    city = pts["285787"][:2]
    fit_idx = [i for i, r in enumerate(rows) if not r.quirk]

    # Stage 0: the sun model and the reference year, at a coarse grid around Kuwait City; each fold chooses its own.
    coarse = [(lat, lng) for lat in (29.30, 29.33, 29.36) for lng in (47.97, 47.99)]
    coarse_grids = {}
    for model in MODELS:
        for year in YEARS:
            coarse_grids[(model, year)] = Grid(rows, model, year, coarse)
        say(f"  stage 0 windows for {model}, {YEARS.start}-{YEARS.stop - 1}")

    def stage0(idx: list[int]) -> list[tuple[int, str, int]]:
        return sorted(((max(free_counts(w, idx) for w in g.at.values()), m, y) for (m, y), g in coarse_grids.items()), reverse=True)

    ranked = stage0(fit_idx)
    _, model, year = ranked[0]
    say(f"0 sun model and reference year: {model} {year} ({ranked[0][0]} of {6 * len(fit_idx)} windows); "
        + "; ".join(f"{m} {y} {n}" for n, m, y in ranked[1:8]))
    for m in MODELS:
        say(f"  {m}: best years " + ", ".join(f"{y} {n}" for n, mm, y in ranked if mm == m)[:80])
    points = [(lat, lng) for lat in LATITUDES for lng in LONGITUDES]
    grids = {}

    def grid_for(m: str, y: int) -> Grid:
        if (m, y) not in grids:
            grids[(m, y)] = Grid(rows, m, y, points)
            say(f"  windows at {len(points)} points for {m} {y}")
        return grids[(m, y)]

    grid = grid_for(model, year)
    results = {}
    for two in (False, True):
        folds = {}
        for name, months in FOLDS:
            idx = [i for i in fit_idx if rows[i].month in months]
            _, m_f, y_f = stage0(idx)[0]
            folds[name] = (months, fit(rows, idx, m_f, y_f, grid_for(m_f, y_f), two))
        final = fit(rows, fit_idx, model, year, grid, two)
        held = []
        for name, (months, f) in folds.items():
            scored = [r for r in rows if r.month not in months]
            s, _ = score(method_of(f), base_zone(f, city), scored, SCORE_YEAR)
            held.append(s)
        results[two] = (folds, final, merged(held))
        say(f"{'two points (E6)' if two else 'one point'}: held out at {SCORE_YEAR} {overall(results[two][2]):.2%}; all rows: {describe(final)}")
    two = overall(results[True][2]) - overall(results[False][2]) > TWO_POINT_GAIN
    folds, final, held_total = results[two]
    say(f"2 the point: {'two points (E6)' if two else 'one point'} kept (held-out gain of two points "
        f"{overall(results[True][2]) - overall(results[False][2]):+.2%}, threshold {TWO_POINT_GAIN:.1%})")
    for name, (months, f) in folds.items():
        say(f"  fold {name} (fitted on months {','.join(map(str, months))}): {describe(f)}")
    p = final.picks
    say(f"3 at the chosen point(s): Fajr 18 on {covering([grid.at[final.morning][('FAJR', p['FAJR'].config[1])][i] for i in fit_idx], 18.0)}, "
        f"Isha 17.5 on {covering([grid.at[final.evening][('ISHA', p['ISHA'].config[1])][i] for i in fit_idx], 17.5)}, "
        f"Asr k = 1 geometric on {max(covering([grid.at[final.evening][('ASR', 'GEOMETRIC', r)][i] for i in fit_idx], 1.0) for r in ROUNDINGS)} "
        f"and apparent on {max(covering([grid.at[final.evening][('ASR', 'APPARENT', r)][i] for i in fit_idx], 1.0) for r in ROUNDINGS)} "
        f"of {len(fit_idx)} days")

    method = method_of(final)
    base = base_zone(final, city)
    at_ref, _ = score(method, base, rows, year)
    say(f"the asset's values on all rows at the reference year {year} (in-sample): {shares(at_ref)}; worst {at_ref.worst} min")
    for y in (2025, 2026, 2027, 2028):
        s, q = score(method, base, rows, y)
        say(f"  the same computed for {y} (as the app shows it): {shares(s)}; worst {s.worst} min (quirk days included)")
    say(f"held out (month-split) at {SCORE_YEAR}: {shares(held_total)}; within 1 min: "
        + ", ".join(f"{e} {held_total.within1[e] / held_total.total[e]:.1%}" for e in EVENTS) + f"; worst {held_total.worst} min")
    plan = common.method_json(id=AUTHORITY, fajr={"type": "angle", "degrees": 18.0}, isha={"type": "angle", "degrees": 17.5},
                              evidence=method["evidence"])
    before, _ = score(plan, common.zone_json("kuwait_city", *city, authority=AUTHORITY), rows, SCORE_YEAR)
    say(f"the plan's profile before this fit (18/17.5, MEEUS, geometric Asr, at Kuwait City's point) for {SCORE_YEAR}: {shares(before)}")

    sample = common.sample(SOURCE, datetime.date(year, 2, 1), datetime.date(year, 12, 31), len(rows), 1)
    split = common.sample(SOURCE, datetime.date(SCORE_YEAR, 2, 1), datetime.date(SCORE_YEAR, 12, 31), len(rows), 1, month_split=True)
    quirk_days = sum(r.quirk for r in rows)
    base_stats = common.fit_stats([sample], [split], held_total, quirk_days=quirk_days)
    base_stats["maxDiffMin"] = held_total.worst
    zones = []
    for zone_id, name_ar, offset, where in PLACES:
        gid, lat, lng = (where, *pts[where][:2]) if isinstance(where, str) else where
        named = {"centre": common.point(lat, lng), "catalogueIds": [int(gid)] if isinstance(where, str) else []}
        if zone_id == "kuwait_city":
            z = dict(base, nameAr=name_ar, namedPlaces=[named], fit=base_stats)
        else:
            z = place_zone(zone_id, lat, lng, offset)
            held = []
            for _, (months, f) in folds.items():
                s, _ = score(method_of(f), base_zone(f, city), [r for r in rows if r.month not in months], SCORE_YEAR,
                             place=dict(place_zone(zone_id, lat, lng, offset)), offset=offset)
                held.append(s)
            st = merged(held)
            z.update(nameAr=name_ar, namedPlaces=[named], fit=dict(common.fit_stats([sample], [split], st, quirk_days=quirk_days), maxDiffMin=st.worst))
        zones.append(z)
        say(f"  zone {zone_id} {name_ar}: {lat:.5f} N {lng:.5f} E, offset {offset:+d}; held out exact "
            + ", ".join(f"{e} {z['fit']['exact'][e]:.1%}" for e in EVENTS))
    for z in zones:
        z["authority"] = None             # the asset names the authority once
        z.pop("offsetRule", None)
    profile = dict(base_stats)
    path = common.write_asset(AUTHORITY, AUTHORITY, "KW", "LISTED_PLACES", zones, fit=profile, source=SOURCE_LINE)
    say(f"wrote {path.relative_to(common.ROOT).as_posix()}: {len(zones)} zones; method {method['fajr']}, {method['isha']}, "
        f"offsets {method['offsets']}, dhuhrAfterTransitMin {method['dhuhrAfterTransitMin']}, rounding {method['rounding']}, "
        f"horizon {method['horizon']}, asrSun {method['asrSun']}, sunModel {method['sunModel']}; reference year {year} (A8: the app shows it every year)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
