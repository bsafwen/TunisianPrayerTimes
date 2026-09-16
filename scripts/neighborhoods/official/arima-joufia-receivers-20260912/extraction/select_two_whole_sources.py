from pathlib import Path
from decimal import Decimal
from fractions import Fraction
from html.parser import HTMLParser
import hashlib,json,re,zipfile,xml.etree.ElementTree as ET
import numpy as np
import pymupdf
from pypdf import PdfReader
from pypdf.generic import ContentStream
from shapely.geometry import shape,Polygon,Point
from pyproj import Transformer
from shapely.ops import transform

D=Path(__file__).resolve().parent;T=D.parents[1];R=Path('C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes')
def read(p):return json.loads(Path(p).read_bytes())
def pin(p):
 p=Path(p).resolve();return {'file':p.as_posix(),'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'bytes':p.stat().st_size}
def save(p,o):
 assert not p.exists(),p
 p.write_text(json.dumps(o,ensure_ascii=False,indent=2)+'\n',encoding='utf8',newline='\n')
def ch(o):return hashlib.sha256(json.dumps(o,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest()
ip=D/'unreviewed-native-path-inventory.json';mp=D/'retrieval-manifest.json'
assert pin(ip)['sha256']=='e62e68308526d7255fbe447728b5936029aff8dec0a4ce646d302f277849f399'
assert pin(mp)['sha256']=='dd7adf0cbb851dd5f4d7557fe4ce6420fd770d9605857d38ec12a489dcb06868'
meta=R/'android-app/app/src/main/assets/neighborhoods.json';assert pin(meta)['sha256']=='992b1409c45567e83d153cd2b033a5ea1466413c8d3e0b6f3861d4fb0b9f3b41'
rows={r['id']:r for r in read(meta)['features']};cachep=T/'work/geo/osm-areas.json';cache=read(cachep);areas={a['id']:a for a in cache['areas']}
country=shape(areas['osm:relation:192757']['geometry'])
regp=R/'scripts/neighborhoods/point-retention-reviews/current-official-sector-registry.json';reg=read(regp)
forward=Transformer.from_crs(4326,32632,always_xy=True)
selections={'arima':(601,76,5173),'menzel-bouzelfa-joufia':(374,77,5174)}
features=[];facts=[]
for iv in read(ip)['records']:
 src=iv['source'];key=src['key'];di,dp,jp=selections[key]
 c=next(c for c in iv['closedPaths'] if c['drawingIndex']==di)
 assert c['validNativeRing'] and c['insideViewport'] and len(c['activeClips'])==2 and all(x['wholePathInside'] for x in c['activeClips']) and len(c['targetLabels'])==1
 assert c['explicitlyClosed'] and not c['pdfImplicitClosingEdge']
 rid=src['currentRawId'];rr=next(r for r in reg['sectors'] if r['sectorCode']==src['currentCode'])
 assert rr==src['officialRegistryRow'] and areas[rid]['tags']['ref:tn:codegeo']==rr['sectorCode']
 g=shape(c['geometry']);assert g.is_valid and country.covers(g)
 doc=pymupdf.open(src['response']['file']);page=doc[0];native=c['nativeVertices']
 assert hashlib.sha256(page.get_pixmap(matrix=pymupdf.Matrix(1.65,1.65)).tobytes('png')).hexdigest()==iv['wholePageRender']['sha256']
 draw=page.get_drawings()[di];assert draw['layer']=='circonscription_isie2023' and draw['fill'] is None and draw['type']=='s'
 reader=PdfReader(src['response']['file']);ops=ContentStream(reader.pages[0]['/Contents'],reader).operations;matches=[]
 for start,(args,op) in enumerate(ops):
  if op!=b'm':continue
  end=start+1
  while end<len(ops) and ops[end][1]==b'l':end+=1
  if end-start!=len(native):continue
  h=end<len(ops) and ops[end][1]==b'h';stroke=end+int(h)
  if stroke>=len(ops) or ops[stroke][1]!=b'S':continue
  dec=[[str(v) for v in a] for a,o in ops[start:end]]
  converted=[[float(np.float32(float(x))),float(np.float32(np.float32(page.rect.height)-np.float32(float(y))))] for x,y in dec]
  if converted==native:matches.append({'moveOperation':start,'strokeOperation':stroke,'rawDecimalVertices':dec,'redundantPdfCloseAfterExplicitClosure':h})
 assert len(matches)==1,(key,len(matches));match=matches[0];raw=match['rawDecimalVertices'];assert raw[0]==raw[-1]
 p=Polygon([[float(x),float(y)] for x,y in raw]);assert p.is_valid and p.exterior.is_simple
 assert len(set(map(tuple,raw[:-1])))==len(raw)-1
 I=((Fraction(1),Fraction(0),Fraction(0)),(Fraction(0),Fraction(1),Fraction(0)),(Fraction(0),Fraction(0),Fraction(1)));M=I;stack=[]
 def multiply(a,b):return tuple(tuple(sum(a[i][k]*b[k][j] for k in range(3)) for j in range(3)) for i in range(3))
 for args,op in ops[:match['moveOperation']]:
  if op==b'q':stack.append(M)
  elif op==b'Q':M=stack.pop()
  elif op==b'cm':
   aa,bb,cc,dd,ee,ff=[Fraction(str(v)) for v in args];M=multiply(M,((aa,cc,ee),(bb,dd,ff),(Fraction(0),Fraction(0),Fraction(1))))
 assert M==I
 record={'id':rid,'code':rr['sectorCode'],'registryRow':rr,'currentRaw':rows[rid],'originalCacheTags':areas[rid]['tags'],'sourcePdf':src['response'],'sourceUrl':src['requestedUrl'],
  'wholePageRender':iv['wholePageRender'],'wholePagePersonallyViewed':True,'selectedDrawingIndex':di,'nativeVertexCount':len(native),'nativeGeometrySha256':ch(c['geometry']),
  'originalPath':{**match,'edgeCount':len(native)-1,'identityCurrentTransform':True,'explicitOriginalClosure':True,'simpleValidOriginalRing':True},
  'registration':{k:iv[k] for k in ['viewportBBox','pageTransformationMatrix','measureXref','gcsXref','crsWkt','crsEpsg','lpts','gptsLatLon','affineMetersFromPage','maxControlResidualM']},
  'wholePathInsideViewportAndBothActiveClips':True,'interiorTargetLabel':c['targetLabels'][0],'sourceCountryFullyCoversWholeCandidate':True,'areaM2UTM32':c['areaM2UTM32'],
  'decreeWholeImadaScope':{'file':pin(T/'work/electoral-circle-semantics/decree2023-590.pdf'),'pdfPage':dp,'jortPage':jp,'wholePageRender':pin(T/f'work/soliman-thirteen-source-scope-support/decree-page-{dp}.png'),'wholePagePersonallyViewed':True,'imadaName':rr['sectorAr'],'circleName':rr['sectorAr'],'conclusion':'one_whole_imada_one_electoral_circle'},
  'qualification':'Complete original vector source outline. No clipping, repairs, snapping, inferred edges or Google tracing. Embedded registration is not a survey accuracy certificate.'}
 facts.append(record)
 features.append({'type':'Feature','id':rid,'properties':{'sourcePdf':src['response'],'sourceUrl':src['requestedUrl'],'registryCode':rr['sectorCode'],'registryRow':rr,'selectedDrawingIndex':di,'nativeVertexCount':len(native)},'geometry':c['geometry']})

# Re-read exact identity rows from all three unchanged original INS workbooks.
ns={'s':'http://schemas.openxmlformats.org/spreadsheetml/2006/main'};workbooks=[]
for src in reg['sources']:
 path=Path(src['localFile']);assert pin(path)['sha256']==src['sha256']
 targets={r['registryRow']['sourceRows'][src['file']]:r['code'] for r in facts}
 with zipfile.ZipFile(path) as z:
  strings=[''.join(t.text or '' for t in si.iter() if t.tag.endswith('}t')) for si in ET.fromstring(z.read('xl/sharedStrings.xml')).findall('s:si',ns)]
  book=ET.fromstring(z.read('xl/workbook.xml'));sheet=next(s for s in book.find('s:sheets',ns) if s.attrib['name']==src['sheet']);rel=sheet.attrib['{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id']
  target=next(x.attrib['Target'] for x in ET.fromstring(z.read('xl/_rels/workbook.xml.rels')) if x.attrib['Id']==rel)
  headers=None;found=[]
  for _,row in ET.iterparse(z.open('xl/'+target),events=['end']):
   if not row.tag.endswith('}row'):continue
   n=int(row.attrib['r'])
   if headers is None or n in targets:
    vals={re.sub(r'\d','',c.attrib['r']):(strings[int(c.find('s:v',ns).text)] if c.attrib.get('t')=='s' else c.find('s:v',ns).text if c.find('s:v',ns) is not None else None) for c in row}
    if headers is None:headers=vals
    else:
     vals={headers[k]:v for k,v in vals.items()};code=vals.get('Code_secteur_D',vals.get('Code_Secteur'));assert code==targets[n]
     identity={'governorateCode':vals['Code_Gouvernorat'],'governorateFr':vals['Gouvernorat'],'governorateAr':vals['الولاية'],'delegationCode':vals['Code_Delegation'],'delegationFr':vals['Délégations'],'delegationAr':vals['المعتمدية'],'sectorCode':code,'sectorFr':vals['Secteurs'],'sectorAr':vals['العمادة']}
     expected=next(r['registryRow'] for r in facts if r['code']==code);assert identity=={k:expected[k] for k in identity};found.append({'originalRow':n,'identityFields':identity})
   row.clear()
   if n>=max(targets):break
  assert len(found)==2
  workbooks.append({'originalWorkbook':pin(path),'url':src['url'],'sheet':src['sheet'],'exactIdentityRows':found})
bundle=D/'two-whole-native-source-polygons.geojson';save(bundle,{'type':'FeatureCollection','features':features})
out=D/'two-own-map-source-selection-facts.json';save(out,{'status':'QUALIFIED_TWO_WHOLE_SOURCE_OUTLINES_PENDING_INDEPENDENT_REVIEW','baselineMetadata':pin(meta),'retrievalManifest':pin(mp),'extractionInventory':pin(ip),'bundle':pin(bundle),'sourceCache':pin(cachep),'registry':pin(regp),'records':facts,'originalWorkbookIdentityChecks':workbooks,'producer':pin(__file__),'noFrozenThirteenStageOrLiveMutation':True})
print(json.dumps({'facts':pin(out),'bundle':pin(bundle),'selected':[{'id':r['id'],'code':r['code'],'path':r['selectedDrawingIndex'],'edges':r['originalPath']['edgeCount'],'areaKm2':r['areaM2UTM32']/1e6} for r in facts]},ensure_ascii=False))
