"""Finalize paused-family metadata using literal control UTC, never shell dates."""
import argparse,json,sys
from pathlib import Path
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation import work_window_guard as guard

def main():
 p=argparse.ArgumentParser(description=__doc__)
 for key in ('work','evidence','timer','process-observation','record'):p.add_argument('--'+key,type=Path,required=True)
 a=p.parse_args();w=a.work.resolve();c=read(w.parent/'control.json');assert Path.cwd().resolve()==w
 assert c['phase']=='paused'and c['familyGoalComplete']and not c['pendingSourceCodes']
 assert c['acceptanceOwner']=='/root'and c['mapOwner']=='/root'and c['mapMinimumRemainingSeconds']==420
 original={'file':str(Path(guard.__file__).resolve()),'sha256':'d2fe7a733097536d022bcb5e8ebd5b0688bf1bb6adcd955d659fce052a226ade'};checked(original)
 safe=c['safeMapStartUtc'];hard=c['deadlineUtc'];assert type(safe)is str and type(hard)is str
 guard.parse_utc(safe);guard.parse_utc(hard)
 program=R/'scripts/locality_automation/finalize_completed_family_timer_v8.py'
 args=[sys.executable,'-X','utf8','-B',str(program),'--work',str(w),'--evidence',str(a.evidence.resolve()),'--timer',str(a.timer.resolve()),'--process-observation',str(a.process_observation.resolve())]
 record=a.record.resolve();assert record.parent==w and not record.exists()
 put(record.with_suffix('.launch.json'),{'control':pin(w.parent/'control.json'),'safeStartUtc':safe,'hardDeadlineUtc':hard,'minimumRemainingSeconds':420,'program':pin(program),'argv':args,'cwd':str(w),'administrativeOnly':True,'validationResumed':False,'originalGuard':original})
 result=guard.launch_guarded(safe,hard,args,420,record);checked(original)
 print(json.dumps(result))
 return 0 if result['status']=='COMPLETED'and result['exitCode']==0 else 1

if __name__=='__main__':raise SystemExit(main())
