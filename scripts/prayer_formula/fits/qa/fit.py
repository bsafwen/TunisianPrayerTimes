#!/usr/bin/env python3
"""
Fits Qatar's two profiles (plan 8.1, 8.2, 8.7, 8.8, Q3; milestone AM2, 9.4): `qa_awqaf`, the Ministry of Awqaf's
table, and `qa_qch`, Qatar Calendar House's, which the plan treats together (the same rule with Dhuhr and Asr + 1
from 2025-06-26, and the same six places). Writes android-app/app/src/main/assets/official-zones/qa_awqaf.json and
qa_qch.json (schema 1).

Data, read at run time and never copied (D13, A7), under test-data-local/research-2026-10-08/QA/:
- qa/awqaf_2026.csv: the Ministry's API (Doha) for every day of 2026, with the API's own Hijri date.
- qa/awqaf_api_raw.json: the same API's answers, 2026 and the 5th and 20th of each month of 2025 and 2027.
- qa_skeptic/awq.csv, awq2026.csv, awq_misc.csv: API rows of 2006-2027 (the Fajr change of 2020-08-20 among them).
- qa/doha_all.csv: QCH's Doha times parsed from its PDFs (Ramadan 1447 imsakiya; Muharram to Rabi II 1448).
- qa/pdfs/rabia1_1448.pdf, rabia2_1448.pdf, qa_skeptic/dl/rabia2_1444.pdf, shaw1446.pdf: QCH's wall calendars with a
  text layer (one page a day: Doha's six times and the Fajr and Maghrib minutes to add for Al Shamal, Dukhan and Abu
  Samra), read with the small PDF reader below. The 1447 monthly PDFs are images and are not read.
- qa/cal.html: QCH's /cal page of 2026-10-08 (its prayData: six times at Doha, Al Shamal, Mesaieed, Dukhan, Abu Samra
  and Halul). More /cal pages, named cal_YYYYMMDD.html, are read from test-data-local/research-2026-10-11/QA/cal/ or
  --cal DIR (2026-10-11 was saved on that day).
- Umm al-Qura's Ramadan days (E8), written below, are checked against KACST's year tables when they are at hand
  (test-data-local/research-2026-10-08/SA, their Hijri dates only).
- Town centres: GeoNames (cities1000, and geonames.org for Abu Samra and Halul, read 2026-10-15).

Method (9.4). Each published minute bounds the unrounded time to a minute (E1, nearest), so every parameter has a
window per minute; the value most windows hold wins. The Ministry's table is perpetual (E5): the search reads each
row at the reference year, while every held-out score (FitStats) comes from common.compute (the reference engine,
which the Kotlin engine matches) at the row's actual date, as the app shows it (A8).
1. Dhuhr: sun model (E14), evaluation (E4) and reference year, from the Ministry's 2026 Dhuhr column.
2. All six times at the reference year: Doha's point, then each plan value by its own scan at that point (Fajr 18°
   with - 1 from 2020-08-20, Maghrib + 2, + 1 on Ramadan days, Isha = Maghrib + 90, Asr factor 1 with the noon
   declination at transit, the standard horizon, nearest rounding).
3. The towns: Al Shamal, Dukhan and Abu Samra at points fitted to QCH's printed Fajr and Maghrib minutes of 1448
   (computed for the actual dates; Fajr at 18° without the - 1, a zone offset of Fajr + 1); the print does not name
   its three columns, so the assignment is fitted too. Mesaieed and Halul from the /cal days, at the town's
   latitude (a few October days fix only the longitude).
Held out: Doha (qa_awqaf) by a month-split of 2026 (the point refitted on the odd months and scored on the even
ones, and the reverse) plus the API's 2025 and 2027 days and its rows of 2020-2025; Doha (qa_qch): every QCH day,
none of which fits anything; the three towns: a month-split of the 1448 print (September against August and
October) and the /cal days; Mesaieed and Halul: leave one /cal day out. The 1444 and 1446 prints, made before
1 Muharram 1447, give the towns other minutes (a side check, outside the records); their Doha times are held out.
Quirk days (8.3, Q3): the API's own Ramadan edge days, where its Hijri calendar and Umm al-Qura's disagree.

Usage: python scripts/prayer_formula/fits/qa/fit.py [--cal DIR]
"""

from __future__ import annotations

import argparse
import csv
import datetime
import json
import math
import re
import sys
import time
import zlib
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import common  # noqa: E402

ref = common.ref
EV = common.EVENTS
TZ = "Asia/Qatar"                       # UTC+3 all year
QA = common.LOCAL_DATA / "research-2026-10-08" / "QA"
SA = common.LOCAL_DATA / "research-2026-10-08" / "SA"
CAL_DIR = common.LOCAL_DATA / "research-2026-10-11" / "QA" / "cal"
D2R = math.pi / 180
FAJR_CHANGE = datetime.date(2020, 8, 20)        # E9: the Ministry's Fajr - 1 (8.2)
QCH_CHANGE = datetime.date(2025, 6, 26)         # E9: QCH's Dhuhr and Asr + 1 (1 Muharram 1447)
APP_YEARS = range(2020, 2101)
# Umm al-Qura's Ramadan (Java's HijrahDate, E8 UMM_AL_QURA_COMPUTED), first and last day.
UQ_RAMADAN = {2022: ("2022-04-02", "2022-05-01"), 2025: ("2025-03-01", "2025-03-29"),
              2026: ("2026-02-18", "2026-03-19"), 2027: ("2027-02-08", "2027-03-08")}
SA_TABLES = {2025: "sa_skeptic/data/arar_2025.json", 2026: "sa/web/riyadh2026.json", 2027: "sa_skeptic/data/medina_2027.json"}

# QCH's six places (its /cal cityId, Arabic name), with the town centre (GeoNames id, lat, lng) that the mapping
# measures from; Doha's named places Al Khor and Al Wakra (6 km, 8.8).
PLACES = {
    "doha": ("3", "الدوحة", 290030, 25.28545, 51.53096),
    "shamal": ("6", "الشمال", 289739, 26.12933, 51.2009),
    "dukhan": ("14", "دخان", 289813, 25.42485, 50.78227),
    "abusamra": ("15", "أبو سمرة", 398806, 24.74375, 50.83174),
    "mesaieed": ("13", "مسيعيد", 289525, 24.99226, 51.55067),
    "halul": ("16", "حالول", 289790, 25.67325, 52.41096),
}
DOHA_NAMED = (("Al Khor", 289962, 25.68389, 51.50583), ("Al Wakra", 289915, 25.17151, 51.60337))
NAMED_AREA_KM = 6.0
PRINT_TOWNS = ("shamal", "dukhan", "abusamra")
QCH_PDFS = (("qa/pdfs/rabia1_1448.pdf", 2026), ("qa/pdfs/rabia2_1448.pdf", 2026),
            ("qa_skeptic/dl/rabia2_1444.pdf", 2022), ("qa_skeptic/dl/shaw1446.pdf", 2025))
# FitSample sources (6.3): short ids, explained in README-part.md.
MINISTRY_SOURCE = "qa_awqaf_api"            # the Ministry's API, Doha
QCH_SOURCE = "qa_qch"                      # QCH's calendars (PDF), Ramadan imsakiya and /cal data
MONTHS = {m: i + 1 for i, m in enumerate(("January", "February", "March", "April", "May", "June", "July", "August",
                                          "September", "October", "November", "December"))}
WEEKDAYS = ("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")


# ================================================================================================ PDF text

