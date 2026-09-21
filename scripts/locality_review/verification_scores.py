'''Stdlib-only verification score loader for the review server.'''
import hashlib, json, math
_SIX = ('helper', 'metadata', 'binary', 'governors', 'displayNames', 'coverage')
_HASH_KEYS = {k: k + 'Sha256' for k in _SIX}
_HASH_KEYS['policy'] = 'policySha256'
_HASH_KEYS['claims'] = 'claimsSha256'
_PINS = set(_SIX) | {'policy', 'claims'}
_CHECKS = frozenset(('existence', 'locality_type', 'arabic_name', 'french_search', 'governorate', 'delegation', 'duplicates', 'boundary', 'gps_resolution', 'prayer_source'))
_BUCKETS = tuple(str(v) for v in range(100, -1, -10))
def _fail(msg):
    raise ValueError(msg)
def _read(path, label):
    if not isinstance(path, str) or not path:
        _fail(f'{label}: missing or invalid file path')
    try:
        with open(path, 'rb') as fh:
            return fh.read()
    except (OSError, ValueError) as exc:
        _fail(f'{label}: cannot read {path!r}: {exc}')
def _sha(data):
    return hashlib.sha256(data).hexdigest()
def _json(data, label):
    try:
        return json.loads(data.decode('utf-8'))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        _fail(f'{label}: invalid JSON: {exc}')
def _pin(entry, label):
    if not isinstance(entry, dict):
        _fail(f'{label}: expected object with file and sha256')
    path, digest = entry.get('file'), entry.get('sha256')
    if not isinstance(path, str) or not path or not isinstance(digest, str) or len(digest) != 64:
        _fail(f'{label}: missing or invalid file/sha256')
    return path, digest.lower()
def _pinned(entry, label):
    path, digest = _pin(entry, label)
    data = _read(path, label)
    if _sha(data) != digest:
        _fail(f'{label}: sha256 mismatch for {path!r}')
    return _json(data, label)
def _same_number(a, b):
    if isinstance(a, bool) or isinstance(b, bool):
        return False
    if not isinstance(a, (int, float)) or not isinstance(b, (int, float)):
        return False
    try:
        return math.isfinite(a) and math.isfinite(b) and a == b
    except OverflowError:
        return False
def _validate_pins(catalog, summary, input_pins):
    source = catalog.get('sourcePins')
    if not isinstance(source, dict):
        _fail('catalog.sourcePins: expected object')
    if set(input_pins) != _PINS:
        _fail('currentInputPins: unexpected pin keys')
    for key in _SIX:
        cpath, cdigest = _pin(source.get(key), f'catalog.sourcePins.{key}')
        ipath, idigest = _pin(input_pins.get(key), f'currentInputPins.{key}')
        if cpath != ipath or cdigest != idigest:
            _fail(f'currentInputPins.{key}: does not match catalog')
    for key in ('policy', 'claims'):
        _pin(input_pins.get(key), f'currentInputPins.{key}')
    hashes = summary.get('inputHashes')
    if not isinstance(hashes, dict):
        _fail('summary.inputHashes: expected object')
    if set(hashes) != set(_HASH_KEYS.values()):
        _fail('summary.inputHashes: unexpected keys')
    for key in _SIX:
        _, cdigest = _pin(source.get(key), f'catalog.sourcePins.{key}')
        actual = hashes.get(_HASH_KEYS[key])
        if not isinstance(actual, str) or actual.lower() != cdigest:
            _fail(f'summary.inputHashes.{_HASH_KEYS[key]}: does not match catalog')
    for key in ('policy', 'claims'):
        _, idigest = _pin(input_pins.get(key), f'currentInputPins.{key}')
        actual = hashes.get(_HASH_KEYS[key])
        if not isinstance(actual, str) or actual.lower() != idigest:
            _fail(f'summary.inputHashes.{_HASH_KEYS[key]}: does not match currentInputPins')
def _catalog_index(catalog):
    if not isinstance(catalog, dict):
        _fail('catalog: expected object')
    index = {}
    for loc in catalog.get('locations', []):
        if not isinstance(loc, dict):
            _fail('catalog.locations: expected objects')
        loc_id = loc.get('id')
        if not isinstance(loc_id, str) or not loc_id:
            _fail('catalog.locations: missing or invalid id')
        if loc_id in index:
            _fail(f'catalog.locations: duplicate id {loc_id!r}')
        index[loc_id] = loc
    return index
