"""Join prepared changed bodies and measure only their incident accepted neighbors."""
import argparse,json,sys
from pathlib import Path
from shapely import from_wkb
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--work',type=Path,required=True);p.add_argument('--sources',nargs='+',type=Path,required=True);p.add_argument('--accepted-scopes',nargs='+',type=Path,required=True);a=p.parse_args();w=a.work.resolve();c=read(w.parent/'control.json');spec={k:c[k]for k in ['iteration','windowStartUtc','deadlineUtc']};spec.update(control=str(w.parent/'control.json'),owner='/root');active_control(spec);pool=c['approvedCycle'+str(c['iteration'])+'AcceptancePool']
 rows=[r for f in a.sources for r in read(f)['rows']]
 if set(r['officialCode']for r in rows)!=set(pool)or len(rows)!=len(pool)or any('error'in r for r in rows):raise ValueError('Exactly the prepared changed source bodies required')
 rows.sort(key=lambda r:pool.index(r['officialCode']));targets={r['officialCode']:from_wkb(checked(r['sourceMetricGeometry']).read_bytes())for r in rows};neighbors={}
 for folder in a.accepted_scopes:
  for path in folder.glob('*-source-scope.json'):
   r=read(path)
   if r['officialCode']in pool or not r.get('sourceScopeAccepted')or not r['officialCode'].startswith(c['governorateCode']):continue
   geometry=from_wkb(checked(r['sourceMetricGeometry']).read_bytes())
   if any(g.envelope.buffer(10).intersects(geometry.envelope)for g in targets.values()):neighbors[r['officialCode']]=(r,geometry,pin(path))
 pairs=[]
 for code,g in targets.items():
  for other,(row,h,ref)in neighbors.items():
   if not g.envelope.buffer(10).intersects(h.envelope)or g.distance(h)>10:continue
   area=g.intersection(h).area;pairs.append({'target':code,'neighbor':other,'neighborName':row['officialName'],'acceptedSourceReused':ref,'overlapM2':area,'overlapFractionSmaller':area/min(g.area,h.area),'gapM':g.distance(h),'boundaryWithin5mLengthM':g.boundary.intersection(h.boundary.buffer(5)).length})
 joined=put(w/'gafsa-final-source-facts.json',{'status':'PREPARED_EXACT_SOURCE_BODIES_REQUIRE_ROOT_FINAL_ADMISSION','rows':rows,'inputs':[pin(f.resolve())for f in a.sources],'credit':0})
 put(w/'gafsa-final-incident-facts.json',{'status':'LITERAL_TARGET_INCIDENT_NEIGHBOR_FACTS_NO_OWNERSHIP_EDITS','inputs':[joined],'pairs':pairs,'alreadyAcceptedSourceFactsReused':True,'otherLocationSourceOrGpsGatesRerun':False,'credit':0})
 decision=read(w/'gafsa-last-two-decisions.json');decision['commonEvidence'] += [joined,pin(w/'gafsa-final-incident-facts.json')];(w/'gafsa-last-two-final-decisions.json').write_text(json.dumps(decision,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps({'codes':pool,'incidentComparisons':len(pairs),'materialOverlaps':[r for r in pairs if r['overlapFractionSmaller']>.01],'credit':0}))
if __name__=='__main__':main()
