"""Read-only replay of Android's NeighborhoodIndex against an NPOL asset pair.

``PackedGpsReplay(metadata_path, binary_path).find(lat, lon, accuracy=20)``
returns the selected id plus candidate, qualified and suppressed ids. Omitting
accuracy uses ``find``; ``find_with_accuracy`` also models Android's null/invalid
accuracy result. Inputs are read once, copied and frozen; this module never
repackages or writes assets. Geometry decoding uses the Android validation rules
(three vertices and implicit ring closure are allowed), rather than the stricter
review UI decoder. Shapely is optional and used only by ``geometry``.

Ordinary lookup preserves the app's 1e-10 degree boundary tolerance, including
its exclusion of hole edges. Accuracy lookup uses exact strict membership and
the app's conservative WGS84 clearance frame. It does not additionally require
the uncertainty disk to fit inside the country, because Android does not do so.
This is an offline mathematical replay, not an Android runtime/device test.
"""

from __future__ import annotations

import argparse
from collections.abc import Mapping
import hashlib
import json
import math
from numbers import Real
from pathlib import Path
import struct
from types import MappingProxyType


COORDINATE_EPSILON = 1e-10
_WGS84_FLATTENING = 1.0 / 298.257223563
MIN_MERIDIONAL_RADIUS_METERS = (
    6378137.0 * (1.0 - _WGS84_FLATTENING) * (1.0 - _WGS84_FLATTENING)
)
_HEADER = b"NPOL\x00\x00\x00\x01"
_INT = struct.Struct(">i")
_POLICY_SCHEMA = "ariana-pair-specific-m-else-qualified-osm-policy-v2"
_POLICY_STATUS = "SCRATCH_ONLY_INDEPENDENT_QA_PENDING"
_POLICY_SCOPE = (
    "GPS findWithAccuracy only; strict containment and clearance>accuracy still required for selected winner"
)
_PREFERRED_ID = "osm:relation:7115585"
_FALLBACK_ID = "osm:relation:7115582"
_POLICY_WKB = "6c60f2cd2d4d323a03d632232d0a4e453ba4f991d156b113cf7110819f4be27b"


def _need(condition, message):
    if not condition:
        raise ValueError(message)


def _finite_number(value):
    return isinstance(value, Real) and not isinstance(value, bool) and math.isfinite(value)


def valid_coordinates(lat, lon):
    return (_finite_number(lat) and _finite_number(lon)
            and -90.0 <= lat <= 90.0 and -180.0 <= lon <= 180.0)


def _freeze(value):
    if isinstance(value, Mapping):
        return MappingProxyType({key: _freeze(item) for key, item in value.items()})
    if isinstance(value, (list, tuple)):
        return tuple(_freeze(item) for item in value)
    return value


def _decode_record(binary, row, scale, identifier):
    """Validate and decode exactly the ring shapes accepted by Android."""
    offset, length = row["offset"], row["length"]
    _need(type(offset) is int and type(length) is int
          and offset >= 8 and length >= 36 and offset % 4 == length % 4 == 0
          and offset <= len(binary) - length, f"Invalid packed slice: {identifier}")
    cursor, end = offset, offset + length

    def take():
        nonlocal cursor
        _need(cursor + 4 <= end, f"Truncated packed geometry: {identifier}")
        number = _INT.unpack_from(binary, cursor)[0]
        cursor += 4
        return number

    polygon_count = take()
    _need(0 < polygon_count <= (end - cursor) // 32,
          f"Invalid polygon count: {identifier}")
    polygons = []
    for _ in range(polygon_count):
        ring_count = take()
        _need(0 < ring_count <= (end - cursor) // 28,
              f"Invalid ring count: {identifier}")
        rings = []
        for _ in range(ring_count):
            point_count = take()
            _need(3 <= point_count <= (end - cursor) // 8,
                  f"Invalid point count: {identifier}")
            ring = []
            for _ in range(point_count):
                lon, lat = take() / scale, take() / scale
                _need(valid_coordinates(lat, lon), f"Invalid vertex: {identifier}")
                ring.append((lon, lat))
            rings.append(tuple(ring))
        polygons.append(tuple(rings))
    _need(cursor == end, f"Trailing bytes in packed geometry: {identifier}")
    return tuple(polygons)


