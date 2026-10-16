#!/usr/bin/env python3
"""
Fits `bg_grand_mufti`, the Chief Mufti's Office of Bulgaria's «Времена за намаз» (plan 7.5, 8.8; milestone M3, 9.4),
on the months collected so far, and writes its official-zone asset,
android-app/app/src/main/assets/official-zones/bg_grand_mufti.json (schema 1).

Data, read at run time from test-data-local/official/bg/<yyyy>/<mm>/ and never copied (D13, A7): the collector's
pages (scripts/collectors/bg.py), one per city and month, 48 cities (Aytos to Yambol); on 2026-10-16, October 2026.

What the tables are. Every city's month is Sofia's month shifted by one whole number of minutes on all six times and
every day (October 2026: 48 of 48 cities, 186 of 186 times each), Sofia, Montana and Blagoevgrad unshifted, Kavarna
-19. So the office publishes one table and a shift per city, as KMSH does for Albania (8.8): the profile is fitted on
Sofia's table, and each city is a zone that adds its shift to Sofia's unrounded times (the shifts are read from the
tables, the same on every day collected).

Sofia's table: stages of 9.4 by windows (a printed minute bounds the unrounded time to a half-open minute), from
diyanet_tr's parameters (18 / 17, sunrise -7, Dhuhr +5, Asr +4, Maghrib +7, the sun once a day at 0h UT, NEAREST) at
Sofia's centre; an alternative must cover more than 0.5 % and at least 2 more windows. Until six months are
collected, stage 0's evaluation serves all six times (E4): one month cannot tell one per time.
0. One evaluation for all six (E4).
1. Dhuhr: rounding, whole-minute offset and longitude (within 0.25 deg of the centre).
2. The latitude (within 0.25 deg) from sunrise, Maghrib, Fajr and Isha; it leaves Sofia's latitude only where the fits
   on both halves of the days move it to the same side. Then sunrise's and Maghrib's rounding and offset.
3. Fajr and Isha: rounding, and either the angle (to 0.1 deg, offset 0) or diyanet_tr's angle with a whole-minute
   offset, whichever covers more windows.
4. Asr: shadow factor, rounding, offset.
Held out: alternate days (the stages refitted on the odd days score the even ones, and the reverse), with
common.compute (the reference engine), every city through its zone and the base zone.

Usage: python scripts/prayer_formula/fits/bg_grand_mufti/fit.py
"""

from __future__ import annotations

import calendar
import datetime
import importlib.util
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import common  # noqa: E402

ref = common.ref
EVENTS = common.EVENTS
AUTHORITY = "bg_grand_mufti"
TZ = "Europe/Sofia"
DATA = common.LOCAL_DATA / "official" / "bg"
CATALOGUE = common.ROOT / "android-app" / "app" / "src" / "main" / "assets" / "places" / "europe-cities.tsv"
BASE = "sofia"
CENTRE = (42.69751, 23.32415)              # Sofia, GeoNames 727011 (the catalogue's row)
BOX_LNG, BOX_LAT, LAT_STEP = 0.25, 0.25, 0.005
EVALS = ("EVENT_INSTANT", "DAILY_0H_UT", "DAILY_10H_UT", "KACST")
ASR_EVALS = EVALS + ("LOCAL_MIDNIGHT_SHADOW",)
WINDOW = {"NEAREST": (-30.0, 30.0), "UP": (-60.0, 0.0), "DOWN": (0.0, 60.0)}
MODES = ("NEAREST", "UP", "DOWN")
CLEARLY = 1.005
PRESET = {"FAJR": 0, "SUNRISE": -7, "DHUHR": 5, "ASR": 4, "MAGHRIB": 7, "ISHA": 0}   # diyanet_tr (7.5)
PRESET_ANGLES = {"FAJR": 18.0, "ISHA": 17.0}
PRESET_EVAL = "DAILY_0H_UT"
# The four cities the place catalogue lacks: GeoNames (cities500, 2026-10-16). Byala is Varna province's (42.87 N
# 27.89 E, GeoNames 732721): its shift (-17) is Dobrich's and Varna's, not that of Ruse province's Byala (25.7 E).
EXTRA_POINTS = {"byala": (42.87426, 27.88865), "kaolinovo": (43.61667, 27.11667), "nikopol": (43.70528, 24.89521),
                "sitovo": (44.0273, 27.01982)}
