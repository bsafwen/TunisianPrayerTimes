"""
Libya (plan 9.3, 8.2, 8.8): the General Authority of Awqaf's table for its 22 cities, which only ever shows today, and
Dar al-Ifta's Tripoli feed, which shows today and the next 7 days. Daily, for fits/ly_awqaf.

- awqaf.gov.ly: one POST per city to the site's WordPress endpoint, https://awqaf.gov.ly/sovinel/admin-ajax.php with
  action=get_prayer_times and city=<code>, as the homepage's widget does (mawakit-script.js). The endpoint takes no
  date. Its answer is JSON, {"success":true,"data":[{"city_en","month","day","emsak","fajr","shorouk","dohr","aser",
  "magreb","esha"}]}: today's row in Libya's clock (UTC+2). Each is saved raw as <yyyy>/<mm>/ly_awqaf_<date>_<code>.json,
  dated by the row's own month and day. The codes are the homepage selector's (CITIES, read 2026-10-08). The month's
  first run, and a run whose first requests all fail, read the homepage again and follow its selector and ajax_url; a
  change there fails the run, so the constants and the fit's zones get updated. An answer that is not the asked city's
  row is not saved. Failed cities are asked once more at the end of the run, and those still failing fail it.
- api.ifta.ly/api/v1/prayer-time: Tripoli, {"value":[{day, month, imsak, fajr, sunrise, dhuhr, asr, maghrib, isha}]
  x 8, "isSuccess":true}, today and the next 7 days. The research found its parameters ignored (2026-10-08) and no
  public API description exists (2026-10-16), so no earlier day can be asked for. Saved daily as
  <yyyy>/<mm>/ly_ifta_<first day>.json. A day that no run reached is still held by any answer saved in the 7 days
  before it: that is plan 9.3's 7-day recovery for Tripoli, and it needs no request. The run prints the Tripoli days
  that no saved answer holds (lost for good, like the 21 other cities' rows of every day without a run).
- api.ifta.ly/api/v1/location: Dar al-Ifta's location record (its headquarters in Dhahra, Tripoli, per the research;
  not a stated computation point). Saved as <yyyy>/<mm>/ly_ifta_location_<date>.json when it differs from the last one
  saved; a failure there is only printed.

fits/ly_awqaf/fit.py reads research-2026-10-11/LY/official_rows.csv, not these files. They hold that file's fields: the
Awqaf's emsak, shorouk, dohr, aser, magreb and esha are its imsak, sunrise, dhuhr, asr, maghrib and isha, and
Dar al-Ifta's rows carry seconds (05:42:00).
"""

from __future__ import annotations

import datetime as dt
import json
import re
import urllib.parse
from pathlib import Path

NAME = "ly"
CC = "LY"
CADENCE = "daily"
DESCRIPTION = "Libya Awqaf (awqaf.gov.ly): today's row of its 22 cities; Dar al-Ifta API: Tripoli, today + 7 days"

HOME = "https://awqaf.gov.ly/"
AJAX = "https://awqaf.gov.ly/sovinel/admin-ajax.php"    # the homepage's ajax_url (2026-10-08)
# The homepage's city selector, in its order (2026-10-08; Ojala, Awjila, is labelled like Ejdabya there).
CITIES = ("Tripoli", "Ejdabya", "Breaka", "beda", "Alkomos", "Almarag", "Alzaweya", "Koufra", "Emsaed", "Ojala",
          "Benghazi", "BanyWalead", "Jalo", "Derna", "Raslanof", "Zaleaten", "Sabha", "Sert", "Tobrek", "Ekdames",
          "Mosrata", "Hoon")
