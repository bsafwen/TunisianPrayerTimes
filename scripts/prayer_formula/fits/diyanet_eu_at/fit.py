#!/usr/bin/env python3
"""
Checks `diyanet_eu`, Austria's default (CountryMethodDefaults, plan 6.4, 7.2, 7.5), against IGGÖ's nine 2026 city
tables and Diyanet's own Austrian tables, fits each table's computing point (9.2, 9.4) and writes both tiers of
Austria's zones (6.5), android-app/app/src/main/assets/official-zones/diyanet_eu_at.json (schema 1, mapping NEAREST):
- tier 0: IGGÖ's nine state capitals, each with its city centre as `point` (from which the 25 km are measured,
  `reachKm` 25, `mappingTier` 0) and its fitted point as its E6 morning and evening points;
- tier 1: Diyanet's 191 Austrian entries (namazvakitleri.diyanet.gov.tr, 2026-10-16), each at its fitted point, with
  no reach limit (`mappingTier` 1): a place more than 25 km from every capital takes the nearest of them, as IGGÖ's
  site does («Nächstgelegenen Ort verwenden», /api/prayer-times/at/).

Data, read at run time from test-data-local and never copied (D13, A7):
- research-2026-10-11/AT/iggo2026.csv: IGGÖ's nine city tables (derislam.at), 1 April to 31 December 2026 (275 days
  each; the site shows no earlier 2026 month), Austrian clock time.
- research-2026-10-11/AT/iggo_locate_api_2026-10-11.txt: IGGÖ's "nearest place" API on 2026-10-11 (notes only).
- official/at/lists/country_35.json and official/at/pages/: Diyanet's Austrian entries and each one's page (collected
  by fits/diyanet_tr/collect.py): 31 days from 2026-10-16 and all of 2027, Austrian clock time.
- research-2026-10-11/GEONAMES/cities500.zip (GeoNames, CC BY 4.0) for the entries' towns, and the place catalogue
  (assets/places/europe-cities.tsv) for their Arabic names.

The method is diyanet_eu as EuropeanPresets.DIYANET_EU stands (18 / 16, sunrise - 7, Dhuhr + 5, Asr + 4, Maghrib + 7,
the sun once a day at 0h UT, NEAREST, Diyanet's takdir from 44.5 N, 5 h minimum day and night): nothing of it is
refitted here. Its takdir constants (DiyanetTakdir.kt) were re-derived from IGGÖ's tables (fits/diyanet_takdir.py),
so Fajr and Isha on the takdir days (May to August) are in-sample there and scored apart.

Tier 0, per city: the longitude from Dhuhr (windows, within 0.25 deg of the centre), then the latitude from sunrise,
Asr, Maghrib, and Fajr and Isha outside May-August (exact minutes on a 0.005 then 0.0005 deg grid within 0.15 deg; the
latitude most minutes allow, nearest the centre). Held out: month-split (points fitted on the odd months score the
even ones, and the reverse), with common.compute. A diagnostic prints the shape of IGGÖ's summer Fajr and Isha (the
takdir): the season's edges, the straight lines to and from 21 June, and the solstice depression's share of the
midnight one.

Tier 1, per entry (fits/diyanet_tr/points.py, as for Turkey): the point on a 1/400 degree grid from all of 2027, Fajr
and Isha from 15 April to 31 August left out; held out a month-split of 2027 (refits from the year's point) and the 31
days of 2026, with common.compute, Fajr and Isha in May-August or estimated by the takdir scored apart (the takdir
check on Diyanet's own tables). Each entry is matched by name to its town (GeoNames) near its fitted point.

Usage: python scripts/prayer_formula/fits/diyanet_eu_at/fit.py [--tier0-only]
"""

from __future__ import annotations

import argparse
import csv
import datetime
import math
import statistics
import sys
import time
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(1, str(Path(__file__).resolve().parents[1] / "diyanet_tr"))
import common  # noqa: E402
import diyanet_site as site  # noqa: E402  (fits/diyanet_tr: Diyanet's site and its saved pages)
import points  # noqa: E402  (fits/diyanet_tr: the shared point fit)

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
SOURCE_LINE = ("IGGÖ (Islamische Glaubensgemeinschaft in Österreich): its nine state capitals' 2026 tables (tier 0, within "
               "25 km), then Diyanet's 191 Austrian entries (namazvakitleri.diyanet.gov.tr, tier 1, the nearest), "
               "Diyanet's method, each table at its fitted point")
