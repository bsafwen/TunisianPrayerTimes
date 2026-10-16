#!/usr/bin/env python3
"""
Fits `gf_a2cmgf`, French Guiana's A2CMGF (plan 7.6, Q35; milestone M3, 9.4). The method is computed at the user's
point anywhere in Guiana, so there is no official-zone asset; this script reports the profile and its scores.

Data, read at run time from test-data-local and never copied (D13, A7), in research-2026-10-11/FR/:
- a2_cayenne_jan2026.csv, a2_remire_jan2026.csv: A2CMGF's January 2026 monthly calendars for Cayenne and
  Remire-Montjoly (IslamicFinder pages the association posts; they state "Ligue islamique mondiale", Fajr 18.0 and
  Isha 17.0 degrees, Asr factor 1).
- a2_masjidbox_oct2026.csv: the Cayenne mosque's Masjidbox page, 10-15 October 2026.
- gf_islamicfinder_cayenne_oct2026.csv: IslamicFinder's live Cayenne page on the same days, which adds 1 min to
  Dhuhr and Maghrib (7.6): scored apart, never fitted.
Each calendar is computed at its town's GeoNames point (geonames.org, read 2026-10-15), as the app computes at the
user's point; the Masjidbox page at Cayenne's.

Stages (9.4), each by windows (a published minute bounds the unrounded time to a minute under E1; the value most
windows cover wins): 0. one sun evaluation (E4) for all six times (event instant, daily 0h UT, daily 10h UT, KACST),
by the windows the six rules below then cover; 1. sunrise, Dhuhr and Maghrib (rounding and whole-minute offset);
2. Fajr and Isha at the published 18 and 17 deg (rounding; the angle most windows allow at the event instant is
printed as a check); 3. Asr (factor, rounding, offset). Equal counts keep the event instant, factor 1, UP before
NEAREST before DOWN (sunrise: DOWN first) and the smallest offset. The sun model stays MEEUS and Asr's sun geometric
(E13): a month of days cannot choose them (Asr under each evaluation and sun is printed as a check).
Held out: each January calendar scored with the stages fitted on the other town's, and the Masjidbox days with the
stages fitted on both, every day with common.compute (the reference engine).

Usage: python scripts/prayer_formula/fits/gf_a2cmgf/fit.py
"""

from __future__ import annotations

import csv
import datetime
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import common  # noqa: E402

ref = common.ref
EVENTS = common.EVENTS
AUTHORITY = "gf_a2cmgf"
TZ = "America/Cayenne"                    # UTC-3 all year
COUNTRY = "GF"
FR = common.LOCAL_DATA / "research-2026-10-11" / "FR"
TOWNS = {"Cayenne": (4.938, -52.335), "Remire-Montjoly": (4.917, -52.267)}    # GeoNames, read 2026-10-15
PUBLISHED = {"FAJR": 18.0, "ISHA": 17.0}
WINDOW = {"NEAREST": (-30.0, 30.0), "UP": (-60.0, 0.0), "DOWN": (0.0, 60.0)}
PRAYER_MODES = ("UP", "NEAREST", "DOWN")
SUNRISE_MODES = ("DOWN", "NEAREST", "UP")
ASR_EVALS = ("EVENT_INSTANT", "DAILY_0H_UT", "DAILY_10H_UT", "KACST", "LOCAL_MIDNIGHT_SHADOW")
KEYS = dict(zip(EVENTS, ("fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha")))


def load(name: str, town: str, year: int, month: int) -> list[tuple]:
    rows = []
    with open(FR / name, encoding="utf-8") as fh:
        for r in csv.DictReader(fh):
            day = datetime.date(year, month, int(r["day"]))
            rows.append((town, day, {e: r[KEYS[e]] for e in EVENTS}))
    return rows


def published(day: datetime.date, hhmm: str) -> int:
    return common.local_to_epoch(TZ, day, hhmm)


