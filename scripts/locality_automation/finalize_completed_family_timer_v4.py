"""Finalize a paused family with exact prior bodies plus newly installed bodies.

Administrative metadata only. Preserve the v2 all-new-family finalizer.
"""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sys
import tomllib

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import read, pin, checked
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.pause_reviewed_family_holds_v1 import refresh, save


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--timer', type=Path, required=True)
    args = parser.parse_args()
    work, evidence = args.work.resolve(), args.evidence.resolve()
    cp = work.parent / 'control.json'
    control = read(cp)
    slug = control['familySlug']
    notes = read(checked(control['latestAppliedImprovements']))
    metrics = read(checked(control['latestCompletionMetrics']))
    public = evidence / 'work/locality-progress-dashboard'
    model = read(public / 'task-report.json')
    progress = model['completedFamilies'][slug]
    codes = set(control['allFamilyOfficialCodes'])
    if (control['phase'] != 'paused' or control['pendingSourceCodes']
            or progress['pendingCodes'] or progress['completeAcceptedEntries'] != len(codes)
            or metrics['family'] != slug or metrics['familyCompleteEntries'] != len(codes)
            or metrics['actualJvmFailures'] != 0):
        raise ValueError('Requires a completed and paused finite family')
    publication = read(checked(metrics['publications'][-1]))
    practical_ref = control['latestBoundaryPracticalReceipt']
    if publication['practicalReceipt'] != practical_ref:
        raise ValueError('Completion must use the actual pinned final installation')
    practical = read(checked(practical_ref))
    pool = set(control['approvedCycle' + str(control['iteration']) + 'AcceptancePool'])
    before_path = work / ('before-' + slug + '-publication-task-report.json')
    before = read(before_path)
    prior = set(before['summary']['explicitFullSourceBoundaryLocalityCodes']) & codes
    published = set(model['summary']['explicitFullSourceBoundaryLocalityCodes']) & codes
    if (set(practical['boundaryLocalityCodes']) != pool
            or set(practical['fullSourceBoundaryLocalityCodes']) != pool
            or pool & prior or pool | prior != codes or published != codes
            or metrics['gains']['geographic'] != len(pool - set(before['summary']['validatedLocationCodes']))
            or set(publication['newLocationCodes']) != pool - set(before['summary']['validatedLocationCodes'])
            or set(publication['fullScopeUpgradeCodes']) != pool & set(before['summary']['validatedLocationCodes'])
            or set(publication['locationCodes']) != pool
            or metrics['gains']['completeSourceBodies'] != len(pool)):
        raise ValueError('Exact prior complete bodies and exact new installation must cover the family')
    timer = tomllib.loads(args.timer.read_text(encoding='utf-8'))
    if (timer['id'] != control['cutoffAutomationId'] or timer['kind'] != 'heartbeat'
            or timer['status'] != 'PAUSED' or timer['target_thread_id'] != notes['threadId']):
        raise ValueError('Actual same heartbeat must be paused for this thread')
    observation_path = work / notes['cutoffProcessObservation']
    observation = json.loads(observation_path.read_text(encoding='utf-8-sig'))
    if observation['activeOwnedProcessCount'] or observation['activeOwnedProcesses']:
        raise ValueError('Exact owned process observation must be empty')
    review_paths = [work / 'SELF_EVALUATION.md', work.parent / ('window-' + str(control['iteration']).zfill(3) + '-review.md')]
    old = 'The same timer must be PAUSED and verified before final handoff. No new family or automatic resume.'
    new = 'The same heartbeat is PAUSED and verified. No active owned validation processes; no new family or automatic resume.'
    review_texts = [p.read_text(encoding='utf-8') for p in review_paths]
    if any(t.count(old) != 1 for t in review_texts):
        raise ValueError('Expected exact unfinalized timer note')
    final_path = work.parent / ('window-' + str(control['iteration']).zfill(3) + '-final.json')
    final = read(final_path)
    now = datetime.now(timezone.utc).isoformat()
    verification = put(work / 'paused-timer-verification-v1.json', {
        'state': 'PAUSED_VERIFIED', 'verifiedAtUtc': now, 'automationId': timer['id'],
        'targetThreadId': timer['target_thread_id'], 'timerFile': pin(args.timer),
        'processObservation': pin(observation_path), 'completedFamily': slug,
        'priorPublishedCompleteBodies': pin(before_path), 'reusedFamilyCodes': sorted(prior),
        'newlyInstalledFamilyCodes': sorted(pool), 'completeFamilyCodes': sorted(codes),
        'newTimerCreated': False, 'additionalValidationCredit': 0,
        'additionalGpsChecks': 0, 'privateGoogleKeyRead': False})
    workflow = evidence / 'CURRENT_WORKFLOW.md'
    workflow.write_text('# Completed family timer verified — PAUSED and WAIT\n\n'
        + slug.capitalize() + ' ' + str(len(codes)) + '/' + str(len(codes))
        + ' complete. Same heartbeat PAUSED and verified for this thread; no live owned validation processes. '
        + 'Do not start a new family or automatically resume. The overall Tunisia objective remains incomplete.\n\n'
        + workflow.read_text(encoding='utf-8'), encoding='utf-8')
    control.update(cutoffAutomationState='PAUSED_VERIFIED', cutoffAutomationVerifiedAtUtc=now,
        latestTimerVerification=verification, currentWorkflow=pin(workflow), lastControlUpdateUtc=now,
        currentAcceptedCycleCodes=sorted(codes), currentAcceptedCycleFullCount=len(codes),
        currentWindowAcceptedFamilyCodes=sorted(pool), currentWindowCompleteBodyCount=len(pool),
        reusedCompleteFamilyCodes=sorted(prior), heldCodes=[], familyGoalComplete=True, goalIncomplete=True,
        activeValidationPids=[], sourceQaWorkers=[], mapPendingCodes=[],
        acceptanceScope='Completed ' + slug + '; PAUSED and WAIT. No new validation or geometry work.',
        authorizedFamilyQueuePolicy='Requested finite family completed; wait for new human instructions.')
    save(cp, control)
    for path, value in zip(review_paths, review_texts):
        path.write_text(value.replace(old, new), encoding='utf-8')
    final.update(control=pin(cp), workflow=pin(workflow), review=pin(work / 'SELF_EVALUATION.md'),
        timerVerificationRequired=False, timerState='PAUSED_VERIFIED', timerVerification=verification)
    save(final_path, final)
    refresh(public, cp)
    put(work / 'final-administrative-publication.json', {
        'status': 'COMPLETED_FAMILY_AND_SAME_PAUSED_TIMER_VERIFIED', 'control': pin(cp),
        'workflow': pin(workflow), 'report': pin(public / 'task-report.json'),
        'map': pin(public / 'boundary-map.json'), 'html': pin(public / 'dashboard.html'),
        'timerVerification': verification, 'processObservation': pin(observation_path),
        'helper': pin(Path(__file__)), 'additionalGpsChecks': 0,
        'additionalGeographicCredit': 0, 'privateGoogleKeyRead': False})
    print(json.dumps({'family': slug, 'complete': len(codes), 'new': len(pool),
                     'reused': len(prior), 'phase': 'paused', 'timer': 'PAUSED_VERIFIED'}))


if __name__ == '__main__':
    main()
