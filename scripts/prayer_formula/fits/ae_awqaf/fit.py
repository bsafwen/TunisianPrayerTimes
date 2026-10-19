#!/usr/bin/env python3
"""
Fits `ae_awqaf`, the UAE's General Authority of Islamic Affairs and Endowments (Awqaf), whose tables follow ICOP's
Accurate Times (plan 8.1, 8.2, 8.5, 8.6, 8.7, 8.8, Q13; milestone AM2, harness 9.4), and writes its official-zone
asset, android-app/app/src/main/assets/official-zones/ae_awqaf.json (schema 1).

Data, read at run time and never copied (D13, A7):
- test-data-local/research-2026-10-17/AE/awqaf_email_2026.csv: Awqaf's 2026 tables for nine locations, every day,
  which Awqaf emailed to the owner on 2026-10-17 on the condition that the app makes no money from them (README there):
  36 Abu Dhabi, 37 Al Ain, 57, 66 Ajman, 69 Dubai, 73 Fujairah, 82 Ras Al Khaimah, 91 Sharjah, 94 Umm Al Quwain. The
  fitted zones' rows; the website's copies below cross-check it. The file labels 57 "Al Ruways", but its fitted point
  lies 9 km from Madinat Zayed, which Awqaf's area list also gives location 57 (Al Ruways and Al Dhannah are 61).
- test-data-local/research-2026-10-08/AE/ae/ad36.txt, ain37.txt, dxb69.txt: Awqaf's 2026 tables for Abu Dhabi (36),
  Al Ain (37) and Dubai (69), every third day, from its website; aeskeptic/work/ad_official.txt (Abu Dhabi, every day
  of March, June, October and December 2026) and aeskeptic/work/dxb_oct.txt (Dubai, every day of October 2026),
  collected independently and equal to the first files on every shared day. They agree with the email on every shared
  day but one (Abu Dhabi's Maghrib of 2026-03-30, a minute later on the website), which the run reports.
- test-data-local/research-2026-10-11/AE/emirates_2026-10-11.json: Awqaf's area list (319 areas, 41 locations).
- test-data-local/research-2026-10-11/GEONAMES/cities1000.zip: town points for the zones and the named areas.
- C:/Accurate Times/loc.txt (ICOP's Accurate Times 5.7, the owner's install; supportive evidence only, 8.5): read in
  place, as search seeds for the nine fitted zones and, for the locations without a GeoNames town, the point of the
  entry that names the location; nothing else of it goes into the asset.

The plan's method (8.2, 8.7) is kept and checked by windows: 18/18, Dhuhr + 2, Asr factor 1 on the apparent sun (E13)
with the noon shadow taken at Dhuhr's published instant, transit + 2 min (E4 SHADOW_AT_DHUHR: Accurate Times' Asr
misses in June and July otherwise), every time to the nearest minute, VSOP87 (E14); sunrise and sunset on E2
`Refraction` with the zone's pressure and
temperature and its morning and evening elevations (fallback `Fixed(-1.4)` at exact coordinates); Fajr and sunrise at
the eastern point, the rest at the western point (E6; Accurate Times' "City Settings"), and Al Ain's sunrise at a
point of its own (E6 eventPoints).

Each fitted zone, by windows (a published minute bounds the unrounded time to a minute, so each day bounds the
parameter; the value most days allow wins):
1. the western longitude, from Dhuhr + 2 min (where some days' Dhuhr disagrees, as at Dubai, chosen jointly with
   stage 2 across Dhuhr's tolerance band);
2. the latitude (shared by both points, as Accurate Times' city diameter runs due east), from Asr, Isha and
   Maghrib, each at its conventional value (k = 1, 18 degrees, and Maghrib's best elevation);
3. the evening elevation, from Maghrib (pressure and temperature from the morning elevation by the climate law of
   Accurate Times' UAE entries: ISA lapse rate with 30 C at sea level, rounded as the entries store them);
4. the eastern longitude and the morning elevation jointly, from Fajr (18 degrees) and sunrise (9.4: Fajr is not
   traded for sunrise);
5. at the zones of OWN_SUNRISE (Al Ain), sunrise's own longitude on the zone's parallel and the morning elevation,
   jointly from sunrise, then the evening elevation again at the new pressure and temperature.
Dubai's table (69) is not reproduced: its times miss single minutes on scattered days of every month (tier B).
Held out: the one year in hand (2026), by month-split (fold A fits each zone on the odd months and scores the even
ones, fold B the reverse). The asset takes the values fitted on every day; its FitStats are the folds' scores.

The zones' values are fixed (FIRST_FIT): the stages above ran on 2026-10-18 on the emailed year, so a run reproduces
them and only rescores; --refit runs the stages again (for new tables).

The 32 other locations have no official table in hand (Awqaf's Open Data API registers UAE residents only, 9.3): 29
are written with provisional inputs, the town point with the fitted zones' median elevations and no eastern point,
and no fit record; CHECK reports how Accurate Times' own entries would do on the nine fitted tables (their parameters
stay out of the asset until a table confirms them, 8.5). The three locations no allowed source places (43, 74, 80) are
left out.

The search loops use an interpolated sun table fed to the reference engine's own solver functions; every score
printed and every FitStats comes from common.compute, the reference engine.

Usage: python scripts/prayer_formula/fits/ae_awqaf/fit.py [--refit]
"""

from __future__ import annotations

import argparse
import bisect
import csv
import datetime
import json
import math
import re
import statistics
import sys
import unicodedata
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import common  # noqa: E402

ref = common.ref
EVENTS = common.EVENTS
AUTHORITY = "ae_awqaf"
TZ = "Asia/Dubai"                         # +04 without summer time
AE = common.LOCAL_DATA / "research-2026-10-08" / "AE"
AREAS = common.LOCAL_DATA / "research-2026-10-11" / "AE" / "emirates_2026-10-11.json"
GEONAMES = common.LOCAL_DATA / "research-2026-10-11" / "GEONAMES" / "cities1000.zip"
LOC = Path("C:/Accurate Times/loc.txt")
EMAIL = common.LOCAL_DATA / "research-2026-10-17" / "AE" / "awqaf_email_2026.csv"
EMAIL_KEYS = {"FAJR": "fajr", "SUNRISE": "shuroq", "DHUHR": "dhuhr", "ASR": "asr", "MAGHRIB": "maghrib", "ISHA": "isha"}
SOURCE = "ae_awqaf_tables_2026"
SOURCE_LINE = ("The General Authority of Islamic Affairs and Endowments (Awqaf): its 41 locations' tables, computed with "
               "ICOP's Accurate Times; nine fitted on the 2026 tables Awqaf sent to the developer")
EVIDENCE = ("Awqaf's tables, computed with Accurate Times: 18/18, Dhuhr + 2, apparent Asr with the noon shadow at Dhuhr, "
            "per-zone points and elevations")
