"""Offline precomputation of registered ISIE PDF neighbor groups.

This module consumes cached ``isie_pdf_inventory.inspect`` JSON and sibling
PDF manifests. It never fetches data or reads installed OSM/app geometry. Its
output is source-coverage and comparison evidence, not geographic acceptance.

Typical use::

    python -m scripts.locality_automation.isie_neighbor_groups_v2 \
        --inventory-dir work/isie-execution-20260926/pdf-inventories \
        --out work/isie-execution-20260926/neighbor-groups.json

Use repeatable ``--only-key`` to limit target groups/pair checks while still
using all discovered registered faces as potential neighbors. ``--render-key``
writes a review overlay next to the JSON report.
"""

from __future__ import annotations

import argparse
from collections import defaultdict
import hashlib
import json
import math
from pathlib import Path
import re
import unicodedata
from typing import Any

import numpy as np
from pyproj import CRS, Transformer
from shapely.geometry import LineString, Point, Polygon, box
from shapely.ops import polygonize, transform as transform_geometry, unary_union
from shapely.strtree import STRtree


VERSION = "isie-neighbor-groups-v2"
COMMON_CRS = "EPSG:32632"
DISCOVERY_BAND_M = 100.0
# Reporting floor only. A long, narrow street-width seam can exceed this area
# without being a substantive territorial conflict.
OVERLAP_REVIEW_FLOOR_M2 = 50.0
SEAM_TOLERANCES_M = (0.5, 1.0, 2.0, 5.0)
REGISTRATION_MAX_RESIDUAL_M = 1.0
REGISTRATION_MAX_LOO_RESIDUAL_M = 1.5
_ARABIC_TRANSLATIONS = str.maketrans({
    "أ": "ا", "إ": "ا", "آ": "ا", "ٱ": "ا", "ى": "ي", "ی": "ي", "ک": "ك", "ـ": "",
})


def _norm(value: str) -> str:
    value = unicodedata.normalize("NFKC", value).translate(_ARABIC_TRANSLATIONS)
    chars = []
    for char in value:
        if unicodedata.category(char) in {"Mn", "Me", "Cf"}:
            continue
        chars.append(char if char.isalnum() else " ")
    return " ".join("".join(chars).split())


def _json_hash(value: Any) -> str:
    raw = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def _load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8-sig") as stream:
        return json.load(stream)


def _manifest_rows(path: Path) -> list[dict[str, Any]]:
    try:
        value = _load_json(path)
    except (OSError, ValueError, TypeError):
        return []
    if isinstance(value, dict):
        for key in ("sources", "items", "entries", "maps"):
            if isinstance(value.get(key), list):
                value = value[key]
                break
        else:
            value = [value]
    return [row for row in value if isinstance(row, dict)] if isinstance(value, list) else []


def _identity_tokens(row: dict[str, Any]) -> list[str]:
    values: list[str] = []
    for item in row.get("sourceOsmIds", []) or []:
        if item:
            token = str(item).strip().lower()
            values.append(token if token.startswith("osm:") else "osm:" + token)
    for key in ("url", "key"):
        if row.get(key):
            value = str(row[key]).strip()
            values.append(("url:" if key == "url" else "key:") + value.lower())
    if row.get("code") is not None and row.get("parent"):
        values.append("code-parent:" + _norm(str(row["code"])) + "|" + _norm(str(row["parent"])))
    if not values:
        values.append("manifest-row:" + _norm(str(row.get("file") or row.get("name") or "unknown")))
    return sorted(set(values))


def _source_roots(inventory_dir: Path, explicit: list[Path]) -> list[Path]:
    if explicit:
        return sorted({path.resolve() for path in explicit})
    # Standard cache layout: work/isie-execution-*/pdf-inventories beside
    # work/locality-automation/batches. This remains a local-only search.
    work = inventory_dir.resolve().parent.parent
    inferred = work / "locality-automation" / "batches"
    return [inferred] if inferred.is_dir() else []


def _discover_sources(inventory_dir: Path, roots: list[Path]) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    by_path: dict[str, dict[str, Any]] = {}
    issues: list[dict[str, Any]] = []
    for root in roots:
        if not root.exists():
            issues.append({"code": "source_root_missing", "path": str(root)})
            continue
        for pdf in sorted(root.rglob("*.pdf"), key=lambda p: str(p).lower()):
            resolved = pdf.resolve()
            path_key = str(resolved).casefold()
            if path_key not in by_path:
                by_path[path_key] = {"path": resolved, "refs": [], "actualSha256": None}
            manifest_path = pdf.parent / "manifest.json"
            rows = _manifest_rows(manifest_path) if manifest_path.is_file() else []
            matched = [row for row in rows if not row.get("file") or Path(str(row["file"])).name.casefold() == pdf.name.casefold()]
            if not matched and rows:
                # Batch-level manifests often identify the file by basename or key.
                matched = [row for row in rows if str(row.get("key", "")).casefold() in pdf.stem.casefold()]
            for row in matched:
                by_path[path_key]["refs"].append({"manifestPath": str(manifest_path.resolve()), "row": row})
            if not matched:
                by_path[path_key]["refs"].append({"manifestPath": str(manifest_path.resolve()) if manifest_path.exists() else None,
                                                     "row": {"key": pdf.stem, "file": pdf.name,
                                                             "manifestMissing": not manifest_path.exists()}})
    # Inventory source paths are a useful local fallback if their batch root was
    # moved or not explicitly supplied.
    for inv_path in sorted(inventory_dir.rglob("*.json"), key=lambda p: str(p).lower()):
        try:
            inv = _load_json(inv_path)
            source = inv.get("sourcePdf", {}) if isinstance(inv, dict) else {}
            pdf_value = source.get("file")
            if not pdf_value:
                continue
            pdf = Path(pdf_value)
            if not pdf.is_file():
                continue
            key = str(pdf.resolve()).casefold()
            if key not in by_path:
                manifest_path = pdf.parent / "manifest.json"
                rows = _manifest_rows(manifest_path) if manifest_path.is_file() else []
                by_path[key] = {"path": pdf.resolve(), "refs": [
                    {"manifestPath": str(manifest_path.resolve()) if manifest_path.exists() else None, "row": row}
                    for row in rows if not row.get("file") or Path(str(row["file"])).name.casefold() == pdf.name.casefold()
                ], "actualSha256": None}
                if not by_path[key]["refs"]:
                    by_path[key]["refs"] = [{"manifestPath": None, "row": {"key": pdf.stem, "file": pdf.name,
                                                                             "manifestMissing": True}}]
        except (OSError, ValueError, TypeError):
            continue
    return list(by_path.values()), issues


