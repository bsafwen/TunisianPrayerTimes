'''Crash-safe stdlib task orchestration engine for locality automation plans.

Public API:
    validate_plan(plan) -> None
    run(plan, state_dir, allowed, workers=3) -> dict
    status(state_dir) -> dict
    set_paused(state_dir, paused) -> dict

The engine writes only under the caller-provided state directory except for
declared task outputs produced by the task command. It never uses a shell.
Optional read-only offline preflight/replay jobs call the UTC guard in process
around their direct child. Recorder/refresh mutation is outside this contract;
guarded children must not create unsupervised descendants. Execution success
and receipt/report labels grant no geographic acceptance or verification credit.
'''
from __future__ import annotations

import concurrent.futures
import hashlib
import json
import math
import os
import re
import subprocess
import tempfile
import time
from pathlib import Path
from typing import Any

from . import work_window_guard_jobs_v2 as work_window_guard

SCHEMA_VERSION = 1
DEFAULT_KIND = 'offline'
JOB_KINDS = {'offline', 'network', 'model', 'device', 'compile'}
GUARDED_KINDS = {'network', 'model', 'device'}
SATISFIED = {'success', 'imported'}
BLOCKING = {'failed', 'held', 'stale'}
_SAFE_ID = re.compile(r'^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$')
_HEX = re.compile(r'^[0-9a-fA-F]{64}$')


def _schema_ok(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value == SCHEMA_VERSION


def _as_path(value: Any, field: str) -> Path:
    if isinstance(value, Path):
        return value
    if isinstance(value, str):
        if not value:
            raise ValueError(field + ' must not be empty')
        return Path(value)
    raise ValueError(field + ' must be a path string or Path')


def _now() -> str:
    return time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())


def _job_hash(job: dict) -> str:
    blob = json.dumps(job, sort_keys=True, separators=(',', ':'), ensure_ascii=False)
    return hashlib.sha256(blob.encode('utf-8')).hexdigest()


def _effective_kind(job: dict) -> str:
    return job.get('kind', DEFAULT_KIND)


def _is_imported(job: dict) -> bool:
    value = job.get('importedOutputs')
    return isinstance(value, list) and len(value) > 0


def _valid_text(value: Any) -> bool:
    return isinstance(value, str) and chr(0) not in value


def _valid_file_field(value: Any) -> bool:
    return _valid_text(value) and bool(value)


def _valid_sha(value: Any) -> bool:
    return isinstance(value, str) and bool(_HEX.match(value))


def _resolve(base: Path, raw: str) -> Path:
    path = Path(raw)
    if not path.is_absolute():
        path = base / path
    return path.resolve()


def _base_for(job: dict) -> Path:
    cwd = job.get('cwd')
    if isinstance(cwd, str) and cwd:
        return Path(os.path.abspath(cwd))
    return Path(os.path.abspath(os.getcwd()))


def _job_cwd(job: dict, base: Path) -> Path:
    cwd = job.get('cwd')
    if isinstance(cwd, str) and cwd:
        return Path(os.path.abspath(cwd))
    return base


def _cmp_key(path: Path) -> str:
    return os.path.normcase(os.path.abspath(str(path)))


def _same_or_ancestor(first: Path, second: Path) -> bool:
    first_key = _cmp_key(first)
    second_key = _cmp_key(second)
    if first_key == second_key:
        return True
    try:
        return os.path.commonpath([first_key, second_key]) == first_key
    except ValueError:
        return False


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, 'rb') as handle:
        while True:
            chunk = handle.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def _is_regular_nonempty(path: Path) -> bool:
    try:
        if path.is_symlink():
            return False
        return path.is_file() and path.stat().st_size > 0
    except OSError:
        return False


def _output_entries(job: dict, base: Path) -> list[tuple[str, Path]]:
    entries: list[tuple[str, Path]] = []
    for raw in job.get('outputs', []):
        entries.append((raw, _resolve(base, raw)))
    for item in job.get('importedOutputs', []):
        raw = item['file']
        entries.append((raw, _resolve(base, raw)))
    if 'workWindow' in job:
        raw = job['workWindow']['executionReceipt']
        entries.append((raw, _resolve(base, raw)))
    return entries


def _input_entries(job: dict, base: Path) -> list[tuple[str, Path]]:
    entries: list[tuple[str, Path]] = []
    for item in job.get('inputs', []):
        raw = item['file']
        entries.append((raw, _resolve(base, raw)))
    return entries


