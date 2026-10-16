#!/usr/bin/env python3
"""
Re-derives Diyanet's takdir (README H6) from Diyanet's own 2027 tables across Europe and scores it, held out, against
the engine's current takdir (DiyanetTakdir.kt as the reference engine computes it: common.compute with
EuropeanPresets.DIYANET_EU as it stands) and against the rule of rule.py (README-part.md gives the rule and the record).

Data (test-data-local, read at run time, never copied, A7):
- official/<cc>/diyanet/pages/<IlceID>.html.gz: the sample collect.py fetched on 2026-10-16 (144 entries in 14
  countries, 43.2-78.2 N: 133 chosen over latitude and 11 more between 44.2 and 44.6 N for the cutoff;
  research-2026-10-16/diyanet_takdir/selection.tsv lists them with their GeoNames towns);
- official/at/pages/<IlceID>.html.gz: Diyanet's Austrian entries (fits/diyanet_tr/collect.py, 2026-10-16) at the
  fitted points of the asset official-zones/diyanet_eu_at.json (read only), SANKT VEIT AN DER GOLSEN left out (its
  summer-time slip): 190 tables held out entirely;
- every page: 31 days from 2026-10-16 and all of 2027, in the country's clock time;
- the selection's four southern entries (Ushuaia, Punta Arenas, Rio Gallegos and Dunedin, 45.9-54.8 S, collect.py
  `extra`), checked apart (step 4).

Steps:
1. Each sampled entry's point, as fits/diyanet_tr/points.py fits Diyanet's entries (1/400 degree), on the sunrise,
   Dhuhr, Asr and Maghrib of 2027 whose day and night both last more than 6.4 h at the town (the 5-hour rule never
   binds); Fajr and Isha are never used, so they stay out of sample. Cached in
   research-2026-10-16/diyanet_takdir/points.json (delete it to refit). The Fajr (18) and Isha (16) angles are checked
   on November-January.
2. The sampled entries, ordered by latitude, are split in two: even ranks fit, odd ranks are held out. On the fit half
   the rule's variants are compared (a cutoff by the 21 June Isha instead of the latitude, no holds, the 21 June caps
   everywhere or nowhere, a 20-day minimum transition); the held-out half and Austria are scored with the rule.
3. Scores, per latitude band, exact / within 1 min / worst, for Fajr and Isha: on Diyanet's takdir days (the published
   minute is not the plain angle time's), on March-October and on the whole of 2027, for the current engine (--engine)
   and for the rule; with --engine, also the published sunrise and Maghrib from 58 N against the engine's H2 and the
   rule's 5-hour rule.
4. The southern entries: the printed Fajr against the plain 18 deg and the printed Isha against 16 and 17 deg, and the
   days printed 00:00 (Diyanet's mark for a time that does not occur) against the days without the angle.

Usage: python scripts/prayer_formula/fits/diyanet_takdir/fit.py [--engine] [--austria] [--entries NAME,...]
       (about 10 s, 3 min with --engine --austria)
"""

from __future__ import annotations

import argparse
import csv
import datetime
import json
import math
import statistics
import sys
import time
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(1, str(HERE.parent / "diyanet_tr"))
import diyanet_site as site  # noqa: E402  (fits/diyanet_tr: the saved pages' reader)
import points  # noqa: E402  (fits/diyanet_tr: the point fit; imports fits/common.py)
import rule  # noqa: E402

