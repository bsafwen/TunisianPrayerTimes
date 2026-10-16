"""
Egypt (plan 9.3, 8.8, Q9): the Egyptian General Survey Authority's all-cities prayer page, a plain GET of
https://www.esa.gov.eg/praytimes.aspx that shows today's times for every city of EGSA's all-cities table (78 on
2026-10-08). Daily, for fits/eg_egsa.

The page is an ASP.NET form. Its GridView (placeholder1_GridView1) has one row per city: name, Gregorian date
(yyyy-mm-dd), Hijri date, then Fajr, sunrise, Dhuhr, Asr, Maghrib and Isha on a 12-hour clock with ص or م. A page whose
rows fits/eg_egsa/fit.py can read (each date ISO, each time as its clock() reads it) is saved raw as
praytimes/praytimes_<date>.html, the date its rows print. That flat folder is what the fit reads:
--wayback test-data-local/official/eg/praytimes (a folder of copies of this page). A page without such rows is asked for
once more; if it still has none it is kept apart as praytimes-unparsed/praytimes_<run date>.html, where the fit never
looks, and the run fails. The day cannot be asked for later.

Not collected, since each needs a form postback and so the owner's go-ahead: the monthly view (monthlymwaket.aspx) and
the four cities of the page's city menu that the all-cities table leaves out (Menouf, Diyarb Negm, Abu Tisht, Samalut).
"""

from __future__ import annotations

import datetime as dt
import html
import re
from collections import Counter

NAME = "eg"
CC = "EG"
CADENCE = "daily"
DESCRIPTION = "Egypt EGSA (esa.gov.eg praytimes.aspx): today's times for every city of its all-cities table (GET)"

URL = "https://www.esa.gov.eg/praytimes.aspx"
_GRID = re.compile(r'(?s)id="placeholder1_GridView1".*?</table>')
_CLOCK = re.compile(r"\d{1,2}:\d{1,2}\s*(?:ص|م)")     # fits/eg_egsa clock(): unpadded minutes, then ص or م


def ascii_text(text: str) -> str:
    """[text] safe to print on any console (non-ASCII escaped)."""
    return text.encode("ascii", "backslashreplace").decode("ascii")


def reason(error: Exception) -> str:
    return ascii_text(f"{type(error).__name__}: {error}")[:300]


def table_rows(page: str) -> list[tuple[str, dt.date]]:
    """(city, date) of each row of the page's all-cities GridView, as fits/eg_egsa/fit.py reads it: the rows of 9 cells.
    Raises ValueError for such a row that the fit could not read (a date that is not ISO, or a time it cannot parse)."""
    grid = _GRID.search(page)
    rows = []
    for tr in re.findall(r"(?s)<tr.*?</tr>", grid.group(0)) if grid else []:
        cells = [html.unescape(re.sub(r"<[^>]+>", "", c)).strip() for c in re.findall(r"(?s)<td.*?</td>", tr)]
        if len(cells) != 9:
            continue
        try:
            day = dt.date.fromisoformat(cells[1])
        except ValueError:
            raise ValueError(ascii_text(f"a row dated {cells[1]!r}")) from None
        if not all(_CLOCK.fullmatch(c) for c in cells[3:]):
            raise ValueError(ascii_text(f"a row with the times {cells[3:]}"))
        rows.append((cells[0], day))
    return rows


def page_name(day: dt.date) -> str:
    return f"praytimes/praytimes_{day.isoformat()}.html"


def unparsed_name(today: dt.date) -> str:
    return f"praytimes-unparsed/praytimes_{today.isoformat()}.html"


def collect(session, today: dt.date) -> int:
    content, rows, problem = None, [], ""
    for _ in range(2):
        try:
            content = session.fetch(URL)
        except Exception as error:  # noqa: BLE001 - asked once more
            problem = reason(error)
            continue
        try:
            rows = table_rows(content.decode("utf-8", errors="replace"))
        except ValueError as error:
            problem = str(error)
            continue
        if rows:
            break
        problem = "no row in the all-cities table"
    if not rows:
        if content is not None:
            session.save(CC, unparsed_name(today), content, f"GET {URL}", f"EGSA all-cities page, unread: {problem}")
        raise RuntimeError(f"eg: {URL}: {problem}" + ("; the page was kept in praytimes-unparsed/" if content else ""))

    dates = Counter(day for _, day in rows)
    day = dates.most_common(1)[0][0]
    note = f"EGSA all-cities table of {day}: {len(rows)} cities"
    if len(dates) > 1:
        note += "; rows also dated " + ", ".join(str(d) for d in sorted(dates) if d != day)
    if abs((day - today).days) > 1:
        print(f"eg: the page shows {day}, {abs((day - today).days)} days from {today}")
    # once: an ASP.NET page's view state changes on each fetch, and the fit reads one page per day.
    path = session.save(CC, page_name(day), content, f"GET {URL}", note, once=True)
    print(f"eg: {day}, {len(rows)} cities" + ("" if path else ", already saved"))
    return 1 if path else 0
