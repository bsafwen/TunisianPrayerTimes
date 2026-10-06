"""Measure a finite legacy source-face backlog against the actual current catalog.

Read-only diagnostics. A source-only acceptance never becomes installed/GPS
acceptance. Exact geometry, source/current differences and possible neighbor
conflicts are facts, not an ownership decision or an installation gate.
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import json
from pathlib import Path
import sys

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))
from scripts.locality_automation.run_sealed_boundary_queue import checked, pin, read
from scripts.locality_automation.packed_gps_replay import PackedGpsReplay
from scripts.locality_automation.work_window_guard import parse_utc
from pyproj import Transformer
from shapely import from_wkb
from shapely.geometry import mapping
from shapely.ops import transform, unary_union
from shapely.strtree import STRtree


def compare(source, current):
    if source.is_empty or current.is_empty or not source.is_valid or not current.is_valid:
        raise ValueError("Invalid or empty candidate/current body")
    gained, lost = source.difference(current), current.difference(source)
    intersection, union = source.intersection(current), source.union(current)
    return dict(exactTopologicalEquality=source.equals(current),
                sourceAreaSquareMeters=source.area, currentAreaSquareMeters=current.area,
                intersectionOverUnion=intersection.area / union.area,
                gainedSquareMeters=gained.area, lostSquareMeters=lost.area,
                symmetricDifferenceSquareMeters=gained.area + lost.area,
                boundaryHausdorffMeters=source.boundary.hausdorff_distance(current.boundary)), gained, lost


def pending(report):
    summary = report["summary"]
    for key in ("validationIssues", "sourceOnlyAuditIssues", "reportingIssues"):
        if summary.get(key) != []:
            raise ValueError("Current publication has issues or missing gate: " + key)
    validated = {str(code) for row in report["validations"] for code in row["locationCodes"]}
    source_only = {str(code) for row in report["sourceOnlyAcceptances"] for code in row["locationCodes"]}
    if len(validated) != summary["uniqueValidatedLocations"]:
        raise ValueError("Current validation union/count differs")
    return source_only - validated, validated


def unique_covered_area(body, peers):
    """Do not add overlapping peer areas more than once."""
    return body.intersection(unary_union(peers)).area if peers else 0.0


def audit(spec):
    control = read(spec["control"])
    codes = spec["codes"]
    if (control.get("phase") != "working" or control.get("iteration") != spec["iteration"]
            or control.get("subagentsAllowed") is not False or control.get("acceptanceOwner") != "/root"
            or len(codes) != len(set(codes)) or not codes
            or not set(codes) <= set(control["approvedReconciliationPool"])
            or datetime.now(timezone.utc) >= parse_utc(control["safeSourceQaStartUtc"])):
        raise ValueError("Finite root-only reconciliation release differs")
    paths = {key: checked(spec[key]) for key in ("stage", "publication", "metadata", "binary", "decoder")}
    if paths["decoder"].resolve() != (REPO / "scripts/locality_automation/packed_gps_replay.py").resolve():
        raise ValueError("Wrong decoder path")
    stage, publication = read(paths["stage"]), read(paths["publication"])
    unvalidated, validated = pending(publication)
    if not set(codes) <= unvalidated:
        raise ValueError("A selected source-only code is absent or already geographically accepted")
    membership = stage["firstPassBoundaryReview"]["membership"]
    by_code = {}
    for row in membership:
        code = row.get("officialCode")
        if code is not None:
            if code in by_code:
                raise ValueError("Ambiguous staged identity")
            by_code[code] = row
    source_rows = [row for row in membership if row.get("isieMeasurement") == "full"]
    sources = []
    for row in source_rows:
        if row["registeredCrsEpsg"] != 32632 or row["sourceGateDecision"] != "GO_M_SOURCE":
            raise ValueError("Wrong CRS or legacy source-only gate")
        checked(row["packet"])
        source = from_wkb(checked(row["sourceFaceWkb"]).read_bytes())
        if source.is_empty or not source.is_valid:
            raise ValueError("Invalid staged native source")
        sources.append(source)
    source_tree = STRtree(sources)
    replay = PackedGpsReplay(paths["metadata"], paths["binary"])
    project = Transformer.from_crs(4326, 32632, always_xy=True).transform
    unproject = Transformer.from_crs(32632, 4326, always_xy=True).transform
    current_ids = list(replay.boundaries[index]["id"] for index in replay.boundaries)
    current_bodies = [transform(project, replay.geometry(ident)) for ident in current_ids]
    current_tree = STRtree(current_bodies)
    index_by_id = {ident: i for i, ident in enumerate(current_ids)}
    rows, features = [], []
    for code in codes:
        row = by_code[code]
        if row["isieMeasurement"] != "full" or row["sourceGateDecision"] != "GO_M_SOURCE":
            raise ValueError("Selected code is held or lacks a whole source-only face")
        source = from_wkb(checked(row["sourceFaceWkb"]).read_bytes())
        own_index = index_by_id[row["id"]]
        current = current_bodies[own_index]
        metrics, gained, lost = compare(source, current)
        neighbors, neighbor_bodies = [], []
        for index in current_tree.query(source):
            index = int(index)
            if index == own_index:
                continue
            overlap = source.intersection(current_bodies[index])
            if overlap.area > 0:
                neighbor_bodies.append(current_bodies[index])
                existing = current.intersection(current_bodies[index]).area
                added = gained.intersection(current_bodies[index]).area
                neighbors.append(dict(id=current_ids[index], name=replay.by_id[current_ids[index]]["name"],
                    sourceOverlapSquareMeters=overlap.area, currentOverlapSquareMeters=existing,
                    gainedAreaIntersectingNeighborSquareMeters=added,
                    ownershipDecision="UNRESOLVED_DIAGNOSTIC_ONLY"))
        paired = []
        for index in source_tree.query(source):
            peer = source_rows[int(index)]
            if peer["officialCode"] == code:
                continue
            overlap = source.intersection(sources[int(index)]).area
            if overlap > 0:
                paired.append(dict(officialCode=peer["officialCode"], parent=peer["officialParent"],
                    overlapSquareMeters=overlap, sameParent=peer["officialParent"] == row["officialParent"],
                    provesOfficialOverlap=False))
        for kind, body in (("source_only_face", source), ("current_installed_body", current),
                           ("candidate_gain_unaccepted", gained), ("candidate_loss_unaccepted", lost)):
            features.append(dict(type="Feature", properties=dict(officialCode=code, name=row["nameAr"],
                kind=kind, geographicCredit=0, acceptedCurrentValidation=False),
                geometry=mapping(transform(unproject, body))))
        rows.append(dict(officialCode=code, id=row["id"], name=row["nameAr"], parent=row["officialParent"],
            sourceFace=row["sourceFaceWkb"], sourcePdf=row["sourcePdf"], packet=row["packet"],
            sourcePdfPinVerified=pin(checked(row["sourcePdf"])),
            legacySourceOnlyAcceptance=True, geographicValidation=False, newCredit=0,
            disposition="EXACT_BODY_REQUIRES_REMAINING_GATES" if metrics["exactTopologicalEquality"]
                        else "DIFFERENT_BODY_REQUIRES_SOURCE_NEIGHBOR_RECONCILIATION",
            metrics=metrics, currentNeighborIntersections=sorted(neighbors, key=lambda r: -r["gainedAreaIntersectingNeighborSquareMeters"]),
            uniqueGainedAreaIntersectingAnyCurrentNeighborSquareMeters=unique_covered_area(gained, neighbor_bodies),
            gainedAreaWithoutCurrentNeighborSquareMeters=gained.area - unique_covered_area(gained, neighbor_bodies),
            pairedOfficialSheetIntersections=sorted(paired, key=lambda r: -r["overlapSquareMeters"]),
            sourceAuthorProvenance="UNKNOWN_THIS_DIAGNOSTIC_DOES_NOT_ASSERT_DISJOINT_QA",
            registrationUncertaintyApplied=False, geometryRepairApplied=False))
    for key in paths:
        checked(spec[key])
    for row in source_rows:
        checked(row["sourceFaceWkb"])
        checked(row["packet"])
    return dict(status="READ_ONLY_SOURCE_CURRENT_NEIGHBOR_RECONCILIATION_FACTS", schemaVersion=1,
        generatedAtUtc=datetime.now(timezone.utc).isoformat(), inputs={key: spec[key] for key in paths},
        codeCount=len(rows), reviewedSourceOnlyFaces=len(sources), currentDecodedBodies=len(current_bodies),
        dispositionCounts=dict(Counter(r["disposition"] for r in rows)), rows=rows,
        newGeographicCredit=0, sourceScopeAccepted=False, nativeRegistrationReplayed=False,
        independentAcceptanceQaClaimed=False, appAssetsChanged=False,
        limits=["Legacy electoral source faces do not certify present-day civil administrative extent.",
                "Cross-sheet intersections can arise from independent registrations; they do not prove legal overlap.",
                "Positive gained/lost or neighbor area is a diagnostic fact, never permission to modify ownership.",
                "No source, GPS, prayer, persistence, protected-body or full-consumer acceptance gate is replaced."]), \
        dict(type="FeatureCollection", features=features)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--geojson", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists() or args.geojson.exists():
        raise ValueError("Fresh diagnostic outputs required")
    result, geojson = audit(read(args.manifest))
    for path, value in ((args.geojson, geojson), (args.output, result)):
        with path.open("x", encoding="utf-8", newline="\n") as stream:
            json.dump(value, stream, ensure_ascii=False, allow_nan=False, indent=2)
            stream.write("\n")
    print(json.dumps({key: result[key] for key in ("status", "codeCount", "dispositionCounts", "newGeographicCredit")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
