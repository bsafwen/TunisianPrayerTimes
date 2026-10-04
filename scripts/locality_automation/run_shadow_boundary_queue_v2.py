"""Supervise finite whole-native-source shadow preparation and technical checks.

Every executable phase is a synchronous original-guarded direct leaf. No
installation, recorder, publisher, source acceptance or detached descendants.
"""
import argparse
from datetime import datetime, timezone
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
from scripts.locality_automation.work_window_guard import launch_guarded, parse_utc


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    spec = read(args.manifest)
    ctl = active_control(spec)
    check_tree(spec)
    codes = [item["code"] for item in spec["items"]]
    if (args.output.exists() or spec.get("credit") != 0 or not codes or len(codes) != len(set(codes))
            or not set(codes).issubset(ctl["approvedReconciliationPool"])
            or set(codes).intersection(("425959", "425452", "425154"))):
        raise ValueError("Fresh finite remaining shadow pool; preserve excluded holds and prior work")
    expected_helpers = {"proposalHelper": "prepare_native_shadow_proposal_v2.py", "stager": "stage_boundary_patch.py",
        "gpsHelper": "audit_shadow_boundary_gps_v2.py", "jvmStageHelper": "stage_solo_jvm_audit_v2.py",
        "jvmJoinHelper": "verify_solo_jvm_audit_v2.py", "guard": "work_window_guard.py"}
    helpers = {}
    for key, name in expected_helpers.items():
        helpers[key] = checked(spec[key])
        if helpers[key].resolve() != ROOT / "scripts/locality_automation" / name:
            raise ValueError("Unexpected original or versioned direct leaf")
    args.output.mkdir()
    state = {"status": "PREPARING_FINITE_UNACCEPTED_SHADOWS", "manifest": pin(args.manifest),
        "coordinatorPid": os.getpid(), "prepared": [], "completed": [], "failed": [], "credit": 0,
        "startedAtUtc": datetime.now(timezone.utc).isoformat()}
    def checkpoint():
        temporary = args.output / "queue-state.tmp"
        with temporary.open("w", encoding="utf-8") as stream:
            json.dump(state, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, args.output / "queue-state.json")
    def phase(name, argv, directory):
        live = active_control(spec)
        check_tree(spec)
        state["currentCode"], state["currentPhase"] = directory.name, name
        checkpoint()
        receipt = directory / (name + ".execution.json")
        result = launch_guarded(live["safeSourceQaStartUtc"], live["deadlineUtc"], argv,
            minimum_remaining_seconds=120, record=receipt)
        if result["status"] != "COMPLETED" or result["exitCode"] != 0:
            state["failed"].append({"code": directory.name, "phase": name, "execution": pin(receipt)})
            state["status"] = "STOPPED_ON_PRESERVED_FAILED_SHADOW_PHASE"
            checkpoint()
            return False
        return True
    def python_phase(name, helper, manifest, output, directory):
        return phase(name, [spec["python"], "-X", "utf8", "-B", str(helpers[helper]),
            "--manifest", str(manifest), "--output", str(output)], directory)
    checkpoint()
    try:
        proposal_base = read(checked(spec["baseProposalManifest"]))
        gps_base = read(checked(spec["baseGpsManifest"]))
        jvm_base = read(checked(spec["baseJvmManifest"]))
        reused = {r["code"]: r for r in spec.get("preparedInputs", [])}
        if len(reused) != len(spec.get("preparedInputs", [])) or not set(reused).issubset(codes):
            raise ValueError("Duplicate or out-of-scope prior preparation")
        for item in spec["items"]:
            directory = args.output / item["code"]
            directory.mkdir()
            if item["code"] in reused:
                prior = reused[item["code"]]
                proposal = read(checked(prior["proposal"]))
                catalog = read(checked(prior["staged"]))
                check_tree(proposal)
                check_tree(catalog)
                for name in ("proposalExecution", "catalogExecution"):
                    old = read(checked(prior[name]))
                    if (old["status"] != "COMPLETED" or old["exitCode"] != 0 or old["timedOut"] is not False
                            or parse_utc(old["startedAtUtc"]) < parse_utc(ctl["windowStartUtc"])
                            or parse_utc(old["hardDeadlineUtc"]) != parse_utc(ctl["deadlineUtc"])):
                        raise ValueError("Prior preparation lacks original successful current-window receipts")
                if (proposal["status"] != "UNACCEPTED_ROOT_ISOLATED_HYPOTHESIS_NO_INSTALLATION"
                        or proposal["credit"] != 0 or len(proposal["patches"]) != 1
                        or proposal["patches"][0]["officialCode"] != item["code"]
                        or proposal["baseCatalogMetadata"] != proposal_base["metadata"]
                        or proposal["baseCatalogBinary"] != proposal_base["binary"]
                        or catalog["status"] != "STAGED_REQUIRES_GEOGRAPHIC_AND_GPS_REVIEW"
                        or catalog["proposal"] != prior["proposal"]):
                    raise ValueError("Original complete isolated preparation differs")
                state["prepared"].append({**prior, "reusedImmutablePreparation": True})
                checkpoint()
                continue
            proposal_spec = {**proposal_base, "code": item["code"], "nativeInput": item["nativeInput"], "nativeReplay": item["nativeReplay"]}
            path = directory / "proposal-manifest.json"
            write_new(path, proposal_spec)
            proposal = directory / "proposal"
            if not python_phase("proposal", "proposalHelper", path, proposal, directory):
                return
            catalog = directory / "catalog"
            if not phase("catalog", [spec["python"], "-X", "utf8", "-B", str(helpers["stager"]),
                    "--proposal", str(proposal / "proposal.json"), "--output", str(catalog)], directory):
                return
            state["prepared"].append({"code": item["code"], "proposal": pin(proposal / "proposal.json"), "staged": pin(catalog / "stage-report.json")})
            checkpoint()
        adoption_path = Path(spec["durableGpsAdoptionPath"])
        state["status"] = "WAITING_FOR_EXACT_FULL_REPLAY_DURABILITY_VERIFICATION"
        checkpoint()
        while not adoption_path.exists():
            live = active_control(spec)
            if datetime.now(timezone.utc) >= parse_utc(live["safeSourceQaStartUtc"]):
                state["status"] = "STOPPED_AT_SOURCE_START_CUTOFF"
                return
            time.sleep(1)
        adoption = read(adoption_path)
        if (adoption["status"] != "VERIFIED_BYTE_IDENTICAL_FULL_SHADOW_GPS_DURABILITY_ADOPTION"
                or adoption["credit"] != 0 or adoption["helper"] != spec["gpsHelper"]):
            raise ValueError("Exact whole-cohort journal verification has not released this helper")
        check_tree(adoption)
        state["durableGpsAdoption"] = pin(adoption_path)
        state["status"] = "RUNNING_FINITE_SOLO_SHADOW_TECHNICAL_QUEUE"
        checkpoint()
        for prepared in state["prepared"]:
            code = prepared["code"]
            directory = args.output / code
            gps_spec = {**gps_base, "code": code, "proposal": prepared["proposal"], "staged": prepared["staged"]}
            path = directory / "gps-manifest.json"
            write_new(path, gps_spec)
            gps_output = directory / "gps"
            if not python_phase("gps", "gpsHelper", path, gps_output, directory):
                return
            model_path = gps_output / "report.json"
            model = read(model_path)
            if (model["credit"] != 0 or model["independentQaPassed"] is not False
                    or model["sourceScopeAccepted"] is not False or model["liveAssetsChanged"] is not False
                    or model["indexedExhaustiveFailures"]):
                state["failed"].append({"code": code, "phase": "numeric_hold", "model": pin(model_path)})
                state["status"] = "STOPPED_ON_PRESERVED_SHADOW_NUMERIC_HOLD"
                return
            stage = read(checked(prepared["staged"]))
            jvm_spec = {**jvm_base, "code": code, "model": pin(model_path), "catalogMode": "isolated-shadow",
                "metadata": stage["stagedMetadata"], "binary": stage["stagedBinary"],
                "unresolvedOracleDiagnosticOnly": bool(model["independentFormulaDisagreements"])}
            path = directory / "jvm-stage-manifest.json"
            write_new(path, jvm_spec)
            jvm_output = directory / "jvm"
            if not python_phase("jvm-stage", "jvmStageHelper", path, jvm_output, directory):
                return
            binding_path = jvm_output / "jvm-input-binding.json"
            binding = read(binding_path)
            if not phase("java", binding["argv"], directory):
                return
            join_spec = {key: spec[key] for key in ("control", "iteration", "owner", "windowStartUtc", "deadlineUtc", "credit")}
            join_spec.update(binding=pin(binding_path), javaExecution=pin(directory / "java.execution.json"),
                actualJvm=pin(binding["resultPath"]), helper=spec["jvmJoinHelper"])
            path = directory / "jvm-join-manifest.json"
            write_new(path, join_spec)
            joined = directory / "joined.json"
            if not python_phase("jvm-join", "jvmJoinHelper", path, joined, directory):
                return
            result = read(joined)
            if result["failures"] or result["credit"] != 0 or result["independentQaPassed"] is not False:
                raise ValueError("Unresolved actual JVM comparison or incorrect authority")
            state["completed"].append({"code": code, "model": pin(model_path), "joined": pin(joined),
                "probes": model["probeCount"], "comparisonsPerCatalog": model["counts"]["afterComparisons"],
                "originalOracleDisagreements": len(model["independentFormulaDisagreements"]), "geographicCredit": 0})
            checkpoint()
            print(json.dumps({"code": code, "completed": len(state["completed"]), "total": len(codes), "credit": 0}), flush=True)
        state["status"] = "FINITE_SOLO_SHADOW_TECHNICAL_QUEUE_COMPLETE_NO_ACCEPTANCE"
    except BaseException as error:
        state["status"] = "INTERRUPTED_OR_FAILED_SOLO_SHADOW_QUEUE"
        state["error"] = type(error).__name__ + ": " + str(error)
        raise
    finally:
        state["finishedAtUtc"] = datetime.now(timezone.utc).isoformat()
        checkpoint()
        print(json.dumps({"status": state["status"], "prepared": len(state["prepared"]), "completed": len(state["completed"]), "credit": 0}), flush=True)


if __name__ == "__main__":
    main()
