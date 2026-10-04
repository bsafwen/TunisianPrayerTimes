"""Bind three positive provider captures to the frozen identity and native annex."""
import argparse
import csv
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts.locality_automation.run_sealed_boundary_queue import checked, pin, read


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    spec = read(args.manifest)
    fetch = read(checked(spec['fetch']))
    annex = read(checked(spec['annex']))
    with checked(spec['bindings']).open(encoding='utf-8-sig', newline='') as stream:
        identities = list(csv.DictReader(stream))
    results = []
    for code in ('235653', '245858', '425356'):
        capture = [row for row in fetch['sources'] if row['code'] == code]
        contexts = [row for row in annex['newSourceCaptures'] if row['officialCode'] == code]
        bindings = [row for row in identities if json.loads(row['identityCandidateCodes']) == [code]]
        if len(capture) != 1 or len(contexts) != 1 or len(bindings) != 1:
            raise ValueError('Finite unique source and identity binding required')
        source, context, identity = capture[0], contexts[0], bindings[0]
        audit = context['nativeAudit']
        if (source['httpStatus'] != 200 or not source['status'].startswith('FETCHED_PDF_')
                or source['pdf'] != audit['sourcePdf'] or source['tlsVerification'] is not True
                or source['proxy'] != 'http://127.0.0.1:8888'
                or source['name'] != identity['name'] or source['parent'] != identity['delegation']
                or context['id'] != identity['frozenFunctionalExpectedId']):
            raise ValueError('Source origin, proxy, TLS or exact identity differs')
        checked(source['pdf'])
        for page in source['pages']:
            checked(page['image'])
            checked(page['nativeText'])
        results.append({'officialCode': code, 'id': context['id'], 'name': identity['name'],
                        'parent': identity['delegation'], 'sourceCapture': source,
                        'ISIEPresenceStatus': 'MATCH_DOCUMENTED_ORIGINAL_PDF_AND_VISUAL_TITLE',
                        'GooglePresenceStatus': 'unchecked', 'OSMPresenceStatus': 'unchecked',
                        'nativeContextStatus': context['status'], 'allNativeHolds': audit['holds'],
                        'redundantAllProviderSearchRequired': False,
                        'sourceScopeAccepted': False, 'independentQaPassed': False, 'credit': 0})
    for reference in (spec['fetch'], spec['annex'], spec['bindings']): checked(reference)
    with args.output.open('x', encoding='utf-8') as stream:
        stream.write(json.dumps({'status': 'PASS_THREE_FINITE_POSITIVE_ISIE_CAPTURE_BINDINGS',
                                'manifest': pin(args.manifest), 'rows': results,
                                'geometryChanged': False, 'credit': 0}, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'status': 'PASS_THREE_FINITE_POSITIVE_ISIE_CAPTURE_BINDINGS', 'credit': 0}))


if __name__ == '__main__':
    main()
