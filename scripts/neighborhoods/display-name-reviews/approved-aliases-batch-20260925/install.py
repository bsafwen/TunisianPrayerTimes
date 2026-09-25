"""Install reviewed feature-scoped Arabic aliases into locality-display-names.json.

The script is pinned to the exact reviewed decision file and the live asset bytes
observed before installation. It never edits neighborhoods.json or other assets.
"""
from __future__ import annotations

import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
TASK = Path(r"C:\Users\barou\Documents\Codex\2026-09-06\the-android-app-app-currently-allows")
ASSETS = REPO / "android-app/app/src/main/assets"
CATALOG = ASSETS / "neighborhoods.json"
DISPLAY = ASSETS / "locality-display-names.json"
DECISIONS = TASK / "work/official-imada-evidence-20260924-v2/release-integration/alias-candidate-decision-review-20260925.json"
BACKUP = HERE / "before-locality-display-names.json"
RECEIPT = HERE / "install-receipt.json"
EXPECTED = {
    "catalog": "d920c17fe018db9f77cf3bc747ac8b11336ae2c62c74fcb877acaaa4088c4c4f",
    "display": "606ab74455c124b52a635aebb5adf36b6055edfa16fc2c0fd35f5ef88c7474a2",
    "decisions": "f27dfbd0e5aa82be86232b3e3142afd067e49d89b62d7fa390e8e57ae4e8555e",
}
EXPECTED_SAFE_COUNT = 32
EXPECTED_HOLD_COUNT = 7


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def gov_for_display_id(identifier: str, feature_by_id: dict, delegation_govs: dict) -> set:
    feature = feature_by_id.get(identifier)
    if feature is not None:
        return {feature["governorateId"]}
    if identifier.startswith("delegation:"):
        return set(delegation_govs.get(identifier.split(":", 1)[1], set()))
    return set()


