#!/usr/bin/env python3
"""
Fits `eg_egsa`, the Egyptian General Survey Authority's city tables (plan 8.1, 8.2, 8.7, 8.8; milestone AM2, 9.4),
and writes its official-zone asset, android-app/app/src/main/assets/official-zones/eg_egsa.json (schema 1).

Data, read at run time and never copied (D13, A7):
- test-data-local/research-2026-10-08/EG/eg/praytimes.html: EGSA's all-cities daily page of 2026-10-08 (78 cities)
  and its city menu (83 options; a city's position in it is its zone id).
- The Wayback Machine's copies of the same page (web.archive.org/web/<timestamp>/https://www.esa.gov.eg/
  praytimes.aspx), one HTML file per capture named by its timestamp: 2024-07-23, 2024-08-11, 2025-04-29, 2025-07-05,
  2025-08-18, 2025-12-23, 2026-02-11, 2026-03-20 and 2026-06-12. Read from test-data-local/research-2026-10-11/EG/
  wayback/, or from --wayback DIR.
- test-data-local/research-2026-10-08/EG/eg_skeptic/ram.py and aug.py: hand transcriptions, embedded in those
  scripts, of EGSA's Ramadan 1445 imsakiya for Cairo (2024-03-11 to 04-09) and of its 2025-08-13 table for six
  cities, both as reprinted by Al-Dostor. Read with ast.literal_eval (never executed) and only held out.
- Town centres: GeoNames (geonames.org, read 2026-10-15), in TOWNS below; they are not official times.

Method. The 2025-26 copies (8 days, 623 city-days, 621 without two quirk days) are the fitting data. Each published
minute bounds the unrounded time to a minute (E1, nearest); for a candidate profile each city's windows are strips
in (latitude, longitude), linearised at the city's current point, and the city's point is the centroid of their
intersection (or of the largest subset one point covers). Stages (9.4), each by the count of windows covered:
1. Dhuhr: the sun model (MEEUS or VSOP87, E14), from the Dhuhr windows alone (one longitude per city; on a tie,
   the sun that leaves the cities the widest common longitude intervals). A Dhuhr offset or another rounding equals
   moving every city by 0.25 deg of longitude per minute, so the town centres decide it.
2. Sunrise and sunset: one horizon altitude for every city (E2 `Fixed`), with each city's point free.
3. Fajr and Isha: Dar al-Ifta's stated 19.5 deg and quoted 17.5 deg, kept where the windows' best values hold them.
4. Asr: factor 1 or 2, the noon shadow's declination at transit or at local midnight (E4), geometric or
   apparent sun (E13).
Held out: leave one day out. For each of the 8 days, the sun model, the horizon, the Asr rule and every city's
point are refitted on the 7 others and the day is scored with common.compute (the reference engine, which the
Kotlin engine matches); the same is repeated with the standard horizon and with the MEEUS sun for comparison. The
two reprints are held out at the final points. Quirk days (8.3), counted apart: the 2024 copies, made before EGSA
moved 49 cities' points (printed below), and two new cities on 2025-04-29, before their points moved.

Usage: python scripts/prayer_formula/fits/eg_egsa/fit.py [--wayback DIR]
"""

from __future__ import annotations

import argparse
import ast
import datetime
import html
import itertools
import math
import re
import statistics
import sys
import time
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import common  # noqa: E402

ref = common.ref
EV = common.EVENTS
AUTHORITY = "eg_egsa"
TZ = "Africa/Cairo"
EG = common.LOCAL_DATA / "research-2026-10-08" / "EG"
PAGE = EG / "eg" / "praytimes.html"
RAMADAN_REPRINT = EG / "eg_skeptic" / "ram.py"
AUGUST_REPRINT = EG / "eg_skeptic" / "aug.py"
WAYBACK = common.LOCAL_DATA / "research-2026-10-11" / "EG" / "wayback"
PAGE_SOURCE = "EGSA's all-cities daily page (esa.gov.eg/praytimes.aspx): Wayback copies and the 2026-10-08 copy"
RAMADAN_SOURCE = "EGSA's Ramadan 1445 imsakiya for Cairo, as reprinted by Al-Dostor"
AUGUST_SOURCE = "EGSA's 2025-08-13 table for six cities, as reprinted by Al-Dostor"

# Quirk days (plan 8.3): city-days the current points do not describe, counted apart and never scored.
POINTS_REVISED = datetime.date(2025, 1, 1)   # the 2024 copies predate EGSA's revision of 49 cities' points
REVISED_2025 = {("توشكى الجديدة", datetime.date(2025, 4, 29)), ("غرب النوبارية", datetime.date(2025, 4, 29))}

# The published angles (Dar al-Ifta, fatwa 4021): kept wherever the windows' best values include them.
FAJR_STATED, ISHA_QUOTED = 19.5, 17.5
ASR_RULES = ("shadow", "apparent", "shadow2", "midnight")
STEP = 0.01                  # degrees, for the numerical partial derivatives
DENSITY = 10_000.0           # inhabitants per km2, for a city's own area (areaKm)

