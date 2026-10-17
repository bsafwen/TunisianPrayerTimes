#!/usr/bin/env python3
"""
Fits `iq_sunni_endowment`, Iraq's Sunni Endowment (plan 8.1, 8.2, 8.7, 8.8, Q8; milestone AM2, 9.4), and writes its
official-zone asset, android-app/app/src/main/assets/official-zones/iq_sunni_endowment.json (schema 1).

Data, read at run time and never copied (D13, A7):
- test-data-local/research-2026-10-08/IQ/iq/dws_2026.json: the Endowment's table for Baghdad and its suburbs, every
  day of 2026 (dws.gov.iq API). A perpetual table computed for one year and reused (8.3, Q8).
- C:/Accurate Times/loc.txt (ICOP's Accurate Times 5.7, the owner's install; supportive evidence, 8.5): its 31 Iraqi
  entries. The 28 outside the Kurdistan Region are the zones (8.8): each entry's point, elevation, pressure,
  temperature, relative humidity and lapse rate. Read in place; only the points, elevations and the horizons computed
  from them go into the asset.
- test-data-local/research-2026-10-11/HIJRI/icop_ghc_1428_1450.csv: only to count held-out Ramadan days.
- mosque-dataset/mosques.db (the side check only): the calendars of the mosques near Mosul's and Kirkuk's entries,
  evidence for the horizon law at other elevations, never fitted to (5.6).

The search loops use the fast closed-form code below (the sun at the published minute, which is within a minute of
the event); every score printed and every FitStats comes from common.compute, the reference engine.

Stages (9.4), each by windows (a published minute bounds the unrounded time to a minute; the value most windows cover
wins), at Accurate Times' Baghdad entry, which stays the computing point (stage 6 checks it):
1. Dhuhr: sun model (E14), evaluation (E4) and reference year (E5) from the Dhuhr column alone.
2. The reference year again, by the joint count of all six times, each with its best free shift or angle.
3. Sunrise and Maghrib: the horizon law (the engine's Standard and Refraction models, and the Hohenkerk & Sinclair law
   that Accurate Times documents), then each time's minutes and rounding: a shift window that holds a whole number is
   NEAREST with that offset, one that holds a half is UP + floor or DOWN + ceil, the smaller minute (9.4: rounding is
   "nearest after a shift of ±0.5").
4. Fajr and Isha: the angle windows.
5. Asr: the geometric or the apparent sun (E13), with its noon shadow at the transit or at the published Dhuhr instant
   (E4 SHADOW_AT_DHUHR), whichever form the whole minutes reproduce best. Any time takes an E7 fraction only where the
   whole minute loses more than 2 % of the days (and 2 days) to the best window.
6. The point: a grid around the entry, reported only (the entry is kept unless the grid beats it by more than 1 %).
Held out: the 2026 table by month-split (stages 1-5 refitted on the odd months and scored on the even ones, and the
reverse), scored as the app shows it, the table as printed (A8). The 27 other cities have no official data.
The asset also lists the Kurdistan Region's governorates in its top-level "exactAdmin1" (6.5: EXACT there).

Usage: python scripts/prayer_formula/fits/iq_sunni_endowment/fit.py [--no-mosques]
"""

from __future__ import annotations

import argparse
import csv
import datetime
import json
import math
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import common  # noqa: E402

ref = common.ref
EVENTS = common.EVENTS
AUTHORITY = "iq_sunni_endowment"
TZ = "Asia/Baghdad"                       # UTC+3 without summer time since 2008 (checked on every row)
TZ_MIN = 180
TABLE = common.LOCAL_DATA / "research-2026-10-08" / "IQ" / "iq" / "dws_2026.json"
GHC = common.LOCAL_DATA / "research-2026-10-11" / "HIJRI" / "icop_ghc_1428_1450.csv"
LOC = Path("C:/Accurate Times/loc.txt")
MOSQUES = common.ROOT.parents[2] / "mosque-dataset" / "mosques.db"
KEYS = dict(zip(EVENTS, ("fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha")))
D2R = math.pi / 180
MODELS = ("VSOP87", "MEEUS", "TEXTBOOK")
MODES = ("EVENT_INSTANT", "DAILY_0H_UT", "DAILY_10H_UT", "KACST")
YEARS = range(2000, 2027)                 # reference years tried: to the year of the table
SEMI_DIAMETER = 16 / 60                   # the horizon law's constant semi-diameter (stage 3)
DIP_ARCMIN_PER_SQRT_M = 1.76              # the dip of the sea horizon with terrestrial refraction, 1.76' sqrt(h)
WAVELENGTH_UM = 0.574                     # SLALIB's effective visual wavelength for sla_REFRO
FRACTION_GAIN = 0.02                      # an E7 fraction only where it wins more than 2 % of the days
KURDISTAN = ("Dahuk", "Irbil", "Sulaymaniyah")   # Accurate Times' entries in the Kurdistan Region (EXACT there, 6.5)
KURDISTAN_ADMIN1 = ("05", "08", "11", "19")      # GeoNames IQ.05 Sulaymaniyah, IQ.08 Duhok, IQ.11 Erbil, IQ.19 Halabja
# Accurate Times' entry name -> (zone id, Arabic name).
NAMES = {
    "Al Qaim": ("al_qaim", "القائم"), "Amarah": ("amarah", "العمارة"), "Baghdad": ("baghdad", "بغداد"),
    "Baiji": ("baiji", "بيجي"), "Balad": ("balad", "بلد"), "Baqubah": ("baqubah", "بعقوبة"),
    "Basrah": ("basra", "البصرة"), "Diwaniyeh": ("diwaniyah", "الديوانية"), "Fallujah": ("fallujah", "الفلوجة"),
    "Haditha": ("haditha", "حديثة"), "Hilla": ("hillah", "الحلة"), "Karbala": ("karbala", "كربلاء"),
    "Kirkuk": ("kirkuk", "كركوك"), "Kufah": ("kufa", "الكوفة"), "Kut": ("kut", "الكوت"),
    "Mahmudiyah": ("mahmudiyah", "المحمودية"), "Mandali": ("mandali", "مندلي"), "Moosel": ("mosul", "الموصل"),
    "Muqdadiyah": ("muqdadiyah", "المقدادية"), "Najaf": ("najaf", "النجف"), "Nasiriyah": ("nasiriyah", "الناصرية"),
    "Ramadi": ("ramadi", "الرمادي"), "Rutba": ("rutba", "الرطبة"), "Samarra": ("samarra", "سامراء"),
    "Samawah": ("samawah", "السماوة"), "Sinjar": ("sinjar", "سنجار"), "Tall Afar": ("tal_afar", "تلعفر"),
    "Tikrit": ("tikrit", "تكريت"),
}
SOURCE_LINE = ("Iraq's Sunni Endowment (dws.gov.iq): Baghdad's perpetual table, shown as printed (reference year "
               "2021, A8); the 27 other cities computed as the Endowment computes Baghdad, at Accurate Times' "
               "entries (8.8)")