class Pdf:
    """A minimal PDF text reader (classic and compressed object streams, FlateDecode, simple fonts and Type0 fonts
    with a ToUnicode CMap): enough for QCH's calendars."""

    def __init__(self, data: bytes):
        self.objs: dict[int, bytes] = {}
        for m in re.finditer(rb"(\d+)\s+(\d+)\s+obj\b(.*?)\bendobj", data, re.S):
            self.objs[int(m.group(1))] = m.group(3)
        for body in list(self.objs.values()):
            if re.search(rb"/Type\s*/ObjStm", self.head(body)):
                s = self.stream(body)
                first = int(re.search(rb"/First\s+(\d+)", body).group(1))
                n = int(re.search(rb"/N\s+(\d+)", body).group(1))
                h = s[:first].split()
                pairs = [(int(h[2 * i]), int(h[2 * i + 1])) for i in range(n)]
                for i, (num, off) in enumerate(pairs):
                    end = pairs[i + 1][1] if i + 1 < n else len(s) - first
                    self.objs.setdefault(num, s[first + off:first + end])
        self.cmaps: dict[int, dict[int, str]] = {}

    @staticmethod
    def head(body: bytes) -> bytes:
        i = body.find(b"stream")
        return body if i < 0 else body[:i]

    @staticmethod
    def stream(body: bytes) -> bytes:
        m = re.search(rb"stream\r?\n", body)
        if not m:
            return b""
        raw = body[m.end():]
        raw = raw[:raw.rfind(b"endstream")]
        return zlib.decompressobj().decompress(raw) if b"/FlateDecode" in body[:m.start()] else raw

    @staticmethod
    def ref_of(body: bytes, key: bytes) -> int | None:
        m = re.search(rb"/" + key + rb"\s+(\d+)\s+0\s+R", body)
        return int(m.group(1)) if m else None

    def pages(self) -> list[int]:
        cat = next(b for b in self.objs.values() if re.search(rb"/Type\s*/Catalog", self.head(b)))
        out: list[int] = []

        def walk(n: int):
            b = self.head(self.objs[n])
            kids = re.search(rb"/Kids\s*\[(.*?)\]", b, re.S)
            if kids and re.search(rb"/Type\s*/Pages", b):
                for k in re.findall(rb"(\d+)\s+0\s+R", kids.group(1)):
                    walk(int(k))
            else:
                out.append(n)
        walk(self.ref_of(self.head(cat), b"Pages"))
        return out

    def fonts(self, page: int) -> dict[str, tuple[bool, dict[int, str]]]:
        b = self.head(self.objs[page])
        rn = self.ref_of(b, b"Resources")
        res = self.head(self.objs[rn]) if rn is not None else b
        fn = self.ref_of(res, b"Font")
        if fn is not None:
            table = self.head(self.objs[fn])
        else:
            m = re.search(rb"/Font\s*<<(.*?)>>", res, re.S)
            table = m.group(1) if m else b""
        out = {}
        for name, num in re.findall(rb"/([\w.+\-]+)\s+(\d+)\s+0\s+R", table):
            fb = self.head(self.objs[int(num)])
            tu = self.ref_of(fb, b"ToUnicode")
            out[name.decode()] = (b"/Type0" in fb, self.cmap(tu) if tu is not None else {})
        return out

    def cmap(self, n: int) -> dict[int, str]:
        if n not in self.cmaps:
            s = self.stream(self.objs[n]).decode("latin-1")
            out: dict[int, str] = {}
            for blk in re.findall(r"beginbfchar(.*?)endbfchar", s, re.S):
                for a, b in re.findall(r"<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>", blk):
                    out[int(a, 16)] = "".join(chr(int(b[i:i + 4], 16)) for i in range(0, len(b), 4))
            for blk in re.findall(r"beginbfrange(.*?)endbfrange", s, re.S):
                for a, b, c in re.findall(r"<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>", blk):
                    for k in range(int(a, 16), int(b, 16) + 1):
                        out[k] = chr(int(c, 16) + k - int(a, 16))
            self.cmaps[n] = out
        return self.cmaps[n]

    def contents(self, page: int) -> bytes:
        b = self.head(self.objs[page])
        arr = re.search(rb"/Contents\s*\[(.*?)\]", b, re.S)
        nums = [int(x) for x in re.findall(rb"(\d+)\s+0\s+R", arr.group(1))] if arr else [self.ref_of(b, b"Contents")]
        return b"\n".join(self.stream(self.objs[n]) for n in nums if n is not None)

    def items(self, page: int) -> list[tuple[float, float, float, str]]:
        """(x, y, size, text) of each text-showing operator, in stream order."""
        fonts = self.fonts(page)
        out, stack = [], []
        font, size = None, 0.0
        tm = [1.0, 0.0, 0.0, 1.0, 0.0, 0.0]
        line = [0.0, 0.0]
        lead = 0.0
        for kind, val in tokens(self.contents(page)):
            if kind != "op":
                stack.append((kind, val))
                continue
            if val == "BT":
                tm, line = [1.0, 0.0, 0.0, 1.0, 0.0, 0.0], [0.0, 0.0]
            elif val == "Tf" and len(stack) >= 2:
                font, size = stack[-2][1], float(stack[-1][1])
            elif val == "Tm" and len(stack) >= 6:
                tm = [float(v[1]) for v in stack[-6:]]
                line = [tm[4], tm[5]]
            elif val in ("Td", "TD") and len(stack) >= 2:
                tx, ty = float(stack[-2][1]), float(stack[-1][1])
                line = [line[0] + tx * tm[0] + ty * tm[2], line[1] + tx * tm[1] + ty * tm[3]]
                tm[4], tm[5] = line
                if val == "TD":
                    lead = -ty
            elif val == "TL" and stack:
                lead = float(stack[-1][1])
            elif val == "T*":
                line = [line[0] - lead * tm[2], line[1] - lead * tm[3]]
                tm[4], tm[5] = line
            elif val in ("Tj", "'", '"', "TJ") and stack:
                arg = stack[-1]
                parts = arg[1] if arg[0] == "array" else [arg]
                two, cmap = fonts.get(font, (False, {}))
                text = ""
                for kind2, raw in parts:
                    if kind2 not in ("str", "hex"):
                        continue
                    if two:
                        text += "".join(cmap.get(raw[i] * 256 + raw[i + 1], "") for i in range(0, len(raw) - 1, 2))
                    else:
                        text += "".join(cmap.get(c, chr(c)) for c in raw)
                out.append((tm[4], tm[5], abs(size * tm[3]) or abs(size), text))
            stack = []
        return out


def tokens(s: bytes):
    """Content-stream tokens: ('num', float), ('name', str), ('str'|'hex', bytes), ('array', list), ('dict', None),
    ('op', str)."""
    i, n = 0, len(s)
    arrays: list[list] = []
    white, delim = b" \t\r\n\f\x00", b"()<>[]{}/%"

    def put(tok):
        if arrays:
            arrays[-1].append(tok)
            return None
        return tok

    while i < n:
        c = s[i]
        if c in white:
            i += 1
            continue
        if c == 0x25:
            while i < n and s[i] not in b"\r\n":
                i += 1
            continue
        tok = None
        if c == 0x28:
            depth, i, buf = 1, i + 1, bytearray()
            while i < n and depth:
                ch = s[i]
                if ch == 0x5C:
                    i += 1
                    e = s[i:i + 1]
                    if e in (b"n", b"r", b"t", b"b", b"f"):
                        buf += {b"n": b"\n", b"r": b"\r", b"t": b"\t", b"b": b"\b", b"f": b"\f"}[e]
                        i += 1
                    elif e and e in b"01234567":
                        j = i
                        while j < n and j < i + 3 and s[j:j + 1] in b"01234567":
                            j += 1
                        buf.append(int(s[i:j], 8) & 0xFF)
                        i = j
                    elif e in (b"\r", b"\n"):
                        i += 1
                        if e == b"\r" and s[i:i + 1] == b"\n":
                            i += 1
                    else:
                        buf += e
                        i += 1
                    continue
                if ch == 0x28:
                    depth += 1
                elif ch == 0x29:
                    depth -= 1
                    if depth == 0:
                        i += 1
                        break
                buf.append(ch)
                i += 1
            tok = put(("str", bytes(buf)))
        elif c == 0x3C and s[i + 1:i + 2] == b"<":
            depth, i = 1, i + 2
            while i < n and depth:
                if s[i:i + 2] == b"<<":
                    depth, i = depth + 1, i + 2
                elif s[i:i + 2] == b">>":
                    depth, i = depth - 1, i + 2
                else:
                    i += 1
            tok = put(("dict", None))
        elif c == 0x3C:
            j = s.index(b">", i)
            hx = re.sub(rb"\s", b"", s[i + 1:j])
            tok = put(("hex", bytes.fromhex((hx + b"0" * (len(hx) % 2)).decode())))
            i = j + 1
        elif c == 0x5B:
            arrays.append([])
            i += 1
        elif c == 0x5D:
            arr = arrays.pop() if arrays else []
            i += 1
            tok = put(("array", arr))
        elif c == 0x2F:
            j = i + 1
            while j < n and s[j] not in white and s[j] not in delim:
                j += 1
            tok = put(("name", s[i + 1:j].decode("latin-1")))
            i = j
        else:
            j = i
            while j < n and s[j] not in white and s[j] not in delim:
                j += 1
            word = s[i:j].decode("latin-1")
            i = j if j > i else i + 1
            if re.fullmatch(r"[+\-]?(\d+\.?\d*|\.\d+)", word):
                tok = put(("num", float(word)))
            elif word == "BI":
                k = s.find(b"EI", i)
                i = k + 2 if k >= 0 else n
                continue
            else:
                tok = put(("op", word))
        if tok is not None:
            yield tok


