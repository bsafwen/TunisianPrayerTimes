from pathlib import Path
import hashlib, json, struct, unicodedata
import numpy as np
import pypdf, pymupdf
from pyproj import CRS, Transformer
from shapely.geometry import Polygon, MultiPolygon, Point, shape, mapping
from shapely.ops import transform
W = Path(__file__).resolve().parent
T = W.parents[1]
R = Path('C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes')
A = R / 'android-app/app/src/main/assets'
PDF = W / 'mongi-slim-ettadhamen-isie2023.pdf'
RECEIPT = W / 'official-map-receipt.json'
FROZEN = W / 'frozen-current-facts.json'
PENDING = W / 'current-pending-review-facts.json'
TARGET = 'osm:relation:7115583'
INDEX = 4
META_SHA = 'ebddec69d0063d20f95e2d3d038966f55fc60f0db508b8fcb52bedbd86009e94'
BIN_SHA = '57106f9772ba142d325c3483fa57329328d1f4f349270fdc80c97f6283a1b494'
def sha(p): return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def pin(p): return {'file': str(p), 'sha256': sha(p), 'bytes': Path(p).stat().st_size}
def save(n, o): (W / n).write_text(json.dumps(o, ensure_ascii=False, indent=2, allow_nan=False) + chr(10), encoding='utf-8', newline=chr(10))
def norm(s): return unicodedata.normalize('NFKC', s)
frozen = json.loads(FROZEN.read_bytes())
case = next(c for c in frozen['cases'] if c['id'] == TARGET)
assert case['id'] == TARGET
assert sha(A / 'neighborhoods.json') == META_SHA
assert sha(A / 'neighborhoods.bin') == BIN_SHA
assert frozen['pins']['metadata']['sha256'] == META_SHA
assert frozen['pins']['binary']['sha256'] == BIN_SHA
meta = json.loads((A / 'neighborhoods.json').read_bytes())
blob = (A / 'neighborhoods.bin').read_bytes()
rows = {r['id']: r for r in meta['features']}
row = rows[TARGET]
assert row['offset'] == case['currentRaw']['offset']
assert row['length'] == case['currentRaw']['length']
assert hashlib.sha256(blob[row['offset']:row['offset'] + row['length']]).hexdigest() == case['currentPackedPayloadSha256']
areas = json.loads((T / 'work/geo/osm-areas.json').read_bytes())['areas']
source = {r['id']: r for r in areas}
src = source[TARGET]
assert src['tags'] == case['originalSource']['tags']
receipt = json.loads(RECEIPT.read_bytes())
assert Path(receipt['file']).name == PDF.name
assert sha(PDF) == receipt['sha256']
to_utm = Transformer.from_crs(4326, 32632, always_xy=True)
to_ll = Transformer.from_crs(32632, 4326, always_xy=True)
def projected(g): return transform(to_utm.transform, g)
def unpack(row):
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
            rings.append([(get() / meta['coordinateScale'], get() / meta['coordinateScale']) for _ in range(get())])
        polys.append(Polygon(rings[0], rings[1:]))
    assert offset == row['offset'] + row['length']
    return MultiPolygon(polys)
def compare(a, b):
    inter = a.intersection(b).area
    denom = a.area + b.area - inter
    return {'aAreaKm2': a.area / 1e6, 'bAreaKm2': b.area / 1e6, 'intersectionKm2': inter / 1e6, 'iou': inter / denom if denom else 0.0, 'aCoveredByB': inter / a.area if a.area else 0.0, 'bCoveredByA': inter / b.area if b.area else 0.0, 'aOutsideBKm2': a.difference(b).area / 1e6, 'bOutsideAKm2': b.difference(a).area / 1e6, 'hausdorffMeters': a.hausdorff_distance(b), 'centroidDistanceMeters': a.centroid.distance(b.centroid)}
pdf = pypdf.PdfReader(PDF)
doc = pymupdf.open(PDF)
page = doc[0]
assert len(doc) == 1
vp = pdf.pages[0]['/VP']
assert len(vp) == 1
vp = vp[0].get_object()
measure = vp['/Measure'].get_object()
bbox = np.asarray(vp['/BBox'], dtype=float)
gpts = np.asarray(measure['/GPTS'], dtype=float).reshape(-1, 2)
lpts = np.asarray(measure['/LPTS'], dtype=float).reshape(-1, 2)
wkt = str(measure['/GCS'].get_object()['/WKT'])
crs = CRS.from_wkt(wkt)
assert crs.to_epsg() == 32632
tr = Transformer.from_crs(crs.geodetic_crs, crs, always_xy=True)
pts = []
for x, y in lpts:
    point = pymupdf.Point(bbox[0] + x * (bbox[2] - bbox[0]), bbox[1] + y * (bbox[3] - bbox[1])) * page.transformation_matrix
    pts.append([point.x, point.y, 1])
