"""Export the currently selectable catalog for the local human review screen.

Inputs are explicitly pinned. This reads the existing catalog helper; it never
refreshes its historical ledger or changes Android data.
"""
import argparse
import hashlib
import importlib.util
import json
import math
import unicodedata
from datetime import datetime, timezone
from pathlib import Path


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True,
                      separators=(",", ":"), allow_nan=False).encode("utf-8")


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def _index_features(metadata):
    features = metadata.get("features")
    if not isinstance(features, list):
        raise ValueError("Pinned metadata features must be a list")
    by_id = {}
    for feature in features:
        if not isinstance(feature, dict):
            raise ValueError("Pinned metadata feature must be an object")
        feature_id = feature.get("id")
        if not isinstance(feature_id, str) or not feature_id:
            raise ValueError("Pinned metadata feature has an invalid ID")
        if feature_id in by_id:
            raise ValueError(f"Duplicate pinned metadata ID: {feature_id}")
        group_id = feature.get("pickerGroupId") or feature_id
        if not isinstance(group_id, str) or not group_id:
            raise ValueError(f"Invalid picker group for pinned metadata ID: {feature_id}")
        by_id[feature_id] = feature
    return by_id


def _normalized_name(value):
    decomposed = unicodedata.normalize("NFKD", value)
    letters = ("ي" if char == "ى" else char.lower() for char in decomposed
               if not unicodedata.category(char).startswith("M")
               and unicodedata.category(char) != "Cf" and char != "ـ")
    return " ".join("".join(char if char.isalnum() else " " for char in letters).split())


def _row_fingerprint(row):
    content = {key: value for key, value in row.items()
               if key not in ("offset", "length", "fingerprint")}
    return hashlib.sha256(canonical(content)).hexdigest()


def _expose_grouped_sectors(by_id, metadata_features, binary, displays):
    """Keep each official sector ID while retaining its group's point and aliases."""
    preferred = {}
    separate_members = {}
    for feature in metadata_features.values():
        group_id = feature.get("pickerGroupId")
        if (feature.get("kind") != "sector" or not feature.get("hasBoundary")
                or not isinstance(group_id, str) or group_id == feature["id"]):
            continue
        owner = by_id.get(group_id)
        if not owner or feature["id"] not in owner.get("members", []):
            raise ValueError(f"Official sector has no matching picker group: {feature['id']}")
        sector_name = displays.get(feature["id"], {}).get("nameAr", feature["name"])
        owner_name = displays.get(group_id, {}).get("nameAr", owner["name"])
        same_name = _normalized_name(sector_name) == _normalized_name(owner_name)
        row = dict(feature)
        row["governorate"] = owner["governorate"]
        row["governorateFr"] = owner.get("governorateFr", "")
        row["members"] = list(owner["members"]) if same_name else [feature["id"]]
        if same_name:
            # A click still uses the former group's representative
            # point for its independent nearest prayer-source lookup.
            row["lat"], row["lng"] = owner["lat"], owner["lng"]
            row["delegationId"] = owner["delegationId"]
            row["aliases"] = list(dict.fromkeys(
                feature.get("aliases", []) + owner.get("aliases", [])
                + displays.get(group_id, {}).get("searchAliases", [])))
            row["contextAliases"] = list(dict.fromkeys(
                feature.get("contextAliases", []) + owner.get("contextAliases", [])))
            row["memberContentFingerprints"] = owner.get("memberContentFingerprints", [])
            preferred[group_id] = feature["id"]
        else:
            separate_members.setdefault(group_id, set()).add(feature["id"])
        offset, length = feature.get("offset"), feature.get("length")
        if (not isinstance(offset, int) or not isinstance(length, int)
                or offset < 0 or length <= 0 or offset + length > len(binary)):
            raise ValueError(f"Invalid packed geometry for official sector: {feature['id']}")
        row["geometrySha256"] = hashlib.sha256(binary[offset:offset + length]).hexdigest()
        row["fingerprint"] = _row_fingerprint(row)
        by_id[feature["id"]] = row
    for group_id, sector_ids in separate_members.items():
        owner = dict(by_id[group_id])
        owner["members"] = [identifier for identifier in owner["members"]
                            if identifier not in sector_ids]
        if "memberContentFingerprints" in owner:
            owner["memberContentFingerprints"] = [entry for entry in owner["memberContentFingerprints"]
                                                   if entry["id"] not in sector_ids]
        owner["fingerprint"] = _row_fingerprint(owner)
        by_id[group_id] = owner
    return preferred


def _resolve_selectable_ids(raw_ids, by_id, metadata_features, preferred):
    """Collapse historical member IDs using the app's picker group owner."""
    resolved, seen = [], set()
    for raw_id in raw_ids:
        feature = metadata_features.get(raw_id)
        group_id = (feature.get("pickerGroupId") or raw_id) if feature else raw_id
        if (feature and feature.get("kind") == "sector" and feature.get("hasBoundary")
                and group_id != raw_id):
            location_id = raw_id
        elif group_id != raw_id or raw_id in preferred:
            location_id = preferred.get(group_id, group_id)
            row = by_id.get(location_id)
            members = row.get("members") if row else None
            if (row is None or row.get("id") != location_id
                    or not isinstance(members, list) or raw_id not in members):
                raise ValueError(
                    f"Grouped selectable ID {raw_id} has no matching helper group {group_id}"
                )
        elif raw_id in by_id:
            location_id = raw_id
        else:
            raise ValueError(
                f"Selectable ID {raw_id} is missing from the helper and is not a verified grouped member"
            )
        if location_id not in seen:
            seen.add(location_id)
            resolved.append(location_id)
    return resolved


