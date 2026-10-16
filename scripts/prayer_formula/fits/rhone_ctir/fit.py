#!/usr/bin/env python3
"""
Fits `rhone_ctir`, the Conseil des Mosquees du Rhone's unified calendar (plan 5.6, 7.2, 7.5, Q33; milestone M3, 9.4),
and writes its official-zone asset, android-app/app/src/main/assets/official-zones/rhone_ctir.json (schema 1).

Data, read at run time from test-data-local and never copied (D13, A7):
- research-2026-10-11/FR/ctir_2020.txt: the text of the CTIR's 2020 table (cmrofficiel.fr, "Horaire-CTIR-horaire-2020.pdf",
  the only table the council publishes): 366 rows of six clock times, Paris time, summer time from 03-29 to 10-24.

The table is a fixed-year table that Rhone's mosques still call by date (Q33), so it is fitted at its reference year,
2020 (E5), and every held-out score is the app's 2026 computation against the 2020 row of the same date, as the
mosques show it in 2026 (A8). The CMR computes "from Lyon's latitude and longitude" (cmrofficiel.fr, harmonisation
page), so the point is fitted within 0.05 deg of Lyon's GeoNames point.

What the table shows, and what the fit therefore does:
- Its summer-time rows (03-29 to 10-24) hold the previous date's times: each such row equals the times computed for
  the day before (read so, the fitted profile gives the printed sunrise and Maghrib on 97-98 % of all rows; read at
  their own date, on 49 %). The fit reads them so (the quirk, plan 8.3); the app computes the actual date, as it does
  for every table.
- Sunrise, Dhuhr and Maghrib are the sun at the event instant; Fajr, Isha and Asr follow a declination taken late in
  the day (about 22-23 h UT), which E4's KACST mode (declination at 21 h UT, equation of time at 9 h UT) approaches
  best of the engine's modes.

Stages (9.4), each by windows (a published minute bounds the unrounded time to a half-open minute under E1; the
value most windows cover wins), for each sun model (E14):
1. Dhuhr: evaluation (E4), rounding, whole-minute offset and longitude.
2. Sunrise and sunset (standard horizon): latitude, then each one's evaluation, rounding and offset.
3. Fajr and Isha: evaluation, rounding and angle (offset 0), the angle kept to 0.1 deg.
4. Asr: shadow factor, evaluation, rounding and offset.
VSOP87 replaces the default MEEUS sun only where it covers more than 0.5 % more windows over the four stages. Up,
nearest and down are the same table at offsets 0.5 or 1 min apart, so equal counts keep the order UP, NEAREST, DOWN
(sunrise: DOWN first).
Held out: month-split (stages refitted on the odd months and scored on the even ones, and the reverse), every
held-out day scored with common.compute (the reference engine, E19 included at a French place) for 2026.

Usage: python scripts/prayer_formula/fits/rhone_ctir/fit.py
"""

from __future__ import annotations

import datetime
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import common  # noqa: E402

ref = common.ref
EVENTS = common.EVENTS
AUTHORITY = "rhone_ctir"
TZ = "Europe/Paris"
DATA = common.LOCAL_DATA / "research-2026-10-11" / "FR" / "ctir_2020.txt"
REF_YEAR = 2020
SCORE_YEAR = 2026
LYON = (45.749055, 4.8478904)          # GeoNames 2996944 (geonames.org, read 2026-10-15); admin2 Rhone (69)
BOX = 0.05                             # degrees around Lyon searched for the table's point
LAT_STEP = 0.005
MODELS = ("MEEUS", "VSOP87")
EVALS = ("EVENT_INSTANT", "DAILY_0H_UT", "DAILY_10H_UT", "KACST")
ASR_EVALS = EVALS + ("LOCAL_MIDNIGHT_SHADOW",)
WINDOW = {"NEAREST": (-30.0, 30.0), "UP": (-60.0, 0.0), "DOWN": (0.0, 60.0)}
PRAYER_MODES = ("UP", "NEAREST", "DOWN")
SUNRISE_MODES = ("DOWN", "NEAREST", "UP")
PLAN_ANGLES = {"FAJR": 14.5, "ISHA": 13.5}   # plan 7.5's values, preferred only where they are among the best
ONE = datetime.timedelta(days=1)
SOURCE = "CTIR 2020 table (cmrofficiel.fr), summer-time rows read as the previous date"
HELD_SOURCE = "CTIR 2020 table read by date in 2026, as Rhone's mosques use it"
SOURCE_LINE = ("Conseil des Mosquees du Rhone (CTIR), unified calendar 2020 (cmrofficiel.fr), called by date in 2026; "
               "one zone for the departement")