pts = np.asarray(pts)
dest = np.asarray([tr.transform(lon, lat) for lat, lon in gpts])
affine, _, rank, _ = np.linalg.lstsq(pts, dest, rcond=None)
assert rank == 3
residual = np.linalg.norm(pts @ affine - dest, axis=1)
def to_map(x, y, z=None): return x * affine[0, 0] + y * affine[1, 0] + affine[2, 0], x * affine[0, 1] + y * affine[1, 1] + affine[2, 1]
d = page.get_drawings()[INDEX]
assert d['layer'] == 'circonscription_isie2023'
assert d['color'] == (1., 0., 0.)
items = d['items']
assert all(it[0] == 'l' for it in items)
verts = [tuple(items[0][1])] + [tuple(it[2]) for it in items]
assert all(tuple(it[1]) == verts[j] for j, it in enumerate(items))
assert verts[0] == verts[-1]
poly = Polygon(verts)
assert poly.is_valid and poly.area > 0
for ring in [poly.exterior] + list(poly.interiors):
    assert ring.is_ring and ring.is_closed and ring.is_simple
rect = pymupdf.Rect(min(bbox[0], bbox[2]), min(bbox[1], bbox[3]), max(bbox[0], bbox[2]), max(bbox[1], bbox[3])) * page.transformation_matrix
frame = Polygon([(rect.x0, rect.y0), (rect.x1, rect.y0), (rect.x1, rect.y1), (rect.x0, rect.y1)])
assert frame.covers(poly)
assert poly.boundary.distance(frame.boundary) > 0
active_clips = []
for item in page.get_drawings(extended=True):
    if item.get('layer') != 'circonscription_isie2023':
        continue
    if item['type'] == 'clip':
        active_clips = [c for c in active_clips if c['level'] < item['level']]
        assert len(item['items']) == 1 and item['items'][0][0] == 'qu'
        q = item['items'][0][1]
        clip = Polygon([tuple(q.ul),tuple(q.ur),tuple(q.lr),tuple(q.ll)])
        active_clips.append({'level':item['level'],'shape':clip})
    elif item.get('items') == d['items']:
        break
else:
    raise AssertionError('Target path not found in extended drawing order')
assert len(active_clips) == 2 and all(c['shape'].covers(poly) for c in active_clips)
clip_review = [{'level':c['level'],'outsideAreaPoints2':poly.difference(c['shape']).area,'minMarginPoints':poly.boundary.distance(c['shape'].boundary)} for c in active_clips]
mapped = transform(to_map, poly)
assert mapped.is_valid
official_ll = transform(to_ll.transform, mapped)
labels = []
for block in page.get_text('dict')['blocks']:
    if block['type'] != 0:
        continue
    spans = [s for l in block['lines'] for s in l['spans']]
    text = ' '.join(s['text'] for s in spans)
    bb = block['bbox']
    pt = Point((bb[0] + bb[2]) / 2, (bb[1] + bb[3]) / 2)
    if any(s['color'] in (16711680, 15073280) for s in spans):
        labels.append({'raw': text, 'normalized': norm(text), 'reversedNormalized': norm(text[::-1]), 'centerInsideTarget': poly.covers(pt), 'bbox': list(bb)})
