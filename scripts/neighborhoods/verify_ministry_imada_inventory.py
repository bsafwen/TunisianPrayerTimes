#!/usr/bin/env python3
"""Compare one governorate's selectable imadas with a saved Ministry name list.

This checks primary Arabic names and administrative parents, not boundary accuracy
or the validity of INS/OSM code assignments. The input CSV is the parsed, dated
Ministry catalog snapshot and must have governorate, delegation, and name columns.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
import csv
import hashlib
import json
from pathlib import Path
import re
import sys
import unicodedata


def normalized(value: str) -> str:
    value = unicodedata.normalize("NFKD", value or "")
    value = "".join(c for c in value if not unicodedata.category(c).startswith("M"))
    value = value.replace("ـ", "")
    for old, new in (("أ", "ا"), ("إ", "ا"), ("آ", "ا"), ("ٱ", "ا"),
                     ("ى", "ي"), ("ؤ", "و"), ("ئ", "ي")):
        value = value.replace(old, new)
    return re.sub(r"[^\w]+", "", value, flags=re.UNICODE).lower()


def normalized_parent(value: str) -> str:
    value = re.sub(r"^\s*معتمدية\s*", "", value or "")
    return normalized(value)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ministry-csv", type=Path, required=True)
    parser.add_argument("--assets-dir", type=Path, required=True)
    parser.add_argument("--governorate", required=True)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()

    with args.ministry_csv.open(encoding="utf-8-sig", newline="") as stream:
        official = [row for row in csv.DictReader(stream)
                    if normalized(row["governorate"]) == normalized(args.governorate)]
    if not official:
        raise ValueError(f"No Ministry entries found for {args.governorate}")

    governors = json.loads((args.assets_dir / "gouvernorats.json").read_text(encoding="utf-8"))["gouvernorats"]
    government = [item for item in governors if normalized(item["nomAr"]) == normalized(args.governorate)]
    if len(government) != 1:
        raise ValueError(f"Expected exactly one app governorate for {args.governorate}")
    catalog_path = args.assets_dir / "neighborhoods.json"
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    sectors = [feature for feature in catalog["features"]
               if feature.get("kind") == "sector" and feature.get("governorateId") == government[0]["id"]]

    official_by_pair = defaultdict(list)
    app_by_pair = defaultdict(list)
    for row in official:
        official_by_pair[(normalized(row["name"]), normalized_parent(row["delegation"]))].append(row)
    for feature in sectors:
        app_by_pair[(normalized(feature.get("name", "")), normalized_parent(feature.get("parentName", "")))].append(feature)

    matches, exceptions, extra = [], [], []
    for pair, ministry_rows in official_by_pair.items():
        app_rows = app_by_pair.get(pair, [])
        if len(ministry_rows) == 1 and len(app_rows) == 1:
            matches.append({"name": ministry_rows[0]["name"], "delegation": ministry_rows[0]["delegation"],
                            "appId": app_rows[0]["id"], "appName": app_rows[0]["name"]})
        else:
            exceptions.append({"name": ministry_rows[0]["name"], "delegation": ministry_rows[0]["delegation"],
                               "ministryOccurrences": len(ministry_rows),
                               "appCandidates": [{"id": item["id"], "name": item["name"]} for item in app_rows]})
    for pair, app_rows in app_by_pair.items():
        if pair not in official_by_pair:
            extra.extend({"id": item["id"], "name": item["name"], "parentName": item.get("parentName")}
                         for item in app_rows)
    groups = defaultdict(list)
    for feature in sectors:
        groups[feature.get("pickerGroupId") or feature["id"]].append(feature["id"])
    duplicate_groups = [{"pickerGroupId": group, "appIds": ids}
                        for group, ids in groups.items() if len(ids) > 1]

    report = {
        "schemaVersion": 1,
        "governorate": args.governorate,
        "ministryCsvSha256": sha256(args.ministry_csv),
        "appCatalogSha256": sha256(catalog_path),
        "scope": "Primary Arabic name and administrative-parent inventory only; code and polygon reliability require separate review.",
        "counts": {"ministryEntries": len(official), "appSectorRows": len(sectors),
                   "uniqueNameParentMatches": len(matches), "exceptions": len(exceptions),
                   "unclaimedAppSectors": len(extra), "duplicatePickerGroups": len(duplicate_groups)},
        "exceptions": exceptions,
        "unclaimedAppSectors": extra,
        "duplicatePickerGroups": duplicate_groups,
    }
    rendered = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(rendered, encoding="utf-8")
    sys.stdout.reconfigure(encoding="utf-8")
    print(rendered, end="")
    return 0 if not exceptions and not extra and not duplicate_groups and len(official) == len(sectors) else 1


if __name__ == "__main__":
    raise SystemExit(main())
