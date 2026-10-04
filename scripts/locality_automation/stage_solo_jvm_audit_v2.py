"""Stage pinned existing JVM methods against solo technical GPS requests.

No JVM/compiler run here. Root remains the actual actor; no disjoint QA claim.
"""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import active_control, checked, pin, read
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree
from scripts.locality_automation.audit_prepared_osm_graphs import write_new
from scripts.locality_automation.work_window_guard import parse_utc


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    spec = read(args.manifest)
    control = active_control(spec)
    if datetime.now(timezone.utc) >= parse_utc(control["safeSourceQaStartUtc"]):
        raise ValueError("Source start cutoff reached")
    catalog_mode = spec.get("catalogMode", "current")
    if catalog_mode not in ("current", "isolated-shadow"):
        raise ValueError("Unknown explicit catalogue mode")
    pool = control["approvedCycle15PreparationPool"] if catalog_mode == "current" else control["approvedReconciliationPool"]
    if args.output.exists() or spec.get("credit") != 0 or spec["code"] not in pool:
        raise ValueError("Fresh finite zero-credit JVM staging required")
    check_tree(spec)
    model = read(checked(spec["model"]))
    if catalog_mode == "isolated-shadow":
        if model["status"] != "SOLO_SHADOW_WHOLE_SOURCE_GPS_DIAGNOSTIC_NO_INSTALLATION" or model["liveAssetsChanged"] is not False:
            raise ValueError("Shadow numeric authority differs")
        source_spec = read(checked(read(checked(model["inputGate"]))["manifest"]))
        stage = read(checked(source_spec["staged"]))
        if (spec["metadata"] != stage["stagedMetadata"] or spec["binary"] != stage["stagedBinary"]
                or source_spec["beforeMetadata"] != spec["liveMetadata"] or source_spec["beforeBinary"] != spec["liveBinary"]):
            raise ValueError("Shadow staged assets or live base differ")
    unresolved_diagnostic = spec.get("unresolvedOracleDiagnosticOnly", False)
    if unresolved_diagnostic is not False and (unresolved_diagnostic is not True or catalog_mode != "isolated-shadow"):
        raise ValueError("Unresolved formula diagnosis is restricted to isolated unaccepted shadows")
    if (model["officialCode"] != spec["code"] or model["independentQaPassed"] is not False
            or model["credit"] != 0 or model["indexedExhaustiveFailures"]
            or (model["independentFormulaDisagreements"] and not unresolved_diagnostic)
            or model["actualJvmExecuted"] is not False):
        raise ValueError("Solo numeric phase has unresolved discrepancies or incorrect authority")
    preparation = read(checked(spec["geometryPreparation"]))
    runtime = read(checked(spec["runtimeSourceReview"]))
    classes = list(preparation["currentClassPins"].values()) + runtime["compiledGeometryRuntimeFiles"]
    if len(classes) != 65:
        raise ValueError("Full reviewed pure core runtime requires exactly65 pinned class files")
    for reference in classes + preparation["runtimeJarPins"]:
        checked(reference)
    for binding in spec["currentCoreBindings"]:
        snapshot = checked(binding["snapshot"])
        current = checked(binding["current"])
        if snapshot.read_bytes() != current.read_bytes():
            raise ValueError("Reviewed core source differs from current source")
    old = read(checked(spec["priorJavaReceipt"]))
    if old["status"] != "COMPLETED" or old["exitCode"] != 0:
        raise ValueError("Historical JVM execution did not complete")
    argv = old["argv"]
    if argv[1] != "-cp" or Path(argv[3]).resolve() != checked(spec["javaAdapter"]).resolve():
        raise ValueError("Unexpected retained Java launch shape")
    if not Path(argv[0]).is_file():
        raise ValueError("Retained Java executable unavailable")
    requests = checked(model["actualJvmRequests"])
    probes = read(checked(model["probes"]))["probes"]
    payload = read(requests)
    if len(payload) != len(probes) or len(probes) != model["probeCount"]:
        raise ValueError("Whole original request cohort differs")
    if payload != [{key: point[key] for key in ("name", "lat", "lng", "expectedSourceId")} for point in probes]:
        raise ValueError("Actual JVM requests changed source coordinates or expectations")
    args.output.mkdir()
    assets = args.output / "assets"
    assets.mkdir()
    saved = {}
    for name, key in (("neighborhoods.json", "metadata"), ("neighborhoods.bin", "binary"),
                      ("locality-display-names.json", "names"), ("gouvernorats.json", "governorates")):
        source = checked(spec[key])
        target = assets / name
        with target.open("xb") as stream:
            stream.write(source.read_bytes())
        actual = pin(target)
        if actual["sha256"] != spec[key]["sha256"]:
            raise ValueError("Immutable current asset copy differs")
        saved[name] = actual
    output = args.output / "actual-jvm.json"
    command = [argv[0], "-cp", argv[2], argv[3], str(assets), str(requests),
               "C17-solo-" + catalog_mode + "-" + spec["code"], str(output)]
    binding = {"status": "PINNED_SOLO_CURRENT_JVM_DIRECT_LEAF_NO_ACCEPTANCE", "code": spec["code"],
        "manifest": pin(args.manifest), "model": spec["model"], "argv": command, "catalogMode": catalog_mode,
        "resultPath": str(output), "currentAssets": saved, "retainedClasses": classes,
        "retainedRuntimeJars": preparation["runtimeJarPins"], "sourceHarness": spec["javaAdapter"],
        "currentCoreBindings": spec["currentCoreBindings"], "actualJvmExecuted": False,
        "fullAndroidUiOrGpsProviderServicesExecuted": False, "independentQaPassed": False,
        "credit": 0, "createdAtUtc": datetime.now(timezone.utc).isoformat()}
    if catalog_mode == "isolated-shadow":
        binding["status"] = "PINNED_SOLO_SHADOW_JVM_DIRECT_LEAF_NO_INSTALLATION"
        binding["liveMetadata"] = spec["liveMetadata"]
        binding["liveBinary"] = spec["liveBinary"]
        binding["unresolvedOracleDiagnosticOnly"] = unresolved_diagnostic
        binding["unresolvedFormulaDisagreementCount"] = len(model["independentFormulaDisagreements"])
        if unresolved_diagnostic:
            binding["status"] = "PINNED_UNRESOLVED_SHADOW_JVM_DIAGNOSTIC_NO_INSTALLATION"
    check_tree(spec)
    reference = write_new(args.output / "jvm-input-binding.json", binding)
    print(json.dumps({"binding": reference, "requests": len(payload), "classes": len(classes), "credit": 0}))


if __name__ == "__main__":
    main()