# Tier 1: Diyanet's own Austrian entries.
TIER1_ISHA = 16.0
TIER1_GRID = 400                       # cells per degree (0.0025 deg), as for Turkey (fits/diyanet_tr/README-part.md)
TIER1_LAT_RANGE = (46.2, 49.2)
TIER1_MATCH_KM = 30.0
TIER1_YEAR = "diyanet_at_2027"         # FitSample sources: the yearly table, read 2026-10-16
TIER1_MONTH = "diyanet_at_2026_31d"    # the 31 days from 2026-10-16 on the same pages
COUNTRY_AR = "النمسا"
# Diyanet's Austrian names GeoNames spells otherwise (folded keys, points.fold).
TIER1_ALIASES = {"viyana": "wien", "hainburgadd": "hainburganderdonau", "svalentin": "sanktvalentin",
                 "stjohaninpongau": "sanktjohannimpongau", "stjohanintirol": "sanktjohannintirol",
                 "stveitderglan": "sanktveitanderglan", "stwolfgang": "sanktwolfgangimsalzkammergut",
                 "neumarktamwallarsee": "neumarktamwallersee", "mistelbachanderzaya": "mistelbach",
                 "michelfdorfamkienberge": "micheldorfinoberosterreich", "micheldorfamkienberge": "micheldorfinoberosterreich",
                 "spittalanderdrau": "spittalanderdrau", "ehrwaldoberdorf": "ehrwald", "ternitz": "ternitz",
                 "sanktpolten": "sanktpolten", "deutschlandsberg": "deutschlandsberg"}


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


def takdir_shape(city: str, rows, point, solstice: datetime.date = SOLSTICE) -> list[str]:
    """IGGÖ's (or Diyanet's) summer Fajr and Isha: the days off the plain 18 / 16 deg time by more than 1 min, the
    straight lines (UT clock time) from the last plain day to 21 June and from 21 June to the next plain day, the largest
    distance of a printed minute from those lines, and at 21 June the printed time's depression as a share of the
    midnight one."""
    SOLSTICE = solstice  # noqa: N806 - the day of the year's table
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


# ---- tier 1: Diyanet's own Austrian entries ----------------------------------------------------------------------

def tier1_mask(months=None):
    """The fit's minutes: 2027, Fajr and Isha from 15 April to 31 August left out (the takdir season, widest in the north)."""
    def mask(day):
        if day.date.year != 2027 or (months is not None and day.date.month not in months):
            return (False,) * 6
        summer = (4, 15) <= (day.date.month, day.date.day) <= (8, 31)
        return (not summer, True, True, True, True, not summer)
    return mask


def tier1_apart(day, i, status) -> bool:
    """Fajr and Isha in May-August or estimated by the takdir: scored apart (the takdir check), as tier 0 does."""
    return i in (0, 5) and (day.date.month in TAKDIR_MONTHS or status == "ESTIMATED")


AUSTRIA_LNG = (9.3, 17.3)              # a fitted longitude outside this is a table one hour off (Diyanet's summer-time slip)


def summer_time_2027(day) -> bool:
    return day.date.year == 2027 and day.tz == 120


def tier1_town(entry: dict, fitted: tuple[float, float], geo: list[dict]) -> dict | None:
    """The entry's town: a GeoNames row of its name within 30 km of its fitted point, Austria first, its own name before
    an alternate one, then the nearest («SANKT MARTIN (O)»: Sankt Martin by Traun, 1.8 km, not Sankt Martin im Mühlkreis)."""
    key = points.fold(entry["name"].split("(")[0])
    keys = {key, TIER1_ALIASES.get(key, key)}
    near = [r for r in geo if r["keys"] & keys and points.km(fitted, (r["lat"], r["lng"])) <= TIER1_MATCH_KM]
    if not near:
        return None
    return min(near, key=lambda r: (r["cc"] != "AT", not (r["primary"] & keys), points.km(fitted, (r["lat"], r["lng"]))))


