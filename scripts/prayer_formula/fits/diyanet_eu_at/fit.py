#!/usr/bin/env python3
"""
Checks `diyanet_eu`, Austria's default (CountryMethodDefaults, plan 6.4, 7.2, 7.5), against IGGÖ's nine 2026 city
tables, fits each table's computing point (9.2, 9.4) and writes the first tier of Austria's zones (6.5),
android-app/app/src/main/assets/official-zones/diyanet_eu_at.json (schema 1): IGGÖ's nine state capitals, each with
its city centre as `point` (from which the 25 km are measured, `reachKm` 25, `mappingTier` 0) and its fitted point
as its E6 morning and evening points. The second tier, Diyanet's own Austrian entries (190 on 2026-10-11, tier 1, no
reach limit), is not in the local data, so a place beyond 25 km of every city keeps the method at its own point.

Data, read at run time from test-data-local and never copied (D13, A7):
- research-2026-10-11/AT/iggo2026.csv: IGGÖ's nine city tables (derislam.at), 1 April to 31 December 2026 (275 days
  each; the site shows no earlier 2026 month), Austrian clock time.
- research-2026-10-11/AT/iggo_locate_api_2026-10-11.txt: IGGÖ's "nearest place" API on 2026-10-11 (notes only).

The method is diyanet_eu as EuropeanPresets.DIYANET_EU stands (18 / 16, sunrise - 7, Dhuhr + 5, Asr + 4, Maghrib + 7,
the sun once a day at 0h UT, NEAREST, Diyanet's takdir from 44.5 N, 5 h minimum day and night): nothing of it is
refitted here. Its takdir constants (DiyanetTakdir.kt) were re-derived from these same tables
(fits/diyanet_takdir.py), so Fajr and Isha on the takdir days (May to August) are in-sample and scored apart.

Per city: the longitude from Dhuhr (windows, within 0.25 deg of the centre), then the latitude from sunrise, Asr,
Maghrib, and Fajr and Isha outside May-August (exact minutes on a 0.005 then 0.0005 deg grid within 0.15 deg; the
latitude most minutes allow, nearest the centre). Held out: month-split (points fitted on the odd months score the
even ones, and the reverse), with common.compute. A diagnostic prints the shape of IGGÖ's summer Fajr and Isha (the
takdir): the season's edges, the straight lines to and from 21 June, and the solstice depression's share of the
midnight one.

Usage: python scripts/prayer_formula/fits/diyanet_eu_at/fit.py
"""

from __future__ import annotations

import csv
import datetime
import math
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import common  # noqa: E402

ref = common.ref
EVENTS = common.EVENTS
AUTHORITY = "diyanet_eu_at"
METHOD_ID = "diyanet_eu"
TZ = "Europe/Vienna"
DATA = common.LOCAL_DATA / "research-2026-10-11" / "AT" / "iggo2026.csv"
OFFSETS = {"SUNRISE": -7, "DHUHR": 5, "ASR": 4, "MAGHRIB": 7}
TAKDIR_MONTHS = (5, 6, 7, 8)          # Fajr and Isha estimated by the takdir (9.2: "Fajr and Isha outside May-August")
SPAN = 0.15                            # degrees of latitude searched around the centre
SPAN_LNG = 0.25                        # degrees of longitude (Eisenstadt's table point lies about 14 km east-north-east, 7.5)
COARSE = 0.005
STEP = 0.0005
REACH_KM = 25.0                        # IGGÖ's rule (6.5): a point within 25 km of a city centre takes its table
# City centres (OpenStreetMap, as in fits/diyanet_takdir.py) and Arabic names.
CITIES = {
    "bregenz": ((47.5031, 9.7471), "بريغنتس"), "eisenstadt": ((47.8457, 16.5233), "أيزنشتات"),
    "graz": ((47.0707, 15.4395), "غراتس"), "innsbruck": ((47.2692, 11.4041), "إنسبروك"),
    "klagenfurt": ((46.6249, 14.3050), "كلاغنفورت"), "linz": ((48.3069, 14.2858), "لينتس"),
    "salzburg": ((47.8095, 13.0550), "سالزبورغ"), "st-poelten": ((48.2047, 15.6256), "سانت بولتن"),
    "wien": ((48.2082, 16.3738), "فيينا"),
}
SOURCE = "IGGÖ's 2026 city tables (derislam.at), April-December"
SOURCE_LINE = ("IGGÖ (Islamische Glaubensgemeinschaft in Österreich): its nine state capitals' 2026 tables, Diyanet's "
               "method; Diyanet's own Austrian entries (the second tier) not yet included")