# EGSA menu name -> (label, GeoNames id, lat, lng, GeoNames population). GeoNames has no populated place for the
# rows with None (Toshka's project town and the new cities): their centre is the fitted point. Ras Sudr is the
# GeoNames cape of the town; 6 October's point is GeoNames 7577132 (353219 gives the population but lies 15 km out).
TOWNS = {
    "القاهرة": ("Cairo", 360630, 30.06263, 31.24967, 9606916),
    "الأسكندرية": ("Alexandria", 361058, 31.20167, 29.91556, 5263542),
    "طنطا": ("Tanta", 347497, 30.78833, 31.00167, 576648),
    "المنصورة": ("Mansoura", 360761, 31.036373, 31.380691, 621953),
    "الزقازيق": ("Zagazig", 359493, 30.5875, 31.50194, 430445),
    "أسيوط": ("Asyut", 359783, 27.180956, 31.183683, 528669),
    "سوهاج": ("Sohag", 347796, 26.556952, 31.694785, 266944),
    "بنى سويف": ("Beni Suef", 359173, 29.07417, 31.09778, 273151),
    "المنيا": ("Minya", 360686, 28.091932, 30.758135, 283605),
    "قنا": ("Qena", 350550, 26.164179, 32.72671, 252883),
    "أسوان": ("Aswan", 359792, 24.09082, 32.89942, 379774),
    "مطروح": ("Marsa Matruh", 352733, 31.3529, 27.23725, 176498),
    "الغردقة": ("Hurghada", 361291, 27.257376, 33.812914, 207132),
    "الخارجة": ("Kharga", 360923, 25.451006, 30.546527, 83108),
    "الإسماعيلية": ("Ismailia", 361055, 30.604272, 32.272252, 429465),
    "دمياط": ("Damietta", 358048, 31.416477, 31.813316, 305920),
    "السلوم": ("Sallum", 359867, 31.553713, 25.157927, 16545),
    "نويبع": ("Nuweiba", 350893, 29.046811, 34.663397, 5000),
    "حلايب": ("Halaib", 374290, 22.22273, 36.64679, 27000),
    "شرم الشيخ": ("Sharm el-Sheikh", 349340, 27.915817, 34.32995, 38478),
    "الفيوم": ("Faiyum", 361320, 29.309949, 30.841804, 519047),
    "6أكتوبر": ("6th of October City", 7577132, 29.946443, 30.916871, 368650),
    "كفر الشيخ": ("Kafr el-Sheikh", 354502, 31.111741, 30.939913, 194569),
    "الحامول": ("El Hamool", 361213, 31.31146, 31.147665, 58430),
    "بلطيم": ("Baltim", 359304, 31.555821, 31.089177, 51280),
    "طابا": ("Taba", 347702, 29.49199, 34.89572, 700),
    "راس سدر": ("Ras Sudr", 347816, 29.598864, 32.682732, None),
    "الطور": ("El Tor", 359678, 28.24168, 33.6222, 38285),
    "دهب": ("Dahab", 358245, 28.48208, 34.49505, 8000),
    "ابوزنيمة": ("Abu Zenima", 361930, 29.047498, 33.110336, None),
    "راس غارب": ("Ras Gharib", 350207, 28.358306, 33.078289, 44852),
    "القصير": ("El Quseir", 360531, 26.106113, 34.277162, 24653),
    "برنيس": ("Berenice", 359073, 23.945556, 35.484167, None),
    "سفاجة": ("Safaga", 358620, 26.74906, 33.93891, 53639),
    "مرسى علم": ("Marsa Alam", 352736, 25.063054, 34.890046, 10000),
    "شلاتين": ("Shalateen", 8555994, 23.132349, 35.59094, 12443),
    "سيدى برانى": ("Sidi Barrani", 348548, 31.611665, 25.925394, 38253),
    "الضبعة": ("El Dabaa", 361871, 31.028189, 28.444976, 27697),
    "العلمين": ("El Alamein", 361612, 30.830066, 28.955019, 7400),
    "واحة سيوة": ("Siwa", 347863, 29.203195, 25.519652, 25031),
    "موط (الداخلة)": ("Mut", 351766, 25.487422, 28.979184, 26820),
    "الفرافرة": ("Farafra", 350661, 27.056799, 27.969793, 5000),
    "البويطى": ("Bawiti", 361394, 28.348274, 28.869362, 15653),
    "باريس": ("Baris", 359085, 24.67379, 30.601009, None),
    "المحلة الكبرى": ("El Mahalla el Kubra", 360829, 30.970632, 31.166902, 592573),
    "الحمام": ("El Hammam", 361219, 30.845322, 29.388179, 18407),
    "نجع حمادى": ("Nag Hammadi", 351434, 26.04949, 32.24142, 59601),
    "بورسعيد": ("Port Said", 358619, 31.26531, 32.3019, 780515),
    "السويس": ("Suez", 359796, 29.973714, 32.526267, 699541),
    "العريش": ("Arish", 361546, 31.131592, 33.798437, 199243),
    "دمنهور": ("Damanhur", 358448, 31.034084, 30.468233, 318207),
    "ادفو": ("Edfu", 355635, 24.979163, 32.877224, 79510),
    "اسنا": ("Esna", 355449, 25.29336, 32.55402, 462787),
    "الأقصر": ("Luxor", 360502, 25.698927, 32.642097, 422407),
    "القاهرة الجديدة": ("New Cairo", 7799991, 30.03, 31.47, 313139),
    "توشكى": ("Toshka", None, None, None, None),
    "الدلنجات": ("Delengat", 361827, 30.827965, 30.535516, 68901),
    "كاترين": ("Saint Catherine", 8521413, 28.561909, 33.949342, 4603),
    "السادات": ("Sadat City", 353223, 30.367418, 30.508706, 71501),
    "مدينة 15 مايو": ("15th of May City", 7800138, 29.829831, 31.369649, None),
    "الصالحية الجديدة": ("New Salhia", 433774, 30.63146, 31.939274, 59320),
    "العاشر من رمضان": ("10th of Ramadan City", 353229, 30.29636, 31.746326, 246148),
    "العاصمة الادارية الجديدة": ("New Administrative Capital", 12188760, 30.023848, 31.754963, None),
    "العبور": ("Obour City", 12451089, 30.22886, 31.48149, 138987),
    "الفشن الجديدة": ("New Fashn", None, None, None, None),
    "المنصورة الجديدة": ("New Mansoura", None, None, None, None),
    "النوبارية الجديدة": ("New Nubaria", 408919, 30.668705, 30.073909, 23282),
    "بدر": ("Badr City", 415561, 30.136, 31.715, 32940),
    "بنى مزار الجديدة": ("New Beni Mazar", None, None, None, None),
    "بورسعيد الجديدة سلام": ("New Port Said (Salam)", None, None, None, None),
    "بئر العبد الجديدة": ("New Bir al-Abd", None, None, None, None),
    "توشكى الجديدة": ("New Toshka", None, None, None, None),
    "حدائق العاصمة": ("Hadayek al-Asema", None, None, None, None),
    "رشيد الجديدة": ("New Rashid", None, None, None, None),
    "رفح الجديدة": ("New Rafah", None, None, None, None),
    "سفنكس الجديدة": ("New Sphinx City", None, None, None, None),
    "شرق العوينات": ("East Owainat", None, None, None, None),
    "غرب النوبارية": ("West Nubaria", None, None, None, None),
}