def tier1(started: float) -> tuple[list[dict], common.Score, list[dict]]:
    """Diyanet's Austrian entries: (zones, the held-out score, per-entry results)."""
    local = common.LOCAL_DATA
    entries = site.load_entries(local, "AT")
    missing = [e["name"] for e in entries if not site.page_files(local, "AT", e["id"])]
    if missing:
        raise SystemExit(f"{len(missing)} Austrian entries have no saved page (run fits/diyanet_tr/collect.py --only AT): {missing}")
    method = points.method_json(METHOD_ID, TIER1_ISHA)
    geo = points.read_geonames({"AT", "DE"})
    catalogue = {r["id"]: r for r in points.read_catalogue({"AT"})}
    log(f"tier 1: Diyanet's {len(entries)} Austrian entries; GeoNames {len(geo)} AT and DE rows, catalogue {len(catalogue)} AT rows")
    odd, even = {1, 3, 5, 7, 9, 11}, {2, 4, 6, 8, 10, 12}
    zones, results = [], []
    total_held, total_apart = common.Score(), common.Score()
    fast_bad = 0
    quirks = []
    for n, e in enumerate(entries):
        rows, conflicts = site.load_rows(local, "AT", e["id"])
        all_days = points.days_of(rows, TZ)
        f = points.fit_point(all_days, TIER1_ISHA, tier1_mask(), None, TIER1_LAT_RANGE, TIER1_GRID)
        # A table printed without summer time in 2027 (one hour early from 28 March to 30 October) pulls the fitted
        # longitude 15 degrees east: its summer-time days are then quirk days (8.3), left out of the fit and the scores.
        quirk_days = 0
        days = all_days
        if not AUSTRIA_LNG[0] <= f["lng"] <= AUSTRIA_LNG[1]:
            days = [d for d in all_days if not summer_time_2027(d)]
            quirk_days = len(all_days) - len(days)
            f = points.fit_point(days, TIER1_ISHA, tier1_mask(), None, TIER1_LAT_RANGE, TIER1_GRID)
            late = [d.pub[2] - points.rounded(points.unrounded(d, f["lat"], f["lng"], TIER1_ISHA)[2]) for d in all_days if summer_time_2027(d)]
            quirks.append(f"{e['name']}: {quirk_days} summer-time days of 2027 printed {statistics.median(late):+.0f} min from the "
                          f"summer clock (Dhuhr, median); point refitted on the other days")
        year_days = [d for d in days if d.date.year == 2027]
        month_days = [d for d in days if d.date.year == 2026]
        seed = (f["lat"], f["lng"])
        f_odd = points.refine_point(days, TIER1_ISHA, tier1_mask(odd), seed, TIER1_GRID)
        f_even = points.refine_point(days, TIER1_ISHA, tier1_mask(even), seed, TIER1_GRID)
        held, apart = common.Score(), common.Score()
        for part, p in ((odd, f_even), (even, f_odd)):
            z = common.zone_json(e["id"], p["lat"], p["lng"], authority=AUTHORITY)
            points.score_compute(method, z, [d for d in year_days if d.date.month in part], TZ, "AT", held, tier1_apart, apart)
        z = common.zone_json(e["id"], f["lat"], f["lng"], authority=AUTHORITY)
        estimated = points.score_compute(method, z, month_days, TZ, "AT", held, tier1_apart, apart)
        if n % 40 == 0:
            fast_bad += points.check_fast(method, z, days, TZ, "AT", f["lat"], f["lng"], TIER1_ISHA)
        town = tier1_town(e, seed, geo)
        cat = catalogue.get(town["id"]) if town else None
        if cat and cat["arabic"]:
            name_ar = cat["arabic"]
        else:
            name_ar = points.d3_label(town["name"] if town else points.title_de(e["name"]), COUNTRY_AR)
        stats = common.fit_stats(
            [common.sample(TIER1_YEAR, datetime.date(2027, 1, 1), datetime.date(2027, 12, 31), len(year_days), 1)],
            [common.sample(TIER1_YEAR + ", held out by month; Fajr and Isha outside May-August", datetime.date(2027, 1, 1),
                           datetime.date(2027, 12, 31), len(year_days), 1, month_split=True),
             common.sample(TIER1_MONTH, min(d.date for d in month_days), max(d.date for d in month_days), len(month_days), 1)],
            held, quirk_days=quirk_days)
        # The town is the zone's point, from which the nearest entry is measured; the fitted point is where the table is
        # computed (E6), as for the capitals. An entry with no town in GeoNames keeps its fitted point. A town within a
        # capital's 25 km names no place (tier 0 serves it; its catalogue row stays the capital's named place).
        if town:
            zone_json = {"id": f"diyanet-{e['id']}", "nameAr": name_ar, "point": common.point(town["lat"], town["lng"]),
                         "morningPoint": common.point(*seed), "eveningPoint": common.point(*seed), "mappingTier": 1}
            if min(points.km((town["lat"], town["lng"]), c) for c, _ in CITIES.values()) > REACH_KM:
                zone_json["namedPlaces"] = [{"centre": common.point(town["lat"], town["lng"]), "catalogueIds": [], "areaKm": 0.0}]
        else:
            zone_json = {"id": f"diyanet-{e['id']}", "nameAr": name_ar, "point": common.point(*seed), "mappingTier": 1}
        zone_json["fit"] = stats
        zones.append(zone_json)
        results.append({"entry": e, "fit": f, "odd": f_odd, "even": f_even, "held": held, "apart": apart, "town": town,
                        "conflicts": conflicts, "estimated": estimated, "rows": rows, "quirk": quirk_days > 0})
        points.merge(total_held, held)
        points.merge(total_apart, apart)
    log(f"tier 1 fitted in {time.time() - started:.0f} s; closed form against common.compute at every 40th entry: {fast_bad} times differ")
    log(f"tables with a summer-time slip (quirk days): {quirks}")
    log("tier 1, held out (month-split of 2027 and the 31 days of 2026), outside the takdir: " + shares(total_held))
    log("tier 1, Fajr and Isha in May-August or estimated (Diyanet's own summer against the engine's takdir): "
        + shares(total_apart, ("FAJR", "ISHA")))
    per = {x: [r["held"].shares()[x] for r in results] for x in EVENTS}
    log("  per entry, min / median exact per time: " + " ".join(f"{x[:4]} {100 * min(v):.1f}/{100 * statistics.median(v):.1f}"
                                                            for x, v in per.items()))
    log(f"  entries with a held-out time 2 min or more off: {sum(1 for r in results if r['held'].worst > 1)}")
    passing = sum(1 for z in zones if all(z["fit"]["exact"][x] >= 0.97 and z["fit"]["within1"][x] >= 1.0 for x in EVENTS))
    log(f"  zones at 97 % exact and 100 % within 1 min on every time (Fajr and Isha outside May-August): {passing} of {len(zones)}")
    lats = sorted(r["fit"]["lat"] for r in results)
    log(f"  fitted latitudes {lats[0]:.3f} to {lats[-1]:.3f} N")
    bands = defaultdict(common.Score)
    for r in results:
        points.merge(bands[round(r["fit"]["lat"] * 2) / 2], r["apart"])
    for b in sorted(bands):
        log(f"  takdir days, latitude {b - 0.25:.2f}-{b + 0.25:.2f} N: {shares(bands[b], ('FAJR', 'ISHA'))}")
    matched = [r for r in results if r["town"]]
    dist = sorted((points.km((r["fit"]["lat"], r["fit"]["lng"]), (r["town"]["lat"], r["town"]["lng"])), r["entry"]["name"], r["town"]["name"])
                  for r in matched)
    log(f"  towns matched: {len(matched)} of {len(results)} ({Counter(r['town']['cc'] for r in matched)}); fitted point to the "
        f"town: median {statistics.median(d for d, _, _ in dist):.1f} km, max {dist[-1][0]:.1f} km; farthest "
        + "; ".join(f"{a} -> {b} {d:.1f} km" for d, a, b in dist[-6:]))
    log("  unmatched: " + ", ".join(f"{r['entry']['name']} ({r['fit']['lat']:.3f} {r['fit']['lng']:.3f})" for r in results if not r["town"]))
    log("  outside Austria: " + ", ".join(f"{r['entry']['name']} -> {r['town']['name']} {r['town']['cc']}" for r in matched if r["town"]["cc"] != "AT"))
    named = Counter(z["nameAr"] for z in zones)
    log(f"  Arabic names from the catalogue: {sum(1 for z in zones if not z['nameAr'].startswith(chr(0x2066)))}; D3 labels: "
        f"{sum(1 for z in zones if z['nameAr'].startswith(chr(0x2066)))}; names used twice: {[k for k, v in named.items() if v > 1]}")
    return zones, total_held, results


