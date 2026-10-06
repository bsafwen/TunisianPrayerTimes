"""Verify isolated vector math against an exact complete before/after GPS replay.

Original cohorts, bytes, comparisons, disagreements, protection and metadata
facts must all match. This does not automatically adopt a helper or acceptance.
"""
import argparse
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import active_control, checked, pin, read
from scripts.locality_automation.audit_prepared_osm_graphs import write_new
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree
from scripts.locality_automation.summarize_solo_locality_audits import successful_receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    spec = read(args.manifest)
    ctl = active_control(spec)
    if args.output.exists() or spec.get("credit") != 0:
        raise ValueError("Fresh zero-credit full replay comparison required")
    check_tree(spec)
    before, after = (read(checked(spec[key])) for key in ("original", "revised"))
    if (after.get("membershipStrategy") != "EXPERIMENTAL_EXACT_OPERATION_VECTOR_RING_NO_AUTOMATIC_ADOPTION"
            or after.get("vectorMembership") != spec["vectorMembership"]
            or after.get("originalPackedDecoder") != spec["originalPackedDecoder"]):
        raise ValueError("Exact experimental ring primitive and original decoder required")
    for row in (before, after):
        if (row["status"] != "SOLO_SHADOW_WHOLE_SOURCE_GPS_DIAGNOSTIC_NO_INSTALLATION"
                or row["credit"] != 0 or row["independentQaPassed"] is not False
                or row["sourceScopeAccepted"] is not False or row["liveAssetsChanged"] is not False
                or row["ledgerWrites"] is not False):
            raise ValueError("Technical replay cannot assert acceptance or mutation")
    fields = ("officialCode", "id", "probeCount", "counts", "indexedExhaustiveFailures",
              "independentFormulaDisagreements", "behaviorChangeCount", "currentPeers")
    for key in fields:
        if before[key] != after[key]:
            raise ValueError("Original complete replay facts differ: " + key)
    bytes_equal = []
    for key in ("probes", "actualJvmRequests", "journal", "beforeJournal", "behaviorChanges"):
        original, revised = checked(before[key]), checked(after[key])
        if before[key]["sha256"] != after[key]["sha256"] or original.read_bytes() != revised.read_bytes():
            raise ValueError("Original full ordered bytes differ: " + key)
        bytes_equal.append(key)
    old_gate, new_gate = read(checked(before["inputGate"])), read(checked(after["inputGate"]))
    for key in ("protected323", "unrelatedBodies", "independentQaPassed", "sourceScopeAccepted", "credit"):
        if old_gate[key] != new_gate[key]:
            raise ValueError("Original full protection or authority differs: " + key)
    if after["durabilityBarriers"] != after["probeCount"] * 2:
        raise ValueError("Every complete probe requires a barrier in both journals")
    old_receipt, old_seconds = successful_receipt(spec["originalExecution"], ctl)
    new_receipt, new_seconds = successful_receipt(spec["revisedExecution"], ctl)
    if old_receipt["processId"] != before["payloadPid"] or new_receipt["processId"] != after["payloadPid"]:
        raise ValueError("Original direct leaf provenance differs")
    check_tree(spec)
    result = {"status": "VERIFIED_BYTE_IDENTICAL_FULL_SHADOW_GPS_VECTOR_REPLAY_NO_AUTOMATIC_ADOPTION", "manifest": pin(args.manifest),
        "helper": spec["helper"], "original": spec["original"], "revised": spec["revised"],
        "originalExecution": spec["originalExecution"], "revisedExecution": spec["revisedExecution"],
        "probeCount": after["probeCount"], "comparisonsPerCatalog": after["counts"]["afterComparisons"],
        "allByteIdenticalArtifacts": bytes_equal, "originalProtectionAndDisagreementsPreserved": True,
        "seconds": {"original": old_seconds, "revised": new_seconds}, "singleReplayElapsedRatio": old_seconds / new_seconds,
        "timingQualification": "Single full replay with different concurrent process spans. No controlled steady-state whole-engine or geographic throughput speedup claimed.",
        "vectorMembership": spec["vectorMembership"], "originalPackedDecoder": spec["originalPackedDecoder"],
        "automaticAdoption": False,
        "sourceScopeAccepted": False, "independentQaPassed": False, "credit": 0}
    write_new(args.output, result)
    print({"status": result["status"], "byteIdenticalArtifacts": len(bytes_equal), "seconds": result["seconds"], "credit": 0})


if __name__ == "__main__":
    main()