# ================================================================================================ data

def hm(text: str) -> int:
    h, m = (int(x) for x in text.strip().split(":"))
    return 60 * h + m


def pm12(minutes: list[int]) -> list[int]:
    """Six times printed on a 12-hour clock without AM/PM: Asr, Maghrib and Isha are afternoon times."""
    return [m + (720 if i >= 3 and m < 720 else 0) for i, m in enumerate(minutes)]


def ramadan_uq(day: datetime.date) -> bool:
    span = UQ_RAMADAN.get(day.year)
    return bool(span) and datetime.date.fromisoformat(span[0]) <= day <= datetime.date.fromisoformat(span[1])


def check_uq_ramadan() -> str:
    found = []
    for year, rel in SA_TABLES.items():
        path = SA / rel
        if not path.exists():
            continue
        rows = json.loads(path.read_text(encoding="utf-8"))
        days = [r["date"][:10] for r in rows if r["hijriDate"]["month"] == 9]
        if (days[0], days[-1]) != UQ_RAMADAN[year]:
            raise SystemExit(f"Umm al-Qura Ramadan {year}: KACST's table says {days[0]}..{days[-1]}")
        found.append(str(year))
    return ", ".join(found) or "none at hand"


def ministry() -> dict[datetime.date, tuple[list[int], tuple[int, int, int]]]:
    """Date -> (six minutes, the API's own Hijri date)."""
    out = {}
    with open(QA / "qa" / "awqaf_2026.csv", encoding="utf-8", newline="") as fh:
        for r in csv.reader(fh):
            d, m, y = (int(x) for x in r[7].split("/"))
            out[datetime.date.fromisoformat(r[0])] = ([hm(x) for x in r[1:7]], (y, m, d))
    raw = json.loads((QA / "qa" / "awqaf_api_raw.json").read_text(encoding="utf-8"))["days"]
    for key, v in raw.items():
        day = datetime.date.fromisoformat(key)
        val = ([60 * t["time"]["hour"] + t["time"]["minutes"] for t in v["times"]],
               (v["hijriDate"]["year"], v["hijriDate"]["month"], v["hijriDate"]["day"]))
        if day in out and out[day] != val:
            raise SystemExit(f"API {day}: two different answers")
        out[day] = val
    for name in ("awq.csv", "awq2026.csv", "awq_misc.csv"):
        with open(QA / "qa_skeptic" / name, encoding="utf-8", newline="") as fh:
            for r in csv.reader(fh):
                day = datetime.date.fromisoformat(r[0])
                d, m, y = (int(x) for x in r[1].split("/"))
                val = ([hm(x) for x in r[2:8]], (y, m, d))
                if day in out and out[day] != val:
                    raise SystemExit(f"API {day}: {name} differs")
                out[day] = val
    return out


def qch_csv() -> dict[datetime.date, list[int]]:
    with open(QA / "qa" / "doha_all.csv", encoding="utf-8", newline="") as fh:
        return {datetime.date.fromisoformat(r[0]): pm12([hm(x) for x in r[1:7]]) for r in csv.reader(fh)}


def qch_pdf(path: Path, year: int) -> dict[datetime.date, tuple[list[int], list[int], list[int]]]:
    """A QCH wall calendar: date -> (Doha's six minutes, the three Fajr offsets, the three Maghrib offsets), each
    triple from right to left on the page. Dates follow each other from the first day page, each checked against
    the page's own weekday and month names."""
    pdf = Pdf(path.read_bytes())
    out = {}
    day = None
    for page in pdf.pages():
        items = pdf.items(page)
        texts = [t for *_, t in items]
        # The six times are the large ones (Friday pages add a small Jumu'a time).
        times = [(x, y, t.strip()) for x, y, s, t in items if re.fullmatch(r"\s*\d{1,2}:\d{2}\s*", t) and s >= 12]
        offs = [(x, y, t.strip()) for x, y, s, t in items if re.fullmatch(r"\s*(\d{1,2}\s*[+\-]|[+\-]\s*\d{1,2}|-)\s*", t)]
        split = [(x, y, t.strip()) for x, y, s, t in items if re.fullmatch(r"\s*\d{1,2}\s*", t) and 10 < s < 14]
        if len(times) < 6 and len(split) == 12:     # 1444: hours and minutes printed apart, on one row
            row = sorted(split)
            times = [(row[2 * i][0], row[2 * i][1], f"{row[2 * i][2]}:{row[2 * i + 1][2]}") for i in range(6)]
        if len(times) != 6 or len(offs) != 6:
            continue
        weekday = next((w for w in WEEKDAYS if any(w in t for t in texts)), None)
        month = next((m for m in MONTHS if any(re.search(rf"\b{m}\b", t) for t in texts)), None)
        if day is None:
            big = max((s, t) for x, y, s, t in items if re.fullmatch(r"\s*\d{1,2}\s*", t) and 40 < s < 62)
            day = datetime.date(year, MONTHS[month], int(big[1]))
        else:
            day += datetime.timedelta(days=1)
        if WEEKDAYS[day.weekday()] != weekday or (month and MONTHS[month] != day.month):
            raise SystemExit(f"{path.name}: page of {day} reads {weekday} {month}")
        rows = sorted({round(y) for _, y, _ in times}, reverse=True)
        if len(rows) == 2:                          # 1448: Dhuhr, sunrise, Fajr; then Isha, Maghrib, Asr
            top = [t for x, y, t in sorted(times) if round(y) == rows[0]]
            low = [t for x, y, t in sorted(times) if round(y) == rows[1]]
            dhuhr, sunrise, fajr = top
            isha, maghrib, asr = low
        else:                                       # 1444, 1446: Isha .. Fajr on one row, in stream order
            isha, maghrib, asr, dhuhr, sunrise, fajr = [t for _, _, t in times]
        doha = pm12([hm(t) for t in (fajr, sunrise, dhuhr, asr, maghrib, isha)])
        if not (doha[5] - doha[4] in (90, 91) and 60 < doha[1] - doha[0] < 100 and 660 < doha[2] < 760):
            raise SystemExit(f"{path.name} {day}: times out of order {doha}")
        orows = sorted({round(y) for _, y, _ in offs}, reverse=True)
        if len(orows) == 2:
            fajr_off = [o for o in sorted(offs, reverse=True) if round(o[1]) == orows[0]]
            magh_off = [o for o in sorted(offs, reverse=True) if round(o[1]) == orows[1]]
        else:
            ordered = sorted(offs, reverse=True)
            fajr_off, magh_off = ordered[:3], ordered[3:]

        def value(t: str) -> int:
            m = re.fullmatch(r"(\d+)\s*([+\-])|([+\-])\s*(\d+)", t)
            if not m:
                return 0
            v = int(m.group(1) or m.group(4))
            return -v if (m.group(2) or m.group(3)) == "-" else v
        out[day] = (doha, [value(t) for *_, t in fajr_off], [value(t) for *_, t in magh_off])
    return out


