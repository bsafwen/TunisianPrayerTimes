"""
North Macedonia: the Islamic Religious Community's (BFI) times for Skopje, from bfi.mk (plan 9.3, 8.8).

The BFI publishes no table online. Its Albanian home page shows only the current day's Skopje times, in a table headed
«Namazi | Koha (Shkup)»: Sabahu, Dreka, Iqindia, Akshami and Jacia on a 12-hour clock (5:57 AM on 2026-10-16), with
no date, dawn or sunrise. The times are in the page's HTML, not drawn by a script, and the site's REST index offers
no prayer route (read 2026-10-16), so the page itself is the smallest resource that holds them. It is saved whole once
a day, unparsed, as <yyyy>/<mm>/bfi_<yyyy-mm-dd>.html: the manifest's fetch time dates each copy, and its note repeats
the five times read, so a missed or stale day shows at a glance.

The same page carries the theme's own «Kohët e Namazit» widget («Koha:» labels) with other times (Sabahu 5:58 AM on
2026-10-16): the fit reads the «Koha (Shkup)» block only. A page without that block is still kept, since the day cannot
be fetched again, and the run fails so the gap is seen.
"""

from __future__ import annotations

import codecs
import datetime as dt
import re
from html.parser import HTMLParser

NAME = "mk"
CC = "mk"
CADENCE = "daily"
DESCRIPTION = "BFI, bfi.mk home page: today's Skopje times (the undated 'Koha (Shkup)' block; the fetch time dates it)"

URL = "https://bfi.mk/"
PRAYERS = ("Sabahu", "Dreka", "Iqindia", "Akshami", "Jacia")
WINDOW = 600  # characters of page text after «Koha (Shkup)» that hold the five rows

_BLOCK = re.compile(r"Koha\s*\(\s*Shkup\s*\)")
_TIME = r"(\d{1,2}[:.]\d{2}(?:\s*[AaPp]\.?\s*[Mm]\.?)?)"
_CHARSET = re.compile(rb"""<meta[^>]*charset\s*=\s*["']?\s*([A-Za-z0-9_.:-]+)""", re.IGNORECASE)


def file_name(today: dt.date) -> str:
    return f"{today:%Y/%m}/bfi_{today.isoformat()}.html"


def page_charset(content: bytes) -> str:
    """The charset the page's <meta> declares; UTF-8 when it declares none Python knows."""
    match = _CHARSET.search(content[:8192])
    if match:
        try:
            return codecs.lookup(match.group(1).decode("ascii")).name
        except LookupError:
            pass
    return "utf-8"


class _Text(HTMLParser):
    """The page's text nodes in order, scripts and styles left out."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self._hidden = 0

    def handle_starttag(self, tag, attrs):
        if tag in ("script", "style"):
            self._hidden += 1

    def handle_endtag(self, tag):
        if tag in ("script", "style") and self._hidden:
            self._hidden -= 1

    def handle_data(self, data):
        if not self._hidden and data.strip():
            self.parts.append(" ".join(data.split()))


def page_text(content: bytes) -> str:
    """The page's visible text on one line, single-spaced."""
    parser = _Text()
    parser.feed(content.decode(page_charset(content), errors="replace"))
    parser.close()
    return " ".join(parser.parts)


def block_times(text: str) -> dict[str, str] | None:
    """
    The five times of the «Koha (Shkup)» block as written ("5:57 AM"), each the first that directly follows its
    prayer's name after the heading; None when the block is not there whole.
    """
    heading = _BLOCK.search(text)
    if heading is None:
        return None
    window = text[heading.end():heading.end() + WINDOW]
    times, at = {}, 0
    for prayer in PRAYERS:
        match = re.compile(prayer + r"\s*:?\s*" + _TIME).search(window, at)
        if match is None:
            return None
        times[prayer] = " ".join(match.group(1).split())
        at = match.end()
    return times


def collect(session, today: dt.date) -> int:
    content = session.fetch(URL)
    times = block_times(page_text(content))
    if times:
        note = "Koha (Shkup) as shown at fetch time: " + ", ".join(f"{p} {t}" for p, t in times.items())
    else:
        note = "NO 'Koha (Shkup)' block with the five times: the page changed; kept, the collector needs a look"
    saved = session.save(CC, file_name(today), content, URL, note, once=True)  # one copy of the day's page
    if times is None:
        raise RuntimeError(f"mk: no 'Koha (Shkup)' block with the five times on {URL}; the page was kept anyway")
    return 1 if saved else 0
