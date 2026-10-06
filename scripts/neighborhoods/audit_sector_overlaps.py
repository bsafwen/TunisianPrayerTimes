"""Audit current packaged sector overlaps for one governorate.

This measures conflicts in the bundled polygons. It does not identify which
side of a conflict is geographically correct or certify any source boundary.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

from pyproj import Transformer
from shapely.geometry import shape
from shapely.ops import transform


REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "scripts/locality_review"))
from boundary import _BINARY_HEADER, _parse_multipolygon  # noqa: E402


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def overlaps(a: list[float], b: list[float]) -> bool:
    return a[0] < b[2] and a[2] > b[0] and a[1] < b[3] and a[3] > b[1]


def audit(metadata: Path, binary_path: Path, governorate_id: int, min_m2: float) -> dict:
    catalog = json.loads(metadata.read_text(encoding="utf-8"))
    binary = binary_path.read_bytes()
    assert binary.startswith(_BINARY_HEADER)
    sectors = [row for row in catalog["features"] if row["kind"] == "sector" and row.get("hasBoundary")]
    selected = [row for row in sectors if row["governorateId"] == governorate_id]
    assert selected, f"no bounded sectors for governorate {governorate_id}"
    candidates = [row for row in sectors if any(overlaps(row["bbox"], item["bbox"]) for item in selected)]
    projector = Transformer.from_crs("EPSG:4326", "EPSG:32632", always_xy=True)
    geometries = {}
    invalid = []
    for row in candidates:
        polygons = _parse_multipolygon(
            binary, row["offset"], row["length"], catalog["coordinateScale"], row["id"]
        )
        polygon = shape({"type": "MultiPolygon", "coordinates": polygons})
        if not polygon.is_valid or polygon.is_empty:
            invalid.append(row["id"])
            continue
        geometries[row["id"]] = transform(projector.transform, polygon)

    pairs_seen = set()
    conflicts = []
    errors = []
    for first in selected:
        first_geom = geometries.get(first["id"])
        if first_geom is None:
            continue
        for second in candidates:
            if first["id"] == second["id"] or not overlaps(first["bbox"], second["bbox"]):
                continue
            pair = tuple(sorted((first["id"], second["id"])))
            if pair in pairs_seen:
                continue
            pairs_seen.add(pair)
            second_geom = geometries.get(second["id"])
            if second_geom is None:
                continue
            try:
                area_m2 = first_geom.intersection(second_geom).area
            except Exception as exc:
                errors.append({"ids": pair, "error": str(exc)})
                continue
            if area_m2 >= min_m2:
                conflicts.append({
                    "ids": pair,
                    "names": {first["id"]: first["name"], second["id"]: second["name"]},
                    "governorateIds": {first["id"]: first["governorateId"], second["id"]: second["governorateId"]},
                    "sourceIds": {first["id"]: first["sourceId"], second["id"]: second["sourceId"]},
                    "areaM2": round(area_m2, 1),
                    "areaKm2": round(area_m2 / 1_000_000, 6),
                })
    conflicts.sort(key=lambda row: (-row["areaM2"], row["ids"]))
    return {
        "schemaVersion": 1,
        "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
        "governorateId": governorate_id,
        "minimumReportedOverlapM2": min_m2,
        "counts": {
            "selectedBoundedSectors": len(selected),
            "bboxCandidateSectors": len(candidates),
            "sectorPairsMeasured": len(pairs_seen),
            "positiveConflictsAboveThreshold": len(conflicts),
            "invalidCandidateGeometries": len(invalid),
            "intersectionErrors": len(errors),
        },
        "inputSha256": {str(metadata): sha(metadata), str(binary_path): sha(binary_path)},
        "method": "Valid packaged multipolygons, UTM zone 32N planar intersection area; bbox candidate prefilter. Geographic truth and source currency were not assessed.",
        "invalidGeometryIds": invalid,
        "intersectionErrors": errors,
        "conflicts": conflicts,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--metadata", type=Path, default=REPO / "android-app/app/src/main/assets/neighborhoods.json")
    parser.add_argument("--binary", type=Path, default=REPO / "android-app/app/src/main/assets/neighborhoods.bin")
    parser.add_argument("--governorate-id", type=int, required=True)
    parser.add_argument("--minimum-m2", type=float, default=1.0)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    assert args.minimum_m2 > 0
    report = audit(args.metadata.resolve(), args.binary.resolve(), args.governorate_id, args.minimum_m2)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report["counts"], ensure_ascii=True))


if __name__ == "__main__":
    main()
