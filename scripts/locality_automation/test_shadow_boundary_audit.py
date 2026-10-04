import copy
from types import SimpleNamespace
import unittest

from scripts.locality_automation.audit_shadow_boundary_gps import preserve_catalog
from scripts.locality_automation.verify_solo_jvm_audit_v2 import compare


class ShadowPreservationTests(unittest.TestCase):
    def catalog(self):
        features = [{"id": str(i), "name": "place" + str(i), "hasBoundary": i < 2571,
                     "offset": 0, "length": 1} for i in range(3473)]
        return SimpleNamespace(features=features, boundaries=features[:2571],
                               by_id={row["id"]: row for row in features})

    def test_offset_changes_preserve_exact_unrelated_physical_bytes(self):
        before, after = self.catalog(), self.catalog()
        for row in after.features:
            row["offset"] = 1
        self.assertEqual(len(preserve_catalog(before, after, b"x", b"xx", "0")), 3472)

    def test_unrelated_identity_change_rejected_even_when_geometry_matches(self):
        before, after = self.catalog(), self.catalog()
        after.by_id["5"]["name"] = "another"
        with self.assertRaisesRegex(ValueError, "complete metadata"):
            preserve_catalog(before, after, b"x", b"x", "0")

    def test_unrelated_physical_body_change_rejected(self):
        with self.assertRaisesRegex(ValueError, "physical whole body"):
            preserve_catalog(self.catalog(), self.catalog(), b"x", b"y", "0")

    def test_reordered_identical_features_rejected(self):
        before, after = self.catalog(), self.catalog()
        after.features.reverse()
        with self.assertRaisesRegex(ValueError, "order or identity"):
            preserve_catalog(before, after, b"x", b"x", "0")


class ShadowJvmTruthTests(unittest.TestCase):
    def sample(self):
        model = {"accuracyMeters": None, "expectedSourceId": 3, "id": "p", "lat": 35.0, "lng": 9.0,
                 "indexed": {"winnerId": "new"}}
        actual = {"requestIndex": 0, "name": "p", "lat": 35.0, "lng": 9.0, "accuracy": "ordinary",
                  "label": {"id": "new"}, "expectedSourceId": 3, "actualGpsSourceId": 3,
                  "actualPersistedSourceId": 3, "sourceMatches": True,
                  "labelPersistenceMatches": True, "manualReferenceCleared": True}
        return model, actual

    def test_wrong_label_not_hidden_by_success_flags(self):
        model, actual = self.sample()
        self.assertTrue(compare(model, actual, 0))
        actual["label"]["id"] = "old"
        self.assertFalse(compare(model, actual, 0))

    def test_probe_order_and_persisted_source_are_observed(self):
        for key, wrong in (("requestIndex", 1), ("actualPersistedSourceId", 4), ("lng", 9.1)):
            model, actual = self.sample()
            actual[key] = wrong
            self.assertFalse(compare(model, actual, 0))


if __name__ == "__main__":
    unittest.main()
