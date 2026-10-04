"""Measure unchanged scalar/vector math on one fixed four-mode frozen sample.

Each actual result is durable before comparison. Timing covers mathematical
calls only; this is not a whole-engine throughput or source-acceptance claim.
"""
import argparse
from datetime import datetime, timezone
import importlib.util
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
from scripts.locality_automation.packed_gps_replay import PackedGpsReplay
from scripts.locality_automation.run_solo_source_gps_audit import numeric_exhaustive
from scripts.locality_automation.vector_ring_membership_v1 import isolated_packed_module
from scripts.locality_automation.work_window_guard import parse_utc


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    spec = read(args.manifest)
    ctl = active_control(spec)
    check_tree(spec)
    if (args.output.exists() or spec.get("credit") != 0 or spec["mode"] not in ("baseline", "vector")
            or datetime.now(timezone.utc) >= parse_utc(ctl["safeSourceQaStartUtc"])):
        raise ValueError("Fresh finite zero-credit benchmark required")
    model = read(checked(spec["model"]))
    source = read(checked(spec["originalGpsManifest"]))
    staged = read(checked(source["staged"]))
    if (model["credit"] != 0 or model["officialCode"] != source["code"]
            or source["code"] not in ctl["approvedReconciliationPool"] or model["indexedExhaustiveFailures"]):
        raise ValueError("Original frozen source diagnostic differs")
    n = int(spec["samplesPerCatalog"])
    if not 1 <= n <= min(2000, model["probeCount"]):
        raise ValueError("Exact bounded sample required")
    selected = {i * (model["probeCount"] - 1) // max(1, n - 1) for i in range(n)}
    loader = importlib.util.spec_from_file_location("pinned_benchmark_oracle", checked(source["oracle"]))
    original = importlib.util.module_from_spec(loader)
    loader.loader.exec_module(original)
    factory = PackedGpsReplay
    exhaustive = numeric_exhaustive(source["numericOriginal"])
    if spec["mode"] == "vector":
        private = isolated_packed_module(checked(spec["originalPackedDecoder"]))
        factory = private.PackedGpsReplay
        exhaustive.__globals__["contains_geometry"] = private.contains_geometry
    args.output.mkdir()
    observed, seconds, failures = 0, 0., []
    journal_path = args.output / "observations.jsonl"
    with journal_path.open("x", encoding="utf-8") as out:
        for name, metadata, binary, journal in (
                ("before", source["beforeMetadata"], source["beforeBinary"], model["beforeJournal"]),
                ("after", staged["stagedMetadata"], staged["stagedBinary"], model["journal"])):
            replay = factory(checked(metadata), checked(binary))
            oracle = original.IndependentCurrentOracle(replay)
            modes = {}
            with checked(journal).open(encoding="utf-8") as stream:
                for ordinal, line in enumerate(stream):
                    row = json.loads(line)
                    if row["probeOrdinal"] != ordinal // 4 or row["modeOrdinal"] != ordinal % 4:
                        raise ValueError("Original full sequence differs")
                    if row["probeOrdinal"] not in selected:
                        continue
                    modes.setdefault(row["probeOrdinal"], []).append(row["modeOrdinal"])
                    begin = time.perf_counter()
                    indexed = replay.find(row["lat"], row["lng"], row["accuracyMeters"])
                    winner = exhaustive(replay, row["lat"], row["lng"], row["accuracyMeters"])
                    predicted, near = oracle.find(row["lat"], row["lng"], row["accuracyMeters"])
                    seconds += time.perf_counter() - begin
                    actual = {"catalog": name, "probeOrdinal": row["probeOrdinal"], "modeOrdinal": row["modeOrdinal"],
                        "indexed": indexed, "exhaustiveWinnerId": winner, "oracle": predicted, "nearExactEdge": near}
                    out.write(json.dumps(actual, ensure_ascii=False, allow_nan=False, sort_keys=True) + "\n")
                    out.flush()
                    os.fsync(out.fileno())
                    if (indexed != row["indexed"] or winner != row["exhaustiveWinnerId"]
                            or predicted != row["oracle"] or near != row["oracleNearExactEdge"]):
                        failures.append({"catalog": name, "probeOrdinal": row["probeOrdinal"], "modeOrdinal": row["modeOrdinal"]})
                    observed += 1
            if set(modes) != selected or any(value != [0, 1, 2, 3] for value in modes.values()):
                raise ValueError("Incomplete original sampled cohort")
    check_tree(spec)
    result = {"status": "PASS_FROZEN_MATH_KERNEL_BENCHMARK_NO_ADOPTION" if not failures else "FROZEN_MATH_KERNEL_HOLD",
        "manifest": pin(args.manifest), "mode": spec["mode"], "samplesPerCatalog": len(selected),
        "observations": observed, "mathCallsSeconds": seconds, "failures": failures, "journal": pin(journal_path),
        "currentAssetsChanged": False, "sourceScopeAccepted": False, "independentQaPassed": False,
        "automaticAdoption": False, "credit": 0,
        "qualification": "Evenly spaced subset of both frozen catalogs and all four modes. Uninstrumented mathematical-call timer excludes file IO and checks. Replays/cache setup are fresh; no whole-engine or accepted-location throughput claim."}
    reference = write_new(args.output / "report.json", result)
    print(json.dumps({"report": reference, "mode": spec["mode"], "mathCallsSeconds": seconds, "failures": len(failures), "credit": 0}))


if __name__ == "__main__":
    main()
