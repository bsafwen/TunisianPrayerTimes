#!/usr/bin/env python3
"""
Fits `lb_dar_al_fatwa`, Lebanon's Dar al-Fatwa (plan 8.1, 8.2, 8.7, 8.8, Q10; milestone AM10, 9.4), and writes its
official-zone asset, android-app/app/src/main/assets/official-zones/lb_dar_al_fatwa.json (schema 1).

Data, read at run time and never copied (D13, A7):
- test-data-local/research-2026-10-11/LB/imsak1445.csv: Dar al-Fatwa's Ramadan 1445 imsakiya for Beirut (2024-03-11
  to 04-09, the only official table at hand), transcribed by hand; local clock, summer time from 03-31, 12-hour
  afternoon times.
- test-data-local/research-2026-10-08/LB/lb/region2.json: the perpetual 366-row Beirut table that tv.masjidi.app serves
  to Dar al-Fatwa's own screen at Ras Beirut (standard time, rows by day of a leap year). It equals the imsakiya on all
  180 times (checked below), so it is the same table; it is what the fit runs on, the imsakiya being its official copy.

The table is perpetual and was computed long ago (Q10): its sun matches a year in 1903's leap phase. Reproducing it
needs a sun taken once a day (E4), the way a table computed from almanac values would be. The search loops use the
fast closed-form code below; every score printed and every FitStats comes from common.compute, the reference engine.

Stages (9.4), each by windows (a published minute bounds the unrounded time to a minute; the value most windows cover
wins), on the year table without its shifted rows (quirk days, below):
1. Dhuhr: sun model (E14), evaluation (E4) and reference year (E5) from the Dhuhr column alone.
2. The design: for each sun model, the reference year that maximises the six free covers (each time with its best
   free shift, angle or horizon), with one evaluation for all six times (EVENT_INSTANT, DAILY_0H_UT or DAILY_10H_UT)
   or each time's own (E4 is a per-event map).
3. The point: the latitude by the free covers of the four horizon and twilight times, then the longitude from the
   Dhuhr windows with the rounding that puts the point nearest the town (as for Libya).
4. Each time's minutes and rounding (a shift window holding a whole number is NEAREST + k, a half UP + floor or
   DOWN + ceil, the smaller minute; 9.4), the
   sunrise and sunset horizons (Standard, or a fixed altitude), the Fajr and Isha angles, Asr on the geometric or the
   apparent sun (E13).
Quirk days: rows that match the next date's computed times better than their own (a block in early March, which the
imsakiya's first two days share), found with a first pass and counted apart.
Held out: the year table by month-split (stages 1-4 refitted on the odd months and scored on the even ones, and the
reverse), scored as the app shows it, computed for the actual year (A8): 2026 for the year table (served for 2026),
2024 for the imsakiya, whose days each fold scores with the half that left their month out.

Usage: python scripts/prayer_formula/fits/lb_dar_al_fatwa/fit.py
"""

from __future__ import annotations

import argparse
import csv
import datetime
import json
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import common  # noqa: E402

ref = common.ref
EVENTS = common.EVENTS
AUTHORITY = "lb_dar_al_fatwa"
TZ = "Asia/Beirut"                        # UTC+2, summer time UTC+3 (the year table is in standard time)
TZ_MIN = 120
IMSAKIYA = common.LOCAL_DATA / "research-2026-10-11" / "LB" / "imsak1445.csv"
YEAR_TABLE = common.LOCAL_DATA / "research-2026-10-08" / "LB" / "lb" / "region2.json"
KEYS = dict(zip(EVENTS, ("Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha")))
D2R = math.pi / 180
MODELS = ("MEEUS", "VSOP87", "TEXTBOOK")
MODES = ("EVENT_INSTANT", "DAILY_0H_UT", "DAILY_10H_UT", "KACST")
DESIGN_MODES = ("EVENT_INSTANT", "DAILY_0H_UT", "DAILY_10H_UT")
YEARS = range(1880, 2027)                 # reference years tried: to the table's capture
TOWN = (33.894, 35.502)                   # Beirut (plan 8.7), the zone's point for mapping
APP_YEAR = 2026                           # the year the year table was served for
QUIRK_GAIN = 1.5                          # minutes (four events summed) by which the next date must fit better
FRACTION_GAIN = 0.02                      # an E7 fraction, or a fixed horizon over Standard, only past 2 % of the days
MODEL_GAIN = 0.005                        # another sun model than MEEUS only past 0.5 % of the times
SOURCE_LINE =("Dar al-Fatwa's Beirut table (the Ramadan 1445 imsakiya, and the perpetual year table its screen at Ras "
               "Beirut shows, equal on all 180 times), computed for the actual year (A8); one zone for all of Lebanon")
