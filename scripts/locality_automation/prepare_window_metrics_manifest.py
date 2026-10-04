"""Prepare a pinned administrative window audit after validation is paused.

Reuse summarize_work_window.py unchanged. Preserve incomplete receipts
separately; never infer their finish times or successful validation credit.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import pin, read
from scripts.locality_automation.audit_prepared_osm_graphs import write_new
from scripts.locality_automation.work_window_guard import parse_utc


def category(receipt):
    argv = receipt.get("argv", [])
    if argv and Path(argv[0]).stem.lower() == "java":
        return "actual-jvm"
    scripts = [Path(item).stem.lower() for item in argv if item.lower().endswith(".py")]
    name = scripts[0] if scripts else " ".join(argv).lower()
    if "unittest" in name or "test_" in name:
        return "helper-verification"
    if "ordinary_edge" in name:
        return "frozen-edge-oracle-review"
    if "benchmark" in name or "durable" in name:
        return "helper-efficiency-verification"
    if "phase_activity" in name:
        return "report-activity"
    if "publisher" in name or "publication" in name:
        return "report-and-map-publication"
    if "gps" in name:
        return "whole-source-numeric-gps"
    if "jvm" in name:
        return "jvm-preparation-and-join"
    if any(term in name for term in ("native", "neighbor", "topology", "boundary_patch", "osm_graph")):
        return "source-and-topology-diagnosis"
    return "other-guarded-phase"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--control", required=True, type=Path)
    parser.add_argument("--window-start", required=True, type=Path)
    parser.add_argument("--baseline", required=True, type=Path)
    parser.add_argument("--publication", required=True, type=Path)
    parser.add_argument("--receipts-directory", required=True, type=Path)
    parser.add_argument("--snapshot", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    start, control = read(args.window_start), read(args.control)
    deadline = parse_utc(start["deadlineUtc"])
    if (control["phase"] != "paused" or datetime.now(timezone.utc) < deadline
            or parse_utc(control["windowStartUtc"]) != parse_utc(start["atUtc"])
            or parse_utc(control["deadlineUtc"]) != deadline):
        raise ValueError("Exact completed window must be paused before administrative audit")
    frozen_control = args.output.with_name(args.output.stem + ".paused-control.json")
    if args.output.exists() or args.snapshot.exists() or frozen_control.exists():
        raise ValueError("Preserve previous audit inputs; choose fresh paths")
    original_publication = pin(args.publication)
    payload = args.publication.read_bytes()
    with args.snapshot.open("xb") as stream:
        stream.write(payload)
        stream.flush()
        os.fsync(stream.fileno())
    if pin(args.publication) != original_publication or pin(args.snapshot)["sha256"] != original_publication["sha256"]:
        raise ValueError("Publication changed during exact snapshot")
    control_reference = pin(args.control)
    with frozen_control.open("xb") as stream:
        stream.write(args.control.read_bytes())
        stream.flush()
        os.fsync(stream.fileno())
    if pin(args.control) != control_reference or pin(frozen_control)["sha256"] != control_reference["sha256"]:
        raise ValueError("Paused control changed during exact snapshot")
    phases, incomplete = [], []
    for path in sorted(args.receipts_directory.rglob("*.execution.json")):
        body = read(path)
        if "argv" not in body or "hardDeadlineUtc" not in body:
            raise ValueError("Unrecognized original guard receipt: " + str(path))
        if parse_utc(body["hardDeadlineUtc"]) != deadline:
            raise ValueError("Receipt belongs to a different work window")
        reference = pin(path)
        if body["status"] == "RUNNING":
            incomplete.append({"receipt": reference, "status": body["status"], "processId": body.get("processId"),
                "startedAtUtc": body.get("startedAtUtc"), "finishTimeInferred": False})
        else:
            phases.append({"receipt": reference, "category": category(body)})
    result = {"windowStart": pin(args.window_start), "baselinePublication": pin(args.baseline),
        "publication": pin(args.snapshot), "originalPublicationAtSnapshot": original_publication,
        "phaseReceipts": phases, "incompletePreservedReceipts": incomplete, "pausedControlAtAudit": pin(frozen_control),
        "qualification": "Administrative audit only. All terminal original attempts are included, successful or failed. Incomplete receipts remain separate without fabricated finish times. Categories describe observed commands, not acceptance or LLM attention."}
    reference = write_new(args.output, result)
    print(json.dumps({"manifest": reference, "terminalReceipts": len(phases), "incompletePreserved": len(incomplete)}))


if __name__ == "__main__":
    main()
