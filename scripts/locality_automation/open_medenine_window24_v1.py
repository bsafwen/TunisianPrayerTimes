"""Open root-only Medenine scope; administrative control, no validation credit."""
import json,shutil
from datetime import datetime,timezone,timedelta
from pathlib import Path
# Windows local timezone is Europe/Paris at this recorded instant.
R=Path(__file__).resolve().parents[2]
E=Path(r'C:\Users\barou\Documents\Codex\2026-09-06\the-android-app-app-currently-allows');C=E/'work/locality-efficiency-cycles-20261001';W=C/'root-cycle24'
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def put(p,v):p.write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8')
c=read(C/'control.json');assert c['phase']=='paused' and c['iteration']==23 and (not W.exists() or sorted(p.name for p in W.iterdir()) == ['before-control.json','launch_root_phase.py'])
W.mkdir(exist_ok=True);shutil.copyfile(C/'control.json',W/'before-control.json');shutil.copyfile(C/'root-cycle23/launch_root_phase.py',W/'launch_root_phase.py')
summary=read(E/'work/locality-progress-dashboard/task-report.json')['summary']
reg=read(R/'scripts/neighborhoods/touil-reviews/current-official-sector-registry.json')['sectors'];allcodes=[r['sectorCode'] for r in reg if r['governorateCode']=='52'];full=set(summary['explicitFullSourceBoundaryLocalityCodes']);pool=[v for v in allcodes if v not in full]
now=datetime.now(timezone.utc).replace(microsecond=0);end=now+timedelta(hours=2)
baseline={'geographic':summary['uniqueValidatedLocations'],'completeSourceBodies':summary['explicitFullSourceBoundaryLocationCount'],'installedCorrections':summary['uniqueInstalledCorrectionLocations']}
c.update(phase='working',iteration=24,workHours=2,windowStartUtc=now.isoformat(),deadlineUtc=end.isoformat(),deadlineParis=end.astimezone().isoformat(),startedBaseline=baseline,latestRefreshedCounts=baseline,acceptanceOwner='/root',acceptanceActor='/root',mapOwner='/root',mapActor='/root',subagentsAllowed=False,activeValidationPids=[],sourceQaWorkers=[],approvedCycle24AcceptancePool=pool,allMedenineOfficialCodes=allcodes,currentAcceptedCycleCodes=[],currentAcceptedCycleFullCount=0,pendingSourceCodes=pool,coordinatorCompanions=str(W),intakeCompanions=str(W),latestActors=str(W/'current-actors-pids-dispatch-intake.json'),acceptanceScope='Remaining Medenine only; no unrelated-location checks.',rootRole='Finish remaining Medenine source bodies with minimal target-only checks, root sole writer and no subagents.',reviewPolicy='Finish Medenine. At the two-hour safety checkpoint stop owned phases, preserve artifacts, evaluate actual gains and improvements, and pause for human instructions if incomplete.',safeSourceQaStartUtc=(end-timedelta(minutes=30)).isoformat(),safeIntakeStartUtc=(end-timedelta(minutes=20)).isoformat(),safeMapStartUtc=(end-timedelta(minutes=7)).isoformat(),mapMinimumRemainingSeconds=420,reviewDecision='User authorized finish Medenine; 45 complete entries reused, 49 remaining entries reviewed.',lastControlUpdateUtc=now.isoformat(),cutoffAutomationId='locality-work-and-efficiency-reviews',cutoffTimerStatus='PENDING_VERIFICATION')
put(W/'window-baseline.json',{'capturedAtUtc':now.isoformat(),'counts':baseline,'allMedenineOfficialCodes':allcodes,'alreadyCompleteMedenineCodes':[v for v in allcodes if v in full],'remainingMedenineCodes':pool,'unrelatedLocationsChecked':0})
put(W/'current-actors-pids-dispatch-intake.json',{'atUtc':now.isoformat(),'owner':'/root','workers':[],'ownedValidationPids':[],'dispatch':'Medenine remaining49 only; no subagents','phase':'window-open-awaiting-timer-verification'})
put(C/'control.json',c)
print(json.dumps({'work':str(W),'remaining':len(pool),'start':now.isoformat(),'deadline':c['deadlineUtc'],'deadlineParis':c['deadlineParis'],'baseline':baseline}))

