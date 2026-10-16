#!/usr/bin/env python3
"""
Fits dz_marw, the calendar of Algeria's Ministry of Religious Affairs and Endowments (plan 9.4, milestone
AM2), from the ministry's yearly calendars, kept locally (D13: official tables never enter the repository):

    test-data-local/research-2026-10-11/DZ/refs_ocr.csv     the three reference tables, read from Alger.pdf
                                                           (1447, 1448), Djelfa.pdf and Adrar.pdf (1448);
                                                           only `set`, `month`, `date`, `prayer` and the
                                                           printed minute `ocr` are read
    test-data-local/research-2026-10-11/DZ/offsets/*.csv   the 48 monthly offset grids of the 67 attached places
    test-data-local/research-2026-10-11/DZ/places.csv      the 70 places' Arabic names and town points

and writes android-app/app/src/main/assets/official-zones/dz_marw.json (schema 1, fits/README.md).

Stages (fits/README.md):
  A. Dhuhr: the sun model and its evaluation, from the references' longitude windows.
  B. The reference points: the point covered by the most windows of all six times.
  C. The values at those points: rounding, horizon, Maghrib's minutes, the Fajr and Isha angles, Asr's
     factor and noon instant, elevation.
  D. The attached places (A16): the ministry's offset rule (6.2, WINDOW_CEILING), the Adrar half-month
     windows and Asr's signed shadow (E15), then each place's point from its own offsets.
  E. Held-out scores from the reference engine: the 1448 Hijri months split odd/even (each half scored with
     values fitted on the other) and, for the shipped values (fitted on all of 1448), the 1447 Algiers
     calendar; then the asset.

Usage: python scripts/prayer_formula/fits/dz_marw/fit.py
"""

from __future__ import annotations

import csv
import datetime
import math
import re
import statistics
import sys
import time
from collections import defaultdict, deque
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import common as C  # noqa: E402
import validate_asset  # noqa: E402

ref = C.ref
EV = C.EVENTS
DATA = C.LOCAL_DATA / "research-2026-10-11" / "DZ"
ZONE = "Africa/Algiers"                  # UTC+1 all year (no summer time since 1981)
DAY = datetime.timedelta(days=1)

# The profile (8.2, 8.7); stages A-D check every value.
FAJR, ISHA, MAGHRIB, ASR_K, HORIZON = 18.0, 17.0, 3, 1.0, -0.8333
METHOD = C.method_json(
    id="dz_marw", fajr={"type": "angle", "degrees": FAJR}, isha={"type": "angle", "degrees": ISHA},
    offsets={"MAGHRIB": MAGHRIB}, asr={"type": "signed_shadow"}, rounding={e: "UP" for e in EV},
    highLatitude={"type": "angle_based"},
    evidence={"kind": "FROM_TABLES", "source": "The Ministry of Religious Affairs and Endowments' calendar"})
MODELS = {"MEEUS": ref.meeus_sun, "VSOP87": ref.vsop87_sun, "TEXTBOOK": ref.textbook_sun}
MODES = ("EVENT_INSTANT", "DAILY_0H_UT", "DAILY_10H_UT")

REFERENCES = {"alger": "Algiers", "djelfa": "Djelfa", "adrar": "Adrar"}   # set prefix -> places.csv name
PDF = {"alger": "Alger.pdf", "djelfa": "Djelfa.pdf", "adrar": "Adrar.pdf"}  # the ministry's file names
SETS_1448 = ("alger1448", "djelfa1448", "adrar1448")
HALF_FIRST, HALF_SECOND = 15, 17         # the Adrar zone's windows: days 1-15, then 17 to the end (day 16 too)
QUIRK_MIN = 30                           # a printed time this far off is a misprint (8.3), counted apart
H = 0.01                                 # degrees: finite differences of the linearised searches
STEP = 0.0005                            # degrees: the fine grid of the attached points
SPLITS = {"odd": (lambda m: m % 2 == 1), "even": (lambda m: m % 2 == 0), "all": (lambda m: True)}
HELD_OUT = {"odd": "even", "even": "odd"}

# The calendar abbreviates one name and runs another together; the zone line shows the full names.
NAME_AR = {"ب.ب. مختار": "برج باجي مختار", "اولادجلال": "أولاد جلال"}

# GeoNames admin1 codes (the xx of DZ.xx in download.geonames.org/export/dump/admin1CodesASCII.txt, read
# 2026-10-15), which the place catalogue stores as a place's admin1: the 58 wilayas of 2019, the ten new ones
# under letter codes, with their towns filed under them (Timimoun under TM). Names for reading only.
WILAYAS = {
    "01": "Algiers", "03": "Batna", "04": "Constantine", "06": "Medea", "07": "Mostaganem", "09": "Oran",
    "10": "Saida", "12": "Sétif", "13": "Tiaret", "14": "Tizi Ouzou", "15": "Tlemcen", "18": "Béjaïa",
    "19": "Biskra", "20": "Blida", "21": "Bouira", "22": "Djelfa", "23": "Guelma", "24": "Jijel", "25": "Laghouat",
    "26": "Mascara", "27": "M'Sila", "29": "Oum el Bouaghi", "30": "Sidi Bel Abbès", "31": "Skikda",
    "33": "Tébessa", "34": "Adrar", "35": "Aïn Defla", "36": "Aïn Témouchent",
    "37": "Annaba", "38": "Béchar", "39": "Bordj Bou Arréridj", "40": "Boumerdes",
    "41": "Chlef", "42": "El Bayadh", "43": "El Oued", "44": "El Tarf", "45": "Ghardaia", "46": "Illizi",
    "47": "Khenchela", "48": "Mila", "49": "Naama", "50": "Ouargla", "51": "Relizane", "52": "Souk Ahras",
    "53": "Tamanrasset", "54": "Tindouf", "55": "Tipaza", "56": "Tissemsilt", "BB": "Bordj Badji Mokhtar",
    "TM": "Timimoun", "BA": "Beni Abbes", "IG": "In Guezzam", "IS": "In Salah", "DJ": "Djanet", "EM": "El Menia",
    "TG": "Touggourt", "MG": "El Mghair", "OD": "Ouled Djellal",
}
PLACE_WILAYA = {   # places.csv name -> its wilaya's GeoNames code
    "Algiers": "01", "El Tarf": "44", "Souk Ahras": "52", "Annaba": "37", "Guelma": "23", "Oum El Bouaghi": "29",
    "Skikda": "31", "Constantine": "04", "Mila": "48", "Jijel": "24", "Setif": "12", "Bejaia": "18",
    "Bordj Bou Arreridj": "39", "M'Sila": "27", "Tizi Ouzou": "14", "Dellys": "40", "Bouira": "21",
    "Boumerdes": "40", "Blida": "20", "Medea": "06", "Tipaza": "55", "Ain Defla": "35", "Chlef": "41",
    "Relizane": "51", "Mostaganem": "07", "Oran": "09",
    "Djelfa": "22", "Tebessa": "33", "Bir el Ater": "33", "Khenchela": "47", "El Oued": "43", "Batna": "03",
    "Touggourt": "TG", "Biskra": "19", "Bou Saada": "27", "Ain El Melh": "27", "Hassi R'Mel": "25",
    "Laghouat": "25", "Ain Oussera": "22", "Tissemsilt": "56", "Tiaret": "13", "El Bayadh": "42", "Saida": "10",
    "Mascara": "26", "Naama": "49", "Sidi Bel Abbes": "30", "Ben Badis (Sidi Bel Abbes)": "30", "Tlemcen": "15",
    "Ain Temouchent": "36", "Sebdou": "15", "Maghnia": "15", "Ouled Djellal": "OD", "El M'Ghair": "MG",
    "Adrar": "34", "In Amenas": "46", "Djanet": "DJ", "Illizi": "46", "In Guezzam": "IG", "Tamanrasset": "53",
    "Ouargla": "50", "Ghardaia": "45", "El Menia": "EM", "In Salah": "IS", "Beni Abbes": "BA",
    "Bordj Badji Mokhtar": "BB", "Reggane": "34", "Timimoun": "TM", "Bechar": "38", "Tindouf": "54",
    "Beni Ounif": "38",
}

