"""
Diyanet's takdir (README H6) as Diyanet's own 2027 tables show it, a closed-form emulation for `diyanet_eu` and
`diyanet_tr` (fits/diyanet_takdir/README-part.md; standard library and the reference engine's MEEUS sun only).

Diyanet's criteria (the International Prayer Times Congress of Diyanet and the European Council for Fatwa and Research,
Istanbul, September 2021, applied in Europe from 2023; Diyanet's awqatsalah service, «Vakit Tespit Kriterleri»): the real
Isha (16 degrees in Europe) is estimated when it falls after one third of the sharia night, as Maghrib plus that third;
the estimated imsak is sunrise minus the third and two sixteenths of it, used when the real imsak (18 degrees) is
earlier; where the real imsak does not form, the share of the night of the last day it formed is kept; the switch is
gradual, starting 20 minutes away from the estimated time (21 June where the imsak forms all year); sunrise and Maghrib
keep a day and a night of at least 5 hours (Dhuhr -/+ 2.5 h); takdir times must not run against the sun's natural
movement (imsak and sunrise never earlier, Maghrib and Isha never later than on 21 June). As the tables apply them,
every quantity of a date x is that date's own (the sun once a day at 0 h UT of x, E4 DAILY_0H_UT; minutes are UTC
minutes after 0 h UT of x, unrounded; the northern hemisphere only):

    F(x), I(x)   the plain Fajr (18 deg) and Isha (16 or 17 deg) angle events, or None
    R(x), M(x)   the published sunrise and Maghrib: min(max(sunrise - 7, D - 9.5 h), D - 2.5 h) and
                 min(max(sunset + 7, D + 2.5 h), D + 9.5 h), D = Dhuhr = transit + 5 min (no sunrise: D -/+ 2.5 h in a
                 polar night, D -/+ 9.5 h under a midnight sun)
    S(x)         F(x) + 24 h - M(x)        the date's sharia night: its Maghrib to its own imsak, a day later
    N(x)         R(x) + 24 h - M(x)        the date's customary night
    TI(x)        M(x) + S(x) / 3           the one-third Isha
    TF(x)        R(x) - k S(x) / 3         the one-third imsak, k = Fajr angle / Isha angle (18/16 = 1 + 2/16)

    no takdir    south of 44.375 N (Rodez, 44.365 N, has none; Cuneo, 44.385 N, has one), the southern hemisphere
                 included (Diyanet's tables at 45.9-54.8 S keep the plain angles and print 00:00 where one is absent)
    case A       F on every date of February-November (to 48.56 N): anchors A_F = TF(21 June), A_I = TI(21 June)
    case B       no F on the dates xa..xb: r = S(dl) / 3 / N(dl), dl = xa - 1 the last date with a real imsak; on xa..xb
                 Fajr = R - k r N and Isha = M + r N (the same r after 21 June); anchors those values on xa (spring
                 side) and xb (autumn side)
    transition   on each side of an anchor day a: the n dates next to a whose plain time is past A + 20 (Fajr earlier
                 than A_F + 20, Isha later than A_I - 20, no angle counting as past); on the date i days from a
                 (1 <= i <= n) the time is A + 20 i / n (A_I - 20 i / n), the later Fajr (earlier Isha) of that line and
                 the plain time; on a, A
    held night   where both ends of 21 June's night are held (sunrise - 7 before D - 9.5 h and sunset + 7 after
                 D + 9.5 h: from about 60.2 N): R >= R(21 June) and M <= M(21 June); M never earlier again before
                 21 June and R never earlier again after it; Fajr >= Fajr(21 June) and Isha <= Isha(21 June); in case B
                 the Isha never earlier again before 21 June and the Fajr never earlier again after it

The 21 June date is the calendar date in every year. `takdir_year` returns, for every date of a year, the takdir Fajr
and Isha in UTC minutes (None where the plain time is absent and no rule sets it); its keyword options are the variants
fit.py compares (min_days, june_cap, holds, cutoff), their defaults the rule.
"""

from __future__ import annotations

import datetime
import math
import sys
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
import prayer_engine_reference as ref  # noqa: E402

ONE = datetime.timedelta(days=1)
D2R = math.pi / 180
HORIZON = -0.8333
SUNRISE_OFFSET, MAGHRIB_OFFSET, DHUHR_OFFSET = -7.0, 7.0, 5.0     # Diyanet's offsets (EuropeanPresets.DIYANET_OFFSETS)
SHORTEST = 150.0              # the 5-hour rule: half of the shortest day or night, minutes
STEP = 20.0                   # the switch starts 20 minutes from the anchor
START_LATITUDE = 44.375       # no takdir south of it: Rodez (44.365 N) has none, Cuneo (44.385 N) has one
MAX_SCAN = 200                # days scanned for a transition (a guard; real transitions last 16-70 days)


