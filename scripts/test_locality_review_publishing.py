import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock
from urllib.parse import quote_plus

from locality_automation import review_publishing


def sample_queue():
    return {
        "schemaVersion": "locality-review-queue/1",
        "governorate": "بن عروس",
        "generatedAtUtc": "2026-09-24T10:00:00Z",
        "counts": {"accepted": 3, "rejected": 2, "unresolved": 2, "queued": 2},
        "sourceFiles": [],
        "evidencePackets": [
            {"name": "Saved source map", "href": "file:///C:/work/source-packets/map/index.html"}
        ],
        "items": [
            {
                "id": "review:001",
                "caseIds": ["osm:node:1"],
                "nameAr": "<script>alert(1)</script>",
                "parentAr": "معتمدية تونس",
                "role": "active_location",
                "priority": 0,
                "priorityReasons": ["user problem report"],
                "categories": ["gps"],
                "question": "Check the saved pin",
                "unresolvedConflicts": [{"caseId": "osm:node:1", "text": "GPS location differs"}],
                "issueCount": 1,
                "issuesTruncated": False,
                "manualReports": [{"verdict": "problem", "assertion": "The map pin is misplaced"}],
                "evidence": [
                    {
                        "name": "Saved evidence packet",
                        "file": "C:\\work\\source-packets\\map\\packet.json",
                        "sha256": "a" * 64,
                    },
                    {"name": "Unsafe scheme", "href": "javascript:alert(2)"},
                ],
                "priorAdvice": [{"text": "Model advice only", "verified": False}],
                "caseRecords": [{"id": "osm:node:1", "nameAr": "<script>alert(1)</script>"}],
                "externalDependencies": [],
                "sourceFingerprint": "b" * 64,
                "status": "UNRESOLVED",
                "disposition": "REVIEW_REQUIRED",
            },
            {
                "id": "review:002",
                "caseIds": ["osm:way:2"],
                "nameAr": "الرياض",
                "parentAr": "معتمدية فوشانة",
                "role": "active_location",
                "priority": 20,
                "priorityReasons": ["automatic identity check"],
                "categories": ["identity"],
                "question": "Confirm the display name",
                "unresolvedConflicts": [],
                "issueCount": 0,
                "manualReports": [],
                "evidence": [],
                "priorAdvice": [],
                "caseRecords": [{"id": "osm:way:2", "nameAr": "الرياض"}],
                "externalDependencies": [],
                "sourceFingerprint": "c" * 64,
                "status": "UNRESOLVED",
                "disposition": "REVIEW_REQUIRED",
            },
        ],
        "dispositions": [],
        "qualification": {
            "automaticReview": "Advisory only",
            "geographicReliability": "not verified",
        },
    }


