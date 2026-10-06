"""Pause a published partial family without turning its unresolved holds into completion.

Preparation runs as a guarded leaf. Finalization is administrative only after the
same app-managed heartbeat has been paused; it never invokes validation.
"""
import argparse
from datetime import datetime, timezone
import json
import re
from pathlib import Path
import sys
import tomllib

R = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read, pin, checked, active_control
from scripts.locality_automation.audit_reviewed_source_family import put


def utc(value):
    result = datetime.fromisoformat(value.replace('Z', '+00:00'))
    if result.utcoffset() is None:
        raise ValueError('Aware UTC required')
    return result.astimezone(timezone.utc)


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def refresh(public, control_path, model=None):
    model = read(public / 'task-report.json') if model is None else model
    map_model = read(public / 'boundary-map.json')
    for ref in model['sourceFiles']:
        if ref.get('kind') == 'pointer' and Path(ref['path']).resolve() == control_path.resolve():
            ref['sha256'] = pin(control_path)['sha256']
    sys.path.insert(0, str(public))
    import task_report_app as app
    original = app.build_task_report, app.build_boundary_map, app.refresh_frina_investigation, sys.argv
    try:
        app.build_task_report = lambda: model
        app.build_boundary_map = lambda unused: map_model
        app.refresh_frina_investigation = lambda: None
        sys.argv = [str(public / 'task_report_app.py'), 'refresh']
        app.main()
    finally:
        app.build_task_report, app.build_boundary_map, app.refresh_frina_investigation, sys.argv = original


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--notes', type=Path)
    parser.add_argument('--finalize-timer', type=Path)
    parser.add_argument('--administrative-suffix', default='')
    args = parser.parse_args()
    if not re.fullmatch(r'(?:-[a-z0-9]+)*', args.administrative_suffix):
        raise ValueError('Only a short safe successor suffix is allowed')
    work, evidence = args.work.resolve(), args.evidence.resolve()
    cp = work.parent / 'control.json'
    control = read(cp)
    public = evidence / 'work/locality-progress-dashboard'
    slug = control['familySlug']
    now = datetime.now(timezone.utc)
    final_path = work.parent / ('window-' + str(control['iteration']).zfill(3) + '-final.json')
    if args.finalize_timer:
        if control['phase'] != 'paused' or not control['pendingSourceCodes'] or control['familyGoalComplete']:
            raise ValueError('Requires paused, genuinely partial family')
        automation = tomllib.loads(args.finalize_timer.read_text(encoding='utf-8'))
        notes = read(checked(control['latestAppliedImprovements']))
        if (automation['id'] != control['cutoffAutomationId'] or automation['status'] != 'PAUSED'
                or automation['target_thread_id'] != notes['threadId']):
            raise ValueError('The same heartbeat must be paused')
        verification = put(work / ('paused-timer-verification' + args.administrative_suffix + '.json'), {
            'status': 'SAME_HEARTBEAT_PAUSED_VERIFIED', 'atUtc': now.isoformat(),
            'automation': pin(args.finalize_timer), 'id': automation['id'],
            'targetThreadId': automation['target_thread_id'], 'newTimerCreated': False,
            'newValidation': False, 'pendingCodes': control['pendingSourceCodes']})
        control.update(cutoffTimerState='PAUSED_VERIFIED', cutoffAutomationState='PAUSED_VERIFIED',
                       cutoffTimerStatus='PAUSED_VERIFIED', cutoffAutomationStatus='PAUSED_VERIFIED',
                       cutoffAutomationVerifiedAtUtc=now.isoformat(), latestTimerVerification=verification,
                       cutoffTimerVerification=pin(args.finalize_timer), lastControlUpdateUtc=now.isoformat())
        save(cp, control)
        final = read(final_path)
        final.update(control=pin(cp), timerVerificationRequired=False, timerState='PAUSED_VERIFIED',
                     timerVerification=verification)
        save(final_path, final)
        refresh(public, cp)
        put(work / ('final-administrative-publication' + args.administrative_suffix + '.json'), {
            'status': 'PARTIAL_FAMILY_PAUSED_WITH_PENDING_DECISIONS', 'control': pin(cp),
            'review': pin(work / 'SELF_EVALUATION.md'), 'timerVerification': verification,
            'pendingCodes': control['pendingSourceCodes'],
            'outputs': [pin(public / n) for n in ['task-report.json', 'boundary-map.json', 'dashboard.html']],
            'additionalGpsChecks': 0, 'additionalBoundaryCredit': 0})
        print(json.dumps({'phase': 'paused', 'timer': 'PAUSED_VERIFIED', 'pending': control['pendingSourceCodes']}))
        return

    spec = {k: control[k] for k in ['iteration', 'windowStartUtc', 'deadlineUtc']}
    spec.update(control=str(cp), owner='/root')
    active_control(spec)
    notes = read(args.notes)
    observation = read(checked(notes['processObservation']))
    if observation['ownedValidationProcesses'] or (now - utc(observation['atUtc'])).total_seconds() > 120:
        raise ValueError('Fresh observation of no prior owned validation processes required')
    publication_path = work / (slug + '-incremental-publication-receipt.json')
    publication = read(publication_path)
    verification_path = work / (slug + '-publication-verification.json')
    verification = read(verification_path)
    checked(verification['publication'])
    if verification['status'] != 'VERIFIED_LATEST_TARGET_BODIES_MAP_HIGHLIGHTS_AND_HOUR_GRAPH':
        raise ValueError('Existing exact publication verification required')
    replay = read(work / (slug + '-minimum-audit-v1/actual-jvm.json'))
    numeric = read(work / (slug + '-minimum-audit-v1/numeric-report.json'))
    if replay['failures'] or not replay['actualGeometryIndexExecuted'] or numeric['probeFailures']:
        raise ValueError('Existing target-only verification must pass')
    model = read(public / 'task-report.json')
    summary = model['summary']
    official = set(control['allFamilyOfficialCodes'])
    full = official & set(summary['explicitFullSourceBoundaryLocalityCodes'])
    pending = sorted(official - full)
    geographic = official & set(summary['validatedLocationCodes'])
    if pending != sorted(notes['pendingCodes']) or not pending or len(full) != verification['familyCompleteBodyCount']:
        raise ValueError('Exact unresolved family holds disagree with qualified publication')
    counts = {'geographic': summary['uniqueValidatedLocations'],
              'completeSourceBodies': summary['explicitFullSourceBoundaryLocationCount'],
              'installedCorrections': summary['uniqueInstalledCorrectionLocations']}
    if list(counts.values()) != [publication['geographicCount'], publication['completeBodyCount'], publication['installedCorrectionCount']]:
        raise ValueError('Live report counts disagree with last qualified publication')
    gains = {k: v - control['startedBaseline'][k] for k, v in counts.items()}
    events = []
    for path in work.glob('*.execution.json'):
        item = read(path)
        if item.get('startedAtUtc') and item.get('finishedAtUtc'):
            events.append({'receipt': pin(path), 'start': item['startedAtUtc'], 'end': item['finishedAtUtc'],
                           'seconds': (utc(item['finishedAtUtc']) - utc(item['startedAtUtc'])).total_seconds(),
                           'status': item.get('status'), 'exitCode': item.get('exitCode'),
                           'processId': item.get('processId'), 'cwd': item.get('cwd')})
    start = utc(control['windowStartUtc'])
    union = []
    for left, right in sorted((max(start, utc(x['start'])), min(now, utc(x['end']))) for x in events if utc(x['end']) > start):
        if right < left:
            raise ValueError('Reversed process span')
        if union and left <= union[-1][1]:
            union[-1] = (union[-1][0], max(right, union[-1][1]))
        else:
            union.append((left, right))
    wall = (now - start).total_seconds()
    active = sum((right - left).total_seconds() for left, right in union)
    applied = utc(read(checked(publication['practicalReceipt']))['appliedAtUtc'])
    metrics = {'status': 'PARTIAL_FAMILY_PAUSED_PENDING_EXACT_HUMAN_DECISIONS', 'closedAtUtc': now.isoformat(),
               'family': slug, 'familyCompleteEntries': len(full), 'familyGeographicEntries': len(geographic),
               'familyTotal': len(official), 'pendingCodes': pending, 'counts': counts,
               'baseline': control['startedBaseline'], 'gains': gains,
               'windowWallSeconds': wall, 'qualifiedAdditionsPerWallHour': gains['geographic'] * 3600 / wall,
               'startToInstallationSeconds': (applied - start).total_seconds(),
               'targetCoordinates': numeric['probeCount'], 'actualJvmCases': replay['cases'],
               'actualJvmFailures': 0, 'sampledWrongToCorrectGpsGains': numeric['sourceSupportedImprovements'],
               'unrelatedLocationBehavioralTests': 0, 'fullCatalogueReruns': 0, 'sourceOnlyOrToolingCredit': 0,
               'recordedLeafProcessCount': len(events), 'recordedLeafProcessSecondsSum': sum(x['seconds'] for x in events),
               'recordedLeafProcessIntervalUnionSeconds': active, 'wallOutsideRecordedLeafSpansSeconds': wall - active,
               'spanQualification': 'Recorded guarded leaf spans. Outside spans include review, code edits, conversation, planning and waits; neither idle nor busy is proved. The current administrative closer is not included.',
               'preservedFailedLeafAttempts': [x['receipt'] for x in events if x['exitCode'] not in [0, None]],
               'publication': pin(publication_path), 'verification': pin(verification_path), 'notes': pin(args.notes),
               'privateGoogleKeyRead': False, 'actualGoogleBrowserRenderingTested': False}
    metrics_ref = put(work / (slug + '-partial-window-metrics.json'), metrics)
    put(work / 'reviewed-window-real-process-spans.json', {'events': events, 'intervalUnion': [[x.isoformat(), y.isoformat()] for x, y in union]})
    text = '\n'.join([
        '# ' + slug.capitalize() + ': partial completion, paused for exact source decisions', '',
        f'{len(full)}/{len(official)} complete boundaries; {len(geographic)}/{len(official)} geographic validations. This window added {gains["geographic"]} geographic validations, {gains["completeSourceBodies"]} complete bodies and {gains["installedCorrections"]} unique installed corrections. Global counts: {counts}.', '',
        f'The qualified batch was installed after {(applied-start).total_seconds()/60:.2f} minutes. Window through review: {wall/60:.2f} minutes; {metrics["qualifiedAdditionsPerWallHour"]:.2f} new locations per elapsed hour. Direct leaf span sum {metrics["recordedLeafProcessSecondsSum"]:.3f}s; interval union {active:.3f}s. Outside-span wall time is unclassified.', '',
        f'{numeric["probeCount"]} target coordinates, {replay["cases"]} actual current JVM cases, zero failures; no unrelated behavioral tests. The minimal interior cohort showed {numeric["sourceSupportedImprovements"]} wrong-to-correct GPS gains. Boundary adoption is not a claim that each sampled point previously failed.', '',
        'Applied reusable improvements:', '', *['- ' + x for x in notes['appliedImprovements']], '',
        'Avoidable errors and applied corrections:', '', *['- ' + x for x in notes['errorsAndCorrections']], '',
        'Limits and pending decisions:', '', *['- ' + x for x in notes['limits']], '',
        'Validation is paused. The same timer will be paused and verified separately; no automatic resume while the entry-specific decisions are pending. Overall Tunisia and this family remain incomplete.', ''])
    (work / 'SELF_EVALUATION.md').write_text(text, encoding='utf-8')
    (work.parent / ('window-' + str(control['iteration']).zfill(3) + '-review.md')).write_text(text, encoding='utf-8')
    put(work / 'before-partial-pause-control.json', control)
    companion = put(work / 'current-actors-pids-dispatch-intake-final.json', {
        'atUtc': now.isoformat(), 'owner': '/root', 'workers': [], 'activeOwnedValidationPids': [],
        'processObservation': notes['processObservation'], 'phase': 'paused-pending-source-decisions',
        'pendingCodes': pending, 'noAutomaticResume': True})
    control.update(phase='paused', pausedAtUtc=now.isoformat(), latestRefreshedCounts=counts,
                   currentAcceptedCycleCodes=publication['locationCodes'], currentAcceptedCycleFullCount=len(publication['locationCodes']),
                   currentWindowAcceptedFamilyCodes=sorted(full), currentWindowCompleteBodyCount=len(full),
                   pendingSourceCodes=pending, heldCodes=pending, sourceQaWorkers=[], activeValidationPids=[],
                   familyGoalComplete=False, goalIncomplete=True, latestReviewMetrics=metrics_ref,
                   latestAppliedImprovements=pin(args.notes), latestActorsPidsDispatchIntake=companion,
                   latestActors=companion['file'], latestBoundaryPracticalReceipt=publication['practicalReceipt'],
                   latestInstalledPracticalReceipt=publication['practicalReceipt'],
                   reviewDecision=f'{len(full)}/{len(official)} complete; {len(pending)} exact source-gate decisions pending. PAUSED and WAIT.',
                   sourceIntakeNextAction='WAIT for exact human decisions; no additional source or GPS work.',
                   dispatchPolicy='PAUSED_PENDING_HUMAN_SOURCE_DECISIONS', lastControlUpdateUtc=now.isoformat())
    workflow = evidence / 'CURRENT_WORKFLOW.md'
    workflow.write_text('# ' + slug.capitalize() + ' partial completion — PAUSED pending exact decisions\n\n'
                        + text + '\nDecision evidence: ' + notes['decisionReview'] + '.\n\n'
                        + workflow.read_text(encoding='utf-8'), encoding='utf-8')
    control['currentWorkflow'] = pin(workflow)
    save(cp, control)
    model.setdefault('familyProgress', {})[slug] = {
        'officialEntries': len(official), 'geographicAcceptedEntries': len(geographic),
        'completeAcceptedEntries': len(full), 'pendingCodes': pending, 'phase': 'paused-pending-decisions',
        'reviewViewId': read(public / 'boundary-map.json')['preferredViewId'],
        'currentWorkNewLocations': publication['newLocationCount'],
        'currentWorkFullUpgrades': len(publication['fullScopeUpgradeCodes'])}
    refresh(public, cp, model)
    put(final_path, {'status': metrics['status'], 'metrics': metrics_ref, 'review': pin(work / 'SELF_EVALUATION.md'),
                     'control': pin(cp), 'workflow': pin(workflow), 'counts': counts, 'gains': gains,
                     'pendingCodes': pending, 'timerVerificationRequired': True, 'familyComplete': False,
                     'activeWorkers': [], 'activeOwnedValidationPids': []})
    print(json.dumps({'phase': 'paused', 'familyComplete': len(full), 'pendingCodes': pending, 'gains': gains}))


if __name__ == '__main__':
    main()
