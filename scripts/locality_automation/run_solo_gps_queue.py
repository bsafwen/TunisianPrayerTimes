"""Supervise a finite root-only diagnostic queue through original leaf guards.

No recorder, publisher, network acquisition, acceptance or detached children.
Stop at the first failing phase and preserve its original execution receipt.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import active_control, checked, pin, read
from scripts.locality_automation.work_window_guard import launch_guarded


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    spec = read(args.manifest)
    active_control(spec)
    phase_manifest = checked(spec["phaseManifest"])
    phase = checked(spec["phaseHelper"])
    guard = checked(spec["guard"])
    if phase.resolve() != ROOT / "scripts/locality_automation/run_solo_source_gps_audit.py":
        raise ValueError("Unexpected leaf phase")
    if guard.resolve() != ROOT / "scripts/locality_automation/work_window_guard.py":
        raise ValueError("Unexpected original guard")
    codes = spec["codes"]
    control = read(spec["control"])
    if not codes or len(codes) != len(set(codes)) or not set(codes) <= set(control["approvedCycle15PreparationPool"]):
        raise ValueError("Invalid finite prepared cohort")
    if args.output.exists():
        raise ValueError("Preserve earlier queue attempts")
    args.output.mkdir()
    state = {"status": "RUNNING_SOLO_DIAGNOSTIC_QUEUE", "manifest": pin(args.manifest),
             "coordinatorPid": os.getpid(), "completed": [], "failed": [],
             "startedAtUtc": datetime.now(timezone.utc).isoformat(), "credit": 0}
    def checkpoint():
        temporary = args.output / "queue-state.tmp"
        with temporary.open("w", encoding="utf-8") as stream:
            json.dump(state, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, args.output / "queue-state.json")
    checkpoint()
    for code in codes:
        control = active_control(spec)
        checked(spec["phaseManifest"])
        checked(spec["phaseHelper"])
        output = args.output / (code + "-gps")
        receipt = args.output / (code + ".execution.json")
        command = [spec["python"], "-X", "utf8", "-B", str(phase),
                   "--manifest", str(phase_manifest), "--code", code, "--output", str(output)]
        result = launch_guarded(control["safeSourceQaStartUtc"], control["deadlineUtc"], command,
                                minimum_remaining_seconds=120, record=receipt)
        if result["status"] != "COMPLETED" or result["exitCode"] != 0:
            state["failed"].append({"code": code, "execution": pin(receipt)})
            state["status"] = "STOPPED_ON_PRESERVED_FAILED_PHASE"
            checkpoint()
            break
        report = read(output / "report.json")
        if report.get("credit") != 0 or report.get("independentQaPassed") is not False or report.get("ledgerWrites") is not False:
            raise ValueError("Diagnostic phase unexpectedly asserts authority or mutation")
        state["completed"].append({"code": code, "execution": pin(receipt),
            "report": pin(output / "report.json"), "probes": report["probeCount"],
            "comparisons": report["counts"]["lookupComparisons"],
            "indexedExhaustiveFailures": len(report["indexedExhaustiveFailures"]),
            "oracleDisagreements": len(report["independentFormulaDisagreements"])})
        checkpoint()
        print(json.dumps({"queueCompleted": len(state["completed"]), "queueTotal": len(codes),
                          "code": code, "credit": 0}), flush=True)
    else:
        state["status"] = "FINITE_SOLO_DIAGNOSTIC_QUEUE_COMPLETE"
    state["finishedAtUtc"] = datetime.now(timezone.utc).isoformat()
    checkpoint()
    print(json.dumps({"status": state["status"], "complete": len(state["completed"]), "credit": 0}), flush=True)


if __name__ == "__main__":
    main()
