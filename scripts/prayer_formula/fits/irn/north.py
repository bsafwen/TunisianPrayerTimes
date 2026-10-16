"""
IRN's tables from 63.56 deg N (plan 7.4 "From 63.56 deg N", README H7 and H8), as the 2026 tables show them; the engine's H8
does not do all of this yet (fits/irn/README-part.md proposes the change). Used by fit.py to read each entry's season
days from its table and to score the proposed rules (the reference engine scores the current ones).

All instants are epoch seconds, before IRN's offsets (sunrise - 2, Dhuhr + 5 or + 10, Maghrib + 5) and its rounding
down to the minute. T is the transit with the sun at 12:00 local time (DAILY_LOCAL_NOON); the real sunrise and sunset
use that sun at -0.8333 deg; the real Fajr and Isha are 16 deg and 15 deg with the declination at the event and the
equation of time at local noon (EVENT_DECLINATION); Mecca's offsets (21.4225 N, 39.8262 E) are its sunrise, sunset and
18 deg Fajr and Isha from its transit, by the same conventions with the sun taken at 12:00 Norwegian time (not
Mecca's own noon: its sunrise and sunset then fit Svalbard's year of Mecca days on 364 and 359 days of 365, against
357 and 351), added to T.

- On every day: sunrise no earlier than T - 8 h, sunset no later than T + 8 h; Fajr no earlier than T - 9 h, Isha no
  later than T + 9 h (H7's caps at solar midnight -/+ 3 h, with the midnight 12 h from the day's own transit, not the
  night's lowest point: about 8 s apart in spring, enough to move one capped minute in seven).
- Summer season (every entry but Svalbard; days a < b, a the last real day of spring, b the first real day of autumn):
  sunrise and sunset ramp from the real values of day a to Mecca's of day a + 7, Mecca's until b - 7, and back to the
  real values of day b; Fajr and Isha ramp from the capped real values of a + 7 to Mecca's of a + 14, Mecca's until
  b - 15, back to the capped real values of b - 8. Each ramp is a straight line in UTC time of day between its two
  end days (as H7's southern Isha line is).
- Winter, each half on its own (the January half belongs to the previous winter): MECCA (s the last real day of
  autumn, e the first real day of spring): all four ramp over 30 days to and from Mecca's day; FLOOR (the entries
  without the Mecca winter): sunset no earlier than T + 2 h and sunrise no later than T - 2 h from day f to day g
  (a polar-night day counts as sunrise and sunset at T).
- Svalbard: Mecca's day all year.
- Asr: min(max(the shadow Asr, (T + sunset) / 2), sunset - 1 h), sunset being Maghrib's base after all of the above.
"""

from __future__ import annotations

import datetime

import common
import modes

ref = common.ref
TZ = "Europe/Oslo"
MECCA = (21.4225, 39.8262)
MODEL = ref.SUN_MODELS["MEEUS"]
HOUR = 3600.0
CAP = 8 * HOUR
FLOOR = 2 * HOUR
SUMMER_RAMP = 7
WINTER_RAMP = 30
EVENTS = common.EVENTS


def _events(day: datetime.date, lat: float, lng: float, zone_offset: int) -> tuple[float, dict]:
    cyc = ref.Cycle(day)
    noon = MODEL(cyc.jd0 + (12 * 3600 - zone_offset) / 86400)
    fixed = lambda jd: noon
    dev = lambda jd: (MODEL(jd)[0], noon[1], noon[2])
    t0 = ref.transit(cyc, fixed, lng)

    def ev(sun, alt, side):
        h = ref.event(cyc, sun, lat, lng, alt, side, t0)
        return None if h is None else cyc.instant(h)
    z = abs(lat - noon[0])
    out = {"rise": ev(fixed, -0.8333, -1), "set": ev(fixed, -0.8333, 1), "f16": ev(dev, -16, -1), "i15": ev(dev, -15, 1),
           "f18": ev(dev, -18, -1), "i18": ev(dev, -18, 1), "noon_alt": 90 - z}
    for k in (1, 2):
        out[f"asr{k}"] = None if z >= 90 else ev(fixed, ref.atand(1 / (k + ref.tand(z))), 1)
    return cyc.instant(t0), out


