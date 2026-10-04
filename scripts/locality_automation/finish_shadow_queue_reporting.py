"""Supervise one original-guarded publication of finite completed shadow tasks.

Wait for this exact queue or the recorded intake cutoff, retain partial work,
and publish only actual successful leaf clocks. Qualified counts stay frozen.
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    spec = read(args.manifest)
    ctl = active_control(spec)
    check_tree(spec)
    if args.output.exists() or spec.get("credit") != 0:
        raise ValueError("Fresh sole-root report coordination required")
    args.output.mkdir()
    queue_path = Path(spec["queueStatePath"])
    state = {"status": "WAITING_FOR_FINITE_SHADOW_QUEUE_OR_INTAKE_CUTOFF", "coordinatorPid": os.getpid(),
        "manifest": pin(args.manifest), "startedAtUtc": datetime.now(timezone.utc).isoformat(), "credit": 0}
    def checkpoint():
        temp = args.output / "reporting-state.tmp"
        with temp.open("w", encoding="utf-8") as stream:
            json.dump(state, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp, args.output / "reporting-state.json")
    def phase(name, helper_key, argv):
        live = active_control(spec)
        checked(spec[helper_key])
        receipt = args.output / (name + ".execution.json")
        result = launch_guarded(live["safeMapStartUtc"], live["deadlineUtc"],
            [spec["python"], "-X", "utf8", "-B", str(checked(spec[helper_key])), *argv],
            minimum_remaining_seconds=420, record=receipt)
        state[name + "Execution"] = pin(receipt)
        checkpoint()
        if result["status"] != "COMPLETED" or result["exitCode"] != 0:
            raise ValueError("Preserved failed reporting phase: " + name)
    checkpoint()
    try:
        while True:
            ctl = active_control(spec)
            queue = read(queue_path) if queue_path.is_file() else None
            terminal = queue is not None and queue["status"] not in (
                "PREPARING_FINITE_UNACCEPTED_SHADOWS", "WAITING_FOR_EXACT_FULL_REPLAY_DURABILITY_VERIFICATION",
                "RUNNING_FINITE_SOLO_SHADOW_TECHNICAL_QUEUE")
            if terminal or datetime.now(timezone.utc) >= parse_utc(ctl["safeIntakeStartUtc"]):
                break
            time.sleep(1)
        if queue is None:
            raise ValueError("No exact finite queue evidence exists")
        phases = list(spec["additionalPhases"])
        queue_dir = queue_path.parent
        for row in queue["completed"]:
            model = read(checked(row["model"]))
            joined = read(checked(row["joined"]))
            if (model["credit"] != 0 or joined["credit"] != 0 or joined["failures"]
                    or model["sourceScopeAccepted"] is not False or joined["sourceScopeAccepted"] is not False
                    or model["independentQaPassed"] is not False or joined["independentQaPassed"] is not False):
                raise ValueError("Completed diagnostic cannot become source or independent acceptance")
            code = row["code"]
            for kind, receipt, title in (
                ("gps", queue_dir / code / "gps.execution.json", "Whole-source before/after GPS technical check"),
                ("jvm", queue_dir / code / "java.execution.json", "Actual isolated JVM GPS, source and persistence check")):
                phases.append({"taskId": f"cycle{spec['iteration']}-{code}-shadow-{kind}", "title": title,
                    "locationCodes": [code], "execution": pin(receipt), "endAction": "complete",
                    "detail": "Complete isolated native-source cohort tested; original disagreements and neighbor overlaps retained. Current validated map and app assets remain unchanged. Solo technical preparation, zero accepted-location credit."})
        activity = {"control": spec["control"], "iteration": spec["iteration"], "app": spec["app"],
            "events": pin(checked(spec["eventsAtLaunch"])), "phases": phases}
        activity_path = args.output / "activity-manifest.json"
        write_new(activity_path, activity)
        activity_out = args.output / "activity.json"
        phase("activity", "activityHelper", ["--manifest", str(activity_path), "--output", str(activity_out)])
        publication = read(checked(spec["publicationTemplate"]))
        publication["iteration"] = spec["iteration"]
        for reference in publication["liveEvidence"]:
            reference["sha256"] = pin(reference["file"])["sha256"]
        pub_path = args.output / "publication-manifest.json"
        write_new(pub_path, publication)
        pub_out = args.output / "publication.json"
        phase("publication", "publisherHelper", ["--manifest", str(pub_path), "--receipt", str(pub_out)])
        public = checked(spec["app"]).parent
        verification = {"baselineReport": spec["baselineReport"], "baselineMap": spec["baselineMap"],
            "report": pin(public / "task-report.json"), "map": pin(public / "boundary-map.json"), "activity": pin(activity_out)}
        verify_path = args.output / "verification-manifest.json"
        write_new(verify_path, verification)
        proof = args.output / "verification.json"
        phase("verification", "verificationHelper", ["--manifest", str(verify_path), "--output", str(proof)])
        state.update(status="PUBLISHED_AND_VERIFIED_ZERO_GAIN_SHADOW_TASK_ACTIVITY", proof=pin(proof),
            completedLocations=len(queue["completed"]), originalQueueStatus=queue["status"],
            pendingOrHeldWorkPreserved=True, geographicCredit=0, privateKeyRead=False)
    except BaseException as error:
        state["status"] = "INTERRUPTED_OR_FAILED_REPORT_COORDINATION"
        state["error"] = type(error).__name__ + ": " + str(error)
        raise
    finally:
        state["finishedAtUtc"] = datetime.now(timezone.utc).isoformat()
        checkpoint()
        print(json.dumps({"status": state["status"], "completedLocations": state.get("completedLocations"), "credit": 0}), flush=True)


if __name__ == "__main__":
    main()
