"""Versioned status fixes must report observed waits without altering authority."""
from datetime import datetime, timedelta, timezone
import unittest

from scripts.locality_automation.run_shadow_edge_review_queue_v3 import begin_wait, finish_wait


class ProducerWaitTest(unittest.TestCase):
    def setUp(self):
        self.started = datetime(2026, 10, 4, 1, 10, tzinfo=timezone.utc)
        self.state = {"pending": ["one", "two"], "completed": [], "credit": 0,
            "status": "FROZEN_COHORT_REVIEW_FINISHED_NO_ACCEPTANCE"}

    def test_next_wait_names_pending_code_and_observed_interval(self):
        begin_wait(self.state, "one", self.started)
        self.assertEqual(self.state["status"], "WAITING_FOR_EXACT_FROZEN_SHADOW_COHORT")
        self.assertEqual(self.state["currentCode"], "one")
        finish_wait(self.state, self.started + timedelta(seconds=4), "EXACT_PRODUCER_COHORT_OBSERVED")
        self.state["pending"].remove("one")
        self.state["completed"].append({"code": "one", "credit": 0})
        begin_wait(self.state, "two", self.started + timedelta(seconds=6))
        self.assertEqual(self.state["currentCode"], "two")
        self.assertEqual(self.state["producerWaits"][0]["seconds"], 4)
        self.assertEqual(self.state["credit"], 0)
        self.assertEqual(self.state["completed"], [{"code": "one", "credit": 0}])

    def test_stop_preserves_pending_and_closes_wait(self):
        begin_wait(self.state, "one", self.started)
        self.state["status"] = "STOPPED_AT_SOURCE_START_CUTOFF_WITH_PENDING_PRESERVED"
        finish_wait(self.state, self.started + timedelta(seconds=3), self.state["status"])
        self.assertNotIn("activeWait", self.state)
        self.assertEqual(self.state["pending"], ["one", "two"])
        self.assertEqual(self.state["producerWaits"][0]["outcome"], self.state["status"])
        finish_wait(self.state, self.started + timedelta(seconds=5), "IGNORED_NO_ACTIVE_WAIT")
        self.assertEqual(len(self.state["producerWaits"]), 1)

    def test_naive_clocks_overlapping_waits_and_unknown_codes_refused(self):
        for code, clock in (("missing", self.started), ("one", self.started.replace(tzinfo=None))):
            with self.assertRaises(ValueError):
                begin_wait(self.state, code, clock)
        begin_wait(self.state, "one", self.started)
        with self.assertRaises(ValueError):
            begin_wait(self.state, "two", self.started)
        with self.assertRaises(ValueError):
            finish_wait(self.state, self.started.replace(tzinfo=None), "BAD")
        with self.assertRaises(ValueError):
            finish_wait(self.state, self.started - timedelta(seconds=1), "BAD")
        self.assertIn("activeWait", self.state)


if __name__ == "__main__":
    unittest.main()
