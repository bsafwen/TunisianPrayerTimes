#!/usr/bin/env python3
"""Stage manifest-driven best-effort replacements over the accepted first-release overlay.

This utility writes only to an explicit output directory outside the repository.
It rebuilds packed geometry offsets, cells, and conflicts with the production
compiler helpers while preserving every untouched polygon payload and point row.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
import hashlib
import json
import math
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile

from shapely import set_precision, unary_union
from shapely.geometry import MultiPolygon, Point, Polygon, shape


REPO = Path(__file__).resolve().parents[2]
APP_ASSETS = REPO / "android-app/app/src/main/assets"
HEX_SHA256 = re.compile(r"[0-9a-f]{64}\Z")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def repo_file(value: str, label: str) -> Path:
    relative = Path(value)
    if relative.is_absolute():
        raise ValueError(f"{label} must be repository-relative")
    resolved = (REPO / relative).resolve()
    if not resolved.is_relative_to(REPO):
        raise ValueError(f"{label} escapes the repository")
    return resolved


def json_bytes(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False) + "\n").encode("utf-8")


def unpack(feature: dict, blob: bytes, scale: int):
    position = feature["offset"]

    def integer() -> int:
        nonlocal position
        value = struct.unpack_from(">i", blob, position)[0]
        position += 4
        return value

    polygons = []
    for _ in range(integer()):
        rings = []
        for _ in range(integer()):
            rings.append([(integer() / scale, integer() / scale) for _ in range(integer())])
        if not rings:
            raise ValueError(f"Packed geometry has no rings: {feature.get('id')}")
        polygons.append(Polygon(rings[0], rings[1:]))
    if position != feature["offset"] + feature["length"]:
        raise ValueError(f"Packed geometry length mismatch: {feature.get('id')}")
    if not polygons:
        raise ValueError(f"Packed geometry has no polygons: {feature.get('id')}")
    return polygons[0] if len(polygons) == 1 else MultiPolygon(polygons)


def polygon_counts(geometry) -> tuple[int, int]:
    polygons = [geometry] if isinstance(geometry, Polygon) else list(geometry.geoms)
    return len(polygons), sum(len(polygon.interiors) for polygon in polygons)


def validate_wgs84(geometry) -> None:
    polygons = [geometry] if isinstance(geometry, Polygon) else list(geometry.geoms)
    if not polygons:
        raise ValueError("Source geometry has no polygon components")
    for polygon in polygons:
        for ring in [polygon.exterior, *polygon.interiors]:
            for longitude, latitude, *_ in ring.coords:
                if (not math.isfinite(longitude) or not math.isfinite(latitude)
                        or not -180 <= longitude <= 180 or not -90 <= latitude <= 90):
                    raise ValueError("Source coordinates are not finite WGS84 longitude/latitude")


def normalized_parent(value: str) -> str:
    return value.removeprefix("معتمدية ").strip()


def review_record(review: dict, record: dict) -> dict:
    rows = review.get("records")
    if rows is None:
        rows = review.get("acceptedMappings")
    if rows is None:
        rows = [review]
    if not isinstance(rows, list):
        raise ValueError("Source review records must be a list")
    matches = [row for row in rows if isinstance(row, dict)
               and row.get("targetId", row.get("id")) == record["id"]
               and str(row.get("officialCode", "")) == record["officialCode"]]
    if len(matches) != 1:
        raise ValueError(f"Source review does not uniquely cover {record['id']} / {record['officialCode']}")
    return matches[0]


def sector_overlap_km2(identifier: str, conflicts: list[dict]) -> float:
    return sum(conflict["intersectionKm2"] for conflict in conflicts
               if identifier in conflict["ids"] and conflict["reason"] == "overlapping_sectors")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, help="Repository-relative geometry overlay manifest")
    parser.add_argument("--output-dir", type=Path, required=True,
                        help="Required staging directory outside the repository")
    parser.add_argument("--current-app-base", action="store_true",
                        help="Use current app neighborhoods assets as the base; requires both SHA-256 pins")
    parser.add_argument("--base-json-sha256", help="Required hash pin with --current-app-base")
    parser.add_argument("--base-bin-sha256", help="Required hash pin with --current-app-base")
    args = parser.parse_args()

    manifest_path = repo_file(args.manifest, "Manifest")
    manifest_raw = manifest_path.read_bytes()
    manifest = json.loads(manifest_raw)
    if manifest.get("schemaVersion") != 1:
        raise ValueError("Unsupported overlay manifest schema")
    output = args.output_dir.resolve()
    if output == REPO or output.is_relative_to(REPO):
        raise ValueError("Output directory must be outside the repository")
    output.mkdir(parents=True, exist_ok=True)

    base_builder = repo_file(manifest.get("baseBuilder", ""), "Base builder")
    sources = manifest.get("sources")
    records = manifest.get("records")
    if not isinstance(sources, list) or not sources or not isinstance(records, list):
        raise ValueError("Manifest must provide a nonempty sources list and a records list")
    source_by_id = {}
    source_data = {}
    for source in sources:
        if not isinstance(source, dict) or not isinstance(source.get("id"), str) or not source["id"]:
            raise ValueError("Each manifest source needs a unique ID")
        source_id = source["id"]
        if source_id in source_by_id:
            raise ValueError(f"Duplicate manifest source ID: {source_id}")
        geojson_raw = repo_file(source.get("geojsonFile", ""), f"GeoJSON path for {source_id}").read_bytes()
        review_raw = repo_file(source.get("reviewFile", ""), f"Review path for {source_id}").read_bytes()
        expected_geojson_sha = source.get("geojsonSha256")
        expected_review_sha = source.get("reviewSha256")
        if not isinstance(expected_geojson_sha, str) or not HEX_SHA256.fullmatch(expected_geojson_sha):
            raise ValueError(f"Invalid pinned GeoJSON SHA-256 for {source_id}")
        if not isinstance(expected_review_sha, str) or not HEX_SHA256.fullmatch(expected_review_sha):
            raise ValueError(f"Invalid pinned source-review SHA-256 for {source_id}")
        if sha256(geojson_raw) != expected_geojson_sha:
            raise ValueError(f"Source GeoJSON hash changed for {source_id}")
        if sha256(review_raw) != expected_review_sha:
            raise ValueError(f"Source review hash changed for {source_id}")
        geojson = json.loads(geojson_raw)
        review = json.loads(review_raw)
        if geojson.get("type") != "FeatureCollection" or not isinstance(geojson.get("features"), list):
            raise ValueError(f"Source is not a GeoJSON FeatureCollection: {source_id}")
        if review.get("sourceGeojsonSha256") != expected_geojson_sha:
            raise ValueError(f"Source review does not pin the GeoJSON bytes: {source_id}")
        if (review.get("status") != "provisional_best_effort_boundary_candidate"
                or not isinstance(review.get("uncertainty"), str)
                or not review["uncertainty"].strip()):
            raise ValueError(f"Source review lacks provisional status or an uncertainty note: {source_id}")
        feature_by_id = {}
        for feature in geojson["features"]:
            identifier = feature.get("id")
            if not isinstance(identifier, str) or not identifier or identifier in feature_by_id:
                raise ValueError(f"Source features need unique string IDs: {source_id}")
            feature_by_id[identifier] = feature
        source_by_id[source_id] = source
        source_data[source_id] = {
            "geojsonSha256": expected_geojson_sha,
            "reviewSha256": expected_review_sha,
            "geojson": geojson,
            "review": review,
            "featureById": feature_by_id,
        }

    record_ids, official_codes, source_features = set(), set(), set()
    for record in records:
        if not isinstance(record, dict) or record.get("action") != "replace":
            raise ValueError("This staging builder accepts only declarative replace records")
        identifier = record.get("id")
        code = record.get("officialCode")
        source_id = record.get("sourceId")
        source_feature_id = record.get("sourceFeatureId")
        if (not isinstance(identifier, str) or not identifier.startswith("osm:relation:")
                or identifier in record_ids):
            raise ValueError(f"Replacement IDs must be unique OSM relation IDs: {identifier}")
        if not isinstance(code, str) or not re.fullmatch(r"[0-9]{6}", code) or code in official_codes:
            raise ValueError(f"Replacement official codes must be unique six-digit strings: {code}")
        if source_id not in source_data or not isinstance(source_feature_id, str):
            raise ValueError(f"Replacement has no declared source feature: {identifier}")
        if record.get("representativePolicy", "source_representative") not in (
                "source_representative", "preserve_current_inside"):
            raise ValueError(f"Unknown representative policy for replacement: {identifier}")
        source_key = (source_id, source_feature_id)
        if source_key in source_features:
            raise ValueError(f"Source feature reused by multiple replacement records: {source_key}")
        source_features.add(source_key)
        record_ids.add(identifier)
        official_codes.add(code)

    exclusions = manifest.get("exclusions", [])
    if not isinstance(exclusions, list):
        raise ValueError("Manifest exclusions must be a list when present")
    exclusion_ids = set()
    polygon_exclusion_ids = set()
    point_exclusion_ids = set()
    exclusion_evidence_by_id = {}
    required_exclusion_identity = {
        "sourceId", "name", "kind", "parentName", "governorateId", "delegationId",
        "hasBoundary", "pickerGroupId",
    }
    for exclusion in exclusions:
        if not isinstance(exclusion, dict):
            raise ValueError("Each manifest exclusion must be an object")
        identifier = exclusion.get("id")
        if not isinstance(identifier, str) or not identifier or identifier in exclusion_ids:
            raise ValueError(f"Exclusion IDs must be unique nonempty strings: {identifier}")
        if identifier in record_ids:
            raise ValueError(f"An exclusion cannot also be a replacement target: {identifier}")
        expected = exclusion.get("expectedCurrent")
        if not isinstance(expected, dict):
            raise ValueError(f"Exclusion expectedCurrent must be an object: {identifier}")
        if not required_exclusion_identity.issubset(expected):
            missing = sorted(required_exclusion_identity - set(expected))
            raise ValueError(f"Exclusion must pin all identity fields for {identifier}: missing {missing}")
        target_type = exclusion.get("targetType", "polygon")
        if not isinstance(target_type, str) or target_type not in {"polygon", "point"}:
            raise ValueError(f"Exclusion targetType must be polygon or point: {identifier}")
        if target_type == "point":
            if "packedGeometrySha256" in exclusion:
                raise ValueError(f"Point exclusion must not pin packed geometry: {identifier}")
            point_identity = {"lat", "lng"}
            if not point_identity.issubset(expected):
                raise ValueError(f"Point exclusion must pin exact latitude and longitude: {identifier}")
            if (not identifier.startswith("osm:node:") or expected.get("sourceId") != "osm"
                    or expected.get("kind") != "neighbourhood" or expected.get("hasBoundary") is not False):
                raise ValueError(f"Point exclusion must target an OSM point neighbourhood: {identifier}")
            if (any(type(expected.get(key)) not in (int, float)
                    or not math.isfinite(expected[key]) for key in ("lat", "lng"))
                    or not -90 <= expected["lat"] <= 90 or not -180 <= expected["lng"] <= 180):
                raise ValueError(f"Point exclusion needs finite WGS84 coordinates: {identifier}")
            point_exclusion_ids.add(identifier)
        else:
            geometry_sha = exclusion.get("packedGeometrySha256")
            if not isinstance(geometry_sha, str) or not HEX_SHA256.fullmatch(geometry_sha):
                raise ValueError(f"Exclusion needs a pinned packed-geometry SHA-256: {identifier}")
            polygon_exclusion_ids.add(identifier)
        reason = exclusion.get("reason")
        if not isinstance(reason, str) or not reason.strip():
            raise ValueError(f"Exclusion needs a nonempty reason: {identifier}")
        evidence = exclusion.get("evidence")
        if not isinstance(evidence, list) or not evidence:
            raise ValueError(f"Exclusion needs at least one pinned evidence file: {identifier}")
        seen_evidence_files = set()
        normalized_evidence = []
        for item in evidence:
            if not isinstance(item, dict):
                raise ValueError(f"Exclusion evidence entries must be objects: {identifier}")
            evidence_file, evidence_sha = item.get("file"), item.get("sha256")
            if (not isinstance(evidence_file, str) or not evidence_file
                    or evidence_file in seen_evidence_files):
                raise ValueError(f"Exclusion evidence files must be unique repository-relative paths: {identifier}")
            if not isinstance(evidence_sha, str) or not HEX_SHA256.fullmatch(evidence_sha):
                raise ValueError(f"Exclusion evidence needs a pinned SHA-256: {identifier} / {evidence_file}")
            evidence_path = repo_file(evidence_file, f"Exclusion evidence for {identifier}")
            if sha256(evidence_path.read_bytes()) != evidence_sha:
                raise ValueError(f"Exclusion evidence hash changed: {identifier} / {evidence_file}")
            seen_evidence_files.add(evidence_file)
            normalized_evidence.append({"file": evidence_file, "sha256": evidence_sha})
        exclusion_ids.add(identifier)
        exclusion_evidence_by_id[identifier] = normalized_evidence

    clips = manifest.get("clips", [])
    if not isinstance(clips, list):
        raise ValueError("Manifest clips must be a list when present")
    clip_ids = set()
    preserve_overlap_geometries = {}
    for clip in clips:
        if not isinstance(clip, dict):
            raise ValueError("Each clip must be an object")
        identifier = clip.get("id")
        by_id = clip.get("clipById")
        if (not isinstance(identifier, str) or not identifier.startswith("osm:relation:")
                or identifier in clip_ids or identifier in record_ids or identifier in exclusion_ids
                or not isinstance(by_id, str) or by_id == identifier or by_id in exclusion_ids):
            raise ValueError(f"Invalid or repeated clip target/clipper: {identifier} / {by_id}")
        if (not isinstance(clip.get("expectedCurrent"), dict)
                or not required_exclusion_identity.issubset(clip["expectedCurrent"])
                or not isinstance(clip.get("expectedClipper"), dict)
                or not required_exclusion_identity.issubset(clip["expectedClipper"])):
            raise ValueError(f"Clip identities must pin the target and clipper: {identifier}")
        for key in ("expectedPackedGeometrySha256", "expectedClipperGeometrySha256"):
            if not isinstance(clip.get(key), str) or not HEX_SHA256.fullmatch(clip[key]):
                raise ValueError(f"Clip needs a pinned {key}: {identifier}")
        for key in ("minRemovedAreaKm2", "maxRemovedAreaKm2"):
            if type(clip.get(key)) not in (int, float) or not math.isfinite(clip[key]) or clip[key] <= 0:
                raise ValueError(f"Clip needs a positive finite {key}: {identifier}")
        if clip["minRemovedAreaKm2"] >= clip["maxRemovedAreaKm2"]:
            raise ValueError(f"Clip area interval is invalid: {identifier}")
        evidence = clip.get("evidence")
        if not isinstance(evidence, list) or not evidence:
            raise ValueError(f"Clip needs pinned evidence: {identifier}")
        for item in evidence:
            if (not isinstance(item, dict) or not isinstance(item.get("sha256"), str)
                    or not HEX_SHA256.fullmatch(item["sha256"])):
                raise ValueError(f"Invalid clip evidence pin: {identifier}")
            evidence_path = repo_file(item.get("file", ""), f"Clip evidence for {identifier}")
            if sha256(evidence_path.read_bytes()) != item["sha256"]:
                raise ValueError(f"Clip evidence changed: {identifier} / {evidence_path}")
        has_preserved_overlap = "preserveOverlapSource" in clip
        if has_preserved_overlap:
            preserve_ref = clip["preserveOverlapSource"]
            if (not isinstance(preserve_ref, dict)
                    or set(preserve_ref) != {"sourceId", "featureId"}
                    or not isinstance(preserve_ref.get("sourceId"), str)
                    or not isinstance(preserve_ref.get("featureId"), str)):
                raise ValueError(f"Partial clip needs an exact pinned source feature: {identifier}")
            preserve_source = source_data.get(preserve_ref["sourceId"])
            preserve_feature = (preserve_source["featureById"].get(preserve_ref["featureId"])
                                if preserve_source else None)
            if (not isinstance(preserve_feature, dict)
                    or preserve_feature.get("geometry", {}).get("type") not in {"Polygon", "MultiPolygon"}):
                raise ValueError(f"Partial clip preservation feature is absent or nonpolygonal: {identifier}")
            preserve_geometry = shape(preserve_feature["geometry"])
            validate_wgs84(preserve_geometry)
            if preserve_geometry.is_empty or not preserve_geometry.is_valid:
                raise ValueError(f"Partial clip preservation feature is empty or invalid: {identifier}")
            for key in ("minPreservedOverlapKm2", "maxPreservedOverlapKm2"):
                if (type(clip.get(key)) not in (int, float) or not math.isfinite(clip[key])
                        or clip[key] <= 0):
                    raise ValueError(f"Partial clip needs a positive finite {key}: {identifier}")
            if clip["minPreservedOverlapKm2"] >= clip["maxPreservedOverlapKm2"]:
                raise ValueError(f"Partial clip preserved-area interval is invalid: {identifier}")
            buffer_units = clip.get("preserveBufferGridUnits")
            if type(buffer_units) is not int or buffer_units not in {0, 1}:
                raise ValueError(f"Partial clip must explicitly use zero or one app-grid unit of preservation margin: {identifier}")
            mismatch_limit = clip.get("maxPreserveGeometryMismatchM2")
            if (type(mismatch_limit) not in (int, float) or not math.isfinite(mismatch_limit)
                    or mismatch_limit <= 0 or mismatch_limit > 50):
                raise ValueError(f"Partial clip needs a documented preservation precision limit of at most 50 m2: {identifier}")
            preserve_overlap_geometries[identifier] = preserve_geometry
        target_governorate = clip["expectedCurrent"].get("governorateId")
        clipper_governorate = clip["expectedClipper"].get("governorateId")
        cross_governorate = target_governorate != clipper_governorate
        if cross_governorate:
            if (not has_preserved_overlap
                    or clip.get("crossGovernoratePolicy") != "native_governorate_boundary"
                    or not isinstance(clip.get("crossGovernorateEvidence"), list)
                    or len(clip["crossGovernorateEvidence"]) < 2):
                raise ValueError(f"Cross-governorate clip needs an explicit boundary policy and preserved overlap: {identifier}")
            evidence_pins = {(item["file"], item["sha256"]) for item in evidence}
            cross_pins = set()
            for item in clip["crossGovernorateEvidence"]:
                if (not isinstance(item, dict) or not isinstance(item.get("file"), str)
                        or not isinstance(item.get("sha256"), str)
                        or not HEX_SHA256.fullmatch(item["sha256"])):
                    raise ValueError(f"Cross-governorate source-map evidence pin is invalid: {identifier}")
                cross_pins.add((item["file"], item["sha256"]))
            if len(cross_pins) < 2 or not cross_pins.issubset(evidence_pins):
                raise ValueError(f"Cross-governorate policy must cite two separately pinned source maps: {identifier}")
        elif "crossGovernoratePolicy" in clip or "crossGovernorateEvidence" in clip:
            raise ValueError(f"Cross-governorate policy is only valid for a governorate mismatch: {identifier}")
        source_id = clip.get("sourceId")
        if (not isinstance(source_id, str) or not source_id.startswith("osm-isie-reviewed-clip-")
                or source_id in source_by_id):
            raise ValueError(f"Clip needs a distinct derived source ID: {identifier}")
        if not isinstance(clip.get("reason"), str) or not clip["reason"].strip():
            raise ValueError(f"Clip needs a reason: {identifier}")
        clip_ids.add(identifier)

    if not records and not clips:
        raise ValueError("Manifest must provide at least one replacement record or clip")

    text_updates = manifest.get("textUpdates", [])
    if not isinstance(text_updates, list):
        raise ValueError("Manifest textUpdates must be a list when present")
    text_update_ids = set()
    for update in text_updates:
        if not isinstance(update, dict):
            raise ValueError("Each text update must be an object")
        identifier = update.get("id")
        expected = update.get("expectedCurrent")
        changes = update.get("set")
        if (not isinstance(identifier, str) or not identifier.startswith("osm:node:")
                or identifier in text_update_ids or identifier in exclusion_ids
                or not isinstance(expected, dict) or expected.get("id") != identifier
                or not isinstance(changes, dict) or not changes
                or not set(changes).issubset({"name", "aliases"})):
            raise ValueError(f"Invalid point text update: {identifier}")
        if "name" in changes and (not isinstance(changes["name"], str)
                                  or not re.search(r"[\u0600-\u06FF]", changes["name"])):
            raise ValueError(f"Point update needs an Arabic primary name: {identifier}")
        if "aliases" in changes and (not isinstance(changes["aliases"], list)
                                     or any(not isinstance(value, str) for value in changes["aliases"])):
            raise ValueError(f"Point update aliases are invalid: {identifier}")
        evidence = update.get("evidence")
        if not isinstance(evidence, list) or not evidence:
            raise ValueError(f"Point update needs pinned evidence: {identifier}")
        for item in evidence:
            if (not isinstance(item, dict) or not isinstance(item.get("sha256"), str)
                    or not HEX_SHA256.fullmatch(item["sha256"])):
                raise ValueError(f"Invalid point update evidence pin: {identifier}")
            evidence_path = repo_file(item.get("file", ""), f"Point update evidence for {identifier}")
            if sha256(evidence_path.read_bytes()) != item["sha256"]:
                raise ValueError(f"Point update evidence changed: {identifier} / {evidence_path}")
        if not isinstance(update.get("reason"), str) or not update["reason"].strip():
            raise ValueError(f"Point update needs a reason: {identifier}")
        text_update_ids.add(identifier)

    scripts_dir = REPO / "scripts"
    sys.path.insert(0, str(scripts_dir))
    from generate_neighborhoods import (  # repository compiler helpers, read-only
        GRID,
        SCALE,
        available_timetables,
        detect_conflicts,
        distance,
        packed_geometry_bytes,
    )
    sys.path.insert(0, str(scripts_dir / "neighborhoods"))
    from audit_catalog import GpsLabelAudit

    if args.current_app_base:
        if not args.base_json_sha256 or not args.base_bin_sha256:
            raise ValueError("--current-app-base requires --base-json-sha256 and --base-bin-sha256")
        if not HEX_SHA256.fullmatch(args.base_json_sha256.lower()) or not HEX_SHA256.fullmatch(args.base_bin_sha256.lower()):
            raise ValueError("Current app base SHA-256 pins must be 64 lowercase hexadecimal characters")
        base_assets = APP_ASSETS.resolve()
        base_json_path = base_assets / "neighborhoods.json"
        base_bin_path = base_assets / "neighborhoods.bin"
        base_json_raw = base_json_path.read_bytes()
        base_bin = base_bin_path.read_bytes()
        if sha256(base_json_raw) != args.base_json_sha256.lower():
            raise ValueError("Current app neighborhoods.json differs from its required SHA-256 pin")
        if sha256(base_bin) != args.base_bin_sha256.lower():
            raise ValueError("Current app neighborhoods.bin differs from its required SHA-256 pin")
        base_input_report = {
            "mode": "hash_pinned_current_app_assets",
            "assetsDirectory": str(base_assets),
            "neighborhoodsJson": {"file": str(base_json_path), "sha256": sha256(base_json_raw)},
            "neighborhoodsBin": {"file": str(base_bin_path), "sha256": sha256(base_bin)},
            "manifestBaseBuilderInvoked": False,
        }
    else:
        if args.base_json_sha256 or args.base_bin_sha256:
            raise ValueError("Base SHA-256 pins are valid only with --current-app-base")
        with tempfile.TemporaryDirectory(prefix="best-effort-base-", dir=output) as temp_name:
            base_output = Path(temp_name)
            base_run = subprocess.run([sys.executable, str(base_builder), "--output-dir", str(base_output)],
                                      cwd=REPO, capture_output=True, text=True)
            if base_run.returncode:
                raise RuntimeError(f"Accepted base overlay builder failed:\n{base_run.stdout}\n{base_run.stderr}")
            base_assets = base_output / "assets"
            base_json_raw = (base_assets / "neighborhoods.json").read_bytes()
            base_bin = (base_assets / "neighborhoods.bin").read_bytes()
        base_input_report = {
            "mode": "manifest_base_builder",
            "builder": str(base_builder),
            "neighborhoodsJsonSha256": sha256(base_json_raw),
            "neighborhoodsBinSha256": sha256(base_bin),
            "manifestBaseBuilderInvoked": True,
        }

    metadata = json.loads(base_json_raw)
    if metadata.get("coordinateScale") != SCALE or metadata.get("gridSize") != GRID:
        raise ValueError("Base overlay scale differs from the app compiler")
    if base_bin[:8] != b"NPOL\x00\x00\x00\x01":
        raise ValueError("Base overlay has an unsupported NPOL header")
    features = metadata["features"]
    boundary_features = [feature for feature in features if feature.get("hasBoundary")]
    point_features = [feature for feature in features if not feature.get("hasBoundary")]
    point_features_before = list(point_features)
    original_point_features = {feature["id"]: dict(feature) for feature in point_features}
    if features != boundary_features + point_features:
        raise ValueError("Base overlay does not order polygon features before point rows")
    if metadata["country"]["offset"] + metadata["country"]["length"] != len(base_bin):
        raise ValueError("Base overlay has trailing or missing country geometry bytes")
    feature_by_id = {feature["id"]: (index, feature) for index, feature in enumerate(boundary_features)}
    if len(feature_by_id) != len(boundary_features):
        raise ValueError("Base overlay has duplicate boundary IDs")
    point_feature_by_id = {feature["id"]: feature for feature in point_features}
    if len(point_feature_by_id) != len(point_features):
        raise ValueError("Base overlay has duplicate point IDs")
    if set(feature_by_id).intersection(point_feature_by_id):
        raise ValueError("Base overlay reuses an ID across polygon and point features")
    geometries = [unpack(feature, base_bin, SCALE) for feature in boundary_features]
    country = unpack(metadata["country"], base_bin, SCALE)
    old_conflicts = detect_conflicts(boundary_features, geometries)
    metadata_sources_before = dict(metadata.get("sources", {}))
    if not isinstance(metadata.get("sources"), dict):
        raise ValueError("Base overlay source map is invalid")

    exclusion_reports = []
    if exclusions:
        picker_group_members = defaultdict(list)
        for feature in features:
            picker_group_members[feature.get("pickerGroupId", feature["id"])].append(feature["id"])
        for exclusion in exclusions:
            identifier = exclusion["id"]
            target_type = exclusion.get("targetType", "polygon")
            if target_type == "point":
                feature = point_feature_by_id.get(identifier)
                index = None
            else:
                indexed_feature = feature_by_id.get(identifier)
                if indexed_feature is None:
                    raise ValueError(f"Exclusion target is missing or has no selectable polygon: {identifier}")
                index, feature = indexed_feature
            if feature is None:
                raise ValueError(f"Exclusion target is missing or has the wrong geometry type: {identifier}")
            expected = exclusion["expectedCurrent"]
            if any(feature.get(key) != value for key, value in expected.items()):
                differing = {key: {"expected": value, "actual": feature.get(key)}
                             for key, value in expected.items() if feature.get(key) != value}
                raise ValueError(f"Current app identity fields changed for exclusion {identifier}: {differing}")
            if target_type == "point":
                if (feature.get("id") != identifier or feature.get("sourceId") != "osm"
                        or feature.get("kind") != "neighbourhood" or feature.get("hasBoundary") is not False
                        or "offset" in feature or "length" in feature):
                    raise ValueError(f"Exclusion target is not a point-only OSM neighbourhood: {identifier}")
            elif (feature.get("id") != identifier or feature.get("sourceId") != "osm"
                    or feature.get("kind") != "residential" or feature.get("hasBoundary") is not True):
                raise ValueError(f"Exclusion target is not a bounded OSM residential feature: {identifier}")
            picker_group = feature.get("pickerGroupId")
            members = picker_group_members.get(picker_group, [])
            if not isinstance(picker_group, str) or not picker_group or members != [identifier]:
                raise ValueError(f"Exclusion target is not the sole member of its picker group: {identifier}")
            report_row = {
                "id": identifier,
                "targetType": target_type,
                "reason": exclusion["reason"],
                "expectedCurrent": expected,
                "evidence": exclusion_evidence_by_id[identifier],
            }
            if target_type == "point":
                report_row["coordinates"] = {"lat": feature["lat"], "lng": feature["lng"]}
            else:
                offset, length = feature.get("offset"), feature.get("length")
                country_offset = metadata["country"].get("offset")
                if (type(offset) is not int or type(length) is not int or offset < 8 or length <= 0
                        or type(country_offset) is not int or offset + length > country_offset):
                    raise ValueError(f"Exclusion target has invalid packed-geometry bounds: {identifier}")
                geometry_payload = base_bin[offset:offset + length]
                if (not geometry_payload
                        or sha256(geometry_payload) != exclusion["packedGeometrySha256"]):
                    raise ValueError(f"Exclusion packed geometry hash changed: {identifier}")
                report_row["packedGeometrySha256"] = exclusion["packedGeometrySha256"]
                report_row["packedGeometryBytes"] = length
            exclusion_reports.append(report_row)

        retired_asset_path = APP_ASSETS / "retired-localities.json"
        if not retired_asset_path.is_file():
            raise ValueError("Exclusions require the current retired-localities.json asset")
        retired_asset_raw = retired_asset_path.read_bytes()
        retired_asset_before = json.loads(retired_asset_raw)
        if (not isinstance(retired_asset_before, dict) or retired_asset_before.get("schemaVersion") != 1
                or not isinstance(retired_asset_before.get("retiredLocalityIds"), list)):
            raise ValueError("Current retired-localities.json has an unsupported schema")
        file_retired_ids = retired_asset_before["retiredLocalityIds"]
        metadata_retired_ids = metadata.get("retiredLocalityIds")
        if (any(not isinstance(item, str) or not item for item in file_retired_ids)
                or not isinstance(metadata_retired_ids, list)
                or any(not isinstance(item, str) or not item for item in metadata_retired_ids)
                or len(file_retired_ids) != len(set(file_retired_ids))
                or len(metadata_retired_ids) != len(set(metadata_retired_ids))):
            raise ValueError("Current retired-localities.json or base metadata has invalid retired IDs")
        base_retired_ids = set(metadata_retired_ids)
        current_retired_ids = set(file_retired_ids)
        missing_base_ids = base_retired_ids - current_retired_ids
        successor_extras = current_retired_ids - base_retired_ids
        unexplained_extras = successor_extras - exclusion_ids
        if missing_base_ids or unexplained_extras:
            raise ValueError(
                "Current retired-localities.json must include all base retired IDs and may add only "
                f"manifest exclusions; missing base IDs={sorted(missing_base_ids)}, "
                f"unexplained extras={sorted(unexplained_extras)}")
        if exclusion_ids & base_retired_ids:
            raise ValueError("An exclusion target is already listed as retired in base metadata")
        reviewed_names_before = retired_asset_before.get("reviewedNames")
        replacements_before = retired_asset_before.get("replacements")
        retired_asset_after = dict(retired_asset_before)
        combined_retired_ids = sorted(base_retired_ids | exclusion_ids)
        retired_asset_after["retiredLocalityIds"] = combined_retired_ids
        retired_json_raw = json_bytes(retired_asset_after)
        retired_asset_pins = {
            "inputSha256": sha256(retired_asset_raw),
            "stagedSha256": sha256(retired_json_raw),
            "retiredIdCountBefore": len(file_retired_ids),
            "retiredIdCountAfter": len(combined_retired_ids),
        }
    else:
        retired_asset_before = None
        retired_asset_after = None
        retired_json_raw = None
        retired_asset_pins = None

    assets = APP_ASSETS
    governors = json.loads((assets / "gouvernorats.json").read_text(encoding="utf-8"))["gouvernorats"]
    timetables, rejected_timetables = available_timetables(governors, assets)
    if not timetables or rejected_timetables is None:
        raise ValueError("No available timetable sources for prayer delegation selection")

    original_features = {feature["id"]: dict(feature) for feature in boundary_features}
    original_geometries = list(geometries)
    replacement_reports = []
    source_map_additions = {}
    for record in records:
        identifier, code = record["id"], record["officialCode"]
        index_and_feature = feature_by_id.get(identifier)
        if index_and_feature is None:
            raise ValueError(f"Replacement target is absent from accepted app overlay: {identifier}")
        index, feature = index_and_feature
        expected = record.get("expectedCurrent")
        if not isinstance(expected, dict) or not expected:
            raise ValueError(f"Replacement must pin expected current app fields: {identifier}")
        if any(feature.get(key) != value for key, value in expected.items()):
            differing = {key: {"expected": value, "actual": feature.get(key)}
                         for key, value in expected.items() if feature.get(key) != value}
            raise ValueError(f"Current app identity fields changed for {identifier}: {differing}")
        if (feature.get("id") != identifier or feature.get("kind") != "sector"
                or feature.get("hasBoundary") is not True):
            raise ValueError(f"Replacement target is not the expected bounded sector: {identifier}")

        source_id = record["sourceId"]
        source = source_by_id[source_id]
        source_content = source_data[source_id]
        source_feature = source_content["featureById"].get(record["sourceFeatureId"])
        if source_feature is None:
            raise ValueError(f"Source feature missing for replacement {identifier}")
        review = source_content["review"]
        review_row = review_record(review, record)
        properties = source_feature.get("properties", {})
        ministry_name, ministry_parent = record.get("ministryName"), record.get("ministryParent")
        source_parent = review_row.get("ministryParent", review.get("ministryParent"))
        source_name = review_row.get("ministryName", review.get("ministryName"))
        source_code = str(review_row.get("officialCode", review.get("officialCode", "")))
        source_target = review_row.get("targetId", review.get("targetId"))
        delegation_code = code[:4]
        governorate_code = code[:2]
        if (source_target != identifier or source_code != code or source_parent != ministry_parent
                or source_name != ministry_name or properties.get("sourceId") != source_id
                or properties.get("officialCode") != code or properties.get("nameAr") != ministry_name
                or properties.get("delegationCode") != delegation_code
                or properties.get("governorateCode") != governorate_code
                or normalized_parent(feature.get("parentName", "")) != ministry_parent
                or feature.get("name") != ministry_name):
            raise ValueError(f"Manifest, source, review, and current parent/name/code disagree: {identifier}")
        if (review.get("sourcePdfSha256") != properties.get("sourcePdfSha256")
                or review.get("sourcePdfURL") != properties.get("sourcePdfURL")):
            raise ValueError(f"Source review and GeoJSON PDF pins disagree: {identifier}")
        method = properties.get("geometryMethod")
        if not isinstance(method, str) or not method.strip():
            raise ValueError(f"Source geometry method is missing: {identifier}")

        geometry = shape(source_feature.get("geometry"))
        validate_wgs84(geometry)
        if geometry.is_empty or geometry.geom_type not in ("Polygon", "MultiPolygon") or not geometry.is_valid:
            raise ValueError(f"Source geometry is empty, unsupported, or invalid: {identifier}")
        quantized = set_precision(geometry, 1 / SCALE)
        if (quantized.is_empty or quantized.geom_type not in ("Polygon", "MultiPolygon")
                or not quantized.is_valid or polygon_counts(geometry) != polygon_counts(quantized)):
            raise ValueError(f"App precision changed geometry validity/components/holes: {identifier}")
        if not country.covers(quantized):
            raise ValueError(f"Quantized replacement falls outside app country geometry: {identifier}")
        if geometries[index].equals(quantized):
            raise ValueError(f"Replacement geometry is identical to the current app boundary: {identifier}")

        representative = quantized.representative_point()
        representative_policy = record.get("representativePolicy", "source_representative")
        if representative_policy == "preserve_current_inside":
            if not {"lat", "lng"}.issubset(expected):
                raise ValueError(f"Preserved representative needs pinned current coordinates: {identifier}")
            lat, lng = feature["lat"], feature["lng"]
            if not quantized.contains(Point(lng, lat)):
                raise ValueError(f"Current representative is outside replacement geometry: {identifier}")
        else:
            lat, lng = round(representative.y, 7), round(representative.x, 7)
        nearest = min(timetables, key=lambda item: (distance(lat, lng, item), item["id"]))
        original_target = dict(feature)
        old_delegation = feature.get("delegationId")
        feature.update({
            "sourceId": source_id,
            "lat": lat,
            "lng": lng,
            "delegationId": nearest["id"],
            "bbox": list(quantized.bounds),
            "areaKm2": quantized.area * 111.32**2 * math.cos(math.radians(representative.y)),
        })
        geometries[index] = quantized
        allowed = {"sourceId", "lat", "lng", "delegationId", "bbox", "areaKm2", "offset", "length"}
        changed = {key for key in set(original_target) | set(feature)
                   if original_target.get(key) != feature.get(key)}
        if not changed.issubset(allowed) or not {"sourceId", "bbox", "areaKm2"}.issubset(changed):
            raise ValueError(f"Replacement changed unexpected or missing derived fields: {identifier} {sorted(changed)}")

        source_map_additions[source_id] = {
            "id": source_id,
            "provider": source["provider"],
            "url": review["sourcePdfURL"],
            "sha256": source_content["geojsonSha256"],
            "sourceSha256": review["sourcePdfSha256"],
            "review": {
                "status": review["status"],
                "scopeKind": "best_effort_imada",
                "evidenceFile": source["reviewFile"].removeprefix("scripts/neighborhoods/"),
                "evidenceSha256": source_content["reviewSha256"],
                "sourcePdfURL": review["sourcePdfURL"],
                "sourcePdfSha256": review["sourcePdfSha256"],
                "uncertainty": review["uncertainty"],
            },
        }
        replacement_reports.append({
            "id": identifier,
            "officialCode": code,
            "sourceId": source_id,
            "sourceFeatureId": record["sourceFeatureId"],
            "sourceGeojsonSha256": source_content["geojsonSha256"],
            "sourceReviewSha256": source_content["reviewSha256"],
            "sourceGeometrySha256": properties.get("sourceGeometrySha256"),
            "sourcePdfURL": review["sourcePdfURL"],
            "sourcePdfSha256": review["sourcePdfSha256"],
            "geometryMethod": method,
            "uncertainty": review["uncertainty"],
            "quantizedValid": quantized.is_valid,
            "quantizedPolygonComponents": polygon_counts(quantized)[0],
            "quantizedInteriorRings": polygon_counts(quantized)[1],
            "rawAreaKm2": geometry.area * 111.32**2 * math.cos(math.radians(geometry.representative_point().y)),
            "quantizedAreaKm2": feature["areaKm2"],
            "bounds": list(quantized.bounds),
            "representativePoint": {"lat": lat, "lng": lng},
            "representativePolicy": representative_policy,
            "representativePointPreserved": (lat, lng) == (
                original_target["lat"], original_target["lng"]),
            "prayerDelegation": {"before": old_delegation, "after": nearest["id"],
                                 "nameAr": nearest["nomAr"], "distanceKm": distance(lat, lng, nearest)},
            "expectedCurrent": expected,
        })

    clip_reports = []
    for clip in clips:
        identifier, by_id = clip["id"], clip["clipById"]
        if identifier not in feature_by_id or by_id not in feature_by_id:
            raise ValueError(f"Clip target or clipper is absent: {identifier} / {by_id}")
        target_index, target = feature_by_id[identifier]
        clipper_index, clipper = feature_by_id[by_id]
        if (any(target.get(key) != value for key, value in clip["expectedCurrent"].items())
                or any(clipper.get(key) != value for key, value in clip["expectedClipper"].items())
                or target.get("kind") != "sector" or clipper.get("kind") != "sector"
                or target.get("sourceId") != "osm" or not clipper.get("sourceId", "").startswith("isie-")
                or (target.get("governorateId") != clipper.get("governorateId")
                    and (clip.get("crossGovernoratePolicy") != "native_governorate_boundary"
                         or identifier not in preserve_overlap_geometries))):
            raise ValueError(f"Clip identities or source priority changed: {identifier} / {by_id}")
        target_payload = base_bin[target["offset"]:target["offset"] + target["length"]]
        if sha256(target_payload) != clip["expectedPackedGeometrySha256"]:
            raise ValueError(f"Clip target packed geometry changed: {identifier}")
        old_geometry, clipper_geometry = geometries[target_index], geometries[clipper_index]
        if sha256(packed_geometry_bytes(clipper_geometry)) != clip["expectedClipperGeometrySha256"]:
            raise ValueError(f"Clipper geometry changed: {by_id}")
        overlap = old_geometry.intersection(clipper_geometry)
        preserve_geometry = preserve_overlap_geometries.get(identifier)
        preserve_buffer_units = clip.get("preserveBufferGridUnits", 0)
        preserve_mask = (set_precision(preserve_geometry.buffer(preserve_buffer_units / SCALE), 1 / SCALE)
                         if preserve_geometry is not None else None)
        preserved_overlap = (overlap.intersection(preserve_mask)
                             if preserve_mask is not None else None)
        removed = overlap.difference(preserve_mask) if preserve_mask is not None else overlap
        if removed.is_empty:
            raise ValueError(f"Clip target no longer overlaps clipper: {identifier}")
        clipped = (set_precision(old_geometry.difference(removed), 1 / SCALE)
                   if preserve_geometry is not None
                   else set_precision(old_geometry.difference(clipper_geometry), 1 / SCALE))
        removed_area = removed.area * 111.32**2 * math.cos(math.radians(removed.representative_point().y))
        preserved_area = (preserved_overlap.area * 111.32**2
                          * math.cos(math.radians(preserved_overlap.representative_point().y))) if preserved_overlap is not None else 0.0
        remaining_overlap = clipped.intersection(clipper_geometry)
        residual_outside_preserved_area = (remaining_overlap.difference(preserve_mask).area
                                           if preserve_mask is not None else remaining_overlap.area)
        missing_preserved_area = (preserved_overlap.difference(remaining_overlap).area
                                  if preserved_overlap is not None else 0.0)
        precision_reference = (preserved_overlap.representative_point()
                               if preserved_overlap is not None else removed.representative_point())
        square_degree_to_m2 = (111.32**2
                               * math.cos(math.radians(precision_reference.y)) * 1_000_000)
        residual_outside_preserved_m2 = residual_outside_preserved_area * square_degree_to_m2
        missing_preserved_m2 = missing_preserved_area * square_degree_to_m2
        preserve_mismatch_limit = clip.get("maxPreserveGeometryMismatchM2", 0.0)
        if (not clip["minRemovedAreaKm2"] <= removed_area <= clip["maxRemovedAreaKm2"]
                or clipped.is_empty or clipped.geom_type not in ("Polygon", "MultiPolygon")
                or not clipped.is_valid or not country.covers(clipped)
                or (preserve_geometry is None and residual_outside_preserved_area > 1e-10)
                or (preserve_geometry is not None
                    and (residual_outside_preserved_m2 > preserve_mismatch_limit
                         or missing_preserved_m2 > preserve_mismatch_limit))
                or (preserved_overlap is not None
                    and not clip["minPreservedOverlapKm2"] <= preserved_area <= clip["maxPreservedOverlapKm2"])):
            raise ValueError(f"Clipped polygon has unexpected area, geometry, or overlap: {identifier}")
        before = dict(target)
        representative = clipped.representative_point()
        lat, lng = round(representative.y, 7), round(representative.x, 7)
        nearest = min(timetables, key=lambda item: (distance(lat, lng, item), item["id"]))
        target.update({
            "sourceId": clip["sourceId"], "lat": lat, "lng": lng,
            "delegationId": nearest["id"], "bbox": list(clipped.bounds),
            "areaKm2": clipped.area * 111.32**2 * math.cos(math.radians(representative.y)),
        })
        geometries[target_index] = clipped
        allowed = {"sourceId", "lat", "lng", "delegationId", "bbox", "areaKm2"}
        changed = {key for key in set(before) | set(target) if before.get(key) != target.get(key)}
        if not changed.issubset(allowed) or not {"sourceId", "areaKm2"}.issubset(changed):
            raise ValueError(f"Clip changed unexpected or missing fields: {identifier} {sorted(changed)}")
        evidence = clip["evidence"]
        source_entry = {
            "id": clip["sourceId"],
            "provider": "Reviewed boundary reconciliation",
            "url": metadata["sources"][clipper["sourceId"]]["url"],
            "sha256": evidence[0]["sha256"],
            "review": {"status": "provisional_best_effort_boundary_candidate",
                       "scopeKind": ("osm_sector_partial_clip_preserving_source_overlap"
                                     if preserve_geometry is not None else "osm_sector_clipped_by_isie_imada"),
                       "evidenceFile": evidence[0]["file"].removeprefix("scripts/neighborhoods/"),
                       "evidenceSha256": evidence[0]["sha256"],
                       "uncertainty": clip["reason"]},
        }
        existing_source = source_map_additions.get(clip["sourceId"])
        if existing_source is not None and existing_source != source_entry:
            raise ValueError(f"Derived clip source metadata differs: {clip['sourceId']}")
        source_map_additions[clip["sourceId"]] = source_entry
        sample = removed.representative_point()
        clip_report = {"id": identifier, "clipById": by_id, "sourceId": clip["sourceId"],
                       "removedAreaKm2": removed_area, "remainingAreaKm2": target["areaKm2"],
                       "overlapSample": {"lat": sample.y, "lng": sample.x},
                       "expectedCurrent": clip["expectedCurrent"], "evidence": evidence}
        if preserved_overlap is not None:
            preserved_sample = preserved_overlap.representative_point()
            clip_report.update({"preservedOverlapKm2": preserved_area,
                                "preservedOverlapSample": {"lat": preserved_sample.y,
                                                            "lng": preserved_sample.x},
                                "residualOutsidePreservedGeometryM2": residual_outside_preserved_m2,
                                "missingPreservedGeometryM2": missing_preserved_m2,
                                "maxPreserveGeometryMismatchM2": preserve_mismatch_limit,
                                "preserveBufferGridUnits": preserve_buffer_units,
                                "remainingOverlapKm2": (remaining_overlap.area * 111.32**2
                                    * math.cos(math.radians(remaining_overlap.representative_point().y)))})
        clip_reports.append(clip_report)

    if exclusions:
        retained_pairs = [(feature, geometry) for feature, geometry in zip(boundary_features, geometries)
                          if feature["id"] not in polygon_exclusion_ids]
        if len(retained_pairs) != len(boundary_features) - len(polygon_exclusion_ids):
            raise ValueError("One or more exclusion targets were not uniquely present in selectable polygons")
        boundary_features = [feature for feature, _ in retained_pairs]
        geometries = [geometry for _, geometry in retained_pairs]
        point_features = [feature for feature in point_features
                          if feature["id"] not in point_exclusion_ids]
        if len(point_features) != len(point_features_before) - len(point_exclusion_ids):
            raise ValueError("One or more point exclusion targets were not uniquely present in point rows")
        metadata["retiredLocalityIds"] = sorted(set(metadata["retiredLocalityIds"]) | exclusion_ids)
        retired_asset_after["retiredLocalityIds"] = metadata["retiredLocalityIds"]
        if (retired_asset_after.get("reviewedNames") != reviewed_names_before
                or retired_asset_after.get("replacements") != replacements_before):
            raise ValueError("Exclusion staging changed reviewed names or replacement decisions")
        if retired_asset_after["retiredLocalityIds"] != metadata["retiredLocalityIds"]:
            raise ValueError("Staged retired-localities IDs do not match metadata retiredLocalityIds")
        retired_json_raw = json_bytes(retired_asset_after)
        retired_asset_pins["stagedSha256"] = sha256(retired_json_raw)
        retired_asset_pins["retiredIdCountAfter"] = len(retired_asset_after["retiredLocalityIds"])
        if any(feature["id"] in polygon_exclusion_ids for feature in boundary_features):
            raise ValueError("An excluded ID remains in selectable polygon features")
        if any(feature["id"] in point_exclusion_ids for feature in point_features):
            raise ValueError("An excluded ID remains in point rows")
        feature_by_id = {feature["id"]: (index, feature)
                         for index, feature in enumerate(boundary_features)}

    text_update_reports = []
    for update in text_updates:
        identifier = update["id"]
        feature = point_feature_by_id.get(identifier)
        if (feature is None or identifier in point_exclusion_ids or feature not in point_features
                or feature != update["expectedCurrent"] or feature.get("sourceId") != "osm"
                or feature.get("hasBoundary") is not False):
            raise ValueError(f"Point text update differs from the pinned source row: {identifier}")
        before = dict(feature)
        feature.update(update["set"])
        if feature == before:
            raise ValueError(f"Point text update is a no-op: {identifier}")
        text_update_reports.append({"id": identifier, "before": before,
                                    "after": dict(feature), "reason": update["reason"],
                                    "evidence": update["evidence"]})

    for source_id, source_entry in source_map_additions.items():
        existing = metadata_sources_before.get(source_id)
        if existing is not None and existing != source_entry:
            raise ValueError(f"New source ID collides with existing source map entry: {source_id}")
        metadata["sources"][source_id] = source_entry

    output_bin = bytearray(base_bin[:8])
    untouched_payloads = []
    target_ids = set(record_ids) | clip_ids
    for feature, geometry in zip(boundary_features, geometries):
        if feature["id"] in target_ids:
            payload = packed_geometry_bytes(geometry)
        else:
            payload = base_bin[feature["offset"]:feature["offset"] + feature["length"]]
            if not payload:
                raise ValueError(f"Untouched polygon payload is empty: {feature['id']}")
            untouched_payloads.append((feature["id"], payload))
        feature["offset"] = len(output_bin)
        feature["length"] = len(payload)
        output_bin.extend(payload)
    country_payload = base_bin[metadata["country"]["offset"]:
                               metadata["country"]["offset"] + metadata["country"]["length"]]
    if not country_payload:
        raise ValueError("Base country geometry payload is empty")
    metadata["country"] = {"offset": len(output_bin), "length": len(country_payload)}
    output_bin.extend(country_payload)

    cell_map = defaultdict(list)
    for index, geometry in enumerate(geometries):
        min_x, min_y, max_x, max_y = geometry.bounds
        for y in range(math.floor(min_y / GRID), math.floor(max_y / GRID) + 1):
            for x in range(math.floor(min_x / GRID), math.floor(max_x / GRID) + 1):
                cell_map[f"{y}:{x}"].append(index)
    metadata["cells"] = {key: cell_map[key] for key in sorted(cell_map)}
    conflicts = detect_conflicts(boundary_features, geometries)
    metadata["conflicts"] = conflicts
    metadata["features"] = boundary_features + point_features
    if exclusions:
        if any(feature["id"] in exclusion_ids for feature in metadata["features"]):
            raise ValueError("An excluded ID remains in staged selectable features")
        if any(identifier in conflict.get("ids", []) for conflict in conflicts for identifier in exclusion_ids):
            raise ValueError("An excluded ID remains in staged conflict/GPS geometry candidates")
        if any(type(index) is not int or not 0 <= index < len(boundary_features)
               for indices in metadata["cells"].values() for index in indices):
            raise ValueError("Rebuilt cell index references a missing or excluded polygon")
        if point_exclusion_ids.intersection(feature_by_id):
            raise ValueError("An excluded point ID appears in the runtime GPS boundary index")
    staged_json_raw = json_bytes(metadata)
    staged_bin_raw = bytes(output_bin)

    output_assets = output / "assets"
    output_assets.mkdir(parents=True, exist_ok=True)
    (output_assets / "neighborhoods.json").write_bytes(staged_json_raw)
    (output_assets / "neighborhoods.bin").write_bytes(staged_bin_raw)
    if exclusions:
        if set(retired_asset_after["retiredLocalityIds"]) != set(metadata["retiredLocalityIds"]):
            raise ValueError("Staged retired-localities IDs diverge from neighborhoods metadata")
        (output_assets / "retired-localities.json").write_bytes(retired_json_raw)

    # Byte and identity guarantees are checked after offsets have been rebuilt.
    output_features_by_id = {feature["id"]: feature for feature in metadata["features"]}
    point_rows_preserved = all(
        output_features_by_id[feature["id"]] == original_point_features[feature["id"]]
        for feature in point_features if feature["id"] not in text_update_ids)
    if not point_rows_preserved:
        raise ValueError("Point-only rows changed during geometry staging")
    for identifier, payload in untouched_payloads:
        output_feature = output_features_by_id[identifier]
        stored = staged_bin_raw[output_feature["offset"]:output_feature["offset"] + output_feature["length"]]
        if stored != payload:
            raise ValueError(f"Untouched polygon payload changed: {identifier}")
        before = original_features[identifier]
        if any(before.get(key) != output_feature.get(key)
               for key in set(before) | set(output_feature) if key != "offset"):
            raise ValueError(f"Untouched polygon metadata changed beyond offset: {identifier}")
    if exclusions and any(identifier in output_features_by_id for identifier in exclusion_ids):
        raise ValueError("An excluded ID remains in output feature rows")
    if staged_bin_raw[metadata["country"]["offset"]:
                      metadata["country"]["offset"] + metadata["country"]["length"]] != country_payload:
        raise ValueError("Country polygon payload changed")

    before_conflicts = old_conflicts
    after_conflicts = conflicts
    overlap_metrics = {}
    for identifier in target_ids:
        index = feature_by_id[identifier][0]
        overlap_metrics[identifier] = {
            "sectorOverlapKm2Before": sector_overlap_km2(identifier, before_conflicts),
            "sectorOverlapKm2After": sector_overlap_km2(identifier, after_conflicts),
        }

    label_model = GpsLabelAudit(boundary_features, geometries, country, conflicts)
    gps_checks = []
    for identifier in sorted(target_ids):
        index = feature_by_id[identifier][0]
        peer_ids = sorted({other for conflict in conflicts
                           if identifier in conflict["ids"] and conflict["reason"] == "overlapping_sectors"
                           for other in conflict["ids"] if other != identifier})
        peer_indices = [feature_by_id[peer][0] for peer in peer_ids]
        safe = (geometries[index].difference(unary_union([geometries[i] for i in peer_indices]))
                if peer_indices else geometries[index])
        if safe.is_empty or safe.area <= 0:
            raise ValueError(f"Replacement has no positive-area conflict-free interior: {identifier}")
        point = safe.representative_point()
        evaluation = label_model.evaluate(point.y, point.x)
        gps_candidate_ids = [boundary_features[candidate_index]["id"]
                             for candidate_index in evaluation["candidates"]]
        if exclusion_ids.intersection(gps_candidate_ids):
            raise ValueError(f"An excluded ID remains a runtime GPS candidate near {identifier}")
        winner = evaluation["winner"]
        winner_feature = boundary_features[winner] if winner is not None else None
        winner_id = winner_feature["id"] if winner_feature is not None else None
        same_picker_group = bool(winner_feature and
                                 winner_feature.get("pickerGroupId", winner_id)
                                 == boundary_features[index].get("pickerGroupId", identifier))
        same_display_name = bool(winner_feature and winner_feature.get("name") == boundary_features[index].get("name"))
        group_equivalent_winner = winner_id == identifier or (same_picker_group and same_display_name)
        checks = {
            "id": identifier,
            "pointLat": point.y,
            "pointLng": point.x,
            "strictlyInsideTarget": geometries[index].contains(point),
            "outsideAllPositiveAreaSectorPeers": not any(geometries[i].contains(point) for i in peer_indices),
            "winnerId": winner_id,
            "winnerName": winner_feature.get("name") if winner_feature else None,
            "winnerKind": winner_feature.get("kind") if winner_feature else None,
            "winnerSourceId": winner_feature.get("sourceId") if winner_feature else None,
            "winnerParentName": winner_feature.get("parentName") if winner_feature else None,
            "winnerDelegationId": winner_feature.get("delegationId") if winner_feature else None,
            "winnerHasBoundary": winner_feature.get("hasBoundary") if winner_feature else None,
            "winnerPickerGroupId": winner_feature.get("pickerGroupId") if winner_feature else None,
            "winnerAreaKm2": winner_feature.get("areaKm2") if winner_feature else None,
            "winnerSharesPickerGroup": same_picker_group,
            "winnerHasSameDisplayName": same_display_name,
            "targetInRuntimeCandidates": any(boundary_features[i]["id"] == identifier
                                              for i in evaluation["candidates"]),
            "targetWins": winner_id == identifier,
            "groupEquivalentSelection": group_equivalent_winner,
            "outcome": ("target_feature_selected" if winner_id == identifier else
                        "same_picker_group_same_name_member_selected" if same_picker_group and same_display_name else
                        "other_feature_selected"),
            "activeExcludedIds": [boundary_features[i]["id"] for i in evaluation["excluded"]],
        }
        if exclusions:
            checks["excludedIdsAbsentFromGpsCandidates"] = not exclusion_ids.intersection(gps_candidate_ids)
        gps_checks.append(checks)
        if not (checks["strictlyInsideTarget"] and checks["outsideAllPositiveAreaSectorPeers"]
                and checks["targetInRuntimeCandidates"] and checks["groupEquivalentSelection"]):
            raise ValueError(f"Safe-interior runtime selection check failed for {identifier}: {checks}")

    for row in clip_reports:
        sample = row["overlapSample"]
        selection = label_model.evaluate(sample["lat"], sample["lng"])
        winner = selection["winner"]
        winner_id = boundary_features[winner]["id"] if winner is not None else None
        if winner_id != row["clipById"]:
            raise ValueError(f"Former overlap does not resolve to the ISIE clipper: {row['id']} -> {winner_id}")
        row["formerOverlapGpsWinnerId"] = winner_id
        if "preservedOverlapSample" in row:
            preserved_sample = row["preservedOverlapSample"]
            preserved_selection = label_model.evaluate(preserved_sample["lat"], preserved_sample["lng"])
            target_index = feature_by_id[row["id"]][0]
            clipper_index = feature_by_id[row["clipById"]][0]
            excluded_indices = set(preserved_selection["excluded"])
            active_pairs = [sorted(boundary_features[index]["id"] for index in pair)
                            for pair in preserved_selection["activeConflicts"]]
            expected_pair = sorted([row["id"], row["clipById"]])
            if (target_index not in excluded_indices or clipper_index not in excluded_indices
                    or expected_pair not in active_pairs):
                raise ValueError(f"Preserved overlap no longer triggers pair conflict suppression: {row['id']}")
            preserved_winner = preserved_selection["winner"]
            row["preservedOverlapGpsWinnerId"] = (
                boundary_features[preserved_winner]["id"] if preserved_winner is not None else None)
            row["preservedOverlapGpsConflictSuppressed"] = True

    audit_dir = output / "audit"
    audit_run = subprocess.run([sys.executable, str(REPO / "scripts/neighborhoods/audit_catalog.py"),
                                "--assets", str(output_assets), "--output-dir", str(audit_dir)],
                               cwd=REPO, capture_output=True, text=True)
    if audit_run.returncode:
        raise RuntimeError(f"Staged neighborhood audit process failed:\n{audit_run.stdout}\n{audit_run.stderr}")
    audit = json.loads((audit_dir / "neighborhood-reliability-audit.json").read_text(encoding="utf-8"))
    selection = audit["conflictAwareSelectionValidation"]
    audit_ok = (
        audit["geometryValidation"]["invalidPolygonCount"] == 0
        and not audit["geometryValidation"]["representativePointFailures"]
        and not audit["geometryValidation"]["missingBboxGridEntries"]
        and audit["geometryValidation"]["countryValid"]
        and not selection["excludedIdSelectedFailures"]
        and not selection["samplesOutsideStrictMathematicalIntersection"]
        and not selection["samplesStillSelectingAPairMember"]
        and all(case["passed"] for case in audit["behavioralRegressionFixtures"])
        and all(case["passed"] for case in audit["realCoordinateRegressionCases"])
    )
    report = {
        "schemaVersion": 1,
        "status": "passed" if audit_ok else "failed",
        "manifest": {"file": str(manifest_path.relative_to(REPO)).replace("\\", "/"),
                     "sha256": sha256(manifest_raw)},
        "baseInput": base_input_report,
        "baseOverlayPins": {"neighborhoodsJson": sha256(base_json_raw),
                            "neighborhoodsBin": sha256(base_bin)},
        "stagedAssetSha256": {"neighborhoodsJson": sha256(staged_json_raw),
                               "neighborhoodsBin": sha256(staged_bin_raw)},
        "counts": {"features": len(metadata["features"]), "boundaryFeatures": len(boundary_features),
                   "pointRows": len(point_features), "conflictsBefore": len(before_conflicts),
                   "conflictsAfter": len(after_conflicts), "cellsBefore": len(json.loads(base_json_raw)["cells"]),
                   "cellsAfter": len(metadata["cells"])},
        "replacements": replacement_reports,
        "sectorOverlapKm2": overlap_metrics,
        "preservation": {"untouchedPolygonPayloads": len(untouched_payloads),
                         "allUntouchedPolygonBytesIdentical": True,
                         "allUntouchedPolygonFieldsPreservedExceptOffset": True,
                         "allPointRowsIdentical": point_rows_preserved and not text_updates,
                         "countryPayloadIdentical": True},
        "safeInteriorGpsChecks": gps_checks,
        "audit": {"passed": audit_ok,
                  "invalidPolygonCount": audit["geometryValidation"]["invalidPolygonCount"],
                  "representativePointFailures": len(audit["geometryValidation"]["representativePointFailures"]),
                  "missingBboxGridEntries": len(audit["geometryValidation"]["missingBboxGridEntries"]),
                  "conflictSamplesEvaluated": selection["samplesEvaluated"],
                  "behavioralRegressionFixturesPassed": sum(case["passed"]
                                                               for case in audit["behavioralRegressionFixtures"]),
                  "realCoordinateRegressionCasesPassed": sum(case["passed"]
                                                               for case in audit["realCoordinateRegressionCases"])},
    }
    if exclusions:
        boundary_ids_after = {feature["id"] for feature in boundary_features}
        if exclusion_ids.intersection(boundary_ids_after):
            raise ValueError("An excluded ID remains in the runtime GPS boundary index")
        report["stagedAssetSha256"]["retiredLocalities"] = retired_asset_pins["stagedSha256"]
        report["counts"]["featuresBeforeExclusions"] = len(features)
        report["counts"]["excludedFeatures"] = len(exclusion_ids)
        report["counts"]["excludedBoundaryFeatures"] = len(polygon_exclusion_ids)
        report["counts"]["excludedPointRows"] = len(point_exclusion_ids)
        report["exclusions"] = {"excludedIds": [row["id"] for row in exclusion_reports],
                                "records": exclusion_reports,
                                "retiredLocalities": retired_asset_pins,
                                "retiredIdsMatchNeighborhoodMetadata": (
                                    retired_asset_after["retiredLocalityIds"]
                                    == metadata["retiredLocalityIds"]),
                                "reviewedNamesAndReplacementsPreserved": (
                                    retired_asset_after.get("reviewedNames") == reviewed_names_before
                                    and retired_asset_after.get("replacements") == replacements_before),
                                "allIdsAbsentFromSelectableFeaturesAndGpsCandidates": True}
        report["preservation"].update({
            "excludedSelectablePolygonsRemoved": len(polygon_exclusion_ids),
            "excludedPointRowsRemoved": len(point_exclusion_ids),
            "pointRowsBeforeExclusions": len(point_features_before),
            "pointRowsAfterExclusions": len(point_features),
            "remainingPolygonPayloadsPreserved": len(untouched_payloads),
            "allExcludedIdsAbsentFromSelectableFeatures": True,
            "allExcludedIdsAbsentFromGpsCandidates": True,
            "retiredLocalitiesReviewedNamesAndReplacementsPreserved": True,
        })
    if clips:
        report["clips"] = clip_reports
    if text_updates:
        report["textUpdates"] = text_update_reports
        report["preservation"]["allUntouchedPointRowsIdentical"] = point_rows_preserved
    report_raw = (json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8")
    (output / "geometry-overlay-report.json").write_bytes(report_raw)
    exclusion_markdown = ""
    if exclusions:
        exclusion_markdown = (
            "\n## Exclusions\n\n"
            f"- Removed {len(polygon_exclusion_ids)} singleton OSM residential polygon(s) and "
            f"{len(point_exclusion_ids)} pinned OSM point neighbourhood(s) from selectable features.\n"
            f"- Staged `retired-localities.json` SHA-256: `{retired_asset_pins['stagedSha256']}`; its retired IDs match `neighborhoods.json`.\n\n"
            "| App ID | Target | Packed geometry SHA-256 | Reason |\n"
            "|---|---|---|---|\n" +
            "\n".join(
                f"| {row['id']} | {row['targetType']} | "
                f"{('`' + row['packedGeometrySha256'] + '`') if 'packedGeometrySha256' in row else '—'} | "
                f"{row['reason']} |"
                for row in exclusion_reports
            ) + "\n"
        )
    (output / "geometry-overlay-report.md").write_text(
        "# Best-effort geometry overlay staging\n\n"
        "Staging output only; no app assets were installed.\n\n"
        f"- Base JSON/BIN SHA-256: `{sha256(base_json_raw)}` / `{sha256(base_bin)}`.\n"
        f"- Staged JSON/BIN SHA-256: `{sha256(staged_json_raw)}` / `{sha256(staged_bin_raw)}`.\n"
        f"- Preserved {len(untouched_payloads)} untouched polygon payloads, {len(point_features)} point rows, and the country payload byte-for-byte.\n"
        f"- Independent catalog audit: **{'passed' if audit_ok else 'failed'}**.\n\n"
        "## Replacements\n\n"
        "| App ID | Code | Sector overlap before (km²) | After (km²) | Prayer delegation |\n"
        "|---|---:|---:|---:|---|\n" +
        "\n".join(
            f"| {row['id']} | {row['officialCode']} | "
            f"{overlap_metrics[row['id']]['sectorOverlapKm2Before']:.9f} | "
            f"{overlap_metrics[row['id']]['sectorOverlapKm2After']:.9f} | "
            f"{row['prayerDelegation']['before']} → {row['prayerDelegation']['after']} |"
            for row in replacement_reports
        ) + "\n" + exclusion_markdown,
        encoding="utf-8", newline="\n")
    if not audit_ok:
        raise ValueError(f"Staged neighborhood audit failed; report at {output / 'geometry-overlay-report.json'}")
    console_report = {"outputDir": str(output), "status": report["status"],
                      "stagedAssetSha256": report["stagedAssetSha256"],
                      "sectorOverlapKm2": overlap_metrics,
                      "audit": report["audit"]}
    if exclusions:
        console_report["excludedIds"] = report["exclusions"]["excludedIds"]
    print(json.dumps(console_report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
