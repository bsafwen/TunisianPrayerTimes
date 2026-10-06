"""Prepare a pinned, provisional Zahra/Hiboun clip for offline staging."""

from __future__ import annotations

import hashlib
import json
import math
import sys
from pathlib import Path

from shapely import set_precision
from shapely.geometry import Point, shape


REPO = Path("C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes")
HERE = REPO / "scripts/neighborhoods/official/mahdia-zahra-hiboun-seam-20260925"
ASSETS = REPO / "android-app/app/src/main/assets"
PREFIX = "scripts/neighborhoods/official/mahdia-zahra-hiboun-seam-20260925/"
EXPECTED = {
    "neighborhoods.json": "fcb79512942c42868ce7e1485b8f57b007f63f9896f413015764eae866db563f",
    "neighborhoods.bin": "3e363798e3ca1f9f6606403198a478ba4909a08dd3ac3cc2d583c7319e94b460",
    "el-zahra.pdf": "d3095e2cb8ef1b65bdbf6a9bf3f932a2a25655fb39dde089366cb7970e74ce55",
    "hiboun.pdf": "4c93df7b54f7b9aa7356f3936ced541336cba298e17aa41c6c0d3e7fe57bfa50",
    "el-zahra-native-candidate.geojson": "ec9676c4a9d01a16815b183ee08763424e220f49b08bf8dc0c70458087135785",
}
TARGET = "osm:relation:7152287"
CLIPPER = "osm:relation:7152283"
SOURCE_ID = "isie-zahra-native-preservation-20260925"
DERIVED_ID = "osm-isie-reviewed-clip-zahra-hiboun-seam-20260925"


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def pin(name: str) -> dict:
    return {"file": PREFIX + name, "sha256": sha(HERE / name)}


def km2(geometry) -> float:
    if geometry.is_empty:
        return 0.0
    return geometry.area * 111.32**2 * math.cos(math.radians(geometry.representative_point().y))


