"""Inspect a pinned finite backlog while paused; never release validation work.

Uses the original report epoch, source-author classification and hash checks.
This is a resume-planning diagnostic, not a source review or acceptance gate.
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts.locality_automation.plan_source_review_actions import classify, indexed_reference
from scripts.locality_automation.review_candidate_queue import _Snapshot, _report_epoch, _json


def inspect(spec):
    snapshot = _Snapshot()
    raw, control_pin = snapshot.read(spec['control'])
    control = _json(raw)
    if (control.get('phase') != 'paused' or control.get('subagentsAllowed') is not False
            or control.get('acceptanceOwner') != '/root' or spec.get('reviewer') != '/root'):
        raise ValueError('Diagnostic requires paused sole-root control; no dispatch authority')
    raw, publication = snapshot.reference(spec['publication'])
    report = _json(raw)
    assets = {}
    for name, reference in spec['liveAssets'].items():
        _, assets[name] = snapshot.reference(reference)
    _, full, _, _ = _report_epoch(snapshot, report, assets)
    rows, seen = [], set()

    def add(code, author, source):
        if code in seen:
            raise ValueError('Duplicate finite-case code')
        seen.add(code)
        rows.append({'officialCode': code, 'observedSourceAuthor': author,
                     'disposition': classify(code, author, full, '/root'),
                     'source': source, 'credit': 0})

    for reference in spec['sourceIndexes']:
        raw, _ = snapshot.reference(reference)
        for entry in _json(raw)['rows']:
            code, author = entry['officialCode'], entry.get('sourceActor')
            source = indexed_reference(entry)
            if code not in full:
                payload, source = snapshot.reference(source)
                value = _json(payload)
                if value.get('sourceActor', value.get('sourceAuthor')) != author:
                    raise ValueError('Indexed and actual source author differ')
            add(code, author, source)
    for reference in spec['preparations']:
        raw, source = snapshot.reference(reference)
        value = _json(raw)
        add(value['officialCode'], value.get('sourceActor'), source)
    validated = {code for row in report['validations'] for code in row['locationCodes']}
    legacy = {code for row in report['sourceOnlyAcceptances'] for code in row['locationCodes']} - validated
    for code in sorted(legacy - seen):
        add(code, None, None)
        rows[-1]['disposition'] = 'LEGACY_SOURCE_ONLY_REQUIRES_RECONCILIATION_AND_PROVENANCE'
    snapshot.verify_unchanged()
    potential = [row['officialCode'] for row in rows
                 if row['disposition'] == 'DISJOINT_SOURCE_AUTHOR_REQUIRES_REMAINING_REVIEW_GATES']
    return {'status': 'PAUSED_BACKLOG_DIAGNOSTIC_NO_DISPATCH_AUTHORITY',
            'checkedAtUtc': datetime.now(timezone.utc).isoformat(),
            'control': control_pin, 'publication': publication, 'liveAssets': assets,
            'rows': rows, 'counts': dict(Counter(row['disposition'] for row in rows)),
            'potentialDistinctAuthorSourceReviewCodes': potential,
            'startBlockedForThisBacklog': not potential,
            'sourceReviewAuthorized': False, 'gpsOrJvmAuthorized': False,
            'recordingOrInstallationAuthorized': False, 'credit': 0,
            'qualification': 'Finite metadata diagnostic only. Positive suggestions still need fresh original admission and all evidence/reviewer gates. No claim about uninspected locations.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = inspect(_json(args.manifest.read_bytes()))
    with args.output.open('x', encoding='utf-8') as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    print(json.dumps({key: result[key] for key in
                     ('status', 'counts', 'startBlockedForThisBacklog', 'credit')}))


if __name__ == '__main__':
    main()
