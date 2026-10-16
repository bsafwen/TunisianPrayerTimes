#!/usr/bin/env python3
"""
Fits `jo_awqaf`, Jordan's Ministry of Awqaf, Islamic Affairs and Holy Places (plan 8.1, 8.2, 8.5, 8.6, 8.7, 8.8;
milestone AM2, 9.4), and writes its official-zone asset, android-app/app/src/main/assets/official-zones/jo_awqaf.json.

The ministry prepares its calendar with ICOP and states its structure (2025 calendar, pages 2-3): each large city
has a site in its east for Fajr and sunrise and a site in its west for the other prayers. Accurate Times, ICOP's
software and the ministry's official one (8.5), computes with VSOP87 and Delta T, refraction from a location's
pressure and temperature plus the geometric dip of its elevation on sunrise and sunset only, Fajr and Isha by angle
with no dip, and Fajr and sunrise "D km east" of the stored western point. The profile expresses this with E2
`Refraction`, E3, E6, E13 (APPARENT) and E14 (VSOP87); this script fits each zone's inputs.

The ministry's apparent-sun Asr is reproduced at standard air (1010 hPa, 10 C), not at loc.txt's air for the place
(stage 1: 97 more held-out minutes, Dhuhr and Asr above 99.7 %). A horizon in any air equals one in standard air
at a lower elevation, so every zone keeps standard air (no pressure or temperature stored) with its elevations
fitted in it; the printout gives each elevation's equivalent in loc.txt's air for comparison with Accurate Times.

Data, read at run time from test-data-local and never copied (D13, A7):
- arab-prototype/jo/cal2023.csv: the ministry's 2023 calendar (opendata.gov.jo, "Calendar 2023"), 15 zones x 365
  days, 12-hour clock (Asr, Maghrib and Isha take 12 hours), UTC+3 all year.
- research-2026-10-11/JO/cal2025_daily.txt where present (the 2025 calendar's daily pages, transcribed), else
  arab-prototype/jo/jo2025_sample.txt (31 zone-days of 1 January to 8 February 2025): held out, another year.
- research-2026-10-08/JO/jo/dl/page.html: the ministry's site on 2026-10-08, Amman's zone for 2026-10-08 to 10-17
  (12-hour clock): held out, another year.
- research-2026-10-08/JO/jo/ram1444.txt: the Ramadan 1444 imsakiya of Amman's zone (side check: no Ramadan rule).
- research-2026-10-11/JO/offsets2025.txt: the 2025 calendar's monthly half-minute offsets from Amman for its 18
  further places (pages 8-19).
- research-2026-10-11/HIJRI/icop_ghc_1428_1450.csv: Ramadan days (eastern zone), only to count held-out Ramadan days.
- loc.txt of Accurate Times (C:\\Accurate Times, or ACCURATE_TIMES_DIR), read in place: supportive evidence only
  (8.5). Its entries seed the searches (and their air the stage-1 comparison); every point, diameter and elevation in
  the asset is fitted to the ministry's tables.

Stages (9.4), each by windows (a published minute bounds the unrounded time to a half-open minute, the value most
windows cover wins), with a fast special-purpose model of the reference engine:
1. The sun model (E14), Asr's sun (E13) and the air: every zone refitted with VSOP87, MEEUS and TEXTBOOK, with the
   geometric and the apparent Asr, and in standard air or loc.txt's air; the joint count of all six times decides.
2. Per zone, the western (evening) point: its latitude on a grid, its longitude from the Dhuhr, Asr and Isha
   windows, then Maghrib's horizon: the evening elevation from the windows of the altitude less the semi-diameter,
   with a whole-minute Maghrib offset (E7) of -1 to +2 tried; the latitude with the most minutes wins.
3. Per zone, the eastern (morning) point, on the western point's latitude as Accurate Times' city diameter keeps
   it: its longitude from the Fajr windows (never west of the western point), jointly with the morning elevation
   from the sunrise windows, so that Fajr is not traded for sunrise (8.6); a whole-kilometre diameter where the
   windows allow one (loc.txt stores whole kilometres).
4. Rounding and angles: NEAREST against UP and DOWN (the same counts with every point 0.125 deg east or west, so the
   ministry's own western site for Amman, Sweileh at about 35.84 E, decides), and the twilight angle's common window
   at the fitted points (18 deg, stated in Abed 2015).
5. The 18 further places (`MONTHLY_DIFFERENCE`): each point, diameter and elevation fitted to the printed offsets,
   whose windows bound the place's own unrounded time on the 15th to Amman's plus [v - 0.25, v + 0.25); equally
   good solutions take the one nearest the place's loc.txt entry (its seed). Standard air, unless the entry's own
   air reproduces more offsets (a place at sea level in warm air has a horizon no elevation reaches in standard air).
Held out: each zone refitted on the odd months of 2023 and scored on the even ones, and the reverse (9.4, 9.5),
plus the 2025 and 2026 days; each further place refitted on the odd months' offsets and scored on the even months,
and the reverse, its times against Amman's printed minute plus the printed offset (either neighbouring minute
where the sum ends in a half-minute, 9.5). Every score comes from common.compute, the reference engine.

Usage: python scripts/prayer_formula/fits/jo_awqaf/fit.py [--daily-2025 PATH] [--workers N]
"""

from __future__ import annotations

import argparse
import csv
import datetime
import math
import os
import re
import sys
import unicodedata
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import common  # noqa: E402

ref = common.ref
EVENTS = common.EVENTS
AUTHORITY = "jo_awqaf"
TZ = "Asia/Amman"                       # UTC+3 all year since 2022-10-28 (checked on every row)
OFFSET_H = 3.0
D2R = math.pi / 180
EARTH_R_KM = 6378.137
JO_OLD = common.LOCAL_DATA / "research-2026-10-08" / "JO" / "jo"
JO_NEW = common.LOCAL_DATA / "research-2026-10-11" / "JO"
PROTO = common.LOCAL_DATA / "arab-prototype" / "jo"
GHC = common.LOCAL_DATA / "research-2026-10-11" / "HIJRI" / "icop_ghc_1428_1450.csv"
ACCURATE_TIMES = Path(os.environ.get("ACCURATE_TIMES_DIR", r"C:\Accurate Times"))
YEAR = 2023
MAGHRIB_MINUTES = (0, 1, -1, 2)         # E7 whole-minute Maghrib offsets tried, in order of preference on a tie
STANDARD_P, STANDARD_T = 1010.0, 10.0    # the engine's standard air (README S5), which the zones keep (stage 1)
SOURCE_LINE = ("Jordan's Ministry of Awqaf (awqaf.gov.jo, opendata.gov.jo): the calendar's 15 zones, each computed at a "
               "western and an eastern site (E6) with its own elevations, and its 18 further places as Amman's time "
               "plus a monthly offset derived on the phone (MONTHLY_DIFFERENCE), fitted to the 2023 calendar and the "
               "2025 offset pages")