IFTA = "https://api.ifta.ly/api/v1/prayer-time"
IFTA_LOCATION = "https://api.ifta.ly/api/v1/location"
IFTA_DAYS = 8                       # an answer holds today and the next 7 days
JSON_HEADERS = {"Accept": "application/json"}
GIVE_UP = 5                         # failures in a row, with no city read yet, after which the rest is not asked
NEAR_DAYS = 7                       # a row further than this from the run's date is not today's
SHOWN = ("fajr", "shorouk", "dohr", "aser", "magreb", "esha")       # what the widget shows (not emsak)
IFTA_TIMES = ("fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha")
LOCATION_NOTE = ("Dar al-Ifta's location record (its headquarters per the research, not a stated computation point); "
                 "saved when it changes")
_CLOCK = re.compile(r"\d{1,2}:\d{2}(?::\d{2})?")
_IFTA_FILE = re.compile(r"ly_ifta_(\d{4}-\d{2}-\d{2})(?:-\d{6})?\.json")


def ascii_text(text: str) -> str:
    """[text] safe to print on any console (non-ASCII escaped)."""
    return text.encode("ascii", "backslashreplace").decode("ascii")


def excerpt(content: bytes, size: int = 120) -> str:
    return ascii_text(" ".join(content[:4 * size].decode("utf-8", errors="replace").split())[:size])


def reason(error: Exception) -> str:
    return ascii_text(f"{type(error).__name__}: {error}")[:300]


def twice(call):
    """call(), asked once more when it raises (the session's pause comes between); the second error propagates."""
    try:
        return call()
    except Exception:  # noqa: BLE001 - asked once more
        return call()


# ---------------------------------------------------------------------------------------------- awqaf.gov.ly

def awqaf_form(code: str) -> dict[str, str]:
    """The widget's POST fields (mawakit-script.js): the action and the city code, and no date."""
    return {"action": "get_prayer_times", "city": code}


def cities_from_home(page: str) -> list[str]:
    """The city codes of the homepage's selector (<select id="prayer-times-city">), in its order."""
    select = re.search(r"""(?s)<select[^>]*\bid=["']prayer-times-city["'][^>]*>(.*?)</select>""", page)
    codes = re.findall(r"""<option[^>]*\bvalue=["']([A-Za-z]+)["']""", select.group(1)) if select else []
    return list(dict.fromkeys(codes))


def ajax_url_from_home(page: str) -> str | None:
    """The ajax_url the homepage gives its widget (ajax_object), kept only when it is an https admin-ajax.php address
    on awqaf.gov.ly."""
    m = re.search(r"ajax_object\s*=\s*(\{.*?\})\s*;", page)
    try:
        url = json.loads(m.group(1)).get("ajax_url") if m else None
    except (ValueError, AttributeError):
        return None
    parts = urllib.parse.urlsplit(url) if isinstance(url, str) else None
    if (parts is None or parts.scheme != "https" or parts.hostname not in ("awqaf.gov.ly", "www.awqaf.gov.ly")
            or not parts.path.endswith("/admin-ajax.php")):
        return None
    return url


def awqaf_row(content: bytes, code: str) -> dict:
    """The row of an Awqaf answer for city [code], read as the widget reads it (success, then a non-empty data list)
    with its six shown times. Raises ValueError for any other answer, and for another city's row."""
    try:
        answer = json.loads(content.decode("utf-8-sig"))
    except ValueError:
        raise ValueError("not JSON: " + excerpt(content)) from None
    data = answer.get("data") if isinstance(answer, dict) and answer.get("success") is True else None
    if not (isinstance(data, list) and data and isinstance(data[0], dict)):
        raise ValueError("no row: " + excerpt(content))
    row = data[0]
    missing = [k for k in SHOWN if not _CLOCK.fullmatch(str(row.get(k, "")).strip())]
    if missing:
        raise ValueError(f"no {', '.join(missing)} in the row: " + excerpt(content))
    city = str(row.get("city_en", code)).strip()
    if city.lower() != code.lower():
        raise ValueError(ascii_text(f"the row of {city}, not {code}"))
    return row


