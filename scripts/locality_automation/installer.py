#!/usr/bin/env python3
'''Transactional locality catalog installer.

Public API:
    install(manifest, repo, transaction_dir, compile_argv, compile_cwd) -> dict
    recover(transaction_dir, repo) -> dict

The installer trusts a validated upstream receipt only after pinning its
sha256, status, qualification, issue state, and exact source/destination/
before pins.  It does not decide policy truth.
'''
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import tempfile
import time

JOURNAL_VERSION = 1
ALLOWED_ASSETS = frozenset([
    'android-app/app/src/main/assets/neighborhoods.json',
    'android-app/app/src/main/assets/neighborhoods.bin',
    'android-app/app/src/main/assets/gouvernorats.json',
    'android-app/app/src/main/assets/locality-display-names.json',
    'android-app/app/src/main/assets/retired-localities.json',
])
SCRIPTS_PREFIX = 'scripts/neighborhoods/'
HEX64 = re.compile(r'^[0-9a-f]{64}$')


class InstallerError(Exception):
    pass


def _now():
    return time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())


def _sha256_file(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def _sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def _current_hash(path):
    if os.path.isfile(path):
        return _sha256_file(path)
    if os.path.lexists(path):
        return '<not-a-regular-file>'
    return None


def _is_within(path, parent):
    try:
        p = os.path.normcase(os.path.realpath(path))
        q = os.path.normcase(os.path.realpath(parent))
        return os.path.commonpath([p, q]) == q
    except ValueError:
        return False


def _is_reparse(path):
    try:
        st = os.lstat(path)
    except OSError:
        return False
    attrs = getattr(st, 'st_file_attributes', 0)
    flag = getattr(stat, 'FILE_ATTRIBUTE_REPARSE_POINT', 0)
    return bool(attrs & flag)


def _check_no_symlink_escape(repo, target):
    repo_real = os.path.realpath(repo)
    target_abs = os.path.abspath(target)
    if not _is_within(target_abs, repo_real):
        raise InstallerError('target outside repo')
    rel = os.path.relpath(target_abs, repo_real)
    cur = repo_real
    for part in rel.split(os.sep):
        if part in ('', os.curdir):
            continue
        cur = os.path.join(cur, part)
        if os.path.lexists(cur) and (os.path.islink(cur) or _is_reparse(cur)):
            raise InstallerError('symlink/reparse escape: ' + cur)
    if os.path.exists(target_abs) and not _is_within(os.path.realpath(target_abs), repo_real):
        raise InstallerError('target realpath outside repo')
    parent = os.path.dirname(target_abs)
    if os.path.exists(parent) and not _is_within(os.path.realpath(parent), repo_real):
        raise InstallerError('parent realpath outside repo')


def _resolve_dest(repo, dest):
    if not isinstance(dest, str) or not dest or chr(92) in dest:
        raise InstallerError('invalid destination')
    if dest.startswith('/') or re.match(r'^[A-Za-z]:', dest):
        raise InstallerError('absolute destination')
    parts = dest.split('/')
    if any(p in ('', '.', '..') for p in parts):
        raise InstallerError('invalid destination path')
    rel = '/'.join(parts)
    if rel in ALLOWED_ASSETS:
        pass
    elif rel.startswith(SCRIPTS_PREFIX) and len(rel) > len(SCRIPTS_PREFIX):
        rest = rel[len(SCRIPTS_PREFIX):]
        if not (rest.endswith('.json') or rest.endswith('.geojson')):
            raise InstallerError('destination extension not allowed: ' + rel)
    else:
        raise InstallerError('destination not allowed: ' + rel)
    target = os.path.abspath(os.path.join(repo, *parts))
    _check_no_symlink_escape(repo, target)
    return rel, target


def _require_keys(obj, required, where):
    if not isinstance(obj, dict):
        raise InstallerError(where + ' must be object')
    missing = set(required) - set(obj)
    if missing:
        raise InstallerError(where + ' missing fields: ' + ', '.join(sorted(missing)))


def _check_exact_keys(obj, allowed, where):
    _require_keys(obj, allowed, where)
    extra = set(obj) - set(allowed)
    if extra:
        raise InstallerError(where + ' unknown fields: ' + ', '.join(sorted(extra)))


def _load_json(path):
    with open(path, 'r', encoding='utf-8') as f:
        return json.load(f)


def _atomic_write_json(path, obj):
    d = os.path.dirname(path) or '.'
    os.makedirs(d, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix='.journal-', suffix='.tmp', dir=d)
    with os.fdopen(fd, 'w', encoding='utf-8') as f:
        json.dump(obj, f, ensure_ascii=False, sort_keys=True, indent=2)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)
    _fsync_dir(d)