def main() -> None:
    for name, expected in EXPECTED.items():
        path = ASSETS / name if name.startswith("neighborhoods.") else HERE / name
        if sha(path) != expected:
            raise ValueError(f"Pinned input changed: {path}")
    sys.path.insert(0, str(REPO / "scripts/neighborhoods"))
    sys.path.insert(0, str(REPO / "scripts"))
    from build_best_effort_geometry_overlay import unpack
    from generate_neighborhoods import SCALE, packed_geometry_bytes

    catalog = json.loads((ASSETS / "neighborhoods.json").read_text(encoding="utf-8"))
    packed = (ASSETS / "neighborhoods.bin").read_bytes()
    rows = {row["id"]: row for row in catalog["features"]}
    target, clipper = rows[TARGET], rows[CLIPPER]
    if (target["sourceId"] != "osm" or target["name"] != "الزهراء"
            or target["parentName"] != "معتمدية المهدية" or target["governorateId"] != 345
            or clipper["sourceId"] != "isie-local-sectors-2023-hiboun-review"
            or clipper["name"] != "هيبون" or clipper["parentName"] != "معتمدية المهدية"
            or clipper["governorateId"] != 345):
        raise ValueError("Zahra/Hiboun identity or source changed")
    old = unpack(target, packed, SCALE)
    official = unpack(clipper, packed, SCALE)
    source = json.loads((HERE / "el-zahra-native-candidate.geojson").read_text(encoding="utf-8"))
    feature = source["features"][0]
    if (feature["id"] != TARGET or feature["properties"]["nameAr"] != "الزهراء"
            or feature["properties"]["sourcePdfSha256"] != EXPECTED["el-zahra.pdf"]
            or feature["properties"]["sourceDrawingIndex"] != 628):
        raise ValueError("Native Zahra source identity changed")
    native = shape(feature["geometry"])
    if not all(geometry.is_valid and not geometry.is_empty for geometry in (old, official, native)):
        raise ValueError("Invalid input geometry")
    target_payload = packed[target["offset"]:target["offset"] + target["length"]]
    if hashlib.sha256(target_payload).hexdigest() != hashlib.sha256(packed_geometry_bytes(old)).hexdigest():
        raise ValueError("Target packed geometry is not canonical")
    clipper_payload_sha = hashlib.sha256(packed_geometry_bytes(official)).hexdigest()
    overlap = old.intersection(official)
    preserve_mask = set_precision(native.buffer(1 / SCALE), 1 / SCALE)
    preserved = overlap.intersection(preserve_mask)
    removed = overlap.difference(preserve_mask)
    candidate = set_precision(old.difference(removed), 1 / SCALE)
    if (not candidate.is_valid or candidate.is_empty or candidate.geom_type != "Polygon"
            or not candidate.covers(Point(target["lng"], target["lat"]))):
        raise ValueError("Provisional clip changed Zahra unexpectedly")
    before_km2, removed_km2, preserved_km2 = km2(overlap), km2(removed), km2(preserved)
    if not (0.023 < before_km2 < 0.025 and 0.019 < removed_km2 < 0.022
            and 0.002 < preserved_km2 < 0.005):
        raise ValueError("Overlap measures differ from reviewed candidate")
    uncertainty = (
        "The ISIE 2023 Zahra map draws a red northern divider against Hiboun. The installed Hiboun "
        "ring is separately source-reviewed. Remove only the part of the older OSM Zahra polygon "
        "that crosses the Hiboun ring outside Zahra's closed native red ring, while preserving the "
        "small source-supported residual with one app-grid-unit margin. A wholesale Zahra replacement "
        "would overlap the installed Zgana polygon by about 8.23 km2. This local seam is best effort, "
        "and its ground accuracy and residual overlap remain unverified."
    )
    review = {
        "status": "provisional_best_effort_boundary_candidate",
        "sourceGeojsonSha256": EXPECTED["el-zahra-native-candidate.geojson"],
        "targetId": TARGET,
        "officialCode": "335159",
        "sourceDrawingIndex": 628,
        "sourceMapControlResidualM": 0.16825241755795767,
        "method": "One-sided app-grid clip of OSM Zahra by installed official Hiboun, preserving overlap also present in Zahra's closed native red ISIE ring.",
        "sourceMapPins": [pin("el-zahra.pdf"), pin("hiboun.pdf")],
        "before": {"overlapKm2": round(before_km2, 6)},
        "candidate": {"removedKm2": round(removed_km2, 6),
                      "preservedOverlapKm2": round(preserved_km2, 6),
                      "targetValid": True, "targetRetainsRepresentativePoint": True},
        "uncertainty": uncertainty,
    }
    write(HERE / "source-review.json", review)
    required = ("sourceId", "name", "kind", "parentName", "governorateId", "delegationId", "hasBoundary", "pickerGroupId")
    evidence = [pin(name) for name in ("source-review.json", "el-zahra-native-candidate.geojson", "el-zahra.pdf", "hiboun.pdf")]
    manifest = {
        "schemaVersion": 1,
        "baseBuilder": PREFIX + "build_base_snapshot.py",
        "sources": [{
            "id": SOURCE_ID,
            "provider": "Instance Superieure Independante pour les Elections (ISIE)",
            "geojsonFile": PREFIX + "el-zahra-native-candidate.geojson",
            "geojsonSha256": EXPECTED["el-zahra-native-candidate.geojson"],
            "reviewFile": PREFIX + "source-review.json",
            "reviewSha256": sha(HERE / "source-review.json"),
        }],
        "records": [],
        "clips": [{
            "id": TARGET, "clipById": CLIPPER, "sourceId": DERIVED_ID,
            "expectedCurrent": {key: target.get(key) for key in required},
            "expectedClipper": {key: clipper.get(key) for key in required},
            "expectedPackedGeometrySha256": hashlib.sha256(target_payload).hexdigest(),
            "expectedClipperGeometrySha256": clipper_payload_sha,
            "minRemovedAreaKm2": 0.019, "maxRemovedAreaKm2": 0.022,
            "minPreservedOverlapKm2": 0.002, "maxPreservedOverlapKm2": 0.005,
            "maxPreserveGeometryMismatchM2": 50.0, "preserveBufferGridUnits": 1,
            "preserveOverlapSource": {"sourceId": SOURCE_ID, "featureId": TARGET},
            "reason": uncertainty, "evidence": evidence,
        }],
    }
    write(HERE / "geometry-overlay-manifest.json", manifest)
    print(json.dumps({"status": "prepared_not_installed", "beforeKm2": before_km2,
                      "removedKm2": removed_km2, "preservedOverlapKm2": preserved_km2,
                      "reviewSha256": sha(HERE / "source-review.json"),
                      "manifestSha256": sha(HERE / "geometry-overlay-manifest.json")}, indent=2))


if __name__ == "__main__":
    main()
