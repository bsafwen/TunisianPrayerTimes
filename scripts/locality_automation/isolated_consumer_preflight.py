"""Check a disposable consumer workspace before running its expensive full gates.

This checks the finite declared copy dependencies, active ledger/decoder paths,
and report clock. It grants no acceptance and never substitutes for the original
installer or complete report consumer. Inputs and failed workspaces stay intact.
"""
import argparse
import json
import os
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts.locality_automation.run_sealed_boundary_queue import checked, pin, read
from scripts.locality_automation.work_window_guard import parse_utc


def inspect_workspace(manifest):
    issues, bindings = [], []
    if manifest.get("schemaVersion") != 1:
        raise ValueError("Unsupported disposable preflight schema")
    source = Path(manifest["sourceRepository"]).resolve(strict=True)
    workspace = Path(manifest["workspaceRepository"]).resolve(strict=True)
    if source == workspace or source in workspace.parents or workspace in source.parents:
        raise ValueError("Disposable workspace must be separate from live repository")
    dependencies = manifest["requiredDependencies"]
    if not 1 <= len(dependencies) <= 32:
        raise ValueError("A finite one-to-32 dependency set is required")
    seen = set()
    for declaration in dependencies:
        relative = Path(declaration["relativePath"])
        if relative.is_absolute() or relative.drive or ".." in relative.parts or str(relative) in ("", "."):
            raise ValueError("Copy dependency must remain inside both repositories")
        key = relative.as_posix().casefold()
        if key in seen:
            raise ValueError("Duplicate copy dependency")
        seen.add(key)
        expected_source = (source / relative).resolve()
        destination = (workspace / relative).resolve()
        expected_source.relative_to(source)
        destination.relative_to(workspace)
        reference = declaration["source"]
        if Path(reference["file"]).resolve() != expected_source:
            raise ValueError("Copy dependency source differs from its exact repository path")
        # Bad source pins remain fatal; do not treat them as a missing clone file.
        checked(reference)
        if not destination.is_file():
            issues.append({"kind": "missing_dependency", "relativePath": relative.as_posix()})
        elif pin(destination)["sha256"] != reference["sha256"]:
            issues.append({"kind": "changed_dependency", "relativePath": relative.as_posix()})
        else:
            bindings.append({"relativePath": relative.as_posix(), "source": reference, "copy": pin(destination)})
        checked(reference)

    ledger_reference = manifest["runLedger"]
    ledger = read(checked(ledger_reference))
    metadata = ledger["installedCatalog"]
    expected_metadata = workspace / "android-app/app/src/main/assets/neighborhoods.json"
    expected_metadata.resolve().relative_to(workspace)
    actual_metadata = Path(metadata["file"]).resolve()
    if actual_metadata != expected_metadata.resolve():
        issues.append({"kind": "active_catalog_path", "expectedPath": str(expected_metadata)})
    else:
        checked(metadata)
    expected_decoder = workspace / "scripts/locality_automation/packed_gps_replay.py"
    expected_decoder.resolve().relative_to(workspace)
    selected_decoder = manifest["selectedDecoder"]
    if Path(selected_decoder["file"]).resolve() != expected_decoder.resolve():
        issues.append({"kind": "active_decoder_path", "expectedPath": str(expected_decoder)})
    elif "scripts/locality_automation/packed_gps_replay.py" not in seen:
        raise ValueError("Exact dynamic decoder copy must be declared")
    else:
        checked(selected_decoder)

    receipt_reference = manifest["installedReceipt"]
    receipt = read(checked(receipt_reference))
    if ledger["practicalProgress"] != receipt_reference:
        issues.append({"kind": "active_receipt_binding"})
    report_clock = parse_utc(manifest["reportClockUtc"])
    applied_clock = parse_utc(receipt["appliedAtUtc"])
    if report_clock < applied_clock:
        issues.append({"kind": "report_clock_precedes_install", "appliedAtUtc": receipt["appliedAtUtc"]})
    checked(ledger_reference)
    checked(receipt_reference)
    return {"status": "PASS_ISOLATED_CONSUMER_PREFLIGHT" if not issues else "HOLD_ISOLATED_CONSUMER_PREFLIGHT",
            "issues": issues, "copiedDependencyBindings": bindings,
            "runLedger": ledger_reference, "installedReceipt": receipt_reference,
            "expectedActiveDecoderPath": str(expected_decoder), "reportClockUtc": manifest["reportClockUtc"],
            "originalCompleteConsumerExecuted": False, "geographicCredit": 0,
            "limits": ["Only the explicit pinned dependency set is checked; original complete consumers remain mandatory.",
                       "No source, scope, identity, topology, GPS, persistence or installation gate is changed."]}


def save_diagnostic(path, model):
    """Save the exact consumer result before assertions so rejection is inspectable."""
    with Path(path).open("x", encoding="utf-8") as stream:
        stream.write(json.dumps(model, ensure_ascii=False, indent=2) + "\n")
        stream.flush()
        os.fsync(stream.fileno())
    return pin(Path(path))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    manifest_reference = pin(args.manifest)
    result = inspect_workspace(read(args.manifest))
    checked(manifest_reference)
    result["manifest"] = manifest_reference
    result["program"] = pin(Path(__file__))
    save_diagnostic(args.output, result)
    print(json.dumps({"status": result["status"], "issues": result["issues"], "geographicCredit": 0}))
    return 0 if not result["issues"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
