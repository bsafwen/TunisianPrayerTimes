"""
Plan 9.5 without waiting a collector's year (owner, 2026-10-17): a mosque of the owner's dataset that follows an
official table on every collected day stands in for that table's whole year.

The owner's dataset is supporting evidence, never the truth (owner, 2026-10-11): a mosque counts only where it
reproduces the official table on the days collected from the authority itself (FOLLOW_EXACT of all their minutes,
none more than 1 min off, at least MIN_OVERLAP days), its other days are scored as a held-out body of their own
(`source` "mosque_follower", the mosque's id in the record's notes), and the official days stay the fit's. The dataset
holds each mosque's calendar of 2026 by month and day (read 2026-10-10); it is read here, never copied (A7), and
mawaqit.net is never fetched.

- `mosques(cc)`: a country's mosques with their calendars, read-only.
- `follows(...)`: whether a calendar reproduces an official table on its days.
- `score_calendar(...)`: a follower's other days scored, a day printed on a wrong clock (all six times an hour off)
  left out as a quirk day.
- `merge_held_out(fit, score, sample)`: a zone's FitStats with a further held-out body added.
- `asset_computer(...)`: an asset zone's days through the reference, as the app computes them.
- `run(...)`: the whole pass for one asset, which each fit's `mosque_year.py` calls with its official days.
"""

from __future__ import annotations

import datetime
import json
import math
import sqlite3
import time
from dataclasses import dataclass, field
from pathlib import Path

import common as C

DATASET = C.LOCAL_DATA.parent / "mosque-dataset" / "mosques.db"
YEAR = 2026                    # the calendars' year
FOLLOW_EXACT = 0.98            # a follower matches at least this share of the official minutes exactly
MIN_OVERLAP = 20               # and on at least this many official days
COLUMNS = ("fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha")


@dataclass
class Mosque:
    key: int
    id: str
    name: str
    city: str
    lat: float
    lng: float
    timezone: str
    calendar: dict = field(default_factory=dict)      # date -> {event: "HH:MM"}


def mosques(cc: str) -> list[Mosque]:
    """The dataset's mosques of country [cc] with their calendars of YEAR (an invalid date such as 29 February skipped)."""
    db = sqlite3.connect(f"file:{DATASET.as_posix()}?mode=ro", uri=True)
    out = {}
    for key, mid, name, city, lat, lng, tz in db.execute(
            "select mosque_key, id, name, city, latitude, longitude, timezone from mosques where countryCode = ?", (cc,)):
        out[key] = Mosque(key, mid, name, city or "", lat, lng, tz or "")
    for row in db.execute(
            "select d.mosque_key, d.month, d.day, " + ", ".join(f"d.{c}" for c in COLUMNS) + " from calendar_days d "
            "join mosques m on m.mosque_key = d.mosque_key where m.countryCode = ?", (cc,)):
        try:
            day = datetime.date(YEAR, row[1], row[2])
        except ValueError:
            continue
        out[row[0]].calendar[day] = dict(zip(C.EVENTS, row[3:]))
    db.close()
    return [m for m in out.values() if m.calendar]


def km(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    a = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lng2 - lng1) / 2) ** 2
    return 2 * 6371.0 * math.asin(math.sqrt(a))


def epoch_minute(zone_id: str, day: datetime.date, hhmm: str, event: str) -> int | None:
    """A calendar's clock time as UTC epoch minutes (an Isha before noon belongs to the night after)."""
    try:
        h, m = (int(x) for x in hhmm.strip()[:5].split(":"))
    except ValueError:
        return None
    on = day + datetime.timedelta(days=1) if event == "ISHA" and h < 12 else day
    return C.local_to_epoch(zone_id, on, f"{h:02d}:{m:02d}") // 60


@dataclass
class Follow:
    days: int
    exact: int
    total: int
    worst: int

    @property
    def share(self) -> float:
        return self.exact / self.total if self.total else 0.0

    @property
    def follows(self) -> bool:
        return self.days >= MIN_OVERLAP and self.share >= FOLLOW_EXACT and self.worst <= 1


