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
best count, a conventional value first, then the fewest decimals).
1. Dhuhr: the sun model and the rounding, and the offset after the transit at the stated western longitude, in
   minutes (E7 `dhuhrAfterTransitMin`).
2. Each other time at a point of its own (E6, `eventPoints`; 2026-10-17): the calendar's times do not share the two
   stated points (fitted on 2026-10-15 at two points, Asr, Maghrib and Isha stayed at 94.4-96.9 % held out). Each
   time scans a grid of points (OWN_LATS x OWN_LNGS, between the two stated longitudes; Asr and Maghrib on the stated
   western longitude, which Dhuhr shares, their latitudes only) and keeps, among the points whose picked value covers
   the most days, the one nearest the middle of that plateau: Fajr and Isha at the stated 18 degrees where it holds,
   sunrise and Maghrib their horizon altitude (E2), Asr factor 1 on the geometric sun with the noon shadow at the
   declination of the Asr instant itself (E4 SHADOW_AT_EVENT); each with its rounding.
   Then, finer (2026-10-18): every grid point within NEAR days of the best gets a fine grid around it (FINE_LAT, and
   FINE_LNG for the times with a free longitude, half a grid cell each way), and the same rule picks among all the fine
   points. The grid's 0.005 degree of latitude hid narrow peaks: Asr's best latitude for the odd months is 26.1855 N
   (179 days), between two plateaus of 178 at the grid's points, and the grid's pick (26.20 N) lost 6 days held out.
Held out: the one calendar in hand, by month-split (fold A fits on the odd calendar months and scores the even ones,
fold B the reverse), every score from common.compute with the zone's own points. The asset takes the values fitted
on all 355 days; its FitStats are the folds' held-out scores.

