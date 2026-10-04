"""Extend pinned neighboring source context without rewriting original holds."""
import argparse
from collections import Counter
import csv
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts.locality_automation.audit_native_replay_layers import audit
from scripts.locality_automation.run_sealed_boundary_queue import checked, pin, read


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    spec = read(args.manifest)
    original = read(checked(spec['originalContext']))
    native = read(checked(spec['newNativeContext']))
    binding_path = checked(spec['bindings'])
    with binding_path.open(encoding='utf-8-sig', newline='') as stream:
        bindings = list(csv.DictReader(stream))
    records = original['records']
    if len(records) != 30 or original['credit'] != 0:
        raise ValueError('Exact original thirty-body zero-credit context required')
    codes = {'235653': 'osm:relation:7129257', '245858': 'osm:relation:7125664', '425356': 'osm:relation:7164989'}
    captures = []
    for code, expected_id in codes.items():
        rows = [row for row in native['rows'] if row['officialCode'] == code]
        official = [row for row in bindings if json.loads(row['identityCandidateCodes']) == [code]]
        previous = [row for row in records if row['id'] == expected_id]
        if len(rows) != 1 or len(official) != 1 or len(previous) != 1:
            raise ValueError('Ambiguous source, binding or frozen component member')
        row, identity, old = rows[0], official[0], previous[0]
        if (identity['name'] != row['officialName'] or identity['delegation'] != row['officialParent']
                or identity['frozenFunctionalExpectedId'] != expected_id
                or old['status'] != 'HOLD_AMBIGUOUS_OR_MISSING_ORIGINAL_SOURCE'):
            raise ValueError('Exact original missing source identity binding differs')
        selected = row['configurations'][0]
        if selected['layers'] != ['red'] or selected['ownLabelContainingFaces'] != 1:
            raise ValueError('No unique raw red-layer context; keep pending')
        inputs = dict(row, ownTitleInteriorLabelIndexes=[row['ownLabelIndex']],
                      sourceGateDecision='NEW_NATIVE_CONTEXT_NO_ACCEPTANCE',
                      pageWkb=selected['pageWkb'], registeredWkb=selected['registeredWkb'])
        replay = audit(inputs, inputs)
        captures.append({'id': expected_id, 'officialCode': code, 'originalRecord': old,
                        'nativeAudit': replay, 'sourceAuthor': '/root', 'capturedThisWindow': True,
                        'status': 'NEW_NATIVE_CONTEXT_HOLD' if replay['holds'] else 'NEW_NATIVE_CONTEXT_READY_NOT_ACCEPTED',
                        'sourceScopeAccepted': False, 'independentQaPassed': False, 'credit': 0})
    for reference in (spec['originalContext'], spec['newNativeContext'], spec['bindings']):
        checked(reference)
    counts = dict(Counter(row['status'] for row in captures))
    result = {'status': 'ORIGINAL_THIRTY_BODY_CONTEXT_WITH_THREE_NEW_SOURCE_CAPTURES',
              'manifest': pin(args.manifest), 'originalRecords': records, 'newSourceCaptures': captures,
              'newCaptureCounts': counts,
              'nativeConsistentContextsIncludingOriginal': original['counts']['NATIVE_SOURCE_CONTEXT_READY_NOT_ACCEPTED'] + sum(not row['nativeAudit']['holds'] for row in captures),
              'originalHoldRecordsPreserved': True, 'originalSourceAuthorsInferred': False,
              'currentGeometryChanged': False, 'sourceScopeAccepted': False, 'independentQaPassed': False, 'credit': 0}
    with args.output.open('x', encoding='utf-8') as stream:
        stream.write(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'newCaptureCounts': counts, 'nativeConsistentContextsIncludingOriginal': result['nativeConsistentContextsIncludingOriginal'], 'credit': 0}))


if __name__ == '__main__':
    main()
