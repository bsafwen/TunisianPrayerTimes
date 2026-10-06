"""Dispatch filtering regressions, using isolated realistic compact metadata."""
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from scripts.locality_automation.review_candidate_queue import filter_candidates


class CandidateQueueTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.body = b"packed-slice-0000"
        self.binary = self.root / "neighborhoods.bin"
        self.binary.write_bytes(b"NPOL\0\0\0\1" + self.body)
        self.feature = {"id": "osm:relation:7111785", "name": "الفضلين", "parentName": "معتمدية طبلبة",
                        "aliases": ["El fadhline"], "offset": 8, "length": len(self.body)}
        self.metadata = self.save("neighborhoods.json", {"features": [self.feature]})
        self.receipt = self.save("receipt.json", {"installedAssets": {
            "neighborhoods.json": self.pin(self.metadata), "neighborhoods.bin": self.pin(self.binary)}})
        self.pointer = self.save("run.json", {"practicalProgress": self.pin(self.receipt)})
        self.summary = {"explicitFullSourceBoundaryLocalityCodes": [], "explicitFullSourceBoundaryLocationCount": 0,
                        "validatedLocationCodes": [], "validationIssues": [], "sourceOnlyAuditIssues": [],
                        "reportingIssues": [], "currentAssetPinsVerified": True, "practicalChainVerified": True}
        self.report = self.save("report.json", {"summary": self.summary, "sourceFiles": [
            {"path": str(self.pointer), "sha256": self.pin(self.pointer)["sha256"], "kind": "pointer"}]})
        self.row = {"officialCode": "326052", "id": self.feature["id"], "name": self.feature["name"],
                    "parentName": self.feature["parentName"], "geojson": str(self.root / "a/candidate-source.geojson"),
                    "sourcePdfURL": "https://example.invalid/source.pdf", "rawNativePath": None,
                    "sourceExtraction": None, "administrativeScope": None,
                    "sliceSha256": hashlib.sha256(self.body).hexdigest()}
        self.triage = self.save("triage.json", {"acceptedFullCount": 0, "acceptedFullCodes": [],
            "currentJsonSha256": "0" * 64, "currentBinSha256": "0" * 64, "exactSourceMatches": [self.row]})

    def save(self, name, value):
        path = self.root / name
        path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
        return path

    def pin(self, path):
        return {"file": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}

    def refresh_current(self):
        self.save("neighborhoods.json", {"features": [self.feature]})
        self.save("receipt.json", {"installedAssets": {"neighborhoods.json": self.pin(self.metadata),
                                                        "neighborhoods.bin": self.pin(self.binary)}})
        self.save("run.json", {"practicalProgress": self.pin(self.receipt)})
        self.refresh_report()

    def refresh_report(self):
        self.save("report.json", {"summary": self.summary, "sourceFiles": [
            {"path": str(self.pointer), "sha256": self.pin(self.pointer)["sha256"], "kind": "pointer"}]})

    def rows(self, rows):
        self.save("triage.json", {"exactSourceMatches": rows})

    def inspect(self, assigned=()):
        return filter_candidates(self.triage, self.report, self.metadata, self.binary, assigned)

    def test_real_already_full_failure_excluded_before_missing_identity_or_slice(self):
        self.summary["explicitFullSourceBoundaryLocalityCodes"] = ["326052"]
        self.summary["explicitFullSourceBoundaryLocationCount"] = 1
        self.refresh_report()
        self.row["id"] = "obsolete-id"
        self.row["sliceSha256"] = "0" * 64
        self.rows([self.row])
        result = self.inspect()
        self.assertEqual("READY_FOR_FRESH_SOURCE_REVIEW", result["status"])
        self.assertEqual([], result["actionableCandidates"])
        self.assertEqual("ALREADY_FULL_SOURCE", result["skipped"][0]["reason"])

    def test_changed_slice_is_stale(self):
        self.binary.write_bytes(b"NPOL\0\0\0\1" + b"changed-slice0000")
        self.refresh_current()
        result = self.inspect()
        self.assertEqual("STALE_CURRENT_SLICE_CHANGED", result["skipped"][0]["reason"])
        self.assertTrue(result["skipped"][0]["requiresFreshTriage"])

    def test_currently_assigned_excluded(self):
        result = self.inspect(["326052"])
        self.assertEqual("CURRENTLY_ASSIGNED", result["skipped"][0]["reason"])

    def test_partial_geographic_upgrade_allowed_with_no_live_code(self):
        self.summary["validatedLocationCodes"] = ["326052"]
        self.refresh_report()
        before = {p: p.read_bytes() for p in self.root.iterdir() if p.is_file()}
        result = self.inspect()
        row = result["actionableCandidates"][0]
        self.assertTrue(row["existingGeographicValidation"])
        self.assertTrue(row["fullSourceUpgradeCandidate"])
        self.assertEqual("cached_source_code_requires_independent_binding", row["codeIdentityStatus"])
        self.assertFalse(row["freshSourceValidated"])
        self.assertEqual(0, result["newGeographicCredit"])
        self.assertEqual(before, {p: p.read_bytes() for p in self.root.iterdir() if p.is_file()})

    def test_repacked_offset_with_same_slice_remains_warm_lead(self):
        self.binary.write_bytes(b"NPOL\0\0\0\1" + b"padding" + self.body)
        self.feature["offset"] = 15
        self.refresh_current()
        result = self.inspect()
        self.assertEqual(15, result["actionableCandidates"][0]["currentOffset"])

    def test_stale_report_after_receipt_refresh_holds_without_queue(self):
        self.save("run.json", {"practicalProgress": self.pin(self.receipt), "newEpoch": True})
        result = self.inspect()
        self.assertEqual("HOLD", result["status"])
        self.assertEqual([], result["actionableCandidates"])
        self.assertIsNone(result["epoch"])

    def test_full_count_mismatch_and_missing_report_hold(self):
        self.summary["explicitFullSourceBoundaryLocationCount"] = 1
        self.refresh_report()
        self.assertEqual("HOLD", self.inspect()["status"])
        self.report.unlink()
        self.assertEqual("HOLD", self.inspect()["status"])

    def test_missing_report_source_pins_holds(self):
        self.save("report.json", {"summary": self.summary, "sourceFiles": []})
        self.assertEqual("HOLD", self.inspect()["status"])

    def test_missing_current_id_is_stale(self):
        self.row["id"] = "absent-current-id"
        self.rows([self.row])
        self.assertEqual("STALE_CURRENT_ID_MISSING_OR_AMBIGUOUS", self.inspect()["skipped"][0]["reason"])

    def test_epoch_changes_with_current_assignments(self):
        first = self.inspect()["epoch"]
        second = self.inspect(["326052"])["epoch"]
        self.assertNotEqual(first["sha256"], second["sha256"])
        self.assertEqual(["326052"], second["assignedCodes"])

    def test_duplicate_refs_deduped_alternatives_and_full_path_groups_preserved(self):
        alternate = {**self.row, "geojson": str(self.root / "b/candidate-source.geojson")}
        alternative_pdf = {**self.row, "sourcePdfURL": "https://example.invalid/alternate.pdf"}
        self.rows([self.row, deepcopy(self.row), alternate, alternative_pdf])
        result = self.inspect()
        self.assertEqual(3, len(result["actionableCandidates"]))
        self.assertEqual(2, len(result["groups"]))
        self.assertEqual([0, 1], result["actionableCandidates"][0]["equivalentTriageRowIndices"])
        self.assertEqual("EQUIVALENT_DUPLICATE", result["skipped"][0]["reason"])

    def test_absent_code_name_alias_allowed_and_name_parent_mismatch_stale(self):
        self.row["name"] = "El fadhline"
        self.rows([self.row])
        self.assertEqual(1, len(self.inspect()["actionableCandidates"]))
        for key in ("name", "parentName"):
            row = {**self.row, key: "different"}
            self.rows([row])
            self.assertEqual("STALE_CURRENT_NAME_OR_PARENT_CHANGED", self.inspect()["skipped"][0]["reason"])

    def test_present_live_code_must_match(self):
        self.feature["officialCode"] = "326053"
        self.refresh_current()
        self.assertEqual("STALE_CURRENT_CODE_ID_BINDING", self.inspect()["skipped"][0]["reason"])

    def test_live_asset_changes_during_read_holds(self):
        from scripts.locality_automation import review_candidate_queue as module
        original = module._Snapshot.verify_unchanged
        def mutate(snapshot):
            self.binary.write_bytes(b"changed")
            original(snapshot)
        with patch.object(module._Snapshot, "verify_unchanged", mutate):
            result = self.inspect()
        self.assertEqual("HOLD", result["status"])
        self.assertEqual([], result["groups"])


if __name__ == "__main__":
    unittest.main()
