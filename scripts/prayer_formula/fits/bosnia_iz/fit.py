#!/usr/bin/env python3
"""
Fits `bosnia_iz`, the Islamic Community of Bosnia and Herzegovina's Takvim (plan 7.2, 7.5, 8.8, 9.2; milestone M3,
9.4), and writes its official-zone asset, android-app/app/src/main/assets/official-zones/bosnia_iz.json (schema 1).

Data, read at run time from test-data-local/research-2026-10-11/BA/iz_bih/ and never copied (D13, A7):
- vaktija.json: vaktija.ba's data (github vaktija/vaktija.ba, last data commit 2024-12-30 "nova vremena 25"):
  Sarajevo's perpetual table (366 rows, CET all year) and the monthly differences of 117 places: 106 in BiH, five
  Montenegrin and six Serbian towns. vaktija_499a8ec.json is the same file before that commit.
- places.json: GeoNames points of vaktija.ba's 118 places (Sarajevo included).
- mesihat.json: the Takvim 2025's differences for the mesihats (Croatia 5, Slovenia 5, Serbia 6 with Novi Pazar).
- takvim_extra.json: the Takvim 2025's differences for Bosanska Kostajnica, Teocak and Ustikolina (pages 23-45).
- api_77_2026.json, api_91_2026.json, api_2_2026.json: vaktija.ba's 2026 API for Sarajevo, Trebinje and Bihac.

The Takvim's rule (pages 8-12) gives a place's difference from Sarajevo (43 52'N 18 26'E) per month:
dt_lambda = 4 (18 26' - lambda), dt_phi = tau g (phi - 43 52') with tau Table 1's monthly mean (north row, or the
south row with its printed sign) and g 1.1 north, 0.9 south; Fajr and sunrise take dt_lambda + dt_phi, Dhuhr
dt_lambda, Asr, Maghrib and Isha dt_lambda - dt_phi, each rounded to the whole minute. The profile carries Table 1's
24 means; each listed place is a zone whose fitted point gives its printed differences. Nothing of the tables goes
into the asset (8.8).

1. Sarajevo's base table (one zone, the base of every other).
   - Its Fajr and Isha were recomputed for 2025 (the 2024-12-30 commit changed no other column): they are fitted at
     that reference year (E5). Its sunrise, Dhuhr, Asr and Maghrib are older and follow no single year (printed
     scan below), so they are fitted on the years the app computes, 2026-2029 pooled, as the app shows the table
     (A8). The engine computes the actual year.
   - Stages (9.4), windows per published minute: 0, one evaluation for all six times (E4), at the Takvim's point
     with the published angles and offsets; 1, Dhuhr (evaluation, rounding, offset, longitude); 2, the latitude from
     sunrise, Maghrib, Fajr and Isha, then each horizon time's evaluation, rounding and offset; 3, Fajr and Isha's
     evaluation, rounding and angle; 4, Asr. An alternative to the published values, NEAREST or stage 0's
     evaluation must cover more than 0.5 % more windows.
   - Held out: month-split (fit on the odd months, score the even ones, and the reverse), every held-out day scored
     with common.compute (the reference engine) for 2026.
2. The Takvim places: Bosnia's 110 (vaktija.ba's 107 with Sarajevo, plus the three above), Croatia's 5, Slovenia's 5
   and Serbia's 11. Each point is the one the most printed values allow, nearest the town, within 15 km; within 60 km
   where the month-split shows the wider search predicts the left-out months better. Held out: the point fitted on
   half the months scores the other half's printed differences, per day of 2026.
3. Montenegro's five towns: vaktija.ba's one difference all year on all six times (published offsets, each reaching
   as far as its own OpenStreetMap boundary: ME_REACH_KM).

The asset keeps each zone's town as its `point` and the fitted computing point as its E6 `morningPoint` and
`eveningPoint` (8.8). The engine's TAKVIM rule reads `point` today; the scores below that use the fitted point
assume the engine change README-part.md describes (TAKVIM at the zone's E6 points), and one line shows what the
rule gives as it reads today.

Usage: python scripts/prayer_formula/fits/bosnia_iz/fit.py
"""

from __future__ import annotations

import calendar
import datetime
import json
import math
import sys
import time
import unicodedata
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import common  # noqa: E402

ref = common.ref
EVENTS = common.EVENTS
AUTHORITY = "bosnia_iz"
TZ = "Europe/Sarajevo"
DATA = common.LOCAL_DATA / "research-2026-10-11" / "BA" / "iz_bih"
PHI0 = 43 + 52 / 60            # the Takvim's Sarajevo, pages 8-12 (and the engine's SARAJEVO_LATITUDE)
LAM0 = 18 + 26 / 60
TABLE1_NORTH = [3.40, 1.90, 0.15, -1.68, -3.33, -4.30, -3.90, -2.35, -0.60, 1.25, 2.98, 3.98]    # Table 1 b), page 12
TABLE1_SOUTH = [-2.80, -1.58, -0.13, 1.33, 2.73, 3.53, 3.18, 1.95, 0.50, -1.03, -2.43, -3.23]   # Table 1 a), page 12
TAKVIM_MEANS = TABLE1_NORTH + TABLE1_SOUTH
NEW_YEAR = 2025                # Fajr and Isha's reference year (the 2024-12-30 commit "nova vremena 25")
APP_YEARS = (2026, 2027, 2028, 2029)
SCORE_YEAR = 2026
PUBLISHED = {"FAJR": 0, "SUNRISE": -6, "DHUHR": 1, "ASR": 0, "MAGHRIB": 6, "ISHA": 0}   # 7.2: sunrise -6, Dhuhr +1, Maghrib +6
EVALS = ("EVENT_INSTANT", "DAILY_0H_UT", "DAILY_10H_UT", "KACST")
ASR_EVALS = EVALS + ("LOCAL_MIDNIGHT_SHADOW",)
WINDOW = {"NEAREST": (-30.0, 30.0), "UP": (-60.0, 0.0), "DOWN": (0.0, 60.0)}
MODES = ("NEAREST", "UP", "DOWN")
CLEARLY = 1.005                # an alternative must cover more than 0.5 % more windows than the default
RADIUS_KM = (15.0, 60.0)
MARGIN = 0.001                 # minutes kept inside every printed difference's window (above E5 rounding and float noise)
KM_PER_DEG = 111.2
# 8.8: Montenegro's five towns' areas, set in M3 from the town extents (fits/town_extents.py, OpenStreetMap's town
# boundaries): the farthest vertex of the town's own boundary from the zone's point, rounded up to 0.5 km; Gusinje, a
# point only in OpenStreetMap, the median of the other four (3.42 km). Formerly 8 km each, the plan's start.
ME_REACH_KM = {"Bijelo Polje": 6.0, "Gusinje": 3.5, "Plav": 2.0, "Pljevlja": 3.0, "Rožaje": 4.0}
SOURCE_LINE = ("The Islamic Community of Bosnia and Herzegovina: vaktija.ba's perpetual Sarajevo table and the Takvim "
               "2025's monthly town differences, derived on the phone from the Takvim's rule (pages 8-12, Table 1)")
