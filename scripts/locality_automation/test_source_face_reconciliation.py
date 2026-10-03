import unittest
from shapely.geometry import box
from scripts.locality_automation.audit_source_face_reconciliation import compare, pending, unique_covered_area


class ReconciliationTests(unittest.TestCase):
    def test_gain_and_loss_partition_difference_without_credit(self):
        facts, gained, lost = compare(box(1, 0, 3, 2), box(0, 0, 2, 2))
        self.assertEqual((gained.area, lost.area), (2, 2))
        self.assertEqual(facts["symmetricDifferenceSquareMeters"], 4)
        self.assertAlmostEqual(facts["intersectionOverUnion"], 1 / 3)
        self.assertFalse(facts["exactTopologicalEquality"])

    def test_ring_orientation_is_not_a_geometry_change(self):
        source = box(0, 0, 2, 2)
        facts, _, _ = compare(source, source.reverse())
        self.assertTrue(facts["exactTopologicalEquality"])
        self.assertEqual(facts["gainedSquareMeters"], 0)
        self.assertEqual(facts["lostSquareMeters"], 0)

    def test_already_accepted_source_is_removed_before_work_selection(self):
        report = {"summary": {"validationIssues": [], "sourceOnlyAuditIssues": [],
                  "reportingIssues": [], "uniqueValidatedLocations": 1},
                  "validations": [{"locationCodes": ["a"]}, {"locationCodes": ["a"]}],
                  "sourceOnlyAcceptances": [{"locationCodes": ["a", "b"]}]}
        self.assertEqual(pending(report), ({"b"}, {"a"}))
        report["summary"]["uniqueValidatedLocations"] = 2
        with self.assertRaises(ValueError):
            pending(report)

    def test_bad_geometry_or_reporting_gate_is_rejected(self):
        with self.assertRaises(ValueError):
            compare(box(0, 0, 0, 0), box(0, 0, 1, 1))
        report = {"summary": {"validationIssues": ["stale"], "sourceOnlyAuditIssues": [],
                  "reportingIssues": [], "uniqueValidatedLocations": 0}}
        with self.assertRaises(ValueError):
            pending(report)

    def test_overlapping_peers_are_not_counted_twice(self):
        body = box(0, 0, 3, 1)
        peers = [box(0, 0, 2, 1), box(1, 0, 3, 1)]
        self.assertEqual(sum(body.intersection(p).area for p in peers), 4)
        self.assertEqual(unique_covered_area(body, peers), 3)


if __name__ == "__main__":
    unittest.main()
