#!/usr/bin/env python3
"""
Checks `sd_fitted`, Sudan's fitted profile (plan 5.6, 8.5, 8.7, A3; milestone AM2, 9.4). The method runs at the user's
point anywhere in Sudan (exact coordinates, plan 8.7), so there is no official-zone asset; this script prints the
profile's checks.

The plan fixes the method (A3, owner, 2026-10-11): 18 / 18, Dhuhr and Maghrib + 0, nearest rounding, the standard
horizon (ICOP's Sudan preset, 8.5, and the dataset's two mosques), at the user's coordinates. Nothing is left to fit,
so no value is fitted here.

The Islamic Fiqh Academy's printed tables are not online (8.1, 9.3, 12). Everything below is evidence only (A3, 5.6;
tier C of 9.5), read at run time and never copied:
A. The owner's mosque dataset (mosque-dataset/mosques.db, read-only), the plan's own check: per calendar and time,
   the profile at the mosque's own point, and windows (9.4: a published minute bounds the unrounded time to a minute
   under nearest rounding) for the Fajr and Isha angles and the whole-minute offsets of sunrise, Dhuhr, Asr and Maghrib.
B. research-2026-10-08/SD-SO-DJ-KM/sd/web/mashhad.html: a Sudanese news site's "Ramadan 1447 imsakiya ... issued by
   the Islamic Fiqh Academy" (17 February 2026), 18 state tables of Fajr, Dhuhr, Asr, Maghrib and Isha for 2026-02-18
   to 03-16 (Khartoum to 03-19). Checked at the state capitals (approximate points below, not catalogue rows): the
   profile, Umm al-Qura's rule as online calculators apply it (18.5 deg, Isha = Maghrib + 90 with no Ramadan change),
   Dhuhr against transit per state, and Khartoum's table under that rule at the best point of a small grid.
C. research-2026-10-08/SD-SO-DJ-KM/sd/scripts/dhuhr2002.py: the Dhuhr column, 1 January to 30 March, of the Khartoum
   State ministry's 2002 perpetual table (SUST master's thesis, 2016, pp. 102-103, as transcribed there), at UTC+3,
   the clock of 2002: Dhuhr = transit + n at Khartoum.
The two 2023 imsakiya images in sd/web/img are not machine-readable; README-part.md quotes a few of their minutes.

Every score goes through common.compute (the reference engine).

Usage: python scripts/prayer_formula/fits/sd_fitted/fit.py
"""

from __future__ import annotations

import datetime
import html
import math
import re
import sqlite3
import statistics
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import common  # noqa: E402

ref = common.ref
EVENTS = common.EVENTS
FIVE = ("FAJR", "DHUHR", "ASR", "MAGHRIB", "ISHA")
AUTHORITY = "sd_fitted"
COUNTRY = "SD"
TZ = "Africa/Khartoum"                     # UTC+2 since 2017-11-01
TZ_2002 = "Etc/GMT-3"                      # UTC+3, Sudan's clock from 2000 to 2017
YEAR = 2026                                # the dataset's rows carry no year: read as 2026, 29 February dropped (5.6)
ANGLES = {"FAJR": 18.0, "ISHA": 18.0}      # plan A3, 8.7
DATASET = common.LOCAL_DATA.parent / "mosque-dataset" / "mosques.db"
LOCAL = common.LOCAL_DATA / "research-2026-10-08" / "SD-SO-DJ-KM" / "sd"
PAGE = LOCAL / "web" / "mashhad.html"
DHUHR_2002 = LOCAL / "scripts" / "dhuhr2002.py"
KHARTOUM = (15.5007, 32.5599)
# State capitals, approximate (public geography, for evidence B only).
CAPITALS = {
    "الخرطوم": ("Khartoum", *KHARTOUM), "شمال كردفان": ("El Obeid", 13.1843, 30.2167),
    "الشمالية": ("Dongola", 19.1698, 30.4766), "كسّلا": ("Kassala", 15.4510, 36.4000),
    "النيل الأزرق": ("Ed Damazin", 11.7891, 34.3592), "شمال دارفور": ("El Fasher", 13.6279, 25.3494),
    "جنوب دارفور": ("Nyala", 12.0489, 24.8807), "جنوب كردفان": ("Kadugli", 11.0111, 29.7183),
    "الجزيرة": ("Wad Madani", 14.4012, 33.5199), "النيل الأبيض": ("Rabak", 13.1801, 32.7400),
    "نهر النيل": ("Ed Damer", 17.5928, 33.9592), "البحر الأحمر": ("Port Sudan", 19.6158, 37.2164),
    "القضارف": ("Gedaref", 14.0349, 35.3834), "سنّار": ("Sinja", 13.1500, 33.9333),
    "غرب دارفور": ("El Geneina", 13.4525, 22.4472), "وسط دارفور": ("Zalingei", 12.9096, 23.4741),
    "شرق دارفور": ("Ed Daein", 11.4610, 26.1283), "غرب كردفان": ("El Fula", 11.7333, 28.3500),
}


