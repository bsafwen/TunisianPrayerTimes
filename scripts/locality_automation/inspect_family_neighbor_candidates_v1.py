"""Read-only early source overlap diagnostic against pinned accepted family neighbors.

This grants no acceptance credit and does not replace the final staged retained
incident check. Missing historical source pins remain explicit unknowns.
"""
import argparse,json,sys
from pathlib import Path
from shapely import from_wkb
from shapely.geometry import shape
from shapely.ops import transform
from pyproj import Transformer
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,checked,pin,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
p=argparse.ArgumentParser(description=__doc__)
for key in ['manifest','facts','baseline','before-map','output']:p.add_argument('--'+key,type=Path,required=True)
p.add_argument('--include-adopted-context',action='store_true')
a=p.parse_args();s=read(a.manifest);c=active_control(s);b=read(a.baseline);m=read(a.before_map)
assert s['exactTargets']==c['approvedCycle'+str(c['iteration'])+'AcceptancePool']
facts=read(a.facts)['rows'];assert {r['officialCode']for r in facts}==set(s['exactTargets'])
prior=set(b['alreadyCompleteFamilyCodes'])-set(s['exactTargets']);rows=[r for r in m['locations']if r['code']in prior]
assert {r['code']for r in rows}==prior
tm=Transformer.from_crs(4326,32632,always_xy=True).transform
targets={r['officialCode']:from_wkb(checked(r['sourceMetricGeometry']).read_bytes())for r in facts}
pairs=[];unknown=[]
for r in rows:
 assert r['latestScope']=='full' and r['currentMatchesAccepted']is True
 e=r['sourceGeometryEvidence']
 if not e:
  unknown.append(r['code'])
  if not a.include_adopted_context:continue
  h=transform(tm,shape(r['geometry']));role='adopted/current geometry diagnostic; historical raw source unknown'
 else:
  checked(e['scopeEvidence']);assert e['rawGeometryCrs']=='EPSG:4326'
  h=transform(tm,from_wkb(checked(e['rawSourceGeometry']).read_bytes()));role='pinned accepted raw source'
 for code,g in targets.items():
  if not g.envelope.buffer(10).intersects(h.envelope)or g.distance(h)>10:continue
  ar=g.intersection(h).area
  pairs.append({'target':code,'retained':r['code'],'overlapM2':ar,'overlapFractionSmaller':ar/min(g.area,h.area),'gapM':g.distance(h),'acceptedSourceEvidence':e['scopeEvidence']if e else None,'neighborGeometryRole':role})
material=[x for x in pairs if x['overlapFractionSmaller']>.01]
put(a.output,{'status':'EARLY_SOURCE_NEIGHBOR_DIAGNOSTIC_ONLY','inputs':[pin(x.resolve())for x in [a.manifest,a.facts,a.baseline,a.before_map]],'materialPairs':material,'pairs':pairs,'unknownHistoricalSourceCodes':unknown,'finalStagedIncidentCheckStillRequired':True,'credit':0})
print(json.dumps({'materialPairs':material,'unknownHistoricalSourceCodes':unknown,'incidentComparisons':len(pairs),'credit':0},ensure_ascii=False))
