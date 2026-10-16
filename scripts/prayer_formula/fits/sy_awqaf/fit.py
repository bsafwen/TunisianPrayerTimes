#!/usr/bin/env python3
"""
Fits `sy_awqaf`, Syria's Ministry of Awqaf (plan 8.1, 8.2, 8.7, 8.8, Q12; milestone AM10, 9.4), and writes its
official-zone asset, android-app/app/src/main/assets/official-zones/sy_awqaf.json (schema 1).

Data, read at run time and never copied (D13, A7); every official table at hand is a Ramadan table:
- test-data-local/research-2026-10-08/SY/sy/dam.txt: the ministry's Ramadan 1447 table for Damascus, 2026-02-19 to
  03-19 (29 rows, hand transcription, the table's own repeated rows kept).
- test-data-local/research-2026-10-08/SY/sy/raq1446.txt: its Ramadan 1446 table for Raqqa, 2025-03-01 to 03-29.
- test-data-local/research-2026-10-08/SY/sy/cities.py, read as text (never run): days 1 and 29 of Ramadan 1447
  (2026-02-19 and 03-19) for nine more governorate tables, and the research's town points for ten tables.
(The day-1 rows of the Eastern Ghouta, Tartus, Deir ez-Zor and Raqqa 1447 tables are only in an image,
research-2026-10-08/SY/sy/heads.png, so they are checked by hand in the README, not here.)

The tables are yearly (each Ramadan computed for its own year), so a score is the app's own computation of that date.
Every score printed and every FitStats comes from common.compute, the reference engine; the search loops use the fast
closed-form code below.

Stages (9.4), each by windows (a published minute bounds the unrounded time to a minute; the value most windows
cover wins), at the tables' town points (one or two Ramadans cannot place a point better):
1. Dhuhr: sun model and evaluation from the Dhuhr column (reported).
2. Each time's evaluation (E4 is a per-event map) and rounding: per time and evaluation, the shift windows of all the
   fit tables at once (Fajr at 18 deg, Isha at 17 deg as the ministry's MWL adoption states, sunrise and Maghrib on the
   standard horizon, Asr on the geometric or the apparent sun, E13); the whole or half minute most windows hold gives
   the offset and rounding (a half is UP + floor or DOWN + ceil, 9.4); the evaluation and sun whose reading reproduces
   most minutes win, ties going to the evaluation another time already uses, then the geometric sun, then EVENT_INSTANT.
   Maghrib's offset is read per Ramadan (E9: + 0 in 1446, + 2 in 1447).
3. The angles: the Fajr and Isha windows under the chosen evaluation and rounding, which must hold 18 and 17.
4. E7 fractions only where the best window wins more than 2 % of the days over the whole or half minute.
Quirk days: rows that match the previous date's computed times better than their own (the tables' repeated rows),
found with a first pass and counted apart.
Held out: Damascus 1447 scored with the values fitted on Raqqa 1446, Raqqa 1446 with those fitted on Damascus 1447
(each Ramadan keeps its own E9 Maghrib offset, which only its own table gives), and the nine other tables' days with
the values fitted on both.

Usage: python scripts/prayer_formula/fits/sy_awqaf/fit.py [--no-guard]
"""

from __future__ import annotations

import argparse
import datetime
import math
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import common  # noqa: E402

ref = common.ref
EVENTS = common.EVENTS
AUTHORITY = "sy_awqaf"
TZ = "Asia/Damascus"                      # UTC+3 all year since 2022 (checked on every row)
TZ_MIN = 180
SY = common.LOCAL_DATA / "research-2026-10-08" / "SY" / "sy"
D2R = math.pi / 180
MODES = ("EVENT_INSTANT", "DAILY_0H_UT", "DAILY_10H_UT", "KACST")
ANGLES = {"FAJR": 18.0, "ISHA": 17.0}     # MWL, adopted by the ministry on 2025-02-28 (8.2, Q12)
REVISION = datetime.date(2025, 3, 30)     # E9: Maghrib + 2 from the day after the 1446 tables (plan, provisional)
FRACTION_GAIN = 0.02
QUIRK_GAIN = 1.0                          # minutes (four events summed) by which the previous date must fit better
# The 14 tables (8.8): id -> (Arabic name, town point). Ten points are the research's (cities.py); Raqqa, Deir ez-Zor
# and Tartus are GeoNames' town points, and Eastern Ghouta («منطقة الغوطة الشرقية») takes Douma, its main town
# (GeoNames, recalled, not checked online).
ZONES = {
    "damascus": ("دمشق", None), "aleppo": ("حلب", None), "homs": ("حمص", None), "hama": ("حماة", None),
    "latakia": ("اللاذقية", None), "tartus": ("طرطوس", (34.88902, 35.88659)), "idlib": ("إدلب", None),
    "raqqa": ("الرقة", (35.95283, 39.00788)), "deir_ez_zor": ("دير الزور", (35.33588, 40.14084)),
    "hasakah": ("الحسكة", None), "daraa": ("درعا", None), "suwayda": ("السويداء", None),
    "quneitra": ("القنيطرة", None), "eastern_ghouta": ("الغوطة الشرقية", (33.57175, 36.40270)),
}
CITIES_PY = {"Aleppo": "aleppo", "Hasakah": "hasakah", "Latakia": "latakia", "Homs": "homs", "Hama": "hama",
             "Daraa": "daraa", "Suwayda": "suwayda", "Quneitra": "quneitra", "Idlib": "idlib", "Damascus": "damascus"}
