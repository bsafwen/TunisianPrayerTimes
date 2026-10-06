"""Stage source-pinned locality identity changes while preserving every geometry byte."""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path
import re

FIELDS = {"name", "aliases", "parentName", "contextAliases"}


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def pin(path):
    return {"file": str(path.resolve()), "sha256": sha(path)}


def checked(ref):
    path = Path(ref["file"])
    if sha(path) != ref["sha256"]:
        raise ValueError(f"Pinned input changed: {path}")
    return path


def write(path, value, compact=False):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=None if compact else 2,
                               separators=(",", ":") if compact else None) + "\n",
                    encoding="utf-8", newline="\n")


def stage(args):
    if args.output.exists():
        raise ValueError("Use a fresh staging directory")
    proposal = read(args.proposal)
    if proposal.get("status") != "READY_METADATA_CORRECTIONS":
        raise ValueError("Metadata proposal is not ready")
    before_path = checked(proposal["baseCatalogMetadata"])
    binary_path = checked(proposal["baseCatalogBinary"])
    before, blob = read(before_path), binary_path.read_bytes()
    after = copy.deepcopy(before)
    old_rows = {row["id"]: row for row in before["features"]}
    rows = {row["id"]: row for row in after["features"]}
    seen_ids, seen_codes, changes = set(), set(), []
    for patch in proposal["patches"]:
        ident, code = patch["id"], patch["officialCode"]
        if (ident in seen_ids or code in seen_codes or not isinstance(code, str)
                or not re.fullmatch(r"\d{6}", code) or ident not in rows
                or rows[ident]["kind"] != "sector"):
            raise ValueError("Metadata patch needs a unique current sector and official code")
        if set(patch) & {"geometry", "boundaryScope", "sourcePdf"}:
            raise ValueError("Metadata proposal cannot modify boundary scope or source")
        seen_ids.add(ident)
        seen_codes.add(code)
        new_values = patch["proposedMetadata"]
        if not isinstance(new_values, dict) or not new_values or not set(new_values) <= FIELDS:
            raise ValueError("Only names, aliases and parent context may change")
        for field, value in new_values.items():
            if field in ("name", "parentName"):
                if not isinstance(value, str) or not value.strip():
                    raise ValueError("Display names and parents must be nonblank")
            elif (not isinstance(value, list) or any(not isinstance(v, str) or not v.strip() for v in value)
                  or len(value) != len(set(value))):
                raise ValueError("Search forms must be unique nonblank strings")
        old = old_rows[ident]
        candidate = {**old, **new_values}
        for field in ("aliases", "contextAliases"):
            if not set(old.get(field, [])) <= set(candidate.get(field, [])):
                raise ValueError("All existing search forms must remain")
        if candidate["name"] != old["name"] and old["name"] not in candidate["aliases"]:
            raise ValueError("Former primary name must remain searchable")
        if candidate["parentName"] != old["parentName"] and old["parentName"] not in candidate.get("contextAliases", []):
            raise ValueError("Former parent must remain searchable")
        delta = {key: {"before": old.get(key), "after": value} for key, value in new_values.items()
                 if old.get(key) != value}
        if not delta:
            raise ValueError("Do not stage unchanged metadata rows")
        evidence = read(checked(patch["metadataEvidence"]))
        if evidence.get("status") != "ACCEPTED_METADATA_IDENTITY_CORRECTIONS":
            raise ValueError("Metadata evidence needs explicit identity acceptance")
        matches = [row for row in evidence.get("changes", []) if row.get("id") == ident and row.get("officialCode") == code]
        if len(matches) != 1 or matches[0].get("fields") != delta:
            raise ValueError("Proposal differs from its pinned identity evidence")
        rows[ident].update(new_values)
        changes.append({"id": ident, "officialCode": code, "fields": delta})
    if not changes:
        raise ValueError("No metadata changes")
    for key, value in before.items():
        if key != "features" and value != after[key]:
            raise ValueError(f"Unrelated catalog field changed: {key}")
    for ident, old in old_rows.items():
        allowed = FIELDS if ident in seen_ids else set()
        if {k: v for k, v in old.items() if k not in allowed} != {k: v for k, v in rows[ident].items() if k not in allowed}:
            raise ValueError("Geometry, coordinate, source or identity changed")
    args.output.mkdir(parents=True)
    (args.output / "before-neighborhoods.json").write_bytes(before_path.read_bytes())
    (args.output / "before-neighborhoods.bin").write_bytes(blob)
    write(args.output / "neighborhoods.json", after, True)
    (args.output / "neighborhoods.bin").write_bytes(blob)
    report = {"status": "STAGED_METADATA_REQUIRES_INDEPENDENT_REVIEW", "proposal": pin(args.proposal),
              "beforeMetadata": pin(args.output / "before-neighborhoods.json"),
              "beforeBinary": pin(args.output / "before-neighborhoods.bin"),
              "stagedMetadata": pin(args.output / "neighborhoods.json"),
              "stagedBinary": pin(args.output / "neighborhoods.bin"),
              "metadataLocalityCodes": sorted(seen_codes), "metadataChanges": changes,
              "allGeometryBytesIdentical": True, "coordinatesAndPrayerSourcesIdentical": True,
              "allOtherMetadataIdentical": True}
    write(args.output / "stage-report.json", report)
    print(json.dumps({"status": report["status"], "metadataLocalityCount": len(seen_codes)}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--proposal", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    stage(parser.parse_args())