def _fsync_dir(path):
    try:
        fd = os.open(path, os.O_RDONLY)
    except OSError:
        return
    try:
        os.fsync(fd)
    except OSError:
        pass
    finally:
        os.close(fd)


def _write_journal(td, journal):
    _atomic_write_json(os.path.join(td, 'journal.json'), journal)


def _validate_receipt(receipt, infos):
    if not isinstance(receipt, dict):
        raise InstallerError('validation receipt must be object')
    _require_keys(receipt, ['status', 'qualification', 'files', 'issues', 'unresolvedCaseIds'], 'validation receipt')
    if receipt['status'] != 'READY_TO_INSTALL':
        raise InstallerError('validation status not READY_TO_INSTALL')
    if not isinstance(receipt['qualification'], str):
        raise InstallerError('validation qualification must be string')
    if receipt['issues'] != []:
        raise InstallerError('validation issues must be empty')
    if receipt['unresolvedCaseIds'] != []:
        raise InstallerError('validation unresolvedCaseIds must be empty')
    vfiles = receipt['files']
    if not isinstance(vfiles, list):
        raise InstallerError('validation files must be list')
    if len(vfiles) != len(infos):
        raise InstallerError('validation files length mismatch')
    expected = {i['destination']: (i['sourceSha256'], i['beforeSha256']) for i in infos}
    got = {}
    for j, vf in enumerate(vfiles):
        where = 'validation.files[%d]' % j
        _check_exact_keys(vf, ['sourceSha256', 'destination', 'beforeSha256'], where)
        dest = vf['destination']
        if not isinstance(dest, str):
            raise InstallerError(where + '.destination invalid')
        if dest in got:
            raise InstallerError('validation duplicate destination')
        if dest not in expected:
            raise InstallerError('validation destination mismatch: ' + dest)
        got[dest] = (vf['sourceSha256'], vf['beforeSha256'])
    if got != expected:
        raise InstallerError('validation files do not match manifest')


def _read_manifest(manifest, repo):
    if not isinstance(manifest, dict):
        raise InstallerError('manifest must be object')
    _check_exact_keys(manifest, ['schemaVersion', 'files', 'validation'], 'manifest')
    if manifest['schemaVersion'] != 1:
        raise InstallerError('schemaVersion must be 1')
    if not isinstance(manifest['files'], list) or not manifest['files']:
        raise InstallerError('manifest.files must be a non-empty list')
    validation = manifest['validation']
    _check_exact_keys(validation, ['file', 'sha256'], 'manifest.validation')
    vf = validation['file']
    vsha = validation['sha256']
    if not isinstance(vf, str) or not vf:
        raise InstallerError('validation.file invalid')
    if not isinstance(vsha, str) or not HEX64.match(vsha):
        raise InstallerError('validation.sha256 invalid')
    infos = []
    seen = set()
    for i, ent in enumerate(manifest['files']):
        where = 'files[%d]' % i
        _check_exact_keys(ent, ['source', 'destination', 'beforeSha256'], where)
        src = ent['source']
        _check_exact_keys(src, ['file', 'sha256'], where + '.source')
        sf = src['file']
        ssh = src['sha256']
        if not isinstance(sf, str) or not sf:
            raise InstallerError(where + '.source.file invalid')
        if not isinstance(ssh, str) or not HEX64.match(ssh):
            raise InstallerError(where + '.source.sha256 invalid')
        dest = ent['destination']
        rel, target = _resolve_dest(repo, dest)
        if rel in seen:
            raise InstallerError('duplicate destination: ' + rel)
        seen.add(rel)
        before = ent['beforeSha256']
        if before is not None and (not isinstance(before, str) or not HEX64.match(before)):
            raise InstallerError(where + '.beforeSha256 invalid')
        source = os.path.abspath(sf) if os.path.isabs(sf) else os.path.abspath(os.path.join(repo, sf))
        if not os.path.isfile(source):
            raise InstallerError('source is not a regular file: ' + source)
        if os.path.realpath(source) == os.path.realpath(target):
            raise InstallerError('source equals destination: ' + rel)
        if _sha256_file(source) != ssh:
            raise InstallerError('source sha256 mismatch: ' + source)
        if before is None:
            if os.path.lexists(target):
                raise InstallerError('beforeSha256 null but target exists: ' + rel)
        else:
            if not os.path.isfile(target):
                raise InstallerError('target missing: ' + rel)
            if _sha256_file(target) != before:
                raise InstallerError('beforeSha256 mismatch: ' + rel)
        parent = os.path.dirname(target)
        if not os.path.isdir(parent):
            raise InstallerError('target parent missing: ' + rel)
        infos.append({
            'destination': rel,
            'target': target,
            'source': source,
            'sourceSha256': ssh,
            'beforeSha256': before,
            'afterSha256': ssh,
        })
    vpath = os.path.abspath(vf) if os.path.isabs(vf) else os.path.abspath(os.path.join(repo, vf))
    if not os.path.isfile(vpath):
        raise InstallerError('validation file missing: ' + vpath)
    if _sha256_file(vpath) != vsha:
        raise InstallerError('validation sha256 mismatch')
    receipt = _load_json(vpath)
    _validate_receipt(receipt, infos)
    return infos


