"""Independent guard regressions; isolated temp receipts and mocked children only."""
from contextlib import redirect_stdout
from datetime import datetime, timedelta, timezone
from io import StringIO
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from scripts.locality_automation import work_window_guard as guard

SAFE = "2026-10-02T00:29:00Z"
HARD = "2026-10-02T00:34:10Z"


class IndependentReceiptTests(unittest.TestCase):
    def test_check_only_cannot_overwrite_existing_execution_receipt(self):
        with tempfile.TemporaryDirectory() as temp:
            record = Path(temp) / "execution.json"
            old = {"status": "RUNNING", "processId": 76543,
                   "startedAtUtc": "2026-10-02T00:28:00Z", "owner": "other-run"}
            original = json.dumps(old)
            record.write_text(original, encoding="utf-8")
            with redirect_stdout(StringIO()):
                code = guard.main(["--safe-start", "2000-01-01T00:00:00Z",
                                   "--hard-deadline", "2000-01-01T00:01:00Z",
                                   "--record", str(record)])
            self.assertEqual(2, code)
            self.assertEqual(original, record.read_text(encoding="utf-8"))

    def test_denied_launch_cannot_overwrite_existing_execution_receipt(self):
        with tempfile.TemporaryDirectory() as temp:
            record = Path(temp) / "execution.json"
            original = '{"status":"COMPLETED","processId":76543,"owner":"other-run"}'
            record.write_text(original, encoding="utf-8")
            with patch.object(guard, "_utc_now", return_value=guard.parse_utc(SAFE)), \
                    patch.object(guard.subprocess, "Popen") as popen:
                try:
                    result = guard.launch_guarded(SAFE, HARD, ["fixture-executable"], record=record)
                except (ValueError, OSError):
                    result = {"status": "DENIED"}
            popen.assert_not_called()
            self.assertEqual("DENIED", result["status"])
            self.assertEqual(original, record.read_text(encoding="utf-8"))

    def test_popen_value_error_cannot_leave_allowed_receipt(self):
        with tempfile.TemporaryDirectory() as temp:
            record = Path(temp) / "execution.json"
            output = StringIO()
            before = guard.parse_utc("2026-10-02T00:28:00Z")
            with patch.object(guard, "_utc_now", return_value=before), \
                    patch.object(guard.subprocess, "Popen", side_effect=ValueError("embedded null character")), \
                    redirect_stdout(output):
                code = guard.main(["--safe-start", SAFE, "--hard-deadline", HARD,
                                   "--record", str(record), "--", "fixture\0executable"])
            self.assertEqual(2, code)
            self.assertEqual("DENIED", json.loads(output.getvalue())["status"])
            if record.exists():
                receipt = json.loads(record.read_text(encoding="utf-8"))
                self.assertEqual("DENIED", receipt["status"])
                self.assertIsNone(receipt["processId"])


class IndependentClockTests(unittest.TestCase):
    def setUp(self):
        self.now = datetime(2026, 10, 2, 0, 0, tzinfo=timezone.utc)
        self.safe = guard._stamp(self.now + timedelta(seconds=5))
        self.hard = guard._stamp(self.now + timedelta(seconds=10))

    def test_spawn_clock_rollback_does_not_enlarge_original_budget(self):
        state = {"elapsed": 0.0, "spawned": False}

        class Child:
            pid, returncode = 123, None

            def poll(self):
                return self.returncode

            def kill(self):
                self.returncode = -1

            def wait(self, timeout):
                if self.returncode is None:
                    state["elapsed"] += timeout
                    raise guard.subprocess.TimeoutExpired("fixture", timeout)
                return self.returncode

        child = Child()

        def spawn(*args, **kwargs):
            state.update(elapsed=2.0, spawned=True)
            return child

        def utc():
            return self.now - timedelta(seconds=88) if state["spawned"] else self.now

        with patch.object(guard, "_utc_now", side_effect=utc), \
                patch.object(guard.time, "monotonic", side_effect=lambda: state["elapsed"]), \
                patch.object(guard.subprocess, "Popen", side_effect=spawn):
            result = guard.launch_guarded(self.safe, self.hard, ["fixture"])
        self.assertLessEqual(state["elapsed"], 10.001)
        self.assertTrue(result["timedOut"])
        self.assertEqual(-1, child.returncode)

    def test_interrupt_immediately_after_spawn_stops_owned_child(self):
        state = {"spawned": False, "interrupted": False}

        class Child:
            pid, returncode = 123, None

            def poll(self):
                return self.returncode

            def kill(self):
                self.returncode = -1

            def wait(self, timeout):
                return self.returncode

        child = Child()

        def spawn(*args, **kwargs):
            state["spawned"] = True
            return child

        def utc():
            if state["spawned"] and not state["interrupted"]:
                state["interrupted"] = True
                raise KeyboardInterrupt()
            return self.now

        with patch.object(guard, "_utc_now", side_effect=utc), \
                patch.object(guard.subprocess, "Popen", side_effect=spawn):
            with self.assertRaises(KeyboardInterrupt):
                guard.launch_guarded(self.safe, self.hard, ["fixture"])
        self.assertIsNotNone(child.poll())
        self.assertEqual(-1, child.returncode)

    def test_completion_past_monotonic_deadline_cannot_report_success(self):
        state = {"elapsed": 0.0, "wall": self.now}

        class Child:
            pid, returncode = 123, None

            def poll(self):
                return self.returncode

            def kill(self):
                self.returncode = -1

            def wait(self, timeout):
                # The supervisor resumes late, after the child has exited.
                state.update(elapsed=11.0, wall=state["wall"] - timedelta(seconds=100))
                self.returncode = 0
                return 0

        with patch.object(guard, "_utc_now", side_effect=lambda: state["wall"]), \
                patch.object(guard.time, "monotonic", side_effect=lambda: state["elapsed"]), \
                patch.object(guard.subprocess, "Popen", return_value=Child()):
            result = guard.launch_guarded(self.safe, self.hard, ["fixture"])
        self.assertEqual("DENIED", result["status"])
        self.assertTrue(result["timedOut"])


if __name__ == "__main__":
    unittest.main()
