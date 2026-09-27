"""Read-only, source-only inventory of ISIE GeoPDF linework.

This module does not associate a PDF drawing with an official imada or an app
polygon.  Every geometry below is an inspection hypothesis until identity,
scope, neighboring maps, and geographic placement have been reviewed.

Run with the existing task-local Python environment, for example::

    python -m scripts.locality_automation.isie_pdf_inventory \
        --pdf saved-map.pdf --output source-inventory.json
"""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import hashlib
import json
import math
from pathlib import Path
import re

import numpy as np
import pypdf
from pypdf import PdfReader
import pymupdf
import pyproj
from pyproj import CRS, Transformer
import shapely
from shapely.geometry import LineString, Point, Polygon
from shapely.ops import polygonize_full, unary_union
from shapely.validation import explain_validity


VERSION = "isie-source-inventory-v3"
BOUNDARY_WORDS = re.compile(r"circonscription|gouv|deleg|limit|front|sect|imada|boundary|حدود|معتمد|ولاية", re.I)
YEAR = re.compile(r"(?<!\d)(?:19|20)\d{2}(?!\d)")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def _json_hash(value: object) -> str:
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")).hexdigest()


def _cache_inputs(pdf_hash: str, junction_tolerance_points: float) -> dict:
    return {"sourcePdfSha256": pdf_hash, "inspectorVersion": VERSION,
            "parserVersions": {"pymupdf": pymupdf.VersionBind, "pypdf": pypdf.__version__,
                               "pyproj": pyproj.__version__, "shapely": shapely.__version__,
                               "numpy": np.__version__},
            "settings": {"junctionTolerancePagePoints": junction_tolerance_points}}


def _pairs(numbers: object) -> np.ndarray:
    values = np.asarray([float(number) for number in numbers], dtype=float)
    if len(values) % 2 or not np.isfinite(values).all():
        raise ValueError("Geospatial controls are not finite point pairs")
    return values.reshape((-1, 2))


def _georeferences(raw_page: object, page: pymupdf.Page) -> list[dict]:
    viewports = raw_page.get("/VP", [])
    result = []
    for index, reference in enumerate(viewports):
        record: dict = {"viewportIndex": index, "status": "unusable", "issues": []}
        result.append(record)
        try:
            viewport = reference.get_object()
            bbox = [float(number) for number in viewport["/BBox"]]
            if len(bbox) != 4:
                raise ValueError("Viewport BBox must contain four numbers")
            measure = viewport["/Measure"].get_object()
            record.update({"bboxPdfPoints": bbox, "measureSubtype": str(measure.get("/Subtype", ""))})
            if str(measure.get("/Subtype")) != "/GEO":
                raise ValueError("Viewport measure is not geospatial")
            local, geographic = _pairs(measure["/LPTS"]), _pairs(measure["/GPTS"])
            record.update({"localControls": local.tolist(), "geographicControlsLatLon": geographic.tolist(),
                           "controlCount": len(local)})
            if len(local) != len(geographic) or len(local) < 3:
                raise ValueError("At least three paired controls are needed for an affine fit")
            gcs = measure["/GCS"].get_object()
            wkt = str(gcs["/WKT"])
            crs = CRS.from_wkt(wkt)
            record.update({"crsWkt": wkt, "crsEpsg": crs.to_epsg(), "crsName": crs.name,
                           "crsProjected": crs.is_projected})
            if not crs.geodetic_crs:
                raise ValueError("No geographic CRS is available for the controls")
            to_map = Transformer.from_crs(crs.geodetic_crs, crs, always_xy=True)
            page_xy = []
            for x, y in local:
                point = pymupdf.Point(bbox[0] + x * (bbox[2] - bbox[0]),
                                      bbox[1] + y * (bbox[3] - bbox[1])) * page.transformation_matrix
                page_xy.append([point.x, point.y, 1.0])
            design = np.asarray(page_xy)
            mapped = np.asarray([to_map.transform(lon, lat) for lat, lon in geographic])
            affine, _, rank, _ = np.linalg.lstsq(design, mapped, rcond=None)
            if rank != 3 or not np.isfinite(affine).all():
                raise ValueError("Control geometry cannot determine a stable affine fit")
            residuals = np.linalg.norm(design @ affine - mapped, axis=1)
            axis = crs.axis_info
            meter_factor = axis[0].unit_conversion_factor if crs.is_projected and len(axis) >= 2 else None
            record.update({"status": "fitted", "pageControls": design[:, :2].tolist(),
                           "affineMapUnitsFromPagePoints": affine.tolist(),
                           "designCondition": float(np.linalg.cond(design)),
                           "controlResidualMapUnits": residuals.tolist(),
                           "maxControlResidualMapUnits": float(max(residuals)),
                           "maxControlResidualMeters": float(max(residuals) * meter_factor) if meter_factor else None,
                           "mapUnitsToMeters": meter_factor,
                           "qualityFlags": (["poorly conditioned embedded controls"] if np.linalg.cond(design) > 1e6 else [])
                               + (["internal control residual exceeds 1 metre"] if meter_factor and max(residuals) * meter_factor > 1 else []),
                           "independentGeographicCheck": "not supplied by embedded PDF controls"})
        except (KeyError, ValueError, TypeError, AttributeError, np.linalg.LinAlgError) as exc:
            record["issues"].append(str(exc))
    return result


