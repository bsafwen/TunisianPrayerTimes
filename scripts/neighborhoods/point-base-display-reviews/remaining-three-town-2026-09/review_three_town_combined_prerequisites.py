"""Bounded independent source/provenance/finite-input review; no product imports."""
from pathlib import Path
from datetime import datetime, timezone
import copy
import hashlib
import json
import math
import struct
import xml.etree.ElementTree as ET
import pymupdf
from shapely.geometry import Point, Polygon, MultiPolygon, shape, mapping

T = Path(__file__).resolve().parent.parent
R = Path(r'C:\Users\barou\Desktop\Workspace\TunisianPrayerTimes')
C = R / 'scripts/neighborhoods'
A = R / 'android-app/app/src/main/assets'
OUT = T / 'outputs/remaining-three-town-combined-prerequisites-independent-review.json'

def digest(p):
    return hashlib.sha256(Path(p).read_bytes()).hexdigest()

def pin(p):
    p = Path(p).resolve()
    return {'file': str(p), 'sha256': digest(p), 'bytes': p.stat().st_size}

def read(p):
    return json.loads(Path(p).read_bytes())

def verify(ref):
    assert digest(ref['file']) == ref['sha256'], ref['file']
    if 'bytes' in ref:
        assert Path(ref['file']).stat().st_size == ref['bytes'], ref['file']
    return Path(ref['file']).read_bytes()

def pinned(relative, expected):
    p = T / relative
    assert digest(p) == expected, p
    return read(p), pin(p)

assert not OUT.exists()
source, source_pin = pinned('outputs/remaining-three-town-source-identity-review.json', '8a4b18b96a37d1fb64a7f897a421c6582322862f4b48345b4c5afc6433b38a08')
source_facts, source_facts_pin = pinned('work/remaining-three-town-source-identity/exact-three-town-source-phase-facts.json', 'c44993431b48653f60a0004320a3498c477dc49e6c845e73b7b88a55ed5d012f')
finite, finite_pin = pinned('outputs/remaining-three-town-geography-schema-feasibility.json', '13787c850f50e80077b3ed92ec644e6247b3cee5ef9401313b35768955332402')
facts, facts_pin = pinned('work/remaining-three-town-geography-feasibility/exact-three-geography-source-phase-facts.json', 'ecbd81f7849d241c492af45b212cce0c19e1c00dc1d392048bfb34e58ef48dde')
derived, derived_pin = pinned('outputs/three-town-primary-text-derivation-review.json', '243d67a243a13235a66398d36e2896ba3af8f4941f151a35fcf7093f5e420e75')
json_report, json_pin = pinned('outputs/remaining-three-town-exact-json-source-review.json', '3422357065b29d1f05000da99eb17cfa40652ae63c166a17cbbfa27f8406e6bd')
phone, phone_pin = pinned('outputs/two-village-three-town-phone-review-20260912.json', '74eb8a8fcea72088ac4210792fe2848e23257b0ff0e5cdf482c36d529b9a101b')
inspection, inspection_pin = pinned('work/remaining-three-town-independent-source/independent-source-inspection.json', 'dedf72c24cd431dae504e179f03d51049ed7bf2656446da08e90f17cec7c2077')
live_pins = finite['inputs'] + [pin(R / 'scripts/generate_neighborhoods.py')]
for ref in live_pins:
    verify(ref)
assert live_pins[-1]['sha256'] == '411a7e03e4bd6583c4bddc2d86ed30db22d1bb842a5272fbc39c12470abc2738'