Usage: python scripts/prayer_formula/fits/bh_calendar/fit.py
"""

from __future__ import annotations

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
ASR_MODE = "SHADOW_AT_EVENT"              # E4, shipped: the noon shadow at the declination of the Asr instant itself
MODEL = "VSOP87"                          # E14: the Dhuhr windows' best sun (349 of 355 days, MEEUS 348, TEXTBOOK 300)
OWN_LATS = tuple(round(26.10 + 0.005 * i, 3) for i in range(41))     # 26.100 .. 26.300
OWN_LNGS = tuple(round(50.44 + 0.02 * j, 2) for j in range(15))      # 50.44 .. 50.72: between the stated longitudes
OWN_TIMES = {"FAJR": (-1, 18.0, "east"), "SUNRISE": (-1, -0.8333, "east"), "ASR": (1, 1.0, "west"),
             "MAGHRIB": (1, -0.8333, "west"), "ISHA": (1, 18.0, "west")}     # side, stated value, stated point
NEAR = 2                                   # grid points within this many days of the best are refined
FINE_LAT, FINE_LNG = 0.0005, 0.002         # the fine grid's steps, five each way (half a grid cell)
ROUNDINGS = ("NEAREST", "DOWN", "UP")
EDGES = {"NEAREST": (-30, 30), "UP": (-60, 0), "DOWN": (0, 60)}   # E1: seconds around the published minute
STANDARD_ALTITUDE = -0.8333
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

def asr_event_windows(rows: list[Row], sun, lat: float, lng: float) -> dict[tuple, list]:
    """(ASR, rounding): per row, the shadow factor that puts the shipped Asr in the published minute: the geometric
    sun, the noon shadow at the declination of the Asr instant itself (each edge of the minute takes its own)."""
    out = {("ASR", r): [] for r in ROUNDINGS}
    for row in rows:
        edges = {d: row.published["ASR"] + d for d in (-60, -30, 0, 30, 60)}
        alt = {d: altitude(row, sun, lat, lng, t) for d, t in edges.items()}
        zen = {d: abs(lat - sun(ref.jd_of_instant(t))[0]) for d, t in edges.items()}
        k = {d: shadow_factor(a, zen[d], "GEOMETRIC") for d, a in alt.items()}
        for r, (a, b) in EDGES.items():
            out[("ASR", r)].append((min(k[a], k[b]), max(k[a], k[b])))
    return out


def own_windows(rows: list[Row], sun, west_lng: float) -> dict[tuple, dict]:
    """(time, lat, lng): that time's windows at each grid point; Asr and Maghrib on the stated western longitude only,
    which Dhuhr shares (Maghrib's point is the zone's evening point)."""
    grid = {}
    for e, (side, _, _) in OWN_TIMES.items():
        for lat in OWN_LATS:
            for lng in (west_lng,) if e in ("ASR", "MAGHRIB") else OWN_LNGS:
                grid[(e, lat, lng)] = (asr_event_windows(rows, sun, lat, lng) if e == "ASR"
                                       else angle_windows(rows, sun, lat, lng, e, side))
    say(f"  windows at {len(grid)} points of the grid")
    return grid


@dataclass
class Own:
    dhuhr: Pick                                 # (model, rounding): the offset after the transit, in s
    points: dict[str, tuple[float, float]]      # FAJR, SUNRISE, ASR, MAGHRIB, ISHA
    picks: dict[str, Pick]


def plateau(found: dict) -> tuple:
    """found: cell (lattice steps, integers) -> ((lat, lng), Pick). Among the cells whose pick covers the most days,
    the one nearest the middle of their largest connected group (the plateau's middle, not an edge of it)."""
    top = max(p.at_value for _, p in found.values())
    tied, groups = {c for c, (_, p) in found.items() if p.at_value == top}, []
    while tied:
        group, todo = [], [tied.pop()]
        while todo:
            c = todo.pop()
            group.append(c)
            for n in ((c[0] + 1, c[1]), (c[0] - 1, c[1]), (c[0], c[1] + 1), (c[0], c[1] - 1)):
                if n in tied:
                    tied.remove(n)
                    todo.append(n)
        groups.append(sorted(group))
    group = max(groups, key=lambda g: (len(g), [-x for c in g for x in c]))
    mi, mj = sum(c[0] for c in group) / len(group), sum(c[1] for c in group) / len(group)
    cell = min(group, key=lambda c: ((c[0] - mi) ** 2 + (c[1] - mj) ** 2, c))
    return found[cell]


def fit_own(grid: dict, dhuhr: dict, idx: list[int], windows_at=None) -> Own:
    """Each time's point, rounding and value on the rows idx: among the grid points whose pick covers the most days,
    the one nearest the middle of their largest connected group (the plateau's middle, not an edge of it); the stated
    value (18 degrees, the standard horizon, factor 1) first. With windows_at(time, lat, lng), the same rule then runs
    on the fine grid around every grid point within NEAR days of the best (half a grid cell each way)."""
    points, picks = {}, {}
    for e, (_, value, _) in OWN_TIMES.items():
        keys = [(e, r) for r in ROUNDINGS]

        def pick_of(w):
            return pick_at(w, idx, keys, (value,)) if e == "ASR" else pick(w, idx, keys, (value,))
        found = {(OWN_LATS.index(lat), OWN_LNGS.index(lng) if lng in OWN_LNGS else 0): ((lat, lng), pick_of(w))
                 for (name, lat, lng), w in grid.items() if name == e}
        if windows_at is not None:
            top = max(p.at_value for _, p in found.values())
            free_lng = e not in ("ASR", "MAGHRIB")
            fine = set()
            for (lat, lng), p in found.values():
                if p.at_value >= top - NEAR:
                    for a in range(-5, 6):
                        for b in (range(-5, 6) if free_lng else (0,)):
                            fine.add((round(lat + FINE_LAT * a, 4), round(lng + FINE_LNG * b, 3)))
            # cells in fine steps, so neighbours are one step apart whichever grid point they came from
            found = {(round(la / FINE_LAT), round(lo / FINE_LNG)): ((la, lo), pick_of(windows_at(e, la, lo))) for la, lo in fine}
        points[e], picks[e] = plateau(found)
    return Own(pick(dhuhr, idx, [(MODEL, r) for r in ("DOWN", "NEAREST", "UP")], scale=60.0), points, picks)


def describe(f: Own) -> str:
    return (f"Dhuhr {f.dhuhr.config[1]} + {f.dhuhr.value / 60:g} min ({f.dhuhr.at_value}/{f.dhuhr.n}); "
            + "; ".join(f"{e[:3].lower()} {f.points[e][0]:.3f} N {f.points[e][1]:.2f} E {f.picks[e].config[-1]} "
                        f"{f.picks[e].value:g} ({f.picks[e].at_value})" for e in OWN_TIMES))


def horizon_of(altitude_deg: float) -> dict:
    return {"type": "standard"} if altitude_deg == STANDARD_ALTITUDE else {"type": "fixed", "altitudeDeg": altitude_deg}


def method_of(f: Own) -> dict:
    p = f.picks
    k = p["ASR"].value
    if k not in (1.0, 2.0):
        raise SystemExit(f"Asr shadow factor {k} is neither 1 nor 2")
    rounding = {e: (f.dhuhr.config[1] if e == "DHUHR" else p[e].config[-1]) for e in EVENTS}
    return common.method_json(
        id=AUTHORITY, fajr={"type": "angle", "degrees": p["FAJR"].value}, isha={"type": "angle", "degrees": p["ISHA"].value},
        dhuhrAfterTransitMin=round(f.dhuhr.value / 60, 6), asrShadow=int(k), asr={"type": "shadow"}, asrSun="GEOMETRIC",
        sunModel=MODEL, rounding=rounding,
        horizon={"sunrise": horizon_of(p["SUNRISE"].value), "sunset": horizon_of(p["MAGHRIB"].value)},
        sunEvaluation={"ASR": ASR_MODE},
        evidence={"kind": "FROM_TABLES", "source": EVIDENCE},
    )


def zone_of(f: Own, manama) -> dict:
    """The national zone: Fajr's point as the morning point, Maghrib's (on Dhuhr's western longitude) as the evening
    point, and each time whose own point differs from its default (E6 `eventPoints`)."""
    morning, evening = f.points["FAJR"], f.points["MAGHRIB"]
    own = {e: pt for e, pt in f.points.items() if pt != (morning if e in ("FAJR", "SUNRISE") else evening)}
    zone = {"id": "bahrain", "nameAr": "البحرين", "point": common.point(manama[0], manama[1]),
            "morningPoint": common.point(*morning), "eveningPoint": common.point(*evening)}
    if own:
        zone["eventPoints"] = {e: common.point(*own[e]) for e in EVENTS if e in own}
    return zone


# --- Scores ----------------------------------------------------------------------------------------------------

def computed(method: dict, zone: dict, day: datetime.date) -> dict[str, tuple]:
    lat, lng = zone["point"]["latE5"] / 1e5, zone["point"]["lngE5"] / 1e5
    z = common.zone_json(zone["id"], lat, lng, authority=AUTHORITY, morningPoint=zone["morningPoint"],
                         eveningPoint=zone["eveningPoint"], eventPoints=zone.get("eventPoints", {}))
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
    rows = calendar_rows()
    stated = stated_points()
    manama = geonames_point(MANAMA_GEONAMES_ID)
    say(f"{AUTHORITY}: {len(rows)} days {rows[0].day}..{rows[-1].day} (Hijri year {rows[0].hijri[-4:]}); "
        f"stated points: east {stated[0]}, west {stated[1]}; zone point {manama[2]} {manama[0]:.5f} N {manama[1]:.5f} E")
    suns = {m: TableSun(m, rows[0].day, rows[-1].day) for m in MODELS}
    dhuhr = dhuhr_windows(rows, suns, stated[1][1])
    per_model = {m: max(sweep(dhuhr[(m, r)])[0] for r in ROUNDINGS) for m in MODELS}
    say("  1 Dhuhr, most windows per sun model at the stated 50.44 E: " + ", ".join(f"{m} {n}" for m, n in per_model.items()))
    grid = own_windows(rows, suns[MODEL], stated[1][1])
    cache: dict[tuple, dict] = {}

    def windows_at(e: str, lat: float, lng: float) -> dict:
        if (e, lat, lng) not in cache:
            cache[(e, lat, lng)] = (asr_event_windows(rows, suns[MODEL], lat, lng) if e == "ASR"
                                    else angle_windows(rows, suns[MODEL], lat, lng, e, OWN_TIMES[e][0]))
        return cache[(e, lat, lng)]

    folds = {}
    for name, months in FOLDS:
        idx = [i for i, r in enumerate(rows) if r.day.month in months]
        folds[name] = (idx, fit_own(grid, dhuhr, idx, windows_at))
        say(f"fold {name} (fitted on months {','.join(map(str, months))}, {len(idx)} days): {describe(folds[name][1])}")
    every = list(range(len(rows)))
    coarse = fit_own(grid, dhuhr, every)
    final = fit_own(grid, dhuhr, every, windows_at)
    say(f"all {len(rows)} days, the grid alone: {describe(coarse)}")
    say(f"all {len(rows)} days: {describe(final)}")
    shared = {e: covering(windows_at(e, *final.points["FAJR" if e in ("FAJR", "SUNRISE") else "MAGHRIB"])[final.picks[e].config],
                          final.picks[e].value)
              for e in OWN_TIMES}
    say("  2 the same values at the zone's morning (Fajr's) or evening (Maghrib's) point instead of their own: "
        + ", ".join(f"{e[:3].lower()} {n}" for e, n in shared.items()))

    method = method_of(final)
    zone = zone_of(final, manama)
    held = []
    for name, (idx, f) in folds.items():
        scored = [rows[i] for i in range(len(rows)) if i not in set(idx)]
        s, statuses = score(method_of(f), zone_of(f, manama), scored)
        held.append(s)
        say(f"fold {name} held out ({len(scored)} days): {shares(s)}; worst {s.worst} min; not COMPUTED: {statuses or 'none'}")
    total = merged(held)
    in_sample, statuses = score(method, zone, rows)
    say(f"held out, both folds ({total.total['FAJR']} days): {shares(total)}; within 1 min: "
        + ", ".join(f"{e} {total.within1[e] / total.total[e]:.1%}" for e in EVENTS) + f"; worst {total.worst} min")
    say(f"the asset's values on all days (in-sample): {shares(in_sample)}; worst {in_sample.worst} min")
    first = dict(method)
    first.update(fajr={"type": "angle", "degrees": 18.01}, isha={"type": "angle", "degrees": 17.82}, dhuhrAfterTransitMin=1.07,
                 horizon={"sunrise": {"type": "fixed", "altitudeDeg": -0.83}, "sunset": {"type": "standard"}},
                 rounding={e: ("NEAREST" if e == "MAGHRIB" else "DOWN") for e in EVENTS})
    first_zone = {"id": "bahrain", "nameAr": "البحرين", "point": zone["point"],
                  "morningPoint": common.point(26.205, stated[0][1]), "eveningPoint": common.point(26.175, stated[1][1])}
    before, _ = score(first, first_zone, rows)
    say(f"the two-point values of 2026-10-15 (26.205 / 26.175 N, 18.01 / 17.82, Dhuhr + 1.07) on all days: {shares(before)}")

    sample = common.sample(SOURCE, rows[0].day, rows[-1].day, len(rows), 1)
    split = common.sample(SOURCE, rows[0].day, rows[-1].day, len(rows), 1, month_split=True)
    stats = common.fit_stats([sample], [split], total)
    failures = tier_a(stats)
    say(f"tier A (9.5): {'passes' if not failures else 'fails: ' + '; '.join(failures)}; "
        f"tier B (every time within 1 min): {'passes' if all(v == 1.0 for v in stats['within1'].values()) else 'fails'}")
    zone["fit"] = stats
    path = common.write_asset(AUTHORITY, AUTHORITY, "BH", "NATIONAL", [zone], fit=stats, source=SOURCE_LINE)
    say(f"wrote {path.relative_to(common.ROOT).as_posix()}: 1 zone; method {method['fajr']}, {method['isha']}, "
        f"dhuhrAfterTransitMin {method['dhuhrAfterTransitMin']}, rounding {method['rounding']}, horizon {method['horizon']}, "
        f"sunEvaluation {method['sunEvaluation']}, sunModel {method['sunModel']}; points: morning {final.points['FAJR']}, "
        f"evening {final.points['MAGHRIB']}, own {zone.get('eventPoints', {})}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
