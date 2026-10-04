"""Join every pinned solo numeric observation with an actual guarded JVM result."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import active_control, checked, pin, read
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree
from scripts.locality_automation.audit_prepared_osm_graphs import write_new
from scripts.locality_automation.work_window_guard import parse_utc


def compare(model, actual, ordinal):
    mode = "ordinary" if model["accuracyMeters"] is None else model["accuracyMeters"]
    expected = model["expectedSourceId"]
    label = actual.get("label")
    return (actual["requestIndex"] == ordinal // 4 and actual["name"] == model["id"]
            and actual["lat"] == model["lat"] and actual["lng"] == model["lng"]
            and actual["accuracy"] == mode
            and (label["id"] if label is not None else None) == model["indexed"]["winnerId"]
            and actual["expectedSourceId"] == expected and actual["actualGpsSourceId"] == expected
            and actual["actualPersistedSourceId"] == expected and actual["sourceMatches"] is True
            and actual["labelPersistenceMatches"] is True and actual["manualReferenceCleared"] is True)


def verify(spec, manifest_path, output):
    active_control(spec)
    check_tree(spec)
    binding = read(checked(spec["binding"]))
    model = read(checked(binding["model"]))
    receipt = read(checked(spec["javaExecution"]))
    actual = read(checked(spec["actualJvm"]))
    if (binding["code"] != model["officialCode"] or binding["independentQaPassed"] is not False
            or binding["credit"] != 0 or model["independentQaPassed"] is not False or model["credit"] != 0):
        raise ValueError("Solo role or code qualification changed")
    if (receipt["status"] != "COMPLETED" or receipt["exitCode"] != 0 or receipt["timedOut"] is not False
            or receipt["argv"] != binding["argv"] or receipt["processId"] != actual["processId"]
            or parse_utc(receipt["finishedAtUtc"]) >= parse_utc(spec["deadlineUtc"])
            or parse_utc(receipt["startedAtUtc"]) < parse_utc(spec["windowStartUtc"])):
        raise ValueError("Actual guarded Java receipt, process, inputs or work window differs")
    if (actual["status"] != "PASS_ACTUAL_CURRENT_INDEX_GPS_SOURCE_AND_PERSISTENCE" or actual["failures"]
            or actual["cases"] != model["probeCount"] * 4 or len(actual["results"]) != actual["cases"]):
        raise ValueError("Actual JVM whole cohort failed or incomplete")
    for reference in binding["retainedClasses"] + binding["retainedRuntimeJars"]:
        checked(reference)
    for reference in binding["currentAssets"].values():
        checked(reference)
    for item in binding["currentCoreBindings"]:
        checked(item["snapshot"])
        checked(item["current"])
    journal = checked(model["journal"])
    failures, count = [], 0
    with journal.open(encoding="utf-8") as stream:
        for ordinal, line in enumerate(stream):
            row = json.loads(line)
            if row["probeOrdinal"] != ordinal // 4 or row["modeOrdinal"] != ordinal % 4:
                raise ValueError("Durable journal lost original probe/mode ordering")
            if ordinal >= len(actual["results"]) or not compare(row, actual["results"][ordinal], ordinal):
                failures.append(ordinal)
            count += 1
    catalog_mode = binding.get("catalogMode", "current")
    if catalog_mode not in ("current", "isolated-shadow"):
        raise ValueError("Unknown explicit catalogue mode")
    comparisons = model["counts"]["lookupComparisons"] if catalog_mode == "current" else model["counts"]["afterComparisons"]
    if catalog_mode == "isolated-shadow":
        diagnostic_only = binding.get("unresolvedOracleDiagnosticOnly", False)
        required_status = "PINNED_UNRESOLVED_SHADOW_JVM_DIAGNOSTIC_NO_INSTALLATION" if diagnostic_only else "PINNED_SOLO_SHADOW_JVM_DIRECT_LEAF_NO_INSTALLATION"
        if (binding["status"] != required_status
                or model["status"] != "SOLO_SHADOW_WHOLE_SOURCE_GPS_DIAGNOSTIC_NO_INSTALLATION"
                or model["liveAssetsChanged"] is not False):
            raise ValueError("Shadow catalogue or authority differs")
        checked(binding["liveMetadata"])
        checked(binding["liveBinary"])
    if count != actual["cases"] or count != comparisons:
        raise ValueError("Original observations and actual results differ in length")
    result = {"status": "PASS_SOLO_ACTUAL_CURRENT_JVM_COMPARE_NO_ACCEPTANCE" if not failures else "SOLO_ACTUAL_JVM_COMPARISON_HOLD",
        "officialCode": model["officialCode"], "id": model["id"], "manifest": pin(manifest_path),
        "binding": spec["binding"], "javaExecution": spec["javaExecution"], "actualJvm": spec["actualJvm"],
        "numericModel": binding["model"], "wholeCohortComparisons": count, "failures": failures, "catalogMode": catalog_mode,
        "sourceActor": "/root", "technicalAuditActor": "/root", "independentQaPassed": False,
        "sourceScopeAccepted": False, "credit": 0, "ledgerWrites": False,
        "actualCurrentIndexNearestPrayerSourceAndPersistenceExecuted": True,
        "fullAndroidGpsProviderServicesOrComposeExecuted": False,
        "completedAtUtc": datetime.now(timezone.utc).isoformat(),
        "limits": ["Solo source and technical review do not fulfill the saved disjoint reviewer requirement.",
                   "No native/legal/source-scope or full CAF/A4 acceptance inferred from JVM agreement."]}
    if catalog_mode == "isolated-shadow":
        result["status"] = "PASS_SOLO_ACTUAL_SHADOW_JVM_COMPARE_NO_INSTALLATION" if not failures else "SOLO_ACTUAL_SHADOW_JVM_COMPARISON_HOLD"
        result["liveAssetsChanged"] = False
        result["actualCurrentIndexNearestPrayerSourceAndPersistenceExecuted"] = False
        result["actualShadowIndexNearestPrayerSourceAndPersistenceExecuted"] = True
        result["unresolvedFormulaDisagreementCount"] = len(model["independentFormulaDisagreements"])
        if model["independentFormulaDisagreements"]:
            if binding.get("unresolvedOracleDiagnosticOnly") is not True:
                raise ValueError("Unresolved oracle differences cannot be erased")
            result["status"] = "SOLO_SHADOW_ACTUAL_JVM_AGREES_PENDING_ORACLE_EDGE_REVIEW" if not failures else "SOLO_ACTUAL_SHADOW_JVM_COMPARISON_HOLD"
            result["unresolvedFormulaDisagreements"] = model["independentFormulaDisagreements"]
    check_tree(spec)
    reference = write_new(output, result)
    print(json.dumps({"report": reference, "status": result["status"], "comparisons": count, "failures": len(failures), "credit": 0}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    verify(read(args.manifest), args.manifest, args.output)


if __name__ == "__main__":
    main()
