"""Offline diagnostic comparison of pinned ISIE linework with installed sectors.

The pinned official identity is an input to inspect, never inferred from drawing
index, overlap, a label, or a PDF title.  Candidate metrics and rankings do not
authorize a boundary change or award accepted geographic evidence.
"""

from __future__ import annotations

import argparse
from collections import OrderedDict
import hashlib
import json
import math
from pathlib import Path
import unicodedata

import numpy as np
from pyproj import CRS, Transformer
from shapely.geometry import LineString, MultiPolygon, Point, Polygon
from shapely.ops import polygonize_full, transform, unary_union

from scripts.locality_review.boundary import _BINARY_HEADER, _parse_multipolygon
from .isie_pdf_inventory import sha256


VERSION = "isie-candidate-comparison-v4"
# Eight nearby *source maps* are a bounded relative-placement check, not the
# installed-sector conflict universe. Every installed sector intersecting the
# changed footprint is checked below, including one across a governorate edge.
REFERENCE_SOURCE_COUNT = 8
BOUNDARY_MATCH_TOLERANCE_M = 20.0
# Match the existing 20 m source-line proximity tolerance as a conservative
# margin for source line width, embedded-control error and packed-catalog
# quantization. It selects sectors for inspection; it neither
# excuses a 20 m disagreement nor asserts 20 m absolute map accuracy.
CONFLICT_POSITIONAL_ERROR_BUFFER_M = 20.0
OVERLAP_DIAGNOSTIC_AREA_M2 = 1.0
MIN_AGREEMENT_M = 1000.0
MIN_AGREEMENT_FRACTION = .10


def _hash_json(value: object) -> str:
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
                                  separators=(",", ":")).encode("utf-8")).hexdigest()


def _name(value: str) -> str:
    return "".join(character for character in unicodedata.normalize("NFKC", value)
                   if character.isalnum()).replace("ی", "ي").replace("ھ", "ه")


def _name_match(text: str, official: str) -> bool:
    wanted = _name(official)
    return bool(wanted and wanted in (_name(text), _name(text[::-1])))


def _name_context(text: str, official: str) -> bool:
    wanted = _name(official)
    return bool(wanted and any(wanted in value for value in (_name(text), _name(text[::-1]))))


def _label_scope(red_labels: list[str], official_names: dict[str, str]) -> dict:
    matched = []
    for label in red_labels:
        for identifier, name in official_names.items():
            if _name_match(label, name):
                matched.append({"label": label, "id": identifier, "officialName": name})
    distinct = {row["id"] for row in matched}
    multiple_red = len({_name(label) for label in red_labels if _name(label)}) > 1
    return {"officialAreaLabelMatches": matched,
            "confirmedMultipleOfficialAreasInside": len(distinct) > 1,
            "multipleDistinctRedLabelsInside": multiple_red,
            "scopeFlag": "confirmed_multi_imada_container" if len(distinct) > 1 else
                         "scope_review_multiple_red_labels" if multiple_red else
                         "single_or_no_red_label"}


def _pin(path: Path) -> dict:
    path = Path(path).resolve()
    return {"file": str(path), "sha256": sha256(path), "bytes": path.stat().st_size}


def _bbox_intersects(a: list[float] | tuple[float, ...],
                     b: list[float] | tuple[float, ...]) -> bool:
    return a[0] <= b[2] and b[0] <= a[2] and a[1] <= b[3] and b[1] <= a[3]


