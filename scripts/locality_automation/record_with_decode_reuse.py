"""Explicit single-case sole-owner adapter for the full pinned CAF recorder."""
import argparse
from datetime import datetime, timezone
import importlib.util
import json
import os
from pathlib import Path
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts.locality_automation.run_sealed_boundary_queue import checked, pin, read
from scripts.locality_automation.packed_decode_cache_caf_v1 import reuse_caf_reader
from scripts.locality_automation.work_window_guard import check_window


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--cache-receipt', type=Path, required=True)
    for name in ('run', 'handoff', 'gps', 'output', 'evidence-root', 'report-data'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    manifest = read(args.manifest)
    control = read(manifest['control'])
    if (manifest['mode'] != 'single-standard-current-body-NoOp'
            or control.get('phase') != 'working' or control.get('iteration') != manifest['iteration']
            or control.get('acceptanceOwner') != manifest['expectedOwner']):
        raise ValueError('Explicit standard current-body writer release differs')
    gate = check_window(control['safeIntakeStartUtc'], control['deadlineUtc'], 120)
    if gate['status'] == 'DENIED':
        raise ValueError(gate)
    if args.cache_receipt.exists() or args.output.exists():
        raise ValueError('Fresh per-case output and adapter receipt required')
    if (args.run.resolve() != Path(manifest['run']).resolve()
            or args.handoff.resolve() != Path(manifest['handoff']).resolve()
            or args.evidence_root.resolve() != Path(manifest['evidenceRoot']).resolve()
            or args.report_data.resolve() != checked(manifest['reader']).resolve()
            or args.gps.resolve() != checked(manifest['gps']).resolve()):
        raise ValueError('Explicit per-case input/ledger paths differ')
    for key in ('caf', 'reader', 'decoder', 'decodeAdapter', 'gps'):
        checked(manifest[key])
    for ref in manifest['assets']:
        checked(ref)
    spec = importlib.util.spec_from_file_location('full_pinned_standard_caf', checked(manifest['caf']))
    caf = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(caf)
    started = datetime.now(timezone.utc).isoformat()
    before = time.perf_counter()
    with reuse_caf_reader(caf, manifest['caf'], manifest['reader'], manifest['decoder']) as statistics:
        caf.record(args)
    reference = pin(args.output / 'current-validation-receipt.json')
    for path in (args.run, args.handoff):
        links = read(path)['currentGeometryValidations']
        if links.count(reference) != 1:
            raise ValueError('Exact standard receipt was not linked once in both ledgers')
    for ref in manifest['assets']:
        checked(ref)
    for key in ('caf', 'reader', 'decoder', 'decodeAdapter', 'gps'):
        checked(manifest[key])
    result = {'status': 'COMPLETE_STANDARD_CAF_WITH_SCOPED_DECODE_REUSE',
              'elapsedSeconds': time.perf_counter() - before, 'cacheStatistics': statistics,
              'startedAtUtc': started, 'finishedAtUtc': datetime.now(timezone.utc).isoformat(),
              'payloadPid': os.getpid(), 'standardReceipt': reference, 'manifest': pin(args.manifest),
              'sourceOrQaGatesReplaced': False, 'appAssetsChanged': False,
              'qualification': 'Only the standard receipt supplies acceptance credit. This adapter adds no extra credit.'}
    with args.cache_receipt.open('x', encoding='utf-8') as stream:
        stream.write(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'status': result['status'], 'elapsedSeconds': result['elapsedSeconds']}))


if __name__ == '__main__':
    main()
