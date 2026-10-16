#!/usr/bin/env python3
"""
Fits `apbif`, the published method of the APBIF (Association des Projets de Bienfaisance Islamiques en France; plan
7.2, 7.5, 9.2; milestone M3: "its rounding per event, on APBIF's 2026 tables"). `apbif` is a method at the user's
point (7.5): this fit writes no zone and no asset, only its report.

Data, read at run time from test-data-local/research-2026-10-16/FR/apbif/ and never copied (D13, A7), fetched by
fetch.py from apbif.fr's «Horaires des prières de votre ville» (a GET of cityId and month, one page per town and
month, the year 2026, Paris's clock with summer time on 2026's dates):
- paris/: Paris (cityId 247), all 12 months; lille/ (73), marseille/ (2), strasbourg/ (9): all 12 months;
  rennes/ (8): January only (the site answered 429 Too Many Requests after 40 pages; nothing was retried).
- wayback/horaires_20250522155934.html and horaires_20250807125518.html: the Wayback Machine's copies of the same
  page (Paris, the current month) in May and August 2025. Their 62 rows equal the 2026 rows of those dates on all
  seven columns (Imsak included): the table is perpetual, the same times every year.

What the tables are. A perpetual table read on 2026's clock. The reference-year scan (E5: each year's sun read at
the 2026 dates) puts its sun in 2026's leap phase about 12 years back: 2014 fits best (2010 and 2018 almost as
well), 2026 itself 4 % fewer windows. So the stages run at that reference year, and every score is computed for the
actual year as the app shows it (A8), where the 1-minute differences of the year count.

Stages (9.4) at the reference year at Paris's catalogue point (GeoNames 2988507), each by windows (a published minute
bounds the unrounded time to a half-open minute under E1); the methodology's values are the defaults (18 deg and
12 deg, + 6 on Fajr, Dhuhr, Asr and Maghrib, the sun's centre at -1 deg) and the preset's roundings; an alternative
must cover more than 0.5 % and at least 2 more windows:
1. Rounding and whole-minute offset per time (Dhuhr also its longitude, a diagnostic: the method runs at the user's
   point).
2. Sunrise and sunset horizon: each town's own best fixed altitude, reported (a diagnostic, below).
3. Fajr and Isha: rounding, and the angle the windows give (days with the angle only).
4. Asr: the sun (geometric or apparent, E13), the evaluation (E4) and the rounding and offset.
5. The absent nights (Paris 13-30 June, Lille 28 May-16 July): the middle of the night, unramped (7.4).
Held out: the stages refitted on Paris's odd months score its even months, and the reverse (a month split), and the
profile fitted on Paris scores the four other towns at their own catalogue points; every score with common.compute
(the reference engine) for 2026.

Usage: python scripts/prayer_formula/fits/apbif/fit.py
"""

from __future__ import annotations

import datetime
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import common  # noqa: E402

ref = common.ref
EVENTS = common.EVENTS
TZ = "Europe/Paris"
DATA = common.LOCAL_DATA / "research-2026-10-16" / "FR" / "apbif"
YEAR = 2026
# The towns' catalogue points (android-app/.../places/europe-cities.tsv, GeoNames).
TOWNS = {"paris": (48.85341, 2.3488), "lille": (50.63391, 3.05512), "marseille": (43.29695, 5.38107),
         "strasbourg": (48.58392, 7.74553), "rennes": (48.11109, -1.67431)}