TABLE_SOURCE = "vaktija.ba Sarajevo table (perpetual; Fajr and Isha of 2025, the other columns older)"
DIFF_SOURCE = "IZ BiH Takvim 2025 and vaktija.ba: monthly town differences"

# Town points (GeoNames) for the places places.json does not hold: the Takvim's three extra BiH municipalities
# (geonames.org search, read 2026-10-15) and the mesihat towns (GeoNames, as in the 2026-10-11 research).
TOWN_POINTS = {
    "Bosanska Kostajnica": (45.219559, 16.539292), "Teočak": (44.603207, 18.979669), "Ustikolina": (43.583384, 18.79106),
    "Zagreb": (45.8144, 15.978), "Rijeka": (45.3431, 14.4092), "Osijek": (45.5511, 18.6939), "Split": (43.5089, 16.4392),
    "Dubrovnik": (42.6481, 18.0922), "Ljubljana": (46.0511, 14.5051), "Maribor": (46.5547, 15.6459),
    "Velenje": (46.3592, 15.1103), "Koper": (45.5481, 13.7302), "Jesenice": (46.4367, 14.0526),
    "Beograd": (44.80401, 20.46513), "Novi Sad": (45.25167, 19.83694), "Subotica": (46.1, 19.66667),
    "Preševo": (42.309167, 21.649167), "Niš": (43.32472, 21.90333),
}
# Arabic names (transliterations; the catalogue's Arabic labels may replace them in M5).
NAME_AR = {
    "Banovići": "بانوفيتشي", "Banja Luka": "بانيا لوكا", "Bihać": "بيهاتش", "Bijeljina": "بييلينا", "Bileća": "بيليتشا",
    "Bosanski Brod": "بوسانسكي برود", "Bosanska Dubica": "بوسانسكا دوبيتسا", "Bosanska Gradiška": "بوسانسكا غراديشكا",
    "Bosansko Grahovo": "بوسانسكو غراهوفو", "Bosanska Krupa": "بوسانسكا كروبا", "Bosanski Novi": "بوسانسكي نوفي",
    "Bosanski Petrovac": "بوسانسكي بيتروفاتس", "Bosanski Šamac": "بوسانسكي شاماتس", "Bratunac": "براتوناتس",
    "Brčko": "برتشكو", "Breza": "بريزا", "Bugojno": "بوغوينو", "Busovača": "بوسوفاتشا", "Bužim": "بوجيم",
    "Cazin": "تسازين", "Čajniče": "تشاينيتشه", "Čapljina": "تشابلينا", "Čelić": "تشيليتش", "Čelinac": "تشيليناتس",
    "Čitluk": "تشيتلوك", "Derventa": "ديرفينتا", "Doboj": "دوبوي", "Donji Vakuf": "دوني فاكوف", "Drvar": "درفار",
    "Foča": "فوتشا", "Fojnica": "فوينيتسا", "Gacko": "غاتسكو", "Glamoč": "غلاموتش", "Goražde": "غوراجده",
    "Gornji Vakuf": "غورني فاكوف", "Gračanica": "غراتشانيتسا", "Gradačac": "غراداتشاتس", "Grude": "غروده",
    "Hadžići": "هادجيتشي", "Han-Pijesak": "هان بييساك", "Hlivno": "ليفنو", "Ilijaš": "إيلياش", "Jablanica": "يابلانيتسا",
    "Jajce": "يايتسه", "Kakanj": "كاكاني", "Kalesija": "كاليسيا", "Kalinovik": "كالينوفيك", "Kiseljak": "كيسيلياك",
    "Kladanj": "كلاداني", "Ključ": "كليوتش", "Konjic": "كونييتس", "Kotor-Varoš": "كوتور فاروش", "Kreševo": "كريشيفو",
    "Kupres": "كوبريس", "Laktaši": "لاكتاشي", "Lopare": "لوباره", "Lukavac": "لوكافاتس", "Ljubinje": "ليوبينيه",
    "Ljubuški": "ليوبوشكي", "Maglaj": "ماغلاي", "Modriča": "مودريتشا", "Mostar": "موستار", "Mrkonjić-Grad": "مركونيتش غراد",
    "Neum": "نيوم", "Nevesinje": "نيفيسينيه", "Novi Travnik": "نوفي ترافنيك", "Odžak": "أودجاك", "Olovo": "أولوفو",
    "Orašje": "أوراشيه", "Pale": "باله", "Posušje": "بوسوشيه", "Prijedor": "بريدور", "Prnjavor": "برنيافور",
    "Prozor": "بروزور", "Rogatica": "روغاتيتسا", "Rudo": "رودو", "Sanski Most": "سانسكي موست", "Sarajevo": "سراييفو",
    "Skender-Vakuf": "سكندر فاكوف", "Sokolac": "سوكولاتس", "Srbac": "سرباتس", "Srebrenica": "سريبرينيتسا",
    "Srebrenik": "سريبرينيك", "Stolac": "ستولاتس", "Šekovići": "شيكوفيتشي", "Šipovo": "شيبوفو",
    "Široki Brijeg": "شيروكي بريغ", "Teslić": "تيسليتش", "Tešanj": "تيشاني", "Tomislav-Grad": "توميسلافغراد",
    "Travnik": "ترافنيك", "Trebinje": "تريبينيه", "Trnovo": "ترنوفو", "Tuzla": "توزلا", "Ugljevik": "أوغلييفيك",
    "Vareš": "فاريش", "Velika Kladuša": "فيليكا كلادوشا", "Visoko": "فيسوكو", "Višegrad": "فيشيغراد", "Vitez": "فيتيز",
    "Vlasenica": "فلاسينيتسا", "Zavidovići": "زافيدوفيتشي", "Zenica": "زينيتسا", "Zvornik": "زفورنيك", "Žepa": "جيبا",
    "Žepče": "جيبتشه", "Živinice": "جيفينيتسه", "Bosanska Kostajnica": "بوسانسكا كوستاينيتسا", "Teočak": "تيوتشاك",
    "Ustikolina": "أوستيكولينا",
    "Bijelo Polje": "بييلو بولييه", "Gusinje": "غوسينيه", "Plav": "بلاف", "Pljevlja": "بليفليا", "Rožaje": "روجايه",
    "Nova Varoš": "نوفا فاروش", "Novi Pazar": "نوفي بازار", "Priboj": "بريبوي", "Prijepolje": "بريبولييه",
    "Sjenica": "سيينيتسا", "Tutin": "توتين", "Beograd": "بلغراد", "Novi Sad": "نوفي ساد", "Subotica": "سوبوتيتسا",
    "Preševo": "بريشيفو", "Niš": "نيش",
    "Zagreb": "زغرب", "Rijeka": "رييكا", "Osijek": "أوسييك", "Split": "سبليت", "Dubrovnik": "دوبروفنيك",
    "Ljubljana": "ليوبليانا", "Maribor": "ماريبور", "Velenje": "فيلينيه", "Koper": "كوبر", "Jesenice": "ييسينيتسه",
}
# Documented misprints (8.3), counted apart: Koper's February evening is printed 12, as in January, between the
# January 12 and March 19 of a smooth column; the rule gives 15-16 at any point near Koper.
QUIRKS = {("Koper", 2)}