SOURCE_LINE = ("Syria's Ministry of Awqaf: its 14 governorate Ramadan tables (1446 and 1447, Telegram @SyMOfE), "
               "applied all year (Q12)")
SOURCES = {
    "damascus": "Ministry of Awqaf Ramadan 1447 table, Damascus (t.me/SyMOfE/6578)",
    "raqqa": "Ministry of Awqaf Ramadan 1446 table, Raqqa (t.me/SyMOfE/568)",
    "other": "Ministry of Awqaf Ramadan 1447 tables, days 1 and 29 (t.me/SyMOfE/6569-6578)",
}
# The validity guard's cases (6.5, 8.8, 12), a side check at approximate town points (not verified). True = the plan
# expects the nearest table kept, False = the place at its own point, None = near the threshold (the scan decides).
GUARD_PLACES = (("Tabqa", 35.8361, 38.5481, True), ("Zabadani", 33.7250, 36.0972, True), ("Khan Shaykhun", 35.4417, 36.6500, True),
                ("Al-Tanf", 33.4975, 38.6733, False), ("Ras al-Ayn", 36.8503, 40.0706, None), ("Sukhnah", 34.8964, 38.8706, None),
                ("Tell Abyad", 36.6975, 38.9567, None), ("Palmyra", 34.5603, 38.2672, None), ("Malikiyah", 37.1806, 42.1394, None),
                ("Qamishli", 37.0522, 41.2311, None), ("Abu Kamal", 34.4536, 40.9367, None), ("Nabek", 34.0233, 36.7281, None))


# ------------------------------------------------------------------------------------------------------------
# Data

def minute(text: str) -> int:
    h, m = text.strip().split(":")[:2]
    return int(h) * 60 + int(m)


def load_rows(path: Path, start: datetime.date) -> list[tuple[datetime.date, dict[str, int]]]:
    out = []
    for i, line in enumerate(l for l in path.read_text(encoding="utf-8").splitlines() if l.strip()):
        day = start + datetime.timedelta(days=i)
        if common.utc_offset(TZ, day) != TZ_MIN * 60:
            raise SystemExit(f"{path.name} {day}: not UTC+3")
        out.append((day, dict(zip(EVENTS, (minute(x) for x in line.split())))))
    return out


def load_cities() -> tuple[dict, dict]:
    """cities.py read as text: {zone id: (lat, lng)} and {zone id: [(date, times)]} for its ten tables."""
    text = (SY / "cities.py").read_text(encoding="utf-8")
    points, rows = {}, {}
    for name, lat, lng, a, b in re.findall(r"'(\w+)':\(([\d.]+),([\d.]+),\['([\d: ]+)','([\d: ]+)'\]\)", text):
        zid = CITIES_PY[name]
        points[zid] = (float(lat), float(lng))
        rows[zid] = [(datetime.date(2026, 2, 19), dict(zip(EVENTS, (minute(x) for x in a.split())))),
                     (datetime.date(2026, 3, 19), dict(zip(EVENTS, (minute(x) for x in b.split()))))]
    if sorted(points) != sorted(CITIES_PY.values()):
        raise SystemExit(f"cities.py: {sorted(points)}")
    return points, rows


# ------------------------------------------------------------------------------------------------------------
# Fast model for the search loops (S2-S7; Syria's cycle date is the civil date)

_SUN: dict = {}


