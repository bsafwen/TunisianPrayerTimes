"""
Qatar Calendar House's /cal page (plan 9.3, 8.8, Q3): today's times at QCH's six Qatari places, by its cityId Doha 3,
Al Shamal 6, Mesaieed 13, Dukhan 14, Abu Samra 15 and Halul 16 (Mesaieed and Halul are given by this page only), daily
for a year; and each monthly wall calendar (PDF) the page links, once.

One plain GET, no cookie, form or script call: the page embeds the day's times in `var prayData = [...]` (13 places,
the six Qatari ones among them) and marks the day in `var calData` ("today": true, with its Gregorian day of month);
its city menu only reads prayData. The page is saved raw as official/qa/cal/cal_YYYYMMDD.html, named after the day it
marks, which is Qatar's date (UTC+3: the next day when the task runs after 21:00 UTC). That is the naming
fits/qa/fit.py reads (--cal test-data-local/official/qa/cal). A page without the six places, with a time the fit
cannot read in any row, or without the day's mark goes to cal-unparsed/ instead, so the fit never reads it, and the
run fails. The wall calendars, the PDF
links under /public/uploads/orginal/documents/ (one a month, with the day-by-day town offsets of Al Shamal, Dukhan and
Abu Samra), are fetched once each into pdfs/.
"""

from __future__ import annotations

import datetime as dt
import html as html_lib
import json
import re
import urllib.parse
from pathlib import Path

NAME = "qa"
CC = "QA"
CADENCE = "daily"
DESCRIPTION = "Qatar Calendar House /cal: today's times at its six Qatari places, and each month's wall calendar PDF"

URL = "https://www.qatarch.com/cal"
PLACES = {"3": "Doha", "6": "Al Shamal", "13": "Mesaieed", "14": "Dukhan", "15": "Abu Samra", "16": "Halul"}
TIMES = ("fajr", "shrouq", "thahr", "aser", "moghreb", "ishaa")
DOCUMENTS = "/public/uploads/orginal/documents/"     # the site's own spelling
MAX_PDFS = 2                                         # new wall calendars fetched in one run
# The fit's own pattern (fits/qa/fit.py, cal_pages): a page saved as cal_YYYYMMDD.html must match it.
PRAY_DATA = re.compile(r"var prayData\s*=\s*(\[.*?\]);", re.S)
CAL_DATA = re.compile(r"var\s+calData\s*=\s*(\{.*?\})\s*;", re.S)


def pray_data(page: str) -> dict[str, dict[str, str]] | None:
    """cityId -> its six times as printed ("H:MM"), from the page's prayData; None when the page has none, or a row
    without its cityId."""
    m = PRAY_DATA.search(page)
    if not m:
        return None
    try:
        rows = json.loads(m.group(1))
    except ValueError:
        return None
    if not isinstance(rows, list) or not all(isinstance(r, dict) and "cityId" in r for r in rows):
        return None
    return {str(r["cityId"]): {k: str(r.get(k, "")) for k in TIMES} for r in rows}


def clock(text: str) -> bool:
    """A time as "H:MM", read the way the fit reads it (fits/qa/fit.py, hm)."""
    try:
        _hours, _minutes = (int(x) for x in text.strip().split(":"))
    except ValueError:
        return False
    return True


def complete(data: dict[str, dict[str, str]] | None) -> bool:
    """The six Qatari places are there, and every row's six times read as the fit reads them (it reads all 13)."""
    return data is not None and all(p in data for p in PLACES) and all(
        clock(times[k]) for times in data.values() for k in TIMES)


def marked_day(page: str) -> int | None:
    """The Gregorian day of month that the page's calData marks as today."""
    m = CAL_DATA.search(page)
    if m:
        try:
            for month in json.loads(m.group(1)).get("days", []):
                for day in month.get("days", []):
                    if day.get("today") is True:
                        return int(day["m"])
        except (ValueError, KeyError, TypeError, AttributeError):
            pass
    m = re.search(r'"m"\s*:\s*"(\d{1,2})"\s*,\s*"today"\s*:\s*true', page)
    return int(m.group(1)) if m else None