def _color_family(color: object) -> str:
    if not isinstance(color, (tuple, list)) or len(color) != 3:
        return "none"
    red, green, blue = color
    if red >= .75 and green <= .35 and blue <= .35:
        return "red"
    if max(red, green, blue) <= .22:
        return "black"
    return "other"


def _visible_stroke(drawing: dict) -> bool:
    color = drawing.get("color")
    return (isinstance(color, (tuple, list)) and len(color) == 3
            and min(color) < .95 and float(drawing.get("stroke_opacity", 1)) > .01)


def _point(value: object) -> tuple[float, float]:
    return float(value[0]), float(value[1])


def _path_runs(drawing: dict) -> tuple[list[list[tuple[float, float]]], list[str]]:
    """Preserve disjoint subpaths and endpoints; curves remain explicitly flagged."""
    runs: list[list[tuple[float, float]]] = []
    current: list[tuple[float, float]] = []
    unsupported: list[str] = []
    for item in drawing.get("items", []):
        kind = item[0]
        if kind == "l":
            start, end = _point(item[1]), _point(item[2])
            if current and current[-1] != start:
                runs.append(current)
                current = []
            if not current:
                current.append(start)
            current.append(end)
        elif kind in ("re", "qu"):
            if current:
                runs.append(current)
                current = []
            shape = item[1]
            if kind == "re":
                corners = [(shape.x0, shape.y0), (shape.x1, shape.y0),
                           (shape.x1, shape.y1), (shape.x0, shape.y1)]
            else:
                corners = [_point(shape.ul), _point(shape.ur), _point(shape.lr), _point(shape.ll)]
            runs.append(corners + [corners[0]])
        else:
            unsupported.append(kind)
            if current:
                runs.append(current)
                current = []
    if current:
        runs.append(current)
    if drawing.get("closePath") and len(runs) == 1 and runs[0] and runs[0][0] != runs[0][-1]:
        runs[0] = runs[0] + [runs[0][0]]
    return runs, unsupported


def _labels(page: pymupdf.Page) -> list[dict]:
    labels = []
    for block in page.get_text("dict")["blocks"]:
        if block.get("type") != 0:
            continue
        for line in block.get("lines", []):
            spans = line.get("spans", [])
            value = "".join(span.get("text", "") for span in spans).strip()
            if not value:
                continue
            bounds = [float(number) for number in line["bbox"]]
            colors = sorted({int(span.get("color", 0)) for span in spans})
            labels.append({"text": value, "boundsPagePoints": bounds,
                           "centerPagePoints": [(bounds[0] + bounds[2]) / 2, (bounds[1] + bounds[3]) / 2],
                           "colors": colors,
                           "redText": any(_color_family(((color >> 16 & 255) / 255,
                                                          (color >> 8 & 255) / 255,
                                                          (color & 255) / 255)) == "red" for color in colors),
                           "maxFontSize": max(float(span.get("size", 0)) for span in spans)})
    return labels