def sun(day: datetime.date, model: str, mode: str, local_minute: float) -> tuple[float, float, float]:
    key = (day, model, mode, None if mode != "EVENT_INSTANT" else round(local_minute, 1))
    v = _SUN.get(key)
    if v is None:
        fn = ref.SUN_MODELS[model]
        jd0 = ref.julian_day0(day)
        if mode == "DAILY_0H_UT":
            v = fn(jd0)
        elif mode == "DAILY_10H_UT":
            v = fn(jd0 + 10 / 24)
        elif mode == "KACST":
            v = (fn(jd0 + 21 / 24)[0],) + tuple(fn(jd0 + 9 / 24)[1:])
        else:
            v = fn(jd0 + (local_minute - TZ_MIN) / 1440)
        _SUN[key] = v
    return v


def hour_angle(alt: float, lat: float, decl: float) -> float:
    c = (math.sin(alt * D2R) - math.sin(lat * D2R) * math.sin(decl * D2R)) / (math.cos(lat * D2R) * math.cos(decl * D2R))
    return math.acos(max(-1.0, min(1.0, c))) / D2R


def altitude_at(lat: float, decl: float, h_deg: float) -> float:
    s = math.sin(lat * D2R) * math.sin(decl * D2R) + math.cos(lat * D2R) * math.cos(decl * D2R) * math.cos(h_deg * D2R)
    return math.asin(max(-1.0, min(1.0, s))) / D2R


def asr_altitude(lat: float, decl_noon: float, apparent: bool) -> float:
    zenith = abs(lat - decl_noon)
    if not apparent:
        return math.atan(1 / (1 + math.tan(zenith * D2R))) / D2R
    h0 = 90 - zenith
    h0a = h0 + ref.bennett(h0, 1010.0, 10.0)
    aa = math.atan(1 / (1 + math.tan((90 - h0a) * D2R))) / D2R
    return aa - ref.bennett(aa, 1010.0, 10.0)


def base_minute(e, day, t, lat, lng, model, mode, apparent=False, angle=None) -> float:
    """The unrounded local minute of [e] before any offset, under one evaluation."""
    decl, eot, _ = sun(day, model, mode, t[e])
    noon = 720 + TZ_MIN - eot - 4 * lng
    if e == "DHUHR":
        return noon
    if e == "ASR":
        decl_noon = sun(day, model, mode, t["DHUHR"])[0]
        return noon + 4 * hour_angle(asr_altitude(lat, decl_noon, apparent), lat, decl)
    alt = -(angle if angle is not None else ANGLES[e]) if e in ANGLES else -0.8333
    return noon + (4 if e in ("MAGHRIB", "ISHA") else -4) * hour_angle(alt, lat, decl)


def cover(intervals) -> tuple[int, list[tuple[float, float]]]:
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


def reading(windows, fractions: bool = True) -> dict:
    """The whole or half minute most windows hold (NEAREST + k; a half UP + floor or DOWN + ceil; 9.4), with an E7
    fraction where the best stretch wins more than 2 % of the days."""
    best, stretches = cover(windows)
    lo = math.floor(min(a for a, _ in windows)) - 1
    hi = math.ceil(max(b for _, b in windows)) + 1
    cands = [k / 2 for k in range(2 * lo, 2 * hi + 1)]
    counts = {c: sum(a <= c < b for a, b in windows) for c in cands}
    mid = (stretches[0][0] + stretches[0][1]) / 2
    c = max(cands, key=lambda c: (counts[c], -abs(c - mid)))
    if c == int(c):
        rounding, offset = "NEAREST", int(c)
    else:
        rounding, offset = min((("UP", math.floor(c)), ("DOWN", math.ceil(c))), key=lambda r: (abs(r[1]), r[0] != "DOWN"))
    out = {"value": c, "plain": counts[c], "count": counts[c], "best": best, "stretch": stretches[0], "n": len(windows),
           "rounding": rounding, "offset": offset, "fraction": 0.0}
    if fractions and best - counts[c] > max(2, FRACTION_GAIN * len(windows)):
        k = round(mid)
        out.update(rounding="NEAREST", offset=k, fraction=round(mid - k, 3), count=best)
    return out


def describe(r: dict) -> str:
    fraction = f" E7 {r['fraction']:+.3f}" if r["fraction"] else ""
    return (f"{r['rounding']} {r['offset']:+d}{fraction}: {r['count']} of {r['n']} "
            f"(best {r['best']} at {r['stretch'][0]:+.3f}..{r['stretch'][1]:+.3f})")


# ------------------------------------------------------------------------------------------------------------
# Stages

