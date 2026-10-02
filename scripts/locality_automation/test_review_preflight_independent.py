"""Independent preflight checks using synthetic files only; never run source/GPS QA."""
from __future__ import annotations

import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

from scripts.locality_automation import review_preflight as preflight


class ReviewPreflightIndependentTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="review-preflight-independent-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.evidence = self.root / "evidence"
        self.evidence.mkdir()
        self.package = self.evidence / "source-input-package-ready.json"
        self.raw = self.evidence / "raw.wkb"
        self.adopted = self.evidence / "adopted.wkb"
        self.pdf = self.evidence / "source.pdf"
        for path, contents in ((self.raw, b"raw-distinct"), (self.adopted, b"adopted-distinct"),
                               (self.pdf, b"synthetic-pdf")):
            path.write_bytes(contents)
        self.data = {
            "schemaVersion": 1,
            "status": "FROZEN_COMPLETE_OWN_SOURCE_CURRENT_BODY_INPUTS_PENDING_INDEPENDENT_CURRENT_QA",
            "selectedSourceCodes": ["000001"],
            "sourceRecords": [{"officialCode": "000001", "id": "synthetic:1",
                "sourcePdf": self.pin(self.pdf), "rawSourceGeometry": self.pin(self.raw),
                "expectedAdoptedGeometry": self.pin(self.adopted),
                "scope": "scoped-interface-only", "namedBlueBlackRoles": [],
                "sourceGridPacksExactCurrentBytes": False,
                "specialAdoptionQualification": "An independent adoption review remains required."}],
            "supportingOnlyCodes": ["000002"], "structuralSourceHolds": [],
            "requiredIndependentQa": ["Independently inspect source, adjoining context and GPS."],
            "inputs": {},
        }

    @staticmethod
    def pin(path):
        return {"file": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}

    def inspect(self, **kwargs):
        self.package.write_text(json.dumps(self.data), encoding="utf-8")
        return preflight.inspect_package(self.package, base=self.evidence,
                                         roots=[self.evidence], workers=2, **kwargs)

    def assert_held(self, result):
        self.assertEqual("PREFLIGHT_HOLD", result["status"])
        self.assertEqual(0, result["geographicCreditAdded"])

    def test_nonexact_adoption_scoped_source_and_empty_roles_are_bookkeeping_only(self):
        result = self.inspect()
        self.assertEqual("PREFLIGHT_OK_PENDING_INDEPENDENT_QA", result["status"])
        self.assertEqual(0, result["geographicCreditAdded"])
        self.assertFalse(result["sourceQualifications"][0]["sourceGridPacksExactCurrentBytes"])
        self.assertEqual("scoped-interface-only", result["sourceQualifications"][0]["scope"])

    def test_stale_pin_holds(self):
        self.raw.write_bytes(b"modified-after-pinning")
        self.assert_held(self.inspect())

    def test_duplicate_id_holds(self):
        self.data["selectedSourceCodes"].append("000003")
        record = {**self.data["sourceRecords"][0], "officialCode": "000003"}
        self.data["sourceRecords"].append(record)
        self.assert_held(self.inspect())

    def test_supporting_only_code_cannot_be_selected(self):
        self.data["supportingOnlyCodes"] = ["000001"]
        self.assert_held(self.inspect())

    def test_malformed_pin_path_is_reported_without_exception(self):
        self.data["inputs"]["bad"] = {"file": "\0", "sha256": "a" * 64}
        self.assert_held(self.inspect())

    def test_live_malformed_hash_types_are_reported_without_exception(self):
        live = {}
        for key in preflight.LIVE_KEYS:
            path = self.evidence / (key + ".synthetic")
            path.write_bytes(key.encode("ascii"))
            live[key] = path
            self.data["inputs"][key] = self.pin(path)
        for bad in (None, 123, [], {}, True):
            with self.subTest(hash_type=type(bad).__name__):
                self.data["inputs"]["currentJson"]["sha256"] = bad
                self.assert_held(self.inspect(live_assets=live))

    def test_relative_pin_uses_explicit_base(self):
        self.data["sourceRecords"][0]["sourcePdf"]["file"] = self.pdf.name
        self.assertEqual("PREFLIGHT_OK_PENDING_INDEPENDENT_QA", self.inspect()["status"])

    def test_outside_root_is_not_hashed(self):
        outside = self.root / "outside.synthetic"
        outside.write_bytes(b"outside-evidence")
        self.data["inputs"]["outside"] = self.pin(outside)
        with patch.object(preflight, "_fingerprint", wraps=preflight._fingerprint) as fingerprint:
            self.assert_held(self.inspect())
        self.assertNotIn(outside, [call.args[0] for call in fingerprint.call_args_list])

    def test_known_private_configuration_is_blocked_without_hashing(self):
        for name in (".env", "google-maps.local.js"):
            with self.subTest(name=name):
                private = self.evidence / name
                private.write_bytes(b"synthetic-secret-do-not-read")
                self.data["inputs"]["private"] = self.pin(private)
                with patch.object(preflight, "_fingerprint", wraps=preflight._fingerprint) as fingerprint:
                    self.assert_held(self.inspect())
                self.assertNotIn(private, [call.args[0] for call in fingerprint.call_args_list])

    def test_environment_variant_is_blocked_without_hashing(self):
        private = self.evidence / ".env.local"
        private.write_bytes(b"synthetic-secret-do-not-read")
        self.data["inputs"]["private"] = self.pin(private)
        with patch.object(preflight, "_fingerprint", wraps=preflight._fingerprint) as fingerprint:
            self.assert_held(self.inspect())
        self.assertNotIn(private, [call.args[0] for call in fingerprint.call_args_list])

    def test_private_default_ntfs_stream_is_blocked_without_hashing(self):
        private = self.evidence / ".env"
        private.write_bytes(b"synthetic-secret-do-not-read")
        stream_path = Path(str(private) + "::$DATA")
        self.data["inputs"]["private"] = {**self.pin(private), "file": str(stream_path)}
        with patch.object(preflight, "_fingerprint", wraps=preflight._fingerprint) as fingerprint:
            self.assert_held(self.inspect())
        self.assertNotIn(stream_path, [call.args[0] for call in fingerprint.call_args_list])

    def test_private_package_is_rejected_before_reading(self):
        private = self.evidence / ".env.local"
        private.write_bytes(b"synthetic-secret-do-not-read")
        with patch.object(Path, "read_bytes") as read_bytes:
            with self.assertRaises(ValueError):
                preflight.inspect_package(private, base=self.evidence, roots=[self.evidence])
        read_bytes.assert_not_called()

    def test_private_live_asset_is_rejected_before_hashing(self):
        private = self.evidence / ".env.local"
        private.write_bytes(b"synthetic-secret-do-not-read")
        live = {"currentJson": private, "currentBin": self.adopted}
        with patch.object(preflight, "_fingerprint", wraps=preflight._fingerprint) as fingerprint:
            with self.assertRaises(ValueError):
                self.inspect(live_assets=live)
        self.assertNotIn(private, [call.args[0] for call in fingerprint.call_args_list])

    def test_referenced_json_is_not_hydrated(self):
        private = self.evidence / ".env"
        private.write_bytes(b"synthetic-secret-do-not-read")
        reference = self.evidence / "referenced.json"
        reference.write_text(json.dumps({"indirectPrivate": self.pin(private)}), encoding="utf-8")
        self.data["inputs"]["manifest"] = self.pin(reference)
        with patch.object(preflight, "_fingerprint", wraps=preflight._fingerprint) as fingerprint:
            result = self.inspect()
        self.assertEqual("PREFLIGHT_OK_PENDING_INDEPENDENT_QA", result["status"])
        self.assertNotIn(private, [call.args[0] for call in fingerprint.call_args_list])

    def test_directory_package_is_reported_without_exception(self):
        self.package.mkdir()
        result = preflight.inspect_package(self.package, base=self.evidence, roots=[self.evidence])
        self.assert_held(result)

    def test_unknown_schema_is_held(self):
        self.data["schemaVersion"] = 99
        self.assert_held(self.inspect())

    def test_nonstring_qa_obligation_is_held(self):
        self.data["requiredIndependentQa"] = [None]
        self.assert_held(self.inspect())

    def test_explicit_namespace_mapping(self):
        mapped = self.evidence / "mapped-official"
        mapped.mkdir()
        source = mapped / "mapped-source.pdf"
        source.write_bytes(b"explicit-mapped-source")
        self.data["sourceRecords"][0]["sourcePdf"] = {
            **self.pin(source), "file": "official/mapped-source.pdf"}
        result = self.inspect(reference_roots={"official": mapped})
        self.assertEqual("PREFLIGHT_OK_PENDING_INDEPENDENT_QA", result["status"])

    def test_mapping_traversal_is_blocked_without_hashing(self):
        mapped = self.evidence / "mapped-official"
        mapped.mkdir()
        outside_mapping = self.evidence / "outside-mapping.pdf"
        outside_mapping.write_bytes(b"synthetic-outside-namespace")
        self.data["sourceRecords"][0]["sourcePdf"] = {
            **self.pin(outside_mapping), "file": "official/../outside-mapping.pdf"}
        with patch.object(preflight, "_fingerprint", wraps=preflight._fingerprint) as fingerprint:
            self.assert_held(self.inspect(reference_roots={"official": mapped}))
        self.assertNotIn(outside_mapping, [call.args[0] for call in fingerprint.call_args_list])

    def test_duplicate_namespace_cli_is_rejected_before_inspection(self):
        arguments = ["review_preflight", "--package", str(self.package),
            "--base", str(self.evidence), "--allow-root", str(self.evidence),
            "--output", str(self.evidence / "never-created.json"),
            "--reference-root", "official=" + str(self.evidence),
            "--reference-root", "official=" + str(self.evidence / "other")]
        with patch.object(sys, "argv", arguments), patch.object(sys, "stderr", io.StringIO()), \
                patch.object(preflight, "inspect_package") as inspect:
            with self.assertRaises(SystemExit) as failure:
                preflight.main()
        self.assertEqual(2, failure.exception.code)
        inspect.assert_not_called()

    def test_case_variant_namespace_duplicate_is_rejected(self):
        with self.assertRaises(ValueError):
            self.inspect(reference_roots={"official": self.evidence, "OFFICIAL": self.evidence})

    def test_drive_namespace_prefix_is_rejected(self):
        with self.assertRaises(ValueError):
            self.inspect(reference_roots={"C:": self.evidence})

    def test_live_json_binary_pair_without_names_is_supported(self):
        live = {}
        for key in ("currentJson", "currentBin"):
            path = self.evidence / (key + ".synthetic")
            path.write_bytes(key.encode("ascii"))
            live[key] = path
            self.data["inputs"][key] = self.pin(path)
        result = self.inspect(live_assets=live)
        self.assertEqual("PREFLIGHT_OK_PENDING_INDEPENDENT_QA", result["status"])
        self.assertEqual(2, len(result["liveAssetChecks"]))


if __name__ == "__main__":
    unittest.main()
