"""Finite full source-driven GPS audit, explicitly solo and unaccepted.

Reuse the pinned original numeric oracle and full indexed/exhaustive formulas.
Every observation is durable before classification. No acceptance or asset writes.
"""
from __future__ import annotations

import argparse
import ast
from collections import Counter
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import sys

from pyproj import Transformer
from shapely import from_wkb, normalize
from shapely.geometry import MultiPolygon, Point, Polygon
from shapely.ops import transform

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import active_control, checked, pin, read
from scripts.locality_automation.audit_prepared_osm_graphs import write_new
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree
from scripts.locality_automation.packed_gps_replay import (
    PackedGpsReplay, contains_geometry, edge_clearance_meters, COORDINATE_EPSILON,
)
from scripts.locality_automation.work_window_guard import parse_utc


def numeric_exhaustive(reference):
    path = checked(reference)
    tree = ast.parse(path.read_text(encoding="utf-8-sig"))
    function = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == "exhaustive")
    namespace = {"COORDINATE_EPSILON": COORDINATE_EPSILON,
                 "contains_geometry": contains_geometry, "edge_clearance_meters": edge_clearance_meters}
    exec(compile(ast.Module(body=[function], type_ignores=[]), str(path), "exec"), namespace)
    return namespace["exhaustive"]


def nearest_source(delegations, lat, lon):
    values = []
    for row in delegations:
        dl, dn = math.radians(row["lat"] - lat), math.radians(row["lng"] - lon)
        a = math.sin(dl / 2) ** 2 + math.cos(math.radians(lat)) * math.cos(math.radians(row["lat"])) * math.sin(dn / 2) ** 2
        values.append((6371000 * 2 * math.atan2(math.sqrt(a), math.sqrt(max(0, 1 - a))), row["id"]))
    return min(values)[1]


def protected_bodies(replay, binary, lineage):
    rows = lineage["bindings"]
    if lineage["bindingCount"] != 323 or len(rows) != 323:
        raise ValueError("Complete protected323 lineage required")
    if len({row["officialCode"] for row in rows}) != 323 or len({row["id"] for row in rows}) != 323:
        raise ValueError("Protected code/id binding is not unique")
    proof = []
    for row in rows:
        if row.get("acceptedId") is not None and row["acceptedId"] != row["id"]:
            raise ValueError("Protected accepted identity differs")
        feature = replay.by_id[row["id"]]
        body = replay.geometry(row["id"])
        raw = binary[feature["offset"]:feature["offset"] + feature["length"]]
        if not feature["hasBoundary"] or not body.is_valid or body.is_empty:
            raise ValueError("Invalid protected whole body")
        packed_hash = hashlib.sha256(raw).hexdigest()
        decoded_hash = hashlib.sha256(body.wkb).hexdigest()
        # Match the original numeric consumer's heterogeneous body binding:
        # every declared hash and expected adopted body is mandatory, and at
        # least one original whole-body binding must exist. Never invent fields.
        if ("decodedWkbSha256" in row and decoded_hash != row["decodedWkbSha256"]
                or "packedSliceSha256" in row and packed_hash != row["packedSliceSha256"]):
            raise ValueError("Protected accepted body changed")
        if "expectedAdoptedGeometry" in row:
            accepted = from_wkb(checked(row["expectedAdoptedGeometry"]).read_bytes())
            if not accepted.is_valid or normalize(accepted).wkb != normalize(body).wkb:
                raise ValueError("Protected expected adopted body changed")
        if not any(key in row for key in ("packedSliceSha256", "decodedWkbSha256", "expectedAdoptedGeometry")):
            raise ValueError("Missing original protected whole-body binding")
        checked(row["authoritativeReference"])
        # Original185 entries legitimately contain null optional GPS refs.
        # Their authoritative whole-body guard and both exact body hashes stay
        # mandatory; do not manufacture an accepted GPS document for a null.
        if "acceptedGpsReference" in row and row["acceptedGpsReference"] is not None:
            checked(row["acceptedGpsReference"])
        entry = {"officialCode": row["officialCode"], "id": row["id"],
                 "packedCurrentSha256": packed_hash, "decodedCurrentSha256": decoded_hash,
                 "originalSourceBinding": row}
        if "acceptedGpsReference" in row:
            entry["acceptedGpsReference"] = row["acceptedGpsReference"]
        proof.append(entry)
    return proof


