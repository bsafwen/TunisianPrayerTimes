"""Record source/GPS validation of already correct geometry without rewriting assets."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import importlib.util
import json
import os
from pathlib import Path
import sys

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))
from scripts.locality_automation.install_reviewed_boundary_patch import checked, check_tree, pin, read, sha, write
from scripts.locality_automation.packed_gps_replay import PackedGpsReplay
from scripts.locality_automation.multipart_pdf_evidence_v2 import validate_parts
from pyproj import Transformer
from shapely import set_precision
from shapely.ops import transform
from shapely.wkb import loads


def record(args):
    if args.output.exists():
        raise ValueError("Use a fresh validation receipt directory")
    run_bytes, handoff_bytes = args.run.read_bytes(), args.handoff.read_bytes()
    gps_bytes = args.gps.read_bytes()
    run, gps = json.loads(run_bytes), json.loads(gps_bytes.decode("utf-8-sig"))
    if (gps.get("status") != "PASS_OFFLINE_CURRENT_SOURCE_GPS" or gps.get("structuralFailures") != []
            or gps.get("probeFailures") != [] or gps.get("probeCount", 0) <= 0
            or gps.get("lookupComparisons", 0) <= 0):
        raise ValueError("Current source/GPS audit did not pass")
    check_tree(gps["inputs"])
    snapshot_json, snapshot_bin = checked(gps["inputs"]["currentJson"]), checked(gps["inputs"]["currentBin"])
    baseline_ref = run["practicalProgress"]
    baseline = read(checked(baseline_ref))
    for name, snapshot in (("neighborhoods.json", snapshot_json), ("neighborhoods.bin", snapshot_bin)):
        if sha(checked(baseline["installedAssets"][name])) != sha(snapshot):
            raise ValueError("Validated snapshots differ from the actual current installed catalog")
    checks = gps["decodedCurrentChecks"]
    codes = sorted(checks)
    probe_values = gps["probes"]
    if isinstance(probe_values, dict):
        probe_path = checked(probe_values)
        if probe_path.suffix == ".jsonl":
            probe_values = [json.loads(line) for line in probe_path.read_text(encoding="utf-8-sig").splitlines() if line.strip()]
        else:
            probe_values = read(probe_path)["probes"]
    if not codes or not isinstance(probe_values, list) or len(probe_values) != gps["probeCount"]:
        raise ValueError("Missing current boundary checks or exact probes")
    replay = PackedGpsReplay(snapshot_json, snapshot_bin)
    to_metric = Transformer.from_crs(4326, 32632, always_xy=True).transform
    verified = []
    for code in codes:
        row = checks[code]
        if (row.get("valid") is not True or row.get("sourceScopeAccepted") is not True
                or row.get("equalExpectedAdoptedCoordinates") is not True
                or row.get("freshNativeCoordinatesMatchPinnedSource") is not True
                or row.get("freshCanonicalGeometryMatches") is not True
                or not row.get("sourceDeltaJustification")):
            raise ValueError("Independent full-source acceptance is incomplete")
        if "sourcePdfParts" in row:
            if "sourcePdf" in row:
                raise ValueError("Ambiguous source evidence")
            original_ref = gps.get("_intakeCompatibility", {}).get("rawSealedReport", pin(args.gps))
            original_qa = read(checked(original_ref))
            validate_parts(row["sourcePdfParts"], original_qa["decodedCurrentChecks"][code]["sourcePdfParts"], checked)
            source_evidence = {"sourcePdfParts": row["sourcePdfParts"]}
        else:
            source_pdf = checked(row["sourcePdf"])
            source_evidence = {"sourcePdf": row["sourcePdf"]}
        raw = set_precision(loads(checked(row["rawSourceGeometry"]).read_bytes()), 0)
        adopted = set_precision(loads(checked(row["expectedAdoptedGeometry"]).read_bytes()), 0)
        current = set_precision(replay.geometry(row["id"]), 0)
        if not raw.is_valid or not adopted.is_valid or not adopted.equals(current):
            raise ValueError("Raw source/adopted geometry is invalid or differs from the current packed body")
        raw_m, adopted_m = transform(to_metric, raw), transform(to_metric, adopted)
        metrics = {"rawSourceAreaM2": raw_m.area, "adoptedAreaM2": adopted_m.area,
                   "symmetricDifferenceAreaM2": raw_m.symmetric_difference(adopted_m).area,
                   "hausdorffDistanceM": raw_m.hausdorff_distance(adopted_m)}
        verified.append((code, row, metrics))
    args.output.mkdir(parents=True)
    frozen = args.output / "independent-gps-report.json"
    frozen.write_bytes(gps_bytes)
    write(args.output / "probes.json", {"probes": [{**row, "lng": row.get("lng", row.get("lon"))} for row in probe_values]})
    proofs = {}
    for code, row, metrics in verified:
        source_evidence = {"sourcePdfParts": row["sourcePdfParts"]} if "sourcePdfParts" in row else {"sourcePdf": row["sourcePdf"]}
        scope = {"status": "ACCEPTED_CURRENT_SOURCE_SCOPE", "id": row["id"], "officialCode": code,
                 "boundaryScope": "full-source-face", "freshRegistrationVerified": True,
                 "semanticIdentityVerified": True, "qualification": row["sourceDeltaJustification"],
                 "limits": gps["limits"], "inputs": {**source_evidence,
                     "rawSourceGeometry": row["rawSourceGeometry"], "expectedAdoptedGeometry": row["expectedAdoptedGeometry"],
                     "registrationEvidence": pin(frozen)}, "metrics": metrics,
                 "independentCheck": row, "independentReport": pin(frozen)}
        scope_path = args.output / f"{code}-source-scope.json"
        write(scope_path, scope)
        proof = {"status": "ACCEPTED_CURRENT_SOURCE_GEOMETRY", "id": row["id"], "officialCode": code,
                 "boundaryScope": "full-source-face", "geometryCrs": "EPSG:4326", "rawGeometryCrs": "EPSG:4326",
                 **source_evidence, "rawSourceGeometry": row["rawSourceGeometry"],
                 "expectedAdoptedGeometry": row["expectedAdoptedGeometry"], "sourceScopeQa": pin(scope_path)}
        proof_path = args.output / f"{code}-source-proof.json"
        write(proof_path, proof)
        proofs[code] = pin(proof_path)
    wrapper = {"status": "PASS_OFFLINE_CURRENT_SOURCE_GPS", "structuralFailures": [], "probeFailures": [],
               "decodedCurrentChecks": {code: {"featureId": row["id"], "valid": True, "sourceScopeAccepted": True}
                                        for code, row, _ in verified},
               "currentAssetGuard": {"mode": "exact-installed-baseline"},
               "probeCount": gps["probeCount"], "lookupComparisons": gps["lookupComparisons"],
               "inputs": {"currentJson": gps["inputs"]["currentJson"], "currentBin": gps["inputs"]["currentBin"],
                          "probes": pin(args.output / "probes.json"), "sourceProofs": proofs,
                          "independentReport": pin(frozen)}, "qualification": gps["qualification"], "limits": gps["limits"]}
    write(args.output / "accepted-current-gps-report.json", wrapper)
    receipt = {"status": "VALIDATED_CURRENT_SOURCE_GEOMETRY", "appliedAtUtc": datetime.now(timezone.utc).isoformat(),
               "boundaryLocalityCodes": codes, "fullSourceBoundaryLocalityCodes": codes,
               "scopedBoundaryLocalityCodes": [], "boundaryScopeRecorded": True,
               "gpsEvidence": pin(args.output / "accepted-current-gps-report.json"),
               "baselinePracticalProgress": baseline_ref, "qualification": gps["qualification"], "assetChanges": False}
    receipt_path = args.output / "current-validation-receipt.json"
    write(receipt_path, receipt)
    candidate_run = {**run, "currentGeometryValidations": [*run.get("currentGeometryValidations", []), pin(receipt_path)]}
    module_spec = importlib.util.spec_from_file_location("current_validation_report", args.report_data)
    module = importlib.util.module_from_spec(module_spec)
    module_spec.loader.exec_module(module)
    events, issues, _ = module.current_geometry_validations(candidate_run, root=args.evidence_root)
    if issues or not any(event.get("locationCodes") == codes for event in events):
        raise ValueError({"validationIssues": issues, "acceptedEventCount": len(events)})
    if args.run.read_bytes() != run_bytes or args.handoff.read_bytes() != handoff_bytes:
        raise ValueError("Concurrent ledger change; keep validation receipt for reconciliation")
    (args.output / "before-run.json").write_bytes(run_bytes)
    (args.output / "before-handoff.json").write_bytes(handoff_bytes)
    handoff = json.loads(handoff_bytes)
    handoff["currentGeometryValidations"] = candidate_run["currentGeometryValidations"]
    for path, value in ((args.run, candidate_run), (args.handoff, handoff)):
        temporary = path.with_name(path.name + ".current-validation-tmp")
        write(temporary, value)
        os.replace(temporary, path)
    print(json.dumps({"status": receipt["status"], "localitiesValidated": len(codes), "assetChanges": False,
                      "receipt": str(receipt_path)}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("gps", "run", "handoff", "output", "report-data", "evidence-root"):
        parser.add_argument("--" + name, type=Path, required=True)
    record(parser.parse_args())
