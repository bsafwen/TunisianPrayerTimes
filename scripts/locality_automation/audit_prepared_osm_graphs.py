"""Reconstruct finite prepared OSM boundaries with an alternative graph algorithm.

This is a solo technical audit, never a disjoint reviewer, source acceptance,
GPS/runtime replay, installation, or geographic credit. Unknowns remain unknown.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import csv
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys
from xml.etree import ElementTree as ET

from shapely import from_wkb, set_precision
from shapely.geometry import MultiPolygon, Polygon

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import active_control, checked, pin, read
from scripts.locality_automation.work_window_guard import parse_utc
from scripts.locality_review.boundary import _parse_multipolygon


def graph_assembly(xml_bytes, relation_id):
    root = ET.fromstring(xml_bytes)
    if root.tag != "osm":
        raise ValueError("Expected original OSM document")
    pools = {}
    for kind in ("node", "way", "relation"):
        elements = root.findall(kind)
        ids = [element.attrib["id"] for element in elements]
        if len(ids) != len(set(ids)):
            raise ValueError("Duplicate native element identity")
        pools[kind] = dict(zip(ids, elements))
    relation = pools["relation"].get(relation_id)
    if relation is None:
        raise ValueError("Exact relation is absent")
    tags = {tag.attrib["k"]: tag.attrib["v"] for tag in relation.findall("tag")}
    if len(tags) != len(relation.findall("tag")):
        raise ValueError("Duplicate source tag key")
    by_role, preserved, used, original_edges = defaultdict(dict), [], set(), Counter()
    for member in relation.findall("member"):
        kind, ref = member.attrib["type"], member.attrib["ref"]
        role = member.attrib.get("role", "")
        if kind not in pools or ref not in pools[kind]:
            raise ValueError("Unresolved original member")
        if role in ("", "outer", "inner") and kind == "relation":
            raise ValueError("Nested boundary relation requires explicit separate resolution")
        if kind != "way" or role not in ("", "outer", "inner"):
            preserved.append(dict(member.attrib))
            continue
        if ref in used:
            raise ValueError("Duplicate boundary way member")
        used.add(ref)
        normalized_role = role or "outer"
        ids = tuple(node.attrib["ref"] for node in pools["way"][ref].findall("nd"))
        if len(ids) < 2 or any(node not in pools["node"] for node in ids):
            raise ValueError("Missing literal way node or empty way")
        if any(left == right for left, right in zip(ids, ids[1:])):
            raise ValueError("Zero-length source segment")
        by_role[normalized_role][ref] = ids
        original_edges.update((normalized_role, tuple(sorted((a, b))))
                              for a, b in zip(ids, ids[1:]))
    rings, assembled_edges, consumed = [], Counter(), set()
    for role, ways in sorted(by_role.items()):
        endpoints = defaultdict(list)
        for ref, ids in ways.items():
            if ids[0] != ids[-1]:
                endpoints[ids[0]].append(ref)
                endpoints[ids[-1]].append(ref)
        if any(len(refs) != 2 for refs in endpoints.values()):
            raise ValueError("Nonunique or unclosed source endpoint graph")
        for first_ref in sorted(ways):
            if first_ref in consumed:
                continue
            chain, refs = list(ways[first_ref]), [first_ref]
            consumed.add(first_ref)
            while chain[-1] != chain[0]:
                matches = [ref for ref in endpoints[chain[-1]] if ref not in consumed]
                if len(matches) != 1:
                    raise ValueError("Literal graph walk cannot close uniquely")
                next_ref = matches[0]
                ids = ways[next_ref]
                ordered = ids if ids[0] == chain[-1] else tuple(reversed(ids))
                if ordered[0] != chain[-1]:
                    raise ValueError("Original endpoint mismatch")
                chain.extend(ordered[1:])
                consumed.add(next_ref)
                refs.append(next_ref)
            coordinates = [(float(pools["node"][node].attrib["lon"]),
                            float(pools["node"][node].attrib["lat"])) for node in chain]
            ring_polygon = Polygon(coordinates)
            if not ring_polygon.is_valid or ring_polygon.is_empty:
                raise ValueError("Invalid native ring; no repair allowed")
            assembled_edges.update((role, tuple(sorted((a, b)))) for a, b in zip(chain, chain[1:]))
            rings.append({"role": role, "nodes": chain, "ways": refs, "coordinates": coordinates})
    if consumed != used or assembled_edges != original_edges:
        raise ValueError("Native member or literal edge coverage differs")
    outers = [ring for ring in rings if ring["role"] == "outer"]
    if not outers:
        raise ValueError("No original outer ring")
    holes = [[] for _ in outers]
    for ring in (item for item in rings if item["role"] == "inner"):
        hole = Polygon(ring["coordinates"])
        owners = [index for index, outer in enumerate(outers)
                  if Polygon(outer["coordinates"]).contains(hole)]
        if len(owners) != 1:
            raise ValueError("Source inner ring has no unique containing outer")
        holes[owners[0]].append(ring["coordinates"])
    body = MultiPolygon([Polygon(outer["coordinates"], holes[index]) for index, outer in enumerate(outers)])
    if body.is_empty or not body.is_valid:
        raise ValueError("Invalid native whole body; no repair allowed")
    return body, {"relationTags": tags, "memberCount": len(relation.findall("member")),
                  "boundaryWayCount": len(used), "originalSegmentCount": sum(original_edges.values()),
                  "componentCount": len(body.geoms), "innerRingCount": sum(len(p.interiors) for p in body.geoms),
                  "nonBoundaryMembersPreserved": preserved, "rings": rings,
                  "allBoundaryMembersUsedExactlyOnce": True, "literalEdgeMultisetUnchanged": True,
                  "inferredConnectorsAdded": False, "repairApplied": False}


def edge_signature(body):
    def ring_signature(ring):
        points = list(ring.coords)
        return tuple(sorted(Counter(tuple(sorted((a, b))) for a, b in zip(points, points[1:])).items()))
    parts = list(body.geoms) if hasattr(body, "geoms") else [body]
    return Counter((ring_signature(part.exterior), tuple(sorted(ring_signature(h) for h in part.interiors)))
                   for part in parts)


def csv_rows(reference):
    with checked(reference).open(encoding="utf-8-sig", newline="") as stream:
        return list(csv.DictReader(stream))


def write_new(path, value):
    with path.open("x", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write("\n")
    return pin(path)


def audit(spec, output):
    control = active_control(spec)
    if datetime.now(timezone.utc) >= parse_utc(control["safeSourceQaStartUtc"]):
        raise ValueError("Source start cutoff reached")
    preparations = spec["preparations"]
    if output.exists() or spec.get("credit") != 0 or not preparations:
        raise ValueError("Fresh finite diagnostic-only output required")
    metadata = read(checked(spec["metadata"]))
    binary = checked(spec["binary"]).read_bytes()
    current = {row["id"]: row for row in metadata["features"]}
    ministry = csv_rows(spec["functionalMinistryMatrix"])
    if len(ministry) != 2084:
        raise ValueError("Wrong eligible Ministry catalogue")
    inventory = csv_rows(spec["recordInventory"])
    registry = read(checked(spec["insRegistry"]))["sectors"]
    source = read(checked(spec["publication"]))
    full = set(source["summary"]["validatedLocationCodes"])
    selected = [read(checked(reference)) for reference in preparations]
    codes = [row["officialCode"] for row in selected]
    if (len(codes) != len(set(codes)) or not set(codes) <= set(control["approvedCycle15PreparationPool"])
            or set(codes) & full):
        raise ValueError("Invalid finite pending cohort or repeated full acceptance")
    packer_path = checked(spec["productionPacker"])
    loader = importlib.util.spec_from_file_location("unchanged_graph_audit_packer", packer_path)
    packer = importlib.util.module_from_spec(loader)
    loader.loader.exec_module(packer)
    if metadata["coordinateScale"] != packer.SCALE:
        raise ValueError("Production scale mismatch")
    output.mkdir()
    results, holds = [], []
    for observation, reference in zip(selected, preparations):
        code, ident = observation["officialCode"], observation["id"]
        started = datetime.now(timezone.utc).isoformat()
        try:
            if (observation["status"] != "SOURCE_PREPARATION_PENDING_DISJOINT_QA"
                    or observation["sourceActor"] != "/root" or observation["independentQaPassed"] is not False
                    or observation["newCredit"] != 0):
                raise ValueError("Source provenance or original qualification differs")
            body, facts = graph_assembly(checked(observation["sourceDocument"]).read_bytes(), ident.split(":")[-1])
            own = current[ident]
            official = [row for row in registry if row["sectorCode"] == code]
            members = [row for row in ministry if json.loads(row["identityCandidateCodes"]) == [code]]
            records = [row for row in inventory if row["id"] == ident]
            if len(official) != 1 or len(members) != 1 or len(records) != 1:
                raise ValueError("Identity is not unique across original inventories")
            official, member, record = official[0], members[0], records[0]
            tags = facts["relationTags"]
            names = [own["name"], official["sectorAr"], member["name"], tags.get("name:ar", tags.get("name"))]
            parent = own["parentName"].removeprefix("معتمدية ")
            if (len(set(names)) != 1 or tags.get("ref:tn:codegeo") != code
                    or tags.get("boundary") != "administrative" or tags.get("admin_level") != "6"
                    or official["delegationAr"] != parent or member["delegation"] != parent
                    or member["governorate"] != official["governorateAr"]
                    or member["frozenFunctionalExpectedId"] != ident
                    or json.loads(record["candidateCodes"]) != [code]
                    or own != observation["currentMetadata"]):
                raise ValueError("Literal name/code/parent/governorate/current identity differs")
            raw = from_wkb(checked(observation["rawSourceGeometry"]).read_bytes())
            production = from_wkb(checked(observation["productionInput"]).read_bytes())
            adopted = from_wkb(checked(observation["expectedAdoptedGeometry"]).read_bytes())
            if any(not geometry.is_valid or geometry.is_empty for geometry in (raw, production, adopted)):
                raise ValueError("Invalid pinned body; no repair allowed")
            if not body.equals(raw) or edge_signature(body) != edge_signature(raw):
                raise ValueError("Alternative literal assembly differs from the whole frozen source")
            # Reordering/orientation can differ. The original quantized source
            # edges and each component's inner roles must remain exactly equal.
            if edge_signature(set_precision(body, 1 / packer.SCALE)) != edge_signature(set_precision(production, 1 / packer.SCALE)):
                raise ValueError("Production container changes quantized native edges/components/inner roles")
            encoded = packer.packed_geometry_bytes(set_precision(production, 1 / packer.SCALE))
            stored = binary[own["offset"]:own["offset"] + own["length"]]
            rings = _parse_multipolygon(b"NPOL\0\0\0\1" + encoded, 8, len(encoded), packer.SCALE, ident)
            decoded = MultiPolygon([Polygon(poly[0], poly[1:]) for poly in rings])
            if encoded != stored or not decoded.equals(adopted):
                raise ValueError("Original production bytes/adopted/current whole body differ")
            report = {"status": "PASS_SOLO_TECHNICAL_LITERAL_GRAPH_AND_IDENTITY", "officialCode": code, "id": ident,
                "sourceActor": "/root", "technicalAuditActor": "/root", "disjointReviewer": False,
                "independentQaPassed": False, "sourceScopeAccepted": False,
                "civilGovernmentExtentCertified": False, "gpsOrRuntimeExecuted": False,
                "credit": 0, "sourceObservation": reference, "sourceDocument": observation["sourceDocument"],
                "nativeGraphFacts": facts, "literalNamesAndOfficialParentVerified": True,
                "allOriginalEdgesAndInnerRolesEqual": True, "originalProductionPackedBytesEqualCurrent": True,
                "priorLiteralPackedComparisonPreserved": observation["originalLiteralAssemblyPackedComparison"],
                "currentPackedSha256": hashlib.sha256(stored).hexdigest(),
                "rawSourceGeometry": observation["rawSourceGeometry"], "productionInput": observation["productionInput"],
                "expectedAdoptedGeometry": observation["expectedAdoptedGeometry"],
                "startedAtUtc": started, "finishedAtUtc": datetime.now(timezone.utc).isoformat(),
                "limits": ["Solo technical audit cannot satisfy the saved disjoint source/QA actor requirement.",
                           "OSM published community boundary is not a government extent certificate.",
                           "Neighbor topology, GPS/runtime and full consumer gates require separate completion."]}
            result = write_new(output / (code + "-technical-audit.json"), report)
            results.append({"officialCode": code, "report": result})
        except Exception as error:
            hold = {"status": "SOLO_TECHNICAL_GRAPH_HOLD", "officialCode": code,
                    "sourceObservation": reference, "error": type(error).__name__ + ": " + str(error),
                    "startedAtUtc": started, "finishedAtUtc": datetime.now(timezone.utc).isoformat(), "credit": 0}
            holds.append({"officialCode": code, "report": write_new(output / (code + "-hold.json"), hold)})
    for key in ("metadata", "binary", "publication", "productionPacker", "functionalMinistryMatrix", "recordInventory", "insRegistry"):
        checked(spec[key])
    index = {"status": "FINITE_SOLO_TECHNICAL_AUDIT_COMPLETE_NO_ACCEPTANCE", "manifest": pin(spec["manifestFile"]),
             "codes": codes, "results": results, "holds": holds, "credit": 0, "ledgerWrites": False,
             "actualExecutor": "/root", "payloadPid": os.getpid(), "independentQaPassed": False}
    write_new(output / "index.json", index)
    return index


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    spec = read(args.manifest)
    spec["manifestFile"] = args.manifest
    result = audit(spec, args.output)
    print(json.dumps({"status": result["status"], "pass": len(result["results"]), "hold": len(result["holds"]), "credit": 0}))


if __name__ == "__main__":
    main()
