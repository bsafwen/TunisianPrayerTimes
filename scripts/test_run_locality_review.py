import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from locality_automation.common import pin
from locality_automation.review_publishing import render_review_queue_html
from run_locality_review import (
    _attach_cached_official_sources,
    _attach_display_collisions,
    prepare_all,
)


def _write_packet(path: Path, record: dict) -> dict:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({
        "evidence": {"polygonPacket": {"records": [record]}}
    }), encoding="utf-8")
    return {"kind": "prior_packet", "caseId": record["id"], **pin(path)}


class CachedOfficialEvidenceTests(unittest.TestCase):
    def test_exact_display_collision_is_visible_and_human_prioritized(self):
        queue = {
            "governorate": "بن عروس", "counts": {},
            "items": [
                {"id": "review:other", "caseIds": ["osm:way:other"], "priority": 20,
                 "priorityReasons": [], "evidence": []},
                {"id": "review:duplicate", "caseIds": ["osm:node:1"], "priority": 40,
                 "priorityReasons": [], "evidence": [], "modelEligible": False},
            ],
        }
        group = {
            "governorateAr": "بن عروس", "nameAr": "المروج 5", "parentAr": "معتمدية المروج",
            "members": [{"id": "osm:node:1"}, {"id": "osm:relation:2"}],
            "checks": [{"pointId": "osm:node:1", "polygonId": "osm:relation:2",
                        "relation": "outside", "distanceDegrees": 0.0003}],
        }
        attached = _attach_display_collisions(
            queue, {"groups": [group]}, {"file": "C:/task/collisions.json", "sha256": "a" * 64})
        self.assertEqual(attached, 1)
        self.assertEqual(queue["counts"]["displayCollisionGroups"], 1)
        self.assertEqual(queue["items"][0]["id"], "review:duplicate")
        item = queue["items"][0]
        self.assertEqual(item["priority"], 20)
        self.assertFalse(item["modelEligible"])
        self.assertEqual(item["displayCollision"], group)
        self.assertEqual(item["evidence"][0]["kind"], "display_collision_audit")

    def test_attaches_only_contained_hash_valid_assets_and_fingerprints_their_state(self):
        with tempfile.TemporaryDirectory() as temporary:
            workspace = Path(temporary) / "workspace"
            workspace.mkdir()
            outside_pdf = Path(temporary) / "outside.pdf"
            outside_pdf.write_bytes(b"outside source")
            pdf = workspace / "official.pdf"
            thumbnail = workspace / "thumbnail.png"
            pdf.write_bytes(b"official source")
            thumbnail.write_bytes(b"map preview")

            first = "osm:relation:7174567"
            second = "osm:relation:7174568"
            packet_pins = [
                _write_packet(workspace / "packet-one.json", {
                    "id": first,
                    "sourcePDF": pin(pdf),
                    "thumbnail": pin(thumbnail),
                }),
                _write_packet(workspace / "packet-two.json", {
                    "id": second,
                    "sourcePDF": pin(outside_pdf),
                    "thumbnail": {"file": str(workspace / "missing.png"), "sha256": "0" * 64},
                }),
            ]
            baseline = {
                "schemaVersion": "locality-review-queue/1",
                "governorate": "بن عروس",
                "counts": {},
                "items": [{
                    "id": "review:boundary-pair",
                    "caseIds": [first, second],
                    "priorPacketPins": packet_pins,
                    "sourceFingerprint": "a" * 64,
                    "evidence": [],
                }],
                "caseDispositions": [
                    {"unitId": "review:boundary-pair", "caseId": first, "sourceFingerprint": "a" * 64},
                    {"unitId": "review:boundary-pair", "caseId": second, "sourceFingerprint": "a" * 64},
                ],
            }

            queue = copy.deepcopy(baseline)
            attached = _attach_cached_official_sources(queue, workspace)
            item = queue["items"][0]
            links = [row for row in item["evidence"] if row.get("kind", "").startswith("cached_official_source_")]

            self.assertEqual(attached, 2)
            self.assertEqual({row["caseId"] for row in links}, {first})
            self.assertEqual({row["kind"] for row in links}, {
                "cached_official_source_pdf", "cached_official_source_thumbnail",
            })
            self.assertNotIn(outside_pdf.as_uri(), json.dumps(item["evidence"], ensure_ascii=False))
            self.assertNotIn("missing.png", json.dumps(item["evidence"], ensure_ascii=False))
            self.assertNotEqual(item["sourceFingerprint"], "a" * 64)
            self.assertEqual({row["sourceFingerprint"] for row in queue["caseDispositions"]}, {item["sourceFingerprint"]})

            html = render_review_queue_html(queue)
            self.assertIn(pdf.as_uri(), html)
            self.assertIn(thumbnail.as_uri(), html)
            self.assertIn("Official source PDF", html)
            self.assertIn("Official source map thumbnail", html)

            pdf.write_bytes(b"changed source")
            changed = copy.deepcopy(baseline)
            _attach_cached_official_sources(changed, workspace)
            changed_item = changed["items"][0]
            self.assertNotEqual(changed_item["sourceFingerprint"], item["sourceFingerprint"])
            self.assertEqual([row["kind"] for row in changed_item["evidence"]], [
                "cached_official_source_thumbnail",
            ])


