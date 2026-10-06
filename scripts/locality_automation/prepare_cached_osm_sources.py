"""Prepare a finite original-XML/native source cohort through the machine proxy.

Source preparation only. No scope acceptance, independent QA, GPS, recording,
asset changes or geographic credit. Existing literal native/production routines
are pinned and reused. Every original failed response/attempt is retained.
"""
import argparse
import ast
from collections import Counter
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
from xml.etree import ElementTree as ET

import requests
from shapely import set_precision
from shapely.geometry import Polygon, MultiPolygon, shape

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts.locality_automation.run_sealed_boundary_queue import checked, pin, read
from scripts.locality_review.boundary import _parse_multipolygon


def stamp():
    return datetime.now(timezone.utc).isoformat()


def fresh(path, raw):
    with Path(path).open("xb") as stream:
        stream.write(raw)
    return {**pin(path), "bytes": len(raw)}


def write(path, value):
    return fresh(path, (json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8"))


def signature(geometry):
    def ring_signature(ring):
        coordinates = list(ring.coords)
        return tuple(sorted(Counter(tuple(sorted((a, b))) for a, b in zip(coordinates, coordinates[1:])).items()))
    parts = list(geometry.geoms) if hasattr(geometry, "geoms") else [geometry]
    return Counter((ring_signature(poly.exterior), tuple(sorted(ring_signature(r) for r in poly.interiors))) for poly in parts)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    args = parser.parse_args()
    spec = read(args.manifest)
    control = read(spec["control"])
    candidates = spec["candidates"]
    codes = [row["officialCode"] for row in candidates]
    if (control.get("phase") != "working" or control.get("iteration") != spec["iteration"]
            or control.get("subagentsAllowed") is not False or control.get("acceptanceOwner") != "/root"
            or len(codes) != len(set(codes)) or not set(codes) <= set(control["approvedCycle15PreparationPool"])):
        raise ValueError("Finite root-only preparation release/control differs")
    output = Path(spec["outputDirectory"])
    if not output.is_dir() or any(output.iterdir()):
        raise ValueError("Existing empty source preparation directory required")
    metadata = read(checked(spec["metadata"]))
    binary = checked(spec["binary"]).read_bytes()
    current = {row["id"]: row for row in metadata["features"]}
    original = {row["id"]: row for row in read(checked(spec["originalOsmAreas"]))["areas"]}
    native_path = checked(spec["literalNativeRoutine"])
    namespace = {"Counter": Counter, "Polygon": Polygon, "MultiPolygon": MultiPolygon}
    function = next(node for node in ast.parse(native_path.read_text(encoding="utf-8-sig")).body
                    if isinstance(node, ast.FunctionDef) and node.name == "literal_assembly")
    exec(compile(ast.Module(body=[function], type_ignores=[]), str(native_path), "exec"), namespace)
    assemble = namespace["literal_assembly"]
    packer_path = checked(spec["productionPacker"])
    module_spec = importlib.util.spec_from_file_location("unchanged_source_preparation_packer", packer_path)
    packer = importlib.util.module_from_spec(module_spec)
    module_spec.loader.exec_module(packer)
    if packer.SCALE != metadata["coordinateScale"]:
        raise ValueError("Exact production/current scale mismatch")
    ca = checked(spec["publicProxyCa"])
    session = requests.Session()
    session.trust_env = False
    session.proxies = {"http": "http://127.0.0.1:8888", "https": "http://127.0.0.1:8888"}
    session.headers["User-Agent"] = "TunisianPrayerTimes-source-preparation/2026-10-03"
    def decode(blob, ident):
        rings = _parse_multipolygon(b"NPOL\0\0\0\1" + blob, 8, len(blob), packer.SCALE, ident)
        return MultiPolygon([Polygon(poly[0], poly[1:]) for poly in rings])
    started, rows, holds = stamp(), [], []
    try:
        for candidate in candidates:
            code, ident = candidate["officialCode"], candidate["id"]
            if not re.fullmatch(r"osm:relation:[1-9][0-9]*", ident):
                raise ValueError("Finite relation ID format is invalid")
            relation_id = ident.split(":")[-1]
            url = "https://api.openstreetmap.org/api/0.6/relation/" + relation_id + "/full"
            case = output / code
            case.mkdir()
            attempt = dict(officialCode=code, id=ident, sourceUrl=url, startedAtUtc=stamp(),
                           proxy=session.proxies, actualExecutor="/root", payloadPid=os.getpid(), newCredit=0)
            try:
                response = session.get(url, verify=str(ca), timeout=(20, 90), allow_redirects=False)
                document = fresh(case / "original-full.xml", response.content)
                attempt.update(httpStatus=response.status_code, finishedAtUtc=stamp(), document=document,
                               responseContentType=response.headers.get("Content-Type"), tlsVerificationEnabled=True)
                acquisition = write(case / "acquisition.json", attempt)
                response.raise_for_status()
                if response.status_code != 200 or response.url != url:
                    raise ValueError("Unexpected source response or redirect")
                root = ET.fromstring(response.content)
                relations = [r for r in root.findall("relation") if r.attrib.get("id") == relation_id]
                if root.tag != "osm" or len(relations) != 1:
                    raise ValueError("Original XML does not contain the exact relation")
                tags = {tag.attrib["k"]: tag.attrib["v"] for tag in relations[0].findall("tag")}
                if (tags.get("ref:tn:codegeo") != code or tags.get("boundary") != "administrative"
                        or tags.get("admin_level") != "6" or tags.get("name:ar", tags.get("name")) != candidate["name"]):
                    raise ValueError("Current literal source code/name/boundary role changed")
                raw, facts = assemble(root, relation_id)
                raw_ref = fresh(case / "literal-source.wkb", raw.wkb)
                dated = shape(original[ident]["geometry"])
                exact_edges = signature(raw) == signature(dated)
                production_input = dated if exact_edges else raw
                production_ref = fresh(case / "production-input.wkb", production_input.wkb)
                packed = packer.packed_geometry_bytes(set_precision(production_input, 1 / packer.SCALE))
                adopted = decode(packed, ident)
                adopted_ref = fresh(case / "expected-adopted.wkb", adopted.wkb)
                feature = current[ident]
                stored = binary[feature["offset"]:feature["offset"] + feature["length"]]
                stored_geometry = decode(stored, ident)
                literal_packed = packer.packed_geometry_bytes(set_precision(raw, 1 / packer.SCALE))
                literal_comparison = dict(geometryEqualsCurrent=decode(literal_packed, ident).equals(stored_geometry),
                    packedBytesEqualCurrent=literal_packed == stored,
                    candidatePackedSha256=hashlib.sha256(literal_packed).hexdigest())
                observation = dict(status="SOURCE_PREPARATION_PENDING_DISJOINT_QA", officialCode=code, id=ident,
                    sourceActor="/root", actualExecutor="/root", payloadPid=os.getpid(),
                    sourceDocument=document, acquisition=acquisition, literalRelationTags=tags,
                    rawSourceGeometry=raw_ref, productionInput=production_ref, expectedAdoptedGeometry=adopted_ref,
                    originalLiteralNativeFacts=facts, exactLiteralEdgesComponentsInnerRolesEqualToCached=exact_edges,
                    originalLiteralAssemblyPackedComparison=literal_comparison,
                    productionInputUsesCachedContainer=exact_edges, noCoordinatesOrEdgesInvented=True,
                    geometryEqualsCurrent=adopted.equals(stored_geometry), packedBytesEqualCurrent=packed == stored,
                    currentPackedSha256=hashlib.sha256(stored).hexdigest(), expectedPackedSha256=hashlib.sha256(packed).hexdigest(),
                    currentMetadata=feature, inputs=spec, sourceScopeApproved=False, independentQaPassed=False,
                    civilGovernmentExtentCertified=False, gpsOrRuntimeExecuted=False, newCredit=0)
                reference = write(case / "source-observation.json", observation)
                rows.append(dict(officialCode=code, id=ident, sourceObservation=reference,
                    geometryEqualsCurrent=observation["geometryEqualsCurrent"], packedBytesEqualCurrent=observation["packedBytesEqualCurrent"]))
                print(json.dumps({"status": "SOURCE_PREPARED_NO_CREDIT", **rows[-1]}), flush=True)
            except Exception as exc:
                attempt.update(status="SOURCE_PREPARATION_HOLD", error=type(exc).__name__ + ": " + str(exc), finishedAtUtc=stamp())
                holds.append(write(case / "hold.json", attempt))
                print(json.dumps({"status": "SOURCE_PREPARATION_HOLD", "code": code, "error": attempt["error"]}), flush=True)
    finally:
        session.close()
    for key in ("metadata", "binary"):
        checked(spec[key])
    result = dict(status="FINITE_SOURCE_PREPARATION_COMPLETE_PENDING_DISJOINT_QA", rows=rows, holds=holds,
                  startedAtUtc=started, finishedAtUtc=stamp(), manifest=pin(args.manifest),
                  sourceActor="/root", payloadPid=os.getpid(), independentQaPassed=False, newCredit=0)
    write(output / "source-preparation-result.json", result)
    print(json.dumps({"status": result["status"], "prepared": len(rows), "holds": len(holds), "newCredit": 0}), flush=True)


if __name__ == "__main__":
    main()
