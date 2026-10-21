#!/usr/bin/env python3
"""
Checks `dj_fitted`, Djibouti's fitted profile (plan 5.6, 8.7, A3; milestone AM2, 9.4). The method runs at the user's
point anywhere in Djibouti (exact coordinates, plan 8.7), so there is no official-zone asset; this script prints the
profile's checks.

The plan fixes the method (A3, owner, 2026-10-11): MWL 18 / 17, no offsets, nearest rounding, at the user's
coordinates. Nothing is left to fit, so no value is fitted here.

No official or printed table is at hand (8.1, 12, Q16). test-data-local/research-2026-10-08/SD-SO-DJ-KM holds, for
Djibouti, a research script (dj.py), three Al-Qarn articles of February 2026 and one of March 2025 (alqarn/,
alqarn25/: the Directorate of Islamic Affairs' Ramadan 2026 circular unifying the Maghrib and suhur adhan across the
capital and the regions, with no times; the site's prayer-times box is a third-party widget) and the presidency's page
of ministry remits (attr_fr.txt): no times to check. The only Djiboutian times are the owner's mosque dataset
(mosque-dataset/mosques.db, 5.6), evidence and never a source (5.6); tier C of 9.5 checks the profile against it. It is
read here, read-only and at run time, for that check alone; nothing from it is fitted or written:
1. per calendar and time, the profile at the mosque's own point (exact, within 1 min, worst);
2. windows (9.4: a published minute bounds the unrounded time to a minute under nearest rounding), per calendar: the
   Fajr and Isha angles most windows allow at the event instant, and the whole-minute offsets of sunrise, Dhuhr, Asr
   and Maghrib most windows cover, beside the plan's 18, 17 and 0;
3. a side check of Asr under each sun evaluation (E4): the calendars' Asr wanders about a minute around the shadow rule.

Every score goes through common.compute (the reference engine).

Usage: python scripts/prayer_formula/fits/dj_fitted/fit.py
"""

from __future__ import annotations

import datetime
import math
import sqlite3
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import common  # noqa: E402

ref = common.ref
EVENTS = common.EVENTS
AUTHORITY = "dj_fitted"
COUNTRY = "DJ"
TZ = "Africa/Djibouti"                     # UTC+3 all year
YEAR = 2026                                # the dataset's rows carry no year: read as 2026, 29 February dropped (5.6)
ANGLES = {"FAJR": 18.0, "ISHA": 17.0}      # plan A3, 8.7
DATASET = common.LOCAL_DATA.parent / "mosque-dataset" / "mosques.db"
LOCAL = common.LOCAL_DATA / "research-2026-10-08" / "SD-SO-DJ-KM"
EVALS = ("EVENT_INSTANT", "DAILY_0H_UT", "DAILY_10H_UT", "KACST")


def profile(evaluation: str | None = None) -> dict:
    return common.method_json(
        id=AUTHORITY, fajr={"type": "angle", "degrees": ANGLES["FAJR"]}, isha={"type": "angle", "degrees": ANGLES["ISHA"]},
        sunEvaluation={} if evaluation in (None, "EVENT_INSTANT") else {"ASR": evaluation},
        evidence={"kind": "APPROXIMATE", "source": "Six mosques in the dataset, perhaps their software's default"})


def hhmm(text: str) -> int:
    h, m = text.strip().split(":")[:2]
    return int(h) * 60 + int(m)


def clock(minute: int) -> str:
    return f"{minute // 60:02d}:{minute % 60:02d}"


def calendars() -> list[tuple[list[int], float, float, dict]] | None:
    """The country's records, read-only: (keys, lat, lng, rows by date); identical calendars at one point merged."""
    if not DATASET.exists():
        return None
    con = sqlite3.connect(f"file:{DATASET.as_posix()}?mode=ro&immutable=1", uri=True)
    seen: dict[tuple, list] = {}
    for key, lat, lng in con.execute("select mosque_key, latitude, longitude from mosques where countryCode = ? "
                                     "order by mosque_key", (COUNTRY,)).fetchall():
        rows = {}
        for mo, da, *t in con.execute("select month, day, fajr, sunrise, dhuhr, asr, maghrib, isha from calendar_days "
                                      "where mosque_key = ?", (key,)):
            if not (mo == 2 and da == 29):
                rows[datetime.date(YEAR, mo, da)] = dict(zip(EVENTS, (hhmm(x) for x in t)))
        sig = (round(lat, 4), round(lng, 4), tuple(tuple(rows[d][e] for e in EVENTS) for d in sorted(rows)))
        if sig in seen:
            seen[sig][0].append(key)
        else:
            seen[sig] = [[key], lat, lng, rows]
    con.close()
    return [tuple(v) for v in seen.values()]