def _inventory_index(inventory_dir: Path) -> tuple[dict[str, list[dict[str, Any]]], list[dict[str, Any]]]:
    index: dict[str, list[dict[str, Any]]] = defaultdict(list)
    issues: list[dict[str, Any]] = []
    if not inventory_dir.is_dir():
        return {}, [{"code": "inventory_dir_missing", "path": str(inventory_dir)}]
    for path in sorted(inventory_dir.rglob("*.json"), key=lambda p: str(p).lower()):
        # The cached-PDF driver also writes a directory-level manifest here.
        # Inventory cache entries are named by their 64-hex PDF SHA.
        if not re.fullmatch(r"[0-9a-fA-F]{64}\.json", path.name):
            continue
        try:
            value = _load_json(path)
            source = value.get("sourcePdf", {}) if isinstance(value, dict) else {}
            digest = str(source.get("sha256", "")).lower()
            if not re.fullmatch(r"[0-9a-f]{64}", digest):
                issues.append({"code": "inventory_missing_pdf_hash", "inventoryPath": str(path.resolve())})
                continue
            index[digest].append({"path": path.resolve(), "value": value, "documentHash": _json_hash(value)})
        except (OSError, ValueError, TypeError) as exc:
            issues.append({"code": "inventory_unreadable", "inventoryPath": str(path.resolve()), "detail": str(exc)})
    return index, issues


def _aggregate_sources(copies: list[dict[str, Any]], inventory_index: dict[str, list[dict[str, Any]]]) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    by_sha: dict[str, dict[str, Any]] = {}
    issues: list[dict[str, Any]] = []
    for copy in copies:
        pdf = copy["path"]
        try:
            actual = _sha256(pdf)
            copy["actualSha256"] = actual
            size = pdf.stat().st_size
        except OSError as exc:
            issues.append({"code": "cached_pdf_unreadable", "path": str(pdf), "detail": str(exc)})
            actual, size = "", None
        row_refs = copy["refs"] or [{"manifestPath": None, "row": {"key": pdf.stem, "file": pdf.name}}]
        for ref in row_refs:
            declared = str(ref["row"].get("sha256") or "").lower()
            if declared and actual and declared != actual:
                issues.append({"code": "manifest_pdf_hash_mismatch", "path": str(pdf),
                               "manifestPath": ref.get("manifestPath"), "declaredSha256": declared,
                               "actualSha256": actual})
        sha_key = actual or "unreadable:" + str(pdf).casefold()
        source = by_sha.setdefault(sha_key, {"sourceSha256": actual or None, "pdfBytes": size,
                                             "pdfCopies": [], "manifestRefs": [], "identityTokens": set(),
                                             "metadataVariants": {}, "paths": set(), "cachedPdfCopyCount": 0})
        source["pdfCopies"].append(str(pdf))
        if str(pdf) not in source["paths"] and actual:
            source["cachedPdfCopyCount"] += 1
        source["paths"].add(str(pdf))
        source["hasCachedPdfCopy"] = bool(source.get("hasCachedPdfCopy") or actual)
        for ref in row_refs:
            row = ref["row"]
            variant = {"key": row.get("key"), "name": row.get("name"), "parent": row.get("parent"),
                       "code": row.get("code"), "url": row.get("url"),
                       "sourceOsmIds": sorted(str(v) for v in (row.get("sourceOsmIds") or [])),
                       "declaredPdfSha256": row.get("sha256"), "reusedCache": row.get("reusedCache"),
                       "manifestPath": ref.get("manifestPath"), "pdfPath": str(pdf)}
            source["manifestRefs"].append(variant)
            source["identityTokens"].update(_identity_tokens(row))
            signature = _json_hash({k: variant.get(k) for k in ("name", "parent", "code")})
            source["metadataVariants"][signature] = {k: variant.get(k) for k in ("name", "parent", "code")}
    # Inventory-only records make stale/moved cache entries visible as well.
    known_shas = {key for key in by_sha if re.fullmatch(r"[0-9a-f]{64}", key)}
    for digest, documents in inventory_index.items():
        if digest in known_shas:
            continue
        first = documents[0]["value"]
        pdf_path = str(first.get("sourcePdf", {}).get("file") or "")
        by_sha[digest] = {"sourceSha256": digest, "pdfBytes": first.get("sourcePdf", {}).get("bytes"),
                          "pdfCopies": [], "inventorySourcePdfPath": pdf_path, "manifestRefs": [],
                          "identityTokens": set(), "metadataVariants": {}, "paths": set(),
                          "cachedPdfCopyCount": 0, "hasCachedPdfCopy": False, "inventoryOnly": True}
    # A source identity with multiple cached byte versions is ambiguous. Keep
    # every version/provenance record, but disallow either from face comparison.
    identity_versions: dict[str, set[str]] = defaultdict(set)
    for digest, source in by_sha.items():
        for token in source["identityTokens"]:
            identity_versions[token].add(digest)
    conflicted = {token for token, versions in identity_versions.items() if len(versions) > 1}
    for digest, source in by_sha.items():
        source["identityTokens"] = sorted(source["identityTokens"])
        source["pdfCopies"] = sorted(set(source["pdfCopies"]))
        source["manifestRefs"] = sorted(source["manifestRefs"], key=lambda r: (str(r.get("key")), str(r.get("pdfPath")), str(r.get("manifestPath"))))
        source["metadataVariants"] = [source["metadataVariants"][key] for key in sorted(source["metadataVariants"])]
        source["sourceKey"] = "sha256:" + digest if source["sourceSha256"] else "unreadable:" + digest
        source["versionConflictIdentities"] = sorted(set(source["identityTokens"]) & conflicted)
        source["versionConflict"] = bool(source["versionConflictIdentities"])
        if source["versionConflict"]:
            issues.append({"code": "ambiguous_source_version_conflict", "sourceKey": "sha256:" + digest,
                           "identityTokens": source["versionConflictIdentities"],
                           "manifestRefs": source["manifestRefs"]})
        documents = inventory_index.get(source["sourceSha256"], []) if source["sourceSha256"] else []
        doc_hashes = {item["documentHash"] for item in documents}
        source["inventoryPaths"] = sorted(str(item["path"]) for item in documents)
        source["inventoryAmbiguous"] = len(doc_hashes) > 1
        source["inventory"] = sorted(documents, key=lambda item: str(item["path"]))[0]["value"] if documents and len(doc_hashes) == 1 else None
        if source["inventoryAmbiguous"]:
            issues.append({"code": "ambiguous_inventory_versions", "sourceKey": source["sourceKey"],
                           "inventoryPaths": source["inventoryPaths"]})
    result = [by_sha[key] for key in sorted(by_sha)]
    return result, issues


