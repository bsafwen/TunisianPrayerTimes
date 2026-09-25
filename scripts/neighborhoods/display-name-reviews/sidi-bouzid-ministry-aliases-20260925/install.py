"""Install only directly code-backed Sidi Bouzid Ministry search spellings."""

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
REVIEW = TASK / "work/official-imada-evidence-20260924-v2/11-sidi-bouzid/identity-exceptions/identity-assessment-20260925.json"
BACKUP = HERE / "before-locality-display-names.json"
RECEIPT = HERE / "installed-receipt.json"
EXPECTED = {
    "catalog": "e2016b6878f7663d0cfd09076eaaeeb85a5b774ef987a36f3a6e84e6e16137d6",
    "display": "e4637d42ce9deb04c2407ca5477c13647fd09bda5984faa8203a4cfbfa14f834",
    "review": "29d418819d21d078f0c90f1db631ae3620ad66aedb2f23906b3e709d8ddc49d3",
}


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main() -> None:
    before = DISPLAY.read_bytes()
    if RECEIPT.is_file() and digest(before) == json.loads(RECEIPT.read_text(encoding="utf-8"))["installedDisplaySha256"]:
        print(json.dumps({"status": "already_installed", "receipt": str(RECEIPT)}))
        return
    if (digest(CATALOG.read_bytes()) != EXPECTED["catalog"]
            or digest(before) != EXPECTED["display"]
            or digest(REVIEW.read_bytes()) != EXPECTED["review"]):
        raise ValueError("Catalog, display asset, or reviewed evidence changed")
    if BACKUP.exists() or RECEIPT.exists():
        raise FileExistsError("Backup or receipt exists without an installed output")

    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    features = {row["id"]: row for row in catalog["features"]}
    display = json.loads(before)
    if display["schemaVersion"] != 1 or len({row["id"] for row in display["names"]}) != len(display["names"]):
        raise ValueError("Invalid display asset")
    existing = {row["id"]: row for row in display["names"]}
    review = json.loads(REVIEW.read_text(encoding="utf-8"))
    rows = [row for row in review["rows"] if row.get("proposedArabicSearchAlias")]
    if len(review["rows"]) != 10 or len(rows) != 7:
        raise ValueError("Unexpected reviewed row count")
    added = []
    for row in rows:
        if (row["classification"] != "direct_osm_ins_code_identity_candidate"
                or not row["appParentMatchesMinistryAfterPrefixRemoval"]
                or not row["insParentMatchesMinistry"]):
            raise ValueError(f"Weak or conflicting identity: {row['caseId']}")
        identifier = row["appCandidate"]["id"]
        feature = features[identifier]
        alias = row["proposedArabicSearchAlias"]
        if (feature["kind"] != "sector" or feature["governorateId"] != 355
                or alias != row["ministry"]["name"]
                or feature["name"] != row["appCandidate"]["name"]
                or feature["parentName"] != row["appCandidate"]["parentName"]
                or feature["id"] in {item["id"] for item in added}):
            raise ValueError(f"Catalog identity drift: {identifier}")
        if alias in [feature["name"], *feature.get("aliases", [])]:
            raise ValueError(f"Alias already exists in catalog: {identifier}")
        collisions = [other["id"] for other in catalog["features"]
                      if other["governorateId"] == 355 and other["id"] != identifier
                      and alias in [other["name"], *other.get("aliases", [])]]
        if collisions:
            raise ValueError(f"Alias collides with other Sidi Bouzid rows: {identifier}: {collisions}")
        record = existing.get(identifier)
        if record is None:
            record = {"id": identifier, "nameAr": feature["name"], "searchAliases": []}
            display["names"].append(record)
            existing[identifier] = record
        elif record["nameAr"] != feature["name"]:
            raise ValueError(f"Display name differs from reviewed catalog: {identifier}")
        if alias in record["searchAliases"]:
            raise ValueError(f"Alias already installed: {identifier}")
        record["searchAliases"].append(alias)
        added.append({"id": identifier, "alias": alias, "caseId": row["caseId"]})

    display["names"].sort(key=lambda row: row["id"])
    after = json.dumps(display, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    BACKUP.write_bytes(before)
    temp = DISPLAY.with_name(DISPLAY.name + ".sidi-bouzid-aliases.tmp")
    try:
        temp.write_bytes(after)
        os.replace(temp, DISPLAY)
        if digest(DISPLAY.read_bytes()) != digest(after):
            raise ValueError("Display write verification failed")
    except Exception:
        temp.unlink(missing_ok=True)
        DISPLAY.write_bytes(before)
        raise
    receipt = {
        "status": "installed_sidi_bouzid_ministry_search_aliases",
        "installedAtUtc": datetime.now(timezone.utc).isoformat(),
        "sourceSha256": EXPECTED,
        "installedDisplaySha256": digest(after),
        "aliasesAdded": added,
        "heldParentConflicts": 3,
        "catalogAndGeometryChanged": False,
    }
    RECEIPT.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"aliasesAdded": len(added), "heldParentConflicts": 3, "displaySha256": digest(after)}))


if __name__ == "__main__":
    main()
