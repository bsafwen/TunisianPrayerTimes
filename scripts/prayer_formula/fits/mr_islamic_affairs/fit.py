#!/usr/bin/env python3
"""
Fits `mr_islamic_affairs`, Mauritania's Ministry of Islamic Affairs and Original Education (plan 8.1, 8.2, 8.7,
8.8; 9.4), and writes its official-zone asset, android-app/app/src/main/assets/official-zones/mr_islamic_affairs.json
(schema 1).

Data, read at run time from test-data-local and never copied (D13, A7):
- research-2026-10-08/MR/mr/c2025/p04.csv ... p16.csv: the 1446/2025 imsakiya (13 wilaya capitals, 2025-03-01 to
  03-30), parsed from the ministry's PDF (pages 4-16; the page order gives the capital); its city headings come from
  research-2026-10-08/MR/mrv/py/r2025.json (the PDF's text layer).
- research-2026-10-08/MR/mr/nkc2021.csv ... nkc2024.csv: Nouakchott's 1442-1445 imsakiyas (2021 from the PDF's text
  layer, 2022-2024 hand transcriptions of the ministry's images; 2023 and 2024 print the sunset beside Maghrib).
- Side checks only: research-2026-10-08/MR/mrv/py/akh.py (Al-Akhbar's relay of the 2026 Imsak in the 13 capitals,
  read as text, never run) and official/mr/tig2022_d8.csv (Tig's relay of 2022-04-09, if present).

Since 2023 the imsakiya is a perpetual table, reprinted by month and day (2023, 2024 and 2025 agree on every shared
date): its rows are read at the reference year 2023 (E5), the year it first appeared, while every score below is
also given as the app shows it, computed for the actual year (A8). The 2021 and 2022 tables were computed for their
own years; they hold older rules (E9) and are fixtures outside FitStats (9.3, tier B).

Stages (9.4), each by windows (a published minute bounds the unrounded time to a minute; the value most windows
cover wins; within that stretch a conventional value first, then the fewest decimals):
1. Dhuhr: the sun model (E14), by one longitude per capital.
2. Asr: the geometric or the apparent sun (E13), by one longitude per capital for Dhuhr and Asr together (Asr alone
   cannot tell them apart; the two times share the capital's longitude).
3. Points: each capital keeps its town's latitude (Ramadan's equinox days hardly fix one) and takes the longitude
   that the most of its minutes allow, the town's own where it is among them (E6 morning and evening points
   otherwise; the zone point stays the town).
4. Horizons (E2): the sunrise altitude from Nouakchott's sunrises (printed with no offset, as its sunset is), the
   sunset altitude from every capital's Maghrib at the ministry's stated sunset + 2 (its notice of 2024-03-10), and
   the twelve interior capitals' common sunrise shift (E7, a zone offset). Stages 3 and 4 are repeated until stable.
5. Fajr and Isha angles.
Each capital's longitude is then refitted on all six times. The plan's representation (8.2, 8.7: standard horizons,
Maghrib + 3, sunrise - 1 and - 2.5) is fitted the same way for comparison.
Held out: Nouakchott by month (March and April month-days of 2023-2025) and the interior capitals by day (odd and
even dates of 2025): fold A fits on Nouakchott's March and the odd dates and scores the rest, fold B the reverse,
each with every stage refitted. Scores come from common.compute (the reference engine, which the Kotlin engine
matches) at the actual dates.

Usage: python scripts/prayer_formula/fits/mr_islamic_affairs/fit.py [--no-guard]
"""

from __future__ import annotations

import argparse
import ast
import datetime
import json
import math
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import common  # noqa: E402

EV = common.EVENTS
AUTHORITY = "mr_islamic_affairs"
TZ = "Africa/Nouakchott"                # UTC+0, no summer time since 1960
MR = common.LOCAL_DATA / "research-2026-10-08" / "MR"
RELAY_2022 = common.LOCAL_DATA / "official" / "mr" / "tig2022_d8.csv"
RELAY_2026 = MR / "mrv" / "py" / "akh.py"
REF_YEAR = 2023                         # E5: the reprinted imsakiya first appeared in 1444/2023
PERPETUAL = ("2023", "2024", "2025")
MODELS = ("MEEUS", "VSOP87")
ASR_SUNS = ("GEOMETRIC", "APPARENT")
A1, A2 = -0.8333, -1.15                 # the two horizons the times are computed at; any other is interpolated
MAGHRIB_STATED = 2                      # «المغرب» = sunset + 2 (the ministry's notice of 2024-03-10, and the 2023-2024 tables)
NAME_FIX = {"نوا ذيبو": "نواذيبو"}      # the PDF's heading splits the name
SOURCE_LINE = ("Mauritania's Ministry of Islamic Affairs and Original Education: the Ramadan imsakiya of the 13 wilaya "
               "capitals (1446/2025; Nouakchott also 1442-1445), a perpetual table since 2023, applied all year (Q6)")
