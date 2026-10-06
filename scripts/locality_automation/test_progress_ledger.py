"""Focused state/replay checks for official-identity boundary counts."""
from copy import deepcopy
from pathlib import Path
import json
import tempfile
import unittest

from scripts.locality_automation.common import pin
from scripts.locality_automation.progress_ledger import BoundaryLedger, DEFAULT_METHOD_FAMILIES


class ProgressLedgerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.ledger = BoundaryLedger(self.root / "progress.sqlite")
        self.seq = 0
        self.inventory = {
            "schemaVersion": 1, "fingerprint": "ministry-2026-09-25", "inventoryDate": "2026-09-25",
            "catalogRevision": "catalog-v1", "ruleVersions": {"evidence": "1", "measurement": "1", "acceptance": "1", "disposition": "1"},
            "methods": {name: {"family": family, "sourceVersion": "source-1", "methodVersion": "method-1"}
                        for name, family in DEFAULT_METHOD_FAMILIES.items()},
            "identities": [self.identity("official:A", "g1", "geom-A"), self.identity("official:B", "g2", "geom-B")],
            "reconciliation": {"complete": True, "backlogIds": []},
        }

    @staticmethod
    def identity(ident, governorate, geometry):
        return {"officialId": ident, "governorate": governorate, "catalogFingerprint": "catalog-" + ident,
                "basis": {"stateKey": "state-" + geometry, "geometrySha256": geometry,
                          "neighborHashes": {"official:neighbor": "neighbor-v1"}},
                "boundaryComponents": ["north", "south"]}

    def artifact(self, payload=None):
        self.seq += 1
        path = self.root / f"artifact-{self.seq}.json"
        path.write_text(json.dumps(payload if payload is not None else {"result": self.seq}), encoding="utf-8")
        return pin(path)

    def evidence(self, *, ident="official:A", method="isie_native_vector", evidence_id="e1", stage="measured",
                 components=None, verdict="supports", revision=1, basis=None, **overrides):
        row = next(r for r in self.inventory["identities"] if r["officialId"] == ident)
        event = {"eventId": f"event-{self.seq+1}", "kind": "evidence", "identityId": ident,
                 "evidenceId": evidence_id, "method": method, "stage": stage, "stageRevision": revision,
                 "artifact": self.artifact(), "basis": deepcopy(basis or row["basis"]),
                 "catalogFingerprint": row["catalogFingerprint"],
                 "sourceVersion": self.inventory["methods"][method]["sourceVersion"],
                 "methodVersion": self.inventory["methods"][method]["methodVersion"],
                 "ruleVersion": self.inventory["ruleVersions"]["measurement" if stage == "measured" else "acceptance" if stage == "accepted" else "evidence"]}
        if stage == "measured":
            event.update({"coverage": {"scope": "official_imada", "components": components or ["north"],
                                       "compatibilityGroup": "same-map"},
                          "registered": True, "quantitative": {"maxGapMeters": 12.0},
                          "scopeMatch": True, "verdict": verdict})
        if stage == "accepted":
            event.update({"acceptedCoverage": "whole", "measurementEvidenceIds": [evidence_id],
                          "supportedGeometrySha256": row["basis"]["geometrySha256"]})
        event.update(overrides)
        return event

    def disposition(self, ident="official:A", outcome="SUPPORTED", refs=None):
        row = next(r for r in self.inventory["identities"] if r["officialId"] == ident)
        return {"eventId": f"event-{self.seq+1}", "kind": "disposition", "identityId": ident,
                "artifact": self.artifact(), "basis": deepcopy(row["basis"]), "ruleVersion": "1",
                "disposition": outcome, "evidenceIds": refs or ["e1"]}

    def install(self, status, geometry="geom-A", receipt_id="install-A"):
        artifact = self.artifact({"status": status})
        return {"eventId": f"event-{self.seq}", "kind": "install", "identityId": "official:A",
                "artifact": artifact, "status": status, "geometrySha256": geometry, "receiptId": receipt_id}

    def test_duplicate_and_two_methods_have_one_overall_measurement(self):
        isie = self.evidence(verdict="rejects")
        seq = self.ledger.record(isie)
        self.assertEqual(self.ledger.record(isie), seq)
        self.ledger.record(self.evidence(method="google_partial_outline", evidence_id="g1"))
        snap = self.ledger.snapshot(self.inventory, "g1")
        scope = snap["currentGovernorate"]
        self.assertEqual((scope["N"], scope["P"], scope["M"], scope["S"]), (1, 0, 1, 0))
        self.assertEqual(scope["families"]["ISIE"]["measured"]["identityIds"], ["official:A"])
        self.assertEqual(scope["families"]["Google"]["measured"]["identityIds"], ["official:A"])
        self.assertEqual(scope["allMethods"]["measured"]["identityIds"], ["official:A"])
        self.assertEqual(scope["methods"]["isie_native_vector"]["stages"]["attempted"]["count"], 0)

    def test_partial_to_full_and_acceptance_are_distinct(self):
        self.ledger.record(self.evidence())
        self.ledger.record(self.evidence(stage="attempted", evidence_id="other"))
        first = self.ledger.snapshot(self.inventory)["wholeInventory"]
        self.assertEqual((first["M"], first["S"], first["allMethods"]["partialOnly"]["count"]), (1, 0, 1))
        self.ledger.record(self.evidence(components=["north", "south"], revision=2))
        self.ledger.record(self.evidence(stage="accepted"))
        self.ledger.record(self.disposition())
        final = self.ledger.snapshot(self.inventory)["wholeInventory"]
        self.assertEqual((final["P"], final["M"], final["S"]), (1, 1, 1))
        self.assertEqual(final["allMethods"]["full"]["identityIds"], ["official:A"])
        self.assertEqual(final["allMethods"]["partialOnly"]["count"], 0)
        self.assertEqual(final["dispositions"]["SUPPORTED"]["count"], 1)

    def test_placeholder_and_scope_mismatch_do_not_gain_false_credit(self):
        self.ledger.record(self.evidence(stage="attempted"))
        self.ledger.record(self.disposition())
        self.assertEqual(self.ledger.snapshot(self.inventory)["wholeInventory"]["P"], 0)
        mismatch = self.evidence(evidence_id="mismatch", components=["north", "south"], scopeMatch=False)
        self.ledger.record(mismatch)
        snap = self.ledger.snapshot(self.inventory)["wholeInventory"]
        self.assertEqual(snap["M"], 1)
        self.assertEqual(snap["allMethods"]["full"]["count"], 0)
        self.assertEqual(snap["allMethods"]["partialOnly"]["count"], 1)

    def test_invalidation_and_late_result_cannot_restore_membership(self):
        self.ledger.record(self.evidence())
        invalidation = {"eventId": f"event-{self.seq+1}", "kind": "invalidate", "identityId": "official:A",
                        "evidenceId": "e1", "reason": "Source calibration withdrawn", "artifact": self.artifact()}
        self.ledger.record(invalidation)
        self.ledger.record(self.evidence(components=["north", "south"], revision=2))
        self.assertEqual(self.ledger.snapshot(self.inventory)["wholeInventory"]["M"], 0)
        self.assertTrue(self.ledger.snapshot(self.inventory)["historicalEvidence"]["official:A"])

    def test_inventory_split_changes_denominator_without_inheriting_full(self):
        self.ledger.record(self.evidence(components=["north", "south"]))
        self.ledger.record(self.evidence(stage="accepted"))
        self.ledger.record(self.disposition())
        old = self.ledger.snapshot(self.inventory)["wholeInventory"]
        self.assertEqual((old["N"], old["S"]), (2, 1))
        new = deepcopy(self.inventory)
        new["fingerprint"] = "ministry-after-split"
        new["identities"] = [self.identity("official:A1", "g1", "geom-A1"),
                             self.identity("official:A2", "g1", "geom-A2"), self.identity("official:B", "g2", "geom-B")]
        newer = self.ledger.snapshot(new)["wholeInventory"]
        self.assertEqual((newer["N"], newer["M"], newer["S"]), (3, 0, 0))
        rename = deepcopy(self.inventory)
        rename["identities"][0]["catalogFingerprint"] = "catalog-renamed"
        self.assertEqual(self.ledger.snapshot(rename)["wholeInventory"]["S"], 1)

    def test_replay_and_publication_retry_preserve_delta(self):
        event = self.evidence()
        completion = self.root / "completed.json"
        completion.write_text(json.dumps(event), encoding="utf-8")
        self.ledger.ingest_completion(completion)
        # Process restart sees the same durable record and projects it exactly once.
        restarted = BoundaryLedger(self.root / "progress.sqlite")
        restarted.ingest_completion(completion)
        first = restarted.prepare_report(self.inventory, "g1")
        self.assertEqual(first["currentGovernorate"]["delta"]["allMethods"]["measured"]["newOrRevalidated"], 1)
        self.assertEqual(restarted.prepare_report(self.inventory, "g1")["reportId"], first["reportId"])
        restarted.confirm_published(first["reportId"])
        restarted.confirm_published(first["reportId"])
        second = restarted.prepare_report(self.inventory, "g1")
        self.assertEqual(second["currentGovernorate"]["delta"]["allMethods"]["measured"]["newOrRevalidated"], 0)

    def test_install_rollback_removes_correction_and_acceptance(self):
        self.ledger.record(self.evidence(components=["north", "south"]))
        self.ledger.record(self.install("INSTALLED"))
        acceptance = self.evidence(stage="accepted", proposalSha256="proposal-1", installReceiptId="install-A")
        self.ledger.record(acceptance)
        self.ledger.record(self.disposition(outcome="CORRECTED"))
        accepted = self.ledger.snapshot(self.inventory)["wholeInventory"]
        self.assertEqual((accepted["S"], accepted["currentCorrections"]["count"]), (1, 1))
        self.ledger.record(self.install("ROLLED_BACK"))
        rolled_back = self.ledger.snapshot(self.inventory)["wholeInventory"]
        self.assertEqual((rolled_back["S"], rolled_back["P"], rolled_back["currentCorrections"]["count"]), (0, 0, 0))

    def test_unreconciled_counts_are_not_publishable(self):
        inventory = deepcopy(self.inventory)
        inventory["reconciliation"] = {"complete": False, "backlogIds": ["official:A"]}
        snap = self.ledger.prepare_report(inventory)
        self.assertEqual(snap["status"], "UNRECONCILED")
        self.assertIsNone(snap["publishableCounts"])
        self.assertNotIn("reportId", snap)

    def test_plus_and_minus_remain_visible_when_net_is_zero(self):
        self.ledger.record(self.evidence())
        first = self.ledger.prepare_report(self.inventory)
        self.ledger.confirm_published(first["reportId"])
        self.ledger.record({"eventId": f"event-{self.seq+1}", "kind": "invalidate",
                            "identityId": "official:A", "evidenceId": "e1", "reason": "Source changed",
                            "artifact": self.artifact()})
        self.ledger.record(self.evidence(ident="official:B", evidence_id="e-b"))
        second = self.ledger.prepare_report(self.inventory)
        delta = second["wholeInventory"]["delta"]["allMethods"]["measured"]
        self.assertEqual((delta["newOrRevalidated"], delta["invalidated"], delta["net"]), (1, 1, 0))
        self.assertEqual(delta["newOrRevalidatedIds"], ["official:B"])
        self.assertEqual(delta["invalidatedIds"], ["official:A"])

    def test_inventory_rebase_separates_scope_loss_from_validation_loss(self):
        self.ledger.record(self.evidence(components=["north", "south"]))
        first = self.ledger.prepare_report(self.inventory)
        self.ledger.confirm_published(first["reportId"])
        new = deepcopy(self.inventory)
        new["fingerprint"] = "ministry-split"
        new["identities"] = [self.identity("official:A1", "g1", "geom-A1"),
                             self.identity("official:A2", "g1", "geom-A2"), self.identity("official:B", "g2", "geom-B")]
        # A changed denominator requires an explicit official identity crosswalk.
        with self.assertRaisesRegex(ValueError, "crosswalk"):
            self.ledger.prepare_report(new)
        new["crosswalk"] = [{"oldIds": ["official:A"], "newIds": ["official:A1", "official:A2"], "reason": "split"}]
        second = self.ledger.prepare_report(new)
        self.assertEqual((second["wholeInventory"]["previousN"], second["wholeInventory"]["N"]), (2, 3))
        delta = second["wholeInventory"]["delta"]["allMethods"]["measured"]
        self.assertEqual(delta["scopeRemovedMembershipIds"], ["official:A"])
        self.assertEqual(delta["invalidatedWorkIds"], [])
        self.assertEqual(second["wholeInventory"]["scopeAddedIds"], ["official:A1", "official:A2"])

    def test_missing_or_modified_current_artifact_reports_tracking_error(self):
        event = self.evidence()
        self.ledger.record(event)
        trusted = self.ledger.snapshot(self.inventory)
        self.assertEqual(trusted["status"], "CURRENT")
        Path(event["artifact"]["file"]).write_text('{"tampered":true}', encoding="utf-8")
        result = self.ledger.snapshot(self.inventory)
        self.assertEqual(result["status"], "TRACKING_ERROR")
        self.assertEqual(result["lastTrustedSnapshot"]["watermark"], trusted["watermark"])

    def test_complementary_isie_and_google_partials_do_not_become_full(self):
        self.ledger.record(self.evidence(components=["north"]))
        self.ledger.record(self.evidence(method="google_partial_outline", evidence_id="google-south", components=["south"]))
        scope = self.ledger.snapshot(self.inventory)["wholeInventory"]
        self.assertEqual(scope["M"], 1)
        self.assertEqual(scope["allMethods"]["full"]["count"], 0)
        self.assertEqual(scope["families"]["ISIE"]["partialOnly"]["count"], 1)
        self.assertEqual(scope["families"]["Google"]["partialOnly"]["count"], 1)


if __name__ == "__main__":
    unittest.main()
