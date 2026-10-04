"""Read-only finite boundary feasibility, including every current catalogue peer.

This does not accept a source, establish official ownership, modify conflicts,
or install geometry. Geographic validation and complete-source validation are
separate annotations. Neither annotation restricts the peer search.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
from pathlib import Path
import sys

from pyproj import Transformer
from shapely import from_wkb, to_wkb, set_precision
from shapely.geometry import Point
from shapely.ops import transform

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))
from scripts.locality_automation.run_sealed_boundary_queue import checked, read, pin, active_control
from scripts.locality_automation.packed_gps_replay import PackedGpsReplay


def check_annotations(locations, full_codes, boundaries):
    by_id = {}
    codes = set()
    for row in locations:
        identifier, code = row['id'], row['code']
        if identifier in by_id or code in codes or identifier not in boundaries:
            raise ValueError('Duplicate or missing geographic catalogue binding')
        by_id[identifier] = code
        codes.add(code)
    if not set(full_codes).issubset(codes):
        raise ValueError('Complete-source codes missing from geographic bindings')
    return by_id


def inspect_peers(target_id, before, raw, canonical, metric_peers, indexes,
                  declared_conflicts, geographic, full_codes):
    result = []
    for identifier, geometry, body_ref in metric_peers:
        if identifier == target_id:
            continue
        # Positive overlap is examined across the whole current catalogue.
        # Distance filtering is solely a reporting convenience for disjoint peers.
        distance = min(raw.distance(geometry), canonical.distance(geometry),
                       before.distance(geometry))
        if distance > 500:
            continue
        old = before.intersection(geometry)
        source = raw.intersection(geometry)
        adopted = canonical.intersection(geometry)
        code = geographic.get(identifier)
        result.append({
            'id': identifier,
            'geographicValidatedCode': code,
            'completeSourceValidated': code in full_codes,
            'currentPeerPackedBody': body_ref,
            'beforeOverlapM2': old.area,
            'rawSourceOverlapM2': source.area,
            'canonicalSourceOverlapM2': adopted.area,
            'newRawOverlapM2': source.difference(old).area,
            'newCanonicalOverlapM2': adopted.difference(old).area,
            'minimumDistanceM': distance,
            'existingDeclaredConflict': tuple(sorted((indexes[target_id], indexes[identifier])))
                in declared_conflicts,
        })
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    manifest = read(args.manifest)
    active_control(manifest)
    if Path.cwd().resolve() != Path(manifest['cwd']).resolve():
        raise ValueError('Existing producer cwd required')
    if args.output.exists():
        raise ValueError('Fresh exclusive output required')
    codes = [item['code'] for item in manifest['items']]
    if not codes or len(codes) != len(set(codes)) or len(codes) > 32:
        raise ValueError('Finite unique candidate set required')
    assets = {key: checked(ref) for key, ref in manifest['assets'].items()}
    catalogue = PackedGpsReplay(assets['metadata'], assets['binary'])
    map_data = read(checked(manifest['map']))
    task_report = read(checked(manifest['taskReport']))
    by_id = {row['id']: row for row in catalogue.boundaries.values()}
    full_codes = set(task_report['summary']['explicitFullSourceBoundaryLocalityCodes'])
    geographic = check_annotations(map_data['locations'], full_codes, by_id)
    if (len(geographic) != task_report['summary']['uniqueValidatedLocations']
            or len(full_codes) != task_report['summary']['explicitFullSourceBoundaryLocationCount']):
        raise ValueError('Map/report scope counts differ')
    to_metric = Transformer.from_crs(4326, 32632, always_xy=True).transform
    to_geo = Transformer.from_crs(32632, 4326, always_xy=True).transform
    indexes = {row['id']: index for index, row in catalogue.boundaries.items()}
    metric_peers = []
    for identifier in by_id:
        geometry = catalogue.geometry(identifier)
        metric_peers.append((identifier, transform(to_metric, geometry), {
            'catalogueMetadata': manifest['assets']['metadata'],
            'catalogueBinary': manifest['assets']['binary'],
            'id': identifier,
            'decodedWkbSha256': __import__('hashlib').sha256(to_wkb(geometry)).hexdigest(),
        }))
    rows = []
    for item in manifest['items']:
        identifier = item['id']
        if identifier not in by_id or item['code'] in full_codes:
            raise ValueError('Candidate absent or already complete-source validated')
        source_path = checked(item['sourceMetricWkb'])
        raw = from_wkb(source_path.read_bytes())
        if not raw.is_valid or raw.is_empty or raw.geom_type not in ('Polygon', 'MultiPolygon'):
            raise ValueError('Pinned raw source must be a valid positive polygon')
        raw_geo = transform(to_geo, raw)
        canonical_geo = set_precision(raw_geo, 1e-6)
        canonical = transform(to_metric, canonical_geo)
        before_geo = catalogue.geometry(identifier)
        before = transform(to_metric, before_geo)
        peers = inspect_peers(identifier, before, raw, canonical, metric_peers,
                              indexes, catalogue.conflicts, geographic, full_codes)
        changes = [peer for peer in peers if peer['newCanonicalOverlapM2'] > 1.0]
        row = {
            'code': item['code'], 'id': identifier, 'name': by_id[identifier]['name'],
            'sourceMetricWkb': item['sourceMetricWkb'],
            'currentSourceSymmetricDifferenceM2': raw.symmetric_difference(before).area,
            'canonicalEqualsCurrentTopologically': canonical_geo.equals(before_geo),
            'canonicalWkbEqualsCurrent': to_wkb(canonical_geo) == to_wkb(before_geo),
            'currentManualPointInsideRaw': raw_geo.covers(Point(
                catalogue.by_id[identifier]['lng'], catalogue.by_id[identifier]['lat'])),
            'peers': peers,
            'newOverlapOverOneSquareMeterPeerCount': len(changes),
            'newOverlapWithGeographicValidatedPeers': [peer['geographicValidatedCode']
                for peer in changes if peer['geographicValidatedCode']],
            'newOverlapWithCompleteSourceValidatedPeers': [peer['geographicValidatedCode']
                for peer in changes if peer['completeSourceValidated']],
            'decision': ('HOLD_NEW_CURRENT_PEER_OVERLAPS_REQUIRE_SOURCE_RECONCILIATION'
                         if changes else 'NO_NEW_MATERIAL_OVERLAP_FOUND_OTHER_GATES_UNCHECKED'),
        }
        rows.append(row)
    # All catalogue/report/source pins must still hold after the read-only run.
    for ref in list(manifest['assets'].values()) + [manifest['map'], manifest['taskReport']]:
        checked(ref)
    result = {
        'status': 'READ_ONLY_ALL_CURRENT_NEIGHBOR_FEASIBILITY_NO_ACCEPTANCE',
        'atUtc': datetime.now(timezone.utc).isoformat(),
        'manifest': pin(args.manifest.resolve()),
        'program': pin(Path(__file__).resolve()),
        'currentBoundaryCount': len(by_id), 'geographicValidatedCount': len(geographic),
        'completeSourceCount': len(full_codes), 'rows': rows,
        'assetChanges': False, 'ledgerWrites': False, 'geographicCredit': 0,
        'limits': [
            'One square meter is a diagnostic reporting flag, never an acceptance waiver.',
            'Source extraction, identity, legal scope and GPS/prayer/persistence gates remain unchecked here.',
            'The canonical geometry is diagnostic only and must pass the original packer before installation.',
            'Every current peer is inspected; scope annotations never filter neighbors.',
        ],
    }
    with args.output.open('x', encoding='utf-8') as handle:
        __import__('json').dump(result, handle, ensure_ascii=False, indent=2)
    print(__import__('json').dumps({
        'status': result['status'], 'output': str(args.output),
        'rows': [{key: row[key] for key in ('code', 'decision',
            'newOverlapWithGeographicValidatedPeers', 'newOverlapOverOneSquareMeterPeerCount')}
            for row in rows]}, ensure_ascii=False))


if __name__ == '__main__':
    main()