common = points.common
ref = common.ref
LOCAL = common.LOCAL_DATA
WORK = LOCAL / "research-2026-10-16" / "diyanet_takdir"
SELECTION = WORK / "selection.tsv"
POINTS = WORK / "points.json"
AT_ASSET = common.ASSETS / "diyanet_eu_at.json"
ZONES = {"DE": "Europe/Berlin", "NL": "Europe/Amsterdam", "BE": "Europe/Brussels", "FR": "Europe/Paris",
         "CH": "Europe/Zurich", "DK": "Europe/Copenhagen", "SE": "Europe/Stockholm", "NO": "Europe/Oslo",
         "GB": "Europe/London", "PL": "Europe/Warsaw", "FI": "Europe/Helsinki", "IT": "Europe/Rome",
         "RO": "Europe/Bucharest", "IS": "Atlantic/Reykjavik", "SJ": "Arctic/Longyearbyen", "AT": "Europe/Vienna",
         "LU": "Europe/Luxembourg", "CZ": "Europe/Prague", "HU": "Europe/Budapest", "HR": "Europe/Zagreb",
         "RS": "Europe/Belgrade", "LT": "Europe/Vilnius", "LV": "Europe/Riga", "EE": "Europe/Tallinn",
         "IE": "Europe/Dublin",
         # The southern entries' clocks, as their Dhuhr minutes show them (UTC-3 all year in Argentina and at Punta
         # Arenas, the only Chilean entry sampled; mainland Chile keeps America/Santiago).
         "AR": "America/Argentina/Ushuaia", "CL": "America/Punta_Arenas", "NZ": "Pacific/Auckland"}
YEAR = 2027
FAJR, ISHA = 18.0, 16.0
AT_QUIRK = {"33393"}                    # SANKT VEIT AN DER GOLSEN prints its 2027 summer-time days an hour early
BANDS = [(43.0, 44.4), (44.4, 46.0), (46.0, 48.56), (48.56, 50.0), (50.0, 52.0), (52.0, 54.0), (54.0, 56.0),
         (56.0, 58.0), (58.0, 60.0), (60.0, 63.0), (63.0, 66.6), (66.6, 80.0)]
D2R = math.pi / 180


def log(text: str = "") -> None:
    print(text, flush=True)


# ---- the entries and their points ---------------------------------------------------------------------------------

def half_day_hours(day: points.Day, lat: float) -> float:
    c = (math.sin(-0.8333 * D2R) - math.sin(lat * D2R) * day.sd) / (math.cos(lat * D2R) * day.cd)
    return 12.0 if c <= -1 else 0.0 if c >= 1 else math.acos(c) / D2R / 15


def point_mask(seed_lat: float):
    """2027's sunrise, Dhuhr, Asr and Maghrib on the days whose day and night both last more than 6.4 h."""
    def mask(day):
        if day.date.year != YEAR:
            return (False,) * 6
        ok = 3.2 <= half_day_hours(day, seed_lat) <= 8.8
        return (False, ok, True, ok, ok, False)
    return mask


def winter_angles(days: list[points.Day], lat: float, lng: float) -> tuple[float, float]:
    """The Fajr and Isha angles (0.5 degree steps) that reproduce the most November-January minutes."""
    winter = [d for d in days if d.date.month in (11, 12, 1)]

    def count(j: int, angle: float) -> int:
        n = 0
        for d in winter:
            c = (math.sin(-angle * D2R) - math.sin(lat * D2R) * d.sd) / (math.cos(lat * D2R) * d.cd)
            if abs(c) > 1:
                continue
            h = math.acos(c) / D2R * 4
            x = d.noon0 - 4 * lng + (h if j == 5 else -h)
            n += math.floor(x + 0.5) == d.pub[j]
        return n
    fajr = max((count(0, a), -abs(a - FAJR), a) for a in (17.0, 17.5, 18.0, 18.5, 19.0))[2]
    isha = max((count(5, a), -abs(a - ISHA), a) for a in (15.0, 15.5, 16.0, 16.5, 17.0, 17.5))[2]
    return fajr, isha


