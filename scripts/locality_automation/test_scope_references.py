"""Focused synthetic checks; no source/GPS QA, credentials or network."""
from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from scripts.locality_automation import scope_references as subject


class ScopeReferenceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="scope-references-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "source.json"
        self.pdf = self.root / "source.pdf"
        self.pdf.write_bytes(b"synthetic-source")
        self.data = {"roles": {"selected": self.pin(self.pdf)},
                     "scope": "exact supplied qualification", "geometry": {"type": "Polygon"},
                     "style": {"lineCap": [0, 1, 2], "lineJoin": 0}, "supportingCodes": ["support"]}

    def pin(self, path):
        return {"file": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}

    def inspect(self, **kwargs):
        self.source.write_text(json.dumps(self.data), encoding="utf-8")
        return subject.canonical_scope(self.pin(self.source), base=self.root, roots=[self.root], **kwargs)

    def held(self, result):
        self.assertIsNone(result["scope"])
        self.assertEqual(subject.HOLD, result["receipt"]["status"])
        self.assertEqual(0, result["receipt"]["geographicCreditAdded"])

    def test_complete_scope_paths_and_facts_are_preserved(self):
        self.data["roles"]["selected"]["file"] = self.pdf.name
        before = copy.deepcopy(self.data)
        result = self.inspect()
        self.assertEqual(subject.OK, result["receipt"]["status"])
        canonical = result["scope"]
        canonical.pop(subject.AUDIT_KEY)
        before["roles"]["selected"]["file"] = str(self.pdf.resolve())
        self.assertEqual(before, canonical)
        self.assertEqual(self.data["roles"]["selected"]["file"], self.pdf.name)

    def test_explicit_prefix_map(self):
        self.data["roles"]["selected"]["file"] = "official/source.pdf"
        self.assertEqual(subject.OK, self.inspect(reference_roots={"official": self.root})["receipt"]["status"])

    def test_exact_binding_to_frozen_same_hash_pin(self):
        frozen = self.root / "frozen.pdf"
        frozen.write_bytes(self.pdf.read_bytes())
        original = self.data["roles"]["selected"]
        binding = {"pointer": "/roles/selected", "declaredFile": original["file"],
                   "sha256": original["sha256"], "target": self.pin(frozen)}
        result = self.inspect(bindings=[binding])
        self.assertEqual(self.pin(frozen), result["scope"]["roles"]["selected"])
        for bad in ({**binding, "pointer": "/missing"}, {**binding, "declaredFile": "stale"},
                    {**binding, "target": {**binding["target"], "sha256": "0" * 64}}):
            with self.subTest(binding=bad):
                self.held(self.inspect(bindings=[bad]))
        self.held(self.inspect(bindings=[binding, binding]))
        frozen.write_bytes(b"wrong-content")
        self.held(self.inspect(bindings=[binding]))

    def test_every_inline_nested_pin_is_checked_without_json_hydration(self):
        reference = self.root / "reference.json"
        reference.write_text('{"hidden":{"file":"not-opened","sha256":"' + "0" * 64 + '"}}', encoding="utf-8")
        self.data["roles"]["selected"]["inline"] = {"a/b~c": [self.pin(reference), self.pin(self.pdf)]}
        with patch.object(subject, "_fingerprint", wraps=subject._fingerprint) as fingerprint:
            result = self.inspect()
        self.assertEqual(subject.OK, result["receipt"]["status"])
        self.assertEqual(3, result["receipt"]["explicitPinReferences"])
        self.assertEqual(2, fingerprint.call_count)
        self.assertEqual("/roles/selected/inline/a~1b~0c/0", result["receipt"]["references"][1]["pointer"])
        self.data["roles"]["selected"]["inline"]["a/b~c"][1]["sha256"] = "0" * 64
        self.held(self.inspect())

    def test_private_ads_traversal_network_and_outside_paths_are_not_hashed(self):
        rejected = [".env", ".ENV.local", "google-maps.local.js", ".env. ",
                    ".env::$DATA", "source.pdf:stream", "C:relative", "\\root-relative",
                    "\\\\server\\share\\file", "\\\\?\\C:\\file", "../outside.pdf", "\0"]
        for raw in rejected:
            with self.subTest(path=raw):
                self.data["roles"]["selected"]["file"] = raw
                with patch.object(subject, "_fingerprint") as fingerprint:
                    self.held(self.inspect())
                fingerprint.assert_not_called()
        outside = self.root.parent / "outside.synthetic"
        self.data["roles"]["selected"]["file"] = str(outside)
        with patch.object(subject, "_fingerprint") as fingerprint:
            self.held(self.inspect())
        fingerprint.assert_not_called()

    def test_sizes_and_lowercase_hash_contract(self):
        selected = self.data["roles"]["selected"]
        selected["bytes"] = selected["size"] = self.pdf.stat().st_size
        self.assertEqual(subject.OK, self.inspect()["receipt"]["status"])
        for bad in (True, -1, None, 999):
            selected["size"] = bad
            self.held(self.inspect())
        selected.pop("size")
        selected["sha256"] = selected["sha256"].upper()
        self.held(self.inspect())

    def test_reserved_audit_nonfinite_and_bad_json_hold(self):
        self.data[subject.AUDIT_KEY] = {}
        self.held(self.inspect())
        for raw in (b'{"number":NaN}', b'{"number":1e999}', b'[]', b'{invalid'):
            self.source.write_bytes(raw)
            self.held(subject.canonical_scope(self.pin(self.source), base=self.root, roots=[self.root]))

    def test_source_mutation_during_hashing_holds(self):
        def mutate(path):
            self.source.write_bytes(b"changed")
            return subject._original_fingerprint(path)
        with patch.object(subject, "_original_fingerprint", subject._fingerprint, create=True), \
                patch.object(subject, "_fingerprint", side_effect=mutate):
            self.held(self.inspect())

    def test_namespace_duplicates_and_escape_are_rejected(self):
        with self.assertRaises(ValueError):
            self.inspect(reference_roots={"official": self.root, "OFFICIAL": self.root})
        self.data["roles"]["selected"]["file"] = "official/../source.pdf"
        self.held(self.inspect(reference_roots={"official": self.root}))

    def test_publish_success_hold_and_exclusive_paths(self):
        output, receipt = self.root / "canonical.json", self.root / "receipt.json"
        result = self.inspect()
        published = subject.publish(result, output=output, receipt=receipt)
        self.assertEqual(self.pin(output), published)
        with self.assertRaises(ValueError):
            subject.publish(result, output=output, receipt=receipt)
        self.data["roles"]["selected"]["sha256"] = "0" * 64
        held_output = self.root / "held-scope.json"
        subject.publish(self.inspect(), output=held_output, receipt=self.root / "held-receipt.json")
        self.assertFalse(held_output.exists())

    def test_receipt_failure_does_not_publish_scope(self):
        result = self.inspect()
        with patch.object(subject, "write", side_effect=OSError("synthetic receipt failure")):
            with self.assertRaises(OSError):
                subject.publish(result, output=self.root / "output.json", receipt=self.root / "receipt.json")
        self.assertFalse((self.root / "output.json").exists())

    def test_mutation_during_receipt_write_does_not_publish_scope(self):
        result = self.inspect()
        real_write = subject.write
        def mutate(path, value):
            real_write(path, value)
            self.source.write_bytes(b"changed")
        with patch.object(subject, "write", side_effect=mutate):
            with self.assertRaises(ValueError):
                subject.publish(result, output=self.root / "output.json", receipt=self.root / "receipt.json")
        self.assertFalse((self.root / "output.json").exists())


if __name__ == "__main__":
    unittest.main()
