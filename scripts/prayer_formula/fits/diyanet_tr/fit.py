#!/usr/bin/env python3
"""
Fits the point of each of Diyanet's Turkish entries for `diyanet_tr` (plan 6.5 `DISTRICT`, 7.5, M3, D13) and writes
its official-zone asset, android-app/app/src/main/assets/official-zones/diyanet_tr.json (schema 1, mapping DISTRICT):
one zone per entry, computed at the entry's fitted point, with the GeoNames districts (admin2 codes) it serves.

Data, read at run time from test-data-local and never copied (A7):
- official/tr/lists/ and official/tr/pages/: Diyanet's 81 provinces, their 869 entries and each entry's page (collect.py,
  namazvakitleri.diyanet.gov.tr, read 2026-10-16): 31 days from 2026-10-16 and all of 2027 (the site serves the next
  calendar year, not the current one's past months).
- research-2026-10-11/GEONAMES/cities500.zip and admin1CodesASCII.txt (GeoNames, CC BY 4.0): the districts' seats, their
  admin2 codes and provinces. The place catalogue (assets/places/europe-cities.tsv) gives the Arabic names.

The method is `diyanet_tr` as EuropeanPresets.DIYANET_TR stands (18 / 17, sunrise - 7, Dhuhr + 5, Asr + 4, Maghrib + 7,
the sun once a day at 0h UT, NEAREST, the takdir from 44.5 N, Diyanet's clamp): only each entry's point is fitted
(points.py), on 2027, with Fajr and Isha from 15 April to 31 August left out (the widest takdir season at Austrian
latitudes; the takdir constants were fitted elsewhere, and in Turkey, all south of 44.5 N, the engine applies none).
The grid is 1/400 degree: whole arc-minutes lose about 0.4 point of held-out exactness (README-part.md).

Held out (FitStats): a month-split of 2027 (the point fitted on the odd months scores the even ones, and the reverse)
and the 31 days of 2026 (the point fitted on all of 2027), all six times on every day, with common.compute. Fajr and
Isha from 15 April to 31 August, never in a fit, are also reported apart: the takdir check at Turkey's latitudes.

Mapping (6.5 DISTRICT): each entry is matched by name to its district's seat in its province (GeoNames) and checked
against its fitted point; it serves that seat's admin2 code, and the province's central entry (the one named after the
province) every admin2 code of the province no other entry serves. An entry with no seat code takes the unclaimed code
of the GeoNames row within 4 km of its fitted point. A code GeoNames gives places of several provinces or two distant
seats, or two entries claim, is listed under none: such places take the nearest entry, as the mapping does without a
code. The zone's point is the seat (from which the nearest entry is measured), the fitted point its E6 morning and
evening point. Catalogue ids of the named places are filled afterwards (scripts/places/match_named_places.py).

Usage: python scripts/prayer_formula/fits/diyanet_tr/fit.py [--limit N] [--no-write]    (6 to 10 minutes)
"""

from __future__ import annotations

import argparse
import datetime
import math
import statistics
import sys
import time
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import diyanet_site as site  # noqa: E402
import points  # noqa: E402

common = points.common
EVENTS = common.EVENTS
AUTHORITY = "diyanet_tr"
METHOD_ID = "diyanet_tr"
CC = "TR"
TZ = "Europe/Istanbul"
ISHA = 17.0
GRID = 400                              # cells per degree: 0.0025 deg
LAT_RANGE = (35.5, 42.5)
COUNTRY_AR = "تركيا"
MATCH_KM = 30.0                         # a row of the entry's name within this of its fitted point is its seat
SPLIT_KM = 30.0                         # an admin2 code whose seats lie farther apart is two districts in GeoNames
STRAY_KM = 60.0                         # a catalogue row this far from its code's seat is reported (a GeoNames slip)
SOURCE_YEAR = "diyanet_tr_2027"         # FitSample sources (README-part.md): the yearly table, read 2026-10-16
SOURCE_MONTH = "diyanet_tr_2026_31d"    # the 31 days from 2026-10-16 on the same pages
SOURCE_LINE = ("Diyanet İşleri Başkanlığı (namazvakitleri.diyanet.gov.tr): 869 Turkish entries, each computed at a point "
               "fitted from its 2027 table (E6), mapped by district (GeoNames admin2 code) from the seat's point")
