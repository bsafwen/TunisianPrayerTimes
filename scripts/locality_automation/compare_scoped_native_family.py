"""Compare a finite selected ISIE family without adopting or repairing geometry.

Reports registered source overlaps separately from printed-stroke corridors,
and contextual source counterparts separately from current catalogue peers.
No numeric reporting threshold releases an evidence or acceptance gate.
"""
import argparse
import json
from pathlib import Path
import sys

import numpy as np
import pymupdf
from shapely import from_wkb
from shapely.affinity import affine_transform
from shapely.geometry import Polygon

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts.locality_automation.run_sealed_boundary_queue import active_control, checked, pin, read
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree
from scripts.locality_automation.compare_isie_neighbor_linework import inventory_case, affine_coefficients
from scripts.locality_automation.isie_pdf_inventory import _path_runs


def overlap_diagnostic(a, b, half_width_a, half_width_b):
    overlap = a.intersection(b)
    corridor = a.boundary.buffer(half_width_a).union(b.boundary.buffer(half_width_b))
    return {'rawOverlapM2': overlap.area,
            'overlapOutsideEitherPrintedStrokeCorridorM2': overlap.difference(corridor).area,
            'sourceDistanceM': a.distance(b),
            'printedHalfWidthsM': [half_width_a, half_width_b],
            'qualification': 'Descriptive printed ink comparison only; no acceptance tolerance'}


def reproduce_selected(row):
    case = inventory_case(row)
    drawing = next(x for x in case['page']['nativePaths']
                   if x['drawingIndex'] == row['selectedDrawingIndex'])
    if drawing['layer'] != 'circonscription_isie2023' or not drawing['visibleStroke']:
        raise ValueError('Explicit visible original red drawing required')
    run = drawing['runs'][row['selectedRun']]
    if not run['closed'] or drawing['unsupportedKinds']:
        raise ValueError('Literal closed supported native run required')
    with pymupdf.open(checked(row['sourcePdf'])) as document:
        if document.page_count != 1:
            raise ValueError('One original page required')
        fresh_runs, unsupported = _path_runs(document[0].get_drawings()[row['selectedDrawingIndex']])
        if unsupported or [list(p) for p in fresh_runs[row['selectedRun']]] != run['nativePagePoints']:
            raise ValueError('Fresh original native coordinates differ')
    native = Polygon(run['nativePagePoints'])
    raw = affine_transform(native, affine_coefficients(case['matrix']))
    frozen = (from_wkb(checked(row['sourceMetricWkb']).read_bytes())
              if 'sourceMetricWkb' in row else raw if row.get('supportOnly') else None)
    if frozen is None or raw.wkb != frozen.wkb or not raw.is_valid or raw.is_empty:
        raise ValueError('Selected original native face differs or is invalid')
    # Upper physical half-width uses the largest singular value of the affine
    # transform. This is a printed ink measurement, not a positional tolerance.
    scale = float(np.linalg.svd(case['matrix'][:2], compute_uv=False).max())
    half_width = drawing['lineWidthPagePoints'] * scale / 2
    return raw, half_width, case


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    spec = read(args.manifest)
    control = active_control(spec)
    check_tree(spec)
    rows = spec['items']
    codes = [r['code'] for r in rows]
    if (not 1 <= len(rows) <= 32 or len(codes) != len(set(codes))
            or set(codes) != set(spec['exactApprovedCodes'])
            or not set(codes).issubset(control[spec['approvedPoolField']])):
        raise ValueError('Exact unique authorized finite family required')
    contexts = spec.get('contexts', [])
    if (len(contexts) > 8 or any(not r.get('supportOnly') or r['supportsTargetCode'] not in codes
                               for r in contexts)):
        raise ValueError('Bounded source contexts must support a selected case only')
    if args.output.exists():
        raise ValueError('Fresh output required')
    args.output.mkdir()
    family = {r['code']: reproduce_selected(r) for r in rows}
    context_faces = [(r, reproduce_selected(r)) for r in contexts]
    pairs = []
    for i, a in enumerate(codes):
        for b in codes[i+1:]:
            ga, wa, ca = family[a]
            gb, wb, cb = family[b]
            if ga.distance(gb) > 500:
                continue
            pairs.append({'a': a, 'b': b, **overlap_diagnostic(ga, gb, wa, wb),
                          'aRegistration': ca['registration'], 'bRegistration': cb['registration']})
    support = []
    for row, (geometry, width, case) in context_faces:
        code = row['supportsTargetCode']
        target, target_width, target_case = family[code]
        support.append({'contextCode': row['code'], 'supportsTargetCode': code,
                        **overlap_diagnostic(target, geometry, target_width, width),
                        'registration': case['registration'], 'supportOnly': True})
    import matplotlib
    matplotlib.use('Agg')
    from matplotlib import pyplot as plt
    figure, axis = plt.subplots(figsize=(10, 11))
    for row in rows:
        geometry, _, _ = family[row['code']]
        x, y = geometry.exterior.xy
        axis.plot(x, y, linewidth=1.3)
        point = geometry.representative_point()
        axis.text(point.x, point.y, row['code'], fontsize=8)
    for row, (geometry, _, _) in context_faces:
        x, y = geometry.exterior.xy
        axis.plot(x, y, color='#777777', linestyle='--', linewidth=1)
        point = geometry.representative_point()
        axis.text(point.x, point.y, row['code']+' context', fontsize=7, color='#555555')
    axis.set_aspect('equal')
    axis.ticklabel_format(style='plain', useOffset=False)
    axis.set_xlabel('EPSG:32632 easting (m)')
    axis.set_ylabel('EPSG:32632 northing (m)')
    axis.set_title('Selected ISIE native outlines — UNVALIDATED candidates\nNo asset changes, geometry repair, acceptance or geographic credit')
    figure.tight_layout()
    figure.savefig(args.output/'selected-family.png', dpi=160)
    plt.close(figure)
    check_tree(spec)
    result = {'status': 'FINITE_SELECTED_FAMILY_DIAGNOSTIC_NO_ACCEPTANCE',
              'manifest': pin(args.manifest), 'program': pin(Path(__file__)),
              'exactCodes': codes, 'pairs': pairs, 'sourceContexts': support,
              'plot': pin(args.output/'selected-family.png'),
              'sourceScopeAccepted': False, 'independentQaPassed': False,
              'assetChanges': False, 'ledgerWrites': False, 'geographicCredit': 0,
              'limits': ['Buffers measure printed ink only and never modify selected polygons.',
                         'A source-author/reviewer separation and original acceptance gates remain required.',
                         'Neighboring source context is not new location validation.']}
    with (args.output/'report.json').open('x', encoding='utf-8') as handle:
        json.dump(result, handle, ensure_ascii=False, indent=2)
    print(json.dumps({'status': result['status'], 'cases': len(rows), 'pairs': len(pairs),
                      'contexts': len(contexts), 'credit': 0}))


if __name__ == '__main__':
    main()
