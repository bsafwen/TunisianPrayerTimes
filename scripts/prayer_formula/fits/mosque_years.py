"""
Plan 9.5 without waiting a collector's year (owner, 2026-10-17): a mosque of the owner's dataset that follows an
official table on every collected day stands in for that table's whole year.

The owner's dataset is supporting evidence, never the truth (owner, 2026-10-11): a mosque counts only where it
reproduces the official table on the days collected from the authority itself (FOLLOW_EXACT of all their minutes,
none more than 1 min off, at least MIN_OVERLAP days), its other days are scored as a held-out body of their own
(`source` "mosque_follower", the mosque's id in the record's notes), and the official days stay the fit's. The dataset
holds each mosque's calendar of 2026 by month and day (read 2026-10-10); it is read here, never copied (A7), and
mawaqit.net is never fetched.

- `mosques(cc)`: a country's mosques with their calendars, read-only.
- `follows(...)`: whether a calendar reproduces an official table on its days.
- `score_calendar(...)`: a follower's other days scored, a day printed on a wrong clock (all six times an hour off)
  left out as a quirk day.
- `merge_held_out(fit, score, sample)`: a zone's FitStats with a further held-out body added.
"""

from __future__ import annotations

import datetime
import math
import sqlite3
from dataclasses import dataclass, field
from pathlib import Path

import common as C

DATASET = C.LOCAL_DATA.parent / "mosque-dataset" / "mosques.db"
YEAR = 2026                    # the calendars' year
FOLLOW_EXACT = 0.98            # a follower matches at least this share of the official minutes exactly
MIN_OVERLAP = 20               # and on at least this many official days
COLUMNS = ("fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha")


@dataclass
class Mosque:
    key: int
    id: str
    name: str
    city: str
    lat: float
    lng: float
    timezone: str
    calendar: dict = field(default_factory=dict)      # date -> {event: "HH:MM"}


def mosques(cc: str) -> list[Mosque]:
    """The dataset's mosques of country [cc] with their calendars of YEAR (an invalid date such as 29 February skipped)."""
    db = sqlite3.connect(f"file:{DATASET.as_posix()}?mode=ro", uri=True)
    out = {}
    for key, mid, name, city, lat, lng, tz in db.execute(
            "select mosque_key, id, name, city, latitude, longitude, timezone from mosques where countryCode = ?", (cc,)):
        out[key] = Mosque(key, mid, name, city or "", lat, lng, tz or "")
    for row in db.execute(
            "select d.mosque_key, d.month, d.day, " + ", ".join(f"d.{c}" for c in COLUMNS) + " from calendar_days d "
            "join mosques m on m.mosque_key = d.mosque_key where m.countryCode = ?", (cc,)):
        try:
            day = datetime.date(YEAR, row[1], row[2])
        except ValueError:
            continue
        out[row[0]].calendar[day] = dict(zip(C.EVENTS, row[3:]))
    db.close()
    return [m for m in out.values() if m.calendar]


def km(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    a = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lng2 - lng1) / 2) ** 2
    return 2 * 6371.0 * math.asin(math.sqrt(a))


def epoch_minute(zone_id: str, day: datetime.date, hhmm: str, event: str) -> int | None:
    """A calendar's clock time as UTC epoch minutes (an Isha before noon belongs to the night after)."""
    try:
        h, m = (int(x) for x in hhmm.strip()[:5].split(":"))
    except ValueError:
        return None
    on = day + datetime.timedelta(days=1) if event == "ISHA" and h < 12 else day
    return C.local_to_epoch(zone_id, on, f"{h:02d}:{m:02d}") // 60


@dataclass
class Follow:
    days: int
    exact: int
    total: int
    worst: int

    @property
    def share(self) -> float:
        return self.exact / self.total if self.total else 0.0

    @property
    def follows(self) -> bool:
        return self.days >= MIN_OVERLAP and self.share >= FOLLOW_EXACT and self.worst <= 1