TABLES = {"36": (("ae/ad36.txt", "compact"), ("aeskeptic/work/ad_official.txt", "daily")),
          "37": (("ae/ain37.txt", "compact"),),
          "69": (("ae/dxb69.txt", "compact"), ("aeskeptic/work/dxb_oct.txt", "daily"))}
EMIRATES = {1: "01", 2: "03", 3: "06", 4: "02", 5: "07", 6: "05", 7: "04"}       # area list id -> GeoNames AE admin1
DHUHR_OFFSET_MIN = 2
FAJR_DEG = ISHA_DEG = 18.0
FALLBACK_DEG = -1.4
ASR_MODE = "SHADOW_AT_DHUHR"              # E4: Asr's noon shadow at Dhuhr's published instant, as Accurate Times takes it
FOLDS = (("A", (1, 3, 5, 7, 9, 11)), ("B", (2, 4, 6, 8, 10, 12)))
TOLERANCE = 0.0075
LAT_STEP = 0.0025
# E6 eventPoints: the zones whose sunrise takes a longitude of its own on the zone's parallel (stage 5). At Al Ain's
# shared eastern point sunrise misses 12 held-out days of 365 (96.7 %) and no single longitude and elevation serve
# both Fajr and sunrise; at its own longitude, 12 km further east with a lower morning elevation, it misses 5.
OWN_SUNRISE = ("37",)
# The locations without a table take the fitted zones' median elevations. True would write instead the elevation,
# point and city diameter of the Accurate Times entry that names the location: better where the entry holds (CHECK),
# but plan 8.5 ships its parameters only once an official table confirms them.
SHIP_ACCURATE_TIMES_INPUTS = False
# Each fitted zone's values from the refit of 2026-10-18 on Awqaf's emailed year (all days, and each month-split
# fold's own; Asr by SHADOW_AT_DHUHR): a run reproduces them and only rescores; --refit re-picks.
# (latitude of both points, western longitude, eastern longitude, morning elevation m, evening elevation m[, sunrise's
# own longitude, OWN_SUNRISE])
FIRST_FIT = {
    "36": {"all": (24.4625, 54.2996, 54.6444, 222, 223), "A": (24.4625, 54.2996, 54.6435, 222, 223),
           "B": (24.4625, 54.2994, 54.6444, 222, 223)},
    "37": {"all": (24.2125, 55.53, 56.071, 323, 282, 56.191), "A": (24.21, 55.53, 56.071, 352, 284, 56.1585),
           "B": (24.2125, 55.5299, 56.071, 293, 280, 56.226)},
    "57": {"all": (23.6575, 53.6133, 53.8581, 192, 194), "A": (23.655, 53.6116, 53.8586, 192, 193),
           "B": (23.6575, 53.6133, 53.8593, 192, 194)},
    "66": {"all": (25.415, 55.4321, 55.6262, 225, 224), "A": (25.4175, 55.4321, 55.6264, 225, 224),
           "B": (25.4175, 55.4343, 55.6238, 225, 223)},
    "69": {"all": (25.11, 55.0778, 55.5459, 222, 234), "A": (25.1, 55.0712, 55.5473, 225, 227),
           "B": (25.11, 55.0817, 55.5431, 216, 245)},
    "73": {"all": (25.1475, 56.2188, 56.3597, 240, 239), "A": (25.145, 56.2205, 56.3592, 239, 238),
           "B": (25.14, 56.2197, 56.3599, 242, 241)},
    "82": {"all": (25.7425, 55.8842, 56.1305, 224, 223), "A": (25.7475, 55.8839, 56.1285, 224, 225),
           "B": (25.7425, 55.8844, 56.131, 223, 223)},
    "91": {"all": (25.315, 55.3484, 55.6459, 221, 223), "A": (25.315, 55.3486, 55.6462, 221, 223),
           "B": (25.3175, 55.3476, 55.646, 222, 221)},
    "94": {"all": (25.5575, 55.537, 55.7832, 222, 223), "A": (25.555, 55.5359, 55.7852, 221, 222),
           "B": (25.5575, 55.537, 55.7833, 222, 224)},
}
EPOCH = datetime.date(1970, 1, 1)
# Awqaf's 41 locations: Arabic name (the location's principal area; Awqaf's own labels are not in hand), the town
# point (a cities1000 GeoNames id, a GeoNames point read from geonames.org on 2026-10-15, or the Accurate Times entry
# that names the location), and that entry where one does (provisional inputs, CHECK). None: no allowed source.
LOCATIONS = {
    "36": ("أبوظبي", "292968", "UAE-AD-Abu Dhabi"), "37": ("العين", "292913", "UAE-AD-Ain"),
    "39": ("حميم", "at", "UAE-AD-Hamim"), "40": ("الشهامة", "at", "UAE-AD-Shahamah"), "43": ("الظاهرة", None, None),
    "45": ("جزيرة داس", "at", "UAE-AD-Das"), "46": ("جزيرة دلما", "at", "UAE-AD-Dilma"), "47": ("الشويب", "at", "UAE-AD-Shweab"),
    "50": ("السلع", "at", "UAE-AD-Al Sala"), "51": ("القوع", "at", "UAE-AD-Gooa"), "52": ("جبل حفيت", "at", "UAE-AD-Hafit"),
    "54": ("الخزنة", ("292818", 24.16778, 55.11139), None),          # Al Khaznah, N 24 10 04 E 55 06 41
    "56": ("ليوا", "291279", "UAE-AD-Mzearaah"), "57": ("مدينة زايد", "291580", "UAE-AD-Madinat Zayed"),
    "58": ("المرفأ", "at", "UAE-AD-Marfa"), "61": ("الرويس", "292688", "UAE-AD-Ruweas"), "63": ("سويحان", "413256", "UAE-AD-Sweahan"),
    "65": ("جزيرة زركوه", "at", "UAE-AD-Zirkowa"), "66": ("عجمان", "292932", "UAE-AJM-Ajman"), "67": ("المنامة", "292799", None),
    "68": ("مصفوت", "291489", "UAE-AJM-Masfut"), "69": ("دبي", "292223", "UAE-DUB-Dubai"), "70": ("أرياف دبي", "13118439", None),
    "71": ("حتا", "292862", "UAE-DUB-Hatta"), "72": ("دبا", "292239", "UAE-SHJ-Diba"), "73": ("الفجيرة", "292878", "UAE-FUJ-Fujearah"),
    "74": ("الصرم", None, None), "75": ("مسافي", "at", "UAE-FUJ-Masafi"), "80": ("جبل جيس", None, None),
    "81": ("الحويلات", "at", "UAE-RAK-Hwealat"), "82": ("رأس الخيمة", "291074", "UAE-RAK-Rasel Kheamah"),
    "83": ("شعم", "292674", "UAE-RAK-Shaam"), "84": ("شوكة", "at", "UAE-RAK-Shoakah"), "85": ("طنب الكبرى", "at", "UAE-RAK-Tonb Alkubra"),
    "86": ("جزيرة أبوموسى", "400747", "UAE-SHJ-Abu Musa"), "87": ("الذيد", "292953", "UAE-SHJ-Dhead"),
    "88": ("الساحل الشرقي", "291696", "UAE-SHJ-Khour Fakkan"), "89": ("مليحة", "13117825", None),
    "91": ("الشارقة", "292672", "UAE-SHJ-Sharjah"), "92": ("جزيرة صير بو نعير", "at", "UAE-SHJ-Sir Bo Nair"),
    "94": ("أم القيوين", "290594", "UAE-UMQ-Ummel Quwean"),
}
# Areas whose GeoNames row has no Arabic name equal to Awqaf's (or another spelling): area -> GeoNames id, matched by
# the row's Latin name; every match still has to lie in the area's emirate and within 60 km of its location (8.8).
ALIASES = {
    "مدينة خليفة": "8057551", "بني ياس": "12042052", "الشامخة": "12047416", "مدينة محمد بن زايد": "13118447",
    "مدينة شخبوط": "8057552", "مصدر": "13118448", "ليوا": "291279", "الوقن": "12470174", "الهير": "12748680",
    "الخزنة": "292818", "إمارة عجمان": "292932", "المنامة": "292799", "مصفوت": "291489", "أبو هيل": "292991",
    "الوحيدة": "6691079", "الطوار": "6691083", "القصيص": "13118433", "الكرامة": "6691091", "الحضيبة": "6691093",
    "الوصل": "6691096", "نخلة جميرا": "6691113", "الورقاء": "8469658", "مدينة دبي العالمية": "8469668", "الصفا": "8469819",
    "المطينة": "13118421", "المزهر": "13118422", "الخوانيج": "13118436", "مرغم": "394142", "اللسيلي": "412981",
    "دبا الحصن": "292239", "خور فكان": "291696", "كلباء": "291763", "مربح": "291339", "شعم": "292674", "الرمس": "292692",
    "الجزيرة الحمراء": "291794", "جزيرة أبوموسى": "400747", "المجاز": "8476509", "حلوان": "13118437", "الصجعة": "13118438",
    "مليحة": "13117825", "الراعفة": "13118446",
}
EXTRA_GEONAMES = {"292818": (24.16778, 55.11139, "Al Khaznah")}   # geonames.org, 2026-10-15 (not in cities1000)
QUALIFIERS = ("أبوظبي", "دبي", "الشارقة", "عجمان", "أم القيوين", "الفجيرة", "رأس الخيمة")


