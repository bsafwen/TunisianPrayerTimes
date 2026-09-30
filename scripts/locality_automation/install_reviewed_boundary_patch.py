"""Install an independently replayed boundary patch and record practical results.

Existing staged inputs and earlier practical outcomes remain immutable. The
installer verifies exact live/staged hashes and supplies backups and rollback.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import sys

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))
from scripts.locality_automation.installer import install


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def pin(path):
    return {"file": str(path.resolve()), "sha256": sha(path)}


def checked(ref):
    path = Path(ref["file"])
    if sha(path) != ref["sha256"]:
        raise ValueError(f"Pinned evidence changed: {path}")
    return path


def check_tree(value):
    if isinstance(value, dict):
        if "file" in value and "sha256" in value:
            checked(value)
        else:
            for child in value.values():
                check_tree(child)
    elif isinstance(value, list):
        for child in value:
            check_tree(child)


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2)+"\n", encoding="utf-8", newline="\n")


def apply(args):
    before_run, before_handoff = args.run.read_bytes(), args.handoff.read_bytes()
    if args.transaction.exists():
        raise ValueError("Transaction already exists; inspect instead of reinstalling")
    if args.output.exists():
        expected = {"before-run.json": before_run, "before-handoff.json": before_handoff}
        if {p.name for p in args.output.iterdir()} != set(expected) or any(
                (args.output / name).read_bytes() != value for name, value in expected.items()):
            raise ValueError("Receipt directory already contains results; do not overwrite")
    run = json.loads(before_run)
    previous_ref = run["practicalProgress"]
    previous = read(checked(previous_ref))
    gps = read(args.gps)
    if not str(gps.get("status", "")).startswith("PASS_OFFLINE_STAGED_") or gps.get("structuralFailures") != [] or gps.get("probeFailures") != []:
        raise ValueError("Independent boundary/GPS replay did not pass")
    check_tree(gps["inputs"])
    inputs = gps["inputs"]
    codes = sorted(gps["decodedPatchChecks"])
    if not codes or any(not c["valid"] or not c["equalFinalPatchCoordinates"] for c in gps["decodedPatchChecks"].values()):
        raise ValueError("Decoded candidate differs from the reviewed boundary")
    previous_gps = read(checked(previous["gpsEvidence"]))
    if read(checked(previous_gps["inputs"]["afterJson"])) != read(checked(inputs["beforeJson"])):
        raise ValueError("Earlier practical metadata is not the new patch baseline")
    if previous_gps["inputs"]["afterBin"]["sha256"] != inputs["beforeBin"]["sha256"]:
        raise ValueError("Earlier practical binary is not the new patch baseline")
    name_ref = previous["nameEvidence"]
    names = read(checked(name_ref))
    live_names = REPO / "android-app/app/src/main/assets/locality-display-names.json"
    if sha(live_names) != previous["installedAssets"]["locality-display-names.json"]["sha256"] or read(live_names) != read(checked(names["after"])):
        raise ValueError("Previously reviewed name/search results changed")
    specs = [(inputs["afterJson"], "android-app/app/src/main/assets/neighborhoods.json", inputs["beforeJson"]["sha256"]),
             (inputs["afterBin"], "android-app/app/src/main/assets/neighborhoods.bin", inputs["beforeBin"]["sha256"])]
    proposal = read(checked(inputs["proposal"]))
    qualification = proposal.get("qualification") or "\n".join(dict.fromkeys(
        patch.get("qualification", "") for patch in proposal.get("patches", [])))
    if not isinstance(qualification, str) or not qualification.strip():
        raise ValueError("Proposal must explicitly describe the correction scope")
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "before-run.json").write_bytes(before_run)
    (args.output / "before-handoff.json").write_bytes(before_handoff)
    validation = {"status": "READY_TO_INSTALL", "qualification": qualification,
                  "files": [{"sourceSha256": source["sha256"], "destination": dest, "beforeSha256": expected}
                            for source, dest, expected in specs], "issues": [], "unresolvedCaseIds": [],
                  "gpsEvidence": pin(args.gps)}
    write(args.output / "validation.json", validation)
    manifest = {"schemaVersion": 1, "validation": pin(args.output / "validation.json"),
                "files": [{"source": source, "destination": dest, "beforeSha256": expected} for source, dest, expected in specs]}
    write(args.output / "manifest.json", manifest)
    result = install(manifest, str(REPO), str(args.transaction), [], None)
    write(args.output / "installer-receipt.json", result)
    if result["status"] != "INSTALLED":
        raise ValueError(result)
    assets = {name: pin(REPO / ("android-app/app/src/main/assets/" + name))
              for name in ("neighborhoods.json", "neighborhoods.bin", "locality-display-names.json")}
    if assets["neighborhoods.json"]["sha256"] != inputs["afterJson"]["sha256"] or assets["neighborhoods.bin"]["sha256"] != inputs["afterBin"]["sha256"]:
        raise ValueError("Installed pair differs from the reviewed pair")
    prior_codes = previous.get("cumulativeBoundaryLocalityCodes", previous["boundaryLocalityCodes"])
    practical = {"schemaVersion": 1, "status": "INSTALLED_SCOPED_PRACTICAL_CORRECTIONS",
                 "appliedAtUtc": datetime.now(timezone.utc).isoformat(), "qualification": qualification,
                 "boundaryLocalityCodes": codes, "boundaryLocalitiesCorrected": len(codes),
                 "cumulativeBoundaryLocalityCodes": sorted(set(prior_codes) | set(codes)),
                 "previousPracticalProgress": previous_ref,
                 "displayNamesImproved": names["displayRenames"], "officialSearchAliasesAdded": names["aliasOnlyUpdates"],
                 "nameEvidence": name_ref, "gpsEvidence": pin(args.gps), "installerReceipt": pin(args.output / "installer-receipt.json"),
                 "manifest": pin(args.output / "manifest.json"), "installedAssets": assets,
                 "gpsProbeCount": gps["probeCount"], "gpsLookupComparisons": gps["lookupComparisons"],
                 "sourceSupportedImprovedComparisons": gps["totals"]["source_supported_wrong_to_correct"],
                 "wrongOwnerProbeFailures": len(gps["probeFailures"]),
                 "preservedHistoricalInputs": {"metadata": inputs["beforeJson"], "binary": inputs["beforeBin"]},
                 "androidCompiled": False, "apkGenerated": False, "deviceVerification": False, "proxyChanged": False}
    write(args.output / "practical-receipt.json", practical)
    if args.run.read_bytes() != before_run or args.handoff.read_bytes() != before_handoff:
        raise ValueError("Data installed but concurrent ledger edit detected; preserve receipt for reconciliation")
    handoff = json.loads(before_handoff)
    run["installedCatalog"] = assets["neighborhoods.json"]
    run["practicalProgress"] = handoff["practicalProgress"] = pin(args.output / "practical-receipt.json")
    for path, value in ((args.run, run), (args.handoff, handoff)):
        temporary = path.with_name(path.name + ".boundary-tmp")
        write(temporary, value)
        os.replace(temporary, path)
    print(json.dumps({"status": result["status"], "codes": codes,
                      "cumulativeCodes": practical["cumulativeBoundaryLocalityCodes"], "receipt": str(args.output / "practical-receipt.json")}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("gps", "run", "handoff", "output", "transaction"):
        parser.add_argument("--" + name, type=Path, required=True)
    apply(parser.parse_args())
