'''Deterministic locality catalog diagnostics. Read-only; no CLI, IO, network, or scoring.'''
from __future__ import annotations
import math
import unicodedata

EARTH_RADIUS_KM = 6371.0088
_ARABIC_ALEFS = {'آ', 'أ', 'إ', 'ٱ'}
_MAQSURA, _YA, _KASHIDA = 'ى', 'ي', 'ـ'
_RESIDENTIAL_TERMS = ('اقامة', 'residence')
_LAT_MIN, _LAT_MAX, _LNG_MIN, _LNG_MAX = -90.0, 90.0, -180.0, 180.0

def normalize(text):
    if text is None:
        return ''
    out = []
    for ch in unicodedata.normalize('NFKD', str(text)):
        if unicodedata.combining(ch):
            continue
        category = unicodedata.category(ch)
        if category == 'Cf' or ch == _KASHIDA:
            continue
        if ch in _ARABIC_ALEFS:
            ch = 'ا'
        elif ch == _MAQSURA:
            ch = _YA
        if category.startswith('P'):
            ch = ' '
        out.append(ch)
    return ' '.join(''.join(out).lower().split())

def _text(value):
    return '' if value is None else (value if isinstance(value, str) else str(value))

def _json_value(value):
    if value is None or isinstance(value, (str, int, bool)):
        return value
    if isinstance(value, float):
        return value if math.isfinite(value) else str(value)
    return str(value)

def _coord(value):
    if value is None or isinstance(value, bool):
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None

def _coord_flags(row):
    flags, values = [], []
    for axis, missing, invalid, out_code, low, high in (
        ('lat', 'lat_missing', 'lat_invalid', 'lat_out_of_range', _LAT_MIN, _LAT_MAX),
        ('lng', 'lng_missing', 'lng_invalid', 'lng_out_of_range', _LNG_MIN, _LNG_MAX),
    ):
        raw = row.get(axis)
        value = _coord(raw)
        values.append(value)
        if raw is None or raw == '':
            flags.append((missing, axis + ' is missing'))
        elif value is None:
            flags.append((invalid, axis + ' is NaN, infinite, or non-numeric'))
        elif not (low <= value <= high):
            flags.append((out_code, axis + ' outside ' + str(low) + '..' + str(high)))
            values[-1] = None
    return values[0], values[1], flags

def _haversine_m(lat1, lng1, lat2, lng2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dlat, dlng = math.radians(lat2 - lat1), math.radians(lng2 - lng1)
    a = math.sin(dlat / 2.0) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dlng / 2.0) ** 2
    return 2.0 * EARTH_RADIUS_KM * math.asin(math.sqrt(min(1.0, max(0.0, a)))) * 1000.0

def _name_diagnostics(row):
    normalized = normalize(row.get('nameAr'))
    raw_aliases = row.get('aliases')
    aliases = [_text(item) for item in raw_aliases] if isinstance(raw_aliases, list) else []
    norm_aliases = [normalize(item) for item in aliases]
    counts = {}
    for alias in norm_aliases:
        counts[alias] = counts.get(alias, 0) + 1
    search = {'normalizationEcho': normalized, 'searchTokenCount': len(normalized.split()), 'normalizedAliases': norm_aliases}
    return {
        'normalizedName': normalized,
        'normalizedParent': normalize(row.get('parentAr')),
        'normalizedAliases': norm_aliases,
        'searchIndex': search,
        'aliasCount': len(aliases),
        'emptyAliasCount': sum(1 for alias in norm_aliases if not alias),
        'duplicateAliasCount': sum(1 for count in counts.values() if count > 1),
    }

