import unittest
from scripts.locality_automation.oracle_clearance_reuse import reuse_clearance

MIN_MERIDIONAL_RADIUS_METERS = 10.0


class Oracle:
    def __init__(self):
        self.rings = {0: ((((0., 0.), (1., 0.), (1., 1.), (0., 0.)),),)}
        self.calls = 0

    def clearance(self, index, lat, lon, accuracy):
        self.calls += 1
        if accuracy == 0:
            raise ZeroDivisionError("original failure")
        return self.rings[index][0][0][1][0] + lat + lon + MIN_MERIDIONAL_RADIUS_METERS / accuracy


class ClearanceTests(unittest.TestCase):
    def test_same_exact_rings_and_query_reuse_only_arithmetic(self):
        oracle = Oracle()
        with reuse_clearance(oracle) as stats:
            self.assertEqual(oracle.clearance(0, 2., 3., 4.), oracle.clearance(0, 2., 3., 4.))
            self.assertEqual((oracle.calls, stats["hits"]), (1, 1))
            oracle.clearance(0, 2, 3., 4.)
            self.assertEqual(oracle.calls, 2)
        self.assertNotIn("clearance", vars(oracle))

    def test_changed_rings_and_radius_force_fresh_original_calls(self):
        global MIN_MERIDIONAL_RADIUS_METERS
        oracle = Oracle()
        try:
            with reuse_clearance(oracle):
                initial = oracle.clearance(0, 2., 3., 4.)
                oracle.rings[0] = ((((0., 0.), (2., 0.), (1., 1.), (0., 0.)),),)
                self.assertNotEqual(initial, oracle.clearance(0, 2., 3., 4.))
                MIN_MERIDIONAL_RADIUS_METERS = 20.0
                oracle.clearance(0, 2., 3., 4.)
                self.assertEqual(oracle.calls, 3)
        finally:
            MIN_MERIDIONAL_RADIUS_METERS = 10.0

    def test_original_exceptions_not_cached_and_override_restored(self):
        oracle = Oracle()
        with reuse_clearance(oracle) as stats:
            for _ in range(2):
                with self.assertRaises(ZeroDivisionError):
                    oracle.clearance(0, 2., 3., 0.)
            self.assertEqual((oracle.calls, stats["hits"]), (2, 0))
        self.assertNotIn("clearance", vars(oracle))

    def test_mutable_rings_bypass_and_capacity_is_bounded(self):
        oracle = Oracle()
        with reuse_clearance(oracle, max_entries=1) as stats:
            oracle.clearance(0, 1., 2., 3.)
            oracle.clearance(0, 2., 2., 3.)
            oracle.clearance(0, 1., 2., 3.)
            self.assertEqual(stats["evictions"], 2)
            oracle.rings[0] = list(oracle.rings[0])
            oracle.clearance(0, 1., 2., 3.)
            oracle.clearance(0, 1., 2., 3.)
            self.assertEqual(stats["bypasses"], 2)


if __name__ == "__main__":
    unittest.main()
