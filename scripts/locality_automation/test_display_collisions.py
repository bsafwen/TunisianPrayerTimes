"""Focused tests for the read-only selectable display collision audit."""
import copy
import struct
import unittest

from .display_collisions import audit_display_collisions, render_compact


def _packed_polygon(points, scale=1):
    values = [1, 1, len(points)]
    for lng, lat in points:
        values.extend((lng * scale, lat * scale))
    return struct.pack(">" + "i" * len(values), *values)


class DisplayCollisionAuditTests(unittest.TestCase):
    def setUp(self):
        self.catalog = {"locations": [
            {"id": "osm:relation:1", "kind": "sector", "governorateAr": "ولاية أ",
             "parentAr": "معتمدية ب", "nameAr": "الحي 5", "lat": 2, "lng": 2},
            {"id": "osm:node:2", "kind": "village", "governorateAr": "ولاية أ",
             "parentAr": "معتمدية ب", "nameAr": "الحي 5", "lat": 2, "lng": 4.5},
            {"id": "osm:node:3", "kind": "village", "governorateAr": "ولاية أ",
             "parentAr": "معتمدية أخرى", "nameAr": "الحي 5", "lat": 2, "lng": 2},
            {"id": "osm:way:4", "kind": "residential", "governorateAr": "ولاية ت",
             "parentAr": "كمبوت", "nameAr": "برج", "lat": 8, "lng": 8},
            {"id": "osm:node:5", "kind": "village", "governorateAr": "ولاية ت",
             "parentAr": "كمبوت", "nameAr": "برج", "lat": 8, "lng": 8},
        ]}
        polygon_points = [(0, 0), (4, 0), (4, 4), (0, 4), (0, 0)]
        self.blob = _packed_polygon(polygon_points)
        self.metadata = {"coordinateScale": 1, "features": [
            {"id": "osm:relation:1", "offset": 0, "length": len(self.blob)},
        ]}

    def test_groups_exact_display_tuple_and_checks_all_members_against_packed_namesake(self):
        original = copy.deepcopy(self.catalog)
        report = audit_display_collisions(self.catalog, self.metadata, self.blob)

        self.assertEqual(2, report["groupCount"])
        group = report["groups"][0]
        self.assertEqual(("ولاية أ", "معتمدية ب", "الحي 5"),
                         (group["governorateAr"], group["parentAr"], group["nameAr"]))
        self.assertEqual(["osm:node:2", "osm:relation:1"],
                         [member["id"] for member in group["members"]])
        self.assertEqual([
            {"pointId": "osm:node:2", "polygonId": "osm:relation:1",
             "relation": "outside", "distanceDegrees": 0.5},
            {"pointId": "osm:relation:1", "polygonId": "osm:relation:1",
             "relation": "inside", "distanceDegrees": None},
        ], group["checks"])
        self.assertEqual("unavailable", report["groups"][1]["checks"][0]["relation"])
        self.assertEqual(original, self.catalog)
        self.assertIn("no catalog changes", report["qualification"])
        self.assertNotIn("score", report)

    def test_report_order_and_compact_serialization_are_deterministic(self):
        first = audit_display_collisions(self.catalog, self.metadata, self.blob)
        reversed_catalog = {"locations": list(reversed(self.catalog["locations"]))}
        second = audit_display_collisions(reversed_catalog, self.metadata, self.blob)

        self.assertEqual(first, second)
        self.assertEqual(render_compact(first), render_compact(second))
        self.assertNotIn("\\n", render_compact(first))

    def test_unpack_rejects_truncated_packed_geometry(self):
        metadata = {"coordinateScale": 1, "features": [
            {"id": "osm:relation:1", "offset": 0, "length": 4},
        ]}
        with self.assertRaisesRegex(ValueError, "truncated"):
            audit_display_collisions(self.catalog, metadata, struct.pack(">i", 1))


if __name__ == "__main__":
    unittest.main()