# Reproduce the declared complete-page derivation directly, without running its producer.
assert pymupdf.VersionBind == derived['reproducibleMethod']['bindingVersion'] == '1.28.2'
assert pymupdf.VersionFitz == derived['reproducibleMethod']['mupdfVersion'] == '1.28.2'
assert pymupdf.TEXTFLAGS_TEXT == derived['reproducibleMethod']['textFlagsValue'] == 195
derived_checks = []
for record in derived['records']:
    original = verify(record['originalCachedPdf'])
    assert original == verify(record['preservedOriginalPdfCopy'])
    with pymupdf.open(record['originalCachedPdf']['file']) as pdf:
        assert len(pdf) == record['originalPdfPages']
        for page in record['derivedPages']:
            assert page['sourcePdfPageZeroBased'] == page['sourcePdfPageOneBased'] - 1
            assert verify(page['originalPdf']) == original
            assert page['originalPdfUrl'] == record['originalPdfUrl']
            assert page['artifactKind'] == 'derived_complete_page_text'
            assert page['isOriginalHttpResponseBytes'] is False and page['completePageExtracted'] is True
            txt = pdf[page['sourcePdfPageOneBased'] - 1].get_text('text', flags=pymupdf.TEXTFLAGS_TEXT, sort=False)
            fresh_bytes = txt.encode('utf-8', errors='strict')
            assert fresh_bytes == verify(page['derivedUtf8Text'])
            assert not fresh_bytes.startswith(b'\xef\xbb\xbf') and len(txt) == page['extractionCharacterCount']
            assert [x['phrase'] for x in page['requiredTextLocations']] == page['requiredText']
            for loc in page['requiredTextLocations']:
                phrase = loc['phrase']
                at = txt.index(phrase)
                assert loc['firstCharacterOffset'] == at
                assert loc['firstUtf8ByteOffset'] == len(txt[:at].encode('utf-8'))
                assert loc['firstLineOneBased'] == txt[:at].count('\n') + 1
                assert loc['occurrences'] == txt.count(phrase)
                assert loc['sourcePdfPageOneBased'] == page['sourcePdfPageOneBased']
            derived_checks.append({'placeId': record['placeId'], 'originalPdf': record['originalCachedPdf'],
                'preservedOriginalCopy': record['preservedOriginalPdfCopy'], 'originalPdfUrl': record['originalPdfUrl'],
                'pageOneBased': page['sourcePdfPageOneBased'], 'derivedUtf8Text': page['derivedUtf8Text'],
                'requiredTextLocations': page['requiredTextLocations'], 'exactIndependentReproduction': True,
                'isOriginalHttpResponseBytes': False})
assert len(derived_checks) == 4

cache = read(T / 'work/geo/osm-areas.json')
cache_objects = {x['id']: x for x in cache['nodes'] + cache['areas']}
meta = read(A / 'neighborhoods.json')
rows = {x['id']: x for x in meta['features']}
sf = {x['id']: x for x in source_facts['records']}
json_checks = []
for record in json_report['records']:
    collection = json.loads(verify(record['sourceNode']))
    xml = ET.fromstring(verify(record['comparedOriginalXml']))
    nodes = xml.findall('node')
    assert len(nodes) == 1
    node = nodes[0]
    expected = {'type': 'node', **{k: node.attrib[k] for k in ('timestamp', 'user')}}
    expected.update({k: int(node.attrib[k]) for k in ('id', 'version', 'changeset', 'uid')})
    expected.update({k: float(node.attrib[k]) for k in ('lat', 'lon')})
    expected['tags'] = {t.attrib['k']: t.attrib['v'] for t in node.findall('tag')}
    assert collection['elements'] == [expected] and record['exactElement'] == expected
    sid = record['id']; cached = cache_objects[sid]; row = rows[sid]
    assert expected['tags'] == cached['tags'] == sf[sid]['cachedExactSource']['tags']
    assert expected['lat'] == cached['lat'] == row['lat']
    assert expected['lon'] == cached['lng'] == row['lng']
    assert row == sf[sid]['currentFeature']
    assert record['sourceNode']['url'] == f"https://api.openstreetmap.org/api/0.6/node/{expected['id']}.json"
    assert record['resolvedUrl'] == record['sourceNode']['url'] and record['status'] == 200
    assert record['derivedFromXml'] is False and record['originalResponseBytesPreserved'] is True
    history_file = T / f"work/remaining-three-town-source-identity/osm-node-{expected['id']}-history.xml"
    history = ET.fromstring(history_file.read_bytes()).findall('node')
    assert len(history) == expected['version']
    last = history[-1]
    assert dict(last.attrib) == dict(node.attrib)
    assert {t.attrib['k']: t.attrib['v'] for t in last.findall('tag')} == expected['tags']
    json_checks.append({'id': sid, 'sourceNode': record['sourceNode'], 'comparedCurrentXml': record['comparedOriginalXml'],
        'comparedHistoryXml': pin(history_file), 'version': expected['version'], 'timestamp': expected['timestamp'],
        'changeset': expected['changeset'], 'completeJsonElementExactlyEqualsXml': True,
        'completeTagsAndCoordinatesEqualUntouchedCacheAndCurrentRaw': True,
        'httpProvenance': 'Authentic endpoint retrieval is recorded by the pinned assembler report; this reviewer independently hashed its exact bytes and compared the complete JSON element to the earlier XML/cache, without a redundant fetch.'})
