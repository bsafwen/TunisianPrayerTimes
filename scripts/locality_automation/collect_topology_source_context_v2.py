"""Bind finite conflict components to existing original ISIE source packets.

Never fetches, guesses source identities, repairs native geometry, expands an
acceptance pool, or changes installed/protected bodies. Missing/held sources
remain explicit. This replaces repeated manual searches of the same packets.
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


def select_sources(ids, packets):
    selected = {ident: [] for ident in ids}
    for reference, packet in packets:
        for row in packet["rows"]:
            if row["appId"] in selected:
                selected[row["appId"]].append((reference, row))
    # An ambiguous historical version is retained as a hold, never ranked.
    return selected


def original_decision(row):
    fields = [key for key in ("decision", "verdict") if key in row]
    if len(fields) != 1 or not isinstance(row[fields[0]], str):
        raise ValueError("Exactly one literal original decision or verdict required")
    return fields[0], row[fields[0]]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    spec = read(args.manifest)
    ctl = active_control(spec)
    if args.output.exists() or spec.get("credit") != 0 or datetime.now(timezone.utc) >= parse_utc(ctl["safeSourceQaStartUtc"]):
        raise ValueError("Fresh bounded source context required")
    check_tree(spec)
    plan = read(checked(spec["plan"]))
    if plan["status"] != "FINITE_TOPOLOGY_RECONCILIATION_PLAN_NO_ACCEPTANCE" or plan["credit"] != 0:
        raise ValueError("Exact unaccepted topology plan required")
    ids = {ident for component in plan["components"] for ident in component["ids"]}
    if not ids or len(ids) > 40 or len(spec["packets"]) > 4:
        raise ValueError("Finite <=40-body / <=4-packet scope required")
    candidates = select_sources(ids, [(ref, read(checked(ref))) for ref in spec["packets"]])
    metadata = {row["id"]: row for row in read(checked(spec["metadata"]))["features"]}
    if not ids <= metadata.keys():
        raise ValueError("Conflict body missing from actual current metadata")
    protected = {ident for c in plan["components"] for ident in c["protectedPeerIds"]}
    records = []
    for ident in sorted(ids):
        matches = candidates[ident]
        record = {"id": ident, "currentName": metadata[ident]["name"],
            "currentParentName": metadata[ident].get("parentName"),
            "protectedCurrentBody": ident in protected,
            "originalSourceCandidates": [{"packet": ref, "row": row} for ref, row in matches],
            "sourceAuthor": None, "independentQaPassed": False, "sourceScopeAccepted": False, "credit": 0}
        if len(matches) != 1:
            record["status"] = "HOLD_AMBIGUOUS_OR_MISSING_ORIGINAL_SOURCE"
            records.append(record)
            continue
        reference, original = matches[0]
        decision_field, decision = original_decision(original)
        record.update(officialCode=original["officialCode"], officialName=original["officialName"],
                      officialParent=original["officialParent"], originalDecision=decision,
                      originalDecisionField=decision_field)
        if decision != "GO_M_SOURCE":
            record["status"] = "HOLD_ORIGINAL_SOURCE_DECISION_RETAINED"
            records.append(record)
            continue
        code = original["officialCode"]
        native = spec["nativeFaces"].get(code)
        if native is None:
            record["status"] = "HOLD_FROZEN_COMPLETE_NATIVE_FACE_NOT_RELEASED"
            records.append(record)
            continue
        required = ("pageFaceWkbSha256", "selectedRegisteredFaceWkbSha256", "sourcePdf", "sourceInventory", "ownTitleInteriorLabelIndexes")
        if any(key not in original for key in required):
            record["status"] = "HOLD_SOURCE_SCHEMA_REQUIRES_EXPLICIT_NATIVE_ADAPTER"
            records.append(record)
            continue
        if (native["pageWkb"]["sha256"] != original["pageFaceWkbSha256"]
                or native["registeredWkb"]["sha256"] != original["selectedRegisteredFaceWkbSha256"]):
            raise ValueError("Released complete native face differs from original source binding")
        row = {**original, "sourceGateDecision": decision, **native}
        case = inventory_case(row)
        face = from_wkb(checked(native["pageWkb"]).read_bytes())
        ground = from_wkb(checked(native["registeredWkb"]).read_bytes())
        if affine_transform(face, affine_coefficients(case["matrix"])).wkb != ground.wkb:
            raise ValueError("Original exact affine no longer reproduces whole source face")
        result = audit(row, native)
        record.update(status="HOLD_ORIGINAL_NATIVE_LAYER_DEFECTS" if result["holds"] else "NATIVE_SOURCE_CONTEXT_READY_NOT_ACCEPTED",
                      nativeAudit=result, nativeFaces=native)
        records.append(record)
    check_tree(spec)
    counts = {status: sum(r["status"] == status for r in records) for status in sorted({r["status"] for r in records})}
    ref = write_new(args.output, {"status": "FINITE_ORIGINAL_NEIGHBOR_CONTEXT_NO_ACCEPTANCE", "manifest": pin(args.manifest),
        "completedAtUtc": datetime.now(timezone.utc).isoformat(), "records": records, "counts": counts,
        "completeConflictBodyCount": len(ids), "missingBodiesSilentlyDiscarded": False,
        "sourceAuthorsInferred": False, "currentGeometryChanged": False, "credit": 0,
        "qualification": "Existing source context only. Protected peers remain immutable; source actor provenance, legal/identity/full-scope/current source agreement and every original GPS/prayer/persistence/CAF+A4 gate remain unresolved until explicitly proved."})
    print({"report": ref, "counts": counts, "credit": 0})


if __name__ == "__main__":
    main()
