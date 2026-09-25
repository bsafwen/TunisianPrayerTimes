"""Merge the same-name Borj Bourguiba village and residential picker rows."""

from __future__ import annotations

import hashlib
import json
import math
import os
from datetime import datetime, timezone
from pathlib import Path


HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
ASSETS = REPO / "android-app/app/src/main/assets"
CATALOG = ASSETS / "neighborhoods.json"
PACKED = ASSETS / "neighborhoods.bin"
DISPLAY = ASSETS / "locality-display-names.json"
VILLAGE_ID = "osm:node:1974940233"
RESIDENTIAL_ID = "osm:way:186750635"
EXPECTED = {
    "catalog": "e2016b6878f7663d0cfd09076eaaeeb85a5b774ef987a36f3a6e84e6e16137d6",
    "packed": "a34bdf230220f2a400aefc693f9f1055ad6d9ad59fd640f04c0418485a02d8cd",
    "display": "606ab74455c124b52a635aebb5adf36b6055edfa16fc2c0fd35f5ef88c7474a2",
}
BACKUP = HERE / "before-neighborhoods.json"
RECEIPT = HERE / "installed-receipt.json"


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def distance_m(a: dict, b: dict) -> float:
    lat1, lat2 = math.radians(a["lat"]), math.radians(b["lat"])
    dlng = math.radians(b["lng"] - a["lng"])
    dlat = lat2 - lat1
    hav = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlng / 2) ** 2
    return 6371000 * 2 * math.asin(min(1, math.sqrt(hav)))


def main() -> None:
    before = CATALOG.read_bytes()
    if RECEIPT.is_file() and digest(before) == json.loads(RECEIPT.read_text(encoding="utf-8"))["afterCatalogSha256"]:
        print(json.dumps({"status": "already_installed", "receipt": str(RECEIPT)}))
        return
    actual = {"catalog": digest(before), "packed": digest(PACKED.read_bytes()), "display": digest(DISPLAY.read_bytes())}
    if actual != EXPECTED or BACKUP.exists() or RECEIPT.exists():
        raise ValueError(f"Pinned assets or previous install changed: {actual}")
    doc = json.loads(before)
    features = doc["features"]
    by_id = {row["id"]: row for row in features}
    if len(by_id) != len(features):
        raise ValueError("Duplicate feature IDs")
    village, residential = by_id[VILLAGE_ID], by_id[RESIDENTIAL_ID]
    if (village["kind"] != "village" or residential["kind"] != "residential"
            or village["name"] != "برج بورقيبة" or residential["name"] != "Borj Bourguiba"
            or village["parentName"] != residential["parentName"]
            or village["governorateId"] != residential["governorateId"]
            or village["delegationId"] != residential["delegationId"]
            or village["pickerGroupId"] != VILLAGE_ID
            or residential["pickerGroupId"] != RESIDENTIAL_ID
            or not residential["hasBoundary"] or village["hasBoundary"]):
        raise ValueError("The two candidate identities changed")
    if village["parentName"] != "كمبوت" or village["governorateId"] != 351 or village["delegationId"] != 471:
        raise ValueError("Administrative context changed")
    separation = distance_m(village, residential)
    if separation > 400:
        raise ValueError(f"Place centers are farther apart than reviewed: {separation:.1f} m")
    if any(row["pickerGroupId"] == VILLAGE_ID and row["id"] != VILLAGE_ID for row in features):
        raise ValueError("The village picker group already has another member")
    display = json.loads(DISPLAY.read_text(encoding="utf-8"))
    names = {row["id"]: row["nameAr"] for row in display["names"]}
    if names.get(RESIDENTIAL_ID) != "برج بورقيبة" or names.get(VILLAGE_ID, village["name"]) != "برج بورقيبة":
        raise ValueError("Current effective Arabic labels differ")
    before_residential = dict(residential)
    residential["pickerGroupId"] = VILLAGE_ID
    if {key: value for key, value in residential.items() if key != "pickerGroupId"} != {
            key: value for key, value in before_residential.items() if key != "pickerGroupId"}:
        raise ValueError("Non-picker feature field changed")
    after = json.dumps(doc, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    BACKUP.write_bytes(before)
    temp = CATALOG.with_name(CATALOG.name + ".borj-bourguiba-picker.tmp")
    try:
        temp.write_bytes(after)
        os.replace(temp, CATALOG)
        if digest(CATALOG.read_bytes()) != digest(after):
            raise ValueError("Catalog write verification failed")
    except Exception:
        temp.unlink(missing_ok=True)
        CATALOG.write_bytes(before)
        raise
    receipt = {
        "status": "installed_borj_bourguiba_picker_merge",
        "installedAtUtc": datetime.now(timezone.utc).isoformat(),
        "beforeSha256": EXPECTED,
        "afterCatalogSha256": digest(after),
        "unchangedPackedSha256": digest(PACKED.read_bytes()),
        "unchangedDisplaySha256": digest(DISPLAY.read_bytes()),
        "centerSeparationMeters": round(separation, 1),
        "oldPickerGroupIds": [VILLAGE_ID, RESIDENTIAL_ID],
        "newPickerGroupId": VILLAGE_ID,
        "note": "Same-name village point and nearby residential footprint in the same Kambout parent. Targeted Google searches returned an unrelated Tunis landmark and did not support this decision; the merge is best-effort OSM identity consolidation, not a boundary validation.",
    }
    RECEIPT.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"mergedPickerRows": 1, "separationMeters": receipt["centerSeparationMeters"], "afterCatalogSha256": digest(after)}))


if __name__ == "__main__":
    main()