assert [x['version'] for x in json_checks] == [30, 24, 18]

# Direct finite-data cross-check, independent of product helpers and the finite producer.
def gsha(g):
    return hashlib.sha256(json.dumps(mapping(g), separators=(',', ':')).encode()).hexdigest()

def classify(obj):
    tags = obj['tags']
    if 'geometry' not in obj:
        return tags.get('place')
    if tags.get('boundary') == 'administrative':
        return {'2': 'country', '4': 'governorate', '5': 'delegation', '6': 'sector'}.get(tags.get('admin_level'), 'subdistrict')
    return tags.get('place') or ('residential' if tags.get('landuse') == 'residential' else None)

original = {sid: {'tags': obj['tags'], 'kind': classify(obj), 'sourceId': None,
    'geometry': shape(obj['geometry']) if 'geometry' in obj else Point(obj['lng'], obj['lat'])} for sid, obj in cache_objects.items()}
effective = {sid: {**obj, 'tags': dict(obj['tags']), 'sourceId': 'osm'} for sid, obj in original.items()}
manifest = read(C / 'reviewed-boundaries.json')
coverage = read(C / 'coverage.json')
accepted = {}
for provider in manifest['sources']:
    file = C / provider['file']
    assert digest(file) == provider['sha256']
    features = {x['id']: x for x in read(file)['features']}
    for rec in provider['records']:
        sid = rec['id']; feature = features[sid]; prop = feature['properties']
        tags = {'boundary': 'administrative', 'admin_level': '6', 'ref:tn:codegeo': rec['officialCode'],
            'name:ar': prop['nameAr'], 'name:fr': prop['nameFr']}
        if rec.get('aliases'):
            tags['alt_name'] = ';'.join(rec['aliases'])
        effective[sid] = {'tags': tags, 'kind': 'sector', 'sourceId': provider['id'], 'geometry': shape(feature['geometry'])}
        accepted[sid] = (provider, rec, feature)
assert len(manifest['sources']) == 24 and len(accepted) == 77
decisions = {x['id']: x for x in read(C / 'catalog-curation.json')['decisions']}
def name_tag(k):
    return k in ('name','alt_name','alt_name:ar','alt_name:fr','short_name','loc_name','official_name') or k.startswith(('name:','loc_name:','official_name:'))
for sid, dec in decisions.items():
    if sid in effective and 'nameTags' in dec:
        effective[sid]['tags'] = {k: v for k, v in effective[sid]['tags'].items() if not name_tag(k)} | dec['nameTags']
    if dec['action'] == 'exclude':
        effective.pop(sid, None)
