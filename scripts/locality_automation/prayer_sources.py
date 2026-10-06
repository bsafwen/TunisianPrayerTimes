'''Numerical/timetable source snapshot for locality automation.'''
from __future__ import annotations
import hashlib, json, math
from datetime import datetime, timezone
from pathlib import Path
from .common import digest, module, pin, read, read_pin, verify, write

Q = 'Numerical/timetable validation, not coordinate authenticity or geographic certification.'
CD = 'prayer-sources'

def _ym():
    n = datetime.now(timezone.utc); return n.year, n.month

def _num(v):
    if isinstance(v, bool): return None
    try: f = float(v)
    except (TypeError, ValueError): return None
    return f if math.isfinite(f) else None

def _coords(row):
    lat, lng = _num(row.get('lat')), _num(row.get('lng'))
    if lat is None or lng is None: return None, None, 'invalid latitude/longitude'
    if not (-90 <= lat <= 90) or not (-180 <= lng <= 180): return None, None, 'latitude/longitude out of range'
    return lat, lng, ''

def _csv(repo, did, year, month):
    root = Path(repo).resolve() / 'android-app/app/src/main/assets/csv'; sid = str(did)
    if sid in ('', '.', '..') or '/' in sid or '\\' in sid: raise ValueError(f'Unsafe delegation id: {did!r}')
    p = (root / sid / f'{year:04d}' / f'{month:02d}.csv').resolve()
    if not p.is_relative_to(root): raise ValueError(f'CSV path escapes root: {p}')
    return p

def _hash(path): return digest(path) if path.is_file() else None