def log(text: str = "") -> None:
    print(text, flush=True)


def slug(name: str) -> str:
    text = name.replace("đ", "dj").replace("Đ", "Dj")
    text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode()
    return "-".join(text.lower().replace("-", " ").split())


# ---- data -------------------------------------------------------------------------------------------------------

def load(name: str):
    return json.loads((DATA / name).read_text(encoding="utf-8"))


def table_rows(vaktija: dict) -> dict[tuple[int, int], list[int]]:
    """(month, day) -> the six CET minutes of Sarajevo's perpetual table."""
    return {(mi + 1, di + 1): [v // 60 for v in x["vakat"]]
            for mi, m in enumerate(vaktija["vaktija"]["months"]) for di, x in enumerate(m["days"])}


def dates(rows, year: int, months=None):
    out = []
    for (m, d), mins in sorted(rows.items()):
        if (m, d) == (2, 29) and not calendar.isleap(year):
            continue
        if months is not None and m not in months:
            continue
        out.append((datetime.date(year, m, d), mins))
    return out


def cet_epoch(day: datetime.date, minute: int) -> int:
    return int((datetime.datetime(day.year, day.month, day.day) - datetime.datetime(1970, 1, 1)).total_seconds()) - 3600 + 60 * minute


# ---- fast raw events (the reference's own primitives, S2-S7) ------------------------------------------------------

_SUNS: dict = {}


def sun_of(day, model, mode):
    key = (day, model, mode)
    if key not in _SUNS:
        cyc = ref.Cycle(day)                    # U = D at Sarajevo
        _SUNS[key] = (cyc, ref.SunFunction(ref.SUN_MODELS[model], mode if mode != "LOCAL_MIDNIGHT_SHADOW" else "EVENT_INSTANT", cyc.jd0))
    return _SUNS[key]


def transit_at(day, lng, model, mode):
    cyc, sun = sun_of(day, model, mode)
    return cyc, sun, ref.transit(cyc, sun, lng)


def event_at(day, lat, lng, alt, side, model, mode):
    cyc, sun, t0 = transit_at(day, lng, model, mode)
    return cyc.instant(ref.event(cyc, sun, lat, lng, alt, side, t0))


def asr_at(day, lat, lng, model, mode, factor):
    cyc, sun, t0 = transit_at(day, lng, model, mode)
    if mode == "LOCAL_MIDNIGHT_SHADOW":
        decl = ref.SUN_MODELS[model](ref.jd_of_instant(common.local_to_epoch(TZ, day, "0:00")))[0]
    else:
        decl = sun(cyc.jd0 + t0 / 24)[0]
    alt = ref.atand(1 / (factor + ref.tand(abs(lat - decl))))
    return cyc.instant(ref.event(cyc, sun, lat, lng, alt, 1, t0))


def depression(day, lat, lng, t, model, mode):
    cyc, sun = sun_of(day, model, mode)
    return -ref.altitude(cyc, sun, lat, lng, cyc.hours(t))


# ---- windows -----------------------------------------------------------------------------------------------------

def best_cover(intervals, lo, hi, prefer):
    """(count, value): the most half-open intervals covering one value within [lo, hi]; the value is `prefer` where it
    lies in a best plateau, else the middle of the best plateau nearest to it."""
    points = []
    for a, b in intervals:
        a, b = max(a, lo), min(b, hi)
        if a < b:
            points += [(a, 1), (b, -1)]
    points.sort()
    best, cur, plateaus = 0, 0, []
    for i, (x, d) in enumerate(points):
        cur += d
        nxt = points[i + 1][0] if i + 1 < len(points) else None
        if nxt is None or nxt <= x:
            continue
        if cur > best:
            best, plateaus = cur, [[x, nxt]]
        elif cur == best and best > 0:
            if abs(plateaus[-1][1] - x) < 1e-12:
                plateaus[-1][1] = nxt
            else:
                plateaus.append([x, nxt])
    if not plateaus:
        return 0, prefer
    for a, b in plateaus:
        if a <= prefer < b:
            return best, prefer
    a, b = min(plateaus, key=lambda p: min(abs(p[0] - prefer), abs(p[1] - prefer)))
    return best, (a + b) / 2


def exact(t, offset_min, mode, pub) -> bool:
    return ref.round_instant(t + 60 * offset_min, mode) == pub


def pick(candidates, default_key):
    """The candidate with the most windows; the default (its key) unless another covers clearly more."""
    best = max(candidates, key=lambda c: c[0])
    default = next(c for c in candidates if c[1] == default_key)
    return default if best[0] <= default[0] * CLEARLY else best


# ---- 1. Sarajevo's base table -------------------------------------------------------------------------------------

def year_scan(rows) -> list[tuple]:
    """Sunrise and Maghrib at the Takvim's point: per year 1900-2030 and evaluation, the windows one continuous
    offset covers (a reference-year search for the older columns)."""
    out = []
    for year in range(1900, 2031):
        items = dates(rows, year)
        res = [year]
        for mode in ("EVENT_INSTANT", "DAILY_0H_UT"):
            for k, side in ((1, -1), (4, 1)):
                xs = sorted((cet_epoch(day, mins[k]) - event_at(day, PHI0, LAM0, -0.8333, side, "MEEUS", mode)) / 60
                            for day, mins in items)
                j, best = 0, 0
                for i in range(len(xs)):
                    while xs[i] - xs[j] >= 1.0:
                        j += 1
                    best = max(best, i - j + 1)
                res.append(best)
        out.append(tuple(res))
    return out


def fit_base(rows, months=None, model="MEEUS", verbose=True) -> dict:
    """Stages 1-4. The older columns (sunrise, Dhuhr, Asr, Maghrib) on APP_YEARS pooled, Fajr and Isha at NEW_YEAR."""
    old = [x for y in APP_YEARS for x in dates(rows, y, months)]
    new = dates(rows, NEW_YEAR, months)
    P = {e: [(day, cet_epoch(day, mins[k])) for day, mins in (new if e in ("FAJR", "ISHA") else old)] for k, e in enumerate(EVENTS)}
    p = {"evals": {}, "modes": {}, "offsets": {}, "counts": {}}
    say = log if verbose else (lambda *_: None)
    # Stage 0: one evaluation for all six times (E4), at the Takvim's point with the published angles and offsets,
    # NEAREST; it is every later stage's default, which another evaluation must beat clearly.
    totals = {}
    for ev in EVALS:
        n = 0
        for e in EVENTS:
            for day, pb in P[e]:
                if e == "DHUHR":
                    cyc, _, t0 = transit_at(day, LAM0, model, ev)
                    t = cyc.instant(t0)
                elif e == "ASR":
                    t = asr_at(day, PHI0, LAM0, model, ev, 1)
                else:
                    alt, side = {"FAJR": (-18.0, -1), "SUNRISE": (-0.8333, -1), "MAGHRIB": (-0.8333, 1), "ISHA": (-16.0, 1)}[e]
                    t = event_at(day, PHI0, LAM0, alt, side, model, ev)
                n += exact(t, PUBLISHED[e], "NEAREST", pb)
        totals[ev] = n
    default_ev = max(EVALS, key=lambda ev: (totals[ev] > totals["EVENT_INSTANT"] * CLEARLY or ev == "EVENT_INSTANT", totals[ev]))
    p["stage0"] = default_ev
    say(f"    stage 0, one evaluation for all six: {default_ev} (" + ", ".join(f"{ev} {n}" for ev, n in totals.items())
        + f" of {sum(len(v) for v in P.values())} minutes)")
    # Stage 1: Dhuhr -> evaluation, rounding, offset, longitude (within 0.05 deg of 18 26').
    cands = []
    for ev in EVALS:
        t0s = []
        for day, pb in P["DHUHR"]:
            cyc, _, t0 = transit_at(day, LAM0, model, ev)
            t0s.append((cyc.instant(t0), pb))
        for mode in MODES:
            lo, hi = WINDOW[mode]
            for off in (0, 1, 2):
                ivs = [(LAM0 + (t + 60 * off - pb - hi) / 240, LAM0 + (t + 60 * off - pb - lo) / 240) for t, pb in t0s]
                n, lng = best_cover(ivs, LAM0 - 0.05, LAM0 + 0.05, LAM0)
                cands.append((n, (ev, mode, off), lng))
    n, (ev, mode, off), lng = pick(cands, (default_ev, "NEAREST", PUBLISHED["DHUHR"]))
    p["evals"]["DHUHR"], p["modes"]["DHUHR"], p["offsets"]["DHUHR"], p["counts"]["DHUHR"] = ev, mode, off, n
    p["lng"] = round(lng, 5)
    say(f"    stage 1, Dhuhr: {ev} {mode} {off:+d}, longitude {p['lng']:.5f} ({n} of {len(P['DHUHR'])} windows; "
        + ", ".join(f"{x} at best {max(c[0] for c in cands if c[1][0] == x)}" for x in EVALS) + ")")

    # Stage 2: the latitude (within 0.05 deg of 43 52') from sunrise and Maghrib (each at its best evaluation,
    # rounding and offset) and Fajr and Isha (published angles, offset 0); then each horizon event's rule there.
    def horizon_cands(e, side, offs, lat):
        out = []
        for ev in EVALS:
            ts = [(event_at(day, lat, p["lng"], -0.8333, side, model, ev), pb) for day, pb in P[e]]
            for mode in MODES:
                for off in offs:
                    out.append((sum(1 for t, pb in ts if exact(t, off, mode, pb)), (ev, mode, off)))
        return out

    def angle_best(e, deg, lat):
        side = -1 if e == "FAJR" else 1
        best = 0
        for ev in ("EVENT_INSTANT", "DAILY_0H_UT"):
            ts = [(event_at(day, lat, p["lng"], -deg, side, model, ev), pb) for day, pb in P[e]]
            for mode in MODES:
                best = max(best, sum(1 for t, pb in ts if exact(t, 0, mode, pb)))
        return best

    best_lat = None
    for k in range(-10, 11):
        lat = PHI0 + k * 0.005
        total = max(c[0] for c in horizon_cands("SUNRISE", -1, range(-8, -3), lat))
        total += max(c[0] for c in horizon_cands("MAGHRIB", 1, range(4, 9), lat))
        total += angle_best("FAJR", 18.0, lat) + angle_best("ISHA", 16.0, lat)
        key = (total, -abs(lat - PHI0))
        if best_lat is None or key > best_lat[0]:
            best_lat = (key, lat)
    p["lat"] = round(best_lat[1], 5)
    for e, side, offs in (("SUNRISE", -1, range(-8, -3)), ("MAGHRIB", 1, range(4, 9))):
        cands = horizon_cands(e, side, offs, p["lat"])
        n, (ev, mode, off) = pick(cands, (default_ev, "NEAREST", PUBLISHED[e]))
        p["evals"][e], p["modes"][e], p["offsets"][e], p["counts"][e] = ev, mode, off, n
        say(f"    stage 2, {e.lower()}: {ev} {mode} {off:+d} ({n} of {len(P[e])}; NEAREST {PUBLISHED[e]:+d}: "
            + ", ".join(f"{x} {next(c[0] for c in cands if c[1] == (x, 'NEAREST', PUBLISHED[e]))}" for x in EVALS) + ")")
    say(f"    stage 2, latitude {p['lat']:.5f} ({best_lat[0][0]} windows over the four times)")

    # Stage 3: Fajr and Isha -> evaluation, rounding, angle (to 0.1 deg, the published angle where it is among the best).
    for e, plan_angle in (("FAJR", 18.0), ("ISHA", 16.0)):
        cands = []
        for ev in EVALS:
            for mode in MODES:
                lo, hi = WINDOW[mode]
                ivs = []
                for day, pb in P[e]:
                    da = depression(day, p["lat"], p["lng"], pb + lo, model, ev)
                    db = depression(day, p["lat"], p["lng"], pb + hi, model, ev)
                    ivs.append((min(da, db), max(da, db)))
                n, angle = best_cover(ivs, plan_angle - 2, plan_angle + 2, plan_angle)
                cands.append((n, (ev, mode), angle, ivs))
        best = pick([(c[0], c[1], c) for c in cands], (default_ev, "NEAREST"))[2]
        n, (ev, mode), angle, ivs = best
        rounded = round(angle, 1)
        p["evals"][e], p["modes"][e], p["offsets"][e] = ev, mode, 0
        p["counts"][e] = sum(1 for a, b in ivs if a <= rounded < b)
        p[e.lower()] = rounded
        p["window_" + e] = f"{angle:.3f} ({n})"
        ei = max(c[0] for c in cands if c[1][0] == "EVENT_INSTANT")
        say(f"    stage 3, {e.lower()}: {ev} {mode} {rounded} deg ({p['counts'][e]} of {len(ivs)}; the windows' own angle "
            f"{angle:.3f} covers {n}; EVENT_INSTANT at best {ei})")

    # Stage 4: Asr -> factor, evaluation, rounding, offset.
    cands = []
    for factor in (1, 2):
        for ev in ASR_EVALS:
            ts = [(asr_at(day, p["lat"], p["lng"], model, ev, factor), pb) for day, pb in P["ASR"]]
            for mode in MODES:
                for off in range(-2, 3):
                    cands.append((sum(1 for t, pb in ts if exact(t, off, mode, pb)), (factor, ev, mode, off)))
    n, (factor, ev, mode, off) = pick(cands, (1, default_ev, "NEAREST", 0))
    p["factor"] = factor
    p["evals"]["ASR"], p["modes"]["ASR"], p["offsets"]["ASR"], p["counts"]["ASR"] = ev, mode, off, n
    say(f"    stage 4, Asr: factor {factor} {ev} {mode} {off:+d} ({n} of {len(P['ASR'])}; factor 1 NEAREST +0: "
        + ", ".join(f"{x} {next(c[0] for c in cands if c[1] == (1, x, 'NEAREST', 0))}" for x in ASR_EVALS) + ")")
    return p


def method_of(p: dict | None) -> dict:
    """bosnia_iz's PrayerMethod JSON: the fitted rules, or (p None) the preset as it stood before this fit."""
    if p is None:
        return common.method_json(
            id=AUTHORITY, fajr={"type": "angle", "degrees": 18.0}, isha={"type": "angle", "degrees": 16.0},
            offsets={e: v for e, v in PUBLISHED.items() if v}, takvimMeans=TAKVIM_MEANS,
            evidence={"kind": "PUBLISHED", "source": "preset"})
    return common.method_json(
        id=AUTHORITY,
        fajr={"type": "angle", "degrees": p["fajr"]}, isha={"type": "angle", "degrees": p["isha"]},
        offsets={e: p["offsets"][e] for e in EVENTS if p["offsets"][e]},
        rounding={e: p["modes"][e] for e in EVENTS},
        sunEvaluation={e: p["evals"][e] for e in EVENTS if p["evals"][e] != "EVENT_INSTANT"},
        asrShadow=p["factor"], takvimMeans=TAKVIM_MEANS,
        evidence={"kind": "PUBLISHED", "source": "The Islamic Community of Bosnia and Herzegovina's Takvim"},
    )


def base_zone(town: tuple[float, float], p: dict | None) -> dict:
    """Sarajevo's zone: its town as the point, the fitted computing point as E6 morning and evening points."""
    fields = {"authority": AUTHORITY}
    if p is not None:
        fields["morningPoint"] = common.point(p["lat"], p["lng"])
        fields["eveningPoint"] = common.point(p["lat"], p["lng"])
    return common.zone_json("sarajevo", town[0], town[1], **fields)


def app_minutes(method: dict, town, day: datetime.date, zone: dict, base: dict | None = None) -> dict[str, int]:
    """The app's minutes (epoch minutes) for a zone on a day: common.compute, the independent reference engine."""
    out = common.compute(common.case(method, town[0], town[1], TZ, day, zone=zone, base_zone=base, country="BA"))
    return {e: out[e][1] // 60 for e in EVENTS}


def score_base(method, zone, town, rows, year, months=None) -> common.Score:
    sc = common.Score()
    for day, mins in dates(rows, year, months):
        a = app_minutes(method, town, day, zone)
        for k, e in enumerate(EVENTS):
            sc.add(e, day, a[e], cet_epoch(day, mins[k]) // 60)
    return sc


def merge(into: common.Score, sc: common.Score) -> None:
    for e in EVENTS:
        into.exact[e] += sc.exact[e]
        into.within1[e] += sc.within1[e]
        into.total[e] += sc.total[e]
        for m in range(12):
            into.months[e][m] += sc.months[e][m]
    into.worst = max(into.worst, sc.worst)
    into.misses += sc.misses


def shares(sc: common.Score) -> str:
    ex, w1 = sc.shares(), sc.within1_shares()
    return " ".join(f"{e[:4]} {100 * ex[e]:5.1f}/{100 * w1[e]:5.1f}" for e in EVENTS) + f"  max {sc.worst}"


def describe(p: dict) -> str:
    return (f"point {p['lat']:.5f},{p['lng']:.5f} Fajr {p['fajr']} Isha {p['isha']} Asr factor {p['factor']}; "
            + ", ".join(f"{e[:4]} {p['evals'][e]} {p['modes'][e]} {p['offsets'][e]:+d}" for e in EVENTS))


# ---- 2. the Takvim's rule and the places' points ------------------------------------------------------------------

def whole(x: float) -> int:
    return math.floor(x + 0.5)


def derive(lat: float, lng: float, month: int) -> tuple[int, int, int]:
    """(morning, Dhuhr, evening) for a month 1-12, as PrayerEngine.takvim and the reference's takvim_shift compute them."""
    north = lat >= PHI0
    tau = TAKVIM_MEANS[(0 if north else 12) + month - 1]
    g = 1.1 if north else 0.9
    dl = 4.0 * (LAM0 - lng)
    dp = tau * g * (lat - PHI0)
    return whole(dl + dp), whole(dl), whole(dl - dp)


def e5(x: float) -> float:
    return int(round(x * 1e5)) / 1e5


def matches(lat, lng, printed, months=range(12)) -> int:
    lat, lng = e5(lat), e5(lng)
    return sum(1 for m in months for a, b in zip(derive(lat, lng, m + 1), printed[m]) if a == b)


def km(lat1, lng1, lat2, lng2) -> float:
    return math.hypot((lat1 - lat2) * KM_PER_DEG, (lng1 - lng2) * KM_PER_DEG * math.cos(math.radians(lat2)))


def closed_plateaus(ivs):
    """The most closed intervals covering one value: (count, the plateaus where that many do)."""
    pts = []
    for a, b in ivs:
        if a <= b:
            pts += [(a, 0), (b, 1)]
    pts.sort()
    best, cur, plats, start = 0, 0, [], None
    for x, kind in pts:
        if kind == 0:
            cur += 1
            if cur > best:
                best, plats, start = cur, [], x
            elif cur == best:
                start = x
        else:
            if cur == best and start is not None:
                plats.append((start, x))
                start = None
            cur -= 1
    return best, plats


def fit_point(town, printed, months=range(12), radius_km=15.0, step=0.0005):
    """The point within radius_km of the town whose derived differences equal the most printed ones (each kept MARGIN
    inside its window), nearest the town: longitudes on a 0.0005 deg grid, latitudes exact (windows)."""
    months = list(months)
    tlat, tlng = town
    coslat = math.cos(math.radians(tlat))
    st = tlat - PHI0
    best = None
    k = int(math.ceil(radius_km / (KM_PER_DEG * coslat) / step))
    for i in range(-k, k + 1):
        lng = tlng + i * step
        dx = (lng - tlng) * KM_PER_DEG * coslat
        if abs(dx) > radius_km:
            continue
        dy = math.sqrt(max(radius_km ** 2 - dx ** 2, 0.0)) / KM_PER_DEG
        dl = 4.0 * (LAM0 - lng)
        dhuhr = sum(1 for m in months if abs(dl - printed[m][1]) <= 0.5 - MARGIN)
        for north in (True, False):
            lo_s, hi_s = (max(0.0, st - dy), st + dy) if north else (st - dy, min(-1.5e-5, st + dy))
            if lo_s > hi_s:
                continue
            ivs = []
            for m in months:
                c = TAKVIM_MEANS[(0 if north else 12) + m] * (1.1 if north else 0.9)
                for target, sign in ((printed[m][0], 1), (printed[m][2], -1)):
                    a = (target - 0.5 + MARGIN - dl) / (sign * c)
                    b = (target + 0.5 - MARGIN - dl) / (sign * c)
                    ivs.append((max(min(a, b), lo_s), min(max(a, b), hi_s)))
            n, plats = closed_plateaus(ivs)
            for a, b in plats or [(lo_s, hi_s)]:
                s = min(max(st, a), b)
                key = (n + dhuhr, -km(PHI0 + s, lng, tlat, tlng))
                if best is None or key > best[0]:
                    best = (key, PHI0 + s, lng)
    _, lat, lng = best
    lat, lng = e5(lat), e5(lng)
    return lat, lng, matches(lat, lng, printed, months), km(lat, lng, tlat, tlng)


ODD = [m for m in range(12) if m % 2 == 0]       # January, March, ... (month index 0, 2, ...)
EVEN = [m for m in range(12) if m % 2 == 1]


def fit_place(town, printed):
    """The shipped point (all months) and the month-split points, the search radius chosen by the month split."""
    split = {}
    for radius in RADIUS_KM:
        a = fit_point(town, printed, ODD, radius)
        b = fit_point(town, printed, EVEN, radius)
        split[radius] = (matches(a[0], a[1], printed, EVEN) + matches(b[0], b[1], printed, ODD), a, b)
    radius = RADIUS_KM[1] if split[RADIUS_KM[1]][0] > split[RADIUS_KM[0]][0] else RADIUS_KM[0]
    lat, lng, n, dist = fit_point(town, printed, range(12), radius)
    held, a, b = split[radius]
    return {"lat": lat, "lng": lng, "matches": n, "km": dist, "radius": radius, "held": held,
            "odd_point": (a[0], a[1]), "even_point": (b[0], b[1])}


def offsets_record(name: str, printed, fit: dict, year: int = SCORE_YEAR) -> tuple[dict, common.Score, int]:
    """A Takvim zone's own FitStats (9.5): per day of the year, the offsets derived at the point fitted without that
    month against the printed ones (the base zone's minute cancels); the zone's misprinted months apart."""
    sc, quirk = common.Score(), 0
    day = datetime.date(year, 1, 1)
    while day.year == year:
        m = day.month - 1
        if (name, day.month) in QUIRKS:
            quirk += 1
        else:
            point = fit["even_point"] if m in ODD else fit["odd_point"]     # fitted on the other half
            got = derive(point[0], point[1], day.month)
            want = printed[m]
            for e, k in zip(EVENTS, (0, 0, 1, 2, 2, 2)):
                sc.add(e, day, got[k], want[k])
        day += datetime.timedelta(days=1)
    days = 366 if calendar.isleap(year) else 365
    stats = common.fit_stats(
        [common.sample(DIFF_SOURCE, datetime.date(year, 1, 1), datetime.date(year, 12, 31), days, 1)],
        [common.sample(DIFF_SOURCE + ", held out by month", datetime.date(year, 1, 1), datetime.date(year, 12, 31), days, 1,
                       month_split=True)],
        sc, quirk_days=quirk)
    return stats, sc, quirk


# ---- main ---------------------------------------------------------------------------------------------------------

def main() -> int:
    started = time.time()
    vaktija = load("vaktija.json")
    before = load("vaktija_499a8ec.json")
    places = load("places.json")
    mesihat = load("mesihat.json")
    extra = load("takvim_extra.json")
    rows = table_rows(vaktija)
    names = vaktija["locations"]
    assert len(rows) == 366 and names[77] == "Sarajevo", (len(rows), names[77])
    sarajevo = (places[77]["lat"], places[77]["lon"])
    log(f"bosnia_iz: Sarajevo's table, {len(rows)} rows; {len(names)} vaktija.ba places; {len(mesihat) - 1} mesihat and "
        f"{len(extra) - 1} further Takvim places")

    # The 2024-12-30 commit ("nova vremena 25") changed only Fajr and Isha.
    old_rows = table_rows(before)
    changed = {e: sum(1 for key in rows if rows[key][k] != old_rows[key][k]) for k, e in enumerate(EVENTS)}
    log("rows changed by the 2024-12-30 commit: " + ", ".join(f"{e[:4]} {n}" for e, n in changed.items()))

    log("reference year of the older columns (sunrise, Maghrib) at 43 52'N 18 26'E, windows one continuous offset covers:")
    scan = year_scan(rows)
    for label, i in (("sun at the event", 1), ("sun at 0h UT", 3)):
        best_sr = sorted(scan, key=lambda r: -r[i])[:3]
        best_ss = sorted(scan, key=lambda r: -r[i + 1])[:3]
        joint = max(scan, key=lambda r: r[i] + r[i + 1])
        log(f"  {label}: sunrise best {[(r[0], r[i]) for r in best_sr]}, Maghrib best {[(r[0], r[i + 1]) for r in best_ss]}, "
            f"both {joint[0]} ({joint[i]} + {joint[i + 1]} of 365 each)")
    log(f"  ({time.time() - started:.0f} s)")

    # 1. The base table.
    halves = {"odd": [m + 1 for m in ODD], "even": [m + 1 for m in EVEN]}
    fitted = {}
    for name, months in halves.items():
        log(f"Sarajevo, fit on the {name} months:")
        fitted[name] = fit_base(rows, months)
        log("  " + describe(fitted[name]))
    log("Sarajevo, fit on all months:")
    final = fit_base(rows)
    log("  " + describe(final))
    log(f"  ({time.time() - started:.0f} s)")
    method = method_of(final)
    zone_final = base_zone(sarajevo, final)

    held = common.Score()
    for name, other in (("odd", "even"), ("even", "odd")):
        merge(held, score_base(method_of(fitted[other]), base_zone(sarajevo, fitted[other]), sarajevo, rows, SCORE_YEAR,
                               halves[name]))
    log(f"Sarajevo held out (month-split), {SCORE_YEAR} against the table by date, exact/within 1 (%):")
    log("  " + shares(held))
    log("Sarajevo, the final profile in-sample and in later years (table by date):")
    for year in APP_YEARS:
        log(f"  {year}: " + shares(score_base(method, zone_final, sarajevo, rows, year)))
    log(f"  {NEW_YEAR} (Fajr and Isha's reference year): " + shares(score_base(method, zone_final, sarajevo, rows, NEW_YEAR)))
    log(f"  {SCORE_YEAR}, the final rules at GeoNames' Sarajevo (no E6 point): "
        + shares(score_base(method, base_zone(sarajevo, None), sarajevo, rows, SCORE_YEAR)))
    # Where the older columns put Sarajevo: sunrise's and Maghrib's latitude (the sun at 0h UT, APP_YEARS pooled, the
    # windows one continuous offset covers).
    pref = {}
    for k, side, name in ((1, -1, "sunrise"), (4, 1, "Maghrib")):
        best = None
        for i in range(-20, 21):
            lat = PHI0 + i * 0.01
            xs = sorted((cet_epoch(day, mins[k]) - event_at(day, lat, final["lng"], -0.8333, side, "MEEUS", "DAILY_0H_UT")) / 60
                        for y in APP_YEARS for day, mins in dates(rows, y))
            j, n = 0, 0
            for a in range(len(xs)):
                while xs[a] - xs[j] >= 1.0:
                    j += 1
                n = max(n, a - j + 1)
            if best is None or n > best[0]:
                best = (n, lat)
        pref[name] = best
    log("  the older columns' own latitude (sun at 0h UT, 2026-2029): " + ", ".join(
        f"{name} {lat:.2f} N ({n} of {4 * 365 + 1})" for name, (n, lat) in pref.items()))
    preset_zone = common.zone_json("sarajevo", PHI0, LAM0, authority=AUTHORITY)
    log("the preset before this fit (EVENT_INSTANT, NEAREST, 43 52'N 18 26'E):")
    for year in APP_YEARS:
        log(f"  {year}: " + shares(score_base(method_of(None), preset_zone, (PHI0, LAM0), rows, year)))
    base_stats = common.fit_stats(
        [common.sample(TABLE_SOURCE + ": Fajr and Isha at 2025, the other columns on 2026-2029", datetime.date(NEW_YEAR, 1, 1),
                       datetime.date(APP_YEARS[-1], 12, 31), 366, 1)],
        [common.sample(TABLE_SOURCE + " read by date in 2026, held out by month", datetime.date(SCORE_YEAR, 1, 1),
                       datetime.date(SCORE_YEAR, 12, 31), held.total["DHUHR"], 1, month_split=True)],
        held)
    log(f"  ({time.time() - started:.0f} s)")

    # 2. The Takvim places.
    takvim = []          # (name, country, town, printed)
    for i, name in enumerate(names):
        p = places[i]
        if i == 77 or p["cc"] == "ME":
            continue
        printed = [[x["vakat"][0] // 60, x["vakat"][2] // 60, x["vakat"][4] // 60] for x in vaktija["differences"][i]["months"]]
        months = vaktija["differences"][i]["months"]
        assert all(x["vakat"][0] == x["vakat"][1] and x["vakat"][3] == x["vakat"][4] == x["vakat"][5] for x in months), name
        takvim.append((name, p["cc"], (p["lat"], p["lon"]), printed))
    for source in (mesihat, extra):
        for name, value in source.items():
            if name.startswith("_") or (source is mesihat and name == "Novi Pazar"):   # Novi Pazar: vaktija.ba's, equal
                continue
            takvim.append((name, value["cc"], TOWN_POINTS[name], value["v"]))
    np_i = names.index("Novi Pazar")
    assert [[x["vakat"][0] // 60, x["vakat"][2] // 60, x["vakat"][4] // 60] for x in vaktija["differences"][np_i]["months"]] \
        == mesihat["Novi Pazar"]["v"]
    log(f"Takvim places: {len(takvim)} ({', '.join(f'{cc} {sum(1 for t in takvim if t[1] == cc)}' for cc in ('BA', 'HR', 'SI', 'RS'))})")
    zones_fit = {}
    per_cc = {}
    for name, cc, town, printed in takvim:
        f = fit_place(town, printed)
        zones_fit[name] = f
        town_matches = matches(town[0], town[1], printed)
        acc = per_cc.setdefault(cc, {"n": 0, "of": 0, "held": 0, "town": 0, "km": []})
        acc["n"] += f["matches"]
        acc["of"] += 36
        acc["held"] += f["held"]
        acc["town"] += town_matches
        acc["km"].append(f["km"])
    for cc, acc in per_cc.items():
        kms = sorted(acc["km"])
        log(f"  {cc}: fitted points give {acc['n']} of {acc['of']} printed values, the towns' own points {acc['town']}; "
            f"held out (month-split) {acc['held']}; median {kms[len(kms) // 2]:.1f} km from the town, at most {kms[-1]:.1f}")
    for name, cc, town, printed in takvim:
        f = zones_fit[name]
        if f["matches"] < 36 or f["radius"] > RADIUS_KM[0]:
            miss = []
            for m in range(12):
                got = derive(f["lat"], f["lng"], m + 1)
                for label, a, b in zip(("morning", "Dhuhr", "evening"), got, printed[m]):
                    if a != b:
                        miss.append(f"{calendar.month_abbr[m + 1]} {label} {a - b:+d}")
            log(f"  {name} ({cc}): {f['matches']} of 36 at {f['km']:.1f} km (search {f['radius']:.0f} km): {', '.join(miss) or 'all'}")
    log(f"  ({time.time() - started:.0f} s)")

    # The derived offsets through the reference engine (zone and base zone), on 2026 days, at the fitted points (the
    # proposed TAKVIM-at-E6 reading); and what the current reading (the zone point, the town) gives.
    check = ["Trebinje", "Bihać", "Tutin", "Zagreb", "Ljubljana", "Beograd"]
    agree = total = 0
    now = {"n": 0, "of": 0}
    for name, cc, town, printed in takvim:
        if name not in check:
            continue
        f = zones_fit[name]
        emulated = common.zone_json(slug(name), f["lat"], f["lng"], authority=AUTHORITY, baseZoneId="sarajevo", offsetRule="TAKVIM")
        current = common.zone_json(slug(name), town[0], town[1], authority=AUTHORITY, baseZoneId="sarajevo", offsetRule="TAKVIM",
                                   morningPoint=common.point(f["lat"], f["lng"]), eveningPoint=common.point(f["lat"], f["lng"]))
        for day in (datetime.date(SCORE_YEAR, m, 15) for m in range(1, 13)):
            base_min = app_minutes(method, sarajevo, day, zone_final)
            got = app_minutes(method, town, day, emulated, zone_final)
            cur = app_minutes(method, town, day, current, zone_final)
            want = derive(f["lat"], f["lng"], day.month)
            for e, k in zip(EVENTS, (0, 0, 1, 2, 2, 2)):
                agree += got[e] - base_min[e] == want[k]
                total += 1
                now["n"] += cur[e] - base_min[e] == printed[day.month - 1][k]
                now["of"] += 1
    log(f"reference engine, zone + base zone: derived shifts equal fit.py's derive() on {agree} of {total} times "
        f"({', '.join(check)}, the 15th of each month, fitted point read as the TAKVIM point)")
    log(f"  with the TAKVIM rule as it reads today (the zone's town point): {now['n']} of {now['of']} equal the printed "
        f"differences")

    # vaktija.ba's 2026 API (summer time applied) against the app's full times, held-out parameters per month.
    log(f"vaktija.ba's 2026 API against the app, exact/within 1 (%): held out (base and points fitted without each "
        f"month), then as shipped:")
    for api_id in (77, 91, 2):
        api = load(f"api_{api_id}_2026.json")
        name = api["lokacija"]
        held_sc, shipped_sc = common.Score(), common.Score()
        for mi, month in enumerate(api["mjesec"]):
            half = "even" if (mi + 1) % 2 == 1 else "odd"          # the base fitted on the other half
            runs = []
            for sc, p_run, pt in ((held_sc, fitted[half], "held"), (shipped_sc, final, "shipped")):
                zb = base_zone(sarajevo, p_run)
                mz = None
                if name != "Sarajevo":
                    f = zones_fit[name]
                    xy = (f["lat"], f["lng"]) if pt == "shipped" else (f["even_point"] if mi in ODD else f["odd_point"])
                    mz = common.zone_json(slug(name), xy[0], xy[1], authority=AUTHORITY, baseZoneId="sarajevo", offsetRule="TAKVIM")
                runs.append((sc, method_of(p_run), zb, mz))
            for di, x in enumerate(month["dan"]):
                day = datetime.date(SCORE_YEAR, mi + 1, di + 1)
                for sc, m_run, zb, mz in runs:
                    a = app_minutes(m_run, sarajevo, day, mz or zb, zb if mz else None)
                    for k, e in enumerate(EVENTS):
                        sc.add(e, day, a[e], common.local_to_epoch(TZ, day, x["vakat"][k]) // 60)
        log(f"  {name}, held out: " + shares(held_sc))
        log(f"  {name}, shipped:  " + shares(shipped_sc))
    log(f"  ({time.time() - started:.0f} s)")

    # 3. Montenegro: vaktija.ba's five towns, one difference all year on all six times.
    montenegro = []
    for i, name in enumerate(names):
        if places[i]["cc"] != "ME":
            continue
        values = {v // 60 for x in vaktija["differences"][i]["months"] for v in x["vakat"]}
        assert len(values) == 1, (name, values)
        montenegro.append((name, (places[i]["lat"], places[i]["lon"]), values.pop()))
    log("Montenegro (published, all year, all six times): " + ", ".join(f"{n} {v:+d}" for n, _, v in montenegro))

    # The asset.
    def named(town):
        return [{"centre": common.point(*town), "catalogueIds": [], "areaKm": 0.0}]

    zones = []
    z = {k: v for k, v in zone_final.items() if k != "authority"}
    z.update({"nameAr": NAME_AR["Sarajevo"], "namedPlaces": named(sarajevo), "fit": base_stats})
    zones.append(z)
    profile = common.Score()
    order = {"BA": 0, "HR": 1, "SI": 2, "RS": 3}
    for name, cc, town, printed in sorted(takvim, key=lambda t: (order[t[1]], slug(t[0]))):
        f = zones_fit[name]
        stats, _, _ = offsets_record(name, printed, f)
        z = {"id": slug(name), "nameAr": NAME_AR[name], "point": common.point(*town),
             "morningPoint": common.point(f["lat"], f["lng"]), "eveningPoint": common.point(f["lat"], f["lng"]),
             "baseZoneId": "sarajevo", "offsetRule": "TAKVIM", "namedPlaces": named(town), "fit": stats}
        if cc != "BA":
            z["country"] = cc
        zones.append(z)
    for name, town, value in sorted(montenegro, key=lambda t: slug(t[0])):
        zones.append({"id": slug(name), "nameAr": NAME_AR[name], "point": common.point(*town), "country": "ME",
                      "offsets": {e: float(value) for e in EVENTS}, "baseZoneId": "sarajevo", "reachKm": ME_REACH_KM[name],
                      "namedPlaces": named(town)})
    assert len(zones) == 1 + len(takvim) + len(montenegro) == 136, len(zones)
    assert len({z["id"] for z in zones}) == len(zones)

    # The profile's record: every zone's times as the app shows them (the base and each point fitted without the
    # month) against the official ones (the table's minute plus the printed difference), 2026.
    base_held = {}
    for name, other in (("odd", "even"), ("even", "odd")):
        m_other, z_other = method_of(fitted[other]), base_zone(sarajevo, fitted[other])
        for day, mins in dates(rows, SCORE_YEAR, halves[name]):
            base_held[day] = (app_minutes(m_other, sarajevo, day, z_other), [cet_epoch(day, x) // 60 for x in mins])
    printed_of = {t[0]: t[3] for t in takvim}
    for day, (app, table) in sorted(base_held.items()):
        m = day.month - 1
        for k, e in enumerate(EVENTS):
            profile.add(e, day, app[e], table[k])
        for name, cc, town, printed in takvim:
            if (name, day.month) in QUIRKS:
                continue
            f = zones_fit[name]
            pt = f["even_point"] if m in ODD else f["odd_point"]
            got = derive(pt[0], pt[1], day.month)
            for k, (e, j) in enumerate(zip(EVENTS, (0, 0, 1, 2, 2, 2))):
                profile.add(e, day, app[e] + got[j], table[k] + printed_of[name][m][j])
        for name, town, value in montenegro:
            for k, e in enumerate(EVENTS):
                profile.add(e, day, app[e] + value, table[k] + value)
    quirk_total = sum(1 for name, _, _, _ in takvim for d in base_held if (name, d.month) in QUIRKS)
    profile_stats = common.fit_stats(
        [common.sample(TABLE_SOURCE, datetime.date(NEW_YEAR, 1, 1), datetime.date(APP_YEARS[-1], 12, 31), 366, 1),
         common.sample(DIFF_SOURCE, datetime.date(SCORE_YEAR, 1, 1), datetime.date(SCORE_YEAR, 12, 31), 365, len(takvim))],
        [common.sample("vaktija.ba Sarajevo table plus the printed differences, read by date in 2026, held out by month",
                       datetime.date(SCORE_YEAR, 1, 1), datetime.date(SCORE_YEAR, 12, 31), len(base_held), len(zones),
                       month_split=True)],
        profile, quirk_days=quirk_total)
    log(f"profile (all {len(zones)} zones, held out by month, 2026), exact/within 1 (%):")
    log("  " + shares(profile))
    path = common.write_asset(AUTHORITY, AUTHORITY, "BA", "LISTED_PLACES", zones, fit=profile_stats, source=SOURCE_LINE)
    log(f"wrote {path.relative_to(common.ROOT)} ({len(zones)} zones) in {time.time() - started:.0f} s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
