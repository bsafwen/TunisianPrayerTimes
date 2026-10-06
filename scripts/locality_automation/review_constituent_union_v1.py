"""Verify every declared original constituent, then preserve its literal union.

Each component uses the unchanged original registration/native gates. Cross-PDF
unions retain original gaps and overlaps without snapping or invented separators.
"""
import argparse,json,sys
from pathlib import Path
from datetime import datetime,timezone
from shapely import from_wkb,set_precision
from shapely.ops import unary_union,transform
from pyproj import Transformer
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.review_native_faces_finite_v8 import review,put_geometry

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--plan',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();spec=read(a.manifest);c=active_control(spec);pool=c['approvedCycle'+str(c['iteration'])+'AcceptancePool'];plan=read(a.plan)
 if not set(r['officialCode']for r in plan['cases'])<=set(pool):raise ValueError('Explicit review subset outside finite scope')
 out=a.output.resolve();out.mkdir();rows=[];to_m=Transformer.from_crs(4326,32632,always_xy=True).transform
 for r in plan['cases']:
  code=r['officialCode'];pieces=[]
  try:
   declared=[n for case in r['components']for n in case['declaredCircleIds']]
   if sorted(declared)!=list(range(1,r['expectedCircleCount']+1)):raise ValueError('Complete declared circle roster differs')
   for i,case in enumerate(r['components']):
    folder=out/(code+'-constituent-'+str(i+1));review({**spec,'cases':[case]},folder);facts=read(folder/'native-source-review.json')['rows'][0]
    if 'error'in facts:raise ValueError(facts['error'])
    if not facts['wholeNativeFaceInsidePage']or any(facts['nativeInteriorAdminLengthsPagePoints'].values()):raise ValueError('Constituent full-scope native gate held')
    pieces.append(facts)
   raw=unary_union([from_wkb(checked(v['rawSourceGeometry']).read_bytes())for v in pieces]);grid=set_precision(raw,1e-6)
   if not raw.is_valid or not grid.is_valid or raw.is_empty:raise ValueError('Literal constituent union invalid')
   primary=pieces[0];metric=transform(to_m,raw);pairs=[]
   for i,v in enumerate(pieces):
    g=from_wkb(checked(v['sourceMetricGeometry']).read_bytes())
    for j,t in enumerate(pieces[i+1:],i+1):
     h=from_wkb(checked(t['sourceMetricGeometry']).read_bytes());pairs.append({'a':i+1,'b':j+1,'overlapM2':g.intersection(h).area,'gapM':g.distance(h)})
   result={**primary,'sourceMethod':'complete_literal_union_of_all_decree_constituent_circles','rawSourceGeometry':put_geometry(out/(code+'-raw.wkb'),raw),'geometry':put_geometry(out/(code+'-grid.wkb'),grid),'sourceMetricGeometry':put_geometry(out/(code+'-metric.wkb'),metric),'nativePageGeometryIsPrimaryConstituentOnly':True,'constituentNativeFacts':pieces,'constituentSourceFactReceipts':[pin(out/(code+'-constituent-'+str(i+1)+'/'+code+'-source-facts.json'))for i in range(len(pieces))],'expectedCircleCount':r['expectedCircleCount'],'literalConstituentUnion':True,'originalSeamsRetained':pairs,'registration':{'fitResidualM':max(p['registration']['fitResidualM']for p in pieces),'looMaxM':max(p['registration']['looMaxM']for p in pieces)},'sourceAreaM2':metric.area,'wholeNativeFaceInsidePage':all(p['wholeNativeFaceInsidePage']for p in pieces),'nativeInteriorAdminLengthsPagePoints':{'blue':0.0,'black':0.0},'originalNativePointsMatchSavedInventory':all(p['originalNativePointsMatchSavedInventory']for p in pieces),'sourceScopeAccepted':False,'credit':0,'sourceReviewPlan':pin(a.plan.resolve())}
   rows.append(result);put(out/(code+'-source-facts.json'),result)
   print(json.dumps({'code':code,'components':len(pieces),'areaKm2':metric.area/1e6,'originalSeams':pairs}),flush=True)
  except Exception as ex:
   rows.append({'officialCode':code,'status':'HOLD_CONSTITUENT_SOURCE','error':str(ex),'constituentsPrepared':len(pieces),'credit':0});print(json.dumps(rows[-1]),flush=True)
 put(out/'native-source-review.json',{'status':'CONSTITUENT_FACTS_REQUIRE_ROOT_ROSTER_VISUAL_NEIGHBOR_REVIEW','rows':rows,'credit':0,'completedAtUtc':datetime.now(timezone.utc).isoformat()})
if __name__=='__main__':main()
