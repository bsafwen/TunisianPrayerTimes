#!/usr/bin/env python3
"""
Fits `re_cir`, the Centre Islamique de la Reunion's table (plan 7.6, Q34; milestone M3, 9.4), and writes its
official-zone asset, android-app/app/src/main/assets/official-zones/re_cir.json (schema 1).

Data, read at run time from test-data-local and never copied (D13, A7), in research-2026-10-11/FR/:
- cir_ramadan1445.csv, cir_chawwal1445.csv, cir_ramadan1446.csv: the CIR's tables of 2024 and 2025; bh_ramadan1447.csv:
  Baytoul Hayaate's 2026 copy. Four Fajr columns (Nord, Est, Sud, Ouest) and three iftar columns (Nord/Sud/Est,
  Ouest, Le Tampon), 81 month-days from 19 February to 10 May, equal by month and day in every year: one perpetual
  table. Their sehri (imsak) columns are not used (A14).
- cir_ramadan1444.csv: the 2023 table, with the same iftar columns by month and day and an earlier pair of Fajr
  columns (Nord-Est, Sud-Ouest), which the four-region table replaced; only its iftar rows are scored.
- brasfusil_masjidbox_2026-10.csv: the Bras-Fusil mosque's display (Saint-Benoit, 12-17 October 2026), all six times:
  its Fajr and Isha are checked at Saint-Benoit's point (exact coordinates), as a managed display computes for its
  mosque (9.2); its minute offsets are not adopted (D15).

The CIR prints no Dhuhr, Asr or Isha, and names no method. The profile keeps the plan's rules (Fajr and Isha 18 deg,
Maghrib = sunset + 2 rounded up, the five prayers up and sunrise down, the CIR's elevation shifts, E16): a minute
offset or another rounding is the same column at a point 0.125 deg of longitude away, so the columns cannot tell
them apart. Each zone computes Fajr and sunrise at one of its region's listed localities (E6 morningPoint) and the
other times at one listed locality of the Nord/Sud/Est iftar column (eveningPoint), with fractional zone offsets
(E7) on Fajr and Maghrib, so sunrise stays the locality's own. A point fitted freely to one Fajr column drifts up to
25 km out to sea (its latitude is barely bound by 81 days around the equinox) and moves sunrise with it. Fitted by
windows (a published minute bounds the unrounded time to a minute; the value most windows cover wins):
1. The reference year (E5) of each column family, among 2020-2024: the iftar columns, already printed in 2023, fit a
   leap-year phase; the four Fajr columns, new in 2024, the phase of the year after one.
2. The evening locality, and its Maghrib offset, for the Nord/Sud/Est iftar column (sunset + 2, up).
3. Each region's morning locality, and its Fajr offset, for its Fajr column (18 deg, up).
4. The whole minutes between the iftar columns: Ouest = Nord/Sud/Est + 1, Le Tampon = + 2 (zone offsets).
Another listed locality replaces the region's main town (Saint-Denis for the evening) only where it covers more than
5 % more windows. Le Tampon's zone takes Sud's morning locality (its Fajr is the Sud column) and adds 2 min to
Maghrib and Isha, which plan 7.6 counts as the first 2 min of the place's elevation shift (E16).
Held out: month-split by month-day (fitted on March and May, scored on February and April, and the reverse), every
printed row scored at its own date (2023-2026), as the app computes it (A8), with common.compute.

Usage: python scripts/prayer_formula/fits/re_cir/fit.py
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
AUTHORITY = "re_cir"
TZ = "Indian/Reunion"                      # UTC+4 all year
FR = common.LOCAL_DATA / "research-2026-10-11" / "FR"
PERPETUAL = ("cir_ramadan1445.csv", "cir_chawwal1445.csv", "cir_ramadan1446.csv", "bh_ramadan1447.csv")
OLD = "cir_ramadan1444.csv"
BRAS_FUSIL = "brasfusil_masjidbox_2026-10.csv"
YEARS = (2020, 2021, 2022, 2023, 2024)
UP = (-60.0, 0.0)                        # an UP minute holds the unrounded time in (pub - 60, pub]
MAGHRIB_MIN = 2

# GeoNames (geonames.org, read 2026-10-15): the localities the CIR's tables list per region (plan 7.6).
TOWNS = {
    "Saint-Denis": (-20.882, 55.450), "Sainte-Marie": (-20.897, 55.551), "Sainte-Suzanne": (-20.906165, 55.607877),
    "La Montagne": (-20.888773, 55.422672),
    "Saint-Benoit": (-21.038, 55.715), "Saint-Andre": (-20.963, 55.650), "Bras-Panon": (-20.996674, 55.678233),
    "Sainte-Anne": (-21.085221, 55.749435), "Cambuston": (-20.93338, 55.647644),
    "Saint-Leu": (-21.171, 55.288), "Saint-Louis": (-21.286, 55.411), "Saint-Pierre": (-21.339, 55.478),
    "Saint-Joseph": (-21.378, 55.617), "Le Tampon": (-21.277, 55.518),
    "La Possession": (-20.929, 55.335), "Le Port": (-20.938, 55.294), "Saint-Paul": (-21.010, 55.271),
    "Saint-Gilles les Bains": (-21.052031, 55.225708), "La Saline": (-21.084259, 55.268925),
}
# zone id: (Arabic name, main town, named localities, Fajr column, iftar column)
ZONES = {
    "nord": ("شمال الجزيرة", "Saint-Denis", ("Saint-Denis", "Sainte-Marie", "Sainte-Suzanne", "La Montagne"), "fajr_n", "iftar_nse"),
    "est": ("شرق الجزيرة", "Saint-Benoit", ("Saint-Benoit", "Saint-Andre", "Bras-Panon", "Sainte-Anne", "Cambuston"), "fajr_e", "iftar_nse"),
    "sud": ("جنوب الجزيرة", "Saint-Pierre", ("Saint-Leu", "Saint-Louis", "Saint-Pierre", "Saint-Joseph"), "fajr_s", "iftar_nse"),
    "ouest": ("غرب الجزيرة", "Saint-Paul", ("La Possession", "Le Port", "Saint-Paul", "Saint-Gilles les Bains", "La Saline"), "fajr_o", "iftar_o"),
    "sud_tampon": ("جنوب الجزيرة", "Le Tampon", ("Le Tampon",), "fajr_s", "iftar_tampon"),
}
FAJR_COLUMNS = ("fajr_n", "fajr_e", "fajr_s", "fajr_o")
IFTAR_COLUMNS = ("iftar_nse", "iftar_o", "iftar_tampon")
CIR_SHIFTS = [{"fromM": h, "minutes": m} for h, m in ((100, 1.0), (200, 1.5), (300, 2.0), (500, 3.0), (800, 4.0),
                                                       (1100, 5.0), (1600, 6.0), (2100, 7.0))]
SOURCE_LINE = ("Centre Islamique de la Réunion: Ramadan 1444-1446 and Chawwal 1445 tables (2023-2025) and Baytoul "
               "Hayaate's Ramadan 1447 copy (2026), one perpetual table; four regions and the Le Tampon iftar column")


# ---- data -------------------------------------------------------------------------------------------------------

def minutes(text: str) -> int:
    h, m = text.split(":")
    return int(h) * 60 + int(m)


def load() -> tuple[dict, list]:
    """The perpetual table by (month, day) -> {column: minute}, and every printed row (date, column, minute)."""
    table, printed = {}, []
    for name in PERPETUAL + (OLD,):
        with open(FR / name, encoding="utf-8") as fh:
            for row in csv.DictReader(fh):
                day = datetime.date.fromisoformat(row["date"])
                for col, text in row.items():
                    if not text or col == "date":
                        continue
                    if col in FAJR_COLUMNS and name != OLD or col in IFTAR_COLUMNS:
                        value = minutes(text)
                        cell = table.setdefault((day.month, day.day), {})
                        assert cell.get(col, value) == value, (name, day, col)   # one table by month and day
                        cell[col] = value
                        printed.append((day, col, value))
    return table, printed


def published(day: datetime.date, minute: int) -> int:
    return common.local_to_epoch(TZ, day, f"{minute // 60}:{minute % 60:02d}")


# ---- fast raw events (the reference's own primitives) ---------------------------------------------------------------

_CACHE: dict = {}


def raw_event(day, lat, lng, alt, side):
    key = (day, lat, lng, alt, side)
    if key not in _CACHE:
        cyc = ref.Cycle(day)                       # U = D in Reunion (12:00 - 4 h + 3.7 h UTC)
        sun = ref.SunFunction(ref.meeus_sun, "EVENT_INSTANT", cyc.jd0)
        t0 = ref.transit(cyc, sun, lng)
        _CACHE[key] = cyc.instant(ref.event(cyc, sun, lat, lng, alt, side, t0))
    return _CACHE[key]


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


def offset_windows(cells, year, town, alt, side, base_s):
    """Per cell (month-day, minute), the zone offsets (minutes) that put the town's event + base_s on the minute
    rounded up: pub - 60 < t + x <= pub."""
    out = []
    for (mo, d), minute in cells:
        day = datetime.date(year, mo, d)
        t = raw_event(day, *TOWNS[town], alt, side) + base_s
        pub = published(day, minute)
        out.append(((pub + UP[0] - t) / 60, (pub + UP[1] - t) / 60))
    return out


def fit_locality(cells, year, towns, alt, side, base_s):
    """The region's main town (first in `towns`) and its fractional zone offset (E7), unless another listed locality
    covers more than 5 % more windows (a window or two is no evidence, and the locality sets the zone's sunrise or its
    unpublished Dhuhr, Asr and Isha)."""
    found = []
    for town in towns:
        n, x = best_cover(offset_windows(cells, year, town, alt, side, base_s), -3.0, 3.0, 0.0)
        found.append((n, town, round(x, 2)))
    best = max(found, key=lambda f: f[0])
    return best if best[0] > 1.05 * found[0][0] else found[0]


def fit(table: dict, mds: set) -> dict:
    """Reference years, the evening locality, each region's morning locality, their offsets and the iftar minutes,
    on the month-days `mds`."""
    def cells(col):
        return [(md, v[col]) for md, v in sorted(table.items()) if md in mds and col in v]

    regions = ("nord", "est", "sud", "ouest")
    nse = []                                   # the Nord, Est and Sud localities, Saint-Denis first
    for z in ("nord", "est", "sud"):
        for t in (ZONES[z][1],) + ZONES[z][2]:
            if t not in nse:
                nse.append(t)
    morning_towns = {z: (ZONES[z][1],) + tuple(t for t in ZONES[z][2] if t != ZONES[z][1]) for z in regions}

    def iftar(year):
        return fit_locality(cells("iftar_nse"), year, nse, -0.8333, 1, 60 * MAGHRIB_MIN)

    def fajr(year, z):
        return fit_locality(cells(ZONES[z][3]), year, morning_towns[z], -18.0, -1, 0)

    # Stage 1: the reference year of each family, by the windows it lets the best localities cover.
    iftar_year = max(YEARS, key=lambda y: (iftar(y)[0], y))
    fajr_year = max(YEARS, key=lambda y: (sum(fajr(y, z)[0] for z in regions), y))
    # Stages 2 and 3: the evening locality and each region's morning locality, with their offsets.
    n, evening, evening_off = iftar(iftar_year)
    counts = {"iftar_nse": n}
    mornings = {}
    for z in regions:
        n, town, off = fajr(fajr_year, z)
        mornings[z] = (town, off)
        counts[ZONES[z][3]] = n
    mornings["sud_tampon"] = mornings["sud"]
    # Stage 4: the whole minutes from the Nord/Sud/Est iftar column.
    shifts = {}
    for col in ("iftar_o", "iftar_tampon"):
        diffs = [v[col] - v["iftar_nse"] for md, v in table.items() if md in mds and col in v and "iftar_nse" in v]
        shifts[col] = max(set(diffs), key=diffs.count)
        counts[col] = diffs.count(shifts[col])
    return {"iftar_year": iftar_year, "fajr_year": fajr_year, "evening": (evening, evening_off), "mornings": mornings,
            "shifts": shifts, "counts": counts}


# ---- profile, zones and scores ----------------------------------------------------------------------------------

def method() -> dict:
    """EuropeanPresets.RE_CIR: 18 / 18, Maghrib + 2, the five prayers up and sunrise down, the CIR's shifts."""
    rounding = {e: ("DOWN" if e == "SUNRISE" else "UP") for e in EVENTS}
    return common.method_json(id=AUTHORITY, fajr={"type": "angle", "degrees": 18.0}, isha={"type": "angle", "degrees": 18.0},
                              offsets={"MAGHRIB": MAGHRIB_MIN}, rounding=rounding, elevationShifts=CIR_SHIFTS,
                              evidence={"kind": "FROM_TABLES", "source": "The CIR's tables"})


