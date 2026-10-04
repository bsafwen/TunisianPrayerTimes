"""Administrative finalization only after validation has paused; no new checks."""
import argparse,json,sys
from pathlib import Path
from datetime import datetime,timezone
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin
from scripts.locality_automation.audit_reviewed_source_family import put

def main():
 p=argparse.ArgumentParser();p.add_argument('--work',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True);a=p.parse_args();w=a.work.resolve();e=a.evidence.resolve();cp=w.parent/'control.json';c=read(cp);verification=read(w/'timer-absence-verification.json')
 if c['phase']!='paused'or c['pendingSourceCodes']or verification['state']!='ABSENT_CONFIRMED'or verification['automationTomlFiles']:raise ValueError('Requires completed paused family and verified absent timer')
 now=datetime.now(timezone.utc).isoformat();wf=e/'CURRENT_WORKFLOW.md';wf.write_text('# Completion timer verification\n\nThe existing locality-work-and-efficiency-reviews automation no longer exists: automation_update reported it absent and the automations directory contains no automation.toml files. No replacement timer was created. Validation remains PAUSED and WAIT, with no active workers; do not automatically resume. Read root-cycle28/timer-absence-verification.json.\n\n'+wf.read_text(encoding='utf-8'),encoding='utf-8')
 c.update(cutoffAutomationState='ABSENT_CONFIRMED',cutoffAutomationVerifiedAtUtc=now,latestTimerVerification=pin(w/'timer-absence-verification.json'),currentWorkflow=pin(wf),lastControlUpdateUtc=now,currentAcceptedCycleCodes=c['approvedCycle'+str(c['iteration'])+'AcceptancePool'],currentAcceptedCycleFullCount=len(c['approvedCycle'+str(c['iteration'])+'AcceptancePool']),currentWindowAcceptedFamilyCodes=c['allFamilyOfficialCodes'],currentWindowCompleteBodyCount=len(c['allFamilyOfficialCodes']),familyGoalComplete=True,acceptanceScope='Completed Gafsa; PAUSED and WAIT. No new validation or geometry work.')
 c['lastVerifiedImprovement']={'name':'Minimal target-only Gafsa pipeline, explicit receipt-bound publisher, byte-preserving one-row display override and correct increment timelines','verification':pin(w/'gafsa-publication-verification.json'),'nameDelta':pin(w/'display-name-615754/publication-receipt.json'),'scopeTimelines':pin(w/'report-increment-timing-review.json'),'applied':True};cp.write_text(json.dumps(c,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
 old='The same timer must be PAUSED and verified before final handoff. No new family or automatic resume.';new='Timer verification: the app reports the existing automation no longer exists, and no automation.toml files remain. No replacement timer was created. Validation is PAUSED; no new family or automatic resume.'
 for path in [w/'SELF_EVALUATION.md',w.parent/('window-'+str(c['iteration']).zfill(3)+'-review.md')]:
  value=path.read_text(encoding='utf-8')
  if old not in value:raise ValueError('Exact administrative timer-note anchor differs')
  path.write_text(value.replace(old,new),encoding='utf-8')
 finalp=w.parent/('window-'+str(c['iteration']).zfill(3)+'-final.json');final=read(finalp);final.update(control=pin(cp),workflow=pin(wf),review=pin(w/'SELF_EVALUATION.md'),timerVerificationRequired=False,timerState='ABSENT_CONFIRMED',timerVerification=pin(w/'timer-absence-verification.json'));finalp.write_text(json.dumps(final,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
 public=e/'work/locality-progress-dashboard';model=read(public/'task-report.json');m=read(public/'boundary-map.json')
 for ref in model['sourceFiles']:
  if ref.get('kind')=='pointer'and Path(ref['path']).resolve()==cp.resolve():ref['sha256']=pin(cp)['sha256']
 sys.path.insert(0,str(public));import task_report_app as app
 original=(app.build_task_report,app.build_boundary_map,app.refresh_frina_investigation,sys.argv)
 try:app.build_task_report=lambda:model;app.build_boundary_map=lambda unused:m;app.refresh_frina_investigation=lambda:None;sys.argv=[str(public/'task_report_app.py'),'refresh'];app.main()
 finally:app.build_task_report,app.build_boundary_map,app.refresh_frina_investigation,sys.argv=original
 put(w/'final-administrative-publication.json',{'status':'PAUSED_COMPLETED_FAMILY_AND_ABSENT_TIMER_VERIFIED','control':pin(cp),'workflow':pin(wf),'report':pin(public/'task-report.json'),'map':pin(public/'boundary-map.json'),'html':pin(public/'dashboard.html'),'timerVerification':pin(w/'timer-absence-verification.json'),'additionalGpsChecks':0,'additionalGeographicCredit':0,'privateGoogleKeyRead':False});print(json.dumps({'phase':c['phase'],'timer':'ABSENT_CONFIRMED','replacementCreated':False,'newValidation':False}))
if __name__=='__main__':main()