def load_sample(south: bool = False) -> list[dict]:
    """The sampled entries north of the equator (south=True: those south of it) with their fitted points."""
    with open(SELECTION, encoding="utf-8", newline="") as f:
        sel = [r for r in csv.DictReader(f, delimiter="\t") if (float(r["lat"]) < 0) == south]
    cache = json.loads(POINTS.read_text(encoding="utf-8")) if POINTS.exists() else {}
    out, changed, started = [], False, time.time()
    for r in sel:
        key = f"{r['cc']}/{r['id']}"
        path = LOCAL / "official" / r["cc"].lower() / "diyanet" / "pages" / f"{r['id']}.html.gz"
        if not path.exists():
            log(f"  {key} {r['name']}: no saved page (collect.py pages)")
            continue
        rows = site.parse_page(site.read_text(path))["rows"]
        zone = ZONES[r["cc"]]
        if key not in cache:
            days = points.days_of(rows, zone)
            seed = (float(r["lat"]), float(r["lng"]))
            f = points.fit_point(days, ISHA, point_mask(seed[0]), seed, (seed[0] - 0.5, seed[0] + 0.5), 400)
            fa, ia = winter_angles(days, f["lat"], f["lng"])
            cache[key] = {"lat": f["lat"], "lng": f["lng"], "count": f["count"], "tied": f["tied"],
                          "seedKm": round(points.km((f["lat"], f["lng"]), seed), 2), "fajrAngle": fa, "ishaAngle": ia}
            changed = True
        c = cache[key]
        out.append({"key": key, "cc": r["cc"], "id": r["id"], "name": r["name"], "zone": zone, "rows": rows,
                    "lat": c["lat"], "lng": c["lng"], "fajrAngle": c["fajrAngle"], "ishaAngle": c["ishaAngle"],
                    "seedKm": c["seedKm"], "group": "sample"})
    if changed:
        WORK.mkdir(parents=True, exist_ok=True)
        POINTS.write_text(json.dumps(cache, indent=1) + "\n", encoding="utf-8", newline="\n")
        log(f"  points fitted in {time.time() - started:.0f} s, cached in {POINTS}")
    return sorted(out, key=lambda e: e["lat"])


def load_austria() -> list[dict]:
    """Diyanet's Austrian entries at the asset's fitted points (its tier-1 zones, `diyanet-<IlceID>`)."""
    asset = json.loads(AT_ASSET.read_text(encoding="utf-8"))
    fitted = {}
    for z in asset["zones"]:
        if z["id"].startswith("diyanet-"):
            p = z.get("morningPoint") or z["point"]
            fitted[z["id"][8:]] = (p["latE5"] / 1e5, p["lngE5"] / 1e5)
    out = []
    for e in site.load_entries(LOCAL, "AT"):
        if e["id"] in AT_QUIRK or e["id"] not in fitted:
            continue
        rows, _ = site.load_rows(LOCAL, "AT", e["id"])
        lat, lng = fitted[e["id"]]
        out.append({"key": f"AT/{e['id']}", "cc": "AT", "id": e["id"], "name": e["name"], "zone": "Europe/Vienna",
                    "rows": rows, "lat": lat, "lng": lng, "fajrAngle": FAJR, "ishaAngle": ISHA, "seedKm": 0.0,
                    "group": "austria"})
    return sorted(out, key=lambda e: e["lat"])


# ---- published minutes, the rule and the engine ---------------------------------------------------------------------

def utc_minutes(date: datetime.date, hhmm: str, zone: str, isha: bool = False) -> int:
    """A published clock time as whole UTC minutes after 0 h UT of the date; an Isha before noon belongs to the night."""
    h, m = (int(x) for x in hhmm.split(":"))
    day = date + datetime.timedelta(days=1) if isha and h < 12 else date
    epoch = common.local_to_epoch(zone, day, f"{h}:{m:02d}")
    return (epoch - (date - datetime.date(1970, 1, 1)).days * 86400) // 60


def published(entry: dict) -> dict:
    """{date: (fajr, isha, plain_fajr_rounded, plain_isha_rounded)} for 2027, UTC minutes after 0 h UT of the date."""
    place = rule.Place(entry["lat"], entry["lng"], FAJR, ISHA)
    out = {}
    for d, t in entry["rows"].items():
        if d.year != YEAR:
            continue
        day = place.day(d)
        out[d] = (utc_minutes(d, t[0], entry["zone"]), utc_minutes(d, t[5], entry["zone"], True),
                  None if day.fajr is None else math.floor(day.fajr + 0.5),
                  None if day.isha is None else math.floor(day.isha + 0.5))
    return out


