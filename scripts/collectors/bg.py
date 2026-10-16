"""
Bulgaria: the Chief Mufti's Office's «Времена за намаз» on grandmufti.bg (plan 9.3, 8.8).

One page shows one city's table for one month: days 1 to 31 with Зора, Изгрев, Обяд, Следобяд, Залез and Нощ (the change
of clock shows as an hour's jump), chosen in a «Град» list of 48 cities (Айтос to Ямбол) and a «Месец» list of the twelve
months, with no submit button and no city or month named on the table. The default page shows the current month of a
city it does not name (Sofia, by the fit of 2026-10-11). It was read on 2026-10-16 through WebFetch, whose text drops
the lists' markup, so how a choice reaches the server was not seen. Each run therefore reads the form that holds the
two lists from the default page (action, method, field names, option values) and sends it as a browser would, once per
city, with the current month.

A city's page is saved only if it holds a table of the month's length and marks no other month as chosen, and only once
some city's table has differed from the default page's: when two cities give the default page's table before any gives
another, the choice is being ignored, nothing is saved but the default page, and the run fails. Pages are saved as
<yyyy>/<mm>/<city>_<yyyy-mm>.html, the city in Bulgaria's official transliteration (aytos to yambol); a city already
saved for the month is not fetched again, so a run that stopped half-way resumes there.
"""

from __future__ import annotations

import calendar
import codecs
import datetime as dt
import re
import urllib.parse
from dataclasses import dataclass
from html.parser import HTMLParser
from pathlib import Path

NAME = "bg"
CC = "bg"
CADENCE = "monthly"
DESCRIPTION = ("Chief Mufti, grandmufti.bg 'Vremena za namaz': this month's table of each of the 48 cities "
               "(unverified: how a city is chosen is read from the page's form at run time)")

PAGE_URL = "https://www.grandmufti.bg/bg/home/vremena-za-namaz.html"
REFERENCE_CITY = "София"  # the «Град» list is the named list that holds it among at least MIN_CITIES names
MIN_CITIES = 40  # 48 on 2026-10-16
MONTHS = ("януари", "февруари", "март", "април", "май", "юни", "юли", "август", "септември", "октомври", "ноември",
          "декември")
GIVE_UP_AFTER = 3  # failed cities before anything is saved: then the site is at fault, not one city

_LATIN = dict(zip("абвгдежзийклмнопрстуфхцчшщъьюя", (
    "a", "b", "v", "g", "d", "e", "zh", "z", "i", "y", "k", "l", "m", "n", "o", "p", "r", "s", "t", "u", "f", "h", "ts",
    "ch", "sh", "sht", "a", "y", "yu", "ya")))
_CHARSET = re.compile(rb"""<meta[^>]*charset\s*=\s*["']?\s*([A-Za-z0-9_.:-]+)""", re.IGNORECASE)
_DAY = re.compile(r"\d{1,2}\.?")
_TIME = re.compile(r"\d{1,2}:\d{2}")


def slug(city: str) -> str:
    """
    A city's file name in Bulgaria's official transliteration, words joined by "-": Айтос → aytos, Велико Търново →
    veliko-tarnovo, София → sofia (a final -ия is -ia).
    """
    words = []
    for word in re.findall(r"\w+", city.lower()):
        tail = ""
        if word.endswith("ия"):
            word, tail = word[:-2], "ia"
        words.append("".join(_LATIN.get(letter, letter) for letter in word) + tail)
    return re.sub(r"[^a-z0-9-]", "", "-".join(words))


def file_names(cities: list[tuple[str, str]], today: dt.date) -> list[str]:
    """Each city's file for the month, <yyyy>/<mm>/<city>_<yyyy-mm>.html; a name met twice gets its option value."""
    names, seen = [], set()
    for value, city in cities:
        stem = slug(city) or "city"
        if stem in seen:
            stem = f"{stem}-{slug(value) or len(seen)}"
        seen.add(stem)
        names.append(f"{today:%Y/%m}/{stem}_{today:%Y-%m}.html")
    return names


def default_name(today: dt.date) -> str:
    return f"{today:%Y/%m}/default_{today:%Y-%m}.html"


def page_charset(content: bytes) -> str:
    """
    The charset the page's <meta> declares; when it declares none Python knows, UTF-8 if the bytes are UTF-8, else
    windows-1251, the older Bulgarian web's.
    """
    match = _CHARSET.search(content[:8192])
    if match:
        try:
            return codecs.lookup(match.group(1).decode("ascii")).name
        except LookupError:
            pass
    try:
        content.decode("utf-8")
        return "utf-8"
    except UnicodeDecodeError:
        return "cp1251"