# Diyanet's names that GeoNames spells otherwise (folded keys, points.fold): Marmara Ereğlisi, Karadeniz Ereğli,
# Ondokuzmayıs; Kahramankazan (Kazan until 2016) and Aziziye, whose seat is Ilıca.
ALIASES = {"mereglisi": "marmaraereglisi", "karadenizeregli": "eregli", "19mayis": "ondokuzmayis",
           "kahramankazan": "kazan", "aziziye": "ilica"}
INFER_KM = 4.0                          # an entry with no seat code takes the unclaimed code of a GeoNames row this near
# The native name of an entry GeoNames does not list, where Diyanet's upper case loses a letter (the D3 label).
NATIVE = {"DEMIRKOY": "Demirköy"}


def log(text: str = "") -> None:
    print(text, flush=True)


def takdir_window(day: datetime.date) -> bool:
    return (4, 15) <= (day.month, day.day) <= (8, 31)


def fit_mask(year: int, months=None):
    def mask(day):
        if day.date.year != year or (months is not None and day.date.month not in months):
            return (False,) * 6
        summer = takdir_window(day.date)
        return (not summer, True, True, True, True, not summer)
    return mask


def base_name(name: str) -> str:
    """Diyanet's name without its province hint: «KEMER (B)» -> «KEMER», «ALTINYAYLA(S)» -> «ALTINYAYLA»."""
    return name.split("(")[0].strip()


def province_codes(admin1: dict[str, str]) -> dict[str, str]:
    return {points.fold(n.replace(" Province", "")): code for code, n in admin1.items()}


def match_entry(entry: dict, fitted: tuple[float, float], code: str | None, geo_by_province, geo_all) -> tuple[dict | None, str]:
    """The GeoNames row of the entry's district seat and how it was found: the province's capital for its central
    entry; else a row of its name within 30 km of the fitted point (its own name before an alternate one, a seat before
    a village, then the nearest); else the province's only seat of that name, however far (Diyanet computes a few
    entries away from their town: ARSUZ at Antakya, as HATAY)."""
    key = points.fold(base_name(entry["name"]))
    keys = {key, ALIASES.get(key, key)}
    rows = geo_by_province.get(code, [])
    if key == points.fold(entry["stateName"]):
        seats = [r for r in rows if r["fcode"] in ("PPLA", "PPLC")]
        if seats:
            return min(seats, key=lambda r: points.km(fitted, (r["lat"], r["lng"]))), "central"

    def rank(r):
        return (not (r["primary"] & keys), points.fcode_rank(r["fcode"]) > 1, points.km(fitted, (r["lat"], r["lng"])))

    named = [r for r in rows if r["keys"] & keys]
    near = [r for r in named if points.km(fitted, (r["lat"], r["lng"])) <= MATCH_KM]
    if near:
        return min(near, key=rank), "name"
    others = [r for r in geo_all if r["keys"] & keys and points.km(fitted, (r["lat"], r["lng"])) <= MATCH_KM]
    if others:
        return min(others, key=rank), "name, another province"
    seats = [r for r in named if r["primary"] & keys and points.fcode_rank(r["fcode"]) <= 1]
    if len(seats) == 1:
        return seats[0], f"name, {points.km(fitted, (seats[0]['lat'], seats[0]['lng'])):.0f} km from the fitted point"
    if named:
        return None, f"name {points.km(fitted, (named[0]['lat'], named[0]['lng'])):.0f} km from the fitted point"
    return None, "no GeoNames row of that name"


def split_codes(geo: list[dict]) -> dict[str, list[str]]:
    """Admin2 codes whose seats (PPLA, PPLC, PPLA2) lie more than SPLIT_KM apart: GeoNames gives one code to two
    districts' seats (Kalkandere and Çamlıhemşin), so the code cannot tell them apart."""
    seats = defaultdict(list)
    for r in geo:
        if r["admin2"] and points.fcode_rank(r["fcode"]) <= 1:
            seats[r["admin2"]].append(r)
    out = {}
    for c, rs in seats.items():
        if any(points.km((a["lat"], a["lng"]), (b["lat"], b["lng"])) > SPLIT_KM for a in rs for b in rs):
            out[c] = sorted({r["name"] for r in rs})
    return out


