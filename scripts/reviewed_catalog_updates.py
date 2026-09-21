"""Apply pinned text corrections after historical catalog validation.

Only names, aliases and existing-parent labels may change. Existing boundary
bytes, source defaults and picker identities remain exactly as validated.
"""
import copy
import hashlib
import json
from pathlib import Path

_SEP = (",", ":")

def json_bytes(obj):
    return (json.dumps(obj, ensure_ascii=False, separators=_SEP) + "\n").encode("utf-8")


def canonical_bytes(obj):
    return json.dumps(obj, ensure_ascii=False, sort_keys=True, separators=_SEP).encode("utf-8")


def object_sha256(obj):
    return hashlib.sha256(canonical_bytes(obj)).hexdigest()


def _sha(data):
    return hashlib.sha256(data).hexdigest()


def _path(directory, name):
    root = Path(directory).resolve()
    path = (root / name).resolve()
    if path == root or root not in path.parents:
        raise ValueError("path escapes directory")
    return path


def _pinned(directory, item):
    if not isinstance(item, dict) or set(item) != {"file", "sha256"}:
        raise ValueError("invalid pinned file")
    if not isinstance(item["file"], str) or not isinstance(item["sha256"], str):
        raise ValueError("invalid pinned file fields")
    data = _path(directory, item["file"]).read_bytes()
    if _sha(data) != item["sha256"]:
        raise ValueError("pinned sha256 mismatch")
    return data


def _validate_revision(revision):
    required = {"schemaVersion", "id", "baseMetadataSha256", "baseGeometrySha256", "evidence", "newSources", "updates", "scopeNote"}
    if not isinstance(revision, dict) or set(revision) != required or revision["schemaVersion"] != 1:
        raise ValueError("invalid revision schema")
    if not isinstance(revision["id"], str) or not revision["id"] or not isinstance(revision["scopeNote"], str):
        raise ValueError("invalid revision identity")
    for key in ("baseMetadataSha256", "baseGeometrySha256"):
        if not isinstance(revision[key], str) or len(revision[key]) != 64:
            raise ValueError("invalid revision hash")
    if not isinstance(revision["evidence"], list) or not isinstance(revision["newSources"], dict) or not isinstance(revision["updates"], list):
        raise ValueError("invalid revision collections")
    for item in revision["evidence"]:
        if not isinstance(item, dict) or set(item) != {"file", "sha256"}:
            raise ValueError("invalid evidence")


def _validate_update(update):
    if not isinstance(update, dict) or set(update) != {"id", "expectedFeatureSha256", "set", "geometry"}:
        raise ValueError("invalid update schema")
    if not isinstance(update["id"], str) or not update["id"] or not isinstance(update["expectedFeatureSha256"], str) or len(update["expectedFeatureSha256"]) != 64:
        raise ValueError("invalid update identity/hash")
    values = update["set"]
    if not isinstance(values, dict) or not set(values) <= {"name", "aliases", "parentName", "contextAliases", "sourceId"}:
        raise ValueError("invalid update fields")
    if "name" in values and (not isinstance(values["name"], str) or not values["name"].strip() or not any("\u0600" <= char <= "\u06ff" for char in values["name"])):
        raise ValueError("name must be nonblank Arabic")
    for key in ("aliases", "contextAliases"):
        if key in values:
            value = values[key]
            if not isinstance(value, list) or any(not isinstance(x, str) or not x.strip() for x in value) or len(value) != len(set(value)):
                raise ValueError("invalid string list")
    if "parentName" in values and (not isinstance(values["parentName"], str) or not values["parentName"].strip()):
        raise ValueError("invalid parentName")
    if "sourceId" in values and (not isinstance(values["sourceId"], str) or not values["sourceId"]):
        raise ValueError("invalid sourceId")
    geometry = update["geometry"]
    if geometry is not None and (not isinstance(geometry, dict) or set(geometry) != {"file", "sha256", "featureId"} or any(not isinstance(geometry[x], str) for x in geometry)):
        raise ValueError("invalid geometry spec")


def _metadata_hash(catalog):
    return _sha(json_bytes(catalog))


def _validate_root_review(review):
    keys = {"schemaVersion", "status", "revisionSha256", "afterMetadataSha256", "afterGeometrySha256"}
    if not isinstance(review, dict) or set(review) != keys or review.get("schemaVersion") != 1 or review.get("status") != "ACCEPTED_SCOPED_CATALOG_UPDATE":
        raise ValueError("invalid root review")
    if any(not isinstance(review[k], str) or len(review[k]) != 64 for k in keys - {"schemaVersion", "status"}):
        raise ValueError("invalid root review hash")


