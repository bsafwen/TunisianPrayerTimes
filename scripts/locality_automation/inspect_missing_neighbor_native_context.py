"""Inspect three newly captured neighboring sheets without selecting accepted faces."""
import argparse
import json
from pathlib import Path
import sys

from shapely.affinity import affine_transform
from shapely.geometry import Point
from shapely.ops import polygonize_full, unary_union

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts.locality_automation.audit_native_replay_layers import layer_lines
from scripts.locality_automation.compare_isie_neighbor_linework import inventory_case, affine_coefficients
from scripts.locality_automation.run_sealed_boundary_queue import checked, pin, read


def inspect(row, output):
    case = inventory_case(row)
    page = case['page']
    index = row['ownLabelIndex']
    label = page['labeledAreaLeads'][index]
    if label['text'] != row['literalNativeLabel'] or label['redText'] is not True:
        raise ValueError('Exact manually inspected native caption differs')
    anchor = Point(label['centerPagePoints'])
    lines = {key: layer_lines(page, key) for key in ('red', 'blue', 'black')}
    configurations = []
    for keys in (('red',), ('red', 'blue'), ('red', 'blue', 'black')):
        line = unary_union([lines[key] for key in keys])
        faces, cuts, dangles, invalid = polygonize_full(line)
        matches = [face for face in faces.geoms if face.covers(anchor)]
        record = {'layers': list(keys), 'faceCount': len(faces.geoms),
                  'ownLabelContainingFaces': len(matches), 'invalidRingCount': len(invalid.geoms),
                  'cutCount': len(cuts.geoms), 'dangleCount': len(dangles.geoms),
                  'sourceScopeAccepted': False, 'credit': 0}
        if len(matches) == 1:
            face = matches[0]
            ground = affine_transform(face, affine_coefficients(case['matrix']))
            name = row['officialCode'] + '-' + '+'.join(keys)
            page_path = output / (name + '-page-hypothesis.wkb')
            ground_path = output / (name + '-epsg32632-hypothesis.wkb')
            with page_path.open('xb') as stream:
                stream.write(face.wkb)
            with ground_path.open('xb') as stream:
                stream.write(ground.wkb)
            record.update(pageWkb=pin(page_path), registeredWkb=pin(ground_path),
                          areaSquareMeters=ground.area, valid=face.is_valid and ground.is_valid,
                          boundaryUncoveredPoints=face.boundary.difference(line.buffer(.1)).length,
                          internalDangleLengthPoints=dangles.intersection(face.buffer(-.05)).length,
                          interiorAdminLineLengthsPoints={key: lines[key].intersection(face.buffer(-.5)).length
                                                         for key in ('blue', 'black')},
                          redCaptionsInside=[{'index': i, 'text': caption['text']}
                              for i, caption in enumerate(page['labeledAreaLeads'])
                              if face.covers(Point(caption['centerPagePoints']))])
        configurations.append(record)
    return {'officialCode': row['officialCode'], 'officialName': row['officialName'],
            'officialParent': row['officialParent'], 'sourcePdf': row['sourcePdf'],
            'sourceInventory': row['sourceInventory'], 'ownLabelIndex': index,
            'registration': case['registration'], 'configurations': configurations,
            'status': 'NATIVE_LAYER_FACE_HYPOTHESES_REQUIRE_SOURCE_REVIEW',
            'sourceScopeAccepted': False, 'independentQaPassed': False, 'credit': 0,
            'qualification': 'No automatic face ownership or administrative extent is asserted. '
                             'Raw original layers and every invalid ring, cut, dangle and caption are retained.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    manifest = read(args.manifest)
    rows = manifest['rows']
    if len(rows) != 3 or {row['officialCode'] for row in rows} != {'235653', '245858', '425356'}:
        raise ValueError('Only the finite missing-neighbor context pool is allowed')
    for row in rows:
        checked(row['sourcePdf'])
        checked(row['sourceInventory'])
    args.output.mkdir(exist_ok=False)
    reports = [inspect(row, args.output) for row in rows]
    for row in rows:
        checked(row['sourcePdf'])
        checked(row['sourceInventory'])
    result = {'status': 'MISSING_NEIGHBOR_NATIVE_CONTEXT_NO_ACCEPTANCE', 'manifest': pin(args.manifest),
              'rows': reports, 'assetsChanged': False, 'credit': 0}
    (args.output / 'report.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'codes': [row['officialCode'] for row in reports], 'credit': 0}))


if __name__ == '__main__':
    main()