# ------------------------------------------------------------------------------------------------ data

def clean(name: str) -> str:
    """EGSA's name without its tatweel (U+0640) and stray spaces."""
    name = re.sub(r"\s+", " ", name.replace(chr(0x0640), "")).strip()
    return name.replace("( ", "(").replace(" )", ")")


def display_name(name: str) -> str:
    """The zone's Arabic name: EGSA's, with a space between a number and the word after it («6 أكتوبر»)."""
    return re.sub(r"(\d)([^\d\s])", r"\1 \2", name)


def clock(text: str) -> int:
    """'4:2 م' -> 962 (EGSA prints 12-hour times with ص/م and unpadded minutes)."""
    m = re.fullmatch(r"(\d{1,2}):(\d{1,2})\s*(ص|م)", text.strip())
    h, minute = int(m.group(1)), int(m.group(2))
    if m.group(3) == "م" and h != 12:
        h += 12
    if m.group(3) == "ص" and h == 12:
        h = 0
    return 60 * h + minute


def page_rows(path: Path) -> dict[datetime.date, dict[str, list[int]]]:
    text = path.read_text(encoding="utf-8", errors="replace")
    table = re.search(r'id="placeholder1_GridView1".*?</table>', text, re.S)
    out: dict[datetime.date, dict[str, list[int]]] = {}
    for tr in re.findall(r"<tr.*?</tr>", table.group(0), re.S):
        cells = [html.unescape(re.sub(r"<[^>]+>", "", c)).strip() for c in re.findall(r"<td.*?</td>", tr, re.S)]
        if len(cells) == 9:
            out.setdefault(datetime.date.fromisoformat(cells[1]), {})[clean(cells[0])] = [clock(c) for c in cells[3:]]
    return out


def official_days(wayback: Path) -> tuple[dict, list[str]]:
    days: dict[datetime.date, dict[str, list[int]]] = {}
    files = sorted(wayback.glob("*.html")) if wayback.is_dir() else []
    for f in files + [PAGE]:
        for day, rows in page_rows(f).items():
            days.setdefault(day, {}).update(rows)
    return days, [f.name for f in files]


def city_menu() -> list[str]:
    text = PAGE.read_text(encoding="utf-8", errors="replace")
    select = re.search(r'<select name="ctl00\$placeholder1\$DropDownList1".*?</select>', text, re.S).group(0)
    return [clean(html.unescape(t)) for t in re.findall(r"<option[^>]*>(.*?)</option>", select, re.S)]


def assigned(path: Path, name: str):
    """A literal assigned at the top level of a script, read without running it."""
    for node in ast.parse(path.read_text(encoding="utf-8")).body:
        if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == name for t in node.targets):
            return ast.literal_eval(node.value)
    raise KeyError(f"{name} not in {path.name}")


def reprints() -> tuple[dict, dict]:
    ramadan = {}
    for line in assigned(RAMADAN_REPRINT, "rows").splitlines():   # day month, then six 12-hour times (UTC+2)
        p = line.split()
        values = []
        for i, s in enumerate(p[2:]):
            h, m = (int(x) for x in s.split(":"))
            if (i == 2 and h < 11) or i >= 3:
                h += 12
            values.append(60 * h + m)
        ramadan[datetime.date(2024, int(p[1]), int(p[0]))] = values
    names = {"Cairo": "القاهرة", "Alexandria": "الأسكندرية", "Tanta": "طنطا", "Mansoura": "المنصورة",
             "Zagazig": "الزقازيق", "Asyut": "أسيوط"}
    august = {}
    for key, (_, _, text) in assigned(AUGUST_REPRINT, "off").items():   # 24-hour times (UTC+3)
        if key in names:
            august[names[key]] = [int(s.split(":")[0]) * 60 + int(s.split(":")[1]) for s in text.split()]
    return ramadan, {datetime.date(2025, 8, 13): august}


# ------------------------------------------------------------------------------------------------ fast times

class Suns:
    """The search loops' suns: MEEUS as the reference computes it; VSOP87 tabulated hourly over the data's span
    and interpolated (4-point Lagrange, within 1e-9 deg and 1e-8 min of the series)."""

    def __init__(self, first: datetime.date, last: datetime.date):
        self.jd0 = ref.julian_day0(first) - 2
        n = int((ref.julian_day0(last) + 3 - self.jd0) * 24) + 4
        self.table = [ref.vsop87_sun(self.jd0 + i / 24) for i in range(n)]

    def model(self, name: str):
        return ref.meeus_sun if name == "MEEUS" else self.vsop

    def vsop(self, jd: float) -> tuple[float, float, float]:
        x = (jd - self.jd0) * 24
        i = int(x) - 1
        u = x - i - 1
        w = (-u * (u - 1) * (u - 2) / 6, (u + 1) * (u - 1) * (u - 2) / 2,
             -(u + 1) * u * (u - 2) / 2, (u + 1) * u * (u - 1) / 6)
        rows = self.table[i:i + 4]
        base = rows[0][1]
        return (sum(k * r[0] for k, r in zip(w, rows)),
                sum(k * (r[1] - 1440 * round((r[1] - base) / 1440)) for k, r in zip(w, rows)),
                sum(k * r[2] for k, r in zip(w, rows)))