SAMPLE_2025 = "Ministry imsakiya 1446/2025 (13 capitals; perpetual since 1444/2023)"
SAMPLE_NKC = "Ministry imsakiyas 1444-1446 (2023-2025), Nouakchott"
# PDF page -> (zone id, GeoNames id, town lat, town lng, GeoNames admin1 of the wilaya(s) it serves). Town points: GeoNames
# cities1000 (read 2026-10-15). Nouakchott's table serves the three Nouakchott wilayas (13 Ouest, 14 Nord, 15 Sud).
CAPITALS = {
    4: ("nouakchott", 2377450, 18.08581, -15.9785, ("13", "14", "15")),
    5: ("nema", 2377539, 16.61702, -7.25649, ("01",)),
    6: ("aioun", 2381291, 16.6614, -9.6149, ("02",)),
    7: ("kiffa", 2378538, 16.62073, -11.40208, ("03",)),
    8: ("kaedi", 2378736, 16.15071, -13.50582, ("04",)),
    9: ("aleg", 2381659, 17.05314, -13.91312, ("05",)),
    10: ("rosso", 2376898, 16.51378, -15.80503, ("06",)),
    11: ("atar", 2381334, 20.51875, -13.04637, ("07",)),
    12: ("nouadhibou", 2377457, 20.94188, -17.03842, ("08",)),
    13: ("tidjikja", 2376193, 18.55644, -11.42715, ("09",)),
    14: ("selibaby", 2376719, 15.15846, -12.1843, ("10",)),
    15: ("zouerat", 2375558, 22.73542, -12.47134, ("11",)),
    16: ("akjoujt", 2381690, 19.74163, -14.38534, ("12",)),
}
NKC = "nouakchott"
TOWN = {v[0]: (v[2], v[3]) for v in CAPITALS.values()}
# The validity guard's cases (6.5, 8.8, 9.6), a side check: place, lat, lng, its wilaya's capital, the plan's verdict
# (True: keeps the table, False: own point, None: near the threshold). GeoNames points, except Ain Ben Tili and Chami
# (the plan's approximate points).
GUARD_PLACES = (("Ain Ben Tili", 25.98, -9.57, "zouerat", False), ("Ouad Naga", 17.96636, -15.51802, "rosso", True),
                ("Boutilimit", 17.54584, -14.69777, "rosso", None), ("Bir Moghrein", 25.22125, -11.57928, "zouerat", None),
                ("Tichitt", 18.44134, -9.49394, "tidjikja", None), ("Bassikounou", 15.86652, -5.94604, "nema", None),
                ("Ouadane", 20.92767, -11.62138, "atar", None), ("Chami", 20.03, -15.95, "nouadhibou", None))


# ------------------------------------------------------------------------------------------------------------
# Data

@dataclass
class Row:
    table: str                          # the imsakiya's year
    cid: str                            # the capital's zone id
    day: datetime.date                  # the printed date
    pub: dict                           # event -> published local minute
    imsak: int
    sunset: int | None = None           # the printed sunset (2023, 2024)

    @property
    def fit_day(self) -> datetime.date:
        """The date the table computed: the reference year for the perpetual table (E5)."""
        if self.table in PERPETUAL:
            return datetime.date(REF_YEAR, self.day.month, self.day.day)
        return self.day


def minute(text: str) -> int:
    h, m = text.strip().split(":")
    return int(h) * 60 + int(m)


def load_rows() -> list[Row]:
    rows = []
    for page, (cid, *_rest) in CAPITALS.items():
        for line in (MR / "mr" / "c2025" / f"p{page:02d}.csv").read_text(encoding="utf-8").split():
            f = line.split(",")
            t = [minute(x) for x in f[1:]]
            rows.append(Row("2025", cid, datetime.date.fromisoformat(f[0]), dict(zip(EV, t[1:])), t[0]))
    for year in (2021, 2022, 2023, 2024):
        for line in (MR / "mr" / f"nkc{year}.csv").read_text(encoding="utf-8").split():
            f = line.split(",")
            t = [minute(x) for x in f[1:]]
            if len(t) == 8:                 # imsak, fajr, sunrise, dhuhr, asr, sunset, maghrib, isha
                rows.append(Row(str(year), NKC, datetime.date.fromisoformat(f[0]),
                                dict(zip(EV, (t[1], t[2], t[3], t[4], t[6], t[7]))), t[0], t[5]))
            else:
                rows.append(Row(str(year), NKC, datetime.date.fromisoformat(f[0]), dict(zip(EV, t[1:])), t[0]))
    return rows


def city_names() -> dict[str, str]:
    """The capital's name as its page heads it («إمساكية مدينة …»)."""
    pages = json.loads((MR / "mrv" / "py" / "r2025.json").read_text(encoding="utf-8"))
    out = {}
    for page, (cid, *_r) in CAPITALS.items():
        heading = next(x for x in pages[str(page)]["extra"] if x.startswith("إمساكية مدينة"))
        name = " ".join(heading.replace("إمساكية مدينة", "").split())
        out[cid] = NAME_FIX.get(name, name)
    return out


# ------------------------------------------------------------------------------------------------------------
# Unrounded times at the town points, from the reference engine; a longitude moves every time by 4 min per degree
# and any horizon between A1 and A2 is interpolated (exact to 1e-4 min over that range).

def method(fajr=19.5, isha=17.5, offsets=None, sunrise=None, sunset=None, model="MEEUS", asr_sun="GEOMETRIC",
           revisions=None) -> dict:
    hz = {"sunrise": {"type": "standard"} if sunrise is None else {"type": "fixed", "altitudeDeg": sunrise},
          "sunset": {"type": "standard"} if sunset is None else {"type": "fixed", "altitudeDeg": sunset}}
    return common.method_json(id=AUTHORITY, fajr={"type": "angle", "degrees": fajr}, isha={"type": "angle", "degrees": isha},
                              offsets=dict(offsets or {}), horizon=hz, sunModel=model, asrSun=asr_sun,
                              revisions=list(revisions or []), evidence={"kind": "FROM_TABLES", "source": "fit"})


def local(epoch: float) -> float:
    return (epoch + common.utc_offset(TZ, datetime.date(2025, 1, 1))) / 60.0 % 1440


_BASE: dict = {}


