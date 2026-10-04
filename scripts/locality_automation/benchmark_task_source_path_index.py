"""Run the unchanged report publisher in an isolated directory, with exact comparison."""
import argparse
from contextlib import ExitStack
from datetime import datetime
import importlib
import json
import os
from pathlib import Path
import shutil
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts.locality_automation.run_sealed_boundary_queue import checked, pin, read
from scripts.locality_automation.packed_decode_cache import reuse_reader_packed_slices
from scripts.locality_automation.task_source_path_index import indexed_task_source_paths


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--mode', choices=('stock', 'indexed'), required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--compare', type=Path)
    args = parser.parse_args()
    manifest = read(args.manifest)
    for ref in manifest['files'] + manifest['assets'] + manifest['liveEvidence']:
        checked(ref)
    reader_path = checked(manifest['reader'])
    checked(manifest['decodeCache'])
    checked(manifest['taskSourceIndex'])
    fixed = datetime.fromisoformat(manifest['fixedUtc'])
    if fixed.tzinfo is None:
        raise ValueError('Aware fixed publication clock required')
    args.output.mkdir(exist_ok=False)
    public = reader_path.parent
    vendor = args.output / 'map-vendor'
    vendor.mkdir()
    # Public vendor assets only. Never inspect or copy the private Google config.
    for name in ('leaflet.css', 'leaflet.js'):
        shutil.copyfile(public / 'map-vendor' / name, vendor / name)
    sys.path.insert(0, str(public))
    app = importlib.import_module('task_report_app')
    reader = importlib.import_module('task_report_data')
    frina = importlib.import_module('frina_investigation')
    app.HERE = args.output
    frina.HERE = args.output
    # Only fixture time/output destinations differ. Every original consumer runs.
    app.build_task_report = lambda: reader.build_task_report(now=fixed)
    class FixedClock(datetime):
        @classmethod
        def now(cls, tz=None):
            return fixed.astimezone(tz) if tz else fixed.replace(tzinfo=None)
    frina.datetime = FixedClock
    start = time.perf_counter()
    with ExitStack() as stack:
        statistics = stack.enter_context(reuse_reader_packed_slices(reader, manifest['reader'], manifest['decoder']))
        path_statistics = (stack.enter_context(indexed_task_source_paths(reader, manifest['reader']))
                           if args.mode == 'indexed' else None)
        result = app.refresh()
    seconds = time.perf_counter() - start
    names = ('task-report.json', 'boundary-map.json', 'dashboard.html',
             'frina-investigation.json', 'frina-investigation.html', 'frina-investigation.syntax.js')
    hashes = {}
    for name in names:
        path = args.output / name
        hashes[name] = pin(path)['sha256']
        if args.compare and path.read_bytes() != (args.compare / name).read_bytes():
            raise ValueError('Entire publication differs: ' + name)
    for ref in manifest['files'] + manifest['assets'] + manifest['liveEvidence']:
        checked(ref)
    checked(manifest['taskSourceIndex'])
    evidence = {'status': 'PASS_UNCHANGED_FULL_PUBLICATION', 'mode': args.mode,
                'elapsedSeconds': seconds, 'cacheStatistics': statistics, 'taskSourcePathStatistics': path_statistics, 'files': hashes,
                'entirePublicationByteCompared': bool(args.compare), 'payloadPid': os.getpid(),
                'manifest': pin(args.manifest), 'result': result, 'liveWrites': False,
                'newCredit': 0, 'privateGoogleConfigRead': False,
                'googleAuthenticationOrRenderingAsserted': False}
    (args.output / 'measurement.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({key: evidence[key] for key in ('status', 'mode', 'elapsedSeconds', 'cacheStatistics', 'entirePublicationByteCompared')}))


if __name__ == '__main__':
    main()
