"""Compare unchanged oracle results on a complete archived four-mode cohort.

This measures only the numerical oracle and its repeated source-clearance
calls, not complete native/GPS/JVM/CAF acceptance or a fresh location review.
"""
import argparse
from contextlib import nullcontext
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys
import time

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))
from scripts.locality_automation.run_sealed_boundary_queue import checked, pin, read
from scripts.locality_automation.packed_gps_replay import PackedGpsReplay
from scripts.locality_automation.oracle_clearance_reuse import reuse_clearance
from shapely import from_wkb
from shapely.geometry import Point


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--mode", choices=("stock", "reuse"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    spec = read(args.manifest)
    paths = {key: checked(spec[key]) for key in ("oracle", "decoder", "adapter", "metadata", "binary", "results", "rawSource", "expectedSource")}
    for key, name in (("decoder", "packed_gps_replay.py"), ("adapter", "oracle_clearance_reuse.py")):
        if paths[key].resolve() != (REPO / "scripts/locality_automation" / name).resolve():
            raise ValueError("Wrong local helper path")
    results = read(paths["results"])
    if len(results) != spec["comparisonCount"] or len(results) != spec["probeCount"] * 4:
        raise ValueError("Complete archived four-mode cohort differs")
    grouped = {}
    for row in results:
        grouped.setdefault(row["id"], []).append(row["accuracyMeters"])
    if len(grouped) != spec["probeCount"] or any(modes != [None, 5, 20, 50] for modes in grouped.values()):
        raise ValueError("Missing, duplicated or changed original mode")
    module_spec = importlib.util.spec_from_file_location("unchanged_archived_clearance_oracle", paths["oracle"])
    module = importlib.util.module_from_spec(module_spec)
    module_spec.loader.exec_module(module)
    raw, expected = from_wkb(paths["rawSource"].read_bytes()), from_wkb(paths["expectedSource"].read_bytes())
    replay = PackedGpsReplay(paths["metadata"], paths["binary"])
    oracle = module.IndependentCurrentOracle(replay)
    own = next(index for index, row in replay.boundaries.items() if row["id"] == spec["id"])
    output, failures = [], []
    stats = None
    started = datetime.now(timezone.utc).isoformat()
    timer = time.perf_counter()
    with (reuse_clearance(oracle) if args.mode == "reuse" else nullcontext()) as stats:
        for row in results:
            lat, lon, accuracy = row["lat"], row["lng"], row["accuracyMeters"]
            predicted, edge = oracle.find(lat, lon, accuracy)
            point = Point(lon, lat)
            mode_accuracy = accuracy or 0
            # Preserve the exact archived source frame/grid scalars while
            # independently evaluating source/current containment and the
            # original oracle's clearance. No scalar is rounded or repaired.
            before_clearance = (raw.contains(point) and expected.contains(point)
                and row["conservativeRawSourceQueryFrameClearanceMeters"] >
                    mode_accuracy + row["declaredNPOL1e6HalfCellQueryFrameBoundMeters"])
            clearance = oracle.clearance(own, lat, lon, mode_accuracy) if before_clearance else None
            robust = before_clearance and clearance > mode_accuracy
            actual = dict(id=row["id"], accuracyMeters=accuracy,
                independentGisPrediction=predicted, exactBoundaryOracleQualified=edge,
                sourceAdministrativeRobust=robust, repeatedClearanceValue=clearance)
            for key in ("independentGisPrediction", "exactBoundaryOracleQualified", "sourceAdministrativeRobust"):
                if actual[key] != row[key]:
                    failures.append(dict(id=row["id"], accuracyMeters=accuracy, key=key))
            output.append(actual)
    seconds = time.perf_counter() - timer
    for key in paths:
        checked(spec[key])
    canonical = json.dumps(output, ensure_ascii=False, allow_nan=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    with args.output.with_suffix(".results.json").open("xb") as stream:
        stream.write(canonical)
    proof = dict(status="PASS_COMPLETE_ARCHIVED_NUMERICAL_ORACLE_COHORT" if not failures else "FAIL_ARCHIVED_ORACLE_OUTPUT_MISMATCH",
        mode=args.mode, startedAtUtc=started, finishedAtUtc=datetime.now(timezone.utc).isoformat(),
        payloadPid=os.getpid(), inputs={key: spec[key] for key in paths},
        comparisonCount=len(output), probeCount=len(grouped), numericalSeconds=seconds,
        canonicalResultsSha256=hashlib.sha256(canonical).hexdigest(), failures=failures,
        cacheStats=stats, originalMethodRestored="clearance" not in vars(oracle),
        newGeographicCredit=0, fullGpsOrConsumerAcceptanceReplayed=False,
        qualification="Complete archived oracle/find and repeated source-clearance output comparison only. Saved source frame/grid scalars are consumed unchanged; source/native/JVM/CAF gates are not replayed or accepted.")
    with args.output.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(proof, stream, ensure_ascii=False, allow_nan=False, indent=2)
        stream.write("\n")
    print(json.dumps({key: proof[key] for key in ("status", "mode", "comparisonCount", "numericalSeconds", "canonicalResultsSha256", "cacheStats", "newGeographicCredit")}))
    if failures:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
