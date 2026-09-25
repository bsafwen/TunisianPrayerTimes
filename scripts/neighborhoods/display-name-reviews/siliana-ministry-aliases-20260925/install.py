"""Install eight code-reviewed Siliana Ministry spellings as runtime search aliases.

The current catalog checksum differs from the catalog checksum pinned by the
identity review, so this installer additionally compares each target's current
identity fields with that pinned review before changing display-name data.
"""

from __future__ import annotations

import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path


HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
ASSETS = REPO / "android-app/app/src/main/assets"
CATALOG = ASSETS / "neighborhoods.json"
DISPLAY = ASSETS / "locality-display-names.json"
PROPOSAL = HERE / "proposed-text-deltas.json"
PINNED_REVIEW = HERE / "pinned-identity-review.json"
EXPECTED_CATALOG_SHA256 = "fb5ac069d93d57ffb0d3d9c286a7460e4b51417a18962568ea71c178a40ff1de"
EXPECTED_DISPLAY_SHA256 = "0dff778a80ac5508e0199fe6803199f720245ada2cf4d0b11423f9138a88eedd"
EXPECTED_PROPOSAL_SHA256 = "fd4f3c7c5f098dea3b68f183d7507db7498832c5ef9d510639d2c41ff53b80c1"
EXPECTED_REVIEW_SHA256 = "b2231d4ac5bd787f828344a6c35695d85e2845c1a760573a86575c366ba21a6f"
EXPECTED_REVIEW_CATALOG_SHA256 = "b56e0f6c7fe69db19fd88676508856fe3827a4707d5d9642c47be5b3a46553f4"
EXPECTED_PROPOSAL_IDS = {
    "osm:relation:7126058",
    "osm:relation:7126051",
    "osm:relation:7125518",
    "osm:relation:7125035",
    "osm:relation:7126205",
    "osm:relation:7126320",
    "osm:relation:7126318",
    "osm:relation:7126319",
    "osm:relation:7125787",
}
EXPECTED_INSTALL_IDS = EXPECTED_PROPOSAL_IDS - {"osm:relation:7126320"}
HELD_DUPLICATE_ALIAS = {
    "featureId": "osm:relation:7126320",
    "alias": "الدخانية",
    "collidingFeatureId": "osm:way:184043911",
    "reason": "The same Arabic label already names a nested residential feature; it has a distinct pickerGroupId, so adding this search alias would make one query resolve to two selectable rows.",
}
EXPECTED_HELD_IDS = {
    "osm:relation:7125032",
    "osm:relation:7125778",
    "osm:relation:7125183",
}
EXISTING_DISPLAY_RECORD = {
    "id": "osm:relation:7126051",
    "nameAr": "مرج مقدم",
    "searchAliases": ["MARJ MOKADDEM"],
}
BACKUP = HERE / "before-locality-display-names.json"
IDENTITY_PROOF = HERE / "identity-proof.json"
RECEIPT = HERE / "installed-receipt.json"