def base(rows: list[Row], model: str, asr_sun: str) -> list[dict]:
    """Per row: unrounded local minutes at its town: FAJR, ISHA at the current angles and FAJR2, ISHA2 at 19.0/17.0,
    DHUHR, ASR, and sunrise and sunset at A1 (SR1, SS1) and A2 (SR2, SS2)."""
    out = []
    for r in rows:
        key = (r.table, r.cid, r.day, model, asr_sun)
        if key not in _BASE:
            lat, lng = TOWN[r.cid]
            g1 = common.compute(common.case(method(19.5, 17.5, sunrise=A1, sunset=A1, model=model, asr_sun=asr_sun), lat, lng, TZ,
                                            r.fit_day, country="MR"))
            g2 = common.compute(common.case(method(19.0, 17.0, sunrise=A2, sunset=A2, model=model, asr_sun=asr_sun), lat, lng, TZ,
                                            r.fit_day, country="MR"))
            _BASE[key] = {"FAJR": local(g1["FAJR"][0]), "FAJR2": local(g2["FAJR"][0]), "ISHA": local(g1["ISHA"][0]),
                          "ISHA2": local(g2["ISHA"][0]), "DHUHR": local(g1["DHUHR"][0]), "ASR": local(g1["ASR"][0]),
                          "SR1": local(g1["SUNRISE"][0]), "SR2": local(g2["SUNRISE"][0]),
                          "SS1": local(g1["MAGHRIB"][0]), "SS2": local(g2["MAGHRIB"][0])}
        out.append(_BASE[key])
    return out


def interp(b: dict, which: str, alt: float) -> float:
    lo, hi = b[which + "1"], b[which + "2"]
    return lo + (alt - A1) / (A2 - A1) * (hi - lo)


def angle_time(b: dict, event: str, deg: float) -> float:
    a1, a2 = (19.5, 19.0) if event == "FAJR" else (17.5, 17.0)
    return b[event] + (deg - a1) / (a2 - a1) * (b[event + "2"] - b[event])


@dataclass
class Rule:
    """One rule set: the angles, the horizons (None = Standard), the Maghrib offset, each capital's sunrise shift."""
    fajr: float = 19.5
    isha: float = 17.5
    sunrise: float | None = None
    sunset: float | None = None
    maghrib: float = MAGHRIB_STATED
    sunrise_shift: dict = field(default_factory=dict)       # cid -> minutes (method offset + zone offset)
    model: str = "MEEUS"
    asr_sun: str = "GEOMETRIC"
    lng: dict = field(default_factory=dict)                 # cid -> computing longitude

    def times(self, r: Row, b: dict) -> dict:
        """Unrounded local minutes of the six times of row [r] at its capital's computing longitude."""
        d = -4 * (self.lng.get(r.cid, TOWN[r.cid][1]) - TOWN[r.cid][1])
        return {"FAJR": angle_time(b, "FAJR", self.fajr) + d,
                "SUNRISE": interp(b, "SR", A1 if self.sunrise is None else self.sunrise) + self.sunrise_shift.get(r.cid, 0.0) + d,
                "DHUHR": b["DHUHR"] + d, "ASR": b["ASR"] + d,
                "MAGHRIB": interp(b, "SS", A1 if self.sunset is None else self.sunset) + self.maghrib + d,
                "ISHA": angle_time(b, "ISHA", self.isha) + d}


def nearest(u: float) -> int:
    return math.floor(u + 0.5)


# ------------------------------------------------------------------------------------------------------------
# Windows

def cover(intervals) -> tuple[int, list[tuple[float, float]]]:
    """The largest number of intervals [lo, hi) with a common point, and the stretches where it is reached."""
    marks = sorted([(lo, 1) for lo, hi in intervals] + [(hi, -1) for lo, hi in intervals])
    best, cur, stretches = 0, 0, []
    for i, (x, s) in enumerate(marks):
        cur += s
        nxt = marks[i + 1][0] if i + 1 < len(marks) else x
        if nxt <= x:
            continue
        if cur > best:
            best, stretches = cur, [(x, nxt)]
        elif cur == best and best > 0:
            if stretches and stretches[-1][1] == x:
                stretches[-1] = (stretches[-1][0], nxt)
            else:
                stretches.append((x, nxt))
    return best, stretches


def pick(stretches, near: float, conventional=(), windows=None, slack: int = 0) -> float:
    """In the stretch nearest [near]: a conventional value first (or, with [windows], one that misses at most [slack]
    more windows than the best: the data's resolution), then the fewest decimals nearest its middle."""
    a, b = min(stretches, key=lambda s: 0 if s[0] <= near < s[1] else min(abs(s[0] - near), abs(s[1] - near)))
    for v in conventional:
        if a <= v < b:
            return v
        if windows is not None:
            best = cover(windows)[0]
            if sum(lo <= v < hi for lo, hi in windows) >= best - slack:
                return v
    mid = (a + b) / 2
    for decimals in range(0, 6):
        q = 10 ** decimals
        cands = [k / q for k in range(math.ceil(a * q), math.floor(b * q) + 1) if a <= k / q < b]
        if cands:
            return min(cands, key=lambda v: abs(v - mid))
    return mid


def lng_windows(rule: Rule, r: Row, b: dict, events) -> list:
    """Per event, the longitudes that give its published minute (the rule's other values fixed)."""
    t = Rule(**{**rule.__dict__, "lng": {}}).times(r, b)    # at the town
    lng0 = TOWN[r.cid][1]
    return [(lng0 + (t[e] - r.pub[e] - 0.5) / 4, lng0 + (t[e] - r.pub[e] + 0.5) / 4) for e in events]


def fit_longitudes(rule: Rule, rows, bases, events=EV) -> dict:
    """Stage 3: each capital's longitude (the town's where it is among those the most minutes allow)."""
    out = {}
    for cid in sorted({r.cid for r in rows}):
        ws = [w for r, b in zip(rows, bases) if r.cid == cid for w in lng_windows(rule, r, b, events)]
        lng0 = TOWN[cid][1]
        ws = [(max(lo, lng0 - 0.5), min(hi, lng0 + 0.5)) for lo, hi in ws if hi > lng0 - 0.5 and lo < lng0 + 0.5]
        _, stretches = cover(ws)
        if any(a <= lng0 < b for a, b in stretches):
            out[cid] = lng0
        else:
            a, b = min(stretches, key=lambda s: min(abs(s[0] - lng0), abs(s[1] - lng0)))
            out[cid] = round((a + b) / 2, 5)
    return out


