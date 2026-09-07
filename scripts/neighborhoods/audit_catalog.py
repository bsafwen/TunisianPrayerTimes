#!/usr/bin/env python3
"""Independently audit packed locality geometry and (optionally) its OSM source.

Usage:
  python scripts/neighborhoods/audit_catalog.py --output-dir /path/to/outputs \
      --pbf /path/to/tunisia.osm.pbf

Uses the compiler's existing shapely/osmium dependencies, but does not import its
implementation. No network, app preference, or source asset writes are performed.
Area is approximate spherical square kilometers, calculated by integrating each
ring on a mean-radius Earth; percentage denominators use the bundled country
outline, including any marine area in that outline, not an official land total.
"""
import argparse
from collections import Counter, defaultdict
import csv
import hashlib
import json
import math
from pathlib import Path
import re
import struct
import unicodedata

import osmium
from shapely import make_valid, unary_union
from shapely.geometry import GeometryCollection, MultiPolygon, Point, Polygon, shape
from shapely.strtree import STRtree

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'android-app/app/src/main/assets'
NEIGHBORHOOD_KINDS = {'neighbourhood', 'quarter', 'suburb', 'city_district'}
USED_PLACES = NEIGHBORHOOD_KINDS | {'village', 'hamlet', 'town'}
RADIUS_KM = 6371.0088


def normalize(value):
    value = unicodedata.normalize('NFKD', value).replace('ـ', '').replace('ى', 'ي').lower()
    return re.sub(r'[^\w]+', '', ''.join(c for c in value
                  if not unicodedata.combining(c) and unicodedata.category(c) != 'Cf'))


def all_names(tags):
    keys = ['name:ar', 'name', 'name:fr', 'name:en', 'alt_name', 'alt_name:ar',
            'alt_name:fr', 'short_name']
    keys += [k for k in tags if (k.startswith('name:') or k.startswith('official_name')
                               or k.startswith('loc_name') or k.startswith('old_name')) and k not in keys]
    return list(dict.fromkeys(v.strip() for k in keys for v in tags.get(k, '').split(';') if v.strip()))


def area_km2(geometry):
    def ring_area(ring):
        points = list(ring.coords)
        total = 0.0
        for (lon1, lat1), (lon2, lat2) in zip(points, points[1:]):
            phi1, phi2 = math.radians(lat1), math.radians(lat2)
            mean_sine = ((math.cos(phi1) - math.cos(phi2)) / (phi2 - phi1)
                         if abs(phi2 - phi1) > 1e-8 else math.sin((phi1 + phi2) / 2))
            total += math.radians(lon2 - lon1) * mean_sine
        return abs(total) * RADIUS_KM ** 2
    if geometry.geom_type == 'Polygon':
        return max(0.0, ring_area(geometry.exterior) - sum(ring_area(r) for r in geometry.interiors))
    if hasattr(geometry, 'geoms'):
        return sum(area_km2(g) for g in geometry.geoms)
    return 0.0


def unpack(record, blob, scale):
    position = record['offset']
    def integer():
        nonlocal position
        value = struct.unpack_from('>i', blob, position)[0]
        position += 4
        return value
    polygons = []
    for _ in range(integer()):
        rings = []
        for _ in range(integer()):
            rings.append([(integer() / scale, integer() / scale) for _ in range(integer())])
        polygons.append(Polygon(rings[0], rings[1:]))
    if position != record['offset'] + record['length']:
        raise ValueError(f"Length mismatch at offset {record['offset']}")
    return MultiPolygon(polygons)


def brief(feature):
    return {key: feature[key] for key in ('id', 'name', 'kind', 'sourceId') if key in feature}


