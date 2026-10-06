#!/usr/bin/env python3
"""Report small residential polygons inside an imada for manual catalog review.

This is a triage report, not an automatic exclusion decision. A small land-use
polygon can still be a meaningful named place; a reviewer must check its identity.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from shapely.strtree import STRtree

from build_best_effort_geometry_overlay import unpack


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--governorate-id", type=int, required=True)
    parser.add_argument("--max-area-km2", type=float, default=0.02)
    parser.add_argument("--min-cover-ratio", type=float, default=0.99)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.max_area_km2 <= 0 or not 0 < args.min_cover_ratio <= 1:
        parser.error("Area must be positive and cover ratio must be in (0, 1]")

    metadata = json.loads((args.assets / "neighborhoods.json").read_text(encoding="utf-8"))
    blob = (args.assets / "neighborhoods.bin").read_bytes()
    scale = metadata["coordinateScale"]
    features = metadata["features"]
    sectors = [feature for feature in features
               if feature.get("governorateId") == args.governorate_id
               and feature.get("kind") == "sector" and feature.get("hasBoundary")]
    sector_shapes = [unpack(feature, blob, scale) for feature in sectors]
    tree = STRtree(sector_shapes)
    candidates = []
    for feature in features:
        if (feature.get("governorateId") != args.governorate_id
                or feature.get("kind") != "residential" or not feature.get("hasBoundary")
                or feature.get("areaKm2", float("inf")) > args.max_area_km2):
            continue
        polygon = unpack(feature, blob, scale)
        if polygon.is_empty or polygon.area <= 0:
            continue
        matches = []
        for index in tree.query(polygon):
            sector = sectors[int(index)]
            sector_polygon = sector_shapes[int(index)]
            ratio = polygon.intersection(sector_polygon).area / polygon.area
            if ratio >= args.min_cover_ratio:
                matches.append({
                    "id": sector["id"],
                    "name": sector["name"],
                    "parentName": sector.get("parentName"),
                    "officialBoundarySourceId": sector.get("sourceId"),
                    "coverRatio": round(ratio, 8),
                })
        if matches:
            matches.sort(key=lambda row: (-row["coverRatio"], row["id"]))
            candidates.append({
                "id": feature["id"],
                "name": feature["name"],
                "kind": feature["kind"],
                "sourceId": feature.get("sourceId"),
                "areaKm2": feature["areaKm2"],
                "parentName": feature.get("parentName"),
                "pickerGroupId": feature.get("pickerGroupId"),
                "ownPickerGroup": feature.get("pickerGroupId") == feature["id"],
                "containingSectors": matches,
            })
    candidates.sort(key=lambda row: (row["areaKm2"], row["id"]))
    report = {
        "schemaVersion": 1,
        "governorateId": args.governorate_id,
        "maxAreaKm2": args.max_area_km2,
        "minCoverRatio": args.min_cover_ratio,
        "sectorCount": len(sectors),
        "candidateCount": len(candidates),
        "candidates": candidates,
        "caveat": "Geometry containment and size flag review candidates; they do not prove a place is a residential complex or justify automatic removal.",
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"candidateCount": len(candidates), "output": str(args.output)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
