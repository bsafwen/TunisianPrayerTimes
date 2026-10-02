"""Current-file drift and zero-credit checks with isolated synthetic receipts."""
from pathlib import Path
import json
import tempfile
import unittest

from scripts.locality_automation.common import pin
from scripts.locality_automation.review_checkpoint import ASSETS, checkpoint


class ReviewCheckpointTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.assets = self.root / "android-app/app/src/main/assets"
        self.assets.mkdir(parents=True)
        for name in ASSETS:
            (self.assets / name).write_text("original:" + name)
        self.receipt = self.save("receipt.json", {"installedAssets": {n: pin(self.assets / n) for n in ASSETS},
                                                 "appliedAtUtc": "2026-10-01T14:00:00Z"})
        self.run = self.save("run.json", {"practicalProgress": pin(self.receipt)})
        self.handoff = self.save("handoff.json", {"practicalProgress": pin(self.receipt)})
        self.summary = {"uniqueValidatedLocations": 2, "uniqueInstalledCorrectionLocations": 1,
                        "explicitFullSourceBoundaryLocationCount": 1, "explicitScopedBoundaryLocationCount": 1,
                        "unknownBoundaryScopeLocationCount": 0, "validatedLocationCodes": ["1", "2"],
                        "explicitFullSourceBoundaryLocalityCodes": ["1"],
                        "validationIssues": [], "sourceOnlyAuditIssues": [], "reportingIssues": []}
        self.report = self.save("report.json", {"summary": self.summary,
                           "sourceFiles": [{"path": str(self.run), "sha256": pin(self.run)["sha256"], "kind": "pointer"}]})

    def save(self, name, data):
        p = self.root / name
        p.write_text(json.dumps(data))
        return p

    def inspect(self, **kwargs):
        return checkpoint(self.root, self.run, self.handoff, self.report, **kwargs)

    def test_readonly_current_state_and_missing_ready_file(self):
        before = {p: p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
        result = self.inspect(pending_packages=[self.root / "missing-ready.json"])
        self.assertEqual("CHECKPOINT_MATCHES_CURRENT_FILES", result["status"])
        self.assertEqual(0, result["geographicCreditAdded"])
        self.assertEqual("paused", result["validationStateDeclaredByCaller"])
        self.assertEqual("WAITING_FOR_PACKAGE", result["pendingPackages"][0]["status"])
        self.assertEqual(before, {p: p.read_bytes() for p in self.root.rglob("*") if p.is_file()})

    def test_actual_asset_drift_holds(self):
        (self.assets / "neighborhoods.bin").write_bytes(b"changed")
        self.assertIn("receipt_live_asset_mismatch:neighborhoods.bin", self.inspect()["issues"])

    def test_stale_report_source_holds(self):
        self.save("run.json", {"practicalProgress": pin(self.receipt), "unrelated": "changed"})
        self.assertIn("report_source_changed:pointer", self.inspect()["issues"])

    def test_disagreeing_receipt_pointers_hold(self):
        self.save("handoff.json", {"practicalProgress": {"file": "other.json", "sha256": "0" * 64}})
        self.assertIn("run_handoff_receipt_pointers_differ", self.inspect()["issues"])

    def test_duplicate_count_is_not_credit(self):
        self.summary["validatedLocationCodes"] = ["1", "1"]
        self.save("report.json", {"summary": self.summary, "sourceFiles": []})
        result = self.inspect()
        self.assertIn("validated_count_code_list_mismatch", result["issues"])
        self.assertEqual(0, result["geographicCreditAdded"])

    def test_saved_pending_package_is_not_accepted(self):
        p = self.save("ready.json", {"status": "PASS"})
        result = self.inspect(pending_packages=[p])
        self.assertEqual("SAVED_NOT_ACCEPTED", result["pendingPackages"][0]["status"])
        self.assertEqual(1, result["counts"]["explicitFullSourceBoundaryLocationCount"])


if __name__ == "__main__":
    unittest.main()