def alt_windows(rule: Rule, r: Row, b: dict, which: str, offset: float) -> tuple[float, float]:
    """The horizon altitudes that give the published sunrise (SR) or Maghrib (SS) minute."""
    d = -4 * (rule.lng.get(r.cid, TOWN[r.cid][1]) - TOWN[r.cid][1])
    t1 = b[which + "1"] + offset + d
    slope = (b[which + "2"] - b[which + "1"]) / (A2 - A1)
    p = r.pub["SUNRISE" if which == "SR" else "MAGHRIB"]
    x1, x2 = A1 + (p - 0.5 - t1) / slope, A1 + (p + 0.5 - t1) / slope
    return min(x1, x2), max(x1, x2)


def fit_horizons(rule: Rule, rows, bases, report: dict) -> Rule:
    """Stage 4 for the fixed-horizon rule: sunrise altitude from Nouakchott, sunset altitude from every capital at
    sunset + 2, then the interior capitals' common sunrise shift."""
    nk = [(r, b) for r, b in zip(rows, bases) if r.cid == NKC]
    c_sr, st_sr = cover([alt_windows(rule, r, b, "SR", 0.0) for r, b in nk])
    sunrise = pick(st_sr, (st_sr[0][0] + st_sr[0][1]) / 2)
    c_ss, st_ss = cover([alt_windows(rule, r, b, "SS", rule.maghrib) for r, b in zip(rows, bases)])
    sunset = pick(st_ss, (st_ss[0][0] + st_ss[0][1]) / 2)
    rule = Rule(**{**rule.__dict__, "sunrise": sunrise, "sunset": sunset})
    ws = []
    for r, b in zip(rows, bases):
        if r.cid == NKC:
            continue
        t = Rule(**{**rule.__dict__, "sunrise_shift": {}}).times(r, b)["SUNRISE"]
        ws.append((r.pub["SUNRISE"] - 0.5 - t, r.pub["SUNRISE"] + 0.5 - t))
    c_sh, st_sh = cover(ws)
    # floor(x) - 1 on the fixed horizon reads as round(x - 1.5): kept where it misses at most one window more.
    shift = pick(st_sh, (st_sh[0][0] + st_sh[0][1]) / 2, conventional=(-1.5,), windows=ws, slack=1)
    report.update(sunrise=(c_sr, len(nk), st_sr), sunset=(c_ss, len(rows), st_ss),
                  shift=(c_sh, len(ws), st_sh, sum(a <= -1.5 < b for a, b in ws)))
    return Rule(**{**rule.__dict__, "sunrise_shift": {cid: (0.0 if cid == NKC else shift) for cid in TOWN}})


def fit_angles(rule: Rule, rows, bases, conventional) -> tuple[Rule, dict]:
    """Stage 5: the Fajr and Isha angles, by windows at the computing points."""
    out = {}
    vals = {}
    for e in ("FAJR", "ISHA"):
        ws = []
        for r, b in zip(rows, bases):
            d = -4 * (rule.lng.get(r.cid, TOWN[r.cid][1]) - TOWN[r.cid][1])
            a1, a2 = (19.5, 19.0) if e == "FAJR" else (17.5, 17.0)
            slope = (b[e + "2"] - b[e]) / (a2 - a1)
            x1 = a1 + (r.pub[e] - 0.5 - b[e] - d) / slope
            x2 = a1 + (r.pub[e] + 0.5 - b[e] - d) / slope
            ws.append((min(x1, x2), max(x1, x2)))
        c, st = cover(ws)
        vals[e] = pick(st, conventional[e], conventional=(conventional[e],))
        out[e] = (c, len(ws), st)
    return Rule(**{**rule.__dict__, "fajr": vals["FAJR"], "isha": vals["ISHA"]}), out


def exact_count(rule: Rule, rows, bases, events=EV) -> dict:
    c = {e: 0 for e in events}
    for r, b in zip(rows, bases):
        t = rule.times(r, b)
        for e in events:
            c[e] += nearest(t[e]) == r.pub[e]
    return c


def fit_rule(rows: list[Row], plan: bool = False, say=print) -> tuple[Rule, dict]:
    """Stages 1-5 on rows of the current (2023+) rule. [plan]: the plan's representation instead (standard horizons,
    Maghrib + 3, sunrise - 1 at Nouakchott and - 2.5 elsewhere), whose only free values are the points."""
    rep = {}
    # Stage 1: the sun model by Dhuhr windows (one longitude per capital).
    s1 = {}
    for model in MODELS:
        bases = base(rows, model, "GEOMETRIC")
        rule = Rule(model=model)
        s1[model] = sum(cover([w for r, b in zip(rows, bases) if r.cid == cid for w in lng_windows(rule, r, b, ("DHUHR",))])[0]
                        for cid in {r.cid for r in rows})
    model = max(MODELS, key=lambda m: (s1[m], m == "MEEUS"))
    # Stage 2: the Asr sun by Dhuhr and Asr windows together.
    s2 = {}
    for asr_sun in ASR_SUNS:
        bases = base(rows, model, asr_sun)
        rule = Rule(model=model, asr_sun=asr_sun)
        s2[asr_sun] = sum(cover([w for r, b in zip(rows, bases) if r.cid == cid
                                 for w in lng_windows(rule, r, b, ("DHUHR", "ASR"))])[0] for cid in {r.cid for r in rows})
    asr_sun = max(ASR_SUNS, key=lambda a: (s2[a], a == "GEOMETRIC"))
    bases = base(rows, model, asr_sun)
    rep.update(models=s1, asr=s2, n=len(rows))
    if plan:
        rule = Rule(model=model, asr_sun=asr_sun, maghrib=3, sunrise_shift={cid: (-1.0 if cid == NKC else -2.5) for cid in TOWN})
        rule.lng = fit_longitudes(rule, rows, bases)
        return rule, rep
    # Stages 3 and 4, repeated: points from the horizon-free times first.
    rule = Rule(model=model, asr_sun=asr_sun)
    rule.lng = fit_longitudes(rule, rows, bases, ("FAJR", "DHUHR", "ASR", "ISHA"))
    for _ in range(4):
        before = (rule.sunrise, rule.sunset, dict(rule.lng))
        rule = fit_horizons(rule, rows, bases, rep)
        rule.lng = fit_longitudes(rule, rows, bases)
        if (rule.sunrise, rule.sunset, rule.lng) == before:
            break
    rule, rep["angles"] = fit_angles(rule, rows, bases, {"FAJR": 19.5, "ISHA": 17.5})
    rule.lng = fit_longitudes(rule, rows, bases)
    rep["count"] = exact_count(rule, rows, bases)
    return rule, rep