def say(text: str) -> None:
    print(text, flush=True)


# --- Data ------------------------------------------------------------------------------------------------------

@dataclass
class Row:
    day: datetime.date
    published: dict[str, int]

    @property
    def cycle(self) -> ref.Cycle:
        return ref.Cycle(self.day)        # U = D at every Emirati point (S2)


def read_table(path: Path, kind: str) -> dict[datetime.date, dict[str, int]]:
    out = {}
    if kind == "compact":
        for chunk in path.read_text(encoding="utf-8").strip().split(";"):
            p = chunk.split()
            d = datetime.date(2026, int(p[0][:2]), int(p[0][2:]))
            out[d] = {e: common.local_to_epoch(TZ, d, f"{v[:2]}:{v[2:]}") for e, v in zip(EVENTS, p[1:7])}
    else:
        for line in path.read_text(encoding="utf-8").splitlines():
            p = line.split()
            if len(p) >= 7:
                d = datetime.date.fromisoformat(p[0])
                out[d] = {e: common.local_to_epoch(TZ, d, v) for e, v in zip(EVENTS, p[1:7])}
    return out


def email_tables() -> dict[str, dict[datetime.date, dict[str, int]]]:
    """Awqaf's emailed 2026 tables: zone -> date -> event -> epoch seconds (Imsak and the Hijri date left out)."""
    out: dict[str, dict[datetime.date, dict[str, int]]] = {}
    if not EMAIL.exists():
        return out
    with open(EMAIL, encoding="utf-8-sig", newline="") as fh:
        for r in csv.DictReader(fh):
            d = datetime.date.fromisoformat(r["date"])
            if d in out.setdefault(r["zone"], {}):
                raise SystemExit(f"{EMAIL.name}: zone {r['zone']} has {d} twice")
            out[r["zone"]][d] = {e: common.local_to_epoch(TZ, d, r[k]) for e, k in EMAIL_KEYS.items()}
    return out


def zone_rows(zone_id: str, email: dict | None = None) -> tuple[list[Row], str]:
    """A zone's rows: Awqaf's emailed year where it has the zone (the website's copies then only cross-check it,
    a differing day reported), else the website's tables."""
    if email and zone_id in email:
        table = email[zone_id]
        notes = [f"emailed table: {len(table)} days"]
        for rel, kind in TABLES.get(zone_id, ()):
            t = read_table(AE / rel, kind)
            shared = sorted(set(t) & set(table))
            differ = [f"{d} {e} {(table[d][e] - t[d][e]) // 60:+d}" for d in shared for e in EVENTS if t[d][e] != table[d][e]]
            notes.append(f"{rel}: {len(shared)} shared days, " + (f"differs: {', '.join(differ)}" if differ else "equal"))
        rows = [Row(d, table[d]) for d in sorted(table)]
        for r in rows:
            if [r.published[e] for e in EVENTS] != sorted(r.published[e] for e in EVENTS):
                raise SystemExit(f"zone {zone_id} {r.day}: times out of order")
        return rows, "; ".join(notes)
    merged: dict[datetime.date, dict[str, int]] = {}
    notes = []
    for rel, kind in TABLES[zone_id]:
        t = read_table(AE / rel, kind)
        shared = set(t) & set(merged)
        differ = [d for d in shared if t[d] != merged[d]]
        if merged:
            notes.append(f"{rel}: {len(t)} days, {len(shared)} shared, {len(differ)} differ")
        else:
            notes.append(f"{rel}: {len(t)} days")
        if differ:
            raise SystemExit(f"zone {zone_id}: {rel} differs on {differ[:3]}")
        merged.update(t)
    rows = [Row(d, merged[d]) for d in sorted(merged)]
    for r in rows:
        if [r.published[e] for e in EVENTS] != sorted(r.published[e] for e in EVENTS):
            raise SystemExit(f"zone {zone_id} {r.day}: times out of order")
    return rows, "; ".join(notes)


def accurate_times() -> dict[str, dict]:
    out = {}
    with open(LOC, encoding="latin-1", newline="") as fh:
        for r in csv.reader(fh):
            if len(r) < 14 or not r[0].startswith("UAE-"):
                continue
            lat = (int(r[1]) + int(r[2]) / 60 + int(r[3]) / 3600) * (1 if r[7] == "1" else -1)
            lng = (int(r[4]) + int(r[5]) / 60 + int(r[6]) / 3600) * (1 if r[8] == "1" else -1)
            out[r[0]] = {"lat": lat, "lng": lng, "elev": float(r[10]), "diam": float(r[11])}
    return out


@dataclass
class Place:
    gid: str
    lat: float
    lng: float
    name: str
    admin1: str
    arabic: set[str] = field(default_factory=set)


