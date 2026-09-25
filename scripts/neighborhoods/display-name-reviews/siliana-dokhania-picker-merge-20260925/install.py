"""Merge nested Dokhania residential into its official sector picker group."""

from __future__ import annotations

import hashlib
import json
import os
import unicodedata
from datetime import datetime, timezone
from pathlib import Path


HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
ASSETS = REPO / "android-app/app/src/main/assets"
CATALOG = ASSETS / "neighborhoods.json"
PACKED = ASSETS / "neighborhoods.bin"
DISPLAY = ASSETS / "locality-display-names.json"
SECTOR_ID = "osm:relation:7126320"
RESIDENTIAL_ID = "osm:way:184043911"
HAMLET_ID = "osm:node:7867290340"
SEARCH_ALIAS = "الدخانية"
EXPECTED_HASHES = {
    "neighborhoods.json": "fb5ac069d93d57ffb0d3d9c286a7460e4b51417a18962568ea71c178a40ff1de",
    "neighborhoods.bin": "a8745844d40d2314c3cd14bb4ca295593d1263ad84c13da8e521dc361211e45b",
    "locality-display-names.json": "e4637d42ce9deb04c2407ca5477c13647fd09bda5984faa8203a4cfbfa14f834",
}
BACKUP = HERE / "before-neighborhoods.json"
RECEIPT = HERE / "installed-receipt.json"


