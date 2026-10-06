"""Directly supervise one pinned Python leaf with exact UTC fields from control.

Avoid shell date coercion and duplicated launch command construction. The
original guard still exclusively owns the receipt, child and deadline.
"""
import argparse
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts.locality_automation import work_window_guard as guard
from scripts.locality_automation.run_sealed_boundary_queue import active_control, checked, read

GUARD_SHA256 = 'd2fe7a733097536d022bcb5e8ebd5b0688bf1bb6adcd955d659fce052a226ade'
GATES = {'source': ('safeSourceQaStartUtc', 120), 'intake': ('safeIntakeStartUtc', 120),
         'map': ('safeMapStartUtc', 420)}


def launch(spec, receipt):
    control = active_control(spec)
    guard_path = Path(guard.__file__).resolve()
    original_guard = {'file': str(guard_path), 'sha256': GUARD_SHA256}
    checked(original_guard)
    kind = spec['phase']
    if kind not in GATES or control.get('mapOwner') != '/root' or spec['owner'] != '/root':
        raise ValueError('Explicit sole-root finite phase category required')
    field, minimum = GATES[kind]
    if kind == 'map' and control['mapMinimumRemainingSeconds'] != minimum:
        raise ValueError('Separate original map budget changed')
    program = checked(spec['program'])
    if program.suffix != '.py' or not program.is_file():
        raise ValueError('One pinned direct Python leaf required')
    arguments = spec['arguments']
    if not isinstance(arguments, list) or any(type(argument) is not str for argument in arguments):
        raise ValueError('Literal leaf argument list required')
    if not Path.cwd().is_dir() or not receipt.parent.is_dir():
        raise ValueError('Existing producer cwd and receipt directory required')
    # Direct original guard API: no check-then-spawn and no descendant launcher.
    result = guard.launch_guarded(control[field], control['deadlineUtc'],
        [sys.executable, '-X', 'utf8', '-B', str(program), *arguments], minimum, receipt)
    checked(original_guard)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--record', type=Path, required=True)
    args = parser.parse_args()
    result = launch(read(args.manifest), args.record)
    print(json.dumps(result))
    return 2 if result['status'] == 'DENIED' else (0 if result.get('exitCode') in (None, 0) else 1)


if __name__ == '__main__':
    raise SystemExit(main())
