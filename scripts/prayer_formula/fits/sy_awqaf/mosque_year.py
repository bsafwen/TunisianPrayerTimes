#!/usr/bin/env python3
"""
Syria's mosques in the owner's dataset against the screens' zones (support only, owner 2026-10-11): each mosque's 2026
calendar against every zone's 2026 days as the app shows them (fit.py's fast closed form, the reference's PRAYTIMES
computation), the zone it matches best and the share of its minutes. A mosque that matches a zone on every day shows
that mosques print the screens' tables. The zones' records are the tables' own whole years (fit.py), so nothing is
merged into them, and the dataset is read in place, never copied (A7).

Usage: python scripts/prayer_formula/fits/sy_awqaf/mosque_year.py   (run fit.py first)
"""

from __future__ import annotations

import datetime
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))
import common as C  # noqa: E402
import fit as F  # noqa: E402  (the fast closed form)
import mosque_years as M  # noqa: E402

ASSET = C.ASSETS / "sy_awqaf.json"
DAYS = [datetime.date(M.YEAR, 1, 1) + datetime.timedelta(days=i) for i in range(365)]


def zone_days(z: dict) -> dict:
    """The zone's local minutes on every day of the dataset's year, as the app shows them."""
    at = z.get("eveningPoint") or z["point"]
    lat, lng = at["latE5"] / 1e5, at["lngE5"] / 1e5
    angles = {e: z[k] for e, k in (("FAJR", "fajrAngleDeg"), ("ISHA", "ishaAngleDeg")) if z.get(k) is not None}
    out = {}
    for day in DAYS:
        shown = F.ref.table_date(day, z["referenceYear"], z.get("tableRows", "CALENDAR"))
        out[day] = {e: F.rounded(F.unrounded(e, shown, lat, lng, z.get("asrShadowHourUt"), angles, z.get("offsets")))
                    for e in C.EVENTS}
    return out


def compare(mosque: M.Mosque, days: dict) -> tuple[int, int, int]:
    """Exact minutes, minutes compared and the largest difference of a mosque's calendar against a zone's days."""
    exact = total = worst = 0
    for day, computed in days.items():
        printed = mosque.calendar.get(day)
        if not printed:
            continue
        for e in C.EVENTS:
            try:
                h, m = (int(x) for x in str(printed[e]).strip()[:5].split(":"))
            except ValueError:
                continue
            diff = abs((h * 60 + m - computed[e] + 720) % 1440 - 720)
            total += 1
            exact += diff == 0
            worst = max(worst, diff)
    return exact, total, worst


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    zones = json.loads(ASSET.read_text(encoding="utf-8"))["zones"]
    tables = {z["id"]: zone_days(z) for z in zones}
    names = {z["id"]: z["nameAr"] for z in zones}
    mosques = M.mosques("SY")
    print(f"{len(mosques)} Syrian mosques in the owner's dataset, {len(zones)} zones; each mosque's best zone:")
    followers = 0
    for mosque in sorted(mosques, key=lambda m: (m.city, m.name)):
        scored = [(compare(mosque, days), zid) for zid, days in tables.items()]
        (exact, total, worst), zid = max(scored, key=lambda s: (s[0][0] / max(1, s[0][1]), -s[0][2]))
        if not total:
            continue
        share = exact / total
        follows = share >= M.FOLLOW_EXACT and worst <= 1
        followers += follows
        print(f"  {mosque.city or '-'} / {mosque.name}: {zid} «{names[zid]}» {exact}/{total} ({100 * share:.1f} %), "
              f"worst {worst} min{' - follows it' if follows else ''}")
    print(f"{followers} of {len(mosques)} mosques follow a zone (at least {100 * M.FOLLOW_EXACT:.0f} % exact, all within 1 min)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
