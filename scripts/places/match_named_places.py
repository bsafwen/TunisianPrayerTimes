"""
Fills the official-zone assets' named places with their catalogue rows (plan 6.5 and M5: "matched by name and point"),
so a city picked from the catalogue takes the zone that names it, whatever its distance. Run it after the catalogue
build and after any fit that rewrites an asset (a fit writes no catalogue ids).

A named place takes the catalogue row of its country nearest its centre within MATCH_KM (its centres come from GeoNames,
the catalogue's own source, or from OpenStreetMap a few hundred metres off); where the zone's Arabic name is a row's
Arabic name within MATCH_KM, that row wins. A row goes to one named place only: the nearest. Existing ids are kept.

    python scripts/places/match_named_places.py [--check]     # --check reports without writing
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "android-app" / "app" / "src" / "main" / "assets"
MATCH_KM = 1.0


def km(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    a = (math.sin(math.radians(lat2 - lat1) / 2) ** 2
         + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(math.radians(lng2 - lng1) / 2) ** 2)
    return 2 * 6371.0088 * math.asin(min(1.0, math.sqrt(a)))


def catalogue_rows(folder: Path) -> list[dict]:
    """The listed rows (not neighbours), as the phone reads them: an empty zone repeats the row above's."""
    rows = []
    for name in ("europe-cities.tsv", "arab-cities.tsv"):
        lines = (folder / name).read_text(encoding="utf-8").splitlines()
        header = lines[0].split("\t")
        for line in lines[1:]:
            c = dict(zip(header, line.split("\t")))
            if "n" in c.get("flags", ""):
                continue
            rows.append({"id": int(c["id"]), "cc": c["country"], "arabic": c["arabic"],
                         "lat": int(c["latE5"]) / 1e5, "lng": int(c["lngE5"]) / 1e5})
    return rows


def grid_of(rows: list[dict]) -> dict[tuple[int, int], list[dict]]:
    grid: dict[tuple[int, int], list[dict]] = defaultdict(list)
    for r in rows:
        grid[(math.floor(r["lat"] * 10), math.floor(r["lng"] * 10))].append(r)
    return grid


def near(grid, lat: float, lng: float) -> list[tuple[float, dict]]:
    """Rows within MATCH_KM, nearest first (a 0.1-degree grid, at most about 11 km a cell)."""
    cell = (math.floor(lat * 10), math.floor(lng * 10))
    found = [(km(lat, lng, r["lat"], r["lng"]), r) for dx in (-1, 0, 1) for dy in (-1, 0, 1) for r in grid[(cell[0] + dx, cell[1] + dy)]]
    return sorted((d, r) for d, r in found if d <= MATCH_KM) if found else []


def match_asset(asset: dict, grid) -> tuple[int, int]:
    """Adds catalogue ids to the asset's named places; returns (named places, matched)."""
    countries = asset["country"].split(",")
    claims: dict[int, tuple[float, dict]] = {}   # catalogue id -> (distance, named place) of its nearest claimant
    total = 0
    for zone in asset["zones"]:
        cc = zone.get("country") or countries[0]
        cc = {"EH": "MA"}.get(cc, cc)
        for place in zone.get("namedPlaces", []):
            total += 1
            lat, lng = place["centre"]["latE5"] / 1e5, place["centre"]["lngE5"] / 1e5
            candidates = [(d, r) for d, r in near(grid, lat, lng) if r["cc"] == cc]
            if not candidates:
                continue
            same_name = [(d, r) for d, r in candidates if r["arabic"] and r["arabic"] == zone.get("nameAr")]
            d, r = (same_name or candidates)[0]
            if r["id"] not in claims or d < claims[r["id"]][0]:
                claims[r["id"]] = (d, place)
    matched = 0
    for gid, (_, place) in claims.items():
        ids = place.setdefault("catalogueIds", [])
        if gid not in ids:
            ids.append(gid)
        matched += 1
    return total, matched


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)
    grid = grid_of(catalogue_rows(ASSETS / "places"))
    for path in sorted((ASSETS / "official-zones").glob("*.json")):
        text = path.read_text(encoding="utf-8")
        asset = json.loads(text)
        total, matched = match_asset(asset, grid)
        if not total:
            continue
        print(f"{path.stem}: {matched} of {total} named places matched to a catalogue row")
        if not args.check:
            out = json.dumps(asset, ensure_ascii=False, indent=1) + "\n"
            with open(path, "w", encoding="utf-8", newline="") as f:
                f.write(out)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