# Umm al-Qura (java.time's HijrahDate, the app's fallback for a year the asset does not list): 1 Muharram and
# the month lengths of the JDK's table (test-data-local/research-2026-10-08/CROSS/inputs/topic-hijri.md).
UMM_AL_QURA = {1447: (datetime.date(2025, 6, 26), (30, 29, 30, 30, 30, 29, 30, 29, 30, 29, 30, 29)),
               1448: (datetime.date(2026, 6, 16), (29, 30, 29, 30, 30, 29, 30, 30, 29, 30, 29, 30))}


def dates(first: datetime.date, last: datetime.date):
    d = first
    while d <= last:
        yield d
        d += DAY


def up(u: float) -> int:
    """E1 UP on local minutes (the engine's 60·ceil((t − 0.001 s)/60))."""
    return math.ceil((u * 60 - 0.001) / 60)


def km(a: tuple[float, float], b: tuple[float, float]) -> float:
    return math.hypot((a[0] - b[0]) * 111.2, (a[1] - b[1]) * 111.2 * math.cos(math.radians(a[0])))


def sweep(intervals: list[tuple[float, float]]) -> tuple[int, float, float]:
    """The stretch covered by the most intervals [lo, hi): (count, lo, hi)."""
    edges = sorted([(lo, 1) for lo, _ in intervals] + [(hi, -1) for _, hi in intervals], key=lambda x: (x[0], x[1]))
    best, run = (0, math.nan, math.nan), 0
    for i, (x, step) in enumerate(edges):
        run += step
        if step == 1 and run > best[0]:
            best = (run, x, edges[i + 1][0])
    return best


# ---------------------------------------------------------------------------------------------
# The data

ARABIC_MARKS = dict.fromkeys([*range(0x064B, 0x0653), 0x0670])     # tanwin to sukun, shadda included; dagger alif


def norm(name: str) -> str:
    """A place's Arabic name as a key: the grids print some names with hyphens, without spaces or with a shadda."""
    return re.sub(r"[\s\-]", "", name).translate(ARABIC_MARKS)