TABLE_SOURCE = "dws.gov.iq API: Baghdad and suburbs, 2026 (a perpetual table, reference year 2021)"


# ------------------------------------------------------------------------------------------------------------
# Data

def minute(text: str) -> int:
    h, m = text.strip().split(":")[:2]
    return int(h) * 60 + int(m)


def load_rows() -> dict[datetime.date, dict[str, int]]:
    data = json.loads(TABLE.read_text(encoding="utf-8"))
    out = {}
    for row in data.values():
        if row.get("is_closest"):
            continue
        day = datetime.date.fromisoformat(row["date"])
        if common.utc_offset(TZ, day) != TZ_MIN * 60:
            raise SystemExit(f"{day}: not UTC+3")
        out[day] = {e: minute(row[KEYS[e]]) for e in EVENTS}
    return dict(sorted(out.items()))


def load_entries() -> list[dict]:
    """Accurate Times' Iraqi entries: name, point, elevation, pressure, temperature, humidity, lapse rate."""
    out = []
    for parts in csv.reader(LOC.read_text(encoding="latin-1").splitlines()):
        if not parts or not parts[0].startswith("IRAQ "):
            continue
        lat = (int(parts[1]) + int(parts[2]) / 60 + int(parts[3]) / 3600) * (1 if parts[7].strip() == "1" else -1)
        lng = (int(parts[4]) + int(parts[5]) / 60 + int(parts[6]) / 3600) * (1 if parts[8].strip() == "1" else -1)
        out.append({"name": parts[0][5:], "lat": lat, "lng": lng, "h": float(parts[10]), "P": float(parts[12]),
                    "T": float(parts[13]), "rh": float(parts[14]) / 100, "lapse": float(parts[15].strip('"'))})
    return out


def ramadan_test():
    """Whether a date is in Ramadan by ICOP's calendar for the east (held-out Ramadan days only; no E8 rule here)."""
    starts = {}
    with open(GHC, encoding="utf-8-sig", newline="") as fh:
        for r in csv.DictReader(fh):
            starts[(int(r["year"]), r["month"].strip())] = datetime.date.fromisoformat(r["east_start"])
    spans = [(s, starts[(y, "شوال")]) for (y, m), s in starts.items() if m == "رمضان" and (y, "شوال") in starts]
    return lambda day: any(a <= day < b for a, b in spans)


def at_year(day: datetime.date, year: int | None) -> datetime.date:
    """The table's row for [day]'s month and day computed at [year] (E5); None keeps the actual date."""
    if year is None:
        return day
    return datetime.date(year, day.month, 28 if (day.month, day.day) == (2, 29) and year % 4 else day.day)


# ------------------------------------------------------------------------------------------------------------
# The horizon law: Hohenkerk & Sinclair's refraction (Explanatory Supplement 1992, 3.281, as Accurate Times documents;
# P. T. Wallace's sla_REFRO written out from the published algorithm) at the observed zenith distance 90 deg + D, with
# D = 1.76' sqrt(h) the dip of the sea horizon seen through the air, plus a constant 16' semi-diameter.

