"""
Slovenia: the Islamic Community in Slovenia's vaktija.si feed (plan 9.3, 8.8).

vaktija.si, linked from islamska-skupnost.si, is a page drawn by a script from one JSON feed,
https://www.vaktija.si/api/v2/feed (the address the research of 2026-10-11 saved, still served on 2026-10-16; the bare
vaktija.si's certificate names only www.vaktija.si). It holds Ljubljana's times for the current month and the next two
("times": year, month, name and one entry per day with fajr, sunrise, dhuhr, asr, maghrib and isha as HH:MM), the six
places with their fixed offsets in minutes ("cities": Celje, Jesenice, Koper, Ljubljana at 0, Maribor, Novo mesto),
the year's Hijri calendar and its events, and a "serverTime" that makes every copy's bytes differ.

The feed is saved once a month as feed_<yyyy-mm>.json, unparsed; a month already saved is not fetched again. It is
checked first (whole months of the six times, Ljubljana among the places) and the months it covers go in the
manifest's note. A feed that fails the check is not saved and the run fails; the next run tries again, and the three
months each copy covers leave two months of slack. Keep the monthly task through at least August 2027, so the summer
form (Fajr = sunrise - 105 and Isha no later than Maghrib + 90, 24 April to 23 May on the Ramadan 2020 sheet) is seen
in the Community's own tables.
"""

from __future__ import annotations

import calendar
import datetime as dt
import json
import re
from pathlib import Path

NAME = "si"
CC = "si"
CADENCE = "monthly"
DESCRIPTION = ("Islamic Community in Slovenia, vaktija.si JSON feed: Ljubljana's times for this month and the next two, "
               "with the five places' offsets")

URL = "https://www.vaktija.si/api/v2/feed"
PRAYERS = ("fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha")
REFERENCE_PLACE = "Ljubljana"

_TIME = re.compile(r"\d{1,2}:\d{2}")


def file_name(today: dt.date) -> str:
    return f"feed_{today:%Y-%m}.json"


def feed_note(content: bytes) -> str:
    """What the feed covers, for the manifest's note; ValueError when it is not the feed (an error page, a new layout)."""
    try:
        feed = json.loads(content.decode("utf-8"))
    except ValueError as error:  # UnicodeDecodeError and JSONDecodeError are both ValueErrors
        raise ValueError(f"si: the feed is not JSON ({error})") from error
    months = feed.get("times") if isinstance(feed, dict) else None
    if not isinstance(months, list) or not months:
        raise ValueError("si: the feed has no 'times'")
    covered = []
    for block in months:
        block = block if isinstance(block, dict) else {}
        year, month, days = block.get("year"), block.get("month"), block.get("days")
        if not (isinstance(year, int) and isinstance(month, int) and 1 <= month <= 12 and isinstance(days, list)):
            raise ValueError(f"si: a 'times' entry is not a month: {str(block)[:80]}")
        if not all(isinstance(day, dict) for day in days):
            raise ValueError(f"si: {year}-{month:02d} has days that are not entries")
        numbers = [day.get("day") for day in days]
        times_ok = all(_TIME.fullmatch(str(day.get(p, ""))) for day in days for p in PRAYERS)
        if numbers != list(range(1, calendar.monthrange(year, month)[1] + 1)) or not times_ok:
            raise ValueError(f"si: {year}-{month:02d} is not a whole month of the six times")
        covered.append(f"{year}-{month:02d}")
    places = [c.get("name") for c in feed.get("cities") or [] if isinstance(c, dict) and isinstance(c.get("name"), str)]
    if REFERENCE_PLACE not in places:
        raise ValueError(f"si: {REFERENCE_PLACE} is not among the feed's places")
    others = [p for p in places if p != REFERENCE_PLACE]
    return f"{REFERENCE_PLACE} {', '.join(covered)}; offsets of {len(others)} places: {', '.join(others)}"


def collect(session, today: dt.date) -> int:
    name = file_name(today)
    if (Path(session.root) / CC / name).exists():
        return 0
    content = session.fetch(URL, headers={"Accept": "application/json"})
    note = feed_note(content)
    return 1 if session.save(CC, name, content, URL, note) else 0
