"""Include prior accepted family boundaries in the completed review map.

Reuse receipt-bound display rows, preserve new/upgrade highlights, zero credit.
"""
import argparse,json,sys
from pathlib import Path
from copy import deepcopy
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--work',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True);p.add_argument('--family',required=True);a=p.parse_args();w=a.work.resolve();e=a.evidence.resolve();c=read(w.parent/'control.json');s={k:c[k]for k in ['iteration','windowStartUtc','deadlineUtc']};s.update(control=str(w.parent/'control.json'),owner='/root');active_control(s)
public=e/'work/locality-progress-dashboard';model=read(public/'task-report.json');m=read(public/'boundary-map.json');before=deepcopy(m)
group=next(b for b in m['batches']if b['id']==m['preferredViewId'])
if not group.get('presentationOnly')or not group['id'].startswith('view:'+a.family+'-group-')or c['familySlug']!=a.family:raise ValueError('Current completed-family presentation group required')
family=set(c['allFamilyOfficialCodes']);accepted=family&set(model['summary']['explicitFullSourceBoundaryLocalityCodes'])
if accepted!=family:raise ValueError('Only a completed family may receive reused context')
present={r['code']for r in group['locations']};missing=sorted(family-present);rows={r['code']:r for r in m['locations']};original_ids=[]
for code in missing:
 r=rows[code]
 if r['latestScope']!='full'or not r['currentMatchesAccepted']:raise ValueError('Reused display body lacks accepted full scope')
 history=[v for v in r['history']if v['scope']=='full'];batch_id=history[-1]['batchId'];b=next(b for b in m['batches']if b['id']==batch_id and not b.get('presentationOnly'))
 old=next(v for v in b['locations']if v['code']==code)
 if old['geometry']!=r['geometry']:raise ValueError('Reused accepted display coordinates differ')
 group['locations'].append({**deepcopy(old),'originalAcceptedBatchId':batch_id,'newLocation':False,'fullScopeUpgrade':False,'reusedPreviouslyAccepted':True});original_ids.append(batch_id)
group['locations'].sort(key=lambda r:r['code']);group['locationCount']=len(family);group['fullCount']=len(family);group['reusedPreviouslyAcceptedCodes']=missing;group['reusedSourceBatchIds']=sorted(set(original_ids));group['qualification']+=' Previously accepted complete family bodies are included as neutral context with original acceptance evidence/time; they are not new additions or upgrades.'
if {r['code']for r in group['locations']}!=family or group['newLocationCodes']!=before['batches'][0]['newLocationCodes']or group['fullScopeUpgradeCodes']!=before['batches'][0]['fullScopeUpgradeCodes']or m['locations']!=before['locations']:raise ValueError('Presentation changed qualified history/accounting')
put(w/'before-reused-family-map-context-v1.json',before)
sys.path.insert(0,str(public));import task_report_app as app
original=(app.build_task_report,app.build_boundary_map,app.refresh_frina_investigation,sys.argv)
try:
 app.build_task_report=lambda:model;app.build_boundary_map=lambda unused:m;app.refresh_frina_investigation=lambda:None;sys.argv=[str(public/'task_report_app.py'),'refresh'];app.main()
finally:app.build_task_report,app.build_boundary_map,app.refresh_frina_investigation,sys.argv=original
published=read(public/'boundary-map.json');actual=next(b for b in published['batches']if b['id']==group['id'])
if actual!=group or published['locations']!=before['locations']or read(public/'task-report.json')['summary']!=model['summary']:raise ValueError('Published reused context changed geometry or counts')
script=w/'completed-family-map.syntax-v2.js'
with script.open('x',encoding='utf-8')as f:f.write(app.map_scripts())
put(w/'completed-family-map-reused-context-v1.json',{'status':'ALL_FAMILY_ACCEPTED_BOUNDARIES_VISIBLE_WITH_REUSED_NEUTRAL_CONTEXT','view':group['id'],'visibleCompleteBodies':len(family),'newLocationCodes':group['newLocationCodes'],'fullScopeUpgradeCodes':group['fullScopeUpgradeCodes'],'reusedPreviouslyAcceptedCodes':missing,'originalAcceptedBatchIdsPreserved':True,'allHistoricalCoordinatesAndCountsUnchanged':True,'additionalValidationCredit':0,'additionalGpsChecks':0,'receipt':pin(w/'completed-family-map-group-receipt.json'),'map':pin(public/'boundary-map.json'),'script':pin(script),'staticScriptUnchanged':script.read_bytes()==(w/'completed-family-map.syntax.js').read_bytes()})
print(json.dumps({'visibleCompleteBodies':len(family),'reusedNeutralContext':missing,'new':len(group['newLocationCodes']),'upgrades':len(group['fullScopeUpgradeCodes']),'credit':0}))