def _acquire_lock(repo):
    key = hashlib.sha256(os.path.normcase(os.path.realpath(repo)).encode('utf-8')).hexdigest()[:32]
    lockpath = os.path.join(tempfile.gettempdir(), 'locality-installer-%s.lock' % key)
    f = open(lockpath, 'a+b')
    if os.name == 'nt':
        import msvcrt
        if os.path.getsize(lockpath) == 0:
            f.write(bytes([0]))
            f.flush()
        f.seek(0)
        try:
            msvcrt.locking(f.fileno(), msvcrt.LK_NBLCK, 1)
        except OSError:
            f.close()
            raise InstallerError('repo lock held')
    else:
        import fcntl
        try:
            fcntl.flock(f.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            f.close()
            raise InstallerError('repo lock held')
    return f


def _release_lock(f):
    if f is None:
        return
    try:
        if os.name == 'nt':
            import msvcrt
            f.seek(0)
            msvcrt.locking(f.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl
            fcntl.flock(f.fileno(), fcntl.LOCK_UN)
    except Exception:
        pass
    try:
        f.close()
    except Exception:
        pass


def _create_td(td, repo):
    td_abs = os.path.abspath(td)
    repo_abs = os.path.abspath(repo)
    if os.path.exists(td_abs):
        raise InstallerError('transaction dir already exists')
    if _is_within(td_abs, repo_abs) or _is_within(repo_abs, td_abs):
        raise InstallerError('transaction dir must be outside repo')
    os.makedirs(td_abs, exist_ok=False)
    os.makedirs(os.path.join(td_abs, 'backups'), exist_ok=True)
    return td_abs


def _init_journal(td, repo, infos, compile_argv, compile_cwd):
    changes = []
    for info in infos:
        backup_rel = None
        if info['beforeSha256'] is not None:
            backup_rel = os.path.join('backups', *info['destination'].split('/'))
        changes.append({
            'destination': info['destination'],
            'target': info['target'],
            'source': info['source'],
            'beforeSha256': info['beforeSha256'],
            'afterSha256': info['afterSha256'],
            'backupRel': backup_rel,
            'backupSha256': None,
            'state': 'PLANNED',
            'temp': None,
        })
    return {
        'journalVersion': JOURNAL_VERSION,
        'repo': os.path.realpath(repo),
        'transactionDir': td,
        'created': _now(),
        'status': 'PREPARING',
        'compile': {
            'argv': list(compile_argv) if compile_argv else [],
            'cwd': compile_cwd,
            'stdoutLog': os.path.join(td, 'compile.stdout.log'),
            'stderrLog': os.path.join(td, 'compile.stderr.log'),
        },
        'changes': changes,
        'error': None,
    }


def _make_backup(td, journal, ch):
    _check_no_symlink_escape(journal['repo'], ch['target'])
    if ch['beforeSha256'] is None:
        ch['state'] = 'BACKED_UP'
        return
    bpath = os.path.join(td, ch['backupRel'])
    os.makedirs(os.path.dirname(bpath), exist_ok=True)
    shutil.copy2(ch['target'], bpath)
    ch['originalMode'] = stat.S_IMODE(os.stat(ch['target']).st_mode)
    with open(bpath, 'rb+') as f:
        os.fsync(f.fileno())
    _fsync_dir(os.path.dirname(bpath))
    h = _sha256_file(bpath)
    if h != ch['beforeSha256']:
        raise InstallerError('backup sha mismatch: ' + ch['destination'])
    ch['backupSha256'] = h
    ch['state'] = 'BACKED_UP'


def _check_target(journal, ch):
    _check_no_symlink_escape(journal['repo'], ch['target'])
    target = ch['target']
    before = ch['beforeSha256']
    if before is None:
        if os.path.lexists(target):
            raise InstallerError('target appeared: ' + ch['destination'])
    else:
        if not os.path.isfile(target):
            raise InstallerError('target missing: ' + ch['destination'])
        if _sha256_file(target) != before:
            raise InstallerError('target changed: ' + ch['destination'])


def _stage_and_replace(td, journal, ch):
    _check_target(journal, ch)
    with open(ch['source'], 'rb') as f:
        data = f.read()
    if _sha256_bytes(data) != ch['afterSha256']:
        raise InstallerError('source changed: ' + ch['destination'])
    target = ch['target']
    d = os.path.dirname(target)
    fd, tmp = tempfile.mkstemp(prefix='.' + os.path.basename(target) + '.locality-', suffix='.tmp', dir=d)
    ch['temp'] = tmp
    ch['state'] = 'STAGING'
    _write_journal(td, journal)
    with os.fdopen(fd, 'wb') as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())
    if _sha256_file(tmp) != ch['afterSha256']:
        raise InstallerError('staged hash mismatch: ' + ch['destination'])
    ch['state'] = 'STAGED'
    _write_journal(td, journal)
    _check_no_symlink_escape(journal['repo'], target)
    ch['state'] = 'REPLACING'
    _write_journal(td, journal)
    _check_target(journal, ch)
    if ch['beforeSha256'] is None:
        os.link(tmp, target)  # Exclusive create: never replace a newly appeared file.
        os.unlink(tmp)
    else:
        if ch.get('originalMode') is not None:
            os.chmod(tmp, ch['originalMode'])
        os.replace(tmp, target)
    _fsync_dir(d)
    ch['temp'] = None
    ch['state'] = 'REPLACED'
    _write_journal(td, journal)
    if _current_hash(target) != ch['afterSha256']:
        raise InstallerError('installed hash mismatch: ' + ch['destination'])
    ch['state'] = 'VERIFIED'
    _write_journal(td, journal)


def _run_compile(journal, td):
    argv = list(journal['compile']['argv'])
    if not argv:
        return
    cwd = journal['compile']['cwd']
    if not cwd or not os.path.isdir(cwd):
        raise InstallerError('compile cwd invalid')
    if os.name == 'nt' and argv[0].lower().endswith(('.bat', '.cmd')):
        argv = [os.environ.get('ComSpec', 'cmd.exe'), '/c'] + argv
    out_path = journal['compile']['stdoutLog']
    err_path = journal['compile']['stderrLog']
    with open(out_path, 'wb') as fo, open(err_path, 'wb') as fe:
        p = subprocess.run(argv, cwd=cwd, shell=False, stdout=fo, stderr=fe, check=False)
        fo.flush()
        os.fsync(fo.fileno())
        fe.flush()
        os.fsync(fe.fileno())
    if p.returncode != 0:
        raise InstallerError('compile failed with exit code %d' % p.returncode)


def _rollback_change(td, ch, repo):
    _check_no_symlink_escape(repo, ch['target'])
    target = ch['target']
    before = ch['beforeSha256']
    after = ch['afterSha256']
    cur = _current_hash(target)
    if cur == before or (before is None and cur is None):
        ch['state'] = 'ROLLED_BACK'
        return None
    if cur != after:
        ch['state'] = 'CONFLICT'
        return 'external edit/conflict: ' + ch['destination']
    owned = ch.get('state') in ('REPLACED', 'VERIFIED')
    temp_path = ch.get('temp')
    if ch.get('state') == 'REPLACING' and temp_path:
        owned = not os.path.lexists(temp_path)
        if before is None and os.path.isfile(temp_path):
            owned = os.path.samefile(temp_path, target)
    if not owned:
        ch['state'] = 'CONFLICT'
        return 'replacement ownership uncertain; leaving file untouched: ' + ch['destination']
    if before is None:
        try:
            os.unlink(target)
            _fsync_dir(os.path.dirname(target))
            ch['state'] = 'ROLLED_BACK'
            return None
        except OSError as e:
            ch['state'] = 'CONFLICT'
            return 'unlink failed: %s: %s' % (ch['destination'], e)
    bpath = os.path.join(td, ch['backupRel'])
    if not os.path.isfile(bpath) or _sha256_file(bpath) != before:
        ch['state'] = 'CONFLICT'
        return 'backup missing/corrupt: ' + ch['destination']
    try:
        d = os.path.dirname(target)
        fd, tmp = tempfile.mkstemp(prefix='.' + os.path.basename(target) + '.rollback-', suffix='.tmp', dir=d)
        with open(bpath, 'rb') as rf, os.fdopen(fd, 'wb') as wf:
            shutil.copyfileobj(rf, wf)
            wf.flush()
            os.fsync(wf.fileno())
        if _sha256_file(tmp) != before:
            raise InstallerError('rollback temp hash mismatch')
        if ch.get('originalMode') is not None:
            os.chmod(tmp, ch['originalMode'])
        _check_no_symlink_escape(repo, target)
        if _current_hash(target) != after:
            raise InstallerError('target changed during rollback staging')
        os.replace(tmp, target)
        _fsync_dir(d)
        ch['state'] = 'ROLLED_BACK'
        return None
    except Exception as e:
        ch['state'] = 'CONFLICT'
        return 'restore failed: %s: %s' % (ch['destination'], e)


def _perform_rollback(td, journal):
    journal['status'] = 'ROLLING_BACK'
    _write_journal(td, journal)
    conflicts = []
    repo = journal['repo']
    for ch in reversed(journal['changes']):
        try:
            err = _rollback_change(td, ch, repo)
            if err:
                conflicts.append(err)
        except Exception as e:
            ch['state'] = 'CONFLICT'
            conflicts.append('%s: %s' % (ch['destination'], e))
        _write_journal(td, journal)
    journal['status'] = 'ROLLBACK_CONFLICT' if conflicts else 'ROLLED_BACK'
    journal['error'] = '; '.join(conflicts) if conflicts else None
    _write_journal(td, journal)
    return journal['status'], conflicts


def _handle_failure(td, journal, repo, transaction_dir, err):
    if journal is None or td is None:
        return {
            'status': 'FAILED_PREFLIGHT',
            'transactionDir': transaction_dir,
            'repo': repo,
            'error': err,
            'conflicts': [],
        }
    if journal.get('status') == 'INSTALLED':
        return {
            'status': 'INSTALLED',
            'transactionDir': td,
            'repo': repo,
            'error': 'post-install error: ' + err,
            'conflicts': [],
        }
    try:
        status, conflicts = _perform_rollback(td, journal)
    except Exception as rb:
        status, conflicts = 'ROLLBACK_CONFLICT', ['rollback failed: %s' % rb]
    if conflicts:
        err = err + '; ' + '; '.join(conflicts)
    return {
        'status': status,
        'transactionDir': td,
        'repo': repo,
        'error': err,
        'conflicts': conflicts,
    }


def install(manifest, repo, transaction_dir, compile_argv, compile_cwd):
    repo = os.path.abspath(str(repo))
    transaction_dir = os.path.abspath(str(transaction_dir))
    compile_cwd = os.path.abspath(str(compile_cwd)) if compile_cwd else None
    if compile_argv is None:
        compile_argv = []
    if not isinstance(compile_argv, list) or any(not isinstance(a, str) for a in compile_argv):
        return {
            'status': 'FAILED_PREFLIGHT',
            'transactionDir': transaction_dir,
            'repo': repo,
            'error': 'compile_argv must be list of strings',
            'conflicts': [],
        }
    lock = None
    journal = None
    td = None
    try:
        lock = _acquire_lock(repo)
        if compile_argv and (compile_cwd is None or not os.path.isdir(compile_cwd)):
            raise InstallerError('compile_cwd must be an existing directory')
        infos = _read_manifest(manifest, repo)
        td = _create_td(transaction_dir, repo)
        journal = _init_journal(td, repo, infos, compile_argv, compile_cwd)
        _write_journal(td, journal)
        for ch in journal['changes']:
            _make_backup(td, journal, ch)
            _write_journal(td, journal)
        journal['status'] = 'READY'
        _write_journal(td, journal)
        for ch in journal['changes']:
            _check_target(journal, ch)
        journal['status'] = 'INSTALLING'
        _write_journal(td, journal)
        for ch in journal['changes']:
            _stage_and_replace(td, journal, ch)
        journal['status'] = 'COMPILING'
        _write_journal(td, journal)
        _run_compile(journal, td)
        journal['status'] = 'VERIFYING'
        _write_journal(td, journal)
        for ch in journal['changes']:
            if _current_hash(ch['target']) != ch['afterSha256']:
                raise InstallerError('post-compile hash mismatch: ' + ch['destination'])
        journal['status'] = 'INSTALLED'
        _write_journal(td, journal)
        return {
            'status': 'INSTALLED',
            'transactionDir': td,
            'repo': repo,
            'installed': [c['destination'] for c in journal['changes']],
            'error': None,
            'conflicts': [],
        }
    except KeyboardInterrupt:
        return _handle_failure(td, journal, repo, transaction_dir, 'KeyboardInterrupt')
    except Exception as e:
        return _handle_failure(td, journal, repo, transaction_dir, str(e))
    finally:
        _release_lock(lock)


def _recover_locked(transaction_dir, repo):
    td = os.path.abspath(str(transaction_dir))
    repo = os.path.abspath(str(repo))
    base = {'transactionDir': td, 'repo': repo, 'conflicts': []}
    jpath = os.path.join(td, 'journal.json')
    if not os.path.isfile(jpath):
        return dict(base, status='FAILED_PREFLIGHT', error='journal missing')
    try:
        journal = _load_json(jpath)
    except Exception as e:
        return dict(base, status='FAILED_PREFLIGHT', error='journal unreadable: %s' % e)
    if not isinstance(journal, dict) or journal.get('journalVersion') != JOURNAL_VERSION:
        return dict(base, status='FAILED_PREFLIGHT', error='journal version invalid')
    if os.path.realpath(journal.get('repo', '')) != os.path.realpath(repo):
        return dict(base, status='FAILED_PREFLIGHT', error='journal repo mismatch')
    changes = journal.get('changes')
    if not isinstance(changes, list):
        return dict(base, status='FAILED_PREFLIGHT', error='journal changes invalid')
    for ch in changes:
        if not isinstance(ch, dict):
            return dict(base, status='FAILED_PREFLIGHT', error='journal change invalid')
        try:
            rel, target = _resolve_dest(repo, ch.get('destination'))
        except Exception as e:
            return dict(base, status='FAILED_PREFLIGHT', error='invalid journal destination: %s' % e)
        if os.path.abspath(ch.get('target', '')) != target:
            return dict(base, status='FAILED_PREFLIGHT', error='journal target mismatch: ' + rel)
        before = ch.get('beforeSha256')
        if before is not None:
            backup_rel = ch.get('backupRel')
            if not backup_rel:
                return dict(base, status='FAILED_PREFLIGHT', error='journal backup missing: ' + rel)
            bpath = os.path.join(td, backup_rel)
            if not os.path.isfile(bpath) or _sha256_file(bpath) != before:
                return dict(base, status='FAILED_PREFLIGHT', error='backup verification failed: ' + rel)
    if journal.get('status') == 'INSTALLED':
        bad = []
        for ch in changes:
            rel, target = _resolve_dest(repo, ch['destination'])
            if _current_hash(target) != ch.get('afterSha256'):
                bad.append(rel)
        if bad:
            return dict(base, status='ROLLBACK_CONFLICT', error='installed files modified: ' + ', '.join(bad), conflicts=bad)
        return dict(base, status='INSTALLED', error=None)
    status, conflicts = _perform_rollback(td, journal)
    return dict(base, status=status, error='; '.join(conflicts) if conflicts else None, conflicts=conflicts)


def recover(transaction_dir, repo):
    lock = None
    try:
        lock = _acquire_lock(str(repo))
        return _recover_locked(transaction_dir, repo)
    except Exception as exc:
        return {'status': 'FAILED_PREFLIGHT', 'transactionDir': str(transaction_dir), 'repo': str(repo), 'error': str(exc), 'conflicts': []}
    finally:
        _release_lock(lock)
