"""Review completed frozen shadow cohorts without repeated LLM dispatch.

The root coordinator waits for exact finite queue outputs and launches only
original-guarded direct mathematical review leaves. Original disagreement
journals and source/neighbor holds remain untouched; this supplies no acceptance.
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
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree
from scripts.locality_automation.work_window_guard import launch_guarded, parse_utc

LIVE_STATES = {"PREPARING_FINITE_UNACCEPTED_SHADOWS",
    "WAITING_FOR_EXACT_FULL_REPLAY_DURABILITY_VERIFICATION",
    "RUNNING_FINITE_SOLO_SHADOW_TECHNICAL_QUEUE"}


def validate_completed(code, declaration, model, joined):
    if (declaration["code"] != code or model["officialCode"] != code
            or model["probeCount"] != declaration["probes"]
            or declaration["geographicCredit"] != 0
            or model["indexedExhaustiveFailures"] or joined["failures"]
            or any(item["credit"] != 0 or item["independentQaPassed"] is not False
                or item["sourceScopeAccepted"] is not False for item in (model, joined))
            or model["liveAssetsChanged"] is not False
            or len(model["independentFormulaDisagreements"]) != declaration["originalOracleDisagreements"]):
        raise ValueError("Incomplete or relabeled diagnostic cohort")


def validate_stage(stage, model, spec):
    # Original stager preserves byte-identical before-catalog snapshots at new
    # paths. Both physical files have already passed their own exact hash pins.
    if stage["changedIds"] != [model["id"]] or any(
        stage["before" + kind]["sha256"] != spec["baseline" + kind]["sha256"]
        for kind in ("Metadata", "Binary")):
        raise ValueError("Single named shadow or exact baseline bytes differ")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    spec = read(args.manifest)
    ctl = active_control(spec)
    check_tree(spec)
    queue_spec = read(checked(spec["sourceQueueManifest"]))
    check_tree(queue_spec)
    all_codes = [item["code"] for item in queue_spec["items"]]
    codes = spec["codes"]
    if (args.output.exists() or spec.get("credit") != 0 or not codes
            or len(codes) != len(set(codes)) or not set(codes).issubset(all_codes)
            or not set(codes).issubset(ctl["approvedReconciliationPool"])):
        raise ValueError("Fresh exact finite review pool required")
    helper = checked(spec["helper"])
    if helper.resolve() != ROOT / "scripts/locality_automation/verify_ordinary_edge_oracle.py":
        raise ValueError("Unexpected review leaf")
    template = read(checked(spec["template"]))
    check_tree(template)
    if template["verifier"] != spec["helper"] or template["credit"] != 0:
        raise ValueError("Pinned original mathematical review template required")
    args.output.mkdir()
    state = {"status": "WAITING_FOR_FINITE_FROZEN_SHADOW_COHORTS", "manifest": pin(args.manifest),
        "startedAtUtc": datetime.now(timezone.utc).isoformat(), "coordinatorPid": os.getpid(),
        "completed": [], "pending": list(codes), "credit": 0}
    def checkpoint():
        temporary = args.output / "edge-queue-state.tmp"
        with temporary.open("w", encoding="utf-8") as stream:
            json.dump(state, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, args.output / "edge-queue-state.json")
    checkpoint()
    try:
        for code in codes:
            while True:
                live = active_control(spec)
                if datetime.now(timezone.utc) >= parse_utc(live["safeSourceQaStartUtc"]):
                    state["status"] = "STOPPED_AT_SOURCE_START_CUTOFF_WITH_PENDING_PRESERVED"
                    return
                queue = read(Path(spec["queueStatePath"]))
                if queue["manifest"] != spec["sourceQueueManifest"] or queue["credit"] != 0:
                    raise ValueError("Exact observed producer changed")
                matches = [row for row in queue["completed"] if row["code"] == code]
                if len(matches) > 1:
                    raise ValueError("Duplicate completed code")
                if matches:
                    break
                if queue["status"] not in LIVE_STATES:
                    state["status"] = "STOPPED_WITH_PRODUCER_PENDING_OR_FAILURE_PRESERVED"
                    return
                time.sleep(1)
            declaration = matches[0]
            model = read(checked(declaration["model"]))
            joined = read(checked(declaration["joined"]))
            validate_completed(code, declaration, model, joined)
            prepared = [row for row in queue["prepared"] if row["code"] == code]
            if len(prepared) != 1:
                raise ValueError("Unique immutable catalog preparation required")
            stage = read(checked(prepared[0]["staged"]))
            check_tree(stage)
            if stage["status"] != "STAGED_REQUIRES_GEOGRAPHIC_AND_GPS_REVIEW":
                raise ValueError("Original stage status differs")
            validate_stage(stage, model, spec)
            review = {**template, "cohorts": [
                {"name": code + "-live-before", "metadata": stage["beforeMetadata"],
                    "binary": stage["beforeBinary"], "journals": [model["beforeJournal"]]},
                {"name": code + "-isolated-shadow-after", "metadata": stage["stagedMetadata"],
                    "binary": stage["stagedBinary"], "journals": [model["journal"]]}],
                "originalCompletedModel": declaration["model"], "originalActualJvm": declaration["joined"]}
            directory = args.output / code
            directory.mkdir()
            manifest = directory / "review-manifest.json"
            write_new(manifest, review)
            state.update(status="REVIEWING_FROZEN_ORDINARY_EDGE_COHORT", currentCode=code)
            checkpoint()
            execution = directory / "review.execution.json"
            check_tree(spec)
            live = active_control(spec)
            result = launch_guarded(live["safeSourceQaStartUtc"], live["deadlineUtc"],
                [spec["python"], "-X", "utf8", "-B", str(helper), "--manifest", str(manifest),
                    "--output", str(directory / "review")], minimum_remaining_seconds=120, record=execution)
            if result["status"] != "COMPLETED" or result["exitCode"] != 0:
                state.update(status="STOPPED_ON_PRESERVED_REVIEW_PHASE_FAILURE", execution=pin(execution))
                return
            report_path = directory / "review/report.json"
            report = read(report_path)
            if report["credit"] != 0 or not report["allOriginalArtifactsPreserved"]:
                raise ValueError("Mathematical review incorrectly claimed authority")
            state["completed"].append({"code": code, "report": pin(report_path), "execution": pin(execution),
                "status": report["status"], "counts": report["counts"], "failures": len(report["failures"]),
                "changes": len(report["changes"]), "credit": 0})
            state["pending"].remove(code)
            checkpoint()
            print(json.dumps({"code": code, "completed": len(state["completed"]), "credit": 0}), flush=True)
        state["status"] = "FINITE_SHADOW_EDGE_REVIEW_COMPLETE_NO_ACCEPTANCE"
    except BaseException as error:
        state.update(status="INTERRUPTED_OR_FAILED_EDGE_REVIEW_QUEUE", error=type(error).__name__ + ": " + str(error))
        raise
    finally:
        state["finishedAtUtc"] = datetime.now(timezone.utc).isoformat()
        checkpoint()
        print(json.dumps({"status": state["status"], "completed": len(state["completed"]), "credit": 0}), flush=True)


if __name__ == "__main__":
    main()