MONTHS = {"janv": 1, "févr": 2, "mars": 3, "avr": 4, "mai": 5, "juin": 6, "juil": 7, "août": 8, "sept": 9,
          "oct": 10, "nov": 11, "déc": 12}


# ---- data -------------------------------------------------------------------------------------------------------

def parse(path: Path) -> dict[datetime.date, list[int]]:
    """The table's rows: date -> six clock minutes (Fajr, sunrise, Dhuhr, Asr, Maghrib, Isha)."""
    rows, month = {}, None
    for line in path.read_text(encoding="utf-8").splitlines():
        m = re.match(r"^([a-zéû]+)\.?-20\b", line)
        if m and m.group(1) in MONTHS:
            month = MONTHS[m.group(1)]
            continue
        m = re.match(r"^(\d{1,2}) ((?:\d{1,2}:\d{2} [AP]M ?){6})$", line.strip())
        if m and month:
            mins = [(int(h) % 12 + (12 if ap == "PM" else 0)) * 60 + int(mm)
                    for h, mm, ap in re.findall(r"(\d{1,2}):(\d{2}) ([AP]M)", m.group(2))]
            rows[datetime.date(REF_YEAR, month, int(m.group(1)))] = mins
    return rows


def summer(day: datetime.date) -> bool:
    return common.utc_offset(TZ, day) == 7200


def published(day: datetime.date, minute: int) -> int:
    return common.local_to_epoch(TZ, day, f"{minute // 60}:{minute % 60:02d}")


def source_of(day: datetime.date, quirk: bool = True) -> datetime.date:
    """The date whose times a row holds: the previous date on the table's summer-time rows."""
    return day - ONE if quirk and summer(day) else day


# ---- fast raw events (the reference's own primitives, S2-S7) ------------------------------------------------------

_SUNS: dict = {}


def sun_of(day: datetime.date, model: str, mode: str):
    key = (day, model, mode)
    if key not in _SUNS:
        cyc = ref.Cycle(day)                    # U = D at Lyon (12:00 - 1 h + 0.32 h UTC)
        _SUNS[key] = (cyc, ref.SunFunction(ref.SUN_MODELS[model], mode if mode != "LOCAL_MIDNIGHT_SHADOW" else "EVENT_INSTANT", cyc.jd0))
    return _SUNS[key]


def transit_at(day, lng, model, mode):
    cyc, sun = sun_of(day, model, mode)
    return cyc, sun, ref.transit(cyc, sun, lng)


def event_at(day, lat, lng, alt, side, model, mode):
    cyc, sun, t0 = transit_at(day, lng, model, mode)
    return cyc.instant(ref.event(cyc, sun, lat, lng, alt, side, t0))


def asr_at(day, lat, lng, model, mode, factor):
    cyc, sun, t0 = transit_at(day, lng, model, mode)
    if mode == "LOCAL_MIDNIGHT_SHADOW":
        midnight = common.local_to_epoch(TZ, day, "0:00")
        decl = ref.SUN_MODELS[model](ref.jd_of_instant(midnight))[0]
    else:
        decl = sun(cyc.jd0 + t0 / 24)[0]
    alt = ref.atand(1 / (factor + ref.tand(abs(lat - decl))))
    return cyc.instant(ref.event(cyc, sun, lat, lng, alt, 1, t0))


def depression(day, lat, lng, t, model, mode):
    cyc, sun = sun_of(day, model, mode)
    return -ref.altitude(cyc, sun, lat, lng, cyc.hours(t))


# ---- windows -----------------------------------------------------------------------------------------------------

def best_cover(intervals, lo, hi, prefer):
    """(count, value): the most half-open intervals covering one value within [lo, hi]; the value is `prefer` where it
    lies in a best plateau, else the middle of the best plateau nearest to it."""
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


def exact(t, offset_min, mode, pub) -> bool:
    return ref.round_instant(t + 60 * offset_min, mode) == pub


# ---- the stages ---------------------------------------------------------------------------------------------------

