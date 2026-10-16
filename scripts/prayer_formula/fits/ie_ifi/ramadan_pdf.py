"""
Reads the Islamic Foundation of Ireland's Ramadan 2026/1447 town timetables (test-data-local/official/ie/ifi/
ramadan-2026/<town>_ramadan_timetable_2026.pdf, fetched by fetch.py) with the standard library: a one-page PDF whose
content stream draws text in CID fonts with ToUnicode maps. Each table row is the weekday, the Ramadan day, the
February or March date and six clock times (Fajr, Sunrise, Dhuhr, Asr, Maghrib, Isha), Irish standard time (GMT;
all of Ramadan 1447 falls before the clock change of 29 March 2026).

    rows(path) -> {datetime.date: [six "HH:MM" strings]}
"""

from __future__ import annotations

import datetime
import re
import zlib
from pathlib import Path

RAMADAN_1 = datetime.date(2026, 2, 19)     # Ramadan 1, the tables' first row (Thursday 19 February 2026)
_TIME = re.compile(r"\b(\d{1,2}:\d{2})\b")
_WEEKDAYS = ("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")


def _objects(data: bytes) -> dict[int, bytes]:
    return {int(m.group(1)): m.group(2) for m in re.finditer(rb"(\d+) 0 obj(.*?)endobj", data, re.S)}


def _stream(body: bytes) -> bytes:
    m = re.search(rb"stream\r?\n(.*?)\r?\nendstream", body, re.S)
    if not m:
        raise ValueError("no stream")
    raw = m.group(1)
    return zlib.decompress(raw) if b"FlateDecode" in body[:m.start()] else raw


def _cmap(text: bytes) -> dict[int, str]:
    out = {}
    for block in re.findall(rb"beginbfchar(.*?)endbfchar", text, re.S):
        for a, b in re.findall(rb"<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>", block):
            out[int(a, 16)] = bytes.fromhex(b.decode()).decode("utf-16-be")
    for block in re.findall(rb"beginbfrange(.*?)endbfrange", text, re.S):
        for a, b, c in re.findall(rb"<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>", block):
            lo, hi, start = int(a, 16), int(b, 16), int(c, 16)
            for k in range(lo, hi + 1):
                out[k] = chr(start + k - lo)
    return out


def text_items(path: Path) -> list[tuple[float, float, str]]:
    """(x, y, text) of every text-showing operator on the page."""
    objs = _objects(path.read_bytes())
    page = next(b for b in objs.values() if b"/Type /Page" in b and b"/Contents" in b)
    contents = [int(x) for x in re.findall(rb"(\d+) 0 R", re.search(rb"/Contents\s*\[?([^\]/]*)", page).group(1))]
    resources = objs[int(re.search(rb"/Resources (\d+) 0 R", page).group(1))]
    fonts = {}
    for name, num in re.findall(rb"/(F\d+) (\d+) 0 R", resources):
        to_unicode = re.search(rb"/ToUnicode (\d+) 0 R", objs[int(num)])
        fonts[name.decode()] = _cmap(_stream(objs[int(to_unicode.group(1))])) if to_unicode else {}
    out = []
    for c in contents:
        s = _stream(objs[c]).decode("latin-1")
        font, x, y = None, 0.0, 0.0
        pattern = (r"/(F\d+) [\d.]+ Tf|([-\d.]+) ([-\d.]+) ([-\d.]+) ([-\d.]+) ([-\d.]+) ([-\d.]+) Tm"
                   r"|\[(.*?)\] TJ|<([0-9A-Fa-f]*)> Tj")
        for m in re.finditer(pattern, s, re.S):
            if m.group(1):
                font = m.group(1)
            elif m.group(2):
                x, y = float(m.group(6)), float(m.group(7))
            else:
                body = m.group(8) if m.group(8) is not None else "<" + m.group(9) + ">"
                text = ""
                for hexs in re.findall(r"<([0-9A-Fa-f]+)>", body):
                    for k in range(0, len(hexs), 4):
                        text += fonts.get(font, {}).get(int(hexs[k:k + 4], 16), "?")
                out.append((x, y, text))
    return out


def rows(path: Path) -> dict[datetime.date, list[str]]:
    """The table's 30 rows by date: the six clock times of each Ramadan day."""
    items = sorted(text_items(path), key=lambda t: (t[1], t[0]))
    lines: list[list] = []
    for x, y, text in items:
        if lines and abs(lines[-1][0] - y) <= 2.0:
            lines[-1][1].append((x, text))
        else:
            lines.append([y, [(x, text)]])
    out = {}
    for _, parts in lines:
        line = " ".join(t for _, t in sorted(parts))
        words = line.split()
        if not words or words[0] not in _WEEKDAYS:
            continue
        times = _TIME.findall(line)
        if len(times) != 6:
            raise ValueError(f"{path.name}: a row without six times: {line!r}")
        ramadan_day = int(words[1])
        day = RAMADAN_1 + datetime.timedelta(days=ramadan_day - 1)
        if _WEEKDAYS[day.weekday()] != words[0]:
            raise ValueError(f"{path.name}: {words[0]} for Ramadan {ramadan_day} ({day})")
        out[day] = times
    if len(out) != 30:
        raise ValueError(f"{path.name}: {len(out)} rows")
    return out


def town_name(path: Path) -> str:
    return path.name.split("_")[0]