FIT_TOWN = "paris"
MODES = ("NEAREST", "UP", "DOWN")
WINDOW = {"NEAREST": (-30.0, 30.0), "UP": (-60.0, 0.0), "DOWN": (0.0, 60.0)}
CLEARLY, MIN_GAIN = 1.005, 2
PRESET = {
    "fajr": 18.0, "isha": 12.0, "horizon": -1.0, "factor": 1, "asr_sun": "GEOMETRIC", "asr_eval": "EVENT_INSTANT",
    "offsets": {"FAJR": 6, "SUNRISE": 0, "DHUHR": 6, "ASR": 6, "MAGHRIB": 6, "ISHA": 0},
    "rounding": {"FAJR": "NEAREST", "SUNRISE": "DOWN", "DHUHR": "NEAREST", "ASR": "NEAREST", "MAGHRIB": "UP", "ISHA": "NEAREST"},
}
YEARS = tuple(range(2006, 2031))
ASR_EVALS = ("EVENT_INSTANT", "SHADOW_AT_DHUHR", "SHADOW_AT_EVENT", "DAILY_0H_UT", "DAILY_10H_UT", "KACST")
ROW = re.compile(r"<tr[^>]*>\s*<td>(\d{1,2})</td>((?:<td>\d{2}:\d{2}</td>){7})</tr>")
TIME = re.compile(r"<td>(\d{2}:\d{2})</td>")
SELECTED = re.compile(r'<option value="(\d+)" selected>\s*([^<]+)')


def log(text: str = "") -> None:
    print(text, flush=True)


if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")


# ---- data -------------------------------------------------------------------------------------------------------

def parse_page(path: Path) -> tuple[list, dict[int, list[str]]]:
    """The page's selected options (city, month) and its rows: day -> [imsak, fajr, sunrise, dhuhr, asr, maghrib, isha]."""
    text = path.read_bytes().decode("cp1252", errors="replace")
    return SELECTED.findall(text)[-2:], {int(m.group(1)): TIME.findall(m.group(2)) for m in ROW.finditer(text)}


def load_town(town: str) -> dict[datetime.date, list[str]]:
    """date -> the six printed times (Fajr ... Isha); every Imsak checked to be Fajr - 20."""
    rows = {}
    for path in sorted((DATA / town).glob(f"{town}_{YEAR}-*.html")):
        month = int(path.stem[-2:])
        selected, page = parse_page(path)
        assert selected and int(selected[-1][0]) == month, (path, selected)
        for day, times in page.items():
            imsak, fajr = (int(t[:2]) * 60 + int(t[3:]) for t in times[:2])
            assert fajr - imsak == 20, (path, day)
            rows[datetime.date(YEAR, month, day)] = times[1:]
    return rows


def published(day: datetime.date, event: str, hhmm: str) -> int:
    """A printed time as epoch minutes; an Isha printed before noon is after midnight."""
    d = day + datetime.timedelta(days=1) if event == "ISHA" and int(hhmm[:2]) < 12 else day
    return common.local_to_epoch(TZ, d, hhmm) // 60


# ---- the profile and the engine ------------------------------------------------------------------------------------

def method_of(p: dict) -> dict:
    return common.method_json(
        id="apbif", fajr={"type": "angle", "degrees": p["fajr"]}, isha={"type": "angle", "degrees": p["isha"]},
        offsets={e: v for e, v in p["offsets"].items() if v}, rounding=dict(p["rounding"]),
        horizon={"sunrise": {"type": "fixed", "altitudeDeg": p["horizon"]}, "sunset": {"type": "fixed", "altitudeDeg": p["horizon"]}},
        asrShadow=p["factor"], asrSun=p["asr_sun"],
        sunEvaluation={"ASR": p["asr_eval"]} if p["asr_eval"] != "EVENT_INSTANT" else {},
        highLatitude={"type": "middle_of_night", "ramped": False},
        evidence={"kind": "PUBLISHED", "source": "APBIF's methodology page and its 2026 tables"},
    )


def with_(p: dict, **changes) -> dict:
    q = {**p, "offsets": dict(p["offsets"]), "rounding": dict(p["rounding"])}
    for k, v in changes.items():
        if k in ("offsets", "rounding"):
            q[k].update(v)
        else:
            q[k] = v
    return q


def day_times(m: dict, town: str, day: datetime.date, year: int) -> dict:
    """The reference's day computed at [year]'s date, its instants moved to [day] (the same UTC time of day)."""
    lat, lng = TOWNS[town]
    other = day.replace(year=year)
    out = common.compute(common.case(m, lat, lng, TZ, other, country="FR"))
    shift = 86400 * (day - other).days
    return {e: (None if raw is None else raw + shift, None if minute is None else minute + shift, status)
            for e, (raw, minute, status) in out.items()}