def cal_pages(folder: Path | None) -> dict[datetime.date, dict[str, list[int]]]:
    """QCH /cal pages: date -> cityId -> six minutes (its prayData; afternoon times on either clock)."""
    files = [(datetime.date(2026, 10, 8), QA / "qa" / "cal.html")]
    if folder is not None and folder.is_dir():
        for f in sorted(folder.glob("cal_*.html")):
            m = re.fullmatch(r"cal_(\d{4})(\d{2})(\d{2})\.html", f.name)
            if m:
                files.append((datetime.date(*(int(x) for x in m.groups())), f))
    out = {}
    for day, f in files:
        text = f.read_text(encoding="utf-8", errors="replace")
        data = json.loads(re.search(r"var prayData\s*=\s*(\[.*?\]);", text, re.S).group(1))
        out[day] = {str(r["cityId"]): pm12([hm(r[k]) for k in ("fajr", "shrouq", "thahr", "aser", "moghreb", "ishaa")])
                    for r in data}
    return out


# ================================================================================================ suns, fast times

def almanac_sun(jd: float) -> tuple[float, float, float]:
    """The Astronomical Almanac's low-precision sun (PrayTimes.js's constants), a candidate outside the engine."""
    d = jd - 2451545.0
    g = (357.529 + 0.98560028 * d) % 360
    q = (280.459 + 0.98564736 * d) % 360
    lam = q + 1.915 * math.sin(g * D2R) + 0.020 * math.sin(2 * g * D2R)
    e = 23.439 - 0.00000036 * d
    ra = math.atan2(math.cos(e * D2R) * math.sin(lam * D2R), math.cos(lam * D2R)) / D2R
    return (math.asin(math.sin(e * D2R) * math.sin(lam * D2R)) / D2R, ((q - ra + 180) % 360 - 180) * 4,
            1.00014 - 0.01671 * math.cos(g * D2R))


SUNS = dict(ref.SUN_MODELS)
SUNS.setdefault("ALMANAC", almanac_sun)
ENGINE = [m for m in ("MEEUS", "VSOP87", "TEXTBOOK", "ALMANAC") if m in ref.SUN_MODELS]
MODES = ("EVENT_INSTANT", "DAILY_0H_UT", "DAILY_10H_UT", "KACST")


def at_year(day: datetime.date, year: int | None) -> datetime.date:
    """The row of [day]'s month and day computed at a reference year (E5); None keeps the date."""
    if year is None:
        return day
    return datetime.date(year, day.month, 28 if (day.month, day.day) == (2, 29) else day.day)


_frozen: dict = {}


def frozen(model: str, mode: str, year: int | None, days, lat: float, lng: float) -> dict:
    """Per day: the sun (declination, equation of time) each event is computed with at a base point, and the noon
    declination; the closed forms below then move the point (an instant moves by seconds, the sun by nothing)."""
    key = (model, mode, year, tuple(days), round(lat, 2), round(lng, 2))
    if key in _frozen:
        return _frozen[key]
    out = {}
    fn = SUNS[model]
    for d in days:
        cyc = ref.Cycle(at_year(d, year))
        sun = ref.SunFunction(fn, mode, cyc.jd0)
        t0 = ref.transit(cyc, sun, lng)
        dn = sun(cyc.jd0 + t0 / 24)[0]
        asr = ref.atand(1 / (1 + ref.tand(abs(lat - dn))))
        ts = [ref.event(cyc, sun, lat, lng, -18.0, -1, t0), ref.event(cyc, sun, lat, lng, -0.8333, -1, t0), t0,
              ref.event(cyc, sun, lat, lng, asr, 1, t0), ref.event(cyc, sun, lat, lng, -0.8333, 1, t0)]
        out[d] = ([sun(cyc.jd0 + t / 24)[:2] for t in ts], dn)
    _frozen[key] = out
    return out


def transit_eot(model: str, mode: str, year: int | None, days, lng: float) -> dict:
    """Per day: the equation of time at Dhuhr's transit (stage 1 needs nothing else)."""
    out = {}
    fn = SUNS[model]
    for d in days:
        cyc = ref.Cycle(at_year(d, year))
        sun = ref.SunFunction(fn, mode, cyc.jd0)
        out[d] = sun(cyc.jd0 + ref.transit(cyc, sun, lng) / 24)[1]
    return out


def unrounded(fz_day, lat: float, lng: float, p: dict) -> list[float]:
    """Fajr (angle), sunrise, transit, Asr, sunset as local minutes (UTC+3), before any minute offset."""
    suns, dn = fz_day
    sl, cl = math.sin(lat * D2R), math.cos(lat * D2R)

    def ev(i: int, alt: float, side: int) -> float:
        decl, eot = suns[i]
        c = (math.sin(alt * D2R) - sl * math.sin(decl * D2R)) / (cl * math.cos(decl * D2R))
        return (15 - lng / 15 - eot / 60 + side * math.acos(max(-1.0, min(1.0, c))) / D2R / 15) * 60

    zen = abs(lat - dn)
    k = 2 if p["asr"] == "shadow2" else 1
    alt = math.atan(1 / (k + math.tan(zen * D2R))) / D2R
    if p["asr"] == "apparent":
        h0 = 90 - zen
        a_app = math.atan(1 / (k + math.tan((90 - (h0 + ref.bennett(h0, 1010.0, 10.0))) * D2R))) / D2R
        alt = a_app - ref.bennett(a_app, 1010.0, 10.0)
    return [ev(0, -p["fajr"], -1), ev(1, p["horizon"], -1), (15 - lng / 15 - suns[2][1] / 60) * 60, ev(3, alt, 1),
            ev(4, p["horizon"], 1)]


def rounded(u: float, mode: str) -> int:
    if mode == "UP":
        return math.ceil(u - 1e-5)
    if mode == "DOWN":
        return math.floor(u + 1e-5)
    return math.floor(u + 0.5)


def profile(**kw) -> dict:
    p = {"model": "MEEUS", "mode": "EVENT_INSTANT", "year": 2006, "fajr": 18.0, "fajr_off": -1, "dhuhr": 0, "asr_off": 0,
         "maghrib": 2, "ramadan": 1, "isha": 90, "horizon": -0.8333, "asr": "shadow", "rounding": "NEAREST"}
    p.update(kw)
    return p


def minutes(fz_day, lat, lng, p, day: datetime.date, ramadan: bool, zone_fajr: int = 0) -> list[int]:
    u = unrounded(fz_day, lat, lng, p)
    r = p["rounding"]
    fajr_off = p["fajr_off"] if day >= FAJR_CHANGE else 0
    qch = p.get("qch") and day >= QCH_CHANGE
    m = rounded(u[4] + p["maghrib"] + (p["ramadan"] if ramadan else 0), r)
    return [rounded(u[0] + fajr_off + zone_fajr, r), rounded(u[1], r), rounded(u[2] + p["dhuhr"] + (1 if qch else 0), r),
            rounded(u[3] + p["asr_off"] + (1 if qch else 0), r), m, m + p["isha"]]


def count(rows, lat, lng, p, events=range(6), per=False, base=None):
    """Exact minutes of rows [(day, published six, ramadan)] at a point, with the profile's reference year."""
    days = [d for d, _, _ in rows]
    b = base or (lat, lng)
    fz = frozen(p["model"], p["mode"], p["year"], days, b[0], b[1])
    n = [0] * 6
    for d, pub, ram in rows:
        got = minutes(fz[d], lat, lng, p, d, ram)
        for i in events:
            if pub[i] is not None:
                n[i] += got[i] == pub[i]
    return n if per else sum(n)


def stab(intervals):
    marks = sorted([(lo, 1) for lo, _ in intervals] + [(hi, -1) for _, hi in intervals], key=lambda m: (m[0], m[1]))
    best, cur, span = 0, 0, (math.nan, math.nan)
    for i, (x, s) in enumerate(marks):
        cur += s
        if cur > best:
            best, span = cur, (x, marks[i + 1][0] if i + 1 < len(marks) else x)
    return best, span