# ------------------------------------------------------------------------------------------------------------
# The profile, the zones and the scores (reference engine, actual dates)

def profile_method(rule: Rule, old: dict) -> dict:
    """The proposed `mr_islamic_affairs`: the 2021 rule as its own fields, then E9 revisions (Ramadan 2022, and the
    current rule from 2022-05-02, the day after the last row of the 2022 table). The engine's E9 cannot change a
    horizon, so the rules before 2022-05-02 put the standard horizon on the fixed ones as whole minutes ([old])."""
    revisions = [
        {"validFrom": "2022-04-02", "change": {"offsets": {}, "fajr": {"type": "angle", "degrees": 19.0},
                                               "isha": {"type": "angle", "degrees": 17.0}, "jumua": None}},
        {"validFrom": "2022-05-02", "change": {"offsets": {"SUNRISE": 0, "MAGHRIB": int(rule.maghrib)},
                                               "fajr": {"type": "angle", "degrees": rule.fajr},
                                               "isha": {"type": "angle", "degrees": rule.isha}, "jumua": None}},
    ]
    return method(19.5, 17.5, offsets=old, sunrise=rule.sunrise, sunset=rule.sunset, model=rule.model,
                  asr_sun=rule.asr_sun, revisions=revisions)


def current_method(rule: Rule) -> dict:
    """The current rule alone (as a method with no revisions); the plan's representation when its horizons are None."""
    offsets = {"MAGHRIB": int(rule.maghrib)}
    if rule.sunrise is None:            # the plan's: Nouakchott's - 1 as a method offset, the rest as zone offsets
        offsets["SUNRISE"] = -1
    return method(rule.fajr, rule.isha, offsets=offsets, sunrise=rule.sunrise, sunset=rule.sunset, model=rule.model,
                  asr_sun=rule.asr_sun)


def zone_of(rule: Rule, cid: str) -> dict:
    lat, lng = TOWN[cid]
    plan = rule.sunrise is None
    shift = rule.sunrise_shift.get(cid, 0.0) - (-1.0 if plan else 0.0)
    z = common.zone_json(cid, lat, lng, offsets={"SUNRISE": shift} if abs(shift) > 1e-9 else {})
    if abs(rule.lng.get(cid, lng) - lng) > 1e-9:
        z["morningPoint"] = z["eveningPoint"] = common.point(lat, rule.lng[cid])
    return z


def app_minutes(m: dict, zone: dict, day: datetime.date) -> dict:
    lat, lng = zone["point"]["latE5"] / 1e5, zone["point"]["lngE5"] / 1e5
    got = common.compute(common.case(m, lat, lng, TZ, day, zone=zone, country="MR"))
    return {e: int(local(got[e][1])) for e in EV}


def score_by_zone(m: dict, rule: Rule, rows: list[Row], at_ref: bool = False) -> dict[str, common.Score]:
    """Each row as the app shows it (its actual date), or at the date the table computed ([at_ref], E5)."""
    out: dict[str, common.Score] = {}
    for r in rows:
        got = app_minutes(m, zone_of(rule, r.cid), r.fit_day if at_ref else r.day)
        s = out.setdefault(r.cid, common.Score())
        for e in EV:
            s.add(e, r.day, got[e], r.pub[e])
    return out


def score(m: dict, rule: Rule, rows: list[Row], at_ref: bool = False) -> common.Score:
    return merge(score_by_zone(m, rule, rows, at_ref).values())


def merge(scores) -> common.Score:
    out = common.Score()
    for s in scores:
        for e in EV:
            out.exact[e] += s.exact[e]
            out.within1[e] += s.within1[e]
            out.total[e] += s.total[e]
            for k in range(12):
                out.months[e][k] += s.months[e][k]
        out.worst = max(out.worst, s.worst)
        out.misses += s.misses
    return out


def shares(s: common.Score) -> str:
    return " ".join(f"{e[:3]} {s.exact[e]}/{s.total[e]}" for e in EV) + f" (within 1: {min(s.within1_shares().values()):.1%}, worst {s.worst})"


def stretch(st) -> str:
    return " ".join(f"[{a:.4f}, {b:.4f})" for a, b in st[:2])


# ------------------------------------------------------------------------------------------------------------
# Older rules (E9 fixtures), the relays and the validity guard