class ReviewQueuePublishingTests(unittest.TestCase):
    def test_duplicate_display_label_shows_outside_point_without_merging(self):
        source = sample_queue()
        source["items"][0]["displayCollision"] = {
            "members": [{"id": "osm:node:1"}, {"id": "osm:way:2"}],
            "checks": [{"pointId": "osm:node:<bad>", "polygonId": "osm:way:2",
                        "relation": "outside", "distanceDegrees": 0.0003}],
        }
        rendered = review_publishing.render_review_queue_html(source)
        self.assertIn("Duplicate picker label", rendered)
        self.assertIn("osm:node:&lt;bad&gt;", rendered)
        self.assertIn("outside namesake polygon", rendered)
        self.assertNotIn("osm:node:<bad>", rendered)

    def test_normalized_user_assertion_and_stale_state_are_visible(self):
        source = sample_queue()
        source["items"][0]["manualReports"] = [{
            "verdict": "problem", "userAssertion": "Mourouj 5 < Mourouj 6", "stale": True,
        }]
        rendered = review_publishing.render_review_queue_html(source)
        self.assertIn("Mourouj 5 &lt; Mourouj 6", rendered)
        self.assertIn("needs re-check after catalog change", rendered)

    def test_historical_group_member_audit_is_separate_and_links_current_owner(self):
        source = sample_queue()
        source["counts"]["historicalGroupedMemberAuditCount"] = 1
        source["auditItems"] = [{
            "id": "historical-group-member:abc",
            "historicalMemberId": "osm:node:332247375",
            "currentSelectableOwnerId": "osm:relation:7174576",
            "governorate": "بن عروس",
            "nameAr": "المروج 5",
            "parentAr": "معتمدية المروج",
            "reviewScope": "AUDIT_ONLY_HISTORICAL_GROUP_MEMBER",
            "status": "RECONCILIATION_REQUIRED",
            "disposition": "NOT_RESOLVED",
            "reviewQuestion": "Check retained evidence against the current owner.",
            "manualReports": [{"caseId": "osm:node:332247375", "verdict": "problem",
                               "userAssertion": "The point is outside.", "stale": True}],
            "evidence": [],
        }]

        published = review_publishing._publication_copy(source)
        rendered = review_publishing.render_review_queue_html(source)

        audit = published["auditItems"][0]
        self.assertTrue(audit["manualReviewUrl"].endswith("?q=" + quote_plus("المروج 5")))
        self.assertEqual(audit["manualReviewLinks"][0]["caseId"], "osm:relation:7174576")
        self.assertEqual(len(published["items"]), len(source["items"]))
        self.assertIn("Historical grouped-member audits", rendered)
        self.assertIn("osm:node:332247375", rendered)
        self.assertIn("osm:relation:7174576", rendered)
        self.assertIn("excluded from active selectable review counts", rendered)
        self.assertIn("grouping does not verify identity, GPS, boundaries, or reliability", rendered)
        self.assertIn("The point is outside", rendered)

    def test_current_advice_is_collapsed_and_escaped(self):
        source = sample_queue()
        source["items"][0]["latestAdvisory"] = {
            "unresolved": ["Check <boundary>"],
            "recommendedNextSteps": ["Inspect the official map"],
        }
        rendered = review_publishing.render_review_queue_html(source)
        self.assertIn("<summary>Luna review (unverified)</summary>", rendered)
        self.assertIn("Check &lt;boundary&gt;", rendered)
        self.assertIn("Inspect the official map", rendered)

    def test_html_preserves_engine_order_and_escapes_untrusted_text(self):
        source = sample_queue()
        rendered = review_publishing.render_review_queue_html(source)

        self.assertLess(rendered.index("review:001"), rendered.index("review:002"))
        self.assertIn("3</div></div>", rendered)
        self.assertIn("2</div></div>", rendered)
        self.assertIn("0 model requests", rendered)
        self.assertIn("geographic reliability remains unverified", rendered)
        self.assertIn("&lt;script&gt;alert(1)&lt;/script&gt;", rendered)
        self.assertNotIn("<script>alert(1)</script>", rendered)
        self.assertNotIn("javascript:", rendered)
        self.assertIn("http://127.0.0.1:8769/?q=%3Cscript%3Ealert%281%29%3C%2Fscript%3E", rendered)
        self.assertIn("file:///C:/work/source-packets/map/packet.json", rendered)
        self.assertIn("Saved source map", rendered)

    def test_publish_writes_per_governorate_json_and_html_atomically(self):
        with tempfile.TemporaryDirectory() as temporary:
            out_dir = Path(temporary) / "review"
            original_replace = review_publishing.os.replace
            with mock.patch.object(review_publishing.os, "replace", wraps=original_replace) as replace:
                published = review_publishing.publish_review_queue(sample_queue(), out_dir)

            self.assertEqual(len(published["files"]), 1)
            files = published["files"][0]
            json_path, html_path = Path(files["json"]), Path(files["html"])
            self.assertTrue(json_path.is_file())
            self.assertTrue(html_path.is_file())
            self.assertEqual(replace.call_count, 2)
            for call in replace.call_args_list:
                temporary_path, destination_path = map(Path, call.args)
                self.assertEqual(temporary_path.parent, out_dir)
                self.assertEqual(destination_path.parent, out_dir)
            data = json.loads(json_path.read_text(encoding="utf-8"))
            self.assertEqual(data["counts"]["accepted"], 3)
            self.assertEqual(data["counts"]["rejected"], 2)
            self.assertEqual(data["counts"]["unresolved"], 2)
            self.assertEqual([item["id"] for item in data["items"]], ["review:001", "review:002"])
            self.assertEqual(data["publication"]["modelRequests"], 0)
            self.assertEqual(data["publication"]["modelRequests"], 0)
            self.assertTrue(data["items"][0]["manualReviewUrl"].startswith("http://127.0.0.1:8769/?q="))
            self.assertEqual(data["items"][0]["evidenceLinks"][0]["name"], "Saved evidence packet")
            self.assertFalse(any(path.name.endswith(".tmp") for path in out_dir.iterdir()))
            self.assertFalse((out_dir / ".review-queue.publish.lock").exists())

    def test_multiple_governorates_get_distinct_files_and_duplicates_are_rejected(self):
        first = sample_queue()
        second = sample_queue()
        second["governorate"] = "نابل"
        with tempfile.TemporaryDirectory() as temporary:
            published = review_publishing.publish_review_queue([first, second], temporary)
            self.assertEqual({row["governorate"] for row in published["files"]}, {"بن عروس", "نابل"})
            self.assertEqual(len({row["json"] for row in published["files"]}), 2)
            with self.assertRaisesRegex(ValueError, "duplicate governorate"):
                review_publishing.publish_review_queue([first, first], temporary)

    def test_existing_publisher_lock_prevents_a_second_writer(self):
        with tempfile.TemporaryDirectory() as temporary:
            out_dir = Path(temporary)
            (out_dir / ".review-queue.publish.lock").write_text("pid=1\n", encoding="ascii")
            with self.assertRaisesRegex(RuntimeError, "another review queue publisher"):
                review_publishing.publish_review_queue(sample_queue(), out_dir)


if __name__ == "__main__":
    unittest.main()