def tier1_checks(results: list[dict], tables: dict, tier0_points: dict) -> None:
    """IGGÖ's site against tier 1: the Diyanet entry each IGGÖ table equals on their common days (2026-10-16 to 11-15),
    the summer shape of that entry's 2027 table, and which entry IGGÖ's locate API named on 2026-10-11 against the
    nearest entry by fitted point and by town."""
    for city in sorted(tables):
        def same(r):
            return sum(1 for d in r["rows"] if d in tables[city] for j in range(6) if r["rows"][d][j] == tables[city][d][j])
        r = max(results, key=same)
        n = sum(1 for d in r["rows"] if d in tables[city])
        log(f"  {city}: IGGÖ's table equals Diyanet's {r['entry']['name']} on {same(r)} of {6 * n} times ({n} common days); "
            f"Diyanet's point {r['fit']['lat']:.4f} {r['fit']['lng']:.4f}, IGGÖ's fitted {tier0_points[city][0]:.4f} "
            f"{tier0_points[city][1]:.4f} ({points.km((r['fit']['lat'], r['fit']['lng']), tier0_points[city]):.1f} km)")
        year = {d: t for d, t in r["rows"].items() if d.year == 2027}
        for line in takdir_shape(city, year, (r["fit"]["lat"], r["fit"]["lng"]), datetime.date(2027, 6, 21)):
            log(f"    Diyanet's takdir, 2027: {line}")
    # IGGÖ's locate API on 2026-10-11 (research notes, iggo_locate_api_2026-10-11.txt): the query point and the place it
    # named. The notes give the coordinates of the last four queries and of Lienz; for the others the town centre is assumed.
    api = {"Wels": (48.157, 14.026, "WELS"), "Eferding": (48.309, 14.022, "EFERDING"), "Pochlarn": (48.212, 15.208, "POCHLARN"),
           "Tulln": (48.329, 16.055, "TULLN"), "Zell Am See": (47.324, 12.797, "ZELL AM SEE"), "Lienz": (46.83, 12.77, "HEILIGENBLUT"),
           "Melk": (48.227, 15.332, "POCHLARN"), "48.31 13.92": (48.31, 13.92, "EFERDING"), "48.16 14.03": (48.16, 14.03, "WELS"),
           "46.84 15.44": (46.84, 15.44, "LEIBNITZ")}
    for label, (lat, lng, answer) in api.items():
        by_fit = min(results, key=lambda r: points.km((lat, lng), (r["fit"]["lat"], r["fit"]["lng"])))
        towns = [r for r in results if r["town"]]
        by_town = min(towns, key=lambda r: points.km((lat, lng), (r["town"]["lat"], r["town"]["lng"])))
        log(f"  IGGÖ's API at {label} ({lat}, {lng}) named {answer}; nearest by fitted point {by_fit['entry']['name']}, by town "
            f"{by_town['entry']['name']}")


