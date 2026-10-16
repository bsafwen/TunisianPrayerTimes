"""
The point fit of Diyanet's entries, shared by `diyanet_tr` (fits/diyanet_tr/fit.py) and `diyanet_eu`'s Austrian tiers
(fits/diyanet_eu_at/fit.py), with the GeoNames and catalogue readers both use (plan 6.5, 7.5, 9.4, M3).

Diyanet's method with the sun taken once a day at 0h UT (E4 `DAILY_0H_UT`) has a closed form for every time the
takdir leaves alone, which the search loops use (`Day`, `count`); every score printed and every FitStats comes from
common.compute (the reference engine), and `check_fast` confirms the closed form equals it.

Points are fitted on a grid: the longitude Dhuhr's windows allow, then the latitude the other times allow, then the
whole arc-minute (as fits/om_mara and fits/bosnia_iz) that reproduces the most minutes in a 2-D search around them, and
from it the best point of a finer grid where asked (the Diyanet fits use 1/400 degree: Diyanet's points are not on
whole arc-minutes, and held-out scores prefer 0.0025 degree, fits/diyanet_tr/README-part.md); a tie goes to the tied
point nearest the middle of the tied points.
"""

from __future__ import annotations

import datetime
import io
import math
import re
import sys
import unicodedata
import zipfile
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import common  # noqa: E402  (fits/common.py)

ref = common.ref
EVENTS = common.EVENTS
OFFSETS = (0, -7, 5, 4, 7, 0)          # Fajr, sunrise, Dhuhr, Asr, Maghrib, Isha (EuropeanPresets.DIYANET_OFFSETS)
HORIZON = -0.8333
D2R = math.pi / 180
GEONAMES = common.LOCAL_DATA / "research-2026-10-11" / "GEONAMES"
CATALOGUE = common.ROOT / "android-app" / "app" / "src" / "main" / "assets" / "places" / "europe-cities.tsv"


def method_json(method_id: str, isha: float) -> dict:
    """`diyanet_tr` (Isha 17) or `diyanet_eu` (Isha 16) as EuropeanPresets has them: 18 deg Fajr, sunrise - 7,
    Dhuhr + 5, Asr + 4, Maghrib + 7, the sun once a day at 0h UT, NEAREST, the takdir from 44.5 N, Diyanet's clamp."""
    return common.method_json(
        id=method_id, fajr={"type": "angle", "degrees": 18.0}, isha={"type": "angle", "degrees": isha},
        offsets={"SUNRISE": -7, "DHUHR": 5, "ASR": 4, "MAGHRIB": 7}, sunEvaluation={e: "DAILY_0H_UT" for e in EVENTS},
        highLatitude={"type": "diyanet_takdir"}, polar="DIYANET_CLAMP", minDayNightHours=5,
        evidence={"kind": "FROM_TABLES" if method_id == "diyanet_eu" else "PUBLISHED", "source": "Diyanet"})


def minutes(hhmm: str) -> int:
    h, m = hhmm.split(":")
    return int(h) * 60 + int(m)


