"""Prepare finite map queries from exact-ID area results and catalogue aliases.

No network, emulator interaction, boundary inference or geographic acceptance.
Past results guide query order; the actual new result must still be inspected.
"""
import argparse
import json
from pathlib import Path
import sys
import unicodedata

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts.locality_automation.run_sealed_boundary_queue import checked, pin, read


def normalize(query):
    return ' '.join(unicodedata.normalize('NFKC', query).casefold().split())


def plan(feature, city, observations):
    # Input files are ordered newest first. A newer POI result must not silently
    # lose to a successful older area result for the same query and exact ID.
    latest = {}
    for row in observations:
        if row['id'] == feature['id']:
            latest.setdefault(normalize(row['query']), row)
    failed = {key for key, row in latest.items()
              if row['resultType'] in ('poi_school', 'poi_business', 'poi_list', 'result_list')}
    candidates = []
    for row in latest.values():
        if row['resultType'] == 'area' and row['outlineVisible'] is True:
            candidates.append((row['query'], 'documented_exact_id_area_outline', row.get('evidence')))
    for label in [*feature.get('aliases', []), feature['name']]:
        candidates.append((label+' '+city, 'short_catalogue_name_or_alias_plus_city', None))
    unique = {}
    for query, reason, evidence in candidates:
        key = normalize(query)
        if key and key not in failed:
            unique.setdefault(key, {'query': query, 'reason': reason, 'historicalEvidence': evidence})
    return {'id': feature['id'], 'name': feature['name'], 'queries': list(unique.values()),
            'preservedPoiQueries': [row['query'] for key, row in latest.items() if key in failed],
            'requireFreshAreaTypeAndVisibleOutlineInspection': True,
            'noMatchEstablished': False, 'geographicCredit': 0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    spec = read(args.manifest)
    references = [spec['metadata'], *spec['observations']]
    features = {row['id']: row for row in read(checked(spec['metadata']))['features']}
    codes = [r['code'] for r in spec['items']]
    identifiers = [r['id'] for r in spec['items']]
    if (not 1 <= len(codes) <= 32 or len(set(codes)) != len(codes)
            or len(set(identifiers)) != len(identifiers) or not spec['city'].strip()):
        raise ValueError('Finite unique exact-ID/code query scope required')
    observations = []
    if spec.get('observationOrderWithinFiles') not in ('oldest_first', 'newest_first'):
        raise ValueError('Explicit observation ordering required; source files remain newest first')
    for ref in spec['observations']:
        document = read(checked(ref))
        records = document['observations']
        if spec['observationOrderWithinFiles'] == 'oldest_first':
            records = reversed(records)
        for row in records:
            if row['resultType'] not in ('area', 'poi_school', 'poi_business', 'poi_list', 'result_list', 'unknown', 'unavailable'):
                raise ValueError('Explicit observed result type required')
            observations.append({**row, 'evidence': ref})
    result = {'status': 'FINITE_MAP_QUERY_PREPARATION_ONLY', 'manifest': pin(args.manifest),
              'program': pin(Path(__file__)), 'rows': [], 'geographicCredit': 0,
              'networkRequestsPerformed': False, 'sourceScopeAccepted': False,
              'limitations': ['A cached query success is not a current area or geometry verification.',
                              'A POI/list result does not establish absence.',
                              'A Google outline is supporting evidence, not a survey or independent source review.']}
    for item in spec['items']:
        feature = features[item['id']]
        if feature['parentName'] != spec['exactParentName']:
            raise ValueError('Exact parent context differs')
        result['rows'].append({'code': item['code'], **plan(feature, spec['city'], observations)})
    for ref in references:
        checked(ref)
    with args.output.open('x', encoding='utf-8') as handle:
        json.dump(result, handle, ensure_ascii=False, indent=2)
    print(json.dumps({'status': result['status'], 'cases': len(codes), 'output': str(args.output)}))


if __name__ == '__main__':
    main()
