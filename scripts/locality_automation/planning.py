from urllib.parse import urlsplit

from .diagnostics import normalize

CHECKS = (
    'existence',
    'locality_type',
    'arabic_name',
    'french_search',
    'governorate',
    'delegation',
    'duplicates',
    'boundary',
    'gps_resolution',
    'prayer_source',
)

_VERIFIED = {'verified'}
_FAILED = {
    'failed', 'fail', 'error', 'mismatch', 'invalid', 'no', 'false', 'n',
    '0', '0.0', 'rejected', 'wrong',
    'مرفوض', 'مرفوضة', 'خطأ', 'فشل', 'غير مطابق', 'غير مطابقة',
    'غير مؤكد', 'غير موجود',
}
_ADMIN_PREFIXES = ('ولاية', 'معتمدية', 'المعتمدية')


def _n(value):
    if value is None:
        return ''
    raw = str(value)
    try:
        out = normalize(raw)
    except Exception:
        out = raw
    if out is None:
        out = ''
    return ' '.join(str(out).split())


def _strip_admin(value):
    s = _n(value)
    changed = True
    while changed:
        changed = False
        for prefix in _ADMIN_PREFIXES:
            if s.startswith(prefix):
                s = s[len(prefix):].strip()
                changed = True
                break
    return s


def _gov(value):
    return _strip_admin(value)


def _deleg(value):
    return _strip_admin(value)


def _disp(value):
    return _n(value)


def _id(value):
    return '' if value is None else str(value).strip()


def _url_ok(url):
    if not isinstance(url, str) or not url:
        return False
    try:
        parts = urlsplit(url)
        host = parts.hostname or ''
        port = parts.port
    except ValueError:
        return False
    if parts.scheme.lower() != 'https':
        return False
    if host.lower() not in ('isie.tn', 'www.isie.tn'):
        return False
    if parts.username is not None or parts.password is not None:
        return False
    if port is not None and port != 443:
        return False
    if parts.fragment:
        return False
    return bool(parts.path and parts.path.lower().endswith('.pdf'))


def _parts(path):
    return [p for p in str(path or '').split('/') if p.strip()]


def _base(path):
    ps = _parts(path)
    if not ps:
        return ''
    base = ps[-1].strip()
    if base.lower().endswith('.pdf'):
        base = base[:-4].strip()
    s = base.rstrip()
    if s.endswith(')'):
        i = s.rfind('(')
        if i >= 0:
            before = s[:i].rstrip()
            if before.endswith('-'):
                before = before[:-1].rstrip()
            base = before
    return base


def _path_match(path, gov, parent):
    comps = _parts(path)[:-1]
    g = _gov(gov)
    p = _deleg(parent)
    if not g or not p:
        return False
    gi = [i for i, c in enumerate(comps) if _gov(c) == g]
    pi = [i for i, c in enumerate(comps) if _deleg(c) == p]
    return any(i < j for i in gi for j in pi)


def _aliases(row):
    value = row.get('aliases')
    if isinstance(value, str):
        return [value]
    if isinstance(value, list):
        return [x for x in value if isinstance(x, str)]
    return []


def _sources(row, index):
    gov = row.get('governorateAr') or ''
    parent = row.get('parentAr') or ''
    display = _disp(row.get('nameAr'))
    aliases = [a for a in (_disp(x) for x in _aliases(row)) if a]
    matches = []
    exact = 0
    alias_only = 0
    seen = set()
    for item in index:
        url = item.get('url')
        path = item.get('decodedPath')
        if not _url_ok(url) or not _path_match(path, gov, parent):
            continue
        raw_base = _base(path)
        base = _disp(raw_base)
        if not base:
            continue
        is_exact = bool(display and base == display)
        is_alias = any(base == a for a in aliases)
        if not is_exact and not is_alias:
            continue
        key = (url, str(path))
        if key in seen:
            continue
        seen.add(key)
        text = item.get('text')
        if text is None or text == '':
            text = raw_base
        matches.append({
            'text': text,
            'url': url,
            'decodedPath': path,
        })
        if is_exact:
            exact += 1
        elif is_alias:
            alias_only += 1
    if not matches:
        status = 'missing'
    elif exact == 1 and len(matches) == 1:
        status = 'unique_exact'
    else:
        status = 'ambiguous'
    return sorted(matches, key=lambda x: (x['url'], str(x['decodedPath']))), status, exact, alias_only


