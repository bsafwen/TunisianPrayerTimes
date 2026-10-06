"""Focused tests for bounded Luna review workers; all reviewers are local fakes."""
from __future__ import annotations

import hashlib
import json
import tempfile
import threading
import time
import unittest
from pathlib import Path

from . import review_workers as workers
from .common import pin, read


CATALOG_FINGERPRINT = "a" * 64


def _sha(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _config(root: Path) -> dict:
    return {
        "workspace": str(root / "workspace"),
        "taskRoot": str(root),
        "model": {
            "provider": "codex_cli",
            "name": "gpt-6-luna",
            "reasoning": "max",
            "fallbacks": [],
        },
    }


def _item(unit_id: str, case_ids: list[str], *, priority: int = 0, eligible: bool = True) -> dict:
    return {
        "id": "review:" + unit_id,
        "caseIds": case_ids,
        "nameAr": unit_id,
        "parentAr": "test-parent",
        "role": "active_location",
        "priority": priority,
        "categories": ["identity"],
        "question": "Which supplied evidence resolves this one test question?",
        "unresolvedConflicts": [],
        "evidence": [],
        "sourceFingerprint": _sha("source:" + unit_id),
        "currentCatalogFingerprint": CATALOG_FINGERPRINT,
        "modelEligible": eligible,
        "status": "UNRESOLVED",
        "disposition": "REVIEW_REQUIRED",
    }


def _queue(*items: dict) -> dict:
    return {
        "schemaVersion": workers.QUEUE_SCHEMA,
        "governorate": "بن عروس",
        "currentCatalogFingerprint": CATALOG_FINGERPRINT,
        "currentCatalogPin": CATALOG_FINGERPRINT,
        "caseDispositions": [
            {"caseId": case_id, "unitId": item["id"], "disposition": "UNRESOLVED",
             "sourceFingerprint": item["sourceFingerprint"]}
            for item in items for case_id in item["caseIds"]
        ],
        "items": list(items),
    }


def _catalog(_config: dict) -> dict:
    return {"catalogSha256": CATALOG_FINGERPRINT, "locations": {}}


class ReviewWorkersTests(unittest.TestCase):
    def test_parallel_atomic_group_writes_advisory_result_and_reuses_cache(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            config = _config(root)
            grouped = _item("linked", ["case-A", "case-B"], priority=30)
            other = _item("other", ["case-C"], priority=10)
            queue = _queue(grouped, other)
            lock = threading.Lock()
            active = 0
            max_active = 0
            calls = []

            def reviewer(_config, case, evidence, input_pins):
                nonlocal active, max_active
                with lock:
                    active += 1
                    max_active = max(max_active, active)
                    calls.append(tuple(case["caseIds"]))
                time.sleep(0.06)
                with lock:
                    active -= 1
                return {"status": "advisory", "advice": {"findings": []}}

            report = workers.run_review_queue(
                config, queue, root / "results", max_workers=2,
                reviewer=reviewer, catalog_fingerprint=_catalog,
            )
            self.assertEqual("COMPLETED", report["status"])
            self.assertEqual(2, len(calls))
            self.assertEqual(2, max_active)
            self.assertIn(("case-A", "case-B"), calls)

            case_a = read(workers._case_result_path(root / "results", "case-A"))
            case_b = read(workers._case_result_path(root / "results", "case-B"))
            self.assertEqual(case_a["reviewUnitId"], case_b["reviewUnitId"])
            self.assertEqual(["case-A", "case-B"], case_a["caseIds"])
            self.assertEqual("ADVISORY_ONLY", case_a["acceptance"])
            self.assertEqual(0, case_a["verifiedChecksAdded"])
            claim_a = read(root / "results" / "claims" / (workers._safe_key("case-A") + ".json"))
            claim_b = read(root / "results" / "claims" / (workers._safe_key("case-B") + ".json"))
            self.assertEqual(["case-A", "case-B"], claim_a["claimedCaseIds"])
            self.assertEqual(["case-A", "case-B"], claim_b["claimedCaseIds"])

            cached = workers.run_review_queue(
                config, queue, root / "results", max_workers=2,
                reviewer=lambda *_: self.fail("unchanged advisory must be served from cache"),
                catalog_fingerprint=_catalog,
            )
            self.assertEqual(2, cached["counts"]["cached"])
            self.assertEqual(2, len(calls))

    def test_eight_unit_cap_and_priority_gate_and_luna_only_config(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            config = _config(root)
            items = [_item(f"case-{index}", [f"id-{index}"]) for index in range(9)]
            items.append(_item("low-priority", ["id-low"], priority=40))
            calls = []
            report = workers.run_review_queue(
                config, _queue(*items), root / "results", max_workers=3,
                reviewer=lambda _c, case, _e, _p: calls.append(case["id"]) or {"status": "advisory"},
                catalog_fingerprint=_catalog,
            )
            self.assertEqual(8, len(calls))
            self.assertEqual(8, report["counts"]["selectedItems"])
            self.assertEqual(2, report["counts"]["skippedIneligible"])
            self.assertEqual(8, len(report["items"]))

            wrong_model = _config(root)
            wrong_model["model"]["provider"] = "deepseek"
            with self.assertRaisesRegex(ValueError, "GPT-6 Luna max"):
                workers.run_review_queue(wrong_model, _queue(), root / "wrong", catalog_fingerprint=_catalog)

    def test_uncertain_result_is_cached_and_never_retried(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            config = _config(root)
            calls = 0

            def uncertain(*_args):
                nonlocal calls
                calls += 1
                return {
                    "status": "uncertain",
                    "attempts": [{"provider": "gpt-6-luna", "status": "uncertain",
                                  "reason": "Previous request may have reached provider; no automatic retry."}],
                }

            queue = _queue(_item("uncertain", ["case-uncertain"]))
            first = workers.run_review_queue(config, queue, root / "results", reviewer=uncertain,
                                             catalog_fingerprint=_catalog)
            second = workers.run_review_queue(
                config, queue, root / "results",
                reviewer=lambda *_: self.fail("uncertain calls must not be repeated"),
                catalog_fingerprint=_catalog,
            )
            self.assertEqual(1, calls)
            self.assertEqual("COMPLETED_WITH_ISSUES", first["status"])
            self.assertEqual(1, second["counts"]["cached"])
            saved = read(workers._case_result_path(root / "results", "case-uncertain"))
            self.assertEqual("uncertain", saved["status"])
            self.assertEqual("ADVISORY_ONLY", saved["acceptance"])

    def test_provider_pause_stops_new_dispatch_after_first_failure(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            config = _config(root)
            Path(config["workspace"]).mkdir(parents=True)
            calls = []

            def unavailable(_config, case, _evidence, _pins):
                calls.append(case["id"])
                from .common import write
                write(Path(config["workspace"]) / "control.json", {
                    "paused": True,
                    "reason": "Codex model backend unavailable; inspect the local model log.",
                }, replace=True)
                return {"status": "failed", "attempts": [{
                    "provider": "gpt-6-luna", "status": "failed",
                    "reason": "ModelBackendUnavailable: Codex CLI is not available",
                }]}

            queue = _queue(*[_item(f"backend-{i}", [f"case-{i}"]) for i in range(4)])
            report = workers.run_review_queue(config, queue, root / "results", max_workers=1,
                                              reviewer=unavailable, catalog_fingerprint=_catalog)
            self.assertEqual(1, len(calls))
            self.assertEqual("BACKEND_PAUSED", report["status"])
            self.assertEqual(3, sum(row["status"] == "BACKEND_PAUSED" for row in report["items"]))
            self.assertEqual("failed", report["items"][0]["status"])

    def test_catalog_change_after_review_marks_case_result_stale(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            config = _config(root)
            case_id = "catalog-case"
            item = _item("catalog", [case_id])
            snapshots = 0

            def changing_catalog(_config):
                nonlocal snapshots
                snapshots += 1
                fingerprint = CATALOG_FINGERPRINT if snapshots < 3 else "b" * 64
                return {"catalogSha256": fingerprint, "locations": {}}

            calls = []
            report = workers.run_review_queue(
                config, _queue(item), root / "results",
                reviewer=lambda *_: calls.append(True) or {"status": "advisory"},
                catalog_fingerprint=changing_catalog,
            )
            self.assertEqual(1, len(calls))
            self.assertEqual("STALE_CATALOG", report["status"])
            saved = read(workers._case_result_path(root / "results", case_id))
            self.assertEqual("STALE_CATALOG", saved["status"])
            self.assertTrue(saved["staleCatalog"])
            self.assertEqual(0, saved["verifiedChecksAdded"])

    def test_queue_source_pins_are_checked_before_and_after_dispatch(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            config = _config(root)
            source = root / "ledger.json"
            source.write_text('{"revision": 1}', encoding="utf-8")
            source_pin = pin(source)
            queue = _queue(_item("source", ["case-source"]))
            queue["inputPins"] = {"ledger": source_pin}
            calls = []

            def change_source(_config, _case, _evidence, _pins):
                calls.append(True)
                source.write_text('{"revision": 2}', encoding="utf-8")
                return {"status": "advisory"}

            report = workers.run_review_queue(config, queue, root / "after", reviewer=change_source,
                                              catalog_fingerprint=_catalog)
            self.assertEqual(1, len(calls))
            self.assertEqual("STALE_SOURCE_INPUTS", report["status"])
            saved = read(workers._case_result_path(root / "after", "case-source"))
            self.assertEqual("STALE_SOURCE_INPUTS", saved["status"])
            self.assertTrue(saved["staleSourceInputs"])

            calls.clear()
            before_dispatch = workers.run_review_queue(
                config, queue, root / "before",
                reviewer=lambda *_: calls.append(True) or {"status": "advisory"},
                catalog_fingerprint=_catalog,
            )
            self.assertEqual([], calls)
            self.assertEqual("STALE_SOURCE_INPUTS", before_dispatch["status"])
            self.assertEqual(0, before_dispatch["counts"]["dispatchedItems"])

    def test_case_source_fingerprint_must_match_queue_disposition_before_dispatch(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            config = _config(root)
            queue = _queue(_item("fingerprint", ["case-fingerprint"]))
            queue["caseDispositions"][0]["sourceFingerprint"] = "0" * 64
            calls = []
            report = workers.run_review_queue(
                config, queue, root / "results",
                reviewer=lambda *_: calls.append(True) or {"status": "advisory"},
                catalog_fingerprint=_catalog,
            )
            self.assertEqual([], calls)
            self.assertEqual("STALE_SOURCE_FINGERPRINT", report["status"])
            self.assertEqual("STALE_SOURCE_FINGERPRINT", report["items"][0]["status"])
            self.assertEqual(0, report["counts"]["dispatchedItems"])

    def test_large_polygon_labels_are_projected_and_packet_stays_under_cap(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            packet_path = root / "prior-packet.json"
            packet = {
                "case": {"id": "case-label"},
                "evidence": {"polygonPacket": {
                    "records": [{
                        "id": "case-label",
                        "sourceOsmIds": ["case-label"],
                        "name": "Example",
                        "allTextLabels": [
                            {"text": f"label-{index}", "bounds": [index, 0, index + 1, 1],
                             "point": [index, 0.5]}
                            for index in range(240)
                        ],
                        "inputPins": [{"file": "source.pdf", "sha256": "c" * 64}],
                        "rings": [{"drawingIndex": 0, "valid": True, "containsPoint": False}],
                    }],
                }},
            }
            packet_path.write_text(json.dumps(packet, ensure_ascii=False), encoding="utf-8")
            packet_pin = pin(packet_path)
            item = _item("labels", ["case-label"])
            item["categories"] = ["boundary"]
            item["question"] = "Is the supplied polygon boundary evidence complete?"
            item["priorPacketPins"] = [packet_pin]

            _case, evidence, input_pins = workers._packet(item, "بن عروس")
            self.assertLessEqual(len(json.dumps(evidence, ensure_ascii=False, allow_nan=False)),
                                 workers.MAX_PACKET_CHARS)
            self.assertIn(packet_pin["sha256"], json.dumps(evidence, ensure_ascii=False))
            compact_packet = evidence["selectedPriorPackets"][0]["packet"]["evidence"]["polygonPacket"]
            record = compact_packet["records"][0]
            self.assertEqual(80, record["omittedTextLabelCount"])
            self.assertTrue(all(isinstance(label, str) for label in record["allTextLabels"]))
            self.assertNotIn("bounds", json.dumps(record, ensure_ascii=False))
            self.assertIn("case-label", evidence["atomicQueueItem"]["caseIds"])
            self.assertEqual(packet_pin["sha256"], input_pins[0]["sha256"])


if __name__ == "__main__":
    unittest.main()
