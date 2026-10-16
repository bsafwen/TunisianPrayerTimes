#!/usr/bin/env python3
"""
Fits `ps_gaza_awqaf`, the Gaza Strip's Ministry of Awqaf and Religious Affairs (plan 8.1, 8.2, 8.7, 8.8, A17;
milestone AM10, 9.4), and writes its official-zone asset, android-app/app/src/main/assets/official-zones/ps_gaza_awqaf.json.

The ministry publishes one table for the whole Strip: its decree of 2021-10-05 unifies the adhan in every mosque, its
monthly tables are headed «محافظات غزة», and its radio posts and website widget name no city (8.8). So the asset has
one `NATIONAL` zone, «محافظات غزة», at Gaza City's point; the profile's minutes, angles and rounding are fitted here.

Data, read at run time from test-data-local and never copied (D13, A7), every row at UTC+3 (summer time, checked with
tzdata for Asia/Gaza on each date):
- research-2026-10-08/PS/ps/gaza_1444_dhulhijja.txt: the ministry's Dhu al-Hijja 1444 table, 30 days (2023-06-19 to
  07-18), hand-transcribed from its Telegram image.
- research-2026-10-08/PS/ps/gaza_2026_radio.txt: the ministry's Quran radio's daily posts, 22 days (2026-05-19 to 06-09).
- research-2026-10-08/PS/ps/gaza_widget_20261008.txt: the palwakf.ps footer widget on 2026-10-08, five times (its
  sunrise came from another site and is not used).

Stages (9.4), by windows (a published minute bounds the unrounded time to a half-open minute; a window widened by one
minute either side counts the times within 1 minute, and the value covered by the most of those, then by the most
exact windows, wins), with a fast special-purpose model of the reference engine:
1. Dhuhr: the rounding and whole-minute offset for each sun model, at Gaza City's point (53 days of May to July and
   one October day hardly fix a point, and the 2023 table holds its Dhuhr on one minute for up to 8 days).
2. Fajr and Isha: the angle and the rounding; Egypt's 19.5 and 17.5 deg (8.2) where the windows hold them.
3. Sunrise and Maghrib: the standard horizon with a whole-minute offset and the rounding; Asr's sun (E13) and rounding.
Held out: each source scored with the parameters fitted on the other (the 2023 table against the 2026 posts and widget,
and the reverse), through common.compute, the reference engine.

Usage: python scripts/prayer_formula/fits/ps_gaza_awqaf/fit.py
"""

from __future__ import annotations

import datetime
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import common  # noqa: E402

ref = common.ref
EVENTS = common.EVENTS
AUTHORITY = "ps_gaza_awqaf"
TZ = "Asia/Gaza"
D2R = math.pi / 180
PS = common.LOCAL_DATA / "research-2026-10-08" / "PS" / "ps"
GAZA_CITY = (31.5016, 34.4667)          # GeoNames 281133 (geonames.org, read 2026-10-15)
MODELS = ("MEEUS", "VSOP87")
ROUNDINGS = {"NEAREST": (-0.5, 0.5), "UP": (-1.0, 0.0), "DOWN": (0.0, 1.0)}
OFFSETS = {"DHUHR": (0, 1, 2), "SUNRISE": (-2, -1, 0, 1), "MAGHRIB": (0, 1, 2, 3, 4, 5), "ASR": (0, 1, 2)}
STATED = {"FAJR": 19.5, "ISHA": 17.5}   # Egypt's angles, which the research found close (8.2)
SOURCES = {"table": "gaza_awqaf_dhulhijja_1444 (Telegram image, transcribed)",
           "radio": "gaza_awqaf_quran_radio_2026 (daily posts)", "widget": "palwakf.ps widget, 2026-10-08"}
SOURCE_LINE = ("The Gaza Strip's Ministry of Awqaf: one table for the five governorates (decree of 2021-10-05), fitted to "
               "its Dhu al-Hijja 1444 table, its radio's daily posts of 2026 and its website widget")


# ------------------------------------------------------------------------------------------------------------
# Data

def load(name: str, source: str) -> list[tuple[datetime.date, dict[str, int], str]]:
    out = []
    with open(PS / name, encoding="utf-8") as fh:
        for line in fh:
            parts = line.split()
            if not parts:
                continue
            day = datetime.date.fromisoformat(parts[0])
            if common.utc_offset(TZ, day) != 10800:
                raise SystemExit(f"{name} {day}: Asia/Gaza is not UTC+3")
            times = {e: int(c[:2]) * 60 + int(c[3:]) for e, c in zip(EVENTS, parts[1:])}
            if source == "widget":
                times.pop("SUNRISE")                 # another site's sunrise, not the ministry's
            out.append((day, times, source))
    return out