_clock: dict[datetime.date, tuple[int, int, int]] = {}


def clock_facts(day: datetime.date) -> tuple[int, int, int]:
    if day not in _clock:
        _clock[day] = (common.standard_offset(TZ, day), common.utc_offset(TZ, day), common.utc_offset(TZ, day, 0))
    return _clock[day]


def raw(lat: float, lng: float, day: datetime.date, p: dict, suns: Suns) -> dict[str, float]:
    """Unrounded local clock minutes of the six times (S2-S7 with EVENT_INSTANT, as the reference computes them)."""
    std, actual, midnight_offset = clock_facts(day)
    noon = ref.epoch_days(day) * 86400 + 43200
    cycle = ref.Cycle(ref.EPOCH_DATE + datetime.timedelta(days=math.floor((noon - std + 240 * lng) / 86400)))
    model = suns.model(p["model"])
    sun = ref.SunFunction(model, "EVENT_INSTANT", cycle.jd0)
    t0 = ref.transit(cycle, sun, lng)
    origin = ref.epoch_days(day) * 86400 - actual

    def local(hours):
        return (cycle.instant(hours) - origin) / 60.0

    out = {
        "DHUHR": local(t0),
        "SUNRISE": local(ref.event(cycle, sun, lat, lng, p["horizon"], -1, t0)),
        "MAGHRIB": local(ref.event(cycle, sun, lat, lng, p["horizon"], 1, t0)),
        "FAJR": local(ref.event(cycle, sun, lat, lng, -p["fajr"], -1, t0)),
        "ISHA": local(ref.event(cycle, sun, lat, lng, -p["isha"], 1, t0)),
    }
    rule = p["asr"]
    if rule == "midnight":
        decl = model(ref.jd_of_instant(ref.epoch_days(day) * 86400 - midnight_offset))[0]
    else:
        decl = sun(cycle.jd0 + t0 / 24)[0]
    k = 2 if rule == "shadow2" else 1
    zenith = abs(lat - decl)
    alt = ref.atand(1 / (k + ref.tand(zenith)))
    if rule == "apparent":
        h0 = 90 - zenith
        a_app = ref.atand(1 / (k + ref.tand(90 - (h0 + ref.bennett(h0, 1010.0, 10.0)))))
        alt = a_app - ref.bennett(a_app, 1010.0, 10.0)
    out["ASR"] = local(ref.event(cycle, sun, lat, lng, alt, 1, t0))
    return out


# ------------------------------------------------------------------------------------------------ windows

class City:
    """A city's fitting days and its six times linearised in (latitude, longitude) and the profile's values."""

    def __init__(self, name: str, obs: dict, base: tuple[float, float]):
        self.name, self.obs, self.base = name, obs, base
        self.lin: dict[tuple[str, datetime.date], dict] = {}
        self.p0: dict[str, dict] = {}

    def linearise(self, model: str, p0: dict, suns: Suns) -> None:
        p0 = {**p0, "model": model}
        self.p0[model] = p0
        lat, lng = self.base
        for day in self.obs:
            t = raw(lat, lng, day, p0, suns)
            t_lat = raw(lat + STEP, lng, day, p0, suns)
            t_lng = raw(lat, lng + STEP, day, p0, suns)
            t_dep = raw(lat, lng, day, {**p0, "horizon": p0["horizon"] - STEP, "fajr": p0["fajr"] + STEP,
                                        "isha": p0["isha"] + STEP}, suns)
            asr = {r: raw(lat, lng, day, {**p0, "asr": r}, suns)["ASR"] for r in ASR_RULES}
            self.lin[(model, day)] = {
                "t": t, "a": {e: (t_lat[e] - t[e]) / STEP for e in EV}, "b": {e: (t_lng[e] - t[e]) / STEP for e in EV},
                "d": {e: (t_dep[e] - t[e]) / STEP for e in EV}, "asr": asr}

    def rows(self, model: str, days, p: dict, events=EV) -> list[tuple]:
        """(a, b, lo, hi, day, event): the city's point (base + (x, y) degrees) keeps the minute iff lo <= a x + b y < hi."""
        p0 = self.p0[model]
        out = []
        for day in days:
            if day not in self.obs:
                continue
            lin, mins = self.lin[(model, day)], self.obs[day]
            for i, e in enumerate(EV):
                if e not in events:
                    continue
                t = lin["t"][e]
                if e in ("SUNRISE", "MAGHRIB"):
                    t += lin["d"][e] * (p0["horizon"] - p["horizon"])
                elif e == "FAJR":
                    t += lin["d"][e] * (p["fajr"] - p0["fajr"])
                elif e == "ISHA":
                    t += lin["d"][e] * (p["isha"] - p0["isha"])
                elif e == "ASR":
                    t = lin["asr"][p["asr"]]
                out.append((lin["a"][e], lin["b"][e], mins[i] - 0.5 - t, mins[i] + 0.5 - t, day, e))
        return out


def clip(poly, a, b, c, keep_ge):
    out = []
    for i in range(len(poly)):
        p, q = poly[i], poly[(i + 1) % len(poly)]
        fp, fq = a * p[0] + b * p[1] - c, a * q[0] + b * q[1] - c
        if not keep_ge:
            fp, fq = -fp, -fq
        if fp >= 0:
            out.append(p)
        if (fp >= 0) != (fq >= 0):
            s = fp / (fp - fq)
            out.append((p[0] + s * (q[0] - p[0]), p[1] + s * (q[1] - p[1])))
    return out


def polygon(rows, box: float = 1.0):
    poly = [(-box, -box), (box, -box), (box, box), (-box, box)]
    for a, b, lo, hi, _, _ in rows:
        poly = clip(poly, a, b, lo, True)
        if not poly:
            return []
        poly = clip(poly, a, b, hi, False)
        if not poly:
            return []
    return poly


