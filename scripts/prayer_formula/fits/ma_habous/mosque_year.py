#!/usr/bin/env python3
"""
Morocco's localities held out on a whole year now (fits/mosque_years.py, owner 2026-10-17): the owner's mosques that
follow a Habous locality's table on every collected day (Habous's GMT week of 2026-09-13 to 09-19 left out), their 2026
calendars scored on the other days with the zone's fitted point and horizons (official-zones/ma_habous.json), as
fit.py's held-out records are.

A mosque is tried against its three nearest localities and kept for the one it follows best. Of a locality's
followers, the one with the most official days is scored. With --write, each such zone's record gains that year as a
held-out body (`source` "mosque_follower"); the profile's record is left as fit.py wrote it.

Usage: python scripts/prayer_formula/fits/ma_habous/mosque_year.py [--write]
"""

from __future__ import annotations

import datetime
import json
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))
import common as C  # noqa: E402
import fit as F  # noqa: E402  (the Habous tables, the profile and the scoring)
import mosque_years as M  # noqa: E402

ASSET = C.ASSETS / "ma_habous.json"


def calendar_of(mosque: M.Mosque, days: list[datetime.date]) -> dict:
    """A follower's [days] as {date: {event: UTC epoch minute}}."""
    out = {}
    for day in days:
        minutes = {e: M.epoch_minute(F.TZ, day, mosque.calendar[day][e], e) for e in C.EVENTS}
        if all(v is not None for v in minutes.values()):
            out[day] = minutes
    return out


def computer(vid: str, lat: float, lng: float, sunrise_deg: float, sunset_deg: float):
    """The reference (common.compute) at a zone's fitted point and horizons, as fit.score runs it."""
    method = F.method_of(F.CURRENT)
    zone = C.zone_json(vid, lat, lng, authority="ma_habous", sunriseAltitudeDeg=round(sunrise_deg, 3),
                       sunsetAltitudeDeg=round(sunset_deg, 3))

    def compute_day(day: datetime.date) -> dict:
        out = C.compute(C.case(method, lat, lng, F.TZ, day, zone=zone, country="MA"))
        return {e: out[e][1] // 60 for e in C.EVENTS if out[e][1] is not None}
    return compute_day


def main(argv: list[str]) -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    started = time.time()
    tables, _, _ = F.load_tables()
    official = {vid: {d: {e: p[e] // 60 for e in C.EVENTS} for d, p in zip(t.days, t.pub)
                      if not (F.GMT_ROWS[0] <= d <= F.GMT_ROWS[1])}
                for vid, t in tables.items()}
    asset = json.loads(ASSET.read_text(encoding="utf-8"))
    zones = {z["id"]: z for z in asset["zones"]}
    mosques = M.mosques("MA") + M.mosques("EH")
    print(f"{len(mosques)} mosques (MA, EH) in the owner's dataset; {len(official)} localities with Habous days")

    followers: dict[str, list] = {}
    for m in mosques:
        near = sorted(zones.values(), key=lambda z: M.km(m.lat, m.lng, z["point"]["latE5"] / 1e5, z["point"]["lngE5"] / 1e5))[:3]
        best = None
        for z in near:
            if z["id"] not in official:
                continue
            f = M.follows(m, F.TZ, official[z["id"]])
            if f.follows and (best is None or (f.share, f.days) > (best[1].share, best[1].days)):
                best = (z["id"], f)
        if best:
            followers.setdefault(best[0], []).append((m, best[1]))
    print(f"{sum(len(v) for v in followers.values())} mosques follow a locality's table, at {len(followers)} localities")

    total = C.Score()
    passing = quirks_all = 0
    for vid in sorted(followers, key=lambda v: int(v)):
        z = zones[vid]
        lat, lng = z["point"]["latE5"] / 1e5, z["point"]["lngE5"] / 1e5
        mosque, follow = max(followers[vid], key=lambda x: (x[1].days, x[1].share))
        calendar = calendar_of(mosque, sorted(d for d in mosque.calendar if d not in tables[vid].days))
        score = C.Score()
        quirks = M.score_calendar(computer(vid, lat, lng, z["sunriseAltitudeDeg"], z["sunsetAltitudeDeg"]), calendar, score)
        for e in C.EVENTS:      # the followers' days together, for the summary
            total.exact[e] += score.exact[e]
            total.within1[e] += score.within1[e]
            total.total[e] += score.total[e]
        total.worst = max(total.worst, score.worst)
        quirks_all += quirks
        scored = len(calendar) - quirks
        sample = C.sample("mosque_follower", min(calendar), max(calendar), scored, 1)
        merged = M.merge_held_out(z["fit"], score, sample, quirk_days=quirks)
        tier_a = M.passes_tier_a(merged)
        passing += tier_a
        shares = " ".join(f"{e[:2]} {100 * score.shares()[e]:.0f}" for e in C.EVENTS)
        print(f"  {vid:>3} {z['nameAr']}: {len(followers[vid])} follower(s), scored {mosque.city} "
              f"({M.km(mosque.lat, mosque.lng, lat, lng):.1f} km; {follow.days} official days, {100 * follow.share:.1f} %): "
              f"{scored} days ({quirks} clock-quirk days left out), {shares}, worst {score.worst}{'  tier A' if tier_a else ''}")
        if "--write" in argv:
            z["fit"] = merged
    print("all followers' days: " + ", ".join(f"{e} {100 * total.shares()[e]:.1f} % (w1 {100 * total.within1_shares()[e]:.1f} %)"
                                               for e in C.EVENTS) + f"; worst {total.worst} min; {quirks_all} clock-quirk days")
    print(f"{passing} of {len(followers)} localities pass tier A with their follower's year")
    if "--write" in argv:
        ASSET.write_text(json.dumps(asset, ensure_ascii=False, indent=1) + "\n", encoding="utf-8", newline="\n")
        print(f"wrote {ASSET}")
    print(f"done in {time.time() - started:.0f} s")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