# Towns, the zones' mapping points and named places (plan 8.8): GeoNames (geonames.org, read 2026-10-15) where an id
# is given, else approximate town coordinates (M5 matches the catalogue rows, 6.5). name -> (GeoNames id or None, lat, lng)
TOWNS = {
    "Amman": (250441, 31.9552, 35.9450), "Salt": (250258, 32.0392, 35.7272), "Zarqa": (250090, 32.0728, 36.0880),
    "Madaba": (248370, 31.7160, 35.7939), "Irbid": (248946, 32.5556, 35.8500),
    "Karak": (250624, 31.1825, 35.7000),        # GeoNames "Qir Moav", the old town (filed under Ma'an by GeoNames)
    "Tafila": (250198, 30.8375, 35.6044), "Maan": (248382, 30.1962, 35.7341), "Aqaba": (250774, 29.5267, 35.0078),
    "North Shuna": (None, 32.611, 35.609), "Jerash": (248875, 32.2808, 35.8993), "Ajloun": (250799, 32.3333, 35.7528),
    "Mafraq": (250582, 32.3429, 36.2080), "Umm al-Qutain": (None, 32.320, 36.630), "Dhiban": (None, 31.499, 35.782),
    "Azraq": (250095, 31.8821, 36.8302), "South Shuna": (None, 31.902, 35.620), "Shoubak": (None, 30.519, 35.536),
    "Petra": (246008, 30.3210, 35.4789), "Dulail": (10861986, 32.1208, 36.2801), "Hashimiya": (None, 32.122, 36.112),
    "Irhab": (None, 32.317, 36.083), "Umari": (None, 31.530, 37.070), "Safawi": (None, 32.198, 37.130),
    "Ruwaished": (11352362, 32.5041, 38.2011), "Karameh": (None, 32.740, 38.970), "Qatraneh": (None, 31.247, 36.046),
    "Hasa": (None, 30.820, 35.960), "Husseiniya": (None, 30.600, 35.780), "Ghor Safi": (250267, 31.0361, 35.4654),
    "Quraiqira": (None, 30.500, 35.340), "Gharandal": (None, 30.080, 35.210), "Rahma": (None, 29.920, 35.140),
    "Quweira": (None, 29.800, 35.310), "Rum": (247399, 29.5741, 35.4213), "Mudawwara": (None, 29.320, 36.000),
    "Jafr": (None, 30.317, 36.167), "Bayir": (None, 30.762, 36.679), "Jerusalem": (7303419, 31.7834, 35.2339),
}

# The calendar's 15 zones, with the site's ids (awqaf.gov.jo selector; Umm al-Qutain, which the 2026 site leaves out,
# is 10): id, nameAr, the calendar's own name (2023 file, 2025 pages), the loc.txt entry that seeds it and gives its
# pressure and temperature, GeoNames admin1 codes (JO.xx, geonames.org 2026-10-15) of the governorates it serves,
# and its named towns, the first being its mapping point (8.8).
ZONES = (
    ("1", "عمان، البلقاء، الزرقاء، مادبا", "عمان", "JORDAN Amman", ("16", "02", "17", "23"), ("Amman", "Salt", "Zarqa", "Madaba")),
    ("2", "إربد", "اربد", "JORDAN Irbid", ("18",), ("Irbid",)),
    ("3", "الكرك", "الكرك", "JORDAN Karak", ("09",), ("Karak",)),
    ("4", "الطفيلة", "الطفيلة", "JORDAN Tafeleh", ("12",), ("Tafila",)),
    ("5", "معان", "معان", "JORDAN Maan", ("19",), ("Maan",)),
    ("6", "العقبة", "العقبة", "JORDAN Aqaba", ("21",), ("Aqaba",)),
    ("7", "الأغوار الشمالية", "الأغوار الشمالية", "JORDAN NorthShouneh", ("18",), ("North Shuna",)),
    ("8", "جرش وعجلون", "جرش وعجلون", "JORDAN Jarash", ("22", "20"), ("Jerash", "Ajloun")),
    ("9", "المفرق", "المفرق", "JORDAN Mafraq", ("15",), ("Mafraq",)),
    ("10", "أم القطين", "أم القطين", "JORDAN Hmeadeyeh", ("15",), ("Umm al-Qutain",)),
    ("11", "ذيبان", "ذيبان", "JORDAN Madaba", ("23",), ("Dhiban",)),
    ("12", "الأزرق", "الأزرق", "JORDAN Azraq", ("17",), ("Azraq",)),
    ("13", "الأغوار الوسطى", "الأغوار الوسطى", "JORDAN SouthShouneh", ("02",), ("South Shuna",)),
    ("14", "الشوبك والبتراء", "الشوبك والبتراء", "JORDAN Shoubak-Wadi Mousa", ("19",), ("Shoubak", "Petra")),
    ("15", "الضليل والهاشمية", "الضليل والهاشمية", "JORDAN Mafraq", ("17",), ("Dulail", "Hashimiya")),
)
# Seeds of the western point where the zone's own town has no loc.txt entry (the prototype's fitted points, rounded).
SEED_POINTS = {"11": (31.49, 35.78), "15": (32.13, 36.14), "10": (32.25, 36.55)}

# The 18 further places of the 2025 calendar (pages 8-19), the site's ids 16-33 in printed order: id, nameAr as
# printed, the offsets file's key, the loc.txt entry that seeds it (None: the town and the nearest entry's air),
# the entry whose pressure and temperature it takes, its GeoNames admin1 codes (none for Jerusalem) and its town.
FURTHER = (
    ("16", "ارحاب", "irhab", None, "JORDAN Mafraq", ("15",), "Irhab"),
    ("17", "العمري", "umari", "JORDAN Omari", "JORDAN Omari", ("17",), "Umari"),
    ("18", "الصفاوي", "safawi", "JORDAN Safawi", "JORDAN Safawi", ("15",), "Safawi"),
    ("19", "الرويشد", "ruwaished", "JORDAN Rweashed", "JORDAN Rweashed", ("15",), "Ruwaished"),
    ("20", "حدود الكرامة", "karameh", None, "JORDAN Rweashed", ("15",), "Karameh"),
    ("21", "القطرانة", "qatraneh", "JORDAN Qatraneh", "JORDAN Qatraneh", ("09",), "Qatraneh"),
    ("22", "الحسا", "hasa", "JORDAN Hasa", "JORDAN Hasa", ("12",), "Hasa"),
    ("23", "الحسينية الجنوبية", "husseiniya", "JORDAN SouthHusayneyah", "JORDAN SouthHusayneyah", ("19",), "Husseiniya"),
    ("24", "غور الصافي", "ghorsafi", "JORDAN Safi", "JORDAN Safi", ("09",), "Ghor Safi"),
    ("25", "قريقرة", "quraiqira", None, "JORDAN Gharandal", ("21",), "Quraiqira"),
    ("26", "غرندل", "gharandal", "JORDAN Gharandal", "JORDAN Gharandal", ("21",), "Gharandal"),
    ("27", "رحمة", "rahma", "JORDAN Rahmah", "JORDAN Rahmah", ("21",), "Rahma"),
    ("28", "القويرة", "quweira", "JORDAN Qwearah", "JORDAN Qwearah", ("21",), "Quweira"),
    ("29", "رم", "rum", "JORDAN Rum", "JORDAN Rum", ("21",), "Rum"),
    ("30", "المدورة", "mudawwara", "JORDAN Mdawarah", "JORDAN Mdawarah", ("19",), "Mudawwara"),
    ("31", "الجفر", "jafr", "JORDAN Jafer", "JORDAN Jafer", ("19",), "Jafr"),
    ("32", "باير", "bayir", "JORDAN Bayer", "JORDAN Bayer", ("19",), "Bayir"),
    ("33", "القدس", "quds", "PALESTINE Al-Quds", "PALESTINE Al-Quds", (), "Jerusalem"),
)
JERUSALEM_ID = "33"
# The Jerusalem entry serves only a place in Jerusalem with jo_awqaf chosen (8.8): country PS and the app's
# Jerusalem-governorate radius (PlaceResolution.JERUSALEM_KM, 15 km around 31.7767 N 35.2345 E) as its reach. No admin2:
# GeoNames files the governorate as 7870654, and most places around the city carry none in cities1000.
JERUSALEM_REACH_KM = 15.0
# Jordan's Ghor tables (8.8, step 1): a place below sea level in Irbid, Balqa or Karak takes North Ghor, Central Ghor or
# Ghor Safi, the zone of its governorate with `belowSeaLevel` (ZoneMapping.attach, before the named towns).
BELOW_SEA_LEVEL = {"7", "13", "24"}