class Catalog:
    def __init__(self, metadata_path: Path, binary_path: Path):
        self.metadata_path, self.binary_path = Path(metadata_path).resolve(), Path(binary_path).resolve()
        self.metadata_pin, self.binary_pin = _pin(self.metadata_path), _pin(self.binary_path)
        self.metadata = json.loads(self.metadata_path.read_text(encoding="utf-8"))
        self.binary = self.binary_path.read_bytes()
        if not self.binary.startswith(_BINARY_HEADER):
            raise ValueError("Packed catalog header does not match the locality decoder")
        self.rows = {row["id"]: row for row in self.metadata["features"]}
        self._geometry: dict[tuple[str, str], MultiPolygon] = {}

    def governorate_id(self, identifier: str) -> object:
        governorate = self.row(identifier).get("governorateId")
        if governorate is None:
            raise ValueError(f"Installed sector has no governorate: {identifier}")
        return governorate

    def sector_rows_in_bbox(self, identifier: str, bbox_wgs84: tuple[float, ...]) -> list[dict]:
        """Cheap all-sector prefilter; absent bboxes pass conservatively."""
        return [row for row in self.rows.values()
                if row["id"] != identifier and row.get("kind") == "sector"
                and row.get("hasBoundary")
                and (row.get("bbox") is None or _bbox_intersects(row["bbox"], bbox_wgs84))]

    def row(self, identifier: str) -> dict:
        row = self.rows[identifier]
        if row.get("kind") != "sector" or not row.get("hasBoundary"):
            raise ValueError(f"Installed identity has no sector boundary: {identifier}")
        return row

    def geometry(self, identifier: str, crs: CRS) -> MultiPolygon:
        key = identifier, crs.srs
        if key not in self._geometry:
            row = self.row(identifier)
            rings = _parse_multipolygon(self.binary, row["offset"], row["length"],
                                        self.metadata["coordinateScale"], identifier)
            raw = MultiPolygon([Polygon(parts[0], parts[1:]) for parts in rings])
            to_map = Transformer.from_crs(4326, crs, always_xy=True)
            self._geometry[key] = transform(to_map.transform, raw)
        return self._geometry[key]

    def boundary_pin(self, identifier: str) -> dict:
        row = self.row(identifier)
        start, length = row["offset"], row["length"]
        part = self.binary[start:start + length]
        return {"id": identifier, "sha256": hashlib.sha256(part).hexdigest(),
                "bytes": length, "coordinateScale": self.metadata["coordinateScale"],
                "installedName": row.get("name"), "installedParent": row.get("parentName")}


def _georef(page: dict) -> dict | None:
    fitted = [record for record in page["georeferences"] if record.get("status") == "fitted"]
    return fitted[0] if len(fitted) == 1 else None


def _map_geometry(page_geometry: Polygon | LineString, reference: dict) -> Polygon | LineString:
    affine = np.asarray(reference["affineMapUnitsFromPagePoints"])
    return transform(lambda x, y, z=None: (x * affine[0, 0] + y * affine[1, 0] + affine[2, 0],
                                           x * affine[0, 1] + y * affine[1, 1] + affine[2, 1]), page_geometry)


def _closed_red_candidates(page: dict, reference: dict, official_name: str,
                           official_names: dict[str, str] | None = None) -> list[dict]:
    candidates = []
    for path in page["nativePaths"]:
        if not (path["strokeFamily"] == "red" and path["boundaryLayerNameHint"] and path["visibleStroke"]):
            continue
        for run_index, run in enumerate(path.get("runs", [])):
            if not (run["closed"] and run.get("validNativePolygon") and run.get("nativePagePoints")):
                continue
            native = Polygon(run["nativePagePoints"])
            if not native.is_valid or native.area <= 0:
                continue
            red_labels = run.get("redLabelsInside", [])
            candidates.append({"key": f"red:{page['pageIndex']}:{path['drawingIndex']}:{run_index}",
                               "method": "native_red_closed", "page": page["pageIndex"],
                               "drawingIndexes": [path["drawingIndex"]], "run": run_index,
                               "layer": path["layer"], "redLabelsInside": red_labels,
                               "exactOfficialNameInRedLabel": any(_name_match(label, official_name) for label in red_labels),
                               "contextualOfficialNameInRedLabel": any(_name_context(label, official_name) for label in red_labels),
                               "redLabelCount": len(red_labels),
                               **_label_scope(red_labels, official_names or {}),
                               "nativeAreaPagePoints2": float(native.area),
                               "geometry": _map_geometry(native, reference)})
    return candidates