def takdir_v(rows: dict, point: tuple[float, float], solstice: datetime.date) -> dict:
    """The numbers of takdir_shape for one year's table: per time, the last plain day before the season and the first
    after (day of the year), the 21 June depression as a share of the midnight one, the plain time's distance from
    sunrise (Fajr) or sunset (Isha) on the last plain day, and the largest distance of a printed minute from the V."""
    lat, lng = point
    out = {}
    for j, e in ((0, "FAJR"), (5, "ISHA")):
        def ut(d):
            return (published(d, rows[d][j]) - (datetime.datetime(d.year, d.month, d.day) - datetime.datetime(1970, 1, 1)).total_seconds()) / 60
        off = [d for d in sorted(rows) if raw(d, lat, lng, e) is None or abs(published(d, rows[d][j]) - raw(d, lat, lng, e)) > 60]
        start, end = off[0] - datetime.timedelta(days=1), off[-1] + datetime.timedelta(days=1)
        sol = ut(solstice)
        worst = max(abs(ut(d) - (ut(start) + (sol - ut(start)) * (d - start).days / (solstice - start).days if d <= solstice
                                 else sol + (ut(end) - sol) * (d - solstice).days / (end - solstice).days)) for d in off)
        cyc, sun = sun_of(solstice)
        t0 = ref.transit(cyc, sun, lng)
        midnight = -ref.altitude(cyc, sun, lat, lng, t0 + 12)
        dep = -ref.altitude(cyc, sun, lat, lng, cyc.hours(published(solstice, rows[solstice][j]) + 30))
        edge = raw(start, lat, lng, "SUNRISE" if e == "FAJR" else "MAGHRIB")
        out[e] = {"start": start.timetuple().tm_yday, "end": end.timetuple().tm_yday, "ratio": dep / midnight,
                  "gap": abs(edge - raw(start, lat, lng, e)) / 60, "worst": worst}
    return out