def page_date(page: str, today: dt.date) -> dt.date | None:
    """The day the page shows: the date within a day of [today] whose day of month the page marks (three days in a
    row never share a day of month). None when the page marks no day, or one further away."""
    day = marked_day(page)
    for delta in (0, 1, -1):
        date = today + dt.timedelta(days=delta)
        if day is not None and date.day == day:
            return date
    return None


def quote_url(url: str) -> str:
    """[url] with its path percent-encoded (the site links its PDFs with spaces in the name)."""
    p = urllib.parse.urlsplit(url)
    path = urllib.parse.quote(urllib.parse.unquote(p.path), safe="/()!$&'*+,;=:@-._~")
    return urllib.parse.urlunsplit((p.scheme, p.netloc, path, p.query, ""))


def wall_calendars(page: str, base: str = URL) -> list[tuple[str, str]]:
    """(url, title) of each wall calendar the page links: the PDFs under /public/uploads/orginal/documents/ on
    qatarch.com (the Ramadan imsakiya, under emsakia/, and the share links are left out), in page order."""
    out: list[tuple[str, str]] = []
    for tag in re.findall(r"<a\b[^>]*>", page, re.I | re.S):
        href = re.search(r'href\s*=\s*"([^"]+)"', tag)
        if not href:
            continue
        url = quote_url(urllib.parse.urljoin(base, html_lib.unescape(href.group(1)).strip()))
        parts = urllib.parse.urlsplit(url)
        path = urllib.parse.unquote(parts.path)
        host = (parts.hostname or "").lower()
        if not (host == "qatarch.com" or host.endswith(".qatarch.com")) or not path.startswith(DOCUMENTS):
            continue
        if not path.lower().endswith(".pdf") or any(url == u for u, _ in out):
            continue
        title = re.search(r'download\s*=\s*"([^"]*)"', tag)
        out.append((url, " ".join(html_lib.unescape(title.group(1)).split()) if title else ""))
    return out


def pdf_name(url: str) -> str:
    """pdfs/<the PDF's file name in letters, digits and underscores>.pdf."""
    base = urllib.parse.unquote(urllib.parse.urlsplit(url).path).rsplit("/", 1)[-1]
    stem = re.sub(r"[^A-Za-z0-9]+", "_", re.sub(r"(?i)\.pdf$", "", base)).strip("_")
    return f"pdfs/{stem or 'calendar'}.pdf"


def collect(session, today: dt.date) -> int:
    page = session.fetch(URL)
    text = page.decode("utf-8", errors="replace")
    data, day = pray_data(text), page_date(text, today)
    count, problems = 0, []
    if complete(data) and day is not None:
        doha = " ".join(data["3"][k] for k in TIMES)
        note = (f"Qatar Calendar House /cal: prayData of {day} (the day the page marks), {len(data)} places, the six "
                f"Qatari ones complete; Doha {doha}")
        # once: each fetch carries a new csrf token, and the fit wants one page per day.
        if session.save(CC, f"cal/cal_{day:%Y%m%d}.html", page, URL, note, once=True):
            count += 1
    else:
        why = ("no prayData with the six Qatari places and readable times" if not complete(data)
               else f"the page marks day {marked_day(text)}, not within a day of {today}")
        session.save(CC, f"cal-unparsed/cal_{today:%Y%m%d}.html", page, URL,
                     f"Qatar Calendar House /cal, unread: {why}")
        problems.append(f"/cal: {why}")
    fetched = 0
    for url, title in wall_calendars(text):
        name = pdf_name(url)
        if fetched >= MAX_PDFS or (Path(session.root) / CC.lower() / name).exists():
            continue
        fetched += 1
        try:
            pdf = session.fetch(url)
        except Exception as error:  # noqa: BLE001 - reported below; the page itself is saved
            problems.append(f"{name}: {type(error).__name__}: {error}")
            continue
        if not pdf.startswith(b"%PDF"):
            problems.append(f"{name}: not a PDF ({len(pdf)} bytes)")
            continue
        if session.save(CC, name, pdf, url, f"Qatar Calendar House wall calendar linked from /cal: {title}"):
            count += 1
    if problems:
        raise RuntimeError("qa: " + "; ".join(problems))
    return count
