'''Pinned boundary geometry for the locality review UI.

Standard library only. Pinned bytes are read at most once each and are always
verified against the catalog SHA256 before they are parsed or indexed, so a
disk revision can never be mixed into a response.
'''

import hashlib
import json
import math
import struct
import threading

__all__ = ['BoundaryError', 'BoundaryStore']

_BINARY_HEADER = b'NPOL\x00\x00\x00\x01'
_INT32 = struct.Struct('>i')

# Smallest encoded polygon: ringCount + one ring (vertexCount + 4 vertices).
_MIN_POLYGON_BYTES = 4 + 4 + 4 * 8
# Smallest encoded ring: vertexCount + 4 vertices.
_MIN_RING_BYTES = 4 + 4 * 8

_NEIGHBOR_MARGIN_METERS = 500.0
_METERS_PER_DEGREE = 111195.0
_MAX_NEIGHBORS = 80


class BoundaryError(Exception):
    '''Raised when pinned boundary data is unavailable, invalid or inconsistent.'''

    def __init__(self, message):
        super().__init__(message)
        self.message = str(message)

    def __str__(self):
        return self.message


def _require_int(value, what):
    # bool is an int subclass, so reject it explicitly; floats are not accepted.
    if type(value) is not int:
        raise BoundaryError('%s must be an integer, got %r' % (what, value))
    return value


def _require_number(value, what):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise BoundaryError('%s must be a finite number, got %r' % (what, value))
    try:
        number = float(value)
    except (OverflowError, ValueError):
        raise BoundaryError('%s must be a finite number, got %r' % (what, value)) from None
    if not math.isfinite(number):
        raise BoundaryError('%s must be a finite number, got %r' % (what, value))
    return number


def _reject_json_constant(token):
    raise ValueError('non-finite JSON value %r is not allowed' % (token,))


def _validate_catalog(catalog):
    if not isinstance(catalog, dict):
        raise BoundaryError('catalog must be an object')
    raw_locations = catalog.get('locations')
    if not isinstance(raw_locations, list):
        raise BoundaryError('catalog[%r] must be a list' % ('locations',))
    locations = {}
    for index, raw in enumerate(raw_locations):
        where = 'catalog[%r][%d]' % ('locations', index)
        if not isinstance(raw, dict):
            raise BoundaryError('%s must be an object' % where)
        location_id = raw.get('id')
        if not isinstance(location_id, str) or not location_id:
            raise BoundaryError('%s.id must be a non-empty string' % where)
        if location_id in locations:
            raise BoundaryError('catalog contains duplicate location id %r' % location_id)
        locations[location_id] = dict(raw)
    raw_pins = catalog.get('sourcePins')
    if isinstance(raw_pins, dict):
        pins = {
            key: dict(value) if isinstance(value, dict) else value
            for key, value in raw_pins.items()
        }
    else:
        pins = raw_pins
    return locations, pins


def _catalog_fingerprint(catalog, locations):
    explicit = catalog.get('catalogFingerprint')
    if isinstance(explicit, str) and explicit:
        return explicit
    digest = hashlib.sha256()
    digest.update(b'locality-boundary-catalog-v1\x00')
    for location_id in sorted(locations):
        raw = locations[location_id]
        fingerprint = raw.get('fingerprint', '') if isinstance(raw, dict) else ''
        if not isinstance(fingerprint, str):
            fingerprint = ''
        digest.update(location_id.encode('utf-8'))
        digest.update(b'\x00')
        digest.update(fingerprint.encode('utf-8'))
        digest.update(b'\x00')
    raw_pins = catalog.get('sourcePins')
    if isinstance(raw_pins, dict):
        for kind in ('metadata', 'binary'):
            pin = raw_pins.get(kind)
            if isinstance(pin, dict):
                digest.update(kind.encode('ascii'))
                digest.update(b'\x00')
                digest.update(str(pin.get('sha256', '')).encode('utf-8'))
                digest.update(b'\x00')
    return digest.hexdigest()


def _coordinate(raw, scale):
    value = round(raw / scale, 6)
    if value == 0.0:
        return 0.0
    return value