def refro(zobs: float, hm: float, tdk: float, pmb: float, rh: float, wl: float, phi: float, tlr: float,
          eps: float = 1e-9) -> float:
    """Refraction (radians) for an observed zenith distance [zobs] (radians) at height [hm] m, temperature [tdk] K,
    pressure [pmb] hPa, relative humidity [rh] (0-1), wavelength [wl] micrometres, latitude [phi] radians and
    tropospheric lapse rate [tlr] K/m."""
    d93, gcr, dmd, dmw, s, delta, ht, hs, ismax = 1.623156204, 8314.32, 28.9644, 18.0152, 6378120.0, 18.36, 11000.0, 80000.0, 16384
    zobs2 = min(abs(zobs), d93)
    hmok = min(max(hm, -1e3), hs)
    tdkok = min(max(tdk, 100.0), 500.0)
    pmbok = min(max(pmb, 0.0), 10000.0)
    rhok = min(max(rh, 0.0), 1.0)
    wlok = max(wl, 0.1)
    alpha = min(max(abs(tlr), 0.001), 0.01)
    tol = min(max(abs(eps), 1e-12), 0.1) / 2.0
    wlsq = wlok * wlok
    gb = 9.784 * (1.0 - 0.0026 * math.cos(phi + phi) - 0.00000028 * hmok)
    a = (287.6155 + (1.62887 + 0.01360 / wlsq) / wlsq) * 273.15e-6 / 1013.25
    gamal = (gb * dmd) / gcr
    gamma = gamal / alpha
    gamm2, delm2 = gamma - 2.0, delta - 2.0
    tdc = tdkok - 273.15
    psat = 10.0 ** ((0.7859 + 0.03477 * tdc) / (1.0 + 0.00412 * tdc)) * (1.0 + pmbok * (4.5e-6 + 6e-10 * tdc * tdc))
    pwo = rhok * psat / (1.0 - (1.0 - rhok) * psat / pmbok) if pmbok > 0 else 0.0
    w = pwo * (1.0 - dmw / dmd) * gamma / (delta - gamma)
    c1 = a * (pmbok + w) / tdkok
    c2 = (a * w + 11.2684e-6 * pwo) / tdkok
    c3 = (gamma - 1.0) * alpha * c1 / tdkok
    c4 = (delta - 1.0) * alpha * c2 / tdkok

    def atmt(r):
        t = max(min(tdkok - alpha * (r - r0), 320.0), 100.0)
        tt0 = t / tdkok
        tt0gm2, tt0dm2 = tt0 ** gamm2, tt0 ** delm2
        return t, 1.0 + (c1 * tt0gm2 - c2 * tt0dm2) * tt0, r * (-c3 * tt0gm2 + c4 * tt0dm2)

    def atms(r):
        b = gamal / tt
        ww = (dnt - 1.0) * math.exp(-b * (r - rt))
        return 1.0 + ww, -r * b * ww

    def refi(dn, rdndr):
        return rdndr / (dn + rdndr)

    def zenith(r, dn):
        sine = sk0 / (r * dn)
        return math.atan2(sine, math.sqrt(max(1.0 - sine * sine, 0.0)))

    r0 = s + hmok
    _, dn0, rdndr0 = atmt(r0)
    sk0 = dn0 * r0 * math.sin(zobs2)
    f0 = refi(dn0, rdndr0)
    rt = s + max(ht, hmok)
    tt, dnt, rdndrt = atmt(rt)
    zt, ft = zenith(rt, dnt), refi(dnt, rdndrt)
    dnts, rdndrp = atms(rt)
    zts, fts = zenith(rt, dnts), refi(dnts, rdndrp)
    rs = s + hs
    dns, rdndrs = atms(rs)
    zs, fs = zenith(rs, dns), refi(dns, rdndrs)
    total = 0.0
    for k in (1, 2):
        z0, zrange, fb, ff = (zobs2, zt - zobs2, f0, ft) if k == 1 else (zts, zs - zts, fts, fs)
        refold, strips, fo, fe, step, refp = 1.0, 8, 0.0, 0.0, 1, 0.0
        while True:
            h = zrange / strips
            r = r0 if k == 1 else rt
            for i in range(1, strips, step):
                sz = math.sin(z0 + h * i)
                if sz > 1e-20:
                    target, rg, dr, j = sk0 / sz, r, 1e6, 0
                    while abs(dr) > 1.0 and j < 4:
                        j += 1
                        dn, rdndr = atmt(rg)[1:] if k == 1 else atms(rg)
                        dr = (rg * dn - target) / (dn + rdndr)
                        rg -= dr
                    r = rg
                dn, rdndr = atmt(r)[1:] if k == 1 else atms(r)
                if step == 1 and i % 2 == 0:
                    fe += refi(dn, rdndr)
                else:
                    fo += refi(dn, rdndr)
            refp = h * (fb + 4.0 * fo + 2.0 * fe + ff) / 3.0
            if abs(refp - refold) > tol and strips < ismax:
                refold, strips, fe, fo, step = refp, strips * 2, fe + fo, 0.0, 2
            else:
                break
        total += refp
    return total


def horizon_law(h: float, pressure: float, temperature: float, rh: float, lapse: float, lat: float) -> float:
    """The sun's altitude at sunrise and sunset under the law (degrees)."""
    dip = DIP_ARCMIN_PER_SQRT_M / 60 * math.sqrt(max(h, 0.0))
    refraction = refro((90 + dip) * D2R, h, 273.15 + temperature, pressure, rh, WAVELENGTH_UM, lat * D2R, lapse) / D2R
    return -(SEMI_DIAMETER + refraction + dip)


