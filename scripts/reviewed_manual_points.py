"""Choose a reviewed manual representative inside a sector and a fine area.

Default and overlap modes change manual representative coordinates only.
Explicit settlement_representative mode may also amend the target delegationId
to a reviewed available source while leaving identity, polygon and picker group
unchanged.
"""
from __future__ import annotations
import copy
import hashlib
import json
import math
from pathlib import Path
from shapely.geometry import Point, mapping, shape
SCHEMA_VERSION = 1
FINE_KINDS = frozenset({'suburb', 'neighbourhood', 'quarter', 'city_district'})
SETTLEMENT_KINDS = frozenset({'village', 'town', 'city'})
REQUIRED_TOP_LEVEL = frozenset({'schemaVersion', 'baseMetadataSha256', 'baseGeometrySha256', 'evidence', 'decisions'})
REQUIRED_EVIDENCE = frozenset({'file', 'sha256'})
REQUIRED_DECISION = frozenset({'id', 'expectedFeatureSha256', 'memberId', 'expectedMemberSha256', 'expectedNearestId'})
REQUIRED_OVERLAP_DECISION = REQUIRED_DECISION | frozenset({'mode', 'expectedPoint', 'nameAssociation'})
REQUIRED_SETTLEMENT_DECISION = REQUIRED_OVERLAP_DECISION | frozenset({'expectedBeforeNearestId'})
EXPECTED_POINT_KEYS = frozenset({'lat', 'lng'})
NAME_ASSOCIATION_KEYS = frozenset({'targetNameAr', 'memberNameAr', 'claim'})
OVERLAP_MODE = 'overlap_representative'
SETTLEMENT_MODE = 'settlement_representative'
LIMITATIONS = 'Manual representative pin correction only: the target latitude/longitude is either copied from an existing grouped member pin, derived as the representative point of the current target/member boundary intersection, or, in explicit settlement_representative mode, copied from a reviewed point-only settlement member. Default and overlap modes leave polygons, IDs, picker groups and prayer-selection source IDs unchanged. Explicit settlement_representative mode may change the target delegationId to the reviewed nearest available source while leaving identity, polygon and picker group unchanged. This report does not certify or review the target boundary and is not boundary/GPS certification.'
def _sha256_text(text):
    return hashlib.sha256(text.encode('utf-8')).hexdigest()
def _is_sha256(value):
    if not isinstance(value, str) or len(value) != 64:
        return False
    return all(ch in '0123456789abcdefABCDEF' for ch in value)
def _same_sha(left, right):
    return left.lower() == right.lower()
def _catalog_sha256(catalog):
    try:
        text = json.dumps(catalog, ensure_ascii=False, separators=(',', ':')) + chr(10)
    except (TypeError, ValueError) as exc:
        raise ValueError('Catalog is not JSON serializable') from exc
    return _sha256_text(text)
def _feature_sha256(feature):
    try:
        text = json.dumps(feature, ensure_ascii=False, separators=(',', ':'), sort_keys=True)
    except (TypeError, ValueError) as exc:
        raise ValueError('Feature is not JSON serializable') from exc
    return _sha256_text(text)
def _blob_sha256(blob):
    if not isinstance(blob, (bytes, bytearray, memoryview)):
        raise ValueError('blob must be bytes-like')
    return hashlib.sha256(blob).hexdigest()
def _valid_identifier(value):
    if isinstance(value, bool):
        return False
    if isinstance(value, int):
        return True
    if isinstance(value, str):
        return bool(value.strip())
    return False
def _is_positive_int(value):
    return type(value) is int and value > 0
def _id_sort_key(value):
    if isinstance(value, bool):
        return (2, repr(value))
    if isinstance(value, int):
        return (0, value)
    if isinstance(value, str):
        return (1, value)
    return (2, repr(value))
def _valid_coordinate(value, low, high):
    if isinstance(value, bool):
        return False
    if not isinstance(value, (int, float)):
        return False
    if not math.isfinite(value):
        return False
    return low <= value <= high
def _valid_distance(value):
    if isinstance(value, bool):
        return False
    if not isinstance(value, (int, float)):
        return False
    if not math.isfinite(value):
        return False
    return value >= 0