osm_wgs = shape(src['geometry'])
osm = projected(osm_wgs)
assert osm.is_valid
packed_wgs = unpack(row)
packed = projected(packed_wgs)
assert packed.is_valid
anchor_ll = Point(case['currentRaw']['lng'], case['currentRaw']['lat'])
anchor = projected(anchor_ll)
rec = {'source': pin(PDF), 'receipt': pin(RECEIPT), 'targetId': TARGET, 'drawingIndex': INDEX, 'nativeVertices': len(verts), 'sourceLayer': d['layer'], 'nativeColor': list(d['color']), 'nativeContinuousExplicitlyClosed': True, 'nativeGeometryValid': True, 'fullyInsideViewport': True, 'minViewportMarginPagePoints': float(poly.boundary.distance(frame.boundary)), 'pageRect': [page.rect.x0, page.rect.y0, page.rect.x1, page.rect.y1], 'rotation': page.rotation, 'cropBox': [page.cropbox.x0, page.cropbox.y0, page.cropbox.x1, page.cropbox.y1], 'mediaBox': [page.mediabox.x0, page.mediabox.y0, page.mediabox.x1, page.mediabox.y1], 'viewportBBox': bbox.tolist(), 'localControlPoints': lpts.tolist(), 'controlPointsLatLon': gpts.tolist(), 'storedCrsWkt': wkt, 'epsg': crs.to_epsg(), 'affineMetersFromPagePoints': affine.tolist(), 'controlResidualsMeters': residual.tolist(), 'controlResidualMaxMeters': float(residual.max()), 'axisScaleMetersPerPagePoint': [float(np.linalg.norm(affine[i])) for i in (0, 1)], 'redLabels': labels, 'wgs84Bounds': list(official_ll.bounds), 'nativeVsOriginal': compare(mapped, osm), 'nativeVsPacked': compare(mapped, packed), 'originalVsPacked': compare(osm, packed), 'anchor': {'lng': case['currentRaw']['lng'], 'lat': case['currentRaw']['lat'], 'insideOfficial': mapped.covers(anchor), 'insideOriginal': osm.covers(anchor), 'insidePacked': packed.covers(anchor), 'distanceOfficialMeters': float(mapped.distance(anchor)), 'distanceOriginalMeters': float(osm.distance(anchor)), 'distancePackedMeters': float(packed.distance(anchor)), 'boundaryDistanceOfficialMeters': float(mapped.boundary.distance(anchor)), 'boundaryDistanceOriginalMeters': float(osm.boundary.distance(anchor)), 'boundaryDistancePackedMeters': float(packed.boundary.distance(anchor))}}
parent_matches = [r for r in source.values() if r.get('tags', {}).get('admin_level') == '5' and r.get('tags', {}).get('ref:tn:codegeo') == '1256']
assert len(parent_matches) == 1
parent = parent_matches[0]
assert parent['id'] == 'osm:relation:4156579'
assert parent['tags']['name:ar'] == case['currentRaw']['parentName']
parent_resolution = 'Unique original delegation kind and exact official parent code1256, ID4156579, and current Arabic parentName match; no name/containment fallback'
parent_cmp = {'resolution': parent_resolution, 'resolved': parent is not None, 'parent': None if parent is None else {'id': parent.get('id'), 'name': parent.get('name') or parent.get('tags', {}).get('name'), 'sourceId': 'osm', 'kind': 'delegation', 'tags': parent.get('tags')}}
if parent is not None:
    pg = projected(shape(parent['geometry']))
    parent_cmp.update({'officialVsParent': compare(mapped, pg), 'originalVsParent': compare(osm, pg), 'packedVsParent': compare(packed, pg)})
coverage_path = R / 'scripts/neighborhoods/coverage.json'
assert sha(coverage_path) == '1d3c4413fb2b41cf02c35cced9deb0e013461f6a417969e685d95ab5fec9a517'
coverage_pin = pin(coverage_path)
coverage = json.loads(coverage_path.read_bytes())
off_ll = official_ll
orig_ll = osm_wgs
packed_ll = packed_wgs
query_bbox = [min(off_ll.bounds[0], orig_ll.bounds[0], packed_ll.bounds[0]), min(off_ll.bounds[1], orig_ll.bounds[1], packed_ll.bounds[1]), max(off_ll.bounds[2], orig_ll.bounds[2], packed_ll.bounds[2]), max(off_ll.bounds[3], orig_ll.bounds[3], packed_ll.bounds[3])]
def bbox_overlap(a, b, eps=1e-9): return not (a[2] < b[0] - eps or a[0] > b[2] + eps or a[3] < b[1] - eps or a[1] > b[3] + eps)
candidates = []
for r in meta['features']:
    if r['id'] == TARGET:
        continue
    if r.get('kind') != 'sector':
        continue
    if r.get('hasBoundary') is False:
        continue
    bb = r.get('bbox')
    if bb and bbox_overlap(bb, query_bbox):
        candidates.append(r)
sector_intersections = []
for r in candidates:
    g = projected(unpack(r))
    assert g.is_valid
    if not (g.intersects(mapped) or g.intersects(osm) or g.intersects(packed)):
        continue
    sector_intersections.append({'id': r.get('id'), 'name': r.get('name'), 'sourceId': r.get('sourceId'), 'kind': r.get('kind'), 'offset': r.get('offset'), 'length': r.get('length'), 'bbox': r.get('bbox'), 'official': compare(mapped, g), 'original': compare(osm, g), 'packed': compare(packed, g)})
target_hits = [c for c in coverage['conflicts'] if TARGET in c['ids']]
for e in sector_intersections:
    hits = [c for c in target_hits if set(c['ids']) == {TARGET, e['id']}]
    e['coverageConflictListed'] = bool(hits)
    e['coverageConflictHits'] = hits