def sqrt_height_fallback(lat: float) -> tuple[float, float]:
    """b and k of the engine's SqrtHeight model, -(b + k sqrt(h)), fitted to the law at 1010 hPa, 10 C, 60 %, from 0 to
    1500 m: the horizon at exact coordinates (the Kurdistan Region, the validity guard, «حسب موقعي بالضبط»)."""
    pts = [(math.sqrt(h), -horizon_law(h, 1010.0, 10.0, 0.6, 0.0065, lat)) for h in range(0, 1501, 50)]
    n, sx, sy = len(pts), sum(x for x, _ in pts), sum(y for _, y in pts)
    sxx, sxy = sum(x * x for x, _ in pts), sum(x * y for x, y in pts)
    k = (n * sxy - sx * sy) / (n * sxx - sx * sx)
    b = (sy - k * sx) / n
    worst = max(abs(y - b - k * x) for x, y in pts)
    return round(b, 4), round(k, 5), worst


# ------------------------------------------------------------------------------------------------------------
# Fast model for the search loops (S2-S7; Baghdad's cycle date is the civil date)

_SUN: dict = {}


def sun(day: datetime.date, model: str, mode: str, local_minute: float) -> tuple[float, float, float]:
    """(declination, equation of time, distance) the profile's sun gives on [day] for an event near [local_minute]."""
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


def asr_altitude(lat: float, decl_noon: float, apparent: bool, pressure: float, temperature: float) -> float:
    zenith = abs(lat - decl_noon)
    if not apparent:
        return math.atan(1 / (1 + math.tan(zenith * D2R))) / D2R
    h0 = 90 - zenith
    h0a = h0 + ref.bennett(h0, pressure, temperature)
    aa = math.atan(1 / (1 + math.tan((90 - h0a) * D2R))) / D2R
    return aa - ref.bennett(aa, pressure, temperature)


def noon_minute(eot: float, lng: float) -> float:
    return 720 + TZ_MIN - eot - 4 * lng


def base_minute(e: str, day: datetime.date, t: dict, lat: float, lng: float, p: dict, horizon=None) -> float:
    """The unrounded local minute of [e] before any offset; [horizon] maps "SUNRISE"/"MAGHRIB" to an altitude (or a
    function of the distance)."""
    decl, eot, dist = sun(day, p["model"], p["mode"], t[e])
    noon = noon_minute(eot, lng)
    if e == "DHUHR":
        return noon
    if e == "ASR":
        decl_noon = sun(day, p["model"], p["mode"], t["DHUHR"] - p.get("dhuhr_offset", 5))[0]
        if p.get("shadow_at_dhuhr"):
            # E4 SHADOW_AT_DHUHR (README S4, S7): the noon shadow at the published Dhuhr instant, transit + Dhuhr's
            # minutes, i.e. the true altitude there, refracted for the apparent form.
            h_noon = altitude_at(lat, decl_noon, p.get("dhuhr_offset", 5) / 4)
            alt = asr_altitude(lat, lat - (90 - h_noon), p.get("apparent", True), p["P"], p["T"])
        else:
            alt = asr_altitude(lat, decl_noon, p.get("apparent", True), p["P"], p["T"])
        return noon + 4 * hour_angle(alt, lat, decl)
    if e in ("FAJR", "ISHA"):
        alt = -p["angles"][e]
    else:
        a = horizon[e]
        alt = a(dist) if callable(a) else a
    return noon + (4 if e in ("MAGHRIB", "ISHA") else -4) * hour_angle(alt, lat, decl)


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


def shift_windows(e, rows, lat, lng, p, horizon=None):
    """Per published minute, the shifts s (minutes, NEAREST) with round(u + s) = minute."""
    out = []
    for day, t in rows:
        u = base_minute(e, day, t, lat, lng, p, horizon)
        out.append((t[e] - 0.5 - u, t[e] + 0.5 - u))
    return out


def angle_windows(e, rows, lat, lng, p):
    """Per published minute, the twilight angles that give it (NEAREST, no offset)."""
    out = []
    for day, t in rows:
        decl, eot, _ = sun(day, p["model"], p["mode"], t[e])
        noon = noon_minute(eot, lng)
        a = [-altitude_at(lat, decl, (m - noon) / 4) for m in (t[e] - 0.5, t[e] + 0.5)]
        out.append((min(a), max(a)))
    return out


def reading(windows, fractions: bool = True) -> dict:
    """The offset and rounding a shift window gives (9.4): the whole or half minute most windows hold; a whole number is
    NEAREST + k, a half UP + floor or DOWN + ceil (ceil(x + k) = round(x + k + 0.5)). "plain" is that reading's count;
    with [fractions],
    an E7 fraction (the middle of the best stretch) where the stretch wins more than 2 % of the days and 2 days."""
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
# Stages 1-5

def with_year(rows, year):
    return [(at_year(d, year), t) for d, t in rows]


def stage_dhuhr(rows, say=print) -> list[tuple[int, str, str, int]]:
    """Stage 1: Dhuhr windows of x = offset - 4 x longitude, per sun model, evaluation and year."""
    res = []
    for model in MODELS:
        for mode in MODES:
            for year in YEARS:
                wins = []
                for day, t in with_year(rows, year):
                    eot = sun(day, model, mode, t["DHUHR"] - 5)[1]
                    lo = t["DHUHR"] - 0.5 - noon_minute(eot, 0.0)
                    wins.append((lo, lo + 1))
                res.append((cover(wins)[0], model, mode, year))
    res.sort(key=lambda r: (-r[0], MODELS.index(r[1]), MODES.index(r[2]), -r[3]))
    by_model = {m: max(r[0] for r in res if r[1] == m) for m in MODELS}
    say("  stage 1, Dhuhr windows (of %d): %s; best %s/%s at %d" % (
        len(rows), ", ".join(f"{m} {by_model[m]}" for m in MODELS), res[0][1], res[0][2], res[0][3]))
    return res


