"""Prepare an approved V2 selection successor in an isolated workspace; preserve all original evidence."""
from pathlib import Path
import argparse, copy, hashlib, json, os, shutil, re
from datetime import date, datetime, timezone

A = Path('android-app/app/src/main/assets')
N = Path('scripts/neighborhoods')


def h(p):
    return hashlib.sha256(Path(p).read_bytes()).hexdigest()


def read(p):
    return json.loads(Path(p).read_bytes())


def save(p, d):
    p = Path(p)
    p.parent.mkdir(parents=True, exist_ok=True)
    with p.open('x', encoding='utf8', newline='\n') as f:
        f.write(json.dumps(d, ensure_ascii=False, indent=2) + '\n')
    assert read(p) == d


def tree(p):
    return {x.relative_to(p).as_posix(): h(x) for x in Path(p).rglob('*') if x.is_file()}


def copy_exact(src, dst):
    dst = Path(dst)
    dst.parent.mkdir(parents=True, exist_ok=True)
    assert not dst.exists()
    shutil.copy2(src, dst)
    assert h(src) == h(dst)


def link(src, dst):
    dst = Path(dst)
    dst.parent.mkdir(parents=True, exist_ok=True)
    try:
        os.link(src, dst)
    except OSError:
        shutil.copy2(src, dst)
    return dst


def ref(p):
    return {'file': Path(p).relative_to(S / N).as_posix(), 'sha256': h(p)}


def load_records(path):
    path = Path(path)
    try:
        data = json.loads(path.read_bytes())
    except json.JSONDecodeError:
        data = [json.loads(line) for line in path.read_text(encoding='utf8').splitlines() if line.strip()]
    if isinstance(data, list):
        return data
    if isinstance(data, dict):
        if isinstance(data.get('records'), list):
            return data['records']
        if isinstance(data.get('proposedCorrections'), list):
            return data['proposedCorrections']
        if 'delegationId' in data:
            return [data]
    raise AssertionError(f'cannot load paired records from {path}')