def _source_metadata(source: dict[str, Any]) -> tuple[str | None, str | None, list[str]]:
    variants = source["metadataVariants"]
    names = sorted({str(v["name"]).strip() for v in variants if v.get("name")})
    parents = sorted({str(v["parent"]).strip() for v in variants if v.get("parent")})
    codes = sorted({str(v["code"]).strip() for v in variants if v.get("code") is not None})
    source["names"] = names
    source["delegations"] = parents
    source["officialCodes"] = codes
    source["metadataAmbiguous"] = len({(v.get("name"), v.get("parent"), v.get("code")) for v in variants}) > 1
    source["name"] = names[0] if len(names) == 1 else None
    source["delegation"] = parents[0] if len(parents) == 1 else None
    return source["name"], source["delegation"], codes


def _label_matches(text: str, title: str, parent: str | None) -> tuple[bool, str | None]:
    label, own = _norm(text), _norm(title)
    if label and label == own:
        return True, "exact_title"
    # Some ISIE PDFs expose Arabic glyph runs in visual (right-to-left) order
    # to text extraction, reversing the characters of a title-only label.
    if label and label[::-1] == own:
        return True, "exact_title_reversed_glyph_order"
    parent_n = _norm(parent or "")
    if label and own and parent_n:
        forms = {own + " " + parent_n, parent_n + " " + own}
        if label in forms:
            return True, "parent_qualified_exact"
        if label[::-1] in forms:
            return True, "parent_qualified_reversed_glyph_order"
    return False, None


def _fit_affine(points: np.ndarray, values: np.ndarray) -> tuple[np.ndarray, float, float]:
    design = np.column_stack((points, np.ones(len(points), dtype=float)))
    affine, _, rank, _ = np.linalg.lstsq(design, values, rcond=None)
    if rank != 3 or not np.isfinite(affine).all():
        raise ValueError("control points do not determine a finite two-dimensional affine transform")
    cond = float(np.linalg.cond(design))
    residual = np.linalg.norm(design @ affine - values, axis=1)
    return affine, cond, float(np.max(residual))


def _registration(georef: dict[str, Any]) -> tuple[dict[str, Any], np.ndarray, CRS, Transformer]:
    if georef.get("status") != "fitted":
        raise ValueError("embedded georeference is not fitted")
    source_crs = CRS.from_wkt(str(georef["crsWkt"]))
    if not source_crs.is_projected:
        raise ValueError("embedded map CRS is not projected")
    if not source_crs.geodetic_crs:
        raise ValueError("embedded projected CRS has no geodetic CRS")
    page = np.asarray(georef["pageControls"], dtype=float)
    latlon = np.asarray(georef["geographicControlsLatLon"], dtype=float)
    if page.ndim != 2 or page.shape[1] != 2 or latlon.shape != page.shape or len(page) < 4:
        raise ValueError("at least four paired page/geographic controls are required")
    if not np.isfinite(page).all() or not np.isfinite(latlon).all():
        raise ValueError("embedded control coordinates are not finite")
    to_common = Transformer.from_crs(source_crs.geodetic_crs, CRS.from_user_input(COMMON_CRS), always_xy=True)
    control_xy = np.asarray([to_common.transform(float(lon), float(lat)) for lat, lon in latlon], dtype=float)
    affine, condition, max_fit = _fit_affine(page, control_xy)
    loo_max = 0.0
    loo_values = []
    for index in range(len(page)):
        keep = np.arange(len(page)) != index
        loo_affine, _, _ = _fit_affine(page[keep], control_xy[keep])
        estimate = np.append(page[index], 1.0) @ loo_affine
        error = float(np.linalg.norm(estimate - control_xy[index]))
        loo_values.append(error)
        loo_max = max(loo_max, error)
    source_affine = np.asarray(georef.get("affineMapUnitsFromPagePoints"), dtype=float)
    if source_affine.shape != (3, 2) or not np.isfinite(source_affine).all():
        raise ValueError("inventory affine map transform is missing or malformed")
    map_xy = np.column_stack((page, np.ones(len(page)))) @ source_affine
    to_common_map = Transformer.from_crs(source_crs, CRS.from_user_input(COMMON_CRS), always_xy=True)
    projected_map_controls = np.asarray([to_common_map.transform(float(x), float(y)) for x, y in map_xy])
    consistency = np.linalg.norm(projected_map_controls - control_xy, axis=1)
    max_consistency = float(np.max(consistency))
    report = {"status": "validated" if max_fit <= REGISTRATION_MAX_RESIDUAL_M
              and loo_max <= REGISTRATION_MAX_LOO_RESIDUAL_M
              and max_consistency <= REGISTRATION_MAX_RESIDUAL_M and condition <= 1e6 else "rejected",
              "commonCrs": COMMON_CRS, "embeddedCrsEpsg": source_crs.to_epsg(),
              "controlCount": int(len(page)), "affineControlFitMaxResidualM": max_fit,
              "leaveOneOutMaxResidualM": loo_max, "leaveOneOutResidualsM": loo_values,
              "inventoryMapControlConsistencyMaxM": max_consistency, "controlDesignCondition": condition,
              "thresholds": {"fitMaxResidualM": REGISTRATION_MAX_RESIDUAL_M,
                             "leaveOneOutMaxResidualM": REGISTRATION_MAX_LOO_RESIDUAL_M,
                             "inventoryMapConsistencyMaxM": REGISTRATION_MAX_RESIDUAL_M,
                             "maxCondition": 1e6},
              "qualification": "Internal consistency of embedded controls only; not external survey accuracy."}
    return report, source_affine, source_crs, to_common_map