def grid_point(rows, centre, p, half=(0.06, 0.04), step=(0.0025, 0.0025), events=range(6), zone_fajr=0):
    """The grid cells with the most exact minutes: (count, the middle of those cells, their number)."""
    days = [d for d, _, _ in rows]
    fz = frozen(p["model"], p["mode"], p["year"], days, centre[0], centre[1])
    best, cells = -1, []
    ni, nj = int(round(half[0] / step[0])), int(round(half[1] / step[1]))
    for i in range(-ni, ni + 1):
        for j in range(-nj, nj + 1):
            lat, lng = centre[0] + i * step[0], centre[1] + j * step[1]
            n = 0
            for d, pub, ram in rows:
                got = minutes(fz[d], lat, lng, p, d, ram, zone_fajr)
                n += sum(got[k] == pub[k] for k in events if pub[k] is not None)
            if n > best:
                best, cells = n, [(lat, lng)]
            elif n == best:
                cells.append((lat, lng))
    return best, (sum(c[0] for c in cells) / len(cells), sum(c[1] for c in cells) / len(cells)), len(cells)


def refine(rows, centre, p, events=range(6), zone_fajr=0, coarse=(0.25, 0.25)):
    """Coarse then fine grid; the middle of the best fine cells."""
    n, c, _ = grid_point(rows, centre, p, coarse, (0.025, 0.025), events, zone_fajr)
    n, c, _ = grid_point(rows, c, p, (0.04, 0.04), (0.004, 0.004), events, zone_fajr)
    n, c, k = grid_point(rows, c, p, (0.008, 0.008), (0.001, 0.001), events, zone_fajr)
    return n, (round(c[0], 4), round(c[1], 4)), k


# ================================================================================================ scores

def method_of(p: dict, method_id: str) -> dict:
    revisions = [{"validFrom": FAJR_CHANGE.isoformat(), "change": {"offsets": {"FAJR": p["fajr_off"]}}}]
    if method_id == "qa_qch":
        revisions.append({"validFrom": QCH_CHANGE.isoformat(), "change": {"offsets": {"DHUHR": 1, "ASR": 1}}})
    offsets = {e: v for e, v in (("DHUHR", p["dhuhr"]), ("ASR", p["asr_off"]), ("MAGHRIB", p["maghrib"])) if v}
    horizon = {"type": "standard"} if p["horizon"] == -0.8333 else {"type": "fixed", "altitudeDeg": p["horizon"]}
    return common.method_json(
        id=method_id, fajr={"type": "angle", "degrees": p["fajr"]},
        isha={"type": "after", "minutes": p["isha"], "from": "MAGHRIB", "ramadan": None}, offsets=offsets,
        rounding={e: p["rounding"] for e in EV}, sunModel=p["model"],
        sunEvaluation={} if p["mode"] == "EVENT_INSTANT" else {e: p["mode"] for e in EV},
        asrShadow=2 if p["asr"] == "shadow2" else 1, asrSun="APPARENT" if p["asr"] == "apparent" else "GEOMETRIC",
        horizon={"sunrise": horizon, "sunset": horizon}, ramadanOffsets={"MAGHRIB": p["ramadan"]}, referenceYear=p["year"],
        ramadanDays="UMM_AL_QURA_COMPUTED", revisions=revisions,
        evidence={"kind": "FROM_TABLES", "source": "fit"})


def zone_of(key: str, computing: tuple[float, float], zone_fajr: int) -> dict:
    """The zone as the asset holds it: the town centre as its point, the fitted computing point as E6 points."""
    zid, _, _, lat, lng = PLACES[key]
    pt = common.point(*computing)
    town = common.point(lat, lng)
    extra = {} if pt == town else {"morningPoint": pt, "eveningPoint": pt}
    return common.zone_json(zid, lat, lng, offsets={"FAJR": float(zone_fajr)} if zone_fajr else {}, **extra)