def fit(rows: dict[datetime.date, list[int]], log: bool = False) -> dict:
    """Stages 1-4 on the given 2020 rows, for each sun model; VSOP87 wins only with more than 0.5 % more windows."""
    items = [(source_of(d), published(d, mins[i]) - 86400 * (d - source_of(d)).days, i)
             for d, mins in sorted(rows.items()) for i in range(6)]
    by_event = {e: [(s, pub) for s, pub, i in items if i == k] for k, e in enumerate(EVENTS)}
    results = []
    for model in MODELS:
        p = {"model": model, "evals": {}, "modes": {}, "offsets": {}, "counts": {}}
        lat0, lng0 = LYON
        # Stage 1: Dhuhr -> longitude.
        best = None
        for ev in EVALS:
            t0s = []
            for s, pub in by_event["DHUHR"]:
                cyc, _, t0 = transit_at(s, lng0, model, ev)
                t0s.append((cyc.instant(t0), pub))
            for mode in PRAYER_MODES:
                lo, hi = WINDOW[mode]
                for off in range(0, 11):
                    ivs = [(lng0 + (t + 60 * off - pub - hi) / 240, lng0 + (t + 60 * off - pub - lo) / 240) for t, pub in t0s]
                    n, lng = best_cover(ivs, lng0 - BOX, lng0 + BOX, lng0)
                    key = (n, -EVALS.index(ev), -PRAYER_MODES.index(mode), -abs(lng - lng0))
                    if best is None or key > best[0]:
                        best = (key, ev, mode, off, lng)
        (n, *_), ev, mode, off, lng = best
        p["evals"]["DHUHR"], p["modes"]["DHUHR"], p["offsets"]["DHUHR"], p["counts"]["DHUHR"] = ev, mode, off, n
        p["lng"] = round(lng, 5)
        # Stage 2: sunrise and sunset -> latitude (sun at the event instant), then each one's evaluation, rounding
        # and offset at that latitude.
        horizon = (("SUNRISE", -1, SUNRISE_MODES, range(-3, 3)), ("MAGHRIB", 1, PRAYER_MODES, range(0, 7)))

        def rule_for(e, side, modes, offs, lat, evals):
            cand = None
            for ev in evals:
                ts = [(event_at(s, lat, p["lng"], -0.8333, side, model, ev), pub) for s, pub in by_event[e]]
                for mode in modes:
                    for off in offs:
                        cnt = sum(1 for t, pub in ts if exact(t, off, mode, pub))
                        ckey = (cnt, -EVALS.index(ev), -modes.index(mode), -abs(off))
                        if cand is None or ckey > cand[0]:
                            cand = (ckey, ev, mode, off)
            return cand

        best_lat = None
        steps = int(round(BOX / LAT_STEP))
        for k in range(-steps, steps + 1):
            lat = lat0 + k * LAT_STEP
            total = sum(rule_for(e, side, modes, offs, lat, ("EVENT_INSTANT",))[0][0] for e, side, modes, offs in horizon)
            key = (total, -abs(lat - lat0))
            if best_lat is None or key > best_lat[0]:
                best_lat = (key, lat)
        p["lat"] = round(best_lat[1], 5)
        for e, side, modes, offs in horizon:
            (cnt, *_), ev, mode, off = rule_for(e, side, modes, offs, p["lat"], EVALS)
            p["evals"][e], p["modes"][e], p["offsets"][e], p["counts"][e] = ev, mode, off, cnt
        # Stage 3: Fajr and Isha -> evaluation, rounding, angle.
        for e, side in (("FAJR", -1), ("ISHA", 1)):
            cand = None
            for ev in EVALS:
                for mode in PRAYER_MODES:
                    lo, hi = WINDOW[mode]
                    ivs = []
                    for s, pub in by_event[e]:
                        da = depression(s, p["lat"], p["lng"], pub + lo, model, ev)
                        db = depression(s, p["lat"], p["lng"], pub + hi, model, ev)
                        ivs.append((min(da, db), max(da, db)))
                    n, angle = best_cover(ivs, 10.0, 20.0, PLAN_ANGLES[e])
                    ckey = (n, -EVALS.index(ev), -PRAYER_MODES.index(mode))
                    if cand is None or ckey > cand[0]:
                        cand = (ckey, ev, mode, angle, ivs)
            (n, *_), ev, mode, angle, ivs = cand
            # The angle to 0.1 deg, the precision bodies state angles in; the windows' own value is logged.
            rounded = round(angle, 1)
            p["evals"][e], p["modes"][e], p["offsets"][e] = ev, mode, 0
            p["counts"][e] = sum(1 for a, b in ivs if a <= rounded < b)
            p["fajr" if e == "FAJR" else "isha"] = rounded
            p["window_" + e] = f"{angle:.4f} ({n})"
        # Stage 4: Asr -> factor, evaluation, rounding, offset.
        cand = None
        for factor in (1, 2):
            for ev in ASR_EVALS:
                ts = [(asr_at(s, p["lat"], p["lng"], model, ev, factor), pub) for s, pub in by_event["ASR"]]
                for mode in PRAYER_MODES:
                    for off in range(-3, 5):
                        cnt = sum(1 for t, pub in ts if exact(t, off, mode, pub))
                        ckey = (cnt, -factor, -ASR_EVALS.index(ev), -PRAYER_MODES.index(mode), -abs(off))
                        if cand is None or ckey > cand[0]:
                            cand = (ckey, factor, ev, mode, off)
        (n, *_), factor, ev, mode, off = cand
        p["factor"] = factor
        p["evals"]["ASR"], p["modes"]["ASR"], p["offsets"]["ASR"], p["counts"]["ASR"] = ev, mode, off, n
        p["total"] = sum(p["counts"].values())
        if log:
            print(f"  {model}: windows covered {p['total']} of {len(items)} " + " ".join(f"{e[:4]} {p['counts'][e]}" for e in EVENTS))
        results.append(p)
    default, other = results
    # VSOP87 replaces the default sun only where it covers clearly more windows (more than 0.5 %).
    return other if other["total"] > default["total"] * 1.005 else default


