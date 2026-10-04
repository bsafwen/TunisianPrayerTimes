"""Read the finite reviewed originals and render source faces; no live changes."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sys
import numpy as np
import pypdf
import pymupdf
from pyproj import Transformer
from shapely import set_precision, from_wkb
from shapely.geometry import LineString, Point, Polygon, box
from shapely.ops import polygonize_full, unary_union, transform

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import read, pin, checked, active_control
from scripts.locality_automation.isie_pdf_inventory import _georeferences, _path_runs
from scripts.locality_automation.isie_candidate_comparison import _map_geometry, _name_match
from scripts.locality_automation.audit_reviewed_source_family import put


def review(spec, output):
    control = active_control(spec)
    if spec['exactTargets'] != control['approvedCycle'+str(control['iteration'])+'AcceptancePool']:
        raise ValueError('Current finite source scope differs')
    output.mkdir()
    rows = []
    ll = Transformer.from_crs(32632, 4326, always_xy=True).transform
    for case in spec['cases']:
        try:
            code = case['officialCode']
            pdf = checked(case['sourcePdf'])
            inventory = read(checked(case['sourceInventory']))['pages'][0]
            with pymupdf.open(pdf) as doc:
                page = doc[0]
                picture = output / (code + '-original.png')
                page.get_pixmap(matrix=pymupdf.Matrix(1.8, 1.8), alpha=False).save(picture)
                original = pypdf.PdfReader(pdf)
                references = _georeferences(original.pages[0], page)
                reference = next(r for r in references if r['status'] == 'fitted')
                if reference['crsEpsg'] != 32632 or reference['controlCount'] != 4 or reference['maxControlResidualMeters'] > .5:
                    raise ValueError('Original registration held: ' + code)
                matrix = np.asarray(reference['affineMapUnitsFromPagePoints'])
                design = np.column_stack((reference['pageControls'], np.ones(4)))
                projection = Transformer.from_crs(4326, 32632, always_xy=True)
                targets = np.asarray([projection.transform(lon, lat) for lat, lon in reference['geographicControlsLatLon']])
                loo = [float(np.linalg.norm(design[i] @ np.linalg.solve(np.delete(design, i, 0), np.delete(targets, i, 0)) - targets[i])) for i in range(4)]
                if max(loo) > 1.5:
                    raise ValueError('Original held-out controls held: ' + code)
                drawings = page.get_drawings()
                source_lines = {'red': [], 'blue': [], 'black': []}
                native_matches = []
                map_frames = []
                for item in inventory['nativePaths']:
                    if not item['relevantLineworkHint'] or not item['visibleStroke'] or item['strokeFamily'] not in source_lines:
                        continue
                    d = drawings[item['drawingIndex']]
                    runs, unsupported = _path_runs(d)
                    if unsupported:
                        raise ValueError('Unsupported original boundary path: ' + code)
                    for i, points in enumerate(runs):
                        if [list(p) for p in points] != item['runs'][i]['nativePagePoints']:
                            raise ValueError('Fresh native path differs from saved inventory: ' + code)
                        is_frame = False
                        if item['strokeFamily'] == 'black' and len(points) == 5 and points[0] == points[-1]:
                            rectangle = Polygon(points)
                            is_frame = rectangle.is_valid and rectangle.equals(box(*rectangle.bounds)) and rectangle.area > page.rect.width * page.rect.height * .8
                            if is_frame:
                                map_frames.append(rectangle.boundary)
                        if len(points) > 1 and not is_frame:
                            source_lines[item['strokeFamily']].append(LineString(points))
                    native_matches.append(item['drawingIndex'])
                lines = {k: unary_union(v) for k, v in source_lines.items()}
                diagnostic = case.get('diagnosticTargetLabelFace') or case.get('selectedSourceFace') or {}
                expected_text = diagnostic.get('targetLabelText')
                leads = inventory['labeledAreaLeads']
                if diagnostic.get('targetLabelIndex') is not None:
                    own = [leads[diagnostic['targetLabelIndex']]]
                    if own[0]['text'] != expected_text or (own[0]['centerPagePoints'][1] <= 100 and not case.get('reviewedInMapLabel')):
                        raise ValueError('Pinned in-map label differs: '+code)
                else:
                    own = [v for v in leads if v['centerPagePoints'][1]>100 and (v['text']==expected_text if expected_text else _name_match(v['text'],case['officialName']))]
                red_faces, cuts, dangles, invalid = polygonize_full(lines['red'])
                faces = [p for p in red_faces.geoms if any(p.covers(Point(v['centerPagePoints'])) for v in own)]
                method = 'complete_original_red_face'
                if not faces or case.get('useAdministrativeFaces'):
                    complete, cuts, dangles, invalid = polygonize_full(unary_union(list(lines.values())))
                    faces = [p for p in complete.geoms if any(p.covers(Point(v['centerPagePoints'])) for v in own)]
                    method = 'complete_original_red_and_administrative_face'
                if case.get('reviewedNativePageFace'):
                    selected = from_wkb(checked(case['reviewedNativePageFace']).read_bytes())
                    matching = [g for g in red_faces.geoms if g.equals_exact(selected,0)]
                    if len(matching)!=1:
                        raise ValueError('Reviewed face is not exactly one original closed red face: '+code)
                    faces=matching
                    method='complete_original_red_face_requires_paired_attribution'
                if case.get('reviewedNativePageFaceSet'):
                    complete = polygonize_full(unary_union(list(lines.values())))[0]
                    pieces = []
                    for ref in case['reviewedNativePageFaceSet']:
                        selected = from_wkb(checked(ref).read_bytes())
                        matching = [g for g in complete.geoms if g.equals(selected)]
                        if len(matching) != 1 or not selected.is_valid:
                            raise ValueError('Reviewed piece is not exactly one original closed native face: '+code)
                        pieces.append(matching[0])
                    if any(g.intersection(h).area > 0 for i,g in enumerate(pieces) for h in pieces[i+1:]):
                        raise ValueError('Reviewed constituent faces overlap: '+code)
                    faces = [unary_union(pieces)]
                    method = 'complete_same_sheet_literal_constituent_face_union'
                if len(faces) != 1 or not faces[0].is_valid:
                    raise ValueError('No unique native own-title face: ' + code)
                native = faces[0]
                if any(native.boundary.intersection(frame).length > .01 for frame in map_frames):
                    raise ValueError('Source boundary uses printed map frame: '+code)
                metric = _map_geometry(native, reference)
                raw = transform(ll, metric)
                grid = set_precision(raw, 1e-6)
                if grid.is_empty or not grid.is_valid:
                    raise ValueError('App-grid representation held: ' + code)
                raw_ref = put_geometry(output / (code + '-raw.wkb'), raw)
                grid_ref = put_geometry(output / (code + '-grid.wkb'), grid)
                metric_ref = put_geometry(output / (code + '-metric.wkb'), metric)
                native_ref = put_geometry(output / (code + '-page.wkb'), native)
                labels = []
                for label in inventory['labeledAreaLeads']:
                    p = Point(label['centerPagePoints'])
                    if native.covers(p):
                        mapped = _map_geometry(p, reference)
                        lon, lat = ll(mapped.x, mapped.y)
                        labels.append({'text': label['text'], 'own': label in own,
                                       'pagePoint': list(p.coords)[0], 'lat': lat, 'lng': lon,
                                       'distanceToSourceEdgeM': mapped.distance(metric.boundary)})
                inner = native.buffer(-.5)
                admin_inside = {k: v.intersection(inner).length for k, v in lines.items() if k != 'red'}
                rows.append({'officialCode': code, 'id': case['appId'], 'officialName': case['officialName'],
                    'officialParent': case['officialParent'], 'sourcePdf': case['sourcePdf'], 'sourceUrl': case['sourceUrl'],
                    'sourceInventory': case['sourceInventory'], 'rawSourceGeometry': raw_ref, 'geometry': grid_ref,
                    'sourceMetricGeometry': metric_ref, 'nativePageGeometry': native_ref, 'originalRender': pin(picture),
                    'sourceMethod': method, 'nativeDrawingIndexes': native_matches, 'originalNativePointsMatchSavedInventory': True,
                    'registration': {'fitResidualM': reference['maxControlResidualMeters'], 'looMaxM': max(loo), 'matrix': matrix.tolist()},
                    'sourceAreaM2': metric.area, 'nativeInteriorAdminLengthsPagePoints': admin_inside,
                    'wholeNativeFaceInsidePage': box(*page.rect).covers(native), 'redInvalidCount': len(invalid.geoms),
                    'insideLabels': labels, 'sourceScopeAccepted': False, 'credit': 0,
                    'originalComparatorStatus': case.get('status'), 'originalHoldReasons': case.get('holdReasons', []),
                    'supportingAlreadyAcceptedNeighbor': code not in spec['exactTargets']})
                put(output / (code + '-source-facts.json'), rows[-1])
                print(json.dumps({'code': code, 'method': method, 'areaKm2': round(metric.area / 1e6, 3),
                                  'foreignLabels': [v['text'] for v in labels if not v['own']]}, ensure_ascii=False), flush=True)
        except Exception as exc:
            rows.append({'officialCode':case['officialCode'],'status':'HOLD_ORIGINAL_SOURCE_FACTS','error':str(exc),'sourcePdf':case['sourcePdf'],'credit':0})
            print(json.dumps(rows[-1],ensure_ascii=False),flush=True)
    put(output / 'native-source-review.json', {'status': 'SOURCE_FACTS_PREPARED_REQUIRES_VISUAL_ROLE_NEIGHBOR_REVIEW',
        'rows': rows, 'sourceActor': '/root', 'independentAgentReviewClaimed': False, 'credit': 0,
        'completedAtUtc': datetime.now(timezone.utc).isoformat()})


def put_geometry(path, geometry):
    with path.open('xb') as stream:
        stream.write(geometry.wkb)
    return pin(path)


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--manifest', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    review(read(a.manifest), a.output)
