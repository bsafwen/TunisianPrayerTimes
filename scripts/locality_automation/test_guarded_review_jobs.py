"""Guard/engine integration with temporary offline fixtures only."""
from __future__ import annotations

from copy import deepcopy
from datetime import datetime, timedelta, timezone
from pathlib import Path
import json
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

from . import engine, review_jobs, work_window_guard_jobs_v2 as guard
from .common import pin, read


class GuardedReviewJobsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.now = datetime(2030, 1, 1, tzinfo=timezone.utc)
        self.source = self.root / "measured-input.json"
        self.source.write_text('{"status":"UNRESOLVED","evidence":"temporary"}', encoding="utf-8")
        self.script = self.root / "offline-fixture.py"
        self.script.write_text(
            "import pathlib, sys\n"
            "print('fixture stdout cwd=' + str(pathlib.Path.cwd()), flush=True)\n"
            "print('fixture stderr', file=sys.stderr, flush=True)\n"
            "pathlib.Path(sys.argv[2]).write_bytes(pathlib.Path(sys.argv[1]).read_bytes())\n",
            encoding="utf-8",
        )

    def window(self, name):
        return {
            "policyRevision": 1,
            "safeStartUtc": guard._stamp(self.now + timedelta(minutes=5)),
            "hardDeadlineUtc": guard._stamp(self.now + timedelta(minutes=10)),
            "minimumRemainingSeconds": 120,
            "executionReceipt": str(self.root / (name + ".execution.json")),
        }

    def job(self, name="review", *, source=None, script=None, deps=(), stage="preflight"):
        source, script = source or self.source, script or self.script
        return {
            "id": name, "kind": "offline", "readOnlyOffline": True, "reviewStage": stage,
            "argv": [sys.executable, "-B", str(script), str(source), str(self.root / (name + ".json"))],
            "cwd": str(self.root), "inputs": [pin(source)],
            "outputs": [str(self.root / (name + ".json"))],
            "deps": list(deps), "resources": [], "executableDependencies": [pin(script)],
            "workWindow": self.window(name),
        }

    def spec(self, *jobs):
        return {"schemaVersion": 1, "governorate": "temporary-governorate",
                "inputScope": "source-only", "jobs": list(jobs)}

    def prepare(self, *jobs):
        return review_jobs.build_review_plan(self.spec(*jobs))

    def run_plan(self, plan, *, state="state", now=None):
        with patch.object(guard, "_utc_now", return_value=now or self.now), patch.object(
            engine.subprocess, "run", side_effect=AssertionError("guarded jobs must call the in-process guard")
        ):
            return engine.run(plan, self.root / state, allowed={"offline"}, workers=2)

    def assert_no_pins(self, current):
        self.assertNotEqual("success", current["status"])
        self.assertEqual([], current["outputPins"])

    def test_success_uses_in_process_guard_cwd_and_engine_log_and_pins_receipt(self):
        plan = self.prepare(self.job())
        with patch.object(guard, "launch_guarded", wraps=guard.launch_guarded) as launched:
            state = self.run_plan(plan)
        launched.assert_called_once()
        current = state["jobs"]["review"]
        self.assertEqual("success", current["status"])
        receipt = Path(plan["jobs"][0]["workWindow"]["executionReceipt"])
        execution = read(receipt)
        self.assertEqual("COMPLETED", execution["status"])
        self.assertEqual(0, execution["exitCode"])
        self.assertEqual(str(self.root), execution["cwd"])
        self.assertEqual(plan["jobs"][0]["argv"], execution["argv"])
        self.assertEqual(execution, current["guardExecution"])
        by_file = {item["file"]: item for item in current["outputPins"]}
        self.assertEqual({str(receipt), str(self.root / "review.json")}, set(by_file))
        self.assertEqual(pin(receipt)["sha256"], by_file[str(receipt)]["sha256"])
        self.assertEqual(receipt.stat().st_size, by_file[str(receipt)]["size"])
        self.assertEqual(self.source.read_bytes(), (self.root / "review.json").read_bytes())
        log = Path(current["log"]).read_text(encoding="utf-8")
        self.assertIn("fixture stdout cwd=" + str(self.root), log)
        self.assertIn("fixture stderr", log)

    def test_expired_window_denies_without_creating_a_child_or_output(self):
        plan = self.prepare(self.job())
        with patch.object(guard.subprocess, "Popen", side_effect=AssertionError("expired gate spawned child")) as child:
            state = self.run_plan(plan, now=self.now + timedelta(minutes=11))
        child.assert_not_called()
        self.assert_no_pins(state["jobs"]["review"])
        self.assertFalse((self.root / "review.json").exists())
        receipt = read(plan["jobs"][0]["workWindow"]["executionReceipt"])
        self.assertEqual("DENIED", receipt["status"])
        self.assertEqual("hard_deadline_reached", receipt["reason"])
        self.assertIsNone(receipt["processId"])

    def test_changed_input_before_run_blocks_guard_and_child(self):
        plan = self.prepare(self.job())
        self.source.write_text("changed after preparation", encoding="utf-8")
        with patch.object(guard, "launch_guarded", side_effect=AssertionError("stale input reached guard")) as launched:
            state = self.run_plan(plan)
        launched.assert_not_called()
        current = state["jobs"]["review"]
        self.assert_no_pins(current)
        self.assertEqual(("stale", "input"), (current["status"], current["reasonCode"]))
        self.assertFalse(Path(plan["jobs"][0]["workWindow"]["executionReceipt"]).exists())

    def test_existing_output_blocks_guard_even_when_report_claims_success(self):
        plan = self.prepare(self.job())
        output = self.root / "review.json"
        output.write_text('{"status":"SUCCESS","accepted":true}', encoding="utf-8")
        before = output.read_bytes()
        with patch.object(guard, "launch_guarded", side_effect=AssertionError("existing output reached guard")) as launched:
            state = self.run_plan(plan)
        launched.assert_not_called()
        current = state["jobs"]["review"]
        self.assert_no_pins(current)
        self.assertEqual(("stale", "output"), (current["status"], current["reasonCode"]))
        self.assertEqual(before, output.read_bytes())

    def test_input_changed_by_child_cannot_be_pinned_as_success(self):
        mutator = self.root / "mutator.py"
        mutator.write_text(
            "import pathlib, sys\n"
            "pathlib.Path(sys.argv[2]).write_text('temporary result')\n"
            "pathlib.Path(sys.argv[1]).write_text('input changed during child execution')\n",
            encoding="utf-8",
        )
        plan = self.prepare(self.job(script=mutator))
        state = self.run_plan(plan)
        current = state["jobs"]["review"]
        self.assert_no_pins(current)
        self.assertEqual(("stale", "input"), (current["status"], current["reasonCode"]))
        execution = read(plan["jobs"][0]["workWindow"]["executionReceipt"])
        self.assertEqual(("COMPLETED", 0), (execution["status"], execution["exitCode"]))
        self.assertTrue((self.root / "review.json").exists())

    def test_next_plan_uses_fresh_measured_output_and_existing_engine_dependency(self):
        first_job = self.job("measure")
        first = self.prepare(first_job)
        future = self.root / "measure.json"
        premature = self.job("replay")
        premature["inputs"] = [{"file": str(future), "sha256": "a" * 64}]
        with self.assertRaisesRegex(ValueError, "Missing file"):
            self.prepare(first_job, premature)
        self.assertEqual("success", self.run_plan(first, state="first-state")["jobs"]["measure"]["status"])
        measured_pin = pin(future)
        imported = {"id": "measured", "kind": "offline", "cwd": str(self.root),
                    "inputs": [], "outputs": [], "deps": [], "resources": [],
                    "importedOutputs": [measured_pin]}
        next_plan = self.prepare(imported, self.job("replay", source=future, deps=["measured"], stage="replay"))
        state = self.run_plan(next_plan, state="next-state")
        self.assertEqual("imported", state["jobs"]["measured"]["status"])
        self.assertEqual("success", state["jobs"]["replay"]["status"])
        self.assertIn(measured_pin, next_plan["jobs"][1]["inputs"])
        self.assertEqual(future.read_bytes(), (self.root / "replay.json").read_bytes())

    def test_completed_receipt_can_be_cached_but_cannot_launch_in_another_state(self):
        supplied = self.job()
        plan = self.prepare(supplied)
        first = self.run_plan(plan)
        with patch.object(guard.subprocess, "Popen", side_effect=AssertionError("cached job spawned child")) as child:
            cached = self.run_plan(plan)
        child.assert_not_called()
        self.assertEqual(first["jobs"]["review"]["outputPins"], cached["jobs"]["review"]["outputPins"])
        self.assertEqual("success", cached["jobs"]["review"]["status"])
        with self.assertRaises(ValueError):
            self.prepare(supplied)
        # The already-prepared plan also cannot bypass freshness via another state directory.
        (self.root / "review.json").unlink()
        with patch.object(guard.subprocess, "Popen", side_effect=AssertionError("duplicate receipt spawned child")) as child:
            state = self.run_plan(plan, state="second-state")
        child.assert_not_called()
        self.assert_no_pins(state["jobs"]["review"])
        self.assertFalse((self.root / "review.json").exists())

    def test_cached_success_requires_unchanged_completed_zero_exit_receipt(self):
        for change in ("missing", "bytes", "nonzero", "denied"):
            with self.subTest(change=change):
                job = self.job(change)
                plan = self.prepare(job)
                state_name = "state-" + change
                self.assertEqual("success", self.run_plan(plan, state=state_name)["jobs"][change]["status"])
                receipt = Path(job["workWindow"]["executionReceipt"])
                if change == "missing":
                    receipt.unlink()
                elif change == "bytes":
                    receipt.write_text(receipt.read_text(encoding="utf-8") + "\n", encoding="utf-8")
                else:
                    execution = read(receipt)
                    execution["exitCode"] = 7 if change == "nonzero" else 0
                    execution["status"] = "COMPLETED" if change == "nonzero" else "DENIED"
                    receipt.write_text(json.dumps(execution), encoding="utf-8")
                    # Even a rewritten state pin must not make a denied/nonzero receipt reusable.
                    saved = read(self.root / state_name / "state.json")
                    for item in saved["jobs"][change]["outputPins"]:
                        if item["file"] == str(receipt):
                            item.update(sha256=pin(receipt)["sha256"], size=receipt.stat().st_size)
                    (self.root / state_name / "state.json").write_text(json.dumps(saved), encoding="utf-8")
                with patch.object(guard.subprocess, "Popen", side_effect=AssertionError("invalid receipt spawned child")) as child:
                    state = self.run_plan(plan, state=state_name)
                child.assert_not_called()
                self.assertNotEqual("success", state["jobs"][change]["status"])

    def test_window_changes_identity_and_existing_job_requires_a_new_revision(self):
        supplied = self.job()
        plan = self.prepare(supplied)
        key = plan["jobs"][0]["contentKey"]
        for field, value in (
            ("safeStartUtc", guard._stamp(self.now + timedelta(minutes=4))),
            ("hardDeadlineUtc", guard._stamp(self.now + timedelta(minutes=9))),
            ("minimumRemainingSeconds", 121),
            ("executionReceipt", str(self.root / "different.execution.json")),
        ):
            with self.subTest(field=field):
                changed = deepcopy(supplied)
                changed["workWindow"][field] = value
                self.assertNotEqual(key, self.prepare(changed)["jobs"][0]["contentKey"])
        self.run_plan(plan)
        changed = deepcopy(supplied)
        changed["workWindow"]["executionReceipt"] = str(self.root / "revision.execution.json")
        changed_plan = self.prepare(changed)
        with patch.object(guard.subprocess, "Popen", side_effect=AssertionError("changed revision spawned child")) as child:
            state = self.run_plan(changed_plan)
        child.assert_not_called()
        self.assertEqual(("stale", "definition"),
                         (state["jobs"]["review"]["status"], state["jobs"]["review"]["reasonCode"]))

    def test_policy_is_exact_finite_and_requires_resolved_fresh_receipt(self):
        valid = self.job()
        bad_windows = [None, [], {}]
        for field in valid["workWindow"]:
            missing = deepcopy(valid["workWindow"])
            del missing[field]
            bad_windows.append(missing)
        updates = (
            ("policyRevision", True), ("policyRevision", 0), ("policyRevision", 2), ("policyRevision", "1"),
            ("minimumRemainingSeconds", 119), ("minimumRemainingSeconds", -1),
            ("minimumRemainingSeconds", True), ("minimumRemainingSeconds", "120"),
            ("minimumRemainingSeconds", float("nan")), ("minimumRemainingSeconds", float("inf")),
            ("safeStartUtc", "2030-01-01T00:05:00"),
            ("hardDeadlineUtc", valid["workWindow"]["safeStartUtc"]),
            ("executionReceipt", "relative-receipt.json"),
            ("executionReceipt", str(self.root / "missing-parent" / "receipt.json")),
            ("executionReceipt", str(self.root / "unused" / ".." / "receipt.json")),
            ("unknownPolicyField", True),
        )
        for field, value in updates:
            changed = deepcopy(valid["workWindow"])
            changed[field] = value
            bad_windows.append(changed)
        prepared = self.prepare(valid)
        for bad_window in bad_windows:
            with self.subTest(window=bad_window):
                job = deepcopy(valid)
                job["workWindow"] = bad_window
                with self.assertRaises(ValueError):
                    self.prepare(job)
                direct = deepcopy(prepared)
                direct["jobs"][0]["workWindow"] = bad_window
                with self.assertRaises(ValueError):
                    engine.validate_plan(direct)

    def test_guarded_jobs_require_explicit_read_only_offline_and_review_stage(self):
        valid = self.job()
        prepared = self.prepare(valid)
        changes = (("kind", "network"), ("readOnlyOffline", False), ("readOnlyOffline", 1),
                   ("reviewStage", "install"), ("reviewStage", ""),
                   ("importedOutputs", []), ("importedOutputs", [pin(self.source)]))
        for field, value in changes:
            with self.subTest(field=field, value=value):
                job = deepcopy(valid)
                job[field] = value
                with self.assertRaises(ValueError):
                    self.prepare(job)
                direct = deepcopy(prepared)
                direct["jobs"][0][field] = value
                with self.assertRaises(ValueError):
                    engine.validate_plan(direct)
        for field in ("readOnlyOffline", "reviewStage"):
            job = deepcopy(valid)
            del job[field]
            with self.assertRaises(ValueError):
                self.prepare(job)

    def test_receipt_paths_cannot_collide_with_outputs_or_other_receipts(self):
        first, second = self.job("first"), self.job("second")
        first["workWindow"]["executionReceipt"] = first["outputs"][0]
        with self.assertRaises(ValueError):
            self.prepare(first)
        first = self.job("first")
        second["workWindow"]["executionReceipt"] = first["workWindow"]["executionReceipt"]
        with self.assertRaises(ValueError):
            self.prepare(first, second)

    def test_nonzero_exit_never_pins_partial_outputs(self):
        failing = self.root / "fails-after-output.py"
        failing.write_text("import pathlib,sys\npathlib.Path(sys.argv[2]).write_text('partial')\nraise SystemExit(7)\n",
                           encoding="utf-8")
        plan = self.prepare(self.job(script=failing))
        current = self.run_plan(plan)["jobs"]["review"]
        self.assert_no_pins(current)
        self.assertEqual("failed", current["status"])
        execution = read(plan["jobs"][0]["workWindow"]["executionReceipt"])
        self.assertEqual(("COMPLETED", 7), (execution["status"], execution["exitCode"]))
        self.assertTrue((self.root / "review.json").exists())

    def test_hard_timeout_kills_only_mocked_child_and_never_pins_outputs(self):
        plan = self.prepare(self.job())
        process = Mock()
        process.pid, process.returncode = 123, None
        process.poll.return_value = None

        def killed():
            process.returncode = -1
            process.poll.return_value = -1

        process.kill.side_effect = killed
        process.wait.return_value = -1
        before, late = self.now, self.now + timedelta(minutes=11)
        # A real short deadline would violate the >=120s policy. Move the aware
        # UTC clock only after the mocked child passes both launch gates.
        with patch.object(guard, "_utc_now", side_effect=[before, before, before, late, late]), patch.object(
            guard.subprocess, "Popen", return_value=process
        ) as child, patch.object(engine.subprocess, "run", side_effect=AssertionError("used subprocess.run")):
            state = engine.run(plan, self.root / "state", allowed={"offline"})
        child.assert_called_once()
        process.kill.assert_called_once_with()
        current = state["jobs"]["review"]
        self.assert_no_pins(current)
        execution = read(plan["jobs"][0]["workWindow"]["executionReceipt"])
        self.assertEqual("DENIED", execution["status"])
        self.assertTrue(execution["timedOut"])
        self.assertEqual("hard_deadline_reached", execution["reason"])

    def test_paused_engine_does_not_call_guard_or_reserve_receipt(self):
        plan = self.prepare(self.job())
        state_dir = self.root / "paused-state"
        engine.set_paused(state_dir, True)
        with patch.object(guard, "launch_guarded", side_effect=AssertionError("paused engine launched")) as launched:
            state = self.run_plan(plan, state="paused-state")
        launched.assert_not_called()
        self.assertTrue(state["paused"])
        self.assertEqual("pending", state["jobs"]["review"]["status"])
        self.assertFalse(Path(plan["jobs"][0]["workWindow"]["executionReceipt"]).exists())

    def test_report_label_is_preserved_without_geographic_acceptance(self):
        self.source.write_text('{"status":"SUCCESS","note":"report label only"}', encoding="utf-8")
        plan = self.prepare(self.job())
        state = self.run_plan(plan)
        self.assertEqual("success", state["jobs"]["review"]["status"])
        self.assertEqual("SUCCESS", read(self.root / "review.json")["status"])
        self.assertIn("not geographic acceptance or verification credit", plan["qualification"])
        self.assertNotIn("accepted", state["jobs"]["review"])
        self.assertNotIn("verificationCredit", state["jobs"]["review"])


if __name__ == "__main__":
    unittest.main()