# ---- profile, zone and scores -------------------------------------------------------------------------------------

def method_of(p: dict) -> dict:
    return common.method_json(
        id=AUTHORITY,
        fajr={"type": "angle", "degrees": p["fajr"]}, isha={"type": "angle", "degrees": p["isha"]},
        offsets={e: p["offsets"][e] for e in EVENTS if p["offsets"][e]},
        rounding={e: p["modes"][e] for e in EVENTS},
        sunEvaluation={e: p["evals"][e] for e in EVENTS if p["evals"][e] != "EVENT_INSTANT"},
        sunModel=p["model"], asrShadow=p["factor"],
        evidence={"kind": "FROM_TABLES", "source": "The Conseil des Mosquees du Rhone's 2020 unified calendar"},
    )


def zone_of(p: dict) -> dict:
    """The one zone, id "69" (the departement it serves; CountryMethodDefaultsTest's stored pick uses that id)."""
    return common.zone_json("69", p["lat"], p["lng"], authority=AUTHORITY)


def preset_method() -> dict:
    """EuropeanPresets.RHONE_CTIR as it stood before this fit."""
    rounding = {e: "NEAREST" for e in EVENTS}
    rounding["DHUHR"] = "UP"
    return common.method_json(id=AUTHORITY, fajr={"type": "angle", "degrees": 14.5}, isha={"type": "angle", "degrees": 13.5},
                              offsets={"DHUHR": 5, "MAGHRIB": 3}, rounding=rounding,
                              evidence={"kind": "FROM_TABLES", "source": "preset"})