def _is_arabic_name(value):
    if not isinstance(value, str) or not value.strip():
        return False
    for ch in value:
        code = ord(ch)
        if (0x0600 <= code <= 0x06FF or 0x0750 <= code <= 0x077F
                or 0x08A0 <= code <= 0x08FF or 0xFB50 <= code <= 0xFDFF
                or 0xFE70 <= code <= 0xFEFF):
            return True
    return False
def _is_active(feature):
    if not isinstance(feature, dict):
        return False
    if feature.get('active', True) is not True:
        return False
    if feature.get('retired', False) is not False:
        return False
    return True
def _feature_index(catalog):
    if not isinstance(catalog, dict):
        raise ValueError('Catalog must be a dictionary')
    features = catalog.get('features')
    if not isinstance(features, list):
        raise ValueError('Catalog features must be a list')
    index = {}
    for position, feature in enumerate(features):
        if not isinstance(feature, dict):
            raise ValueError('Catalog feature must be a dictionary')
        feature_id = feature.get('id')
        if not _valid_identifier(feature_id):
            raise ValueError('Catalog feature has an invalid id')
        if feature_id in index:
            raise ValueError('Catalog feature ids must be unique')
        index[feature_id] = (position, feature)
    return index
def _read_manifest(manifest_path):
    try:
        raw = manifest_path.read_text(encoding='utf-8')
    except OSError as exc:
        raise ValueError('Unable to read reviewed manual points manifest') from exc
    try:
        value = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise ValueError('Reviewed manual points manifest is not valid JSON') from exc
    return value
def _validate_manifest_schema(manifest):
    if not isinstance(manifest, dict):
        raise ValueError('Reviewed manual points manifest must be a dictionary')
    if frozenset(manifest) != REQUIRED_TOP_LEVEL:
        raise ValueError('Reviewed manual points manifest schema is invalid')
    if type(manifest['schemaVersion']) is not int or manifest['schemaVersion'] != SCHEMA_VERSION:
        raise ValueError('Reviewed manual points manifest schemaVersion must be 1')
    if not _is_sha256(manifest['baseMetadataSha256']):
        raise ValueError('Reviewed manual points baseMetadataSha256 is invalid')
    if not _is_sha256(manifest['baseGeometrySha256']):
        raise ValueError('Reviewed manual points baseGeometrySha256 is invalid')
    evidence = manifest['evidence']
    decisions = manifest['decisions']
    if not isinstance(evidence, list) or not evidence:
        raise ValueError('Reviewed manual points evidence must be a non-empty list')
    if not isinstance(decisions, list) or not decisions:
        raise ValueError('Reviewed manual points decisions must be a non-empty list')
    for entry in evidence:
        if not isinstance(entry, dict) or frozenset(entry) != REQUIRED_EVIDENCE:
            raise ValueError('Reviewed manual points evidence entry schema is invalid')
        if not isinstance(entry['file'], str) or not entry['file'].strip():
            raise ValueError('Reviewed manual points evidence file is invalid')
        if not _is_sha256(entry['sha256']):
            raise ValueError('Reviewed manual points evidence sha256 is invalid')
    seen_decisions = set()
    seen_targets = set()
    seen_members = set()
    for decision in decisions:
        if not isinstance(decision, dict):
            raise ValueError('Reviewed manual points decision schema is invalid')
        if 'mode' in decision:
            mode = decision['mode']
            if mode == OVERLAP_MODE:
                required_decision = REQUIRED_OVERLAP_DECISION
            elif mode == SETTLEMENT_MODE:
                required_decision = REQUIRED_SETTLEMENT_DECISION
            else:
                raise ValueError('Reviewed manual points decision mode is invalid')
            if frozenset(decision) != required_decision:
                raise ValueError('Reviewed manual points decision schema is invalid')
            expected_point = decision['expectedPoint']
            if not isinstance(expected_point, dict) or frozenset(expected_point) != EXPECTED_POINT_KEYS:
                raise ValueError('Reviewed manual points expectedPoint schema is invalid')
            name_association = decision['nameAssociation']
            if not isinstance(name_association, dict) or frozenset(name_association) != NAME_ASSOCIATION_KEYS:
                raise ValueError('Reviewed manual points nameAssociation schema is invalid')
            if name_association.get('claim') != 'manual_representative_only':
                raise ValueError('Reviewed manual points nameAssociation claim is invalid')
            if mode == SETTLEMENT_MODE and not _is_positive_int(decision['expectedBeforeNearestId']):
                raise ValueError('Reviewed manual points decision expectedBeforeNearestId is invalid')
        elif frozenset(decision) != REQUIRED_DECISION:
            raise ValueError('Reviewed manual points decision schema is invalid')
        decision_id = decision['id']
        member_id = decision['memberId']
        nearest_id = decision['expectedNearestId']
        if not isinstance(decision_id, str) or not decision_id.strip():
            raise ValueError('Reviewed manual points decision id is invalid')
        if not isinstance(member_id, str) or not member_id.strip():
            raise ValueError('Reviewed manual points decision memberId is invalid')
        if type(nearest_id) is not int or nearest_id <= 0:
            raise ValueError('Reviewed manual points decision expectedNearestId is invalid')
        if not _is_sha256(decision['expectedFeatureSha256']):
            raise ValueError('Reviewed manual points decision expectedFeatureSha256 is invalid')
        if not _is_sha256(decision['expectedMemberSha256']):
            raise ValueError('Reviewed manual points decision expectedMemberSha256 is invalid')
        if decision_id in seen_decisions:
            raise ValueError('Reviewed manual points decision ids must be unique')
        if decision_id in seen_targets:
            raise ValueError('Reviewed manual points target ids must be unique')
        if member_id in seen_members:
            raise ValueError('Reviewed manual points member ids must be unique')
        if decision_id == member_id:
            raise ValueError('Reviewed manual points target and member must differ')
        seen_decisions.add(decision_id)
        seen_targets.add(decision_id)
        seen_members.add(member_id)