def _status(value):
    if isinstance(value, dict):
        if 'status' in value:
            value = value['status']
        elif 'verified' in value:
            value = value['verified']
        else:
            return ''
    if isinstance(value, bool):
        return ''
    if value is None:
        return ''
    return _n(value).lower()


def _check_info(checks):
    check_map = {str(k).strip().lower(): v for k, v in checks.items()}
    accepted = []
    missing = []
    failed = 0
    for name in CHECKS:
        status = _status(check_map.get(name.lower()))
        if status in _VERIFIED:
            accepted.append(name)
        else:
            missing.append(name)
            if status in _FAILED:
                failed += 1
    return accepted, failed, missing


def _score_for(score_by_id, loc_id):
    row = score_by_id.get(loc_id)
    if row is None:
        return {'accepted': [], 'failed': 0, 'missing': list(CHECKS), 'has_score': False}
    checks = row.get('checks')
    if not isinstance(checks, dict) or not checks:
        return {'accepted': [], 'failed': 0, 'missing': list(CHECKS), 'has_score': False}
    accepted, failed, missing = _check_info(checks)
    return {
        'accepted': accepted,
        'failed': failed,
        'missing': missing,
        'has_score': bool(accepted or failed),
    }


def _num(value):
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return float(value)
    try:
        return float(str(value).strip())
    except (TypeError, ValueError):
        return 0.0


def _area(entry):
    for key in ('newIntersectionAreaM2', 'deltaM2'):
        if key in entry:
            value = _num(entry.get(key))
            if value:
                return value
    return 0.0


def _catalog_locations(catalog):
    if not isinstance(catalog, dict):
        raise ValueError('catalog must be an object')
    locs = catalog.get('locations')
    if not isinstance(locs, list):
        raise ValueError('catalog.locations must be a list')
    out = []
    for loc in locs:
        if not isinstance(loc, dict):
            raise ValueError('catalog location must be an object')
        if _id(loc.get('id')) == '':
            raise ValueError('catalog location id required')
        if 'nameAr' not in loc:
            raise ValueError('catalog location nameAr required')
        out.append(loc)
    return out


def _index_items(index):
    if index is None:
        return []
    if not isinstance(index, list):
        raise ValueError('index must be a list')
    out = []
    for item in index:
        if not isinstance(item, dict):
            raise ValueError('index item must be an object')
        if 'url' not in item or 'decodedPath' not in item:
            raise ValueError('index item requires url and decodedPath')
        out.append(item)
    return out


def _priority_cases(remaining):
    if remaining is None:
        return []
    if not isinstance(remaining, dict):
        raise ValueError('remaining must be an object')
    cases = remaining.get('priorityBoundaryCases', [])
    if cases is None:
        return []
    if not isinstance(cases, list):
        raise ValueError('remaining.priorityBoundaryCases must be a list')
    out = []
    for entry in cases:
        if not isinstance(entry, dict):
            raise ValueError('priorityBoundaryCase must be an object')
        if _id(entry.get('sectorId')) == '':
            raise ValueError('priorityBoundaryCase sectorId required')
        out.append(entry)
    return out


def _score_rows(scorecards):
    if scorecards is None:
        return []
    if isinstance(scorecards, list):
        rows = scorecards
    elif isinstance(scorecards, dict):
        if 'rows' in scorecards:
            rows = scorecards['rows']
        elif 'scorecards' in scorecards:
            rows = scorecards['scorecards']
        elif 'locations' in scorecards:
            rows = scorecards['locations']
        elif not scorecards:
            rows = []
        else:
            raise ValueError('scorecards object must have scorecards or locations')
    else:
        raise ValueError('unknown scorecards schema')
    if rows is None:
        return []
    if not isinstance(rows, list):
        raise ValueError('scorecards rows must be a list')
    out = []
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError('scorecard row must be an object')
        if _id(row.get('id')) == '':
            raise ValueError('scorecard row id required')
        out.append(row)
    return out