def log(text: str = "") -> None:
    print(text, flush=True)


def method_json() -> dict:
    """EuropeanPresets.DIYANET_EU as it stands."""
    return common.method_json(
        id=METHOD_ID, fajr={"type": "angle", "degrees": 18.0}, isha={"type": "angle", "degrees": 16.0},
        offsets=dict(OFFSETS), sunEvaluation={e: "DAILY_0H_UT" for e in EVENTS},
        highLatitude={"type": "diyanet_takdir"}, polar="DIYANET_CLAMP", minDayNightHours=5,
        evidence={"kind": "FROM_TABLES", "source": "Re-derived from IGGÖ's and Diyanet's tables"})


def load(path: Path) -> dict[str, dict[datetime.date, list[str]]]:
    out: dict[str, dict] = {}
    for r in csv.DictReader(path.open(encoding="utf-8")):
        out.setdefault(r["city"], {})[datetime.date.fromisoformat(r["date"])] = [
            r[c] for c in ("fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha")]
    return out


def published(day: datetime.date, hhmm: str) -> int:
    return common.local_to_epoch(TZ, day, hhmm)


# ---- the sun once a day at 0h UT (E4 DAILY_0H_UT), the reference's primitives -------------------------------------

_SUNS: dict = {}


def sun_of(day):
    if day not in _SUNS:
        cyc = ref.Cycle(day)
        _SUNS[day] = (cyc, ref.SunFunction(ref.SUN_MODELS["MEEUS"], "DAILY_0H_UT", cyc.jd0))
    return _SUNS[day]


def raw(day, lat, lng, event):
    """The unrounded instant before offsets (no takdir): Fajr 18, Isha 16, sunrise/sunset -0.8333, Asr factor 1."""
    cyc, sun = sun_of(day)
    t0 = ref.transit(cyc, sun, lng)
    if event == "DHUHR":
        return cyc.instant(t0)
    if event == "ASR":
        decl = sun(cyc.jd0 + t0 / 24)[0]
        alt, side = ref.atand(1 / (1 + ref.tand(abs(lat - decl)))), 1
    else:
        alt, side = {"FAJR": (-18.0, -1), "SUNRISE": (-0.8333, -1), "MAGHRIB": (-0.8333, 1), "ISHA": (-16.0, 1)}[event]
    return cyc.instant(ref.event(cyc, sun, lat, lng, alt, side, t0))


def matches(t, event, pub) -> bool:
    return t is not None and ref.round_instant(t + 60 * OFFSETS.get(event, 0), "NEAREST") == pub


def best_cover(intervals, lo, hi, prefer):
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


def fit_point(centre, rows, months=None) -> tuple[float, float, int, int]:
    """The longitude Dhuhr's windows allow (nearest the centre), then the latitude the most of the other times allow."""
    lat0, lng0 = centre
    days = [(d, t) for d, t in sorted(rows.items()) if months is None or d.month in months]
    ivs = []
    for d, t in days:
        base = raw(d, lat0, lng0, "DHUHR") + 60 * OFFSETS["DHUHR"]
        pub = published(d, t[2])
        ivs.append((lng0 + (base - pub - 30) / 240, lng0 + (base - pub + 30) / 240))
    nd, lng = best_cover(ivs, lng0 - SPAN_LNG, lng0 + SPAN_LNG, lng0)

    def count(lat):
        n = 0
        for d, t in days:
            for j, e in enumerate(EVENTS):
                if e == "DHUHR" or (e in ("FAJR", "ISHA") and d.month in TAKDIR_MONTHS):
                    continue
                n += matches(raw(d, lat, lng, e), e, published(d, t[j]))
        return n

    # A 0.005 deg grid over the span, then 0.0005 deg around its best cell; ties go to the latitude nearest the centre.
    coarse = max(((count(lat0 + i * COARSE), -abs(i)), lat0 + i * COARSE) for i in range(-round(SPAN / COARSE), round(SPAN / COARSE) + 1))
    best = max(((count(coarse[1] + i * STEP), -abs(coarse[1] + i * STEP - lat0)), coarse[1] + i * STEP)
               for i in range(-round(COARSE / STEP), round(COARSE / STEP) + 1))
    return round(best[1], 5), round(lng, 5), nd, best[0][0]