def sha256(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def normalized(value: str) -> str:
    value = unicodedata.normalize("NFKD", value)
    value = "".join(char for char in value if not unicodedata.category(char).startswith("M"))
    value = value.replace("ـ", "").replace("ى", "ي")
    return " ".join("".join(char for char in value if unicodedata.category(char) != "Cf").lower().split())


def search_text(feature: dict, display_record: dict | None = None) -> str:
    values = [feature.get("name", ""), *feature.get("aliases", []),
              feature.get("parentName", ""), *feature.get("contextAliases", [])]
    if display_record is not None:
        values.extend([display_record.get("nameAr", ""), *display_record.get("searchAliases", [])])
    return " ".join(value for value in values if value)


def identity(feature: dict) -> dict:
    return {key: feature.get(key) for key in (
        "id", "sourceId", "name", "aliases", "kind", "parentName", "contextAliases",
        "governorateId", "lat", "lng", "delegationId", "hasBoundary", "bbox", "areaKm2",
        "offset", "length", "pickerGroupId",
    )}


def main() -> None:
    catalog_before = CATALOG.read_bytes()
    packed_before = PACKED.read_bytes()
    display_before = DISPLAY.read_bytes()
    actual_hashes = {
        CATALOG.name: sha256(catalog_before),
        PACKED.name: sha256(packed_before),
        DISPLAY.name: sha256(display_before),
    }
    if RECEIPT.is_file():
        receipt = json.loads(RECEIPT.read_text(encoding="utf-8"))
        current_hashes = {
            CATALOG.name: actual_hashes[CATALOG.name],
            PACKED.name: actual_hashes[PACKED.name],
            DISPLAY.name: actual_hashes[DISPLAY.name],
        }
        if current_hashes == receipt["afterSha256"]:
            print(json.dumps({"status": "already_installed", "receipt": str(RECEIPT)}, ensure_ascii=True))
            return
        raise ValueError("A receipt exists but the current assets no longer match its output hashes")
    if actual_hashes != EXPECTED_HASHES:
        raise ValueError(f"Pinned asset hashes changed: {actual_hashes}")
    if BACKUP.exists():
        raise FileExistsError(f"Backup already exists: {BACKUP}")

    catalog = json.loads(catalog_before)
    feature_order = [row["id"] for row in catalog["features"]]
    by_id = {row["id"]: row for row in catalog["features"]}
    if len(by_id) != len(catalog["features"]):
        raise ValueError("Duplicate feature IDs in catalog")
    if set((SECTOR_ID, RESIDENTIAL_ID, HAMLET_ID)) - set(by_id):
        raise ValueError("A required Dokhania feature is missing")
    sector, residential, hamlet = (by_id[key] for key in (SECTOR_ID, RESIDENTIAL_ID, HAMLET_ID))
    if (sector["kind"] != "sector" or sector["pickerGroupId"] != SECTOR_ID
            or sector["name"] != "دخانية" or sector["governorateId"] != 344
            or sector["delegationId"] != 421 or sector["hasBoundary"] is not True):
        raise ValueError("Official sector identity/group changed")
    if (residential["kind"] != "residential" or residential["pickerGroupId"] != RESIDENTIAL_ID
            or residential["name"] != SEARCH_ALIAS or residential["governorateId"] != 344
            or residential["delegationId"] != 421 or residential["hasBoundary"] is not True):
        raise ValueError("Nested residential identity/group changed")
    if (hamlet["kind"] != "hamlet" or hamlet["pickerGroupId"] != HAMLET_ID
            or hamlet["name"] != "دوار الدخانية" or hamlet["governorateId"] != 344
            or hamlet["delegationId"] != 421 or hamlet["hasBoundary"] is not False):
        raise ValueError("Distinct Dokhania hamlet identity/group changed")
    if not (sector["bbox"][0] <= residential["bbox"][0] <= residential["bbox"][2] <= sector["bbox"][2]
            and sector["bbox"][1] <= residential["bbox"][1] <= residential["bbox"][3] <= sector["bbox"][3]):
        raise ValueError("Nested residential bbox is no longer fully within sector bbox")

    display = json.loads(display_before)
    display_rows = {row["id"]: row for row in display["names"]}
    if SECTOR_ID in display_rows or RESIDENTIAL_ID in display_rows:
        raise ValueError("A display override appeared for a merge member; preserve/review it explicitly")
    if any(SEARCH_ALIAS in row.get("searchAliases", []) for row in display["names"]):
        raise ValueError("The held exact alias already belongs to another display record")

    exact_name_candidates = [row["id"] for row in catalog["features"]
                             if row["governorateId"] == 344
                             and SEARCH_ALIAS in [row.get("name", ""), *row.get("aliases", [])]]
    if sorted(exact_name_candidates) != sorted((RESIDENTIAL_ID,)):
        raise ValueError(f"Unexpected exact-name candidates for held alias: {exact_name_candidates}")

    before_sector = identity(sector)
    before_residential = identity(residential)
    before_hamlet = identity(hamlet)
    residential["pickerGroupId"] = SECTOR_ID
    expected_features = [*catalog["features"]]
    if [row["id"] for row in expected_features] != feature_order:
        raise ValueError("Feature order unexpectedly changed")

    groups: dict[str, list[dict]] = {}
    for row in catalog["features"]:
        groups.setdefault(row.get("pickerGroupId") or row["id"], []).append(row)
    merged_members = groups.get(SECTOR_ID, [])
    if {row["id"] for row in merged_members} != {SECTOR_ID, RESIDENTIAL_ID}:
        raise ValueError("The target group does not contain exactly the official sector and nested residential")
    if groups.get(HAMLET_ID, []) != [hamlet]:
        raise ValueError("The distinct hamlet is no longer a standalone picker choice")
    canonical = next((row for row in merged_members if row["id"] == SECTOR_ID), None)
    if canonical is not sector:
        raise ValueError("Picker representative is not the official sector")
    display_by_id = {row["id"]: row for row in display["names"]}
    merged_search = normalized(" ".join(search_text(row, display_by_id.get(row["id"])) for row in merged_members))
    hamlet_search = normalized(search_text(hamlet, display_by_id.get(HAMLET_ID)))
    query = normalized(SEARCH_ALIAS)
    if query not in merged_search or query not in hamlet_search:
        raise ValueError("Expected query text is not available through merged group and distinct hamlet")
    matching_features = [row for row in catalog["features"] if row["governorateId"] == 344
                         and query in normalized(search_text(row, display_by_id.get(row["id"]))) ]
    matching_feature_ids = sorted(row["id"] for row in matching_features)
    if matching_feature_ids != sorted((RESIDENTIAL_ID, HAMLET_ID)):
        raise ValueError(f"Unexpected other governorate search candidates for {SEARCH_ALIAS}: {matching_feature_ids}")
    resulting_picker_rows = sorted({row.get("pickerGroupId") or row["id"] for row in matching_features})
    if resulting_picker_rows != sorted((SECTOR_ID, HAMLET_ID)):
        raise ValueError(f"Unexpected grouped search rows for {SEARCH_ALIAS}: {resulting_picker_rows}")
    if residential["pickerGroupId"] != SECTOR_ID or residential["id"] != RESIDENTIAL_ID:
        raise ValueError("Residential picker-group-only change did not apply")
    after_sector = identity(sector)
    after_residential = identity(residential)
    after_hamlet = identity(hamlet)
    for key in before_sector:
        if before_sector[key] != after_sector[key]:
            raise ValueError(f"Sector identity unexpectedly changed: {key}")
    for key in before_residential:
        if key == "pickerGroupId":
            if before_residential[key] != RESIDENTIAL_ID or after_residential[key] != SECTOR_ID:
                raise ValueError("Expected residential pickerGroupId transition changed")
        elif before_residential[key] != after_residential[key]:
            raise ValueError(f"Residential identity unexpectedly changed: {key}")
    if before_hamlet != after_hamlet:
        raise ValueError("Standalone hamlet identity unexpectedly changed")

    after_bytes = (json.dumps(catalog, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")
    proof = {
        "change": {"featureId": RESIDENTIAL_ID, "field": "pickerGroupId", "before": RESIDENTIAL_ID, "after": SECTOR_ID},
        "canonicalPickerRow": SECTOR_ID,
        "mergedPickerMembers": sorted(row["id"] for row in merged_members),
        "mergedGroupSearchContains": SEARCH_ALIAS,
        "searchSource": "The residential member retains its source name; groupPickerLocalities concatenates member searchText into the canonical sector row.",
        "distinctHamlet": {"featureId": HAMLET_ID, "name": hamlet["name"], "pickerGroupId": hamlet["pickerGroupId"], "hasBoundary": hamlet["hasBoundary"], "searchAlsoMatchesThisDistinctRow": query in hamlet_search},
        "exactAliasCandidatesBeforeGrouping": exact_name_candidates,
        "directSearchCandidateFeatureIdsBeforeGrouping": matching_feature_ids,
        "resultingPickerRowIdsForQuery": resulting_picker_rows,
        "sectorIdentityBefore": before_sector,
        "sectorIdentityAfter": after_sector,
        "residentialIdentityBefore": before_residential,
        "residentialIdentityAfter": after_residential,
        "hamletIdentityUnchanged": before_hamlet == after_hamlet,
        "featureCount": len(feature_order),
        "featureOrderSha256": sha256("\n".join(feature_order).encode("utf-8")),
        "featureOrderUnchanged": [row["id"] for row in catalog["features"]] == feature_order,
        "sectorContainsResidentialByBbox": True,
        "geometryAreaAndPackedOffsetsUnchanged": all(before_residential[key] == after_residential[key] for key in ("bbox", "areaKm2", "offset", "length")),
        "aliasAddedToDisplayOverride": False,
    }
    after_hashes = {
        CATALOG.name: sha256(after_bytes),
        PACKED.name: actual_hashes[PACKED.name],
        DISPLAY.name: actual_hashes[DISPLAY.name],
    }
    proof_bytes = (json.dumps(proof, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    receipt = {
        "status": "installed_dokhania_residential_picker_merge",
        "installedAtUtc": datetime.now(timezone.utc).isoformat(),
        "beforeSha256": actual_hashes,
        "afterSha256": after_hashes,
        "identityProofSha256": sha256(proof_bytes),
        "featureId": RESIDENTIAL_ID,
        "fieldChanged": "pickerGroupId",
        "beforeValue": RESIDENTIAL_ID,
        "afterValue": SECTOR_ID,
        "displayAliasAdded": False,
        "binaryGeometryChanged": False,
        "featureOrderUnchanged": True,
        "expectedQueryPickerRows": [SECTOR_ID, HAMLET_ID],
        "identityProof": proof,
    }

    if sha256(CATALOG.read_bytes()) != actual_hashes[CATALOG.name] or sha256(PACKED.read_bytes()) != actual_hashes[PACKED.name] or sha256(DISPLAY.read_bytes()) != actual_hashes[DISPLAY.name]:
        raise ValueError("One of the pinned app assets changed during preflight")
    BACKUP.write_bytes(catalog_before)
    temporary = CATALOG.with_name(CATALOG.name + ".dokhania-picker.tmp")
    try:
        temporary.write_bytes(after_bytes)
        os.replace(temporary, CATALOG)
        if sha256(CATALOG.read_bytes()) != after_hashes[CATALOG.name]:
            raise ValueError("Catalog write verification failed")
        if sha256(PACKED.read_bytes()) != actual_hashes[PACKED.name] or sha256(DISPLAY.read_bytes()) != actual_hashes[DISPLAY.name]:
            raise ValueError("Read-only packed/display asset changed during install")
        RECEIPT.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    except Exception:
        if temporary.exists():
            temporary.unlink()
        CATALOG.write_bytes(catalog_before)
        if RECEIPT.exists():
            RECEIPT.unlink()
        if BACKUP.exists():
            BACKUP.unlink()
        raise
    print(json.dumps({"status": receipt["status"], "afterSha256": after_hashes}, ensure_ascii=True))


if __name__ == "__main__":
    main()
