import hashlib, json, struct
from pathlib import Path
import numpy as np
import pymupdf, pypdf
from pyproj import CRS, Transformer
from shapely.geometry import Polygon, MultiPolygon, Point, shape, mapping
from shapely.ops import transform
from shapely.validation import explain_validity
T = Path('C:/Users/barou/Documents/Codex/2026-09-06/the-android-app-app-currently-allows')
B = T / 'work/six-spelling-followup-20260920'
OUT = B / 'independent-review' / 'sedouikech'
NATIVE = B / 'native-review'
PDF = B / 'primary-pdfs' / 'osm_relation_7133851.pdf'
FACTS = B / 'facts.json'
SEL = B / 'selected-geometries.json'
META = B / 'frozen' / 'metadata.json'
RECEIPTS = B / 'primary-pdfs' / 'receipts.json'
BIN = NATIVE / 'neighborhoods.bin'
VALIDATION = NATIVE / 'source-validation.json'
TARGET = 'osm:relation:7133851'
A_IDX, B_IDX = 514, 515
A_SEQ, B_SEQ = 522, 523
A_START = (472.5794372558594, 553.3898315429688)
A_END = (508.57177734375, 198.02499389648438)
def read(p): return json.loads(p.read_text(encoding='utf8'))
def pin(p):
    b = p.read_bytes()
    return {'file': str(p), 'sha256': hashlib.sha256(b).hexdigest(), 'bytes': len(b)}
def save(n, o):
    OUT.mkdir(parents=True, exist_ok=True)
    p = OUT / n
    if p.exists(): raise SystemExit('refusing to overwrite ' + str(p))
    with p.open('x', encoding='utf8') as f: f.write(json.dumps(o, ensure_ascii=False, indent=2) + chr(10))
def style(d):
    return {k:d.get(k) for k in ('type','layer','color','fill','width','dashes','lineCap','lineJoin','closePath','even_odd','stroke_opacity','fill_opacity')}
def line_verts(d):
    items = d.get('items') or []
    if not items or not all(it[0] == 'l' for it in items):
        return None, 'NOT_ALL_LINE_ITEMS'
    v = [tuple(items[0][1])] + [tuple(it[2]) for it in items]
    for i, it in enumerate(items):
        if tuple(it[1]) != v[i]:
            return None, 'LINE_DISCONTINUITY'
    return v, None
def active_clips(extended, seqno, poly):
    active = {}
    found = False
    facts = []
    error = None
    for ed in extended:
        level = ed['level']
        active = {k: v for k, v in active.items() if k < level}
        if ed['type'] == 'clip':
            active[level] = ed
        elif ed.get('seqno') == seqno:
            found = True
            if not active:
                error = 'NO_ACTIVE_CLIPS'
                break
            for k, clip in active.items():
                if not (len(clip['items']) == 1 and clip['items'][0][0] == 'qu'):
                    error = 'ACTIVE_CLIP_NOT_QUAD'
                    break
                q = clip['items'][0][1]
                cp = Polygon([tuple(q.ul), tuple(q.ur), tuple(q.lr), tuple(q.ll)])
                if not cp.covers(poly):
                    error = 'ACTIVE_CLIP_DOES_NOT_COVER_PATH'
                    break
                facts.append({'level': k, 'clipVertices': list(cp.exterior.coords), 'coversWholeTargetPath': True})
            break
    if not found:
        error = error or 'NO_MATCHING_SEQUENCE'
    if not facts:
        error = error or 'NO_ACTIVE_CLIPS'
    return facts, error
def unpack(blob, row, scale):
    offset = row['offset']
    def get():
        nonlocal offset
        n = struct.unpack_from('>i', blob, offset)[0]
        offset += 4
        return n
    polys = []
    for _ in range(get()):
        rings = []
        for _ in range(get()):
            rings.append([(get() / scale, get() / scale) for _ in range(get())])
        polys.append(Polygon(rings[0], rings[1:]))
    if offset != row['offset'] + row['length']:
        raise SystemExit('packed row length mismatch')
    return MultiPolygon(polys)
