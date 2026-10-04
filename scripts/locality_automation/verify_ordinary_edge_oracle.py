"""Verify the corrected ordinary edge oracle against frozen complete cohorts.

Recompute every ordinary observation; original accuracy paths remain identical
objects. Old disagreements remain in their original journals and reports.
"""
import argparse
from collections import Counter
from datetime import datetime, timezone
import importlib.util
import json
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import active_control, checked, pin, read
from scripts.locality_automation.audit_prepared_osm_graphs import write_new
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree
from scripts.locality_automation.packed_gps_replay import PackedGpsReplay
from scripts.locality_automation.ordinary_edge_oracle_v2 import OrdinaryEdgeOracleV2
from scripts.locality_automation.work_window_guard import parse_utc


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    spec = read(args.manifest)
    ctl = active_control(spec)
    if args.output.exists() or spec.get("credit") != 0 or datetime.now(timezone.utc) >= parse_utc(ctl["safeSourceQaStartUtc"]):
        raise ValueError("Fresh zero-credit edge review required")
    check_tree(spec)
    loader = importlib.util.spec_from_file_location("pinned_original_edge_oracle", checked(spec["originalOracle"]))
    original = importlib.util.module_from_spec(loader)
    loader.loader.exec_module(original)
    args.output.mkdir()
    observations = args.output / "ordinary-observations.jsonl"
    counts, failures, changes, cohorts = Counter(), [], [], []
    with observations.open("x", encoding="utf-8") as stream:
        for cohort in spec["cohorts"]:
            replay = PackedGpsReplay(checked(cohort["metadata"]), checked(cohort["binary"]))
            if len(replay.features) != 3473 or len(replay.boundaries) != 2571:
                raise ValueError("Complete catalogue universe differs")
            edge = OrdinaryEdgeOracleV2(original.IndependentCurrentOracle(replay))
            for reference in cohort["journals"]:
                journal = checked(reference)
                ordinary_count, total = 0, 0
                with journal.open(encoding="utf-8") as source:
                    for ordinal, line in enumerate(source):
                        row = json.loads(line)
                        if row["probeOrdinal"] != ordinal // 4 or row["modeOrdinal"] != ordinal % 4:
                            raise ValueError("Original full probe/mode order differs")
                        total += 1
                        if row["modeOrdinal"] != 0:
                            # Wrapper delegates these paths verbatim; no replay or gate weakening.
                            counts["unchangedAccuracyObservations"] += 1
                            continue
                        revised, near = edge.find(row["lat"], row["lng"], None)
                        observation = {"cohort": cohort["name"], "journal": reference, "probeOrdinal": row["probeOrdinal"],
                            "id": row["id"], "lat": row["lat"], "lng": row["lng"], "original": row["oracle"],
                            "revised": revised, "indexed": row["indexed"], "nearExactEdge": near}
                        stream.write(json.dumps(observation, ensure_ascii=False, allow_nan=False) + "\n")
                        stream.flush()
                        os.fsync(stream.fileno())
                        keys = ("winnerId", "candidateIds", "qualifiedIds", "suppressedIds")
                        if any(revised[k] != row["indexed"][k] for k in keys):
                            failures.append({"cohort": cohort["name"], "id": row["id"], "probeOrdinal": row["probeOrdinal"]})
                        if any(revised[k] != row["oracle"][k] for k in keys):
                            changes.append({"cohort": cohort["name"], "id": row["id"], "probeOrdinal": row["probeOrdinal"], "nearExactEdge": near})
                        ordinary_count += 1
                        counts["recomputedOrdinaryObservations"] += 1
                if ordinary_count * 4 != total:
                    raise ValueError("Incomplete four-mode cohort")
                cohorts.append({"journal": reference, "ordinary": ordinary_count, "allModes": total})
                print(json.dumps({"cohort": cohort["name"], "journalCompleted": journal.name, "ordinary": ordinary_count}), flush=True)
    check_tree(spec)
    result = {"status": "PASS_VERSIONED_ORDINARY_EDGE_ORACLE_REVIEW_NO_ACCEPTANCE" if not failures else "VERSIONED_ORDINARY_EDGE_ORACLE_HOLD",
        "manifest": pin(args.manifest), "counts": dict(counts), "cohorts": cohorts, "failures": failures,
        "changes": changes, "observations": pin(observations), "allOriginalArtifactsPreserved": True,
        "sourceScopeAccepted": False, "independentQaPassed": False, "credit": 0,
        "accuracyPathsChanged": False, "currentCodeOrAssetsChanged": False,
        "limits": ["This verifies a mathematical edge rule against frozen observations; it does not supply a different source reviewer.",
                   "Original disagreements and original strict-admission rejection remain preserved."]}
    ref = write_new(args.output / "report.json", result)
    print(json.dumps({"report": ref, "status": result["status"], "counts": dict(counts), "failures": len(failures), "changes": len(changes), "credit": 0}))


if __name__ == "__main__":
    main()
