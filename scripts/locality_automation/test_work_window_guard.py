"""UTC regression and isolated subprocess fixtures; no source/GPS commands."""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

from scripts.locality_automation import work_window_guard as guard

SAFE = "2026-10-02T00:29:00Z"
HARD = "2026-10-02T00:34:10Z"


class UtcGuardTests(unittest.TestCase):
    def status(self, now, safe=SAFE, hard=HARD, minimum=0):
        return guard._evaluate(*guard._config(safe, hard, minimum), guard.parse_utc(now))

    def test_powershell_local_ticks_regression_and_offset_equivalence(self):
        self.assertEqual(guard.parse_utc(SAFE), guard.parse_utc("2026-10-02T02:29:00+02:00"))
        for late in ("2026-10-02T00:30:20.3324200Z", "2026-10-02T00:30:25.7721759Z",
                     "2026-10-02T02:30:25.7721759+02:00"):
            with self.subTest(late=late):
                self.assertEqual("safe_start_reached", self.status(late)["reason"])

    def test_cross_midnight_offsets_and_aware_utc(self):
        value = guard.parse_utc("2026-10-02T01:29:00+02:00")
        self.assertEqual(datetime(2026, 10, 1, 23, 29, tzinfo=timezone.utc), value)
        self.assertIs(value.tzinfo, timezone.utc)
        self.assertEqual("ALLOWED", self.status("2026-10-01T23:59:59Z")["status"])

    def test_naive_ambiguous_and_malformed_timestamps_rejected(self):
        bad = ("2026-10-02T00:29:00", "2026-10-02 00:29:00Z", "2026-10-02T00:29Z",
               "2026-10-02T00:29:00 Europe/Paris", "2026-10-02T00:29:00-00:00",
               "2026-10-02T00:29:00+02:99", "2026-10-02T00:29:00+24:00",
               "2026-02-30T00:29:00Z", "2026-10-02T00:29:60Z", "", None)
        for value in bad:
            with self.subTest(value=value), self.assertRaises(ValueError):
                guard.parse_utc(value)
        with self.assertRaises(ValueError):
            guard._evaluate(*guard._config(SAFE, HARD, 0), datetime(2026, 10, 2))

    def test_equal_cutoffs_and_late_clock_deny(self):
        for now, reason in ((SAFE, "safe_start_reached"), (HARD, "hard_deadline_reached"),
                            ("2026-10-03T00:00:00Z", "hard_deadline_reached")):
            self.assertEqual(reason, self.status(now)["reason"])
        with self.assertRaises(ValueError):
            guard._config(HARD, HARD, 0)

    def test_estimate_equal_denies_and_zero_before_cutoff_allows(self):
        self.assertEqual("insufficient_remaining_time",
                         self.status("2026-10-02T00:28:00Z", minimum=370)["reason"])
        self.assertEqual("ALLOWED", self.status("2026-10-02T00:28:00Z", minimum=369)["status"])
        self.assertEqual("ALLOWED", self.status("2026-10-02T00:28:00Z", minimum=0)["status"])
        for minimum in (-1, float("inf"), float("nan"), True):
            with self.subTest(minimum=minimum), self.assertRaises(ValueError):
                guard._config(SAFE, HARD, minimum)
        with self.assertRaises(ValueError):
            guard._config(HARD, SAFE, 0)

    def test_actual_clock_entry_point_and_cli_denial_json(self):
        with patch.object(guard, "_utc_now", return_value=guard.parse_utc(SAFE)):
            self.assertEqual("DENIED", guard.check_window(SAFE, HARD)["status"])
        completed = subprocess.run([sys.executable, str(Path(guard.__file__).resolve()),
                                    "--safe-start", "2000-01-01T00:00:00Z", "--hard-deadline",
                                    "2000-01-01T00:01:00Z"], capture_output=True, text=True, check=False)
        self.assertEqual(2, completed.returncode)
        self.assertEqual("hard_deadline_reached", json.loads(completed.stdout)["reason"])
        rejected = subprocess.run([sys.executable, str(Path(guard.__file__).resolve()),
                                   "--safe-start", SAFE, "--hard-deadline", HARD, "--now", SAFE],
                                  capture_output=True, text=True, check=False)
        self.assertEqual(2, rejected.returncode)