def centroid(poly) -> tuple[float, float]:
    area = cx = cy = 0.0
    for i in range(len(poly)):
        (x0, y0), (x1, y1) = poly[i], poly[(i + 1) % len(poly)]
        cross = x0 * y1 - x1 * y0
        area += cross
        cx += (x0 + x1) * cross
        cy += (y0 + y1) * cross
    if abs(area) < 1e-14:
        return sum(p[0] for p in poly) / len(poly), sum(p[1] for p in poly) / len(poly)
    return cx / (3 * area), cy / (3 * area)


def violation(row, x: float, y: float) -> float:
    v = row[0] * x + row[1] * y
    return max(row[2] - v, v - row[3], 0.0)


def cover(rows, deep: bool = True):
    """The most windows one point covers, and that point (x, y): all windows, else all but the one or two that the
    current point misses most, else the best cell of a grid (deep only)."""
    poly = polygon(rows)
    if poly:
        return len(rows), centroid(poly)
    worst = sorted(range(len(rows)), key=lambda i: -violation(rows[i], 0.0, 0.0))[:6]
    for i in worst:
        poly = polygon(rows[:i] + rows[i + 1:])
        if poly:
            return len(rows) - 1, centroid(poly)
    if not deep:
        return len(rows) - 2, (0.0, 0.0)
    for i, j in itertools.combinations(worst, 2):
        poly = polygon([r for k, r in enumerate(rows) if k not in (i, j)])
        if poly:
            return len(rows) - 2, centroid(poly)
    best, cells = -1, []
    for i in range(-60, 61):
        for j in range(-60, 61):
            x, y = 0.005 * i, 0.005 * j
            n = sum(1 for r in rows if r[2] <= r[0] * x + r[1] * y < r[3])
            if n > best:
                best, cells = n, [(x, y)]
            elif n == best:
                cells.append((x, y))
    return best, (sum(c[0] for c in cells) / len(cells), sum(c[1] for c in cells) / len(cells))


def stab(intervals) -> int:
    """The most intervals [lo, hi) one value lies in."""
    ends = sorted([(lo, 1) for lo, _ in intervals] + [(hi, -1) for _, hi in intervals], key=lambda e: (e[0], e[1]))
    best = cur = 0
    for _, step in ends:
        cur += step
        best = max(best, cur)
    return best


# ------------------------------------------------------------------------------------------------ stages

def dhuhr_cover(cities, model: str, days) -> tuple[int, float]:
    """Stage 1: the Dhuhr windows one longitude per city covers, and the slack they leave (the summed width, in
    degrees, of each fully covered city's common longitude interval: a sun whose equation of time is off narrows it)."""
    total, slack = 0, 0.0
    for c in cities:
        intervals = []
        for a, b, lo, hi, _, _ in c.rows(model, days, c.p0[model], ("DHUHR",)):
            intervals.append(tuple(sorted((lo / b, hi / b))))
        n = stab(intervals)
        total += n
        if n == len(intervals):
            slack += min(i[1] for i in intervals) - max(i[0] for i in intervals)
    return total, slack


def scan(cities, model, days, p, key, values, events) -> dict[float, int]:
    return {v: sum(cover(c.rows(model, days, {**p, key: v}, events), deep=False)[0] for c in cities) for v in values}


def best_value(cities, model, days, p, key, lo, hi, coarse, fine, events, keep=None):
    """Coarse then fine scan of one value; the middle of the best plateau (or [keep] when the plateau holds it)."""
    n = int(round((hi - lo) / coarse))
    scores = scan(cities, model, days, p, key, [round(lo + coarse * i, 4) for i in range(n + 1)], events)
    tops = [v for v in scores if scores[v] == max(scores.values())]
    first, last = min(tops) - coarse, max(tops) + coarse          # the coarse plateau and a coarse step around it
    fine_values = [round(first + fine * i, 4) for i in range(int(round((last - first) / fine)) + 1)]
    scores.update(scan(cities, model, days, p, key, fine_values, events))
    best = max(scores.values())
    plateau = sorted(v for v in fine_values if scores.get(v) == best)
    if keep is not None and scores.get(keep) == best:
        return keep, best, plateau
    if not plateau:
        plateau = [v for v, s in scores.items() if s == best]
    return round((plateau[0] + plateau[-1]) / 2, 4), best, plateau