def load_all():
    return (load("gaza_1444_dhulhijja.txt", "table") + load("gaza_2026_radio.txt", "radio")
            + load("gaza_widget_20261008.txt", "widget"))


# ------------------------------------------------------------------------------------------------------------
# Fast model of the reference engine (README S1-S7)

class Sun:
    __slots__ = ("a", "b", "c")

    def __init__(self, model: str, day: datetime.date):
        jd0 = ref.julian_day0(day)
        f = ref.SUN_MODELS[model]
        y0, y1, y2 = f(jd0), f(jd0 + 0.5), f(jd0 + 1.0)
        self.a = y0
        self.b = tuple(-3 * u + 4 * v - w for u, v, w in zip(y0, y1, y2))
        self.c = tuple(2 * u - 4 * v + 2 * w for u, v, w in zip(y0, y1, y2))

    def at(self, hours: float):
        x = hours / 24
        a, b, c = self.a, self.b, self.c
        return a[0] + x * (b[0] + x * c[0]), a[1] + x * (b[1] + x * c[1]), a[2] + x * (b[2] + x * c[2])


_SUNS: dict = {}


def sun_of(model: str, day: datetime.date) -> Sun:
    key = (model, day)
    if key not in _SUNS:
        _SUNS[key] = Sun(model, day)
    return _SUNS[key]


def transit(sun: Sun, lng: float) -> float:
    mean_noon = 12 - lng / 15
    t = mean_noon
    for _ in range(10):
        nxt = mean_noon - sun.at(t)[1] / 60
        if abs(nxt - t) < 0.1 / 3600:
            return nxt
        t = nxt
    return t


def event(sun: Sun, lat: float, lng: float, alt: float, side: int, t0: float) -> float:
    mean_noon = 12 - lng / 15
    sl, cl, sa = math.sin(lat * D2R), math.cos(lat * D2R), math.sin(alt * D2R)
    t = t0
    for _ in range(10):
        decl, eot, _ = sun.at(t)
        c = (sa - sl * math.sin(decl * D2R)) / (cl * math.cos(decl * D2R))
        nxt = mean_noon - eot / 60 + side * math.acos(max(-1.0, min(1.0, c))) / D2R / 15
        if abs(nxt - t) < 0.1 / 3600:
            return nxt
        t = nxt
    return t


def altitude_at(sun: Sun, lat: float, lng: float, th: float) -> float:
    decl, eot, _ = sun.at(th)
    s = (math.sin(lat * D2R) * math.sin(decl * D2R)
         + math.cos(lat * D2R) * math.cos(decl * D2R) * math.cos((th - 12 + lng / 15 + eot / 60) * 15 * D2R))
    return math.asin(max(-1.0, min(1.0, s))) / D2R


def asr_altitude(lat: float, decl_noon: float, apparent: bool) -> float:
    zenith = abs(lat - decl_noon)
    if apparent:
        h0 = 90 - zenith
        h0a = h0 + ref.bennett(h0, 1010.0, 10.0)
        aa = math.atan(1 / (1 + math.tan((90 - h0a) * D2R))) / D2R
        return aa - ref.bennett(aa, 1010.0, 10.0)
    return math.atan(1 / (1 + math.tan(zenith * D2R))) / D2R


def rounded(u: float, rnd: str) -> int:
    if rnd == "DOWN":
        return math.floor(u + 1e-6)
    if rnd == "UP":
        return math.ceil(u - 1e-6)
    return math.floor(u + 0.5)


# ------------------------------------------------------------------------------------------------------------
# Fit

def best_value(pairs):
    """pairs: per published minute, (exact window, within-1 window), each [lo, hi). The value with the most within-1
    windows, then the most exact ones: (within-1 count, exact count, the best stretches)."""
    marks = []
    for (lo, hi), (lo1, hi1) in pairs:
        marks += [(lo1, 0, 1), (hi1, 0, -1), (lo, 1, 1), (hi, 1, -1)]
    marks.sort()
    w1 = ex = 0
    best, stretches = (-1, -1), []
    for i, (x, kind, sgn) in enumerate(marks):
        if kind == 0:
            w1 += sgn
        else:
            ex += sgn
        nxt = marks[i + 1][0] if i + 1 < len(marks) else x
        if nxt <= x:
            continue
        if (w1, ex) > best:
            best, stretches = (w1, ex), [(x, nxt)]
        elif (w1, ex) == best:
            if stretches and stretches[-1][1] == x:
                stretches[-1] = (stretches[-1][0], nxt)
            else:
                stretches.append((x, nxt))
    return best[0], best[1], stretches


