"""Resume only a paused pending family after a pinned direct human decision.

Administrative prepare/confirm; approval limits are recorded by the existing
approval writer. No new window, tests, source acceptance or geometry changes.
"""
import argparse,json,sys,tomllib
from datetime import datetime,timezone
from pathlib import Path
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.work_window_guard import parse_utc
def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('mode',choices=['prepare','confirm'])
    p.add_argument('--work',type=Path,required=True)
    p.add_argument('--instruction',type=Path,required=True)
    p.add_argument('--timer',type=Path)
    p.add_argument('--evidence',type=Path,required=True)
    a=p.parse_args();w=a.work.resolve();cp=w.parent/'control.json';c=read(cp)
    if Path.cwd().resolve()!=w or c['acceptanceOwner']!='/root' or c['acceptanceActor']!='/root' or c['subagentsAllowed']is not False:raise ValueError('Existing sole-root producer required')
    instruction=read(a.instruction);auth=instruction['authorization']
    codes=auth['officialCodes'];review=read(checked(instruction['review']))
    if auth['sourceRole']!='direct-user-instruction' or auth['action']!='entry-only-source-decisions' or not auth['userStatement'].strip() or not auth['questionContext'].strip():raise ValueError('Direct human mixed source decision required')
    if sorted(c['pendingSourceCodes'])!=sorted(codes) or len(set(codes))!=len(codes):raise ValueError('Exact pending pool differs')
    registration=read(checked(instruction['registrationInstruction']))
    precision=read(checked(instruction['precisionArtifactInstruction']))
    ra,pa=registration['authorization'],precision['authorization']
    for child in [ra,pa]:
        if any(child[k]!=auth[k]for k in ['sourceRole','userStatement','questionContext']):raise ValueError('Exact direct human authorization differs')
    if ra['action']!='entry-only-held-out-registration-limit'or pa['action']!='entry-only-grid-hairline-artifact'or set(ra['officialCodes'])&{pa['officialCode']}or sorted(ra['officialCodes']+[pa['officialCode']])!=sorted(codes):raise ValueError('Disjoint mixed pending decision pool differs')
    if registration['review']!=instruction['review']:raise ValueError('Pinned registration review differs')
    bycode={r['officialCode']:r for r in review['rows']}
    for code in ra['officialCodes']:
        held=[q for q in bycode[code]['parts']if q['registrationHeld']]
        if len(held)!=1 or held[0]['fitResidualM']>.5 or held[0]['heldOutMaxM']>ra['looLimitM']:raise ValueError('Instruction does not cover exact unchanged review')
        checked(held[0]['sourcePdf'])
    artifact=read(checked(precision['review']))
    if artifact['officialCode']!=pa['officialCode']or pa['maxSourceOutsideDecoded10cmM2']!=.01 or pa['maxDecodedOutsideSource10cmM2']!=0 or artifact['measuredSourceOutsideDecoded10cmM2']>.01 or artifact['measuredDecodedOutsideSource10cmM2']!=0:raise ValueError('Exact reviewed artifact decision differs')
    for key in ['sourcePdf','nativePageGeometry','rawSourceGeometry','geometry']:checked(artifact[key])
    now=datetime.now(timezone.utc)
    if now>=parse_utc(c['safeSourceQaStartUtc']):raise ValueError('Original safe start has passed; new human work window needed')
    if a.mode=='prepare':
        if c['phase']!='paused' or c['familyGoalComplete']:raise ValueError('Requires paused partial family')
        obs=read(checked(instruction['processObservation']))
        if obs['ownedValidationProcesses'] or (now-parse_utc(obs['atUtc'])).total_seconds()>120:raise ValueError('Fresh no-owned-process observation required')
        put(w/'before-direct-human-resume-control-v1.json',c)
        c.update(phase='working',resumedAtUtc=now.isoformat(),directHumanResume=pin(a.instruction.resolve()),cutoffTimerState='ACTIVATION_PENDING',cutoffAutomationState='ACTIVATION_PENDING',sourceIntakeNextAction='Wait for same timer verification; then finish only exact approved pending sources.',dispatchPolicy='ROOT_ONLY_APPROVED_FAMILY_REMAINDER')
    else:
        if c['phase']!='working' or c.get('directHumanResume')!=pin(a.instruction.resolve()) or not a.timer:raise ValueError('Requires prepared identical direct instruction and same timer')
        t=tomllib.loads(a.timer.read_text(encoding='utf-8'));local=datetime.fromisoformat(c['deadlineParis'])
        rule=f'RRULE:FREQ=DAILY;BYHOUR={local.hour};BYMINUTE={local.minute};BYSECOND={local.second};COUNT=1'
        if t['id']!=c['cutoffAutomationId'] or t['kind']!='heartbeat' or t['status']!='ACTIVE' or t['target_thread_id']!=instruction['threadId'] or t['rrule']!=rule:raise ValueError('Same active exact-deadline heartbeat required')
        ref=put(w/'active-timer-verification-after-human-v1.json',{'state':'ACTIVE_VERIFIED','timer':pin(a.timer),'deadlineUtc':c['deadlineUtc'],'sameTimer':True,'noDuplicate':True,'directInstruction':pin(a.instruction.resolve())})
        c.update(cutoffTimerState='ACTIVE_VERIFIED',cutoffTimerStatus='ACTIVE_VERIFIED',cutoffAutomationState='ACTIVE_VERIFIED',cutoffAutomationStatus='ACTIVE_VERIFIED',latestTimerVerification=ref,cutoffTimerVerification=ref,familyGoalComplete=False,goalIncomplete=True)
        actors=put(w/'current-actors-pids-dispatch-intake-approved-resume-v1.json',{'owner':'/root','workers':[],'ownedValidationPids':[],'phase':'approved-pending-source-remainder','scope':codes,'sameTimerAndDeadline':True,'processObservation':instruction['processObservation']})
        c.update(latestActors=actors['file'],latestActorsPidsDispatchIntake=actors)
        wf=a.evidence/'CURRENT_WORKFLOW.md'
        wf.write_text('# '+c['familySlug'].capitalize()+' approved remainder active\n\nDirect human approved the exact mixed registration/artifact decisions for '+', '.join(codes)+'. Record both pinned proofs with the existing approval writers. Complete only these pending entries. Original window and deadline '+c['deadlineUtc']+' unchanged; same ACTIVE timer verified. Root only, no agents or unrelated tests. All other source/hash/native/legal/neighbor/GPS/default fitting gates unchanged. On completion pause, review and wait. Host HTTP/HTTPS through127.0.0.1:8888; no web__run/private-key reads/preview bypass.\n\n'+wf.read_text(encoding='utf-8'),encoding='utf-8')
        c['currentWorkflow']=pin(wf)
    c['lastControlUpdateUtc']=now.isoformat()
    cp.write_text(json.dumps(c,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'mode':a.mode,'pending':codes,'deadlineUnchanged':c['deadlineUtc'],'sourceCredit':0}))
if __name__=='__main__':main()