def norm(s: str) -> str:
    s = "".join(c for c in unicodedata.normalize("NFKC", s) if not ("\u064b" <= c <= "\u065f" or c in "\u0670\u0640"))
    s = re.sub("[أإآٱ]", "ا", s).replace("ى", "ي").replace("ة", "ه").replace("ؤ", "و").replace("ئ", "ي")
    return re.sub(r"\s+", " ", s).strip()


def geonames_ae() -> dict[str, Place]:
    out = {}
    with zipfile.ZipFile(GEONAMES) as z, z.open("cities1000.txt") as fh:
        for line in fh:
            v = line.decode("utf-8").rstrip("\n").split("\t")
            if v[8] == "AE":
                arabic = {norm(a) for a in v[3].split(",") if re.search("[\u0600-\u06ff]", a)}
                out[v[0]] = Place(v[0], float(v[4]), float(v[5]), v[1], v[10], arabic)
    for gid, (lat, lng, name) in EXTRA_GEONAMES.items():
        out[gid] = Place(gid, lat, lng, name, "01")
    return out


def km(a: tuple[float, float], b: tuple[float, float]) -> float:
    p = math.pi / 180
    h = math.sin((b[0] - a[0]) * p / 2) ** 2 + math.cos(a[0] * p) * math.cos(b[0] * p) * math.sin((b[1] - a[1]) * p / 2) ** 2
    return 12742 * math.asin(math.sqrt(h))


# --- The fast sun and the windows --------------------------------------------------------------------------------

class TableSun:
    """The model's sun on a 30-minute grid over 2026, linearly interpolated (only the search loops use it)."""

    def __init__(self, model: str, first: datetime.date, last: datetime.date, step: float = 1 / 48):
        self.jd0 = ref.julian_day0(first) - 1
        self.step = step
        f = ref.SUN_MODELS[model]
        self.values = [f(self.jd0 + i * step) for i in range(int((ref.julian_day0(last) + 2 - self.jd0) / step) + 3)]

    def __call__(self, jd: float):
        x = (jd - self.jd0) / self.step
        i = int(x)
        w = x - i
        a, b = self.values[i], self.values[i + 1]
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


def widest_middle(spans: list[tuple[float, float]]) -> float:
    lo, hi = max(spans, key=lambda s: s[1] - s[0])
    return (lo + hi) / 2


def climate(h: float) -> tuple[float, float]:
    """Accurate Times' UAE entries: pressure and temperature from the elevation (ISA lapse rate, 30 C at sea level),
    as whole hPa and C."""
    return float(round(1013.25 * (1 - 0.0065 * h / 303.15) ** 5.255)), float(round(30 - 0.0065 * h))


class Horizon:
    """E2 Refraction: the altitude of elevation h with pressure p and temperature t, at the sun's distance r (S5);
    inverted through a table at r = 1 (the semi-diameter's share is exact)."""

    def __init__(self, p: float, t: float):
        self.p, self.t = p, t
        self.hs = [0.5 * i for i in range(0, 4001)]                     # 0 .. 2000 m
        self.alts = [self.altitude(h, 1.0) for h in self.hs]            # decreasing
        self.neg = [-a for a in self.alts]                              # increasing, for bisect

    def altitude(self, h: float, r: float) -> float:
        dip = ref.dip_deg(h)
        return -(959.63 / 3600 / r + ref.bennett(-dip, self.p, self.t) + dip)

    def elevation(self, a: float, r: float) -> float:
        """the h whose altitude at distance r is a (-inf above the h = 0 horizon, +inf below 2000 m)"""
        a1 = a + 959.63 / 3600 * (1 / r - 1)                            # the same altitude at r = 1
        if a1 > self.alts[0]:
            return -math.inf
        if a1 <= self.alts[-1]:
            return math.inf
        i = bisect.bisect_left(self.neg, -a1)
        x0, x1, y0, y1 = self.hs[i - 1], self.hs[i], self.alts[i - 1], self.alts[i]
        return x0 + (a1 - y0) * (x1 - x0) / (y1 - y0)


def asr_altitude(lat: float, decl: float, p: float, t: float) -> float:
    """S7, factor 1 on the apparent sun, the noon shadow at the transit (E4 EVENT_INSTANT; reported only)."""
    zen = abs(lat - decl)
    h0 = 90 - zen
    h0_app = h0 + ref.bennett(h0, p, t)
    a_app = ref.atand(1 / (1 + ref.tand(90 - h0_app)))
    return a_app - ref.bennett(a_app, p, t)


def asr_altitude_at_dhuhr(cy, sun, lat: float, lng: float, t0: float, p: float, t: float) -> float:
    """S7 with E4 SHADOW_AT_DHUHR, as shipped: the noon altitude is the sun's at Dhuhr's published instant, transit +
    2 min, refracted on the apparent sun (E13); factor 1."""
    h0 = ref.altitude(cy, sun, lat, lng, t0 + DHUHR_OFFSET_MIN / 60)
    h0_app = h0 + ref.bennett(h0, p, t)
    a_app = ref.atand(1 / (1 + ref.tand(90 - h0_app)))
    return a_app - ref.bennett(a_app, p, t)


@dataclass
class ZoneFit:
    lat: float
    lng_w: float
    lng_m: float
    h_m: float
    h_e: float
    p: float
    t: float
    counts: dict
    lng_rise: float | None = None         # sunrise's own longitude on the zone's parallel (OWN_SUNRISE)

    def east_km(self) -> float:
        return km((self.lat, self.lng_w), (self.lat, self.lng_m))


