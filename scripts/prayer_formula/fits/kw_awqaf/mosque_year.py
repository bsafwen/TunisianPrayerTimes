#!/usr/bin/env python3
"""
Kuwait held out on a whole year now (fits/mosque_years.py, owner 2026-10-17): the owner's mosques that follow the
Ministry of Awqaf's perpetual timetable on every transcribed row (each listed place: Kuwait City's rows plus its
published offset), their 2026 calendars scored on the other days at the place's zone (official-zones/kw_awqaf.json),
as the app computes it (A8). The sheet is transcribed for six months; a follower's calendar covers the other six.
The 12 zones are one table and whole-minute offsets, so the year of the follower with the most rows goes to every
zone's record (fit.py gives them all Kuwait City's record).
The quirk rows (29 February, 1 March) are left out of the test, as fit.py leaves them out of its fit. The profile is
the shipped one (ArabAuthorityProfiles.KW_AWQAF: 18°, 17.5°, Asr on the apparent sun, the engine's defaults).

Usage: python scripts/prayer_formula/fits/kw_awqaf/mosque_year.py [--write]   (run fit.py first)
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
import fit as F  # noqa: E402  (the sheet and the places)
import mosque_years as M  # noqa: E402

ASSET = C.ASSETS / "kw_awqaf.json"
METHOD = C.method_json(id=F.AUTHORITY, fajr={"type": "angle", "degrees": 18.0}, isha={"type": "angle", "degrees": 17.5},
                       asrSun="APPARENT", referenceYear=2011, evidence={"kind": "FROM_TABLES", "source": F.EVIDENCE})


def main(argv: list[str]) -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    sheet, _ = F.sheets()
    official = {}
    for zid, _, offset, _ in F.PLACES:
        rows = {}
        for (mo, d), minutes in sheet.items():
            if (mo, d) in F.QUIRKS:
                continue
            day = datetime.date(M.YEAR, mo, d)
            rows[day] = {e: C.local_to_epoch(F.TZ, day, f"{m // 60}:{m % 60:02d}") // 60 + offset for e, m in zip(C.EVENTS, minutes)}
        official[zid] = rows
    zones = {z["id"]: z for z in json.loads(ASSET.read_text(encoding="utf-8"))["zones"]}
    return M.run(argv, asset_path=ASSET, countries=["KW"], tz=F.TZ, official=official, one_table=True,
                 compute_for=lambda zid: M.asset_computer(METHOD, zones, zid, F.TZ, "KW"))


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