IMSAKIYA_SOURCE = "darelfatwa.gov.lb Ramadan 1445 imsakiya, Beirut (official)"
YEAR_SOURCE = ("tv.masjidi.app region 2: the perpetual Beirut table Dar al-Fatwa's screen at Ras Beirut shows "
               "(equals the imsakiya), scored for 2026")


# ------------------------------------------------------------------------------------------------------------
# Data

def minute(text: str) -> int:
    h, m = text.strip().split(":")[:2]
    return int(h) * 60 + int(m)


def load_year_table() -> dict[tuple[int, int], dict[str, int]]:
    """(month, day) -> the six minutes in standard time (UTC+2); rows by day of a leap year."""
    rows = json.loads(YEAR_TABLE.read_text(encoding="utf-8"))
    if len(rows) != 366:
        raise SystemExit(f"year table: {len(rows)} rows")
    out = {}
    for i, r in enumerate(rows):
        d = datetime.date(2024, 1, 1) + datetime.timedelta(days=i)
        out[(d.month, d.day)] = {e: minute(r[KEYS[e]]) for e in EVENTS}
    return out


def load_imsakiya() -> dict[datetime.date, dict[str, int]]:
    """date -> the six minutes in standard time (UTC+2): afternoon times read as 12-hour, summer time removed."""
    out = {}
    with open(IMSAKIYA, encoding="utf-8-sig", newline="") as fh:
        for r in csv.DictReader(fh):
            day = datetime.date.fromisoformat(r["date"])
            t = {e: minute(r[e.lower()]) for e in EVENTS}
            for e in ("ASR", "MAGHRIB", "ISHA"):
                t[e] += 720
            dst = common.utc_offset(TZ, day) // 60 - TZ_MIN
            out[day] = {e: v - dst for e, v in t.items()}
    return out


def isleap(y: int) -> bool:
    return y % 4 == 0 and (y % 100 != 0 or y % 400 == 0)


def at_year(md: tuple[int, int], year: int) -> datetime.date | None:
    """The row of (month, day) at [year]; the 29 February row only in a leap year."""
    if md == (2, 29) and not isleap(year):
        return None
    return datetime.date(year, *md)


def rows_at(table, mds, year):
    out = []
    for md in mds:
        d = at_year(md, year)
        if d is not None:
            out.append((d, table[md]))
    return out


# ------------------------------------------------------------------------------------------------------------
# Fast model for the search loops (S2-S7; Beirut's cycle date is the civil date)

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


def noon_minute(eot: float, lng: float) -> float:
    return 720 + TZ_MIN - eot - 4 * lng


def sun_of(p: dict, e: str, day, t) -> tuple:
    return sun(day, p["model"], p["modes"][e], t[e])


def base_minute(e: str, day, t, lat: float, lng: float, p: dict) -> float:
    """The unrounded local minute of [e] before any offset."""
    decl, eot, _ = sun_of(p, e, day, t)
    noon = noon_minute(eot, lng)
    if e == "DHUHR":
        return noon
    if e == "ASR":
        decl_noon = sun(day, p["model"], p["modes"]["ASR"], t["DHUHR"])[0]
        return noon + 4 * hour_angle(asr_altitude(lat, decl_noon, p.get("apparent", False)), lat, decl)
    if e in ("FAJR", "ISHA"):
        alt = -p["angles"][e]
    else:
        alt = p["horizons"][e]
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