def run(spec, manifest_path, code, output):
    control = active_control(spec)
    if datetime.now(timezone.utc) >= parse_utc(control["safeSourceQaStartUtc"]):
        raise ValueError("Source start cutoff reached")
    if code not in control["approvedCycle15PreparationPool"] or spec.get("credit") != 0 or output.exists():
        raise ValueError("Fresh finite solo diagnostic phase required")
    check_tree(spec)
    index = read(checked(spec["technicalAuditIndex"]))
    if index["holds"] or index["independentQaPassed"] is not False or index["credit"] != 0:
        raise ValueError("Original solo technical audit was not clean or truthful")
    reference = next(row["report"] for row in index["results"] if row["officialCode"] == code)
    technical = read(checked(reference))
    observation = read(checked(technical["sourceObservation"]))
    ident = technical["id"]
    if (technical["status"] != "PASS_SOLO_TECHNICAL_LITERAL_GRAPH_AND_IDENTITY"
            or technical["disjointReviewer"] is not False or technical["sourceScopeAccepted"] is not False):
        raise ValueError("Solo technical audit cannot be promoted to independent source acceptance")
    replay = PackedGpsReplay(checked(spec["metadata"]), checked(spec["binary"]))
    if len(replay.features) != 3473 or len(replay.boundaries) != 2571:
        raise ValueError("Full current catalogue universe differs")
    binary = checked(spec["binary"]).read_bytes()
    lineage = read(checked(spec["protectedLineage"]))
    protection = protected_bodies(replay, binary, lineage)
    loader = importlib.util.spec_from_file_location("original_solo_source_oracle", checked(spec["oracle"]))
    original = importlib.util.module_from_spec(loader)
    loader.loader.exec_module(original)
    oracle = original.IndependentCurrentOracle(replay)
    exhaustive = numeric_exhaustive(spec["numericOriginal"])
    raw = from_wkb(checked(technical["rawSourceGeometry"]).read_bytes())
    expected = from_wkb(checked(technical["expectedAdoptedGeometry"]).read_bytes())
    current = replay.geometry(ident)
    if not raw.is_valid or not current.is_valid or not expected.equals(current):
        raise ValueError("Frozen source/current bodies differ or are invalid")
    to_m = Transformer.from_crs(4326, 32632, always_xy=True).transform
    to_ll = Transformer.from_crs(32632, 4326, always_xy=True).transform
    raw_m, current_m = transform(to_m, raw), transform(to_m, current)
    country_m = transform(to_m, oracle.country)
    peers, incidents = [], []
    own_bounds = current.bounds
    for row in replay.features:
        if not row["hasBoundary"] or row["id"] == ident:
            continue
        a, b, c, d = row["bbox"]
        left, bottom, right, top = own_bounds
        if c < left or a > right or d < bottom or b > top:
            continue
        body = replay.geometry(row["id"])
        if not body.is_valid:
            raise ValueError("Invalid current peer")
        body_m = transform(to_m, body)
        overlap, raw_overlap = current_m.intersection(body_m), raw_m.intersection(body_m)
        peers.append({"id": row["id"], "metadata": dict(row),
                      "currentIntersectionSquareMeters": overlap.area,
                      "rawIntersectionSquareMeters": raw_overlap.area,
                      "decodedWkbSha256": hashlib.sha256(body.wkb).hexdigest()})
        if not overlap.is_empty:
            incidents.append((overlap, "current-peer-overlap-supporting-only"))
        if not raw_overlap.is_empty:
            incidents.append((raw_overlap, "raw-peer-overlap-supporting-only"))
    points = original.source_probes(raw_m, to_ll, raw, replay.by_id[ident], incidents)
    extra = original.source_probes(current_m, to_ll, current, replay.by_id[ident], incidents)
    seen = {(row["lat"], row["lng"]) for row in points}
    for point in extra:
        key = point["lat"], point["lng"]
        if key not in seen:
            points.append({**point, "sourceClass": "current-grid-" + point["sourceClass"]})
            seen.add(key)
    params = read(checked(spec["prayerParams"]))
    governorates = read(checked(spec["governorates"]))["gouvernorats"]
    eligible = [delegation for gov in governorates for delegation in gov["delegations"] if str(delegation["id"]) in params]
    if len(eligible) != 258 or any(row["id"] == 495 for row in eligible):
        raise ValueError("Original eligible prayer-source universe differs")
    output.mkdir()
    started = datetime.now(timezone.utc).isoformat()
    for number, point in enumerate(points):
        point["id"] = point["name"] = code + "-source-" + str(number).zfill(6)
        point["expectedSourceId"] = nearest_source(eligible, point["lat"], point["lng"])
    probes = write_new(output / "probes.json", {"probes": points})
    requests = write_new(output / "actual-jvm-requests.json", [{key: point[key] for key in ("name", "lat", "lng", "expectedSourceId")} for point in points])
    input_gate = write_new(output / "input-gate.json", {"status": "PINNED_SOLO_TECHNICAL_GPS_INPUTS_NO_ACCEPTANCE",
            "manifest": pin(manifest_path), "technicalAudit": reference, "protected323": protection,
            "probes": probes, "requests": requests, "orderedModes": [None, 5, 20, 50],
            "independentQaPassed": False, "credit": 0, "completeInputPinsChecked": True})
    failures, oracle_disagreements, counts = [], [], Counter()
    journal_path = output / "observations.jsonl"
    with journal_path.open("x", encoding="utf-8") as journal:
        for number, point in enumerate(points):
            lat, lon = point["lat"], point["lng"]
            for mode_number, mode in enumerate((None, 5, 20, 50)):
                indexed = replay.find(lat, lon, mode)
                exhaustive_winner = exhaustive(replay, lat, lon, mode)
                predicted, near_edge = oracle.find(lat, lon, mode)
                record = {"probeOrdinal": number, "modeOrdinal": mode_number, "id": point["id"],
                          "lat": lat, "lng": lon, "accuracyMeters": mode, "indexed": indexed,
                          "exhaustiveWinnerId": exhaustive_winner, "oracle": predicted,
                          "oracleNearExactEdge": near_edge, "expectedSourceId": point["expectedSourceId"],
                          "sourceClass": point["sourceClass"]}
                journal.write(json.dumps(record, ensure_ascii=False, allow_nan=False) + "\n")
                journal.flush()
                os.fsync(journal.fileno())
                counts["lookupComparisons"] += 1
                if indexed["winnerId"] != exhaustive_winner:
                    failures.append({"probeOrdinal": number, "modeOrdinal": mode_number})
                fields = ("winnerId", "candidateIds", "qualifiedIds", "suppressedIds")
                if any(indexed[key] != predicted[key] for key in fields):
                    oracle_disagreements.append({"probeOrdinal": number, "modeOrdinal": mode_number,
                                                 "nearExactEdge": near_edge})
                counts["oracleNearExactEdgeComparisons"] += int(near_edge)
            if (number + 1) % 500 == 0:
                print(json.dumps({"officialCode": code, "probesCompleted": number + 1,
                                  "probeCount": len(points), "credit": 0}), flush=True)
    result = {"status": "SOLO_FULL_SOURCE_DRIVEN_GPS_AUDIT_REQUIRES_REVIEW", "officialCode": code, "id": ident,
              "sourceActor": "/root", "technicalAuditActor": "/root", "independentQaPassed": False,
              "sourceScopeAccepted": False, "credit": 0, "ledgerWrites": False,
              "probeCount": len(points), "counts": dict(counts), "indexedExhaustiveFailures": failures,
              "independentFormulaDisagreements": oracle_disagreements,
              "currentPeers": peers, "countryOutsideCurrentSquareMeters": current_m.difference(country_m).area,
              "rawGridSymmetricDifferenceSquareMeters": raw_m.symmetric_difference(current_m).area,
              "inputGate": input_gate, "probes": probes, "actualJvmRequests": requests,
              "journal": pin(journal_path), "startedAtUtc": started,
              "finishedAtUtc": datetime.now(timezone.utc).isoformat(), "payloadPid": os.getpid(),
              "actualJvmExecuted": False, "fullConsumerExecuted": False,
              "limits": ["Solo technical phase does not satisfy the saved disjoint-reviewer requirement.",
                         "All numeric disagreements retained; no new source/body credit.",
                         "Native graph, current323 protection and source-driven GPS evidence are separate artifacts."]}
    for key in ("metadata", "binary", "publication", "protectedLineage", "oracle", "numericOriginal", "governorates", "prayerParams"):
        checked(spec[key])
    reference = write_new(output / "report.json", result)
    print(json.dumps({"report": reference, "probeCount": len(points), "comparisons": counts["lookupComparisons"],
                      "indexedExhaustiveFailures": len(failures), "oracleDisagreements": len(oracle_disagreements), "credit": 0}), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--code", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    run(read(args.manifest), args.manifest, args.code, args.output)


if __name__ == "__main__":
    main()