def counts(pairs_of_minutes) -> tuple[int, int]:
    """(within-1, exact) of (computed, published) minutes."""
    w1 = sum(abs(c - p) <= 1 for c, p in pairs_of_minutes)
    return w1, sum(c == p for c, p in pairs_of_minutes)


def fit(rows, say=None) -> dict:
    """Stages 1-3 on [rows] at Gaza City's point: the sun model and each time's parameters, by the most times within
    1 minute and then the most exact (the table holds some times for days, so many minutes cannot be exact)."""
    lat, lng = GAZA_CITY
    best_all = None
    for model in MODELS:
        events = {}
        trs = {day: transit(sun_of(model, day), lng) for day, _, _ in rows}
        best = None
        for rnd in ROUNDINGS:
            for k in OFFSETS["DHUHR"]:
                w1, ex = counts([(rounded((trs[day] + 3) * 60 + k, rnd), t["DHUHR"]) for day, t, _ in rows])
                cand = (w1, ex, -k, {"rounding": rnd, "offset": k})
                if best is None or cand[:3] > best[:3]:
                    best = cand
        events["DHUHR"] = best
        for e in ("FAJR", "ISHA"):
            best = None
            for rnd, (blo, bhi) in ROUNDINGS.items():
                pairs = []
                for day, t, _ in rows:
                    if e not in t:
                        continue
                    sun = sun_of(model, day)
                    dep = []
                    for lo, hi in ((t[e] + blo, t[e] + bhi), (t[e] + blo - 1, t[e] + bhi + 1)):
                        a1, a2 = (altitude_at(sun, lat, lng, x / 60 - 3) for x in (lo, hi))
                        dep.append((-max(a1, a2), -min(a1, a2)))
                    pairs.append(tuple(dep))
                w1, ex, st = best_value(pairs)
                stated = any(x <= STATED[e] < y for x, y in st)
                value = STATED[e] if stated else (st[(len(st) - 1) // 2][0] + st[(len(st) - 1) // 2][1]) / 2
                cand = (w1, ex, stated, {"rounding": rnd, "degrees": round(value, 3)})
                if best is None or cand[:3] > best[:3]:
                    best = cand
            events[e] = best
        for e, side in (("SUNRISE", -1), ("MAGHRIB", 1)):
            base = {day: (event(sun_of(model, day), lat, lng, -0.8333, side, trs[day]) + 3) * 60 for day, _, _ in rows}
            best = None
            for rnd in ROUNDINGS:
                for k in OFFSETS[e]:
                    w1, ex = counts([(rounded(base[day] + k, rnd), t[e]) for day, t, _ in rows if e in t])
                    cand = (w1, ex, -abs(k), {"rounding": rnd, "offset": k})
                    if best is None or cand[:3] > best[:3]:
                        best = cand
            events[e] = best
        best = None
        for apparent in (False, True):
            base = {}
            for day, _, _ in rows:
                sun = sun_of(model, day)
                base[day] = (event(sun, lat, lng, asr_altitude(lat, sun.at(trs[day])[0], apparent), 1, trs[day]) + 3) * 60
            for rnd in ROUNDINGS:
                for k in OFFSETS["ASR"]:
                    w1, ex = counts([(rounded(base[day] + k, rnd), t["ASR"]) for day, t, _ in rows])
                    cand = (w1, ex, (not apparent, -k), {"rounding": rnd, "apparent": apparent, "offset": k})
                    if best is None or cand[:3] > best[:3]:
                        best = cand
        events["ASR"] = best
        w1 = sum(v[0] for v in events.values())
        ex = sum(v[1] for v in events.values())
        cand = (w1, ex, model == "MEEUS", model, {e: (v[1], v[3]) for e, v in events.items()})
        if say:
            say(f"  {model}: within 1 min {w1}, exact {ex}; " + "; ".join(f"{e[:3]} {v[0]}/{v[1]} {v[3]}" for e, v in events.items()))
        if best_all is None or cand[:3] > best_all[:3]:
            best_all = cand
    return {"model": best_all[3], "lat": lat, "lng": lng, "events": best_all[4], "score": best_all[:2]}


def method_of(f: dict) -> dict:
    ev = f["events"]
    offsets = {e: ev[e][1]["offset"] for e in ("DHUHR", "SUNRISE", "MAGHRIB", "ASR") if ev[e][1]["offset"]}
    return common.method_json(
        id=AUTHORITY, fajr={"type": "angle", "degrees": ev["FAJR"][1]["degrees"]},
        isha={"type": "angle", "degrees": ev["ISHA"][1]["degrees"]}, offsets=offsets,
        rounding={e: ev[e][1]["rounding"] for e in EVENTS}, asrSun="APPARENT" if ev["ASR"][1]["apparent"] else "GEOMETRIC",
        sunModel=f["model"], highLatitude={"type": "angle_based"}, evidence={"kind": "FROM_TABLES", "source": "fit"})


def zone_of(f: dict) -> dict:
    computing = common.point(f["lat"], f["lng"])
    town = common.point(*GAZA_CITY)
    extra = {} if computing == town else {"morningPoint": computing, "eveningPoint": computing}
    return common.zone_json("gaza", GAZA_CITY[0], GAZA_CITY[1], **extra)


def score(rows, f: dict) -> common.Score:
    method, zone = method_of(f), zone_of(f)
    s = common.Score()
    for day, t, _ in rows:
        got = common.compute(common.case(method, GAZA_CITY[0], GAZA_CITY[1], TZ, day, zone=zone, country="PS"))
        off = common.utc_offset(TZ, day)
        for e in EVENTS:
            if e in t:
                s.add(e, day, (got[e][1] + off) // 60 % 1440, t[e])
    return s


def shares(s: common.Score) -> str:
    return " ".join(f"{e[:3]} {s.exact[e]}/{s.total[e]}" for e in EVENTS)


def describe(f: dict) -> str:
    ev = f["events"]
    return (f"{f['model']} sun at {f['lat']:.4f} N {f['lng']:.4f} E; Fajr {ev['FAJR'][1]['degrees']} deg {ev['FAJR'][1]['rounding']}, "
            f"sunrise {ev['SUNRISE'][1]['offset']:+d} {ev['SUNRISE'][1]['rounding']}, Dhuhr {ev['DHUHR'][1]['offset']:+d} "
            f"{ev['DHUHR'][1]['rounding']}, Asr {'apparent' if ev['ASR'][1]['apparent'] else 'geometric'} "
            f"{ev['ASR'][1]['offset']:+d} {ev['ASR'][1]['rounding']}, Maghrib {ev['MAGHRIB'][1]['offset']:+d} "
            f"{ev['MAGHRIB'][1]['rounding']}, Isha {ev['ISHA'][1]['degrees']} deg {ev['ISHA'][1]['rounding']}")


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    rows = load_all()
    by = {k: [r for r in rows if r[2] == k] for k in SOURCES}
    print("Rows: " + ", ".join(f"{k} {len(v)}" for k, v in by.items()))
    print("All rows:")
    final = fit(rows, print)
    print("Profile: " + describe(final))
    s_in = score(rows, final)
    print("In-sample: " + shares(s_in) + f"; worst {s_in.worst}")
    halves = {
        "table": ([r for r in rows if r[2] != "table"], by["table"]),
        "2026": (by["table"], [r for r in rows if r[2] != "table"]),
    }
    held = []
    for label, (fit_rows, score_rows) in halves.items():
        f = fit(fit_rows)
        s = score(score_rows, f)
        held.append(s)
        print(f"Held out on {label} (fitted on the other source): {describe(f)}\n  " + shares(s) + f"; worst {s.worst}")
    held_all = common.Score()
    for s in held:
        for e in EVENTS:
            held_all.exact[e] += s.exact[e]
            held_all.within1[e] += s.within1[e]
            held_all.total[e] += s.total[e]
            for m in range(12):
                held_all.months[e][m] += s.months[e][m]
        held_all.worst = max(held_all.worst, s.worst)
    print("Held out, both directions: " + shares(held_all) + f"; within 1 min {min(held_all.within1_shares().values()):.1%}")

    samples = [common.sample(SOURCES[k], min(r[0] for r in v), max(r[0] for r in v), len(v), 1) for k, v in by.items()]
    held_samples = [common.sample(SOURCES["table"] + ", scored with the parameters fitted on the 2026 rows",
                                  min(r[0] for r in by["table"]), max(r[0] for r in by["table"]), len(by["table"]), 1),
                    common.sample(SOURCES["radio"] + " and " + SOURCES["widget"] + ", scored with the parameters fitted on the 2023 table",
                                  min(r[0] for r in by["radio"]), max(r[0] for r in by["widget"]), len(by["radio"]) + len(by["widget"]), 1)]
    stats = common.fit_stats(samples, held_samples, held_all)
    zone = zone_of(final)
    asset_zone = {"id": "gaza", "nameAr": "محافظات غزة", "point": zone["point"],
                  "morningPoint": zone["morningPoint"], "eveningPoint": zone["eveningPoint"],
                  "namedPlaces": [{"centre": zone["point"], "catalogueIds": [], "areaKm": 0.0}], "fit": stats}
    path = common.write_asset(AUTHORITY, AUTHORITY, "PS", "NATIONAL", [asset_zone], fit=stats, source=SOURCE_LINE)
    print(f"Wrote {path.relative_to(common.ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
