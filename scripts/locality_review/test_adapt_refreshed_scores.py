"""Focused no-network checks for refreshed score report adaptation."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import adapt_refreshed_scores as adapter
from verification_scores import load_scores


def write_json(path: Path, value) -> dict[str, str]:
    path.parent.mkdir(parents=True, exist_ok=True)
    data = (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    path.write_bytes(data)
    return {"file": str(path.resolve()), "sha256": hashlib.sha256(data).hexdigest()}


def pin_bytes(path: Path, data: bytes) -> dict[str, str]:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return {"file": str(path.resolve()), "sha256": hashlib.sha256(data).hexdigest()}


class AdaptRefreshedScoresTest(unittest.TestCase):
    def build_fixture(self, root: Path, *, promote_target: bool = False):
        target_id = "osm:node:target"
        accepted_id = "osm:way:accepted"
        old_input_pins = {}
        for key in adapter.SOURCE_KEYS:
            old_input_pins[key] = pin_bytes(root / "old-source" / key, ("old-" + key).encode())
        old_input_pins["policy"] = write_json(root / "old-policy.json", {"version": 1})
        old_input_pins["claims"] = write_json(root / "old-claims.json", {"accepted": []})
        old_hashes = {adapter.HASH_KEYS[key]: old_input_pins[key]["sha256"] for key in adapter.INPUT_KEYS}
        old_rows = [
            {"id": accepted_id, "scorePercent": 10, "verifiedChecks": ["existence"]},
            {"id": target_id, "scorePercent": None, "verifiedChecks": []},
        ]
        old_buckets = {key: 0 for key in adapter.BUCKETS}
        old_buckets["10"] = 1
        old_scorecards = {
            "metadataSha256": old_hashes["metadataSha256"],
            "binarySha256": old_hashes["binarySha256"],
            "policySha256": old_hashes["policySha256"],
            "claimsSha256": old_hashes["claimsSha256"],
            "rows": old_rows,
        }
        old_summary = {
            "totalLocations": 2,
            "scoredCount": 1,
            "unscoredCount": 1,
            "verifiedCheckCount": 1,
            "buckets": old_buckets,
            "inputHashes": old_hashes,
        }
        old_summary_pin = write_json(root / "old-summary.json", old_summary)
        old_scorecards_pin = write_json(root / "old-scorecards.json", old_scorecards)
        old_inputs_pin = write_json(root / "old-input-pins.json", old_input_pins)
        accepted_metric = {
            "totalSelectableLocations": 2,
            "scoredLocations": 1,
            "awaitingScoring": 1,
            "verifiedChecks": 1,
            "verificationScoreBuckets": old_buckets,
            "summary": old_summary_pin,
            "scorecards": old_scorecards_pin,
            "currentInputPins": old_inputs_pin,
        }
        accepted_metric_path = root / "accepted-metric.json"
        write_json(accepted_metric_path, accepted_metric)

        new_source_pins = {}
        for key in adapter.SOURCE_KEYS:
            data = ("new-" + key).encode()
            new_source_pins[key] = pin_bytes(root / "new-source" / key, data)
        locations = [
            {"id": accepted_id, "nameAr": "اسم مقبول", "lat": 36.8, "lng": 10.1},
            {"id": target_id, "nameAr": "هدف غير مسجل", "lat": 36.9, "lng": 10.2},
        ]
        catalog = {"sourcePins": new_source_pins, "locations": locations}
        catalog_pin = write_json(root / "catalog.json", catalog)
        target_checks = ["arabic_name"] if promote_target else []
        target_score = 10 if promote_target else None
        refreshed_rows = [
            {
                "id": accepted_id, "displayNameAr": "اسم مقبول", "lat": 36.8, "lng": 10.1,
                "verifiedChecks": ["existence"], "scorePercent": 10, "status": "scored",
            },
            {
                "id": target_id, "displayNameAr": "هدف غير مسجل", "lat": 36.9, "lng": 10.2,
                "verifiedChecks": target_checks, "scorePercent": target_score,
                "status": "scored" if promote_target else "unscored",
            },
        ]
        refreshed_buckets = {key: 0 for key in adapter.BUCKETS}
        refreshed_buckets["10"] = 1 + int(promote_target)
        refreshed_scorecards = {
            "metadataSha256": new_source_pins["metadata"]["sha256"],
            "binarySha256": new_source_pins["binary"]["sha256"],
            "rows": refreshed_rows,
        }
        scorecards_pin = write_json(root / "refreshed-scorecards.json", refreshed_scorecards)
        metric = {
            "status": "CHANGED_EVIDENCE_INVALIDATED",
            "scorecards": scorecards_pin,
            "totalSelectableLocations": 2,
            "scoredLocations": 1 + int(promote_target),
            "awaitingScoring": 1 - int(promote_target),
            "verifiedChecks": 1 + int(promote_target),
            "verificationScoreBuckets": refreshed_buckets,
        }
        metric_pin = write_json(root / "refreshed-metric.json", metric)
        refresh_report = {
            "status": "AUTOMATION_INSTALLATION_SNAPSHOT",
            "verificationMetric": {
                "report": metric_pin,
                "total": 2,
                "scored": 1 + int(promote_target),
                "unscored": 1 - int(promote_target),
                "verifiedChecks": 1 + int(promote_target),
                "verificationScoreBuckets": refreshed_buckets,
            },
        }
        refresh_report_pin = write_json(root / "refresh-report.json", refresh_report)
        snapshot_pin = write_json(root / "snapshot.json", {
            "catalog": catalog_pin,
            "metric": metric_pin,
            "report": refresh_report_pin,
        })
        return snapshot_pin["file"], str(accepted_metric_path), catalog, target_id

    def test_loads_with_manual_review_loader_and_keeps_target_unscored(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot, accepted, catalog, target = self.build_fixture(root)
            report = adapter.build_compatible_report(
                snapshot, accepted, root / "output", require_unscored_id=target,
            )
            loaded = load_scores(str(report), catalog)
            self.assertTrue(loaded["available"])
            self.assertEqual(loaded["total"], 2)
            self.assertEqual(loaded["scoredCount"], 1)
            self.assertEqual(loaded["scores"], {"osm:way:accepted": 10})

    def test_rejects_refreshed_check_missing_from_accepted_claims(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot, accepted, _, target = self.build_fixture(root, promote_target=True)
            with self.assertRaisesRegex(ValueError, "promotes a check absent from accepted evidence"):
                adapter.build_compatible_report(
                    snapshot, accepted, root / "output", require_unscored_id=target,
                )


if __name__ == "__main__":
    unittest.main()