def _ring_membership(ring, lat, lon, tolerance):
    first_x, first_y = ring[0]
    previous_x, previous_y = first_x, first_y
    inside = on_edge = False
    # Include the closing segment even when the encoded ring is already closed.
    for x, y in (*ring[1:], ring[0]):
        dx, dy = x - previous_x, y - previous_y
        cross = (lon - previous_x) * dy - (lat - previous_y) * dx
        if (min(previous_x, x) - tolerance <= lon <= max(previous_x, x) + tolerance
                and min(previous_y, y) - tolerance <= lat <= max(previous_y, y) + tolerance
                and abs(cross) <= tolerance * math.hypot(dx, dy)):
            on_edge = True
        if (previous_y > lat) != (y > lat) and lon < dx * (lat - previous_y) / dy + previous_x:
            inside = not inside
        previous_x, previous_y = x, y
    return inside, on_edge


def contains_geometry(polygons, lat, lon, *, include_boundary=True,
                      boundary_tolerance=COORDINATE_EPSILON):
    """Port of containsPackedGeometry, including outer/hole boundary behavior."""
    for rings in polygons:
        inside_outer = inside_hole = False
        for index, ring in enumerate(rings):
            inside, on_edge = _ring_membership(ring, lat, lon, boundary_tolerance)
            if index == 0:
                inside_outer = inside or on_edge if include_boundary else inside and not on_edge
            else:
                inside_hole = inside_hole or inside or on_edge
        if inside_outer and not inside_hole:
            return True
    return False


def edge_clearance_meters(polygons, lat, lon, accuracy):
    """Port of packedEdgeClearanceMeters with identical operation ordering."""
    query_lat, query_lon = math.radians(lat), math.radians(lon)
    max_abs_lat = abs(query_lat) + accuracy / MIN_MERIDIONAL_RADIUS_METERS
    if not math.isfinite(max_abs_lat) or max_abs_lat >= math.pi / 2.0:
        return None
    y_scale = MIN_MERIDIONAL_RADIUS_METERS
    x_scale = y_scale * math.cos(max_abs_lat)
    best = math.inf
    for rings in polygons:
        for ring in rings:
            previous_x = x_scale * (math.radians(ring[0][0]) - query_lon)
            previous_y = y_scale * (math.radians(ring[0][1]) - query_lat)
            for lon_next, lat_next in (*ring[1:], ring[0]):
                x = x_scale * (math.radians(lon_next) - query_lon)
                y = y_scale * (math.radians(lat_next) - query_lat)
                dx, dy = x - previous_x, y - previous_y
                length_squared = dx * dx + dy * dy
                if length_squared == 0.0:
                    distance = math.hypot(previous_x, previous_y)
                else:
                    projection = (-previous_x * dx - previous_y * dy) / length_squared
                    t = min(1.0, max(0.0, projection))
                    distance = math.hypot(previous_x + t * dx, previous_y + t * dy)
                best = min(best, distance)
                previous_x, previous_y = x, y
    return best


