"""Counterbalanced durable-writing benchmark on a frozen real whole journal.

Every trial keeps byte-identical output and original classification results.
No GPS formulas, probe cohorts, source gates, publication or assets are changed.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import statistics
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import active_control, checked, pin, read
from scripts.locality_automation.audit_prepared_osm_graphs import write_new
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree
from scripts.locality_automation.durable_observation_journal import persist_original_rows, persist_probe, validate_probe
from scripts.locality_automation.work_window_guard import parse_utc


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    spec = read(args.manifest)
    ctl = active_control(spec)
    if args.output.exists() or spec.get("credit") != 0 or datetime.now(timezone.utc) >= parse_utc(ctl["safeSourceQaStartUtc"]):
        raise ValueError("Fresh zero-credit benchmark required")
    check_tree(spec)
    source = checked(spec["journal"])
    rows = [json.loads(line) for line in source.read_text(encoding="utf-8").splitlines()]
    if len(rows) != spec["comparisonCount"] or len(rows) % 4:
        raise ValueError("Full frozen probe cohort differs")
    for offset in range(0, len(rows), 4):
        validate_probe(rows[offset:offset + 4])
    original_hash = hashlib.sha256(source.read_bytes()).hexdigest()
    args.output.mkdir()
    trials = []
    for number, mode in enumerate(("record", "probe", "probe", "record", "record", "probe")):
        failures, disagreements, classes = [], [], []
        def classify(row):
            if row["indexed"]["winnerId"] != row["exhaustiveWinnerId"]:
                failures.append((row["probeOrdinal"], row["modeOrdinal"]))
            if any(row["indexed"][key] != row["oracle"][key] for key in ("winnerId", "candidateIds", "qualifiedIds", "suppressedIds")):
                disagreements.append((row["probeOrdinal"], row["modeOrdinal"]))
            classes.append((row["probeOrdinal"], row["modeOrdinal"], row["sourceClass"]))
        target = args.output / f"trial{number}-{mode}.jsonl"
        start = time.perf_counter()
        with target.open("x", encoding="utf-8") as stream:
            if mode == "record":
                persist_original_rows(stream, rows, classify)
            else:
                for offset in range(0, len(rows), 4):
                    persist_probe(stream, rows[offset:offset + 4], classify)
        elapsed = time.perf_counter() - start
        actual_hash = hashlib.sha256(target.read_bytes()).hexdigest()
        if actual_hash != original_hash or classes != [(row["probeOrdinal"], row["modeOrdinal"], row["sourceClass"]) for row in rows]:
            raise ValueError("Durable output bytes or original classification order differs")
        facts = hashlib.sha256(json.dumps([failures, disagreements, classes], ensure_ascii=False).encode()).hexdigest()
        trials.append({"number": number, "mode": mode, "seconds": elapsed, "journal": pin(target),
            "failures": len(failures), "disagreements": len(disagreements), "classificationSha256": facts})
        print(json.dumps({"trial": number, "mode": mode, "seconds": elapsed}), flush=True)
    if len({trial["classificationSha256"] for trial in trials}) != 1:
        raise ValueError("Classification facts differ between strategies")
    check_tree(spec)
    means = {mode: statistics.mean(t["seconds"] for t in trials if t["mode"] == mode) for mode in ("record", "probe")}
    result = {"status": "VERIFIED_COUNTERBALANCED_DURABLE_PROBE_BENCHMARK_NO_AUTOMATIC_ADOPTION",
        "manifest": pin(args.manifest), "trials": trials, "comparisonsPerTrial": len(rows),
        "meansSeconds": means, "writingSpeedup": means["record"] / means["probe"],
        "byteIdenticalEveryTrial": True, "classificationFactsAndOrderIdentical": True,
        "allRecordsDurableBeforeClassification": True, "fullGpsEngineSpeedupMeasured": False,
        "credit": 0, "originalSourceAndAcceptanceGatesChanged": False}
    ref = write_new(args.output / "report.json", result)
    print(json.dumps({"report": ref, "means": means, "writingSpeedup": result["writingSpeedup"], "credit": 0}))


if __name__ == "__main__":
    main()
