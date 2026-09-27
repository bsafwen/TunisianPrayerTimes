"""Focused replay checks for retired review IDs promoted to sector IDs."""

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).parent))
SPEC = importlib.util.spec_from_file_location("locality_review_serve", Path(__file__).with_name("serve.py"))
serve = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(serve)


class LegacyIdProjectionTest(unittest.TestCase):
    def test_replay_projects_history_without_copying_or_double_counting(self):
        old_id = "delegation:489"
        sector_id = "osm:relation:7142941"
        old_fingerprint = "a" * 64
        current_fingerprint = "b" * 64
        catalog = {
            "catalogFingerprint": "c" * 64,
            "locations": [{"id": sector_id, "fingerprint": current_fingerprint}],
            "legacyIdAliases": {old_id: sector_id},
        }

        def record(request_id, location_id, fingerprint, verdict):
            return {
                "requestId": request_id, "id": location_id,
                "fingerprint": fingerprint, "verdict": verdict,
                "issues": [], "note": "", "evidenceUrl": "",
                "knowledge": "unspecified", "savedAtUtc": "2026-09-21T00:00:00Z",
                "status": serve.REVIEW_STATUS,
                "catalogFingerprint": "d" * 64,
                "source": serve.REVIEW_SOURCE,
            }

        legacy = record("00000000-0000-4000-8000-000000000001", old_id,
                        old_fingerprint, "looks_correct")
        current = record("00000000-0000-4000-8000-000000000002", sector_id,
                         current_fingerprint, "problem")
        current["issues"] = ["type"]
        withdrawal = record("00000000-0000-4000-8000-000000000003", sector_id,
                            current_fingerprint, "withdrawn")

        with tempfile.TemporaryDirectory() as directory:
            log_path = Path(directory) / "responses.jsonl"
            log_path.write_text(json.dumps(legacy) + "\n", encoding="utf-8")
            store = serve.Store(directory, catalog)
            self.assertEqual(store.state_payload()["latest"], {sector_id: legacy})
            self.assertEqual(store.counts["stale"], 1)
            self.assertEqual(store.counts["reviewed"], 0)
            self.assertEqual(store.export_payload()["events"], [legacy])

            store.append(current)
            self.assertEqual(store.state_payload()["latest"], {sector_id: current})
            self.assertEqual(store.counts["reviewed"], 1)
            self.assertEqual(store.counts["stale"], 0)
            self.assertEqual(store.export_payload()["events"], [legacy, current])

            store.append(withdrawal)
            reloaded = serve.Store(directory, catalog)
            self.assertEqual(reloaded.state_payload()["latest"], {})
            self.assertEqual(reloaded.counts["reviewed"], 0)
            self.assertEqual(reloaded.counts["stale"], 0)
            self.assertEqual(reloaded.export_payload()["events"],
                             [legacy, current, withdrawal])
            self.assertEqual(len(reloaded.seen), 3)


if __name__ == "__main__":
    unittest.main()
