"""Focused tests for the deterministic locality review queue."""
import json
import unittest

from scripts.locality_automation.review_queue import (
    DEFAULT_GOVERNORATE,
    PRIORITY_BOUNDARY,
    PRIORITY_GENERAL,
    PRIORITY_IDENTITY_OR_GPS,
    PRIORITY_PROBLEM_REPORT,
    PRIORITY_PENDING_REPORT,
    PRIORITY_STALE_PROBLEM_REPORT,
    build_review_queue,
)


class ReviewQueueTests(unittest.TestCase):
    def setUp(self):
        self.ids = {
            "m5": "osm:relation:10",
            "neighbor": "osm:relation:11",
            "text_only": "osm:relation:12",
            "outside": "osm:relation:90",
        }
        self.ledger = {"cases": {
            "auto-m5": {"id": self.ids["m5"], "governorate": DEFAULT_GOVERNORATE,
                        "role": "boundary_conflict", "status": "AUTOMATED_PASS_RECORDED",
                        "verificationCredit": 0, "batch": "C:\\runs\\ben-arous", "recordedAt": "2026-09-24T00:00:00Z"},
            "auto-neighbor": {"id": self.ids["neighbor"], "governorate": DEFAULT_GOVERNORATE,
                              "role": "active_location", "status": "AUTOMATED_PASS_RECORDED",
                              "verificationCredit": 0, "batch": "C:\\runs\\ben-arous", "recordedAt": "2026-09-24T00:00:00Z"},
            "auto-text": {"id": self.ids["text_only"], "governorate": DEFAULT_GOVERNORATE,
                          "role": "active_location", "status": "AUTOMATED_PASS_RECORDED",
                          "verificationCredit": 0, "batch": "C:\\runs\\ben-arous", "recordedAt": "2026-09-24T00:00:00Z"},
            "auto-outside": {"id": self.ids["outside"], "governorate": "تونس",
                             "role": "border_dependency", "status": "AUTOMATED_PASS_RECORDED",
                             "verificationCredit": 0, "batch": "C:\\runs\\tunis", "recordedAt": "2026-09-24T00:00:00Z"},
        }}
        self.batch_cases = [{
            "batchPath": "C:\\runs\\ben-arous",
            "catalogPin": {"file": "C:\\catalog\\catalog.json", "sha256": "a" * 64},
            "cases": [
                {"id": self.ids["m5"], "nameAr": "المروج 5", "parentAr": "معتمدية المروج",
                 "governorateAr": DEFAULT_GOVERNORATE, "role": "boundary_conflict",
                 "missingChecks": ["existence", "arabic_name", "gps_resolution", "boundary"],
                 "candidateId": self.ids["neighbor"],
                 "sourceMatches": [{"text": "المروج.pdf", "url": "https://example.invalid/mourouj.pdf"}]},
                {"id": self.ids["neighbor"], "nameAr": "المروج 4", "parentAr": "معتمدية المروج",
                 "governorateAr": DEFAULT_GOVERNORATE, "role": "active_location",
                 "missingChecks": ["boundary"]},
                {"id": self.ids["text_only"], "nameAr": "حي جديد", "parentAr": "معتمدية المروج",
                 "governorateAr": DEFAULT_GOVERNORATE, "role": "active_location",
                 "missingChecks": [], "acceptedChecks": ["prayer_source"]},
            ],
        }]
        self.catalog = {"locations": [
            {"id": self.ids["m5"], "fingerprint": "catalog-m5-v1"},
            {"id": self.ids["neighbor"], "fingerprint": "catalog-neighbor-v1"},
            {"id": self.ids["text_only"], "fingerprint": "catalog-text-v1"},
        ]}

    def build(self, **overrides):
        args = {
            "governorate": DEFAULT_GOVERNORATE,
            "case_ledger": self.ledger,
            "batch_cases": self.batch_cases,
            "investigations": [],
            "feedback": [],
            "catalog_locations": self.catalog,
            "source_files": {"caseLedger": "work/locality-automation/case-ledger.json"},
            "evidence_packets": [],
        }
        args.update(overrides)
        return build_review_queue(**args)

    def test_groups_structured_boundary_pair_and_prioritizes_manual_problem(self):
        report = {"id": self.ids["m5"], "verdict": "problem",
                  "note": "The GPS point shown for Mourouj 5 is outside the locality.",
                  "requestId": "request-1", "fingerprint": "catalog-m5-v1",
                  "savedAtUtc": "2026-09-24T01:00:00Z"}
        queue = self.build(feedback={"pending": [report], "stale": []})

        self.assertEqual(queue["governorate"], DEFAULT_GOVERNORATE)
        self.assertEqual(queue["counts"]["caseCount"], 3)
        self.assertEqual(queue["counts"]["outOfScopeCaseCount"], 1)
        self.assertEqual(queue["counts"]["reviewUnitCount"], 2)
        pair = next(item for item in queue["items"] if self.ids["m5"] in item["caseIds"])
        self.assertEqual(pair["caseIds"], [self.ids["m5"], self.ids["neighbor"]])
        self.assertEqual(pair["priority"], PRIORITY_PROBLEM_REPORT)
        self.assertIn("identity", pair["categories"])
        self.assertIn("gps", pair["categories"])
        self.assertIn("boundary", pair["categories"])
        self.assertEqual(pair["nameAr"], "المروج 5")
        self.assertEqual(pair["parentAr"], "معتمدية المروج")
        self.assertEqual(pair["manualReports"][0]["userAssertion"], report["note"])
        self.assertEqual(pair["manualReports"][0]["submittedAt"], report["savedAtUtc"])
        self.assertTrue(pair["modelEligible"])
        self.assertEqual(pair["currentCatalogFingerprint"], {
            self.ids["m5"]: "catalog-m5-v1", self.ids["neighbor"]: "catalog-neighbor-v1"})
        self.assertEqual(queue["currentCatalogFingerprint"][self.ids["m5"]], "catalog-m5-v1")

    def test_current_catalog_labels_override_item_display_without_rewriting_history(self):
        baseline = self.build()
        baseline_item = next(item for item in baseline["items"] if self.ids["m5"] in item["caseIds"])
        current_catalog = {"locations": [
            {"id": self.ids["m5"], "fingerprint": "catalog-m5-v1",
             "nameAr": "المروج 5 الحالية", "parentAr": "معتمدية المروج الحالية"},
            {"id": self.ids["neighbor"], "fingerprint": "catalog-neighbor-v1",
             "nameAr": "المروج 4 الحالي", "parentAr": "معتمدية المروج الحالية"},
            {"id": self.ids["text_only"], "fingerprint": "catalog-text-v1",
             "nameAr": "حي جديد", "parentAr": "معتمدية المروج"},
        ]}

        queue = self.build(catalog_locations=current_catalog)
        item = next(item for item in queue["items"] if self.ids["m5"] in item["caseIds"])
        historical_m5 = next(row for row in item["caseRecords"] if row["id"] == self.ids["m5"])
        historical_neighbor = next(row for row in item["caseRecords"] if row["id"] == self.ids["neighbor"])

        self.assertEqual(item["nameAr"], "المروج 5 الحالية")
        self.assertEqual(item["parentAr"], "معتمدية المروج الحالية")
        self.assertEqual(historical_m5["nameAr"], "المروج 5")
        self.assertEqual(historical_m5["parentAr"], "معتمدية المروج")
        self.assertEqual(historical_m5["sourceMatches"], self.batch_cases[0]["cases"][0]["sourceMatches"])
        self.assertEqual(historical_neighbor["nameAr"], "المروج 4")
        self.assertEqual(item["evidence"], baseline_item["evidence"])
        self.assertEqual(item["sourceFingerprint"], baseline_item["sourceFingerprint"])

        current_catalog["locations"][0]["fingerprint"] = "catalog-m5-v2"
        changed_queue = self.build(catalog_locations=current_catalog)
        changed_item = next(item for item in changed_queue["items"] if self.ids["m5"] in item["caseIds"])
        self.assertNotEqual(changed_item["sourceFingerprint"], baseline_item["sourceFingerprint"])
        self.assertEqual(changed_item["nameAr"], "المروج 5 الحالية")

    def test_exact_grouped_historical_member_is_preserved_as_audit_only(self):
        owner_id = self.ids["neighbor"]
        missing_id = self.ids["m5"]
        catalog = {"locations": [
            {"id": owner_id, "fingerprint": "owner-v2", "governorateAr": DEFAULT_GOVERNORATE,
             "nameAr": "المروج 4 الحالي", "parentAr": "معتمدية المروج الحالية", "kind": "neighborhood"},
            {"id": self.ids["text_only"], "fingerprint": "catalog-text-v1",
             "governorateAr": DEFAULT_GOVERNORATE, "nameAr": "حي جديد", "parentAr": "معتمدية المروج", "kind": "quarter"},
        ]}
        ledger = {"cases": dict(self.ledger["cases"])}
        ledger["cases"]["unmapped"] = {
            "id": "osm:node:unmapped", "governorate": DEFAULT_GOVERNORATE,
            "role": "active_location", "status": "AUTOMATED_PASS_RECORDED",
            "verificationCredit": 0, "batch": "C:\\runs\\ben-arous", "recordedAt": "2026-09-24T00:00:00Z",
        }
        feedback = {"pending": [{
            "id": missing_id, "verdict": "problem",
            "userAssertion": "The saved pin seems outside the named locality.",
            "requestId": "old-problem", "fingerprint": "catalog-m5-v1",
        }], "stale": []}
        issue = {"id": missing_id, "issue": "The historical point needs coordinate review.",
                 "source": "manual investigation",
                 "evidence": {"file": "historical-overlay.json", "sha256": "e" * 64}}
        feature_rows = [
            {"id": missing_id, "pickerGroupId": owner_id},
            {"id": owner_id, "pickerGroupId": owner_id},
            {"id": self.ids["text_only"], "pickerGroupId": self.ids["text_only"]},
        ]

        queue = self.build(case_ledger=ledger, catalog_locations=catalog,
                           picker_group_features=feature_rows, feedback=feedback,
                           investigations=[issue])

        self.assertEqual(queue["counts"]["ledgerCaseCount"], 4)
        self.assertEqual(queue["counts"]["caseCount"], 3)
        self.assertEqual(queue["counts"]["selectableCatalogCaseCount"], 2)
        self.assertEqual(queue["counts"]["unmappedMissingCatalogCaseCount"], 1)
        self.assertEqual(queue["counts"]["historicalGroupedMemberAuditCount"], 1)
        self.assertEqual(len(queue["auditItems"]), 1)
        self.assertFalse(any(missing_id in item["caseIds"] for item in queue["items"]))
        self.assertTrue(any("osm:node:unmapped" in item["caseIds"] for item in queue["items"]))
        audit = queue["auditItems"][0]
        self.assertEqual(audit["historicalMemberId"], missing_id)
        self.assertEqual(audit["currentSelectableOwnerId"], owner_id)
        self.assertEqual(audit["nameAr"], "المروج 4 الحالي")
        self.assertEqual(audit["parentAr"], "معتمدية المروج الحالية")
        self.assertEqual(audit["manualReports"][0]["userAssertion"], feedback["pending"][0]["userAssertion"])
        self.assertTrue(audit["manualReports"][0]["stale"])
        self.assertTrue(audit["manualReports"][0]["reconciliationRequired"])
        self.assertEqual(audit["unresolvedConflicts"][0]["evidence"]["sha256"], "e" * 64)
        self.assertEqual(audit["status"], "RECONCILIATION_REQUIRED")
        self.assertEqual(audit["verificationCredit"], 0)
        self.assertEqual(audit["geographicReliability"], "NOT_ASSESSED")
        self.assertNotIn(missing_id, {row["caseId"] for row in queue["caseDispositions"]})

    def test_group_metadata_never_suppresses_missing_id_without_current_owner(self):
        missing_id = self.ids["m5"]
        features = [{"id": missing_id, "pickerGroupId": "osm:relation:not-current"}]
        queue = self.build(picker_group_features=features)
        self.assertEqual(queue["counts"]["historicalGroupedMemberAuditCount"], 0)
        self.assertTrue(any(missing_id in item["caseIds"] for item in queue["items"]))

    def test_free_text_ids_do_not_create_atomic_groups_and_passes_stay_unresolved(self):
        issues = [{"id": self.ids["text_only"],
                   "issue": f"Related to {self.ids['neighbor']}; review possible overlap.",
                   "source": "deepseek advisory; unverified"}]
        queue = self.build(investigations=issues)

        self.assertEqual(queue["counts"]["reviewUnitCount"], 2)
        text_item = next(item for item in queue["items"] if self.ids["text_only"] in item["caseIds"])
        self.assertEqual(text_item["caseIds"], [self.ids["text_only"]])
        self.assertEqual(text_item["status"], "UNRESOLVED")
        self.assertEqual(text_item["disposition"], "REVIEW_REQUIRED")
        self.assertFalse(text_item["modelEligible"])
        self.assertEqual(text_item["priority"], PRIORITY_GENERAL)
        self.assertEqual(queue["counts"]["accepted"], 0)
        self.assertEqual(queue["counts"]["rejected"], 0)
        self.assertEqual(queue["counts"]["unresolved"], len(queue["items"]))

    def test_exact_human_disposition_suppresses_but_advisory_or_stale_source_does_not(self):
        first = self.build()
        target = next(item for item in first["items"] if set(item["caseIds"]) == {self.ids["neighbor"], self.ids["m5"]})
        human = {"unitId": target["id"], "caseIds": target["caseIds"],
                 "sourceFingerprint": target["sourceFingerprint"], "disposition": "ACCEPTED",
                 "humanReviewed": True, "reviewedBy": "reviewer"}

        reviewed = self.build(reviewed=[human])
        self.assertEqual(reviewed["counts"]["accepted"], 1)
        self.assertTrue(any(row["disposition"] == "ACCEPTED" for row in reviewed["dispositions"]))
        self.assertFalse(any(row["id"] == target["id"] for row in reviewed["items"]))

        advisory_only = {**human, "humanReviewed": False, "reviewedBy": None}
        advisory_queue = self.build(reviewed=[advisory_only])
        self.assertEqual(advisory_queue["counts"]["accepted"], 0)
        self.assertTrue(any(row["id"] == target["id"] for row in advisory_queue["items"]))

        changed_catalog = {"locations": [
            {"id": self.ids["m5"], "fingerprint": "catalog-m5-v2"},
            {"id": self.ids["neighbor"], "fingerprint": "catalog-neighbor-v1"},
            {"id": self.ids["text_only"], "fingerprint": "catalog-text-v1"},
        ]}
        changed_queue = self.build(reviewed=[human], catalog_locations=changed_catalog)
        changed = next(item for item in changed_queue["items"] if self.ids["m5"] in item["caseIds"])
        self.assertNotEqual(changed["sourceFingerprint"], target["sourceFingerprint"])
        self.assertEqual(changed_queue["counts"]["accepted"], 0)

    def test_duplicate_investigations_are_deduped_and_output_is_order_independent(self):
        issue = {"id": self.ids["m5"], "issue": "GPS resolution remains unaccepted.",
                 "source": "independent check", "evidence": {"file": "evidence.json", "sha256": "b" * 64}}
        queue = self.build(investigations=[issue, issue, issue])
        reversed_queue = self.build(investigations=[issue, issue, issue])
        item = next(row for row in queue["items"] if self.ids["m5"] in row["caseIds"])

        self.assertEqual(queue["counts"]["scopedInvestigationRows"], 3)
        self.assertEqual(queue["counts"]["uniqueInvestigationRows"], 1)
        self.assertEqual(item["issueCount"], 1)
        self.assertEqual(item["sourceFingerprint"], next(row for row in reversed_queue["items"] if row["id"] == item["id"])["sourceFingerprint"])
        json.dumps(queue, ensure_ascii=False, allow_nan=False)

    def test_caps_issue_context_but_fingerprints_all_unique_rows(self):
        issues = [{"id": self.ids["m5"], "issue": f"Question {index}: identity remains unresolved.",
                   "source": "manual evidence"} for index in range(20)]
        queue = self.build(investigations=issues)
        item = next(row for row in queue["items"] if self.ids["m5"] in row["caseIds"])

        self.assertEqual(item["issueCount"], 20)
        self.assertEqual(len(item["unresolvedConflicts"]), 12)
        self.assertTrue(item["issuesTruncated"])
        self.assertEqual(queue["counts"]["scopedInvestigationRows"], 20)
        self.assertFalse(item["modelEligible"])

    def test_priority_order_pending_then_identity_then_general(self):
        queue = self.build(feedback={"pending": [{"id": self.ids["text_only"], "nameAr": "حي جديد"}], "stale": []})
        by_ids = {tuple(item["caseIds"]): item for item in queue["items"]}
        self.assertEqual(by_ids[(self.ids["m5"], self.ids["neighbor"])]["priority"], PRIORITY_BOUNDARY)
        self.assertFalse(by_ids[(self.ids["m5"], self.ids["neighbor"])]["modelEligible"])
        self.assertEqual(by_ids[(self.ids["text_only"],)]["priority"], PRIORITY_PENDING_REPORT)

    def test_concrete_gps_diagnostic_is_prioritized_and_model_eligible(self):
        issue = {"id": self.ids["m5"],
                 "issue": "The catalog point is outside the official locality polygon; GPS mismatch needs review.",
                 "source": "independent overlay diagnostic",
                 "evidence": {"file": "overlay.json", "sha256": "c" * 64}}
        queue = self.build(investigations=[issue])
        item = next(row for row in queue["items"] if self.ids["m5"] in row["caseIds"])

        self.assertEqual(item["priority"], PRIORITY_IDENTITY_OR_GPS)
        self.assertEqual(item["concreteSignals"], ["gps"])
        self.assertTrue(item["modelEligible"])

    def test_generic_boundary_hold_template_does_not_trigger_model_review(self):
        issue = {"id": self.ids["m5"],
                 "issue": "Boundary hypothesis is ambiguous, clipped, or excludes the saved point; retained for investigation."}
        queue = self.build(investigations=[issue])
        item = next(row for row in queue["items"] if self.ids["m5"] in row["caseIds"])

        self.assertEqual(item["concreteSignals"], [])
        self.assertEqual(item["priority"], PRIORITY_BOUNDARY)
        self.assertFalse(item["modelEligible"])
        self.assertEqual(queue["counts"]["gpsDiagnosticUnits"], 0)
        self.assertEqual(queue["counts"]["boundaryDiagnosticUnits"], 0)

    def test_current_catalog_pin_is_exposed_for_worker_staleness_check(self):
        pin = {"file": "C:\\catalog\\current.json", "sha256": "d" * 64}
        queue = self.build(catalog_pin=pin)

        self.assertEqual(queue["currentCatalogPin"], "d" * 64)
        self.assertEqual(queue["catalogFingerprintSource"], "current_catalog_pin")

    def test_stale_problem_report_does_not_get_current_problem_priority(self):
        stale_report = {"id": self.ids["m5"], "verdict": "problem", "note": "GPS is wrong.",
                        "requestId": "old-request", "fingerprint": "catalog-m5-old"}
        queue = self.build(feedback=[stale_report])
        item = next(row for row in queue["items"] if self.ids["m5"] in row["caseIds"])

        self.assertEqual(queue["counts"]["manualProblemReports"], 0)
        self.assertEqual(queue["counts"]["staleManualReports"], 1)
        self.assertEqual(item["priority"], PRIORITY_STALE_PROBLEM_REPORT)
        self.assertTrue(item["manualReports"][0]["stale"])
        self.assertFalse(item["modelEligible"])

    def test_empty_governorate_is_rejected(self):
        with self.assertRaises(ValueError):
            self.build(governorate="  ")


if __name__ == "__main__":
    unittest.main()
