"""Verify exact report unions, retained history, packed map and actual exports.

Uses established independent binary/WKB primitives. This is offline data/UI
logic verification and makes no claim about Google authentication or rendering.
"""
import argparse
import ast
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import struct
import sys
from xml.etree import ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts.locality_automation.run_sealed_boundary_queue import checked, pin, read


def ring_edges(a, b):
    if len(a) != len(b) or a[0] != a[-1] or b[0] != b[-1]:
        raise ValueError("Closed ring representation differs")
    first, second = a[:-1], b[:-1]
    for direction, sequence in (("same", second), ("reverse", list(reversed(second)))):
        for index, coordinate in enumerate(sequence):
            if coordinate == first[0] and sequence[index:] + sequence[:index] == first:
                return {"direction": direction, "cyclicStartOffset": index}
    raise ValueError("Accepted adopted/current ring edges differ")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    spec = read(args.manifest)
    before = read(checked(spec["baselineReport"]))
    old_map = read(checked(spec["baselineMap"]))
    report = read(checked(spec["report"]))
    model = read(checked(spec["map"]))
    ui = read(Path(spec["outputDirectory"]) / "ui-result.json")
    if ui.get("status") != "PASS_FINITE_NEW_MAP_UI_EXPORT_FILTER_ISSUES":
        raise ValueError("Actual UI/export logic did not pass")
    codes = set(ui["newCodes"])
    if not codes or not codes <= set(spec["allowedNewCodes"]):
        raise ValueError("Audit codes differ from finite approved cases")
    summary = report["summary"]
    for key in ("validationIssues", "sourceOnlyAuditIssues", "reportingIssues"):
        if summary.get(key) != []:
            raise ValueError("Publication issue array is not empty: " + key)
    for count_key, codes_key in (("uniqueValidatedLocations", "validatedLocationCodes"),
        ("explicitFullSourceBoundaryLocationCount", "explicitFullSourceBoundaryLocalityCodes"),
        ("currentSourceGeometryValidatedLocationCount", "currentSourceGeometryValidatedLocationCodes")):
        expected = set(before["summary"][codes_key]) | codes
        actual = summary[codes_key]
        if len(actual) != len(set(actual)) or set(actual) != expected or summary[count_key] != len(expected):
            raise ValueError("Exact acceptance union differs: " + codes_key)
    if summary["uniqueInstalledCorrectionLocations"] != before["summary"]["uniqueInstalledCorrectionLocations"]:
        raise ValueError("NoOp admissions changed correction count")
    previous = {row["id"]: row for row in before["validations"]}
    current = {row["id"]: row for row in report["validations"]}
    if len(current) != len(report["validations"]) or any(current.get(key) != value for key, value in previous.items()):
        raise ValueError("Historical validation IDs, dates or evidence changed")
    if report["sourceOnlyAcceptances"] != before["sourceOnlyAcceptances"]:
        raise ValueError("Source-only history changed")
    old_batches = {batch["id"]: batch for batch in old_map["batches"]}
    batches = {batch["id"]: batch for batch in model["batches"]}
    if any(batches.get(key) != value for key, value in old_batches.items()):
        raise ValueError("Historical map batches changed")
    if model["warnings"] or {row["code"] for row in model["locations"]} != set(summary["validatedLocationCodes"]):
        raise ValueError("Current map/report union or warnings differ")
    metadata = read(checked(model["currentAssets"]["metadata"]))
    binary = checked(model["currentAssets"]["binary"]).read_bytes()
    namespace = {"struct": struct}
    primitive_path = checked(spec["coordinatePrimitives"])
    for node in ast.parse(primitive_path.read_text(encoding="utf-8-sig")).body:
        if isinstance(node, ast.FunctionDef) and node.name in ("decode", "adopted_wkb"):
            exec(compile(ast.Module(body=[node], type_ignores=[]), str(primitive_path), "exec"), namespace)
    decode, adopted_wkb = namespace["decode"], namespace["adopted_wkb"]
    for location in model["locations"]:
        if decode(metadata, binary, location["id"]) != location["geometry"]["coordinates"]:
            raise ValueError("Map/current packed coordinates differ: " + location["code"])
    checks = []
    for code in sorted(codes):
        candidates = [b for b in model["batches"] if code in b["newLocationCodes"]]
        if len(candidates) != 1:
            raise ValueError("New code appears in multiple first-acceptance batches")
        batch = candidates[0]
        row = next(r for r in batch["locations"] if r["code"] == code)
        if (row["scope"] != "full" or not row["newLocation"] or not row["currentMatchesAccepted"]
                or any(row[k] is not None for k in ("beforeGeometry", "addedGeometry", "removedGeometry"))):
            raise ValueError("New unchanged-body full scope/action differs")
        coordinates = decode(metadata, binary, row["id"])
        source = row["sourceGeometryEvidence"]
        adopted = adopted_wkb(checked(source["expectedAdoptedGeometry"]).read_bytes())
        if len(coordinates) != len(adopted) or any(len(a) != len(b) for a, b in zip(coordinates, adopted)):
            raise ValueError("Adopted/current polygon/ring counts differ")
        equivalence = [[ring_edges(a, b) for a, b in zip(poly, target)] for poly, target in zip(coordinates, adopted)]
        geo = read(Path(spec["outputDirectory"]) / (code + ".geojson"))
        if len(geo["features"]) != 1:
            raise ValueError("Finite GeoJSON export contains unrelated locations")
        feature = geo["features"][0]
        if (feature["geometry"] != row["geometry"] or feature["properties"]["geometryRewritten"] is not False
                or feature["properties"]["sourceGeometryEvidence"] != source
                or feature["properties"]["acceptedGpsLimits"] != row["acceptedGpsLimits"]
                or feature["properties"]["acceptedAtUtc"] != batch["atUtc"]):
            raise ValueError("GeoJSON geometry/provenance/limits changed")
        ns = {"k": "http://www.opengis.net/kml/2.2"}
        marks = ET.parse(Path(spec["outputDirectory"]) / (code + ".kml")).findall(".//k:Placemark", ns)
        if len(marks) != 1:
            raise ValueError("KML contains unrelated placemarks")
        def ring(node):
            return [[float(v) for v in word.split(",")[:2]] for word in node.text.split()]
        exported = [[ring(poly.find("k:outerBoundaryIs/k:LinearRing/k:coordinates", ns)),
                     *[ring(node) for node in poly.findall("k:innerBoundaryIs/k:LinearRing/k:coordinates", ns)]]
                    for poly in marks[0].findall(".//k:Polygon", ns)]
        if exported != coordinates:
            raise ValueError("KML ordered coordinates differ")
        checks.append({"code": code, "acceptedBatchId": batch["id"], "acceptedAtUtc": batch["atUtc"],
            "vertices": sum(len(r) for p in coordinates for r in p), "exactPackedMapGeoJsonKml": True,
            "adoptedRingEquivalence": equivalence, "newHighlightPassed": True})
    if checked(spec["map"]).read_bytes() != (Path(spec["outputDirectory"]) / "boundary-map.json").read_bytes():
        raise ValueError("Publication changed after actual UI/export check")
    result = {"status": "PASS_EXACT_BOUNDARY_PUBLICATION_AND_EXPORTS", "payloadPid": os.getpid(),
        "atUtc": datetime.now(timezone.utc).isoformat(), "newCodes": sorted(codes), "checks": checks,
        "counts": [summary["uniqueValidatedLocations"], summary["explicitFullSourceBoundaryLocationCount"], summary["uniqueInstalledCorrectionLocations"]],
        "allMapLocationsDecoded": len(model["locations"]), "previousHistoryPreserved": True,
        "uiProof": pin(Path(spec["outputDirectory"]) / "ui-result.json"), "manifest": pin(args.manifest),
        "newCreditFromMap": 0, "privateKeyRead": False, "networkRequestsPerformed": False,
        "actualBrowserAuthOrRenderingVerified": False}
    with args.output.open("x", encoding="utf-8") as stream:
        stream.write(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"status": result["status"], "counts": result["counts"], "newCodes": result["newCodes"], "vertices": sum(row["vertices"] for row in checks)}))


if __name__ == "__main__":
    main()