def derive_revision(catalog, blob, revision, directory, references, *, pack_geometry, detect_conflicts, distance):
    """Apply explicit text edits after the generator validates geometry and sources.

    Geometry support is intentionally disabled until neighboring-boundary and
    prayer-selection changes have their own accepted integration review.
    """
    if not isinstance(catalog, dict) or not isinstance(blob, bytes):
        raise ValueError('Invalid catalog/blob types')
    _validate_revision(revision)
    if _metadata_hash(catalog) != revision['baseMetadataSha256'] or _sha(blob) != revision['baseGeometrySha256']:
        raise ValueError('Base catalog hashes differ from review')
    if catalog.get('schemaVersion') != 1 or catalog.get('coordinateScale') != 1_000_000 or blob[:8] != b'NPOL\x00\x00\x00\x01':
        raise ValueError('Unsupported catalog format')
    if revision['newSources'] or not revision['updates']:
        raise ValueError('Text revisions require updates and cannot add sources')
    for item in revision['evidence']:
        _pinned(Path(directory), item)
    rows = catalog.get('features')
    if not isinstance(rows, list) or any(not isinstance(f, dict) or not isinstance(f.get('id'), str) for f in rows):
        raise ValueError('Invalid feature inventory')
    current = {f['id']: f for f in rows}
    if len(current) != len(rows):
        raise ValueError('Repeated feature ID')
    revised = copy.deepcopy(catalog)
    by_id = {f['id']: f for f in revised['features']}
    retired = set(catalog.get('retiredLocalityIds', []))
    changed, seen, renamed = {}, set(), []
    for update in revision['updates']:
        _validate_update(update)
        uid = update['id']
        if uid in seen or uid in retired or uid not in current:
            raise ValueError('Repeated, retired or missing update ID')
        seen.add(uid)
        if update['geometry'] is not None or 'sourceId' in update['set']:
            raise ValueError('This integration permits text corrections only')
        if object_sha256(current[uid]) != update['expectedFeatureSha256']:
            raise ValueError('Feature differs from reviewed baseline')
        changed[uid] = sorted(k for k, v in update['set'].items() if current[uid].get(k) != v)
        if not changed[uid]:
            raise ValueError('Empty reviewed change')
        by_id[uid].update(copy.deepcopy(update['set']))
        if 'name' in changed[uid]:
            renamed.append(uid)
    # No geometry decoding, relayout, regrouping or conflict regeneration is
    # needed for names. Preserve the entire previous spatial catalog exactly.
    if any(revised[k] != catalog[k] for k in catalog if k != 'features'):
        raise ValueError('Non-text catalog state changed')
    for before, after in zip(rows, revised['features']):
        allowed = set(changed.get(before['id'], []))
        if ({k: v for k, v in before.items() if k not in allowed}
                != {k: v for k, v in after.items() if k not in allowed}):
            raise ValueError('Unreviewed feature field changed')
    if _metadata_hash(catalog) != revision['baseMetadataSha256']:
        raise ValueError('Original input mutated')
    report = {'revisionId':revision['id'], 'scopeNote':revision['scopeNote'],
        'changedFields':changed, 'geometryIDs':[], 'renamedIDs':sorted(renamed),
        'nearestChanges':{}, 'rawPointDiscrepancies':[], 'conflictDiff':{'added':[],'removed':[]},
        'counts':{'features':len(rows),'updates':len(seen),'geometryUpdates':0,'renamed':len(renamed),'newSources':0},
        'hashes':{'beforeMetadataSha256':revision['baseMetadataSha256'],
                  'beforeGeometrySha256':revision['baseGeometrySha256'],
                  'afterMetadataSha256':_metadata_hash(revised),'afterGeometrySha256':_sha(blob)},
        'touchedGroups':sorted({current[uid]['pickerGroupId'] for uid in seen})}
    return revised, blob, report


def apply_reviewed_updates(catalog, blob, manifest_path, references, *, pack_geometry, detect_conflicts, distance):
    if manifest_path is None or not Path(manifest_path).exists():
        return catalog, blob, None
    manifest_path = Path(manifest_path)
    if not manifest_path.is_file():
        raise ValueError("manifest is not a file")
    try:
        manifest = json.loads(manifest_path.read_bytes().decode("utf-8"))
    except Exception as exc:
        raise ValueError("invalid manifest JSON") from exc
    if not isinstance(manifest, dict) or set(manifest) != {"schemaVersion", "revisions"} or manifest["schemaVersion"] != 1 or not isinstance(manifest["revisions"], list):
        raise ValueError("invalid manifest schema")
    current_catalog, current_blob = copy.deepcopy(catalog), bytes(blob)
    reports, renamed = [], set()
    for entry in manifest["revisions"]:
        if not isinstance(entry, dict) or set(entry) != {"revision", "review"}:
            raise ValueError("invalid manifest entry")
        revision_bytes = _pinned(manifest_path.parent, entry["revision"])
        review_bytes = _pinned(manifest_path.parent, entry["review"])
        try:
            revision, review = json.loads(revision_bytes.decode("utf-8")), json.loads(review_bytes.decode("utf-8"))
        except Exception as exc:
            raise ValueError("invalid manifest payload") from exc
        _validate_root_review(review)
        if review["revisionSha256"] != entry["revision"]["sha256"]:
            raise ValueError("root review does not identify revision")
        candidate_catalog, candidate_blob, report = derive_revision(current_catalog, current_blob, revision, manifest_path.parent, references, pack_geometry=pack_geometry, detect_conflicts=detect_conflicts, distance=distance)
        if report["hashes"]["afterMetadataSha256"] != review["afterMetadataSha256"] or report["hashes"]["afterGeometrySha256"] != review["afterGeometrySha256"]:
            raise ValueError("root-reviewed output hash mismatch")
        current_catalog, current_blob = candidate_catalog, candidate_blob
        reports.append(report)
        renamed.update(report["renamedIDs"])
    return current_catalog, current_blob, {"revisions": reports, "renamedIds": sorted(renamed)}