def fit_zone(rows: list[Row], sun, seed: dict) -> ZoneFit:
    """Stages 1-4 on the given rows (all NEAREST)."""
    n = len(rows)
    # 1. Western longitude from Dhuhr + 2 min: the transit at lng is the transit at 54 E less 240 s per degree.
    ws = []
    for row in rows:
        cy = row.cycle
        t54 = cy.instant(ref.transit(cy, sun, 54.0)) + 60 * DHUHR_OFFSET_MIN
        p = row.published["DHUHR"]
        ws.append((54.0 + (t54 - (p + 30)) / 240, 54.0 + (t54 - (p - 30)) / 240))
    dh_best, dh_spans = sweep(ws)
    # Where every day's Dhuhr agrees, their common span fixes the longitude (its widest span's middle); where some
    # days disagree (Dubai: 138 of 142), the longitude is chosen jointly with stage 2 across Dhuhr's tolerance band.
    if dh_best == n:
        lng_candidates = [round(widest_middle(dh_spans), 4)]
    else:
        dh_band = _spans_from(ws, dh_best - math.ceil(TOLERANCE * n))
        lng_candidates = sorted({round(lo + (hi - lo) * j / 4, 4) for lo, hi in dh_band for j in range(5)})
    h_m = seed["elev"]
    lat = round(seed["lat"] / LAT_STEP) * LAT_STEP
    for _ in range(2):                    # pressure and temperature follow the morning elevation
        p, t = climate(h_m)
        hz = Horizon(p, t)
        # 2. Latitude from Asr (k = 1, apparent, shadow at Dhuhr), Isha (18) and Maghrib (its best elevation), at each candidate
        # western longitude: a scan of +-0.06 degree that moves until its best latitude is not at an edge (the seed
        # may be off by more). The pair with the most windows, Dhuhr's included, wins.
        scanned: dict[tuple[float, float], tuple] = {}

        def at_point(lng: float, la: float) -> tuple:
            asr = isha = 0
            mag = []
            for row in rows:
                cy = row.cycle
                t0 = ref.transit(cy, sun, lng)
                r = sun(cy.jd0 + t0 / 24)[2]
                ta = cy.instant(ref.event(cy, sun, la, lng, asr_altitude_at_dhuhr(cy, sun, la, lng, t0, p, t), 1, t0))
                asr += row.published["ASR"] - 30 <= ta < row.published["ASR"] + 30
                ti = cy.instant(ref.event(cy, sun, la, lng, -ISHA_DEG, 1, t0))
                isha += row.published["ISHA"] - 30 <= ti < row.published["ISHA"] + 30
                e1 = hz.elevation(ref.altitude(cy, sun, la, lng, cy.hours(row.published["MAGHRIB"] - 30)), r)
                e2 = hz.elevation(ref.altitude(cy, sun, la, lng, cy.hours(row.published["MAGHRIB"] + 30)), r)
                mag.append((min(e1, e2), max(e1, e2)))
            m_best, m_spans = sweep(mag)
            dh = sum(lo <= lng < hi for lo, hi in ws)
            return dh + asr + isha + m_best, -abs(la - seed["lat"]), -abs(lng - widest_middle(dh_spans)), la, lng, asr, isha, m_best, m_spans

        for lng in lng_candidates:
            centre = lat
            for _ in range(8):
                grid = [round(centre + k * LAT_STEP, 4) for k in range(-24, 25)]
                for la in grid:
                    if (lng, la) not in scanned:
                        scanned[(lng, la)] = at_point(lng, la)
                top = max(scanned[(lng, la)] for la in grid)
                if grid[2] <= top[3] <= grid[-3]:
                    break
                centre = top[3]
        _, _, _, lat_new, lng_w, asr_n, isha_n, mag_n, m_spans = max(scanned.values())
        # 3. Evening elevation: the whole metre nearest the middle of Maghrib's widest best span.
        h_e = float(round(widest_middle(m_spans)))
        # 4. Eastern longitude and morning elevation jointly: Fajr 18 at the latitude, then sunrise's elevation.
        fw = []
        for row in rows:
            cy = row.cycle
            t0 = ref.transit(cy, sun, 55.0)
            tf = cy.instant(ref.event(cy, sun, lat_new, 55.0, -FAJR_DEG, -1, t0))
            p_ = row.published["FAJR"]
            fw.append((55.0 + (tf - (p_ + 30)) / 240, 55.0 + (tf - (p_ - 30)) / 240))
        f_best, _ = sweep(fw)
        band = [s for s in _spans_from(fw, f_best - math.ceil(TOLERANCE * n))]
        candidates = sorted({round(lo + (hi - lo) * j / 8, 4) for lo, hi in band for j in range(9)})
        best = None
        for lng_m in candidates:
            fajr = sum(lo <= lng_m < hi for lo, hi in fw)
            rise = []
            for row in rows:
                cy = row.cycle
                t0 = ref.transit(cy, sun, lng_m)
                r = sun(cy.jd0 + t0 / 24)[2]
                e1 = hz.elevation(ref.altitude(cy, sun, lat_new, lng_m, cy.hours(row.published["SUNRISE"] - 30)), r)
                e2 = hz.elevation(ref.altitude(cy, sun, lat_new, lng_m, cy.hours(row.published["SUNRISE"] + 30)), r)
                rise.append((min(e1, e2), max(e1, e2)))
            r_best, r_spans = sweep(rise)
            key = (fajr + r_best, fajr, -abs(lng_m - widest_middle(band)))
            if best is None or key > best[0]:
                best = (key, lng_m, fajr, r_best, r_spans)
        _, lng_m, fajr_n, rise_n, r_spans = best
        h_m_new = float(round(widest_middle(r_spans)))
        converged = h_m_new == h_m and lat_new == lat
        lat, h_m = lat_new, h_m_new
        if converged:
            break
    p, t = climate(h_m)
    dh_n = sum(lo <= lng_w < hi for lo, hi in ws)
    return ZoneFit(lat, lng_w, lng_m, h_m, h_e, p, t,
                   {"DHUHR": dh_n, "ASR": asr_n, "ISHA": isha_n, "MAGHRIB": mag_n, "FAJR": fajr_n, "SUNRISE": rise_n, "n": n})


def own_sunrise(rows: list[Row], sun, f: ZoneFit) -> ZoneFit:
    """Stage 5 (OWN_SUNRISE): sunrise's own longitude on the zone's parallel and the morning elevation, jointly from
    sunrise's elevation windows (the most days, then the longitude nearest Fajr's; 0.01 degree steps within 0.3 of
    it, then 0.0025 within 0.06 of the best), pressure and temperature following the elevation; then the evening
    elevation again from Maghrib at the western point, at the new pressure and temperature."""
    h_m, h_e, mag_n = f.h_m, f.h_e, f.counts.get("MAGHRIB")
    for _ in range(3):
        p, t = climate(h_m)
        hz = Horizon(p, t)
        best = None
        centre = f.lng_m
        for span, step in ((0.3, 0.01), (0.06, 0.0025)):
            for j in range(int(round(2 * span / step)) + 1):
                lng = round(centre - span + step * j, 4)
                rise = []
                for row in rows:
                    cy = row.cycle
                    r = sun(cy.jd0 + ref.transit(cy, sun, lng) / 24)[2]
                    e1 = hz.elevation(ref.altitude(cy, sun, f.lat, lng, cy.hours(row.published["SUNRISE"] - 30)), r)
                    e2 = hz.elevation(ref.altitude(cy, sun, f.lat, lng, cy.hours(row.published["SUNRISE"] + 30)), r)
                    rise.append((min(e1, e2), max(e1, e2)))
                n, spans = sweep(rise)
                key = (n, -abs(lng - f.lng_m))
                if best is None or key > best[0]:
                    best = (key, lng, widest_middle(spans))
            centre = best[1]
        h_new = float(round(best[2]))
        p, t = climate(h_new)
        hz = Horizon(p, t)
        mag = []
        for row in rows:
            cy = row.cycle
            r = sun(cy.jd0 + ref.transit(cy, sun, f.lng_w) / 24)[2]
            e1 = hz.elevation(ref.altitude(cy, sun, f.lat, f.lng_w, cy.hours(row.published["MAGHRIB"] - 30)), r)
            e2 = hz.elevation(ref.altitude(cy, sun, f.lat, f.lng_w, cy.hours(row.published["MAGHRIB"] + 30)), r)
            mag.append((min(e1, e2), max(e1, e2)))
        mag_n, m_spans = sweep(mag)
        h_e = float(round(widest_middle(m_spans)))
        converged = h_new == h_m
        h_m = h_new
        if converged:
            break
    p, t = climate(h_m)
    return ZoneFit(f.lat, f.lng_w, f.lng_m, h_m, h_e, p, t, dict(f.counts, SUNRISE=best[0][0], MAGHRIB=mag_n), best[1])