def select_profile(cities, days, full: bool, say=None, force: dict | None = None) -> dict:
    """Stages 1-4 on [days]; with full=False the published angles are kept without their scans (folds). [force]
    fixes the sun model or the horizon instead of fitting it (the alternatives the report compares)."""
    force = force or {}
    base = {"horizon": -0.8333, "fajr": FAJR_STATED, "isha": ISHA_QUOTED, "asr": "shadow"}
    sunrise_sunset = ("DHUHR", "SUNRISE", "MAGHRIB")
    results = {}
    for model in [force["model"]] if "model" in force else ["MEEUS", "VSOP87"]:
        dh = dhuhr_cover(cities, model, days)
        if "horizon" in force:
            h = force["horizon"]
            cov = scan(cities, model, days, {**base, "model": model}, "horizon", [h], sunrise_sunset)[h]
            plateau = [h]
        else:
            h, cov, plateau = best_value(cities, model, days, {**base, "model": model}, "horizon", -0.880, -0.800, 0.005,
                                         0.001, sunrise_sunset)
        results[model] = (dh, cov, h, plateau)
    model = max(results, key=lambda m: (results[m][0], results[m][1]))
    dh, cov, h, plateau = results[model]
    p = {**base, "model": model, "horizon": h}
    if say:
        n_rows = sum(len(c.rows(model, days, p, sunrise_sunset)) for c in cities)
        for m, (d, c_, hh, pl) in results.items():
            standard = scan(cities, m, days, {**base, "model": m}, "horizon", [-0.8333], sunrise_sunset)[-0.8333]
            say(f"  stages 1-2 {m:6s}: one longitude per city covers {d[0]} of its Dhuhr windows, slack {d[1]:.3f} deg "
                f"(summed); horizon {hh:+.4f} (best plateau {pl[0]:+.3f}..{pl[-1]:+.3f}) covers {c_}/{n_rows} sunrise, "
                f"Dhuhr and sunset windows, the standard -0.8333 {standard}")
    if full:
        f, cov_f, plat_f = best_value(cities, model, days, p, "fajr", 19.0, 20.0, 0.05, 0.01,
                                      ("DHUHR", "SUNRISE", "MAGHRIB", "FAJR"), keep=FAJR_STATED)
        i, cov_i, plat_i = best_value(cities, model, days, p, "isha", 17.0, 18.0, 0.05, 0.01,
                                      ("DHUHR", "SUNRISE", "MAGHRIB", "ISHA"), keep=ISHA_QUOTED)
        p.update(fajr=f, isha=i)
        if say:
            say(f"  stage 3: Fajr {f} (best plateau {plat_f[0]}..{plat_f[-1]}, {cov_f} windows); "
                f"Isha {i} (best plateau {plat_i[0]}..{plat_i[-1]}, {cov_i} windows)")
    asr = {r: sum(cover(c.rows(model, days, {**p, "asr": r}), deep=False)[0] for c in cities) for r in ASR_RULES}
    p["asr"] = max(ASR_RULES, key=lambda r: (asr[r], r == "shadow"))
    if say:
        say("  stage 4: windows covered with each Asr rule: " + ", ".join(f"{r} {n}" for r, n in asr.items()))
    return p


def fit_points(cities, days, p) -> dict[str, tuple[float, float, int, int]]:
    out = {}
    for c in cities:
        rows = c.rows(p["model"], days, p)
        n, (x, y) = cover(rows)
        out[c.name] = (c.base[0] + x, c.base[1] + y, n, len(rows))
    return out


def leave_one_day_out(cities, recent, days, zone_ids, force: dict | None = None):
    """Each day scored (common.compute) with the profile and the points fitted on the other days."""
    scores = {c.name: common.Score() for c in cities}
    folds = []
    for hold in recent:
        train = [d for d in recent if d != hold]
        p = select_profile(cities, train, full=False, force=force)
        pts = fit_points(cities, train, p)
        method = method_of(p)
        for c in cities:
            if c.name not in days[hold] or (c.name, hold) in REVISED_2025:
                continue
            got = table(method, zone_ids[c.name], pts[c.name][0], pts[c.name][1], hold)
            for e, g, w in zip(EV, got, days[hold][c.name]):
                scores[c.name].add(e, hold, g, w)
        folds.append((hold, p["model"], p["horizon"], p["asr"]))
    return scores, folds


def relinearise(cities, points, p, suns, models=("MEEUS", "VSOP87")) -> None:
    for c in cities:
        c.base = points[c.name][:2]
        for m in models:
            c.linearise(m, {**p, "model": m}, suns)


# ------------------------------------------------------------------------------------------------ scoring

def method_of(p: dict) -> dict:
    horizon = {"type": "fixed", "altitudeDeg": p["horizon"]}
    return common.method_json(
        id=AUTHORITY, fajr={"type": "angle", "degrees": p["fajr"]}, isha={"type": "angle", "degrees": p["isha"]},
        sunModel=p["model"], horizon={"sunrise": horizon, "sunset": horizon},
        asrShadow=2 if p["asr"] == "shadow2" else 1, asrSun="APPARENT" if p["asr"] == "apparent" else "GEOMETRIC",
        sunEvaluation={"ASR": "LOCAL_MIDNIGHT_SHADOW"} if p["asr"] == "midnight" else {},
        evidence={"kind": "FROM_TABLES",
                  "source": "The Egyptian General Survey Authority's city tables (Fajr angle stated)"})


