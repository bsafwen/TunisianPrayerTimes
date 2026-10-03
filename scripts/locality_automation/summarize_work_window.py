"""Compute qualified window gains and real guarded process spans from pinned files."""
import argparse
from collections import defaultdict
from datetime import datetime, timezone
import json
from pathlib import Path
import statistics
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts.locality_automation.run_sealed_boundary_queue import checked, pin, read
from scripts.locality_automation.work_window_guard import parse_utc


def union_seconds(spans):
    merged = []
    for start, end in sorted(spans):
        if end < start:
            raise ValueError('Negative physical process span')
        if merged and start <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(end, merged[-1][1]))
        else:
            merged.append((start, end))
    return sum((end - start).total_seconds() for start, end in merged)


def count_summary(publication):
    summary = publication['summary']
    for key in ('validationIssues', 'sourceOnlyAuditIssues', 'reportingIssues'):
        if summary.get(key) != []:
            raise ValueError('Unresolved publication issues: ' + key)
    return {'geographic': summary['uniqueValidatedLocations'],
            'completeSourceBodies': summary['explicitFullSourceBoundaryLocationCount'],
            'installedCorrections': summary['uniqueInstalledCorrectionLocations']}


def benchmark_summary(references, comparison_field):
    modes = defaultdict(list)
    for index, ref in enumerate(references):
        result = read(checked(ref))
        if not result['status'].startswith('PASS_') or (index and result.get(comparison_field) is not True):
            raise ValueError('Incomplete full-output benchmark comparison')
        modes[result['mode']].append(result['elapsedSeconds'])
    if set(modes) != {'baseline', 'cache'} or any(len(rows) != 2 for rows in modes.values()):
        raise ValueError('Two baseline and two cache measurements required')
    baseline, cache = statistics.mean(modes['baseline']), statistics.mean(modes['cache'])
    return {'baselineSeconds': modes['baseline'], 'cacheSeconds': modes['cache'],
            'baselineMeanSeconds': baseline, 'cacheMeanSeconds': cache,
            'elapsedReductionPercent': 100 * (1 - cache / baseline), 'speedRatio': baseline / cache,
            'scope': 'Exact tested consumer only; not overall geographic throughput.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    manifest = read(args.manifest)
    start = read(checked(manifest['windowStart']))
    before = read(checked(manifest['baselinePublication']))
    after = read(checked(manifest['publication']))
    initial, current = count_summary(before), count_summary(after)
    if initial != start['baseline']:
        raise ValueError('Pinned baseline counts disagree')
    scheduled_start, scheduled_end = parse_utc(start['atUtc']), parse_utc(start['deadlineUtc'])
    hours = (scheduled_end - scheduled_start).total_seconds() / 3600
    if hours <= 0:
        raise ValueError('Positive scheduled window required')
    old_codes, new_codes = set(before['summary']['validatedLocationCodes']), set(after['summary']['validatedLocationCodes'])
    if not old_codes <= new_codes:
        raise ValueError('Previously validated place disappeared')
    gains = {key: current[key] - initial[key] for key in initial}
    if gains['geographic'] != len(new_codes - old_codes):
        raise ValueError('Geographic gain/code union disagree')
    rows, groups, all_spans, seen = [], defaultdict(list), [], set()
    for declaration in manifest['phaseReceipts']:
        ref = declaration['receipt']
        path = checked(ref)
        if str(path.resolve()) in seen:
            raise ValueError('A phase receipt is counted twice')
        seen.add(str(path.resolve()))
        result = read(path)
        if result.get('status') == 'RUNNING':
            raise ValueError('Incomplete physical process receipt')
        row = {'receipt': ref, 'category': declaration['category'], 'status': result['status'],
               'pid': result.get('processId'), 'exitCode': result.get('exitCode'), 'reason': result.get('reason')}
        if result.get('startedAtUtc') and result.get('finishedAtUtc'):
            started, ended = parse_utc(result['startedAtUtc']), parse_utc(result['finishedAtUtc'])
            row.update(startedAtUtc=result['startedAtUtc'], finishedAtUtc=result['finishedAtUtc'],
                       seconds=(ended - started).total_seconds(),
                       startedBeforeSafeStart=started <= parse_utc(result['safeStartUtc']),
                       finishedBeforeHardDeadline=ended < parse_utc(result['hardDeadlineUtc']))
            if not row['finishedBeforeHardDeadline'] and result.get('exitCode') == 0:
                raise ValueError('Successful result extends past hard cutoff')
            groups[declaration['category']].append((started, ended))
            all_spans.append((started, ended))
        rows.append(row)
    benchmarks = {name: benchmark_summary(item['measurements'], item['comparisonField'])
                  for name, item in manifest.get('benchmarks', {}).items()}
    result = {'status': 'PINNED_WORK_WINDOW_METRICS', 'createdAtUtc': datetime.now(timezone.utc).isoformat(),
              'manifest': pin(args.manifest), 'baseline': initial, 'current': current, 'qualifiedGains': gains,
              'newGeographicCodes': sorted(new_codes - old_codes), 'scheduledHours': hours,
              'geographicGainsPerScheduledHour': gains['geographic'] / hours,
              'fullBodyGainsPerScheduledHour': gains['completeSourceBodies'] / hours,
              'processRows': rows, 'processUnionSeconds': union_seconds(all_spans),
              'processSumSeconds': sum(row.get('seconds', 0) for row in rows),
              'processUnionSecondsByCategory': {key: union_seconds(value) for key, value in groups.items()},
              'benchmarks': benchmarks, 'sourceOnlyOrToolingCredit': 0,
              'qualification': 'Durations are observed child start/finish spans, not LLM attention or unmeasured user waits. Scheduled-hour throughput includes reused backlog and tooling time; no controlled end-to-end speedup claim.'}
    with args.output.open('x', encoding='utf-8') as stream:
        stream.write(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({key: result[key] for key in ('status', 'qualifiedGains', 'geographicGainsPerScheduledHour', 'processUnionSeconds')}))


if __name__ == '__main__':
    main()