class GpsLabelAudit:
    """Model conflict-aware runtime selection with its documented edge tolerance.

    Shapely remains the independent topology/area oracle. Label evaluation includes
    the Kotlin edge epsilon because exact mathematical interior differs from the
    app near very small crossing slivers and shared vertices.
    """
    def __init__(self, features, geometries, country, conflict_records=()):
        self.features, self.geometries, self.country = features, geometries, country
        self.tree = STRtree(geometries)
        self.rings = [[[[tuple(p) for p in ring.coords] for ring in [polygon.exterior] + list(polygon.interiors)]
                       for polygon in (list(g.geoms) if g.geom_type == 'MultiPolygon' else [g])]
                      for g in geometries]
        ids = {f['id']: i for i, f in enumerate(features)}
        self.conflicts = [(ids[r['ids'][0]], ids[r['ids'][1]]) for r in conflict_records]

    def contains(self, index, point, include_boundary=True):
        x, y = point.x, point.y
        for polygon in self.rings[index]:
            inside_outer, inside_hole = False, False
            for ring_index, coordinates in enumerate(polygon):
                inside, on_edge = False, False
                previous_x, previous_y = coordinates[-1]
                for current_x, current_y in coordinates:
                    cross = (x - previous_x) * (current_y - previous_y) - (y - previous_y) * (current_x - previous_x)
                    edge_length = math.hypot(current_x - previous_x, current_y - previous_y)
                    if (abs(cross) <= 1e-10 * edge_length and min(previous_x, current_x) - 1e-10 <= x <= max(previous_x, current_x) + 1e-10
                        and min(previous_y, current_y) - 1e-10 <= y <= max(previous_y, current_y) + 1e-10):
                        on_edge = True
                    if ((previous_y > y) != (current_y > y)
                        and x < (current_x - previous_x) * (y - previous_y) / (current_y - previous_y) + previous_x):
                        inside = not inside
                    previous_x, previous_y = current_x, current_y
                if ring_index == 0:
                    inside_outer = inside or on_edge if include_boundary else inside and not on_edge
                else:
                    inside_hole = inside_hole or inside or on_edge
            if inside_outer and not inside_hole:
                return True
        return False

    def evaluate(self, lat, lng):
        empty = {'candidates': [], 'excluded': [], 'activeConflicts': [], 'previousWinner': None, 'winner': None}
        if not math.isfinite(lat) or not math.isfinite(lng) or not (-90 <= lat <= 90 and -180 <= lng <= 180):
            return empty
        point = Point(lng, lat)
        if not self.country.covers(point):
            return empty
        candidates = [int(i) for i in self.tree.query(point) if self.contains(int(i), point)]
        excluded, active_conflicts, strict_cache = set(), [], {}
        def strict(index):
            if index not in strict_cache:
                strict_cache[index] = self.contains(index, point, include_boundary=False)
            return strict_cache[index]
        for first, second in self.conflicts:
            if first in candidates and second in candidates and strict(first) and strict(second):
                excluded.update((first, second))
                active_conflicts.append((first, second))
        rank = lambda index: (self.features[index]['areaKm2'], self.features[index]['id'])
        remaining = [i for i in candidates if i not in excluded]
        return {'candidates': candidates, 'excluded': sorted(excluded), 'activeConflicts': active_conflicts,
                'previousWinner': min(candidates, key=rank) if candidates else None,
                'winner': min(remaining, key=rank) if remaining else None}


def validate_lookup_model():
    """Small behavioral regression fixtures, independent of any generated source."""
    def box(x0, y0, x1, y1):
        return Polygon([(x0, y0), (x1, y0), (x1, y1), (x0, y1)])
    shapes = [box(0, 0, 10, 10), box(1, 1, 6, 6), box(4, 1, 9, 6), box(4.5, 2, 5, 3)]
    records = [{'id': str(i), 'name': str(i), 'kind': 'sector' if i == 0 else 'residential', 'areaKm2': g.area}
               for i, g in enumerate(shapes)]
    model = GpsLabelAudit(records, shapes, box(-1, -1, 11, 11), [{'ids': ['1', '2']}])
    cases = [ ('overlap falls back to sector', 4, 5, 0),
              ('independent finer label survives', 2.5, 4.75, 3),
              ('outside overlap keeps original', 4, 2, 1),
              ('shared edge alone is not strict conflict', 4, 4, 1),
              ('outside country returns none', 50, 50, None),
              ('invalid coordinate returns none', float('nan'), 5, None)]
    results = []
    for name, lat, lng, expected in cases:
        actual = model.evaluate(lat, lng)['winner']
        assert actual == expected, (name, actual, expected)
        results.append({'case': name, 'passed': True})
    model_without_sector = GpsLabelAudit(records[1:3], shapes[1:3], box(-1, -1, 11, 11), [{'ids': ['1', '2']}])
    assert model_without_sector.evaluate(4, 5)['winner'] is None
    results.append({'case': 'all containing labels disputed returns none', 'passed': True})
    holed = Polygon([(0, 0), (5, 0), (5, 5), (0, 5)], [[(1, 1), (1, 2), (2, 2), (2, 1)]])
    hole_model = GpsLabelAudit([records[0]], [holed], box(-1, -1, 11, 11))
    assert hole_model.evaluate(1.5, 1)['winner'] is None
    assert hole_model.evaluate(0, 1)['winner'] == 0
    results.append({'case': 'hole edges excluded while outer edge included', 'passed': True})
    tiny = Polygon([(0, 0), (0.00001, 0), (0, 0.00001)])
    tiny_model = GpsLabelAudit([records[0]], [tiny], box(-1, -1, 11, 11))
    assert tiny_model.contains(0, Point(0.000002, 0.000002), False)
    assert not tiny_model.contains(0, Point(0.000009, 0.000009))
    assert tiny_model.contains(0, Point(0.000005, 0.000005))
    assert not tiny_model.contains(0, Point(0.000005, 0.000005), False)
    results.append({'case': 'short edge tolerance uses coordinate distance and retains exact boundary semantics', 'passed': True})
    return results