def windows_of(e, tables, model, mode, apparent=False, maghrib_by_table=None):
    """Shift windows (NEAREST terms) of [e] over the tables, each at its town point; Maghrib net of its table's
    Ramadan offset when [maghrib_by_table] gives one."""
    out = []
    for name, (lat, lng), rows in tables:
        extra = (maghrib_by_table or {}).get(name, 0) if e == "MAGHRIB" else 0
        for day, t in rows:
            u = base_minute(e, day, t, lat, lng, model, mode, apparent) + extra
            out.append((t[e] - 0.5 - u, t[e] + 0.5 - u))
    return out


def fit(tables, say=print, report=True) -> dict:
    """Stages 1-4 on [tables]: [(zone id, point, rows)]."""
    if report:
        for model in ("MEEUS", "VSOP87"):
            res = {m: cover(windows_of("DHUHR", tables, model, m))[0] for m in MODES}
            say(f"  stage 1, Dhuhr windows {model}: " + ", ".join(f"{m} {c}" for m, c in res.items()))
    model = "MEEUS"
    # Maghrib's offset per Ramadan (E9): each table's own reading at the event instant.
    maghrib = {}
    for name, point, rows in tables:
        r = reading(windows_of("MAGHRIB", [(name, point, rows)], model, "EVENT_INSTANT"), fractions=False)
        maghrib[name] = (r["value"] - 0.5) if r["rounding"] == "UP" else r["value"]
    say("  stage 2, Maghrib per table at the event instant: " + ", ".join(
        f"{n} +{maghrib[n]:g}" for n in maghrib) + " (with the rounding below)")
    maghrib_net = {n: (math.floor(v)) for n, v in maghrib.items()}      # the whole minutes, read again jointly below
    chosen, readings, apparent = {}, {}, False
    for e in ("FAJR", "ISHA", "ASR", "SUNRISE", "DHUHR", "MAGHRIB"):
        options = []
        for mode in MODES:
            for ap in ((False, True) if e == "ASR" else (False,)):
                r = reading(windows_of(e, tables, model, mode, ap, maghrib_net), fractions=False)
                options.append((r["plain"], mode, ap, r))
        # The default (the event instant, the geometric sun) unless another option wins by more than 2 % of the days
        # (and 2 days); among those, the most minutes, then the geometric sun, then an evaluation already used.
        n_days = sum(len(rows) for _, _, rows in tables)
        default = next(o for o in options if o[1] == "EVENT_INSTANT" and not o[2])
        better = [o for o in options if o[0] - default[0] > max(2, FRACTION_GAIN * n_days)]
        used = set(chosen.values())
        mode_pick = default if not better else min(
            better, key=lambda o: (-o[0], o[2], o[1] not in used, MODES.index(o[1])))
        _, mode, ap, _ = mode_pick
        chosen[e] = mode
        if e == "ASR":
            apparent = ap
        readings[e] = reading(windows_of(e, tables, model, mode, ap, maghrib_net))
        say(f"  stage 2, {e.lower()}: " + ", ".join(f"{o[1]}{' apparent' if o[2] else ''} {o[0]}" for o in options)
            + f" -> {mode}{' apparent' if ap else ''}, " + describe(readings[e]))
    # Stage 3: the angle windows under the chosen evaluation and rounding.
    for e in ("FAJR", "ISHA"):
        shift = readings[e]["value"] + readings[e]["fraction"]
        wins = []
        for name, (lat, lng), rows in tables:
            for day, t in rows:
                decl, eot, _ = sun(day, model, chosen[e], t[e])
                noon = 720 + TZ_MIN - eot - 4 * lng
                a = [-altitude_at(lat, decl, (m - shift - noon) / 4) for m in (t[e] - 0.5, t[e] + 0.5)]
                wins.append((min(a), max(a)))
        best, stretches = cover(wins)
        at = sum(lo <= ANGLES[e] < hi for lo, hi in wins)
        say(f"  stage 3, {e.lower()} angle windows: {best} of {len(wins)} at {stretches[0][0]:.4f}-{stretches[0][1]:.4f}; "
            f"{at} at {ANGLES[e]}")
    maghrib_offsets = {n: readings["MAGHRIB"]["offset"] + maghrib_net[n] for n in maghrib_net}
    return {"model": model, "modes": chosen, "apparent": apparent, "readings": readings, "maghrib": maghrib_offsets}


