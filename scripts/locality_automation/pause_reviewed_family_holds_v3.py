"""Pause a partial family and aggregate existing proofs from its disjoint increments.

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
    parser.add_argument('--published-work', type=Path)
    parser.add_argument('--completed-work', type=Path, action='append', default=[])
    parser.add_argument('--attempt-work', type=Path, action='append', default=[])
    parser.add_argument('--verification', type=Path)
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
    published = args.published_work.resolve() if args.published_work else work
    completed = [p.resolve() for p in args.completed_work] or [published]
    if len(set(completed)) != len(completed) or published not in completed:
        raise ValueError('Unique actual completed increments including last publication required')
    for directory in [work, published, *completed]:
        if not directory.is_dir() or directory.parent != work.parent:
            raise ValueError('Existing sibling producer directories required')
    publication_path = published / (slug + '-incremental-publication-receipt.json')
    publication = read(publication_path)
    verification_path = args.verification.resolve() if args.verification else published / (slug + '-publication-verification.json')
    verification = read(verification_path)
    if checked(verification['publication']).resolve() != publication_path.resolve():
        raise ValueError('Fresh qualified verification must bind latest actual publication')
    if verification['status'] != 'VERIFIED_LATEST_TARGET_BODIES_MAP_HIGHLIGHTS_AND_HOUR_GRAPH':
        raise ValueError('Existing exact publication verification required')
    replay = {'failures': [], 'actualGeometryIndexExecuted': True, 'cases': 0}
    numeric = {'probeFailures': [], 'probeCount': 0, 'sourceSupportedImprovements': 0}
    completed_proofs, accepted_codes, new_codes, upgrade_codes, applied_times = [], set(), set(), set(), []
    correction_codes=set()
    for directory in completed:
        pub_path = directory / (slug + '-incremental-publication-receipt.json')
        pub = read(pub_path)
        practical = read(checked(pub['practicalReceipt']))
        jvm_path = directory / (slug + '-minimum-audit-v1/actual-jvm.json')
        numeric_path = directory / (slug + '-minimum-audit-v1/numeric-report.json')
        child_replay, child_numeric = read(jvm_path), read(numeric_path)
        binding=directory / (slug + '-ready-v1-before-control.json')
        if not binding.exists():binding=directory / 'before-control.json'
        before = read(binding)
        for key in ['familySlug', 'startedBaseline']:
            if before[key] != control[key]:
                raise ValueError('Completed increment must belong to this exact authorized window')
        for key in ['windowStartUtc', 'deadlineUtc']:
            if utc(before[key]) != utc(control[key]):
                raise ValueError('Completed increment must bind the same aware UTC instants')
        codes = set(pub['locationCodes'])
        if not codes or codes & accepted_codes or not codes <= set(control['allFamilyOfficialCodes']):
            raise ValueError('Changed increments must be disjoint exact family codes')
        if (child_replay['failures'] or not child_replay['actualGeometryIndexExecuted']
                or child_numeric['probeFailures'] or child_numeric['probeCount'] != len(codes)
                or child_replay['cases'] != 4 * len(codes)):
            raise ValueError('Existing minimum target-only verification must pass for each actual increment')
        accepted_codes |= codes
        new_codes |= set(pub['newLocationCodes'])
        upgrade_codes |= set(pub['fullScopeUpgradeCodes'])
        actual_corrections=set(pub.get('sourceBodyCorrectionCodes',[]))
        incident=read(directory/(slug+'-retained-incidents-v1.json'))
        if actual_corrections!={r['code']for r in incident.get('provedPriorBodyCorrections',[])}:raise ValueError('Exact previously-full correction proofs required')
        correction_codes |= actual_corrections
        for key in ['probeCount', 'sourceSupportedImprovements']: numeric[key] += child_numeric[key]
        replay['cases'] += child_replay['cases']
        applied_times.append(utc(practical['appliedAtUtc']))
        completed_proofs.append({'directory': str(directory), 'publication': pin(pub_path),
                                 'practical': pub['practicalReceipt'], 'actualJvm': pin(jvm_path),
                                 'numeric': pin(numeric_path), 'windowBinding': pin(binding)})
    if new_codes & upgrade_codes or correction_codes&(new_codes|upgrade_codes) or new_codes | upgrade_codes |correction_codes != accepted_codes:
        raise ValueError('Actual new and upgraded code accounting differs')
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
    for directory in dict.fromkeys([*completed, work,*[p.resolve()for p in args.attempt_work]]):
        for path in directory.glob('*.execution.json'):
            item = read(path)
            if item.get('startedAtUtc') and item.get('finishedAtUtc'):
                if utc(item['startedAtUtc']) < utc(control['windowStartUtc']) or utc(item['finishedAtUtc']) > utc(control['deadlineUtc']):
                    raise ValueError('Audited receipt outside this exact window')
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
    applied = max(applied_times)
    first_applied = min(applied_times)
    if len(new_codes) != gains['geographic'] or len(new_codes|upgrade_codes) != gains['completeSourceBodies']:
        raise ValueError('Actual increment totals must equal pinned whole-window gains')
    metrics = {'status': 'PARTIAL_FAMILY_PAUSED_PENDING_EXACT_HUMAN_DECISIONS', 'closedAtUtc': now.isoformat(),
               'family': slug, 'familyCompleteEntries': len(full), 'familyGeographicEntries': len(geographic),
               'familyTotal': len(official), 'pendingCodes': pending, 'counts': counts,
               'baseline': control['startedBaseline'], 'gains': gains,
               'windowWallSeconds': wall, 'qualifiedAdditionsPerWallHour': gains['geographic'] * 3600 / wall,
               'startToInstallationSeconds': (applied - start).total_seconds(),
               'startToFirstInstallationSeconds': (first_applied - start).total_seconds(),
               'completedIncrementProofs': completed_proofs, 'changedBoundaryCount': len(accepted_codes), 'provedExistingFullCorrectionCodes':sorted(correction_codes), 'provedExistingFullCorrectionCount':len(correction_codes),
               'allCurrentWindowAcceptedChangedCodes': sorted(accepted_codes),
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
        f'First qualified installation after {(first_applied-start).total_seconds()/60:.2f} minutes; last after {(applied-start).total_seconds()/60:.2f} minutes. Window through review: {wall/60:.2f} minutes; {metrics["qualifiedAdditionsPerWallHour"]:.2f} new locations per elapsed hour. Direct leaf span sum {metrics["recordedLeafProcessSecondsSum"]:.3f}s; interval union {active:.3f}s. Outside-span wall time is unclassified.', '',
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
                   currentWindowAcceptedFamilyCodes=sorted(accepted_codes), currentWindowCompleteBodyCount=len(accepted_codes),
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
        'currentWorkNewLocations': len(new_codes),
        'currentWorkFullUpgrades': len(upgrade_codes), 'currentWorkExistingBodyCorrections':len(correction_codes)}
    refresh(public, cp, model)
    put(final_path, {'status': metrics['status'], 'metrics': metrics_ref, 'review': pin(work / 'SELF_EVALUATION.md'),
                     'control': pin(cp), 'workflow': pin(workflow), 'counts': counts, 'gains': gains,
                     'pendingCodes': pending, 'timerVerificationRequired': True, 'familyComplete': False,
                     'activeWorkers': [], 'activeOwnedValidationPids': []})
    print(json.dumps({'phase': 'paused', 'familyComplete': len(full), 'pendingCodes': pending, 'gains': gains}))


if __name__ == '__main__':
    main()
