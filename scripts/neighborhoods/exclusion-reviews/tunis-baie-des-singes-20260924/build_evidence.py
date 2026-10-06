#!/usr/bin/env python3
"""Reproduce the Baie des Singes exclusion evidence and combined stage manifest."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys

from pyproj import Transformer
from shapely.geometry import shape
from shapely.ops import transform

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
ASSETS = REPO / "android-app/app/src/main/assets"
TEMPLATE = REPO / "scripts/neighborhoods/exclusion-reviews/tunis-qammart-three-small-residential-20260924/geometry-overlay-manifest.json"
TARGET_ID = "osm:way:128232827"
SECTOR_ID = "osm:relation:7062337"
TRIAGE_REPORT_PATH = "work/interior-imada-triage-20260924/tunis-latin-name-triage-20260924.json"
EXPECTED_JSON_SHA = "9940982ec572277c4940c03490c1a523c77cfad9f074138691869b1cc6f3a051"
EXPECTED_BIN_SHA = "44858cfc2ac13adb09200b94b36d3b01882fea5f830c3031847e5adfd99ba204"
EXPECTED_OSM_AREAS_SHA = "346a79dca9013b427cf7ce4e11fb31ca03fa575ee7a68c865701c463c504a1ce"
EXPECTED_PBF_SHA = "7edc8fa6fc00635c4507ab3718552c015533210b46c4d95099b43a76f21939fd"
EXPECTED_TRIAGE_SHA = "8d08610c2127a3d2d8d103a5dd79f0213e735433e0e88f3f1aa82a051fe00439"
EXPECTED_TEMPLATE_SHA = "e44f7675ee1deabf96dafc2eec4bf4f9c9363f193b82876b3e309a00e8fe8477"
EXPECTED_TAG_SNAPSHOT_SHA = "bd7b30482d40b885c40ecb95d35dd0ee17e30bbfcf7e268ee86c824ae40d8fdb"
EXPECTED_TRIAGE_EXTRACT_SHA = "fc7d8f35179007e994b574cc0c8df80c8cdb82bb17f26e71301f3ef36d3bbbef"
EXPECTED_TUNIS_OVERLAP_PERCENT = 98.67760621656272
IDENTITY_FIELDS = (
    "sourceId", "name", "aliases", "kind", "parentName", "governorateId",
    "delegationId", "hasBoundary", "pickerGroupId",
)


def sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def canonical_sha(value: object) -> str:
    raw = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return sha256(raw)


def json_bytes(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", type=Path, default=ASSETS,
                        help="Pinned installed baseline app-assets directory")
    args = parser.parse_args()
    json_raw = (args.assets / "neighborhoods.json").read_bytes()
    bin_raw = (args.assets / "neighborhoods.bin").read_bytes()
    if (sha256(json_raw), sha256(bin_raw)) != (EXPECTED_JSON_SHA, EXPECTED_BIN_SHA):
        raise ValueError("Input assets are not the pinned installed Tunis baseline")
    metadata = json.loads(json_raw)
    by_id = {feature["id"]: feature for feature in metadata["features"]}
    target = by_id.get(TARGET_ID)
    parent = by_id.get(SECTOR_ID)
    if target is None or parent is None:
        raise ValueError("Pinned target or official sector is missing from installed catalog")
    if (target["sourceId"] != "osm" or target["kind"] != "residential"
            or target["hasBoundary"] is not True or target["pickerGroupId"] != TARGET_ID
            or sum(feature.get("pickerGroupId") == TARGET_ID for feature in metadata["features"]) != 1):
        raise ValueError("Target is no longer the reviewed singleton OSM residential polygon")
    if (parent["kind"] != "sector" or parent["name"] != "المرسى الشاطئ"
            or parent["sourceId"] != "isie-best-effort-imada-tunis-marsa-beach-20260924"):
        raise ValueError("Current official parent sector identity/source changed")
    expected_current = {key: target[key] for key in IDENTITY_FIELDS}
    packed = bin_raw[target["offset"]:target["offset"] + target["length"]]
    if not packed or target["offset"] < 8 or target["offset"] + target["length"] > metadata["country"]["offset"]:
        raise ValueError("Target packed-geometry bounds are invalid")
    packed_sha = sha256(packed)
    target_metadata_sha = canonical_sha({k: v for k, v in target.items() if k not in ("offset", "length")})

    snapshot_path = HERE / "osm-tag-snapshot.json"
    snapshot_raw = snapshot_path.read_bytes()
    snapshot = json.loads(snapshot_raw)
    if (snapshot.get("snapshotSha256") != EXPECTED_PBF_SHA
            or snapshot.get("extractedAreasSha256") != EXPECTED_OSM_AREAS_SHA):
        raise ValueError("Pinned OSM PBF or extraction snapshot changed")
    snapshot_by_id = {feature["id"]: feature for feature in snapshot["features"]}
    if set(snapshot_by_id) != {TARGET_ID, SECTOR_ID}:
        raise ValueError("OSM snapshot must contain exactly the target and its official parent relation")
    osm_target = snapshot_by_id[TARGET_ID]
    osm_sector = snapshot_by_id[SECTOR_ID]
    if osm_target["tags"] != {"landuse": "residential", "name": "Baie des Singes"}:
        raise ValueError("Target OSM source tags changed")
    if ("name:ar" in osm_target["tags"] or "place" in osm_target["tags"]
            or "official_name" in osm_target["tags"]):
        raise ValueError("Target now has stronger locality naming evidence; review decision")
    if (osm_sector["tags"].get("ref:tn:codegeo") != "117151"
            or osm_sector["tags"].get("name:ar") != "المرسى الشاطئ"):
        raise ValueError("Pinned OSM parent sector tags changed")
    if any(canonical_sha(feature["geometry"]) != feature["canonicalGeometrySha256"]
           for feature in snapshot["features"]):
        raise ValueError("Embedded source geometry digest mismatch")

    sys.path.insert(0, str(REPO / "scripts"))
    sys.path.insert(0, str(REPO / "scripts/neighborhoods"))
    from build_best_effort_geometry_overlay import unpack  # noqa: E402

    target_geometry = unpack(target, bin_raw, metadata["coordinateScale"])
    current_sector_geometry = unpack(parent, bin_raw, metadata["coordinateScale"])
    raw_osm_target = shape(osm_target["geometry"])
    raw_osm_sector = shape(osm_sector["geometry"])
    if not target_geometry.is_valid or not raw_osm_target.is_valid or not raw_osm_sector.is_valid:
        raise ValueError("A pinned target/parent geometry is invalid")
    transformer = Transformer.from_crs("EPSG:4326", "EPSG:32632", always_xy=True).transform
    projected_target = transform(transformer, target_geometry)
    projected_current_sector = transform(transformer, current_sector_geometry)
    projected_raw_target = transform(transformer, raw_osm_target)
    projected_raw_sector = transform(transformer, raw_osm_sector)
    overlap_area = projected_target.intersection(projected_current_sector).area
    overlap_percent = overlap_area / projected_target.area * 100.0
    raw_overlap_percent = (projected_raw_target.intersection(projected_raw_sector).area
                           / projected_raw_target.area * 100.0)
    if abs(overlap_percent - EXPECTED_TUNIS_OVERLAP_PERCENT) > 1e-7:
        raise ValueError(f"Current-sector overlap changed: {overlap_percent:.12f}%")
    if overlap_percent >= 100.0 or not projected_raw_sector.covers(projected_raw_target):
        raise ValueError("Expected current partial overlap and historical OSM containment changed")

    extract_path = HERE / "prior-triage-extract.json"
    extract_raw = extract_path.read_bytes()
    extract = json.loads(extract_raw)
    if extract.get("sourceReportSha256") != EXPECTED_TRIAGE_SHA:
        raise ValueError("Prior triage report pin changed")
    triage_target = extract.get("targetEvidence", {})
    if (triage_target.get("id") != TARGET_ID
            or abs(triage_target.get("geometryAudit", {}).get("currentTargetOverlapPercent", 0.0)
                   - overlap_percent) > 1e-7):
        raise ValueError("Prior triage extract does not match recomputed target geometry")
    if sha256(TEMPLATE.read_bytes()) != EXPECTED_TEMPLATE_SHA:
        raise ValueError("Reviewed Qammart combined manifest changed")
    template = json.loads(TEMPLATE.read_bytes())
    if (len(template.get("sources", [])) != 3 or len(template.get("records", [])) != 3
            or len(template.get("exclusions", [])) != 3):
        raise ValueError("Qammart manifest no longer contains the three reviewed replacements/exclusions")

    review = {
        "schemaVersion": 1,
        "date": "2026-09-24",
        "decision": "retire_baie_des_singes_as_unsupported_separate_selectable_locality",
        "reason": "The 0.0074 km² object is a Latin-only OSM landuse=residential polygon, with no Arabic name, place=neighbourhood tag, or official_name tag. It has no INS sector identity; its administrative parent is official sector 117151 المرسى الشاطئ. The current installed official-sector geometry overlaps about 98.68% of the target polygon but does not fully contain it. No distinct locality identity or defensible Arabic primary is supported by reviewed sources.",
        "uncertainty": "This review establishes the pinned OSM tags/geometry and spatial overlap only; it does not establish what is on the parcel or a legal neighborhood boundary. The older pinned OSM parent-sector geometry covered the polygon fully, while the current installed ISIE boundary covers 98.6776%; do not claim full containment. A locality-specific official or municipal source could change this decision.",
        "inputAssetSha256": {"neighborhoodsJson": EXPECTED_JSON_SHA, "neighborhoodsBin": EXPECTED_BIN_SHA},
        "priorTriage": {"file": extract["sourceReport"], "sha256": EXPECTED_TRIAGE_SHA,
                        "embeddedExtractFile": "prior-triage-extract.json",
                        "embeddedExtractSha256": sha256(extract_raw)},
        "osmSource": {
            "snapshot": snapshot["snapshot"], "pbfSha256": EXPECTED_PBF_SHA,
            "extractedAreasSha256": EXPECTED_OSM_AREAS_SHA,
            "embeddedSnapshotFile": "osm-tag-snapshot.json", "embeddedSnapshotSha256": sha256(snapshot_raw),
            "targetTags": osm_target["tags"], "targetTagsSha256": canonical_sha(osm_target["tags"]),
            "targetSourceGeometrySha256": osm_target["canonicalGeometrySha256"],
            "targetSourceRecordSha256": osm_target["canonicalRecordSha256"],
        },
        "baselineTarget": {
            "id": TARGET_ID, "expectedCurrent": expected_current,
            "expectedCurrentSha256": canonical_sha(expected_current),
            "catalogMetadataWithoutOffsetsSha256": target_metadata_sha,
            "packedGeometrySha256": packed_sha, "packedGeometryBytes": len(packed),
            "appAreaKm2": target["areaKm2"], "projectedPackedAreaKm2": projected_target.area / 1_000_000,
        },
        "officialParent": {
            "id": SECTOR_ID, "officialCode": "117151", "nameArabic": "المرسى الشاطئ",
            "administrativeDelegationCode": "1171", "administrativeDelegationArabic": "المرسى",
            "currentCatalogSourceId": parent["sourceId"],
            "sourceTags": osm_sector["tags"],
            "sourceTagsSha256": canonical_sha(osm_sector["tags"]),
            "currentSectorPackedGeometrySha256": sha256(
                bin_raw[parent["offset"]:parent["offset"] + parent["length"]]),
        },
        "geometryReview": {
            "projection": "EPSG:32632",
            "currentInstalledTargetIntersectionKm2": overlap_area / 1_000_000,
            "currentInstalledTargetOverlapPercent": overlap_percent,
            "currentInstalledParentCoversTarget": projected_current_sector.covers(projected_target),
            "pinnedOldOsmTargetAreaKm2": projected_raw_target.area / 1_000_000,
            "pinnedOldOsmParentIntersectionKm2": (
                projected_raw_target.intersection(projected_raw_sector).area / 1_000_000),
            "pinnedOldOsmTargetOverlapPercent": raw_overlap_percent,
            "pinnedOldOsmParentCoversTarget": projected_raw_sector.covers(projected_raw_target),
        },
        "retiredLocalityIdsPreserved": ["osm:way:825272931", "osm:way:825272932", "osm:way:825505201"],
    }
    review_path = HERE / "review-evidence.json"
    review_raw = json_bytes(review)
    review_path.write_bytes(review_raw)

    manifest = json.loads(json.dumps(template))
    manifest.setdefault("evidencePins", {})["baieDesSingesReview"] = {
        "file": f"scripts/neighborhoods/exclusion-reviews/{HERE.name}/review-evidence.json",
        "sha256": sha256(review_raw),
    }
    manifest.setdefault("evidencePins", {})["baieDesSingesPriorTriage"] = {
        "file": f"scripts/neighborhoods/exclusion-reviews/{HERE.name}/prior-triage-extract.json",
        "sha256": sha256(extract_raw),
    }
    manifest["exclusions"].append({
        "id": TARGET_ID,
        "expectedCurrent": expected_current,
        "packedGeometrySha256": packed_sha,
        "reason": "Tiny Latin-only OSM residential parcel lacks separate neighborhood identity or Arabic primary; current sector 117151 overlaps 98.68%, not full containment.",
        "evidence": [
            {"file": f"scripts/neighborhoods/exclusion-reviews/{HERE.name}/review-evidence.json",
             "sha256": sha256(review_raw)},
            {"file": f"scripts/neighborhoods/exclusion-reviews/{HERE.name}/osm-tag-snapshot.json",
             "sha256": sha256(snapshot_raw)},
            {"file": f"scripts/neighborhoods/exclusion-reviews/{HERE.name}/prior-triage-extract.json",
             "sha256": sha256(extract_raw)},
            {"file": f"scripts/neighborhoods/exclusion-reviews/{HERE.name}/build_evidence.py",
             "sha256": sha256(Path(__file__).read_bytes())},
        ],
    })
    manifest_path = HERE / "geometry-overlay-manifest.json"
    manifest_raw = json_bytes(manifest)
    manifest_path.write_bytes(manifest_raw)
    print(json.dumps({
        "reviewEvidenceSha256": sha256(review_raw),
        "sourceSnapshotSha256": sha256(snapshot_raw),
        "priorTriageExtractSha256": sha256(extract_raw),
        "builderSha256": sha256(Path(__file__).read_bytes()),
        "manifestSha256": sha256(manifest_raw),
        "sources": len(manifest["sources"]), "replacements": len(manifest["records"]),
        "exclusions": [row["id"] for row in manifest["exclusions"]],
        "baieTargetPackedGeometrySha256": packed_sha,
        "currentInstalledSectorOverlapPercent": round(overlap_percent, 8),
        "currentParentCoversTarget": projected_current_sector.covers(projected_target),
        "rawOsmParentCoversTarget": projected_raw_sector.covers(projected_raw_target),
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