class Entry:
    """One entry's unrounded instants by day number n of a year (n may run past the year's ends)."""

    def __init__(self, lat: float, lng: float, year: int):
        self.lat, self.lng, self.year = lat, lng, year
        self._days: dict = {}
        self._mecca: dict = {}

    def date(self, n: int) -> datetime.date:
        return datetime.date(self.year, 1, 1) + datetime.timedelta(days=n - 1)

    def day(self, n: int) -> dict:
        if n not in self._days:
            day = self.date(n)
            offset = common.utc_offset(TZ, day)
            T, e = _events(day, self.lat, self.lng, offset)
            if day not in self._mecca:
                # Mecca's day with IRN's own sun: taken at 12:00 Norwegian time, as for the Norwegian entries
                mt, m = _events(day, MECCA[0], MECCA[1], offset)
                self._mecca[day] = {k: m[k] - mt for k in ("rise", "set", "f18", "i18")}
            m = self._mecca[day]
            e.update({"T": T, "Mrise": T + m["rise"], "Mset": T + m["set"], "Mfajr": T + m["f18"], "Misha": T + m["i18"],
                      "mid0": (day - datetime.date(1970, 1, 1)).days * 86400, "date": day})
            self._days[n] = e
        return self._days[n]

    def lowest(self, n: int) -> float:
        """Solar midnight after day n."""
        return (self.day(n)["T"] + self.day(n + 1)["T"]) / 2

    def polar_night(self, n: int) -> bool:
        d = self.day(n)
        return d["rise"] is None and d["set"] is None and d["noon_alt"] < -0.8333

    def sun_event(self, k: str, n: int, capped: bool = True) -> float:
        """The real sunrise or sunset (polar night: T), capped at T -/+ 8 h unless [capped] is False and it exists."""
        d = self.day(n)
        v = d[k]
        if v is None:
            return d["T"] if self.polar_night(n) else d["T"] + (CAP if k == "set" else -CAP)
        if not capped:
            return v
        return min(v, d["T"] + CAP) if k == "set" else max(v, d["T"] - CAP)

    def twilight(self, k: str, n: int) -> float:
        """The real Fajr (16 deg) or Isha (15 deg) under its cap at the day's own transit -/+ 9 h (solar midnight
        -/+ 3 h, the midnight taken 12 h from the day's transit)."""
        d = self.day(n)
        if k == "fajr":
            cap = d["T"] - 9 * HOUR
            v = d["f16"]
            return cap if v is None or v < cap else v
        cap = d["T"] + 9 * HOUR
        v = d["i15"]
        return cap if v is None or v > cap else v

    def mecca(self, k: str, n: int) -> float:
        return self.day(n)["M" + k]


def _line(entry: Entry, n: int, n0: int, y0: float, n1: int, y1: float) -> float:
    """The straight line in UTC time of day from (n0, y0) to (n1, y1), on day n."""
    t0 = y0 - entry.day(n0)["mid0"]
    t1 = y1 - entry.day(n1)["mid0"]
    return entry.day(n)["mid0"] + t0 + (t1 - t0) * (n - n0) / (n1 - n0)


def dhuhr_extra(day: datetime.date, start=(3, 21), end=(9, 20)) -> int:
    return 5 if start <= (day.month, day.day) <= end else 0


def instants(entry: Entry, n: int, seasons: dict, svalbard: bool = False) -> dict[str, float | None]:
    """The six unrounded times of day n (IRN's offsets included), and Asr with factor 2 as ASR2."""
    d = entry.day(n)
    out = {"rise": entry.sun_event("rise", n), "set": entry.sun_event("set", n),
           "fajr": entry.twilight("fajr", n), "isha": entry.twilight("isha", n)}
    mec = {k: entry.mecca(k, n) for k in out}
    if svalbard:
        out = dict(mec)
    else:
        summer = seasons.get("summer")
        if summer:
            a, b = summer
            for k in ("rise", "set"):
                if a < n < a + SUMMER_RAMP:
                    out[k] = _line(entry, n, a, entry.sun_event(k, a, capped=False), a + SUMMER_RAMP, entry.mecca(k, a + SUMMER_RAMP))
                elif a + SUMMER_RAMP <= n <= b - SUMMER_RAMP:
                    out[k] = mec[k]
                elif b - SUMMER_RAMP < n < b:
                    out[k] = _line(entry, n, b - SUMMER_RAMP, entry.mecca(k, b - SUMMER_RAMP), b, entry.sun_event(k, b, capped=False))
            for k in ("fajr", "isha"):
                a2, b2 = a + SUMMER_RAMP, b - SUMMER_RAMP - 1
                if a2 < n < a2 + SUMMER_RAMP:
                    out[k] = _line(entry, n, a2, entry.twilight(k, a2), a2 + SUMMER_RAMP, entry.mecca(k, a2 + SUMMER_RAMP))
                elif a2 + SUMMER_RAMP <= n <= b2 - SUMMER_RAMP:
                    out[k] = mec[k]
                elif b2 - SUMMER_RAMP < n < b2:
                    out[k] = _line(entry, n, b2 - SUMMER_RAMP, entry.mecca(k, b2 - SUMMER_RAMP), b2, entry.twilight(k, b2))
        half = seasons.get("spring" if n <= 181 else "autumn")
        if half:
            kind, x, y = half
            if kind == "MECCA":                 # autumn: x = s, the last real day; spring: y = e, the first real day
                for k in ("rise", "set", "fajr", "isha"):
                    real = (lambda m, k=k: entry.sun_event(k, m) if k in ("rise", "set") else entry.twilight(k, m))
                    if n > 181:
                        s = x
                        if s < n < s + WINTER_RAMP:
                            out[k] = _line(entry, n, s, real(s), s + WINTER_RAMP, entry.mecca(k, s + WINTER_RAMP))
                        elif n >= s + WINTER_RAMP:
                            out[k] = mec[k]
                    else:
                        e = y
                        if n <= e - WINTER_RAMP:
                            out[k] = mec[k]
                        elif e - WINTER_RAMP < n < e:
                            out[k] = _line(entry, n, e - WINTER_RAMP, entry.mecca(k, e - WINTER_RAMP), e, real(e))
            elif kind == "FLOOR":               # x .. y the floor days of this half
                if x <= n <= y:
                    out["set"] = max(out["set"], d["T"] + FLOOR)
                    out["rise"] = min(out["rise"], d["T"] - FLOOR)
    out["set"] = min(out["set"], d["T"] + CAP)
    out["rise"] = max(out["rise"], d["T"] - CAP)
    base = out["set"]
    asr = {}
    for k in (1, 2):
        shadow = d[f"asr{k}"]
        mid = (d["T"] + base) / 2
        asr[k] = min(mid if shadow is None else max(shadow, mid), base - HOUR)
    return {"FAJR": out["fajr"], "SUNRISE": out["rise"] - 120, "DHUHR": d["T"] + 60 * (5 + dhuhr_extra(d["date"])),
            "ASR": asr[1], "MAGHRIB": base + 300, "ISHA": out["isha"], "ASR2": asr[2]}