def table(method: dict, zone_id: str, lat: float, lng: float, day: datetime.date) -> list[int]:
    """The reference engine's six minutes (local clock) for a zone computed at (lat, lng)."""
    zone = common.zone_json(zone_id, lat, lng, authority=AUTHORITY)
    out = common.compute(common.case(method, lat, lng, TZ, day, zone=zone, country="EG"))
    origin = ref.epoch_days(day) * 86400 - common.utc_offset(TZ, day)
    minutes = []
    for e in EV:
        if out[e][2] != "COMPUTED":
            raise ValueError(f"{zone_id} {day} {e}: {out[e][2]}")
        minutes.append((out[e][1] - origin) // 60)
    return minutes


def merged(scores) -> common.Score:
    total = common.Score()
    for s in scores:
        for e in EV:
            total.exact[e] += s.exact[e]
            total.within1[e] += s.within1[e]
            total.total[e] += s.total[e]
            total.months[e] = [x + y for x, y in zip(total.months[e], s.months[e])]
        total.worst = max(total.worst, s.worst)
        total.misses += s.misses
    return total


def shares(score: common.Score) -> str:
    n = sum(score.total.values())
    ex = sum(score.exact.values())
    per = " ".join(f"{e[:3].lower()} {100 * score.exact[e] / score.total[e]:.1f}" for e in EV if score.total[e])
    within = 100 * sum(score.within1.values()) / n
    return f"{ex}/{n} exact ({100 * ex / n:.2f} %; {per}), within 1: {within:.2f} %, max {score.worst}"


def area_km(population: int | None) -> float:
    """A city's own area (NamedPlace.areaKm): the radius of a disc holding its population at DENSITY, 3 to 15 km."""
    if not population:
        return 3.0
    return min(15.0, max(3.0, round(2 * math.sqrt(population / (math.pi * DENSITY))) / 2))


def km(a, b) -> float:
    return math.hypot((a[0] - b[0]) * 111.2, (a[1] - b[1]) * 111.2 * math.cos(math.radians((a[0] + b[0]) / 2)))


# ------------------------------------------------------------------------------------------------ main

def start_point(name: str, obs: dict, suns: Suns) -> tuple[float, float]:
    """The town centre, or for a city GeoNames lacks, the Dhuhr longitude and the latitude its sunrise and sunset suit."""
    t = TOWNS[name]
    if t[1] is not None:
        return t[2], t[3]
    p = {"model": "MEEUS", "horizon": -0.8333, "fajr": FAJR_STATED, "isha": ISHA_QUOTED, "asr": "shadow"}
    lng = statistics.median(31.0 + (raw(27.0, 31.0, d, p, suns)["DHUHR"] - mins[2]) / 4.0 for d, mins in obs.items())

    def misfit(lat: float) -> float:
        total = 0.0
        for d, m in obs.items():
            t = raw(lat, lng, d, p, suns)
            total += (t["SUNRISE"] - m[1]) ** 2 + (t["MAGHRIB"] - m[4]) ** 2
        return total

    lat = min((22.0 + 0.25 * i for i in range(41)), key=misfit)
    lat = min((lat + 0.025 * i for i in range(-10, 11)), key=misfit)
    return lat, lng


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--wayback", type=Path, default=WAYBACK, help="folder of the Wayback copies of praytimes.aspx")
    args = parser.parse_args()
    started = time.time()
    say = print

    days, wayback_files = official_days(args.wayback)
    menu = city_menu()
    ramadan, august = reprints()
    recent = sorted(d for d in days if d >= POINTS_REVISED)
    if len(recent) < 4:
        say(f"Only {len(recent)} official day(s) of 2025-26 found (Wayback copies expected in {args.wayback}); "
            "a point per city needs several seasons. The asset is not written.")
        return 1
    zone_names = [n for n in menu[1:] if any(n in days[d] for d in recent)]
    missing = [n for n in menu[1:] if n not in zone_names]
    unknown = sorted({n for d in recent for n in days[d]} - set(zone_names))
    assert not unknown and all(n in TOWNS for n in zone_names), unknown
    say(f"Data: {len(days)} official days ({len(wayback_files)} Wayback copies + the 2026-10-08 page), {len(recent)} of "
        f"2025-26 with {sum(len(days[d]) for d in recent)} city-days; {len(zone_names)} zones; menu cities without a "
        f"row: {', '.join(missing)}")

    suns = Suns(min(days), max(days))
    obs = {n: {d: days[d][n] for d in recent if n in days[d] and (n, d) not in REVISED_2025} for n in zone_names}
    cities = [City(n, obs[n], start_point(n, obs[n], suns)) for n in zone_names]
    base = {"model": "MEEUS", "horizon": -0.8333, "fajr": FAJR_STATED, "isha": ISHA_QUOTED, "asr": "shadow"}
    for c in cities:                      # pass 1: points at the textbook profile, from the town centres
        for m in ("MEEUS", "VSOP87"):
            c.linearise(m, {**base, "model": m}, suns)
    relinearise(cities, fit_points(cities, recent, {**base, "model": "VSOP87"}), base, suns)
    say(f"Profile (all {len(recent)} days):")
    profile = select_profile(cities, recent, full=True, say=say)
    relinearise(cities, fit_points(cities, recent, profile), profile, suns)
    points = fit_points(cities, recent, profile)
    relinearise(cities, points, profile, suns)
    points = fit_points(cities, recent, profile)
    say(f"  profile: {profile}")

    # Stage 1's offset check: the points sit on the town centres only with Dhuhr = transit + 0, nearest minute.
    located = [n for n in zone_names if TOWNS[n][1] is not None]
    dlng = statistics.median(points[n][1] - TOWNS[n][3] for n in located)
    dlat = statistics.median(points[n][0] - TOWNS[n][2] for n in located)
    far = sorted(((km(points[n][:2], TOWNS[n][2:4]), TOWNS[n][0]) for n in located), reverse=True)
    say(f"  points against {len(located)} GeoNames town centres: median offset {dlng:+.4f} deg of longitude "
        f"({4 * dlng:+.3f} min; UP or DOWN rounding would need +-0.125 deg) and {dlat:+.4f} of latitude; "
        f"{sum(1 for d, _ in far if d <= 5)} within 5 km; farthest " + ", ".join(f"{t} {d:.0f} km" for d, t in far[:8]))
    in_sample = sum(v[2] for v in points.values())
    say(f"  in-sample windows covered at the points: {in_sample}/{sum(v[3] for v in points.values())}")

    # Held out: leave one day out (profile and points refitted on the other days), scored with the reference.
    zone_ids = {n: str(menu.index(n)) for n in zone_names}
    loo, folds = leave_one_day_out(cities, recent, days, zone_ids)
    say("Folds (held-out day: sun model, horizon, Asr rule): " + "; ".join(f"{d} {m} {h:+.4f} {a}" for d, m, h, a in folds))
    loo_all = merged(loo.values())
    say(f"Held out, leave one day out ({len(recent)} days, {len(zone_names)} zones): {shares(loo_all)}")
    for label, force in (("the standard horizon -0.8333", {"horizon": -0.8333}), ("the MEEUS sun", {"model": "MEEUS"})):
        alt, _ = leave_one_day_out(cities, recent, days, zone_ids, force)
        say(f"  the same with {label}: {shares(merged(alt.values()))}")

    final = method_of(profile)
    cairo = "القاهرة"
    ram_score = common.Score()
    for day, values in ramadan.items():
        for e, g, w in zip(EV, table(final, zone_ids[cairo], points[cairo][0], points[cairo][1], day), values):
            ram_score.add(e, day, g, w)
    say(f"Held out, Cairo's Ramadan 1445 imsakiya (Al-Dostor reprint, {len(ramadan)} days): {shares(ram_score)}")
    aug_day = datetime.date(2025, 8, 13)
    aug_by_city = {}
    for n, values in august[aug_day].items():
        aug_by_city[n] = common.Score()
        for e, g, w in zip(EV, table(final, zone_ids[n], points[n][0], points[n][1], aug_day), values):
            aug_by_city[n].add(e, aug_day, g, w)
    aug_score = merged(aug_by_city.values())
    say(f"Held out, 2025-08-13 table of six cities (Al-Dostor reprint): {shares(aug_score)}")

    # Quirk days: scored at the final points, counted apart.
    quirk = {n: common.Score() for n in zone_names}
    quirk_old = {n: common.Score() for n in zone_names}
    quirk_days = Counter()
    for day in sorted(days):
        for n, values in days[day].items():
            if n not in quirk or not (day < POINTS_REVISED or (n, day) in REVISED_2025):
                continue
            quirk_days[n] += 1
            for e, g, w in zip(EV, table(final, zone_ids[n], points[n][0], points[n][1], day), values):
                quirk[n].add(e, day, g, w)
                if day < POINTS_REVISED:
                    quirk_old[n].add(e, day, g, w)
    say(f"Quirk days at the final points ({sum(quirk_days.values())} city-days): {shares(merged(quirk.values()))}")
    for n, day in sorted(REVISED_2025, key=lambda x: x[0]):
        got = table(final, zone_ids[n], points[n][0], points[n][1], day)
        diffs = " ".join(f"{e[:3].lower()} {g - w:+d}" for e, g, w in zip(EV, got, days[day][n]))
        say(f"  {TOWNS[n][0]} on {day}: computed - published {diffs}")
    old = sorted(d for d in days if d < POINTS_REVISED)
    if old:
        moved = []
        for c in cities:
            o = {d: days[d][c.name] for d in old if c.name in days[d]}
            if len(o) < 2:
                continue
            probe = City(c.name, o, points[c.name][:2])
            probe.linearise(profile["model"], profile, suns)
            _, (x, _) = cover(probe.rows(profile["model"], old, profile))
            moved.append((x * 111.2, menu.index(c.name), c.name))
        north = [m for m in moved if m[0] > 5]
        median_north = statistics.median(m[0] for m in north) if north else 0.0
        others = ", ".join(f"{TOWNS[m[2]][0]} {m[0]:+.0f}" for m in moved if m[0] <= 5)
        say(f"  {', '.join(str(d) for d in old)}: the latitude those days alone allow lies more than 5 km north of the "
            f"current point for {len(north)} of {len(moved)} cities (median {median_north:.0f} km; menu positions "
            f"{sorted(m[1] for m in north)}); the others (km): {others}")
        say(f"  those days at the current points: the {len(north)} cities {shares(merged(quirk_old[m[2]] for m in north))}; "
            f"the {len(moved) - len(north)} others {shares(merged(quirk_old[m[2]] for m in moved if m[0] <= 5))}")

    # The asset.
    first, last = recent[0], recent[-1]
    zone_days = sum(len(o) for o in obs.values())
    loo_source = PAGE_SOURCE + "; leave one day out: each day scored with the profile and points fitted on the other days"
    zones = []
    for n in zone_names:
        lat, lng = points[n][:2]
        t = TOWNS[n]
        centre = (t[2], t[3]) if t[1] is not None else (lat, lng)
        n_days = len(obs[n])
        held = [common.sample(loo_source, first, last, n_days, 1)]
        score = [loo[n]]
        ramadan_days = 0
        if n == cairo:
            held.append(common.sample(RAMADAN_SOURCE, min(ramadan), max(ramadan), len(ramadan), 1))
            ramadan_days = len(ramadan)
            score.append(ram_score)
        if n in aug_by_city:
            held.append(common.sample(AUGUST_SOURCE, aug_day, aug_day, 1, 1))
            score.append(aug_by_city[n])
        s = merged(score)
        stats = common.fit_stats([common.sample(PAGE_SOURCE, first, last, n_days, 1)], held, s, ramadan_days, quirk_days[n])
        stats["maxDiffMin"] = max(s.worst, quirk[n].worst)
        # Plan 8.8: the zone's point is its town, which the mapping measures from; the fitted computing point goes
        # into the morning and evening points (E6, README S6), so every time is still computed there.
        town = common.point(*centre)
        computing = common.point(lat, lng)
        zones.append({
            "id": zone_ids[n], "nameAr": display_name(n), "point": town,
            "morningPoint": computing if computing != town else None,
            "eveningPoint": computing if computing != town else None,
            "namedPlaces": [{"centre": town, "catalogueIds": [], "areaKm": area_km(t[4])}],
            "fit": stats,
        })
    profile_score = merged([loo_all, ram_score, aug_score])
    profile_stats = common.fit_stats(
        [common.sample(PAGE_SOURCE, first, last, zone_days, len(zone_names))],
        [common.sample(loo_source, first, last, zone_days, len(zone_names)),
         common.sample(RAMADAN_SOURCE, min(ramadan), max(ramadan), len(ramadan), 1),
         common.sample(AUGUST_SOURCE, aug_day, aug_day, len(aug_by_city), len(aug_by_city))],
        profile_score, len(ramadan), sum(quirk_days.values()))
    profile_stats["maxDiffMin"] = max(profile_score.worst, max(q.worst for q in quirk.values()))
    path = common.write_asset(
        AUTHORITY, AUTHORITY, "EG", "NEAREST", zones, fit=profile_stats, named_first=True,
        source=f"The Egyptian General Survey Authority's all-cities daily table (esa.gov.eg/praytimes.aspx): {len(zones)} "
               f"cities, each computed at a point fitted on {len(recent)} days of {first.year}-{str(last.year)[2:]} "
               "(Wayback copies and the 2026-10-08 page)")
    say(f"Profile held out (leave one day out + reprints): {shares(profile_score)}")
    say(f"Wrote {path} ({len(zones)} zones) in {time.time() - started:.0f} s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