def _polygon_facts(coordinates: list[tuple[float, float]], georef: dict | None, labels: list[dict]) -> dict:
    polygon = Polygon(coordinates)
    valid = bool(polygon.is_valid and polygon.area > 0)
    inside = [item for item in labels if valid and polygon.covers(Point(item["centerPagePoints"]))]
    facts: dict = {"validNativePolygon": valid, "nativeValidity": explain_validity(polygon),
                   "areaPagePoints2": float(polygon.area),
                   "labelsInside": [item["text"] for item in inside],
                   "redLabelsInside": [item["text"] for item in inside if item["redText"]]}
    if georef and georef.get("status") == "fitted":
        affine = np.asarray(georef["affineMapUnitsFromPagePoints"])
        vertices = np.column_stack((np.asarray(coordinates), np.ones(len(coordinates)))) @ affine
        mapped = Polygon(vertices)
        factor = georef.get("mapUnitsToMeters")
        facts.update({"areaMapUnits2": float(mapped.area),
                      "areaM2": float(mapped.area * factor * factor) if factor else None})
    return facts


def _drawing_inventory(page: pymupdf.Page, georefs: list[dict], labels: list[dict], tolerance: float) -> tuple[list[dict], list[dict], dict]:
    fitted = next((item for item in georefs if item["status"] == "fitted"), None)
    paths, geometry = [], []
    layers: dict[str, dict] = defaultdict(lambda: {"drawingCount": 0, "lineItems": 0, "strokeFamilies": Counter()})
    for index, drawing in enumerate(page.get_drawings()):
        layer = str(drawing.get("layer") or "(unlayered)")
        family = _color_family(drawing.get("color"))
        item_kinds = Counter(item[0] for item in drawing.get("items", []))
        layer_row = layers[layer]
        layer_row["drawingCount"] += 1
        layer_row["lineItems"] += item_kinds["l"]
        layer_row["strokeFamilies"][family] += 1
        runs, unsupported = _path_runs(drawing)
        boundary_named = bool(BOUNDARY_WORDS.search(layer))
        relevant = _visible_stroke(drawing) and (boundary_named or family in ("red", "black"))
        entry: dict = {"drawingIndex": index, "layer": layer, "strokeFamily": family,
                       "strokeColor": list(drawing["color"]) if drawing.get("color") else None,
                       "lineWidthPagePoints": drawing.get("width"), "itemKinds": dict(item_kinds),
                       "lineItemCount": item_kinds["l"], "runCount": len(runs),
                       "unsupportedKinds": sorted(set(unsupported)),
                       "pdfClosePath": bool(drawing.get("closePath")),
                       "boundsPagePoints": list(drawing["rect"]),
                       "boundaryLayerNameHint": boundary_named, "visibleStroke": _visible_stroke(drawing),
                       "relevantLineworkHint": relevant,
                       "relevanceReasons": (["boundary-like layer name"] if boundary_named and relevant else [])
                           + ([family + " stroke"] if family in ("red", "black") and relevant else [])}
        if runs:
            entry["runs"] = []
            for run in runs:
                closed = len(run) >= 4 and run[0] == run[-1]
                run_info: dict = {"vertices": len(run), "startPagePoints": run[0], "endPagePoints": run[-1],
                                  "closed": closed, "coordinateSha256": _json_hash(run)}
                if closed and not unsupported:
                    run_info.update(_polygon_facts(run, fitted, labels))
                elif relevant and len(run) >= 2:
                    line = LineString(run)
                    run_info["nearbyRedLabelsWithin20Points"] = [item["text"] for item in labels
                        if item["redText"] and line.distance(Point(item["centerPagePoints"])) <= 20]
                # Store native vertices only for the relevant paths. Other paths remain
                # addressable by drawing index and the immutable PDF hash.
                if relevant:
                    run_info["nativePagePoints"] = run
                entry["runs"].append(run_info)
                if len(run) >= 2 and not unsupported and drawing.get("color") is not None and relevant:
                    geometry.append({"index": index, "layer": layer, "family": family,
                                     "run": len(entry["runs"]) - 1, "line": LineString(run)})
        paths.append(entry)
    layer_inventory = [{"layer": name, "drawingCount": row["drawingCount"],
                        "lineItems": row["lineItems"], "strokeFamilies": dict(row["strokeFamilies"])}
                       for name, row in sorted(layers.items())]
    return paths, layer_inventory, _topology(geometry, labels, fitted, tolerance)