def stats_for(held: common.Score, fitted_days: int, month_days: int, first: datetime.date, last: datetime.date) -> dict:
    return common.fit_stats(
        [common.sample(SOURCE_YEAR, datetime.date(2027, 1, 1), datetime.date(2027, 12, 31), fitted_days, 1)],
        [common.sample(SOURCE_YEAR + ", held out by month", datetime.date(2027, 1, 1), datetime.date(2027, 12, 31), fitted_days, 1,
                       month_split=True),
         common.sample(SOURCE_MONTH, first, last, month_days, 1)],
        held)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--limit", type=int, help="fit only the first N entries (a trial; nothing is written)")
    parser.add_argument("--no-write", action="store_true")
    args = parser.parse_args(argv)
    sys.stdout.reconfigure(encoding="utf-8")
    started = time.time()
    local = common.LOCAL_DATA
    entries = site.load_entries(local, CC)
    missing = [e for e in entries if not site.page_files(local, CC, e["id"])]
    if missing:
        log(f"{len(missing)} of {len(entries)} entries have no saved page: run collect.py first")
        if args.limit is None:
            return 1
    entries = [e for e in entries if site.page_files(local, CC, e["id"])][: args.limit]
    method = points.method_json(METHOD_ID, ISHA)
    geo = points.read_geonames({"TR"})
    admin1 = points.read_admin1_names("TR")
    pcodes = province_codes(admin1)
    geo_by_province = defaultdict(list)
    for r in geo:
        geo_by_province[r["admin1"]].append(r)
    catalogue = {r["id"]: r for r in points.read_catalogue({"TR"})}
    code_province, ambiguous = points.code_provinces(geo)
    log(f"diyanet_tr: {len(entries)} entries; GeoNames {len(geo)} TR rows, {len(code_province)} admin2 codes "
        f"({len(ambiguous)} given to places of several provinces); catalogue {len(catalogue)} TR rows ({time.time() - started:.0f} s)")

    odd, even = {1, 3, 5, 7, 9, 11}, {2, 4, 6, 8, 10, 12}
    results = []
    total_held, total_takdir, total_insample = common.Score(), common.Score(), common.Score()
    fast_bad = 0
    for n, e in enumerate(entries):
        rows, conflicts = site.load_rows(local, CC, e["id"])
        days = points.days_of(rows, TZ)
        year_days = [d for d in days if d.date.year == 2027]
        month_days = [d for d in days if d.date.year == 2026]
        f = points.fit_point(days, ISHA, fit_mask(2027), None, LAT_RANGE, GRID)
        seed = (f["lat"], f["lng"])
        # The month-split refits start from the whole year's point, as fits/om_mara's do.
        f_odd = points.refine_point(days, ISHA, fit_mask(2027, odd), seed, GRID)
        f_even = points.refine_point(days, ISHA, fit_mask(2027, even), seed, GRID)
        held, takdir = common.Score(), common.Score()

        def summer(day, i, status):
            return i in (0, 5) and takdir_window(day.date)

        for part, p in ((odd, f_even), (even, f_odd)):
            z = common.zone_json(e["id"], p["lat"], p["lng"], authority=AUTHORITY)
            sc, ta = common.Score(), common.Score()
            points.score_compute(method, z, [d for d in year_days if d.date.month in part], TZ, CC, sc, summer, ta)
            points.merge(held, sc)
            points.merge(held, ta)            # in Turkey the summer Fajr and Isha are plain times: held out, scored
            points.merge(takdir, ta)
        z = common.zone_json(e["id"], f["lat"], f["lng"], authority=AUTHORITY)
        points.score_compute(method, z, month_days, TZ, CC, held)
        ins = common.Score()                  # in-sample, for the report only: the closed form (check_fast below)
        for d in year_days:
            u = points.unrounded(d, f["lat"], f["lng"], ISHA)
            for i, x in enumerate(EVENTS):
                ins.add(x, d.date, points.rounded(u[i]), d.pub[i])
        if n % 50 == 0:
            fast_bad += points.check_fast(method, z, days, TZ, CC, f["lat"], f["lng"], ISHA)
        code = pcodes.get(points.fold(e["stateName"]))
        seat, how = match_entry(e, seed, code, geo_by_province, geo)
        results.append({"entry": e, "fit": f, "odd": f_odd, "even": f_even, "held": held, "takdir": takdir, "ins": ins,
                        "seat": seat, "how": how, "province": code, "conflicts": conflicts,
                        "stats": stats_for(held, len(year_days), len(month_days), min(d.date for d in month_days),
                                           max(d.date for d in month_days))})
        points.merge(total_held, held)
        points.merge(total_takdir, takdir)
        points.merge(total_insample, ins)
        if n % 100 == 0 or n == len(entries) - 1:
            log(f"  {n + 1}/{len(entries)} {e['stateName']} / {e['name']}: {f['lat']:.4f} {f['lng']:.4f} ({how}); held out "
                f"{points.shares(held)} ({time.time() - started:.0f} s)")

    # ---------------------------------------------------------------- report: the fit
    log()
    log(f"closed form against common.compute at every 50th entry: {fast_bad} times differ")
    log("held out, all entries (month-split of 2027 and the 31 days of 2026), exact / within 1 min:")
    log("  " + points.shares(total_held))
    log("in-sample (all of 2027, the point fitted on it): " + points.shares(total_insample))
    log("Fajr and Isha from 15 April to 31 August (never fitted; the takdir season elsewhere), held out:")
    log("  " + points.shares(total_takdir, ("FAJR", "ISHA")))
    worst = sorted(results, key=lambda r: min(r["held"].shares()[x] for x in EVENTS))[:8]
    log("lowest held-out entries: " + "; ".join(
        f"{r['entry']['stateName']}/{r['entry']['name']} {100 * min(r['held'].shares()[x] for x in EVENTS):.1f}%" for r in worst))
    per_time = {x: [r["held"].shares()[x] for r in results] for x in EVENTS}
    log("held-out exact per entry, min / median per time: " + " ".join(
        f"{x[:4]} {100 * min(v):.1f}/{100 * statistics.median(v):.1f}" for x, v in per_time.items()))
    within = [r for r in results if r["held"].worst > 1]
    log(f"entries with a held-out time 2 min or more off: {len(within)}"
        + ("" if not within else ": " + "; ".join(f"{r['entry']['name']} {r['held'].misses[:3]}" for r in within[:10])))
    tier_a = sum(1 for r in results if all(r["stats"]["exact"][x] >= 0.97 and r["stats"]["within1"][x] >= 1.0 for x in EVENTS))
    log(f"zones whose held-out record reaches 97 % exact and 100 % within 1 min on every time: {tier_a} of {len(results)}")
    tied = Counter(min(r["fit"]["tied"], 10) for r in results)
    log(f"plateau of the best count (grid cells; 10 = 10 or more): {dict(sorted(tied.items()))}")
    shifts = [points.km((r["odd"]["lat"], r["odd"]["lng"]), (r["even"]["lat"], r["even"]["lng"])) for r in results]
    log(f"odd-month point to even-month point: median {statistics.median(shifts):.2f} km, max {max(shifts):.2f} km")
    # The takdir check by latitude band.
    bands = defaultdict(common.Score)
    for r in results:
        points.merge(bands[math.floor(r["fit"]["lat"])], r["takdir"])
    for b in sorted(bands):
        log(f"  takdir window, latitude {b}-{b + 1} N: {points.shares(bands[b], ('FAJR', 'ISHA'))}")

    # ---------------------------------------------------------------- districts
    matched = [r for r in results if r["seat"] is not None]
    log()
    log(f"matched to a district seat: {len(matched)} of {len(results)} ("
        + ", ".join(f"{k} {v}" for k, v in Counter(r['how'].split(' ')[0] if r['seat'] else 'unmatched' for r in results).items()) + ")")
    for r in results:
        if r["seat"] is None:
            log(f"  unmatched: {r['entry']['stateName']} / {r['entry']['name']} at {r['fit']['lat']:.4f} {r['fit']['lng']:.4f}: {r['how']}")
    dist = sorted((points.km((r["fit"]["lat"], r["fit"]["lng"]), (r["seat"]["lat"], r["seat"]["lng"])), r) for r in matched)
    log(f"fitted point to the seat: median {statistics.median(d for d, _ in dist):.1f} km, 90 % within "
        f"{dist[int(0.9 * len(dist))][0]:.1f} km, max {dist[-1][0]:.1f} km ({dist[-1][1]['entry']['name']})")
    log("  farthest: " + "; ".join(f"{r['entry']['stateName']}/{r['entry']['name']} -> {r['seat']['name']} {d:.1f} km" for d, r in dist[-8:]))
    split = split_codes(geo)
    log(f"admin2 codes whose seats lie more than {SPLIT_KM:.0f} km apart (two districts under one code; listed under none): {split}")
    claims = defaultdict(list)
    for r in matched:
        c = r["seat"]["admin2"]
        if c:
            claims[c].append(r)
    own = {}
    dropped_shared, dropped_other = [], []
    for c, rs in claims.items():
        if c in ambiguous or c in split:
            dropped_other.append((c, [x["entry"]["name"] for x in rs]))
        elif len(rs) > 1:
            dropped_shared.append((c, [x["entry"]["name"] for x in rs]))
        else:
            own[c] = rs[0]
    log(f"admin2 codes claimed by one entry: {len(own)}; by several (listed under none): {dropped_shared}")
    log(f"claimed codes of several provinces or split (listed under none): {dropped_other}")
    unlisted = ambiguous | set(split) | {c for c, _ in dropped_shared}
    central = {}
    for r in results:
        if points.fold(base_name(r["entry"]["name"])) == points.fold(r["entry"]["stateName"]):
            central[r["province"]] = r
    log(f"central entries (named after their province): {len(central)} of 81")
    # An entry GeoNames gives no seat code (a district created since, or a seat row without one) takes the code of the
    # GeoNames row nearest its fitted point when that row lies within INFER_KM and its code is no other entry's
    # (ERGENE: Marmaracık, 0.7 km). Codes listed under none on purpose stay so.
    inferred = []
    has_code = {r["entry"]["id"] for r in own.values()}
    for r in results:
        if r["entry"]["id"] in has_code or r["province"] in central and central[r["province"]] is r:
            continue
        if r["seat"] is not None and r["seat"]["admin2"] in unlisted:
            continue
        p = (r["fit"]["lat"], r["fit"]["lng"])
        near = min(((points.km(p, (g["lat"], g["lng"])), g) for g in geo_by_province.get(r["province"], []) if g["admin2"]),
                   key=lambda x: x[0], default=None)
        if near and near[0] <= INFER_KM and near[1]["admin2"] not in claims and near[1]["admin2"] not in unlisted \
                and near[1]["admin2"] not in own:
            own[near[1]["admin2"]] = r
            inferred.append(f"{r['entry']['name']} <- {near[1]['admin2']} ({near[1]['name']} {near[0]:.1f} km)")
    log(f"codes inferred for entries without a seat code: {inferred}")
    served = defaultdict(set)
    for c, r in own.items():
        served[r["entry"]["id"]].add(c)
    unserved = []
    for c, prov in code_province.items():
        if c in unlisted or c in own:
            continue
        if prov in central:
            served[central[prov]["entry"]["id"]].add(c)
        else:
            unserved.append(c)
    log(f"admin2 codes served: {sum(len(v) for v in served.values())} of {len(code_province)}; listed under none "
        f"{len(unlisted)}; in a province without a central entry: {len(unserved)}")
    for prov_name in ("İSTANBUL", "ANKARA", "KOCAELİ"):
        prov = pcodes.get(points.fold(prov_name))
        rs = [r for r in results if r["province"] == prov]
        codes = {c for c, p in code_province.items() if p == prov}
        log(f"  {prov_name}: {len(rs)} entries serve {sum(len(served[r['entry']['id']]) for r in rs)} of the province's "
            f"{len(codes)} GeoNames codes; the central entry {len(served[central[prov]['entry']['id']]) if prov in central else 0}; "
            f"entries with no code of their own: {[r['entry']['name'] for r in rs if not served[r['entry']['id']]]}")
    no_code = [r for r in results if not served[r["entry"]["id"]]]
    log(f"entries serving no code (reached only as the nearest entry): {len(no_code)}: "
        + ", ".join(f"{r['entry']['stateName']}/{r['entry']['name']}" for r in no_code))

    # The catalogue's places: by code or by the nearest entry (measured to the zone's point, the seat), how far from
    # the point where the table is computed, and the rows GeoNames files under a far district's code.
    def mapping_point(r):
        return (r["seat"]["lat"], r["seat"]["lng"]) if r["seat"] else (r["fit"]["lat"], r["fit"]["lng"])

    zone_of_code = {c: r for r in results for c in served[r["entry"]["id"]]}
    by_code = nearest_only = differs = 0
    far, stray = [], []
    for row in catalogue.values():
        p = (row["lat"], row["lng"])
        nearest = min(results, key=lambda r: points.km(p, mapping_point(r)))
        r = zone_of_code.get(row["admin2"])
        if r is None:
            nearest_only += 1
            r = nearest
        else:
            by_code += 1
            differs += r is not nearest
            if points.km(p, mapping_point(r)) > STRAY_KM:
                stray.append(f"{row['name']} ({row['id']}, code {row['admin2']}) -> {r['entry']['name']} "
                             f"{points.km(p, mapping_point(r)):.0f} km, nearest {nearest['entry']['name']} {points.km(p, mapping_point(nearest)):.0f} km")
        far.append((points.km(p, (r["fit"]["lat"], r["fit"]["lng"])), row["name"], r["entry"]["name"]))
    far.sort()
    log(f"catalogue TR rows: {by_code} by their admin2 code, {nearest_only} by the nearest entry; the code's entry is not "
        f"the nearest for {differs}; row to the point its table is computed at: median {far[len(far) // 2][0]:.1f} km, "
        f"95 % within {far[int(0.95 * len(far))][0]:.1f} km, max {far[-1][0]:.1f} km")
    log("  farthest rows: " + "; ".join(f"{n} -> {z} {d:.0f} km" for d, n, z in far[-10:]))
    log(f"  rows more than {STRAY_KM:.0f} km from their code's seat (GeoNames slips, for the catalogue build): {stray}")

    # ---------------------------------------------------------------- the asset
    zones = []
    for r in results:
        e, f, seat = r["entry"], r["fit"], r["seat"]
        cat = catalogue.get(seat["id"]) if seat else None
        if cat and cat["arabic"]:
            name_ar = cat["arabic"]
        else:
            native = NATIVE.get(base_name(e["name"])) or points.title_tr(base_name(e["name"]))
            name_ar = points.d3_label(cat["name"] if cat else seat["name"] if seat else native, COUNTRY_AR)
        # The seat is the zone's point, which the mapping measures from; the fitted point is where the table is
        # computed (E6), as for IGGÖ's capitals and the Takvim's towns.
        if seat:
            z = {"id": e["id"], "nameAr": name_ar, "point": common.point(seat["lat"], seat["lng"]),
                 "morningPoint": common.point(f["lat"], f["lng"]), "eveningPoint": common.point(f["lat"], f["lng"])}
        else:
            z = {"id": e["id"], "nameAr": name_ar, "point": common.point(f["lat"], f["lng"])}
        z["admin2"] = sorted(served[e["id"]], key=int)
        if seat:
            z["namedPlaces"] = [{"centre": common.point(seat["lat"], seat["lng"]), "catalogueIds": [], "areaKm": 0.0}]
        z["fit"] = r["stats"]
        zones.append(z)
    names = Counter(z["nameAr"] for z in zones)
    log(f"Arabic names from the catalogue: {sum(1 for z in zones if not z['nameAr'].startswith(chr(0x2066)))}, D3 labels "
        f"{sum(1 for z in zones if z['nameAr'].startswith(chr(0x2066)))}; names used by two entries: {sorted(k for k, v in names.items() if v > 1)}")
    days_year = sum(r["stats"]["heldOut"][0]["days"] for r in results)
    days_month = sum(r["stats"]["heldOut"][1]["days"] for r in results)
    profile = common.fit_stats(
        [common.sample(SOURCE_YEAR, datetime.date(2027, 1, 1), datetime.date(2027, 12, 31), days_year, len(results))],
        [common.sample(SOURCE_YEAR + ", held out by month", datetime.date(2027, 1, 1), datetime.date(2027, 12, 31), days_year,
                       len(results), month_split=True),
         common.sample(SOURCE_MONTH, datetime.date(2026, 10, 16), datetime.date(2026, 11, 15), days_month, len(results))],
        total_held)
    if args.limit is None and not args.no_write:
        path = common.write_asset(AUTHORITY, METHOD_ID, CC, "DISTRICT", zones, fit=profile, source=SOURCE_LINE)
        log(f"wrote {path.relative_to(common.ROOT)} ({len(zones)} zones, {path.stat().st_size} bytes)")
    log(f"done in {time.time() - started:.0f} s")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
