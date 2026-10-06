#!/usr/bin/env python3
"""Reproduce the Qammart small-residential exclusion evidence from a staged catalog."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys


HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
sys.path.insert(0, str(REPO / "scripts/neighborhoods"))
from build_best_effort_geometry_overlay import unpack  # noqa: E402

EXPECTED_JSON_SHA = "2a3ecd1d4bcdd75e837c3d39827b435c5f46c0e6c8d681131d1860d3d6a1be1c"
EXPECTED_BIN_SHA = "ce01bfd302ac3010637745816b728f8f98610aa3b7b9dcf8b017c50d575d5154"
QAMMART_ID = "osm:relation:7062758"
TARGET_IDS = ("osm:way:825272931", "osm:way:825272932", "osm:way:825505201")


def sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", type=Path, required=True,
                        help="Assets from the pinned pre-exclusion three-boundary stage")
    parser.add_argument("--output", type=Path, default=HERE / "review-evidence.json")
    args = parser.parse_args()
    json_raw = (args.assets / "neighborhoods.json").read_bytes()
    bin_raw = (args.assets / "neighborhoods.bin").read_bytes()
    if (sha256(json_raw), sha256(bin_raw)) != (EXPECTED_JSON_SHA, EXPECTED_BIN_SHA):
        raise ValueError("Input assets are not the reviewed pre-exclusion stage")
    metadata = json.loads(json_raw)
    by_id = {feature["id"]: feature for feature in metadata["features"]}
    tags_raw = (HERE / "osm-tag-snapshot.json").read_bytes()
    tags = json.loads(tags_raw)
    tag_by_id = {feature["id"]: feature["tags"] for feature in tags["features"]}
    if set(tag_by_id) != set(TARGET_IDS):
        raise ValueError("OSM tag snapshot does not contain exactly the reviewed IDs")
    sector = by_id[QAMMART_ID]
    if (sector["name"] != "قمرت" or sector["kind"] != "sector"
            or sector["sourceId"] != "isie-best-effort-imada-tunis-qammart-20260924"
            or sector["pickerGroupId"] != QAMMART_ID):
        raise ValueError("Qammart official-sector identity changed")
    sector_geometry = unpack(sector, bin_raw, metadata["coordinateScale"])
    rows = []
    for identifier in TARGET_IDS:
        feature = by_id[identifier]
        source_tags = tag_by_id[identifier]
        if (source_tags != {"landuse": "residential", "name": feature["name"]}
                or feature["kind"] != "residential" or feature["sourceId"] != "osm"
                or feature["parentName"] != "قمرت" or feature["hasBoundary"] is not True
                or feature["pickerGroupId"] != identifier
                or feature["governorateId"] != sector["governorateId"]
                or feature["areaKm2"] >= 0.02):
            raise ValueError(f"Reviewed small-residential identity changed: {identifier}")
        if sum(row.get("pickerGroupId") == identifier for row in metadata["features"]) != 1:
            raise ValueError(f"Reviewed picker group is no longer a singleton: {identifier}")
        geometry = unpack(feature, bin_raw, metadata["coordinateScale"])
        cover_ratio = geometry.intersection(sector_geometry).area / geometry.area
        if cover_ratio < 0.99999:
            raise ValueError(f"Residential parcel is no longer wholly inside Qammart: {identifier}")
        packed = bin_raw[feature["offset"]:feature["offset"] + feature["length"]]
        expected_current = {key: feature[key] for key in (
            "sourceId", "name", "aliases", "kind", "parentName", "governorateId",
            "delegationId", "hasBoundary", "pickerGroupId")}
        rows.append({
            "id": identifier,
            "expectedCurrent": expected_current,
            "packedGeometrySha256": sha256(packed),
            "areaKm2": feature["areaKm2"],
            "qammartCoverRatio": round(cover_ratio, 8),
            "osmTags": source_tags,
        })
    report = {
        "schemaVersion": 1,
        "date": "2026-09-24",
        "decision": "retire_three_unsupported_small_residential_picker_rows",
        "reason": "These three French-only names identify separate, tiny OSM residential land-use parcels wholly inside the officially named Qammart imada. Their OSM tags contain no administrative reference or evidence of neighborhood-scale identity. The Qammart sector remains selectable and supplies the place label.",
        "uncertainty": "The evidence establishes their OSM land-use classification and small contained footprints, not the exact building type. Do not infer that every residential polygon is an apartment complex or retire larger named neighborhoods automatically.",
        "inputAssetSha256": {"neighborhoodsJson": EXPECTED_JSON_SHA,
                             "neighborhoodsBin": EXPECTED_BIN_SHA},
        "osmTagSnapshot": {"file": "osm-tag-snapshot.json", "sha256": sha256(tags_raw),
                           "pbfSha256": tags["snapshotSha256"],
                           "extractedAreasSha256": tags["extractedAreasSha256"]},
        "survivingSector": {"id": QAMMART_ID, "name": sector["name"],
                            "parentName": sector["parentName"],
                            "sourceId": sector["sourceId"],
                            "officialCode": "117157"},
        "exclusions": rows,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(args.output), "excludedIds": list(TARGET_IDS)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