def old_rule(rows: list[Row], rule: Rule, say=print) -> dict:
    """The 2021 and 2022 rules at the zone's computing point, by windows: angles, the sunrise and Maghrib offsets on
    the standard horizon, and the whole-minute offsets that put them on the current fixed horizons."""
    out = {}
    nk_lng = rule.lng.get(NKC, TOWN[NKC][1])
    for year in ("2021", "2022"):
        sel = [r for r in rows if r.table == year]
        bases = base(sel, rule.model, rule.asr_sun)
        conv = {"FAJR": 19.5, "ISHA": 17.5} if year == "2021" else {"FAJR": 19.0, "ISHA": 17.0}
        # The year's own longitude from its Dhuhr and Asr (angle- and horizon-free), then its angles there.
        own = Rule(model=rule.model, asr_sun=rule.asr_sun)
        own.lng = fit_longitudes(own, sel, bases, ("DHUHR", "ASR"))
        own, ang = fit_angles(own, sel, bases, conv)
        probe = Rule(model=rule.model, asr_sun=rule.asr_sun, lng={NKC: nk_lng}, fajr=own.fajr, isha=own.isha)
        res = {}
        for which, key, horizon in (("SR", "SUNRISE", None), ("SS", "MAGHRIB", None),
                                    ("SR", "SUNRISE", rule.sunrise), ("SS", "MAGHRIB", rule.sunset)):
            ws = []
            for r, b in zip(sel, bases):
                t = interp(b, which, A1 if horizon is None else horizon) - 4 * (nk_lng - TOWN[NKC][1])
                ws.append((r.pub[key] - 0.5 - t, r.pub[key] + 0.5 - t))
            c, st = cover(ws)
            res[(key, horizon is None)] = (c, len(ws), st, ws)
        out[year] = {"angles": ang, "offsets": res, "fajr": probe.fajr, "isha": probe.isha,
                     "lng_shift": own.lng[NKC] - nk_lng}
    return out


def whole_offsets(old: dict) -> dict:
    """The whole minutes that put both years' standard-horizon sunrise and Maghrib on the fixed horizons."""
    out = {}
    for key in ("SUNRISE", "MAGHRIB"):
        ws = [w for o in old.values() for w in o["offsets"][(key, False)][3]]
        out[key] = max(range(-3, 6), key=lambda k: (sum(a <= k < b for a, b in ws), -abs(k)))
    return out


def relay_2026(m: dict, rule: Rule, say=print):
    """Al-Akhbar's Imsak of 2026-03-04 in the 13 capitals (Imsak = Fajr - 5), as the app shows it."""
    if not RELAY_2026.exists():
        return
    found = re.search(r"akh\s*=\s*(\{.*?\})", RELAY_2026.read_text(encoding="utf-8"), re.S)
    if not found:
        return
    values = ast.literal_eval(found.group(1))
    day = datetime.date(2026, 3, 4)
    hit = sum(app_minutes(m, zone_of(rule, CAPITALS[p][0]), day)["FAJR"] - 5 == minute(v) for p, v in values.items())
    ref_hit = sum(app_minutes(m, zone_of(rule, CAPITALS[p][0]), datetime.date(REF_YEAR, 3, 4))["FAJR"] - 5 == minute(v)
                  for p, v in values.items())
    say(f"  2026 relay (Al-Akhbar, Imsak of 2026-03-04, 13 capitals): {hit}/13 as the app shows 2026, {ref_hit}/13 at {REF_YEAR}")


def relay_2022(m: dict, rule: Rule, say=print):
    """Tig's relay of 2022-04-09 (Imsak and iftar, 13 capitals): the 2022 revision as the app shows it."""
    if not RELAY_2022.exists():
        say("  2022 relay: official/mr/tig2022_d8.csv not found, skipped")
        return
    names = {"Nouakchott": "nouakchott", "Nema": "nema", "Aioun": "aioun", "Kiffa": "kiffa", "Kaedi": "kaedi",
             "Aleg": "aleg", "Rosso": "rosso", "Atar": "atar", "Nouadhibou": "nouadhibou", "Tidjikja": "tidjikja",
             "Selibaby": "selibaby", "Zouerat": "zouerat", "Akjoujt": "akjoujt"}
    lines = [l for l in RELAY_2022.read_text(encoding="utf-8").splitlines() if l and not l.startswith("#")][1:]
    day = datetime.date(2022, 4, 9)
    fajr = iftar = 0
    for line in lines:
        city, imsak, ift = line.split(",")
        got = app_minutes(m, zone_of(rule, names[city]), day)
        fajr += got["FAJR"] - 5 == minute(imsak)
        iftar += got["MAGHRIB"] == minute(ift)
    say(f"  2022 relay (Tig, 2022-04-09, 13 capitals): Imsak + 5 = Fajr {fajr}/{len(lines)}, iftar = Maghrib {iftar}/{len(lines)}"
        " under the profile's 2022 revision")


def km(lat1, lng1, lat2, lng2) -> float:
    r = math.pi / 180
    a = math.sin((lat2 - lat1) * r / 2) ** 2 + math.cos(lat1 * r) * math.cos(lat2 * r) * math.sin((lng2 - lng1) * r / 2) ** 2
    return 2 * 6371.0 * math.asin(math.sqrt(a))


def guard_check(m: dict, rule: Rule, year: int = 2026, say=print):
    """The validity guard (6.5) at the plan's places, by wilaya: the table T (the capital's zone), the place's own
    reference R (the method at its point, no zone), and the table's margin at the capital's own point."""
    days = [datetime.date(year, 1, 1) + datetime.timedelta(days=i) for i in range(365)]

    def run(lat, lng, zone=None):
        return [common.compute(common.case(m, lat, lng, TZ, d, zone=zone, country="MR")) for d in days]

    def leads(r_days, t_days):
        out = {e: [] for e in EV}
        for r, t in zip(r_days, t_days):
            for e in EV:
                lead = (r[e][0] - t[e][1]) / 60
                out[e].append(-lead if e == "SUNRISE" else lead)
        return out

    cache = {}
    for name, lat, lng, cid, expect in GUARD_PLACES:
        if cid not in cache:
            z = zone_of(rule, cid)
            t_days = run(*TOWN[cid], z)
            cache[cid] = (t_days, {e: max(v) for e, v in leads(run(*TOWN[cid]), t_days).items()})
        t_days, margin = cache[cid]
        excess = {e: max(v) - margin[e] for e, v in leads(run(lat, lng), t_days).items()}
        worst = max(excess, key=excess.get)
        keeps = excess[worst] <= 4
        verdict = "keeps the table" if keeps else "own point"
        plan = {True: "keeps", False: "own point", None: "near the threshold"}[expect]
        say(f"  {name:13} -> {cid:10} {km(lat, lng, *TOWN[cid]):4.0f} km: worst {worst} {excess[worst]:+.1f} min beyond the margin: "
            f"{verdict} (plan: {plan})")


