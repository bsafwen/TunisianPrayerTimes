#!/usr/bin/env python3
"""Check one governorate's installed sector codes against the saved INS registry.

The app does not carry a code field. OSM sector IDs obtain their candidate code
from the pinned source area's ref:tn:codegeo tag; ISIE sector IDs carry the code
in their ID. This checks identity links, not polygon accuracy or source rights.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path
import sys

from verify_ministry_imada_inventory import normalized, normalized_parent, sha256


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", required=True, type=Path)
    parser.add_argument("--registry", required=True, type=Path)
    parser.add_argument("--osm-areas", required=True, type=Path)
    parser.add_argument("--governorate-id", required=True, type=int)
    parser.add_argument("--governorate-name", required=True)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()

    features = json.loads(args.catalog.read_text(encoding="utf-8"))["features"]
    sectors = [row for row in features if row.get("kind") == "sector"
               and row.get("governorateId") == args.governorate_id]
    official = [row for row in json.loads(args.registry.read_text(encoding="utf-8"))["sectors"]
                if normalized(row["governorateAr"]) == normalized(args.governorate_name)]
    areas = {row["id"]: row for row in
             json.loads(args.osm_areas.read_text(encoding="utf-8"))["areas"]}
    official_by_code = defaultdict(list)
    for row in official:
        official_by_code[str(row["sectorCode"])].append(row)

    results = []
    for feature in sectors:
        feature_id = feature["id"]
        if feature_id.startswith("isie:sector:"):
            code = feature_id.removeprefix("isie:sector:")
            code_source = "isie-sector-id"
        elif feature_id.startswith("osm:"):
            area = areas.get(feature_id)
            code = str((area or {}).get("tags", {}).get("ref:tn:codegeo", "")).strip()
            code_source = "osm-source-tag" if area else "missing-osm-source-area"
        else:
            code = ""
            code_source = "unsupported-feature-id"
        candidates = official_by_code.get(code, []) if code else []
        name_match = len(candidates) == 1 and normalized(feature["name"]) == normalized(candidates[0]["sectorAr"])
        parent_match = len(candidates) == 1 and normalized_parent(feature.get("parentName", "")) == normalized_parent(candidates[0]["delegationAr"])
        results.append({
            "appId": feature_id,
            "appName": feature["name"],
            "appParent": feature.get("parentName", ""),
            "code": code,
            "codeSource": code_source,
            "officialCandidateCount": len(candidates),
            "officialName": candidates[0]["sectorAr"] if len(candidates) == 1 else None,
            "officialParent": candidates[0]["delegationAr"] if len(candidates) == 1 else None,
            "nameMatchesNormalized": name_match,
            "parentMatchesNormalized": parent_match,
        })

    code_counts = Counter(row["code"] for row in results if row["code"])
    exceptions = [row for row in results if not row["code"]
                  or row["officialCandidateCount"] != 1
                  or not row["nameMatchesNormalized"]
                  or not row["parentMatchesNormalized"]
                  or code_counts[row["code"]] != 1]
    matched_codes = set(code_counts) & set(official_by_code)
    report = {
        "schemaVersion": 1,
        "governorate": args.governorate_name,
        "governorateId": args.governorate_id,
        "scope": "INS sector code and normalized Arabic name/parent identity evidence only; no geographic or legal reliability claim.",
        "pins": {"catalogSha256": sha256(args.catalog), "registrySha256": sha256(args.registry),
                 "osmAreasSha256": sha256(args.osm_areas)},
        "counts": {"appSectors": len(sectors), "officialRegistrySectors": len(official),
                   "appCodesPresent": sum(bool(row["code"]) for row in results),
                   "distinctAppCodes": len(code_counts),
                   "officialCodesMatched": len(matched_codes),
                   "normalizedNameParentAndCodeMatches": len(results) - len(exceptions),
                   "exceptions": len(exceptions),
                   "officialCodesNotInstalled": len(set(official_by_code) - set(code_counts))},
        "codeSourceCounts": dict(sorted(Counter(row["codeSource"] for row in results).items())),
        "exceptions": exceptions,
        "officialCodesNotInstalled": sorted(set(official_by_code) - set(code_counts)),
        "limitations": [
            "The INS registry is a published census hierarchy, independent of the Ministry name list.",
            "OSM code tags are candidate identity links, corroborated here by INS Arabic name and parent; they do not prove polygon shape.",
            "ISIE feature IDs were assigned during earlier source review and are checked against INS, not independently re-extracted here.",
        ],
    }
    rendered = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(rendered, encoding="utf-8")
    sys.stdout.reconfigure(encoding="utf-8")
    print(rendered, end="")
    return 0 if not exceptions and not report["officialCodesNotInstalled"] and len(sectors) == len(official) else 1


if __name__ == "__main__":
    raise SystemExit(main())