def row_day(row: dict, today: dt.date) -> dt.date:
    """The date a row holds: its month and day in the year that puts it nearest [today] (rows carry no year), or
    [today] when it gives no month and day. Raises ValueError for a row more than NEAR_DAYS from [today]."""
    try:
        month, day = int(row["month"]), int(row["day"])
    except (KeyError, TypeError, ValueError):
        return today
    dates = []
    for year in (today.year - 1, today.year, today.year + 1):
        try:
            dates.append(dt.date(year, month, day))
        except ValueError:
            pass
    near = min(dates, key=lambda d: abs((d - today).days)) if dates else None
    if near is None or abs((near - today).days) > NEAR_DAYS:
        raise ValueError(f"a row of month {month} day {day}, not near {today}")
    return near


def awqaf_name(day: dt.date, code: str) -> str:
    return f"{day:%Y/%m}/ly_awqaf_{day.isoformat()}_{code}.json"


def difference(old, new) -> str:
    added, removed = [c for c in new if c not in old], [c for c in old if c not in new]
    return "; ".join(part for part in (("added " + ", ".join(added)) if added else "",
                                       ("removed " + ", ".join(removed)) if removed else "") if part)


# ---------------------------------------------------------------------------------------------- api.ifta.ly

def ifta_rows(content: bytes) -> list[dict]:
    """The day rows of a Dar al-Ifta answer ({"value": [...], "isSuccess": true}), each with its day, month and six
    times. Raises ValueError for any other answer."""
    try:
        answer = json.loads(content.decode("utf-8-sig"))
    except ValueError:
        raise ValueError("not JSON: " + excerpt(content)) from None
    rows = answer.get("value") if isinstance(answer, dict) and answer.get("isSuccess") is True else None
    if not (isinstance(rows, list) and rows and all(isinstance(r, dict) for r in rows)):
        raise ValueError("no day rows: " + excerpt(content))
    for r in rows:
        if "day" not in r or "month" not in r or not all(_CLOCK.fullmatch(str(r.get(k, "")).strip()) for k in IFTA_TIMES):
            raise ValueError("a row without its day or its six times: " + excerpt(json.dumps(r).encode()))
    return rows


def ifta_name(first: dt.date) -> str:
    return f"{first:%Y/%m}/ly_ifta_{first.isoformat()}.json"


def location_name(day: dt.date) -> str:
    return f"{day:%Y/%m}/ly_ifta_location_{day.isoformat()}.json"


def ifta_firsts(folder: Path) -> list[dt.date]:
    """The first days of the Dar al-Ifta answers saved under [folder] (official/ly), from their names."""
    found = (_IFTA_FILE.fullmatch(p.name) for p in folder.rglob("ly_ifta_*.json")) if folder.is_dir() else ()
    return sorted({dt.date.fromisoformat(m.group(1)) for m in found if m})


def lost_days(firsts: list[dt.date], first: dt.date, span: int = IFTA_DAYS) -> list[dt.date]:
    """The Tripoli days between the last answer saved before [first] and [first] that neither holds (an answer that
    starts on D holds D to D + span - 1): what more than a week without a run loses."""
    earlier = [d for d in firsts if d < first]
    if not earlier:
        return []
    reach = max(earlier) + dt.timedelta(days=span)
    return [reach + dt.timedelta(days=k) for k in range((first - reach).days)]


def last_location(folder: Path) -> bytes | None:
    """The bytes of the location record saved last under [folder], or None."""
    paths = list(folder.rglob("ly_ifta_location_*.json")) if folder.is_dir() else []
    return max(paths, key=lambda p: (p.stat().st_mtime, p.name)).read_bytes() if paths else None


# ---------------------------------------------------------------------------------------------- the run