def _key(gsha, hsha, year, month, csv):
    raw = json.dumps({'governors': gsha, 'helper': hsha, 'year': year, 'month': month, 'csv': csv}, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()
    return hashlib.sha256(raw).hexdigest()

def _no_nan(v, w='value'):
    if isinstance(v, float):
        if not math.isfinite(v): raise ValueError(f'Non-finite number in {w}')
    elif isinstance(v, dict):
        for k, x in v.items(): _no_nan(x, f'{w}.{k}')
    elif isinstance(v, list):
        for i, x in enumerate(v): _no_nan(x, f'{w}[{i}]')

def _stable(path, before, label):
    if digest(path) != before: raise ValueError(f'{label} changed during snapshot: {path}')

def _verify(data, year, month, gpath, gsha, hpath, hsha, paths, hashes, rows):
    if not isinstance(data, dict) or data.get('year') != year or data.get('month') != month: raise ValueError('cached year/month mismatch')
    if data.get('qualification') != Q: raise ValueError('cached qualification mismatch')
    pins = data.get('inputPins')
    if not isinstance(pins, dict): raise ValueError('cached inputPins missing')
    for label, path, sha in (('governors', gpath, gsha), ('prayerHelper', hpath, hsha)):
        item = pins.get(label)
        if not isinstance(item, dict) or Path(item.get('file', '')).resolve() != path or item.get('sha256') != sha: raise ValueError(f'cached {label} pin mismatch')
    if pins.get('csvFingerprints') != hashes: raise ValueError('cached CSV fingerprints mismatch')
    files, sources = data.get('csvFiles'), data.get('sources')
    if not isinstance(files, list) or not isinstance(sources, list): raise ValueError('cached csvFiles/sources missing')
    fmap = {str(x.get('ref')): x for x in files if isinstance(x, dict) and x.get('ref') is not None}
    smap = {str(x.get('ref')): x for x in sources if isinstance(x, dict) and x.get('ref') is not None}
    if len(fmap) != len(files) or len(smap) != len(sources): raise ValueError('cached duplicate refs')
    if set(fmap) != set(paths) or set(smap) != {str(r['id']) for r in rows}: raise ValueError('cached ref set mismatch')
    for rid, path in paths.items():
        item, expected = fmap[rid], hashes[rid]
        if Path(item.get('path', '')).resolve() != path: raise ValueError(f'cached CSV path mismatch: {rid}')
        if bool(item.get('exists')) != (expected is not None): raise ValueError(f'cached CSV exists mismatch: {rid}')
        if item.get('sha256Before') != expected or item.get('sha256After') != expected: raise ValueError(f'cached CSV hash mismatch: {rid}')
    for r in rows:
        rid = str(r['id']); s = smap[rid]; lat, lng, _ = _coords(r)
        if _num(s.get('lat')) != lat or _num(s.get('lng')) != lng or s.get('nameAr') != r.get('nomAr'): raise ValueError(f'cached source mismatch: {rid}')
    _no_nan(data, 'cache')

def source_snapshot(config, snap):
    year, month = _ym()
    repo, workspace = Path(config['repo']).resolve(), Path(config['workspace']).resolve()
    gpin, hpin = snap['catalog']['sourcePins']['governors'], config['helpers']['prayer']
    gpath, hpath = verify(gpin), verify(hpin)
    gsha, hsha = pin(gpath)['sha256'], pin(hpath)['sha256']
    gov = read_pin(gpin)
    rows, seen = [], set()
    for g in gov.get('gouvernorats', []):
        for r in g.get('delegations', []):
            rid = str(r.get('id', ''))
            if not rid: raise ValueError('delegation id required')
            if rid in seen: raise ValueError(f'duplicate delegation id: {rid}')
            seen.add(rid); rows.append(r)
    paths = {str(r['id']): _csv(repo, r['id'], year, month) for r in rows}
    hashes = {rid: _hash(p) for rid, p in paths.items()}
    key = _key(gsha, hsha, year, month, hashes)
    cpath = workspace / CD / f'{key}.json'
    if cpath.is_file():
        data = read(cpath); _verify(data, year, month, gpath, gsha, hpath, hsha, paths, hashes, rows)
        for rid, p in paths.items():
            if _hash(p) != hashes[rid]: raise ValueError(f'CSV changed during cache read: {p}')
        _stable(gpath, gsha, 'governors'); _stable(hpath, hsha, 'prayer helper'); return data
    helper = module(hpin, 'automation_prayer_helper')
    ll, chk = getattr(helper, 'll', None), getattr(helper, 'chk', None)
    if not callable(ll) or not callable(chk): raise ValueError('prayer helper must expose ll and chk')
    sources, files = [], []
    for r in rows:
        rid = str(r['id']); lat, lng, why = _coords(r); valid = False
        if lat is not None and lng is not None:
            try: adjusted, err = ll(lat, lng)
            except Exception as exc: why = f'll failed: {exc}'
            else:
                if err: why = str(err)
                elif not (isinstance(adjusted, (tuple, list)) and len(adjusted) == 2 and _num(adjusted[0]) is not None and _num(adjusted[1]) is not None): why = 'll returned invalid coordinates'
                else: valid = True
        p, expected = paths[rid], hashes[rid]
        try:
            c = chk(p, year, month)
            if Path(c.get('path', '')).resolve() != p: raise ValueError('chk path mismatch')
            if bool(c.get('exists')) != (expected is not None): raise ValueError('chk exists mismatch')
            if c.get('sha256Before') != expected or c.get('sha256After') != expected: raise ValueError('chk hash mismatch')
        except Exception as exc:
            c = {'path': str(p), 'exists': expected is not None, 'sha256Before': expected, 'sha256After': expected, 'available': False, 'reason': f'chk failed: {exc}'}
        if _hash(p) != expected: raise ValueError(f'CSV changed during snapshot: {p}')
        cav = bool(c.get('available')) and expected is not None and bool(c.get('exists'))
        if not cav and not c.get('reason'): c['reason'] = 'missing or unavailable CSV'
        reason = '; '.join(x for x in (why, '' if cav else str(c.get('reason', ''))) if x)
        sources.append({'ref': rid, 'key': rid, 'nameAr': r.get('nomAr'), 'lat': lat, 'lng': lng, 'sourceValid': valid, 'csvAvailable': cav, 'available': valid and cav, 'reason': reason or 'available'})
        files.append({'ref': rid, 'path': str(p), 'exists': bool(c.get('exists')), 'sha256Before': c.get('sha256Before'), 'sha256After': c.get('sha256After'), 'available': cav, 'reason': c.get('reason', '')})
    for rid, p in paths.items():
        if _hash(p) != hashes[rid]: raise ValueError(f'CSV changed during snapshot: {p}')
    _stable(gpath, gsha, 'governors'); _stable(hpath, hsha, 'prayer helper')
    out = {'year': year, 'month': month, 'sources': sources, 'csvFiles': files, 'inputPins': {'governors': {'file': str(gpath), 'sha256': gsha}, 'prayerHelper': {'file': str(hpath), 'sha256': hsha}, 'csvFingerprints': hashes}, 'qualification': Q}
    _no_nan(out, 'output')
    if cpath.exists():
        data = read(cpath); _verify(data, year, month, gpath, gsha, hpath, hsha, paths, hashes, rows); return data
    try: write(cpath, out, replace=False)
    except FileExistsError:
        data = read(cpath); _verify(data, year, month, gpath, gsha, hpath, hsha, paths, hashes, rows); return data
    _stable(gpath, gsha, 'governors'); _stable(hpath, hsha, 'prayer helper'); return out