def audit_source(pbf, country, features, geometries, point_rows):
    """Read source separately, including place kinds the compiler does not use."""
    factory = osmium.geom.GeoJSONFactory()
    tree = STRtree(geometries)
    included_ids = {f['id'] for f in features}
    named_area_kinds, named_admin_levels = Counter(), Counter()
    named_node_kinds, included_node_kinds, linked_node_kinds = Counter(), Counter(), Counter()
    omitted_areas, omitted_nodes, name_link_issues, invalid_source_areas = [], [], [], []
    source_tag_keys = Counter()
    selected_nodes = []
    unnamed_residential = 0
    for obj in (osmium.FileProcessor(str(pbf)).with_areas()
                .with_filter(osmium.filter.KeyFilter('place', 'boundary', 'landuse', 'addr:suburb', 'is_in:suburb'))):
        tags = dict(obj.tags)
        aliases = all_names(tags)
        if obj.is_area():
            if tags.get('landuse') == 'residential' and not aliases:
                unnamed_residential += 1
            relevant = ('place' in tags or tags.get('boundary') == 'administrative'
                        or tags.get('landuse') in ('residential', 'allotments')
                        or 'addr:suburb' in tags or 'is_in:suburb' in tags)
            if not relevant or not aliases:
                continue
            identifier = f"osm:{'way' if obj.from_way() else 'relation'}:{obj.orig_id()}"
            try:
                original = shape(json.loads(factory.create_multipolygon(obj)))
                if not original.intersects(country):
                    continue
                if not original.is_valid:
                    invalid_source_areas.append({'id': identifier, 'name': aliases[0]})
                    original = make_valid(original)
                geometry = original.intersection(country)
                if geometry.is_empty or area_km2(geometry) < 0.000001:
                    continue
            except Exception as error:
                omitted_areas.append({'id': identifier, 'name': aliases[0], 'error': str(error)})
                continue
            if 'place' in tags:
                named_area_kinds[tags['place']] += 1
            if tags.get('boundary') == 'administrative':
                named_admin_levels[tags.get('admin_level', 'missing')] += 1
            for key in tags:
                if key.startswith(('source', 'check_date', 'survey:date', 'wikidata', 'wikipedia', 'official_name')):
                    source_tag_keys[key] += 1
            if identifier not in included_ids:
                administrative_container = tags.get('boundary') == 'administrative' and tags.get('admin_level') in ('2', '4', '5')
                if administrative_container:
                    continue
                representative = geometry.representative_point()
                omitted_areas.append({'id': identifier, 'name': aliases[0],
                    'place': tags.get('place'), 'adminLevel': tags.get('admin_level'),
                    'landuse': tags.get('landuse'), 'boundary': tags.get('boundary'),
                    'areaKm2': round(area_km2(geometry), 6),
                    'originalAreaKm2': round(area_km2(original), 6),
                    'lat': representative.y, 'lng': representative.x,
                    'building': tags.get('building'),
                    'addrSuburb': tags.get('addr:suburb'),
                    'sourceTags': tags,
                    'reason': 'Named source area absent from compiled catalog; meaning requires review'})
        elif obj.is_node() and 'place' in tags and aliases:
            point = Point(obj.lon, obj.lat)
            if not country.covers(point):
                continue
            identifier = f'osm:node:{obj.id}'
            named_node_kinds[tags['place']] += 1
            if identifier in included_ids:
                included_node_kinds[tags['place']] += 1
            containing = [int(i) for i in tree.query(point) if geometries[i].covers(point)]
            matches = [i for i in containing if {normalize(n) for n in aliases}.intersection(
                       normalize(n) for n in [features[i]['name']] + features[i]['aliases'])]
            if matches:
                linked_node_kinds[tags['place']] += 1
            if tags['place'] in USED_PLACES:
                selected_nodes.append((identifier, aliases, point, tags['place'], containing, matches))
            elif not matches:
                omitted_nodes.append({'id': identifier, 'name': aliases[0], 'kind': tags['place'],
                                      'lat': obj.lat, 'lng': obj.lon})
    alias_index = defaultdict(list)
    for i, feature in enumerate(features[:len(geometries)]):
        for name in [feature['name']] + feature['aliases']:
            alias_index[normalize(name)].append(i)
    for identifier, aliases, point, kind, containing, matches in selected_nodes:
        if identifier in included_ids and matches:
            name_link_issues.append({'id': identifier, 'name': aliases[0],
                'issue': 'Point retained although an expanded source alias matches a containing polygon',
                'matchingPolygons': [brief(features[i]) for i in matches]})
        elif identifier in included_ids:
            candidates = {i for alias in aliases for i in alias_index[normalize(alias)]}
            nearby = [i for i in candidates if geometries[i].distance(point) < 0.005]
            if nearby:
                name_link_issues.append({'id': identifier, 'name': aliases[0],
                    'issue': 'Same-name polygon nearby but does not contain source place point; requires review, not automatic merging',
                    'nearbyPolygons': [brief(features[i]) for i in nearby]})
    exact_recoveries = [r for r in name_link_issues if 'expanded source alias' in r['issue']]
    return {'pbfSha256': hashlib.sha256(pbf.read_bytes()).hexdigest(),
            'namedPlacePolygonsByTagInsideCountry': dict(named_area_kinds),
            'namedAdministrativePolygonsByLevelInsideCountry': dict(named_admin_levels),
            'namedPlaceNodesByTagInsideCountry': dict(named_node_kinds),
            'pointOnlyCompiledNodesBySourceTag': dict(included_node_kinds),
            'nodesWithNameMatchingContainingPolygonBySourceTag': dict(linked_node_kinds),
            'unnamedResidentialAreasInExtract': unnamed_residential,
            'omittedNamedAreas': omitted_areas, 'omittedNamedNodesWithoutMatchingPolygon': omitted_nodes,
            'nameLinkReviewCandidates': name_link_issues,
            'actionableOmissionReview': {
                'unknownAdministrativeLevelCandidates': [r['id'] for r in omitted_areas
                    if r.get('boundary') == 'administrative' and not r.get('adminLevel')],
                'unclassifiedLocalityCandidates': [r['id'] for r in omitted_areas if r.get('place') == 'locality'],
                'expandedAliasExactContainmentRecoveries': len(exact_recoveries),
                'nearbySameNameWithoutContainment': len(name_link_issues) - len(exact_recoveries),
                'interpretation': 'Unknown administrative levels and place=locality do not establish neighborhood semantics. Nearby same-name points must not be merged into polygons that do not contain them. An old_name-only area has no current display name. Building outlines, squares, fitness/allotment areas, cities and islands are not extra neighborhood boundaries. Tiny intersections of foreign administrative polygons with the quantized country border are not missing Tunisian neighborhoods.'},
            'invalidOriginalSourceAreasRequiringRepair': invalid_source_areas,
            'provenanceTagCountsOnRelevantNamedSourceAreas': dict(source_tag_keys)}