def shift_windows(e, rows, lat, lng, p):
    out = []
    for day, t in rows:
        u = base_minute(e, day, t, lat, lng, p)
        out.append((t[e] - 0.5 - u, t[e] + 0.5 - u))
    return out


def altitude_windows(e, rows, lat, lng, p, shift):
    """The horizon (sunrise, sunset) or twilight (Fajr, Isha, as positive depressions) values giving each minute."""
    out = []
    for day, t in rows:
        decl, eot, _ = sun_of(p, e, day, t)
        noon = noon_minute(eot, lng)
        a = [altitude_at(lat, decl, (m - shift - noon) / 4) for m in (t[e] - 0.5, t[e] + 0.5)]
        if e in ("FAJR", "ISHA"):
            a = [-x for x in a]
        out.append((min(a), max(a)))
    return out


def reading(windows, fractions: bool = True) -> dict:
    """The whole or half minute most windows hold (NEAREST + k; a half UP + floor or DOWN + ceil; 9.4), with an E7
    fraction where the best stretch wins more than 2 % of the days and 2 days."""
    best, stretches = cover(windows)
    lo = math.floor(min(a for a, _ in windows)) - 1
    hi = math.ceil(max(b for _, b in windows)) + 1
    cands = [k / 2 for k in range(2 * lo, 2 * hi + 1)]
    counts = {c: sum(a <= c < b for a, b in windows) for c in cands}
    mid = (stretches[0][0] + stretches[0][1]) / 2
    c = max(cands, key=lambda c: (counts[c], -abs(c - mid)))
    if c == int(c):
        rounding, offset = "NEAREST", int(c)
    else:   # a half: UP + floor(c) or DOWN + ceil(c), the same table; the smaller minute
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

def free_covers(rows, lat, lng, p) -> dict[str, int]:
    """Each time's best cover with a free shift (Dhuhr, Asr), angle (Fajr, Isha) or horizon (sunrise, Maghrib)."""
    out = {}
    for e in ("DHUHR", "ASR"):
        out[e] = cover(shift_windows(e, rows, lat, lng, p))[0]
    for e in ("FAJR", "ISHA", "SUNRISE"):
        out[e] = cover(altitude_windows(e, rows, lat, lng, p, 0.0))[0]
    out["MAGHRIB"] = cover(altitude_windows("MAGHRIB", rows, lat, lng, p, 4.0))[0]
    return out


def stage_dhuhr(table, mds, say) -> None:
    res = []
    for model in MODELS:
        for mode in MODES:
            for year in YEARS:
                wins = []
                for day, t in rows_at(table, mds, year):
                    eot = sun(day, model, mode, t["DHUHR"])[1]
                    lo = t["DHUHR"] - 0.5 - noon_minute(eot, 0.0)
                    wins.append((lo, lo + 1))
                res.append((cover(wins)[0], model, mode, year))
    best = {m: max((r for r in res if r[1] == m), key=lambda r: r[0]) for m in MODELS}
    say("  stage 1, Dhuhr windows (of %d): %s" % (len(mds), ", ".join(f"{m} {best[m][0]} ({best[m][2]}, {best[m][3]})" for m in MODELS)))


