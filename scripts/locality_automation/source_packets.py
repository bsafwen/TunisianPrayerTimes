'''Source packet builder for offline GeoPDF extraction results.
No model calls, no network calls, no catalog writes. Diagnostics only.
'''
from __future__ import annotations

import html
import math
import re
import uuid
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote, quote_plus

try:
    from .common import read, read_pin, verify, pin, write, module, inside, slug
except ImportError:
    from common import read, read_pin, verify, pin, write, module, inside, slug

try:
    from shapely.geometry import Point, shape
    from shapely.ops import transform
    from shapely.validation import explain_validity
except ImportError as exc:
    raise RuntimeError('Shapely 2 is required for source_packets.build_packet') from exc

try:
    from pyproj import Transformer
except ImportError as exc:
    raise RuntimeError('pyproj is required for source_packets.build_packet') from exc

try:
    import fitz
except ImportError:
    fitz = None

OVERLAY_STORAGE_CAP = 3
THUMBNAIL_MAX_WIDTH = 1600
QQ = chr(34)
LF = chr(10)


def _finite_float(value):
    if isinstance(value, bool):
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    if not math.isfinite(number):
        return None
    return number


def _sanitize_json(value):
    if isinstance(value, float):
        return value if math.isfinite(value) else None
    if isinstance(value, dict):
        return {str(k): _sanitize_json(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [_sanitize_json(v) for v in value]
    if isinstance(value, (str, int, bool)) or value is None:
        return value
    return str(value)


def _area_value(value):
    clean = _sanitize_json(value)
    if clean is None or isinstance(clean, (int, float)):
        return clean
    if isinstance(clean, list):
        return clean
    return None


def _zero_area(value):
    if isinstance(value, bool):
        return False
    if isinstance(value, (int, float)):
        return math.isfinite(value) and value == 0
    if isinstance(value, list):
        return all(_zero_area(item) for item in value)
    return False


def _str_list(value):
    if not isinstance(value, list):
        return []
    return [str(item) for item in value]


def _record_key(record, index):
    if isinstance(record, dict):
        value = record.get('key')
        if isinstance(value, str) and value.strip():
            return value
    return 'record_' + str(index)


def _record_catalog_id(record):
    if not isinstance(record, dict):
        return None
    ids = _str_list(record.get('sourceOsmIds'))
    if len(ids) == 1:
        return ids[0]
    return None


def _decision_template(key, record, extraction_pin):
    return {'id': _record_catalog_id(record), 'key': key, 'extraction': extraction_pin, 'drawingIndex': None, 'decision': 'investigate', 'reason': '', 'sourceIdentityReviewed': False, 'wholeBoundaryReviewed': False}


def _empty_record(index, key, decision, issues):
    return {'candidateIndex': index, 'key': key, 'id': None, 'sourceOsmIds': [], 'name': None, 'parent': None, 'file': None, 'crsEpsg': None, 'affineMetersFromPagePoints': None, 'allTextLabels': [], 'skippedBoundaryPaths': None, 'sourceHashExpected': None, 'sourceHashBefore': None, 'sourceHashAfter': None, 'sourceHashStable': False, 'sourcePDF': None, 'thumbnail': None, 'inputPins': [], 'issues': issues, 'rings': [], 'rankedDrawingIndexes': [], 'overlays': [], 'overlayStorageCap': OVERLAY_STORAGE_CAP, 'overlaysLimitedForStorageOnly': True, 'allRingsEvaluated': False, 'decision': decision}


def _unique_path(path):
    p = Path(path)
    if not p.exists():
        return p
    for number in range(2, 10000):
        candidate = p.with_name(p.stem + '_' + str(number) + p.suffix)
        if not candidate.exists():
            return candidate
    raise FileExistsError('cannot find unique path for ' + str(p))


def _unique_target(record_key, drawing_index, used):
    base = slug(record_key) + '__' + slug(drawing_index)
    candidate = base
    number = 2
    while candidate in used:
        candidate = base + '_' + str(number)
        number += 1
    used.add(candidate)
    return candidate


def _write_text_new(path, text):
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    with p.open('x', encoding='utf-8', newline=LF) as handle:
        handle.write(text)
    return pin(p)


def _source_pdf_path(extraction_path, record):
    if not isinstance(record, dict):
        raise ValueError('record is not an object')
    name = record.get('file')
    if not isinstance(name, str) or not name.strip():
        raise ValueError('record.file is missing')
    candidate = (extraction_path.parent / name).resolve()
    inside(candidate, extraction_path.parent)
    return candidate


def _make_thumbnail(source_pdf, out_dir, key):
    if fitz is None:
        raise RuntimeError('PyMuPDF fitz is not available')
    thumb_dir = out_dir / 'thumbnails'
    thumb_dir.mkdir(parents=True, exist_ok=True)
    target = _unique_path(thumb_dir / (slug(key) + '_page1.png'))
    with fitz.open(source_pdf) as document:
        if document.page_count < 1:
            raise ValueError('source PDF has no pages')
        page = document[0]
        width = float(page.rect.width)
        if not math.isfinite(width) or width <= 0:
            raise ValueError('source PDF first page width is invalid')
        scale = min(1.0, float(THUMBNAIL_MAX_WIDTH) / width)
        pixmap = page.get_pixmap(matrix=fitz.Matrix(scale, scale), alpha=False)
        if pixmap.width > THUMBNAIL_MAX_WIDTH:
            scale = scale * (float(THUMBNAIL_MAX_WIDTH) / float(pixmap.width))
            pixmap = page.get_pixmap(matrix=fitz.Matrix(scale, scale), alpha=False)
        pixmap.save(str(target))
    return pin(target)


def _coords_xy(coords):
    for coord in coords:
        if len(coord) < 2:
            yield (math.nan, math.nan)
        else:
            yield (float(coord[0]), float(coord[1]))


def _iter_xy(geom):
    if geom.is_empty:
        return
    geom_type = geom.geom_type
    if geom_type == 'Polygon':
        yield from _coords_xy(geom.exterior.coords)
        for interior in geom.interiors:
            yield from _coords_xy(interior.coords)
    elif geom_type == 'MultiPolygon':
        for part in geom.geoms:
            yield from _iter_xy(part)
    elif geom_type in ('LineString', 'LinearRing'):
        yield from _coords_xy(geom.coords)
    elif geom_type == 'Point':
        yield (float(geom.x), float(geom.y))
    elif geom_type == 'MultiPoint':
        for part in geom.geoms:
            yield (float(part.x), float(part.y))
    elif geom_type == 'GeometryCollection':
        for part in geom.geoms:
            yield from _iter_xy(part)
    else:
        coords = getattr(geom, 'coords', None)
        if coords is not None:
            yield from _coords_xy(coords)


def _check_lon_lat(geom):
    finite = True
    within = True
    for x, y in _iter_xy(geom):
        if not (math.isfinite(x) and math.isfinite(y)):
            finite = False
            within = False
            continue
        if not (-180.0 <= x <= 180.0 and -90.0 <= y <= 90.0):
            within = False
    return finite, within


def _holes_count(geom):
    if geom.geom_type == 'Polygon':
        return len(geom.interiors)
    if geom.geom_type == 'MultiPolygon':
        return sum(len(part.interiors) for part in geom.geoms)
    return 0


def _catalog_locations(catalog):
    if isinstance(catalog, dict) and isinstance(catalog.get('locations'), list):
        return catalog['locations']
    if isinstance(catalog, dict) and isinstance(catalog.get('file'), str) and isinstance(catalog.get('sha256'), str):
        loaded = read(verify(catalog))
        if not isinstance(loaded, dict) or not isinstance(loaded.get('locations'), list):
            raise ValueError('catalog pin does not contain a locations list')
        return loaded['locations']
    if isinstance(catalog, dict):
        return []
    raise ValueError('catalog must be a dict')


def _locations_by_id(locations):
    result = {}
    for location in locations:
        if not isinstance(location, dict):
            continue
        location_id = location.get('id')
        if location_id is None:
            continue
        key = str(location_id)
        result.setdefault(key, []).append(location)
    return result


def _record_ids(record):
    if not isinstance(record, dict):
        return []
    return _str_list(record.get('sourceOsmIds'))


def _matching_points(record, locations_by_id):
    points = []
    for location_id in _record_ids(record):
        for location in locations_by_id.get(location_id, []):
            lat = _finite_float(location.get('lat'))
            lng = _finite_float(location.get('lng'))
            if lat is None or lng is None:
                continue
            points.append((location_id, lng, lat, location))
    return points


def _evaluate_ring(ring, record, locations_by_id, transformer):
    issues = []
    drawing_index = _sanitize_json(ring.get('drawingIndex')) if isinstance(ring, dict) else None
    target_labels = _str_list(ring.get('targetLabels')) if isinstance(ring, dict) else []
    inside_red_labels = _str_list(ring.get('insideRedLabels')) if isinstance(ring, dict) else []
    diag = {'drawingIndex': drawing_index, 'targetLabels': target_labels, 'insideRedLabels': inside_red_labels, 'vertices': _sanitize_json(ring.get('vertices')) if isinstance(ring, dict) else None, 'geometryType': None, 'supportedType': False, 'nonEmpty': False, 'valid': False, 'validityReason': None, 'finite': False, 'withinLonLat': False, 'clipOutsideViewportAreaPoints2': _area_value(ring.get('outsideViewportAreaPoints2')) if isinstance(ring, dict) else None, 'clipOutsideActiveClipsAreaPoints2': _area_value(ring.get('outsideActiveClipsAreaPoints2')) if isinstance(ring, dict) else None, 'clipOutsideViewportZero': _zero_area(ring.get('outsideViewportAreaPoints2')) if isinstance(ring, dict) else False, 'clipOutsideActiveClipsZero': _zero_area(ring.get('outsideActiveClipsAreaPoints2')) if isinstance(ring, dict) else False, 'clipOutsideZero': False, 'containsPoint': False, 'coversPoint': False, 'matchedLocationIds': [], 'areaM2': None, 'areaKm2': None, 'holes': None, 'issues': issues, 'overlayRendered': False, 'overlay': None, 'overlaySkippedReason': None}
    if not isinstance(ring, dict):
        issues.append('ring is not an object')
        return diag
    geometry = ring.get('geometry')
    if not isinstance(geometry, dict):
        issues.append('ring geometry is missing or not an object')
        return diag
    try:
        geom = shape(geometry)
    except Exception as exc:
        issues.append('cannot build shapely geometry: ' + str(exc))
        return diag
    diag['geometryType'] = geom.geom_type
    diag['supportedType'] = geom.geom_type in ('Polygon', 'MultiPolygon')
    diag['nonEmpty'] = not geom.is_empty
    diag['valid'] = bool(geom.is_valid)
    if not geom.is_valid:
        try:
            diag['validityReason'] = explain_validity(geom)
        except Exception as exc:
            diag['validityReason'] = 'explain_validity failed: ' + str(exc)
    finite, within = _check_lon_lat(geom)
    diag['finite'] = finite
    diag['withinLonLat'] = within
    diag['clipOutsideZero'] = bool(diag['clipOutsideActiveClipsZero'] and diag['clipOutsideViewportZero'])
    diag['holes'] = _holes_count(geom)
    if diag['valid'] and diag['nonEmpty'] and diag['supportedType']:
        try:
            geom_m = transform(transformer.transform, geom)
            area = float(geom_m.area)
            diag['areaM2'] = _finite_float(area)
            diag['areaKm2'] = _finite_float(area / 1000000.0)
            diag['_geomM'] = geom_m
        except Exception as exc:
            issues.append('cannot transform geometry to EPSG32632: ' + str(exc))
    if diag['valid'] and diag['nonEmpty'] and diag['supportedType']:
        for location_id, lng, lat, _location in _matching_points(record, locations_by_id):
            try:
                point = Point(lng, lat)
                if geom.covers(point):
                    if location_id not in diag['matchedLocationIds']:
                        diag['matchedLocationIds'].append(location_id)
                    diag['coversPoint'] = True
                    if geom.contains(point):
                        diag['containsPoint'] = True
            except Exception as exc:
                issues.append('point containment failed for ' + location_id + ': ' + str(exc))
    return diag


def _ring_rank(diag):
    valid_clip = bool(diag.get('valid') and diag.get('nonEmpty') and diag.get('supportedType') and diag.get('finite') and diag.get('withinLonLat') and diag.get('clipOutsideZero'))
    labels = bool(diag.get('targetLabels') or diag.get('insideRedLabels'))
    area = diag.get('areaM2')
    if not isinstance(area, (int, float)):
        area = -1.0
    return (1 if valid_clip else 0, 1 if diag.get('containsPoint') else 0, 1 if labels else 0, float(area))


def _empty_ring_diag(ring, exc):
    return {'drawingIndex': _sanitize_json(ring.get('drawingIndex')) if isinstance(ring, dict) else None, 'targetLabels': [], 'insideRedLabels': [], 'vertices': None, 'geometryType': None, 'supportedType': False, 'nonEmpty': False, 'valid': False, 'validityReason': None, 'finite': False, 'withinLonLat': False, 'clipOutsideViewportAreaPoints2': None, 'clipOutsideActiveClipsAreaPoints2': None, 'clipOutsideViewportZero': False, 'clipOutsideActiveClipsZero': False, 'clipOutsideZero': False, 'containsPoint': False, 'coversPoint': False, 'matchedLocationIds': [], 'areaM2': None, 'areaKm2': None, 'holes': None, 'issues': ['ring evaluation failed: ' + str(exc)], 'overlayRendered': False, 'overlay': None, 'overlaySkippedReason': None}


def _process_record(index, record, extraction_path, extraction_pin, helper_pin, helper, out_dir, locations_by_id, transformer, used_targets):
    key = _record_key(record, index)
    decision = _decision_template(key, record, extraction_pin)
    record_issues = []
    source_pdf_pin = None
    source_hash_before = None
    source_hash_after = None
    source_hash_expected = None
    source_hash_stable = False
    thumbnail_pin = None
    can_render = False
    if not isinstance(record, dict):
        record_issues.append('record is not an object')
        packet_record = _empty_record(index, key, decision, record_issues)
        packet_record['inputPins'] = [extraction_pin, helper_pin]
        return packet_record, decision
    source_ids = _record_ids(record)
    if record.get('crsEpsg') != 32632:
        return _empty_record(index, key, decision, ['Unsupported or missing source CRS; needs investigation']), decision
    if not source_ids:
        record_issues.append('sourceOsmIds is missing or not a list of ids')
    source_hash_expected = record.get('sha256')
    if not isinstance(source_hash_expected, str) or re.fullmatch('[a-f0-9]{64}', source_hash_expected) is None:
        record_issues.append('record.sha256 is missing or malformed')
        source_hash_expected = None
    try:
        source_pdf = _source_pdf_path(extraction_path, record)
    except Exception as exc:
        record_issues.append('source PDF path invalid: ' + str(exc))
        source_pdf = None
    if source_pdf is not None:
        try:
            source_pdf_pin = pin(source_pdf)
            source_hash_before = source_pdf_pin['sha256']
        except Exception as exc:
            record_issues.append('source PDF missing or unreadable: ' + str(exc))
    if source_hash_before is not None and source_hash_expected is not None:
        if source_hash_before == source_hash_expected:
            can_render = True
        else:
            record_issues.append('source PDF sha256 does not match extraction record')
    if can_render and source_pdf is not None:
        try:
            thumbnail_pin = _make_thumbnail(source_pdf, out_dir, key)
        except Exception as exc:
            record_issues.append('thumbnail failed: ' + str(exc))
    rings = record.get('closedRings')
    if not isinstance(rings, list):
        record_issues.append('closedRings is missing or not a list')
        rings = []
    ring_diags = []
    for ring_index, ring in enumerate(rings):
        try:
            diag = _evaluate_ring(ring, record, locations_by_id, transformer)
        except Exception as exc:
            diag = _empty_ring_diag(ring, exc)
        if diag.get('drawingIndex') is None:
            record_issues.append('ring at index ' + str(ring_index) + ' has missing drawingIndex')
        ring_diags.append(diag)
    ordered = sorted(ring_diags, key=_ring_rank, reverse=True)
    overlays = []
    overlay_candidates = [item for item in ordered if _ring_rank(item)[0] == 1]
    if not can_render:
        for diag in overlay_candidates:
            diag['overlaySkippedReason'] = 'overlay skipped because source PDF hash was not available or did not match'
    else:
        for diag in overlay_candidates[:OVERLAY_STORAGE_CAP]:
            geom_m = diag.get('_geomM')
            if geom_m is None:
                diag['overlaySkippedReason'] = 'overlay skipped because EPSG32632 geometry is unavailable'
                continue
            if not diag.get('valid'):
                diag['overlaySkippedReason'] = 'overlay skipped for invalid geometry'
                continue
            drawing_index = diag.get('drawingIndex')
            target = _unique_target(key, drawing_index if drawing_index is not None else 'ring', used_targets)
            try:
                overlay_pin = helper.render_overlay(out_dir, target, record, geom_m, source_pdf)
                verify(overlay_pin)
                diag['overlayRendered'] = True
                diag['overlay'] = overlay_pin
                overlays.append(overlay_pin)
            except Exception as exc:
                diag['overlaySkippedReason'] = 'render_overlay failed: ' + str(exc)
                record_issues.append('render_overlay failed for ' + str(drawing_index) + ': ' + str(exc))
    for diag in ring_diags:
        diag.pop('_geomM', None)
    if source_pdf is not None and source_pdf.is_file():
        try:
            after_pin = pin(source_pdf)
            source_hash_after = after_pin['sha256']
        except Exception as exc:
            record_issues.append('source PDF hash after processing failed: ' + str(exc))
    if source_hash_before is not None and source_hash_after is not None:
        source_hash_stable = source_hash_before == source_hash_after
    if source_hash_before is not None and source_hash_after is not None and not source_hash_stable:
        record_issues.append('source PDF changed during processing')
    packet_record = {'candidateIndex': index, 'key': key, 'id': _record_catalog_id(record), 'sourceOsmIds': source_ids, 'name': record.get('name'), 'parent': record.get('parent'), 'file': record.get('file'), 'crsEpsg': record.get('crsEpsg'), 'affineMetersFromPagePoints': _sanitize_json(record.get('affineMetersFromPagePoints')), 'allTextLabels': _str_list(record.get('allTextLabels')), 'skippedBoundaryPaths': _sanitize_json(record.get('skippedBoundaryPaths')), 'sourceHashExpected': source_hash_expected, 'sourceHashBefore': source_hash_before, 'sourceHashAfter': source_hash_after, 'sourceHashStable': source_hash_stable, 'sourcePDF': source_pdf_pin, 'thumbnail': thumbnail_pin, 'inputPins': [extraction_pin, helper_pin] + ([source_pdf_pin] if source_pdf_pin is not None else []), 'issues': record_issues, 'rings': ring_diags, 'rankedDrawingIndexes': [item.get('drawingIndex') for item in ordered], 'overlays': overlays, 'overlayStorageCap': OVERLAY_STORAGE_CAP, 'overlaysLimitedForStorageOnly': True, 'allRingsEvaluated': True, 'decision': decision}
    return packet_record, decision


def _esc(value):
    return html.escape(str(value), quote=True)


def _rel_url(pin_value, root):
    if not isinstance(pin_value, dict) or not isinstance(pin_value.get('file'), str):
        return None
    path = Path(pin_value['file']).resolve()
    try:
        relative = path.relative_to(Path(root).resolve())
    except ValueError:
        return path.as_uri()
    return quote(relative.as_posix())


def _render_html(packet, out_dir):
    lines = []
    lines.append('<!doctype html>')
    lines.append('<html lang=' + QQ + 'en' + QQ + '>')
    lines.append('<meta charset=' + QQ + 'utf-8' + QQ + '>')
    lines.append('<title>Source packets</title>')
    lines.append('<style>body{font-family:sans-serif;margin:1rem;} table{border-collapse:collapse;} td,th{border:1px solid #999;padding:.25rem;vertical-align:top;} img{max-width:100%;height:auto;} .issue{color:#900;}</style>')
    lines.append('<body>')
    lines.append('<h1>Source packets</h1>')
    lines.append('<p>Diagnostic packet only. No automatic choose, accept, reject, or repair. Source hashes are checked before and after record processing; mismatches are reported as issues.</p>')
    lines.append('<p>' + _esc(packet.get('storageNote')) + '</p>')
    extraction = packet.get('extraction') if isinstance(packet.get('extraction'), dict) else {}
    lines.append('<p>Extraction: ' + _esc(extraction.get('file')) + ' sha256 ' + _esc(extraction.get('sha256')) + '</p>')
    for issue in packet.get('issues', []):
        lines.append('<p class=' + QQ + 'issue' + QQ + '>GLOBAL ISSUE: ' + _esc(issue) + '</p>')
    for record in packet.get('records', []):
        lines.append('<hr>')
        lines.append('<h2>Record ' + _esc(record.get('key')) + '</h2>')
        lines.append('<p>id: ' + _esc(record.get('id')) + ' | name: ' + _esc(record.get('name')) + ' | parent: ' + _esc(record.get('parent')) + '</p>')
        source_pdf = record.get('sourcePDF')
        if isinstance(source_pdf, dict) and isinstance(source_pdf.get('file'), str):
            lines.append('<p>Source PDF: <a href=' + QQ + _esc(Path(source_pdf['file']).as_uri()) + QQ + '>file URI</a></p>')
        else:
            lines.append('<p>Source PDF: unavailable</p>')
        search_name = record.get('name') or record.get('key') or ''
        search_url = 'http://127.0.0.1:8769/?q=' + quote_plus(str(search_name))
        lines.append('<p>Tool search: <a href=' + QQ + _esc(search_url) + QQ + '>' + _esc(search_name) + '</a></p>')
        record_issues = record.get('issues')
        if isinstance(record_issues, list) and record_issues:
            lines.append('<h3>Record issues</h3><ul>')
            for issue in record_issues:
                lines.append('<li class=' + QQ + 'issue' + QQ + '>' + _esc(issue) + '</li>')
            lines.append('</ul>')
        thumb_url = _rel_url(record.get('thumbnail'), out_dir)
        if thumb_url is not None:
            lines.append('<p>Thumbnail: <a href=' + QQ + _esc(thumb_url) + QQ + '><img src=' + QQ + _esc(thumb_url) + QQ + ' alt=' + QQ + 'thumbnail' + QQ + '></a></p>')
        overlays = record.get('overlays')
        if isinstance(overlays, list) and overlays:
            lines.append('<h3>Overlays (storage cap ' + _esc(record.get('overlayStorageCap')) + ' per record)</h3>')
            for overlay in overlays:
                overlay_url = _rel_url(overlay, out_dir)
                if overlay_url is not None:
                    lines.append('<a href=' + QQ + _esc(overlay_url) + QQ + '><img src=' + QQ + _esc(overlay_url) + QQ + ' alt=' + QQ + 'overlay' + QQ + '></a> ')
        lines.append('<h3>Ring diagnostics</h3>')
        lines.append('<table><thead><tr><th>drawingIndex</th><th>type</th><th>valid</th><th>nonEmpty</th><th>finite</th><th>lonlat</th><th>clipOutside0</th><th>contains</th><th>covers</th><th>areaM2</th><th>holes</th><th>targetLabels</th><th>insideRedLabels</th><th>overlay</th><th>issues</th></tr></thead><tbody>')
        rings = record.get('rings')
        if not isinstance(rings, list):
            rings = []
        for ring in rings:
            lines.append('<tr>')
            lines.append('<td>' + _esc(ring.get('drawingIndex')) + '</td>')
            lines.append('<td>' + _esc(ring.get('geometryType')) + '</td>')
            lines.append('<td>' + _esc(ring.get('valid')) + '</td>')
            lines.append('<td>' + _esc(ring.get('nonEmpty')) + '</td>')
            lines.append('<td>' + _esc(ring.get('finite')) + '</td>')
            lines.append('<td>' + _esc(ring.get('withinLonLat')) + '</td>')
            lines.append('<td>' + _esc(ring.get('clipOutsideZero')) + '</td>')
            lines.append('<td>' + _esc(ring.get('containsPoint')) + '</td>')
            lines.append('<td>' + _esc(ring.get('coversPoint')) + '</td>')
            lines.append('<td>' + _esc(ring.get('areaM2')) + '</td>')
            lines.append('<td>' + _esc(ring.get('holes')) + '</td>')
            target_labels = ring.get('targetLabels')
            inside_labels = ring.get('insideRedLabels')
            lines.append('<td>' + _esc('; '.join(target_labels if isinstance(target_labels, list) else [])) + '</td>')
            lines.append('<td>' + _esc('; '.join(inside_labels if isinstance(inside_labels, list) else [])) + '</td>')
            if ring.get('overlayRendered'):
                overlay_url = _rel_url(ring.get('overlay'), out_dir)
                if overlay_url is not None:
                    lines.append('<td><a href=' + QQ + _esc(overlay_url) + QQ + '>overlay</a></td>')
                else:
                    lines.append('<td>rendered</td>')
            else:
                lines.append('<td>' + _esc(ring.get('overlaySkippedReason')) + '</td>')
            ring_issues = ring.get('issues')
            lines.append('<td>' + _esc('; '.join(ring_issues if isinstance(ring_issues, list) else [])) + '</td>')
            lines.append('</tr>')
        lines.append('</tbody></table>')
    lines.append('</body></html>')
    return LF.join(lines)


def build_packet(extraction_pin: dict, catalog: dict, helper_pin: dict, output_dir: Path) -> dict:
    extraction_path = verify(extraction_pin)
    extraction = read_pin(extraction_pin)
    if not isinstance(extraction, dict):
        raise ValueError('extraction JSON must contain an object')
    out_dir = Path(output_dir)
    if out_dir.exists():
        raise FileExistsError('output directory already exists: ' + str(out_dir))
    extraction_root = extraction_path.parent.resolve()
    out_resolved = out_dir.resolve()
    if out_resolved == extraction_root:
        raise ValueError('output directory must differ from extraction input directory')
    try:
        inside(out_resolved, extraction_root)
    except ValueError:
        pass
    else:
        raise ValueError('output directory must be outside extraction input directory')
    out_dir.mkdir(parents=True, exist_ok=False)
    helper = module(helper_pin, 'source_packets_helper_' + uuid.uuid4().hex)
    if not hasattr(helper, 'render_overlay'):
        raise ValueError('helper module does not expose render_overlay')
    locations = _catalog_locations(catalog)
    locations_by_id = _locations_by_id(locations)
    transformer = Transformer.from_crs(4326, 32632, always_xy=True)
    records = extraction.get('records')
    global_issues = []
    if not isinstance(records, list):
        global_issues.append('extraction.records is missing or not a list')
        records = []
    packet_records = []
    decisions = []
    used_targets = set()
    for index, record in enumerate(records):
        try:
            packet_record, decision = _process_record(index, record, extraction_path, extraction_pin, helper_pin, helper, out_dir, locations_by_id, transformer, used_targets)
        except Exception as exc:
            key = _record_key(record, index)
            decision = _decision_template(key, record, extraction_pin)
            packet_record = _empty_record(index, key, decision, ['record processing failed: ' + str(exc)])
            packet_record['inputPins'] = [extraction_pin, helper_pin]
        packet_records.append(packet_record)
        decisions.append(decision)
    packet = {'generatedAt': datetime.now(timezone.utc).isoformat(), 'extraction': extraction_pin, 'helper': helper_pin, 'catalogLocationCount': len(locations), 'outputDir': str(out_resolved), 'storageNote': 'Overlays are capped at 3 per record for storage only. All closed rings are included in diagnostics.', 'issues': global_issues, 'records': packet_records}
    decisions_pin = write(out_dir / 'decisions-template.json', decisions)
    html_text = _render_html(packet, out_dir)
    html_pin = _write_text_new(out_dir / 'index.html', html_text)
    packet['artifacts'] = {'decisionsTemplate': decisions_pin, 'indexHtml': html_pin}
    write(out_dir / 'packet.json', packet)
    return packet
