"""Install reviewed Sousse Ministry spellings as search aliases only.

The dated identity crosswalk links all 24 Ministry exceptions by INS code.
Only its 18 unambiguous spelling variants are installed here. Geometry,
picker identity, and prayer-source mapping are deliberately untouched.
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
EXPECTED_CATALOG_SHA256 = "fb5ac069d93d57ffb0d3d9c286a7460e4b51417a18962568ea71c178a40ff1de"
EXPECTED_DISPLAY_SHA256 = "4069a9a99a5d9b7e5974071b29f6360efbf30a34f724a40256c00f84ecd790f2"
BACKUP = HERE / "before-locality-display-names.json"
RECEIPT = HERE / "installed-receipt.json"


def sha256(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def main() -> None:
    before = DISPLAY.read_bytes()
    if sha256(before) != EXPECTED_DISPLAY_SHA256:
        if RECEIPT.is_file():
            receipt = json.loads(RECEIPT.read_text(encoding="utf-8"))
            if sha256(before) == receipt["installedDisplaySha256"]:
                print(json.dumps({"status": "already_installed", "receipt": str(RECEIPT)}))
                return
        raise ValueError("The display-name asset changed after review")
    if sha256(CATALOG.read_bytes()) != EXPECTED_CATALOG_SHA256:
        raise ValueError("The catalog changed after the Sousse identity review")
    if BACKUP.exists() or RECEIPT.exists():
        raise FileExistsError("A backup or receipt already exists")

    proposal = json.loads(PROPOSAL.read_text(encoding="utf-8"))
    updates = proposal["safeMinistrySearchAliases"]
    if len(updates) != 18 or len(proposal["excludedAliasCases"]) != 1:
        raise ValueError("Unexpected Sousse identity review payload")
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    features = {feature["id"]: feature for feature in catalog["features"]}
    display = json.loads(before)
    if display["schemaVersion"] != 1 or not isinstance(display["names"], list):
        raise ValueError("Unknown display-name format")
    existing = {record["id"] for record in display["names"]}
    if len(existing) != len(display["names"]):
        raise ValueError("Duplicate display-name IDs")
    if len({record["id"] for record in updates}) != len(updates):
        raise ValueError("Duplicate proposed feature IDs")

    for record in updates:
        identifier = record["id"]
        feature = features[identifier]
        alias = record["value"]
        if (record["operation"] != "append_if_absent" or feature["governorateId"] != 348
                or feature["kind"] != "sector" or identifier in existing
                or alias in [feature["name"], *feature.get("aliases", [])]):
            raise ValueError(f"Unsafe or redundant alias: {identifier}")
        other_matches = [other["id"] for other in catalog["features"]
                         if other["governorateId"] == 348 and other["id"] != identifier
                         and alias in [other["name"], *other.get("aliases", [])]]
        if other_matches:
            raise ValueError(f"Ambiguous alias for {identifier}: {other_matches}")
        display["names"].append({"id": identifier, "nameAr": feature["name"], "searchAliases": [alias]})

    display["names"].sort(key=lambda record: record["id"])
    after = json.dumps(display, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    BACKUP.write_bytes(before)
    temporary = DISPLAY.with_name(DISPLAY.name + ".sousse-aliases.tmp")
    try:
        temporary.write_bytes(after)
        os.replace(temporary, DISPLAY)
        if sha256(DISPLAY.read_bytes()) != sha256(after):
            raise ValueError("Display-name write verification failed")
    except Exception:
        if temporary.exists():
            temporary.unlink()
        DISPLAY.write_bytes(before)
        raise
    receipt = {
        "status": "installed_sousse_ministry_search_aliases",
        "installedAtUtc": datetime.now(timezone.utc).isoformat(),
        "catalogSha256": EXPECTED_CATALOG_SHA256,
        "sourceProposalSha256": sha256(PROPOSAL.read_bytes()),
        "baseDisplaySha256": EXPECTED_DISPLAY_SHA256,
        "installedDisplaySha256": sha256(after),
        "aliasesAdded": len(updates),
        "featureIds": [record["id"] for record in updates],
        "displayNameCorrectionsAdded": 0,
        "note": "El Gharbine already displays as الغربيين through the existing runtime override; the ambiguous unqualified سهلول alias stays excluded.",
    }
    RECEIPT.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(receipt, ensure_ascii=False))


if __name__ == "__main__":
    main()