def tier1_takdir(results: list[dict]) -> None:
    """Diyanet's summer Fajr and Isha over its Austrian 2027 tables (each at its fitted point), by half a degree of
    latitude, and the line in latitude of each number: the shape a V-shaped takdir in the engine would need."""
    recs = []
    for r in results:
        if r["quirk"]:                          # a table printed without summer time is left out
            continue
        year = {d: t for d, t in r["rows"].items() if d.year == 2027}
        recs.append((r["fit"]["lat"], takdir_v(year, (r["fit"]["lat"], r["fit"]["lng"]), datetime.date(2027, 6, 21))))
    log(f"Diyanet's takdir in its 2027 Austrian tables ({len(recs)} entries; days of the year, 2027):")
    for e in ("FAJR", "ISHA"):
        for lo in (46.5, 47.0, 47.5, 48.0, 48.5):
            band = [v[e] for lat, v in recs if lo <= lat < lo + 0.5]
            if not band:
                continue
            med = {k: statistics.median(x[k] for x in band) for k in band[0]}
            log(f"  {e[:4]} {lo:.1f}-{lo + 0.5:.1f} N ({len(band)}): last plain day {med['start']:.0f} "
                f"({min(x['start'] for x in band)}-{max(x['start'] for x in band)}), first plain day after {med['end']:.0f}, "
                f"21 June at {med['ratio']:.3f} of the midnight depression ({min(x['ratio'] for x in band):.3f}-"
                f"{max(x['ratio'] for x in band):.3f}), plain {'Fajr to sunrise' if e == 'FAJR' else 'sunset to Isha'} "
                f"{med['gap']:.1f} min on the last plain day, at most {max(x['worst'] for x in band):.1f} min off the V")
        xs = [lat for lat, _ in recs]
        mx = sum(xs) / len(xs)
        for k in ("ratio", "start", "end", "gap"):
            ys = [v[e][k] for _, v in recs]
            my = sum(ys) / len(ys)
            b1 = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sum((x - mx) ** 2 for x in xs)
            b0 = my - b1 * mx
            log(f"    {e[:4]} {k} = {b0:.4f} {b1:+.5f} * latitude (largest residual {max(abs(y - b0 - b1 * x) for x, y in zip(xs, ys)):.3f})")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--tier0-only", action="store_true", help="IGGÖ's nine cities only (the asset as before tier 1)")
    args = parser.parse_args(argv)
    sys.stdout.reconfigure(encoding="utf-8")
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
    fitted_on = [common.sample(SOURCE, first, last, days, len(tables))]
    held_out = [common.sample(SOURCE + ", held out by month; Fajr and Isha outside May-August", first, last, days, len(tables),
                              month_split=True)]
    source = SOURCE_LINE
    if not args.tier0_only:
        tier0_points = {z["id"]: (z["eveningPoint"]["latE5"] / 1e5, z["eveningPoint"]["lngE5"] / 1e5) for z in zones}
        zones1, held1, results = tier1(started)
        tier1_checks(results, tables, tier0_points)
        tier1_takdir(results)
        zones += zones1
        merge(total_held, held1)
        year_days = sum(z["fit"]["heldOut"][0]["days"] for z in zones1)
        month_days = sum(z["fit"]["heldOut"][1]["days"] for z in zones1)
        fitted_on.append(common.sample(TIER1_YEAR, datetime.date(2027, 1, 1), datetime.date(2027, 12, 31), year_days, len(zones1)))
        held_out += [common.sample(TIER1_YEAR + ", held out by month; Fajr and Isha outside May-August", datetime.date(2027, 1, 1),
                                   datetime.date(2027, 12, 31), year_days, len(zones1), month_split=True),
                     common.sample(TIER1_MONTH, datetime.date(2026, 10, 16), datetime.date(2026, 11, 15), month_days, len(zones1))]
        log("both tiers, held out, outside the takdir: " + shares(total_held))
    else:
        source = ("IGGÖ (Islamische Glaubensgemeinschaft in Österreich): its nine state capitals' 2026 tables, Diyanet's "
                  "method; Diyanet's own Austrian entries (the second tier) not included")
    profile = common.fit_stats(fitted_on, held_out, total_held)
    path = common.write_asset(AUTHORITY, METHOD_ID, "AT", "NEAREST", zones, fit=profile, source=source)
    log(f"wrote {path.relative_to(common.ROOT)} ({len(zones)} zones, {path.stat().st_size} bytes) in {time.time() - started:.0f} s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