def _collect_scores(scorecards, catalog_index):
    rows = scorecards.get('rows')
    if not isinstance(rows, list):
        _fail('scorecards.rows: expected array')
    scores, buckets = {}, {key: 0 for key in _BUCKETS}
    verified_total, scored_count, seen = 0, 0, set()
    for pos, row in enumerate(rows):
        rid = row.get('id') if isinstance(row, dict) else None
        if not isinstance(rid, str) or not rid or rid in seen:
            _fail(f'scorecards.rows[{pos}]: missing, invalid, or duplicate id')
        seen.add(rid)
        loc = catalog_index.get(rid)
        if loc is None:
            _fail(f'scorecards.rows: id {rid!r} not in catalog')
        checks = row.get('verifiedChecks')
        if not isinstance(checks, list):
            _fail(f'scorecards row {rid!r}: verifiedChecks must be an array')
        check_set = set()
        for check in checks:
            if not isinstance(check, str) or check not in _CHECKS or check in check_set:
                _fail(f'scorecards row {rid!r}: invalid or duplicate verified check {check!r}')
            check_set.add(check)
        score = row.get('scorePercent')
        if score is None:
            if checks:
                _fail(f'scorecards row {rid!r}: unscored row has verified checks')
            continue
        if isinstance(score, bool) or not isinstance(score, (int, float)) or score < 0 or score > 100 or not math.isfinite(score):
            _fail(f'scorecards row {rid!r}: scorePercent must be finite 0..100')
        if score != 10 * len(checks):
            _fail(f'scorecards row {rid!r}: scorePercent does not match verified checks')
        if row.get('displayNameAr') != loc.get('nameAr'):
            _fail(f'scorecards row {rid!r}: displayNameAr does not match catalog')
        if not _same_number(row.get('lat'), loc.get('lat')) or not _same_number(row.get('lng'), loc.get('lng')):
            _fail(f'scorecards row {rid!r}: lat/lng do not match catalog')
        score_int = int(score)
        scores[rid] = score_int
        buckets[str(score_int)] += 1
        verified_total += len(checks)
        scored_count += 1
    if len(rows) != len(catalog_index) or seen != set(catalog_index):
        _fail('scorecards.rows: ids do not exactly match catalog locations')
    return scores, buckets, verified_total, scored_count, len(rows)
def _compare_derived(report, summary, total, scored_count, verified_total, buckets):
    unscored = total - scored_count
    for key, expected in (('totalSelectableLocations', total), ('scoredLocations', scored_count), ('awaitingScoring', unscored), ('verifiedChecks', verified_total)):
        val = report.get(key)
        if not isinstance(val, int) or isinstance(val, bool) or val != expected:
            _fail(f'report.{key}: does not match derived value')
    for key, expected in (('totalLocations', total), ('scoredCount', scored_count), ('unscoredCount', unscored), ('verifiedCheckCount', verified_total)):
        val = summary.get(key)
        if not isinstance(val, int) or isinstance(val, bool) or val != expected:
            _fail(f'summary.{key}: does not match derived value')
    for label, obj, key in (('report', report, 'verificationScoreBuckets'), ('summary', summary, 'buckets')):
        val = obj.get(key)
        if not isinstance(val, dict) or set(val) != set(buckets):
            _fail(f'{label}.{key}: bucket keys mismatch')
        for bucket, expected in buckets.items():
            count = val.get(bucket)
            if not isinstance(count, int) or isinstance(count, bool) or count != expected:
                _fail(f'{label}.{key}.{bucket}: does not match derived buckets')
def load_scores(report_path, catalog):
    if report_path is None:
        return {'available': False, 'scores': {}, 'message': 'Verification scores are not loaded.', 'reportSha256': None}
    data = _read(report_path, 'report')
    report_sha = _sha(data)
    report = _json(data, 'report')
    if not isinstance(report, dict):
        _fail('report: expected object')
    summary = _pinned(report.get('summary'), 'report.summary')
    scorecards = _pinned(report.get('scorecards'), 'report.scorecards')
    input_pins = _pinned(report.get('currentInputPins'), 'report.currentInputPins')
    if not isinstance(summary, dict) or not isinstance(scorecards, dict) or not isinstance(input_pins, dict):
        _fail('report: summary/scorecards/currentInputPins must be objects')
    catalog_index = _catalog_index(catalog)
    _validate_pins(catalog, summary, input_pins)
    for key in ('metadataSha256', 'binarySha256', 'policySha256', 'claimsSha256'):
        if scorecards.get(key) != summary['inputHashes'][key]:
            _fail(f'scorecards.{key}: does not match accepted summary')
    scores, buckets, verified_total, scored_count, total = _collect_scores(scorecards, catalog_index)
    _compare_derived(report, summary, total, scored_count, verified_total, buckets)
    return {'available': True, 'scores': scores, 'scoredCount': scored_count, 'total': total, 'reportSha256': report_sha}
