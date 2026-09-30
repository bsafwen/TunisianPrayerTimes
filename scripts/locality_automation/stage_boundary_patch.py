"""Stage a pinned locality-boundary patch without changing the live catalog.

The proposal owns source interpretation. This helper preserves record identity,
unrelated packed bytes and country geometry, and regenerates derived indexes.
Installation uses locality_automation.installer after geographic/GPS review.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
import copy
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import sys

from shapely.geometry import MultiPolygon, Polygon
from shapely.wkb import loads

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))
from scripts.locality_review.boundary import _parse_multipolygon


def read(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def pin(path: Path) -> dict:
    return {"file": str(path.resolve()), "sha256": sha(path)}


def checked(ref: dict) -> Path:
    path = Path(ref["file"])
    if sha(path) != ref["sha256"]:
        raise ValueError(f"Pinned input changed: {path}")
    return path


def write(path: Path, value: dict, *, compact: bool = False) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False,
                              indent=None if compact else 2,
                              separators=(",", ":") if compact else None) + "\n",
                    encoding="utf-8", newline="\n")


def decode(blob: bytes, row: dict, scale: int):
    parts = _parse_multipolygon(blob, row["offset"], row["length"], scale, row.get("id", "country"))
    return MultiPolygon([Polygon(rings[0], rings[1:]) for rings in parts])


def rebuild_grid(features: list, size: float) -> dict:
    cells = defaultdict(list)
    for index, row in enumerate(features):
        if not row["hasBoundary"]:
            continue
        x1, y1, x2, y2 = row["bbox"]
        for y in range(math.floor(y1 / size), math.floor(y2 / size) + 1):
            for x in range(math.floor(x1 / size), math.floor(x2 / size) + 1):
                cells[f"{y}:{x}"].append(index)
    return dict(sorted(cells.items()))


def stage(proposal_path: Path, output: Path) -> dict:
    proposal = read(proposal_path)
    metadata = checked(proposal["baseCatalogMetadata"])
    binary = checked(proposal["baseCatalogBinary"])
    before, blob = read(metadata), binary.read_bytes()
    if blob[:8] != b"NPOL\x00\x00\x00\x01":
        raise ValueError("Unsupported packed format")
    if output.exists():
        raise ValueError("Use a fresh staging directory")
    patches = proposal["patches"]
    changed_ids = {row["id"] for row in patches}
    if len(changed_ids) != len(patches) or not patches:
        raise ValueError("Missing or duplicate patch identities")
    specification = importlib.util.spec_from_file_location("locality_packer", REPO / "scripts/generate_neighborhoods.py")
    packer = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(packer)
    if before["coordinateScale"] != packer.SCALE:
        raise ValueError("Packer coordinate scale differs from catalog")
    rows_by_id = {row["id"]: row for row in before["features"]}
    candidate_bytes, candidates = {}, {}
    for patch in patches:
        row = rows_by_id[patch["id"]]
        if row["kind"] != "sector" or not row["hasBoundary"]:
            raise ValueError("Boundary patch must name a current official sector")
        checked(patch["sourcePdf"])
        geom = loads(checked(patch["geometry"]).read_bytes())
        if not isinstance(geom, (Polygon, MultiPolygon)) or not geom.is_valid or geom.is_empty:
            raise ValueError(f"Invalid proposed polygon: {patch['id']}")
        packed = packer.packed_geometry_bytes(geom)
        decoded = decode(blob[:8] + packed,
                         {"id": row["id"], "offset": 8, "length": len(packed)}, packer.SCALE)
        if not decoded.is_valid or not decoded.equals(geom):
            raise ValueError(f"Proposed geometry is not a valid exact app-grid polygon: {patch['id']}")
        candidate_bytes[row["id"]], candidates[row["id"]] = packed, decoded
    after = copy.deepcopy(before)
    after_by_id = {row["id"]: row for row in after["features"]}
    packed = bytearray(blob[:8])
    cursor = 8
    ordered = sorted((r for r in before["features"] if r["hasBoundary"]), key=lambda r: r["offset"])
    for row in ordered:
        if row["offset"] != cursor:
            raise ValueError("Boundary slices are not contiguous; refuse to discard bytes")
        old_slice = blob[cursor:cursor + row["length"]]
        new_slice = candidate_bytes.get(row["id"], old_slice)
        target = after_by_id[row["id"]]
        target["offset"], target["length"] = len(packed), len(new_slice)
        packed.extend(new_slice)
        cursor += row["length"]
    if cursor != before["country"]["offset"]:
        raise ValueError("Unrecognized bytes before country geometry")
    after["country"]["offset"] = len(packed)
    packed.extend(blob[cursor:])
    for patch in patches:
        target, geom = after_by_id[patch["id"]], candidates[patch["id"]]
        reviewed_date = proposal.get("reviewedDate", "2026-09-30")
        prefix = proposal.get("sourceIdPrefix", "osm-isie-source-bounded")
        source_id = f"{prefix}-{patch['officialCode']}-{reviewed_date.replace('-', '')}"
        if source_id in after["sources"]:
            raise ValueError("Source ID already exists; inspect prior installation")
        target["sourceId"] = source_id
        target["bbox"] = list(geom.bounds)
        target["areaKm2"] = geom.area * 111.32**2 * math.cos(math.radians(geom.representative_point().y))
        after["sources"][source_id] = {
            "id": source_id, "provider": "OpenStreetMap contributors; ISIE 2023 shared-edge correction",
            "url": patch["sourceUrl"], "sha256": patch["geometry"]["sha256"],
            "sourceSha256": before["source"]["sha256"],
            "review": {"reviewedDate": reviewed_date, "scope": patch["qualification"],
                       "proposalSha256": sha(proposal_path), "sourcePdfSha256": patch["sourcePdf"]["sha256"],
                       "uncertainty": "Outer installed footprint retained; this is a partial boundary correction."}}
    after["cells"] = rebuild_grid(after["features"], after["gridSize"])
    boundary_rows = [row for row in after["features"] if row["hasBoundary"]]
    geometries = [decode(bytes(packed), row, after["coordinateScale"]) for row in boundary_rows]
    regenerated = packer.detect_conflicts(boundary_rows, geometries)
    after["conflicts"] = sorted(
        [item for item in before["conflicts"] if not changed_ids.intersection(item["ids"])] +
        [item for item in regenerated if changed_ids.intersection(item["ids"])], key=lambda item: item["ids"])
    for old in before["features"]:
        new = after_by_id[old["id"]]
        allowed = {"offset", "length", "sourceId", "bbox", "areaKm2"} if old["id"] in changed_ids else {"offset"}
        if {k: v for k, v in old.items() if k not in allowed} != {k: v for k, v in new.items() if k not in allowed}:
            raise ValueError(f"Unapproved record change: {old['id']}")
        if old["hasBoundary"] and old["id"] not in changed_ids:
            if blob[old["offset"]:old["offset"] + old["length"]] != packed[new["offset"]:new["offset"] + new["length"]]:
                raise ValueError(f"Unrelated geometry changed: {old['id']}")
    if packed[after["country"]["offset"]:] != blob[before["country"]["offset"]:]:
        raise ValueError("Country geometry changed")
    output.mkdir(parents=True)
    (output / "before-neighborhoods.json").write_bytes(metadata.read_bytes())
    (output / "before-neighborhoods.bin").write_bytes(blob)
    write(output / "neighborhoods.json", after, compact=True)
    (output / "neighborhoods.bin").write_bytes(packed)
    result = {"status": "STAGED_REQUIRES_GEOGRAPHIC_AND_GPS_REVIEW", "proposal": pin(proposal_path),
              "beforeMetadata": pin(output / "before-neighborhoods.json"),
              "beforeBinary": pin(output / "before-neighborhoods.bin"),
              "stagedMetadata": pin(output / "neighborhoods.json"), "stagedBinary": pin(output / "neighborhoods.bin"),
              "changedIds": sorted(changed_ids), "unrelatedRecordIdentityPreserved": True,
              "unrelatedPackedGeometryByteIdentical": True, "countryGeometryByteIdentical": True,
              "oldIncidentConflicts": [c for c in before["conflicts"] if changed_ids.intersection(c["ids"])],
              "newIncidentConflicts": [c for c in after["conflicts"] if changed_ids.intersection(c["ids"])]}
    write(output / "stage-report.json", result)
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--proposal", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = stage(args.proposal, args.output)
    print(json.dumps({k: result[k] for k in ("status", "changedIds", "oldIncidentConflicts", "newIncidentConflicts")}, ensure_ascii=False))
