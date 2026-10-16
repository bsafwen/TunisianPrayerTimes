#!/usr/bin/env python3
"""
Fits `mq_ccim`, the Centre Culturel Islamique de la Martinique's method (plan 7.6; milestone M3, 9.4). The CCIM
names the Muslim World League and links IslamicFinder's Fort-de-France page for it; the method is computed at the
user's point anywhere in Martinique, so there is no official-zone asset; this script reports the profile and its
scores.

Data, read at run time from test-data-local and never copied (D13, A7):
- research-2026-10-11/FR/mq_islamicfinder_fdf_oct2026.csv: that IslamicFinder page, October 2026 (31 days), computed
  here at Fort-de-France's GeoNames point (geonames.org, read 2026-10-15), as the app computes at the user's point.

Stages (9.4), each by windows (a published minute bounds the unrounded time to a minute under E1; the value most
windows cover wins): 0. one sun evaluation (E4) for all six times (event instant, daily 0h UT, daily 10h UT, KACST),
by the windows the six rules below then cover; 1. sunrise, Dhuhr and Maghrib (rounding and whole-minute offset);
2. Fajr and Isha at the named MWL angles, 18 and 17 deg (rounding; the angle most windows allow at the event instant is
printed as a check); 3. Asr (factor, rounding, offset). Equal counts keep the event instant, factor 1, UP before
NEAREST before DOWN (sunrise: DOWN first) and the smallest offset. The sun model stays MEEUS and Asr's sun geometric
(E13): a month of days cannot choose them (the other evaluations and suns are printed as a check).
Held out: one month only, so a day-split: the stages fitted on the odd days and scored on the even ones, and the
reverse, every day with common.compute (the reference engine).

Usage: python scripts/prayer_formula/fits/mq_ccim/fit.py
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
AUTHORITY = "mq_ccim"
TZ = "America/Martinique"                 # UTC-4 all year
COUNTRY = "MQ"
DATA = common.LOCAL_DATA / "research-2026-10-11" / "FR" / "mq_islamicfinder_fdf_oct2026.csv"
FORT_DE_FRANCE = (14.604, -61.074)        # GeoNames, read 2026-10-15
PUBLISHED = {"FAJR": 18.0, "ISHA": 17.0}
WINDOW = {"NEAREST": (-30.0, 30.0), "UP": (-60.0, 0.0), "DOWN": (0.0, 60.0)}
PRAYER_MODES = ("UP", "NEAREST", "DOWN")
SUNRISE_MODES = ("DOWN", "NEAREST", "UP")
EVALS = ("EVENT_INSTANT", "DAILY_0H_UT", "DAILY_10H_UT", "KACST", "LOCAL_MIDNIGHT_SHADOW")
KEYS = dict(zip(EVENTS, ("fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha")))


def load() -> list[tuple]:
    with open(DATA, encoding="utf-8") as fh:
        return [(datetime.date(2026, 10, int(r["day"])), {e: r[KEYS[e]] for e in EVENTS}) for r in csv.DictReader(fh)]


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


def unrounded(rows: list[tuple], factor: int = 1, evaluation: str = "EVENT_INSTANT", asr_sun: str = "GEOMETRIC") -> list[dict]:
    """The reference engine's unrounded times of each day (no offsets), the sun evaluated at `evaluation` for all six
    (LOCAL_MIDNIGHT_SHADOW: Asr only)."""
    events = ("ASR",) if evaluation == "LOCAL_MIDNIGHT_SHADOW" else EVENTS
    method = common.method_json(id=AUTHORITY, fajr={"type": "angle", "degrees": PUBLISHED["FAJR"]},
                                isha={"type": "angle", "degrees": PUBLISHED["ISHA"]}, asrShadow=factor, asrSun=asr_sun,
                                sunEvaluation={e: evaluation for e in events})
    return [{e: v[0] for e, v in common.compute(common.case(method, *FORT_DE_FRANCE, TZ, day, country=COUNTRY)).items()}
            for day, mins in rows]


def depression(day: datetime.date, t: float) -> float:
    lat, lng = FORT_DE_FRANCE
    cyc = ref.Cycle(day)                      # U = D at UTC-4 (12:00 + 4 h - 4.07 h)
    sun = ref.SunFunction(ref.meeus_sun, "EVENT_INSTANT", cyc.jd0)
    return -ref.altitude(cyc, sun, lat, lng, cyc.hours(t))


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
    pubs = [{e: published(day, mins[e]) for e in EVENTS} for day, mins in rows]
    best, totals = None, {}
    for ev in EVALS[:4]:
        times = unrounded(rows, 1, ev)
        rules = rules_at(times, pubs)
        key = (sum(n for n, _, _ in rules.values()), -EVALS.index(ev))
        totals[ev] = key[0]
        if best is None or key > best[0]:
            best = (key, ev, rules)
    (total, _), ev, rules = best
    two = rules_at(unrounded(rows, 2, ev), pubs)["ASR"]
    factor = 2 if two[0] > rules["ASR"][0] else 1
    if factor == 2:
        rules["ASR"] = two
    p = {"evaluation": ev, "factor": factor, "total": total, "totals": totals,
         "counts": {e: r[0] for e, r in rules.items()}, "modes": {e: r[1] for e, r in rules.items()},
         "offsets": {e: r[2] for e, r in rules.items()}}
    for e in ("FAJR", "ISHA"):                # a check: the angle most windows allow
        free = None
        for mode in PRAYER_MODES:
            lo, hi = WINDOW[mode]
            ivs = []
            for (day, mins), pub in zip(rows, pubs):
                da, db = depression(day, pub[e] + lo), depression(day, pub[e] + hi)
                ivs.append((min(da, db), max(da, db)))
            n, angle = best_cover(ivs, 10.0, 22.0, PUBLISHED[e])
            if free is None or (n, -PRAYER_MODES.index(mode)) > free[0]:
                free = ((n, -PRAYER_MODES.index(mode)), mode, round(angle, 2))
        p["free_" + e] = f"{free[2]} {free[1]} ({free[0][0]})"
    return p


def method_of(p: dict) -> dict:
    return common.method_json(
        id=AUTHORITY, fajr={"type": "angle", "degrees": PUBLISHED["FAJR"]}, isha={"type": "angle", "degrees": PUBLISHED["ISHA"]},
        offsets={e: p["offsets"][e] for e in EVENTS if p["offsets"][e]}, rounding={e: p["modes"][e] for e in EVENTS},
        sunEvaluation={} if p["evaluation"] == "EVENT_INSTANT" else {e: p["evaluation"] for e in EVENTS},
        asrShadow=p["factor"], evidence={"kind": "FROM_TABLES", "source": "The IslamicFinder page the CCIM links"})


def preset() -> dict:
    """EuropeanPresets.MQ_CCIM before this fit: MWL 18 / 17, Dhuhr and Maghrib + 1, the five prayers up, sunrise down."""
    return common.method_json(id=AUTHORITY, fajr={"type": "angle", "degrees": 18.0}, isha={"type": "angle", "degrees": 17.0},
                              offsets={"DHUHR": 1, "MAGHRIB": 1}, rounding={e: ("DOWN" if e == "SUNRISE" else "UP") for e in EVENTS},
                              evidence={"kind": "FROM_TABLES", "source": "preset"})


def mwl() -> dict:
    """The plain `mwl` reference preset: 18 / 17, nearest rounding, no offsets."""
    return common.method_json(id="mwl", fajr={"type": "angle", "degrees": 18.0}, isha={"type": "angle", "degrees": 17.0},
                              evidence={"kind": "PUBLISHED", "source": "mwl"})


def score(method: dict, rows: list[tuple], into: common.Score | None = None) -> common.Score:
    sc = into if into is not None else common.Score()
    for day, mins in rows:
        out = common.compute(common.case(method, *FORT_DE_FRANCE, TZ, day, country=COUNTRY))
        for e in EVENTS:
            sc.add(e, day, out[e][1] // 60, published(day, mins[e]) // 60)
    return sc


def shares(sc: common.Score) -> str:
    ex, w1 = sc.shares(), sc.within1_shares()
    return " ".join(f"{e[:4]} {100 * ex[e]:5.1f}/{100 * w1[e]:5.1f}" for e in EVENTS) + f"  max {sc.worst} ({sc.total['DHUHR']} days)"


def describe(p: dict) -> str:
    return (f"{p['evaluation']} (windows: " + ", ".join(f"{k} {v}" for k, v in p["totals"].items())
            + f"), Fajr 18.0 Isha 17.0 Asr factor {p['factor']}; "
            + ", ".join(f"{e[:4]} {p['modes'][e]} {p['offsets'][e]:+d} ({p['counts'][e]})" for e in EVENTS)
            + f"; free angles at the event instant: Fajr {p['free_FAJR']}, Isha {p['free_ISHA']}")


def evaluation_counts(rows: list[tuple]) -> str:
    pubs = [{e: published(day, mins[e]) for e in EVENTS} for day, mins in rows]
    out = []
    for ev in EVALS:
        for asr_sun in ("GEOMETRIC", "APPARENT"):
            times = unrounded(rows, 1, ev, asr_sun)
            n = {e: sum(1 for t, pub in zip(times, pubs) if ref.round_instant(t[e], "UP") == pub[e]) for e in ("FAJR", "ISHA", "ASR")}
            out.append(f"{ev} {asr_sun[:4]}: Asr {n['ASR']}" + (f" Fajr {n['FAJR']} Isha {n['ISHA']}" if asr_sun == "GEOMETRIC" else ""))
    return "; ".join(out)


def main() -> int:
    rows = load()
    print(f"mq_ccim: {len(rows)} days of IslamicFinder's Fort-de-France page (October 2026)")
    halves = {"odd": [r for r in rows if r[0].day % 2 == 1], "even": [r for r in rows if r[0].day % 2 == 0]}
    fits = {name: fit(part) for name, part in halves.items()}
    for name, p in fits.items():
        print(f"fit on the {name} days: {describe(p)}")
    final = fit(rows)
    print(f"fit on all days: {describe(final)}")
    held = common.Score()
    score(method_of(fits["even"]), halves["odd"], held)
    score(method_of(fits["odd"]), halves["even"], held)
    print("held out (day-split), exact/within 1 (%):")
    print("  " + shares(held))
    print("the preset before this fit (MWL 18/17, Dhuhr and Maghrib + 1, up, sunrise down):")
    print("  " + shares(score(preset(), rows)))
    print("plain mwl (nearest, no offsets):")
    print("  " + shares(score(mwl(), rows)))
    print("check, exact under each evaluation (E4) and Asr sun (E13): " + evaluation_counts(rows))
    stats = common.fit_stats(
        [common.sample("IslamicFinder's Fort-de-France page (linked by the CCIM)", datetime.date(2026, 10, 1),
                       datetime.date(2026, 10, 31), 31, 1)],
        [common.sample("IslamicFinder's Fort-de-France page, day-split", datetime.date(2026, 10, 1),
                       datetime.date(2026, 10, 31), 31, 1)],
        held)
    print("FitStats (held out; no asset, the method runs at the user's point):")
    print("  exact " + str(stats["exact"]))
    print("  within1 " + str(stats["within1"]) + f" maxDiffMin {stats['maxDiffMin']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
