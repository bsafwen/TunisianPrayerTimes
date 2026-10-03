"""Rank exact cached OSM identity leads by neighboring delegation, with no credit.

No fuzzy name match, retrieval, source assembly, scope approval or acceptance.
Existing source presence and exact INS/Ministry/current identities permit a
future review; unchecked ISIE/Google fields remain unchecked.
"""
import argparse
from collections import Counter, defaultdict
import csv
from datetime import datetime, timezone
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts.locality_automation.run_sealed_boundary_queue import checked, pin, read


def plain_name(value):
    return " ".join(value.split()) if isinstance(value, str) else ""


def plan(manifest, limit):
    if not isinstance(limit, int) or isinstance(limit, bool) or limit <= 0:
        raise ValueError("Positive finite case limit required")
    publication = read(checked(manifest["publication"]))
    metadata = read(checked(manifest["metadata"]))
    original = read(checked(manifest["originalOsmAreas"]))
    registry = read(checked(manifest["insRegistry"]))
    with checked(manifest["functionalMinistryMatrix"]).open(encoding="utf-8-sig", newline="") as stream:
        ministry = list(csv.DictReader(stream))
    with checked(manifest["recordInventory"]).open(encoding="utf-8-sig", newline="") as stream:
        inventory = list(csv.DictReader(stream))
    if len(ministry) != 2084:
        raise ValueError("Expected Ain Saboun-excluded 2084-entry Ministry matrix")
    if any(publication["summary"].get(key) != [] for key in ("validationIssues", "sourceOnlyAuditIssues", "reportingIssues")):
        raise ValueError("Current publication has unresolved reporting issues")
    already = set(publication["summary"]["validatedLocationCodes"])
    def unique_ids(rows):
        ids = [row["id"] for row in rows]
        if len(ids) != len(set(ids)):
            raise ValueError("Duplicate identity in a pinned source inventory")
        return dict(zip(ids, rows))
    current = unique_ids(metadata["features"])
    records = unique_ids(inventory)
    areas = unique_ids(original["areas"])
    ministry_bindings = Counter((tuple(json.loads(row["identityCandidateCodes"])), row["frozenFunctionalExpectedId"])
                                for row in ministry)
    ins = defaultdict(list)
    for row in registry["sectors"]:
        ins[row["sectorCode"]].append(row)
    candidates, reasons = [], Counter()
    for member in ministry:
        codes = json.loads(member["identityCandidateCodes"])
        ident = member["frozenFunctionalExpectedId"]
        if len(codes) != 1:
            reasons["nonunique_code"] += 1
            continue
        code = codes[0]
        if ministry_bindings[(tuple(codes), ident)] != 1:
            reasons["nonunique_ministry_binding"] += 1
            continue
        if code in already:
            reasons["already_geographically_validated"] += 1
            continue
        if ident not in current or ident not in areas or ident not in records or len(ins[code]) != 1:
            reasons["no_unique_current_original_registry_binding"] += 1
            continue
        feature, original_row, row, official = current[ident], areas[ident], records[ident], ins[code][0]
        tags = original_row["tags"]
        parent = plain_name(feature["parentName"])
        if parent.startswith("معتمدية "):
            parent = parent[len("معتمدية "):]
        if (feature.get("hasBoundary") is not True or feature.get("kind") != "sector"
                or tags.get("boundary") != "administrative" or tags.get("admin_level") != "6"
                or original_row.get("geometry", {}).get("type") not in ("Polygon", "MultiPolygon")):
            reasons["not_cached_complete_sector_body_lead"] += 1
            continue
        names = [member["name"], feature["name"], tags.get("name:ar", tags.get("name")), official["sectorAr"]]
        if len({plain_name(name) for name in names}) != 1 or not all(plain_name(name) for name in names):
            reasons["literal_name_conflict"] += 1
            continue
        if (parent != plain_name(official["delegationAr"]) or plain_name(member["delegation"]) != parent
                or plain_name(member["governorate"]) != plain_name(official["governorateAr"])
                or json.loads(row["candidateCodes"]) != [code]):
            reasons["literal_parent_governorate_or_inventory_conflict"] += 1
            continue
        # Disclose original tag disagreement rather than silently repairing it.
        original_code = tags.get("ref:tn:codegeo")
        if original_code is not None and original_code != code:
            reasons["original_code_tag_conflict"] += 1
            continue
        candidates.append({"officialCode": code, "id": ident, "name": feature["name"],
            "delegation": parent, "delegationCode": official["delegationCode"], "governorate": member["governorate"],
            "ministryCsvLine": member["ministryCsvLine"], "sourcePresence": "documented_cached_osm_named_body",
            "originalCodeTag": original_code, "currentRetrievalProofStatus": member["currentRetrievalProofStatus"],
            "ISIEPresenceStatus": member["ISIEPresenceStatus"], "GooglePresenceStatus": member["GooglePresenceStatus"],
            "nativeAssemblyStatus": "unchecked", "fullScopeStatus": "unapproved", "newCredit": 0})
    families = defaultdict(list)
    for row in candidates:
        families[row["delegationCode"]].append(row)
    ordered = sorted(families.values(), key=lambda rows: (-len(rows), rows[0]["delegationCode"]))
    chosen = [row for rows in ordered for row in sorted(rows, key=lambda row: row["officialCode"])][:limit]
    return {"status": "PREPARATION_ONLY_EXACT_CACHED_FAMILY_LEADS", "createdAtUtc": datetime.now(timezone.utc).isoformat(),
        "candidateCount": len(candidates), "familyCount": len(families), "selectedFiniteCount": len(chosen),
        "selected": chosen, "rankedFamilies": [{"delegationCode": rows[0]["delegationCode"],
            "delegation": rows[0]["delegation"], "count": len(rows)} for rows in ordered],
        "excludedReasons": dict(reasons), "inputs": manifest, "newCredit": 0,
        "sourcePoolAdmitted": False, "civilExtentOrSourceBodyVerified": False,
        "qualification": "Literal cached existence/identity leads only. Root must approve a finite source pool; original XML/member-native, independent scope/identity/neighbor/topology/GPS/runtime/consumer gates remain required. Unchecked searches stay unknown; no redundant search is required after a documented positive match."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--limit", type=int, default=12)
    args = parser.parse_args()
    result = plan(read(args.manifest), args.limit)
    with args.output.open("x", encoding="utf-8") as stream:
        stream.write(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"status": result["status"], "candidates": result["candidateCount"], "families": result["familyCount"],
                      "selected": [row["officialCode"] for row in result["selected"]], "output": pin(args.output)}))


if __name__ == "__main__":
    main()