def main() -> None:
    catalog_bytes = CATALOG.read_bytes()
    display_before = DISPLAY.read_bytes()
    decision_bytes = DECISIONS.read_bytes()
    actual = {
        "catalog": digest(catalog_bytes),
        "display": digest(display_before),
        "decisions": digest(decision_bytes),
    }
    if RECEIPT.is_file() and actual["display"] == json.loads(RECEIPT.read_text(encoding="utf-8"))["installedDisplaySha256"]:
        print(json.dumps({"status": "already_installed", "receipt": str(RECEIPT)}))
        return
    if actual != EXPECTED:
        raise ValueError(f"Pinned input hash mismatch: expected={EXPECTED}, actual={actual}")
    if BACKUP.exists() or RECEIPT.exists():
        raise FileExistsError("Backup or receipt exists without matching installed output")

    catalog = json.loads(catalog_bytes)
    display = json.loads(display_before)
    decisions = json.loads(decision_bytes)
    if len(catalog["features"]) != 3474:
        raise ValueError("Unexpected catalog feature count")
    feature_by_id = {row["id"]: row for row in catalog["features"]}
    if len(feature_by_id) != len(catalog["features"]):
        raise ValueError("Duplicate feature IDs in catalog")
    if display.get("schemaVersion") != 1 or not isinstance(display.get("names"), list):
        raise ValueError("Unexpected display asset schema")
    if len({row["id"] for row in display["names"]}) != len(display["names"]):
        raise ValueError("Duplicate IDs in locality display asset")
    if decisions.get("counts", {}).get("candidateRows") != 39:
        raise ValueError("Unexpected decision row count")
    if len(decisions.get("rows", [])) != 39 or len({row["caseId"] for row in decisions["rows"]}) != 39:
        raise ValueError("Decision file must contain 39 unique exact cases")

    safe = [row for row in decisions["rows"] if row.get("decision") == "safe_text_only_alias"]
    held = [row for row in decisions["rows"] if row.get("decision") == "hold"]
    if len(safe) != EXPECTED_SAFE_COUNT or len(held) != EXPECTED_HOLD_COUNT:
        raise ValueError(f"Expected {EXPECTED_SAFE_COUNT} safe and {EXPECTED_HOLD_COUNT} held cases")
    if decisions.get("counts", {}).get("safeTextOnlyAliases") != EXPECTED_SAFE_COUNT:
        raise ValueError("Decision safe count disagrees with rows")

    names = display["names"]
    display_by_id = {row["id"]: row for row in names}
    delegation_govs: dict[str, set] = {}
    for feature in catalog["features"]:
        delegation_id = feature.get("delegationId")
        if delegation_id is not None:
            delegation_govs.setdefault(str(delegation_id), set()).add(feature["governorateId"])

    # Snapshot held rows. The loop below is restricted to the safe set.
    held_ids = {row["featureId"] for row in held}
    if len(held_ids) != EXPECTED_HOLD_COUNT:
        raise ValueError("Held cases must target seven distinct features")
    held_before = {identifier: display_by_id.get(identifier) for identifier in held_ids}

    added = []
    already_present = []
    touched_ids = set()
    proposed_by_gov: dict[tuple, str] = {}
    for row in safe:
        identifier = row["featureId"]
        alias = row["proposedAlias"]
        feature = feature_by_id.get(identifier)
        if feature is None:
            raise ValueError(f"Reviewed feature ID missing from catalog: {identifier}")
        if identifier in touched_ids or identifier in held_ids:
            raise ValueError(f"Duplicate approved ID or overlap with held ID: {identifier}")
        touched_ids.add(identifier)
        current = row["appCurrent"]
        if (feature["name"] != current["name"]
                or feature.get("parentName") != current.get("parentName")
                or feature.get("governorateId") != current.get("governorateId")):
            raise ValueError(f"Current name/parent/governorate drift: {identifier}")
        if (row.get("decision") != "safe_text_only_alias"
                or row["evidence"].get("uniqueAppInsCodeLink") is not True
                or row["evidence"].get("parentMatches") is not True
                or row["evidence"].get("ministryInsNameNormalizedEqual") is not True
                or row["evidence"].get("ministryInsDelegationNormalizedEqual") is not True):
            raise ValueError(f"Insufficient identity review evidence: {row['caseId']}")
        if row["ministryName"] != alias:
            raise ValueError(f"Proposed alias is not the exact Ministry spelling: {row['caseId']}")

        gov_id = feature["governorateId"]
        batch_key = (gov_id, alias)
        if batch_key in proposed_by_gov and proposed_by_gov[batch_key] != identifier:
            raise ValueError(f"Two approved proposals collide within governorate {gov_id}: {alias}")
        proposed_by_gov[batch_key] = identifier

        catalog_collisions = [other["id"] for other in catalog["features"]
                              if other["id"] != identifier
                              and other.get("governorateId") == gov_id
                              and alias in [other.get("name"), *other.get("aliases", [])]]
        if catalog_collisions:
            raise ValueError(f"Catalog same-governorate alias collision: {identifier}: {catalog_collisions}")

        display_collisions = []
        record = display_by_id.get(identifier)
        for other in names:
            if other["id"] == identifier:
                continue
            if alias not in [other.get("nameAr"), *other.get("searchAliases", [])]:
                continue
            if gov_id in gov_for_display_id(other["id"], feature_by_id, delegation_govs):
                display_collisions.append(other["id"])
        if display_collisions:
            raise ValueError(f"Display same-governorate alias collision: {identifier}: {display_collisions}")

        if record is None:
            record = {"id": identifier, "nameAr": feature["name"], "searchAliases": []}
            names.append(record)
            display_by_id[identifier] = record
        elif record.get("nameAr") != feature["name"]:
            raise ValueError(f"Existing display primary label differs from current feature: {identifier}")
        aliases = record.get("searchAliases")
        if not isinstance(aliases, list) or len(set(aliases)) != len(aliases):
            raise ValueError(f"Invalid or duplicate searchAliases on display record: {identifier}")
        if alias in aliases:
            already_present.append({"id": identifier, "alias": alias, "caseId": row["caseId"]})
        else:
            aliases.append(alias)
            added.append({"id": identifier, "alias": alias, "caseId": row["caseId"]})

    if len(touched_ids) != EXPECTED_SAFE_COUNT:
        raise ValueError("Not every approved feature ID was checked")
    if len(added) + len(already_present) != EXPECTED_SAFE_COUNT:
        raise ValueError("Approved alias accounting does not reconcile")
    for identifier, before_record in held_before.items():
        if display_by_id.get(identifier) != before_record:
            raise ValueError(f"Held display row changed: {identifier}")

    names.sort(key=lambda row: row["id"])
    output = json.dumps(display, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    BACKUP.write_bytes(display_before)
    temp = DISPLAY.with_name(DISPLAY.name + ".approved-alias-batch.tmp")
    try:
        temp.write_bytes(output)
        os.replace(temp, DISPLAY)
        installed_bytes = DISPLAY.read_bytes()
        if digest(installed_bytes) != digest(output):
            raise ValueError("Atomic display write verification failed")
        installed = json.loads(installed_bytes)
        if len({row["id"] for row in installed["names"]}) != len(installed["names"]):
            raise ValueError("Installed display asset contains duplicate IDs")
        installed_by_id = {row["id"]: row for row in installed["names"]}
        for row in safe:
            target = installed_by_id[row["featureId"]]
            if row["proposedAlias"] not in target["searchAliases"]:
                raise ValueError(f"Installed alias missing: {row['featureId']}")
        for identifier, before_record in held_before.items():
            if installed_by_id.get(identifier) != before_record:
                raise ValueError(f"Held display row differs after install: {identifier}")
        if digest(CATALOG.read_bytes()) != EXPECTED["catalog"]:
            raise ValueError("Catalog changed during display installation")
    except Exception:
        temp.unlink(missing_ok=True)
        DISPLAY.write_bytes(display_before)
        raise

    receipt = {
        "status": "installed_reviewed_feature_scoped_arabic_aliases",
        "installedAtUtc": datetime.now(timezone.utc).isoformat(),
        "sourceSha256": EXPECTED,
        "installedDisplaySha256": digest(output),
        "approvedSafeCases": EXPECTED_SAFE_COUNT,
        "aliasesAdded": len(added),
        "alreadyPresentOnTarget": already_present,
        "aliasesAddedRows": added,
        "heldCases": EXPECTED_HOLD_COUNT,
        "heldCaseIds": [row["caseId"] for row in held],
        "heldDisplayRowsUnchanged": True,
        "catalogGeometryPickerGroupsPrayerMappingChanged": False,
        "catalogSha256After": digest(CATALOG.read_bytes()),
        "featureCount": len(catalog["features"]),
        "displayRecordCountBefore": len(json.loads(display_before)["names"]),
        "displayRecordCountAfter": len(installed["names"]),
    }
    RECEIPT.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": receipt["status"], "aliasesAdded": len(added),
                      "alreadyPresentOnTarget": len(already_present),
                      "holdsUnchanged": EXPECTED_HOLD_COUNT,
                      "displaySha256": receipt["installedDisplaySha256"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
