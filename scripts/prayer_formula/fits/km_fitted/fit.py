#!/usr/bin/env python3
"""
Checks `km_fitted`, the Comoros' traditional Moroni table as a daily formula (plan 5.6, 8.7, A3, E15; milestone AM2,
9.4), and writes its official-zone asset, android-app/app/src/main/assets/official-zones/km_fitted.json (schema 1).

The plan fixes the whole method (A3, owner, 2026-10-11): Fajr = sunrise - 73, Dhuhr = transit + 4, Maghrib = sunset
+ 3, Isha = Maghrib + 74, Asr = Maghrib - 168 (E15 `BeforeMaghrib`), nearest rounding, every time from Moroni's point
(-11.70, 43.25, plan 8.7), mapped NATIONAL, and the sunrise row at the user's own point (`sunriseAtPlace`, 6.5).
Nothing is left to fit, so no value is fitted here.

No official or printed table is at hand (Q16): test-data-local/research-2026-10-08/SD-SO-DJ-KM/km holds a research
script only. The table exists locally only in the owner's mosque dataset (mosque-dataset/mosques.db, 5.6), which is
evidence, never a source (5.6); tier C of 9.5 checks these profiles against it. It is read here, read-only and at run
time, for that check alone; nothing from it is fitted or written:
1. the calendars that follow the table: Dhuhr and Asr within 2 min of the formula, or of the formula - 3 (Mayotte's
   version, 7.6), on at least 85 % of the days (the KM-coded record in Mayotte is classified, not checked);
2. the check per time (exact, within 1 and 2 min, worst), each distinct calendar once; the sunrise row against each
   mosque's own point where its calendar has a sunrise column (within 3 min of it on 90 % of the days);
3. a windows check of the plan's five minute values (9.4: a published minute bounds the unrounded time to a minute;
   the whole minute most windows cover), on all days and on each half of a month-split;
4. side checks: Maghrib - 168 against the shadow-factor-1 Asr, the validity guard (6.5) at the islands' ends, and
   `yt_traditional` (Mayotte: the Moroni table - 3 from the same point).

Every score goes through common.compute (the reference engine). The reference has no `sunriseAtPlace`, so the sunrise
row is computed as the Kotlin engine does it: the method at the place's own point (zone null).

Usage: python scripts/prayer_formula/fits/km_fitted/fit.py
"""

from __future__ import annotations

import datetime
import json
import math
import sqlite3
import statistics
import sys
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import common  # noqa: E402

EVENTS = common.EVENTS
PRAYERS = ("FAJR", "DHUHR", "ASR", "MAGHRIB", "ISHA")
AUTHORITY = "km_fitted"
TZ = "Indian/Comoro"                       # UTC+3 all year
COUNTRY = "KM"
YEAR = 2026                                # the dataset's rows carry no year: read as 2026, 29 February dropped (5.6)
MORONI = (-11.70, 43.25)                   # plan 8.7: the table's point
MINUTES = {"FAJR": 73, "DHUHR": 4, "MAGHRIB": 3, "ISHA": 74, "ASR": 168}   # plan A3, 8.7
DATASET = common.LOCAL_DATA.parent / "mosque-dataset" / "mosques.db"
LOCAL = common.LOCAL_DATA / "research-2026-10-08" / "SD-SO-DJ-KM" / "km"
QUIRK_MIN = 5                              # quirk day: more than this from the formula and from the calendar's median
SOURCE_LINE = ("The Comoros' traditional Moroni table as a daily formula (the table 16 of 24 mosques use, plan 5.6, A3); "
               "one zone at Moroni's point, the sunrise at the user's point")
