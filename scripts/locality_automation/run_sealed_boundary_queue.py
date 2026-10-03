"""Sequential admission of a finite, pinned queue through the unchanged recorder.

This orchestrates existing leaf programs. It does not interpret sources, alter
geometry, replace acceptance gates, or grant itself new source/QA authority.
Every child is owned synchronously by the original UTC work-window guard.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import sys

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))
from scripts.locality_automation.work_window_guard import launch_guarded


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def checked(ref):
    path = Path(ref["file"])
    if sha(path) != ref["sha256"]:
        raise ValueError("Pinned input changed: " + str(path))
    if "bytes" in ref and path.stat().st_size != ref["bytes"]:
        raise ValueError("Pinned input size changed: " + str(path))
    return path


def pin(path):
    return {"file": str(path), "sha256": sha(path)}


def active_control(manifest):
    control = read(manifest["control"])
    if (control.get("phase") != "working"
            or control.get("iteration") != manifest["iteration"]
            or control.get("acceptanceOwner") != manifest["owner"]
            or control.get("acceptanceActor") != manifest["owner"]
            or control.get("subagentsAllowed") is not False
            or control.get("windowStartUtc") != manifest["windowStartUtc"]
            or control.get("deadlineUtc") != manifest["deadlineUtc"]):
        raise ValueError("Current owner/window/control differs from released manifest")
    return control


def verify_preflight(value, item, manifest):
    if (value.get("status") != "PASS_READ_ONLY_EXACT_CAF_PRE_RECORD_PREFIX"
            or value.get("codes") != [item["code"]]
            or value.get("report") != {k: item["report"][k] for k in ("file", "sha256")}
            or value.get("recorder") != manifest["recorder"]
            or value.get("ledgerWrites") is not False
            or value.get("assetsUnchanged") is not True):
        raise ValueError("Exact preflight output contract did not pass")
    for reference in value["ledgers"].values():
        checked(reference)
    checked(value["executionOwnerControl"])
    for reference in manifest["assets"]:
        checked(reference)


def verify_linked_acceptance(item, manifest, output):
    receipt_path = output / "current-validation-receipt.json"
    receipt_reference = pin(receipt_path)
    receipt = read(receipt_path)
    if (receipt.get("status") != "VALIDATED_CURRENT_SOURCE_GEOMETRY"
            or receipt.get("boundaryLocalityCodes") != [item["code"]]
            or receipt.get("fullSourceBoundaryLocalityCodes") != [item["code"]]
            or receipt.get("scopedBoundaryLocalityCodes") != []
            or receipt.get("boundaryScopeRecorded") is not True
            or receipt.get("assetChanges") is not False):
        raise ValueError("Acceptance receipt is not exactly the released full-body NoOp")
    for key in ("run", "handoff"):
        links = read(manifest[key]).get("currentGeometryValidations", [])
        if sum(link == receipt_reference for link in links) != 1:
            raise ValueError("Receipt not linked exactly once in " + key)
    frozen = output / "independent-gps-report.json"
    if sha(frozen) != item["report"]["sha256"]:
        raise ValueError("Recorded independent evidence differs from released report")
    for reference in manifest["assets"]:
        checked(reference)
    return receipt_reference


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    manifest = read(args.manifest)
    work = Path(manifest["outputDirectory"])
    if not work.is_dir() or Path.cwd().resolve() != Path(manifest["cwd"]).resolve():
        raise ValueError("Existing producer cwd/output directory required")
    for name in ("guard", "preflight", "recorder"):
        checked(manifest[name])
    codes = [item["code"] for item in manifest["items"]]
    if not codes or len(codes) != len(set(codes)):
        raise ValueError("Finite queue must contain distinct codes")
    control = active_control(manifest)
    if not set(codes) <= set(control["sealedPendingIntakeCodes"]):
        raise ValueError("Queue contains codes outside explicit sealed backlog")
    for item in manifest["items"]:
        report = read(checked(item["report"]))
        if (report.get("status") != "PASS_OFFLINE_CURRENT_SOURCE_GPS"
                or report.get("structuralFailures") != [] or report.get("probeFailures") != []
                or set(report.get("decodedCurrentChecks", {})) != {item["code"]}):
            raise ValueError("Sealed independent report does not pass finite-case contract")
    if args.dry_run:
        print(json.dumps({"status": "PASS_PINNED_QUEUE_DRY_RUN", "codes": codes,
                          "writes": False, "modelCalls": 0}))
        return
    lock_path = work / "queue-owner.lock"
    with lock_path.open("x", encoding="utf-8") as lock:
        lock.write(json.dumps({"pid": os.getpid(), "manifest": pin(args.manifest)}))
    summary = {"status": "RUNNING", "pid": os.getpid(), "manifest": pin(args.manifest),
               "startedAtUtc": datetime.now(timezone.utc).isoformat(), "completed": []}
    def save():
        temporary = work / "queue-progress.tmp"
        temporary.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
        os.replace(temporary, work / "queue-progress.json")
    def phase(argv, receipt):
        policy = active_control(manifest)
        for name in ("guard", "preflight", "recorder"):
            checked(manifest[name])
        result = launch_guarded(policy["safeIntakeStartUtc"], policy["deadlineUtc"], argv,
                                minimum_remaining_seconds=120, record=receipt)
        if result.get("status") != "COMPLETED" or result.get("exitCode") != 0:
            raise ValueError("Guarded phase failed; preserve receipt: " + str(receipt))
        return result
    try:
        save()
        for item in manifest["items"]:
            code = item["code"]
            output = work / (code + "-current-source-validation-v1")
            if output.exists():
                raise ValueError("Fresh record output required; reconcile existing output explicitly")
            pref = work / (code + "-preflight.json")
            prefix = [manifest["python"], "-X", "utf8", "-B"]
            first = phase(prefix + [manifest["preflight"]["file"], "--report", item["report"]["file"],
                          "--report-sha256", item["report"]["sha256"], "--code", code,
                          "--output", str(pref), "--planned-record-output", str(output)],
                          work / (code + "-preflight.execution.json"))
            verify_preflight(read(pref), item, manifest)
            second = phase(prefix + [manifest["recorder"]["file"], "--gps", item["report"]["file"],
                           "--run", manifest["run"], "--handoff", manifest["handoff"],
                           "--output", str(output), "--report-data", manifest["reportData"],
                           "--evidence-root", manifest["evidenceRoot"]],
                           work / (code + "-record.execution.json"))
            accepted = verify_linked_acceptance(item, manifest, output)
            summary["completed"].append({"code": code, "receipt": accepted,
                "preflight": pin(pref), "preflightFinishedAtUtc": first["finishedAtUtc"],
                "recordStartedAtUtc": second["startedAtUtc"], "recordFinishedAtUtc": second["finishedAtUtc"]})
            save()
            print(json.dumps({"status": "VERIFIED_FULL_BODY_ACCEPTANCE", "code": code,
                              "completed": len(summary["completed"])}), flush=True)
        summary["status"] = "COMPLETED"
    except BaseException as exc:
        summary.update(status="STOPPED", error=str(exc))
        raise
    finally:
        summary["finishedAtUtc"] = datetime.now(timezone.utc).isoformat()
        save()
        lock_path.unlink()


if __name__ == "__main__":
    main()
