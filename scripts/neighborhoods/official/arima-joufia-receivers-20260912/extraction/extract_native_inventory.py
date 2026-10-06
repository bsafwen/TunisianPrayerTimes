from pathlib import Path
import hashlib,json,re,unicodedata
import numpy as np
import pymupdf
from pyproj import CRS,Transformer
from shapely.geometry import Polygon,Point,box,mapping
from shapely.ops import transform
from shapely.validation import explain_validity

D=Path(__file__).resolve().parent
def read(p):return json.loads(Path(p).read_bytes())
def pin(p):
 p=Path(p).resolve();return {'file':p.as_posix(),'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'bytes':p.stat().st_size}
def norm(s):return ''.join(unicodedata.normalize('NFKC',s).replace('ی','ي').replace('ک','ك').split())
manifest=read(D/'retrieval-manifest.json');results=[]
assert pin(D/'retrieval-manifest.json')['sha256']=='dd7adf0cbb851dd5f4d7557fe4ce6420fd770d9605857d38ec12a489dcb06868'
forward=Transformer.from_crs(4326,32632,always_xy=True);inverse=Transformer.from_crs(32632,4326,always_xy=True)
for source in manifest['records']:
 assert source['success'] and pin(source['response']['file'])==source['response']
 pdf=pymupdf.open(source['response']['file']);assert len(pdf)==1;page=pdf[0]
 render=D/(source['key']+'-whole-page.png');assert not render.exists()
 page.get_pixmap(matrix=pymupdf.Matrix(1.65,1.65)).save(str(render))
 obj=pdf.xref_object(page.xref);vp=[float(x) for x in re.search(r'/BBox\s*\[([^]]+)\]',obj).group(1).split()]
 mx=int(re.search(r'/Measure\s+(\d+)\s+0\s+R',obj).group(1));measure=pdf.xref_object(mx)
 arr=lambda k:[float(x) for x in re.search('/'+k+r'\s*\[([^]]+)\]',measure).group(1).split()]
 lpts=np.array(arr('LPTS')).reshape((-1,2));gpts=np.array(arr('GPTS')).reshape((-1,2))
 gx=int(re.search(r'/GCS\s+(\d+)\s+0\s+R',measure).group(1));wkt=pdf.xref_get_key(gx,'WKT')[1]
 assert CRS.from_wkt(wkt).to_epsg()==32632
 xy=[]
 for lx,ly in lpts:
  p=pymupdf.Point(vp[0]+lx*(vp[2]-vp[0]),vp[1]+ly*(vp[3]-vp[1]))*page.transformation_matrix
  xy.append([p.x,p.y,1.0])
 xy=np.array(xy);utm=np.array([forward.transform(lon,lat) for lat,lon in gpts])
 affine=np.linalg.lstsq(xy,utm,rcond=None)[0];residual=np.sqrt(((xy@affine-utm)**2).sum(axis=1))
 viewport=box(min(xy[:,0]),min(xy[:,1]),max(xy[:,0]),max(xy[:,1]))
 labels=[]
 for b in page.get_text('dict')['blocks']:
  for line in b.get('lines',[]):
   spans=line['spans'];red=[x for x in spans if x['color']==15073280]
   if not red:continue
   text=''.join(x['text'] for x in red);bounds=[min(x['bbox'][0] for x in red),min(x['bbox'][1] for x in red),max(x['bbox'][2] for x in red),max(x['bbox'][3] for x in red)]
   labels.append({'text':text,'normalizedForward':norm(text),'normalizedReverse':norm(text[::-1]),'point':[(bounds[0]+bounds[2])/2,(bounds[1]+bounds[3])/2],'bounds':bounds,'isTargetName':norm(source['officialName']) in (norm(text),norm(text[::-1]))})
 extended=page.get_drawings(extended=True);clip_by_seq={};clips={}
 for entry in extended:
  level=entry['level']
  for k in list(clips):
   if k>=level:del clips[k]
  if entry['type']=='clip':clips[level]=entry
  elif entry.get('seqno') is not None:clip_by_seq[entry['seqno']]=dict(clips)
 closed=[];open_paths=[]
 for n,draw in enumerate(page.get_drawings()):
  if draw.get('color')!=(1.,0.,0.) or not draw.get('layer','').startswith('circonscription_isie2023'):continue
  coords=[];continuity=True;kinds=[]
  for item in draw['items']:
   kinds.append(item[0])
   if item[0]!='l':continuity=False;continue
   a,b=tuple(item[1]),tuple(item[2])
   if not coords:coords.append(a)
   if coords[-1]!=a:continuity=False
   coords.append(b)
  explicit=bool(coords and coords[0]==coords[-1]);implicit=bool(draw['closePath'] and not explicit)
  if not continuity or not (explicit or implicit):
   open_paths.append({'drawingIndex':n,'seqno':draw['seqno'],'itemsKinds':sorted(set(kinds)),'contiguous':continuity,'closedByPdf':bool(draw['closePath']),'explicitlyClosed':explicit,'nativeVertices':coords});continue
  if implicit:coords.append(coords[0])
  p=Polygon(coords);clip_results=[]
  for lev,cl in clip_by_seq.get(draw['seqno'],{}).items():
   if len(cl['items'])==1 and cl['items'][0][0]=='qu':
    q=cl['items'][0][1];cg=Polygon([tuple(q.ul),tuple(q.ur),tuple(q.lr),tuple(q.ll)])
    clip_results.append({'level':lev,'wholePathInside':cg.covers(p)})
   else:clip_results.append({'level':lev,'wholePathInside':None,'unsupportedClipKinds':[x[0] for x in cl['items']]})
  valid=p.is_valid;inside=[x for x in labels if valid and p.covers(Point(*x['point']))]
  target=Polygon(np.column_stack((np.array(coords),np.ones(len(coords))))@affine)
  closed.append({'drawingIndex':n,'seqno':draw['seqno'],'layer':draw['layer'],'explicitlyClosed':explicit,'pdfImplicitClosingEdge':implicit,
   'nativeVertices':coords,'verticesIncludingClosure':len(coords),'validNativeRing':valid,'validity':explain_validity(p),'insideViewport':viewport.covers(p),
   'activeClips':clip_results,'insideRedLabels':inside,'targetLabels':[x for x in inside if x['isTargetName']],
   'areaM2UTM32':target.area,'geometry':mapping(transform(inverse.transform,target))})
 results.append({'source':source,'wholePageRender':pin(render),'pageCount':1,'pageMediaBox':list(page.mediabox),'viewportBBox':vp,
  'pageTransformationMatrix':list(page.transformation_matrix),'measureXref':mx,'gcsXref':gx,'crsWkt':wkt,'crsEpsg':32632,
  'lpts':lpts.tolist(),'gptsLatLon':gpts.tolist(),'affineMetersFromPage':affine.tolist(),'maxControlResidualM':float(max(residual)),
  'redLabels':labels,'closedPaths':closed,'openOrUnsupportedPaths':open_paths})
out=D/'unreviewed-native-path-inventory.json';assert not out.exists()
out.write_text(json.dumps({'status':'UNREVIEWED_NATIVE_PATH_INVENTORY_PERSONAL_SOURCE_PAGE_REVIEW_REQUIRED','retrievalManifest':pin(D/'retrieval-manifest.json'),'records':results,'producer':pin(__file__)},ensure_ascii=False,indent=2)+'\n',encoding='utf8')
print(json.dumps({'inventory':pin(out),'records':[{'key':x['source']['key'],'closed':[{'drawing':c['drawingIndex'],'valid':c['validNativeRing'],'viewport':c['insideViewport'],'clips':c['activeClips'],'targetLabels':[l['text'] for l in c['targetLabels']],'areaM2':c['areaM2UTM32'],'vertices':c['verticesIncludingClosure']} for c in x['closedPaths']],'openCount':len(x['openOrUnsupportedPaths'])} for x in results]},ensure_ascii=False,indent=2))