ASSET_ZONE = {"id": "moroni", "nameAr": "موروني", "point": common.point(*MORONI), "sunriseAtPlace": True}
REF_ZONE = common.zone_json("moroni", *MORONI, authority=AUTHORITY, nameAr="موروني", sunriseAtPlace=True)
# Approximate ends of the three islands, for the validity-guard side check only (not catalogue points).
ISLAND_ENDS = {
    "Ngazidja north": (-11.37, 43.30), "Ngazidja south": (-11.92, 43.48), "Ngazidja east": (-11.60, 43.50),
    "Mwali west": (-12.29, 43.63), "Mwali east": (-12.35, 43.87),
    "Nzwani north": (-12.08, 44.40), "Nzwani west": (-12.20, 44.21), "Nzwani east": (-12.26, 44.54),
    "Nzwani south": (-12.37, 44.48),
}
MAMOUDZOU = (-12.78, 45.23)


def km_method() -> dict:
    return common.method_json(
        id=AUTHORITY, fajr={"type": "before_sunrise", "minutes": MINUTES["FAJR"]},
        isha={"type": "after", "minutes": MINUTES["ISHA"], "from": "MAGHRIB", "ramadan": None},
        asr={"type": "before_maghrib", "minutes": MINUTES["ASR"]},
        offsets={"DHUHR": MINUTES["DHUHR"], "MAGHRIB": MINUTES["MAGHRIB"]},
        evidence={"kind": "FROM_TABLES", "source": "The Moroni table 16 of 24 mosques use"})


def yt_method() -> dict:
    """EuropeanPresets.YT_TRADITIONAL: the Moroni table less 3 min (Fajr 76, Dhuhr + 1, Maghrib + 0, Isha 74, Asr 168)."""
    return common.method_json(
        id="yt_traditional", fajr={"type": "before_sunrise", "minutes": 76},
        isha={"type": "after", "minutes": 74, "from": "MAGHRIB", "ramadan": None},
        asr={"type": "before_maghrib", "minutes": 168}, offsets={"DHUHR": 1},
        evidence={"kind": "FROM_TABLES", "source": "yt"})


def days_of(year: int = YEAR) -> list[datetime.date]:
    d, out = datetime.date(year, 1, 1), []
    while d.year == year:
        if not (d.month == 2 and d.day == 29):
            out.append(d)
        d += datetime.timedelta(days=1)
    return out