def follows(mosque: Mosque, zone_id: str, official: dict) -> Follow:
    """The mosque's calendar against an official table, {date: {event: UTC epoch minute}}, on the table's days."""
    days = exact = total = worst = 0
    for day, published in official.items():
        times = mosque.calendar.get(day) if day.year == YEAR else None     # the calendars are of YEAR only
        if times is None:
            continue
        days += 1
        for e in C.EVENTS:
            mine = epoch_minute(zone_id, day, times[e], e)
            if mine is None or published.get(e) is None:
                continue
            total += 1
            diff = abs(mine - published[e])
            exact += diff == 0
            worst = max(worst, diff)
    return Follow(days, exact, total, worst)


def clock_quirk(printed: dict, computed: dict) -> bool:
    """A day printed on a wrong clock, not with wrong times (plan 8.3's quirk days): each of its times is either within
    1 min of the computed one or within 2 min of a whole number of hours off it, and at least one is hours off. The
    owner's Moroccan calendars print UTC−1 from 15 February to 13 March 2026, an hour behind the legal UTC+0 of
    Ramadan's suspension, a few days two hours off, and the mornings of the change days on the other clock; before and
    after they agree with Habous's minutes."""
    diffs = [computed[e] - printed[e] for e in C.EVENTS if printed.get(e) is not None and computed.get(e) is not None]
    hours = [d for d in diffs if abs(d) > 1]
    return bool(hours) and all(abs(d) >= 58 and abs(abs(d) - 60 * round(abs(d) / 60)) <= 2 for d in hours)


def score_calendar(compute_day, calendar: dict, score: C.Score) -> int:
    """Scores {date: {event: UTC epoch minute}} with compute_day(date) -> {event: UTC epoch minute}; returns the
    quirk days left out (clock_quirk)."""
    quirks = 0
    for day in sorted(calendar):
        computed = compute_day(day)
        if clock_quirk(calendar[day], computed):
            quirks += 1
            continue
        for e in C.EVENTS:
            if calendar[day].get(e) is not None and computed.get(e) is not None:
                score.add(e, day, computed[e], calendar[day][e])
    return quirks


def merge_held_out(fit: dict, score: C.Score, sample: dict, quirk_days: int = 0) -> dict:
    """A zone's FitStats with [score] added as a further held-out body [sample] (shares by count, months added)."""
    merged = dict(fit)
    merged["heldOut"] = list(fit.get("heldOut", [])) + [sample]
    old_days = {e: sum(fit.get("daysPerMonth", {}).get(e, [])) for e in C.EVENTS}
    merged["exact"] = {e: round((fit["exact"].get(e, 0.0) * old_days[e] + score.exact[e]) / max(1, old_days[e] + score.total[e]), 4)
                       for e in C.EVENTS}
    merged["within1"] = {e: round((fit["within1"].get(e, 0.0) * old_days[e] + score.within1[e]) / max(1, old_days[e] + score.total[e]), 4)
                         for e in C.EVENTS}
    merged["daysPerMonth"] = {e: [a + b for a, b in zip(fit.get("daysPerMonth", {}).get(e, [0] * 12), score.months[e])]
                              for e in C.EVENTS}
    merged["maxDiffMin"] = max(fit.get("maxDiffMin", 0), score.worst)
    merged["quirkDays"] = fit.get("quirkDays", 0) + quirk_days
    return merged


def passes_tier_a(fit: dict) -> bool:
    """FitStats.passesTierA for a method without Ramadan rules (plan 9.5)."""
    return bool(fit.get("heldOut")) and all(
        fit["exact"].get(e, 0) >= 0.97 and fit["within1"].get(e, 0) >= 1.0
        and len(fit["daysPerMonth"].get(e, [])) == 12 and all(d >= 7 for d in fit["daysPerMonth"][e])
        for e in C.EVENTS)


def asset_zone(z: dict) -> dict:
    """An asset's zone in the reference's zone format (the asset leaves the defaults out); its record stays out."""
    zone = C.zone_json(z["id"], z["point"]["latE5"] / 1e5, z["point"]["lngE5"] / 1e5)
    zone.update({k: v for k, v in z.items() if k != "fit"})
    return zone


