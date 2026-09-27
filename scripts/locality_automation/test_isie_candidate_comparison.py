"""One synthetic regression for an installed conflict beyond eight source neighbors."""

import hashlib
import unittest

from pyproj import CRS, Transformer
from shapely.geometry import box
from shapely.ops import transform

from .isie_candidate_comparison import (Catalog, REFERENCE_SOURCE_COUNT,
                                        _comparison_cache_inputs, _hash_json,
                                        _installed_sector_conflicts)


class _SyntheticCatalog(Catalog):
    def __init__(self, crs):
        self.crs = crs
        self.rows = {}
        self.geometries = {}
        self.decoded = []
        self.to_wgs84 = Transformer.from_crs(crs, 4326, always_xy=True)

    def add(self, identifier, geometry, governorate=345):
        self.geometries[identifier] = geometry
        self.rows[identifier] = {
            "id": identifier, "kind": "sector", "hasBoundary": True,
            "governorateId": governorate,
            "bbox": transform(self.to_wgs84.transform, geometry).bounds,
        }

    def geometry(self, identifier, crs):
        assert crs == self.crs
        self.decoded.append(identifier)
        return self.geometries[identifier]

    def boundary_pin(self, identifier):
        return {"id": identifier,
                "sha256": hashlib.sha256(self.geometries[identifier].wkb).hexdigest(),
                "coordinateScale": 1000000}


class InstalledSectorConflictTests(unittest.TestCase):
    def test_new_overlap_beyond_eight_nearest_is_checked_and_cache_pinned(self):
        crs = CRS.from_epsg(32632)
        catalog = _SyntheticCatalog(crs)
        old = box(500000, 3900000, 500010, 3900010)
        proposed = box(500000, 3900000, 500150, 3900010)
        catalog.add("target", old)
        for index in range(REFERENCE_SOURCE_COUNT):
            x = 500020 + index * 8
            catalog.add(f"near-{index}", box(x, 3900001, x + 2, 3900009))
        catalog.add("ninth", box(500130, 3900001, 500140, 3900009))
        catalog.add("other-governorate", box(500135, 3900001, 500145, 3900009), 999)
        catalog.add("far", box(500500, 3900001, 500510, 3900009))

        by_distance = sorted((old.distance(geometry), identifier) for identifier, geometry
                             in catalog.geometries.items() if identifier not in ("target", "other-governorate", "far"))
        self.assertNotIn("ninth", [identifier for _, identifier in by_distance[:REFERENCE_SOURCE_COUNT]])

        check = _installed_sector_conflicts("target", proposed, old, crs, 1.0, catalog)
        by_id = {row["id"]: row for row in check["sectors"]}
        self.assertEqual(check["inspectedSectorCount"], 10)
        self.assertTrue(by_id["ninth"]["newlyIntersectingDiagnostic"])
        self.assertEqual(by_id["ninth"]["newlyCoveredOverlapM2"], 80.0)
        self.assertEqual(by_id["other-governorate"]["governorateId"], 999)
        self.assertNotIn("far", catalog.decoded)  # bbox rejection precedes packed geometry work

        case = {"id": "target", "nameAr": "synthetic", "officialCode": "x", "sourcePdfSha256": "p"}
        inventory = {"cacheKey": "inventory"}
        key_inputs = _comparison_cache_inputs(case, inventory, catalog, [], {"target": "synthetic"},
                                              [check["scopeBboxWgs84"]])
        self.assertIn("ninth", [identifier for identifier, _ in key_inputs["affectedInstalledBoundaries"]])
        old_key = _hash_json(key_inputs)
        catalog.add("ninth", box(500131, 3900001, 500141, 3900009))
        changed = _comparison_cache_inputs(case, inventory, catalog, [], {"target": "synthetic"},
                                           [check["scopeBboxWgs84"]])
        self.assertNotEqual(old_key, _hash_json(changed))


if __name__ == "__main__":
    unittest.main()
