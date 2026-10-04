"""Bind finite historical source gates to the unchanged native PDF replay helper.

Recognize the original heterogeneous schemas explicitly; never synthesize a GO,
repair geometry, choose by overlap, or infer a reviewer identity.
"""
import argparse
from datetime import datetime, timezone
import math
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import active_control, checked, pin, read
from scripts.locality_automation.audit_prepared_osm_graphs import write_new
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree
from scripts.locality_automation.work_window_guard import parse_utc


def historical_fields(row):
    decision = row.get("decision", row.get("verdict"))
    if decision != "GO_M_SOURCE" or row["officialCode"] == "425959":
        raise ValueError("Original source hold cannot be promoted")
    if "selectedSource" in row:
        source = row["selectedSource"]
        if source["kind"] != "own_title_sheet" or source["sourceSheetCode"] != row["officialCode"]:
            raise ValueError("Whole own-title source required")
        pdf, inventory = source["sourcePdf"], source["sourceInventory"]
        page_hash, metric_hash = row["approvedPageFaceWkbSha256"], row["approvedRegisteredFaceWkbSha256"]
    elif "nativeInventory" in row:
        if row["uniqueOwnTitleSourceInExactParentVerified"] is not True:
            raise ValueError("Original exact parent/title binding missing")
        pdf, inventory = row["sourcePdf"], row["nativeInventory"]
        page_hash, metric_hash = row["pageFaceWkbSha256"], row["registeredFaceWkbSha256"]
    elif "selectedRegisteredFaceWkbSha256" in row:
        pdf, inventory = row["sourcePdf"], row["sourceInventory"]
        page_hash, metric_hash = row["pageFaceWkbSha256"], row["selectedRegisteredFaceWkbSha256"]
    else:
        raise ValueError("Unrecognized historical gate schema; preserve original")
    if not page_hash or not metric_hash:
        raise ValueError("Missing original complete source body hash")
    return pdf, inventory, page_hash, metric_hash


def title_index(row, page):
    if "ownTitleInteriorLabelIndexes" in row:
        indexes = row["ownTitleInteriorLabelIndexes"]
        if len(indexes) != 1:
            raise ValueError("Original unique own-title index required")
        index = indexes[0]
    elif row.get("manualRecoveredEvidence") is not None:
        index = row["manualRecoveredEvidence"]["ownTitleLeadIndex"]
    elif "anchorPagePoints" in row:
        anchor = row["anchorPagePoints"]
        # Only bind the historical anchor to its original label; no automatic
        # title interpretation or boundary selection is performed here.
        matches = [i for i, label in enumerate(page["labeledAreaLeads"])
                   if label["redText"] and math.dist(anchor, label["centerPagePoints"]) <= .1]
        if len(matches) != 1:
            raise ValueError("Historical native-label anchor is not unique within0.1point")
        index = matches[0]
    else:
        raise ValueError("Missing original native-label anchor")
    if not page["labeledAreaLeads"][index]["redText"]:
        raise ValueError("Historical label is not original red source text")
    return index


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    spec = read(args.manifest)
    control = active_control(spec)
    if args.output.exists() or spec.get("credit") != 0 or datetime.now(timezone.utc) >= parse_utc(control["safeSourceQaStartUtc"]):
        raise ValueError("Fresh zero-credit native preparation phase required")
    if len(set(spec["codes"])) != len(spec["codes"]) or not set(spec["codes"]).issubset(control["approvedReconciliationPool"]):
        raise ValueError("Finite approved source pool differs")
    check_tree(spec)
    reconciliation = read(checked(spec["reconciliation"]))
    result = []
    for code in spec["codes"]:
        old = next(r for r in reconciliation["rows"] if r["officialCode"] == code)
        packet = read(checked(old["packet"]))
        row = next(r for r in packet["rows"] if r["officialCode"] == code)
        pdf, inventory, page_hash, metric_hash = historical_fields(row)
        if pdf != old["sourcePdf"] or metric_hash != old["sourceFace"]["sha256"] or row["appId"] != old["id"]:
            raise ValueError("Historical source, body or current identity differs")
        checked(pdf)
        cached = read(checked(inventory))
        index = title_index(row, cached["pages"][0])
        result.append({"officialCode": code, "officialName": row["officialName"], "officialParent": row["officialParent"],
            "appId": row["appId"], "sourcePdf": pdf, "sourceInventory": inventory,
            "pageFaceWkbSha256": page_hash, "legacySourceFace": old["sourceFace"], "legacyPacket": old["packet"],
            "ownTitleInteriorLabelIndexes": [index], "sourceGateDecision": "GO_M_SOURCE", "credit": 0})
    write_new(args.output, {"status": "ROOT_FROZEN_NATIVE_REPLAY_INPUTS_UNACCEPTED", "manifest": pin(args.manifest),
        "strictOriginalLeaveOneOutGateMeters": 1.5, "rows": result, "independentQaPassed": False,
        "sourceScopeAccepted": False, "credit": 0, "originalSourceActorsInferred": False})
    print({"prepared": len(result), "credit": 0})


if __name__ == "__main__":
    main()
