"""Pinned, read-only comparison of original neighboring ISIE linework.

Line proximity is diagnostic: it does not assign a face, erase a source hold,
prove survey accuracy, or authorize installation. No geometry is repaired.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sys

import numpy as np
import pymupdf
from pyproj import CRS, Transformer
from shapely import from_wkb
from shapely.affinity import affine_transform
from shapely.geometry import LineString, Point
from shapely.ops import transform, unary_union

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.isie_candidate_comparison import Catalog
from scripts.locality_automation.run_sealed_boundary_queue import checked, pin, read, active_control
from scripts.locality_automation.work_window_guard import parse_utc

LAYERS = {
    "red": "circonscription_isie2023",
    "blue": "delegation_isie_2023",
    "black": "gouv_2023",
}


def affine_coefficients(matrix):
    a, b, c = np.asarray(matrix, dtype=float)
    return [a[0], b[0], a[1], b[1], c[0], c[1]]


def inverse_matrix(matrix):
    a, b, c = np.asarray(matrix, dtype=float)
    full = np.array([[a[0], b[0], c[0]], [a[1], b[1], c[1]], [0, 0, 1]])
    inverse = np.linalg.inv(full)
    return [[inverse[0, 0], inverse[1, 0]],
            [inverse[0, 1], inverse[1, 1]],
            [inverse[0, 2], inverse[1, 2]]]


def proximity(line, reference, tolerance):
    if tolerance <= 0 or not np.isfinite(tolerance):
        raise ValueError("Positive finite diagnostic tolerance required")
    length = line.length
    covered = line.intersection(reference.buffer(tolerance)).length
    return {"toleranceMeters": tolerance, "lineLengthMeters": length,
            "lengthWithinToleranceMeters": covered,
            "fractionWithinTolerance": covered / length if length else None,
            "qualification": "Diagnostic proximity only; no boundary ownership or accuracy claim"}


def inventory_case(row):
    pdf = checked(row["sourcePdf"])
    inventory = read(checked(row["sourceInventory"]))
    if inventory["sourcePdf"]["sha256"] != row["sourcePdf"]["sha256"]:
        raise ValueError("Inventory belongs to a different source PDF")
    page = inventory["pages"][0]
    references = [ref for ref in page["georeferences"]
                  if ref.get("status") == "fitted" and ref.get("crsEpsg") == 32632]
    if len(references) != 1:
        raise ValueError("Expected exactly one metre-based EPSG:32632 registration")
    ref = references[0]
    design = np.c_[np.array(ref["pageControls"], dtype=float), np.ones(4)]
    if design.shape != (4, 3) or np.linalg.matrix_rank(design) != 3:
        raise ValueError("Four non-collinear embedded controls required")
    projector = Transformer.from_crs(4326, 32632, always_xy=True)
    ground = np.array([projector.transform(lon, lat)
                       for lat, lon in ref["geographicControlsLatLon"]])
    fitted = np.linalg.lstsq(design, ground, rcond=None)[0]
    recorded = np.array(ref["affineMapUnitsFromPagePoints"])
    if not np.allclose(recorded, fitted, rtol=0, atol=1e-8):
        raise ValueError("Inventory affine does not reproduce embedded controls")
    loo = []
    for index in range(4):
        keep = np.arange(4) != index
        if np.linalg.matrix_rank(design[keep]) != 3:
            raise ValueError("Degenerate held-out controls")
        candidate = np.linalg.lstsq(design[keep], ground[keep], rcond=None)[0]
        loo.append(float(np.linalg.norm(design[index] @ candidate - ground[index])))
    fit_error = float(np.linalg.norm(design @ fitted - ground, axis=1).max())
    if fit_error > 0.5 or max(loo) > 1.5:
        raise ValueError("Original registration gate failed; no relaxed tolerance")
    registered = {}
    for key, layer in LAYERS.items():
        runs = [LineString(run["nativePagePoints"])
                for drawing in page["nativePaths"]
                if drawing["visibleStroke"] and drawing["layer"] == layer
                for run in drawing["runs"] if len(run["nativePagePoints"]) >= 2]
        registered[key] = affine_transform(unary_union(runs), affine_coefficients(fitted))
    return {"pdf": pdf, "page": page, "matrix": fitted,
            "lines": registered, "registration": {
                "fitResidualMeters": fit_error, "leaveOneOutMaxMeters": max(loo),
                "originalThresholdsMeters": {"fit": 0.5, "leaveOneOut": 1.5},
                "qualification": "Embedded-control consistency only; no survey accuracy"}}


def components(geometry):
    if geometry.is_empty:
        return
    if geometry.geom_type in ("LineString", "LinearRing"):
        yield geometry
    elif hasattr(geometry, "geoms"):
        for part in geometry.geoms:
            yield from components(part)


def plot_line(axis, geometry, inverse, **style):
    page_geometry = affine_transform(geometry, affine_coefficients(inverse))
    for index, line in enumerate(components(page_geometry)):
        axis.plot(*line.xy, **{**style, "label": style.get("label") if index == 0 else None})


def run(spec, output):
    control = active_control(spec)
    if datetime.now(timezone.utc) >= parse_utc(control["safeSourceQaStartUtc"]):
        raise ValueError("Source review start cutoff reached")
    if spec.get("credit") != 0 or spec.get("sourceAndQaActor") != "/root":
        raise ValueError("Root diagnostic-only manifest required")
    rows = spec["rows"]
    if len(rows) < 2 or len({row["officialCode"] for row in rows}) != len(rows):
        raise ValueError("Distinct finite neighboring sources required")
    if rows[0]["officialCode"] not in control["approvedReconciliationPool"]:
        raise ValueError("Primary case outside finite reconciliation pool")
    if output.exists():
        raise ValueError("Preserve previous attempts; choose a fresh output directory")
    for row in rows:
        checked(row["legacyPacket"])
        packet = read(row["legacyPacket"]["file"])
        original = next(item for item in packet["rows"] if item["officialCode"] == row["officialCode"])
        if (original["sourcePdf"]["sha256"] != row["sourcePdf"]["sha256"]
                or original["decision"] != row["sourceGateDecision"]
                or original["appId"] != row["appId"]):
            raise ValueError("Original source decision, PDF or identity differs")
    cases = [inventory_case(row) for row in rows]
    face = from_wkb(checked(spec["primaryRegisteredFace"]).read_bytes())
    if face.is_empty or not face.is_valid:
        raise ValueError("Primary source body invalid; never repair")
    native_page_face = from_wkb(checked(spec["primaryPageFace"]).read_bytes())
    if affine_transform(native_page_face, affine_coefficients(cases[0]["matrix"])).wkb != face.wkb:
        raise ValueError("Selected frozen face does not reproduce the original registration")
    catalog = Catalog(checked(spec["metadata"]), checked(spec["binary"]))
    current = catalog.geometry(rows[0]["appId"], CRS.from_epsg(32632))
    if not current.is_valid:
        raise ValueError("Invalid installed geometry")
    output.mkdir()
    report = {"schemaVersion": 1, "status": "ROOT_NEIGHBOR_LINEWORK_DIAGNOSTIC_ONLY",
              "manifest": pin(spec["manifestFile"]), "credit": 0,
              "independentQa": False, "geometryChanged": False,
              "primaryCode": rows[0]["officialCode"], "rows": [], "comparisons": []}
    for row, case in zip(rows, cases):
        report["rows"].append({"officialCode": row["officialCode"],
             "sourcePdf": row["sourcePdf"], "sourceInventory": row["sourceInventory"],
             "sourceGateDecisionRetained": row["sourceGateDecision"],
             "registration": case["registration"],
             "nativeLayerLengthsMeters": {key: value.length for key, value in case["lines"].items()}})
    target = face.boundary
    for row, case in zip(rows[1:], cases[1:]):
        for kind, line in case["lines"].items():
            report["comparisons"].append({"neighborCode": row["officialCode"], "layer": kind,
                "sourceFaceBoundary": [proximity(target, line, tolerance) for tolerance in (1, 5, 20)]})
    import matplotlib
    matplotlib.use("Agg")
    from matplotlib import pyplot as plt
    doc = pymupdf.open(cases[0]["pdf"])
    page = doc[0]
    pixmap = page.get_pixmap(matrix=pymupdf.Matrix(2, 2), alpha=False)
    raster = np.frombuffer(pixmap.samples, np.uint8).reshape(pixmap.height, pixmap.width, pixmap.n)
    inverse = inverse_matrix(cases[0]["matrix"])
    bounds = native_page_face.bounds
    padding = 14
    figure, axes = plt.subplots(1, len(cases), figsize=(8 * len(cases), 8.8))
    for index, axis in enumerate(axes):
        axis.imshow(raster, extent=(0, page.rect.width, page.rect.height, 0))
        plot_line(axis, target, inverse, color="#00d6c0", linewidth=2, label="Primary source face")
        plot_line(axis, current.boundary, inverse, color="#ffbf00", linewidth=1.6,
                  linestyle="--", label="Current installed body")
        if index:
            for kind, color in (("red", "#bf00ff"), ("blue", "#145bff"), ("black", "#111111")):
                plot_line(axis, cases[index]["lines"][kind], inverse, color=color,
                          linewidth=1.1, label=f"Neighbor native {kind}")
        axis.set_xlim(bounds[0] - padding, bounds[2] + padding)
        axis.set_ylim(bounds[3] + padding, bounds[1] - padding)
        axis.set_title(f"{rows[0]['officialCode']} primary" if not index else
                       f"Neighbor {rows[index]['officialCode']} - original {rows[index]['sourceGateDecision']}")
        axis.legend(loc="lower right", fontsize=8)
        axis.set_xlabel("Original primary-page coordinates (points)")
    figure.suptitle("ISIE neighboring native linework - root diagnostic only, zero credit\n"
                   "Embedded registration and proximity do not establish survey accuracy or boundary ownership.")
    figure.tight_layout(rect=(0, 0, 1, .93))
    image_path = output / "neighbor-linework.png"
    figure.savefig(image_path, dpi=160)
    plt.close(figure)
    report["image"] = pin(image_path)
    report["currentAssets"] = {"metadata": spec["metadata"], "binary": spec["binary"]}
    for reference in (spec["metadata"], spec["binary"], spec["primaryRegisteredFace"], spec["primaryPageFace"]):
        checked(reference)
    (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    spec = read(args.manifest)
    spec["manifestFile"] = args.manifest
    result = run(spec, args.output)
    print(json.dumps({"status": result["status"], "primaryCode": result["primaryCode"],
                      "sources": len(result["rows"]), "credit": 0}))


if __name__ == "__main__":
    main()
