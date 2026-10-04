"""Group accepted finite batches for review without adding validation credit.

Preserve original acceptance times, evidence, snapshots and every coordinate.
No older family bodies or unrelated locations are revalidated.
"""
import argparse,ast,json,sys
from copy import deepcopy
from datetime import datetime,timezone
from pathlib import Path
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,checked,pin,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--work',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True);p.add_argument('--family',required=True);p.add_argument('--child-work',type=Path,action='append',required=True);p.add_argument('--family-total',type=int,required=True);a=p.parse_args();w=a.work.resolve();e=a.evidence.resolve();c=read(w.parent/'control.json');s={k:c[k]for k in ['iteration','windowStartUtc','deadlineUtc']};s.update(control=str(w.parent/'control.json'),owner='/root');active_control(s)
 public=e/'work/locality-progress-dashboard';model=read(public/'task-report.json');m=read(public/'boundary-map.json');publications=[pin(child/(a.family+'-incremental-publication-receipt.json'))for child in a.child_work];pubs=[read(checked(ref))for ref in publications];ids=['practical-'+p['practicalReceipt']['sha256'][:16]for p in pubs];children=[next(b for b in m['batches']if b['id']==ident)for ident in ids]
 rows=[];new=set();upgraded=set()
 for b in children:
  new.update(b['newLocationCodes']);upgraded.update(b['fullScopeUpgradeCodes'])
  rows.extend({**deepcopy(r),'originalAcceptedBatchId':b['id']}for r in b['locations'])
 if len({r['code']for r in rows})!=len(rows)or new&upgraded or {r['code']for r in rows}!=new|upgraded:raise ValueError('Group must contain disjoint real additions and upgrades only')
 latest=max(children,key=lambda b:datetime.fromisoformat(b['atUtc']));ident='view:'+a.family+'-complete-'+str(c['iteration']);label=a.family.capitalize()+' complete · '+str(a.family_total)+'/'+str(a.family_total)+' · '+str(len(new))+' NEW · '+str(len(upgraded))+' FULL upgrades'
 group={**deepcopy(latest),'id':ident,'label':label,'kind':'presentation_group','presentationOnly':True,'sourceBatchIds':ids,'atUtc':latest['atUtc'],'locationCount':len(rows),'locations':sorted(rows,key=lambda r:r['code']),'newLocationCodes':sorted(new),'fullScopeUpgradeCodes':sorted(upgraded),'fullCount':len(rows),'scopeCount':0,'reviewContextPoints':[p for b in children for p in b.get('reviewContextPoints',[])],'probeCount':sum(b.get('probeCount',0)or 0 for b in children),'qualification':'Display group of existing accepted batches only; zero new validation or installation credit. Every row keeps its originalAcceptedBatchId so inspection, export and issue points retain the original time, evidence and snapshot.'}
 m['batches'].insert(0,group);m['latestBatchId']=ident;m['preferredViewId']=ident;m['generatedAtUtc']=datetime.now(timezone.utc).isoformat();model['generatedAtUtc']=m['generatedAtUtc'];model.setdefault('completedFamilies',{})[a.family]={'officialEntries':a.family_total,'completeAcceptedEntries':a.family_total,'reviewViewId':ident,'currentWorkNewLocations':len(new),'currentWorkFullUpgrades':len(upgraded)}
 for name in ['task-report.json','boundary-map.json']:
  with (w/('before-completed-family-group-'+name)).open('xb')as f:f.write((public/name).read_bytes())
 ui=public/'boundary_map_ui.py';source=ui.read_text(encoding='utf-8');before=source
 loop="for(const b of [...D.batches].sort((a,z)=>new Date(a.atUtc)-new Date(z.atUtc)))for(const r of b.locations)"
 default="el('map-batch').value=latestAdditionRows.length?LATEST_ADDITIONS_VIEW:(D.latestBatchId||'all');"
 if source.count(loop)!=1 or source.count(default)!=1:raise ValueError('Expected original group-compatible map UI anchors')
 grouped_loop=loop.replace(')for(const r',')if(!b.presentationOnly)for(const r')
 preferred="el('map-batch').value=D.preferredViewId===D.latestBatchId&&byBatch.has(D.preferredViewId)?D.preferredViewId:(latestAdditionRows.length?LATEST_ADDITIONS_VIEW:(D.latestBatchId||'all'));"
 source=source.replace(loop,grouped_loop).replace(default,preferred)
 ast.parse(source)
 with (w/'boundary-map-ui-before-family-group.py').open('x',encoding='utf-8')as f:f.write(before)
 ui.write_text(source,encoding='utf-8')
 sys.path.insert(0,str(public));import task_report_app as app
 original=(app.build_task_report,app.build_boundary_map,app.refresh_frina_investigation,sys.argv)
 try:
  app.build_task_report=lambda:model;app.build_boundary_map=lambda unused:m;app.refresh_frina_investigation=lambda:None;sys.argv=[str(public/'task_report_app.py'),'refresh'];app.main()
 finally:app.build_task_report,app.build_boundary_map,app.refresh_frina_investigation,sys.argv=original
 with (w/'completed-family-map.syntax.js').open('x',encoding='utf-8')as f:f.write(app.map_scripts())
 put(w/'completed-family-map-group-receipt.json',{'status':'GROUPED_EXISTING_ACCEPTED_BATCHES_NO_NEW_CREDIT','presentationViewId':ident,'label':label,'sourceBatches':publications,'originalAcceptedBatchIdsPreserved':True,'unchangedCoordinatesByConstruction':True,'locationCount':len(rows),'newLocationCount':len(new),'newLocationCodes':sorted(new),'fullScopeUpgradeCodes':sorted(upgraded),'additionalValidationCredit':0,'additionalGpsChecks':0,'olderFamilyBodiesRechecked':0,'unrelatedLocationChecks':0,'privateGoogleConfigRead':False,'actualBrowserRenderingTested':False,'report':pin(public/'task-report.json'),'map':pin(public/'boundary-map.json'),'html':pin(public/'dashboard.html'),'ui':pin(ui),'syntax':pin(w/'completed-family-map.syntax.js')})
 print(json.dumps({'view':ident,'label':label,'rows':len(rows),'newCredit':0}))
if __name__=='__main__':main()