def best_cover(intervals, lo, hi, prefer):
    """(count, value): the most half-open intervals covering one value within [lo, hi]; `prefer` where it lies in a
    best plateau, else the middle of the best plateau nearest to it."""
    points = []
    for a, b in intervals:
        a, b = max(a, lo), min(b, hi)
        if a < b:
            points += [(a, 1), (b, -1)]
    points.sort()
    best, cur, plateaus = 0, 0, []
    for i, (x, d) in enumerate(points):
        cur += d
        nxt = points[i + 1][0] if i + 1 < len(points) else None
        if nxt is None or nxt <= x:
            continue
        if cur > best:
            best, plateaus = cur, [[x, nxt]]
        elif cur == best and best > 0:
            if abs(plateaus[-1][1] - x) < 1e-12:
                plateaus[-1][1] = nxt
            else:
                plateaus.append([x, nxt])
    if not plateaus:
        return 0, prefer
    for a, b in plateaus:
        if a <= prefer < b:
            return best, prefer
    a, b = min(plateaus, key=lambda p: min(abs(p[0] - prefer), abs(p[1] - prefer)))
    return best, (a + b) / 2


class Raw:
    """One day's sun at a town (MEEUS at the event instant, the reference's own primitives), for the angle check."""

    def __init__(self, town: str, day: datetime.date):
        self.lat, self.lng = TOWNS[town]
        self.cyc = ref.Cycle(day)                         # U = D west of Greenwich at UTC-3 (12:00 + 3 h - 3.5 h)
        self.sun = ref.SunFunction(ref.meeus_sun, "EVENT_INSTANT", self.cyc.jd0)

    def depression(self, t: float) -> float:
        return -ref.altitude(self.cyc, self.sun, self.lat, self.lng, self.cyc.hours(t))


def unrounded(rows: list[tuple], factor: int = 1, evaluation: str = "EVENT_INSTANT", asr_sun: str = "GEOMETRIC") -> list[dict]:
    """The reference engine's unrounded times of each row (no offsets), the sun evaluated at `evaluation` for all six
    (LOCAL_MIDNIGHT_SHADOW: Asr only)."""
    events = ("ASR",) if evaluation == "LOCAL_MIDNIGHT_SHADOW" else EVENTS
    method = common.method_json(id=AUTHORITY, fajr={"type": "angle", "degrees": PUBLISHED["FAJR"]},
                                isha={"type": "angle", "degrees": PUBLISHED["ISHA"]}, asrShadow=factor, asrSun=asr_sun,
                                sunEvaluation={e: evaluation for e in events})
    return [{e: v[0] for e, v in common.compute(common.case(method, *TOWNS[town], TZ, day, country=COUNTRY)).items()}
            for town, day, mins in rows]


RULES = (("FAJR", PRAYER_MODES, (0,)), ("SUNRISE", SUNRISE_MODES, range(-2, 3)), ("DHUHR", PRAYER_MODES, range(0, 4)),
         ("ASR", PRAYER_MODES, range(-2, 3)), ("MAGHRIB", PRAYER_MODES, range(0, 4)), ("ISHA", PRAYER_MODES, (0,)))


def rules_at(times: list[dict], pubs: list[dict]) -> dict:
    """Per event, (count, mode, offset) covering most windows; ties keep the mode order and the smallest offset."""
    out = {}
    for e, modes, offs in RULES:
        n, _, _, mode, off = max((sum(1 for t, pub in zip(times, pubs) if ref.round_instant(t[e] + 60 * off, mode) == pub[e]),
                                  -modes.index(mode), -abs(off), mode, off) for mode in modes for off in offs)
        out[e] = (n, mode, off)
    return out