def rule_minutes(entry: dict, june_bound: bool = True, **variant) -> dict:
    tk = rule.takdir_year(rule.Place(entry["lat"], entry["lng"], FAJR, ISHA, june_bound), YEAR, **variant)
    return {d: tuple(None if v is None else math.floor(v + 0.5) for v in fi) for d, fi in tk.times.items()}, tk


def engine_minutes(entry: dict, days: list[datetime.date], events=("FAJR", "ISHA")) -> dict:
    """The engine (the reference's CaseEngine, DIYANET_EU as it stands) at the entry's point: UTC minutes after 0 h UT.
    The entry's dates share the engine's per-date caches (raw days, steps 1-3, angles, H2's held night and H6's year
    table): with one method, point and clock (DAILY_0H_UT, no noon modes) they hold the same values for every case date,
    and a held night or a year's takdir is then built once, not for each date."""
    method = points.method_json("diyanet_eu", ISHA)
    zone = common.zone_json(entry["key"], entry["lat"], entry["lng"], authority="fit")
    shared = {"_raw": {}, "_steps": {}, "_angles": {}, "_held": {}, "_takdir": {}}
    out = {}
    for d in days:
        engine = ref.CaseEngine(common.case(method, entry["lat"], entry["lng"], entry["zone"], d, zone=zone, country=entry["cc"]))
        for name, cache in shared.items():
            setattr(engine, name, cache)
        day = engine.run()
        base = (d - datetime.date(1970, 1, 1)).days * 1440
        out[d] = tuple(None if day.minutes.get(e) is None else int(round(day.minutes[e])) // 60 - base for e in events)
    return out


def ends_check(entries: list[dict]) -> None:
    """The 5-hour rule (H2): the published sunrise and Maghrib of 2027 against the current engine's and against
    rule.py's (sunrise - 7 and sunset + 7 held to Dhuhr -/+ 9.5 h and 2.5 h, and to 21 June's where both ends of
    21 June's night are held), by band, at the entries from 58 N."""
    tally = Tally()
    for e in entries:
        if e["lat"] < 58.0:
            continue
        b = band_of(e["lat"])
        place = rule.Place(e["lat"], e["lng"], FAJR, ISHA)
        days = sorted(d for d in e["rows"] if d.year == YEAR)
        eng = engine_minutes(e, days, ("SUNRISE", "MAGHRIB"))
        for d in days:
            t = e["rows"][d]
            want = (utc_minutes(d, t[1], e["zone"]), utc_minutes(d, t[4], e["zone"], True))
            day = place.day(d)
            mine = (math.floor(day.R + 0.5), math.floor(day.M + 0.5))
            for j, ev in enumerate(("SUNRISE", "MAGHRIB")):
                for label, got in (("engine", eng[d][j]), ("rule", mine[j])):
                    tally.add((label, b, ev, "year"), got, want[j])
                    tally.add((label, "all", ev, "year"), got, want[j])
    log("The 5-hour rule, all of 2027, from 58 N (exact % / within 1 min % / worst min):")
    log("  band              |  engine Sunrise       engine Maghrib     |  rule Sunrise         rule Maghrib")
    for b in BANDS + ["all"]:
        if not tally.n[("rule", b, "SUNRISE", "year")]:
            continue
        name = "all" if b == "all" else f"{b[0]:.2f}-{b[1]:.2f}"
        log(f"  {name:16s}  | {tally.text(('engine', b, 'SUNRISE', 'year'))}   {tally.text(('engine', b, 'MAGHRIB', 'year'))} "
            f"| {tally.text(('rule', b, 'SUNRISE', 'year'))}   {tally.text(('rule', b, 'MAGHRIB', 'year'))}")


# ---- scores -------------------------------------------------------------------------------------------------------

class Tally:
    def __init__(self):
        self.n = defaultdict(int)
        self.exact = defaultdict(int)
        self.w1 = defaultdict(int)
        self.worst = defaultdict(int)

    def add(self, key, got, want):
        if got is None:
            return
        diff = abs(got - want)
        self.n[key] += 1
        self.exact[key] += diff == 0
        self.w1[key] += diff <= 1
        self.worst[key] = max(self.worst[key], diff)

    def text(self, key) -> str:
        n = self.n[key]
        return "      -       " if not n else f"{100 * self.exact[key] / n:5.1f}/{100 * self.w1[key] / n:5.1f} {self.worst[key]:3d}"


def south_check(entries: list[dict]) -> None:
    """The southern entries (2026-10-16 to 2027-12-31): Fajr against the plain 18 deg, Isha against 16 and 17 deg, on
    the days with a printed time, and the days printed 00:00 (no time) against the days without the angle."""
    log("Southern entries (no takdir expected): Fajr 18 deg, Isha 16 / 17 deg, exact / within 1 min of the printed days")
    for e in entries:
        places = {a: rule.Place(e["lat"], e["lng"], FAJR, a) for a in (16.0, 17.0)}
        n = {"F": [0, 0, 0], 16.0: [0, 0, 0], 17.0: [0, 0, 0]}
        placeholders = absent_there = 0
        for d, t in e["rows"].items():
            for j, hhmm in ((0, t[0]), (5, t[5])):
                if hhmm == "00:00":
                    placeholders += 1
                    day = places[17.0].day(d)
                    absent_there += (day.fajr if j == 0 else day.isha) is None or (j == 5 and day.fajr is None)
                    continue
                want = utc_minutes(d, hhmm, e["zone"], j == 5)
                for key in (("F",) if j == 0 else (16.0, 17.0)):
                    day = places[17.0 if key == "F" else key].day(d)
                    got = day.fajr if j == 0 else day.isha
                    if got is None:
                        continue
                    diff = abs(math.floor(got + 0.5) - want)
                    n[key][0] += 1
                    n[key][1] += diff == 0
                    n[key][2] += diff <= 1
        cells = "  ".join(f"{k if k == 'F' else f'Isha {k:g}'}: {v[1]}/{v[2]}/{v[0]}" for k, v in n.items())
        log(f"  {e['name']:14s} {e['lat']:8.4f} {e['lng']:9.4f}  {cells};  00:00 printed {placeholders} times, "
            f"{absent_there} where the time or the day's Fajr is absent")


def band_of(lat: float) -> tuple[float, float]:
    return next(b for b in BANDS if b[0] <= lat < b[1])


def score(entries: list[dict], computed: dict, tally: Tally, label: str) -> None:
    """computed[key] = {date: (fajr, isha)}; adds to tally[(label, band, event, scope)] for the three scopes."""
    for e in entries:
        pub = e["pub"]
        b = band_of(e["lat"])
        for d, (pf, pi, plf, pli) in pub.items():
            got = computed[e["key"]].get(d)
            if got is None:
                continue
            summer = datetime.date(YEAR, 3, 1) <= d <= datetime.date(YEAR, 10, 31)
            for j, (want, plain) in enumerate(((pf, plf), (pi, pli))):
                ev = ("FAJR", "ISHA")[j]
                takdir_day = plain is None or abs(want - plain) > 1
                for scope, on in (("takdir", takdir_day), ("summer", summer), ("year", True)):
                    if on:
                        tally.add((label, b, ev, scope), got[j], want)
                        tally.add((label, "all", ev, scope), got[j], want)


def report(tally: Tally, labels: list[str], scope: str, title: str) -> None:
    log(title)
    head = "  band (N)        " + "".join(f"| {lab:^37s}" for lab in labels)
    log(head)
    log("                  " + "".join("|  Fajr ex/w1 max     Isha ex/w1 max    " for _ in labels))
    for b in BANDS + ["all"]:
        if not any(tally.n[(lab, b, "FAJR", scope)] for lab in labels):
            continue
        name = "all" if b == "all" else f"{b[0]:.2f}-{b[1]:.2f}"
        row = f"  {name:16s}"
        for lab in labels:
            row += f"| {tally.text((lab, b, 'FAJR', scope))}   {tally.text((lab, b, 'ISHA', scope))} "
        log(row)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--engine", action="store_true", help="also score the current engine (common.compute)")
    parser.add_argument("--austria", action="store_true", help="also score Diyanet's 190 Austrian tables (held out)")
    parser.add_argument("--entries", help="only these entry names (comma-separated)")
    args = parser.parse_args(argv)
    sys.stdout.reconfigure(encoding="utf-8")
    started = time.time()
    sample = load_sample()
    if args.entries:
        names = set(args.entries.split(","))
        sample = [e for e in sample if e["name"] in names]
    fit_half = [e for i, e in enumerate(sample) if i % 2 == 0]
    held_half = [e for i, e in enumerate(sample) if i % 2 == 1]
    groups = {"fit": fit_half, "held": held_half}
    if args.austria:
        groups["austria"] = load_austria()
    for es in groups.values():
        for e in es:
            e["pub"] = published(e)
    countries = defaultdict(int)
    for e in sample:
        countries[e["cc"]] += 1
    log(f"Diyanet's 2027 tables: {len(sample)} sampled entries ({len(fit_half)} fit, {len(held_half)} held out), "
        + ", ".join(f"{k} {v}" for k, v in sorted(countries.items()))
        + (f"; Austria {len(groups['austria'])} held out" if args.austria else ""))
    other_angles = [e for e in sample if (e["fajrAngle"], e["ishaAngle"]) != (FAJR, ISHA)]
    log(f"  winter angles other than 18/16: {[(e['name'], e['fajrAngle'], e['ishaAngle']) for e in other_angles]}")
    log(f"  fitted point to the town: median {statistics.median(e['seedKm'] for e in sample):.1f} km, max "
        f"{max(e['seedKm'] for e in sample):.1f} km")

    # Variants of the rule, compared on the fit half.
    variants = {"rule": {}, "Isha cutoff": {"cutoff": "isha"}, "no holds": {"holds": False},
                "21 June cap everywhere": {"june_cap": "always"},
                "no 21 June caps": {"june_cap": "never", "june_bound": False, "holds": False},
                "20-day minimum": {"min_days": 20}}
    tally = Tally()
    for name, var in variants.items():
        computed = {e["key"]: rule_minutes(e, **var)[0] for e in fit_half}
        score(fit_half, computed, tally, name)
    report(tally, list(variants), "summer", "Fit half, March-October, by variant (exact % / within 1 min % / worst min):")

    # Held out: the rule, and the engine where asked.
    tally = Tally()
    for gname in [g for g in groups if g != "fit"]:
        es = groups[gname]
        computed = {e["key"]: rule_minutes(e)[0] for e in es}
        score(es, computed, tally, f"rule:{gname}")
        if args.engine:
            t0 = time.time()
            eng = {e["key"]: engine_minutes(e, sorted(e["pub"])) for e in es}
            log(f"  engine on {gname}: {time.time() - t0:.0f} s")
            score(es, eng, tally, f"engine:{gname}")
    for gname in [g for g in groups if g != "fit"]:
        labels = [f"engine:{gname}", f"rule:{gname}"] if args.engine else [f"rule:{gname}"]
        for scope, title in (("takdir", "Diyanet's takdir days"), ("summer", "March-October"), ("year", "all of 2027")):
            report(tally, labels, scope, f"Held out ({gname}), {title} (exact % / within 1 min % / worst min):")
    if args.engine:
        ends_check(sample)
    south = load_sample(south=True)
    if south and not args.entries:
        south_check(south)
    log(f"done in {time.time() - started:.0f} s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
