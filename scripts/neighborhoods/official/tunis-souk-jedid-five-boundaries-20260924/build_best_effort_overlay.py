#!/usr/bin/env python3
"""Rebuild the pinned five-record best-effort overlay into an explicit output directory.

The pre-overlay catalog is read from the compressed, hash-pinned copies beside
this script. Output is never written to app assets. The utility makes no claim
of administrative equivalence or redistribution permission.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
import gzip
import hashlib
import json
import math
from pathlib import Path
import subprocess
import struct
import sys

from shapely import set_precision, unary_union
from shapely.geometry import Point, shape


HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
APP_ASSETS = REPO / "android-app/app/src/main/assets"
BASELINE_JSON_SHA256 = "75322d6eab8acc95201aa80ae46512a82b1900b1e37801e54dcfe92b175287c2"
BASELINE_BIN_SHA256 = "daa6a02848078697f1b5ff71002e6797f5081c6cf2365369b44d530c06bc2fb1"
SOURCE_GEOJSON_SHA256 = "225c7633750678391d0f0adc211f0273bb9baf81e7bb1a0c2ef19e4f467fa92e"
EXTRACTION_GEOJSON_SHA256 = "ab858a182a2dc125a4e206d88d9fdbe3b2ad556c3c3f02ef5b6e29f63be3c878"
PRE_CORRECTION_OVERLAY_JSON_SHA256 = "6dddbc57ecdd86755777fb19ea4be72c687f9b1282fc7d0bb406e1a8fae7cbf9"
OVERLAY_JSON_SHA256 = "c7daff647e40080dad98d4296b90f67e17198fb2349051f159a38a0ba136f5db"
OVERLAY_BIN_SHA256 = "8c0c1a6c5d2bc310dae01a487a47ed5967449f8826730959bdbcb0afdfb5c94e"
NAME_CORRECTION_REVIEW = HERE / "tunis-primary-name-corrections-review.json"
NAME_CORRECTION_REVIEW_SHA256 = "f699e05118cc11e8fc4202e7befb85879ea146f1824c9dd54b95304c0ee0d313"
ACCEPTANCE_PATH = HERE / "first-release-acceptance.json"
NEW_SOURCE_ID = "isie-best-effort-imada-tunis-souk-jedid-five-boundaries-20260924"
SOURCE_MANIFEST_RELATIVE = Path("scripts/neighborhoods/reviewed-boundaries.json")
BASELINE_JSON_GZIP = HERE / "pre-overlay-neighborhoods.json.gz"
BASELINE_BIN_GZIP = HERE / "pre-overlay-neighborhoods.bin.gz"
EXTRACTION_GEOJSON = HERE / "independent-selected-geometries.geojson"
LEGACY_DRAWING_INDEX = {"115653": 226, "115652": 302, "115658": 233, "435958": 4, "435955": None}
CAVEAT_BY_CODE = {
    "115653": "Drawing 226 has a target label center 74.2 m outside the outline; the same path recurs on the Ahmed map, so association remains uncertain.",
    "115652": "Drawing 302 label center is inside; electoral circle to current imada equivalence is unverified.",
    "115658": "Drawing 233 is inferred from the map index/title and fit to the current sector; no target label center falls inside.",
    "435958": "Drawing 4 label center is inside and the outline recurs on the Rmeiliya map; current legal/survey boundary is unverified.",
    "435955": "Derived from the pinned old OSM Rmeiliya polygon minus the Ouled candidate; not the native ISIE Rmeiliya outline.",
}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def unpack(feature: dict, blob: bytes, scale: int):
    from shapely.geometry import MultiPolygon, Polygon

    pos = feature["offset"]

    def integer() -> int:
        nonlocal pos
        value = struct.unpack_from(">i", blob, pos)[0]
        pos += 4
        return value

    polygons = []
    for _ in range(integer()):
        rings = []
        for _ in range(integer()):
            rings.append([(integer() / scale, integer() / scale) for _ in range(integer())])
        polygons.append(Polygon(rings[0], rings[1:]))
    if pos != feature["offset"] + feature["length"]:
        raise ValueError(f"Packed geometry length mismatch for {feature.get('id', 'country')}")
    return polygons[0] if len(polygons) == 1 else MultiPolygon(polygons)


def geodesic_proxy_area_km2(geometry) -> float:
    point = geometry.representative_point()
    return geometry.area * 111.32**2 * math.cos(math.radians(point.y))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True,
                        help="Required staging output directory outside the repository")
    args = parser.parse_args()

    output = args.output_dir.resolve()
    if output == REPO or output.is_relative_to(REPO):
        raise ValueError("Output must be outside the repository; app assets and source files are read-only")
    assets = APP_ASSETS
    baseline_json_raw = gzip.decompress(BASELINE_JSON_GZIP.read_bytes())
    baseline_bin = gzip.decompress(BASELINE_BIN_GZIP.read_bytes())
    source_manifest_path = (REPO / SOURCE_MANIFEST_RELATIVE).resolve()
    source_manifest = json.loads(source_manifest_path.read_text(encoding="utf-8"))
    source_matches = [source for source in source_manifest.get("sources", [])
                      if source.get("id") == NEW_SOURCE_ID]
    if len(source_matches) != 1:
        raise ValueError("Installed boundary manifest does not contain one pinned five-record source")
    source_entry = source_matches[0]
    source_path = (source_manifest_path.parent / source_entry["file"]).resolve()
    if not source_path.is_relative_to(source_manifest_path.parent.resolve()):
        raise ValueError("Pinned candidate-source path escapes its manifest directory")
    geojson_raw = source_path.read_bytes()
    if source_entry.get("sha256") != SOURCE_GEOJSON_SHA256:
        raise ValueError("Installed source manifest hash changed")

    name_review_raw = NAME_CORRECTION_REVIEW.read_bytes()
    if sha256(name_review_raw) != NAME_CORRECTION_REVIEW_SHA256:
        raise ValueError("Pinned Tunis name-correction review changed")
    name_review = json.loads(name_review_raw)
    if name_review.get("status") != "accepted_for_name_only_projection":
        raise ValueError("Tunis name-correction review is not accepted")
    name_records = name_review.get("acceptedMappings", [])
    name_record_ids = [record.get("appId") for record in name_records]
    name_record_codes = [record.get("insCode") for record in name_records]
    if (len(name_records) != 14 or len(set(name_record_ids)) != 14
            or len(set(name_record_codes)) != 14):
        raise ValueError("Expected 14 unique reviewed Tunis name/code mappings")
    review_overlay_pin = name_review.get("sourcePins", {}).get("currentOverlayBeforeNameCorrections", {})
    if (review_overlay_pin.get("builderOutputSha256") != PRE_CORRECTION_OVERLAY_JSON_SHA256
            or review_overlay_pin.get("binarySha256") != OVERLAY_BIN_SHA256):
        raise ValueError("Name-correction review does not pin the accepted pre-correction overlay")

    # Cross-check the installed source against the independent task extraction.
    extraction_raw = EXTRACTION_GEOJSON.read_bytes()
    if sha256(extraction_raw) != EXTRACTION_GEOJSON_SHA256:
        raise ValueError("Task-work extraction pin changed")
    extraction = json.loads(extraction_raw)
    actual_pins = {
        "neighborhoodsJson": sha256(baseline_json_raw),
        "neighborhoodsBin": sha256(baseline_bin),
        "installedCandidateSourceGeojson": sha256(geojson_raw),
        "independentExtractionGeojson": sha256(extraction_raw),
    }
    expected_pins = {
        "neighborhoodsJson": BASELINE_JSON_SHA256,
        "neighborhoodsBin": BASELINE_BIN_SHA256,
        "installedCandidateSourceGeojson": SOURCE_GEOJSON_SHA256,
        "independentExtractionGeojson": EXTRACTION_GEOJSON_SHA256,
    }
    if actual_pins != expected_pins:
        raise ValueError(f"Input pin mismatch: {actual_pins}")

    scripts_dir = REPO / "scripts"
    sys.path.insert(0, str(scripts_dir))
    from generate_neighborhoods import (  # repository compiler helpers, read-only
        SCALE,
        GRID,
        available_timetables,
        detect_conflicts,
        distance,
        packed_geometry_bytes,
    )

    sys.path.insert(0, str(scripts_dir / "neighborhoods"))
    from audit_catalog import GpsLabelAudit

    metadata = json.loads(baseline_json_raw)
    geojson = json.loads(geojson_raw)
    if metadata.get("coordinateScale") != SCALE or metadata.get("gridSize") != GRID:
        raise ValueError("Compiler and app asset coordinate scales differ")
    if geojson.get("type") != "FeatureCollection" or len(geojson.get("features", [])) != 5:
        raise ValueError("Expected the pinned five-feature staging GeoJSON")

    baseline_features = metadata["features"]
    boundary_features = [feature for feature in baseline_features if feature.get("hasBoundary")]
    point_features = [feature for feature in baseline_features if not feature.get("hasBoundary")]
    if baseline_features != boundary_features + point_features:
        raise ValueError("Pinned catalog no longer groups boundary features before point features")
    if baseline_bin[:8] != b"NPOL\x00\x00\x00\x01":
        raise ValueError("Unexpected NPOL header")
    if metadata["country"]["offset"] + metadata["country"]["length"] != len(baseline_bin):
        raise ValueError("Unexpected trailing bytes after pinned country geometry")

    selected = geojson["features"]
    candidate_specs = [
        {"index": 0, "officialCode": "115653", "id": "isie:sector:115653", "action": "add", "name": "حي بن خلدون الثاني",
         "nameFr": "Cite ibn khaldoun ii", "governorateId": 342, "contextId": "osm:relation:7201523"},
        {"index": 1, "officialCode": "115652", "id": "osm:relation:7201523", "action": "replace", "sourceId": NEW_SOURCE_ID},
        {"index": 2, "officialCode": "115658", "id": "osm:relation:7201520", "action": "replace", "sourceId": NEW_SOURCE_ID},
        {"index": 3, "officialCode": "435958", "id": "isie:sector:435958", "action": "add", "name": "أولاد الفالح",
         "nameFr": "Ouled el faleh", "governorateId": 355, "contextId": "osm:relation:7169675"},
        {"index": 4, "officialCode": "435955", "id": "osm:relation:7169675", "action": "replace", "sourceId": NEW_SOURCE_ID},
    ]
    if len(boundary_features) != 2578 or len(metadata.get("conflicts", [])) != 455:
        raise ValueError("Pinned catalog inventory changed")
    by_id = {feature["id"]: index for index, feature in enumerate(boundary_features)}
    for spec in candidate_specs:
        properties = selected[spec["index"]].get("properties", {})
        if (properties.get("officialCode") != spec["officialCode"]
                or properties.get("sourceId") != NEW_SOURCE_ID):
            raise ValueError(f"Candidate source record ordering/identity changed: {spec['id']}")
        if spec["action"] == "replace" and spec["id"] not in by_id:
            raise ValueError(f"Missing replacement ID: {spec['id']}")
        if spec["action"] == "add" and any(f["id"] == spec["id"] for f in baseline_features):
            raise ValueError(f"New ID already exists: {spec['id']}")

    old_geometries = [unpack(feature, baseline_bin, SCALE) for feature in boundary_features]
    country = unpack(metadata["country"], baseline_bin, SCALE)
    gov_path = assets / "gouvernorats.json"
    runtime_governors = json.loads(gov_path.read_text(encoding="utf-8"))["gouvernorats"]
    timetables, rejected_timetables = available_timetables(runtime_governors, assets)
    if rejected_timetables is None:
        raise ValueError("Unexpected timetable availability result")

    staged_features = [dict(feature) for feature in boundary_features]
    staged_geometries = list(old_geometries)
    original_target_rows = {}
    geometry_methods = {}

    for spec in candidate_specs:
        source_feature = selected[spec["index"]]
        source_geometry = shape(source_feature["geometry"])
        geometry = set_precision(source_geometry, 1 / SCALE)
        if (geometry.is_empty or geometry.geom_type not in ("Polygon", "MultiPolygon")
                or not geometry.is_valid):
            raise ValueError(f"Invalid staged geometry after app precision: {spec['id']}")
        properties = source_feature["properties"]
        legacy_properties = extraction["features"][spec["index"]]["properties"]
        if legacy_properties.get("drawingIndex") != LEGACY_DRAWING_INDEX[spec["officialCode"]]:
            raise ValueError(f"Task extraction drawing index changed for code {spec['officialCode']}")
        legacy_geometry = set_precision(shape(extraction["features"][spec["index"]]["geometry"]), 1 / SCALE)
        if not geometry.equals(legacy_geometry):
            raise ValueError(f"Pinned installed candidate no longer matches the independent extraction: {spec['id']}")
        geometry_methods[spec["id"]] = {
            "drawingIndex": LEGACY_DRAWING_INDEX[spec["officialCode"]],
            "officialCode": properties.get("officialCode"),
            "sourceId": properties.get("sourceId"),
            "sourcePdfSha256": properties.get("sourcePdfSha256"),
            "sourcePdf": properties.get("sourcePdfURL"),
            "sourceGeometrySha256": properties.get("sourceGeometrySha256"),
            "identityCaveat": CAVEAT_BY_CODE[spec["officialCode"]],
            "geometryMethod": properties.get("geometryMethod"),
            "areaKm2AtAppPrecision": geodesic_proxy_area_km2(geometry),
            "valid": geometry.is_valid,
            "componentCount": 1 if geometry.geom_type == "Polygon" else len(geometry.geoms),
        }
        if geometry.geom_type == "MultiPolygon":
            component_areas = sorted((geodesic_proxy_area_km2(part) for part in geometry.geoms), reverse=True)
            geometry_methods[spec["id"]]["componentAreaKm2AtAppPrecisionDescending"] = component_areas
            geometry_methods[spec["id"]]["largestComponentAreaKm2AtAppPrecision"] = component_areas[0]
            geometry_methods[spec["id"]]["smallerComponentsAreaKm2AtAppPrecision"] = sum(component_areas[1:])
        if spec["action"] == "add":
            context = next(feature for feature in boundary_features if feature["id"] == spec["contextId"])
            row = {
                "id": spec["id"],
                "sourceId": NEW_SOURCE_ID,
                "name": spec["name"],
                "aliases": [spec["nameFr"]],
                "kind": "sector",
                "parentName": context["parentName"],
                "contextAliases": list(context.get("contextAliases", [])),
                "governorateId": spec["governorateId"],
                "hasBoundary": True,
                "pickerGroupId": spec["id"],
            }
            destination_index = len(staged_features)
            staged_features.append(row)
            staged_geometries.append(geometry)
            by_id[spec["id"]] = destination_index
        else:
            destination_index = by_id[spec["id"]]
            row = staged_features[destination_index]
            original_target_rows[spec["id"]] = dict(row)
            row["sourceId"] = spec["sourceId"]
            staged_geometries[destination_index] = geometry

        row = staged_features[destination_index]
        representative = geometry.representative_point()
        lat, lng = round(representative.y, 7), round(representative.x, 7)
        nearest = min(timetables, key=lambda item: (distance(lat, lng, item), item["id"]))
        old_delegation_id = original_target_rows.get(spec["id"], {}).get("delegationId")
        row.update({
            "lat": lat,
            "lng": lng,
            "delegationId": nearest["id"],
            "bbox": list(geometry.bounds),
            "areaKm2": geometry.area * 111.32**2 * math.cos(math.radians(representative.y)),
        })
        if spec["action"] == "add":
            row["governorateId"] = spec["governorateId"]
        geometry_methods[spec["id"]].update({
            "action": spec["action"],
            "representativeLat": lat,
            "representativeLng": lng,
            "nearestPrayerDelegationId": nearest["id"],
            "nearestPrayerDelegationNameAr": nearest["nomAr"],
            "nearestPrayerDistanceKm": distance(lat, lng, nearest),
            "previousPrayerDelegationId": old_delegation_id,
        })

    # Keep the source audit trail explicit and separate for native and derived geometry.
    metadata["sources"] = dict(metadata["sources"])
    metadata["sources"][NEW_SOURCE_ID] = {
        key: value for key, value in source_entry.items() if key not in ("file", "records")
    }

    # Serialize only target payloads; all other source geometry slices remain raw copies.
    output.mkdir(parents=True, exist_ok=True)
    staged_assets = output / "assets"
    staged_assets.mkdir(parents=True, exist_ok=True)
    rebuilt_bin = bytearray(baseline_bin[:8])
    untouched_slice_checks = []
    target_ids = {spec["id"] for spec in candidate_specs}
    for feature, geometry in zip(staged_features, staged_geometries):
        old_index = next((i for i, original in enumerate(boundary_features) if original["id"] == feature["id"]), None)
        if feature["id"] in target_ids:
            payload = packed_geometry_bytes(geometry)
        else:
            original = boundary_features[old_index]
            payload = baseline_bin[original["offset"]:original["offset"] + original["length"]]
            if not payload:
                raise ValueError(f"Empty untouched geometry payload: {feature['id']}")
            untouched_slice_checks.append({"id": feature["id"], "length": len(payload), "sha256": sha256(payload)})
        feature["offset"] = len(rebuilt_bin)
        feature["length"] = len(payload)
        rebuilt_bin.extend(payload)
    country_payload = baseline_bin[metadata["country"]["offset"]:
                                   metadata["country"]["offset"] + metadata["country"]["length"]]
    metadata["country"] = {"offset": len(rebuilt_bin), "length": len(country_payload)}
    rebuilt_bin.extend(country_payload)

    all_features = staged_features + point_features
    cell_map = defaultdict(list)
    for index, (feature, geometry) in enumerate(zip(staged_features, staged_geometries)):
        min_x, min_y, max_x, max_y = geometry.bounds
        for y in range(math.floor(min_y / GRID), math.floor(max_y / GRID) + 1):
            for x in range(math.floor(min_x / GRID), math.floor(max_x / GRID) + 1):
                cell_map[f"{y}:{x}"].append(index)
    metadata["cells"] = {key: cell_map[key] for key in sorted(cell_map)}
    conflicts = detect_conflicts(staged_features, staged_geometries)
    metadata["conflicts"] = conflicts
    metadata["features"] = all_features

    # Pin the five-boundary overlay before the reviewed name-only layer.
    pre_correction_json_raw = (json.dumps(metadata, ensure_ascii=False, separators=(",", ":"), allow_nan=False) + "\n").encode("utf-8")
    pre_correction_bin_raw = bytes(rebuilt_bin)
    pre_correction_pins = {
        "neighborhoodsJson": sha256(pre_correction_json_raw),
        "neighborhoodsBin": sha256(pre_correction_bin_raw),
    }
    if pre_correction_pins != {
        "neighborhoodsJson": PRE_CORRECTION_OVERLAY_JSON_SHA256,
        "neighborhoodsBin": OVERLAY_BIN_SHA256,
    }:
        raise ValueError(f"Five-boundary overlay baseline changed before name corrections: {pre_correction_pins}")

    original_feature_by_id = {feature["id"]: feature for feature in baseline_features}
    pre_correction_feature_by_id = {feature["id"]: feature for feature in all_features}
    target_ids = {spec["id"] for spec in candidate_specs}
    name_correction_changes = []
    for record in name_records:
        identifier = record.get("appId")
        if identifier in target_ids or identifier not in original_feature_by_id:
            raise ValueError(f"Name correction overlaps boundary changes or misses baseline: {identifier}")
        before = original_feature_by_id[identifier]
        feature = pre_correction_feature_by_id.get(identifier)
        if feature is None:
            raise ValueError(f"Name correction feature is missing: {identifier}")
        if any(before.get(key) != feature.get(key) for key in set(before) | set(feature) if key != "offset"):
            raise ValueError(f"Name correction target has unrelated overlay metadata changes: {identifier}")
        expected_fields = {
            "name": record.get("previousName"),
            "aliases": record.get("previousAliases"),
            "parentName": record.get("parentName"),
            "sourceId": record.get("sourceId"),
            "governorateId": record.get("governorateId"),
            "delegationId": record.get("prayerDelegationId"),
            "pickerGroupId": record.get("pickerGroupId"),
            "hasBoundary": record.get("geometryHasBoundary"),
        }
        if any(feature.get(key) != value for key, value in expected_fields.items()):
            raise ValueError(f"Name correction baseline identity/parent differs from review: {identifier}")
        if (record.get("insCode") != record.get("expectedOsmCodegeo")
                or record.get("insDelegation") != record.get("ministryDelegation")
                or record.get("parentName", "").removeprefix("معتمدية ") != record.get("ministryDelegation")
                or not str(record.get("insCode", "")).isdigit()):
            raise ValueError(f"Name correction code/parent evidence is inconsistent: {identifier}")
        baseline_payload = baseline_bin[before["offset"]:before["offset"] + before["length"]]
        overlay_payload = pre_correction_bin_raw[feature["offset"]:feature["offset"] + feature["length"]]
        if (not baseline_payload or baseline_payload != overlay_payload
                or sha256(overlay_payload) != record.get("packedGeometrySha256")):
            raise ValueError(f"Name correction geometry is not the reviewed unchanged payload: {identifier}")
        pre_correction_feature = dict(feature)
        old_name = feature["name"]
        new_name = record.get("ministryName")
        old_aliases = list(feature.get("aliases", []))
        if not isinstance(new_name, str) or not new_name or old_name == new_name:
            raise ValueError(f"Invalid or unnecessary name correction for {identifier}")
        new_aliases = list(dict.fromkeys([*old_aliases, old_name]))
        feature["name"] = new_name
        feature["aliases"] = new_aliases
        changed_fields = {key for key in set(pre_correction_feature) | set(feature)
                          if pre_correction_feature.get(key) != feature.get(key)}
        if not changed_fields.issubset({"name", "aliases"}) or old_name not in new_aliases or len(new_aliases) != len(set(new_aliases)):
            raise ValueError(f"Name correction changed non-name metadata or failed alias preservation: {identifier}")
        name_correction_changes.append({
            "id": identifier,
            "insCode": record["insCode"],
            "previousName": old_name,
            "ministryName": new_name,
            "previousAliases": old_aliases,
            "aliasesAfter": new_aliases,
            "changedFields": sorted(changed_fields),
            "packedGeometrySha256": sha256(overlay_payload),
        })

    preserved_identity_checks = []
    for record in name_review.get("preservedDistinctIdentities", []):
        identifier = record.get("appId")
        feature = pre_correction_feature_by_id.get(identifier)
        if (feature is None or identifier in name_record_ids
                or record.get("insCode") != record.get("expectedOsmCodegeo")):
            raise ValueError(f"Protected distinct identity is missing, targeted, or has inconsistent code: {identifier}")
        protected_fields = {
            "name": record.get("name"),
            "aliases": record.get("aliases"),
            "parentName": record.get("parentName"),
            "governorateId": record.get("governorateId"),
            "sourceId": record.get("sourceId"),
            "delegationId": record.get("prayerDelegationId"),
            "pickerGroupId": record.get("pickerGroupId"),
        }
        if any(feature.get(key) != value for key, value in protected_fields.items()):
            raise ValueError(f"Protected distinct identity changed: {identifier}")
        payload = pre_correction_bin_raw[feature["offset"]:feature["offset"] + feature["length"]]
        if not payload or sha256(payload) != record.get("packedGeometrySha256"):
            raise ValueError(f"Protected distinct identity geometry changed: {identifier}")
        preserved_identity_checks.append({"id": identifier, "insCode": record.get("insCode"), "unchanged": True})

    staged_json_raw = (json.dumps(metadata, ensure_ascii=False, separators=(",", ":"), allow_nan=False) + "\n").encode("utf-8")
    staged_bin_raw = pre_correction_bin_raw
    output_pins = {"neighborhoodsJson": sha256(staged_json_raw), "neighborhoodsBin": sha256(staged_bin_raw)}
    if output_pins != {"neighborhoodsJson": OVERLAY_JSON_SHA256, "neighborhoodsBin": OVERLAY_BIN_SHA256}:
        raise ValueError(f"Rebuilt overlay differs from the verified output pins: {output_pins}")
    acceptance = json.loads(ACCEPTANCE_PATH.read_text(encoding="utf-8"))
    accepted_names = acceptance.get("tunisNameCorrections", {})
    if (accepted_names.get("reviewFile") != NAME_CORRECTION_REVIEW.name
            or accepted_names.get("reviewSha256") != NAME_CORRECTION_REVIEW_SHA256
            or accepted_names.get("recordCount") != len(name_records)
            or accepted_names.get("ids") != name_record_ids
            or acceptance.get("installedAssets", {}).get("neighborhoodsJsonSha256") != output_pins["neighborhoodsJson"]
            or acceptance.get("installedAssets", {}).get("neighborhoodsBinSha256") != output_pins["neighborhoodsBin"]):
        raise ValueError("First-release acceptance does not pin this reviewed name-correction output")
    (staged_assets / "neighborhoods.json").write_bytes(staged_json_raw)
    (staged_assets / "neighborhoods.bin").write_bytes(staged_bin_raw)

    # Assert all non-target geometry byte slices and point-only records are exact copies.
    output_feature_by_id = {feature["id"]: feature for feature in all_features}
    for feature in boundary_features:
        if feature["id"] in target_ids:
            continue
        output_feature = output_feature_by_id[feature["id"]]
        before = baseline_bin[feature["offset"]:feature["offset"] + feature["length"]]
        after = staged_bin_raw[output_feature["offset"]:output_feature["offset"] + output_feature["length"]]
        if before != after:
            raise ValueError(f"Untouched geometry bytes changed for {feature['id']}")
    if point_features != [output_feature_by_id[feature["id"]] for feature in point_features]:
        raise ValueError("Point-only feature metadata changed")
    if staged_bin_raw[metadata["country"]["offset"]:metadata["country"]["offset"] + metadata["country"]["length"]] != country_payload:
        raise ValueError("Pinned country geometry bytes changed")

    # For each added sector, test a safe interior point outside all sector-overlap conflicts.
    label_model = GpsLabelAudit(staged_features, staged_geometries, country, conflicts)
    added_label_checks = []
    for identifier in ("isie:sector:115653", "isie:sector:435958"):
        index = by_id[identifier]
        conflicting_ids = {other for conflict in conflicts if identifier in conflict["ids"]
                           for other in conflict["ids"] if other != identifier}
        overlap_area = sum(
            staged_geometries[index].intersection(staged_geometries[by_id[other]]).area
            for other in conflicting_ids
        )
        safe_region = (staged_geometries[index].difference(
            unary_union([staged_geometries[by_id[other]] for other in conflicting_ids]))
            if conflicting_ids else staged_geometries[index])
        if safe_region.is_empty or safe_region.area <= 0:
            raise ValueError(f"No positive-area conflict-free interior for {identifier}")
        point = safe_region.representative_point()
        evaluation = label_model.evaluate(point.y, point.x)
        winner_index = evaluation["winner"]
        winner_id = staged_features[winner_index]["id"] if winner_index is not None else None
        added_label_checks.append({
            "id": identifier,
            "pointLat": point.y,
            "pointLng": point.x,
            "strictlyInsideCandidate": staged_geometries[index].contains(point),
            "outsideAllPairwiseSectorOverlapAreas": not any(
                staged_geometries[by_id[other]].contains(point) for other in conflicting_ids),
            "candidateOverlapAreaKm2WithSectorPeers": overlap_area * 111.32**2 * math.cos(math.radians(point.y)),
            "conflictingSectorIds": sorted(conflicting_ids),
            "winnerId": winner_id,
            "winnerName": staged_features[winner_index]["name"] if winner_index is not None else None,
            "candidateWins": winner_id == identifier,
            "candidateInRuntimeCandidates": any(
                staged_features[candidate]["id"] == identifier for candidate in evaluation["candidates"]),
            "runtimeExcludedConflictIds": [staged_features[candidate]["id"] for candidate in evaluation["excluded"]],
        })

    # Make independent table of byte-preservation, source mapping and derived counts.
    replacement_metadata_changes = []
    allowed_fields = {"sourceId", "lat", "lng", "delegationId", "bbox", "areaKm2", "offset", "length"}
    for identifier, before in original_target_rows.items():
        after = output_feature_by_id[identifier]
        changed = {key: {"before": before.get(key), "after": after.get(key)}
                   for key in sorted(set(before) | set(after)) if before.get(key) != after.get(key)}
        unexpected = set(changed) - allowed_fields
        if unexpected:
            raise ValueError(f"Unexpected metadata edits for {identifier}: {sorted(unexpected)}")
        replacement_metadata_changes.append({"id": identifier, "changes": changed})

    report = {
        "schemaVersion": 1,
        "status": "reproducible staging overlay; not installed",
        "baselinePins": actual_pins,
        "installedCandidateSource": {"file": source_entry["file"], "sha256": SOURCE_GEOJSON_SHA256},
        "independentExtractionSha256": EXTRACTION_GEOJSON_SHA256,
        "selectedDrawingIndices": {spec["id"]: geometry_methods[spec["id"]]["drawingIndex"]
                                    for spec in candidate_specs},
        "baselineCounts": {"featureCount": len(baseline_features), "boundaryFeatureCount": len(boundary_features),
                           "conflictCount": len(json.loads(baseline_json_raw)["conflicts"]),
                           "cellCount": len(json.loads(baseline_json_raw)["cells"])},
        "prayerSourceAssignment": {
            "method": "Compiler available_timetables + haversine distance, tie-broken by source ID",
            "availableCurrentTimetableCount": len(timetables),
            "meaning": "delegationId is the nearest prayer-data source and can differ from the administrative parent",
        },
        "stagedCounts": {"featureCount": len(all_features), "boundaryFeatureCount": len(staged_features),
                         "conflictCount": len(conflicts), "cellCount": len(metadata["cells"])},
        "delta": {"addedIds": ["isie:sector:115653", "isie:sector:435958"],
                  "replacedIds": ["osm:relation:7201523", "osm:relation:7201520", "osm:relation:7169675"],
                  "unchangedPackedGeometryCount": len(untouched_slice_checks),
                  "allUnchangedPackedGeometryBytesIdentical": True,
                  "pointOnlyFeatureMetadataIdentical": True,
                  "countryGeometryBytesIdentical": True,
                  "conflictCountDelta": len(conflicts) - len(json.loads(baseline_json_raw)["conflicts"])},
        "replacementMetadataChanges": replacement_metadata_changes,
        "tunisNameCorrections": {
            "reviewFile": NAME_CORRECTION_REVIEW.name,
            "reviewSha256": NAME_CORRECTION_REVIEW_SHA256,
            "count": len(name_correction_changes),
            "changes": name_correction_changes,
            "protectedDistinctIdentityChecks": preserved_identity_checks,
            "allPackedGeometryUnchanged": pre_correction_pins["neighborhoodsBin"] == output_pins["neighborhoodsBin"],
        },
        "affectedRows": {identifier: geometry_methods[identifier] for identifier in geometry_methods},
        "addedSectorGpsChecks": added_label_checks,
        "untouchedPackedGeometryPayloadSha256": sha256("\n".join(
            f"{r['id']}:{r['sha256']}" for r in untouched_slice_checks).encode("utf-8")),
        "sourceCaveats": [
            "ISIE GeoPDF outlines are electoral-circle map vectors; administrative equivalence is unverified.",
            "Redistribution license terms for the downloaded ISIE GeoPDFs are unverified.",
            "Ahmed Tlili and Hay Ben Khaldoun II use map/index identity with noted label or association caveats.",
            "Derived Rmeiliya is old OSM geometry minus the Ouled candidate, not an ISIE Rmeiliya boundary.",
            "This staged projection does not certify current legal or surveyed boundary accuracy.",
        ],
        "stagedAssetSha256": {
            "neighborhoodsJson": sha256(staged_json_raw),
            "neighborhoodsBin": sha256(staged_bin_raw),
        },
    }
    (output / "overlay-dry-run-report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8", newline="\n")
    (output / "overlay-dry-run-report.md").write_text(
        "# Release overlay dry-run\n\n"
        "Staging evidence only. The builder writes only to the required output directory.\n\n"
        f"- Baseline pins: metadata `{actual_pins['neighborhoodsJson']}`, binary `{actual_pins['neighborhoodsBin']}`.\n"
        f"- Canonical candidate source: `{source_entry['file']}` (`{SOURCE_GEOJSON_SHA256}`); all five shapes match the independent extraction after app precision.\n"
        f"- Staged boundary source ID: `{NEW_SOURCE_ID}` for all five rows; administrative equivalence and ISIE redistribution terms remain unverified.\n"
        f"- Output pins: metadata `{output_pins['neighborhoodsJson']}`, binary `{output_pins['neighborhoodsBin']}`.\n"
        f"- Tunis names: {len(name_correction_changes)} reviewed primary names corrected; prior Arabic names retained as aliases; all packed geometry bytes unchanged.\n"
        f"- Features: {len(baseline_features)} → {len(all_features)}; boundary polygons: {len(boundary_features)} → {len(staged_features)}.\n"
        f"- Conflicts: {report['baselineCounts']['conflictCount']} → {len(conflicts)}; cells: {report['baselineCounts']['cellCount']} → {len(metadata['cells'])}.\n"
        f"- All {len(untouched_slice_checks)} untouched packed geometry payloads, all {len(point_features)} point-only rows, and the country payload passed exact-byte checks.\n\n"
        "## Prayer source and GPS checks\n\n" +
        "| ID | Geometry representative point | Prayer source before → after | Conflict-free point winner |\n|---|---|---|---|\n" +
        "\n".join(
            f"| {identifier} | {row['representativeLat']:.7f}, {row['representativeLng']:.7f} | "
            f"{row['previousPrayerDelegationId'] or 'added'} → {row['nearestPrayerDelegationId']} "
            f"({row['nearestPrayerDelegationNameAr']}) | "
            f"{next((check['winnerId'] for check in added_label_checks if check['id'] == identifier), 'replacement')} |"
            for identifier, row in geometry_methods.items()
        ) + "\n\n" +
        "## Caveats\n\n" + "\n".join(f"- {item}" for item in report["sourceCaveats"]) + "\n",
        encoding="utf-8", newline="\n")

    # Run the independent CLI audit against the staged assets only.
    audit_dir = output / "audit"
    audit_script = scripts_dir / "neighborhoods" / "audit_catalog.py"
    subprocess.run([
        sys.executable, str(audit_script), "--assets", str(staged_assets), "--output-dir", str(audit_dir)
    ], check=True)
    audit_path = audit_dir / "neighborhood-reliability-audit.json"
    audit = json.loads(audit_path.read_text(encoding="utf-8"))
    selection = audit["conflictAwareSelectionValidation"]
    audit_summary = {
        "status": "passed" if (audit["geometryValidation"]["invalidPolygonCount"] == 0
                                  and not audit["geometryValidation"]["representativePointFailures"]
                                  and not audit["geometryValidation"]["missingBboxGridEntries"]
                                  and audit["geometryValidation"]["countryValid"]
                                  and not selection["excludedIdSelectedFailures"]
                                  and not selection["samplesOutsideStrictMathematicalIntersection"]
                                  and not selection["samplesStillSelectingAPairMember"]
                                  and all(case["passed"] for case in audit["behavioralRegressionFixtures"])
                                  and all(case["passed"] for case in audit["realCoordinateRegressionCases"])
                                  ) else "failed",
        "geometryValidation": audit["geometryValidation"],
        "sourceIdFeatureCount": audit["polygonsBySource"].get(NEW_SOURCE_ID, 0),
        "sectorSectorPositiveAreaOverlapPairCount": len(audit["overlaps"]["sectorOverlapPairs"]),
        "conflictSamples": {
            "registered": selection["registeredConflictCount"],
            "evaluated": selection["samplesEvaluated"],
            "fullySuppressed": selection["samplePairsFullySuppressed"],
            "outsideStrictIntersection": len(selection["samplesOutsideStrictMathematicalIntersection"]),
            "pairMemberStillSelected": len(selection["samplesStillSelectingAPairMember"]),
            "excludedIdSelectedFailures": len(selection["excludedIdSelectedFailures"]),
        },
        "behavioralRegressionFixturesPassed": sum(case["passed"] for case in audit["behavioralRegressionFixtures"]),
        "realCoordinateRegressionCasesPassed": sum(case["passed"] for case in audit["realCoordinateRegressionCases"]),
        "sectorCoverage": audit["coverage"]["sectorPolygons"],
        "auditReport": "audit/neighborhood-reliability-audit.json",
    }
    report["auditCatalog"] = audit_summary
    (output / "overlay-dry-run-report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8", newline="\n")
    with (output / "overlay-dry-run-report.md").open("a", encoding="utf-8", newline="\n") as note:
        note.write("\n## Independent catalog audit\n\n")
        note.write(
            f"`audit_catalog.py` status: **{audit_summary['status']}**; "
            f"{audit_summary['geometryValidation']['invalidPolygonCount']} invalid polygons, "
            f"{len(audit_summary['geometryValidation']['representativePointFailures'])} representative-point failures, "
            f"{len(audit_summary['geometryValidation']['missingBboxGridEntries'])} missing grid entries; "
            f"{audit_summary['sectorSectorPositiveAreaOverlapPairCount']} sector-sector positive-area overlap pairs.\n\n"
            f"Conflict sample checks: {selection['samplesEvaluated']} / {selection['registeredConflictCount']} evaluated; "
            f"{selection['samplePairsFullySuppressed']} pairs fully suppressed; "
            f"{audit_summary['behavioralRegressionFixturesPassed']} behavioral fixtures and "
            f"{audit_summary['realCoordinateRegressionCasesPassed']} real-coordinate cases passed.\n"
        )
    print(json.dumps({
        "output": str(output),
        "baselineCounts": report["baselineCounts"],
        "stagedCounts": report["stagedCounts"],
        "unchangedPayloadCount": len(untouched_slice_checks),
        "addedSectorGpsChecks": added_label_checks,
        "auditCatalog": audit_summary,
    }, ensure_ascii=True, indent=2))


if __name__ == "__main__":
    main()
