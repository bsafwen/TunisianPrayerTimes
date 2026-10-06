"""Verify ABBA frozen math trials without claiming whole-engine throughput."""
import argparse
from pathlib import Path
import statistics
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import active_control, checked, pin, read
from scripts.locality_automation.audit_prepared_osm_graphs import write_new
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree
from scripts.locality_automation.summarize_solo_locality_audits import successful_receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    spec = read(args.manifest)
    ctl = active_control(spec)
    check_tree(spec)
    if args.output.exists() or spec.get("credit") != 0 or len(spec["trials"]) != 4:
        raise ValueError("Fresh four-trial benchmark comparison required")
    rows, originals, hashes = [], [], set()
    for declaration, expected in zip(spec["trials"], ("baseline", "vector", "vector", "baseline")):
        trial = read(checked(declaration["report"]))
        execution, seconds = successful_receipt(declaration["execution"], ctl)
        if (trial["status"] != "PASS_FROZEN_MATH_KERNEL_BENCHMARK_NO_ADOPTION" or trial["mode"] != expected
                or trial["credit"] != 0 or trial["failures"] or trial["automaticAdoption"] is not False
                or trial["mathCallsSeconds"] <= 0 or trial["observations"] != 8 * trial["samplesPerCatalog"]):
            raise ValueError("Incomplete trial, observed disagreement or incorrect authority")
        manifest = read(checked(trial["manifest"]))
        originals.append({key: manifest[key] for key in ("model", "originalGpsManifest", "originalPackedDecoder", "vectorMembership", "helper", "samplesPerCatalog")})
        hashes.add(trial["journal"]["sha256"])
        checked(trial["journal"])
        rows.append({"mode": expected, "mathCallsSeconds": trial["mathCallsSeconds"], "leafSeconds": seconds,
            "observations": trial["observations"], "pid": execution["processId"], **declaration})
    if len(hashes) != 1 or any(item != originals[0] for item in originals[1:]):
        raise ValueError("Different cohort, helper or actual output journal")
    baseline = [row["mathCallsSeconds"] for row in rows if row["mode"] == "baseline"]
    vector = [row["mathCallsSeconds"] for row in rows if row["mode"] == "vector"]
    old, new = statistics.mean(baseline), statistics.mean(vector)
    result = {"status": "VERIFIED_COUNTERBALANCED_IDENTICAL_FROZEN_MATH_VECTOR_BENCHMARK",
        "manifest": pin(args.manifest), "trials": rows, "byteIdenticalActualJournals": True,
        "baselineMathSeconds": baseline, "vectorMathSeconds": vector,
        "baselineMeanSeconds": old, "vectorMeanSeconds": new, "mathSpeedRatio": old / new,
        "mathElapsedReductionPercent": 100 * (1 - new / old), "automaticAdoption": False,
        "sourceScopeAccepted": False, "independentQaPassed": False, "currentAssetsChanged": False, "credit": 0,
        "qualification": "Fixed sampled mathematical calls only, ABBA ordering, both frozen catalogs/all four modes; concurrent host workload varies. Complete replay proofs are separate. No whole-engine or qualified-location throughput claim."}
    reference = write_new(args.output, result)
    print({"report": reference, "mathSpeedRatio": old / new, "baseline": baseline, "vector": vector, "credit": 0})


if __name__ == "__main__":
    main()
