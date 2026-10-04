import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from scripts.locality_automation.collect_topology_source_context_v4 import native_adapter, original_decision, select_sources


class SourceContextTests(unittest.TestCase):
    def test_missing_and_ambiguous_sources_are_not_ranked_or_discarded(self):
        a, b = {"appId": "A", "officialCode": "1"}, {"appId": "A", "officialCode": "2"}
        values = select_sources({"A", "B"}, [("p1", {"rows": [a]}), ("p2", {"rows": [b]})])
        self.assertEqual(values["A"], [("p1", a), ("p2", b)])
        self.assertEqual(values["B"], [])

    def test_decision_schema_preserves_literal_hold_and_rejects_ambiguity(self):
        self.assertEqual(original_decision({"verdict": "HOLD"}), ("verdict", "HOLD"))
        for row in ({}, {"decision": "GO_M_SOURCE", "verdict": "HOLD"}, {"decision": True}):
            with self.subTest(row=row), self.assertRaises(ValueError):
                original_decision(row)

    def test_native_input_cannot_override_identity_or_promote_old_hold(self):
        base = {"pageWkb": {}, "registeredWkb": {}}
        with self.assertRaises(ValueError):
            native_adapter({"decision": "GO_M_SOURCE"}, {**base, "appId": "changed"})
        with self.assertRaises(ValueError):
            native_adapter({"decision": "HOLD"}, base)

    def test_actual_original_inventory_and_face_hash_bindings_are_mandatory(self):
        with tempfile.TemporaryDirectory(prefix="locality-source-adapter-") as directory:
            root = Path(directory)
            inventory = root / "inventory.json"
            inventory.write_text(json.dumps({"pages": [{"labeledAreaLeads": [{"redText": True}]}]}), encoding="utf-8")
            pdf = root / "original.pdf"
            pdf.write_bytes(b"only a pinned adapter fixture, never accepted geometry")
            pin = lambda p: {"file": str(p), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
            original = {"decision": "GO_M_SOURCE", "ownTitlePdfCountInCorrectParent": 1,
                "sourceInventorySha256": pin(inventory)["sha256"], "sourcePdf": pin(pdf),
                "nativeOwnLabel": {"index": 0, "redText": True},
                "acceptedWkb": {"page": {"sha256": "page"}, "epsg32632": {"sha256": "ground"}},
                "diagnosticPageFaceWkbSha256": "page", "diagnosticRegisteredFaceWkbSha256": "ground"}
            native = {"pageWkb": {"sha256": "page"}, "registeredWkb": {"sha256": "ground"}, "sourceInventory": pin(inventory)}
            self.assertEqual(native_adapter(original, native)["sourcePdf"], pin(pdf))
            for mutated in (dict(original, diagnosticRegisteredFaceWkbSha256="changed"),
                            dict(original, sourceInventorySha256="changed"),
                            dict(original, nativeOwnLabel={"index": False, "redText": True})):
                with self.subTest(mutated=mutated), self.assertRaises(ValueError):
                    native_adapter(mutated, native)
            changed = copy.deepcopy(native)
            changed["registeredWkb"]["sha256"] = "changed"
            with self.assertRaises(ValueError):
                native_adapter(original, changed)


if __name__ == "__main__":
    unittest.main()
