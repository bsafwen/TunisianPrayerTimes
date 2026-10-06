"""Group every pinned native-shadow conflict before expensive GPS/JVM work.

This is dispatch evidence only. Components identify bodies requiring coherent
source review, including protected peers; they do not authorize edits or decide
ownership. Original conflict arrays remain complete and unchanged.
"""
import argparse
from datetime import datetime, timezone
import math
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.audit_prepared_osm_graphs import write_new
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree
from scripts.locality_automation.packed_gps_replay import PackedGpsReplay
from scripts.locality_automation.run_sealed_boundary_queue import active_control, checked, pin, read
from scripts.locality_automation.run_solo_source_gps_audit import protected_bodies
from scripts.locality_automation.work_window_guard import parse_utc


def edges(values, known):
    result = {}
    for value in values:
        ids = value["ids"]
        area = value["intersectionKm2"]
        if (len(ids) != 2 or len(set(ids)) != 2 or not set(ids) <= known
                or isinstance(area, bool) or not isinstance(area, (int, float))
                or not math.isfinite(area) or area < 0):
            raise ValueError("Complete distinct known conflict identities and finite raw area required")
        key = tuple(sorted(ids))
        if key in result:
            raise ValueError("Duplicate conflict pair; do not silently discard original evidence")
        result[key] = value
    return result


def group_conflicts(old_values, new_values, metadata, changed, protected):
    known = set(metadata)
    if not changed <= known or not protected <= known or not changed:
        raise ValueError("Complete metadata identities and nonempty changed scope required")
    old, new = edges(old_values, known), edges(new_values, known)
    adjacency = {ident: set() for ident in changed}
    for pair in new:
        if not set(pair) & changed:
            raise ValueError("Incident conflict outside original changed scope")
        a, b = pair
        adjacency.setdefault(a, set()).add(b)
        adjacency.setdefault(b, set()).add(a)
    visited, components = set(), []
    for first in sorted(adjacency):
        if first in visited:
            continue
        pending, members = [first], set()
        while pending:
            current = pending.pop()
            if current in members:
                continue
            members.add(current)
            pending.extend(adjacency[current] - members)
        visited.update(members)
        raw = [value for pair, value in new.items() if set(pair) <= members]
        introduced = [value for pair, value in new.items() if set(pair) <= members and pair not in old]
        increased = [value for pair, value in new.items() if set(pair) <= members and pair in old
                     and value["intersectionKm2"] > old[pair]["intersectionKm2"]]
        components.append({"ids": sorted(members), "changedIds": sorted(members & changed),
            "unchangedPeerIds": sorted(members - changed), "protectedPeerIds": sorted(members & protected),
            "names": [{"id": ident, "name": metadata[ident]["name"], "parentName": metadata[ident].get("parentName")}
                      for ident in sorted(members)],
            "rawNewIncidentConflicts": raw, "introducedPairs": introduced, "increasedPairs": increased,
            "requiresCoherentSourceReview": bool(raw),
            "expensiveAcceptanceDispatchAllowed": False, "editsAuthorized": False, "credit": 0})
    return components


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    spec = read(args.manifest)
    ctl = active_control(spec)
    if args.output.exists() or spec.get("credit") != 0 or datetime.now(timezone.utc) >= parse_utc(ctl["safeSourceQaStartUtc"]):
        raise ValueError("Fresh zero-credit current-window plan required")
    check_tree(spec)
    topology = read(checked(spec["topology"]))
    if (topology["status"] != "JOINT_NATIVE_TOPOLOGY_DIAGNOSTIC_ALL_ORIGINAL_BODIES_PRESERVED"
            or topology["credit"] != 0 or topology["conflictsDiscardedOrClipped"]
            or topology["currentAssetsChanged"] or topology["sourceScopeAccepted"]):
        raise ValueError("Only exact unchanged, unaccepted original topology evidence may be planned")
    stage = read(checked(spec["stage"]))
    check_tree(stage)
    if stage["beforeMetadata"] != spec["metadata"] or stage["beforeBinary"] != spec["binary"]:
        raise ValueError("Original staged baseline differs from current physical catalogue")
    for key in ("oldIncidentConflicts", "newIncidentConflicts", "changedIds"):
        if topology[key] != stage[key]:
            raise ValueError("Complete original topology arrays or changed scope differ")
    lineage = read(checked(spec["protectedLineage"]))
    replay = PackedGpsReplay(checked(spec["metadata"]), checked(spec["binary"]))
    protection = protected_bodies(replay, checked(spec["binary"]).read_bytes(), lineage)
    components = group_conflicts(topology["oldIncidentConflicts"], topology["newIncidentConflicts"],
        replay.by_id, set(topology["changedIds"]), {r["id"] for r in lineage["bindings"]})
    check_tree(spec)
    result = {"status": "FINITE_TOPOLOGY_RECONCILIATION_PLAN_NO_ACCEPTANCE", "manifest": pin(args.manifest),
        "completedAtUtc": datetime.now(timezone.utc).isoformat(), "components": components,
        "rawOldIncidentConflicts": topology["oldIncidentConflicts"],
        "rawNewIncidentConflicts": topology["newIncidentConflicts"], "protectedBodies": protection,
        "sourceScopeAccepted": False, "independentQaPassed": False, "assetsChanged": False,
        "expensiveAcceptanceDispatchAllowed": False, "credit": 0,
        "qualification": "Read-only connected review groups. No overlap summation, ownership inference, clipping, neighbor scope expansion or protection waiver. All source/native/identity/legal/neighbor/protection/full-scope/GPS/prayer/persistence/CAF+A4 gates remain."}
    ref = write_new(args.output, result)
    print({"report": ref, "groups": len(components), "involvedBodies": sum(len(c["ids"]) for c in components),
           "protectedPeers": sum(len(c["protectedPeerIds"]) for c in components), "credit": 0})


if __name__ == "__main__":
    main()