def _map_points(points: list[list[float]] | list[tuple[float, float]], affine: np.ndarray,
                to_common: Transformer) -> list[tuple[float, float]]:
    if not points:
        return []
    coords = np.asarray(points, dtype=float)
    mapped = np.column_stack((coords, np.ones(len(coords)))) @ affine
    x, y = to_common.transform(mapped[:, 0], mapped[:, 1])
    return [(float(a), float(b)) for a, b in zip(x, y)]


def _linework_polygons(page: dict[str, Any]) -> list[Polygon]:
    lines = []
    for path in page.get("nativePaths", []):
        if (path.get("layer") != "circonscription_isie2023" or path.get("strokeFamily") != "red"
                or not path.get("visibleStroke") or path.get("unsupportedKinds")):
            continue
        for run in path.get("runs", []):
            coords = run.get("nativePagePoints")
            if coords and len(coords) >= 2:
                line = LineString(coords)
                if not line.is_empty and line.length > 0:
                    lines.append(line)
    if not lines:
        return []
    merged = unary_union(lines)
    return [poly for poly in polygonize(merged) if poly.is_valid and poly.area > 0]


def _prepare_source(source: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any] | None]:
    name, parent, _ = _source_metadata(source)
    result: dict[str, Any] = {"sourceKey": source["sourceKey"], "sourcePdfSha256": source["sourceSha256"],
                              "pdfBytes": source["pdfBytes"], "pdfCopies": source["pdfCopies"],
                              "inventorySourcePdfPath": source.get("inventorySourcePdfPath"),
                              "manifestRefs": source["manifestRefs"], "identityTokens": source["identityTokens"],
                              "names": source["names"], "delegations": source["delegations"],
                              "officialCodes": source["officialCodes"], "inventoryPaths": source["inventoryPaths"],
                              "inventorySha256": None, "inventoryDocumentSha256": None,
                              "status": "unresolved", "issues": [], "registration": None,
                              "face": None, "labelConflicts": [], "inventoryFileSha256": None}
    if source.get("inventory") is None:
        result["issues"].append("ambiguous_inventory" if source["inventoryAmbiguous"] else "inventory_missing")
        return result, None
    inv = source["inventory"]
    result["inventorySha256"] = inv.get("sourcePdf", {}).get("sha256")
    result["inventoryDocumentSha256"] = _json_hash(inv)
    if source["inventoryPaths"]:
        try:
            result["inventoryFileSha256"] = _sha256(Path(source["inventoryPaths"][0]))
        except OSError:
            result["issues"].append("inventory_file_unreadable")
    if source["sourceSha256"] and result["inventorySha256"] != source["sourceSha256"]:
        result["issues"].append("inventory_pdf_hash_mismatch")
        return result, None
    if source["versionConflict"]:
        result["issues"].append("ambiguous_source_version")
        return result, None
    if source["metadataAmbiguous"]:
        result["issues"].append("ambiguous_manifest_identity_metadata")
        return result, None
    if not name:
        result["issues"].append("manifest_title_missing")
        return result, None
    pages = inv.get("pages") or []
    found: list[tuple[int, dict[str, Any], list[Polygon], dict[str, Any]]] = []
    registration_errors = []
    for page in pages:
        try:
            georefs = [g for g in page.get("georeferences", []) if g.get("status") == "fitted"]
            if not georefs:
                continue
            # Inspect each fitted viewport independently. More than one valid
            # face/page is an ambiguity, never a first-match selection.
            for georef in georefs:
                reg, affine, source_crs, transformer = _registration(georef)
                if reg["status"] != "validated":
                    registration_errors.append(reg)
                    continue
                polygons = _linework_polygons(page)
                labels = page.get("labeledAreaLeads") or []
                exact = [(label, "exact_title") for label in labels if _label_matches(str(label.get("text", "")), name, None)[0]]
                qualified = [(label, "parent_qualified_exact") for label in labels
                             if not exact and _label_matches(str(label.get("text", "")), name, parent)[0]]
                selected_labels = exact or qualified
                label_polys = []
                for label, match_kind in selected_labels:
                    center = label.get("centerPagePoints")
                    if not center or len(center) != 2:
                        continue
                    point = Point(center)
                    covering = [poly for poly in polygons if poly.covers(point)]
                    for poly in covering:
                        label_polys.append((poly, label, match_kind))
                # The same locality title may be printed more than once on a
                # sheet (e.g. inside the face and again in a page heading).
                # Prefer in-map interior anchors, then require a unique face,
                # rather than rejecting identical title labels as ambiguity.
                interior = [item for item in label_polys if float(item[1]["centerPagePoints"][1]) >= 100.0]
                if interior:
                    label_polys = interior
                unique_polys: dict[bytes, tuple[Polygon, dict[str, Any], str, float]] = {}
                for poly, label, match_kind in label_polys:
                    current = unique_polys.get(poly.wkb)
                    depth = poly.boundary.distance(Point(label["centerPagePoints"]))
                    if current is None or depth > current[3]:
                        unique_polys[poly.wkb] = (poly, label, match_kind, depth)
                if label_polys and len(unique_polys) == 1:
                    poly, label, match_kind, _ = next(iter(unique_polys.values()))
                    found.append((int(page.get("pageIndex", 0)), {"label": label, "matchKind": match_kind,
                                                                  "affine": affine, "sourceCrs": source_crs,
                                                                  "transformer": transformer, "georef": georef,
                                                                  "registration": reg}, [poly], page))
        except (KeyError, ValueError, TypeError, np.linalg.LinAlgError) as exc:
            registration_errors.append({"status": "rejected", "detail": str(exc)})
    if len(found) != 1:
        if not found:
            result["issues"].append("own_title_red_face_missing_or_registration_rejected")
        else:
            result["issues"].append("ambiguous_own_title_face_across_pages")
        result["registration"] = registration_errors[0] if registration_errors else None
        result["registrationIssues"] = registration_errors
        return result, None
    page_index, info, polys, page = found[0]
    poly = polys[0]
    affine, transformer = info["affine"], info["transformer"]
    common_coords = _map_points(list(poly.exterior.coords), affine, transformer)
    common_holes = [_map_points(list(ring.coords), affine, transformer) for ring in poly.interiors]
    face = Polygon(common_coords, common_holes)
    if not face.is_valid:
        face = face.buffer(0)
    if face.is_empty or not face.is_valid or face.area <= 0:
        result["issues"].append("mapped_own_title_face_invalid")
        return result, None
    georef = info["georef"]
    page_controls = georef.get("pageControls", [])
    frame_coords = _map_points(page_controls, affine, transformer)
    frame = Polygon(frame_coords)
    if not frame.is_valid:
        frame = frame.buffer(0)
    if frame.is_empty or not frame.is_valid:
        frame = None
    own_label = info["label"]
    own_point_xy = _map_points([own_label.get("centerPagePoints")], affine, transformer)[0]
    all_labels = []
    own_norm = _norm(name)
    for label in page.get("labeledAreaLeads", []) or []:
        center = label.get("centerPagePoints")
        if not center:
            continue
        mapped_point = _map_points([center], affine, transformer)[0]
        label_point = Point(mapped_point)
        matches_own, _ = _label_matches(str(label.get("text", "")), name, parent)
        all_labels.append({"text": str(label.get("text", "")), "normalized": _norm(str(label.get("text", ""))),
                           "centerCommonMeters": [mapped_point[0], mapped_point[1]],
                           "insideOwnFace": bool(face.covers(label_point)),
                           "distanceToOwnBoundaryM": float(face.boundary.distance(label_point)),
                           "matchesOwnTitle": matches_own})
    conflicts = [label for label in all_labels if not label["matchesOwnTitle"] and label["insideOwnFace"]]
    for label in conflicts:
        matching_keys = []
        for other in []:  # filled after all source titles are indexed by the caller
            matching_keys.append(other)
        label["matchingSourceKeys"] = matching_keys
    result.update({"status": "registered_own_title_face", "registration": info["registration"],
                   "face": {"pageIndex": page_index, "label": str(own_label.get("text", "")),
                            "labelMatch": info["matchKind"], "labelCenterCommonMeters": list(own_point_xy),
                            "areaSquareMeters": float(face.area), "perimeterMeters": float(face.length),
                            "boundsMeters": [float(v) for v in face.bounds], "geometrySha256": hashlib.sha256(face.wkb).hexdigest()},
                   "foreignRedLabelsInsideOwnFace": conflicts})
    candidate = {"sourceKey": source["sourceKey"], "name": name, "delegation": parent,
                 "officialCodes": source["officialCodes"], "face": face, "frame": frame,
                 "labels": all_labels, "record": result, "inventory": inv}
    return result, candidate


