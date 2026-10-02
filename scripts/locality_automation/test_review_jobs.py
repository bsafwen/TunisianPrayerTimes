"""Adapter and existing-engine integration checks using local temporary fixtures."""
from __future__ import annotations

from copy import deepcopy
import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

from . import engine, review_jobs
from .common import pin, read, write


class ReviewJobsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.script = self.root / "helper.py"
        self.script.write_text(
            "import pathlib, sys\n"
            "pathlib.Path(sys.argv[2]).write_text(pathlib.Path(sys.argv[1]).read_text())\n",
            encoding="utf-8",
        )
        self.source = self.root / "source.txt"
        self.source.write_text("temporary source evidence", encoding="utf-8")

    def job(self, name: str, *, source: Path | None = None, script: Path | None = None, deps=()):
        source = source or self.source
        script = script or self.script
        return {
            "id": name, "kind": "offline", "argv": [sys.executable, "-B", str(script), str(source), str(self.root / (name + ".txt"))],
            "cwd": str(self.root), "inputs": [pin(source)],
            "outputs": [str(self.root / (name + ".txt"))], "deps": list(deps), "resources": [],
            "executableDependencies": [pin(script)],
        }

    def spec(self, *jobs, scope="source-only"):
        return {"schemaVersion": 1, "governorate": "temporary-governorate", "inputScope": scope, "jobs": list(jobs)}

    def test_prepare_is_detached_deterministic_and_never_runs_commands(self):
        supplied = self.spec(self.job("review"))
        before = deepcopy(supplied)
        with patch.object(engine, "run", side_effect=AssertionError("prepare ran engine")), patch.object(
            engine.subprocess, "Popen", side_effect=AssertionError("prepare launched command")
        ), patch.object(engine.subprocess, "run", side_effect=AssertionError("prepare ran command")):
            first = review_jobs.build_review_plan(supplied)
            second = review_jobs.build_review_plan(supplied)
        self.assertEqual(before, supplied)
        self.assertEqual(first, second)
        self.assertFalse((self.root / "review.txt").exists())
        self.assertEqual(2, len(first["jobs"][0]["inputs"]))
        supplied["jobs"][0]["argv"].append("changed")
        self.assertNotIn("changed", first["jobs"][0]["argv"])

    def test_identity_changes_only_for_declared_job_inputs_or_argv(self):
        other = self.root / "other.txt"
        other.write_text("other source", encoding="utf-8")
        supplied = self.spec(self.job("first"), self.job("second", source=other))
        initial = review_jobs.build_review_plan(supplied)
        other.write_text("changed sibling evidence", encoding="utf-8")
        supplied["jobs"][1]["inputs"] = [pin(other)]
        changed = review_jobs.build_review_plan(supplied)
        self.assertEqual(initial["jobs"][0], changed["jobs"][0])
        self.assertNotEqual(initial["jobs"][1]["contentKey"], changed["jobs"][1]["contentKey"])
        supplied["jobs"][0]["argv"].append("--different-option")
        argv_changed = review_jobs.build_review_plan(supplied)
        self.assertNotEqual(changed["jobs"][0]["contentKey"], argv_changed["jobs"][0]["contentKey"])
        self.assertEqual(changed["jobs"][1], argv_changed["jobs"][1])

    def test_scope_is_explicit_and_does_not_inject_assets(self):
        source_only = review_jobs.build_review_plan(self.spec(self.job("job")))
        live = review_jobs.build_review_plan(self.spec(self.job("job"), scope="live-assets"))
        self.assertEqual(source_only["jobs"], live["jobs"])
        self.assertEqual("live-assets", live["inputScope"])
        missing = self.spec(self.job("job"))
        del missing["inputScope"]
        with self.assertRaisesRegex(ValueError, "inputScope"):
            review_jobs.build_review_plan(missing)

    def test_verified_pin_merge_does_not_rehash_but_next_prepare_verifies_again(self):
        supplied = self.spec(self.job("job"))
        with patch.object(review_jobs, "pin", wraps=pin) as checked:
            first = review_jobs.build_review_plan(supplied)
            self.assertEqual(2, checked.call_count)  # One data file and one executable.
            second = review_jobs.build_review_plan(supplied)
            self.assertEqual(4, checked.call_count)
            self.assertEqual(first, second)
            self.source.write_text("changed between preparations", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "File changed"):
                review_jobs.build_review_plan(supplied)
            self.assertEqual(6, checked.call_count)

    def test_executable_manifest_and_exact_current_pins_are_required(self):
        job = self.job("job")
        del job["executableDependencies"]
        with self.assertRaisesRegex(ValueError, "executableDependencies"):
            review_jobs.build_review_plan(self.spec(job))
        job["executableDependencies"] = [pin(self.source)]
        with self.assertRaisesRegex(ValueError, "entry executable"):
            review_jobs.build_review_plan(self.spec(job))
        job = self.job("job")
        self.script.write_text("raise SystemExit(1)\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "File changed"):
            review_jobs.build_review_plan(self.spec(job))

    def test_local_module_is_checked_without_importing_it(self):
        module = self.root / "side_effect.py"
        module.write_text("raise RuntimeError('must never import during prepare')\n", encoding="utf-8")
        job = self.job("module", script=module)
        job["argv"] = [sys.executable, "-m", "side_effect"]
        plan = review_jobs.build_review_plan(self.spec(job))
        self.assertEqual(job["argv"], plan["jobs"][0]["argv"])
        job["executableDependencies"] = [pin(self.script)]
        with self.assertRaisesRegex(ValueError, "entry executable"):
            review_jobs.build_review_plan(self.spec(job))

    def test_python_entrypoint_parser_does_not_treat_script_arguments_as_modules(self):
        job = self.job("job")
        job["argv"] = [sys.executable, "-B", "-X", "utf8", str(self.script), "-m", "not_a_module", "data.js"]
        plan = review_jobs.build_review_plan(self.spec(job))
        self.assertEqual(job["argv"], plan["jobs"][0]["argv"])
        job["argv"] = [sys.executable, "-c", "print('inline')"]
        with self.assertRaisesRegex(ValueError, "Inline Python"):
            review_jobs.build_review_plan(self.spec(job))

    def test_no_future_input_placeholders_or_status_based_imports(self):
        job = self.job("job")
        job["inputs"] = [{"file": str(self.root / "future.json"), "sha256": "a" * 64}]
        with self.assertRaisesRegex(ValueError, "Missing file"):
            review_jobs.build_review_plan(self.spec(job))
        job = self.job("job")
        job["status"] = "SUCCESS"
        with self.assertRaisesRegex(ValueError, "unsupported fields.*status"):
            review_jobs.build_review_plan(self.spec(job))
        job = self.job("job")
        job["kind"] = "network"
        with self.assertRaisesRegex(ValueError, "kind must be offline"):
            review_jobs.build_review_plan(self.spec(job))

    def test_engine_rejects_collisions_unknown_dependencies_and_cycles(self):
        first, second = self.job("first"), self.job("second")
        second["outputs"] = first["outputs"]
        with self.assertRaisesRegex(ValueError, "output collision"):
            review_jobs.build_review_plan(self.spec(first, second))
        first["deps"] = ["missing"]
        with self.assertRaisesRegex(ValueError, "unknown dependency"):
            review_jobs.build_review_plan(self.spec(first))
        first, second = self.job("first", deps=["second"]), self.job("second", deps=["first"])
        with self.assertRaisesRegex(ValueError, "cyclic dependency"):
            review_jobs.build_review_plan(self.spec(first, second))

    def test_imported_outputs_are_exact_known_files_and_dependencies_run(self):
        result = self.root / "prior-result.json"
        result.write_text('{"status":"UNRESOLVED"}', encoding="utf-8")
        prior = {"id": "prior", "kind": "offline", "cwd": str(self.root), "inputs": [],
                 "outputs": [], "deps": [], "resources": [], "importedOutputs": [pin(result)]}
        next_job = self.job("next", source=result, deps=["prior"])
        plan = review_jobs.build_review_plan(self.spec(prior, next_job))
        state = engine.run(plan, self.root / "state", allowed={"offline"})
        self.assertEqual("imported", state["jobs"]["prior"]["status"])
        self.assertEqual("success", state["jobs"]["next"]["status"])
        self.assertEqual(result.read_text(), (self.root / "next.txt").read_text())
        bad = deepcopy(prior)
        bad["importedOutputs"][0]["sha256"] = "a" * 64
        with self.assertRaisesRegex(ValueError, "File changed"):
            review_jobs.build_review_plan(self.spec(bad))
        bad = deepcopy(prior)
        bad["outputs"] = [str(self.root / "not-pinned.txt")]
        with self.assertRaisesRegex(ValueError, "pin every output"):
            review_jobs.build_review_plan(self.spec(bad))

    def test_existing_report_status_never_becomes_cached_success(self):
        job = self.job("report")
        output = Path(job["outputs"][0])
        output.write_text('{"status":"SUCCESS"}', encoding="utf-8")
        plan = review_jobs.build_review_plan(self.spec(job))
        state = engine.run(plan, self.root / "state", allowed={"offline"})
        self.assertEqual("stale", state["jobs"]["report"]["status"])
        self.assertEqual("output", state["jobs"]["report"]["reasonCode"])

    def test_failed_and_held_dependency_do_not_block_independent_job(self):
        failing = self.root / "fail.py"
        failing.write_text("raise SystemExit(7)\n", encoding="utf-8")
        plan = review_jobs.build_review_plan(self.spec(
            self.job("failed", script=failing), self.job("dependent", deps=["failed"]), self.job("independent")
        ))
        state = engine.run(plan, self.root / "state", allowed={"offline"}, workers=2)
        self.assertEqual("failed", state["jobs"]["failed"]["status"])
        self.assertEqual("held", state["jobs"]["dependent"]["status"])
        self.assertEqual("success", state["jobs"]["independent"]["status"])
        self.assertFalse((self.root / "dependent.txt").exists())

    def test_persisted_held_sibling_does_not_block_independent_job(self):
        plan = review_jobs.build_review_plan(self.spec(self.job("held"), self.job("independent")))
        state_dir = self.root / "state"
        engine.set_paused(state_dir, True)  # Fixture only: never the real workspace.
        engine.run(plan, state_dir, allowed={"offline"})
        saved = read(state_dir / "state.json")
        saved["jobs"]["held"].update(status="held", reasonCode="stale_running", reason="fixture uncertain run")
        write(state_dir / "state.json", saved, replace=True)
        engine.set_paused(state_dir, False)
        state = engine.run(plan, state_dir, allowed={"offline"})
        self.assertEqual("held", state["jobs"]["held"]["status"])
        self.assertEqual("success", state["jobs"]["independent"]["status"])
        self.assertFalse((self.root / "held.txt").exists())

    def test_engine_marks_changed_input_stale_without_rerunning_or_poisoning_sibling(self):
        other = self.root / "other.txt"
        other.write_text("sibling evidence", encoding="utf-8")
        plan = review_jobs.build_review_plan(self.spec(self.job("changed"), self.job("sibling", source=other)))
        state_dir = self.root / "state"
        engine.run(plan, state_dir, allowed={"offline"}, workers=2)
        self.source.write_text("changed after successful execution", encoding="utf-8")
        with patch.object(engine.subprocess, "Popen", side_effect=AssertionError("must not rerun")):
            state = engine.run(plan, state_dir, allowed={"offline"}, workers=2)
        self.assertEqual("stale", state["jobs"]["changed"]["status"])
        self.assertEqual("input", state["jobs"]["changed"]["reasonCode"])
        self.assertEqual("success", state["jobs"]["sibling"]["status"])
        self.assertEqual("temporary source evidence", (self.root / "changed.txt").read_text())

    def test_cli_writes_once_and_prints_compact_counts(self):
        spec_file = self.root / "spec.json"
        output = self.root / "plan.json"
        write(spec_file, self.spec(self.job("review")))
        with contextlib.redirect_stdout(io.StringIO()) as stdout:
            self.assertEqual(0, review_jobs.main(["--spec", str(spec_file), "--output", str(output)]))
        counts = json.loads(stdout.getvalue())
        self.assertEqual((1, 1, 0), (counts["jobs"], counts["offline"], counts["imported"]))
        self.assertEqual(pin(output), counts["plan"])
        before = output.read_bytes()
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
            review_jobs.main(["--spec", str(spec_file), "--output", str(output)])
        self.assertEqual(2, error.exception.code)
        self.assertEqual(before, output.read_bytes())
        self.assertFalse((self.root / "review.txt").exists())

    def test_cli_plan_destination_cannot_occupy_a_declared_result(self):
        spec_file = self.root / "spec.json"
        job = self.job("result")
        write(spec_file, self.spec(job))
        output = Path(job["outputs"][0])
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
            review_jobs.main(["--spec", str(spec_file), "--output", str(output)])
        self.assertEqual(2, error.exception.code)
        self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