coverage_summary = {'coverageJson': coverage_pin, 'targetIdListedConflict': bool(target_hits), 'targetHits': target_hits, 'intersectingSectorIds': [e['id'] for e in sector_intersections], 'intersectingSectorIdsListedConflict': [e['id'] for e in sector_intersections if e['coverageConflictListed']], 'candidateCount': len(candidates), 'intersectingCount': len(sector_intersections), 'queryBboxWgs84': query_bbox}
pending_summary = None
if PENDING.exists():
    pending = json.loads(PENDING.read_bytes())
    matches = pending.get('matches', [])
    pending_summary = {'file': pin(PENDING), 'pendingCount': pending.get('pendingCount'), 'matchCount': len(matches), 'containsStale7111933': any(isinstance(m, list) and len(m) > 1 and isinstance(m[1], dict) and m[1].get('id') == 'osm:relation:7111933' for m in matches)}
curation_audit = {'stalePendingReview': {'id': 'osm:relation:7111933', 'pendingReviewSource': pending_summary, 'status': 'stale Teboulba-vs-Bekalta-north contradiction remains in provided pendingReview facts'}, 'identityAccepted': 'osm:relation:7111933 exact identity as Bekalta North / البقالطة الشمالية is already accepted by the frozen active-boundary source evidence; no further Teboulba research is required.', 'safeRemovalScope': 'Remove only the stale osm:relation:7111933 entry from catalog-curation.json pendingReview after confirming the accepted replacement metadata state; do not alter active boundary/geometry sources or any other pending entry.', 'noFurtherTeboulbaResearch': True}
facts = {'status': 'READ_ONLY_ONE_MAP_SCOPE_FACTS', 'producer': pin(Path(__file__)), 'targetId': TARGET, 'inputPins': [pin(FROZEN), pin(RECEIPT), pin(PDF), pin(A / 'neighborhoods.json'), pin(A / 'neighborhoods.bin'), pin(T / 'work/geo/osm-areas.json')], 'currentPins': {'metadata': frozen['pins']['metadata'], 'binary': frozen['pins']['binary']}, 'pdfRecord': rec, 'originalVsPacked': compare(osm, packed), 'parentComparison': parent_cmp, 'currentSectorIntersections': sector_intersections, 'coverageConflictSummary': coverage_summary, 'curationAudit': curation_audit, 'activeClipReview': clip_review, 'method': 'Read embedded /VP /Measure /GCS WKT and GPTS/LPTS; fit affine in stored projected CRS after page transform. Select only complete closed native red circonscription_isie2023 drawing index 4. Compare with original osm-areas.json geometry and exact packed neighborhoods.bin payload. No source generation, crop, snap, buffer, repair, union, production edits, tests or devices.', 'limitations': ['One official map only; other red drawings are outside the target scope and were not extracted.', 'Native PDF path is the sole official geometry source; no vertex was invented or repaired.', 'Original and packed OSM geometries are the same provider lineage, not independent confirmation.', 'Current sectors are bbox-prefiltered around the target official/original/packed bounds; only intersecting local polygons receive geometry comparisons.']}
diag = {'status': 'READ_ONLY_NATIVE_GEOMETRY_DIAGNOSTIC', 'targetId': TARGET, 'drawingIndex': INDEX, 'source': pin(PDF), 'receipt': pin(RECEIPT), 'viewportBBox': bbox.tolist(), 'localControlPoints': lpts.tolist(), 'controlPointsLatLon': gpts.tolist(), 'storedCrsWkt': wkt, 'epsg': crs.to_epsg(), 'affineMetersFromPagePoints': affine.tolist(), 'controlResidualsMeters': residual.tolist(), 'controlResidualMaxMeters': float(residual.max()), 'drawing': {'layer': d['layer'], 'color': list(d['color']), 'rect': [d['rect'].x0, d['rect'].y0, d['rect'].x1, d['rect'].y1], 'closePath': d.get('closePath'), 'itemTypes': [it[0] for it in items], 'itemsCount': len(items)}, 'nativeVerticesPagePoints': verts, 'nativeGeometryWgs84': mapping(official_ll), 'nativeGeometryUtm32n': mapping(mapped), 'redLabels': labels, 'wgs84Bounds': list(official_ll.bounds), 'assertions': {'onePage': True, 'oneViewport': True, 'epsg32632FromWkt': crs.to_epsg() == 32632, 'affineRank3': bool(rank == 3), 'straightLineItemsOnly': True, 'continuousClosedPath': True, 'polygonValid': poly.is_valid, 'allRingsClosedSimple': True, 'insideViewport': True, 'layerAndRedColor': True}}
save('geometry-facts.json', facts)
save('native-geometry-diagnostic.json', diag)