def zone_dicts(p: dict) -> dict:
    out = {}
    evening_town, evening_off = p["evening"]
    for z, (name_ar, town, named, fajr_col, iftar_col) in ZONES.items():
        morning_town, fajr_off = p["mornings"][z]
        maghrib = evening_off + (p["shifts"][iftar_col] if iftar_col == "iftar_o" else 0)
        offsets = {e: round(v, 2) for e, v in (("FAJR", fajr_off), ("MAGHRIB", maghrib)) if v}
        # Plan 7.6: Le Tampon's column moves Maghrib and Isha at least + 2, as the first minutes of the E16 shift.
        floor = {"elevationShiftFloor": float(p["shifts"][iftar_col])} if iftar_col == "iftar_tampon" else {}
        out[z] = common.zone_json(z, *TOWNS[town], authority=AUTHORITY, offsets=offsets, **floor,
                                  morningPoint=None if morning_town == town else common.point(*TOWNS[morning_town]),
                                  eveningPoint=None if evening_town == town else common.point(*TOWNS[evening_town]))
    return out


def score_rows(zones: dict, printed: list, mds: set | None) -> dict[str, common.Score]:
    """Each zone's Fajr against its Fajr column and its Maghrib against its iftar column, every printed row at its own
    date (the row's month-day in `mds`), as the app computes it."""
    m = method()
    scores = {z: common.Score() for z in ZONES}
    cache = {}
    for day, col, minute in printed:
        if mds is not None and (day.month, day.day) not in mds:
            continue
        for z, (_, town, _, fajr_col, iftar_col) in ZONES.items():
            if col not in (fajr_col, iftar_col):
                continue
            key = (z, day)
            if key not in cache:
                cache[key] = common.compute(common.case(m, *TOWNS[town], TZ, day, zone=zones[z], country="RE"))
            event = "FAJR" if col == fajr_col else "MAGHRIB"
            scores[z].add(event, day, cache[key][event][1] // 60, published(day, minute) // 60)
    return scores


def merge(into: common.Score, sc: common.Score) -> None:
    for e in EVENTS:
        into.exact[e] += sc.exact[e]
        into.within1[e] += sc.within1[e]
        into.total[e] += sc.total[e]
        for k in range(12):
            into.months[e][k] += sc.months[e][k]
    into.worst = max(into.worst, sc.worst)
    into.misses += sc.misses


def line(sc: common.Score) -> str:
    ex, w1 = sc.shares(), sc.within1_shares()
    return (f"Fajr {100 * ex['FAJR']:5.1f}/{100 * w1['FAJR']:5.1f} ({sc.total['FAJR']}) "
            f"Maghrib {100 * ex['MAGHRIB']:5.1f}/{100 * w1['MAGHRIB']:5.1f} ({sc.total['MAGHRIB']}) max {sc.worst}")


def bras_fusil() -> str:
    """Fajr and Isha of the Bras-Fusil display against re_cir at Saint-Benoit's own point (no zone)."""
    m, sc = method(), common.Score()
    with open(FR / BRAS_FUSIL, encoding="utf-8") as fh:
        for row in csv.DictReader(fh):
            day = datetime.date.fromisoformat(row["date"])
            out = common.compute(common.case(m, *TOWNS["Saint-Benoit"], TZ, day, country="RE"))
            for e, col in (("FAJR", "fajr"), ("ISHA", "isha")):
                sc.add(e, day, out[e][1] // 60, published(day, minutes(row[col])) // 60)
    ex, w1 = sc.shares(), sc.within1_shares()
    return (f"Fajr {sc.exact['FAJR']}/{sc.total['FAJR']} exact, {sc.within1['FAJR']} within 1; "
            f"Isha {sc.exact['ISHA']}/{sc.total['ISHA']} exact, {sc.within1['ISHA']} within 1; max {sc.worst}")


def main() -> int:
    table, printed = load()
    mds = set(table)
    print(f"re_cir: {len(table)} month-days, {len(printed)} printed cells (2023-2026)")
    halves = {"Mar+May": {md for md in mds if md[0] % 2 == 1}, "Feb+Apr": {md for md in mds if md[0] % 2 == 0}}
    fits = {}

    def describe(p):
        return (f"years iftar {p['iftar_year']} Fajr {p['fajr_year']}; evening {p['evening'][0]} {p['evening'][1]:+.2f}; "
                + "; ".join(f"{z} {p['mornings'][z][0]} {p['mornings'][z][1]:+.2f}" for z in ("nord", "est", "sud", "ouest"))
                + f"; iftar minutes {p['shifts']}")

    for name, part in halves.items():
        fits[name] = fit(table, part)
        print(f"fit on {name}: " + describe(fits[name]))
    final = fit(table, mds)
    print("fit on all: " + describe(final))
    print("  windows covered: " + " ".join(f"{c} {n}" for c, n in final["counts"].items()))

    held = {z: common.Score() for z in ZONES}
    for name, other in (("Mar+May", "Feb+Apr"), ("Feb+Apr", "Mar+May")):
        part = score_rows(zone_dicts(fits[other]), printed, halves[name])
        for z in ZONES:
            merge(held[z], part[z])
    profile = common.Score()
    print("held out (month-split), each printed row at its own date, exact/within 1 (%):")
    for z in ZONES:
        print(f"  {z:10s} {line(held[z])}")
        merge(profile, held[z])
    print(f"  {'all zones':10s} {line(profile)}")
    insample = score_rows(zone_dicts(final), printed, None)
    print("final zones on every printed row (in-sample), exact/within 1 (%):")
    for z in ZONES:
        print(f"  {z:10s} {line(insample[z])}")
    print("final zones by printed year (all zones):")
    for year in sorted({d.year for d, _, _ in printed}):
        by_year, agg = score_rows(zone_dicts(final), [r for r in printed if r[0].year == year], None), common.Score()
        for z in ZONES:
            merge(agg, by_year[z])
        print(f"  {year}: {line(agg)}")
    print("Bras-Fusil display (2026-10-12..17) at Saint-Benoit, exact coordinates: " + bras_fusil())

    first, last = min(d for d, _, _ in printed), max(d for d, _, _ in printed)
    fitted_on = [common.sample("CIR tables by month and day (Ramadan 1444-1446, Chawwal 1445, Baytoul Hayaate 1447)",
                               first, last, len({d for d, _, _ in printed}), len(ZONES))]
    zones = zone_dicts(final)
    asset_zones = []
    for z, (name_ar, town, named, fajr_col, iftar_col) in ZONES.items():
        days = sorted({d for d, col, _ in printed if col in (fajr_col, iftar_col)})
        held_sample = [common.sample(f"CIR rows of the {fajr_col} and {iftar_col} columns at their own dates, month-split",
                                     days[0], days[-1], len(days), 1, month_split=True)]
        zone = {k: v for k, v in zones[z].items() if k != "authority"}
        zone.update({"nameAr": name_ar,
                     "namedPlaces": [{"centre": common.point(*TOWNS[t]), "catalogueIds": [], "areaKm": 0.0} for t in named],
                     "fit": common.fit_stats(fitted_on, held_sample, held[z])})
        asset_zones.append(zone)
    held_all = [common.sample("CIR rows at their own dates, month-split, five zones", first, last,
                              len({d for d, _, _ in printed}), len(ZONES), month_split=True)]
    path = common.write_asset(AUTHORITY, AUTHORITY, "RE", "NAMED_REGIONS", asset_zones,
                              fit=common.fit_stats(fitted_on, held_all, profile), source=SOURCE_LINE)
    print(f"wrote {path.relative_to(common.ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
