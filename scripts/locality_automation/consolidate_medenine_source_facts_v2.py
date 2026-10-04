"""Consolidate reviewed successors; measure incident source faces without repairs."""
import json,sys
from pathlib import Path
from shapely import from_wkb
from shapely.geometry import Point
from pyproj import Transformer
from PIL import Image,ImageDraw
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,checked,pin,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
E=Path(r'C:\Users\barou\Documents\Codex\2026-09-06\the-android-app-app-currently-allows');W=E/'work/locality-efficiency-cycles-20261001/root-cycle24'
s=read(W/'medenine-originals-manifest.json');active_control(s)
rows={}
for dirname in ['native-source-v1','native-source-v2','native-source-v3','derjaoua-neighbor-525951']:
 for r in read(W/dirname/'native-source-review.json')['rows']:
  if 'geometry' in r or r['officialCode'] not in rows:rows[r['officialCode']]=r
for r in rows.values():
 for k in ['sourcePdf','sourceInventory','rawSourceGeometry','geometry','sourceMetricGeometry','nativePageGeometry','originalRender']:
  if k in r:r[k]=pin(checked(r[k]))
put(W/'combined-native-source-review-v2.json',{'rows':list(rows.values()),'credit':0,'actualSourceReviewer':'/root'})
polys={c:from_wkb(checked(r['sourceMetricGeometry']).read_bytes())for c,r in rows.items()if 'sourceMetricGeometry'in r}
pairs=[]
for c,g in polys.items():
 for d,h in polys.items():
  if d<=c or not g.envelope.buffer(5).intersects(h.envelope):continue
  if c not in s['exactTargets']and d not in s['exactTargets']:continue
  intersection=g.intersection(h);shared=g.boundary.intersection(h.boundary.buffer(5)).length
  if intersection.area>1 or shared>10:pairs.append({'a':c,'b':d,'overlapM2':intersection.area,'sharedWithin5mM':shared,'gapM':g.distance(h),'overlapFractionSmaller':intersection.area/min(g.area,h.area)})
project=Transformer.from_crs(4326,32632,always_xy=True).transform
foreign=[]
from scripts.locality_automation.isie_candidate_comparison import _name_match
for c,r in rows.items():
 if c not in polys:continue
 for l in r['insideLabels']:
  if l['own']:continue
  matched=[d for d,v in rows.items()if d in polys and d!=c and _name_match(l['text'],v.get('officialName',''))]
  p=Point(*project(l['lng'],l['lat']))
  foreign.append({'code':c,'text':l['text'],'ownSourceEdgeDistanceM':p.distance(polys[c].boundary),'candidateOwners':[{'code':d,'insideOwnerFace':polys[d].covers(p),'outsideOwnerDistanceM':p.distance(polys[d])}for d in matched]})
derja=from_wkb(checked(read(W/'derjaoua-neighbor-525955/native-source-review.json')['rows'][0]['sourceMetricGeometry']).read_bytes())
g=polys['525961'];derjaComparison={'sources':['525951','525955'],'areasM2':[g.area,derja.area],'iou':g.intersection(derja).area/g.union(derja).area,'hausdorffM':g.hausdorff_distance(derja),'symmetricDifferenceM2':g.symmetric_difference(derja).area}
put(W/'paired-source-facts-v2.json',{'pairs':pairs,'foreignLabelCenters':foreign,'derjaouaTwoPassingNeighborSources':derjaComparison,'qualification':'Literal native source comparisons; no snapping, source repair or ownership alteration.','credit':0})
out=W/'selected-source-overlays-v1';out.mkdir()
for c in ['525961','525251','525453','525157']:
 r=rows[c];g=from_wkb(checked(r['nativePageGeometry']).read_bytes());fig=Image.open(checked(r['originalRender'])).convert('RGB');d=ImageDraw.Draw(fig)
 for ring in [g.exterior,*g.interiors]:d.line([(x*1.8,y*1.8)for x,y in ring.coords],fill=(0,210,0),width=5)
 fig.save(out/(c+'.png'))
print(json.dumps({'holds':[r for r in rows.values()if 'geometry'not in r],'foreign':foreign,'materialPairs':[p for p in pairs if p['overlapFractionSmaller']>.01],'derja':derjaComparison},ensure_ascii=False))
for code in ['525151','525156','525352']:
 r=read(W/('qasr-peer-'+code)/'native-source-review.json')['rows'][0]
 print(json.dumps({'qasrNeighbor':code,'areaM2':r.get('sourceAreaM2'),'wholePage':r.get('wholeNativeFaceInsidePage'),'admin':r.get('nativeInteriorAdminLengthsPagePoints'),'labels':r.get('insideLabels'),'error':r.get('error')},ensure_ascii=False))
