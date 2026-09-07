#!/usr/bin/env python3
"""Compile attributed place polygons for offline GPS labels and detect ambiguity.

Usage: python scripts/generate_neighborhoods.py /path/to/tunisia.osm.pbf
Dependencies: scripts/neighborhoods/requirements.txt. No geocoding or Google API.
"""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
import math
from pathlib import Path
import re
import struct
import unicodedata

import osmium
from shapely import make_valid, set_precision
from shapely.geometry import shape, Point, MultiPolygon, Polygon
from shapely.strtree import STRtree

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / 'android-app/app/src/main/assets'
PLACES = {'neighbourhood', 'quarter', 'suburb', 'city_district', 'village', 'hamlet', 'town'}
AREA_PLACES = PLACES | {'locality'}
FINE_KINDS = {'neighbourhood', 'quarter', 'suburb', 'city_district', 'residential', 'subdistrict', 'locality'}
MUNICIPAL_MANIFEST = ROOT / 'scripts/neighborhoods/municipal-sources.json'
GRID = 0.1
SCALE = 1_000_000


def clean(text):
    return re.sub(r'\s+', ' ', ''.join(c for c in text if unicodedata.category(c) != 'Cf')).strip()


def norm(text):
    text = ''.join(c for c in unicodedata.normalize('NFKD', clean(text)) if not unicodedata.combining(c))
    return re.sub(r'[^\w]+', '', text.replace('ـ', '').replace('ى', 'ي').lower())


def names(tags):
    result = []
    keys = ['name:ar', 'name', 'name:fr', 'name:en', 'alt_name', 'alt_name:ar', 'alt_name:fr', 'short_name']
    keys += sorted(k for k in tags if k not in keys and
                   (k.startswith('name:') or k == 'loc_name' or k.startswith('loc_name:')
                    or k == 'official_name' or k.startswith('official_name:')))
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