class PackedGpsReplay:
    """Frozen catalog snapshot; paths or an in-memory mapping/bytes are accepted."""

    def __init__(self, metadata, binary):
        self.metadata_path = Path(metadata).resolve() if isinstance(metadata, (str, Path)) else None
        self.binary_path = Path(binary).resolve() if isinstance(binary, (str, Path)) else None
        if self.metadata_path is not None:
            raw_metadata = self.metadata_path.read_bytes()
            metadata = json.loads(raw_metadata.decode("utf-8-sig"))
            self.metadata_sha256 = hashlib.sha256(raw_metadata).hexdigest()
        else:
            self.metadata_sha256 = None
        binary = self.binary_path.read_bytes() if self.binary_path is not None else bytes(binary)
        self.binary_sha256 = hashlib.sha256(binary).hexdigest()
        self.binary = binary
        self.metadata = _freeze(metadata)
        _need(self.metadata.get("schemaVersion") == 1, "Unsupported metadata schema")
        _need(binary[:8] == _HEADER, "Packed asset does not have the NPOL v1 header")
        self.grid_size, self.scale = self.metadata["gridSize"], self.metadata["coordinateScale"]
        _need(_finite_number(self.grid_size) and self.grid_size > 0
              and _finite_number(self.scale) and self.scale > 0, "Invalid coordinate scale/grid")
        self.features = self.metadata["features"]
        by_id, boundaries, decoded = {}, {}, {}
        for index, row in enumerate(self.features):
            identifier = row["id"]
            _need(isinstance(identifier, str) and identifier and identifier not in by_id,
                  f"Invalid or duplicate feature id: {identifier}")
            by_id[identifier] = row
            _need(type(row["hasBoundary"]) is bool, f"Invalid hasBoundary: {identifier}")
            if not row["hasBoundary"]:
                continue
            bbox = row["bbox"]
            _need(len(bbox) == 4 and all(_finite_number(value) for value in bbox)
                  and valid_coordinates(bbox[1], bbox[0])
                  and valid_coordinates(bbox[3], bbox[2])
                  and bbox[0] < bbox[2] and bbox[1] < bbox[3], f"Invalid bbox: {identifier}")
            _need(_finite_number(row["areaKm2"]) and row["areaKm2"] > 0,
                  f"Invalid area: {identifier}")
            boundaries[index] = row
            decoded[identifier] = _decode_record(binary, row, self.scale, identifier)
        self.by_id, self.boundaries = MappingProxyType(by_id), MappingProxyType(boundaries)
        self._decoded = MappingProxyType(decoded)
        self._country = _decode_record(binary, self.metadata["country"], self.scale, "country")
        boundary_indexes = {row["id"]: index for index, row in boundaries.items()}
        conflicts = set()
        for conflict in self.metadata.get("conflicts", ()):
            ids = conflict["ids"]
            _need(len(ids) == 2 and isinstance(conflict["reason"], str) and conflict["reason"].strip()
                  and ids[0] != ids[1] and all(identifier in boundary_indexes for identifier in ids),
                  "Invalid conflict record")
            conflicts.add(tuple(sorted((boundary_indexes[ids[0]], boundary_indexes[ids[1]]))))
        self.conflicts = tuple(sorted(conflicts))
        self.cells = self.metadata["cells"]
        for indexes in self.cells.values():
            _need(all(type(index) is int and index in boundaries for index in indexes),
                  "Grid references an absent boundary")
        self.priority = self._read_priority(boundary_indexes)

    def _read_priority(self, boundary_indexes):
        policies = self.metadata.get("gpsConflictPolicies", ())
        _need(isinstance(policies, tuple) and len(policies) <= 1, "Unsupported GPS policy list")
        if not policies:
            return None
        policy = policies[0]
        ids, preferred = policy["ids"], policy["gpsPreferredId"]
        # Android currently accepts only this sealed pair. Selection below is
        # driven by metadata ids, so no geography is hardcoded into lookup.
        _need(policy["schemaVersion"] == _POLICY_SCHEMA and policy["status"] == _POLICY_STATUS
              and policy["scope"] == _POLICY_SCOPE
              and policy["osmFallbackWhenMNotAccuracyQualified"] is True
              and len(ids) == 2 and set(ids) == {_PREFERRED_ID, _FALLBACK_ID}
              and preferred == _PREFERRED_ID and policy["acceptedOfficialCode"] == "125654"
              and policy["preferredSourceWkbSha256"] == _POLICY_WKB
              and policy["osmPeerSourceId"] == "osm", "GPS policy is not accepted by current Android")
        fallback = next(identifier for identifier in ids if identifier != preferred)
        preferred_index, fallback_index = boundary_indexes[preferred], boundary_indexes[fallback]
        pair = tuple(sorted((preferred_index, fallback_index)))
        _need(pair in self.conflicts and self.by_id[preferred]["sourceId"] != "osm"
              and self.by_id[fallback]["sourceId"] == "osm", "Invalid GPS policy sources/pair")
        return pair, preferred_index, fallback_index

    def decode_geometry(self, identifier):
        """Immutable polygons -> rings -> (longitude, latitude) tuples."""
        return self._decoded[identifier]

    def geometry(self, identifier):
        """Return a Shapely MultiPolygon; this optional accessor is for analysis."""
        from shapely.geometry import MultiPolygon, Polygon
        return MultiPolygon([Polygon(rings[0], rings[1:]) for rings in self.decode_geometry(identifier)])

    def contains(self, identifier, lat, lon, *, strict=False, tolerance=COORDINATE_EPSILON):
        return valid_coordinates(lat, lon) and contains_geometry(
            self.decode_geometry(identifier), lat, lon,
            include_boundary=not strict, boundary_tolerance=tolerance)

    def is_inside_country(self, lat, lon):
        return valid_coordinates(lat, lon) and contains_geometry(self._country, lat, lon)

    def clearance(self, identifier, lat, lon, accuracy):
        if not valid_coordinates(lat, lon) or not _finite_number(accuracy) or accuracy <= 0:
            return None
        return edge_clearance_meters(self.decode_geometry(identifier), lat, lon, accuracy)

    @staticmethod
    def _result(winner=None, candidates=(), qualified=(), suppressed=(), **extra):
        candidates, qualified, suppressed = sorted(candidates), sorted(qualified), sorted(suppressed)
        return {"winnerId": winner, "candidates": candidates, "qualified": qualified,
                "suppressed": suppressed, "candidateIds": candidates,
                "qualifiedIds": qualified, "suppressedIds": suppressed, **extra}

    def find(self, lat, lon, accuracy=None):
        """Ordinary find when accuracy is omitted; GPS findWithAccuracy otherwise."""
        return self._find(lat, lon, accuracy, accuracy is not None)

    def find_with_accuracy(self, lat, lon, accuracy):
        """Includes Android's null-accuracy rejection, unlike ordinary ``find``."""
        return self._find(lat, lon, accuracy, True)

    def _find(self, lat, lon, accuracy, with_accuracy):
        if with_accuracy and (not _finite_number(accuracy) or accuracy <= 0):
            return self._result(invalidAccuracy=True)
        if not valid_coordinates(lat, lon):
            return self._result(invalidCoordinates=True)
        if not self.is_inside_country(lat, lon):
            return self._result(outsideCountry=True)
        if with_accuracy and abs(math.radians(lat)) + accuracy / MIN_MERIDIONAL_RADIUS_METERS >= math.pi / 2:
            return self._result(invalidAccuracyFrame=True)
        key = f"{math.floor(lat / self.grid_size)}:{math.floor(lon / self.grid_size)}"
        candidates = set()
        strict, clearances = {}, {}

        def is_strict(index):
            if index not in strict:
                strict[index] = self.contains(self.boundaries[index]["id"], lat, lon,
                                              strict=True, tolerance=0.0 if with_accuracy else COORDINATE_EPSILON)
            return strict[index]

        def clearance(index):
            if index not in clearances:
                clearances[index] = self.clearance(self.boundaries[index]["id"], lat, lon, accuracy)
            return clearances[index]

        for index in self.cells.get(key, ()):
            row = self.boundaries[index]
            xmin, ymin, xmax, ymax = row["bbox"]
            if xmin <= lon <= xmax and ymin <= lat <= ymax and self.contains(
                    row["id"], lat, lon, tolerance=0.0 if with_accuracy else COORDINATE_EPSILON):
                candidates.add(index)
        qualified = ({index for index in candidates if is_strict(index) and clearance(index) > accuracy}
                     if with_accuracy else candidates.copy())
        suppressed = set()
        if not with_accuracy:
            for first, second in self.conflicts:
                if first in candidates and second in candidates and is_strict(first) and is_strict(second):
                    suppressed.update((first, second))
        else:
            priority_pair, preferred, fallback = self.priority or (None, None, None)
            preferred_qualified = preferred in qualified
            for candidate in qualified:
                for pair in self.conflicts:
                    if candidate not in pair:
                        continue
                    if pair == priority_pair and (candidate == preferred or
                                                  (candidate == fallback and not preferred_qualified)):
                        continue
                    peer = pair[1] if candidate == pair[0] else pair[0]
                    if is_strict(peer) or clearance(peer) <= accuracy:
                        suppressed.add(candidate)
                        break
        available = qualified - suppressed
        winner = min(available, key=lambda index: (
            self.boundaries[index]["areaKm2"], self.boundaries[index]["id"])) if available else None
        ids = lambda indexes: [self.boundaries[index]["id"] for index in indexes]
        return self._result(self.boundaries[winner]["id"] if winner is not None else None,
                            ids(candidates), ids(qualified), ids(suppressed))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("metadata", type=Path)
    parser.add_argument("binary", type=Path)
    parser.add_argument("lat", type=float)
    parser.add_argument("lon", type=float)
    parser.add_argument("--accuracy", type=float)
    args = parser.parse_args()
    replay = PackedGpsReplay(args.metadata, args.binary)
    print(json.dumps(replay.find(args.lat, args.lon, args.accuracy), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