def export_catalog(pins_path, output_path):
    output = Path(output_path).resolve()
    if output.exists():
        raise ValueError("Output already exists; use a new snapshot filename")
    all_pins = read(pins_path)
    keys = ("helper", "metadata", "binary", "governors", "displayNames", "coverage")
    pins = {k: all_pins[k] for k in keys}

    def verify():
        for key, value in pins.items():
            if digest(value["file"]) != value["sha256"]:
                raise ValueError(f"Input changed: {key}")

    verify()
    metadata = read(pins["metadata"]["file"])
    metadata_features = _index_features(metadata)
    coverage = read(pins["coverage"]["file"])["prayerSelection"]
    displays = {r["id"]: r for r in read(pins["displayNames"]["file"])["names"]}
    gov_rows = read(pins["governors"]["file"])["gouvernorats"]
    sources = {r["id"]: r for g in gov_rows for r in g["delegations"]}
    spec = importlib.util.spec_from_file_location("review_export_helper", pins["helper"]["file"])
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)
    metadata_sha, rows = helper.catalog()
    if metadata_sha != pins["metadata"]["sha256"]:
        raise ValueError("Helper read a different metadata snapshot")
    by_id = {r["id"]: r for r in rows}
    if len(by_id) != len(rows):
        raise ValueError("Duplicate helper IDs")
    preferred = _expose_grouped_sectors(
        by_id, metadata_features, Path(pins["binary"]["file"]).read_bytes(), displays)
    manual = {r["id"]: r for r in coverage["currentManualSelections"]}
    if len(manual) != len(coverage["currentManualSelections"]):
        raise ValueError("Duplicate selectable IDs")
    requested_ids = list(manual)
    requested_ids += [f"delegation:{i}" for i in coverage["currentSourceIds"]
                      if f"delegation:{i}" not in manual]
    requested_ids += [feature["id"] for feature in metadata_features.values()
                      if feature.get("kind") == "sector" and feature.get("hasBoundary")
                      and isinstance(feature.get("pickerGroupId"), str)
                      and feature["pickerGroupId"] != feature["id"]
                      and feature.get("delegationId") in coverage["currentSourceIds"]]
    ids = _resolve_selectable_ids(requested_ids, by_id, metadata_features, preferred)
    preferred_owner = {sector_id: group_id for group_id, sector_id in preferred.items()}
    locations = []
    for location_id in ids:
        r = by_id[location_id]
        # A namesake sector inherits the former visible group's saved point and
        # prayer source when those were explicitly selected in coverage.
        selected = (manual.get(preferred_owner[location_id]) if location_id in preferred_owner
                    else manual.get(location_id))
        lat, lng = ((selected["lat"], selected["lng"]) if selected
                    else (r["lat"], r["lng"]))
        if not (math.isfinite(lat) and math.isfinite(lng)
                and -90 <= lat <= 90 and -180 <= lng <= 180):
            raise ValueError(f"Invalid coordinates: {location_id}")
        if selected:
            source_id = selected["sourceId"]
        elif location_id.startswith("delegation:"):
            source_id = int(location_id.split(":")[1])
        else:
            source_id = r.get("delegationId")
        if source_id not in coverage["currentSourceIds"]:
            raise ValueError(f"Unavailable prayer source: {location_id}")
        source = sources[source_id]
        override = displays.get(location_id, {})
        aliases = r.get("aliases", []) + override.get("searchAliases", [])
        search_terms = r.get("contextAliases", []) + [r.get("governorateFr", "")]
        item = {
            "id": location_id,
            "nameAr": override.get("nameAr", r["name"]),
            "aliases": list(dict.fromkeys(a for a in aliases if isinstance(a, str) and a.strip())),
            "searchTerms": list(dict.fromkeys(a for a in search_terms if isinstance(a, str) and a.strip())),
            "governorateAr": r["governorate"],
            "parentAr": r.get("parentName", ""),
            "kind": r["kind"], "lat": lat, "lng": lng,
            "hasBoundary": bool(r.get("hasBoundary")),
            "prayerSource": {"id": source_id, "nameAr": source["nomAr"],
                             "lat": source["lat"], "lng": source["lng"]},
        }
        identity = {**item, "members": r.get("members", []),
                    "geometrySha256": r.get("geometrySha256"),
                    "catalogRowFingerprint": r.get("fingerprint")}
        item["fingerprint"] = hashlib.sha256(canonical(identity)).hexdigest()
        locations.append(item)
    verify()
    catalog = {"schemaVersion": 1, "generatedAtUtc": datetime.now(timezone.utc).isoformat(),
               "catalogFingerprint": hashlib.sha256(canonical(locations)).hexdigest(),
               "sourcePins": pins, "locations": locations}
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("x", encoding="utf-8") as f:
        json.dump(catalog, f, ensure_ascii=False, indent=2)
        f.write("\n")
    return {"locations": len(locations), "catalog": str(output),
            "sha256": digest(output)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pins", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    print(json.dumps(export_catalog(args.pins, args.output)))


if __name__ == "__main__":
    main()
