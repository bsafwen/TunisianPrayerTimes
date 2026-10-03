"""Build reusable original-member neighbor facts for a finite source-only cohort."""
import argparse
from collections import defaultdict
from itertools import combinations
import json
from pathlib import Path
import sys
from xml.etree import ElementTree as ET

from pyproj import Transformer
from shapely import from_wkb
from shapely.geometry import LineString
from shapely.ops import transform

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts.locality_automation.run_sealed_boundary_queue import checked, pin, read


def compare_member_sets(members):
    nodes, ways, conflicts = {}, {}, []
    for code, record in members.items():
        for kind, data in (('node', record['nodes']), ('way', record['ways'])):
            prior = nodes if kind == 'node' else ways
            for ident, value in data.items():
                if ident in prior and prior[ident][1] != value:
                    conflicts.append({'elementType': kind, 'id': ident, 'firstCode': prior[ident][0], 'otherCode': code})
                else:
                    prior[ident] = (code, value)
    return conflicts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    manifest = read(args.manifest)
    members, observations, uses, geometries = {}, {}, defaultdict(list), {}
    project = Transformer.from_crs(4326, 32632, always_xy=True).transform
    for ref in manifest['sourceObservations']:
        observation = read(checked(ref))
        code, ident = observation['officialCode'], observation['id']
        if code in members or code not in manifest['finiteCodes']:
            raise ValueError('Duplicate or out-of-cohort source code')
        if (observation['status'] != 'SOURCE_PREPARATION_PENDING_DISJOINT_QA'
                or observation['sourceActor'] != '/root' or observation['newCredit'] != 0):
            raise ValueError('Source preparation role differs')
        xml = ET.parse(checked(observation['sourceDocument'])).getroot()
        relation = next(row for row in xml.findall('relation') if row.attrib['id'] == ident.split(':')[-1])
        nodes = {row.attrib['id']: (row.attrib['lon'], row.attrib['lat']) for row in xml.findall('node')}
        ways = {row.attrib['id']: tuple(node.attrib['ref'] for node in row.findall('nd')) for row in xml.findall('way')}
        used = {}
        for member in relation.findall('member'):
            if member.attrib['type'] == 'way' and member.attrib.get('role', '') in ('', 'outer', 'inner'):
                way = member.attrib['ref']
                if way in used or way not in ways:
                    raise ValueError('Repeated or missing literal boundary way')
                used[way] = member.attrib.get('role', '')
                uses[way].append({'code': code, 'role': used[way]})
        raw = from_wkb(checked(observation['rawSourceGeometry']).read_bytes())
        adopted = from_wkb(checked(observation['expectedAdoptedGeometry']).read_bytes())
        if not raw.is_valid or not adopted.is_valid:
            raise ValueError('Prepared geometry is invalid')
        members[code] = {'nodes': nodes, 'ways': ways, 'boundaryRoles': used}
        geometries[code] = transform(project, raw)
        observations[code] = {'id': ident, 'name': observation['currentMetadata']['name'],
                              'sourceObservation': ref, 'sourceDocument': observation['sourceDocument'],
                              'boundaryWayCount': len(used), 'nativeParts': len(raw.geoms),
                              'packedBytesEqualCurrent': observation['packedBytesEqualCurrent'],
                              'rawAreaM2': geometries[code].area}
    if sorted(members) != sorted(manifest['finiteCodes']):
        raise ValueError('Complete declared cohort is unavailable')
    conflicts = compare_member_sets(members)
    if conflicts:
        raise ValueError({'originalSharedElementConflicts': conflicts})
    shared = []
    for way, owners in sorted(uses.items()):
        if len(owners) < 2:
            continue
        record = members[owners[0]['code']]
        coords = [tuple(float(item) for item in record['nodes'][node]) for node in record['ways'][way]]
        shared.append({'wayId': way, 'literalSourceMemberships': owners,
                       'lengthM': transform(project, LineString(coords)).length,
                       'potentialNeighborOnly': True, 'territoryOwnershipInferred': False})
    pairs = []
    for left, right in combinations(sorted(members), 2):
        overlap = geometries[left].intersection(geometries[right]).area
        common = sorted(set(members[left]['boundaryRoles']) & set(members[right]['boundaryRoles']))
        if common or overlap > 1:
            pairs.append({'codes': [left, right], 'literalSharedWayIds': common,
                          'rawSourceIntersectionAreaM2': overlap, 'positiveAreaAbove1M2': overlap > 1,
                          'ownershipOrScopeAcceptance': False})
    result = {'status': 'SOURCE_ONLY_COMPLETE_FINITE_MEMBER_NEIGHBOR_CONTEXT', 'manifest': pin(args.manifest),
              'locations': observations, 'sharedBoundaryWays': shared, 'pairObservations': pairs,
              'originalSharedElementConflicts': conflicts, 'projection': 'EPSG:32632',
              'sourceAuthor': '/root', 'independentQaPassed': False, 'newCredit': 0,
              'qualification': 'Original common-way/node declarations and raw-source pair areas only. No neighbor beyond the finite cohort is proved absent; no government extent, scope, finer ownership, GPS or independent acceptance is certified.'}
    with args.output.open('x', encoding='utf-8') as stream:
        stream.write(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + '\n')
    print(json.dumps({'status': result['status'], 'locations': len(members), 'sharedWays': len(shared),
                      'overlapFlags': sum(row['positiveAreaAbove1M2'] for row in pairs), 'newCredit': 0}))


if __name__ == '__main__':
    main()