# ------------------------------------------------------------------------------------------------------------

def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--no-guard", action="store_true", help="skip the validity-guard side check")
    args = ap.parse_args(argv)
    sys.stdout.reconfigure(encoding="utf-8")

    rows = load_rows()
    names = city_names()
    cur = [r for r in rows if r.table in PERPETUAL]
    print(f"{AUTHORITY}: {len(rows)} official rows; current rule {len(cur)} ({sum(r.cid == NKC for r in cur)} Nouakchott "
          f"rows of 2023-2025, {len(cur) - sum(r.cid == NKC for r in cur)} interior rows of 2025); fixtures 2021 and 2022: "
          f"{sum(r.table in ('2021', '2022') for r in rows)}")
    # Checks of the tables themselves.
    imsak = sum(r.pub["FAJR"] - r.imsak == 5 for r in rows)
    printed = [r for r in rows if r.sunset is not None]
    same = {}
    for r in (r for r in cur if r.cid == NKC):
        same.setdefault((r.day.month, r.day.day), []).append(tuple(r.pub[e] for e in EV))
    shared = [v for v in same.values() if len(v) > 1]
    print(f"  Imsak = Fajr - 5 on {imsak}/{len(rows)} rows; printed sunset = Maghrib - 2 on "
          f"{sum(r.pub['MAGHRIB'] - r.sunset == 2 for r in printed)}/{len(printed)}; Nouakchott 2023-2025 identical on "
          f"{sum(len(set(v)) == 1 for v in shared)}/{len(shared)} shared month-days (a perpetual table)")

    # Final fit on all current rows.
    print(f"Current rule (2023-2025 rows read at {REF_YEAR}):")
    rule, rep = fit_rule(cur)
    print("  1 Dhuhr windows, one longitude per capital: " + ", ".join(f"{k} {v}" for k, v in rep["models"].items())
          + f" of {rep['n']} -> {rule.model}")
    print("  2 Dhuhr and Asr windows together: " + ", ".join(f"{k} {v}" for k, v in rep["asr"].items())
          + f" of {2 * rep['n']} -> {rule.asr_sun}")
    c, n, st = rep["sunrise"]
    print(f"  4 sunrise altitude (Nouakchott, no offset): {c}/{n} at {stretch(st)} -> {rule.sunrise}")
    c, n, st = rep["sunset"]
    print(f"    sunset altitude (all capitals, Maghrib = sunset + {MAGHRIB_STATED}): {c}/{n} at {stretch(st)} -> {rule.sunset}")
    c, n, st, at15 = rep["shift"]
    print(f"    interior sunrise shift: {c}/{n} at {stretch(st)} min, -1.5 (floor - 1) {at15}/{n} -> {rule.sunrise_shift['kiffa']}")
    for e in ("FAJR", "ISHA"):
        c, n, st = rep["angles"][e]
        print(f"  5 {e.title()} angle: {c}/{n} at {stretch(st)} -> {getattr(rule, e.lower())}")
    print("  points: " + ", ".join(f"{cid} {rule.lng[cid] - TOWN[cid][1]:+.4f}" for cid in TOWN) + " deg of longitude from the town")
    print("  in-sample at the reference year: " + " ".join(f"{e[:3]} {v}" for e, v in rep["count"].items()) + f" of {len(cur)}")
    plan_rule, _ = fit_rule(cur, plan=True)
    pc = exact_count(plan_rule, cur, base(cur, plan_rule.model, plan_rule.asr_sun))
    print("  the plan's representation (standard horizons, Maghrib + 3, sunrise - 1 / - 2.5), its points refitted: "
          + " ".join(f"{e[:3]} {v}" for e, v in pc.items()))
    geo = Rule(**{**rule.__dict__, "asr_sun": "GEOMETRIC"})
    gc = exact_count(geo, cur, base(cur, rule.model, "GEOMETRIC"))
    print(f"  Asr on the geometric sun at the same points: {gc['ASR']} against {rep['count']['ASR']}")
    # Reference-year scan: the final rule computed at each candidate year (the fit's points and horizons kept).
    scan = {}
    m_cur = current_method(rule)
    for y in range(2011, REF_YEAR + 1):
        hits = 0
        for r in cur:
            got = app_minutes(m_cur, zone_of(rule, r.cid), datetime.date(y, r.day.month, r.day.day))
            hits += sum(got[e] == r.pub[e] for e in EV)
        scan[y] = hits
    top = sorted(scan.items(), key=lambda kv: -kv[1])
    print("  reference years, all six times: " + ", ".join(f"{y} {c}" for y, c in top[:6]) + f" ... worst {top[-1][0]} {top[-1][1]}"
          f" (of {6 * len(cur)})")

    # Held out: Nouakchott by month, the interior by day.
    def in_a(r: Row) -> bool:
        return r.day.month == 3 if r.cid == NKC else r.day.day % 2 == 1

    held, held_plan, held_ref = {}, [], []
    for name, fit_rows, score_rows in (("A", [r for r in cur if in_a(r)], [r for r in cur if not in_a(r)]),
                                       ("B", [r for r in cur if not in_a(r)], [r for r in cur if in_a(r)])):
        fr, frep = fit_rule(fit_rows)
        pr, _ = fit_rule(fit_rows, plan=True)
        by_zone = score_by_zone(current_method(fr), fr, score_rows)
        for cid, s in by_zone.items():
            held[cid] = merge([held.get(cid, common.Score()), s])
        held_ref.append(score(current_method(fr), fr, score_rows, at_ref=True))
        held_plan.append(score(current_method(pr), pr, score_rows))
        print(f"  fold {name}: fitted on {len(fit_rows)} rows ({fr.model}, Asr {fr.asr_sun}, sunrise {fr.sunrise}, sunset "
              f"{fr.sunset}, shift {fr.sunrise_shift['kiffa']}, Fajr {fr.fajr}, Isha {fr.isha}); held out {len(score_rows)}: "
              f"{shares(merge(by_zone.values()))}")
    all_held = merge(held.values())
    print("Held out, as the app shows it (actual years): " + shares(all_held))
    print("  the same at the reference year (the table itself): " + shares(merge(held_ref)))
    print("  the plan's representation, held out as the app shows it: " + shares(merge(held_plan)))

    # Older rules and the proposed profile.
    old = old_rule(rows, rule)
    for year, o in old.items():
        a = o["angles"]
        print(f"Fixture {year} (its own longitude {o['lng_shift'] * 1e3:+.1f} mdeg from the zone's): Fajr {a['FAJR'][0]}/{a['FAJR'][1]}"
              f" at {stretch(a['FAJR'][2])} -> {o['fajr']}; Isha {a['ISHA'][0]}/{a['ISHA'][1]} at {stretch(a['ISHA'][2])} -> {o['isha']}")
        for (key, std), (c, n, st, _) in o["offsets"].items():
            print(f"    {key.title()} offset on the {'standard' if std else 'fixed'} horizon: {c}/{n} at {stretch(st)}")
    whole = whole_offsets(old)
    profile = profile_method(rule, whole)
    exact_old = method(19.5, 17.5, offsets={"MAGHRIB": MAGHRIB_STATED}, model=rule.model, asr_sun=rule.asr_sun,
                       revisions=[{"validFrom": "2022-04-02", "change": {"offsets": {}, "fajr": {"type": "angle", "degrees": 19.0},
                                                                         "isha": {"type": "angle", "degrees": 17.0}, "jumua": None}}])
    for year in ("2021", "2022"):
        sel = [r for r in rows if r.table == year]
        print(f"  {year} as the proposed profile computes it (E9, whole-minute offsets {whole} on the fixed horizons): "
              + shares(score(profile, rule, sel)))
        print(f"  {year} with its own standard horizons (an E9 that can change the horizon): " + shares(score(exact_old, rule, sel)))
    prof_cur = score(profile, rule, cur)
    print("Proposed profile on the 2023-2025 rows, as the app shows them: " + shares(prof_cur))
    relay_2026(profile, rule)
    relay_2022(profile, rule)

    # Zones and FitStats.
    first, last = min(r.day for r in cur), max(r.day for r in cur)
    zones = []
    for page, (cid, gid, lat, lng, admin1) in CAPITALS.items():
        own = [r for r in cur if r.cid == cid]
        held_own = held[cid]
        if cid == NKC:
            fitted_on = [common.sample(SAMPLE_NKC, min(r.day for r in own), max(r.day for r in own), len(own), 1)]
            held_out = [common.sample(SAMPLE_NKC + ", March and April month-days split", min(r.day for r in own),
                                      max(r.day for r in own), len(own), 1, month_split=True)]
        else:
            fitted_on = [common.sample(SAMPLE_2025 + f": {cid}", min(r.day for r in own), max(r.day for r in own), len(own), 1)]
            held_out = [common.sample(SAMPLE_2025 + f": {cid}, odd and even dates split", min(r.day for r in own),
                                      max(r.day for r in own), len(own), 1)]
        z = zone_of(rule, cid)
        zones.append({
            "id": cid, "nameAr": names[cid], "point": common.point(lat, lng),
            "morningPoint": z.get("morningPoint"), "eveningPoint": z.get("eveningPoint"),
            "offsets": z["offsets"], "admin1": list(admin1),
            "namedPlaces": [{"centre": common.point(lat, lng), "catalogueIds": [gid]}],
            "fit": common.fit_stats(fitted_on, held_out, held_own, ramadan_days=len(own)),
        })
    profile_fit = common.fit_stats(
        [common.sample(SAMPLE_2025 + " and Nouakchott 2023-2024", first, last, len(cur), 13)],
        [common.sample(SAMPLE_2025 + ": Nouakchott by month (March, April), the interior capitals by date (odd, even)",
                       first, last, len(cur), 13, month_split=True)],
        all_held, ramadan_days=len(cur))
    path = common.write_asset(AUTHORITY, AUTHORITY, "MR", "NEAREST", zones, fit=profile_fit, source=SOURCE_LINE, by_admin1=True)

    print("Zones (computing point, held-out exact of 6 per day):")
    for z in zones:
        f = z["fit"]
        mp = z.get("morningPoint")
        moved = "town" if mp is None else f"{km(z['point']['latE5'] / 1e5, z['point']['lngE5'] / 1e5, mp['latE5'] / 1e5, mp['lngE5'] / 1e5):.1f} km"
        print(f"  {z['id']:11} {z['nameAr']:9} {moved:7} offsets {z['offsets'] or '-'}  held out: "
              + " ".join(f"{e[:3]} {100 * f['exact'][e]:.0f}" for e in EV))
    within = all(z["fit"]["within1"][e] >= 1.0 for z in zones for e in EV)
    print(f"Tier B (9.5): every held-out time within 1 minute: {'yes' if within else 'no'}; worst {all_held.worst} min. "
          "No zone has held-out days outside March and April, so none could meet tier A's coverage.")
    if not args.no_guard:
        print("Validity guard, 2026 (side check, by wilaya):")
        guard_check(current_method(rule), rule)
    print(f"Proposed profile: Fajr {rule.fajr}, Isha {rule.isha}, horizons Fixed({rule.sunrise}) / Fixed({rule.sunset}), Maghrib "
          f"+{int(rule.maghrib)}, Asr {rule.asr_sun}, {rule.model}; interior zones SUNRISE {rule.sunrise_shift['kiffa']} (E7); "
          f"base (2021) offsets {whole}, revisions 2022-04-02 (19.0/17.0) and 2022-05-02 (current)")
    print(f"Wrote {path.relative_to(common.ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