def _source_title_index(sources: list[dict[str, Any]]) -> dict[str, list[str]]:
    index: dict[str, list[str]] = defaultdict(list)
    for source in sources:
        name = source.get("name")
        if name:
            index[_norm(name)].append(source["sourceKey"])
    return {key: sorted(set(value)) for key, value in index.items()}


def _set_label_claims(candidates: list[dict[str, Any]], title_index: dict[str, list[str]]) -> None:
    for candidate in candidates:
        conflicts = candidate["record"].get("foreignRedLabelsInsideOwnFace", [])
        for label in conflicts:
            label["matchingSourceKeys"] = sorted(set(title_index.get(label["normalized"], [])
                                                       + title_index.get(label["normalized"][::-1], [])))


def _tree_query_indices(tree: STRtree, query_geom: Any, geoms: list[Any], id_map: dict[int, list[int]], wkb_map: dict[bytes, list[int]]) -> list[int]:
    result = tree.query(query_geom)
    indices = []
    for item in result:
        if isinstance(item, (int, np.integer)):
            indices.append(int(item))
        else:
            matches = id_map.get(id(item)) or wkb_map.get(item.wkb, [])
            indices.extend(matches)
    return sorted(set(indices))


def _near_metrics(boundary_a: Any, boundary_b: Any) -> dict[float, tuple[float, float]]:
    # Directional boundary-within-buffer lengths are the standard seam metric.
    return {tol: (float(boundary_a.intersection(boundary_b.buffer(tol)).length),
                  float(boundary_b.intersection(boundary_a.buffer(tol)).length))
            for tol in SEAM_TOLERANCES_M}


def _label_pair_diagnostics(a: dict[str, Any], b: dict[str, Any]) -> list[dict[str, Any]]:
    found = []
    for label_owner, other, direction in ((a, b, "a_pdf_label_matching_b_name"),
                                          (b, a, "b_pdf_label_matching_a_name")):
        for label in label_owner["labels"]:
            matches, _ = _label_matches(label["text"], other["name"], other.get("delegation"))
            if not matches:
                continue
            point = Point(label["centerCommonMeters"])
            found.append({"direction": direction, "labelText": label["text"],
                          "labelPdfSourceKey": label_owner["sourceKey"],
                          "matchedOwnTitleSourceKey": other["sourceKey"],
                          "labelCenterCommonMeters": label["centerCommonMeters"],
                          "insideLabelOwnersOwnFace": bool(label["insideOwnFace"]),
                          "insideOtherOwnTitleFace": bool(other["face"].covers(point)),
                          "distanceToLabelOwnersFaceBoundaryM": label["distanceToOwnBoundaryM"],
                          "distanceToOtherFaceBoundaryM": float(other["face"].boundary.distance(point)),
                          "diagnosticOnly": True})
    return found


