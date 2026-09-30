"""Replay a pinned ISIE source face with explicit rounded-control tolerances.

This offline helper writes review evidence only. It cannot install boundaries,
award S, or publish progress. The PDF, identity gate and original inventory are
kept unchanged. Original pages accompany coordinate comparisons for review.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
from pathlib import Path

import numpy as np
import pymupdf
from PIL import Image, ImageDraw
from pyproj import CRS, Transformer
from shapely.affinity import affine_transform
from shapely.geometry import LineString, Point
from shapely.ops import polygonize_full, unary_union

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts.locality_automation.isie_pdf_inventory import inspect
from scripts.locality_automation.isie_candidate_comparison import Catalog


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def pin(path):
    path = Path(path).resolve()
    return {"file": str(path), "sha256": sha(path)}


def checked(ref):
    path = Path(ref["file"])
    if sha(path) != ref["sha256"]:
        raise ValueError(f"Changed pinned input: {path}")
    return path


def lines(page, layers):
    return [LineString(run["nativePagePoints"])
            for path in page["nativePaths"]
            if path["visibleStroke"] and path["layer"] in layers
            for run in path["runs"] if len(run["nativePagePoints"]) >= 2]


def source_case(row, out):
    pdf = checked(row["sourcePdf"])
    cached = json.loads(checked(row["sourceInventory"]).read_text(encoding="utf-8"))
    fresh = inspect(pdf)
    page = fresh["pages"][0]
    cached_page = cached["pages"][0]
    red = unary_union(lines(page, {"circonscription_isie2023"}))
    faces, cuts, dangles, invalid = polygonize_full(red)
    title_index = row["ownTitleInteriorLabelIndexes"][0]
    # The historical gate supplies the independently reviewed own title index.
    center = Point(cached_page["labeledAreaLeads"][title_index]["centerPagePoints"])
    matches = [poly for poly in faces.geoms if poly.covers(center)]
    if len(matches) != 1:
        raise ValueError(f"{row['officialCode']}: own title no longer identifies one face")
    face = matches[0]
    if sha_bytes(face.wkb) != row["pageFaceWkbSha256"]:
        raise ValueError("Fresh source extraction differs from the pinned selected face")
    own = [label for label in page["labeledAreaLeads"]
           if label["redText"] and face.covers(Point(label["centerPagePoints"]))]
    refs = [r for r in page["georeferences"] if r["status"] == "fitted"]
    if len(refs) != 1:
        raise ValueError("Expected one usable embedded control set")
    ref = refs[0]
    crs = CRS.from_wkt(ref["crsWkt"])
    if not crs.is_projected or ref["mapUnitsToMeters"] != 1.0:
        raise ValueError("Expected metre-based projected controls")
    design = np.c_[np.asarray(ref["pageControls"], dtype=float), np.ones(4)]
    if design.shape != (4, 3) or np.linalg.matrix_rank(design) != 3:
        raise ValueError("Expected four non-collinear controls")
    to_map = Transformer.from_crs(crs.geodetic_crs, crs, always_xy=True)
    observed = np.asarray([to_map.transform(lon, lat)
                           for lat, lon in ref["geographicControlsLatLon"]])
    fit = np.linalg.lstsq(design, observed, rcond=None)[0]
    residual = float(np.linalg.norm(design @ fit - observed, axis=1).max())
    loo = []
    for i in range(4):
        keep = np.arange(4) != i
        if np.linalg.matrix_rank(design[keep]) != 3:
            raise ValueError("Degenerate held-out controls")
        fitted = np.linalg.lstsq(design[keep], observed[keep], rcond=None)[0]
        loo.append(float(np.linalg.norm(design[i] @ fitted - observed[i])))
    a, b, c = fit
    ground = affine_transform(face, [a[0], b[0], a[1], b[1], c[0], c[1]])
    other = lines(page, {"limite_delegation_isie2023", "limite_gouvernorat_isie2023"})
    interior_admin = sum(line.intersection(face.buffer(-0.05)).length for line in other)
    doc = pymupdf.open(pdf)
    original = out / f"{row['officialCode']}-original.png"
    doc[0].get_pixmap(matrix=pymupdf.Matrix(1.45, 1.45), alpha=False).save(original)
    raw = []
    # Read the actual GPTS number tokens as an explicit source-precision check.
    from pypdf import PdfReader
    for vp in PdfReader(pdf).pages[0].get("/VP", []):
        raw.append([str(token) for token in vp.get_object()["/Measure"].get_object()["/GPTS"]])
    rounded = len(raw) == 1 and len(raw[0]) == 8 and all(
        "." in token and len(token.split(".")[1]) <= 5 for token in raw[0])
    page_wkb = out / f"{row['officialCode']}-page.wkb"
    ground_wkb = out / f"{row['officialCode']}-epsg{crs.to_epsg()}.wkb"
    page_wkb.write_bytes(face.wkb)
    ground_wkb.write_bytes(ground.wkb)
    result = {"officialCode": row["officialCode"], "officialName": row["officialName"],
              "officialParent": row["officialParent"], "sourcePdf": pin(pdf),
              "sourceInventory": row["sourceInventory"], "pageWkb": pin(page_wkb),
              "registeredWkb": pin(ground_wkb), "freshSourceFaceMatchesFrozenFace": True,
              "redLabelsInside": own, "nativeTopology": {"closedFaces": len(faces.geoms),
              "cutEdges": len(cuts.geoms), "dangles": len(dangles.geoms),
              "invalidRings": len(invalid.geoms), "interiorAdministrativeLinePoints": interior_admin},
              "registration": {"controls": 4, "rawGPTSTokens": raw,
              "roundedToFiveDecimalDegrees": rounded, "fitMaxControlResidualMeters": residual,
              "leaveOneOutErrorsMeters": loo, "leaveOneOutMaxResidualMeters": max(loo),
              "thresholdsMetersInclusive": {"fit": 0.5, "leaveOneOut": 2.0},
              "accepted": rounded and residual <= 0.5 and max(loo) <= 2.0,
              "affineMapUnitsFromPagePoints": fit.tolist(), "crsEpsg": crs.to_epsg(),
              "qualification": "Internal rounded-GeoPDF consistency; no external survey accuracy."},
              "sourceFaceAreaSquareMeters": ground.area, "originalPage": pin(original)}
    result["diagnosticEligible"] = (result["registration"]["accepted"] and len(own) == 1
                                    and face.is_valid and face.is_simple
                                    and interior_admin < 0.01 and len(invalid.geoms) == 0
                                    and not dangles.intersects(face.buffer(-0.05)))
    return result, ground


def sha_bytes(value):
    return hashlib.sha256(value).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gate", type=Path, required=True)
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--code", action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    if (args.output / "report.json").exists():
        raise ValueError("Use a fresh output directory to preserve prior evidence")
    gate = json.loads(args.gate.read_text(encoding="utf-8"))
    catalog = Catalog(args.metadata, args.binary)
    results, geometries, images = [], {}, []
    for code in args.code:
        row = next(r for r in gate["rows"] if r["officialCode"] == code)
        result, candidate = source_case(row, args.output)
        geometries[code] = candidate
        identifier = row.get("appId", row.get("id"))
        if not identifier:
            identifier = next(r["id"] for r in catalog.rows.values()
                              if r.get("officialCode") == code)
        installed = catalog.geometry(identifier, CRS.from_epsg(32632))
        union = candidate.union(installed)
        result["appId"] = identifier
        result["installedBoundarySha256"] = catalog.boundary_pin(identifier)["sha256"]
        result["relativeInstalledComparison"] = {
            "sourceAreaSquareMeters": candidate.area, "sourcePerimeterMeters": candidate.length,
            "installedAreaSquareMeters": installed.area, "installedPerimeterMeters": installed.length,
            "intersectionSquareMeters": candidate.intersection(installed).area,
            "intersectionOverUnion": candidate.intersection(installed).area / union.area,
            "symmetricDifferenceSquareMeters": candidate.symmetric_difference(installed).area,
            "boundaryHausdorffMeters": candidate.hausdorff_distance(installed)}
        to_ll = Transformer.from_crs(32632, 4326, always_xy=True)
        from shapely.ops import transform
        impacted = catalog.sector_rows_in_bbox(identifier, transform(to_ll.transform, union).bounds)
        result["candidateOverlapsWithCurrentCatalog"] = [
            {"id": nr["id"], "name": nr["name"], "areaSquareMeters": overlap.area}
            for nr in impacted
            if (overlap := candidate.intersection(catalog.geometry(nr["id"], CRS.from_epsg(32632)))).area > 50]
        results.append(result)
        images.append(Image.open(result["originalPage"]["file"]).convert("RGB"))
    canvas = Image.new("RGB", (sum(im.width for im in images), max(im.height for im in images) + 42), "white")
    x = 0
    for code, im in zip(args.code, images):
        canvas.paste(im, (x, 42))
        ImageDraw.Draw(canvas).text((x + 20, 12), f"ISIE original - {code}", fill="black")
        x += im.width
    canvas.save(args.output / "original-neighbor-pages.png")
    pairs = [{"codes": [a, b], "intersectionSquareMeters": geometries[a].intersection(geometries[b]).area,
              "boundaryHausdorffMeters": geometries[a].hausdorff_distance(geometries[b])}
             for i, a in enumerate(args.code) for b in args.code[i+1:]]
    report = {"schemaVersion": 1, "decision": "REVIEW_EVIDENCE_ONLY", "gate": pin(args.gate),
              "catalogMetadata": pin(args.metadata), "catalogBinary": pin(args.binary),
              "rows": results, "neighborPairs": pairs,
              "qualification": "M eligibility awaits visual review. S, GPS support and installation are separate."}
    (args.output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    print(json.dumps({"output": str(args.output), "rows": [
        {"code": r["officialCode"], "eligible": r["diagnosticEligible"],
         "fit": r["registration"]["fitMaxControlResidualMeters"],
         "loo": r["registration"]["leaveOneOutMaxResidualMeters"],
         "iou": r["relativeInstalledComparison"]["intersectionOverUnion"],
         "overlaps": r["candidateOverlapsWithCurrentCatalog"]} for r in results]}, ensure_ascii=False))


if __name__ == "__main__":
    main()