def _connected_candidates(page: dict, reference: dict, official_name: str,
                          official_names: dict[str, str] | None = None) -> list[dict]:
    lines = []
    for path in page["nativePaths"]:
        if not path["relevantLineworkHint"]:
            continue
        for run_index, run in enumerate(path.get("runs", [])):
            points = run.get("nativePagePoints")
            if points and len(points) >= 2 and not path.get("unsupportedKinds"):
                lines.append({"drawingIndex": path["drawingIndex"], "run": run_index,
                              "layer": path["layer"], "family": path["strokeFamily"],
                              "line": LineString(points)})
    if not lines:
        return []
    polygons, _, _, _ = polygonize_full(unary_union([item["line"] for item in lines]))
    candidates = []
    for index, native in enumerate(polygons.geoms):
        if native.area <= 0:
            continue
        contributors = [item for item in lines if native.boundary.intersection(item["line"]).length > 1e-6]
        drawing_indexes = sorted({item["drawingIndex"] for item in contributors})
        families = {item["family"] for item in contributors}
        if len(drawing_indexes) < 2 or "red" not in families or len(families) < 2:
            continue
        red_labels = [item["text"] for item in page["labeledAreaLeads"]
                      if native.covers(Point(item["centerPagePoints"]))]
        candidates.append({"key": f"connected:{page['pageIndex']}:{index}",
                           "method": "exact_connected_layers", "page": page["pageIndex"],
                           "drawingIndexes": drawing_indexes, "strokeFamilies": sorted(families),
                           "redLabelsInside": red_labels,
                           "exactOfficialNameInRedLabel": any(_name_match(label, official_name) for label in red_labels),
                           "contextualOfficialNameInRedLabel": any(_name_context(label, official_name) for label in red_labels),
                           "redLabelCount": len(red_labels),
                           **_label_scope(red_labels, official_names or {}),
                           "nativeAreaPagePoints2": float(native.area),
                           "geometry": _map_geometry(native, reference)})
    return candidates


def _metrics(candidate: Polygon, installed: MultiPolygon, factor: float) -> dict:
    intersection = candidate.intersection(installed).area * factor * factor
    candidate_area = candidate.area * factor * factor
    installed_area = installed.area * factor * factor
    union = candidate_area + installed_area - intersection
    return {"candidateAreaM2": candidate_area, "installedAreaM2": installed_area,
            "intersectionM2": intersection, "intersectionOverUnion": intersection / union if union else None,
            "candidateCoveredByInstalled": intersection / candidate_area if candidate_area else None,
            "installedCoveredByCandidate": intersection / installed_area if installed_area else None,
            "candidateOutsideInstalledM2": candidate_area - intersection,
            "installedOutsideCandidateM2": installed_area - intersection,
            "hausdorffBoundaryMeters": candidate.boundary.hausdorff_distance(installed.boundary) * factor}


def _installed_sector_conflicts(case_id: str, candidate: Polygon, installed: MultiPolygon,
                                crs: CRS, factor: float, catalog: Catalog) -> dict:
    """Inspect all installed sectors around old OR proposed geometry.

    The bbox prefilter is in catalog lon/lat coordinates. Packed neighbor
    geometries are decoded only after their bboxes reach the buffered footprint.
    The 1 m² flag is diagnostic, never an acceptance or installation decision.
    """
    footprint = installed.union(candidate).buffer(CONFLICT_POSITIONAL_ERROR_BUFFER_M / factor)
    to_wgs84 = Transformer.from_crs(crs, 4326, always_xy=True)
    bbox = tuple(float(value) for value in transform(to_wgs84.transform, footprint).bounds)
    newly_covered = candidate.difference(installed)
    sectors = []
    for row in catalog.sector_rows_in_bbox(case_id, bbox):
        other = catalog.geometry(row["id"], crs)
        if not footprint.intersects(other):
            continue
        old_overlap = installed.intersection(other).area * factor * factor
        proposed_overlap = candidate.intersection(other).area * factor * factor
        newly_covered_overlap = newly_covered.intersection(other).area * factor * factor
        sectors.append({"id": row["id"], "governorateId": row.get("governorateId"),
                        "installedBoundaryPin": catalog.boundary_pin(row["id"]),
                        "oldOverlapM2": old_overlap, "proposedOverlapM2": proposed_overlap,
                        "newlyCoveredOverlapM2": newly_covered_overlap,
                        "overlapDeltaM2": proposed_overlap - old_overlap,
                        "distanceToProposedMeters": candidate.distance(other) * factor,
                        "newOverlapDiagnostic": newly_covered_overlap > OVERLAP_DIAGNOSTIC_AREA_M2,
                        "newlyIntersectingDiagnostic": old_overlap <= OVERLAP_DIAGNOSTIC_AREA_M2
                        and proposed_overlap > OVERLAP_DIAGNOSTIC_AREA_M2})
    sectors.sort(key=lambda item: item["id"])
    return {"status": "new_installed_overlap_diagnostic" if any(row["newOverlapDiagnostic"] for row in sectors)
            else "no_new_installed_overlap_diagnostic",
            "governorateId": catalog.governorate_id(case_id),
            "scope": "all installed sectors intersecting buffered union of old target and proposed candidate, including across governorate edges",
            "positionalErrorSearchBufferMeters": CONFLICT_POSITIONAL_ERROR_BUFFER_M,
            "scopeBboxWgs84": bbox, "inspectedSectorCount": len(sectors),
            "newOverlapDiagnosticCount": sum(row["newOverlapDiagnostic"] for row in sectors),
            "sectors": sectors}