def run(assets, output_dir, pbf):
    behavioral_regressions = validate_lookup_model()
    metadata_path, geometry_path = assets / 'neighborhoods.json', assets / 'neighborhoods.bin'
    metadata = json.loads(metadata_path.read_text(encoding='utf-8'))
    blob = geometry_path.read_bytes()
    if blob[:8] != b'NPOL\x00\x00\x00\x01':
        raise ValueError('Unexpected binary header')
    features = [f for f in metadata['features'] if f['hasBoundary']]
    points = [f for f in metadata['features'] if not f['hasBoundary']]
    geometries = [unpack(f, blob, metadata['coordinateScale']) for f in features]
    country = unpack(metadata['country'], blob, metadata['coordinateScale'])
    conflict_records = metadata.get('conflicts', [])
    label_model = GpsLabelAudit(features, geometries, country, conflict_records)
    invalid = [brief(f) for f, g in zip(features, geometries) if not g.is_valid]
    bad_representatives = [brief(f) for f, g in zip(features, geometries)
                          if not g.covers(Point(f['lng'], f['lat']))]
    if invalid:
        raise ValueError(f'Invalid compiled polygons: {invalid}')
    country_area = area_km2(country)
    indices = {'allNamedPolygons': list(range(len(features))),
               'sectorPolygons': [i for i, f in enumerate(features) if f['kind'] == 'sector'],
               'nonSectorPolygons': [i for i, f in enumerate(features) if f['kind'] != 'sector'],
               'neighborhoodQuarterSuburbPolygons': [i for i, f in enumerate(features) if f['kind'] in NEIGHBORHOOD_KINDS],
               'explicitNeighbourhoodPolygons': [i for i, f in enumerate(features) if f['kind'] == 'neighbourhood']}
    coverage = {}
    unions = {}
    for label, selected in indices.items():
        union = unary_union([geometries[i] for i in selected]) if selected else GeometryCollection()
        union = union.intersection(country)
        unions[label] = union
        amount = area_km2(union)
        coverage[label] = {'polygonCount': len(selected), 'unionAreaKm2': round(amount, 6),
                           'percentOfBundledCountryOutline': round(amount / country_area * 100, 6),
                           'sumOfPolygonAreasKm2': round(sum(area_km2(geometries[i]) for i in selected), 6)}
    print('Computed union coverage.', flush=True)
    gaps = country.difference(unions['allNamedPolygons'])
    gap_parts = list(gaps.geoms) if hasattr(gaps, 'geoms') else [gaps]
    largest_gaps = sorted(gap_parts, key=area_km2, reverse=True)[:20]
    tree = STRtree(geometries)
    overlap_kinds = Counter()
    ambiguous = []
    sector_overlap = []
    for i, geometry in enumerate(geometries):
        for j in tree.query(geometry):
            j = int(j)
            if j <= i:
                continue
            intersection = geometry.intersection(geometries[j])
            amount = area_km2(intersection)
            if amount <= 0.000001:
                continue
            labels = ':'.join(sorted((features[i]['kind'], features[j]['kind'])))
            overlap_kinds[labels] += 1
            record = {'first': brief(features[i]), 'second': brief(features[j]),
                      'intersectionKm2': round(amount, 6)}
            if features[i]['kind'] == features[j]['kind'] == 'sector':
                sector_overlap.append(record)
            if (features[i]['kind'] != 'sector' and features[j]['kind'] != 'sector'
                and not geometry.covers(geometries[j]) and not geometries[j].covers(geometry)):
                ambiguous.append(record)
    point_rows = []
    group_counts = defaultdict(Counter)
    current_label_counts = defaultdict(Counter)
    for point in points:
        location = Point(point['lng'], point['lat'])
        selection = label_model.evaluate(point['lat'], point['lng'])
        containing = selection['candidates']
        winner, old_winner = selection['winner'], selection['previousWinner']
        non_sector = [i for i in containing if features[i]['kind'] != 'sector']
        neighborhood = [i for i in containing if features[i]['kind'] in NEIGHBORHOOD_KINDS]
        status = 'non-sector polygon' if non_sector else ('sector only' if containing else 'no named polygon')
        group_counts[point['kind']][status] += 1
        group_counts['ALL'][status] += 1
        current_kind = features[winner]['kind'] if winner is not None else 'no label'
        current_label_counts[point['kind']][current_kind] += 1
        current_label_counts['ALL'][current_kind] += 1
        point_rows.append({**brief(point), 'lat': point['lat'], 'lng': point['lng'],
            'coverageStatus': status, 'containingPolygonCount': len(containing),
            'containingNonSectorCount': len(non_sector), 'containingNeighborhoodTypeCount': len(neighborhood),
            'currentGpsLabel': features[winner]['name'] if winner is not None else None,
            'currentGpsLabelKind': features[winner]['kind'] if winner is not None else None,
            'currentGpsPolygonId': features[winner]['id'] if winner is not None else None,
            'currentGpsSourceId': features[winner].get('sourceId', 'osm') if winner is not None else None,
            'previousMinimumAreaGpsLabel': features[old_winner]['name'] if old_winner is not None else None,
            'previousMinimumAreaGpsPolygonId': features[old_winner]['id'] if old_winner is not None else None,
            'selectionChangedByConflictExclusion': winner != old_winner,
            'excludedConflictingPolygonIds': [features[i]['id'] for i in selection['excluded']],
            'sameNormalizedNameAsGpsLabel': winner is not None and normalize(point['name']) == normalize(features[winner]['name'])})
    conflict_samples, conflict_outcomes, conflict_failures, mathematical_differences = [], Counter(), [], []
    ids = {f['id']: i for i, f in enumerate(features)}
    for number, record in enumerate(conflict_records):
        lat, lng = record['sample']['lat'], record['sample']['lng']
        point = Point(lng, lat)
        selection = label_model.evaluate(lat, lng)
        pair = [ids[identifier] for identifier in record['ids']]
        winner, old_winner = selection['winner'], selection['previousWinner']
        excluded_ids = [features[i]['id'] for i in selection['excluded']]
        pair_suppressed = all(index in selection['excluded'] for index in pair)
        strict_mathematical = all(geometries[i].contains(point) for i in pair)
        strict_runtime = all(label_model.contains(i, point, False) for i in pair)
        if strict_mathematical != strict_runtime:
            mathematical_differences.append({'conflictIndex': number, 'ids': record['ids'],
                'mathematicalStrictInterior': strict_mathematical, 'runtimeStrictInterior': strict_runtime})
        if winner in selection['excluded'] or (strict_runtime and winner in pair):
            conflict_failures.append({'conflictIndex': number, 'ids': record['ids'], 'winner': features[winner]['id']})
        if winner is None:
            outcome = 'no unambiguous containing label'
        elif winner == old_winner:
            outcome = 'independent existing label retained' if winner not in pair else 'sample lies on runtime boundary tolerance'
        else:
            outcome = 'sector fallback' if features[winner]['kind'] == 'sector' else 'other independent label selected'
        conflict_outcomes[outcome] += 1
        conflict_samples.append({'conflictIndex': number, **record,
            'bothPolygonsStrictlyContainMathematically': strict_mathematical,
            'bothPolygonsStrictlyContainAtRuntimeTolerance': strict_runtime,
            'bothPairIdsSuppressed': pair_suppressed,
            'excludedConflictingPolygonIds': excluded_ids,
            'currentGpsLabel': brief(features[winner]) if winner is not None else None,
            'previousMinimumAreaLabel': brief(features[old_winner]) if old_winner is not None else None,
            'outcome': outcome})
    real_point_cases = []
    for name, lat, lng in [('المنزه 9 أ', 36.8428, 10.1465), ('النصر 2', 36.864, 10.1647), ('بوشوشة', 36.809, 10.14)]:
        result = label_model.evaluate(lat, lng)
        actual = features[result['winner']]['name'] if result['winner'] is not None else None
        assert actual == name, (name, actual, lat, lng)
        real_point_cases.append({'expected': name, 'lat': lat, 'lng': lng, 'actual': actual, 'passed': True})
    grid_errors = []
    size = metadata['gridSize']
    for index, feature in enumerate(features):
        x0, y0, x1, y1 = feature['bbox']
        for y in range(math.floor(y0 / size), math.floor(y1 / size) + 1):
            for x in range(math.floor(x0 / size), math.floor(x1 / size) + 1):
                if index not in metadata['cells'].get(f'{y}:{x}', []):
                    grid_errors.append({'id': feature['id'], 'cell': f'{y}:{x}'})
    report = {'source': metadata['source'], 'sources': metadata.get('sources', {'osm': metadata['source']}),
        'polygonsBySource': dict(Counter(f.get('sourceId', 'osm') for f in features)),
        'method': {'geometry': 'Independently decoded packed coordinates; Shapely topology and containment',
            'gpsLabel': 'Actual runtime rule: candidates with inclusive outer boundary and excluded hole boundary; exclude both conflicting IDs only if both strictly contain; edge test abs(cross) <= 1e-10 degrees * segment length with coordinate bbox epsilon 1e-10 degrees; choose smallest remaining area then stable ID',
            'countryBoundaryNote': 'Audit uses Shapely country covers; no surveyed cases are at the country boundary',
            'area': 'Approximate spherical square kilometers, mean Earth radius 6371.0088 km',
            'denominator': 'Bundled Tunisia country outline, including any marine territory; not official land area',
            'overlapToleranceKm2': 0.000001,
            'nonSectorWarning': 'Non-sector polygons include towns and villages; they are not necessarily neighborhoods or finer than sectors',
            'completenessWarning': 'Geographic coverage does not demonstrate that every neighborhood is named, bounded, accurate, or current'},
        'artifactSha256': {'metadata': hashlib.sha256(metadata_path.read_bytes()).hexdigest(),
                           'geometry': hashlib.sha256(blob).hexdigest()},
        'countryOutlineAreaKm2': round(country_area, 6), 'coverage': coverage,
        'geometryValidation': {'invalidPolygonCount': len(invalid), 'representativePointFailures': bad_representatives,
                               'missingBboxGridEntries': grid_errors, 'countryValid': country.is_valid},
        'namedPolygonGaps': {'areaKm2': round(area_km2(gaps), 6), 'componentCount': len(gap_parts),
            'largestComponents': [{'areaKm2': round(area_km2(g), 6), 'bbox': list(g.bounds),
                'sampleLat': g.representative_point().y, 'sampleLng': g.representative_point().x} for g in largest_gaps]},
        'overlaps': {'positiveAreaPairCountsByKind': dict(overlap_kinds),
            'sectorOverlapPairs': sorted(sector_overlap, key=lambda x: -x['intersectionKm2']),
            'nonSectorNonNestedOverlapPairs': sorted(ambiguous, key=lambda x: -x['intersectionKm2'])},
        'pointOnlyCoverageByKind': {k: dict(v) for k, v in group_counts.items()},
        'pointOnlyCurrentGpsLabelKinds': {k: dict(v) for k, v in current_label_counts.items()},
        'pointOnlyLabelsChangedByConflictExclusion': sum(r['selectionChangedByConflictExclusion'] for r in point_rows),
        'conflictAwareSelectionValidation': {'registeredConflictCount': len(conflict_records),
            'samplesEvaluated': len(conflict_samples), 'outcomes': dict(conflict_outcomes),
            'samplePairsFullySuppressed': sum(r['bothPairIdsSuppressed'] for r in conflict_samples),
            'samplesOutsideStrictMathematicalIntersection': [r['conflictIndex'] for r in conflict_samples
                if not r['bothPolygonsStrictlyContainMathematically']],
            'samplesStillSelectingAPairMember': [r['conflictIndex'] for r in conflict_samples
                if r['currentGpsLabel'] and r['currentGpsLabel']['id'] in r['ids']],
            'excludedIdSelectedFailures': conflict_failures,
            'mathematicalVsRuntimeBoundaryToleranceDifferences': mathematical_differences,
            'samples': conflict_samples,
            'confidenceInterpretation': 'Conflict exclusion removes an incompatible label choice. It does not independently certify any surviving name or boundary. Sector fallbacks are coarser locations, and archived municipal labels retain their 2018 currency limitation.'},
        'behavioralRegressionFixtures': behavioral_regressions, 'realCoordinateRegressionCases': real_point_cases,
        'pointOnlyLocations': point_rows}
    if pbf:
        print('Auditing original PBF tags and alias links.', flush=True)
        report['sourceAudit'] = audit_source(pbf, country, features + points, geometries, point_rows)
    output_dir.mkdir(parents=True, exist_ok=True)
    json_path = output_dir / 'neighborhood-reliability-audit.json'
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    with (output_dir / 'neighborhood-reliability-audit.csv').open('w', encoding='utf-8-sig', newline='') as handle:
        writer = csv.DictWriter(handle, fieldnames=list(point_rows[0]))
        writer.writeheader()
        writer.writerows(point_rows)
    lines = ['# Current offline locality polygon reliability audit', '',
        'This report describes the regenerated catalog and conflict-aware runtime. It does not replace the earlier historical audit of the 2,714-polygon catalog.', '',
        'This audit checks geometry and source coverage. It cannot certify every Tunisian neighborhood or official boundary accuracy.', '',
        f"OSM extract snapshot: {metadata['source']['timestamp']}. Source: {metadata['source']['url']}", '',
        'The full source registry and checksums are retained in the JSON. Extract timestamps and archive dates are not real-world boundary verification dates.', '',
        '| Source | Polygon count |', '|---|---:|']
    for source, count in sorted(Counter(f.get('sourceId', 'osm') for f in features).items()):
        lines.append(f'| {source} | {count} |')
    lines += ['',
        f'Bundled country-outline area: approximately {country_area:,.2f} km². The denominator includes any marine area in that outline.', '',
        '| Polygon category | Count | Union km² | % of country outline |', '|---|---:|---:|---:|']
    for label, row in coverage.items():
        lines.append(f"| {label} | {row['polygonCount']} | {row['unionAreaKm2']:,.2f} | {row['percentOfBundledCountryOutline']:.4f}% |")
    lines += ['', 'Non-sector polygons include towns and villages. Their coverage must not be reported as exact neighborhood coverage.', '',
        '## Known point-only localities', '', '| Kind | Non-sector polygon | Sector only | No named polygon |', '|---|---:|---:|---:|']
    for label, row in sorted(group_counts.items()):
        lines.append(f"| {label} | {row['non-sector polygon']} | {row['sector only']} | {row['no named polygon']} |")
    lines += ['', 'A containing polygon with a different name does not establish the boundary of a point-only locality. See currentGpsLabel in the CSV for the actual conflict-aware label each point would receive; previousMinimumAreaGpsLabel records the prior selection rule for comparison.', '',
        '## Conflict-aware selection', '',
        f'- Registered conflict sample points evaluated: {len(conflict_samples)}. Excluded IDs incorrectly selected: {len(conflict_failures)}.',
        f'- Sample pairs with both IDs suppressed: {sum(r["bothPairIdsSuppressed"] for r in conflict_samples)} of {len(conflict_samples)}.',
        f'- Supplied samples outside the strict mathematical intersection: {sum(not r["bothPolygonsStrictlyContainMathematically"] for r in conflict_samples)}.',
        f'- Point-only locations whose label changes because of conflict exclusion: {sum(r["selectionChangedByConflictExclusion"] for r in point_rows)}.',
        f'- Boundary-tolerance differences from exact mathematical interior: {len(mathematical_differences)}.',
        f'- Behavioral regression fixtures passed: {len(behavioral_regressions)}. Existing real-coordinate label regressions passed: {len(real_point_cases)}.', '',
        '| Conflict sample outcome | Count |', '|---|---:|']
    for label, count in sorted(conflict_outcomes.items()):
        lines.append(f'| {label} | {count} |')
    lines += ['', 'Actual current labels for the known point-only records:', '',
        '| Selected polygon kind | Count |', '|---|---:|']
    for label, count in sorted(current_label_counts['ALL'].items()):
        lines.append(f'| {label} | {count} |')
    lines += ['', 'These counts measure selected label resolution, not a calibrated probability that the label is correct. Excluding an incompatible pair improves selection but does not certify the remaining polygon. A sector fallback is a coarser location. Bouarada municipal records remain archived 2018 source geometry, with current accuracy unverified.', '',
        '## Topology and source limitations', '',
        f'- Invalid packed polygons: {len(invalid)}. Representative-point failures: {len(bad_representatives)}. Missing grid cells: {len(grid_errors)}.',
        f'- Named-area gaps within the country outline: approximately {area_km2(gaps):,.2f} km².',
        f'- Sector pairs with positive-area overlap: {len(sector_overlap)}.',
        f'- Non-sector pairs with partial, non-nested overlap: {len(ambiguous)}. These need semantic review; intersecting area types may both be correct.',
        '- Valid geometry means the coordinates form a usable polygon. It does not validate the real-world name, boundary, hierarchy, completeness, or date.',
        '- Source place points and unnamed residential polygons do not provide evidence for drawing an exact neighborhood boundary.',
        '- GPS uncertainty near a boundary can change the displayed name even when the boundary itself is correct.']
    if pbf:
        source = report['sourceAudit']
        lines += ['', '## Original OSM source audit', '',
            f"- Named source areas absent from the compiled catalog, excluding national/governorate/delegation containers: {len(source['omittedNamedAreas'])}.",
            f"- Unnamed residential areas in the PBF extract: {source['unnamedResidentialAreasInExtract']}.",
            f"- Alias or nearby same-name boundary review candidates: {len(source['nameLinkReviewCandidates'])}.",
            f"- Original source polygons requiring topology repair: {len(source['invalidOriginalSourceAreasRequiringRepair'])}.",
            f"- Unknown administrative-level polygons requiring review: {len(source['actionableOmissionReview']['unknownAdministrativeLevelCandidates'])}; unspecified place=locality polygons: {len(source['actionableOmissionReview']['unclassifiedLocalityCandidates'])}.",
            f"- Exact containing-polygon recoveries using expanded source aliases: {source['actionableOmissionReview']['expandedAliasExactContainmentRecoveries']}. Same-name nearby polygons without containment are not safe automatic merges.",
            '- Full source-tag counts and review records are in the JSON. Omitted places can be cities, farms, or geographic localities rather than neighborhoods; adding them blindly would create false precision.']
    (output_dir / 'neighborhood-reliability-audit.md').write_text('\n'.join(lines) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({k: report[k] for k in ('coverage', 'geometryValidation', 'pointOnlyCoverageByKind')}, ensure_ascii=False, indent=2))
    print(f'Report written to {json_path}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--assets', type=Path, default=ASSETS)
    parser.add_argument('--pbf', type=Path)
    parser.add_argument('--output-dir', type=Path, required=True)
    arguments = parser.parse_args()
    run(arguments.assets, arguments.output_dir, arguments.pbf)
