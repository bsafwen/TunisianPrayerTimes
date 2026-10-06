"""Run the actual new wrapper in an isolated fixed-time full publication fixture."""
import argparse
from datetime import datetime
import importlib
import json
from pathlib import Path
import shutil
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts.locality_automation.run_sealed_boundary_queue import checked, pin, read
from scripts.locality_automation import refresh_report_with_task_source_index_v1 as wrapper

NAMES = ('task-report.json', 'boundary-map.json', 'dashboard.html',
         'frina-investigation.json', 'frina-investigation.html', 'frina-investigation.syntax.js')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--compare', required=True, type=Path)
    args = parser.parse_args()
    spec = read(args.manifest)
    checked(spec['publicationWrapper'])
    if Path(wrapper.__file__).resolve() != checked(spec['publicationWrapper']).resolve():
        raise ValueError('Actual pinned wrapper required')
    public = checked(spec['reader']).parent
    before_live = {name: pin(public / name) for name in NAMES}
    fixed = datetime.fromisoformat(spec['fixedUtc'])
    if fixed.tzinfo is None:
        raise ValueError('Exact aware fixture time required')
    args.output.mkdir(exist_ok=False)
    vendor = args.output / 'map-vendor'
    vendor.mkdir()
    for name in ('leaflet.css', 'leaflet.js'):
        shutil.copyfile(public / 'map-vendor' / name, vendor / name)
    sys.path.insert(0, str(public))
    app = importlib.import_module('task_report_app')
    reader = importlib.import_module('task_report_data')
    frina = importlib.import_module('frina_investigation')
    class FixedClock(datetime):
        @classmethod
        def now(cls, tz=None):
            return fixed.astimezone(tz) if tz else fixed.replace(tzinfo=None)
    app.HERE = args.output
    frina.HERE = args.output
    reader.datetime = FixedClock
    frina.datetime = FixedClock
    original_app_build, original_reader_build = app.build_task_report, reader.build_task_report
    original_argv = sys.argv
    try:
        sys.argv = ['wrapper', '--manifest', str(args.manifest), '--receipt', str(args.output / 'wrapper-receipt.json')]
        wrapper.main()
    finally:
        sys.argv = original_argv
    if app.build_task_report is not original_app_build or reader.build_task_report is not original_reader_build:
        raise ValueError('Actual wrapper did not restore stock module bindings')
    outputs = []
    for name in NAMES:
        if (args.output / name).read_bytes() != (args.compare / name).read_bytes():
            raise ValueError('Whole output differs from stock fixture: ' + name)
        checked(before_live[name])
        outputs.append(pin(args.output / name))
    checked(spec['publicationWrapper'])
    result = {'status': 'PASS_ACTUAL_WRAPPER_ENTIRE_SIX_OUTPUT_STOCK_FIXTURE',
              'manifest': pin(args.manifest), 'wrapperReceipt': pin(args.output / 'wrapper-receipt.json'),
              'outputs': outputs, 'liveOutputPinsUnchanged': before_live,
              'stockBindingsRestored': True, 'privateGoogleConfigRead': False,
              'googleAuthenticationOrRenderingAsserted': False, 'liveWrites': False, 'credit': 0}
    (args.output / 'verification.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'status': result['status'], 'entireOutputCount': len(outputs), 'credit': 0}))


if __name__ == '__main__':
    main()