def _atomic_write_json(path: Path, obj: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    data = json.dumps(obj, ensure_ascii=False, indent=2, sort_keys=True).encode('utf-8')
    descriptor, temporary = tempfile.mkstemp(prefix=path.name + '.', suffix='.tmp', dir=str(path.parent))
    try:
        with os.fdopen(descriptor, 'wb') as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        if os.name != 'nt':
            directory_fd = os.open(path.parent, os.O_RDONLY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
    except BaseException:
        try:
            os.unlink(temporary)
        except OSError:
            pass
        raise


def _read_json(path: Path) -> Any:
    with open(path, 'r', encoding='utf-8') as handle:
        return json.load(handle)


def _default_state() -> dict:
    return {'schemaVersion': SCHEMA_VERSION, 'jobs': {}}


def _read_paused(state_dir: Path) -> bool:
    path = state_dir / 'control.json'
    if not path.exists():
        return False
    obj = _read_json(path)
    if not isinstance(obj, dict) or not _schema_ok(obj.get('schemaVersion')) or not isinstance(obj.get('paused'), bool):
        raise ValueError('invalid engine control file')
    return obj['paused']


def _load_state(state_dir: Path) -> dict:
    path = state_dir / 'state.json'
    if not path.exists():
        return _default_state()
    obj = _read_json(path)
    if not isinstance(obj, dict) or not _schema_ok(obj.get('schemaVersion')) or not isinstance(obj.get('jobs'), dict):
        raise ValueError('invalid engine state')
    return obj


def _write_state(state_dir: Path, state: dict) -> None:
    _atomic_write_json(state_dir / 'state.json', state)


def _check_cycles(jobs_by_id: dict) -> None:
    visiting: set[str] = set()
    done: set[str] = set()

    def visit(job_id: str) -> None:
        if job_id in done:
            return
        if job_id in visiting:
            raise ValueError('cyclic dependency at ' + job_id)
        visiting.add(job_id)
        for dependency in jobs_by_id[job_id].get('deps', []):
            visit(dependency)
        visiting.remove(job_id)
        done.add(job_id)

    for job_id in jobs_by_id:
        visit(job_id)


def _check_path_collisions(jobs_by_id: dict) -> None:
    outputs: list[tuple[str, str, Path]] = []
    inputs: list[tuple[str, str, Path]] = []
    for job_id, job in jobs_by_id.items():
        base = _base_for(job)
        for raw, path in _output_entries(job, base):
            outputs.append((job_id, raw, path))
        for raw, path in _input_entries(job, base):
            inputs.append((job_id, raw, path))
    for index, (job_id, raw, path) in enumerate(outputs):
        for previous_id, previous_raw, previous_path in outputs[:index]:
            if _same_or_ancestor(path, previous_path) or _same_or_ancestor(previous_path, path):
                raise ValueError('output collision: ' + job_id + ':' + raw + ' and ' + previous_id + ':' + previous_raw)
    for job_id, raw, path in outputs:
        for input_job, input_raw, input_path in inputs:
            if _same_or_ancestor(path, input_path) or _same_or_ancestor(input_path, path):
                ancestors = set()
                def collect(ident):
                    for dep in jobs_by_id[ident].get('deps', []):
                        if dep not in ancestors:
                            ancestors.add(dep)
                            collect(dep)
                collect(input_job)
                if _cmp_key(path) != _cmp_key(input_path) or job_id not in ancestors:
                    raise ValueError('output overlaps input without dependency: ' + job_id + ':' + raw)


def _validate_work_window(job: dict, where: str) -> None:
    """Validate launch policy independently of report content or current clock.

    Preparation requires a fresh receipt; engine reservation remains exclusive
    at execution. A completed state may reuse only its own exact pinned receipt.
    """
    window = job['workWindow']
    fields = {'policyRevision', 'safeStartUtc', 'hardDeadlineUtc',
              'minimumRemainingSeconds', 'executionReceipt'}
    if not isinstance(window, dict) or set(window) != fields:
        raise ValueError(where + ' workWindow requires exactly: ' + ', '.join(sorted(fields)))
    if type(window['policyRevision']) is not int or window['policyRevision'] != 1:
        raise ValueError(where + ' workWindow policyRevision must be 1')
    minimum = window['minimumRemainingSeconds']
    if isinstance(minimum, bool) or not isinstance(minimum, (int, float)) or minimum < 120:
        raise ValueError(where + ' minimumRemainingSeconds must be finite and at least 120')
    work_window_guard._config(window['safeStartUtc'], window['hardDeadlineUtc'], minimum)
    raw = window['executionReceipt']
    if (not _valid_file_field(raw) or not Path(raw).is_absolute() or
            Path(raw) != Path(raw).resolve() or not Path(raw).parent.is_dir()):
        raise ValueError(where + ' executionReceipt must be absolute and resolved with an existing parent directory')
    stage = job.get('reviewStage')
    if (_effective_kind(job) != 'offline' or job.get('readOnlyOffline') is not True or
            not isinstance(stage, str) or stage not in {'preflight', 'replay'} or 'importedOutputs' in job):
        raise ValueError(where + ' workWindow supports only runnable readOnlyOffline preflight/replay jobs')
    executable = _resolve(_base_for(job), job['argv'][0])
    if _cmp_key(executable) == _cmp_key(Path(raw)):
        raise ValueError(where + ' executionReceipt cannot be the executable')
    # A wrapper would supervise the guard CLI, not the approved direct child.
    if any(Path(arg).name.casefold() in {'work_window_guard.py', 'work_window_guard_jobs_v2.py'} for arg in job['argv']) or any(
            arg.casefold().removeprefix('-m') in {'scripts.locality_automation.work_window_guard', 'work_window_guard',
                    'scripts.locality_automation.work_window_guard_jobs_v2', 'work_window_guard_jobs_v2'} for arg in job['argv']):
        raise ValueError(where + ' workWindow must launch the direct child, not a guard CLI wrapper')


def validate_plan(plan: dict) -> None:
    if not isinstance(plan, dict):
        raise ValueError('plan must be an object')
    if not _schema_ok(plan.get('schemaVersion')):
        raise ValueError('plan schemaVersion must be 1')
    governorate = plan.get('governorate')
    if not isinstance(governorate, str) or not governorate.strip():
        raise ValueError('plan governorate must be a non-empty string')
    jobs = plan.get('jobs')
    if not isinstance(jobs, list):
        raise ValueError('plan jobs must be a list')
    jobs_by_id: dict[str, dict] = {}
    for index, job in enumerate(jobs):
        where = 'jobs[' + str(index) + ']'
        if not isinstance(job, dict):
            raise ValueError(where + ' must be an object')
        job_id = job.get('id')
        if not isinstance(job_id, str) or not _SAFE_ID.match(job_id):
            raise ValueError(where + ' id must be a safe slug')
        if job_id in jobs_by_id:
            raise ValueError('duplicate job id: ' + job_id)
        jobs_by_id[job_id] = job
        kind = job.get('kind', DEFAULT_KIND)
        if not isinstance(kind, str) or kind not in JOB_KINDS:
            raise ValueError(where + ' kind is not allowed')
        job_governorate = job.get('governorate', governorate)
        if not isinstance(job_governorate, str) or not job_governorate.strip():
            raise ValueError(where + ' governorate must be a non-empty string')
        if job_governorate != governorate:
            raise ValueError(where + ' violates single active governorate')
        imported = _is_imported(job)
        if 'argv' in job:
            argv = job['argv']
            if not isinstance(argv, list) or any(not isinstance(item, str) for item in argv):
                raise ValueError(where + ' argv must be a list of strings')
            if any(not _valid_text(item) for item in argv):
                raise ValueError(where + ' argv contains an invalid entry')
            if not imported and not argv:
                raise ValueError(where + ' non-imported task requires argv')
        elif not imported:
            raise ValueError(where + ' non-imported task requires argv')
        if 'workWindow' in job:
            _validate_work_window(job, where)
        if 'cwd' in job:
            cwd = job['cwd']
            if not _valid_file_field(cwd):
                raise ValueError(where + ' cwd must be a non-empty string')
        elif not imported:
            raise ValueError(where + ' non-imported task requires cwd')
        if 'timeoutSeconds' in job:
            timeout = job['timeoutSeconds']
            if isinstance(timeout, bool) or not isinstance(timeout, (int, float)) or not math.isfinite(timeout) or timeout <= 0:
                raise ValueError(where + ' timeoutSeconds must be a positive number')
        inputs = job.get('inputs', [])
        if not isinstance(inputs, list):
            raise ValueError(where + ' inputs must be a list')
        for input_index, item in enumerate(inputs):
            input_where = where + '.inputs[' + str(input_index) + ']'
            if not isinstance(item, dict):
                raise ValueError(input_where + ' must be an object')
            if not _valid_file_field(item.get('file')):
                raise ValueError(input_where + ' file is invalid')
            if not _valid_sha(item.get('sha256')):
                raise ValueError(input_where + ' sha256 is invalid')
        outputs = job.get('outputs', [])
        if not isinstance(outputs, list):
            raise ValueError(where + ' outputs must be a list')
        for output_index, raw in enumerate(outputs):
            if not _valid_file_field(raw):
                raise ValueError(where + '.outputs[' + str(output_index) + '] is invalid')
        imported_outputs = job.get('importedOutputs', [])
        if not isinstance(imported_outputs, list):
            raise ValueError(where + ' importedOutputs must be a list')
        for imported_index, item in enumerate(imported_outputs):
            imported_where = where + '.importedOutputs[' + str(imported_index) + ']'
            if not isinstance(item, dict):
                raise ValueError(imported_where + ' must be an object')
            if not _valid_file_field(item.get('file')):
                raise ValueError(imported_where + ' file is invalid')
            if not _valid_sha(item.get('sha256')):
                raise ValueError(imported_where + ' sha256 is invalid')
        deps = job.get('deps', [])
        if not isinstance(deps, list) or any(not isinstance(item, str) or not _SAFE_ID.match(item) for item in deps):
            raise ValueError(where + ' deps must be a list of safe ids')
        if job_id in deps:
            raise ValueError(where + ' must not depend on itself')
        resources = job.get('resources', [])
        if not isinstance(resources, list) or any(not _valid_file_field(item) for item in resources):
            raise ValueError(where + ' resources must be a list of non-empty strings')
    for job_id, job in jobs_by_id.items():
        for dependency in job.get('deps', []):
            if dependency not in jobs_by_id:
                raise ValueError('job ' + job_id + ' has unknown dependency ' + dependency)
    _check_cycles(jobs_by_id)
    _check_path_collisions(jobs_by_id)


def _new_job_state(job: dict, job_hash: str, state_dir: Path) -> dict:
    job_id = job['id']
    return {
        'status': 'pending',
        'jobHash': job_hash,
        'inputPins': [],
        'outputPins': [],
        'startedAt': None,
        'finishedAt': None,
        'reason': None,
        'reasonCode': None,
        'log': str(state_dir / 'logs' / (job_id + '.log')),
        'kind': _effective_kind(job),
        'imported': _is_imported(job),
    }


def _mark_stale(state: dict, reason: str, code: str) -> None:
    state['status'] = 'stale'
    state['reason'] = reason
    state['reasonCode'] = code
    if not state.get('finishedAt'):
        state['finishedAt'] = _now()


def _mark_held(state: dict, reason: str, code: str) -> None:
    state['status'] = 'held'
    state['reason'] = reason
    state['reasonCode'] = code
    if not state.get('finishedAt'):
        state['finishedAt'] = _now()


def _compute_input_pins(job: dict, base: Path) -> tuple[list[dict] | None, str | None]:
    pins: list[dict] = []
    for item in job.get('inputs', []):
        raw = item['file']
        path = _resolve(base, raw)
        if not path.is_file():
            return None, 'missing input: ' + raw
        try:
            actual = _sha256_file(path)
        except OSError as exc:
            return None, 'could not read input ' + raw + ': ' + str(exc)
        if actual.lower() != item['sha256'].lower():
            return None, 'input hash mismatch: ' + raw
        pins.append({'file': raw, 'path': str(path), 'sha256': actual})
    return pins, None


def _pin_imported(job: dict, base: Path) -> tuple[list[dict] | None, str | None]:
    pins: list[dict] = []
    for item in job.get('importedOutputs', []):
        raw = item['file']
        path = _resolve(base, raw)
        if not _is_regular_nonempty(path):
            return None, 'imported output missing or not a regular nonempty file: ' + raw
        try:
            actual = _sha256_file(path)
            size = path.stat().st_size
        except OSError as exc:
            return None, 'could not read imported output ' + raw + ': ' + str(exc)
        if actual.lower() != item['sha256'].lower():
            return None, 'imported output hash mismatch: ' + raw
        pins.append({'file': raw, 'path': str(path), 'sha256': actual, 'size': size})
    for raw in job.get('outputs', []):
        path = _resolve(base, raw)
        if not _is_regular_nonempty(path):
            return None, 'imported job output missing or not a regular nonempty file: ' + raw
        try:
            actual = _sha256_file(path)
            size = path.stat().st_size
        except OSError as exc:
            return None, 'could not read imported job output ' + raw + ': ' + str(exc)
        pins.append({'file': raw, 'path': str(path), 'sha256': actual, 'size': size})
    return pins, None


def _pin_outputs_after_run(job: dict, base: Path) -> tuple[list[dict] | None, str | None]:
    pins: list[dict] = []
    for raw, path in _output_entries(job, base):
        if not _is_regular_nonempty(path):
            return None, 'declared output missing, empty, or not regular: ' + raw
        try:
            actual = _sha256_file(path)
            size = path.stat().st_size
        except OSError as exc:
            return None, 'could not read declared output ' + raw + ': ' + str(exc)
        pins.append({'file': raw, 'path': str(path), 'sha256': actual, 'size': size})
    return pins, None


def _output_reuse_status(job: dict, pins: list[dict], base: Path) -> str:
    entries = _output_entries(job, base)
    if not entries:
        return 'ok' if not pins else 'stale'
    pin_by_key: dict[str, dict] = {}
    for pin in pins:
        if isinstance(pin, dict) and isinstance(pin.get('path'), str):
            pin_by_key[_cmp_key(Path(pin['path']))] = pin
    missing = 0
    for raw, path in entries:
        pin = pin_by_key.get(_cmp_key(path))
        if pin is None:
            return 'stale'
        if not path.exists() and not path.is_symlink():
            missing += 1
            continue
        if not _is_regular_nonempty(path):
            return 'stale'
        try:
            actual = _sha256_file(path)
        except OSError:
            return 'stale'
        if actual.lower() != str(pin.get('sha256', '')).lower():
            return 'stale'
    if missing == len(entries):
        return 'missing_all'
    if missing:
        return 'stale'
    return 'ok'


def _outputs_absent(job: dict, base: Path) -> tuple[bool, str | None]:
    for raw, path in _output_entries(job, base):
        if path.exists() or path.is_symlink():
            return False, raw
    return True, None


def _guard_receipt_error(job: dict, base: Path, expected: Any) -> str | None:
    """Require this direct execution's completed exit-zero receipt, not labels."""
    if not isinstance(expected, dict) or expected.get('status') != 'COMPLETED' or expected.get('exitCode') != 0 or expected.get('timedOut') is not False:
        return 'guard execution did not complete successfully'
    try:
        actual = _read_json(_resolve(base, job['workWindow']['executionReceipt']))
        if actual != expected or actual.get('argv') != job['argv'] or actual.get('cwd') != str(_job_cwd(job, base)):
            return 'guard receipt binding changed'
    except (OSError, ValueError, TypeError) as exc:
        return 'guard receipt unavailable: ' + str(exc)
    return None


def _prepare_state(state: dict, plan: dict, state_dir: Path) -> bool:
    changed = False
    for job in plan['jobs']:
        job_id = job['id']
        job_hash = _job_hash(job)
        current = state['jobs'].get(job_id)
        if not isinstance(current, dict):
            state['jobs'][job_id] = _new_job_state(job, job_hash, state_dir)
            changed = True
            continue
        if current.get('jobHash') != job_hash:
            _mark_stale(current, 'job definition changed; create a new revision/id', 'definition')
            changed = True
            continue
        current.setdefault('kind', _effective_kind(job))
        current.setdefault('imported', _is_imported(job))
        current.setdefault('log', str(state_dir / 'logs' / (job_id + '.log')))
        current.setdefault('inputPins', [])
        current.setdefault('outputPins', [])
        current.setdefault('startedAt', None)
        current.setdefault('finishedAt', None)
        current.setdefault('reason', None)
        current.setdefault('reasonCode', None)
        status = current.get('status')
        if status == 'running':
            _mark_held(current, 'stale running job held', 'stale_running')
            changed = True
        elif status == 'held' and current.get('reasonCode') == 'stale_running':
            pass
        elif status == 'held' and current.get('reasonCode') == 'permission':
            current['status'] = 'pending'
            changed = True
        elif status in {'failed', 'stale', 'held'}:
            pass  # Never retry a failed or uncertain paid/device task implicitly.
        elif status not in {'pending', 'success', 'imported'}:
            current['status'] = 'pending'
            current['reason'] = None
            current['reasonCode'] = None
            current['startedAt'] = None
            current['finishedAt'] = None
            changed = True
    for job in plan['jobs']:
        job_id = job['id']
        current = state['jobs'][job_id]
        if current.get('status') == 'held' and current.get('reasonCode') == 'stale_running':
            continue
        base = _base_for(job)
        status = current.get('status')
        if status in {'success', 'imported'}:
            pins, error = _compute_input_pins(job, base)
            if error is not None:
                _mark_stale(current, error, 'input')
                changed = True
                continue
            if pins != current.get('inputPins'):
                _mark_stale(current, 'input pins changed', 'input')
                changed = True
                continue
            if 'workWindow' in job:
                receipt_error = _guard_receipt_error(job, base, current.get('guardExecution'))
                if receipt_error:
                    _mark_stale(current, receipt_error, 'guard')
                    changed = True
                    continue
            reuse = _output_reuse_status(job, current.get('outputPins', []), base)
            if reuse == 'ok':
                continue
            _mark_stale(current, 'output pins are stale', 'output')
            changed = True
            continue
        if status == 'pending':
            if any(state['jobs'][d].get('status') not in SATISFIED for d in job.get('deps', [])):
                continue  # Produced inputs become available only after dependencies.
            pins, error = _compute_input_pins(job, base)
            if error is not None:
                _mark_stale(current, error, 'input')
                changed = True
                continue
            if pins != current.get('inputPins'):
                current['inputPins'] = pins
                changed = True
            if _is_imported(job):
                imported_pins, imported_error = _pin_imported(job, base)
                if imported_error is not None:
                    _mark_stale(current, imported_error, 'import')
                    changed = True
                    continue
                current['outputPins'] = imported_pins
                changed = True
            else:
                absent, existing = _outputs_absent(job, base)
                if not absent:
                    _mark_stale(current, 'output already exists: ' + existing, 'output')
                    changed = True
                    continue
    # A previously completed descendant is no longer current if its upstream is stale.
    for _ in plan['jobs']:
        propagated = False
        for job in plan['jobs']:
            current = state['jobs'][job['id']]
            if current.get('status') in SATISFIED and any(
                    state['jobs'][d].get('status') not in SATISFIED for d in job.get('deps', [])):
                _mark_stale(current, 'upstream evidence no longer current', 'upstream')
                propagated = changed = True
        if not propagated:
            break
    return changed


def _write_log_header(handle: Any, job: dict, cwd: Path) -> None:
    lines = [
        'job=' + job['id'],
        'cwd=' + str(cwd),
        'argv=' + ' '.join(job.get('argv', [])),
    ]
    for line in lines:
        handle.write((line + os.linesep).encode('utf-8'))


def _execute_job(job: dict, base: Path, log_path: Path, timeout: float | None) -> dict:
    pins, error = _compute_input_pins(job, base)
    if error is not None:
        return {'status': 'stale', 'inputPins': [], 'outputPins': [], 'reasonCode': 'input', 'reason': error}
    absent, existing = _outputs_absent(job, base)
    if not absent:
        return {'status': 'stale', 'inputPins': pins, 'outputPins': [], 'reasonCode': 'output', 'reason': 'output already exists: ' + existing}
    cwd = _job_cwd(job, base)
    if not cwd.is_dir():
        return {'status': 'failed', 'inputPins': pins, 'outputPins': [], 'reasonCode': 'cwd', 'reason': 'cwd is not a directory'}
    argv = list(job.get('argv', []))
    guard_evidence = {}
    try:
        log_path.parent.mkdir(parents=True, exist_ok=True)
        with open(log_path, 'ab') as handle:
            _write_log_header(handle, job, cwd)
            try:
                if 'workWindow' in job:
                    handle.flush()
                    window = job['workWindow']
                    guarded = work_window_guard.launch_guarded(
                        window['safeStartUtc'], window['hardDeadlineUtc'], argv,
                        window['minimumRemainingSeconds'], record=window['executionReceipt'],
                        cwd=str(cwd), stdout=handle, stderr=handle, timeout_seconds=timeout,
                    )
                    guard_evidence = {'guardExecution': guarded}
                    exit_code = guarded['exitCode']
                    if guarded['status'] != 'COMPLETED':
                        return {'status': 'failed', 'inputPins': pins, 'outputPins': [],
                                'reasonCode': 'timeout' if guarded['timedOut'] else 'guard',
                                'reason': guarded.get('reason'), 'exitCode': exit_code, **guard_evidence}
                else:
                    completed = subprocess.run(
                        argv, cwd=str(cwd), stdout=handle, stderr=subprocess.STDOUT,
                        shell=False, timeout=timeout, check=False,
                    )
                    exit_code = completed.returncode
            except subprocess.TimeoutExpired:
                return {'status': 'failed', 'inputPins': pins, 'outputPins': [], 'reasonCode': 'timeout', 'reason': 'command timed out', 'exitCode': None}
    except (OSError, ValueError) as exc:
        return {'status': 'failed', 'inputPins': pins, 'outputPins': [], 'reasonCode': 'spawn', 'reason': str(exc)}
    if exit_code != 0:
        return {'status': 'failed', 'inputPins': pins, 'outputPins': [], 'reasonCode': 'exit', 'reason': 'exit status ' + str(exit_code), 'exitCode': exit_code, **guard_evidence}
    after_pins, after_error = _compute_input_pins(job, base)
    if after_error or after_pins != pins:
        return {'status': 'stale', 'inputPins': pins, 'outputPins': [], 'reasonCode': 'input', 'reason': after_error or 'input changed during execution', **guard_evidence}
    if 'workWindow' in job:
        receipt_error = _guard_receipt_error(job, base, guard_evidence.get('guardExecution'))
        if receipt_error:
            return {'status': 'failed', 'inputPins': pins, 'outputPins': [], 'reasonCode': 'guard', 'reason': receipt_error, **guard_evidence}
    output_pins, output_error = _pin_outputs_after_run(job, base)
    if output_error is not None:
        return {'status': 'failed', 'inputPins': pins, 'outputPins': [], 'reasonCode': 'output', 'reason': output_error, 'exitCode': exit_code, **guard_evidence}
    return {'status': 'success', 'inputPins': pins, 'outputPins': output_pins, 'reasonCode': None, 'reason': None, 'exitCode': exit_code, **guard_evidence}


def _import_job(job: dict, base: Path) -> dict:
    inputs, input_error = _compute_input_pins(job, base)
    if input_error:
        return {'status': 'stale', 'reasonCode': 'input', 'reason': input_error}
    pins, error = _pin_imported(job, base)
    if error is not None:
        return {'status': 'stale', 'inputPins': [], 'outputPins': [], 'reasonCode': 'import', 'reason': error}
    return {'status': 'imported', 'inputPins': inputs, 'outputPins': pins, 'reasonCode': None, 'reason': None}


def _apply_result(state: dict, job_id: str, result: dict) -> None:
    current = state['jobs'][job_id]
    for key in ('inputPins', 'outputPins', 'reasonCode', 'reason', 'exitCode', 'guardExecution'):
        if key in result:
            current[key] = result[key]
    current['status'] = result['status']
    current['finishedAt'] = _now()
    if current['status'] in SATISFIED:
        current['reason'] = None
        current['reasonCode'] = None


def _timeout(job: dict) -> float | None:
    value = job.get('timeoutSeconds')
    if value is None:
        return None
    return float(value)


def _run_scheduler(state: dict, plan: dict, jobs_by_id: dict, workers: int, state_dir: Path) -> None:
    job_ids = [job['id'] for job in plan['jobs']]
    deps = {job_id: list(dict.fromkeys(jobs_by_id[job_id].get('deps', []))) for job_id in job_ids}
    resources = {job_id: set(jobs_by_id[job_id].get('resources', [])) for job_id in job_ids}
    bases = {job_id: _base_for(jobs_by_id[job_id]) for job_id in job_ids}
    running: dict[concurrent.futures.Future, str] = {}
    owners: dict[str, str] = {}
    executor = concurrent.futures.ThreadPoolExecutor(max_workers=workers)
    try:
        while True:
            if _read_paused(state_dir) and not running:
                break
            changed = False
            for job_id in job_ids:
                current = state['jobs'][job_id]
                if current.get('status') == 'held' and current.get('reasonCode') == 'upstream' and all(
                        state['jobs'][dep].get('status') in SATISFIED for dep in deps[job_id]):
                    current.update(status='pending', reason=None, reasonCode=None, finishedAt=None)
                    changed = True
                if current.get('status') != 'pending':
                    continue
                dep_statuses = [state['jobs'][dep].get('status') for dep in deps[job_id]]
                if any(status in BLOCKING for status in dep_statuses):
                    _mark_held(current, 'upstream not successful', 'upstream')
                    changed = True
            if changed:
                _write_state(state_dir, state)
                continue
            dispatched = False
            for job_id in job_ids:
                if _read_paused(state_dir):
                    break
                if len(running) >= workers:
                    break
                current = state['jobs'][job_id]
                if current.get('status') != 'pending':
                    continue
                dep_statuses = [state['jobs'][dep].get('status') for dep in deps[job_id]]
                if any(status not in SATISFIED for status in dep_statuses):
                    continue
                if resources[job_id] & set(owners):
                    continue
                job = jobs_by_id[job_id]
                if _is_imported(job):
                    current['startedAt'] = _now()
                    result = _import_job(job, bases[job_id])
                    _apply_result(state, job_id, result)
                    _write_state(state_dir, state)
                    dispatched = True
                    continue
                current['status'] = 'running'
                current['startedAt'] = _now()
                current['finishedAt'] = None
                current['reason'] = None
                current['reasonCode'] = None
                _write_state(state_dir, state)
                future = executor.submit(_execute_job, job, bases[job_id], Path(current['log']), _timeout(job))
                running[future] = job_id
                for resource in resources[job_id]:
                    owners[resource] = job_id
                dispatched = True
            if not running:
                if not dispatched:
                    break
                continue
            done, _ = concurrent.futures.wait(list(running.keys()), timeout=0.5, return_when=concurrent.futures.FIRST_COMPLETED)
            if not done:
                continue
            for future in done:
                job_id = running.pop(future)
                try:
                    result = future.result()
                except Exception as exc:
                    result = {'status': 'failed', 'reasonCode': 'worker', 'reason': str(exc)}
                _apply_result(state, job_id, result)
                for resource in resources[job_id]:
                    if owners.get(resource) == job_id:
                        del owners[resource]
                _write_state(state_dir, state)
    finally:
        executor.shutdown(wait=True)
        # Drain results even if pause, Ctrl+C, or a state-write error interrupted dispatch.
        for future, job_id in running.items():
            try:
                result = future.result()
            except Exception as exc:
                result = {'status': 'failed', 'reasonCode': 'worker', 'reason': str(exc)}
            _apply_result(state, job_id, result)
        _write_state(state_dir, state)


class _RunnerLock:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.fd: int | None = None

    def __enter__(self) -> _RunnerLock:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        descriptor = os.open(str(self.path), os.O_RDWR | os.O_CREAT, 0o644)
        try:
            if os.name == 'nt':
                import msvcrt
                if os.fstat(descriptor).st_size == 0:
                    os.write(descriptor, b'0')
                os.lseek(descriptor, 0, os.SEEK_SET)
                msvcrt.locking(descriptor, msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as exc:
            os.close(descriptor)
            raise RuntimeError('another runner holds the state lock') from exc
        self.fd = descriptor
        try:
            os.lseek(descriptor, 0, os.SEEK_SET)
            os.write(descriptor, ('pid=' + str(os.getpid())).encode('utf-8'))
            os.fsync(descriptor)
        except OSError:
            self.__exit__(None, None, None)
            raise
        return self

    def __exit__(self, exc_type: Any, exc: Any, traceback: Any) -> bool:
        descriptor = self.fd
        self.fd = None
        if descriptor is not None:
            try:
                if os.name == 'nt':
                    import msvcrt
                    os.lseek(descriptor, 0, os.SEEK_SET)
                    msvcrt.locking(descriptor, msvcrt.LK_UNLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(descriptor, fcntl.LOCK_UN)
            finally:
                os.close(descriptor)
        return False


def _normalise_allowed(allowed: Any) -> set[str]:
    if allowed is None:
        raise ValueError('allowed kinds must be provided')
    if isinstance(allowed, str):
        raise ValueError('allowed must be a set of job kinds')
    try:
        values = set(allowed)
    except TypeError as exc:
        raise ValueError('allowed must be a set of job kinds') from exc
    if not values:
        raise ValueError('allowed kinds must not be empty')
    for value in values:
        if not isinstance(value, str) or value not in JOB_KINDS:
            raise ValueError('unsupported allowed kind: ' + str(value))
    return values


def run(plan: dict, state_dir: Path | str, allowed: set[str], workers: int = 3) -> dict:
    state_path = _as_path(state_dir, 'state_dir')
    if not isinstance(workers, int) or isinstance(workers, bool) or workers < 1:
        raise ValueError('workers must be a positive integer')
    allowed_set = _normalise_allowed(allowed)
    validate_plan(plan)
    jobs_by_id = {job['id']: job for job in plan['jobs']}
    state_path.mkdir(parents=True, exist_ok=True)
    (state_path / 'logs').mkdir(parents=True, exist_ok=True)
    with _RunnerLock(state_path / 'runner.lock'):
        state = _load_state(state_path)
        if _prepare_state(state, plan, state_path):
            _write_state(state_path, state)
        for job in plan['jobs']:
            current = state['jobs'][job['id']]
            if current['status'] == 'pending' and not _is_imported(job) and _effective_kind(job) not in allowed_set:
                _mark_held(current, 'kind not enabled: ' + _effective_kind(job), 'permission')
        _write_state(state_path, state)
        _run_scheduler(state, plan, jobs_by_id, workers, state_path)
        _write_state(state_path, state)
        return status(state_path)


def status(state_dir: Path | str) -> dict:
    state_path = _as_path(state_dir, 'state_dir')
    if not state_path.exists():
        return {'schemaVersion': SCHEMA_VERSION, 'paused': False, 'jobs': {}}
    state = _load_state(state_path) if (state_path / 'state.json').exists() else _default_state()
    return {'schemaVersion': SCHEMA_VERSION, 'paused': _read_paused(state_path), 'jobs': state['jobs']}


def set_paused(state_dir: Path | str, paused: bool) -> dict:
    if not isinstance(paused, bool):
        raise ValueError('paused must be a boolean')
    state_path = _as_path(state_dir, 'state_dir')
    state_path.mkdir(parents=True, exist_ok=True)
    _atomic_write_json(state_path / 'control.json', {'schemaVersion': SCHEMA_VERSION, 'paused': paused})
    return status(state_path)