@dataclass
class Day:
    """One date's quantities at a point, the sun once a day at 0 h UT: UTC minutes after 0 h UT of the date."""
    date: datetime.date
    transit: float
    sunrise: float | None
    sunset: float | None
    fajr: float | None
    isha: float | None
    polar_night: bool
    R: float = 0.0            # the published sunrise (sunrise - 7, held by the 5-hour rule)
    M: float = 0.0            # the published Maghrib (sunset + 7, held by the 5-hour rule)


def day_of(date: datetime.date, lat: float, lng: float, fajr_angle: float, isha_angle: float) -> Day:
    decl, eot, _ = ref.meeus_sun(ref.julian_day0(date))
    noon = (12 - eot / 60 - lng / 15) * 60
    sl, cl = math.sin(lat * D2R), math.cos(lat * D2R)
    sd, cd = math.sin(decl * D2R), math.cos(decl * D2R)

    def half(alt: float) -> float | None:
        c = (math.sin(alt * D2R) - sl * sd) / (cl * cd)
        return None if abs(c) > 1 else math.acos(c) / D2R * 4

    h0, hf, hi = half(HORIZON), half(-fajr_angle), half(-isha_angle)
    noon_alt = math.asin(max(-1.0, min(1.0, sl * sd + cl * cd))) / D2R
    day = Day(date, noon, None if h0 is None else noon - h0, None if h0 is None else noon + h0,
              None if hf is None else noon - hf, None if hi is None else noon + hi, noon_alt < HORIZON)
    dhuhr = noon + DHUHR_OFFSET
    if day.sunrise is None:
        day.R = dhuhr - SHORTEST if day.polar_night else dhuhr - (720 - SHORTEST)
        day.M = dhuhr + SHORTEST if day.polar_night else dhuhr + (720 - SHORTEST)
    else:
        day.R = min(max(day.sunrise + SUNRISE_OFFSET, dhuhr - (720 - SHORTEST)), dhuhr - SHORTEST)
        day.M = min(max(day.sunset + MAGHRIB_OFFSET, dhuhr + SHORTEST), dhuhr + (720 - SHORTEST))
    return day


@dataclass
class Place:
    lat: float
    lng: float
    fajr_angle: float = 18.0
    isha_angle: float = 16.0
    june_bound: bool = True       # the 5-hour rule's 21 June bound and holds on sunrise and Maghrib
    _days: dict = field(default_factory=dict, repr=False)
    _held: dict = field(default_factory=dict, repr=False)

    def held(self, year: int) -> bool:
        if year not in self._held:
            self._held[year] = held_june(self, year)
        return self._held[year]

    def day(self, date: datetime.date) -> Day:
        if date not in self._days:
            day = day_of(date, self.lat, self.lng, self.fajr_angle, self.isha_angle)
            if self.june_bound and date.month in (3, 4, 5, 6, 7, 8, 9, 10) and self.held(date.year):
                # Where the 5-hour rule holds both ends of 21 June's night (the Congress: takdir times must not run
                # against the sun's natural movement): sunrise never earlier and Maghrib never later than on 21 June,
                # Maghrib never earlier again before 21 June and sunrise never earlier again after it.
                june = day_of(datetime.date(date.year, 6, 21), self.lat, self.lng, self.fajr_angle, self.isha_angle)
                day.R = max(day.R, june.R)
                day.M = min(day.M, june.M)
                sol = datetime.date(date.year, 6, 21)
                if datetime.date(date.year, 3, 1) < date <= sol:
                    day.M = max(day.M, self.day(date - ONE).M)
                elif sol < date <= datetime.date(date.year, 10, 31):
                    day.R = max(day.R, self.day(date - ONE).R)
            self._days[date] = day
        return self._days[date]


@dataclass
class Takdir:
    """A year's takdir: {date: (fajr, isha)} in UTC minutes, and how it was built."""
    times: dict
    case: str                                   # "none", "A" or "B"
    info: dict


def sharia_night(d: Day) -> float | None:
    return None if d.fajr is None else d.fajr + 1440 - d.M


def customary_night(d: Day) -> float:
    return d.R + 1440 - d.M


def held_june(place, year: int) -> bool:
    """Whether the 5-hour rule holds both ends of 21 June's published night (sunrise - 7 before Dhuhr - 9.5 h and
    sunset + 7 after Dhuhr + 9.5 h, or a midnight sun): the night of 21 June is then exactly 5 h."""
    june = day_of(datetime.date(year, 6, 21), place.lat, place.lng, place.fajr_angle, place.isha_angle)
    dhuhr = june.transit + DHUHR_OFFSET
    return june.sunrise is None or (june.sunrise + SUNRISE_OFFSET < dhuhr - (720 - SHORTEST)
                                    and june.sunset + MAGHRIB_OFFSET > dhuhr + (720 - SHORTEST))


