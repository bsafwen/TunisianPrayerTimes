#!/usr/bin/env python3
"""
Independent Python reference of the shared prayer-time engine, written from
scripts/prayer_formula/README.md only: its section "Shared engine (places
outside Tunisia)" (S1-S13 and the high-latitude rules H1-H8) and its sections
1 (Julian day) and 2 (INM's Meeus sun). It does not read the Kotlin engine;
EngineReferenceCrossCheckTest compares the two on test-data/engine-reference.

Everything runs in UTC. Instants are epoch seconds (float); the solver works
in hours after 0h UT of a cycle's UT date U (S1, S2). The facts that need
time-zone rules (the standard and actual UTC offsets at D's civil noon, 00:00
of D, the Isha clock caps) come from the case file, so no zoneinfo is needed.

Usage:
  python scripts/prayer_formula/prayer_engine_reference.py --cases test-data/engine-reference/cases.json --out test-data/engine-reference/expected.tsv
  python scripts/prayer_formula/prayer_engine_reference.py --cases test-data/engine-reference/cases.json --case mwl-mata-utu-2026-10-13
"""

from __future__ import annotations

import argparse
import copy
import datetime
import functools
import json
import math
import sys
from dataclasses import dataclass
from pathlib import Path

HERE = Path(__file__).resolve().parent
VSOP87_PATH = HERE / "vsop87d_earth_terms.tsv"

EVENTS = ("FAJR", "SUNRISE", "DHUHR", "ASR", "MAGHRIB", "ISHA")
SHADOW_KINDS = ("shadow", "signed_shadow")
UNIX_EPOCH_JD = 2440587.5
EPOCH_DATE = datetime.date(1970, 1, 1)
ONE_DAY = datetime.timedelta(days=1)
HOUR = 3600
TOLERANCE_H = 0.1 / 3600            # S3: 0.1 s, in hours
MAX_PASSES = 10                     # S3
EARTH_RADIUS_M = 6378137.0          # S5 (Refraction, StandardWithDip)
SARAJEVO_LAT = 43 + 52 / 60         # S11 TAKVIM: 43°52'N
SARAJEVO_LNG = 18 + 26 / 60         #             18°26'E
MECCA_POINT = {"latE5": 2142250, "lngE5": 3982620}   # H8: 21.4225°N 39.8262°E
MECCA_OFFSET_S = 10800              # H8: Asia/Riyadh, UTC+03:00 all year
COMMON_YEAR = 2021                  # H7: switch days are day numbers of a common year
IRN_NORTH = 63.56                   # H7 south of it, H8 from it
RAMP_DAYS = 200                     # H3
D2R = math.pi / 180

# The twilight curves (adhan-js 4.4.6): base; coefficients of a, b, c, d per |φ|/55.
SHAFAQ_AHMER = (62.0, 17.40, -7.16, 5.12, 19.44)     # S10
MOONSIGHTING_FAJR = (75.0, 28.65, 19.44, 32.74, 48.10)   # H4
MOONSIGHTING_ISHA = (75.0, 25.60, 2.05, -9.21, 6.14)     # H4, the general curve

EXPECTED_HEADER = (
    "# Expected PrayerDay of every case in cases.json, written by the independent Python reference\n"
    "# scripts/prayer_formula/prayer_engine_reference.py (README \"Shared engine\", S1-S13, H1-H8). Do not edit;"
    " regenerate with\n"
    "# python scripts/prayer_formula/prayer_engine_reference.py --cases test-data/engine-reference/cases.json"
    " --out test-data/engine-reference/expected.tsv\n"
    "# id\tevent\tstatus\trule\tminute (UTC)\tunrounded (epoch seconds)\n"
)


def sind(x: float) -> float:
    return math.sin(x * D2R)


def cosd(x: float) -> float:
    return math.cos(x * D2R)


def tand(x: float) -> float:
    return math.tan(x * D2R)


def asind(x: float) -> float:
    return math.asin(x) / D2R


def acosd(x: float) -> float:
    return math.acos(x) / D2R


def atand(x: float) -> float:
    return math.atan(x) / D2R


def atan2d(y: float, x: float) -> float:
    return math.atan2(y, x) / D2R


# ---------------------------------------------------------------------------------------------
# Section 1: Julian day at 0h UT of a civil date.

def julian_day0(day: datetime.date) -> float:
    y, m, d = day.year, day.month, day.day
    if m <= 2:
        y -= 1
        m += 12
    a = math.floor(y / 100)
    b = 2 - a + math.floor(a / 4)
    return math.floor(365.25 * (y + 4716)) + math.floor(30.6001 * (m + 1)) + d + b - 1524.5


def epoch_days(day: datetime.date) -> int:
    return (day - EPOCH_DATE).days


def jd_of_instant(t: float) -> float:
    """Conventions: JD(t) = 2440587.5 + t / 86400."""
    return UNIX_EPOCH_JD + t / 86400


# ---------------------------------------------------------------------------------------------
# S1: the four sun models. Each returns (declination deg, equation of time min, distance AU).

def almanac_distance(jd: float) -> float:
    g = 357.528 + 0.9856003 * (jd - 2451545.0)
    return 1.00014 - 0.01671 * cosd(g) - 0.00014 * cosd(2 * g)


def meeus_sun(jd: float) -> tuple[float, float, float]:
    """INM's sun (section 2, Meeus 1900 epoch) at a UT Julian day, no ΔT; r from the Almanac."""
    t = (jd - 2415020.0) / 36525
    mean_long = (279.69668 + 36000.76892 * t + 0.0003025 * t * t) % 360
    anomaly = 358.47583 + 35999.04975 * t - 0.000150 * t * t - 0.0000033 * t ** 3
    ecc = 0.01675104 - 0.0000418 * t - 0.000000126 * t * t
    centre = ((1.919460 - 0.004789 * t - 0.000014 * t * t) * sind(anomaly)
              + (0.020094 - 0.000100 * t) * sind(2 * anomaly)
              + 0.000293 * sind(3 * anomaly))
    omega = 259.18 - 1934.142 * t
    app_long = mean_long + centre - 0.00569 - 0.00479 * sind(omega)
    obliquity = (23.452294 - 0.0130125 * t - 0.00000164 * t * t + 0.000000503 * t ** 3
                 + 0.00256 * cosd(omega))
    decl = asind(sind(obliquity) * sind(app_long))
    y = tand(obliquity / 2) ** 2
    e_rad = (y * sind(2 * mean_long) - 2 * ecc * sind(anomaly)
             + 4 * ecc * y * sind(anomaly) * cosd(2 * mean_long)
             - 0.5 * y * y * sind(4 * mean_long) - 1.25 * ecc * ecc * sind(2 * anomaly))
    return decl, 4 * e_rad * (180 / math.pi), almanac_distance(jd)


