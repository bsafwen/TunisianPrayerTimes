#!/usr/bin/env python3
"""Compile attributed place polygons for offline GPS labels and detect ambiguity.

Usage: python scripts/generate_neighborhoods.py /path/to/tunisia.osm.pbf
Dependencies: scripts/neighborhoods/requirements.txt. No geocoding or Google API.
"""
import argparse
import calendar
from collections import Counter, defaultdict
import csv
from datetime import date
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import re
import struct
import unicodedata

import osmium
from shapely import make_valid, set_precision
from shapely.geometry import mapping, shape, Point, MultiPolygon, Polygon
from shapely.strtree import STRtree

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / 'android-app/app/src/main/assets'
PLACES = {'neighbourhood', 'quarter', 'suburb', 'city_district', 'village', 'hamlet', 'town'}
AREA_PLACES = PLACES | {'locality'}
FINE_KINDS = {'neighbourhood', 'quarter', 'suburb', 'city_district', 'residential', 'subdistrict', 'locality'}
MUNICIPAL_MANIFEST = ROOT / 'scripts/neighborhoods/municipal-sources.json'
CURATION_MANIFEST = ROOT / 'scripts/neighborhoods/catalog-curation.json'
PRAYER_SOURCE_COORDINATES = ROOT / 'scripts/neighborhoods/prayer-source-coordinates.json'
REVIEWED_BOUNDARIES = ROOT / 'scripts/neighborhoods/reviewed-boundaries.json'
REVIEWED_PICKER_GROUPS = ROOT / 'scripts/neighborhoods/reviewed-picker-groups.json'
FINAL_EXCLUSIONS = ROOT / 'scripts/neighborhoods/final-exclusions.json'
REVIEWED_CATALOG_UPDATES = ROOT / 'scripts/neighborhoods/reviewed-catalog-updates.json'
REVIEWED_MANUAL_POINTS = ROOT / 'scripts/neighborhoods/reviewed-manual-points.json'
GRID = 0.1
SCALE = 1_000_000


def clean(text):
    return re.sub(r'\s+', ' ', ''.join(c for c in text if unicodedata.category(c) != 'Cf')).strip()


def norm(text):
    text = ''.join(c for c in unicodedata.normalize('NFKD', clean(text)) if not unicodedata.combining(c))
    return re.sub(r'[^\w]+', '', text.replace('ـ', '').replace('ى', 'ي').lower())


def is_current_name_tag(key):
    return (key in ('name', 'alt_name', 'alt_name:ar', 'alt_name:fr', 'short_name', 'loc_name', 'official_name')
            or key.startswith(('name:', 'loc_name:', 'official_name:')))


def names(tags):
    result = []
    keys = ['name:ar', 'name', 'name:fr', 'name:en', 'alt_name', 'alt_name:ar', 'alt_name:fr', 'short_name']
    keys += sorted(k for k in tags if k not in keys and is_current_name_tag(k))
    for key in keys:
        for value in tags.get(key, '').split(';'):
            if clean(value) and clean(value) not in result:
                result.append(clean(value))
    return result


def area_kind(tags):
    if tags.get('boundary') == 'administrative':
        level = tags.get('admin_level')
        if level == '2' and tags.get('ISO3166-1') == 'TN': return 'country'
        if level == '4': return 'governorate'
        if level == '5': return 'delegation'
        if level == '6': return 'sector'
        if level in ('7', '8', '9', '10'): return 'subdistrict'
        # A named, explicitly administrative polygon is usable even when its
        # mapper omitted the administrative level. Do not invent an imada rank.
        if not level and not tags.get('building'): return 'locality'
    if tags.get('place') in AREA_PLACES and not tags.get('building'):
        if tags.get('landuse') not in ('farmyard', 'farmland') and tags.get('leisure') != 'fitness_station':
            return tags['place']
    if tags.get('landuse') == 'residential' and not tags.get('building'): return 'residential'
    return None


def extract(pbf):
    factory = osmium.geom.GeoJSONFactory()
    areas, nodes, errors = [], [], []
    processor = (osmium.FileProcessor(str(pbf)).with_areas()
                 .with_filter(osmium.filter.KeyFilter('place', 'boundary', 'landuse')))
    for obj in processor:
        if obj.is_area():
            tags = dict(obj.tags)
            kind = area_kind(tags)
            if not kind or not names(tags): continue
            identifier = f"osm:{'way' if obj.from_way() else 'relation'}:{obj.orig_id()}"
            try:
                geometry = shape(json.loads(factory.create_multipolygon(obj)))
                if not geometry.is_valid: geometry = make_valid(geometry)
                if geometry.geom_type not in ('Polygon', 'MultiPolygon') or geometry.is_empty:
                    raise ValueError('Not a polygon after validation')
                areas.append({'id': identifier, 'tags': tags, 'kind': kind, 'shape': geometry, 'sourceId': 'osm'})
            except Exception as error:
                errors.append({'id': identifier, 'reason': str(error)})
        elif obj.is_node() and obj.tags.get('place') in PLACES:
            tags = dict(obj.tags)
            if names(tags):
                nodes.append({'id': f'osm:node:{obj.id}', 'tags': tags, 'kind': tags['place'],
                              'point': Point(obj.lon, obj.lat), 'sourceId': 'osm'})
    return areas, nodes, errors


def load_municipal_sources(manifest_path):
    """Validate byte-pinned, licensed municipal polygons without repairing borders.

    Invalid input stops generation instead of silently publishing a partial source.
    The source file is kept unchanged in the repository for provenance/rebuilds.
    """
    manifest_path = Path(manifest_path)
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    if manifest.get('schemaVersion') != 1 or not isinstance(manifest.get('sources'), list):
        raise ValueError('Invalid municipal source manifest')
    result, sources, seen_ids = [], {}, set()
    for source in manifest['sources']:
        for required in ('id', 'provider', 'catalogUrl', 'url', 'license', 'file', 'sha256', 'featureCount', 'idPrefix'):
            if not source.get(required): raise ValueError(f'Missing source metadata: {required}')
        if source['id'] in sources: raise ValueError('Duplicate municipal source ID')
        path = (manifest_path.parent / source['file']).resolve()
        if not path.is_relative_to(manifest_path.parent.resolve()):
            raise ValueError('Municipal source file escapes source directory')
        raw = path.read_bytes()
        if hashlib.sha256(raw).hexdigest() != source['sha256']:
            raise ValueError(f"Municipal source checksum mismatch: {source['id']}")
        data = json.loads(raw)
        if data.get('type') != 'FeatureCollection' or len(data.get('features', [])) != source['featureCount']:
            raise ValueError('Unexpected municipal feature collection/count')
        crs = data.get('crs', {}).get('properties', {}).get('name')
        if crs and crs not in ('urn:ogc:def:crs:OGC:1.3:CRS84', 'EPSG:4326', 'urn:ogc:def:crs:EPSG::4326'):
            raise ValueError('Municipal source must use WGS84 longitude/latitude')
        for feature in data['features']:
            properties = feature.get('properties', {})
            local_id = properties.get('Quartier_id', '')
            if not isinstance(local_id, str) or not re.fullmatch(r'[A-Za-z0-9_-]+', local_id):
                raise ValueError('Missing or invalid municipal locality ID')
            identifier = source['idPrefix'] + local_id
            if identifier in seen_ids: raise ValueError('Duplicate municipal locality ID')
            tags = {'name:ar': properties.get('Quartier_Ar', ''), 'name:fr': properties.get('Quartier_Fr', '')}
            if any(not isinstance(v, str) for v in tags.values()) or not names(tags):
                raise ValueError('Missing or invalid municipal locality name')
            geometry = shape(feature.get('geometry'))
            if geometry.geom_type not in ('Polygon', 'MultiPolygon') or geometry.is_empty or not geometry.is_valid:
                raise ValueError(f'Invalid municipal polygon: {identifier}')
            if geometry.has_z or not all(math.isfinite(v) for v in geometry.bounds):
                raise ValueError('Municipal geometry must contain finite 2D coordinates')
            lo_x, lo_y, hi_x, hi_y = geometry.bounds
            if not (-180 <= lo_x <= hi_x <= 180 and -90 <= lo_y <= hi_y <= 90):
                raise ValueError('Municipal coordinates outside longitude/latitude range')
            # The source's industrial zone is a named locality, not a neighborhood.
            kind = source.get('kindOverrides', {}).get(local_id, 'neighbourhood')
            if kind not in FINE_KINDS: raise ValueError('Unsupported municipal locality kind')
            result.append({'id': identifier, 'sourceId': source['id'], 'tags': tags, 'kind': kind, 'shape': geometry})
            seen_ids.add(identifier)
        sources[source['id']] = {k: v for k, v in source.items() if k not in ('file', 'idPrefix', 'kindOverrides')}
    return result, sources


def aggregate_review_file(directory, reference, label):
    """Read only an explicitly pinned review/parts file inside the source tree."""
    if (not isinstance(reference, dict) or not isinstance(reference.get('file'), str)
            or not reference['file'] or not isinstance(reference.get('sha256'), str)
            or not re.fullmatch(r'[0-9a-f]{64}', reference['sha256'])):
        raise ValueError(f'Missing aggregate {label} reference')
    path = (directory / reference['file']).resolve()
    if not path.is_relative_to(directory.resolve()):
        raise ValueError(f'Aggregate {label} escapes source directory')
    raw = path.read_bytes()
    if hashlib.sha256(raw).hexdigest() != reference['sha256']:
        raise ValueError(f'Aggregate {label} checksum mismatch')
    return raw


import hashlib
import json
from pathlib import Path

_final_exclusion_HEX = set("0123456789abcdef")
_final_exclusion_KINDS = {"residential", "quarter", "neighbourhood"}

def _final_exclusion_sha_ok(value):
    return isinstance(value, str) and len(value) == 64 and all(c in _final_exclusion_HEX for c in value)

def _final_exclusion_file_sha(path):
    try:
        h = hashlib.sha256()
        with open(path, "rb") as fh:
            for chunk in iter(lambda: fh.read(1 << 20), b""):
                h.update(chunk)
        return h.hexdigest()
    except OSError as exc:
        raise ValueError(f"cannot read evidence file {path!r}") from exc

def _final_exclusion_need(cond, msg):
    if not cond:
        raise ValueError(msg)

def apply_final_exclusions(result, blob, selection_report, manifest_path):
    _final_exclusion_need(isinstance(result, dict), "result must be an object")
    _final_exclusion_need(isinstance(blob, (bytes, bytearray, memoryview)), "blob must be bytes-like")
    bview = blob.tobytes() if isinstance(blob, memoryview) else blob
    try:
        mpath = Path(manifest_path).resolve(strict=True)
    except Exception as exc:
        raise ValueError(f"manifest path is invalid: {manifest_path!r}") from exc
    _final_exclusion_need(mpath.is_file(), "manifest path must be a file")
    try:
        mbytes = mpath.read_bytes()
    except OSError as exc:
        raise ValueError(f"cannot read manifest {mpath!r}") from exc
    try:
        manifest = json.loads(mbytes.decode("utf-8"))
    except Exception as exc:
        raise ValueError("manifest must be valid UTF-8 JSON") from exc
    _final_exclusion_need(isinstance(manifest, dict), "manifest must be an object")
    _final_exclusion_need(set(manifest) == {"schemaVersion", "exclusions"}, "manifest must have exact keys schemaVersion,exclusions")
    _final_exclusion_need(type(manifest["schemaVersion"]) is int and manifest["schemaVersion"] == 1, "manifest.schemaVersion must be integer 1")
    exclusions = manifest["exclusions"]
    _final_exclusion_need(isinstance(exclusions, list) and exclusions, "manifest.exclusions must be a nonempty list")
    base = mpath.parent
    seen = set()
    info = []
    for i, ex in enumerate(exclusions):
        _final_exclusion_need(isinstance(ex, dict) and set(ex) == {"id", "expectedFeature", "expectedGeometrySha256", "reason", "evidence"}, f"manifest.exclusions[{i}] has wrong keys")
        eid = ex["id"]
        _final_exclusion_need(isinstance(eid, str) and eid, f"manifest.exclusions[{i}].id must be a nonempty string")
        _final_exclusion_need(not eid.startswith("delegation:") and eid not in seen, f"duplicate exclusion id {eid!r}")
        seen.add(eid)
        expected = ex["expectedFeature"]
        _final_exclusion_need(isinstance(expected, dict), f"exclusion {eid!r}.expectedFeature must be an object")
        _final_exclusion_need(isinstance(ex["reason"], str) and ex["reason"].strip(), f"exclusion {eid!r}.reason must be nonempty")
        geom = ex["expectedGeometrySha256"]
        _final_exclusion_need(geom is None or _final_exclusion_sha_ok(geom), f"exclusion {eid!r}.expectedGeometrySha256 must be 64 lowercase hex or null")
        ev = ex["evidence"]
        _final_exclusion_need(isinstance(ev, list) and ev, f"exclusion {eid!r}.evidence must be a list")
        refs = []
        for j, er in enumerate(ev):
            _final_exclusion_need(isinstance(er, dict) and set(er) == {"file", "sha256"}, f"exclusion {eid!r}.evidence[{j}] has wrong keys")
            rel = er["file"]
            _final_exclusion_need(isinstance(rel, str) and rel and not Path(rel).is_absolute(), f"exclusion {eid!r}.evidence[{j}].file must be a relative path")
            try:
                ep = (base / rel).resolve(strict=True)
            except Exception as exc:
                raise ValueError(f"exclusion {eid!r} evidence file missing: {rel!r}") from exc
            try:
                ep.relative_to(base.resolve())
            except ValueError:
                raise ValueError(f"exclusion {eid!r} evidence file escapes manifest directory: {rel!r}")
            _final_exclusion_need(ep.is_file(), f"exclusion {eid!r} evidence path is not a file: {rel!r}")
            digest = er["sha256"]
            _final_exclusion_need(_final_exclusion_sha_ok(digest), f"exclusion {eid!r}.evidence[{j}].sha256 must be 64 lowercase hex")
            _final_exclusion_need(_final_exclusion_file_sha(ep) == digest, f"exclusion {eid!r} evidence sha256 mismatch: {rel!r}")
            refs.append({"file": rel, "sha256": digest})
        info.append((eid, expected, geom, refs))
    _final_exclusion_need(isinstance(result.get("features"), list), "result.features must be a list")
    _final_exclusion_need(isinstance(result.get("cells"), dict), "result.cells must be an object")
    _final_exclusion_need(isinstance(result.get("conflicts"), list), "result.conflicts must be a list")
    _final_exclusion_need(isinstance(result.get("retiredLocalityIds"), list), "result.retiredLocalityIds must be a list")
    features = result["features"]
    id_to_idx = {}
    for i, f in enumerate(features):
        _final_exclusion_need(isinstance(f, dict), f"result.features[{i}] must be an object")
        fid = f.get("id")
        _final_exclusion_need(isinstance(fid, str) and fid, f"result.features[{i}].id must be a nonempty string")
        _final_exclusion_need(fid not in id_to_idx, f"duplicate feature id {fid!r}")
        id_to_idx[fid] = i
    retired = result["retiredLocalityIds"]
    _final_exclusion_need(all(isinstance(x, str) and x for x in retired), "result.retiredLocalityIds must contain nonempty strings")
    retired_set = set(retired)
    for eid, expected, geom, refs in info:
        _final_exclusion_need(eid not in retired_set, f"exclusion id {eid!r} is already retired")
        _final_exclusion_need(eid in id_to_idx, f"exclusion id {eid!r} is not present in result.features")
        row = features[id_to_idx[eid]]
        _final_exclusion_need(row == expected, f"expectedFeature mismatch for exclusion {eid!r}")
        _final_exclusion_need(row.get("sourceId") == "osm", f"exclusion {eid!r} sourceId must be osm")
        kind = row.get("kind")
        _final_exclusion_need(isinstance(kind, str) and kind in _final_exclusion_KINDS, f"exclusion {eid!r} kind must be residential, quarter, or neighbourhood")
        _final_exclusion_need(row.get("pickerGroupId") == eid, f"exclusion {eid!r} pickerGroupId must equal id")
        members = [f for f in features if f.get("pickerGroupId") == eid]
        _final_exclusion_need(len(members) == 1 and members[0].get("id") == eid, f"exclusion {eid!r} is not the only picker group member")
        hb = row.get("hasBoundary")
        _final_exclusion_need(type(hb) is bool, f"exclusion {eid!r} hasBoundary must be boolean")
        if hb:
            _final_exclusion_need(_final_exclusion_sha_ok(geom), f"exclusion {eid!r} boundary requires expectedGeometrySha256")
            off, length = row.get("offset"), row.get("length")
            _final_exclusion_need(type(off) is int and off >= 8, f"exclusion {eid!r} offset must be an integer >= 8")
            _final_exclusion_need(type(length) is int and length > 0, f"exclusion {eid!r} length must be a positive integer")
            _final_exclusion_need(off + length <= len(bview), f"exclusion {eid!r} geometry slice is out of bounds")
            _final_exclusion_need(hashlib.sha256(bview[off:off + length]).hexdigest() == geom, f"exclusion {eid!r} geometry sha256 mismatch")
        else:
            _final_exclusion_need(geom is None, f"exclusion {eid!r} point requires null expectedGeometrySha256")
    excluded_ids = set(seen)
    old_to_new = {}
    new_features = []
    for old, f in enumerate(features):
        if f["id"] not in excluded_ids:
            old_to_new[old] = len(new_features)
            new_features.append(f)
    new_cells = {}
    for key, indices in result["cells"].items():
        _final_exclusion_need(isinstance(indices, list), f"result.cells[{key!r}] must be a list")
        used = set()
        mapped = []
        for idx in indices:
            _final_exclusion_need(type(idx) is int, f"result.cells[{key!r}] indices must be integers")
            _final_exclusion_need(0 <= idx < len(features), f"result.cells[{key!r}] index out of range: {idx}")
            _final_exclusion_need(idx not in used, f"result.cells[{key!r}] duplicate index {idx}")
            used.add(idx)
            _final_exclusion_need(features[idx].get("hasBoundary") is True, f"result.cells[{key!r}] index {idx} is not a boundary row")
            if idx in old_to_new:
                mapped.append(old_to_new[idx])
        if mapped:
            new_cells[key] = mapped
    new_conflicts = []
    for i, c in enumerate(result["conflicts"]):
        _final_exclusion_need(isinstance(c, dict) and isinstance(c.get("ids"), list), f"result.conflicts[{i}] must be an object with an ids list")
        _final_exclusion_need(all(isinstance(x, str) and x for x in c["ids"]), f"result.conflicts[{i}].ids must contain nonempty strings")
        if not excluded_ids.intersection(c["ids"]):
            new_conflicts.append(c)
    new_result = dict(result)
    new_result["features"] = new_features
    new_result["cells"] = new_cells
    new_result["conflicts"] = new_conflicts
    new_result["retiredLocalityIds"] = sorted(retired_set | excluded_ids)
    if selection_report is not None:
        _final_exclusion_need(isinstance(selection_report, dict), "selection_report must be an object or None")
        _final_exclusion_need(isinstance(selection_report.get("currentManualSelections"), list), "selection_report.currentManualSelections must be a list")
        _final_exclusion_need(isinstance(selection_report.get("rawChanges"), list), "selection_report.rawChanges must be a list")
        _final_exclusion_need(isinstance(selection_report.get("manualChanges"), list), "selection_report.manualChanges must be a list")
        _final_exclusion_need(type(selection_report.get("rawChangedCount")) is int and selection_report["rawChangedCount"] >= 0, "selection_report.rawChangedCount must be a nonnegative integer")
        _final_exclusion_need(type(selection_report.get("manualChangedCount")) is int and selection_report["manualChangedCount"] >= 0, "selection_report.manualChangedCount must be a nonnegative integer")
        def filt(rows, name, required):
            out = []
            for i, row in enumerate(rows):
                _final_exclusion_need(isinstance(row, dict), f"selection_report.{name}[{i}] must be an object")
                for key in required:
                    _final_exclusion_need(key in row, f"selection_report.{name}[{i}].{key} is required")
                _final_exclusion_need(isinstance(row["id"], str) and row["id"], f"selection_report.{name}[{i}].id must be a nonempty string")
                if row["id"] not in excluded_ids:
                    out.append(row)
            return out
        new_cms = filt(selection_report["currentManualSelections"], "currentManualSelections", ("id", "representativeId", "lat", "lng", "sourceId"))
        new_raw = filt(selection_report["rawChanges"], "rawChanges", ("id", "before", "after"))
        new_man = filt(selection_report["manualChanges"], "manualChanges", ("id", "before", "after"))
        for row in new_cms:
            rep = row["representativeId"]
            _final_exclusion_need(isinstance(rep, str) and rep, "selection_report representativeId must be a nonempty string")
            _final_exclusion_need(rep not in excluded_ids, f"selection_report row {row['id']!r} representativeId points to excluded id {rep!r}")
        new_selection_report = dict(selection_report)
        new_selection_report["currentManualSelections"] = new_cms
        new_selection_report["rawChanges"] = new_raw
        new_selection_report["manualChanges"] = new_man
        new_selection_report["rawChangedCount"] = len(new_raw)
        new_selection_report["manualChangedCount"] = len(new_man)
        new_selection_report["excludedAfterSelectionIds"] = sorted(excluded_ids)
    else:
        new_selection_report = None
    report = {
        "manifestSha256": hashlib.sha256(mbytes).hexdigest(),
        "excludedIds": sorted(excluded_ids),
        "evidenceReferences": [ref for _, _, _, refs in info for ref in refs],
        "geometryBytesPreserved": True,
    }
    return new_result, new_selection_report, report


def _selection_require(condition, message):
    if not condition:
        raise ValueError('Prayer selection successor: ' + message)


def _selection_ref(directory, reference, label):
    _selection_require(isinstance(reference, dict) and set(reference) == {'file', 'sha256'}, label + ' reference')
    return aggregate_review_file(directory, reference, label)


def _selection_changes(rows, available):
    _selection_require(isinstance(rows, list), 'change rows')
    result = {}
    for row in rows:
        _selection_require(isinstance(row, dict) and set(row) == {'id', 'before', 'after'}, 'change fields')
        _selection_require(isinstance(row['id'], str) and row['id'] and row['id'] not in result, 'change identity')
        _selection_require(all(type(row[k]) is int and row[k] in available for k in ('before', 'after'))
                           and row['before'] != row['after'], 'change source IDs')
        result[row['id']] = (row['before'], row['after'])
    return result


def prayer_selection_review(directory):
    """Read a finite, pinned separation of display identity and prayer selection."""
    directory = Path(directory).resolve()
    current = json.loads((directory / 'prayer-source-coordinates.json').read_bytes())
    _selection_require(isinstance(current, dict), 'coordinate manifest')
    if 'selectionSuccessor' not in current:
        return None
    reference = current['selectionSuccessor']
    review = json.loads(_selection_ref(directory, reference, 'selection successor'))
    refs = ('baselineGovernors', 'baselineCoordinates', 'baselineMetadata', 'baselineGeometry',
            'manualRows', 'geographicReview', 'mapsReview', 'impactReview')
    base = {'schemaVersion', 'method', 'status', 'sourceSha256', 'identityInputs', 'approvedCorrections',
            'expectedRawChanges', 'expectedManualChanges', 'availableSourceIds', 'rejectedSources',
            'qualification', *refs}
    v2 = {'predecessorCoordinates', 'predecessorSelectionReview'}
    _selection_require(isinstance(review, dict), 'review fields')
    schema = review.get('schemaVersion')
    _selection_require(type(schema) is int and schema in (1, 2), 'review schema')
    _selection_require(set(review) == base | (v2 if schema == 2 else set()), 'review fields')
    _selection_require(review['method'] == 'reviewed_prayer_selection_after_frozen_display_validation'
                       and review['status'] == 'APPROVED_SCOPED_REFERENCE_CORRECTIONS_PENDING_GENERATION', 'review scope')
    loaded = {key: _selection_ref(directory, review[key], key) for key in refs}
    pins = {'baselineCoordinates': 'f56e4c570c5d4c6136dd8d8b9b811b51882452da6cdf5b001a00224c7e8a63c2',
            'baselineMetadata': '52c4b266907a53174e051d36d03e92678de7696b35cd1c6a7e5d985da530983e',
            'baselineGeometry': 'daa6a02848078697f1b5ff71002e6797f5081c6cf2365369b44d530c06bc2fb1'}
    _selection_require(all(review[k]['sha256'] == v for k, v in pins.items()), 'finite baseline pins')
    identity = review['identityInputs']
    _selection_require(isinstance(identity, dict) and set(identity) == {
        'municipalSources', 'curation', 'reviewedBoundaries', 'reviewedPickerGroups'}, 'identity input inventory')
    for key, ref in identity.items():
        _selection_ref(directory, ref, key)
    old = json.loads(loaded['baselineCoordinates'])
    _selection_require(isinstance(old, dict) and 'selectionSuccessor' not in old
                       and isinstance(old.get('corrections'), list) and len(old['corrections']) == 48, 'baseline corrections')
    baseline = old['corrections']
    additions = review['approvedCorrections']
    _selection_require(isinstance(additions, list) and additions, 'approved correction inventory')
    _selection_require(all(isinstance(r, dict) and type(r.get('delegationId')) is int and r['delegationId'] > 0
                           and r.get('referenceKind') == 'inm_published_reference'
                           and r.get('original') != r.get('proposed') for r in additions), 'approved correction inventory')
    current_corrections = current.get('corrections')
    _selection_require(isinstance(current_corrections, list) and len(current_corrections) >= 48
                       and current_corrections[:48] == baseline, 'baseline corrections changed')
    batches = current.get('reviewBatches', [])
    _selection_require(isinstance(batches, list), 'review batches')
    if schema == 1:
        _selection_require(len(additions) == 4 and [r.get('delegationId') for r in additions] == [441, 468, 516, 579], 'V1 approved correction inventory')
        _selection_require(current_corrections == baseline + additions, 'existing corrections changed')
        _selection_require(len(current_corrections) == 52, 'correction count')
        prior_batches = old.get('reviewBatches', [])
        _selection_require(isinstance(prior_batches, list), 'review batches')
        _selection_require(len(batches) == len(prior_batches) + 1 and batches[:-1] == prior_batches, 'historical review batches')
        batch = batches[-1]
        _selection_require(batch.get('appliedSourceIds') == [441, 468, 516, 579]
                           and batch.get('priorCoordinateManifest') == review['baselineCoordinates']
                           and batch.get('changedExistingCorrectionIds') == []
                           and batch.get('preservedOriginalCorrectionCount') == 48, 'additive review batch')
    else:
        pred_coord_ref, pred_review_ref = review['predecessorCoordinates'], review['predecessorSelectionReview']
        _selection_require(isinstance(pred_coord_ref, dict) and set(pred_coord_ref) == {'file', 'sha256'}, 'predecessorCoordinates reference')
        _selection_require(isinstance(pred_review_ref, dict) and set(pred_review_ref) == {'file', 'sha256'}, 'predecessorSelectionReview reference')
        pred = json.loads(_selection_ref(directory, pred_coord_ref, 'predecessorCoordinates'))
        pred_review = json.loads(_selection_ref(directory, pred_review_ref, 'predecessorSelectionReview'))
        _selection_require(isinstance(pred, dict) and isinstance(pred_review, dict), 'predecessor documents')
        _selection_require(pred.get('selectionSuccessor') == pred_review_ref, 'predecessor selection successor')
        pred_corrections = pred.get('corrections')
        _selection_require(isinstance(pred_corrections, list) and len(pred_corrections) >= 48
                           and pred_corrections[:48] == baseline, 'predecessor baseline corrections changed')
        pred_schema = pred_review.get('schemaVersion')
        _selection_require(type(pred_schema) is int and pred_schema in (1, 2), 'predecessor review schema')
        _selection_require(set(pred_review) == base | (v2 if pred_schema == 2 else set()), 'predecessor review fields')
        _selection_require(pred_review.get('method') == review['method'] and pred_review.get('status') == review['status'], 'predecessor review scope')
        _selection_require(pred_review.get('sourceSha256') == review['sourceSha256'], 'predecessor PBF changed')
        _selection_require(all(pred_review.get(k) == review[k] for k in
                               ('baselineGovernors', 'baselineCoordinates', 'baselineMetadata', 'baselineGeometry', 'identityInputs')),
                           'predecessor baseline identity changed')
        _selection_require(pred_review.get('availableSourceIds') == review['availableSourceIds']
                           and pred_review.get('rejectedSources') == review['rejectedSources'], 'predecessor availability changed')
        prev_approved = pred_review.get('approvedCorrections')
        _selection_require(isinstance(prev_approved, list) and prev_approved, 'predecessor approved correction inventory')
        _selection_require(all(isinstance(r, dict) and type(r.get('delegationId')) is int and r['delegationId'] > 0
                               and r.get('referenceKind') == 'inm_published_reference'
                               and r.get('original') != r.get('proposed') for r in prev_approved), 'predecessor approved correction inventory')
        if pred_schema == 1:
            _selection_require(len(prev_approved) == 4 and [r.get('delegationId') for r in prev_approved] == [441, 468, 516, 579], 'V1 predecessor approved correction inventory')
            _selection_require(isinstance(pred_review.get('expectedRawChanges'), list) and len(pred_review['expectedRawChanges']) == 10
                               and isinstance(pred_review.get('expectedManualChanges'), list) and len(pred_review['expectedManualChanges']) == 10, 'predecessor V1 expected changes')
        _selection_require(prev_approved == pred_corrections[48:], 'predecessor approved corrections')
        _selection_require(additions[:len(prev_approved)] == prev_approved and len(additions) > len(prev_approved), 'cumulative approved corrections')
        _selection_require(current_corrections == baseline + additions, 'existing corrections changed')
        _selection_require(current_corrections[:len(pred_corrections)] == pred_corrections, 'predecessor corrections prefix')
        _selection_require(len(current_corrections) > len(pred_corrections), 'new correction required')
        prior_batches = pred.get('reviewBatches', [])
        _selection_require(isinstance(prior_batches, list), 'review batches')
        _selection_require(len(batches) == len(prior_batches) + 1 and batches[:-1] == prior_batches, 'historical review batches')
        batch = batches[-1]
        new_ids = [r['delegationId'] for r in additions[len(prev_approved):]]
        _selection_require(batch.get('appliedSourceIds') == new_ids
                           and batch.get('priorCoordinateManifest') == pred_coord_ref
                           and batch.get('changedExistingCorrectionIds') == []
                           and batch.get('preservedOriginalCorrectionCount') == len(pred_corrections), 'additive review batch')
    ids = [r.get('delegationId') if isinstance(r, dict) else None for r in current_corrections]
    _selection_require(all(type(i) is int and i > 0 for i in ids) and len(ids) == len(set(ids)), 'correction IDs')
    if schema == 1:
        _selection_require(len(ids) == 52, 'correction count')
    available = review['availableSourceIds']
    _selection_require(isinstance(available, list) and len(available) == 258
                       and all(type(i) is int and i > 0 for i in available)
                       and available == sorted(set(available))
                       and review['rejectedSources'] == [{'id': 495, 'reason': 'no_complete_bundled_month'}], 'available sources')
    if schema == 1:
        _selection_require(isinstance(review['expectedRawChanges'], list) and len(review['expectedRawChanges']) == 10
                           and isinstance(review['expectedManualChanges'], list) and len(review['expectedManualChanges']) == 10, 'expected change count')
    _selection_changes(review['expectedRawChanges'], available)
    _selection_changes(review['expectedManualChanges'], available)
    return {'review': review, 'reference': reference, 'loaded': loaded}


def display_identity_coordinate_bytes(directory):
    """Historical proof context only; exported selection uses current references."""
    context = prayer_selection_review(directory)
    return (context['loaded']['baselineCoordinates'] if context is not None
            else (Path(directory) / 'prayer-source-coordinates.json').read_bytes())


def load_prayer_selection_successor(manifest_path, runtime_governors, assets, source_sha256, identity_input_paths):
    import copy
    directory = Path(manifest_path).resolve().parent
    context = prayer_selection_review(directory)
    if context is None:
        return None
    review = context['review']
    _selection_require(review['sourceSha256'] == source_sha256, 'PBF changed')
    _selection_require(set(identity_input_paths) == set(review['identityInputs']), 'identity path keys')
    for key, path in identity_input_paths.items():
        ref = review['identityInputs'][key]
        _selection_require(Path(path).resolve() == (directory / ref['file']).resolve()
                           and hashlib.sha256(Path(path).read_bytes()).hexdigest() == ref['sha256'], 'identity path changed: ' + key)
    old_governors = json.loads(context['loaded']['baselineGovernors'])['gouvernorats']
    proposed = copy.deepcopy(old_governors)
    by_id = {d['id']: (g, d) for g in proposed for d in g['delegations']}
    _selection_require(len(by_id) == sum(len(g['delegations']) for g in proposed), 'duplicate source')
    for correction in review['approvedCorrections']:
        governor, row = by_id[correction['delegationId']]
        _selection_require(governor['id'] == correction['expectedGovernorateId']
                           and correction['expectedNames'] == {k: row[k] for k in ('nomAr', 'nomFr', 'nomEn')}
                           and correction['original'] == {k: row[k] for k in ('lat', 'lng')}, 'source identity or old reference')
        row.update(correction['proposed'])
    message = ('runtime sources differ beyond four approved coordinate pairs' if review['schemaVersion'] == 1
               else 'runtime sources differ beyond approved coordinate pairs')
    _selection_require(proposed == runtime_governors, message)
    # The caller validates all current corrections, including the unchanged
    # 48 original objects. Their source coordinates also remain unchanged here.
    old_tables, old_rejected = available_timetables(old_governors, assets)
    new_tables, new_rejected = available_timetables(runtime_governors, assets)
    for tables, rejected in ((old_tables, old_rejected), (new_tables, new_rejected)):
        _selection_require(sorted(d['id'] for d in tables) == review['availableSourceIds']
                           and rejected == review['rejectedSources'], 'timetable availability changed')
    manual_doc = json.loads(context['loaded']['manualRows'])
    _selection_require(isinstance(manual_doc, dict) and set(manual_doc) == {'schemaVersion', 'rows'}
                       and type(manual_doc['schemaVersion']) is int and manual_doc['schemaVersion'] == 1
                       and isinstance(manual_doc['rows'], list) and len(manual_doc['rows']) == 3336, 'manual inventory')
    seen = set()
    for row in manual_doc['rows']:
        _selection_require(isinstance(row, dict) and set(row) == {
            'id', 'representativeId', 'lat', 'lng', 'members', 'before', 'after'}, 'manual row fields')
        _selection_require(isinstance(row['id'], str) and row['id'] and row['id'] not in seen
                           and isinstance(row['representativeId'], str) and row['representativeId'], 'manual owner ID')
        seen.add(row['id'])
        _selection_require(isinstance(row['members'], list) and row['members']
                           and all(isinstance(i, str) and i for i in row['members'])
                           and len(row['members']) == len(set(row['members'])), 'manual group members')
        _selection_require(all(type(row[k]) in (int, float) and math.isfinite(row[k]) for k in ('lat', 'lng'))
                           and -90 <= row['lat'] <= 90 and -180 <= row['lng'] <= 180, 'manual coordinate')
        _selection_require(all(type(row[k]) is int and row[k] in review['availableSourceIds'] for k in ('before', 'after')), 'manual source')
    context.update(baselineGovernors=old_governors, baselineTimetables=old_tables, runtimeTimetables=new_tables,
                   baselineMetadata=json.loads(context['loaded']['baselineMetadata']), manualRows=manual_doc['rows'])
    return context


def finalize_prayer_selection_successor(context, result, blob):
    if context is None:
        return None
    review = context['review']
    _selection_require(result == context['baselineMetadata'], 'display catalog differs from reviewed frozen baseline')
    _selection_require(hashlib.sha256(blob).hexdigest() == review['baselineGeometry']['sha256'], 'packed geometry changed')
    rows = result['features']; raw = {r['id']: r for r in rows}
    _selection_require(len(rows) == len(raw) == 3484, 'raw identity inventory')
    nearest = lambda row, tables: min(tables, key=lambda d: (distance(row['lat'], row['lng'], d), d['id']))['id']
    old_tables, new_tables = context['baselineTimetables'], context['runtimeTimetables']
    raw_changes, manual_changes, current_manual, pending = {}, {}, [], {}
    groups = defaultdict(set)
    for row in rows:
        before, after = nearest(row, old_tables), nearest(row, new_tables)
        _selection_require(row['delegationId'] == before, 'baseline raw source: ' + row['id'])
        if before != after:
            raw_changes[row['id']] = (before, after)
        pending[row['id']] = after
        groups[row['pickerGroupId']].add(row['id'])
    for row in context['manualRows']:
        representative = raw.get(row['representativeId'])
        members = groups[row['id']] | ({row['id']} if row['id'].startswith('delegation:') else set())
        _selection_require(representative is not None and all(representative[k] == row[k] for k in ('lat', 'lng'))
                           and row['representativeId'] in members and set(row['members']) == members, 'manual representative/group: ' + row['id'])
        before, after = nearest(row, old_tables), nearest(row, new_tables)
        _selection_require(before == row['before'] and after == row['after'], 'manual nearest source: ' + row['id'])
        if before != after:
            manual_changes[row['id']] = (before, after)
        current_manual.append({**{k: row[k] for k in ('id', 'representativeId', 'lat', 'lng')}, 'sourceId': after})
    _selection_require(raw_changes == _selection_changes(review['expectedRawChanges'], review['availableSourceIds'])
                       and manual_changes == _selection_changes(review['expectedManualChanges'], review['availableSourceIds']), 'unreviewed nearest-source changes')
    # Commit only after every identity, geometry, group and selection check passes.
    for row in rows:
        row['delegationId'] = pending[row['id']]
    changes = lambda values: [{'id': i, 'before': b, 'after': a} for i, (b, a) in sorted(values.items())]
    return {'schemaVersion': 1, 'method': review['method'], 'reviewReference': context['reference'],
            'historicalIdentityScope': {k: review[k] for k in ('baselineGovernors', 'baselineCoordinates', 'baselineMetadata', 'baselineGeometry')},
            'currentSourceIds': review['availableSourceIds'], 'rawChangedCount': len(raw_changes),
            'manualChangedCount': len(manual_changes), 'rawChanges': changes(raw_changes),
            'manualChanges': changes(manual_changes), 'currentManualSelections': current_manual,
            'qualification': 'Historical display proofs retain their prior prayer-reference context. This section and exported raw delegationId values give current nearest-prayer selection. Final exclusions, when present, remove explicitly reviewed facilities; retained identities and geometry are unchanged.'}


def reviewed_prayer_coordinate_reference(directory, reference, label):
    """Read a pinned prayer manifest, allowing one audited additive successor."""
    directory = Path(directory).resolve()
    if (not isinstance(reference, dict)
            or reference.get('file') != 'prayer-source-coordinates.json'):
        return aggregate_review_file(directory, reference, label)
    current_path = (directory / 'prayer-source-coordinates.json').resolve()
    if not current_path.is_relative_to(directory):
        raise ValueError(f'Current {label} manifest escapes source directory')
    current_raw = display_identity_coordinate_bytes(directory)
    current_sha = hashlib.sha256(current_raw).hexdigest()
    if reference.get('sha256') == current_sha:
        return current_raw
    expected = reference.get('expectedCorrection')
    if (not isinstance(expected, dict)
            or type(expected.get('delegationId')) is not int
            or expected['delegationId'] <= 0):
        raise ValueError(f'Invalid additive {label} expected correction')

    def read_manifest(raw, name):
        try:
            manifest = json.loads(raw)
        except (TypeError, json.JSONDecodeError) as exc:
            raise ValueError(f'Invalid {name} coordinate manifest') from exc
        corrections = manifest.get('corrections') if isinstance(manifest, dict) else None
        if (not isinstance(manifest, dict) or type(manifest.get('schemaVersion')) is not int
                or manifest.get('schemaVersion') != 1
                or not isinstance(corrections, list) or not corrections):
            raise ValueError(f'Invalid {name} coordinate manifest')
        by_id = {}
        for correction in corrections:
            identifier = correction.get('delegationId') if isinstance(correction, dict) else None
            if type(identifier) is not int or identifier <= 0 or identifier in by_id:
                raise ValueError(f'Invalid or duplicate {name} correction ID')
            by_id[identifier] = correction
        return manifest, by_id

    current, current_by_id = read_manifest(current_raw, 'current')
    batches = current.get('reviewBatches')
    if not isinstance(batches, list):
        raise ValueError(f'Invalid current {label} review batches')
    matches = []
    for batch in batches:
        prior = batch.get('priorCoordinateManifest') if isinstance(batch, dict) else None
        if isinstance(prior, dict) and prior.get('sha256') == reference.get('sha256'):
            matches.append((batch, prior))
    if len(matches) != 1:
        raise ValueError(f'No unique additive review batch for {label}')
    batch, prior_reference = matches[0]
    prior_path = (directory / prior_reference.get('file', '')).resolve()
    if prior_path == current_path:
        raise ValueError(f'Additive {label} prior manifest is current manifest')
    prior_raw = aggregate_review_file(directory, prior_reference, f'{label} prior coordinate manifest')
    prior, prior_by_id = read_manifest(prior_raw, 'prior')
    old_ids = set(prior_by_id)
    current_ids = set(current_by_id)
    added_ids = current_ids - old_ids
    if not old_ids < current_ids:
        raise ValueError(f'Additive {label} must strictly add correction IDs')
    if any(current_by_id[item] != prior_by_id[item] for item in old_ids):
        raise ValueError(f'Additive {label} changed an existing correction')
    identifier = expected['delegationId']
    if (expected != prior_by_id.get(identifier)
            or expected != current_by_id.get(identifier)):
        raise ValueError(f'Additive {label} expected correction does not match both manifests')
    applied = batch.get('appliedSourceIds')
    if (not isinstance(applied, list) or not applied
            or any(type(item) is not int or item <= 0 for item in applied)
            or len(set(applied)) != len(applied) or not set(applied) <= added_ids):
        raise ValueError(f'Invalid additive {label} applied source IDs')
    if (batch.get('changedExistingCorrectionIds') != []
            or batch.get('preservedOriginalCorrectionCount') != len(old_ids)):
        raise ValueError(f'Additive {label} prior correction preservation changed')
    return current_raw


def aggregate_geometry_sha256(geometry):
    # This matches the reviewed exports, preserving their original coordinates
    # and ring order. The containing file is independently byte-pinned as well.
    return hashlib.sha256(json.dumps(mapping(geometry), separators=(',', ':')).encode('utf-8')).hexdigest()


def packed_geometry_bytes(geometry):
    """Encode an already quantized polygon in the existing NPOL ring format."""
    polygons = [geometry] if isinstance(geometry, Polygon) else list(geometry.geoms)
    packed = bytearray(struct.pack('>i', len(polygons)))
    for polygon in polygons:
        rings = [polygon.exterior] + list(polygon.interiors)
        packed.extend(struct.pack('>i', len(rings)))
        for ring in rings:
            coords = list(ring.coords)
            packed.extend(struct.pack('>i', len(coords)))
            for lng, lat in coords:
                packed.extend(struct.pack('>ii', round(lng * SCALE), round(lat * SCALE)))
    return bytes(packed)


def validate_exhaustive_aggregate(directory, review, source, properties, geometry, country):
    """Accept only the reviewed complete set of electoral parts of one imada."""
    code = properties['officialCode']
    if (properties.get('id') != review.get('id')
            or review.get('officialCode') != code or review.get('boundarySourceSha256') != source['sha256']
            or review.get('delegationCode') != code[:4] or review.get('governorateCode') != code[:2]
            or review.get('imadaName') != properties.get('nameAr')):
        raise ValueError('Aggregate administrative identity or boundary source changed')
    inventory = review.get('decreeInventory', {})
    if (not isinstance(inventory, dict) or inventory.get('completeForDelegation') is not True
            or inventory.get('delegationCode') != code[:4]
            or not isinstance(inventory.get('rows'), list) or not inventory['rows']):
        raise ValueError('Aggregate needs the reviewed complete delegation decree inventory')
    inventory_rows, all_circle_names = {}, set()
    for row in inventory['rows']:
        row_code = row.get('officialCode') if isinstance(row, dict) else None
        if (not isinstance(row_code, str) or not re.fullmatch(r'[0-9]{6}', row_code)
                or row_code[:4] != code[:4] or row_code in inventory_rows
                or not isinstance(row.get('imadaName'), str) or not clean(row['imadaName'])
                or not isinstance(row.get('circleNames'), list) or not row['circleNames']
                or any(not isinstance(name, str) or not clean(name) for name in row['circleNames'])):
            raise ValueError('Invalid aggregate decree inventory row')
        names_in_row = set(row['circleNames'])
        if len(names_in_row) != len(row['circleNames']) or names_in_row & all_circle_names:
            raise ValueError('Repeated electoral circle in aggregate decree inventory')
        all_circle_names.update(names_in_row)
        inventory_rows[row_code] = row
    target_row = inventory_rows.get(code)
    if target_row is None or target_row['imadaName'] != review['imadaName']:
        raise ValueError('Aggregate imada is absent from the reviewed decree inventory')
    parts = review.get('parts')
    if not isinstance(parts, list) or len(parts) < 2:
        raise ValueError('Aggregate needs at least two explicitly reviewed electoral parts')
    reviewed_parts = {}
    for part in parts:
        identifier = part.get('id') if isinstance(part, dict) else None
        if (not isinstance(identifier, str) or not identifier or identifier in reviewed_parts
                or part.get('parentImadaCode') != code
                or not isinstance(part.get('circleName'), str) or not clean(part['circleName'])
                or not isinstance(part.get('sourcePdfURL'), str)
                or not part['sourcePdfURL'].startswith('https://www.isie.tn/')
                or any(not isinstance(part.get(key), str) or not re.fullmatch(r'[0-9a-f]{64}', part[key])
                       for key in ('sourcePdfSha256', 'geometrySha256'))):
            raise ValueError('Invalid or repeated aggregate source part')
        reviewed_parts[identifier] = part
    required_names = target_row['circleNames']
    if (len(parts) != len(required_names)
            or {part['circleName'] for part in parts} != set(required_names)):
        raise ValueError('Aggregate does not contain exactly the exhaustive decree circle set')
    collection = json.loads(aggregate_review_file(directory, review.get('sourceParts'), 'source parts'))
    if (collection.get('type') != 'FeatureCollection' or collection.get('sourceId') != source['id']
            or not isinstance(collection.get('features'), list) or len(collection['features']) != len(parts)):
        raise ValueError('Aggregate source-parts collection inventory changed')
    crs = collection.get('crs', {}).get('properties', {}).get('name')
    if crs and crs not in ('urn:ogc:def:crs:OGC:1.3:CRS84', 'EPSG:4326', 'urn:ogc:def:crs:EPSG::4326'):
        raise ValueError('Aggregate source parts must use WGS84 longitude/latitude')
    part_features = {}
    for feature in collection['features']:
        identifier = feature.get('id') if isinstance(feature, dict) else None
        if not isinstance(identifier, str) or identifier not in reviewed_parts or identifier in part_features:
            raise ValueError('Aggregate source-parts feature IDs changed or repeated')
        part_features[identifier] = feature
    # The aggregate's own provenance must agree with the independently pinned
    # parts file and review. A convenient first PDF cannot stand for both parts.
    declared = properties.get('sourceParts')
    if not isinstance(declared, list) or len(declared) != len(parts):
        raise ValueError('Aggregate is missing its complete source-parts provenance')
    declared_parts = {}
    for part in declared:
        identifier = part.get('circleId') if isinstance(part, dict) else None
        if not isinstance(identifier, str) or identifier not in reviewed_parts or identifier in declared_parts:
            raise ValueError('Aggregate provenance part IDs changed or repeated')
        declared_parts[identifier] = part
    union = None
    for identifier, part in reviewed_parts.items():
        feature = part_features[identifier]
        if feature.get('type') != 'Feature' or not isinstance(feature.get('properties'), dict):
            raise ValueError('Invalid aggregate source-parts feature')
        expected = {'circleId': identifier, 'circleNameAr': part['circleName'], 'parentImadaCode': code,
                    'sourcePdfURL': part['sourcePdfURL'], 'sourcePdfSha256': part['sourcePdfSha256'],
                    'sourceGeometrySha256': part['geometrySha256']}
        if any(record.get(key) != value for record in (feature['properties'], declared_parts[identifier])
               for key, value in expected.items()):
            raise ValueError('Aggregate part identity, PDF or geometry provenance changed')
        footprint = shape(feature.get('geometry'))
        if (footprint.geom_type not in ('Polygon', 'MultiPolygon') or footprint.is_empty
                or not footprint.is_valid or footprint.has_z
                or not all(math.isfinite(value) for value in footprint.bounds) or not country.covers(footprint)
                or aggregate_geometry_sha256(footprint) != part['geometrySha256']):
            raise ValueError('Invalid or changed aggregate source-part geometry')
        # No rounding, snapping, buffering, repair or gap filling is permitted.
        union = footprint if union is None else union.union(footprint)
    if (not union.equals(geometry) or not isinstance(review.get('geometrySha256'), str)
            or not re.fullmatch(r'[0-9a-f]{64}', review['geometrySha256'])
            or aggregate_geometry_sha256(geometry) != review['geometrySha256']
            or properties.get('sourceGeometrySha256') != review['geometrySha256']):
        raise ValueError('Aggregate geometry is not the exact reviewed unrounded parts union')
    quantization = review.get('quantization', {})
    if (not isinstance(quantization, dict) or type(quantization.get('coordinateScale')) is not int
            or quantization['coordinateScale'] != SCALE
            or not isinstance(quantization.get('packedGeometrySha256'), str)
            or not re.fullmatch(r'[0-9a-f]{64}', quantization['packedGeometrySha256'])):
        raise ValueError('Aggregate coordinate quantization needs explicit review')
    aggregate_review_file(directory, quantization.get('review'), 'quantization review')
    quantized = set_precision(geometry, 1 / SCALE)
    if (quantized.is_empty or not quantized.is_valid or quantized.geom_type not in ('Polygon', 'MultiPolygon')
            or not country.covers(quantized)):
        raise ValueError('Invalid quantized aggregate geometry')
    # Pin the actual existing packed-ring representation, not a numeric error
    # threshold that could silently accept different lost source-gap branches.
    packed = packed_geometry_bytes(quantized)
    if hashlib.sha256(packed).hexdigest() != quantization['packedGeometrySha256']:
        raise ValueError('Aggregate packed coordinate quantization changed')


def load_reviewed_boundary_manifest_for_lineage(directory, reference, reviewed_boundaries, official_report):
    if not isinstance(directory, Path): raise ValueError('directory must be a Path')
    directory = directory.resolve()
    if not isinstance(reference, dict): raise ValueError('reference must be a dictionary')
    ref_file, ref_sha = reference.get('file'), reference.get('sha256')
    if not isinstance(ref_file, str) or not ref_file: raise ValueError('reference.file must be a non-empty string')
    if not isinstance(ref_sha, str) or not re.fullmatch(r'[0-9a-f]{64}', ref_sha): raise ValueError('reference.sha256 must be lowercase 64-hex')
    if reviewed_boundaries is None: raise ValueError('reviewed_boundaries is required')
    if not isinstance(reviewed_boundaries, (str, Path)): raise ValueError('reviewed_boundaries must be a path')
    if not isinstance(official_report, dict): raise ValueError('official_report must be a dictionary')
    official_sha = official_report.get('manifestSha256')
    if not isinstance(official_sha, str): raise ValueError('official_report.manifestSha256 must be a string')
    active_path = (directory / ref_file).resolve()
    if not active_path.is_relative_to(directory): raise ValueError('reference escapes source directory')
    if active_path != Path(reviewed_boundaries).resolve(): raise ValueError('reference does not point to reviewed_boundaries')
    try:
        active_raw = active_path.read_bytes()
    except OSError as exc:
        raise ValueError('unable to read reviewed boundary manifest') from exc
    active_sha = hashlib.sha256(active_raw).hexdigest()
    if active_sha != official_sha: raise ValueError('active manifest checksum does not match official_report.manifestSha256')
    active = json.loads(active_raw)
    if not isinstance(active, dict) or active.get('schemaVersion') != 1 or not isinstance(active.get('sources'), list): raise ValueError('active manifest must be a schemaVersion 1 object with sources list')
    if ref_sha == active_sha: return active
    compat_ref = active.get('lineageCompatibility')
    if not isinstance(compat_ref, dict): raise ValueError('active manifest requires a lineageCompatibility pin')
    proof = json.loads(aggregate_review_file(directory, compat_ref, 'lineage compatibility'))
    if not isinstance(proof, dict): raise ValueError('lineage compatibility document must be an object')
    has_aggregate_successor = 'aggregateScopeSuccessor' in proof
    expected_proof_keys = {'schemaVersion', 'method', 'sourceSha256', 'previousManifest', 'addedSources', 'scopeSuccessor', 'evidence'}
    if has_aggregate_successor:
        expected_proof_keys.add('aggregateScopeSuccessor')
    if set(proof.keys()) != expected_proof_keys: raise ValueError('lineage compatibility document has unexpected keys')
    if proof['schemaVersion'] != 1 or proof['method'] != 'reviewed_append_only_boundary_extension': raise ValueError('lineage compatibility must be schemaVersion 1 reviewed_append_only_boundary_extension')
    source_sha = proof['sourceSha256']
    if not isinstance(source_sha, str) or not re.fullmatch(r'[0-9a-f]{64}', source_sha): raise ValueError('lineage compatibility sourceSha256 must be lowercase 64-hex')
    added_sources = proof['addedSources']
    if not isinstance(added_sources, list) or not added_sources or any(not isinstance(s, dict) for s in added_sources): raise ValueError('lineage compatibility addedSources must be a non-empty list of objects')
    evidence = proof['evidence']
    if not isinstance(evidence, list) or not evidence: raise ValueError('lineage compatibility evidence must be a non-empty list')
    for index, pin in enumerate(evidence): aggregate_review_file(directory, pin, f'lineage evidence {index}')
    previous_ref = proof['previousManifest']
    if not isinstance(previous_ref, dict): raise ValueError('previousManifest must be a dictionary')
    previous_file = previous_ref.get('file')
    if not isinstance(previous_file, str) or not previous_file: raise ValueError('previousManifest.file must be a non-empty string')
    previous_sha = previous_ref.get('sha256')
    if not isinstance(previous_sha, str) or not re.fullmatch(r'[0-9a-f]{64}', previous_sha): raise ValueError('previousManifest.sha256 must be lowercase 64-hex')
    previous_path = (directory / previous_file).resolve()
    if previous_path == active_path: raise ValueError('previousManifest must not resolve to the active manifest')
    previous = json.loads(aggregate_review_file(directory, previous_ref, 'previous manifest'))
    if not isinstance(previous, dict) or previous.get('schemaVersion') != 1 or not isinstance(previous.get('sources'), list) or not previous['sources']: raise ValueError('previous manifest must be schemaVersion 1 with non-empty sources')
    if active['sources'] != previous['sources'] + added_sources: raise ValueError('active sources must equal previous sources plus addedSources')
    old_provider_ids, old_record_ids = set(), set()
    for source in previous['sources']:
        if not isinstance(source, dict): raise ValueError('previous source must be an object')
        provider_id = source.get('id')
        if not isinstance(provider_id, str) or not provider_id or provider_id in old_provider_ids: raise ValueError('previous source ids must be unique non-empty strings')
        old_provider_ids.add(provider_id)
        if source.get('sourceSha256') != source_sha or not isinstance(source.get('records'), list): raise ValueError('previous source sourceSha256/records invalid')
        for record in source.get('records'):
            if not isinstance(record, dict): raise ValueError('previous source record must be an object')
            record_id = record.get('id')
            if not isinstance(record_id, str) or not record_id or record_id in old_record_ids: raise ValueError('previous source record ids must be unique non-empty strings')
            old_record_ids.add(record_id)
    new_provider_ids, new_record_ids, whole_record_ids, aggregate_record_ids = set(), set(), [], []
    for source in added_sources:
        provider_id = source.get('id')
        if not isinstance(provider_id, str) or not provider_id or provider_id in new_provider_ids or provider_id in old_provider_ids: raise ValueError('new source ids must be unique non-empty strings disjoint from previous ids')
        new_provider_ids.add(provider_id)
        if source.get('sourceSha256') != source_sha or not isinstance(source.get('records'), list) or not source.get('records'): raise ValueError('new source sourceSha256/records invalid')
        for record in source.get('records'):
            if not isinstance(record, dict): raise ValueError('new source record must be an object')
            record_id = record.get('id')
            scope_kind = record.get('scopeKind', 'whole_imada')
            if (not isinstance(record_id, str) or not record_id or record_id in new_record_ids or record_id in old_record_ids
                    or record.get('action') not in ('replace', 'add')
                    or scope_kind not in ('whole_imada', 'exhaustive_electoral_parts')
                    or (scope_kind == 'exhaustive_electoral_parts' and not has_aggregate_successor)):
                raise ValueError('new source record id/action/scopeKind invalid')
            new_record_ids.add(record_id)
            if scope_kind == 'whole_imada':
                whole_record_ids.append(record_id)
            else:
                aggregate_record_ids.append(record_id)
    if has_aggregate_successor and not aggregate_record_ids: raise ValueError('aggregateScopeSuccessor requires at least one new aggregate id')
    active_exclusions = {'sources', 'administrativeScopeReview', 'lineageCompatibility'}
    previous_exclusions = {'sources', 'administrativeScopeReview', 'lineageCompatibility'}
    if has_aggregate_successor:
        active_exclusions.add('aggregateScopeReview')
        previous_exclusions.add('aggregateScopeReview')
    active_other = {k: v for k, v in active.items() if k not in active_exclusions}; previous_other = {k: v for k, v in previous.items() if k not in previous_exclusions}
    if active_other != previous_other: raise ValueError('active manifest changes fields outside the reviewed append-only extension')
    new_scope_ref = proof['scopeSuccessor']
    old_scope_ref = previous.get('administrativeScopeReview')
    if not isinstance(new_scope_ref, dict) or active.get('administrativeScopeReview') != new_scope_ref or not isinstance(old_scope_ref, dict): raise ValueError('administrativeScopeReview pins must match scopeSuccessor and exist in previous manifest')
    old_scope = json.loads(aggregate_review_file(directory, old_scope_ref, 'previous administrative scope')); new_scope = json.loads(aggregate_review_file(directory, new_scope_ref, 'new administrative scope'))
    if not isinstance(old_scope, dict) or old_scope.get('schemaVersion') != 1 or not isinstance(old_scope.get('records'), list) or not isinstance(new_scope, dict) or new_scope.get('schemaVersion') != 1 or not isinstance(new_scope.get('records'), list): raise ValueError('administrative scope documents must be schemaVersion 1 objects with records lists')
    old_records, new_records = old_scope['records'], new_scope['records']
    if (len(new_records) < len(old_records) or new_records[:len(old_records)] != old_records
            or {k: v for k, v in new_scope.items() if k != 'records'} != {k: v for k, v in old_scope.items() if k != 'records'}):
        raise ValueError('new administrative scope must retain old records as an exact prefix and change only records')
    seen_scope_ids = set()
    for record in new_records:
        record_id = record.get('id') if isinstance(record, dict) else None
        if not isinstance(record_id, str) or not record_id or record_id in seen_scope_ids: raise ValueError('scope record ids must be unique non-empty strings')
        seen_scope_ids.add(record_id)
    if [record['id'] for record in new_records[len(old_records):]] != whole_record_ids: raise ValueError('appended scope record ids must exactly match added whole_imada source record ids')
    if has_aggregate_successor:
        new_aggregate_scope_ref = proof['aggregateScopeSuccessor']
        old_aggregate_scope_ref = previous.get('aggregateScopeReview')
        if not isinstance(new_aggregate_scope_ref, dict) or active.get('aggregateScopeReview') != new_aggregate_scope_ref or not isinstance(old_aggregate_scope_ref, dict): raise ValueError('aggregateScopeReview pins must match aggregateScopeSuccessor and exist in previous manifest')
        old_aggregate_scope = json.loads(aggregate_review_file(directory, old_aggregate_scope_ref, 'previous aggregate scope')); new_aggregate_scope = json.loads(aggregate_review_file(directory, new_aggregate_scope_ref, 'new aggregate scope'))
        if not isinstance(old_aggregate_scope, dict) or old_aggregate_scope.get('schemaVersion') != 1 or not isinstance(old_aggregate_scope.get('records'), list) or not isinstance(new_aggregate_scope, dict) or new_aggregate_scope.get('schemaVersion') != 1 or not isinstance(new_aggregate_scope.get('records'), list): raise ValueError('aggregate scope documents must be schemaVersion 1 objects with records lists')
        old_aggregate_records, new_aggregate_records = old_aggregate_scope['records'], new_aggregate_scope['records']
        if (len(new_aggregate_records) < len(old_aggregate_records) or new_aggregate_records[:len(old_aggregate_records)] != old_aggregate_records
                or {k: v for k, v in new_aggregate_scope.items() if k != 'records'} != {k: v for k, v in old_aggregate_scope.items() if k != 'records'}):
            raise ValueError('new aggregate scope must retain old records as an exact prefix and change only records')
        seen_aggregate_scope_ids = set()
        for record in new_aggregate_records:
            record_id = record.get('id') if isinstance(record, dict) else None
            if not isinstance(record_id, str) or not record_id or record_id in seen_aggregate_scope_ids: raise ValueError('aggregate scope record ids must be unique non-empty strings')
            seen_aggregate_scope_ids.add(record_id)
        if [record['id'] for record in new_aggregate_records[len(old_aggregate_records):]] != aggregate_record_ids: raise ValueError('appended aggregate scope record ids must exactly match added exhaustive_electoral_parts source record ids')
    if ref_sha != previous_sha:
        load_reviewed_boundary_manifest_for_lineage(
            directory,
            {'file': previous_file, 'sha256': ref_sha},
            previous_path,
            {'manifestSha256': previous_sha},
        )
    return active


def load_reviewed_boundaries(manifest_path, areas, source_sha256):
    """Overlay individually reviewed, byte-pinned official sector footprints.

    Stable IDs survive replacement. No snapping, clipping, inferred boundaries or
    automatic geometry repair occurs here; source uncertainty remains eligible
    for the same conflict detection as every other polygon.
    """
    manifest_path = Path(manifest_path)
    raw = manifest_path.read_bytes()
    manifest = json.loads(raw)
    if manifest.get('schemaVersion') != 1 or not isinstance(manifest.get('sources'), list):
        raise ValueError('Invalid reviewed boundary manifest')
    # Electoral circles sometimes split an imada or combine parts of several.
    # A matching label alone cannot establish a whole administrative sector.
    scope_reference = manifest.get('administrativeScopeReview', {})
    if (not isinstance(scope_reference, dict) or not scope_reference.get('file')
            or not re.fullmatch(r'[0-9a-f]{64}', scope_reference.get('sha256', ''))):
        raise ValueError('Missing reviewed electoral-to-administrative boundary correspondence')
    scope_path = (manifest_path.parent / scope_reference['file']).resolve()
    if not scope_path.is_relative_to(manifest_path.parent.resolve()):
        raise ValueError('Administrative scope review escapes source directory')
    scope_raw = scope_path.read_bytes()
    if hashlib.sha256(scope_raw).hexdigest() != scope_reference['sha256']:
        raise ValueError('Administrative scope review checksum mismatch')
    scope_review = json.loads(scope_raw)
    primary = scope_review.get('primarySource', {})
    if (scope_review.get('schemaVersion') != 1 or not isinstance(primary, dict)
            or not primary.get('url', '').startswith('https://www.isie.tn/')
            or not re.fullmatch(r'[0-9a-f]{64}', primary.get('sha256', ''))
            or not isinstance(scope_review.get('records'), list)):
        raise ValueError('Invalid administrative scope review provenance')
    scope_records = {}
    for record in scope_review['records']:
        identifier = record.get('id')
        if (not isinstance(identifier, str) or not identifier or identifier in scope_records
                or record.get('conclusion') != 'whole_imada'
                or any(type(record.get(key)) is not int or record[key] < 1
                       for key in ('pdfPage', 'jortPage'))
                or any(not isinstance(record.get(key), str) or not clean(record[key])
                       for key in ('imadaName', 'circleName'))):
            raise ValueError(f'Unreviewed or partial administrative sector boundary: {identifier}')
        circle_name = clean(record['circleName'])
        # The decree appends the delegation to some whole-imada homonyms.
        # Remove only that exact, independently reviewed parent suffix.
        suffix = re.search(r'\s*-\s*\(([^()]+)\)\s*$', circle_name)
        if suffix and norm(suffix.group(1)) == norm(record.get('delegationName', '')):
            circle_name = circle_name[:suffix.start()]
        if norm(record['imadaName']) != norm(circle_name):
            raise ValueError(f'Administrative circle name correspondence needs review: {identifier}')
        scope_records[identifier] = record
    whole_ids, aggregate_ids = set(), set()
    for source in manifest['sources']:
        for record in source.get('records', []):
            scope_kind = record.get('scopeKind', 'whole_imada')
            if scope_kind == 'whole_imada':
                whole_ids.add(record.get('id'))
            elif scope_kind == 'exhaustive_electoral_parts':
                aggregate_ids.add(record.get('id'))
            else:
                raise ValueError('Unknown reviewed boundary administrative scope kind')
    if whole_ids & aggregate_ids:
        raise ValueError('Boundary cannot have both whole-circle and aggregate scope')
    expected_ids = whole_ids
    if set(scope_records) != expected_ids:
        raise ValueError('Administrative scope review inventory needs review')
    aggregate_records = {}
    aggregate_reference = manifest.get('aggregateScopeReview')
    if aggregate_reference is not None:
        aggregate_review = json.loads(aggregate_review_file(manifest_path.parent, aggregate_reference, 'scope review'))
        aggregate_primary = aggregate_review.get('primarySource', {})
        if (aggregate_review.get('schemaVersion') != 1 or not isinstance(aggregate_primary, dict)
                or any(aggregate_primary.get(key) != primary.get(key) for key in ('url', 'sha256'))
                or not isinstance(aggregate_review.get('records'), list)):
            raise ValueError('Invalid aggregate administrative scope review provenance')
        for record in aggregate_review['records']:
            identifier = record.get('id') if isinstance(record, dict) else None
            if (not isinstance(identifier, str) or not identifier or identifier in aggregate_records
                    or record.get('conclusion') != 'whole_imada_from_exhaustive_electoral_parts'
                    or any(type(record.get(key)) is not int or record[key] < 1 for key in ('pdfPage', 'jortPage'))
                    or not isinstance(record.get('imadaName'), str) or not clean(record['imadaName'])):
                raise ValueError('Invalid or unreviewed exhaustive aggregate scope')
            aggregate_records[identifier] = record
    if set(aggregate_records) != aggregate_ids:
        raise ValueError('Aggregate administrative scope review inventory needs review')
    by_id = {area['id']: area for area in areas}
    if len(by_id) != len(areas):
        raise ValueError('Duplicate source area ID before boundary overlay')
    country = next(area['shape'] for area in areas if area['kind'] == 'country')
    sources, contexts, targets, applications = {}, {}, {}, []
    seen_ids, seen_codes = set(), set()
    for source in manifest['sources']:
        for key in ('id', 'provider', 'url', 'file', 'sha256', 'sourceSha256', 'records', 'review'):
            if not source.get(key):
                raise ValueError(f'Missing reviewed boundary source field: {key}')
        if source['sourceSha256'] != source_sha256 or source['id'] in sources or source['id'] == 'osm':
            raise ValueError('Reviewed boundary source changed or duplicated')
        path = (manifest_path.parent / source['file']).resolve()
        if not path.is_relative_to(manifest_path.parent.resolve()):
            raise ValueError('Reviewed boundary file escapes source directory')
        data_raw = path.read_bytes()
        if hashlib.sha256(data_raw).hexdigest() != source['sha256']:
            raise ValueError('Reviewed boundary data checksum mismatch')
        collection = json.loads(data_raw)
        records = source['records']
        if (collection.get('type') != 'FeatureCollection' or not isinstance(records, list)
                or not isinstance(collection.get('features'), list)
                or len(collection['features']) != len(records)):
            raise ValueError('Unexpected reviewed boundary feature count')
        features = {feature.get('id'): feature for feature in collection['features']}
        if len(features) != len(records) or set(features) != {record.get('id') for record in records}:
            raise ValueError('Reviewed boundary identity inventory mismatch')
        crs = collection.get('crs', {}).get('properties', {}).get('name')
        if crs and crs not in ('urn:ogc:def:crs:OGC:1.3:CRS84', 'EPSG:4326', 'urn:ogc:def:crs:EPSG::4326'):
            raise ValueError('Reviewed boundary geometry must use WGS84 longitude/latitude')
        for record in records:
            identifier = record['id']
            if not isinstance(identifier, str) or not identifier or identifier in seen_ids:
                raise ValueError('Invalid or repeated reviewed boundary ID')
            feature = features[identifier]
            properties = feature.get('properties', {})
            is_aggregate = identifier in aggregate_records
            if (feature.get('type') != 'Feature' or properties.get('sourceId') != source['id']
                    or (not is_aggregate and (not properties.get('sourcePdfURL', '').startswith('https://www.isie.tn/')
                                             or not re.fullmatch(r'[0-9a-f]{64}', properties.get('sourcePdfSha256', ''))))):
                raise ValueError('Missing reviewed official boundary provenance')
            code = properties.get('officialCode', '')
            if (not isinstance(code, str) or not re.fullmatch(r'[0-9]{6}', code)
                    or code != record.get('officialCode')
                    or properties.get('delegationCode') != code[:4]
                    or properties.get('governorateCode') != code[:2]):
                raise ValueError('Reviewed official boundary codes disagree')
            scope_record = aggregate_records[identifier] if is_aggregate else scope_records[identifier]
            if (scope_record.get('officialCode') != code
                    or scope_record.get('boundarySourceSha256') != source['sha256']):
                raise ValueError('Administrative scope review identity or boundary source changed')
            if code in seen_codes:
                raise ValueError(f'Duplicate reviewed official boundary code: {code}')
            old = by_id.get(identifier)
            expected = record.get('expectedOriginalTags')
            if record.get('action') == 'replace':
                if (old is None or old['kind'] != 'sector' or not isinstance(expected, dict) or not expected
                        or any(old['tags'].get(key) != value for key, value in expected.items())):
                    raise ValueError(f'Reviewed boundary original identity changed: {identifier}')
            elif record.get('action') == 'add':
                if old is not None or identifier != f'isie:sector:{code}' or expected:
                    raise ValueError('New official sector must have a new explicit identity')
            else:
                raise ValueError('Unknown reviewed boundary operation')
            tags = {'boundary': 'administrative', 'admin_level': '6', 'ref:tn:codegeo': code,
                    'name:ar': properties.get('nameAr'), 'name:fr': properties.get('nameFr')}
            if any(not isinstance(tags[key], str) or not clean(tags[key]) for key in ('name:ar', 'name:fr')):
                raise ValueError('Missing reviewed official boundary names')
            # Aliases must be part of the accepted identity record, never copied
            # indiscriminately from an older possibly mislabeled polygon.
            aliases = record.get('aliases', [])
            if not isinstance(aliases, list) or any(not isinstance(value, str) or not clean(value) for value in aliases):
                raise ValueError('Invalid reviewed boundary aliases')
            if aliases:
                tags['alt_name'] = ';'.join(aliases)
            geometry = shape(feature.get('geometry'))
            if (geometry.geom_type not in ('Polygon', 'MultiPolygon') or geometry.is_empty or not geometry.is_valid
                    or geometry.has_z or not all(math.isfinite(value) for value in geometry.bounds)
                    or not country.covers(geometry)):
                raise ValueError(f'Invalid or outside-country reviewed boundary: {identifier}')
            if is_aggregate:
                validate_exhaustive_aggregate(manifest_path.parent, scope_record, source, properties, geometry, country)
            governors = [area for area in areas if area['kind'] == 'governorate'
                         and area['tags'].get('ref:tn:codegeo') == code[:2]]
            delegations = [area for area in areas if area['kind'] == 'delegation'
                           and area['tags'].get('ref:tn:codegeo') == code[:4]]
            if len(governors) != 1 or len(delegations) != 1:
                raise ValueError('Reviewed boundary needs an unambiguous administrative identity')
            targets.update({area['id']: area for area in governors + delegations})
            contexts[identifier] = {'id': identifier, 'action': 'administrative_context',
                                    'governorateId': governors[0]['id'], 'delegationId': delegations[0]['id'],
                                    'sourceId': source['id']}
            by_id[identifier] = {'id': identifier, 'tags': tags, 'kind': 'sector',
                                 'shape': geometry, 'sourceId': source['id']}
            applications.append({'id': identifier, 'action': record['action'], 'officialCode': code,
                                 'sourceId': source['id']})
            seen_ids.add(identifier)
            seen_codes.add(code)
        sources[source['id']] = {key: value for key, value in source.items() if key not in ('file', 'records')}
    # Replacements may change a legacy identity. Check the final inventory so an
    # added or corrected code cannot also survive on an unreplaced old sector.
    for code in seen_codes:
        claims = [area['id'] for area in by_id.values() if area['kind'] == 'sector'
                  and area['tags'].get('ref:tn:codegeo') == code]
        if len(claims) != 1:
            raise ValueError(f'Reviewed official boundary has unresolved legacy code claims: {code}: {claims}')
    report = {'manifestSha256': hashlib.sha256(raw).hexdigest(), 'applications': applications,
              'administrativeScopeReviewSha256': scope_reference['sha256'],
              'sources': sources, 'scope': 'Reviewed source footprints; overlapping registrations retain conflict handling.'}
    if aggregate_reference is not None:
        report['aggregateScopeReviewSha256'] = aggregate_reference['sha256']
    return list(by_id.values()), sources, contexts, targets, report


def load_catalog_curation(manifest_path, source_sha256, areas, nodes):
    """Apply reviewed source-specific decisions, never guesses from a name alone.

    The input checksum and expected tags make a changed source fail closed until
    the decisions have been reviewed again. Original source files and borders
    remain unchanged; reviewed name tags replace only derived naming metadata.
    """
    manifest_path = Path(manifest_path)
    raw = manifest_path.read_bytes()
    manifest = json.loads(raw)
    if (manifest.get('schemaVersion') != 1 or manifest.get('sourceSha256') != source_sha256
            or not isinstance(manifest.get('targets'), list)
            or not isinstance(manifest.get('decisions'), list)):
        raise ValueError('Catalog curation needs review for this source checksum')
    by_id = {obj['id']: obj for obj in areas + nodes}
    if len(by_id) != len(areas) + len(nodes):
        raise ValueError('Duplicate source IDs while loading catalog curation')

    def verify_expected(entry):
        identifier = entry.get('id')
        obj = by_id.get(identifier)
        expected_tags = entry.get('expectedTags')
        if (obj is None or obj['kind'] != entry.get('expectedKind')
                or not isinstance(expected_tags, dict) or not expected_tags
                or any(obj['tags'].get(key) != value for key, value in expected_tags.items())):
            raise ValueError(f'Catalog curation source assertion changed: {identifier}')
        return obj

    targets = {}
    for target in manifest.get('targets', []):
        obj = verify_expected(target)
        if obj['id'] in targets or obj['kind'] not in ('delegation', 'governorate'):
            raise ValueError('Invalid or duplicate catalog curation target')
        targets[obj['id']] = obj
    for pending in manifest.get('pendingReviews', []):
        verify_expected(pending)
        if not pending.get('reason') or not pending.get('evidence'):
            raise ValueError('Undocumented pending catalog review')
    rules = {}
    for rule in manifest.get('decisions', []):
        obj = verify_expected(rule)
        if obj['id'] in rules or not rule.get('reason') or not rule.get('evidence'):
            raise ValueError('Duplicate or undocumented catalog curation decision')
        if rule.get('action') == 'administrative_context':
            parent_names = rule.get('parentNames')
            if (obj['kind'] != 'sector'
                    or bool(rule.get('delegationId')) == bool(parent_names)):
                raise ValueError('Administrative curation must identify a sector and one parent identity')
            if parent_names and (not isinstance(parent_names, list)
                                 or any(not isinstance(value, str) or not clean(value) for value in parent_names)):
                raise ValueError('Invalid reviewed parent names')
            for field, kind in (('delegationId', 'delegation'), ('governorateId', 'governorate')):
                if field in rule and (rule[field] not in targets or targets[rule[field]]['kind'] != kind):
                    raise ValueError(f'Unknown catalog curation {field}')
        elif rule.get('action') == 'preserve_point':
            if 'point' not in obj or 'shape' in obj:
                raise ValueError('Only an existing source point can retain a saved locality identity')
            if 'pointRetentionReview' in rule:
                aggregate_review_file(manifest_path.parent, rule['pointRetentionReview'], 'point retention review')
        elif rule.get('action') == 'reviewed_locality_classification':
            allowed = {'id', 'expectedKind', 'expectedTags', 'action', 'targetKind', 'reason', 'evidence', 'reviewEvidence'}
            if (set(rule) != allowed or obj['kind'] != 'sector' or 'shape' not in obj or 'point' in obj
                    or rule.get('targetKind') != 'locality' or rule['expectedTags'] != obj['tags']):
                raise ValueError('Reviewed locality classification requires one exact polygon sector source and locality target')
            validate_reviewed_locality_classification(manifest_path, obj, rule, source_sha256)
        elif rule.get('action') == 'locality_context':
            # This is deliberately separate from sector membership curation.
            # It cannot carry name, manual-reference or governorate changes.
            allowed = {'id', 'expectedKind', 'expectedTags', 'action', 'reason', 'evidence', 'reviewEvidence'}
            if (set(rule) != allowed or obj['kind'] not in PLACES | {'residential', 'locality'}
                    or rule['expectedTags'] != obj['tags']):
                raise ValueError('Locality context requires exact non-administrative source assertions')
            aggregate_review_file(manifest_path.parent, rule['reviewEvidence'], 'locality context review')
        elif rule.get('action') not in ('exclude', 'name_tags'):
            raise ValueError('Unknown catalog curation action')
        if 'replacementId' in rule or 'replacementReview' in rule:
            if (rule['action'] != 'exclude' or obj['kind'] != 'sector'
                    or not isinstance(rule.get('replacementId'), str)
                    or rule['replacementId'] == obj['id']):
                raise ValueError('Saved replacement requires an explicitly retired sector')
            aggregate_review_file(manifest_path.parent, rule.get('replacementReview'), 'saved replacement review')
        # A name correction can accompany an already reviewed administrative
        # membership decision. Both sets of assertions remain mandatory.
        if rule.get('action') == 'name_tags' or 'nameTags' in rule:
            if (rule['action'] not in ('name_tags', 'administrative_context')
                    and not (rule['action'] == 'preserve_point' and 'pointRetentionReview' in rule)):
                raise ValueError('This curation action cannot also replace name tags')
            name_tags = rule.get('nameTags')
            if (not isinstance(name_tags, dict) or not name_tags
                    or any(not isinstance(key, str) or not is_current_name_tag(key)
                           or not isinstance(value, str) or not clean(value)
                           for key, value in name_tags.items())
                    or not names(name_tags)):
                raise ValueError('Invalid reviewed name tags')
            # Replacing the complete consumed name set prevents an unreviewed
            # translation/alias from silently surviving a primary-name fix.
            source_names = {key: value for key, value in obj['tags'].items() if is_current_name_tag(key)}
            expected_names = {key: value for key, value in rule['expectedTags'].items() if is_current_name_tag(key)}
            if source_names != expected_names:
                raise ValueError(f"Incomplete reviewed source name assertions: {obj['id']}")
        if 'manualPointId' in rule:
            reference = by_id.get(rule['manualPointId'])
            if (obj['kind'] != 'sector' or rule['action'] not in ('name_tags', 'administrative_context')
                    or reference is None or 'point' not in reference):
                raise ValueError('A reviewed manual reference must identify an existing source point for a sector')
        if 'pickerBaseDelegationId' in rule:
            if (rule['action'] != 'preserve_point' or obj['kind'] not in ('town', 'village')
                    or type(rule['pickerBaseDelegationId']) is not int or rule['pickerBaseDelegationId'] <= 0):
                raise ValueError('A reviewed base picker link must preserve an existing named town or village point')
        if 'pickerBaseNameReview' in rule:
            if ('pickerBaseDelegationId' not in rule or rule['action'] != 'preserve_point'
                    or 'nameTags' in rule or 'pointRetentionReview' in rule):
                raise ValueError('Reviewed point/base names require an unchanged preserved source point')
            aggregate_review_file(manifest_path.parent, rule['pickerBaseNameReview'], 'point/base name review')
        if 'pickerBaseLegacyNameReview' in rule:
            if ('pickerBaseDelegationId' not in rule or rule['action'] != 'preserve_point'
                    or 'pickerBaseNameReview' in rule or 'nameTags' in rule or 'pointRetentionReview' in rule):
                raise ValueError('Legacy reviewed point/base names require an unchanged preserved source point')
            aggregate_review_file(manifest_path.parent, rule['pickerBaseLegacyNameReview'], 'legacy point/base name review')
        if 'pickerBaseDisplayReview' in rule:
            allowed = {'id', 'expectedKind', 'expectedTags', 'action', 'reason', 'evidence', 'pickerBaseDisplayReview'}
            if (set(rule) != allowed or rule['action'] != 'preserve_point'
                    or rule['expectedTags'] != obj['tags']):
                raise ValueError('Reviewed point/base display association requires one unchanged source point')
            display_review = json.loads(aggregate_review_file(
                manifest_path.parent, rule['pickerBaseDisplayReview'], 'point/base display review'))
            if not isinstance(display_review, dict):
                raise ValueError('Invalid reviewed point/base display method')
            if display_review.get('method') == 'reviewed_explicit_suburb_point_base_display_identity':
                if obj['kind'] != 'suburb':
                    raise ValueError('Reviewed inhabited-quarter association requires an unchanged suburb point')
            elif (display_review.get('method') != 'reviewed_explicit_point_base_display_identity'
                    or obj['kind'] not in ('town', 'village')):
                raise ValueError('Reviewed town/base association requires an unchanged town or village point')
        rules[obj['id']] = rule
    report = {**manifest, 'manifestSha256': hashlib.sha256(raw).hexdigest()}
    return rules, targets, report


def validate_original_sector_point_retentions(review, directory, curation, original_sources,
                                               effective_sources, source_sha256):
    """Retain reviewed saved points in unchanged original coded sectors.

    This branch changes names/manual choice only. It neither imports geometry
    nor invents a previous context decision for an original OSM source pair.
    """
    keys = {'schemaVersion', 'status', 'sourceSha256', 'baselineMetadata', 'baselineBinary',
            'officialRegistry', 'sourceFacts', 'independentSourceReview', 'records', 'qualifications'}
    qualifications = {'sourcePointAndSectorRetained': True, 'originalGeometryUnchanged': True,
                      'noBoundaryCertification': True, 'prayerSourceIsNotDisplayIdentity': True}
    if (set(review) != keys or review['schemaVersion'] != 1
            or review['status'] != 'reviewed_original_sector_point_choices'
            or review['sourceSha256'] != source_sha256 or review['qualifications'] != qualifications
            or not isinstance(review['records'], list) or not review['records']):
        raise ValueError('Invalid original-sector retained-point review')
    baseline = json.loads(aggregate_review_file(directory, review['baselineMetadata'], 'saved original-sector baseline'))
    blob = aggregate_review_file(directory, review['baselineBinary'], 'saved original-sector geometry')
    registry = json.loads(aggregate_review_file(directory, review['officialRegistry'], 'original-sector official identity'))
    aggregate_review_file(directory, review['sourceFacts'], 'original-sector point source facts')
    independent = json.loads(aggregate_review_file(directory, review['independentSourceReview'], 'independent original-sector point review'))
    if (baseline.get('source', {}).get('sha256') != source_sha256 or independent.get('status') != 'PASS'
            or independent.get('sourceSha256') != source_sha256
            or independent.get('baselineMetadataSha256') != review['baselineMetadata']['sha256']
            or not any(row.get('sha256') == review['sourceFacts']['sha256']
                       for row in independent.get('sourceReports', []))):
        raise ValueError('Original-sector point baseline or independent source review changed')
    before = {row['id']: row for row in baseline['features']}
    if len(before) != len(baseline['features']):
        raise ValueError('Repeated saved original-sector locality')
    result, seen, all_changes = [], set(), []
    for record in review['records']:
        record_keys = {'pointId', 'sectorId', 'pointDecision', 'sectorDecision', 'officialSectorRecord',
                       'sourcePoint', 'publicNode', 'exactPublicNode', 'sourceSector', 'contexts',
                       'sourceNamePeerIds', 'beforeMetadata', 'afterMetadata', 'beforeGroups',
                       'afterGroup', 'exactRawFieldChanges'}
        if not isinstance(record, dict) or set(record) != record_keys:
            raise ValueError('Malformed original-sector point choice')
        point_id, sector_id = record['pointId'], record['sectorId']
        ids = {point_id, sector_id}
        point_rule, sector_rule = curation.get(point_id), curation.get(sector_id)
        point = original_sources.get(point_id, {}); sector = original_sources.get(sector_id, {})
        live_point = effective_sources.get(point_id, {}); live_sector = effective_sources.get(sector_id, {})
        if (len(ids) != 2 or seen & ids or not ids <= set(before)
                or not isinstance(point_rule, dict) or not isinstance(sector_rule, dict)
                or set(point_rule) != {'id', 'expectedKind', 'expectedTags', 'action', 'nameTags',
                                     'reason', 'evidence', 'pointRetentionReview'}
                or set(sector_rule) != {'id', 'expectedKind', 'expectedTags', 'action', 'nameTags',
                                      'manualPointId', 'reason', 'evidence'}
                or record['pointDecision'] != {k: v for k, v in point_rule.items() if k != 'pointRetentionReview'}
                or record['sectorDecision'] != sector_rule
                or point_rule['action'] != 'preserve_point' or sector_rule['action'] != 'name_tags'
                or point_rule['expectedKind'] != 'village' or sector_rule['expectedKind'] != 'sector'
                or sector_rule['manualPointId'] != point_id
                or point.get('kind') != 'village' or live_point.get('kind') != 'village'
                or sector.get('kind') != 'sector' or live_sector.get('kind') != 'sector'
                or point.get('shape') is not None or live_point.get('shape') is not None
                or point.get('point') is None or live_point.get('point') is None
                or sector.get('shape') is None or live_sector.get('shape') is None
                or any(source.get('sourceId') is not None for source in (point, sector))
                or any(source.get('sourceId') != 'osm' for source in (live_point, live_sector))
                or point['tags'] != live_point['tags'] or sector['tags'] != live_sector['tags']
                or point_rule['expectedTags'] != point['tags'] or sector_rule['expectedTags'] != sector['tags']
                or point['point'].coords[:] != live_point['point'].coords[:]
                or point['tags'].get('place') != 'village'):
            raise ValueError('Original-sector point curation or exact source pair changed')
        original_point = {'id': point_id, 'tags': point['tags'], 'lat': point['point'].y, 'lng': point['point'].x}
        node = json.loads(aggregate_review_file(directory, record['publicNode'], 'retained original village point'))
        element = record['exactPublicNode']
        if (record['sourcePoint'] != original_point or node.get('elements') != [element]
                or element.get('type') != 'node' or f"osm:node:{element.get('id')}" != point_id
                or element.get('tags') != point['tags'] or element.get('lat') != original_point['lat']
                or element.get('lon') != original_point['lng'] or type(element.get('version')) is not int
                or not element.get('timestamp') or not element.get('changeset')
                or record['publicNode'].get('url') != f"https://api.openstreetmap.org/api/0.6/node/{element['id']}.json"):
            raise ValueError('Retained village public source differs from consumed original')
        official = record['officialSectorRecord']; code = official.get('sectorCode', '')
        if (not re.fullmatch(r'[0-9]{6}', code)
                or [row for row in registry['sectors'] if row.get('sectorCode') == code] != [official]
                or official.get('delegationCode') != code[:4] or official.get('governorateCode') != code[:2]
                or sector['tags'].get('ref:tn:codegeo') != code
                or names(sector['tags'])[0] != official['sectorAr']
                or sector_rule['nameTags'] != {k: v for k, v in sector['tags'].items() if is_current_name_tag(k)}
                or any(sum(obj['kind'] == 'sector' and obj['tags'].get('ref:tn:codegeo') == code
                           for obj in sources.values()) != 1 for sources in (original_sources, effective_sources))):
            raise ValueError('Retained original sector lacks its exact unique official identity')
        final_tags = {k: v for k, v in point['tags'].items() if not is_current_name_tag(k)}
        final_tags.update(point_rule['nameTags'])
        if (not isinstance(point['tags'].get('name:ar'), str) or not point['tags']['name:ar']
                or set(point_rule['nameTags']) != {k for k in point['tags'] if is_current_name_tag(k)} | {'alt_name:ar'}
                or point_rule['nameTags'].get('name') != official['sectorAr']
                or point_rule['nameTags'].get('name:ar') != official['sectorAr']
                or not {clean(value) for value in point_rule['nameTags']['alt_name:ar'].split(';')}
                    <= set(names(point['tags']))
                or names(final_tags)[0] != official['sectorAr']
                or not set(names(point['tags'])).issubset(names(final_tags))
                or any(value != final_tags.get(key) for key, value in point['tags'].items()
                       if is_current_name_tag(key) and key not in {'name', 'name:ar'})):
            raise ValueError('Retained village loses source names or an unchanged foreign spelling')
        source_sector = record['sourceSector']
        packed = set_precision(sector['shape'], 1 / SCALE)
        baseline_sector = before[sector_id]
        if (set(source_sector) != {'tags', 'originalGeometrySha256', 'packedGeometrySha256'}
                or source_sector['tags'] != sector['tags']
                or any(aggregate_geometry_sha256(obj['shape']) != source_sector['originalGeometrySha256']
                       for obj in (sector, live_sector))
                or hashlib.sha256(packed_geometry_bytes(packed)).hexdigest() != source_sector['packedGeometrySha256']
                or hashlib.sha256(blob[baseline_sector['offset']:baseline_sector['offset'] + baseline_sector['length']]).hexdigest()
                    != source_sector['packedGeometrySha256']
                or not sector['shape'].covers(point['point']) or not packed.covers(point['point'])):
            raise ValueError('Retained village is outside its unchanged original or packed sector')
        contexts = record['contexts']
        if (not isinstance(contexts, list) or sorted(row.get('kind') for row in contexts) != ['delegation', 'governorate']
                or len({row.get('id') for row in contexts}) != 2 or {row['id'] for row in contexts} & ids):
            raise ValueError('Missing retained village official parent contexts')
        parent = None
        for row in contexts:
            context_id = row['id']; expected_code = code[:4] if row['kind'] == 'delegation' else code[:2]
            if (set(row) != {'id', 'kind', 'tags', 'geometrySha256'} or curation.get(context_id) is not None):
                raise ValueError('Retained village context needs a separate curation review')
            for sources in (original_sources, effective_sources):
                obj = sources.get(context_id, {})
                if (obj.get('kind') != row['kind'] or obj.get('tags') != row['tags']
                        or obj['tags'].get('ref:tn:codegeo') != expected_code or obj.get('shape') is None
                        or aggregate_geometry_sha256(obj['shape']) != row['geometrySha256']
                        or not obj['shape'].covers(point['point'])
                        or (row['kind'] == 'delegation' and not obj['shape'].covers(sector['shape']))):
                    raise ValueError('Retained village original/effective context changed')
            if row['kind'] == 'delegation':
                parent = original_sources[context_id]
        if (record['beforeMetadata'] != {sid: before[sid] for sid in sorted(ids)}
                or record['beforeGroups'] != {sid: [sid] for sid in sorted(ids)}
                or any(before[sid]['pickerGroupId'] != sid
                       or sorted(row['id'] for row in baseline['features'] if row['pickerGroupId'] == sid) != [sid]
                       for sid in ids)):
            raise ValueError('Retained original point saved identities or complete before groups changed')
        after = {sid: {k: v for k, v in before[sid].items() if k not in {'offset', 'length'}} for sid in sorted(ids)}
        if (after[point_id]['kind'] != 'village' or after[point_id]['hasBoundary'] is not False
                or after[sector_id]['kind'] != 'sector' or after[sector_id]['hasBoundary'] is not True
                or any(after[sid]['sourceId'] != 'osm' for sid in ids)
                or any(after[point_id][key] != original_point[key] for key in ('lat', 'lng'))
                or after[point_id]['governorateId'] != after[sector_id]['governorateId']
                or after[point_id]['delegationId'] != after[sector_id]['delegationId']):
            raise ValueError('Retained original point geographic metadata changed')
        after[sector_id].update(lat=original_point['lat'], lng=original_point['lng'], manualPointId=point_id)
        after[point_id].update(name=names(final_tags)[0], aliases=names(final_tags)[1:],
                               parentName=names(parent['tags'])[0], pickerGroupId=sector_id)
        changes = [{'id': sid, 'field': key, 'before': before[sid].get(key), 'after': after[sid].get(key)}
                   for sid in (sector_id, point_id) for key in sorted(set(before[sid]) | set(after[sid]))
                   if key not in {'offset', 'length'} and before[sid].get(key) != after[sid].get(key)]
        if (record['afterMetadata'] != after or record['afterGroup'] != {'id': sector_id, 'memberIds': sorted(ids)}
                or record['exactRawFieldChanges'] != changes):
            raise ValueError('Retained original point after metadata is not the exact derived transition')
        peer_keys = {norm(value) for obj in (point, sector) for value in names(obj['tags'])}
        peer_keys.update(norm(value) for value in names(final_tags))
        for sources, apply_names in ((original_sources, False), (effective_sources, False), (effective_sources, True)):
            actual = []
            for source_id, obj in sources.items():
                tags = obj['tags']
                if apply_names and 'nameTags' in curation.get(source_id, {}):
                    tags = {**{k: v for k, v in tags.items() if not is_current_name_tag(k)}, **curation[source_id]['nameTags']}
                if peer_keys & {norm(value) for value in names(tags)}:
                    actual.append(source_id)
            if sorted(actual) != record['sourceNamePeerIds'] or sorted(actual) != sorted(ids):
                raise ValueError('Retained original point has an unreviewed source name peer')
        result.append({'method': 'reviewed_original_sector_point_choices', 'pointId': point_id, 'sectorId': sector_id,
                       'expectedAfterMetadata': after, 'expectedAfterGroup': record['afterGroup']})
        all_changes.extend(changes);seen.update(ids)
    if independent.get('exactProposedRawFieldChanges') != all_changes:
        raise ValueError('Independent retained-point raw transition differs')
    return result


def load_reviewed_point_retentions(manifest_path, curation, original_sources, effective_sources,
                                  official_sources, official_contexts, source_sha256):
    """Validate named saved points jointly with their imported sector choices.

    This permits only an exact reviewed transition. It does not rename an
    unreviewed point, relax manual-anchor containment, or merge by distance.
    """
    rules = {identifier: rule for identifier, rule in curation.items() if 'pointRetentionReview' in rule}
    if not rules:
        return []
    directory = Path(manifest_path).parent
    documents, records, seen_sectors = {}, {}, set()
    for identifier, rule in rules.items():
        if rule['action'] != 'preserve_point':
            raise ValueError('Point retention evidence belongs only to a preserved source point')
        reference = rule['pointRetentionReview']
        digest = reference['sha256']
        if digest in documents:
            continue
        review = json.loads(aggregate_review_file(directory, reference, 'point retention review'))
        if review.get('status') == 'reviewed_original_sector_point_choices':
            originals = validate_original_sector_point_retentions(
                review, directory, curation, original_sources, effective_sources, source_sha256)
            for original in originals:
                point_id, sector_id = original['pointId'], original['sectorId']
                if (point_id in records or sector_id in seen_sectors or point_id not in rules
                        or rules[point_id]['pointRetentionReview'] != reference):
                    raise ValueError('Repeated original-sector retained point or incompatible review')
                records[point_id] = original;seen_sectors.add(sector_id)
            documents[digest] = review
            continue
        if (review.get('schemaVersion') != 1 or review.get('sourceSha256') != source_sha256
                or review.get('status') != 'reviewed_imported_sector_point_choices'
                or not isinstance(review.get('records'), list) or not review['records']):
            raise ValueError('Invalid imported-sector point retention review')
        documents[digest] = review
        readiness = json.loads(aggregate_review_file(directory, review.get('sourceReadiness'), 'settlement identity'))
        baseline = json.loads(aggregate_review_file(directory, review.get('baselineMetadata'), 'saved identity baseline'))
        baseline_blob = aggregate_review_file(directory, review.get('baselineBinary'), 'saved geometry baseline')
        original_shapes = json.loads(aggregate_review_file(directory, review.get('originalSectorGeometry'), 'original sectors'))
        registry = json.loads(aggregate_review_file(directory, review.get('officialRegistry'), 'sector identity registry'))
        if (readiness.get('sourceSha256') != source_sha256
                or original_shapes.get('sourceSha256') != source_sha256
                or baseline.get('source', {}).get('sha256') != source_sha256):
            raise ValueError('Point retention baseline/source checksum differs')
        supporting = review.get('supportingEvidence')
        if not isinstance(supporting, list) or not supporting:
            raise ValueError('Point retention needs primary identity evidence')
        for evidence in supporting:
            aggregate_review_file(directory, evidence, 'point retention supporting evidence')
        supersession = review.get('supersession', {})
        previous_curation = json.loads(aggregate_review_file(
            directory, supersession.get('previousCuration'), 'previous point curation'))
        superseded = supersession.get('previousDecision', {})
        if (previous_curation.get('sourceSha256') != source_sha256
                or superseded.get('action') != 'locality_context'
                or superseded.get('id') != supersession.get('pointId')
                or superseded['id'] not in {record.get('pointId') for record in review['records']}
                or [item for item in previous_curation.get('decisions', [])
                    if item.get('id') == superseded['id']] != [superseded]):
            raise ValueError('Point retention does not supersede the exact reviewed previous context')
        aggregate_review_file(directory, superseded.get('reviewEvidence'), 'superseded point context')
        previous = {feature['id']: feature for feature in baseline['features']}
        reviewed_choices = {choice['villageId']: choice for choice in readiness.get('proposedChoices', [])}
        imported = json.loads(aggregate_review_file(directory, review.get('officialBoundary'), 'imported counterpart'))
        imported_by_id = {feature['id']: feature for feature in imported['features']}
        for record in review['records']:
            point_id, sector_id = record.get('pointId'), record.get('sectorId')
            decision = rules.get(point_id)
            choice = reviewed_choices.get(point_id, {})
            if (point_id in records or sector_id in seen_sectors or decision is None
                    or decision['pointRetentionReview'] != reference
                    or record.get('pointDecision') != {k: v for k, v in decision.items() if k != 'pointRetentionReview'}
                    or record.get('sectorDecision') != curation.get(sector_id)
                    or choice.get('sectorId') != sector_id or choice.get('sourceIdentityReviewed') is not True
                    or choice.get('manualPointId') != point_id
                    or choice.get('canonicalPickerGroupId') != sector_id
                    or choice.get('expectedCompleteAfterGroupMembers') != sorted([point_id, sector_id])):
                raise ValueError('Reviewed point/sector choice inventory changed')
            source = original_sources.get(point_id, {})
            original_sector = original_sources.get(sector_id, {})
            sector = effective_sources.get(sector_id, {})
            source_point = choice.get('expectedOriginalVillage', {})
            if (source.get('kind') != 'village' or 'point' not in source or source.get('shape') is not None
                    or source.get('tags') != source_point.get('tags') or source['tags'] != decision['expectedTags']
                    or source['point'].y != source_point.get('lat') or source['point'].x != source_point.get('lng')
                    or original_sector.get('kind') != 'sector'
                    or original_sector.get('tags') != curation[sector_id]['expectedTags']
                    or original_sector['tags'] != {k: v for k, v in choice['expectedOriginalSectorTags'].items() if k != 'type'}):
                raise ValueError('Exact retained source point/sector changed')
            original_record = next((item for item in original_shapes['records'] if item['id'] == sector_id), None)
            if (original_record is None or original_record['tags'] != original_sector['tags']
                    or aggregate_geometry_sha256(shape(original_record['geometry'])) != record.get('originalGeometrySha256')
                    or aggregate_geometry_sha256(original_sector['shape']) != record['originalGeometrySha256']):
                raise ValueError('Original sector geometry changed during point retention review')
            for key, expected in ((point_id, choice['expectedCurrentVillage']), (sector_id, choice['expectedCurrentSector'])):
                if previous.get(key) != expected:
                    raise ValueError('Reviewed saved locality before record changed')
                members = sorted(item['id'] for item in baseline['features']
                                 if item['pickerGroupId'] == expected['pickerGroupId'])
                if members != choice['expectedCompleteBeforeGroups'].get(expected['pickerGroupId']):
                    raise ValueError('Previous saved locality group has unreviewed members')
            before = previous[sector_id]
            if hashlib.sha256(baseline_blob[before['offset']:before['offset'] + before['length']]).hexdigest() != record.get('previousPackedGeometrySha256'):
                raise ValueError('Previous saved sector geometry checksum differs')
            boundary = imported_by_id.get(sector_id, {})
            properties = boundary.get('properties', {})
            code = record.get('officialCode')
            context = official_contexts.get(sector_id, {})
            provider = official_sources.get(sector.get('sourceId'), {})
            if (not isinstance(code, str) or not re.fullmatch(r'[0-9]{6}', code)
                    or code != choice.get('officialCode') or properties.get('officialCode') != code
                    or properties.get('nameAr') != choice.get('primaryName')
                    or sector.get('kind') != 'sector' or sector.get('sourceId') != imported.get('sourceId')
                    or provider.get('sha256') != review['officialBoundary']['sha256']
                    or context.get('delegationId') != curation[sector_id].get('delegationId')
                    or curation[sector_id].get('manualPointId') != point_id
                    or context.get('sourceId') != sector['sourceId']
                    or sum(obj['kind'] == 'sector' and obj['tags'].get('ref:tn:codegeo') == code
                           for obj in effective_sources.values()) != 1):
                raise ValueError('Imported sector identity/manual counterpart changed')
            official = record.get('officialRecord')
            if ([item for item in registry.get('sectors', []) if item.get('sectorCode') == code] != [official]
                    or official.get('sectorAr') != choice['primaryName']
                    or official.get('delegationCode') != code[:4] or official.get('governorateCode') != code[:2]):
                raise ValueError('Point retention official code/name/parent differs')
            final_tags = {k: v for k, v in source['tags'].items() if not is_current_name_tag(k)}
            final_tags.update(decision.get('nameTags', {k: v for k, v in source['tags'].items() if is_current_name_tag(k)}))
            if (names(final_tags)[0] != choice['primaryName']
                    or not set(names(source['tags'])).issubset(names(final_tags))):
                raise ValueError('Retained point loses a reviewed name or does not match its sector')
            footprint = shape(boundary['geometry'])
            packed = set_precision(footprint, 1 / SCALE)
            if (aggregate_geometry_sha256(sector['shape']) != record.get('officialGeometrySha256')
                    or aggregate_geometry_sha256(footprint) != record['officialGeometrySha256']
                    or hashlib.sha256(packed_geometry_bytes(packed)).hexdigest() != record.get('officialPackedGeometrySha256')
                    or not footprint.covers(source['point']) or not packed.covers(source['point'])):
                raise ValueError('Retained point is not inside its exact reviewed imported counterpart')
            record = {**record, 'expectedName': names(final_tags)[0], 'expectedAliases': names(final_tags)[1:],
                      'lat': source['point'].y, 'lng': source['point'].x, 'manualSource': choice['manualPoint']['delegationId'],
                      'governorateId': choice['expectedCurrentVillage']['governorateId']}
            records[point_id] = record
            seen_sectors.add(sector_id)
        # Every source name peer that can affect this transition must have been
        # inventoried. Final grouping below must still match each exact pair.
        union = None
        for record in review['records']:
            footprint = effective_sources[record['sectorId']]['shape']
            union = footprint if union is None else union.union(footprint)
        peer_names = {norm(value) for record in review['records']
                      for value in names(original_sources[record['pointId']]['tags'])
                      + names(original_sources[record['sectorId']]['tags'])}
        actual_peers = {identifier for identifier, obj in original_sources.items()
                        if any(norm(value) in peer_names for value in names(obj['tags']))
                        and (obj.get('shape') is not None or obj.get('point') is not None)
                        and union.intersects(obj['shape'] if obj.get('shape') is not None else obj['point'])}
        if actual_peers != {identifier for record in review['records'] for identifier in (record['pointId'], record['sectorId'])}:
            raise ValueError('Imported sector/point name-peer inventory needs review')
    if set(records) != set(rules):
        raise ValueError('Point retention review has missing or extra decisions')
    return list(records.values())


def verify_reviewed_point_choices(records, features):
    """Fail closed on unexpected grouping, point movement or manual remapping."""
    by_id = {feature['id']: feature for feature in features}
    for record in records:
        point_id, sector_id = record['pointId'], record['sectorId']
        point, sector = by_id.get(point_id, {}), by_id.get(sector_id, {})
        if record.get('method') == 'reviewed_original_sector_point_choices':
            expected = record['expectedAfterMetadata'];group = record['expectedAfterGroup']
            if (sorted(row['id'] for row in features if row['pickerGroupId'] == group['id']) != group['memberIds']
                    or any(sid not in by_id or {k: v for k, v in by_id[sid].items()
                           if k not in {'offset', 'length'}} != metadata for sid, metadata in expected.items())):
                raise ValueError('Reviewed original-sector saved point or final group changed')
            continue
        members = sorted(feature['id'] for feature in features if feature['pickerGroupId'] == sector_id)
        if (members != sorted([point_id, sector_id]) or point.get('hasBoundary') is not False
                or point.get('sourceId') != 'osm' or point.get('kind') != 'village'
                or sector.get('hasBoundary') is not True or sector.get('manualPointId') != point_id
                or point.get('name') != record['expectedName'] or point.get('aliases') != record['expectedAliases']
                or any(feature.get('lat') != record['lat'] or feature.get('lng') != record['lng']
                       or feature.get('delegationId') != record['manualSource']
                       or feature.get('parentName') != record['expectedParentName']
                       or feature.get('governorateId') != record['governorateId']
                       or feature.get('name') != record['expectedName'] for feature in (point, sector))):
            raise ValueError(f'Reviewed saved point/sector final choice needs review: {point_id}')


def apply_reviewed_locality_contexts(manifest_path, curation, features, geometries,
                                    original_sources, effective_sources, source_sha256, country):
    """Change only reviewed subtitles, after all identity/group decisions finish.

    Official outlines are evidence for complete locality containment, not an
    imported sector replacement. No point move, buffering or name-based parent
    inference is performed. Exact before-record guards make later changes fail.
    """
    rules = {identifier: rule for identifier, rule in curation.items()
             if rule['action'] == 'locality_context'}
    if not rules:
        return []
    directory = Path(manifest_path).parent
    documents, proofs, contexts = {}, {}, {}
    for rule in rules.values():
        reference = rule['reviewEvidence']
        raw = aggregate_review_file(directory, reference, 'locality context review')
        if reference['sha256'] in documents:
            continue
        review = json.loads(raw)
        if (review.get('schemaVersion') != 1 or review.get('sourceSha256') != source_sha256
                or review.get('status') != 'reviewed_context_only'
                or review.get('scope') != 'exact_locality_context_only'
                or not isinstance(review.get('records'), list) or not review['records']
                or not isinstance(review.get('contexts'), list) or len(review['contexts']) < 2):
            raise ValueError('Invalid reviewed locality-context evidence')
        documents[reference['sha256']] = review
        registry = json.loads(aggregate_review_file(directory, review.get('officialRegistry'), 'locality context registry'))
        source_facts = json.loads(aggregate_review_file(directory, review.get('sourceFacts'), 'locality context source facts'))
        if source_facts.get('sourceSha256') != source_sha256:
            raise ValueError('Locality context source facts use a different PBF')
        aggregate_review_file(directory, review.get('researchReview'), 'locality context research')
        additional = review.get('additionalSourceProofs', [])
        if not isinstance(additional, list):
            raise ValueError('Invalid locality context supporting-proof inventory')
        for supporting in additional:
            aggregate_review_file(directory, supporting, 'locality context supporting proof')
        for context in review['contexts']:
            identifier = context.get('id')
            original = original_sources.get(identifier)
            effective = effective_sources.get(identifier)
            official = context.get('officialRecord', {})
            code = official.get('sectorCode')
            if (original is None or effective is None or original['kind'] != 'sector'
                    or effective['kind'] != 'sector' or original['tags'] != context.get('expectedSourceTags')
                    or effective['tags'] != original['tags']
                    or not isinstance(code, str) or not re.fullmatch(r'[0-9]{6}', code)
                    or original['tags'].get('ref:tn:codegeo') != code
                    or official.get('delegationCode') != code[:4] or official.get('governorateCode') != code[:2]
                    or [r for r in registry.get('sectors', []) if r.get('sectorCode') == code] != [official]
                    or sum(o['kind'] == 'sector' and o['tags'].get('ref:tn:codegeo') == code
                           for o in original_sources.values()) != 1):
                raise ValueError('Locality context official/source identity changed')
            scope = context.get('administrativeScope', {})
            if (scope.get('relationship') != 'one_electoral_circle_equals_one_imada'
                    or scope.get('officialCode') != code or scope.get('imadaName') != official.get('sectorAr')
                    or scope.get('circleName') != official.get('sectorAr')
                    or type(scope.get('decreePdfPage')) is not int or scope['decreePdfPage'] <= 0
                    or context.get('personallyVisuallyReviewed') is not True
                    or context.get('explicitlyClosed') is not True
                    or type(context.get('activeClipCount')) is not int or context['activeClipCount'] < 1
                    or context.get('fullyInsideEveryActiveClip') is not True
                    or not isinstance(context.get('maxControlResidualMeters'), (int, float))
                    or not 0 <= context['maxControlResidualMeters'] < 1):
                raise ValueError('Locality context needs complete reviewed whole-imada map scope')
            aggregate_review_file(directory, scope.get('decreePdf'), 'locality context decree')
            pdf = context.get('sourcePdf', {})
            if not isinstance(pdf.get('url'), str) or not pdf['url'].startswith('https://www.isie.tn/'):
                raise ValueError('Locality context needs its exact official map URL')
            aggregate_review_file(directory, pdf, 'locality context map')
            outline = shape(context['geometry'])
            if (outline.geom_type not in ('Polygon', 'MultiPolygon') or outline.is_empty or not outline.is_valid
                    or not all(math.isfinite(value) for value in outline.bounds)
                    or not -180 <= outline.bounds[0] <= outline.bounds[2] <= 180
                    or not -90 <= outline.bounds[1] <= outline.bounds[3] <= 90
                    or context.get('crs') != 'EPSG:4326'
                    or aggregate_geometry_sha256(outline) != context.get('geometrySha256')):
                raise ValueError('Invalid or changed original official locality-context outline')
            key = (reference['sha256'], identifier)
            if key in contexts:
                raise ValueError('Repeated locality-context outline')
            contexts[key] = (context, outline)
        for record in review['records']:
            decision = record.get('decision', {})
            identifier = decision.get('id')
            expected_rule = rules.get(identifier)
            if (expected_rule is None or identifier in proofs
                    or expected_rule['reviewEvidence'] != reference
                    or decision != {key: value for key, value in expected_rule.items() if key != 'reviewEvidence'}):
                raise ValueError('Locality context proof/curation inventory differs')
            proofs[identifier] = record
    if set(proofs) != set(rules):
        raise ValueError('Missing reviewed locality-context decision')
    indices = {feature['id']: index for index, feature in enumerate(features)}
    applications = []
    for identifier, rule in rules.items():
        index = indices.get(identifier)
        if index is None:
            raise ValueError('Reviewed locality-context ID is no longer selectable')
        feature, record = features[index], proofs[identifier]
        original, effective = original_sources[identifier], effective_sources[identifier]
        before = record.get('expectedFeature')
        if (feature != before or original['tags'] != rule['expectedTags']
                or effective['tags'] != original['tags'] or effective['kind'] != original['kind']
                or feature['kind'] != rule['expectedKind'] or feature['sourceId'] != 'osm'):
            raise ValueError(f'Reviewed locality-context before record changed: {identifier}')
        source_shape = original.get('shape') if original.get('shape') is not None else original.get('point')
        effective_shape = effective.get('shape') if effective.get('shape') is not None else effective.get('point')
        packed = geometries[index] if index < len(geometries) else effective_shape
        if (source_shape is None or effective_shape is None or source_shape.is_empty or not source_shape.is_valid
                or aggregate_geometry_sha256(source_shape) != record.get('originalGeometrySha256')
                or aggregate_geometry_sha256(effective_shape) != record.get('originalGeometrySha256')
                or not country.covers(source_shape) or not country.covers(packed)
                or feature['hasBoundary'] != (index < len(geometries))):
            raise ValueError('Reviewed locality-context source footprint changed')
        packed_sha = (hashlib.sha256(packed_geometry_bytes(packed)).hexdigest()
                      if index < len(geometries) else None)
        if record.get('packedGeometrySha256') != packed_sha:
            raise ValueError('Reviewed locality-context packed footprint changed')
        reference_sha = rule['reviewEvidence']['sha256']
        old, old_outline = contexts[(reference_sha, record['oldContextId'])]
        new, new_outline = contexts[(reference_sha, record['newContextId'])]
        after = record.get('contextAfter', {})
        aliases = after.get('contextAliases')
        own_names = {norm(value) for value in names(effective['tags'])}
        sector_name = new['officialRecord']['sectorAr']
        useful_parent = (sector_name if norm(sector_name) not in own_names
                         else 'معتمدية ' + new['officialRecord']['delegationAr'])
        reviewed_aliases = sorted((set(before['contextAliases']) - set(names(old['expectedSourceTags'])))
                                 | set(names(new['expectedSourceTags'])))
        if (old['id'] == new['id'] or old['officialRecord']['sectorAr'] != before['parentName']
                or old['officialRecord']['delegationCode'] != new['officialRecord']['delegationCode']
                or set(after) != {'parentName', 'contextAliases'}
                or after['parentName'] != useful_parent
                or not isinstance(aliases, list) or any(not isinstance(a, str) or not clean(a) for a in aliases)
                or aliases != reviewed_aliases or after['parentName'] not in aliases
                or not new_outline.covers(source_shape) or not new_outline.covers(packed)
                or not old_outline.disjoint(source_shape) or not old_outline.disjoint(packed)):
            raise ValueError('Locality context must prove full source/packed membership and exclude old parent')
        feature.update(after)
        applications.append({'id': identifier, 'decisionId': identifier, 'action': 'locality_context',
                             'inherited': False, 'reviewEvidenceSha256': reference_sha,
                             'oldContextId': old['id'], 'newContextId': new['id']})
    return applications


def validate_reviewed_locality_classification(manifest_path, obj, rule, source_sha256):
    identifier = obj['id']
    proof = json.loads(aggregate_review_file(
        Path(manifest_path).parent, rule['reviewEvidence'], 'reviewed locality classification'))
    source_code = obj['tags'].get('ref:tn:codegeo')
    source_name = obj['tags'].get('name')
    allowed = {'schemaVersion', 'status', 'targetId', 'sourceKind', 'effectiveKind', 'sourcePbfSha256',
               'originalTags', 'originalGeometrySha256', 'expectedTargetFeatureWithoutOffsetLength',
               'expectedPackedGeometrySha256', 'expectedPickerMembers', 'expectedIncidentConflicts',
               'primaryReview', 'sourcePdf', 'rejectedAdministrativeCode', 'noPositiveAreaOverlaps', 'qualifications'}
    if (set(proof) != allowed or proof.get('schemaVersion') != 1
            or proof.get('status') != 'reviewed_detached_component_locality_classification'
            or proof.get('targetId') != identifier
            or proof.get('rejectedAdministrativeCode') != source_code
            or proof.get('noPositiveAreaOverlaps') is not True
            or obj['shape'].geom_type not in ('Polygon', 'MultiPolygon')
            or obj['shape'].is_empty or not obj['shape'].is_valid
            or proof.get('qualifications') != {
                'sourceNameAndGeometryRetained': True, 'currentDelegationContextRetained': True,
                'noExactSectorContextAssigned': True, 'noBoundaryCertification': True}
            or proof.get('sourceKind') != 'sector' or proof.get('effectiveKind') != 'locality'
            or proof.get('sourcePbfSha256') != source_sha256
            or proof.get('originalTags') != obj['tags']
            or proof.get('originalGeometrySha256') != aggregate_geometry_sha256(obj['shape'])
            or proof.get('expectedPickerMembers') != [identifier]
            or proof.get('expectedIncidentConflicts') != []
            or not isinstance(proof.get('expectedTargetFeatureWithoutOffsetLength'), dict)
            or not isinstance(proof.get('expectedPackedGeometrySha256'), str)
            or not isinstance(proof.get('primaryReview'), dict)
            or not isinstance(proof.get('sourcePdf'), dict)):
        raise ValueError(f'Reviewed locality classification proof changed: {identifier}')
    primary_review = json.loads(aggregate_review_file(
        Path(manifest_path).parent, proof['primaryReview'], 'Gremdi component identity'))
    identity = primary_review.get('gremdiComponentIdentity')
    if (not isinstance(identity, dict) or identity.get('id') != identifier
            or identity.get('wrongSourceCode') != source_code
            or identity.get('existingName') != source_name
            or identity.get('sourcePdfSha256') != proof['sourcePdf'].get('sha256')
            or identity.get('officialMapName') != 'القراطن'
            or identity.get('officialSectorCode') != '346659'
            or source_code != '346656' or identity.get('sourceDrawingIndex') != 128
            or not isinstance(identity.get('exactIslandGeometry'), dict)):
        raise ValueError(f'Reviewed locality identity changed: {identifier}')
    aggregate_review_file(Path(manifest_path).parent, proof['sourcePdf'], 'Gremdi source PDF')
    return proof


def apply_reviewed_locality_classifications(areas_nodes, curation, original_picker_sources):
    for obj in areas_nodes:
        rule = curation.get(obj['id'])
        if rule is None or rule.get('action') != 'reviewed_locality_classification':
            continue
        identifier = obj['id']
        source = original_picker_sources.get(identifier)
        if (source is None or source['kind'] != 'sector' or source['tags'] != obj['tags']
                or obj['kind'] != 'sector' or rule.get('targetKind') != 'locality'):
            raise ValueError(f'Reviewed locality classification source changed: {identifier}')
        obj['kind'] = 'locality'


def verify_reviewed_locality_classifications(manifest_path, features, point_only, geometries, conflicts, curation):
    all_features = features + point_only
    by_id = {feature['id']: feature for feature in all_features}
    by_geometry = {feature['id']: geometry for feature, geometry in zip(features, geometries)}
    for identifier, rule in curation.items():
        if rule.get('action') != 'reviewed_locality_classification':
            continue
        proof = json.loads(aggregate_review_file(
            Path(manifest_path).parent, rule['reviewEvidence'], 'reviewed locality classification'))
        feature = by_id.get(identifier)
        geometry = by_geometry.get(identifier)
        if feature is None or geometry is None:
            raise ValueError(f'Reviewed locality classification target missing: {identifier}')
        expected = proof.get('expectedTargetFeatureWithoutOffsetLength')
        projected = {key: value for key, value in feature.items() if key not in ('offset', 'length')}
        if (feature.get('kind') != 'locality' or rule.get('targetKind') != 'locality'
                or projected != expected
                or hashlib.sha256(packed_geometry_bytes(geometry)).hexdigest()
                   != proof.get('expectedPackedGeometrySha256')
                or proof.get('expectedPickerMembers') != [identifier]
                or feature.get('pickerGroupId') != identifier
                or sum(1 for item in all_features if item.get('pickerGroupId') == identifier) != 1
                or any(set_precision(geometry, 0).intersection(set_precision(peer, 0)).area > 0
                       for other_id, peer in by_geometry.items() if other_id != identifier)
                or [conflict for conflict in conflicts if identifier in conflict['ids']]
                   != proof.get('expectedIncidentConflicts')):
            raise ValueError(f'Reviewed locality classification projection changed: {identifier}')


def detect_conflicts(features, geometries):
    """Keep original borders; mark peers whose crossing claims are ambiguous.

    Sectors are a partition: any positive-area sector overlap is a conflict.
    Fine local areas can be nested normally, but crossing fine boundaries conflict.
    The same rule applies within each broad place kind (e.g. village/village).
    Broad place areas versus finer areas and sector versus neighborhood are normal
    different hierarchies and are not treated as peer conflicts.
    """
    # Stored vertices have already been quantized. Reset GEOS's *operation*
    # precision model without moving those vertices: otherwise intersection
    # vertices and interior samples also snap to 1e-6 and can fall onto a border
    # in a narrow but genuine overlap between the stored polygons.
    geometries = [set_precision(geometry, 0) for geometry in geometries]
    tree = STRtree(geometries)
    conflicts = []
    for first_index, first in enumerate(features):
        a = geometries[first_index]
        for second_index in sorted(int(i) for i in tree.query(a) if i > first_index):
            second, b = features[second_index], geometries[second_index]
            sectors = first['kind'] == second['kind'] == 'sector'
            fine = first['kind'] in FINE_KINDS and second['kind'] in FINE_KINDS
            peers = first['kind'] == second['kind'] and first['kind'] in PLACES
            if not (sectors or fine or peers): continue
            if not sectors and (a.covers(b) or b.covers(a)): continue
            overlap = a.intersection(b)
            if overlap.is_empty or overlap.area <= 0: continue
            def polygon_parts(geometry):
                if isinstance(geometry, Polygon):
                    return [geometry]
                return [part for member in getattr(geometry, 'geoms', []) for part in polygon_parts(member)]

            # Ignore disconnected contact lines in GeometryCollections. Prefer
            # the largest real polygon component and retain full precision.
            point = next((point for part in sorted(polygon_parts(overlap), key=lambda p: (-p.area, p.bounds))
                          for point in [part.representative_point()] if a.contains(point) and b.contains(point)), None)
            if point is None:
                raise ValueError(f"No strictly interior conflict sample for {first['id']} / {second['id']}")
            conflicts.append({'ids': sorted([first['id'], second['id']]),
                              'reason': 'overlapping_sectors' if sectors else 'crossing_local_areas',
                              'intersectionKm2': overlap.area * 111.32**2 * math.cos(math.radians(point.y)),
                              'sample': {'lat': point.y, 'lng': point.x}})
    return sorted(conflicts, key=lambda c: c['ids'])


def distance(lat, lng, other):
    a, b = math.radians(lat), math.radians(other['lat'])
    value = math.sin((b-a)/2)**2 + math.cos(a)*math.cos(b)*math.sin(math.radians(other['lng']-lng)/2)**2
    return 6371.0 * 2 * math.asin(min(1.0, math.sqrt(value)))


def complete_prayer_month(path):
    """A named CSV is not usable when its rows contain blank or invalid times."""
    try:
        year, month = int(path.parent.name), int(path.stem)
        if year <= 0 or not 1 <= month <= 12:
            return False
        with path.open(encoding='utf-8-sig', newline='') as stream:
            rows = list(csv.reader(stream))[1:]
        if len(rows) != calendar.monthrange(year, month)[1]:
            return False
        for day, row in enumerate(rows, 1):
            if len(row) < 7 or int(row[0]) != day:
                return False
            minutes = []
            for value in row[1:7]:
                if not re.fullmatch(r'\d{1,2}:\d{2}', value.strip()):
                    return False
                hour, minute = map(int, value.strip().split(':'))
                if not 0 <= hour <= 23 or not 0 <= minute <= 59:
                    return False
                minutes.append(hour * 60 + minute)
            if any(earlier >= later for earlier, later in zip(minutes, minutes[1:])):
                return False
        return True
    except (OSError, UnicodeError, ValueError, csv.Error):
        return False


def inm_source_delegation_names_match(source_delegation, delegation, governor):
    """Keep reviewed display spelling fixes separate from INM's original spelling."""
    if not all(isinstance(value, dict) for value in (source_delegation, delegation, governor)):
        return False
    governor_id = governor.get('id')
    source_id, asset_id = source_delegation.get('id'), delegation.get('id')
    if (type(governor_id) is not int or type(source_id) is not int
            or type(asset_id) is not int or source_id != asset_id):
        return False
    source_ar, source_fr, source_en = (source_delegation.get(key)
                                     for key in ('intituleAr', 'intituleFr', 'intituleAn'))
    asset_ar, asset_fr, asset_en = (delegation.get(key) for key in ('nomAr', 'nomFr', 'nomEn'))
    if not all(isinstance(value, str) and value
               for value in (source_ar, source_fr, source_en, asset_ar, asset_fr, asset_en)):
        return False
    if source_fr != asset_fr or source_en != asset_en:
        return False
    if source_ar == asset_ar:
        return True
    # Exact, ID-bound source typos retained after the reviewed Arabic display fixes.
    # Governor names, source parents and all response provenance remain checked below.
    return (governor_id, source_id, source_ar, asset_ar) in {
        (345, 425, 'مللولش', 'ملولش'),
        (358, 534, 'التظامن', 'التضامن'),
    }


def validate_inm_published_reference(correction, governor, delegation, directory):
    """Bind published INM reference coordinates to original daily response bytes.

    These fields do not certify the server's internal calculation inputs or a
    settlement point. They must never enter the OSM point-equality picker path.
    """
    identifier = correction['delegationId']
    if ('osm' in correction or 'pointId' in correction
            or type(identifier) is not int or identifier <= 0
            or type(correction.get('expectedGovernorateId')) is not int
            or correction['expectedGovernorateId'] <= 0):
        raise ValueError(f'Invalid INM published reference identity: {identifier}')
    evidence = correction.get('inmEvidence')
    if (not isinstance(evidence, dict)
            or set(evidence) != {'serviceDate', 'prayer', 'sun', 'retrievalManifest'}):
        raise ValueError(f'Missing INM published reference evidence: {identifier}')
    service_date = evidence['serviceDate']
    if not isinstance(service_date, str) or not re.fullmatch(r'[0-9]{4}-[0-9]{2}-[0-9]{2}', service_date):
        raise ValueError(f'Invalid INM service date: {identifier}')
    try:
        service_year = date.fromisoformat(service_date).year
    except ValueError as exc:
        raise ValueError(f'Invalid INM service date: {identifier}') from exc
    retrieval_reference = evidence['retrievalManifest']
    if not isinstance(retrieval_reference, dict) or set(retrieval_reference) != {'file', 'sha256'}:
        raise ValueError(f'Invalid INM retrieval manifest reference: {identifier}')
    retrieval = json.loads(aggregate_review_file(directory, retrieval_reference, 'INM retrieval manifest'))
    if not isinstance(retrieval, dict) or not isinstance(retrieval.get('responses'), list):
        raise ValueError(f'Invalid INM retrieval manifest: {identifier}')
    retrieval_directory = (directory / retrieval_reference['file']).resolve().parent
    name_fields = {'nomAr': 'intituleAr', 'nomFr': 'intituleFr', 'nomEn': 'intituleAn'}
    for resource, endpoint, time_fields in (
            ('prayer', 'horaire_gouvernorat', ('sobh', 'dhohr', 'aser', 'magreb', 'isha')),
            ('sun', 'lever_coucher_gouvernorat', ('lever', 'pm', 'coucher'))):
        reference = evidence[resource]
        expected_url = (f'https://www.meteo.tn/{endpoint}/{service_date}/'
                        f'{governor["id"]}/{identifier}/')
        if (not isinstance(reference, dict) or set(reference) != {'file', 'sha256', 'url'}
                or reference['url'] != expected_url):
            raise ValueError(f'Invalid INM {resource} response reference: {identifier}')
        response_bytes = aggregate_review_file(directory, reference, f'INM {resource} response')
        response_path = (directory / reference['file']).resolve()
        # Original retrieval filenames are relative to their own manifest. Moving
        # that complete source folder preserves the original retrieval bytes.
        matching_retrievals = [record for record in retrieval['responses']
                               if isinstance(record, dict) and isinstance(record.get('file'), str)
                               and record['file']
                               and (retrieval_directory / record['file']).resolve() == response_path]
        if len(matching_retrievals) != 1:
            raise ValueError(f'Missing or duplicate INM {resource} retrieval: {identifier}')
        record = matching_retrievals[0]
        if (record.get('sha256') != reference['sha256']
                or record.get('requestedUrl') != expected_url or record.get('finalUrl') != expected_url
                or type(record.get('status')) is not int or record['status'] != 200):
            raise ValueError(f'INM {resource} retrieval provenance changed: {identifier}')
        response = json.loads(response_bytes)
        if (not isinstance(response, dict) or response.get('method') != 'GET'
                or not isinstance(response.get('data'), dict)):
            raise ValueError(f'Invalid INM {resource} response: {identifier}')
        data = response['data']
        source_governor, source_delegation = data.get('gouvernorat'), data.get('delegation')
        if (type(data.get('id')) is not int or data['id'] <= 0
                or not isinstance(source_governor, dict) or not isinstance(source_delegation, dict)
                or type(source_governor.get('id')) is not int or source_governor['id'] != governor['id']
                or type(source_delegation.get('id')) is not int or source_delegation['id'] != identifier
                or source_delegation.get('parent') != source_governor
                or any(source_governor.get(source_key) != governor.get(asset_key)
                       for asset_key, source_key in name_fields.items())
                or not inm_source_delegation_names_match(source_delegation, delegation, governor)):
            raise ValueError(f'INM {resource} response identity changed: {identifier}')
        if (data.get('date') != f'{service_date} 00:00'
                or type(data.get('annee')) is not int or data['annee'] != service_year
                or data.get('active') is not True
                or any(not isinstance(data.get(field), str)
                       or not re.fullmatch(r'(?:[01][0-9]|2[0-3]):[0-5][0-9]', data[field])
                       for field in time_fields)):
            raise ValueError(f'INM {resource} service/date fields changed: {identifier}')
        coordinates = {}
        for key, limit in (('lat', 90), ('lng', 180)):
            value = data.get(key)
            if (not isinstance(value, str)
                    or not re.fullmatch(r'[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)', value.strip())):
                raise ValueError(f'Invalid INM {resource} coordinate string: {identifier}')
            number = float(value.strip())
            if not math.isfinite(number) or not -limit <= number <= limit:
                raise ValueError(f'Invalid INM {resource} coordinate range: {identifier}')
            coordinates[key] = number
        if coordinates != correction['proposed']:
            raise ValueError(f'INM {resource} coordinates differ from reviewed asset point: {identifier}')
    return {'delegationId': identifier, 'governorateId': governor['id'], **correction['proposed'],
            'serviceDate': service_date,
            'sourceEvidence': {key: evidence[key] for key in ('prayer', 'sun', 'retrievalManifest')}}


def validate_prayer_source_coordinates(governors, manifest_path):
    """Keep runtime anchors and generated nearest-source mappings in agreement.

    Reviewed settlement points and published INM references do not certify INM
    calculation inputs. Refuse changed points instead of overriding an in-memory
    copy while the app would still ship different coordinates.
    """
    raw = Path(manifest_path).read_bytes()
    manifest = json.loads(raw)
    corrections = manifest.get('corrections')
    if (manifest.get('schemaVersion') != 1 or not manifest.get('reviewedDate')
            or not isinstance(corrections, list) or not corrections):
        raise ValueError('Invalid prayer source coordinate curation')
    by_id = {}
    for governor in governors:
        for delegation in governor['delegations']:
            if delegation['id'] in by_id:
                raise ValueError('Duplicate prayer source ID')
            by_id[delegation['id']] = (governor, delegation)
    reviewed = set()
    official_references = []
    for correction in corrections:
        identifier = correction.get('delegationId')
        if identifier not in by_id or identifier in reviewed:
            raise ValueError(f'Missing or duplicate curated prayer source: {identifier}')
        governor, delegation = by_id[identifier]
        expected = correction.get('expectedNames', {})
        if (governor['id'] != correction.get('expectedGovernorateId')
                or set(expected) != {'nomAr', 'nomFr', 'nomEn'}
                or any(delegation.get(key) != value for key, value in expected.items())):
            raise ValueError(f'Curated prayer source identity changed: {identifier}')
        for field in ('original', 'proposed'):
            point = correction.get(field, {})
            if (set(point) != {'lat', 'lng'}
                    or any(type(value) not in (int, float) or not math.isfinite(value) for value in point.values())
                    or not (-90 <= point['lat'] <= 90 and -180 <= point['lng'] <= 180)):
                raise ValueError(f'Invalid curated prayer source {field} point: {identifier}')
        if any(delegation.get(key) != value for key, value in correction['proposed'].items()):
            raise ValueError(f'Prayer source coordinates need review: {identifier}; restore the reviewed asset point or update its curation evidence')
        if not correction.get('reviewedDate') or not correction.get('originalProvenance'):
            raise ValueError(f'Undocumented prayer source coordinate correction: {identifier}')
        if 'referenceKind' not in correction:
            if not all(correction.get('osm', {}).get(key) for key in ('id', 'url', 'version', 'timestamp')):
                raise ValueError(f'Undocumented prayer source coordinate correction: {identifier}')
        elif correction['referenceKind'] == 'inm_published_reference':
            official_references.append(validate_inm_published_reference(
                correction, governor, delegation, Path(manifest_path).parent))
        else:
            raise ValueError(f'Unknown prayer source reference kind: {identifier}')
        reviewed.add(identifier)
    report = {'manifestSha256': hashlib.sha256(raw).hexdigest(),
            'reviewedDate': manifest['reviewedDate'], 'correctedSourceIds': sorted(reviewed),
            'settlementReferences': [{'delegationId': correction['delegationId'],
                                      'governorateId': correction['expectedGovernorateId'],
                                      'pointId': correction['osm']['id'], **correction['proposed']}
                                     for correction in corrections if 'referenceKind' not in correction],
            'scope': manifest['scope']}
    if official_references:
        report['officialPublishedReferences'] = official_references
    return report


def validate_reviewed_point_base_name(rule, manifest_path, coordinate_path, obj, original_sources,
                                      effective_sources, current, base, governor, source_sha256):
    """Allow only an evidenced, exact point/base primary-name identity.

    This does not infer spelling similarity. The existing base-link coordinate,
    governorate, source-point and nearest-timetable checks remain mandatory.
    """
    try:
        directory = Path(manifest_path).parent
        review = json.loads(aggregate_review_file(directory, rule['pickerBaseNameReview'], 'point/base name review'))
        proposal = json.loads(aggregate_review_file(directory, review['sourceProposal'], 'point/base identity proposal'))
        pair = proposal['exactPair']
        identifier, target = obj['id'], rule['pickerBaseDelegationId']
        expected = pair['rawCurrent']
        expected_base = {k: v for k, v in pair['baseCurrent'].items() if k != 'governorateId'}
        if (review.get('schemaVersion') != 1 or review.get('status') != 'reviewed_exact_point_base_name_identity'
                or review.get('sourceSha256') != source_sha256
                or proposal.get('status') != 'SUPPORTED_EXACT_POINT_BASE_IDENTITY_PROPOSAL_ONLY'
                or review['pointId'] != identifier or pair['rawPointId'] != identifier
                or review['baseId'] != target or pair['basePickerId'] != f'delegation:{target}'
                or rule['action'] != 'preserve_point' or rule['expectedTags'] != obj['tags']
                or review['pointNameAr'] != current['name'] or current['name'] != pair['pointNameAr']
                or review['baseNameAr'] != base['nomAr'] or base['nomAr'] != pair['baseNomAr']
                or norm(current['name']) == norm(base['nomAr'])
                or base != expected_base or governor['id'] != pair['baseCurrent']['governorateId']
                or {k: v for k, v in governor.items() if k != 'delegations'} != review['expectedGovernor']
                or current != {k: v for k, v in expected.items() if k != 'pickerGroupId'}
                or expected['pickerGroupId'] != identifier or expected['hasBoundary'] is not False):
            raise ValueError('Reviewed point/base exact labels or current identity changed')
        # Pin this correction object, not the whole manifest: unrelated appended
        # reference corrections cannot change the reviewed identity of this pair.
        corrections = json.loads(display_identity_coordinate_bytes(Path(coordinate_path).parent))['corrections']
        matches = [r for r in corrections if r['delegationId'] == target]
        correction = proposal['identityEvidence']['approvedSourceCoordinateCuration']['exactCorrection']
        if (matches != [correction] or hashlib.sha256(json.dumps(correction, ensure_ascii=False,
                sort_keys=True, separators=(',', ':')).encode('utf-8')).hexdigest() != review['correctionSha256']
                or correction['osm']['id'] != identifier or correction['osm']['tags'] != obj['tags']
                or correction['proposed'] != {'lat': current['lat'], 'lng': current['lng']}):
            raise ValueError('Reviewed point/base settlement-reference correction changed')
        node = json.loads(aggregate_review_file(directory, review['sourceNode'], 'named source point'))['elements']
        if (len(node) != 1 or node[0]['type'] != 'node' or f"osm:node:{node[0]['id']}" != identifier
                or node[0]['tags'] != obj['tags'] or node[0]['lat'] != current['lat']
                or node[0]['lon'] != current['lng']
                or any(node[0][k] != correction['osm'][k] for k in ('version', 'timestamp', 'changeset'))
                or review['sourceNode'] != correction['osmEvidence']['liveResponse']):
            raise ValueError('Reviewed exact source node snapshot changed')
        aggregate_review_file(directory, review['primaryMinistryPage'], 'official locality spelling')
        aggregate_review_file(directory, review['coordinateSourceReview'], 'settlement-reference identity')
        if (review['primaryMinistryPage'] != {k: correction['primaryCorroboration'][k] for k in ('file', 'sha256')}
                or review['coordinateSourceReview'] not in correction['sourceReviews']):
            raise ValueError('Reviewed locality spelling corroboration changed')
        registry = json.loads(aggregate_review_file(directory, review['officialRegistry'], 'official sector identities'))
        protected = proposal['preservedSectors']
        official = [r for r in registry['sectors'] if r['delegationCode'] == review['officialDelegationCode']]
        if (official != [r['officialRecord'] for r in protected] or not protected
                or len({r['id'] for r in protected}) != len(protected)
                or review['officialDelegationCode'] != proposal['identityEvidence']['officialINSIdentity']['delegationCode']):
            raise ValueError('Reviewed complete administrative identity inventory changed')
        peers = {r['id']: r for r in proposal['exactNamePeerScreen']['sourcePeers']}
        peer_keys = {norm(v) for v in [current['name'], base['nomAr'], base['nomFr'], *current['aliases']]}
        for sources, expected_provider in ((original_sources, None), (effective_sources, 'osm')):
            point = sources[identifier]
            if (point['kind'] != current['kind'] or point.get('sourceId') != expected_provider
                    or point['tags'] != obj['tags'] or point.get('shape') is not None
                    or point['point'].geom_type != 'Point'
                    or point['point'].coords[:] != [(current['lng'], current['lat'])]
                    or {i for i, s in sources.items() if peer_keys & {norm(v) for v in names(s['tags'])}} != set(peers)):
                raise ValueError('Reviewed original/effective source point or name-peer inventory changed')
            for peer_id, peer in peers.items():
                if sources[peer_id]['tags'] != peer['tags']:
                    raise ValueError('Reviewed source-name peer identity changed')
            for row in protected:
                sector = sources[row['id']]
                if (sector['kind'] != 'sector'
                        or sector['tags'].get('ref:tn:codegeo') != row['officialRecord']['sectorCode']):
                    raise ValueError('Reviewed imada identity changed')
        return proposal
    except (OSError, UnicodeError, ValueError, KeyError, TypeError, AttributeError, IndexError) as error:
        raise ValueError('Missing, malformed or changed reviewed point/base name identity') from error


def sector_owned_source_state(source):
    """Describe an actual source object without fabricating an OSM entity."""
    geometry = source.get('shape')
    if geometry is None:
        geometry = source.get('point')
    if geometry is None or geometry.is_empty or geometry.geom_type not in ('Point', 'Polygon', 'MultiPolygon'):
        raise ValueError('Reviewed sector-owned settlement source geometry is missing')
    return {'tags': source['tags'], 'kind': source['kind'], 'sourceId': source.get('sourceId'),
            'geometrySha256': aggregate_geometry_sha256(geometry)}


def derive_sector_owned_settlement_closure(extension, features, source_phases, contexts, peer_keys, after):
    """Derive complete identities from all rows and both real source phases."""
    current = {row['id']: row for row in features}
    if len(current) != len(features):
        raise ValueError('Reviewed sector-owned settlement repeats a raw ID')
    rows = {row['id']: row for row in extension['protectedRawRecords']}
    states = {row['id']: row for row in extension['sourceStates']}
    groups = defaultdict(set)
    for row in features:
        groups[row['pickerGroupId']].add(row['id'])
    closure = extension['rawContextClosure']
    local = sorted(row['id'] for row in features if row.get('governorateId') == closure['governorateId']
                   and (row.get('parentName') == closure['administrativeParentName']
                        or closure['administrativeParentName'] in row.get('contextAliases', [])))
    if local != closure['expectedRawIds'] or local != extension['localProtectedRawIds']:
        raise ValueError('Reviewed sector-owned settlement complete administrative raw closure changed')
    sectors = set(closure['sourceSectorIds']['original'])
    identifier = extension['onlyRawChange']['id']
    if not sectors | {identifier} <= set(current):
        raise ValueError('Reviewed sector-owned settlement lost a point or sector')
    seed_groups = {current[sid]['pickerGroupId'] for sid in sectors | {identifier}}
    protected = {sid for group in seed_groups for sid in groups[group]} | set(local)
    required_sources = set(contexts)
    for phase, sources in source_phases.items():
        sector_ids = sorted(sid for sid, source in sources.items() if source['kind'] == 'sector'
                            and source['tags'].get('ref:tn:codegeo', '')[:4] in extension['officialDelegationCodes'])
        if sector_ids != closure['sourceSectorIds'][phase]:
            raise ValueError('Reviewed sector-owned settlement complete source-sector inventory changed')
        geometries = [sources[sid]['shape'] for sid in sector_ids]
        covered_raw = sorted(row['id'] for row in features
                             if any(geometry.covers(Point(row['lng'], row['lat'])) for geometry in geometries))
        covered_points = sorted(sid for sid, source in sources.items() if source.get('point') is not None
                                and source.get('shape') is None
                                and any(geometry.covers(source['point']) for geometry in geometries))
        peers = sorted(sid for sid, source in sources.items()
                       if peer_keys & {norm(value) for value in names(source['tags'])})
        if (covered_raw != closure['fullRowRepresentativeSectorCoverage'][phase]
                or covered_points != closure['fullSourcePointCoverage'][phase]
                or peers != extension['sourceNamePeerIds'][phase]):
            raise ValueError('Reviewed sector-owned settlement global row, point or name-peer closure changed')
        protected.update(covered_raw)
        protected.update(set(covered_points) & set(current))
        protected.update(set(peers) & set(current))
        required_sources.update(covered_points)
        required_sources.update(peers)
        for sid, record in states.items():
            if sid not in sources or sector_owned_source_state(sources[sid]) != record[phase]:
                raise ValueError('Reviewed sector-owned settlement exact source phase changed')
    while True:
        expanded = protected | {sid for rid in protected for sid in groups[current[rid]['pickerGroupId']]}
        if expanded == protected:
            break
        protected = expanded
    if protected != set(rows) or required_sources | protected != set(states):
        raise ValueError('Reviewed sector-owned settlement hidden or lost raw/source member')
    expected = extension['expectedAfterRawGroups' if after else 'expectedBeforeRawGroups']
    actual = {group: sorted(groups[group]) for group in {current[sid]['pickerGroupId'] for sid in protected}}
    if actual != expected:
        raise ValueError('Reviewed sector-owned settlement complete raw group membership changed')
    for sid, state in states.items():
        if (sid in current) != state['rawPresent']:
            raise ValueError('Reviewed sector-owned settlement source-only presence changed')
    return current, groups


def validate_sector_owned_settlement_preservation(directory, review, rule, registry, original_sources,
                                                   effective_sources, curation, source_sha256,
                                                   reviewed_boundaries, official_report, picker_path):
    """Validate a village/town joining its already-reviewed sector-owned base."""
    extension = review['sectorOwnedSettlementPreservation']
    keys = {'schemaVersion', 'strictOwner', 'officialDelegationCodes', 'localProtectedRawIds',
            'readOnlyRawNamePeers', 'sourceOnlyAbsorbedPoints', 'rawContextClosure', 'protectedRawRecords',
            'sourceStates', 'curationBindings', 'acceptedBoundaryReplacements', 'reviewedBoundaries',
            'sourceNamePeerIds', 'coordinateContextMembership', 'expectedBeforeRawGroups',
            'expectedAfterRawGroups', 'completePickerGroups', 'onlyRawChange'}
    if (review['method'] != 'reviewed_explicit_point_base_display_identity'
            or any(key in review for key in ('townPreservationExtension', 'deferredCompleteGroupExtension',
                'typedNamePeerExtension', 'sourcePhaseLineage', 'retainedTownPoint', 'officialDelegationCode'))
            or not isinstance(extension, dict) or set(extension) != keys
            or type(extension['schemaVersion']) is not int or extension['schemaVersion'] != 1):
        raise ValueError('Malformed or mixed sector-owned settlement preservation contract')
    identifier, target = review['pointId'], f"delegation:{review['baseId']}"
    change = {'id': identifier, 'field': 'pickerGroupId', 'before': identifier, 'after': target}
    if extension['onlyRawChange'] != change or review['onlyRawChange'] != change:
        raise ValueError('Reviewed sector-owned settlement requested more than point membership')
    reviewed_complete_group_owner(
        picker_path, extension['strictOwner'], source_sha256, review['baseId'],
        original_sources=original_sources, effective_sources=effective_sources,
        reviewed_boundaries=reviewed_boundaries, official_report=official_report)
    owner = extension['strictOwner']['recordId']
    sectors = review['preservedSectors']; sector_ids = {row['id'] for row in sectors}
    contexts = review['contexts']; codes = extension['officialDelegationCodes']
    if (not isinstance(codes, list) or len(codes) != 1 or not re.fullmatch(r'[0-9]{4}', codes[0])
            or not sectors or len(sector_ids) != len(sectors) or owner not in sector_ids
            or sorted([r for r in registry['sectors'] if r['delegationCode'] in codes], key=lambda r: r['sectorCode'])
                != sorted([r['officialIdentity'] for r in sectors], key=lambda r: r['sectorCode'])
            or len({r['officialIdentity']['sectorCode'] for r in sectors}) != len(sectors)
            or not isinstance(contexts, dict) or set(contexts) & sector_ids
            or sorted(c['kind'] for c in contexts.values()) != ['delegation', 'governorate']):
        raise ValueError('Reviewed sector-owned settlement complete official sector/context inventory changed')
    parent_id = next(sid for sid, context in contexts.items() if context['kind'] == 'delegation')
    governor_id = next(sid for sid, context in contexts.items() if context['kind'] == 'governorate')
    if (contexts[parent_id]['tags'].get('ref:tn:codegeo') != codes[0]
            or contexts[governor_id]['tags'].get('ref:tn:codegeo') != codes[0][:2]
            or contexts[parent_id]['tags'].get('name:ar') != 'معتمدية ' + review['baseCurrent']['nomAr']
            or picker_review_name_key(contexts[governor_id]['tags'].get('name:ar', ''))
                != picker_review_name_key('ولاية ' + review['expectedGovernor']['nomAr'])):
        raise ValueError('Reviewed sector-owned settlement real administrative identity changed')
    records = extension['sourceStates']; protected = extension['protectedRawRecords']
    states = {r['id']: r for r in records}; raw = {r['id']: r for r in protected}
    state_keys = {'tags', 'kind', 'sourceId', 'geometrySha256'}
    if (not records or [r['id'] for r in records] != sorted(states)
            or any(set(r) != {'id', 'rawPresent', 'expectedCuration', 'original', 'effective'}
                   or type(r['rawPresent']) is not bool
                   or any(set(r[phase]) != state_keys for phase in ('original', 'effective')) for r in records)
            or not protected or [r['id'] for r in protected] != sorted(raw)
            or any(set(r) != {'id', 'kind', 'currentMetadata', 'packedGeometrySha256'} for r in protected)
            or not sector_ids | {identifier} <= set(raw) or set(raw) & set(contexts)
            or {sid for sid, r in states.items() if r['rawPresent']} != set(raw)
            or not isinstance(extension['curationBindings'], dict)
            or set(extension['curationBindings']) != set(states)
            or extension['localProtectedRawIds'] != sorted(set(extension['localProtectedRawIds']))
            or not sector_ids | {identifier} <= set(extension['localProtectedRawIds'])
            or raw[identifier]['currentMetadata'] != review['rawCurrent']):
        raise ValueError('Reviewed sector-owned settlement typed source/raw inventory is malformed')
    bindings = extension['curationBindings']
    if bindings[identifier] is not None or curation.get(identifier) != rule:
        raise ValueError('Reviewed sector-owned settlement new point curation differs')
    primary_name = norm(review['rawCurrent']['name'])
    if (not primary_name or norm(review['baseCurrent']['nomAr']) != primary_name
            or norm(raw[owner]['currentMetadata']['name']) != primary_name):
        raise ValueError('Reviewed sector-owned settlement lacks the exact shared primary place name')
    phases = {'original': original_sources, 'effective': effective_sources}
    for sid, record in states.items():
        if record['expectedCuration'] != bindings[sid] or (sid != identifier and curation.get(sid) != bindings[sid]):
            raise ValueError('Reviewed sector-owned settlement existing curation binding changed')
        decision = bindings[sid]
        if decision is not None:
            if (decision['id'] != sid or decision.get('action') not in ('name_tags', 'preserve_point', 'administrative_context')):
                raise ValueError('Unsupported sector-owned settlement source curation')
            for reference in decision.get('evidence', []):
                if isinstance(reference, dict) and 'file' in reference:
                    aggregate_review_file(directory, reference, 'preserved sector-owned settlement curation evidence')
        for phase, sources in phases.items():
            if (sid not in sources or sector_owned_source_state(sources[sid]) != record[phase]
                    or (phase == 'original' and record[phase]['sourceId'] is not None)
                    or record['original']['kind'] != record['effective']['kind']):
                raise ValueError('Reviewed sector-owned settlement authentic source phase changed')
    replacements = extension['acceptedBoundaryReplacements']; replaced = {r['id']: r for r in replacements}
    if ([r['id'] for r in replacements] != sorted(replaced)
            or set(replaced) != {sid for sid, r in states.items() if r['effective']['sourceId'] != 'osm'}
            or not set(replaced) <= sector_ids):
        raise ValueError('Reviewed sector-owned settlement accepted replacement coverage changed')
    manifest = None
    if replacements:
        reference = extension['reviewedBoundaries']
        manifest = load_reviewed_boundary_manifest_for_lineage(
            directory, reference, reviewed_boundaries, official_report)
    elif extension['reviewedBoundaries'] is not None:
        raise ValueError('Reviewed sector-owned settlement has an unexpected replacement manifest')
    for sid, record in states.items():
        original, effective = record['original'], record['effective']
        tags = dict(original['tags']); geometry_sha = original['geometrySha256']; provider = 'osm'
        if sid in replaced:
            replacement = replaced[sid]
            if set(replacement) != {'id', 'sourceRecord', 'record', 'application', 'geojson', 'featureSha256', 'providerEvidence'}:
                raise ValueError('Malformed sector-owned settlement accepted replacement')
            source, accepted = replacement['sourceRecord'], replacement['record']
            application = {'id': sid, 'action': 'replace', 'officialCode': accepted['officialCode'], 'sourceId': source['id']}
            if ([s for s in manifest['sources'] if s['id'] == source['id']] != [source]
                    or [r for r in source['records'] if r['id'] == sid] != [accepted]
                    or accepted['action'] != 'replace' or source['sourceSha256'] != source_sha256
                    or source['id'] == 'osm' or original['kind'] != 'sector'
                    or not accepted['expectedOriginalTags']
                    or any(original['tags'].get(k) != v for k, v in accepted['expectedOriginalTags'].items())
                    or accepted['officialCode'] != original['tags'].get('ref:tn:codegeo')
                    or replacement['application'] != application
                    or [r for r in official_report['applications'] if r['id'] == sid] != [application]
                    or official_report['sources'].get(source['id']) != {k: v for k, v in source.items() if k not in ('file', 'records')}
                    or replacement['geojson'] != {'file': source['file'], 'sha256': source['sha256']}
                    or (bindings[sid] is not None and 'nameTags' in bindings[sid])):
                raise ValueError('Reviewed sector-owned settlement replacement differs from the accepted loader')
            collection = json.loads(aggregate_review_file(Path(reviewed_boundaries).parent,
                                    replacement['geojson'], 'sector-owned accepted boundary feature'))
            matches = [f for f in collection['features'] if f['id'] == sid]
            if len(matches) != 1:
                raise ValueError('Reviewed sector-owned settlement accepted feature is not unique')
            feature = matches[0]; properties = feature['properties']
            if (feature['type'] != 'Feature' or properties['sourceId'] != source['id']
                    or properties['officialCode'] != accepted['officialCode']
                    or hashlib.sha256(json.dumps(feature, ensure_ascii=False, sort_keys=True,
                        separators=(',', ':')).encode('utf-8')).hexdigest() != replacement['featureSha256']):
                raise ValueError('Reviewed sector-owned settlement accepted feature changed')
            proof = replacement['providerEvidence']
            aggregate_review_file(directory, proof, 'sector-owned accepted provider decision')
            if ((directory / proof['file']).resolve() != (Path(reviewed_boundaries).parent / source['review']['evidenceFile']).resolve()
                    or proof['sha256'] != source['review']['evidenceSha256']):
                raise ValueError('Reviewed sector-owned settlement provider decision is not active')
            tags = {'boundary': 'administrative', 'admin_level': '6', 'ref:tn:codegeo': accepted['officialCode'],
                    'name:ar': properties['nameAr'], 'name:fr': properties['nameFr']}
            if accepted.get('aliases'):
                tags['alt_name'] = ';'.join(accepted['aliases'])
            geometry_sha = aggregate_geometry_sha256(shape(feature['geometry'])); provider = source['id']
        if bindings[sid] is not None and 'nameTags' in bindings[sid]:
            tags = {k: v for k, v in tags.items() if not is_current_name_tag(k)}
            tags.update(bindings[sid]['nameTags'])
        if effective != {'tags': tags, 'kind': original['kind'], 'sourceId': provider, 'geometrySha256': geometry_sha}:
            raise ValueError('Reviewed sector-owned settlement source transformation is unaccounted for')
    for sector in sectors:
        sid = sector['id']
        if (sector['originalTags'] != states[sid]['original']['tags']
                or sector['sourceGeometrySha256'] != states[sid]['original']['geometrySha256']
                or sector['originalTags'].get('ref:tn:codegeo') != sector['officialIdentity']['sectorCode']
                or sector['expectedCuration'] != bindings[sid]
                or sector['currentMetadata'] != raw[sid]['currentMetadata']
                or sector['packedGeometrySha256'] != raw[sid]['packedGeometrySha256']):
            raise ValueError('Reviewed sector-owned settlement preserved sector bindings disagree')
        for sources in phases.values():
            if [i for i, s in sources.items() if s['kind'] == 'sector'
                    and s['tags'].get('ref:tn:codegeo') == sector['officialIdentity']['sectorCode']] != [sid]:
                raise ValueError('Reviewed sector-owned settlement coded source is not unique')
    for sid, context in contexts.items():
        original = states[sid]['original']
        if (context != {'kind': original['kind'], 'tags': original['tags'],
                        'sourceGeometrySha256': original['geometrySha256']} or bindings[sid] is not None):
            raise ValueError('Reviewed sector-owned settlement read-only context changed')
        for sources in phases.values():
            if [i for i, s in sources.items() if s['kind'] == context['kind']
                    and s['tags'].get('ref:tn:codegeo') == context['tags']['ref:tn:codegeo']] != [sid]:
                raise ValueError('Reviewed sector-owned settlement source context is not unique')
    peers = extension['readOnlyRawNamePeers']; foreign = {r['id']: r for r in peers}
    if [r['id'] for r in peers] != sorted(foreign) or set(raw) - set(extension['localProtectedRawIds']) != set(foreign):
        raise ValueError('Reviewed sector-owned settlement foreign peer closure changed')
    coordinates = {'point': Point(review['rawCurrent']['lng'], review['rawCurrent']['lat']),
                   'base': Point(review['baseCurrent']['lng'], review['baseCurrent']['lat'])}
    for sid, peer in foreign.items():
        expected_keys = {'id', 'reason', 'officialIdentity', 'original', 'effective', 'currentMetadata',
                         'packedGeometrySha256', 'expectedCuration', 'expectedRawGroup',
                         'candidatePointMembership', 'candidateBaseMembership'}
        official = peer['officialIdentity']; original = states[sid]['original']; effective = states[sid]['effective']
        if (set(peer) != expected_keys or original['kind'] != 'sector' or effective['kind'] != 'sector'
                or peer['original'] != original or peer['effective'] != effective
                or original['tags'] != effective['tags'] or original['geometrySha256'] != effective['geometrySha256']
                or effective['sourceId'] != 'osm' or original['tags'].get('ref:tn:codegeo') != official['sectorCode']
                or [r for r in registry['sectors'] if r['sectorCode'] == official['sectorCode']] != [official]
                or official['delegationCode'] in codes or official['governorateCode'] == codes[0][:2]
                or peer['currentMetadata'] != raw[sid]['currentMetadata']
                or peer['currentMetadata']['governorateId'] == review['expectedGovernor']['id']
                or peer['packedGeometrySha256'] != raw[sid]['packedGeometrySha256']
                or peer['expectedCuration'] is not None or bindings[sid] is not None
                or peer['expectedRawGroup'] != [sid]
                or any(peer[key] != {'original': False, 'effective': False, 'packed': False}
                       for key in ('candidatePointMembership', 'candidateBaseMembership'))):
            raise ValueError('Reviewed sector-owned settlement distinct foreign namesake changed')
        for sources in phases.values():
            if (any(sources[sid]['shape'].covers(point) for point in coordinates.values())
                    or [i for i, source in sources.items() if source['kind'] == 'sector'
                        and source['tags'].get('ref:tn:codegeo') == official['sectorCode']] != [sid]):
                raise ValueError('Reviewed sector-owned settlement foreign peer is not distinct')
    for sid, row in raw.items():
        current = row['currentMetadata']; source = effective_sources[sid]; polygon = source.get('shape') is not None
        if (current['id'] != sid or current['kind'] != row['kind'] or row['kind'] != source['kind']
                or current['sourceId'] != source.get('sourceId') or type(current['hasBoundary']) is not bool
                or current['hasBoundary'] != polygon
                or (sid not in foreign and current['governorateId'] != review['expectedGovernor']['id'])
                or (polygon and (source['shape'].geom_type not in ('Polygon', 'MultiPolygon')
                    or not isinstance(row['packedGeometrySha256'], str)
                    or not re.fullmatch(r'[0-9a-f]{64}', row['packedGeometrySha256'])))
                or (not polygon and (source['point'].geom_type != 'Point'
                    or source['point'].coords[:] != [(current['lng'], current['lat'])]
                    or row['packedGeometrySha256'] is not None or 'offset' in current or 'length' in current))):
            raise ValueError('Reviewed sector-owned settlement protected raw source representation changed')
    absent = extension['sourceOnlyAbsorbedPoints']; absent_ids = {r['id'] for r in absent}
    if ([r['id'] for r in absent] != sorted(absent_ids)
            or set(states) != set(raw) | set(contexts) | absent_ids):
        raise ValueError('Reviewed sector-owned settlement source-only inventory changed')
    for record in absent:
        sid = record['id']; receiver = record['absorbedBySectorId']
        expected_keys = {'id', 'original', 'effective', 'exactCachedSource', 'expectedCuration', 'expectedRawPresence',
                         'expectedGroupPresence', 'matchingPackedRawReceiverIds', 'sourceSectorCoveringIds',
                         'absorbedBySectorId', 'receiverOriginalSourceNameIntersection', 'qualification'}
        cached = record['exactCachedSource']; source = original_sources[sid]; point = source.get('point')
        if (set(record) != expected_keys or receiver not in sector_ids or sid in raw or sid in contexts
                or record['original'] != states[sid]['original'] or record['effective'] != states[sid]['effective']
                or source['kind'] not in ('town', 'village') or point is None or source.get('shape') is not None
                or cached != {'id': sid, 'tags': source['tags'], 'lat': point.y, 'lng': point.x}
                or record['expectedRawPresence'] is not False or record['expectedGroupPresence'] is not False
                or record['expectedCuration'] is not None or bindings[sid] is not None
                or record['matchingPackedRawReceiverIds'] != [receiver] or record['sourceSectorCoveringIds'] != [receiver]):
            raise ValueError('Reviewed sector-owned settlement absorbed source identity changed')
        for sources in phases.values():
            if (sources[sid].get('shape') is not None or sources[sid]['point'].coords[:] != point.coords[:]
                    or sources[sid]['tags'] != cached['tags']
                    or sorted(i for i in sector_ids if sources[i]['shape'].covers(point)) != [receiver]
                    or sorted({norm(v) for v in names(sources[sid]['tags'])}
                        & {norm(v) for v in names(sources[receiver]['tags'])}) != record['receiverOriginalSourceNameIntersection']
                    or not record['receiverOriginalSourceNameIntersection']):
                raise ValueError('Reviewed sector-owned settlement absorbed source/receiver phase changed')
    before, after = extension['expectedBeforeRawGroups'], extension['expectedAfterRawGroups']
    if (not isinstance(before, dict) or not isinstance(after, dict)
            or any(not isinstance(g, str) or not isinstance(ids, list) or not ids or ids != sorted(set(ids))
                   for g, ids in before.items())
            or before.get(identifier) != [identifier] or before.get(target) != [owner]
            or any(before.get(sid) != [sid] for sid in sector_ids - {owner})
            or any(before.get(sid) != [sid] for sid in foreign)
            or after != {**{g: ids for g, ids in before.items() if g != identifier}, target: sorted([owner, identifier])}
            or extension['completePickerGroups'] != {phase: {g: sorted(ids + ([g] if g.startswith('delegation:') else []))
                for g, ids in groups.items()} for phase, groups in (('before', before), ('after', after))}
            or review['completePickerGroups'] != extension['completePickerGroups']):
        raise ValueError('Reviewed sector-owned settlement group transition is not the exact point append')
    closure = extension['rawContextClosure']
    closure_keys = {'baselineMetadata', 'governorateId', 'administrativeParentName', 'expectedRawIds',
                    'sourceSectorIds', 'fullRowRepresentativeSectorCoverage', 'fullSourcePointCoverage'}
    if (not isinstance(closure, dict) or set(closure) != closure_keys
            or closure['governorateId'] != review['expectedGovernor']['id']
            or closure['administrativeParentName'] != contexts[parent_id]['tags']['name:ar']
            or any(not isinstance(closure[key], dict) or set(closure[key]) != {'original', 'effective'}
                   for key in ('sourceSectorIds', 'fullRowRepresentativeSectorCoverage', 'fullSourcePointCoverage'))
            or any(closure['sourceSectorIds'][phase] != sorted(sector_ids) for phase in phases)):
        raise ValueError('Malformed sector-owned settlement complete-row closure evidence')
    baseline = json.loads(aggregate_review_file(directory, closure['baselineMetadata'], 'sector-owned full baseline raw catalog'))['features']
    baseline_rows = {row['id']: row for row in baseline}
    if len(baseline_rows) != len(baseline) or any(baseline_rows.get(sid) != row['currentMetadata'] for sid, row in raw.items()):
        raise ValueError('Reviewed sector-owned settlement baseline raw records changed')
    peer_keys = {norm(value) for value in [review['rawCurrent']['name'], *review['rawCurrent']['aliases'],
                 review['baseCurrent']['nomAr'], review['baseCurrent']['nomFr'], review['baseCurrent']['nomEn']]}
    if (not isinstance(extension['sourceNamePeerIds'], dict) or set(extension['sourceNamePeerIds']) != set(phases)
            or sorted(review['sourceNamePeers'], key=lambda r: r['id']) != [{'id': sid,
                'tags': states[sid]['original']['tags'], 'kind': states[sid]['original']['kind']}
                for sid in extension['sourceNamePeerIds']['original']]):
        raise ValueError('Reviewed sector-owned settlement top-level source-name peers disagree')
    derive_sector_owned_settlement_closure(extension, baseline, phases, contexts, peer_keys, False)
    membership = extension['coordinateContextMembership']
    if not isinstance(membership, dict) or set(membership) != sector_ids | set(contexts):
        raise ValueError('Reviewed sector-owned settlement coordinate-context inventory is incomplete')
    for sid in membership:
        if not isinstance(membership[sid], dict) or set(membership[sid]) != set(phases):
            raise ValueError('Malformed sector-owned settlement coordinate source phases')
        for phase, sources in phases.items():
            geometry = sources[sid]['shape']
            if (set(membership[sid][phase]) != set(coordinates)
                    or any(membership[sid][phase][role] != {'contains': geometry.contains(point), 'covers': geometry.covers(point)}
                           or any(type(v) is not bool for v in membership[sid][phase][role].values())
                           for role, point in coordinates.items())):
                raise ValueError('Reviewed sector-owned settlement original/effective coordinate context changed')
            if sid in contexts and not all(membership[sid][phase][role]['contains'] for role in coordinates):
                raise ValueError('Reviewed sector-owned settlement lacks positive real administrative context')
    # These dictionaries are in-memory loader objects, never output evidence files.
    return {'exactPair': {'basePickerId': target, 'rawCurrent': review['rawCurrent']}, 'preservedSectors': [],
            'sectorOwnedSettlementPreservation': {'proof': extension, 'contexts': contexts,
                'sourcePhases': phases, 'peerKeys': peer_keys, 'sourceSha256': source_sha256,
                'baseCurrent': review['baseCurrent'], 'expectedGovernor': review['expectedGovernor'],
                'curation': curation, 'pointRule': rule}}


def verify_sector_owned_settlement_state(proposal, features, geometries, base_groups, after):
    """Recheck complete source, raw, packed-shape and owner state at both phases."""
    runtime = proposal['sectorOwnedSettlementPreservation']; extension = runtime['proof']
    identifier = extension['onlyRawChange']['id']; target = extension['onlyRawChange']['after']
    owner = extension['strictOwner']['recordId']
    current, groups = derive_sector_owned_settlement_closure(extension, features, runtime['sourcePhases'],
        runtime['contexts'], runtime['peerKeys'], after)
    indices = {row['id']: index for index, row in enumerate(features)}
    protected = {r['id'] for r in extension['protectedRawRecords']}
    expected_raw_owners = {owner, identifier} if after else {owner}
    expected_explicit_owners = {identifier} if after else set()
    if (groups.get(target) != expected_raw_owners or (after and groups.get(identifier))
            or {sid for sid, group in base_groups.items() if group == target} != expected_explicit_owners
            or set(base_groups) & (protected - expected_explicit_owners)):
        raise ValueError('Reviewed sector-owned settlement raw or explicit ownership changed')
    for sid, expected in extension['curationBindings'].items():
        if runtime['curation'].get(sid) != (runtime['pointRule'] if sid == identifier else expected):
            raise ValueError('Reviewed sector-owned settlement final source curation changed')
    for row in extension['protectedRawRecords']:
        sid = row['id']; index = indices[sid]; expected = dict(row['currentMetadata'])
        if after and sid == identifier:
            expected['pickerGroupId'] = target
        if ({k: v for k, v in current[sid].items() if k not in ('offset', 'length')}
                != {k: v for k, v in expected.items() if k not in ('offset', 'length')}):
            raise ValueError('Reviewed sector-owned settlement retained raw fields changed')
        if expected['hasBoundary']:
            if (index >= len(geometries) or hashlib.sha256(packed_geometry_bytes(geometries[index])).hexdigest()
                    != row['packedGeometrySha256']):
                raise ValueError('Reviewed sector-owned settlement retained packed polygon changed')
        elif (index < len(geometries) or current[sid] != expected or row['packedGeometrySha256'] is not None
                or 'offset' in current[sid] or 'length' in current[sid]):
            raise ValueError('Reviewed sector-owned settlement retained point acquired a polygon')
    absent = set(runtime['contexts']) | {r['id'] for r in extension['sourceOnlyAbsorbedPoints']}
    for sid in absent:
        if sid in indices or groups.get(sid) or sid in base_groups or sid in base_groups.values():
            raise ValueError('Reviewed sector-owned settlement absent source/context acquired a raw row or owner')
    for peer in extension['readOnlyRawNamePeers']:
        sid = peer['id']; geometry = geometries[indices[sid]]
        if (groups.get(sid) != set(peer['expectedRawGroup'])
                or any(geometry.covers(Point(row['lng'], row['lat']))
                       for row in (proposal['exactPair']['rawCurrent'], runtime['baseCurrent']))):
            raise ValueError('Reviewed sector-owned settlement foreign namesake joined or overlaps the candidate')
    for record in extension['sourceOnlyAbsorbedPoints']:
        sid = record['id']; source = runtime['sourcePhases']['effective'][sid]; point = source['point']
        labels = names(source['tags']); identity = {'name': labels[0], 'aliases': labels[1:]}
        matching = sorted(features[index]['id'] for index, geometry in enumerate(geometries)
                          if geometry.covers(point) and same_picker_name(identity, features[index]))
        if matching != record['matchingPackedRawReceiverIds']:
            raise ValueError('Reviewed sector-owned settlement absorbed source gained a hidden/lost receiver')


def sector_owned_reservation_ids(value):
    """Read concrete catalog IDs from descriptors; never interpret file text as code."""
    if isinstance(value, str):
        return {value} if re.fullmatch(r'(?:osm:(?:node|way|relation):[0-9]+|delegation:[0-9]+)', value) else set()
    if isinstance(value, dict):
        result = set()
        for key, member in value.items():
            result.update(sector_owned_reservation_ids(key))
            result.update(sector_owned_reservation_ids(member))
        return result
    if isinstance(value, (list, tuple, set)):
        return set().union(*(sector_owned_reservation_ids(member) for member in value)) if value else set()
    return set()


def reserve_sector_owned_settlement_proposals(reviews, manifest_path, strict_report, city_report,
                                               residential_report, deferred_hamlets, features,
                                               geometries, governors, base_groups, curation_applications,
                                               original_sources=None, effective_sources=None,
                                               reviewed_boundaries=None, official_report=None):
    """Cross-check new writes against ALL existing mutation/protection contracts.

    Shared read-only dependencies are legal. A mutation/ownership claim against
    another protected raw or absent source is not. The exact approved owner
    record is the sole intentional strict-record overlap for its new point.
    """
    pending = [(sid, p) for sid, p in reviews.items() if 'sectorOwnedSettlementPreservation' in p]
    if not pending:
        return
    claims = []
    for sid, proposal in reviews.items():
        pair = proposal['exactPair']; target = pair['basePickerId']
        if 'sectorOwnedSettlementPreservation' in proposal:
            ext = proposal['sectorOwnedSettlementPreservation']['proof']
            protected = {r['id'] for r in ext['sourceStates']} | {target}
        else:
            # Returned descriptors omit original sourceStates. Select their
            # actual raw/absent/context descriptors rather than inventing them.
            protected = sector_owned_reservation_ids({k: v for k, v in proposal.items()
                if k in ('exactPair', 'preservedSectors', 'preservedNamePeers', 'preservedNamePoints',
                         'readOnlyNameContexts', 'deferredCompleteGroupExtension', 'townPreservationExtension')})
        claims.append({'label': ('point', sid), 'read': protected, 'write': {sid, target}})
    manifest = json.loads(Path(manifest_path).read_bytes())
    for section in ('records', 'localRecords', 'cityDisplayAssociations', 'residentialDisplayAssociations'):
        for record in manifest.get(section, []):
            target = record.get('targetPickerGroupId')
            if target is None and record.get('targetDelegationId') is not None:
                target = f"delegation:{record['targetDelegationId']}"
            writes = {record['id']}
            if target is not None:
                writes.add(target)
            if section in ('records', 'localRecords'):
                writes.update(record.get('expectedExistingMemberIds', []))
                writes.update(member['id'] for member in record.get('members', []))
            claims.append({'label': (section, record['id']), 'read': sector_owned_reservation_ids(record),
                           'write': writes, 'record': record})
    for report_label, report in (('city-report', city_report), ('residential-report', residential_report),
                                 ('split-report', strict_report.get('splitSettlementReferences'))):
        if report is None:
            continue
        for application in report['applications']:
            protected = sector_owned_reservation_ids({key: application[key] for key in (
                'id', 'pickerGroupId', 'protectedMetadata', 'finalGroups', 'preservedAbsorbedPointId',
                'preservedNonCatalogPlace', 'preservedAbsentSourceIds', 'exclusiveSourceIds',
                'completeSectorGroupPreservation') if key in application})
            writes = {application['id'], application['pickerGroupId']}
            claims.append({'label': (report_label, application['id']), 'read': protected, 'write': writes})
    for record in deferred_hamlets:
        writes = {record['id'], record['targetPickerGroupId'], *record['expectedExistingMemberIds']}
        writes.update(member['id'] for member in record['members'])
        claims.append({'label': ('deferred-hamlet', record['id']),
                       'read': sector_owned_reservation_ids(record), 'write': writes})
    for sid, proposal in pending:
        runtime = proposal['sectorOwnedSettlementPreservation']; ext = runtime['proof']
        target = proposal['exactPair']['basePickerId']; mine = next(c for c in claims if c['label'] == ('point', sid))
        reviewed_complete_group_owner(
            manifest_path, ext['strictOwner'], runtime['sourceSha256'], runtime['baseCurrent']['id'],
            original_sources=original_sources, effective_sources=effective_sources,
            reviewed_boundaries=reviewed_boundaries, official_report=official_report)
        owner = ext['strictOwner']['recordId']; receipt = ext['strictOwner']['expectedAppliedReceipt']
        if ([r for r in strict_report['applications'] if r.get('pickerGroupId') == target or owner in r.get('ids', [])] != [receipt]
                or any(r.get('id') == sid and r.get('action') == 'picker_base_display' for r in curation_applications)):
            raise ValueError('Reviewed sector-owned settlement strict receipt or deferred phase changed')
        matches = [(g, d) for g in governors for d in g['delegations'] if d['id'] == runtime['baseCurrent']['id']]
        if (len(matches) != 1 or matches[0][1] != runtime['baseCurrent']
                or {k: v for k, v in matches[0][0].items() if k != 'delegations'} != runtime['expectedGovernor']):
            raise ValueError('Reviewed sector-owned settlement retained timetable reference changed')
        for other in claims:
            if other is mine:
                continue
            if other['label'] == ('records', owner):
                if (other['record'] != ext['strictOwner']['expectedRecord']
                        or other['write'] != {owner, target} or mine['write'] & other['read'] != {target}
                        or other['write'] & mine['read'] != {owner, target}):
                    raise ValueError('Reviewed sector-owned settlement owner reservation is not the one approved overlap')
                continue
            if (mine['write'] & (other['read'] | other['write'])
                    or other['write'] & (mine['read'] | mine['write'])):
                raise ValueError('Reviewed sector-owned settlement conflicts with an existing or pending protected identity')
        verify_sector_owned_settlement_state(proposal, features, geometries, base_groups, False)
    # Validate old pending point states too before either old or new pending
    # dispatcher commits. Their existing apply still performs its own checks.
    for proposal in reviews.values():
        if 'deferredCompleteGroupExtension' in proposal:
            verify_reviewed_complete_group_state(proposal, features, geometries, base_groups, False)
    for sid, proposal in pending:
        proposal['sectorOwnedSettlementPreservation']['sharedReservationValidated'] = True


def apply_sector_owned_settlement_proposals(reviews, features, geometries, base_groups, curation_applications):
    """After shared reservations, verify every before-state then append points."""
    pending = [(sid, p) for sid, p in reviews.items() if 'sectorOwnedSettlementPreservation' in p]
    if not pending:
        return None
    for sid, proposal in pending:
        if proposal['sectorOwnedSettlementPreservation'].get('sharedReservationValidated') is not True:
            raise ValueError('Reviewed sector-owned settlement lacks shared pending-contract reservations')
        verify_sector_owned_settlement_state(proposal, features, geometries, base_groups, False)
    indices = {row['id']: index for index, row in enumerate(features)}
    for sid, proposal in pending:
        target = proposal['exactPair']['basePickerId']
        features[indices[sid]]['pickerGroupId'] = target
        base_groups[sid] = target
        curation_applications.append({'id': sid, 'decisionId': sid, 'action': 'picker_base_display',
                                     'pickerGroupId': target, 'inherited': False})
    groups = defaultdict(list)
    for feature in features:
        groups[feature['pickerGroupId']].append(feature['id'])
    return [{'pickerGroupId': group, 'ids': sorted(ids)} for group, ids in sorted(groups.items())
            if len(ids) > 1 or group.startswith('delegation:')]


def reviewed_complete_group_owner(manifest_path, owner, source_sha256, target,
                                  original_sources=None, effective_sources=None,
                                  reviewed_boundaries=None, official_report=None):
    """Bind the unchanged strict owner, including its active approved proof."""
    manifest_path = Path(manifest_path)
    manifest_raw = manifest_path.read_bytes()
    manifest = json.loads(manifest_raw)
    if (not isinstance(owner, dict) or set(owner) != {'recordId', 'expectedRecord', 'reviewEvidence',
            'proofRecordObjectSha256', 'expectedAppliedReceipt'}
            or manifest.get('schemaVersion') != 1 or manifest.get('sourceSha256') != source_sha256
            or not isinstance(manifest.get('records'), list)):
        raise ValueError('Malformed reviewed complete-group strict owner')
    identifier = owner['recordId']
    record = owner['expectedRecord']
    matching = [r for r in manifest['records'] if r.get('id') == identifier or r.get('targetDelegationId') == target]
    if (matching != [record] or record['id'] != identifier
            or record['targetDelegationId'] != target
            or record['expectedExistingMemberIds'] != [identifier]
            or record['expectedTargetMemberIds'] != [f'delegation:{target}']
            or len(record['members']) != 1 or record['members'][0]['id'] != identifier
            or record['members'][0]['expectedKind'] != 'sector'
            or owner['expectedAppliedReceipt'] != {'pickerGroupId': f'delegation:{target}', 'ids': [identifier]}):
        raise ValueError('Reviewed complete-group strict owner record changed')
    active_evidence_refs = [manifest.get('reviewEvidence'), *manifest.get('additionalReviewEvidence', [])]
    if owner['reviewEvidence'] in active_evidence_refs:
        evidence = json.loads(aggregate_review_file(manifest_path.parent, owner['reviewEvidence'], 'complete-group owner proof'))
        proofs = [r for r in evidence['records'] if r.get('id') == identifier and r.get('status') == 'eligible_proposal']
        if (len(proofs) != 1 or hashlib.sha256(json.dumps(proofs[0], ensure_ascii=False, sort_keys=True,
                separators=(',', ':')).encode('utf-8')).hexdigest() != owner['proofRecordObjectSha256']):
            raise ValueError('Reviewed complete-group approved owner proof changed')
        return
    if (original_sources is None or effective_sources is None
            or reviewed_boundaries is None or official_report is None):
        raise ValueError('Reviewed complete-group strict owner record changed')
    directory = manifest_path.parent
    if not isinstance(manifest.get('lineageCompatibility'), dict):
        raise ValueError('Reviewed complete-group strict owner record changed')
    lineage_doc = json.loads(aggregate_review_file(directory, manifest['lineageCompatibility'], 'picker lineage compatibility'))
    if not isinstance(lineage_doc, dict) or lineage_doc.get('method') != 'reviewed_picker_strict_boundary_successor':
        raise ValueError('Reviewed complete-group strict owner record changed')
    load_reviewed_picker_manifest_for_lineage(
        directory,
        {'file': manifest_path.name, 'sha256': hashlib.sha256(manifest_raw).hexdigest()},
        manifest_path, manifest, source_sha256,
        reviewed_boundaries=reviewed_boundaries, official_report=official_report,
        original_sources=original_sources, effective_sources=effective_sources)
    historical_ref = None
    seen = {(str(manifest_path.resolve()), hashlib.sha256(manifest_raw).hexdigest())}
    current_doc = manifest
    for depth in range(8):
        current_lineage_ref = current_doc.get('lineageCompatibility')
        if not isinstance(current_lineage_ref, dict):
            break
        current_lineage = json.loads(aggregate_review_file(directory, current_lineage_ref, 'picker lineage compatibility'))
        if (not isinstance(current_lineage, dict)
                or current_lineage.get('method') != 'reviewed_picker_strict_boundary_successor'):
            break
        previous_proof_ref = current_lineage.get('previousProofCollection')
        if (isinstance(previous_proof_ref, dict) and previous_proof_ref == owner['reviewEvidence']):
            if historical_ref is not None:
                raise ValueError('Reviewed complete-group historical owner proof changed')
            historical_ref = previous_proof_ref
        previous_manifest_ref = current_lineage.get('previousManifest')
        if not isinstance(previous_manifest_ref, dict):
            raise ValueError('Reviewed complete-group historical owner proof changed')
        previous_raw = aggregate_review_file(directory, previous_manifest_ref, 'lineageCompatibility.previousManifest')
        previous_sha = hashlib.sha256(previous_raw).hexdigest()
        if previous_sha != previous_manifest_ref.get('sha256'):
            raise ValueError('Reviewed complete-group historical owner proof changed')
        previous_path = (directory / previous_manifest_ref['file']).resolve()
        seen_key = (str(previous_path), previous_sha)
        if seen_key in seen:
            raise ValueError('Reviewed complete-group historical owner proof changed')
        seen.add(seen_key)
        previous_doc = json.loads(previous_raw)
        if not isinstance(previous_doc, dict):
            raise ValueError('Reviewed complete-group historical owner proof changed')
        current_doc = previous_doc
    if historical_ref is None:
        raise ValueError('Reviewed complete-group strict owner record changed')
    historical_raw = aggregate_review_file(directory, historical_ref, 'complete-group historical owner proof')
    historical_doc = json.loads(historical_raw)
    if not isinstance(historical_doc, dict) or not isinstance(historical_doc.get('records'), list):
        raise ValueError('Reviewed complete-group approved owner proof changed')
    historical_proofs = [r for r in historical_doc['records']
                         if isinstance(r, dict) and r.get('id') == identifier and r.get('status') == 'eligible_proposal']
    if len(historical_proofs) != 1:
        raise ValueError('Reviewed complete-group approved owner proof changed')
    historical_proof = historical_proofs[0]
    historical_hash = hashlib.sha256(json.dumps(historical_proof, ensure_ascii=False, sort_keys=True,
                                              separators=(',', ':')).encode('utf-8')).hexdigest()
    if historical_hash != owner['proofRecordObjectSha256']:
        raise ValueError('Reviewed complete-group approved owner proof changed')
    active_matches = []
    for active_ref in active_evidence_refs:
        active_raw = aggregate_review_file(directory, active_ref, 'picker review evidence')
        active_doc = json.loads(active_raw)
        active_rows = active_doc.get('records') if isinstance(active_doc, dict) else None
        if not isinstance(active_rows, list):
            raise ValueError('Reviewed complete-group approved owner proof changed')
        for row in active_rows:
            if (isinstance(row, dict) and row.get('id') == identifier
                    and row.get('status') == 'eligible_proposal'):
                active_matches.append((active_doc, row))
    if len(active_matches) != 1:
        raise ValueError('Reviewed complete-group approved owner proof changed')
    active_doc, active_proof = active_matches[0]
    active_hash = hashlib.sha256(json.dumps(active_proof, ensure_ascii=False, sort_keys=True,
                                           separators=(',', ':')).encode('utf-8')).hexdigest()
    if active_hash != owner['proofRecordObjectSha256']:
        raise ValueError('Reviewed complete-group approved owner proof changed')
    if (active_proof != historical_proof
            or json.dumps(active_proof, ensure_ascii=False, sort_keys=True, separators=(',', ':'))
               != json.dumps(historical_proof, ensure_ascii=False, sort_keys=True, separators=(',', ':'))):
        raise ValueError('Reviewed complete-group approved owner proof changed')
    if (historical_doc.get('schemaVersion') != active_doc.get('schemaVersion')
            or historical_doc.get('sourceSha256') != active_doc.get('sourceSha256')):
        raise ValueError('Reviewed complete-group approved owner proof context changed')
    if {k: v for k, v in historical_doc.items() if k != 'records'} != {
            k: v for k, v in active_doc.items() if k != 'records'}:
        raise ValueError('Reviewed complete-group approved owner proof context changed')


def validate_deferred_complete_group_extension(review, manifest_path, original_sources, effective_sources,
                                               curation, rule, source_sha256,
                                               reviewed_boundaries=None, official_report=None):
    """Validate source identity and explicit group maps without assigning a group."""
    extension = review['deferredCompleteGroupExtension']
    keys = {'schemaVersion', 'strictOwner', 'expectedBeforeGroups', 'expectedAfterGroups', 'protectedRawRecords'}
    if (not isinstance(extension, dict) or set(extension) != keys
            or type(extension['schemaVersion']) is not int or extension['schemaVersion'] != 1
            or review['method'] not in ('reviewed_explicit_suburb_point_base_display_identity',
                                        'reviewed_explicit_point_base_display_identity')
            or 'sourcePhaseLineage' in review or review['preservedNamePeers'] != []
            or review.get('typedNamePeerExtension', {}).get('rawPointPeers', []) != []):
        raise ValueError('Malformed or unsupported deferred complete-group extension')
    identifier, target = review['pointId'], f"delegation:{review['baseId']}"
    reviewed_complete_group_owner(
        manifest_path, extension['strictOwner'], source_sha256, review['baseId'],
        original_sources=original_sources, effective_sources=effective_sources,
        reviewed_boundaries=reviewed_boundaries, official_report=official_report)
    owner = extension['strictOwner']['recordId']
    before, after = extension['expectedBeforeGroups'], extension['expectedAfterGroups']
    rows = extension['protectedRawRecords']
    if (not isinstance(before, dict) or not isinstance(after, dict) or not isinstance(rows, list)
            or not rows or any(not isinstance(r, dict) or set(r) != {'id', 'kind', 'currentMetadata',
                'originalTags', 'sourceGeometrySha256', 'expectedCuration', 'packedGeometrySha256'} for r in rows)
            or len({r['id'] for r in rows}) != len(rows)
            or before.get(identifier) != [identifier] or before.get(target) != sorted([target, owner])):
        raise ValueError('Malformed reviewed complete-group inventory')
    expected_after = {key: list(value) for key, value in before.items() if key != identifier}
    expected_after[target] = sorted([target, owner, identifier])
    if after != expected_after or review['completePickerGroups'] != {'before': before, 'after': after}:
        raise ValueError('Reviewed complete-group extension changes more than the point membership')
    raw = {r['id']: r for r in rows}
    flat = [member for member_ids in before.values() for member in member_ids]
    if (any(not isinstance(key, str) or not isinstance(value, list) or not value
            or value != sorted(set(value)) for key, value in before.items())
            or len(flat) != len(set(flat)) or set(flat) != set(raw) | {target}
            or {i for i in flat if i.startswith('delegation:')} != {target}
            or owner not in {r['id'] for r in review['preservedSectors']}
            or {r['id'] for r in rows if r['kind'] == 'sector'} != {r['id'] for r in review['preservedSectors']}
            or {r['id'] for r in rows if r['kind'] not in ('sector', 'residential')} != {identifier}
            or raw[identifier]['currentMetadata'] != review['rawCurrent']
            or raw[identifier]['expectedCuration'] is not None):
        raise ValueError('Reviewed complete-group raw member coverage changed')
    for row in rows:
        rid, current = row['id'], row['currentMetadata']
        if (current['id'] != rid or current['kind'] != row['kind'] or current['sourceId'] != 'osm'
                or current['governorateId'] != review['expectedGovernor']['id']
                or current['pickerGroupId'] not in before or rid not in before[current['pickerGroupId']]
                or (curation.get(rid) != rule if rid == identifier else curation.get(rid) != row['expectedCuration'])):
            raise ValueError('Reviewed complete-group metadata or curation binding changed')
        if rid == identifier:
            if (row['kind'] != ('suburb' if review['method'] ==
                    'reviewed_explicit_suburb_point_base_display_identity' else 'village')
                    or current['hasBoundary'] is not False
                    or 'offset' in current or 'length' in current or row['packedGeometrySha256'] is not None):
                raise ValueError('Reviewed complete-group point representation changed')
        elif (current['hasBoundary'] is not True or not isinstance(row['packedGeometrySha256'], str)
                or len(row['packedGeometrySha256']) != 64):
            raise ValueError('Reviewed complete-group polygon representation changed')
        for sources, provider in ((original_sources, None), (effective_sources, 'osm')):
            source = sources[rid]
            geometry = source.get('shape')
            if geometry is None:
                geometry = source.get('point')
            if (source['kind'] != row['kind'] or source.get('sourceId') != provider
                    or source['tags'] != row['originalTags'] or geometry is None or geometry.is_empty
                    or aggregate_geometry_sha256(geometry) != row['sourceGeometrySha256']
                    or (geometry.geom_type != 'Point' if rid == identifier
                        else geometry.geom_type not in ('Polygon', 'MultiPolygon'))):
                raise ValueError('Reviewed complete-group original/effective source member changed')
    for sector in review['preservedSectors']:
        row = raw[sector['id']]
        if (row['currentMetadata'] != sector['currentMetadata']
                or row['originalTags'] != sector['originalTags']
                or row['sourceGeometrySha256'] != sector['sourceGeometrySha256']
                or row['packedGeometrySha256'] != sector['packedGeometrySha256']
                or row['expectedCuration'] != sector['expectedCuration']):
            raise ValueError('Reviewed complete-group preserved imada bindings disagree')
    return {**extension, 'sourceSha256': source_sha256, 'baseCurrent': review['baseCurrent'],
            'expectedGovernor': review['expectedGovernor']}


def verify_reviewed_complete_group_state(proposal, features, geometries, base_groups, after):
    """Read-only exact group, retained metadata and packed-geometry postconditions."""
    extension = proposal['deferredCompleteGroupExtension']
    pair = proposal['exactPair']; identifier = pair['rawCurrent']['id']; target = pair['basePickerId']
    indices = {f['id']: index for index, f in enumerate(features)}
    if len(indices) != len(features):
        raise ValueError('Repeated raw ID in reviewed complete-group state')
    groups = defaultdict(set)
    for feature in features:
        groups[feature['pickerGroupId']].add(feature['id'])
    expected_groups = extension['expectedAfterGroups' if after else 'expectedBeforeGroups']
    for group, expected in expected_groups.items():
        actual = groups.get(group, set()) | ({group} if group.startswith('delegation:') else set())
        if sorted(actual) != expected:
            raise ValueError('Reviewed complete-group membership changed')
    if (after and groups.get(identifier)) or ({i for i, group in base_groups.items() if group == target}
            != ({identifier} if after else set())):
        raise ValueError('Reviewed complete-group explicit point ownership changed')
    protected_ids = {row['id'] for row in extension['protectedRawRecords']}
    if set(base_groups) & (protected_ids - ({identifier} if after else set())):
        raise ValueError('Reviewed complete-group member acquired a competing explicit owner')
    for row in extension['protectedRawRecords']:
        index = indices.get(row['id'])
        expected = dict(row['currentMetadata'])
        if after and row['id'] == identifier:
            expected['pickerGroupId'] = target
        if index is None or ({k: v for k, v in features[index].items() if k not in ('offset', 'length')}
                != {k: v for k, v in expected.items() if k not in ('offset', 'length')}):
            raise ValueError('Reviewed complete-group retained raw metadata changed')
        if row['id'] == identifier:
            if (index < len(geometries) or features[index] != expected
                    or features[index]['hasBoundary'] is not False
                    or 'offset' in features[index] or 'length' in features[index]):
                raise ValueError('Reviewed complete-group point gained a boundary')
        elif (index >= len(geometries) or hashlib.sha256(packed_geometry_bytes(geometries[index])).hexdigest()
                != row['packedGeometrySha256']):
            raise ValueError('Reviewed complete-group retained packed polygon changed')
    for row in proposal.get('readOnlyNameContexts', []):
        if (row['id'] in indices or groups.get(row['id'])
                or row['id'] in base_groups or row['id'] in base_groups.values()):
            raise ValueError('Reviewed complete-group source-only context gained a row or owner')


def apply_deferred_point_base_group_extensions(reviews, manifest_path, strict_report, features, geometries,
                                              governors, base_groups, curation_applications,
                                              original_sources=None, effective_sources=None,
                                              reviewed_boundaries=None, official_report=None):
    """Check every pending extension after existing mutations, then commit only its point."""
    pending = [(identifier, proposal) for identifier, proposal in reviews.items()
               if 'deferredCompleteGroupExtension' in proposal]
    if not pending:
        return None
    used_ids, used_targets = set(), set()
    for identifier, proposal in pending:
        extension = proposal['deferredCompleteGroupExtension']; target = proposal['exactPair']['basePickerId']
        protected = {row['id'] for row in extension['protectedRawRecords']}
        if (used_ids & protected or target in used_targets
                or identifier != proposal['exactPair']['rawCurrent']['id']):
            raise ValueError('Overlapping reviewed complete-group extensions')
        used_ids.update(protected); used_targets.add(target)
        base_id = extension['baseCurrent']['id']
        matches = [(g, d) for g in governors for d in g['delegations'] if d['id'] == base_id]
        if (len(matches) != 1 or matches[0][1] != extension['baseCurrent']
                or {k: v for k, v in matches[0][0].items() if k != 'delegations'} != extension['expectedGovernor']):
            raise ValueError('Reviewed complete-group base reference changed')
        reviewed_complete_group_owner(
            manifest_path, extension['strictOwner'], extension['sourceSha256'], base_id,
            original_sources=original_sources, effective_sources=effective_sources,
            reviewed_boundaries=reviewed_boundaries, official_report=official_report)
        receipt = extension['strictOwner']['expectedAppliedReceipt']
        owner = extension['strictOwner']['recordId']
        if ([r for r in strict_report['applications'] if r.get('pickerGroupId') == target or owner in r.get('ids', [])]
                != [receipt] or any(r.get('id') == identifier and r.get('action') == 'picker_base_display'
                    for r in curation_applications)):
            raise ValueError('Reviewed complete-group strict receipt or deferred phase changed')
        verify_reviewed_complete_group_state(proposal, features, geometries, base_groups, False)
    indices = {feature['id']: index for index, feature in enumerate(features)}
    for identifier, proposal in pending:
        target = proposal['exactPair']['basePickerId']
        features[indices[identifier]]['pickerGroupId'] = target
        base_groups[identifier] = target
        curation_applications.append({'id': identifier, 'decisionId': identifier,
                                     'action': 'picker_base_display', 'pickerGroupId': target, 'inherited': False})
    combined = defaultdict(list)
    for feature in features:
        combined[feature['pickerGroupId']].append(feature['id'])
    return [{'pickerGroupId': identifier, 'ids': sorted(ids)} for identifier, ids in sorted(combined.items())
            if len(ids) > 1 or identifier.startswith('delegation:')]



def verify_reviewed_point_base_names(reviews, features, geometries, base_groups):
    """Preserve the retained point, complete base group, imadas and name peers."""
    indices = {f['id']: index for index, f in enumerate(features)}
    groups = defaultdict(set)
    for feature in features:
        groups[feature['pickerGroupId']].add(feature['id'])
    for identifier, proposal in reviews.items():
        if 'sectorOwnedSettlementPreservation' in proposal:
            verify_sector_owned_settlement_state(proposal, features, geometries, base_groups, True)
            continue
        if 'townPreservationExtension' in proposal:
            verify_reviewed_town_preservation(proposal, features, geometries, base_groups)
            continue
        if 'deferredCompleteGroupExtension' in proposal:
            verify_reviewed_complete_group_state(proposal, features, geometries, base_groups, True)
            continue
        pair = proposal['exactPair']
        target = pair['basePickerId']
        expected = {**pair['rawCurrent'], 'pickerGroupId': target}
        if (identifier not in indices or features[indices[identifier]] != expected
                or groups.get(target) != {identifier} or groups.get(identifier)
                or {i for i, group in base_groups.items() if group == target} != {identifier}):
            raise ValueError('Reviewed point/base complete display group or retained fields changed')
        for row in proposal['preservedSectors'] + proposal.get('preservedNamePeers', []):
            if row['id'] not in indices:
                raise ValueError('Reviewed separate imada or name peer disappeared')
            index = indices[row['id']]
            if ({k: v for k, v in features[index].items() if k not in ('offset', 'length')}
                    != {k: v for k, v in row['current'].items() if k not in ('offset', 'length')}
                    or groups.get(row['id']) != {row['id']} or index >= len(geometries)
                    or hashlib.sha256(packed_geometry_bytes(geometries[index])).hexdigest() != row['packedGeometrySha256']):
                raise ValueError('Reviewed separate imada or name peer fields, complete group or geometry changed')
        for row in proposal.get('preservedNamePoints', []):
            index = indices.get(row['id'])
            if (index is None or index < len(geometries)
                    or features[index] != row['current']
                    or features[index]['hasBoundary'] is not False
                    or 'offset' in features[index] or 'length' in features[index]
                    or features[index]['pickerGroupId'] != row['id']
                    or groups.get(row['id']) != {row['id']}):
                raise ValueError('Reviewed separate point name peer fields or complete group changed')
        for row in proposal.get('readOnlyNameContexts', []):
            if (row['id'] in indices or groups.get(row['id'])
                    or row['id'] in base_groups or row['id'] in base_groups.values()):
                raise ValueError('Reviewed source-only name context gained a raw row or display group')



def validate_reviewed_legacy_point_base_name(rule, manifest_path, coordinate_path, obj, original_sources,
                                             effective_sources, current, base, governor, source_sha256, curation):
    """Bind an explicitly reviewed pair to the preserved older batch evidence.

    Older coordinate records remain unchanged. Their complete node tags and
    changesets come from their byte-pinned original API batch, not optional
    fallbacks in the separate single-node/Kalaa validation format.
    """
    try:
        directory = Path(manifest_path).parent
        review = json.loads(aggregate_review_file(directory, rule['pickerBaseLegacyNameReview'], 'legacy point/base name review'))
        proposal = json.loads(aggregate_review_file(directory, review['sourceProposal'], 'legacy pair identity proposal'))
        identifier, target = obj['id'], rule['pickerBaseDelegationId']
        admissions = review['admittedPairs']
        pairs = proposal['records']
        if (review.get('schemaVersion') != 1 or review.get('method') != 'reviewed_legacy_batch_point_base_identity'
                or review.get('sourceSha256') != source_sha256
                or proposal.get('status') != 'TWO_EXACT_POINT_BASE_IDENTITIES_SUPPORTED_IMPLEMENTATION_NOT_STAGED'
                or not isinstance(admissions, list) or not admissions
                or len({r['pointId'] for r in admissions}) != len(admissions)
                or len({r['baseId'] for r in admissions}) != len(admissions)
                or {(r['pointId'], r['baseId']) for r in admissions} != {(r['pointId'], r['baseId']) for r in pairs}
                or len(pairs) != len(admissions)):
            raise ValueError('Legacy point/base review admission changed')
        admission, = [r for r in admissions if r['pointId'] == identifier and r['baseId'] == target]
        pair, = [r for r in pairs if r['pointId'] == identifier and r['baseId'] == target]
        expected = pair['currentPoint']
        if (pair['decision'] != 'SUPPORTED_EXACT_POINT_BASE_DISPLAY_ASSOCIATION'
                or rule['action'] != 'preserve_point' or rule['expectedTags'] != obj['tags']
                or admission['pointNameAr'] != current['name'] or current['name'] != expected['name']
                or admission['baseNameAr'] != base['nomAr'] or base != pair['currentBase']
                or norm(current['name']) == norm(base['nomAr'])
                or {k: v for k, v in governor.items() if k != 'delegations'} != pair['governorate']
                or current != {k: v for k, v in expected.items() if k != 'pickerGroupId'}
                or expected['id'] != identifier or expected['pickerGroupId'] != identifier
                or expected['kind'] != 'village' or expected['hasBoundary'] is not False
                or pair['expectedBeforeGroups'] != [{'id': identifier, 'memberIds': [identifier]},
                    {'id': f'delegation:{target}', 'memberIds': [f'delegation:{target}']}]
                or pair['expectedAfterGroups'] != [{'id': f'delegation:{target}',
                    'memberIds': [f'delegation:{target}', identifier]}]):
            raise ValueError('Legacy point/base exact names or preserved identity changed')
        corrections = json.loads(display_identity_coordinate_bytes(Path(coordinate_path).parent))['corrections']
        correction = pair['existingCorrection']
        if ([r for r in corrections if r['delegationId'] == target] != [correction]
                or hashlib.sha256(json.dumps(correction, ensure_ascii=False, sort_keys=True,
                    separators=(',', ':')).encode('utf-8')).hexdigest() != admission['correctionSha256']
                or admission['correctionSha256'] != pair['existingCorrectionCanonicalSha256']
                or correction['osm']['id'] != identifier
                or correction['proposed'] != {'lat': current['lat'], 'lng': current['lng']}):
            raise ValueError('Legacy point/base exact settlement-reference correction changed')
        batch = json.loads(aggregate_review_file(directory, review['sourceBatch'], 'original named-node batch'))['elements']
        original_proposal = json.loads(aggregate_review_file(directory, review['originalReferenceProposal'], 'original coordinate proposal'))
        expected_ids = review['expectedBatchNodeIds']
        prefix = 'https://api.openstreetmap.org/api/0.6/nodes.json?nodes='
        url = review['sourceBatchUrl']
        if (len(expected_ids) != 15 or len(set(expected_ids)) != 15
                or any(type(i) is not int for i in expected_ids)
                or len(batch) != 15 or any(n['type'] != 'node' for n in batch)
                or sorted(n['id'] for n in batch) != expected_ids
                or not url.startswith(prefix) or sorted(map(int, url[len(prefix):].split(','))) != expected_ids
                or original_proposal['liveOsmEvidenceUrl'] != url
                or correction['osmEvidence']['liveNodeResponseUrl'] != url
                or original_proposal['liveOsmEvidenceSha256'] != review['sourceBatch']['sha256']
                or correction['osmEvidence']['liveNodeResponseSha256'] != review['sourceBatch']['sha256']):
            raise ValueError('Legacy source batch URL, exact inventory or provenance changed')
        node, = [n for n in batch if f"osm:node:{n['id']}" == identifier]
        original_reference, = [r for r in original_proposal['candidates'] if r['delegationId'] == target]
        if (node != pair['exactLiveBatchNode'] or node['tags'] != obj['tags']
                or node['tags'] != pair['originalCachedNode']['tags']
                or node['lat'] != current['lat'] or node['lon'] != current['lng']
                or node['version'] != correction['osm']['version'] or node['timestamp'] != correction['osm']['timestamp']
                or node['tags']['name:ar'] != correction['osm']['name']
                or original_reference['osm'] != correction['osm']
                or original_reference['proposed'] != correction['proposed']
                or original_reference['original'] != correction['original']):
            raise ValueError('Legacy source point version, complete tags or coordinate changed')
        phone_reference = admission['historicalSourceReview']
        phone = json.loads(aggregate_review_file(directory, phone_reference, 'historical settlement review'))
        observation, = [r for r in phone['reviews'] if r['delegationId'] == target]
        if (phone_reference['sha256'] != correction['phoneVisualReview']['reportSha256']
                or phone['proposalSha256'] != review['originalReferenceProposal']['sha256']
                or observation['decision'] not in ('approve_as_settlement_reference', 'supported_as_named_settlement_reference_anchor')
                or observation['proposed'] != correction['proposed'] or observation['original'] != correction['original']):
            raise ValueError('Legacy reviewed settlement evidence changed')
        registry = json.loads(aggregate_review_file(directory, review['officialRegistry'], 'official locality identities'))
        sectors = pair['preservedSectorInventory']
        if ([r for r in registry['sectors'] if r['delegationCode'] == pair['officialParentCode']]
                != [r['officialIdentity'] for r in sectors] or not sectors
                or len({r['id'] for r in sectors}) != len(sectors)):
            raise ValueError('Legacy complete administrative context inventory changed')
        peers = {r['id']: r for r in pair['completeMatchingSourceNameInventory']}
        peer_keys = {norm(v) for v in [current['name'], base['nomAr'], base['nomFr'], *current['aliases']]}
        for sources, expected_provider in ((original_sources, None), (effective_sources, 'osm')):
            point = sources[identifier]
            context = sources[pair['sourceGovernoratePolygon']['id']]
            if (point['kind'] != current['kind'] or point.get('sourceId') != expected_provider
                    or point['tags'] != obj['tags'] or point.get('shape') is not None
                    or point['point'].geom_type != 'Point'
                    or point['point'].coords[:] != [(current['lng'], current['lat'])]
                    or context['kind'] != 'governorate' or context['tags'].get('name:ar') != 'ولاية ' + governor['nomAr']
                    or aggregate_geometry_sha256(context['shape']) != pair['sourceGovernoratePolygon']['sourceGeometrySha256']
                    or not context['shape'].contains(point['point'])
                    or {i for i, s in sources.items() if peer_keys & {norm(v) for v in names(s['tags'])}} != set(peers)):
                raise ValueError('Legacy original/effective point, governorate or name peers changed')
            for peer_id, peer in peers.items():
                if sources[peer_id]['tags'] != peer['tags']:
                    raise ValueError('Legacy matching source identity changed')
            for row in sectors:
                sector = sources[row['id']]
                official, transfer = row['officialIdentity'], row['existingCurationForSourceToCurrentCode']
                if (sector['kind'] != 'sector' or sector['tags'] != row['originalTags']
                        or aggregate_geometry_sha256(sector['shape']) != row['originalSourceGeometrySha256']):
                    raise ValueError('Legacy protected imada source identity or geometry changed')
                source_code = sector['tags']['ref:tn:codegeo']
                if transfer is None:
                    if source_code != official['sectorCode'] or row['id'] in curation:
                        raise ValueError('Legacy same-code imada acquired an unreviewed curation')
                elif (curation.get(row['id']) != transfer or transfer['action'] != 'administrative_context'
                        or transfer['identityReview']['oldOfficialSectorCode'] != source_code
                        or transfer['reviewedOfficialSector']['sectorCode'] != official['sectorCode']
                        or transfer['reviewedOfficialSector']['sectorAr'] != official['sectorAr']
                        or transfer['reviewedOfficialSector']['delegationCode'] != official['delegationCode']):
                    raise ValueError('Legacy exact reviewed old/current imada code transition changed')
        # Reuse the same final point/base and protected-sector verifier as the
        # existing format. This adapter changes no feature or source metadata.
        return {'exactPair': {'basePickerId': f'delegation:{target}', 'rawCurrent': expected},
                'preservedSectors': [{'id': row['id'], 'current': row['currentMetadata'],
                    'packedGeometrySha256': row['packedGeometrySha256']} for row in sectors]}
    except (OSError, UnicodeError, ValueError, KeyError, TypeError, AttributeError, IndexError) as error:
        raise ValueError('Missing, malformed or changed reviewed legacy point/base name identity') from error



def validate_reviewed_source_phase_lineage(directory, review, original_sources, effective_sources,
                                          peer_keys, source_sha256, reviewed_boundaries, official_report):
    """Bind a complete name-peer inventory across accepted boundary replacements.

    Original names and shapes remain evidence, including peers whose erroneous
    names were removed. Each changed state needs its actual boundary-loader
    application; this proof does not restore aliases or modify source objects.
    """
    lineage = json.loads(aggregate_review_file(directory, review['sourcePhaseLineage'], 'source phase lineage'))
    if (lineage.get('schemaVersion') != 1
            or lineage.get('method') != 'reviewed_official_boundary_source_phase_lineage'
            or lineage.get('sourceSha256') != source_sha256
            or lineage.get('pointId') != review['pointId'] or lineage.get('baseId') != review['baseId']):
        raise ValueError('Reviewed source phase lineage identity changed')
    reference = lineage['reviewedBoundaries']
    boundary_manifest = load_reviewed_boundary_manifest_for_lineage(
        directory, reference, reviewed_boundaries, official_report)
    records = lineage['sourceStates']
    states = {record['id']: record for record in records}
    sector_ids = {row['id'] for row in review['preservedSectors']}
    peer_ids = {phase: lineage[phase + 'NamePeerIds'] for phase in ('original', 'effective')}
    if (not records or len(states) != len(records)
            or any(set(record) != {'id', 'original', 'effective'} for record in records)
            or any(ids != sorted(set(ids)) or review['pointId'] not in ids for ids in peer_ids.values())
            or set(states) != sector_ids | set(peer_ids['original']) | set(peer_ids['effective'])):
        raise ValueError('Incomplete or repeated source phase inventory')
    for phase, sources in (('original', original_sources), ('effective', effective_sources)):
        actual_peers = sorted(identifier for identifier, source in sources.items()
                              if peer_keys & {norm(value) for value in names(source['tags'])})
        if actual_peers != peer_ids[phase]:
            raise ValueError('Reviewed complete original/effective name peers changed')
        for identifier, record in states.items():
            source = sources[identifier]
            geometry = source.get('shape') if source.get('shape') is not None else source.get('point')
            actual = {'tags': source['tags'], 'kind': source['kind'], 'sourceId': source.get('sourceId'),
                      'geometrySha256': aggregate_geometry_sha256(geometry)}
            if actual != record[phase] or (phase == 'original' and actual['sourceId'] is not None):
                raise ValueError('Reviewed source phase tags, kind, provider or geometry changed')
    original_peers = [{'id': identifier, 'tags': states[identifier]['original']['tags'],
                       'kind': states[identifier]['original']['kind']} for identifier in peer_ids['original']]
    if sorted(review['sourceNamePeers'], key=lambda row: row['id']) != original_peers:
        raise ValueError('Original peer lineage differs from the identity review')
    for row in review['preservedSectors']:
        original = states[row['id']]['original']
        if (original['kind'] != 'sector' or original['tags'] != row['originalTags']
                or original['geometrySha256'] != row['sourceGeometrySha256']):
            raise ValueError('Original imada lineage differs from the preserved inventory')
    changed = {identifier for identifier, record in states.items()
               if any(record['original'][key] != record['effective'][key]
                      for key in ('tags', 'kind', 'geometrySha256'))
               or record['effective']['sourceId'] != 'osm'}
    replacements = lineage['acceptedReplacements']
    if (not changed or len(replacements) != len(changed)
            or {record['id'] for record in replacements} != changed):
        raise ValueError('Every source phase change needs exactly one accepted replacement')
    for replacement in replacements:
        identifier = replacement['id']
        original, effective = states[identifier]['original'], states[identifier]['effective']
        source, record = replacement['sourceRecord'], replacement['record']
        source_matches = [s for s in boundary_manifest['sources'] if s['id'] == source['id']]
        record_matches = [r for r in source['records'] if r['id'] == identifier]
        application = {'id': identifier, 'action': 'replace', 'officialCode': record['officialCode'],
                       'sourceId': source['id']}
        if (source_matches != [source] or record_matches != [record]
                or record['id'] != identifier or record['action'] != 'replace'
                or source['sourceSha256'] != source_sha256 or not source.get('review')
                or record['expectedOriginalTags'] != original['tags']
                or original['kind'] != 'sector' or effective['kind'] != 'sector'
                or effective['sourceId'] != source['id'] or source['id'] == 'osm'
                or replacement['application'] != application
                or [r for r in official_report['applications'] if r['id'] == identifier] != [application]
                or official_report['sources'].get(source['id'])
                    != {k: v for k, v in source.items() if k not in ('file', 'records')}
                or replacement['geojson'] != {'file': source['file'], 'sha256': source['sha256']}):
            raise ValueError('Source phase replacement differs from the accepted boundary application')
        collection = json.loads(aggregate_review_file(Path(reviewed_boundaries).parent,
                                replacement['geojson'], 'accepted source phase geometry'))
        features = [feature for feature in collection['features'] if feature['id'] == identifier]
        if len(features) != 1:
            raise ValueError('Accepted source phase feature identity is not unique')
        feature = features[0]
        feature_sha = hashlib.sha256(json.dumps(feature, ensure_ascii=False, sort_keys=True,
                                                separators=(',', ':')).encode('utf-8')).hexdigest()
        properties = feature['properties']
        tags = {'boundary': 'administrative', 'admin_level': '6', 'ref:tn:codegeo': record['officialCode'],
                'name:ar': properties['nameAr'], 'name:fr': properties['nameFr']}
        if record.get('aliases'):
            tags['alt_name'] = ';'.join(record['aliases'])
        if (feature_sha != replacement['featureSha256'] or feature['type'] != 'Feature'
                or properties['sourceId'] != source['id'] or properties['officialCode'] != record['officialCode']
                or tags != effective['tags']
                or aggregate_geometry_sha256(shape(feature['geometry'])) != effective['geometrySha256']):
            raise ValueError('Accepted boundary feature does not produce the reviewed effective state')
    extra = lineage['additionalPreservedSectors']
    if (len(extra) != len(changed - sector_ids) or {row['id'] for row in extra} != changed - sector_ids
            or any(row['completeRawGroup'] != [row['id']]
                   or row['currentMetadata']['id'] != row['id']
                   or row['currentMetadata']['pickerGroupId'] != row['id']
                   or row['currentMetadata']['kind'] != 'sector'
                   or row['currentMetadata']['hasBoundary'] is not True
                   or row['currentMetadata']['sourceId'] != states[row['id']]['effective']['sourceId']
                   for row in extra)):
        raise ValueError('Changed historical peers need exact separate final sector records')
    return {'states': states,
            'peers': {phase: [{'id': identifier, **states[identifier][phase]} for identifier in ids]
                      for phase, ids in peer_ids.items()},
            'additionalPreservedSectors': [{'id': row['id'], 'current': row['currentMetadata'],
                'packedGeometrySha256': row['packedGeometrySha256']} for row in extra]}


def validate_reviewed_administrative_successor_identity(directory, review, registry, original_sources,
                                                        effective_sources, curation, states, raw, bindings,
                                                        source_sha256):
    """Validate unchanged source sectors with reviewed successor membership.

    The actual historical delegation stays a source context. No successor
    polygon or source-code rewrite is inferred from the reviewed membership.
    """
    extension = review['townPreservationExtension']
    value = extension['administrativeSuccession']
    keys = {'schemaVersion', 'method', 'sourceDelegationCode', 'currentDelegationCode',
            'historicalDelegationContextId', 'currentDelegationSourceContextIds', 'reviewedParentNames',
            'sectorSuccessions', 'independentPointIds', 'coordinateReviewedSectorIds', 'evidence',
            'reviewEvidence', 'rawContextClosure', 'sourceOnlyAbsorbedPoints'}
    if (not isinstance(value, dict) or set(value) != keys
            or type(value['schemaVersion']) is not int or value['schemaVersion'] != 1
            or value['method'] != 'reviewed_unchanged_sector_administrative_successor_identity'
            or extension['acceptedBoundaryReplacements'] != [] or extension['reviewedBoundaries'] is not None):
        raise ValueError('Malformed or incompatible reviewed administrative succession')
    old_code, new_code = value['sourceDelegationCode'], value['currentDelegationCode']
    if (any(not isinstance(code, str) or not re.fullmatch(r'[0-9]{4}', code) for code in (old_code, new_code))
            or old_code == new_code or old_code[:2] != new_code[:2]
            or extension['officialDelegationCodes'] != [new_code]):
        raise ValueError('Reviewed administrative succession requires one same-governorate successor')
    identifier = review['pointId']
    sectors = {row['id']: row for row in review['preservedSectors']}
    contexts = review['contexts']
    historical_id = value['historicalDelegationContextId']
    if (len(contexts) != 2 or historical_id not in contexts
            or contexts[historical_id]['kind'] != 'delegation'
            or contexts[historical_id]['tags'].get('ref:tn:codegeo') != old_code
            or value['currentDelegationSourceContextIds'] != {'original': [], 'effective': []}
            or {sid for sid, row in raw.items() if row['currentMetadata']['hasBoundary']} != set(sectors)):
        raise ValueError('Reviewed successor actual source context or preserved polygon set changed')
    for phase, sources in (('original', original_sources), ('effective', effective_sources)):
        if sorted(sid for sid, source in sources.items() if source['kind'] == 'delegation'
                  and source['tags'].get('ref:tn:codegeo') == new_code) != value['currentDelegationSourceContextIds'][phase]:
            raise ValueError('Reviewed successor delegation source is no longer absent')
    for state in states.values():
        if state['effective'] != {**state['original'], 'sourceId': 'osm'}:
            raise ValueError('Reviewed successor membership cannot rewrite source identity or geometry')

    successions = value['sectorSuccessions']
    if (not isinstance(successions, list) or [row['id'] for row in successions] != sorted(sectors)
            or len({row['originalSectorCode'] for row in successions}) != len(sectors)
            or len({row['currentSectorCode'] for row in successions}) != len(sectors)):
        raise ValueError('Reviewed successor sector mapping is not a complete bijection')
    parents = value['reviewedParentNames']
    official = [row for row in registry['sectors'] if row['delegationCode'] == new_code]
    if (not isinstance(parents, list) or len(parents) != 3 or len(set(parents)) != 3
            or any(not isinstance(name, str) or not name for name in parents)
            or parents[0] != 'معتمدية ' + parents[1]
            or not official or any(norm(row['delegationAr']) != norm(parents[1])
                                  or norm(row['delegationFr']) != norm(parents[2]) for row in official)
            or norm(review['baseCurrent']['nomAr']) != norm(parents[1])):
        raise ValueError('Reviewed successor parent names lack the exact current identity')
    for row in successions:
        sid, old_sector, new_sector = row['id'], row['originalSectorCode'], row['currentSectorCode']
        sector = sectors[sid]
        decision = bindings[sid]
        if (set(row) != {'id', 'originalSectorCode', 'currentSectorCode', 'officialIdentity', 'expectedAdministrativeCuration'}
                or not isinstance(old_sector, str) or not re.fullmatch(old_code + r'[0-9]{2}', old_sector)
                or not isinstance(new_sector, str) or not re.fullmatch(new_code + r'[0-9]{2}', new_sector)
                or row['officialIdentity'] != sector['officialIdentity'] or row['officialIdentity'] not in official
                or row['officialIdentity']['sectorCode'] != new_sector
                or row['expectedAdministrativeCuration'] != decision or curation.get(sid) != decision
                or not isinstance(decision, dict)
                or set(decision) != {'id', 'expectedKind', 'expectedTags', 'action', 'parentNames',
                                     'reason', 'evidence', 'unresolved'}
                or decision['id'] != sid or decision['action'] != 'administrative_context'
                or decision['expectedKind'] != 'sector' or decision['parentNames'] != parents
                or decision['expectedTags'] != {'name:ar': row['officialIdentity']['sectorAr'], 'ref:tn:codegeo': old_sector}):
            raise ValueError('Reviewed successor sector identity or parent-only curation changed')
        for phase, sources in (('original', original_sources), ('effective', effective_sources)):
            if (states[sid][phase]['tags'].get('ref:tn:codegeo') != old_sector
                    or states[sid][phase]['tags'].get('name:ar') != row['officialIdentity']['sectorAr']
                    or sorted(key for key, source in sources.items() if source['kind'] == 'sector'
                              and source['tags'].get('ref:tn:codegeo') == old_sector) != [sid]):
                raise ValueError('Reviewed successor unchanged source sector is not unique')

    evidence = value['evidence']
    if not isinstance(evidence, list) or not evidence:
        raise ValueError('Reviewed successor membership lacks primary evidence')
    has_derived_primary = False
    for reference in evidence:
        content = aggregate_review_file(directory, reference, 'successor primary membership evidence')
        if (reference.get('role') != 'official_current_sector_membership'
                or not isinstance(reference.get('url'), str) or not reference['url'].startswith(('https://', 'http://'))
                or not isinstance(reference.get('requiredText'), list) or not reference['requiredText']
                or any(not isinstance(text, str) or not text or text not in content.decode('utf-8')
                       for text in reference['requiredText'])):
            raise ValueError('Reviewed successor primary membership text changed')
        if 'derivedProvenance' in reference:
            provenance = json.loads(aggregate_review_file(directory, reference['derivedProvenance'],
                                                          'successor derived primary provenance'))
            pdf = aggregate_review_file(directory, provenance['originalPdf'], 'successor original primary PDF')
            text = aggregate_review_file(directory, provenance['derivedText'], 'successor derived whole-page text')
            if (provenance.get('derivedTextIsOriginalResponseBytes') is not False or not pdf.startswith(b'%PDF-')
                    or text != content or provenance['derivedText']['sha256'] != reference['sha256']
                    or provenance['originalUrl'] != reference['url'].split('#', 1)[0]
                    or not isinstance(provenance.get('method'), str) or not provenance['method']):
                raise ValueError('Reviewed successor PDF-derived evidence provenance changed')
            has_derived_primary = True
    if not has_derived_primary:
        raise ValueError('Reviewed successor membership requires its pinned original-PDF-derived evidence')
    aggregate_review_file(directory, value['reviewEvidence'], 'successor independent identity scope review')

    closure = value['rawContextClosure']
    if (not isinstance(closure, dict) or set(closure) != {'baselineMetadata', 'governorateId',
                                                        'administrativeParentName', 'expectedRawIds'}
            or type(closure['governorateId']) is not int or closure['governorateId'] != review['expectedGovernor']['id']
            or closure['administrativeParentName'] != parents[0] or closure['expectedRawIds'] != sorted(raw)):
        raise ValueError('Malformed reviewed successor full raw-context closure')
    baseline = json.loads(aggregate_review_file(directory, closure['baselineMetadata'], 'successor complete baseline catalog'))
    baseline_rows = baseline['features']
    baseline_ids = {row['id'] for row in baseline_rows}
    observed = sorted(row['id'] for row in baseline_rows if row.get('governorateId') == closure['governorateId']
                      and (row.get('parentName') == closure['administrativeParentName']
                           or closure['administrativeParentName'] in row.get('contextAliases', [])))
    if (baseline.get('schemaVersion') != 1 or baseline['source']['sha256'] != source_sha256
            or len(baseline_ids) != len(baseline_rows) or observed != closure['expectedRawIds']
            or any(row != raw[row['id']]['currentMetadata'] for row in baseline_rows if row['id'] in raw)):
        raise ValueError('Reviewed successor complete baseline raw-context set or exact records changed')
    independent = value['independentPointIds']
    if (not isinstance(independent, list) or independent != sorted(set(independent))
            or set(independent) != {sid for sid, row in raw.items() if not row['currentMetadata']['hasBoundary']} - {identifier}
            or not independent):
        raise ValueError('Reviewed successor independent point set is incomplete')
    for sid in independent:
        row = raw[sid]
        if (not re.fullmatch(r'osm:node:[0-9]+', sid) or row['kind'] != 'hamlet'
                or bindings.get(sid) is not None or curation.get(sid) is not None
                or row['currentMetadata']['pickerGroupId'] != sid
                or extension['expectedBeforeGroups'].get(sid) != [sid]
                or extension['expectedAfterGroups'].get(sid) != [sid]):
            raise ValueError('Reviewed successor independent hamlet lost its separate singleton identity')

    coordinates = {'point': Point(review['rawCurrent']['lng'], review['rawCurrent']['lat']),
                   'base': Point(review['baseCurrent']['lng'], review['baseCurrent']['lat'])}
    coordinates.update({f'independentPoint:{sid}': Point(raw[sid]['currentMetadata']['lng'], raw[sid]['currentMetadata']['lat'])
                        for sid in independent})
    reviewed_membership = value['coordinateReviewedSectorIds']
    if not isinstance(reviewed_membership, dict) or set(reviewed_membership) != set(coordinates):
        raise ValueError('Reviewed successor sector-coordinate inventory changed')
    for role, point in coordinates.items():
        if set(reviewed_membership[role]) != {'original', 'effective'}:
            raise ValueError('Malformed reviewed successor sector-coordinate phases')
        for phase, sources in (('original', original_sources), ('effective', effective_sources)):
            hits = sorted(sid for sid, source in sources.items() if source['kind'] == 'sector'
                          and source.get('shape') is not None and source['shape'].covers(point))
            if len(hits) != 1 or hits[0] not in sectors or reviewed_membership[role][phase] != hits:
                raise ValueError('Reviewed successor point lacks unique unchanged transferred-sector context')

    absent = value['sourceOnlyAbsorbedPoints']
    if (not isinstance(absent, list) or not absent
            or [row['id'] for row in absent] != sorted({row['id'] for row in absent})):
        raise ValueError('Malformed reviewed successor absorbed source-only point inventory')
    absent_ids = {row['id'] for row in absent}
    if absent_ids & (set(states) | baseline_ids):
        raise ValueError('Reviewed successor absorbed point gained a raw/context identity')
    for row in absent:
        sid, receiver = row['id'], row['absorbedBySectorId']
        if (set(row) != {'id', 'original', 'effective', 'expectedCuration', 'absorbedBySectorId', 'expectedRawPresence'}
                or not re.fullmatch(r'osm:node:[0-9]+', sid) or receiver not in sectors
                or row['expectedRawPresence'] is not False or row['expectedCuration'] is not None
                or curation.get(sid) is not None
                or row['effective'] != {**row['original'], 'sourceId': 'osm'}
                or any(r.get('pickerGroupId') == sid for r in baseline_rows)):
            raise ValueError('Reviewed successor source-only point presence, receiver or curation changed')
        for phase, sources in (('original', original_sources), ('effective', effective_sources)):
            source = sources[sid]
            point = source.get('point')
            if (point is None or point.geom_type != 'Point' or point.is_empty or source.get('shape') is not None
                    or source['kind'] != 'village' or row[phase] != {'tags': source['tags'], 'kind': source['kind'],
                        'sourceId': source.get('sourceId'), 'geometrySha256': aggregate_geometry_sha256(point)}
                    or row['original']['sourceId'] is not None):
                raise ValueError('Reviewed successor absorbed source point changed')
            labels = names(source['tags'])
            identity = {'name': labels[0], 'aliases': labels[1:]}
            matching = sorted(key for key, area in sources.items() if area.get('shape') is not None
                              and area['shape'].covers(point) and names(area['tags'])
                              and same_picker_name(identity, {'name': names(area['tags'])[0], 'aliases': names(area['tags'])[1:]}))
            if matching != [receiver]:
                raise ValueError('Reviewed successor absorbed point no longer has one exact source receiver')
    # This is an additional source-point closure, separate from raw/catalog rows.
    for sources in (original_sources, effective_sources):
        covered = {sid for sid, source in sources.items() if source.get('point') is not None
                   and any(sources[sector_id]['shape'].covers(source['point']) for sector_id in sectors)}
        if covered != {identifier, *independent, *absent_ids}:
            raise ValueError('Reviewed successor complete covered source-point inventory changed')
    return {'independentPointIds': independent,
            'finalDescriptor': {'governorateId': closure['governorateId'],
                                'administrativeParentName': closure['administrativeParentName'],
                                'expectedRawIds': closure['expectedRawIds'],
                                'sourceOnlyAbsentIds': sorted(absent_ids)}}

def validate_reviewed_official_boundary_parent_membership(reference, directory, compat, boundary_manifest,
                                                          feature, original_sources, effective_sources,
                                                          source_sha256):
    METHOD = 'reviewed_official_boundary_parent_membership'
    LEGACY = 'current_coded_namesake_administrative_parent'
    DIFFERENCE_METHOD = ('shapely WGS84 degree2; local estimate = difference.area*111.32**2*'
                         'cos(radians(fixedSectorAnchorLatitude)); not UTM/survey')
    directory = Path(directory)

    def _keys(value, keys, label):
        if not isinstance(value, dict):
            raise ValueError(f'{label}: expected object')
        expected = set(keys)
        actual = set(value)
        if actual != expected:
            missing = sorted(expected - actual)
            extra = sorted(actual - expected)
            raise ValueError(f'{label}: expected keys {sorted(expected)}; missing={missing}; extra={extra}')
        return value

    def _str(value, label, nonempty=False):
        if not isinstance(value, str):
            raise ValueError(f'{label}: expected string')
        if nonempty and not value:
            raise ValueError(f'{label}: expected non-empty string')
        return value

    def _sha(value, label):
        value = _str(value, label)
        if re.fullmatch(r'[0-9a-f]{64}', value) is None:
            raise ValueError(f'{label}: expected lowercase 64-character hex sha256')
        return value

    def _num(value, label, positive=False):
        if type(value) not in (int, float) or isinstance(value, bool) or not math.isfinite(value):
            raise ValueError(f'{label}: expected finite number')
        if positive and value <= 0:
            raise ValueError(f'{label}: expected positive number')
        return value

    def _point(value, label):
        _keys(value, ('lat', 'lng'), label)
        _num(value['lat'], f'{label}.lat')
        _num(value['lng'], f'{label}.lng')
        return value

    def _load(ref, label, allow_size=False):
        expected = ('file', 'sha256', 'bytes') if allow_size and 'bytes' in ref else ('file', 'sha256')
        _keys(ref, expected, label)
        _sha(ref['sha256'], f'{label}.sha256')
        raw = aggregate_review_file(directory, ref, label)
        if 'bytes' in ref and (type(ref['bytes']) is not int or ref['bytes'] != len(raw)):
            raise ValueError(f'{label}: byte count mismatch')
        try:
            doc = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise ValueError(f'{label}: invalid JSON') from exc
        if not isinstance(doc, dict):
            raise ValueError(f'{label}: expected object')
        return doc

    def _canonical(value):
        return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                         separators=(',', ':')).encode('utf-8')).hexdigest()

    _sha(source_sha256, 'sourceSha256')
    if not isinstance(compat, dict) or not isinstance(boundary_manifest, dict) or not isinstance(feature, dict):
        raise ValueError('reviewed official boundary parent membership: missing active context')
    review = _load(reference, 'reviewed official boundary parent membership')
    _keys(review, (
        'schemaVersion', 'method', 'sourceSha256', 'ownerId', 'parentId', 'previousManifest',
        'previousRecord', 'previousProofCollection', 'previousProofRecordObjectSha256',
        'reviewedBoundaries', 'acceptedBoundaryReplacement', 'administrativeScopeReview',
        'scopePrimarySource', 'wholeScopeRow', 'currentOfficialRegistry', 'originalChildGeometrySha256',
        'effectiveChildGeometrySha256', 'originalParentGeometrySha256', 'effectiveParentGeometrySha256',
        'parentMembership', 'qualification'), 'reviewed official boundary parent membership')
    if type(review['schemaVersion']) is not int or review['schemaVersion'] != 1:
        raise ValueError('reviewed official boundary parent membership.schemaVersion: expected 1')
    if review['method'] != METHOD:
        raise ValueError('reviewed official boundary parent membership.method: unexpected value')
    if review['sourceSha256'] != source_sha256:
        raise ValueError('reviewed official boundary parent membership.sourceSha256: mismatch')
    owner_id = _str(review['ownerId'], 'reviewed official boundary parent membership.ownerId', nonempty=True)
    parent_id = _str(review['parentId'], 'reviewed official boundary parent membership.parentId', nonempty=True)
    if owner_id != compat.get('ownerId'):
        raise ValueError('reviewed official boundary parent membership: owner identity mismatch')
    _str(review['qualification'], 'reviewed official boundary parent membership.qualification', nonempty=True)
    for key in ('previousManifest', 'previousRecord', 'previousProofCollection', 'reviewedBoundaries',
                'acceptedBoundaryReplacement'):
        if review[key] != compat.get(key):
            raise ValueError(f'reviewed official boundary parent membership.{key}: does not match active compatibility')
    _sha(review['previousProofRecordObjectSha256'],
         'reviewed official boundary parent membership.previousProofRecordObjectSha256')
    for prior in (compat.get('previousManifest'), compat.get('previousProofCollection'),
                  compat.get('reviewedBoundaries')):
        if (isinstance(prior, dict) and prior.get('file') == reference.get('file')
                and prior.get('sha256') == reference.get('sha256')):
            raise ValueError('reviewed official boundary parent membership: review reference reuses a prior lineage reference')

    proof_doc = _load(compat['previousProofCollection'], 'compatibility.previousProofCollection')
    proof_rows = proof_doc.get('records')
    if not isinstance(proof_rows, list):
        raise ValueError('compatibility.previousProofCollection.records: expected list')
    proof_matches = [row for row in proof_rows if isinstance(row, dict) and row.get('id') == owner_id]
    if len(proof_matches) != 1 or proof_matches[0].get('status') != 'eligible_proposal':
        raise ValueError('compatibility.previousProofCollection: eligible owner proof is not unique')
    if _canonical(proof_matches[0]) != review['previousProofRecordObjectSha256']:
        raise ValueError('reviewed official boundary parent membership.previousProofRecordObjectSha256: canonical proof mismatch')

    scope_ref = review['administrativeScopeReview']
    _keys(scope_ref, ('file', 'sha256', 'bytes') if isinstance(scope_ref, dict) and 'bytes' in scope_ref else ('file', 'sha256'),
          'reviewed official boundary parent membership.administrativeScopeReview')
    _sha(scope_ref['sha256'], 'reviewed official boundary parent membership.administrativeScopeReview.sha256')
    if boundary_manifest.get('administrativeScopeReview') != scope_ref:
        target_ref = compat.get('reviewedBoundaries')
        if not isinstance(target_ref, dict):
            raise ValueError('reviewed official boundary parent membership: compatibility reviewedBoundaries is missing')
        target_sha = _sha(target_ref.get('sha256'), 'reviewed official boundary parent membership.compatibility.reviewedBoundaries.sha256')
        current = boundary_manifest
        current_sources = current.get('sources')
        if not isinstance(current_sources, list):
            raise ValueError('reviewed official boundary parent membership: active boundary manifest sources are missing')
        current_len = len(current_sources)
        seen = set()
        reached = None
        while current_len:
            lineage_ref = current.get('lineageCompatibility')
            if not isinstance(lineage_ref, dict):
                raise ValueError('reviewed official boundary parent membership: lineageCompatibility is missing')
            proof = _load(lineage_ref, 'lineage compatibility', allow_size=True)
            proof_keys = ('schemaVersion', 'method', 'sourceSha256', 'previousManifest',
                          'addedSources', 'scopeSuccessor', 'evidence')
            if 'aggregateScopeSuccessor' in proof:
                proof_keys += ('aggregateScopeSuccessor',)
            _keys(proof, proof_keys, 'lineage compatibility')
            if proof['schemaVersion'] != 1 or proof['method'] != 'reviewed_append_only_boundary_extension':
                raise ValueError('lineage compatibility must be schemaVersion 1 reviewed_append_only_boundary_extension')
            added = proof['addedSources']
            if not isinstance(added, list) or not added:
                raise ValueError('lineage compatibility addedSources must be a non-empty list')
            previous_ref = proof['previousManifest']
            previous = _load(previous_ref, 'lineage previous manifest', allow_size=True)
            previous_sha = previous_ref['sha256']
            if previous_sha in seen:
                raise ValueError('lineage compatibility contains a cycle')
            seen.add(previous_sha)
            previous_sources = previous.get('sources')
            if not isinstance(previous_sources, list):
                raise ValueError('lineage previous manifest sources are missing')
            if len(previous_sources) >= current_len or len(previous_sources) + len(added) != current_len:
                raise ValueError('lineage compatibility must strictly decrease the source count by at least one provider')
            current = previous
            current_len = len(previous_sources)
            if previous_sha == target_sha:
                reached = previous
                break
        if reached is None:
            raise ValueError('reviewed official boundary parent membership: reviewedBoundaries lineage target was not reached')
        if reached.get('administrativeScopeReview') != scope_ref:
            raise ValueError('reviewed official boundary parent membership: lineage target administrativeScopeReview does not match review')
        active_scope_ref = boundary_manifest.get('administrativeScopeReview')
        old_scope = _load(scope_ref, 'administrative scope review', allow_size=True)
        new_scope = _load(active_scope_ref, 'active administrative scope review', allow_size=True)
        if (not isinstance(old_scope, dict) or old_scope.get('schemaVersion') != 1
                or not isinstance(old_scope.get('records'), list)
                or not isinstance(new_scope, dict) or new_scope.get('schemaVersion') != 1
                or not isinstance(new_scope.get('records'), list)):
            raise ValueError('administrative scope documents must be schemaVersion 1 objects with records lists')
        old_records = old_scope['records']
        new_records = new_scope['records']
        if (len(new_records) < len(old_records)
                or new_records[:len(old_records)] != old_records
                or {k: v for k, v in new_scope.items() if k != 'records'} != {k: v for k, v in old_scope.items() if k != 'records'}):
            raise ValueError('active administrative scope must retain historical records as an exact prefix and change only records')
        old_owner = [row for row in old_records if isinstance(row, dict) and row.get('id') == owner_id]
        new_owner = [row for row in new_records if isinstance(row, dict) and row.get('id') == owner_id]
        if len(old_owner) != 1 or len(new_owner) != 1 or new_owner[0] != old_owner[0]:
            raise ValueError('active administrative scope: owner row is not the unique unchanged row')
        if sum(1 for row in new_records if isinstance(row, dict) and row.get('officialCode') == old_owner[0].get('officialCode')) != 1:
            raise ValueError('active administrative scope: owner officialCode is not unique')
    if scope_ref.get('file') == reference.get('file') and scope_ref.get('sha256') == reference.get('sha256'):
        raise ValueError('reviewed official boundary parent membership: scope reference is self-referential')
    scope_doc = _load(scope_ref, 'administrative scope review', allow_size=True)
    if scope_doc.get('primarySource') != review['scopePrimarySource']:
        raise ValueError('reviewed official boundary parent membership.scopePrimarySource: mismatch')
    scope_rows = scope_doc.get('records')
    if not isinstance(scope_rows, list):
        raise ValueError('administrative scope review: expected records list')
    scope_matches = [row for row in scope_rows if isinstance(row, dict) and row.get('id') == owner_id]
    if len(scope_matches) != 1:
        raise ValueError('administrative scope review: owner row is not unique')
    whole = scope_matches[0]
    if review['wholeScopeRow'] != whole:
        raise ValueError('reviewed official boundary parent membership.wholeScopeRow: does not match unique accepted row')
    if whole.get('conclusion') != 'whole_imada':
        raise ValueError('administrative scope review: accepted scope kind is not whole_imada')
    if sum(1 for row in scope_rows if isinstance(row, dict)
           and row.get('officialCode') == whole.get('officialCode')) != 1:
        raise ValueError('administrative scope review: official code row is not unique')

    old_record = compat.get('previousRecord')
    new_record = compat.get('activeRecord')
    if not isinstance(old_record, dict) or not isinstance(new_record, dict):
        raise ValueError('reviewed official boundary parent membership: previous/active records are missing')
    old_identities = old_record.get('identityEvidence')
    new_identities = new_record.get('identityEvidence')
    if not isinstance(old_identities, list) or not isinstance(new_identities, list):
        raise ValueError('reviewed official boundary parent membership: identityEvidence is missing')
    old_children = [row for row in old_identities if isinstance(row, dict)
                    and row.get('method') == 'current_coded_sector' and row.get('id') == owner_id]
    old_legacy = [row for row in old_identities if isinstance(row, dict) and row.get('method') == LEGACY]
    new_parents = [row for row in new_identities if isinstance(row, dict) and row.get('method') == METHOD]
    if (len(old_children) != 1 or len(old_legacy) != 1 or len(new_parents) != 1
            or new_parents[0].get('id') != parent_id):
        raise ValueError('reviewed official boundary parent membership: expected exactly one child and legacy/new parent identity')
    old_child = old_children[0]
    old_parent = old_legacy[0]
    new_parent = new_parents[0]
    if old_parent.get('id') != parent_id:
        raise ValueError('reviewed official boundary parent membership.parentId: does not match legacy parent identity')
    registry = review['currentOfficialRegistry']
    if not isinstance(registry, dict) or registry != old_child.get('currentOfficialRecord'):
        raise ValueError('reviewed official boundary parent membership.currentOfficialRegistry: does not match old child registry')
    if whole.get('officialRegistryRow') != registry:
        raise ValueError('reviewed official boundary parent membership.wholeScopeRow.officialRegistryRow: does not match child registry')

    replacement = compat.get('acceptedBoundaryReplacement')
    if (not isinstance(replacement, dict) or replacement.get('id') != owner_id
            or replacement.get('record', {}).get('scopeKind', 'whole_imada') != 'whole_imada'):
        raise ValueError('reviewed official boundary parent membership: accepted whole boundary replacement is missing')
    source_record = replacement.get('sourceRecord')
    if not isinstance(source_record, dict):
        raise ValueError('reviewed official boundary parent membership: accepted source record is missing')
    source_id = _str(source_record.get('id'), 'reviewed official boundary parent membership.sourceRecord.id', nonempty=True)
    properties = feature.get('properties') or {}
    if not isinstance(properties, dict):
        raise ValueError('reviewed official boundary parent membership: accepted feature properties are missing')
    if (properties.get('id') != owner_id or properties.get('sourceId') != source_id
            or properties.get('officialCode') != registry.get('sectorCode')
            or properties.get('nameAr') != registry.get('sectorAr')
            or properties.get('nameFr') != registry.get('sectorFr')
            or properties.get('parentAr') != registry.get('delegationAr')
            or properties.get('parentFr') != registry.get('delegationFr')
            or properties.get('governorateAr') != registry.get('governorateAr')
            or properties.get('governorateFr') != registry.get('governorateFr')
            or properties.get('governorateCode') != registry.get('governorateCode')
            or properties.get('delegationCode') != registry.get('delegationCode')):
        raise ValueError('reviewed official boundary parent membership: accepted feature names/codes do not match the registry')
    if (whole.get('officialCode') != registry.get('sectorCode')
            or whole.get('imadaName') != registry.get('sectorAr')
            or whole.get('delegationName') != registry.get('delegationAr')
            or whole.get('governorateName') != registry.get('governorateAr')
            or whole.get('delegationCode') != registry.get('delegationCode')
            or whole.get('governorateCode') != registry.get('governorateCode')
            or whole.get('boundarySourceId') != source_id
            or whole.get('boundarySourceFile') != replacement.get('geojson', {}).get('file')
            or whole.get('boundarySourceSha256') != replacement.get('geojson', {}).get('sha256')):
        raise ValueError('reviewed official boundary parent membership: scope hierarchy or source does not match the accepted feature')

    parent_registry = old_parent.get('currentOfficialRecord')
    if not isinstance(parent_registry, dict) or not set(parent_registry).issubset(registry):
        raise ValueError('reviewed official boundary parent membership: parent registry is not a child-registry subset')
    if any(registry.get(key) != value for key, value in parent_registry.items()):
        raise ValueError('reviewed official boundary parent membership: parent registry values changed')
    parent_code = _str(old_parent.get('code'), 'reviewed official boundary parent membership.parent code', nonempty=True)
    if parent_code != registry.get('delegationCode') or parent_code != parent_registry.get('delegationCode'):
        raise ValueError('reviewed official boundary parent membership: parent code is not the delegation code')
    parent_name_key = picker_review_name_key(old_parent.get('name', ''))
    delegation_name_key = picker_review_name_key(registry.get('delegationAr', ''))
    if parent_name_key not in (delegation_name_key,
                               picker_review_name_key('معتمدية ' + registry.get('delegationAr', ''))):
        raise ValueError('reviewed official boundary parent membership: parent name does not match the delegation registry')
    expected_parent = json.loads(json.dumps(old_parent))
    expected_parent['method'] = METHOD
    expected_parent['parentMembershipReview'] = reference
    if (new_parent != expected_parent
            or json.dumps(new_parent, ensure_ascii=False, separators=(',', ':'), sort_keys=False)
               != json.dumps(expected_parent, ensure_ascii=False, separators=(',', ':'), sort_keys=False)):
        raise ValueError('reviewed official boundary parent membership: new parent identity is not the exact legacy identity with method/reference replacement')
    if new_parent.get('currentOfficialRecord') != parent_registry:
        raise ValueError('reviewed official boundary parent membership: parent registry changed')

    original_child = original_sources.get(owner_id)
    effective_child = effective_sources.get(owner_id)
    original_parent = original_sources.get(parent_id)
    effective_parent = effective_sources.get(parent_id)
    if (not isinstance(original_child, dict) or not isinstance(effective_child, dict)
            or not isinstance(original_parent, dict) or not isinstance(effective_parent, dict)
            or original_child.get('shape') is None or effective_child.get('shape') is None
            or original_parent.get('shape') is None or effective_parent.get('shape') is None):
        raise ValueError('reviewed official boundary parent membership: actual original/effective source geometry is missing')
    if original_child.get('kind') != 'sector' or effective_child.get('kind') != 'sector':
        raise ValueError('reviewed official boundary parent membership: child sources are not sectors')
    if (original_child['shape'].is_empty or not original_child['shape'].is_valid
            or effective_child['shape'].is_empty or not effective_child['shape'].is_valid):
        raise ValueError('reviewed official boundary parent membership: child geometry is invalid')
    if aggregate_geometry_sha256(original_child['shape']) != review['originalChildGeometrySha256']:
        raise ValueError('reviewed official boundary parent membership.originalChildGeometrySha256: mismatch')
    if aggregate_geometry_sha256(effective_child['shape']) != review['effectiveChildGeometrySha256']:
        raise ValueError('reviewed official boundary parent membership.effectiveChildGeometrySha256: mismatch')
    if old_child.get('effectiveOriginalGeometrySha256') != review['originalChildGeometrySha256']:
        raise ValueError('reviewed official boundary parent membership: old child original hash changed')
    parent_hash = review['originalParentGeometrySha256']
    if (review['effectiveParentGeometrySha256'] != parent_hash
            or old_parent.get('effectiveOriginalGeometrySha256') != parent_hash):
        raise ValueError('reviewed official boundary parent membership: parent hashes changed')
    if (original_parent.get('kind') != 'delegation' or effective_parent.get('kind') != 'delegation'
            or original_parent.get('tags') != old_parent.get('expectedOriginalTags')
            or effective_parent.get('tags') != old_parent.get('expectedOriginalTags')
            or original_parent['shape'].is_empty or not original_parent['shape'].is_valid
            or effective_parent['shape'].is_empty or not effective_parent['shape'].is_valid
            or aggregate_geometry_sha256(original_parent['shape']) != parent_hash
            or aggregate_geometry_sha256(effective_parent['shape']) != parent_hash):
        raise ValueError('reviewed official boundary parent membership: parent source phase changed')
    if sum(1 for obj in effective_sources.values()
           if (isinstance(obj, dict) and obj.get('kind') == 'delegation'
               and isinstance(obj.get('tags'), dict)
               and obj['tags'].get('ref:tn:codegeo') == parent_code)) != 1:
        raise ValueError('reviewed official boundary parent membership: parent code is not unique among delegations')

    membership = review['parentMembership']
    _keys(membership, ('anchor', 'base', 'oldParentCoversOldChild', 'newParentCoversEffectiveChild',
                       'differenceAreaDegree2', 'differenceAreaKm2', 'differenceMethod',
                       'differenceGeometrySha256', 'fixedAnchorLatitude', 'toleranceRel', 'toleranceAbs'),
          'reviewed official boundary parent membership.parentMembership')
    anchor = _point(membership['anchor'], 'reviewed official boundary parent membership.parentMembership.anchor')
    base = _point(membership['base'], 'reviewed official boundary parent membership.parentMembership.base')
    if membership['oldParentCoversOldChild'] is not True or membership['newParentCoversEffectiveChild'] is not False:
        raise ValueError('reviewed official boundary parent membership.parentMembership: coverage flags changed')
    if membership['toleranceRel'] != 1e-12 or membership['toleranceAbs'] != 1e-12:
        raise ValueError('reviewed official boundary parent membership.parentMembership: tolerances changed')
    if membership['differenceMethod'] != DIFFERENCE_METHOD:
        raise ValueError('reviewed official boundary parent membership.parentMembership.differenceMethod: unexpected value')
    _sha(membership['differenceGeometrySha256'],
         'reviewed official boundary parent membership.parentMembership.differenceGeometrySha256')
    _num(membership['differenceAreaDegree2'],
         'reviewed official boundary parent membership.parentMembership.differenceAreaDegree2', positive=True)
    _num(membership['differenceAreaKm2'],
         'reviewed official boundary parent membership.parentMembership.differenceAreaKm2')
    _num(membership['fixedAnchorLatitude'],
         'reviewed official boundary parent membership.parentMembership.fixedAnchorLatitude')
    old_members = old_record.get('members')
    if not isinstance(old_members, list) or len(old_members) != 1 or old_members[0].get('id') != owner_id:
        raise ValueError('reviewed official boundary parent membership: old owner member is not the unique child')
    old_member = old_members[0]
    if anchor['lat'] != old_member.get('expectedLat') or anchor['lng'] != old_member.get('expectedLng'):
        raise ValueError('reviewed official boundary parent membership.parentMembership.anchor: is not the fixed member anchor')
    expected_base = old_record.get('expectedBase')
    if (not isinstance(expected_base, dict) or base['lat'] != expected_base.get('lat')
            or base['lng'] != expected_base.get('lng')):
        raise ValueError('reviewed official boundary parent membership.parentMembership.base: is not the old expected base')
    if membership['fixedAnchorLatitude'] != anchor['lat']:
        raise ValueError('reviewed official boundary parent membership.parentMembership.fixedAnchorLatitude: mismatch')
    anchor_point = Point(anchor['lng'], anchor['lat'])
    base_point = Point(base['lng'], base['lat'])
    for point in (anchor_point, base_point):
        for source_shape in (original_parent['shape'], effective_parent['shape'],
                             original_child['shape'], effective_child['shape']):
            if not source_shape.covers(point):
                raise ValueError('reviewed official boundary parent membership: parent/child geometry no longer covers the fixed membership points')
    if not effective_parent['shape'].covers(original_child['shape']):
        raise ValueError('reviewed official boundary parent membership: old parent no longer covers old child')
    if effective_parent['shape'].covers(effective_child['shape']):
        raise ValueError('reviewed official boundary parent membership: new parent unexpectedly covers effective child')
    difference = effective_child['shape'].difference(effective_parent['shape'])
    if difference.is_empty or not difference.is_valid or not math.isfinite(difference.area) or difference.area <= 0:
        raise ValueError('reviewed official boundary parent membership: difference geometry is empty')
    if aggregate_geometry_sha256(difference) != membership['differenceGeometrySha256']:
        raise ValueError('reviewed official boundary parent membership.parentMembership.differenceGeometrySha256: mismatch')
    if not math.isclose(difference.area, membership['differenceAreaDegree2'], rel_tol=1e-12, abs_tol=1e-12):
        raise ValueError('reviewed official boundary parent membership.parentMembership.differenceAreaDegree2: mismatch')
    local_area = difference.area * 111.32 ** 2 * math.cos(math.radians(membership['fixedAnchorLatitude']))
    if not math.isclose(local_area, membership['differenceAreaKm2'], rel_tol=1e-12, abs_tol=1e-12):
        raise ValueError('reviewed official boundary parent membership.parentMembership.differenceAreaKm2: mismatch')
    return reference


def load_reviewed_picker_manifest_for_lineage(directory, reference, picker_path, picker, source_sha256,
                                              reviewed_boundaries=None, official_report=None,
                                              original_sources=None, effective_sources=None,
                                              _lineage_depth=0, _lineage_seen=None) -> bytes:
    import re

    METHOD = 'reviewed_source_bound_residential_base_display_identity'
    SECTION = 'residentialDisplayAssociations'
    COMPAT_METHOD = 'reviewed_picker_proof_successor'
    STRICT_METHOD = 'reviewed_picker_strict_boundary_successor'
    LINEAGE_METHOD = 'reviewed_source_bound_residential_official_boundary_lineage'
    SHA_RE = re.compile(r'^[0-9a-f]{64}$')

    def _exact_keys(value, keys, label):
        if not isinstance(value, dict):
            raise ValueError(f'{label}: expected object')
        expected = set(keys)
        actual = set(value)
        if actual != expected:
            missing = sorted(expected - actual)
            extra = sorted(actual - expected)
            raise ValueError(f'{label}: expected keys {sorted(expected)}; missing={missing}; extra={extra}')
        return value

    def _str(value, label, nonempty=False):
        if not isinstance(value, str):
            raise ValueError(f'{label}: expected string')
        if nonempty and not value:
            raise ValueError(f'{label}: expected non-empty string')
        return value

    def _sha(value, label):
        value = _str(value, label)
        if SHA_RE.fullmatch(value) is None:
            raise ValueError(f'{label}: expected lowercase 64-character hex sha256')
        return value

    def _int(value, label):
        if not isinstance(value, int) or isinstance(value, bool):
            raise ValueError(f'{label}: expected integer')
        return value

    def _resolve(rel, label):
        rel = _str(rel, f'{label}.file', nonempty=True)
        rel_path = Path(rel)
        if rel_path.is_absolute() or '..' in rel_path.parts:
            raise ValueError(f'{label}: expected relative path without ..')
        base = Path(directory).resolve()
        candidate = (base / rel_path).resolve()
        try:
            candidate.relative_to(base)
        except ValueError as exc:
            raise ValueError(f'{label}: path escapes directory') from exc
        return candidate

    def _load_ref(ref, label):
        _exact_keys(ref, ('file', 'sha256'), label)
        _sha(ref['sha256'], f'{label}.sha256')
        path = _resolve(ref['file'], label)
        raw = aggregate_review_file(directory, ref, label)
        try:
            doc = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise ValueError(f'{label}: invalid JSON') from exc
        return path, raw, doc

    def _section_rows(doc, label):
        rows = doc.get(SECTION)
        if not isinstance(rows, list) or any(not isinstance(row, dict) for row in rows):
            raise ValueError(f'{label}: expected the top-level residentialDisplayAssociations list')
        return rows

    _sha(source_sha256, 'sourceSha256')
    _exact_keys(reference, ('file', 'sha256'), 'reference')
    _sha(reference['sha256'], 'reference.sha256')
    active_path = _resolve(reference['file'], 'reference')
    if active_path != Path(picker_path).resolve():
        raise ValueError('Picker lineage reference does not bind the active picker path')

    try:
        active_raw = active_path.read_bytes()
    except OSError as exc:
        raise ValueError(f'active picker manifest: cannot read {active_path}') from exc
    try:
        active_json = json.loads(active_raw)
    except json.JSONDecodeError as exc:
        raise ValueError('active picker manifest: invalid JSON') from exc
    if not isinstance(active_json, dict):
        raise ValueError('active picker manifest: expected object')
    if active_json != picker:
        raise ValueError('active picker manifest: does not match passed picker')
    if _int(active_json.get('schemaVersion'), 'active picker manifest.schemaVersion') != 1:
        raise ValueError('active picker manifest.schemaVersion: expected 1')
    if active_json.get('sourceSha256') != source_sha256:
        raise ValueError('active picker manifest.sourceSha256: does not match source_sha256')

    active_sha = hashlib.sha256(active_raw).hexdigest()
    if active_sha == reference['sha256']:
        _same_sha_lineage = active_json.get('lineageCompatibility')
        _same_sha_method = None
        if isinstance(_same_sha_lineage, dict):
            _same_sha_path, _same_sha_raw, _same_sha_doc = _load_ref(_same_sha_lineage, 'picker lineage compatibility')
            if isinstance(_same_sha_doc, dict):
                _same_sha_method = _same_sha_doc.get('method')
        if _same_sha_method != STRICT_METHOD:
            return active_raw

    if 'lineageCompatibility' not in active_json:
        raise ValueError('active picker manifest: sha256 mismatch without lineageCompatibility')
    compat_ref = active_json['lineageCompatibility']
    _compat_path, _compat_raw, compat = _load_ref(compat_ref, 'picker lineage compatibility')
    if isinstance(compat, dict) and compat.get('method') == STRICT_METHOD:
        def _strict_boundary_successor_branch():
            _exact_keys(compat, (
                'schemaVersion', 'method', 'sourceSha256', 'previousManifest', 'ownerId', 'previousRecord',
                'activeRecord', 'previousProofCollection', 'successorProofCollection', 'reviewedBoundaries',
                'acceptedBoundaryReplacement', 'qualification'), 'lineageCompatibility (strict boundary successor)')
            if _int(compat['schemaVersion'], 'lineageCompatibility.schemaVersion') != 1:
                raise ValueError('lineageCompatibility.schemaVersion: expected 1')
            if compat['sourceSha256'] != source_sha256:
                raise ValueError('lineageCompatibility.sourceSha256: does not match source_sha256')
            _str(compat['qualification'], 'lineageCompatibility.qualification', nonempty=True)
            if _lineage_depth >= 8:
                raise ValueError('lineageCompatibility: strict boundary successor depth exceeded')
            _seen = set() if _lineage_seen is None else set(_lineage_seen)
            if (str(active_path), active_sha) in _seen:
                raise ValueError('lineageCompatibility: strict boundary successor cycle detected')
            _seen.add((str(active_path), active_sha))
            _owner_id = _str(compat['ownerId'], 'lineageCompatibility.ownerId', nonempty=True)
            if not isinstance(reviewed_boundaries, (str, Path)) or not isinstance(official_report, dict):
                raise ValueError('lineageCompatibility: strict successor needs the active reviewed boundaries and report')
            if not isinstance(original_sources, dict) or not isinstance(effective_sources, dict):
                raise ValueError('lineageCompatibility: strict successor needs the actual source phases')
            _prev_ref = compat['previousManifest']
            _exact_keys(_prev_ref, ('file', 'sha256'), 'lineageCompatibility.previousManifest')
            _sha(_prev_ref['sha256'], 'lineageCompatibility.previousManifest.sha256')
            _prev_path, _prev_raw, _previous_json = _load_ref(_prev_ref, 'lineageCompatibility.previousManifest')
            if _prev_path == active_path:
                raise ValueError('lineageCompatibility.previousManifest: path must differ from active path')
            if (str(_prev_path), _prev_ref['sha256']) in _seen:
                raise ValueError('lineageCompatibility.previousManifest: lineage cycle detected')
            if (not isinstance(_previous_json, dict)
                    or _int(_previous_json.get('schemaVersion'), 'previous manifest.schemaVersion') != 1):
                raise ValueError('previous manifest.schemaVersion: expected 1')
            if _previous_json.get('sourceSha256') != source_sha256:
                raise ValueError('previous manifest.sourceSha256: does not match source_sha256')
            _old_rows = _previous_json.get('records')
            _new_rows = active_json.get('records')
            if not isinstance(_old_rows, list) or not isinstance(_new_rows, list):
                raise ValueError('lineageCompatibility: picker manifests need a records list')
            _old_index = [i for i, row in enumerate(_old_rows) if isinstance(row, dict) and row.get('id') == _owner_id]
            _new_index = [i for i, row in enumerate(_new_rows) if isinstance(row, dict) and row.get('id') == _owner_id]
            if len(_old_index) != 1 or _old_index != _new_index:
                raise ValueError('lineageCompatibility: owner id must occur once at the same index')
            _old_record = compat['previousRecord']
            _new_record = compat['activeRecord']
            if (not isinstance(_old_record, dict) or not isinstance(_new_record, dict)
                    or _old_rows[_old_index[0]] != _old_record or _new_rows[_new_index[0]] != _new_record
                    or json.dumps(_old_rows[_old_index[0]], separators=(',', ':'), sort_keys=False)
                       != json.dumps(_old_record, separators=(',', ':'), sort_keys=False)
                    or json.dumps(_new_rows[_new_index[0]], separators=(',', ':'), sort_keys=False)
                       != json.dumps(_new_record, separators=(',', ':'), sort_keys=False)):
                raise ValueError('lineageCompatibility: owner records do not match the manifests')
            _replacement = compat['acceptedBoundaryReplacement']
            if (not isinstance(_replacement, dict) or set(_replacement) != {
                    'id', 'sourceRecord', 'record', 'application', 'geojson', 'featureSha256', 'providerEvidence'}
                    or _replacement.get('id') != _owner_id):
                raise ValueError('lineageCompatibility.acceptedBoundaryReplacement: unexpected shape')
            _source = _replacement.get('sourceRecord')
            _accepted = _replacement.get('record')
            _boundary_ref = compat['reviewedBoundaries']
            _exact_keys(_boundary_ref, ('file', 'sha256'), 'lineageCompatibility.reviewedBoundaries')
            _sha(_boundary_ref['sha256'], 'lineageCompatibility.reviewedBoundaries.sha256')
            if _resolve(_boundary_ref['file'], 'lineageCompatibility.reviewedBoundaries') != Path(reviewed_boundaries).resolve():
                raise ValueError('lineageCompatibility.reviewedBoundaries: does not bind the active accepted manifest')
            _boundary_manifest = load_reviewed_boundary_manifest_for_lineage(
                directory, _boundary_ref, reviewed_boundaries, official_report)
            if (not isinstance(_boundary_manifest, dict) or not isinstance(_source, dict) or not isinstance(_accepted, dict)
                    or [s for s in _boundary_manifest.get('sources', []) if s.get('id') == _source.get('id')] != [_source]
                    or [r for r in _source.get('records', []) if r.get('id') == _owner_id] != [_accepted]
                    or _source.get('sourceSha256') != source_sha256 or _source.get('id') == 'osm'
                    or _accepted.get('action') != 'replace' or not _accepted.get('expectedOriginalTags')):
                raise ValueError('lineageCompatibility.acceptedBoundaryReplacement: does not match the accepted loader report')
            _application = {'id': _owner_id, 'action': 'replace', 'officialCode': _accepted['officialCode'],
                            'sourceId': _source['id']}
            if (_replacement.get('application') != _application
                    or [r for r in official_report.get('applications', []) if r.get('id') == _owner_id] != [_application]
                    or official_report.get('sources', {}).get(_source['id']) != {
                        k: v for k, v in _source.items() if k not in ('file', 'records')}):
                raise ValueError('lineageCompatibility.acceptedBoundaryReplacement: provider evidence is not the active report')
            if _replacement.get('geojson') != {'file': _source['file'], 'sha256': _source['sha256']}:
                raise ValueError('lineageCompatibility.acceptedBoundaryReplacement: accepted feature file is not the provider record')
            _sha(_replacement['featureSha256'], 'lineageCompatibility.acceptedBoundaryReplacement.featureSha256')
            _collection = json.loads(aggregate_review_file(Path(reviewed_boundaries).parent,
                                                           _replacement['geojson'], 'picker accepted boundary feature'))
            _matches = [f for f in _collection.get('features', []) if f.get('id') == _owner_id] if isinstance(_collection, dict) else []
            if len(_matches) != 1:
                raise ValueError('lineageCompatibility.acceptedBoundaryReplacement: accepted feature is not unique')
            _feature = _matches[0]
            _properties = _feature.get('properties') or {}
            if (_feature.get('type') != 'Feature' or _properties.get('sourceId') != _source['id']
                    or _properties.get('officialCode') != _accepted['officialCode']
                    or hashlib.sha256(json.dumps(_feature, ensure_ascii=False, sort_keys=True,
                                                 separators=(',', ':')).encode('utf-8')).hexdigest() != _replacement['featureSha256']):
                raise ValueError('lineageCompatibility.acceptedBoundaryReplacement: accepted feature hash or identity changed')
            _provider_decision = _replacement['providerEvidence']
            aggregate_review_file(directory, _provider_decision, 'picker accepted boundary provider decision')
            if ((directory / _provider_decision['file']).resolve()
                    != (Path(reviewed_boundaries).parent / _source['review']['evidenceFile']).resolve()
                    or _provider_decision['sha256'] != _source['review']['evidenceSha256']):
                raise ValueError('lineageCompatibility.acceptedBoundaryReplacement: provider decision is not active')
            _original = original_sources.get(_owner_id)
            _effective = effective_sources.get(_owner_id)
            if (not isinstance(_original, dict) or not isinstance(_effective, dict)
                    or _original.get('shape') is None or _effective.get('shape') is None):
                raise ValueError('lineageCompatibility: actual original/effective owner source is missing')
            if not isinstance(_old_record.get('members'), list) or len(_old_record['members']) != 1:
                raise ValueError('lineageCompatibility: expected a single sector member')
            _native_sha = aggregate_geometry_sha256(shape(_feature['geometry']))
            if _native_sha != aggregate_geometry_sha256(_effective['shape']):
                raise ValueError('lineageCompatibility: effective owner geometry does not match the accepted whole source')
            if _effective['shape'].is_empty or not _effective['shape'].is_valid:
                raise ValueError('lineageCompatibility: invalid accepted owner shape')
            _packed = set_precision(_effective['shape'], 1 / SCALE)
            if _packed.is_empty or not _packed.is_valid:
                raise ValueError('lineageCompatibility: invalid packed owner shape')
            _packed_sha = hashlib.sha256(packed_geometry_bytes(_packed)).hexdigest()
            _derived_tags = {'boundary': 'administrative', 'admin_level': '6', 'ref:tn:codegeo': _accepted['officialCode'],
                             'name:ar': _properties['nameAr'], 'name:fr': _properties['nameFr']}
            if _accepted.get('aliases'):
                _derived_tags['alt_name'] = ';'.join(_accepted['aliases'])
            _old_member = _old_record['members'][0]
            _original_tags = _original.get('tags')
            _original_geometry_sha = (aggregate_geometry_sha256(_original['shape'])
                                      if _original.get('shape') is not None else _original.get('geometrySha256'))
            if (not isinstance(_original_tags, dict) or not isinstance(_original_geometry_sha, str)
                    or _original_tags != _old_member.get('expectedOriginalTags')
                    or _original.get('kind') != 'sector' or _original.get('sourceId') is not None
                    or _old_member.get('id') != _owner_id or _old_member.get('expectedKind') != 'sector'
                    or _old_member.get('expectedSourceId') != 'osm'
                    or _original_geometry_sha != _old_member.get('originalGeometrySha256')
                    or any(_original_tags.get(k) != v for k, v in _accepted['expectedOriginalTags'].items())
                    or _accepted['officialCode'] != _original_tags.get('ref:tn:codegeo')
                    or _effective.get('tags') != _derived_tags or _effective.get('sourceId') != _source['id']
                    or _effective.get('kind') != _original.get('kind')):
                raise ValueError('lineageCompatibility: owner source phases do not match the accepted replacement')
            for _point in ((_old_member.get('expectedLat'), _old_member.get('expectedLng')),
                           (_old_record.get('expectedBase', {}).get('lat'), _old_record.get('expectedBase', {}).get('lng'))):
                if (not all(isinstance(value, (int, float)) and not isinstance(value, bool) for value in _point)
                        or not all(math.isfinite(value) for value in _point)
                        or not _effective['shape'].covers(Point(_point[1], _point[0]))
                        or not _packed.covers(Point(_point[1], _point[0]))):
                    raise ValueError('lineageCompatibility: effective owner geometry does not cover the fixed anchor/base')
            _expected_owner = json.loads(json.dumps(_old_record))
            _expected_owner['members'][0]['expectedSourceId'] = _source['id']
            _expected_owner['members'][0]['originalGeometrySha256'] = _native_sha
            _expected_owner['members'][0]['packedGeometrySha256'] = _packed_sha
            _identity_index = [i for i, row in enumerate(_expected_owner.get('identityEvidence') or [])
                               if isinstance(row, dict) and row.get('method') == 'current_coded_sector'
                               and row.get('id') == _owner_id]
            if len(_identity_index) != 1:
                raise ValueError('lineageCompatibility: expected exactly one current_coded_sector identity')
            _expected_owner['identityEvidence'][_identity_index[0]]['effectiveOriginalGeometrySha256'] = _native_sha
            _parent_method = 'reviewed_official_boundary_parent_membership'
            _parent_rows = [row for row in _new_record.get('identityEvidence') or []
                            if isinstance(row, dict) and row.get('method') == _parent_method]
            _parent_ref = None
            if _parent_rows:
                if len(_parent_rows) != 1:
                    raise ValueError('lineageCompatibility: expected one reviewed official parent membership')
                _parent_ref = validate_reviewed_official_boundary_parent_membership(
                    _parent_rows[0].get('parentMembershipReview'), directory, compat, _boundary_manifest,
                    _feature, original_sources, effective_sources, source_sha256)
                _parent_indices = [i for i, row in enumerate(_expected_owner.get('identityEvidence') or [])
                                   if isinstance(row, dict)
                                   and row.get('method') == 'current_coded_namesake_administrative_parent'
                                   and row.get('id') == _parent_rows[0]['id']]
                if len(_parent_indices) != 1:
                    raise ValueError('lineageCompatibility: exact previous parent identity missing')
                _expected_owner['identityEvidence'][_parent_indices[0]]['method'] = _parent_method
                _expected_owner['identityEvidence'][_parent_indices[0]]['parentMembershipReview'] = _parent_ref
            if (_new_record != _expected_owner
                    or json.dumps(_new_record, ensure_ascii=False, separators=(',', ':'), sort_keys=False)
                    != json.dumps(_expected_owner, ensure_ascii=False, separators=(',', ':'), sort_keys=False)):
                raise ValueError('lineageCompatibility: active owner record changes more than the four allowlisted fields')
            _prev_proof_ref = compat['previousProofCollection']
            _succ_proof_ref = compat['successorProofCollection']
            for _proof_ref, _proof_label in ((_prev_proof_ref, 'previousProofCollection'), (_succ_proof_ref, 'successorProofCollection')):
                _exact_keys(_proof_ref, ('file', 'sha256'), 'lineageCompatibility.' + _proof_label)
                _sha(_proof_ref['sha256'], 'lineageCompatibility.' + _proof_label + '.sha256')
            if (_prev_proof_ref == _succ_proof_ref or _prev_proof_ref['file'] == _succ_proof_ref['file']
                    or _prev_proof_ref['sha256'] == _succ_proof_ref['sha256']):
                raise ValueError('lineageCompatibility: proof collection references must be unique')
            _prev_proof_path, _prev_proof_raw, _previous_proof = _load_ref(_prev_proof_ref, 'lineageCompatibility.previousProofCollection')
            _succ_proof_path, _succ_proof_raw, _successor_proof = _load_ref(_succ_proof_ref, 'lineageCompatibility.successorProofCollection')
            if (_prev_proof_path == _succ_proof_path or not isinstance(_previous_proof, dict)
                    or not isinstance(_successor_proof, dict)):
                raise ValueError('lineageCompatibility: proof collections must be distinct objects')
            _prev_proof_rows = _previous_proof.get('records')
            _succ_proof_rows = _successor_proof.get('records')
            if not isinstance(_prev_proof_rows, list) or not isinstance(_succ_proof_rows, list):
                raise ValueError('lineageCompatibility: proof collections need a records list')
            _proof_index = [i for i, row in enumerate(_prev_proof_rows) if isinstance(row, dict) and row.get('id') == _owner_id]
            if (len(_proof_index) != 1
                    or [i for i, row in enumerate(_succ_proof_rows) if isinstance(row, dict) and row.get('id') == _owner_id] != _proof_index):
                raise ValueError('lineageCompatibility: eligible owner proof must occur once at the same index')
            _old_proof = _prev_proof_rows[_proof_index[0]]
            _new_proof = _succ_proof_rows[_proof_index[0]]
            _expected_proof = json.loads(json.dumps(_previous_proof))
            _expected_proof['records'][_proof_index[0]] = _new_proof
            if (_expected_proof != _successor_proof
                    or json.dumps(_expected_proof, ensure_ascii=False, separators=(',', ':'), sort_keys=False)
                    != json.dumps(_successor_proof, ensure_ascii=False, separators=(',', ':'), sort_keys=False)):
                raise ValueError('lineageCompatibility: successor proof collection changes more than the eligible owner proof')
            if (_old_proof.get('status') != 'eligible_proposal'
                    or not isinstance(_old_proof.get('members'), list) or len(_old_proof['members']) != 1
                    or _old_proof['members'][0].get('id') != _owner_id):
                raise ValueError('lineageCompatibility: owner proof is not the exact eligible sector')
            _expected_owner_proof = json.loads(json.dumps(_old_proof))
            _expected_owner_proof['members'][0]['officialSource'] = {
                'sourceId': _source['id'], 'officialCode': _accepted['officialCode'],
                'geometrySha256': _native_sha, 'packedGeometrySha256': _packed_sha}
            _expected_owner_proof['proposedBehavior'] = (
                'Existing identity and picker membership retained; effective sector boundary replaced by '
                'the explicitly accepted official whole-imada source. Historical evidence and original '
                'OSM geometry are preserved separately.')
            if _parent_ref is not None:
                _proof_parent_indices = [i for i, row in enumerate(_expected_owner_proof.get('identityEvidence') or [])
                                         if isinstance(row, dict)
                                         and row.get('method') == 'current_coded_namesake_administrative_parent'
                                         and row.get('id') == _parent_rows[0]['id']]
                if len(_proof_parent_indices) != 1:
                    raise ValueError('lineageCompatibility: exact previous proof parent identity missing')
                _expected_owner_proof['identityEvidence'][_proof_parent_indices[0]]['method'] = _parent_method
                _expected_owner_proof['identityEvidence'][_proof_parent_indices[0]]['parentMembershipReview'] = _parent_ref
                _expected_owner_proof['proposedBehavior'] = 'Reviewed official whole-imada source is the effective sector boundary. The legacy coded delegation parent geometry does not cover that effective whole sector, so administrative parent membership is established by reviewed_official_boundary_parent_membership instead of legacy containment. Historical identity and original OSM geometry are preserved.'
            if (_new_proof != _expected_owner_proof
                    or json.dumps(_new_proof, separators=(',', ':'), sort_keys=False)
                       != json.dumps(_expected_owner_proof, separators=(',', ':'), sort_keys=False)):
                raise ValueError('lineageCompatibility: owner proof differs outside the exact two-path successor')
            _evidence_refs = []
            if isinstance(active_json.get('reviewEvidence'), dict):
                _evidence_refs.append(active_json['reviewEvidence'])
            _additional_evidence = active_json.get('additionalReviewEvidence')
            if _additional_evidence is not None:
                if (not isinstance(_additional_evidence, list)
                        or any(not isinstance(item, dict) for item in _additional_evidence)):
                    raise ValueError('active picker manifest.additionalReviewEvidence: expected references')
                _evidence_refs.extend(_additional_evidence)
            _eligible_ids = []
            for _evidence_ref in _evidence_refs:
                _evidence_doc = json.loads(aggregate_review_file(directory, _evidence_ref, 'picker review evidence'))
                _evidence_rows = _evidence_doc.get('records') if isinstance(_evidence_doc, dict) else None
                if not isinstance(_evidence_rows, list):
                    raise ValueError('picker review evidence: expected a records list')
                for _evidence_row in _evidence_rows:
                    if isinstance(_evidence_row, dict) and _evidence_row.get('status') == 'eligible_proposal':
                        _eligible_ids.append(_str(_evidence_row.get('id'), 'picker review evidence.records[].id', nonempty=True))
            if len(_eligible_ids) != len(set(_eligible_ids)):
                raise ValueError('active picker review collections contain a duplicate eligible id')
            _expected_active = json.loads(json.dumps(_previous_json))
            _expected_active['records'][_old_index[0]] = _new_record
            _replaced_refs = 0
            if _expected_active.get('reviewEvidence') == _prev_proof_ref:
                _expected_active['reviewEvidence'] = _succ_proof_ref
                _replaced_refs += 1
            _expected_additional = _expected_active.get('additionalReviewEvidence')
            if isinstance(_expected_additional, list):
                _expected_active['additionalReviewEvidence'] = [
                    _succ_proof_ref if item == _prev_proof_ref else item for item in _expected_additional]
                _replaced_refs += sum(1 for item in _expected_additional if item == _prev_proof_ref)
            if _replaced_refs != 1:
                raise ValueError('lineageCompatibility.previousProofCollection: must be replaced exactly once')
            _expected_active['lineageCompatibility'] = compat_ref
            if (_expected_active != active_json
                    or json.dumps(_expected_active, ensure_ascii=False, separators=(',', ':'), sort_keys=False)
                    != json.dumps(active_json, ensure_ascii=False, separators=(',', ':'), sort_keys=False)):
                raise ValueError('active picker manifest: does not match the strict boundary successor reconstruction')
            _previous_reference = {'file': _prev_ref['file'],
                                   'sha256': _prev_ref['sha256'] if active_sha == reference['sha256'] else reference['sha256']}
            load_reviewed_picker_manifest_for_lineage(
                directory, _previous_reference, _prev_path, _previous_json, source_sha256,
                reviewed_boundaries=reviewed_boundaries, official_report=official_report,
                original_sources=original_sources, effective_sources=effective_sources,
                _lineage_depth=_lineage_depth + 1, _lineage_seen=_seen)
            return active_raw
        return _strict_boundary_successor_branch()
    if isinstance(compat, dict) and compat.get('method') == 'reviewed_picker_local_records_append':
        _exact_keys(
            compat,
            ('schemaVersion', 'method', 'sourceSha256', 'previousManifest', 'appendedLocalRecords', 'appendedLocalReviewEvidence', 'qualification'),
            'active picker manifest.lineageCompatibility (local records append)',
        )
        if _int(compat['schemaVersion'], 'lineageCompatibility.schemaVersion') != 1:
            raise ValueError('lineageCompatibility.schemaVersion: expected 1')
        if compat['sourceSha256'] != source_sha256:
            raise ValueError('lineageCompatibility.sourceSha256: does not match source_sha256')
        _str(compat['qualification'], 'lineageCompatibility.qualification', nonempty=True)

        append_prev_ref = compat['previousManifest']
        _exact_keys(append_prev_ref, ('file', 'sha256'), 'lineageCompatibility.previousManifest')
        _sha(append_prev_ref['sha256'], 'lineageCompatibility.previousManifest.sha256')
        if append_prev_ref['sha256'] != reference['sha256']:
            raise ValueError('lineageCompatibility.previousManifest.sha256: does not match reference.sha256')
        append_prev_path, _append_prev_raw, append_previous_json = _load_ref(append_prev_ref, 'lineageCompatibility.previousManifest')
        if append_prev_path == active_path:
            raise ValueError('lineageCompatibility.previousManifest: path must differ from active path')
        if not isinstance(append_previous_json, dict):
            raise ValueError('previous manifest: expected object')
        if _int(append_previous_json.get('schemaVersion'), 'previous manifest.schemaVersion') != 1:
            raise ValueError('previous manifest.schemaVersion: expected 1')
        if append_previous_json.get('sourceSha256') != source_sha256:
            raise ValueError('previous manifest.sourceSha256: does not match source_sha256')
        if 'lineageCompatibility' in append_previous_json:
            raise ValueError('previous manifest: lineageCompatibility must be absent')

        old_local_records = append_previous_json.get('localRecords')
        if not isinstance(old_local_records, list) or any(not isinstance(row, dict) for row in old_local_records):
            raise ValueError('previous manifest.localRecords: expected a list of objects')
        old_local_evidence = append_previous_json.get('additionalLocalReviewEvidence')
        if not isinstance(old_local_evidence, list) or any(not isinstance(item, dict) for item in old_local_evidence):
            raise ValueError('previous manifest.additionalLocalReviewEvidence: expected a list of objects')

        appended_records = compat['appendedLocalRecords']
        if not isinstance(appended_records, list) or len(appended_records) != 2:
            raise ValueError('lineageCompatibility.appendedLocalRecords: expected exactly two records')
        if any(not isinstance(record, dict) for record in appended_records):
            raise ValueError('lineageCompatibility.appendedLocalRecords: expected object entries')
        appended_ids = set()
        for record in appended_records:
            record_id = _str(record.get('id'), 'lineageCompatibility.appendedLocalRecords[].id', nonempty=True)
            if record_id in appended_ids:
                raise ValueError('lineageCompatibility.appendedLocalRecords: duplicate id')
            appended_ids.add(record_id)
            if any(old_local_record.get('id') == record_id for old_local_record in old_local_records):
                raise ValueError('lineageCompatibility.appendedLocalRecords: id already present in previous manifest.localRecords')
            if record.get('status') != 'eligible_proposal':
                raise ValueError('lineageCompatibility.appendedLocalRecords[].status: unexpected value')
            if record.get('method') != 'reviewed_named_neighbourhood_residential_display_identity':
                raise ValueError('lineageCompatibility.appendedLocalRecords[].method: unexpected value')

        appended_evidence_refs = compat['appendedLocalReviewEvidence']
        if not isinstance(appended_evidence_refs, list) or len(appended_evidence_refs) != 1:
            raise ValueError('lineageCompatibility.appendedLocalReviewEvidence: expected exactly one reference')
        append_evidence_ref = appended_evidence_refs[0]
        _exact_keys(append_evidence_ref, ('file', 'sha256'), 'lineageCompatibility.appendedLocalReviewEvidence[0]')
        _sha(append_evidence_ref['sha256'], 'lineageCompatibility.appendedLocalReviewEvidence[0].sha256')
        append_evidence_path = _resolve(append_evidence_ref['file'], 'lineageCompatibility.appendedLocalReviewEvidence[0]')
        for old_item in old_local_evidence:
            if old_item == append_evidence_ref or old_item.get('file') == append_evidence_ref['file']:
                raise ValueError('lineageCompatibility.appendedLocalReviewEvidence: reference or path already present')
            old_file = old_item.get('file')
            if isinstance(old_file, str):
                old_path = _resolve(old_file, 'previous manifest.additionalLocalReviewEvidence[]')
                if old_path == append_evidence_path:
                    raise ValueError('lineageCompatibility.appendedLocalReviewEvidence: reference or path already present')
        _append_evidence_path, _append_evidence_raw, append_evidence_json = _load_ref(append_evidence_ref, 'lineageCompatibility.appendedLocalReviewEvidence[0]')
        if not isinstance(append_evidence_json, dict):
            raise ValueError('lineageCompatibility.appendedLocalReviewEvidence: expected object')
        _exact_keys(
            append_evidence_json,
            ('schemaVersion', 'sourceSha256', 'sourceFacts', 'researchReview', 'records'),
            'lineageCompatibility.appendedLocalReviewEvidence',
        )
        if _int(append_evidence_json['schemaVersion'], 'lineageCompatibility.appendedLocalReviewEvidence.schemaVersion') != 1:
            raise ValueError('lineageCompatibility.appendedLocalReviewEvidence.schemaVersion: expected 1')
        if append_evidence_json['sourceSha256'] != source_sha256:
            raise ValueError('lineageCompatibility.appendedLocalReviewEvidence.sourceSha256: does not match source_sha256')
        if append_evidence_json['records'] != appended_records:
            raise ValueError('lineageCompatibility.appendedLocalReviewEvidence.records: does not match appendedLocalRecords')
        if json.dumps(append_evidence_json['records'], separators=(',', ':'), sort_keys=False) != json.dumps(appended_records, separators=(',', ':'), sort_keys=False):
            raise ValueError('lineageCompatibility.appendedLocalReviewEvidence.records: JSON order or types differ')

        source_facts_ref = append_evidence_json['sourceFacts']
        research_review_ref = append_evidence_json['researchReview']
        _source_facts_path, _source_facts_raw, source_facts_json = _load_ref(source_facts_ref, 'lineageCompatibility.appendedLocalReviewEvidence.sourceFacts')
        _research_review_path, _research_review_raw, research_review_json = _load_ref(research_review_ref, 'lineageCompatibility.appendedLocalReviewEvidence.researchReview')
        if not isinstance(source_facts_json, dict):
            raise ValueError('lineageCompatibility.appendedLocalReviewEvidence.sourceFacts: expected object')
        if not isinstance(research_review_json, dict):
            raise ValueError('lineageCompatibility.appendedLocalReviewEvidence.researchReview: expected object')
        if source_facts_json.get('sourceSha256') != source_sha256:
            raise ValueError('lineageCompatibility.appendedLocalReviewEvidence.sourceFacts.sourceSha256: does not match source_sha256')
        if research_review_json.get('sourceSha256') != source_sha256:
            raise ValueError('lineageCompatibility.appendedLocalReviewEvidence.researchReview.sourceSha256: does not match source_sha256')

        append_expected_active = json.loads(json.dumps(append_previous_json))
        append_expected_records = append_expected_active.get('localRecords')
        if not isinstance(append_expected_records, list):
            raise ValueError('previous manifest.localRecords: expected a list during reconstruction')
        append_expected_records.extend(appended_records)
        append_expected_evidence = append_expected_active.get('additionalLocalReviewEvidence')
        if not isinstance(append_expected_evidence, list):
            raise ValueError('previous manifest.additionalLocalReviewEvidence: expected a list during reconstruction')
        append_expected_evidence.append(append_evidence_ref)
        append_expected_active['lineageCompatibility'] = compat_ref
        if append_expected_active != active_json:
            raise ValueError('active picker manifest: does not match local records append reconstruction')
        if json.dumps(append_expected_active, separators=(',', ':'), sort_keys=False) != json.dumps(active_json, separators=(',', ':'), sort_keys=False):
            raise ValueError('active picker manifest: serialized order or types differ from local records append reconstruction')
        return active_raw
    _exact_keys(
        compat,
        ('schemaVersion', 'method', 'sourceSha256', 'previousManifest', 'changes', 'qualification'),
        'active picker manifest.lineageCompatibility',
    )
    if _int(compat['schemaVersion'], 'lineageCompatibility.schemaVersion') != 1:
        raise ValueError('lineageCompatibility.schemaVersion: expected 1')
    if compat['method'] != COMPAT_METHOD:
        raise ValueError('lineageCompatibility.method: unexpected value')
    if compat['sourceSha256'] != source_sha256:
        raise ValueError('lineageCompatibility.sourceSha256: does not match source_sha256')
    _str(compat['qualification'], 'lineageCompatibility.qualification', nonempty=True)

    prev_ref = compat['previousManifest']
    _exact_keys(prev_ref, ('file', 'sha256'), 'lineageCompatibility.previousManifest')
    _sha(prev_ref['sha256'], 'lineageCompatibility.previousManifest.sha256')
    if prev_ref['sha256'] != reference['sha256']:
        raise ValueError('lineageCompatibility.previousManifest.sha256: does not match reference.sha256')
    prev_path, _prev_raw, previous_json = _load_ref(prev_ref, 'lineageCompatibility.previousManifest')
    if prev_path == active_path:
        raise ValueError('lineageCompatibility.previousManifest: path must differ from active path')
    if not isinstance(previous_json, dict):
        raise ValueError('previous manifest: expected object')
    if _int(previous_json.get('schemaVersion'), 'previous manifest.schemaVersion') != 1:
        raise ValueError('previous manifest.schemaVersion: expected 1')
    if previous_json.get('sourceSha256') != source_sha256:
        raise ValueError('previous manifest.sourceSha256: does not match source_sha256')
    if 'lineageCompatibility' in previous_json:
        raise ValueError('previous manifest: lineageCompatibility must be absent')

    changes = compat['changes']
    if not isinstance(changes, list) or len(changes) != 1:
        raise ValueError('lineageCompatibility.changes: expected exactly one change')
    change = changes[0]
    _exact_keys(
        change,
        ('section', 'id', 'previousRecord', 'activeRecord', 'historicalProof', 'successorProof'),
        'lineageCompatibility.changes[0]',
    )
    if change['section'] != SECTION:
        raise ValueError('lineageCompatibility.changes[0].section: unexpected value')
    change_id = _str(change['id'], 'lineageCompatibility.changes[0].id', nonempty=True)

    old_rows = _section_rows(previous_json, 'previous manifest')
    new_rows = _section_rows(active_json, 'active picker manifest')
    old_matches = [row for row in old_rows if isinstance(row, dict) and row.get('id') == change_id]
    new_matches = [row for row in new_rows if isinstance(row, dict) and row.get('id') == change_id]
    if len(old_matches) != 1:
        raise ValueError('previous manifest: expected exactly one matching row id')
    if len(new_matches) != 1:
        raise ValueError('active picker manifest: expected exactly one matching row id')
    old_record = old_matches[0]
    new_record = new_matches[0]
    if old_record != change['previousRecord']:
        raise ValueError('lineageCompatibility.changes[0].previousRecord: does not match previous manifest row')
    if new_record != change['activeRecord']:
        raise ValueError('lineageCompatibility.changes[0].activeRecord: does not match active picker manifest row')
    if set(old_record) != set(new_record):
        raise ValueError('previous/active records: expected identical key sets')
    for key in old_record:
        if key != 'reviewEvidence' and old_record[key] != new_record[key]:
            raise ValueError(f'previous/active records: field {key!r} differs')
    if old_record.get('reviewEvidence') == new_record.get('reviewEvidence'):
        raise ValueError('previous/active records: reviewEvidence must differ')
    historical_ref = change['historicalProof']
    successor_ref = change['successorProof']
    if old_record.get('reviewEvidence') != historical_ref:
        raise ValueError('previous record.reviewEvidence: does not match historicalProof')
    if new_record.get('reviewEvidence') != successor_ref:
        raise ValueError('active record.reviewEvidence: does not match successorProof')
    if old_record.get('method') != METHOD or new_record.get('method') != METHOD:
        raise ValueError('previous/active records.method: unexpected value')
    if old_record.get('id') != change_id or new_record.get('id') != change_id:
        raise ValueError('previous/active records.id: does not match change id')
    target = _int(old_record.get('targetDelegationId'), 'previous record.targetDelegationId')
    new_target = _int(new_record.get('targetDelegationId'), 'active record.targetDelegationId')
    if new_target != target:
        raise ValueError('previous/active records.targetDelegationId: must be unchanged')
    expected_application = {'method': METHOD, 'id': change_id, 'targetDelegationId': target}

    _hist_path, _hist_raw, hist_json = _load_ref(historical_ref, 'historicalProof')
    _succ_path, _succ_raw, succ_json = _load_ref(successor_ref, 'successorProof')
    if not isinstance(hist_json, dict):
        raise ValueError('historicalProof: expected object')
    if not isinstance(succ_json, dict):
        raise ValueError('successorProof: expected object')

    def _check_proof(doc, label):
        if _int(doc.get('schemaVersion'), f'{label}.schemaVersion') != 2:
            raise ValueError(f'{label}.schemaVersion: expected 2')
        if doc.get('sourceSha256') != source_sha256:
            raise ValueError(f'{label}.sourceSha256: does not match source_sha256')
        app = doc.get('application')
        _exact_keys(app, ('method', 'id', 'targetDelegationId'), f'{label}.application')
        if app != expected_application:
            raise ValueError(f'{label}.application: unexpected value')
        if doc.get('conclusion') != METHOD:
            raise ValueError(f'{label}.conclusion: unexpected value')
        for key in ('officialRegistry', 'primarySources', 'sourceFacts'):
            if key not in doc:
                raise ValueError(f'{label}.{key}: missing')

    _check_proof(hist_json, 'historicalProof')
    _check_proof(succ_json, 'successorProof')
    if hist_json['officialRegistry'] != succ_json['officialRegistry']:
        raise ValueError('historical/successor proofs.officialRegistry: must be unchanged')
    if hist_json['primarySources'] != succ_json['primarySources']:
        raise ValueError('historical/successor proofs.primarySources: must be unchanged')
    if succ_json.get('historicalProof') != historical_ref:
        raise ValueError('successorProof.historicalProof: does not match historicalProof reference')
    if 'sourcePhaseLineage' not in succ_json:
        raise ValueError('successorProof.sourcePhaseLineage: missing')

    lineage_ref = succ_json['sourcePhaseLineage']
    _lineage_path, _lineage_raw, lineage_json = _load_ref(lineage_ref, 'successorProof.sourcePhaseLineage')
    if not isinstance(lineage_json, dict):
        raise ValueError('sourcePhaseLineage: expected object')
    if _int(lineage_json.get('schemaVersion'), 'sourcePhaseLineage.schemaVersion') != 1:
        raise ValueError('sourcePhaseLineage.schemaVersion: expected 1')
    if lineage_json.get('method') != LINEAGE_METHOD:
        raise ValueError('sourcePhaseLineage.method: unexpected value')
    if lineage_json.get('sourceSha256') != source_sha256:
        raise ValueError('sourcePhaseLineage.sourceSha256: does not match source_sha256')
    lineage_app = lineage_json.get('application')
    _exact_keys(lineage_app, ('method', 'id', 'targetDelegationId'), 'sourcePhaseLineage.application')
    if lineage_app != expected_application:
        raise ValueError('sourcePhaseLineage.application: unexpected value')
    for key in ('historicalSourceFacts', 'historicalFullSourceFacts'):
        if key not in lineage_json:
            raise ValueError(f'sourcePhaseLineage.{key}: missing')
    if lineage_json.get('historicalSourceFacts') != succ_json.get('sourceFacts'):
        raise ValueError('sourcePhaseLineage.historicalSourceFacts: does not match successorProof.sourceFacts')
    if lineage_json.get('historicalFullSourceFacts') != hist_json.get('sourceFacts'):
        raise ValueError('sourcePhaseLineage.historicalFullSourceFacts: does not match historicalProof.sourceFacts')

    expected_active = json.loads(json.dumps(previous_json))
    expected_rows = _section_rows(expected_active, 'previous manifest during reconstruction')
    expected_matches = [row for row in expected_rows if isinstance(row, dict) and row.get('id') == change_id]
    if len(expected_matches) != 1:
        raise ValueError('previous manifest: expected exactly one matching row id during reconstruction')
    expected_matches[0]['reviewEvidence'] = successor_ref
    expected_active['lineageCompatibility'] = compat_ref
    if expected_active != active_json:
        raise ValueError('active picker manifest: does not match lineage reconstruction')
    if json.dumps(expected_active, separators=(',', ':'), sort_keys=False) != json.dumps(active_json, separators=(',', ':'), sort_keys=False):
        raise ValueError('active picker manifest: serialized order or types differ from lineage reconstruction')
    return active_raw


def validate_reviewed_town_preservation_extension(directory, review, rule, registry, original_sources,
                                                  effective_sources, curation, source_sha256,
                                                  reviewed_boundaries, official_report, reviewed_picker_groups,
                                                  governor=None):
    """Preserve complete town-adjacent groups and their actual source phases.

    This reads the existing decisions and source objects; it never changes them.
    Geometry membership and reviewed administrative context are separate facts.
    An optional retained town point must already own the base automatically;
    only the reviewed new point joins that complete group.
    """
    extension = review['townPreservationExtension']
    keys = {'schemaVersion', 'officialDelegationCodes', 'sourceStates', 'curationBindings',
            'acceptedBoundaryReplacements', 'reviewedBoundaries', 'sourceNamePeerIds',
            'coordinateContextMembership', 'protectedRawRecords', 'expectedBeforeGroups',
            'expectedAfterGroups', 'priorPickerBindings'}
    has_retained_town = isinstance(extension, dict) and 'retainedTownPoint' in extension
    has_successor = isinstance(extension, dict) and 'administrativeSuccession' in extension
    if has_retained_town:
        keys = keys | {'retainedTownPoint'}
    if has_successor:
        keys = keys | {'administrativeSuccession'}
    if (review['method'] != 'reviewed_explicit_point_base_display_identity'
            or any(key in review for key in ('sourcePhaseLineage', 'typedNamePeerExtension',
                                             'deferredCompleteGroupExtension', 'officialDelegationCode'))
            or not isinstance(extension, dict) or set(extension) != keys
            or (has_successor and (has_retained_town or not isinstance(extension['administrativeSuccession'], dict)))
            or type(extension['schemaVersion']) is not int or extension['schemaVersion'] != 1):
        raise ValueError('Malformed or incompatible reviewed town preservation extension')
    identifier, target = review['pointId'], f"delegation:{review['baseId']}"
    codes = extension['officialDelegationCodes']
    context_codes = [extension['administrativeSuccession'].get('sourceDelegationCode')] if has_successor else codes
    sectors = review['preservedSectors']
    sector_ids = {row['id'] for row in sectors}
    contexts = review['contexts']
    if (not isinstance(codes, list) or not codes or codes != sorted(set(codes))
            or any(not isinstance(code, str) or not re.fullmatch(r'[0-9]{4}', code) for code in codes)
            or len({code[:2] for code in codes}) != 1
            or not sectors or len(sector_ids) != len(sectors)
            or sorted([r for r in registry['sectors'] if r['delegationCode'] in codes],
                      key=lambda r: r['sectorCode'])
                != sorted([r['officialIdentity'] for r in sectors], key=lambda r: r['sectorCode'])
            or len({r['officialIdentity']['sectorCode'] for r in sectors}) != len(sectors)
            or {r['officialIdentity']['delegationCode'] for r in sectors} != set(codes)
            or not isinstance(contexts, dict) or set(contexts) & sector_ids
            or sorted(c['kind'] for c in contexts.values()) != ['delegation'] * len(codes) + ['governorate']
            or sorted(c['tags'].get('ref:tn:codegeo') for c in contexts.values() if c['kind'] == 'delegation') != context_codes):
        raise ValueError('Reviewed town complete plural imada or context inventory changed')
    governor_id = next(i for i, c in contexts.items() if c['kind'] == 'governorate')
    if (contexts[governor_id]['tags'].get('ref:tn:codegeo') != codes[0][:2]
            or contexts[governor_id]['tags'].get('name:ar') != 'ولاية ' + review['expectedGovernor']['nomAr']):
        raise ValueError('Reviewed town shared governorate identity changed')

    rows = extension['protectedRawRecords']
    raw = {row['id']: row for row in rows}
    records = extension['sourceStates']
    states = {row['id']: row for row in records}
    raw_keys = {'id', 'kind', 'currentMetadata', 'packedGeometrySha256'}
    state_keys = {'tags', 'kind', 'sourceId', 'geometrySha256'}
    if (not rows or [r['id'] for r in rows] != sorted(raw)
            or any(set(row) != raw_keys for row in rows)
            or not sector_ids | {identifier} <= set(raw) or set(raw) & set(contexts)
            or [r['id'] for r in records] != sorted(states)
            or set(states) != set(raw) | set(contexts)
            or any(set(row) != {'id', 'original', 'effective'}
                   or any(set(row[phase]) != state_keys for phase in ('original', 'effective')) for row in records)
            or not isinstance(extension['curationBindings'], dict)
            or set(extension['curationBindings']) != set(states) - {identifier}
            or curation.get(identifier) != rule or rule['id'] != identifier
            or raw[identifier]['currentMetadata'] != review['rawCurrent']):
        raise ValueError('Reviewed town typed source or complete raw closure is malformed')
    bindings = extension['curationBindings']
    for sid, expected in bindings.items():
        if (curation.get(sid) != expected
                or (expected is not None and (expected['id'] != sid
                    or expected['action'] not in ('name_tags', 'preserve_point', 'administrative_context')))):
            raise ValueError('Reviewed town existing curation binding changed')
    for phase, sources in (('original', original_sources), ('effective', effective_sources)):
        for sid, record in states.items():
            source = sources[sid]
            geometry = source.get('shape')
            if geometry is None:
                geometry = source.get('point')
            actual = {'tags': source['tags'], 'kind': source['kind'], 'sourceId': source.get('sourceId'),
                      'geometrySha256': aggregate_geometry_sha256(geometry)}
            if (geometry is None or geometry.is_empty or geometry.geom_type not in ('Point', 'Polygon', 'MultiPolygon')
                    or actual != record[phase] or (phase == 'original' and actual['sourceId'] is not None)
                    or record['original']['kind'] != record['effective']['kind']):
                raise ValueError('Reviewed town exact source phase changed')

    replacements = extension['acceptedBoundaryReplacements']
    replaced = {r['id']: r for r in replacements}
    expected_replaced = {sid for sid, r in states.items() if r['effective']['sourceId'] != 'osm'}
    if ([r['id'] for r in replacements] != sorted(replaced)
            or set(replaced) != expected_replaced or not set(replaced) <= sector_ids):
        raise ValueError('Reviewed town official replacement inventory changed')
    reference = extension['reviewedBoundaries']
    boundary_manifest = None
    if replacements:
        boundary_manifest = load_reviewed_boundary_manifest_for_lineage(
            directory, reference, reviewed_boundaries, official_report)
    elif reference is not None:
        raise ValueError('Reviewed town no-replacement lineage must not claim an active replacement manifest')
    for sid, record in states.items():
        original, effective = record['original'], record['effective']
        tags = dict(original['tags'])
        geometry_sha, provider = original['geometrySha256'], 'osm'
        decision = bindings.get(sid)
        if sid in replaced:
            replacement = replaced[sid]
            if set(replacement) != {'id', 'sourceRecord', 'record', 'application', 'geojson', 'featureSha256'}:
                raise ValueError('Malformed reviewed town accepted replacement')
            source, accepted = replacement['sourceRecord'], replacement['record']
            application = {'id': sid, 'action': 'replace', 'officialCode': accepted['officialCode'],
                           'sourceId': source['id']}
            if ([s for s in boundary_manifest['sources'] if s['id'] == source['id']] != [source]
                    or [r for r in source['records'] if r['id'] == sid] != [accepted]
                    or accepted['id'] != sid or accepted['action'] != 'replace'
                    or original['kind'] != 'sector' or effective['kind'] != 'sector'
                    or source['sourceSha256'] != source_sha256 or source['id'] == 'osm' or not source.get('review')
                    or not accepted['expectedOriginalTags']
                    or any(original['tags'].get(k) != v for k, v in accepted['expectedOriginalTags'].items())
                    or accepted['officialCode'] != original['tags'].get('ref:tn:codegeo')
                    or replacement['application'] != application
                    or [r for r in official_report['applications'] if r['id'] == sid] != [application]
                    or official_report['sources'].get(source['id'])
                        != {k: v for k, v in source.items() if k not in ('file', 'records')}
                    or replacement['geojson'] != {'file': source['file'], 'sha256': source['sha256']}
                    or (decision is not None and 'nameTags' in decision)):
                raise ValueError('Reviewed town replacement differs from the accepted loader operation')
            collection = json.loads(aggregate_review_file(Path(reviewed_boundaries).parent,
                                    replacement['geojson'], 'town accepted boundary feature'))
            matches = [f for f in collection['features'] if f['id'] == sid]
            if len(matches) != 1:
                raise ValueError('Reviewed town accepted boundary feature is not unique')
            feature = matches[0]
            properties = feature['properties']
            if (feature['type'] != 'Feature' or properties['sourceId'] != source['id']
                    or properties['officialCode'] != accepted['officialCode']
                    or hashlib.sha256(json.dumps(feature, ensure_ascii=False, sort_keys=True,
                        separators=(',', ':')).encode('utf-8')).hexdigest() != replacement['featureSha256']):
                raise ValueError('Reviewed town accepted boundary feature changed')
            tags = {'boundary': 'administrative', 'admin_level': '6', 'ref:tn:codegeo': accepted['officialCode'],
                    'name:ar': properties['nameAr'], 'name:fr': properties['nameFr']}
            if accepted.get('aliases'):
                tags['alt_name'] = ';'.join(accepted['aliases'])
            geometry_sha = aggregate_geometry_sha256(shape(feature['geometry']))
            provider = source['id']
        if decision is not None and 'nameTags' in decision:
            tags = {k: v for k, v in tags.items() if not is_current_name_tag(k)}
            tags.update(decision['nameTags'])
        if effective != {'tags': tags, 'kind': original['kind'], 'sourceId': provider, 'geometrySha256': geometry_sha}:
            raise ValueError('Reviewed town effective state lacks its exact accepted transformation')

    successor = None
    independent_point_ids = set()
    if has_successor:
        successor = validate_reviewed_administrative_successor_identity(
            directory, review, registry, original_sources, effective_sources, curation, states, raw, bindings, source_sha256)
        independent_point_ids = set(successor['independentPointIds'])
    for sector in sectors:
        sid = sector['id']
        if (states[sid]['original']['kind'] != 'sector'
                or sector['originalTags'] != states[sid]['original']['tags']
                or sector['sourceGeometrySha256'] != states[sid]['original']['geometrySha256']
                or (not has_successor and sector['originalTags'].get('ref:tn:codegeo') != sector['officialIdentity']['sectorCode'])
                or sector['expectedCuration'] != bindings[sid]
                or sector['currentMetadata'] != raw[sid]['currentMetadata']
                or sector['packedGeometrySha256'] != raw[sid]['packedGeometrySha256']):
            raise ValueError('Reviewed town complete imada state bindings disagree')
    for sid, context in contexts.items():
        original = states[sid]['original']
        if (context['kind'] != original['kind'] or context['tags'] != original['tags']
                or context['sourceGeometrySha256'] != original['geometrySha256']):
            raise ValueError('Reviewed town read-only context state bindings disagree')
    for sid, row in raw.items():
        metadata = row['currentMetadata']
        source = effective_sources[sid]
        is_polygon = source.get('shape') is not None
        if (row['id'] != sid or metadata['id'] != sid or row['kind'] != metadata['kind']
                or row['kind'] != source['kind'] or metadata['sourceId'] != source.get('sourceId')
                or metadata['governorateId'] != review['expectedGovernor']['id']
                or type(metadata['hasBoundary']) is not bool or metadata['hasBoundary'] != is_polygon
                or (is_polygon and (source['shape'].geom_type not in ('Polygon', 'MultiPolygon')
                    or not isinstance(row['packedGeometrySha256'], str)
                    or not re.fullmatch(r'[0-9a-f]{64}', row['packedGeometrySha256'])))
                or (not is_polygon and (source['point'].geom_type != 'Point'
                    or row['packedGeometrySha256'] is not None or 'offset' in metadata or 'length' in metadata
                    or source['point'].coords[:] != [(metadata['lng'], metadata['lat'])]))):
            raise ValueError('Reviewed town typed raw source representation changed')

    retained_town_id = None
    if has_retained_town:
        retained = extension['retainedTownPoint']
        if (not isinstance(retained, dict) or set(retained) != {'id', 'sourceNode', 'sourceNodeIdentity'}
                or not isinstance(retained['id'], str) or not re.fullmatch(r'osm:node:[0-9]+', retained['id'])
                or retained['id'] == identifier or retained['id'] not in raw):
            raise ValueError('Malformed reviewed retained town point')
        retained_town_id = retained['id']
        owner = raw[retained_town_id]['currentMetadata']
        if (owner['kind'] != 'town' or owner['hasBoundary'] is not False
                or owner['pickerGroupId'] != target or owner['delegationId'] != review['baseId']
                or bindings.get(retained_town_id) is not None or curation.get(retained_town_id) is not None
                or {sid for sid, row in raw.items() if row['currentMetadata']['hasBoundary'] is False}
                    != {identifier, retained_town_id}
                or not isinstance(governor, dict)
                or {k: v for k, v in governor.items() if k != 'delegations'} != review['expectedGovernor']
                or [base for base in governor['delegations'] if base['id'] == review['baseId']]
                    != [review['baseCurrent']]):
            raise ValueError('Reviewed retained town ownership or complete point inventory changed')
        primary = norm(review['rawCurrent']['name'])
        if (not primary or norm(owner['name']) != primary
                or norm(review['baseCurrent']['nomAr']) != primary):
            raise ValueError('Reviewed retained town lacks the same primary settlement identity')
        reference, identity = retained['sourceNode'], retained['sourceNodeIdentity']
        if (not isinstance(reference, dict) or set(reference) != {'file', 'sha256', 'url'}
                or not isinstance(identity, dict) or set(identity) != {'url', 'sha256', 'exactElement'}):
            raise ValueError('Malformed reviewed retained town source snapshot')
        snapshot = json.loads(aggregate_review_file(directory, reference, 'retained town exact source node'))['elements']
        element = identity['exactElement']
        if (not isinstance(element, dict) or snapshot != [element]
                or element['type'] != 'node' or type(element['id']) is not int or element['id'] <= 0
                or f"osm:node:{element['id']}" != retained_town_id
                or element['tags'] != states[retained_town_id]['original']['tags']
                or element['lat'] != owner['lat'] or element['lon'] != owner['lng']
                or type(element['version']) is not int or element['version'] <= 0
                or not isinstance(element['timestamp'], str) or not element['timestamp']
                or type(element['changeset']) is not int or element['changeset'] <= 0
                or reference['sha256'] != identity['sha256'] or reference['url'] != identity['url']
                or reference['url'] != f"https://api.openstreetmap.org/api/0.6/node/{element['id']}.json"):
            raise ValueError('Reviewed retained town authentic source node changed')
        for sources in (original_sources, effective_sources):
            source = sources[retained_town_id]
            point = source.get('point')
            if (source['kind'] != 'town' or source.get('shape') is not None or point is None
                    or point.geom_type != 'Point' or point.coords[:] != [(owner['lng'], owner['lat'])]
                    or source['tags'] != element['tags'] or norm(names(source['tags'])[0]) != primary):
                raise ValueError('Reviewed retained town original/effective identity changed')
            actual_delegations = [(sid, area) for sid, area in sources.items() if area['kind'] == 'delegation'
                                  and area.get('shape') is not None and area['shape'].covers(point)]
            if (not actual_delegations or any(sid not in contexts for sid, area in actual_delegations)
                    or matching_base_delegation(owner, point, [area for sid, area in actual_delegations], governor) != target):
                raise ValueError('Reviewed retained town automatic base ownership changed')

    peer_keys = {norm(v) for v in [review['rawCurrent']['name'], *review['rawCurrent']['aliases'],
                 review['baseCurrent']['nomAr'], review['baseCurrent']['nomFr'], review['baseCurrent']['nomEn']]}
    peer_ids = extension['sourceNamePeerIds']
    if not isinstance(peer_ids, dict) or set(peer_ids) != {'original', 'effective'}:
        raise ValueError('Malformed reviewed town name-peer phases')
    for phase, sources in (('original', original_sources), ('effective', effective_sources)):
        ids = peer_ids[phase]
        if (ids != sorted(set(ids)) or identifier not in ids or not set(ids) <= set(states)
                or sorted(sid for sid, source in sources.items()
                          if peer_keys & {norm(v) for v in names(source['tags'])}) != ids):
            raise ValueError('Reviewed town complete source-name peer inventory changed')
    original_peers = [{'id': sid, 'tags': states[sid]['original']['tags'],
                       'kind': states[sid]['original']['kind']} for sid in peer_ids['original']]
    if sorted(review['sourceNamePeers'], key=lambda r: r['id']) != original_peers:
        raise ValueError('Reviewed town original name peers disagree')

    membership = extension['coordinateContextMembership']
    if not isinstance(membership, dict) or set(membership) != sector_ids | set(contexts):
        raise ValueError('Reviewed town coordinate/context inventory is incomplete')
    coordinates = {'point': Point(review['rawCurrent']['lng'], review['rawCurrent']['lat']),
                   'base': Point(review['baseCurrent']['lng'], review['baseCurrent']['lat'])}
    if has_retained_town:
        coordinates['retainedTown'] = Point(owner['lng'], owner['lat'])
    delegation_ids = {sid for sid, context in contexts.items() if context['kind'] == 'delegation'}
    for sid, expected in membership.items():
        if not isinstance(expected, dict) or set(expected) != {'original', 'effective'}:
            raise ValueError('Malformed reviewed town membership phase')
        for phase, sources in (('original', original_sources), ('effective', effective_sources)):
            geometry = sources[sid].get('shape')
            if (geometry is None or geometry.geom_type not in ('Polygon', 'MultiPolygon')
                    or set(expected[phase]) != set(coordinates)):
                raise ValueError('Reviewed town administrative context is not a polygon')
            for role, point in coordinates.items():
                value = expected[phase][role]
                if (not isinstance(value, dict) or set(value) != {'contains', 'covers'}
                        or any(type(v) is not bool for v in value.values())
                        or value != {'contains': geometry.contains(point), 'covers': geometry.covers(point)}):
                    raise ValueError('Reviewed town coordinate context membership changed')
    for phase in ('original', 'effective'):
        for role in coordinates:
            if (membership[governor_id][phase][role]['contains'] is not True
                    or not any(membership[sid][phase][role]['contains'] for sid in delegation_ids)):
                raise ValueError('Reviewed town lacks its positive geometric governorate/delegation context')

    before, after = extension['expectedBeforeGroups'], extension['expectedAfterGroups']
    if not isinstance(before, dict) or not isinstance(after, dict):
        raise ValueError('Malformed reviewed town complete group maps')
    members = [sid for values in before.values() for sid in values]
    if has_retained_town:
        expected_after = {group: values for group, values in before.items() if group != identifier}
        expected_after[target] = sorted([identifier, retained_town_id])
        if (len(members) != len(raw) or set(members) != set(raw)
                or set(before) != sector_ids | {identifier, target}
                or before.get(identifier) != [identifier] or before.get(target) != [retained_town_id]
                or any(not isinstance(values, list) or not values or values != sorted(set(values))
                       or (group != target and group not in values)
                       or any(raw[sid]['currentMetadata']['pickerGroupId'] != group for sid in values)
                       or (group not in (identifier, target) and set(values) & sector_ids != {group})
                       for group, values in before.items())
                or after != expected_after or review['completePickerGroups'] != {'before': before, 'after': after}):
            raise ValueError('Reviewed retained town complete group transition changed')
    elif (len(members) != len(raw) or set(members) != set(raw)
            or set(before) != sector_ids | {identifier} | independent_point_ids or before.get(identifier) != [identifier]
            or target in before
            or any(not isinstance(values, list) or not values or values != sorted(set(values))
                   or group not in values or any(raw[sid]['currentMetadata']['pickerGroupId'] != group for sid in values)
                   or (group != identifier and group not in independent_point_ids and set(values) & sector_ids != {group})
                   for group, values in before.items())
            or after != {**{group: values for group, values in before.items() if group != identifier}, target: [identifier]}):
        raise ValueError('Reviewed town group closure or single point transition changed')

    prior = extension['priorPickerBindings']
    if not isinstance(prior, dict) or set(prior) != {'manifest', 'records'}:
        raise ValueError('Malformed reviewed town prior picker bindings')
    picker_path = Path(reviewed_picker_groups)
    picker = json.loads(picker_path.read_bytes())
    if picker.get('sourceSha256') != source_sha256 or picker.get('schemaVersion') != 1:
        raise ValueError('Reviewed town active prior picker source changed')
    touching = []
    prior_ids = set(states)
    if has_successor:
        prior_ids.update(successor['finalDescriptor']['sourceOnlyAbsentIds'])
    quoted_ids = [json.dumps(sid) for sid in sorted(prior_ids)]
    for section in ('records', 'localRecords', 'distinctPairs', 'cityDisplayAssociations', 'residentialDisplayAssociations'):
        for record in picker.get(section, []):
            if (record.get('targetDelegationId') == review['baseId'] or record.get('targetPickerGroupId') == target):
                raise ValueError('Reviewed town base already has an active prior picker owner')
            serialized = json.dumps(record, ensure_ascii=False, sort_keys=True)
            if any(sid in serialized for sid in quoted_ids):
                touching.append({'section': section, 'id': record['id'], 'record': record})
    touching.sort(key=lambda r: (r['section'], r['id']))
    if prior['records'] != touching:
        raise ValueError('Reviewed town touching prior picker inventory changed')
    if touching:
        reference = prior['manifest']
        pinned = load_reviewed_picker_manifest_for_lineage(
            directory, reference, picker_path, picker, source_sha256,
            reviewed_boundaries=reviewed_boundaries, official_report=official_report,
            original_sources=original_sources, effective_sources=effective_sources)
        if (directory / reference['file']).resolve() != picker_path.resolve() or json.loads(pinned) != picker:
            raise ValueError('Reviewed town prior picker pin does not bind the active manifest')
    elif prior['manifest'] is not None:
        raise ValueError('Reviewed town empty prior picker inventory has an unexpected manifest claim')
    preserved_extension = {'protectedRawRecords': rows, 'expectedAfterGroups': after,
                           'readOnlyContextIds': sorted(contexts)}
    if has_retained_town:
        preserved_extension['retainedTownPointId'] = retained_town_id
    if has_successor:
        preserved_extension['administrativeSuccession'] = successor['finalDescriptor']
    return {'exactPair': {'basePickerId': target, 'rawCurrent': review['rawCurrent']},
            'preservedSectors': [],
            'townPreservationExtension': preserved_extension}


def verify_reviewed_town_preservation(proposal, features, geometries, base_groups):
    """Read-only final fields, packed shapes, complete groups and ownership."""
    extension = proposal['townPreservationExtension']
    pair = proposal['exactPair']
    identifier, target = pair['rawCurrent']['id'], pair['basePickerId']
    indices = {feature['id']: index for index, feature in enumerate(features)}
    if len(indices) != len(features):
        raise ValueError('Reviewed town final catalog repeats a raw ID')
    groups = defaultdict(set)
    for feature in features:
        groups[feature['pickerGroupId']].add(feature['id'])
    for group, members in extension['expectedAfterGroups'].items():
        if groups.get(group) != set(members):
            raise ValueError('Reviewed town final complete raw group changed')
    protected = {row['id'] for row in extension['protectedRawRecords']}
    if 'administrativeSuccession' in extension:
        successor = extension['administrativeSuccession']
        observed = sorted(row['id'] for row in features if row.get('governorateId') == successor['governorateId']
                          and (row.get('parentName') == successor['administrativeParentName']
                               or successor['administrativeParentName'] in row.get('contextAliases', [])))
        if observed != successor['expectedRawIds'] or set(observed) != protected:
            raise ValueError('Reviewed successor final complete administrative raw-context closure changed')
        for sid in successor['sourceOnlyAbsentIds']:
            if sid in indices or groups.get(sid) or sid in base_groups or sid in base_groups.values():
                raise ValueError('Reviewed successor absorbed source-only point gained a raw row or owner')
    expected_owners = {identifier}
    if 'retainedTownPointId' in extension:
        expected_owners.add(extension['retainedTownPointId'])
    if (groups.get(identifier) or groups.get(target) != expected_owners
            or {sid for sid, group in base_groups.items() if group == target} != expected_owners
            or set(base_groups) & (protected - expected_owners)):
        raise ValueError('Reviewed town final explicit ownership changed')
    for row in extension['protectedRawRecords']:
        sid = row['id']
        index = indices.get(sid)
        expected = dict(row['currentMetadata'])
        if sid == identifier:
            expected['pickerGroupId'] = target
        if (index is None or {k: v for k, v in features[index].items() if k not in ('offset', 'length')}
                != {k: v for k, v in expected.items() if k not in ('offset', 'length')}):
            raise ValueError('Reviewed town final retained raw metadata changed')
        if expected['hasBoundary'] is False:
            if (index < len(geometries) or features[index] != expected
                    or 'offset' in features[index] or 'length' in features[index]
                    or row['packedGeometrySha256'] is not None):
                raise ValueError('Reviewed town retained point gained geometry or changed fields')
        elif (index >= len(geometries) or hashlib.sha256(packed_geometry_bytes(geometries[index])).hexdigest()
                != row['packedGeometrySha256']):
            raise ValueError('Reviewed town retained packed polygon changed')
    for sid in extension['readOnlyContextIds']:
        if (sid in indices or groups.get(sid) or sid in base_groups or sid in base_groups.values()):
            raise ValueError('Reviewed town source-only context gained a raw row or owner')



def validate_degueche_independent_prayer_selection(review, directory, coordinate_path,
                                                   original_sources, effective_sources,
                                                   current, governor, bases, curation):
    """Bind the retained Degache display to its separately selected prayer source."""
    identifier, display_id, manual_id = 'osm:node:351582010', 478, 481
    option = review.get('independentPublishedPrayerSelection')
    keys = {'schemaVersion', 'method', 'displayGroupId', 'retainedPoint', 'manualSourceId',
            'publishedPrayerReference', 'historicalDisplayEvidence', 'preservedPoints', 'qualification'}
    qualification = ('The published daily reference is separate from the retained Degache town; '
                     'it does not move or merge Bou Helal or certify a settlement center.')
    if (not isinstance(option, dict) or set(option) != keys
            or type(option['schemaVersion']) is not int or option['schemaVersion'] != 1
            or option['method'] != 'exact_degueche_retained_display_independent_prayer_source'
            or option['displayGroupId'] != 'delegation:478'
            or option['retainedPoint'] != {'id': identifier, 'lat': 33.9841058, 'lng': 8.2154584}
            or type(option['manualSourceId']) is not int or option['manualSourceId'] != manual_id
            or option['qualification'] != qualification
            or review.get('method') != 'reviewed_explicit_point_base_display_identity'
            or review.get('pointId') != identifier or type(review.get('baseId')) is not int
            or review['baseId'] != display_id or governor['id'] != 352 or len(bases) != 1
            or current.get('id') != identifier or current.get('kind') != 'town'
            or current.get('hasBoundary') is not False or current.get('delegationId') != manual_id
            or current.get('lat') != 33.9841058 or current.get('lng') != 8.2154584
            or review.get('officialDelegationCode') != '6252'):
        raise ValueError('Invalid exact Degache independent prayer/display contract')
    base = bases[0]
    if (base != {'id': 478, 'nomAr': 'دقاش', 'nomFr': 'Degueche', 'nomEn': 'Degueche',
                 'lat': 34.016, 'lng': 8.273}
            or any(key in review for key in ('townPreservationExtension', 'deferredCompleteGroupExtension',
                'sectorOwnedSettlementPreservation', 'typedNamePeerExtension', 'sourcePhaseLineage',
                'retainedTownPoint'))):
        raise ValueError('Degache published reference requires its unmixed ordinary town contract')
    historical = option['historicalDisplayEvidence']
    if (not isinstance(historical, dict) or set(historical) != {'file', 'sha256'}
            or historical['sha256'] != '87896aaf2dbce58865d98b3b94bce242b78c0fe9fee77ee5ca458a1baa239c5b'):
        raise ValueError('Missing immutable Degache retained-display facts')
    old = json.loads(aggregate_review_file(directory, historical, 'historical Degache display'))
    historical_point = old['currentIdentity']['onlyRawMember']
    expected_point = {**historical_point, 'delegationId': manual_id, 'pickerGroupId': identifier}
    if (historical_point['id'] != identifier or historical_point['pickerGroupId'] != 'delegation:478'
            or historical_point['delegationId'] != display_id or review.get('rawCurrent') != expected_point
            or review.get('contexts', {}).get('osm:relation:7154892', {}).get('kind') != 'delegation'
            or review['contexts']['osm:relation:7154892'].get('tags', {}).get('ref:tn:codegeo') != '6252'
            or set(review['contexts']) != {'osm:relation:7154892', 'osm:relation:1435826'}):
        raise ValueError('Degache historical display identity or exact administrative context changed')
    reference = option['publishedPrayerReference']
    if (not isinstance(reference, dict) or set(reference) != {'file', 'sha256', 'expectedCorrection'}
            or reference['file'] != 'prayer-source-coordinates.json'
            or (directory / reference['file']).resolve() != Path(coordinate_path).resolve()):
        raise ValueError('Missing active Degache published prayer reference')
    coordinates = json.loads(aggregate_review_file(directory, reference, 'Degache prayer coordinates'))
    corrections = [item for item in coordinates.get('corrections', []) if item.get('delegationId') == display_id]
    if (len(corrections) != 1 or corrections[0] != reference['expectedCorrection']
            or corrections[0].get('referenceKind') != 'inm_published_reference'
            or corrections[0].get('expectedGovernorateId') != governor['id']
            or corrections[0].get('expectedNames') != {key: base[key] for key in ('nomAr', 'nomFr', 'nomEn')}
            or corrections[0].get('original') != {'lat': 33.97, 'lng': 8.22}
            or corrections[0].get('proposed') != {'lat': 34.016, 'lng': 8.273}):
        raise ValueError('Degache published correction changed')
    validate_inm_published_reference(corrections[0], governor, base, directory)
    # Every old imada retains its fields. Only the independently recomputed
    # Degache South prayer default changes alongside the town default.
    old_sectors = {row['id']: row for row in old['preservation']['sixSectors']}
    sectors = review.get('preservedSectors')
    if (not isinstance(sectors, list) or len(sectors) != 6
            or {row.get('id') for row in sectors} != set(old_sectors)):
        raise ValueError('Missing exact six Degache sector identities')
    for sector in sectors:
        before = old_sectors[sector['id']]
        expected_metadata = {key: value for key, value in before.items() if key != 'officialIdentity'}
        if sector['id'] == 'osm:relation:7154920':
            expected_metadata['delegationId'] = 625
        if (sector.get('currentMetadata') != expected_metadata
                or sector.get('officialIdentity') != before['officialIdentity']
                or sector.get('expectedCuration') is not None):
            raise ValueError('Degache preserved sector metadata or current identity changed')
    preserved = option['preservedPoints']
    point_ids = {'osm:node:351581998', 'osm:node:5629636916'}
    if (not isinstance(preserved, list) or len(preserved) != 2
            or any(not isinstance(row, dict) or set(row) != {'id', 'kind', 'tags', 'currentMetadata'} for row in preserved)
            or {row['id'] for row in preserved} != point_ids):
        raise ValueError('Missing separate Bou Helal and Kriz point identities')
    old_points = {row['id']: row for row in old['preservation']['threeSeparatePointRows']}
    preserved_points = []
    for row in preserved:
        expected = old_points[row['id']]
        if (row['currentMetadata'] != expected or row['kind'] != expected['kind']
                or expected['pickerGroupId'] != row['id'] or expected['hasBoundary'] is not False
                or curation.get(row['id']) is not None):
            raise ValueError('Degache separate point fields or current curation changed')
        for sources in (original_sources, effective_sources):
            source = sources.get(row['id'])
            if (source is None or source.get('kind') != row['kind'] or source.get('tags') != row['tags']
                    or source.get('shape') is not None or source.get('point') is None
                    or source['point'].geom_type != 'Point'
                    or source['point'].x != expected['lng'] or source['point'].y != expected['lat']):
                raise ValueError('Degache preserved source point or exact tags changed')
        preserved_points.append({'id': row['id'], 'current': expected})
    return {'manualSourceId': manual_id, 'preservedNamePoints': preserved_points}


def validate_reviewed_point_base_display(rule, manifest_path, coordinate_path, obj, original_sources,
                                          effective_sources, current, governor, timetables, source_sha256, curation,
                                          reviewed_boundaries=None, official_report=None, reviewed_picker_groups=None):
    """Associate a reviewed town or inhabited quarter without moving either point.

    The two exact coordinates are independent inputs, equal or distinct. This method neither
    infers identity from distance nor relaxes the coincident-reference formats.
    """
    try:
        directory = Path(manifest_path).parent
        review = json.loads(aggregate_review_file(directory, rule['pickerBaseDisplayReview'], 'point/base display review'))
        aggregate_review_file(directory, review['sourceProposal'], 'town display identity proposal')
        aggregate_review_file(directory, review['independentSourceReview'], 'independent town identity review')
        identifier, target = obj['id'], review['baseId']
        expected = review['rawCurrent']
        bases = [d for d in governor['delegations'] if d['id'] == target]
        if review.get('method') == 'reviewed_explicit_point_base_display_identity':
            allowed_kinds = ('town', 'village')
            identity_decision = 'same_named_settlement_display_only'
            primary_role = 'official_settlement_identity'
            suburb_review = False
        elif review.get('method') == 'reviewed_explicit_suburb_point_base_display_identity':
            allowed_kinds = ('suburb',)
            identity_decision = 'same_named_inhabited_quarter_display_only'
            primary_role = 'official_inhabited_quarter_identity'
            suburb_review = True
        else:
            raise ValueError('Unknown reviewed point/base display method')
        independent_selection = None
        if 'independentPublishedPrayerSelection' in review:
            if suburb_review:
                raise ValueError('Independent published selection requires the exact Degache town contract')
            independent_selection = validate_degueche_independent_prayer_selection(
                review, directory, coordinate_path, original_sources, effective_sources,
                current, governor, bases, curation)
            identity_decision = 'retained_named_town_delegation_display_only'
        manual_target = independent_selection['manualSourceId'] if independent_selection else target
        sector_owned_extension = 'sectorOwnedSettlementPreservation' in review
        if sector_owned_extension and (suburb_review or any(key in review for key in (
                'townPreservationExtension', 'deferredCompleteGroupExtension', 'typedNamePeerExtension',
                'sourcePhaseLineage', 'retainedTownPoint', 'officialDelegationCode'))):
            raise ValueError('Sector-owned settlement requires its unmixed explicit settlement contract')
        if 'deferredCompleteGroupExtension' in review and not (suburb_review or (
                expected.get('kind') == 'village' and not any(key in review for key in (
                    'townPreservationExtension', 'typedNamePeerExtension', 'sourcePhaseLineage',
                    'independentPublishedPrayerSelection', 'retainedTownPoint')))):
            raise ValueError('Deferred complete-group extension requires an explicit suburb or unmixed village method')
        retained_town_extension = (isinstance(review.get('townPreservationExtension'), dict)
                                  and 'retainedTownPoint' in review['townPreservationExtension'])
        if retained_town_extension and (suburb_review or any(key in review for key in (
                'deferredCompleteGroupExtension', 'typedNamePeerExtension', 'sourcePhaseLineage',
                'officialDelegationCode'))):
            raise ValueError('Retained town point requires the unmixed town preservation contract')
        typed_extension = None
        preserved_name_points, read_only_name_contexts = [], []
        if 'typedNamePeerExtension' in review:
            typed_extension = review['typedNamePeerExtension']
            if (not suburb_review or not isinstance(typed_extension, dict)
                    or set(typed_extension) != {'schemaVersion', 'rawPointPeers', 'readOnlyContextPeers'}
                    or type(typed_extension['schemaVersion']) is not int or typed_extension['schemaVersion'] != 1
                    or not isinstance(typed_extension['rawPointPeers'], list)
                    or not isinstance(typed_extension['readOnlyContextPeers'], list)
                    or not (typed_extension['rawPointPeers'] or typed_extension['readOnlyContextPeers'])):
                raise ValueError('Malformed or unsupported typed source-name peer extension')
            preserved_name_points = typed_extension['rawPointPeers']
            read_only_name_contexts = typed_extension['readOnlyContextPeers']
        if (review.get('schemaVersion') != 1
                or review.get('sourceSha256') != source_sha256 or type(target) is not int or target <= 0
                or review.get('identityDecision') != identity_decision
                or review['pointId'] != identifier
                or len(bases) != 1 or bases[0] != review['baseCurrent']
                or {k: v for k, v in governor.items() if k != 'delegations'} != review['expectedGovernor']
                or current != {k: v for k, v in expected.items() if k != 'pickerGroupId'}
                or expected['id'] != identifier or expected['pickerGroupId'] != identifier
                or expected['kind'] not in allowed_kinds or expected['hasBoundary'] is not False
                or expected['governorateId'] != governor['id'] or current['delegationId'] != manual_target
                or rule['expectedTags'] != obj['tags'] or rule['action'] != 'preserve_point'
                or review['originalSourceNode'] != {'id': identifier, 'tags': obj['tags'],
                    'lat': current['lat'], 'lng': current['lng']}
                or review['onlyRawChange'] != {'id': identifier, 'field': 'pickerGroupId',
                    'before': identifier, 'after': f'delegation:{target}'}
                or ('deferredCompleteGroupExtension' not in review and not retained_town_extension
                    and not sector_owned_extension and review['completePickerGroups'] != {'before': [[identifier], [f'delegation:{target}']],
                        'after': [[f'delegation:{target}', identifier]]})
                or review['displayPhaseCoordinateChanges'] != []):
            raise ValueError('Reviewed town display identity or either retained coordinate changed')
        base = bases[0]
        if (min(timetables, key=lambda d: (distance(current['lat'], current['lng'], d), d['id']))['id'] != manual_target
                or min(timetables, key=lambda d: (distance(base['lat'], base['lng'], d), d['id']))['id'] != target
                or [r for r in json.loads(display_identity_coordinate_bytes(Path(coordinate_path).parent))['corrections']
                    if r['delegationId'] == target] != review['expectedCoordinateCorrections']):
            raise ValueError('Reviewed separate references or available prayer source changed')
        node = json.loads(aggregate_review_file(directory, review['sourceNode'], 'exact named source point'))['elements']
        original_node = review['sourceNodeIdentity']
        if (node != [original_node['exactElement']] or len(node) != 1 or node[0]['type'] != 'node'
                or f"osm:node:{node[0]['id']}" != identifier or node[0]['tags'] != obj['tags']
                or node[0]['lat'] != current['lat'] or node[0]['lon'] != current['lng']
                or review['sourceNode']['sha256'] != original_node['sha256']
                or review['sourceNode']['url'] != original_node['url']
                or type(node[0]['version']) is not int or not node[0]['timestamp'] or not node[0]['changeset']):
            raise ValueError('Reviewed named source node snapshot changed')
        primary = review['primaryEvidence']
        if not primary or not any(r['role'] == primary_role for r in primary):
            raise ValueError('Reviewed display association lacks primary settlement identity')
        for reference in primary:
            raw = aggregate_review_file(directory, reference, 'primary town identity')
            if (not reference['url'].startswith(('https://', 'http://')) or not reference['requiredText']
                    or any(not isinstance(text, str) or not text or text not in raw.decode('utf-8')
                           for text in reference['requiredText'])):
                raise ValueError('Reviewed primary town identity content changed')
        registry = json.loads(aggregate_review_file(directory, review['officialRegistry'], 'official imada identities'))
        if sector_owned_extension:
            return validate_sector_owned_settlement_preservation(
                directory, review, rule, registry, original_sources, effective_sources, curation,
                source_sha256, reviewed_boundaries, official_report, reviewed_picker_groups)
        if 'townPreservationExtension' in review:
            return validate_reviewed_town_preservation_extension(
                directory, review, rule, registry, original_sources, effective_sources, curation,
                source_sha256, reviewed_boundaries, official_report, reviewed_picker_groups, governor)
        sectors = review['preservedSectors']
        official = [r for r in registry['sectors'] if r['delegationCode'] == review['officialDelegationCode']]
        if (not sectors or len({r['id'] for r in sectors}) != len(sectors)
                or sorted(official, key=lambda r: r['sectorCode']) != sorted(
                    [r['officialIdentity'] for r in sectors], key=lambda r: r['sectorCode'])
                or len({r['officialIdentity']['sectorCode'] for r in sectors}) != len(sectors)
                or any(r['originalTags'].get('ref:tn:codegeo') != r['officialIdentity']['sectorCode'] for r in sectors)):
            raise ValueError('Reviewed complete separate imada inventory changed')
        contexts = list(review['contexts'].values())
        if (sorted(c['kind'] for c in contexts) != ['delegation', 'governorate']
                or next(c for c in contexts if c['kind'] == 'delegation')['tags'].get('ref:tn:codegeo') != review['officialDelegationCode']
                or next(c for c in contexts if c['kind'] == 'governorate')['tags'].get('ref:tn:codegeo') != review['officialDelegationCode'][:2]
                or next(c for c in contexts if c['kind'] == 'governorate')['tags'].get('name:ar') != 'ولاية ' + governor['nomAr']):
            raise ValueError('Reviewed town governorate/delegation context identity changed')
        peer_keys = {norm(v) for v in [current['name'], *current['aliases'], base['nomAr'], base['nomFr'], base['nomEn']]}
        peers = review['sourceNamePeers']
        if (len({r['id'] for r in peers}) != len(peers) or identifier not in {r['id'] for r in peers}
                or set(review['contexts']) & {r['id'] for r in sectors}):
            raise ValueError('Reviewed source identity inventories are malformed')
        preserved_name_peers = []
        if suburb_review:
            # A suburb's namesake imada and distant homonyms keep their own
            # identities. This format pins unchanged original/effective peers;
            # source replacements need a separate joint lineage review.
            preserved_name_peers = review['preservedNamePeers']
            expected_peer_ids = {row['id'] for row in peers} - {identifier} - {row['id'] for row in sectors}
            if typed_extension is not None:
                point_peer_keys = {'id', 'kind', 'originalTags', 'sourceGeometrySha256', 'expectedCuration',
                                   'currentMetadata', 'completeRawGroup'}
                context_peer_keys = {'id', 'kind', 'expectedCuration', 'expectedRawPresence', 'completeRawGroup'}
                if (any(not isinstance(row, dict) or set(row) != point_peer_keys for row in preserved_name_points)
                        or any(not isinstance(row, dict) or set(row) != context_peer_keys for row in read_only_name_contexts)):
                    raise ValueError('Malformed typed point or source-only name peer record')
                point_peer_ids = {row['id'] for row in preserved_name_points}
                context_peer_ids = {row['id'] for row in read_only_name_contexts}
                if (len(point_peer_ids) != len(preserved_name_points)
                        or len(context_peer_ids) != len(read_only_name_contexts)
                        or point_peer_ids & context_peer_ids
                        or not (point_peer_ids | context_peer_ids) <= expected_peer_ids
                        or context_peer_ids != ({row['id'] for row in peers} & set(review['contexts']))
                        or any(row['kind'] != 'neighbourhood' or not row['id'].startswith('osm:node:')
                               or row['currentMetadata']['id'] != row['id']
                               or row['currentMetadata']['kind'] != row['kind']
                               or row['currentMetadata']['sourceId'] != 'osm'
                               or row['currentMetadata']['hasBoundary'] is not False
                               or 'offset' in row['currentMetadata'] or 'length' in row['currentMetadata']
                               or row['currentMetadata']['pickerGroupId'] != row['id']
                               or row['completeRawGroup'] != [row['id']]
                               for row in preserved_name_points)
                        or any(row['kind'] not in ('delegation', 'governorate')
                               or row['kind'] != review['contexts'][row['id']]['kind']
                               or row['expectedRawPresence'] is not False or row['completeRawGroup'] != []
                               for row in read_only_name_contexts)):
                    raise ValueError('Reviewed typed name-peer partition, kind or presence changed')
                expected_peer_ids -= point_peer_ids | context_peer_ids
            if (not isinstance(preserved_name_peers, list)
                    or 'sourcePhaseLineage' in review
                    or len({row['id'] for row in preserved_name_peers}) != len(preserved_name_peers)
                    or {row['id'] for row in preserved_name_peers} != expected_peer_ids
                    or any(row['currentMetadata']['id'] != row['id']
                           or row['currentMetadata']['kind'] != row['kind']
                           or row['currentMetadata']['sourceId'] != 'osm'
                           or row['currentMetadata']['hasBoundary'] is not True
                           or row['currentMetadata']['pickerGroupId'] != row['id']
                           or row['completeRawGroup'] != [row['id']]
                           for row in preserved_name_peers)):
                raise ValueError('Reviewed suburb homonym inventory or unchanged group changed')
        lineage = None
        if 'sourcePhaseLineage' in review:
            lineage = validate_reviewed_source_phase_lineage(directory, review, original_sources, effective_sources,
                                                            peer_keys, source_sha256, reviewed_boundaries, official_report)
        for phase, sources, provider in (('original', original_sources, None), ('effective', effective_sources, 'osm')):
            phase_peers = peers if lineage is None else lineage['peers'][phase]
            point = sources[identifier]
            if (point['kind'] != current['kind'] or point.get('sourceId') != provider
                    or point['tags'] != obj['tags'] or point.get('shape') is not None
                    or point['point'].geom_type != 'Point'
                    or point['point'].coords[:] != [(current['lng'], current['lat'])]
                    or {i for i, s in sources.items() if peer_keys & {norm(v) for v in names(s['tags'])}}
                        != {r['id'] for r in phase_peers}):
                raise ValueError('Reviewed original/effective point or complete name peers changed')
            for peer in phase_peers:
                if sources[peer['id']]['tags'] != peer['tags'] or sources[peer['id']]['kind'] != peer['kind']:
                    raise ValueError('Reviewed source-name peer changed')
            for row in preserved_name_peers:
                peer = sources[row['id']]
                if (peer['kind'] != row['kind'] or peer.get('sourceId') != provider
                        or peer['tags'] != row['originalTags']
                        or peer['shape'].geom_type not in ('Polygon', 'MultiPolygon')
                        or aggregate_geometry_sha256(peer['shape']) != row['sourceGeometrySha256']
                        or curation.get(row['id']) != row['expectedCuration']):
                    raise ValueError('Reviewed suburb homonym source or geometry changed')
            for row in preserved_name_points:
                peer = sources[row['id']]
                if (peer['kind'] != row['kind'] or peer.get('sourceId') != provider
                        or peer['tags'] != row['originalTags'] or peer.get('shape') is not None
                        or peer['point'].geom_type != 'Point' or peer['point'].is_empty
                        or peer['point'].coords[:] != [(row['currentMetadata']['lng'], row['currentMetadata']['lat'])]
                        or aggregate_geometry_sha256(peer['point']) != row['sourceGeometrySha256']
                        or curation.get(row['id']) != row['expectedCuration']):
                    raise ValueError('Reviewed separate point name peer source or coordinates changed')
            for row in read_only_name_contexts:
                context = sources[row['id']]
                if (context['kind'] != row['kind'] or context.get('sourceId') != provider
                        or context['shape'].geom_type not in ('Polygon', 'MultiPolygon')
                        or context['shape'].is_empty
                        or curation.get(row['id']) != row['expectedCuration']):
                    raise ValueError('Reviewed source-only name context kind, provider or curation changed')
            for context_id, expected_context in review['contexts'].items():
                context = sources[context_id]
                if (context['kind'] != expected_context['kind'] or context['tags'] != expected_context['tags']
                        or aggregate_geometry_sha256(context['shape']) != expected_context['sourceGeometrySha256']
                        or context['kind'] not in ('governorate', 'delegation')
                        or not context['shape'].contains(point['point'])
                        or not context['shape'].contains(Point(base['lng'], base['lat']))):
                    raise ValueError('Reviewed read-only administrative context changed')
            for row in sectors:
                sector = sources[row['id']]
                expected_tags, expected_geometry = row['originalTags'], row['sourceGeometrySha256']
                if lineage is not None:
                    state = lineage['states'][row['id']][phase]
                    expected_tags, expected_geometry = state['tags'], state['geometrySha256']
                if (sector['kind'] != 'sector' or sector['tags'] != expected_tags
                        or aggregate_geometry_sha256(sector['shape']) != expected_geometry
                        or curation.get(row['id']) != row['expectedCuration']):
                    raise ValueError('Reviewed separate imada source identity changed')
        # Final verification checks the whole display group and all preserved
        # point/imada fields after automatic, explicit and context grouping.
        result = {'exactPair': {'basePickerId': f'delegation:{target}', 'rawCurrent': expected},
                'preservedSectors': [{'id': row['id'], 'current': row['currentMetadata'],
                    'packedGeometrySha256': row['packedGeometrySha256']} for row in sectors]
                    + ([] if lineage is None else lineage['additionalPreservedSectors'])}
        if independent_selection is not None:
            result['preservedNamePoints'] = independent_selection['preservedNamePoints']
        if suburb_review:
            result['preservedNamePeers'] = [{'id': row['id'], 'current': row['currentMetadata'],
                'packedGeometrySha256': row['packedGeometrySha256']} for row in preserved_name_peers]
        if typed_extension is not None:
            result['preservedNamePoints'] = [{'id': row['id'], 'current': row['currentMetadata']}
                for row in preserved_name_points]
            result['readOnlyNameContexts'] = [{'id': row['id'], 'kind': row['kind']}
                for row in read_only_name_contexts]
        if 'deferredCompleteGroupExtension' in review:
            result['deferredCompleteGroupExtension'] = validate_deferred_complete_group_extension(
                review, reviewed_picker_groups, original_sources, effective_sources, curation, rule, source_sha256,
                reviewed_boundaries=reviewed_boundaries, official_report=official_report)
        return result
    except (OSError, UnicodeError, ValueError, KeyError, TypeError, AttributeError, IndexError) as error:
        raise ValueError('Missing, malformed or changed reviewed point/base display identity') from error



def available_timetables(governors, assets):
    """Choose manual defaults only from sources with a complete bundled month.

    Runtime additionally checks the selected month; this compile-time filter
    prevents an entirely empty source from receiving any default locality map.
    """
    available, rejected = [], []
    for delegation in (d for governor in governors for d in governor['delegations']):
        if not delegation.get('lat') or not delegation.get('lng'):
            rejected.append({'id': delegation['id'], 'reason': 'missing_coordinates'})
        elif any(complete_prayer_month(path) for path in sorted((assets/'csv'/str(delegation['id'])).glob('*/*.csv'))):
            available.append(delegation)
        else:
            rejected.append({'id': delegation['id'], 'reason': 'no_complete_bundled_month'})
    if not available:
        raise ValueError('No prayer source has coordinates and a complete bundled month')
    return available, rejected


def picker_name_keys(feature):
    return {norm(value) for value in [feature['name'], *feature.get('aliases', [])] if norm(value)}


def same_picker_name(first, second):
    # A shared loose alias must not collapse "Ennasr 1" and "Ennasr 2",
    # or the numbered part of a locality into its unnumbered parent.
    def numbers(feature):
        return tuple(''.join(str(unicodedata.digit(c)) for c in token)
                     for token in re.findall(r'\d+', feature['name']))
    return numbers(first) == numbers(second) and bool(picker_name_keys(first) & picker_name_keys(second))


def administrative_name_keys(area):
    # These are administrative title prefixes, not alternate place spellings.
    return {norm(re.sub(r'^(?:معتمدية\s+|d[ée]l[ée]gation\s+(?:de\s+)?|delegation\s+(?:of\s+)?)',
                        '', value, flags=re.IGNORECASE)) for value in names(area['tags'])}


def coded_sector_parent(obj, governor, delegations):
    """Read sector membership from explicit Tunisian administrative codes.

    The six-digit sector code contains its four-digit delegation code and
    two-digit governorate code. A source code can be stale after a transfer;
    callers must not use it to override conflicting current geometry by itself.
    """
    code = obj['tags'].get('ref:tn:codegeo', '')
    if obj['kind'] != 'sector' or not re.fullmatch(r'[0-9]{6}', code):
        return None
    governor_code = governor['tags'].get('ref:tn:codegeo', '')
    if governor_code and (not re.fullmatch(r'[0-9]{2}', governor_code) or code[:2] != governor_code):
        return None
    candidates = [area for area in delegations if area['kind'] == 'delegation'
                  and area['tags'].get('ref:tn:codegeo') == code[:4]]
    return candidates[0] if len(candidates) == 1 else None


def administrative_context(obj, footprint, governor, parents, administrative_delegations=None,
                           reviewed_parent=None, reviewed_parent_names=None):
    """Prefer reviewed membership; otherwise require containment for context."""
    containing = [parent for parent in parents if parent['id'] != obj['id']
                  and parent['shape'].covers(footprint)
                  and not (obj['kind'] == 'sector' and parent['kind'] == 'sector')]
    coded_parent = coded_sector_parent(obj, governor, administrative_delegations
                                      if administrative_delegations is not None else parents)
    if reviewed_parent_names:
        # New official delegations may have no source polygon. Preserve an
        # actual containing sector, and use only the reviewed parent name.
        containing = [parent for parent in containing if parent['kind'] == 'sector']
    elif reviewed_parent is not None:
        containing = [parent for parent in containing if parent['kind'] == 'sector'] + [reviewed_parent]
    elif coded_parent is not None and coded_parent['shape'].covers(footprint):
        # Source codes are useful only when their geometry also agrees. An old
        # code must not move Essaida's transferred sectors back into Regueb.
        containing = [coded_parent]
    containing.sort(key=lambda parent: (parent['shape'].area, parent['id']))
    sectors = [parent for parent in containing if parent['kind'] == 'sector']
    delegations = [parent for parent in containing if parent['kind'] == 'delegation']
    own_names = {norm(value) for value in names(obj['tags'])}
    # A subtitle equal to the row title carries no useful context. Fall back to
    # the containing delegation instead of leaving a duplicate row unexplained.
    useful_sector = len(sectors) == 1 and norm(names(sectors[0]['tags'])[0]) not in own_names
    parent = sectors[0] if useful_sector else delegations[0] if len(delegations) == 1 else governor
    parent_name = (reviewed_parent_names[0] if reviewed_parent_names and not useful_sector
                   else names(parent['tags'])[0])
    context = [area for area in containing if area['kind'] != 'sector' or len(sectors) == 1]
    # Governorate names are already supplied once by the app's original
    # governorate catalog; repeating its many translated OSM aliases on every
    # locality would bloat the metadata loaded when opening the picker.
    aliases = sorted({value for area in context for value in names(area['tags'])}
                     | set(reviewed_parent_names or []))
    return parent_name, aliases, delegations


def matching_base_delegation(feature, footprint, delegations, governor):
    """Only merge a town with a proven namesake administrative delegation."""
    if feature['kind'] != 'town':
        return None
    candidates = set()
    for area in delegations:
        if not area['shape'].covers(footprint):
            continue
        common_names = picker_name_keys(feature) & administrative_name_keys(area)
        for delegation in governor['delegations']:
            timetable_names = {norm(delegation.get(key, '')) for key in ('nomAr', 'nomFr', 'nomEn')}
            if (feature.get('delegationId') == delegation['id']
                    and common_names & timetable_names and delegation.get('lat') and delegation.get('lng')
                    and area['shape'].covers(Point(delegation['lng'], delegation['lat']))):
                candidates.add(f"delegation:{delegation['id']}")
    return next(iter(candidates)) if len(candidates) == 1 else None


def sector_anchor_picker_groups(features, footprints, existing_groups, source_sectors, source_footprints, base_groups):
    """Find complete display groups supported by one unambiguous named sector.

    Unlike the overlap/alias rule, this only accepts identical current primary
    names. A unique source code identifies the administrative anchor; it does not
    certify the source borders. The source curation checksum gates source changes.
    """
    if not source_sectors or not source_footprints:
        return []
    sectors_by_id = {area['id']: area for area in source_sectors}
    if len(sectors_by_id) != len(source_sectors):
        raise ValueError('Duplicate source sector ID while grouping picker rows')
    codes = Counter(area['tags'].get('ref:tn:codegeo', '') for area in source_sectors)
    source_tree = STRtree([area['shape'] for area in source_sectors])

    def primary(feature):
        # No alias expansion, accent folding, digit removal or spelling changes.
        return clean(unicodedata.normalize('NFC', feature['name']))

    buckets = defaultdict(list)
    for index, feature in enumerate(features):
        buckets[(feature['governorateId'], primary(feature), feature.get('parentName'))].append(index)
    group_for = {index: members for members in existing_groups for index in members}
    candidates = []
    for anchor_index, anchor in enumerate(features):
        source = sectors_by_id.get(anchor['id'])
        if anchor['kind'] != 'sector' or source is None:
            continue
        code = source['tags'].get('ref:tn:codegeo', '')
        if (not re.fullmatch(r'[0-9]{6}', code) or codes[code] != 1
                or primary(anchor) != clean(unicodedata.normalize('NFC', names(source['tags'])[0]))):
            continue

        def contained(index):
            original = source_footprints.get(features[index]['id'])
            return (original is not None and source['shape'].covers(original)
                    and footprints[anchor_index].covers(footprints[index]))

        key = (anchor['governorateId'], primary(anchor), anchor.get('parentName'))
        seeds = [index for index in buckets[key] if contained(index)]
        members = set().union(*(group_for[index] for index in seeds))
        if (not members or len({min(group_for[index]) for index in members}) < 2
                or any(features[index]['id'] in base_groups
                       or (features[index]['kind'] == 'sector' and index != anchor_index)
                       or (features[index]['governorateId'], primary(features[index]), features[index].get('parentName')) != key
                       or not contained(index) for index in members)):
            continue

        def competing_sector(index):
            original = source_footprints[features[index]['id']]
            for other_index in source_tree.query(original):
                other = source_sectors[int(other_index)]
                if other['id'] == anchor['id']:
                    continue
                intersection = other['shape'].intersection(original)
                if intersection.area > 0 or (not features[index]['hasBoundary'] and not intersection.is_empty):
                    return True
            return False

        if any(competing_sector(index) for index in members if index != anchor_index):
            continue
        candidates.append(sorted(members))
    return candidates


def load_reviewed_distinct_picker_pairs(manifest_path, features, original_sources,
                                       effective_sources, source_sha256):
    """Pin specific separate display identities without changing their geography."""
    manifest_path = Path(manifest_path)
    try:
        raw = manifest_path.read_bytes()
        manifest = json.loads(raw)
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ValueError('Missing or malformed distinct-choice manifest') from error
    if (not isinstance(manifest, dict) or manifest.get('schemaVersion') != 1
            or manifest.get('sourceSha256') != source_sha256
            or not isinstance(manifest.get('distinctPairs', []), list)):
        raise ValueError('Distinct picker choices need review for this source')
    current = {feature['id']: feature for feature in features}
    if len(current) != len(features):
        raise ValueError('Duplicate locality ID before distinct-choice review')
    pairs, applications = set(), []
    metadata_fields = {'name', 'aliases', 'kind', 'parentName', 'governorateId', 'sourceId'}
    record_fields = {'id', 'originalKind', 'originalTags', 'originalGeometrySha256',
                     'effectiveKind', 'effectiveTags', 'effectiveGeometrySha256', 'expectedMetadata'}
    for entry in manifest.get('distinctPairs', []):
        identifiers = entry.get('ids') if isinstance(entry, dict) else None
        if (not isinstance(identifiers, list) or len(identifiers) != 2
                or any(not isinstance(identifier, str) or not identifier for identifier in identifiers)
                or len(set(identifiers)) != 2):
            raise ValueError('Distinct picker pair needs exactly two explicit IDs')
        pair = frozenset(identifiers)
        if pair in pairs:
            raise ValueError('Repeated reviewed distinct picker pair')
        try:
            proof = json.loads(aggregate_review_file(
                manifest_path.parent, entry.get('reviewEvidence'), 'distinct picker choices'))
        except (OSError, UnicodeError, json.JSONDecodeError) as error:
            raise ValueError('Missing or malformed distinct-choice proof') from error
        if (not isinstance(proof, dict) or proof.get('schemaVersion') != 1
                or proof.get('conclusion') != 'distinct_locality_choices'
                or proof.get('sourceSha256') != source_sha256
                or not isinstance(proof.get('records'), list) or len(proof['records']) != 2):
            raise ValueError('Invalid reviewed distinct-choice identity proof')
        records = {}
        for record in proof['records']:
            if (not isinstance(record, dict) or set(record) != record_fields
                    or not isinstance(record.get('id'), str) or record['id'] not in pair
                    or record['id'] in records):
                raise ValueError('Distinct-choice proof inventory changed')
            identifier = record['id']
            original, effective = original_sources.get(identifier), effective_sources.get(identifier)
            expected = record['expectedMetadata']
            if (original is None or effective is None or identifier not in current
                    or original.get('shape') is None or effective.get('shape') is None
                    or original['kind'] != record['originalKind']
                    or original['tags'] != record['originalTags']
                    or aggregate_geometry_sha256(original['shape']) != record['originalGeometrySha256']
                    or effective['kind'] != record['effectiveKind']
                    or effective['tags'] != record['effectiveTags']
                    or aggregate_geometry_sha256(effective['shape']) != record['effectiveGeometrySha256']
                    or not isinstance(expected, dict) or set(expected) != metadata_fields
                    or {key: current[identifier].get(key) for key in metadata_fields} != expected):
                raise ValueError(f'Reviewed distinct-choice source or metadata changed: {identifier}')
            records[identifier] = expected
        if set(records) != pair or not isinstance(proof.get('evidence'), list) or not proof['evidence']:
            raise ValueError('Distinct-choice proof lacks its complete source evidence')
        for reference in proof['evidence']:
            try:
                aggregate_review_file(manifest_path.parent, reference, 'distinct-choice source evidence')
            except (OSError, UnicodeError) as error:
                raise ValueError('Missing distinct-choice source evidence') from error
        pairs.add(pair)
        applications.append({'ids': sorted(pair), 'reviewEvidence': entry['reviewEvidence'],
                             'expectedMetadata': records})
    report = ({'manifestSha256': hashlib.sha256(raw).hexdigest(),
               'reviewedPairCount': len(pairs), 'applications': applications} if pairs else None)
    return frozenset(pairs), report


def verify_distinct_picker_groups(features, blocked_pairs, review=None, residential_report=None):
    """Reject rejoining; admit only separately validated exact late alias changes."""
    current = {feature['id']: feature for feature in features}
    if len(current) != len(features):
        raise ValueError('Duplicate locality ID while checking separate picker choices')
    for pair in blocked_pairs:
        if (len(pair) != 2 or any(identifier not in current for identifier in pair)
                or len({current[identifier].get('pickerGroupId') for identifier in pair}) != 2
                or any(not current[identifier].get('pickerGroupId') for identifier in pair)):
            raise ValueError('Reviewed distinct locality choices were joined or removed')
    transitions = {}
    if residential_report is not None:
        for application in residential_report['applications']:
            if 'reviewedLateAliasTransition' not in application:
                continue
            transition = application['reviewedLateAliasTransition']
            identifier = application['id']
            if (application.get('identityMethod') != 'reviewed_noncoincident_town_residential_base_identity'
                    or not isinstance(transition, dict) or set(transition) != {'id', 'before', 'after'}
                    or transition['id'] != identifier or identifier in transitions or identifier not in current
                    or any(not isinstance(transition[key], list) or any(not isinstance(v, str) for v in transition[key])
                           or len(transition[key]) != len(set(transition[key])) for key in ('before', 'after'))
                    or transition['before'] == transition['after']
                    or current[identifier].get('pickerGroupId') != application['pickerGroupId']
                    or application['protectedMetadata'][identifier]['aliases'] != transition['after']
                    or current[identifier].get('aliases') != transition['after']):
                raise ValueError('Invalid reviewed late alias transition for a distinct choice')
            transitions[identifier] = transition
    applied = set()
    if review is not None:
        for application in review['applications']:
            for identifier, expected in application['expectedMetadata'].items():
                if identifier in transitions:
                    transition = transitions[identifier]
                    if expected.get('aliases') != transition['before']:
                        raise ValueError('Reviewed late aliases do not match the distinct-choice source proof')
                    expected = {**expected, 'aliases': transition['after']}
                    applied.add(identifier)
                if (identifier not in current
                        or {key: current[identifier].get(key) for key in expected} != expected):
                    raise ValueError('Reviewed separate choice metadata changed after grouping')
    if applied != set(transitions):
        raise ValueError('Reviewed late aliases have no matching distinct-choice proof')


def assign_picker_groups(features, geometries, base_groups=None, source_sectors=None, source_footprints=None,
                         blocked_pairs=None):
    """Deduplicate display rows while retaining every ID and original polygon.

    Names alone are insufficient: polygon pairs need positive overlap, and
    point records need actual polygon containment or identical coordinates,
    kind and administrative context. Shared edges and nearby or remote homonyms
    stay separate. Identical primary names may additionally share a uniquely
    coded containing sector after checking every existing group member.
    Canonical IDs are deterministic.
    """
    base_groups = base_groups or {}
    blocked_pairs = frozenset(blocked_pairs or ())
    indices = {feature['id']: index for index, feature in enumerate(features)}
    if (len(indices) != len(features)
            or any(not isinstance(pair, frozenset) or len(pair) != 2
                   or any(identifier not in indices for identifier in pair) for pair in blocked_pairs)):
        raise ValueError('Invalid reviewed distinct picker pair inventory')
    blocked_indices = [frozenset(indices[identifier] for identifier in pair) for pair in blocked_pairs]
    parents = list(range(len(features)))
    roots = [{index} for index in range(len(features))]
    footprints = [geometries[index] if index < len(geometries) else Point(feature['lng'], feature['lat'])
                  for index, feature in enumerate(features)]
    overlap_cache = {}

    def spatial_match(first, second):
        pair = tuple(sorted((first, second)))
        if pair not in overlap_cache:
            a, b = footprints[first], footprints[second]
            if first < len(geometries) and second < len(geometries):
                overlap_cache[pair] = a.intersection(b).area > 0
            elif first < len(geometries):
                overlap_cache[pair] = a.covers(b)
            elif second < len(geometries):
                overlap_cache[pair] = b.covers(a)
            else:
                overlap_cache[pair] = (a.equals(b)
                                       and features[first]['kind'] == features[second]['kind']
                                       and features[first].get('parentName') == features[second].get('parentName'))
        return overlap_cache[pair]

    def root(index):
        while parents[index] != index:
            parents[index] = parents[parents[index]]
            index = parents[index]
        return index

    def join(first, second):
        a, b = root(first), root(second)
        if a == b:
            return
        # An alias chain must not join distinct numbered names or bridge two
        # different verified original-delegation rows.
        members = roots[a] | roots[b]
        if any(pair <= members for pair in blocked_indices):
            return
        targets = {base_groups[features[index]['id']] for index in members if features[index]['id'] in base_groups}
        if len(targets) > 1 or any(not same_picker_name(features[i], features[j]) or not spatial_match(i, j)
                                   for i in roots[a] for j in roots[b]):
            return
        parents[b] = a
        roots[a] = members

    tree = STRtree(geometries)
    for first, feature in enumerate(features):
        footprint = footprints[first]
        for second in sorted(int(index) for index in tree.query(footprint)):
            if first == second or (first < len(geometries) and second < first):
                continue
            other = features[second]
            if feature['governorateId'] != other['governorateId'] or not same_picker_name(feature, other):
                continue
            if spatial_match(first, second):
                join(first, second)
    # The polygon tree cannot find duplicate point records. Compare those in a
    # second pass, preserving existing polygon grouping order and requiring
    # exactly equal coordinates, kinds and administrative context.
    point_buckets = defaultdict(list)
    for index in range(len(geometries), len(features)):
        feature = features[index]
        key = (feature['lat'], feature['lng'], feature['governorateId'],
               feature['kind'], feature.get('parentName'))
        for previous in point_buckets[key]:
            join(previous, index)
        point_buckets[key].append(index)
    existing_groups = defaultdict(set)
    for index in range(len(features)):
        existing_groups[root(index)].add(index)
    for members in sector_anchor_picker_groups(features, footprints, list(existing_groups.values()),
                                                source_sectors, source_footprints, base_groups):
        # The complete groups passed the stricter sector checks above. Their
        # disjoint children need not overlap each other; do not relax join().
        combined = set().union(*(roots[root(index)] for index in members))
        if any(pair <= combined for pair in blocked_indices):
            continue
        target = root(members[0])
        for index in members[1:]:
            other = root(index)
            if other != target:
                parents[other] = target
                roots[target] |= roots[other]
    groups = defaultdict(list)
    for index, feature in enumerate(features):
        groups[root(index)].append(feature)
    duplicate_groups = []
    for members in groups.values():
        targets = {base_groups[feature['id']] for feature in members if feature['id'] in base_groups}
        canonical = next(iter(targets)) if targets else min(members, key=lambda feature: (
            feature['kind'] != 'sector', not feature['hasBoundary'], feature['id']))['id']
        for feature in members:
            feature['pickerGroupId'] = canonical
        if len(members) > 1 or canonical.startswith('delegation:'):
            duplicate_groups.append({'pickerGroupId': canonical, 'ids': sorted(feature['id'] for feature in members)})
    verify_distinct_picker_groups(features, blocked_pairs)
    return sorted(duplicate_groups, key=lambda group: group['pickerGroupId'])


def picker_review_name_key(value):
    """Compare current primary names, keeping word boundaries and all numbers."""
    value = unicodedata.normalize('NFKD', value).replace('ـ', '').replace('ى', 'ي').lower()
    value = ''.join(c for c in value if not unicodedata.category(c).startswith('M')
                    and unicodedata.category(c) != 'Cf')
    return ' '.join(''.join(c if c.isalnum() else ' ' for c in value).split())


def validate_reviewed_addressed_village(record, features, geometries, timetables,
                                        original_sources, effective_sources, curation, indices):
    """Validate one pinned address relationship; do not infer village extent."""
    identifier, target = record['id'], record['targetPickerGroupId']
    if (record['expectedExistingMemberIds'] != [identifier]
            or record['expectedTargetMemberIds'] != [target]
            or record.get('villagePointId') != target):
        raise ValueError('Reviewed addressed village requires its exact point and residential groups')
    name = record.get('nameEvidence', {})
    if (not isinstance(name, dict) or any(not isinstance(name.get(k), str) or not name[k]
            for k in ('arabic', 'latin'))
            or any(name.get(k) != value for k, value in (
                ('addressTag', 'addr:place'), ('arabicNameTag', 'name:ar'), ('latinNameTag', 'name:en')))):
        raise ValueError('Missing reviewed explicit locality-address name evidence')
    context = record.get('codedContext', {})
    if not isinstance(context, dict):
        raise ValueError('Missing reviewed addressed-village administrative context')
    context_id = context.get('id')
    old_context, live_context = original_sources.get(context_id), effective_sources.get(context_id)
    context_index = indices.get(context_id)
    if (old_context is None or live_context is None or context_index is None
            or context_index >= len(geometries) or old_context.get('kind') != 'sector'
            or live_context.get('kind') != 'sector' or not features[context_index]['hasBoundary']
            or old_context['tags'] != context.get('expectedOriginalTags')
            or live_context['tags'] != context.get('expectedEffectiveTags')
            or curation.get(context_id) != context.get('expectedCuration')):
        raise ValueError('Reviewed addressed-village source context changed')
    current_context = features[context_index]
    if (any(current_context[k] != context.get(expected) for k, expected in (
            ('kind', 'expectedKind'), ('sourceId', 'expectedSourceId'), ('name', 'expectedName'),
            ('aliases', 'expectedAliases'), ('parentName', 'expectedParentName'),
            ('contextAliases', 'expectedContextAliases'), ('delegationId', 'expectedDelegationId'),
            ('lat', 'expectedLat'), ('lng', 'expectedLng')))
            or current_context['governorateId'] != record['expectedGovernorateId']):
        raise ValueError('Reviewed addressed-village displayed context changed')
    original_context, effective_context = old_context.get('shape'), live_context.get('shape')
    packed_context = geometries[context_index]
    if (original_context is None or effective_context is None
            or aggregate_geometry_sha256(original_context) != context.get('originalGeometrySha256')
            or aggregate_geometry_sha256(effective_context) != context.get('effectiveOriginalGeometrySha256')
            or hashlib.sha256(packed_geometry_bytes(packed_context)).hexdigest() != context.get('packedGeometrySha256')):
        raise ValueError('Reviewed addressed-village context geometry changed')
    registry = context.get('currentOfficialRecord', {})
    code = live_context['tags'].get('ref:tn:codegeo')
    if (not isinstance(registry, dict) or not isinstance(code, str) or len(code) != 6 or not code.isdigit()
            or registry.get('sectorCode') != code or registry.get('delegationCode') != code[:4]
            or registry.get('governorateCode') != code[:2]
            or registry.get('sectorAr') != current_context['name']
            or registry.get('delegationAr') != record['expectedParentName']
            or not isinstance(registry.get('sourceRows'), dict) or len(registry['sourceRows']) != 3
            or any(type(row) is not int or row <= 0 for row in registry['sourceRows'].values())
            or sum(obj['tags'].get('ref:tn:codegeo') == code for obj in effective_sources.values()
                   if obj['kind'] == 'sector') != 1
            or context.get('originalContainsEveryMember') is not True
            or context.get('packagedContainsEveryMember') is not True):
        raise ValueError('Reviewed addressed-village coded identity or official parent changed')
    for member in record['members']:
        mid = member['id'];index = indices[mid];current = features[index]
        original, effective = original_sources.get(mid), effective_sources.get(mid)
        expected_kind = 'village' if mid == target else 'residential'
        if (original is None or effective is None or member.get('expectedKind') != expected_kind
                or original.get('kind') != expected_kind or effective.get('kind') != expected_kind
                or original['tags'] != member.get('expectedOriginalTags')
                or effective['tags'] != member.get('expectedEffectiveTags')
                or curation.get(mid) != member.get('expectedCuration')
                or any(current[k] != member.get(expected) for k, expected in (
                    ('kind', 'expectedKind'), ('sourceId', 'expectedSourceId'), ('name', 'expectedName'),
                    ('aliases', 'expectedAliases'), ('contextAliases', 'expectedContextAliases'),
                    ('lat', 'expectedLat'), ('lng', 'expectedLng')))
                or current['name'] != name['arabic']
                or current['governorateId'] != record['expectedGovernorateId']
                or current['parentName'] != record['expectedParentName']
                or current['delegationId'] != record['expectedDelegationId']
                or any(effective['tags'].get(key) != value for key, value in (
                    ('addr:place', name['arabic']), ('name:ar', name['arabic']), ('name:en', name['latin'])))):
            raise ValueError(f'Reviewed addressed-village member identity or explicit address changed: {mid}')
        if mid == target:
            original_geometry, effective_geometry = original.get('point'), effective.get('point')
            if (index < len(geometries) or current['hasBoundary']
                    or original.get('shape') is not None or effective.get('shape') is not None
                    or effective['tags'].get('place') != 'village'
                    or original_geometry is None or effective_geometry is None
                    or effective_geometry.geom_type != 'Point' or original_geometry.geom_type != 'Point'
                    or effective_geometry.x != current['lng'] or effective_geometry.y != current['lat']
                    or member.get('packedGeometrySha256') is not None):
                raise ValueError('Reviewed addressed-village point or its retained coordinates changed')
            packed = effective_geometry
        else:
            original_geometry, effective_geometry = original.get('shape'), effective.get('shape')
            if (index >= len(geometries) or not current['hasBoundary']
                    or effective['tags'].get('landuse') != 'residential'
                    or original_geometry is None or effective_geometry is None):
                raise ValueError('Reviewed addressed-village residential fragment changed')
            packed = geometries[index]
            if hashlib.sha256(packed_geometry_bytes(packed)).hexdigest() != member.get('packedGeometrySha256'):
                raise ValueError('Reviewed addressed-village packed residential geometry changed')
        if (aggregate_geometry_sha256(original_geometry) != member.get('originalGeometrySha256')
                or aggregate_geometry_sha256(effective_geometry) != member.get('effectiveOriginalGeometrySha256')
                or not original_context.covers(original_geometry)
                or not effective_context.covers(effective_geometry) or not packed_context.covers(packed)):
            raise ValueError('Reviewed addressed-village original geometry or context containment changed')
        nearest = min(timetables, key=lambda d: (distance(current['lat'], current['lng'], d), d['id']))
        if nearest['id'] != record['expectedDelegationId']:
            raise ValueError('Reviewed addressed-village members no longer share their manual prayer source')
    matching_villages = set()
    for sid, obj in effective_sources.items():
        if obj.get('kind') != 'village':
            continue
        geometry = obj.get('shape')
        if geometry is None:
            geometry = obj.get('point')
        values = names(obj['tags']) + [obj['tags'].get('addr:place', '')]
        if (geometry is not None and effective_context.intersects(geometry)
                and {picker_review_name_key(value) for value in values}
                & {picker_review_name_key(name['arabic']), picker_review_name_key(name['latin'])}):
            matching_villages.add(sid)
    if (record.get('expectedMatchingVillageSourceIds') != [target] or matching_villages != {target}):
        raise ValueError('Reviewed addressed locality has a competing same-name village source')


def validate_reviewed_named_settlement(manifest_path, record, features, geometries, timetables,
                                        original_sources, effective_sources, curation, indices):
    """Validate a pinned primary-source display identity, without a distance rule."""
    identifier, target = record['id'], record['targetPickerGroupId']
    if (record['expectedExistingMemberIds'] != [identifier]
            or record['expectedTargetMemberIds'] != [target]
            or record.get('settlementPointId') != target
            or record.get('expectedPointKind') not in ('hamlet', 'village')
            or record.get('expectedContextRole') != 'sector'):
        raise ValueError('Reviewed named settlement requires its exact point and residential groups')
    name = record.get('nameEvidence', {})
    if (not isinstance(name, dict) or any(not isinstance(name.get(k), str) or not name[k]
            for k in ('arabic', 'originalArabic', 'latin'))
            or name.get('arabicNameTag') != 'name:ar' or name.get('latinNameTag') not in ('name:fr', 'name:en')):
        raise ValueError('Missing reviewed explicit locality-name name evidence')
    context = record.get('codedContext', {})
    if not isinstance(context, dict):
        raise ValueError('Missing reviewed named-settlement administrative context')
    context_id = context.get('id')
    old_context, live_context = original_sources.get(context_id), effective_sources.get(context_id)
    context_index = indices.get(context_id)
    if (old_context is None or live_context is None or context_index is None
            or context_index >= len(geometries) or old_context.get('kind') != 'sector'
            or live_context.get('kind') != 'sector' or not features[context_index]['hasBoundary']
            or old_context['tags'] != context.get('expectedOriginalTags')
            or live_context['tags'] != context.get('expectedEffectiveTags')
            or curation.get(context_id) != context.get('expectedCuration')):
        raise ValueError('Reviewed named-settlement source context changed')
    current_context = features[context_index]
    if (any(current_context[k] != context.get(expected) for k, expected in (
            ('kind', 'expectedKind'), ('sourceId', 'expectedSourceId'), ('name', 'expectedName'),
            ('aliases', 'expectedAliases'), ('parentName', 'expectedParentName'),
            ('contextAliases', 'expectedContextAliases'), ('delegationId', 'expectedDelegationId'),
            ('lat', 'expectedLat'), ('lng', 'expectedLng')))
            or current_context['governorateId'] != record['expectedGovernorateId']):
        raise ValueError('Reviewed named-settlement displayed context changed')
    original_context, effective_context = old_context.get('shape'), live_context.get('shape')
    packed_context = geometries[context_index]
    if (original_context is None or effective_context is None
            or aggregate_geometry_sha256(original_context) != context.get('originalGeometrySha256')
            or aggregate_geometry_sha256(effective_context) != context.get('effectiveOriginalGeometrySha256')
            or hashlib.sha256(packed_geometry_bytes(packed_context)).hexdigest() != context.get('packedGeometrySha256')):
        raise ValueError('Reviewed named-settlement context geometry changed')
    registry = context.get('currentOfficialRecord', {})
    code = live_context['tags'].get('ref:tn:codegeo')
    if (not isinstance(registry, dict) or not isinstance(code, str) or len(code) != 6 or not code.isdigit()
            or registry.get('sectorCode') != code or registry.get('delegationCode') != code[:4]
            or registry.get('governorateCode') != code[:2]
            or registry.get('sectorAr') != current_context['name']
            or registry.get('sectorAr') != record['expectedParentName']
            or not isinstance(registry.get('sourceRows'), dict) or len(registry['sourceRows']) != 3
            or any(type(row) is not int or row <= 0 for row in registry['sourceRows'].values())
            or sum(obj['tags'].get('ref:tn:codegeo') == code for obj in effective_sources.values()
                   if obj['kind'] == 'sector') != 1
            or context.get('originalContainsEveryMember') is not True
            or context.get('packagedContainsEveryMember') is not True):
        raise ValueError('Reviewed named-settlement coded identity or official parent changed')
    for member in record['members']:
        mid = member['id'];index = indices[mid];current = features[index]
        original, effective = original_sources.get(mid), effective_sources.get(mid)
        expected_kind = record['expectedPointKind'] if mid == target else 'residential'
        if (original is None or effective is None or member.get('expectedKind') != expected_kind
                or original.get('kind') != expected_kind or effective.get('kind') != expected_kind
                or original['tags'] != member.get('expectedOriginalTags')
                or effective['tags'] != member.get('expectedEffectiveTags')
                or curation.get(mid) != member.get('expectedCuration')
                or any(current[k] != member.get(expected) for k, expected in (
                    ('kind', 'expectedKind'), ('sourceId', 'expectedSourceId'), ('name', 'expectedName'),
                    ('aliases', 'expectedAliases'), ('contextAliases', 'expectedContextAliases'),
                    ('lat', 'expectedLat'), ('lng', 'expectedLng')))
                or current['name'] != name['arabic']
                or current['governorateId'] != record['expectedGovernorateId']
                or current['parentName'] != record['expectedParentName']
                or current['delegationId'] != record['expectedDelegationId']
                or any(effective['tags'].get(key) != value for key, value in (
                    ('name:ar', name['arabic']), (name['latinNameTag'], name['latin'])))):
            raise ValueError(f'Reviewed named-settlement member identity or exact bilingual name changed: {mid}')
        if (original['tags'].get('name:ar') != name['originalArabic']
                or original['tags'].get(name['latinNameTag']) != name['latin']):
            raise ValueError('Reviewed named-settlement original bilingual identity changed')
        correction = member.get('expectedCuration')
        if name['originalArabic'] != name['arabic']:
            if (not isinstance(correction, dict) or correction.get('id') != mid
                    or correction.get('action') != 'name_tags'
                    or correction.get('expectedTags') != original['tags']
                    or correction.get('nameTags', {}).get('name:ar') != name['arabic']
                    or correction.get('nameTags', {}).get(name['latinNameTag']) != name['latin']
                    or name['originalArabic'] not in current['aliases']):
                raise ValueError('Reviewed named-settlement spelling lacks its exact name-only curation')
            review_files = [reference for evidence in correction['evidence'] if isinstance(evidence, dict)
                            for reference in evidence.get('reviewFiles', [])]
            if not review_files:
                raise ValueError('Reviewed named-settlement spelling lacks byte-pinned primary evidence')
            for reference in review_files:
                aggregate_review_file(manifest_path.parent, reference, 'named-settlement spelling evidence')
        elif correction is not None:
            raise ValueError('Unchanged named-settlement identity has an unexpected curation')
        if mid == target:
            original_geometry, effective_geometry = original.get('point'), effective.get('point')
            if (index < len(geometries) or current['hasBoundary']
                    or original.get('shape') is not None or effective.get('shape') is not None
                    or effective['tags'].get('place') != record['expectedPointKind']
                    or original_geometry is None or effective_geometry is None
                    or effective_geometry.geom_type != 'Point' or original_geometry.geom_type != 'Point'
                    or effective_geometry.x != current['lng'] or effective_geometry.y != current['lat']
                    or member.get('packedGeometrySha256') is not None):
                raise ValueError('Reviewed named-settlement point or its retained coordinates changed')
            packed = effective_geometry
        else:
            original_geometry, effective_geometry = original.get('shape'), effective.get('shape')
            if (index >= len(geometries) or not current['hasBoundary']
                    or effective['tags'].get('landuse') != 'residential'
                    or original_geometry is None or effective_geometry is None):
                raise ValueError('Reviewed named-settlement residential fragment changed')
            packed = geometries[index]
            if hashlib.sha256(packed_geometry_bytes(packed)).hexdigest() != member.get('packedGeometrySha256'):
                raise ValueError('Reviewed named-settlement packed residential geometry changed')
        if (aggregate_geometry_sha256(original_geometry) != member.get('originalGeometrySha256')
                or aggregate_geometry_sha256(effective_geometry) != member.get('effectiveOriginalGeometrySha256')
                or not original_context.covers(original_geometry)
                or not effective_context.covers(effective_geometry) or not packed_context.covers(packed)):
            raise ValueError('Reviewed named-settlement original geometry or context containment changed')
        nearest = min(timetables, key=lambda d: (distance(current['lat'], current['lng'], d), d['id']))
        if nearest['id'] != record['expectedDelegationId']:
            raise ValueError('Reviewed named-settlement members no longer share their manual prayer source')
    # A competing source anywhere in the pinned extract requires a new review,
    # even if its point does not touch this sector. POIs are distinguished by
    # their source tags in the frozen complete competitor inventory.
    keys = {picker_review_name_key(name['arabic']), picker_review_name_key(name['originalArabic']),
            picker_review_name_key(name['latin'])}
    for label, source_inventory in (('original', original_sources), ('effective', effective_sources)):
        matching_places, matching_residential = set(), set()
        context_places, context_residential = set(), set()
        context_shape = original_context if label == 'original' else effective_context
        for sid, obj in source_inventory.items():
            tags = obj['tags']
            if not ({picker_review_name_key(value) for value in names(tags)} & keys):
                continue
            geometry = obj.get('shape')
            if geometry is None:
                geometry = obj.get('point')
            if tags.get('place') in AREA_PLACES | {'city'}:
                matching_places.add(sid)
                if geometry is not None and context_shape.intersects(geometry):
                    context_places.add(sid)
            if tags.get('landuse') == 'residential':
                matching_residential.add(sid)
                if geometry is not None and context_shape.intersects(geometry):
                    context_residential.add(sid)
        if (record.get('expectedGlobalMatchingPlaceSourceIds') != [target]
                or record.get('expectedContextMatchingPlaceSourceIds') != [target]
                or record.get('expectedGlobalMatchingResidentialSourceIds') != [identifier]
                or record.get('expectedContextMatchingResidentialSourceIds') != [identifier]
                or matching_places != {target} or context_places != {target}
                or matching_residential != {identifier} or context_residential != {identifier}):
            raise ValueError(f'Reviewed named settlement has a competing {label} source identity')


def validate_reviewed_distributed_settlement(manifest_path, record, features, geometries, timetables,
                                             original_sources, effective_sources, curation, indices):
    """Associate only reviewed residential patches; never invent a village outline.

    Each patch keeps its own administrative context. The source village point
    supports the reviewed identity and must remain absent from the raw catalog.
    """
    from xml.etree import ElementTree

    target = record['targetPickerGroupId']
    member_ids = set(record['expectedExistingMemberIds'] + record['expectedTargetMemberIds'])
    if (record['expectedTargetMemberIds'] != [target]
            or len(record['expectedSourceGroups']) < 2
            or any(group['memberIds'] != [group['pickerGroupId']] for group in record['expectedSourceGroups'])):
        raise ValueError('Distributed settlement requires its exact singleton source groups')
    name = record.get('nameEvidence', {})
    if (not isinstance(name, dict) or any(not isinstance(name.get(k), str) or not name[k]
                                        for k in ('arabic', 'latin'))
            or any(name.get(k) != value for k, value in (
                ('residentialLatinTag', 'name'), ('canonicalArabicTag', 'name:ar'),
                ('villageArabicTag', 'name:ar'), ('villageLatinTag', 'name:fr')))):
        raise ValueError('Missing distributed-settlement bilingual source identity')
    metadata_fields = {'id', 'sourceId', 'name', 'aliases', 'kind', 'parentName', 'contextAliases',
                       'governorateId', 'lat', 'lng', 'delegationId', 'hasBoundary', 'bbox',
                       'areaKm2', 'pickerGroupId'}
    for member in record['members']:
        mid = member['id']; index = indices[mid]; current = features[index]
        original, effective = original_sources.get(mid), effective_sources.get(mid)
        expected = member.get('expectedMetadata')
        if (index >= len(geometries) or current['hasBoundary'] is not True
                or original is None or effective is None
                or original.get('kind') != 'residential' or effective.get('kind') != 'residential'
                or original['tags'] != member.get('expectedOriginalTags')
                or effective['tags'] != member.get('expectedEffectiveTags')
                or curation.get(mid) != member.get('expectedCuration')
                or not isinstance(expected, dict) or set(expected) != metadata_fields
                or {k: current.get(k) for k in metadata_fields} != expected
                or any(current[k] != member.get('expected' + k[0].upper() + k[1:]) for k in (
                    'kind', 'sourceId', 'name', 'aliases', 'parentName', 'contextAliases',
                    'governorateId', 'delegationId', 'lat', 'lng'))
                or current['governorateId'] != record['expectedGovernorateId']
                or current['delegationId'] != record['expectedDelegationId']
                or effective['tags'].get('landuse') != 'residential'
                or effective['tags'].get('name') != name['latin']):
            raise ValueError(f'Reviewed distributed-settlement member changed: {mid}')
        old_shape, live_shape = original.get('shape'), effective.get('shape')
        if (old_shape is None or live_shape is None
                or aggregate_geometry_sha256(old_shape) != member.get('originalGeometrySha256')
                or aggregate_geometry_sha256(live_shape) != member.get('effectiveOriginalGeometrySha256')
                or hashlib.sha256(packed_geometry_bytes(geometries[index])).hexdigest() != member.get('packedGeometrySha256')):
            raise ValueError('Reviewed distributed-settlement footprint changed')
        if mid == target:
            if (current['parentName'] != record['expectedParentName'] or current['name'] != name['arabic']
                    or effective['tags'].get('name:ar') != name['arabic']
                    or effective['tags'].get('addr:city') != name['latin']):
                raise ValueError('Reviewed distributed-settlement canonical identity changed')
        elif current['name'] != name['latin']:
            raise ValueError('Reviewed distributed-settlement patch name changed')
        nearest = min(timetables, key=lambda d: (distance(current['lat'], current['lng'], d), d['id']))
        if nearest['id'] != record['expectedDelegationId']:
            raise ValueError('Reviewed distributed-settlement manual prayer source changed')
    node = record.get('bilingualNode', {})
    node_id = node.get('id'); old_node = original_sources.get(node_id); live_node = effective_sources.get(node_id)
    if (old_node is None or live_node is None or node_id in indices
            or node.get('expectedAbsentFromRawCatalog') is not True or node.get('containedByMemberId') != target
            or node.get('expectedKind') != 'village'
            or old_node.get('kind') != 'village' or live_node.get('kind') != 'village'
            or live_node.get('sourceId') != node.get('expectedSourceId')
            or old_node['tags'] != node.get('expectedOriginalTags')
            or live_node['tags'] != node.get('expectedEffectiveTags')
            or curation.get(node_id) != node.get('expectedCuration')
            or live_node['tags'].get('place') != 'village'
            or live_node['tags'].get('name:ar') != name['arabic']
            or live_node['tags'].get('name:fr') != name['latin']):
        raise ValueError('Reviewed distributed-settlement source village changed')
    for source_node in (old_node, live_node):
        point = source_node.get('point')
        if (point is None or source_node.get('shape') is not None or point.geom_type != 'Point'
                or point.x != node.get('expectedLng') or point.y != node.get('expectedLat')
                or aggregate_geometry_sha256(point) != node.get('originalPointGeometrySha256')):
            raise ValueError('Reviewed distributed-settlement source point changed')
    point = live_node['point']
    if (not original_sources[target]['shape'].covers(point)
            or not effective_sources[target]['shape'].covers(point)
            or not geometries[indices[target]].covers(point)):
        raise ValueError('Reviewed distributed-settlement village anchor left its canonical patch')
    keys = {picker_review_name_key(name[k]) for k in ('arabic', 'latin')}
    for inventory in (original_sources, effective_sources):
        matching_places, matching_residential = set(), set()
        for sid, obj in inventory.items():
            if not ({picker_review_name_key(value) for value in names(obj['tags'])} & keys):
                continue
            if obj['tags'].get('place') in AREA_PLACES | {'city'}:
                matching_places.add(sid)
            if obj['tags'].get('landuse') == 'residential':
                matching_residential.add(sid)
        if (record.get('expectedMatchingPlaceSourceIds') != [node_id] or matching_places != {node_id}
                or record.get('expectedMatchingResidentialSourceIds') != sorted(member_ids)
                or matching_residential != member_ids):
            raise ValueError('Reviewed distributed settlement has a competing named source')
    edits = record.get('sourceEditEvidence', {})
    metadata = ElementTree.fromstring(aggregate_review_file(manifest_path.parent, edits.get('metadata'), 'distributed settlement edit metadata'))
    changeset = metadata.find('changeset')
    if (changeset is None or changeset.get('id') != str(edits.get('changesetId'))
            or {tag.get('k'): tag.get('v') for tag in changeset.findall('tag')} != edits.get('expectedChangesetTags')
            or edits.get('expectedChangesetTags', {}).get('source') != 'aerial imagery;local knowledge'):
        raise ValueError('Reviewed distributed-settlement local-knowledge edit changed')
    download = ElementTree.fromstring(aggregate_review_file(manifest_path.parent, edits.get('download'), 'distributed settlement edits'))
    required = edits.get('requiredEdits', [])
    histories = edits.get('latestHistoryRecords', [])
    source_ids = member_ids | {node_id}
    for label, entries in (('edits', required), ('histories', histories)):
        if (not isinstance(entries, list) or len(entries) != len(source_ids)
                or any(not isinstance(entry, dict) for entry in entries)
                or {entry.get('id') for entry in entries} != source_ids):
            raise ValueError(f'Distributed-settlement {label} inventory changed')
    for edit in required:
        _, kind, osm_id = edit['id'].split(':')
        matches = [obj for action in download if action.tag == edit.get('action')
                   for obj in action if obj.tag == kind and obj.get('id') == osm_id]
        if (len(matches) != 1 or matches[0].get('changeset') != str(edits['changesetId'])
                or matches[0].get('version') != str(edit.get('version'))
                or {tag.get('k'): tag.get('v') for tag in matches[0].findall('tag')} != edit.get('tags')
                or (kind == 'node' and (float(matches[0].get('lat')) != edit.get('lat')
                                       or float(matches[0].get('lon')) != edit.get('lng')))):
            raise ValueError('Reviewed distributed-settlement exact source edit changed')
    for history in histories:
        _, kind, osm_id = history['id'].split(':')
        document = ElementTree.fromstring(aggregate_review_file(manifest_path.parent, history.get('history'), 'distributed settlement source history'))
        objects = [obj for obj in document if obj.tag == kind and obj.get('id') == osm_id]
        latest = max(objects, key=lambda obj: int(obj.get('version'))) if objects else None
        if (latest is None or latest.get('version') != str(history.get('version'))
                or {tag.get('k'): tag.get('v') for tag in latest.findall('tag')} != history.get('tags')
                or history['tags'] != original_sources[history['id']]['tags']):
            raise ValueError('Reviewed distributed-settlement source history changed')
    documents, reviews = record.get('primaryDocuments'), record.get('researchEvidence')
    if not isinstance(documents, list) or not documents or not isinstance(reviews, list) or not reviews:
        raise ValueError('Distributed settlement lacks its independent documentary review')
    for document in documents:
        aggregate_review_file(manifest_path.parent, document.get('reference'), 'distributed settlement primary source')
    for reference in reviews:
        aggregate_review_file(manifest_path.parent, reference, 'distributed settlement identity review')


def validate_reviewed_two_patch_settlement(manifest_path, record, features, geometries, timetables,
                                           original_sources, effective_sources, curation, indices,
                                           source_sha256):
    """Associate two reviewed residential labels without claiming a village boundary."""
    from xml.etree import ElementTree

    directory = Path(manifest_path).parent
    identifier, target = record['id'], record['targetPickerGroupId']
    node = record.get('absorbedVillage', {})
    node_id = node.get('id')
    member_ids = {identifier, target}
    if (not all(mid.startswith('osm:way:') for mid in member_ids)
            or record['expectedExistingMemberIds'] != [identifier]
            or record['expectedTargetMemberIds'] != [target]
            or not isinstance(node_id, str) or not node_id.startswith('osm:node:')
            or record.get('exclusiveSupportingSourceIds') != [node_id]
            or node_id in indices or node.get('expectedAbsentFromRawCatalog') is not True
            or node.get('containedByMemberId') != target):
        raise ValueError('Reviewed two-patch settlement requires exact patches and an absorbed village')
    name = record.get('nameEvidence', {})
    if (not isinstance(name, dict) or set(name) != {'arabic', 'latin'}
            or any(not isinstance(value, str) or not value for value in name.values())):
        raise ValueError('Reviewed two-patch settlement lacks exact bilingual identity')
    facts = json.loads(aggregate_review_file(directory, record.get('sourceFacts'), 'two-patch current source facts'))
    if (facts.get('schemaVersion') != 1 or facts.get('sourceSha256') != source_sha256
            or facts.get('members') != record['members'] or facts.get('absorbedVillage') != node
            or facts.get('contexts') != record.get('contexts')):
        raise ValueError('Reviewed two-patch source fact inventory changed')
    for key in ('originalSourceFacts', 'contextAndCompleteNameSourceFacts'):
        original_facts = json.loads(aggregate_review_file(directory, facts.get(key), 'two-patch original PBF evidence'))
        if original_facts.get('sourceSha256') != source_sha256:
            raise ValueError('Reviewed two-patch original source snapshot changed')
    metadata_fields = {'id', 'sourceId', 'name', 'aliases', 'kind', 'parentName', 'contextAliases',
                       'governorateId', 'lat', 'lng', 'delegationId', 'hasBoundary', 'bbox',
                       'areaKm2', 'pickerGroupId'}
    for member in record['members']:
        mid = member['id']; index = indices[mid]; current = features[index]
        expected = member.get('expectedMetadata')
        original, effective = original_sources.get(mid), effective_sources.get(mid)
        if (index >= len(geometries) or not isinstance(expected, dict) or set(expected) != metadata_fields
                or current != expected or current['sourceId'] != 'osm' or current['kind'] != 'residential'
                or current['hasBoundary'] is not True or current['pickerGroupId'] != mid
                or current['name'] != name['arabic'] or current['governorateId'] != record['expectedGovernorateId']
                or current['parentName'] != record['expectedParentName']
                or current['delegationId'] != record['expectedDelegationId']
                or original is None or effective is None or curation.get(mid) is not None
                or member.get('expectedCuration') is not None):
            raise ValueError(f'Reviewed two-patch current metadata changed: {mid}')
        for obj, tag_key, shape_key in ((original, 'expectedOriginalTags', 'originalGeometrySha256'),
                                        (effective, 'expectedEffectiveTags', 'effectiveOriginalGeometrySha256')):
            footprint = obj.get('shape')
            if (obj.get('kind') != 'residential' or obj.get('sourceId') != (None if tag_key == 'expectedOriginalTags' else 'osm')
                    or obj['tags'] != member.get(tag_key) or obj['tags'].get('landuse') != 'residential'
                    or any(obj['tags'].get(key) != value for key, value in (
                        ('name', name['arabic']), ('name:ar', name['arabic']), ('name:en', name['latin'])))
                    or footprint is None or footprint.is_empty or not footprint.is_valid
                    or footprint.geom_type not in ('Polygon', 'MultiPolygon')
                    or aggregate_geometry_sha256(footprint) != member.get(shape_key)):
                raise ValueError('Reviewed two-patch exact source or current footprint changed')
        if hashlib.sha256(packed_geometry_bytes(geometries[index])).hexdigest() != member.get('packedGeometrySha256'):
            raise ValueError('Reviewed two-patch packed footprint changed')
        if min(timetables, key=lambda d: (distance(current['lat'], current['lng'], d), d['id']))['id'] != record['expectedDelegationId']:
            raise ValueError('Reviewed two-patch prayer default changed')
    for sources, key in ((original_sources, 'expectedOriginalTags'), (effective_sources, 'expectedEffectiveTags')):
        obj = sources.get(node_id); point = obj.get('point') if obj is not None else None
        if (obj is None or obj.get('kind') != 'village' or obj.get('sourceId') != (None if key == 'expectedOriginalTags' else 'osm')
                or obj.get('shape') is not None or obj['tags'] != node.get(key)
                or obj['tags'].get('place') != 'village' or curation.get(node_id) is not None
                or node.get('expectedCuration') is not None
                or any(obj['tags'].get(k) != v for k, v in (('name', name['arabic']), ('name:ar', name['arabic']), ('name:en', name['latin'])))
                or point is None or point.geom_type != 'Point'
                or [point.x, point.y] != node.get('coordinates')
                or aggregate_geometry_sha256(point) != node.get('pointGeometrySha256')
                or not sources[target]['shape'].covers(point) or sources[identifier]['shape'].covers(point)):
            raise ValueError('Reviewed two-patch absorbed village identity or canonical containment changed')
    point = effective_sources[node_id]['point']
    if not geometries[indices[target]].covers(point) or geometries[indices[identifier]].covers(point):
        raise ValueError('Reviewed two-patch absorbed point left its unique packed canonical patch')
    contexts = record.get('contexts', [])
    if (len(contexts) != 2 or [ctx.get('expectedKind') for ctx in contexts] != ['sector', 'delegation']
            or len({ctx.get('id') for ctx in contexts}) != 2):
        raise ValueError('Reviewed two-patch administrative context inventory changed')
    registry = json.loads(aggregate_review_file(directory, record.get('officialRegistry'), 'two-patch official context registry'))
    official = record.get('currentOfficialRecord', {})
    registry_rows = registry.get('records', registry.get('sectors', []))
    if ([row for row in registry_rows if row.get('sectorCode') == official.get('sectorCode')] != [official]
            or official.get('sectorAr') != record['expectedParentName']
            or official.get('sectorCode', '')[:4] != official.get('delegationCode')):
        raise ValueError('Reviewed two-patch official context identity changed')
    for context in contexts:
        cid = context['id']; kind = context['expectedKind']
        code = official['sectorCode'] if kind == 'sector' else official['delegationCode']
        for sources, tag_key in ((original_sources, 'expectedOriginalTags'), (effective_sources, 'expectedEffectiveTags')):
            obj = sources.get(cid); footprint = obj.get('shape') if obj is not None else None
            if (obj is None or obj.get('kind') != kind or obj.get('sourceId') != (None if tag_key == 'expectedOriginalTags' else 'osm')
                    or obj['tags'] != context.get(tag_key) or obj['tags'].get('ref:tn:codegeo') != code
                    or curation.get(cid) != context.get('expectedCuration') or footprint is None
                    or aggregate_geometry_sha256(footprint) != context.get('originalGeometrySha256')
                    or not all(footprint.covers(sources[mid]['shape']) for mid in member_ids)
                    or not footprint.covers(sources[node_id]['point'])
                    or sum(o['tags'].get('ref:tn:codegeo') == code for o in sources.values() if o.get('kind') == kind) != 1):
                raise ValueError('Reviewed two-patch exact administrative source or containment changed')
        if kind == 'sector':
            ci = indices.get(cid)
            if (ci is None or ci >= len(geometries) or features[ci] != context.get('expectedMetadata')
                    or hashlib.sha256(packed_geometry_bytes(geometries[ci])).hexdigest() != context.get('packedGeometrySha256')
                    or not all(geometries[ci].covers(geometries[indices[mid]]) for mid in member_ids)
                    or not geometries[ci].covers(point)):
                raise ValueError('Reviewed two-patch packed context changed')
        elif cid in indices:
            raise ValueError('Reviewed two-patch source delegation unexpectedly became a raw locality')
    keys = {picker_review_name_key(value) for value in name.values()}
    for sources in (original_sources, effective_sources):
        places, patches = set(), set()
        for sid, obj in sources.items():
            if not ({picker_review_name_key(value) for value in names(obj['tags'])} & keys):
                continue
            if obj['tags'].get('place') in AREA_PLACES | {'city'}: places.add(sid)
            if obj['tags'].get('landuse') == 'residential': patches.add(sid)
        if (places != {node_id} or patches != member_ids
                or record.get('expectedMatchingPlaceSourceIds') != [node_id]
                or record.get('expectedMatchingResidentialSourceIds') != sorted(member_ids)):
            raise ValueError('Reviewed two-patch settlement has a competing eligible named source')
    excluded = facts.get('excludedSameNameObjects', [])
    if ({item.get('id') for item in excluded} != set(record.get('excludedSameNameSourceIds', [])) or len(excluded) != 2
            or any(item['id'] in indices or item['id'] in original_sources or item['id'] in effective_sources for item in excluded)
            or sorted(item['tags'].get('historic', item['tags'].get('building')) for item in excluded) != ['mosque', 'ruins']):
        raise ValueError('Reviewed two-patch fortress or mosque must remain outside the eligible catalog')
    events = record.get('sourceEditEvidence', [])
    if [event.get('role') for event in events] != ['surveyed_village_creation', 'joint_patch_creation', 'duplicate_label_discussion']:
        raise ValueError('Reviewed two-patch source history scope changed')
    mapper = None
    source_ids = member_ids | {node_id}
    for number, event in enumerate(events):
        xml = ElementTree.fromstring(aggregate_review_file(directory, event.get('metadata'), 'two-patch source edit metadata'))
        changeset = xml.find('changeset')
        tags = {t.get('k'): t.get('v') for t in changeset.findall('tag')} if changeset is not None else {}
        if (changeset is None or changeset.get('id') != str(event.get('changesetId'))
                or changeset.get('uid') != event.get('mapperId') or tags != event.get('expectedTags')
                or tags.get('source') != ('aerial imagery;survey', 'aerial imagery', None)[number]):
            raise ValueError('Reviewed two-patch source declaration changed')
        if number == 0: mapper = changeset.get('uid')
        if number == 1 and mapper != changeset.get('uid'):
            raise ValueError('Reviewed village and residential creation mapper changed')
        download = ElementTree.fromstring(aggregate_review_file(directory, event.get('download'), 'two-patch actual source edits'))
        actual = []
        for action in download:
            for obj in action:
                sid = f"osm:{obj.tag}:{obj.get('id')}"
                if sid in source_ids:
                    actual.append({'id': sid, 'action': action.tag, 'version': int(obj.get('version')),
                                   'changeset': int(obj.get('changeset')), 'mapperId': obj.get('uid'),
                                   'tags': {tag.get('k'): tag.get('v') for tag in obj.findall('tag')},
                                   'nodeReferences': [nd.get('ref') for nd in obj.findall('nd')],
                                   'coordinates': [float(obj.get('lon')), float(obj.get('lat'))] if obj.tag == 'node' else None})
        expected_ids = ({node_id}, member_ids, source_ids)[number]
        if (sorted(actual, key=lambda row: row['id']) != event.get('requiredEdits')
                or len(actual) != len(expected_ids) or {row['id'] for row in actual} != expected_ids
                or any(row['changeset'] != event['changesetId'] or row['mapperId'] != event['mapperId'] for row in actual)
                or (number < 2 and any(row['action'] != 'create' or row['version'] != 1 or row['tags'].get('name') != name['latin'] for row in actual))):
            raise ValueError('Reviewed two-patch per-object named source edits changed')
        if number == 2:
            comments = [{'id': c.get('id'), 'mapperId': c.get('uid'),
                         'textSha256': hashlib.sha256((c.findtext('text') or '').encode()).hexdigest()}
                        for c in changeset.findall('discussion/comment')]
            if not comments or comments != event.get('discussion'):
                raise ValueError('Reviewed explicit duplicate-representation discussion changed')
    histories = record.get('sourceHistories', [])
    if len(histories) != 3 or {item.get('id') for item in histories} != source_ids:
        raise ValueError('Reviewed two-patch exact history inventory changed')
    for history in histories:
        sid = history['id']; _, kind, oid = sid.split(':')
        document = ElementTree.fromstring(aggregate_review_file(directory, history.get('reference'), 'two-patch source history'))
        objects = [obj for obj in document if obj.tag == kind and obj.get('id') == oid]
        actual = [{'version': int(obj.get('version')), 'changeset': int(obj.get('changeset')),
                   'tags': {tag.get('k'): tag.get('v') for tag in obj.findall('tag')},
                   'nodeReferences': [nd.get('ref') for nd in obj.findall('nd')],
                   'coordinates': [float(obj.get('lon')), float(obj.get('lat'))] if kind == 'node' else None}
                  for obj in sorted(objects, key=lambda obj: int(obj.get('version')))]
        if (not actual or actual != history.get('records') or actual[-1]['tags'] != original_sources[sid]['tags']
                or (kind == 'node' and any(row['coordinates'] != node['coordinates'] for row in actual))):
            raise ValueError('Reviewed two-patch original source history changed')
    for key in ('primaryDocuments', 'researchEvidence'):
        references = record.get(key, [])
        if len(references) < 2:
            raise ValueError('Reviewed two-patch identity lacks its independent primary-source review')
        for reference in references:
            aggregate_review_file(directory, reference, 'two-patch identity evidence')

def validate_reviewed_surveyed_hamlet(manifest_path, record, features, geometries, timetables,
                                     original_sources, effective_sources, curation, indices,
                                     source_sha256, after_context=False):
    """Validate an exact surveyed-source association, not a hamlet perimeter.

    The two residential footprints and existing hamlet point stay independent.
    Their reviewed context decisions must finish before the display-only join.
    """
    from xml.etree import ElementTree

    directory = Path(manifest_path).parent
    target = record['targetPickerGroupId']
    left = record['expectedExistingMemberIds']
    source_groups = record['expectedSourceGroups']
    member_ids = set(left + [target])
    if (len(left) != 2 or len(set(left)) != 2 or not all(i.startswith('osm:way:') for i in left)
            or not target.startswith('osm:node:') or record['expectedTargetMemberIds'] != [target]
            or source_groups != [{'pickerGroupId': mid, 'memberIds': [mid]} for mid in left]
            or record['id'] != left[0] or record.get('applicationPhase') != 'after_reviewed_locality_contexts'):
        raise ValueError('Surveyed hamlet needs its exact two patches and existing point')
    actual_groups = defaultdict(set)
    for feature in features:
        actual_groups[feature['pickerGroupId']].add(feature['id'])
    if any(actual_groups.get(mid) != {mid} for mid in member_ids):
        raise ValueError('Reviewed surveyed-hamlet singleton group changed')
    name = record.get('nameEvidence', {})
    if (not isinstance(name, dict) or set(name) != {'arabic', 'latin', 'historicalArabic', 'historicalLatin'}
            or any(not isinstance(value, str) or not value for value in name.values())):
        raise ValueError('Surveyed hamlet lacks its exact current and historical names')
    facts = json.loads(aggregate_review_file(directory, record.get('sourceFacts'), 'surveyed hamlet source facts'))
    if (facts.get('schemaVersion') != 1 or facts.get('sourceSha256') != source_sha256
            or not isinstance(facts.get('records'), list)
            or len(facts['records']) != 3 or {item.get('id') for item in facts['records']} != member_ids):
        raise ValueError('Surveyed hamlet source-fact inventory changed')
    fact_records = {item['id']: item for item in facts['records']}
    members = record.get('members', [])
    if len(members) != 3 or {item.get('id') for item in members} != member_ids:
        raise ValueError('Surveyed hamlet exact member inventory changed')
    metadata_fields = {'id', 'sourceId', 'name', 'aliases', 'kind', 'parentName', 'contextAliases',
                       'governorateId', 'lat', 'lng', 'delegationId', 'hasBoundary', 'pickerGroupId'}
    for member in members:
        mid = member['id']; index = indices.get(mid)
        if index is None:
            raise ValueError('Reviewed surveyed-hamlet raw member disappeared')
        current = features[index]; original = original_sources.get(mid); effective = effective_sources.get(mid)
        kind = 'hamlet' if mid == target else 'residential'
        before, after, fact = member.get('expectedBeforeContext'), member.get('expectedMetadata'), fact_records[mid]
        fields = metadata_fields | (set() if mid == target else {'bbox', 'areaKm2'})
        rule = curation.get(mid)
        if (original is None or effective is None or original.get('kind') != kind or effective.get('kind') != kind
                or original['tags'] != member.get('expectedOriginalTags')
                or effective['tags'] != member.get('expectedEffectiveTags') or original['tags'] != effective['tags']
                or not isinstance(before, dict) or not isinstance(after, dict)
                or set(before) != fields or set(after) != fields
                or current != (after if after_context else before)
                or after.get('kind') != kind or after.get('sourceId') != 'osm'
                or after.get('hasBoundary') is not (mid != target)
                or after.get('pickerGroupId') != mid or after.get('name') != name['arabic']
                or after.get('governorateId') != record['expectedGovernorateId']
                or after.get('delegationId') != record['expectedDelegationId']
                or after.get('parentName') != record['expectedParentName']
                or {key for key in fields if before[key] != after[key]} != {'parentName', 'contextAliases'}
                or not isinstance(rule, dict) or rule.get('action') != 'locality_context'
                or rule != member.get('expectedCuration') or rule.get('expectedTags') != original['tags']
                or any(fact.get(key) != member.get(key) for key in (
                    'expectedMetadata', 'expectedOriginalTags', 'expectedEffectiveTags', 'expectedCuration'))
                or effective['tags'].get('name') != name['arabic']
                or any(effective['tags'].get(key) != name['arabic'] for key in ('name:ar', 'name:aeb'))
                or any(effective['tags'].get(key) != name['latin'] for key in ('name:en', 'name:fr'))):
            raise ValueError(f'Reviewed surveyed-hamlet metadata or context changed: {mid}')
        context = json.loads(aggregate_review_file(directory, rule.get('reviewEvidence'), 'surveyed hamlet existing context proof'))
        matched = [item for item in context.get('records', []) if item.get('decision', {}).get('id') == mid]
        if (context.get('sourceSha256') != source_sha256 or len(matched) != 1
                or matched[0].get('expectedFeature') != before
                or matched[0].get('decision') != {key: value for key, value in rule.items() if key != 'reviewEvidence'}):
            raise ValueError('Surveyed hamlet before-context proof changed')
        if mid == target:
            if index < len(geometries) or effective['tags'].get('place') != 'hamlet':
                raise ValueError('Surveyed hamlet canonical raw point changed kind')
            for obj in (original, effective):
                point = obj.get('point')
                if (obj.get('shape') is not None or point is None or point.geom_type != 'Point'
                        or point.x != after['lng'] or point.y != after['lat']
                        or aggregate_geometry_sha256(point) != member.get('originalPointGeometrySha256')
                        or member.get('originalPointGeometrySha256') != fact.get('originalPointGeometrySha256')):
                    raise ValueError('Reviewed surveyed-hamlet source point moved')
        else:
            if index >= len(geometries) or effective['tags'].get('landuse') != 'residential':
                raise ValueError('Surveyed hamlet residential member lost its footprint')
            for obj, key in ((original, 'originalGeometrySha256'), (effective, 'effectiveOriginalGeometrySha256')):
                source_shape = obj.get('shape')
                if (source_shape is None or source_shape.geom_type not in ('Polygon', 'MultiPolygon')
                        or source_shape.is_empty or not source_shape.is_valid
                        or aggregate_geometry_sha256(source_shape) != member.get(key) or member.get(key) != fact.get(key)):
                    raise ValueError('Reviewed surveyed-hamlet original footprint changed')
            packed_sha = hashlib.sha256(packed_geometry_bytes(geometries[index])).hexdigest()
            if packed_sha != member.get('packedGeometrySha256') or packed_sha != fact.get('packedGeometrySha256'):
                raise ValueError('Reviewed surveyed-hamlet packed footprint changed')
        nearest = min(timetables, key=lambda d: (distance(current['lat'], current['lng'], d), d['id']))
        if nearest['id'] != record['expectedDelegationId']:
            raise ValueError('Reviewed surveyed-hamlet prayer default changed')
    keys = {picker_review_name_key(value) for value in name.values()}
    for inventory in (original_sources, effective_sources):
        places, residential = set(), set()
        for sid, obj in inventory.items():
            if not ({picker_review_name_key(value) for value in names(obj['tags'])} & keys):
                continue
            if obj['tags'].get('place') in AREA_PLACES | {'city'}:
                places.add(sid)
            if obj['tags'].get('landuse') == 'residential':
                residential.add(sid)
        if (record.get('expectedMatchingPlaceSourceIds') != [target] or places != {target}
                or record.get('expectedMatchingResidentialSourceIds') != sorted(left) or residential != set(left)
                or facts.get('expectedMatchingPlaceSourceIds') != [target]
                or facts.get('expectedMatchingResidentialSourceIds') != sorted(left)):
            raise ValueError('Reviewed surveyed hamlet has a competing named source')
    edits = record.get('sourceEditEvidence', {})
    events, histories = edits.get('events'), edits.get('histories')
    if (not isinstance(events, list) or len(events) != 3
            or [event.get('role') for event in events] != ['residential_creation', 'hamlet_creation_and_refinement', 'name_cleanup']
            or len({event.get('changesetId') for event in events}) != 3
            or not isinstance(histories, list) or len(histories) != 3
            or {history.get('id') for history in histories} != member_ids):
        raise ValueError('Surveyed hamlet lacks its full reviewed edit/history chain')
    history_records = {}
    for history in histories:
        mid = history['id']; _, kind, osm_id = mid.split(':')
        document = ElementTree.fromstring(aggregate_review_file(directory, history.get('reference'), 'surveyed hamlet source history'))
        objects = [obj for obj in document if obj.tag == kind and obj.get('id') == osm_id]
        actual = [{'version': int(obj.get('version')), 'changeset': int(obj.get('changeset')),
                   'tags': {tag.get('k'): tag.get('v') for tag in obj.findall('tag')},
                   'nodeReferences': [node.get('ref') for node in obj.findall('nd')],
                   'point': [float(obj.get('lon')), float(obj.get('lat'))] if kind == 'node' else None}
                  for obj in sorted(objects, key=lambda obj: int(obj.get('version')))]
        if (not actual or actual != history.get('records') or actual[-1]['tags'] != original_sources[mid]['tags']
                or any(row['nodeReferences'] != actual[-1]['nodeReferences'] for row in actual)
                or (mid == target and any(row['point'] != [features[indices[mid]]['lng'], features[indices[mid]]['lat']] for row in actual))
                or (mid != target and actual[-1]['nodeReferences'] != fact_records[mid].get('pbfObject', {}).get('nodes'))):
            raise ValueError('Reviewed surveyed-hamlet source history changed')
        history_records[mid] = actual
    node_coordinates, creation_coordinates, refined_coordinates = {}, {}, {}
    survey_uid = events[0].get('expectedMapperId')
    if not isinstance(survey_uid, str) or not survey_uid:
        raise ValueError('Missing reviewed surveyed-source mapper identity')
    for number, event in enumerate(events):
        document = ElementTree.fromstring(aggregate_review_file(directory, event.get('metadata'), 'surveyed hamlet changeset metadata'))
        changeset = document.find('changeset')
        tags = {tag.get('k'): tag.get('v') for tag in changeset.findall('tag')} if changeset is not None else {}
        if (changeset is None or changeset.get('id') != str(event.get('changesetId'))
                or changeset.get('uid') != event.get('expectedMapperId') or tags != event.get('expectedChangesetTags')
                or (number < 2 and (event.get('expectedMapperId') != survey_uid or tags.get('source') != 'survey;aerial imagery'))
                or (number == 2 and ('source' in tags or event.get('newSurveyClaim') is not False))):
            raise ValueError('Reviewed surveyed-hamlet source declaration changed')
        download = ElementTree.fromstring(aggregate_review_file(directory, event.get('download'), 'surveyed hamlet actual edits'))
        found = {}
        for action in download:
            for obj in action:
                mid = f"osm:{obj.tag}:{obj.get('id')}"
                if obj.tag == 'node' and action.tag in ('create', 'modify') and obj.get('lon') is not None and obj.get('lat') is not None:
                    coordinate = [float(obj.get('lon')), float(obj.get('lat'))]
                    node_coordinates[obj.get('id')] = coordinate
                    if number == 0:
                        creation_coordinates[obj.get('id')] = coordinate
                    elif number == 1:
                        refined_coordinates[obj.get('id')] = coordinate
                if mid not in member_ids:
                    continue
                if mid in found or obj.get('changeset') != str(event['changesetId']) or obj.get('uid') != event['expectedMapperId']:
                    raise ValueError('Reviewed surveyed-hamlet actual edit inventory changed')
                found[mid] = {'id': mid, 'action': action.tag, 'version': int(obj.get('version')),
                              'tags': {tag.get('k'): tag.get('v') for tag in obj.findall('tag')},
                              'nodeReferences': [node.get('ref') for node in obj.findall('nd')],
                              'point': [float(obj.get('lon')), float(obj.get('lat'))] if obj.tag == 'node' else None}
        expected_ids = (set(left), {left[0], target}, member_ids)[number]
        required = event.get('requiredEdits', [])
        if (not isinstance(required, list) or len(required) != len(expected_ids)
                or set(found) != expected_ids or {item.get('id') for item in required} != expected_ids
                or any(found[item['id']] != item for item in required)):
            raise ValueError('Reviewed surveyed-hamlet exact named edits changed')
        for mid, edited in found.items():
            matches = [row for row in history_records[mid] if row['version'] == edited['version']]
            if (len(matches) != 1 or matches[0]['changeset'] != event['changesetId']
                    or any(matches[0][key] != edited[key] for key in ('tags', 'nodeReferences', 'point'))):
                raise ValueError('Surveyed hamlet edit differs from its exact source history')
            if number == 0 and (edited['action'] != 'create' or edited['version'] != 1
                                or edited['tags'].get('name') != name['latin']
                                or edited['tags'].get('name:aeb') != name['historicalArabic']):
                raise ValueError('Surveyed hamlet original joint patch naming changed')
            if number == 1 and (edited['action'] != ('create' if mid == target else 'modify')
                                or edited['tags'].get('name') != name['historicalLatin']
                                or edited['tags'].get('name:aeb') != name['historicalArabic']):
                raise ValueError('Surveyed hamlet point/refinement naming changed')
    for mid in left:
        refs = history_records[mid][-1]['nodeReferences']
        if (not refs or refs[0] != refs[-1]
                or any(ref not in creation_coordinates or ref not in refined_coordinates or ref not in node_coordinates for ref in refs)):
            raise ValueError('Surveyed hamlet lacks the complete residential coordinate chain')
        created = Polygon([creation_coordinates[ref] for ref in refs])
        refined = Polygon([refined_coordinates[ref] for ref in refs])
        latest = Polygon([node_coordinates[ref] for ref in refs])
        if (not created.is_valid or not refined.is_valid or not latest.is_valid
                or created.equals(refined) or not refined.equals(original_sources[mid]['shape'])
                or not latest.equals(refined)
                or not created.equals(shape(fact_records[mid]['creationGeometry']))
                or not refined.equals(shape(fact_records[mid]['refined2024Geometry']))):
            raise ValueError('Surveyed hamlet full coordinate edits no longer reproduce the current source')
    reviews = record.get('researchEvidence')
    if not isinstance(reviews, list) or len(reviews) < 2:
        raise ValueError('Surveyed hamlet lacks its independent identity review')
    for reference in reviews:
        aggregate_review_file(directory, reference, 'surveyed hamlet independent identity review')


def apply_deferred_reviewed_hamlet_groups(manifest_path, records, features, geometries, timetables,
                                         original_sources, effective_sources, curation, source_sha256):
    """Recheck all deferred inputs after context correction, then change group IDs only."""
    indices = {feature['id']: index for index, feature in enumerate(features)}
    for record in records:
        validate_reviewed_surveyed_hamlet(manifest_path, record, features, geometries, timetables,
                                          original_sources, effective_sources, curation, indices,
                                          source_sha256, after_context=True)
    for record in records:
        for mid in record['expectedExistingMemberIds']:
            features[indices[mid]]['pickerGroupId'] = record['targetPickerGroupId']
    combined = defaultdict(list)
    for feature in features:
        combined[feature['pickerGroupId']].append(feature['id'])
    return [{'pickerGroupId': identifier, 'ids': sorted(member_ids)}
            for identifier, member_ids in sorted(combined.items())
            if len(member_ids) > 1 or identifier.startswith('delegation:')]


def validate_reviewed_complete_locality_group_owner(record, directory, features, indices, geometries,
                                                     groups, original_sources, effective_sources,
                                                     curation, timetables, source_sha256):
    """Choose an existing village member as owner of one complete reviewed group.

    The sector and settlement may overlap without sharing a boundary. Their
    exact separate source and packed footprints, metadata and coordinates stay
    intact; only the group's owner changes to the retained settlement member.
    """
    identifier, target = record['id'], record['targetPickerGroupId']
    member_ids = {identifier, target}
    keys = {'id', 'status', 'method', 'targetPickerGroupId', 'expectedExistingMemberIds', 'members',
            'expectedGovernorateId', 'canonicalName', 'officialRegistry', 'officialSectorRecord',
            'expectedSourceNamePeerIds', 'overlap', 'manualSelection', 'sourceReview', 'qualifications'}
    qualifications = {'polygonsHaveDifferentExtents': True, 'noBoundaryCertification': True,
                      'allRawCoordinatesRetained': True, 'prayerSourceIsNotDisplayIdentity': True,
                      'ownerIsExistingVillagePolygon': True}
    if (set(record) != keys or record['method'] != 'reviewed_complete_locality_group_owner'
            or record['status'] != 'eligible_proposal' or type(record['expectedGovernorateId']) is not int
            or record['expectedExistingMemberIds'] != sorted(member_ids)
            or groups.get(identifier) != member_ids or groups.get(target, set())
            or not member_ids <= set(indices) or record['qualifications'] != qualifications
            or not isinstance(record['canonicalName'], str) or not clean(record['canonicalName'])
            or not isinstance(record['members'], list) or len(record['members']) != 2
            or {row.get('id') for row in record['members']} != member_ids):
        raise ValueError('Reviewed complete local group or village-owner scope changed')
    source_review = json.loads(aggregate_review_file(directory, record['sourceReview'], 'complete local group source review'))
    if (source_review.get('schemaVersion') != 1 or source_review.get('sourceSha256') != source_sha256
            or source_review.get('memberIds') != sorted(member_ids)
            or source_review.get('canonicalName') != record['canonicalName']
            or source_review.get('proposedOwnerId') != target):
        raise ValueError('Reviewed complete local group source identity changed')
    registry = json.loads(aggregate_review_file(directory, record['officialRegistry'], 'complete local group official registry'))
    official = record['officialSectorRecord']
    code = official.get('sectorCode') if isinstance(official, dict) else None
    if (not isinstance(code, str) or not re.fullmatch(r'[0-9]{6}', code)
            or [row for row in registry['sectors'] if row.get('sectorCode') == code] != [official]
            or official.get('sectorAr') != record['canonicalName']
            or official.get('delegationCode') != code[:4] or official.get('governorateCode') != code[:2]):
        raise ValueError('Reviewed complete local group official sector changed')
    protected = {}
    for member in record['members']:
        mid = member['id'];index = indices[mid]
        current = features[index]
        original, effective = original_sources.get(mid), effective_sources.get(mid)
        expected_kind = 'sector' if mid == identifier else 'village'
        expected_metadata = member.get('expectedMetadata')
        metadata = {key: value for key, value in current.items() if key not in {'pickerGroupId', 'offset', 'length'}}
        if (set(member) != {'id', 'expectedMetadata', 'expectedOriginalTags', 'expectedEffectiveTags',
                           'expectedCuration', 'originalGeometrySha256', 'packedGeometrySha256'}
                or index >= len(geometries) or original is None or effective is None
                or original.get('sourceId') is not None or effective.get('sourceId') != 'osm'
                or current.get('sourceId') != 'osm' or current.get('kind') != expected_kind
                or original.get('kind') != expected_kind or effective.get('kind') != expected_kind
                or not current.get('hasBoundary') or current.get('name') != record['canonicalName']
                or current.get('governorateId') != record['expectedGovernorateId']
                or current.get('pickerGroupId') != identifier or metadata != expected_metadata
                or original['tags'] != member['expectedOriginalTags']
                or effective['tags'] != member['expectedEffectiveTags']
                or curation.get(mid) != member['expectedCuration']
                or names(original['tags'])[0] not in [current['name'], *current.get('aliases', [])]
                or names(effective['tags'])[0] != record['canonicalName']):
            raise ValueError('Reviewed complete local member metadata, name or source changed')
        for source in (original, effective):
            geometry = source.get('shape')
            if (geometry is None or geometry.is_empty
                    or aggregate_geometry_sha256(geometry) != member['originalGeometrySha256']):
                raise ValueError('Reviewed complete local member source footprint changed')
        if hashlib.sha256(packed_geometry_bytes(geometries[index])).hexdigest() != member['packedGeometrySha256']:
            raise ValueError('Reviewed complete local member packed footprint changed')
        if mid == identifier:
            decision = member['expectedCuration']
            if (not isinstance(decision, dict) or decision.get('id') != mid
                    or decision.get('action') != 'name_tags' or 'manualPointId' in decision
                    or decision.get('expectedTags') != original['tags']
                    or any(source['tags'].get('ref:tn:codegeo') != code for source in (original, effective))
                    or any(sum(source['kind'] == 'sector' and source['tags'].get('ref:tn:codegeo') == code
                               for source in sources.values()) != 1 for sources in (original_sources, effective_sources))):
                raise ValueError('Reviewed complete local coded sector or name-only curation changed')
            references = [ref for evidence in decision.get('evidence', []) if isinstance(evidence, dict)
                          for ref in evidence.get('reviewFiles', [])]
            if not references:
                raise ValueError('Reviewed complete local sector correction lacks pinned evidence')
            for reference in references:
                aggregate_review_file(directory, reference, 'complete local sector name evidence')
        elif (member['expectedCuration'] is not None or original['tags'] != effective['tags']
                or original['tags'].get('place') != 'village'):
            raise ValueError('Reviewed complete local village is no longer unchanged')
        nearest = min(timetables, key=lambda d: (distance(current['lat'], current['lng'], d), d['id']))
        if current['delegationId'] != nearest['id']:
            raise ValueError('Reviewed complete local member nearest prayer source changed')
        protected[mid] = metadata
    peer_keys = {norm(value) for mid in member_ids for value in names(original_sources[mid]['tags'])}
    peer_keys.add(norm(record['canonicalName']))
    for sources in (original_sources, effective_sources):
        peers = sorted(mid for mid, source in sources.items()
                       if peer_keys & {norm(value) for value in names(source['tags'])})
        if peers != record['expectedSourceNamePeerIds'] or peers != sorted(member_ids):
            raise ValueError('Reviewed complete local group gained a source name peer')
    overlaps = {}
    for phase, sector, village in (
            ('original', original_sources[identifier]['shape'], original_sources[target]['shape']),
            ('packed', geometries[indices[identifier]], geometries[indices[target]])):
        # Measure existing vertices at full operation precision. Source hashes
        # and packed hashes above independently freeze the actual footprints.
        sector, village = set_precision(sector, 0), set_precision(village, 0)
        intersection = sector.intersection(village)
        overlaps[phase] = {'sectorCoversVillage': sector.covers(village),
                           'villageCoversSector': village.covers(sector),
                           'villageFractionInsideSector': intersection.area / village.area,
                           'sectorFractionInsideVillage': intersection.area / sector.area}
        expected = record['overlap'].get(phase, {})
        if (intersection.area <= 0 or sector.equals(village) or set(expected) != set(overlaps[phase])
                or any(type(expected[key]) is not bool or expected[key] != overlaps[phase][key]
                       for key in ('sectorCoversVillage', 'villageCoversSector'))
                or any(type(expected[key]) not in (int, float) or not math.isfinite(expected[key])
                       or not math.isclose(expected[key], overlaps[phase][key], rel_tol=1e-12, abs_tol=0)
                       for key in ('villageFractionInsideSector', 'sectorFractionInsideVillage'))):
            raise ValueError('Reviewed complete local partial overlap changed')
    owner = features[indices[target]]
    point = Point(owner['lng'], owner['lat'])
    manual = {'representativeId': target, 'lat': owner['lat'], 'lng': owner['lng'],
              'expectedNearestSourceId': owner['delegationId']}
    if (record['manualSelection'] != manual or set(record['overlap']) != {'original', 'packed'}
            or any(not original_sources[mid]['shape'].covers(point)
                   or not geometries[indices[mid]].covers(point) for mid in member_ids)):
        raise ValueError('Reviewed complete local village representative changed')
    return {'id': identifier, 'identityMethod': record['method'], 'retainedOwnerId': target,
            'manualSelection': manual, 'overlap': overlaps,
            'finalGroups': [{'id': target, 'memberIds': sorted(member_ids)}],
            'protectedMetadata': protected}



def validate_reviewed_sector_settlement_display_identity(record, directory, features, indices, geometries,
                                                         groups, original_sources, effective_sources,
                                                         curation, timetables, source_sha256):
    """Validate a reviewed sector/settlement display-identity grouping."""
    RECORD_KEYS = {
        'id', 'status', 'method', 'targetPickerGroupId', 'expectedExistingMemberIds',
        'expectedTargetMemberIds', 'members', 'expectedGovernorateId', 'canonicalName',
        'officialRegistry', 'officialSectorRecord', 'pointContextOfficialRecord',
        'codedParent', 'pointContext', 'expectedSourceNamePeerIds', 'spatialRelationship',
        'manualSelection', 'sourceReview', 'qualifications'
    }
    MEMBER_KEYS = {
        'id', 'expectedMetadata', 'expectedOriginalTags', 'expectedEffectiveTags',
        'expectedCuration', 'originalGeometrySha256', 'packedGeometrySha256'
    }
    QUALIFICATIONS = {
        'displayIdentityOnly': True,
        'noBoundaryCertification': True,
        'allRawCoordinatesRetained': True,
        'prayerSourceIsNotDisplayIdentity': True,
        'ownerIsExistingSuburbPoint': True
    }
    GEOMETRY_SPEC_KEYS = {'id', 'expectedKind', 'expectedTags', 'originalGeometrySha256'}
    SPATIAL_KEYS = {'pointOutsideSector', 'representativeDistanceKm'}
    MANUAL_KEYS = {'representativeId', 'lat', 'lng', 'expectedNearestSourceId'}

    def fail(message):
        raise ValueError(message)

    def is_number(value):
        return isinstance(value, (int, float)) and not isinstance(value, bool)

    def names_of(tags, label):
        if not isinstance(tags, dict):
            fail(f'{label} tags are not an object')
        try:
            values = list(names(tags))
        except Exception as exc:
            raise ValueError(f'{label} names cannot be read') from exc
        if not values or any(not isinstance(value, str) for value in values):
            fail(f'{label} names are malformed')
        return values

    def load_ref(reference, label):
        if (not isinstance(reference, dict) or set(reference) != {'file', 'sha256'}
                or not isinstance(reference['file'], str) or not reference['file']
                or not isinstance(reference['sha256'], str)
                or not re.fullmatch(r'[0-9a-f]{64}', reference['sha256'])):
            fail(f'{label} reference is malformed')
        raw = aggregate_review_file(directory, reference, label)
        return json.loads(raw), raw

    def is_polygonal(geometry, label):
        if (geometry is None or geometry.is_empty or not geometry.is_valid
                or geometry.geom_type not in ('Polygon', 'MultiPolygon')):
            fail(f'{label} is not a valid nonempty polygonal shape')
        return geometry

    def point_candidate(source, current, label):
        point = source.get('point')
        if not isinstance(point, Point) or point.is_empty or not point.is_valid:
            fail(f'{label} requires an actual source Point in its point field')
        return point

    def feature_at(mid, label):
        if mid not in indices:
            fail(f'{label} member id is missing from indices')
        try:
            index = indices[mid]
            if index < 0 or index >= len(features):
                fail(f'{label} member index is outside features')
            current = features[index]
        except (TypeError, IndexError, KeyError) as exc:
            raise ValueError(f'{label} member index cannot be read') from exc
        if not isinstance(current, dict):
            fail(f'{label} current feature is malformed')
        return index, current

    try:
        if not isinstance(record, dict) or set(record) != RECORD_KEYS:
            fail('Record keys changed')

        identifier = record['id']
        target = record['targetPickerGroupId']
        if (not isinstance(identifier, str) or not identifier.startswith('osm:relation:')
                or not isinstance(target, str) or not target.startswith('osm:node:')
                or identifier == target):
            fail('Record source or target identity is malformed')

        member_ids = {identifier, target}
        sorted_pair = sorted(member_ids)

        if (record['status'] != 'eligible_proposal'
                or record['method'] != 'reviewed_sector_settlement_display_identity'
                or record['expectedExistingMemberIds'] != [identifier]
                or record['expectedTargetMemberIds'] != [target]
                or type(record['expectedGovernorateId']) is not int
                or not isinstance(record['canonicalName'], str)
                or not record['canonicalName'].strip()):
            fail('Record scope or identity changed')

        if (groups.get(identifier) != {identifier} or groups.get(target) != {target}
                or identifier not in indices or target not in indices):
            fail('Source or target picker group is no longer a singleton')

        quals = record['qualifications']
        if (not isinstance(quals, dict) or set(quals) != set(QUALIFICATIONS)
                or any(type(quals[key]) is not bool or quals[key] is not True
                       for key in QUALIFICATIONS)):
            fail('Qualifications changed')

        source_index, source_current = feature_at(identifier, 'source')
        _, target_current = feature_at(target, 'target')
        if (not is_number(source_current.get('lat')) or not is_number(source_current.get('lng'))
                or not is_number(target_current.get('lat')) or not is_number(target_current.get('lng'))):
            fail('Member coordinates are malformed')
        point_lat, point_lng = target_current['lat'], target_current['lng']

        review, _ = load_ref(record['sourceReview'], 'source review')
        if (not isinstance(review, dict)
                or review.get('schemaVersion') != 1
                or review.get('sourceSha256') != source_sha256
                or review.get('status') != 'ROOT_APPROVED_MANUAL_DISPLAY_ONLY'
                or review.get('memberIds') != sorted_pair
                or review.get('canonicalName') != record['canonicalName']
                or review.get('proposedOwnerId') != target):
            fail('Source review identity changed')

        phone_evidence = review.get('phoneEvidence')
        if not isinstance(phone_evidence, list) or len(phone_evidence) < 2:
            fail('Source review phone evidence is missing')

        query_target = f'{point_lat}, {point_lng}'
        query_seen = False
        for reference in phone_evidence:
            if not isinstance(reference, dict) or set(reference) != {'file', 'sha256'}:
                fail('Source review phone evidence reference is malformed')
            file_ref, sha_ref = reference['file'], reference['sha256']
            if (not isinstance(file_ref, str) or not file_ref.strip()
                    or not isinstance(sha_ref, str) or not re.fullmatch(r'[0-9a-f]{64}', sha_ref)):
                fail('Source review phone evidence reference is malformed')

            evidence, evidence_bytes = load_ref(reference, 'phone evidence')
            if hashlib.sha256(evidence_bytes).hexdigest() != sha_ref:
                fail('Source review phone evidence hash changed')
            if not isinstance(evidence, dict):
                fail('Source review phone evidence is malformed')

            if evidence.get('boundaryVerified') is not False:
                fail('Source review phone evidence makes a boundary claim')
            titles = evidence.get('publicCardTitles')
            if (not isinstance(titles, list) or any(not isinstance(value, str) for value in titles)
                    or record['canonicalName'] not in titles):
                fail('Source review selected public card name changed')
            if evidence.get('query') == query_target:
                query_seen = True
        if not query_seen:
            fail('Source review phone evidence query changed')

        registry, _ = load_ref(record['officialRegistry'], 'official sector registry')
        if not isinstance(registry, dict) or not isinstance(registry.get('sectors'), list):
            fail('Official sector registry is malformed')

        official = record['officialSectorRecord']
        context_official = record['pointContextOfficialRecord']
        if not isinstance(official, dict) or not isinstance(context_official, dict):
            fail('Official sector records are malformed')

        code = official.get('sectorCode')
        context_code = context_official.get('sectorCode')
        if (not isinstance(code, str) or not re.fullmatch(r'[0-9]{6}', code)
                or not isinstance(context_code, str) or not re.fullmatch(r'[0-9]{6}', context_code)
                or code == context_code):
            fail('Official sector codes are malformed')

        if [row for row in registry['sectors']
                if isinstance(row, dict) and row.get('sectorCode') == code] != [official]:
            fail('Official sector record is not the unique coded record')
        if [row for row in registry['sectors']
                if isinstance(row, dict) and row.get('sectorCode') == context_code] != [context_official]:
            fail('Point context official sector record is not the unique coded record')

        if (official.get('sectorAr') != record['canonicalName']
                or official.get('delegationCode') != code[:4]
                or official.get('governorateCode') != code[:2]
                or context_official.get('delegationCode') != code[:4]
                or context_official.get('governorateCode') != code[:2]
                or context_official.get('delegationCode') != context_code[:4]
                or context_official.get('governorateCode') != context_code[:2]):
            fail('Official sector record code relationships changed')

        members = record['members']
        if (not isinstance(members, list) or len(members) != 2
                or any(not isinstance(row, dict) for row in members)):
            fail('Members are malformed')
        if {row.get('id') for row in members} != member_ids:
            fail('Members do not match the reviewed pair')

        protected = {}
        member_current = {}
        member_original_geometry = {}
        member_effective_geometry = {}
        nearest_ids = []

        for member in members:
            if set(member) != MEMBER_KEYS:
                fail('Member keys changed')
            mid = member['id']
            if mid not in member_ids:
                fail('Member identity changed')

            expected_kind = 'sector' if mid == identifier else 'suburb'
            index, current = feature_at(mid, 'member')
            member_current[mid] = current

            metadata = {key: value for key, value in current.items()
                        if key not in {'pickerGroupId', 'offset', 'length'}}
            if metadata != member['expectedMetadata'] or current.get('pickerGroupId') != mid:
                fail('Member metadata or current group changed')

            if (current.get('sourceId') != 'osm'
                    or current.get('name') != record['canonicalName']
                    or current.get('governorateId') != record['expectedGovernorateId']
                    or current.get('kind') != expected_kind):
                fail('Member current identity changed')

            original = original_sources.get(mid)
            effective = effective_sources.get(mid)
            if not isinstance(original, dict) or not isinstance(effective, dict):
                fail('Member source records are missing')

            if (original.get('sourceId') is not None or effective.get('sourceId') != 'osm'
                    or original.get('kind') != expected_kind or effective.get('kind') != expected_kind):
                fail('Member source identity changed')

            original_tags = original.get('tags')
            effective_tags = effective.get('tags')
            expected_original_tags = member['expectedOriginalTags']
            expected_effective_tags = member['expectedEffectiveTags']
            if (not isinstance(original_tags, dict) or not isinstance(effective_tags, dict)
                    or not isinstance(expected_original_tags, dict) or not isinstance(expected_effective_tags, dict)
                    or original_tags != effective_tags
                    or original_tags != expected_original_tags
                    or effective_tags != expected_effective_tags):
                fail('Member source tags changed')

            original_names = names_of(original_tags, 'member original')
            effective_names = names_of(effective_tags, 'member effective')
            if (original_names[0] != record['canonicalName']
                    or effective_names[0] != record['canonicalName']):
                fail('Member primary names changed')

            if mid == identifier:
                if (original_tags.get('admin_level') != '6'
                        or original_tags.get('boundary') != 'administrative'
                        or original_tags.get('ref:tn:codegeo') != code):
                    fail('Coded sector tags changed')
            else:
                if original_tags.get('place') != 'suburb':
                    fail('Settlement point tags changed')

            if member['expectedCuration'] is not None or curation.get(mid) is not None:
                fail('Member curation changed')

            original_hash = member['originalGeometrySha256']
            if not isinstance(original_hash, str) or not re.fullmatch(r'[0-9a-f]{64}', original_hash):
                fail('Member original geometry hash is malformed')

            if mid == identifier:
                original_geometry = is_polygonal(original.get('shape'), 'source original shape')
                effective_geometry = is_polygonal(effective.get('shape'), 'source effective shape')
                if (aggregate_geometry_sha256(original_geometry) != original_hash
                        or aggregate_geometry_sha256(effective_geometry) != original_hash):
                    fail('Source polygon geometry hash changed')
                if current.get('hasBoundary') is not True:
                    fail('Source sector no longer has a boundary')
                if index < 0 or index >= len(geometries):
                    fail('Source sector packed geometry index is missing')
                packed = is_polygonal(geometries[index], 'source packed shape')
                packed_hash = member['packedGeometrySha256']
                if not isinstance(packed_hash, str) or not re.fullmatch(r'[0-9a-f]{64}', packed_hash):
                    fail('Source packed geometry hash is malformed')
                if hashlib.sha256(packed_geometry_bytes(packed)).hexdigest() != packed_hash:
                    fail('Source packed geometry changed')
            else:
                original_geometry = point_candidate(original, current, 'target original')
                effective_geometry = point_candidate(effective, current, 'target effective')
                if (aggregate_geometry_sha256(original_geometry) != original_hash
                        or aggregate_geometry_sha256(effective_geometry) != original_hash):
                    fail('Target point geometry hash changed')
                if current.get('hasBoundary') is not False:
                    fail('Target settlement point now has a boundary')
                if member['packedGeometrySha256'] is not None:
                    fail('Target settlement point gained packed geometry')
                if (original_geometry.x != current.get('lng') or original_geometry.y != current.get('lat')
                        or effective_geometry.x != current.get('lng') or effective_geometry.y != current.get('lat')):
                    fail('Target point coordinates changed')

            member_original_geometry[mid] = original_geometry
            member_effective_geometry[mid] = effective_geometry

            lat, lng = current.get('lat'), current.get('lng')
            if (not is_number(lat) or not is_number(lng) or not math.isfinite(lat)
                    or not math.isfinite(lng) or not -90 <= lat <= 90 or not -180 <= lng <= 180):
                fail('Member coordinates are malformed')
            try:
                nearest = min(timetables, key=lambda d: (distance(lat, lng, d), d['id']))
            except Exception as exc:
                raise ValueError('Member prayer timetable is malformed') from exc
            if current.get('delegationId') != nearest.get('id'):
                fail('Member prayer source changed')
            nearest_ids.append(nearest['id'])
            protected[mid] = metadata

        if len(nearest_ids) != 2 or nearest_ids[0] != nearest_ids[1]:
            fail('Member prayer sources no longer agree')

        parent_spec = record['codedParent']
        context_spec = record['pointContext']
        for spec, label, expected_kind, expected_admin, expected_code in (
                (parent_spec, 'coded parent', 'delegation', '5', code[:4]),
                (context_spec, 'point context', 'sector', '6', context_code)):
            if not isinstance(spec, dict) or set(spec) != GEOMETRY_SPEC_KEYS:
                fail(f'{label} keys changed')
            spec_id = spec['id']
            expected_tags = spec['expectedTags']
            if (not isinstance(spec_id, str) or not spec_id
                    or spec['expectedKind'] != expected_kind
                    or not isinstance(expected_tags, dict)
                    or expected_tags.get('admin_level') != expected_admin
                    or expected_tags.get('boundary') != 'administrative'
                    or expected_tags.get('ref:tn:codegeo') != expected_code
                    or not isinstance(expected_tags.get('name'), str)
                    or not expected_tags['name'].strip()
                    or not isinstance(spec['originalGeometrySha256'], str)
                    or not re.fullmatch(r'[0-9a-f]{64}', spec['originalGeometrySha256'])):
                fail(f'{label} specification changed')

            if spec_id not in original_sources or spec_id not in effective_sources:
                fail(f'{label} source records are missing')
            original_source = original_sources[spec_id]
            effective_source = effective_sources[spec_id]
            if not isinstance(original_source, dict) or not isinstance(effective_source, dict):
                fail(f'{label} source records are malformed')

            if (original_source.get('kind') != expected_kind
                    or effective_source.get('kind') != expected_kind
                    or original_source.get('tags') != expected_tags
                    or effective_source.get('tags') != expected_tags):
                fail(f'{label} kind or tags changed')

            original_shape = is_polygonal(original_source.get('shape'), f'{label} original')
            effective_shape = is_polygonal(effective_source.get('shape'), f'{label} effective')
            if (aggregate_geometry_sha256(original_shape) != spec['originalGeometrySha256']
                    or aggregate_geometry_sha256(effective_shape) != spec['originalGeometrySha256']):
                fail(f'{label} geometry hash changed')
            if curation.get(spec_id) is not None:
                fail(f'{label} curation changed')

            if spec is parent_spec:
                if source_current.get('parentName') != expected_tags['name']:
                    fail('Source parent name changed')
            else:
                if target_current.get('parentName') != expected_tags['name']:
                    fail('Target parent name changed')

        parent_id = parent_spec['id']
        context_id = context_spec['id']
        if parent_id == context_id:
            fail('Coded parent and point context are the same source')

        for sources, geometry_map in ((original_sources, member_original_geometry),
                                      (effective_sources, member_effective_geometry)):
            parent_shape = is_polygonal(sources[parent_id].get('shape'), 'parent coverage shape')
            context_shape = is_polygonal(sources[context_id].get('shape'), 'context coverage shape')
            sector_shape = is_polygonal(sources[identifier].get('shape'), 'sector coverage shape')
            point_shape = geometry_map[target]
            if (not parent_shape.covers(sector_shape)
                    or not parent_shape.covers(point_shape)
                    or not context_shape.covers(point_shape)
                    or not parent_shape.covers(context_shape)):
                fail('Reviewed parent or context coverage changed')

        expected_peers = record['expectedSourceNamePeerIds']
        if not isinstance(expected_peers, list) or expected_peers != sorted_pair:
            fail('Expected source name peer list changed')

        for sources in (original_sources, effective_sources):
            peer_keys = set()
            for mid in member_ids:
                source = sources.get(mid)
                if not isinstance(source, dict):
                    fail('Member source is missing during peer inventory')
                for value in names_of(source.get('tags'), 'peer member'):
                    peer_keys.add(norm(value))

            matched = []
            for mid, source in sources.items():
                if not isinstance(source, dict):
                    continue
                try:
                    values = names_of(source.get('tags'), 'peer source')
                except ValueError:
                    continue
                if peer_keys & {norm(value) for value in values}:
                    matched.append(mid)

            if sorted(matched) != expected_peers or sorted(matched) != sorted_pair:
                fail('Source name peer inventory changed')

        spatial = record['spatialRelationship']
        if not isinstance(spatial, dict) or set(spatial) != SPATIAL_KEYS:
            fail('Spatial relationship keys changed')
        if spatial.get('pointOutsideSector') is not True:
            fail('Spatial relationship no longer excludes the point')

        supplied_distance = spatial.get('representativeDistanceKm')
        if not is_number(supplied_distance) or not math.isfinite(supplied_distance):
            fail('Representative distance is malformed')

        target_point = member_original_geometry[target]
        actual_distance = distance(source_current.get('lat'), source_current.get('lng'),
                                   {'lat': target_point.y, 'lng': target_point.x})
        if (not is_number(actual_distance) or not math.isfinite(actual_distance)
                or actual_distance <= 0 or actual_distance > 2
                or not math.isclose(actual_distance, supplied_distance, rel_tol=1e-12, abs_tol=1e-12)):
            fail('Representative distance changed')

        if original_sources[identifier]['shape'].covers(target_point):
            fail('Source sector unexpectedly covers the settlement point')
        if geometries[source_index].covers(target_point):
            fail('Packed source sector unexpectedly covers the settlement point')

        manual_selection = record['manualSelection']
        manual = {
            'representativeId': target,
            'lat': target_current.get('lat'),
            'lng': target_current.get('lng'),
            'expectedNearestSourceId': target_current.get('delegationId')
        }
        if (not isinstance(manual_selection, dict)
                or set(manual_selection) != MANUAL_KEYS
                or manual_selection != manual):
            fail('Manual selection changed')

        return {
            'id': identifier,
            'identityMethod': record['method'],
            'retainedOwnerId': target,
            'manualSelection': manual,
            'spatialRelationship': spatial,
            'finalGroups': [{'id': target, 'memberIds': sorted_pair}],
            'protectedMetadata': protected
        }
    except ValueError:
        raise
    except Exception as exc:
        raise ValueError('Reviewed sector settlement display identity is malformed') from exc


def validate_reviewed_named_neighbourhood_residential_display_identity(
        record, features, geometries, indices, original_sources, effective_sources, curation, timetables):
    '''Validate one exact reviewed named neighbourhood/residential display-identity join.'''
    record_keys = {'id', 'status', 'method', 'targetPickerGroupId', 'expectedExistingMemberIds',
                   'expectedTargetMemberIds', 'expectedGovernorateId', 'expectedDelegationId',
                   'expectedParentName', 'relationship', 'nameEvidence', 'members', 'qualifications'}
    if (set(record) != record_keys or record.get('status') != 'eligible_proposal'
            or record.get('method') != 'reviewed_named_neighbourhood_residential_display_identity'
            or record.get('relationship') not in ('quarter_residential', 'neighbourhood_point_residential')):
        raise ValueError('Invalid reviewed named neighbourhood/residential record')
    left, right = record.get('expectedExistingMemberIds'), record.get('expectedTargetMemberIds')
    if (not isinstance(left, list) or not isinstance(right, list) or not left or not right
            or any(not isinstance(mid, str) for mid in left + right)
            or len(left) != len(set(left)) or len(right) != len(set(right)) or set(left) & set(right)):
        raise ValueError('Invalid reviewed named neighbourhood/residential group binding')
    name = record.get('nameEvidence')
    if (not isinstance(name, dict) or set(name) != {'arabic', 'latin', 'residentialLatin'}
            or any(not isinstance(name.get(key), str) or not name[key] for key in name)):
        raise ValueError('Invalid reviewed named neighbourhood/residential name evidence')
    qualifications = record.get('qualifications')
    if (not isinstance(qualifications, dict)
            or set(qualifications) != {'displayIdentityOnly', 'noBoundaryEqualityClaim',
                                       'allRawGeometryAndCoordinatesRetained',
                                       'prayerSourceIsNotIdentityEvidence'}
            or any(value is not True for value in qualifications.values())):
        raise ValueError('Invalid reviewed named neighbourhood/residential qualifications')
    members = record.get('members')
    member_keys = {'id', 'expectedMetadata', 'expectedOriginalTags', 'expectedEffectiveTags',
                   'expectedCuration', 'originalGeometrySha256', 'packedGeometrySha256'}
    if (not isinstance(members, list) or len(members) != len(left) + len(right)
            or any(not isinstance(member, dict) or set(member) != member_keys for member in members)):
        raise ValueError('Invalid reviewed named neighbourhood/residential member inventory')
    member_by_id = {}
    for member in members:
        mid, metadata = member.get('id'), member.get('expectedMetadata')
        if (not isinstance(mid, str) or mid in member_by_id or not isinstance(metadata, dict)
                or metadata.get('id') != mid or metadata.get('sourceId') != 'osm'
                or not isinstance(member.get('expectedOriginalTags'), dict)
                or not isinstance(member.get('expectedEffectiveTags'), dict)
                or member.get('expectedCuration') is not None
                or not isinstance(member.get('originalGeometrySha256'), str) or not member['originalGeometrySha256']
                or (member.get('packedGeometrySha256') is not None
                    and (not isinstance(member['packedGeometrySha256'], str) or not member['packedGeometrySha256']))):
            raise ValueError('Invalid reviewed named neighbourhood/residential member record')
        member_by_id[mid] = member
    if set(member_by_id) != set(left) | set(right):
        raise ValueError('Reviewed named neighbourhood/residential member ids changed')

    def checked(member):
        mid = member['id']
        if mid not in indices:
            raise ValueError(f'Reviewed named neighbourhood/residential member index missing: {mid}')
        index = indices[mid]
        if (not isinstance(index, int) or index < 0 or index >= len(features)
                or features[index].get('id') != mid):
            raise ValueError(f'Reviewed named neighbourhood/residential member index changed: {mid}')
        current, metadata = features[index], member['expectedMetadata']
        if ({key: value for key, value in current.items()
             if key not in ('pickerGroupId', 'offset', 'length')} != metadata):
            raise ValueError(f'Reviewed named neighbourhood/residential metadata changed: {mid}')
        if (current.get('governorateId') != record['expectedGovernorateId']
                or current.get('delegationId') != record['expectedDelegationId']):
            raise ValueError(f'Reviewed named neighbourhood/residential context changed: {mid}')
        nearest = min(timetables, key=lambda d: (distance(current['lat'], current['lng'], d), d['id']))
        if nearest['id'] != record['expectedDelegationId']:
            raise ValueError(f'Reviewed named neighbourhood/residential prayer source changed: {mid}')
        original, effective = original_sources.get(mid), effective_sources.get(mid)
        if original is None or effective is None:
            raise ValueError(f'Reviewed named neighbourhood/residential source missing: {mid}')
        if (original.get('kind') != metadata.get('kind') or effective.get('kind') != metadata.get('kind')
                or original.get('tags') != member['expectedOriginalTags']
                or effective.get('tags') != member['expectedEffectiveTags']
                or curation.get(mid) is not None or effective.get('sourceId') != 'osm'):
            raise ValueError(f'Reviewed named neighbourhood/residential source identity changed: {mid}')
        if metadata.get('kind') == 'neighbourhood':
            if (metadata.get('hasBoundary') is not False or member.get('packedGeometrySha256') is not None
                    or index < len(geometries)):
                raise ValueError(f'Reviewed named neighbourhood/residential point geometry changed: {mid}')
            if (original.get('shape') is not None or effective.get('shape') is not None
                    or original.get('point') is None or effective.get('point') is None):
                raise ValueError(f'Reviewed named neighbourhood/residential point source fields changed: {mid}')
            original_geom, effective_geom = original['point'], effective['point']
            if (getattr(original_geom, 'geom_type', None) != 'Point'
                    or getattr(effective_geom, 'geom_type', None) != 'Point'
                    or original_geom.x != metadata.get('lng') or original_geom.y != metadata.get('lat')
                    or effective_geom.x != metadata.get('lng') or effective_geom.y != metadata.get('lat')
                    or aggregate_geometry_sha256(original_geom) != member['originalGeometrySha256']
                    or aggregate_geometry_sha256(effective_geom) != member['originalGeometrySha256']):
                raise ValueError(f'Reviewed named neighbourhood/residential point identity changed: {mid}')
            return original_geom, effective_geom, None
        if metadata.get('hasBoundary') is not True or index >= len(geometries):
            raise ValueError(f'Reviewed named neighbourhood/residential polygon index changed: {mid}')
        packed = geometries[index]
        original_geom, effective_geom = original.get('shape'), effective.get('shape')
        if (not isinstance(original_geom, (Polygon, MultiPolygon))
                or not isinstance(effective_geom, (Polygon, MultiPolygon))
                or not isinstance(packed, (Polygon, MultiPolygon))
                or original_geom.is_empty or not original_geom.is_valid
                or effective_geom.is_empty or not effective_geom.is_valid
                or packed.is_empty or not packed.is_valid
                or aggregate_geometry_sha256(original_geom) != member['originalGeometrySha256']
                or aggregate_geometry_sha256(effective_geom) != member['originalGeometrySha256']
                or hashlib.sha256(packed_geometry_bytes(packed)).hexdigest() != member['packedGeometrySha256']):
            raise ValueError(f'Reviewed named neighbourhood/residential polygon identity changed: {mid}')
        return original_geom, effective_geom, packed

    prepared = {member['id']: checked(member) for member in members}
    if record['relationship'] == 'quarter_residential':
        quarter = [m for m in members if m['expectedMetadata'].get('kind') == 'quarter']
        residential = [m for m in members if m['expectedMetadata'].get('kind') == 'residential']
        village = [m for m in members if m['expectedMetadata'].get('kind') == 'village']
        if (len(quarter) != 1 or len(residential) != 1 or len(village) != 1
                or set(left) != {residential[0]['id']} or record.get('id') != residential[0]['id']
                or set(right) != {quarter[0]['id'], village[0]['id']}
                or record.get('targetPickerGroupId') != quarter[0]['id']):
            raise ValueError('Reviewed named neighbourhood/residential quarter roles changed')
        quarter, residential, village = quarter[0], residential[0], village[0]
        if (quarter['expectedMetadata'].get('parentName') != record['expectedParentName']
                or residential['expectedMetadata'].get('parentName') != record['expectedParentName']):
            raise ValueError('Reviewed named neighbourhood/residential quarter context changed')
        if (quarter['expectedMetadata'].get('name') != name['latin'] + ' - ' + name['arabic']
                or residential['expectedMetadata'].get('name') != name['arabic'] + ' - ' + name['latin']
                or name['residentialLatin'] != residential['expectedMetadata'].get('name')
                or village['expectedMetadata'].get('name') != name['latin']
                or quarter['expectedOriginalTags'].get('name') != quarter['expectedMetadata'].get('name')
                or quarter['expectedOriginalTags'].get('place') != 'quarter'
                or residential['expectedOriginalTags'].get('name') != name['residentialLatin']
                or residential['expectedOriginalTags'].get('landuse') != 'residential'
                or village['expectedOriginalTags'].get('name') != name['latin']
                or village['expectedOriginalTags'].get('place') != 'village'):
            raise ValueError('Reviewed named neighbourhood/residential literal name evidence changed')
        quarter_geom, residential_geom, village_geom = prepared[quarter['id']], prepared[residential['id']], prepared[village['id']]
        if (not village_geom[0].covers(quarter_geom[0]) or not quarter_geom[0].covers(residential_geom[0])
                or not village_geom[1].covers(quarter_geom[1]) or not quarter_geom[1].covers(residential_geom[1])
                or not village_geom[2].covers(quarter_geom[2]) or not quarter_geom[2].covers(residential_geom[2])):
            raise ValueError('Reviewed named neighbourhood/residential quarter containment changed')
        return
    point = [m for m in members if m['expectedMetadata'].get('kind') == 'neighbourhood']
    residential = [m for m in members if m['expectedMetadata'].get('kind') == 'residential']
    if (len(point) != 1 or len(residential) != 1
            or set(left) != {residential[0]['id']} or record.get('id') != residential[0]['id']
            or set(right) != {point[0]['id']} or record.get('targetPickerGroupId') != point[0]['id']):
        raise ValueError('Reviewed named neighbourhood/residential point roles changed')
    point, residential = point[0], residential[0]
    if (point['expectedMetadata'].get('parentName') != record['expectedParentName']
            or residential['expectedMetadata'].get('parentName') != record['expectedParentName']):
        raise ValueError('Reviewed named neighbourhood/residential point context changed')
    if (point['expectedMetadata'].get('name') != name['arabic']
            or point['expectedOriginalTags'].get('name') != name['arabic']
            or point['expectedOriginalTags'].get('name:ar') != name['arabic']
            or point['expectedOriginalTags'].get('name:fr') != name['latin']
            or point['expectedOriginalTags'].get('place') != 'neighbourhood'
            or residential['expectedMetadata'].get('name') != name['residentialLatin']
            or residential['expectedOriginalTags'].get('name') != name['residentialLatin']
            or residential['expectedOriginalTags'].get('addr:place') != name['arabic']
            or residential['expectedOriginalTags'].get('landuse') != 'residential'):
        raise ValueError('Reviewed named neighbourhood/residential literal point name evidence changed')
    point_geom, residential_geom = prepared[point['id']], prepared[residential['id']]
    if (not residential_geom[0].covers(point_geom[0])
            or not residential_geom[1].covers(point_geom[1])
            or not residential_geom[2].covers(point_geom[1])):
        raise ValueError('Reviewed named neighbourhood/residential point containment changed')


def validate_reviewed_locality_groups(manifest_path, manifest, features, geometries, timetables,
                                      original_sources, effective_sources, source_sha256, curation,
                                      groups, indices, reserved_groups, reserved_members):
    """Validate exact reviewed locality groups; never infer additional members.

    Each method requires its own pinned source relationship. Every existing
    raw record, point and polygon is retained separately.
    """
    records = manifest.get('localRecords', [])
    if not isinstance(records, list):
        raise ValueError('Invalid reviewed locality-group inventory')
    reference = manifest.get('localReviewEvidence')
    additional = manifest.get('additionalLocalReviewEvidence', [])
    if not isinstance(additional, list):
        raise ValueError('Invalid additional locality-group evidence inventory')
    if not records:
        if reference is not None or additional:
            raise ValueError('Locality-group evidence has no reviewed inventory')
        return [], None
    proofs, evidence_documents, seen_references = {}, [], set()
    for evidence_reference in [reference] + additional:
        evidence = json.loads(aggregate_review_file(manifest_path.parent, evidence_reference, 'locality-group evidence'))
        if evidence_reference['sha256'] in seen_references:
            raise ValueError('Repeated locality-group evidence reference')
        seen_references.add(evidence_reference['sha256'])
        if (evidence.get('schemaVersion') != 1 or evidence.get('sourceSha256') != source_sha256
                or not isinstance(evidence.get('records'), list) or not evidence['records']):
            raise ValueError('Reviewed locality-group evidence uses a different source version')
        facts = json.loads(aggregate_review_file(manifest_path.parent, evidence.get('sourceFacts'),
                                                'locality-group source facts'))
        if facts.get('schemaVersion') != 1 or facts.get('sourceSha256') != source_sha256:
            raise ValueError('Reviewed locality-group source facts changed')
        aggregate_review_file(manifest_path.parent, evidence.get('researchReview'), 'locality-group research')
        evidence_documents.append(evidence)
        for proof in evidence['records']:
            identifier = proof.get('id')
            if (not isinstance(identifier, str) or identifier in proofs
                    or proof.get('status') != 'eligible_proposal'):
                raise ValueError('Invalid or repeated reviewed locality-group evidence')
            proofs[identifier] = proof
    seen_groups, seen_members, seen_ids, staged = set(), set(), set(), []
    deferred = []
    complete_owner_applications = []
    sector_settlement_applications = []
    seen_support_ids = set()
    available = {d['id'] for d in timetables}
    for record in records:
        identifier, target = record.get('id'), record.get('targetPickerGroupId')
        if (not isinstance(identifier, str) or not isinstance(target, str) or identifier == target
                or identifier.startswith('delegation:') or target.startswith('delegation:')
                or {identifier, target} & (seen_groups | reserved_groups)
                or proofs.get(identifier) != record
                or record.get('method') not in ('explicit_named_village_containment', 'explicit_addressed_village',
                                              'reviewed_primary_source_named_settlement',
                                              'reviewed_distributed_settlement', 'reviewed_surveyed_hamlet',
                                              'reviewed_two_patch_settlement', 'reviewed_complete_locality_group_owner',
                                              'reviewed_sector_settlement_display_identity',
                                              'reviewed_named_neighbourhood_residential_display_identity')):
            raise ValueError('Invalid, transitive or unreviewed locality-group target')
        if record['method'] == 'reviewed_sector_settlement_display_identity':
            member_ids = {identifier, target}
            if member_ids & (seen_members | reserved_members | seen_support_ids):
                raise ValueError('Reviewed sector settlement overlaps a reserved identity')
            application = validate_reviewed_sector_settlement_display_identity(
                record, manifest_path.parent, features, indices, geometries, groups,
                original_sources, effective_sources, curation, timetables, source_sha256)
            staged.append((target, [identifier]))
            sector_settlement_applications.append(application)
            seen_groups.update(member_ids);seen_members.update(member_ids);seen_ids.add(identifier)
            continue
        if record['method'] == 'reviewed_complete_locality_group_owner':
            left = record.get('expectedExistingMemberIds')
            if (not isinstance(left, list) or any(not isinstance(mid, str) for mid in left)
                    or set(left) & (seen_members | reserved_members | seen_support_ids)):
                raise ValueError('Reviewed complete local owner overlaps a reserved identity')
            application = validate_reviewed_complete_locality_group_owner(
                record, manifest_path.parent, features, indices, geometries, groups,
                original_sources, effective_sources, curation, timetables, source_sha256)
            staged.append((target, left))
            complete_owner_applications.append(application)
            seen_groups.update((identifier, target));seen_members.update(left);seen_ids.add(identifier)
            continue
        left, right = record.get('expectedExistingMemberIds'), record.get('expectedTargetMemberIds')
        if (not isinstance(left, list) or not isinstance(right, list) or not left or not right
                or any(not isinstance(i, str) for i in left + right)
                or len(left) != len(set(left)) or len(right) != len(set(right))
                or set(right) != groups.get(target, set())
                or identifier not in left or target not in right or set(left) & set(right)):
            raise ValueError('Reviewed locality source or target group membership changed')
        source_group_ids = {identifier}
        if record['method'] in ('reviewed_distributed_settlement', 'reviewed_surveyed_hamlet'):
            source_groups = record.get('expectedSourceGroups')
            if not isinstance(source_groups, list) or len(source_groups) < 2:
                raise ValueError('Distributed settlement lacks its exact source-group inventory')
            source_group_ids, source_members = set(), set()
            for source_group in source_groups:
                if not isinstance(source_group, dict) or set(source_group) != {'pickerGroupId', 'memberIds'}:
                    raise ValueError('Malformed reviewed distributed source group')
                gid, mids = source_group['pickerGroupId'], source_group['memberIds']
                if (not isinstance(gid, str) or gid.startswith('delegation:') or gid == target
                        or gid in source_group_ids or gid in seen_groups | reserved_groups
                        or not isinstance(mids, list) or not mids or any(not isinstance(mid, str) for mid in mids)
                        or len(mids) != len(set(mids)) or gid not in mids
                        or set(mids) != groups.get(gid, set()) or source_members & set(mids)):
                    raise ValueError('Reviewed distributed source group changed or overlaps another review')
                source_group_ids.add(gid);source_members.update(mids)
            if identifier != source_groups[0]['pickerGroupId'] or source_members != set(left):
                raise ValueError('Distributed settlement source-group union changed')
        elif 'expectedSourceGroups' in record or set(left) != groups.get(identifier, set()):
            raise ValueError('Reviewed locality source group membership changed')
        member_ids = set(left + right)
        members = record.get('members')
        if (not isinstance(members, list) or any(not isinstance(m, dict) for m in members)
                or len(members) != len(member_ids) or {m.get('id') for m in members} != member_ids
                or member_ids & (seen_members | reserved_members)):
            raise ValueError('Reviewed locality-group member inventory overlaps another review')
        if member_ids & seen_support_ids:
            raise ValueError('Reviewed locality reuses an exclusively supporting source identity')
        source_id = record.get('expectedDelegationId')
        if (type(source_id) is not int or source_id not in available
                or type(record.get('expectedGovernorateId')) is not int
                or not isinstance(record.get('expectedParentName'), str) or not record['expectedParentName']):
            raise ValueError('Reviewed locality-group context or prayer source changed')
        if record['method'] == 'reviewed_named_neighbourhood_residential_display_identity':
            validate_reviewed_named_neighbourhood_residential_display_identity(
                record, features, geometries, indices, original_sources, effective_sources, curation, timetables)
            staged.append((target, left))
            seen_groups.update((identifier, target));seen_members.update(member_ids);seen_ids.add(identifier)
            continue
        if record['method'] == 'reviewed_two_patch_settlement':
            support = record.get('exclusiveSupportingSourceIds', [])
            other_support = {sid for other in records if other is not record
                             for sid in [other.get('bilingualNode', {}).get('id'),
                                         other.get('absorbedVillage', {}).get('id'),
                                         *other.get('exclusiveSupportingSourceIds', [])] if sid is not None}
            if (not isinstance(support, list) or any(not isinstance(sid, str) for sid in support)
                    or set(support) & (seen_support_ids | seen_members | reserved_members | member_ids | other_support)):
                raise ValueError('Reviewed two-patch supporting identity is already reserved')
            validate_reviewed_two_patch_settlement(manifest_path, record, features, geometries, timetables,
                                                   original_sources, effective_sources, curation, indices, source_sha256)
            staged.append((target, left))
            seen_groups.update((identifier, target));seen_members.update(member_ids);seen_ids.add(identifier)
            seen_support_ids.update(support)
            continue
        if record['method'] == 'reviewed_surveyed_hamlet':
            validate_reviewed_surveyed_hamlet(manifest_path, record, features, geometries, timetables,
                                              original_sources, effective_sources, curation, indices, source_sha256)
            deferred.append(record)
            seen_groups.update(source_group_ids | {target});seen_members.update(member_ids);seen_ids.add(identifier)
            continue
        if record['method'] == 'reviewed_distributed_settlement':
            validate_reviewed_distributed_settlement(manifest_path, record, features, geometries, timetables,
                                                      original_sources, effective_sources, curation, indices)
            staged.append((target, left))
            seen_groups.update(source_group_ids | {target});seen_members.update(member_ids);seen_ids.add(identifier)
            continue
        if record['method'] == 'reviewed_primary_source_named_settlement':
            validate_reviewed_named_settlement(manifest_path, record, features, geometries, timetables,
                                               original_sources, effective_sources, curation, indices)
            staged.append((target, left))
            seen_groups.update((identifier, target));seen_members.update(member_ids);seen_ids.add(identifier)
            continue
        if record['method'] == 'explicit_addressed_village':
            validate_reviewed_addressed_village(record, features, geometries, timetables,
                                                original_sources, effective_sources, curation, indices)
            staged.append((target, left))
            seen_groups.update((identifier, target));seen_members.update(member_ids);seen_ids.add(identifier)
            continue
        anchor_id = record.get('anchorId')
        if anchor_id not in right:
            raise ValueError('Reviewed village anchor is not an existing target-group member')
        anchor = effective_sources.get(anchor_id)
        if (anchor is None or anchor.get('kind') != 'village' or anchor['tags'].get('place') != 'village'
                or anchor.get('shape') is None or anchor_id not in indices or indices[anchor_id] >= len(geometries)):
            raise ValueError('Reviewed locality group lacks its explicit village footprint')
        source_anchor, packed_anchor = anchor['shape'], geometries[indices[anchor_id]]
        name = record.get('nameEvidence', {})
        if (not isinstance(name, dict) or any(not isinstance(name.get(k), str) or not name[k]
                for k in ('arabic', 'latin'))
                or any(name.get(k) != value for k, value in (
                    ('anchorLatinTag', 'name'), ('nodeArabicTag', 'name:ar'), ('nodeLatinTag', 'name:fr'),
                    ('residentialArabicTag', 'name:ar'), ('residentialLatinTag', 'name:en')))):
            raise ValueError('Missing reviewed per-ID locality name evidence')
        for member in members:
            mid = member['id'];index = indices[mid];current = features[index]
            original, effective = original_sources.get(mid), effective_sources.get(mid)
            if (index >= len(geometries) or not current['hasBoundary'] or original is None or effective is None
                    or current['kind'] not in ('residential', 'village')
                    or original.get('kind') != member.get('expectedKind')
                    or effective.get('kind') != member.get('expectedKind')
                    or original['tags'] != member.get('expectedOriginalTags')
                    or effective['tags'] != member.get('expectedEffectiveTags')
                    or curation.get(mid) != member.get('expectedCuration')
                    or any(current[k] != member.get(expected) for k, expected in (
                        ('kind', 'expectedKind'), ('sourceId', 'expectedSourceId'), ('name', 'expectedName'),
                        ('aliases', 'expectedAliases'), ('lat', 'expectedLat'), ('lng', 'expectedLng')))
                    or current['governorateId'] != record['expectedGovernorateId']
                    or current['parentName'] != record['expectedParentName'] or current['delegationId'] != source_id):
                raise ValueError(f'Reviewed locality member identity or context changed: {mid}')
            unrounded, quantized = effective.get('shape'), geometries[index]
            if (original.get('shape') is None
                    or aggregate_geometry_sha256(original['shape']) != member.get('originalGeometrySha256')
                    or unrounded is None
                    or aggregate_geometry_sha256(unrounded) != member.get('effectiveOriginalGeometrySha256')
                    or hashlib.sha256(packed_geometry_bytes(quantized)).hexdigest() != member.get('packedGeometrySha256')
                    or not source_anchor.covers(unrounded) or not packed_anchor.covers(quantized)):
                raise ValueError(f'Reviewed locality footprint or village containment changed: {mid}')
            if mid == anchor_id:
                if current['name'] != name['latin'] or effective['tags'].get('name') != name['latin']:
                    raise ValueError('Reviewed village primary name changed')
            elif (current['kind'] != 'residential' or current['name'] != name['arabic']
                    or effective['tags'].get('name:ar') != name['arabic']
                    or effective['tags'].get('name:en') != name['latin']):
                raise ValueError('Reviewed residential bilingual name evidence changed')
            nearest = min(timetables, key=lambda d: (distance(current['lat'], current['lng'], d), d['id']))
            if nearest['id'] != source_id:
                raise ValueError('Reviewed locality members no longer share their manual prayer source')
        node = record.get('bilingualNode', {})
        if not isinstance(node, dict):
            raise ValueError('Missing reviewed bilingual village source node')
        node_id = node.get('id');old_node = original_sources.get(node_id);live_node = effective_sources.get(node_id)
        container_id = node.get('containedByMemberId')
        if (old_node is None or live_node is None or node_id in indices or container_id not in left
                or old_node.get('kind') != 'village' or live_node.get('kind') != 'village'
                or node.get('expectedKind') != 'village' or live_node.get('sourceId') != node.get('expectedSourceId')
                or old_node['tags'] != node.get('expectedOriginalTags')
                or live_node['tags'] != node.get('expectedEffectiveTags')
                or curation.get(node_id) != node.get('expectedCuration')
                or live_node['tags'].get('name:ar') != name['arabic']
                or live_node['tags'].get('name:fr') != name['latin']
                or live_node['tags'].get('place') != 'village' or live_node.get('point') is None):
            raise ValueError('Reviewed bilingual village source identity changed')
        point = live_node['point']
        if (point.x != node.get('expectedLng') or point.y != node.get('expectedLat')
                or not source_anchor.covers(point) or not packed_anchor.covers(point)
                or not effective_sources[container_id]['shape'].covers(point)
                or not geometries[indices[container_id]].covers(point)):
            raise ValueError('Reviewed bilingual village node containment changed')
        matching_villages = set()
        for sid, obj in effective_sources.items():
            if obj.get('kind') != 'village':
                continue
            geom = obj.get('shape', obj.get('point'))
            if (geom is not None and source_anchor.intersects(geom)
                    and {picker_review_name_key(value) for value in names(obj['tags'])}
                    & {picker_review_name_key(name['arabic']), picker_review_name_key(name['latin'])}):
                matching_villages.add(sid)
        expected_villages = record.get('expectedSameNameVillageSourceIds')
        if (not isinstance(expected_villages, list) or len(expected_villages) != 2
                or set(expected_villages) != {anchor_id, node_id} or matching_villages != set(expected_villages)):
            raise ValueError('Reviewed locality has a competing same-name village source')
        staged.append((target, left))
        seen_groups.update((identifier, target));seen_members.update(member_ids);seen_ids.add(identifier)
    if seen_ids != set(proofs):
        raise ValueError('Reviewed locality-group inventory differs from its source evidence')
    all_applications = staged + [(record['targetPickerGroupId'], record['expectedExistingMemberIds']) for record in deferred]
    report = {'evidenceSha256': reference['sha256'], 'sourceFactsSha256': evidence_documents[0]['sourceFacts']['sha256'],
                    'reviewedGroupCount': len(all_applications), 'preservedMemberCount': len(seen_members),
                    'changedGroupIdCount': sum(len(ids) for _, ids in all_applications),
                    'applications': [{'pickerGroupId': target, 'ids': sorted(ids)} for target, ids in all_applications]}
    if deferred:
        report['_deferredSurveyedHamlets'] = deferred
    if sector_settlement_applications:
        report['sectorSettlementDisplayIdentities'] = {'reviewedGroupCount': len(sector_settlement_applications),
                                                      'applications': sector_settlement_applications}
    if complete_owner_applications:
        report['completeGroupOwners'] = {'reviewedGroupCount': len(complete_owner_applications),
                                        'applications': complete_owner_applications}
    if additional:
        report['additionalEvidenceSha256'] = [item['sha256'] for item in additional]
        report['additionalSourceFactsSha256'] = [item['sourceFacts']['sha256'] for item in evidence_documents[1:]]
    return staged, report


def validate_reviewed_absorbed_town_reference(identity, manifest_path, manifest, record,
                                             features, indices, geometries, original_sources,
                                             effective_sources, base, governor, curation):
    """Validate one explicitly reviewed residential choice's absorbed town reference.

    This is a source-identity proof, not a general name/point-containment merger.
    The caller has already bound every identity field to its approved proof and
    validated the complete singleton source/target groups and prayer default.
    """
    identifier = identity.get('id')
    member_id = identity.get('memberId')
    if (identity.get('method') != 'reviewed_absorbed_town_reference'
            or not isinstance(identifier, str) or not identifier.startswith('osm:node:')
            or identifier in indices or member_id not in indices
            or record.get('expectedExistingMemberIds') != [member_id]
            or record.get('expectedTargetMemberIds') != [f"delegation:{base['id']}"]):
        raise ValueError('Reviewed absorbed town needs its exact singleton groups')
    old = original_sources.get(identifier)
    current = effective_sources.get(identifier)
    member_old = original_sources.get(member_id)
    member_source = effective_sources.get(member_id)
    member = features[indices[member_id]]
    if (old is None or current is None or member_old is None or member_source is None
            or old.get('kind') != 'town' or current.get('kind') != 'town'
            or identity.get('expectedKind') != 'town'
            or current.get('sourceId') != identity.get('expectedSourceId')
            or old['tags'] != identity.get('expectedOriginalTags')
            or current['tags'] != identity.get('expectedEffectiveTags')
            or 'expectedCuration' not in identity
            or curation.get(identifier) != identity['expectedCuration']
            or 'expectedMemberCuration' not in identity
            or curation.get(member_id) != identity['expectedMemberCuration']
            or old['tags'].get('place') != 'town' or current['tags'].get('place') != 'town'
            or member_old.get('kind') != 'residential' or member_source.get('kind') != 'residential'
            or member['kind'] != 'residential' or not member['hasBoundary']
            or indices[member_id] >= len(geometries)
            or member_source['tags'] != identity.get('expectedMemberEffectiveTags')
            or member.get('aliases') != identity.get('expectedMemberAliases')
            or member['governorateId'] != governor['id'] or member['delegationId'] != base['id']):
        raise ValueError('Reviewed absorbed town or residential identity changed')
    point = current.get('point')
    original_point = old.get('point')
    if (point is None or original_point is None or point.geom_type != 'Point'
            or original_point.geom_type != 'Point' or old.get('shape') is not None
            or current.get('shape') is not None
            or aggregate_geometry_sha256(original_point) != identity.get('originalGeometrySha256')
            or aggregate_geometry_sha256(point) != identity.get('effectiveOriginalGeometrySha256')
            or point.x != identity.get('expectedLng') or point.y != identity.get('expectedLat')
            or point.x != base['lng'] or point.y != base['lat']
            or not member_old['shape'].covers(original_point)
            or not member_source['shape'].covers(point)
            or not geometries[indices[member_id]].covers(point)):
        raise ValueError('Reviewed town/base reference or residential containment changed')
    arabic, latin, old_latin = (identity.get(key) for key in ('nameAr', 'nameFr', 'oldNameFr'))
    if (any(not isinstance(value, str) or not value for value in (arabic, latin, old_latin))
            or member['name'] != arabic or base['nomAr'] != arabic or base['nomFr'] != latin
            or any(source['tags'].get('name:ar') != arabic or source['tags'].get('name:fr') != latin
                   for source in (old, current, member_old, member_source))
            or any(source['tags'].get('addr:city') != arabic or source['tags'].get('old_name') != old_latin
                   for source in (member_old, member_source))
            or old['tags'].get('old_name:fr') != old_latin
            or current['tags'].get('old_name:fr') != old_latin):
        raise ValueError('Reviewed bilingual town and former-name correspondence changed')
    # The source-reference correction is independently pinned. A nearby point
    # or a later reanchoring cannot silently satisfy this exact association.
    reference = identity.get('prayerSourceReference')
    coordinates = json.loads(reviewed_prayer_coordinate_reference(
        Path(manifest_path).parent, reference, 'absorbed-town source reference'))
    matches = [item for item in coordinates.get('corrections', [])
               if item.get('delegationId') == base['id']]
    if (len(matches) != 1 or matches[0] != reference.get('expectedCorrection')
            or matches[0].get('expectedGovernorateId') != governor['id']
            or matches[0].get('expectedNames') != {key: base[key] for key in ('nomAr', 'nomFr', 'nomEn')}
            or matches[0].get('osm', {}).get('id') != identifier
            or matches[0].get('osm', {}).get('place') != 'town'
            or matches[0].get('proposed') != {'lat': point.y, 'lng': point.x}):
        raise ValueError('Reviewed original prayer-source town reference changed')
    pair = identity.get('requiredDistinctPair')
    if (not isinstance(pair, dict) or not isinstance(pair.get('ids'), list)
            or len(pair['ids']) != 2 or len(set(pair['ids'])) != 2 or member_id not in pair['ids']
            or sum(item == pair for item in manifest.get('distinctPairs', [])) != 1
            or any(i not in indices for i in pair['ids'])
            or len({features[indices[i]]['pickerGroupId'] for i in pair['ids']}) != 2):
        raise ValueError('Reviewed town/qualified-sector separation is missing')
    aggregate_review_file(Path(manifest_path).parent, pair['reviewEvidence'], 'absorbed-town distinct choice')
    # These names screen the complete eligible source inventory only. They do
    # not infer identities or attach any unlisted peer to this display group.
    peer_names = identity.get('peerNameValues')
    expected = identity.get('expectedSourcePeers')
    if (not isinstance(peer_names, list) or not peer_names
            or any(not isinstance(value, str) or not value for value in peer_names)
            or arabic not in peer_names or latin not in peer_names
            or not isinstance(expected, list) or not expected
            or any(not isinstance(item, dict) or not isinstance(item.get('id'), str) for item in expected)):
        raise ValueError('Missing reviewed absorbed-town peer inventory')
    keys = {norm(value) for value in peer_names}
    expected_by_id = {item['id']: item for item in expected}
    if len(expected_by_id) != len(expected) or not {identifier, member_id} <= set(expected_by_id):
        raise ValueError('Repeated or incomplete absorbed-town peer inventory')
    for sources, tags_key, shape_key in (
            (original_sources, 'originalTags', 'originalGeometrySha256'),
            (effective_sources, 'effectiveTags', 'effectiveGeometrySha256')):
        actual = {i: source for i, source in sources.items()
                  if keys & {norm(value) for value in names(source['tags'])}}
        if set(actual) != set(expected_by_id):
            raise ValueError('Reviewed absorbed-town competing name inventory changed')
        for i, source in actual.items():
            expected_peer = expected_by_id[i]
            geometry = source.get('shape')
            if geometry is None:
                geometry = source.get('point')
            if (geometry is None or source['kind'] != expected_peer.get('kind')
                    or source['tags'] != expected_peer.get(tags_key)
                    or aggregate_geometry_sha256(geometry) != expected_peer.get(shape_key)):
                raise ValueError('Reviewed absorbed-town peer source changed')
        if ({i for i, source in actual.items() if source['kind'] == 'town'} != {identifier}
                or {i for i, source in actual.items() if source['kind'] == 'residential'
                    and names(source['tags'])[0] == arabic} != {member_id}):
            raise ValueError('Reviewed absorbed-town settlement identity is not unique')
    references = identity.get('reviewFiles')
    if not isinstance(references, list) or not references:
        raise ValueError('Missing absorbed-town primary evidence')
    for reference in references:
        aggregate_review_file(Path(manifest_path).parent, reference, 'absorbed-town primary evidence')



def validate_reviewed_city_display_associations(manifest_path, manifest, features, geometries,
                                                 governors, timetables, original_sources,
                                                 effective_sources, source_sha256, curation,
                                                 groups, indices, reserved_groups, reserved_members):
    """Validate an explicit city-name display policy, never a spatial base merge.

    The source residential keeps its own reference, nearest source and saved ID.
    The canonical browsing choice keeps the named city's base identity while
    manual prayer selection uses its authentic retained locality representative.
    Historical fixed-source policy is preserved as evidence, not a runtime rule.
    """
    entries = manifest.get('cityDisplayAssociations', [])
    if not isinstance(entries, list):
        raise ValueError('Invalid reviewed city display-association inventory')
    if not entries:
        return [], None
    directory = Path(manifest_path).parent
    bases = {d['id']: (g, d) for g in governors for d in g['delegations']}
    available = {d['id'] for d in timetables}
    staged, applications, used = [], [], set(reserved_members) | set(reserved_groups)

    def document(reference, label):
        value = json.loads(aggregate_review_file(directory, reference, label))
        if not isinstance(value, dict):
            raise ValueError(f'Invalid reviewed city {label}')
        return value

    for entry in entries:
        try:
            if (not isinstance(entry, dict)
                    or set(entry) != {'method', 'id', 'targetDelegationId', 'reviewEvidence'}
                    or entry['method'] != 'reviewed_city_display_association'
                    or not isinstance(entry['id'], str) or entry['id'] not in indices
                    or type(entry['targetDelegationId']) is not int
                    or entry['targetDelegationId'] not in bases
                    or entry['targetDelegationId'] not in available):
                raise ValueError('Invalid reviewed city display association')
            identifier, target = entry['id'], entry['targetDelegationId']
            target_group = f'delegation:{target}'
            governor, base = bases[target]
            proof = document(entry['reviewEvidence'], 'display policy')
            application = {key: entry[key] for key in ('method', 'id', 'targetDelegationId')}
            if (proof.get('schemaVersion') != 1 or proof.get('sourceSha256') != source_sha256
                    or proof.get('conclusion') != 'reviewed_city_display_association'
                    or proof.get('application') != application):
                raise ValueError('City display policy does not match its exact application')
            review = document(proof['sourceReview'], 'source review')
            facts = document(proof['sourceFacts'], 'direct source facts')
            registry = document(proof['officialRegistry'], 'official registry')
            if (review.get('schemaVersion') != 1
                    or review.get('status') != 'source_review_complete_policy_proposal_only'
                    or facts.get('schemaVersion') != 1 or facts.get('sourceSha256') != source_sha256
                    or review['inputPins']['sourcePbf']['sha256'] != source_sha256
                    or review['inputPins']['sourceFacts']['sha256'] != proof['sourceFacts']['sha256']
                    or review['inputPins']['officialRegistry']['sha256'] != proof['officialRegistry']['sha256']):
                raise ValueError('City display source evidence changed')
            historical_ref = proof.get('historicalPolicyProof')
            if (not isinstance(historical_ref, dict)
                    or historical_ref.get('sha256') != '91ed841f8fa31963ab89361ab8232a7b5995884314c098ac71bafedf71baeea3'):
                raise ValueError('City nearest policy lacks its immutable historical proof')
            historical_proof = document(historical_ref, 'historical fixed-source policy')
            if (historical_proof.get('application') != application
                    or review.get('historicalPolicyReview') != historical_proof['sourceReview']):
                raise ValueError('City nearest policy changed its historical identity lineage')
            historical_review = document(review['historicalPolicyReview'], 'historical source review')
            preserved_fields = ('primarySources', 'primaryFindings', 'identity', 'sourceAssertions',
                                'context', 'currentBase', 'geometryAndPrayerFacts')
            if (any(review.get(key) != historical_review.get(key) for key in preserved_fields)
                    or proof.get('sourceFacts') != historical_proof.get('sourceFacts')
                    or proof.get('officialRegistry') != historical_proof.get('officialRegistry')
                    or proof.get('requiredDistinctPair') != historical_proof.get('requiredDistinctPair')
                    or proof.get('primarySources') != historical_proof.get('primarySources')
                    or review['completeTransition']['beforeGroups']
                       != historical_review['completeTransition']['beforeGroups']):
                raise ValueError('City nearest policy altered preserved identity or historical before-groups')
            policy = review['recommendedPolicy']
            expected_policy = {
                'method': 'reviewed_city_display_association', 'sourceIdentitySupported': True,
                'requiresRootPolicyAcceptance': True,
                'scope': 'Exact city display identity with nearest available prayer selection at its retained raw locality representative.',
                'preserveRawRepresentativeAndDefault': True, 'preserveCanonicalDisplayIdentity': True,
                'selectNearestAvailableAtRetainedRepresentative': True,
                'recomputeSavedManualCanonicalIds': True,
                'preserveSavedRawManual623AndGpsSources': True,
                'basePointOutsideResidentialAcknowledged': True, 'historicalFixed468PolicySuperseded': True,
                'townNodeIsIdentityEvidenceOnly': True, 'notACompleteTownBoundaryClaim': True,
                'noGenericOrTransitiveNameMatching': True}
            if (proof.get('acceptedPolicy') != policy or policy != expected_policy
                    or any(type(policy[key]) is not type(value) for key, value in expected_policy.items())):
                raise ValueError('Missing exact retained-locality nearest-source city policy')
            identity, transition = review['identity'], review['completeTransition']
            sector_id, point_id = identity['distinctSectorId'], identity['absorbedTownNodeId']
            if (identity['residentialId'] != identifier or identity['townBaseId'] != target_group
                    or identity.get('cityArabicExactAcrossBaseResidentialNode') is not True
                    or identity.get('noCurrentCurationOrOfficialBoundaryOverride') is not True
                    or not isinstance(sector_id, str) or sector_id not in indices
                    or not isinstance(point_id, str) or not point_id.startswith('osm:node:')
                    or point_id in indices or len({identifier, sector_id, point_id}) != 3
                    or used & {identifier, sector_id, point_id, target_group}
                    or groups.get(identifier, set()) != {identifier}
                    or groups.get(sector_id, set()) != {sector_id}
                    or groups.get(target_group, set())):
                raise ValueError('City display association needs exact separate singleton choices')
            before = transition['beforeGroups']
            after = transition['afterGroups']
            expected_before = [
                {'id': sector_id, 'memberIds': sorted([sector_id, identifier]),
                 'canonicalName': identity['sectorArabic'], 'manualSource': target},
                {'id': target_group, 'memberIds': [target_group],
                 'canonicalName': identity['cityArabic'], 'manualSource': target}]
            expected_after = [
                {'id': sector_id, 'memberIds': [sector_id],
                 'canonicalName': identity['sectorArabic'], 'manualSource': target},
                {'id': target_group, 'memberIds': sorted([target_group, identifier]),
                 'canonicalName': identity['cityArabic'], 'manualSource': 623}]
            if (before != expected_before or after != expected_after
                    or transition.get('afterDistinctSourceMemberIds') != [identifier]
                    or transition.get('targetMemberIdsBefore') != [target_group]
                    or transition.get('rawChanges') != [{'id': identifier, 'field': 'pickerGroupId',
                                                        'before': sector_id, 'after': target_group}]
                    or transition.get('retirementReplacementReviewedNameChanges') is not False
                    or transition.get('allRawNamesAliasesContextsCoordinatesDefaultsPreserved') is not True
                    or transition.get('allPackedBytesPreserved') is not True):
                raise ValueError('City display transition changed beyond its reviewed grouping')
            pair = proof['requiredDistinctPair']
            if (not isinstance(pair, dict) or pair.get('ids') != [sector_id, identifier]
                    or sum(item == pair for item in manifest.get('distinctPairs', [])) != 1):
                raise ValueError('City display association lacks its exact separate-sector rule')
            aggregate_review_file(directory, pair['reviewEvidence'], 'city distinct-sector choice')
            primary_sources = proof['primarySources']
            if (not isinstance(primary_sources, list) or len(primary_sources) != len(review['primarySources'])
                    or len({item['url'] for item in primary_sources}) != len(primary_sources)):
                raise ValueError('Incomplete city primary evidence inventory')
            for source in primary_sources:
                matches = [item for item in review['primarySources'] if item['url'] == source['url']]
                if len(matches) != 1 or matches[0]['artifact']['sha256'] != source['sha256']:
                    raise ValueError('City primary evidence is not bound to its reviewed source')
                aggregate_review_file(directory, source, 'city primary source')
            assertions = review['sourceAssertions']
            expected = {item['id']: item for item in assertions}
            source_facts = {item['id']: item for item in facts['records']}
            if (len(expected) != len(assertions) or len(source_facts) != len(facts['records'])
                    or set(expected) != set(source_facts)
                    or set(expected) != set(identity['uniqueSourceNamePeerIds'])
                    or set(expected) != set(facts['globalCatalogEligibleMatchingNameIds'])
                    or not {identifier, sector_id, point_id} <= set(expected)
                    or used & set(expected)):
                raise ValueError('City source identity inventory is incomplete or repeated')
            peer_names = facts.get('peerNameValues')
            if (not isinstance(peer_names, list) or not peer_names
                    or any(not isinstance(value, str) or not value for value in peer_names)
                    or identity['cityArabic'] not in peer_names):
                raise ValueError('City source naming screen changed')
            peer_keys = {norm(value) for value in peer_names}
            for sources, prefix in ((original_sources, 'original'), (effective_sources, 'effective')):
                matching = {i for i, source in sources.items()
                            if peer_keys & {norm(value) for value in names(source['tags'])}}
                if matching != set(expected):
                    raise ValueError('City competing named-source inventory changed')
                for source_id in matching:
                    actual, assertion = sources[source_id], expected[source_id]
                    geometry = actual.get('shape')
                    if geometry is None:
                        geometry = actual.get('point')
                    if (geometry is None or actual['kind'] != assertion[f'{prefix}Kind']
                            or actual['tags'] != assertion[f'{prefix}Tags']
                            or aggregate_geometry_sha256(geometry) != assertion[f'{prefix}GeometrySha256']
                            or assertion.get('expectedCuration', 'missing') is not None
                            or curation.get(source_id) is not None):
                        raise ValueError('City source tags, kind, footprint or curation changed')
                    if prefix == 'original':
                        fact = source_facts[source_id]
                        if (fact['kind'] != actual['kind'] or fact['tags'] != actual['tags']
                                or fact['geometrySha256'] != assertion['originalGeometrySha256']
                                or aggregate_geometry_sha256(shape(fact['geometry'])) != fact['geometrySha256']):
                            raise ValueError('City original source no longer matches direct PBF facts')
                if ({i for i in matching if sources[i]['kind'] == 'town'} != {point_id}
                        or {i for i in matching if sources[i]['kind'] == 'residential'
                            and names(sources[i]['tags'])[0] == identity['cityArabic']} != {identifier}):
                    raise ValueError('City residential or named town identity is no longer unique')
            protected = {}
            for source_id, assertion in expected.items():
                if 'expectedMetadata' not in assertion:
                    if source_id in indices:
                        raise ValueError('An absorbed city source unexpectedly became a raw choice')
                    continue
                if source_id not in indices or indices[source_id] >= len(geometries):
                    raise ValueError('A reviewed city polygon is no longer retained')
                actual = features[indices[source_id]]
                ignored = {'pickerGroupId', 'offset', 'length'}
                metadata = {key: value for key, value in assertion['expectedMetadata'].items() if key not in ignored}
                if ({key: value for key, value in actual.items() if key not in ignored} != metadata
                        or actual.get('sourceId') != 'osm' or actual.get('hasBoundary') is not True
                        or hashlib.sha256(packed_geometry_bytes(geometries[indices[source_id]])).hexdigest()
                           != assertion['packedGeometrySha256']):
                    raise ValueError('City raw metadata or packed polygon changed')
                protected[source_id] = metadata
            residential, sector = features[indices[identifier]], features[indices[sector_id]]
            point = effective_sources[point_id].get('point')
            if (point is None or point.geom_type != 'Point' or original_sources[point_id].get('shape') is not None
                    or effective_sources[point_id].get('shape') is not None
                    or residential['kind'] != 'residential' or sector['kind'] != 'sector'
                    or residential['name'] != identity['cityArabic'] or base['nomAr'] != identity['cityArabic']
                    or sector['name'] != identity['sectorArabic'] or residential['name'] == sector['name']
                    or review['currentBase'] != {**base, 'governorateId': governor['id']}
                    or any(features[indices[i]]['governorateId'] != governor['id'] for i in protected)
                    or [d['id'] for g in governors for d in g['delegations']
                        if d['nomAr'] == identity['cityArabic']] != [target]
                    or any(source['tags'].get('name:ar') != identity['cityArabic']
                           or source['tags'].get('wikidata') != identity['cityNodeAndResidentialSharedWikidata']
                           for source in (original_sources[identifier], effective_sources[identifier],
                                          original_sources[point_id], effective_sources[point_id]))):
                raise ValueError('Exact combined-city identity or original base changed')
            context = review['context']
            parent_id = context['originalDelegationSource']['id']
            if (parent_id not in expected or context['originalDelegationSource'] != expected[parent_id]
                    or expected[parent_id]['originalKind'] != 'delegation'
                    or context.get('fullResidentialCoveredBySourceDelegation') is not False):
                raise ValueError('City administrative context evidence changed')
            code = effective_sources[parent_id]['tags'].get('ref:tn:codegeo', '')
            records = [r for r in registry['sectors'] if r['delegationCode'] == code]
            if (not isinstance(code, str) or not re.fullmatch(r'\d{4}', code)
                    or records != context['currentOfficialRecords'] or not records
                    or registry['sources'] != context['officialWorkbookSources']
                    or any(r['governorateCode'] != code[:2] or r['delegationAr'] != base['nomAr']
                           or norm(r['governorateAr']) != norm(governor['nomAr'])
                           or len(r.get('sourceRows', {})) != 3 for r in records)):
                raise ValueError('City current official administrative identity changed')
            claims = {}
            for source_id in expected:
                source = effective_sources[source_id]
                claimed_code = source['tags'].get('ref:tn:codegeo')
                if claimed_code:
                    actual_claims = sorted(i for i, s in effective_sources.items()
                                           if s['kind'] == source['kind'] and s['tags'].get('ref:tn:codegeo') == claimed_code)
                    if actual_claims != [source_id]:
                        raise ValueError('City administrative code is no longer unique')
                    claims[claimed_code] = actual_claims
                if source['kind'] == 'sector':
                    matches = [r for r in records if r['sectorCode'] == claimed_code]
                    if len(matches) != 1 or matches[0]['sectorAr'] != names(source['tags'])[0]:
                        raise ValueError('City or sibling imada source identity changed')
            if claims != identity['uniqueCurrentCodeClaims'] or claims != facts['administrativeCodeClaims']:
                raise ValueError('City complete administrative code inventory changed')
            relations = review['geometryAndPrayerFacts']['referencePoints']
            point_rows = {'raw_residential_manual_reference': residential, 'raw_sector_manual_reference': sector,
                          'absorbed_town_node': {'lat': point.y, 'lng': point.x}, 'existing_meteo_base468': base}
            if len(relations) != len(point_rows) or {r['role'] for r in relations} != set(point_rows):
                raise ValueError('City reference-point evidence inventory changed')
            for relation in relations:
                ref = point_rows[relation['role']]
                if relation['lat'] != ref['lat'] or relation['lng'] != ref['lng']:
                    raise ValueError('City reference coordinates changed')
                nearest = sorted(timetables, key=lambda d: (distance(ref['lat'], ref['lng'], d), d['id']))[:3]
                if [d['id'] for d in nearest] != [d['id'] for d in relation['nearestUsableSources']]:
                    raise ValueError('City reviewed raw or canonical nearest-source relation changed')
                q = Point(ref['lng'], ref['lat'])
                if set(relation['containment']) != set(expected) - {point_id}:
                    raise ValueError('City reference containment inventory changed')
                for source_id, relation_claim in relation['containment'].items():
                    if (original_sources[source_id]['shape'].covers(q) != relation_claim['originalCovers']
                            or effective_sources[source_id]['shape'].covers(q) != relation_claim['originalCovers']
                            or (source_id in indices and geometries[indices[source_id]].covers(q)
                                != relation_claim.get('packedCovers'))):
                        raise ValueError('City reviewed containment or noncontainment changed')
            base_point = Point(base['lng'], base['lat'])
            if (residential['delegationId'] != 623 or target != 468 or sector['delegationId'] != target
                    or original_sources[identifier]['shape'].covers(base_point)
                    or effective_sources[identifier]['shape'].covers(base_point)
                    or geometries[indices[identifier]].covers(base_point)
                    or point.equals(base_point)
                    or not original_sources[identifier]['shape'].covers(original_sources[point_id]['point'])
                    or not effective_sources[identifier]['shape'].covers(point)
                    or not geometries[indices[identifier]].covers(point)
                    or original_sources[parent_id]['shape'].covers(original_sources[identifier]['shape'])
                    or effective_sources[parent_id]['shape'].covers(effective_sources[identifier]['shape'])):
                raise ValueError('City explicit raw623/canonical468/noncontainment policy no longer applies')
            # This exact one-city policy is not a configurable nearest-source bypass.
            # The retained raw metadata and packed geometry were bound above and
            # are checked again after all overrides by the final display verifier.
            manual_selection = proof.get('manualPrayerSelection')
            expected_selection = {
                'method': 'nearest_available_at_retained_locality', 'displayGroupId': 'delegation:468',
                'representativeId': 'osm:way:174739936',
                'representative': {'lat': 36.465982, 'lng': 10.74479},
                'expectedCurrentNearestSourceId': 623,
                'expectedNearestSourceCoordinates': {'lat': 36.4561, 'lng': 10.7376},
                'expectedNearestDistanceKm': 1.2731349096201334,
                'historicalCanonicalSourceId': 468,
                'distanceMetric': 'haversine_6371km_distance_then_id',
                'savedManualCanonicalIdsRecompute': True, 'savedRawLocalityIdsPreserved': True,
                'gpsUsesActualFix': True}
            nearest_manual = min(timetables, key=lambda d: (
                distance(residential['lat'], residential['lng'], d), d['id']))
            if (manual_selection != expected_selection
                    or any(type(manual_selection[key]) is not type(value)
                           for key, value in expected_selection.items())
                    or review.get('manualPrayerSelection') != manual_selection
                    or identifier != expected_selection['representativeId']
                    or target_group != expected_selection['displayGroupId']
                    or {key: residential[key] for key in ('lat', 'lng')} != expected_selection['representative']
                    or nearest_manual['id'] != expected_selection['expectedCurrentNearestSourceId']
                    or residential['delegationId'] != nearest_manual['id']
                    or {key: nearest_manual[key] for key in ('lat', 'lng')}
                       != expected_selection['expectedNearestSourceCoordinates']
                    or abs(distance(residential['lat'], residential['lng'], nearest_manual)
                           - expected_selection['expectedNearestDistanceKm']) > 1e-9):
                raise ValueError('City retained manual representative or nearest-source result changed')
            staged.append((target_group, {identifier}))
            used.update(expected)
            used.add(target_group)
            applications.append({'id': identifier, 'pickerGroupId': target_group,
                                 'reviewEvidence': entry['reviewEvidence'], 'finalGroups': expected_after,
                                 'protectedMetadata': protected, 'preservedRawSource': residential['delegationId'],
                                 'canonicalManualSource': nearest_manual['id'],
                                 'manualPrayerSelection': manual_selection, 'preservedAbsorbedPointId': point_id})
        except (OSError, UnicodeError, ValueError, KeyError, TypeError, AttributeError, IndexError) as error:
            raise ValueError('Missing, malformed or changed reviewed city display policy') from error
    return staged, {'reviewedGroupCount': len(staged), 'preservedMemberCount': len(staged),
                    'applications': applications}


def source_bound_residential_boundary_records(proof, directory, records, sector_ids,
                                              timetables, source_sha256,
                                              reviewed_boundaries, official_report):
    """Migrate only explicitly accepted imada records, preserving town evidence."""
    lineage = json.loads(aggregate_review_file(directory, proof['sourcePhaseLineage'],
                                               'residential boundary source lineage'))
    if (lineage.get('schemaVersion') != 1
            or lineage.get('method') != 'reviewed_source_bound_residential_official_boundary_lineage'
            or lineage.get('sourceSha256') != source_sha256
            or lineage.get('application') != proof['application']
            or lineage.get('historicalSourceFacts') != proof['sourceFacts']):
        raise ValueError('Residential boundary lineage identity changed')
    reference = lineage['reviewedBoundaries']
    manifest = load_reviewed_boundary_manifest_for_lineage(
        directory, reference, reviewed_boundaries, official_report)
    replacements = lineage['replacements']
    changed = {row['id'] for row in replacements}
    if not changed or len(changed) != len(replacements) or not changed <= sector_ids:
        raise ValueError('Residential lineage needs unique protected imada replacements')
    migrated = dict(records)
    record_fields = {'effectiveTags', 'effectiveSourceId', 'effectiveGeometrySha256',
                     'currentMetadata', 'packedGeometrySha256'}
    metadata_fields = {'sourceId', 'name', 'aliases', 'parentName', 'contextAliases',
                       'lat', 'lng', 'bbox', 'areaKm2', 'delegationId'}
    for replacement in replacements:
        identifier = replacement['id']
        before, after = replacement['originalRecord'], replacement['effectiveRecord']
        application = replacement['acceptedApplication']
        if (before != records[identifier] or before['kind'] != 'sector'
                or set(before) != set(after)
                or any(before[k] != after[k] for k in before if k not in record_fields)
                or application != {'id': identifier, 'action': 'replace',
                                   'officialCode': before['originalTags']['ref:tn:codegeo'],
                                   'sourceId': after['effectiveSourceId']}
                or after['effectiveSourceId'] == 'osm'
                or [r for r in official_report['applications'] if r['id'] == identifier] != [application]):
            raise ValueError('Residential lineage altered historical evidence or loader application')
        sources = [s for s in manifest['sources'] if s['id'] == application['sourceId']]
        if len(sources) != 1:
            raise ValueError('Residential lineage provider is not unique')
        source = sources[0]
        accepted = [r for r in source['records'] if r['id'] == identifier]
        if (len(accepted) != 1 or accepted[0]['action'] != 'replace'
                or accepted[0]['officialCode'] != application['officialCode']
                or accepted[0]['expectedOriginalTags'] != before['originalTags']
                or source['sourceSha256'] != source_sha256 or not source.get('review')
                or official_report['sources'].get(source['id'])
                    != {k: v for k, v in source.items() if k not in ('file', 'records')}
                or after['effectiveTags'].get('ref:tn:codegeo') != application['officialCode']):
            raise ValueError('Residential lineage differs from its accepted boundary provider')
        old, new = before['currentMetadata'], after['currentMetadata']
        if (set(old) != set(new)
                or any(old[k] != new[k] for k in old if k not in metadata_fields)
                or new['sourceId'] != source['id']
                or new['name'] != names(after['effectiveTags'])[0]
                or min(timetables, key=lambda d: (distance(new['lat'], new['lng'], d), d['id']))['id']
                    != new['delegationId']):
            raise ValueError('Residential lineage altered unreviewed metadata or nearest source')
        migrated[identifier] = after
    return migrated, changed


def validate_source_bound_residential_display(entry, directory, current, indices, groups,
                                              geometries, base, governor, timetables,
                                              original_sources, effective_sources,
                                              source_sha256, curation, used,
                                              reviewed_boundaries=None, official_report=None):
    """Validate one explicitly reviewed residential/base identity without mutation.

    Bilingual residential addresses can establish identity without an extracted
    city point. Existing imada groups and curated context remain exact inputs.
    """
    document = lambda ref, label: json.loads(aggregate_review_file(directory, ref, label))
    proof = document(entry['reviewEvidence'], 'source-bound residential display')
    review = document(proof['sourceReview'], 'three-town source review')
    facts = document(proof['sourceFacts'], 'three-town original/effective source facts')
    registry = document(proof['officialRegistry'], 'three-town official imada inventory')
    identifier, target = entry['id'], entry['targetDelegationId']
    target_group = f'delegation:{target}'
    application = {k: entry[k] for k in ('method', 'id', 'targetDelegationId')}
    decisions = [r for r in review['candidates'] if r['baseId'] == target]
    if (proof.get('schemaVersion') != 2 or proof.get('sourceSha256') != source_sha256
            or proof.get('application') != application
            or proof.get('conclusion') != 'reviewed_source_bound_residential_base_display_identity'
            or review.get('status') != 'THREE_RESIDENTIAL_TOWN_IDENTITIES_SUPPORTED_SOURCE_ONLY_NO_IMPLEMENTATION'
            or len(decisions) != 1 or facts.get('schemaVersion') != 1
            or facts.get('sourceSha256') != source_sha256
            or facts['triage']['sha256'] != proof['sourceReview']['sha256']
            or facts['cachedSource']['sha256'] != review['taskEvidencePins']['work/geo/osm-areas.json']
            or proof['officialRegistry']['sha256'] != review['taskEvidencePins']['outputs/current-official-sector-registry.json']):
        raise ValueError('Source-bound residential review does not bind the original evidence')
    decision = decisions[0]
    expected = proof['expected']
    parent_id = decision['geographicChecks']['cachedDelegation']['id']
    sector_ids = {r['id'] for r in decision['preservedImadas']}
    protected_ids = {identifier} | {i for r in decision['preservedImadas'] for i in r['completeGroupMemberIds']}
    records = {i: facts['records'][i] for i in expected['sourceAssertionIds']}
    governor_ids = [i for i, r in records.items() if r['kind'] == 'governorate']
    if (expected['application'] != application or decision['rawId'] != identifier
            or expected['currentBase'] != decision['base']
            or expected['preservedGovernorate'] != {k: governor[k] for k in ('id', 'nomAr', 'nomFr', 'nomEn', 'lat', 'lng')}
            or decision['governorate'] != expected['preservedGovernorate']
            or set(expected['protectedRawIds']) != protected_ids
            or len(expected['sourceAssertionIds']) != len(records) or len(governor_ids) != 1
            or expected['officialRecords'] != [r['official'] for r in decision['preservedImadas']]
            or expected['sourceNamePeerInventory'] != decision['matchingSourceInventory']
            or expected['exactReviewedContainment'] != decision['geographicChecks']):
        raise ValueError('Source-bound full town/context inventory changed')
    place = expected['placeEvidence']
    point_id = place['id']
    extracted = place['role'] in ('absorbed_extracted_village', 'absorbed_extracted_town')
    expected_ids = protected_ids | {parent_id, governor_ids[0]} | ({point_id} if extracted else set())
    reserved = protected_ids | {point_id, target_group}
    if (set(records) != expected_ids or used & reserved or groups.get(identifier) != {identifier}
            or groups.get(target_group) or point_id in current
            or set(expected['beforeGroups']) != {target_group, identifier, *[current[i]['pickerGroupId'] for i in sector_ids]}):
        raise ValueError('Source-bound display association collides with another reviewed choice')
    for group_id, members in expected['beforeGroups'].items():
        actual = groups.get(group_id, set()) | ({group_id} if group_id.startswith('delegation:') else set())
        if len(members) != len(set(members)) or actual != set(members):
            raise ValueError('Source-bound imada or settlement group membership changed')
    boundary_replacements = set()
    if 'sourcePhaseLineage' in proof:
        records, boundary_replacements = source_bound_residential_boundary_records(
            proof, directory, records, sector_ids, timetables, source_sha256,
            reviewed_boundaries, official_report)
    for source_id, wanted in records.items():
        if curation.get(source_id) != wanted['expectedCuration']:
            raise ValueError('Source-bound existing curation changed')
        effective_provider = wanted['effectiveSourceId'] if source_id in boundary_replacements else 'osm'
        for source_map, prefix, provider in ((original_sources, 'original', None),
                                             (effective_sources, 'effective', effective_provider)):
            actual = source_map.get(source_id)
            geometry = actual.get('shape') if actual else None
            if geometry is None and actual is not None:
                geometry = actual.get('point')
            if (actual is None or actual.get('sourceId') != provider
                    or wanted[prefix + 'SourceId'] != provider or actual['kind'] != wanted['kind']
                    or actual['tags'] != wanted[prefix + 'Tags'] or geometry is None
                    or aggregate_geometry_sha256(geometry) != wanted[prefix + 'GeometrySha256']):
                raise ValueError('Source-bound original/effective source tags or shape changed')
        if source_id in protected_ids:
            actual = current.get(source_id)
            if (actual is None or {k: v for k, v in actual.items() if k not in {'offset', 'length'}} != wanted['currentMetadata']):
                raise ValueError('Source-bound protected raw metadata changed')
            packed_sha = (hashlib.sha256(packed_geometry_bytes(geometries[indices[source_id]])).hexdigest()
                          if actual['hasBoundary'] else None)
            if packed_sha != wanted['packedGeometrySha256']:
                raise ValueError('Source-bound protected packed geometry changed')
    residential = current[identifier]
    tags = records[identifier]['originalTags']
    code = expected['officialDelegationCode']
    rows = [r for r in registry['sectors'] if r['delegationCode'] == code]
    if (residential['kind'] != 'residential' or residential['sourceId'] != 'osm'
            or residential['governorateId'] != governor['id'] or residential['delegationId'] != target
            or {k: v for k, v in decision['residential']['current'].items() if k not in {'offset', 'length'}} != expected['currentRaw']
            or expected['currentRaw'] != records[identifier]['currentMetadata']
            or tags != decision['residential']['tags'] or not re.fullmatch(r'\d{4}', code)
            or code != decision['officialDelegationCode'] or rows != expected['officialRecords']
            or len(rows) != len(sector_ids) or records[parent_id]['kind'] != 'delegation'
            or records[parent_id]['originalTags'].get('ref:tn:codegeo') != code
            or records[governor_ids[0]]['originalTags'].get('ref:tn:codegeo') != code[:2]):
        raise ValueError('Source-bound raw town or official administrative identity changed')
    for source_map in (original_sources, effective_sources):
        actual_sectors = {i for i, s in source_map.items() if s['kind'] == 'sector'
                          and s['tags'].get('ref:tn:codegeo', '').startswith(code)}
        if actual_sectors != sector_ids:
            raise ValueError('Source-bound complete imada inventory changed')
        for source_id in {parent_id, governor_ids[0], *sector_ids}:
            actual = source_map[source_id]
            claims = {i for i, s in source_map.items() if s['kind'] == actual['kind']
                      and s['tags'].get('ref:tn:codegeo') == actual['tags'].get('ref:tn:codegeo')}
            if claims != {source_id}:
                raise ValueError('Source-bound administrative code is no longer unique')
        peers = decision['matchingSourceInventory']
        query = set(peers['queryNormalizedNames'])
        actual_peers = {i for i, s in source_map.items() if query & {norm(v) for v in names(s['tags'])}}
        if actual_peers != {r['id'] for r in peers['cachedAreas'] + peers['cachedNodes']}:
            raise ValueError('Source-bound competing name inventory changed')
    if {records[i]['originalTags']['ref:tn:codegeo'] for i in sector_ids} != {r['sectorCode'] for r in rows}:
        raise ValueError('Source-bound imada codes differ from the official registry')
    correction = expected.get('baseNameCorrection')
    expected_base = correction['afterBase'] if correction else expected['currentBase']
    if base != expected_base:
        raise ValueError('Source-bound canonical base reference or name changed')
    coordinate_manifest = json.loads(display_identity_coordinate_bytes(directory))
    current_corrections = [r for r in coordinate_manifest['corrections'] if r['delegationId'] == target]
    expected_correction = expected['coordinateCorrectionAfter']
    if current_corrections != ([expected_correction] if expected_correction else []):
        raise ValueError('Source-bound own coordinate correction or expected names changed')
    before_correction = expected['coordinateCorrectionBefore']
    if extracted:
        response = document(proof['placeResponse'], 'source-bound settlement response')
        payloads = [r for r in response['elements'] if r.get('type') == 'node' and f"osm:node:{r['id']}" == point_id]
        payload = place['exactResponseElement']
        point = effective_sources[point_id]['point']
        point_tags = records[point_id]['originalTags']
        if (not place['requiredForIdentity'] or payloads != [payload]
                or payload != decision['supportingPlace']['frozenLivePayload']
                or payload['tags'] != point_tags or [payload['lat'], payload['lon']] != [point.y, point.x]
                or [point.y, point.x] != [base['lat'], base['lng']]
                or records[point_id]['kind'] not in ('village', 'town')
                or before_correction != decision['supportingPlace']['exactCurrentCorrection']
                or proof['placeResponse']['sha256'] != review['taskEvidencePins']['work/source-coordinates/candidate-place-nodes-second-live.json']):
            raise ValueError('Source-bound extracted settlement identity changed')
    else:
        if (place['role'] != 'excluded_city_supplement_only' or place['requiredForIdentity'] is not False
                or point_id in original_sources or point_id in effective_sources or 'city' in PLACES
                or point_id != decision['supportingPlace']['supplementalCachedCityNode']['id']
                or expected_correction is not None or before_correction is not None):
            raise ValueError('Source-bound excluded city was incorrectly required or restored')
        point = None
        point_tags = None
    mode = expected['identityMode']
    if mode == 'bilingual_residential_address':
        if (extracted or correction is not None or tags.get('name') != base['nomFr']
                or tags.get('addr:city') != base['nomAr'] or 'name:ar' in tags):
            raise ValueError('Source-bound bilingual residential address changed')
    elif mode == 'explicit_residential_alias_to_extracted_place':
        if (not extracted or correction is not None or tags.get('name:ar') != residential['name']
                or tags.get('addr:city') != residential['name']
                or tags.get('alt_name:ar') != base['nomAr'] or point_tags.get('name:ar') != base['nomAr']
                or tags.get('name:fr') != point_tags.get('name:fr')):
            raise ValueError('Source-bound explicit residential/settlement alias changed')
    elif mode == 'extracted_place_with_official_base_spelling':
        if (not extracted or correction is None or tags.get('name:ar') != base['nomAr']
                or point_tags.get('name:ar') != base['nomAr'] or residential['name'] != base['nomAr']
                or tags.get('addr:city') != tags.get('name:fr') or tags.get('name:fr') != point_tags.get('name:fr')
                or tags.get('wikipedia') != point_tags.get('wikipedia')
                or tags.get('addr:postcode') != point_tags.get('addr:postcode')
                or {r['delegationAr'] for r in rows} != {base['nomAr']}):
            raise ValueError('Source-bound official base spelling changed')
    else:
        raise ValueError('Unsupported exact residential identity evidence mode')
    base_point = Point(base['lng'], base['lat'])
    geography = decision['geographicChecks']
    if geography['currentBaseInsideOriginalAndPackedResidential'] is not True:
        raise ValueError('Source-bound base containment was not reviewed')
    for polygon in (original_sources[identifier]['shape'], effective_sources[identifier]['shape'], geometries[indices[identifier]]):
        if not polygon.covers(base_point) or (point is not None and not polygon.covers(point)):
            raise ValueError('Source-bound unchanged reference left its residential footprint')
    for source_map in (original_sources, effective_sources):
        if source_map[parent_id]['shape'].covers(source_map[identifier]['shape']) != geography['residentialFullyInsideCachedDelegation']:
            raise ValueError('Source-bound reviewed delegation containment changed')
    for reference in (base, residential):
        if min(timetables, key=lambda d: (distance(reference['lat'], reference['lng'], d), d['id']))['id'] != target:
            raise ValueError('Source-bound preserved prayer default changed')
    if not proof['primarySources']:
        raise ValueError('Source-bound identity requires primary corroboration')
    reviewed_primary = next(r for r in review['primaryCorroboration'] if r['baseId'] == target)
    primary_sha = (reviewed_primary['evidence']['sha256'] if 'evidence' in reviewed_primary
                   else review['taskEvidencePins'][reviewed_primary['file']])
    if primary_sha not in {r['sha256'] for r in proof['primarySources']}:
        raise ValueError('Source-bound primary locality evidence was omitted')
    for reference in proof['primarySources']:
        aggregate_review_file(directory, reference, 'source-bound primary locality evidence')
    aliases, saved_name = list(residential['aliases']), None
    if correction:
        addendum = document(proof['baseNameReview'], 'source-bound saved base spelling')
        before = expected['currentBase']
        saved_name = {'id': target_group, 'name': base['nomAr'], 'kind': 'delegation'}
        expected_after_correction = {**before_correction, 'expectedNames': {**before_correction['expectedNames'], 'nomAr': base['nomAr']}}
        if (correction['beforeBase'] != before or base != {**before, 'nomAr': residential['name']}
                or correction['savedName'] != saved_name or correction['historicalSearchAlias'] != before['nomAr']
                or before['nomAr'] in aliases or before['nomAr'] == base['nomAr']
                or expected_correction != expected_after_correction
                or addendum['sourceReview']['sha256'] != proof['sourceReview']['sha256']
                or addendum['correction'] != correction
                or addendum['coordinateCorrectionBefore'] != before_correction
                or addendum['coordinateCorrectionAfter'] != expected_correction):
            raise ValueError('Source-bound saved spelling/own correction review changed')
        aliases = sorted(set(aliases + [before['nomAr']]))
    elif before_correction != expected_correction:
        raise ValueError('Source-bound unreviewed coordinate correction change')
    final_groups = {k: v for k, v in expected['beforeGroups'].items() if k != identifier}
    final_groups[target_group] = sorted([target_group, identifier])
    if final_groups != expected['afterGroups']:
        raise ValueError('Source-bound final complete group inventory changed')
    protected = {i: {k: v for k, v in current[i].items() if k not in {'pickerGroupId', 'offset', 'length'}} for i in protected_ids}
    protected[identifier] = {**protected[identifier], 'aliases': aliases}
    result = {'id': identifier, 'pickerGroupId': target_group, 'reviewEvidence': entry['reviewEvidence'],
              'finalGroups': [{'id': i, 'memberIds': ids} for i, ids in final_groups.items()],
              'protectedMetadata': protected, 'preservedRawSource': target, 'canonicalManualSource': target,
              'preservedNonCatalogPlace': {'id': point_id, 'role': place['role']}}
    return (identifier, target_group, aliases), result, saved_name, reserved


def _validated_noncoincident_name_only_changes(proof, lineage, directory, source_sha256, curation):
    if 'nameOnlySourceReview' not in lineage:
        return {}
    if not isinstance(curation, dict):
        raise ValueError('Noncoincident name-only live curation changed')
    review_reference = lineage['nameOnlySourceReview']
    review = json.loads(aggregate_review_file(
        directory, review_reference, 'noncoincident name-only lineage review'))
    review_keys = {'schemaVersion', 'method', 'sourceSha256', 'application', 'priorLineage',
                   'priorProof', 'nameCorrectionReview', 'changes'}
    if not isinstance(review, dict) or set(review) != review_keys:
        raise ValueError('Noncoincident name-only review shape changed')
    if (review['schemaVersion'] != 1
            or review['method'] != 'reviewed_noncoincident_name_only_lineage_successor'):
        raise ValueError('Noncoincident name-only review method changed')
    if (review['sourceSha256'] != source_sha256
            or proof.get('sourceSha256') != source_sha256
            or lineage.get('sourceSha256') != source_sha256):
        raise ValueError('Noncoincident name-only review source identity changed')
    if (review['application'] != proof.get('application')
            or lineage.get('application') != review['application']):
        raise ValueError('Noncoincident name-only review application changed')
    changes = review['changes']
    change_keys = {'id', 'curation', 'beforeEffective', 'afterEffective',
                   'beforeMetadata', 'afterMetadata'}
    if (not isinstance(changes, list) or not changes
            or any(not isinstance(change, dict) or set(change) != change_keys
                   for change in changes)):
        raise ValueError('Noncoincident name-only change inventory changed')
    change_ids = [change['id'] for change in changes]
    if (any(not isinstance(identifier, str) or not identifier for identifier in change_ids)
            or len(change_ids) != len(set(change_ids))):
        raise ValueError('Noncoincident name-only change inventory changed')
    if 'sourcePhaseLineage' not in proof:
        raise ValueError('Noncoincident name-only current proof lineage reference changed')
    prior_lineage = json.loads(aggregate_review_file(
        directory, review['priorLineage'], 'noncoincident prior lineage'))
    prior_proof = json.loads(aggregate_review_file(
        directory, review['priorProof'], 'noncoincident prior proof'))
    name_review = json.loads(aggregate_review_file(
        directory, review['nameCorrectionReview'], 'noncoincident name correction review'))
    if (not isinstance(prior_lineage, dict) or not isinstance(prior_proof, dict)
            or not isinstance(name_review, dict)):
        raise ValueError('Noncoincident name-only pinned JSON changed')
    if 'nameOnlySourceReview' in prior_lineage:
        raise ValueError('Nested noncoincident name-only lineage successor')
    if prior_proof.get('sourcePhaseLineage') != review['priorLineage']:
        raise ValueError('Noncoincident name-only prior proof lineage reference changed')
    expected_proof = json.loads(json.dumps(prior_proof))
    expected_proof['sourcePhaseLineage'] = proof['sourcePhaseLineage']
    if expected_proof != proof:
        raise ValueError('Noncoincident name-only proof successor changed')
    prior_states_list = prior_lineage.get('sourceStates')
    prior_protected_list = prior_lineage.get('protectedRaw')
    if (not isinstance(prior_states_list, list) or not isinstance(prior_protected_list, list)
            or any(not isinstance(row, dict) or set(row) != {'id', 'original', 'effective'}
                   for row in prior_states_list)
            or any(not isinstance(row, dict)
                   or set(row) != {'id', 'expectedMetadata', 'packedGeometrySha256'}
                   for row in prior_protected_list)):
        raise ValueError('Noncoincident name-only prior lineage inventory changed')
    prior_states = {row['id']: row for row in prior_states_list}
    prior_protected = {row['id']: row for row in prior_protected_list}
    if (len(prior_states) != len(prior_states_list)
            or len(prior_protected) != len(prior_protected_list)):
        raise ValueError('Noncoincident name-only prior lineage inventory changed')
    replacements = prior_lineage.get('acceptedReplacements')
    if not isinstance(replacements, list):
        raise ValueError('Noncoincident name-only accepted replacements changed')
    accepted_ids = set()
    for replacement in replacements:
        if not isinstance(replacement, dict) or not isinstance(replacement.get('id'), str):
            raise ValueError('Noncoincident name-only accepted replacements changed')
        accepted_ids.add(replacement['id'])
    if len(accepted_ids) != len(replacements):
        raise ValueError('Noncoincident name-only accepted replacements changed')
    corrections = name_review.get('changes')
    if not isinstance(corrections, list):
        raise ValueError('Noncoincident name correction review changes changed')
    correction_by_id = {}
    for correction in corrections:
        if not isinstance(correction, dict):
            raise ValueError('Noncoincident name correction record changed')
        identifier = correction.get('id')
        nested = correction.get('proposal')
        if not isinstance(identifier, str) and isinstance(nested, dict):
            identifier = nested.get('id')
        if not isinstance(identifier, str) or not identifier:
            raise ValueError('Noncoincident name correction record changed')
        if identifier in correction_by_id:
            raise ValueError('Duplicate noncoincident name correction record')
        correction_by_id[identifier] = correction
    source_kinds = proof.get('sourceKinds')
    if not isinstance(source_kinds, dict):
        raise ValueError('Noncoincident name-only source kinds changed')
    declared = {}
    for change in changes:
        identifier = change['id']
        if source_kinds.get(identifier) != 'sector':
            raise ValueError('Noncoincident name-only ID is not a sector')
        if identifier not in prior_states or identifier not in prior_protected:
            raise ValueError('Noncoincident name-only ID is not in prior lineage')
        if identifier in accepted_ids:
            raise ValueError('Noncoincident name-only ID was already replaced')
        correction = correction_by_id.get(identifier)
        if correction is None:
            raise ValueError('Missing noncoincident name correction record')
        proposal = correction.get('proposal', correction)
        if not isinstance(proposal, dict):
            raise ValueError('Noncoincident name correction proposal changed')
        if 'id' in proposal and proposal.get('id') != identifier:
            raise ValueError('Noncoincident name correction proposal ID changed')
        original = prior_states[identifier]['original']
        if (not isinstance(original, dict) or original.get('kind') != 'sector'
                or not isinstance(original.get('tags'), dict)):
            raise ValueError('Noncoincident name-only original row changed')
        tags = original['tags']
        code = tags.get('ref:tn:codegeo')
        if not isinstance(code, str) or not code:
            raise ValueError('Noncoincident name-only original sector code changed')
        if proposal.get('phoneNameFacetSupported') is not True:
            raise ValueError('Noncoincident name-only phone name facet unsupported')
        phone_evidence = proposal.get('phoneNameEvidence')
        if (not isinstance(phone_evidence, dict)
                or phone_evidence.get('rootScreenshotReviewed') is not True):
            raise ValueError('Noncoincident name-only root screenshot not reviewed')
        source_record = proposal.get('sourceRecord')
        if (not isinstance(source_record, dict)
                or source_record.get('id') != identifier
                or source_record.get('kind') != original.get('kind')
                or source_record.get('tags') != tags):
            raise ValueError('Noncoincident name-only source record changed')
        official_registry = proposal.get('officialRegistryRecord')
        if (proposal.get('officialSectorCode') != code
                or not isinstance(official_registry, dict)
                or official_registry.get('sectorCode') != code):
            raise ValueError('Noncoincident name-only official sector code changed')
        old_name = proposal.get('oldName')
        new_name = proposal.get('newName')
        old_aliases = proposal.get('oldAliases')
        proposed_aliases = proposal.get('proposedAliases')
        if (not isinstance(old_name, str) or not old_name
                or not isinstance(new_name, str) or not new_name
                or not isinstance(old_aliases, list)
                or any(not isinstance(alias, str) for alias in old_aliases)
                or not isinstance(proposed_aliases, list)
                or any(not isinstance(alias, str) for alias in proposed_aliases)):
            raise ValueError('Noncoincident name-only proposed names changed')
        if official_registry.get('sectorAr') != new_name:
            raise ValueError('Noncoincident name-only official Arabic name changed')
        before_effective = change['beforeEffective']
        before_metadata = change['beforeMetadata']
        if before_effective != prior_states[identifier]['effective']:
            raise ValueError('Noncoincident name-only before effective changed')
        if before_metadata != prior_protected[identifier]['expectedMetadata']:
            raise ValueError('Noncoincident name-only before metadata changed')
        if not isinstance(before_effective, dict) or not isinstance(before_metadata, dict):
            raise ValueError('Noncoincident name-only before rows changed')
        if before_effective != {**original, 'sourceId': 'osm'}:
            raise ValueError('Noncoincident name-only prior source was not unchanged')
        if (before_metadata.get('name') != old_name
                or before_metadata.get('aliases') != old_aliases):
            raise ValueError('Noncoincident name-only prior names changed')
        point = proposal.get('currentPointToKeep')
        if (not isinstance(point, dict)
                or 'lat' not in before_metadata or 'lng' not in before_metadata
                or point.get('lat') != before_metadata.get('lat')
                or point.get('lng') != before_metadata.get('lng')
                or 'governorateId' not in before_metadata
                or proposal.get('governorateIdToKeep') != before_metadata.get('governorateId')
                or 'delegationId' not in before_metadata
                or proposal.get('nearestDelegationIdToKeep') != before_metadata.get('delegationId')
                or 'parentName' not in before_metadata
                or proposal.get('parentNameToKeep') != before_metadata.get('parentName')):
            raise ValueError('Noncoincident name-only geography changed')
        cur = change['curation']
        curation_keys = {'id', 'expectedKind', 'expectedTags', 'action', 'nameTags',
                         'reason', 'evidence'}
        if (not isinstance(cur, dict) or set(cur) != curation_keys
                or cur.get('id') != identifier
                or cur.get('expectedKind') != 'sector'
                or cur.get('expectedTags') != tags
                or cur.get('action') != 'name_tags'):
            raise ValueError('Noncoincident name-only curation changed')
        if curation.get(identifier) != cur:
            raise ValueError('Noncoincident name-only live curation changed')
        if (not isinstance(cur.get('reason'), str) or not cur['reason'].strip()
                or not isinstance(cur.get('evidence'), str) or not cur['evidence'].strip()):
            raise ValueError('Noncoincident name-only curation lacks reason or evidence')
        name_tags = cur.get('nameTags')
        if not isinstance(name_tags, dict) or not name_tags:
            raise ValueError('Noncoincident name-only name tags changed')
        for key, value in name_tags.items():
            if (not isinstance(key, str) or not is_current_name_tag(key)
                    or not isinstance(value, str)):
                raise ValueError('Noncoincident name-only name tags changed')
        if names(name_tags) != [new_name] + proposed_aliases:
            raise ValueError('Noncoincident name-only name tags changed')
        if new_name == old_name or old_name not in proposed_aliases:
            raise ValueError('Noncoincident name-only name did not change')
        after_effective = change['afterEffective']
        after_metadata = change['afterMetadata']
        if not isinstance(after_effective, dict) or not isinstance(after_metadata, dict):
            raise ValueError('Noncoincident name-only successor rows changed')
        if set(after_effective) != set(before_effective):
            raise ValueError('Noncoincident name-only effective shape changed')
        for key in before_effective:
            if key != 'tags' and after_effective.get(key) != before_effective.get(key):
                raise ValueError('Noncoincident name-only effective field changed')
        if (after_effective.get('kind') != 'sector'
                or after_effective.get('sourceId') != 'osm'
                or after_effective.get('geometrySha256') != before_effective.get('geometrySha256')):
            raise ValueError('Noncoincident name-only effective source changed')
        if not isinstance(before_effective.get('tags'), dict):
            raise ValueError('Noncoincident name-only prior tags changed')
        expected_tags = {key: value for key, value in before_effective['tags'].items()
                         if not is_current_name_tag(key)}
        expected_tags.update(name_tags)
        if after_effective.get('tags') != expected_tags:
            raise ValueError('Noncoincident name-only effective tags changed')
        expected_metadata = json.loads(json.dumps(before_metadata))
        expected_metadata['name'] = new_name
        expected_metadata['aliases'] = proposed_aliases
        if after_metadata != expected_metadata:
            raise ValueError('Noncoincident name-only effective metadata changed')
        declared[identifier] = change
    expected_lineage = json.loads(json.dumps(prior_lineage))
    expected_states = {row['id']: row for row in expected_lineage['sourceStates']}
    expected_protected = {row['id']: row for row in expected_lineage['protectedRaw']}
    for change in changes:
        identifier = change['id']
        expected_states[identifier]['effective'] = change['afterEffective']
        expected_protected[identifier]['expectedMetadata'] = change['afterMetadata']
    expected_lineage['nameOnlySourceReview'] = review_reference
    if expected_lineage != lineage:
        raise ValueError('Noncoincident name-only lineage successor changed')
    return declared


def validate_noncoincident_boundary_source_phase_lineage(proof, directory, expected, facts,
                                                        current, indices, geometries, original_sources,
                                                        effective_sources, source_sha256, curation,
                                                        reviewed_boundaries, official_report):
    """Bind accepted sector replacements without changing the reviewed town identity."""
    lineage = json.loads(aggregate_review_file(directory, proof['sourcePhaseLineage'],
                                               'noncoincident boundary source lineage'))
    name_only_changes = _validated_noncoincident_name_only_changes(
        proof, lineage, directory, source_sha256, curation)
    if (lineage.get('schemaVersion') != 1
            or lineage.get('method') != 'reviewed_noncoincident_official_boundary_lineage'
            or lineage.get('sourceSha256') != source_sha256
            or lineage.get('application') != proof['application']
            or lineage.get('historicalSourceFacts') != proof['sourceFacts']):
        raise ValueError('Noncoincident boundary lineage identity changed')
    reference = lineage['reviewedBoundaries']
    boundary_manifest = load_reviewed_boundary_manifest_for_lineage(
        directory, reference, reviewed_boundaries, official_report)
    source_ids = set(proof['sourceKinds'])
    sector_ids = {row['sourceId'] for row in expected['preservedImadas']}
    records = lineage['sourceStates']
    states = {row['id']: row for row in records}
    if (len(states) != len(records) or set(states) != source_ids
            or any(set(row) != {'id', 'original', 'effective'} for row in records)):
        raise ValueError('Noncoincident boundary lineage source inventory changed')
    changed = set()
    for identifier, row in states.items():
        wanted = facts['records'][identifier]
        historical = {'kind': proof['sourceKinds'][identifier], 'tags': wanted['originalTags'],
                      'sourceId': None, 'geometrySha256': wanted['originalGeometrySha256']}
        declared = name_only_changes.get(identifier)
        if (row['original'] != historical or wanted['expectedCuration'] is not None
                or curation.get(identifier) != (declared['curation'] if declared else None)):
            raise ValueError('Noncoincident original source or curation changed')
        for phase, sources in (('original', original_sources), ('effective', effective_sources)):
            source = sources.get(identifier)
            geometry = source.get('shape') if source else None
            if geometry is None and source is not None:
                geometry = source.get('point')
            if source is None or geometry is None:
                raise ValueError('Missing noncoincident lineage source')
            actual = {'kind': source['kind'], 'tags': source['tags'], 'sourceId': source.get('sourceId'),
                      'geometrySha256': aggregate_geometry_sha256(geometry)}
            if actual != row[phase]:
                raise ValueError('Noncoincident source phase changed')
        if identifier not in name_only_changes and row['effective'] != {**historical, 'sourceId': 'osm'}:
            changed.add(identifier)
    replacements = lineage['acceptedReplacements']
    replacement_ids = [row['id'] for row in replacements]
    if (not changed or len(replacement_ids) != len(set(replacement_ids))
            or set(replacement_ids) != changed or not changed <= sector_ids):
        raise ValueError('Noncoincident changes need an exact accepted imada replacement inventory')
    for replacement in replacements:
        identifier = replacement['id']
        original, effective = states[identifier]['original'], states[identifier]['effective']
        source, record = replacement['sourceRecord'], replacement['record']
        application = {'id': identifier, 'action': 'replace', 'officialCode': record['officialCode'],
                       'sourceId': source['id']}
        if ([s for s in boundary_manifest['sources'] if s['id'] == source['id']] != [source]
                or [r for r in source['records'] if r['id'] == identifier] != [record]
                or record['id'] != identifier or record['action'] != 'replace'
                or record['expectedOriginalTags'] != original['tags']
                or source['sourceSha256'] != source_sha256 or not source.get('review')
                or original['kind'] != 'sector' or effective['kind'] != 'sector'
                or effective['sourceId'] != source['id'] or source['id'] == 'osm'
                or replacement['application'] != application
                or [r for r in official_report['applications'] if r['id'] == identifier] != [application]
                or official_report['sources'].get(source['id'])
                    != {k: v for k, v in source.items() if k not in ('file', 'records')}
                or replacement['geojson'] != {'file': source['file'], 'sha256': source['sha256']}):
            raise ValueError('Noncoincident replacement differs from its accepted loader application')
        collection = json.loads(aggregate_review_file(Path(reviewed_boundaries).parent,
                                                      replacement['geojson'], 'accepted imada geometry'))
        features = [feature for feature in collection['features'] if feature['id'] == identifier]
        if len(features) != 1:
            raise ValueError('Noncoincident accepted feature is not unique')
        feature = features[0]
        feature_sha = hashlib.sha256(json.dumps(feature, ensure_ascii=False, sort_keys=True,
                                                separators=(',', ':')).encode('utf-8')).hexdigest()
        properties = feature['properties']
        tags = {'boundary': 'administrative', 'admin_level': '6', 'ref:tn:codegeo': record['officialCode'],
                'name:ar': properties['nameAr'], 'name:fr': properties['nameFr']}
        if record.get('aliases'):
            tags['alt_name'] = ';'.join(record['aliases'])
        if (feature_sha != replacement['featureSha256'] or feature['type'] != 'Feature'
                or properties['sourceId'] != source['id'] or properties['officialCode'] != record['officialCode']
                or tags != effective['tags']
                or aggregate_geometry_sha256(shape(feature['geometry'])) != effective['geometrySha256']):
            raise ValueError('Noncoincident accepted feature does not produce the effective source')
    identifier, point_id = expected['residentialId'], expected['sourceTownId']
    absorption = lineage['absorbedTown']
    if (set(absorption) != {'id', 'originalHostId', 'effectiveHostId', 'matchingPolygonIds',
                           'aliasesBeforeAbsorption', 'aliasesAfterAbsorption'}
            or absorption['id'] != point_id or absorption['effectiveHostId'] != identifier
            or absorption['originalHostId'] != expected['retainedSourceTown']['currentAbsorptionHost']
            or point_id in current):
        raise ValueError('Noncoincident absorbed town identity changed')
    point = effective_sources[point_id]['point']
    point_names = names(effective_sources[point_id]['tags'])
    point_identity = {'name': point_names[0], 'aliases': point_names[1:]}
    matching = sorted(i for i, index in indices.items() if index < len(geometries)
                      and geometries[index].covers(point) and same_picker_name(point_identity, current[i]))
    if (not matching or matching != absorption['matchingPolygonIds']
            or min(matching, key=lambda i: current[i]['areaKm2']) != identifier
            or sum(current[i]['areaKm2'] == current[identifier]['areaKm2'] for i in matching) != 1):
        raise ValueError('Noncoincident town absorption host changed')
    residential_names = names(effective_sources[identifier]['tags'])
    before = sorted(set(residential_names[1:]) - {residential_names[0]})
    after = sorted(set(before + point_names) - {residential_names[0]})
    if (before != absorption['aliasesBeforeAbsorption'] or after != absorption['aliasesAfterAbsorption']
            or current[identifier]['aliases'] != after):
        raise ValueError('Noncoincident source-bound absorbed aliases changed')
    protected = lineage['protectedRaw']
    protected_rows = {row['id']: row for row in protected}
    if (len(protected_rows) != len(protected)
            or set(protected_rows) != set(expected['protectedRawIds'])
            or any(set(row) != {'id', 'expectedMetadata', 'packedGeometrySha256'} for row in protected)):
        raise ValueError('Noncoincident protected output inventory changed')
    for source_id, row in protected_rows.items():
        historical = {k: v for k, v in facts['records'][source_id]['currentMetadata'].items()
                      if k not in {'offset', 'length'}}
        if source_id == identifier:
            historical = {**historical, 'pickerGroupId': identifier, 'aliases': after}
        wanted = row['expectedMetadata']
        declared = name_only_changes.get(source_id)
        if declared is not None:
            if declared['beforeMetadata'] != historical or wanted != declared['afterMetadata']:
                raise ValueError('Noncoincident name-only protected metadata changed')
        elif source_id in changed:
            allowed = {'sourceId', 'name', 'aliases', 'parentName', 'contextAliases',
                       'lat', 'lng', 'bbox', 'areaKm2'}
            if (set(wanted) != set(historical)
                    or any(wanted[k] != historical[k] for k in wanted if k not in allowed)
                    or wanted['sourceId'] != states[source_id]['effective']['sourceId']
                    or wanted['name'] != names(states[source_id]['effective']['tags'])[0]):
                raise ValueError('Noncoincident accepted sector changed an unreviewed field')
        elif wanted != historical:
            raise ValueError('Noncoincident unchanged protected locality changed')
        actual = current.get(source_id)
        if actual is None or {k: v for k, v in actual.items() if k not in {'offset', 'length'}} != wanted:
            raise ValueError('Noncoincident reviewed effective metadata changed')
        packed_sha = (hashlib.sha256(packed_geometry_bytes(geometries[indices[source_id]])).hexdigest()
                      if actual['hasBoundary'] else None)
        if packed_sha != row['packedGeometrySha256']:
            raise ValueError('Noncoincident reviewed effective packed geometry changed')
        if source_id not in changed and packed_sha != facts['records'][source_id]['packedGeometrySha256']:
            raise ValueError('Noncoincident preserved footprint changed')
    transition = {'id': identifier, 'before': after,
                  'after': sorted(set(after + [expected['baseBefore']['nomAr']]))}
    if lineage['expectedLateAliasTransition'] != transition:
        raise ValueError('Noncoincident reviewed late historical alias changed')
    return lineage


def validate_noncoincident_town_residential_display(entry, directory, current, indices, groups,
                                                  geometries, base, governor, timetables,
                                                  original_sources, effective_sources,
                                                  source_sha256, curation, used,
                                                  reviewed_boundaries=None, official_report=None):
    """Bind a reviewed town identity while preserving its distinct legacy reference.

    A source town point establishes the named settlement, not a replacement
    timetable coordinate. Containment or noncontainment is explicit evidence;
    neither distance nor a shared nearest timetable establishes identity.
    """
    document = lambda ref, label: json.loads(aggregate_review_file(directory, ref, label))
    proof = document(entry['reviewEvidence'], 'noncoincident town display identity')
    proposal = document(proof['sourceProposal'], 'noncoincident town source proposal')
    review = document(proof['sourceReview'], 'noncoincident town identity review')
    facts = document(proof['sourceFacts'], 'noncoincident exact source facts')
    registry = document(proof['officialRegistry'], 'noncoincident complete imada registry')
    identifier, target = entry['id'], entry['targetDelegationId']
    target_group = f'delegation:{target}'
    application = {k: entry[k] for k in ('method', 'id', 'targetDelegationId')}
    decisions = [r for r in proposal['records'] if r['targetBaseId'] == target]
    source_decisions = [r for r in review['records'] if r['baseId'] == target]
    if (proof.get('schemaVersion') != 1 or proof.get('sourceSha256') != source_sha256
            or proof.get('application') != application
            or proof.get('conclusion') != 'reviewed_noncoincident_town_residential_base_identity'
            or proposal.get('status') != 'COMPACT_CURRENT_SOURCE_PROPOSAL_NOT_IMPLEMENTED'
            or review.get('status') != 'TWO_EXACT_DISPLAY_TRANSITIONS_SOURCE_SUPPORTED_NOT_IMPLEMENTED'
            or facts.get('sourcePbfSha256') != source_sha256
            or len(decisions) != 1 or len(source_decisions) != 1
            or proposal['frozenSourceReview']['sha256'] != proof['sourceReview']['sha256']
            or proposal['frozenSourceFacts']['sha256'] != proof['sourceFacts']['sha256']
            or review['sourceFacts']['sha256'] != proof['sourceFacts']['sha256']
            or proof['officialRegistry']['sha256'] != review['inputPins']['outputs/current-official-sector-registry.json']):
        raise ValueError('Noncoincident town review is not bound to the source identity')
    expected, source_decision = decisions[0], source_decisions[0]
    if (proof['expected'] != expected or expected['decision'] != 'SOURCE_SUPPORTED_EXACT_DISPLAY_TRANSITION_NO_NUMERIC_CHANGE'
            or source_decision['decision'] != 'SUPPORTED_EXACT_TOWN_BASE_ASSOCIATION_WITH_DISTINCT_IMADA_CHOICE'
            or expected['residentialId'] != identifier or expected['baseBefore'] != source_decision['baseCurrent']
            or expected['currentWholeGroups'] != source_decision['beforeGroups']
            or expected['expectedFinalWholeGroups'] != source_decision['proposedAfterGroups']
            or expected['exactRawFieldChanges'] != source_decision['exactProposedRawChanges']):
        raise ValueError('Noncoincident exact source proposal changed')
    sector_id, point_id = expected['separateSectorId'], expected['sourceTownId']
    parent_id, governor_id = proof['parentSourceId'], proof['governorSourceId']
    sector_ids = {r['sourceId'] for r in expected['preservedImadas']}
    protected_ids = set(expected['protectedRawIds'])
    source_ids = protected_ids | {point_id, parent_id, governor_id}
    reserved = protected_ids | {point_id, target_group}
    if (used & reserved or set(proof['sourceKinds']) != source_ids
            or protected_ids != set(source_decision['protectedRawIds'])
            or sector_id not in sector_ids or identifier not in protected_ids
            or groups.get(identifier) != {identifier} or groups.get(target_group)
            or point_id in current or proof['sourceKinds'].get(point_id) != 'town'
            or proof['sourceKinds'].get(parent_id) != 'delegation'
            or proof['sourceKinds'].get(governor_id) != 'governorate'):
        raise ValueError('Noncoincident display conflicts with another reviewed choice')
    before_groups = expected['requiredGroupsAfterDistinctGuardBeforeDisplay']
    exact_before = {k: list(v) for k, v in expected['currentWholeGroups'].items()}
    if exact_before.get(sector_id) != sorted([sector_id, identifier]):
        raise ValueError('Noncoincident original combined group changed')
    exact_before[sector_id], exact_before[identifier] = [sector_id], [identifier]
    if before_groups != exact_before:
        raise ValueError('Noncoincident distinct-choice transition changed')
    for group_id, members in before_groups.items():
        actual = groups.get(group_id, set()) | ({group_id} if group_id.startswith('delegation:') else set())
        if len(members) != len(set(members)) or actual != set(members):
            raise ValueError('Noncoincident complete group inventory changed')
    lineage = None
    if 'sourcePhaseLineage' in proof:
        lineage = validate_noncoincident_boundary_source_phase_lineage(
            proof, directory, expected, facts, current, indices, geometries, original_sources,
            effective_sources, source_sha256, curation, reviewed_boundaries, official_report)
    else:
        for source_id in source_ids:
            wanted = facts['records'][source_id]
            if wanted.get('expectedCuration') is not None or curation.get(source_id) is not None:
                raise ValueError('Noncoincident source acquired an unreviewed curation')
            for source_map, provider in ((original_sources, None), (effective_sources, 'osm')):
                actual = source_map.get(source_id)
                geometry = actual.get('shape') if actual else None
                if geometry is None and actual is not None:
                    geometry = actual.get('point')
                if (actual is None or actual.get('sourceId') != provider
                        or actual['kind'] != proof['sourceKinds'][source_id]
                        or actual['tags'] != wanted['originalTags'] or geometry is None
                        or aggregate_geometry_sha256(geometry) != wanted['originalGeometrySha256']):
                    raise ValueError('Noncoincident original/effective source identity changed')
            if source_id in protected_ids:
                wanted_raw = {k: v for k, v in wanted['currentMetadata'].items() if k not in {'offset', 'length'}}
                if source_id == identifier:
                    wanted_raw = {**wanted_raw, 'pickerGroupId': identifier}
                actual = current.get(source_id)
                if actual is None or {k: v for k, v in actual.items() if k not in {'offset', 'length'}} != wanted_raw:
                    raise ValueError('Noncoincident protected metadata changed before display association')
                packed_sha = (hashlib.sha256(packed_geometry_bytes(geometries[indices[source_id]])).hexdigest()
                              if actual['hasBoundary'] else None)
                if packed_sha != wanted['packedGeometrySha256']:
                    raise ValueError('Noncoincident protected packed geometry changed')
    residential = current[identifier]
    tags, point_tags = effective_sources[identifier]['tags'], effective_sources[point_id]['tags']
    point = effective_sources[point_id]['point']
    point_reference = expected['retainedSourceTown']['pointCoordinates']
    if (base != expected['baseAfter'] or base != {**expected['baseBefore'], 'nomAr': residential['name']}
            or residential['kind'] != 'residential' or residential['delegationId'] != target
            or residential['governorateId'] != governor['id'] or current[sector_id]['delegationId'] != target
            or tags.get('name:ar') != base['nomAr'] or tags.get('addr:city') != base['nomAr']
            or point_tags.get('name:ar') != base['nomAr'] or point_tags.get('place') != 'town'
            or {'lat': point.y, 'lng': point.x} != point_reference
            or expected['identityEvidence']['requiredOriginalSourceNames'] != {'residentialTags': tags, 'sourceTownTags': point_tags}
            or expected['identityEvidence']['exactLatinCorrespondence'] != {
                'baseNomFr': base['nomFr'], 'residentialNameFr': tags.get('name:fr'), 'townNameFr': point_tags.get('name:fr')}
            or base['nomFr'] != tags.get('name:fr')):
        raise ValueError('Noncoincident exact bilingual town or unchanged base identity changed')
    code = effective_sources[parent_id]['tags'].get('ref:tn:codegeo', '')
    official = [r for r in registry['sectors'] if r['delegationCode'] == code]
    if (not re.fullmatch(r'\d{4}', code) or len(official) != 5
            or official != [r['official'] for r in expected['preservedImadas']]
            or {r['delegationAr'] for r in official} != {base['nomAr']}
            or {r['governorateCode'] for r in official} != {code[:2]}
            or effective_sources[governor_id]['tags'].get('ref:tn:codegeo') != code[:2]
            or {norm(r['governorateAr']) for r in official} != {norm(governor['nomAr'])}):
        raise ValueError('Noncoincident full official context changed')
    for source_map in (original_sources, effective_sources):
        actual_sectors = {i for i, s in source_map.items() if s['kind'] == 'sector'
                          and s['tags'].get('ref:tn:codegeo', '').startswith(code)}
        if actual_sectors != sector_ids:
            raise ValueError('Noncoincident complete source imada inventory changed')
        for source_id in {parent_id, governor_id, *sector_ids}:
            obj = source_map[source_id]
            claims = {i for i, s in source_map.items() if s['kind'] == obj['kind']
                      and s['tags'].get('ref:tn:codegeo') == obj['tags'].get('ref:tn:codegeo')}
            if claims != {source_id}:
                raise ValueError('Noncoincident administrative code is no longer unique')
        query = set(source_decision['exactNameQueries'])
        peers = {i for i, s in source_map.items() if query & {norm(v) for v in names(s['tags'])}}
        if peers != set(expected['identityEvidence']['requiredSourcePeerIds']):
            raise ValueError('Noncoincident town source peer inventory changed')
    if {effective_sources[i]['tags']['ref:tn:codegeo'] for i in sector_ids} != {r['sectorCode'] for r in official}:
        raise ValueError('Noncoincident imada code identity changed')
    policy = expected['explicitReferencePolicy']
    inside = policy['baseInsideOriginalAndPackedResidential']
    base_point = Point(base['lng'], base['lat'])
    if (policy['policy'] != 'PRESERVE_LEGACY_BASE_COORDINATES_AND_ALL_RAW_DEFAULTS'
            or type(inside) is not bool or point.equals(base_point)
            or policy['rawAndTownNearestExistingUsableBase'] != target
            or (not inside and not policy['requiredNoncontainmentEvidence'])
            or inside != source_decision['geometryFacts']['pointFacts'][0]['insideOriginalResidential']
            or inside != source_decision['geometryFacts']['pointFacts'][0]['insidePackedResidential']):
        raise ValueError('Noncoincident reference policy was not explicitly reviewed')
    for polygon in (original_sources[identifier]['shape'], effective_sources[identifier]['shape'], geometries[indices[identifier]]):
        if not polygon.covers(point) or polygon.covers(base_point) != inside:
            raise ValueError('Noncoincident exact reference containment changed')
    for source_map in (original_sources, effective_sources):
        for context_id in (parent_id, governor_id, sector_id):
            if not all(source_map[context_id]['shape'].covers(q) for q in (point, base_point)):
                raise ValueError('Noncoincident reference left the reviewed context')
    if not all(geometries[indices[sector_id]].covers(q) for q in (point, base_point)):
        raise ValueError('Noncoincident reference left the packed namesake imada')
    coordinate_manifest = json.loads(display_identity_coordinate_bytes(directory))
    if any(r['delegationId'] == target for r in coordinate_manifest['corrections']):
        raise ValueError('Noncoincident base acquired an unreviewed coordinate correction')
    for reference in (base, residential, current[sector_id], {'lat': point.y, 'lng': point.x}):
        if min(timetables, key=lambda d: (distance(reference['lat'], reference['lng'], d), d['id']))['id'] != target:
            raise ValueError('Noncoincident preserved timetable assignment changed')
    primary = proof['primaryEvidence']
    if primary['sha256'] != review['inputPins']['work/la-goulette-new-matmata-display-review/primary-indexed-response.json']:
        raise ValueError('Noncoincident official corroboration was not reviewed')
    aggregate_review_file(directory, primary, 'noncoincident indexed official corroboration')
    aliases = sorted(set(residential['aliases'] + [expected['baseBefore']['nomAr']]))
    saved_name = {'id': target_group, 'name': base['nomAr'], 'kind': 'delegation'}
    exact_changes = [{'id': identifier, 'field': 'pickerGroupId', 'before': sector_id, 'after': target_group},
                     {'id': identifier, 'field': 'aliases', 'before': residential['aliases'], 'after': aliases}]
    if lineage is None:
        if (expected['baseBefore']['nomAr'] == base['nomAr'] or expected['baseBefore']['nomAr'] in residential['aliases']
                or expected['exactRawFieldChanges'] != exact_changes or expected['exactTinyNameAppend'] != saved_name):
            raise ValueError('Noncoincident preferred name and historical search transition changed')
    elif (expected['baseBefore']['nomAr'] == base['nomAr']
            or expected['baseBefore']['nomAr'] in residential['aliases']
            or lineage['expectedLateAliasTransition'] != {'id': identifier, 'before': residential['aliases'], 'after': aliases}
            or expected['exactTinyNameAppend'] != saved_name):
        raise ValueError('Noncoincident accepted-boundary historical search transition changed')
    final_groups = {k: list(v) for k, v in before_groups.items() if k != identifier}
    final_groups[target_group] = sorted([target_group, identifier])
    if final_groups != expected['expectedFinalWholeGroups']:
        raise ValueError('Noncoincident final whole-group transition changed')
    protected = {i: {k: v for k, v in current[i].items() if k not in {'pickerGroupId', 'offset', 'length'}} for i in protected_ids}
    protected[identifier] = {**protected[identifier], 'aliases': aliases}
    result = {'id': identifier, 'pickerGroupId': target_group, 'reviewEvidence': entry['reviewEvidence'],
              'finalGroups': [{'id': i, 'memberIds': ids} for i, ids in final_groups.items()],
              'protectedMetadata': protected, 'preservedRawSource': target, 'canonicalManualSource': target,
              'preservedNonCatalogPlace': {'id': point_id, 'role': 'absorbed_extracted_town'},
              'identityMethod': application['method'],
              'reviewedLateAliasTransition': {'id': identifier, 'before': list(residential['aliases']), 'after': aliases}}
    return (identifier, target_group, aliases), result, saved_name, reserved


def validate_explicit_settlement_polygon_display(entry, manifest_path, current, indices, groups,
                                                geometries, base, governor, timetables,
                                                original_sources, effective_sources,
                                                source_sha256, curation, used):
    """Bind one reviewed settlement polygon to its unchanged timetable choice.

    A separately reviewed distinct-pair guard runs before this late phase. The
    imada keeps its own representative and nearest timetable, including when
    that timetable differs from the inhabited settlement's timetable.
    """
    directory = Path(manifest_path).parent
    document = lambda ref, label: json.loads(aggregate_review_file(directory, ref, label))
    method = 'reviewed_explicit_settlement_polygon_base_display_identity'
    proof = document(entry['reviewEvidence'], 'explicit settlement polygon identity')
    facts = document(proof['sourceFacts'], 'explicit settlement source facts')
    review = document(proof['sourceReview'], 'explicit settlement source review')
    independent = document(proof['independentSourceReview'], 'independent settlement identity review')
    registry = document(proof['officialRegistry'], 'explicit settlement imada registry')
    identifier, target = entry['id'], entry['targetDelegationId']
    target_group = f'delegation:{target}'
    application = {k: entry[k] for k in ('method', 'id', 'targetDelegationId')}
    if (entry['method'] != method or proof.get('schemaVersion') != 1
            or proof.get('sourceSha256') != source_sha256
            or proof.get('application') != application
            or proof.get('conclusion') != 'same_named_settlement_display_only'
            or facts.get('schemaVersion') != 1 or facts.get('sourceSha256') != source_sha256
            or facts.get('baseId') != target or facts.get('polygonId') != identifier
            or facts.get('sourceReview') != proof['sourceReview']
            or facts.get('officialRegistry') != proof['officialRegistry']
            or proof.get('expected') != facts.get('expected')):
        raise ValueError('Explicit settlement review does not bind this exact application')
    binding = facts['sourceReviewBinding']
    if review.get('status') != binding['status']:
        raise ValueError('Explicit settlement source review status changed')
    reviewed_record = review
    for key in binding['selector']:
        if isinstance(reviewed_record, dict) and isinstance(key, str):
            reviewed_record = reviewed_record[key]
        elif isinstance(reviewed_record, list) and type(key) is int:
            reviewed_record = reviewed_record[key]
        else:
            raise ValueError('Invalid explicit settlement source review selector')
    if reviewed_record != binding['record']:
        raise ValueError('Explicit settlement source decision changed')
    expected, records = facts['expected'], facts['records']
    if (independent.get('status') != 'PASS'
            or independent.get('baselineMetadataSha256') != facts['baselineMetadataSha256']
            or proof['sourceReview']['sha256'] not in {r['sha256'] for r in independent['sourceReports']}
            or [r for r in independent['exactProposedRawFieldChanges'] if r['id']==identifier]
               != expected['exactRawFieldChanges']):
        raise ValueError('Explicit settlement identity lacks the pinned independent source approval')
    point_id, sector_id = expected['pointId'], expected['separateSectorId']
    parent_id, governor_id = expected['parentSourceId'], expected['governorSourceId']
    sector_ids = {r['id'] for r in expected['preservedImadas']}
    protected = set(expected['protectedRawIds'])
    peer_ids = set(expected['originalNamePeerIds']) | set(expected['effectiveNamePeerIds'])
    reserved = protected | {point_id, target_group}
    if (base != expected['baseCurrent']
            or {k:v for k,v in governor.items() if k != 'delegations'} != expected['governorCurrent']
            or used & reserved or sector_id not in sector_ids
            or identifier not in protected or not sector_ids <= protected
            or set(records) != protected | peer_ids | {point_id, parent_id, governor_id}
            or point_id in current or groups.get(identifier) != {identifier}
            or groups.get(target_group)
            or len(expected['protectedRawIds']) != len(protected)
            or len(expected['preservedImadas']) != len(sector_ids)):
        raise ValueError('Explicit settlement source or ownership inventory changed')
    before = expected['currentWholeGroups']
    required = {k:list(v) for k,v in before.items()}
    if (before.get(sector_id) != sorted([sector_id, identifier])
            or before.get(target_group) != [target_group] or identifier in before):
        raise ValueError('Explicit settlement original imada/town group changed')
    required[sector_id], required[identifier] = [sector_id], [identifier]
    if required != expected['requiredGroupsAfterDistinctGuard']:
        raise ValueError('Explicit settlement distinct-group transition changed')
    group_members = {i for members in before.values() for i in members if not i.startswith('delegation:')}
    if group_members != protected:
        raise ValueError('Explicit settlement protected groups are incomplete')
    for group_id, members in required.items():
        actual = groups.get(group_id, set()) | ({group_id} if group_id.startswith('delegation:') else set())
        if len(members) != len(set(members)) or actual != set(members):
            raise ValueError('Explicit settlement distinct guard did not preserve whole groups')
    active = json.loads(Path(manifest_path).read_bytes())
    pair = expected['distinctPair']
    if (pair['ids'] != sorted([sector_id, identifier])
            or [r for r in active.get('distinctPairs', []) if r['ids'] == pair['ids']] != [pair]):
        raise ValueError('Explicit settlement distinct-pair ownership is not active')
    distinct = document(pair['reviewEvidence'], 'explicit settlement distinct-pair proof')
    if (distinct.get('conclusion') != 'distinct_locality_choices'
            or distinct.get('sourceSha256') != source_sha256
            or {r['id'] for r in distinct['records']} != {sector_id, identifier}):
        raise ValueError('Explicit settlement distinct-pair proof changed')
    for source_id, wanted in records.items():
        if wanted.get('id') != source_id or curation.get(source_id) != wanted['expectedCuration']:
            raise ValueError('Explicit settlement source curation changed')
        for phase, source_map in (('original', original_sources), ('effective', effective_sources)):
            actual = source_map.get(source_id)
            geometry = actual.get('shape') if actual else None
            if geometry is None and actual is not None:
                geometry = actual.get('point')
            if (actual is None or actual.get('sourceId') != wanted[f'{phase}SourceId']
                    or actual['kind'] != wanted['kind'] or actual['tags'] != wanted[f'{phase}Tags']
                    or geometry is None
                    or aggregate_geometry_sha256(geometry) != wanted[f'{phase}GeometrySha256']):
                raise ValueError('Explicit settlement source kind, tags, provider or geometry changed')
        if source_id in protected:
            wanted_row = wanted['currentMetadata']
            if source_id == identifier:
                wanted_row = {**wanted_row, 'pickerGroupId': identifier}
            row = current.get(source_id)
            if row is None or {k:v for k,v in row.items() if k not in {'offset','length'}} != wanted_row:
                raise ValueError('Explicit settlement protected raw metadata changed')
            packed_sha = (hashlib.sha256(packed_geometry_bytes(geometries[indices[source_id]])).hexdigest()
                          if row['hasBoundary'] else None)
            if packed_sha != wanted['packedGeometrySha256']:
                raise ValueError('Explicit settlement protected packed geometry changed')
        elif wanted['currentMetadata'] is not None or source_id in current:
            raise ValueError('Explicit settlement source-only evidence became selectable')
    polygon, point = current[identifier], effective_sources[point_id]['point']
    if (polygon['kind'] not in ('residential', 'village', 'town')
            or records[point_id]['kind'] not in ('town', 'village')
            or records[parent_id]['kind'] != 'delegation'
            or records[governor_id]['kind'] != 'governorate'
            or any(records[i]['kind'] != 'sector' for i in sector_ids)
            or polygon['delegationId'] != target or polygon['governorateId'] != governor['id']
            or {'lat':point.y,'lng':point.x} != expected['sourcePointCoordinates']):
        raise ValueError('Explicit settlement typed polygon/point identity changed')
    base_names = {norm(base[k]) for k in ('nomAr','nomFr','nomEn')}
    polygon_names = {norm(v) for v in names(effective_sources[identifier]['tags'])}
    point_names = {norm(v) for v in names(effective_sources[point_id]['tags'])}
    if not base_names & point_names or not polygon_names & point_names:
        raise ValueError('Explicit settlement reviewed names lost their common identity')
    queries = sorted(base_names | polygon_names | point_names)
    if expected['nameQueries'] != queries:
        raise ValueError('Explicit settlement complete name query changed')
    for phase, source_map in (('original',original_sources), ('effective',effective_sources)):
        peers = {i for i,s in source_map.items() if set(queries) & {norm(v) for v in names(s['tags'])}}
        if sorted(peers) != expected[f'{phase}NamePeerIds']:
            raise ValueError('Explicit settlement exact named peer inventory changed')
    code = effective_sources[parent_id]['tags']['ref:tn:codegeo']
    official = [r for r in registry['sectors'] if r['delegationCode'] == code]
    if (not re.fullmatch(r'\d{4}',code)
            or official != [r['official'] for r in expected['preservedImadas']]
            or {r['sectorCode'] for r in official} != {effective_sources[i]['tags']['ref:tn:codegeo'] for i in sector_ids}
            or {norm(r['governorateAr']) for r in official} != {norm(governor['nomAr'])}
            or effective_sources[governor_id]['tags'].get('ref:tn:codegeo') != code[:2]):
        raise ValueError('Explicit settlement official imada inventory changed')
    for source_map in (original_sources,effective_sources):
        actual_sectors = {i for i,s in source_map.items() if s['kind']=='sector'
                          and s['tags'].get('ref:tn:codegeo','').startswith(code)}
        if actual_sectors != sector_ids:
            raise ValueError('Explicit settlement coded imada inventory is incomplete')
        for source_id in {parent_id,governor_id,*sector_ids}:
            s=source_map[source_id]
            claims={i for i,r in source_map.items() if r['kind']==s['kind']
                    and r['tags'].get('ref:tn:codegeo')==s['tags']['ref:tn:codegeo']}
            if claims!={source_id}:
                raise ValueError('Explicit settlement administrative code is not unique')
    base_point = Point(base['lng'],base['lat'])
    containment = expected['baseInsideSettlementPolygon']
    if (set(containment) != {'original','effective','packed'}
            or any(type(v) is not bool for v in containment.values())
            or expected['coordinatePolicy'] != 'preserve_all_reference_and_raw_coordinates'
            or (not all(containment.values()) and not expected['reviewedNoncontainmentReason'])):
        raise ValueError('Explicit settlement legacy reference relationship lacks review')
    for phase, geometry in [('original',original_sources[identifier]['shape']),
                            ('effective',effective_sources[identifier]['shape']),
                            ('packed',geometries[indices[identifier]])]:
        if not geometry.covers(point) or geometry.covers(base_point) != containment[phase]:
            raise ValueError('Explicit settlement point/base containment changed')
    for source_map in (original_sources,effective_sources):
        for context_id in (parent_id,governor_id):
            if not all(source_map[context_id]['shape'].covers(p) for p in (point,base_point)):
                raise ValueError('Explicit settlement references left the reviewed parent/governor')
    contexts = expected['namesakeSectorReferenceContainment']
    for phase, geometry in [('original',original_sources[sector_id]['shape']),
                            ('effective',effective_sources[sector_id]['shape']),
                            ('packed',geometries[indices[sector_id]])]:
        actual = {'base':geometry.covers(base_point),'sourcePoint':geometry.covers(point)}
        if contexts[phase] != actual or not all(actual.values()):
            raise ValueError('Explicit settlement references left the namesake imada')
    for phase, sector_geometry, settlement_geometry in (
            ('original',original_sources[sector_id]['shape'],original_sources[identifier]['shape']),
            ('effective',effective_sources[sector_id]['shape'],effective_sources[identifier]['shape']),
            ('packed',geometries[indices[sector_id]],geometries[indices[identifier]])):
        if (expected['settlementWithinNamesakeSector'][phase] is not True
                or not sector_geometry.covers(settlement_geometry)):
            raise ValueError('Explicit settlement whole footprint left its reviewed imada')
    coordinate_manifest=json.loads(display_identity_coordinate_bytes(directory))
    if [r for r in coordinate_manifest['corrections'] if r['delegationId']==target] != expected['coordinateCorrections']:
        raise ValueError('Explicit settlement timetable reference provenance changed')
    references={'base':base,'settlementPolygon':polygon,'sourcePoint':{'lat':point.y,'lng':point.x},
                'separateSector':current[sector_id]}
    if set(expected['nearestReferenceIds']) != set(references):
        raise ValueError('Explicit settlement nearest-reference inventory changed')
    for role, reference in references.items():
        nearest=min(timetables,key=lambda d:(distance(reference['lat'],reference['lng'],d),d['id']))['id']
        if (nearest != expected['nearestReferenceIds'][role]
                or (role != 'separateSector' and nearest != target)
                or (role == 'separateSector' and nearest != current[sector_id]['delegationId'])):
            raise ValueError('Explicit settlement preserved timetable assignment changed')
    node_document=document(proof['publicNode'],'explicit settlement public node')
    node=expected['publicNodeElement']
    if (node_document.get('elements') != [node] or node.get('type')!='node'
            or f"osm:node:{node['id']}"!=point_id or type(node.get('version')) is not int
            or not node.get('timestamp') or type(node.get('changeset')) is not int
            or node['lat']!=point.y or node['lon']!=point.x
            or node['tags']!=effective_sources[point_id]['tags']):
        raise ValueError('Explicit settlement live-source identity differs from reviewed extraction')
    if proof['primaryEvidence'] != facts['primaryEvidence'] or not proof['primaryEvidence']:
        raise ValueError('Explicit settlement primary identity evidence is missing')
    for ref in proof['primaryEvidence']:
        aggregate_review_file(directory,ref,'explicit settlement primary corroboration')
    final_groups={k:list(v) for k,v in required.items() if k!=identifier}
    final_groups[target_group]=sorted([target_group,identifier])
    if (final_groups!=expected['expectedFinalWholeGroups']
            or expected['exactRawFieldChanges']!=[{'id':identifier,'field':'pickerGroupId',
                'before':sector_id,'after':target_group}]):
        raise ValueError('Explicit settlement final one-field transition changed')
    protected_metadata={i:{k:v for k,v in current[i].items() if k not in {'offset','length','pickerGroupId'}}
                        for i in protected}
    absent_ids=sorted(i for i,r in records.items() if r['currentMetadata'] is None)
    result={'id':identifier,'pickerGroupId':target_group,'reviewEvidence':entry['reviewEvidence'],
            'identityMethod':method,'finalGroups':[{'id':k,'memberIds':v} for k,v in final_groups.items()],
            'protectedMetadata':protected_metadata,'preservedRawSource':target,'canonicalManualSource':target,
            'preservedNonCatalogPlace':{'id':point_id,'role':f"absorbed_extracted_{records[point_id]['kind']}"},
            'preservedAbsentSourceIds':absent_ids}
    return (identifier,target_group,list(polygon['aliases'])),result,None,reserved


def validate_residential_complete_sector_groups(extension, decision, facts, current, groups,
                                                 identifier, point_id, parent_id, governor_id,
                                                 target_group):
    """Preserve actual sector-owned groups without inferring a new name merge."""
    keys = {'schemaVersion', 'preservedSectorIds', 'protectedRawIds',
            'expectedBeforeGroups', 'expectedAfterGroups', 'exactRawFieldChanges'}
    if (not isinstance(extension, dict) or set(extension) != keys
            or type(extension['schemaVersion']) is not int or extension['schemaVersion'] != 1
            or facts.get('completeSectorGroupPreservation') != extension
            or decision.get('completeSectorGroupPreservation') != extension):
        raise ValueError('Residential complete-group evidence is not identically source-bound')
    sector_ids = decision['preservedCurrentSectorIds']
    if (not isinstance(sector_ids, list) or not sector_ids
            or any(not isinstance(i, str) for i in sector_ids)
            or len(sector_ids) != len(set(sector_ids))
            or extension['preservedSectorIds'] != sorted(sector_ids)
            or len({identifier, point_id, parent_id, governor_id}) != 4
            or {identifier, point_id, parent_id, governor_id, target_group} & set(sector_ids)):
        raise ValueError('Residential complete-group typed inventory changed')
    sector_set = set(sector_ids)
    # Derive membership from every actual row, not the supplied protected list
    # or a source-review claim that each sector is a singleton.
    actual_groups = {i: sorted(sid for sid, row in current.items()
                              if row['pickerGroupId'] == i) for i in sorted(sector_ids)}
    if (any(i not in current or current[i]['kind'] != 'sector'
            or current[i]['pickerGroupId'] != i for i in sector_ids)
            or any(groups.get(i, set()) != set(ids) for i, ids in actual_groups.items())
            or not any(len(ids) > 1 for ids in actual_groups.values())):
        raise ValueError('Residential complete-group extension requires existing sector-owned groups')
    protected = {identifier} | {sid for ids in actual_groups.values() for sid in ids}
    before = {**actual_groups, identifier: [identifier], target_group: [target_group]}
    after = {**actual_groups, target_group: sorted([target_group, identifier])}
    change = [{'id': identifier, 'field': 'pickerGroupId', 'before': identifier, 'after': target_group}]
    if (extension['protectedRawIds'] != sorted(protected)
            or extension['expectedBeforeGroups'] != before
            or extension['expectedAfterGroups'] != after
            or extension['exactRawFieldChanges'] != change
            or sorted(sid for sid, row in current.items() if row['pickerGroupId'] == identifier) != [identifier]
            or any(row['pickerGroupId'] == target_group for row in current.values())
            or target_group in current or identifier not in current
            or any(current[i]['kind'] != 'residential' for i in protected - sector_set)
            or any(current[i]['sourceId'] != 'osm' or current[i]['hasBoundary'] is not True for i in protected)):
        raise ValueError('Residential complete raw membership or exact one-field transition changed')
    source_only = {point_id, parent_id, governor_id}
    records = facts['records']
    if (protected & source_only or set(records) != protected | source_only
            or any(r.get('expectedCuration') is not None or r.get('originalSourceId') is not None
                   or r.get('effectiveSourceId') != 'osm' for r in records.values())
            or any(i in current or i in groups or records[i]['metadata'] is not None
                   or records[i]['packedGeometrySha256'] is not None for i in source_only)):
        raise ValueError('Residential complete-group source-only inventory changed')
    return protected, {'schemaVersion': 1, 'preservedSectorIds': sorted(sector_ids),
                       'protectedRawIds': sorted(protected), 'preservedSourceOnlyIds': sorted(source_only),
                       'expectedAfterGroups': after, 'exactRawFieldChanges': change}


def verify_residential_complete_sector_groups(application, current, groups):
    """Re-derive all preserved members after every late display override."""
    proof = application['completeSectorGroupPreservation']
    keys = {'schemaVersion', 'preservedSectorIds', 'protectedRawIds', 'preservedSourceOnlyIds',
            'expectedAfterGroups', 'exactRawFieldChanges'}
    if (not isinstance(proof, dict) or set(proof) != keys
            or type(proof['schemaVersion']) is not int or proof['schemaVersion'] != 1):
        raise ValueError('Residential final complete-group guard is malformed')
    sector_ids, protected, absent = (proof['preservedSectorIds'], proof['protectedRawIds'],
                                     proof['preservedSourceOnlyIds'])
    if any(not isinstance(ids, list) or any(not isinstance(i, str) for i in ids)
           or ids != sorted(set(ids)) for ids in (sector_ids, protected, absent)):
        raise ValueError('Residential final complete-group IDs are repeated or malformed')
    identifier, target_group = application['id'], application['pickerGroupId']
    if (not sector_ids or len(absent) != 3
            or {identifier, target_group} & set(sector_ids)
            or set(protected) & set(absent)
            or application['preservedAbsorbedPointId'] not in absent
            or any(i in current or i in groups for i in absent)
            or identifier not in current or current[identifier]['pickerGroupId'] != target_group
            or identifier in groups or target_group in current
            or any(i not in current or current[i]['kind'] != 'sector'
                   or current[i]['pickerGroupId'] != i for i in sector_ids)):
        raise ValueError('Residential final complete-group owners or source-only state changed')
    actual = {i: sorted(sid for sid, row in current.items()
                        if row['pickerGroupId'] == i) for i in sector_ids}
    derived = {identifier} | {sid for ids in actual.values() for sid in ids}
    actual[target_group] = sorted([target_group] + [sid for sid, row in current.items()
                                                   if row['pickerGroupId'] == target_group])
    if (sorted(derived) != protected or set(application['protectedMetadata']) != derived
            or actual != proof['expectedAfterGroups']
            or application['finalGroups'] != [{'id': i, 'memberIds': ids} for i, ids in actual.items()]
            or proof['exactRawFieldChanges'] != [{'id': identifier, 'field': 'pickerGroupId',
                                                 'before': identifier, 'after': target_group}]):
        raise ValueError('Residential complete sector groups gained or lost members')


def apply_reviewed_residential_display_associations(manifest_path, features, geometries,
                                                   governors, timetables, original_sources,
                                                   effective_sources, source_sha256, curation,
                                                   reviewed_boundaries=None, official_report=None):
    """Apply exact residential/city display identities after all geographic work.

    The absorbed named settlement is evidence only. A reviewed base may lie
    outside a residential fragment; its coordinate and every imada stay intact.
    Historical base spellings enter search only here, after automatic grouping.
    """
    directory = Path(manifest_path).parent
    try:
        manifest = json.loads(Path(manifest_path).read_bytes())
        entries = manifest.get('residentialDisplayAssociations', [])
        if (manifest.get('schemaVersion') != 1 or manifest.get('sourceSha256') != source_sha256
                or not isinstance(entries, list)):
            raise ValueError('Invalid residential display review inventory')
        if not entries:
            return None, None
        current = {f['id']: f for f in features}
        indices = {f['id']: i for i, f in enumerate(features)}
        groups = defaultdict(set)
        for feature in features:
            groups[feature['pickerGroupId']].add(feature['id'])
        if len(current) != len(features):
            raise ValueError('Repeated residential display raw ID')
        bases = {d['id']: (g, d) for g in governors for d in g['delegations']}
        available = {d['id'] for d in timetables}
        staged, applications, reviewed_names, used = [], [], [], set()

        def document(reference, label):
            value = json.loads(aggregate_review_file(directory, reference, label))
            if not isinstance(value, dict):
                raise ValueError('Invalid residential display evidence document')
            return value

        for entry in entries:
            if (not isinstance(entry, dict)
                    or set(entry) != {'method', 'id', 'targetDelegationId', 'reviewEvidence'}
                    or entry['method'] not in ('reviewed_residential_base_display_identity',
                                             'reviewed_source_bound_residential_base_display_identity',
                                             'reviewed_noncoincident_town_residential_base_identity',
                                             'reviewed_explicit_settlement_polygon_base_display_identity')
                    or not isinstance(entry['id'], str) or entry['id'] not in current
                    or type(entry['targetDelegationId']) is not int
                    or entry['targetDelegationId'] not in bases
                    or entry['targetDelegationId'] not in available):
                raise ValueError('Invalid exact residential/base application')
            identifier, target = entry['id'], entry['targetDelegationId']
            target_group = f'delegation:{target}'
            governor, base = bases[target]
            if entry['method'] == 'reviewed_explicit_settlement_polygon_base_display_identity':
                mutation, application, saved_name, reserved = validate_explicit_settlement_polygon_display(
                    entry, manifest_path, current, indices, groups, geometries, base, governor, timetables,
                    original_sources, effective_sources, source_sha256, curation, used)
                staged.append(mutation)
                applications.append(application)
                used.update(reserved)
                continue
            if entry['method'] == 'reviewed_noncoincident_town_residential_base_identity':
                mutation, application, saved_name, reserved = validate_noncoincident_town_residential_display(
                    entry, directory, current, indices, groups, geometries, base, governor, timetables,
                    original_sources, effective_sources, source_sha256, curation, used,
                    reviewed_boundaries, official_report)
                staged.append(mutation)
                applications.append(application)
                reviewed_names.append(saved_name)
                used.update(reserved)
                continue
            if entry['method'] == 'reviewed_source_bound_residential_base_display_identity':
                mutation, application, saved_name, reserved = validate_source_bound_residential_display(
                    entry, directory, current, indices, groups, geometries, base, governor, timetables,
                    original_sources, effective_sources, source_sha256, curation, used,
                    reviewed_boundaries, official_report)
                staged.append(mutation)
                applications.append(application)
                if saved_name is not None:
                    reviewed_names.append(saved_name)
                used.update(reserved)
                continue
            proof = document(entry['reviewEvidence'], 'residential display identity')
            application = {key: entry[key] for key in ('method', 'id', 'targetDelegationId')}
            if (proof.get('schemaVersion') != 1 or proof.get('sourceSha256') != source_sha256
                    or proof.get('application') != application
                    or proof.get('conclusion') != 'reviewed_residential_base_display_identity'):
                raise ValueError('Residential display evidence does not bind this application')
            review = document(proof['sourceReview'], 'residential source review')
            facts = document(proof['sourceFacts'], 'residential source facts')
            registry = document(proof['officialRegistry'], 'residential administrative registry')
            if (review.get('status') != 'SOURCE_SUPPORTED_TWO_SETTLEMENT_BASE_ASSOCIATIONS_PENDING_EXPLICIT_GUARDED_IMPLEMENTATION'
                    or facts.get('schemaVersion') != 1 or facts.get('sourceSha256') != source_sha256
                    or facts['cachedExtractionSha256'] not in {p['sha256'] for p in review['sourcePins']}
                    or proof['officialRegistry']['sha256'] not in {p['sha256'] for p in review['sourcePins']}):
                raise ValueError('Residential source review or extraction changed')
            matches = [r for r in review['records'] if r['baseId'] == target]
            if len(matches) != 1:
                raise ValueError('Residential identity must have one reviewed base')
            decision = matches[0]
            point_id, parent_id = decision['absorbedSettlementEvidenceId'], decision['sourceDelegationId']
            sector_ids = decision['preservedCurrentSectorIds']
            complete_groups = proof.get('completeSectorGroupPreservation')
            protected_ids, complete_group_report = {identifier, *sector_ids}, None
            if complete_groups is not None:
                if proof.get('baseNameCorrection') is not None:
                    raise ValueError('Residential complete-group preservation permits only a picker-group change')
                protected_ids, complete_group_report = validate_residential_complete_sector_groups(
                    complete_groups, decision, facts, current, groups, identifier, point_id,
                    parent_id, proof['governorSourceId'], target_group)
            if (decision['residentialId'] != identifier
                    or not decision['decision'].startswith('SUPPORT_EXACT_SETTLEMENT_DISPLAY_ASSOCIATION;')
                    or decision['rawPointRestoration'] is not False
                    or decision['prayerSourceUnchanged'] != target
                    or point_id in current or groups.get(identifier) != {identifier}
                    or groups.get(target_group) or len(set(sector_ids)) != len(sector_ids)
                    or (complete_groups is None and any(groups.get(i) != {i} for i in sector_ids))
                    or used & {point_id, target_group, *protected_ids}):
                raise ValueError('Residential display requires separate unchanged settlement and imada choices')
            records = facts['records']
            expected_ids = protected_ids | {point_id, parent_id, proof['governorSourceId']}
            if (set(records) != expected_ids
                    or len(expected_ids) != (len(sector_ids) + 4 if complete_groups is None else len(protected_ids) + 3)
                    or proof['governorate'] != {k: governor[k] for k in ('id', 'nomAr', 'nomFr')}
                    or proof['sourceReviewRecordBaseId'] != target):
                raise ValueError('Residential full source/context inventory changed')
            for source_id, expected in records.items():
                for source_map, provider in ((original_sources, None), (effective_sources, 'osm')):
                    actual = source_map.get(source_id)
                    geometry = actual.get('shape') if actual else None
                    if geometry is None and actual is not None:
                        geometry = actual.get('point')
                    if (actual is None or actual.get('sourceId') != provider
                            or actual['kind'] != expected['kind'] or actual['tags'] != expected['tags']
                            or geometry is None or aggregate_geometry_sha256(geometry) != expected['geometrySha256']
                            or curation.get(source_id) is not None):
                        raise ValueError('Residential source tags, geometry, kind or curation changed')
                if source_id in protected_ids:
                    actual = current.get(source_id)
                    if (actual is None or indices[source_id] >= len(geometries)
                            or {k: v for k, v in actual.items() if k not in {'offset', 'length'}} != expected['metadata']
                            or hashlib.sha256(packed_geometry_bytes(geometries[indices[source_id]])).hexdigest()
                               != expected['packedGeometrySha256']):
                        raise ValueError('Residential or imada metadata/packed footprint changed')
            residential, point = current[identifier], effective_sources[point_id]['point']
            if (residential['kind'] != 'residential' or residential['sourceId'] != 'osm'
                    or residential['delegationId'] != target or residential['governorateId'] != governor['id']
                    or records[point_id]['kind'] not in ('town', 'village')
                    or records[parent_id]['kind'] != 'delegation'
                    or records[proof['governorSourceId']]['kind'] != 'governorate'
                    or records[identifier]['tags'] != decision['originalResidentialTags']
                    or records[identifier]['geometrySha256'] != decision['originalGeometrySha256']
                    or records[identifier]['packedGeometrySha256'] != decision['packedGeometrySha256']
                    or records[point_id]['tags'] != decision['settlementSource']['tags']
                    or [point.y, point.x] != [decision['settlementSource']['lat'], decision['settlementSource']['lng']]
                    or residential['name'] != records[point_id]['tags'].get('name:ar')
                    or records[identifier]['tags'].get('name:ar') != residential['name']
                    or {k: v for k, v in residential.items() if k not in {'offset', 'length'}}
                       != {k: v for k, v in decision['currentRaw'].items() if k not in {'offset', 'length'}}):
                raise ValueError('Residential and absorbed settlement identity changed')
            peer_names = {norm(v) for v in names(records[identifier]['tags']) + names(records[point_id]['tags'])}
            expected_peers = facts['sourceNamePeerIds']
            if (not isinstance(expected_peers, list) or len(expected_peers) != len(set(expected_peers))
                    or not {identifier, point_id} <= set(expected_peers) <= {identifier, point_id, parent_id}):
                raise ValueError('Residential named peers must be exact settlement/context evidence')
            for source_map in (original_sources, effective_sources):
                peers = {i for i, s in source_map.items() if peer_names & {norm(v) for v in names(s['tags'])}}
                if peers != set(expected_peers):
                    raise ValueError('Residential competing named-settlement inventory changed')
            code = decision['officialDelegationCode']
            rows = [r for r in registry['sectors'] if r['delegationCode'] == code]
            if (not re.fullmatch(r'\d{4}', code) or rows != decision['officialSectorInventory']
                    or len(rows) != len(sector_ids)
                    or records[parent_id]['tags'].get('ref:tn:codegeo') != code
                    or records[proof['governorSourceId']]['tags'].get('ref:tn:codegeo') != code[:2]):
                raise ValueError('Residential complete official parent inventory changed')
            for source_map in (original_sources, effective_sources):
                actual_sectors = {i for i, s in source_map.items() if s['kind'] == 'sector'
                                  and s['tags'].get('ref:tn:codegeo', '').startswith(code)}
                if actual_sectors != set(sector_ids):
                    raise ValueError('Residential full imada source inventory changed')
                for source_id in [parent_id, proof['governorSourceId'], *sector_ids]:
                    tag_code = records[source_id]['tags']['ref:tn:codegeo']
                    claims = [i for i, s in source_map.items() if s['kind'] == records[source_id]['kind']
                              and s['tags'].get('ref:tn:codegeo') == tag_code]
                    if claims != [source_id]:
                        raise ValueError('Residential administrative code is not unique')
            if {records[i]['tags']['ref:tn:codegeo'] for i in sector_ids} != {r['sectorCode'] for r in rows}:
                raise ValueError('Residential imada codes differ from the reviewed registry')
            base_point = Point(base['lng'], base['lat'])
            base_inside = decision['baseAndSourceContainment']['legacyBase']['nativeContains']
            if (type(base_inside) is not bool
                    or decision['baseAndSourceContainment']['legacyBase']['packedContains'] != base_inside):
                raise ValueError('Residential base containment was not explicitly reviewed')
            for polygon in (original_sources[identifier]['shape'], effective_sources[identifier]['shape'],
                            geometries[indices[identifier]]):
                if not polygon.covers(point) or polygon.covers(base_point) != base_inside:
                    raise ValueError('Residential reviewed point relationship changed')
            for source_map in (original_sources, effective_sources):
                for source_id in (parent_id, proof['governorSourceId']):
                    if not all(source_map[source_id]['shape'].covers(q) for q in (point, base_point)):
                        raise ValueError('Residential references left their reviewed administrative context')
            for reference in (residential, base, {'lat': point.y, 'lng': point.x}):
                if min(timetables, key=lambda d: (distance(reference['lat'], reference['lng'], d), d['id']))['id'] != target:
                    raise ValueError('Residential reviewed raw/base prayer assignment changed')
            for reference in proof['primarySources']:
                if reference['sha256'] not in {p['sha256'] for p in review['sourcePins']}:
                    raise ValueError('Residential primary evidence was not in the source review')
                aggregate_review_file(directory, reference, 'residential primary evidence')
            if not proof['primarySources']:
                raise ValueError('Residential display requires pinned primary evidence')
            aliases = list(residential['aliases'])
            correction = proof.get('baseNameCorrection')
            if correction is None:
                if base != decision['currentBase']:
                    raise ValueError('Residential canonical base changed')
            else:
                addendum = document(correction['reviewEvidence'], 'saved base name/search review')
                requested = decision['nameDecision']['supportedBaseArabicCorrection']
                before = decision['currentBase']
                expected_after = {**before, 'nomAr': requested['after']}
                saved_name = {'id': target_group, 'name': requested['after'], 'kind': 'delegation'}
                if (set(correction) != {'reviewEvidence', 'beforeBase', 'afterBase', 'savedName', 'historicalSearchAlias'}
                        or requested != {'id': target, 'field': 'nomAr', 'before': before['nomAr'], 'after': residential['name']}
                        or correction['beforeBase'] != before or correction['afterBase'] != expected_after
                        or base != expected_after or correction['savedName'] != saved_name
                        or correction['historicalSearchAlias'] != before['nomAr']
                        or before['nomAr'] == residential['name'] or before['nomAr'] in aliases
                        or addendum['originalFrozenReview']['sha256'] != proof['sourceReview']['sha256']
                        or addendum.get('status') != 'NARROW_FUTURE_IMPLEMENTATION_ADDENDUM_NOT_APPLIED'
                        or not any(change.get('append') == saved_name for change in addendum['proposedExactCombinedChanges'])):
                    raise ValueError('Residential saved-name correction lacks exact reviewed old/new identity')
                aliases = sorted(set(aliases + [before['nomAr']]))
                reviewed_names.append(saved_name)
            protected = {i: {k: v for k, v in current[i].items() if k not in {'pickerGroupId', 'offset', 'length'}}
                         for i in ([identifier, *sector_ids] if complete_groups is None else sorted(protected_ids))}
            protected[identifier] = {**protected[identifier], 'aliases': aliases}
            final_groups = [{'id': target_group, 'memberIds': sorted([target_group, identifier])}]
            final_groups.extend({'id': i, 'memberIds': [i]} for i in sector_ids)
            result = {'id': identifier, 'pickerGroupId': target_group,
                      'reviewEvidence': entry['reviewEvidence'], 'finalGroups': final_groups,
                      'protectedMetadata': protected, 'preservedAbsorbedPointId': point_id,
                      'preservedRawSource': target, 'canonicalManualSource': target}
            if complete_group_report is not None:
                result['finalGroups'] = [{'id': i, 'memberIds': ids}
                                         for i, ids in complete_group_report['expectedAfterGroups'].items()]
                result['completeSectorGroupPreservation'] = complete_group_report
            applications.append(result)
            staged.append((identifier, target_group, aliases))
            used.update({point_id, target_group, *protected_ids})
        # No mutation until every application passes; existing final verifiers
        # also run after this phase, so no earlier reviewed choice can be hidden.
        for identifier, target_group, aliases in staged:
            current[identifier]['pickerGroupId'] = target_group
            current[identifier]['aliases'] = aliases
        combined = defaultdict(list)
        for feature in features:
            combined[feature['pickerGroupId']].append(feature['id'])
        picker_groups = [{'pickerGroupId': group, 'ids': sorted(members)}
                         for group, members in sorted(combined.items())
                         if len(members) > 1 or group.startswith('delegation:')]
        return {'reviewedGroupCount': len(staged), 'preservedMemberCount': len(staged),
                'applications': applications, 'reviewedBaseNames': reviewed_names}, picker_groups
    except (OSError, UnicodeError, ValueError, KeyError, TypeError, AttributeError, IndexError) as error:
        raise ValueError('Missing, malformed or changed residential display identity review') from error
def verify_reviewed_city_display_associations(features, report):
    """Check exact final choices and unchanged raw fields after every override."""
    if report is None:
        return
    current = {feature['id']: feature for feature in features}
    if len(current) != len(features):
        raise ValueError('Duplicate locality ID after city display review')
    groups = defaultdict(set)
    for feature in features:
        groups[feature['pickerGroupId']].add(feature['id'])
    for application in report['applications']:
        if 'completeSectorGroupPreservation' in application:
            verify_residential_complete_sector_groups(application, current, groups)
        for group in application['finalGroups']:
            members = groups.get(group['id'], set())
            if group['id'].startswith('delegation:'):
                members = members | {group['id']}
            if members != set(group['memberIds']):
                raise ValueError('Reviewed city display choices joined, expanded or disappeared')
        for identifier, metadata in application['protectedMetadata'].items():
            if (identifier not in current
                    or {key: value for key, value in current[identifier].items()
                        if key not in {'pickerGroupId', 'offset', 'length'}} != metadata):
                raise ValueError('City display association changed a protected raw geographic field')
        if application.get('identityMethod') == 'reviewed_explicit_settlement_polygon_base_display_identity':
            absent = application.get('preservedAbsentSourceIds')
            if (not isinstance(absent, list) or len(absent) != len(set(absent))
                    or not all(isinstance(i, str) and i not in current for i in absent)
                    or application['preservedNonCatalogPlace']['id'] not in absent):
                raise ValueError('Explicit settlement source-only inventory became selectable')
        if 'preservedNonCatalogPlace' in application:
            preserved = application['preservedNonCatalogPlace']
            if (set(preserved) != {'id', 'role'}
                    or preserved['role'] not in ('absorbed_extracted_village', 'absorbed_extracted_town',
                                                  'excluded_city_supplement_only')
                    or not isinstance(preserved['id'], str) or preserved['id'] in current):
                raise ValueError('Source-bound display unexpectedly recreated an evidence-only place')
        elif application.get('identityMethod') == 'reviewed_complete_locality_group_owner':
            owner_id = application.get('retainedOwnerId')
            if (owner_id not in current or current[owner_id]['kind'] != 'village'
                    or current[owner_id]['pickerGroupId'] != owner_id):
                raise ValueError('Reviewed complete local group lost its retained village owner')
        elif application['preservedAbsorbedPointId'] in current:
            raise ValueError('City display association unexpectedly recreated its absorbed source point')
        if 'acceptedBoundaryAbsorption' in application:
            absorption = application['acceptedBoundaryAbsorption']
            if (set(absorption) != {'pointId', 'receiverId', 'receiverAliases'}
                    or absorption['pointId'] != application['preservedAbsorbedPointId']
                    or absorption['receiverId'] != application['id']
                    or absorption['pointId'] in current or groups.get(absorption['pointId'])
                    or any(feature.get('manualPointId') == absorption['pointId'] for feature in features)
                    or absorption['receiverAliases'] != application['protectedMetadata'][absorption['receiverId']]['aliases']
                    or current[absorption['receiverId']]['aliases'] != absorption['receiverAliases']):
                raise ValueError('Accepted split boundary changed its final source-only absorption')

def validate_reviewed_contained_settlement_reference(identity, manifest_path, record,
                                                      features, indices, geometries,
                                                      original_sources, effective_sources,
                                                      source_sha256, base, governor,
                                                      timetables, curation):
    """Bind an exact standalone residential/base choice to its named settlement.

    Supplemental city nodes remain byte-pinned external identity evidence. They
    never enter the extracted source inventory, catalog, GPS lookup or PLACES.
    """
    import xml.etree.ElementTree as ET
    try:
        directory = manifest_path.parent
        document = lambda ref, label: json.loads(aggregate_review_file(directory, ref, label))
        facts = document(identity['reviewEvidence'], 'contained settlement source facts')
        review = document(facts['sourceReview'], 'contained settlement source review')
        identifier, point_id, target = record['id'], identity['id'], record['targetDelegationId']
        rows = [r for r in facts['records'] if r['id'] == identifier]
        approved = [r for r in review['records'] if r['id'] == identifier]
        if (facts.get('schemaVersion') != 1 or facts.get('sourceSha256') != source_sha256
                or review.get('status') != 'six_source_supported_display_candidates_no_existing_method_fit'
                or len(rows) != 1 or len(approved) != 1
                or identity.get('method') != 'reviewed_contained_settlement_reference'
                or identity.get('accepted') is not True or identity.get('residentialId') != identifier
                or identity.get('targetDelegationId') != target):
            raise ValueError('Contained settlement review identity changed')
        row, reviewed = rows[0], approved[0]
        if (row['sourceReviewRecord'] != reviewed or reviewed['baseId'] != target
                or reviewed['sourceSettlement']['id'] != point_id
                or row['expectedBase'] != base or row['expectedGovernorateId'] != governor['id']
                or any(base[key] != reviewed['baseReference'][key] for key in ('nomAr', 'nomFr', 'lat', 'lng'))
                or record['expectedExistingMemberIds'] != [identifier]
                or record['expectedTargetMemberIds'] != [f'delegation:{target}']
                or point_id in indices or identifier not in indices
                or record['requiredCurations'] != []):
            raise ValueError('Contained settlement requires the exact standalone pair and absent point')
        index = indices[identifier]
        current = features[index]
        if (index >= len(geometries)
                or {k: v for k, v in current.items() if k not in {'offset', 'length'}} != row['expectedMetadata']
                or current['kind'] != 'residential' or current['sourceId'] != 'osm'
                or current['name'] != base['nomAr'] or current['name'] != reviewed['name']
                or current['delegationId'] != target or current['governorateId'] != governor['id']
                or hashlib.sha256(packed_geometry_bytes(geometries[index])).hexdigest() != row['packedGeometrySha256']):
            raise ValueError('Contained settlement raw choice or packed footprint changed')
        assertions = row['sourceAssertions']
        expected = {item['id']: item for item in assertions}
        if (len(expected) != len(assertions) or identifier not in expected
                or expected[identifier]['tags'] != reviewed['rawSourceTags']
                or expected[identifier]['geometrySha256'] != reviewed['originalGeometrySha256']
                or row['packedGeometrySha256'] != reviewed['packedGeometrySha256']):
            raise ValueError('Repeated or missing contained settlement source assertion')
        for label, sources in (('original', original_sources), ('effective', effective_sources)):
            for source_id, assertion in expected.items():
                source = sources.get(source_id)
                if source is None:
                    raise ValueError('Missing contained settlement source')
                geometry = source.get('shape')
                if geometry is None:
                    geometry = source.get('point')
                if (geometry is None or source.get('sourceId') != (None if label == 'original' else 'osm')
                        or source['kind'] != assertion['kind'] or source['tags'] != assertion['tags']
                        or aggregate_geometry_sha256(geometry) != assertion['geometrySha256']
                        or curation.get(source_id) is not None):
                    raise ValueError('Contained settlement original/effective source changed')
        peer_names = row['peerNameValues']
        if (not isinstance(peer_names, list) or not peer_names
                or any(not isinstance(v, str) or not clean(v) for v in peer_names)
                or base['nomAr'] not in peer_names):
            raise ValueError('Missing exact contained settlement name inventory')
        peer_keys = {clean(value).casefold() for value in peer_names}
        matching = lambda sources: {i for i, obj in sources.items()
                                   if peer_keys & {clean(v).casefold() for v in names(obj['tags'])}}
        expected_peers = set(row['matchingExtractedSourceIds'])
        if (len(expected_peers) != len(row['matchingExtractedSourceIds'])
                or not expected_peers <= set(expected)
                or matching(original_sources) != expected_peers
                or matching(effective_sources) != expected_peers):
            raise ValueError('Contained settlement competing source inventory changed')
        settlement = row['settlement']
        if (settlement['id'] != point_id or settlement['tags'] != reviewed['sourceSettlement']['tags']
                or any(settlement[key] != reviewed['sourceSettlement'][key] for key in ('lat', 'lng'))
                or settlement['tags'].get('name:ar') != base['nomAr']
                or settlement['tags'].get('place') != settlement['kind']
                or settlement['kind'] not in ('town', 'village', 'city')):
            raise ValueError('Contained settlement typed name or point changed')
        point = Point(settlement['lng'], settlement['lat'])
        if settlement['kind'] == 'city':
            if point_id in original_sources or point_id in effective_sources:
                raise ValueError('Supplemental city unexpectedly entered catalog extraction')
            bridge = row['liveSourceBridge']
            live_report = document(bridge['report'], 'live city-node source bridge')
            historical = document(bridge['cachedCitySources'], 'historical city source inventory')
            if (live_report.get('status') != 'PASS_EXACT_LIVE_API_MATCH'
                    or live_report.get('retrievalCount') != 3
                    or bridge['cachedCitySources']['sha256'] != live_report['cachedSha256']):
                raise ValueError('Supplemental city source bridge changed')
            saved = {obj['id']: obj for obj in historical}
            live = {obj['id']: obj for obj in live_report['records']}
            responses = {obj['id']: obj for obj in bridge['responses']}
            if (len(saved) != len(historical) or len(live) != 3 or len(responses) != 3
                    or len(bridge['responses']) != 3 or set(live) != set(responses) or point_id not in live):
                raise ValueError('Supplemental city bridge has incomplete or repeated IDs')
            for source_id, live_row in live.items():
                response = responses[source_id]
                xml = ET.fromstring(aggregate_review_file(directory, response, 'public city-node response'))
                elements = xml.findall('node')
                if (xml.tag != 'osm' or len(elements) != 1 or list(xml) != elements
                        or response['sha256'] != live_row['sha256'] or source_id not in saved):
                    raise ValueError('Unexpected public city-node response inventory')
                node = elements[0]
                tags = {tag.attrib['k']: tag.attrib['v'] for tag in node.findall('tag')}
                actual = {'id': f"osm:node:{node.attrib['id']}", 'tags': tags,
                          'lat': float(node.attrib['lat']), 'lng': float(node.attrib['lon'])}
                if (len(tags) != len(node.findall('tag')) or list(node) != node.findall('tag')
                        or node.attrib.get('visible') != 'true' or actual['id'] != source_id
                        or tags.get('place') != 'city' or actual != saved[source_id]
                        or actual != live_row['cachedRecord'] or tags != live_row['tags']
                        or actual['lat'] != live_row['coordinates']['lat']
                        or actual['lng'] != live_row['coordinates']['lng']
                        or int(node.attrib['version']) != live_row['nodeVersion']
                        or node.attrib['timestamp'] != live_row['nodeTimestamp']
                        or node.attrib['changeset'] != live_row['changeset']
                        or live_row['url'] != f"https://api.openstreetmap.org/api/0.6/node/{node.attrib['id']}"
                        or live_row.get('source') != 'live_public_OSM_API_not_PBF_extraction'
                        or not live_row.get('retrievedAt') or live_row.get('statusCode') != 200
                        or live_row.get('allTagsEqual') is not True or live_row.get('coordinatesEqual') is not True
                        or live_row.get('tagDifferences') != {}):
                    raise ValueError('Supplemental live city identity differs from its reviewed snapshot')
            if saved[point_id] != {key: settlement[key] for key in ('id', 'tags', 'lat', 'lng')}:
                raise ValueError('Supplemental city point differs from the display proof')
            supplemental_peers = sorted(i for i, obj in saved.items()
                                        if peer_keys & {clean(v).casefold() for v in names(obj['tags'])})
            if supplemental_peers != row['matchingSupplementalCityIds'] or supplemental_peers != [point_id]:
                raise ValueError('Supplemental named city identity is no longer unique')
        else:
            if (point_id not in expected or expected[point_id]['kind'] != settlement['kind']
                    or expected[point_id]['tags'] != settlement['tags']
                    or original_sources[point_id].get('shape') is not None
                    or effective_sources[point_id].get('shape') is not None
                    or original_sources[point_id]['point'].coords[:] != point.coords[:]
                    or effective_sources[point_id]['point'].coords[:] != point.coords[:]
                    or row.get('liveSourceBridge') is not None or row['matchingSupplementalCityIds'] != []):
                raise ValueError('Absorbed settlement differs from the retained source point')
        governor_id = row['governorSourceId']
        governor_source = effective_sources.get(governor_id)
        if (governor_id not in expected or governor_source['kind'] != 'governorate'
                or governor_source['tags'].get('name:ar') != 'ولاية ' + governor['nomAr']
                or current['parentName'] != governor_source['tags']['name:ar']
                or current['contextAliases'] != []):
            raise ValueError('Contained settlement governorate context changed')
        base_point = Point(base['lng'], base['lat'])
        for sources in (original_sources, effective_sources):
            footprint = sources[identifier]['shape']
            context = sources[governor_id]['shape']
            if (not footprint.contains(point) or not footprint.contains(base_point)
                    or not context.covers(footprint) or not context.contains(point)
                    or not context.contains(base_point)):
                raise ValueError('Contained settlement/base/context containment changed')
            local_settlements = {i for i in expected_peers if sources[i]['kind'] in PLACES
                                 and sources[i].get('point') is not None
                                 and context.contains(sources[i]['point'])}
            expected_local = set() if settlement['kind'] == 'city' else {point_id}
            if (local_settlements != expected_local
                    or {i for i in expected_peers if sources[i]['kind'] == 'residential'} != {identifier}):
                raise ValueError('Contained residential or same-context settlement is not unique')
        if (not geometries[index].contains(point) or not geometries[index].contains(base_point)
                or point.equals(base_point)
                or row['rawAndBaseCoordinatesDiffer'] is not True
                or (current['lat'], current['lng']) == (base['lat'], base['lng'])):
            raise ValueError('Contained settlement reviewed separate reference points changed')
        for lat, lng in ((current['lat'], current['lng']), (point.y, point.x), (base['lat'], base['lng'])):
            if (not math.isfinite(lat) or not math.isfinite(lng)
                    or min(timetables, key=lambda d: (distance(lat, lng, d), d['id']))['id'] != target):
                raise ValueError('Contained settlement references no longer share their prayer source')
    except (OSError, UnicodeError, ValueError, KeyError, TypeError, AttributeError, IndexError, ET.ParseError) as error:
        raise ValueError('Missing, malformed or changed contained settlement/base evidence') from error

def validate_soliman_split_boundary_lineage(directory, wrapper, row, proposal, facts,
                                           original_sources, effective_sources, source_sha256,
                                           features, indices, geometries, groups, base, governor,
                                           curation, reviewed_boundaries, official_report):
    """Bind only Soliman's accepted imada replacement to its unchanged town proof."""
    document = lambda ref, label: json.loads(aggregate_review_file(directory, ref, label))
    lineage = document(row['acceptedBoundaryLineage'], 'Soliman accepted boundary lineage')
    identifier, sector_id, point_id = 'osm:way:177385463', 'osm:relation:7100693', 'osm:node:1124155543'
    historical_ref = {'file': 'three-city-split-reviews/three-city-split-source-proof.json',
                      'sha256': 'ff66bf2aedeed7e18832b077175acf02968aefddb23cb2850d8c79af9f3f2f62'}
    fields = {'schemaVersion', 'method', 'sourceSha256', 'residentialId', 'sectorId', 'pointId',
              'baseId', 'historicalProof', 'reviewedBoundaries', 'sourceStates',
              'originalNamePeerIds', 'effectiveNamePeerIds', 'acceptedReplacement',
              'currentDistinctPair', 'sectorProjection', 'sectorContainment', 'sourceAbsorption'}
    if (not isinstance(lineage, dict) or set(lineage) != fields or lineage['schemaVersion'] != 1
            or lineage['method'] != 'reviewed_soliman_split_accepted_boundary_lineage'
            or lineage['sourceSha256'] != source_sha256 or lineage['residentialId'] != identifier
            or lineage['sectorId'] != sector_id or lineage['pointId'] != point_id
            or lineage['baseId'] != 465 or base['id'] != 465 or governor['id'] != 350
            or row['id'] != identifier or proposal['sectorId'] != sector_id
            or proposal['absorbedSourcePointId'] != point_id or lineage['historicalProof'] != historical_ref):
        raise ValueError('Accepted split boundary lineage is not the exact reviewed Soliman case')
    historical = document(historical_ref, 'historical three-city split proof')
    # Preserve the original wrapper and every other city's row, not a newly
    # labelled copy of effective source data masquerading as historical facts.
    restored_rows = [{key: value for key, value in item.items() if key != 'acceptedBoundaryLineage'}
                     if item.get('id') == identifier else item for item in wrapper['records']]
    if ({**wrapper, 'records': restored_rows} != historical
            or sum(item.get('id') == identifier for item in wrapper['records']) != 1
            or any('acceptedBoundaryLineage' in item for item in wrapper['records'] if item.get('id') != identifier)
            or wrapper['sourceFacts']['sha256'] != hashlib.sha256(
                aggregate_review_file(directory, wrapper['sourceFacts'], 'historical split facts')).hexdigest()):
        raise ValueError('Accepted split boundary lineage rewrote historical wrapper fields')
    reference = lineage['reviewedBoundaries']
    boundary_manifest = load_reviewed_boundary_manifest_for_lineage(
        directory, reference, reviewed_boundaries, official_report)
    assertions = {item['id']: item for item in row['sourceAssertions']}
    context_ids = {'osm:relation:1435825', 'osm:relation:7145292'}
    required_ids = {identifier, sector_id, point_id} | context_ids
    records = lineage['sourceStates']
    states = {item['id']: item for item in records}
    if (len(assertions) != len(row['sourceAssertions']) or set(assertions) != required_ids
            or len(records) != len(states) or set(states) != required_ids
            or any(set(item) != {'id', 'original', 'effective'} for item in records)):
        raise ValueError('Accepted split boundary lineage needs the complete five-source closure')
    keys = {clean(value).casefold() for value in row['peerNameValues']}
    required_peers = sorted(row['matchingExtractedSourceIds'])
    if len(required_peers) != len(set(required_peers)):
        raise ValueError('Repeated historical Soliman source-name peer')
    for phase, sources in (('original', original_sources), ('effective', effective_sources)):
        peers = sorted(i for i, source in sources.items()
                       if keys & {clean(value).casefold() for value in names(source['tags'])})
        if lineage[phase + 'NamePeerIds'] != required_peers or peers != required_peers:
            raise ValueError('Accepted split boundary lineage changed complete name peers')
        for source_id, item in states.items():
            source = sources[source_id]
            geometry = source.get('shape') if source.get('shape') is not None else source.get('point')
            actual = {'kind': source['kind'], 'tags': source['tags'], 'sourceId': source.get('sourceId'),
                      'geometrySha256': aggregate_geometry_sha256(geometry)}
            original = {key: value for key, value in assertions[source_id].items() if key != 'id'}
            original['sourceId'] = None
            if (actual != item[phase] or item['original'] != original
                    or (source_id != sector_id and item['effective'] != {**original, 'sourceId': 'osm'})
                    or curation.get(source_id) is not None):
                raise ValueError('Accepted split boundary lineage altered an unreviewed source state')
    replacement = lineage['acceptedReplacement']
    if set(replacement) != {'sourceRecord', 'record', 'application', 'geojson', 'featureSha256'}:
        raise ValueError('Malformed accepted Soliman replacement binding')
    source, accepted = replacement['sourceRecord'], replacement['record']
    original, effective = states[sector_id]['original'], states[sector_id]['effective']
    application = {'id': sector_id, 'action': 'replace', 'officialCode': '156154', 'sourceId': source['id']}
    if ([s for s in boundary_manifest['sources'] if s['id'] == source['id']] != [source]
            or [r for r in source['records'] if r['id'] == sector_id] != [accepted]
            or accepted['id'] != sector_id or accepted['action'] != 'replace'
            or accepted['officialCode'] != '156154' or accepted['expectedOriginalTags'] != original['tags']
            or source['sourceSha256'] != source_sha256 or not source.get('review')
            or original['kind'] != 'sector' or effective['kind'] != 'sector'
            or effective['sourceId'] != source['id'] or source['id'] == 'osm'
            or replacement['application'] != application
            or [r for r in official_report['applications'] if r['id'] == sector_id] != [application]
            or official_report['sources'].get(source['id']) != {k: v for k, v in source.items() if k not in ('file', 'records')}
            or replacement['geojson'] != {'file': source['file'], 'sha256': source['sha256']}):
        raise ValueError('Soliman lineage differs from the actual accepted source record')
    collection = json.loads(aggregate_review_file(Path(reviewed_boundaries).parent,
                            replacement['geojson'], 'accepted Soliman native geometry'))
    matches = [feature for feature in collection['features'] if feature['id'] == sector_id]
    if len(matches) != 1:
        raise ValueError('Accepted Soliman native feature is not unique')
    native = matches[0]
    properties = native['properties']
    tags = {'boundary': 'administrative', 'admin_level': '6', 'ref:tn:codegeo': '156154',
            'name:ar': properties['nameAr'], 'name:fr': properties['nameFr']}
    if accepted.get('aliases'):
        tags['alt_name'] = ';'.join(accepted['aliases'])
    feature_sha = hashlib.sha256(json.dumps(native, ensure_ascii=False, sort_keys=True,
                                            separators=(',', ':')).encode('utf-8')).hexdigest()
    if (native['type'] != 'Feature' or properties['sourceId'] != source['id']
            or properties['officialCode'] != '156154' or tags != effective['tags']
            or replacement['featureSha256'] != feature_sha
            or aggregate_geometry_sha256(shape(native['geometry'])) != effective['geometrySha256']):
        raise ValueError('Accepted native geometry does not produce the effective Soliman source')
    projection = lineage['sectorProjection']
    if set(projection) != {'metadata', 'packedGeometrySha256'}:
        raise ValueError('Malformed accepted Soliman packed projection')
    old_fact = next(item for item in facts['records'] if item['id'] == sector_id)
    old = {key: value for key, value in old_fact['currentMetadata'].items() if key not in {'offset', 'length'}}
    new = projection['metadata']
    allowed = {'sourceId', 'name', 'aliases', 'parentName', 'contextAliases', 'lat', 'lng', 'bbox', 'areaKm2', 'delegationId'}
    index = indices[sector_id]
    packed = geometries[index]
    expected_packed = set_precision(effective_sources[sector_id]['shape'], 1 / SCALE)
    expected_bytes = packed_geometry_bytes(expected_packed)
    anchor = expected_packed.representative_point()
    if (set(new) != set(old) or any(new[key] != old[key] for key in old if key not in allowed)
            or new != {key: value for key, value in features[index].items() if key not in {'offset', 'length'}}
            or new['sourceId'] != source['id'] or new['name'] != names(tags)[0]
            or new['aliases'] != names(tags)[1:] or new['delegationId'] != 465
            or new['lat'] != round(anchor.y, 7) or new['lng'] != round(anchor.x, 7)
            or new['bbox'] != list(expected_packed.bounds)
            or new['areaKm2'] != expected_packed.area * 111.32**2 * math.cos(math.radians(anchor.y))
            or packed_geometry_bytes(packed) != expected_bytes
            or projection['packedGeometrySha256'] != hashlib.sha256(expected_bytes).hexdigest()
            or not packed.covers(Point(new['lng'], new['lat']))):
        raise ValueError('Accepted Soliman projection altered its computed inside anchor or packed footprint')
    pair = lineage['currentDistinctPair']
    historical_pair = row['requiredDistinctPair']
    if (set(pair) != set(historical_pair) or pair['ids'] != historical_pair['ids']
            or pair['reviewEvidence'] == historical_pair['reviewEvidence']):
        raise ValueError('Accepted Soliman lineage needs an exact distinct-pair successor')
    old_proof = document(historical_pair['reviewEvidence'], 'historical Soliman distinct pair')
    new_proof = document(pair['reviewEvidence'], 'effective Soliman distinct pair')
    if (set(old_proof) != set(new_proof)
            or any(old_proof[key] != new_proof[key] for key in old_proof if key not in {'records', 'evidence'})
            or len(new_proof['records']) != len(old_proof['records'])
            or new_proof['evidence'][:len(old_proof['evidence'])] != old_proof['evidence']):
        raise ValueError('Soliman distinct-pair successor rewrote historical evidence')
    mutable_pair_fields = {'effectiveTags', 'effectiveGeometrySha256', 'expectedMetadata'}
    for before, after in zip(old_proof['records'], new_proof['records']):
        if before['id'] != sector_id:
            if after != before:
                raise ValueError('Soliman distinct successor changed the protected residential record')
        elif (set(after) != set(before)
                or any(after[key] != before[key] for key in before if key not in mutable_pair_fields)
                or after['effectiveTags'] != effective['tags']
                or after['effectiveGeometrySha256'] != effective['geometrySha256']
                or after['expectedMetadata'] != {key: new[key] for key in before['expectedMetadata']}):
            raise ValueError('Soliman distinct successor differs from the accepted sector projection')
    for evidence in new_proof['evidence']:
        aggregate_review_file(directory, evidence, 'Soliman distinct successor source evidence')
    point = effective_sources[point_id]['point']
    base_point = Point(base['lng'], base['lat'])
    containment = lineage['sectorContainment']
    if set(containment) != {'original', 'effective', 'packed'}:
        raise ValueError('Soliman needs separate original, native and packed containment claims')
    for phase, geometry in (('original', original_sources[sector_id]['shape']),
                            ('effective', effective_sources[sector_id]['shape']), ('packed', packed)):
        actual = {'containsBase': geometry.covers(base_point),
                  'containsSourceSettlementPoint': geometry.covers(point)}
        if (set(containment[phase]) != set(actual)
                or any(type(value) is not bool for value in containment[phase].values())
                or containment[phase] != actual):
            raise ValueError('Soliman source-phase containment claims do not match actual geometry')
    old_claims = proposal['geometryFactsNotBoundaryCertification']['sector']
    if (containment['original'] != {'containsBase': old_claims['originalContainsBase'],
                                   'containsSourceSettlementPoint': old_claims['originalContainsSourceSettlementPoint']}
            or containment['original'] != {'containsBase': False, 'containsSourceSettlementPoint': False}
            or containment['effective'] != {'containsBase': False, 'containsSourceSettlementPoint': True}):
        raise ValueError('Soliman lineage is not the reviewed village/base containment transition')
    absorption = lineage['sourceAbsorption']
    if (set(absorption) != {'pointId', 'receiverId', 'pointCoordinates', 'originalPackedMatches',
                           'effectivePackedMatches', 'receiverAliases'}
            or absorption['pointId'] != point_id or absorption['receiverId'] != identifier
            or absorption['pointCoordinates'] != {'lat': point.y, 'lng': point.x}
            or point_id in indices or groups.get(point_id)):
        raise ValueError('Soliman absorption identity or global source-only absence changed')
    point_names = names(effective_sources[point_id]['tags'])
    point_identity = {'name': point_names[0], 'aliases': point_names[1:]}
    original_matches = []
    for source_id, obj in original_sources.items():
        if obj['kind'] in ('country', 'governorate', 'delegation') or obj.get('shape') is None:
            continue
        labels = names(obj['tags'])
        if not labels or not same_picker_name(point_identity, {'name': labels[0], 'aliases': labels[1:]}):
            continue
        candidate = set_precision(obj['shape'], 1 / SCALE)
        if candidate.is_empty or not candidate.covers(point):
            continue
        if source_id not in indices or curation.get(source_id) is not None:
            raise ValueError('Soliman original absorbing source is no longer an unchanged raw choice')
        original_matches.append({'id': source_id,
            'packedGeometrySha256': hashlib.sha256(packed_geometry_bytes(candidate)).hexdigest(),
            'areaKm2': candidate.area * 111.32**2 * math.cos(math.radians(candidate.representative_point().y))})
    effective_matches = [{'id': feature['id'],
        'packedGeometrySha256': hashlib.sha256(packed_geometry_bytes(geometries[i])).hexdigest(),
        'areaKm2': feature['areaKm2']}
        for i, feature in enumerate(features[:len(geometries)])
        if geometries[i].covers(point) and same_picker_name(point_identity, feature)]
    for key, matches in (('originalPackedMatches', original_matches), ('effectivePackedMatches', effective_matches)):
        matches.sort(key=lambda item: item['id'])
        if (not matches or absorption[key] != matches
                or not {item['id'] for item in matches} <= {identifier, sector_id}):
            raise ValueError('Soliman complete original/effective absorbing polygon inventory changed')
        ranked = sorted(matches, key=lambda item: (item['areaKm2'], item['id']))
        if (ranked[0]['id'] != identifier
                or len(ranked) > 1 and ranked[0]['areaKm2'] == ranked[1]['areaKm2']):
            raise ValueError('Soliman source village changed its unique smallest-area receiver')
    receiver = features[indices[identifier]]
    if (absorption['receiverAliases'] != receiver['aliases']
            or receiver['aliases'] != sorted(set(names(effective_sources[identifier]['tags'])[1:] + point_names)
                                            - {receiver['name']})):
        raise ValueError('Soliman absorbed village changed its receiver aliases')
    return {'reference': row['acceptedBoundaryLineage'], 'states': states,
            'currentDistinctPair': pair, 'sectorProjection': projection, 'sectorContainment': containment,
            'absorption': {'pointId': point_id, 'receiverId': identifier,
                           'receiverAliases': absorption['receiverAliases']}}

def validate_reviewed_split_settlement_reference(identity, manifest_path, manifest, record,
                                                  features, indices, geometries, groups,
                                                  original_sources, effective_sources,
                                                  source_sha256, base, governor, timetables, curation,
                                                  reviewed_boundaries=None, official_report=None):
    """Associate three specifically reviewed towns while preserving their imadas.

    This is an identity validator in the strict base-group loop. It does not
    change its grouping, containment, timetable or identity-name preconditions.
    """
    try:
        directory = manifest_path.parent
        document = lambda ref, label: json.loads(aggregate_review_file(directory, ref, label))
        wrapper = document(identity['reviewEvidence'], 'split settlement proof')
        review = document(wrapper['sourceReview'], 'split settlement source review')
        facts = document(wrapper['sourceFacts'], 'split settlement exact source facts')
        registry = document(wrapper['officialRegistry'], 'split settlement official registry')
        identifier, target, point_id = record['id'], record['targetDelegationId'], identity['id']
        rows = [r for r in wrapper['records'] if r['id'] == identifier]
        proposals = [r for r in review['proposals'] if r['residentialId'] == identifier]
        if (wrapper.get('schemaVersion') != 1 or wrapper.get('sourceSha256') != source_sha256
                or facts.get('schemaVersion') != 1 or facts.get('sourceSha256') != source_sha256
                or review.get('status') != 'FROZEN_PROPOSAL_PENDING_CODE_AND_ACTUAL_GENERATION'
                or len(rows) != 1 or len(proposals) != 1
                or identity.get('method') != 'reviewed_split_settlement_reference'
                or identity.get('accepted') is not True or identity.get('residentialId') != identifier):
            raise ValueError('Split settlement needs its exact reviewed source identity')
        row, proposal = rows[0], proposals[0]
        sector_id, base_group = proposal['sectorId'], f'delegation:{target}'
        source_ids = {identifier, sector_id, point_id}
        lineage = None
        if 'acceptedBoundaryLineage' in row:
            lineage = validate_soliman_split_boundary_lineage(
                directory, wrapper, row, proposal, facts, original_sources, effective_sources, source_sha256,
                features, indices, geometries, groups, base, governor, curation, reviewed_boundaries, official_report)
        active_pair = lineage['currentDistinctPair'] if lineage is not None else row['requiredDistinctPair']
        if (row['sourceReviewRecord'] != proposal or proposal['absorbedSourcePointId'] != point_id
                or proposal['targetDelegationId'] != target or proposal['targetBaseId'] != base_group
                or proposal['currentBase'] != {**base, 'governorateId': governor['id']}
                or row['requiredDistinctPair']['ids'] != [sector_id, identifier]
                or row['requiredDistinctPair']['reviewEvidence']['sha256'] != proposal['requiredDistinctPair']['reviewEvidence']['sha256']
                or sum(pair == active_pair for pair in manifest.get('distinctPairs', [])) != 1
                or record['expectedExistingMemberIds'] != [identifier]
                or record['expectedTargetMemberIds'] != [base_group]
                or groups.get(identifier, set()) != {identifier}
                or groups.get(sector_id, set()) != {sector_id} or groups.get(base_group, set())
                or point_id in indices or len(source_ids) != 3):
            raise ValueError('Split settlement distinct/source/target groups changed')
        expected_before = [
            {'id': sector_id, 'memberIds': sorted([sector_id, identifier]), 'canonicalName': base['nomAr'], 'manualSource': target},
            {'id': base_group, 'memberIds': [base_group], 'canonicalName': base['nomAr'], 'manualSource': target}]
        expected_after = [
            {'id': sector_id, 'memberIds': [sector_id], 'canonicalName': base['nomAr'], 'manualSource': target},
            {'id': base_group, 'memberIds': sorted([base_group, identifier]), 'canonicalName': base['nomAr'], 'manualSource': target}]
        if (proposal['beforeGroups'] != expected_before or proposal['afterGroups'] != expected_after
                or proposal['exactMetadataChanges'] != [{'id': identifier, 'field': 'pickerGroupId', 'before': sector_id, 'after': base_group}]
                or wrapper['sourceFacts']['sha256'] != review['sourceFacts']['sha256']
                or wrapper['officialRegistry']['sha256'] != facts['officialRegistry']['sha256']):
            raise ValueError('Split settlement reviewed transition or primary source binding changed')
        aggregate_review_file(directory, row['requiredDistinctPair']['reviewEvidence'], 'distinct town and imada')
        protected_ids = source_ids | {base_group}

        def references(value):
            if isinstance(value, dict):
                return any(references(item) for item in value.values())
            if isinstance(value, list):
                return any(references(item) for item in value)
            return isinstance(value, str) and value in protected_ids

        others = [r for r in manifest['records'] if r is not record]
        others += manifest.get('localRecords', []) + manifest.get('cityDisplayAssociations', [])
        others += [pair for pair in manifest.get('distinctPairs', []) if pair != active_pair]
        if any(references(other) for other in others):
            raise ValueError('Split settlement city, sector or supporting point is used by another review')
        if sorted(r['sha256'] for r in wrapper['primaryCorroboration']) != sorted(r['sha256'] for r in review['primaryCorroboration']):
            raise ValueError('Split settlement primary corroboration inventory changed')
        for reference in wrapper['primaryCorroboration']:
            aggregate_review_file(directory, reference, 'split settlement primary corroboration')
        raw_facts = {r['id']: r for r in facts['records']}
        if len(raw_facts) != len(facts['records']) or not source_ids <= set(raw_facts):
            raise ValueError('Incomplete split settlement source-fact inventory')
        assertions = {r['id']: r for r in row['sourceAssertions']}
        if len(assertions) != len(row['sourceAssertions']) or not source_ids <= set(assertions):
            raise ValueError('Incomplete split settlement source assertions')
        keys = {clean(value).casefold() for value in row['peerNameValues']}
        if base['nomAr'].casefold() not in keys:
            raise ValueError('Missing split settlement Arabic source-name scope')
        peers = set(row['matchingExtractedSourceIds'])
        if len(peers) != len(row['matchingExtractedSourceIds']) or not peers <= set(assertions):
            raise ValueError('Repeated split settlement source-name peers')
        for label, sources in (('original', original_sources), ('effective', effective_sources)):
            actual_peers = {i for i, obj in sources.items()
                            if keys & {clean(value).casefold() for value in names(obj['tags'])}}
            if actual_peers != peers:
                raise ValueError('Split settlement competing source inventory changed')
            for source_id, assertion in assertions.items():
                accepted_effective_sector = lineage is not None and label == 'effective' and source_id == sector_id
                phase_assertion = lineage['states'][source_id]['effective'] if accepted_effective_sector else assertion
                phase_provider = (phase_assertion['sourceId'] if accepted_effective_sector
                                  else None if label == 'original' else 'osm')
                source = sources.get(source_id)
                if source is None:
                    raise ValueError('Missing split settlement source')
                geometry = source.get('shape')
                if geometry is None:
                    geometry = source.get('point')
                if (geometry is None or source.get('sourceId') != phase_provider
                        or source['kind'] != phase_assertion['kind'] or source['tags'] != phase_assertion['tags']
                        or aggregate_geometry_sha256(geometry) != phase_assertion['geometrySha256']
                        or curation.get(source_id) is not None):
                    raise ValueError('Split settlement exact source tags, geometry or curation changed')
                if source_id in source_ids and not accepted_effective_sector:
                    fact = raw_facts[source_id]
                    if (fact['kind'] != source['kind'] or fact['tags'] != source['tags']
                            or fact['expectedCuration'] is not None
                            or fact['geometrySha256'] != assertion['geometrySha256']
                            or aggregate_geometry_sha256(shape(fact['geometry'])) != assertion['geometrySha256']):
                        raise ValueError('Split settlement assertion differs from the frozen original source')
        protected = {}
        for source_id in (identifier, sector_id):
            index = indices[source_id]
            feature, fact = features[index], raw_facts[source_id]
            metadata = {k: v for k, v in fact['currentMetadata'].items() if k not in {'pickerGroupId', 'offset', 'length'}}
            expected_provider, packed_sha = 'osm', fact['packedGeometrySha256']
            if lineage is not None and source_id == sector_id:
                projection = lineage['sectorProjection']
                metadata = {k: v for k, v in projection['metadata'].items() if k != 'pickerGroupId'}
                expected_provider = lineage['states'][sector_id]['effective']['sourceId']
                packed_sha = projection['packedGeometrySha256']
            if (index >= len(geometries)
                    or {k: v for k, v in feature.items() if k not in {'pickerGroupId', 'offset', 'length'}} != metadata
                    or feature['governorateId'] != governor['id'] or feature['delegationId'] != target
                    or feature['sourceId'] != expected_provider or feature['hasBoundary'] is not True
                    or feature['name'] != base['nomAr'] or fact['currentMetadata']['pickerGroupId'] != sector_id
                    or hashlib.sha256(packed_geometry_bytes(geometries[index])).hexdigest() != packed_sha):
                raise ValueError('Split settlement raw choice or packed footprint changed')
            protected[source_id] = metadata
        residential, sector, node = (effective_sources[i] for i in (identifier, sector_id, point_id))
        if (residential['kind'] != 'residential' or sector['kind'] != 'sector'
                or node['kind'] not in ('town', 'village') or node.get('shape') is not None
                or node['kind'] != proposal['sourceIdentity']['sourcePointPlace']
                or node['tags'].get('place') != node['kind']
                or any(source['tags'].get('name:ar') != base['nomAr'] for source in (residential, sector, node))
                or raw_facts[point_id]['absentFromCurrentRawCatalog'] is not True):
            raise ValueError('Split settlement typed point or exact Arabic identity changed')
        official = proposal['officialSectorIdentity']
        code = official['sectorCode']
        if ([r for r in registry['sectors'] if r['sectorCode'] == code] != [official]
                or sector['tags'].get('ref:tn:codegeo') != code
                or official['governorateAr'] != governor['nomAr'] or official['sectorAr'] != base['nomAr']
                or [i for i, s in effective_sources.items() if s['kind'] == 'sector'
                    and s['tags'].get('ref:tn:codegeo') == code] != [sector_id]):
            raise ValueError('Split settlement independently coded imada identity changed')
        point, base_point = node['point'], Point(base['lng'], base['lat'])
        context_id = row['governorSourceId']
        for sources in (original_sources, effective_sources):
            context, city_shape, imada = (sources[i]['shape'] for i in (context_id, identifier, sector_id))
            if (sources[context_id]['kind'] != 'governorate'
                    or sources[context_id]['tags'].get('name:ar') != 'ولاية ' + governor['nomAr']
                    or not context.covers(city_shape) or not context.contains(point) or not context.contains(base_point)
                    or not city_shape.contains(point) or not city_shape.contains(base_point)
                    or {i for i in peers if sources[i]['kind'] == 'residential'} != {identifier}
                    or {i for i in peers if sources[i].get('point') is not None
                        and context.contains(sources[i]['point'])} != {point_id}):
                raise ValueError('Split settlement containing context or unique source point changed')
            claims = proposal['geometryFactsNotBoundaryCertification']['sector']
            if lineage is not None:
                phase = 'original' if sources is original_sources else 'effective'
                phase_claims = lineage['sectorContainment'][phase]
                claims = {'originalContainsBase': phase_claims['containsBase'],
                          'originalContainsSourceSettlementPoint': phase_claims['containsSourceSettlementPoint']}
            if (imada.covers(base_point) != claims['originalContainsBase']
                    or imada.covers(point) != claims['originalContainsSourceSettlementPoint']):
                raise ValueError('Split settlement reviewed imada relation changed')
        city_packed, imada_packed = (geometries[indices[i]] for i in (identifier, sector_id))
        claims = proposal['geometryFactsNotBoundaryCertification']['sector']
        if lineage is not None:
            claims = lineage['sectorContainment']['packed']
        if (not city_packed.contains(point) or not city_packed.contains(base_point)
                or imada_packed.covers(base_point) != claims['containsBase']
                or imada_packed.covers(point) != claims['containsSourceSettlementPoint']):
            raise ValueError('Split settlement packed containment changed')
        for obj in (features[indices[identifier]], features[indices[sector_id]], base,
                    {'lat': point.y, 'lng': point.x}):
            if min(timetables, key=lambda d: (distance(obj['lat'], obj['lng'], d), d['id']))['id'] != target:
                raise ValueError('Split settlement references no longer share the reviewed timetable')
        return {'id': identifier, 'pickerGroupId': base_group, 'reviewEvidence': identity['reviewEvidence'],
                'finalGroups': proposal['afterGroups'], 'protectedMetadata': protected,
                'preservedAbsorbedPointId': point_id, 'exclusiveSourceIds': sorted(source_ids),
                **({'acceptedBoundaryLineage': lineage['reference'],
                    'acceptedBoundaryAbsorption': lineage['absorption']} if lineage is not None else {})}
    except (OSError, UnicodeError, ValueError, KeyError, TypeError, AttributeError, IndexError) as error:
        raise ValueError('Missing, malformed or changed reviewed town/sector split evidence') from error

def validate_settlement_retained_display_reference(option, manifest_path, record, proof,
                                                    features, indices, geometries,
                                                    original_sources, effective_sources,
                                                    source_sha256, base, governor, timetables, curation):
    """Bind a reviewed place identity to a separately evidenced prayer reference.

    This option preserves a complete existing polygon group and its absorbed
    settlement witness. It permits only the pinned primary spelling pair. An
    exact singleton-sector curation may select that existing witness for manual
    use; otherwise each member retains its unchanged representative.
    """
    method = 'reviewed_settlement_retained_display_reference'
    directory = Path(manifest_path).parent
    identifier, target = record['id'], base['id']
    member_ids = set(record['expectedExistingMemberIds'])
    manual_mode = any('requiredCurations' in obj for obj in (record, proof))
    base_group = f'delegation:{target}'
    if (not isinstance(option, dict) or set(option) != {'schemaVersion', 'method', 'reviewEvidence'}
            or type(option['schemaVersion']) is not int or option['schemaVersion'] != 1
            or option['method'] != method or proof.get('retainedDisplayReference') != option
            or any('reviewedSpellingEquivalence' in obj for obj in (record, proof))
            or record['expectedTargetMemberIds'] != [base_group]):
        raise ValueError('Invalid reviewed settlement retained-display option')
    review = json.loads(aggregate_review_file(directory, option['reviewEvidence'], 'settlement retained-display review'))
    physical_reference = 'settlementPrayerCorrection' in review
    correction_key = 'settlementPrayerCorrection' if physical_reference else 'publishedPrayerCorrection'
    keys = {'schemaVersion', 'method', 'sourceSha256', 'id', 'targetDelegationId', 'governorateId',
            'baseCurrent', 'baselineMetadataSha256', 'exactRawFieldChanges', 'recordCoreSha256',
            'eligibleProofCoreSha256', 'sourceFacts', 'independentSourceReview', correction_key,
            'witness', 'sources', 'namePeerIds', 'primaryNames', 'primaryEvidence', 'contextIds',
            'officialRegistry', 'officialSectorRecord', 'protectedMetadata', 'beforeGroups', 'finalGroups',
            'memberPrayerSources', 'manualSelection', 'qualifications'}
    name_mode = 'nameCuration' in review
    if name_mode:
        keys.add('nameCuration')
    qualifications = {('prayerCoordinatesAreNotDisplayGeometry' if physical_reference
                       else 'publishedCoordinatesAreNotDisplayGeometry'): True,
                      'sourceWitnessRemainsAbsorbed': True,
                      'memberPolygonsAreNotEquivalent': True, 'administrativeBoundaryNotCertified': True,
                      'manualPrayerSourceUsesRepresentative': True}
    def core_hash(value):
        return hashlib.sha256(json.dumps({key: item for key, item in value.items()
            if key != 'retainedDisplayReference'}, ensure_ascii=False, sort_keys=True,
            separators=(',', ':')).encode('utf-8')).hexdigest()
    if (not isinstance(review, dict) or set(review) != keys or type(review['schemaVersion']) is not int
            or review['schemaVersion'] != 1 or review['method'] != method
            or (name_mode and not manual_mode)
            or review['sourceSha256'] != source_sha256 or review['id'] != identifier
            or type(review['targetDelegationId']) is not int or review['targetDelegationId'] != target
            or type(review['governorateId']) is not int or review['governorateId'] != governor['id']
            or review['baseCurrent'] != base or review['qualifications'] != qualifications
            or review['recordCoreSha256'] != core_hash(record) or review['eligibleProofCoreSha256'] != core_hash(proof)
            or not isinstance(review['baselineMetadataSha256'], str)
            or re.fullmatch(r'[0-9a-f]{64}', review['baselineMetadataSha256']) is None):
        raise ValueError('Reviewed settlement proof scope or approved record changed')
    aggregate_review_file(directory, review['sourceFacts'], 'settlement source facts')
    independent = json.loads(aggregate_review_file(directory, review['independentSourceReview'], 'independent settlement source review'))
    changes = review['exactRawFieldChanges']
    manual_fields = {'lat', 'lng', 'manualPointId'} if manual_mode else set()
    name_fields = {'name', 'aliases', 'parentName', 'contextAliases'} if name_mode else set()
    if (independent.get('status') != 'PASS' or independent.get('sourceSha256') != source_sha256
            or independent.get('baselineMetadataSha256') != review['baselineMetadataSha256']
            or not any(row.get('sha256') == review['sourceFacts']['sha256'] for row in independent.get('sourceReports', []))
            or independent.get('exactProposedRawFieldChanges') != changes
            or not isinstance(changes, list) or not changes
            or any(not isinstance(row, dict) or set(row) != {'id', 'field', 'before', 'after'}
                   or row['id'] not in indices or row['field'] not in {'pickerGroupId', 'delegationId'} | manual_fields | name_fields
                   or row['before'] == row['after'] for row in changes)
            or len({(row['id'], row['field']) for row in changes}) != len(changes)
            or {row['id'] for row in changes if row['field'] == 'pickerGroupId'} != member_ids
            or any((row['before'] != identifier or row['after'] != base_group) if row['field'] == 'pickerGroupId'
                   else features[indices[row['id']]].get(row['field']) != row['after'] for row in changes)):
        raise ValueError('Independent settlement source review or exact changes differ')
    coordinates = json.loads(display_identity_coordinate_bytes(directory))
    corrections = [row for row in coordinates['corrections'] if row.get('delegationId') == target]
    if (len(corrections) != 1 or corrections[0] != review[correction_key]
            or corrections[0].get('expectedGovernorateId') != governor['id']
            or corrections[0].get('expectedNames') != {key: base[key] for key in ('nomAr', 'nomFr', 'nomEn')}
            or corrections[0].get('proposed') != {key: base[key] for key in ('lat', 'lng')}):
        raise ValueError('Independent settlement prayer reference changed')
    if physical_reference:
        if 'referenceKind' in corrections[0]:
            raise ValueError('Physical settlement prayer reference must not declare a published reference kind')
    else:
        if (corrections[0].get('referenceKind') != 'inm_published_reference'
                or corrections[0].get('proposed') != {key: base[key] for key in ('lat', 'lng')}):
            raise ValueError('Independent published settlement prayer reference changed')
        validate_inm_published_reference(corrections[0], governor, base, directory)
    correction_object = corrections[0]
    primary = review['primaryNames']
    if (not isinstance(primary, dict) or set(primary) != {'base', 'locality', 'evidence'}
            or primary['base'] != base['nomAr'] or primary['locality'] != proof['name']
            or not picker_review_name_key(primary['locality']) or not isinstance(primary['evidence'], list)
            or not isinstance(review['primaryEvidence'], list) or not review['primaryEvidence']):
        raise ValueError('Missing exact reviewed primary place names')
    paired_primary = False
    for reference in primary['evidence'] + review['primaryEvidence']:
        content = aggregate_review_file(directory, reference, 'primary inhabited settlement evidence').decode('utf-8')
        required = reference.get('requiredText')
        if (not isinstance(reference.get('url'), str) or not reference['url'].startswith('https://')
                or not isinstance(required, list) or not required
                or any(not isinstance(text, str) or not text or text not in content for text in required)):
            raise ValueError('Primary settlement name evidence changed')
        if reference in primary['evidence'] and all(name in required for name in (primary['base'], primary['locality'])):
            paired_primary = True
    if picker_review_name_key(primary['base']) != picker_review_name_key(primary['locality']) and not paired_primary:
        raise ValueError('Different primary spellings need their exact reviewed primary-source pair')
    witness = review['witness']
    if (not isinstance(witness, dict) or set(witness) != {'id', 'kind', 'tags', 'lat', 'lng', 'publicNode', 'exactPublicElement'}
            or witness['id'] in indices or witness['kind'] not in ('village', 'town')
            or any(type(witness[key]) not in (int, float) or not math.isfinite(witness[key]) for key in ('lat', 'lng'))):
        raise ValueError('Invalid absorbed settlement witness')
    point_id = witness['id']
    point = Point(witness['lng'], witness['lat'])
    node_document = json.loads(aggregate_review_file(directory, witness['publicNode'], 'public settlement witness'))
    node = witness['exactPublicElement']
    if (node_document.get('elements') != [node] or node.get('type') != 'node'
            or point_id != f"osm:node:{node.get('id')}" or node.get('tags') != witness['tags']
            or node.get('lat') != point.y or node.get('lon') != point.x
            or witness['tags'].get('place') != witness['kind']
            or picker_review_name_key(names(witness['tags'])[0]) != picker_review_name_key(primary['locality'])
            or witness['publicNode'].get('url') != f"https://api.openstreetmap.org/api/0.6/node/{node['id']}.json"):
        raise ValueError('Exact public settlement witness changed')
    if physical_reference:
        osm = correction_object.get('osm')
        evidence = correction_object.get('osmEvidence')
        if (not isinstance(osm, dict)
                or osm.get('id') != witness['id']
                or osm.get('tags') != witness['tags']
                or type(node.get('version')) is not int or not node.get('timestamp') or not node.get('changeset')
                or osm.get('version') != node.get('version')
                or osm.get('timestamp') != node.get('timestamp')
                or osm.get('changeset') != node.get('changeset')
                or correction_object.get('proposed') != {'lat': witness['lat'], 'lng': witness['lng']}
                or not isinstance(evidence, dict)
                or evidence.get('liveResponse') != witness['publicNode']):
            raise ValueError('Physical settlement prayer reference does not match the validated public node')
    manual_rule = None
    if manual_mode:
        prerequisites = record.get('requiredCurations')
        if (not isinstance(prerequisites, list) or len(prerequisites) != 1
                or proof.get('requiredCurations') != prerequisites
                or member_ids != {identifier}):
            raise ValueError('A retained manual witness requires one complete singleton sector')
        manual_rule = prerequisites[0]
        source = original_sources.get(identifier)
        allowed_rule_keys = {'id', 'expectedKind', 'expectedTags', 'action', 'reason',
                             'evidence', 'nameTags', 'manualPointId'}
        if (not isinstance(manual_rule, dict) or set(manual_rule) != allowed_rule_keys
                or source is None or source['kind'] != 'sector'
                or manual_rule.get('id') != identifier or manual_rule.get('expectedKind') != 'sector'
                or manual_rule.get('action') != 'name_tags'
                or manual_rule.get('expectedTags') != source['tags']
                or (not name_mode and manual_rule.get('nameTags') != {key: value for key, value in source['tags'].items()
                                                                        if is_current_name_tag(key)})
                or manual_rule.get('manualPointId') != point_id
                or curation.get(identifier) != manual_rule):
            raise ValueError('Retained manual witness must preserve all source names and geometry')
        current = features[indices[identifier]]
        if indices[identifier] >= len(geometries) or current.get('kind') != 'sector':
            raise ValueError('Retained manual witness lost its existing sector polygon')
        # These are the same precision-aware geometries used by build(). Do not
        # substitute a decoded geometry lacking its quantization precision model.
        ordinary_point = geometries[indices[identifier]].representative_point()
        expected_manual_changes = {
            (identifier, 'lat'): (round(ordinary_point.y, 7), round(point.y, 7)),
            (identifier, 'lng'): (round(ordinary_point.x, 7), round(point.x, 7)),
            (identifier, 'manualPointId'): (None, point_id)}
        actual_manual_changes = {(row['id'], row['field']): (row['before'], row['after'])
                                 for row in changes if row['field'] in manual_fields}
        if (actual_manual_changes != expected_manual_changes
                or current.get('manualPointId') != point_id
                or current['lat'] != round(point.y, 7) or current['lng'] != round(point.x, 7)):
            raise ValueError('Retained manual witness exact before/after coordinates changed')
    contexts = review['contextIds']
    if not isinstance(contexts, dict) or set(contexts) != {'governor', 'delegation'}:
        raise ValueError('Missing settlement administrative source context')
    source_rows = review['sources']
    source_ids = member_ids | {point_id} | set(contexts.values())
    if (not isinstance(source_rows, list) or len(source_rows) != len(source_ids)
            or any(not isinstance(row, dict) or set(row) != {'id', 'kind', 'tags', 'geometrySha256', 'expectedCuration'}
                   for row in source_rows) or {row['id'] for row in source_rows} != source_ids):
        raise ValueError('Reviewed settlement source closure changed')
    peer_keys = {norm(value) for value in [base['nomAr'], base['nomFr'], base['nomEn'], primary['locality']]
                 + names(witness['tags']) + [value for i in member_ids for value in names(original_sources[i]['tags'])]}
    registry = json.loads(aggregate_review_file(directory, review['officialRegistry'], 'settlement official sector registry'))
    official = review['officialSectorRecord']
    if (not isinstance(official, dict) or [row for row in registry['sectors'] if row.get('sectorCode') == official.get('sectorCode')] != [official]
            or official.get('sectorAr') != primary['locality'] or official.get('delegationAr') != primary['locality']
            or picker_review_name_key(official.get('governorateAr', '')) != picker_review_name_key(governor['nomAr'])):
        raise ValueError('Reviewed settlement official current identity changed')
    identities = record['identityEvidence']
    if (identities != proof['identityEvidence'] or len(identities) != 2
            or {(row.get('id'), row.get('method')) for row in identities}
               != {(identifier, 'current_coded_sector'), (contexts['delegation'], 'current_coded_namesake_administrative_parent')}
            or any(row.get('accepted') is not True or row.get('currentOfficialRecord') != (
                official if row['id'] == identifier else {key: official[key] for key in (
                    'governorateCode', 'governorateAr', 'governorateFr', 'delegationCode', 'delegationAr', 'delegationFr')}) for row in identities)):
        raise ValueError('Reviewed settlement ordinary sector and parent evidence changed')
    expected_curations = {identifier: manual_rule} if manual_rule is not None else {}
    name_replacements, name_before = {}, {}
    if name_mode:
        naming = review['nameCuration']
        assertions = {row['id']: row for row in source_rows}
        if (not isinstance(naming, dict) or set(naming) != {'curationIds', 'beforeMetadata'}
                or not isinstance(naming['curationIds'], list)
                or naming['curationIds'] != sorted(set(naming['curationIds']))
                or identifier not in naming['curationIds']
                or not set(naming['curationIds']) <= {identifier, contexts['delegation']}
                or not isinstance(naming['beforeMetadata'], dict)):
            raise ValueError('Invalid exact retained-place naming scope')
        for source_id in naming['curationIds']:
            assertion = assertions[source_id]
            rule = assertion['expectedCuration']
            sector = source_id == identifier
            canonical = primary['locality'] if sector else 'معتمدية ' + official['delegationAr']
            original_names = {key: value for key, value in assertion['tags'].items()
                              if is_current_name_tag(key)}
            rule_keys = {'id', 'expectedKind', 'expectedTags', 'action', 'reason', 'evidence', 'nameTags'}
            if sector:
                rule_keys.add('manualPointId')
            if (not isinstance(rule, dict) or set(rule) != rule_keys
                    or rule.get('id') != source_id or rule.get('action') != 'name_tags'
                    or rule.get('expectedKind') != ('sector' if sector else 'delegation')
                    or rule.get('expectedTags') != assertion['tags']
                    or curation.get(source_id) != rule
                    or (sector and (rule != manual_rule or rule.get('manualPointId') != point_id))
                    or not isinstance(rule.get('nameTags'), dict)
                    or not set(rule['nameTags']) <= set(original_names) | {'name:ar', 'alt_name:ar'}
                    or rule['nameTags'].get('name') != canonical or rule['nameTags'].get('name:ar') != canonical
                    or set(names(rule['nameTags'])) != set(names(assertion['tags'])) | {canonical}
                    or any(rule['nameTags'].get(key) != value for key, value in original_names.items()
                           if key not in {'name', 'name:ar', 'alt_name:ar'})):
                raise ValueError('Retained-place naming must preserve exact prior names and official identity')
            expected_curations[source_id] = rule
            previous = names(assertion['tags'])[0]
            if previous != canonical:
                name_replacements[previous] = canonical
        name_before = naming['beforeMetadata']
        expected_name_ids = {row['id'] for row in changes if row['field'] in name_fields} | {identifier}
        if (not name_replacements or set(name_before) != expected_name_ids
                or not expected_name_ids <= set(indices)
                or any(row['id'] != identifier for row in changes if row['field'] in {'name', 'aliases'})):
            raise ValueError('Retained-place naming raw inventory changed')
    for sources, provider in ((original_sources, None), (effective_sources, 'osm')):
        if (review['namePeerIds'] != sorted(i for i, source in sources.items()
                if peer_keys & {norm(value) for value in names(source['tags'])})
                or not set(review['namePeerIds']) <= source_ids):
            raise ValueError('Settlement acquired an unreviewed source name peer')
        for row in source_rows:
            source = sources.get(row['id'])
            geometry = None if source is None else source.get('shape')
            if geometry is None and source is not None:
                geometry = source.get('point')
            if (source is None or geometry is None or geometry.is_empty or source.get('sourceId') != provider
                    or source['kind'] != row['kind'] or source['tags'] != (
                        {**{key: value for key, value in row['tags'].items() if not is_current_name_tag(key)},
                         **expected_curations[row['id']]['nameTags']}
                        if provider is not None and row['id'] in expected_curations else row['tags'])
                    or aggregate_geometry_sha256(geometry) != row['geometrySha256']
                    or row['expectedCuration'] != expected_curations.get(row['id'])
                    or curation.get(row['id']) != expected_curations.get(row['id'])):
                raise ValueError('Reviewed original/effective settlement source or curation changed')
        source_point = sources[point_id]
        if (source_point.get('shape') is not None or source_point['point'].geom_type != 'Point'
                or not source_point['point'].equals_exact(point, 0)
                or source_point['tags'] != witness['tags'] or source_point['kind'] != witness['kind']
                or any(sources[i].get('shape') is None or not sources[i]['shape'].covers(point) for i in member_ids)):
            raise ValueError('Reviewed absorbed settlement witness or member containment changed')
        parent, region = sources[contexts['delegation']], sources[contexts['governor']]
        if (parent['kind'] != 'delegation' or region['kind'] != 'governorate'
                or parent['tags'].get('ref:tn:codegeo') != official['delegationCode']
                or region['tags'].get('ref:tn:codegeo') != official['governorateCode']
                or not parent['shape'].covers(point) or not region['shape'].covers(point)
                or not all(parent['shape'].covers(sources[i]['shape']) for i in member_ids)):
            raise ValueError('Reviewed settlement administrative context changed')
    if any(indices[i] >= len(geometries) or not geometries[indices[i]].covers(point) for i in member_ids):
        raise ValueError('Packed settlement member no longer contains authentic witness')
    name_after = {}
    if name_mode:
        # Inherited labels are metadata effects, not additional display-group
        # members. Their own reviewed groups may be applied at a later stage.
        for raw_id, before_metadata in name_before.items():
            current = features[indices[raw_id]]
            after_metadata = {key: value for key, value in current.items()
                              if key not in {'pickerGroupId', 'offset', 'length'}}
            if (not isinstance(before_metadata, dict) or before_metadata.get('id') != raw_id
                    or any(key in before_metadata for key in ('pickerGroupId', 'offset', 'length'))
                    or before_metadata.get('governorateId') != governor['id']
                    or not isinstance(before_metadata.get('contextAliases'), list)
                    or after_metadata.get('parentName') != name_replacements.get(
                        before_metadata.get('parentName'), before_metadata.get('parentName'))
                    or after_metadata.get('contextAliases') != sorted(set(before_metadata['contextAliases'])
                        | {new for old, new in name_replacements.items()
                           if old in before_metadata['contextAliases']})):
                raise ValueError('Retained-place inherited naming changed beyond exact source corrections')
            derived = {(raw_id, key): (before_metadata.get(key), after_metadata.get(key))
                       for key in set(before_metadata) | set(after_metadata)
                       if before_metadata.get(key) != after_metadata.get(key)}
            approved = {(row['id'], row['field']): (row['before'], row['after'])
                        for row in changes if row['id'] == raw_id and row['field'] != 'pickerGroupId'}
            if derived != approved:
                raise ValueError('Retained-place full naming metadata differs from independent exact changes')
            if raw_id == identifier:
                if (before_metadata.get('name') != names(original_sources[identifier]['tags'])[0]
                        or not isinstance(before_metadata.get('aliases'), list)
                        or set([before_metadata['name'], *before_metadata['aliases']])
                           != set([current['name'], *current.get('aliases', [])])):
                    raise ValueError('Retained-place corrected primary lost an existing searchable name')
            name_after[raw_id] = after_metadata
        inherited_ids = {row['id'] for row in features if row['governorateId'] == governor['id']
                         and any(old in row.get('contextAliases', []) for old in name_replacements)}
        if not inherited_ids <= set(name_before):
            raise ValueError('Retained-place source correction gained an unreviewed inherited label')
    protected = review['protectedMetadata']
    protected_ids = set(protected) if isinstance(protected, dict) else set()
    if not member_ids <= protected_ids or not protected_ids <= set(indices):
        raise ValueError('Missing reviewed settlement protected raw rows')
    expected_metadata = {i: {key: value for key, value in features[indices[i]].items()
                             if key not in {'pickerGroupId', 'offset', 'length'}} for i in protected_ids}
    if protected != expected_metadata:
        raise ValueError('Reviewed settlement preserved raw metadata changed')
    def scoped_groups(after):
        def group_for(row):
            return base_group if after and row['id'] in member_ids else row['pickerGroupId']
        result = []
        for group_id in sorted({group_for(features[indices[i]]) for i in protected_ids} | {base_group}):
            members = {row['id'] for row in features if group_for(row) == group_id}
            if not members <= protected_ids:
                raise ValueError('Reviewed settlement protected group gained an unreviewed member')
            if group_id.startswith('delegation:'):
                members.add(group_id)
            result.append({'id': group_id, 'memberIds': sorted(members)})
        return result
    before, after = scoped_groups(False), scoped_groups(True)
    nearest = {i: min(timetables, key=lambda row: (distance(features[indices[i]]['lat'], features[indices[i]]['lng'], row), row['id']))['id']
               for i in member_ids}
    ranks = {'city': 0, 'town': 0, 'village': 1, 'hamlet': 2, 'suburb': 3, 'neighbourhood': 3,
             'quarter': 3, 'city_district': 3, 'residential': 4, 'sector': 5, 'municipality': 6}
    representative_id = min(member_ids, key=lambda i: (ranks.get(features[indices[i]]['kind'], 7), i))
    representative = features[indices[representative_id]]
    manual = {'representativeId': representative_id, 'lat': representative['lat'], 'lng': representative['lng'],
              'expectedNearestSourceId': nearest[representative_id]}
    if (review['beforeGroups'] != before or review['finalGroups'] != after
            or review['memberPrayerSources'] != nearest or review['manualSelection'] != manual
            or any(features[indices[i]]['delegationId'] != nearest[i] for i in member_ids)):
        raise ValueError('Reviewed settlement complete group or independent nearest prayer source changed')
    expected_metadata.update(name_after)
    return point, {'id': identifier, 'identityMethod': method, 'targetDelegationId': target,
        'displayReference': {'id': point_id, 'lat': point.y, 'lng': point.x},
        ('settlementPrayerReference' if physical_reference else 'publishedPrayerReference'):
            {key: base[key] for key in ('lat', 'lng')}, 'manualSelection': manual,
        'reviewedPrimaryNameKey': picker_review_name_key(primary['locality']), 'memberPrayerSources': nearest,
        'finalGroups': after, 'protectedMetadata': expected_metadata,
        'preservedNonCatalogPlace': {'id': point_id, 'role': f"absorbed_extracted_{witness['kind']}"}}



def validate_hichria_retained_display_reference(option, manifest_path, record, proof,
                                                features, indices, geometries,
                                                original_sources, effective_sources,
                                                source_sha256, base, governor):
    """Keep the reviewed Hichria display separate from its prayer-selection reference.

    This exact Hichria contract preserves the existing administrative-transfer
    checks. It cannot turn another name or nearby point into a display identity.
    """
    method = 'reviewed_hichria_retained_display_reference'
    identifier, target = 'osm:relation:7169598', 1522
    display = {'id': identifier, 'lat': 34.895, 'lng': 9.40957}
    published = {'lat': 34.829, 'lng': 9.376}
    northern = {'id': 'osm:node:7938334046', 'kind': 'village',
                'tags': {'name': 'الهيشرية', 'name:ar': 'الهيشرية', 'name:en': 'El Hichria',
                         'name:fr': 'El Hichria', 'place': 'village'},
                'lat': 34.8742313, 'lng': 9.435056}
    settlement = {'lat': northern['lat'], 'lng': northern['lng']}
    directory = Path(manifest_path).parent
    if (not isinstance(option, dict)
            or set(option) != {'schemaVersion', 'method', 'reviewEvidence'}
            or type(option['schemaVersion']) is not int or option['schemaVersion'] != 1
            or option['method'] != method or proof.get('retainedDisplayReference') != option
            or record.get('id') != identifier or record.get('targetDelegationId') != target
            or type(record.get('targetDelegationId')) is not int
            or base['id'] != target or governor['id'] != 355
            or {key: base.get(key) for key in published} not in (published, settlement)
            or {key: base.get(key) for key in ('nomAr', 'nomFr', 'nomEn')}
               != {'nomAr': 'الهيشرية', 'nomFr': 'Hichria', 'nomEn': 'Hichria'}):
        raise ValueError('Invalid exact Hichria retained-display contract')
    reference = option['reviewEvidence']
    if not isinstance(reference, dict) or set(reference) != {'file', 'sha256'}:
        raise ValueError('Missing Hichria retained-display proof')
    review = json.loads(aggregate_review_file(directory, reference, 'Hichria retained-display proof'))
    physical_reference = 'settlementPrayerReference' in review
    reference_key = 'settlementPrayerReference' if physical_reference else 'publishedPrayerReference'
    reference_point = settlement if physical_reference else published
    keys = {'schemaVersion', 'method', 'sourceSha256', 'id', 'targetDelegationId',
            'governorateId', 'historicalReview', reference_key,
            'displayReference', 'absorbedNorthernVillage', 'manualSelection',
            'protectedMetadata', 'finalGroups', 'qualifications'}
    qualifications = {'publishedPointOutsideDisplaySector': True,
                      'publishedCoordinatesAreNotDisplayGeometry': True,
                      'southernVillageIdentityNotMerged': True,
                      'sectorBoundaryNotCertified': True}
    if physical_reference:
        qualifications = {'settlementPointInsideDisplaySector': True,
                          'prayerCoordinatesAreNotDisplayGeometry': True,
                          'southernVillageIdentityNotMerged': True,
                          'sectorBoundaryNotCertified': True}
    manual = {'method': 'nearest_available_at_retained_locality',
              'representativeId': identifier, 'expectedNearestSourceId': target}
    if (not isinstance(review, dict) or set(review) != keys
            or type(review['schemaVersion']) is not int or review['schemaVersion'] != 1
            or review['method'] != method or review['sourceSha256'] != source_sha256
            or review['id'] != identifier or type(review['targetDelegationId']) is not int
            or review['targetDelegationId'] != target or type(review['governorateId']) is not int
            or review['governorateId'] != governor['id'] or review['displayReference'] != display
            or {key: base.get(key) for key in reference_point} != reference_point
            or review['manualSelection'] != manual
            or type(review['manualSelection'].get('expectedNearestSourceId')) is not int
            or review['qualifications'] != qualifications
            or any(value is not True for value in review['qualifications'].values())):
        raise ValueError('Hichria retained-display scope or representative changed')
    historical = review['historicalReview']
    if (not isinstance(historical, dict) or set(historical) != {'file', 'sha256'}
            or historical['sha256'] != 'f670006b560fbc0fcd334b4f5eaaa2df623a9c7ee50b566866ece96a556a5f33'):
        raise ValueError('Missing immutable Hichria administrative-transfer review')
    old_document = json.loads(aggregate_review_file(directory, historical, 'historical Hichria review'))
    old_matches = [item for item in old_document['records'] if item.get('id') == identifier]
    if len(old_matches) != 1 or old_matches[0].get('status') != 'eligible_proposal':
        raise ValueError('Historical Hichria reviewed identity changed')
    # Rebuild the only permitted successor of the old active proof. The old
    # base-point containment remains historical, never relabeled as current.
    expected_proof = json.loads(json.dumps(old_matches[0]))
    expected_proof['basePoint'] = reference_point
    expected_proof['displayReference'] = display
    expected_proof['retainedDisplayReference'] = option
    expected_proof['members'][0].update(originalContainsBasePoint=physical_reference,
        packagedContainsBasePoint=physical_reference, originalContainsDisplayReference=True,
        packagedContainsDisplayReference=True)
    expected_proof['scope'] = ('Retain the reviewed Hichria delegation display at its unchanged sector '
        'representative. Published prayer coordinates are separate and outside this sector; '
        'do not infer southern village identity or certify a delegation boundary.')
    if physical_reference:
        expected_proof['scope'] = 'Retain the reviewed Hichria delegation display at its unchanged sector representative. The original northern village supplies the physical prayer-selection reference; preserve the separate southern village and all sector geometry without boundary certification.'
    if (proof != expected_proof or record.get('identityEvidence') != old_matches[0]['identityEvidence']
            or record.get('expectedExistingMemberIds') != [identifier]
            or record.get('expectedTargetMemberIds') != ['delegation:1522']
            or len(record.get('members', [])) != 1 or record['members'][0].get('id') != identifier):
        raise ValueError('Hichria successor changed the accepted identity or exact group')
    prayer_reference = review[reference_key]
    if (not isinstance(prayer_reference, dict)
            or set(prayer_reference) != {'file', 'sha256', 'expectedCorrection'}
            or prayer_reference['file'] != 'prayer-source-coordinates.json'):
        raise ValueError('Missing independently published Hichria prayer reference')
    coordinates = json.loads(reviewed_prayer_coordinate_reference(directory, prayer_reference, 'Hichria prayer coordinates'))
    corrections = [item for item in coordinates.get('corrections', []) if item.get('delegationId') == target]
    if (len(corrections) != 1 or corrections[0] != prayer_reference['expectedCorrection']
            or corrections[0].get('referenceKind') != (None if physical_reference else 'inm_published_reference')
            or corrections[0].get('expectedGovernorateId') != governor['id']
            or corrections[0].get('expectedNames') != {key: base[key] for key in ('nomAr', 'nomFr', 'nomEn')}
            or corrections[0].get('original') != {'lat': 34.895, 'lng': 9.3918}
            or corrections[0].get('proposed') != reference_point):
        raise ValueError('Hichria published reference correction changed')
    if physical_reference:
        correction = corrections[0]
        osm = correction.get('osm')
        evidence = correction.get('osmEvidence')
        if (not isinstance(osm, dict) or osm.get('id') != northern['id']
                or osm.get('place') != northern['kind'] or osm.get('tags') != northern['tags']
                or not isinstance(evidence, dict) or not isinstance(evidence.get('liveResponse'), dict)):
            raise ValueError('Hichria physical reference lost its original village witness')
        live_reference = evidence['liveResponse']
        if live_reference.get('url') != 'https://api.openstreetmap.org/api/0.6/node/7938334046.json':
            raise ValueError('Hichria physical reference node URL changed')
        response = json.loads(aggregate_review_file(directory, live_reference, 'Hichria village response'))
        elements = response.get('elements', [])
        if len(elements) != 1:
            raise ValueError('Hichria village response must contain one exact element')
        node = elements[0]
        if (node.get('type') != 'node' or node.get('id') != 7938334046
                or node.get('lat') != northern['lat'] or node.get('lon') != northern['lng']
                or node.get('tags') != northern['tags'] or type(node.get('version')) is not int
                or not node.get('timestamp') or not node.get('changeset')
                or any(osm.get(key) != node.get(key) for key in ('version', 'timestamp', 'changeset'))):
            raise ValueError('Hichria physical reference disagrees with its source node')
        superseded = correction.get('supersededPublishedReference')
        if (not isinstance(superseded, dict)
                or set(superseded) != {'priorCoordinateManifest', 'exactCorrection'}):
            raise ValueError('Missing historical Hichria published reference')
        prior_document = json.loads(aggregate_review_file(directory, superseded['priorCoordinateManifest'], 'historical Hichria coordinates'))
        prior_matches = [row for row in prior_document.get('corrections', []) if row.get('delegationId') == target]
        if (len(prior_matches) != 1 or prior_matches[0] != superseded['exactCorrection']
                or prior_matches[0].get('referenceKind') != 'inm_published_reference'
                or prior_matches[0].get('proposed') != published
                or any(prior_matches[0].get(key) != correction.get(key) for key in
                       ('original', 'originalProvenance', 'originalSourceAssetSha256', 'originalSourceAssetEvidence', 'expectedNames', 'expectedGovernorateId'))):
            raise ValueError('Historical Hichria correction provenance changed')
        validate_inm_published_reference(prior_matches[0], governor, {**base, **published}, directory)
    else:
        validate_inm_published_reference(corrections[0], governor, base, directory)
    current = features[indices[identifier]]
    source = effective_sources.get(identifier)
    original = original_sources.get(identifier)
    index = indices[identifier]
    display_point = Point(display['lng'], display['lat'])
    published_point = Point(published['lng'], published['lat'])
    if (original is None or source is None or original['kind'] != 'sector' or source['kind'] != 'sector'
            or current.get('kind') != 'sector' or not current.get('hasBoundary')
            or {key: current.get(key) for key in ('id', 'lat', 'lng')} != display
            or index >= len(geometries)
            or any(item.get('shape') is None or not item['shape'].covers(display_point)
                   or item['shape'].covers(published_point) for item in (original, source))
            or not geometries[index].covers(display_point) or geometries[index].covers(published_point)):
        raise ValueError('Hichria prayer/display point distinction or containment changed')
    if review['absorbedNorthernVillage'] != northern or northern['id'] in indices:
        raise ValueError('Hichria northern village source-only identity changed')
    for sources, receiver in ((original_sources, original), (effective_sources, source)):
        point_source = sources.get(northern['id'])
        if (point_source is None or point_source.get('kind') != northern['kind']
                or point_source.get('tags') != northern['tags'] or point_source.get('shape') is not None
                or point_source.get('point') is None or point_source['point'].geom_type != 'Point'
                or point_source['point'].x != northern['lng'] or point_source['point'].y != northern['lat']
                or not receiver['shape'].covers(point_source['point'])
                or not geometries[index].covers(point_source['point'])):
            raise ValueError('Hichria absorbed northern village or source containment changed')
    protected_ids = {identifier, 'osm:relation:7169594', 'osm:relation:7169555',
                     'osm:node:10006882480', 'osm:node:7938334020', 'osm:relation:7169579'}
    if (not isinstance(review['protectedMetadata'], dict)
            or set(review['protectedMetadata']) != protected_ids
            or not protected_ids <= set(indices)):
        raise ValueError('Missing Hichria preserved raw context or southern locality')
    expected_metadata = {i: {key: value for key, value in features[indices[i]].items()
                             if key not in {'pickerGroupId', 'offset', 'length'}} for i in protected_ids}
    final_by_id = {i: ('delegation:1522' if i == identifier else features[indices[i]]['pickerGroupId'])
                   for i in protected_ids}
    expected_groups = []
    for group_id in sorted(set(final_by_id.values())):
        actual_members = {feature['id'] for feature in features
                          if ('delegation:1522' if feature['id'] == identifier else feature['pickerGroupId']) == group_id}
        if not actual_members <= protected_ids:
            raise ValueError('Hichria protected group acquired an unreviewed member')
        if group_id.startswith('delegation:'):
            actual_members.add(group_id)
        expected_groups.append({'id': group_id, 'memberIds': sorted(actual_members)})
    if review['protectedMetadata'] != expected_metadata or review['finalGroups'] != expected_groups:
        raise ValueError('Hichria retained display changed a preserved raw field or group')
    return display_point, {'id': identifier, 'identityMethod': method,
        'targetDelegationId': target, 'displayReference': display,
        reference_key: reference_point, 'manualSelection': manual,
        ('settlementPointInsideDisplaySector' if physical_reference else 'publishedPointOutsideDisplaySector'): True,
        'finalGroups': expected_groups, 'protectedMetadata': expected_metadata,
        'preservedNonCatalogPlace': {'id': northern['id'], 'role': 'absorbed_extracted_village'}}



def validate_menzel_bouzaiane_spelling_equivalence(option, manifest_path, record, proof,
                                                  features, indices, geometries,
                                                  original_sources, effective_sources,
                                                  source_sha256, base, governor, curation):
    """Validate one accepted spelling relationship without changing either label.

    The source village is the existing settlement reference, not a new raw place.
    All group members and separate local rows retain their independent geometry.
    """
    method = 'reviewed_menzel_bouzaiane_spelling_equivalence'
    identifier, target = 'osm:relation:7169630', 510
    member_ids = {identifier, 'osm:way:170079232'}
    sector_ids = {identifier, 'osm:relation:7169623', 'osm:relation:7169625',
                  'osm:relation:7169626', 'osm:relation:7169627',
                  'osm:relation:7169628', 'osm:relation:7169629'}
    raw_ids = sector_ids | {'osm:way:170079232', 'osm:way:192749364'}
    absent_ids = {'osm:node:7371710998', 'osm:node:7371704678', 'osm:node:8257614460'}
    context_ids = {'osm:relation:7169593', 'osm:relation:1435831'}
    base_name, locality_name = 'منزل بو زيان', 'منزل بوزيان'
    administrative_parent = 'معتمدية منزل بوزيان'
    point_id = 'osm:node:7371710998'
    point = Point(9.4269175, 34.5731138)
    directory = Path(manifest_path).parent
    if (not isinstance(option, dict)
            or set(option) != {'schemaVersion', 'method', 'reviewEvidence'}
            or type(option['schemaVersion']) is not int or option['schemaVersion'] != 1
            or option['method'] != method or proof.get('reviewedSpellingEquivalence') != option
            or 'retainedDisplayReference' in record or 'retainedDisplayReference' in proof
            or record.get('id') != identifier or record.get('targetDelegationId') != target
            or type(record.get('targetDelegationId')) is not int
            or base != {'id': target, 'nomFr': 'Menzel Bouzayane', 'nomAr': base_name,
                        'nomEn': 'Menzel Bouzayane', 'lat': point.y, 'lng': point.x}
            or governor['id'] != 355
            or set(record.get('expectedExistingMemberIds', [])) != member_ids
            or len(record.get('expectedExistingMemberIds', [])) != 2
            or record.get('expectedTargetMemberIds') != ['delegation:510']
            or len(record.get('members', [])) != 2
            or {row.get('id') for row in record['members']} != member_ids):
        raise ValueError('Invalid exact Menzel Bouzaiane spelling relationship')
    reference = option['reviewEvidence']
    if not isinstance(reference, dict) or set(reference) != {'file', 'sha256'}:
        raise ValueError('Missing Menzel Bouzaiane spelling proof')
    review = json.loads(aggregate_review_file(directory, reference, 'Menzel Bouzaiane spelling proof'))
    expected_keys = {'schemaVersion', 'method', 'sourceSha256', 'id', 'targetDelegationId',
                     'governorateId', 'baseSpelling', 'localitySpelling', 'sourceFacts',
                     'originalNodeEvidence', 'officialRegistry', 'officialSectorRecords',
                     'acceptedCoordinateCorrection', 'sourceRecords', 'sourceNamePeerIds',
                     'protectedMetadata', 'packedGeometrySha256', 'expectedBeforeGroups',
                     'expectedAfterGroups', 'sourceOnlyAbsentIds', 'publicContextEvidence',
                     'qualifications'}
    qualifications = {'namesCoordinatesAndGeometriesUnchanged': True,
                      'sectorAndResidentialFootprintsNotEquivalent': True,
                      'googleOutlineIsQualitativeContextOnly': True,
                      'sourceVillageRemainsAbsorbed': True,
                      'noAdministrativeBoundaryCertification': True}
    if (not isinstance(review, dict) or set(review) != expected_keys
            or type(review['schemaVersion']) is not int or review['schemaVersion'] != 1
            or review['method'] != method or review['sourceSha256'] != source_sha256
            or review['id'] != identifier or type(review['targetDelegationId']) is not int
            or review['targetDelegationId'] != target or type(review['governorateId']) is not int
            or review['governorateId'] != governor['id']
            or review['baseSpelling'] != base_name or review['localitySpelling'] != locality_name
            or review['qualifications'] != qualifications
            or any(value is not True for value in review['qualifications'].values())):
        raise ValueError('Menzel Bouzaiane spelling proof scope changed')
    aggregate_review_file(directory, review['sourceFacts'], 'Menzel Bouzaiane finite source facts')
    if not isinstance(review['publicContextEvidence'], list) or not review['publicContextEvidence']:
        raise ValueError('Missing reviewed public Menzel Bouzaiane context')
    for evidence in review['publicContextEvidence']:
        aggregate_review_file(directory, evidence, 'Menzel Bouzaiane public context')
    registry = json.loads(aggregate_review_file(directory, review['officialRegistry'], 'Menzel Bouzaiane sector registry'))
    official = sorted([row for row in registry['sectors'] if row['delegationCode'] == '4357'],
                      key=lambda row: row['sectorCode'])
    if (len(official) != 7 or {row['sectorCode'] for row in official}
            != {'435751', '435752', '435753', '435754', '435755', '435756', '435757'}
            or any(row['governorateCode'] != '43' or row['delegationAr'] != locality_name
                   for row in official) or review['officialSectorRecords'] != official):
        raise ValueError('Menzel Bouzaiane complete official sector identity changed')
    # Existing coordinate corrections are immutable semantic objects. Do not
    # introduce another whole-manifest dependency on unrelated source updates.
    coordinate_document = json.loads(display_identity_coordinate_bytes(directory))
    corrections = [row for row in coordinate_document['corrections'] if row.get('delegationId') == target]
    if (len(corrections) != 1 or corrections[0] != review['acceptedCoordinateCorrection']
            or corrections[0].get('expectedGovernorateId') != governor['id']
            or corrections[0].get('expectedNames') != {key: base[key] for key in ('nomAr', 'nomFr', 'nomEn')}
            or corrections[0].get('proposed') != {'lat': point.y, 'lng': point.x}
            or corrections[0].get('osm', {}).get('id') != point_id
            or corrections[0].get('osm', {}).get('place') != 'village'):
        raise ValueError('Menzel Bouzaiane accepted settlement reference changed')
    node_reference = review['originalNodeEvidence']
    if node_reference.get('sha256') != '49be026dd6719b1261ce12e8929468e7a81c974cfa4076ae90d797f413b25979':
        raise ValueError('Menzel Bouzaiane original node evidence changed')
    node_document = json.loads(aggregate_review_file(directory, node_reference, 'Menzel Bouzaiane original node response'))
    nodes = [row for row in node_document['elements'] if row.get('type') == 'node' and row.get('id') == 7371710998]
    if (len(nodes) != 1 or nodes[0].get('lat') != point.y or nodes[0].get('lon') != point.x
            or nodes[0].get('version') != 3 or nodes[0].get('timestamp') != '2021-02-02T00:59:52Z'
            or nodes[0].get('changeset') != 98539602):
        raise ValueError('Menzel Bouzaiane exact original village witness changed')
    source_rows = review['sourceRecords']
    if (not isinstance(source_rows, list) or len(source_rows) != len(raw_ids | absent_ids | context_ids)
            or any(not isinstance(row, dict) or set(row) != {'id', 'kind', 'tags', 'geometrySha256', 'expectedCuration'}
                   for row in source_rows)
            or {row['id'] for row in source_rows} != raw_ids | absent_ids | context_ids):
        raise ValueError('Menzel Bouzaiane source closure changed')
    assertions = {row['id']: row for row in source_rows}
    peer_ids = member_ids | {point_id, 'osm:relation:7169593'}
    peer_keys = {norm(v) for v in (base_name, locality_name, base['nomFr'], 'Menzel Bouzaiane', 'Menzel Buzeyyan')}
    if review['sourceNamePeerIds'] != sorted(peer_ids):
        raise ValueError('Menzel Bouzaiane source name inventory changed')
    for sources, provider in ((original_sources, None), (effective_sources, 'osm')):
        for source_id, row in assertions.items():
            source = sources.get(source_id)
            geometry = None if source is None else source.get('shape')
            if geometry is None and source is not None:
                geometry = source.get('point')
            if (source is None or geometry is None or geometry.is_empty
                    or source.get('sourceId') != provider or source['kind'] != row['kind']
                    or source['tags'] != row['tags'] or aggregate_geometry_sha256(geometry) != row['geometrySha256']
                    or row['expectedCuration'] is not None or curation.get(source_id) is not None):
                raise ValueError('Menzel Bouzaiane original/effective source or curation changed')
        if ({source_id for source_id, source in sources.items()
             if peer_keys & {norm(value) for value in names(source['tags'])}} != peer_ids):
            raise ValueError('Menzel Bouzaiane acquired an unreviewed source name peer')
        village = sources[point_id]
        if (village['kind'] != 'village' or village['tags'] != nodes[0]['tags']
                or village.get('shape') is not None or village['point'].geom_type != 'Point'
                or not village['point'].equals_exact(point, 0)
                or any(not sources[i]['shape'].covers(point) for i in member_ids)):
            raise ValueError('Menzel Bouzaiane absorbed reference or original containment changed')
        parent = sources['osm:relation:7169593']
        if (parent['kind'] != 'delegation' or parent['tags'].get('ref:tn:codegeo') != '4357'
                or parent['tags'].get('name:ar') != administrative_parent
                or sum(row['kind'] == 'delegation' and row['tags'].get('ref:tn:codegeo') == '4357'
                       for row in sources.values()) != 1
                or not all(parent['shape'].covers(sources[i]['shape']) for i in member_ids)
                or {sources[i]['tags'].get('ref:tn:codegeo') for i in sector_ids}
                   != {row['sectorCode'] for row in official}):
            raise ValueError('Menzel Bouzaiane exact administrative source identity changed')
    actual_raw_ids = {row['id'] for row in features if row['governorateId'] == governor['id']
                      and (row.get('parentName') == administrative_parent
                           or administrative_parent in row.get('contextAliases', []))}
    if (actual_raw_ids != raw_ids or not raw_ids <= set(indices)
            or review['sourceOnlyAbsentIds'] != sorted(absent_ids) or absent_ids & set(indices)):
        raise ValueError('Menzel Bouzaiane complete raw/absorbed source closure changed')
    expected_metadata = {i: {key: value for key, value in features[indices[i]].items()
                             if key not in {'pickerGroupId', 'offset', 'length'}} for i in raw_ids}
    if (review['protectedMetadata'] != expected_metadata
            or set(review['packedGeometrySha256']) != raw_ids
            or any(indices[i] >= len(geometries)
                   or hashlib.sha256(packed_geometry_bytes(geometries[indices[i]])).hexdigest()
                      != review['packedGeometrySha256'][i] for i in raw_ids)
            or any(features[indices[i]]['name'] != locality_name
                   or not geometries[indices[i]].covers(point) for i in member_ids)):
        raise ValueError('Menzel Bouzaiane preserved metadata or packed footprints changed')
    identities = record.get('identityEvidence')
    sector_registry = next(row for row in official if row['sectorCode'] == '435751')
    parent_registry = {key: sector_registry[key] for key in ('governorateCode', 'governorateAr',
                        'governorateFr', 'delegationCode', 'delegationAr', 'delegationFr')}
    if (not isinstance(identities, list) or len(identities) != 2
            or {(row.get('id'), row.get('method')) for row in identities}
                != {(identifier, 'current_coded_sector'),
                    ('osm:relation:7169593', 'current_coded_namesake_administrative_parent')}
            or identities != proof.get('identityEvidence')
            or any(row.get('accepted') is not True
                   or row.get('currentOfficialRecord') != (sector_registry if row['id'] == identifier else parent_registry)
                   for row in identities)):
        raise ValueError('Menzel Bouzaiane accepted sector/delegation proof changed')
    def scoped_groups(after):
        group_for = lambda row: 'delegation:510' if after and row['id'] in member_ids else row['pickerGroupId']
        group_ids = {group_for(features[indices[i]]) for i in raw_ids} | {'delegation:510'}
        result = []
        for group_id in sorted(group_ids):
            members = {row['id'] for row in features if group_for(row) == group_id}
            if not members <= raw_ids:
                raise ValueError('Menzel Bouzaiane protected group has an unreviewed member')
            if group_id.startswith('delegation:'):
                members.add(group_id)
            result.append({'id': group_id, 'memberIds': sorted(members)})
        return result
    before, after = scoped_groups(False), scoped_groups(True)
    if review['expectedBeforeGroups'] != before or review['expectedAfterGroups'] != after:
        raise ValueError('Menzel Bouzaiane exact before/after display groups changed')
    return picker_review_name_key(locality_name), {
        'id': identifier, 'identityMethod': method, 'targetDelegationId': target,
        'baseSpelling': base_name, 'localitySpelling': locality_name,
        'finalGroups': after, 'protectedMetadata': expected_metadata,
        'preservedNonCatalogPlace': {'id': point_id, 'role': 'absorbed_extracted_village'}}


def validate_reviewed_paired_article_spelling(option, manifest_path, record, proof,
                                              features, indices, original_sources,
                                              effective_sources, source_sha256,
                                              base, governor, curation):
    """Accept a pinned spelling pair for one coded sector, without changing names.

    The official article establishes a naming relationship, not polygon equality.
    The ordinary strict group and deferred point checks still own all mutations.
    """
    method = 'reviewed_paired_article_spelling_equivalence'
    try:
        directory = Path(manifest_path).parent
        if (not isinstance(option, dict)
                or set(option) != {'schemaVersion', 'method', 'reviewEvidence'}
                or type(option['schemaVersion']) is not int or option['schemaVersion'] != 1
                or option['method'] != method or proof.get('reviewedSpellingEquivalence') != option
                or 'retainedDisplayReference' in record or 'retainedDisplayReference' in proof):
            raise ValueError('Malformed paired-article spelling option')
        review = json.loads(aggregate_review_file(directory, option['reviewEvidence'], 'paired spelling review'))
        keys = {'schemaVersion', 'method', 'sourceSha256', 'baseCurrent', 'expectedGovernor',
                'sector', 'parent', 'officialRegistry', 'officialSectorRecord', 'primaryArticle',
                'sourceReview', 'qualifications'}
        qualifications = {'namesAndCoordinatesUnchanged': True, 'displayIdentityOnly': True,
                          'noBoundaryEquivalenceClaim': True}
        if (not isinstance(review, dict) or set(review) != keys
                or type(review['schemaVersion']) is not int or review['schemaVersion'] != 1
                or review['method'] != method or review['sourceSha256'] != source_sha256
                or review['baseCurrent'] != base
                or review['expectedGovernor'] != {k: v for k, v in governor.items() if k != 'delegations'}
                or review['qualifications'] != qualifications
                or any(v is not True for v in review['qualifications'].values())):
            raise ValueError('Paired spelling scope changed')
        sector, parent = review['sector'], review['parent']
        if (set(sector) != {'id', 'originalTags', 'sourceGeometrySha256', 'metadata'}
                or set(parent) != {'id', 'originalTags', 'sourceGeometrySha256'}):
            raise ValueError('Malformed paired spelling source inventory')
        identifier, target = sector['id'], base['id']
        if (record['id'] != identifier or proof['id'] != identifier
                or any(type(row.get('targetDelegationId')) is not int
                       or row['targetDelegationId'] != target for row in (record, proof))
                or record.get('expectedExistingMemberIds') != [identifier]
                or record.get('expectedTargetMemberIds') != [f'delegation:{target}']
                or identifier not in indices or identifier in curation or parent['id'] in curation):
            raise ValueError('Paired spelling exact membership changed')
        current = features[indices[identifier]]
        if (current['id'] != identifier or current['sourceId'] != 'osm' or current['kind'] != 'sector'
                or current['hasBoundary'] is not True or current['pickerGroupId'] != identifier
                or current['governorateId'] != governor['id']
                or {k: v for k, v in current.items() if k not in ('offset', 'length')}
                   != {k: v for k, v in sector['metadata'].items() if k not in ('offset', 'length')}):
            raise ValueError('Paired spelling sector metadata changed')
        official = review['officialSectorRecord']
        code = official['sectorCode']
        registry = json.loads(aggregate_review_file(directory, review['officialRegistry'], 'paired spelling registry'))
        base_name, locality_name = base['nomAr'], official['sectorAr']
        if (not isinstance(code, str) or re.fullmatch(r'[0-9]{6}', code) is None
                or [row for row in registry['sectors'] if row.get('sectorCode') == code] != [official]
                or official['delegationCode'] != code[:4] or official['governorateCode'] != code[:2]
                or official['governorateAr'] != governor['nomAr']
                or official['delegationAr'] != locality_name or current['name'] != locality_name
                or sector['originalTags'].get('name:ar') != locality_name
                or not isinstance(base_name, str) or not isinstance(locality_name, str)
                or not picker_review_name_key(base_name) or not picker_review_name_key(locality_name)
                or picker_review_name_key(base_name) == picker_review_name_key(locality_name)):
            raise ValueError('Paired spelling official names changed')
        for sources, provider in ((original_sources, None), (effective_sources, 'osm')):
            for row, kind, expected_code in ((sector, 'sector', code), (parent, 'delegation', code[:4])):
                source = sources[row['id']]
                geometry = source['shape']
                if (source['kind'] != kind or source.get('sourceId') != provider
                        or source['tags'] != row['originalTags'] or geometry.is_empty
                        or geometry.geom_type not in ('Polygon', 'MultiPolygon')
                        or aggregate_geometry_sha256(geometry) != row['sourceGeometrySha256']
                        or source['tags'].get('ref:tn:codegeo') != expected_code
                        or sum(obj['kind'] == kind and obj['tags'].get('ref:tn:codegeo') == expected_code
                               for obj in sources.values()) != 1):
                    raise ValueError('Paired spelling original or effective source changed')
            parent_source = sources[parent['id']]
            if (parent_source['tags'].get('name:ar', '').removeprefix('معتمدية ') != locality_name
                    or not parent_source['shape'].covers(sources[identifier]['shape'])
                    or not parent_source['shape'].contains(Point(base['lng'], base['lat']))):
                raise ValueError('Paired spelling official parent context changed')
        article = review['primaryArticle']
        if (set(article) != {'file', 'sha256', 'url', 'articleId'}
                or not isinstance(article['url'], str)
                or not article['url'].startswith('https://www.interieur.gov.tn/')
                or type(article['articleId']) is not int or article['articleId'] <= 0):
            raise ValueError('Malformed paired spelling primary article')
        html = aggregate_review_file(directory, article, 'paired spelling primary article').decode('utf-8')
        articles = [text for text in re.findall(r'<article\b.*?</article>', html, re.S)
                    if f"/actualite/{article['articleId']}/" in text]
        if len(articles) != 1 or any(name not in articles[0] for name in (base_name, locality_name)):
            raise ValueError('Both spellings must appear in the same reviewed primary article')
        approval = json.loads(aggregate_review_file(directory, review['sourceReview'], 'paired spelling approval'))
        if (type(approval['schemaVersion']) is not int or approval['schemaVersion'] != 1
                or approval['status'] != 'ROOT_APPROVED_PAIRED_SPELLING_DISPLAY_ONLY'
                or approval['sourceSha256'] != source_sha256
                or type(approval['baseId']) is not int or approval['baseId'] != target
                or approval['sectorId'] != identifier or approval['baseSpelling'] != base_name
                or approval['localitySpelling'] != locality_name):
            raise ValueError('Paired spelling independent approval changed')
        return picker_review_name_key(locality_name), {
            'id': identifier, 'identityMethod': method, 'targetDelegationId': target,
            'baseSpelling': base_name, 'localitySpelling': locality_name,
            'reviewEvidence': option['reviewEvidence']}
    except (KeyError, TypeError, AttributeError, IndexError, UnicodeDecodeError) as exc:
        raise ValueError('Malformed paired-article spelling evidence') from exc


def apply_reviewed_picker_groups(manifest_path, features, geometries, governors, timetables,
                                 original_sources, effective_sources, source_sha256, curation,
                                 reviewed_boundaries=None, official_report=None):
    """Join exact reviewed display groups without modifying geographic records.

    The pre-existing locality and original-delegation group inventories must
    still match the review. Every locality member retains its coordinates and
    nearest prayer source; source geometry, packed geometry and original tags
    are pinned independently. No name, proximity or transitive rule adds members.
    """
    manifest_path = Path(manifest_path)
    raw = manifest_path.read_bytes()
    manifest = json.loads(raw)
    if (manifest.get('schemaVersion') != 1 or manifest.get('sourceSha256') != source_sha256
            or not isinstance(manifest.get('records'), list)):
        raise ValueError('Reviewed picker groups need review for this source')
    _validated_strict_parent_owner = None
    _picker_lineage_ref = manifest.get('lineageCompatibility')
    if _picker_lineage_ref is not None:
        _picker_lineage_doc = json.loads(aggregate_review_file(
            manifest_path.parent, _picker_lineage_ref, 'picker lineage compatibility'))
        if (isinstance(_picker_lineage_doc, dict)
                and _picker_lineage_doc.get('method') == 'reviewed_picker_strict_boundary_successor'):
            load_reviewed_picker_manifest_for_lineage(
                manifest_path.parent,
                {'file': manifest_path.name, 'sha256': hashlib.sha256(raw).hexdigest()},
                manifest_path, manifest, source_sha256,
                reviewed_boundaries=reviewed_boundaries, official_report=official_report,
                original_sources=original_sources, effective_sources=effective_sources)
            _validated_strict_parent_owner = _picker_lineage_doc['activeRecord']
    additional_evidence = manifest.get('additionalReviewEvidence', [])
    if not isinstance(additional_evidence, list):
        raise ValueError('Invalid additional picker-group evidence')
    reviewed_records = {}
    for reference in [manifest.get('reviewEvidence'), *additional_evidence]:
        evidence = json.loads(aggregate_review_file(manifest_path.parent, reference,
                                                   'picker-group evidence'))
        if evidence.get('sourceSha256', source_sha256) != source_sha256:
            raise ValueError('Reviewed picker evidence uses a different source version')
        if 'sourceFacts' in evidence:
            aggregate_review_file(manifest_path.parent, evidence['sourceFacts'], 'picker source facts')
        for proof in evidence['records']:
            if proof.get('status') != 'eligible_proposal':
                continue
            if proof['id'] in reviewed_records:
                raise ValueError('Repeated approved picker-group evidence')
            reviewed_records[proof['id']] = proof
    indices = {f['id']: index for index, f in enumerate(features)}
    if len(indices) != len(features):
        raise ValueError('Repeated locality ID before reviewed picker grouping')
    groups = defaultdict(set)
    for feature in features:
        groups[feature['pickerGroupId']].add(feature['id'])
    bases = {d['id']: (g, d) for g in governors for d in g['delegations']}
    available_ids = {d['id'] for d in timetables}
    staged, seen_ids, seen_targets, seen_members = [], set(), set(), set()
    split_applications = []
    retained_display_applications = []
    spelling_applications = []
    for record in manifest['records']:
        identifier = record.get('id')
        target = record.get('targetDelegationId')
        if (not isinstance(identifier, str) or identifier in seen_ids or identifier.startswith('delegation:')
                or type(target) is not int or target in seen_targets or target not in bases
                or target not in available_ids):
            raise ValueError('Invalid, repeated or unavailable reviewed picker target')
        proof = reviewed_records.get(identifier)
        governor, base = bases[target]
        base_group = f'delegation:{target}'
        expected_base = record.get('expectedBase', {})
        if (proof is None or proof['targetDelegationId'] != target
                or record.get('expectedGovernorateId') != governor['id']
                or proof['governorateId'] != governor['id']
                or any(proof['basePoint'][key] != base.get(key) for key in ('lat', 'lng'))
                or any(expected_base.get(key) != base.get(key) for key in ('nomAr', 'lat', 'lng'))
                or any(type(base.get(key)) not in (int, float) or not math.isfinite(base[key])
                       for key in ('lat', 'lng'))):
            raise ValueError(f'Reviewed original picker location changed: {identifier}')
        expected_members = record.get('expectedExistingMemberIds')
        expected_target = record.get('expectedTargetMemberIds')
        members = record.get('members')
        if (not isinstance(expected_members, list) or not expected_members
                or len(expected_members) != len(set(expected_members))
                or set(expected_members) != groups.get(identifier, set())
                or set(expected_members) != {m['id'] for m in proof['members']}
                or not isinstance(members, list) or len(members) != len(expected_members)
                or {m.get('id') for m in members} != set(expected_members)
                or seen_members & set(expected_members)
                or not isinstance(expected_target, list) or len(expected_target) != len(set(expected_target))
                or set(expected_target) != groups.get(base_group, set()) | {base_group}
                or set(expected_target) != set(proof['existingTargetGroupMembers'])):
            raise ValueError(f'Reviewed picker group membership changed: {identifier}')
        prerequisites = record.get('requiredCurations', [])
        if (not isinstance(prerequisites, list) or prerequisites != proof.get('requiredCurations', [])
                or any(not isinstance(rule, dict) or rule.get('id') not in expected_members
                       or curation.get(rule['id']) != rule for rule in prerequisites)
                or len({rule['id'] for rule in prerequisites}) != len(prerequisites)):
            raise ValueError('Reviewed picker curation prerequisite changed')
        for rule in prerequisites:
            for source in rule['evidence']:
                if isinstance(source, dict):
                    for reference in source.get('reviewFiles', []):
                        aggregate_review_file(manifest_path.parent, reference, 'picker curation evidence')
        base_point = Point(base['lng'], base['lat'])
        display_point = base_point
        retained_settlement = None
        if 'retainedDisplayReference' in record or 'retainedDisplayReference' in proof:
            option = record.get('retainedDisplayReference')
            if isinstance(option, dict) and option.get('method') == 'reviewed_settlement_retained_display_reference':
                display_point, display_application = validate_settlement_retained_display_reference(
                    option, manifest_path, record, proof, features, indices, geometries,
                    original_sources, effective_sources, source_sha256, base, governor, timetables, curation)
                retained_settlement = display_application
            else:
                display_point, display_application = validate_hichria_retained_display_reference(
                    option, manifest_path, record, proof, features, indices, geometries,
                    original_sources, effective_sources, source_sha256, base, governor)
            retained_display_applications.append(display_application)
        if 'reviewedSpellingEquivalence' in record or 'reviewedSpellingEquivalence' in proof:
            option = record.get('reviewedSpellingEquivalence')
            if isinstance(option, dict) and option.get('method') == 'reviewed_paired_article_spelling_equivalence':
                primary, spelling_application = validate_reviewed_paired_article_spelling(
                    option, manifest_path, record, proof, features, indices,
                    original_sources, effective_sources, source_sha256, base, governor, curation)
                spelling_applications.append(spelling_application)
            else:
                primary, spelling_application = validate_menzel_bouzaiane_spelling_equivalence(
                    option, manifest_path, record, proof,
                    features, indices, geometries, original_sources, effective_sources,
                    source_sha256, base, governor, curation)
                retained_display_applications.append(spelling_application)
        else:
            primary = (retained_settlement['reviewedPrimaryNameKey'] if retained_settlement is not None
                       else picker_review_name_key(base['nomAr']))
        if not primary or primary != picker_review_name_key(proof['name']):
            raise ValueError('Missing reviewed original picker name')
        for existing_id in groups.get(base_group, set()):
            existing = features[indices[existing_id]]
            if (existing['governorateId'] != governor['id'] or existing['delegationId'] != target
                    or picker_review_name_key(existing['name']) != primary):
                raise ValueError('Existing original picker members no longer agree')
        for member in members:
            member_id = member['id']
            index = indices[member_id]
            current = features[index]
            expected_prayer_source = (retained_settlement['memberPrayerSources'][member_id]
                                     if retained_settlement is not None else target)
            original = original_sources.get(member_id)
            effective = effective_sources.get(member_id)
            if (index >= len(geometries) or not current['hasBoundary'] or original is None or effective is None
                    or original['tags'] != member.get('expectedOriginalTags')
                    or any(current[key] != member.get(expected) for key, expected in (
                        ('kind', 'expectedKind'), ('sourceId', 'expectedSourceId'), ('name', 'expectedName'),
                        ('parentName', 'expectedParentName'), ('delegationId', 'expectedDelegationId'),
                        ('lat', 'expectedLat'), ('lng', 'expectedLng')))
                    or current['governorateId'] != governor['id'] or current['delegationId'] != expected_prayer_source
                    or picker_review_name_key(current['name']) != primary):
                raise ValueError(f'Reviewed picker member identity or default changed: {member_id}')
            source_geometry = effective.get('shape')
            packed_geometry = geometries[index]
            if (source_geometry is None or not source_geometry.covers(display_point)
                    or not packed_geometry.covers(display_point)
                    or aggregate_geometry_sha256(source_geometry) != member.get('originalGeometrySha256')
                    or hashlib.sha256(packed_geometry_bytes(packed_geometry)).hexdigest() != member.get('packedGeometrySha256')):
                raise ValueError(f'Reviewed picker geometry or containment changed: {member_id}')
            nearest = min(timetables, key=lambda d: (distance(current['lat'], current['lng'], d), d['id']))
            if nearest['id'] != expected_prayer_source:
                raise ValueError(f'Reviewed picker member no longer matches its reviewed nearest prayer source: {member_id}')
        identity_evidence = record.get('identityEvidence', [])
        if not isinstance(identity_evidence, list):
            raise ValueError('Missing reviewed picker locality identity evidence')
        supported = False
        sector_evidence = set()
        for identity in identity_evidence:
            if identity.get('accepted') is not True:
                continue
            pinned_identities = [old for old in proof['identityEvidence'] if old.get('accepted') is True
                                 and all(identity.get(key) == value for key, value in old.items())]
            if len(pinned_identities) != 1:
                raise ValueError('Reviewed picker identity is not bound to its approved source evidence')
            if identity.get('method') == 'reviewed_split_settlement_reference':
                split_applications.append(validate_reviewed_split_settlement_reference(
                    identity, manifest_path, manifest, record, features, indices, geometries, groups,
                    original_sources, effective_sources, source_sha256, base, governor, timetables, curation,
                    reviewed_boundaries, official_report))
                supported = True
                continue
            if identity.get('method') == 'reviewed_contained_settlement_reference':
                validate_reviewed_contained_settlement_reference(
                    identity, manifest_path, record, features, indices, geometries,
                    original_sources, effective_sources, source_sha256, base, governor,
                    timetables, curation)
                supported = True
                continue
            if identity.get('method') == 'reviewed_absorbed_town_reference':
                validate_reviewed_absorbed_town_reference(
                    identity, manifest_path, manifest, record, features, indices, geometries,
                    original_sources, effective_sources, base, governor, curation)
                supported = True
                continue
            identity_id = identity.get('id')
            original = original_sources.get(identity_id)
            effective = effective_sources.get(identity_id)
            if (original is None or effective is None
                    or original['tags'] != identity.get('expectedOriginalTags')
                    or aggregate_geometry_sha256(effective['shape']) != identity.get('effectiveOriginalGeometrySha256')):
                raise ValueError('Reviewed picker supporting identity source changed')
            method = identity.get('method')
            source_parent_evidence = identity.get('originalCodedParentEvidence', [])
            if (not isinstance(source_parent_evidence, list)
                    or (method in ('reviewed_current_sector_transfer', 'reviewed_current_sector_name_equivalence')
                        and not source_parent_evidence)):
                raise ValueError('Missing reviewed source-parent evidence')
            seen_parents = set()
            for parent in source_parent_evidence:
                parent_id = parent.get('id')
                original_parent = original_sources.get(parent_id)
                effective_parent = effective_sources.get(parent_id)
                source_code = identity.get('sourceCode', identity.get('code'))
                if (not isinstance(source_code, str) or len(source_code) != 6
                        or effective['kind'] != 'sector' or parent_id in seen_parents
                        or original_parent is None or effective_parent is None
                        or original_parent['kind'] != 'delegation' or effective_parent['kind'] != 'delegation'
                        or parent.get('sourceCode') != source_code[:4]
                        or original_parent['tags'] != parent.get('expectedOriginalTags')
                        or effective_parent['tags'].get('ref:tn:codegeo') != source_code[:4]
                        or aggregate_geometry_sha256(effective_parent['shape']) != parent.get('effectiveOriginalGeometrySha256')
                        or parent.get('containsSourceSector') is not True
                        or not effective_parent['shape'].covers(effective['shape'])
                        or parent.get('containsEveryGroupMember') is not all(
                            effective_parent['shape'].covers(effective_sources[i]['shape']) for i in expected_members)
                        or sum(obj['tags'].get('ref:tn:codegeo') == source_code[:4] for obj in effective_sources.values()
                               if obj['kind'] == 'delegation') != 1):
                    raise ValueError('Reviewed picker source-parent identity or containment changed')
                seen_parents.add(parent_id)
            if method == 'explicit_named_place':
                if (identity_id not in expected_members or original['tags'].get('place') not in AREA_PLACES
                        or picker_review_name_key(names(effective['tags'])[0]) != primary):
                    raise ValueError('Reviewed picker place identity no longer agrees')
            elif method == 'reviewed_explicit_city':
                # This applies only to an exact manifest entry. It does not
                # add cities to the automatic grouping or place-kind rules.
                if (identity_id not in expected_members or original['tags'].get('place') != 'city'
                        or picker_review_name_key(names(effective['tags'])[0]) != primary):
                    raise ValueError('Reviewed picker city identity no longer agrees')
            elif method in ('reviewed_current_sector_transfer', 'reviewed_current_sector_name_equivalence'):
                source_code = identity.get('sourceCode')
                current_code = identity.get('currentCode')
                registry = identity.get('currentOfficialRecord')
                equivalence = identity.get('nameEquivalence')
                expected_curation = identity.get('expectedCuration')
                if (identity_id not in expected_members or effective['kind'] != 'sector'
                        or not isinstance(source_code, str) or len(source_code) != 6 or not source_code.isdigit()
                        or not isinstance(current_code, str) or len(current_code) != 6 or not current_code.isdigit()
                        or source_code[:2] != current_code[:2]
                        or original['tags'].get('ref:tn:codegeo') != source_code
                        or effective['tags'].get('ref:tn:codegeo') != source_code
                        or not isinstance(registry, dict) or registry.get('sectorCode') != current_code
                        or registry.get('governorateCode') != current_code[:2]
                        or registry.get('delegationCode') != current_code[:4]
                        or picker_review_name_key(registry.get('governorateAr', '')) != picker_review_name_key(governor['nomAr'])
                        or not isinstance(equivalence, dict)
                        or equivalence.get('displayName') != features[indices[identity_id]]['name']
                        or equivalence.get('displayParentName') != features[indices[identity_id]]['parentName']
                        or equivalence.get('officialSectorAr') != registry.get('sectorAr')
                        or equivalence.get('officialDelegationAr') != registry.get('delegationAr')
                        or any(not isinstance(equivalence.get(key), str) or not clean(equivalence[key]) for key in (
                            'displayName', 'displayParentName', 'officialSectorAr', 'officialDelegationAr'))
                        or curation.get(identity_id) != expected_curation
                        or sum(obj['tags'].get('ref:tn:codegeo') == source_code for obj in effective_sources.values()
                               if obj['kind'] == 'sector') != 1):
                    raise ValueError('Reviewed picker sector code or exact name equivalence changed')
                if method == 'reviewed_current_sector_transfer':
                    if (source_code == current_code or not isinstance(expected_curation, dict)
                            or expected_curation.get('action') != 'administrative_context'
                            or expected_curation.get('expectedTags', {}).get('ref:tn:codegeo') != source_code
                            or picker_review_name_key(registry['sectorAr']) != primary
                            or picker_review_name_key(equivalence['displayParentName'].removeprefix('معتمدية '))
                               != picker_review_name_key(registry['delegationAr'])
                            or any(obj['tags'].get('ref:tn:codegeo') == current_code for obj in effective_sources.values()
                                   if obj['kind'] == 'sector')):
                        raise ValueError('Reviewed picker sector transfer changed or has a competing current source')
                elif source_code != current_code:
                    raise ValueError('A reviewed name equivalence must retain the same official sector code')
                sector_evidence.add(identity_id)
            elif method == 'reviewed_official_boundary_parent_membership':
                code = identity.get('code')
                registry = identity.get('currentOfficialRecord')
                if (record != _validated_strict_parent_owner
                        or not isinstance(_validated_strict_parent_owner, dict)
                        or [row for row in _validated_strict_parent_owner.get('identityEvidence') or []
                            if row.get('method') == method] != [identity]
                        or effective['kind'] != 'delegation'
                        or not isinstance(code, str) or effective['tags'].get('ref:tn:codegeo') != code
                        or not isinstance(registry, dict) or registry.get('delegationCode') != code
                        or registry.get('governorateCode') != code[:2]
                        or picker_review_name_key(registry.get('governorateAr', '')) != picker_review_name_key(governor['nomAr'])
                        or picker_review_name_key(registry.get('delegationAr', '')) != primary
                        or picker_review_name_key(names(effective['tags'])[0].removeprefix('معتمدية ')) != primary
                        or sum(obj['tags'].get('ref:tn:codegeo') == code for obj in effective_sources.values()
                               if obj['kind'] == 'delegation') != 1):
                    raise ValueError('Reviewed official parent membership is not the exact validated strict successor')
            elif method in ('current_coded_sector', 'current_coded_namesake_administrative_parent'):
                code = identity.get('code')
                registry = identity.get('currentOfficialRecord', {})
                if (not isinstance(code, str) or effective['tags'].get('ref:tn:codegeo') != code
                        or not isinstance(registry, dict)
                        or registry.get('governorateCode') != code[:2]
                        or registry.get('delegationCode') != code[:4]
                        or picker_review_name_key(registry.get('governorateAr', '')) != picker_review_name_key(governor['nomAr'])
                        or sum(obj['tags'].get('ref:tn:codegeo') == code for obj in effective_sources.values()
                               if obj['kind'] == effective['kind']) != 1):
                    raise ValueError('Reviewed picker administrative identity is no longer unique')
                if method == 'current_coded_sector':
                    if (effective['kind'] != 'sector' or identity_id not in expected_members
                            or registry.get('sectorCode') != code
                            or picker_review_name_key(registry.get('sectorAr', '')) != primary
                            or picker_review_name_key(features[indices[identity_id]]['parentName'].removeprefix('معتمدية '))
                               != picker_review_name_key(registry.get('delegationAr', ''))):
                        raise ValueError('Reviewed picker sector identity changed')
                    sector_evidence.add(identity_id)
                else:
                    if (effective['kind'] != 'delegation'
                            or picker_review_name_key(registry.get('delegationAr', '')) != primary
                            or picker_review_name_key(names(effective['tags'])[0].removeprefix('معتمدية ')) != primary
                            or not all(effective['shape'].covers(effective_sources[i]['shape']) for i in expected_members)):
                        raise ValueError('Reviewed picker administrative parent or containment changed')
            else:
                raise ValueError('Unknown reviewed picker identity evidence')
            supported = True
        if not supported or any(features[indices[i]]['kind'] == 'sector' and i not in sector_evidence
                                for i in expected_members):
            raise ValueError('Reviewed picker group lacks an independently supported current identity')
        staged.append((base_group, expected_members))
        seen_ids.add(identifier)
        seen_targets.add(target)
        seen_members.update(expected_members)
    if seen_ids != set(reviewed_records):
        raise ValueError('Reviewed picker group inventory differs from its approved source evidence')
    local_staged, local_report = validate_reviewed_locality_groups(
        manifest_path, manifest, features, geometries, timetables, original_sources, effective_sources,
        source_sha256, curation, groups, indices,
        seen_ids | {f'delegation:{target}' for target in seen_targets},
        seen_members | {mid for target in seen_targets for mid in groups.get(f'delegation:{target}', set())})
    city_staged, city_report = validate_reviewed_city_display_associations(
        manifest_path, manifest, features, geometries, governors, timetables,
        original_sources, effective_sources, source_sha256, curation, groups, indices,
        seen_ids | {f'delegation:{target}' for target in seen_targets}
        | {record['id'] for record in manifest.get('localRecords', [])}
        | {record['targetPickerGroupId'] for record in manifest.get('localRecords', [])},
        seen_members | {mid for target in seen_targets for mid in groups.get(f'delegation:{target}', set())}
        | {member['id'] for record in manifest.get('localRecords', []) for member in record['members']}
        | {sid for record in manifest.get('localRecords', [])
           if record.get('method') == 'reviewed_two_patch_settlement'
           for sid in record.get('exclusiveSupportingSourceIds', [])})
    # Check every record before mutating any group, so a partial review cannot
    # produce a partially updated catalog. Existing target groups never expand
    # the set of approved source IDs through transitive matching.
    for base_group, member_ids in staged + local_staged + city_staged:
        for identifier in member_ids:
            features[indices[identifier]]['pickerGroupId'] = base_group
    combined = defaultdict(list)
    for feature in features:
        combined[feature['pickerGroupId']].append(feature['id'])
    duplicate_groups = [{'pickerGroupId': identifier, 'ids': sorted(member_ids)}
                        for identifier, member_ids in sorted(combined.items())
                        if len(member_ids) > 1 or identifier.startswith('delegation:')]
    report = {'manifestSha256': hashlib.sha256(raw).hexdigest(),
        'evidenceSha256': manifest['reviewEvidence']['sha256'], 'reviewedGroupCount': len(staged),
        'preservedMemberCount': len(seen_members),
        'applications': [{'pickerGroupId': base_group, 'ids': sorted(ids)} for base_group, ids in staged]}
    if additional_evidence:
        report['additionalEvidenceSha256'] = [reference['sha256'] for reference in additional_evidence]
    if split_applications:
        split_report = {'reviewedGroupCount': len(split_applications), 'applications': split_applications}
        verify_reviewed_city_display_associations(features, split_report)
        report['splitSettlementReferences'] = split_report
    if spelling_applications:
        report['spellingEquivalences'] = {'reviewedGroupCount': len(spelling_applications),
                                          'applications': spelling_applications}
    if retained_display_applications:
        retained_report = {'reviewedGroupCount': len(retained_display_applications),
                           'applications': retained_display_applications}
        verify_reviewed_city_display_associations(features, retained_report)
        report['retainedDisplayReferences'] = retained_report
    if local_report is not None:
        verify_reviewed_city_display_associations(features, local_report.get('completeGroupOwners'))
        report['localityGroups'] = local_report
    if city_report is not None:
        verify_reviewed_city_display_associations(features, city_report)
        report['cityDisplayAssociations'] = city_report
    return duplicate_groups, report


def reviewed_saved_replacements(manifest_path, curation, original_sources, effective_sources,
                                official_sources, official_contexts, features, source_sha256):
    """Keep an exact retired identity only after its jointly reviewed correction.

    Old footprints never survive as GPS candidates. This does not infer a
    replacement from matching names, proximity or polygon overlap.
    """
    directory = Path(manifest_path).parent
    excluded = {i for i, rule in curation.items() if rule['action'] == 'exclude'}
    current = {f['id']: f for f in features}
    replacements, seen_targets = [], set()
    for identifier, rule in curation.items():
        if 'replacementReview' not in rule:
            continue
        review = json.loads(aggregate_review_file(directory, rule['replacementReview'], 'saved replacement review'))
        target_id = rule['replacementId']
        original = original_sources[identifier]
        if (review.get('schemaVersion') != 1
                or review.get('conclusion') != 'redundant_uncoded_sector_with_corrected_coded_replacement'
                or review.get('sourceSha256') != source_sha256
                or review.get('id') != identifier or review.get('replacementId') != target_id
                or identifier not in excluded or identifier in current
                or target_id in excluded or target_id in seen_targets or target_id not in current
                or original['kind'] != 'sector' or original['tags'] != review.get('expectedOriginalTags')
                or original['tags'] != rule['expectedTags'] or original['tags'].get('ref:tn:codegeo')
                or aggregate_geometry_sha256(original['shape']) != review.get('expectedOriginalGeometrySha256')):
            raise ValueError('Reviewed retired sector identity or original footprint changed')
        clone = original_sources.get(review.get('originalGeometryCloneId'))
        if clone is None or aggregate_geometry_sha256(clone['shape']) != review['expectedOriginalGeometrySha256']:
            raise ValueError('Reviewed source geometry clone no longer matches')
        source_ref = review.get('boundarySource')
        collection = json.loads(aggregate_review_file(directory, source_ref, 'replacement boundary source'))
        required_ids = review.get('requiredBoundaryIds')
        if (not isinstance(required_ids, list) or not required_ids
                or len(set(required_ids)) != len(required_ids)
                or target_id not in required_ids or review['originalGeometryCloneId'] not in required_ids
                or set(required_ids) != {f['id'] for f in collection['features']}):
            raise ValueError('Saved replacement requires the complete reviewed companion inventory')
        for boundary in collection['features']:
            bid = boundary['id']; props = boundary['properties']
            effective = effective_sources.get(bid)
            context = official_contexts.get(bid, {})
            source = official_sources.get(props.get('sourceId'), {})
            if (bid not in current or bid in excluded or effective is None or effective['kind'] != 'sector'
                    or context.get('sourceId') != props['sourceId'] or source.get('sha256') != source_ref['sha256']
                    or effective['tags'].get('ref:tn:codegeo') != props['officialCode']
                    or aggregate_geometry_sha256(effective['shape']) != aggregate_geometry_sha256(shape(boundary['geometry']))):
                raise ValueError('A jointly reviewed replacement boundary is missing or changed')
        registry = json.loads(aggregate_review_file(directory, review.get('officialRegistry'), 'replacement official registry'))
        code = review.get('replacementOfficialCode', '')
        matches = [r for r in registry['sectors'] if r['delegationCode'] == code[:4]
                   and norm(r['sectorAr']) == norm(review.get('replacementName', ''))]
        if (len(matches) != 1 or matches[0]['sectorCode'] != code
                or norm(matches[0]['sectorAr']) != norm(names(original['tags'])[0])
                or len(matches[0].get('sourceRows', {})) != 3):
            raise ValueError('Retired and replacement names lack one unique official sector identity')
        target = current[target_id]
        if (target['kind'] != review.get('replacementKind') or target['name'] != review.get('replacementName')
                or target['governorateId'] != review.get('replacementGovernorateId')
                or target['parentName'] != review.get('replacementParentName')
                or target.get('pickerGroupId') != target_id
                or effective_sources[target_id]['tags'].get('ref:tn:codegeo') != code):
            raise ValueError('Saved replacement final name, parent or canonical picker identity changed')
        if not isinstance(review.get('evidence'), list) or not review['evidence']:
            raise ValueError('Saved replacement lacks its individual source review')
        for reference in review['evidence']:
            aggregate_review_file(directory, reference, 'saved replacement evidence')
        replacements.append({'id': identifier, 'replacementId': target_id,
                             'name': target['name'], 'kind': target['kind']})
        seen_targets.add(target_id)
    return sorted(replacements, key=lambda row: row['id'])


def load_reviewed_official_manual_points(manifest_path, source_sha256,
                                         original_sources, effective_sources,
                                         curation, timetables):
    manifest_path = Path(manifest_path)
    manifest_dir = manifest_path.resolve().parent
    manifest = json.loads(manifest_path.read_bytes().decode('utf-8'))
    if not isinstance(manifest, dict):
        raise ValueError('official manual-point manifest must be a JSON object')
    sources = manifest.get('sources')
    if not isinstance(sources, list):
        raise ValueError('official manual-point manifest must contain a sources list')

    def _require_string(value, label):
        if not isinstance(value, str) or not value:
            raise ValueError(f'{label} must be a non-empty string')
        return value

    def _provider_id(provider):
        if 'providerId' in provider:
            value = _require_string(provider['providerId'], 'source providerId')
            if 'id' in provider and provider['id'] != value:
                raise ValueError('source id/providerId mismatch')
            return value
        if 'id' in provider:
            return _require_string(provider['id'], 'source id')
        raise ValueError('official source entry must have id or providerId')

    def _source_dir(provider_file):
        path = Path(provider_file)
        if not path.is_absolute():
            path = manifest_dir / path
        return path.resolve().parent

    def _pin(value, label):
        if not isinstance(value, dict) or set(value) != {'file', 'sha256'}:
            raise ValueError(f'{label} must contain exactly file and sha256')
        _require_string(value['file'], f'{label}.file')
        _require_string(value['sha256'], f'{label}.sha256')
        return value

    def _coord(value, label):
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise ValueError(f'{label} must be a finite number')
        value = float(value)
        if not math.isfinite(value):
            raise ValueError(f'{label} must be a finite number')
        return value

    def _lat_lng(value, label):
        if not isinstance(value, dict) or set(value) != {'lat', 'lng'}:
            raise ValueError(f'{label} must contain exactly lat and lng')
        lat = _coord(value['lat'], f'{label}.lat')
        lng = _coord(value['lng'], f'{label}.lng')
        if not (-90.0 <= lat <= 90.0) or not (-180.0 <= lng <= 180.0):
            raise ValueError(f'{label} coordinates out of range')
        return lat, lng

    def _source_coords(value, label):
        if isinstance(value, dict):
            if 'lat' not in value or 'lng' not in value:
                raise ValueError(f'{label} must contain lat and lng')
            return _coord(value['lat'], f'{label}.lat'), _coord(value['lng'], f'{label}.lng')
        if hasattr(value, 'x') and hasattr(value, 'y'):
            return _coord(value.y, f'{label}.y'), _coord(value.x, f'{label}.x')
        raise ValueError(f'{label} must be a lat/lng mapping or point')

    def _primary_name(tags, label):
        if not isinstance(tags, dict):
            raise ValueError(f'{label} tags must be an object')
        values = names(tags)
        if not values:
            raise ValueError(f'{label} must have a primary name')
        return values[0]

    def _covers(shape, lat, lng, label):
        point = Point(lng, lat)
        if (shape is None or shape.geom_type not in ('Polygon', 'MultiPolygon')
                or shape.is_empty or not shape.is_valid):
            raise ValueError(f'{label} must be a polygon')
        if not bool(shape.covers(point)):
            raise ValueError(f'{label} does not cover chosen point')
        quantized = set_precision(shape, 1.0 / SCALE)
        if quantized.is_empty or not quantized.is_valid or not bool(quantized.covers(point)):
            raise ValueError(f'{label} quantized polygon does not cover chosen point')

    choices = {}
    report_rows = []
    seen_record_ids = set()

    for provider in sources:
        if not isinstance(provider, dict):
            raise ValueError('official source entry must be an object')
        if 'manualPointReview' not in provider:
            continue
        review_ref = provider['manualPointReview']
        provider_id = _provider_id(provider)
        provider_file = _require_string(provider.get('file'), 'source file')
        provider_sha256 = _require_string(provider.get('sha256'), 'source sha256')
        source_dir = manifest_dir
        if provider.get('sourceSha256') != source_sha256:
            raise ValueError('Manual point provider source checksum changed')
        _pin(review_ref, f'manualPointReview for {provider_id}')
        review_bytes = aggregate_review_file(manifest_dir, review_ref,
                                             f'manualPointReview for {provider_id}')
        review = json.loads(review_bytes.decode('utf-8'))
        if not isinstance(review, dict):
            raise ValueError(f'manualPointReview for {provider_id} must be an object')
        review_keys = {'schemaVersion', 'method', 'sourceSha256', 'providerId',
                       'boundarySource', 'baselineMetadata', 'records', 'evidence'}
        if set(review) != review_keys:
            raise ValueError(f'manualPointReview for {provider_id} has unexpected schema keys')
        if type(review['schemaVersion']) is not int or review['schemaVersion'] != 1:
            raise ValueError(f'manualPointReview for {provider_id} has unsupported schemaVersion')
        if review['method'] != 'reviewed_official_manual_point_choices':
            raise ValueError(f'manualPointReview for {provider_id} has unexpected method')
        if _require_string(review['sourceSha256'], 'review sourceSha256') != source_sha256:
            raise ValueError(f'manualPointReview for {provider_id} sourceSha256 mismatch')
        if _require_string(review['providerId'], 'review providerId') != provider_id:
            raise ValueError(f'manualPointReview for {provider_id} providerId mismatch')
        expected_boundary = {'file': provider_file, 'sha256': provider_sha256}
        if review['boundarySource'] != expected_boundary:
            raise ValueError(f'manualPointReview for {provider_id} boundarySource mismatch')

        baseline_ref = _pin(review['baselineMetadata'],
                            f'baselineMetadata for {provider_id}')
        baseline_bytes = aggregate_review_file(source_dir, baseline_ref,
                                               f'baselineMetadata for {provider_id}')
        baseline = json.loads(baseline_bytes.decode('utf-8'))
        if not isinstance(baseline, dict) or not isinstance(baseline.get('features'), list):
            raise ValueError(f'baselineMetadata for {provider_id} must contain features list')
        baseline_by_id = {}
        for feature in baseline['features']:
            if not isinstance(feature, dict):
                raise ValueError(f'baselineMetadata for {provider_id} has non-object feature')
            feature_id = _require_string(feature.get('id'), 'baseline feature id')
            if feature_id in baseline_by_id:
                raise ValueError(f'baselineMetadata for {provider_id} has duplicate feature id {feature_id}')
            baseline_by_id[feature_id] = feature

        evidence = review['evidence']
        if not isinstance(evidence, list) or not evidence:
            raise ValueError(f'manualPointReview for {provider_id} evidence must be a non-empty list')
        for index, pin in enumerate(evidence):
            label = f'evidence[{index}] for {provider_id}'
            _pin(pin, label)
            aggregate_review_file(source_dir, pin, label)

        records = review['records']
        if not isinstance(records, list) or not records:
            raise ValueError(f'manualPointReview for {provider_id} records must be a non-empty list')
        common_keys = {'id', 'officialCode', 'mode', 'expectedBeforeMetadata',
                       'expectedCuration', 'point', 'expectedNearestId'}
        for record in records:
            if not isinstance(record, dict):
                raise ValueError(f'manualPointReview for {provider_id} has non-object record')
            record_id = _require_string(record.get('id'), 'record id')
            if record_id in seen_record_ids:
                raise ValueError(f'duplicate manual point record id {record_id}')
            seen_record_ids.add(record_id)
            mode = record.get('mode')
            if mode == 'previous_metadata':
                allowed_keys = set(common_keys)
            elif mode == 'existing_source_point':
                allowed_keys = set(common_keys)
                allowed_keys.add('sourcePoint')
            else:
                raise ValueError(f'record {record_id} has unsupported mode')
            if 'nameAssociation' in record:
                if mode != 'existing_source_point':
                    raise ValueError(f'record {record_id} cannot have nameAssociation')
                allowed_keys.add('nameAssociation')
            if set(record) != allowed_keys:
                raise ValueError(f'record {record_id} has unexpected keys')

            official_code = record['officialCode']
            if not isinstance(official_code, str) or len(official_code) != 6 or not official_code.isascii() or not official_code.isdigit():
                raise ValueError(f'record {record_id} officialCode must be non-empty')
            lat, lng = _lat_lng(record['point'], f'record {record_id} point')
            expected_nearest = record['expectedNearestId']
            if type(expected_nearest) is not int or expected_nearest <= 0:
                raise ValueError(f'record {record_id} expectedNearestId must be an int')

            baseline_feature = baseline_by_id.get(record_id)
            if baseline_feature is None:
                raise ValueError(f'record {record_id} missing baseline feature')
            if record['expectedBeforeMetadata'] != baseline_feature:
                raise ValueError(f'record {record_id} expectedBeforeMetadata mismatch')
            if baseline_feature.get('kind') != 'sector' or baseline_feature.get('hasBoundary') is not True:
                raise ValueError(f'record {record_id} baseline feature must be a boundary sector')

            original_sector = original_sources.get(record_id)
            effective_sector = effective_sources.get(record_id)
            if not isinstance(original_sector, dict) or not isinstance(effective_sector, dict):
                raise ValueError(f'record {record_id} missing original/effective sector')
            if original_sector.get('kind') != 'sector' or effective_sector.get('kind') != 'sector':
                raise ValueError(f'record {record_id} original/effective source must be sector')
            if effective_sector.get('sourceId') != provider_id:
                raise ValueError(f'record {record_id} effective source provider mismatch')
            accepted = [r for r in provider['records'] if r['id'] == record_id]
            if len(accepted) != 1 or accepted[0]['action'] != 'replace' or accepted[0]['officialCode'] != official_code:
                raise ValueError(f'record {record_id} needs an accepted replacement in this provider')
            if (original_sector['tags'].get('ref:tn:codegeo') != official_code
                    or effective_sector['tags'].get('ref:tn:codegeo') != official_code):
                raise ValueError(f'record {record_id} original/effective officialCode mismatch')

            actual_curation = curation.get(record_id)
            if actual_curation != record['expectedCuration']:
                raise ValueError(f'record {record_id} curation mismatch')
            if isinstance(actual_curation, dict):
                if actual_curation.get('action') == 'exclude':
                    raise ValueError(f'record {record_id} sector is excluded')
                for key in ('nameTags', 'manualPointId'):
                    if key in actual_curation:
                        raise ValueError(f'record {record_id} sector curation has {key}')

            _covers(effective_sector.get('shape'), lat, lng,
                    f'record {record_id} effective shape')

            if mode == 'previous_metadata':
                baseline_lat = _coord(baseline_feature.get('lat'), f'record {record_id} baseline lat')
                baseline_lng = _coord(baseline_feature.get('lng'), f'record {record_id} baseline lng')
                if lat != baseline_lat or lng != baseline_lng:
                    raise ValueError(f'record {record_id} previous_metadata point mismatch')
                _covers(original_sector.get('shape'), lat, lng,
                        f'record {record_id} original shape')
            else:
                source_point = record['sourcePoint']
                if not isinstance(source_point, dict) or set(source_point) != {'id', 'kind', 'tags', 'lat', 'lng'}:
                    raise ValueError(f'record {record_id} sourcePoint has unexpected schema')
                donor_id = _require_string(source_point['id'], f'record {record_id} sourcePoint.id')
                donor_kind = _require_string(source_point['kind'], f'record {record_id} sourcePoint.kind')
                if donor_kind not in ('village', 'town', 'suburb', 'neighbourhood', 'hamlet'):
                    raise ValueError(f'record {record_id} sourcePoint.kind is not an acceptable place kind')
                if not isinstance(source_point['tags'], dict):
                    raise ValueError(f'record {record_id} sourcePoint.tags must be an object')
                sp_lat = _coord(source_point['lat'], f'record {record_id} sourcePoint.lat')
                sp_lng = _coord(source_point['lng'], f'record {record_id} sourcePoint.lng')
                if not (-90.0 <= sp_lat <= 90.0) or not (-180.0 <= sp_lng <= 180.0):
                    raise ValueError(f'record {record_id} sourcePoint coordinates out of range')
                if sp_lat != lat or sp_lng != lng:
                    raise ValueError(f'record {record_id} sourcePoint point mismatch')

                donor_original = original_sources.get(donor_id)
                donor_effective = effective_sources.get(donor_id)
                if not isinstance(donor_original, dict) or not isinstance(donor_effective, dict):
                    raise ValueError(f'record {record_id} donor missing original/effective source')
                if donor_original.get('kind') != donor_kind or donor_effective.get('kind') != donor_kind:
                    raise ValueError(f'record {record_id} donor kind mismatch')
                if donor_original.get('tags') != source_point['tags']:
                    raise ValueError(f'record {record_id} donor original tags mismatch')
                if donor_effective.get('tags') != source_point['tags']:
                    raise ValueError(f'record {record_id} donor effective tags mismatch')
                if donor_original.get('point') is None or donor_effective.get('point') is None:
                    raise ValueError(f'record {record_id} donor missing point')
                donor_original_lat, donor_original_lng = _source_coords(
                    donor_original['point'], f'record {record_id} donor original point')
                donor_effective_lat, donor_effective_lng = _source_coords(
                    donor_effective['point'], f'record {record_id} donor effective point')
                if (donor_original_lat, donor_original_lng) != (sp_lat, sp_lng):
                    raise ValueError(f'record {record_id} donor original point mismatch')
                if (donor_effective_lat, donor_effective_lng) != (sp_lat, sp_lng):
                    raise ValueError(f'record {record_id} donor effective point mismatch')
                if donor_id in baseline_by_id:
                    raise ValueError(f'record {record_id} donor already present in baselineMetadata')
                donor_curation = curation.get(donor_id)
                if isinstance(donor_curation, dict):
                    if donor_curation.get('action') == 'exclude' or 'nameTags' in donor_curation:
                        raise ValueError(f'record {record_id} donor curation has exclude/nameTags')

                source_primary = _primary_name(source_point['tags'], f'record {record_id} donor')
                effective_primary = _primary_name(effective_sector.get('tags'), f'record {record_id} effective sector')
                names_differ = norm(source_primary) != norm(effective_primary)
                if names_differ:
                    if 'nameAssociation' not in record:
                        raise ValueError(f'record {record_id} needs nameAssociation')
                    association = record['nameAssociation']
                    if not isinstance(association, dict) or set(association) != {
                            'sectorPrimary', 'pointPrimary', 'claim', 'review'}:
                        raise ValueError(f'record {record_id} nameAssociation has unexpected schema')
                    if association['sectorPrimary'] != effective_primary:
                        raise ValueError(f'record {record_id} nameAssociation sectorPrimary mismatch')
                    if association['pointPrimary'] != source_primary:
                        raise ValueError(f'record {record_id} nameAssociation pointPrimary mismatch')
                    if association['claim'] != 'manual_representative_only':
                        raise ValueError(f'record {record_id} nameAssociation claim mismatch')
                    review_pin_label = f'record {record_id} nameAssociation.review'
                    _pin(association['review'], review_pin_label)
                    aggregate_review_file(source_dir, association['review'], review_pin_label)
                elif 'nameAssociation' in record:
                    raise ValueError(f'record {record_id} has unnecessary nameAssociation')

            if not timetables:
                raise ValueError('timetables must be non-empty')
            nearest = min(timetables, key=lambda d: (distance(lat, lng, d), d['id']))['id']
            if nearest != expected_nearest:
                raise ValueError(f'record {record_id} expectedNearestId mismatch')

            choices[record_id] = Point(lng, lat)
            row = {
                'id': record_id,
                'sourceId': provider_id,
                'mode': mode,
                'lat': lat,
                'lng': lng,
                'delegationId': nearest,
            }
            if mode == 'existing_source_point':
                row['pointId'] = record['sourcePoint']['id']
            report_rows.append(row)

    return choices, report_rows
def build(pbf, output, report_dir, municipal_manifest=MUNICIPAL_MANIFEST,
          curation_manifest=CURATION_MANIFEST, prayer_source_coordinates=PRAYER_SOURCE_COORDINATES,
          reviewed_boundaries=REVIEWED_BOUNDARIES, reviewed_picker_groups=REVIEWED_PICKER_GROUPS, final_exclusions=FINAL_EXCLUSIONS, reviewed_catalog_updates=REVIEWED_CATALOG_UPDATES,
          reviewed_manual_points=REVIEWED_MANUAL_POINTS):
    runtime_governors = json.loads((ASSETS/'gouvernorats.json').read_text(encoding='utf-8'))['gouvernorats']
    prayer_source_coordinate_report = validate_prayer_source_coordinates(runtime_governors, prayer_source_coordinates)
    source_sha256 = hashlib.sha256(pbf.read_bytes()).hexdigest()
    selection_context = load_prayer_selection_successor(
        prayer_source_coordinates, runtime_governors, ASSETS, source_sha256,
        {'municipalSources': municipal_manifest, 'curation': curation_manifest,
         'reviewedBoundaries': reviewed_boundaries, 'reviewedPickerGroups': reviewed_picker_groups})
    # Existing source proofs describe frozen display identity. Current prayer
    # defaults are independently verified and recomputed before asset writes.
    governors = selection_context['baselineGovernors'] if selection_context else runtime_governors
    timetables, rejected_timetables = available_timetables(governors, ASSETS)
    areas, nodes, errors = extract(pbf)
    original_picker_sources = {obj['id']: {'tags': dict(obj['tags']), 'kind': obj['kind'],
                                           'shape': obj.get('shape'), 'point': obj.get('point')}
                               for obj in areas + nodes}
    curation, curation_targets, curation_report = load_catalog_curation(
        curation_manifest, source_sha256, areas, nodes)
    classification_ids = {identifier for identifier, rule in curation.items()
                          if rule.get('action') == 'reviewed_locality_classification'}
    if classification_ids:
        boundary_manifest = json.loads(Path(reviewed_boundaries).read_bytes())
        boundary_ids = {record['id'] for source in boundary_manifest['sources']
                        for record in source['records']}
        if classification_ids & boundary_ids:
            raise ValueError('Reviewed locality classification target is also a reviewed boundary record')
    apply_reviewed_locality_classifications(areas + nodes, curation, original_picker_sources)
    areas, official_sources, official_contexts, official_targets, official_report = load_reviewed_boundaries(
        reviewed_boundaries, areas, source_sha256)
    retained_point_choices = load_reviewed_point_retentions(
        curation_manifest, curation, original_picker_sources, {obj['id']: obj for obj in areas + nodes},
        official_sources, official_contexts, source_sha256)
    official_manual_points, official_manual_point_report = load_reviewed_official_manual_points(
        reviewed_boundaries, source_sha256, original_picker_sources,
        {obj['id']: obj for obj in areas + nodes}, curation, timetables)
    if official_manual_point_report:
        official_report['manualPointChoices'] = official_manual_point_report
    context_targets = {**curation_targets, **official_targets}
    curation_applications = [
        {'id': identifier, 'decisionId': identifier, 'action': 'reviewed_locality_classification',
         'originalKind': 'sector', 'effectiveKind': 'locality', 'inherited': False,
         'rejectedAdministrativeCode': curation[identifier]['expectedTags']['ref:tn:codegeo'],
         'reviewEvidenceSha256': curation[identifier]['reviewEvidence']['sha256']}
        for identifier in sorted(classification_ids)]
    # Validate every decision against untouched extracted tags first. Apply name
    # metadata before constructing parent indexes, aliases or picker groups so
    # children and all other consumers see the same reviewed identity.
    for obj in areas + nodes:
        decision = curation.get(obj['id'], {})
        if 'nameTags' in decision:
            if obj['id'] in official_contexts:
                raise ValueError('Official boundary identity and name curation need a joint review')
            obj['tags'] = {key: value for key, value in obj['tags'].items() if not is_current_name_tag(key)}
            obj['tags'].update(decision['nameTags'])
            curation_applications.append({'id': obj['id'], 'decisionId': obj['id'],
                                          'action': 'name_tags', 'inherited': False})
    excluded_ids = {identifier for identifier, rule in curation.items() if rule['action'] == 'exclude'}
    areas = [area for area in areas if area['id'] not in excluded_ids]
    nodes = [node for node in nodes if node['id'] not in excluded_ids]
    point_references = {node['id']: node for node in nodes}
    country = next(a['shape'] for a in areas if a['kind'] == 'country')
    municipal_areas, municipal_sources = load_municipal_sources(municipal_manifest)
    if set(municipal_sources) & (set(official_sources) | {'osm'}):
        raise ValueError('Boundary source IDs must be unique across providers')
    # Municipal input must actually be inside Tunisia; clipping an unrelated
    # coordinate system would conceal a broken source file.
    for area in municipal_areas:
        if not country.covers(area['shape']):
            raise ValueError(f"Municipal polygon outside Tunisia: {area['id']}")
    areas.extend(municipal_areas)
    governor_areas = [a for a in areas if a['kind'] == 'governorate']
    assert len(governor_areas) == 24, 'Expected all 24 governorates'
    for area in governor_areas:
        target = norm(names(area['tags'])[0].removeprefix('ولاية '))
        area['governorateId'] = next(g['id'] for g in governors if norm(g['nomAr']) == target)
    governor_tree = STRtree([a['shape'] for a in governor_areas])
    parent_areas = [a for a in areas if a['kind'] in ('delegation', 'sector')]
    parent_tree = STRtree([a['shape'] for a in parent_areas])
    administrative_delegations = [area for area in parent_areas if area['kind'] == 'delegation']
    features, geometries, missing_governor = [], [], []
    base_picker_groups = {}
    reviewed_point_base_names = {}

    def metadata(obj, point, geometry=None):
        decision = official_contexts.get(obj['id']) or curation.get(obj['id'], {})
        candidates = [governor_areas[i] for i in governor_tree.query(point) if governor_areas[i]['shape'].covers(point)]
        # Compare source polygons to source administrative boundaries. Using the
        # quantized copy here could create tiny artificial containment failures.
        footprint = obj.get('shape', point)
        parents = [parent_areas[i] for i in parent_tree.query(footprint)]
        context_decision = decision if decision.get('action') == 'administrative_context' else {}
        if not context_decision and obj['kind'] != 'sector':
            containing_sectors = [parent for parent in parents
                                  if parent['kind'] == 'sector' and parent['shape'].covers(footprint)]
            if len(containing_sectors) == 1:
                inherited = official_contexts.get(containing_sectors[0]['id']) or curation.get(containing_sectors[0]['id'], {})
                if inherited.get('action') == 'administrative_context':
                    context_decision = inherited
        governor_id = decision.get('governorateId') or context_decision.get('governorateId')
        if not candidates and not governor_id:
            missing_governor.append(obj['id'])
            return None
        gov = (context_targets[governor_id] if governor_id
               else min(candidates, key=lambda area: (area['shape'].area, area['id'])))
        parent_name, context_aliases, delegations = administrative_context(
            obj, footprint, gov, parents, administrative_delegations,
            context_targets.get(context_decision.get('delegationId')), context_decision.get('parentNames'))
        if context_decision:
            curation_applications.append({'id': obj['id'], 'decisionId': context_decision['id'],
                                          'inherited': context_decision['id'] != obj['id']})
        aliases = names(obj['tags'])
        nearest = min(timetables, key=lambda d: (distance(point.y, point.x, d), d['id']))
        result = {'id': obj['id'], 'sourceId': obj['sourceId'], 'name': aliases[0], 'aliases': aliases[1:], 'kind': obj['kind'],
                  'parentName': parent_name, 'contextAliases': context_aliases, 'governorateId': gov['governorateId'],
                  'lat': round(point.y, 7), 'lng': round(point.x, 7),
                  'delegationId': nearest['id'], 'hasBoundary': geometry is not None}
        if geometry is not None:
            result['bbox'] = list(geometry.bounds)
            # Manual reference corrections must not change geometric area ranks.
            result['areaKm2'] = geometry.area * 111.32**2 * math.cos(math.radians(geometry.representative_point().y))
        governor = next(governor for governor in governors if governor['id'] == gov['governorateId'])
        base_group = matching_base_delegation(result, footprint, delegations, governor)
        reviewed_base = decision.get('pickerBaseDelegationId')
        if reviewed_base:
            # Some original timetable coordinates were explicitly corrected to
            # an existing catalog point. Keep one choice for that same point.
            reference = next((r for r in prayer_source_coordinate_report['settlementReferences']
                              if r['delegationId'] == reviewed_base), None)
            target = next((d for d in governor['delegations'] if d['id'] == reviewed_base), None)
            name_identity_review = None
            if 'pickerBaseNameReview' in decision:
                name_identity_review = validate_reviewed_point_base_name(
                    decision, curation_manifest, prayer_source_coordinates, obj, original_picker_sources,
                    {source['id']: source for source in areas + nodes}, result, target, governor, source_sha256)
                reviewed_point_base_names[obj['id']] = name_identity_review
            elif 'pickerBaseLegacyNameReview' in decision:
                name_identity_review = validate_reviewed_legacy_point_base_name(
                    decision, curation_manifest, prayer_source_coordinates, obj, original_picker_sources,
                    {source['id']: source for source in areas + nodes}, result, target, governor, source_sha256, curation)
                reviewed_point_base_names[obj['id']] = name_identity_review
            if (geometry is not None or reference is None or target is None
                    or reference['pointId'] != obj['id'] or reference['governorateId'] != gov['governorateId']
                    or reference['lat'] != point.y or reference['lng'] != point.x
                    or target['lat'] != point.y or target['lng'] != point.x
                    or (norm(aliases[0]) != norm(target['nomAr']) and name_identity_review is None)
                    or result['delegationId'] != reviewed_base
                    or (base_group is not None and base_group != f'delegation:{reviewed_base}')):
                raise ValueError(f'Reviewed original-source picker point needs review: {obj["id"]}')
            base_group = f'delegation:{reviewed_base}'
            curation_applications.append({'id': obj['id'], 'decisionId': obj['id'],
                                          'action': 'picker_base', 'pickerGroupId': base_group,
                                          'inherited': False})
        if 'pickerBaseDisplayReview' in decision:
            display_review = validate_reviewed_point_base_display(
                decision, curation_manifest, prayer_source_coordinates, obj, original_picker_sources,
                {source['id']: source for source in areas + nodes}, result, governor, timetables, source_sha256, curation,
                reviewed_boundaries, official_report, reviewed_picker_groups)
            display_group = display_review['exactPair']['basePickerId']
            if geometry is not None or (base_group is not None and base_group != display_group):
                raise ValueError('Reviewed point/base display association conflicts with another source choice')
            reviewed_point_base_names[obj['id']] = display_review
            if any(key in display_review for key in (
                    'deferredCompleteGroupExtension', 'sectorOwnedSettlementPreservation')):
                if base_group is not None:
                    raise ValueError('Reviewed deferred point already has an early base owner')
            else:
                base_group = display_group
                curation_applications.append({'id': obj['id'], 'decisionId': obj['id'],
                                              'action': 'picker_base_display', 'pickerGroupId': base_group,
                                              'inherited': False})
        if base_group:
            base_picker_groups[result['id']] = base_group
        return result

    for obj in sorted(areas, key=lambda a: a['id']):
        if obj['kind'] in ('country', 'governorate', 'delegation'): continue
        geometry = obj['shape']
        if not country.intersects(geometry): continue
        if not country.covers(geometry): geometry = geometry.intersection(country)
        if geometry.geom_type not in ('Polygon', 'MultiPolygon') or geometry.is_empty:
            errors.append({'id': obj['id'], 'reason': 'No polygon inside Tunisia'})
            continue
        # Uniform quantization to ~0.1 m; no buffers, Voronoi cells or invented borders.
        geometry = set_precision(geometry, 1 / SCALE)
        if geometry.is_empty:
            errors.append({'id': obj['id'], 'reason': 'Collapsed at coordinate precision'})
            continue
        point = geometry.representative_point()
        if obj['id'] in official_manual_points:
            point = official_manual_points[obj['id']]
        manual_point_id = curation.get(obj['id'], {}).get('manualPointId')
        if manual_point_id:
            reference = point_references.get(manual_point_id)
            if (reference is None
                    or norm(names(reference['tags'])[0]) != norm(names(obj['tags'])[0])
                    or not obj['shape'].covers(reference['point'])
                    or not geometry.covers(reference['point'])):
                raise ValueError(f'Reviewed manual point no longer matches its sector: {obj["id"]}')
            # Use the reviewed settlement coordinate for a manual choice. The
            # actual GPS lookup still uses its device fix and unchanged polygon.
            point = reference['point']
        feature = metadata(obj, point, geometry)
        if feature:
            if manual_point_id:
                feature['manualPointId'] = manual_point_id
                curation_applications.append({'id': obj['id'], 'decisionId': obj['id'],
                                              'action': 'manual_point', 'pointId': manual_point_id,
                                              'inherited': False})
            features.append(feature); geometries.append(geometry)

    # Known point-only places remain searchable, but never claim GPS containment.
    tree = STRtree(geometries)
    point_only = []
    for obj in sorted(nodes, key=lambda n: n['id']):
        point = obj['point']
        if not country.covers(point): continue
        aliases = names(obj['tags'])
        point_identity = {'name': aliases[0], 'aliases': aliases[1:]}
        matching = [i for i in tree.query(point) if geometries[i].covers(point)
                    and same_picker_name(point_identity, features[i])]
        # A retained point's reviewed historical aliases stay on that point.
        # Copying its former generic name to a newly named official sector can
        # bridge two distinct sectors across a source-registration overlap.
        reviewed_named_point = ('pointRetentionReview' in curation.get(obj['id'], {})
                                and 'nameTags' in curation[obj['id']])
        if matching and not reviewed_named_point:
            feature = features[min(matching, key=lambda i: features[i]['areaKm2'])]
            feature['aliases'] = sorted(set(feature['aliases'] + aliases) - {feature['name']})
        # A reviewed, previously selectable point keeps its ID and coordinate
        # after its containing sector is corrected. Picker grouping still joins
        # matching labels, without losing saved selections or point-based maps.
        preserve_point = curation.get(obj['id'], {}).get('action') == 'preserve_point'
        if not matching or preserve_point:
            feature = metadata(obj, point)
            if feature:
                point_only.append(feature)
                if preserve_point:
                    curation_applications.append({'id': obj['id'], 'decisionId': obj['id'],
                                                  'action': 'preserve_point', 'inherited': False})

    distinct_picker_pairs, distinct_picker_report = load_reviewed_distinct_picker_pairs(
        reviewed_picker_groups, features + point_only, original_picker_sources,
        {obj['id']: obj for obj in areas + nodes}, source_sha256)
    picker_groups = assign_picker_groups(
        features + point_only, geometries, base_picker_groups,
        source_sectors=[area for area in areas if area['kind'] == 'sector'],
        source_footprints={obj['id']: obj.get('shape', obj.get('point')) for obj in areas + nodes},
        blocked_pairs=distinct_picker_pairs)
    picker_groups, reviewed_picker_report = apply_reviewed_picker_groups(
        reviewed_picker_groups, features + point_only, geometries, governors, timetables,
        original_picker_sources, {obj['id']: obj for obj in areas + nodes}, source_sha256, curation,
        reviewed_boundaries, official_report)
    reviewed_locality_report = reviewed_picker_report.pop('localityGroups', None)
    deferred_hamlets = reviewed_locality_report.pop('_deferredSurveyedHamlets', []) if reviewed_locality_report else []
    reviewed_city_report = reviewed_picker_report.pop('cityDisplayAssociations', None)
    curation_applications.extend(apply_reviewed_locality_contexts(
        curation_manifest, curation, features + point_only, geometries, original_picker_sources,
        {obj['id']: obj for obj in areas + nodes}, source_sha256, country))
    if deferred_hamlets:
        picker_groups = apply_deferred_reviewed_hamlet_groups(
            reviewed_picker_groups, deferred_hamlets, features + point_only, geometries, timetables,
            original_picker_sources, {obj['id']: obj for obj in areas + nodes}, curation, source_sha256)
    reviewed_residential_report, residential_picker_groups = apply_reviewed_residential_display_associations(
        reviewed_picker_groups, features + point_only, geometries, governors, timetables,
        original_picker_sources, {obj['id']: obj for obj in areas + nodes}, source_sha256, curation,
        reviewed_boundaries, official_report)
    if reviewed_residential_report is not None:
        picker_groups = residential_picker_groups
    sector_owned_pending = any('sectorOwnedSettlementPreservation' in proposal
                               for proposal in reviewed_point_base_names.values())
    if sector_owned_pending:
        reserve_sector_owned_settlement_proposals(
            reviewed_point_base_names, reviewed_picker_groups, reviewed_picker_report, reviewed_city_report,
            reviewed_residential_report, deferred_hamlets, features + point_only, geometries, governors,
            base_picker_groups, curation_applications,
            original_sources=original_picker_sources,
            effective_sources={obj['id']: obj for obj in areas + nodes},
            reviewed_boundaries=reviewed_boundaries, official_report=official_report)
    extended_picker_groups = apply_deferred_point_base_group_extensions(
        reviewed_point_base_names, reviewed_picker_groups, reviewed_picker_report, features + point_only,
        geometries, governors, base_picker_groups, curation_applications,
        original_sources=original_picker_sources,
        effective_sources={obj['id']: obj for obj in areas + nodes},
        reviewed_boundaries=reviewed_boundaries, official_report=official_report)
    if extended_picker_groups is not None:
        picker_groups = extended_picker_groups
    if sector_owned_pending:
        sector_owned_picker_groups = apply_sector_owned_settlement_proposals(
            reviewed_point_base_names, features + point_only, geometries, base_picker_groups, curation_applications)
        if sector_owned_picker_groups is not None:
            picker_groups = sector_owned_picker_groups
    verify_reviewed_city_display_associations(features + point_only, reviewed_residential_report)
    verify_reviewed_point_choices(retained_point_choices, features + point_only)
    verify_distinct_picker_groups(features + point_only, distinct_picker_pairs, distinct_picker_report,
                                  residential_report=reviewed_residential_report)
    verify_reviewed_city_display_associations(features + point_only, reviewed_city_report)
    verify_reviewed_city_display_associations(
        features + point_only, reviewed_picker_report.get('splitSettlementReferences'))
    verify_reviewed_city_display_associations(
        features + point_only, reviewed_picker_report.get('retainedDisplayReferences'))
    verify_reviewed_city_display_associations(
        features + point_only, (reviewed_locality_report or {}).get('completeGroupOwners'))
    verify_reviewed_point_base_names(
        reviewed_point_base_names, features + point_only, geometries, base_picker_groups)
    saved_replacements = reviewed_saved_replacements(
        curation_manifest, curation, original_picker_sources, {obj['id']: obj for obj in areas + nodes},
        official_sources, official_contexts, features + point_only, source_sha256)
    output.mkdir(parents=True, exist_ok=True)
    report_dir.mkdir(parents=True, exist_ok=True)
    blob = bytearray(b'NPOL' + struct.pack('>i', 1))

    def write_geometry(geometry):
        offset = len(blob)
        blob.extend(packed_geometry_bytes(geometry))
        return {'offset': offset, 'length': len(blob)-offset}

    cells = defaultdict(list)
    for index, (feature, geometry) in enumerate(zip(features, geometries)):
        feature.update(write_geometry(geometry))
        lo_x, lo_y, hi_x, hi_y = feature['bbox']
        for y in range(math.floor(lo_y/GRID), math.floor(hi_y/GRID)+1):
            for x in range(math.floor(lo_x/GRID), math.floor(hi_x/GRID)+1):
                cells[f'{y}:{x}'].append(index)
    country_record = write_geometry(set_precision(country, 1/SCALE))
    source = {'provider': 'OpenStreetMap contributors / Geofabrik', 'license': 'ODbL-1.0',
              'url': 'https://download.geofabrik.de/africa/' + pbf.name,
              'sha256': source_sha256,
              'timestamp': osmium.io.Reader(str(pbf)).header().get('osmosis_replication_timestamp')}
    sources = {'osm': source, **municipal_sources, **official_sources}
    conflicts = detect_conflicts(features, geometries)
    verify_reviewed_locality_classifications(
        curation_manifest, features, point_only, geometries, conflicts, curation)
    result = {'schemaVersion': 1, 'coordinateScale': SCALE, 'gridSize': GRID,
              'source': source, 'sources': sources, 'conflicts': conflicts,
              'retiredLocalityIds': sorted(excluded_ids),
              'country': country_record, 'cells': dict(sorted(cells.items())),
              'features': features + point_only}
    selection_report = finalize_prayer_selection_successor(selection_context, result, blob)
    # Historical source proofs above remain intact. Explicit later facility
    # exclusions change only the exported catalog after those checks pass.
    result, selection_report, final_exclusion_report = apply_final_exclusions(
        result, blob, selection_report, final_exclusions)
    # Current spelling and parent-label reviews run after all historical proofs.
    # Only textual updates are enabled here; boundary adoption needs a separate
    # geographic and prayer-selection review before this scope can be widened.
    catalog_update_report = None
    if reviewed_catalog_updates is not None and Path(reviewed_catalog_updates).exists():
        spec = importlib.util.spec_from_file_location(
            'reviewed_catalog_updates', Path(__file__).with_name('reviewed_catalog_updates.py'))
        update_module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(update_module)
        current_timetables, _ = available_timetables(runtime_governors, ASSETS)
        original_result = json.loads(json.dumps(result, ensure_ascii=False))
        original_blob = bytes(blob)
        result, blob, catalog_update_report = update_module.apply_reviewed_updates(
            result, original_blob, reviewed_catalog_updates, current_timetables,
            pack_geometry=packed_geometry_bytes, detect_conflicts=detect_conflicts, distance=distance)
        if bytes(blob) != original_blob or set(result) != set(original_result):
            raise ValueError('Reviewed catalog naming updates changed geometry or schema')
        if any(result[key] != original_result[key] for key in result if key != 'features'):
            raise ValueError('Reviewed catalog naming updates changed non-text catalog state')
        if len(result['features']) != len(original_result['features']):
            raise ValueError('Reviewed catalog naming updates changed feature count')
        text_fields = {'name', 'aliases', 'parentName', 'contextAliases'}
        for before, after in zip(original_result['features'], result['features']):
            if ({k: v for k, v in before.items() if k not in text_fields}
                    != {k: v for k, v in after.items() if k not in text_fields}):
                raise ValueError('Reviewed catalog naming updates changed identity or spatial data')
    reviewed_manual_points_report = None
    if reviewed_manual_points is not None and Path(reviewed_manual_points).exists():
        manual_module_spec = importlib.util.spec_from_file_location(
            'reviewed_manual_points', Path(__file__).with_name('reviewed_manual_points.py'))
        manual_module = importlib.util.module_from_spec(manual_module_spec)
        manual_module_spec.loader.exec_module(manual_module)
        manual_timetables, _ = available_timetables(runtime_governors, ASSETS)
        manual_geometry_by_id = {feature['id']: geometry for feature, geometry in zip(features, geometries)}
        result, reviewed_manual_points_report = manual_module.apply_reviewed_manual_points(
            result, blob, reviewed_manual_points, manual_timetables, manual_geometry_by_id, distance=distance)
    if reviewed_manual_points_report is not None and selection_report is not None:
        # Keep the exported current manual-selection table aligned with the pins.
        # Historical source-selection deltas still describe their original run.
        selections = {row['id']: row for row in selection_report['currentManualSelections']}
        for change in reviewed_manual_points_report['changedPins']:
            row = selections.get(change['id'])
            if (row is None or row['representativeId'] != change['id']
                    or row['sourceId'] != change.get('beforeDelegationId', change['delegationId'])
                    or {key: row[key] for key in ('lat', 'lng')} != change['before']):
                raise ValueError('Manual pin correction differs from current selection')
            row.update(change['after'])
            row['sourceId'] = change['delegationId']
        selection_report['qualification'] += ' reviewedManualPoints updates current manual-representative pins and any explicit representative source amendment; historical source-change rows retain their original review context.'
    removed = set(final_exclusion_report['excludedIds'])
    if any(row['replacementId'] in removed for row in saved_replacements):
        raise ValueError('Final exclusion is a saved replacement target')
    kept_polygons = [(f, g) for f, g in zip(features, geometries) if f['id'] not in removed]
    geometries = [g for _, g in kept_polygons]
    features = [f for f in result['features'] if f['hasBoundary']]
    point_only = [f for f in result['features'] if not f['hasBoundary']]
    excluded_ids.update(removed)
    conflicts = result['conflicts']
    (output/'neighborhoods.bin').write_bytes(blob)
    (output/'neighborhoods.json').write_text(json.dumps(result, ensure_ascii=False, separators=(',', ':'))+'\n', encoding='utf-8', newline='\n')
    # Preference cleanup runs before the larger picker catalog is preloaded.
    # Keep this generated index tiny instead of parsing all polygon metadata.
    reviewed_name_ids = ({identifier for identifier, rule in curation.items() if 'nameTags' in rule}
                         | {identifier for identifier, rule in curation.items()
                            if rule.get('action') == 'reviewed_locality_classification'}
                         | set(official_contexts))
    if catalog_update_report is not None:
        reviewed_name_ids.update(catalog_update_report['renamedIds'])
    reviewed_names = [{'id': feature['id'], 'name': feature['name'], 'kind': feature['kind']}
                      for feature in sorted(features + point_only, key=lambda item: item['id'])
                      if feature['id'] in reviewed_name_ids]
    if reviewed_residential_report is not None:
        for reviewed_base_name in reviewed_residential_report['reviewedBaseNames']:
            identifier = reviewed_base_name['id']
            if identifier in excluded_ids or any(row['id'] == identifier for row in reviewed_names):
                raise ValueError('Repeated or retired reviewed base saved-name ID')
            reviewed_names.append(reviewed_base_name)
    saved_updates = {'schemaVersion': 1, 'retiredLocalityIds': sorted(excluded_ids), 'reviewedNames': reviewed_names}
    if saved_replacements:
        saved_updates['replacements'] = saved_replacements
    (output/'retired-localities.json').write_text(json.dumps(
        saved_updates,
        ensure_ascii=False, separators=(',', ':'))+'\n', encoding='utf-8', newline='\n')
    by_id = {f['id']: f for f in features}
    report = {'source': source, 'sources': sources, 'polygonCount': len(features), 'pointOnlyCount': len(point_only),
              'curation': curation_report,
              'finalExclusions': final_exclusion_report,
              'curationApplications': curation_applications,
              'reviewedLocalityReplacements': saved_replacements,
              'prayerSourceCount': len(timetables), 'rejectedPrayerSources': rejected_timetables,
              'prayerSourceCoordinates': prayer_source_coordinate_report,
              'reviewedBoundaries': official_report,
              'reviewedPickerGroups': reviewed_picker_report,
              'pickerGroupCount': len({feature['pickerGroupId'] for feature in features + point_only}),
              'pickerDuplicateGroups': picker_groups,
              'polygonsBySource': dict(Counter(f['sourceId'] for f in features)),
              'conflictCount': len(conflicts),
              'conflictsByReason': dict(Counter(c['reason'] for c in conflicts)),
              'municipalInternalConflictCount': sum(all(by_id[i]['sourceId'] in municipal_sources for i in c['ids']) for c in conflicts),
              'conflicts': [{**c, 'names': [by_id[i]['name'] for i in c['ids']]} for c in conflicts],
              'polygonsByKind': dict(Counter(f['kind'] for f in features)),
              'polygonsByGovernorate': {g['nomAr']: sum(f['governorateId'] == g['id'] for f in features) for g in governors},
              'rejected': errors, 'missingGovernorate': missing_governor,
              'geometryBytes': len(blob), 'metadataBytes': (output/'neighborhoods.json').stat().st_size,
              'pointOnlyPlaces': [{k: f[k] for k in ('id', 'name', 'kind', 'lat', 'lng')} for f in point_only]}
    if reviewed_locality_report is not None:
        report['reviewedLocalityGroups'] = reviewed_locality_report
    if distinct_picker_report is not None:
        report['reviewedDistinctPickerPairs'] = distinct_picker_report
    if reviewed_city_report is not None:
        report['reviewedCityDisplayAssociations'] = reviewed_city_report
    if reviewed_residential_report is not None:
        report['reviewedResidentialDisplayAssociations'] = reviewed_residential_report
    if catalog_update_report is not None:
        report['reviewedCatalogUpdates'] = catalog_update_report
    if reviewed_manual_points_report is not None:
        report['reviewedManualPoints'] = reviewed_manual_points_report
    if selection_report is not None:
        report['prayerSelection'] = selection_report
        report['displayIdentityValidationContext'] = {
            'scope': 'Existing display proof reports describe their pinned historical identity and prayer-reference context. reviewedCatalogUpdates, when present, gives later textual corrections. reviewedManualPoints, when present, gives later manual representative pin corrections; those reports retain historical before-correction metadata hashes and are checked separately. Geometry, IDs and groups remain unchanged. Explicit reviewedManualPoints settlement choices may amend current manual prayer-selection source IDs; historical source-change reports retain their original context.',
            'baselineGovernors': selection_context['review']['baselineGovernors'],
            'baselineCoordinates': selection_context['review']['baselineCoordinates'],
            'historicalReportKeys': [key for key in report if key not in ('reviewedCatalogUpdates', 'reviewedManualPoints')
                                     and (key.startswith('reviewed') or key in ('curation', 'curationApplications'))]}
    (report_dir/'coverage.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')
    # Windows terminals may still use cp1252. Output files remain full UTF-8;
    # keep the console summary portable and omit detailed coordinate lists.
    print(json.dumps({k: v for k, v in report.items() if k not in ('pointOnlyPlaces', 'conflicts', 'pickerDuplicateGroups', 'curation', 'curationApplications')},
                     ensure_ascii=True, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('pbf', type=Path)
    parser.add_argument('--output', type=Path, default=ASSETS)
    parser.add_argument('--report-dir', type=Path, default=ROOT/'scripts/neighborhoods')
    parser.add_argument('--municipal-manifest', type=Path, default=MUNICIPAL_MANIFEST)
    parser.add_argument('--curation-manifest', type=Path, default=CURATION_MANIFEST)
    parser.add_argument('--prayer-source-coordinates', type=Path, default=PRAYER_SOURCE_COORDINATES)
    parser.add_argument('--reviewed-boundaries', type=Path, default=REVIEWED_BOUNDARIES)
    parser.add_argument('--reviewed-picker-groups', type=Path, default=REVIEWED_PICKER_GROUPS)
    parser.add_argument('--final-exclusions', type=Path, default=FINAL_EXCLUSIONS)
    parser.add_argument('--reviewed-catalog-updates', type=Path, default=REVIEWED_CATALOG_UPDATES)
    parser.add_argument('--reviewed-manual-points', type=Path, default=REVIEWED_MANUAL_POINTS)
    options = parser.parse_args()
    build(options.pbf, options.output, options.report_dir, options.municipal_manifest,
          options.curation_manifest, options.prayer_source_coordinates, options.reviewed_boundaries,
          options.reviewed_picker_groups, options.final_exclusions, options.reviewed_catalog_updates,
          options.reviewed_manual_points)
