"""Verify complete stock/indexed/indexed/stock report trials and real receipts."""
import argparse
from datetime import datetime, timezone
from pathlib import Path
import statistics
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.audit_prepared_osm_graphs import write_new
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree
from scripts.locality_automation.run_sealed_boundary_queue import checked, pin, read
from scripts.locality_automation.work_window_guard import parse_utc


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    spec = read(args.manifest)
    if args.output.exists() or spec.get("credit") != 0 or len(spec["trials"]) != 4:
        raise ValueError("Fresh zero-credit four-trial proof required")
    check_tree(spec)
    clocks = read(checked(spec["windowStart"]))
    rows, modes, sets = [], [], []
    for item in spec["trials"]:
        measurement = read(checked(item["measurement"]))
        receipt = read(checked(item["execution"]))
        if (measurement["status"] != "PASS_UNCHANGED_FULL_PUBLICATION"
                or measurement["liveWrites"] or measurement["newCredit"] != 0
                or measurement["privateGoogleConfigRead"]
                or receipt["status"] != "COMPLETED" or receipt["exitCode"] != 0 or receipt["timedOut"]
                or not receipt["recordOwner"] or type(receipt["processId"]) is not int
                or not parse_utc(clocks["atUtc"]) <= parse_utc(receipt["startedAtUtc"]) <= parse_utc(receipt["finishedAtUtc"]) < parse_utc(clocks["deadlineUtc"])
                or parse_utc(receipt["hardDeadlineUtc"]) != parse_utc(clocks["deadlineUtc"])
                or parse_utc(receipt["startedAtUtc"]) >= parse_utc(receipt["safeStartUtc"])):
            raise ValueError("Trial authority, actual successful receipt or original clock differs")
        if measurement["manifest"] != spec["benchmarkManifest"]:
            raise ValueError("Trial used another immutable manifest")
        if measurement["mode"] == "indexed":
            stats = measurement["taskSourcePathStatistics"]
            if stats["hits"] <= 0 or stats["misses"] <= 0 or stats["stabilityRechecks"] != stats["misses"]:
                raise ValueError("Lookup hits and complete final stability rechecks required")
        directory = Path(item["measurement"]["file"]).parent
        actual = {name: pin(directory / name)["sha256"] for name in measurement["files"]}
        if actual != measurement["files"] or len(actual) != 6:
            raise ValueError("Six complete physically current publication outputs required")
        sets.append(actual)
        modes.append(measurement["mode"])
        rows.append({"mode": measurement["mode"], "elapsedSeconds": measurement["elapsedSeconds"],
                     "measurement": item["measurement"], "execution": item["execution"],
                     "taskSourcePathStatistics": measurement["taskSourcePathStatistics"]})
    if modes != ["stock", "indexed", "indexed", "stock"] or any(s != sets[0] for s in sets[1:]):
        raise ValueError("Counterbalanced order or full publication bytes differ")
    stock = statistics.mean(r["elapsedSeconds"] for r in rows if r["mode"] == "stock")
    indexed = statistics.mean(r["elapsedSeconds"] for r in rows if r["mode"] == "indexed")
    check_tree(spec)
    result = {"status": "PASS_COUNTERBALANCED_FULL_STOCK_AND_INDEXED_PUBLICATION", "manifest": pin(args.manifest),
        "verifiedAtUtc": datetime.now(timezone.utc).isoformat(), "trials": rows,
        "stockMeanSeconds": stock, "indexedMeanSeconds": indexed,
        "speedRatio": stock/indexed, "elapsedReductionPercent": 100*(1-indexed/stock),
        "sixEntireOutputFilesIdentical": sets[0], "originalReaderAndAppUnchanged": True,
        "productionAdopted": False, "credit": 0,
        "qualification": "Measured complete isolated report refresh only, including unchanged original CAF/A4/source/export checks. This is not source validation throughput, Google rendering, or permission to weaken evidence checks."}
    ref = write_new(args.output, result)
    print({"report": ref, "stock": stock, "indexed": indexed, "ratio": stock/indexed, "reductionPercent": result["elapsedReductionPercent"], "credit": 0})


if __name__ == "__main__":
    main()