def _pair_record(a: dict[str, Any], b: dict[str, Any]) -> dict[str, Any]:
    ga, gb = a["face"], b["face"]
    raw_overlap = float(ga.intersection(gb).area)
    union_area = float(ga.area + gb.area - raw_overlap)
    face_iou = raw_overlap / union_area if union_area > 0 else 0.0
    needs_shape_review = raw_overlap >= OVERLAP_REVIEW_FLOOR_M2
    near = {}
    reciprocal_tolerances = []
    one_sided_tolerances = []
    full_metrics = _near_metrics(ga.boundary, gb.boundary)
    for tol in SEAM_TOLERANCES_M:
        aa, bb = full_metrics[tol]
        near[str(tol)] = {"aBoundaryNearBLengthM": aa, "bBoundaryNearALengthM": bb,
                          "reciprocalMinimumLengthM": min(aa, bb)}
        if aa > 0 and bb > 0:
            reciprocal_tolerances.append(tol)
        elif aa > 0 or bb > 0:
            one_sided_tolerances.append(tol)
    reciprocal_length_1m = near["1.0"]["reciprocalMinimumLengthM"]
    reciprocal_length_5m = near["5.0"]["reciprocalMinimumLengthM"]
    if max(reciprocal_length_1m, reciprocal_length_5m) >= 20.0:
        evidence = "reciprocal_seam_candidate_partial"
        first_reciprocal = min(reciprocal_tolerances)
    elif reciprocal_tolerances:
        evidence = "short_reciprocal_junction"
        first_reciprocal = min(reciprocal_tolerances)
    else:
        evidence = "near_unmatched"
        first_reciprocal = None
    identity_a = set(a["record"].get("identityTokens", []))
    identity_b = set(b["record"].get("identityTokens", []))
    distinct_source_identities = bool(identity_a and identity_b and identity_a.isdisjoint(identity_b))
    if distinct_source_identities and face_iou >= 0.98:
        evidence = "near_duplicate_own_title_faces"
    common_diag: dict[str, Any] = {"status": "unavailable"}
    if a["frame"] is not None and b["frame"] is not None:
        try:
            common_frame = a["frame"].intersection(b["frame"])
            if not common_frame.is_empty and common_frame.area > 0:
                ca, cb = ga.intersection(common_frame), gb.intersection(common_frame)
                ba = ga.boundary.intersection(common_frame)
                bb = gb.boundary.intersection(common_frame)
                clip_a = float(ca.boundary.intersection(common_frame.boundary.buffer(1e-6)).length)
                clip_b = float(cb.boundary.intersection(common_frame.boundary.buffer(1e-6)).length)
                common_metrics = _near_metrics(ba, bb)
                common_near = {str(tol): {"aSourceBoundaryNearBLengthM": common_metrics[tol][0],
                                          "bSourceBoundaryNearALengthM": common_metrics[tol][1]}
                               for tol in SEAM_TOLERANCES_M}
                common_diag = {"status": "computed", "commonFrameAreaSquareMeters": float(common_frame.area),
                               "faceAAreaWithinCommonFrameM2": float(ca.area),
                               "faceBAreaWithinCommonFrameM2": float(cb.area),
                               "overlapWithinCommonFrameM2": float(ca.intersection(cb).area),
                               "aBoundaryLengthWithinCommonFrameM": float(ba.length),
                               "bBoundaryLengthWithinCommonFrameM": float(bb.length),
                               "aClippedPolygonBoundaryCoincidentWithFrameM": clip_a,
                               "bClippedPolygonBoundaryCoincidentWithFrameM": clip_b,
                               "reciprocalBoundaryNearM": common_near,
                               "qualification": "Frame-coincident closures are diagnostic and excluded from source-boundary near lengths."}
            else:
                common_diag = {"status": "no_common_frame_overlap"}
        except Exception as exc:
            common_diag = {"status": "error", "detail": str(exc)}
    return {"pairKey": "|".join(sorted((a["sourceKey"], b["sourceKey"]))),
            "sourceA": a["sourceKey"], "sourceB": b["sourceKey"],
            "nameA": a["name"], "delegationA": a.get("delegation"), "officialCodesA": a.get("officialCodes", []),
            "nameB": b["name"], "delegationB": b.get("delegation"), "officialCodesB": b.get("officialCodes", []),
            "faceDistanceMeters": float(ga.distance(gb)), "discoveryBandMeters": DISCOVERY_BAND_M,
            "pairEvidenceClass": evidence, "firstReciprocalToleranceMeters": first_reciprocal,
            "reciprocalBoundaryMetrics": near, "oneSidedToleranceMeters": one_sided_tolerances,
            "reciprocalSeamReportingThresholdMeters": 20.0,
            "reciprocalBoundaryCoverageFractionsAt1m": {
                "aBoundaryNearBOverAFacePerimeter": near["1.0"]["aBoundaryNearBLengthM"] / ga.length if ga.length else None,
                "bBoundaryNearAOverBFacePerimeter": near["1.0"]["bBoundaryNearALengthM"] / gb.length if gb.length else None},
            "exactSharedBoundaryLengthMeters": float(ga.boundary.intersection(gb.boundary).length),
            "overlapSquareMeters": raw_overlap,
            "faceIntersectionOverUnion": face_iou,
            "overlapReview": {"reportingFloorSquareMeters": OVERLAP_REVIEW_FLOOR_M2,
                              "needsShapeReview": needs_shape_review,
                              "geographicConflict": "not_evaluated",
                              "qualification": "Area alone cannot establish a boundary conflict; review width, source line or road alignment, labels, and GPS consequences."},
            "commonFrameClippingDiagnostics": common_diag,
            "labelConflicts": _label_pair_diagnostics(a, b),
            "boundaryAcceptance": "not_evaluated"}