def asset_computer(method: dict, zones: dict, zone_id: str, tz: str, country: str):
    """compute_day(date) -> {event: UTC epoch minute} for an asset zone (through its base zone where it has one), as
    the app computes a place the zone serves."""
    zone = asset_zone(zones[zone_id])
    base = asset_zone(zones[zone["baseZoneId"]]) if zone.get("baseZoneId") else None
    lat, lng = zone["point"]["latE5"] / 1e5, zone["point"]["lngE5"] / 1e5

    def compute_day(day: datetime.date) -> dict:
        out = C.compute(C.case(method, lat, lng, tz, day, zone=zone, base_zone=base, country=country))
        return {e: out[e][1] // 60 for e in C.EVENTS if out[e][1] is not None}
    return compute_day


def calendar_minutes(mosque: Mosque, tz: str, days) -> dict:
    """A calendar's [days] as {date: {event: UTC epoch minute}} (a day with an unreadable time left out)."""
    out = {}
    for day in days:
        minutes = {e: epoch_minute(tz, day, mosque.calendar[day][e], e) for e in C.EVENTS}
        if all(v is not None for v in minutes.values()):
            out[day] = minutes
    return out


def table_minutes(tz: str, rows: dict) -> dict:
    """Printed rows {date: [six "HH:MM"]} as {date: {event: UTC epoch minute}} (an Isha before noon is the night after)."""
    return {d: {e: epoch_minute(tz, d, t, e) for e, t in zip(C.EVENTS, times)} for d, times in rows.items()}


def run(argv: list[str], *, asset_path: Path, countries: list[str], tz: str, official: dict, compute_for,
        near: int = 3, one_table: bool = False) -> int:
    """The pass for one asset: every mosque of [countries] is tried against its [near] nearest zones of [official]
    ({zone id: {date: {event: UTC epoch minute}}}, the official days at hand, of any year) and kept for the one it
    follows best; of a zone's followers, the one with the most official days is scored on its other days of YEAR with
    compute_for(zone id) -> compute_day. With --write each such zone's record gains that year as a held-out body; the
    asset's own record is left as the fit wrote it. A record that holds a follower year already is not merged again
    (run the fit first). [one_table]: the zones are one table and whole-minute offsets (Kuwait), so the best follower's
    year, scored at its own zone, goes to every zone's record, as the fit gives every zone the same record."""
    started = time.time()
    asset = json.loads(asset_path.read_text(encoding="utf-8"))
    zones = {z["id"]: z for z in asset["zones"]}
    official = {zid: {d: t for d, t in days.items() if d.year == YEAR} for zid, days in official.items() if zid in zones}
    candidates = [zones[zid] for zid, days in official.items() if len(days) >= MIN_OVERLAP]
    mosques_ = [m for cc in countries for m in mosques(cc)]
    print(f"{asset_path.name}: {len(mosques_)} mosques ({', '.join(countries)}) in the owner's dataset; "
          f"{len(candidates)} of {len(zones)} zones have {MIN_OVERLAP} or more official days of {YEAR} "
          f"(most {max((len(v) for v in official.values()), default=0)})")
    if not candidates:
        print("no zone has enough official days to test a calendar against; nothing to do")
        return 0

    followers: dict[str, list] = {}
    near_misses = []
    for m in mosques_:
        closest = sorted(candidates, key=lambda z: km(m.lat, m.lng, z["point"]["latE5"] / 1e5, z["point"]["lngE5"] / 1e5))[:near]
        best = closest_miss = None
        for z in closest:
            f = follows(m, tz, official[z["id"]])
            if f.follows and (best is None or (f.share, f.days) > (best[1].share, best[1].days)):
                best = (z["id"], f)
            elif f.days >= MIN_OVERLAP and f.share >= 0.8 and (closest_miss is None or f.share > closest_miss[2].share):
                closest_miss = (m, z["id"], f)
        if best:
            followers.setdefault(best[0], []).append((m, best[1]))
        elif closest_miss:
            near_misses.append(closest_miss)
    print(f"{sum(len(v) for v in followers.values())} mosques follow a zone's table, at {len(followers)} zones; "
          f"{len(near_misses)} more come close (80 % exact or more)")
    for m, zid, f in sorted(near_misses, key=lambda x: -x[2].share)[:5]:
        print(f"  close, not a follower: {m.city} against {zid}: {f.days} days, {100 * f.share:.1f} % exact, worst {f.worst} min")
    if one_table and followers:
        zid, chosen = max(((zid, x) for zid, v in followers.items() for x in v), key=lambda t: (t[1][1].days, t[1][1].share))
        print(f"one table: the follower with the most official days ({chosen[0].city}, at {zid}) speaks for every zone")
        followers = {zid: [chosen]}

    total = C.Score()
    passing = quirks_all = 0
    already = []
    for zid in sorted(followers, key=lambda v: (len(v), v)):
        z = zones[zid]
        lat, lng = z["point"]["latE5"] / 1e5, z["point"]["lngE5"] / 1e5
        mosque, follow = max(followers[zid], key=lambda x: (x[1].days, x[1].share))
        own = set(official[zid])
        calendar = calendar_minutes(mosque, tz, sorted(d for d in mosque.calendar if d not in own))
        if not calendar:
            print(f"  {zid:>6} {z.get('nameAr', '')}: {len(followers[zid])} follower(s); the official days cover the "
                  f"whole calendar, nothing more to score")
            continue
        score = C.Score()
        quirks = score_calendar(compute_for(zid), calendar, score)
        for e in C.EVENTS:
            total.exact[e] += score.exact[e]
            total.within1[e] += score.within1[e]
            total.total[e] += score.total[e]
        total.worst = max(total.worst, score.worst)
        total.misses += score.misses
        quirks_all += quirks
        scored = len(calendar) - quirks
        shares = " ".join(f"{e[:2]} {100 * score.shares()[e]:.0f}" for e in C.EVENTS)
        tiers = []
        for target in (zones.values() if one_table else [z]):
            record = target.get("fit")
            if record is None:
                tiers.append(f"{target['id']}: no record, not merged")
            elif any(s.get("source") == "mosque_follower" for s in record.get("heldOut", [])):
                already.append(target["id"])
                tiers.append(f"{target['id']}: holds a follower year already, not merged")
            else:
                sample = C.sample("mosque_follower", min(calendar), max(calendar), scored, 1)
                merged = merge_held_out(record, score, sample, quirk_days=quirks)
                tier_a = passes_tier_a(merged)
                passing += tier_a
                if tier_a:
                    tiers.append(f"{target['id']}: tier A")
                if "--write" in argv:
                    target["fit"] = merged
        print(f"  {zid:>6} {z.get('nameAr', '')}: {len(followers[zid])} follower(s), scored {mosque.city} ({mosque.id}, "
              f"{km(mosque.lat, mosque.lng, lat, lng):.1f} km; {follow.days} official days, {100 * follow.share:.1f} %): "
              f"{scored} days ({quirks} quirk days left out), {shares}, worst {score.worst}"
              + (f"; {', '.join(tiers)}" if tiers else ""))
    print("all followers' days: " + ", ".join(f"{e} {100 * total.shares()[e]:.1f} % (w1 {100 * total.within1_shares()[e]:.1f} %)"
                                               for e in C.EVENTS) + f"; worst {total.worst} min; {quirks_all} quirk days")
    if total.misses:
        print(f"  {len(total.misses)} times beyond 1 min, e.g. " + ", ".join(f"{e} {d} {x:+d}" for e, d, x in total.misses[:6]))
    print(f"{passing} of {len(zones) if one_table else len(followers)} zones pass tier A with the followers' year")
    if "--write" in argv:
        if already:
            print(f"not written: {len(already)} records hold a follower year already; run the fit first")
            return 1
        asset_path.write_text(json.dumps(asset, ensure_ascii=False, indent=1) + "\n", encoding="utf-8", newline="\n")
        print(f"wrote {asset_path}")
    print(f"done in {time.time() - started:.0f} s")
    return 0