def stage_design(table, mds, say) -> dict:
    """Stage 2: per sun model and year, the free covers under each evaluation; the uniform and the per-event designs."""
    lat, lng = TOWN
    per = {}                          # (model, year) -> mode -> event -> cover
    for model in ("MEEUS", "VSOP87"):
        for year in YEARS:
            rows = rows_at(table, mds, year)
            per[(model, year)] = {m: free_covers(rows, lat, lng, {"model": model, "modes": dict.fromkeys(EVENTS, m)})
                                  for m in DESIGN_MODES}
    designs = []
    for (model, year), by_mode in per.items():
        for m in DESIGN_MODES:
            designs.append((sum(by_mode[m].values()), model, year, dict.fromkeys(EVENTS, m), by_mode[m]))
        pick = {e: max(DESIGN_MODES, key=lambda m: by_mode[m][e]) for e in EVENTS}
        designs.append((sum(by_mode[pick[e]][e] for e in EVENTS), model, year, pick, {e: by_mode[pick[e]][e] for e in EVENTS}))
    designs.sort(key=lambda d: (-d[0], d[1] != "MEEUS", -d[2]))
    uniform = {m: max((d for d in designs if set(d[3].values()) == {m}), key=lambda d: d[0]) for m in DESIGN_MODES}
    for m, d in uniform.items():
        say(f"  stage 2, all at {m}: {d[0]} of {6 * len(mds)} ({d[1]} {d[2]}) {d[4]}")
    by_model = {m: next(d for d in designs if d[1] == m) for m in ("MEEUS", "VSOP87")}
    for m, d in by_model.items():
        say(f"  stage 2, {m}, each time its own evaluation: {d[0]} at {d[2]}: "
            + ", ".join(f"{e.lower()} {d[3][e]} {d[4][e]}" for e in EVENTS))
    best = designs[0]
    if best[1] != "MEEUS" and best[0] - by_model["MEEUS"][0] <= MODEL_GAIN * 6 * len(mds):
        best = by_model["MEEUS"]                       # the default sun unless another wins clearly
    n, model, year, modes, counts = best
    say(f"  stage 2 -> {model} at {year}, " + ", ".join(f"{e.lower()} {modes[e]}" for e in EVENTS))
    return {"model": model, "year": year, "modes": modes}