def _reference_lines(inventory: dict, destination_crs: CRS) -> object | None:
    geometries = []
    for page in inventory["pages"]:
        reference = _georef(page)
        if not reference or not reference.get("crsWkt"):
            continue
        source_crs = CRS.from_wkt(reference["crsWkt"])
        to_dest = Transformer.from_crs(source_crs, destination_crs, always_xy=True)
        for path in page["nativePaths"]:
            if not path["relevantLineworkHint"] or path["strokeFamily"] == "none":
                continue
            for run in path.get("runs", []):
                points = run.get("nativePagePoints")
                if not points or len(points) < 2 or path.get("unsupportedKinds"):
                    continue
                native = LineString(points)
                mapped = _map_geometry(native, reference)
                geometries.append(transform(to_dest.transform, mapped))
    return unary_union(geometries) if geometries else None


def _prepare_reference_checks(references: list[tuple[str, dict]], crs: CRS, factor: float,
                              cache: OrderedDict | None = None) -> list[tuple[str, str, object]]:
    prepared = []
    for case_id, inventory in references:
        source_hash = inventory["sourcePdf"]["sha256"]
        key = source_hash, crs.srs, factor
        if cache is not None and key in cache:
            buffered = cache[key]
            cache.move_to_end(key)
        else:
            lines = _reference_lines(inventory, crs)
            buffered = lines.buffer(BOUNDARY_MATCH_TOLERANCE_M / factor) if lines is not None and not lines.is_empty else None
            if cache is not None:
                cache[key] = buffered
                if len(cache) > 24:
                    cache.popitem(last=False)
        if buffered is not None:
            prepared.append((case_id, source_hash, buffered))
    return prepared


def _independent_source_check(candidate: Polygon, references: list[tuple[str, str, object]], factor: float) -> dict:
    results = []
    boundary = candidate.boundary
    for case_id, source_hash, buffered_lines in references:
        shared = boundary.intersection(buffered_lines).length * factor
        fraction = shared / (boundary.length * factor) if boundary.length else 0
        results.append({"neighborId": case_id, "sourcePdfSha256": source_hash,
                        "boundaryWithin20mOfNeighborSourceMeters": shared,
                        "fractionOfCandidateBoundary": fraction})
    results.sort(key=lambda row: (-row["boundaryWithin20mOfNeighborSourceMeters"], row["neighborId"]))
    qualified = [row for row in results if row["boundaryWithin20mOfNeighborSourceMeters"] >= MIN_AGREEMENT_M
                 and row["fractionOfCandidateBoundary"] >= MIN_AGREEMENT_FRACTION]
    return {"status": "cross_source_relative_consistency" if qualified else "no_qualifying_cross_source_check",
            "checks": results,
            "qualification": "A separately georeferenced ISIE neighbor map checks relative placement. Shared production may carry a common geographic error; this is not an independent absolute accuracy check."}


def _comparison_cache_inputs(case: dict, inventory: dict, catalog: Catalog,
                             neighbors: list[tuple[str, dict]], official_names: dict[str, str],
                             scope_bboxes_wgs84: list[tuple[float, ...]]) -> dict:
    installed_pin = catalog.boundary_pin(case["id"])
    # Pin every bbox-qualified sector, including those whose exact geometry
    # currently misses the footprint. A new entrant or changed shared edge
    # then invalidates this comparison without depending on the whole catalog.
    affected = {row["id"]: catalog.boundary_pin(row["id"])
                for bbox in scope_bboxes_wgs84
                for row in catalog.sector_rows_in_bbox(case["id"], bbox)}
    return {"version": VERSION, "sourcePdfSha256": case["sourcePdfSha256"],
            "sourceInventoryCacheKey": inventory["cacheKey"],
            "officialCase": {key: case[key] for key in ("id", "nameAr", "officialCode")},
            "officialAreaNameIndexSha256": _hash_json(official_names),
            "installedBoundary": {key: installed_pin[key] for key in ("id", "sha256", "coordinateScale")},
            "governorateId": catalog.governorate_id(case["id"]),
            "candidateScopeBboxesWgs84": scope_bboxes_wgs84,
            "affectedInstalledBoundaries": sorted((id_, pin["sha256"]) for id_, pin in affected.items()),
            "neighborSources": sorted((id_, source["sourcePdf"]["sha256"], source["cacheKey"])
                                      for id_, source in neighbors),
            "settings": {"referenceSourceCount": REFERENCE_SOURCE_COUNT,
                         "lineToleranceMeters": BOUNDARY_MATCH_TOLERANCE_M,
                         "minAgreementMeters": MIN_AGREEMENT_M,
                         "minAgreementFraction": MIN_AGREEMENT_FRACTION,
                         "conflictPositionalErrorSearchBufferMeters": CONFLICT_POSITIONAL_ERROR_BUFFER_M,
                         "overlapDiagnosticAreaM2": OVERLAP_DIAGNOSTIC_AREA_M2}}