@dataclass
class _Option:
    value: str | None
    label: str = ""
    selected: bool = False


@dataclass
class _Control:
    name: str | None
    form: int | None
    value: str = ""  # an <input>'s
    options: list[_Option] | None = None  # a <select>'s; None for an <input>


class _FormParser(HTMLParser):
    """The page's forms, and in document order the controls a browser would send and every list."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.forms: list[tuple[str, str]] = []  # (action, method)
        self.controls: list[_Control] = []
        self._form: int | None = None
        self._list: _Control | None = None
        self._option: _Option | None = None

    def handle_starttag(self, tag, attrs):
        a = {k: v or "" for k, v in attrs}
        if tag == "form":
            self.forms.append((a.get("action", ""), a.get("method", "").strip().lower()))
            self._form = len(self.forms) - 1
        elif tag == "select":
            self._end_option()
            self._list = _Control(a.get("name") or None, self._form, options=[])
            self.controls.append(self._list)
        elif tag == "option" and self._list is not None:
            self._end_option()
            self._option = _Option(a["value"] if "value" in a else None, "", "selected" in a)
        elif tag == "input" and a.get("name"):
            kind = a.get("type", "text").lower()
            if kind in ("checkbox", "radio"):
                if "checked" in a:
                    self.controls.append(_Control(a["name"], self._form, a.get("value", "on")))
            elif kind not in ("submit", "button", "image", "reset", "file"):
                self.controls.append(_Control(a["name"], self._form, a.get("value", "")))

    def handle_endtag(self, tag):
        if tag in ("option", "select"):
            self._end_option()
            if tag == "select":
                self._list = None
        elif tag == "form":
            self._form = None

    def handle_data(self, data):
        if self._option is not None:
            self._option.label += data

    def _end_option(self):
        if self._option is not None and self._list is not None:
            label = " ".join(self._option.label.split())
            value = label if self._option.value is None else self._option.value
            self._list.options.append(_Option(value, label, self._option.selected))
        self._option = None


@dataclass
class Selector:
    """The page's «Град» and «Месец» lists, and how its form sends them."""

    action: str
    method: str  # "get" or "post"
    charset: str
    city_field: str
    cities: list[tuple[str, str]]  # (option value, city name), in the page's order
    month_field: str | None
    months: list[tuple[str, str]]  # (option value, month name)
    chosen_month: str | None  # the month option the page marks as selected
    controls: list[tuple[str, str]]  # what the form sends as the page stands, in order

    def fields(self, city: str, month: str | None) -> list[tuple[str, str]]:
        """The form's fields with [city] chosen, and [month] when given."""
        fields = []
        for name, value in self.controls:
            if name == self.city_field:
                value = city
            elif name == self.month_field and month is not None:
                value = month
            fields.append((name, value))
        return fields


def find_selector(text: str, page_url: str = PAGE_URL, charset: str = "utf-8") -> Selector | None:
    """
    The form around the «Град» list (the named list that holds REFERENCE_CITY among at least MIN_CITIES names) and the
    «Месец» list beside it; None when the page has no such list. Lists outside any form go by GET to the page itself.
    """
    parser = _FormParser()
    parser.feed(text)
    parser.close()
    lists = [c for c in parser.controls if c.options is not None and c.name]
    city = next((c for c in lists
                 if len(c.options) >= MIN_CITIES and any(o.label == REFERENCE_CITY for o in c.options)), None)
    if city is None:
        return None
    month = next((c for c in lists if c is not city and c.form == city.form
                  and {MONTHS[0], MONTHS[-1]} <= {o.label.lower() for o in c.options}), None)
    if city.form is None:
        action, method, members = "", "get", [c for c in (city, month) if c is not None]
    else:
        (action, method), members = parser.forms[city.form], [c for c in parser.controls if c.name and c.form == city.form]
    controls = []
    for c in members:
        if c.options is None:
            controls.append((c.name, c.value))
        elif c.options:
            controls.append((c.name, next((o for o in c.options if o.selected), c.options[0]).value))
    return Selector(
        action=urllib.parse.urljoin(page_url, action),
        method="post" if method == "post" else "get",
        charset=charset,
        city_field=city.name,
        cities=[(o.value, o.label) for o in city.options if o.value.strip() and o.label],
        month_field=month.name if month else None,
        months=[(o.value, o.label) for o in month.options] if month else [],
        chosen_month=next((o.value for o in month.options if o.selected), None) if month else None,
        controls=controls,
    )


def month_value(months: list[tuple[str, str]], number: int) -> str | None:
    """The «Месец» option of month [number] (1 to 12): by its name, else by its place in a list of twelve."""
    for value, label in months:
        if label.lower() == MONTHS[number - 1]:
            return value
    return months[number - 1][0] if len(months) == 12 else None