def _topology(geometry: list[dict], labels: list[dict], georef: dict | None, tolerance: float) -> dict:
    """Report exact line polygonization and near endpoint junctions as leads."""
    endpoints = []
    for item in geometry:
        line = item["line"]
        if line.is_empty or line.is_closed:
            continue
        for end_name, xy in (("start", line.coords[0]), ("end", line.coords[-1])):
            endpoints.append({"drawingIndex": item["index"], "run": item["run"],
                              "end": end_name, "point": xy})
    junctions = []
    for i, left in enumerate(endpoints):
        for right in endpoints[i + 1:]:
            if left["drawingIndex"] == right["drawingIndex"] and left["run"] == right["run"]:
                continue
            distance = math.dist(left["point"], right["point"])
            if distance <= tolerance:
                junctions.append({"a": {key: left[key] for key in ("drawingIndex", "run", "end")},
                                  "b": {key: right[key] for key in ("drawingIndex", "run", "end")},
                                  "distancePagePoints": distance})
    endpoint_to_line = []
    for endpoint in endpoints:
        point = Point(endpoint["point"])
        for item in geometry:
            if endpoint["drawingIndex"] == item["index"] and endpoint["run"] == item["run"]:
                continue
            distance = point.distance(item["line"])
            if distance <= tolerance:
                endpoint_to_line.append({"endpoint": {key: endpoint[key] for key in ("drawingIndex", "run", "end")},
                                         "line": {"drawingIndex": item["index"], "run": item["run"]},
                                         "distancePagePoints": float(distance)})
    # Exact polygonization can reveal rings shared by differently colored paths.
    # No snapping or gap-filling is performed here.
    linework = [item["line"] for item in geometry if not item["line"].is_empty]
    connected_rings = []
    dangle_count = cut_count = invalid_count = 0
    if linework:
        polygons, dangles, cuts, invalid = polygonize_full(unary_union(linework))
        dangle_count, cut_count, invalid_count = len(dangles.geoms), len(cuts.geoms), len(invalid.geoms)
        for polygon in polygons.geoms:
            if polygon.area <= 0:
                continue
            contributors = []
            for item in geometry:
                try:
                    common = polygon.boundary.intersection(item["line"]).length
                except Exception:
                    continue
                if common > 1e-6:
                    contributors.append({"drawingIndex": item["index"], "run": item["run"],
                                         "layer": item["layer"], "strokeFamily": item["family"],
                                         "sharedBoundaryPagePoints": float(common)})
            if len({item["drawingIndex"] for item in contributors}) < 2:
                continue  # a single closed drawing is reported under its own path
            ring = {"areaPagePoints2": float(polygon.area), "boundsPagePoints": list(polygon.bounds),
                    "contributors": contributors,
                    "reason": "exactly connected linework across multiple drawings; identity and scope unreviewed"}
            ring.update(_polygon_facts(list(polygon.exterior.coords), georef, labels))
            connected_rings.append(ring)
    return {"junctionTolerancePagePoints": tolerance, "endpointJunctions": junctions,
            "endpointToLineJunctions": endpoint_to_line,
            "exactConnectedRings": connected_rings,
            "polygonizeDangleCount": dangle_count, "polygonizeCutCount": cut_count,
            "polygonizeInvalidCount": invalid_count,
            "qualification": "Endpoint proximity and exact polygonization are leads, not proof that line layers share an official boundary."}