def norm(text: str) -> str:
    """Arabic names as printed, with presentation forms folded (the 2023 file writes «اﻷغوار» with U+FEF7)."""
    return unicodedata.normalize("NFKC", text).strip()


# ------------------------------------------------------------------------------------------------------------
# Data

def minute(h: int, m: int) -> int:
    return h * 60 + m


def load_2023() -> dict[str, dict[datetime.date, dict[str, int]]]:
    out: dict[str, dict[datetime.date, dict[str, int]]] = {}
    with open(PROTO / "cal2023.csv", encoding="utf-8", newline="") as fh:
        for r in csv.DictReader(fh):
            day = datetime.date.fromisoformat(r["date"])
            if common.utc_offset(TZ, day) != 10800:
                raise SystemExit(f"{day}: Asia/Amman is not UTC+3")
            row = {}
            for e, key in zip(EVENTS, ("fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha")):
                h, m = (int(x) for x in r[key].split(":"))
                if e in ("ASR", "MAGHRIB", "ISHA") or (e == "DHUHR" and h < 10):
                    h += 12
                row[e] = minute(h, m)
            out.setdefault(norm(r["city"]), {})[day] = row
    return out


def load_2025(path: Path) -> dict[str, dict[datetime.date, dict[str, int]]]:
    out: dict[str, dict[datetime.date, dict[str, int]]] = {}
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            if line.startswith("#") or not line.strip():
                continue
            zone, date, times = line.strip().split("|")
            day = datetime.date.fromisoformat(date)
            vals = [minute(*(int(x) for x in t.split(":"))) for t in times.split()]
            row = dict(zip(EVENTS, vals))
            old = out.setdefault(norm(zone), {}).get(day)
            if old is not None and old != row:
                raise SystemExit(f"{zone} {day}: two different rows in {path.name}")
            out[norm(zone)][day] = row
    return out


def load_2026() -> dict[datetime.date, dict[str, int]]:
    """Amman's zone on the ministry's site, 2026-10-08 to 10-17 (HH:MM:SS, 12-hour clock without AM/PM)."""
    text = (JO_OLD / "dl" / "page.html").read_text(encoding="utf-8", errors="replace")
    out = {}
    for m in re.finditer(r"(\d\d)/(\d\d)/(\d{4})((?:\s*<[^>]+>\s*|\s+)*\d\d:\d\d:\d\d){6}", text):
        day = datetime.date(int(m.group(3)), int(m.group(2)), int(m.group(1)))
        clocks = re.findall(r"(\d\d):(\d\d):\d\d", m.group(0))
        row = {}
        for e, (h, mm) in zip(EVENTS, clocks):
            h = int(h)
            if e in ("ASR", "MAGHRIB", "ISHA") or (e == "DHUHR" and h < 10):
                h += 12
            row[e] = minute(h, int(mm))
        out[day] = row
    return out


def load_ramadan_1444() -> dict[datetime.date, dict[str, int]]:
    """The Ramadan 1444 imsakiya of Amman's zone (one-page PDF's text): date, then first adhan and the six times."""
    text = (JO_OLD / "ram1444.txt").read_text(encoding="utf-8")
    out = {}
    for m in re.finditer(r"(\d\d)/(\d\d)/(\d\d)\s.*?(\d\.\d\d)\s+((?:\d\d:\d\d\s+){5}\d\d:\d\d)", text, re.S):
        day = datetime.date(2000 + int(m.group(1)), int(m.group(2)), int(m.group(3)))
        row = {}
        for e, t in zip(EVENTS, m.group(5).split()):
            h, mm = (int(x) for x in t.split(":"))
            if e in ("ASR", "MAGHRIB", "ISHA") or (e == "DHUHR" and h < 10):
                h += 12
            row[e] = minute(h, mm)
        out[day] = row
    return out


def load_offsets() -> dict[str, dict[int, dict[str, float]]]:
    out: dict[str, dict[int, dict[str, float]]] = {}
    with open(JO_NEW / "offsets2025.txt", encoding="utf-8") as fh:
        for line in fh:
            if line.startswith("#") or not line.strip():
                continue
            month, key, vals = line.strip().split("|")
            out.setdefault(key, {})[int(month)] = dict(zip(EVENTS, (float(x) for x in vals.split())))
    return out


def load_loc() -> dict[str, dict]:
    """Accurate Times' entries for Jordan and Palestine: point, elevation, diameter, pressure, temperature."""
    out = {}
    with open(ACCURATE_TIMES / "loc.txt", encoding="latin-1") as fh:
        for line in fh:
            parts = line.strip().split(",")
            name = parts[0].strip('"')
            if not (name.startswith("JORDAN") or name.startswith("PALESTINE")):
                continue
            v = [float(x) for x in parts[1:]]
            out[name] = {"lat": v[0] + v[1] / 60 + v[2] / 3600, "lng": v[3] + v[4] / 60 + v[5] / 3600, "elev": v[9],
                         "diam": v[10], "p": v[11], "t": v[12]}
    return out


def ramadan_test():
    """Whether a date is in Ramadan by ICOP's eastern zone (Jordan's month starts, 8.6; counting only)."""
    starts = {}
    with open(GHC, encoding="utf-8-sig", newline="") as fh:
        for r in csv.DictReader(fh):
            starts[(int(r["year"]), r["month"].strip())] = datetime.date.fromisoformat(r["east_start"])
    spans = [(s, starts[(y, "شوال")]) for (y, m), s in starts.items() if m == "رمضان" and (y, "شوال") in starts]
    return lambda day: any(a <= day < b for a, b in spans)


# ------------------------------------------------------------------------------------------------------------
# Fast model of the reference engine (README S1-S7) for the searches: the sun of a cycle date as a quadratic
# through the model at 0h, 12h and 24h UT (Jordan's cycle date is its civil date, step 1 of the solver).

class Sun:
    __slots__ = ("a", "b", "c")

    def __init__(self, model: str, day: datetime.date):
        jd0 = ref.julian_day0(day)
        f = ref.SUN_MODELS[model]
        y0, y1, y2 = f(jd0), f(jd0 + 0.5), f(jd0 + 1.0)
        self.a = y0
        self.b = tuple(-3 * u + 4 * v - w for u, v, w in zip(y0, y1, y2))
        self.c = tuple(2 * u - 4 * v + 2 * w for u, v, w in zip(y0, y1, y2))

    def at(self, hours: float) -> tuple[float, float, float]:
        x = hours / 24
        a, b, c = self.a, self.b, self.c
        return a[0] + x * (b[0] + x * c[0]), a[1] + x * (b[1] + x * c[1]), a[2] + x * (b[2] + x * c[2])


_SUNS: dict = {}


def sun_of(model: str, day: datetime.date) -> Sun:
    key = (model, day)
    s = _SUNS.get(key)
    if s is None:
        s = _SUNS[key] = Sun(model, day)
    return s


def transit(sun: Sun, lng: float) -> float:
    mean_noon = 12 - lng / 15
    t = mean_noon
    for _ in range(10):
        nxt = mean_noon - sun.at(t)[1] / 60
        if abs(nxt - t) < 0.1 / 3600:
            return nxt
        t = nxt
    return t


def event(sun: Sun, lat: float, lng: float, alt: float, side: int, t0: float) -> float:
    mean_noon = 12 - lng / 15
    sl, cl, sa = math.sin(lat * D2R), math.cos(lat * D2R), math.sin(alt * D2R)
    t = t0
    for _ in range(10):
        decl, eot, _ = sun.at(t)
        c = (sa - sl * math.sin(decl * D2R)) / (cl * math.cos(decl * D2R))
        nxt = mean_noon - eot / 60 + side * math.acos(max(-1.0, min(1.0, c))) / D2R / 15
        if abs(nxt - t) < 0.1 / 3600:
            return nxt
        t = nxt
    return t