def local_minute(epoch: float) -> int:
    """Minutes after local midnight of a rounded instant (UTC+3)."""
    return int(((epoch + 3 * 3600) % 86400) // 60)


def clock(minute: float) -> str:
    return f"{int(minute // 60):02d}:{int(minute % 60):02d}"


# ---- the dataset (evidence only) -------------------------------------------------------------------------------

class Calendar:
    def __init__(self, key: int, lat: float, lng: float, rows: dict):
        self.keys, self.points, self.rows = [key], [(lat, lng)], rows

    def signature(self) -> tuple:
        return tuple(tuple(self.rows[d][e] for e in EVENTS) for d in sorted(self.rows))


def hhmm(text: str) -> int:
    h, m = text.strip().split(":")[:2]
    return int(h) * 60 + int(m)


def km_coded_calendars() -> tuple[list[Calendar], list[Calendar]] | None:
    """The KM-coded records of the dataset, each with its 365 rows, read-only: those in the Comoros, and those east of
    44.8 E, which lie in Mayotte (the check leaves them out)."""
    if not DATASET.exists():
        return None
    con = sqlite3.connect(f"file:{DATASET.as_posix()}?mode=ro&immutable=1", uri=True)
    comoros, mayotte = [], []
    for key, lat, lng in con.execute("select mosque_key, latitude, longitude from mosques where countryCode = ? "
                                     "order by mosque_key", (COUNTRY,)).fetchall():
        rows = {}
        for mo, da, *t in con.execute("select month, day, fajr, sunrise, dhuhr, asr, maghrib, isha from calendar_days "
                                      "where mosque_key = ?", (key,)):
            if not (mo == 2 and da == 29):
                rows[datetime.date(YEAR, mo, da)] = dict(zip(EVENTS, (hhmm(x) for x in t)))
        if len(rows) == 365:
            (mayotte if lng > 44.8 else comoros).append(Calendar(key, lat, lng, rows))
    con.close()
    return comoros, mayotte


def distinct(calendars: list[Calendar]) -> list[Calendar]:
    groups: dict[tuple, Calendar] = {}
    for c in calendars:
        sig = c.signature()
        if sig in groups:
            groups[sig].keys += c.keys
            groups[sig].points += c.points
        else:
            groups[sig] = Calendar(c.keys[0], *c.points[0], c.rows)
    return list(groups.values())


# ---- the table as the app computes it --------------------------------------------------------------------------

def table_days(method: dict, days: list[datetime.date]) -> dict:
    """Per day, the reference's unrounded instants and rounded local minutes at Moroni (the zone)."""
    out = {}
    for day in days:
        r = common.compute(common.case(method, *MORONI, TZ, day, zone=REF_ZONE, country=COUNTRY))
        out[day] = {e: (r[e][0], local_minute(r[e][1])) for e in EVENTS}
    return out


def own_sunrise(method: dict, lat: float, lng: float, days: list[datetime.date]) -> dict:
    """The sunrise row at a place's own point, as the Kotlin engine gives it for a `sunriseAtPlace` zone."""
    return {day: local_minute(common.compute(common.case(method, lat, lng, TZ, day, country=COUNTRY))["SUNRISE"][1])
            for day in days}


def follower_shift(cal: Calendar, table: dict) -> int | None:
    """0 (the table), -3 (the table - 3) or None: Dhuhr and Asr within 2 min on at least 85 % of the days."""
    for shift in (0, -3):
        ok = sum(1 for d, row in cal.rows.items()
                 if abs(row["DHUHR"] - table[d]["DHUHR"][1] - shift) <= 2 and abs(row["ASR"] - table[d]["ASR"][1] - shift) <= 2)
        if ok >= 0.85 * len(cal.rows):
            return shift
    return None


def variant(cal: Calendar, table: dict, shift: int) -> str:
    if shift == -3:
        return "the table - 3 (Mayotte's version)"
    maghrib = statistics.median(row["MAGHRIB"] - table[d]["MAGHRIB"][1] for d, row in cal.rows.items())
    return "the table, Maghrib at sunset" if maghrib <= -2 else "the table"


class Check:
    """Exact, within 1, within 2 and worst per time; quirk days counted apart: a published minute more than QUIRK_MIN
    from the computed one and from its calendar's own median difference for that time (a typing slip, not the
    calendar's rule)."""

    def __init__(self):
        self.n, self.exact, self.w1, self.w2 = Counter(), Counter(), Counter(), Counter()
        self.worst, self.quirk_worst, self.quirks = Counter(), Counter(), Counter()
        self.months = defaultdict(lambda: [0] * 12)

    def add(self, event: str, day: datetime.date, computed: int, published: int, centre: float = 0.0):
        diff = published - computed
        if abs(diff) > QUIRK_MIN and abs(diff - centre) > QUIRK_MIN:
            self.quirks[event] += 1
            self.quirk_worst[event] = max(self.quirk_worst[event], abs(diff))
            return
        self.n[event] += 1
        self.months[event][day.month - 1] += 1
        self.exact[event] += diff == 0
        self.w1[event] += abs(diff) <= 1
        self.w2[event] += abs(diff) <= 2
        self.worst[event] = max(self.worst[event], abs(diff))

    def line(self, events=EVENTS) -> str:
        parts = []
        for e in events:
            if not self.n[e]:
                continue
            n = self.n[e]
            parts.append(f"{e[:4]} {100 * self.exact[e] / n:5.1f}/{100 * self.w1[e] / n:5.1f}/{100 * self.w2[e] / n:5.1f}"
                         f" max {self.worst[e]}" + (f" (+{self.quirks[e]} quirk, worst {self.quirk_worst[e]})" if self.quirks[e] else ""))
        return " | ".join(parts)


def check_calendars(cals: list[Calendar], table: dict) -> Check:
    check = Check()
    for cal in cals:
        for e in PRAYERS:
            centre = statistics.median(row[e] - table[d][e][1] for d, row in cal.rows.items())
            for d, row in cal.rows.items():
                check.add(e, d, table[d][e][1], row[e], centre)
    return check


# ---- windows (9.4) ---------------------------------------------------------------------------------------------

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


def parameter_windows(cals: list[Calendar], table: dict, months: set[int] | None = None) -> dict:
    """Per parameter, the windows of its value in minutes, from each published minute (nearest rounding): Fajr's minutes
    before sunrise, Dhuhr's after transit, Maghrib's after sunset, Isha's after Maghrib, Asr's before Maghrib."""
    out = defaultdict(list)
    for cal in cals:
        for d, row in cal.rows.items():
            if months is not None and d.month not in months:
                continue
            t = table[d]
            sunrise = t["FAJR"][0] + 60 * MINUTES["FAJR"]
            transit = t["DHUHR"][0] - 60 * MINUTES["DHUHR"]
            maghrib = t["MAGHRIB"][0]
            sunset = maghrib - 60 * MINUTES["MAGHRIB"]
            pub = {e: common.local_to_epoch(TZ, d, clock(row[e])) for e in PRAYERS}
            out["FAJR"].append(((sunrise - pub["FAJR"] - 30) / 60, (sunrise - pub["FAJR"] + 30) / 60))
            out["DHUHR"].append(((pub["DHUHR"] - 30 - transit) / 60, (pub["DHUHR"] + 30 - transit) / 60))
            out["MAGHRIB"].append(((pub["MAGHRIB"] - 30 - sunset) / 60, (pub["MAGHRIB"] + 30 - sunset) / 60))
            out["ISHA"].append(((pub["ISHA"] - 30 - maghrib) / 60, (pub["ISHA"] + 30 - maghrib) / 60))
            out["ASR"].append(((maghrib - pub["ASR"] - 30) / 60, (maghrib - pub["ASR"] + 30) / 60))
    return out


def whole_minute_counts(intervals: list[tuple[float, float]], around: int) -> dict[int, int]:
    return {v: sum(1 for a, b in intervals if a <= v < b) for v in range(around - 2, around + 3)}


# ---- side checks -----------------------------------------------------------------------------------------------

def asr_against_shadow(table: dict, days: list[datetime.date]) -> str:
    plain = common.method_json(id="shadow", fajr={"type": "angle", "degrees": 18.0}, isha={"type": "angle", "degrees": 18.0})
    diffs = []
    for d in days:
        shadow = common.compute(common.case(plain, *MORONI, TZ, d, country=COUNTRY))["ASR"][0]
        diffs.append(((table[d]["ASR"][0] - shadow) / 60, d))
    lo, hi = min(diffs), max(diffs)
    before = [d for x, d in diffs if x < 0]
    return (f"Maghrib - 168 against the shadow-factor-1 Asr at Moroni: {lo[0]:+.1f} min ({lo[1]}) to {hi[0]:+.1f} min "
            f"({hi[1]}); before it from {min(before)} to {max(before)}" if before else "never before it")


def validity_guard(method: dict, days: list[datetime.date]) -> list[str]:
    """6.5: each time's lead (the profile at the place, unrounded, minus the table there) against the table's own
    margin at Moroni plus 4 min; the sunrise row is the place's own and is not compared (6.5 item 6)."""
    def worst_leads(lat: float, lng: float) -> dict:
        worst = dict.fromkeys(PRAYERS, -math.inf)
        for d in days:
            t = common.compute(common.case(method, lat, lng, TZ, d, zone=REF_ZONE, country=COUNTRY))
            r = common.compute(common.case(method, lat, lng, TZ, d, country=COUNTRY))
            for e in PRAYERS:
                worst[e] = max(worst[e], (r[e][0] - t[e][1]) / 60)
        return worst
    margin = worst_leads(*MORONI)
    lines = ["margin at Moroni: " + ", ".join(f"{e[:4]} {margin[e]:+.2f}" for e in PRAYERS)]
    for name, (lat, lng) in ISLAND_ENDS.items():
        lead = worst_leads(lat, lng)
        acts = [e for e in PRAYERS if lead[e] > margin[e] + 4]
        lines.append(f"{name} ({lat:.2f}, {lng:.2f}): worst lead " + ", ".join(f"{e[:4]} {lead[e]:+.2f}" for e in PRAYERS)
                     + (f" -> acts on {acts}" if acts else " -> keeps the table"))
    return lines


def yt_consistency(km_table: dict, days: list[datetime.date]) -> str:
    path = common.ASSETS / "yt_traditional.json"
    note = "no yt_traditional asset"
    if path.exists():
        zones = json.loads(path.read_text(encoding="utf-8"))["zones"]
        same = len(zones) == 1 and zones[0]["point"] == ASSET_ZONE["point"]
        note = f"yt_traditional asset point {'=' if same else '!='} km_fitted's ({zones[0]['point']})"
    yt_zone = common.zone_json("mayotte", *MORONI, authority="yt_traditional", nameAr="مايوت", sunriseAtPlace=True)
    equal = Counter()
    for d in days:
        r = common.compute(common.case(yt_method(), *MAMOUDZOU, "Indian/Mayotte", d, zone=yt_zone, country="YT"))
        for e in PRAYERS:
            equal[e] += local_minute(r[e][1]) == km_table[d][e][1] - 3
    return note + "; yt_traditional = km_fitted - 3 on " + ", ".join(f"{e[:4]} {equal[e]}/{len(days)}" for e in PRAYERS)


# ---- main ------------------------------------------------------------------------------------------------------

def main() -> int:
    days = days_of()
    method = km_method()
    local = sorted(p.name for p in LOCAL.glob("*")) if LOCAL.exists() else []
    print(f"km_fitted: the Moroni table as a daily formula (plan A3): Fajr = sunrise - {MINUTES['FAJR']}, Dhuhr + "
          f"{MINUTES['DHUHR']}, Maghrib + {MINUTES['MAGHRIB']}, Isha = Maghrib + {MINUTES['ISHA']}, Asr = Maghrib - "
          f"{MINUTES['ASR']}, nearest, from Moroni {MORONI}; nothing left to fit")
    print(f"local data {LOCAL.relative_to(common.LOCAL_DATA)}: {local} (no table)")
    print("computing the table at Moroni for 2026 ...")
    table = table_days(method, days)
    print("  " + asr_against_shadow(table, days))

    coded = km_coded_calendars()
    if coded is None:
        print(f"no dataset at {DATASET}: the tier-C check is skipped")
    else:
        cals, mayotte = coded
        print(f"the owner's mosque dataset (evidence only, tier C of 9.5): {len(cals)} Comorian records; "
              f"{len(mayotte)} KM-coded record(s) east of 44.8 E, in Mayotte, left out: " + ", ".join(
                  (lambda s: "not the table" if s is None else variant(m, table, s))(follower_shift(m, table)) for m in mayotte))
        followers = []
        for cal in distinct(cals):
            shift = follower_shift(cal, table)
            if shift is not None:
                followers.append((cal, shift, variant(cal, table, shift)))
        by_variant = defaultdict(list)
        for cal, shift, name in followers:
            by_variant[name].append(cal)
        print(f"  calendars that follow the table: {sum(len(c.keys) for c, _, _ in followers)} records on "
              f"{len(followers)} distinct calendars; " + "; ".join(
                  f"{name}: {sum(len(c.keys) for c in cs)} on {len(cs)}" for name, cs in by_variant.items()))
        print("check per time, exact/within 1/within 2 (%) and worst minute, each distinct calendar once:")
        for name, cs in by_variant.items():
            check = check_calendars(cs, table)
            print(f"  {name} ({len(cs)} calendars, {len(cs) * len(days)} days): {check.line(PRAYERS)}")
            if name == "the table":
                main_check, main_cals = check, cs
        print(f"  quirk days: a published minute more than {QUIRK_MIN} min from the computed one and from its calendar's "
              "own median difference for that time, counted apart (typing slips); 'max' leaves them out")
        print("sunrise row at each mosque's own point (calendars whose sunrise column is within 3 min of it on 90 % of days):")
        sunrise = Check()
        kept = 0
        for cal, shift, name in followers:
            for key, (lat, lng) in zip(cal.keys, cal.points):
                own = own_sunrise(method, lat, lng, days)
                close = sum(1 for d, row in cal.rows.items() if abs(row["SUNRISE"] - own[d]) <= 3)
                if close < 0.9 * len(days):
                    continue
                kept += 1
                centre = statistics.median(row["SUNRISE"] - own[d] for d, row in cal.rows.items())
                for d, row in cal.rows.items():
                    sunrise.add("SUNRISE", d, own[d], row["SUNRISE"], centre)
        print(f"  {kept} of {sum(len(c.keys) for c, _, _ in followers)} records: {sunrise.line(('SUNRISE',))}")

        print("windows check of the plan's minutes on the table's own calendars (the value most windows cover):")
        wins = parameter_windows(main_cals, table)
        halves = {"odd months": parameter_windows(main_cals, table, {1, 3, 5, 7, 9, 11}),
                  "even months": parameter_windows(main_cals, table, {2, 4, 6, 8, 10, 12})}
        labels = {"FAJR": "Fajr, minutes before sunrise", "DHUHR": "Dhuhr, after transit", "MAGHRIB": "Maghrib, after sunset",
                  "ISHA": "Isha, after Maghrib", "ASR": "Asr, before Maghrib"}
        for e in ("FAJR", "DHUHR", "MAGHRIB", "ISHA", "ASR"):
            counts = whole_minute_counts(wins[e], MINUTES[e])
            best = max(counts, key=lambda v: (counts[v], -abs(v - MINUTES[e])))
            n, lo, hi = best_cover(wins[e])
            split = []
            for half, w in halves.items():
                c = whole_minute_counts(w[e], MINUTES[e])
                split.append(f"{half} {max(c, key=lambda v: (c[v], -abs(v - MINUTES[e])))}")
            print(f"  {labels[e]}: plan {MINUTES[e]} covers {counts[MINUTES[e]]} of {len(wins[e])}; best whole minute {best}; "
                  f"counts {counts}; best plateau {lo:.2f}..{hi:.2f} ({n}); month-split: {', '.join(split)}")
        print(f"  held out: nothing is fitted here, so every score above is held out from this script; the plan read "
              f"its minutes from these calendars (A3), so for the plan's values they are in-sample")
        print("  days per month on the table's calendars: " + str(main_check.months["DHUHR"]))
        print("rounding and sun model on the table's calendars (a check; the plan's nearest rounding and MEEUS kept):")
        for label, change in (("Dhuhr DOWN", {"rounding": {**method["rounding"], "DHUHR": "DOWN"}}),
                              ("Dhuhr UP", {"rounding": {**method["rounding"], "DHUHR": "UP"}}),
                              ("VSOP87", {"sunModel": "VSOP87"})):
            check = check_calendars(main_cals, table_days({**method, **change}, days))
            print(f"  {label}: {check.line(('DHUHR',) if label.startswith('Dhuhr') else PRAYERS)}")

    print("validity guard side check (6.5, 2026):")
    for line in validity_guard(method, days):
        print("  " + line)
    print(yt_consistency(table, days))
    path = common.write_asset(AUTHORITY, AUTHORITY, COUNTRY, "NATIONAL", [ASSET_ZONE], source=SOURCE_LINE)
    print(f"wrote {path.relative_to(common.ROOT)} (one zone «موروني», sunriseAtPlace, no FitStats: tier C, 9.5)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