def inspect(pdf_path: Path, *, junction_tolerance_points: float = .25) -> dict:
    pdf_path = Path(pdf_path).resolve()
    if not pdf_path.is_file():
        raise FileNotFoundError(pdf_path)
    if not math.isfinite(junction_tolerance_points) or junction_tolerance_points <= 0:
        raise ValueError("junction tolerance must be a finite positive number of PDF page points")
    source_hash = sha256(pdf_path)
    cache_inputs = _cache_inputs(source_hash, junction_tolerance_points)
    reader = PdfReader(str(pdf_path), strict=False)
    pages = []
    with pymupdf.open(pdf_path) as document:
        if len(reader.pages) != len(document):
            raise ValueError("PDF parsers disagree on page count")
        for index, page in enumerate(document):
            labels = _labels(page)
            georefs = _georeferences(reader.pages[index], page)
            paths, layers, topology = _drawing_inventory(page, georefs, labels, junction_tolerance_points)
            closed_red = [item["drawingIndex"] for item in paths if item["strokeFamily"] == "red"
                          and item["relevantLineworkHint"] and item["boundaryLayerNameHint"]
                          and any(run.get("closed") and run.get("validNativePolygon") for run in item.get("runs", []))]
            closed_other = [item["drawingIndex"] for item in paths if item["relevantLineworkHint"]
                            and item["drawingIndex"] not in closed_red
                            and any(run.get("closed") and run.get("validNativePolygon") for run in item.get("runs", []))]
            open_relevant = [item["drawingIndex"] for item in paths if item["relevantLineworkHint"]
                             and any(not run["closed"] for run in item.get("runs", []))]
            if closed_red:
                classification = "complete_native_path_hypothesis_present"
                reason = "At least one valid closed red native path exists; labeled scope and geographic placement still need review."
            elif closed_other:
                classification = "other_visible_closed_path_hypothesis_present"
                reason = "A visible boundary-like or black native path closes; map semantics and geographic scope need review."
            elif topology["exactConnectedRings"]:
                classification = "connected_layer_recovery_hypothesis_present"
                reason = "Separate relevant line drawings form an exact ring; shared-layer meaning and neighboring maps still need review."
            elif open_relevant:
                classification = "broken_or_open_linework_needs_neighbor_review"
                reason = "Relevant native paths are open; no complete path was established by this inventory."
            else:
                classification = "non_traceable_from_inspected_native_linework"
                reason = "No complete or open relevant linework was identified; raster and neighboring-map inspection may change this."
            page_text = "\n".join(label["text"] for label in labels)
            pages.append({"pageIndex": index, "pageSizePoints": [page.rect.width, page.rect.height],
                          "rotation": page.rotation, "mediaBox": list(page.mediabox), "cropBox": list(page.cropbox),
                          "georeferences": georefs, "lineLayers": layers, "nativePaths": paths,
                          "lineTopology": topology, "textLabels": labels,
                          "labeledAreaLeads": [item for item in labels if item["redText"]],
                          "dateMentionsInPageText": sorted(set(YEAR.findall(page_text))),
                          "dateMentionsInLayerNames": sorted(set(YEAR.findall(" ".join(item["layer"] for item in layers)))),
                          "sourceLineworkClassification": {"value": classification, "reason": reason,
                                                           "closedRedDrawingIndexes": closed_red,
                                                           "otherClosedRelevantDrawingIndexes": closed_other,
                                                           "openRelevantDrawingIndexes": open_relevant}})
    metadata = reader.metadata
    if sha256(pdf_path) != source_hash:
        raise ValueError("Source PDF changed during inspection")
    return {"schemaVersion": 1, "status": "READ_ONLY_SOURCE_ONLY_UNREVIEWED",
            "sourcePdf": {"file": str(pdf_path), "sha256": source_hash, "bytes": pdf_path.stat().st_size},
            "cacheInputs": cache_inputs, "cacheKey": _json_hash(cache_inputs),
            "pdfMetadata": {"title": str(metadata.title) if metadata and metadata.title else None,
                            "creationDate": str(metadata.creation_date) if metadata and metadata.creation_date else None,
                            "modificationDate": str(metadata.modification_date) if metadata and metadata.modification_date else None},
            "pageCount": len(pages), "pages": pages,
            "qualification": "No official identity or app geometry was used. Native rings and control residuals are inspection evidence, not accepted boundaries or external accuracy checks."}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pdf", required=True, type=Path, help="Saved source PDF; no network access")
    parser.add_argument("--output", required=True, type=Path, help="New inventory JSON path")
    parser.add_argument("--junction-tolerance-points", type=float, default=.25)
    args = parser.parse_args()
    output = args.output.resolve()
    if not math.isfinite(args.junction_tolerance_points) or args.junction_tolerance_points <= 0:
        raise ValueError("junction tolerance must be a finite positive number of PDF page points")
    source_hash = sha256(args.pdf.resolve())
    cache_key = _json_hash(_cache_inputs(source_hash, args.junction_tolerance_points))
    if output.exists():
        old = json.loads(output.read_text(encoding="utf-8"))
        if old.get("cacheKey") != cache_key or old.get("sourcePdf", {}).get("sha256") != source_hash:
            raise FileExistsError(f"Existing output has different source or settings: {output}")
        print(json.dumps({"status": "cache_hit", "output": str(output), "cacheKey": cache_key}))
        return
    report = inspect(args.pdf, junction_tolerance_points=args.junction_tolerance_points)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("x", encoding="utf-8") as target:
        json.dump(report, target, ensure_ascii=False, indent=2)
        target.write("\n")
    print(json.dumps({"status": "written", "output": str(output), "cacheKey": report["cacheKey"],
                      "pages": report["pageCount"]}))


if __name__ == "__main__":
    main()
