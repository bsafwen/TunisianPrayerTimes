"""Write a compact, read-only checkpoint from current receipt/report pointers."""
from __future__ import annotations

import argparse
from pathlib import Path
import json

from .common import now, pin, read_pin, write

ASSETS = ("neighborhoods.json", "neighborhoods.bin", "locality-display-names.json")
COUNTS = ("uniqueValidatedLocations", "uniqueInstalledCorrectionLocations",
          "explicitFullSourceBoundaryLocationCount", "explicitScopedBoundaryLocationCount",
          "unknownBoundaryScopeLocationCount")


def checkpoint(repo, run_file, handoff_file, report_file, pending_packages=(), validation_state="paused"):
    if validation_state not in ("paused", "active"):
        raise ValueError("Validation state must be explicitly paused or active")
    # Stable reads, then verification against the actual installed receipt.
    inputs = {"run": pin(run_file), "handoff": pin(handoff_file), "report": pin(report_file)}
    run, handoff, report = (read_pin(inputs[key]) for key in ("run", "handoff", "report"))
    receipt_pin = run["practicalProgress"]
    receipt = read_pin(receipt_pin)
    issues = []
    if receipt_pin != handoff.get("practicalProgress"):
        issues.append("run_handoff_receipt_pointers_differ")
    live = {}
    folder = Path(repo).resolve() / "android-app/app/src/main/assets"
    for name in ASSETS:
        actual = pin(folder / name)
        live[name] = actual
        expected = receipt.get("installedAssets", {}).get(name, {})
        if expected.get("sha256") != actual["sha256"]:
            issues.append("receipt_live_asset_mismatch:" + name)
    # Report observations must still be bound to the events and pointer used to
    # produce them; do not refresh the report or treat counts as new acceptance.
    for item in report.get("sourceFiles", []):
        try:
            read_reference = {"file": item["path"], "sha256": item["sha256"]}
            actual = pin(read_reference["file"])
            if actual["sha256"] != read_reference["sha256"]:
                issues.append("report_source_changed:" + item.get("kind", "unknown"))
        except (KeyError, ValueError, OSError):
            issues.append("report_source_missing_or_invalid")
    summary = report["summary"]
    for key in ("validationIssues", "sourceOnlyAuditIssues", "reportingIssues"):
        if summary.get(key) != []:
            issues.append("report_issues_or_missing_list:" + key)
    counts = {key: summary[key] for key in COUNTS}
    if len(set(summary["validatedLocationCodes"])) != counts["uniqueValidatedLocations"]:
        issues.append("validated_count_code_list_mismatch")
    if len(set(summary["explicitFullSourceBoundaryLocalityCodes"])) != counts["explicitFullSourceBoundaryLocationCount"]:
        issues.append("full_source_count_code_list_mismatch")
    pending = []
    for raw in pending_packages:
        path = Path(raw).resolve()
        present = path.is_file()
        pending.append({"file": str(path), "present": present,
                        "pin": pin(path) if present else None,
                        "status": "SAVED_NOT_ACCEPTED" if present else "WAITING_FOR_PACKAGE"})
    # Detect input changes during this short read without traversing history.
    for key, original in inputs.items():
        if pin(original["file"])["sha256"] != original["sha256"]:
            issues.append("checkpoint_input_changed:" + key)
    return {"schemaVersion": 1, "generatedAtUtc": now(), "validationStateDeclaredByCaller": validation_state,
            "status": "CHECKPOINT_MATCHES_CURRENT_FILES" if not issues else "CHECKPOINT_HOLD",
            "inputs": inputs, "latestReceipt": receipt_pin, "latestReceiptAtUtc": receipt.get("appliedAtUtc"),
            "liveAssetPins": live, "counts": counts, "latestValidatedAtUtc": summary.get("latestValidatedAtUtc"),
            "pendingPackages": pending, "issues": issues, "geographicCreditAdded": 0,
            "qualification": "Counts are existing qualified report results. A complete source body and a local correction are distinct. This checkpoint does not run QA, inspect process liveness, modify data, resume a goal, or certify national GPS coverage."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("repo", "run", "handoff", "report", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--pending-package", type=Path, action="append", default=[])
    parser.add_argument("--validation-state", choices=("paused", "active"), required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Use a fresh checkpoint output file")
    result = checkpoint(args.repo, args.run, args.handoff, args.report,
                        args.pending_package, args.validation_state)
    write(args.output, result)
    print(json.dumps({key: result[key] for key in ("status", "counts", "issues")}, ensure_ascii=False))
    raise SystemExit(2 if result["issues"] else 0)


if __name__ == "__main__":
    main()
