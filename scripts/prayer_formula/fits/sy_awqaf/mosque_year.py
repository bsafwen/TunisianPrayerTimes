#!/usr/bin/env python3
"""
Syria's governorates held out beyond Ramadan (fits/mosque_years.py, owner 2026-10-17): the owner's mosques that follow
a governorate's Ramadan 1447 table on every day of it (the table's repeated rows left out, as fit.py leaves them), their
2026 calendars scored on the other days at the governorate's zone (official-zones/sy_awqaf.json), as the app computes
it. Every official table at hand is a Ramadan table, so a follower's year is the only check of the method outside
Ramadan (Q12). The profile is fit.py's own, refitted here as fit.py does.

Usage: python scripts/prayer_formula/fits/sy_awqaf/mosque_year.py [--write]   (run fit.py first)
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
import fit as F  # noqa: E402  (the Ramadan tables and the profile)
import mosque_years as M  # noqa: E402

ASSET = C.ASSETS / "sy_awqaf.json"


def epoch_rows(rows) -> dict:
    """[(date, {event: local minute})] as {date: {event: UTC epoch minute}}."""
    return {d: {e: C.local_to_epoch(F.TZ, d, f"{m // 60}:{m % 60:02d}") // 60 for e, m in t.items()} for d, t in rows}


def main(argv: list[str]) -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    points, city_rows = F.load_cities()
    for zid, (_, pt) in F.ZONES.items():
        if pt is not None:
            points[zid] = pt
    damascus = F.load_rows(F.SY / "dam.txt", datetime.date(2026, 2, 19))
    raqqa = F.load_rows(F.SY / "raq1446.txt", datetime.date(2025, 3, 1))
    quiet = lambda *_: None  # noqa: E731
    first = F.fit([("damascus", points["damascus"], damascus), ("raqqa", points["raqqa"], raqqa)], say=quiet, report=False)
    q_dam = F.quirks_of(damascus, points["damascus"], first)
    q_raq = F.quirks_of(raqqa, points["raqqa"], first)
    dam = [(d, t) for d, t in damascus if d not in q_dam]
    raq = [(d, t) for d, t in raqqa if d not in q_raq]
    p = F.fit([("damascus", points["damascus"], dam), ("raqqa", points["raqqa"], raq)], say=quiet, report=False)
    method = F.method_of(p, p["maghrib"]["raqqa"], p["maghrib"]["damascus"])
    official = {zid: epoch_rows(rows) for zid, rows in city_rows.items() if zid != "damascus"}
    official["damascus"] = epoch_rows(dam)
    print(f"Damascus 1447: {len(dam)} days without the repeated rows ({', '.join(str(d) for d in q_dam)})")
    zones = {z["id"]: z for z in json.loads(ASSET.read_text(encoding="utf-8"))["zones"]}
    return M.run(argv, asset_path=ASSET, countries=["SY"], tz=F.TZ, official=official,
                 compute_for=lambda zid: M.asset_computer(method, zones, zid, F.TZ, "SY"))


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