def _verify_evidence(manifest, manifest_path):
    base = manifest_path.resolve().parent
    seen_paths = set()
    for entry in manifest['evidence']:
        raw_file = entry['file']
        candidate = Path(raw_file)
        if not candidate.is_absolute():
            candidate = base / candidate
        try:
            resolved = candidate.resolve()
        except OSError as exc:
            raise ValueError('Unable to resolve evidence file') from exc
        try:
            resolved.relative_to(base)
        except ValueError:
            raise ValueError('Evidence file must be inside the manifest parent directory')
        if resolved in seen_paths:
            raise ValueError('Evidence files must be unique')
        seen_paths.add(resolved)
        if not resolved.is_file():
            raise ValueError('Evidence file does not exist')
        try:
            actual = hashlib.sha256(resolved.read_bytes()).hexdigest()
        except OSError as exc:
            raise ValueError('Unable to read evidence file') from exc
        if not _same_sha(actual, entry['sha256']):
            raise ValueError('Evidence file sha256 does not match')
def _geometry_covers(geometry, point):
    if geometry is None:
        return False
    try:
        if bool(getattr(geometry, 'is_empty', False)):
            return False
        if hasattr(geometry, 'is_valid') and not bool(geometry.is_valid):
            return False
        covers = getattr(geometry, 'covers', None)
        if covers is None:
            return False
        return bool(covers(point))
    except Exception:
        return False
def _lookup_geometry(geometry_by_id, feature_id):
    try:
        return geometry_by_id[feature_id]
    except Exception as exc:
        raise ValueError('Missing supplied geometry for feature') from exc
def _iter_references(references):
    if references is None:
        return
    if isinstance(references, dict):
        for key, value in references.items():
            yield value, key
        return
    try:
        iterator = iter(references)
    except TypeError as exc:
        raise ValueError('References must be iterable') from exc
    for value in iterator:
        yield value, None
def _reference_id(reference, fallback):
    if isinstance(reference, dict):
        for key in ('id', 'delegationId'):
            value = reference.get(key)
            if _valid_identifier(value):
                return value
    for key in ('id', 'delegationId'):
        value = getattr(reference, key, None)
        if _valid_identifier(value):
            return value
    if _valid_identifier(fallback):
        return fallback
    raise ValueError('Reference does not expose a usable id')
