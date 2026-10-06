import unittest
from scripts.locality_automation.admit_source_review_actions import action_for


class SourceAdmissionTest(unittest.TestCase):
    def row(self, disposition, author=None):
        return {"disposition": disposition, "observedSourceAuthor": author,
            "geographicCredit": 0, "actualNewReviewerClaimed": False}

    def test_already_complete_and_unreviewable_cases_cannot_enter_review(self):
        self.assertEqual(action_for(self.row("ALREADY_FULL_SOURCE_SKIP_PAYLOAD"), "/root"), "SKIP_ALREADY_COMPLETE")
        self.assertEqual(action_for(self.row("SAME_SOURCE_AUTHOR_REQUIRES_DISJOINT_REVIEW", "/root"), "/root"), "HOLD_REVIEWER_POLICY")
        self.assertEqual(action_for(self.row("SOURCE_AUTHOR_UNKNOWN_REQUIRES_PROVENANCE"), "/root"), "HOLD_PROVENANCE_AND_SOURCE_RECONCILIATION")

    def test_source_review_release_requires_distinct_observed_author(self):
        disposition = "DISJOINT_SOURCE_AUTHOR_REQUIRES_REMAINING_REVIEW_GATES"
        for author in (None, "", " ", "/root"):
            with self.assertRaises(ValueError):
                action_for(self.row(disposition, author), "/root")
        self.assertEqual(action_for(self.row(disposition, "/root/source_prior"), "/root"), "RELEASE_SOURCE_SCOPE_REVIEW_ONLY")

    def test_relabeling_credit_or_actor_rejected(self):
        for changed in ({**self.row("ALREADY_FULL_SOURCE_SKIP_PAYLOAD"), "geographicCredit": 1},
                {**self.row("ALREADY_FULL_SOURCE_SKIP_PAYLOAD"), "actualNewReviewerClaimed": True},
                self.row("MADE_UP_READY", "/root/source_prior")):
            with self.assertRaises(ValueError):
                action_for(changed, "/root")


if __name__ == "__main__":
    unittest.main()