def app_minutes(method: dict, zone: dict, day: datetime.date) -> list[int]:
    lat, lng = zone["point"]["latE5"] / 1e5, zone["point"]["lngE5"] / 1e5
    out = common.compute(common.case(method, lat, lng, TZ, day, zone=zone, country="QA", ramadan=ramadan_uq(day)))
    origin = ref.epoch_days(day) * 86400 - common.utc_offset(TZ, day)
    got = []
    for e in EV:
        if out[e][2] != "COMPUTED":
            raise ValueError(f"{zone['id']} {day} {e}: {out[e][2]}")
        got.append((out[e][1] - origin) // 60)
    return got


def score_rows(method: dict, zone: dict, rows, score: common.Score, events=range(6)) -> int:
    """Adds each row's published minutes (None: not published) against the app; returns the Ramadan days."""
    ram = 0
    for d, pub, _ in rows:
        got = app_minutes(method, zone, d)
        ram += ramadan_uq(d)
        for i in events:
            if pub[i] is not None:
                score.add(EV[i], d, got[i], pub[i])
    return ram


def merged(scores) -> common.Score:
    out = common.Score()
    for s in scores:
        for e in EV:
            out.exact[e] += s.exact[e]
            out.within1[e] += s.within1[e]
            out.total[e] += s.total[e]
            out.months[e] = [a + b for a, b in zip(out.months[e], s.months[e])]
        out.worst = max(out.worst, s.worst)
        out.misses += s.misses
    return out


def shares(score: common.Score) -> str:
    n, ex = sum(score.total.values()), sum(score.exact.values())
    if not n:
        return "no data"
    per = " ".join(f"{e[:3].lower()} {100 * score.exact[e] / score.total[e]:.1f}/{score.total[e]}" for e in EV if score.total[e])
    return f"{ex}/{n} exact ({100 * ex / n:.2f} %; {per}), within 1: {100 * sum(score.within1.values()) / n:.2f} %, max {score.worst}"


def tier_a(stats: dict) -> list[str]:
    """FitStats.passesTierA (9.5) for an E8 method, as the failures it finds."""
    failures = []
    for e in EV:
        if not any(stats["daysPerMonth"][e]):
            failures.append(f"no {e}")
            continue
        if stats["exact"][e] < 0.97:
            failures.append(f"{e} {stats['exact'][e]:.1%}")
        if stats["within1"][e] < 1.0:
            failures.append(f"{e} {stats['within1'][e]:.1%} within 1")
    if any(min(v) < 7 for v in stats["daysPerMonth"].values() if any(v)):
        failures.append("under 7 held-out days in some month")
    if stats["heldOutRamadanDays"] < 29:
        failures.append(f"{stats['heldOutRamadanDays']} held-out Ramadan days")
    return failures


def km(a, b) -> float:
    return math.hypot((a[0] - b[0]) * 111.2, (a[1] - b[1]) * 111.2 * math.cos(math.radians((a[0] + b[0]) / 2)))


# The validity guard's places of 8.8 (a side check): GeoNames cities1000 points, or approximate ones (~).
GUARD_PLACES = (("Al Shahaniya", 25.37088, 51.22264), ("Al Jumayliyah", 25.61068, 51.09108), ("Umm Bab", 25.21417, 50.80722),
                ("Al Ghuwayriyah", 25.82882, 51.24567), ("Ras Laffan ~", 25.92, 51.55), ("Khor Al Udaid ~", 24.62, 51.38),
                ("Sealine ~", 24.85, 51.50), ("Al Kharrara ~", 24.91, 51.06))


def guard_check(method: dict, zones: dict, step: int = 3) -> None:
    """Plan 6.5's comparison on every third day of 2026: the attached table T against the method at the place's own
    point R (unrounded, no zone), each time's lead beyond the table's own margin at its town point; 4 min allowed."""
    days = [datetime.date(2026, 1, 1) + datetime.timedelta(days=i) for i in range(0, 365, step)]

    def run(lat, lng, zone=None):
        return [common.compute(common.case(method, lat, lng, TZ, d, zone=zone, country="QA", ramadan=ramadan_uq(d)))
                for d in days]

    def leads(r_days, t_days):
        out = {e: [] for e in EV}
        for r, t in zip(r_days, t_days):
            for e in EV:
                lead = (r[e][0] - t[e][1]) / 60
                out[e].append(-lead if e == "SUNRISE" else lead)
        return out

    margins = {}
    for name, lat, lng in GUARD_PLACES:
        key = min(zones, key=lambda k: km((lat, lng), PLACES[k][3:5]))
        zone = zones[key]
        if key not in margins:
            t_days = run(PLACES[key][3], PLACES[key][4], zone)
            margins[key] = (t_days, {e: max(v) for e, v in leads(run(PLACES[key][3], PLACES[key][4]), t_days).items()})
        t_days, margin = margins[key]
        excess = {e: max(v) - margin[e] for e, v in leads(run(lat, lng), t_days).items()}
        worst = max(excess, key=excess.get)
        print(f"  {name:16s} -> {key:9s} {km((lat, lng), PLACES[key][3:5]):4.0f} km: worst {worst.lower()} "
              f"{excess[worst]:+.1f} min beyond the margin: {'keeps the table' if excess[worst] <= 4 else 'own point'}")


def span_of(days) -> tuple[datetime.date, datetime.date]:
    return min(days), max(days)


# ================================================================================================ main

def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--cal", type=Path, default=CAL_DIR, help="folder of more QCH /cal pages (cal_YYYYMMDD.html)")
    ap.add_argument("--no-guard", action="store_true", help="skip the validity-guard side check")
    args = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    started = time.time()

    print(f"Umm al-Qura Ramadan days checked against KACST's tables of {check_uq_ramadan()}")
    api = ministry()
    api26 = sorted(d for d in api if d.year == 2026)
    api_other = sorted(d for d in api if d.year != 2026)
    qch_doha = qch_csv()
    prints = {}
    for rel, year in QCH_PDFS:
        for d, v in qch_pdf(QA / rel, year).items():
            prints[d] = v
    cal = cal_pages(args.cal)
    for d, (doha, _, _) in prints.items():          # the print's Doha against the parsed CSV
        if d in qch_doha and qch_doha[d] != doha:
            raise SystemExit(f"QCH {d}: the PDF's Doha {doha} differs from doha_all.csv {qch_doha[d]}")
        qch_doha.setdefault(d, doha)
    for d, rows in cal.items():
        if d in qch_doha and qch_doha[d] != rows["3"]:
            raise SystemExit(f"/cal {d}: Doha {rows['3']} differs from QCH's table {qch_doha[d]}")
        qch_doha.setdefault(d, rows["3"])
    by_year = Counter(d.year for d in prints)
    print(f"Data: Ministry API {len(api)} days ({len(api26)} of 2026; others {Counter(d.year for d in api_other)}); "
          f"QCH Doha {len(qch_doha)} days; QCH prints with town offsets {dict(sorted(by_year.items()))}; /cal "
          f"{', '.join(str(d) for d in sorted(cal))}")

    # The API's Ramadan offset follows its own Hijri labels (Q3): its edge days are quirk days.
    def api_ramadan(d):
        return api[d][1][1] == 9
    quirk = {d for d in api if d.year in UQ_RAMADAN and api_ramadan(d) != ramadan_uq(d)}
    fit_rows = [(d, api[d][0], api_ramadan(d)) for d in api26]
    print(f"  API days whose Ramadan differs from Umm al-Qura's (quirk days, counted apart): {sorted(map(str, quirk))}")
    gaps = Counter(api[d][0][5] - api[d][0][4] for d in api)
    print(f"  Isha - Maghrib on the API's days: {dict(gaps)}")

    # Stage 1: Dhuhr windows by sun model, evaluation and reference year (2026's column).
    doha = PLACES["doha"]
    s1 = []
    for model in SUNS:
        years = range(1990, 2026) if model != "VSOP87" else (1998, 2002, 2006, 2010, 2014, 2018, 2022)
        for mode in MODES:
            for year in years:
                eot = transit_eot(model, mode, year, api26, doha[4])
                wins = []
                for d, pub, _ in fit_rows:
                    k = (15 - eot[d] / 60) * 60     # Dhuhr = k - 4 lng (minutes)
                    wins.append(((k - pub[2] - 0.5) / 4, (k - pub[2] + 0.5) / 4))
                n, (a, b) = stab(wins)
                s1.append((n, model, mode, year, (a + b) / 2))
    s1.sort(key=lambda r: (-r[0], r[3] != 2006))
    print("Stage 1, Dhuhr windows one longitude covers (of 365): " + ", ".join(
        f"{m}/{e}/{y} {n}" for n, m, e, y, _ in s1[:10]))

    # Stage 2: all six times at the reference year, for the best candidates of stage 1 and the plan's own.
    cands = []
    for n, m, e, y, lng in s1:
        if (m, e, y) not in cands and len(cands) < 8:
            cands.append((m, e, y))
    for m in ("MEEUS", "VSOP87"):
        if (m, "EVENT_INSTANT", 2006) not in cands:
            cands.append((m, "EVENT_INSTANT", 2006))
    s2 = {}
    for m, e, y in cands:
        p = profile(model=m, mode=e, year=y)
        n, c, k = refine(fit_rows, (doha[3], doha[4]), p, coarse=(0.15, 0.1))
        s2[(m, e, y)] = (n, c, k)
    ranked = sorted(s2.items(), key=lambda kv: -kv[1][0])
    print("Stage 2, all six times at the reference year (of 2190), Doha's point: " + "; ".join(
        f"{m}/{e}/{y} {n} at {c[0]:.4f} {c[1]:.4f} ({k} cells)" for (m, e, y), (n, c, k) in ranked[:8]))
    engine_best = next(kv for kv in ranked if kv[0][0] in ENGINE)
    (model, mode, year), (n2, point, _) = engine_best
    p = profile(model=model, mode=mode, year=year)
    years_scan = {y: refine(fit_rows, point, dict(p, year=y), coarse=(0.05, 0.05))[0] for y in range(year - 8, year + 13, 4)}
    print(f"  {model}/{mode} by reference year (of 2190, the point refitted): "
          + ", ".join(f"{y} {n}" for y, n in years_scan.items()))
    print(f"  shipped: {model}/{mode}, reference year {year}, Doha computed at {point[0]:.4f} N {point[1]:.4f} E "
          f"({km(point, (doha[3], doha[4])):.1f} km from the town centre); per time "
          f"{count(fit_rows, point[0], point[1], p, per=True)}")

    def at(q):
        return count(fit_rows, point[0], point[1], q)
    print("  checks at that point (minutes of 2190): "
          f"Maghrib + {', '.join(f'{v}:{at(dict(p, maghrib=v))}' for v in (1, 2, 3))}; "
          f"Fajr offset {', '.join(f'{v:+d}:{at(dict(p, fajr_off=v))}' for v in (-2, -1, 0))}; "
          f"Dhuhr {', '.join(f'{v:+d}:{at(dict(p, dhuhr=v))}' for v in (-1, 0, 1))}; "
          f"Asr {', '.join(f'{r}:{at(dict(p, asr=r))}' for r in ('shadow', 'apparent', 'shadow2'))}; "
          f"rounding {', '.join(f'{r}:{at(dict(p, rounding=r))}' for r in ('NEAREST', 'UP', 'DOWN'))}; "
          f"Ramadan + {', '.join(f'{v}:{at(dict(p, ramadan=v))}' for v in (0, 1))}")
    fz = frozen(model, mode, year, api26, point[0], point[1])
    fajr_w = []
    sl, cl = math.sin(point[0] * D2R), math.cos(point[0] * D2R)
    for d, pub, ram in fit_rows:
        decl, eot = fz[d][0][0]                 # the sun at Fajr's instant
        noon = (15 - point[1] / 15 - eot / 60) * 60 + p["fajr_off"]
        h1, h2 = (noon - pub[0] - 0.5) / 4, (noon - pub[0] + 0.5) / 4     # Fajr = noon - 4 H, kept in its minute
        dep = [-math.asin(max(-1.0, min(1.0, math.cos(h * D2R) * cl * math.cos(decl * D2R) + sl * math.sin(decl * D2R)))) / D2R
               for h in (h1, h2)]
        fajr_w.append(tuple(sorted(dep)))
    nf, (fa, fb) = stab(fajr_w)
    print(f"  Fajr angle window with the - 1: {fa:.3f}..{fb:.3f} deg ({nf}/365); horizon "
          + ", ".join(f"{h}: {at(dict(p, horizon=h))}" for h in (-0.80, -0.8333, -0.85)))
    misses = []
    for d, pub, ram in fit_rows:
        u = unrounded(fz[d], point[0], point[1], p)
        got = minutes(fz[d], point[0], point[1], p, d, ram)
        raw = [u[0] + p["fajr_off"], u[1], u[2], u[3], u[4] + p["maghrib"] + (p["ramadan"] if ram else 0)]
        for i in range(5):
            if got[i] != pub[i]:
                misses.append(f"{d:%m-%d} {EV[i][:3].lower()} {got[i] - pub[i]:+d} (.{int(100 * (raw[i] % 1)):02d})")
    print(f"  in-sample misses at the reference year (computed - published, the fraction of the computed minute): "
          + ", ".join(misses))

    # Held out, qa_awqaf's Doha: a month-split of 2026, then the API's other app-year days.
    mth = {"qa_awqaf": method_of(p, "qa_awqaf"), "qa_qch": method_of(dict(p, qch=True), "qa_qch")}
    parts, fold_pts = [], {}
    ram_days = 0
    for keep in (1, 0):
        part = [r for r in fit_rows if r[0].month % 2 == keep]
        _, c, _ = refine(part, point, p, coarse=(0.05, 0.05))
        fold_pts[keep] = c
        s = common.Score()
        test = [(d, api[d][0], None) for d in api26 if d.month % 2 != keep and d not in quirk]
        ram_days += sum(ramadan_uq(d) for d in api26 if d.month % 2 != keep)
        score_rows(mth["qa_awqaf"], zone_of("doha", c, 0), test, s)
        parts.append(s)
    split_score = merged(parts)
    other = [(d, api[d][0], None) for d in api_other if d.year in APP_YEARS and d not in quirk]
    other_score = common.Score()
    ram_days += score_rows(mth["qa_awqaf"], zone_of("doha", point, 0), other, other_score)
    print(f"  folds: odd months -> {fold_pts[1]}, even months -> {fold_pts[0]}")
    print(f"Held out, qa_awqaf Doha, month-split of 2026 (as the app shows it, computed for 2026): {shares(split_score)}")
    print(f"  the API's {len(other)} days of 2020-2025 and 2027: {shares(other_score)}")
    at_ref = common.Score()
    for d, pub, _ in fit_rows:
        got = minutes(fz[d], point[0], point[1], p, d, api_ramadan(d))
        for i in range(6):
            at_ref.add(EV[i], d, got[i], pub[i])
    print(f"  in-sample at the reference year {year} (search code): {shares(at_ref)}")
    side = [(d, api[d][0]) for d in api if d.year not in APP_YEARS]
    print(f"  side check, API rows before 2020 ({', '.join(str(d) for d, _ in sorted(side))}): " + "; ".join(
        f"{d} " + " ".join(f"{g - w:+d}" for g, w in zip(app_minutes(mth['qa_awqaf'], zone_of('doha', point, 0), d), pub))
        for d, pub in sorted(side)))
    quirk_score = common.Score()
    score_rows(mth["qa_awqaf"], zone_of("doha", point, 0), [(d, api[d][0], None) for d in sorted(quirk)], quirk_score)
    print(f"  quirk days: {shares(quirk_score)}")

    # qa_qch's Doha: every QCH day is held out (nothing was fitted on them).
    qch_rows = [(d, qch_doha[d], None) for d in sorted(qch_doha)]
    qch_score = common.Score()
    qch_ram = score_rows(mth["qa_qch"], zone_of("doha", point, 0), qch_rows, qch_score)
    print(f"Held out, qa_qch Doha, {len(qch_rows)} QCH days ({', '.join(f'{y}: {n}' for y, n in sorted(Counter(d.year for d in qch_doha).items()))}): "
          f"{shares(qch_score)}; Ramadan days {qch_ram}")
    same = sum(1 for d in qch_doha if d in api and all(
        a == b + (1 if (i in (2, 3) and d >= QCH_CHANGE) else 0) for i, (a, b) in enumerate(zip(qch_doha[d], api[d][0]))))
    both = sum(1 for d in qch_doha if d in api)
    print(f"  QCH = the API (+ 1 on Dhuhr and Asr from {QCH_CHANGE}) on {same} of the {both} days both publish")

    # Stage 3: the printed towns. Columns right to left; the left one is Al Shamal (the smallest minutes).
    p_town = dict(p, year=None)
    print_rows = {}
    for d in sorted(prints):
        dh, fo, mo = prints[d]
        print_rows[d] = (dh, fo, mo)
    def town_rows(col: int, days):
        out = []
        for d in days:
            dh, fo, mo = print_rows[d]
            pub = [dh[0] + fo[col], None, None, None, dh[4] + mo[col], None]
            out.append((d, pub, ramadan_uq(d)))
        return out
    days48 = [d for d in print_rows if d.year == 2026]
    days_old = [d for d in print_rows if d.year != 2026]
    assignments = {}
    for perm in (("abusamra", "dukhan", "shamal"), ("dukhan", "abusamra", "shamal")):
        total, fits = 0, {}
        for col, key in enumerate(perm):
            centre = (PLACES[key][3], PLACES[key][4])
            n, c, k = refine(town_rows(col, days48), centre, p_town, events=(0, 4), zone_fajr=1, coarse=(0.3, 0.3))
            fits[key] = (n, c, k, col)
            total += n
        assignments[perm] = (total, fits)
    perm = max(assignments, key=lambda k: assignments[k][0])
    print("Stage 3, the print's columns (right to left), printed Fajr and Maghrib minutes of 1448 reproduced: " + "; ".join(
        f"{'/'.join(k)} {v[0]}/{6 * len(days48)}" for k, v in assignments.items()) + f" -> {'/'.join(perm)}")
    towns = assignments[perm][1]
    for key in PRINT_TOWNS:
        n, c, k, col = towns[key]
        print(f"  {key}: column {col}, {n}/{2 * len(days48)} printed Fajr and Maghrib minutes of 1448 at {c[0]:.4f} N "
              f"{c[1]:.4f} E ({k} cells, {km(c, PLACES[key][3:5]):.1f} km from the town)")

    # Mesaieed and Halul: the town's latitude, the longitudes that keep every /cal minute (QCH's: Dhuhr and Asr + 1).
    p_cal = dict(p_town, fajr_off=0, qch=True)

    def cal_rows(key, days):
        return [(d, cal[d][PLACES[key][0]], ramadan_uq(d)) for d in days if PLACES[key][0] in cal[d]]
    for key in ("mesaieed", "halul"):
        lat = PLACES[key][3]
        rows = cal_rows(key, sorted(cal))
        best = []
        for j in range(-400, 401):
            lng = PLACES[key][4] + j * 0.001
            best.append((count(rows, lat, lng, p_cal, base=(lat, PLACES[key][4])), round(lng, 3)))
        top = max(b[0] for b in best)
        lngs = [b[1] for b in best if b[0] == top]
        runs, run = [], [lngs[0]]
        for x in lngs[1:]:
            if x - run[-1] < 0.0015:
                run.append(x)
            else:
                runs.append(run)
                run = [x]
        runs.append(run)
        near = min(runs, key=lambda r: min(abs(x - PLACES[key][4]) for x in r))
        c = (lat, round((near[0] + near[-1]) / 2, 4))
        towns[key] = (top, c, len(near), None)
        print(f"  {key}: {top}/{6 * len(rows)} /cal minutes at {c[0]:.4f} N {c[1]:.4f} E (longitudes {near[0]:.3f}.."
              f"{near[-1]:.3f}; {km(c, PLACES[key][3:5]):.1f} km from the town; at the town {count(rows, lat, PLACES[key][4], p_cal)})")

    # The 1444 and 1446 prints (2022, 2025) predate 1 Muharram 1447: their town minutes follow another rule (a side
    # check, outside the records).
    for key in PRINT_TOWNS:
        n, c, k, col = towns[key]
        rows = town_rows(col, days_old)
        per = count(rows, c[0], c[1], dict(p_town, fajr_off=0), events=(0, 4), per=True)
        diff = Counter()
        fzo = frozen(model, mode, None, days_old, c[0], c[1])
        for d, pub, ram in rows:
            got = minutes(fzo[d], c[0], c[1], dict(p_town, fajr_off=0), d, ram)
            diff[got[4] - pub[4]] += 1
        print(f"  side check, {key} on the 1444 and 1446 prints ({len(rows)} days): Fajr {per[0]}, Maghrib {per[4]} exact; "
              f"computed - printed Maghrib {dict(sorted(diff.items()))}")

    # Held out for the towns (both profiles): month-split of 1448 and the /cal days.
    town_scores = {a: {} for a in mth}
    town_samples = {}
    for key in PRINT_TOWNS:
        n, c, k, col = towns[key]
        scores = {a: [] for a in mth}
        for keep in (1, 0):
            fit_days = [d for d in days48 if d.month % 2 == keep]
            test_days = [d for d in days48 if d.month % 2 != keep]
            _, cc, _ = refine(town_rows(col, fit_days), c, p_town, events=(0, 4), zone_fajr=1, coarse=(0.05, 0.05))
            for a in mth:
                s = common.Score()
                score_rows(mth[a], zone_of(key, cc, 1), town_rows(col, test_days), s, events=(0, 4))
                scores[a].append(s)
        for a in mth:
            s = common.Score()
            events = range(6) if a == "qa_qch" else (0, 1, 4, 5)
            score_rows(mth[a], zone_of(key, c, 1), cal_rows(key, sorted(cal)), s, events=events)
            scores[a].append(s)
            town_scores[a][key] = merged(scores[a])
        town_samples[key] = (days48, days_old)
    for key in ("mesaieed", "halul"):
        n, c, k, _ = towns[key]
        rows = cal_rows(key, sorted(cal))
        for a in mth:
            s = common.Score()
            events = range(6) if a == "qa_qch" else (0, 1, 4, 5)
            if len(rows) >= 2:                  # leave one day out: the longitude from the other days
                for i, row in enumerate(rows):
                    rest = rows[:i] + rows[i + 1:]
                    lat = PLACES[key][3]
                    best = max(((count(rest, lat, PLACES[key][4] + j * 0.001, p_cal), -abs(j)), j)
                               for j in range(-400, 401))
                    score_rows(mth[a], zone_of(key, (lat, PLACES[key][4] + best[1] * 0.001), 1), [row], s, events=events)
            town_scores[a][key] = s
    for a in mth:
        print(f"Held out, {a} towns: " + "; ".join(f"{key} {shares(town_scores[a][key])}" for key in PRINT_TOWNS + ("mesaieed", "halul")))

    # The assets.
    doha_named = [{"centre": common.point(doha[3], doha[4]), "catalogueIds": [], "areaKm": 0.0}] + [
        {"centre": common.point(lat, lng), "catalogueIds": [], "areaKm": NAMED_AREA_KM} for _, _, lat, lng in DOHA_NAMED]
    for a in mth:
        zones, all_scores = [], []
        if a == "qa_awqaf":
            doha_score = merged([split_score, other_score])
            fitted = [common.sample(MINISTRY_SOURCE + f"_2026_at_{year}", api26[0], api26[-1], len(api26), 1)]
            held = [common.sample(MINISTRY_SOURCE + "_2026_month_split", api26[0], api26[-1], len(api26), 1, month_split=True),
                    common.sample(MINISTRY_SOURCE + "_2020_2027", *span_of([d for d, _, _ in other]), len(other), 1)]
            doha_stats = common.fit_stats(fitted, held, doha_score, ram_days, len([d for d in quirk if d.year in APP_YEARS]))
            doha_stats["maxDiffMin"] = max(doha_score.worst, quirk_score.worst)
        else:
            doha_score = qch_score
            fitted = [common.sample(MINISTRY_SOURCE + f"_2026_at_{year}", api26[0], api26[-1], len(api26), 1)]
            held = [common.sample(QCH_SOURCE + "_doha", *span_of(qch_doha), len(qch_doha), 1)]
            doha_stats = common.fit_stats(fitted, held, doha_score, qch_ram, 0)
        all_scores.append(doha_score)
        doha_held = list(held)
        zone = {"id": doha[0], "nameAr": doha[1], "point": common.point(doha[3], doha[4]), "namedPlaces": doha_named,
                "fit": doha_stats}
        if common.point(*point) != zone["point"]:
            zone["morningPoint"] = zone["eveningPoint"] = common.point(*point)
        zones.append(zone)
        for key in PRINT_TOWNS + ("mesaieed", "halul"):
            zid, name, gid, lat, lng = PLACES[key]
            n, c, k, _ = towns[key]
            s = town_scores[a][key]
            all_scores.append(s)
            if key in PRINT_TOWNS:
                fitted = [common.sample(QCH_SOURCE + "_print_1448", *span_of(days48), len(days48), 1)]
                held = [common.sample(QCH_SOURCE + "_print_1448_month_split", *span_of(days48), len(days48), 1, month_split=True),
                        common.sample(QCH_SOURCE + "_cal", *span_of(cal), len(cal), 1)]
            else:
                fitted = [common.sample(QCH_SOURCE + "_cal", *span_of(cal), len(cal), 1)]
                held = [common.sample(QCH_SOURCE + "_cal_leave_one_out", *span_of(cal), len(cal), 1)] if len(cal) >= 2 else []
            ram = sum(ramadan_uq(d) for d in (days48 if key in PRINT_TOWNS else []))
            z = {"id": zid, "nameAr": name, "point": common.point(lat, lng), "offsets": {"FAJR": 1.0},
                 "namedPlaces": [{"centre": common.point(lat, lng), "catalogueIds": [], "areaKm": 0.0}],
                 "fit": common.fit_stats(fitted, held, s, ram, 0)}
            if common.point(*c) != z["point"]:
                z["morningPoint"] = z["eveningPoint"] = common.point(*c)
            zones.append(z)
        prof = merged(all_scores)
        prof_stats = common.fit_stats(
            [common.sample(MINISTRY_SOURCE + f"_2026_at_{year}", api26[0], api26[-1], len(api26), 1),
             common.sample(QCH_SOURCE + "_towns_print_1448_cal", *span_of(days48 + list(cal)), len(set(days48) | set(cal)), 5)],
            doha_held + [common.sample(QCH_SOURCE + "_towns", *span_of(days48 + list(cal)), len(set(days48) | set(cal)), 5)],
            prof, ram_days if a == "qa_awqaf" else qch_ram, len(quirk) if a == "qa_awqaf" else 0)
        path = common.write_asset(a, a, "QA", "LISTED_PLACES", zones, fit=prof_stats, named_first=True,
                                  source=("Qatar's Ministry of Awqaf (Doha's perpetual table, as printed: reference year 2006) "
                                          "and Qatar Calendar House's places" if a == "qa_awqaf" else
                                          "Qatar Calendar House's tables (Doha's perpetual table, as printed: reference year "
                                          "2006) and its places"))
        verdicts = {z["id"]: tier_a(z["fit"]) for z in zones}
        print(f"Wrote {path.relative_to(common.ROOT).as_posix()}; tier A: " + "; ".join(
            f"{z['id']} {'passes' if not verdicts[z['id']] else 'fails (' + ', '.join(verdicts[z['id']][:3]) + ')'}" for z in zones))
    if not args.no_guard:
        print("Validity guard, 2026, every third day (side check, qa_awqaf):")
        guard_zones = {"doha": zone_of("doha", point, 0)}
        for key in PRINT_TOWNS + ("mesaieed", "halul"):
            guard_zones[key] = zone_of(key, towns[key][1], 1)
        guard_check(mth["qa_awqaf"], guard_zones)
    print(f"Profile: {model}/{mode}, reference year {year}; Fajr {p['fajr']} deg {p['fajr_off']:+d} from {FAJR_CHANGE}, "
          f"Maghrib + {p['maghrib']} (+ {p['ramadan']} Ramadan), Isha = Maghrib + {p['isha']}, {p['rounding']}; "
          f"done in {time.time() - started:.0f} s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
