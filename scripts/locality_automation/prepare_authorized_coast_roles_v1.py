"""Pin an explicitly authorized coastline role and prove hidden duplicate strokes."""
import argparse
import json
import sys
from pathlib import Path
import pymupdf

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import read, pin, checked, active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.isie_pdf_inventory import _path_runs

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--plan', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    spec = read(args.manifest)
    control = active_control(spec)
    plan = read(args.plan)
    code = plan['officialCode']
    if code not in control['approvedCycle'+str(control['iteration'])+'AcceptancePool']:
        raise ValueError('Outside current finite source scope')
    if plan['authorization']['sourceRole'] != 'direct-user-instruction' or not plan['authorization']['userStatement'].strip():
        raise ValueError('Explicit human coastline-role instruction required')
    case = dict(next(row for row in spec['cases'] if row['officialCode'] == code))
    if case['sourcePdf'] != plan['sourcePdf']:
        raise ValueError('Authorized exact original PDF differs')
    checked(plan['review'])
    inventory = read(checked(case['sourceInventory']))
    paths = inventory['pages'][0]['nativePaths']
    results = []
    with pymupdf.open(checked(case['sourcePdf'])) as document:
        drawings = document[0].get_drawings()
        for pair in plan['pairs']:
            blue_index, black_index = pair['underlyingBlueDrawingIndex'], pair['overlyingBlackDrawingIndex']
            blue, black = drawings[blue_index], drawings[black_index]
            blue_runs, blue_unsupported = _path_runs(blue)
            black_runs, black_unsupported = _path_runs(black)
            if (blue_unsupported or black_unsupported or blue_runs != black_runs
                    or tuple(black['color']) != (0, 0, 0)
                    or black['seqno'] <= blue['seqno'] or black['stroke_opacity'] != 1
                    or black['width'] < blue['width']
                    or black.get('lineCap') != blue.get('lineCap')
                    or black.get('lineJoin') != blue.get('lineJoin')):
                raise ValueError('An exact wider opaque later coastline covering the blue copy is required')
            blue_item = next(row for row in paths if row['drawingIndex'] == blue_index)
            black_item = next(row for row in paths if row['drawingIndex'] == black_index)
            for item, runs in [(blue_item, blue_runs), (black_item, black_runs)]:
                if [run['nativePagePoints'] for run in item['runs']] != [[list(point) for point in run] for run in runs]:
                    raise ValueError('Original native inventory vertices changed')
            if blue_item['strokeFamily'] != 'blue' or black_item['strokeFamily'] != 'black':
                raise ValueError('Original source roles differ')
            blue_item['originalVisibleStrokeBeforeCompositing'] = blue_item['visibleStroke']
            blue_item['visibleStroke'] = False
            blue_item['coveredByExactOpaqueCoastlineDrawingIndex'] = black_index
            black_item['originalStrokeFamily'] = black_item['strokeFamily']
            black_item['strokeFamily'] = 'red'
            black_item['sourceRole'] = 'human-authorized-outer-coastline'
            results.append({**pair, 'exactNativeRunsEqual': True, 'blueFullyCoveredByLaterWiderOpaqueBlack': True})
    args.output.mkdir()
    inventory['sourceLineRoleSuccessor'] = {'originalInventory': case['sourceInventory'], 'sourcePdf': case['sourcePdf'], 'plan': pin(args.plan.resolve()), 'authorization': plan['authorization'], 'pairs': results, 'nativePointsUnchanged': True, 'credit': 0}
    case['sourceInventory'] = put(args.output/(code+'-inventory.json'), inventory)
    put(args.output/'manifest.json', {**spec, 'cases': [case], 'coastalRolePlan': pin(args.plan.resolve()), 'credit': 0})
    put(args.output/'role-receipt.json', inventory['sourceLineRoleSuccessor'])
    print(json.dumps({'code': code, 'coastalPaths': len(results), 'nativePointsUnchanged': True, 'credit': 0}))

if __name__ == '__main__':
    main()
