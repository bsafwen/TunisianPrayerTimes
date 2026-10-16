"""
Checks an official-zone asset (schema 1, plan 6.5) the way the phone's parser and OfficialZoneAssetsTest read it:
required keys, Arabic names, unique ids, existing base zones, legal enums and a complete FitStats.

    python scripts/prayer_formula/fits/validate_asset.py android-app/app/src/main/assets/official-zones/<authority>.json
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

EVENTS = {"FAJR", "SUNRISE", "DHUHR", "ASR", "MAGHRIB", "ISHA"}
MAPPINGS = {"NEAREST", "NEAREST_LOCAL", "LISTED_PLACES", "DISTRICT", "NATIONAL", "NAMED_REGIONS", "ADMIN_AREA", "EXACT"}
OFFSET_RULES = {"PUBLISHED", "MONTHLY_DIFFERENCE", "WINDOW_CEILING", "TAKVIM"}
ZONE_KEYS = {
    "id", "nameAr", "point", "morningPoint", "eveningPoint", "elevationM", "eveningElevationM", "pressureHpa", "temperatureC",
    "sunriseAltitudeDeg", "sunsetAltitudeDeg", "offsets", "baseZoneId", "offsetRule", "halfMonthWindows", "terrainClass",
    "admin1", "admin2", "namedPlaces", "reachKm", "mappingTier", "switchDays", "fit", "country", "sunriseAtPlace",
    "elevationShiftFloor", "belowSeaLevel",
}
FIT_KEYS = {"fittedOn", "heldOut", "exact", "within1", "daysPerMonth", "heldOutRamadanDays", "quirkDays", "maxDiffMin"}
ARABIC = re.compile(r"[؀-ۿ]")


def problems_of(path: Path) -> list[str]:
    asset = json.loads(path.read_text(encoding="utf-8"))
    out = []
    if asset.get("schema") != 1:
        out.append("schema must be 1")
    if asset.get("authority") != path.stem:
        out.append(f"authority {asset.get('authority')} is not the file name {path.stem}")
    for key in ("methodId", "country", "mapping", "zones"):
        if key not in asset:
            out.append(f"missing {key}")
    if asset.get("mapping") not in MAPPINGS:
        out.append(f"mapping {asset.get('mapping')}")
    if not all(re.fullmatch(r"[A-Z]{2}", c) for c in str(asset.get("country", "")).split(",")):
        out.append(f"country {asset.get('country')}")
    if not all(isinstance(code, str) and code for code in asset.get("exactAdmin1", [])):
        out.append("exactAdmin1 must list admin1 codes")
    fits = [("asset", asset["fit"])] if asset.get("fit") else []
    zones = asset.get("zones", [])
    ids = [z.get("id") for z in zones]
    if not zones:
        out.append("no zones")
    if len(ids) != len(set(ids)):
        out.append("duplicate zone ids")
    for z in zones:
        where = f"zone {z.get('id')}"
        unknown = set(z) - ZONE_KEYS
        if unknown:
            out.append(f"{where}: unknown keys {sorted(unknown)}")
        if not isinstance(z.get("id"), str) or not z.get("id"):
            out.append(f"{where}: id must be a non-empty string")
        if not ARABIC.search(z.get("nameAr") or ""):
            out.append(f"{where}: nameAr must be Arabic")
        for key in ("point", "morningPoint", "eveningPoint"):
            p = z.get(key)
            if p is None and key != "point":
                continue
            if not (isinstance(p, dict) and isinstance(p.get("latE5"), int) and isinstance(p.get("lngE5"), int)
                    and abs(p["latE5"]) <= 9_000_000 and abs(p["lngE5"]) <= 18_000_000):
                out.append(f"{where}: {key} must be {{latE5, lngE5}} integers")
        if set(z.get("offsets", {})) - EVENTS:
            out.append(f"{where}: offsets keys must be events")
        if z.get("offsetRule", "PUBLISHED") not in OFFSET_RULES:
            out.append(f"{where}: offsetRule {z.get('offsetRule')}")
        if z.get("baseZoneId") is not None and z["baseZoneId"] not in ids:
            out.append(f"{where}: unknown baseZoneId {z['baseZoneId']}")
        for event, r in z.get("switchDays", {}).items():
            if event not in EVENTS or not (isinstance(r, dict) and {"first", "last"} <= set(r)):
                out.append(f"{where}: switchDays {event}")
        for named in z.get("namedPlaces", []):
            if "centre" not in named:
                out.append(f"{where}: a named place needs a centre")
        if z.get("fit"):
            fits.append((where, z["fit"]))
    for where, fit in fits:
        missing = FIT_KEYS - set(fit)
        if missing:
            out.append(f"{where}: FitStats misses {sorted(missing)}")
        for sample in fit.get("fittedOn", []) + fit.get("heldOut", []):
            if not {"source", "from", "to", "days", "zones"} <= set(sample):
                out.append(f"{where}: a sample needs source, from, to, days and zones")
        for key in ("exact", "within1", "daysPerMonth"):
            if set(fit.get(key, {})) - EVENTS:
                out.append(f"{where}: {key} keys must be events")
        if any(len(v) != 12 for v in fit.get("daysPerMonth", {}).values()):
            out.append(f"{where}: daysPerMonth needs 12 counts per event")
    return out


def main(argv: list[str]) -> int:
    status = 0
    for arg in argv:
        found = problems_of(Path(arg))
        print(f"{arg}: {'OK' if not found else str(len(found)) + ' problems'}")
        for p in found[:40]:
            print("  " + p)
        status |= bool(found)
    return status


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