def compare(case: dict, inventory: dict, catalog: Catalog, neighbors: list[tuple[str, dict]],
            reference_cache: OrderedDict | None = None,
            official_names: dict[str, str] | None = None) -> dict:
    if inventory["sourcePdf"]["sha256"] != case["sourcePdfSha256"]:
        raise ValueError("Source inventory hash differs from pinned official case")
    case_id = case["id"]
    official_names = official_names or {case_id: case["nameAr"]}
    installed_pin = catalog.boundary_pin(case_id)
    rows = []
    blockers = []
    seen_geometries: list[tuple[str, Polygon]] = []
    scope_bboxes_wgs84: list[tuple[float, ...]] = []
    for page in inventory["pages"]:
        reference = _georef(page)
        if reference is None:
            blockers.append(f"page {page['pageIndex']}: zero or multiple fitted geospatial viewports")
            continue
        factor = reference.get("mapUnitsToMeters")
        if not reference.get("crsProjected") or not factor:
            blockers.append(f"page {page['pageIndex']}: projected metric CRS unavailable")
            continue
        if reference.get("qualityFlags") or reference.get("designCondition", math.inf) > 1e6:
            blockers.append(f"page {page['pageIndex']}: embedded control fit has quality flags")
            continue
        if reference.get("maxControlResidualMeters") is None or reference["maxControlResidualMeters"] > 1:
            blockers.append(f"page {page['pageIndex']}: embedded control residual exceeds one metre")
            continue
        crs = CRS.from_wkt(reference["crsWkt"])
        installed = catalog.geometry(case_id, crs)
        neighbor_checks = _prepare_reference_checks(neighbors, crs, factor, reference_cache)
        candidates = _closed_red_candidates(page, reference, case["nameAr"], official_names)
        candidates += _connected_candidates(page, reference, case["nameAr"], official_names)
        for candidate in candidates:
            geometry = candidate.pop("geometry")
            if not geometry.is_valid or geometry.area <= 0:
                rows.append({**candidate, "comparisonStatus": "unmeasured_invalid_candidate"})
                continue
            duplicate = next((key for key, earlier in seen_geometries
                              if abs(earlier.area - geometry.area) < .01
                              and earlier.equals(geometry)), None)
            if duplicate:
                candidate["duplicateGeometryOf"] = duplicate
            else:
                seen_geometries.append((candidate["key"], geometry))
            candidate["eligibleAsWholeTargetHypothesis"] = not candidate["confirmedMultipleOfficialAreasInside"]
            if candidate["confirmedMultipleOfficialAreasInside"]:
                candidate["scopeReason"] = "Multiple distinct pinned official area labels lie inside this face; measure only as a negative scope diagnostic."
            elif candidate["multipleDistinctRedLabelsInside"]:
                candidate["scopeReason"] = "Several red labels lie inside; inspect their map semantics before treating this as a whole imada."
            else:
                candidate["scopeReason"] = "No multiple-area label was established; scope remains unreviewed."
            metrics = _metrics(geometry, installed, factor)
            conflicts = _installed_sector_conflicts(case_id, geometry, installed, crs, factor, catalog)
            scope_bboxes_wgs84.append(conflicts["scopeBboxWgs84"])
            check = _independent_source_check(geometry, neighbor_checks, factor)
            measured = check["status"] == "cross_source_relative_consistency"
            rows.append({**candidate, "comparisonStatus": "measured_relative" if measured else "unmeasured_placement_unchecked",
                         "crsEpsg": crs.to_epsg(), "controlResidualMeters": reference["maxControlResidualMeters"],
                         "metrics": metrics, "installedSectorConflictCheck": conflicts,
                         "externalPlacementCheck": check,
                         "identityQualification": "Source-to-official association and map scope still require review."})
    rows.sort(key=lambda item: (item["confirmedMultipleOfficialAreasInside"],
                                not item["exactOfficialNameInRedLabel"],
                                not item["contextualOfficialNameInRedLabel"],
                                item["multipleDistinctRedLabelsInside"],
                                item.get("duplicateGeometryOf") is not None,
                                -round(item.get("metrics", {}).get("intersectionOverUnion", -1), 6),
                                item["method"] != "native_red_closed", item["key"]))
    for rank, row in enumerate(rows, 1):
        row["inspectionRank"] = rank
        row["rankingReason"] = "Scope flags first, then exact/contextual red label and installed similarity; rank does not establish official identity."
    exact_measured = [row for row in rows if row["exactOfficialNameInRedLabel"]
                      and row["comparisonStatus"] == "measured_relative"
                      and row["eligibleAsWholeTargetHypothesis"]
                      and not row.get("duplicateGeometryOf")]
    context_measured = [row for row in rows if row["contextualOfficialNameInRedLabel"]
                        and row["comparisonStatus"] == "measured_relative"
                        and row["eligibleAsWholeTargetHypothesis"]
                        and not row.get("duplicateGeometryOf")]
    mixed_scope_measured = any(row["confirmedMultipleOfficialAreasInside"] and row["comparisonStatus"] == "measured_relative"
                               and (row["exactOfficialNameInRedLabel"] or row["contextualOfficialNameInRedLabel"])
                               for row in rows)
    case_status = ("measured_candidate_ambiguity" if len(exact_measured) > 1 else
                   "measured_candidate_needs_identity_review" if len(exact_measured) == 1 else
                   "measured_contextual_candidate_ambiguity" if len(context_measured) > 1 else
                   "measured_contextual_candidate_needs_identity_review" if len(context_measured) == 1 else
                   "measured_mixed_scope_negative_diagnostic" if mixed_scope_measured else
                   "unmeasured_identity_or_placement")
    cache_inputs = _comparison_cache_inputs(case, inventory, catalog, neighbors, official_names,
                                            sorted(set(scope_bboxes_wgs84)))
    return {"schemaVersion": 2, "status": "READ_ONLY_CANDIDATE_COMPARISON",
            "caseMeasurementStatus": case_status, "caseId": case_id, "officialIdentityPin": case,
            "installedBoundaryPin": installed_pin, "sourcePdf": inventory["sourcePdf"],
            "sourceInventoryCacheKey": inventory["cacheKey"], "cacheInputs": cache_inputs,
            "cacheKey": _hash_json(cache_inputs), "candidateCount": len(rows),
            "distinctCandidateCount": sum(not row.get("duplicateGeometryOf") for row in rows),
            "candidates": rows,
            "blockers": blockers, "catalogProvenance": {"metadata": catalog.metadata_pin,
                                                        "binary": catalog.binary_pin},
            "qualification": "Metrics are diagnostic and never select or install a boundary. Case measurement credit requires identity and scope review; cross-source checks establish relative map consistency only."}


