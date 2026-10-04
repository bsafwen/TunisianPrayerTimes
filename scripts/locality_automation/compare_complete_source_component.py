"""Compare a complete pinned five-body source component; never install or repair it."""
import argparse
from datetime import datetime, timezone
from itertools import combinations
import json
from pathlib import Path
import sys

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from pyproj import CRS
from shapely import from_wkb
from shapely.affinity import affine_transform

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts.locality_automation.audit_native_replay_layers import audit
from scripts.locality_automation.audit_paired_native_faces import intersection_measure
from scripts.locality_automation.compare_isie_neighbor_linework import inventory_case, affine_coefficients
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree
from scripts.locality_automation.isie_candidate_comparison import Catalog
from scripts.locality_automation.plot_paired_native_faces import draw
from scripts.locality_automation.run_sealed_boundary_queue import active_control, checked, pin, read
from scripts.locality_automation.work_window_guard import parse_utc


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    spec = read(args.manifest)
    control = active_control(spec)
    if (datetime.now(timezone.utc) >= parse_utc(control['safeSourceQaStartUtc'])
            or spec['credit'] != 0 or spec['sourceAndQaActor'] != '/root'):
        raise ValueError('Active finite root source diagnostic only')
    expected = {'235653', '426055', '426053', '426052', '426051'}
    if len(spec['rows']) != 5 or {row['officialCode'] for row in spec['rows']} != expected:
        raise ValueError('Exactly the complete five-body Es-Sray component is required')
    check_tree(spec)
    plan = read(checked(spec['plan']))
    members = {row['appId'] for row in spec['rows']}
    components = [component for component in plan['components'] if set(component['ids']) == members]
    if len(components) != 1 or spec['primaryCode'] not in control['approvedReconciliationPool']:
        raise ValueError('Complete frozen component and approved primary required')
    catalog = Catalog(checked(spec['metadata']), checked(spec['binary']))
    sources, installed, checks = {}, {}, []
    for row in spec['rows']:
        code = row['officialCode']
        sources[code] = from_wkb(checked(row['registeredWkb']).read_bytes())
        source_case = inventory_case(row)
        page_face = from_wkb(checked(row['pageWkb']).read_bytes())
        if affine_transform(page_face, affine_coefficients(source_case['matrix'])).wkb != sources[code].wkb:
            raise ValueError('Pinned native registration does not reproduce the entire source face')
        installed[code] = catalog.geometry(row['appId'], CRS.from_epsg(32632))
        checks.append(audit(row, row))
    comparisons = []
    for first, second in combinations(sorted(expected), 2):
        comparisons.append({'codes': [first, second], 'scenarios': {
            'installedBoth': intersection_measure(installed[first], installed[second]),
            'firstSourceSecondInstalled': intersection_measure(sources[first], installed[second]),
            'firstInstalledSecondSource': intersection_measure(installed[first], sources[second]),
            'originalWholeSourceBoth': intersection_measure(sources[first], sources[second])}})
    args.output.mkdir(exist_ok=False)
    fig, axes = plt.subplots(1, 2, figsize=(13, 8))
    colors = ['#1756a9', '#be4238', '#26734f', '#8754a3', '#a87816']
    for bodies, axis, title in ((installed, axes[0], 'Current installed bodies'),
                                 (sources, axes[1], 'Whole original source hypotheses')):
        for code, color in zip(sorted(expected), colors):
            draw(axis, bodies[code], color, code)
        axis.set_title(title)
        axis.set_aspect('equal')
        axis.ticklabel_format(style='plain', useOffset=False)
        axis.legend()
        axis.set_xlabel('EPSG:32632 easting (m)')
        axis.set_ylabel('Northing (m)')
    fig.suptitle('UNINSTALLED SOURCE COMPARISON - raw boundaries, every seam and hold retained')
    fig.tight_layout()
    image = args.output / 'complete-source-component.png'
    fig.savefig(image, dpi=150)
    plt.close(fig)
    check_tree(spec)
    result = {'status': 'COMPLETE_FIVE_BODY_SOURCE_COMPARISON_NO_ACCEPTANCE', 'manifest': pin(args.manifest),
              'nativeChecks': checks, 'comparisons': comparisons, 'image': pin(image),
              'frozenComponent': components[0], 'geometryChanged': False, 'protectedNeighborChanged': False,
              'independentQaPassed': False, 'sourceScopeAccepted': False, 'credit': 0,
              'qualification': 'Raw overlaps from separate embedded registrations remain diagnostics. '
                               'No survey, civil scope, border snap, protected revision, installation or acceptance is asserted.'}
    (args.output / 'report.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'cases': len(checks), 'pairs': len(comparisons),
                      'nativeHolds': [{'code': row['officialCode'], 'holds': row['holds']} for row in checks], 'credit': 0}))


if __name__ == '__main__':
    main()
