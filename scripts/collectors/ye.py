"""
Yemen's General Authority of Awqaf and Guidance, Sanaa (plan 9.3, 8.8, Q2): the Tebyan panel's daily JSON, the six
times of its 31 locations, one file per date. Monthly, with a backfill.

Each run asks for every date from the endpoint's first, 2026-05-21, to today + 31 days that has no file yet
(official/ye/dYYYYMMDD.json, the naming of the backfill of 2026-10-11 that fits/ye_awqaf/fit.py reads), oldest first
and at most 400 requests (about 20 minutes at 3 s each), so the first run backfills and later runs fill what is new.
One GET per date, as the site's panel asks (Accept: application/json). An answer is saved when it holds the asked
date's doc and its locations. The endpoint answers some dates empty: 10 days of June to October 2026 so far, and
likely every date it has not reached yet, since a date's doc is written during that day (`api_updated_at`). Those
are not saved and are asked again on the next run. Each manifest note records the answer's `season` field (3 until
2026-07-14, 4 from 07-15) and its `api_updated_at`. A request that fails (network, HTTP or not JSON) fails the run,
after the rest is saved.
"""

from __future__ import annotations

import datetime as dt
import json
from pathlib import Path

NAME = "ye"
CC = "YE"
CADENCE = "monthly"
DESCRIPTION = "Yemen Awqaf (Sanaa) Tebyan JSON, 31 locations: dates from 2026-05-21 to today + 31 not yet saved"

API = ("https://awqaf.gov.ye/api/method/"
       "blue_theme.blue_theme.page.tebyan_prayer_panel.tebyan_prayer_panel.get_page_data")
FIRST = dt.date(2026, 5, 21)        # the endpoint's first date
AHEAD = 31                          # days after today asked too
MAX_REQUESTS = 400
HEADERS = {"Accept": "application/json"}


def file_name(day: dt.date) -> str:
    return f"d{day:%Y%m%d}.json"


def date_url(day: dt.date) -> str:
    return f"{API}?fetch_date={day.isoformat()}"


def wanted(today: dt.date, have) -> list[dt.date]:
    """The dates from FIRST to today + AHEAD whose file is not saved yet ([have](file name) says), oldest first."""
    out, day = [], FIRST
    while day <= today + dt.timedelta(days=AHEAD):
        if not have(file_name(day)):
            out.append(day)
        day += dt.timedelta(days=1)
    return out


def answer(content: bytes, day: dt.date) -> dict | None:
    """The doc of an answer that holds [day]'s times (its fetch_date and a non-empty location list), with the number
    of locations; None for an empty answer or another date's. Raises ValueError when the answer is not JSON."""
    payload = json.loads(content.decode("utf-8"))
    message = payload.get("message") if isinstance(payload, dict) else None
    if not isinstance(message, dict):
        return None
    doc, locations = message.get("doc"), message.get("locations")
    if not isinstance(doc, dict) or not isinstance(locations, list) or not locations:
        return None
    if str(doc.get("fetch_date")) != day.isoformat():
        return None
    return {**doc, "locations": len(locations)}


def note(doc: dict) -> str:
    return (f"General Authority of Awqaf and Guidance (Sanaa), Tebyan panel JSON, {doc['locations']} locations, "
            f"season {doc.get('season')}, api_updated_at {doc.get('api_updated_at')}")


def spans(days: list[dt.date]) -> str:
    """2026-06-15..06-17, 06-28: runs of consecutive dates, for the summary line."""
    out: list[list[dt.date]] = []
    for day in days:
        if out and day - out[-1][-1] == dt.timedelta(days=1):
            out[-1].append(day)
        else:
            out.append([day])
    return ", ".join(f"{r[0]}..{r[-1]}" if len(r) > 1 else str(r[0]) for r in out)


def collect(session, today: dt.date) -> int:
    folder = Path(session.root) / CC.lower()
    days = wanted(today, lambda name: (folder / name).exists())
    asked, saved, empty, failed = days[:MAX_REQUESTS], 0, [], []
    for day in asked:
        url = date_url(day)
        try:
            content = session.fetch(url, headers=HEADERS)
            doc = answer(content, day)
        except Exception as error:  # noqa: BLE001 - reported below; the other dates go on
            failed.append(f"{day} ({type(error).__name__}: {error})")
            continue
        if doc is None:
            empty.append(day)
        elif session.save(CC, file_name(day), content, url, note(doc)):
            saved += 1
    print(f"ye: {len(asked)} dates asked, {saved} saved, {len(empty)} empty"
          + (f" ({spans(empty)})" if empty else "") + f", {len(failed)} failed"
          + (f"; {len(days) - len(asked)} left for the next run" if len(days) > len(asked) else ""))
    if failed:
        raise RuntimeError("ye: failed dates: " + "; ".join(failed))
    return saved
