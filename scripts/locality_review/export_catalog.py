"""Export the currently selectable catalog for the local human review screen.

Inputs are explicitly pinned. This reads the existing catalog helper; it never
refreshes its historical ledger or changes Android data.
"""
import argparse
import hashlib
import importlib.util
import json
import math
from datetime import datetime, timezone
from pathlib import Path


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True,
                      separators=(",", ":"), allow_nan=False).encode("utf-8")


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pins", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    output = Path(args.output).resolve()
    if output.exists():
        raise ValueError("Output already exists; use a new snapshot filename")
    all_pins = read(args.pins)
    keys = ("helper", "metadata", "binary", "governors", "displayNames", "coverage")
    pins = {k: all_pins[k] for k in keys}

    def verify():
        for key, value in pins.items():
            if digest(value["file"]) != value["sha256"]:
                raise ValueError(f"Input changed: {key}")

    verify()
    coverage = read(pins["coverage"]["file"])["prayerSelection"]
    displays = {r["id"]: r for r in read(pins["displayNames"]["file"])["names"]}
    govs = read(pins["governors"]["file"])["gouvernorats"]
    sources = {r["id"]: r for g in govs for r in g["delegations"]}
    spec = importlib.util.spec_from_file_location("review_export_helper", pins["helper"]["file"])
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)
    metadata_sha, rows = helper.catalog()
    if metadata_sha != pins["metadata"]["sha256"]:
        raise ValueError("Helper read a different metadata snapshot")
    by_id = {r["id"]: r for r in rows}
    if len(by_id) != len(rows):
        raise ValueError("Duplicate helper IDs")
    manual = {r["id"]: r for r in coverage["currentManualSelections"]}
    if len(manual) != len(coverage["currentManualSelections"]):
        raise ValueError("Duplicate selectable IDs")
    ids = list(manual)
    ids += [f"delegation:{i}" for i in coverage["currentSourceIds"]
            if f"delegation:{i}" not in manual]
    locations = []
    for location_id in ids:
        r = by_id[location_id]
        selected = manual.get(location_id)
        lat, lng = ((selected["lat"], selected["lng"]) if selected
                    else (r["lat"], r["lng"]))
        if not (math.isfinite(lat) and math.isfinite(lng)
                and -90 <= lat <= 90 and -180 <= lng <= 180):
            raise ValueError(f"Invalid coordinates: {location_id}")
        source_id = selected["sourceId"] if selected else int(location_id.split(":")[1])
        if source_id not in coverage["currentSourceIds"]:
            raise ValueError(f"Unavailable prayer source: {location_id}")
        source = sources[source_id]
        override = displays.get(location_id, {})
        aliases = (r.get("aliases", []) + override.get("searchAliases", []))
        search_terms = (r.get("contextAliases", []) + [r.get("governorateFr", "")])
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
    print(json.dumps({"locations": len(locations), "catalog": str(output),
                      "sha256": digest(output)}))


if __name__ == "__main__":
    main()
