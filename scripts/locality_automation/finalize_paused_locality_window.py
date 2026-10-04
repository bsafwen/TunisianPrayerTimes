"""Release verified reusable helpers and write an audit while validation stays paused."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import sys
import tempfile
import tomllib

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree
from scripts.locality_automation.run_sealed_boundary_queue import checked, pin, read
from scripts.locality_automation.work_window_guard import parse_utc


def write_new(path, value):
    with path.open('x', encoding='utf-8') as stream:
        stream.write(json.dumps(value, ensure_ascii=False, indent=2) + '\n')
        stream.flush()
        os.fsync(stream.fileno())
    return pin(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    args = parser.parse_args()
    spec = read(args.manifest)
    control_path = Path(spec['control'])
    control = read(control_path)
    if control['phase'] != 'paused' or control['iteration'] != spec['iteration']:
        raise ValueError('Review and helper adoption require the exact paused window')
    check_tree(spec)
    cutoff = read(checked(spec['cutoff']))
    metrics = read(checked(spec['metrics']))
    publication = read(checked(spec['publicationVerification']))
    if (control['phase'] != 'paused' or control['iteration'] != spec['iteration']
            or control['subagentsAllowed'] is not False or control['acceptanceOwner'] != '/root'
            or datetime.now(timezone.utc) < parse_utc(control['deadlineUtc'])
            or cutoff['remainingOwnedValidationProcesses'] != []
            or metrics['scheduledHours'] != 2 or any(metrics['qualifiedGains'].values())
            or publication['status'] != 'PASS_RETAINED_PUBLICATION_AND_ACTUAL_TASK_CLOCKS'):
        raise ValueError('Exact paused root-only zero-gain window with verified publication required')
    automation = tomllib.loads(checked(spec['automation']).read_text(encoding='utf-8'))
    if automation['status'] != 'PAUSED' or automation['target_thread_id'] != spec['threadId']:
        raise ValueError('Same deadline heartbeat must already be paused and verified')
    speed = read(checked(spec['benchmarkComparison']))
    fixture = read(checked(spec['wrapperFixture']))
    if (speed['status'] != 'PASS_COUNTERBALANCED_FULL_STOCK_AND_INDEXED_PUBLICATION'
            or speed['credit'] != 0 or len(speed['trials']) != 4
            or fixture['status'] != 'PASS_ACTUAL_WRAPPER_ENTIRE_SIX_OUTPUT_STOCK_FIXTURE'
            or fixture['liveWrites'] or fixture['credit'] != 0):
        raise ValueError('Complete original stock comparison and actual isolated wrapper fixture required')
    for trial in speed['trials']:
        measurement = read(checked(trial['measurement']))
        receipt = read(checked(trial['execution']))
        directory = Path(trial['measurement']['file']).parent
        if receipt['exitCode'] != 0 or receipt['timedOut']:
            raise ValueError('Counterbalanced trial receipt no longer qualifies')
        for name, digest in speed['sixEntireOutputFilesIdentical'].items():
            checked({'file': str(directory / name), 'sha256': digest})
        if measurement['files'] != speed['sixEntireOutputFilesIdentical']:
            raise ValueError('Trial whole outputs disagree')
    for ref in fixture['outputs']:
        checked(ref)
        if ref['sha256'] != speed['sixEntireOutputFilesIdentical'][Path(ref['file']).name]:
            raise ValueError('Actual wrapper fixture differs from entire stock output')
    test_receipt = read(checked(spec['newTests']))
    negative = read(checked(spec['negativeLauncherCheck']))
    if test_receipt['exitCode'] != 0 or negative['status'] != 'PASS_REAL_LAUNCHER_SOURCE_CUTOFF_REFUSAL':
        raise ValueError('New helper failure/restoration/cutoff verification did not pass')
    annex = read(checked(spec['sourceAnnex']))
    if annex['nativeConsistentContextsIncludingOriginal'] != 24 or annex['credit'] != 0:
        raise ValueError('Exact source annex facts differ')
    now = datetime.now(timezone.utc).isoformat()
    work = Path(spec['workDirectory'])
    release = {'status': 'VERIFIED_HELPERS_ADOPTED_FOR_NEXT_AUTHORIZED_WINDOW_ONLY', 'atUtc': now,
               'manifest': pin(args.manifest), 'helpers': spec['helpers'],
               'benchmark': spec['benchmarkComparison'], 'wholeWrapperFixture': spec['wrapperFixture'],
               'tests': spec['newTests'], 'actualSourceCutoffDenial': spec['negativeLauncherCheck'],
               'elapsedReductionPercent': speed['elapsedReductionPercent'],
               'stockMeanSeconds': speed['stockMeanSeconds'], 'indexedMeanSeconds': speed['indexedMeanSeconds'],
               'originalGuardReaderPublisherDecoderAssetsUnchanged': True,
               'sourceScopeAccepted': False, 'newCredit': 0, 'validationResumed': False}
    release_ref = write_new(work / 'helper-verification-and-adoption-final.json', release)
    next_plan = {'status': 'PREPARED_WAITING_HUMAN_INSTRUCTIONS', 'sourceAnnex': spec['sourceAnnex'],
                 'positiveCaptureBindings': spec['positiveCaptureBindings'],
                 'completeSourceComponents': spec['completeSourceComponents'],
                 'defaultReportPublisher': spec['helpers']['reportPublisher'],
                 'directLeafLauncher': spec['helpers']['directLeafLauncher'],
                 'requiredStockGuard': spec['stockGuard'], 'validationResumed': False,
                 'rules': ['Reuse the three captured ISIE originals; do not refetch or redundantly search all providers.',
                           'Check source admission and unresolved provenance before any expensive GPS/JVM replay.',
                           'Review each complete connected source group; keep raw seams and protected bodies unchanged until all acceptance gates pass.',
                           'Use exact control UTC strings through the direct original guard API and fresh exclusive leaf receipts.',
                           'Use the verified report wrapper with unchanged stock CAF/A4 readers and all source/export checks.',
                           'Keep validation paused until a new human work instruction.'], 'credit': 0}
    next_ref = write_new(work / 'next-window-reuse-plan.json', next_plan)
    rows = metrics['processRows']
    failures = [row for row in rows if row['exitCode'] not in (None, 0)]
    review_path = Path(spec['reviewOutput'])
    review = f"""# Window {spec['iteration']:03d}: paused after two hours