def _name_flags(diagnostics):
    flags = []
    normalized = diagnostics['normalizedName']
    aliases = diagnostics['normalizedAliases']
    if not normalized:
        flags.append(('display_missing', 'nameAr is empty after normalization'))
    elif not any('ARABIC' in unicodedata.name(ch, '') and unicodedata.category(ch).startswith('L') for ch in normalized):
        flags.append(('display_arabic_absent', 'display has no Arabic letters'))
    if any('a' <= ch <= 'z' for ch in normalized):
        flags.append(('display_contains_latin', 'normalized display contains Latin letters'))
    terms = [term for term in _RESIDENTIAL_TERMS if any(term in value for value in [normalized] + aliases)]
    if terms:
        flags.append(('residential_complex_term', 'contains residential-complex term(s): ' + ', '.join(terms) + '; needs review only'))
    for index, alias in enumerate(aliases):
        if not alias:
            flags.append(('alias_empty', 'alias at index ' + str(index) + ' is empty after normalization'))
    counts = {}
    for alias in aliases:
        counts[alias] = counts.get(alias, 0) + 1
    for alias in sorted(counts):
        if counts[alias] > 1:
            flags.append(('alias_repeated', 'normalized alias repeated ' + str(counts[alias]) + ' times: ' + (alias or '<empty>')))
    if normalized and any(alias == normalized for alias in aliases):
        flags.append(('alias_equals_display', 'an alias equals normalized display'))
    return flags

def _valid_sources(sources):
    valid = []
    if not isinstance(sources, list):
        return valid
    for source in sources:
        if not isinstance(source, dict):
            continue
        if not (source.get('sourceValid') is True and source.get('csvAvailable') is True and source.get('available') is True):
            continue
        lat, lng = _coord(source.get('lat')), _coord(source.get('lng'))
        if lat is None or lng is None or not (_LAT_MIN <= lat <= _LAT_MAX and _LNG_MIN <= lng <= _LNG_MAX):
            continue
        valid.append({'ref': _json_value(source.get('ref')), 'key': _json_value(source.get('key')), 'nameAr': _json_value(source.get('nameAr')), 'lat': lat, 'lng': lng})
    valid.sort(key=lambda item: (_text(item['ref']), _text(item['key']), _text(item['nameAr'])))
    return valid

def _source_check(latv, lngv, valid_sources, sources_provided, selected_id):
    note = 'Numerical distance only; nearest source is not authentic coordinate proof.'
    if not sources_provided:
        return {'status': 'skipped', 'reason': 'sources_not_provided', 'note': note}
    if not valid_sources:
        return {'status': 'skipped', 'reason': 'no_valid_available_sources', 'validSourceCount': 0, 'note': note}
    if latv is None or lngv is None:
        return {'status': 'skipped', 'reason': 'location_coordinates_invalid', 'validSourceCount': len(valid_sources), 'note': note}
    rows = []
    for source in valid_sources:
        meters = _haversine_m(latv, lngv, source['lat'], source['lng'])
        rows.append({'ref': source['ref'], 'key': source['key'], 'nameAr': source['nameAr'], 'distanceMeters': round(meters, 6)})
    rows.sort(key=lambda item: (item['distanceMeters'], _text(item['ref']), _text(item['key'])))
    nearest_ids = {str(item['ref']) for item in rows if item['distanceMeters'] <= rows[0]['distanceMeters'] + 1.0}
    return {'status': 'compared', 'validSourceCount': len(valid_sources), 'nearest3': rows[:3], 'selectedSourceMatches': str(selected_id) in nearest_ids, 'selectedSourceId': selected_id, 'note': note}

