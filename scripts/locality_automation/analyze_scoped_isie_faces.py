"""Reproduce literal closed red source candidates and current-body differences.

This is finite preparation, not source ownership, independent QA or acceptance.
It neither repairs invalid faces nor selects among competing closed candidates.
"""
import argparse
import json
from pathlib import Path
import sys

import numpy as np
import pymupdf
from pyproj import Transformer
from shapely import set_precision, to_wkb
from shapely.affinity import affine_transform
from shapely.geometry import Polygon, box
from shapely.ops import transform
from shapely.validation import explain_validity

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts.locality_automation.run_sealed_boundary_queue import active_control, checked, pin, read
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree
from scripts.locality_automation.isie_pdf_inventory import _path_runs
from scripts.locality_automation.compare_isie_neighbor_linework import inventory_case, affine_coefficients
from scripts.locality_automation.packed_gps_replay import PackedGpsReplay


def save(path, value):
    with path.open('x', encoding='utf-8') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write('\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    spec = read(args.manifest)
    active_control(spec)
    check_tree(spec)
    if args.output.exists() or not 1 <= len(spec['cases']) <= 32:
        raise ValueError('Fresh finite output required')
    codes = [row['code'] for row in spec['cases']]
    if len(set(codes)) != len(codes):
        raise ValueError('Duplicate case')
    args.output.mkdir()
    replay = PackedGpsReplay(checked(spec['metadata']), checked(spec['binary']))
    to_geo = Transformer.from_crs(32632, 4326, always_xy=True).transform
    to_metric = Transformer.from_crs(4326, 32632, always_xy=True).transform
    results, unique = [], {}
    for row in spec['cases']:
        code, identifier = row['code'], row['id']
        inventory = read(checked(row['sourceInventory']))
        page_data = inventory['pages'][0]
        result = {'code': code, 'id': identifier, 'sourcePdf': row['sourcePdf'],
                  'sourceInventory': row['sourceInventory'], 'sourceActor': '/root',
                  'sourceScopeAccepted': False, 'independentQaPassed': False,
                  'currentName': replay.by_id[identifier]['name'], 'closedCandidates': []}
        case = inventory_case(row)
        result['registration'] = case['registration']
        with pymupdf.open(checked(row['sourcePdf'])) as doc:
            if doc.page_count != 1:
                raise ValueError('Explicit single-page source expected')
            page = doc[0]
            drawings = page.get_drawings()
            result['extendedClipRecords'] = [
                {'seqno': d.get('seqno'), 'level': d.get('level'),
                 'scissor': list(d['scissor']) if d.get('scissor') else None}
                for d in page.get_drawings(extended=True) if d['type'] == 'clip']
            viewport = Polygon(page_data['georeferences'][0]['pageControls'])
            for drawing in page_data['nativePaths']:
                if drawing['layer'] != 'circonscription_isie2023' or not drawing['visibleStroke']:
                    continue
                fresh = drawings[drawing['drawingIndex']]
                runs, unsupported = _path_runs(fresh)
                if unsupported != drawing['unsupportedKinds']:
                    raise ValueError('Original drawing item types changed')
                for ordinal, run in enumerate(drawing['runs']):
                    if not run['closed']:
                        continue
                    points = run['nativePagePoints']
                    if [list(p) for p in runs[ordinal]] != points:
                        raise ValueError('Fresh original native coordinates differ')
                    native = Polygon(points)
                    metric = affine_transform(native, affine_coefficients(case['matrix']))
                    candidate = {'drawingIndex': drawing['drawingIndex'], 'run': ordinal,
                                 'vertices': len(points), 'valid': metric.is_valid,
                                 'validity': explain_validity(metric), 'nativeAreaPoints2': native.area,
                                 'viewportCoversNativeFace': viewport.covers(native),
                                 'labelsInsideObservationOnly': run.get('redLabelsInside', []),
                                 'lineWidthPagePoints': drawing['lineWidthPagePoints']}
                    if metric.is_valid and not metric.is_empty and metric.area > 0:
                        raw_geo = transform(to_geo, metric)
                        grid = set_precision(raw_geo, 1e-6)
                        before = replay.geometry(identifier)
                        before_metric = transform(to_metric, before)
                        prefix = args.output / f'{code}-drawing-{drawing["drawingIndex"]}-run-{ordinal}'
                        for name, geometry in (('page', native), ('metric', metric), ('geographic', raw_geo), ('grid', grid)):
                            path = Path(str(prefix) + '-' + name + '.wkb')
                            with path.open('xb') as stream:
                                stream.write(to_wkb(geometry))
                            candidate[name + 'Geometry'] = pin(path)
                        candidate.update(areaM2=metric.area, currentAreaM2=before_metric.area,
                            symmetricDifferenceM2=metric.symmetric_difference(before_metric).area,
                            gainedM2=metric.difference(before_metric).area,
                            lostM2=before_metric.difference(metric).area,
                            hausdorffM=metric.hausdorff_distance(before_metric),
                            gridEqualsCurrentTopologically=grid.equals(before),
                            currentManualPointInsideRaw=raw_geo.contains(__import__('shapely').Point(
                                replay.by_id[identifier]['lng'], replay.by_id[identifier]['lat'])))
                    result['closedCandidates'].append(candidate)
            if len(result['closedCandidates']) == 1 and result['closedCandidates'][0]['valid']:
                unique[code] = __import__('shapely').from_wkb(checked(result['closedCandidates'][0]['metricGeometry']).read_bytes())
            result['exactConnectedRingLeads'] = [{k: x[k] for k in ('areaPagePoints2', 'boundsPagePoints', 'contributors', 'redLabelsInside') if k in x}
                for x in page_data['lineTopology'].get('exactConnectedRings', [])]
        results.append(result)
        save(args.output / f'{code}-analysis.json', result)
        print(json.dumps({'code': code, 'closedCandidates': len(result['closedCandidates']),
                          'equalCurrent': [x.get('gridEqualsCurrentTopologically') for x in result['closedCandidates']],
                          'sourceScopeAccepted': False}), flush=True)
    pairwise = [{'a': a, 'b': b, 'rawOverlapM2': unique[a].intersection(unique[b]).area,
                 'distanceM': unique[a].distance(unique[b])}
                for i, a in enumerate(sorted(unique)) for b in sorted(unique)[i+1:]
                if unique[a].distance(unique[b]) < 500]
    check_tree(spec)
    save(args.output / 'analysis-report.json', {'status': 'SCOPED_NATIVE_SOURCE_PREPARATION_NO_ACCEPTANCE',
         'manifest': pin(args.manifest), 'program': pin(Path(__file__)), 'cases': results,
         'uniqueValidClosedRedCandidateCount': len(unique), 'pairwiseRawDiagnostics': pairwise,
         'sourceScopeAccepted': False, 'independentQaPassed': False, 'geographicCredit': 0,
         'limits': ['Native hypotheses and labels are not ownership acceptance.',
                    'All clipping, legal scope, neighbors and independent source review remain required.',
                    'No candidate repair, peer change, GPS replay or asset write performed.']})


if __name__ == '__main__':
    main()
