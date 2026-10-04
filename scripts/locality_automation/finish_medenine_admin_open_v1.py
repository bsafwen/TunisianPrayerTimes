"""Administrative close of batch24 and finite final-entry continuation25.

No boundary extraction, GPS replay, installation or publication runs here.
"""
import json,sys,os,ast
from datetime import datetime,timezone
from pathlib import Path
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin
from scripts.locality_automation.audit_reviewed_source_family import put
E=Path(r'C:\Users\barou\Documents\Codex\2026-09-06\the-android-app-app-currently-allows');C=E/'work/locality-efficiency-cycles-20261001';W=C/'root-cycle24';c=read(C/'control.json');now=datetime.now(timezone.utc)
if c['iteration']!=24 or c['phase']!='working':raise ValueError('Expected current Medenine24 controller')
# Bookkeeping review must inspect finished receipts after its own phase ended.
p=R/'scripts/locality_automation/audit_medenine_window24_v1.py';source=p.read_text(encoding='utf-8');start=source.index('# Preserve the first');end=source.index("for path in sorted(W.glob",start)
source=source[:start]+source[end:];source=source.replace('PUBLISHED_93_OF_94_AWAITING_FINAL_REGISTRATION_DECISION','PUBLISHED_93_OF_94_FINAL_EXCEPTION_AUTHORIZED').replace('Installation remains withheld while a human decision about a2m exception is pending.','The human has now explicitly authorized a2m exception for525363; final-entry continuation is isolated from the already installed48-case batch.').replace('qasr-final-decision-v1/decision.json','qasr-final-decision-v1/decision-v2.json').replace('qasr-final-decision-v1/REVIEW.md','qasr-final-decision-v1/REVIEW-v2.md')
exec(compile(source,str(p),'exec'),{'__name__':'__main__','__file__':str(p)})
metrics=read(W/'window24-evaluation-snapshot-v1.json');put(C/'window-024-final.json',{'status':'48_ENTRIES_PUBLISHED_FINAL_ENTRY_EXCEPTION_AUTHORIZED','closedAtUtc':now.isoformat(),'metrics':pin(W/'window24-evaluation-snapshot-v1.json'),'review':pin(W/'SELF_EVALUATION.md'),'familyComplete':False,'familyCompleteEntries':93,'familyOfficialEntries':94,'gains':metrics['gains'],'counts':metrics['finalCounts'],'nextOnlyCode':'525363','humanExceptionInstruction':'allow 2 meters'})
with (C/'window-024-review.md').open('x',encoding='utf-8')as f:f.write((W/'SELF_EVALUATION.md').read_text(encoding='utf-8'))
nextwork=C/'root-cycle25';nextwork.mkdir();put(nextwork/'before-control.json',c)
with (nextwork/'launch_root_phase.py').open('xb')as f:f.write((W/'launch_root_phase.py').read_bytes())
put(nextwork/'window-baseline.json',{'atUtc':now.isoformat(),'counts':metrics['finalCounts'],'officialScope':['525363'],'humanInstruction':'allow 2 meters','previousWindow24':pin(C/'window-024-final.json'),'originalSafetyDeadlineRetained':c['deadlineUtc']})
exception={'officialCode':'525363','humanInstructionVerbatim':'allow 2 meters','receivedDate':'2026-10-04','maximumHeldOutResidualM':2.0,'allOtherRegistrationLimitsPreserved':True,'appliesToNoOtherEntry':True,'nativeCoordinatesEdited':False,'registrationDecision':pin(W/'qasr-final-decision-v1/decision-v2.json'),'sourceReview':pin(W/'qasr-final-decision-v1/REVIEW-v2.md')}
ref=put(nextwork/'human-registration-exception.json',exception)
c.update(iteration=25,phase='working',windowStartUtc=now.isoformat(),startedBaseline=metrics['finalCounts'],latestRefreshedCounts=metrics['finalCounts'],currentAcceptedCycleCodes=[],currentAcceptedCycleFullCount=0,currentInstalledCycleCorrectionCodes=[],acceptanceScope='Only525363 القصر الجديد final Medenine entry; explicit human2m registration exception, all other gates retained.',approvedCycle25AcceptancePool=['525363'],pendingSourceCodes=['525363'],coordinatorCompanions=str(nextwork),intakeCompanions=str(nextwork),latestActors=str(nextwork/'current-actors-pids-dispatch-intake.json'),cutoffTimerStatus='UPDATE_REQUIRED',lastControlUpdateUtc=now.isoformat(),medeninComplete=False,medenineComplete=False,medenineCompleteLocationCount=93,userReviewAuthorization='Human explicitly said allow2meters for525363; finish Medenine using one-entry minimal check, no unrelated locations or agents.',dispatchPolicy='Only525363 guarded original boundary adoption; no other source locations.',reviewDecision='48-case batch24 installed and published; continue final case25 under human exception.',cycle25RegistrationException=ref)
c['finalCycle24Review']=pin(C/'window-024-review.md');c['finalCycle24Outcome']=pin(C/'window-024-final.json');c['cycle24Metrics']=pin(W/'window24-evaluation-snapshot-v1.json')
put(nextwork/'current-actors-pids-dispatch-intake.json',{'atUtc':now.isoformat(),'root':'/root','acceptanceOwner':'/root','mapOwner':'/root','subagentsAllowed':False,'activeValidationPids':[],'dispatchOnly':['525363'],'timerUpdateRequired':True,'exception':ref})
heading=f'''# Medenine final entry active —window025\n\nThe human approved a2m registration exception for525363 القصر الجديد only.93/94 Medenine entries are complete; the installed48-case batch24 added37 locations and upgraded11 complete bodies. Continue only525363 from root-cycle25, root sole writer, no subagents or unrelated tests. Preserve the exact original ISIE perimeter and every other gate. Same original safety deadline {c['deadlineUtc']}; update and verify the same timer before engine phases.\n\nRead root-cycle25/human-registration-exception.json, root-cycle24/qasr-final-decision-v1/REVIEW-v2.md and window-024-review.md. Proxy127.0.0.1:8888, no web__run/private-key reading/browser-preview bypass.\n\n'''
current=E/'CURRENT_WORKFLOW.md';current.write_text(heading+current.read_text(encoding='utf-8-sig'),encoding='utf-8');c['currentWorkflow']=pin(current)
tmp=C/'control.final-entry-open.tmp';tmp.write_text(json.dumps(c,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');os.replace(tmp,C/'control.json')
print(json.dumps({'iteration':25,'onlyCode':'525363','deadlineRetained':c['deadlineUtc'],'exceptionMaxM':2,'baseline':metrics['finalCounts']}))
