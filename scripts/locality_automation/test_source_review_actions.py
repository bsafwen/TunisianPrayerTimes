import unittest
from scripts.locality_automation.plan_source_review_actions import classify, indexed_reference


class ReviewActionTests(unittest.TestCase):
    def test_current_acceptance_takes_precedence_over_bad_old_authorship(self):
        self.assertEqual(classify("a", None, {"a"}, "/root"), "ALREADY_FULL_SOURCE_SKIP_PAYLOAD")

    def test_actual_same_author_is_not_independent(self):
        self.assertEqual(classify("a", "/root", set(), "/root"), "SAME_SOURCE_AUTHOR_REQUIRES_DISJOINT_REVIEW")

    def test_unknown_author_is_not_promoted_to_a_disjoint_source(self):
        for value in (None, "", " "):
            self.assertEqual(classify("a", value, set(), "/root"), "SOURCE_AUTHOR_UNKNOWN_REQUIRES_PROVENANCE")

    def test_different_source_author_still_requires_all_review_gates(self):
        self.assertEqual(classify("a", "/root/old-source", set(), "/root"), "DISJOINT_SOURCE_AUTHOR_REQUIRES_REMAINING_REVIEW_GATES")

    def test_exact_flat_and_nested_legacy_index_shapes(self):
        reference = dict(file="source.json", sha256="a" * 64)
        self.assertEqual(indexed_reference(reference), reference)
        self.assertEqual(indexed_reference(dict(sourceFinal=reference)), reference)
        with self.assertRaises(ValueError):
            indexed_reference(dict(sourceFinal=reference, **reference))
        with self.assertRaises(KeyError):
            indexed_reference(dict(path="source.json"))


if __name__ == "__main__":
    unittest.main()