def _make_graph(candidates: list[dict[str, Any]], target_keys: set[str] | None) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    geoms = [item["face"] for item in candidates]
    if not geoms:
        return [], []
    tree = STRtree(geoms)
    id_map: dict[int, list[int]] = defaultdict(list)
    wkb_map: dict[bytes, list[int]] = defaultdict(list)
    for i, geom in enumerate(geoms):
        id_map[id(geom)].append(i)
        wkb_map[geom.wkb].append(i)
    edge_indices: set[tuple[int, int]] = set()
    neighbors: dict[int, list[int]] = defaultdict(list)
    for i, candidate in enumerate(candidates):
        geom = candidate["face"]
        minx, miny, maxx, maxy = geom.bounds
        query_box = box(minx - DISCOVERY_BAND_M, miny - DISCOVERY_BAND_M,
                        maxx + DISCOVERY_BAND_M, maxy + DISCOVERY_BAND_M)
        for j in _tree_query_indices(tree, query_box, geoms, id_map, wkb_map):
            if i == j or candidates[i]["sourceKey"] == candidates[j]["sourceKey"]:
                continue
            left, right = sorted((i, j))
            if (left, right) in edge_indices:
                continue
            if geom.distance(candidates[j]["face"]) <= DISCOVERY_BAND_M:
                edge_indices.add((left, right))
                neighbors[i].append(j)
                neighbors[j].append(i)
    selected_targets = [i for i, item in enumerate(candidates)
                        if target_keys is None or item["sourceKey"] in target_keys]
    # Pair records are emitted once per unordered pair. In pilot mode include
    # every pair touching a requested target, while retaining all neighbors.
    pair_records = []
    for i, j in sorted(edge_indices):
        if target_keys is not None and candidates[i]["sourceKey"] not in target_keys and candidates[j]["sourceKey"] not in target_keys:
            continue
        pair_records.append(_pair_record(candidates[i], candidates[j]))
    groups = []
    for i in selected_targets:
        member_indices = [i] + sorted(neighbors.get(i, []), key=lambda j: candidates[j]["sourceKey"])
        groups.append({"targetSourceKey": candidates[i]["sourceKey"], "targetName": candidates[i]["name"],
                       "targetDelegation": candidates[i].get("delegation"),
                       "members": [{"sourceKey": candidates[j]["sourceKey"], "name": candidates[j]["name"],
                                    "delegation": candidates[j].get("delegation"),
                                    "relationship": "target" if j == i else "one_hop_candidate",
                                    "faceDistanceMeters": 0.0 if j == i else float(candidates[i]["face"].distance(candidates[j]["face"]))}
                                   for j in member_indices],
                       "discoveryBandMeters": DISCOVERY_BAND_M,
                       "qualification": "One-hop spatial discovery only; not a geographic acceptance decision."})
    return groups, pair_records


def _fallback_buckets(records: list[dict[str, Any]]) -> list[dict[str, Any]]:
    buckets: dict[str, list[str]] = defaultdict(list)
    for record in records:
        if record["status"] == "registered_own_title_face":
            continue
        parents = record.get("delegations") or []
        for parent in parents:
            buckets[parent].append(record["sourceKey"])
    return [{"delegation": parent, "unresolvedSourceKeys": sorted(set(keys)),
             "relationship": "reporting_bucket_only_not_geographic_neighbors"}
            for parent, keys in sorted(buckets.items(), key=lambda item: _norm(item[0]))]


def _render_overlay(key: str, candidates: list[dict[str, Any]], pairs: list[dict[str, Any]], out_path: Path) -> str:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    by_key = {item["sourceKey"]: item for item in candidates}
    target = by_key.get(key)
    if target is None:
        raise ValueError(f"--render-key did not resolve to a registered own face: {key}")
    member_keys = {key}
    for pair in pairs:
        if pair["sourceA"] == key:
            member_keys.add(pair["sourceB"])
        elif pair["sourceB"] == key:
            member_keys.add(pair["sourceA"])
    fig, ax = plt.subplots(figsize=(10, 10))
    palette = {key: "#e53935"}
    for member in sorted(member_keys):
        item = by_key[member]
        poly = item["face"]
        pieces = list(poly.geoms) if hasattr(poly, "geoms") else [poly]
        color = palette.get(member, "#1976d2")
        for piece in pieces:
            x, y = piece.exterior.xy
            ax.fill(x, y, color=color, alpha=.2 if member != key else .35)
            ax.plot(x, y, color=color, linewidth=1.5)
        ax.plot([], [], color=color, label=f"{item['name']} — {item.get('delegation') or 'unknown delegation'}")
    ax.set_aspect("equal", adjustable="datalim")
    ax.grid(True, alpha=.2)
    ax.legend(loc="best", fontsize=8)
    ax.set_title("Cached ISIE registered own-title faces — discovery overlay")
    fig.tight_layout()
    out_path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out_path, dpi=160, metadata={"Software": VERSION})
    plt.close(fig)
    return str(out_path.resolve())


