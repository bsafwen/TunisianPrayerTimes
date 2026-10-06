"""Independent scope pin and failure-publication checks, using synthetic files only."""
from __future__ import annotations

import copy
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

from scripts.locality_automation import scope_references as companion


class ScopeReferenceIndependentTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="scope-reference-independent-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.evidence = self.root / "evidence"
        self.evidence.mkdir()
        self.scope_file = self.evidence / "scope.json"
        self.proof = self.evidence / "proof.dat"
        self.proof.write_bytes(b"one-selected-proof")
        self.data = {"selectedCodes": ["000001"], "supportingCodes": ["000002"],
                     "native": {"type": "Polygon", "coordinates": [[[1, 2], [3, 4], [1, 2]]]},
                     "style": {"cap": None, "join": "round", "clip": False},
                     "source": {**self.pin(self.proof), "bytes": self.proof.stat().st_size,
                                "role": "selected", "qualification": "Scoped source only."}}

    @staticmethod
    def pin(path):
        return {"file": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}

    def save(self):
        self.scope_file.write_text(json.dumps(self.data), encoding="utf-8")
        return self.pin(self.scope_file)

    def inspect(self, **kwargs):
        return companion.canonical_scope(self.save(), base=self.evidence, roots=[self.evidence], **kwargs)

    def assert_hold(self, result):
        self.assertIsNone(result["scope"])
        self.assertEqual(companion.HOLD, result["receipt"]["status"])
        self.assertTrue(result["receipt"]["issues"])
        self.assertEqual(0, result["receipt"]["geographicCreditAdded"])

    def test_preserves_nonpath_facts_missing_fields_and_hashes(self):
        original = copy.deepcopy(self.data)
        result = self.inspect()
        self.assertEqual(companion.OK, result["receipt"]["status"])
        canonical = copy.deepcopy(result["scope"])
        canonical.pop(companion.AUDIT_KEY)
        self.assertEqual(original, canonical)
        self.assertNotIn("lineStyle", canonical["style"])
        self.data["native"] = {"type": "MultiPolygon", "coordinates": [original["native"]["coordinates"]]}
        result = self.inspect()
        self.assertEqual(self.data["native"], result["scope"]["native"])

    def test_inline_pin_metadata_is_verified_and_canonicalized(self):
        inline = self.evidence / "inline-proof.dat"
        inline.write_bytes(b"inline-extra-proof")
        self.data["source"]["metadata"] = {"selected": {**self.pin(inline), "file": inline.name}}
        result = self.inspect()
        self.assertEqual(companion.OK, result["receipt"]["status"])
        self.assertEqual(2, result["receipt"]["explicitPinReferences"])
        self.assertEqual(str(inline), result["scope"]["source"]["metadata"]["selected"]["file"])
        inline.write_bytes(b"modified-inline-extra-proof")
        self.assert_hold(self.inspect())

    def test_opaque_referenced_json_is_hashed_without_hydration(self):
        referenced = self.evidence / "opaque.json"
        referenced.write_text(json.dumps({"nested": {"file": ".env", "sha256": "0" * 64}}))
        self.data["source"] = self.pin(referenced)
        result = self.inspect()
        self.assertEqual(companion.OK, result["receipt"]["status"])
        self.assertEqual(1, result["receipt"]["explicitPinReferences"])

    def test_pointer_escapes_bind_exact_role_without_touching_other_facts(self):
        self.data["a/~b"] = self.data.pop("source")
        frozen = self.evidence / "frozen.dat"
        frozen.write_bytes(self.proof.read_bytes())
        selected = self.data["a/~b"]
        result = self.inspect(bindings=[{"pointer": "/a~1~0b", "declaredFile": selected["file"],
                                        "sha256": selected["sha256"], "target": self.pin(frozen)}])
        self.assertEqual(companion.OK, result["receipt"]["status"])
        actual = result["scope"]["a/~b"]
        self.assertEqual(str(frozen), actual["file"])
        self.assertEqual({k: v for k, v in selected.items() if k != "file"},
                         {k: v for k, v in actual.items() if k != "file"})

    def test_malformed_binding_targets_hold_without_crashing(self):
        selected = self.data["source"]
        for target in (None, [], 4, "target", True):
            with self.subTest(target=target):
                self.assert_hold(self.inspect(bindings=[{"pointer": "/source", "declaredFile": selected["file"],
                    "sha256": selected["sha256"], "target": target}]))

    def test_wrong_stale_unused_and_duplicate_bindings_hold(self):
        selected = self.data["source"]
        row = {"pointer": "/source", "declaredFile": selected["file"],
               "sha256": selected["sha256"], "target": self.pin(self.proof)}
        for rows in ([{**row, "pointer": "/unused"}], [{**row, "declaredFile": "stale"}],
                     [row, row], [{**row, "target": {**row["target"], "sha256": "0" * 64}}]):
            with self.subTest(rows=rows):
                self.assert_hold(self.inspect(bindings=rows))

    def test_binding_target_declared_size_must_match_actual_bytes(self):
        selected = self.data["source"]
        for field in ("bytes", "size"):
            with self.subTest(field=field):
                target = {**self.pin(self.proof), field: 999}
                self.assert_hold(self.inspect(bindings=[{"pointer": "/source", "declaredFile": selected["file"],
                    "sha256": selected["sha256"], "target": target}]))

    def test_duplicate_json_keys_cannot_drop_supplied_scope_facts(self):
        for raw in (b'{"fact":1,"fact":2}',
                    ('{"source":{"file":"missing.dat","sha256":"' + "0" * 64 +
                     '"},"source":' + json.dumps(self.data["source"]) + '}').encode("utf-8")):
            with self.subTest(raw=raw):
                self.scope_file.write_bytes(raw)
                self.assert_hold(companion.canonical_scope(self.pin(self.scope_file),
                    base=self.evidence, roots=[self.evidence]))

    def test_lowercase_hash_contract_is_required_for_check_tree(self):
        self.data["source"]["sha256"] = self.data["source"]["sha256"].upper()
        self.assert_hold(self.inspect())

    def test_conflicting_pin_hashes_hold(self):
        self.data["anotherRole"] = {**self.pin(self.proof), "sha256": "0" * 64}
        self.assert_hold(self.inspect())

    def test_repeated_eligible_file_is_hashed_once(self):
        self.data["otherRole"] = [self.pin(self.proof), {"again": self.pin(self.proof)}]
        with patch.object(companion, "_fingerprint", wraps=companion._fingerprint) as fingerprint:
            result = self.inspect()
        self.assertEqual(companion.OK, result["receipt"]["status"])
        self.assertEqual(3, result["receipt"]["explicitPinReferences"])
        self.assertEqual(1, fingerprint.call_count)

    def test_size_and_bytes_malformed_values_hold(self):
        for field in ("size", "bytes"):
            for value in (True, -1, None, 1.0, "18", 999):
                with self.subTest(field=field, value=value):
                    self.data["source"][field] = value
                    self.assert_hold(self.inspect())
            self.data["source"].pop(field)

    def test_rejected_target_paths_are_never_hashed(self):
        original = self.data["source"]["file"]
        for path in (str(self.evidence / ".env "), str(self.evidence / "google-maps.local.js."),
                     original + "::$DATA", "proof.dat:stream", "../outside.dat", "C:proof.dat",
                     "\\\\?\\C:\\scope-proof.dat", "\\\\server\\share\\proof.dat"):
            with self.subTest(path=path):
                self.data["source"]["file"] = path
                with patch.object(companion, "_fingerprint", wraps=companion._fingerprint) as fingerprint:
                    self.assert_hold(self.inspect())
                fingerprint.assert_not_called()

    def test_namespace_escape_inside_allowed_root_holds_without_hashing(self):
        namespace = self.evidence / "official"
        namespace.mkdir()
        self.data["source"]["file"] = "official/../proof.dat"
        with patch.object(companion, "_fingerprint", wraps=companion._fingerprint) as fingerprint:
            self.assert_hold(self.inspect(reference_roots={"official": namespace}))
        fingerprint.assert_not_called()

    def test_scope_drift_during_hashing_holds(self):
        original = companion._fingerprint
        def mutate(path):
            result = original(path)
            self.scope_file.write_bytes(self.scope_file.read_bytes() + b" ")
            return result
        with patch.object(companion, "_fingerprint", side_effect=mutate):
            self.assert_hold(self.inspect())

    def test_existing_cli_outputs_reject_before_pin_work(self):
        pinned = self.save()
        for existing_name in ("new-scope.json", "new-receipt.json"):
            with self.subTest(existing_name=existing_name):
                output, receipt = self.evidence / "new-scope.json", self.evidence / "new-receipt.json"
                existing = self.evidence / existing_name
                existing.write_bytes(b"existing-result")
                args = ["scope_references", "--scope", str(self.scope_file), "--scope-sha", pinned["sha256"],
                        "--base", str(self.evidence), "--allow-root", str(self.evidence),
                        "--output", str(output), "--receipt", str(receipt)]
                with patch.object(sys, "argv", args), patch.object(sys, "stderr", io.StringIO()), \
                        patch.object(companion, "canonical_scope") as canonical:
                    with self.assertRaises(SystemExit) as failure:
                        companion.main()
                self.assertEqual(2, failure.exception.code)
                canonical.assert_not_called()
                self.assertEqual(b"existing-result", existing.read_bytes())
                existing.unlink()

    def test_publication_drift_keeps_hold_receipt_without_scope(self):
        result = self.inspect()
        output, receipt = self.evidence / "new-scope.json", self.evidence / "new-receipt.json"
        original = companion.os.link
        def mutate(source, target):
            original(source, target)
            self.scope_file.write_bytes(self.scope_file.read_bytes() + b" ")
        with patch.object(companion.os, "link", side_effect=mutate):
            with self.assertRaises(ValueError):
                companion.publish(result, output=output, receipt=receipt)
        self.assertFalse(output.exists())
        self.assertTrue(receipt.exists())
        self.assertEqual(companion.HOLD, json.loads(receipt.read_text())["status"])

    def test_scope_drift_before_publication_retains_failed_receipt(self):
        result = self.inspect()
        output, receipt = self.evidence / "new-scope.json", self.evidence / "new-receipt.json"
        self.scope_file.write_bytes(self.scope_file.read_bytes() + b" ")
        with self.assertRaises(ValueError):
            companion.publish(result, output=output, receipt=receipt)
        self.assertFalse(output.exists())
        self.assertTrue(receipt.exists())
        self.assertEqual(companion.HOLD, json.loads(receipt.read_text())["status"])

    def test_scope_drift_during_receipt_write_keeps_hold_receipt_without_scope(self):
        result = self.inspect()
        output, receipt = self.evidence / "new-scope.json", self.evidence / "new-receipt.json"
        original = companion.write
        def mutate(path, value, **kwargs):
            pinned = original(path, value, **kwargs)
            if value["status"] == companion.OK:
                self.scope_file.write_bytes(self.scope_file.read_bytes() + b" ")
            return pinned
        with patch.object(companion, "write", side_effect=mutate):
            with self.assertRaises(ValueError):
                companion.publish(result, output=output, receipt=receipt)
        self.assertFalse(output.exists())
        self.assertTrue(receipt.exists())
        self.assertEqual(companion.HOLD, json.loads(receipt.read_text())["status"])

    def test_receipt_write_failure_cannot_publish_scope(self):
        result = self.inspect()
        output, receipt = self.evidence / "new-scope.json", self.evidence / "new-receipt.json"
        with patch.object(companion, "write", side_effect=OSError("synthetic receipt write failure")):
            with self.assertRaises(OSError):
                companion.publish(result, output=output, receipt=receipt)
        self.assertFalse(output.exists())

    def test_racing_output_is_preserved(self):
        result = self.inspect()
        output, receipt = self.evidence / "new-scope.json", self.evidence / "new-receipt.json"
        def race(source, target):
            output.write_bytes(b"external-racing-file")
            raise FileExistsError("destination appeared")
        with patch.object(companion.os, "link", side_effect=race):
            with self.assertRaises(FileExistsError):
                companion.publish(result, output=output, receipt=receipt)
        self.assertEqual(b"external-racing-file", output.read_bytes())


if __name__ == "__main__":
    unittest.main()