def local_minute(epoch: float, day: datetime.date) -> int:
    return int(((epoch + common.utc_offset(TZ, day)) % 86400) // 60)


def computed(method: dict, lat: float, lng: float, days) -> dict:
    """Per day: per event, (unrounded epoch seconds, rounded local minute) at the place's own point."""
    out = {}
    for day in days:
        r = common.compute(common.case(method, lat, lng, TZ, day, country=COUNTRY))
        out[day] = {e: (r[e][0], local_minute(r[e][1], day)) for e in EVENTS}
    return out


def check_line(rows: dict, comp: dict) -> tuple[str, Counter, Counter, Counter]:
    exact, w1, worst = Counter(), Counter(), Counter()
    for day, row in rows.items():
        for e in EVENTS:
            diff = row[e] - comp[day][e][1]
            exact[e] += diff == 0
            w1[e] += abs(diff) <= 1
            worst[e] = max(worst[e], abs(diff))
    n = len(rows)
    line = " | ".join(f"{e[:4]} {100 * exact[e] / n:5.1f}/{100 * w1[e] / n:5.1f} max {worst[e]}" for e in EVENTS)
    return line, exact, w1, worst


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
    """The sun's depression at instant t (MEEUS, at the event instant); U = D at UTC+3 in Djibouti."""
    cyc = ref.Cycle(day)
    sun = ref.SunFunction(ref.meeus_sun, "EVENT_INSTANT", cyc.jd0)
    return -ref.altitude(cyc, sun, lat, lng, cyc.hours(t))


def windows(rows: dict, comp: dict, lat: float, lng: float) -> tuple[str, bool]:
    """The angles and whole-minute offsets most windows cover, against the plan's (18, 17, 0); and whether the
    calendar is on the profile (both angles within 0.1 deg of the plan's, sunrise, Dhuhr and Maghrib + 0)."""
    out, on_profile = [], True
    pub = {day: {e: common.local_to_epoch(TZ, day, clock(row[e])) for e in EVENTS} for day, row in rows.items()}
    for e in ("FAJR", "ISHA"):
        ivs = []
        for day in rows:
            a, b = depression(day, lat, lng, pub[day][e] - 30), depression(day, lat, lng, pub[day][e] + 30)
            ivs.append((min(a, b), max(a, b)))
        n, lo, hi = best_cover(ivs)
        plan = sum(1 for a, b in ivs if a <= ANGLES[e] < b)
        on_profile &= abs((lo + hi) / 2 - ANGLES[e]) <= 0.1
        out.append(f"{e[:4]} angle {(lo + hi) / 2:.2f} ({n}; {ANGLES[e]:g}: {plan})")
    for e in ("SUNRISE", "DHUHR", "ASR", "MAGHRIB"):
        ivs = [((pub[day][e] - 30 - comp[day][e][0]) / 60, (pub[day][e] + 30 - comp[day][e][0]) / 60) for day in rows]
        counts = {v: sum(1 for a, b in ivs if a <= v < b) for v in range(-3, 4)}
        best = max(counts, key=lambda v: (counts[v], -abs(v)))
        on_profile &= e == "ASR" or best == 0
        out.append(f"{e[:4]} {best:+d} ({counts[best]}; 0: {counts[0]})")
    return ", ".join(out) + f" of {len(rows)} days", on_profile


class Total:
    def __init__(self):
        self.exact, self.w1, self.worst, self.n, self.calendars = Counter(), Counter(), Counter(), 0, 0

    def add(self, rows: dict, exact: Counter, w1: Counter, worst: Counter):
        for e in EVENTS:
            self.exact[e] += exact[e]
            self.w1[e] += w1[e]
            self.worst[e] = max(self.worst[e], worst[e])
        self.n += len(rows)
        self.calendars += 1

    def line(self) -> str:
        return " | ".join(f"{e[:4]} {100 * self.exact[e] / self.n:5.1f}/{100 * self.w1[e] / self.n:5.1f} max {self.worst[e]}"
                          for e in EVENTS)


def main() -> int:
    days = [datetime.date(YEAR, 1, 1) + datetime.timedelta(days=i) for i in range(365)]
    local = sorted(str(p.relative_to(LOCAL)) for pattern in ("dj.py", "attr_fr.txt", "alqarn*/*.txt")
                   for p in LOCAL.glob(pattern)) if LOCAL.exists() else []
    print(f"{AUTHORITY}: MWL {ANGLES['FAJR']:g} / {ANGLES['ISHA']:g}, no offsets, nearest, at the user's point (plan A3, "
          f"8.7); no zones, no asset; nothing left to fit")
    print(f"local data {LOCAL.relative_to(common.LOCAL_DATA)}: {local} (no table)")
    cals = calendars()
    if cals is None:
        print(f"no dataset at {DATASET}: the tier-C check is skipped")
        return 0
    print(f"the owner's mosque dataset (evidence only, tier C of 9.5): {sum(len(k) for k, *_ in cals)} records on "
          f"{len(cals)} calendars")
    method = profile()
    total, on_profile = Total(), Total()
    per_eval = {ev: Counter() for ev in EVALS}
    for keys, lat, lng, rows in cals:
        comp = computed(method, lat, lng, days)
        line, exact, w1, worst = check_line(rows, comp)
        text, on = windows(rows, comp, lat, lng)
        print(f"  calendar at {lat:.3f}, {lng:.3f} ({len(keys)} record{'s' if len(keys) > 1 else ''}), exact/within 1 (%): {line}")
        print(f"    windows: {text}{' (on the profile)' if on else ''}")
        total.add(rows, exact, w1, worst)
        if on:
            on_profile.add(rows, exact, w1, worst)
        for ev in EVALS:
            c = comp if ev == "EVENT_INSTANT" else computed(profile(ev), lat, lng, days)
            per_eval[ev]["ASR"] += sum(1 for day, row in rows.items() if row["ASR"] == c[day]["ASR"][1])
    print(f"check, every calendar once ({total.n} days; held out: nothing is fitted here, but the plan read "
          f"{ANGLES['FAJR']:g} / {ANGLES['ISHA']:g} from these calendars, A3):")
    print("  all: " + total.line())
    if on_profile.calendars:
        print(f"  the {on_profile.calendars} calendars on the profile ({on_profile.n} days): " + on_profile.line())
    print("side check, Asr exact under each sun evaluation (E4): " + ", ".join(
        f"{ev} {100 * per_eval[ev]['ASR'] / total.n:.1f} %" for ev in EVALS) + " (not adopted: no published rule, A3)")
    print("FitStats: none (tier C: no official data, 9.5); chip «فلكية» (A3, 6.11)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