def city_request(selector: Selector, city: str, month: str | None) -> tuple[str, bytes | None]:
    """The URL, and the body when the form posts, that a browser sends once [city] and [month] are chosen."""
    query = urllib.parse.urlencode(selector.fields(city, month), encoding=selector.charset, errors="xmlcharrefreplace")
    parts = urllib.parse.urlsplit(selector.action)._replace(fragment="")
    if selector.method == "post":
        return urllib.parse.urlunsplit(parts), query.encode("ascii")
    return urllib.parse.urlunsplit(parts._replace(query=query)), None


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


def table_rows(text: str, days: int) -> list[tuple[str, ...]]:
    """
    The month's table wherever the page puts it: the first run of rows for days 1 to [days] and no further, each a day
    number and the six times (H:MM); [] when the page holds none.
    """
    parser = _Text()
    parser.feed(text)
    parser.close()
    tokens, rows, i = parser.parts, [], 0
    while i + 6 < len(tokens):
        if _DAY.fullmatch(tokens[i]) and all(_TIME.fullmatch(t) for t in tokens[i + 1:i + 7]):
            rows.append(tuple(tokens[i:i + 7]))
            i += 7
        else:
            i += 1
    numbers = [int(row[0].rstrip(".")) for row in rows]
    for start in range(len(rows) - days + 1):
        run, after = numbers[start:start + days], numbers[start + days:start + days + 1]
        if run == list(range(1, days + 1)) and after != [days + 1]:
            return rows[start:start + days]
    return []


def page_problem(text: str, days: int, month: str | None) -> str | None:
    """Why a city's page is not this month's table; None when it holds days 1 to [days] and marks no other month."""
    if not table_rows(text, days):
        return f"no table of days 1 to {days}"
    shown = find_selector(text)
    if month is not None and shown is not None and shown.chosen_month not in (None, month):
        return f"the page marks month option {shown.chosen_month!r} as chosen, not {month!r}"
    return None


def _keep_default(session, today: dt.date, content: bytes, why: str) -> None:
    session.save(CC, default_name(today), content, PAGE_URL, f"the default page (this month, its city not named); {why}")


def collect(session, today: dt.date) -> int:
    default = session.fetch(PAGE_URL)
    charset = page_charset(default)
    default_text = default.decode(charset, errors="replace")
    selector = find_selector(default_text, PAGE_URL, charset)
    if selector is None:
        _keep_default(session, today, default, "no named city list holding Sofia was found")
        raise RuntimeError(f"bg: no city list on {PAGE_URL}; its default page was kept")
    month = month_value(selector.months, today.month)
    days = calendar.monthrange(today.year, today.month)[1]
    default_rows = table_rows(default_text, days)
    todo = [(value, city, name) for (value, city), name in zip(selector.cities, file_names(selector.cities, today))
            if not (Path(session.root) / CC / name).exists()]

    saved, failures, held, trusted = 0, [], [], False
    for value, city, name in todo:
        url, body = city_request(selector, value, month)
        try:
            content = session.fetch(url, data=body, headers={"Referer": PAGE_URL})
            text = content.decode(page_charset(content), errors="replace")
            problem = page_problem(text, days, month)
        except Exception as error:  # noqa: BLE001 - one city's failure must not stop the others
            problem = f"{type(error).__name__}: {error}"
        if problem:
            failures.append(f"{slug(city)}: {problem}")
            if len(failures) >= GIVE_UP_AFTER and not saved and not held:
                break
            continue
        note = f"{city} ({slug(city)}), {today:%Y-%m}" + (f"; POST {body.decode('ascii')}" if body else "")
        held.append((name, content, url, note, table_rows(text, days) != default_rows))
        # Pages wait until some city's table differs from the default page's, proof that the choice reached the server.
        trusted = trusted or held[-1][4] or len(todo) == 1
        if not trusted and len(held) >= 2:
            _keep_default(session, today, default, "the city choice is ignored: two cities gave this page's table")
            stems = ", ".join(Path(item[0]).stem for item in held)
            raise RuntimeError(f"bg: {stems} gave the default page's table: the city choice is not reaching the server")
        if trusted:
            saved += sum(session.save(CC, *item[:4]) is not None for item in held)
            held.clear()
    failures += [f"{Path(item[0]).stem}: the default page's table, not saved (unconfirmed)" for item in held]
    if failures:
        if not saved:
            _keep_default(session, today, default, "no city could be saved: " + "; ".join(failures[:3]))
        raise RuntimeError(f"bg: {len(failures)} of {len(todo)} cities not saved ({saved} saved): "
                           + "; ".join(failures[:6]))
    return saved