class GuardedLauncherTests(unittest.TestCase):
    def test_concurrent_receipt_reservation_permits_only_one_launcher(self):
        with tempfile.TemporaryDirectory() as temp:
            record, barrier = Path(temp) / "execution.json", threading.Barrier(2)
            before = guard.parse_utc("2026-10-02T00:28:00Z")

            def contender():
                barrier.wait(timeout=5)
                try:
                    return guard.launch_guarded(SAFE, HARD, ["fixture"], record=record)
                except FileExistsError:
                    return {"status": "DENIED", "reason": "receipt_already_exists"}

            with patch.object(guard, "_utc_now", return_value=before), \
                    patch.object(guard.subprocess, "Popen") as popen:
                popen.return_value.pid, popen.return_value.returncode = 123, 0
                popen.return_value.poll.return_value = 0
                with ThreadPoolExecutor(max_workers=2) as pool:
                    results = list(pool.map(lambda _: contender(), range(2)))
            self.assertEqual(1, popen.call_count)
            completed = [result for result in results if result["status"] == "COMPLETED"]
            self.assertEqual(1, len(completed))
            self.assertEqual(completed[0], json.loads(record.read_text(encoding="utf-8")))
            self.assertEqual(1, sum(result["status"] == "DENIED" for result in results))

    def test_owner_change_preserves_foreign_receipt(self):
        with tempfile.TemporaryDirectory() as temp:
            record, result = Path(temp) / "execution.json", {"status": "ALLOWED"}
            receipt = guard._reserve_record(record, result)
            foreign = '{"status":"RUNNING","recordOwner":"someone-else"}'
            record.write_text(foreign, encoding="utf-8")
            with self.assertRaises(ValueError):
                guard._write_record(receipt, {"status": "COMPLETED"})
            self.assertEqual(foreign, record.read_text(encoding="utf-8"))

    def test_failed_launch_keeps_denied_receipt_without_pid(self):
        with tempfile.TemporaryDirectory() as temp:
            record = Path(temp) / "failure.json"
            before = guard.parse_utc("2026-10-02T00:28:59Z")
            with patch.object(guard, "_utc_now", return_value=before), \
                    patch.object(guard.subprocess, "Popen", side_effect=OSError("fixture missing")):
                result = guard.launch_guarded(SAFE, HARD, ["missing-fixture-executable"], record=record)
            self.assertEqual("DENIED", result["status"])
            self.assertEqual("launch_failed", result["reason"])
            self.assertIsNone(result["processId"])
            self.assertIsNone(result["startedAtUtc"])
            self.assertEqual(result, json.loads(record.read_text()))

    def test_second_gate_stops_late_spawn_before_popen(self):
        before, late = guard.parse_utc("2026-10-02T00:28:59Z"), guard.parse_utc(SAFE)
        with patch.object(guard, "_utc_now", side_effect=[before, late]), \
                patch.object(guard.subprocess, "Popen") as popen:
            result = guard.launch_guarded(SAFE, HARD, [sys.executable, "-c", "pass"])
        popen.assert_not_called()
        self.assertEqual("safe_start_reached", result["reason"])
        self.assertIsNone(result["processId"])

    def test_start_scheduling_delay_kills_only_new_child(self):
        before, late = guard.parse_utc("2026-10-02T00:28:59Z"), guard.parse_utc(SAFE)
        with patch.object(guard, "_utc_now", side_effect=[before, before, late, late]), \
                patch.object(guard.subprocess, "Popen") as popen:
            process = popen.return_value
            process.pid, process.returncode = 123, -1
            process.poll.side_effect = [None, -1]
            result = guard.launch_guarded(SAFE, HARD, [sys.executable, "-c", "pass"])
        process.kill.assert_called_once_with()
        self.assertEqual("gate_expired_during_spawn", result["reason"])
        self.assertFalse(popen.call_args.kwargs["shell"])

    def test_success_records_argv_pid_aware_times_from_existing_cwd(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            marker, record = root / "success.txt", root / "execution.json"
            now = datetime.now(timezone.utc)
            argv = [sys.executable, "-c", "import pathlib,sys; pathlib.Path(sys.argv[1]).write_text('ok')",
                    str(marker)]
            result = guard.launch_guarded(guard._stamp(now + timedelta(seconds=10)),
                                         guard._stamp(now + timedelta(seconds=20)), argv, record=record)
            self.assertEqual("COMPLETED", result["status"])
            self.assertEqual("ok", marker.read_text())
            self.assertEqual(argv, result["argv"])
            self.assertEqual(str(Path.cwd()), result["cwd"])
            self.assertIsInstance(result["processId"], int)
            for key in ("startedAtUtc", "finishedAtUtc"):
                self.assertIs(guard.parse_utc(result[key]).tzinfo, timezone.utc)
            self.assertEqual(result, json.loads(record.read_text()))
            self.assertEqual(0, result["exitCode"])

    def test_hard_timeout_only_affects_owned_temp_fixture(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            fixture, marker, record = root / "fixture.py", root / "too-late.txt", root / "timeout.json"
            fixture.write_text("import pathlib,sys,time\ntime.sleep(10)\npathlib.Path(sys.argv[1]).write_text('late')\n")
            unrelated = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(10)"],
                                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            try:
                now = datetime.now(timezone.utc)
                result = guard.launch_guarded(guard._stamp(now + timedelta(seconds=0.7)),
                                             guard._stamp(now + timedelta(seconds=0.9)),
                                             [sys.executable, str(fixture), str(marker)], record=record)
                self.assertEqual("DENIED", result["status"])
                self.assertEqual("hard_deadline_reached", result["reason"])
                self.assertTrue(result["timedOut"])
                self.assertIsNotNone(result["exitCode"])
                self.assertFalse(marker.exists())
                self.assertIsNone(unrelated.poll())
                self.assertEqual(result, json.loads(record.read_text()))
            finally:
                unrelated.kill()
                unrelated.wait(timeout=5)


if __name__ == "__main__":
    unittest.main()