def _read_bbox(raw, what):
    if isinstance(raw, dict):
        if all(key in raw for key in ('minLng', 'minLat', 'maxLng', 'maxLat')):
            values = [raw['minLng'], raw['minLat'], raw['maxLng'], raw['maxLat']]
        elif all(key in raw for key in ('minX', 'minY', 'maxX', 'maxY')):
            values = [raw['minX'], raw['minY'], raw['maxX'], raw['maxY']]
        else:
            raise BoundaryError('%s must be a four-number bbox' % what)
    elif isinstance(raw, (list, tuple)) and len(raw) == 4:
        values = list(raw)
    else:
        raise BoundaryError('%s must be a four-number bbox' % what)
    min_lng = _require_number(values[0], '%s.minLng' % what)
    min_lat = _require_number(values[1], '%s.minLat' % what)
    max_lng = _require_number(values[2], '%s.maxLng' % what)
    max_lat = _require_number(values[3], '%s.maxLat' % what)
    if min_lng > max_lng or min_lat > max_lat:
        raise BoundaryError('%s must be finite ordered bounds' % what)
    if not (-180.0 <= min_lng <= 180.0 and -180.0 <= max_lng <= 180.0 and
            -90.0 <= min_lat <= 90.0 and -90.0 <= max_lat <= 90.0):
        raise BoundaryError('%s must be inside longitude/latitude bounds' % what)
    return (min_lng, min_lat, max_lng, max_lat)


def _bbox_intersects(a, b):
    return not (a[2] < b[0] or a[0] > b[2] or a[3] < b[1] or a[1] > b[3])


def _bbox_distance(a, b):
    dx = 0.0
    if a[2] < b[0]:
        dx = b[0] - a[2]
    elif a[0] > b[2]:
        dx = a[0] - b[2]
    dy = 0.0
    if a[3] < b[1]:
        dy = b[1] - a[3]
    elif a[1] > b[3]:
        dy = a[1] - b[3]
    return dx * dx + dy * dy


def _extent_from_features(features):
    box = None
    for feature in features:
        if not isinstance(feature, dict):
            continue
        geometry = feature.get('geometry')
        if not isinstance(geometry, dict):
            continue
        polygons = geometry.get('coordinates')
        if not isinstance(polygons, list):
            continue
        for polygon in polygons:
            if not isinstance(polygon, list):
                continue
            for ring in polygon:
                if not isinstance(ring, list):
                    continue
                for position in ring:
                    if not isinstance(position, (list, tuple)) or len(position) < 2:
                        continue
                    lng = position[0]
                    lat = position[1]
                    if isinstance(lng, bool) or not isinstance(lng, (int, float)):
                        continue
                    if isinstance(lat, bool) or not isinstance(lat, (int, float)):
                        continue
                    lng = float(lng)
                    lat = float(lat)
                    if not math.isfinite(lng) or not math.isfinite(lat):
                        continue
                    if box is None:
                        box = [lng, lat, lng, lat]
                    else:
                        if lng < box[0]:
                            box[0] = lng
                        if lat < box[1]:
                            box[1] = lat
                        if lng > box[2]:
                            box[2] = lng
                        if lat > box[3]:
                            box[3] = lat
    return tuple(box) if box is not None else None


def _read_int32(data, position, end, feature_id, what):
    if position + 4 > end:
        raise BoundaryError(
            'feature %r boundary is truncated while reading %s' % (feature_id, what)
        )
    return _INT32.unpack_from(data, position)[0], position + 4


