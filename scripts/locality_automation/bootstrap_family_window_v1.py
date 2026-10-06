"""Verify the existing review timer and reset a finite family's carried control."""
import argparse,json,sys,tomllib
from datetime import datetime,timezone
from pathlib import Path
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--family',required=True);p.add_argument('--governorate-code',required=True);p.add_argument('--thread-id',required=True);p.add_argument('--timer',type=Path,required=True);p.add_argument('--output-prefix',required=True);a=p.parse_args()
W=Path.cwd();cp=W.parent/'control.json';c=read(cp);s={k:c[k]for k in ('iteration','windowStartUtc','deadlineUtc')};s.update(control=str(cp),owner='/root');active_control(s)
assert c['familySlug']==a.family and c['governorateCode']==a.governorate_code and c['acceptanceOwner']=='/root' and not c['subagentsAllowed']
timer=a.timer.resolve(strict=True);t=tomllib.loads(timer.read_text(encoding='utf-8'))
d=datetime.fromisoformat(c['deadlineParis']);expected=f'RRULE:FREQ=DAILY;BYHOUR={d.hour};BYMINUTE={d.minute};BYSECOND={d.second};COUNT=1'
assert t['id']==c['cutoffAutomationId']=='locality-work-cutoff-review' and t['status']=='ACTIVE' and t['target_thread_id']==a.thread_id and t['rrule']==expected
b=read(W/'window-baseline.json');put(W/'before-baseline-context-v1.json',b)
pool=c['approvedCycle'+str(c['iteration'])+'AcceptancePool'];assert set(pool)==set(b['remainingFamilyCodes']) and not(set(pool)&set(b['alreadyCompleteFamilyCodes']))
b.update(correctionExistingCompleteCodes=[],retainedCompletedCodes=b['alreadyCompleteFamilyCodes'])
(W/'window-baseline.json').write_text(json.dumps(b,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
verification=put(W/'active-review-timer-verification-v1.json',{'status':'ACTIVE_VERIFIED_EXACT_SAME_HEARTBEAT','automation':pin(timer),'targetThreadId':a.thread_id,'deadlineUtc':c['deadlineUtc'],'deadlineParis':c['deadlineParis'],'newTimerCreated':False,'atUtc':datetime.now(timezone.utc).isoformat(),'credit':0})
c.update(familyGoalComplete=False,goalIncomplete=True,heldCodes=[],currentCorrectionExistingCompleteCodes=[],currentWindowExistingBodyCorrectionCodes=[],mapPendingCodes=[],cutoffTimerState='ACTIVE_VERIFIED',cutoffTimerStatus='ACTIVE_VERIFIED',cutoffAutomationState='ACTIVE_VERIFIED',cutoffAutomationStatus='ACTIVE_VERIFIED',cutoffTimerVerification=pin(timer),latestTimerVerification=verification,currentWindowCompleteBodyGain=0,sourceIntakeNextAction=f'WORK: finish only {len(pool)} remaining {a.family} bodies, retain {len(b["alreadyCompleteFamilyCodes"])} accepted full bodies.',authorizedFamilyQueuePolicy='Direct human requested another governorate. Root selected '+a.family+'; no other governorate in this finite window.',reviewDecision='Use current incomplete registry and preserve all accepted family bodies.',lastControlUpdateUtc=datetime.now(timezone.utc).isoformat())
cp.write_text(json.dumps(c,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
reg=read(R/'scripts/neighborhoods/touil-reviews/current-official-sector-registry.json')['sectors']
targets=[{'code':q['sectorCode'],'name':q['sectorAr'],'parent':q['delegationAr']}for q in reg if q['sectorCode']in pool]
put(W/(a.output_prefix+'-finite-targets-v1.json'),{'targets':targets,'baseline':b,'timer':verification,'credit':0})
print(json.dumps({'targets':targets,'retained':len(b['alreadyCompleteFamilyCodes']),'timer':'ACTIVE_VERIFIED','credit':0},ensure_ascii=False))