class Day:
    """One published day: its date, the six published local minutes (and as epoch minutes, read with the offset of
    each clock time, summer-time days included), the clock offset at noon (minutes) and the day's sun."""
    __slots__ = ("date", "pub", "pub_epoch", "tz", "sd", "cd", "decl", "noon0")

    def __init__(self, date: datetime.date, times, zone_id: str):
        self.date = date
        self.pub = [minutes(t) for t in times]
        self.pub_epoch = [common.local_to_epoch(zone_id, date, t) // 60 for t in times]
        self.tz = common.utc_offset(zone_id, date) // 60
        decl, eot, _ = ref.meeus_sun(ref.julian_day0(date))   # the cycle date is the civil date in TR and AT
        self.decl = decl
        self.sd, self.cd = math.sin(decl * D2R), math.cos(decl * D2R)
        self.noon0 = (12 - eot / 60) * 60 + self.tz          # local minutes of the transit at longitude 0


def days_of(rows: dict, zone_id: str) -> list[Day]:
    return [Day(d, rows[d], zone_id) for d in sorted(rows)]


def unrounded(day: Day, lat: float, lng: float, isha: float) -> list:
    """The six unrounded local minutes, offsets included, outside the takdir (None where absent)."""
    sl, cl = math.sin(lat * D2R), math.cos(lat * D2R)
    noon = day.noon0 - 4 * lng
    den = cl * day.cd

    def half(alt):
        c = (math.sin(alt * D2R) - sl * day.sd) / den
        return None if abs(c) > 1 else math.acos(c) / D2R * 4

    h0 = half(HORIZON)
    hf = half(-18.0)
    hi = half(-isha)
    zen = abs(lat - day.decl)
    ha = half(math.atan(1 / (1 + math.tan(zen * D2R))) / D2R)
    return [None if hf is None else noon - hf, None if h0 is None else noon - h0 - 7, noon + 5,
            None if ha is None else noon + ha + 4, None if h0 is None else noon + h0 + 7, None if hi is None else noon + hi]


def rounded(x: float | None) -> int | None:
    return None if x is None else math.floor(x + 0.5)


def count(days: list[Day], lat: float, lng: float, isha: float, mask) -> int:
    """Exact minutes at (lat, lng) over the days and events mask(day) allows (a tuple of six booleans)."""
    n = 0
    for day in days:
        use = mask(day)
        if not any(use):
            continue
        u = unrounded(day, lat, lng, isha)
        for i in range(6):
            if use[i] and u[i] is not None and math.floor(u[i] + 0.5) == day.pub[i]:
                n += 1
    return n


def stab(intervals) -> tuple[int, list[tuple[float, float]]]:
    """The most half-open intervals [lo, hi) one value lies in, and every stretch where it does."""
    marks = sorted([(lo, 1) for lo, _ in intervals] + [(hi, -1) for _, hi in intervals], key=lambda m: (m[0], m[1]))
    best, cur, spans = 0, 0, []
    for i, (x, s) in enumerate(marks):
        cur += s
        nxt = marks[i + 1][0] if i + 1 < len(marks) else x
        if nxt <= x:
            continue
        if cur > best:
            best, spans = cur, [(x, nxt)]
        elif cur == best and best > 0:
            spans.append((x, nxt))
    return best, spans


def longitude_windows(days: list[Day]) -> tuple[int, list[tuple[float, float]]]:
    """Dhuhr = noon0 - 4 lng + 5, rounded to the nearest minute: each published Dhuhr is a window of longitudes."""
    ivs = []
    for day in days:
        k = day.noon0 + 5 - day.pub[2]               # pub - 0.5 <= noon0 + 5 - 4 lng < pub + 0.5
        ivs.append(((k - 0.5) / 4, (k + 0.5) / 4))
    return stab(ivs)


def best_point(days, seed_cells: tuple[int, int], isha: float, mask, span: int = 2, cells_per_degree: int = 60) -> tuple[int, int, int, int]:
    """(count, lat, lng, tied) on the grid of 1/cells_per_degree degree (whole arc-minutes by default) reproducing the
    most minutes around the seed (in cells), recentred while the best lies on the edge; a tie goes to the tied point
    nearest the middle of the tied points."""
    la0, lo0 = seed_cells
    cache = {}

    def score(la, lo):
        if (la, lo) not in cache:
            cache[(la, lo)] = count(days, la / cells_per_degree, lo / cells_per_degree, isha, mask)
        return cache[(la, lo)]

    for _ in range(30):
        res = [(score(la0 + i, lo0 + j), la0 + i, lo0 + j) for i in range(-span, span + 1) for j in range(-span, span + 1)]
        top = max(r[0] for r in res)
        tied = [r for r in res if r[0] == top]
        if all(abs(r[1] - la0) < span and abs(r[2] - lo0) < span for r in tied):
            break
        la0, lo0 = min(tied, key=lambda r: (abs(r[1] - la0) + abs(r[2] - lo0), r[1], r[2]))[1:]
    # The whole plateau of the top count (it may reach beyond the last window).
    plateau, frontier = {(r[1], r[2]) for r in tied}, [(r[1], r[2]) for r in tied]
    while frontier and len(plateau) < 400:
        la, lo = frontier.pop()
        for i, j in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            p = (la + i, lo + j)
            if p not in plateau and score(*p) == top:
                plateau.add(p)
                frontier.append(p)
    mla = sum(p[0] for p in plateau) / len(plateau)
    mlo = sum(p[1] for p in plateau) / len(plateau)
    la, lo = min(plateau, key=lambda p: ((p[0] - mla) ** 2 + (p[1] - mlo) ** 2, p[0], p[1]))
    return top, la, lo, len(plateau)


def seed_latitude(days, lng: float, isha: float, mask, lat_range: tuple[float, float]) -> float:
    """The latitude whose sunrise, Asr, Maghrib, Fajr and Isha reproduce the most minutes at lng (0.05 deg, then
    whole arc-minutes), Dhuhr left out."""
    def no_dhuhr(day):
        m = mask(day)
        return (m[0], m[1], False, m[3], m[4], m[5])

    lo, hi = lat_range
    coarse = max((count(days, lo + 0.05 * i, lng, isha, no_dhuhr), -abs(lo + 0.05 * i - (lo + hi) / 2), lo + 0.05 * i)
                 for i in range(int((hi - lo) / 0.05) + 1))[2]
    fine = max((count(days, coarse + i / 60, lng, isha, no_dhuhr), -abs(i), coarse + i / 60) for i in range(-4, 5))
    return fine[2]


def refine_point(days, isha: float, mask, seed: tuple[float, float], cells_per_degree: int) -> dict:
    """The best grid point around a point already fitted (a month-split refit from the whole year's point)."""
    k = cells_per_degree
    top, la, lo, tied = best_point(days, (round(seed[0] * k), round(seed[1] * k)), isha, mask, cells_per_degree=k)
    return {"lat": la / k, "lng": lo / k, "count": top, "tied": tied}


def fit_point(days, isha: float, mask, seed: tuple[float, float] | None, lat_range: tuple[float, float],
              cells_per_degree: int = 60) -> dict:
    """The entry's point on the grid (whole arc-minutes by default): {"lat", "lng", "count", "tied", "lngWindows",
    "lngSpan"}."""
    nd, spans = longitude_windows([d for d in days if mask(d)[2]])
    # The Dhuhr plateau nearest the seed's longitude (or the widest), and the arc-minute nearest its middle.
    if seed is not None:
        a, b = min(spans, key=lambda s: 0 if s[0] <= seed[1] < s[1] else min(abs(s[0] - seed[1]), abs(s[1] - seed[1])))
    else:
        a, b = max(spans, key=lambda s: s[1] - s[0])
    lng = (a + b) / 2
    lat = seed_latitude(days, lng, isha, mask, lat_range if seed is None else (seed[0] - 0.5, seed[0] + 0.5))
    k = cells_per_degree
    if k != 60:   # a finer grid starts from the whole arc-minute's best
        _, la, lo, _ = best_point(days, (round(lat * 60), round(lng * 60)), isha, mask)
        lat, lng = la / 60, lo / 60
    top, la, lo, tied = best_point(days, (round(lat * k), round(lng * k)), isha, mask, cells_per_degree=k)
    return {"lat": la / k, "lng": lo / k, "count": top, "tied": tied, "lngWindows": nd, "lngSpan": (a, b)}


# ------------------------------------------------------------------------------------------------ scoring

def score_compute(method: dict, zone: dict, days: list[Day], zone_id: str, country: str, score: common.Score,
                  skip=None, apart: common.Score | None = None) -> int:
    """Scores the published days with common.compute at the zone; skip(day, event_index, status) sends a time to
    [apart] (or drops it when apart is None). Returns the count of ESTIMATED times."""
    estimated = 0
    p = zone["point"]
    for day in days:
        out = common.compute(common.case(method, p["latE5"] / 1e5, p["lngE5"] / 1e5, zone_id, day.date, zone=zone, country=country))
        for i, e in enumerate(EVENTS):
            _, minute, status = out[e]
            estimated += status == "ESTIMATED"
            if minute is None:
                continue
            got = minute // 60                    # epoch minutes, compared with the published clock time's
            if skip is not None and skip(day, i, status):
                if apart is not None:
                    apart.add(e, day.date, got, day.pub_epoch[i])
                continue
            score.add(e, day.date, got, day.pub_epoch[i])
    return estimated


def check_fast(method: dict, zone: dict, days: list[Day], zone_id: str, country: str, lat: float, lng: float,
               isha: float) -> int:
    """How many COMPUTED times the closed form gives differently from common.compute (expected 0)."""
    bad = 0
    p = zone["point"]
    for day in days:
        out = common.compute(common.case(method, p["latE5"] / 1e5, p["lngE5"] / 1e5, zone_id, day.date, zone=zone, country=country))
        base = (day.date - datetime.date(1970, 1, 1)).days * 1440 - day.tz   # epoch minute of local 00:00 at noon's offset
        fast = unrounded(day, lat, lng, isha)
        for i, e in enumerate(EVENTS):
            _, minute, status = out[e]
            if status != "COMPUTED" or minute is None or fast[i] is None:
                continue
            bad += minute // 60 != base + rounded(fast[i])
    return bad


def merge(into: common.Score, sc: common.Score) -> None:
    for e in EVENTS:
        into.exact[e] += sc.exact[e]
        into.within1[e] += sc.within1[e]
        into.total[e] += sc.total[e]
        for m in range(12):
            into.months[e][m] += sc.months[e][m]
    into.worst = max(into.worst, sc.worst)
    into.misses += sc.misses


def shares(sc: common.Score, events=EVENTS) -> str:
    ex, w1 = sc.shares(), sc.within1_shares()
    return " ".join(f"{e[:4]} {100 * ex[e]:5.1f}/{100 * w1[e]:5.1f}" for e in events if sc.total[e]) + f"  max {sc.worst}"


def km(a: tuple[float, float], b: tuple[float, float]) -> float:
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * 6371.0088 * math.asin(min(1.0, math.sqrt(h)))


# ------------------------------------------------------------------------------------------------ names and places

TURKISH = str.maketrans({"ç": "c", "ğ": "g", "ı": "i", "ö": "o", "ş": "s", "ü": "u", "â": "a", "î": "i", "û": "u",
                         "ä": "a", "ß": "ss", "é": "e", "è": "e"})


def fold(text: str) -> str:
    """A matching key: Turkish-aware lower case, diacritics and everything but a-z0-9 dropped."""
    t = text.replace("İ", "i").replace("I", "ı").lower().translate(TURKISH)
    t = unicodedata.normalize("NFKD", t)
    return re.sub(r"[^a-z0-9]", "", "".join(ch for ch in t if not unicodedata.combining(ch)))


def title_tr(text: str) -> str:
    """Diyanet's upper-case name in title case, Turkish dotted and dotless i kept («KARADENİZ EREĞLİ» -> «Karadeniz Ereğli»)."""
    def word(w):
        if not w:
            return w
        rest = w[1:].replace("I", "ı").replace("İ", "i").lower()
        return w[0] + rest
    return " ".join(word(w) for w in text.split(" "))


def title_de(text: str) -> str:
    """Diyanet's upper-case Austrian name in title case, a one-letter province hint dropped («GAISBERG (K)» -> «Gaisberg»,
    «BERG (Reutte)» -> «Berg (Reutte)»)."""
    base, _, hint = text.partition("(")
    words = " ".join(w.capitalize() for w in base.split())
    hint = hint.rstrip(")").strip()
    return f"{words} ({hint.capitalize() if hint.isupper() else hint})" if len(hint) > 1 else words


def read_geonames(countries: set[str]) -> list[dict]:
    """GeoNames cities500 rows of the countries (all places of more than 500 people and every seat down to PPLA4)."""
    out = []
    with zipfile.ZipFile(GEONAMES / "cities500.zip") as z, z.open("cities500.txt") as raw:
        for line in io.TextIOWrapper(raw, encoding="utf-8"):
            c = line.rstrip("\n").split("\t")
            if len(c) < 19 or c[8] not in countries:
                continue
            primary = {fold(c[1]), fold(c[2])} - {""}
            keys = primary | {fold(a) for a in c[3].split(",") if a and all(ord(ch) < 0x370 for ch in a)}
            keys.discard("")
            out.append({"id": int(c[0]), "name": c[1], "lat": float(c[4]), "lng": float(c[5]), "fcode": c[7], "cc": c[8],
                        "admin1": c[10], "admin2": c[11], "population": int(c[14] or 0), "keys": keys, "primary": primary})
    return out


def read_admin1_names(country: str) -> dict[str, str]:
    out = {}
    for line in (GEONAMES / "admin1CodesASCII.txt").read_text(encoding="utf-8").splitlines():
        k, name, ascii_name, _ = line.split("\t")
        if k.startswith(country + "."):
            out[k[len(country) + 1:]] = name
    return out


def read_catalogue(countries: set[str]) -> list[dict]:
    """The bundled catalogue's rows of the countries (the phone's places, 6.8)."""
    lines = CATALOGUE.read_text(encoding="utf-8").splitlines()
    header = lines[0].split("\t")
    out = []
    for line in lines[1:]:
        c = dict(zip(header, line.split("\t")))
        if c["country"] in countries:
            out.append({"id": int(c["id"]), "cc": c["country"], "admin2": c["admin2"], "name": c["name"], "arabic": c["arabic"],
                        "lat": int(c["latE5"]) / 1e5, "lng": int(c["lngE5"]) / 1e5, "population": int(c["population"] or 0)})
    return out


def d3_label(native: str, country_ar: str) -> str:
    """D3 (owner, 2026-10-11): a place with no Arabic name is its native name in an LTR isolate, then the Arabic country name."""
    return f"⁦{native}⁩، {country_ar}"


def code_provinces(rows: list[dict]) -> tuple[dict[str, str], set[str]]:
    """Each admin2 code's province (the admin1 most of its rows give) and the codes whose rows lie in several."""
    by = defaultdict(Counter)
    for r in rows:
        if r["admin2"]:
            by[r["admin2"]][r["admin1"]] += 1
    return {k: v.most_common(1)[0][0] for k, v in by.items()}, {k for k, v in by.items() if len(v) > 1}


FCODE_RANK = {"PPLC": 0, "PPLA": 0, "PPLA2": 1, "PPLA3": 2, "PPLA4": 3, "PPL": 4, "PPLX": 5}


def fcode_rank(fcode: str) -> int:
    return FCODE_RANK.get(fcode, 6)
