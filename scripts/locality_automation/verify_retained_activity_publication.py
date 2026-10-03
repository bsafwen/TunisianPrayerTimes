"""Verify a zero-addition publication and its real finite task clocks offline."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts.locality_automation.run_sealed_boundary_queue import checked, pin, read
from scripts.locality_automation.work_window_guard import parse_utc


def verify(before, after, old_map, new_map, activity):
    for key in ("validationIssues", "sourceOnlyAuditIssues", "reportingIssues"):
        if after["summary"].get(key) != []:
            raise ValueError("Current publication issue gate differs: " + key)
    old_summary = {k: v for k, v in before["summary"].items() if k != "unverifiedHistoricalClaimCount"}
    new_summary = {k: v for k, v in after["summary"].items() if k != "unverifiedHistoricalClaimCount"}
    if old_summary != new_summary:
        raise ValueError("Zero-addition publication changed summary/count/code sets")
    for key in ("validations", "sourceOnlyAcceptances"):
        if before[key] != after[key]:
            raise ValueError("Retained report history changed: " + key)
    old_series, new_series = before["cumulativeSeries"], after["cumulativeSeries"]
    if new_series[:len(old_series)] != old_series:
        raise ValueError("Existing cumulative graph points changed")
    expected_flat = {k: v for k, v in old_series[-1].items() if k not in ("hour", "label")}
    expected_flat.update(validatedLocationCount=0, validatedLocationCodes=[],
                         newValidatedLocationCount=0, repeatValidatedLocationCount=0)
    previous_hour = parse_utc(old_series[-1]["hour"])
    for row in new_series[len(old_series):]:
        if (parse_utc(row["hour"]) <= previous_hour
                or {k: v for k, v in row.items() if k not in ("hour", "label")} != expected_flat):
            raise ValueError("New cumulative graph point changed geographic credit")
        previous_hour = parse_utc(row["hour"])
    claims = {row["eventId"]: row for row in after["historicalClaims"]}
    old_claims = {row["eventId"]: row for row in before["historicalClaims"]}
    if len(claims) != len(after["historicalClaims"]) or any(claims.get(k) != v for k, v in old_claims.items()):
        raise ValueError("Historical unverified claims changed")
    phase_by_task = {row["taskId"]: row for row in activity["phases"]}
    for key in claims.keys() - old_claims.keys():
        row = claims[key]
        phase = phase_by_task.get(row["taskId"])
        if (phase is None or row["countedAsGeographicValidation"] is not False
                or row["qualification"] != phase["detail"]
                or set(row["locationCodes"]) != set(phase["locationCodes"])):
            raise ValueError("New unverified claim has unrelated facts or credit")
    for publication in (before, after):
        if publication["summary"]["unverifiedHistoricalClaimCount"] != len(publication["historicalClaims"]):
            raise ValueError("Unverified claim count differs")
    old = {k: v for k, v in old_map.items() if k != "generatedAtUtc"}
    new = {k: v for k, v in new_map.items() if k != "generatedAtUtc"}
    if old != new:
        raise ValueError("Retained map changed beyond its publication clock")
    if (activity["status"] != "APPENDED_ORIGINAL_TASK_ACTIVITY_NO_REFRESH"
            or activity["newGeographicCredit"] != 0 or activity["ledgerOrAssetChanges"] is not False):
        raise ValueError("Activity batch qualification differs")
    tasks = {}
    for hour in after["hours"]:
        for group in hour["locationGroups"]:
            for task in group["tasks"]:
                tasks.setdefault(task["taskId"], []).append(task)
    results = []
    for phase in activity["phases"]:
        copies = tasks.get(phase["taskId"], [])
        if not copies:
            raise ValueError("Published task missing: " + phase["taskId"])
        expected = "complete" if phase["endAction"] == "complete" else "blocked"
        for task in copies:
            if (task["status"] != expected or task["title"] != phase["title"]
                    or set(task["locationCodes"]) != set(phase["locationCodes"])
                    or task["result"] != phase["detail"]
                    or parse_utc(task["startedAtUtc"]) != parse_utc(phase["startedAtUtc"])
                    or parse_utc(task["endedAtUtc"]) != parse_utc(phase["finishedAtUtc"])
                    or task["sharedClock"] is not (len(phase["locationCodes"]) > 1)
                    or task["validationIds"] != [] or task["sourceOnlyAcceptanceIds"] != []):
                raise ValueError("Published task facts/clock/credit differ: " + phase["taskId"])
        results.append({"taskId": phase["taskId"], "locationCount": len(phase["locationCodes"]),
                        "sharedClock": copies[0]["sharedClock"], "publishedCopies": len(copies)})
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    spec = read(args.manifest)
    paths = {key: checked(spec[key]) for key in ("baselineReport", "report", "baselineMap", "map", "activity")}
    report = read(paths["report"])
    rows = verify(read(paths["baselineReport"]), report, read(paths["baselineMap"]), read(paths["map"]), read(paths["activity"]))
    for source in report["sourceFiles"]:
        checked({"file": source["path"], "sha256": source["sha256"]})
    for key in paths:
        checked(spec[key])
    proof = {"status": "PASS_RETAINED_PUBLICATION_AND_ACTUAL_TASK_CLOCKS",
        "atUtc": datetime.now(timezone.utc).isoformat(), "manifest": pin(args.manifest),
        "tasks": rows, "mapBodiesPreserved": len(read(paths["map"])["locations"]),
        "qualifiedGains": {"geographic": 0, "completeSourceBodies": 0, "installedCorrections": 0},
        "browserRenderingVerified": False, "networkRequestsPerformed": False, "privateKeyRead": False}
    with args.output.open("x", encoding="utf-8") as stream:
        json.dump(proof, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(json.dumps({"status": proof["status"], "taskCount": len(rows), "mapBodiesPreserved": proof["mapBodiesPreserved"]}))


if __name__ == "__main__":
    main()
