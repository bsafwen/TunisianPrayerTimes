"""Link exact targeted historical source exceptions without discarding old holds.

This finite context audit preserves the original caption/native holds and the
bounded followup decisions. It never infers a source actor, grants current QA,
changes a boundary, or turns a source exception into installation authority.
"""
import argparse
from datetime import datetime, timezone
from pathlib import Path
import sys

from shapely import from_wkb
from shapely.affinity import affine_transform

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.audit_native_replay_layers import audit
from scripts.locality_automation.audit_prepared_osm_graphs import write_new
from scripts.locality_automation.compare_isie_neighbor_linework import inventory_case, affine_coefficients
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree
from scripts.locality_automation.run_sealed_boundary_queue import active_control, checked, pin, read
from scripts.locality_automation.work_window_guard import parse_utc


def companion(item, original):
    code = item["code"]
    gate = read(checked(item["gate"]))
    check_tree(gate)
    if code == "426053":
        if gate["gateId"] != "kasserine-426053-paired-neighbor-source-exception-20260929-v1":
            raise ValueError("Unexpected explicit targeted source gate")
        if gate["decision"] != "GO_M_SOURCE_EXCEPTION" or gate["reciprocal426056RemainsHold"] is not True:
            raise ValueError("Exact bounded exception and retained reciprocal hold required")
        target, source, face = gate["officialIdentity"]["target"], gate["sources"][code], gate["selectedFace"]
        if target["code"] != code or source["correctParentExactTitlePdfCount"] != 1 or not face["completeNativeRedBoundary"]:
            raise ValueError("Literal exact-title whole-source identity differs")
        qa = read(checked(item["archivedQa"]))
        check_tree(qa)
        if qa["producerGate"] != item["gate"] or qa["decision"] != "GO_M_SOURCE_EXCEPTION_QA_ONLY":
            raise ValueError("Explicit archived QA/source pin differs")
        if qa["target"]["selectedPageSymmetricDifferenceSquarePoints"] != 0 or qa["target"]["selectedMetricSymmetricDifferenceSquareMeters"] != 0:
            raise ValueError("Archived original whole-source replay differs")
        if any(gate["publication"][key] for key in ("mCounted", "sCounted", "appEdited", "liveStateEdited")):
            raise ValueError("Source-only publication boundary differs")
        row = {"officialCode": code, "officialName": target["name"], "officialParent": target["parent"],
            "appId": original["id"], "sourcePdf": source["pdf"], "sourceInventory": source["nativeInventory"],
            "ownTitleInteriorLabelIndexes": [source["ownLabelIndex"]], "sourceGateDecision": gate["decision"],
            "pageWkb": face["pageWkb"], "registeredWkb": face["registeredWkb"]}
        limitation = qa["interpretation"]
    elif code == "425251":
        if (gate["gateId"] != "kasserine-425251-reciprocal-source-gate-20260929-v1"
                or gate["decision"] != "GO_M_SOURCE" or gate["official"]["code"] != code
                or gate["sourceReviewOnly"] is not True or any(gate["newCredit"].values())
                or gate["appOrLiveDataChanged"]):
            raise ValueError("Exact source-only reciprocal followup required")
        face = gate["candidateFace"]
        if not face["valid"] or face["holes"] != 0 or not gate["sourceIdentity"]["reciprocalInsAppIdentityStillBacklog"]:
            raise ValueError("Original whole source and reciprocal identity hold differ")
        old = original["originalSourceCandidates"][0]["row"]
        row = {"officialCode": code, "officialName": gate["official"]["name"], "officialParent": gate["official"]["parent"],
            "appId": original["id"], "sourcePdf": face["sourcePdf"], "sourceInventory": face["sourceInventory"],
            "ownTitleInteriorLabelIndexes": [old["nativeOwnLabel"]["index"]], "sourceGateDecision": gate["decision"],
            "pageWkb": face["pageWkb"], "registeredWkb": face["epsg32632Wkb"]}
        limitation = gate["limits"]
    else:
        raise ValueError("No automatic targeted-schema or code inference")
    if (row["officialCode"] != original["officialCode"] or row["officialName"] != original["officialName"]
            or row["officialParent"] != original["officialParent"]):
        raise ValueError("Literal original code/name/parent changed")
    case = inventory_case(row)
    page_face = from_wkb(checked(row["pageWkb"]).read_bytes())
    ground = from_wkb(checked(row["registeredWkb"]).read_bytes())
    if affine_transform(page_face, affine_coefficients(case["matrix"])).wkb != ground.wkb:
        raise ValueError("Whole targeted source no longer reproduces exact original registration")
    native = audit(row, row)
    return {"code": code, "id": original["id"], "oldContext": original, "targetedGate": item["gate"],
        "archivedQa": item.get("archivedQa"), "boundedHistoricalDecision": gate["decision"],
        "nativeAuditWithAllOrdinaryHoldsRetained": native, "wholeSourceFaces": {k: row[k] for k in ("pageWkb", "registeredWkb")},
        "limitations": limitation, "originalSourceHoldsDiscarded": False,
        "actualNewIndependentReviewerClaimed": False, "sourceAuthor": None,
        "currentSourceAcceptance": False, "geometryChanged": False, "credit": 0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    spec = read(args.manifest)
    ctl = active_control(spec)
    if args.output.exists() or spec.get("credit") != 0 or len(spec["items"]) != 2 or datetime.now(timezone.utc) >= parse_utc(ctl["safeSourceQaStartUtc"]):
        raise ValueError("Fresh exact finite root-only companion audit required")
    check_tree(spec)
    context = read(checked(spec["sourceContext"]))
    if context["credit"] != 0 or context["status"] != "FINITE_ORIGINAL_NEIGHBOR_CONTEXT_NO_ACCEPTANCE":
        raise ValueError("Original bounded source context differs")
    records = []
    for item in spec["items"]:
        original = next(r for r in context["records"] if r.get("officialCode") == item["code"])
        if original["status"] != "HOLD_ORIGINAL_SOURCE_DECISION_RETAINED":
            raise ValueError("A targeted companion must retain the explicit original source hold")
        records.append(companion(item, original))
    check_tree(spec)
    ref = write_new(args.output, {"status": "TARGETED_HISTORICAL_SOURCE_COMPANIONS_NO_ACCEPTANCE",
        "manifest": pin(args.manifest), "completedAtUtc": datetime.now(timezone.utc).isoformat(),
        "records": records, "originalHoldsPreserved": True, "credit": 0,
        "qualification": "Historical bounded caption-placement decisions are supporting context only. No current disjoint review, civil/legal scope, protected-body revision, GPS/runtime, full consumer acceptance or installation is asserted."})
    print({"report": ref, "codes": [r["code"] for r in records], "ordinaryNativeHolds": [r["nativeAuditWithAllOrdinaryHoldsRetained"]["holds"] for r in records], "credit": 0})


if __name__ == "__main__":
    main()