def fit(rows: list[tuple]) -> dict:
    """Stage 0: one evaluation (E4) for all six times, by the windows it lets the six rules cover; then each event's
    rounding and offset, and Asr's factor, at that evaluation."""
    raws = [(Raw(town, day), day, mins) for town, day, mins in rows]
    pubs = [{e: published(day, mins[e]) for e in EVENTS} for town, day, mins in rows]
    best, totals = None, {}
    for ev in ASR_EVALS[:4]:
        rules = rules_at(unrounded(rows, 1, ev), pubs)
        key = (sum(n for n, _, _ in rules.values()), -ASR_EVALS.index(ev))
        totals[ev] = key[0]
        if best is None or key > best[0]:
            best = (key, ev, rules)
    (total, _), ev, rules = best
    two = rules_at(unrounded(rows, 2, ev), pubs)["ASR"]
    factor = 2 if two[0] > rules["ASR"][0] else 1
    if factor == 2:
        rules["ASR"] = two
    p = {"evaluation": ev, "factor": factor, "asr_sun": "GEOMETRIC", "total": total, "totals": totals,
         "counts": {e: r[0] for e, r in rules.items()}, "modes": {e: r[1] for e, r in rules.items()},
         "offsets": {e: r[2] for e, r in rules.items()}}
    for e in ("FAJR", "ISHA"):
        # A check only: the angle most windows allow, with the sun at the event instant (the published one is used).
        free = None
        for mode in PRAYER_MODES:
            lo, hi = WINDOW[mode]
            ivs = []
            for r, day, mins in raws:
                pub = published(day, mins[e])
                da, db = r.depression(pub + lo), r.depression(pub + hi)
                ivs.append((min(da, db), max(da, db)))
            n, angle = best_cover(ivs, 10.0, 22.0, PUBLISHED[e])
            if free is None or (n, -PRAYER_MODES.index(mode)) > free[0]:
                free = ((n, -PRAYER_MODES.index(mode)), mode, round(angle, 2))
        p["angles_" + e] = PUBLISHED[e]
        p["free_" + e] = f"{free[2]} {free[1]} ({free[0][0]})"
    return p


def evaluation_counts(rows: list[tuple]) -> str:
    """Exact Asr, Fajr and Isha (rounded up) under each evaluation and Asr sun, on the given rows (a check)."""
    pubs = [{e: published(day, mins[e]) for e in EVENTS} for town, day, mins in rows]
    out = []
    for ev in ASR_EVALS:
        for asr_sun in ("GEOMETRIC", "APPARENT"):
            times = unrounded(rows, 1, ev, asr_sun)
            n = {e: sum(1 for t, pub in zip(times, pubs) if ref.round_instant(t[e], "UP") == pub[e]) for e in ("FAJR", "ISHA", "ASR")}
            out.append(f"{ev} {asr_sun[:4]}: Asr {n['ASR']}" + (f" Fajr {n['FAJR']} Isha {n['ISHA']}" if asr_sun == "GEOMETRIC" else ""))
    return "; ".join(out)


def method_of(p: dict) -> dict:
    return common.method_json(
        id=AUTHORITY, fajr={"type": "angle", "degrees": p["angles_FAJR"]}, isha={"type": "angle", "degrees": p["angles_ISHA"]},
        offsets={e: p["offsets"][e] for e in EVENTS if p["offsets"][e]}, rounding={e: p["modes"][e] for e in EVENTS},
        asrShadow=p["factor"], asrSun=p["asr_sun"],
        sunEvaluation={} if p["evaluation"] == "EVENT_INSTANT" else {e: p["evaluation"] for e in EVENTS},
        evidence={"kind": "PUBLISHED", "source": "A2CMGF's calendars name the Muslim World League"})


def preset() -> dict:
    """EuropeanPresets.GF_A2CMGF before this fit: MWL 18 / 17, the five prayers up and sunrise down."""
    return common.method_json(id=AUTHORITY, fajr={"type": "angle", "degrees": 18.0}, isha={"type": "angle", "degrees": 17.0},
                              rounding={e: ("DOWN" if e == "SUNRISE" else "UP") for e in EVENTS},
                              evidence={"kind": "PUBLISHED", "source": "preset"})


