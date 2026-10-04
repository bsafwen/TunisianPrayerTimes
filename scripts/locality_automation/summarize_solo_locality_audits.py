"""Summarize completed finite solo GPS/JVM checks and propose actual task clocks.

Preparations never become validated locations here. This read-only leaf does
not append events, publish progress, modify app assets or confer acceptance.
"""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import active_control, checked, pin, read
from scripts.locality_automation.audit_prepared_osm_graphs import write_new
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree
from scripts.locality_automation.work_window_guard import parse_utc


def successful_receipt(ref, control):
    receipt = read(checked(ref))
    start, finish = parse_utc(receipt["startedAtUtc"]), parse_utc(receipt["finishedAtUtc"])
    if (receipt["status"] != "COMPLETED" or receipt["exitCode"] != 0 or receipt["timedOut"] is not False
            or not receipt.get("recordOwner") or receipt["processId"] <= 0
            or not parse_utc(control["windowStartUtc"]) <= start <= finish < parse_utc(control["deadlineUtc"])
            or parse_utc(receipt["hardDeadlineUtc"]) != parse_utc(control["deadlineUtc"])):
        raise ValueError("Original successful current-window leaf receipt required")
    return receipt, (finish - start).total_seconds()


def summarize(spec, control):
    cases = spec["cases"]
    if (len(cases) != len(control["approvedCycle15PreparationPool"])
            or {c["code"] for c in cases} != set(control["approvedCycle15PreparationPool"])):
        raise ValueError("Complete finite prepared13 scope required exactly once")
    rows, phases = [], []
    for item in cases:
        model = read(checked(item["model"]))
        joined = read(checked(item["jvm"]))
        code = item["code"]
        if (model["officialCode"] != code or joined["officialCode"] != code or model["id"] != joined["id"]
                or model["status"] != "SOLO_FULL_SOURCE_DRIVEN_GPS_AUDIT_REQUIRES_REVIEW"
                or joined["status"] != "PASS_SOLO_ACTUAL_CURRENT_JVM_COMPARE_NO_ACCEPTANCE"
                or model["indexedExhaustiveFailures"] or model["independentFormulaDisagreements"] or joined["failures"]
                or model["counts"]["lookupComparisons"] != model["probeCount"] * 4
                or joined["wholeCohortComparisons"] != model["counts"]["lookupComparisons"]):
            raise ValueError("Whole finite numeric/JVM evidence differs or has discrepancies")
        for evidence in (model, joined):
            if (evidence["sourceActor"] != "/root" or evidence["technicalAuditActor"] != "/root"
                    or evidence["independentQaPassed"] is not False or evidence["sourceScopeAccepted"] is not False
                    or evidence["credit"] != 0 or evidence["ledgerWrites"] is not False):
                raise ValueError("Solo preparation cannot be promoted to source or independent QA acceptance")
        numeric_receipt, numeric_seconds = successful_receipt(item["modelExecution"], control)
        java_receipt, java_seconds = successful_receipt(item["javaExecution"], control)
        if numeric_receipt["processId"] != model["payloadPid"] or joined["javaExecution"] != item["javaExecution"]:
            raise ValueError("Actual numeric or Java provenance differs")
        gate = read(checked(model["inputGate"]))
        if len(gate["protected323"]) != 323 or gate["independentQaPassed"] is not False or gate["credit"] != 0:
            raise ValueError("Complete protected323 or zero-credit input qualification differs")
        for key in ("probes", "actualJvmRequests", "journal"):
            checked(model[key])
        rows.append({"officialCode": code, "id": model["id"], "probeCount": model["probeCount"],
            "comparisons": model["counts"]["lookupComparisons"], "numericSeconds": numeric_seconds,
            "actualJavaSeconds": java_seconds, "model": item["model"], "jvm": item["jvm"],
            "disposition": "SOLO_TECHNICAL_PREPARATION_COMPLETE_REQUIRES_SOURCE_REVIEW", "newGeographicCredit": 0})
        for kind, title, execution, detail in (
            ("gps", "Full source-driven GPS technical check", item["modelExecution"],
             "All source/grid probes and four accuracy modes checked against original exhaustive and independent formulas; protected323 bodies retained. Solo preparation; zero accepted-location credit."),
            ("jvm", "Actual JVM GPS, prayer-source and persistence check", item["javaExecution"],
             "Reviewed current pure core methods executed for every original request and four accuracy modes, then joined to durable numeric observations. Solo preparation; source acceptance remains pending.")):
            phases.append({"taskId": f"cycle{control['iteration']}-{code}-solo-{kind}", "title": title,
                "locationCodes": [code], "execution": execution, "endAction": "complete", "detail": detail})
    return rows, phases


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--activity-output", required=True, type=Path)
    args = parser.parse_args()
    spec = read(args.manifest)
    control = active_control(spec)
    if args.output.exists() or args.activity_output.exists() or spec.get("credit") != 0:
        raise ValueError("Fresh zero-credit summary and task proposal required")
    check_tree(spec)
    rows, phases = summarize(spec, control)
    summary = {"status": "COMPLETE_PREPARED13_SOLO_TECHNICAL_AUDITS_NO_ACCEPTANCE", "manifest": pin(args.manifest),
        "rows": rows, "preparedLocations": len(rows), "probeCount": sum(r["probeCount"] for r in rows),
        "comparisons": sum(r["comparisons"] for r in rows), "numericProcessSeconds": sum(r["numericSeconds"] for r in rows),
        "actualJavaProcessSeconds": sum(r["actualJavaSeconds"] for r in rows), "newGeographicCredit": 0,
        "newCompleteBodyCredit": 0, "newCorrectionCredit": 0, "independentQaPassed": False,
        "generatedAtUtc": datetime.now(timezone.utc).isoformat()}
    ref = write_new(args.output, summary)
    write_new(args.activity_output, {"control": spec["control"], "iteration": spec["iteration"],
        "app": spec["app"], "events": spec["events"], "phases": phases + spec.get("additionalPhases", [])})
    print(json.dumps({"summary": ref, "prepared": len(rows), "probes": summary["probeCount"],
        "comparisons": summary["comparisons"], "geographicCredit": 0}))


if __name__ == "__main__":
    main()