def build(pbf, output, report_dir, municipal_manifest=MUNICIPAL_MANIFEST):
    governors = json.loads((ASSETS/'gouvernorats.json').read_text(encoding='utf-8'))['gouvernorats']
    timetables = [d for g in governors for d in g['delegations'] if d.get('lat') and d.get('lng')]
    areas, nodes, errors = extract(pbf)
    country = next(a['shape'] for a in areas if a['kind'] == 'country')
    municipal_areas, municipal_sources = load_municipal_sources(municipal_manifest)
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
    features, geometries, missing_governor = [], [], []

    def metadata(obj, point, geometry=None):
        candidates = [governor_areas[i] for i in governor_tree.query(point) if governor_areas[i]['shape'].covers(point)]
        if not candidates:
            missing_governor.append(obj['id'])
            return None
        gov = min(candidates, key=lambda a: (a['shape'].area, a['id']))
        parents = [parent_areas[i] for i in parent_tree.query(point)
                   if parent_areas[i]['id'] != obj['id'] and parent_areas[i]['shape'].covers(point)]
        sectors = [a for a in parents if a['kind'] == 'sector'] if obj['kind'] != 'sector' else []
        delegations = [a for a in parents if a['kind'] == 'delegation']
        # Context must not turn a same-level overlap into an arbitrary parent.
        # Imadas are peers, not parents of other imadas. Multiple imadas at a
        # local point fall back to one containing delegation, then governorate.
        parent = sectors[0] if len(sectors) == 1 else delegations[0] if len(delegations) == 1 else gov
        aliases = names(obj['tags'])
        nearest = min(timetables, key=lambda d: (distance(point.y, point.x, d), d['id']))
        result = {'id': obj['id'], 'sourceId': obj['sourceId'], 'name': aliases[0], 'aliases': aliases[1:], 'kind': obj['kind'],
                  'parentName': names(parent['tags'])[0], 'governorateId': gov['governorateId'],
                  'lat': round(point.y, 7), 'lng': round(point.x, 7),
                  'delegationId': nearest['id'], 'hasBoundary': geometry is not None}
        if geometry is not None:
            result['bbox'] = list(geometry.bounds)
            result['areaKm2'] = geometry.area * 111.32**2 * math.cos(math.radians(point.y))
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
        feature = metadata(obj, point, geometry)
        if feature:
            features.append(feature); geometries.append(geometry)

    # Known point-only places remain searchable, but never claim GPS containment.
    tree = STRtree(geometries)
    point_only = []
    for obj in sorted(nodes, key=lambda n: n['id']):
        point = obj['point']
        if not country.covers(point): continue
        aliases = names(obj['tags'])
        matching = [i for i in tree.query(point) if geometries[i].covers(point)
                    and {norm(n) for n in aliases}.intersection(norm(n) for n in [features[i]['name']] + features[i]['aliases'])]
        if matching:
            feature = features[min(matching, key=lambda i: features[i]['areaKm2'])]
            feature['aliases'] = sorted(set(feature['aliases'] + aliases) - {feature['name']})
        else:
            feature = metadata(obj, point)
            if feature:
                point_only.append(feature)

    output.mkdir(parents=True, exist_ok=True)
    report_dir.mkdir(parents=True, exist_ok=True)
    blob = bytearray(b'NPOL' + struct.pack('>i', 1))

    def write_geometry(geometry):
        offset = len(blob)
        polygons = [geometry] if isinstance(geometry, Polygon) else list(geometry.geoms)
        blob.extend(struct.pack('>i', len(polygons)))
        for polygon in polygons:
            rings = [polygon.exterior] + list(polygon.interiors)
            blob.extend(struct.pack('>i', len(rings)))
            for ring in rings:
                coords = list(ring.coords)
                blob.extend(struct.pack('>i', len(coords)))
                for lng, lat in coords:
                    blob.extend(struct.pack('>ii', round(lng*SCALE), round(lat*SCALE)))
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
              'sha256': hashlib.sha256(pbf.read_bytes()).hexdigest(),
              'timestamp': osmium.io.Reader(str(pbf)).header().get('osmosis_replication_timestamp')}
    sources = {'osm': source, **municipal_sources}
    conflicts = detect_conflicts(features, geometries)
    result = {'schemaVersion': 1, 'coordinateScale': SCALE, 'gridSize': GRID,
              'source': source, 'sources': sources, 'conflicts': conflicts,
              'country': country_record, 'cells': dict(sorted(cells.items())),
              'features': features + point_only}
    (output/'neighborhoods.bin').write_bytes(blob)
    (output/'neighborhoods.json').write_text(json.dumps(result, ensure_ascii=False, separators=(',', ':'))+'\n', encoding='utf-8', newline='\n')
    by_id = {f['id']: f for f in features}
    report = {'source': source, 'sources': sources, 'polygonCount': len(features), 'pointOnlyCount': len(point_only),
              'polygonsBySource': dict(Counter(f['sourceId'] for f in features)),
              'conflictCount': len(conflicts),
              'conflictsByReason': dict(Counter(c['reason'] for c in conflicts)),
              'municipalInternalConflictCount': sum(all(by_id[i]['sourceId'] != 'osm' for i in c['ids']) for c in conflicts),
              'conflicts': [{**c, 'names': [by_id[i]['name'] for i in c['ids']]} for c in conflicts],
              'polygonsByKind': dict(Counter(f['kind'] for f in features)),
              'polygonsByGovernorate': {g['nomAr']: sum(f['governorateId'] == g['id'] for f in features) for g in governors},
              'rejected': errors, 'missingGovernorate': missing_governor,
              'geometryBytes': len(blob), 'metadataBytes': (output/'neighborhoods.json').stat().st_size,
              'pointOnlyPlaces': [{k: f[k] for k in ('id', 'name', 'kind', 'lat', 'lng')} for f in point_only]}
    (report_dir/'coverage.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8', newline='\n')
    # Windows terminals may still use cp1252. Output files remain full UTF-8;
    # keep the console summary portable and omit detailed coordinate lists.
    print(json.dumps({k: v for k, v in report.items() if k not in ('pointOnlyPlaces', 'conflicts')},
                     ensure_ascii=True, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('pbf', type=Path)
    parser.add_argument('--output', type=Path, default=ASSETS)
    parser.add_argument('--report-dir', type=Path, default=ROOT/'scripts/neighborhoods')
    parser.add_argument('--municipal-manifest', type=Path, default=MUNICIPAL_MANIFEST)
    options = parser.parse_args()
    build(options.pbf, options.output, options.report_dir, options.municipal_manifest)