def fixed_zone(v: tuple) -> ZoneFit:
    """FIRST_FIT's values as a ZoneFit (pressure and temperature from the morning elevation, as fitted)."""
    lat, lng_w, lng_m, h_m, h_e = v[:5]
    p, t = climate(h_m)
    return ZoneFit(lat, lng_w, lng_m, float(h_m), float(h_e), p, t, {}, v[5] if len(v) > 5 else None)


def _spans_from(windows: list[tuple[float, float]], threshold: int) -> list[tuple[float, float]]:
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


# --- The method, the zones and the scores ------------------------------------------------------------------------

def method_json(sun_model: str = "VSOP87", asr_sun: str = "APPARENT", asr_mode: str = ASR_MODE) -> dict:
    refraction = {"type": "refraction", "fallback": {"type": "fixed", "altitudeDeg": FALLBACK_DEG}}
    return common.method_json(
        id=AUTHORITY, fajr={"type": "angle", "degrees": FAJR_DEG}, isha={"type": "angle", "degrees": ISHA_DEG},
        offsets={"DHUHR": DHUHR_OFFSET_MIN}, asrSun=asr_sun, sunModel=sun_model,
        sunEvaluation={} if asr_mode == "EVENT_INSTANT" else {"ASR": asr_mode},
        horizon={"sunrise": refraction, "sunset": refraction}, evidence={"kind": "FROM_TABLES", "source": EVIDENCE})


def fitted_zone(zone_id: str, town: tuple[float, float], f: ZoneFit) -> dict:
    z = {"id": zone_id, "point": common.point(*town), "morningPoint": common.point(f.lat, f.lng_m),
         "eveningPoint": common.point(f.lat, f.lng_w), "elevationM": int(f.h_m), "eveningElevationM": int(f.h_e),
         "pressureHpa": f.p, "temperatureC": f.t}
    if f.lng_rise is not None:
        z["eventPoints"] = {"SUNRISE": common.point(f.lat, f.lng_rise)}
    return z


def engine_zone(z: dict) -> dict:
    lat, lng = z["point"]["latE5"] / 1e5, z["point"]["lngE5"] / 1e5
    return common.zone_json(z["id"], lat, lng, authority=AUTHORITY, **{k: z.get(k) for k in (
        "morningPoint", "eveningPoint", "elevationM", "eveningElevationM", "pressureHpa", "temperatureC", "eventPoints")})


