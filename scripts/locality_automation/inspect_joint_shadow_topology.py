"""Bind joint staging to original individual bodies and all protected records.

The exact original conflict report is retained; no official overlap assertion,
conflict clipping or GPS/JVM acceptance is inferred from this diagnosis.
"""
import argparse
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import active_control, checked, pin, read
from scripts.locality_automation.audit_prepared_osm_graphs import write_new
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree
from scripts.locality_automation.packed_gps_replay import PackedGpsReplay
from scripts.locality_automation.run_solo_source_gps_audit import protected_bodies
from scripts.locality_automation.work_window_guard import parse_utc


def body_bytes(replay, blob, ident):
    row = replay.by_id[ident]
    return blob[row["offset"]:row["offset"] + row["length"]] if row["hasBoundary"] else b""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    spec = read(args.manifest)
    ctl = active_control(spec)
    check_tree(spec)
    if args.output.exists() or spec.get("credit") != 0 or datetime.now(timezone.utc) >= parse_utc(ctl["safeSourceQaStartUtc"]):
        raise ValueError("Fresh finite zero-credit joint topology inspection required")
    stage = read(checked(spec["jointStage"]))
    check_tree(stage)
    proposal = read(checked(stage["proposal"]))
    if (proposal["status"] != "UNACCEPTED_ROOT_JOINT_SOURCE_HYPOTHESIS_NO_INSTALLATION"
            or proposal["credit"] != 0 or stage["status"] != "STAGED_REQUIRES_GEOGRAPHIC_AND_GPS_REVIEW"):
        raise ValueError("Joint staged scope is not explicitly unaccepted")
    before = PackedGpsReplay(checked(stage["beforeMetadata"]), checked(stage["beforeBinary"]))
    after = PackedGpsReplay(checked(stage["stagedMetadata"]), checked(stage["stagedBinary"]))
    old_blob, new_blob = checked(stage["beforeBinary"]).read_bytes(), checked(stage["stagedBinary"]).read_bytes()
    changed = {patch["id"] for patch in proposal["patches"]}
    if (len(before.features) != 3473 or len(before.boundaries) != 2571
            or [row["id"] for row in before.features] != [row["id"] for row in after.features]
            or len(after.boundaries) != 2571 or changed != set(stage["changedIds"])):
        raise ValueError("Complete current/joint catalogue universe differs")
    unrelated = 0
    for row in before.features:
        ident = row["id"]
        if ident in changed:
            continue
        fields = lambda item: {key: value for key, value in item.items() if key not in ("offset", "length")}
        if fields(row) != fields(after.by_id[ident]) or body_bytes(before, old_blob, ident) != body_bytes(after, new_blob, ident):
            raise ValueError("Unrelated complete metadata or physical body changed")
        unrelated += 1
    lineage = read(checked(spec["protectedLineage"]))
    if any(row["id"] in changed for row in lineage["bindings"]):
        raise ValueError("Joint diagnostic changes an accepted protected body")
    protection = protected_bodies(after, new_blob, lineage)
    individual = {}
    for reference in spec["individualStages"]:
        item = read(checked(reference))
        check_tree(item)
        if len(item["changedIds"]) != 1 or item["changedIds"][0] in individual:
            raise ValueError("Unique complete individual stage required")
        ident = item["changedIds"][0]
        replay = PackedGpsReplay(checked(item["stagedMetadata"]), checked(item["stagedBinary"]))
        blob = checked(item["stagedBinary"]).read_bytes()
        if body_bytes(replay, blob, ident) != body_bytes(after, new_blob, ident):
            raise ValueError("Joint source body differs physically from original individual stage")
        individual[ident] = reference
    if set(individual) != changed:
        raise ValueError("Incomplete independent original individual body coverage")
    result = {"status": "JOINT_NATIVE_TOPOLOGY_DIAGNOSTIC_ALL_ORIGINAL_BODIES_PRESERVED",
        "manifest": pin(args.manifest), "changedIds": sorted(changed), "individualStages": individual,
        "unrelatedPhysicalBodiesAndCompleteMetadataPreserved": unrelated, "protected323": protection,
        "oldIncidentConflicts": stage["oldIncidentConflicts"], "newIncidentConflicts": stage["newIncidentConflicts"],
        "conflictsDiscardedOrClipped": False, "separateSheetIntersectionsProveOfficialOverlap": False,
        "actualJointGpsOrJvmExecuted": False, "sourceScopeAccepted": False, "independentQaPassed": False,
        "currentAssetsChanged": False, "credit": 0}
    ref = write_new(args.output, result)
    print({"report": ref, "changed": len(changed), "unrelatedPreserved": unrelated,
        "oldConflicts": len(stage["oldIncidentConflicts"]), "newConflicts": len(stage["newIncidentConflicts"]), "credit": 0})


if __name__ == "__main__":
    main()