def takdir_year(place: Place, year: int, min_days: int = 0, june_cap: str = "held", holds: bool = True,
                cutoff: str = "latitude") -> Takdir:
    k = place.fajr_angle / place.isha_angle
    first, last = datetime.date(year, 1, 1), datetime.date(year, 12, 31)
    dates = [first + i * ONE for i in range((last - first).days + 1)]
    times = {d: [place.day(d).fajr, place.day(d).isha] for d in dates}
    sol = datetime.date(year, 6, 21)
    s = place.day(sol)
    info: dict = {}

    def third_isha(d: Day) -> float:
        return d.M + sharia_night(d) / 3

    def third_fajr(d: Day) -> float:
        return d.R - k * sharia_night(d) / 3

    if cutoff == "latitude" and place.lat < START_LATITUDE:     # north only: the southern tables keep the angles
        return Takdir({d: tuple(v) for d, v in times.items()}, "none", info)
    summer = [d for d in dates if datetime.date(year, 2, 1) <= d <= datetime.date(year, 11, 30)]
    absent = [d for d in summer if place.day(d).fajr is None]
    if not absent:
        if cutoff == "isha" and (s.isha is None or s.isha <= third_isha(s)):
            return Takdir({d: tuple(v) for d, v in times.items()}, "none", info)
        case = "A"
        anchors = {0: (sol, third_fajr(s), sol, third_fajr(s)), 1: (sol, third_isha(s), sol, third_isha(s))}
    else:
        case = "B"
        xa, xb = absent[0], absent[-1]
        dl = place.day(xa - ONE)
        r = sharia_night(dl) / 3 / customary_night(dl)

        def frozen(d: datetime.date, j: int) -> float:
            day = place.day(d)
            n = customary_night(day)
            return day.R - k * r * n if j == 0 else day.M + r * n

        for d in summer:
            if xa <= d <= xb:
                times[d] = [frozen(d, 0), frozen(d, 1)]
        anchors = {j: (xa, frozen(xa, j), xb, frozen(xb, j)) for j in (0, 1)}
        info.update({"xa": xa, "xb": xb, "dl": dl.date, "r": r})
    for j in (0, 1):                              # 0 Fajr, 1 Isha
        a1, v1, a2, v2 = anchors[j]
        sign = 1 if j == 0 else -1

        def plain(d: datetime.date) -> float | None:
            day = place.day(d)
            return day.fajr if j == 0 else day.isha

        def past(d: datetime.date, a_value: float) -> bool:
            p = plain(d)
            return p is None or (p < a_value + STEP if j == 0 else p > a_value - STEP)

        lengths = []
        for a, value, step in ((a1, v1, -ONE), (a2, v2, ONE)):
            n = 0
            while n < MAX_SCAN and past(a + (n + 1) * step, value):
                n += 1
            length = max(n, min_days)
            lengths.append(length)
            for i in range(1, length + 1):
                d = a + i * step
                if d.year != year:
                    continue
                line = value + sign * STEP * i / length
                p = plain(d)
                times[d][j] = line if p is None else (max(line, p) if j == 0 else min(line, p))
            if a.year == year:
                times[a][j] = value
        info[("FAJR", "ISHA")[j]] = anchors[j]
        info[("FAJR_DAYS", "ISHA_DAYS")[j]] = tuple(lengths)
    held = held_june(place, year)
    if june_cap == "always" or (june_cap == "held" and held):
        # Where the 5-hour rule holds 21 June's night: no takdir Fajr earlier, and no Isha later, than 21 June's (the
        # times of day, UTC).
        cap_f, cap_i = times[sol]
        for d in dates:
            f, i = times[d]
            times[d] = [None if f is None else max(f, cap_f), None if i is None else min(i, cap_i)]
    if holds and held and case == "B":
        # The sun's natural movement: before 21 June the takdir Isha never moves earlier again, after it the takdir
        # Fajr never does (where the 5-hour rule's Dhuhr-bound ends drift with the equation of time).
        first_i = max(anchors[1][0] - info["ISHA_DAYS"][0] * ONE, first)
        last_f = min(anchors[0][2] + info["FAJR_DAYS"][1] * ONE, last)
        d = first_i + ONE
        while d <= sol:
            if times[d][1] is not None and times[d - ONE][1] is not None:
                times[d][1] = max(times[d][1], times[d - ONE][1])
            d += ONE
        d = sol + ONE
        while d <= last_f:
            if times[d][0] is not None and times[d - ONE][0] is not None:
                times[d][0] = max(times[d][0], times[d - ONE][0])
            d += ONE
    return Takdir({d: tuple(v) for d, v in times.items()}, case, info)
