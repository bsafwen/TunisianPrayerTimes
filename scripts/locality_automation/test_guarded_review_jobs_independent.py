"""Independent guarded-job edge cases, using temporary offline fixtures only."""
from __future__ import annotations

from copy import deepcopy
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

from . import engine, review_jobs, work_window_guard_jobs_v2 as guard
from .common import pin, read


class IndependentGuardedJobsTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.now = datetime(2030, 1, 1, tzinfo=timezone.utc)
        self.source = self.root / "source.json"
        self.source.write_text('{"status":"HOLD","evidence":"synthetic"}', encoding="utf-8")
        self.script = self.root / "fixture.py"
        self.script.write_text(
            "import json,os,pathlib,sys\n"
            "print(json.dumps({'cwd':os.getcwd(),'proxy':{n:os.environ.get(n) for n in ('HTTP_PROXY','HTTPS_PROXY')}}),flush=True)\n"
            "print('stderr '+os.getcwd(),file=sys.stderr,flush=True)\n"
            "pathlib.Path(sys.argv[2]).write_bytes(pathlib.Path(sys.argv[1]).read_bytes())\n",
            encoding="utf-8",
        )

    def job(self, name="review", *, cwd=None, script=None, deps=(), stage="preflight"):
        return {
            "id": name, "kind": "offline", "readOnlyOffline": True, "reviewStage": stage,
            "argv": [sys.executable, "-B", str(script or self.script), str(self.source), str(self.root / (name + ".json"))],
            "cwd": str(cwd or self.root), "inputs": [pin(self.source)],
            "outputs": [str(self.root / (name + ".json"))], "deps": list(deps),
            "resources": [], "executableDependencies": [pin(script or self.script)],
            "workWindow": {
                "policyRevision": 1,
                "safeStartUtc": guard._stamp(self.now + timedelta(minutes=5)),
                "hardDeadlineUtc": guard._stamp(self.now + timedelta(minutes=10)),
                "minimumRemainingSeconds": 120,
                "executionReceipt": str(self.root / (name + ".execution.json")),
            },
        }

    def spec(self, *jobs):
        return {"schemaVersion": 1, "governorate": "synthetic", "inputScope": "source-only", "jobs": list(jobs)}

    def prepare(self, *jobs):
        return review_jobs.build_review_plan(self.spec(*jobs))

    def run_plan(self, plan, *, workers=1, now=None, state="state"):
        with patch.object(guard, "_utc_now", return_value=now or self.now):
            return engine.run(plan, self.root / state, allowed={"offline"}, workers=workers)

    def test_known_guard_wrappers_are_rejected_case_insensitively_before_spawn(self):
        prepared = self.prepare(self.job())
        wrappers = (
            "work_window_guard.py", "WORK_WINDOW_GUARD.PY",
            "work_window_guard_jobs_v2.py", "WORK_WINDOW_GUARD_JOBS_V2.PY",
            "scripts.locality_automation.work_window_guard",
            "scripts.locality_automation.work_window_guard_jobs_v2",
            "-mscripts.locality_automation.work_window_guard",
            "-mscripts.locality_automation.work_window_guard_jobs_v2",
            "-mSCRIPTS.LOCALITY_AUTOMATION.WORK_WINDOW_GUARD_JOBS_V2",
        )
        with patch.object(guard.subprocess, "Popen") as spawn:
            for wrapper in wrappers:
                with self.subTest(wrapper=wrapper):
                    plan = deepcopy(prepared)
                    plan["jobs"][0]["argv"] = ([sys.executable, wrapper] if wrapper.startswith("-m")
                                               else [sys.executable, "-m", wrapper])
                    with self.assertRaises(ValueError):
                        engine.validate_plan(plan)
            executable_receipt = deepcopy(prepared)
            executable_receipt["jobs"][0]["argv"][0] = executable_receipt["jobs"][0]["workWindow"]["executionReceipt"]
            with self.assertRaises(ValueError):
                engine.validate_plan(executable_receipt)
        spawn.assert_not_called()

    def test_extreme_numeric_policy_and_unknown_fields_reject_cleanly(self):
        valid = self.job()
        for field, value in (("minimumRemainingSeconds", 10**400), ("minimumRemainingSeconds", False),
                             ("minimumRemainingSeconds", float("nan")), ("policyRevision", True),
                             ("safeStartUtc", "2030-01-01T00:05:00-00:00")):
            with self.subTest(field=field, value=value):
                changed = deepcopy(valid)
                changed["workWindow"][field] = value
                with self.assertRaises(ValueError):
                    self.prepare(changed)
        unknown_job = deepcopy(valid)
        unknown_job["accepted"] = True
        with self.assertRaises(ValueError):
            self.prepare(unknown_job)
        unknown_spec = self.spec(valid)
        unknown_spec["cachedSuccess"] = True
        with self.assertRaises(ValueError):
            review_jobs.build_review_plan(unknown_spec)

    def test_job_timeout_is_preserved_and_holds_dependent_stage(self):
        sleeper = self.root / "sleep.py"
        sleeper.write_text("import pathlib,sys,time\ntime.sleep(5)\npathlib.Path(sys.argv[2]).write_text('late')\n", encoding="utf-8")
        first = self.job("preflight", script=sleeper)
        first["timeoutSeconds"] = 0.15
        replay = self.job("replay", deps=["preflight"], stage="replay")
        plan = self.prepare(first, replay)
        with patch.object(guard, "launch_guarded", wraps=guard.launch_guarded) as launched:
            state = self.run_plan(plan)
        self.assertEqual(1, launched.call_count)
        self.assertEqual(0.15, launched.call_args.kwargs["timeout_seconds"])
        current = state["jobs"]["preflight"]
        self.assertEqual(("failed", "timeout"), (current["status"], current["reasonCode"]))
        self.assertEqual([], current["outputPins"])
        self.assertEqual("command_timeout", current["guardExecution"]["reason"])
        self.assertEqual(("held", "upstream"), (state["jobs"]["replay"]["status"], state["jobs"]["replay"]["reasonCode"]))
        self.assertFalse((self.root / "preflight.json").exists())
        self.assertFalse((self.root / "replay.execution.json").exists())

    def test_denied_preflight_cannot_dispatch_replay_even_with_multiple_workers(self):
        plan = self.prepare(self.job("preflight"), self.job("replay", deps=["preflight"], stage="replay"))
        with patch.object(guard.subprocess, "Popen") as spawn:
            state = self.run_plan(plan, workers=2, now=self.now + timedelta(minutes=11))
        spawn.assert_not_called()
        self.assertEqual("failed", state["jobs"]["preflight"]["status"])
        self.assertEqual("held", state["jobs"]["replay"]["status"])
        self.assertFalse((self.root / "replay.execution.json").exists())

    def test_receipt_ownership_failure_after_spawn_stops_owned_child_and_fails_engine(self):
        plan = self.prepare(self.job())
        receipt = Path(plan["jobs"][0]["workWindow"]["executionReceipt"])

        class Child:
            pid, returncode = 4242, None
            killed = False

            def poll(self):
                return self.returncode

            def kill(self):
                self.killed = True
                self.returncode = -1

            def wait(self, timeout):
                return self.returncode

        child = Child()
        original_write = guard._write_record

        def ownership_failure(owned_receipt, result):
            receipt.write_text('{"recordOwner":"foreign-owner","status":"RUNNING"}', encoding="utf-8")
            return original_write(owned_receipt, result)

        with patch.object(guard.subprocess, "Popen", return_value=child), patch.object(
            guard, "_write_record", side_effect=ownership_failure
        ):
            state = self.run_plan(plan)
        self.assertTrue(child.killed)
        self.assertEqual(-1, child.returncode)
        self.assertEqual("foreign-owner", read(receipt)["recordOwner"])
        self.assertEqual("failed", state["jobs"]["review"]["status"])
        self.assertEqual([], state["jobs"]["review"]["outputPins"])

    def test_incomplete_returned_receipt_cannot_be_promoted_to_cache_success(self):
        plan = self.prepare(self.job())
        job = plan["jobs"][0]

        def incomplete(*args, **kwargs):
            result = {"status": "RUNNING", "exitCode": 0, "timedOut": False, "reason": None,
                      "argv": job["argv"], "cwd": job["cwd"]}
            Path(kwargs["record"]).write_text(json.dumps(result), encoding="utf-8")
            Path(job["outputs"][0]).write_text("partial output", encoding="utf-8")
            return result

        with patch.object(guard, "launch_guarded", side_effect=incomplete):
            state = self.run_plan(plan)
        self.assertEqual("failed", state["jobs"]["review"]["status"])
        self.assertEqual([], state["jobs"]["review"]["outputPins"])
        with patch.object(guard, "launch_guarded", side_effect=AssertionError("failed run was retried")) as launched:
            repeated = self.run_plan(plan)
        launched.assert_not_called()
        self.assertEqual("failed", repeated["jobs"]["review"]["status"])

    def test_concurrent_engine_jobs_keep_cwd_logs_and_inherited_environment_isolated(self):
        first_cwd, second_cwd = self.root / "one", self.root / "two"
        first_cwd.mkdir()
        second_cwd.mkdir()
        cwd_before, stdout_before, stderr_before = Path.cwd(), sys.stdout, sys.stderr
        environment_before = dict(os.environ)
        proxy_before = {name: os.environ.get(name) for name in ("HTTP_PROXY", "HTTPS_PROXY")}
        plan = self.prepare(self.job("one", cwd=first_cwd), self.job("two", cwd=second_cwd))
        with patch.object(engine.subprocess, "run", side_effect=AssertionError("guard CLI wrapper used")):
            state = self.run_plan(plan, workers=2)
        for name, cwd in (("one", first_cwd), ("two", second_cwd)):
            self.assertEqual("success", state["jobs"][name]["status"])
            text = Path(state["jobs"][name]["log"]).read_text(encoding="utf-8")
            observation = json.loads(next(line for line in text.splitlines() if line.startswith("{")))
            self.assertEqual(str(cwd), observation["cwd"])
            self.assertEqual(proxy_before, observation["proxy"])
            self.assertIn("stderr " + str(cwd), text)
        self.assertEqual(cwd_before, Path.cwd())
        self.assertIs(stdout_before, sys.stdout)
        self.assertIs(stderr_before, sys.stderr)
        self.assertEqual(environment_before, dict(os.environ))

    def test_review_stage_changes_content_key_and_existing_revision_is_stale(self):
        supplied = self.job()
        original = self.prepare(supplied)
        changed = deepcopy(supplied)
        changed["reviewStage"] = "replay"
        revised = self.prepare(changed)
        self.assertNotEqual(original["jobs"][0]["contentKey"], revised["jobs"][0]["contentKey"])
        self.assertEqual("success", self.run_plan(original)["jobs"]["review"]["status"])
        with patch.object(guard, "launch_guarded", side_effect=AssertionError("changed stage spawned")) as launched:
            state = self.run_plan(revised)
        launched.assert_not_called()
        self.assertEqual(("stale", "definition"), (state["jobs"]["review"]["status"], state["jobs"]["review"]["reasonCode"]))


if __name__ == "__main__":
    unittest.main()
