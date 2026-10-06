"""Diagnose finite source/rounding/pair holds before any scope writer or JVM work."""
import argparse,json,sys
from pathlib import Path
from shapely import from_wkb
from shapely.ops import transform
from pyproj import Transformer
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--reviews',type=Path,nargs='+',required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
s=read(a.manifest);c=active_control(s);codes=s['exactTargets']
if codes!=c['approvedCycle'+str(c['iteration'])+'AcceptancePool']:raise ValueError('Exact finite scope differs')
rows={r['officialCode']:r for f in a.reviews for r in read(f)['rows']}
if set(rows)!=set(codes):raise ValueError('Missing or extraneous target source facts')
to_m=Transformer.from_crs(4326,32632,always_xy=True).transform;holds=[];facts=[];geoms={}
for code in codes:
 r=rows[code]
 if 'error'in r:holds.append({'code':code,'hold':r['error']});continue
 loaded={k:from_wkb(checked(r[k]).read_bytes())for k in ['geometry','rawSourceGeometry','sourceMetricGeometry','nativePageGeometry']}
 invalid=[k for k,g in loaded.items()if g.is_empty or not g.is_valid]
 delta=transform(to_m,loaded['rawSourceGeometry']).hausdorff_distance(transform(to_m,loaded['geometry']))
 if invalid or delta>=.1:holds.append({'code':code,'invalid':invalid,'rawToGridHausdorffM':delta})
 facts.append({'code':code,'rawToGridHausdorffM':delta,'valid':not invalid});geoms[code]=loaded['sourceMetricGeometry']
pairs=[]
for i,code in enumerate(codes):
 if code not in geoms:continue
 g=geoms[code]
 for other in codes[i+1:]:
  if other not in geoms or not g.envelope.intersects(geoms[other].envelope):continue
  h=geoms[other];overlap=g.intersection(h).area
  if overlap/min(g.area,h.area)>.01:pairs.append({'a':code,'b':other,'overlapM2':overlap,'fractionSmaller':overlap/min(g.area,h.area)})
result={'status':'DIAGNOSTIC_ONLY_REQUIRES_FINAL_SOURCE_ADMISSION','manifest':pin(a.manifest.resolve()),'reviews':[pin(f.resolve())for f in a.reviews],'preparedBodies':len(facts),'holds':holds,'materialPairs':pairs,'rounding':facts,'maximumRawToGridHausdorffM':max([r['rawToGridHausdorffM']for r in facts],default=0),'sourceScopeAccepted':False,'credit':0}
with a.output.open('x',encoding='utf-8')as f:json.dump(result,f,ensure_ascii=False,indent=2)
print(json.dumps({k:result[k]for k in ['preparedBodies','holds','materialPairs','maximumRawToGridHausdorffM','credit']}))
