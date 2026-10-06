#!/usr/bin/env python3
"""Extract the pinned Kabouti red-sector ring from its georeferenced ISIE PDF."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

import numpy as np
import pymupdf
import pypdf
from pyproj import CRS, Transformer
from shapely.geometry import Polygon, mapping
from shapely.ops import transform


HERE = Path(__file__).resolve().parent
EVIDENCE = HERE / "evidence"
PDF = EVIDENCE / "kabouti.pdf"
GEOJSON = HERE / "kabouti-preservation-ring.geojson"
REVIEW = HERE / "source-review.json"
EXPECTED_PDF_SHA256 = "f9a2edf4a8f8e07c86c6d8d054f3c98cb296af4d8f5e6630f94ffe1aa01d9a9a"
EXPECTED_TARIF_PDF_SHA256 = "b588a1fcc7910ee29f2c2f5756175c5ddcbebd00de53553ad64e435937203168"
PDF_URL = "https://www.isie.tn/wp-content/uploads/2023/CartesCirconscriptionsElectoralesLocales2023/بن%20عروس/مرناق/الكبوطي.pdf"
TARIF_PDF_URL = "https://www.isie.tn/wp-content/uploads/2023/CartesCirconscriptionsElectoralesLocales2023/نابل%202/قرنبالية/جبل%20طريف.pdf"
FEATURE_ID = "kabouti-red-sector-preservation-ring"
DRAWING_INDEX = 369
OFFICIAL_CODE = "136257"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def write_json(path: Path, value: object) -> bytes:
    raw = (json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8")
    path.write_bytes(raw)
    return raw


def extract_georeference(path: Path):
    reader = pypdf.PdfReader(path)
    if len(reader.pages) != 1:
        raise ValueError("Expected the registered one-page Kabouti GeoPDF")
    document = pymupdf.open(path)
    page = document[0]
    viewport_list = reader.pages[0].get("/VP")
    if not viewport_list:
        raise ValueError("The Kabouti map has no GeoPDF viewport")
    viewport = viewport_list[0].get_object()
    measure = viewport["/Measure"].get_object()
    bbox = np.asarray(viewport["/BBox"], dtype=float)
    gpts = np.asarray(measure["/GPTS"], dtype=float).reshape(-1, 2)
    lpts = np.asarray(measure["/LPTS"], dtype=float).reshape(-1, 2)
    crs = CRS.from_wkt(str(measure["/GCS"].get_object()["/WKT"]))
    to_projected = Transformer.from_crs(crs.geodetic_crs, crs, always_xy=True)
    controls = []
    for x, y in lpts:
        point = pymupdf.Point(bbox[0] + x * (bbox[2] - bbox[0]),
                              bbox[1] + y * (bbox[3] - bbox[1])) * page.transformation_matrix
        controls.append([point.x, point.y, 1])
    controls = np.asarray(controls)
    destinations = np.asarray([to_projected.transform(lon, lat) for lat, lon in gpts])
    affine, _, rank, _ = np.linalg.lstsq(controls, destinations, rcond=None)
    if rank != 3:
        raise ValueError("Kabouti GeoPDF control points do not determine a full affine transform")
    residuals = np.linalg.norm(controls @ affine - destinations, axis=1)
    to_wgs84 = Transformer.from_crs(crs, "EPSG:4326", always_xy=True)

    def page_to_wgs84(x, y, z=None):
        east = x * affine[0, 0] + y * affine[1, 0] + affine[2, 0]
        north = x * affine[0, 1] + y * affine[1, 1] + affine[2, 1]
        return to_wgs84.transform(east, north)

    return document, page, page_to_wgs84, {
        "pdfGeoCrsEpsg": crs.to_epsg(),
        "controlPointCount": int(len(controls)),
        "controlResidualMeters": [float(value) for value in residuals],
        "controlResidualMaximumMeters": float(max(residuals)),
        "controlResidualRmsMeters": float(np.sqrt(np.mean(residuals ** 2))),
        "pageSize": [float(page.rect.width), float(page.rect.height)],
    }


def main() -> None:
    pdf_raw = PDF.read_bytes()
    pdf_sha = sha256(pdf_raw)
    if pdf_sha != EXPECTED_PDF_SHA256:
        raise ValueError("Kabouti GeoPDF changed from the reviewed source bytes")
    tarif_pdf = EVIDENCE / "jebel-tarif.pdf"
    if sha256(tarif_pdf.read_bytes()) != EXPECTED_TARIF_PDF_SHA256:
        raise ValueError("Jebel Tarif GeoPDF changed from the paired review source")

    document, page, page_to_wgs84, georef = extract_georeference(PDF)
    drawings = page.get_drawings()
    if len(drawings) <= DRAWING_INDEX:
        raise ValueError("Pinned Kabouti red-sector drawing index is absent")
    drawing = drawings[DRAWING_INDEX]
    if str(drawing.get("layer")) != "circonscription_isie2023":
        raise ValueError(f"Unexpected source layer at drawing {DRAWING_INDEX}: {drawing.get('layer')}")
    segments = [item for item in drawing.get("items", []) if item[0] == "l"]
    if len(segments) != 740:
        raise ValueError(f"Expected 740 continuous line segments, got {len(segments)}")
    coords = [(segments[0][1].x, segments[0][1].y)] + [(item[2].x, item[2].y) for item in segments]
    gaps = [float(np.linalg.norm(np.asarray(segments[index][2]) - np.asarray(segments[index + 1][1])))
            for index in range(len(segments) - 1)]
    closure = float(np.linalg.norm(np.asarray(segments[-1][2]) - np.asarray(segments[0][1])))
    if max(gaps, default=0) > 1e-5 or closure > 1e-5 or len(coords) != 741:
        raise ValueError("The registered Kabouti red ring is not a closed continuous 741-point path")

    page_polygon = Polygon(coords)
    candidate = transform(page_to_wgs84, page_polygon)
    if candidate.is_empty or not candidate.is_valid or candidate.geom_type != "Polygon":
        raise ValueError("Extracted Kabouti preservation ring is not one valid polygon")
    feature = {
        "type": "Feature",
        "id": FEATURE_ID,
        "properties": {
            "officialSectorCode": OFFICIAL_CODE,
            "sourcePdfSha256": pdf_sha,
            "sourceLayer": "circonscription_isie2023",
            "drawingIndex": DRAWING_INDEX,
            "drawingMethod": "Continuous closed line-only native GeoPDF vector path; no raster tracing, simplification or repair.",
            "role": "Preservation mask only; not a replacement Kabouti boundary.",
        },
        "geometry": mapping(candidate),
    }
    geojson_raw = write_json(GEOJSON, {"type": "FeatureCollection", "features": [feature]})
    geojson_sha = sha256(geojson_raw)
    review = {
        "schemaVersion": 1,
        "status": "provisional_best_effort_boundary_candidate",
        "sourceGeojsonSha256": geojson_sha,
        "sourcePdfURL": PDF_URL,
        "sourcePdfSha256": pdf_sha,
        "pairedTarifPdfURL": TARIF_PDF_URL,
        "pairedTarifPdfSha256": EXPECTED_TARIF_PDF_SHA256,
        "officialSectorCode": OFFICIAL_CODE,
        "extraction": {
            "library": f"PyMuPDF {pymupdf.VersionBind} with GeoPDF /GPTS and /LPTS affine controls",
            "layer": str(drawing.get("layer")),
            "drawingIndex": DRAWING_INDEX,
            "segmentCount": len(segments),
            "coordinateCount": len(coords),
            "maximumSegmentGapPageUnits": max(gaps, default=0),
            "closureGapPageUnits": closure,
            **georef,
        },
        "sourceInterpretation": "This red sector ring is used only to preserve the tiny part of the installed Kabouti/Tarif conflict that falls inside the Kabouti map candidate plus one 1e-6-degree app-grid margin. The paired 2023 maps place the broad recorded overlap on the جبل طريف side of their shared administrative linework.",
        "purpose": "Preserve a small source-supported overlap while clipping the broad contested area from Kabouti; do not install this candidate as the full Kabouti boundary.",
        "uncertainty": "The 2023 electoral GeoPDF ring remains pending as a whole-boundary validation and is interpreted against the paired Tarif map only at this seam. Control residuals describe map registration fit, not ground or legal-boundary accuracy. The retained overlap is intentionally unresolved and must remain suppressed by the app conflict rule.",
    }
    review_raw = write_json(REVIEW, review)
    print(json.dumps({"status": "passed", "officialSectorCode": OFFICIAL_CODE,
                      "pdfSha256": pdf_sha, "geojsonSha256": geojson_sha,
                      "sourceReviewSha256": sha256(review_raw),
                      "geometryType": candidate.geom_type, "valid": candidate.is_valid,
                      "areaSquareKmApprox": candidate.area * 111.32 ** 2 * np.cos(np.radians(candidate.representative_point().y)),
                      "geometryBounds": candidate.bounds, "georef": georef}, ensure_ascii=True, indent=2))


if __name__ == "__main__":
    main()