def minutes(entry: Entry, n: int, seasons: dict, svalbard: bool = False) -> dict[str, int]:
    return {k: ref.round_instant(v, "DOWN") for k, v in instants(entry, n, seasons, svalbard).items()}


# ---- reading an entry's season days from its table -------------------------------------------------------------------

COLUMNS = {"FAJR": "fajr", "SUNRISE": "fajr_endtime", "DHUHR": "duhr", "ASR": "shadow_1x", "MAGHRIB": "maghrib", "ISHA": "isha"}


def table_minute(entry: Entry, rows: list, n: int, col: str) -> int:
    return common.local_to_epoch(TZ, entry.date(n), rows[n - 1][col][:5])


def _hits(entry: Entry, rows: list, n: int, seasons: dict, events=("MAGHRIB", "FAJR", "ISHA", "SUNRISE")) -> int:
    p = minutes(entry, n, seasons)
    hits = 0
    for e in events:
        t = table_minute(entry, rows, n, COLUMNS[e])
        hits += any(p[e] == t + k * 86400 for k in (-1, 0, 1))
    return hits


def read_seasons(entry: Entry, rows: list, svalbard: bool) -> dict:
    """The season days that reproduce the table best: summer (a, b), and each winter half (MECCA with its anchor, or
    FLOOR with its days, or none)."""
    if svalbard:
        return {}
    days_in = len(rows)

    def best(cands, rng, make):
        top = None
        for x in cands:
            score = sum(_hits(entry, rows, n, make(x)) for n in rng(x) if 1 <= n <= days_in)
            if top is None or score > top[0]:
                top = (score, x)
        return top[1]
    a = best(range(80, 145), lambda x: range(x - 2, x + 2 * SUMMER_RAMP + 3), lambda x: {"summer": (x, 400)})
    b = best(range(200, 270), lambda x: range(x - 2 * SUMMER_RAMP - 3, x + 3), lambda x: {"summer": (-100, x)})
    seasons = {"summer": (a, b)}
    autumn_days = range(274, days_in + 1)
    spring_days = range(1, 75)
    cands_a = [("MECCA", s, None) for s in range(280, 335)] + [("FLOOR", f, days_in) for f in range(300, days_in + 1)] + [None]
    cands_s = [("MECCA", None, e) for e in range(25, 70)] + [("FLOOR", 1, g) for g in range(1, 60)] + [None]
    top = None
    for c in cands_a:
        sz = dict(seasons, autumn=c)
        score = sum(_hits(entry, rows, n, sz) for n in autumn_days)
        if top is None or score > top[0]:
            top = (score, c)
    seasons["autumn"] = top[1]
    top = None
    for c in cands_s:
        sz = dict(seasons, spring=c)
        score = sum(_hits(entry, rows, n, sz) for n in spring_days)
        if top is None or score > top[0]:
            top = (score, c)
    seasons["spring"] = top[1]
    # An autumn FLOOR half starts on the first day the floor moves a time (any earlier start moves nothing); a spring
    # half keeps its fitted last day (the floor ends by IRN's own count, not where the real sunset passes T + 2 h).
    c = seasons["autumn"]
    if c and c[0] == "FLOOR":
        moved = [n for n in autumn_days if entry.sun_event("set", n) < entry.day(n)["T"] + FLOOR]
        seasons["autumn"] = ("FLOOR", min(moved), days_in) if moved else None
    c = seasons["spring"]
    if c and c[0] == "FLOOR" and not any(entry.sun_event("set", n) < entry.day(n)["T"] + FLOOR for n in range(1, c[2] + 1)):
        seasons["spring"] = None
    return seasons
