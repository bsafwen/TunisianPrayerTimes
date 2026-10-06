"""Merge explicit finite original reviews and check native gates and incident pairs."""
import argparse
import json
import sys
from pathlib import Path
from shapely import from_wkb
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import read, pin, checked, active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.registration_policy_v2 import require_registration
from scripts.locality_automation.native_administrative_policy_v1 import require_native_administration

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--reviews', nargs='+', type=Path, required=True)
    parser.add_argument('--output-prefix', type=Path, required=True)
    args = parser.parse_args()
    spec = read(args.manifest)
    control = active_control(spec)
    codes = spec['exactTargets']
    if codes != control['approvedCycle'+str(control['iteration'])+'AcceptancePool']:
        raise ValueError('Current complete finite pool differs')
    rows = {}
    for path in args.reviews:
        for row in read(path)['rows']:
            rows[row['officialCode']] = row
    if set(rows) != set(codes):
        raise ValueError('Missing or extraneous source bodies')
    geometries = {}
    for code in codes:
        row = rows[code]
        if 'error' in row or not row['wholeNativeFaceInsidePage'] or not row['originalNativePointsMatchSavedInventory']:
            raise ValueError('Original source held: '+code)
        require_native_administration(code, row, control)
        for part in row.get('constituentNativeFacts', [row]):
            require_registration(code, part['sourcePdf'], part['registration'], control)
        for key in ['geometry', 'rawSourceGeometry', 'sourceMetricGeometry', 'nativePageGeometry']:
            geometry = from_wkb(checked(row[key]).read_bytes())
            if not geometry.is_valid or geometry.is_empty:
                raise ValueError('Invalid original geometry: '+code)
            if key == 'sourceMetricGeometry':
                geometries[code] = geometry
    pairs = []
    for index, first in enumerate(codes):
        a = geometries[first]
        for second in codes[index+1:]:
            b = geometries[second]
            if not a.envelope.buffer(5).intersects(b.envelope):
                continue
            overlap = a.intersection(b).area
            shared = a.boundary.intersection(b.boundary.buffer(5)).length
            if overlap > 1 or shared > 10:
                pairs.append({'a': first, 'b': second, 'overlapM2': overlap,
                              'overlapFractionSmaller': overlap/min(a.area, b.area),
                              'sharedWithin5mM': shared, 'gapM': a.distance(b)})
    prefix = str(args.output_prefix)
    facts = put(Path(prefix+'-source-facts.json'), {'rows': [rows[code] for code in codes],
                'originalReviews': [pin(path.resolve()) for path in args.reviews], 'sourceOnlyCredit': 0})
    put(Path(prefix+'-paired-facts.json'), {'pairs': pairs, 'sourceFacts': facts,
        'qualification': 'Only finite proposed source bodies compared. Literal original registration seams retained; no snapping or unrelated behavior probes.', 'credit': 0})
    print(json.dumps({'completeNativeBodiesPrepared': len(codes),
                      'materialPairs': [pair for pair in pairs if pair['overlapFractionSmaller'] > .01],
                      'maximumOverlapFraction': max([pair['overlapFractionSmaller'] for pair in pairs], default=0),
                      'sourceOnlyCredit': 0}))

if __name__ == '__main__':
    main()
