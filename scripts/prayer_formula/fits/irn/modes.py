"""
Two sun evaluations (E4) that IRN's tables use and the engine does not have yet, emulated for the fit only (proposed
in fits/irn/README-part.md; the reference engine and the Kotlin engine are not changed):

- DAILY_LOCAL_NOON: the sun (declination, equation of time, distance) taken once a day at 12:00 local civil time of the
  date, summer time included (Europe/Oslo: 11:00 UT in winter, 10:00 UT in summer). IRN's sunrise, sunset, transit and
  Asr columns.
- EVENT_DECLINATION: the declination at the event instant (iterated as EVENT_INSTANT does), the equation of time held at
  12:00 local civil time. IRN's twilight columns (16 deg, 15 deg, 18 deg).

A method JSON names them with the zone after "@" ("DAILY_LOCAL_NOON@Europe/Oslo"), so the patched SunFunction finds
each date's own offset; install() patches the reference module of this process only. It also installs, behind the
SOLSTICE_FIX switch, the held Fajr of 21 June as the tables have it (H7: the spring clock through day 172).
"""

from __future__ import annotations

import datetime
import math
from zoneinfo import ZoneInfo

import common

ref = common.ref
_BASE = ref.SunFunction
_OFFSETS: dict = {}


def noon_jd(jd0: float, zone: str) -> float:
    """The Julian day of 12:00 local civil time on the cycle's date."""
    day = ref.EPOCH_DATE + datetime.timedelta(days=round(jd0 + 0.5 - ref.UNIX_EPOCH_JD))
    key = (zone, day)
    if key not in _OFFSETS:
        _OFFSETS[key] = datetime.datetime(day.year, day.month, day.day, 12, tzinfo=ZoneInfo(zone)).utcoffset().total_seconds()
    return jd0 + (12 * 3600 - _OFFSETS[key]) / 86400


class SunFunction(_BASE):
    def __init__(self, model, mode: str, jd0: float, noon_jd: float | None = None):
        self._eot = None
        self._plain = "@" not in mode
        if self._plain:
            # The reference has the two modes since 2026-10-16 (README S4); "@zone" names stay for this fit's history.
            super().__init__(model, mode, jd0, noon_jd)
            return
        kind, zone = mode.split("@", 1)
        noon = model(noon_jd(jd0, zone))
        self.model = model
        if kind == "DAILY_LOCAL_NOON":
            self.fixed = noon
        elif kind == "EVENT_DECLINATION":
            self.fixed = None
            self._eot = noon[1]
        else:
            raise ValueError(f"unknown emulated evaluation {mode}")

    def __call__(self, jd: float):
        if self._plain:
            return super().__call__(jd)
        if self.fixed is not None:
            return self.fixed
        value = self.model(jd)
        return value if self._eot is None else (value[0], self._eot, value[2])


def install() -> None:
    ref.SunFunction = SunFunction
    _install_solstice_fix()


# H7's held Fajr on 21 June: the tables keep the spring clock (that of day first - 1) through day 172 and take the
# autumn clock (day last + 1) from day 173; the engine takes the autumn clock from day 172. Emulated only while
# SOLSTICE_FIX is True (fit.py sets it for the proposal's scores and clears it for the current engine's).
SOLSTICE_FIX = False
_ORIGINAL_IRN = None


def _install_solstice_fix() -> None:
    global _ORIGINAL_IRN
    if _ORIGINAL_IRN is not None:
        return
    _ORIGINAL_IRN = ref.CaseEngine.irn

    def irn(self, site, day, name, t):
        if SOLSTICE_FIX and name == "FAJR" and abs(site.point[0]) < 63.56 and ref.common_day_number(day) == 172:
            days = self.switch_days(site, day, name)
            if days is not None and days[0] <= 172 <= days[1]:
                fajr = self.angle_on(site, ref.date_of_day_number(days[0] - 1, day.year), "FAJR")
                local = fajr + self.utc_offset
                clock = local - math.floor(local / 86400) * 86400
                return min(self.clock_instant(day, clock), self.day_steps(site, day).sunrise - ref.HOUR)
        return _ORIGINAL_IRN(self, site, day, name, t)
    ref.CaseEngine.irn = irn


def local_noon_sun(model, cyc, zone: str):
    """The DAILY_LOCAL_NOON sun of a cycle, for the fit's own fast code."""
    v = model(noon_jd(cyc.jd0, zone))
    return lambda jd: v


def event_declination_sun(model, cyc, zone: str):
    """The EVENT_DECLINATION sun of a cycle, for the fit's own fast code."""
    eot = model(noon_jd(cyc.jd0, zone))[1]
    return lambda jd: (lambda v: (v[0], eot, v[2]))(model(jd))