def zone(city: str, point: tuple[float, float] | None) -> dict:
    centre = CITIES[city][0]
    fields = {"authority": AUTHORITY}
    if point is not None:
        fields["morningPoint"] = common.point(*point)
        fields["eveningPoint"] = common.point(*point)
    return common.zone_json(city, centre[0], centre[1], **fields)


def score(method, z, centre, rows, months=None, in_sample_takdir=True) -> tuple[common.Score, common.Score, int]:
    """(the times outside the takdir, Fajr and Isha on the takdir days, ESTIMATED count): common.compute at the zone."""
    main, takdir, estimated = common.Score(), common.Score(), 0
    for d, t in sorted(rows.items()):
        if months is not None and d.month not in months:
            continue
        out = common.compute(common.case(method, centre[0], centre[1], TZ, d, zone=z, country="AT"))
        for j, e in enumerate(EVENTS):
            _, minute, status = out[e]
            estimated += status == "ESTIMATED"
            target = takdir if (e in ("FAJR", "ISHA") and d.month in TAKDIR_MONTHS) else main
            target.add(e, d, minute // 60, published(d, t[j]) // 60)
    return main, takdir, estimated


def merge(into: common.Score, sc: common.Score) -> None:
    for e in EVENTS:
        into.exact[e] += sc.exact[e]
        into.within1[e] += sc.within1[e]
        into.total[e] += sc.total[e]
        for m in range(12):
            into.months[e][m] += sc.months[e][m]
    into.worst = max(into.worst, sc.worst)
    into.misses += sc.misses


def shares(sc: common.Score, events=EVENTS) -> str:
    ex, w1 = sc.shares(), sc.within1_shares()
    return " ".join(f"{e[:4]} {100 * ex[e]:5.1f}/{100 * w1[e]:5.1f}" for e in events if sc.total[e]) + f"  max {sc.worst}"


SOLSTICE = datetime.date(2026, 6, 21)


def takdir_shape(city: str, rows, point) -> list[str]:
    """IGGÖ's summer Fajr and Isha: the days off the plain 18 / 16 deg time by more than 1 min, the straight lines
    (UT clock time) from the last plain day to 21 June and from 21 June to the next plain day, the largest distance of
    a printed minute from those lines, and at 21 June the printed time's depression as a share of the midnight one."""
    lat, lng = point
    out = []
    for j, e in ((0, "FAJR"), (5, "ISHA")):
        def ut(d):
            return (published(d, rows[d][j]) - (datetime.datetime(d.year, d.month, d.day) - datetime.datetime(1970, 1, 1)).total_seconds()) / 60
        off = [d for d in sorted(rows) if abs(published(d, rows[d][j]) - raw(d, lat, lng, e)) > 60]
        start, end = off[0] - datetime.timedelta(days=1), off[-1] + datetime.timedelta(days=1)
        sol = ut(SOLSTICE)
        worst = 0.0
        for d in off:
            line = ut(start) + (sol - ut(start)) * (d - start).days / (SOLSTICE - start).days if d <= SOLSTICE else \
                sol + (ut(end) - sol) * (d - SOLSTICE).days / (end - SOLSTICE).days
            worst = max(worst, abs(ut(d) - line))
        cyc, sun = sun_of(SOLSTICE)
        t0 = ref.transit(cyc, sun, lng)
        midnight = -ref.altitude(cyc, sun, lat, lng, t0 + 12)
        dep = -ref.altitude(cyc, sun, lat, lng, cyc.hours(published(SOLSTICE, rows[SOLSTICE][j]) + 30))

        def plain_minutes(d):   # the plain angle time's distance from sunrise (Fajr) or sunset (Isha)
            if e == "FAJR":
                return (raw(d, lat, lng, "SUNRISE") - raw(d, lat, lng, "FAJR")) / 60
            return (raw(d, lat, lng, "ISHA") - raw(d, lat, lng, "MAGHRIB")) / 60

        out.append(f"{e[:4]} plain until {start} and again from {end}, {(sol - ut(start)) / (SOLSTICE - start).days:+.3f} then "
                   f"{(ut(end) - sol) / (end - SOLSTICE).days:+.3f} min/day, at most {worst:.1f} min off those lines; "
                   f"21 June at {dep:.2f} deg = {dep / midnight:.3f} of the midnight {midnight:.2f}; plain "
                   f"{'Fajr to sunrise' if e == 'FAJR' else 'sunset to Isha'} {plain_minutes(start):.1f} min on {start}, "
                   f"{plain_minutes(end):.1f} on {end}")
    return out


def km(a, b) -> float:
    return math.hypot((a[0] - b[0]) * 111.2, (a[1] - b[1]) * 111.2 * math.cos(math.radians(b[0])))


def main() -> int:
    started = time.time()
    tables = load(DATA)
    method = method_json()
    log(f"diyanet_eu in Austria: IGGÖ's tables for {len(tables)} cities, {sum(len(v) for v in tables.values())} city-days "
        f"({min(min(v) for v in tables.values())} to {max(max(v) for v in tables.values())})")
    zones, total_held, total_takdir, total_centre = [], common.Score(), common.Score(), common.Score()
    for city in sorted(tables):
        rows = tables[city]
        centre = CITIES[city][0]
        months = sorted({d.month for d in rows})
        odd, even = [m for m in months if m % 2 == 1], [m for m in months if m % 2 == 0]
        lat, lng, nd, nl = fit_point(centre, rows)
        p_odd = fit_point(centre, rows, odd)[:2]
        p_even = fit_point(centre, rows, even)[:2]
        held, held_takdir = common.Score(), common.Score()
        for part, other in ((odd, p_even), (even, p_odd)):
            m_sc, t_sc, _ = score(method, zone(city, other), centre, rows, part)
            merge(held, m_sc)
            merge(held_takdir, t_sc)
        ins, ins_takdir, est = score(method, zone(city, (lat, lng)), centre, rows)
        at_centre, _, _ = score(method, zone(city, None), centre, rows)
        merge(total_held, held)
        merge(total_takdir, ins_takdir)
        merge(total_centre, at_centre)
        log(f"{city}: point {lat:.5f} {lng:.5f}, {km((lat, lng), centre):.1f} km from the centre (Dhuhr windows {nd}, "
            f"other times {nl}); odd-month point {p_odd}, even-month point {p_even}")
        log(f"  held out (month-split), outside the takdir: {shares(held)}")
        log(f"  in-sample, outside the takdir:              {shares(ins)}  ESTIMATED {est}")
        log(f"  Fajr and Isha on the takdir days (May-Aug): {shares(ins_takdir, ('FAJR', 'ISHA'))}")
        log(f"  at the city centre, outside the takdir:     {shares(at_centre)}")
        for line in takdir_shape(city, rows, (lat, lng)):
            log(f"  IGGÖ's takdir: {line}")
        stats = common.fit_stats(
            [common.sample(SOURCE, min(rows), max(rows), len(rows), 1)],
            [common.sample(SOURCE + ", held out by month; Fajr and Isha outside May-August", min(rows), max(rows), len(rows), 1,
                           month_split=True)],
            held)
        z = {k: v for k, v in zone(city, (lat, lng)).items() if k != "authority"}
        z.update({"nameAr": CITIES[city][1], "reachKm": REACH_KM, "mappingTier": 0,
                  "namedPlaces": [{"centre": common.point(*centre), "catalogueIds": [], "areaKm": 0.0}], "fit": stats})
        zones.append(z)
        log(f"  ({time.time() - started:.0f} s)")
    log("all nine, held out (month-split), outside the takdir: " + shares(total_held))
    log("all nine, Fajr and Isha on the takdir days (in-sample for the takdir): " + shares(total_takdir, ("FAJR", "ISHA")))
    log("all nine at the city centres, outside the takdir: " + shares(total_centre))
    days = sum(len(v) for v in tables.values())
    first = min(min(v) for v in tables.values())
    last = max(max(v) for v in tables.values())
    profile = common.fit_stats(
        [common.sample(SOURCE, first, last, days, len(tables))],
        [common.sample(SOURCE + ", held out by month; Fajr and Isha outside May-August", first, last, days, len(tables),
                       month_split=True)],
        total_held)
    path = common.write_asset(AUTHORITY, METHOD_ID, "AT", "NEAREST", zones, fit=profile, source=SOURCE_LINE)
    log(f"wrote {path.relative_to(common.ROOT)} ({len(zones)} zones) in {time.time() - started:.0f} s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