def compare(a, b):
    if not a.is_valid or not b.is_valid:
        return {'status': 'WITHHELD_INVALID_RING', 'aValidity': explain_validity(a), 'bValidity': explain_validity(b)}
    inter = a.intersection(b).area
    return {'officialAreaKm2': a.area / 1e6, 'comparedAreaKm2': b.area / 1e6, 'intersectionKm2': inter / 1e6, 'iou': inter / a.union(b).area, 'officialCoveredByCompared': inter / a.area, 'comparedCoveredByOfficial': inter / b.area, 'officialOutsideComparedKm2': a.difference(b).area / 1e6, 'comparedOutsideOfficialKm2': b.difference(a).area / 1e6, 'hausdorffMeters': a.hausdorff_distance(b), 'centroidDistanceMeters': a.centroid.distance(b.centroid)}
def pointfacts(poly, point):
    if poly.is_valid:
        return {'covered': poly.covers(point), 'distanceToPolygonMeters': poly.distance(point), 'distanceToBoundaryMeters': poly.boundary.distance(point)}
    return {'covered': None, 'nativeRingValid': False, 'distanceToBoundaryMeters': poly.boundary.distance(point), 'qualification': 'unrepaired invalid ring; ordinary containment withheld'}
TO_UTM = Transformer.from_crs(4326, 32632, always_xy=True)
TO_LL = Transformer.from_crs(32632, 4326, always_xy=True)
def utm(g): return transform(TO_UTM.transform, g)
inputs = [FACTS, SEL, META, RECEIPTS, BIN, PDF, VALIDATION]
before = {str(p): pin(p) for p in inputs}
validation=read(VALIDATION)
for p in (FACTS,SEL,META,RECEIPTS,PDF):
    assert before[str(p)] == validation['start'][str(p)], 'Frozen input differs from prior native review'
assert before[str(BIN)] == validation['copiedLiveBin'], 'Frozen binary differs from prior native review'
receipts = read(RECEIPTS)
row = None
for r in receipts:
    if r.get('id') == TARGET:
        row = r
        break
if row is None: raise SystemExit('target receipt missing')
if row.get('file'):
    rp = Path(row['file'])
    if rp.is_absolute() and rp.resolve() != PDF.resolve():
        raise SystemExit('receipt path differs from frozen PDF path')
if pin(PDF)['sha256'] != row.get('sha256') or pin(PDF)['bytes'] != row.get('bytes'):
    raise SystemExit('PDF byte pin mismatch')
facts = read(FACTS)
item = None
for it in facts.get('items', []):
    if it.get('id') == TARGET:
        item = it
        break
if item is None: raise SystemExit('target missing from facts')
meta = read(META)
assert before[str(META)]['sha256'] == facts['inputPins']['metadata']['sha256']
scale = meta['coordinateScale']
sel = read(SEL)
orig_geom = shape(sel['geometries'][TARGET]['geometry'])
current_row = item['currentRawRow']
assert next(x for x in meta['features'] if x['id']==TARGET) == current_row
packed_geom = unpack(BIN.read_bytes(), current_row, scale)
anchor = Point(current_row['lng'], current_row['lat'])
reader = pypdf.PdfReader(str(PDF))
if len(reader.pages) != 1: raise SystemExit('PDF page count not one')
page = pymupdf.open(str(PDF))[0]
vp = reader.pages[0]['/VP']
if len(vp) != 1: raise SystemExit('viewport count not one')
vp = vp[0].get_object()
measure = vp['/Measure'].get_object()
bbox = np.asarray(vp['/BBox'], dtype=float)
gpts = np.asarray(measure['/GPTS'], dtype=float).reshape(-1, 2)
lpts = np.asarray(measure['/LPTS'], dtype=float).reshape(-1, 2)
wkt = str(measure['/GCS'].get_object()['/WKT'])
crs = CRS.from_wkt(wkt)
if crs.to_epsg() != 32632: raise SystemExit('EPSG not 32632')
tr = Transformer.from_crs(crs.geodetic_crs, crs, always_xy=True)
pts = []
for x, y in lpts:
    pt = pymupdf.Point(bbox[0] + x * (bbox[2] - bbox[0]), bbox[1] + y * (bbox[3] - bbox[1])) * page.transformation_matrix
    pts.append([pt.x, pt.y, 1])
pts = np.asarray(pts)
dest = np.asarray([tr.transform(lon, lat) for lat, lon in gpts])
affine, _, rank, _ = np.linalg.lstsq(pts, dest, rcond=None)
if rank != 3: raise SystemExit('affine rank not three')
residual = np.linalg.norm(pts @ affine - dest, axis=1)
def to_map(x, y):
    return (x * affine[0, 0] + y * affine[1, 0] + affine[2, 0], x * affine[0, 1] + y * affine[1, 1] + affine[2, 1])