def build_report(inventory_dir: Path, out_path: Path, source_roots: list[Path],
                 only_keys: list[str]) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    roots = _source_roots(inventory_dir, source_roots)
    copies, discovery_issues = _discover_sources(inventory_dir, roots)
    inventory_index, inventory_issues = _inventory_index(inventory_dir)
    sources, aggregate_issues = _aggregate_sources(copies, inventory_index)
    records: list[dict[str, Any]] = []
    candidates: list[dict[str, Any]] = []
    for source in sources:
        record, candidate = _prepare_source(source)
        if source["versionConflict"]:
            record["issues"].append("ambiguous_source_version")
        if source["inventoryAmbiguous"] and "ambiguous_inventory" not in record["issues"]:
            record["issues"].append("ambiguous_inventory")
        # A version conflict is already recorded by _prepare_source; keep one
        # stable copy of each reason in the public source record.
        record["issues"] = list(dict.fromkeys(record["issues"]))
        records.append(record)
        if candidate is not None:
            candidates.append(candidate)
    title_index = _source_title_index(sources)
    _set_label_claims(candidates, title_index)
    source_alias_to_key: dict[str, set[str]] = defaultdict(set)
    for source in sources:
        for ref in source["manifestRefs"]:
            for value in (ref.get("key"), *(ref.get("sourceOsmIds") or []), ref.get("url"), ref.get("code")):
                if value is not None:
                    source_alias_to_key[str(value).strip().casefold()].add(source["sourceKey"])
    target_keys: set[str] | None = None
    selected_aliases = []
    if only_keys:
        target_keys = set()
        for query in only_keys:
            matching = source_alias_to_key.get(query.strip().casefold(), set())
            if not matching:
                matching = {source["sourceKey"] for source in sources
                            if query.strip().casefold() in {str(ref.get("key", "")).casefold() for ref in source["manifestRefs"]}}
            if not matching:
                raise ValueError(f"--only-key did not match any cached manifest identity: {query}")
            target_keys.update(matching)
            selected_aliases.append({"query": query, "sourceKeys": sorted(matching)})
    groups, pairs = _make_graph(candidates, target_keys)
    fallback = _fallback_buckets(records)
    unresolved = [record["sourceKey"] for record in records if record["status"] != "registered_own_title_face"]
    pair_by_key = {p["pairKey"]: p for p in pairs}
    summary = {"cachedPdfCopies": sum(source.get("cachedPdfCopyCount", 0) for source in sources),
               "cachedPdfSources": sum(bool(source.get("hasCachedPdfCopy")) for source in sources),
               "uniqueCachedPdfSha256Sources": sum(bool(source.get("hasCachedPdfCopy")) and bool(source["sourceSha256"]) for source in sources),
               "cachedSourceRecordsIncludingInventoryOnly": len(sources),
               "manifestSourceEntryCount": sum(len(source["manifestRefs"]) for source in sources),
               "inventoriedSources": sum(bool(source.get("inventoryPaths")) for source in sources),
               "usableUnambiguousInventorySources": sum(source.get("inventory") is not None for source in sources),
               "inventoryJsonDocuments": sum(len(source.get("inventoryPaths", [])) for source in sources),
               "georegisteredOwnFaces": len(candidates), "oneHopGroups": len(groups),
               "pairChecks": len(pairs), "unresolvedSources": len(unresolved),
               "sourcesWithSourceVersionConflict": sum(source["versionConflict"] for source in sources),
               "overlapPairsAtOrAbove50m2ForReview": sum(pair["overlapReview"]["needsShapeReview"] for pair in pairs),
               "nearDuplicateOwnTitleFacePairs": sum(pair["pairEvidenceClass"] == "near_duplicate_own_title_faces"
                                                      for pair in pairs)}
    report = {"schemaVersion": 2, "toolVersion": VERSION,
              "status": "CACHED_SOURCE_COVERAGE_ONLY",
              "coverageScope": "Cached local ISIE PDF and manifest coverage only; not geographic acceptance, official-imada denominator coverage, or app installation.",
              "comparisonBasis": "Own-title registered PDF faces and nearby cached PDF faces only. No installed OSM geometry or network data used.",
              "settings": {"commonCrs": COMMON_CRS, "discoveryBandMeters": DISCOVERY_BAND_M,
                           "discoveryBandIsAcceptanceTolerance": False,
                           "seamMetricTolerancesMeters": list(SEAM_TOLERANCES_M),
                           "seamMetricDefinition": "Directional boundary length within tolerance: A.boundary intersect B.boundary.buffer(tol), and reciprocal B.boundary intersect A.boundary.buffer(tol).",
                            "overlapReviewFloorSquareMeters": OVERLAP_REVIEW_FLOOR_M2,
                            "overlapReviewRule": "Classify reported overlaps by strip width and length, source-line or road alignment, label/anchor containment, and GPS impact; area alone never accepts or rejects a boundary",
                           "reciprocalSeamReportingThresholdMeters": 20.0,
                           "reciprocalSeamThresholdIsAcceptance": False,
                           "registrationMaxResidualMeters": REGISTRATION_MAX_RESIDUAL_M,
                           "registrationMaxLeaveOneOutResidualMeters": REGISTRATION_MAX_LOO_RESIDUAL_M,
                           "onlyKeySelections": selected_aliases, "sourceRoots": [str(path) for path in roots],
                           "inventoryDir": str(inventory_dir.resolve())},
              "denominators": summary, "sources": records,
              "groups": groups, "pairs": pairs, "fallbackDelegationReviewBuckets": fallback,
              "unresolvedSourceKeys": unresolved,
              "issues": sorted(discovery_issues + inventory_issues + aggregate_issues,
                               key=lambda issue: (issue.get("code", ""), issue.get("sourceKey", ""), issue.get("path", "")))}
    return report, candidates


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory-dir", required=True, type=Path, help="Directory of existing native inventory JSON files")
    parser.add_argument("--out", required=True, type=Path, help="Output deterministic JSON report path")
    parser.add_argument("--source-root", action="append", type=Path, default=[],
                        help="Cached PDF/manifest root; repeatable. Defaults to sibling work/locality-automation/batches.")
    parser.add_argument("--only-key", action="append", default=[], help="Limit target groups/pair checks; repeatable")
    parser.add_argument("--render-key", help="Also render a registered target and its one-hop discovery neighbors")
    args = parser.parse_args()
    inventory_dir = args.inventory_dir.resolve()
    out_path = args.out.resolve()
    report, candidates = build_report(inventory_dir, out_path, args.source_root, args.only_key)
    if args.render_key:
        match_keys = set()
        query = args.render_key.strip().casefold()
        for item in report["sources"]:
            if query == item["sourceKey"].casefold() or any(
                query == str(value).casefold() for ref in item["manifestRefs"]
                for value in (ref.get("key"), *(ref.get("sourceOsmIds") or []), ref.get("url")) if value is not None):
                match_keys.add(item["sourceKey"])
        if len(match_keys) != 1:
            raise ValueError(f"--render-key must resolve to exactly one cached source; matched {len(match_keys)}")
        key = next(iter(match_keys))
        render_path = out_path.with_name(out_path.stem + "_" + re.sub(r"[^A-Za-z0-9_-]+", "_", args.render_key) + ".png")
        report["renderedPilotOverlay"] = _render_overlay(key, candidates, report["pairs"], render_path)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("w", encoding="utf-8", newline="\n") as stream:
        json.dump(report, stream, ensure_ascii=False, sort_keys=True, indent=2)
        stream.write("\n")
    print(json.dumps({"status": report["status"], "out": str(out_path), "denominators": report["denominators"]},
                     ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