def audit_catalog(catalog: dict, governorate: str, sources: list | None = None) -> dict:
    if not isinstance(catalog, dict):
        raise ValueError('catalog must be a dict')
    locations = catalog.get('locations')
    if not isinstance(locations, list):
        raise ValueError('catalog.locations must be a list')
    seen = set()
    for row in locations:
        if isinstance(row, dict) and row.get('id') is not None:
            key = repr(row.get('id'))
            if key in seen:
                raise ValueError('duplicate location id: ' + str(row.get('id')))
            seen.add(key)
    gov = normalize(governorate)
    selected = []
    for row in locations:
        if not isinstance(row, dict) or normalize(row.get('governorateAr')) != gov:
            continue
        diag = _name_diagnostics(row)
        latv, lngv, flags = _coord_flags(row)
        flags.extend(_name_flags(diag))
        selected.append({'raw': row, 'id': _json_value(row.get('id')), 'nameAr': _json_value(row.get('nameAr')), 'norm': diag['normalizedName'], 'parent': diag['normalizedParent'], 'diag': diag, 'latv': latv, 'lngv': lngv, 'flags': flags})
    groups = {}
    for entry in selected:
        groups.setdefault((entry['norm'], entry['parent']), []).append(entry)
    for rows in groups.values():
        if len(rows) < 2:
            continue
        kinds = sorted({_text(item['raw'].get('kind')) for item in rows})
        detail = 'same normalized display and parent; duplicate candidate requires review'
        if len(kinds) > 1:
            detail += '; kinds differ (' + ', '.join(kinds) + '), review administrative levels'
        for entry in rows:
            entry['flags'].append(('duplicate_candidate_same_parent', detail))
    names = {}
    for entry in selected:
        names.setdefault(entry['norm'], []).append(entry)
    findings = []
    for normalized, rows in names.items():
        if not normalized:
            continue
        parents = sorted({item['parent'] for item in rows})
        if len(parents) > 1:
            findings.append({'code': 'namesakes_same_display_different_parent', 'normalizedDisplay': normalized, 'parents': parents, 'ids': sorted(_text(item['id']) for item in rows)})
    findings.sort(key=lambda item: (item['normalizedDisplay'], item['parents']))
    valid_sources = _valid_sources(sources)
    sources_provided = sources is not None
    output_rows = []
    for entry in sorted(selected, key=lambda item: (item['norm'], _text(item['id']), item['parent'], _text(item['nameAr']), repr(item['diag']['normalizedAliases']), repr(sorted(set(item['flags']))))):
        selected_id = (entry['raw'].get('prayerSource') or {}).get('id')
        source = _source_check(entry['latv'], entry['lngv'], valid_sources, sources_provided, selected_id)
        if source.get('selectedSourceMatches') is False:
            entry['flags'].append(('prayer_source_not_nearest', 'selected reference differs from nearest available in supplied snapshot'))
        output_rows.append({'id': entry['id'], 'nameAr': entry['nameAr'], 'flags': [{'code': code, 'detail': detail} for code, detail in sorted(set(entry['flags']))], 'nameDiagnostics': entry['diag'], 'sourceCheck': source})
    issues = {}
    for row in output_rows:
        for flag in row['flags']:
            issues[flag['code']] = issues.get(flag['code'], 0) + 1
    summary = {'catalogLocationCount': len(locations), 'selectedGovernorate': _json_value(governorate), 'selectedCount': len(output_rows), 'flaggedEntries': sum(1 for row in output_rows if row['flags']), 'needsReview': sum(1 for row in output_rows if row['flags']), 'issuesByCode': {code: issues[code] for code in sorted(issues)}, 'sourceSummary': {'provided': sources_provided, 'totalSources': len(sources) if isinstance(sources, list) else 0, 'validAvailableSources': len(valid_sources)}}
    return {'governorate': _json_value(governorate), 'rows': output_rows, 'findings': findings, 'summary': summary, 'qualificationNotes': ['Numerical and name matches are diagnostic only; they are not real-world verification of official names, boundaries, or coordinates.', 'Nearest source coordinates are not treated as authentic coordinate proof.', 'No automatic fixes or deletions are performed; input objects are not modified.', 'Search index diagnostics show normalization echo, search token count, and aliases only; app fuzzy-search behavior is not verified.', 'Latin or uncommon aliases are not declared wrong merely for being Latin or uncommon; alternate names cannot be inferred.', 'No geographic reliability percentages are produced.']}
