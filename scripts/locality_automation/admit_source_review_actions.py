"""Enforce artifact-plan source authorship before expensive acceptance work.

This does not approve GPS, source geometry, recording or installation. It either
releases a distinct-author source-scope review or preserves an explicit blocker.
Diagnostics require a separately declared bounded plan and earn no credit.
"""
import argparse
from collections import Counter
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import checked, pin, read
from scripts.locality_automation.audit_prepared_osm_graphs import write_new


def action_for(row, reviewer):
    disposition = row["disposition"]
    if row.get("geographicCredit") != 0 or row.get("actualNewReviewerClaimed") is not False:
        raise ValueError("Dispatch plan incorrectly claims reviewer or acceptance authority")
    if disposition == "ALREADY_FULL_SOURCE_SKIP_PAYLOAD":
        return "SKIP_ALREADY_COMPLETE"
    if disposition == "SAME_SOURCE_AUTHOR_REQUIRES_DISJOINT_REVIEW":
        if row["observedSourceAuthor"] != reviewer:
            raise ValueError("Same-author blocker differs from observed author")
        return "HOLD_REVIEWER_POLICY"
    if disposition in ("SOURCE_AUTHOR_UNKNOWN_REQUIRES_PROVENANCE", "LEGACY_SOURCE_ONLY_REQUIRES_RECONCILIATION_AND_PROVENANCE"):
        return "HOLD_PROVENANCE_AND_SOURCE_RECONCILIATION"
    if disposition == "DISJOINT_SOURCE_AUTHOR_REQUIRES_REMAINING_REVIEW_GATES":
        author = row["observedSourceAuthor"]
        if not isinstance(author, str) or not author.strip() or author == reviewer:
            raise ValueError("Distinct observed source author missing")
        return "RELEASE_SOURCE_SCOPE_REVIEW_ONLY"
    raise ValueError("Unknown source-plan disposition; preserve it")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    spec = read(args.manifest)
    plan = read(checked(spec["plan"]))
    if args.output.exists() or spec.get("credit") != 0 or plan["reviewer"] != spec["reviewer"]:
        raise ValueError("Fresh zero-credit observed-reviewer admission required")
    rows, seen = [], set()
    for row in plan["rows"]:
        code = row["officialCode"]
        if code in seen:
            raise ValueError("Duplicate planned code")
        seen.add(code)
        action = action_for(row, plan["reviewer"])
        rows.append({"officialCode": code, "observedSourceAuthor": row["observedSourceAuthor"], "action": action,
            "sourceScopeReviewMayStart": action == "RELEASE_SOURCE_SCOPE_REVIEW_ONLY",
            "gpsOrJvmAcceptanceDispatchAllowed": False, "recordingOrInstallationAllowed": False, "credit": 0})
    result = {"status": "ENFORCED_SOURCE_REVIEW_ADMISSION_NO_ACCEPTANCE", "manifest": pin(args.manifest),
        "plan": spec["plan"], "rows": rows, "counts": dict(Counter(row["action"] for row in rows)),
        "releasedSourceReviewCodes": [row["officialCode"] for row in rows if row["sourceScopeReviewMayStart"]],
        "expensiveAcceptanceDispatchCodes": [], "sourceScopeAccepted": False, "independentQaPassed": False, "credit": 0,
        "qualification": "A released source-scope review must still pass all native/identity/legal/neighbor/topology and complete-scope gates before expensive GPS/JVM work. Same-author/unknown/accepted cases cannot silently enter the acceptance pipeline. A new diagnostic requires an explicit separate finite purpose and time budget; this file authorizes none."}
    reference = write_new(args.output, result)
    print({"report": reference, "counts": result["counts"], "released": result["releasedSourceReviewCodes"], "credit": 0})


if __name__ == "__main__":
    main()
