"""Index finite, hash-pinned solo preparation without inventing acceptance.

Each location is counted once. Repeated speed experiments are excluded from
case/comparison totals; original disagreements remain visible alongside the
separate revised ordinary-edge diagnosis.
"""
import argparse
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import active_control, checked, pin, read
from scripts.locality_automation.audit_prepared_osm_graphs import write_new
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree


def summarize_case(model_ref, jvm_ref, shadow):
    model, jvm = read(checked(model_ref)), read(checked(jvm_ref))
    for item in (model, jvm):
        if (item["credit"] != 0 or item["independentQaPassed"] is not False
                or item["sourceScopeAccepted"] is not False or item["ledgerWrites"] is not False):
            raise ValueError("Solo preparation cannot acquire reviewer or acceptance authority")
    if (model["officialCode"] != jvm["officialCode"] or model["id"] != jvm["id"]
            or model["indexedExhaustiveFailures"] or jvm["failures"]
            or jvm["fullAndroidGpsProviderServicesOrComposeExecuted"] is not False):
        raise ValueError("Incomplete, mismatched or relabeled model/JVM cohort")
    probes = model["probeCount"]
    if shadow:
        before, after = (model["counts"][key] for key in ("beforeComparisons", "afterComparisons"))
        if model["liveAssetsChanged"] is not False or jvm["liveAssetsChanged"] is not False:
            raise ValueError("Shadow preparation mutated live assets")
        comparisons = before + after
        if before != probes * 4 or after != probes * 4:
            raise ValueError("Complete four-mode before/after cohort required")
    else:
        after = model["counts"]["lookupComparisons"]
        comparisons = after
        if after != probes * 4 or model["independentFormulaDisagreements"]:
            raise ValueError("Complete current-body four-mode cohort required")
    if jvm["wholeCohortComparisons"] != after:
        raise ValueError("Actual Java does not cover the entire after/current cohort")
    return {"officialCode": model["officialCode"], "id": model["id"],
        "kind": "isolated-native-hypothesis" if shadow else "current-body-solo-preparation",
        "probes": probes, "mathematicalComparisons": comparisons, "actualJvmComparisons": after,
        "preservedOriginalOracleDisagreements": len(model["independentFormulaDisagreements"]),
        "model": model_ref, "jvm": jvm_ref, "credit": 0,
        "independentQaPassed": False, "sourceScopeAccepted": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    spec = read(args.manifest)
    active_control(spec)
    check_tree(spec)
    if args.output.exists() or spec.get("credit") != 0:
        raise ValueError("Fresh zero-credit finite evidence index required")
    beja = read(checked(spec["currentBodySummary"]))
    queue = read(checked(spec["shadowQueue"]))
    if (beja["newGeographicCredit"] or beja["newCompleteBodyCredit"] or beja["newCorrectionCredit"]
            or beja["independentQaPassed"] is not False or queue["credit"] != 0 or queue["failed"]
            or queue["status"] != "FINITE_SOLO_SHADOW_TECHNICAL_QUEUE_COMPLETE_NO_ACCEPTANCE"):
        raise ValueError("Incomplete or relabeled finite producer summary")
    rows = [summarize_case(row["model"], row["jvm"], False) for row in beja["rows"]]
    rows.append(summarize_case(spec["firstShadowModel"], spec["firstShadowJvm"], True))
    rows.extend(summarize_case(row["model"], row["joined"], True) for row in queue["completed"])
    codes = [row["officialCode"] for row in rows]
    if len(codes) != len(set(codes)) or set(codes) != set(spec["expectedCodes"]):
        raise ValueError("Exact unique finite location scope required")
    edge_rows = []
    for reference in spec["edgeReviews"]:
        edge = read(checked(reference))
        if (edge["failures"] or edge["credit"] != 0 or not edge["allOriginalArtifactsPreserved"]
                or edge["sourceScopeAccepted"] is not False or edge["independentQaPassed"] is not False
                or edge["accuracyPathsChanged"] is not False or edge["currentCodeOrAssetsChanged"] is not False):
            raise ValueError("Supplemental edge diagnosis changed authority, accuracy or original facts")
        edge_rows.append({"report": reference, "counts": edge["counts"], "changes": len(edge["changes"]), "credit": 0})
    edge_changes = sum(row["changes"] for row in edge_rows)
    original_holds = sum(row["preservedOriginalOracleDisagreements"] for row in rows)
    if edge_changes != original_holds:
        raise ValueError("Supplemental change total does not cover preserved original holds")
    totals = {key: sum(row[key] for row in rows) for key in
        ("probes", "mathematicalComparisons", "actualJvmComparisons", "preservedOriginalOracleDisagreements")}
    result = {"status": "INDEXED_FINITE_SOLO_PREPARATION_NO_ACCEPTANCE", "manifest": pin(args.manifest),
        "locationsPrepared": len(rows), "counts": totals, "rows": rows,
        "edgeReviews": edge_rows, "supplementalOrdinaryChanges": edge_changes,
        "recomputedOrdinaryObservations": sum(row["counts"]["recomputedOrdinaryObservations"] for row in edge_rows),
        "unchangedAccuracyObservations": sum(row["counts"]["unchangedAccuracyObservations"] for row in edge_rows),
        "sourceScopeAccepted": False, "independentQaPassed": False, "credit": 0,
        "qualification": "Preparation only. Mathematical counts include both before/after for isolated native hypotheses; actual Java covers current/after. Repeated speed experiments excluded. Original edge holds retained; supplemental correction does not grant geographic acceptance. Android provider services and Compose were not exercised."}
    check_tree(spec)
    write_new(args.output, result)
    print({"status": result["status"], "locations": len(rows), "counts": totals, "credit": 0})


if __name__ == "__main__":
    main()