def round_minute(u: float, mode: str) -> int:
    if mode == "DOWN":
        return math.floor(u + 1e-3 / 60)
    if mode == "UP":
        return math.ceil(u - 1e-3 / 60)
    return math.floor(u + 0.5)


def bounds(m: int, mode: str) -> tuple[float, float]:
    """The unrounded minutes [lo, hi) that a published minute allows under a rounding (E1)."""
    return {"DOWN": (m, m + 1), "NEAREST": (m - 0.5, m + 0.5), "UP": (m - 1, m)}[mode]


def shift_of(p: dict, e: str, day: datetime.date) -> float:
    """[e]'s minutes under the profile (its offset, E7 fraction and, for Maghrib, the E9 revision of [day])."""
    r = p["readings"][e]
    extra = (p["maghrib"]["damascus"] if day >= REVISION else p["maghrib"]["raqqa"]) if e == "MAGHRIB" else 0
    return r["offset"] + r["fraction"] + extra


def lng_windows(rows, lat, lng0, p, tol):
    """Per published minute, the longitudes at [lat] that give it under the profile, widened by [tol] minutes."""
    out = []
    for day, t in rows:
        for e in EVENTS:
            u0 = base_minute(e, day, t, lat, lng0, p["model"], p["modes"][e], p["apparent"]) + shift_of(p, e, day)
            lo, hi = bounds(t[e], p["readings"][e]["rounding"])
            # u(lng) = u0 - 4 (lng - lng0) lies in [lo, hi)
            out.append((lng0 + (u0 - hi - tol) / 4, lng0 + (u0 - lo + tol) / 4))
    return out


def city_point(rows, town, p, tol) -> tuple[tuple[float, float], bool, int, int]:
    """As the Libya fit does for cities with a few days: the town's latitude stays (a Ramadan hardly fixes one), and
    its longitude too where it reproduces as many minutes as any longitude within 0.5 deg does, each minute within the
    model's precision [tol]; otherwise the middle of the longitudes that do, nearest the town."""
    lat, lng0 = town
    wins = lng_windows(rows, lat, lng0, p, tol)
    at_town = sum(a <= lng0 < b for a, b in wins)
    top, stretches = cover([w for w in ((max(a, lng0 - 0.5), min(b, lng0 + 0.5)) for a, b in wins) if w[1] > w[0]])
    if at_town >= top:
        return town, False, top, at_town
    a, b = min(stretches, key=lambda s: min(abs(s[0] - lng0), abs(s[1] - lng0)))
    return (lat, round((a + b) / 2, 4)), True, top, at_town