def follows(mosque: Mosque, zone_id: str, official: dict) -> Follow:
    """The mosque's calendar against an official table, {date: {event: UTC epoch minute}}, on the table's days."""
    days = exact = total = worst = 0
    for day, published in official.items():
        times = mosque.calendar.get(day) if day.year == YEAR else None     # the calendars are of YEAR only
        if times is None:
            continue
        days += 1
        for e in C.EVENTS:
            mine = epoch_minute(zone_id, day, times[e], e)
            if mine is None or published.get(e) is None:
                continue
            total += 1
            diff = abs(mine - published[e])
            exact += diff == 0
            worst = max(worst, diff)
    return Follow(days, exact, total, worst)


def clock_quirk(printed: dict, computed: dict) -> bool:
    """A day printed on a wrong clock, not with wrong times (plan 8.3's quirk days): each of its times is either within
    1 min of the computed one or within 2 min of a whole number of hours off it, and at least one is hours off. The
    owner's Moroccan calendars print UTC−1 from 15 February to 13 March 2026, an hour behind the legal UTC+0 of
    Ramadan's suspension, a few days two hours off, and the mornings of the change days on the other clock; before and
    after they agree with Habous's minutes."""
    diffs = [computed[e] - printed[e] for e in C.EVENTS if printed.get(e) is not None and computed.get(e) is not None]
    hours = [d for d in diffs if abs(d) > 1]
    return bool(hours) and all(abs(d) >= 58 and abs(abs(d) - 60 * round(abs(d) / 60)) <= 2 for d in hours)


def score_calendar(compute_day, calendar: dict, score: C.Score) -> int:
    """Scores {date: {event: UTC epoch minute}} with compute_day(date) -> {event: UTC epoch minute}; returns the
    quirk days left out (clock_quirk)."""
    quirks = 0
    for day in sorted(calendar):
        computed = compute_day(day)
        if clock_quirk(calendar[day], computed):
            quirks += 1
            continue
        for e in C.EVENTS:
            if calendar[day].get(e) is not None and computed.get(e) is not None:
                score.add(e, day, computed[e], calendar[day][e])
    return quirks


def merge_held_out(fit: dict, score: C.Score, sample: dict, quirk_days: int = 0) -> dict:
    """A zone's FitStats with [score] added as a further held-out body [sample] (shares by count, months added)."""
    merged = dict(fit)
    merged["heldOut"] = list(fit.get("heldOut", [])) + [sample]
    old_days = {e: sum(fit.get("daysPerMonth", {}).get(e, [])) for e in C.EVENTS}
    merged["exact"] = {e: round((fit["exact"].get(e, 0.0) * old_days[e] + score.exact[e]) / max(1, old_days[e] + score.total[e]), 4)
                       for e in C.EVENTS}
    merged["within1"] = {e: round((fit["within1"].get(e, 0.0) * old_days[e] + score.within1[e]) / max(1, old_days[e] + score.total[e]), 4)
                         for e in C.EVENTS}
    merged["daysPerMonth"] = {e: [a + b for a, b in zip(fit.get("daysPerMonth", {}).get(e, [0] * 12), score.months[e])]
                              for e in C.EVENTS}
    merged["maxDiffMin"] = max(fit.get("maxDiffMin", 0), score.worst)
    merged["quirkDays"] = fit.get("quirkDays", 0) + quirk_days
    return merged


def passes_tier_a(fit: dict) -> bool:
    """FitStats.passesTierA for a method without Ramadan rules (plan 9.5)."""
    return bool(fit.get("heldOut")) and all(
        fit["exact"].get(e, 0) >= 0.97 and fit["within1"].get(e, 0) >= 1.0
        and len(fit["daysPerMonth"].get(e, [])) == 12 and all(d >= 7 for d in fit["daysPerMonth"][e])
        for e in C.EVENTS)
