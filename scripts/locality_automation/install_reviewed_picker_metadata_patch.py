"""Install source-proved self-sector picker metadata with zero geographic credit."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import sys

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))
from scripts.locality_automation.install_reviewed_boundary_patch import checked, check_tree, pin, read, sha, write
from scripts.locality_automation.installer import install
from scripts.locality_automation.stage_boundary_picker_patch import validate_picker_separation


def apply(args):
    if args.output.exists() or args.transaction.exists():
        raise ValueError("Use fresh receipt and transaction directories")
    run_bytes, handoff_bytes, gps_bytes = args.run.read_bytes(), args.handoff.read_bytes(), args.gps.read_bytes()
    run = json.loads(run_bytes)
    previous_ref = run["practicalProgress"]
    previous = read(checked(previous_ref))
    gps = json.loads(gps_bytes.decode("utf-8-sig"))
    if (gps.get("status") != "PASS_OFFLINE_STAGED_METADATA_GPS" or gps.get("structuralFailures") != []
            or gps.get("probeFailures") != [] or gps.get("decodedPatchChecks") != {}
            or not gps.get("probeCount", 0) > 0 or not gps.get("lookupComparisons", 0) > 0):
        raise ValueError("Independent metadata/search/GPS audit did not pass")
    check_tree(gps["inputs"])
    inputs = gps["inputs"]
    proposal = read(checked(inputs["proposal"]))
    if proposal.get("status") != "READY_METADATA_CORRECTIONS":
        raise ValueError("Not a metadata-only proposal")
    checks = gps.get("metadataPatchChecks", {})
    codes = sorted(str(patch["officialCode"]) for patch in proposal["patches"])
    if (not codes or len(codes) != len(set(codes)) or set(codes) != set(checks)
            or any(row.get("valid") is not True or row.get("equalProposedMetadata") is not True for row in checks.values())):
        raise ValueError("Every metadata patch needs an exact independent check")
    before = read(checked(inputs["beforeJson"]))
    after = read(checked(inputs["afterJson"]))
    if inputs["beforeBin"]["sha256"] != inputs["afterBin"]["sha256"]:
        raise ValueError("Metadata-only patch changed packed geometry")
    previous_gps = read(checked(previous["gpsEvidence"]))
    if (read(checked(previous_gps["inputs"]["afterJson"])) != before
            or previous_gps["inputs"]["afterBin"]["sha256"] != inputs["beforeBin"]["sha256"]):
        raise ValueError("The reviewed practical catalog is not this metadata baseline")
    before_rows = {row["id"]: row for row in before["features"]}
    after_rows = {row["id"]: row for row in after["features"]}
    changed_ids, changes = set(), []
    allowed = {"pickerGroupId"}
    if set(before_rows) != set(after_rows):
        raise ValueError("Locality identities changed")
    for patch in proposal["patches"]:
        ident = patch["id"]
        if ident in changed_ids or set(patch) & {"geometry", "boundaryScope", "sourcePdf"}:
            raise ValueError("Invalid metadata patch identity or boundary fields")
        values = patch["proposedMetadata"]
        if not set(values) <= allowed:
            raise ValueError("Only source-proved self-sector picker separation may change")
        validate_picker_separation(before_rows[ident], patch, values)
        expected = {**before_rows[ident], **values}
        if after_rows[ident] != expected:
            raise ValueError("Staged record differs from the proposed metadata")
        fields = {key: {"before": before_rows[ident].get(key), "after": value}
                  for key, value in values.items() if before_rows[ident].get(key) != value}
        if not fields:
            raise ValueError("Unchanged metadata patch")
        changed_ids.add(ident)
        changes.append({"id": ident, "officialCode": str(patch["officialCode"]), "fields": fields})
    if any(after_rows[ident] != row for ident, row in before_rows.items() if ident not in changed_ids):
        raise ValueError("Unrelated locality metadata changed")
    if {k: v for k, v in before.items() if k != "features"} != {k: v for k, v in after.items() if k != "features"}:
        raise ValueError("Catalog geometry/source/index metadata changed")
    live_names = REPO / "android-app/app/src/main/assets/locality-display-names.json"
    name_ref = previous["nameEvidence"]
    names = read(checked(name_ref))
    if (sha(live_names) != previous["installedAssets"]["locality-display-names.json"]["sha256"]
            or read(live_names) != read(checked(names["after"]))):
        raise ValueError("Previously reviewed display/search overrides changed")
    if sha(REPO / "android-app/app/src/main/assets/neighborhoods.bin") != inputs["beforeBin"]["sha256"]:
        raise ValueError("Live geometry no longer matches this baseline")
    args.output.mkdir(parents=True)
    frozen = args.output / "accepted-gps-report.json"
    frozen.write_bytes(gps_bytes)
    (args.output / "before-run.json").write_bytes(run_bytes)
    (args.output / "before-handoff.json").write_bytes(handoff_bytes)
    qualification = proposal["qualification"]
    dest = "android-app/app/src/main/assets/neighborhoods.json"
    validation = {"status": "READY_TO_INSTALL", "qualification": qualification, "issues": [], "unresolvedCaseIds": [],
                  "files": [{"sourceSha256": inputs["afterJson"]["sha256"], "destination": dest,
                             "beforeSha256": inputs["beforeJson"]["sha256"]}], "gpsEvidence": pin(frozen)}
    write(args.output / "validation.json", validation)
    manifest = {"schemaVersion": 1, "validation": pin(args.output / "validation.json"),
                "files": [{"source": inputs["afterJson"], "destination": dest,
                           "beforeSha256": inputs["beforeJson"]["sha256"]}]}
    write(args.output / "manifest.json", manifest)
    result = install(manifest, str(REPO), str(args.transaction), [], None)
    write(args.output / "installer-receipt.json", result)
    if result["status"] != "INSTALLED":
        raise ValueError(result)
    assets = {name: pin(REPO / ("android-app/app/src/main/assets/" + name))
              for name in ("neighborhoods.json", "neighborhoods.bin", "locality-display-names.json")}
    if assets["neighborhoods.json"]["sha256"] != inputs["afterJson"]["sha256"] or assets["neighborhoods.bin"]["sha256"] != inputs["afterBin"]["sha256"]:
        raise ValueError("Installed assets differ from reviewed identity metadata")
    practical = {"schemaVersion": 1, "status": "INSTALLED_METADATA_CORRECTIONS",
                 "appliedAtUtc": datetime.now(timezone.utc).isoformat(), "qualification": qualification,
                 "boundaryLocalityCodes": [], "boundaryLocalitiesCorrected": 0,
                 "fullSourceBoundaryLocalityCodes": [], "scopedBoundaryLocalityCodes": [], "boundaryScopeRecorded": True,
                 "metadataLocalityCodes": codes, "boundaryMetadataChanges": changes,
                 "cumulativeBoundaryLocalityCodes": previous.get("cumulativeBoundaryLocalityCodes", previous["boundaryLocalityCodes"]),
                 "previousPracticalProgress": previous_ref, "nameEvidence": name_ref,
                 "displayNamesImproved": names["displayRenames"], "officialSearchAliasesAdded": names["aliasOnlyUpdates"],
                 "gpsEvidence": pin(frozen), "installerReceipt": pin(args.output / "installer-receipt.json"),
                 "manifest": pin(args.output / "manifest.json"), "installedAssets": assets,
                 "gpsProbeCount": gps["probeCount"], "gpsLookupComparisons": gps["lookupComparisons"],
                 "wrongOwnerProbeFailures": 0, "sourceSupportedImprovedComparisons": 0,
                 "preservedHistoricalInputs": {"metadata": inputs["beforeJson"], "binary": inputs["beforeBin"]},
                 "androidCompiled": False, "apkGenerated": False, "deviceVerification": False, "proxyChanged": False}
    write(args.output / "practical-receipt.json", practical)
    if args.run.read_bytes() != run_bytes or args.handoff.read_bytes() != handoff_bytes:
        raise ValueError("Metadata installed; concurrent ledger edit requires receipt reconciliation")
    handoff = json.loads(handoff_bytes)
    run["installedCatalog"] = assets["neighborhoods.json"]
    run["practicalProgress"] = handoff["practicalProgress"] = pin(args.output / "practical-receipt.json")
    for path, value in ((args.run, run), (args.handoff, handoff)):
        temporary = path.with_name(path.name + ".metadata-tmp")
        write(temporary, value)
        os.replace(temporary, path)
    print(json.dumps({"status": result["status"], "metadataLocalityCount": len(codes),
                      "geometryChanged": False, "receipt": str(args.output / "practical-receipt.json")}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("gps", "run", "handoff", "output", "transaction"):
        parser.add_argument("--" + name, type=Path, required=True)
    apply(parser.parse_args())