def stage_point(rows, p, say) -> tuple[float, float]:
    """Stage 3: latitude from the four horizon and twilight covers, longitude from Dhuhr nearest the town."""
    lng0 = TOWN[1]
    lats = [round(TOWN[0] - 0.08 + 0.004 * i, 3) for i in range(41)]
    scores = []
    for lat in lats:
        c = free_covers(rows, lat, lng0, p)
        scores.append((c["FAJR"] + c["ISHA"] + c["SUNRISE"] + c["MAGHRIB"], lat))
    top = max(s for s, _ in scores)
    best = [lat for s, lat in scores if s == top]
    lat = best[(len(best) - 1) // 2]
    # Longitude: Dhuhr's windows of the shift at the town; each whole or half minute is a rounding at its own
    # longitude (4 min per degree); the one nearest the town wins.
    wins = shift_windows("DHUHR", rows, lat, lng0, p)
    best_n, stretches = cover(wins)
    mid = (stretches[0][0] + stretches[0][1]) / 2
    cand = min((k / 2 for k in range(-4, 5)), key=lambda c: abs(c - mid))
    lng = round(lng0 + (cand - mid) / 4, 4)
    say(f"  stage 3, latitude {lat} ({top} of {4 * len(rows)} at the four times; tied {best[0]}-{best[-1]}); Dhuhr "
        f"{best_n} of {len(rows)} at a shift of {mid:+.3f} min at the town -> {cand:+.1f} at longitude {lng} E")
    return lat, lng


def fit(table, mds, say=print, dhuhr_report: bool = False) -> dict:
    """Stages 1-4 on the rows [mds] of the year table (stage 1 only reports, so it runs once)."""
    if dhuhr_report:
        stage_dhuhr(table, mds, say)
    p = stage_design(table, mds, say)
    rows = rows_at(table, mds, p["year"])
    lat, lng = stage_point(rows, p, say)
    p["point"] = (lat, lng)
    # Horizons: Standard, or the fixed altitude most windows cover at the shift the windows prefer.
    p["horizons"], readings = {}, {}
    for e, shifts in (("SUNRISE", (-1.0, -0.5, 0.0, 0.5, 1.0)), ("MAGHRIB", (3.0, 3.5, 4.0, 4.5, 5.0))):
        options = []
        for s in shifts:
            c, stretches = cover(altitude_windows(e, rows, lat, lng, p, s))
            a, b = stretches[0]
            options.append((c, -abs((a + b) / 2 + 0.8333), s, round((a + b) / 2, 4)))
        std = {s: sum(lo <= -0.8333 < hi for lo, hi in altitude_windows(e, rows, lat, lng, p, s)) for s in shifts}
        c, _, s, alt = max(options)
        s_std = max(std, key=std.get)
        if std[s_std] >= c - FRACTION_GAIN * len(rows):
            alt = -0.8333
        p["horizons"][e] = alt
        say(f"  stage 4, {e.lower()} horizon: fixed {max(options)[3]} at shift {s:+.1f} ({c} of {len(rows)}); Standard "
            f"best {std[s_std]} at {s_std:+.1f} -> {alt}")
    for e in ("FAJR", "ISHA"):
        best, stretches = cover(altitude_windows(e, rows, lat, lng, p, 0.0))
        a, b = stretches[0]
        p.setdefault("angles", {})[e] = round((a + b) / 2, 2)
        say(f"  stage 4, {e.lower()} angle: {best} of {len(rows)} at {a:.4f}-{b:.4f} -> {p['angles'][e]}")
    for e in ("DHUHR", "SUNRISE", "MAGHRIB"):
        readings[e] = reading(shift_windows(e, rows, lat, lng, p))
        say(f"  stage 4, {e.lower()}: " + describe(readings[e]))
    asr = {ap: reading(shift_windows("ASR", rows, lat, lng, dict(p, apparent=ap))) for ap in (False, True)}
    p["apparent"] = asr[True]["plain"] > asr[False]["plain"]
    readings["ASR"] = asr[p["apparent"]]
    say("  stage 4, asr: geometric " + describe(asr[False]) + "; apparent " + describe(asr[True]))
    for e in ("FAJR", "ISHA"):
        readings[e] = reading(shift_windows(e, rows, lat, lng, p))
    p["readings"] = readings
    return p


def quirk_rows(table, mds, p) -> list[tuple[int, int]]:
    """Rows whose times match the next date's computed times better than their own by QUIRK_GAIN minutes (the four
    events away from noon), then the runs they form with gaps of one day filled."""
    lat, lng = p["point"]
    flagged = []
    for md in mds:
        d = at_year(md, p["year"])
        if d is None:
            continue
        t = table[md]
        def err(day):
            return sum(abs(base_minute(e, day, t, lat, lng, p) + p["readings"][e]["value"] - t[e])
                       for e in ("FAJR", "SUNRISE", "MAGHRIB", "ISHA"))
        if err(d + datetime.timedelta(days=1)) < err(d) - QUIRK_GAIN:
            flagged.append(d)
    runs = []
    for d in flagged:
        if runs and (d - runs[-1][-1]).days <= 2:
            runs[-1].append(d)
        else:
            runs.append([d])
    out = []
    for run in runs:
        if len(run) >= 3:
            d = run[0]
            while d <= run[-1]:
                out.append((d.month, d.day))
                d += datetime.timedelta(days=1)
    return out


# ------------------------------------------------------------------------------------------------------------
# The profile, the zone, the scores (reference engine)

def method_of(p: dict) -> dict:
    r = p["readings"]
    offsets = {e: r[e]["offset"] for e in ("SUNRISE", "DHUHR", "ASR", "MAGHRIB") if r[e]["offset"]}
    rounding = {e: r[e]["rounding"] if e in r else "NEAREST" for e in EVENTS}

    def horizon(e):
        a = p["horizons"][e]
        return {"type": "standard"} if a == -0.8333 else {"type": "fixed", "altitudeDeg": a}
    modes = {e: m for e, m in p["modes"].items() if m != "EVENT_INSTANT"}
    return common.method_json(
        id=AUTHORITY, fajr={"type": "angle", "degrees": p["angles"]["FAJR"]}, isha={"type": "angle", "degrees": p["angles"]["ISHA"]},
        offsets=offsets, rounding=rounding, sunModel=p["model"], sunEvaluation=modes,
        asrSun="APPARENT" if p["apparent"] else "GEOMETRIC",
        horizon={"sunrise": horizon("SUNRISE"), "sunset": horizon("MAGHRIB")},
        highLatitude={"type": "angle_based"}, evidence={"kind": "FROM_TABLES", "source": "fit"})


def zone_of(p: dict) -> dict:
    lat, lng = p["point"]
    fractions = {e: p["readings"][e]["fraction"] for e in p["readings"] if p["readings"][e]["fraction"]}
    town = common.point(*TOWN)
    computing = common.point(lat, lng)
    z = common.zone_json("beirut", TOWN[0], TOWN[1], nameAr="بيروت", offsets=fractions,
                         morningPoint=computing if computing != town else None,
                         eveningPoint=computing if computing != town else None)
    z.pop("authority", None)
    return z


def table_minutes(method: dict, zone: dict, day: datetime.date) -> dict[str, int]:
    """The engine's six minutes in standard time (the year table's clock)."""
    got = common.compute(common.case(method, TOWN[0], TOWN[1], TZ, day, zone=zone, country="LB"))
    return {e: (got[e][1] + TZ_MIN * 60) // 60 % 1440 for e in EVENTS}


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


def score_rows(score: common.Score, method: dict, zone: dict, items, worst: dict | None = None) -> None:
    for day, t, label_day in items:
        got = table_minutes(method, zone, day)
        for e in EVENTS:
            score.add(e, label_day, got[e], t[e])
            if worst is not None:
                worst[e] = max(worst.get(e, 0), abs(got[e] - t[e]))


# The validity guard's cases (6.5, 8.8, 12), a side check at approximate town points (not verified). True = the
# plan expects Beirut's table kept there.
GUARD_PLACES = (("Saida", 33.5631, 35.3689, True), ("Tripoli", 34.4367, 35.8497, True), ("Zahle", 33.8497, 35.9042, True),
                ("Baalbek", 34.0058, 36.2181, True), ("Nabatieh", 33.3772, 35.4836, True), ("Tyre", 33.2733, 35.1939, False),
                ("Naqoura", 33.1181, 35.1397, False), ("Hermel", 34.3942, 36.3847, False), ("Qaa", 34.3436, 36.4772, False),
                ("Qoubaiyat", 34.5703, 36.2797, False))


def guard_check(method: dict, zone: dict, year: int = APP_YEAR, say=print) -> None:
    """The guard of 6.5 for each place: the table's leads over the method at the place's own point, unrounded, against
    the table's own margin at the zone's point (the town), plus 4 minutes."""
    days = [datetime.date(year, 1, 1) + datetime.timedelta(days=i) for i in range(365)]

    def run(lat, lng, z=None):
        return [common.compute(common.case(method, lat, lng, TZ, d, zone=z, country="LB")) for d in days]

    def leads(r_days, t_days):
        out = {e: [] for e in EVENTS}
        for r, t in zip(r_days, t_days):
            for e in EVENTS:
                lead = (r[e][0] - t[e][1]) / 60
                out[e].append(-lead if e == "SUNRISE" else lead)
        return out
    t_days = run(TOWN[0], TOWN[1], zone)
    margin = {e: max(v) for e, v in leads(run(*TOWN), t_days).items()}
    say("  the table's own margins at Beirut (min): " + ", ".join(f"{e[:3]} {margin[e]:+.1f}" for e in EVENTS))
    for name, lat, lng, expect in GUARD_PLACES:
        excess = {e: max(v) - margin[e] for e, v in leads(run(lat, lng), t_days).items()}
        worst = max(excess, key=excess.get)
        keeps = excess[worst] <= 4
        say("  %-9s worst %s %+.1f min beyond the margin: %s%s" % (
            name, worst.lower(), excess[worst], "keeps Beirut's table" if keeps else "its own point",
            "" if keeps == expect else "  (the plan expects the other verdict)"))


def shares(score: common.Score) -> str:
    return " ".join(f"{e[:3]} {score.exact[e]}/{score.total[e]}" for e in EVENTS)


def pct(score: common.Score) -> str:
    return " ".join(f"{e[:3]} {100 * score.exact[e] / score.total[e]:.1f}%/{100 * score.within1[e] / score.total[e]:.1f}%"
                    for e in EVENTS if score.total[e])


def within(score_misses, total, n) -> str:
    """The share within n minutes from a Score's misses list (misses are the differences beyond 1 min)."""
    beyond = sum(1 for _, _, diff in score_misses if abs(diff) > n)
    return f"{100 * (total - beyond) / total:.1f}%"


# ------------------------------------------------------------------------------------------------------------

def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--no-guard", action="store_true", help="skip the validity-guard side check")
    args = ap.parse_args(argv)
    sys.stdout.reconfigure(encoding="utf-8")

    table = load_year_table()
    imsakiya = load_imsakiya()
    equal = sum(imsakiya[d][e] == table[(d.month, d.day)][e] for d in imsakiya for e in EVENTS)
    print(f"year table 366 rows; imsakiya {len(imsakiya)} days ({min(imsakiya)} to {max(imsakiya)}), equal to the year "
          f"table on {equal} of {6 * len(imsakiya)} times")
    all_mds = sorted(table)

    print("First pass, all rows (to find the shifted rows):")
    first = fit(table, all_mds, say=lambda s: None)
    quirks = quirk_rows(table, all_mds, first)
    print(f"  quirk rows: {', '.join(f'{m:02d}-{d:02d}' for m, d in quirks)} (they match the next date's times)")
    mds = [md for md in all_mds if md not in quirks]

    print("Year table without the quirk rows:")
    p = fit(table, mds, dhuhr_report=True)
    method, zone = method_of(p), zone_of(p)

    # Held out: month-split; each half's parameters score the other half for 2026 and, for information, at the
    # reference year; the imsakiya's days (2024) are scored by the half that left their month out.
    folds_app, folds_ref, folds_ims = [], [], []
    quirk_score = common.Score()
    worst_app = {}
    for label, keep in (("odd", 1), ("even", 0)):
        print(f"Year table, {label} months:")
        part = fit(table, [md for md in mds if md[0] % 2 == keep])
        m_part, z_part = method_of(part), zone_of(part)
        held = [md for md in mds if md[0] % 2 != keep]
        s_app, s_ref, s_ims = common.Score(), common.Score(), common.Score()
        score_rows(s_app, m_part, z_part, [(at_year(md, APP_YEAR), table[md], at_year(md, APP_YEAR)) for md in held
                                           if at_year(md, APP_YEAR)], worst_app)
        score_rows(s_ref, m_part, z_part, [(at_year(md, part["year"]), table[md], at_year(md, APP_YEAR) or datetime.date(2024, 2, 29))
                                           for md in held if at_year(md, part["year"])])
        score_rows(s_ims, m_part, z_part, [(d, t, d) for d, t in imsakiya.items()
                                           if d.month % 2 != keep and (d.month, d.day) not in quirks])
        score_rows(quirk_score, m_part, z_part, [(d, t, d) for d, t in imsakiya.items()
                                                 if d.month % 2 != keep and (d.month, d.day) in quirks])
        folds_app.append(s_app)
        folds_ref.append(s_ref)
        folds_ims.append(s_ims)
        print(f"  held out on the {'even' if keep else 'odd'} months: for 2026 " + shares(s_app) + f"; at {part['year']} "
              + shares(s_ref) + "; imsakiya days (2024) " + shares(s_ims))
    held_app, held_ref, held_ims = merge(folds_app), merge(folds_ref), merge(folds_ims)
    held_all = merge([held_app, held_ims])

    # The zone, its FitStats and the asset.
    ims_days = sorted(imsakiya)
    quirk_days = len(quirks) + sum((d.month, d.day) in quirks for d in imsakiya)
    fitted_on = [common.sample(YEAR_SOURCE.replace(", scored for 2026", ""), datetime.date(2026, 1, 1),
                               datetime.date(2026, 12, 31), 366, 1)]
    held_out = [common.sample(IMSAKIYA_SOURCE, ims_days[0], ims_days[-1], len(ims_days), 1, month_split=True),
                common.sample(YEAR_SOURCE, datetime.date(2026, 1, 1), datetime.date(2026, 12, 31), 365, 1, month_split=True)]
    stats = common.fit_stats(fitted_on, held_out, held_all, ramadan_days=len(ims_days), quirk_days=quirk_days)
    stats["maxDiffMin"] = max(held_all.worst, quirk_score.worst)
    zone_asset = {k: v for k, v in zone.items()}
    zone_asset["namedPlaces"] = [{"centre": zone["point"], "catalogueIds": [], "areaKm": 0.0}]
    zone_asset["fit"] = stats
    path = common.write_asset(AUTHORITY, AUTHORITY, "LB", "NATIONAL", [zone_asset], fit=stats, source=SOURCE_LINE)

    # Final scores with the shipped values.
    final_ref, final_app, final_ims = common.Score(), common.Score(), common.Score()
    score_rows(final_ref, method, zone, [(at_year(md, p["year"]), table[md], at_year(md, APP_YEAR) or datetime.date(2024, 2, 29))
                                         for md in mds if at_year(md, p["year"])])
    score_rows(final_app, method, zone, [(at_year(md, APP_YEAR), table[md], at_year(md, APP_YEAR)) for md in mds if at_year(md, APP_YEAR)])
    score_rows(final_ims, method, zone, [(d, t, d) for d, t in imsakiya.items()])
    r = p["readings"]
    print("Profile: Fajr %.2f, Isha %.2f; %s; sunrise %s %+d at %s, Dhuhr %s %+d, Asr %s %+d (%s sun), Maghrib %s %+d at %s;"
          " %s sun, evaluation %s; reference year %d; computing point %.4f N %.4f E" % (
              p["angles"]["FAJR"], p["angles"]["ISHA"], ", ".join(f"{e} {r[e]['rounding']}" for e in ("FAJR", "ISHA")),
              r["SUNRISE"]["rounding"], r["SUNRISE"]["offset"], p["horizons"]["SUNRISE"], r["DHUHR"]["rounding"],
              r["DHUHR"]["offset"], r["ASR"]["rounding"], r["ASR"]["offset"], "apparent" if p["apparent"] else "geometric",
              r["MAGHRIB"]["rounding"], r["MAGHRIB"]["offset"], p["horizons"]["MAGHRIB"], p["model"],
              ", ".join(f"{e.lower()} {m}" for e, m in p["modes"].items()), p["year"], *p["point"]))
    print("Held out (FitStats; the imsakiya for 2024 and the year table for 2026, quirk days apart): " + pct(held_all))
    print("  year table for 2026: " + pct(held_app) + "; within 2 min " + ", ".join(
        f"{e[:3]} {within(held_app.misses and [m for m in held_app.misses if m[0] == e], held_app.total[e], 2)}" for e in EVENTS)
          + "; worst " + ", ".join(f"{e[:3]} {worst_app[e]}" for e in EVENTS))
    print("  imsakiya for 2024: " + pct(held_ims))
    print(f"  year table at the reference years: " + pct(held_ref))
    print(f"Final values on the year table at {p['year']} (in-sample): " + shares(final_ref) + f"; worst {final_ref.worst}")
    print("Final values on the year table for 2026: " + shares(final_app) + f"; worst {final_app.worst}")
    print("Final values on the imsakiya for 2024 (its 2 quirk days included): " + shares(final_ims) + f"; worst {final_ims.worst}")
    if not args.no_guard:
        print(f"Validity guard, {APP_YEAR} (side check):")
        guard_check(method, zone)
    print(f"Wrote {path.relative_to(common.ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