def precision(tables, p) -> float:
    """The model's precision: the median distance by which the in-sample misses at the town points fall outside their
    minute (the Libya fit's rule), so that a point is not moved for a second or two."""
    out = []
    for name, (lat, lng), rows in tables:
        for day, t in rows:
            for e in EVENTS:
                u = base_minute(e, day, t, lat, lng, p["model"], p["modes"][e], p["apparent"]) + shift_of(p, e, day)
                lo, hi = bounds(t[e], p["readings"][e]["rounding"])
                if not lo <= u < hi:
                    out.append(lo - u if u < lo else u - hi + 1e-9)
    out.sort()
    return round(out[(len(out) - 1) // 2], 3) if out else 0.0


def method_of(p: dict, before: int, after: int) -> dict:
    """The profile, Maghrib's offset [before] the E9 revision and [after] it (each read from its own Ramadan)."""
    r = p["readings"]
    offsets = {e: r[e]["offset"] for e in ("SUNRISE", "DHUHR", "ASR", "FAJR", "ISHA") if r[e]["offset"]}
    rounding = {e: r[e]["rounding"] for e in EVENTS}
    revisions = [] if before == after else [{"validFrom": REVISION.isoformat(), "change": {"offsets": {"MAGHRIB": after}}}]
    if before:
        offsets["MAGHRIB"] = before
    modes = {e: m for e, m in p["modes"].items() if m != "EVENT_INSTANT"}
    return common.method_json(
        id=AUTHORITY, fajr={"type": "angle", "degrees": ANGLES["FAJR"]}, isha={"type": "angle", "degrees": ANGLES["ISHA"]},
        offsets=offsets, rounding=rounding, sunModel=p["model"], sunEvaluation=modes,
        asrSun="APPARENT" if p["apparent"] else "GEOMETRIC", revisions=revisions,
        highLatitude={"type": "angle_based"}, evidence={"kind": "FROM_TABLES", "source": "fit"})


def zone_of(zid: str, town: tuple[float, float], p: dict, computing: tuple[float, float] | None = None) -> dict:
    """The zone: its town as `point` (mapping), a fitted computing point as the E6 morning and evening points."""
    fractions = {e: p["readings"][e]["fraction"] for e in p["readings"] if p["readings"][e]["fraction"]}
    z = common.zone_json(zid, town[0], town[1], nameAr=ZONES[zid][0], offsets=fractions)
    if computing is not None and common.point(*computing) != z["point"]:
        z["morningPoint"] = z["eveningPoint"] = common.point(*computing)
    z.pop("authority", None)
    return z


def table_minutes(method: dict, zone: dict, day: datetime.date) -> dict[str, int]:
    lat, lng = zone["point"]["latE5"] / 1e5, zone["point"]["lngE5"] / 1e5
    got = common.compute(common.case(method, lat, lng, TZ, day, zone=zone, country="SY"))
    off = common.utc_offset(TZ, day)
    return {e: (got[e][1] + off) // 60 % 1440 for e in EVENTS}


def score_into(score: common.Score, method: dict, zone: dict, rows) -> None:
    for day, t in rows:
        got = table_minutes(method, zone, day)
        for e in EVENTS:
            score.add(e, day, got[e], t[e])


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
    return " ".join(f"{e[:3]} {100 * score.exact[e] / score.total[e]:.1f}%/{100 * score.within1[e] / score.total[e]:.1f}%"
                    for e in EVENTS if score.total[e])


def quirks_of(rows, point, p) -> list[datetime.date]:
    """Rows whose times match the previous date's computed times better than their own (four events away from noon)."""
    lat, lng = point
    out = []
    for day, t in rows:
        def err(d):
            total = 0.0
            for e in ("FAJR", "SUNRISE", "MAGHRIB", "ISHA"):
                u = base_minute(e, d, t, lat, lng, p["model"], p["modes"][e])
                total += abs(u + p["readings"][e]["value"] + (2 if e == "MAGHRIB" and d >= REVISION else 0) - t[e])
            return total
        if err(day - datetime.timedelta(days=1)) < err(day) - QUIRK_GAIN:
            out.append(day)
    return out


def km(lat1, lng1, lat2, lng2) -> float:
    a = (math.sin((lat2 - lat1) * D2R / 2) ** 2
         + math.cos(lat1 * D2R) * math.cos(lat2 * D2R) * math.sin((lng2 - lng1) * D2R / 2) ** 2)
    return 2 * 6371.0 * math.asin(math.sqrt(a))


def guard_check(method: dict, zones: dict, year: int = 2026, say=print) -> None:
    """The guard of 6.5 at each place: the nearest table's leads over the method at the place's own point (unrounded),
    against the table's own margin at its zone point, plus 4 minutes."""
    days = [datetime.date(year, 1, 1) + datetime.timedelta(days=i) for i in range(365)]

    def run(lat, lng, z=None):
        return [common.compute(common.case(method, lat, lng, TZ, d, zone=z, country="SY")) for d in days]

    def leads(r_days, t_days):
        out = {e: [] for e in EVENTS}
        for r, t in zip(r_days, t_days):
            for e in EVENTS:
                lead = (r[e][0] - t[e][1]) / 60
                out[e].append(-lead if e == "SUNRISE" else lead)
        return out
    cache = {}
    for name, lat, lng, expect in GUARD_PLACES:
        zid = min(zones, key=lambda z: km(lat, lng, *zones[z]["pt"]))
        if zid not in cache:
            z = zones[zid]
            t_days = run(*z["pt"], z["zone"])
            cache[zid] = (t_days, {e: max(v) for e, v in leads(run(*z["pt"]), t_days).items()})
        t_days, margin = cache[zid]
        excess = {e: max(v) - margin[e] for e, v in leads(run(lat, lng), t_days).items()}
        worst = max(excess, key=excess.get)
        keeps = excess[worst] <= 4
        verdict = "keeps the table" if keeps else "its own point"
        note = "" if expect is None or keeps == expect else "  (the plan expects the other verdict)"
        say("  %-13s -> %-14s %4.0f km: worst %s %+.1f min beyond the margin: %s%s%s" % (
            name, zid, km(lat, lng, *zones[zid]["pt"]), worst.lower(), excess[worst], verdict,
            " (near the threshold in the plan)" if expect is None else "", note))


# ------------------------------------------------------------------------------------------------------------

def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--no-guard", action="store_true", help="skip the validity-guard side check")
    args = ap.parse_args(argv)
    sys.stdout.reconfigure(encoding="utf-8")

    points, city_rows = load_cities()
    for zid, (_, pt) in ZONES.items():
        if pt is not None:
            points[zid] = pt
    damascus = load_rows(SY / "dam.txt", datetime.date(2026, 2, 19))
    raqqa = load_rows(SY / "raq1446.txt", datetime.date(2025, 3, 1))
    others = {zid: rows for zid, rows in city_rows.items() if zid != "damascus"}
    print(f"Damascus 1447: {len(damascus)} rows; Raqqa 1446: {len(raqqa)} rows; {len(others)} more 1447 tables, 2 days each")

    # First pass on both Ramadan tables, to find the repeated rows; then the fit without them.
    first = fit([("damascus", points["damascus"], damascus), ("raqqa", points["raqqa"], raqqa)], say=lambda s: None, report=False)
    q_dam = quirks_of(damascus, points["damascus"], first)
    q_raq = quirks_of(raqqa, points["raqqa"], first)
    print("  quirk rows (they match the previous date): " + ", ".join(str(d) for d in q_dam + q_raq))
    dam = [(d, t) for d, t in damascus if d not in q_dam]
    raq = [(d, t) for d, t in raqqa if d not in q_raq]

    print("Damascus 1447 and Raqqa 1446:")
    p = fit([("damascus", points["damascus"], dam), ("raqqa", points["raqqa"], raq)])
    method = method_of(p, p["maghrib"]["raqqa"], p["maghrib"]["damascus"])      # 1446 before the revision, 1447 after
    print("Damascus 1447 alone (to score Raqqa 1446):")
    p_dam = fit([("damascus", points["damascus"], dam)], report=False)
    print("Raqqa 1446 alone (to score Damascus 1447):")
    p_raq = fit([("raqqa", points["raqqa"], raq)], report=False)

    # Each fold keeps both Ramadans' Maghrib offsets (E9): only its own table gives a Ramadan's offset.
    p_dam = dict(p_dam, maghrib={"raqqa": p["maghrib"]["raqqa"], "damascus": p_dam["maghrib"]["damascus"]})
    p_raq = dict(p_raq, maghrib={"raqqa": p_raq["maghrib"]["raqqa"], "damascus": p["maghrib"]["damascus"]})
    m_dam = method_of(p_dam, p_dam["maghrib"]["raqqa"], p_dam["maghrib"]["damascus"])
    m_raq = method_of(p_raq, p_raq["maghrib"]["raqqa"], p_raq["maghrib"]["damascus"])
    data = {"damascus": dam, "raqqa": raq, **others}
    tol = precision([("damascus", points["damascus"], dam), ("raqqa", points["raqqa"], raq)], p)
    print(f"  the model's precision (median distance of the in-sample misses beyond their minute): {tol} min")

    # Computing points (the shipped asset): each table's own days.
    computing = {}
    for zid, rows in data.items():
        pt, moved, top, at_town = city_point(rows, points[zid], p, tol)
        computing[zid] = pt
        if moved:
            print(f"  {zid}: computing point {pt[0]:.4f} N {pt[1]:.4f} E, {km(*points[zid], *pt):.1f} km from the town "
                  f"({top} of {6 * len(rows)} minutes there, {at_town} at the town)")

    # Held out: each table's days with a profile fitted without its own table (Damascus by Raqqa's, Raqqa by
    # Damascus's, the nine by both) and a computing point fitted on its other days (odd and even days; day 1 and day 29).
    def day_split(zid, prof, meth):
        rows = data[zid]
        halves = [rows[0::2], rows[1::2]]
        s = common.Score()
        for k in (0, 1):
            pt = city_point(halves[1 - k], points[zid], prof, tol)[0]
            score_into(s, meth, zone_of(zid, points[zid], prof, pt), halves[k])
        return s
    held = {"damascus": day_split("damascus", p_raq, m_raq), "raqqa": day_split("raqqa", p_dam, m_dam)}
    for zid in others:
        held[zid] = day_split(zid, p, method)
    quirk_score = common.Score()
    score_into(quirk_score, method, zone_of("damascus", points["damascus"], p, computing["damascus"]),
               [(d, t) for d, t in damascus if d in q_dam])
    score_into(quirk_score, method, zone_of("raqqa", points["raqqa"], p, computing["raqqa"]), [(d, t) for d, t in raqqa if d in q_raq])
    in_sample = {}
    for zid, rows in data.items():
        in_sample[zid] = common.Score()
        score_into(in_sample[zid], method, zone_of(zid, points[zid], p, computing[zid]), rows)

    # Zones, FitStats and the asset.
    samples = {
        "damascus": common.sample(SOURCES["damascus"], damascus[0][0], damascus[-1][0], len(damascus), 1),
        "raqqa": common.sample(SOURCES["raqqa"], raqqa[0][0], raqqa[-1][0], len(raqqa), 1),
    }
    zones, guard_zones = [], {}
    for zid in ZONES:
        z = zone_of(zid, points[zid], p, computing.get(zid))
        z["namedPlaces"] = [{"centre": z["point"], "catalogueIds": [], "areaKm": 0.0}]
        if zid in held:
            # The profile from the other tables, the computing point from the zone's own other days (day-split).
            if zid in ("damascus", "raqqa"):
                own, q = samples[zid], (len(q_dam) if zid == "damascus" else len(q_raq))
                fitted_on = [samples["raqqa" if zid == "damascus" else "damascus"], dict(own, source=own["source"] + ", the point")]
            else:
                rows = others[zid]
                own, q = common.sample(SOURCES["other"] + f": {zid}", rows[0][0], rows[-1][0], len(rows), 1), 0
                fitted_on = [samples["damascus"], samples["raqqa"], dict(own, source=own["source"] + ", the point")]
            held_out = [dict(own, source=own["source"] + ", day-split")]
            z["fit"] = common.fit_stats(fitted_on, held_out, held[zid], ramadan_days=held[zid].total["FAJR"] + q, quirk_days=q)
        zones.append(z)
        guard_zones[zid] = {"pt": points[zid], "zone": zone_of(zid, points[zid], p, computing.get(zid))}
    all_held = merge(held.values())
    other_days = sum(len(r) for r in others.values())
    profile_fit = common.fit_stats(
        [samples["damascus"], samples["raqqa"]],
        [dict(samples["damascus"], source=samples["damascus"]["source"] + ", values fitted on Raqqa 1446"),
         dict(samples["raqqa"], source=samples["raqqa"]["source"] + ", values fitted on Damascus 1447"),
         common.sample(SOURCES["other"] + ": the nine other tables", datetime.date(2026, 2, 19), datetime.date(2026, 3, 19),
                       other_days, len(others))],
        all_held, ramadan_days=sum(s.total["FAJR"] for s in held.values()) + len(q_dam) + len(q_raq),
        quirk_days=len(q_dam) + len(q_raq))
    profile_fit["maxDiffMin"] = max(all_held.worst, quirk_score.worst)
    path = common.write_asset(AUTHORITY, AUTHORITY, "SY", "NEAREST", zones, fit=profile_fit, source=SOURCE_LINE)

    r = p["readings"]
    print("Profile: Fajr %g, Isha %g; %s; Asr on the %s sun; Maghrib +%d before %s, +%d from it; sun %s" % (
        ANGLES["FAJR"], ANGLES["ISHA"], ", ".join(f"{e.lower()} {r[e]['rounding']} {r[e]['offset']:+d}"
                                                  + (f" E7 {r[e]['fraction']:+.3f}" if r[e]["fraction"] else "")
                                                  + f" ({p['modes'][e]})" for e in EVENTS),
        "apparent" if p["apparent"] else "geometric", p["maghrib"]["raqqa"], REVISION, p["maghrib"]["damascus"], p["model"]))
    print("Held out (FitStats; Damascus by Raqqa's values, Raqqa by Damascus's, nine tables by both): " + pct(all_held)
          + f"; worst {all_held.worst} min")
    for zid in ("damascus", "raqqa"):
        print(f"  {zid}: " + shares(held[zid]))
    print("  nine tables, days 1 and 29: " + shares(merge(held[z] for z in others)))
    for zid in others:
        s = held[zid]
        bad = [m for m in s.misses]
        print(f"    {zid}: {sum(s.exact.values())}/{sum(s.total.values())} exact" + (f", beyond 1 min: {bad}" if bad else ""))
    print("In-sample (every table with the shipped values and points): " + shares(merge(in_sample.values())))
    print("  Damascus 1447 and Raqqa 1446: " + shares(merge([in_sample["damascus"], in_sample["raqqa"]])))
    print(f"Quirk rows with the shipped values: " + shares(quirk_score) + f"; worst {quirk_score.worst} min")
    if not args.no_guard:
        print("Validity guard, 2026 (side check):")
        guard_check(method, guard_zones)
    print(f"Wrote {path.relative_to(common.ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
