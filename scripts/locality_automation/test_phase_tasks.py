import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

from scripts.locality_automation.append_phase_tasks import append_batch, phase_tasks
from scripts.locality_automation.run_sealed_boundary_queue import pin

APP = Path(r"C:\Users\barou\Documents\Codex\2026-09-06\the-android-app-app-currently-allows\work\locality-progress-dashboard\task_report_app.py")


class PhaseTaskTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.directory = Path(self.temporary.name)
        sys.path.insert(0, str(APP.parent))
        spec = importlib.util.spec_from_file_location("actual_stock_activity_isolated_test", APP)
        self.app = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.app)
        self.app.EVENTS = self.directory / "events.jsonl"
        self.app.LOCK = self.directory / "lock"
        self.app.EVENTS.write_text("", encoding="utf-8")

    def tearDown(self):
        self.temporary.cleanup()
        sys.path.remove(str(APP.parent))

    def row(self):
        return dict(taskId="fixture-phase", title="Native replay", detail="Source-only; zero geographic credit",
            locationCodes=["425154", "426055"], endAction="block",
            startedAtUtc="2026-10-03T18:33:20+00:00", finishedAtUtc="2026-10-03T18:33:23+00:00")

    def test_stock_event_schema_duplicate_retries_and_shared_clock(self):
        append_batch(self.app, [self.row()])
        append_batch(self.app, [self.row()])
        rows = [json.loads(line) for line in self.app.EVENTS.read_text().splitlines()]
        self.assertEqual(len(rows), 2)
        self.assertEqual([r["action"] for r in rows], ["start", "block"])
        self.assertTrue(all(r["locationCodes"] == ["425154", "426055"] for r in rows))
        self.assertFalse(self.app.LOCK.exists())

    def test_stock_exclusive_lock_stops_activity_mutation(self):
        self.app.LOCK.write_text("another owner")
        with self.assertRaises(FileExistsError):
            append_batch(self.app, [self.row()])
        self.assertEqual(self.app.EVENTS.read_text(), "")
        self.assertEqual(self.app.LOCK.read_text(), "another owner")

    def test_aware_utc_normalization_and_bad_phase_rejected_before_writes(self):
        control = dict(windowStartUtc="2026-10-03T18:14:50+00:00", deadlineUtc="2026-10-03T20:14:50+00:00",
            safeSourceQaStartUtc="2026-10-03T19:44:50Z", safeMapStartUtc="2026-10-03T20:07:50Z")
        receipt = dict(status="COMPLETED", exitCode=0, timedOut=False, processId=123, recordOwner="fixture",
            startedAtUtc="2026-10-03T18:33:20Z", finishedAtUtc="2026-10-03T18:33:23Z",
            hardDeadlineUtc="2026-10-03T20:14:50Z", safeStartUtc="2026-10-03T19:44:50Z")
        path = self.directory / "execution.json"
        path.write_text(json.dumps(receipt))
        spec = dict(phases=[dict(self.row(), execution=pin(path))])
        self.assertEqual(len(phase_tasks(spec, control)), 1)
        receipt["exitCode"] = False
        path.write_text(json.dumps(receipt))
        spec["phases"][0]["execution"] = pin(path)
        with self.assertRaises(ValueError):
            phase_tasks(spec, control)
        self.assertEqual(self.app.EVENTS.read_text(), "")


if __name__ == "__main__":
    unittest.main()