def slug(latin: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", latin.split(" (")[0].lower().replace("'", "")).strip("-")


class Data:
    def __init__(self):
        self.pub = defaultdict(dict)       # set -> date -> event -> printed minute
        self.hmonth = defaultdict(dict)    # set -> date -> the ministry's Hijri month
        with open(DATA / "refs_ocr.csv", encoding="utf-8") as fh:
            for r in csv.DictReader(fh):
                day = datetime.date.fromisoformat(r["date"])
                self.pub[r["set"]].setdefault(day, {})[r["prayer"].upper()] = int(r["ocr"])
                self.hmonth[r["set"]][day] = int(r["month"])
        self.months = {}                   # set -> month -> (first, last)
        for s, days in self.hmonth.items():
            by_month = defaultdict(list)
            for d, m in days.items():
                by_month[m].append(d)
            self.months[s] = {m: (min(v), max(v)) for m, v in sorted(by_month.items())}
        self.starts = {}                   # Hijri year -> the 12 month starts: the first date of each monthly table
        for s, months in self.months.items():
            starts = [months[m][0] for m in range(1, 13)]
            assert self.starts.setdefault(int(s[-4:]), starts) == starts, f"{s}: month starts differ"
        self.places = []
        with open(DATA / "places.csv", encoding="utf-8") as fh:
            for r in csv.DictReader(fh):
                self.places.append({"ref": r["ref"], "ar": r["city_ar"], "latin": r["city_latin"], "key": norm(r["city_ar"]),
                                    "town": (float(r["lat"]), float(r["lon"])),
                                    "reference": REFERENCES[r["ref"]] == r["city_latin"]})
        self.grids = defaultdict(list)     # (set, place key) -> its monthly (or half-monthly) offsets
        keys = {(p["ref"], p["key"]) for p in self.places}
        for path in sorted((DATA / "offsets").glob("*.csv")):
            with open(path, encoding="utf-8") as fh:
                for r in csv.DictReader(fh):
                    s, key = r["set"], norm(r["city_ar"])
                    assert (s[:-4], key) in keys, f"{path.name}: unknown place {r['city_ar']}"
                    entry = {"set": s, "month": int(r["month"]), "half": r["half"],
                             "first": datetime.date.fromisoformat(r["first_gregorian"]),
                             "last": datetime.date.fromisoformat(r["last_gregorian"]),
                             "offsets": {e: int(r[e.lower()]) for e in EV}}
                    assert (entry["first"], entry["last"]) == self.months[s][entry["month"]], f"{path.name}: month bounds"
                    self.grids[(s, key)].append(entry)
        for (s, key), entries in self.grids.items():
            assert len(entries) == (24 if s.startswith("adrar") else 12), f"{s} {key}: {len(entries)} grid rows"

    def attached(self, refname: str) -> list[dict]:
        return [p for p in self.places if p["ref"] == refname and not p["reference"]]

    def town(self, refname: str) -> tuple[float, float]:
        return next(p["town"] for p in self.places if p["ref"] == refname and p["reference"])


def grid_window(entry: dict, second: int = HALF_SECOND) -> tuple[datetime.date, datetime.date]:
    """The days an offset of the grids covers under the ministry's rule (6.2): the month, or in the Adrar zone
    days 1-15 and [second] to the end."""
    first, last = entry["first"], entry["last"]
    if entry["half"] == "all":
        return first, last
    if entry["half"] == "1-15":
        return first, first + (HALF_FIRST - 1) * DAY
    return first + (second - 1) * DAY, last


def month_bounds(starts: list[datetime.date], next_start: datetime.date, day: datetime.date):
    bounds = list(zip(starts, starts[1:] + [next_start]))
    for k, (a, b) in enumerate(bounds):
        if a <= day < b:
            return k + 1, a, b - DAY
    raise ValueError(day)


def app_window(first: datetime.date, last: datetime.date, day: datetime.date, half: bool):
    """The window the repository passes for a date (6.2): its month, or its half in the Adrar zone (day 16
    takes the second half)."""
    if not half:
        return first, last
    if (day - first).days < HALF_FIRST:
        return first, first + (HALF_FIRST - 1) * DAY
    return first + (HALF_SECOND - 1) * DAY, last


def printed_offset(entries: list[dict], day: datetime.date) -> dict:
    """The printed offset that applies on a date: its month's, or in the Adrar zone «1-15» to day 15, then «15-30»."""
    for entry in entries:
        if entry["first"] <= day <= entry["last"]:
            if entry["half"] == "all" or (entry["half"] == "1-15") == ((day - entry["first"]).days < HALF_FIRST):
                return entry["offsets"]
    raise ValueError(day)


# ---------------------------------------------------------------------------------------------
# Fast special-purpose times for the searches (the reference's own solver, without the engine's later steps)

def fast_day(lat: float, lng: float, day: datetime.date, model=ref.meeus_sun, mode: str = "EVENT_INSTANT",
             signed: bool = True, noon: str = "TRANSIT", fajr: float = FAJR, isha: float = ISHA,
             horizon: float = HORIZON) -> dict[str, float]:
    """Steps 1-7 of the profile at a point, as local minutes (UTC+1). Algeria's cycle date U is the civil date
    (S2: 11:00 UT ± 47 min), so the reference's Cycle(day) and its event solver give RawDay's times exactly."""
    cyc = ref.Cycle(day)
    sun = ref.SunFunction(model, mode, cyc.jd0)
    t0 = ref.transit(cyc, sun, lng)
    noon_decl = model(cyc.jd0 - 1 / 24)[0] if noon == "LOCAL_MIDNIGHT" else sun(cyc.jd0 + t0 / 24)[0]
    x = ASR_K + ref.tand(lat - noon_decl if signed else abs(lat - noon_decl))
    asr = ref.event(cyc, sun, lat, lng, ref.atand(1 / x), 1, t0) if x > 0 else math.nan
    return {"FAJR": 60 * ref.event(cyc, sun, lat, lng, -fajr, -1, t0) + 60,
            "SUNRISE": 60 * ref.event(cyc, sun, lat, lng, horizon, -1, t0) + 60,
            "DHUHR": 60 * t0 + 60, "ASR": 60 * asr + 60,
            "MAGHRIB": 60 * ref.event(cyc, sun, lat, lng, horizon, 1, t0) + 60 + MAGHRIB,
            "ISHA": 60 * ref.event(cyc, sun, lat, lng, -isha, 1, t0) + 60}


class Times:
    """fast_day, cached by point and date."""

    def __init__(self, **kw):
        self.kw, self.cache = kw, {}

    def __call__(self, point: tuple[float, float], day: datetime.date) -> dict[str, float]:
        key = (point, day)
        if key not in self.cache:
            self.cache[key] = fast_day(point[0], point[1], day, **self.kw)
        return self.cache[key]


# ---------------------------------------------------------------------------------------------
# The reference engine, memoised: one engine cache per set of points (its steps are keyed by zone id and date)

class Engine:
    def __init__(self):
        self.memo = {name: {} for name in ("_raw", "_steps", "_angles", "_lowest", "_polar_years", "_lre")}

    def day(self, point, day, zone=None, base=None, window=None) -> dict[str, tuple[float, int]]:
        case = C.case(METHOD, point[0], point[1], ZONE, day, zone=zone, base_zone=base, window=window, country="DZ")
        engine = ref.CaseEngine(case)
        for name, memo in self.memo.items():
            setattr(engine, name, memo)
        result = engine.run()
        assert not result.rules, f"estimated times on {day}: {result.rules}"
        return {e: (result.unrounded[e], int(round(C.local_minutes(result.minutes[e], 3600)))) for e in EV}


def zone_dict(zone_id: str, point, base_id: str | None = None) -> dict:
    if base_id is None:
        return C.zone_json(zone_id, point[0], point[1])
    return C.zone_json(zone_id, point[0], point[1], baseZoneId=base_id, offsetRule="WINDOW_CEILING")


def self_checks(data: Data) -> None:
    """fast_day equals the reference's unrounded times, and the memoised engine equals common.compute."""
    worst = 0.0
    for lat, lng in ((36.77, 3.06), (19.57, 5.77), (22.79, 5.52)):
        for k in range(0, 360, 45):
            day = datetime.date(2026, 6, 16) + k * DAY
            out = C.compute(C.case(METHOD, lat, lng, ZONE, day, country="DZ"))
            fast = fast_day(lat, lng, day)
            worst = max(worst, max(abs(C.local_minutes(out[e][0], 3600) - fast[e]) for e in EV))
    assert worst < 1e-6, worst
    engine, n = Engine(), 0
    base = zone_dict("adrar", (27.88, -0.28))
    zone = zone_dict("tamanrasset", (22.8, 5.53), "adrar")
    for k in range(0, 60, 7):
        day = datetime.date(2026, 6, 16) + k * DAY
        window = app_window(*data.months["adrar1448"][data.hmonth["adrar1448"][day]], day, True)
        for z, b in ((base, None), (zone, base)):
            a = engine.day((22.8, 5.53), day, z, b, window)
            c = C.compute(C.case(METHOD, 22.8, 5.53, ZONE, day, zone=z, base_zone=b, window=window, country="DZ"))
            assert all(a[e][1] == int(round(C.local_minutes(c[e][1], 3600))) and abs(a[e][0] - c[e][0]) < 1e-6 for e in EV)
            n += 1
    print(f"self-checks: fast times = reference to {worst * 60e3:.4f} ms; memoised engine = common.compute on {n} cases")


# ---------------------------------------------------------------------------------------------
# A. Dhuhr: sun model and evaluation

def stage_a(data: Data) -> None:
    sets = ("alger1448", "djelfa1448", "adrar1448", "alger1447")
    lines = []
    for mname, model in MODELS.items():
        for mode in MODES:
            cells = []
            for s in sets:
                lng0 = data.town(s[:-4])[1]
                windows = []
                for day, p in data.pub[s].items():
                    cyc = ref.Cycle(day)
                    u = 60 * ref.transit(cyc, ref.SunFunction(model, mode, cyc.jd0), lng0) + 60
                    windows.append(((u - p["DHUHR"]) / 4, (u - p["DHUHR"] + 1) / 4))   # λ − λ0, Dhuhr rounded up
                n, lo, hi = sweep(windows)
                cell = f"{n}/{len(windows)}"
                if mode == "EVENT_INSTANT" and mname != "TEXTBOOK":
                    cell += f" λ {60 * (lng0 + lo):.2f}′–{60 * (lng0 + hi):.2f}′"
                cells.append(cell)
            lines.append(f"  {mname:<8} {mode:<13} " + " | ".join(cells))
    print("A. Dhuhr, days one longitude covers (Algiers 1448 | Djelfa | Adrar | Algiers 1447):")
    print("\n".join(lines))


# ---------------------------------------------------------------------------------------------
# B. The reference points

def strips(data: Data, s: str, days, point, times: Times) -> list:
    """Per day and time, the linearised time u0 + a·Δφ + b·Δλ and its printed minute (misprints left out)."""
    out = []
    for day in days:
        p = data.pub[s][day]
        u, ua, ub = times(point, day), times((point[0] + H, point[1]), day), times((point[0], point[1] + H), day)
        out.extend((u[e], (ua[e] - u[e]) / H, (ub[e] - u[e]) / H, p[e]) for e in EV if abs(p[e] - u[e]) < QUIRK_MIN)
    return out


def coverage(lines: list, nx: int, ny: int, dx: float, dy: float) -> list[list[int]]:
    """The windows count on the grid of offsets (i·dx, j·dy): for each line, the cells where it rounds up to
    its printed minute (m − 1 < u0 + a·x + b·y ≤ m)."""
    width = 2 * nx + 1
    grid = []
    for j in range(-ny, ny + 1):
        y, diff = j * dy, [0] * (width + 1)
        for u0, a, b, m in lines:
            c = u0 + b * y
            if abs(a) < 1e-9:
                if m - 1 < c <= m:
                    diff[0] += 1
                    diff[width] -= 1
                continue
            lo, hi = sorted(((m - 1 - c) / a, (m - c) / a))
            i0, i1 = max(-nx, math.ceil(lo / dx)), min(nx, math.floor(hi / dx))
            if i0 <= i1:
                diff[i0 + nx] += 1
                diff[i1 + nx + 1] -= 1
        run, row = 0, []
        for i in range(width):
            run += diff[i]
            row.append(run)
        grid.append(row)
    return grid


def largest_component(cells: set) -> list:
    """The largest 4-connected group of cells."""
    best, seen = [], set()
    for start in sorted(cells):
        if start in seen:
            continue
        seen.add(start)
        queue, group = deque([start]), []
        while queue:
            c = queue.popleft()
            group.append(c)
            for n in ((c[0] + 1, c[1]), (c[0] - 1, c[1]), (c[0], c[1] + 1), (c[0], c[1] - 1)):
                if n in cells and n not in seen:
                    seen.add(n)
                    queue.append(n)
        if len(group) > len(best):
            best = group
    return best


def nearest_cell(region: list, target, dx: float, dy: float, lat: float) -> tuple:
    """The data leave a small region of equally good cells; the town is the prior, and a cell on the region's edge
    puts some window's maximum a hair from a whole minute. So: the cell nearest the town (the town itself when it
    qualifies) among those whose eight neighbours are in the region too, or among all where none is."""
    cells = set(region)
    inner = [c for c in region if all((c[0] + a, c[1] + b) in cells for a in (-1, 0, 1) for b in (-1, 0, 1))]
    sx, sy = dx * 111.2, dy * 111.2 * math.cos(math.radians(lat))
    return min(inner or region, key=lambda c: ((c[0] - target[0]) * sx) ** 2 + ((c[1] - target[1]) * sy) ** 2)


def fit_reference(data: Data, refname: str, split: str, times: Times) -> tuple[float, float]:
    s = refname + "1448"
    days = [d for d in data.pub[s] if SPLITS[split](data.hmonth[s][d])]
    town = data.town(refname)
    nx, ny, dx, dy = 60, 60, 0.001, 0.0005
    grid = coverage(strips(data, s, days, town, times), nx, ny, dx, dy)
    best = max(max(row) for row in grid)
    cells = {(i - nx, j - ny) for j, row in enumerate(grid) for i, v in enumerate(row) if v == best}
    i, j = nearest_cell(largest_component(cells), (0, 0), dx, dy, town[0])
    return round(town[0] + i * dx, 5), round(town[1] + j * dy, 5)


def exact_hits(data: Data, s: str, days, point, times: Times) -> tuple[int, int]:
    hits = total = 0
    for day in days:
        p, u = data.pub[s][day], times(point, day)
        for e in EV:
            if abs(p[e] - u[e]) < QUIRK_MIN:
                hits += up(u[e]) == p[e]
                total += 1
    return hits, total


def stage_b(data: Data, times: Times) -> dict:
    points = {split: {r: fit_reference(data, r, split, times) for r in REFERENCES} for split in SPLITS}
    whole = {"alger": (36 + 46 / 60, 3 + 3 / 60), "djelfa": (34 + 40 / 60, 3 + 15 / 60), "adrar": (27 + 53 / 60, -17 / 60)}
    print("B. Reference points (all six times; held out: the other half of 1448's months, and 1447 for Algiers):")
    for r in REFERENCES:
        s = r + "1448"
        cells = []
        for label, pts in (("fitted", {k: points[k][r] for k in SPLITS}), ("plan's whole arc-minutes", {k: whole[r] for k in SPLITS})):
            hits = total = 0
            for split, other in HELD_OUT.items():
                days = [d for d in data.pub[s] if SPLITS[other](data.hmonth[s][d])]
                h, t = exact_hits(data, s, days, pts[split], times)
                hits, total = hits + h, total + t
            text = f"{label} {100 * hits / total:.2f} %"
            if r == "alger":
                h, t = exact_hits(data, "alger1447", sorted(data.pub["alger1447"]), pts["all"], times)
                text += f", 1447 {100 * h / t:.2f} %"
            cells.append(text)
        lat, lng = points["all"][r]
        where = (f"{lat:.5f} N {abs(lng):.5f} {'E' if lng >= 0 else 'W'} ({int(lat)}°{60 * (lat % 1):.2f}′, "
                 f"{int(abs(lng))}°{60 * (abs(lng) % 1):.2f}′)")
        print(f"  {REFERENCES[r]:<8} {where}, {km(points['all'][r], data.town(r)):.1f} km from the town: " + "; ".join(cells))
    margin = 0.0                         # how far each missed minute's unrounded time is from the printed minute's window
    for s in ("alger1448", "djelfa1448", "adrar1448", "alger1447"):
        for day, p in data.pub[s].items():
            u = times(points["all"][s[:-4]], day)
            for e in EV:
                if abs(p[e] - u[e]) < QUIRK_MIN and up(u[e]) != p[e]:
                    margin = max(margin, 60 * ((u[e] - p[e]) if up(u[e]) > p[e] else (p[e] - 1 - u[e])))
    print(f"  every missed reference minute, at the shipped points: within {margin:.1f} s of the printed minute")
    return points


# ---------------------------------------------------------------------------------------------
# C. The values at the fitted points

def altitude(point, day, minute) -> float:
    cyc = ref.Cycle(day)
    return ref.altitude(cyc, ref.meeus_sun, point[0], point[1], (minute - 60) / 60)


def stage_c(data: Data, points: dict, times: Times) -> None:
    print("C. Values, windows pooled over the three references (odd months | even months):")
    rows = defaultdict(list)
    for split in ("odd", "even"):
        win = defaultdict(list)
        for r in REFERENCES:
            s, pt = r + "1448", points[split][r]
            for day in data.pub[s]:
                if not SPLITS[split](data.hmonth[s][day]):
                    continue
                p, u = data.pub[s][day], times(pt, day)
                for e in EV:   # E1: under NEAREST rounding, the shift c with round(u + c) = m
                    if abs(p[e] - u[e]) < QUIRK_MIN:
                        base = u[e] - (MAGHRIB if e == "MAGHRIB" else 0)
                        win["shift " + e].append((p[e] - 0.5 - base, p[e] + 0.5 - base))
                sunset = u["MAGHRIB"] - MAGHRIB
                win["Maghrib minutes"].append((p["MAGHRIB"] - 1 - sunset, p["MAGHRIB"] - sunset))
                win["sunrise horizon"].append((altitude(pt, day, p["SUNRISE"] - 1), altitude(pt, day, p["SUNRISE"])))
                win["Fajr angle"].append((-altitude(pt, day, p["FAJR"]), -altitude(pt, day, p["FAJR"] - 1)))
                if abs(p["ISHA"] - u["ISHA"]) < QUIRK_MIN:
                    win["Isha angle"].append((-altitude(pt, day, p["ISHA"] - 1), -altitude(pt, day, p["ISHA"])))
                cyc = ref.Cycle(day)
                noon = ref.meeus_sun(cyc.jd0 + (u["DHUHR"] - 60) / 60 / 24)[0]
                shadow = math.tan(math.radians(pt[0] - noon))
                k = [1 / math.tan(math.radians(altitude(pt, day, p["ASR"] - j))) - shadow for j in (1, 0)]
                win["Asr factor"].append((k[0], k[1]))
        for name, intervals in win.items():
            n, lo, hi = sweep(intervals)
            rows[name].append(f"{lo:.4f} to {hi:.4f} ({n}/{len(intervals)})")
    for name in sorted(rows):
        print(f"  {name:<16} " + " | ".join(rows[name]))
    # Asr's evaluation (E4) and elevation, counted at the all-1448 points.
    variants = {"EVENT_INSTANT": Times(), "LOCAL_MIDNIGHT_SHADOW": Times(noon="LOCAL_MIDNIGHT")}
    variants.update({mode: Times(mode=mode) for mode in ("DAILY_0H_UT", "DAILY_10H_UT", "KACST")})
    counts = dict.fromkeys(variants, 0)
    for r in REFERENCES:
        s, pt = r + "1448", points["all"][r]
        for day, p in data.pub[s].items():
            for name, tm in variants.items():
                counts[name] += up(tm(pt, day)["ASR"]) == p["ASR"]
    print("  Asr exact of 1,065 by evaluation (E4): " + ", ".join(f"{k} {v}" for k, v in counts.items()))
    pt = points["all"]["djelfa"]
    dip = ref.dip_deg(1140)
    dipped = Times(horizon=HORIZON - dip)
    n0 = sum(up(times(pt, d)["SUNRISE"]) == p["SUNRISE"] for d, p in data.pub["djelfa1448"].items())
    n1 = sum(up(dipped(pt, d)["SUNRISE"]) == p["SUNRISE"] for d, p in data.pub["djelfa1448"].items())
    print(f"  Djelfa (1,140 m) sunrise: {n0}/355 exact at sea level, {n1}/355 with the dip of 1,140 m (E2 StandardWithDip)")


# ---------------------------------------------------------------------------------------------
# D. The attached places

class PlaceFit:
    """One attached place: windows of its printed offsets as functions of its point, linearised around a point.
    An offset is ceil(max over the window of (place − reference) − 1e-9), the ministry's rule (6.2)."""

    def __init__(self, place: dict, entries: list[dict], ref_times: dict, second: int = HALF_SECOND, **kw):
        self.place, self.entries, self.ref, self.second = place, entries, ref_times, second
        self.times = Times(**kw)          # one cache per place keeps memory flat

    def lines(self, p0) -> list:
        out = []
        for entry in self.entries:
            days = list(dates(*grid_window(entry, self.second)))
            for e in EV:
                rows = []
                for d in days:
                    u = self.times(p0, d)
                    ua, ub = self.times((p0[0] + H, p0[1]), d), self.times((p0[0], p0[1] + H), d)
                    rows.append((u[e] - self.ref[d][e], (ua[e] - u[e]) / H, (ub[e] - u[e]) / H))
                out.append((entry["offsets"][e], rows))
        return out

    @staticmethod
    def prune(lines: list, span: float) -> list:
        """Keeps, per offset, the days that can hold the maximum somewhere in the box |x|, |y| ≤ span."""
        corners = ((-span, -span), (-span, span), (span, -span), (span, span))
        out = []
        for printed, rows in lines:
            vals = [tuple(c + a * x + b * y for x, y in corners) for c, a, b in rows]
            keep = [rows[i] for i, v in enumerate(vals)
                    if not any(j != i and all(w[k] >= v[k] for k in range(4)) and (w != v or j < i) for j, w in enumerate(vals))]
            out.append((printed, keep))
        return out

    @staticmethod
    def count(lines: list, x: float, y: float) -> int:
        return sum(math.ceil(max(c + a * x + b * y for c, a, b in rows) - 1e-9) == printed for printed, rows in lines)

    def search(self, p0, span: float, step: float) -> tuple[int, list, list]:
        lines = self.prune(self.lines(p0), span)
        k = int(round(span / step))
        scores = {(i, j): self.count(lines, i * step, j * step) for i in range(-k, k + 1) for j in range(-k, k + 1)}
        best = max(scores.values())
        return best, [c for c, v in scores.items() if v == best], lines

    def fit(self) -> tuple[tuple[float, float], int, int]:
        """The point the most windows cover: a coarse search around the town, a fine one around its best cell,
        then the whole region of best cells, in which nearest_cell picks. Returns (point, hits, offsets)."""
        town = self.place["town"]
        _, cells, _ = self.search(town, 0.25, 0.01)
        ci, cj = min(cells, key=lambda c: c[0] ** 2 + c[1] ** 2)
        p1 = (town[0] + ci * 0.01, town[1] + cj * 0.01)
        best, cells, _ = self.search(p1, 0.012, STEP)
        span = 0.06
        lines, limit = self.prune(self.lines(p1), span), round(span / STEP)
        town_index = (round((town[0] - p1[0]) / STEP), round((town[1] - p1[1]) / STEP))
        start = min(cells, key=lambda c: c[0] ** 2 + c[1] ** 2)
        region, seen, queue = [], {start}, deque([start])
        while queue:                                  # the whole region of best cells, beyond the fine box
            c = queue.popleft()
            region.append(c)
            for n in ((c[0] + 1, c[1]), (c[0] - 1, c[1]), (c[0], c[1] + 1), (c[0], c[1] - 1)):
                if n not in seen and max(map(abs, n)) <= limit:
                    seen.add(n)
                    if self.count(lines, n[0] * STEP, n[1] * STEP) == best:
                        queue.append(n)
        i, j = nearest_cell(region, town_index, STEP, STEP, town[0])
        point = town if (i, j) == town_index else (round(p1[0] + i * STEP, 5), round(p1[1] + j * STEP, 5))
        return point, sum(o == printed for o, printed in self.derived(point)), len(self.entries) * 6

    def derived(self, point, entries=None) -> list[tuple[int, int]]:
        """(derived offset, printed offset) of every grid cell, from exact times at [point]."""
        out = []
        for entry in entries or self.entries:
            days = list(dates(*grid_window(entry, self.second)))
            us = [(d, self.times(point, d)) for d in days]
            out.extend((math.ceil(max(u[e] - self.ref[d][e] for d, u in us) - 1e-9), entry["offsets"][e]) for e in EV)
        return out


def ref_times(data: Data, s: str, point, times: Times) -> dict:
    first, last = data.months[s][1][0], data.months[s][12][1]
    return {d: times(point, d) for d in dates(first, last)}


def stage_d(data: Data, ref_points: dict, times: Times) -> dict:
    # The Adrar windows and the signed shadow, chosen on each half of the year separately.
    print("D. Attached places (offsets reproduced, in-sample per half: odd months | even months):")
    tropic = [p for p in data.attached("adrar") if p["town"][0] < 23.44]
    for label, places, variants in (
            ("Adrar zone, second window from day", data.attached("adrar"), [(n, {}, n) for n in (15, 16, 17, 18)]),
            ("south of the Tropic (In Guezzam, Tamanrasset, B. B. Mokhtar), Asr", tropic,
             [("signed shadow", {}, HALF_SECOND), ("unsigned", {"signed": False}, HALF_SECOND)])):
        cells = []
        for name, kw, second in variants:
            per_half = []
            for split in ("odd", "even"):
                rt = ref_times(data, "adrar1448", ref_points[split]["adrar"], Times(**kw) if kw else times)
                hits = total = 0
                for p in places:
                    entries = [g for g in data.grids[("adrar1448", p["key"])] if SPLITS[split](g["month"])]
                    _, h, t = PlaceFit(p, entries, rt, second, **kw).fit()
                    hits, total = hits + h, total + t
                per_half.append(f"{hits}/{total}")
            cells.append(f"{name}: {' | '.join(per_half)}")
        print(f"  {label}: " + "; ".join(cells))
    # The points, per split.
    points = {split: {} for split in SPLITS}
    misses = []
    for split in SPLITS:
        for refname in REFERENCES:
            s = refname + "1448"
            rt = ref_times(data, s, ref_points[split][refname], times)
            for p in data.attached(refname):
                entries = [g for g in data.grids[(s, p["key"])] if SPLITS[split](g["month"])]
                fit = PlaceFit(p, entries, rt)
                point, hits, total = fit.fit()
                points[split][p["key"]] = point
                if split == "all" and hits < total:
                    for entry in entries:
                        month = f"{entry['month']}" + ("" if entry["half"] == "all" else "/" + entry["half"])
                        for (o, printed), e in zip(fit.derived(point, [entry]), EV):
                            if o != printed:
                                misses.append(f"{p['latin']} {month} {e} {o:+d} for {printed:+d}")
    dist = [km(points["all"][p["key"]], p["town"]) for p in data.places if not p["reference"]]
    print(f"  points (all 1448): median {statistics.median(dist):.1f} km from the town, {sum(x <= 2 for x in dist)} of 67 within 2 km, "
          f"farthest {max(dist):.1f} km; {sum(x == 0 for x in dist)} at the town itself")
    print(f"  1448 cells not reproduced in-sample ({len(misses)}): " + "; ".join(misses))
    return points


# ---------------------------------------------------------------------------------------------
# E. Held-out scores from the reference engine, and the asset

class Record:
    """One zone's held-out record (or the profile's)."""

    def __init__(self):
        self.score, self.quirks, self.quirk_worst, self.ramadan = C.Score(), set(), 0, set()

    def add(self, day, hijri_month, computed: dict, printed: dict, quirk: set) -> None:
        if hijri_month == 9:
            self.ramadan.add(day)
        for e in EV:
            if e in quirk:
                self.quirks.add(day)
                self.quirk_worst = max(self.quirk_worst, abs(computed[e] - printed[e]))
            else:
                self.score.add(e, day, computed[e], printed[e])

    def stats(self, fitted_on: list, held_out: list) -> dict:
        out = C.fit_stats(fitted_on, held_out, self.score, ramadan_days=len(self.ramadan), quirk_days=len(self.quirks))
        out["maxDiffMin"] = max(out["maxDiffMin"], self.quirk_worst)   # FitStats.maxDiffMin: quirk days included
        return out


def stage_e(data: Data, ref_points: dict, place_points: dict, times: Times):
    started = time.time()
    records = defaultdict(Record)                      # zone id -> its held-out record; "" = the profile
    labels = {"split": "1448, Hijri months split odd/even (held out)", "1447": "1447 Algiers zone, shipped points (held out)",
              "in": "1448, shipped points (in-sample)"}
    finals = {k: [0, 0, 0, 0, []] for k in labels}     # exact, within 1, times, worst, examples off by 2+
    offsets = {k: [0, 0, 0] for k in labels}           # derived offsets read from the engine: hits, cells, worst
    quirk_days = set()
    jobs = ([(split, s, HELD_OUT[split], "split") for split in HELD_OUT for s in SETS_1448]
            + [("all", "alger1447", "all", "1447")] + [("all", s, "all", "in") for s in SETS_1448])
    for split, s, test, kind in jobs:
        engine, refname = Engine(), s[:-4]
        ref_id, rp = slug(REFERENCES[refname]), ref_points[split][refname]
        base = zone_dict(ref_id, rp)
        days = sorted(d for d in data.pub[s] if SPLITS[test](data.hmonth[s][d]))
        base_minutes, quirk = {}, {}

        def tally(zone_id, d, computed, printed):
            if kind != "in":
                for rec in (records[zone_id], records[""]):
                    rec.add(d, data.hmonth[s][d], computed, printed, quirk[d])
            f = finals[kind]
            for e in EV:
                if e not in quirk[d]:
                    diff = computed[e] - printed[e]
                    f[0] += diff == 0
                    f[1] += abs(diff) <= 1
                    f[2] += 1
                    f[3] = max(f[3], abs(diff))
                    if abs(diff) > 1:
                        f[4].append(f"{zone_id} {d} {e} {diff:+d}")

        for d in days:
            out = engine.day(rp, d, zone=base)
            base_minutes[d] = {e: out[e][1] for e in EV}
            quirk[d] = {e for e in EV if abs(out[e][1] - data.pub[s][d][e]) >= QUIRK_MIN}
            quirk_days.update((s, d, e) for e in quirk[d])
            tally(ref_id, d, base_minutes[d], data.pub[s][d])
        for p in data.attached(refname):
            pid, pp = slug(p["latin"]), place_points[split][p["key"]]
            zone, entries = zone_dict(pid, pp, ref_id), data.grids[(s, p["key"])]
            seen = set()
            for d in days:
                first, last = data.months[s][data.hmonth[s][d]]
                window = app_window(first, last, d, refname == "adrar")
                out = engine.day(pp, d, zone=zone, base=base, window=window)
                computed = {e: out[e][1] for e in EV}
                po = printed_offset(entries, d)
                tally(pid, d, computed, {e: data.pub[s][d][e] + po[e] for e in EV})
                block = (window, id(po))
                if block not in seen and (d - first).days != HALF_FIRST:     # each grid cell once (day 16 aside)
                    seen.add(block)
                    for e in EV:
                        o = computed[e] - base_minutes[d][e]                 # the engine's derived offset
                        offsets[kind][0] += o == po[e]
                        offsets[kind][1] += 1
                        offsets[kind][2] = max(offsets[kind][2], abs(o - po[e]))
    print(f"E. Reference engine ({time.time() - started:.0f} s); misprints counted apart: {len(quirk_days)} times "
          f"({', '.join(sorted({str(d) for _, d, _ in quirk_days}))}, Isha)")
    for kind, label in labels.items():
        ex, w1, n, worst, examples = finals[kind]
        h, cells, ow = offsets[kind]
        print(f"  {label}: times {100 * ex / n:.2f} % exact, {100 * w1 / n:.3f} % within 1 min (max {worst}); "
              f"derived offsets {h}/{cells} = {100 * h / cells:.2f} % (max {ow})"
              + (f"; off by 2: {len(examples)}, e.g. {', '.join(examples[:3])}" if examples else ""))
    return records


def stage_uaq(data: Data, ref_points: dict, place_points: dict, times: Times) -> None:
    """With Umm al-Qura's month starts (the app's fallback for a year the asset lacks) instead of the ministry's."""
    same = cells = within = total = worst = 0
    examples = []
    for s in SETS_1448 + ("alger1447",):
        refname, year = s[:-4], int(s[-4:])
        start, lengths = UMM_AL_QURA[year]
        starts = [start]
        for n in lengths:
            starts.append(starts[-1] + n * DAY)
        rp = ref_points["all"][refname]
        for p in data.attached(refname):
            pp, entries = place_points["all"][p["key"]], data.grids[(s, p["key"])]
            memo = {}
            for d in sorted(data.pub[s]):
                _, first, last = month_bounds(starts[:12], starts[12], d)
                window = app_window(first, last, d, refname == "adrar")
                if window not in memo:
                    us = [(x, times(pp, x), times(rp, x)) for x in dates(*window)]
                    memo[window] = {e: math.ceil(max(u[e] - r[e] for _, u, r in us) - 1e-9) for e in EV}
                po, ru = printed_offset(entries, d), times(rp, d)
                for e in EV:
                    printed = data.pub[s][d][e] + po[e]
                    computed = up(ru[e]) + memo[window][e]
                    same += memo[window][e] == po[e]
                    cells += 1
                    if abs(data.pub[s][d][e] - ru[e]) < QUIRK_MIN:
                        within += abs(computed - printed) <= 1
                        total += 1
                        worst = max(worst, abs(computed - printed))
                        if abs(computed - printed) > 1:
                            examples.append(f"{slug(p['latin'])} {d} {e} {computed - printed:+d}")
    print(f"  with Umm al-Qura's month starts instead (shipped points): offsets equal on {100 * same / cells:.1f} % of "
          f"place-days, times within 1 min on {100 * within / total:.2f} % (max {worst} min; {len(examples)} off by 2+, "
          f"on {len({x.split()[1] for x in examples})} dates, e.g. {', '.join(examples[:3])})")


def summary_table(records: dict) -> None:
    print("  held-out exact % per time (FAJR SUNRISE DHUHR ASR MAGHRIB ISHA; within 1 min):")
    groups = [("the 3 references", [slug(n) for n in REFERENCES.values()]), ("all 70 zones (profile)", [""])]
    for label, ids in groups:
        score = C.Score()
        for i in ids:
            s = records[i].score
            for e in EV:
                score.exact[e] += s.exact[e]
                score.within1[e] += s.within1[e]
                score.total[e] += s.total[e]
                score.worst = max(score.worst, s.worst)
        ex, w1 = score.shares(), score.within1_shares()
        print(f"    {label:<24} " + " ".join(f"{100 * ex[e]:.1f}" for e in EV)
              + f"; within 1: {min(w1.values()) * 100:.2f} % (max {score.worst} min)")
    worst = min(((i, min(r.score.shares().values())) for i, r in records.items() if i), key=lambda x: x[1])
    print(f"    lowest zone: {worst[0]} ({100 * worst[1]:.1f} % on its lowest time)")


def write(data: Data, ref_points: dict, place_points: dict, records: dict) -> Path:
    y48, y47 = data.months["alger1448"], data.months["alger1447"]
    span48, span47 = (y48[1][0], y48[12][1]), (y47[1][0], y47[12][1])
    n48, n47 = (span48[1] - span48[0]).days + 1, (span47[1] - span47[0]).days + 1

    def admin1(place):
        """The wilaya as the place catalogue stores it: GeoNames' admin1 code (the xx of DZ.xx)."""
        code = PLACE_WILAYA[place["latin"]]
        assert code in WILAYAS, code
        return [code]

    zones = []
    for refname, name in REFERENCES.items():
        place = next(p for p in data.places if p["ref"] == refname and p["reference"])
        held = [C.sample(f"MARW calendar 1448 ({PDF[refname]}): reference table, Hijri months split odd/even", *span48, n48, 1, True)]
        if refname == "alger":
            held.append(C.sample("MARW calendar 1447 (Alger.pdf): reference table", *span47, n47, 1))
        fitted = [C.sample(f"MARW calendar 1448 ({PDF[refname]}): reference table", *span48, n48, 1)]
        zones.append({"id": slug(name), "nameAr": NAME_AR.get(place["ar"], place["ar"]), "point": C.point(*ref_points["all"][refname]),
                      "halfMonthWindows": refname == "adrar", "admin1": admin1(place),
                      "namedPlaces": [{"centre": C.point(*place["town"]), "catalogueIds": []}],
                      "fit": records[slug(name)].stats(fitted, held)})
    for refname, name in REFERENCES.items():
        for p in data.attached(refname):
            pid = slug(p["latin"])
            fitted = [C.sample(f"MARW calendar 1448 ({PDF[refname]}): {p['latin']}'s monthly offsets", *span48, n48, 1)]
            held = [C.sample(f"MARW calendar 1448 ({PDF[refname]}): {p['latin']} (reference minute + printed offset), "
                             "Hijri months split odd/even", *span48, n48, 1, True)]
            if refname == "alger":
                held.append(C.sample(f"MARW calendar 1447 (Alger.pdf): {p['latin']} (reference minute + printed offset)", *span47, n47, 1))
            zones.append({"id": pid, "nameAr": NAME_AR.get(p["ar"], p["ar"]), "point": C.point(*place_points["all"][p["key"]]),
                          "baseZoneId": slug(name), "offsetRule": "WINDOW_CEILING", "admin1": admin1(p),
                          "namedPlaces": [{"centre": C.point(*p["town"]), "catalogueIds": []}],
                          "fit": records[pid].stats(fitted, held)})
    profile = records[""].stats(
        [C.sample("MARW calendars 1448 (Alger.pdf, Djelfa.pdf, Adrar.pdf): reference tables and monthly offsets", *span48, n48, 70)],
        [C.sample("MARW calendars 1448: all 70 places, Hijri months split odd/even", *span48, n48, 70, True),
         C.sample("MARW calendar 1447 (Alger.pdf): Algiers and its 25 places", *span47, n47, 26)])
    return C.write_asset(
        "dz_marw", "dz_marw", "DZ", "NEAREST", zones, fit=profile, by_admin1=True, hijri_month_starts=data.starts,
        source="Algeria's Ministry of Religious Affairs and Endowments: yearly calendars 1447 (Algiers) and 1448 "
               "(Algiers, Djelfa, Adrar), three reference tables and 67 places' monthly offsets")


def main() -> int:
    started = time.time()
    data = Data()
    print(f"dz_marw: {sum(len(v) for v in data.pub.values())} reference days, {sum(len(v) for v in data.grids.values())} "
          f"grid rows, {len(data.places)} places, from {DATA}")
    self_checks(data)
    times = Times()
    stage_a(data)
    ref_points = stage_b(data, times)
    stage_c(data, ref_points, times)
    place_points = stage_d(data, ref_points, times)
    records = stage_e(data, ref_points, place_points, times)
    stage_uaq(data, ref_points, place_points, times)
    summary_table(records)
    path = write(data, ref_points, place_points, records)
    problems = validate_asset.problems_of(path)
    print(f"wrote {path.relative_to(C.ROOT)}: {'valid' if not problems else problems}; {time.time() - started:.0f} s")
    return 1 if problems else 0


if __name__ == "__main__":
    raise SystemExit(main())
