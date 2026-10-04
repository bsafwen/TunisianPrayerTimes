"""Regression cases for the failed disposable consumers and strict copy gates."""
import copy
import json
from pathlib import Path
import tempfile
import unittest

from scripts.locality_automation.isolated_consumer_preflight import inspect_workspace, save_diagnostic
from scripts.locality_automation.run_sealed_boundary_queue import pin


class IsolatedPreflightTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source, self.workspace = self.root / "live", self.root / "disposable"
        self.source.mkdir(); self.workspace.mkdir()
        self.dependencies = []
        for relative, body in [("scripts/locality_automation/packed_gps_replay.py", b"exact decoder"),
                               ("scripts/historical/install-receipt.json", b'{"original":"receipt"}')]:
            original, copied = self.source / relative, self.workspace / relative
            original.parent.mkdir(parents=True, exist_ok=True); copied.parent.mkdir(parents=True, exist_ok=True)
            original.write_bytes(body); copied.write_bytes(body)
            self.dependencies.append({"relativePath": relative, "source": pin(original)})
        metadata = self.workspace / "android-app/app/src/main/assets/neighborhoods.json"
        metadata.parent.mkdir(parents=True); metadata.write_text("{}", encoding="utf-8")
        receipt = self.root / "practical.json"
        receipt.write_text(json.dumps({"appliedAtUtc": "2026-10-04T10:00:00Z"}), encoding="utf-8")
        self.ledger = self.root / "run.json"
        self.ledger.write_text(json.dumps({"installedCatalog": pin(metadata), "practicalProgress": pin(receipt)}), encoding="utf-8")
        self.manifest = {"schemaVersion": 1, "sourceRepository": str(self.source), "workspaceRepository": str(self.workspace),
                         "requiredDependencies": self.dependencies, "runLedger": pin(self.ledger),
                         "selectedDecoder": pin(self.workspace / self.dependencies[0]["relativePath"]),
                         "installedReceipt": pin(receipt), "reportClockUtc": "2026-10-04T10:01:00Z"}

    def test_complete_clone_has_no_acceptance_credit(self):
        result = inspect_workspace(self.manifest)
        self.assertEqual(result["status"], "PASS_ISOLATED_CONSUMER_PREFLIGHT")
        self.assertEqual(result["geographicCredit"], 0)
        self.assertFalse(result["originalCompleteConsumerExecuted"])

    def test_original_missing_history_and_wrong_decoder_are_both_reported(self):
        (self.workspace / self.dependencies[1]["relativePath"]).unlink()
        self.manifest["selectedDecoder"] = pin(self.source / self.dependencies[0]["relativePath"])
        kinds = {item["kind"] for item in inspect_workspace(self.manifest)["issues"]}
        self.assertEqual(kinds, {"missing_dependency", "active_decoder_path"})

    def test_clock_cannot_hide_new_installation(self):
        self.manifest["reportClockUtc"] = "2026-10-04T09:59:59Z"
        self.assertEqual(inspect_workspace(self.manifest)["issues"][0]["kind"], "report_clock_precedes_install")
        self.manifest["reportClockUtc"] = "2026-10-04T10:01:00"
        with self.assertRaises(ValueError): inspect_workspace(self.manifest)

    def test_changed_copy_and_bad_original_pin_are_distinct(self):
        (self.workspace / self.dependencies[1]["relativePath"]).write_bytes(b"changed")
        self.assertEqual(inspect_workspace(self.manifest)["issues"][0]["kind"], "changed_dependency")
        (self.source / self.dependencies[1]["relativePath"]).write_bytes(b"bad original")
        with self.assertRaises(ValueError): inspect_workspace(self.manifest)

    def test_duplicate_and_escaping_dependency_paths_are_rejected(self):
        for dependency in [self.dependencies[0], {"relativePath": "../escape", "source": self.dependencies[0]["source"]},
                           {"relativePath": str(self.source / "absolute"), "source": self.dependencies[0]["source"]}]:
            manifest = copy.deepcopy(self.manifest); manifest["requiredDependencies"].append(dependency)
            with self.subTest(dependency=dependency["relativePath"]), self.assertRaises(ValueError):
                inspect_workspace(manifest)

    def test_live_repository_is_never_a_disposable_workspace(self):
        self.manifest["workspaceRepository"] = str(self.source)
        with self.assertRaises(ValueError): inspect_workspace(self.manifest)

    def test_full_rejected_diagnostic_saved_before_assertion_and_never_overwritten(self):
        path = self.root / "rejected-model.json"; model = {"summary": {"validationIssues": ["خطأ"]}, "rows": [[1, 2, 3]]}
        reference = save_diagnostic(path, model)
        self.assertEqual(json.loads(path.read_text(encoding="utf-8")), model)
        self.assertEqual(reference, pin(path))
        with self.assertRaises(FileExistsError): save_diagnostic(path, {"replacement": True})
        self.assertEqual(json.loads(path.read_text(encoding="utf-8")), model)


if __name__ == "__main__":
    unittest.main()