blob = (A / 'neighborhoods.bin').read_bytes()
def packed(row):
    part = blob[row['offset']:row['offset'] + row['length']]
    data = iter(struct.unpack('>' + 'i' * (len(part) // 4), part))
    polys = []
    for _ in range(next(data)):
        rings = []
        for _ in range(next(data)):
            ring = [(next(data)/meta['coordinateScale'], next(data)/meta['coordinateScale']) for _ in range(next(data))]
            rings.append(ring)
        polys.append(Polygon(rings[0], rings[1:]))
    assert next(data, None) is None
    return polys[0] if len(polys) == 1 else MultiPolygon(polys)
groups = {}
for row in rows.values():
    groups.setdefault(row['pickerGroupId'], []).append(row['id'])
for members in groups.values():
    members.sort()
assert len(rows) == 3623 and len(groups) == 3490 and len(decisions) == 155
registry = read(T / 'outputs/current-official-sector-registry.json')
governors = read(A / 'gouvernorats.json')['gouvernorats']
base_rows = {d['id']: d for g in governors for d in g['delegations']}
assert coverage['rejectedPrayerSources'] == [{'id': 495, 'reason': 'no_complete_bundled_month'}]
refs = [d for i, d in base_rows.items() if i != 495]
assert len(refs) == facts['referenceInventory']['acceptedCount'] == 258
corrections = read(C / 'prayer-source-coordinates.json')['corrections']
def km(a, b):
    lat1, lat2 = math.radians(a['lat']), math.radians(b['lat'])
    v = math.sin((lat2-lat1)/2)**2 + math.cos(lat1)*math.cos(lat2)*math.sin(math.radians(b['lng']-a['lng'])/2)**2
    return 12742 * math.asin(min(1, math.sqrt(v)))
finite_checks = []
protected = set(); sector_ids = set(); state_ids = set()
for f in facts['candidates']:
    sid, bid = f['id'], f['baseId']
    assert f['currentRaw'] == rows[sid] and f['currentBase'] == base_rows[bid]
    assert rows[sid]['pickerGroupId'] == sid and groups[sid] == [sid]
    assert f'delegation:{bid}' not in groups and sid not in decisions and rows[sid]['hasBoundary'] is False
    assert rows[sid]['delegationId'] == bid and f['originalSourceNode'] == cache_objects[sid]
    assert f['expectedCoordinateCorrections'] == [r for r in corrections if r['delegationId'] == bid] == []
    codes = f['officialDelegationCodes']
    expected_registry = sorted([r for r in registry['sectors'] if r['delegationCode'] in codes], key=lambda x:x['sectorCode'])
    assert expected_registry == sorted([r['officialIdentity'] for r in f['preservedSectors']], key=lambda x:x['sectorCode'])
    local_sectors = {r['id'] for r in f['preservedSectors']}
    local_raw = {r['id'] for r in f['protectedRawRecords']}
    assert local_raw == local_sectors | {sid}
    assert not (protected & local_raw)
    protected |= local_raw; sector_ids |= local_sectors
    for rec in f['protectedRawRecords']:
        row = rows[rec['id']]
        assert row == rec['currentMetadata'] and row['kind'] == rec['kind']
        assert groups[row['pickerGroupId']] == [row['id']]
        expected_sha = hashlib.sha256(blob[row['offset']:row['offset']+row['length']]).hexdigest() if row['hasBoundary'] else None
        assert expected_sha == rec['packedGeometrySha256']
    for rec in f['preservedSectors']:
        obj = original[rec['id']]
        assert rec['originalTags'] == obj['tags']
        assert rec['sourceGeometrySha256'] == gsha(obj['geometry'])
        assert rec['officialIdentity']['sectorCode'] == obj['tags']['ref:tn:codegeo']
        assert rec['expectedCuration'] == decisions.get(rec['id'])
    for state in f['sourceStates']:
        state_ids.add(state['id'])
        for phase, inventory in [('original', original), ('effective', effective)]:
            obj = inventory[state['id']]
            assert state[phase] == {'tags':obj['tags'], 'kind':obj['kind'], 'sourceId':obj['sourceId'], 'geometrySha256':gsha(obj['geometry'])}
    for i, expected in f['curationBindings'].items():
        assert decisions.get(i) == expected
    for replacement in f['acceptedBoundaryReplacements']:
        provider, rec, feature = accepted[replacement['id']]
        assert replacement['sourceRecord'] == provider and replacement['record'] == rec
        assert replacement['geojson'] == {'file':provider['file'], 'sha256':provider['sha256']}
        assert replacement['featureSha256'] == hashlib.sha256(json.dumps(feature, ensure_ascii=False, sort_keys=True, separators=(',',':')).encode()).hexdigest()
        assert [x for x in coverage['reviewedBoundaries']['applications'] if x['id'] == rec['id']] == [replacement['application']]
    before = {key:groups[key] for key in sorted(local_raw)}
    assert before == f['expectedBeforeGroups']
    after = {key: val for key,val in before.items() if key != sid} | {f'delegation:{bid}':[sid]}
    assert after == f['expectedAfterGroups'] and f['multiMemberImadaGroups'] == {}
    actual_containers = set()
    coordinates = {'point':Point(rows[sid]['lng'], rows[sid]['lat']), 'base':Point(base_rows[bid]['lng'], base_rows[bid]['lat'])}
    for phase, inventory in [('original', original), ('effective', effective)]:
        for i, obj in inventory.items():
            if obj['kind'] in ('sector','delegation','governorate') and any(obj['geometry'].covers(p) for p in coordinates.values()):
                actual_containers.add(i)
    assert actual_containers <= local_sectors | set(f['contexts'])
    assert {m['id'] for m in f['coordinateContextMembership']} == local_sectors | set(f['contexts']) | actual_containers
    for membership in f['coordinateContextMembership']:
        i = membership['id']
        for phase, inventory in [('original', original), ('effective', effective)]:
            for role,p in coordinates.items():
                g = inventory[i]['geometry']
                assert membership[phase][role] == {'contains':bool(g.contains(p)), 'covers':bool(g.covers(p))}
        if membership['packed'] is not None:
            g = packed(rows[i])
            for role,p in coordinates.items():
                assert membership['packed'][role] == {'contains':bool(g.contains(p)), 'covers':bool(g.covers(p))}
    rankings = {}
    for role, coord in [('point',rows[sid]), ('base',base_rows[bid])]:
        actual = sorted([{'id':r['id'], 'kilometers':km(coord,r)} for r in refs], key=lambda x:(x['kilometers'],x['id']))
        expected = f['referenceGuards'][role]['ranking']
        assert len(actual) == len(expected) == 258
        for a,b in zip(actual,expected):
            assert a['id'] == b['id'] and abs(a['kilometers']-b['kilometers']) < 1e-9
        assert actual[0]['id'] == bid
        rankings[role] = {'nearest':actual[0], 'runnerUp':actual[1]}
    finite_checks.append({'id':sid, 'baseId':bid, 'preservedSectorCount':len(local_sectors),
        'protectedRawCount':len(local_raw), 'sourceStateCount':len(f['sourceStates']),
        'exactRawAndPackedGeometryParity':True, 'allCurrentCurationBindingsPreserved':True,
        'originalEffectiveAndPackedMembershipReproduced':True, 'allBoundedContainersIncluded':True,
        'nearestReferenceRankings':rankings, 'onlyPermittedRawChange':f['onlyPossibleRawChange'],
        'schemaRoute':f['schemaAssessment'], 'sourceQualifications':f['sourceQualifications']})
assert len(protected) == 44 and len(sector_ids) == 41 and len(state_ids) == 52
proposed_groups = {sid: row['pickerGroupId'] for sid,row in rows.items()}
for f in facts['candidates']:
    proposed_groups[f['id']] = f"delegation:{f['baseId']}"
assert len(set(proposed_groups.values())) == len(groups) == 3490

primary_findings = [
    {'id':'osm:node:287625095', 'finding':'Ministry DGAT/URAM2018 describes the inhabited city, its peripheral sectors and regional urban role, while separately distinguishing North and South administrative delegations.',
     'independentlyViewedFullPdfPages':[26,33,51], 'coordinateQualification':'Current town identity is supported. The retained coordinate originates in version26 bank retag/move; versions27-29 carried town and bank, and version30 removed bank. No surveyed center or meteo anchor certification, relocation or former-coordinate reinstatement is approved.'},
    {'id':'osm:node:264881676', 'finding':'ACTE town profile explicitly identifies Douz in Kebili; ANME programme document PDF6 confirms Douz among the pilot communes. Original HTML and full ANME page were independently inspected.',
     'administrativeQualification':'Obsolete Gabes is_in tags existed in older versions and were removed in version20. Neither Douz North nor Douz South or their sectors is equated with the whole town.'},
    {'id':'osm:node:1165828361', 'finding':'Official Ministry tender06/2017 PDF1 explicitly identifies city flood works in Jemmal. Full page independently viewed.',
     'classificationQualification':'All18 node versions use place=village; raw kind stays village. No source population value is promoted as certified city population.',
     'geographicQualification':'The retained node is within Jemmal El Qeblia325651, while the legacy base anchor lies within Zaouiet Kontoch325655; both are in delegation3256 in original/effective/packed evidence. This difference is retained and does not license a coordinate or sector-boundary change.'}
]
for ref in live_pins:
    verify(ref)
report = {'schemaVersion':1, 'status':'PASS_FOR_ISOLATED_THREE_TOWN_DISPLAY_SOURCE_STAGE_ONLY',
    'createdAt':datetime.now(timezone.utc).isoformat(),
    'scope':'Independent prerequisite acceptance for exactly Jendouba287625095→627, Douz264881676→484, and Jemmal1165828361→595. No staged wrappers, generator acceptance or live installation is approved by this report.',
    'sourceIdentityReview':source_pin, 'sourceIdentityFacts':source_facts_pin,
    'finiteGeographyReview':finite_pin, 'finiteGeographyFacts':facts_pin,
    'primaryTextDerivationReview':derived_pin, 'exactJsonRetrievalReview':json_pin,
    'rootPublicPhoneSummary':phone_pin,
    'independentSourceInspection':inspection_pin,
    'earlierInspectionStatusQualification':'The pinned inspection file deliberately retains its earlier pending visual/finite status. This later report records completion of five full-page visual reviews and the subsequent finite/provenance review without rewriting the historical artifact.',
    'primaryFindings':primary_findings,
    'derivedTextProvenance':{'status':'PASS', 'independentMethod':'Reopen the original pinned PDFs; call PyMuPDF1.28.2 get_text("text", flags=TEXTFLAGS_TEXT=195, sort=False); strict UTF8 encode; compare every byte and requiredText character/byte/line location to the preserved derived artifacts. No producer script invoked.',
        'checks':derived_checks, 'urlQualification':'Primary URLs identify the original PDFs. Derived TXT files are complete page extractions with explicit provenance, never original HTTP response bodies or claims of hosted TXT files.'},
    'exactNodeJsonContinuity':{'status':'PASS', 'checks':json_checks, 'totalHistoryVersionsIndependentlyRead':72},
    'phoneFindingQualification':'Root public Maps city cards/urban outlines provide supplementary named-city corroboration only. This reviewer read the frozen report, did not access the device or ledger, and imports no Google coordinates or polygons. Phone cards do not certify either retained coordinate or an administrative boundary.',
    'finiteIndependentCrossCheck':{'status':'PASS', 'records':finite_checks, 'protectedRawRows':44, 'officialSectors':41,
        'sourceStates':52, 'referenceComparisons':1548, 'rawRecordsBeforeAfter':[3623,3623], 'rawGroupsBeforeAfter':[3490,3490],
        'conditionalPickerRowsBeforeAfter':[3566,3563], 'conditionalCurationCountBeforeAfter':[155,158],
        'qualification':'Ranking re-evaluation is confined to the six retained point/base coordinates against current258 eligible references. Current coverage availability is retained; no CSV eligibility pass or whole-catalog nearest matrix was rerun.'},
    'existingSchemaReview':{'engine':pin(R/'scripts/generate_neighborhoods.py'),
        'sourceNodeContract':'Read lines2027-2035: JSON elements array must match exact sourceNodeIdentity element, tags, coordinates, version/timestamp/changeset. Authentic JSON snapshots satisfy this prerequisite.',
        'primaryEvidenceContract':'Read lines2036-2045: pinned UTF8 with exact requiredText and authority URL. Independently reproduced derived pages truthfully satisfy artifact encoding prerequisite when their original PDF provenance is retained.',
        'routes':'Jendouba and Douz use existing townPreservationExtension with plural delegation inventories and exact administrative_context bindings. Jemmal uses existing sourcePhaseLineage for its two already accepted replacement sectors; neither route changes boundaries.',
        'engineInvoked':False, 'wrapperAcceptanceClaimed':False},
    'stageRequirements':['Append exactly three point/base display decisions; each may change only pickerGroupId on its original point.',
        'Preserve all155 prior curation objects, all41 sectors, both accepted Jemmal replacement records, all current raw rows/coordinates/kinds/parents/names/aliases/defaults, and all base coordinates and prayer references.',
        'Bind the pinned authentic JSON responses, complete primary evidence derivation provenance, frozen source/finite/phone reports, and explicit limitations in the stage.',
        'Retain existing administrative_context records exactly, even where geometry and administrative membership disagree.',
        'Keep stage separate from live repository; require independent staged byte/schema review before any product generation or installation.'],
    'unapprovedOperations':['Coordinate correction','Boundary import or geometry repair','Whole-town/imada equivalence','Raw kind/name/population change','Other locality decisions','Live install','Generator acceptance'],
    'inputsUnchanged':live_pins,
    'operations':{'sourceArtifactsOnly':True, 'productImports':False, 'productHelpers':False, 'generator':False,
        'stageWrites':False, 'liveWrites':False, 'phoneAccess':False, 'ledgerAccess':False, 'tests':False, 'build':False},
    'producer':pin(Path(__file__))}
OUT.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print(json.dumps({'report':pin(OUT), 'status':report['status'], 'derivedPages':len(derived_checks),
    'jsonSnapshots':len(json_checks), 'historyVersions':72, 'rawRows':44, 'sectors':41, 'sourceStates':52}, ensure_ascii=False))