LAWS = ("Standard", "StandardWithDip", "Refraction", "Hohenkerk-Sinclair law")


def law_altitude(name: str, entry: dict):
    """The sunrise and sunset altitude of a law tried in stage 3, at the entry's own inputs: a number, or for the
    engine's Refraction model a function of the sun's distance (its semi-diameter varies)."""
    h = entry["h"]
    if name == "Standard":
        return -0.8333
    if name == "StandardWithDip":
        return -0.8333 - ref.dip_deg(h)
    if name == "Refraction":
        return lambda dist: -(959.63 / 3600 / dist + ref.bennett(-ref.dip_deg(h), entry["P"], entry["T"]) + ref.dip_deg(h))
    return horizon_law(h, entry["P"], entry["T"], entry["rh"], entry["lapse"], entry["lat"])


def joint(rows, entry, p, horizon) -> tuple[int, dict]:
    """The six best covers at the entry, each time with its free shift or angle."""
    lat, lng = entry["lat"], entry["lng"]
    counts = {}
    for e in ("DHUHR", "ASR", "SUNRISE", "MAGHRIB"):
        counts[e] = cover(shift_windows(e, rows, lat, lng, p, horizon))[0]
    for e in ("FAJR", "ISHA"):
        counts[e] = cover(angle_windows(e, rows, lat, lng, p))[0]
    return sum(counts.values()), counts


def fit(rows, entry, say=print) -> dict:
    """Stages 1-5 on [rows] (actual dates); the profile values at the entry."""
    s1 = stage_dhuhr(rows, say)
    top = s1[0][0]
    pairs = []
    for c, model, mode, _ in s1:
        if c >= top - 2 and (model, mode) not in pairs:
            pairs.append((model, mode))
    law = law_altitude("Hohenkerk-Sinclair law", entry)
    base = {"P": entry["P"], "T": entry["T"], "apparent": True, "dhuhr_offset": 5}
    s2 = []
    for model, mode in pairs:
        for year in YEARS:
            p = dict(base, model=model, mode=mode)
            n, counts = joint(with_year(rows, year), entry, p, {"SUNRISE": law, "MAGHRIB": law})
            s2.append((n, model, mode, year, counts))
    s2.sort(key=lambda r: (-r[0], -r[3]))
    n, model, mode, year, counts = s2[0]
    runner = [f"{r[1]}/{r[2]} {r[3]} {r[0]}" for r in s2[1:4]]
    say(f"  stage 2, joint count of the free covers (of {6 * len(rows)}): {model}/{mode} at {year}: {n} {counts}; "
        "next: " + ", ".join(runner))
    p = dict(base, model=model, mode=mode, year=year)
    rows_y = with_year(rows, year)
    lat, lng = entry["lat"], entry["lng"]

    # Stage 3: the horizon law, by the days sunrise and Maghrib reproduce at their whole or half minute.
    laws = {}
    for name in LAWS:
        a = law_altitude(name, entry)
        hz = {"SUNRISE": a, "MAGHRIB": a}
        rs = {e: reading(shift_windows(e, rows_y, lat, lng, p, hz), fractions=False) for e in ("SUNRISE", "MAGHRIB")}
        laws[name] = (sum(r["plain"] for r in rs.values()), rs)
    law_name = max(LAWS, key=lambda k: (laws[k][0], -LAWS.index(k)))
    say("  stage 3, sunrise + Maghrib at their whole or half minute (of %d): %s -> %s" % (
        2 * len(rows), "; ".join(f"{k} {v[0]} (sunrise {v[1]['SUNRISE']['rounding']} {v[1]['SUNRISE']['offset']:+d}, "
                                 f"Maghrib {v[1]['MAGHRIB']['rounding']} {v[1]['MAGHRIB']['offset']:+d})"
                                 for k, v in laws.items()), law_name))
    p["horizon_law"] = law_name
    p["horizon"] = law_altitude(law_name, entry)
    hz = {"SUNRISE": p["horizon"], "MAGHRIB": p["horizon"]}
    readings = {}
    for e in ("DHUHR", "SUNRISE", "MAGHRIB"):
        readings[e] = reading(shift_windows(e, rows_y, lat, lng, p, hz))
        say(f"  stage 3, {e.lower()}: " + describe(readings[e]))
    p["dhuhr_offset"] = readings["DHUHR"]["offset"]

    # Stage 4: the angles.
    p["angles"] = {}
    for e in ("FAJR", "ISHA"):
        best, stretches = cover(angle_windows(e, rows_y, lat, lng, p))
        a, b = stretches[0]
        whole = round((a + b) / 2 * 2) / 2                 # the nearest half degree, if the windows hold it
        wins = angle_windows(e, rows_y, lat, lng, p)
        at_whole = sum(lo <= whole < hi for lo, hi in wins)
        p["angles"][e] = whole if at_whole >= best - FRACTION_GAIN * len(rows) else round((a + b) / 2, 3)
        say(f"  stage 4, {e.lower()}: {best} of {len(rows)} at {a:.4f}-{b:.4f} deg; {at_whole} at {whole} -> {p['angles'][e]}")

    # Stage 5: Asr on the geometric or the apparent sun (E13), its noon shadow at the transit or at the published Dhuhr
    # instant (E4 SHADOW_AT_DHUHR): the form whose whole minutes reproduce the most days; ties go to the geometric sun,
    # then to the transit (the engine's defaults).
    forms = {}
    for at_dhuhr in (False, True):
        for apparent in (False, True):
            q = dict(p, apparent=apparent, shadow_at_dhuhr=at_dhuhr)
            forms[(apparent, at_dhuhr)] = reading(shift_windows("ASR", rows_y, lat, lng, q))
    best = max(forms, key=lambda k: (forms[k]["plain"], not k[0], not k[1]))
    p["apparent"], p["shadow_at_dhuhr"] = best
    readings["ASR"] = forms[best]
    for (apparent, at_dhuhr), r in forms.items():
        say(f"  stage 5, Asr, {'apparent' if apparent else 'geometric'} sun, noon shadow at "
            f"{'the Dhuhr instant' if at_dhuhr else 'the transit'}: " + describe(r))
    say(f"  stage 5 -> {'apparent' if best[0] else 'geometric'} sun, shadow at {'the Dhuhr instant' if best[1] else 'the transit'}")
    p["forms"] = forms
    p["readings"] = readings
    return p


