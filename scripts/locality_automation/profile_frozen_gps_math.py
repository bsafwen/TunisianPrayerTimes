"""Profile an explicitly sampled frozen mathematical cohort without acceptance.

Recompute unchanged indexed, exhaustive and original oracle outputs. This is
instrumented hotspot evidence, not a full validation or speedup benchmark.
"""
import argparse
import cProfile
from datetime import datetime, timezone
import importlib.util
import json
from pathlib import Path
import pstats
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import active_control, checked, pin, read
from scripts.locality_automation.audit_prepared_osm_graphs import write_new
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree
from scripts.locality_automation.packed_gps_replay import PackedGpsReplay
from scripts.locality_automation.run_solo_source_gps_audit import numeric_exhaustive
from scripts.locality_automation.work_window_guard import parse_utc


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    spec = read(args.manifest)
    ctl = active_control(spec)
    check_tree(spec)
    if (args.output.exists() or spec.get("credit") != 0
            or datetime.now(timezone.utc) >= parse_utc(ctl["safeSourceQaStartUtc"])):
        raise ValueError("Fresh finite zero-credit profile required")
    model = read(checked(spec["model"]))
    original = read(checked(spec["originalGpsManifest"]))
    staged = read(checked(original["staged"]))
    if (model["officialCode"] != original["code"] or model["credit"] != 0
            or model["officialCode"] not in ctl["approvedReconciliationPool"]
            or model["indexedExhaustiveFailures"] or model["sourceScopeAccepted"] is not False):
        raise ValueError("Frozen isolated diagnostic scope differs")
    sample_count = int(spec["samplesPerCatalog"])
    if sample_count < 1 or sample_count > min(2000, model["probeCount"]):
        raise ValueError("Bounded positive mathematical sample required")
    selected = {i * (model["probeCount"] - 1) // max(1, sample_count - 1) for i in range(sample_count)}
    loader = importlib.util.spec_from_file_location("pinned_profile_oracle", checked(original["oracle"]))
    oracle_module = importlib.util.module_from_spec(loader)
    loader.loader.exec_module(oracle_module)
    exhaustive = numeric_exhaustive(original["numericOriginal"])
    profile = cProfile.Profile()
    observed, failures = 0, []
    for name, metadata, binary, journal in (
            ("before", original["beforeMetadata"], original["beforeBinary"], model["beforeJournal"]),
            ("after", staged["stagedMetadata"], staged["stagedBinary"], model["journal"])):
        replay = PackedGpsReplay(checked(metadata), checked(binary))
        oracle = oracle_module.IndependentCurrentOracle(replay)
        modes = {}
        with checked(journal).open(encoding="utf-8") as stream:
            for ordinal, line in enumerate(stream):
                row = json.loads(line)
                if row["probeOrdinal"] != ordinal // 4 or row["modeOrdinal"] != ordinal % 4:
                    raise ValueError("Frozen original probe/mode sequence differs")
                if row["probeOrdinal"] not in selected:
                    continue
                modes.setdefault(row["probeOrdinal"], []).append(row["modeOrdinal"])
                profile.enable()
                try:
                    indexed = replay.find(row["lat"], row["lng"], row["accuracyMeters"])
                    winner = exhaustive(replay, row["lat"], row["lng"], row["accuracyMeters"])
                    predicted, near = oracle.find(row["lat"], row["lng"], row["accuracyMeters"])
                finally:
                    profile.disable()
                if (indexed != row["indexed"] or winner != row["exhaustiveWinnerId"]
                        or predicted != row["oracle"] or near != row["oracleNearExactEdge"]):
                    failures.append({"catalog": name, "probeOrdinal": row["probeOrdinal"], "modeOrdinal": row["modeOrdinal"]})
                observed += 1
        if set(modes) != selected or any(value != [0, 1, 2, 3] for value in modes.values()):
            raise ValueError("Incomplete sampled four-mode cohort")
    args.output.mkdir()
    profile_path = args.output / "math.prof"
    profile.dump_stats(str(profile_path))
    stats = pstats.Stats(profile)
    functions = [{"file": key[0], "line": key[1], "function": key[2], "primitiveCalls": value[0],
        "totalCalls": value[1], "selfSeconds": value[2], "cumulativeSeconds": value[3]}
        for key, value in stats.stats.items()]
    functions.sort(key=lambda item: item["selfSeconds"], reverse=True)
    check_tree(spec)
    result = {"status": "PROFILED_FROZEN_GPS_MATH_SAMPLE_NO_ACCEPTANCE" if not failures else "FROZEN_GPS_PROFILE_OUTPUT_HOLD",
        "manifest": pin(args.manifest), "samplesPerCatalog": len(selected), "allModesObservations": observed,
        "failures": failures, "instrumentedSelfSeconds": stats.total_tt, "hottestFunctions": functions[:30],
        "profile": pin(profile_path), "sourceScopeAccepted": False, "independentQaPassed": False,
        "currentAssetsChanged": False, "helperAdoptionAuthorized": False, "credit": 0,
        "qualification": "Evenly spaced subset, all four original modes and both catalogs. cProfile is intrusive; this is hotspot diagnosis, not whole-cohort validation, uninstrumented timing or an end-to-end throughput claim."}
    reference = write_new(args.output / "report.json", result)
    print(json.dumps({"report": reference, "status": result["status"], "observations": observed,
        "failures": len(failures), "credit": 0}))


if __name__ == "__main__":
    main()
