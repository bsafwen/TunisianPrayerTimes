"""
Iraq's Sunni Endowment (plan 9.3, 8.3, Q8): the table of Baghdad and its suburbs from the API behind the dws.gov.iq
widget «مواقيت الصلاة الرسمية لليوم», every day of the year. Yearly, from January 2027: the 2027 table is the held-out
year (a repeat of 2026 by month and day confirms the reference year 2021); 2026 is in research-2026-10-08/IQ.

One GET per date (Accept: application/json), each answer saved raw as official/iq/<year>/dws_YYYYMMDD.json; a later
run asks only the dates without a file. The API serves one year's table: for a date outside it, it answers the
nearest row of that year flagged `is_closest`, and such rows, like a row of another date, are dropped (not saved).
On the year's first run the 15th of each month is asked first: when none of them comes back as the year's own row,
the year is not served yet, so the run stops after those 12 requests and fails, to be run again later. Failed
requests are asked again one date at a time, twice at most; dates still failing fail the run, after the rest is
saved. When the very first request fails, the run reads the site's script bundle (assets/index-*.js, where the
research found the API's address) and switches to the address it names, if another.
"""

from __future__ import annotations

import datetime as dt
import json
import re
import urllib.parse
from pathlib import Path

NAME = "iq"
CC = "IQ"
CADENCE = "yearly"
DESCRIPTION = "Iraq Sunni Endowment API (dws.gov.iq): Baghdad's table, every day of the year, from January 2027"

API = "https://eldwanapp.jeeteak.com/api/prayer-times"
SITE = "https://dws.gov.iq/"
FIRST_YEAR = 2027
RETRIES = 2
MAX_REQUESTS = 400
HEADERS = {"Accept": "application/json"}
TIMES = ("fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha")
NOTE = "Sunni Endowment API (dws.gov.iq widget): Baghdad and suburbs, the year's own row (is_closest false)"


def year_days(year: int) -> list[dt.date]:
    day, out = dt.date(year, 1, 1), []
    while day.year == year:
        out.append(day)
        day += dt.timedelta(days=1)
    return out


def ask_order(days: list[dt.date]) -> list[dt.date]:
    """The 15th of each month first (the probe), then the other dates in order."""
    return [d for d in days if d.day == 15] + [d for d in days if d.day != 15]


def file_name(day: dt.date) -> str:
    return f"{day.year}/dws_{day:%Y%m%d}.json"


def date_url(endpoint: str, day: dt.date) -> str:
    return f"{endpoint}?date={day.isoformat()}"


def own_row(content: bytes, day: dt.date) -> bool:
    """True for [day]'s own row, False for a row flagged is_closest or of another date. Raises ValueError when the
    answer is not JSON or holds no row of times."""
    payload = json.loads(content.decode("utf-8"))
    row = payload.get("data") if isinstance(payload, dict) else None
    if not isinstance(row, dict) or not all(k in row for k in TIMES):
        raise ValueError("no prayer-times row in the answer")
    return not row.get("is_closest") and str(row.get("date")) == day.isoformat()


def script_urls(page: str, base: str = SITE) -> list[str]:
    """The page's scripts under assets/ (a Vite build's index-<hash>.js first), absolute."""
    found = [urllib.parse.urljoin(base, src) for src in re.findall(r'<script\b[^>]*\bsrc="([^"]+\.js)"', page)
             if "assets/" in src]
    return sorted(dict.fromkeys(found), key=lambda u: not u.rsplit("/", 1)[-1].startswith("index-"))


def endpoints_in_script(js: str) -> list[str]:
    """The prayer-times addresses a bundle names: a URL ending in /prayer-times, or an API base (ending in /api, or
    /api/v<n>) plus /prayer-times when the bundle uses that path."""
    out = []
    for url in re.findall(r"https?://[A-Za-z0-9.-]+(?::\d+)?(?:/[A-Za-z0-9._~%-]+)*/?", js):
        url = url.rstrip("/")
        if url.endswith("/prayer-times"):
            candidate = url
        elif re.search(r"/api(?:/v\d+)?$", url) and "prayer-times" in js:
            candidate = url + "/prayer-times"
        else:
            continue
        if candidate not in out:
            out.append(candidate)
    return out


def discover(session) -> tuple[str | None, int]:
    """The address the site's bundle names (API itself when it still names it), and the requests spent (1 or 2)."""
    try:
        page = session.fetch(SITE).decode("utf-8", errors="replace")
    except Exception:  # noqa: BLE001 - the site may be down too; the run goes on with API
        return None, 1
    scripts = script_urls(page)
    if not scripts:
        return None, 1
    try:
        found = endpoints_in_script(session.fetch(scripts[0]).decode("utf-8", errors="replace"))
    except Exception:  # noqa: BLE001
        return None, 2
    return (API if API in found else (found[0] if found else None)), 2


def collect(session, today: dt.date) -> int:
    year = today.year
    if year < FIRST_YEAR:
        print(f"iq: collected from January {FIRST_YEAR} (2026 is in research-2026-10-08/IQ); nothing asked")
        return 0
    folder = Path(session.root) / CC.lower()
    days = [d for d in ask_order(year_days(year)) if not (folder / file_name(d)).exists()]
    # The probe: the 15ths still to ask, unless an earlier run already saved rows of this year (then it is served).
    probes = 0 if len(days) < len(year_days(year)) else sum(d.day == 15 for d in days)
    endpoint, requests, saved, own, dropped, failed = API, 0, 0, 0, [], []

    def ask(day: dt.date) -> bool:
        """Asks for [day] and saves its own row; False when the row is dropped."""
        nonlocal requests, saved, own
        requests += 1
        url = date_url(endpoint, day)
        content = session.fetch(url, headers=HEADERS)
        if not own_row(content, day):
            return False
        own += 1
        if session.save(CC, file_name(day), content, url, NOTE):
            saved += 1
        return True

    for i, day in enumerate(days):
        if requests >= MAX_REQUESTS:
            failed.append(day)
            continue
        try:
            if not ask(day):
                dropped.append(day)
        except Exception:  # noqa: BLE001 - asked again below
            failed.append(day)
            if i == 0:
                found, spent = discover(session)
                requests += spent
                endpoint = found or endpoint
        if probes and i == probes - 1 and not own:
            if dropped:
                raise RuntimeError(f"iq: {year} is not served yet: the 15th of each month gave rows flagged is_closest "
                                   "or of other dates; run again later")
            raise RuntimeError(f"iq: no usable answer for the 15th of any month of {year} from {endpoint}; run again "
                               "later")
    for _ in range(RETRIES):
        again, failed = failed, []
        for day in again:
            if requests >= MAX_REQUESTS:
                failed.append(day)
                continue
            try:
                if not ask(day):
                    dropped.append(day)
            except Exception:  # noqa: BLE001
                failed.append(day)
    print(f"iq: {year}: {len(days)} dates asked, {saved} saved, {len(dropped)} dropped (is_closest or another date), "
          f"{len(failed)} failed, {requests} requests" + ("" if endpoint == API else f"; API now at {endpoint}"))
    if failed:
        raise RuntimeError(f"iq: {len(failed)} dates failed after {RETRIES} retries or were left by the "
                           f"{MAX_REQUESTS}-request cap: " + ", ".join(str(d) for d in sorted(failed)))
    return saved
