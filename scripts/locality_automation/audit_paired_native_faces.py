"""Read-only paired original ISIE face diagnostic, with no acceptance authority.

Replays pinned original whole faces and native layer gates before comparing
installed, single-face and paired-face intersections. Never snaps, clips,
repairs, installs or claims survey accuracy or a civil administrative extent.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sys

from pyproj import CRS
from shapely import from_wkb
from shapely.affinity import affine_transform

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.audit_native_replay_layers import audit
from scripts.locality_automation.audit_prepared_osm_graphs import write_new
from scripts.locality_automation.compare_isie_neighbor_linework import inventory_case, affine_coefficients, proximity
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree
from scripts.locality_automation.isie_candidate_comparison import Catalog
from scripts.locality_automation.run_sealed_boundary_queue import active_control, checked, pin, read
from scripts.locality_automation.work_window_guard import parse_utc


def intersection_measure(a, b):
    intersection = a.intersection(b)
    return {"intersectionAreaSquareMeters": intersection.area,
            "intersectionBoundaryLengthMeters": intersection.length,
            "distanceMeters": a.distance(b),
            "qualification": "Raw planar diagnostic; no ownership or accuracy claim"}


def run(spec, output):
    ctl = active_control(spec)
    now = datetime.now(timezone.utc)
    if (spec.get("credit") != 0 or spec.get("sourceAndQaActor") != "/root"
            or output.exists() or now >= parse_utc(ctl["safeSourceQaStartUtc"])
            or now >= parse_utc(spec["diagnosticBudgetEndsUtc"])):
        raise ValueError("Fresh finite root-only diagnostic required")
    rows = spec["rows"]
    if len(rows) != 2 or len({r["officialCode"] for r in rows}) != 2:
        raise ValueError("Exactly two distinct originally named source faces required")
    if rows[0]["officialCode"] not in ctl["approvedReconciliationPool"]:
        raise ValueError("Primary outside approved reconciliation pool")
    check_tree(spec)
    catalog = Catalog(checked(spec["metadata"]), checked(spec["binary"]))
    source, installed, native, cases = [], [], [], []
    for row in rows:
        original = next(r for r in read(checked(row["legacyPacket"]))["rows"]
                        if r["officialCode"] == row["officialCode"])
        for key in ("appId", "officialName", "officialParent", "ownTitleInteriorLabelIndexes"):
            if original[key] != row[key]:
                raise ValueError("Original literal identity or title anchor changed")
        if (original["decision"] != row["sourceGateDecision"]
                or original["sourcePdf"]["sha256"] != row["sourcePdf"]["sha256"]
                or original["sourceInventory"]["sha256"] != row["sourceInventory"]["sha256"]
                or original["pageFaceWkbSha256"] != row["pageWkb"]["sha256"]
                or original["selectedRegisteredFaceWkbSha256"] != row["registeredWkb"]["sha256"]):
            raise ValueError("Original source, decision or complete-face binding differs")
        case = inventory_case(row)
        face = from_wkb(checked(row["registeredWkb"]).read_bytes())
        page_face = from_wkb(checked(row["pageWkb"]).read_bytes())
        if affine_transform(page_face, affine_coefficients(case["matrix"])).wkb != face.wkb:
            raise ValueError("Original registration does not exactly reproduce frozen whole face")
        native.append(audit(row, row))
        source.append(face)
        installed.append(catalog.geometry(row["appId"], CRS.from_epsg(32632)))
        cases.append(case)
    scenarios = {}
    for name, a, b in (("installedBoth", installed[0], installed[1]),
                       ("primarySourceNeighborInstalled", source[0], installed[1]),
                       ("primaryInstalledNeighborSource", installed[0], source[1]),
                       ("originalWholeSourceBoth", source[0], source[1])):
        scenarios[name] = intersection_measure(a, b)
    correspondence = []
    for i in range(2):
        for tolerance in (1., 5., 20.):
            correspondence.append({"fromCode": rows[i]["officialCode"],
                "toCode": rows[1-i]["officialCode"],
                "wholeFaceBoundaryToOtherBlueLine": proximity(source[i].boundary, cases[1-i]["lines"]["blue"], tolerance),
                "wholeFaceBoundaryToOtherWholeFaceBoundary": proximity(source[i].boundary, source[1-i].boundary, tolerance)})
    check_tree(spec)
    result = {"status": "PAIRED_ORIGINAL_NATIVE_FACE_DIAGNOSTIC_NO_ACCEPTANCE",
        "manifest": pin(spec["manifestPath"]), "completedAtUtc": datetime.now(timezone.utc).isoformat(),
        "nativeChecks": native, "scenarios": scenarios, "correspondence": correspondence,
        "originalWholeFacesPreserved": True, "geometryChanged": False,
        "protectedNeighborChanged": False, "independentQaPassed": False,
        "sourceScopeAccepted": False, "credit": 0,
        "qualification": "Paired electoral-map source consistency only. Raw seams/overlaps are retained. Source identity, legal scope, all neighbors, protected lineage, GPS, prayer, persistence, complete scope and original CAF+A4 remain mandatory before any acceptance."}
    ref = write_new(output, result)
    print(json.dumps({"report": ref, "scenarios": scenarios,
                      "holds": [{"code": r["officialCode"], "holds": r["holds"]} for r in native], "credit": 0}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    spec = read(args.manifest)
    spec["manifestPath"] = str(args.manifest)
    run(spec, args.output)


if __name__ == "__main__":
    main()
