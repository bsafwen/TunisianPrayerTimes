"""
The asset's fit records for the shipped `irn` (EuropeanPresets.IRN: README S4's DAILY_LOCAL_NOON and EVENT_DECLINATION,
E18's dated Dhuhr step, H7 and H8): the reference engine on every day of IRN's 358 tables of 2026, with each zone's
switch days. A southern zone's stored ISHA days go where H7's own rule gives the same days. Single days a table jumps to
Mecca's day (all four of Fajr, sunrise, Maghrib and Isha over 30 min off, the neighbours fitting) are counted apart.
The rules fit no value from the minutes, but the switch days are read from the same tables, so the records are
in-sample in that respect until IRN's 2027 tables are served (fits/irn/README-part.md, Open 1).

Usage: python scripts/prayer_formula/fits/irn/records.py   (about half an hour on 8 processes)
"""

from __future__ import annotations

import datetime
import json
import multiprocessing
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))
import common  # noqa: E402
import fit as irn_fit  # noqa: E402

ref = common.ref
EVENTS = common.EVENTS
ASSET = common.ASSETS / "irn.json"
METHOD = common.method_json(
    id=irn_fit.AUTHORITY, fajr={"type": "angle", "degrees": 16.0}, isha={"type": "angle", "degrees": 15.0},
    offsets={"SUNRISE": -2, "DHUHR": 5, "MAGHRIB": 5},
    datedOffsets=[{"event": "DHUHR", "fromMonth": 3, "fromDay": 21, "toMonth": 9, "toDay": 20, "minutes": 5.0}],
    rounding={e: "DOWN" for e in EVENTS},
    sunEvaluation={**{e: "DAILY_LOCAL_NOON" for e in ("SUNRISE", "DHUHR", "ASR", "MAGHRIB")},
                   **{e: "EVENT_DECLINATION" for e in ("FAJR", "ISHA")}},
    sunModel="MEEUS", highLatitude={"type": "irn"}, polar="IRN_MECCA_CYCLE",
    evidence={"kind": "FROM_TABLES", "source": irn_fit.SOURCE})


def oslo_changes() -> list:
    """Europe/Oslo's UTC offsets over 2024-2028 for zoneFacts' offsetChanges: 01:00 UTC on the last Sundays of March
    and October."""
    out = [[int((datetime.datetime(2024, 1, 1) - datetime.datetime(1970, 1, 1)).total_seconds()) - 3600, 3600]]
    for y in range(2024, 2029):
        for month, offset in ((3, 7200), (10, 3600)):
            d = datetime.date(y, month, 31)
            while d.weekday() != 6:
                d -= datetime.timedelta(days=1)
            out.append([int((datetime.datetime(d.year, d.month, d.day, 1) - datetime.datetime(1970, 1, 1)).total_seconds()), offset])
    return out


CHANGES = oslo_changes()


def case(lat: float, lng: float, day: datetime.date, zone: dict) -> dict:
    c = common.case(METHOD, lat, lng, irn_fit.TZ, day, zone=zone, country="NO")
    c["zoneFacts"]["offsetChanges"] = CHANGES
    return c