def point_check(rows, entry, p, say=print):
    """Stage 6: the joint exact count on a grid around the entry, with the fitted values (fast model)."""
    rows_y = with_year(rows, p["year"])
    hz = {"SUNRISE": p["horizon"], "MAGHRIB": p["horizon"]}
    offs = {e: p["readings"][e]["offset"] + p["readings"][e]["fraction"] for e in ("DHUHR", "ASR", "SUNRISE", "MAGHRIB")}
    offs.update(FAJR=0, ISHA=0)
    modes = {e: (p["readings"][e]["rounding"] if e in p["readings"] else "NEAREST") for e in EVENTS}

    def count(lat, lng):
        n = 0
        for day, t in rows_y:
            for e in EVENTS:
                u = base_minute(e, day, t, lat, lng, p, hz) + offs[e]
                n += round_minute(u, modes[e]) == t[e]
        return n
    grid = [(count(entry["lat"] + i * 0.002, entry["lng"] + j * 0.0002), i, j) for i in range(-5, 6) for j in range(-4, 5)]
    top = max(g[0] for g in grid)
    at = next(g[0] for g in grid if g[1] == 0 and g[2] == 0)
    best = [g for g in grid if g[0] == top]
    say("  stage 6, point grid (+-0.010 deg N, +-0.0008 deg E): the entry %d of %d, the best %d at %s" % (
        at, 6 * len(rows), top, ", ".join(f"{entry['lat'] + i * 0.002:.4f} N {entry['lng'] + j * 0.0002:.4f} E" for _, i, j in best[:3])))
    return at, top


def round_minute(u: float, mode: str) -> int:
    if mode == "DOWN":
        return math.floor(u + 1e-3 / 60)
    if mode == "UP":
        return math.ceil(u - 1e-3 / 60)
    return math.floor(u + 0.5)


# ------------------------------------------------------------------------------------------------------------
# The profile, the zones, the scores (reference engine)

def method_of(p: dict, fallback: tuple[float, float]) -> dict:
    r = p["readings"]
    offsets = {"DHUHR": r["DHUHR"]["offset"], "ASR": r["ASR"]["offset"], "MAGHRIB": r["MAGHRIB"]["offset"],
               "SUNRISE": r["SUNRISE"]["offset"]}
    rounding = {e: "NEAREST" for e in EVENTS}
    for e in ("SUNRISE", "DHUHR", "ASR", "MAGHRIB"):
        rounding[e] = r[e]["rounding"]
    if p["horizon_law"] == "Refraction":
        side = {"type": "refraction", "fallback": None}
    elif p["horizon_law"] == "Standard":
        side = {"type": "standard"}
    else:   # a fixed altitude per zone (the law at the zone's inputs), the law's fit at exact coordinates
        side = {"type": "per_zone", "fallback": {"type": "sqrt_height", "baseDeg": fallback[0], "perSqrtMetreDeg": fallback[1]}}
    return common.method_json(
        id=AUTHORITY, fajr={"type": "angle", "degrees": p["angles"]["FAJR"]}, isha={"type": "angle", "degrees": p["angles"]["ISHA"]},
        offsets={e: v for e, v in offsets.items() if v}, rounding=rounding, sunModel=p["model"],
        sunEvaluation=({} if p["mode"] == "EVENT_INSTANT" else {e: p["mode"] for e in EVENTS})
        | ({"ASR": "SHADOW_AT_DHUHR"} if p.get("shadow_at_dhuhr") else {}),
        asrSun="APPARENT" if p["apparent"] else "GEOMETRIC", horizon={"sunrise": side, "sunset": side}, referenceYear=p["year"],
        highLatitude={"type": "angle_based"}, evidence={"kind": "FROM_TABLES", "source": "fit"})