def _parse_multipolygon(binary, offset, length, scale, feature_id):
    size = len(binary)
    if offset < len(_BINARY_HEADER) or length <= 0 or offset > size or length > size - offset:
        raise BoundaryError(
            'feature %r boundary slice [%d, %d) lies outside the pinned binary'
            % (feature_id, offset, offset + length)
        )
    end = offset + length
    position = offset

    polygon_count, position = _read_int32(
        binary, position, end, feature_id, 'polygon count'
    )
    if polygon_count < 1:
        raise BoundaryError(
            'feature %r boundary polygon count must be positive' % feature_id
        )
    if polygon_count > (end - position) // _MIN_POLYGON_BYTES:
        raise BoundaryError(
            'feature %r boundary polygon count %d exceeds the encoded slice size'
            % (feature_id, polygon_count)
        )

    polygons = []
    for _ in range(polygon_count):
        ring_count, position = _read_int32(
            binary, position, end, feature_id, 'ring count'
        )
        if ring_count < 1:
            raise BoundaryError(
                'feature %r boundary ring count must be positive' % feature_id
            )
        if ring_count > (end - position) // _MIN_RING_BYTES:
            raise BoundaryError(
                'feature %r boundary ring count %d exceeds the encoded slice size'
                % (feature_id, ring_count)
            )
        rings = []
        for _ in range(ring_count):
            vertex_count, position = _read_int32(
                binary, position, end, feature_id, 'vertex count'
            )
            if vertex_count < 4:
                raise BoundaryError(
                    'feature %r boundary ring has %d vertices; at least 4 are required'
                    % (feature_id, vertex_count)
                )
            if vertex_count > (end - position) // 8:
                raise BoundaryError(
                    'feature %r boundary vertex count %d exceeds the encoded slice size'
                    % (feature_id, vertex_count)
                )
            ring = []
            first_vertex = None
            last_vertex = None
            for vertex_index in range(vertex_count):
                lng_raw, position = _read_int32(
                    binary, position, end, feature_id, 'longitude'
                )
                lat_raw, position = _read_int32(
                    binary, position, end, feature_id, 'latitude'
                )
                if lng_raw < -180 * scale or lng_raw > 180 * scale:
                    raise BoundaryError(
                        'feature %r boundary longitude is outside [-180, 180]'
                        % feature_id
                    )
                if lat_raw < -90 * scale or lat_raw > 90 * scale:
                    raise BoundaryError(
                        'feature %r boundary latitude is outside [-90, 90]'
                        % feature_id
                    )
                if vertex_index == 0:
                    first_vertex = (lng_raw, lat_raw)
                last_vertex = (lng_raw, lat_raw)
                ring.append([_coordinate(lng_raw, scale), _coordinate(lat_raw, scale)])
            if first_vertex != last_vertex:
                raise BoundaryError(
                    'feature %r boundary ring is not closed' % feature_id
                )
            rings.append(ring)
        polygons.append(rings)

    if position != end:
        raise BoundaryError(
            'feature %r boundary slice has %d unexpected trailing byte(s)'
            % (feature_id, end - position)
        )
    return polygons


