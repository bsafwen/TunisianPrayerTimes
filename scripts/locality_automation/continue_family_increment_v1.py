"""Advance a family increment within the same guarded work window and deadline."""
import argparse,json,shutil,sys
from pathlib import Path
from datetime import datetime,timezone
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--previous',type=Path,required=True);a=p.parse_args();old=a.previous.resolve();C=old.parent;c=read(C/'control.json');s={k:c[k]for k in ['iteration','windowStartUtc','deadlineUtc']};s.update(control=str(C/'control.json'),owner='/root');active_control(s)
q=read(old/(c['familySlug']+'-publication-verification.json'));receipt=read(old/(c['familySlug']+'-incremental-publication-receipt.json'))
if q['status']!='VERIFIED_LATEST_TARGET_BODIES_MAP_HIGHLIGHTS_AND_HOUR_GRAPH':raise ValueError('Prior publication not verified')
iteration=c['iteration']+1;W=C/('root-cycle'+str(iteration));W.mkdir();put(W/'before-control.json',c);shutil.copyfile(old/'launch_root_phase.py',W/'launch_root_phase.py')
baseline={'geographic':receipt['geographicCount'],'completeSourceBodies':receipt['completeBodyCount'],'installedCorrections':receipt['installedCorrectionCount']}
c.update(iteration=iteration,latestRefreshedCounts=baseline,currentAcceptedCycleCodes=[],currentAcceptedCycleFullCount=0,coordinatorCompanions=str(W),intakeCompanions=str(W),latestActors=str(W/'current-actors-pids-dispatch-intake.json'),lastControlUpdateUtc=datetime.now(timezone.utc).isoformat())
c['approvedCycle'+str(iteration)+'AcceptancePool']=c['pendingSourceCodes']
put(W/'window-baseline.json',{'counts':baseline,'overallStartedBaseline':c['startedBaseline'],'sameWindowStartUtc':c['windowStartUtc'],'sameDeadlineUtc':c['deadlineUtc'],'priorIncrement':pin(old/(c['familySlug']+'-incremental-publication-receipt.json'))})
put(W/'current-actors-pids-dispatch-intake.json',{'owner':'/root','workers':[],'ownedValidationPids':[],'phase':'remaining-family-increment','scope':c['pendingSourceCodes'],'sameTimerAndDeadline':True})
(C/'control.json').write_text(json.dumps(c,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'work':str(W),'remaining':c['pendingSourceCodes'],'baseline':baseline,'deadlineUnchanged':c['deadlineUtc']}))