@functools.lru_cache(maxsize=None)
def vsop87_series() -> dict[str, tuple[tuple[tuple[float, float, float], ...], ...]]:
    """vsop87d_earth_terms.tsv as {variable: (terms of tau^0, terms of tau^1, ...)}."""
    by_power: dict[str, dict[int, list[tuple[float, float, float]]]] = {"L": {}, "B": {}, "R": {}}
    with open(VSOP87_PATH, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            var, power, a, b, c = line.split("\t")
            by_power[var].setdefault(int(power), []).append((float(a), float(b), float(c)))
    return {var: tuple(tuple(powers.get(k, ())) for k in range(max(powers) + 1))
            for var, powers in by_power.items()}


def _vsop_sum(powers, tau: float) -> float:
    total = 0.0
    for k, terms in enumerate(powers):
        total += tau ** k * sum(a * math.cos(b + c * tau) for a, b, c in terms)
    return total


def vsop87_sun(jd: float) -> tuple[float, float, float]:
    """S1 VSOP87, as Accurate Times computes."""
    year = 2000 + (jd - 2451545.0) / 365.25
    if year < 2050:
        u = year - 2000
        delta_t = 62.92 + 0.32217 * u + 0.005589 * u * u
    else:
        w = (year - 1820) / 100
        delta_t = -20 + 32 * w * w - 0.5628 * (2150 - year)
    jde = jd + delta_t / 86400
    tau = (jde - 2451545.0) / 365250
    big_t = 10 * tau
    series = vsop87_series()
    lon = _vsop_sum(series["L"], tau)
    lat = _vsop_sum(series["B"], tau)
    dist = _vsop_sum(series["R"], tau)
    theta = math.degrees(lon + math.pi)                  # Θ = L + π, in degrees from here on
    beta = -math.degrees(lat)                            # β = −B
    l_prime = theta - (1.397 * big_t + 0.00031 * big_t * big_t)
    theta -= 0.09033 / 3600
    beta += 0.03916 / 3600 * (cosd(l_prime) - sind(l_prime))
    omega = 125.04452 - 1934.136261 * big_t + 0.0020708 * big_t * big_t + big_t ** 3 / 450000
    ls = 280.4665 + 36000.7698 * big_t
    lm = 218.3165 + 481267.8813 * big_t
    dpsi = -17.20 * sind(omega) - 1.32 * sind(2 * ls) - 0.23 * sind(2 * lm) + 0.21 * sind(2 * omega)   # arcsec
    deps = 9.20 * cosd(omega) + 0.57 * cosd(2 * ls) + 0.10 * cosd(2 * lm) - 0.09 * cosd(2 * omega)     # arcsec
    lam = theta + dpsi / 3600 - 20.4898 / 3600 / dist
    eps = ((23 + 26 / 60 + 21.448 / 3600)
           - (46.8150 * big_t + 0.00059 * big_t * big_t - 0.001813 * big_t ** 3) / 3600 + deps / 3600)
    alpha = atan2d(sind(lam) * cosd(eps) - tand(beta) * sind(eps), cosd(lam))
    decl = asind(sind(beta) * cosd(eps) + cosd(beta) * sind(eps) * sind(lam))
    d = jd - 2451545.0
    tu = d / 36525
    gast = (280.46061837 + 360.98564736629 * d + 0.000387933 * tu * tu - tu ** 3 / 38710000
            + dpsi / 3600 * cosd(eps))
    ut_hours = 24 * ((jd - 0.5) - math.floor(jd - 0.5))
    eot = 4 * (gast - alpha) + 720 - 60 * ut_hours
    eot = (eot + 720) % 1440 - 720                       # into [−720, 720)
    return decl, eot, dist


def textbook_sun(jd: float) -> tuple[float, float, float]:
    """S1 TEXTBOOK, the low-precision sun of Libya's perpetual table."""
    t = (jd - 2451545.0) / 36525
    mean_long = (280.46646 + 36000.76983 * t + 0.0003032 * t * t) % 360
    anomaly = mean_long - 282
    centre = 1.9146 * sind(anomaly) + 0.019993 * sind(2 * anomaly)
    lam = mean_long + centre
    decl = asind(sind(23.44) * sind(lam))
    y = tand(23.5 / 2) ** 2
    eot = 4 * (-centre + y * sind(2 * lam) * 180 / math.pi)
    return decl, eot, almanac_distance(jd)


def almanac_sun(jd: float) -> tuple[float, float, float]:
    """S1 ALMANAC, the Astronomical Almanac's low-precision sun with PrayTimes.js's constants (Oman)."""
    d = jd - 2451545.0
    g = (357.529 + 0.98560028 * d) % 360
    q = (280.459 + 0.98564736 * d) % 360
    lam = q + 1.915 * sind(g) + 0.020 * sind(2 * g)
    eps = 23.439 - 0.00000036 * d
    alpha = atan2d(cosd(eps) * sind(lam), cosd(lam))
    decl = asind(sind(eps) * sind(lam))
    eot = 4 * ((q - alpha + 180) % 360 - 180)
    return decl, eot, 1.00014 - 0.01671 * cosd(g) - 0.00014 * cosd(2 * g)


SUN_MODELS = {"MEEUS": meeus_sun, "VSOP87": vsop87_sun, "TEXTBOOK": textbook_sun, "ALMANAC": almanac_sun}


class SunFunction:
    """S4: the sun of one evaluation mode, for a cycle with JD0."""

    def __init__(self, model, mode: str, jd0: float, noon_jd: float | None = None):
        self.model = model
        self.eot = None                     # EVENT_DECLINATION: E and r held at local noon
        if mode == "DAILY_0H_UT":
            self.fixed = model(jd0)
        elif mode == "DAILY_10H_UT":
            self.fixed = model(jd0 + 10 / 24)
        elif mode == "KACST":
            decl = model(jd0 + 21 / 24)[0]
            _, eot, dist = model(jd0 + 9 / 24)
            self.fixed = (decl, eot, dist)
        elif mode == "DAILY_LOCAL_NOON":
            self.fixed = model(noon_jd)
        elif mode == "EVENT_DECLINATION":
            self.fixed = None
            _, self.eot, self.distance = model(noon_jd)
        elif mode in ("EVENT_INSTANT", "LOCAL_MIDNIGHT_SHADOW", "SHADOW_AT_DHUHR", "SHADOW_AT_EVENT"):
            self.fixed = None
        else:
            raise ValueError(f"unknown evaluation mode {mode}")

    def __call__(self, jd: float) -> tuple[float, float, float]:
        if self.fixed is not None:
            return self.fixed
        value = self.model(jd)
        return value if self.eot is None else (value[0], self.eot, self.distance)


NOON_MODES = ("DAILY_LOCAL_NOON", "EVENT_DECLINATION")


# ---------------------------------------------------------------------------------------------
# S2, S3: cycles and events.

class Cycle:
    """A solar cycle: its UT date U, JD0(U), and the instant of 0h UT of U."""

    def __init__(self, u: datetime.date):
        self.u = u
        self.jd0 = julian_day0(u)
        self.epoch0 = (self.jd0 - UNIX_EPOCH_JD) * 86400

    def instant(self, hours: float | None) -> float | None:
        return None if hours is None else self.epoch0 + 3600 * hours

    def hours(self, instant: float) -> float:
        return (instant - self.epoch0) / 3600


def transit(cycle: Cycle, sun, lng: float) -> float:
    """S3: transit at longitude lng, hours after 0h UT of U."""
    mean_noon = 12 - lng / 15
    t = mean_noon
    for _ in range(MAX_PASSES):
        t_next = mean_noon - sun(cycle.jd0 + t / 24)[1] / 60
        if abs(t_next - t) < TOLERANCE_H:
            return t_next
        t = t_next
    return t


def altitude(cycle: Cycle, sun, lat: float, lng: float, t: float) -> float:
    """S3: alt(t) = asin(sin φ·sin δ + cos φ·cos δ·cos H), H = 15·(t − M) + E/4."""
    decl, eot, _ = sun(cycle.jd0 + t / 24)
    hour_angle = 15 * (t - (12 - lng / 15)) + eot / 4
    x = sind(lat) * sind(decl) + cosd(lat) * cosd(decl) * cosd(hour_angle)
    return asind(max(-1.0, min(1.0, x)))


def event(cycle: Cycle, sun, lat: float, lng: float, alt: float, side: int, t0: float) -> float | None:
    """S3: the event at altitude alt on side −1 (morning) or +1 (evening); None when absent."""
    mean_noon = 12 - lng / 15
    t = t0
    for _ in range(MAX_PASSES):
        decl, eot, _ = sun(cycle.jd0 + t / 24)
        c = (sind(alt) - sind(lat) * sind(decl)) / (cosd(lat) * cosd(decl))
        if abs(c) > 1:
            break
        t_next = mean_noon - eot / 60 + side * acosd(c) / 15
        if abs(t_next - t) < TOLERANCE_H:
            return t_next
        t = t_next
    lo, hi = t0 + 12 * side, t0
    if altitude(cycle, sun, lat, lng, lo) >= alt or altitude(cycle, sun, lat, lng, hi) <= alt:
        return None
    while abs(hi - lo) >= TOLERANCE_H:
        mid = (lo + hi) / 2
        if altitude(cycle, sun, lat, lng, mid) < alt:
            lo = mid
        else:
            hi = mid
    return (lo + hi) / 2


# ---------------------------------------------------------------------------------------------
# S5, S6: horizons, points and elevations.

def dip_deg(h: float) -> float:
    return acosd(EARTH_RADIUS_M / (EARTH_RADIUS_M + h)) if h > 0 else 0.0


def bennett(a: float, pressure: float, temperature: float) -> float:
    """Bennett's refraction for apparent altitude a, in degrees."""
    return 1 / tand(a + 7.31 / (a + 4.4)) / 60 * (pressure / 1010) * (283 / (273 + temperature))


@dataclass(frozen=True)
class Site:
    """Where a method runs (S6): exact coordinates (zone None) or a zone."""
    key: str
    point: tuple[float, float]
    morning: tuple[float, float]
    evening: tuple[float, float]
    morning_elev: float
    evening_elev: float
    pressure: float
    temperature: float
    zone: dict | None


def lat_lng(point: dict) -> tuple[float, float]:
    return point["latE5"] / 1e5, point["lngE5"] / 1e5


def zone_site(zone: dict) -> Site:
    point = lat_lng(zone["point"])
    morning = lat_lng(zone["morningPoint"]) if zone.get("morningPoint") else point
    evening = lat_lng(zone["eveningPoint"]) if zone.get("eveningPoint") else point
    elev = float(zone["elevationM"]) if zone.get("elevationM") is not None else 0.0
    evening_elev = float(zone["eveningElevationM"]) if zone.get("eveningElevationM") is not None else elev
    pressure = float(zone["pressureHpa"]) if zone.get("pressureHpa") is not None else 1010.0
    temperature = float(zone["temperatureC"]) if zone.get("temperatureC") is not None else 10.0
    return Site("zone:" + zone["id"], point, morning, evening, elev, evening_elev, pressure, temperature, zone)


# ---------------------------------------------------------------------------------------------
# Small rules: E1, E10, E16, E18, the twilight curves, H1, H4's DUM RF share, H7's day numbers.

def round_instant(t: float, mode: str) -> float:
    """E1."""
    if mode == "NEAREST":
        return 60 * math.floor((t + 30) / 60)
    if mode == "UP":
        return 60 * math.ceil((t - 0.001) / 60)
    if mode == "DOWN":
        return 60 * math.floor((t + 0.001) / 60)
    raise ValueError(f"unknown rounding {mode}")


def days_in_year(year: int) -> int:
    return 366 if (year % 4 == 0 and year % 100 != 0) or year % 400 == 0 else 365


def seasonal_value(values, day: datetime.date) -> float:
    """E18: the 36-value seasonal offset of day, in minutes."""
    if not values:
        return 0.0
    n = day.timetuple().tm_yday
    p = 36 * (n - 1) / days_in_year(day.year)
    k = math.floor(p)
    w = p - k
    return values[k] * (1 - w) + values[(k + 1) % 36] * w


def twilight_curve(lat: float, day: datetime.date, coefficients: tuple) -> float:
    """S10 / H4: an adhan-js seasonal curve, in minutes (Shafaq Ahmer, Moonsighting's Fajr and Isha)."""
    base, ka, kb, kc, kd = coefficients
    x = abs(lat)
    a = base + ka * x / 55
    b = base + kb * x / 55
    c = base + kc * x / 55
    d = base + kd * x / 55
    length = days_in_year(day.year)
    n = day.timetuple().tm_yday
    if lat >= 0:
        dyy = n + 10
        if dyy >= length:
            dyy -= length
    else:
        dyy = n - (173 if length == 366 else 172)
        if dyy < 0:
            dyy += length
    if dyy < 91:
        return a + (b - a) / 91 * dyy
    if dyy < 137:
        return b + (c - b) / 46 * (dyy - 91)
    if dyy < 183:
        return c + (d - c) / 46 * (dyy - 137)
    if dyy < 229:
        return d + (c - d) / 46 * (dyy - 183)
    if dyy < 275:
        return c + (b - c) / 46 * (dyy - 229)
    return b + (a - b) / 91 * (dyy - 275)


def shafaq_ahmer_minutes(lat: float, day: datetime.date) -> float:
    """S10: the Moonsighting Committee's «Shafaq Ahmer» minutes after sunset."""
    return twilight_curve(lat, day, SHAFAQ_AHMER)


def later(t: float | None, bound: float) -> float:
    """H1: a Fajr no earlier than bound (an absent one becomes bound)."""
    return bound if t is None else max(t, bound)


def earlier(t: float | None, bound: float) -> float:
    """H1: an Isha no later than bound (an absent one becomes bound)."""
    return bound if t is None else min(t, bound)


def dum_rf_share(day: datetime.date) -> float:
    """H4: DUM RF's share r(D) of the night for Isha."""
    june1, june30 = datetime.date(day.year, 6, 1), datetime.date(day.year, 6, 30)
    if june1 <= day <= june30:
        return 0.30
    if day < june1:
        n = math.floor(((june1 - day).days - 1) / 3) + 1
    else:
        n = math.floor(((day - june30).days - 1) / 3) + 1
    return max(0.25, 0.30 - n / 240)


def dated_value(entries: list, name: str, day: datetime.date) -> float:
    """E18's dated offsets: the minutes of every entry of the event whose dates, both ends included, contain day."""
    total = 0.0
    md = (day.month, day.day)
    for e in entries:
        if e["event"] != name:
            continue
        start, end = (e["fromMonth"], e["fromDay"]), (e["toMonth"], e["toDay"])
        if (start <= md <= end) if start <= end else (md >= start or md <= end):
            total += float(e["minutes"])
    return total


def common_day_number(day: datetime.date) -> int:
    """H7: the day number of day's calendar date in a common year (29 February counts as 28 February)."""
    if day.month == 2 and day.day == 29:
        return 59
    return (datetime.date(COMMON_YEAR, day.month, day.day) - datetime.date(COMMON_YEAR, 1, 1)).days + 1


def date_of_day_number(n: int, year: int) -> datetime.date:
    common = datetime.date(COMMON_YEAR, 1, 1) + datetime.timedelta(days=n - 1)
    return datetime.date(year, common.month, common.day)


def utc_hours(t: float) -> float:
    """H7's h(t): the UTC time of day of t, in hours."""
    return (t % 86400) / 3600


def clock_seconds(text: str) -> int:
    hours, minutes = text.split(":")
    return int(hours) * 3600 + int(minutes) * 60


# ---------------------------------------------------------------------------------------------
# One case.

class RawDay:
    """S9 step 1: the raw events of one civil date's cycle at a site (lazy)."""

    def __init__(self, engine: CaseEngine, site: Site, day: datetime.date):
        self.engine = engine
        self.site = site
        self.day = day
        self.cycle = Cycle(engine.cycle_date(site, day))

    def sun(self, event_name: str) -> SunFunction:
        mode = self.engine.modes[event_name]
        noon = self.engine.local_noon_jd(self.day) if mode in NOON_MODES else None
        return SunFunction(self.engine.model, mode, self.cycle.jd0, noon)

    def _sunrise_horizon(self) -> tuple[SunFunction, float]:
        sun = self.sun("SUNRISE")
        t0 = transit(self.cycle, sun, self.site.morning[1])
        alt = self.engine.horizon_altitude(self.engine.method["horizon"]["sunrise"], self.site, "sunrise",
                                           lambda: sun(self.cycle.jd0 + t0 / 24)[2])
        return sun, alt

    def _horizon_event(self, which: str) -> float | None:
        mode_event, side, (lat, lng) = (("SUNRISE", -1, self.site.morning) if which == "sunrise"
                                        else ("MAGHRIB", 1, self.site.evening))
        sun = self.sun(mode_event)
        t0 = transit(self.cycle, sun, lng)
        alt = self.engine.horizon_altitude(self.engine.method["horizon"][which], self.site, which,
                                           lambda: sun(self.cycle.jd0 + t0 / 24)[2])
        return self.cycle.instant(event(self.cycle, sun, lat, lng, alt, side, t0))

    @functools.cached_property
    def sunrise(self) -> float | None:
        return self._horizon_event("sunrise")

    @functools.cached_property
    def sunset(self) -> float | None:
        return self._horizon_event("sunset")

    @functools.cached_property
    def transit(self) -> float:
        """Dhuhr's transit T, at the evening point, with DHUHR's sun."""
        return self.cycle.instant(transit(self.cycle, self.sun("DHUHR"), self.site.evening[1]))

    @functools.cached_property
    def polar_night(self) -> bool:
        """H2: whether the sun's altitude at T is below the sunrise horizon (else a midnight sun)."""
        sun, horizon = self._sunrise_horizon()
        lat, lng = self.site.morning
        return altitude(self.cycle, sun, lat, lng, self.cycle.hours(self.transit)) < horizon

    @functools.cached_property
    def maghrib_base(self) -> float | None:
        angle = self.engine.method["maghribAngle"]
        if angle is None:
            return self.sunset
        sun = self.sun("MAGHRIB")
        lat, lng = self.site.evening
        t0 = transit(self.cycle, sun, lng)
        return self.cycle.instant(event(self.cycle, sun, lat, lng, -float(angle), 1, t0))

    @functools.cached_property
    def asr(self) -> float | None:
        """S7, for the shadow kinds (BeforeMaghrib is step 5)."""
        method = self.engine.method
        kind = method["asr"]["type"]
        if kind not in SHADOW_KINDS:
            raise ValueError(f"no shadow Asr for {kind}")
        mode = self.engine.modes["ASR"]
        sun = self.sun("ASR")
        lat, lng = self.site.evening
        t0 = transit(self.cycle, sun, lng)
        decl_t0 = sun(self.cycle.jd0 + t0 / 24)[0]   # the fixed δ in the daily modes and KACST
        # The noon zenith distance φ − δₙ, with its sign (S7).
        if mode == "LOCAL_MIDNIGHT_SHADOW":
            noon = lat - self.engine.model(jd_of_instant(self.engine.local_midnight(self.day)))[0]
        elif mode == "SHADOW_AT_DHUHR":
            offsets, _, _ = self.engine.rule_for(self.day)
            minutes = offsets.get("DHUHR", 0) + method["dhuhrAfterTransitMin"]
            noon = math.copysign(90 - altitude(self.cycle, sun, lat, lng, t0 + minutes / 60), lat - decl_t0)
        else:
            noon = lat - decl_t0
        alt = self.shadow_altitude(kind, noon)
        if alt is None:
            return None
        t = event(self.cycle, sun, lat, lng, alt, 1, t0)
        if mode == "SHADOW_AT_EVENT":
            for _ in range(5):
                if t is None:
                    return None
                alt = self.shadow_altitude(kind, lat - sun(self.cycle.jd0 + t / 24)[0])
                if alt is None:
                    return None
                t_next = event(self.cycle, sun, lat, lng, alt, 1, t0)
                if t_next is None:
                    return None
                converged = abs(t_next - t) < TOLERANCE_H
                t = t_next
                if converged:
                    break
        return self.cycle.instant(t)

    def shadow_altitude(self, kind: str, noon: float) -> float | None:
        """S7: the Asr altitude from the noon zenith distance φ − δₙ (its sign read only by SignedShadow)."""
        method = self.engine.method
        k = method["asrShadow"]
        if kind == "shadow":
            zenith = abs(noon)
            if zenith >= 90:
                return None
            if method["asrSun"] == "APPARENT":
                p, temp = self.site.pressure, self.site.temperature
                h0 = 90 - zenith
                h0_app = h0 + bennett(h0, p, temp)
                a_app = atand(1 / (k + tand(90 - h0_app)))
                return a_app - bennett(a_app, p, temp)
            return atand(1 / (k + tand(zenith)))
        if method["asrSun"] == "APPARENT":
            raise ValueError("the README gives no APPARENT form of SignedShadow")
        x = k + tand(noon)
        if x <= 0:
            return None
        return atand(1 / x)

    def angle_event(self, name: str, degrees: float, at: dict | None = None) -> float | None:
        """S5: Fajr or Isha by angle, at latitude φ·twilightLatitudeScale (E17), with E4's sun; Isha at `at` when set."""
        sun = self.sun(name)
        lat, lng = lat_lng(at) if at is not None else (self.site.morning if name == "FAJR" else self.site.evening)
        lat *= self.engine.method["twilightLatitudeScale"]
        t0 = transit(self.cycle, sun, lng)
        side = -1 if name == "FAJR" else 1
        return self.cycle.instant(event(self.cycle, sun, lat, lng, -float(degrees), side, t0))

    # E19 and S12 bounds: plain angles, real latitude, the method's points, EVENT_INSTANT.

    def _plain(self, lat: float, lng: float, alt: float, side: int) -> float | None:
        sun = SunFunction(self.engine.model, "EVENT_INSTANT", self.cycle.jd0)
        t0 = transit(self.cycle, sun, lng)
        return self.cycle.instant(event(self.cycle, sun, lat, lng, alt, side, t0))

    def morning_angle(self, degrees: float) -> float | None:
        return self._plain(*self.site.morning, -degrees, -1)

    def evening_angle(self, degrees: float) -> float | None:
        return self._plain(*self.site.evening, -degrees, 1)

    @functools.cached_property
    def a1(self) -> float | None:
        """A1: the factor-1 Shadow Asr on the geometric sun."""
        sun = SunFunction(self.engine.model, "EVENT_INSTANT", self.cycle.jd0)
        lat, lng = self.site.evening
        t0 = transit(self.cycle, sun, lng)
        zenith = abs(lat - sun(self.cycle.jd0 + t0 / 24)[0])
        if zenith >= 90:
            return None
        alt = atand(1 / (1 + tand(zenith)))
        return self.cycle.instant(event(self.cycle, sun, lat, lng, alt, 1, t0))


class DaySteps:
    """S9 steps 1-3 of one civil date at a site: the raw events, then the polar rules (H2) or IRN's Mecca
    cycle (H8). Every later step reads its sunrise and sunset."""

    def __init__(self, engine: CaseEngine, site: Site, day: datetime.date):
        self.engine = engine
        self.site = site
        self.day = day
        raw = engine.raw_day(site, day)
        self.raw = raw
        self.transit = raw.transit
        self.sunrise = raw.sunrise
        self.sunset = raw.sunset
        self.maghrib_base = raw.maghrib_base
        self.fajr: float | None = None          # set by H8 only
        self.isha: float | None = None
        self.rules: dict[str, str] = {}
        self.h2_sunrise = False                 # H2 set this sunrise (S8)
        self.h2_sunset = False
        self._polar_asr: tuple[str, float] | None = None
        self._irn_asr = False                   # H8's Asr
        method = engine.method
        if engine.diyanet_preset:
            self._clamp(True)
        elif method["polar"] == "MIN_DAY_NIGHT_5H":
            if self._needs() and engine.gate(site, day.year):
                self._clamp(False)
        elif method["polar"] == "IRN_MECCA_CYCLE":
            self._mecca_cycle()
        else:
            raise ValueError(f"unknown polar rule {method['polar']}")

    def _needs(self) -> bool:
        sunrise, sunset, t = self.raw.sunrise, self.raw.sunset, self.transit
        return (sunrise is None or sunset is None
                or not (t - 9.5 * HOUR <= sunrise <= t - 2.5 * HOUR)
                or not (t + 2.5 * HOUR <= sunset <= t + 9.5 * HOUR))

    def _clamp(self, diyanet: bool) -> None:
        """H2: the 5-hour minimum, centred on T (T + 5 min for the Diyanet presets)."""
        name = "DIYANET_CLAMP" if diyanet else "MIN_DAY_NIGHT_5H"
        centre = self.transit + (300 if diyanet else 0)
        raw_sunrise, raw_sunset = self.raw.sunrise, self.raw.sunset
        if raw_sunrise is None:
            sunrise = centre - 2.5 * HOUR if self.raw.polar_night else centre - 9.5 * HOUR
        else:
            sunrise = min(max(raw_sunrise, centre - 9.5 * HOUR), centre - 2.5 * HOUR)
        if raw_sunset is None:
            sunset = centre + 2.5 * HOUR if self.raw.polar_night else centre + 9.5 * HOUR
        else:
            sunset = min(max(raw_sunset, centre + 2.5 * HOUR), centre + 9.5 * HOUR)
        maghrib_angle = self.engine.method["maghribAngle"]
        if raw_sunrise is None or abs(sunrise - raw_sunrise) > 0.001:
            self.h2_sunrise = True
            self.rules["SUNRISE"] = name
        if raw_sunset is None or abs(sunset - raw_sunset) > 0.001:
            self.h2_sunset = True
            if maghrib_angle is None:
                self.rules["MAGHRIB"] = name
        self.sunrise, self.sunset = sunrise, sunset
        if maghrib_angle is None:
            self.maghrib_base = sunset
        self._polar_asr = (name, self.transit) if diyanet else ("POLAR_ASR_MIDPOINT", (self.transit + sunset) / 2)

    def _mecca_cycle(self) -> None:
        """H8, from 63.56° (the zone's or place's point): IRN's northern rules."""
        if abs(self.site.point[0]) < IRN_NORTH:
            return
        times = self.engine.north_times(self.site, self.day)
        engine, raw = self.engine, self.raw
        real = {"SUNRISE": raw.sunrise, "MAGHRIB": raw.sunset,
                "FAJR": engine.angle_on(self.site, self.day, "FAJR"), "ISHA": engine.angle_on(self.site, self.day, "ISHA")}
        for name, value in (("SUNRISE", times["rise"]), ("MAGHRIB", times["set"]), ("FAJR", times["fajr"]),
                            ("ISHA", times["isha"])):
            if real[name] is None or abs(value - real[name]) > 0.001:
                self.rules[name] = "IRN_MECCA_CYCLE"
        self.sunrise, self.sunset = times["rise"], times["set"]
        self.fajr, self.isha = times["fajr"], times["isha"]
        if engine.method["maghribAngle"] is None:
            self.maghrib_base = self.sunset
        self._irn_asr = True

    @functools.cached_property
    def asr(self) -> tuple[float | None, str | None]:
        """The shadow Asr of steps 1-3 and its rule: S7, or the polar Asr (H2, H8) when that is absent."""
        if self.engine.method["asr"]["type"] not in SHADOW_KINDS:
            return None, None
        value = self.raw.asr
        if self._irn_asr:
            mid = (self.transit + self.sunset) / 2
            asr = min(mid if value is None else max(value, mid), self.sunset - HOUR)
            return asr, ("POLAR_ASR_MIDPOINT" if value is None or abs(asr - value) > 0.001 else None)
        if value is None and self._polar_asr is not None:
            return self._polar_asr[1], self._polar_asr[0]
        return value, None


@dataclass
class PrayerDay:
    unrounded: dict[str, float | None]
    minutes: dict[str, float | None]
    rules: dict[str, str]           # the ESTIMATED events and their rules

    def status(self, name: str) -> str:
        if self.unrounded[name] is None:
            return "ABSENT"
        return "ESTIMATED" if name in self.rules else "COMPUTED"


class CaseEngine:
    """S9: the order of operations for one case of cases.json."""

    def __init__(self, case: dict):
        self.case = case
        self.method = case["method"]
        self.model = SUN_MODELS[self.method["sunModel"]]
        self.day = datetime.date.fromisoformat(case["date"])
        self.place = case["place"]
        facts = case["zoneFacts"]
        self.standard_offset = facts["standardOffsetSeconds"]
        self.utc_offset = facts.get("utcOffsetSeconds", self.standard_offset)
        self.midnight = facts["localMidnightEpochSeconds"]
        self.offset_changes = facts.get("offsetChanges") or [[-1e18, self.utc_offset]]
        evaluation = self.method.get("sunEvaluation") or {}
        self.modes = {name: evaluation.get(name, "EVENT_INSTANT") for name in EVENTS}
        self.diyanet_preset = (self.method["polar"] == "DIYANET_CLAMP"
                               or self.method.get("minDayNightHours") is not None)
        self._raw: dict[tuple[str, datetime.date], RawDay] = {}
        self._steps: dict[tuple[str, datetime.date], DaySteps] = {}
        self._angles: dict[tuple[str, datetime.date, str], float | None] = {}
        self._lowest: dict[tuple[str, datetime.date], float | None] = {}
        self._polar_years: dict[tuple[str, int], bool] = {}
        self._lre: dict[tuple[str, int], float | None] = {}
        self._mecca: CaseEngine | None = None
        self._raw_north: dict = {}

    # S2 ---------------------------------------------------------------------------------------

    def cycle_date(self, site: Site, day: datetime.date) -> datetime.date:
        noon = epoch_days(day) * 86400 + 43200
        n = noon - self.standard_offset                 # D 12:00 − the standard offset
        t = n + 3600 * site.point[1] / 15
        return EPOCH_DATE + datetime.timedelta(days=math.floor(t / 86400))

    def local_midnight(self, day: datetime.date) -> float:
        """00:00 of day in Z: the case's fact for D, one standard day apart for other dates."""
        return self.midnight + 86400 * (day - self.day).days

    def offset_at(self, epoch: float) -> float:
        """Z's UTC offset at an instant, from zoneFacts' offset changes (the case's offset without them)."""
        offset = self.offset_changes[0][1]
        for start, value in self.offset_changes:
            if start <= epoch:
                offset = value
            else:
                break
        return offset

    def local_noon_jd(self, day: datetime.date) -> float:
        """S4: the Julian day of 12:00 local civil time in Z on day."""
        clock = epoch_days(day) * 86400 + 43200
        t = clock - self.offset_at(clock - self.utc_offset)
        t = clock - self.offset_at(t)
        return t / 86400 + UNIX_EPOCH_JD

    def clock_instant(self, day: datetime.date, seconds: float) -> float:
        """A clock time on a local date in Z, read with the case's UTC offset (H3, H4, H7)."""
        return epoch_days(day) * 86400 + seconds - self.utc_offset

    def raw_day(self, site: Site, day: datetime.date) -> RawDay:
        key = (site.key, day)
        if key not in self._raw:
            self._raw[key] = RawDay(self, site, day)
        return self._raw[key]

    def day_steps(self, site: Site, day: datetime.date) -> DaySteps:
        key = (site.key, day)
        if key not in self._steps:
            self._steps[key] = DaySteps(self, site, day)
        return self._steps[key]

    # S5, S6 -----------------------------------------------------------------------------------

    def exact_site(self) -> Site:
        point = lat_lng(self.place)
        elev = float(self.place["elevationM"]) if self.place.get("elevationM") is not None else 0.0
        return Site("exact", point, point, point, elev, elev, 1010.0, 10.0, None)

    def horizon_altitude(self, model: dict, site: Site, which: str, distance) -> float:
        kind = model["type"]
        h = site.morning_elev if which == "sunrise" else site.evening_elev
        if kind == "standard":
            return -0.8333
        if kind == "fixed":
            return float(model["altitudeDeg"])
        if kind == "pressure":
            return -(0.2667 + 0.5667 * math.exp(-h / 8434))
        if kind == "refraction":
            if site.zone is None and model.get("fallback") is not None:
                return self.horizon_altitude(model["fallback"], site, which, distance)
            dip = dip_deg(h)
            semi_diameter = 959.63 / 3600 / distance()
            return -(semi_diameter + bennett(-dip, site.pressure, site.temperature) + dip)
        if kind == "per_zone":
            value = None
            if site.zone is not None:
                value = site.zone.get("sunriseAltitudeDeg" if which == "sunrise" else "sunsetAltitudeDeg")
            if value is not None:
                return float(value)
            if model.get("fallback") is None:
                raise ValueError("PerZone horizon without a zone value or a fallback")
            return self.horizon_altitude(model["fallback"], site, which, distance)
        if kind == "standard_with_dip":
            return -0.8333 - dip_deg(h)
        if kind == "sqrt_height":
            return -(float(model["baseDeg"]) + float(model["perSqrtMetreDeg"]) * math.sqrt(max(h, 0.0)))
        raise ValueError(f"unknown horizon model {kind}")

    # H2's gate and H8's Mecca -----------------------------------------------------------------

    def has_polar_day(self, site: Site, year: int) -> bool:
        """Whether some day of the year has no raw sunrise, no raw sunset or (shadow methods) no shadow Asr."""
        key = (site.key, year)
        if key not in self._polar_years:
            shadow = self.method["asr"]["type"] in SHADOW_KINDS
            found = False
            day = datetime.date(year, 1, 1)
            while day.year == year and not found:
                raw = self.raw_day(site, day)
                found = raw.sunrise is None or raw.sunset is None or (shadow and raw.asr is None)
                day += ONE_DAY
            self._polar_years[key] = found
        return self._polar_years[key]

    def gate(self, site: Site, year: int) -> bool:
        """H2's gate for MIN_DAY_NIGHT_5H."""
        latitude = max(abs(site.morning[0]), abs(site.evening[0]))
        return latitude >= 60 and self.has_polar_day(site, year)

    def mecca_offsets(self, day: datetime.date) -> tuple[float, float, float, float]:
        """H8: Mecca's sunrise, sunset, Fajr and Isha, as seconds from its transit on the same date."""
        if self._mecca is None:
            method = copy.deepcopy(self.method)
            method.update({"fajr": {"type": "angle", "degrees": 18.0}, "isha": {"type": "angle", "degrees": 18.0},
                           "twilightLatitudeScale": 1.0, "polar": "MIN_DAY_NIGHT_5H", "minDayNightHours": None,
                           "highLatitude": {"type": "angle_based"},
                           "horizon": {"sunrise": {"type": "standard"}, "sunset": {"type": "standard"}}})
            # The method's own evaluations; local noon is the place's (its zone facts).
            self._mecca = CaseEngine({
                "id": self.case["id"] + "/mecca", "date": self.case["date"], "method": method,
                "place": {**MECCA_POINT, "elevationM": None, "zoneId": self.place["zoneId"], "countryCode": None},
                "ramadanDay": False, "zone": None, "baseZone": None, "offsetWindow": None, "adjustments": {},
                "zoneFacts": dict(self.case["zoneFacts"])})
        mecca = self._mecca
        steps = mecca.day_steps(mecca.exact_site(), day)
        t = steps.transit
        return (steps.sunrise - t, steps.sunset - t,
                steps.raw.angle_event("FAJR", 18.0) - t, steps.raw.angle_event("ISHA", 18.0) - t)

    def north_day(self, site: Site, d: datetime.date) -> dict:
        """H8: the real times of date d (raw, then capped), Mecca's day on d, and 00:00 UTC of d."""
        key = (site.key, d, "north")
        if key not in self._raw_north:
            raw = self.raw_day(site, d)
            t = raw.transit
            polar = raw.sunrise is None and raw.sunset is None and raw.polar_night
            rise_u = raw.sunrise if raw.sunrise is not None else (t if polar else t - 8 * HOUR)
            set_u = raw.sunset if raw.sunset is not None else (t if polar else t + 8 * HOUR)
            fajr, isha = self.angle_on(site, d, "FAJR"), self.angle_on(site, d, "ISHA")
            m_rise, m_set, m_fajr, m_isha = self.mecca_offsets(d)
            self._raw_north[key] = {
                "T": t, "rise_u": rise_u, "set_u": set_u,
                "rise": max(rise_u, t - 8 * HOUR) if raw.sunrise is not None else rise_u,
                "set": min(set_u, t + 8 * HOUR) if raw.sunset is not None else set_u,
                "fajr": t - 9 * HOUR if fajr is None or fajr < t - 9 * HOUR else fajr,
                "isha": t + 9 * HOUR if isha is None or isha > t + 9 * HOUR else isha,
                "M_rise": t + m_rise, "M_set": t + m_set, "M_fajr": t + m_fajr, "M_isha": t + m_isha,
                "mid0": epoch_days(d) * 86400,
            }
        return self._raw_north[key]

    def north_days(self, site: Site, year: int) -> dict:
        """H8's days: the zone's switchDays, or the ones a place without them derives from its own sun."""
        key = (site.key, year, "north-days")
        if key in self._raw_north:
            return self._raw_north[key]
        given = (site.zone or {}).get("switchDays") or {}
        days: dict = {"svalbard": False, "summer": None, "autumn": None, "spring": None}
        summer = given.get("MAGHRIB")
        if summer is not None:
            if summer["first"] <= 1 and summer["last"] >= 365:
                days["svalbard"] = True
            else:
                days["summer"] = (summer["first"] - 1, summer["last"] + 1)
            mecca, floor = given.get("SUNRISE") or {}, given.get("DHUHR") or {}
            if mecca.get("first"):
                days["autumn"] = ("MECCA", mecca["first"] - 1)
            elif floor.get("first"):
                days["autumn"] = ("FLOOR", floor["first"])
            if mecca.get("last"):
                days["spring"] = ("MECCA", mecca["last"] + 1)
            elif floor.get("last"):
                days["spring"] = ("FLOOR", floor["last"])
        else:
            def after(n: int) -> float | None:
                raw = self.raw_day(site, date_of_day_number(n, year))
                return None if raw.sunset is None else raw.sunset - raw.transit
            long_day = [n for n in range(60, 301) if (lambda x: x is None or x > 8 * HOUR + 5 * 60)(after(n))]
            first = next((n for n in long_day if n <= 172), None)
            last = next((n for n in reversed(long_day) if n >= 172), None)
            if first is not None and last is not None:
                days["summer"] = (first - 1, last + 1)
            if abs(site.point[0]) >= 66.33:
                short = lambda n: (lambda x: x is None or x < 3 * HOUR + 20 * 60)(after(n))
                s = next((n for n in range(260, 366) if short(n)), None)
                e = next((n for n in range(100, 0, -1) if short(n)), None)
                if s is not None:
                    days["autumn"] = ("MECCA", s - 1)
                if e is not None:
                    days["spring"] = ("MECCA", e + 1)
            else:
                days["autumn"], days["spring"] = ("FLOOR", 182), ("FLOOR", 181)
        self._raw_north[key] = days
        return days

    def north_times(self, site: Site, day: datetime.date) -> dict:
        """H8: sunrise, sunset, Fajr and Isha of day."""
        year, n = day.year, common_day_number(day)

        def date_of(m: int) -> datetime.date:
            if m < 1:
                return date_of_day_number(m + 365, year - 1)
            if m > 365:
                return date_of_day_number(m - 365, year + 1)
            return date_of_day_number(m, year)

        def nd(m: int) -> dict:
            return self.north_day(site, date_of(m))

        def line(m: int, m0: int, y0: float, m1: int, y1: float) -> float:
            u0, u1 = y0 - nd(m0)["mid0"], y1 - nd(m1)["mid0"]
            return nd(m)["mid0"] + u0 + (u1 - u0) * (m - m0) / (m1 - m0)

        cur = nd(n)
        mec = {k: cur["M_" + k] for k in ("rise", "set", "fajr", "isha")}
        out = {k: cur[k] for k in ("rise", "set", "fajr", "isha")}
        days = self.north_days(site, year)
        if days["svalbard"]:
            out = dict(mec)
        else:
            if days["summer"] is not None:
                a, b = days["summer"]
                for k in ("rise", "set"):
                    if a < n < a + 7:
                        out[k] = line(n, a, nd(a)[k + "_u"], a + 7, nd(a + 7)["M_" + k])
                    elif a + 7 <= n <= b - 7:
                        out[k] = mec[k]
                    elif b - 7 < n < b:
                        out[k] = line(n, b - 7, nd(b - 7)["M_" + k], b, nd(b)[k + "_u"])
                a2, b2 = a + 7, b - 8
                for k in ("fajr", "isha"):
                    if a2 < n < a2 + 7:
                        out[k] = line(n, a2, nd(a2)[k], a2 + 7, nd(a2 + 7)["M_" + k])
                    elif a2 + 7 <= n <= b2 - 7:
                        out[k] = mec[k]
                    elif b2 - 7 < n < b2:
                        out[k] = line(n, b2 - 7, nd(b2 - 7)["M_" + k], b2, nd(b2)[k])
            half = days["spring"] if n <= 181 else days["autumn"]
            if half is not None and half[0] == "MECCA":
                x = half[1]
                for k in ("rise", "set", "fajr", "isha"):
                    if n > 181:
                        if x < n < x + 30:
                            out[k] = line(n, x, nd(x)[k], x + 30, nd(x + 30)["M_" + k])
                        elif n >= x + 30:
                            out[k] = mec[k]
                    elif n <= x - 30:
                        out[k] = mec[k]
                    elif x - 30 < n < x:
                        out[k] = line(n, x - 30, nd(x - 30)["M_" + k], x, nd(x)[k])
            elif half is not None and half[0] == "FLOOR":
                inside = n >= half[1] if n > 181 else n <= half[1]
                if inside:
                    out["set"] = max(out["set"], cur["T"] + 2 * HOUR)
                    out["rise"] = min(out["rise"], cur["T"] - 2 * HOUR)
        out["set"] = min(out["set"], cur["T"] + 8 * HOUR)
        out["rise"] = max(out["rise"], cur["T"] - 8 * HOUR)
        return out

    # S8 ---------------------------------------------------------------------------------------

    def night(self, site: Site, evening_day: datetime.date) -> tuple[float | None, float | None]:
        """N(evening_day): its sunset and the next date's sunrise, both of steps 1-3."""
        return (self.day_steps(site, evening_day).sunset,
                self.day_steps(site, evening_day + ONE_DAY).sunrise)

    def night_length(self, site: Site, evening_day: datetime.date) -> float:
        start, end = self.night(site, evening_day)
        if start is None or end is None:
            raise ValueError(f"the night of {evening_day} has a missing end")
        return end - start

    def altitude_at(self, site: Site, evening_day: datetime.date, t: float) -> float:
        """The sun's altitude at instant t (S3, EVENT_INSTANT, evening point)."""
        cycle = self.raw_day(site, evening_day).cycle
        lat, lng = site.evening
        return altitude(cycle, self.model, lat, lng, cycle.hours(t))

    def lowest_point(self, site: Site, evening_day: datetime.date) -> float | None:
        key = (site.key, evening_day)
        if key in self._lowest:
            return self._lowest[key]
        start, end = self.night(site, evening_day)
        result = None
        if start is not None and end is not None:
            if self.day_steps(site, evening_day).h2_sunset or self.day_steps(site, evening_day + ONE_DAY).h2_sunrise:
                result = (start + end) / 2
            else:
                def rising(t: float) -> bool:
                    return self.altitude_at(site, evening_day, t + 0.5) > self.altitude_at(site, evening_day, t - 0.5)

                lo, hi = start, end
                if rising(lo) or not rising(hi):
                    result = (lo + hi) / 2
                else:
                    while hi - lo >= 0.1:
                        mid = (lo + hi) / 2
                        if rising(mid):
                            hi = mid
                        else:
                            lo = mid
                    result = (lo + hi) / 2
        self._lowest[key] = result
        return result

    # S9 steps 0-7 -----------------------------------------------------------------------------

    def rule_for(self, day: datetime.date) -> tuple[dict, dict, dict]:
        """Step 0 (E9): offsets, fajr and isha after every revision with validFrom ≤ day."""
        offsets = dict(self.method["offsets"])
        fajr, isha = self.method["fajr"], self.method["isha"]
        for revision in sorted(self.method["revisions"], key=lambda r: r["validFrom"]):
            if datetime.date.fromisoformat(revision["validFrom"]) > day:
                continue
            change = revision["change"]
            offsets.update(change.get("offsets") or {})
            if change.get("fajr") is not None:
                fajr = change["fajr"]
            if change.get("isha") is not None:
                isha = change["isha"]
        return offsets, fajr, isha

    @functools.cached_property
    def elevation_shift(self) -> float:
        """E16: by the place's own elevation, never the zone's."""
        own = self.place.get("elevationM")
        if own is None:
            return 0.0
        shift = 0.0
        for entry in sorted(self.method["elevationShifts"], key=lambda e: e["fromM"]):
            if entry["fromM"] <= own:
                shift = entry["minutes"]
        return shift

    def terrain_minutes(self, site: Site) -> float:
        """E10: Maghrib's terrain minutes."""
        terrain = self.method["maghribTerrain"]
        if terrain is None:
            return 0.0
        low_max, high_min = terrain["lowMaxM"], terrain["highMinM"]
        if site.zone is not None:
            h = site.zone["elevationM"] if site.zone.get("elevationM") is not None else 0
        else:
            h = self.place["elevationM"] if self.place.get("elevationM") is not None else 0
        if h <= low_max:
            return terrain["lowMinutes"]
        if h >= high_min:
            return terrain["highMinutes"]
        # A zone's own class; at the place's own point, the class the case passes (the nearest location's, E10).
        terrain_class = site.zone.get("terrainClass") if site.zone is not None else self.case.get("terrainClass")
        if terrain_class == "LOW":
            return terrain["lowMinutes"]
        if terrain_class == "HIGH":
            return terrain["highMinutes"]
        return terrain["lowMinutes"] if h - low_max <= high_min - h else terrain["highMinutes"]

    def steps_1_7(self, site: Site, day: datetime.date, shift: dict, ramadan: bool):
        """Steps 1-7: unrounded instants, the rules of the ESTIMATED ones, the steps 1-3 of day."""
        method = self.method
        offsets, fajr_rule, isha_rule = self.rule_for(day)
        steps = self.day_steps(site, day)                                       # steps 1-3
        rules: dict[str, str] = dict(steps.rules)
        ram = method["ramadanOffsets"] if ramadan else {}
        e16 = self.elevation_shift
        floor = (site.zone or {}).get("elevationShiftFloor")
        e16_evening = max(float(floor), e16) if floor is not None else e16       # E16's floor (Le Tampon)

        def season(name: str) -> float:
            """E18: the seasonal value and the dated offsets of the date."""
            return (seasonal_value((method["seasonalOffsets"] or {}).get(name), day)
                    + dated_value(method.get("datedOffsets") or [], name, day))

        def plus(t: float | None, minutes: float) -> float | None:
            return None if t is None else t + 60 * minutes

        times: dict[str, float | None] = dict.fromkeys(EVENTS)
        # Step 4: Maghrib.
        times["MAGHRIB"] = plus(steps.maghrib_base,
                                offsets.get("MAGHRIB", 0) + self.terrain_minutes(site) + ram.get("MAGHRIB", 0)
                                + shift.get("MAGHRIB", 0) + e16_evening + season("MAGHRIB"))
        # Step 5: Fajr, Isha, an Asr before Maghrib.
        if steps.fajr is not None:
            times["FAJR"] = steps.fajr
        elif fajr_rule["type"] == "angle":
            times["FAJR"] = self.angle_on(site, day, "FAJR")
        elif fajr_rule["type"] == "before_sunrise":
            times["FAJR"] = plus(steps.sunrise, -fajr_rule["minutes"])
        else:
            raise ValueError(f"unknown Fajr rule {fajr_rule['type']}")
        if steps.isha is not None:
            times["ISHA"] = steps.isha
        elif isha_rule["type"] == "angle":
            times["ISHA"] = self.angle_on(site, day, "ISHA")
        elif isha_rule["type"] == "after":
            start = times["MAGHRIB"] if isha_rule["from"] == "MAGHRIB" else steps.sunset
            n = isha_rule["minutes"]
            if ramadan and isha_rule.get("ramadan") is not None:
                n = isha_rule["ramadan"]
            times["ISHA"] = plus(start, n)
        else:
            raise ValueError(f"unknown Isha rule {isha_rule['type']}")
        if method["asr"]["type"] == "before_maghrib":
            times["ASR"] = plus(times["MAGHRIB"], -method["asr"]["minutes"])
        else:
            times["ASR"], asr_rule = steps.asr
            if asr_rule is not None:
                rules["ASR"] = asr_rule
        # Step 6: the high-latitude rule, on the angle times only.
        rule = method["highLatitude"]
        for name, by_angle in (("FAJR", fajr_rule["type"] == "angle"), ("ISHA", isha_rule["type"] == "angle")):
            if not by_angle:
                continue
            old = times[name]
            new = self.high_latitude(rule, site, day, name, old, times["MAGHRIB"])
            if new is not None and (old is None or abs(new - old) > 0.001):
                rules[name] = "IRN_SOUTHERN_SUMMER" if rule["type"] == "irn" else rule["type"].upper()
            times[name] = new
        # Step 7: the other offsets.
        isha_angle = isha_rule["type"] == "angle"
        times["FAJR"] = plus(times["FAJR"], offsets.get("FAJR", 0) + ram.get("FAJR", 0) + shift.get("FAJR", 0)
                             - e16 + season("FAJR"))
        sunrise = steps.sunrise
        if site.zone is not None and site.zone.get("sunriseAtPlace"):
            # 6.5: the sunrise row at the user's own point; Fajr by interval and the nights stay the zone's.
            sunrise = self.raw_day(self.exact_site(), day).sunrise
        times["SUNRISE"] = plus(sunrise, offsets.get("SUNRISE", 0) + ram.get("SUNRISE", 0)
                                + shift.get("SUNRISE", 0) - e16 + season("SUNRISE"))
        times["DHUHR"] = plus(steps.transit, offsets.get("DHUHR", 0) + method["dhuhrAfterTransitMin"]
                              + ram.get("DHUHR", 0) + shift.get("DHUHR", 0) + season("DHUHR"))
        times["ASR"] = plus(times["ASR"], offsets.get("ASR", 0) + ram.get("ASR", 0) + shift.get("ASR", 0)
                            + season("ASR"))
        times["ISHA"] = plus(times["ISHA"], offsets.get("ISHA", 0) + ram.get("ISHA", 0) + shift.get("ISHA", 0)
                             + (e16_evening if isha_angle else 0) + season("ISHA"))
        return times, rules, steps

    # The high-latitude rules (H3-H7) ----------------------------------------------------------

    def angle_degrees(self, day: datetime.date, name: str) -> float | None:
        rule = self.rule_for(day)[1 if name == "FAJR" else 2]
        return float(rule["degrees"]) if rule["type"] == "angle" else None

    def angle_on(self, site: Site, day: datetime.date, name: str) -> float | None:
        """"The angle": the method's Fajr or Isha angle event of day (S5, E17's latitude, E4's sun)."""
        key = (site.key, day, name)
        if key not in self._angles:
            degrees = self.angle_degrees(day, name)
            at = self.rule_for(day)[2].get("at") if name == "ISHA" else None
            self._angles[key] = None if degrees is None else self.raw_day(site, day).angle_event(name, degrees, at)
        return self._angles[key]

    def night_for(self, site: Site, day: datetime.date, name: str) -> float:
        """The length of the night a time belongs to: N(D) for Isha, N(D − 1) for Fajr."""
        return self.night_length(site, day if name == "ISHA" else day - ONE_DAY)

    def from_edge(self, site: Site, day: datetime.date, name: str, x: float) -> float:
        """sunset + x (Isha) or sunrise − x (Fajr), with the sunrise and sunset of steps 1-3."""
        steps = self.day_steps(site, day)
        return steps.sunset + x if name == "ISHA" else steps.sunrise - x

    def interval_on(self, site: Site, day: datetime.date, name: str) -> float:
        """The real interval of a date with the angle: Isha − its sunset, or its sunrise − Fajr."""
        steps = self.day_steps(site, day)
        angle = self.angle_on(site, day, name)
        return angle - steps.sunset if name == "ISHA" else steps.sunrise - angle

    def ramp(self, site: Site, day: datetime.date, name: str, x: float) -> float:
        """H3: hold x to the real interval of the first date before, then after, with the angle."""
        for sign in (-1, 1):
            for k in range(1, RAMP_DAYS + 1):
                other = day + sign * k * ONE_DAY
                if self.angle_on(site, other, name) is not None:
                    r = self.interval_on(site, other, name)
                    x = min(max(x, r - 300 * k), r + 300 * k)
                    break
        return x

    def lre_ratio(self, site: Site, day: datetime.date, name: str) -> float:
        """H3: LRE's R over D's calendar year, from the Isha by angle (else the Fajr by angle)."""
        key = (site.key, day.year)
        if key not in self._lre:
            _, fajr_rule, isha_rule = self.rule_for(day)
            base = "ISHA" if isha_rule["type"] == "angle" else ("FAJR" if fajr_rule["type"] == "angle" else None)
            ratios = []
            if base is not None:
                def interval(d: datetime.date) -> float | None:
                    return None if self.angle_on(site, d, base) is None else self.interval_on(site, d, base)

                first, last = datetime.date(day.year, 1, 1), datetime.date(day.year, 12, 31)
                previous, current = interval(first - ONE_DAY), interval(first)
                d = first
                while d <= last:
                    following = interval(d + ONE_DAY)
                    if (current is not None and previous is not None and following is not None
                            and abs(current - previous) <= 600 and abs(current - following) <= 600):
                        ratios.append(current / self.night_for(site, d, base))
                    previous, current = current, following
                    d += ONE_DAY
            self._lre[key] = sum(ratios) / len(ratios) if ratios else None
        ratio = self._lre[key]
        return ratio if ratio is not None else self.angle_degrees(day, name) / 60

    def absent_nights(self, rule: dict, site: Site, day: datetime.date, name: str, t: float | None) -> float | None:
        """H3: the absent-nights rules, ramped or not."""
        if self.angle_on(site, day, name) is not None:
            return t
        kind = rule["type"]
        night = self.night_for(site, day, name)
        ramped = True
        if kind == "angle_based_absent_nights":
            x = self.angle_degrees(day, name) / 60 * night
        elif kind == "local_relative_estimation":
            x = self.lre_ratio(site, day, name) * night
        elif kind == "one_seventh":
            x = night / 7
        elif kind == "middle_of_night":
            x = night / 2
            ramped = bool(rule.get("ramped"))
        elif kind == "nearest_day" and not rule.get("lastClockTime"):
            x = None
            for k in range(1, RAMP_DAYS + 1):
                for other in (day - k * ONE_DAY, day + k * ONE_DAY):
                    if self.angle_on(site, other, name) is not None:
                        x = self.interval_on(site, other, name) / self.night_for(site, other, name) * night
                        break
                if x is not None:
                    break
            if x is None:
                return t
        elif kind == "nearest_day":
            x = None
            for k in range(1, RAMP_DAYS + 1):
                angle = self.angle_on(site, day - k * ONE_DAY, name)
                if angle is not None:
                    local = angle + self.utc_offset
                    local_day = math.floor(local / 86400)
                    moved = (local_day + k) * 86400 + (local - local_day * 86400) - self.utc_offset
                    steps = self.day_steps(site, day)
                    x = moved - steps.sunset if name == "ISHA" else steps.sunrise - moved
                    break
            if x is None:
                return t
        else:
            raise ValueError(f"unknown absent-nights rule {kind}")
        if ramped:
            x = self.ramp(site, day, name, x)
        return self.from_edge(site, day, name, x)

    def angle_based(self, site: Site, day: datetime.date, name: str, t: float | None) -> float:
        """H4: D5's AngleBased."""
        bound = self.from_edge(site, day, name, self.angle_degrees(day, name) / 60 * self.night_for(site, day, name))
        return earlier(t, bound) if name == "ISHA" else later(t, bound)

    def takdir(self, site: Site, day: datetime.date, name: str, t: float | None) -> float | None:
        """H6: Diyanet's takdir."""
        phi = abs(site.evening[0])
        if phi < 44.5:
            return t
        alpha = self.angle_degrees(day, name)
        steps = self.day_steps(site, day)
        if phi >= 46:
            evening_day = day if name == "ISHA" else day - ONE_DAY
            lowest = self.lowest_point(site, evening_day)
            if lowest is not None:
                depression = -self.altitude_at(site, evening_day, lowest)
                clamped = min(max(phi, 46.62), 48.27)
                k = 2.41002 - 0.03603 * clamped if name == "FAJR" else 2.15968 - 0.03192 * clamped
                effective = min(alpha, k * depression)
                if effective < alpha:
                    moved = self.raw_day(site, day).angle_event(name, effective)
                    if moved is not None:
                        if t is None:
                            t = moved
                        else:
                            t = max(t, moved) if name == "FAJR" else min(t, moved)
        if name == "FAJR":
            minutes = 127.5 if phi >= 46 else 134 + (127.5 - 134) * (phi - 44.5) / 1.5
            t = later(t, steps.sunrise - 60 * minutes)
        elif phi < 46:
            minutes = 118 + (112.5 - 118) * (phi - 44.5) / 1.5
            t = earlier(t, steps.sunset + 60 * minutes)
        if t is None:
            t = self.from_edge(site, day, name, alpha / 60 * self.night_for(site, day, name))
        return t

    def switch_days(self, site: Site, day: datetime.date, name: str) -> tuple[int, int] | None:
        """H7: [first, last] for ISHA or FAJR, from the zone or from the year's events."""
        given = (site.zone or {}).get("switchDays") or {}
        if name in given:
            return given[name]["first"], given[name]["last"]
        if name == "ISHA":
            def late(n: int) -> bool:
                isha = self.angle_on(site, date_of_day_number(n, day.year), "ISHA")
                return isha is None or utc_hours(isha) >= 20
            first = next((n for n in range(32, 173) if late(n)), None)
            last = next((n for n in range(365, 171, -1) if late(n)), None)
            return None if first is None or last is None else (first + 1, last - 1)
        for n in range(32, 173):
            other = date_of_day_number(n, day.year)
            fajr = self.angle_on(site, other, "FAJR")
            if fajr is None or self.day_steps(site, other).sunrise - fajr >= 153 * 60:
                return n, 341 - n
        return None

    def irn(self, site: Site, day: datetime.date, name: str, t: float | None) -> float | None:
        """H7: IRN's rule (from 63.56°, H8 has set Fajr and Isha)."""
        if abs(site.point[0]) >= IRN_NORTH:
            return t
        days = self.switch_days(site, day, name)
        n = common_day_number(day)
        if days is None or not days[0] <= n <= days[1]:
            return t
        first, last = days
        steps = self.day_steps(site, day)
        if name == "ISHA":
            delay = 60 * (self.rule_for(day)[0].get("MAGHRIB", 0) + 40)
            target = utc_hours(self.day_steps(site, date_of_day_number(172, day.year)).sunset + delay)
            if n <= 172:
                x0, y0, x1, y1 = first - 1, utc_hours(self.angle_on(site, date_of_day_number(first - 1, day.year),
                                                                      "ISHA")), 172, target
            else:
                x0, y0, x1, y1 = 172, target, last + 1, utc_hours(self.angle_on(site, date_of_day_number(
                    last + 1, day.year), "ISHA"))
            hours = y0 + (y1 - y0) * (n - x0) / (x1 - x0)
            return max(epoch_days(day) * 86400 + 3600 * hours, steps.sunset + delay)
        reference = first - 1 if n <= 172 else last + 1
        fajr = self.angle_on(site, date_of_day_number(reference, day.year), "FAJR")
        local = fajr + self.offset_at(fajr)
        clock = local - math.floor(local / 86400) * 86400
        return min(self.clock_instant(day, clock), steps.sunrise - HOUR)

    def high_latitude(self, rule: dict, site: Site, day: datetime.date, name: str, t: float | None,
                      maghrib: float | None) -> float | None:
        """Step 6: the method's high-latitude rule on an angle time t."""
        kind = rule["type"]
        steps = self.day_steps(site, day)
        if kind == "angle_based":
            return self.angle_based(site, day, name, t)
        if kind in ("angle_based_absent_nights", "local_relative_estimation", "one_seventh", "middle_of_night",
                    "nearest_day"):
            return self.absent_nights(rule, site, day, name, t)
        if kind == "clock_caps":
            text = rule.get("ishaLatest") if name == "ISHA" else rule.get("fajrEarliest")
            if text is None:
                return t
            seconds = clock_seconds(text)
            if name == "ISHA":
                local_day = day + ONE_DAY if seconds < 12 * HOUR else day
                return earlier(t, self.clock_instant(local_day, seconds))
            local_day = day - ONE_DAY if seconds > 12 * HOUR else day
            return later(t, self.clock_instant(local_day, seconds))
        if kind == "moonsighting_seasonal":
            lat = site.evening[0]
            night = self.night_for(site, day, name)
            if name == "ISHA":
                if rule.get("fajrOnly"):
                    return t
                base = steps.sunset + night / 7 if abs(lat) >= 55 else t
                curve = steps.sunset + 60 * twilight_curve(lat, day, MOONSIGHTING_ISHA)
                return curve if base is None else min(base, curve)
            base = steps.sunrise - night / 7 if abs(lat) >= 55 else t
            curve = steps.sunrise - 60 * twilight_curve(lat, day, MOONSIGHTING_FAJR)
            return curve if base is None else max(base, curve)
        if kind == "dum_rf":
            # H4: N' = 24 h minus D's own day.
            if steps.sunrise is None or steps.sunset is None:
                return t
            night = 86400 - (steps.sunset - steps.sunrise)
            if name == "ISHA":
                return earlier(t, steps.sunset + dum_rf_share(day) * night)
            return later(t, steps.sunrise - 0.30 * night)
        if kind == "dum_rt":
            evening_day = day if name == "ISHA" else day - ONE_DAY
            lowest = self.lowest_point(site, evening_day)
            if lowest is None or self.altitude_at(site, evening_day, lowest) <= -18:
                return t
            return steps.sunset + 90 * 60 if name == "ISHA" else steps.sunrise - 120 * 60
        if kind == "summer_form":
            start = (rule["fromMonth"], rule["fromDay"])
            end = (rule["toMonth"], rule["toDay"])
            today = (day.month, day.day)
            inside = start <= today <= end if start <= end else (today >= start or today <= end)
            if not inside:
                return self.angle_based(site, day, name, t)
            if name == "FAJR":
                return later(t, steps.sunrise - 60 * rule["fajrBeforeSunrise"])
            minutes = rule.get("ishaAfterMaghrib")
            return t if minutes is None or maghrib is None else earlier(t, maghrib + 60 * minutes)
        if kind == "diyanet_takdir":
            return self.takdir(site, day, name, t)
        if kind == "irn":
            return self.irn(site, day, name, t)
        raise ValueError(f"unknown high-latitude rule {kind}")

    # S11 --------------------------------------------------------------------------------------

    def _difference(self, own: Site, base: Site, day: datetime.date) -> dict[str, float]:
        """own − base in minutes: steps 1-7 at both sites, no zone shift, no Ramadan day."""
        own_times = self.steps_1_7(own, day, {}, False)[0]
        base_times = self.steps_1_7(base, day, {}, False)[0]
        result = {}
        for name in EVENTS:
            if own_times[name] is None or base_times[name] is None:
                raise ValueError(f"derived shift: {name} absent on {day}")
            result[name] = (own_times[name] - base_times[name]) / 60
        return result

    def running_site_and_shift(self) -> tuple[Site, dict[str, float]]:
        zone, base_zone = self.case["zone"], self.case["baseZone"]
        if zone is None:
            return self.exact_site(), {}
        if zone.get("baseZoneId") is None or base_zone is None:
            return zone_site(zone), dict(zone["offsets"])
        site = zone_site(base_zone)
        rule = zone["offsetRule"]
        if rule == "PUBLISHED":
            extra = dict(zone["offsets"])
        elif rule == "MONTHLY_DIFFERENCE":
            diff = self._difference(zone_site(zone), site, self.day.replace(day=15))
            extra = {name: math.floor(2 * x + 0.5) / 2 for name, x in diff.items()}
        elif rule == "WINDOW_CEILING":
            window = self.case.get("offsetWindow")
            if window:
                first = datetime.date.fromisoformat(window["from"])
                last = datetime.date.fromisoformat(window["to"])
            else:
                first = last = self.day
            highest = dict.fromkeys(EVENTS, -math.inf)
            day = first
            while day <= last:
                for name, x in self._difference(zone_site(zone), site, day).items():
                    highest[name] = max(highest[name], x)
                day += ONE_DAY
            extra = {name: math.ceil(x - 1e-9) for name, x in highest.items()}
        elif rule == "TAKVIM":
            extra = self.takvim_shift(zone)
        else:
            raise ValueError(f"unknown offset rule {rule}")
        base_offsets = base_zone["offsets"]
        return site, {name: base_offsets.get(name, 0) + extra.get(name, 0) for name in EVENTS}

    def takvim_shift(self, zone: dict) -> dict[str, float]:
        means = self.method["takvimMeans"]
        month = self.day.month

        def parts(point: dict) -> tuple[float, float]:
            """Δλ and Δφ at one of the zone's E6 points."""
            lat, lng = lat_lng(point)
            if lat >= SARAJEVO_LAT:
                tau, g = means[month - 1], 1.1
            else:
                tau, g = means[12 + month - 1], 0.9
            return 4 * (SARAJEVO_LNG - lng), tau * g * (lat - SARAJEVO_LAT)

        d_lng_m, d_lat_m = parts(zone.get("morningPoint") or zone["point"])
        d_lng_e, d_lat_e = parts(zone.get("eveningPoint") or zone["point"])
        morning = math.floor(d_lng_m + d_lat_m + 0.5)
        evening = math.floor(d_lng_e - d_lat_e + 0.5)
        return {"FAJR": morning, "SUNRISE": morning, "DHUHR": math.floor(d_lng_e + 0.5),
                "ASR": evening, "MAGHRIB": evening, "ISHA": evening}

    # S10, S12, S13 ----------------------------------------------------------------------------

    def envelope(self, site: Site, steps: DaySteps, times: dict, rules: dict, modes: dict,
                 p_minus: float | None, isha_interval: bool) -> None:
        """S10 (E19)."""
        france = self.place.get("countryCode") == "FR"
        custom = self.method["id"] == "custom"
        if not (france or custom):
            return
        raw = steps.raw
        fajr_degrees = 20.0 if custom and not france else 18.0

        def move(name: str, value: float, mode: str) -> None:
            times[name] = value
            modes[name] = mode
            rules.setdefault(name, "VALIDITY_ENVELOPE")

        if times["FAJR"] is not None:
            f_main = raw.morning_angle(fajr_degrees)
            if f_main is None:
                f_main = p_minus
            if f_main is not None and times["FAJR"] < f_main - 60:
                move("FAJR", f_main, "UP")
            f12 = raw.morning_angle(12.0)
            if f12 is not None and times["FAJR"] > f12:
                move("FAJR", f12, "DOWN")
        if times["SUNRISE"] is not None and times["SUNRISE"] > steps.sunrise + 60:
            move("SUNRISE", steps.sunrise, "DOWN")
        if times["DHUHR"] is not None and times["DHUHR"] < steps.transit + 72 - 60:
            move("DHUHR", steps.transit + 72, "UP")
        if times["ASR"] is not None and raw.a1 is not None and times["ASR"] < raw.a1 - 60:
            move("ASR", raw.a1, "UP")
        if times["MAGHRIB"] is not None and times["MAGHRIB"] < steps.sunset + 60 - 60:
            move("MAGHRIB", steps.sunset + 60, "UP")
        if times["ISHA"] is not None:
            i12 = raw.evening_angle(12.0)
            if isha_interval or "ISHA" in rules:
                shafaq = steps.sunset + 60 * shafaq_ahmer_minutes(site.evening[0], self.day)
                if times["ISHA"] < shafaq:
                    move("ISHA", shafaq, "UP")
                elif i12 is None or times["ISHA"] < i12:
                    rules.setdefault("ISHA", "VALIDITY_ENVELOPE")
            elif i12 is not None and times["ISHA"] < i12:
                move("ISHA", i12, "UP")

    def steppers(self, steps: DaySteps, times: dict, rules: dict) -> None:
        """S12: the user's steppers and the window check."""
        lower = {"DHUHR": steps.transit + 72, "ASR": steps.raw.a1, "MAGHRIB": steps.sunset}
        for name in EVENTS:
            n = (self.case.get("adjustments") or {}).get(name, 0)
            u = times[name]
            if not n or u is None:
                continue
            v = u + 60 * n
            if name in lower and lower[name] is not None:
                bound = lower[name]
                if u >= bound:
                    result = bound if v < bound - 60 else v
                else:
                    result = v if n > 0 else u
            elif name in ("FAJR", "SUNRISE") and steps.sunrise is not None:
                bound = steps.sunrise
                if u <= bound:
                    result = bound if v > bound + 60 else v
                else:
                    result = v if n < 0 else u
            else:
                result = v
            if result != v:
                rules.setdefault(name, "ADJUSTMENT_HELD")
            times[name] = result

    @staticmethod
    def walk(values: dict[str, float | None]) -> dict[str, float | None]:
        """S13 step 1: Fajr = min(Fajr, sunrise), then each = max(itself, the last present time)."""
        out = dict(values)
        if out["FAJR"] is not None and out["SUNRISE"] is not None:
            out["FAJR"] = min(out["FAJR"], out["SUNRISE"])
        last = out["FAJR"]
        for name in EVENTS[1:]:
            if out[name] is None:
                continue
            if last is not None:
                out[name] = max(out[name], last)
            last = out[name]
        return out

    def order_guard(self, times: dict, rules: dict, modes: dict,
                    p_minus: float | None, p_plus: float | None) -> PrayerDay:
        """S13."""
        walked = self.walk(times)
        for name in EVENTS:
            if times[name] is not None and abs(walked[name] - times[name]) > 0.001:
                rules.setdefault(name, "HELD_IN_ORDER")
        held = dict(walked)
        if p_plus is not None:
            for name in ("MAGHRIB", "ISHA"):
                if held[name] is not None:
                    held[name] = min(held[name], p_plus)
        if p_minus is not None:
            for name in ("FAJR", "SUNRISE"):
                if held[name] is not None:
                    held[name] = max(held[name], p_minus)
        for name in EVENTS:
            if walked[name] is not None and abs(held[name] - walked[name]) > 0.001:
                rules.setdefault(name, "NIGHT_LOWEST_POINT")
        minutes: dict[str, float | None] = {}
        for name in EVENTS:
            t = held[name]
            if t is None:
                minutes[name] = None
                continue
            mode = modes[name]
            if name in ("MAGHRIB", "ISHA") and p_plus is not None and abs(t - p_plus) < 60:
                mode = "NEAREST"
            if name in ("FAJR", "SUNRISE") and p_minus is not None and abs(t - p_minus) < 60:
                mode = "NEAREST"
            minutes[name] = round_instant(t, mode)
        return PrayerDay(held, self.walk(minutes), rules)

    # The whole day ----------------------------------------------------------------------------

    def run(self) -> PrayerDay:
        method = self.method
        site, shift = self.running_site_and_shift()
        times, rules, steps = self.steps_1_7(site, self.day, shift, bool(self.case["ramadanDay"]))
        _, _, isha_rule = self.rule_for(self.day)
        isha_interval = isha_rule["type"] == "after"
        modes = {name: method["rounding"][name] for name in EVENTS}
        if isha_interval:
            modes["ISHA"] = method["rounding"]["MAGHRIB"]
        if method["asr"]["type"] == "before_maghrib":
            modes["ASR"] = method["rounding"]["MAGHRIB"]
        p_minus = self.lowest_point(site, self.day - ONE_DAY)
        p_plus = self.lowest_point(site, self.day)
        # Step 8: the caps, then E19.
        facts = self.case["zoneFacts"]
        latest, earliest = facts.get("ishaLatestEpochSeconds"), facts.get("ishaEarliestEpochSeconds")
        if latest is not None and times["ISHA"] is not None and times["ISHA"] > latest:
            times["ISHA"] = float(latest)
            rules.setdefault("ISHA", "ISHA_LATEST")
        if earliest is not None and times["ISHA"] is not None:
            times["ISHA"] = max(times["ISHA"], float(earliest))
        self.envelope(site, steps, times, rules, modes, p_minus, isha_interval)
        self.steppers(steps, times, rules)                                     # step 9
        return self.order_guard(times, rules, modes, p_minus, p_plus)         # step 10


# ---------------------------------------------------------------------------------------------
# Files and command line.

def iso_minute(t: float) -> str:
    return datetime.datetime.fromtimestamp(int(t), datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def load_cases(path: Path) -> list[dict]:
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def case_rows(case: dict, day: PrayerDay) -> list[str]:
    rows = []
    for name in EVENTS:
        unrounded = day.unrounded[name]
        if unrounded is None:
            rows.append("\t".join((case["id"], name, "ABSENT", "-", "-", "-")))
        else:
            rows.append("\t".join((case["id"], name, day.status(name), day.rules.get(name, "-"),
                                   iso_minute(day.minutes[name]), f"{unrounded:.3f}")))
    return rows


def expected_tsv(cases: list[dict]) -> str:
    """The text of expected.tsv; raises naming every case that failed."""
    lines = [EXPECTED_HEADER.rstrip("\n")]
    failures = []
    for case in cases:
        try:
            lines.extend(case_rows(case, CaseEngine(case).run()))
        except ValueError as error:
            failures.append(f"{case['id']}: {error}")
    if failures:
        raise RuntimeError("cases that raised:\n  " + "\n  ".join(failures))
    return "\n".join(lines) + "\n"


def describe_case(case: dict) -> str:
    engine = CaseEngine(case)
    site, shift = engine.running_site_and_shift()
    steps = engine.day_steps(site, engine.day)
    day = engine.run()
    offset = engine.utc_offset
    sign = "+" if offset >= 0 else "-"
    zone_label = f"{sign}{abs(offset) // 3600:02d}:{abs(offset) % 3600 // 60:02d}"

    def local(t: float) -> str:
        return datetime.datetime.fromtimestamp(int(t) + offset, datetime.timezone.utc).strftime("%Y-%m-%d %H:%M")

    out = [f"{case['id']}: D {engine.day}, U {steps.raw.cycle.u}, site {site.key}, UTC offset {zone_label}",
           f"  transit {iso_minute(round_instant(steps.transit, 'NEAREST'))} ({steps.transit:.3f})"]
    if shift:
        out.append("  zone shift " + ", ".join(f"{k} {v:+g}" for k, v in shift.items() if v))
    for name in EVENTS:
        if day.unrounded[name] is None:
            out.append(f"  {name:<8} ABSENT")
            continue
        out.append(f"  {name:<8} {day.status(name):<9} {day.rules.get(name, '-'):<25}"
                   f" {iso_minute(day.minutes[name])}  {local(day.minutes[name])} {zone_label}"
                   f"  ({day.unrounded[name]:.3f})")
    return "\n".join(out)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--cases", type=Path, required=True, help="cases.json")
    parser.add_argument("--out", type=Path, help="write expected.tsv here")
    parser.add_argument("--case", help="print one case's six times instead")
    args = parser.parse_args(argv)
    cases = load_cases(args.cases)
    if args.case:
        matches = [case for case in cases if case["id"] == args.case]
        if not matches:
            print(f"no case {args.case}", file=sys.stderr)
            return 1
        print(describe_case(matches[0]))
        return 0
    if args.out is None:
        parser.error("give --out or --case")
    try:
        text = expected_tsv(cases)
    except RuntimeError as error:
        print(error, file=sys.stderr)
        return 1
    with open(args.out, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)
    print(f"{len(cases)} cases, {6 * len(cases)} rows -> {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