rect = pymupdf.Rect(min(bbox[0], bbox[2]), min(bbox[1], bbox[3]), max(bbox[0], bbox[2]), max(bbox[1], bbox[3])) * page.transformation_matrix
frame = Polygon([(rect.x0, rect.y0), (rect.x1, rect.y0), (rect.x1, rect.y1), (rect.x0, rect.y1)])
drawings = page.get_drawings()
extended = page.get_drawings(extended=True)
if A_IDX >= len(drawings) or B_IDX >= len(drawings): raise SystemExit('target drawing missing')
dA, dB = drawings[A_IDX], drawings[B_IDX]
checks = []
checks.append({'name': 'drawingA_layer_color', 'ok': dA.get('layer') == 'circonscription_isie2023' and tuple(dA.get('color') or ()) == (1.0, 0.0, 0.0)})
checks.append({'name': 'drawingB_layer_color', 'ok': dB.get('layer') == 'circonscription_isie2023' and tuple(dB.get('color') or ()) == (1.0, 0.0, 0.0)})
checks.append({'name': 'drawingA_seq', 'ok': dA.get('seqno') == A_SEQ})
checks.append({'name': 'drawingB_seq', 'ok': dB.get('seqno') == B_SEQ})
vA, errA = line_verts(dA)
vB, errB = line_verts(dB)
checks.append({'name': 'drawingA_continuous', 'ok': errA is None, 'error': errA})
checks.append({'name': 'drawingB_continuous', 'ok': errB is None, 'error': errB})
checks.append({'name': 'identical_style', 'ok': style(dA) == style(dB)})
checks.append({'name': 'both_source_strokes_explicitly_open', 'ok': dA.get('closePath') is False and dB.get('closePath') is False})
checks.append({'name': 'exact_component_line_counts', 'ok': len(dA['items'])==1023 and len(dB['items'])==309})
if vA is not None and vB is not None:
    checks.append({'name': 'exact_A_start', 'ok': vA[0] == A_START, 'actual': vA[0]})
    checks.append({'name': 'exact_A_end', 'ok': vA[-1] == A_END, 'actual': vA[-1]})
    checks.append({'name': 'exact_shared_B_start', 'ok': vB[0] == vA[-1], 'actualBStart': vB[0], 'expected': vA[-1]})
    checks.append({'name': 'exact_return_B_end', 'ok': vB[-1] == vA[0], 'actualBEnd': vB[-1], 'expected': vA[0]})
else:
    checks.append({'name': 'exact_endpoints', 'ok': False, 'error': 'component vertices unavailable'})
ok_pre = all(c['ok'] for c in checks)
out = {'id': TARGET, 'pdfPin': pin(PDF), 'receiptPin': row, 'inputPinsBefore': before, 'checks': checks, 'components': [], 'combined': None, 'validity': None, 'comparisons': {}, 'sourceGeographyLimits': [], 'existingFactDefects': []}
if not ok_pre:
    out['status'] = 'WITHHELD_COMPONENT_CHECKS_FAILED'
    save('evidence.json', out)
    raise SystemExit('component checks failed')
