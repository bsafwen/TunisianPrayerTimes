"""Read only three official GeoPDFs and two existing sector polygons; work outputs only."""
from pathlib import Path
import hashlib, json, struct, unicodedata
import numpy as np
import pypdf, pymupdf
from pyproj import CRS, Transformer
from shapely.geometry import Polygon, MultiPolygon, Point, shape, mapping
from shapely.ops import transform, unary_union

W=Path(__file__).resolve().parent; T=W.parents[1]
R=Path('C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes')
A=R/'android-app/app/src/main/assets'
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def pin(p): return {'file':str(p),'sha256':sha(p)}
def save(n,o): (W/n).write_text(json.dumps(o,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
def norm(s): return unicodedata.normalize('NFKC',s)
meta=json.loads((A/'neighborhoods.json').read_bytes());blob=(A/'neighborhoods.bin').read_bytes()
assert sha(A/'neighborhoods.json')=='d396925c0a7adb85010fb1a2b3f839ffcea939bc12b59a0da6c04d23c57d79a4'
assert sha(A/'neighborhoods.bin')=='bab9876668eb53f9fd27992242575b031acaa74c3f4e36cca7842289164fe444'
rows={r['id']:r for r in meta['features']}
areas=json.loads((T/'work/geo/osm-areas.json').read_bytes())['areas'];source={r['id']:r for r in areas}
to_utm=Transformer.from_crs(4326,32632,always_xy=True);to_ll=Transformer.from_crs(32632,4326,always_xy=True)
def projected(g): return transform(to_utm.transform,g)
def unpack(row):
 offset=row['offset']
 def get():
  nonlocal offset
  n=struct.unpack_from('>i',blob,offset)[0];offset+=4;return n
 polys=[]
 for _ in range(get()):
  rings=[]
  for _ in range(get()): rings.append([(get()/meta['coordinateScale'],get()/meta['coordinateScale']) for _ in range(get())])
  polys.append(Polygon(rings[0],rings[1:]))
 assert offset==row['offset']+row['length']
 return MultiPolygon(polys)
def compare(a,b):
 inter=a.intersection(b).area;union=a.union(b).area
 return {'aAreaKm2':a.area/1e6,'bAreaKm2':b.area/1e6,'intersectionKm2':inter/1e6,'iou':inter/union,'aCoveredByB':inter/a.area,'bCoveredByA':inter/b.area,'aOutsideBKm2':a.difference(b).area/1e6,'bOutsideAKm2':b.difference(a).area/1e6,'hausdorffMeters':a.hausdorff_distance(b),'centroidDistanceMeters':a.centroid.distance(b.centroid)}
receipts=json.loads((W/'source-receipts.json').read_bytes())
targets=[('ettadhamen4.pdf',4,'osm:relation:7115584'),('hammam-maarouf-riadh1.pdf',292,'osm:relation:7113315'),('hammam-maarouf-riadh2.pdf',383,'osm:relation:7113315')]
records=[];native={};geometries={}
for filename,index,ident in targets:
 p=W/filename;receipt=next(x for x in receipts if Path(x['file']).name==filename);assert sha(p)==receipt['sha256']
 pdf=pypdf.PdfReader(p);doc=pymupdf.open(p);page=doc[0];assert len(doc)==1
 vp=pdf.pages[0]['/VP'];assert len(vp)==1
 vp=vp[0].get_object();measure=vp['/Measure'].get_object();bbox=np.asarray(vp['/BBox'],dtype=float)
 gpts=np.asarray(measure['/GPTS'],dtype=float).reshape(-1,2);lpts=np.asarray(measure['/LPTS'],dtype=float).reshape(-1,2)
 wkt=str(measure['/GCS'].get_object()['/WKT']);crs=CRS.from_wkt(wkt);assert crs.to_epsg()==32632
 tr=Transformer.from_crs(crs.geodetic_crs,crs,always_xy=True)
 pts=[]
 for x,y in lpts:
  point=pymupdf.Point(bbox[0]+x*(bbox[2]-bbox[0]),bbox[1]+y*(bbox[3]-bbox[1]))*page.transformation_matrix
  pts.append([point.x,point.y,1])
 pts=np.asarray(pts);dest=np.asarray([tr.transform(lon,lat) for lat,lon in gpts]);affine,_,rank,_=np.linalg.lstsq(pts,dest,rcond=None)
 residual=np.linalg.norm(pts@affine-dest,axis=1);assert rank==3
 def to_map(x,y,z=None):return x*affine[0,0]+y*affine[1,0]+affine[2,0],x*affine[0,1]+y*affine[1,1]+affine[2,1]
 d=page.get_drawings()[index];assert d['layer']=='circonscription_isie2023' and d['color']==(1.,0.,0.)
 items=d['items'];assert all(it[0]=='l' for it in items)
 verts=[tuple(items[0][1])]+[tuple(it[2]) for it in items]
 assert all(tuple(it[1])==verts[j] for j,it in enumerate(items)) and verts[0]==verts[-1]
 poly=Polygon(verts);assert poly.is_valid and poly.area>0
 rect=pymupdf.Rect(min(bbox[0],bbox[2]),min(bbox[1],bbox[3]),max(bbox[0],bbox[2]),max(bbox[1],bbox[3]))*page.transformation_matrix
 frame=Polygon([(rect.x0,rect.y0),(rect.x1,rect.y0),(rect.x1,rect.y1),(rect.x0,rect.y1)])
 assert frame.covers(poly) and poly.boundary.distance(frame.boundary)>0
 mapped=transform(to_map,poly);assert mapped.is_valid
 native[filename]=mapped;geometries[filename]=mapping(transform(to_ll.transform,mapped))
 labels=[]
 for block in page.get_text('dict')['blocks']:
  if block['type']!=0:continue
  spans=[s for l in block['lines'] for s in l['spans']];text=' '.join(s['text'] for s in spans);bb=block['bbox'];pt=Point((bb[0]+bb[2])/2,(bb[1]+bb[3])/2)
  if poly.covers(pt) and any(s['color']==16711680 for s in spans):labels.append({'raw':text,'normalized':norm(text),'reversedNormalized':norm(text[::-1])})
 osm=projected(shape(source[ident]['geometry']));packed=projected(unpack(rows[ident]));anchor=projected(Point(rows[ident]['lng'],rows[ident]['lat']))
 rec={'source':receipt,'targetId':ident,'drawingIndex':index,'nativeVertices':len(verts),'sourceLayer':d['layer'],'nativeContinuousExplicitlyClosed':True,'nativeGeometryValid':True,'fullyInsideViewport':True,'minViewportMarginPagePoints':poly.boundary.distance(frame.boundary),'pageRect':list(page.rect),'rotation':page.rotation,'cropBox':list(page.cropbox),'mediaBox':list(page.mediabox),'viewportBBox':bbox.tolist(),'localControlPoints':lpts.tolist(),'controlPointsLatLon':gpts.tolist(),'storedCrsWkt':wkt,'epsg':crs.to_epsg(),'affineMetersFromPagePoints':affine.tolist(),'controlResidualsMeters':residual.tolist(),'axisScaleMetersPerPagePoint':[float(np.linalg.norm(affine[i])) for i in (0,1)],'insideRedLabels':labels,'wgs84Bounds':list(shape(geometries[filename]).bounds),'nativeVsOriginal':compare(mapped,osm),'nativeVsPacked':compare(mapped,packed),'containsCurrentAnchor':mapped.covers(anchor),'distanceFromAnchorMeters':mapped.distance(anchor),'anchorDistanceToBoundaryMeters':mapped.boundary.distance(anchor)}
 records.append(rec)
comparisons=[]
for ident in ['osm:relation:7115584','osm:relation:7113315']:
 selected=[native[f] for f,_,i in targets if i==ident];merged=unary_union(selected)
 osm=projected(shape(source[ident]['geometry']));packed=projected(unpack(rows[ident]));anchor=projected(Point(rows[ident]['lng'],rows[ident]['lat']))
 obj={'id':ident,'sourceTags':source[ident]['tags'],'currentRow':rows[ident],'originalVsPacked':compare(osm,packed),'officialScopeVsOriginal':compare(merged,osm),'officialScopeVsPacked':compare(merged,packed),'officialUnionType':merged.geom_type,'officialUnionValid':merged.is_valid,'officialUnionContainsCurrentAnchor':merged.covers(anchor),'parts':len(selected),'unionOperation':'Shapely native union of untouched independently georeferenced polygons; no snap, clip, buffer, repair, cutting, or invented vertices'}
 if len(selected)==2:obj['splitSeam']={'distanceMeters':selected[0].distance(selected[1]),'intersectionAreaM2':selected[0].intersection(selected[1]).area,'boundaryIntersectionLengthMeters':selected[0].boundary.intersection(selected[1].boundary).length,'relation':selected[0].relate(selected[1])}
 comparisons.append(obj);geometries[ident+'-original']=source[ident]['geometry'];geometries[ident+'-packed']=mapping(unpack(rows[ident]))
book=T/'work/official-codes/ins-2012.pdf';assert sha(book)=='d11dc600430ffc31fb2bd50f903c2037b9337b52d6bc68b81778c3da9319b580'
historical=[]
for page_no,code in [(39,'125657'),(77,'315256')]:
 text=pymupdf.open(book)[page_no-1].get_text();lines=text.splitlines();i=next(j for j,s in enumerate(lines) if code in ''.join(s.split()))
 historical.append({'source':pin(book),'pdfPageOneBased':page_no,'code':code,'nearbyExtractedText':lines[max(0,i-8):i+9]})
facts={'status':'READ_ONLY_OFFICIAL_SCOPE_ASSESSMENT','producer':pin(Path(__file__)),'inputPins':[pin(A/'neighborhoods.json'),pin(A/'neighborhoods.bin'),pin(T/'work/geo/osm-areas.json')],'historicalCodeEvidence':historical,'pdfRecords':records,'sectorComparisons':comparisons,'geometryMethod':'Read embedded /VP /Measure /GCS WKT and GPTS/LPTS; fit affine in stored projected CRS after page transform. Select complete closed native red circonscription_isie2023 paths, never infer or repair a boundary. EPSG is parsed from WKT, not guessed. Map frames, title, legend and labels visually reviewed separately.','limitations':['INS code continuity establishes name identity but not every boundary vertex.','ISIE maps describe 2023 local election districts; district/imada legend requires explicit scope interpretation.','Independent native georeferencing can produce seam slivers; no snapping is applied.','Original and packed OSM geometries are the same provider lineage, not independent confirmation.']}
save('native-geometries.json',geometries);save('geometry-facts.json',facts)
print(json.dumps({'records':[{'file':Path(x['source']['file']).name,'epsg':x['epsg'],'residualMax':max(x['controlResidualsMeters']),'insideRedLabels':x['insideRedLabels'],'nativeVsOriginal':x['nativeVsOriginal'],'anchorInside':x['containsCurrentAnchor']} for x in records],'comparisons':comparisons},ensure_ascii=False))
