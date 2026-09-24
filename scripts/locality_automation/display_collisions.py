"""Read-only audit of exact duplicate selectable display identities.

The audit checks each colliding catalog point against packed polygons owned by
members of the same display-name group. It reports geometry only; it does not
merge catalog entries or grant identity or verification decisions.
"""
from __future__ import annotations

import argparse
import json
import math
import struct
import sys
from collections import defaultdict
from collections.abc import Mapping, Sequence
from pathlib import Path

from shapely.geometry import MultiPolygon, Point, Polygon


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_METADATA = ROOT / "android-app/app/src/main/assets/neighborhoods.json"
DEFAULT_BINARY = ROOT / "android-app/app/src/main/assets/neighborhoods.bin"


def unpack(record: Mapping, blob: bytes, coordinate_scale: int) -> MultiPolygon:
    """Decode one packed multipolygon, following audit_catalog's binary layout."""
    if not isinstance(coordinate_scale, int) or isinstance(coordinate_scale, bool) or coordinate_scale <= 0:
        raise ValueError("coordinateScale must be a positive integer")
    offset, length = record.get("offset"), record.get("length")
    if (not isinstance(offset, int) or isinstance(offset, bool)
            or not isinstance(length, int) or isinstance(length, bool)
            or offset < 0 or length < 4 or offset + length > len(blob)):
        raise ValueError("packed geometry has an invalid offset or length")
    end = offset + length
    position = offset

    def integer(label: str) -> int:
        nonlocal position
        if position + 4 > end:
            raise ValueError("packed geometry is truncated while reading " + label)
        value = struct.unpack_from(">i", blob, position)[0]
        position += 4
        return value

    polygon_count = integer("polygon count")
    if polygon_count < 1:
        raise ValueError("packed geometry must contain at least one polygon")
    polygons = []
    for _ in range(polygon_count):
        ring_count = integer("ring count")
        if ring_count < 1:
            raise ValueError("packed polygon must contain an exterior ring")
        rings = []
        for _ in range(ring_count):
            point_count = integer("point count")
            if point_count < 4:
                raise ValueError("packed polygon ring must contain at least four points")
            ring = [(integer("longitude") / coordinate_scale,
                     integer("latitude") / coordinate_scale)
                    for _ in range(point_count)]
            rings.append(ring)
        polygons.append(Polygon(rings[0], rings[1:]))
    if position != end:
        raise ValueError("packed geometry length does not match its decoded content")
    return MultiPolygon(polygons)


def _catalog_locations(value) -> list[Mapping]:
    if isinstance(value, Mapping):
        value = value.get("locations")
    if not isinstance(value, Sequence) or isinstance(value, (str, bytes, bytearray)):
        raise ValueError("current catalog must contain a locations list")

    result = []
    seen_ids = set()
    for index, row in enumerate(value):
        if not isinstance(row, Mapping):
            raise ValueError("current catalog location %d must be an object" % index)
        required = ("id", "kind", "governorateAr", "parentAr", "nameAr", "lat", "lng")
        if any(key not in row for key in required):
            raise ValueError("current catalog location %d is missing required display or point fields" % index)
        if any(not isinstance(row[key], str) for key in required[:5]):
            raise ValueError("current catalog location %d has non-string identity fields" % index)
        if not row["id"]:
            raise ValueError("current catalog location %d has an empty id" % index)
        if row["id"] in seen_ids:
            raise ValueError("current catalog contains duplicate id %r" % row["id"])
        seen_ids.add(row["id"])
        lat, lng = row["lat"], row["lng"]
        if (isinstance(lat, bool) or isinstance(lng, bool)
                or not isinstance(lat, (int, float)) or not isinstance(lng, (int, float))
                or not math.isfinite(lat) or not math.isfinite(lng)
                or not -90 <= lat <= 90 or not -180 <= lng <= 180):
            raise ValueError("current catalog location %r has invalid coordinates" % row["id"])
        result.append(row)
    return result