def main():
    global W, R, S, REL, E, TARGET_IDS
    ap = argparse.ArgumentParser(description='Prepare an approved reference batch in a new isolated workspace.')
    ap.add_argument('--workspace', required=True, type=Path)
    ap.add_argument('--repository', required=True, type=Path)
    ap.add_argument('--evidence-name', required=True)
    ap.add_argument('--compiler', required=True, type=Path)
    ap.add_argument('--root-decision', required=True, type=Path)
    args = ap.parse_args()
    W, R = args.workspace.resolve(), args.repository.resolve()
    assert R.is_dir() and not W.exists(), 'repository must exist and workspace must be new'
    assert not W.is_relative_to(R) and not R.is_relative_to(W), 'workspace and repository must be separate'
    slug = args.evidence_name
    assert re.fullmatch(r'[a-z0-9][a-z0-9_-]{0,79}', slug), 'invalid evidence folder name'
    assert slug.lower() not in {'con','prn','aux','nul',*[f'com{i}' for i in range(1,10)],*[f'lpt{i}' for i in range(1,10)]}
    S = W/'source'
    REL = 'coordinate-reviews/'+slug
    E = S/N/REL
    assert not (R/N/REL).exists(), 'evidence directory already exists'
    assert args.compiler.is_file() and args.root_decision.is_file()

    dec = read(args.root_decision)
    assert dec['status'] == 'APPROVED_SCOPED_REFERENCE_CORRECTIONS_PENDING_GENERATION'
    TARGET_IDS = dec['sourceIds']
    assert isinstance(TARGET_IDS, list) and TARGET_IDS
    assert all(type(i) is int and i > 0 for i in TARGET_IDS) and len(TARGET_IDS)==len(set(TARGET_IDS))
    assert set(TARGET_IDS).isdisjoint(dec.get('protectedSourceIds', []))
    assert isinstance(dec.get('inputPins'), dict) and dec['inputPins']
    assert set(dec['inputPins']) == set(dec['inputFiles'])
    assert all(Path(p).resolve().is_relative_to(R) for p in dec['inputFiles'].values())

    if 'inputPins' in dec:
        for name, digest in dec['inputPins'].items():
            assert h(dec['inputFiles'][name]) == digest, name

    impact_path = Path(dec['evidence']['impactReview']['file'])
    paired_path = Path(dec['evidence']['pairedInmReview']['file'])
    assert h(impact_path) == dec['evidence']['impactReview']['sha256']
    assert h(paired_path) == dec['evidence']['pairedInmReview']['sha256']

    impact = read(impact_path)
    records = load_records(paired_path)
    assert len(records) == len(TARGET_IDS), len(records)
    assert [r['delegationId'] for r in records] == TARGET_IDS

    for r in records:
        assert r['referenceKind'] == 'inm_published_reference'
        assert r['qualification'] is True
        assert isinstance(r['delegationId'], int)
        assert isinstance(r['expectedGovernorateId'], int)
        assert set(r['original']) == {'lat', 'lng'}
        assert set(r['proposed']) == {'lat', 'lng'}
        assert set(r['expectedNames']) == {'nomFr', 'nomAr', 'nomEn'}
        assert isinstance(r['INMevidence'], dict)
        for kind in ('prayer', 'sun'):
            ev = r['INMevidence'][kind]
            assert isinstance(ev, dict)
            for field in ('url', 'date', 'file', 'sha256'):
                assert field in ev
            assert h(ev['file']) == ev['sha256']
            tr = r['transport'][kind]
            assert tr['sha256'] == ev['sha256']
            assert tr['status'] == 200
            assert tr['requestedUrl'] == ev['url']

    v2 = impact['historicalV1ToStagingV2']
    manual = v2['manualRows']
    assert isinstance(manual, list)
    assert len(manual) == len({r['id'] for r in manual})
    assert [
        {k: r[k] for k in ('id', 'before', 'after')}
        for r in manual
        if r['before'] != r['after']
    ] == dec['expectedManualChanges']
    assert 'expectedRawChanges' in dec and 'expectedManualChanges' in dec
    assert dec['expectedRawChanges'] == v2['expectedRawChanges']
    assert dec['expectedManualChanges'] == v2['expectedManualChanges']

    nbase = tree(R / N)
    abase = tree(R / A)
    cbase = tree(R / A / 'csv')
    old_coord = read(R / N / 'prayer-source-coordinates.json')
    predecessor_selection_reference = copy.deepcopy(old_coord['selectionSuccessor'])
    predecessor_selection_source = R / N / predecessor_selection_reference['file']
    assert h(predecessor_selection_source) == predecessor_selection_reference['sha256']
    predecessor_review = read(predecessor_selection_source)
    old_gov = read(R / A / 'gouvernorats.json')
    old_count = len(old_coord['corrections'])
    assert old_count >= 48
    existing_ids = {c['delegationId'] for c in old_coord['corrections']}
    assert len(existing_ids)==old_count and existing_ids.isdisjoint(TARGET_IDS)
    assert not S.exists() and not (W / 'prepared-stage.json').exists()

    W.mkdir(parents=True, exist_ok=False)
    S.mkdir()
    (S / 'scripts').mkdir()

    def source_copy(src, dst):
        return shutil.copy2(src, dst) if Path(src) == R / N / 'prayer-source-coordinates.json' else link(src, dst)

    shutil.copytree(R / N, S / N, copy_function=source_copy)
    # The source-tree copy already preserves this predecessor path verbatim.
    assert h(S / N / predecessor_selection_reference['file']) == predecessor_selection_reference['sha256']
    copy_exact(args.compiler, S / 'scripts/generate_neighborhoods.py')

    (S / A).mkdir(parents=True)
    copy_exact(R / A / 'gouvernorats.json', S / A / 'gouvernorats.json')
    for name in ('neighborhoods.json', 'neighborhoods.bin', 'retired-localities.json', 'locality-display-names.json'):
        link(R / A / name, S / A / name)
    shutil.copytree(R / A / 'csv', S / A / 'csv', copy_function=link)

    assert not os.path.samefile(R / N / 'prayer-source-coordinates.json', S / N / 'prayer-source-coordinates.json')
    assert not os.path.samefile(R / A / 'gouvernorats.json', S / A / 'gouvernorats.json')

    E.mkdir(parents=True)
    baseline = {}
    for key, name in [('baselineGovernors', 'before-gouvernorats.json'),
                      ('baselineCoordinates', 'before-prayer-source-coordinates.json'),
                      ('baselineMetadata', 'before-neighborhoods.json'),
                      ('baselineGeometry', 'before-neighborhoods.bin')]:
        src = R / N / predecessor_review[key]['file']
        assert h(src) == predecessor_review[key]['sha256']
        baseline[key] = copy.deepcopy(predecessor_review[key])

    predecessor_coordinates_dst = E / 'predecessor-prayer-source-coordinates.json'
    copy_exact(R / N / 'prayer-source-coordinates.json', predecessor_coordinates_dst)
    predecessor_coordinates = ref(predecessor_coordinates_dst)
    predecessor_governors_dst = E / 'predecessor-gouvernorats.json'
    copy_exact(R / A / 'gouvernorats.json', predecessor_governors_dst)
    predecessor_governors = ref(predecessor_governors_dst)

    copy_exact(args.root_decision, E / 'root-decision.json')
    root_decision_ref = ref(E / 'root-decision.json')

    reviews = {}
    for key, meta in dec.get('evidence', {}).items():
        if key not in ('geographicReview', 'mapsReview', 'impactReview'):
            continue
        if not isinstance(meta, dict) or 'file' not in meta:
            continue
        src = Path(meta['file'])
        if 'sha256' in meta:
            assert h(src) == meta['sha256']
        dst = E / f'{key}-{src.name}'
        copy_exact(src, dst)
        reviews[key] = ref(dst)
    assert set(reviews) == {'geographicReview', 'mapsReview', 'impactReview'}

    predecessor_selection_review = predecessor_selection_reference

    originals_dir = E / 'timetable-evidence' / 'originals'
    relocation_map = {}
    manifest_relocation_map = {}
    for r in records:
        for kind in ('prayer', 'sun'):
            ev = r['INMevidence'][kind]
            src = Path(ev['file'])
            assert h(src) == ev['sha256']
            dst = originals_dir / src.name
            if dst.exists():
                assert h(dst) == ev['sha256']
            else:
                copy_exact(src, dst)
            correction_ref = (Path(REL) / 'timetable-evidence' / 'originals' / src.name).as_posix()
            manifest_ref = (Path('originals') / src.name).as_posix()
            relocation_map[str(src)] = correction_ref
            relocation_map[src.as_posix()] = correction_ref
            relocation_map[src.name] = correction_ref
            manifest_relocation_map[str(src)] = manifest_ref
            manifest_relocation_map[src.as_posix()] = manifest_ref
            manifest_relocation_map[src.name] = manifest_ref

    manifest_files = {
        Path(r['transport'][kind]['manifestFile'])
        for r in records
        for kind in ('prayer', 'sun')
    }
    assert len(manifest_files) == 1, manifest_files
    resp_src = next(iter(manifest_files))
    resp_dst = originals_dir / resp_src.name
    assert not resp_dst.exists()
    copy_exact(resp_src, resp_dst)
    correction_ref = (Path(REL) / 'timetable-evidence' / 'originals' / resp_src.name).as_posix()
    manifest_ref = (Path('originals') / resp_src.name).as_posix()
    relocation_map[str(resp_src)] = correction_ref
    relocation_map[resp_src.as_posix()] = correction_ref
    relocation_map[resp_src.name] = correction_ref
    manifest_relocation_map[str(resp_src)] = manifest_ref
    manifest_relocation_map[resp_src.as_posix()] = manifest_ref
    manifest_relocation_map[resp_src.name] = manifest_ref

    derived_dst = E / 'timetable-evidence' / 'derived-retrieval-manifest.json'
    # The frozen source is a JSONL response inventory, not a standalone manifest.
    # Preserve each original receipt field and only relocate its response file.
    response_names = {}
    for r in records:
        for kind in ('prayer', 'sun'):
            response_names[(r['delegationId'], kind)] = Path(r['INMevidence'][kind]['file']).name
    selected = {}
    for line in resp_src.read_text(encoding='utf8').splitlines():
        if not line.strip():
            continue
        item = json.loads(line)
        key = (item.get('delegationId'), item.get('kind'))
        if key in response_names:
            assert key not in selected
            selected[key] = item
    assert set(selected) == set(response_names)
    responses = []
    for key in [(r['delegationId'], kind) for r in records for kind in ('prayer', 'sun')]:
        item = selected[key]
        receipt = copy.deepcopy(item)
        assert isinstance(receipt, dict)
        assert receipt.get('sha256') == item.get('sha256') == next(
            r['INMevidence'][key[1]]['sha256'] for r in records if r['delegationId'] == key[0]
        )
        receipt['file'] = (Path('originals') / response_names[key]).as_posix()
        responses.append(receipt)
    save(derived_dst, {'schemaVersion': 1, 'responses': responses})

    save(E / 'manual-rows.json', {'schemaVersion': impact.get('schemaVersion', 1), 'rows': manual})
    manual_ref = ref(E / 'manual-rows.json')

    reviewed_date = dec.get('reviewedDate', datetime.now(timezone.utc).date().isoformat())
    date.fromisoformat(reviewed_date)
    batch_id = dec.get('reviewBatchId', reviewed_date+'-'+slug)
    service_dates = {r['INMevidence'][k]['date'] for r in records for k in ('prayer','sun')}
    assert len(service_dates)==1
    service_timestamp = service_dates.pop()
    assert re.fullmatch(r'\d{4}-\d{2}-\d{2} 00:00', service_timestamp)
    service_date = service_timestamp[:10]
    date.fromisoformat(service_date)
    scope = dec.get('scope')
    if not scope:
        scope = (
            f'{len(old_coord["corrections"]) + len(records)} reviewed cumulative reference corrections. '
            'No internal calculation or boundary certification.'
        )

    new_gov = copy.deepcopy(old_gov)
    by_id = {r['id']: (g, r) for g in new_gov['gouvernorats'] for r in g['delegations']}
    added = []
    for r in records:
        target = r['delegationId']
        governor, row = by_id[target]
        assert governor['id'] == r['expectedGovernorateId']
        assert r['expectedNames'] == {k: row[k] for k in ('nomAr', 'nomFr', 'nomEn')}

        original = {k: float(r['original'][k]) for k in ('lat', 'lng')}
        proposed = {k: float(r['proposed'][k]) for k in ('lat', 'lng')}
        for k in ('lat', 'lng'):
            assert abs(float(row[k]) - original[k]) < 1e-12, (target, k, row[k], original[k])

        correction = {
            'delegationId': target,
            'expectedGovernorateId': r['expectedGovernorateId'],
            'original': original,
            'proposed': proposed,
            'expectedNames': copy.deepcopy(r['expectedNames']),
            'referenceKind': 'inm_published_reference',
            'inmEvidence': {},
            'reviewedDate': reviewed_date,
            'originalProvenance': {
                'kind': 'legacy-undocumented',
                'sourceAssetSha256': predecessor_governors['sha256'],
            },
            'reviewBatchId': batch_id,
            'originalSourceAssetSha256': predecessor_governors['sha256'],
            'originalSourceAssetEvidence': predecessor_governors,
            'qualification': 'Published nearest-selection reference; no internal calculation or boundary certification.',
        }
        for kind in ('prayer', 'sun'):
            ev = copy.deepcopy(r['INMevidence'][kind])
            src = Path(ev['file'])
            correction['inmEvidence'][kind] = {
                'file': relocation_map[str(src)],
                'sha256': ev['sha256'],
                'url': ev['url'],
            }
        correction['inmEvidence']['serviceDate'] = service_date
        correction['inmEvidence']['retrievalManifest'] = ref(derived_dst)
        row.update(proposed)
        added.append(correction)

    assert [c['delegationId'] for c in added] == TARGET_IDS

    gov_path = S / A / 'gouvernorats.json'
    gov_path.write_text(json.dumps(new_gov, ensure_ascii=False, indent=2) + '\n', encoding='utf8', newline='\n')
    assert read(gov_path) == new_gov

    new_coord = copy.deepcopy(old_coord)
    new_coord['corrections'] += added
    assert len(new_coord['corrections']) == old_count+len(records)
    assert new_coord['corrections'][:len(old_coord['corrections'])] == old_coord['corrections']

    old_batches = copy.deepcopy(old_coord['reviewBatches'])
    batch = {
        'id': batch_id,
        'reviewedDate': reviewed_date,
        'referenceKind': 'inm_published_reference',
        'priorSourceAsset': predecessor_governors,
        'priorCoordinateManifest': predecessor_coordinates,
        'reviewedSourceAssetSha256': h(gov_path),
        'sourceReview': root_decision_ref,
        'retrievalManifest': ref(derived_dst),
        'appliedSourceIds': TARGET_IDS,
        'preservedOriginalCorrectionCount': len(old_coord['corrections']),
        'changedExistingCorrectionIds': [],
        'exactAssetFieldChanges': [
            {
                'delegationId': c['delegationId'],
                'field': k,
                'before': c['original'][k],
                'after': c['proposed'][k],
            }
            for c in added
            for k in ('lat', 'lng')
        ],
        'scope': scope,
    }
    new_coord['reviewBatches'] = old_batches + [batch]
    assert new_coord['reviewBatches'][:len(old_batches)] == old_batches

    identity = {
        'municipalSources': 'municipal-sources.json',
        'curation': 'catalog-curation.json',
        'reviewedBoundaries': 'reviewed-boundaries.json',
        'reviewedPickerGroups': 'reviewed-picker-groups.json',
    }
    review = {
        'schemaVersion': 2,
        'method': 'reviewed_prayer_selection_after_frozen_display_validation',
        'status': dec['status'],
        'sourceSha256': dec.get('sourceSha256'),
        'predecessorCoordinates': predecessor_coordinates,
        'predecessorSelectionReview': predecessor_selection_review,
        **baseline,
        'geographicReview': reviews['geographicReview'],
        'mapsReview': reviews['mapsReview'],
        'impactReview': reviews['impactReview'],
        'manualRows': manual_ref,
        'identityInputs': copy.deepcopy(predecessor_review['identityInputs']),
        'approvedCorrections': copy.deepcopy(predecessor_review['approvedCorrections']) + added,
        'expectedRawChanges': dec['expectedRawChanges'],
        'expectedManualChanges': dec['expectedManualChanges'],
        'availableSourceIds': copy.deepcopy(predecessor_review['availableSourceIds']),
        'rejectedSources': copy.deepcopy(predecessor_review['rejectedSources']),
        'qualification': (
            'Frozen display proofs remain historical. Current nearest-selection uses the cumulative '
            'reviewed published references after full baseline identity and geometry conservation checks.'
        ),
    }
    save(E / 'selection-successor.json', review)

    new_coord.update(
        selectionSuccessor=ref(E / 'selection-successor.json'),
        reviewedDate=reviewed_date,
        reviewedSourceAssetSha256=h(gov_path),
        scope=scope,
    )
    if isinstance(new_coord.get('reviewLimitations'), list) and new_coord['reviewLimitations']:
        new_coord['reviewLimitations'][0] = scope

    coord_path = S / N / 'prayer-source-coordinates.json'
    coord_path.write_text(json.dumps(new_coord, ensure_ascii=False, indent=2) + '\n', encoding='utf8', newline='\n')
    assert read(coord_path) == new_coord

    assert tree(R / N) == nbase
    assert tree(R / A) == abase
    assert tree(R / A / 'csv') == cbase
    if 'inputPins' in dec:
        for name, digest in dec['inputPins'].items():
            assert h(dec['inputFiles'][name]) == digest, name

    save(W / 'prepared-stage.json', {
        'stageRoot': str(S),
        'inputHashes': {'neighborhoods': nbase, 'assets': abase, 'csv': cbase},
        'baselineFilePins': {
            'rootCompiler': h(R / 'scripts/generate_neighborhoods.py'),
            'compilerCandidate': h(args.compiler),
        },
        'rootDecision': {'file': str(args.root_decision), 'sha256': h(args.root_decision)},
        'newEvidenceFiles': tree(E),
        'sourceIds': dec['sourceIds'],
        'correctionsBefore': len(old_coord['corrections']),
        'correctionsAfter': len(new_coord['corrections']),
        'preservedOriginalCorrectionCount': len(old_coord['corrections']),
        'predecessorSelectionReview': predecessor_selection_review,
        'repositoryWritten': False,
    })

    print(json.dumps({
        'stageRoot': str(S),
        'newEvidenceFiles': len(tree(E)),
        'manualRows': len(manual),
        'corrections': len(new_coord['corrections']),
        'selectionReview': ref(E / 'selection-successor.json'),
        'repositoryWritten': False,
    }))


if __name__ == '__main__':
    main()
