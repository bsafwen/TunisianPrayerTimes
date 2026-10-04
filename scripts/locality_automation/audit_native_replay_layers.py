"""Recheck all original native layer gates with their actual named PDF layers.

Reports original face consistency and preserves every hold. No clipping, source
ownership inference, geometry repair, survey certification or acceptance.
"""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sys

from shapely import from_wkb
from shapely.geometry import LineString, Point
from shapely.ops import polygonize_full, unary_union

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import active_control, checked, pin, read
from scripts.locality_automation.audit_prepared_osm_graphs import write_new
from scripts.locality_automation.compare_isie_neighbor_linework import inventory_case, LAYERS
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree
from scripts.locality_automation.work_window_guard import parse_utc


def layer_lines(page, key):
    return unary_union([LineString(run["nativePagePoints"])
        for drawing in page["nativePaths"] if drawing["visibleStroke"] and drawing["layer"] == LAYERS[key]
        for run in drawing["runs"] if len(run["nativePagePoints"]) >= 2])


def audit(row, fresh):
    case = inventory_case(row)
    page = case["page"]
    face = from_wkb(checked(fresh["pageWkb"]).read_bytes())
    ground = from_wkb(checked(fresh["registeredWkb"]).read_bytes())
    if not face.is_valid or not ground.is_valid or face.is_empty or ground.is_empty:
        raise ValueError("Invalid original whole source face")
    index = row["ownTitleInteriorLabelIndexes"][0]
    anchor = Point(page["labeledAreaLeads"][index]["centerPagePoints"])
    if not face.covers(anchor):
        raise ValueError("Original title anchor no longer lies in its pinned native face")
    lines = {key: layer_lines(page, key) for key in LAYERS}
    red_faces, cuts, dangles, invalid = polygonize_full(lines["red"])
    matching = [part for part in red_faces.geoms if part.covers(anchor)]
    if len(matching) != 1 or not matching[0].equals(face):
        raise ValueError("Original native face changed during exact layer replay")
    interior = face.buffer(-.5)
    admin_lengths = {key: line.intersection(interior).length for key, line in lines.items() if key != "red"}
    connected = {}
    for keys in (("red", "blue"), ("red", "blue", "black")):
        faces, connected_cuts, connected_dangles, connected_invalid = polygonize_full(unary_union([lines[k] for k in keys]))
        matches = [part for part in faces.geoms if part.covers(anchor)]
        connected["+".join(keys)] = {"containingFaceCount": len(matches),
            "symmetricDifferenceFractionOfOriginalRed": matches[0].symmetric_difference(face).area / face.area if len(matches) == 1 else None,
            "invalidRingCount": len(connected_invalid.geoms)}
    uncovered = face.boundary.difference(lines["red"].buffer(.1)).length
    own = [i for i, label in enumerate(page["labeledAreaLeads"]) if label["redText"] and face.covers(Point(label["centerPagePoints"]))]
    internal_dangles = dangles.intersection(face.buffer(-.05)).length
    holds = []
    if uncovered > .01:
        holds.append("ORIGINAL_RED_FACE_BOUNDARY_NOT_COVERED")
    if any(length > .01 for length in admin_lengths.values()):
        holds.append("ACTUAL_BLUE_OR_BLACK_LAYER_CROSSES_SOURCE_INTERIOR")
    if any(v["containingFaceCount"] != 1 or v["symmetricDifferenceFractionOfOriginalRed"] > 1e-9 for v in connected.values()):
        holds.append("CONNECTED_NATIVE_LAYERS_CHANGE_WHOLE_SOURCE_FACE")
    if own != [index]:
        holds.append("OTHER_NATIVE_RED_LABEL_INSIDE_SOURCE_FACE")
    if len(invalid.geoms) or internal_dangles > .01:
        holds.append("NATIVE_RED_INVALID_RING_OR_INTERIOR_DANGLE")
    return {"officialCode": row["officialCode"], "sourcePdf": row["sourcePdf"], "inventory": row["sourceInventory"],
        "pageWkb": fresh["pageWkb"], "registeredWkb": fresh["registeredWkb"], "layerNames": LAYERS,
        "allVisibleNativeLayerNames": sorted({d["layer"] for d in page["nativePaths"] if d["visibleStroke"] and d["layer"]}),
        "originalGateDecision": row["sourceGateDecision"], "registration": case["registration"],
        "redFaceCount": len(red_faces.geoms), "ownTitleIndexesInside": own, "uncoveredBoundaryPagePoints": uncovered,
        "interiorAdminLineLengthsPagePoints": admin_lengths, "connectedLayers": connected,
        "totalRedDangleCount": len(dangles.geoms), "interiorRedDangleLengthPagePoints": internal_dangles,
        "redInvalidRingCount": len(invalid.geoms), "holds": holds,
        "status": "SOLO_NATIVE_LAYER_CONSISTENCY_HOLD" if holds else "PASS_SOLO_NATIVE_LAYER_CONSISTENCY_NO_ACCEPTANCE",
        "independentQaPassed": False, "sourceScopeAccepted": False, "credit": 0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    spec = read(args.manifest)
    ctl = active_control(spec)
    if args.output.exists() or spec.get("credit") != 0 or datetime.now(timezone.utc) >= parse_utc(ctl["safeSourceQaStartUtc"]):
        raise ValueError("Fresh zero-credit native phase required")
    check_tree(spec)
    rows = []
    for cohort in spec["cohorts"]:
        inputs = read(checked(cohort["input"]))
        replay = read(checked(cohort["replay"]))
        for code in cohort["codes"]:
            if code not in ctl["approvedReconciliationPool"] or any(row["officialCode"] == code for row in rows):
                raise ValueError("Finite unique approved source pool required")
            row = next(r for r in inputs["rows"] if r["officialCode"] == code)
            fresh = next(r for r in replay["rows"] if r["officialCode"] == code)
            if fresh["sourcePdf"]["sha256"] != row["sourcePdf"]["sha256"] or fresh["registeredWkb"]["sha256"] != row["legacySourceFace"]["sha256"]:
                raise ValueError("Original source or complete native body differs")
            rows.append(audit(row, fresh))
    check_tree(spec)
    ref = write_new(args.output, {"status": "ROOT_NATIVE_LAYER_REPLAY_NO_ACCEPTANCE", "manifest": pin(args.manifest),
        "rows": rows, "holdCount": sum(bool(r["holds"]) for r in rows), "credit": 0,
        "originalDiagnosticResultsPreserved": True, "originalSourceActorsInferred": False})
    print(json.dumps({"report": ref, "cases": len(rows), "holds": [(r["officialCode"], r["holds"]) for r in rows if r["holds"]], "credit": 0}))


if __name__ == "__main__":
    main()
