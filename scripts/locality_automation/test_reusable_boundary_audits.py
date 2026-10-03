"""Meaningful failure cases for retained history and exact ring boundaries."""
import json
from pathlib import Path
import tempfile
import unittest
from scripts.locality_automation.run_sealed_boundary_queue import pin
from scripts.locality_automation.extend_accepted_body_registry import build
from scripts.locality_automation.verify_boundary_publication import ring_edges


class ReusableAuditContracts(unittest.TestCase):
    def test_changed_vertex_is_rejected_but_ring_start_is_preserved(self):
        ring = [[0, 0], [1, 0], [1, 1], [0, 0]]
        rotated = [[1, 0], [1, 1], [0, 0], [1, 0]]
        self.assertEqual(ring_edges(ring, rotated)["direction"], "same")
        self.assertEqual(ring_edges(ring, list(reversed(ring)))["direction"], "reverse")
        changed = [[0, 0], [1, 0], [1, 1.000001], [0, 0]]
        with self.assertRaisesRegex(ValueError, "ring edges differ"):
            ring_edges(ring, changed)

    def test_historical_null_scope_never_becomes_new_full_credit(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            def save(name, value):
                path = root / name
                path.write_text(json.dumps(value), encoding="utf-8")
                return pin(path)
            base = save("base.json", {"bindings": []})
            publication = save("report.json", {"summary": {"explicitFullSourceBoundaryLocalityCodes": ["123456"]},
                "validations": [{"id": "old", "fullSourceBoundaryLocalityCodes": None}]})
            metadata = save("metadata.json", {"features": []})
            binary = root / "binary.bin"
            binary.write_bytes(b"")
            with self.assertRaisesRegex(ValueError, "No current-body full receipt"):
                build(base, publication, metadata, pin(binary))

    def test_previously_accepted_code_cannot_be_dropped(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            def save(name, value):
                path = root / name
                path.write_text(json.dumps(value), encoding="utf-8")
                return pin(path)
            base = save("base.json", {"bindings": [{"officialCode": "123456", "id": "original-id"}]})
            publication = save("report.json", {"summary": {"explicitFullSourceBoundaryLocalityCodes": []}, "validations": []})
            metadata = save("metadata.json", {"features": []})
            binary = root / "binary.bin"
            binary.write_bytes(b"")
            with self.assertRaisesRegex(ValueError, "Historical accepted codes were dropped"):
                build(base, publication, metadata, pin(binary))


if __name__ == "__main__":
    unittest.main()
