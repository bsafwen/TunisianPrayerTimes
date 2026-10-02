"""Create a fresh eligible Ministry catalogue without modifying the source or app.

Existence evidence is explicit: a list of identity fields plus a sources object
with ISIE/GoogleMaps/OpenStreetMap outcomes. Missing/unavailable checks remain
unknown; a provider match permits review and never certifies unit identity.
"""
from pathlib import Path
import argparse
import csv
import hashlib
import json

SOURCES = ('ISIE', 'GoogleMaps', 'OpenStreetMap')
OUTCOMES = {'match', 'no_match', 'unavailable', 'not_checked'}

def identity(row):
    return tuple(row[key] for key in ('governorate', 'delegation', 'name'))

def classify(row, policy, sources=None):
    excluded = {identity(item): item for item in policy['excludedEntries']}
    if identity(row) in excluded:
        return 'excluded_by_user', excluded[identity(row)]['reason']
    sources = sources or {}
    if set(sources) - set(SOURCES) or any(value not in OUTCOMES for value in sources.values()):
        raise ValueError('Unknown existence source or outcome')
    outcomes = [sources.get(key, 'not_checked') for key in SOURCES]
    if 'match' in outcomes:
        return 'eligible_for_review', 'Documented location match; identity and geometry still require review.'
    if all(value == 'no_match' for value in outcomes):
        return 'excluded_no_match', 'No matching location in all three checked providers.'
    return 'presence_unconfirmed', 'Unavailable or unchecked providers do not prove absence.'

def prepare(catalogue, policy_path, existence_path=None):
    policy = json.loads(policy_path.read_text(encoding='utf-8-sig'))
    if policy['schemaVersion'] != 1 or tuple(policy['existenceSources']) != SOURCES:
        raise ValueError('Unsupported catalogue policy')
    excluded = [identity(row) for row in policy['excludedEntries']]
    if len(excluded) != len(set(excluded)):
        raise ValueError('Duplicate policy exclusion')
    with catalogue.open(encoding='utf-8-sig', newline='') as stream:
        reader = csv.DictReader(stream)
        fields, rows = reader.fieldnames, list(reader)
    evidence = {}
    if existence_path:
        for item in json.loads(existence_path.read_text(encoding='utf-8-sig')):
            key = identity(item)
            if key in evidence:
                raise ValueError('Duplicate existence evidence')
            evidence[key] = item['sources']
    counts = {key: 0 for key in ('excluded_by_user', 'excluded_no_match', 'eligible_for_review', 'presence_unconfirmed')}
    eligible, dispositions = [], []
    for row in rows:
        status, reason = classify(row, policy, evidence.get(identity(row)))
        counts[status] += 1
        if not status.startswith('excluded_'):
            eligible.append(row)
        else:
            dispositions.append({'identity': dict(zip(('governorate', 'delegation', 'name'), identity(row))),
                                 'status': status, 'reason': reason})
    pin = lambda path: {'file': str(path.resolve()), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
    return fields, eligible, {'schemaVersion': 1, 'originalRows': len(rows), 'eligibleRows': len(eligible),
        'counts': counts, 'dispositions': dispositions, 'catalogue': pin(catalogue), 'policy': pin(policy_path),
        'existenceEvidence': pin(existence_path) if existence_path else None,
        'sourceCatalogueChanged': False, 'appDataChanged': False, 'geographicCreditAdded': 0,
        'qualification': 'Unconfirmed presence remains in the eligible target but is not cleared for detailed validation. User exclusions and three-provider no-match skips are separately counted.'}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--catalogue', type=Path, required=True)
    parser.add_argument('--policy', type=Path, default=Path(__file__).with_name('ministry_catalog_policy.json'))
    parser.add_argument('--existence', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    if args.output.resolve() == args.report.resolve() or args.output.exists() or args.report.exists():
        raise ValueError('Fresh disjoint output and report required')
    fields, rows, report = prepare(args.catalogue, args.policy, args.existence)
    with args.output.open('x', encoding='utf-8', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)
    with args.report.open('x', encoding='utf-8') as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    print(json.dumps({key: report[key] for key in ('originalRows', 'eligibleRows', 'counts', 'geographicCreditAdded')}))

if __name__ == '__main__':
    main()