def collect(session, today: dt.date) -> int:
    folder = Path(session.root) / CC.lower()
    endpoint, cities, problems = AJAX, list(CITIES), []
    read = saved = 0
    home_read = False

    def follow_home() -> None:
        """Reads the homepage's ajax_url and city selector and follows them (best effort)."""
        nonlocal endpoint, cities, home_read
        home_read = True
        try:
            page = session.fetch(HOME).decode("utf-8", errors="replace")
        except Exception as error:  # noqa: BLE001 - the known address and cities stay
            print(f"ly: homepage not read ({reason(error)}); the known address and cities stay")
            return
        url, codes = ajax_url_from_home(page), cities_from_home(page)
        unread = [what for what, value in (("ajax_url", url), ("city selector", codes)) if not value]
        if unread:
            print(f"ly: no {' or '.join(unread)} found on the homepage; the known one stays")
        if url and url != endpoint:
            problems.append(f"the homepage's ajax_url is {url}, not {AJAX}: update ly.AJAX")
            endpoint = url
        if codes and set(codes) != set(cities):
            problems.append(f"the homepage's city selector differs from ly.CITIES ({difference(cities, codes)}): "
                            "update it, and the fit's zones")
        cities = codes or cities

    def ask(code: str) -> None:
        nonlocal read, saved
        content = session.fetch(endpoint, data=awqaf_form(code))
        row = awqaf_row(content, code)
        day = row_day(row, today)
        read += 1
        source = f"POST {endpoint} action=get_prayer_times city={code}"
        if session.save(CC, awqaf_name(day, code), content, source, f"Awqaf, city {code}: its row of {day}", once=True):
            saved += 1

    def round_of(codes: list[str]) -> dict[str, str]:
        """Asks each city; the reason of each failure. After GIVE_UP failures with no city read yet, the rest is left."""
        failed: dict[str, str] = {}
        for code in codes:
            if not read and len(failed) >= GIVE_UP:
                failed[code] = "not asked: the endpoint answered nothing"
                continue
            try:
                ask(code)
            except Exception as error:  # noqa: BLE001 - asked once more below
                failed[code] = reason(error)
        return failed

    if not any((folder / f"{today:%Y}" / f"{today:%m}").glob("ly_awqaf_*.json")):
        follow_home()                       # the month's first run checks the selector
    failed = round_of(cities)
    if failed and not read and not home_read:
        follow_home()                       # nothing answered: the address or the codes may have moved
        failed = round_of(cities)
    elif failed:
        failed = round_of(list(failed))
    problems += [f"Awqaf {code}: {why}" for code, why in failed.items()]

    def ifta() -> tuple[bytes, list[dict]]:
        content = session.fetch(IFTA, headers=JSON_HEADERS)
        return content, ifta_rows(content)

    first = last = None
    try:
        content, rows = twice(ifta)
        first = row_day(rows[0], today)
        last = first + dt.timedelta(days=len(rows) - 1)
        if session.save(CC, ifta_name(first), content, f"GET {IFTA}",
                        f"Dar al-Ifta, Tripoli: {len(rows)} days, {first} to {last}", once=True):
            saved += 1
            lost = lost_days(ifta_firsts(folder), first)
            if lost:
                print(f"ly: Tripoli's days {lost[0]} to {lost[-1]} are in no saved api.ifta.ly answer (more than a "
                      "week without a run): lost")
    except Exception as error:  # noqa: BLE001 - reported below
        problems.append(f"api.ifta.ly: {reason(error)}")

    try:
        content = session.fetch(IFTA_LOCATION, headers=JSON_HEADERS)
        json.loads(content.decode("utf-8-sig"))
        if content != last_location(folder) and session.save(CC, location_name(today), content,
                                                              f"GET {IFTA_LOCATION}", LOCATION_NOTE):
            saved += 1
    except Exception as error:  # noqa: BLE001 - a reference record, not a table: printed only
        print(f"ly: api.ifta.ly location not read ({reason(error)})")

    print(f"ly: Awqaf {read} of {len(cities)} cities read, api.ifta.ly "
          + (f"{first} to {last}" if first else "not read") + f"; {saved} file(s) saved")
    if problems:
        raise RuntimeError("ly: " + "; ".join(problems))
    return saved