def sha256(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def identity_snapshot(feature: dict, pair: dict, proposal: dict) -> dict:
    alias_row = proposal["byId"][feature["id"]]
    code = alias_row["insSectorCode"]
    tag_code = pair["osmTags"].get("ref:tn:codegeo")
    snapshot = {
        "featureId": feature["id"],
        "appName": feature["name"],
        "appParent": feature["parentName"],
        "kind": feature["kind"],
        "governorateId": feature["governorateId"],
        "ministryInsSectorCode": code,
        "reviewInsSectorCode": pair["insSectorCode"],
        "reviewOsmCodeTag": tag_code,
        "reviewCodeTagMatchesIns": pair["codeTagMatchesIns"],
    }
    expected = {
        "featureId": pair["appId"],
        "appName": pair["appName"],
        "appParent": pair["appParent"],
        "kind": "sector",
        "governorateId": 344,
        "ministryInsSectorCode": pair["insSectorCode"],
        "reviewInsSectorCode": pair["insSectorCode"],
        "reviewOsmCodeTag": pair["insSectorCode"],
        "reviewCodeTagMatchesIns": True,
    }
    if snapshot != expected:
        raise ValueError(f"Current feature identity no longer matches pinned review: {feature['id']}")
    if code != tag_code:
        raise ValueError(f"Proposed code no longer agrees with the pinned OSM tag: {feature['id']}")
    return snapshot


def main() -> None:
    before = DISPLAY.read_bytes()
    before_hash = sha256(before)
    if before_hash != EXPECTED_DISPLAY_SHA256:
        if RECEIPT.is_file():
            receipt = json.loads(RECEIPT.read_text(encoding="utf-8"))
            if before_hash == receipt["installedDisplaySha256"]:
                print(json.dumps({"status": "already_installed", "receipt": str(RECEIPT)}, ensure_ascii=True))
                return
        raise ValueError("The display-name asset changed after the Siliana review was pinned")
    catalog_bytes = CATALOG.read_bytes()
    catalog_hash = sha256(catalog_bytes)
    if catalog_hash != EXPECTED_CATALOG_SHA256:
        raise ValueError("The current catalog changed after Siliana identity fields were checked")
    proposal_bytes = PROPOSAL.read_bytes()
    if sha256(proposal_bytes) != EXPECTED_PROPOSAL_SHA256:
        raise ValueError("The copied proposal changed after review")
    review_bytes = PINNED_REVIEW.read_bytes()
    if sha256(review_bytes) != EXPECTED_REVIEW_SHA256:
        raise ValueError("The pinned identity review changed")
    if BACKUP.exists() or IDENTITY_PROOF.exists() or RECEIPT.exists():
        raise FileExistsError("An identity proof, backup, or receipt already exists")

    proposal = json.loads(proposal_bytes)
    review = json.loads(review_bytes)
    updates = proposal["safeMinistrySearchAliases"]
    held = proposal["heldNameConflictCases"]
    if len(updates) != 9 or {row["id"] for row in updates} != EXPECTED_PROPOSAL_IDS:
        raise ValueError("Unexpected Siliana safe-alias rows")
    if {row["appId"] for row in held} != EXPECTED_HELD_IDS:
        raise ValueError("Unexpected Siliana name-conflict hold set")
    if set(updates_row["id"] for updates_row in updates) & EXPECTED_HELD_IDS:
        raise ValueError("A held name-conflict feature appears in safe aliases")
    if proposal.get("baseCatalogSha256") != EXPECTED_REVIEW_CATALOG_SHA256:
        raise ValueError("Proposal and pinned review do not share the same source catalog pin")
    if review["sourcePins"]["catalogJsonSha256"] != EXPECTED_REVIEW_CATALOG_SHA256:
        raise ValueError("Unexpected pinned review catalog hash")
    review_safe = review["stagedTextDeltas"]["safeMinistrySearchAliases"]
    review_held = review["stagedTextDeltas"]["heldNameConflictCases"]
    if {row["id"] for row in review_safe} != EXPECTED_PROPOSAL_IDS:
        raise ValueError("Pinned review safe aliases disagree with proposal")
    if {row["appId"] for row in review_held} != EXPECTED_HELD_IDS:
        raise ValueError("Pinned review hold set disagrees with proposal")

    catalog = json.loads(catalog_bytes)
    features = {feature["id"]: feature for feature in catalog["features"]}
    if len(features) != len(catalog["features"]):
        raise ValueError("Duplicate feature IDs in current catalog")
    pairs = {row["appId"]: row for row in review["pairs"]}
    if len(pairs) != len(review["pairs"]):
        raise ValueError("Duplicate feature IDs in pinned identity review")
    display = json.loads(before)
    if display.get("schemaVersion") != 1 or not isinstance(display.get("names"), list):
        raise ValueError("Unknown display-name format")
    existing_by_id = {record["id"]: record for record in display["names"]}
    if len(existing_by_id) != len(display["names"]):
        raise ValueError("Duplicate display-name IDs")
    if len({row["id"] for row in updates}) != len(updates):
        raise ValueError("Duplicate proposed feature IDs")
    if existing_by_id.get(EXISTING_DISPLAY_RECORD["id"]) != EXISTING_DISPLAY_RECORD:
        raise ValueError("The existing Marj Mokaddem display record changed")
    unexpected_existing = (set(existing_by_id) & EXPECTED_INSTALL_IDS) - {EXISTING_DISPLAY_RECORD["id"]}
    if unexpected_existing:
        raise ValueError(f"Unexpected pre-existing target display rows: {sorted(unexpected_existing)}")

    identity_rows = []
    by_id = {row["id"]: row for row in updates}
    proposal["byId"] = by_id
    for identifier in sorted(EXPECTED_PROPOSAL_IDS):
        feature = features.get(identifier)
        pair = pairs.get(identifier)
        if feature is None or pair is None:
            raise ValueError(f"Missing current feature or pinned identity row: {identifier}")
        if pair.get("nameConflictHold") is not None:
            raise ValueError(f"Held identity conflict must not be installed: {identifier}")
        identity_rows.append(identity_snapshot(feature, pair, proposal))

    excluded = by_id[HELD_DUPLICATE_ALIAS["featureId"]]
    if excluded["value"] != HELD_DUPLICATE_ALIAS["alias"]:
        raise ValueError("The documented duplicate-alias hold no longer matches the proposal")
    collision_feature = features.get(HELD_DUPLICATE_ALIAS["collidingFeatureId"])
    held_feature = features[HELD_DUPLICATE_ALIAS["featureId"]]
    if (collision_feature is None or collision_feature["kind"] != "residential"
            or collision_feature["name"] != HELD_DUPLICATE_ALIAS["alias"]
            or collision_feature["governorateId"] != 344
            or collision_feature["delegationId"] != held_feature["delegationId"]
            or collision_feature.get("pickerGroupId") == held_feature.get("pickerGroupId")):
        raise ValueError("The known nested residential alias collision changed; re-review before proceeding")

    for record in updates:
        identifier = record["id"]
        if identifier not in EXPECTED_INSTALL_IDS:
            continue
        feature = features[identifier]
        alias = record["value"]
        if (record["operation"] != "append_if_absent" or record["field"] != "aliases"
                or feature["governorateId"] != 344 or feature["kind"] != "sector"
                or alias in [feature["name"], *feature.get("aliases", [])]):
            raise ValueError(f"Unsafe or redundant alias: {identifier}")
        other_matches = [other["id"] for other in catalog["features"]
                         if other["governorateId"] == 344 and other["id"] != identifier
                         and alias in [other["name"], *other.get("aliases", [])]]
        if other_matches:
            raise ValueError(f"Ambiguous alias for {identifier}: {other_matches}")
        display_matches = [row["id"] for row in display["names"]
                           if row["id"] != identifier and alias in row.get("searchAliases", [])]
        if display_matches:
            raise ValueError(f"Alias already belongs to another display record: {display_matches}")
        current = existing_by_id.get(identifier)
        if current is None:
            display["names"].append({"id": identifier, "nameAr": feature["name"], "searchAliases": [alias]})
        else:
            if current["nameAr"] != feature["name"] or alias in current.get("searchAliases", []):
                raise ValueError(f"Existing display row has changed or alias is already present: {identifier}")
            current["searchAliases"].append(alias)

    display["names"].sort(key=lambda record: record["id"])
    after = json.dumps(display, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    identity_proof = {
        "status": "eight_aliases_installed_nine_current_identity_rows_match_pinned_review",
        "pinnedReviewSha256": EXPECTED_REVIEW_SHA256,
        "pinnedReviewCatalogSha256": EXPECTED_REVIEW_CATALOG_SHA256,
        "proposalBaseCatalogSha256": proposal["baseCatalogSha256"],
        "currentCatalogSha256": catalog_hash,
        "wholeCatalogHashMatchesPinnedReview": catalog_hash == EXPECTED_REVIEW_CATALOG_SHA256,
        "comparisonNote": "The full current catalog hash differs from the review's source-catalog pin; all nine proposed feature IDs, names, parents, sector kinds, governorate IDs, and review/proposal code links were compared directly and match.",
        "features": identity_rows,
        "excludedDuplicateAlias": {
            **HELD_DUPLICATE_ALIAS,
            "nestedFeatureKind": collision_feature["kind"],
            "nestedFeatureParent": collision_feature.get("parentName"),
            "delegationId": collision_feature["delegationId"],
            "sectorPickerGroupId": held_feature.get("pickerGroupId"),
            "nestedFeaturePickerGroupId": collision_feature.get("pickerGroupId"),
            "nestedResidentialGroupedOrHiddenUnderSector": False,
            "action": "Keep alias excluded pending a separate picker-group correction review; do not remove either catalog feature based on this alias collision alone.",
        },
        "existingDisplayRecordBefore": EXISTING_DISPLAY_RECORD,
        "existingDisplayRecordAfter": {
            "id": EXISTING_DISPLAY_RECORD["id"],
            "nameAr": EXISTING_DISPLAY_RECORD["nameAr"],
            "existingLatinAliasPreserved": EXISTING_DISPLAY_RECORD["searchAliases"][0],
            "newMinistryAliasAppended": by_id[EXISTING_DISPLAY_RECORD["id"]]["value"],
        },
        "heldNameConflictIds": sorted(EXPECTED_HELD_IDS),
    }
    identity_proof_bytes = (json.dumps(identity_proof, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    temporary = DISPLAY.with_name(DISPLAY.name + ".siliana-aliases.tmp")
    receipt = {
        "status": "installed_siliana_ministry_search_aliases",
        "installedAtUtc": datetime.now(timezone.utc).isoformat(),
        "currentCatalogSha256": catalog_hash,
        "pinnedReviewCatalogSha256": EXPECTED_REVIEW_CATALOG_SHA256,
        "sourceProposalSha256": EXPECTED_PROPOSAL_SHA256,
        "pinnedIdentityReviewSha256": EXPECTED_REVIEW_SHA256,
        "baseDisplaySha256": EXPECTED_DISPLAY_SHA256,
        "installedDisplaySha256": sha256(after),
        "identityProofSha256": sha256(identity_proof_bytes),
        "proposedSafeAliases": len(updates),
        "aliasesAdded": len(EXPECTED_INSTALL_IDS),
        "featureIds": sorted(EXPECTED_INSTALL_IDS),
        "excludedAliasCases": [{
            **HELD_DUPLICATE_ALIAS,
            "nestedFeaturePickerGroupId": collision_feature.get("pickerGroupId"),
            "sectorPickerGroupId": held_feature.get("pickerGroupId"),
            "nestedResidentialGroupedOrHiddenUnderSector": False,
        }],
        "newDisplayRows": len(EXPECTED_INSTALL_IDS) - 1,
        "existingDisplayRowsUpdated": 1,
        "preservedExistingDisplayRecord": EXISTING_DISPLAY_RECORD,
        "heldNameConflictIds": sorted(EXPECTED_HELD_IDS),
        "displayNameCorrectionsAdded": 0,
        "geometryOrCatalogChanged": False,
    }

    if sha256(CATALOG.read_bytes()) != catalog_hash:
        raise ValueError("Catalog changed during install preflight")
    BACKUP.write_bytes(before)
    IDENTITY_PROOF.write_bytes(identity_proof_bytes)
    try:
        temporary.write_bytes(after)
        os.replace(temporary, DISPLAY)
        if sha256(DISPLAY.read_bytes()) != receipt["installedDisplaySha256"]:
            raise ValueError("Display-name write verification failed")
        if sha256(CATALOG.read_bytes()) != catalog_hash:
            raise ValueError("Catalog changed during alias installation")
        installed = json.loads(DISPLAY.read_text(encoding="utf-8"))
        installed_rows = {record["id"]: record for record in installed["names"]}
        existing_after = installed_rows[EXISTING_DISPLAY_RECORD["id"]]
        if (existing_after["nameAr"] != EXISTING_DISPLAY_RECORD["nameAr"]
                or EXISTING_DISPLAY_RECORD["searchAliases"][0] not in existing_after["searchAliases"]
                or by_id[EXISTING_DISPLAY_RECORD["id"]]["value"] not in existing_after["searchAliases"]):
            raise ValueError("Existing Marj Mokaddem name or Latin alias was not preserved")
        RECEIPT.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    except Exception:
        if temporary.exists():
            temporary.unlink()
        DISPLAY.write_bytes(before)
        if RECEIPT.exists():
            RECEIPT.unlink()
        if IDENTITY_PROOF.exists():
            IDENTITY_PROOF.unlink()
        if BACKUP.exists():
            BACKUP.unlink()
        raise
    print(json.dumps(receipt, ensure_ascii=True))


if __name__ == "__main__":
    main()