def profile(evaluation: str | None = None) -> dict:
    return common.method_json(
        id=AUTHORITY, fajr={"type": "angle", "degrees": ANGLES["FAJR"]}, isha={"type": "angle", "degrees": ANGLES["ISHA"]},
        sunEvaluation={} if evaluation in (None, "EVENT_INSTANT") else {"ASR": evaluation},
        evidence={"kind": "APPROXIMATE", "source": "Two mosques in the dataset; ICOP's preset"})


def calculators_umm_al_qura() -> dict:
    """18.5 deg, Isha = Maghrib + 90 with no Ramadan change, nearest: Umm al-Qura as online calculators apply it."""
    return common.method_json(id="calculators_uq", fajr={"type": "angle", "degrees": 18.5},
                              isha={"type": "after", "minutes": 90, "from": "MAGHRIB", "ramadan": None},
                              evidence={"kind": "APPROXIMATE", "source": "check"})


def hhmm(text: str) -> int:
    h, m = text.strip().split(":")[:2]
    return int(h) * 60 + int(m)


def clock(minute: int) -> str:
    return f"{minute // 60:02d}:{minute % 60:02d}"


def local_minute(epoch: float, day: datetime.date, zone: str = TZ) -> int:
    return int(((epoch + common.utc_offset(zone, day)) % 86400) // 60)


def computed(method: dict, lat: float, lng: float, days, zone: str = TZ) -> dict:
    """Per day: per event, (unrounded epoch seconds, rounded local minute) at the place's own point."""
    out = {}
    for day in days:
        r = common.compute(common.case(method, lat, lng, zone, day, country=COUNTRY))
        out[day] = {e: (r[e][0], local_minute(r[e][1], day, zone)) for e in EVENTS}
    return out


class Tally:
    def __init__(self):
        self.n, self.exact, self.w1, self.worst = Counter(), Counter(), Counter(), Counter()

    def add(self, event: str, diff: int):
        self.n[event] += 1
        self.exact[event] += diff == 0
        self.w1[event] += abs(diff) <= 1
        self.worst[event] = max(self.worst[event], abs(diff))

    def line(self, events=EVENTS) -> str:
        return " | ".join(f"{e[:4]} {100 * self.exact[e] / self.n[e]:5.1f}/{100 * self.w1[e] / self.n[e]:5.1f} max {self.worst[e]}"
                          for e in events if self.n[e])


def best_cover(intervals: list[tuple[float, float]]) -> tuple[int, float, float]:
    """(count, low, high): the most half-open intervals covering one value, and that best plateau."""
    points = sorted([(a, 1) for a, b in intervals if a < b] + [(b, -1) for a, b in intervals if a < b])
    best, cur, lo, hi = 0, 0, math.nan, math.nan
    for i, (x, d) in enumerate(points):
        cur += d
        nxt = points[i + 1][0] if i + 1 < len(points) else x
        if cur > best and nxt > x:
            best, lo, hi = cur, x, nxt
    return best, lo, hi


def depression(day: datetime.date, lat: float, lng: float, t: float) -> float:
    """The sun's depression at instant t (MEEUS, at the event instant); U = D at UTC+2 in Sudan."""
    cyc = ref.Cycle(day)
    sun = ref.SunFunction(ref.meeus_sun, "EVENT_INSTANT", cyc.jd0)
    return -ref.altitude(cyc, sun, lat, lng, cyc.hours(t))


def windows(rows: dict, comp: dict, lat: float, lng: float) -> str:
    """The angles and whole-minute offsets most windows cover, against the plan's (18, 18, 0)."""
    out = []
    pub = {day: {e: common.local_to_epoch(TZ, day, clock(row[e])) for e in EVENTS} for day, row in rows.items()}
    for e in ("FAJR", "ISHA"):
        ivs = []
        for day in rows:
            a, b = depression(day, lat, lng, pub[day][e] - 30), depression(day, lat, lng, pub[day][e] + 30)
            ivs.append((min(a, b), max(a, b)))
        n, lo, hi = best_cover(ivs)
        plan = sum(1 for a, b in ivs if a <= ANGLES[e] < b)
        out.append(f"{e[:4]} angle {(lo + hi) / 2:.2f} ({n}; {ANGLES[e]:g}: {plan})")
    for e in ("SUNRISE", "DHUHR", "ASR", "MAGHRIB"):
        ivs = [((pub[day][e] - 30 - comp[day][e][0]) / 60, (pub[day][e] + 30 - comp[day][e][0]) / 60) for day in rows]
        counts = {v: sum(1 for a, b in ivs if a <= v < b) for v in range(-3, 4)}
        best = max(counts, key=lambda v: (counts[v], -abs(v)))
        out.append(f"{e[:4]} {best:+d} ({counts[best]}; 0: {counts[0]})")
    return ", ".join(out) + f" of {len(rows)} days"


# ---- A. the owner's mosque dataset -----------------------------------------------------------------------------

def dataset_calendars() -> list[tuple[int, float, float, dict]] | None:
    if not DATASET.exists():
        return None
    con = sqlite3.connect(f"file:{DATASET.as_posix()}?mode=ro&immutable=1", uri=True)
    out = []
    for key, lat, lng in con.execute("select mosque_key, latitude, longitude from mosques where countryCode = ? "
                                     "order by mosque_key", (COUNTRY,)).fetchall():
        rows = {}
        for mo, da, *t in con.execute("select month, day, fajr, sunrise, dhuhr, asr, maghrib, isha from calendar_days "
                                      "where mosque_key = ?", (key,)):
            if not (mo == 2 and da == 29):
                rows[datetime.date(YEAR, mo, da)] = dict(zip(EVENTS, (hhmm(x) for x in t)))
        out.append((key, lat, lng, rows))
    con.close()
    return out


def section_a(days: list[datetime.date]) -> None:
    cals = dataset_calendars()
    if cals is None:
        print(f"A. no dataset at {DATASET}: skipped")
        return
    print(f"A. the owner's mosque dataset (evidence only, tier C of 9.5): {len(cals)} calendars")
    total = Tally()
    for key, lat, lng, rows in cals:
        comp = computed(profile(), lat, lng, days)
        tally = Tally()
        for day, row in rows.items():
            for e in EVENTS:
                diff = row[e] - comp[day][e][1]
                tally.add(e, diff)
                total.add(e, diff)
        held = {e: Counter(row[e] for row in rows.values()).most_common(1)[0] for e in ("MAGHRIB", "ISHA")}
        print(f"  calendar at {lat:.3f}, {lng:.3f}, exact/within 1 (%): {tally.line()}")
        print(f"    windows: {windows(rows, comp, lat, lng)}")
        print("    most frequent minute: " + ", ".join(f"{e[:4]} {clock(m)} on {n} days" for e, (m, n) in held.items()))
    print(f"  check, both calendars ({total.n['FAJR']} days; held out: nothing is fitted here, but the plan read 18 / 18 "
          f"from these calendars, A3):")
    print("    " + total.line())


# ---- B. the news site's 2026 Ramadan imsakiya ------------------------------------------------------------------

def page_tables() -> list[tuple[str, list[tuple[datetime.date, dict]]]]:
    text = PAGE.read_text(encoding="utf-8", errors="replace")
    out = []
    for m in re.finditer(r"<table>(.*?)</table>", text, flags=re.S):
        heads = re.findall(r"<h[2-6][^>]*>(.*?)</h[2-6]>", text[:m.start()], flags=re.S)
        head = html.unescape(re.sub(r"<[^>]+>", "", heads[-1])) if heads else ""
        state = next((s for s in CAPITALS if s in head), None)
        rows = []
        for tr in re.findall(r"<tr>(.*?)</tr>", m.group(1), flags=re.S):
            cells = [html.unescape(re.sub(r"<[^>]+>", "", c)).strip() for c in re.findall(r"<td>(.*?)</td>", tr, flags=re.S)]
            if len(cells) < 7:
                continue
            dd, mm, yy = re.split(r"\D+", cells[1])[:3]
            rows.append((datetime.date(int(yy), int(mm), int(dd)), dict(zip(FIVE, (hhmm(x) for x in cells[2:7])))))
        if state and rows:
            out.append((state, rows))
    return out


def section_b() -> None:
    if not PAGE.exists():
        print(f"B. no page at {PAGE}: skipped")
        return
    tables = page_tables()
    rows_total = sum(len(r) for _, r in tables)
    gaps = Counter(row["ISHA"] - row["MAGHRIB"] for _, rows in tables for _, row in rows)
    print(f"B. the news site's 'Ramadan 1447 imsakiya issued by the Islamic Fiqh Academy' (evidence only): {len(tables)} "
          f"state tables, {rows_total} days, {min(d for _, r in tables for d, _ in r)} to {max(d for _, r in tables for d, _ in r)}")
    print(f"  Isha - Maghrib: {dict(gaps)}")
    ours, theirs, dhuhr = Tally(), Tally(), []
    for state, rows in tables:
        name, lat, lng = CAPITALS[state]
        days = [d for d, _ in rows]
        a = computed(profile(), lat, lng, days)
        b = computed(calculators_umm_al_qura(), lat, lng, days)
        for day, row in rows:
            for e in FIVE:
                ours.add(e, row[e] - a[day][e][1])
                theirs.add(e, row[e] - b[day][e][1])
        transit_local = [(a[d]["DHUHR"][0] + common.utc_offset(TZ, d)) % 86400 / 60 for d in days]
        dhuhr.append((statistics.median(row["DHUHR"] - t for (_, row), t in zip(rows, transit_local)), name))
    print(f"  sd_fitted at the capitals, exact/within 1 (%): {ours.line(FIVE)}")
    print(f"  18.5 deg and Isha = Maghrib + 90 at the capitals: {theirs.line(FIVE)}")
    dhuhr.sort()
    print("  published Dhuhr - transit at the capital, median (min): " + ", ".join(f"{n} {x:+.1f}" for x, n in dhuhr))
    khartoum = next(rows for state, rows in tables if CAPITALS[state][0] == "Khartoum")
    days = [d for d, _ in khartoum]

    def exact_at(lat: float, lng: float) -> int:
        c = computed(calculators_umm_al_qura(), lat, lng, days)
        return sum(1 for d, row in khartoum for e in FIVE if row[e] == c[d][e][1])

    print("  Khartoum's table under 18.5 deg / Maghrib + 90, grid search for its point ...")
    best = max(((exact_at(KHARTOUM[0] + i / 10, KHARTOUM[1] + j / 10), KHARTOUM[0] + i / 10, KHARTOUM[1] + j / 10)
                for i in range(-4, 5) for j in range(-4, 5)))
    best = max([best] + [(exact_at(best[1] + i / 40, best[2] + j / 40), best[1] + i / 40, best[2] + j / 40)
                         for i in range(-2, 3) for j in range(-2, 3)])
    print(f"    {best[0]} of {5 * len(days)} minutes at {best[1]:.3f} N {best[2]:.3f} E "
          f"(at the city centre: {exact_at(*KHARTOUM)})")


# ---- C. the 2002 ministry table's Dhuhr column -----------------------------------------------------------------

def section_c() -> None:
    if not DHUHR_2002.exists():
        print(f"C. no transcription at {DHUHR_2002}: skipped")
        return
    blocks = re.findall(r'\("(\d\d)-(\d\d)","(\d\d)-(\d\d)","(\d\d:\d\d)"\)', DHUHR_2002.read_text(encoding="utf-8"))
    method = profile()
    ivs, exact = [], Counter()
    for m0, d0, m1, d1, value in blocks:
        day, last = datetime.date(YEAR, int(m0), int(d0)), datetime.date(YEAR, int(m1), int(d1))
        while day <= last:
            r = common.compute(common.case(method, *KHARTOUM, TZ_2002, day, country=COUNTRY))
            transit = r["DHUHR"][0]
            pub = common.local_to_epoch(TZ_2002, day, value)
            ivs.append(((pub - 30 - transit) / 60, (pub + 30 - transit) / 60))
            day += datetime.timedelta(days=1)
    for n in range(0, 3):
        exact[n] = sum(1 for a, b in ivs if a <= n < b)
    count, lo, hi = best_cover(ivs)
    print(f"C. the Khartoum State ministry's 2002 perpetual table, Dhuhr column (evidence only): {len(ivs)} days, computed "
          f"for {YEAR} at UTC+3: Dhuhr = transit + n exact on " + ", ".join(f"n={n}: {c}" for n, c in exact.items())
          + f"; best shift {(lo + hi) / 2:+.2f} min ({count})")


def main() -> int:
    days = [datetime.date(YEAR, 1, 1) + datetime.timedelta(days=i) for i in range(365)]
    print(f"{AUTHORITY}: {ANGLES['FAJR']:g} / {ANGLES['ISHA']:g}, Dhuhr and Maghrib + 0, nearest, at the user's point (plan "
          f"A3, 8.7); no zones, no asset; nothing left to fit")
    section_a(days)
    section_b()
    section_c()
    print("FitStats: none (tier C: no official data, 9.5); chip «تقريبية» (A3, 6.11)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