# Arabic names where the catalogue has none: GeoNames' Arabic names (Byala, Nikopol, Sitovo), else transliterations.
EXTRA_NAMES = {"byala": "بيالا", "nikopol": "نيكوبول", "sitovo": "سيتوفو", "kaolinovo": "كاولينوفو", "isperih": "إسبريه",
               "knezha": "كنيجا", "novi-pazar": "نوفي بازار", "provadia": "بروفاديا", "tvarditsa": "تفارديتسا",
               "yakoruda": "ياكورودا"}
SOURCE_LINE = ("The Chief Mufti's Office of Bulgaria (grandmufti.bg), «Времена за намаз»: one table and a whole-minute "
               "shift for each of its 48 cities")


def log(text: str = "") -> None:
    print(text, flush=True)


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")


# ---- data -------------------------------------------------------------------------------------------------------

def collector():
    """scripts/collectors/bg.py (its page reader), under its own name."""
    spec = importlib.util.spec_from_file_location("bg_collector", common.ROOT / "scripts" / "collectors" / "bg.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules["bg_collector"] = module
    spec.loader.exec_module(module)
    return module


def load_tables() -> dict[str, dict[datetime.date, list[str]]]:
    """city slug -> date -> the six printed clock times, from every month collected."""
    bg = collector()
    out: dict[str, dict] = {}
    for path in sorted(DATA.glob("*/*/*_*.html")):
        slug, ym = path.stem.rsplit("_", 1)
        if slug == "default" or "-" in ym[8:]:
            continue
        year, month = int(ym[:4]), int(ym[5:7])
        days = calendar.monthrange(year, month)[1]
        raw = path.read_bytes()
        rows = bg.table_rows(raw.decode(bg.page_charset(raw), errors="replace"), days)
        assert len(rows) == days, (path, len(rows))
        for r in rows:
            out.setdefault(slug, {})[datetime.date(year, month, int(r[0].rstrip(".")))] = list(r[1:])
    return out


def catalogue_rows() -> dict[str, dict]:
    import unicodedata

    def norm(s):
        s = unicodedata.normalize("NFKD", s).encode("ascii", "ignore").decode().lower()
        return "".join(ch if ch.isalnum() else "-" for ch in s).strip("-")

    lines = CATALOGUE.read_text(encoding="utf-8").splitlines()
    header = lines[0].split("\t")
    out = {}
    for line in lines[1:]:
        r = dict(zip(header, line.split("\t")))
        if r["country"] != "BG":
            continue
        for name in {norm(r["name"]), norm(r["ascii"])} | {norm(a) for a in r["aliases"].split("|") if a}:
            if name not in out or int(r["population"] or 0) > int(out[name]["population"] or 0):
                out[name] = r
    return out


def minutes(day: datetime.date, hhmm: str) -> int:
    return common.local_to_epoch(TZ, day, hhmm) // 60


# ---- fast raw events (the reference's own primitives) ------------------------------------------------------------

_SUNS: dict = {}


def sun_of(day, mode):
    key = (day, mode)
    if key not in _SUNS:
        cyc = ref.Cycle(day)
        _SUNS[key] = (cyc, ref.SunFunction(ref.SUN_MODELS["MEEUS"], mode if mode != "LOCAL_MIDNIGHT_SHADOW" else "EVENT_INSTANT", cyc.jd0))
    return _SUNS[key]


def transit_at(day, lng, mode):
    cyc, sun = sun_of(day, mode)
    return cyc, sun, ref.transit(cyc, sun, lng)


def event_at(day, lat, lng, alt, side, mode):
    cyc, sun, t0 = transit_at(day, lng, mode)
    return cyc.instant(ref.event(cyc, sun, lat, lng, alt, side, t0))


def asr_at(day, lat, lng, mode, factor):
    cyc, sun, t0 = transit_at(day, lng, mode)
    if mode == "LOCAL_MIDNIGHT_SHADOW":
        decl = ref.SUN_MODELS["MEEUS"](ref.jd_of_instant(common.local_to_epoch(TZ, day, "0:00")))[0]
    else:
        decl = sun(cyc.jd0 + t0 / 24)[0]
    alt = ref.atand(1 / (factor + ref.tand(abs(lat - decl))))
    return cyc.instant(ref.event(cyc, sun, lat, lng, alt, 1, t0))


def depression(day, lat, lng, t, mode):
    cyc, sun = sun_of(day, mode)
    return -ref.altitude(cyc, sun, lat, lng, cyc.hours(t))


# ---- windows -----------------------------------------------------------------------------------------------------

def best_cover(intervals, lo, hi, prefer):
    """(count, value): the most half-open intervals covering one value in [lo, hi]; `prefer` where it lies in a best
    plateau, else the middle of the best plateau nearest it."""
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


def exact(t, offset_min, mode, pub_epoch) -> bool:
    return ref.round_instant(t + 60 * offset_min, mode) == pub_epoch


MIN_GAIN = 2           # and at least two more windows: with one month, a single day is noise


def clearly(best: int, default: int) -> bool:
    return best > default * CLEARLY and best >= default + MIN_GAIN


def pick(candidates, default_key):
    best = max(candidates, key=lambda c: c[0])
    default = next(c for c in candidates if c[1] == default_key)
    return best if clearly(best[0], default[0]) else default


PER_TIME_EVALUATION_MONTHS = 6   # fewer months collected: stage 0's evaluation for all six (E4), not one per time
# The point's latitude leaves the centre only where the fits on both halves of the data move it to the same side.
LATITUDE = {"free": True}


def same_side(a: float, b: float, centre: float) -> bool:
    return (a - centre) * (b - centre) > 0


# ---- the stages ---------------------------------------------------------------------------------------------------

def fit(rows: dict[datetime.date, list[str]], say=lambda *_: None) -> dict:
    P = {e: [(d, common.local_to_epoch(TZ, d, times[k])) for d, times in sorted(rows.items())] for k, e in enumerate(EVENTS)}
    p = {"evals": {}, "modes": {}, "offsets": {}, "counts": {}}
    lat0, lng0 = CENTRE
    # Stage 0.
    totals = {}
    for ev in EVALS:
        n = 0
        for e in EVENTS:
            for day, pb in P[e]:
                if e == "DHUHR":
                    cyc, _, t0 = transit_at(day, lng0, ev)
                    t = cyc.instant(t0)
                elif e == "ASR":
                    t = asr_at(day, lat0, lng0, ev, 1)
                else:
                    alt, side = {"FAJR": (-PRESET_ANGLES["FAJR"], -1), "SUNRISE": (-0.8333, -1), "MAGHRIB": (-0.8333, 1),
                                 "ISHA": (-PRESET_ANGLES["ISHA"], 1)}[e]
                    t = event_at(day, lat0, lng0, alt, side, ev)
                n += exact(t, PRESET[e], "NEAREST", pb)
        totals[ev] = n
    dev = max(EVALS, key=lambda ev: (clearly(totals[ev], totals[PRESET_EVAL]) or ev == PRESET_EVAL, totals[ev]))
    p["stage0"] = dev
    n_months = len({(d.year, d.month) for d in rows})
    evals = EVALS if n_months >= PER_TIME_EVALUATION_MONTHS else (dev,)
    asr_evals = ASR_EVALS if n_months >= PER_TIME_EVALUATION_MONTHS else (dev,)
    say(f"  stage 0, one evaluation for all six (diyanet_tr's angles and offsets at Sofia): {dev} ("
        + ", ".join(f"{ev} {n}" for ev, n in totals.items()) + f" of {sum(len(v) for v in P.values())})"
        + ("" if len(evals) > 1 else f"; {n_months} month(s): kept for every time"))
    # Stage 1: Dhuhr.
    cands = []
    for ev in evals:
        t0s = []
        for day, pb in P["DHUHR"]:
            cyc, _, t0 = transit_at(day, lng0, ev)
            t0s.append((cyc.instant(t0), pb))
        for mode in MODES:
            lo, hi = WINDOW[mode]
            for off in range(0, 9):
                ivs = [(lng0 + (t + 60 * off - pb - hi) / 240, lng0 + (t + 60 * off - pb - lo) / 240) for t, pb in t0s]
                n, lng = best_cover(ivs, lng0 - BOX_LNG, lng0 + BOX_LNG, lng0)
                cands.append((n, (ev, mode, off), lng))
    n, (ev, mode, off), lng = pick(cands, (dev, "NEAREST", PRESET["DHUHR"]))
    p["evals"]["DHUHR"], p["modes"]["DHUHR"], p["offsets"]["DHUHR"], p["counts"]["DHUHR"] = ev, mode, off, n
    p["lng"] = round(lng, 5)
    say(f"  stage 1, Dhuhr: {ev} {mode} {off:+d}, longitude {p['lng']:.5f} ({n} of {len(P['DHUHR'])})")

    # Stage 2: latitude from sunrise and Maghrib, then their rules.
    def horizon_cands(e, side, offs, lat):
        out = []
        for ev in evals:
            ts = [(event_at(day, lat, p["lng"], -0.8333, side, ev), pb) for day, pb in P[e]]
            for mode in MODES:
                for off in offs:
                    out.append((sum(1 for t, pb in ts if exact(t, off, mode, pb)), (ev, mode, off)))
        return out

    def twilight_best(e, side, lat):
        best = 0
        for ev in evals:
            ts = [(event_at(day, lat, p["lng"], -PRESET_ANGLES[e], side, ev), pb) for day, pb in P[e]]
            for mode in MODES:
                for off in range(-4, 5):
                    best = max(best, sum(1 for t, pb in ts if exact(t, off, mode, pb)))
        return best

    offs_sr, offs_mg = range(-11, -2), range(3, 12)

    def lat_total(lat):
        return (max(c[0] for c in horizon_cands("SUNRISE", -1, offs_sr, lat)) + max(c[0] for c in horizon_cands("MAGHRIB", 1, offs_mg, lat))
                + twilight_best("FAJR", -1, lat) + twilight_best("ISHA", 1, lat))

    best_lat = None
    for k in range(-round(BOX_LAT / LAT_STEP), round(BOX_LAT / LAT_STEP) + 1):
        lat = lat0 + k * LAT_STEP
        key = (lat_total(lat), -abs(lat - lat0))
        if best_lat is None or key > best_lat[0]:
            best_lat = (key, lat)
    at_centre = lat_total(lat0)
    p["lat"] = round(best_lat[1], 5) if clearly(best_lat[0][0], at_centre) and LATITUDE["free"] else lat0
    p["lat_windows"] = f"{best_lat[0][0]} at {best_lat[1]:.5f}, {at_centre} at the centre"
    for e, side, offs in (("SUNRISE", -1, offs_sr), ("MAGHRIB", 1, offs_mg)):
        n, (ev, mode, off) = pick(horizon_cands(e, side, offs, p["lat"]), (dev, "NEAREST", PRESET[e]))
        p["evals"][e], p["modes"][e], p["offsets"][e], p["counts"][e] = ev, mode, off, n
    say(f"  stage 2: latitude {p['lat']:.5f} (sunrise, Maghrib, Fajr and Isha windows: {p['lat_windows']}); "
        f"sunrise {p['evals']['SUNRISE']} {p['modes']['SUNRISE']} "
        f"{p['offsets']['SUNRISE']:+d} ({p['counts']['SUNRISE']}), Maghrib {p['evals']['MAGHRIB']} {p['modes']['MAGHRIB']} "
        f"{p['offsets']['MAGHRIB']:+d} ({p['counts']['MAGHRIB']})")
    # Stage 3: Fajr and Isha, the angle at offset 0 or the preset angle with an offset.
    for e, side in (("FAJR", -1), ("ISHA", 1)):
        cands = []
        for ev in evals:
            for mode in MODES:
                lo, hi = WINDOW[mode]
                ivs = []
                for day, pb in P[e]:
                    da = depression(day, p["lat"], p["lng"], pb + lo, ev)
                    db = depression(day, p["lat"], p["lng"], pb + hi, ev)
                    ivs.append((min(da, db), max(da, db)))
                n, angle = best_cover(ivs, PRESET_ANGLES[e] - 2.5, PRESET_ANGLES[e] + 2.5, PRESET_ANGLES[e])
                rounded = round(angle, 1)
                n = sum(1 for a, b in ivs if a <= rounded < b)
                cands.append((n, (ev, mode, rounded, 0)))
                ts = [(event_at(day, p["lat"], p["lng"], -PRESET_ANGLES[e], side, ev), pb) for day, pb in P[e]]
                for off in range(-4, 5):
                    cands.append((sum(1 for t, pb in ts if exact(t, off, mode, pb)), (ev, mode, PRESET_ANGLES[e], off)))
        default = (dev, "NEAREST", PRESET_ANGLES[e], 0)
        best = max(cands, key=lambda c: (c[0], c[1][2] == PRESET_ANGLES[e], c[1][3] == 0))
        n0 = next(c[0] for c in cands if c[1] == default)
        n, (ev, mode, angle, off) = best if clearly(best[0], n0) else (n0, default)
        p["evals"][e], p["modes"][e], p["offsets"][e], p["counts"][e] = ev, mode, off, n
        p[e.lower()] = angle
        alt_angle = max((c for c in cands if c[1][3] == 0), key=lambda c: c[0])
        alt_off = max((c for c in cands if c[1][2] == PRESET_ANGLES[e]), key=lambda c: c[0])
        say(f"  stage 3, {e.lower()}: {ev} {mode} {angle} deg {off:+d} ({n} of {len(P[e])}; best angle at offset 0: "
            f"{alt_angle[1]} {alt_angle[0]}; best offset at {PRESET_ANGLES[e]} deg: {alt_off[1]} {alt_off[0]})")
    # Stage 4: Asr.
    cands = []
    for factor in (1, 2):
        for ev in asr_evals:
            ts = [(asr_at(day, p["lat"], p["lng"], ev, factor), pb) for day, pb in P["ASR"]]
            for mode in MODES:
                for off in range(-2, 8):
                    cands.append((sum(1 for t, pb in ts if exact(t, off, mode, pb)), (factor, ev, mode, off)))
    n, (factor, ev, mode, off) = pick(cands, (1, dev, "NEAREST", PRESET["ASR"]))
    p["factor"] = factor
    p["evals"]["ASR"], p["modes"]["ASR"], p["offsets"]["ASR"], p["counts"]["ASR"] = ev, mode, off, n
    say(f"  stage 4, Asr: factor {factor} {ev} {mode} {off:+d} ({n} of {len(P['ASR'])})")
    return p


# ---- profile, zones, scores ---------------------------------------------------------------------------------------

def method_of(p: dict | None) -> dict:
    """bg_grand_mufti's PrayerMethod JSON: the fitted rules, or (p None) the preset before this fit (diyanet_tr's)."""
    common_fields = dict(highLatitude={"type": "diyanet_takdir"}, polar="DIYANET_CLAMP", minDayNightHours=5)
    if p is None:
        return common.method_json(id=AUTHORITY, fajr={"type": "angle", "degrees": 18.0}, isha={"type": "angle", "degrees": 17.0},
                                  offsets={e: v for e, v in PRESET.items() if v}, sunEvaluation={e: PRESET_EVAL for e in EVENTS},
                                  evidence={"kind": "APPROXIMATE", "source": "preset"}, **common_fields)
    return common.method_json(
        id=AUTHORITY, fajr={"type": "angle", "degrees": p["fajr"]}, isha={"type": "angle", "degrees": p["isha"]},
        offsets={e: p["offsets"][e] for e in EVENTS if p["offsets"][e]}, rounding={e: p["modes"][e] for e in EVENTS},
        sunEvaluation={e: p["evals"][e] for e in EVENTS if p["evals"][e] != "EVENT_INSTANT"}, asrShadow=p["factor"],
        evidence={"kind": "FROM_TABLES", "source": "The Chief Mufti's Office's city tables (fits/bg_grand_mufti)"}, **common_fields)


def base_zone(p: dict | None) -> dict:
    fields = {"authority": AUTHORITY}
    if p is not None:
        fields["morningPoint"] = common.point(p["lat"], p["lng"])
        fields["eveningPoint"] = common.point(p["lat"], p["lng"])
    return common.zone_json(BASE, CENTRE[0], CENTRE[1], **fields)


def city_zone(slug: str, point, shift: int) -> dict:
    return common.zone_json(slug, point[0], point[1], authority=AUTHORITY, baseZoneId=BASE,
                            offsets={e: float(shift) for e in EVENTS} if shift else {})


def score(method, zone, base, point, rows, days=None) -> common.Score:
    sc = common.Score()
    for d, times in sorted(rows.items()):
        if days is not None and d not in days:
            continue
        out = common.compute(common.case(method, point[0], point[1], TZ, d, zone=zone, base_zone=base, country="BG"))
        for k, e in enumerate(EVENTS):
            sc.add(e, d, out[e][1] // 60, minutes(d, times[k]))
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


def km(a, b) -> float:
    import math
    return math.hypot((a[0] - b[0]) * 111.2, (a[1] - b[1]) * 111.2 * math.cos(math.radians(b[0])))


def main() -> int:
    started = time.time()
    tables = load_tables()
    base_rows = tables[BASE]
    months = sorted({(d.year, d.month) for d in base_rows})
    log(f"bg_grand_mufti: {len(tables)} cities, months {', '.join(f'{y}-{m:02d}' for y, m in months)}, {len(base_rows)} days of Sofia")

    # Each city: Sofia's table plus one whole-minute shift?
    shifts = {}
    for slug, rows in sorted(tables.items()):
        assert set(rows) == set(base_rows), slug
        diffs = {minutes(d, rows[d][k]) - minutes(d, base_rows[d][k]) for d in rows for k in range(6)}
        assert len(diffs) == 1, (slug, sorted(diffs))
        shifts[slug] = diffs.pop()
    log(f"every city is Sofia's table plus one whole-minute shift on all six times and every day ({len(base_rows) * 6} times "
        f"each): " + ", ".join(f"{s} {v:+d}" for s, v in sorted(shifts.items(), key=lambda t: (t[1], t[0]))))

    days = sorted(base_rows)
    halves = {"odd": {d for d in days if d.day % 2 == 1}, "even": {d for d in days if d.day % 2 == 0}}
    fitted = {}
    for name, part in halves.items():
        log(f"fit on the {name} days:")
        fitted[name] = fit({d: base_rows[d] for d in part}, log)
        log("  " + describe(fitted[name]))
    LATITUDE["free"] = same_side(fitted["odd"]["lat"], fitted["even"]["lat"], CENTRE[0])
    log(f"the halves' latitudes {fitted['odd']['lat']:.5f} and {fitted['even']['lat']:.5f}: "
        + ("the latitude may leave the centre" if LATITUDE["free"] else "not on one side of the centre, which stays"))
    log("fit on all days:")
    final = fit(base_rows, log)
    log("  " + describe(final))
    log(f"  ({time.time() - started:.0f} s)")

    # Points and names of the 48 cities.
    cat = catalogue_rows()
    points, names, ids = {}, {}, {}
    for slug in tables:
        row = cat.get(slug)
        if row is not None:
            points[slug] = (int(row["latE5"]) / 1e5, int(row["lngE5"]) / 1e5)
            names[slug] = row["arabic"] or EXTRA_NAMES[slug]
            ids[slug] = row["id"]
        else:
            points[slug], names[slug] = EXTRA_POINTS[slug], EXTRA_NAMES[slug]
    points[BASE] = CENTRE
    # The shifts against the longitude difference from Sofia (a check of the points; not a rule the office states).
    import statistics
    lam0 = statistics.median(points[s][1] + shifts[s] / 4 for s in tables)
    off = sorted(((s, shifts[s], -4 * (points[s][1] - lam0)) for s in tables), key=lambda t: -abs(t[1] - t[2]))
    log(f"shift against -4 (lambda - {lam0:.3f}): largest gaps " + ", ".join(f"{s} {v:+d} vs {x:+.1f}" for s, v, x in off[:5]))

    # Held out: Sofia's table, then every city through its zone and the base zone.
    held_sofia, held_all = common.Score(), common.Score()
    for name, other in (("odd", "even"), ("even", "odd")):
        m, zb = method_of(fitted[other]), base_zone(fitted[other])
        merge(held_sofia, score(m, zb, None, CENTRE, base_rows, halves[name]))
        for slug in tables:
            z = zb if slug == BASE else city_zone(slug, points[slug], shifts[slug])
            merge(held_all, score(m, z, None if slug == BASE else zb, points[slug], tables[slug], halves[name]))
    log(f"held out (alternate days), exact/within 1 (%):")
    log("  Sofia:        " + shares(held_sofia))
    log(f"  all {len(tables)} cities: " + shares(held_all))
    method, zone = method_of(final), base_zone(final)
    log("in-sample, Sofia: " + shares(score(method, zone, None, CENTRE, base_rows)))
    log("the preset before this fit (diyanet_tr's parameters at Sofia's centre): "
        + shares(score(method_of(None), base_zone(None), None, CENTRE, base_rows)))
    log("diyanet_tr's parameters at each city's own point (no table), all cities: " + shares(
        sum_scores([score(method_of(None), None, None, points[s], tables[s]) for s in tables])))
    log(f"  ({time.time() - started:.0f} s)")

    first, last = days[0], days[-1]
    source = f"The Chief Mufti's Office's tables, {', '.join(f'{y}-{m:02d}' for y, m in months)}"
    stats_sofia = common.fit_stats([common.sample(source + ": Sofia", first, last, len(days), 1)],
                                   [common.sample(source + ": Sofia, held out by alternate days", first, last, len(days), 1)],
                                   held_sofia)
    stats_all = common.fit_stats([common.sample(source + ": Sofia's table and the cities' shifts", first, last, len(days), len(tables))],
                                 [common.sample(source + ", every city, held out by alternate days", first, last, len(days), len(tables))],
                                 held_all)
    zones = []
    for slug in sorted(tables, key=lambda s: (s != BASE, s)):
        if slug == BASE:
            z = {k: v for k, v in zone.items() if k != "authority"}
        else:
            z = {k: v for k, v in city_zone(slug, points[slug], shifts[slug]).items() if k != "authority"}
        z.update({"nameAr": names[slug], "namedPlaces": [{"centre": common.point(*points[slug]), "catalogueIds": [], "areaKm": 0.0}],
                  "fit": stats_sofia})
        zones.append(z)
    log(f"Sofia's computing point (E6): {final['lat']:.5f} {final['lng']:.5f}, {km((final['lat'], final['lng']), CENTRE):.1f} km "
        f"from Sofia's centre")
    path = common.write_asset(AUTHORITY, AUTHORITY, "BG", "NEAREST", zones, fit=stats_all, source=SOURCE_LINE)
    log(f"wrote {path.relative_to(common.ROOT)} ({len(zones)} zones) in {time.time() - started:.0f} s")
    return 0


def sum_scores(scores):
    out = common.Score()
    for sc in scores:
        merge(out, sc)
    return out


if __name__ == "__main__":
    sys.exit(main())
