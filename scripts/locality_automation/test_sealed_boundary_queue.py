"""Failure contracts for mechanical admission; no source decisions are mocked."""
import json
from pathlib import Path
import tempfile
import unittest

from scripts.locality_automation.run_sealed_boundary_queue import (
    active_control, checked, pin, verify_linked_acceptance, verify_preflight,
)


class AdmissionFailureContracts(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def write(self, name, value):
        path = self.root / name
        path.write_text(json.dumps(value), encoding="utf-8")
        return path

    def test_modified_pinned_input_is_rejected(self):
        path = self.write("proof.json", {"pass": True})
        reference = pin(path)
        path.write_text('{"pass": false}', encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Pinned input changed"):
            checked(reference)

    def test_paused_or_transferred_owner_is_rejected(self):
        control = dict(phase="working", iteration=15, acceptanceOwner="/root",
            acceptanceActor="/root", subagentsAllowed=False, windowStartUtc="start", deadlineUtc="end")
        path = self.write("control.json", control)
        manifest = dict(control=str(path), iteration=15, owner="/root", windowStartUtc="start", deadlineUtc="end")
        self.assertEqual(active_control(manifest), control)
        for change in ({"phase": "paused"}, {"acceptanceOwner": "/other"},
                       {"deadlineUtc": "extended"}, {"subagentsAllowed": True}):
            self.write("control.json", {**control, **change})
            with self.assertRaises(ValueError):
                active_control(manifest)

    def test_wrong_case_preflight_never_authorizes_record(self):
        item = {"code": "123456", "report": {"file": "report", "sha256": "a"}}
        manifest = {"recorder": {"file": "recorder", "sha256": "b"}}
        result = dict(status="PASS_READ_ONLY_EXACT_CAF_PRE_RECORD_PREFIX", codes=["999999"],
            report=item["report"], recorder=manifest["recorder"], ledgerWrites=False, assetsUnchanged=True)
        with self.assertRaisesRegex(ValueError, "preflight output contract"):
            verify_preflight(result, item, manifest)

    def test_partial_or_duplicate_ledger_link_is_rejected(self):
        output = self.root / "acceptance"
        output.mkdir()
        receipt = dict(status="VALIDATED_CURRENT_SOURCE_GEOMETRY", boundaryLocalityCodes=["123456"],
            fullSourceBoundaryLocalityCodes=["123456"], scopedBoundaryLocalityCodes=[],
            boundaryScopeRecorded=True, assetChanges=False)
        receipt_path = output / "current-validation-receipt.json"
        receipt_path.write_text(json.dumps(receipt), encoding="utf-8")
        frozen = output / "independent-gps-report.json"
        frozen.write_text('{}', encoding="utf-8")
        reference = pin(receipt_path)
        run = self.write("run.json", {"currentGeometryValidations": [reference]})
        handoff = self.write("handoff.json", {"currentGeometryValidations": []})
        asset = self.write("unchanged-asset.json", {"asset": True})
        manifest = dict(run=str(run), handoff=str(handoff), assets=[pin(asset)])
        item = {"code": "123456", "report": pin(frozen)}
        with self.assertRaisesRegex(ValueError, "exactly once in handoff"):
            verify_linked_acceptance(item, manifest, output)
        self.write("handoff.json", {"currentGeometryValidations": [reference, reference]})
        with self.assertRaisesRegex(ValueError, "exactly once in handoff"):
            verify_linked_acceptance(item, manifest, output)
        self.write("handoff.json", {"currentGeometryValidations": [reference]})
        self.assertEqual(verify_linked_acceptance(item, manifest, output), reference)


if __name__ == "__main__":
    unittest.main()
