"""Record an explicitly supplied human name equivalence without geometry work.

Preserve the historical hold, exact source body, counters and acceptance times.
"""
import argparse,json,sys,os
from datetime import datetime,timezone
from pathlib import Path
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,checked,pin,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
def atomic(path,value):
 t=path.with_name(path.name+'.identity.tmp');t.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');os.replace(t,path)
def main():
 p=argparse.ArgumentParser(description=__doc__)
 for k in ['work','evidence','historical-hold','source-scope']:p.add_argument('--'+k,type=Path,required=True)
 for k in ['code','official-name','ministry-name','statement']:p.add_argument('--'+k,required=True)
 a=p.parse_args();w=a.work.resolve();e=a.evidence.resolve();cp=w.parent/'control.json';c=read(cp);s={k:c[k]for k in ['iteration','windowStartUtc','deadlineUtc']};s.update(control=str(cp),owner='/root');active_control(s)
 scope=read(a.source_scope);old=read(a.historical_hold)
 if a.code not in c['allMedenineOfficialCodes']or scope['officialCode']!=a.code or scope['officialName']!=a.official_name or old['case']['ministryName']!=a.ministry_name:raise ValueError('Exact human-confirmed identity differs')
 out=w/('human-identity-confirmation-'+a.code);out.mkdir();now=datetime.now(timezone.utc).isoformat()
 qualification='Human explicitly confirmed '+a.official_name+' = '+a.ministry_name+' on2026-10-04. Exact INS/ISIE sector '+a.code+' and dated Ministry row '+old['case']['ministryRowId']+' are linked by that human confirmation. Historical official-crosswalk hold is preserved; no new primary official spelling-equivalence document is claimed. Boundary and app assets unchanged.'
 confirmation=put(out/'confirmation.json',{'status':'HUMAN_CONFIRMED_NAME_EQUIVALENCE','officialCode':a.code,'officialName':a.official_name,'ministryName':a.ministry_name,'ministryRowId':old['case']['ministryRowId'],'source':'Direct human message in this chat','humanStatementVerbatim':a.statement,'atUtc':now,'historicalHold':pin(a.historical_hold),'previousSourceScope':pin(a.source_scope),'newPrimaryOfficialEquivalenceFound':False,'literalMinistrySpellingMatch':False,'datedRosterMember':True,'qualification':qualification,'geometryChanged':False,'appAssetsChanged':False,'additionalGeographicCredit':0,'additionalCompleteBodyCredit':0,'additionalInstalledCorrectionCredit':0,'additionalGpsChecks':0})
 amended={**scope,'datedRosterMember':True,'ministrySpellingEquivalence':{'sourceRole':'Human explicit confirmation','statement':a.statement,'evidence':confirmation,'newPrimaryOfficialEquivalenceFound':False},'priorScope':pin(a.source_scope),'qualificationAmendment':qualification,'geometryUnchanged':True}
 amendedref=put(out/'qualified-source-scope.json',amended)
 runp=e/'work/isie-execution-20260926/run.json';hp=e/'work/saved-review-controller-20260920/running-handoff.json'
 for name,path in [('control',cp),('run',runp),('handoff',hp)]:
  with (out/('before-'+name+'.json')).open('xb')as f:f.write(path.read_bytes())
  value=read(path);value.setdefault('humanIdentityConfirmations',{})[a.code]=confirmation;value.setdefault('qualifiedSourceScopeAmendments',{})[a.code]=amendedref;atomic(path,value)
 public=e/'work/locality-progress-dashboard';model=read(public/'task-report.json');m=read(public/'boundary-map.json')
 for name in ['task-report.json','boundary-map.json']:
  with (out/('before-'+name)).open('xb')as f:f.write((public/name).read_bytes())
 evidence={'officialCode':a.code,'officialName':a.official_name,'ministryName':a.ministry_name,'humanConfirmedEquivalent':True,'literalMinistrySpellingMatch':False,'newPrimaryOfficialEquivalenceFound':False,'evidence':confirmation,'qualification':qualification}
 affected=0
 for row in [*m['locations'],*(r for b in m['batches']for r in b['locations'])]:
  if row['code']!=a.code:continue
  row.update(datedRosterMember=True,datedRosterMembershipSource='Explicit human spelling-equivalence confirmation',explicitIdentityQualification=evidence,catalogBindingQualification=qualification,humanIdentityConfirmation=confirmation);affected+=1
 m['generatedAtUtc']=now;model['generatedAtUtc']=now;model.setdefault('humanIdentityConfirmations',{})[a.code]=confirmation
 for ref in model['sourceFiles']:
  if ref.get('kind')=='pointer'and Path(ref['path']).resolve()in [runp.resolve(),hp.resolve(),cp.resolve()]:ref['sha256']=pin(Path(ref['path']))['sha256']
 sys.path.insert(0,str(public));import task_report_app as app
 oldcall=(app.build_task_report,app.build_boundary_map,app.refresh_frina_investigation,sys.argv)
 try:
  app.build_task_report=lambda:model;app.build_boundary_map=lambda unused:m;app.refresh_frina_investigation=lambda:None;sys.argv=[str(public/'task_report_app.py'),'refresh'];app.main()
 finally:app.build_task_report,app.build_boundary_map,app.refresh_frina_investigation,sys.argv=oldcall
 put(out/'publication-receipt.json',{'status':'HUMAN_IDENTITY_QUALIFICATION_PUBLISHED_NO_NEW_GEOGRAPHIC_CREDIT','confirmation':confirmation,'qualifiedSourceScope':amendedref,'affectedExistingMapRows':affected,'geometryCoordinatesUnchangedByConstruction':True,'acceptanceTimesAndSnapshotPinsUnchanged':True,'appAssetsChanged':False,'additionalGpsChecks':0,'additionalGeographicCredit':0,'report':pin(public/'task-report.json'),'map':pin(public/'boundary-map.json'),'html':pin(public/'dashboard.html'),'privateGoogleConfigRead':False,'actualBrowserRenderingTested':False})
 print(json.dumps({'confirmed':a.code,'humanStatement':a.statement,'affectedExistingMapRows':affected,'newGeographicCredit':0,'geometryChanged':False},ensure_ascii=False))
if __name__=='__main__':main()
