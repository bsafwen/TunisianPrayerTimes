"""Verify the same active heartbeat and clear inherited completed-family state.

Administrative only; launch guards retain literal aware UTC source fields.
"""
import argparse
from datetime import datetime, timezone
from pathlib import Path
import json
import sys
import tomllib
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import read, pin
from scripts.locality_automation.audit_reviewed_source_family import put

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--work', type=Path, required=True)
    p.add_argument('--evidence', type=Path, required=True)
    p.add_argument('--timer', type=Path, required=True)
    p.add_argument('--thread-id', required=True)
    a=p.parse_args(); w=a.work.resolve(); e=a.evidence.resolve(); cp=w.parent/'control.json'; c=read(cp)
    baseline=read(w/'window-baseline.json')
    assert c['phase']=='working' and baseline['family']==c['familySlug']
    assert sorted(baseline['remainingFamilyCodes'])==sorted(c['pendingSourceCodes'])
    t=tomllib.loads(a.timer.read_text(encoding='utf-8'))
    local=datetime.fromisoformat(c['deadlineParis'])
    rule=f'RRULE:FREQ=DAILY;BYHOUR={local.hour};BYMINUTE={local.minute};BYSECOND={local.second};COUNT=1'
    assert t['id']==c['cutoffAutomationId'] and t['kind']=='heartbeat' and t['status']=='ACTIVE'
    assert t['rrule']==rule and t['target_thread_id']==a.thread_id
    ref=put(w/'active-timer-verification-v1.json',{'state':'ACTIVE_VERIFIED','timer':pin(a.timer),
        'deadlineUtc':c['deadlineUtc'],'sameTimer':True,'noDuplicate':True})
    c.update(cutoffTimerState='ACTIVE_VERIFIED',cutoffTimerStatus='ACTIVE_VERIFIED',
        cutoffAutomationState='ACTIVE_VERIFIED',cutoffAutomationStatus='ACTIVE_VERIFIED',
        cutoffTimerVerification=ref,latestTimerVerification=ref,familyGoalComplete=False,goalIncomplete=True,
        heldCodes=[],currentWindowAcceptedFamilyCodes=[],currentWindowCompleteBodyCount=0,
        latestActorsPidsDispatchIntake=pin(w/'current-actors-pids-dispatch-intake.json'),
        reviewDecision='Finish only the newly authorized '+c['familySlug']+' scope.',
        sourceIntakeNextAction='Reuse original cached sources; fetch only missing exact indexed PDFs through the proxy.',
        authorizedFamilyQueue=[{'family':c['familySlug'],'governorateCode':c['governorateCode']}],
        authorizedFamilyQueuePolicy='Complete only the newly authorized finite family, then pause and wait.',
        lastControlUpdateUtc=datetime.now(timezone.utc).isoformat())
    wf=e/'CURRENT_WORKFLOW.md'
    wf.write_text('# '+c['familySlug'].capitalize()+' active — window'+str(c['iteration'])+'\n\n'
        +'Human authorization: finish '+c['familySlug']+'. '+str(len(baseline['allFamilyOfficialCodes']))
        +' official entries; '+str(len(baseline['alreadyCompleteFamilyCodes']))+' complete bodies reused; '
        +str(len(baseline['remainingFamilyCodes']))+' remaining. Root only, no agents. '
        +'Every source/geometry/legal/neighbor gate and exact entry/PDF exception remains pinned. '
        +'Default fit 0.5 m and held-out 1.5 m; previous other-entry exceptions do not transfer. '
        +'Every engine phase direct original guard with literal aware UTC strings, fresh receipt and existing cwd; '
        +'source/intake minimum 120 s, map 420 s. Exact same timer ACTIVE verified for '+c['deadlineParis']+'. '
        +'Minimum changed-location checks, no unrelated behavior tests. For actual ambiguities compare adjoining '
        +'original ISIE maps and use connected-emulator Google Maps satellite. On completion pause, self-evaluate and wait. '
        +'All host HTTP/HTTPS through 127.0.0.1:8888; never web__run/private key reads/browser-preview bypass.\n\n'
        +wf.read_text(encoding='utf-8'),encoding='utf-8')
    c['currentWorkflow']=pin(wf)
    cp.write_text(json.dumps(c,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'family':c['familySlug'],'total':len(baseline['allFamilyOfficialCodes']),
        'reused':len(baseline['alreadyCompleteFamilyCodes']),'remaining':len(c['pendingSourceCodes']),
        'timer':'ACTIVE_VERIFIED','deadlineParis':c['deadlineParis']}))

if __name__=='__main__':main()