def zone_of(entry: dict, p: dict) -> dict:
    """The entry's zone: its point and elevation, its air where not standard (E2, E13), its horizon by the fitted law
    where that is a fixed altitude (E2 PerZone), and the fitted E7 fractions."""
    zid, name_ar = NAMES[entry["name"]]
    fractions = {e: p["readings"][e]["fraction"] for e in p["readings"] if p["readings"][e]["fraction"]}
    standard_air = entry["P"] == 1010.0 and entry["T"] == 10.0
    altitude = law_altitude(p["horizon_law"], entry)
    fixed = None if callable(altitude) or p["horizon_law"] == "Standard" else round(altitude, 4)
    return common.zone_json(
        zid, entry["lat"], entry["lng"], authority=AUTHORITY, nameAr=name_ar, elevationM=int(round(entry["h"])),
        pressureHpa=None if standard_air else entry["P"], temperatureC=None if standard_air else entry["T"],
        sunriseAltitudeDeg=fixed, sunsetAltitudeDeg=fixed, offsets=fractions)


def table_minutes(method: dict, zone: dict, day: datetime.date) -> dict[str, int]:
    lat, lng = zone["point"]["latE5"] / 1e5, zone["point"]["lngE5"] / 1e5
    got = common.compute(common.case(method, lat, lng, TZ, day, zone=zone, country="IQ"))
    off = common.utc_offset(TZ, day)
    return {e: (got[e][1] + off) // 60 % 1440 for e in EVENTS}


def score_into(score: common.Score, method: dict, zone: dict, items, year=None):
    """Adds each (date, published times) as the app shows it (year None: the actual date) or at a reference year."""
    for d, t in items:
        got = table_minutes(method, zone, at_year(d, year))
        for e in EVENTS:
            score.add(e, d, got[e], t[e])


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


# ------------------------------------------------------------------------------------------------------------
# Side check (evidence only, 5.6): mosque calendars near Mosul's and Kirkuk's entries

def mosque_check(method: dict, zones: dict, entries: dict, year: int, say=print):
    if not MOSQUES.exists():
        say("  (mosque dataset not found)")
        return
    con = sqlite3.connect(f"file:{MOSQUES}?mode=ro", uri=True)
    mosques = con.execute("select mosque_key, latitude, longitude from mosques where countryCode='IQ'").fetchall()
    for name in ("Moosel", "Kirkuk"):
        e = entries[name]
        near = [k for k, la, lo in mosques if km(la, lo, e["lat"], e["lng"]) < 25]
        cals = {}
        for k in near:
            rows = con.execute("select month, day, fajr, sunrise, dhuhr, asr, maghrib, isha from calendar_days "
                               "where mosque_key=?", (k,)).fetchall()
            if len(rows) >= 365:
                cals[k] = tuple((mo, da) + tuple(minute(x) for x in r) for mo, da, *r in rows if (mo, da) != (2, 29))
        if not cals:
            continue
        groups: dict = {}
        for k, c in cals.items():
            groups.setdefault(c, []).append(k)
        cal, members = max(groups.items(), key=lambda kv: len(kv[1]))
        zone = zones[NAMES[name][0]]
        out = []
        for label, y in ((str(year), year), ("2026", 2026)):
            s = common.Score()
            for mo, da, *t in cal:
                got = table_minutes(method, zone, datetime.date(y, mo, da))
                for ev, pub in zip(EVENTS, t):
                    s.add(ev, datetime.date(2026, mo, da), got[ev], pub)
            out.append(f"computed for {label}: " + shares(s))
        say(f"  {NAMES[name][0]} ({e['h']:.0f} m): the calendar of {len(members)} of {len(cals)} mosques within 25 km; "
            + "; ".join(out))


def km(lat1, lng1, lat2, lng2) -> float:
    a = (math.sin((lat2 - lat1) * D2R / 2) ** 2
         + math.cos(lat1 * D2R) * math.cos(lat2 * D2R) * math.sin((lng2 - lng1) * D2R / 2) ** 2)
    return 2 * 6371.0 * math.asin(math.sqrt(a))


# ------------------------------------------------------------------------------------------------------------

def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--no-mosques", action="store_true", help="skip the mosque-calendar side check")
    args = ap.parse_args(argv)
    sys.stdout.reconfigure(encoding="utf-8")

    rows = load_rows()
    entries = {e["name"]: e for e in load_entries()}
    in_ramadan = ramadan_test()
    if sorted(n for n in entries if n not in KURDISTAN) != sorted(NAMES):
        raise SystemExit(f"Accurate Times' Iraqi entries differ from the zone list: {sorted(entries)}")
    bgd = entries["Baghdad"]
    items = list(rows.items())
    print(f"{len(rows)} Baghdad rows, {min(rows)} to {max(rows)}; Accurate Times' Baghdad entry {bgd['lat']:.5f} N "
          f"{bgd['lng']:.5f} E, {bgd['h']:.0f} m, {bgd['P']:.0f} hPa, {bgd['T']:.0f} C, {100 * bgd['rh']:.0f} %")

    print("Baghdad, all rows:")
    p = fit(items, bgd)
    point_check(items, bgd, p)
    fallback = sqrt_height_fallback(33.3)
    print(f"  exact coordinates: SqrtHeight b {fallback[0]} k {fallback[1]} (the law within {fallback[2]:.4f} deg, 0-1500 m)")
    method = method_of(p, fallback)

    # Held out: month-split, scored as the app shows it (the table as printed, A8) and, for information, at the
    # reference year itself.
    folds, folds_ref = [], []
    for label, keep in (("odd", 1), ("even", 0)):
        print(f"Baghdad, {label} months:")
        part = fit([(d, t) for d, t in items if d.month % 2 == keep], bgd)
        m_part = method_of(part, fallback)
        z_part = zone_of(bgd, part)
        held = [(d, t) for d, t in items if d.month % 2 != keep]
        s, s_ref = common.Score(), common.Score()
        score_into(s, m_part, z_part, held)
        score_into(s_ref, m_part, z_part, held, year=part["year"])
        folds.append(s)
        folds_ref.append(s_ref)
        print(f"  held out on the {'even' if keep else 'odd'} months, computed for 2026: " + shares(s)
              + f"; at {part['year']}: " + shares(s_ref))
    held = merge(folds)
    held_ref = merge(folds_ref)

    # Zones: the 28 entries outside the Kurdistan Region, each horizon from the law at its own inputs.
    zones, zone_by_id, lines = [], {}, []
    sample = common.sample(TABLE_SOURCE, min(rows), max(rows), len(rows), 1)
    split = common.sample(TABLE_SOURCE, min(rows), max(rows), len(rows), 1, month_split=True)
    ram = sum(in_ramadan(d) for d in rows)
    for name in sorted(NAMES, key=lambda n: NAMES[n][0]):
        e = entries[name]
        z = zone_of(e, p)
        z["namedPlaces"] = [{"centre": z["point"], "catalogueIds": [], "areaKm": 0.0}]
        if name == "Baghdad":
            z["fit"] = common.fit_stats([sample], [split], held, ramadan_days=ram)
        zones.append({k: v for k, v in z.items() if k != "authority"})     # the asset's zone keys (validate_asset)
        zone_by_id[z["id"]] = z
        lines.append(f"{z['id']} {e['h']:.0f} m {z['sunriseAltitudeDeg']}")
    profile_fit = common.fit_stats([sample], [split], held, ramadan_days=ram)
    path = common.write_asset(AUTHORITY, AUTHORITY, "IQ", "NEAREST", zones, fit=profile_fit, source=SOURCE_LINE)
    # The Kurdistan Region maps EXACT (6.5): the asset's top-level "exactAdmin1", which common.write_asset does not
    # write, added here before "zones" in the same format (LF, indent 1).
    asset = json.loads(path.read_text(encoding="utf-8"))
    ordered = {}
    for key, value in asset.items():
        if key == "zones":
            ordered["exactAdmin1"] = list(KURDISTAN_ADMIN1)
        ordered[key] = value
    path.write_text(json.dumps(ordered, ensure_ascii=False, indent=1) + "\n", encoding="utf-8", newline="\n")

    # Final scores with the shipped asset.
    final, final_ref = common.Score(), common.Score()
    score_into(final, method, zone_by_id["baghdad"], items)
    score_into(final_ref, method, zone_by_id["baghdad"], items, year=p["year"])
    r = p["readings"]
    print("Profile: Fajr %.1f, Isha %.1f, Dhuhr %s %+d, Asr %s %+d (%s sun, noon shadow at %s%s), sunrise %s %+d, "
          "Maghrib %s %+d, horizon %s; %s %s, reference year %d" % (
              p["angles"]["FAJR"], p["angles"]["ISHA"], r["DHUHR"]["rounding"], r["DHUHR"]["offset"], r["ASR"]["rounding"],
              r["ASR"]["offset"], "apparent" if p["apparent"] else "geometric",
              "the Dhuhr instant, E4 SHADOW_AT_DHUHR" if p["shadow_at_dhuhr"] else "the transit",
              f", zone E7 {r['ASR']['fraction']:+.3f}" if r["ASR"]["fraction"] else "", r["SUNRISE"]["rounding"],
              r["SUNRISE"]["offset"], r["MAGHRIB"]["rounding"], r["MAGHRIB"]["offset"], p["horizon_law"], p["model"],
              p["mode"], p["year"]))
    print("Zone horizons (law): " + ", ".join(lines))
    print("Held out (FitStats, computed for 2026): " + pct(held) + f"; worst {held.worst} min")
    print(f"Held out at the reference year {p['year']}: " + pct(held_ref) + f"; worst {held_ref.worst} min")
    print("Final asset on every row, as the app shows it (2026): " + shares(final) + f"; worst {final.worst}")
    print(f"Final asset on every row at {p['year']}: " + shares(final_ref) + f"; worst {final_ref.worst}")
    # Asr against the previous values (the noon shadow at the transit, with the zones' E7 + 0.044), reference engine.
    before = common.method_json(**{**method, "sunEvaluation": {k: v for k, v in method["sunEvaluation"].items() if k != "ASR"}})
    zone_before = dict(zone_by_id["baghdad"], offsets={"ASR": 0.044})
    for label, year in ((str(p["year"]), p["year"]), ("2026", None)):
        s = common.Score()
        score_into(s, before, zone_before, items, year=year)
        shipped = final_ref if year else final
        print(f"Asr computed for {label}: shipped {shipped.exact['ASR']} of 365; with the transit shadow and E7 + 0.044 "
              f"{s.exact['ASR']}")
    if not args.no_mosques:
        print("Side check, mosque calendars (evidence only, never fitted):")
        mosque_check(method, zone_by_id, entries, p["year"])
    print(f"Kurdistan Region (EXACT, 6.5): Accurate Times' {', '.join(KURDISTAN)} left out; GeoNames admin1 IQ."
          + ", IQ.".join(KURDISTAN_ADMIN1))
    print(f"Wrote {path.relative_to(common.ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