combined = vA + vB[1:]
poly = Polygon(combined)
checks.append({'name': 'positive_area', 'ok': poly.area > 0, 'area': poly.area})
viewport_ok = frame.covers(poly) and poly.boundary.distance(frame.boundary) > 0
clipsA, errClipA = active_clips(extended, A_SEQ, poly)
clipsB, errClipB = active_clips(extended, B_SEQ, poly)
checks.append({'name': 'viewport_contains_combined', 'ok': viewport_ok, 'margin': poly.boundary.distance(frame.boundary)})
checks.append({'name': 'active_clips_A', 'ok': errClipA is None, 'error': errClipA, 'facts': clipsA})
checks.append({'name': 'active_clips_B', 'ok': errClipB is None, 'error': errClipB, 'facts': clipsB})
checks.append({'name': 'identical_active_clips', 'ok': clipsA==clipsB and errClipA is None and errClipB is None})
ok = all(c['ok'] for c in checks)
out['checks'] = checks
out['components'] = [
    {'drawingIndex': A_IDX, 'seqno': A_SEQ, 'layer': dA.get('layer'), 'color': dA.get('color'), 'style': style(dA), 'firstStart': vA[0], 'lastEnd': vA[-1], 'lineItemCount': len(dA['items']), 'vertexCount': len(vA), 'continuousLineByLine': errA is None},
    {'drawingIndex': B_IDX, 'seqno': B_SEQ, 'layer': dB.get('layer'), 'color': dB.get('color'), 'style': style(dB), 'firstStart': vB[0], 'lastEnd': vB[-1], 'lineItemCount': len(dB['items']), 'vertexCount': len(vB), 'continuousLineByLine': errB is None}
]
out['components'][0]['exactNativeVertices']=vA
out['components'][1]['exactNativeVertices']=vB
out['georeference']={'epsg':crs.to_epsg(),'storedCrsWkt':wkt,'viewportBBox':bbox.tolist(),'localControlPoints':lpts.tolist(),'controlPointsLatLon':gpts.tolist(),'affineMetersFromPagePoints':affine.tolist(),'controlResidualsMeters':residual.tolist(),'pageRect':list(page.rect),'cropBox':list(page.cropbox),'mediaBox':list(page.mediabox),'rotation':page.rotation,'qualification':'Affine fit residual is not surveyed geographic accuracy.'}
out['combined'] = {'fromDrawingIndices': [A_IDX, B_IDX], 'sharedEndpointA': vA[0], 'sharedEndpointB': vA[-1], 'concatenationRule': 'vA + vB[1:]', 'inventedSegment': False, 'vertexCount': len(combined), 'closedByExistingSharedEndpoint': combined[0] == combined[-1], 'areaPagePoints2': poly.area}
out['validity'] = {'isValid': poly.is_valid, 'explain': explain_validity(poly), 'repaired': False}
mapped = transform(to_map, poly)
wgs84 = transform(TO_LL.transform, mapped)
out['nativeGeometries']={'page':mapping(poly),'utm':mapping(mapped),'wgs84':mapping(wgs84)}
out['validity']['utmValidity']=explain_validity(mapped)
out['validity']['wgs84Validity']=explain_validity(wgs84)
if ok and poly.is_valid and mapped.is_valid and wgs84.is_valid:
    out['comparisons']['officialVsOriginal'] = compare(mapped, utm(orig_geom))
    out['comparisons']['officialVsPacked'] = compare(mapped, utm(packed_geom))
    out['comparisons']['currentAnchor'] = pointfacts(mapped, utm(anchor))
    out['validity']['utmValidity'] = explain_validity(mapped)
    out['wgs84Bounds'] = list(shape(mapping(transform(TO_LL.transform, mapped))).bounds)
else:
    out['comparisons']['officialVsOriginal'] = {'status': 'WITHHELD_INVALID_RING', 'explain': explain_validity(poly), 'repaired': False}
    out['comparisons']['officialVsPacked'] = {'status': 'WITHHELD_INVALID_RING', 'explain': explain_validity(poly), 'repaired': False}
    out['comparisons']['currentAnchor'] = pointfacts(mapped, utm(anchor)) if ok else {'covered':None,'qualification':'Source path checks failed.'}
out['sourceGeographyLimits'] = [
    'Only frozen 2023 circonscription PDF and frozen metadata/bin/original source are used; no live repo, no network, no other PDF scan.',
    'Page-to-UTM affine residual max meters: ' + str(float(residual.max())),
    'PDF map is an electoral circonscription rendering; it is not a cadastral survey and may differ from OSM boundary generalization.'
]
out['existingStrictReviewScopeQualification'] = [
    'The original strict single-drawing review correctly withheld drawing 514(seq522) and 515(seq523), because neither is individually closed. This separately recorded two-component check does not alter that original review.',
    'facts pdfIndexMatches gives title/link but no PDF byte hash; receipt pin is required and checked here.'
]
out['status'] = 'EXACT_TWO_COMPONENT_OUTLINE_VALID' if ok and poly.is_valid and mapped.is_valid and wgs84.is_valid else 'WITHHELD_INVALID_NATIVE_RING' if ok else 'WITHHELD_CHECKS_FAILED'
after = {str(p): pin(p) for p in inputs}
out['inputPinsAfter'] = after
out['inputsUnchanged'] = before == after
save('evidence.json', out)
if not out['inputsUnchanged']:
    raise SystemExit('input changed during processing')