def score(method: dict, z: dict, rows: list[Row]) -> common.Score:
    s = common.Score()
    ez = engine_zone(z)
    lat, lng = z["point"]["latE5"] / 1e5, z["point"]["lngE5"] / 1e5
    for row in rows:
        out = common.compute(common.case(method, lat, lng, TZ, row.day, zone=ez, country="AE"))
        for e in EVENTS:
            s.add(e, row.day, out[e][1] // 60, row.published[e] // 60)
    return s


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


def within1(s: common.Score) -> float:
    return sum(s.within1[e] for e in EVENTS) / max(1, sum(s.total[e] for e in EVENTS))


def tier_a(stats: dict) -> list[str]:
    out = []
    for e in EVENTS:
        if stats["exact"][e] < 0.97:
            out.append(f"{e} {stats['exact'][e]:.1%}")
        if stats["within1"][e] < 1.0:
            out.append(f"{e} within 1 min {stats['within1'][e]:.1%}")
        if min(stats["daysPerMonth"][e]) < 7:
            out.append(f"{e} under 7 days in a month")
    return out


# --- Diagnostics (reported, not shipped) ---------------------------------------------------------------------------

def window_checks(rows: list[Row], sun, f: ZoneFit) -> str:
    """At the fitted points: the Dhuhr offset window (min), the Fajr and Isha angle windows, and Asr's convention: the
    shipped noon shadow at Dhuhr (E4 SHADOW_AT_DHUHR) against the shadow at the transit, apparent and geometric."""
    dh, fa, ish = [], [], []
    asr_app = asr_geo = asr_dhuhr = 0
    for row in rows:
        cy = row.cycle
        t0w = ref.transit(cy, sun, f.lng_w)
        tr = cy.instant(t0w)
        p = row.published
        dh.append(((p["DHUHR"] - 30 - tr) / 60, (p["DHUHR"] + 30 - tr) / 60))
        a1 = ref.altitude(cy, sun, f.lat, f.lng_m, cy.hours(p["FAJR"] - 30))
        a2 = ref.altitude(cy, sun, f.lat, f.lng_m, cy.hours(p["FAJR"] + 30))
        fa.append((min(-a1, -a2), max(-a1, -a2)))
        a1 = ref.altitude(cy, sun, f.lat, f.lng_w, cy.hours(p["ISHA"] - 30))
        a2 = ref.altitude(cy, sun, f.lat, f.lng_w, cy.hours(p["ISHA"] + 30))
        ish.append((min(-a1, -a2), max(-a1, -a2)))
        decl = sun(cy.jd0 + t0w / 24)[0]
        for kind in ("app", "geo", "dhuhr"):
            if kind == "geo":
                alt = ref.atand(1 / (1 + ref.tand(abs(f.lat - decl))))
            elif kind == "app":
                alt = asr_altitude(f.lat, decl, f.p, f.t)
            else:
                alt = asr_altitude_at_dhuhr(cy, sun, f.lat, f.lng_w, t0w, f.p, f.t)
            ta = cy.instant(ref.event(cy, sun, f.lat, f.lng_w, alt, 1, t0w))
            ok = p["ASR"] - 30 <= ta < p["ASR"] + 30
            asr_app += ok and kind == "app"
            asr_geo += ok and kind == "geo"
            asr_dhuhr += ok and kind == "dhuhr"
    b1, s1 = sweep(dh)
    b2, s2 = sweep(fa)
    b3, s3 = sweep(ish)
    return (f"Dhuhr offset {b1}/{len(rows)} on [{s1[0][0]:.3f}, {s1[-1][1]:.3f}) min; Fajr angle {b2} on "
            f"[{s2[0][0]:.3f}, {s2[-1][1]:.3f}); Isha angle {b3} on [{s3[0][0]:.3f}, {s3[-1][1]:.3f}); Asr k = 1, apparent "
            f"sun, noon shadow at Dhuhr (shipped) {asr_dhuhr}, at the transit {asr_app}; geometric at the transit {asr_geo}")


# --- Areas -----------------------------------------------------------------------------------------------------

def area_list() -> dict[str, dict]:
    """location id -> its emirates (GeoNames admin1) and its areas (Arabic names, duplicates dropped)."""
    out: dict[str, dict] = {}
    for e in json.loads(AREAS.read_text(encoding="utf-8")):
        for area_id, name, loc in e["cities"]:
            o = out.setdefault(str(loc), {"admin1": set(), "areas": []})
            o["admin1"].add(EMIRATES[e["id"]])
            if (name, EMIRATES[e["id"]]) not in [(a, b) for a, b, _ in o["areas"]]:
                o["areas"].append((name, EMIRATES[e["id"]], area_id))
    return out


def geocode(name: str, admin1: str, near: tuple[float, float], places: dict[str, Place]) -> Place | None:
    """An area inside its emirate: a GeoNames row with Awqaf's Arabic name (with or without the trailing emirate
    name Awqaf adds), or ALIASES; the candidate nearest the location wins; none farther than 60 km."""
    keys = {norm(name)}
    for q in QUALIFIERS:
        if name.endswith(" " + q):
            keys.add(norm(name[: -len(q) - 1]))
    keys |= {k[2:] if k.startswith("ال") else "ال" + k for k in list(keys)}
    found = [p for p in places.values() if p.admin1 == admin1 and p.arabic & keys]
    if not found and name in ALIASES and ALIASES[name] in places and places[ALIASES[name]].admin1 == admin1:
        found = [places[ALIASES[name]]]
    found = [p for p in found if km(near, (p.lat, p.lng)) <= 60]
    return min(found, key=lambda p: km(near, (p.lat, p.lng))) if found else None


# --- Main ------------------------------------------------------------------------------------------------------

def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="Fits ae_awqaf and writes its official-zone asset.")
    parser.add_argument("--refit", action="store_true", help="re-pick each zone's values by the stages instead of FIRST_FIT")
    refit = parser.parse_args().refit
    at = accurate_times()
    places = geonames_ae()
    areas = area_list()
    say(f"{AUTHORITY}: {len(areas)} locations and {sum(len(a['areas']) for a in areas.values())} distinct areas in the "
        f"area list; {len(places)} GeoNames rows in the UAE; {len(at)} Accurate Times UAE entries; "
        + ("re-picking each zone's values (--refit)" if refit else "the fixed values of FIRST_FIT, Asr by SHADOW_AT_DHUHR"))
    if set(areas) != set(LOCATIONS):
        raise SystemExit(f"area list locations {sorted(set(areas) ^ set(LOCATIONS))} differ from LOCATIONS")
    sun = TableSun("VSOP87", datetime.date(2026, 1, 1), datetime.date(2026, 12, 31))
    meeus = TableSun("MEEUS", datetime.date(2026, 1, 1), datetime.date(2026, 12, 31))
    method = method_json()

    email = email_tables()
    if set(email) - set(LOCATIONS):
        raise SystemExit(f"{EMAIL.name}: zones {sorted(set(email) - set(LOCATIONS))} are not Awqaf locations")
    fitted, records, all_rows, fold_scores = {}, {}, {}, {}
    for zid in sorted(set(TABLES) | set(email), key=int):
        rows, note = zone_rows(zid, email)
        all_rows[zid] = rows
        seed = at[LOCATIONS[zid][2]]
        town = places[LOCATIONS[zid][1]]
        fresh = refit or zid not in FIRST_FIT       # a zone without fixed values yet runs the stages
        say(f"zone {zid} {LOCATIONS[zid][0]} ({town.name}): {len(rows)} days ({note}); seed {LOCATIONS[zid][2]}"
            + ("" if not fresh or refit else "; no fixed values yet: the stages run"))
        f = fit_zone(rows, sun, seed) if fresh else fixed_zone(FIRST_FIT[zid]["all"])
        if fresh and zid in OWN_SUNRISE:
            shared = f.counts["SUNRISE"]
            f = own_sunrise(rows, sun, f)
            say(f"  stage 5: sunrise at its own {f.lng_rise:.4f} E ({km((f.lat, f.lng_m), (f.lat, f.lng_rise)):.1f} km east of "
                f"Fajr's), {f.counts['SUNRISE']} days' windows against {shared} at Fajr's point")
        fitted[zid] = f
        say(f"  all days: western {f.lat:.4f} N {f.lng_w:.4f} E, eastern {f.lng_m:.4f} E ({f.east_km():.1f} km), "
            + (f"sunrise {f.lng_rise:.4f} E, " if f.lng_rise is not None else "")
            + f"morning {f.h_m:.0f} m, evening {f.h_e:.0f} m, {f.p:.0f} hPa {f.t:.0f} C" + (f"; windows {f.counts}" if f.counts else ""))
        say("  " + window_checks(rows, sun, f))
        say(f"  computing point {km((f.lat, f.lng_w), (town.lat, town.lng)):.1f} km from {town.name}; nearest GeoNames town "
            + min((f"{p.name} {km((f.lat, f.lng_w), (p.lat, p.lng)):.1f} km" for p in places.values()),
                  key=lambda s: float(s.rsplit(' ', 2)[-2])))
        if refit:
            fm = fit_zone(rows, meeus, seed)
            if zid in OWN_SUNRISE:
                fm = own_sunrise(rows, meeus, fm)
            mz = fitted_zone(zid, (town.lat, town.lng), fm)
            say(f"  MEEUS refit: {overall(score(method_json('MEEUS'), mz, rows)):.2%} exact in-sample, against VSOP87's "
                f"{overall(score(method, fitted_zone(zid, (town.lat, town.lng), f), rows)):.2%}")
        held = []
        asr_transit = 0
        for name, months in FOLDS:
            part = [r for r in rows if r.day.month in months]
            ff = fit_zone(part, sun, seed) if fresh else fixed_zone(FIRST_FIT[zid][name])
            if fresh and zid in OWN_SUNRISE:
                ff = own_sunrise(part, sun, ff)
            rest = [r for r in rows if r.day.month not in months]
            s = score(method, fitted_zone(zid, (town.lat, town.lng), ff), rest)
            held.append(s)
            asr_transit += score(method_json(asr_mode="EVENT_INSTANT"), fitted_zone(zid, (town.lat, town.lng), ff), rest).exact["ASR"]
            say(f"  fold {name} ({'fitted' if fresh else 'fixed values fitted'} on {len(part)} days): {ff.lat:.4f} N, "
                f"{ff.lng_w:.4f} / {ff.lng_m:.4f} E" + (f", sunrise {ff.lng_rise:.4f} E" if ff.lng_rise is not None else "")
                + f", {ff.h_m:.0f} / {ff.h_e:.0f} m; held out: {shares(s)}")
        total = merged(held)
        say(f"  held-out Asr {total.exact['ASR']}/{len(rows)} with the noon shadow at Dhuhr (shipped); at the same fold points "
            f"with the shadow at the transit (EVENT_INSTANT) {asr_transit}/{len(rows)}")
        fold_scores[zid] = total
        stats = common.fit_stats([common.sample(SOURCE, rows[0].day, rows[-1].day, len(rows), 1)],
                                 [common.sample(SOURCE, rows[0].day, rows[-1].day, len(rows), 1, month_split=True)], total)
        records[zid] = stats
        failures = tier_a(stats)
        insample = score(method, fitted_zone(zid, (town.lat, town.lng), f), rows)
        say(f"  held out (both folds): {shares(total)}; worst {total.worst} min; in-sample {overall(insample):.2%}")
        say(f"  tier A: {'passes' if not failures else 'fails: ' + ', '.join(failures)}")

    alts = {zid: (Horizon(f.p, f.t).altitude(f.h_m, 1.0), Horizon(f.p, f.t).altitude(f.h_e, 1.0)) for zid, f in fitted.items()}
    say("the fitted horizons at mean distance (sunrise / sunset): " + ", ".join(f"{z} {a:.3f} / {b:.3f}" for z, (a, b) in alts.items())
        + f"; medians {statistics.median(a for a, _ in alts.values()):.3f} / {statistics.median(b for _, b in alts.values()):.3f} "
        f"(the profile's exact-coordinates fallback is Fixed({FALLBACK_DEG}))")

    # CHECK: provisional inputs for the locations without a table: Accurate Times' entry as it stands (its point as
    # the western point, its diameter east, its elevation morning and evening, the climate law) against the fitted
    # zones' median elevations at the town point, each judged on the three fitted tables (the median leaves the zone
    # out).
    check = {}
    for zid, rows in all_rows.items():
        e = at[LOCATIONS[zid][2]]
        p, t = climate(e["elev"])
        east = e["lng"] + e["diam"] / (111.32 * math.cos(math.radians(e["lat"])))
        town = places[LOCATIONS[zid][1]]
        z_at = {"id": zid, "point": common.point(town.lat, town.lng), "morningPoint": common.point(e["lat"], east),
                "eveningPoint": common.point(e["lat"], e["lng"]), "elevationM": int(e["elev"]), "eveningElevationM": int(e["elev"]),
                "pressureHpa": p, "temperatureC": t}
        others = [fitted[o] for o in fitted if o != zid]
        hm = float(round(statistics.median(o.h_m for o in others)))
        he = float(round(statistics.median(o.h_e for o in others)))
        pm, tm = climate(hm)
        z_med = {"id": zid, "point": common.point(town.lat, town.lng), "elevationM": int(hm), "eveningElevationM": int(he),
                 "pressureHpa": pm, "temperatureC": tm}
        s_at, s_med = score(method, z_at, rows), score(method, z_med, rows)
        check[zid] = (s_at, s_med)
        say(f"check {zid}: Accurate Times' entry {overall(s_at):.1%} exact, {within1(s_at):.1%} within 1 min, worst {s_at.worst}; "
            f"town point with the others' median elevations {overall(s_med):.1%}, {within1(s_med):.1%}, worst {s_med.worst}")
    # Plan 8.5: Accurate Times' parameters ship only once an official table confirms them, so the other locations
    # take the median whatever CHECK shows (its numbers go to the report for the owner).
    use_at = SHIP_ACCURATE_TIMES_INPUTS
    median_hm = float(round(statistics.median(f.h_m for f in fitted.values())))
    median_he = float(round(statistics.median(f.h_e for f in fitted.values())))
    say(f"provisional inputs for the other locations: the town point with the fitted zones' median elevations "
        f"{median_hm:.0f} / {median_he:.0f} m, no eastern point (Accurate Times' entries would do better where they hold, "
        f"as at Abu Dhabi, but stay unconfirmed: plan 8.5)")

    zones, left_out, unmatched, far = [], [], [], []
    geocoded = 0
    for zid, (name_ar, where, entry) in sorted(LOCATIONS.items(), key=lambda kv: int(kv[0])):
        info = areas[zid]
        if where is None:
            left_out.append(f"{zid} {name_ar}")
            continue
        if where == "at":
            town = (at[entry]["lat"], at[entry]["lng"])
        elif isinstance(where, tuple):
            town = where[1:]
        else:
            town = (places[where].lat, places[where].lng)
        if zid in fitted:
            z = fitted_zone(zid, town, fitted[zid])
            z["fit"] = records[zid]
        elif use_at and entry:
            e = at[entry]
            p, t = climate(e["elev"])
            z = {"id": zid, "point": common.point(*town), "elevationM": int(e["elev"]), "pressureHpa": p, "temperatureC": t}
            if (e["lat"], e["lng"]) != town or e["diam"]:
                z["eveningPoint"] = common.point(e["lat"], e["lng"])
                z["morningPoint"] = common.point(e["lat"], e["lng"] + e["diam"] / (111.32 * math.cos(math.radians(e["lat"]))))
        else:
            p, t = climate(median_hm)
            z = {"id": zid, "point": common.point(*town), "elevationM": int(median_hm), "eveningElevationM": int(median_he),
                 "pressureHpa": p, "temperatureC": t}
        z["nameAr"] = name_ar
        z["admin1"] = sorted(info["admin1"])
        named = []
        for area, admin1, _ in info["areas"]:
            g = geocode(area, admin1, town, places)
            if g is None:
                unmatched.append(f"{zid}:{area}")
                continue
            if km(town, (g.lat, g.lng)) > 60:
                far.append(f"{zid}:{area}")
                continue
            entry_ = {"centre": common.point(g.lat, g.lng), "catalogueIds": [int(g.gid)]}
            if entry_ not in named:
                named.append(entry_)
            geocoded += 1
        if named:
            z["namedPlaces"] = named
        zones.append(z)
    say(f"zones written: {len(zones)} ({len(fitted)} fitted); left out (no allowed source places them): {', '.join(left_out)}")
    say(f"areas geocoded inside their emirate: {geocoded} of {sum(len(a['areas']) for a in areas.values())}; not found in "
        f"cities1000: {len(unmatched)} (for the catalogue build, M5); beyond 60 km: {far or 'none'}")

    profile_total = merged(list(fold_scores.values()))
    first = min(r.day for rows in all_rows.values() for r in rows)
    last = max(r.day for rows in all_rows.values() for r in rows)
    days = sum(len(r) for r in all_rows.values())
    profile = common.fit_stats([common.sample(SOURCE, first, last, days, len(fitted))],
                               [common.sample(SOURCE, first, last, days, len(fitted), month_split=True)], profile_total)
    say(f"profile ({len(fitted)} zones, held out): {shares(profile_total)}; within 1 min: "
        + ", ".join(f"{e} {profile['within1'][e]:.1%}" for e in EVENTS) + f"; worst {profile_total.worst} min")
    # A place takes the location with the nearest listed area or own point in its emirate (plan 8.8): named first.
    path = common.write_asset(AUTHORITY, AUTHORITY, "AE", "NEAREST", zones, fit=profile, source=SOURCE_LINE, by_admin1=True,
                              named_first=True)
    say(f"wrote {path.relative_to(common.ROOT).as_posix()}: {len(zones)} zones")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
