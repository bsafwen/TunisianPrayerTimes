"""Explicit sole-owner wrapper around the unchanged locked publication command."""
import argparse
from datetime import datetime, timezone
import importlib
import json
import os
from pathlib import Path
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts.locality_automation.run_sealed_boundary_queue import checked, pin, read
from scripts.locality_automation.packed_decode_cache import reuse_reader_packed_slices
from scripts.locality_automation.work_window_guard import check_window


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--receipt', type=Path, required=True)
    args = parser.parse_args()
    manifest = read(args.manifest)
    control = read(manifest['control'])
    if (control.get('phase') != 'working' or control.get('iteration') != manifest['iteration']
            or control.get('acceptanceOwner') != '/root' or control.get('mapOwner') != '/root'
            or control.get('subagentsAllowed') is not False):
        raise ValueError('Explicit active sole-root publication ownership differs')
    gate = check_window(control['safeMapStartUtc'], control['deadlineUtc'], 420)
    if gate['status'] == 'DENIED':
        raise ValueError(gate)
    if args.receipt.exists():
        raise ValueError('Fresh publication receipt required')
    checked(manifest['decodeCache'])
    checked(manifest['decoder'])
    for ref in manifest['files'] + manifest['assets'] + manifest['liveEvidence']:
        checked(ref)
    public = checked(manifest['reader']).parent
    sys.path.insert(0, str(public))
    app = importlib.import_module('task_report_app')
    reader = importlib.import_module('task_report_data')
    original_argv = sys.argv
    started = datetime.now(timezone.utc).isoformat()
    before = time.perf_counter()
    try:
        sys.argv = [str(public / 'task_report_app.py'), 'refresh']
        with reuse_reader_packed_slices(reader, manifest['reader'], manifest['decoder']) as statistics:
            app.main()
    finally:
        sys.argv = original_argv
    for ref in manifest['files'] + manifest['assets'] + manifest['liveEvidence']:
        checked(ref)
    checked(manifest['decodeCache'])
    checked(manifest['decoder'])
    model = read(public / 'task-report.json')
    result = {'status': 'PUBLISHED_THROUGH_UNCHANGED_LOCKED_APP', 'elapsedSeconds': time.perf_counter() - before,
              'cacheStatistics': statistics, 'payloadPid': os.getpid(), 'startedAtUtc': started,
              'finishedAtUtc': datetime.now(timezone.utc).isoformat(), 'manifest': pin(args.manifest),
              'outputs': [pin(public / name) for name in ('task-report.json', 'boundary-map.json', 'dashboard.html')],
              'geographicCount': model['summary']['uniqueValidatedLocations'],
              'stockAppLockRetained': True, 'originalPublisherAndReaderFilesUnmodified': True,
              'newCredit': 0, 'privateGoogleConfigRead': False}
    with args.receipt.open('x', encoding='utf-8') as stream:
        stream.write(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({key: result[key] for key in ('status', 'elapsedSeconds', 'cacheStatistics', 'geographicCount', 'newCredit')}))


if __name__ == '__main__':
    main()