def _nearest_reference_id(lat, lng, references, distance):
    if not callable(distance):
        raise ValueError('distance callback must be callable')
    entries = list(_iter_references(references))
    if not entries:
        raise ValueError('No available references were supplied')
    best_key = None
    best_id = None
    for reference, fallback in entries:
        reference_id = _reference_id(reference, fallback)
        try:
            value = distance(lat, lng, reference)
        except Exception as exc:
            raise ValueError('distance callback failed') from exc
        if not _valid_distance(value):
            raise ValueError('distance callback returned an invalid distance')
        key = (value, _id_sort_key(reference_id))
        if best_key is None or key < best_key:
            best_key = key
            best_id = reference_id
    return best_id
def _clone_geometry(geometry):
    if geometry is None:
        raise ValueError('Missing supplied geometry for feature')
    try:
        return shape(mapping(geometry))
    except Exception as exc:
        raise ValueError('Unable to clone supplied geometry') from exc
def _is_six_decimal(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return False
    if not math.isfinite(value):
        return False
    return round(value, 6) == value
def _validate_decision(decision, index, references, geometry_by_id, distance):
    target_id = decision['id']
    member_id = decision['memberId']
    if target_id not in index:
        raise ValueError('Reviewed manual points target feature was not found')
    if member_id not in index:
        raise ValueError('Reviewed manual points member feature was not found')
    target_position, target = index[target_id]
    member = index[member_id][1]
    mode = decision.get('mode')
    is_overlap = mode == OVERLAP_MODE
    is_settlement = mode == SETTLEMENT_MODE
    if target_id == member_id:
        raise ValueError('Reviewed manual points target and member must differ')
    if not _is_active(target):
        raise ValueError('Reviewed manual points target is not active')
    if target.get('kind') != 'sector':
        raise ValueError('Reviewed manual points target must be a sector')
    if target.get('hasBoundary') is not True:
        raise ValueError('Reviewed manual points target must have a boundary')
    if not _is_active(member):
        raise ValueError('Reviewed manual points member is not active')
    if is_settlement:
        if member.get('kind') not in SETTLEMENT_KINDS:
            raise ValueError('Reviewed manual points settlement member kind is not allowed')
        if member.get('hasBoundary') is not False:
            raise ValueError('Reviewed manual points settlement member must be point-only')
    else:
        if member.get('kind') not in FINE_KINDS:
            raise ValueError('Reviewed manual points member kind is not allowed')
        if member.get('hasBoundary') is not True:
            raise ValueError('Reviewed manual points member must have a boundary')
    if target.get('pickerGroupId') != target_id:
        raise ValueError('Manual point target must own its picker group')
    if is_settlement:
        if member.get('pickerGroupId') != member_id:
            raise ValueError('Reviewed manual points settlement member must own its picker group')
    elif not is_overlap and member.get('pickerGroupId') != target.get('id'):
        raise ValueError('Reviewed manual points member is not in the target picker group')
    target_name = target.get('name')
    member_name = member.get('name')
    if not _is_arabic_name(target_name):
        raise ValueError('Reviewed manual points target name must be non-blank Arabic')
    if is_overlap or is_settlement:
        if not _is_arabic_name(member_name):
            raise ValueError('Reviewed manual points member name must be non-blank Arabic')
        association = decision['nameAssociation']
        if association.get('targetNameAr') != target_name:
            raise ValueError('Reviewed manual points target name association does not match')
        if association.get('memberNameAr') != member_name:
            raise ValueError('Reviewed manual points member name association does not match')
        if association.get('claim') != 'manual_representative_only':
            raise ValueError('Reviewed manual points nameAssociation claim is invalid')
    elif target_name != member_name:
        raise ValueError('Reviewed manual points target and member names must match')
    target_governorate = target.get('governorateId')
    member_governorate = member.get('governorateId')
    if not _valid_identifier(target_governorate) or target_governorate != member_governorate:
        raise ValueError('Reviewed manual points target and member governorateId must match')
    if is_overlap:
        target_parent = target.get('parentName')
        member_parent = member.get('parentName')
        if not isinstance(target_parent, str) or not target_parent.strip() or target_parent != member_parent:
            raise ValueError('Reviewed manual points target and member parentName must match')
    elif is_settlement:
        target_parent = target.get('parentName')
        member_parent = member.get('parentName')
        if not isinstance(target_parent, str) or not target_parent.strip():
            raise ValueError('Reviewed manual points target parentName must be non-blank')
        if member_parent != target_name:
            raise ValueError('Reviewed manual points settlement member parentName must match target name')
        aliases = member.get('contextAliases')
        if not isinstance(aliases, list) or target_parent not in aliases:
            raise ValueError('Reviewed manual points target parentName must be in member contextAliases')
    target_delegation = target.get('delegationId')
    member_delegation = member.get('delegationId')
    if is_settlement:
        if not _is_positive_int(target_delegation):
            raise ValueError('Reviewed manual points target delegationId must be a positive integer')
        if not _is_positive_int(member_delegation):
            raise ValueError('Reviewed manual points member delegationId must be a positive integer')
    elif not _valid_identifier(target_delegation) or target_delegation != member_delegation:
        raise ValueError('Reviewed manual points target and member delegationId must match')
    target_lat = target.get('lat')
    target_lng = target.get('lng')
    member_lat = member.get('lat')
    member_lng = member.get('lng')
    if not _valid_coordinate(target_lat, -90.0, 90.0) or not _valid_coordinate(target_lng, -180.0, 180.0):
        raise ValueError('Reviewed manual points target has invalid coordinates')
    if not _valid_coordinate(member_lat, -90.0, 90.0) or not _valid_coordinate(member_lng, -180.0, 180.0):
        raise ValueError('Reviewed manual points member has invalid coordinates')
    target_sha = _feature_sha256(target)
    member_sha = _feature_sha256(member)
    if not _same_sha(target_sha, decision['expectedFeatureSha256']):
        raise ValueError('Reviewed manual points target feature sha256 does not match')
    if not _same_sha(member_sha, decision['expectedMemberSha256']):
        raise ValueError('Reviewed manual points member feature sha256 does not match')
    if is_overlap:
        target_geometry = _clone_geometry(_lookup_geometry(geometry_by_id, target_id))
        member_geometry = _clone_geometry(_lookup_geometry(geometry_by_id, member_id))
        try:
            intersection = target_geometry.intersection(member_geometry)
        except Exception as exc:
            raise ValueError('Unable to intersect target and member geometries') from exc
        if not bool(getattr(intersection, 'is_valid', False)):
            raise ValueError('Target/member intersection is invalid')
        if bool(getattr(intersection, 'is_empty', True)):
            raise ValueError('Target/member intersection is empty')
        if intersection.geom_type not in ('Polygon', 'MultiPolygon'):
            raise ValueError('Target/member intersection must be polygonal')
        representative = intersection.representative_point()
        after_lat = round(representative.y, 6)
        after_lng = round(representative.x, 6)
        if not _valid_coordinate(after_lat, -90.0, 90.0) or not _valid_coordinate(after_lng, -180.0, 180.0):
            raise ValueError('Derived overlap point is outside world range')
        expected_point = decision['expectedPoint']
        expected_lat = expected_point.get('lat')
        expected_lng = expected_point.get('lng')
        if not _is_six_decimal(expected_lat) or not _is_six_decimal(expected_lng):
            raise ValueError('Reviewed manual points expectedPoint must be six-decimal numeric')
        if not _valid_coordinate(expected_lat, -90.0, 90.0) or not _valid_coordinate(expected_lng, -180.0, 180.0):
            raise ValueError('Reviewed manual points expectedPoint is outside world range')
        if expected_lat != after_lat or expected_lng != after_lng:
            raise ValueError('Reviewed manual points expectedPoint does not match derived overlap point')
        if after_lat == target_lat and after_lng == target_lng:
            raise ValueError('Reviewed manual points proposed pin is unchanged')
    elif is_settlement:
        target_geometry = _clone_geometry(_lookup_geometry(geometry_by_id, target_id))
        after_lat = round(member_lat, 6)
        after_lng = round(member_lng, 6)
        if not _valid_coordinate(after_lat, -90.0, 90.0) or not _valid_coordinate(after_lng, -180.0, 180.0):
            raise ValueError('Reviewed manual points rounded settlement member pin is outside world range')
        expected_point = decision['expectedPoint']
        expected_lat = expected_point.get('lat')
        expected_lng = expected_point.get('lng')
        if not _is_six_decimal(expected_lat) or not _is_six_decimal(expected_lng):
            raise ValueError('Reviewed manual points expectedPoint must be six-decimal numeric')
        if not _valid_coordinate(expected_lat, -90.0, 90.0) or not _valid_coordinate(expected_lng, -180.0, 180.0):
            raise ValueError('Reviewed manual points expectedPoint is outside world range')
        if expected_lat != after_lat or expected_lng != after_lng:
            raise ValueError('Reviewed manual points expectedPoint does not match rounded settlement member pin')
        if after_lat == target_lat and after_lng == target_lng:
            raise ValueError('Reviewed manual points proposed pin is unchanged')
    else:
        if member_lat == target_lat and member_lng == target_lng:
            raise ValueError('Reviewed manual points proposed pin is unchanged')
        if round(member_lat, 6) != member_lat or round(member_lng, 6) != member_lng:
            raise ValueError('Member pin must use the six-decimal compiled precision')
        target_geometry = _lookup_geometry(geometry_by_id, target_id)
        member_geometry = _lookup_geometry(geometry_by_id, member_id)
        after_lat = member_lat
        after_lng = member_lng
    point = Point(after_lng, after_lat)
    if not _geometry_covers(target_geometry, point):
        raise ValueError('Target geometry does not cover the proposed pin')
    if not is_settlement and not _geometry_covers(member_geometry, point):
        raise ValueError('Member geometry does not cover the proposed pin')
    if is_settlement:
        expected_before = decision['expectedBeforeNearestId']
        before_nearest = _nearest_reference_id(target_lat, target_lng, references, distance)
        member_nearest = _nearest_reference_id(member_lat, member_lng, references, distance)
        after_nearest = _nearest_reference_id(after_lat, after_lng, references, distance)
        if before_nearest != expected_before:
            raise ValueError('Nearest reference at old target does not match expectedBeforeNearestId')
        if target_delegation != expected_before:
            raise ValueError('Target delegationId does not match expectedBeforeNearestId')
        if member_nearest != decision['expectedNearestId']:
            raise ValueError('Nearest reference at member does not match expectedNearestId')
        if after_nearest != decision['expectedNearestId']:
            raise ValueError('Nearest reference at rounded pin does not match expectedNearestId')
        if member_delegation != decision['expectedNearestId']:
            raise ValueError('Member delegationId does not match expectedNearestId')
        if expected_before == decision['expectedNearestId']:
            raise ValueError('Reviewed manual points source must change in settlement mode')
        if target_delegation == member_delegation:
            raise ValueError('Reviewed manual points source must change in settlement mode')
        return {
            'target_position': target_position,
            'target_id': target_id,
            'member_id': member_id,
            'before_lat': target_lat,
            'before_lng': target_lng,
            'after_lat': after_lat,
            'after_lng': after_lng,
            'delegationId': member_delegation,
            'beforeDelegationId': target_delegation,
        }
    nearest_id = _nearest_reference_id(after_lat, after_lng, references, distance)
    if nearest_id != decision['expectedNearestId']:
        raise ValueError('Nearest reference does not match expectedNearestId')
    if nearest_id != target_delegation or nearest_id != member_delegation:
        raise ValueError('Nearest reference would change the prayer source')
    return {
        'target_position': target_position,
        'target_id': target_id,
        'member_id': member_id,
        'before_lat': target_lat,
        'before_lng': target_lng,
        'after_lat': after_lat,
        'after_lng': after_lng,
        'delegationId': target_delegation,
    }
def _apply_edits(catalog, edits):
    new_catalog = copy.deepcopy(catalog)
    features = new_catalog['features']
    for edit in edits:
        target = features[edit['target_position']]
        if target.get('id') != edit['target_id']:
            raise ValueError('Catalog changed during manual point correction')
        if 'beforeDelegationId' in edit and target.get('delegationId') != edit['beforeDelegationId']:
            raise ValueError('Catalog changed during manual point correction')
        target['lat'] = edit['after_lat']
        target['lng'] = edit['after_lng']
        if 'beforeDelegationId' in edit:
            target['delegationId'] = edit['delegationId']
    return new_catalog
def _assert_only_pins_changed(before, after, edits):
    if set(before) != set(after):
        raise ValueError('Manual point correction changed catalog schema')
    for key in before:
        if key == 'features':
            continue
        if before[key] != after[key]:
            raise ValueError('Manual point correction changed non-feature catalog state')
    before_features = before['features']
    after_features = after['features']
    if len(before_features) != len(after_features):
        raise ValueError('Manual point correction changed feature count')
    allowed = {}
    expected = {}
    for edit in edits:
        allowed_fields = {'lat', 'lng'}
        if 'beforeDelegationId' in edit:
            allowed_fields.add('delegationId')
        allowed[edit['target_id']] = allowed_fields
        expected[edit['target_id']] = edit
    for original, updated in zip(before_features, after_features):
        if original.get('id') != updated.get('id'):
            raise ValueError('Manual point correction changed feature identity or order')
        feature_id = original.get('id')
        allowed_fields = allowed.get(feature_id, set())
        edit = expected.get(feature_id)
        for key in set(original) | set(updated):
            if key in allowed_fields:
                if edit is None:
                    raise ValueError('Manual point correction changed an unsupported feature field')
                if key == 'lat':
                    if (original.get('lat') != edit['before_lat']
                            or updated.get('lat') != edit['after_lat']):
                        raise ValueError('Manual point correction changed an unsupported feature field')
                elif key == 'lng':
                    if (original.get('lng') != edit['before_lng']
                            or updated.get('lng') != edit['after_lng']):
                        raise ValueError('Manual point correction changed an unsupported feature field')
                elif key == 'delegationId':
                    if 'beforeDelegationId' not in edit:
                        raise ValueError('Manual point correction changed an unsupported feature field')
                    if (original.get('delegationId') != edit['beforeDelegationId']
                            or updated.get('delegationId') != edit['delegationId']):
                        raise ValueError('Manual point correction changed an unsupported feature field')
                else:
                    if original.get(key) != updated.get(key):
                        raise ValueError('Manual point correction changed an unsupported feature field')
                continue
            if original.get(key) != updated.get(key):
                raise ValueError('Manual point correction changed an unsupported feature field')
def apply_reviewed_manual_points(catalog, blob, manifest_path, references, geometry_by_id, *, distance):
    if manifest_path is None:
        return catalog, None
    try:
        path = Path(manifest_path)
    except TypeError as exc:
        raise ValueError('manifest_path must be path-like') from exc
    if not path.exists():
        return catalog, None
    if not path.is_file():
        raise ValueError('Reviewed manual points manifest path must be a file')
    manifest = _read_manifest(path)
    _validate_manifest_schema(manifest)
    before_metadata_sha = _catalog_sha256(catalog)
    if not _same_sha(before_metadata_sha, manifest['baseMetadataSha256']):
        raise ValueError('Reviewed manual points baseMetadataSha256 does not match catalog')
    before_geometry_sha = _blob_sha256(blob)
    if not _same_sha(before_geometry_sha, manifest['baseGeometrySha256']):
        raise ValueError('Reviewed manual points baseGeometrySha256 does not match blob')
    _verify_evidence(manifest, path)
    index = _feature_index(catalog)
    retired = set(catalog.get('retiredLocalityIds', []))
    if any(d['id'] in retired or d['memberId'] in retired for d in manifest['decisions']):
        raise ValueError('Manual point decision uses a retired locality')
    edits = []
    for decision in manifest['decisions']:
        edits.append(_validate_decision(decision, index, references, geometry_by_id, distance))
    new_catalog = _apply_edits(catalog, edits)
    _assert_only_pins_changed(catalog, new_catalog, edits)
    after_metadata_sha = _catalog_sha256(new_catalog)
    changed_pins = []
    for edit in edits:
        row = {
            'id': edit['target_id'],
            'memberId': edit['member_id'],
            'before': {'lat': edit['before_lat'], 'lng': edit['before_lng']},
            'after': {'lat': edit['after_lat'], 'lng': edit['after_lng']},
            'delegationId': edit['delegationId'],
        }
        if 'beforeDelegationId' in edit:
            row['beforeDelegationId'] = edit['beforeDelegationId']
        changed_pins.append(row)
    report = {
        'changedPins': changed_pins,
        'unchangedGeometrySha256': before_geometry_sha,
        'beforeMetadataSha256': before_metadata_sha,
        'afterMetadataSha256': after_metadata_sha,
        'limitations': LIMITATIONS,
    }
    return new_catalog, report
