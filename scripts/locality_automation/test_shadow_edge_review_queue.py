"""Ensure a queued technical diagnostic cannot acquire acceptance authority."""
import copy
import unittest
from scripts.locality_automation.run_shadow_edge_review_queue import validate_completed
from scripts.locality_automation.run_shadow_edge_review_queue_v2 import validate_stage


class FrozenCohortAuthorityTest(unittest.TestCase):
    def setUp(self):
        self.declaration = {"code": "426055", "probes": 5, "geographicCredit": 0,
            "originalOracleDisagreements": 1}
        self.model = {"officialCode": "426055", "probeCount": 5, "indexedExhaustiveFailures": [],
            "liveAssetsChanged": False, "independentFormulaDisagreements": [{"retained": True}],
            "credit": 0, "independentQaPassed": False, "sourceScopeAccepted": False}
        self.joined = {"failures": [], "credit": 0, "independentQaPassed": False, "sourceScopeAccepted": False}

    def test_original_held_diagnostic_remains_usable_without_acceptance(self):
        original = copy.deepcopy((self.declaration, self.model, self.joined))
        validate_completed("426055", self.declaration, self.model, self.joined)
        self.assertEqual(original, (self.declaration, self.model, self.joined))

    def test_acceptance_or_independent_actor_claim_rejected(self):
        for item in (self.model, self.joined):
            for field, value in (("credit", 1), ("independentQaPassed", True), ("sourceScopeAccepted", True)):
                with self.subTest(item=item is self.model, field=field):
                    altered = copy.deepcopy(item)
                    altered[field] = value
                    with self.assertRaises(ValueError):
                        validate_completed("426055", self.declaration,
                            altered if item is self.model else self.model,
                            altered if item is self.joined else self.joined)

    def test_replaced_or_incomplete_cohort_rejected(self):
        for field, value in (("officialCode", "426262"), ("probeCount", 4),
                ("indexedExhaustiveFailures", [{"id": "broken"}]), ("liveAssetsChanged", True),
                ("independentFormulaDisagreements", [])):
            with self.subTest(field=field):
                changed = {**self.model, field: value}
                with self.assertRaises(ValueError):
                    validate_completed("426055", self.declaration, changed, self.joined)
        with self.assertRaises(ValueError):
            validate_completed("426055", self.declaration, self.model, {**self.joined, "failures": [{}]})

    def test_exact_stager_snapshot_can_have_a_different_path(self):
        spec = {"baselineMetadata": {"file": "live.json", "sha256": "m"},
            "baselineBinary": {"file": "live.bin", "sha256": "b"}}
        stage = {"changedIds": ["case"],
            "beforeMetadata": {"file": "immutable-copy.json", "sha256": "m"},
            "beforeBinary": {"file": "immutable-copy.bin", "sha256": "b"}}
        validate_stage(stage, {"id": "case"}, spec)
        for changed in ({**stage, "changedIds": ["other"]},
                {**stage, "beforeBinary": {"file": "immutable-copy.bin", "sha256": "foreign"}}):
            with self.assertRaises(ValueError):
                validate_stage(changed, {"id": "case"}, spec)


if __name__ == "__main__":
    unittest.main()