def dip(h: float) -> float:
    return ref.dip_deg(h)


def bennett(a: float, p: float, temp: float) -> float:
    return ref.bennett(a, p, temp)


def a_prime(h: float, p: float, temp: float) -> float:
    """The horizon altitude less the semi-diameter (E2 Refraction): -(refraction at the dipped horizon + dip)."""
    d = dip(h)
    return -(bennett(-d, p, temp) + d)


def height_of(ap: float, p: float, temp: float) -> float:
    """The elevation whose a_prime is [ap] (monotonic); 0 above a_prime(0)."""
    if ap >= a_prime(0.0, p, temp):
        return 0.0
    lo, hi = 0.0, 6000.0
    for _ in range(60):
        mid = (lo + hi) / 2
        if a_prime(mid, p, temp) > ap:
            lo = mid
        else:
            hi = mid
    return (lo + hi) / 2


def asr_altitude(lat: float, decl_noon: float, p: float, temp: float, apparent: bool) -> float:
    zenith = abs(lat - decl_noon)
    if apparent:
        h0 = 90 - zenith
        h0a = h0 + bennett(h0, p, temp)
        aa = math.atan(1 / (1 + math.tan((90 - h0a) * D2R))) / D2R
        return aa - bennett(aa, p, temp)
    return math.atan(1 / (1 + math.tan(zenith * D2R))) / D2R


def east_of(lat: float, lng: float, km: float) -> float:
    return lng + km / (EARTH_R_KM * math.cos(lat * D2R)) / D2R


def km_east(lat: float, lng_w: float, lng_e: float) -> float:
    return (lng_e - lng_w) * D2R * EARTH_R_KM * math.cos(lat * D2R)


def times(z: dict, day: datetime.date, model: str = "VSOP87", apparent: bool = True) -> dict[str, float]:
    """Unrounded local minutes (UTC+3) of a zone z = {lat, lngw, lnge, he, hw, p, t, c}."""
    sun = sun_of(model, day)
    lat = z["lat"]
    tw, te = transit(sun, z["lngw"]), transit(sun, z["lnge"])
    rise = -(959.63 / 3600 / sun.at(te)[2]) + a_prime(z["he"], z["p"], z["t"])
    sets = -(959.63 / 3600 / sun.at(tw)[2]) + a_prime(z["hw"], z["p"], z["t"])
    out = {
        "FAJR": event(sun, lat, z["lnge"], -18.0, -1, te),
        "SUNRISE": event(sun, lat, z["lnge"], rise, -1, te),
        "DHUHR": tw,
        "ASR": event(sun, lat, z["lngw"], asr_altitude(lat, sun.at(tw)[0], z["p"], z["t"], apparent), 1, tw),
        "MAGHRIB": event(sun, lat, z["lngw"], sets, 1, tw),
        "ISHA": event(sun, lat, z["lngw"], -18.0, 1, tw),
    }
    res = {e: (v + OFFSET_H) * 60 for e, v in out.items()}
    res["MAGHRIB"] += z.get("c", 0)
    return res


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