{control['windowStartUtc']} to {control['deadlineUtc']}. Root only; no subagents. Work is paused and awaits the user's instructions.

| Qualified measure | Before | After | Gain | Per scheduled hour |
| --- | ---: | ---: | ---: | ---: |
| Locations validated | 461 | 461 | 0 | 0 |
| Complete current-source bodies | 323 | 323 | 0 | 0 |
| Installed corrections | 277 | 277 | 0 | 0 |

## Evaluation

The window did not achieve an accepted addition. Admission found no eligible release: 35 already complete, 13 same-author preparations requiring distinct source review, and 8 legacy candidates requiring provenance/reconciliation. All original holds remain. No expensive blocked C17 GPS/JVM cohorts were repeated.

Three previously missing original ISIE PDFs were captured through the required proxy with TLS verification. Essafsaf and Rouhia have consistent whole-native context; Zohour West 4 retains a foreign El Bassatine caption hold. These are source preparations, with zero validation credit. The complete 30-body neighborhood context now has 24 consistent native contexts, with original hold records preserved.

Three Abd al-Azim neighbor comparisons and complete five-body Es-Sray / six-body Djedliane comparisons showed that large mixed-source conflicts shrink when original neighboring sheets are compared together. All separately registered seams remain diagnostics. Protected neighbors, installed geometry, source legal scope and acceptance gates were not changed.

Observed guarded child spans: {metrics['processUnionSeconds']/60:.3f} minutes of union, {metrics['processSumSeconds']/60:.3f} minutes summed across {len(rows)} terminal receipts. These measure child processes, not LLM attention or productive location labor. {len(failures)} launched attempts returned nonzero; original failures and the expected cutoff-denial test are preserved. No successful child ran past the hard deadline. The remaining wall time includes source interpretation, coding, manifest preparation, administration and the configured closing margin; these are not credited as validation.

Efficiency remains poor at zero accepted locations per hour. Repeated schema/path assumptions, PowerShell time coercion and mismatched verification baselines created avoidable retries. Report optimization helps reporting only; it does not solve source/provenance conflicts. Selection should stop inadmissible cases before heavy replay, and source preparation should produce complete reusable connected-group evidence.

## Verified improvements applied while paused

The next-window report publisher uses a temporary index for repeated task-source path resolution. Counterbalanced full stock/indexed/indexed/stock trials fell from {speed['stockMeanSeconds']:.3f}s to {speed['indexedMeanSeconds']:.3f}s mean ({speed['elapsedReductionPercent']:.2f}% less time). All six output files were byte-identical. The actual wrapper passed the entire stock fixture and restores module bindings on success and failure; paused-window publication is refused. Original reader, publisher, CAF/A4 checks, decoder and assets remain unchanged.

A direct control-driven launcher now reads literal UTC values, calls the original guard synchronously, and retains 120s source/intake and 420s map minimum budgets. It passed four unit checks, one actual complete-component leaf, and a real source-cutoff refusal with no child started. Three wrapper failure/restoration/paused-window checks also passed. Earlier path, topology and source-context checks and all original failed attempts remain preserved.

