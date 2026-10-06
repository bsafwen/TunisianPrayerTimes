"""Open an explicitly human-requested family after an expired paused window.

This guard is administrative opening only; no source or acceptance phase runs
before the new window's same heartbeat is verified. Original guard unchanged.
"""
import argparse,json,sys,tomllib
from datetime import datetime,timezone,timedelta
from pathlib import Path
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.family_work_v3 import validate_identity
from scripts.locality_automation import work_window_guard as guard
def main():
 p=argparse.ArgumentParser(description=__doc__)
 for key in ('family','governorate-code','governorate-name','human-instruction'):p.add_argument('--'+key,required=True)
 for key in ('evidence','timer','process-observation','record'):p.add_argument('--'+key,type=Path,required=True)
 a=p.parse_args();w=Path.cwd();cp=w.parent/'control.json';c=read(cp)
 assert w.name=='root-cycle'+str(c['iteration']) and c['phase']=='paused' and c['familyGoalComplete']
 assert c['acceptanceOwner']==c['mapOwner']=='/root' and not c['subagentsAllowed']
 ob=read(a.process_observation);assert ob['activeOwnedProcessCount']==ob.get('unknownIdentityCount',0)==0
 t=tomllib.loads(a.timer.read_text(encoding='utf-8'));assert t['id']==c['cutoffAutomationId']=='locality-work-cutoff-review' and t['status']=='PAUSED'
 rows=validate_identity(a.governorate_code,a.governorate_name,read(R/'scripts/neighborhoods/touil-reviews/current-official-sector-registry.json'))
 assert rows and a.family!=c['familySlug'] and not(w.parent/('root-cycle'+str(c['iteration']+1))).exists()
 record=a.record.resolve();assert record.parent==w and not record.exists()
 original={'file':str(Path(guard.__file__).resolve()),'sha256':'d2fe7a733097536d022bcb5e8ebd5b0688bf1bb6adcd955d659fce052a226ade'};checked(original)
 put(w/(a.family+'-before-new-human-open-control-v1.json'),c)
 now=datetime.now(timezone.utc);c['newHumanFamilyOpening']={'humanInstruction':a.human_instruction,'family':a.family,'governorateCode':a.governorate_code,'governorateName':a.governorate_name,'authorizedAtUtc':now.isoformat(),'safeStartUtc':(now+timedelta(minutes=10)).isoformat(),'hardDeadlineUtc':(now+timedelta(minutes=20)).isoformat(),'minimumRemainingSeconds':120,'scope':'Administrative opener only; source, intake and map use the new actual window fields after same timer verification.','processObservation':pin(a.process_observation.resolve())}
 cp.write_text(json.dumps(c,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
 opening=read(cp)['newHumanFamilyOpening'];safe=opening['safeStartUtc'];hard=opening['hardDeadlineUtc'];guard.parse_utc(safe);guard.parse_utc(hard)
 program=R/'scripts/locality_automation/family_work_v3.py'
 argv=[sys.executable,'-X','utf8','-B',str(program),'open','--family',a.family,'--evidence',str(a.evidence.resolve()),'--governorate-code',a.governorate_code,'--governorate-name',a.governorate_name]
 put(record.with_suffix('.launch.json'),{'control':pin(cp),'newHumanOpening':opening,'program':pin(program),'argv':argv,'cwd':str(w),'originalGuard':original,'administrativeOnly':True,'validationBeforeTimerVerification':False})
 result=guard.launch_guarded(safe,hard,argv,120,record);checked(original);print(json.dumps(result))
 return 0 if result['status']=='COMPLETED' and result['exitCode']==0 else 1
if __name__=='__main__':raise SystemExit(main())