class PrepareAllTests(unittest.TestCase):
    def test_prepare_all_follows_accepted_order_and_checkpoints_each_governorate(self):
        with tempfile.TemporaryDirectory() as temporary:
            workspace = Path(temporary)
            review_root = workspace / "review"
            index_path = workspace / "queue-index.json"
            index_path.write_text(json.dumps({
                "orderedGovernorates": [
                    {"governorateAr": "بن عروس"},
                    {"governorateAr": "تونس"},
                ],
            }, ensure_ascii=False), encoding="utf-8")
            config = {"workspace": str(workspace)}
            calls = []

            def fake_prepare(_config, governorate):
                calls.append(governorate)
                if governorate == "تونس":
                    checkpoint = json.loads((review_root / "prepare-all-summary.json").read_text(encoding="utf-8"))
                    self.assertEqual(checkpoint["status"], "RUNNING")
                    self.assertEqual(checkpoint["completedGovernorates"], 1)
                    self.assertEqual(checkpoint["results"][0]["governorate"], "بن عروس")
                queue = {
                    "governorate": governorate,
                    "counts": {
                        "caseCount": 13,
                        "selectableCatalogCaseCount": 11,
                        "unmappedMissingCatalogCaseCount": 2,
                        "reviewUnitCount": 7,
                        "modelEligible": 2,
                        "displayCollisionGroups": 1,
                        "displayCollisionReviewUnits": 3,
                        "historicalGroupedMemberAuditCount": 4,
                    },
                }
                publication = {"files": [{
                    "governorate": governorate,
                    "json": str(review_root / (governorate + ".json")),
                    "html": str(review_root / (governorate + ".html")),
                }]}
                return queue, publication, review_root

            with patch("run_locality_review.snapshot", return_value={"queueIndex": pin(index_path)}), \
                    patch("run_locality_review.prepare", side_effect=fake_prepare), \
                    patch("run_locality_review.run_review_queue", side_effect=AssertionError("model worker called")):
                result = prepare_all(config)

            self.assertEqual(calls, ["بن عروس", "تونس"])
            self.assertEqual(result["status"], "COMPLETE")
            self.assertEqual(result["completedGovernorates"], 2)
            self.assertEqual(result["results"][0], {
                "governorate": "بن عروس",
                "activeCaseCount": 13,
                "selectableCatalogCaseCount": 11,
                "unmappedMissingCatalogCaseCount": 2,
                "activeUnitCount": 7,
                "modelEligibleUnitCount": 2,
                "currentCollisionGroupCount": 1,
                "currentCollisionReviewUnitCount": 3,
                "historicalAuditCount": 4,
                "publicationPaths": {
                    "json": str(review_root / "بن عروس.json"),
                    "html": str(review_root / "بن عروس.html"),
                },
            })
            saved = json.loads((review_root / "prepare-all-summary.json").read_text(encoding="utf-8"))
            self.assertEqual(saved["status"], "COMPLETE")
            self.assertIsNone(saved["nextGovernorate"])


if __name__ == "__main__":
    unittest.main()