Reusable finite programs now preserve historical packet decision/verdict schemas, build native source context, fetch missing official sheets in parallel through the proxy using Windows trusted certificates, and compare complete connected source components. Original source selection, protected-body, legal, topology, GPS, prayer, persistence and full-scope acceptance gates remain mandatory.

## Report and next work

The dashboard preserves all 461 mapped bodies and every prior cumulative graph point. Fifteen actual source-task clocks are published, including shared multi-location clocks without duplicating labor or awarding acceptance credit. No Google browser authentication/rendering claim is made.

Reuse the captured sheets and frozen technical cohorts. Resolve complete source groups and admissibility before heavy replay. The verified launcher and report wrapper are the defaults for a future user-authorized window. The same heartbeat is paused; there is no automatic resume.

Evidence: `root-cycle18/window-final-metrics.json`, `whole-window-publication-verification.json`, `source-context-annex.json`, `new-source-bindings.json`, `source-index-comparison.json`, `indexed-wrapper-fixture-v1/verification.json`, `helper-verification-and-adoption-final.json`, and `next-window-reuse-plan.json`.
"""
    with review_path.open('x', encoding='utf-8') as stream: stream.write(review)
    final = {'status': 'PAUSED_EVALUATED_IMPROVED_WAITING_USER', 'atUtc': now, 'iteration': spec['iteration'],
             'counts': metrics['current'], 'qualifiedGains': metrics['qualifiedGains'],
             'metrics': spec['metrics'], 'cutoff': spec['cutoff'], 'review': pin(review_path),
             'helperVerificationAndAdoption': release_ref, 'nextWindowReusePlan': next_ref,
             'sourceAnnex': spec['sourceAnnex'], 'sourceCaptureBindings': spec['positiveCaptureBindings'],
             'automation': spec['automation'], 'subagentsUsed': False, 'automaticResume': False}
    final_ref = write_new(Path(spec['finalOutput']), final)
    control.update(phase='paused', actualControlPauseAtUtc=cutoff['observedAtUtc'],
        lastControlUpdateUtc=now, cutoffTimerState='PAUSED_VERIFIED_C18', cutoffTimerVerification=spec['automation'],
        userAuthorizedAdaptiveCycles=False, currentAcceptedCycleCodes=[], currentAcceptedCycleFullCount=0,
        latestRefreshedCounts=metrics['current'], reviewDecision='Two hours completed; evaluated and verified improvements applied. WAIT user instructions.',
        lastVerifiedImprovement={'name': '38.25% faster identical report publication and direct exact-UTC control launcher', 'verification': release_ref, 'geographicCreditAdded': 0},
        nextWindowPriority='Reuse captured neighboring sheets, check genuine admission first, review coherent source components before heavy replay.',
        defaultReportPublisher=spec['helpers']['reportPublisher'], directControlLeafLauncher=spec['helpers']['directLeafLauncher'],
        currentWindowReview={'review': pin(review_path), 'final': final_ref, 'metrics': spec['metrics']})
    original_control = pin(control_path)
    descriptor, temporary = tempfile.mkstemp(prefix=control_path.name+'.', suffix='.tmp', dir=control_path.parent)
    with os.fdopen(descriptor, 'w', encoding='utf-8') as stream:
        stream.write(json.dumps(control, ensure_ascii=False, indent=2) + '\n')
        stream.flush(); os.fsync(stream.fileno())
    checked(original_control)
    os.replace(temporary, control_path)
    Path(spec['workflowOutput']).write_text(
        f"# Locality validation paused after window {spec['iteration']:03d}\n\n"
        f"Two-hour window ended {control['deadlineUtc']}. Counts461 geographic /323 complete source bodies /277 installed corrections; zero additions. No subagents.\n\n"
        f"Read `{review_path}` and `{work / 'next-window-reuse-plan.json'}` before future work. The same heartbeat is paused. WAIT human instructions; no automatic resume.\n\n"
        "The verified report wrapper and direct control-driven original guard launcher are prepared for the next authorized window. Reuse the three fetched official neighbor sheets and complete source components. Never repeat held heavy cohorts without changed admissibility.\n\n"
        "All original source/hash/native/identity/legal/neighbor/topology/GPS/prayer/persistence/protected-body/full-scope/CAF+A4 gates remain. Root sole acceptance/map/geometry owner; never fabricate independent source review. Keep all failed attempts. All host HTTP/HTTPS through127.0.0.1:8888; never web__run, private Google-key reads or browser-preview bypass.\n", encoding='utf-8')
    print(json.dumps({'status': final['status'], 'qualifiedGains': final['qualifiedGains'], 'review': str(review_path)}))


if __name__ == '__main__':
    main()