class BoundaryStore(object):
    '''Read-only store for the pinned boundary snapshot described by a catalog.'''

    def __init__(self, catalog):
        locations, pins = _validate_catalog(catalog)
        self._locations = locations
        self._pins = pins
        self._catalog_fingerprint = _catalog_fingerprint(catalog, locations)
        self._lock = threading.Lock()
        self._metadata = None
        self._binary = None
        self._payload_cache = {}

    def get(self, location_id):
        '''Return the boundary payload for one selectable catalog location.

        Raises KeyError for an unknown selectable id and BoundaryError when the
        pinned snapshot is unavailable, invalid or inconsistent.
        '''
        try:
            location = self._locations.get(location_id)
        except TypeError:
            raise KeyError(location_id) from None
        if location is None:
            raise KeyError(location_id)

        fingerprint = location.get('fingerprint', '')
        if not isinstance(fingerprint, str):
            raise BoundaryError(
                'catalog location %r fingerprint must be a string' % location_id
            )
        has_boundary = location.get('hasBoundary', False)
        if type(has_boundary) is not bool:
            raise BoundaryError(
                'catalog location %r hasBoundary must be a boolean' % location_id
            )
        lat = _require_number(
            location.get('lat'), 'catalog location %r lat' % location_id
        )
        lng = _require_number(
            location.get('lng'), 'catalog location %r lng' % location_id
        )
        if not -90.0 <= lat <= 90.0:
            raise BoundaryError(
                'catalog location %r lat is outside [-90, 90]' % location_id
            )
        if not -180.0 <= lng <= 180.0:
            raise BoundaryError(
                'catalog location %r lng is outside [-180, 180]' % location_id
            )

        with self._lock:
            cached = self._payload_cache.get(location_id)
        if cached is not None:
            return cached

        scale, by_owner = self._ensure_metadata()
        entries = by_owner.get(location_id, ())
        boundary_entries = [entry for entry in entries if entry['hasBoundary']]

        if has_boundary and not boundary_entries:
            raise BoundaryError('No stored boundary was found for this place. Refresh the catalog.')
        features = []
        if boundary_entries:
            binary = self._ensure_binary()
            features = [
                self._build_feature(binary, scale, location_id, entry)
                for entry in boundary_entries
            ]

        neighbor_features, neighbor_total, neighbor_truncated, neighbor_omitted = (
            self._neighbor_features(location_id, features, lat, lng, by_owner, scale)
        )

        payload = {
            'id': location_id,
            'fingerprint': fingerprint,
            'catalogFingerprint': self._catalog_fingerprint,
            'hasBoundary': bool(features),
            'primaryHasBoundary': has_boundary,
            'groupedOutlinesOnly': bool(features) and not has_boundary,
            'pin': {'lat': lat, 'lng': lng},
            'features': features,
            'neighborFeatures': neighbor_features,
            'neighborTotal': neighbor_total,
            'neighborTruncated': neighbor_truncated,
            'neighborOmitted': neighbor_omitted,
        }
        with self._lock:
            if len(self._payload_cache) >= 16:
                self._payload_cache.pop(next(iter(self._payload_cache)))
            self._payload_cache[location_id] = payload
        return payload

    def _neighbor_features(self, location_id, features, lat, lng, by_owner, scale):
        extent = _extent_from_features(features)
        if extent is None:
            extent = (lng, lat, lng, lat)
        min_lng, min_lat, max_lng, max_lat = extent
        span_lng = max_lng - min_lng
        span_lat = max_lat - min_lat
        if not math.isfinite(span_lng) or span_lng < 0:
            span_lng = 0.0
        if not math.isfinite(span_lat) or span_lat < 0:
            span_lat = 0.0
        center_lat = (min_lat + max_lat) / 2.0
        if not math.isfinite(center_lat):
            center_lat = 0.0
        margin_lat = max(span_lat * 0.10, _NEIGHBOR_MARGIN_METERS / _METERS_PER_DEGREE)
        cos_lat = math.cos(math.radians(center_lat))
        if not math.isfinite(cos_lat) or abs(cos_lat) < 0.000001:
            cos_lat = 0.000001
        margin_lng = max(
            span_lng * 0.10,
            _NEIGHBOR_MARGIN_METERS / (_METERS_PER_DEGREE * abs(cos_lat))
        )
        query = (
            min_lng - margin_lng,
            min_lat - margin_lat,
            max_lng + margin_lng,
            max_lat + margin_lat,
        )

        candidates = []
        unknown_bbox = 0
        for owner_id, entries in by_owner.items():
            if owner_id == location_id:
                continue
            for entry in entries:
                if not entry['hasBoundary']:
                    continue
                bbox = entry.get('bbox')
                if bbox is None:
                    unknown_bbox += 1
                    continue
                if not _bbox_intersects(bbox, query):
                    continue
                distance = _bbox_distance(bbox, extent)
                candidates.append((distance, owner_id, entry['id'], entry))
        candidates.sort(key=lambda item: (item[0], item[1], item[2]))

        total = len(candidates) + unknown_bbox
        neighbor_features = []
        omitted = unknown_bbox
        if not candidates:
            return neighbor_features, total, False, omitted
        try:
            binary = self._ensure_binary()
        except BoundaryError:
            return neighbor_features, total, False, total
        for _distance, owner_id, _feature_id, entry in candidates:
            if len(neighbor_features) >= _MAX_NEIGHBORS:
                break
            try:
                feature = self._build_feature(binary, scale, owner_id, entry, neighbor=True)
            except BoundaryError:
                omitted += 1
                continue
            neighbor_features.append(feature)

        truncated = total > len(neighbor_features) + omitted
        return neighbor_features, total, truncated, omitted

    def _ensure_metadata(self):
        metadata = self._metadata
        if metadata is not None:
            return metadata
        with self._lock:
            if self._metadata is None:
                self._metadata = self._load_metadata()
            return self._metadata

    def _ensure_binary(self):
        binary = self._binary
        if binary is not None:
            return binary
        with self._lock:
            if self._binary is None:
                self._binary = self._load_binary()
            return self._binary

    def _pin_spec(self, kind):
        where = 'catalog[%r][%r]' % ('sourcePins', kind)
        raw_pins = self._pins
        if not isinstance(raw_pins, dict):
            raise BoundaryError('catalog[%r] must be an object' % ('sourcePins',))
        pin = raw_pins.get(kind)
        if not isinstance(pin, dict):
            raise BoundaryError('%s must be an object' % where)
        path = pin.get('file')
        if not isinstance(path, str) or not path:
            raise BoundaryError('%s.file must be a non-empty string' % where)
        expected = pin.get('sha256')
        if not isinstance(expected, str) or not expected.strip():
            raise BoundaryError('%s.sha256 must be a non-empty string' % where)
        return {'file': path, 'sha256': expected.strip().lower()}

    def _read_pinned(self, kind):
        pin = self._pin_spec(kind)
        path = pin['file']
        try:
            with open(path, 'rb') as handle:
                data = handle.read()
        except OSError as exc:
            raise BoundaryError(
                'cannot read pinned %s file %r: %s' % (kind, path, exc)
            ) from exc
        digest = hashlib.sha256(data).hexdigest()
        if digest != pin['sha256']:
            raise BoundaryError(
                'pinned %s file %r does not match the catalog SHA256 '
                '(expected %s, computed %s)' % (kind, path, pin['sha256'], digest)
            )
        return data

    def _load_metadata(self):
        data = self._read_pinned('metadata')
        try:
            text = data.decode('utf-8-sig')
        except UnicodeDecodeError as exc:
            raise BoundaryError(
                'pinned metadata file is not valid UTF-8: %s' % exc
            ) from exc
        try:
            raw = json.loads(text, parse_constant=_reject_json_constant)
        except ValueError as exc:
            raise BoundaryError(
                'pinned metadata file is not valid JSON: %s' % exc
            ) from exc
        return self._index_metadata(raw)

    def _load_binary(self):
        pin = self._pin_spec('binary')
        binary = self._read_pinned('binary')
        if binary[: len(_BINARY_HEADER)] != _BINARY_HEADER:
            raise BoundaryError(
                'pinned binary file %r does not start with the NPOL v1 header'
                % pin['file']
            )
        return binary

    def _index_metadata(self, raw):
        if not isinstance(raw, dict):
            raise BoundaryError('pinned metadata must be a JSON object')
        scale = _require_int(
            raw.get('coordinateScale'), 'pinned metadata.coordinateScale'
        )
        if scale <= 0:
            raise BoundaryError('pinned metadata.coordinateScale must be positive')
        features = raw.get('features')
        if not isinstance(features, list):
            raise BoundaryError('pinned metadata.features must be a list')

        display_names = {}
        if 'displayNames' in self._pins:
            try:
                overrides = json.loads(self._read_pinned('displayNames').decode('utf-8-sig'))
                display_names = {entry['id']: entry['nameAr'] for entry in overrides['names']}
            except (ValueError, KeyError, TypeError) as exc:
                raise BoundaryError('The pinned Arabic display names could not be read.') from exc

        by_owner = {}
        for index, feature in enumerate(features):
            where = 'pinned metadata feature %d' % index
            if not isinstance(feature, dict):
                raise BoundaryError('%s must be an object' % where)
            feature_id = feature.get('id')
            if not isinstance(feature_id, str) or not feature_id:
                raise BoundaryError('%s.id must be a non-empty string' % where)
            name = feature.get('name', '')
            if not isinstance(name, str):
                raise BoundaryError('%s.name must be a string' % where)
            has_boundary = feature.get('hasBoundary')
            if type(has_boundary) is not bool:
                raise BoundaryError('%s.hasBoundary must be a boolean' % where)
            group = feature.get('pickerGroupId')
            if group is not None and not isinstance(group, str):
                raise BoundaryError('%s.pickerGroupId must be a string' % where)
            owner = group or feature_id
            if owner not in self._locations:
                # Not a selectable catalog owner: never displayed.
                continue

            name = display_names.get(feature_id, name)
            if feature_id in self._locations:
                name = self._locations[feature_id]['nameAr']

            bbox = None
            if 'bbox' in feature:
                try:
                    bbox = _read_bbox(feature.get('bbox'), '%s.bbox' % where)
                except BoundaryError:
                    bbox = None
            entry = {'id': feature_id, 'name': name, 'hasBoundary': has_boundary, 'bbox': bbox}
            if has_boundary:
                problem = None
                try:
                    offset = _require_int(feature.get('offset'), '%s.offset' % where)
                    length = _require_int(feature.get('length'), '%s.length' % where)
                    if offset < 0:
                        raise BoundaryError('%s.offset must be non-negative' % where)
                    if length <= 0:
                        raise BoundaryError('%s.length must be positive' % where)
                except BoundaryError as exc:
                    problem = str(exc)
                if problem is None:
                    entry['offset'] = offset
                    entry['length'] = length
                else:
                    entry['error'] = problem
            by_owner.setdefault(owner, []).append(entry)
        return scale, by_owner

    def _build_feature(self, binary, scale, owner_id, entry, neighbor=False):
        feature_id = entry['id']
        problem = entry.get('error')
        if problem:
            raise BoundaryError(
                'boundary geometry for feature %r is unavailable: %s'
                % (feature_id, problem)
            )
        polygons = _parse_multipolygon(
            binary,
            entry['offset'],
            entry['length'],
            scale,
            feature_id,
        )
        properties = {
            'id': feature_id,
            'name': entry['name'],
            'primary': False if neighbor else (feature_id == owner_id),
        }
        if neighbor:
            properties['ownerId'] = owner_id
            properties['neighbor'] = True
        return {
            'type': 'Feature',
            'id': feature_id,
            'properties': properties,
            'geometry': {'type': 'MultiPolygon', 'coordinates': polygons},
        }