def audit_display_collisions(locations, metadata: Mapping, blob: bytes) -> dict:
    """Return exact display-collision groups and packed-polygon point checks.

    ``locations`` may be the locations sequence itself or a catalog object with
    a ``locations`` list. ``metadata`` is the parsed neighborhoods.json object.
    Distances are planar Shapely distances in longitude/latitude degrees.
    """
    rows = _catalog_locations(locations)
    if not isinstance(metadata, Mapping):
        raise ValueError("neighborhood metadata must be an object")
    coordinate_scale = metadata.get("coordinateScale")
    if (not isinstance(coordinate_scale, int) or isinstance(coordinate_scale, bool)
            or coordinate_scale <= 0):
        raise ValueError("coordinateScale must be a positive integer")
    features = metadata.get("features")
    if not isinstance(features, list):
        raise ValueError("neighborhood metadata must contain a features list")
    if not isinstance(blob, bytes):
        raise ValueError("packed geometry must be bytes")

    groups = defaultdict(list)
    for row in rows:
        groups[(row["governorateAr"], row["parentAr"], row["nameAr"])].append(row)
    collision_groups = [(identity, members) for identity, members in groups.items() if len(members) > 1]
    collision_groups.sort(key=lambda item: item[0])

    feature_by_id = {}
    for feature in features:
        if not isinstance(feature, Mapping) or not isinstance(feature.get("id"), str):
            raise ValueError("neighborhood feature must have a string id")
        if feature["id"] in feature_by_id:
            raise ValueError("neighborhood metadata contains duplicate feature id %r" % feature["id"])
        feature_by_id[feature["id"]] = feature

    result_groups = []
    for (governorate, parent, name), members in collision_groups:
        members = sorted(members, key=lambda row: row["id"])
        polygons = {}
        for member in members:
            feature = feature_by_id.get(member["id"])
            if feature is None:
                continue
            has_offset, has_length = "offset" in feature, "length" in feature
            if has_offset != has_length:
                raise ValueError("packed feature %r has an incomplete geometry reference" % member["id"])
            if feature.get("hasBoundary") and not has_offset:
                raise ValueError("boundary feature %r has no packed geometry reference" % member["id"])
            if has_offset:
                polygons[member["id"]] = unpack(feature, blob, coordinate_scale)

        checks = []
        for member in members:
            point = Point(member["lng"], member["lat"])
            if not polygons:
                checks.append({"pointId": member["id"], "polygonId": None,
                               "relation": "unavailable", "distanceDegrees": None})
                continue
            for polygon_id in sorted(polygons):
                polygon = polygons[polygon_id]
                inside = polygon.covers(point)
                checks.append({
                    "pointId": member["id"],
                    "polygonId": polygon_id,
                    "relation": "inside" if inside else "outside",
                    "distanceDegrees": None if inside else polygon.distance(point),
                })
        result_groups.append({
            "governorateAr": governorate,
            "parentAr": parent,
            "nameAr": name,
            "members": [{"id": row["id"], "kind": row["kind"],
                         "lat": row["lat"], "lng": row["lng"]}
                        for row in members],
            "checks": checks,
        })

    return {
        "schema": "locality-display-collision-audit/1",
        "groupCount": len(result_groups),
        "groups": result_groups,
        "qualification": "Read-only geometry diagnostic; no catalog changes, merging, verification, or score credit.",
    }


def audit_files(catalog_path, metadata_path=DEFAULT_METADATA, binary_path=DEFAULT_BINARY) -> dict:
    """Load one current selectable catalog and the shipped packed geometry."""
    catalog = json.loads(Path(catalog_path).read_text(encoding="utf-8-sig"))
    metadata = json.loads(Path(metadata_path).read_text(encoding="utf-8-sig"))
    blob = Path(binary_path).read_bytes()
    return audit_display_collisions(catalog, metadata, blob)


def render_compact(report: Mapping) -> str:
    """Serialize a stable one-line JSON report suitable for automation logs."""
    return json.dumps(report, ensure_ascii=False, sort_keys=True,
                      separators=(",", ":"), allow_nan=False)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", required=True, help="current selectable catalog JSON")
    parser.add_argument("--metadata", default=str(DEFAULT_METADATA), help="shipped neighborhoods.json")
    parser.add_argument("--binary", default=str(DEFAULT_BINARY), help="shipped neighborhoods.bin")
    args = parser.parse_args(argv)
    sys.stdout.buffer.write((render_compact(audit_files(args.catalog, args.metadata, args.binary)) + "\n").encode("utf-8"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
