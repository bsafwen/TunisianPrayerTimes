"""Count each technically completed location once per observed UTC hour.

Administrative paused-window audit only. Prepared locations never become
validated locations; repeated speed trials are excluded by the pinned unique
case index.
"""
import argparse
from datetime import datetime, timedelta, timezone
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import checked, pin, read
from scripts.locality_automation.audit_prepared_osm_graphs import write_new
from scripts.locality_automation.work_window_guard import parse_utc


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    spec = read(args.manifest)
    start = read(checked(spec["windowStart"]))
    evidence = read(checked(spec["evidence"]))
    control = read(spec["control"])
    helper = checked(spec["helper"])
    if helper.resolve() != Path(__file__).resolve():
        raise ValueError("Pinned administrative helper differs")
    metrics_path = Path(spec["metricsPath"])
    metrics = read(metrics_path)
    metrics_reference = pin(metrics_path)
    beginning, deadline = parse_utc(start["atUtc"]), parse_utc(start["deadlineUtc"])
    if (args.output.exists() or control["phase"] != "paused" or datetime.now(timezone.utc) < deadline
            or parse_utc(control["windowStartUtc"]) != beginning
            or parse_utc(control["deadlineUtc"]) != deadline or evidence["credit"] != 0):
        raise ValueError("Exact completed paused window and fresh zero-credit evidence required")
    if any(metrics["qualifiedGains"][key] != 0 for key in
            ("geographic", "completeSourceBodies", "installedCorrections")):
        raise ValueError("Nonzero accepted gains require actual per-event hourly accounting")
    metrics_manifest = read(checked(metrics["manifest"]))
    if metrics_manifest["windowStart"] != spec["windowStart"]:
        raise ValueError("Qualified metrics belong to a different window")
    buckets = {}
    hour = beginning.replace(minute=0, second=0, microsecond=0)
    while hour < deadline:
        buckets[hour] = {"hourStartUtc": hour.isoformat(),
            "observedWindowStartUtc": max(beginning, hour).isoformat(),
            "observedWindowEndUtc": min(deadline, hour + timedelta(hours=1)).isoformat(),
            "currentBodyPreparations": [], "nativeHypothesisPreparations": [],
            "newValidatedLocations": 0, "newCompleteSourceBodies": 0, "newInstalledCorrections": 0}
        hour += timedelta(hours=1)
    seen = set()
    for row in evidence["rows"]:
        joined = read(checked(row["jvm"]))
        if (row["officialCode"] in seen or row["credit"] != 0 or joined["credit"] != 0
                or joined["independentQaPassed"] is not False or joined["sourceScopeAccepted"] is not False
                or row["officialCode"] != joined["officialCode"] or joined["failures"]):
            raise ValueError("Duplicate, failed or relabeled solo preparation")
        seen.add(row["officialCode"])
        completed = parse_utc(joined["completedAtUtc"])
        if not beginning <= completed < deadline:
            raise ValueError("Technical completion lies outside this work window")
        hour = completed.replace(minute=0, second=0, microsecond=0)
        if row["kind"] not in ("current-body-solo-preparation", "isolated-native-hypothesis"):
            raise ValueError("Unknown preparation kind")
        field = "currentBodyPreparations" if row["kind"] == "current-body-solo-preparation" else "nativeHypothesisPreparations"
        buckets[hour][field].append(row["officialCode"])
    if len(seen) != evidence["locationsPrepared"]:
        raise ValueError("Finite evidence count differs")
    rows = list(buckets.values())
    for row in rows:
        row["technicalPreparationsCompleted"] = len(row["currentBodyPreparations"]) + len(row["nativeHypothesisPreparations"])
    result = {"status": "PINNED_HOURLY_SOLO_PREPARATION_COMPLETIONS_NO_ACCEPTANCE", "manifest": pin(args.manifest),
        "hours": rows, "locationsPrepared": len(seen), "qualifiedMetrics": metrics_reference, "credit": 0,
        "qualification": "Completion is the original successful numeric/actual-JVM join timestamp. UTC buckets are clipped to the authorized window. Each case counted once; speed experiments excluded. Prepared counts are not geographic validation or complete-source/installed additions."}
    write_new(args.output, result)
    print({"status": result["status"], "hours": len(rows), "locationsPrepared": len(seen), "credit": 0})


if __name__ == "__main__":
    main()