def _load_inputs(identity_map: Path, source_queue: Path, inventory_dir: Path) -> tuple[dict, dict, dict]:
    official = json.loads(identity_map.read_text(encoding="utf-8"))
    if official["sourceQueue"]["sha256"] != sha256(source_queue):
        raise ValueError("Identity map refers to a different source queue")
    queue = json.loads(source_queue.read_text(encoding="utf-8"))
    by_id = {row["id"]: row for row in official["cases"]}
    queued = {row["id"]: row for row in queue["cases"]}
    if len(by_id) != len(official["cases"]):
        raise ValueError("Duplicate official case identity")
    inventories = {}
    for case_id, case in by_id.items():
        pinned = [item for item in queued[case_id].get("cachedPdfs", [])
                  if item["sha256"] == case["sourcePdfSha256"]]
        if len(pinned) != 1 or sha256(Path(pinned[0]["file"])) != case["sourcePdfSha256"]:
            raise ValueError(f"Pinned saved PDF unavailable or ambiguous: {case_id}")
        path = inventory_dir / f"{case['sourcePdfSha256']}.json"
        inventory = json.loads(path.read_text(encoding="utf-8"))
        if inventory["sourcePdf"]["sha256"] != case["sourcePdfSha256"]:
            raise ValueError(f"Inventory and case source mismatch: {case_id}")
        inventories[case_id] = inventory
    return by_id, queued, inventories


