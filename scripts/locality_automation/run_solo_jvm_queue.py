"""Supervise finite solo stage/JVM/join leaf phases as numeric reports arrive.

All children are synchronous and owned by the unchanged original UTC guard.
This coordinator supplies no independent reviewer, source scope or acceptance.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import active_control, checked, pin, read
from scripts.locality_automation.audit_prepared_osm_graphs import write_new
from scripts.locality_automation.work_window_guard import launch_guarded, parse_utc


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    spec = read(args.manifest)
    control = active_control(spec)
    base = read(checked(spec["baseStageManifest"]))
    stage_helper, join_helper = checked(spec["stageHelper"]), checked(spec["joinHelper"])
    if (stage_helper.resolve() != ROOT / "scripts/locality_automation/stage_solo_jvm_audit.py"
            or join_helper.resolve() != ROOT / "scripts/locality_automation/verify_solo_jvm_audit.py"):
        raise ValueError("Unexpected leaf helpers")
    items = spec["items"]
    codes = [item["code"] for item in items]
    if (not items or len(codes) != len(set(codes))
            or not set(codes) <= set(control["approvedCycle15PreparationPool"])
            or args.output.exists() or spec.get("credit") != 0):
        raise ValueError("Fresh finite diagnostic-only queue required")
    args.output.mkdir()
    state = {"status": "RUNNING_SOLO_JVM_QUEUE", "manifest": pin(args.manifest),
             "coordinatorPid": os.getpid(), "completed": [], "failed": [], "credit": 0,
             "startedAtUtc": datetime.now(timezone.utc).isoformat()}
    def checkpoint():
        temporary = args.output / "queue-state.tmp"
        with temporary.open("w", encoding="utf-8") as stream:
            json.dump(state, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, args.output / "queue-state.json")
    def phase(name, argv, directory):
        live = active_control(spec)
        receipt = directory / (name + ".execution.json")
        result = launch_guarded(live["safeSourceQaStartUtc"], live["deadlineUtc"], argv,
                                minimum_remaining_seconds=120, record=receipt)
        if result["status"] != "COMPLETED" or result["exitCode"] != 0:
            state["failed"].append({"phase": name, "code": directory.name, "receipt": pin(receipt)})
            state["status"] = "STOPPED_ON_PRESERVED_FAILED_PHASE"
            checkpoint()
            return False
        return True
    checkpoint()
    try:
        for item in items:
            code, model_path = item["code"], Path(item["modelPath"])
            while not model_path.exists():
                live = active_control(spec)
                if datetime.now(timezone.utc) >= parse_utc(live["safeSourceQaStartUtc"]):
                    state["status"] = "STOPPED_AT_SOURCE_START_CUTOFF"
                    checkpoint()
                    return
                time.sleep(1)
            live = active_control(spec)
            checked(spec["stageHelper"])
            checked(spec["joinHelper"])
            model = read(model_path)
            if (model["officialCode"] != code or model["credit"] != 0
                    or model["independentQaPassed"] is not False
                    or model["indexedExhaustiveFailures"] or model["independentFormulaDisagreements"]):
                state["failed"].append({"code": code, "model": pin(model_path), "phase": "numeric_hold"})
                state["status"] = "STOPPED_ON_PRESERVED_NUMERIC_HOLD"
                checkpoint()
                return
            directory = args.output / code
            directory.mkdir()
            stage = {**base, "code": code, "model": pin(model_path)}
            stage_path = directory / "stage-manifest.json"
            write_new(stage_path, stage)
            stage_out = directory / "jvm"
            command = [spec["python"], "-X", "utf8", "-B", str(stage_helper),
                       "--manifest", str(stage_path), "--output", str(stage_out)]
            if not phase("stage", command, directory):
                return
            binding_path = stage_out / "jvm-input-binding.json"
            binding = read(binding_path)
            if not phase("java", binding["argv"], directory):
                return
            actual_path = Path(binding["resultPath"])
            join = {key: spec[key] for key in ("control", "iteration", "owner", "windowStartUtc", "deadlineUtc", "credit")}
            join.update(binding=pin(binding_path), javaExecution=pin(directory / "java.execution.json"), actualJvm=pin(actual_path))
            join_path = directory / "join-manifest.json"
            write_new(join_path, join)
            joined = directory / "joined.json"
            command = [spec["python"], "-X", "utf8", "-B", str(join_helper),
                       "--manifest", str(join_path), "--output", str(joined)]
            if not phase("join", command, directory):
                return
            report = read(joined)
            if report["failures"] or report["credit"] != 0 or report["independentQaPassed"] is not False:
                state["failed"].append({"phase": "join_hold", "code": code, "report": pin(joined)})
                state["status"] = "STOPPED_ON_PRESERVED_JVM_HOLD"
                checkpoint()
                return
            state["completed"].append({"code": code, "report": pin(joined),
                                        "comparisons": report["wholeCohortComparisons"]})
            checkpoint()
            print(json.dumps({"code": code, "jvmQueueCompleted": len(state["completed"]),
                              "queueTotal": len(items), "credit": 0}), flush=True)
        state["status"] = "FINITE_SOLO_JVM_QUEUE_COMPLETE"
    except BaseException as error:
        state["status"] = "INTERRUPTED_OR_FAILED_SOLO_JVM_QUEUE"
        state["error"] = type(error).__name__ + ": " + str(error)
        raise
    finally:
        state["finishedAtUtc"] = datetime.now(timezone.utc).isoformat()
        checkpoint()
        print(json.dumps({"status": state["status"], "complete": len(state["completed"]), "credit": 0}), flush=True)


if __name__ == "__main__":
    main()
