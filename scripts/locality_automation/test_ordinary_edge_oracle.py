from types import SimpleNamespace
import unittest
from shapely.geometry import Point, box
from scripts.locality_automation.ordinary_edge_oracle_v2 import OrdinaryEdgeOracleV2, strict_ordinary


class EdgeRuleTests(unittest.TestCase):
    def oracle(self):
        replay = SimpleNamespace(boundaries={0: {"id": "a", "areaKm2": 1}, 1: {"id": "b", "areaKm2": 2}}, conflicts=[(0, 1)])
        result = {"winnerId": None, "candidateIds": ["a", "b"], "qualifiedIds": ["a", "b"], "suppressedIds": ["a", "b"]}
        original = SimpleNamespace(replay=replay, geoms={0: box(0, 0, 1, 1), 1: box(-1, -1, 2, 2)}, find=lambda *args: (result, True))
        return OrdinaryEdgeOracleV2(original), result

    def test_exact_and_tolerance_close_interior_points_are_not_strict(self):
        body = box(0, 0, 1, 1)
        for x in (0, .5e-10, 1e-10):
            self.assertFalse(strict_ordinary(body, Point(x, .5)))
        self.assertTrue(strict_ordinary(body, Point(2e-10, .5)))

    def test_ordinary_conflict_does_not_suppress_edge_candidate(self):
        oracle, original = self.oracle()
        result, near = oracle.find(.5, .5e-10, None)
        self.assertEqual(result["winnerId"], "a")
        self.assertEqual(result["suppressedIds"], [])
        self.assertEqual(original["suppressedIds"], ["a", "b"])

    def test_actual_double_strict_interior_still_suppresses_both(self):
        oracle, original = self.oracle()
        result, near = oracle.find(.5, .5, None)
        self.assertEqual(result, original)

    def test_accuracy_path_is_returned_unchanged(self):
        oracle, original = self.oracle()
        for accuracy in (5, 20, 50):
            result, near = oracle.find(.5, .5, accuracy)
            self.assertIs(result, original)


if __name__ == "__main__":
    unittest.main()
