import unittest
from scripts.locality_automation.plan_topology_reconciliation import group_conflicts


class TopologyReconciliationTests(unittest.TestCase):
    def setUp(self):
        self.rows = {code: {"name": code} for code in "ABCD"}

    def edge(self, a, b, area=1):
        return {"ids": [a, b], "intersectionKm2": area, "reason": "overlapping_sectors"}

    def test_shared_protected_peer_joins_two_changed_bodies(self):
        values = [self.edge("A", "B"), self.edge("C", "B")]
        groups = group_conflicts([], values, self.rows, {"A", "C"}, {"B"})
        self.assertEqual(len(groups), 1)
        self.assertEqual(groups[0]["ids"], ["A", "B", "C"])
        self.assertEqual(groups[0]["protectedPeerIds"], ["B"])
        self.assertEqual(groups[0]["rawNewIncidentConflicts"], values)
        self.assertFalse(groups[0]["editsAuthorized"])
        self.assertFalse(groups[0]["expensiveAcceptanceDispatchAllowed"])

    def test_old_pair_increase_is_retained_separately(self):
        old = [self.edge("A", "B", 2)]
        new = [self.edge("B", "A", 3), self.edge("A", "C", .00001)]
        group = group_conflicts(old, new, self.rows, {"A"}, set())[0]
        self.assertEqual(group["increasedPairs"], [new[0]])
        self.assertEqual(group["introducedPairs"], [new[1]])
        self.assertEqual(group["rawNewIncidentConflicts"], new)

    def test_isolated_changed_body_has_no_acceptance_authority(self):
        groups = group_conflicts([], [], self.rows, {"A", "D"}, set())
        self.assertEqual(len(groups), 2)
        self.assertTrue(all(not g["requiresCoherentSourceReview"] for g in groups))
        self.assertTrue(all(not g["expensiveAcceptanceDispatchAllowed"] for g in groups))

    def test_duplicate_unknown_nonincident_and_nonfinite_edges_refused(self):
        for values in ([self.edge("A", "B"), self.edge("B", "A")],
                       [self.edge("A", "X")], [self.edge("B", "C")],
                       [self.edge("A", "B", float("nan"))]):
            with self.subTest(values=values), self.assertRaises(ValueError):
                group_conflicts([], values, self.rows, {"A"}, set())


if __name__ == "__main__":
    unittest.main()