def _add_review(review, value):
    if value not in review:
        review.append(value)


def plan_cases(catalog, index, scorecards, remaining, governorate, limit=12):
    if not isinstance(governorate, str) or not governorate.strip():
        raise ValueError('governorate required')
    if not isinstance(limit, int) or isinstance(limit, bool) or limit <= 0:
        raise ValueError('limit must be a positive integer')

    locations = _catalog_locations(catalog)
    index_items = _index_items(index)
    priority_entries = _priority_cases(remaining)
    score_rows = _score_rows(scorecards)

    score_by_id = {}
    for row in score_rows:
        if _id(row.get('id')) in score_by_id:
            raise ValueError('duplicate scorecard ID')
        score_by_id.setdefault(_id(row.get('id')), row)

    by_id = {}
    for loc in locations:
        loc_id = _id(loc.get('id'))
        if loc_id in by_id:
            raise ValueError('duplicate catalog ID')
        if loc_id and loc_id not in by_id:
            by_id[loc_id] = loc

    active_gov = _gov(governorate)
    if not active_gov:
        raise ValueError('governorate required')

    active_locations = []
    active_ids = set()
    for loc in locations:
        loc_id = _id(loc.get('id'))
        if not loc_id or loc_id in active_ids:
            continue
        if _gov(loc.get('governorateAr')) == active_gov:
            active_ids.add(loc_id)
            active_locations.append(loc)

    priority_by_sector = {}
    candidate_links = {}
    unresolved = []
    unresolved_seen = set()

    def mark_unresolved(value):
        value_id = _id(value)
        if value_id and value_id not in unresolved_seen:
            unresolved_seen.add(value_id)
            unresolved.append(value_id)

    for entry in priority_entries:
        sector_id = _id(entry.get('sectorId'))
        old = priority_by_sector.get(sector_id)
        if old is None or _area(entry) > _area(old):
            priority_by_sector[sector_id] = entry
        candidate_id = _id(entry.get('candidateId'))
        if candidate_id:
            candidate_links.setdefault(candidate_id, []).append(entry)
            if candidate_id not in by_id:
                mark_unresolved(candidate_id)
        if sector_id not in by_id:
            mark_unresolved(sector_id)

    built = []
    used = set()

    def build(loc, role, reason, explicit, area, extra_review=None, candidate_id=None):
        loc_id = _id(loc.get('id'))
        if loc_id in used:
            return None
        score = _score_for(score_by_id, loc_id)
        sources, source_status, _exact, alias_only = _sources(loc, index_items)
        review = ['whole_boundary_review']
        if source_status == 'unique_exact':
            _add_review(review, 'source_download_lead_only')
        elif source_status == 'ambiguous':
            _add_review(review, 'resolve_source_ambiguity')
        else:
            _add_review(review, 'find_official_source')
        if alias_only:
            _add_review(review, 'alias_leads_present')
        if not score['has_score']:
            _add_review(review, 'unknown_scorecard')
        if score['failed']:
            _add_review(review, 'failed_checks')
        if score['missing']:
            _add_review(review, 'missing_checks')
        if loc.get('hasBoundary'):
            _add_review(review, 'existing_boundary_not_verified')
        if role == 'boundary_conflict':
            _add_review(review, 'explicit_priority_conflict')
        for tag in extra_review or []:
            _add_review(review, tag)
        case = {
            'id': loc.get('id'),
            'nameAr': loc.get('nameAr', ''),
            'governorateAr': loc.get('governorateAr', ''),
            'parentAr': loc.get('parentAr', ''),
            'role': role,
            'reason': reason,
            'acceptedChecks': list(score['accepted']),
            'missingChecks': list(score['missing']),
            'sourceMatches': list(sources),
            'sourceStatus': source_status,
            'query': ' '.join(
                '{} {} {}'.format(
                    loc.get('nameAr', ''), loc.get('parentAr', ''), loc.get('governorateAr', '')
                ).split()
            ),
            'reviewRequired': review,
        }
        if candidate_id:
            case['candidateId'] = candidate_id
        used.add(loc_id)
        built.append((case, {
            'id': loc_id,
            'explicit': bool(explicit),
            'area': area,
            'failed': score['failed'],
            'accepted': len(score['accepted']),
            'parent': _disp(loc.get('parentAr')),
            'name': _disp(loc.get('nameAr')),
        }))
        return case

    for loc in active_locations:
        loc_id = _id(loc.get('id'))
        if loc_id in used:
            continue
        score = _score_for(score_by_id, loc_id)
        all_verified = len(score['accepted']) == len(CHECKS) and score['failed'] == 0
        entry = priority_by_sector.get(loc_id)
        if all_verified and entry is None:
            continue
        links = candidate_links.get(loc_id, [])
        if entry is not None:
            area = _area(entry)
            candidate_id = _id(entry.get('candidateId'))
            reason = 'explicit remaining boundary conflict; area={:g}; candidateId={}'.format(
                area, candidate_id
            )
            extra = ['linked_candidate'] if candidate_id else []
            build(loc, 'boundary_conflict', reason, True, area, extra, candidate_id or None)
        else:
            extra = ['cross_governorate_dependency'] if links else []
            if not score['has_score']:
                reason = 'active governorate location; unscored'
            elif score['failed']:
                reason = 'active governorate location; failed checks=' + str(score['failed'])
            else:
                reason = 'active governorate location'
            build(loc, 'active_location', reason, False, 0.0, extra, None)

    for sector_id, entry in priority_by_sector.items():
        if sector_id in active_ids or sector_id in used:
            continue
        loc = by_id.get(sector_id)
        if loc is None:
            mark_unresolved(sector_id)
            continue
        area = _area(entry)
        candidate_id = _id(entry.get('candidateId'))
        reason = (
            'explicit remaining sector outside active governorate admitted as '
            'border_dependency; area={:g}; candidateId={}'
        ).format(area, candidate_id)
        extra = ['cross_governorate_dependency', 'border_dependency']
        if candidate_id:
            extra.append('linked_candidate')
        build(loc, 'border_dependency', reason, True, area, extra, candidate_id or None)

    def sort_key(item):
        _case, meta = item
        return (
            0 if meta['explicit'] else 1,
            -meta['area'],
            -meta['failed'],
            -meta['accepted'],
            meta['parent'],
            meta['name'],
            meta['id'],
        )

    built.sort(key=sort_key)

    cases_all = []
    seen_cases = set()
    for case, meta in built:
        case_id = _id(case.get('id'))
        if case_id in seen_cases:
            continue
        seen_cases.add(case_id)
        cases_all.append((case, meta))

    selected = cases_all[:limit]
    cases = [case for case, _meta in selected]

    total_active_cases = sum(
        1 for case, _meta in cases_all if case.get('role') != 'border_dependency'
    )
    emitted_active_cases = sum(
        1 for case in cases if case.get('role') != 'border_dependency'
    )
    remaining_active_case_count = max(0, total_active_cases - emitted_active_cases)
    explicit_border_dependencies = sum(
        1 for case, _meta in cases_all if case.get('role') == 'border_dependency'
    )

    notes = [
        f'Scoped to active governorate {governorate}; {len(active_locations)} active locations.',
        f'Explicit border dependencies outside active governorate: {explicit_border_dependencies}.',
        f'Returned {len(cases)} of {len(cases_all)} cases (limit={limit}); '
        f'remaining active cases: {remaining_active_case_count}.',
        'sourceStatus unique_exact is a source-download lead only; whole boundary always requires review.',
        'hasBoundary is not treated as verification; unscored/zero-check rows remain unknown.',
    ]
    if unresolved:
        notes.append('Unresolved IDs: ' + ', '.join(unresolved))

    return {
        'scope': {
            'governorateAr': governorate,
            'governorate': governorate,
            'activeOnly': True,
        },
        'cases': cases,
        'totalActiveLocations': len(active_locations),
        'explicitBorderDependencies': explicit_border_dependencies,
        'remainingActiveCaseCount': remaining_active_case_count,
        'notes': notes,
        'unresolvedIds': unresolved,
    }