def unrounded(m: dict, town: str, rows: dict, year: int) -> list[tuple]:
    """(day, event, unrounded instant, status, published minute) for every printed time."""
    out = []
    for day, times in sorted(rows.items()):
        res = day_times(m, town, day, year)
        for k, e in enumerate(EVENTS):
            out.append((day, e, res[e][0], res[e][2], published(day, e, times[k])))
    return out


def counted(e: str, status: str) -> bool:
    """Fajr and Isha on absent nights are the high-latitude rule's, scored apart (stage 5)."""
    return not (e in ("FAJR", "ISHA") and status == "ESTIMATED")


def clearly(best: int, default: int) -> bool:
    return best > default * CLEARLY and best >= default + MIN_GAIN


def pick(cands: list[tuple], default_key) -> tuple:
    best = max(cands, key=lambda c: c[0])
    default = next(c for c in cands if c[1] == default_key)
    return best if clearly(best[0], default[0]) else default


def rounding_offset(times: list[tuple], e: str, p: dict, offsets=range(-2, 3)) -> tuple:
    """Stage 1: (windows, (mode, offset)) for one time, from the unrounded instants with p's offset included."""
    cands = []
    for mode in MODES:
        for k in offsets:
            n = sum(1 for _, ev, t, st, pb in times if ev == e and counted(e, st) and t is not None
                    and ref.round_instant(t + 60 * k, mode) // 60 == pb)
            cands.append((n, (mode, p["offsets"][e] + k)))
    return pick(cands, (p["rounding"][e], p["offsets"][e])), cands


# ---- windows with the reference's own primitives (diagnostics) -----------------------------------------------------

def best_cover(intervals, lo, hi, prefer):
    """(count, value): the most half-open intervals covering one value within [lo, hi]; the preferred value where it lies
    in a best plateau, else the middle of the best plateau nearest it."""
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
    a, b = min(plateaus, key=lambda q: min(abs(q[0] - prefer), abs(q[1] - prefer)))
    return best, (a + b) / 2


def sun_on(day: datetime.date, year: int):
    other = day.replace(year=year)
    cyc = ref.Cycle(other)  # U = D in France
    return cyc, ref.SunFunction(ref.SUN_MODELS["MEEUS"], "EVENT_INSTANT", cyc.jd0), 86400 * (day - other).days


def dhuhr_longitude(rows: dict, town: str, year: int, mode: str, offset: int) -> tuple:
    lat0, lng0 = TOWNS[town]
    lo, hi = WINDOW[mode]
    ivs = []
    for day, times in rows.items():
        cyc, sun, shift = sun_on(day, year)
        t = cyc.instant(ref.transit(cyc, sun, lng0)) + shift + 60 * offset
        pb = published(day, "DHUHR", times[2]) * 60
        ivs.append((lng0 + (t - pb - hi) / 240, lng0 + (t - pb - lo) / 240))
    return best_cover(ivs, lng0 - 0.1, lng0 + 0.1, lng0)


def horizon_scan(rows: dict, town: str, year: int, p: dict) -> tuple:
    """Each fixed altitude's windows for sunrise and Maghrib with p's offsets and roundings: the best of each, and -1's."""
    lat, lng = TOWNS[town]
    scores = []
    for i in range(0, 61):
        alt = round(-0.80 - 0.01 * i, 2)
        n_sr = n_mg = 0
        for day, times in rows.items():
            cyc, sun, shift = sun_on(day, year)
            t0 = ref.transit(cyc, sun, lng)
            sr = cyc.instant(ref.event(cyc, sun, lat, lng, alt, -1, t0)) + shift + 60 * p["offsets"]["SUNRISE"]
            ss = cyc.instant(ref.event(cyc, sun, lat, lng, alt, 1, t0)) + shift + 60 * p["offsets"]["MAGHRIB"]
            n_sr += ref.round_instant(sr, p["rounding"]["SUNRISE"]) // 60 == published(day, "SUNRISE", times[1])
            n_mg += ref.round_instant(ss, p["rounding"]["MAGHRIB"]) // 60 == published(day, "MAGHRIB", times[4])
        scores.append((alt, n_sr, n_mg))
    best_sr = max(scores, key=lambda s: (s[1], -abs(s[0] - p["horizon"])))
    best_mg = max(scores, key=lambda s: (s[2], -abs(s[0] - p["horizon"])))
    at = next(s for s in scores if abs(s[0] - p["horizon"]) < 1e-9)
    return best_sr, best_mg, at


def angle_windows(rows: dict, town: str, year: int, event: str, p: dict, mode: str, keep) -> tuple:
    lat, lng = TOWNS[town]
    k = EVENTS.index(event)
    lo, hi = WINDOW[mode]
    ivs = []
    for day, times in rows.items():
        if not keep(day):
            continue
        cyc, sun, shift = sun_on(day, year)
        pb = published(day, event, times[k]) * 60 - shift - 60 * p["offsets"][event]
        a = -ref.altitude(cyc, sun, lat, lng, cyc.hours(pb + lo))
        b = -ref.altitude(cyc, sun, lat, lng, cyc.hours(pb + hi))
        ivs.append((min(a, b), max(a, b)))
    n, angle = best_cover(ivs, p[event.lower()] - 2, p[event.lower()] + 2, p[event.lower()])
    return n, angle, len(ivs)


# ---- the stages ---------------------------------------------------------------------------------------------------

def fit(rows: dict, year: int, say=lambda *_: None) -> tuple[dict, dict]:
    """Stages 1, 3 and 4 at the reference year on the fitting town's rows; returns the profile and the stage facts."""
    p = with_(PRESET)
    facts = {}
    times = unrounded(method_of(p), FIT_TOWN, rows, year)
    # Stage 1: rounding and offset of the four times the methodology gives no angle for.
    for e in ("SUNRISE", "DHUHR", "MAGHRIB"):
        (n, (mode, off)), cands = rounding_offset(times, e, p)
        default = next(c[0] for c in cands if c[1] == (p["rounding"][e], p["offsets"][e]))
        p["rounding"][e], p["offsets"][e] = mode, off
        facts[e] = f"{mode} {off:+d}: {n} windows (the preset's {default})"
    n_lng, lng = dhuhr_longitude(rows, FIT_TOWN, year, p["rounding"]["DHUHR"], p["offsets"]["DHUHR"])
    facts["longitude"] = f"{lng:.4f} ({n_lng} windows; the town point {TOWNS[FIT_TOWN][1]:.4f})"
    # Stage 3: Fajr and Isha, rounding and angle.
    est = {d for d, e, t, st, pb in times if e == "FAJR" and st == "ESTIMATED"}
    for e in ("FAJR", "ISHA"):
        (n, (mode, off)), cands = rounding_offset(times, e, p, offsets=(0,))
        p["rounding"][e] = mode
        nw, angle, total = angle_windows(rows, FIT_TOWN, year, e, p, mode, lambda d: d not in est)
        facts[e] = f"{mode} {p['offsets'][e]:+d} at {p[e.lower()]} deg: {n} windows; the windows' own angle {angle:.3f} ({nw} of {total})"
    # Stage 4: Asr.
    cands = []
    for asr_sun in ("GEOMETRIC", "APPARENT"):
        for ev in ASR_EVALS:
            q = with_(p, asr_sun=asr_sun, asr_eval=ev)
            t_asr = [row for row in unrounded(method_of(q), FIT_TOWN, rows, year) if row[1] == "ASR"]
            for mode in MODES:
                for k in (-1, 0, 1):
                    n = sum(1 for _, _, t, _, pb in t_asr if ref.round_instant(t + 60 * k, mode) // 60 == pb)
                    cands.append((n, (asr_sun, ev, mode, p["offsets"]["ASR"] + k)))
    n, (asr_sun, ev, mode, off) = pick(cands, (p["asr_sun"], p["asr_eval"], p["rounding"]["ASR"], p["offsets"]["ASR"]))
    default = next(c[0] for c in cands if c[1] == (p["asr_sun"], p["asr_eval"], p["rounding"]["ASR"], p["offsets"]["ASR"]))
    geometric = max(c for c in cands if c[1][0] == "GEOMETRIC")
    p.update(asr_sun=asr_sun, asr_eval=ev)
    p["rounding"]["ASR"], p["offsets"]["ASR"] = mode, off
    facts["ASR"] = (f"{asr_sun} {ev} {mode} {off:+d}: {n} windows (the preset's {default}; the best geometric "
                    f"{geometric[1][1]} {geometric[1][2]} {geometric[1][3]:+d}: {geometric[0]})")
    for key in ("FAJR", "SUNRISE", "DHUHR", "longitude", "ASR", "MAGHRIB", "ISHA"):
        say(f"    {key.lower():9s} {facts[key]}")
    return p, facts


# ---- scores -------------------------------------------------------------------------------------------------------

def score(p: dict, town: str, rows: dict, year: int = YEAR, months=None) -> tuple[common.Score, list, list]:
    """The app's minutes (common.compute, the actual year) against the rows; absent-night Fajr kept apart too."""
    sc, misses, absent = common.Score(), [], []
    m = method_of(p)
    for day, times in sorted(rows.items()):
        if months is not None and day.month not in months:
            continue
        res = day_times(m, town, day, year)
        for k, e in enumerate(EVENTS):
            got, pb = res[e][1] // 60, published(day, e, times[k])
            sc.add(e, day, got, pb)
            if e == "FAJR" and res[e][2] == "ESTIMATED":
                absent.append((day, got - pb))
            elif res[e][2] == "ESTIMATED":
                misses.append((day, e, "ESTIMATED " + str(got - pb)))
    return sc, misses, absent


def merge(into: common.Score, sc: common.Score) -> None:
    for e in EVENTS:
        into.exact[e] += sc.exact[e]
        into.within1[e] += sc.within1[e]
        into.total[e] += sc.total[e]
        for k in range(12):
            into.months[e][k] += sc.months[e][k]
    into.worst = max(into.worst, sc.worst)
    into.misses += sc.misses


def shares(sc: common.Score) -> str:
    ex, w1 = sc.shares(), sc.within1_shares()
    return " ".join(f"{e[:4]} {100 * ex[e]:5.1f}/{100 * w1[e]:5.1f}" for e in EVENTS) + f"  max {sc.worst}"


def kotlin(p: dict) -> str:
    """The changed fields of EuropeanPresets.APBIF."""
    changed = []
    if p["asr_sun"] != PRESET["asr_sun"]:
        changed.append(f"asrSun = AsrSun.{p['asr_sun']}")
    if p["asr_eval"] != PRESET["asr_eval"]:
        changed.append(f"sunEvaluation = mapOf(TimeEvent.ASR to SunEvaluation.{p['asr_eval']})")
    if p["rounding"] != PRESET["rounding"]:
        extra = ", ".join(f"TimeEvent.{e} to Rounding.{m}" for e, m in p["rounding"].items() if m != "NEAREST")
        changed.append(f"rounding = Rounding.ALL_NEAREST + mapOf({extra})")
    if p["offsets"] != PRESET["offsets"]:
        changed.append("offsets = mapOf(" + ", ".join(f"TimeEvent.{e} to {v}" for e, v in p["offsets"].items() if v) + ")")
    return "; ".join(changed) or "(no change)"


def main() -> int:
    started = time.time()
    towns = {t: load_town(t) for t in TOWNS}
    rows = towns[FIT_TOWN]
    log("apbif: " + ", ".join(f"{t} {len(r)} days" for t, r in towns.items()) + " of APBIF's 2026 tables (Imsak = Fajr - 20 on all)")

    # The table is perpetual: the Wayback Machine's 2025 Paris months against 2026.
    for path in sorted((DATA / "wayback").glob("horaires_2025*.html")):
        selected, page = parse_page(path)
        if not page:
            log(f"  {path.name}: no table (the site's bot check)")
            continue
        month = int(selected[-1][0])
        same = sum(1 for day, t in page.items() if t[1:] == rows[datetime.date(YEAR, month, day)])
        log(f"  {path.name}: {selected[-2][1].strip()} {month:02d}/2025, {same} of {len(page)} rows equal to 2026's on all columns")

    # The reference year (E5).
    m0 = method_of(PRESET)
    totals = []
    for year in YEARS:
        times = unrounded(m0, FIT_TOWN, rows, year)
        n = sum(1 for d, e, t, st, pb in times if counted(e, st) and ref.round_instant(t, PRESET["rounding"][e]) // 60 == pb)
        totals.append((n, year))
    ref_year = max(totals)[1]
    log("reference year (the preset at Paris, windows of the six times): "
        + ", ".join(f"{y} {n}" for n, y in sorted(totals, reverse=True)[:6]) + f", ... {YEAR} {dict((y, n) for n, y in totals)[YEAR]}")

    # Stages at the reference year, on all months and on each half.
    halves = {"odd": [m for m in range(1, 13) if m % 2], "even": [m for m in range(1, 13) if not m % 2]}
    fitted = {}
    for name, months in halves.items():
        log(f"stages at {ref_year} on Paris's {name} months:")
        fitted[name], _ = fit({d: v for d, v in rows.items() if d.month in months}, ref_year, log)
    log(f"stages at {ref_year} on all of Paris's months:")
    final, facts = fit(rows, ref_year, log)

    # Stage 2 (diagnostic): each town's own horizon at the reference year.
    log(f"stage 2, sunrise and sunset horizons at {ref_year} (best fixed altitude: windows; at {PRESET['horizon']}):")
    for town, trows in towns.items():
        b_sr, b_mg, at = horizon_scan(trows, town, ref_year, final)
        log(f"    {town:10s} sunrise {b_sr[0]:+.2f}: {b_sr[1]} ({at[1]}); Maghrib {b_mg[0]:+.2f}: {b_mg[2]} ({at[2]}) of {len(trows)}")
    log(f"    the angles at each town ({ref_year}, NEAREST, the windows' own angle, nights with the angle):")
    m_final = method_of(final)
    for town, trows in towns.items():
        absent = {d for d in trows if day_times(m_final, town, d, ref_year)["FAJR"][2] == "ESTIMATED"}
        fa = angle_windows(trows, town, ref_year, "FAJR", final, "NEAREST", lambda d: d not in absent)
        ia = angle_windows(trows, town, ref_year, "ISHA", final, "NEAREST", lambda d: True)
        log(f"    {town:10s} Fajr {fa[1]:.3f} ({fa[0]} of {fa[2]}), Isha {ia[1]:.3f} ({ia[0]} of {ia[2]})")
    log(f"  ({time.time() - started:.0f} s)")

    # Held out: the month split at Paris, and the four other towns, computed for 2026 (A8).
    held = common.Score()
    for name, other in (("odd", "even"), ("even", "odd")):
        sc, _, _ = score(fitted[other], FIT_TOWN, rows, months=halves[name])
        merge(held, sc)
    log(f"held out, Paris by month split, computed for {YEAR} (exact / within 1, %):")
    log("    " + shares(held))
    log(f"final profile computed for {YEAR} (as the app shows):")
    for town, trows in towns.items():
        sc, est, absent = score(final, town, trows)
        tag = "in-sample" if town == FIT_TOWN else "held out"
        log(f"    {town:10s} {tag:9s} {shares(sc)}")
        if absent:
            log(f"      absent nights: {len(absent)}, Fajr exact {sum(1 for _, x in absent if x == 0)}, within 1 {sum(1 for _, x in absent if abs(x) <= 1)}"
                f" ({absent[0][0]} to {absent[-1][0]})")
        if est:
            log(f"      other ESTIMATED times: {est[:6]}")
        far = sorted(sc.misses, key=lambda x: -abs(x[2]))
        if far:
            log("      beyond 1 min: " + ", ".join(f"{d} {e[:4]} {x:+d}" for e, d, x in far[:8]))
    sc_ref, _, _ = score(final, FIT_TOWN, rows, year=ref_year)
    log(f"final profile at the reference year {ref_year}, Paris: {shares(sc_ref)}")
    pre, _, _ = score(PRESET, FIT_TOWN, rows)
    log(f"the preset before this fit, Paris, {YEAR}: {shares(pre)}")
    log(f"proposed Kotlin change (EuropeanPresets.APBIF): {kotlin(final)}")
    log(f"done in {time.time() - started:.0f} s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