def score_entry(task: tuple) -> tuple:
    loc, asset_zone = task
    lat, lng = float(loc["lat_n_s"]), float(loc["long_e_w"])
    days = dict(asset_zone.get("switchDays") or {})
    drop_isha = False
    if lat < irn_fit.SOUTH_LIMIT and "ISHA" in days:
        trial = {k: v for k, v in days.items() if k != "ISHA"}
        zone = common.zone_json(asset_zone["id"], lat, lng, authority=irn_fit.AUTHORITY, morningPoint=asset_zone.get("morningPoint"),
                                eveningPoint=asset_zone.get("eveningPoint"), switchDays=trial)
        engine = ref.CaseEngine(case(lat, lng, datetime.date(irn_fit.YEAR, 6, 1), zone))
        site, _ = engine.running_site_and_shift()
        rule = engine.switch_days(site, engine.day, "ISHA")
        if rule is not None and tuple(rule) == (days["ISHA"]["first"], days["ISHA"]["last"]):
            days, drop_isha = trial, True
    zone = common.zone_json(asset_zone["id"], lat, lng, authority=irn_fit.AUTHORITY, morningPoint=asset_zone.get("morningPoint"),
                            eveningPoint=asset_zone.get("eveningPoint"), switchDays=days)
    diffs = []
    for r in irn_fit.table(loc["location_iso"]):
        day = datetime.date.fromisoformat(r["date"])
        got = common.compute(case(lat, lng, day, zone))
        row = {}
        for e in EVENTS:
            pub = irn_fit.published(day, r[irn_fit.COL[e]])
            row[e] = None if got[e][1] is None or pub is None else irn_fit.minute_diff(got[e][1], pub)
        diffs.append((day, row))
    return loc["location_iso"], drop_isha, days, diffs


def quirk_days(diffs: list) -> set:
    far = [all(row[e] is not None and abs(row[e]) > 30 for e in ("FAJR", "SUNRISE", "MAGHRIB", "ISHA")) for _, row in diffs]
    return {diffs[i][0] for i, b in enumerate(far)
            if b and not (i > 0 and far[i - 1]) and not (i + 1 < len(far) and far[i + 1])}


def main() -> int:
    asset = json.loads(ASSET.read_text(encoding="utf-8"))
    by_id = {z["id"]: z for z in asset["zones"]}
    locs = irn_fit.entries()
    with multiprocessing.Pool(8) as pool:
        results = pool.map(score_entry, [(loc, by_id[loc["kommune_no"]]) for loc in locs], chunksize=1)
    names = {l["location_iso"]: l for l in locs}
    profile, total_quirks, passing, dropped = common.Score(), 0, 0, 0
    for iso, drop_isha, days, diffs in results:
        loc = names[iso]
        quirks = quirk_days(diffs)
        sc = common.Score()
        for day, row in diffs:
            if day in quirks:
                continue
            for e in EVENTS:
                if row[e] is not None:
                    sc.add(e, day, row[e], 0)
        total_quirks += len(quirks)
        passing += irn_fit.passes(sc)
        dropped += drop_isha
        irn_fit.merge(profile, sc)
        zone = by_id[loc["kommune_no"]]
        zone["switchDays"] = days
        source = f"IRN's 2026 table for {loc['kommune_name']}"
        zone["fit"] = common.fit_stats(
            [common.sample(source, datetime.date(irn_fit.YEAR, 1, 1), datetime.date(irn_fit.YEAR, 12, 31), len(diffs), 1)],
            [common.sample(source + " (no value fitted from its minutes; its switch days read from it)",
                           datetime.date(irn_fit.YEAR, 1, 1), datetime.date(irn_fit.YEAR, 12, 31), len(diffs), 1)],
            sc, quirk_days=len(quirks))
    prof = common.fit_stats(
        [common.sample(irn_fit.SOURCE, datetime.date(irn_fit.YEAR, 1, 1), datetime.date(irn_fit.YEAR, 12, 31), 365, len(locs))],
        [common.sample(irn_fit.SOURCE + " (no value fitted from the minutes; switch days read from each table)",
                       datetime.date(irn_fit.YEAR, 1, 1), datetime.date(irn_fit.YEAR, 12, 31), 365, len(locs))],
        profile, quirk_days=total_quirks)
    path = common.write_asset(asset["authority"], asset["methodId"], asset["country"], asset["mapping"], asset["zones"],
                              fit=prof, source=asset["source"])
    print(f"irn: {len(locs)} zones, tier A {passing}; stored ISHA days dropped at {dropped} (H7's rule gives them); "
          f"quirk days {total_quirks}; all: {irn_fit.shares(profile)}; wrote {path.relative_to(common.ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