def middle(stretches) -> float:
    a, b = stretches[(len(stretches) - 1) // 2]
    return (a + b) / 2


def nearest_in(stretches, target: float) -> float:
    best = None
    for a, b in stretches:
        x = min(max(target, a), b - 1e-9)
        if best is None or abs(x - target) < abs(best - target):
            best = x
    return best


def bounds(m: float, shift: float = 0.0) -> tuple[float, float]:
    """The unrounded minutes [lo, hi) a published minute allows under NEAREST (shift -0.5: UP, +0.5: DOWN)."""
    return m - 0.5 + shift, m + 0.5 + shift


def lng_windows(targets, e: str, lat: float, lng0: float, p: float, t: float, model: str, apparent: bool):
    """Longitudes that put event e (DHUHR, ASR, ISHA at the western point, FAJR at the eastern) in each target
    [lo, hi) of local minutes; an event moves 4 min per degree, the sun's own change being negligible."""
    out = []
    for day, lo, hi in targets:
        sun = sun_of(model, day)
        tr = transit(sun, lng0)
        if e == "DHUHR":
            th = tr
        elif e == "ASR":
            th = event(sun, lat, lng0, asr_altitude(lat, sun.at(tr)[0], p, t, apparent), 1, tr)
        elif e == "ISHA":
            th = event(sun, lat, lng0, -18.0, 1, tr)
        else:
            th = event(sun, lat, lng0, -18.0, -1, tr)
        um = (th + OFFSET_H) * 60
        out.append(((um - hi) / 4 + lng0, (um - lo) / 4 + lng0))
    return out


def altitude_windows(targets, e: str, lat: float, lng: float, model: str, adds=(0.0,), cap: float | None = None):
    """For sunrise (morning) or Maghrib (evening): the windows of the horizon altitude less the semi-diameter that
    put the event in each target [lo, hi) of local minutes, the target moved earlier by each of [adds] minutes (a
    zone's whole-minute offset); one list of windows per add. [cap]: the highest such altitude an elevation reaches in
    the zone's air, a_prime(0) (the dip is 0 at or below sea level); windows above it are cut."""
    out = [[] for _ in adds]
    sl, cl = math.sin(lat * D2R), math.cos(lat * D2R)
    mean_noon = 12 - lng / 15
    for day, lo, hi in targets:
        sun = sun_of(model, day)
        sdeg = 959.63 / 3600 / sun.at(transit(sun, lng))[2]
        for k, add in enumerate(adds):
            lo_h, hi_h = (lo - add) / 60 - OFFSET_H, (hi - add) / 60 - OFFSET_H
            decl, eot, _ = sun.at((lo_h + hi_h) / 2)
            sd, cd = math.sin(decl * D2R), math.cos(decl * D2R)
            a1, a2 = (math.asin(max(-1.0, min(1.0, sl * sd + cl * cd * math.cos((th - mean_noon + eot / 60) * 15 * D2R)))) / D2R
                      for th in (lo_h, hi_h))
            lo_a, hi_a = min(a1, a2) + sdeg, max(a1, a2) + sdeg
            if cap is not None:
                hi_a = min(hi_a, cap)
            if hi_a > lo_a:
                out[k].append((lo_a, hi_a))
    return out if len(adds) > 1 else out[0]


def targets_of(days, e: str, add: float = 0.0, shift: float = 0.0):
    return [(d, *bounds(row[e] - add, shift)) for d, row in days]


# ------------------------------------------------------------------------------------------------------------
# Zone fit (stages 2 and 3)

def evening_at(days, lat, lng0, p, t, model, apparent, shift, with_maghrib=True):
    wins = []
    for e in ("DHUHR", "ASR", "ISHA"):
        wins += lng_windows(targets_of(days, e, shift=shift), e, lat, lng0, p, t, model, apparent)
    n, stretches = cover(wins)
    lngw = middle(stretches)
    if not with_maghrib:
        return n, lngw, 0, None
    best = None
    for c, w in zip(MAGHRIB_MINUTES, altitude_windows(targets_of(days, "MAGHRIB", shift=shift), "MAGHRIB", lat, lngw, model,
                                                     MAGHRIB_MINUTES, a_prime(0.0, p, t))):
        mn, mst = cover(w)
        if best is None or mn > best[0]:
            best = (mn, c, mst)
    return n + best[0], lngw, best[1], best[2]


def fit_evening(days, seed_lat, seed_lng, p, t, model="VSOP87", apparent=True, shift=0.0):
    """The western point's latitude: located by Dhuhr, Asr and Isha on a coarse and a medium grid, then chosen with
    Maghrib (and its whole-minute offset) on a 0.001 deg grid; the middle of the best latitudes."""
    coarse = {la: evening_at(days, la, seed_lng, p, t, model, apparent, shift, False)
              for la in frange(seed_lat - 0.3, seed_lat + 0.3, 0.02)}
    lb = max(coarse, key=lambda k: coarse[k][0])
    medium = {la: evening_at(days, la, coarse[lb][1], p, t, model, apparent, shift, False)
              for la in frange(lb - 0.03, lb + 0.03, 0.005)}
    lm = max(medium, key=lambda k: medium[k][0])
    fine = {la: evening_at(days, la, medium[lm][1], p, t, model, apparent, shift)
            for la in frange(lm - 0.01, lm + 0.01, 0.001)}
    top = max(v[0] for v in fine.values())
    lats = sorted(k for k, v in fine.items() if v[0] == top)
    lat = lats[(len(lats) - 1) // 2]
    n, lngw, c, mst = fine[lat]
    return {"lat": lat, "lngw": lngw, "c": c, "hw": height_of(middle(mst), p, t), "lat_span": (lats[0], lats[-1]), "n": n}


def fit_morning(days, lat, lngw, p, t, model="VSOP87", shift=0.0):
    fw = lng_windows(targets_of(days, "FAJR", shift=shift), "FAJR", lat, lngw + 0.1, p, t, model, True)
    fw = [(max(a, lngw), b) for a, b in fw if b > lngw]       # never west of the western point
    n, stretches = cover(fw)
    cands = []
    for a, b in stretches:
        steps = max(1, int((b - a) / 0.0005))
        cands += [a + (b - a) * (i + 0.5) / steps for i in range(steps)]
    sunrise = targets_of(days, "SUNRISE", shift=shift)
    cap = a_prime(0.0, p, t)
    scored = [(cover(altitude_windows(sunrise, "SUNRISE", lat, x, model, cap=cap))[0], x) for x in cands]
    top = max(s for s, _ in scored)
    good = sorted(x for s, x in scored if s == top)
    lnge = good[(len(good) - 1) // 2]
    # A whole-kilometre diameter where the windows allow one (Accurate Times stores whole kilometres).
    mid_km = km_east(lat, lngw, lnge)
    for whole in sorted(range(0, 41), key=lambda v: abs(v - mid_km)):
        x = east_of(lat, lngw, whole)
        if not good[0] - 1e-9 <= x <= good[-1] + 1e-9:
            continue
        fx = sum(a <= x < b for a, b in fw)
        sx = cover(altitude_windows(sunrise, "SUNRISE", lat, x, model, cap=cap))[0]
        if fx == n and sx == top:
            lnge = x
            break
    sn, sst = cover(altitude_windows(sunrise, "SUNRISE", lat, lnge, model, cap=cap))
    return {"lnge": lnge, "he": height_of(middle(sst), p, t), "n": n + sn}


def frange(a: float, b: float, step: float) -> list[float]:
    n = int(round((b - a) / step))
    return [round(a + i * step, 6) for i in range(n + 1)]


def fit_zone(days, seed, p, t, model="VSOP87", apparent=True, shift=0.0) -> dict:
    ev = fit_evening(days, seed[0], seed[1], p, t, model, apparent, shift)
    mo = fit_morning(days, ev["lat"], ev["lngw"], p, t, model, shift)
    return {"lat": ev["lat"], "lngw": ev["lngw"], "lnge": mo["lnge"], "he": round(mo["he"]), "hw": round(ev["hw"]),
            "c": ev["c"], "p": p, "t": t, "lat_span": ev["lat_span"], "n": ev["n"] + mo["n"]}


def count(days, z, model="VSOP87", apparent=True, shift=0.0) -> dict[str, int]:
    out = dict.fromkeys(EVENTS, 0)
    for d, row in days:
        u = times(z, d, model, apparent)
        for e in EVENTS:
            out[e] += math.floor(u[e] + 0.5 - shift) == row[e]
    return out


def angle_windows(days, z, e: str, model="VSOP87"):
    """The twilight depressions that give the published minute at the zone's point (stage 4)."""
    lat, lng = z["lat"], (z["lnge"] if e == "FAJR" else z["lngw"])
    res = []
    for (day, lo_m, hi_m) in targets_of(days, e):
        sun = sun_of(model, day)
        lo_h, hi_h = lo_m / 60 - OFFSET_H, hi_m / 60 - OFFSET_H
        decl, eot, _ = sun.at((lo_h + hi_h) / 2)
        mean_noon = 12 - lng / 15
        sl, cl, sd, cd = math.sin(lat * D2R), math.cos(lat * D2R), math.sin(decl * D2R), math.cos(decl * D2R)

        def alt(th):
            return math.asin(sl * sd + cl * cd * math.cos((th - mean_noon + eot / 60) * 15 * D2R)) / D2R
        a1, a2 = alt(lo_h), alt(hi_h)
        res.append((-max(a1, a2), -min(a1, a2)))
    return res


# ------------------------------------------------------------------------------------------------------------
# Further places (stage 5)

def further_targets(key, months, offsets, amman, model="VSOP87"):
    """Per event, (15th of the month, lo, hi): the place's own unrounded minute on the 15th lies in Amman's plus
    [v - 0.25, v + 0.25) for the printed half-minute v (floor(2x + 0.5) / 2 = v)."""
    out = {e: [] for e in EVENTS}
    for m in months:
        day = datetime.date(2025, m, 15)
        base = times(amman, day, model)
        for e in EVENTS:
            v = offsets[key][m][e]
            out[e].append((day, base[e] + v - 0.25, base[e] + v + 0.25))
    return out


def fit_further(key, months, offsets, amman, seed, p, t, model="VSOP87") -> dict:
    """Point, diameter and elevations whose derived offsets reproduce the most printed ones; ties go to the
    solution nearest the seed (the place's loc.txt entry: lat, lng, elevation, diameter)."""
    slat, slng, selev, sdiam = seed
    tg = further_targets(key, months, offsets, amman, model)
    ap_seed = a_prime(selev, p, t)
    cap = a_prime(0.0, p, t)
    best = None
    for lat in frange(slat - 0.2, slat + 0.2, 0.005):
        wins = []
        for e in ("DHUHR", "ASR", "ISHA"):
            wins += lng_windows(tg[e], e, lat, slng, p, t, model, True)
        n, st = cover(wins)
        lngw = nearest_in(st, slng)
        mn, mst = cover(altitude_windows(tg["MAGHRIB"], "MAGHRIB", lat, lngw, model, cap=cap))
        hw = height_of(nearest_in(mst, ap_seed), p, t)
        fw = [(max(a, lngw), b) for a, b in lng_windows(tg["FAJR"], "FAJR", lat, slng, p, t, model, True) if b > lngw]
        fn, fst = cover(fw) if fw else (0, [(lngw, lngw + 1e-9)])
        lnge = nearest_in(fst, east_of(lat, lngw, sdiam))
        sn, sst = cover(altitude_windows(tg["SUNRISE"], "SUNRISE", lat, lnge, model, cap=cap))
        he = height_of(nearest_in(sst, ap_seed), p, t)
        k = EARTH_R_KM * D2R * math.cos(lat * D2R)
        dist = math.hypot((lat - slat) * EARTH_R_KM * D2R, (lngw - slng) * k)
        key2 = (n + mn + fn + sn, -dist)
        if best is None or key2 > best[0]:
            best = (key2, {"lat": lat, "lngw": lngw, "lnge": lnge, "he": round(he), "hw": round(hw), "c": 0, "p": p, "t": t,
                           "seed_km": dist, "n": n + mn + fn + sn})
    return best[1]


# ------------------------------------------------------------------------------------------------------------
# The reference engine, in parallel

def method_of() -> dict:
    return common.method_json(
        id=AUTHORITY, fajr={"type": "angle", "degrees": 18.0}, isha={"type": "angle", "degrees": 18.0},
        horizon={"sunrise": {"type": "refraction", "fallback": None}, "sunset": {"type": "refraction", "fallback": None}},
        asrSun="APPARENT", sunModel="VSOP87", rounding={e: "NEAREST" for e in EVENTS}, highLatitude={"type": "angle_based"},
        evidence={"kind": "FROM_TABLES", "source": "fit"})


def ref_zone(zid: str, z: dict, town: tuple[float, float], base_id: str | None = None) -> dict:
    """The zone as the asset and the reference read it: the town as its point, the fitted sites as E6 points."""
    extra = {"offsetRule": "MONTHLY_DIFFERENCE", "baseZoneId": base_id} if base_id else {}
    return common.zone_json(
        zid, town[0], town[1], morningPoint=common.point(z["lat"], z["lnge"]), eveningPoint=common.point(z["lat"], z["lngw"]),
        elevationM=int(z["he"]), eveningElevationM=int(z["hw"]), pressureHpa=stored_air(z)[0], temperatureC=stored_air(z)[1],
        offsets={"MAGHRIB": float(z["c"])} if z.get("c") else {}, **extra)


def stored_air(z: dict) -> tuple[float | None, float | None]:
    """The zone's pressure and temperature as stored: none in standard air (the engine's 1010 hPa and 10 C)."""
    if (z["p"], z["t"]) == (STANDARD_P, STANDARD_T):
        return None, None
    return float(z["p"]), float(z["t"])


def _run(batch):
    method, zones, items = batch
    out = []
    for key, zid, base_id, day in items:
        zone = zones[zid]
        lat, lng = zone["point"]["latE5"] / 1e5, zone["point"]["lngE5"] / 1e5
        got = common.compute(common.case(method, lat, lng, TZ, day, zone=zone, base_zone=zones.get(base_id) if base_id else None,
                                         country="JO"))
        off = common.utc_offset(TZ, day)
        out.append((key, {e: ((got[e][1] + off) // 60 % 1440, (got[e][0] + off) / 60 % 1440) for e in EVENTS}))
    return out


def run_reference(method, zones, items, workers: int) -> dict:
    """items: (key, zone id, base zone id or None, date); returns key -> event -> (local minute, unrounded)."""
    if not items:
        return {}
    if workers <= 1:
        return dict(_run((method, zones, items)))
    n = max(1, min(len(items), workers * 4))
    chunks = [(method, zones, items[i::n]) for i in range(n)]
    out = {}
    with ProcessPoolExecutor(max_workers=workers) as ex:
        for part in ex.map(_run, chunks):
            out.update(part)
    return out


def merge(scores) -> common.Score:
    out = common.Score()
    for s in scores:
        for e in EVENTS:
            out.exact[e] += s.exact[e]
            out.within1[e] += s.within1[e]
            out.total[e] += s.total[e]
            for m in range(12):
                out.months[e][m] += s.months[e][m]
        out.worst = max(out.worst, s.worst)
        out.misses += s.misses
    return out


def shares(score: common.Score) -> str:
    return " ".join(f"{e[:3]} {score.exact[e]}/{score.total[e]}" for e in EVENTS)


def pct(score: common.Score) -> str:
    return " ".join(f"{e[:3]} {100 * score.exact[e] / score.total[e]:.1f}" if score.total[e] else f"{e[:3]} -" for e in EVENTS)


# ------------------------------------------------------------------------------------------------------------

def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--daily-2025", type=Path, default=None,
                    help="the 2025 calendar's daily pages, transcribed (default: research-2026-10-11/JO/cal2025_daily.txt "
                         "where present, else arab-prototype/jo/jo2025_sample.txt)")
    ap.add_argument("--workers", type=int, default=min(16, os.cpu_count() or 1))
    args = ap.parse_args(argv)
    sys.stdout.reconfigure(encoding="utf-8")

    cal = load_2023()
    daily_path = args.daily_2025 or (JO_NEW / "cal2025_daily.txt")
    if not daily_path.exists():
        daily_path = PROTO / "jo2025_sample.txt"
    cal25 = load_2025(daily_path)
    cal26 = load_2026()
    offsets = load_offsets()
    loc = load_loc()
    in_ramadan = ramadan_test()
    names = {norm(z[2]) for z in ZONES}
    if set(cal) != names:
        raise SystemExit(f"zones differ: {sorted(set(cal) ^ names)}")
    print(f"2023: {sum(len(v) for v in cal.values())} zone-days; 2025 ({daily_path.name}): "
          f"{sum(len(v) for v in cal25.values())} zone-days; 2026: {len(cal26)} Amman days; offsets: {len(offsets)} places", flush=True)

    days23 = {zid: sorted(cal[norm(name)].items()) for zid, _, name, *_ in ZONES}
    seeds, loc_air = {}, {}
    for zid, _, _, entry, *_ in ZONES:
        e = loc[entry]
        seeds[zid] = (SEED_POINTS.get(zid, (e["lat"], e["lng"])), STANDARD_P, STANDARD_T)
        loc_air[zid] = (e["p"], e["t"])

    # Stages 2-3 on all of 2023: the asset's inputs.
    fits = {}
    for zid in days23:
        (slat, slng), p, t = seeds[zid]
        fits[zid] = fit_zone(days23[zid], (slat, slng), p, t)

    # Stage 1: sun model, Asr's sun and the air (every zone refitted for each alternative); the joint count decides.
    print("Stage 1, all 2023 minutes at each zone's refitted inputs:", flush=True)
    for label, model, apparent, air_kind in (("VSOP87, apparent Asr, standard air", "VSOP87", True, "standard"),
                                             ("VSOP87, apparent Asr, loc.txt air", "VSOP87", True, "loc"),
                                             ("MEEUS, apparent Asr, standard air", "MEEUS", True, "standard"),
                                             ("TEXTBOOK, apparent Asr, standard air", "TEXTBOOK", True, "standard"),
                                             ("VSOP87, geometric Asr", "VSOP87", False, "standard")):
        tot = dict.fromkeys(EVENTS, 0)
        for zid in days23:
            (slat, slng), p, t = seeds[zid]
            if air_kind == "loc":
                p, t = loc_air[zid]
            z = fits[zid] if label.startswith("VSOP87, apparent Asr, standard") else fit_zone(days23[zid], (slat, slng), p, t, model, apparent)
            for e, v in count(days23[zid], z, model, apparent).items():
                tot[e] += v
        print(f"  {label:38s} {sum(tot.values())} of {6 * 365 * len(days23)}: " + " ".join(f"{e[:3]} {v}" for e, v in tot.items()), flush=True)

    # Stage 4: rounding: UP and DOWN equal NEAREST with every point 0.125 deg (half a minute of time) further east or
    # west; their counts at the NEAREST points so moved, and where Amman's western site would then lie.
    print("Stage 4, rounding (joint count at the fitted points, moved for UP and DOWN):", flush=True)
    for label, shift in (("NEAREST", 0.0), ("UP", -0.5), ("DOWN", 0.5)):
        tot = 0
        for zid in days23:
            z = dict(fits[zid], lngw=fits[zid]["lngw"] - shift / 4, lnge=fits[zid]["lnge"] - shift / 4)
            tot += sum(count(days23[zid], z, shift=shift).values())
        print(f"  {label:8s} {tot} minutes, Amman's western site at {fits['1']['lngw'] - shift / 4:.3f} E", flush=True)
    wins = {e: [] for e in ("FAJR", "ISHA")}
    for zid in days23:
        for e in wins:
            wins[e] += angle_windows(days23[zid], fits[zid], e)
    for e in wins:
        n, st = cover(wins[e])
        print(f"  {e} angle at the fitted points: {n} of {len(wins[e])} windows at {st[0][0]:.3f}-{st[-1][1]:.3f} deg", flush=True)
    both_n, both_st = cover(wins["FAJR"] + wins["ISHA"])
    print(f"  both: {both_n} at {both_st[0][0]:.3f}-{both_st[-1][1]:.3f} deg (18 deg stated)", flush=True)

    # Month-split refits (held out).
    halves = {}
    for zid in days23:
        (slat, slng), p, t = seeds[zid]
        for keep in (1, 0):
            part = [x for x in days23[zid] if x[0].month % 2 == keep]
            halves[(zid, keep)] = fit_zone(part, (slat, slng), p, t)

    # Further places: Amman's full fit as the base.
    amman = fits["1"]
    further = {}
    further_halves = {}
    for fid, _, key, entry, air_entry, *_ in FURTHER:
        a = loc[air_entry]
        if entry is not None:
            e = loc[entry]
            lat0, lng0, elev, diam = e["lat"], e["lng"], e["elev"], e["diam"]
        else:
            town = TOWNS[next(f[6] for f in FURTHER if f[0] == fid)]
            lat0, lng0, elev, diam = town[1], town[2], a["elev"], 0.0
        # Standard air like the zones, unless the entry's own air reproduces more printed offsets: a place at or
        # below sea level in warm air (Ghor Safi) has a horizon no elevation reaches in standard air (dip 0 at h <= 0).
        # The seed's elevation is carried to standard air as the elevation with the same horizon.
        std_seed = (lat0, lng0, height_of(a_prime(max(elev, 0.0), a["p"], a["t"]), STANDARD_P, STANDARD_T), diam)
        loc_seed = (lat0, lng0, max(elev, 0.0), diam)

        def best_of(months):
            s_fit = fit_further(key, months, offsets, amman, std_seed, STANDARD_P, STANDARD_T)
            l_fit = fit_further(key, months, offsets, amman, loc_seed, a["p"], a["t"])
            return l_fit if l_fit["n"] > s_fit["n"] else s_fit
        further[fid] = best_of(list(range(1, 13)))
        for keep in (1, 0):
            further_halves[(fid, keep)] = best_of([m for m in range(1, 13) if m % 2 == keep])

    # ---------------- the reference engine
    method = method_of()
    zones_ref = {}
    for zid, _, _, _, _, towns in ZONES:
        town = TOWNS[towns[0]][1:]
        zones_ref[zid] = ref_zone(zid, fits[zid], town)
        for keep in (1, 0):
            zones_ref[f"{zid}/{keep}"] = ref_zone(zid, halves[(zid, keep)], town)
    for fid, *_, town_key in FURTHER:
        town = TOWNS[town_key][1:]
        zones_ref[fid] = ref_zone(fid, further[fid], town, "1")
        for keep in (1, 0):
            zones_ref[f"{fid}/{keep}"] = ref_zone(fid, further_halves[(fid, keep)], town, "1")

    items = []
    for zid, _, name, *_ in ZONES:
        for d, _ in days23[zid]:
            items.append((("23all", zid, d), zid, None, d))
            items.append((("23held", zid, d), f"{zid}/{1 if d.month % 2 == 0 else 0}", None, d))
        for d in sorted(cal25.get(norm(name), {})):
            items.append((("25", zid, d), zid, None, d))
    for d in sorted(cal26):
        items.append((("26", "1", d), "1", None, d))
    amman25 = cal25.get(norm(ZONES[0][2]), {})
    for fid, *_ in FURTHER:
        for d in sorted(amman25):
            items.append((("f25", fid, d), f"{fid}/{1 if d.month % 2 == 0 else 0}", "1", d))
            items.append((("f25all", fid, d), fid, "1", d))
        for m in range(1, 13):
            d = datetime.date(2025, m, 15)
            items.append((("foff", fid, m), f"{fid}/{1 if m % 2 == 0 else 0}", "1", d))
            items.append((("foffall", fid, m), fid, "1", d))
    for m in range(1, 13):
        items.append((("aoff", "1", m), "1", None, datetime.date(2025, m, 15)))
    print(f"Reference engine: {len(items)} cases on {args.workers} workers ...", flush=True)
    got = run_reference(method, zones_ref, items, args.workers)

    # ---------------- scores
    zone_scores = {}
    print("Zones (2023 fit: western site, diameter, elevations, Maghrib minutes; in-sample and held-out exact of 365):")
    all_in = common.Score()
    for zid, name_ar, name, *_ in ZONES:
        z = fits[zid]
        s_in, s_held, s25, s26 = common.Score(), common.Score(), common.Score(), common.Score()
        for d, row in days23[zid]:
            for e in EVENTS:
                s_in.add(e, d, got[("23all", zid, d)][e][0], row[e])
                s_held.add(e, d, got[("23held", zid, d)][e][0], row[e])
        for d, row in sorted(cal25.get(norm(name), {}).items()):
            for e in EVENTS:
                s25.add(e, d, got[("25", zid, d)][e][0], row[e])
        if zid == "1":
            for d, row in sorted(cal26.items()):
                for e in EVENTS:
                    s26.add(e, d, got[("26", "1", d)][e][0], row[e])
        zone_scores[zid] = (s_in, s_held, s25, s26)
        all_in = merge([all_in, s_in])
        h = (halves[(zid, 1)], halves[(zid, 0)])
        lp, lt = loc_air[zid]
        in_loc = [round(height_of(a_prime(h_, STANDARD_P, STANDARD_T), lp, lt)) for h_ in (z["he"], z["hw"])]
        print("  %-3s %-28s %.3f N %.4f E, %4.1f km, %4d/%4d m (%4d/%4d m in loc.txt air), Maghrib %+d | in %s | held %s | halves %.1f/%.1f km" % (
            zid, name_ar, z["lat"], z["lngw"], km_east(z["lat"], z["lngw"], z["lnge"]), z["he"], z["hw"], *in_loc, z["c"],
            " ".join(str(s_in.exact[e]) for e in EVENTS), " ".join(str(s_held.exact[e]) for e in EVENTS),
            km_east(h[0]["lat"], h[0]["lngw"], h[0]["lnge"]), km_east(h[1]["lat"], h[1]["lngw"], h[1]["lnge"])), flush=True)
    held_all = merge([zone_scores[z][1] for z in zone_scores])
    s25_all = merge([zone_scores[z][2] for z in zone_scores])
    s26_all = zone_scores["1"][3]
    print("2023 in-sample (all zones): " + pct(all_in))
    print("2023 month-split held out:  " + pct(held_all) + f"; within 1 min {min(held_all.within1_shares().values()):.2%}, worst {held_all.worst}")
    print(f"2025 held out ({sum(s25_all.total.values()) // 6} zone-days): " + shares(s25_all) + f"; worst {s25_all.worst}")
    print(f"2026 held out (Amman, {s26_all.total['FAJR']} days): " + shares(s26_all) + f"; worst {s26_all.worst}")
    ram = load_ramadan_1444()
    same = sum(cal[norm('عمان')].get(d) == row for d, row in ram.items())
    print(f"Ramadan 1444 imsakiya (Amman): {same} of {len(ram)} days equal the 2023 calendar (no Ramadan rule)")

    # Further places.
    print("Further places (point, diameter, elevations; printed offsets reproduced in-sample and held out, of 72):")
    further_scores = {}
    off_in = off_held = 0
    worst_off = {"in": 0.0, "held": 0.0}
    for fid, name_ar, key, *_ in FURTHER:
        z = further[fid]
        n_in = n_held = 0
        for m in range(1, 13):
            base = got[("aoff", "1", m)]
            for e in EVENTS:
                for tag, cnt in (("foffall", "in"), ("foff", "held")):
                    own = got[(tag, fid, m)][e][1]
                    v = math.floor(2 * (((own - base[e][1]) + 720) % 1440 - 720) + 0.5) / 2
                    hit = v == offsets[key][m][e]
                    worst_off[cnt] = max(worst_off[cnt], abs(v - offsets[key][m][e]))
                    if cnt == "in":
                        n_in += hit
                    else:
                        n_held += hit
        off_in += n_in
        off_held += n_held
        s = common.Score()
        for d in sorted(amman25):
            m = d.month
            for e in EVENTS:
                target = amman25[d][e] + offsets[key][m][e]
                mine = got[("f25", fid, d)][e][0]
                pub = math.floor(target) if abs(mine - math.floor(target)) <= abs(mine - math.ceil(target)) else math.ceil(target)
                s.add(e, d, mine, pub)
        further_scores[fid] = s
        print("  %-3s %-18s %.3f N %.4f E, %4.1f km, %4d/%4d m, %4.1f km from its seed | offsets %d / %d | 2025 times %s" % (
            fid, name_ar, z["lat"], z["lngw"], km_east(z["lat"], z["lngw"], z["lnge"]), z["he"], z["hw"], z["seed_km"],
            n_in, n_held, shares(s)), flush=True)
    print(f"Printed offsets: {off_in} of {72 * len(FURTHER)} in-sample, {off_held} held out (month-split); "
          f"largest difference {worst_off['in']} and {worst_off['held']} min")
    f_all = merge(list(further_scores.values()))
    print(f"Further places' 2025 times (Amman's printed minute + printed offset): " + shares(f_all) + f"; worst {f_all.worst}")

    # ---------------- FitStats and the asset
    first25 = min((d for v in cal25.values() for d in v), default=None)
    last25 = max((d for v in cal25.values() for d in v), default=None)
    src23 = "jo_ministry_2023_xls (opendata.gov.jo Calendar 2023)"
    src25 = f"jo_ministry_2025_pdf daily pages ({daily_path.name})"
    src26 = "awqaf.gov.jo prayer-time page, 2026-10-08 capture"
    zones_out = []
    for zid, name_ar, name, entry, admin1, towns in ZONES:
        s_in, s_held, s25, s26 = zone_scores[zid]
        n25 = s25.total["FAJR"]
        fitted_on = [common.sample(f"{src23}: {name_ar}", datetime.date(YEAR, 1, 1), datetime.date(YEAR, 12, 31), 365, 1)]
        held_out = [common.sample(f"{src23}: {name_ar}, refitted on odd months and scored on even months, and the reverse",
                                  datetime.date(YEAR, 1, 1), datetime.date(YEAR, 12, 31), 365, 1, month_split=True)]
        held_days = [d for d, _ in days23[zid]]
        if n25:
            d25 = sorted(cal25[norm(name)])
            held_out.append(common.sample(f"{src25}: {name_ar}", d25[0], d25[-1], n25, 1))
            held_days += d25
        if zid == "1":
            held_out.append(common.sample(f"{src26}: {name_ar}", min(cal26), max(cal26), len(cal26), 1))
            held_days += sorted(cal26)
        score = merge([s_held, s25, s26])
        z = fits[zid]
        town = TOWNS[towns[0]]
        zones_out.append({
            "id": zid, "nameAr": name_ar, "point": common.point(town[1], town[2]),
            "morningPoint": common.point(z["lat"], z["lnge"]), "eveningPoint": common.point(z["lat"], z["lngw"]),
            "elevationM": int(z["he"]), "eveningElevationM": int(z["hw"]),
            "pressureHpa": stored_air(z)[0], "temperatureC": stored_air(z)[1],
            "offsets": {"MAGHRIB": float(z["c"])} if z["c"] else {},
            "admin1": list(admin1),
            "belowSeaLevel": zid in BELOW_SEA_LEVEL,
            "namedPlaces": [{"centre": common.point(TOWNS[t][1], TOWNS[t][2]), "catalogueIds": [], "areaKm": 0.0} for t in towns],
            "fit": common.fit_stats(fitted_on, held_out, score, ramadan_days=sum(in_ramadan(d) for d in held_days)),
        })
    for fid, name_ar, key, entry, air_entry, admin, town_key in FURTHER:
        z = further[fid]
        s = further_scores[fid]
        town = TOWNS[town_key]
        held_days = sorted(amman25)
        fitted_on = [common.sample(f"jo_ministry_2025_pdf offset pages 8-19: {name_ar}", datetime.date(2025, 1, 15),
                                   datetime.date(2025, 12, 15), 12, 1),
                     common.sample(f"{src23}: Amman's zone (the base zone)", datetime.date(YEAR, 1, 1), datetime.date(YEAR, 12, 31), 365, 1)]
        held_out = []
        if held_days:
            held_out.append(common.sample(f"{src25}: Amman's printed minute plus {name_ar}'s printed offset, offsets derived at "
                                          f"points refitted on odd months and scored on even months, and the reverse",
                                          held_days[0], held_days[-1], len(held_days), 1, month_split=True))
        zone = {
            "id": fid, "nameAr": name_ar, "point": common.point(town[1], town[2]),
            "morningPoint": common.point(z["lat"], z["lnge"]), "eveningPoint": common.point(z["lat"], z["lngw"]),
            "elevationM": int(z["he"]), "eveningElevationM": int(z["hw"]),
            "pressureHpa": stored_air(z)[0], "temperatureC": stored_air(z)[1],
            "baseZoneId": "1", "offsetRule": "MONTHLY_DIFFERENCE",
            "namedPlaces": [{"centre": common.point(town[1], town[2]), "catalogueIds": [], "areaKm": 0.0}],
            "fit": common.fit_stats(fitted_on, held_out, s, ramadan_days=sum(in_ramadan(d) for d in held_days)),
        }
        if fid == JERUSALEM_ID:
            zone["country"] = "PS"
            zone["reachKm"] = JERUSALEM_REACH_KM
        else:
            zone["admin1"] = list(admin)
            zone["belowSeaLevel"] = fid in BELOW_SEA_LEVEL
        zones_out.append(zone)
    profile_score = merge([merge([zone_scores[z][1], zone_scores[z][2], zone_scores[z][3]]) for z in zone_scores]
                          + list(further_scores.values()))
    profile_fit = common.fit_stats(
        [common.sample(f"{src23}: the 15 zones", datetime.date(YEAR, 1, 1), datetime.date(YEAR, 12, 31), 365, 15),
         common.sample("jo_ministry_2025_pdf offset pages 8-19: the 18 further places", datetime.date(2025, 1, 15),
                       datetime.date(2025, 12, 15), 12, 18)],
        [common.sample(f"{src23}: the 15 zones, month-split", datetime.date(YEAR, 1, 1), datetime.date(YEAR, 12, 31), 365, 15, month_split=True)]
        + ([common.sample(f"{src25}: the zones", first25, last25, sum(len(v) for v in cal25.values()), len(cal25))] if first25 else [])
        + [common.sample(f"{src26}: Amman", min(cal26), max(cal26), len(cal26), 1)]
        + ([common.sample(f"{src25}: Amman plus the printed offsets of the 18 further places, month-split", min(amman25), max(amman25),
                          len(amman25), 18, month_split=True)] if amman25 else []),
        profile_score,
        ramadan_days=sum(in_ramadan(d) for zid in days23 for d, _ in days23[zid]))
    path = common.write_asset(AUTHORITY, AUTHORITY, "JO", "NEAREST", zones_out, fit=profile_fit, source=SOURCE_LINE,
                              by_admin1=True, named_first=True)
    tier = [zid for zid in zone_scores if all(v >= 0.97 for v in merge(list(zone_scores[zid][1:])).shares().values())
            and all(v == 1.0 for v in merge(list(zone_scores[zid][1:])).within1_shares().values())]
    print(f"Zones at or above 97 % exact on every time held out, all within 1 min: {len(tier)} of {len(zone_scores)} ({', '.join(tier)})")
    print(f"Wrote {path.relative_to(common.ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