def score(method: dict, zone: dict | None, rows, year: int, quirk_read: bool = False,
          skip_summer: bool = False) -> tuple[common.Score, int]:
    """The app's minutes (common.compute, at a French place: E19 included) against the rows. Another year: each row
    against the app's computation for the same date in that year, as the mosques show it, the printed clock read with
    2020's offset of that date (in 2026 summer time has the same dates). quirk_read (2020 only): a summer-time row
    against its source date's computation, the fit's own reading. Returns the score and the count of ESTIMATED times."""
    sc, estimated = common.Score(), 0
    for d, mins in sorted(rows.items()):
        if skip_summer and summer(d):
            continue
        if year != REF_YEAR:
            if (d.month, d.day) == (2, 29):
                continue
            day = d.replace(year=year)
            target = day
        else:
            day = d
            target = source_of(d, quirk_read)
        out = common.compute(common.case(method, LYON[0], LYON[1], TZ, target, zone=zone, country="FR"))
        shift = 86400 * (d - target).days
        for i, e in enumerate(EVENTS):
            _, minute, status = out[e]
            estimated += status == "ESTIMATED"
            sc.add(e, day, (minute + shift) // 60, published(d, mins[i]) // 60)
    return sc, estimated


def shares(sc: common.Score) -> str:
    ex, w1 = sc.shares(), sc.within1_shares()
    return " ".join(f"{e[:4]} {100 * ex[e]:5.1f}/{100 * w1[e]:5.1f}" for e in EVENTS) + f"  max {sc.worst}"


def describe(p: dict) -> str:
    return (f"{p['model']} point {p['lat']:.4f},{p['lng']:.4f} Fajr {p['fajr']} Isha {p['isha']} Asr factor {p['factor']}; "
            + ", ".join(f"{e[:4]} {p['evals'][e][:5]} {p['modes'][e]} {p['offsets'][e]:+d}" for e in EVENTS)
            + f"; windows' own angles: Fajr {p['window_FAJR']}, Isha {p['window_ISHA']}")


def main() -> int:
    rows = parse(DATA)
    assert len(rows) == 366, len(rows)
    print(f"rhone_ctir: {len(rows)} rows of the CTIR 2020 table ({DATA.name})")
    halves = {"odd": {d: v for d, v in rows.items() if d.month % 2 == 1},
              "even": {d: v for d, v in rows.items() if d.month % 2 == 0}}
    fitted = {}
    for name, part in halves.items():
        print(f"fit on the {name} months:")
        fitted[name] = fit(part, log=True)
        print("  " + describe(fitted[name]))
    print("fit on all months:")
    final = fit(rows, log=True)
    print("  " + describe(final))

    # Held out: each half scored with the fit of the other half, for 2026 as the mosques show the table.
    held = common.Score()
    for name, other in (("odd", "even"), ("even", "odd")):
        p = fitted[other]
        sc, _ = score(method_of(p), zone_of(p), halves[name], SCORE_YEAR)
        for e in EVENTS:
            held.exact[e] += sc.exact[e]
            held.within1[e] += sc.within1[e]
            held.total[e] += sc.total[e]
            for m in range(12):
                held.months[e][m] += sc.months[e][m]
        held.worst = max(held.worst, sc.worst)
        held.misses += sc.misses
    print("held out (month-split), 2026 against the 2020 rows by date, exact/within 1 (%):")
    print("  " + shares(held))

    method, zone = method_of(final), zone_of(final)
    ref_sc, ref_est = score(method, zone, rows, REF_YEAR, quirk_read=True)
    print("final profile at the reference year, summer rows read as the previous date (in-sample):")
    print("  " + shares(ref_sc) + f"  ESTIMATED {ref_est}")
    own_sc, _ = score(method, zone, rows, REF_YEAR, quirk_read=False)
    print("final profile at the reference year, every row at its own date (the quirk unread):")
    print("  " + shares(own_sc))
    app_sc, app_est = score(method, zone, rows, SCORE_YEAR)
    print(f"final profile in {SCORE_YEAR} against the rows by date (in-sample for the parameters):")
    print("  " + shares(app_sc) + f"  ESTIMATED {app_est}")
    win_sc, _ = score(method, zone, rows, SCORE_YEAR, skip_summer=True)
    print(f"final profile in {SCORE_YEAR}, winter rows only (the summer rows left out):")
    print("  " + shares(win_sc))
    for year in (2027, 2028):
        y_sc, _ = score(method, zone, rows, year)
        print(f"final profile in {year} against the rows by date:")
        print("  " + shares(y_sc))
    pre_sc, _ = score(preset_method(), None, rows, SCORE_YEAR)
    print(f"the preset before this fit (14.5/13.5, Dhuhr +5 up, Maghrib +3, rest nearest) at Lyon in {SCORE_YEAR}:")
    print("  " + shares(pre_sc))

    stats = common.fit_stats(
        [common.sample(SOURCE, datetime.date(REF_YEAR, 1, 1), datetime.date(REF_YEAR, 12, 31), len(rows), 1)],
        [common.sample(HELD_SOURCE, datetime.date(SCORE_YEAR, 1, 1), datetime.date(SCORE_YEAR, 12, 31), held.total["DHUHR"], 1,
                       month_split=True)],
        held)
    asset_zone = {k: v for k, v in zone.items() if k != "authority"}
    asset_zone.update({"nameAr": "الرون", "admin2": ["69"],
                       "namedPlaces": [{"centre": common.point(*LYON), "catalogueIds": [], "areaKm": 0.0}],
                       "fit": stats})
    path = common.write_asset(AUTHORITY, AUTHORITY, "FR", "NATIONAL", [asset_zone], fit=stats, source=SOURCE_LINE)
    print(f"wrote {path.relative_to(common.ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