def score(method: dict, rows: list[tuple], into: common.Score | None = None) -> common.Score:
    sc = into if into is not None else common.Score()
    for town, day, mins in rows:
        out = common.compute(common.case(method, *TOWNS[town], TZ, day, country=COUNTRY))
        for e in EVENTS:
            sc.add(e, day, out[e][1] // 60, published(day, mins[e]) // 60)
    return sc


def shares(sc: common.Score) -> str:
    ex, w1 = sc.shares(), sc.within1_shares()
    return " ".join(f"{e[:4]} {100 * ex[e]:5.1f}/{100 * w1[e]:5.1f}" for e in EVENTS) + f"  max {sc.worst} ({sc.total['DHUHR']} days)"


def describe(p: dict) -> str:
    return (f"{p['evaluation']} (windows: " + ", ".join(f"{k} {v}" for k, v in p["totals"].items())
            + f"), Fajr {p['angles_FAJR']} Isha {p['angles_ISHA']} Asr factor {p['factor']}; "
            + ", ".join(f"{e[:4]} {p['modes'][e]} {p['offsets'][e]:+d} ({p['counts'][e]})" for e in EVENTS)
            + f"; free angles at the event instant: Fajr {p['free_FAJR']}, Isha {p['free_ISHA']}")


def main() -> int:
    cayenne = load("a2_cayenne_jan2026.csv", "Cayenne", 2026, 1)
    remire = load("a2_remire_jan2026.csv", "Remire-Montjoly", 2026, 1)
    mosque = load("a2_masjidbox_oct2026.csv", "Cayenne", 2026, 10)
    live = load("gf_islamicfinder_cayenne_oct2026.csv", "Cayenne", 2026, 10)
    print(f"gf_a2cmgf: {len(cayenne)} + {len(remire)} January days, {len(mosque)} Masjidbox days, {len(live)} live IslamicFinder days")
    fits = {"Cayenne": fit(cayenne), "Remire-Montjoly": fit(remire), "both": fit(cayenne + remire)}
    for name, p in fits.items():
        print(f"fit on {name}: {describe(p)}")
    held = common.Score()
    score(method_of(fits["Remire-Montjoly"]), cayenne, held)
    score(method_of(fits["Cayenne"]), remire, held)
    jan = common.Score()
    for e in EVENTS:
        jan.exact[e], jan.within1[e], jan.total[e] = held.exact[e], held.within1[e], held.total[e]
    jan.worst = held.worst
    score(method_of(fits["both"]), mosque, held)
    print("held out, exact/within 1 (%):")
    print("  January, each town by the other's fit: " + shares(jan))
    print("  Masjidbox October by the January fit: " + shares(score(method_of(fits["both"]), mosque)))
    print("  all held-out days:                     " + shares(held))
    print("live IslamicFinder Cayenne page (adds 1 min to Dhuhr and Maghrib), by the January fit:")
    print("  " + shares(score(method_of(fits["both"]), live)))
    print("the preset (MWL 18/17, up, sunrise down) on every day:")
    print("  " + shares(score(preset(), cayenne + remire + mosque)))
    for name, rows in (("Cayenne", cayenne), ("Remire-Montjoly", remire), ("Masjidbox", mosque)):
        print(f"check, exact under each evaluation (E4) and Asr sun (E13), {name}: " + evaluation_counts(rows))
    stats = common.fit_stats(
        [common.sample("A2CMGF January 2026 calendars (IslamicFinder pages it posts): Cayenne, Remire-Montjoly",
                       datetime.date(2026, 1, 1), datetime.date(2026, 1, 31), 31, 2)],
        [common.sample("A2CMGF January 2026 calendars, each town by the other's fit",
                       datetime.date(2026, 1, 1), datetime.date(2026, 1, 31), 31, 2),
         common.sample("The Cayenne mosque's Masjidbox page", datetime.date(2026, 10, 10), datetime.date(2026, 10, 15), 6, 1)],
        held)
    print("FitStats (held out; no asset, the method runs at the user's point):")
    print("  exact " + str(stats["exact"]))
    print("  within1 " + str(stats["within1"]) + f" maxDiffMin {stats['maxDiffMin']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