def _reference_sources(case_id: str, cases: dict, inventories: dict,
                       catalog: Catalog) -> list[tuple[str, dict]]:
    # This nearest-eight limit only chooses separately georeferenced source
    # maps for a relative check. Installed-sector conflict checks have no cap.
    crs = CRS.from_epsg(32632)
    target = catalog.geometry(case_id, crs)
    governorate = catalog.governorate_id(case_id)
    distances = []
    for other_id in cases:
        if other_id != case_id and catalog.governorate_id(other_id) == governorate:
            distances.append((target.distance(catalog.geometry(other_id, crs)), other_id))
    distances.sort()
    return [(other_id, inventories[other_id]) for _, other_id in distances[:REFERENCE_SOURCE_COUNT]]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--identity-map", required=True, type=Path)
    parser.add_argument("--source-queue", required=True, type=Path)
    parser.add_argument("--inventory-dir", required=True, type=Path)
    parser.add_argument("--metadata", required=True, type=Path)
    parser.add_argument("--binary", required=True, type=Path)
    selection = parser.add_mutually_exclusive_group(required=True)
    selection.add_argument("--case-id", action="append", help="Repeat to compare a bounded set")
    selection.add_argument("--all", action="store_true", help="Compare every pinned cached identity")
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    cases, _, inventories = _load_inputs(args.identity_map, args.source_queue, args.inventory_dir)
    queued = json.loads(args.source_queue.read_text(encoding="utf-8"))
    official_names = {row["id"]: row["nameAr"] for row in queued["cases"]}
    catalog = Catalog(args.metadata, args.binary)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    results = []
    reference_cache: OrderedDict = OrderedDict()
    for case_id in (list(cases) if args.all else args.case_id):
        neighbors = _reference_sources(case_id, cases, inventories, catalog)
        # Versioned names leave every v3 saved report in place as history.
        output = args.output_dir / (case_id.replace(":", "_") + f"-{VERSION}.json")
        if output.exists():
            report = json.loads(output.read_text(encoding="utf-8"))
            old_inputs = report.get("cacheInputs", {})
            if old_inputs.get("version") != VERSION or "candidateScopeBboxesWgs84" not in old_inputs:
                raise FileExistsError(f"Existing comparison has a different version: {output}")
            cache_key = _hash_json(_comparison_cache_inputs(
                cases[case_id], inventories[case_id], catalog, neighbors, official_names,
                old_inputs["candidateScopeBboxesWgs84"]))
            if report.get("cacheKey") != cache_key:
                raise FileExistsError(f"Existing comparison has a different cache key: {output}")
            state = "cache_hit"
        else:
            report = compare(cases[case_id], inventories[case_id], catalog, neighbors, reference_cache, official_names)
            with output.open("x", encoding="utf-8") as target:
                json.dump(report, target, ensure_ascii=False, indent=2)
                target.write("\n")
            state = "written"
        results.append({"caseId": case_id, "status": state, "file": str(output),
                        "candidateCount": report["candidateCount"],
                        "distinctCandidateCount": report["distinctCandidateCount"],
                        "measurementStatus": report["caseMeasurementStatus"]})
    if args.all:
        summary = {"schemaVersion": 2, "status": "READ_ONLY_DIAGNOSTIC_COMPARISON_BATCH",
                   "methodVersion": VERSION, "count": len(results),
                   "cases": [{key: row[key] for key in ("caseId", "file", "candidateCount",
                                                       "distinctCandidateCount", "measurementStatus")}
                             for row in results],
                   "qualification": "No candidate is selected or installed; relative source checks do not establish external absolute accuracy."}
        summary_file = args.output_dir / f"summary-{VERSION}.json"
        if summary_file.exists():
            existing = json.loads(summary_file.read_text(encoding="utf-8"))
            if existing != summary:
                raise FileExistsError(f"Existing batch summary differs: {summary_file}")
        else:
            with summary_file.open("x", encoding="utf-8") as target:
                json.dump(summary, target, ensure_ascii=False, indent=2)
                target.write("\n")
    print(json.dumps(results, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
