"""Prepare and replay a finite independently reviewed whole-source family.

Original probe construction, indexed/exhaustive formulas and independent GEOS
oracle are retained. Whole catalogue controls run once per coherent family.
This read-only helper grants no installation or geographic credit.
"""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys

from pyproj import Transformer
from shapely import from_wkb
from shapely.geometry import Point
from shapely.ops import transform

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import active_control, checked, pin, read
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree
from scripts.locality_automation.packed_gps_replay import PackedGpsReplay
from scripts.locality_automation.run_solo_source_gps_audit import numeric_exhaustive, nearest_source, protected_bodies


def put(path, value):
    with path.open('x', encoding='utf-8') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.flush()
        os.fsync(stream.fileno())
    return pin(path)


def load(path):
    spec = importlib.util.spec_from_file_location('unchanged_family_oracle', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def validate(spec):
    control = active_control(spec)
    check_tree(spec)
    proposal = read(checked(spec['proposal']))
    codes = [str(p['officialCode']) for p in proposal['patches']]
    if not codes or len(codes) != len(set(codes)) or sorted(codes) != sorted(control['approvedCycle22AcceptancePool']):
        raise ValueError('Exact independently admitted finite source pool required')
    if proposal['status'] != 'SOURCE_SCOPE_REVIEWED_REQUIRES_GPS_AND_CONSUMER':
        raise ValueError('Reviewed source proposal required')
    for patch in proposal['patches']:
        scope = read(checked(patch['sourceScopeReview']))
        check_tree(scope)
        if (scope['officialCode'] != str(patch['officialCode']) or scope['id'] != patch['id']
                or not scope['sourceScopeAccepted'] or scope['sourceAuthor'] != '/root'
                or scope['actualReviewer'] != '/root/sfax_source_review'
                or not scope['sourceScopeIndependentReviewerReal']
                or patch['boundaryScope'] != 'full-source-face' or patch['datedRosterMember'] is not True):
            raise ValueError('Actual disjoint whole-imada source decision required')
        for key in ('sourcePdf', 'rawSourceGeometry'):
            if scope[key] != patch[key]:
                raise ValueError('Reviewed original source differs')
        if scope['expectedAdoptedGeometry'] != patch['geometry']:
            raise ValueError('Reviewed canonical source differs')
        if scope['freshNativeChecks']['freshNativePointsEqualInventory'] is not True or scope['freshNativeChecks']['maxIndependentRegisteredVertexDifferenceM'] != 0:
            raise ValueError('Actual independent original native coordinates did not match')
    stage = read(checked(spec['stage']))
    check_tree(stage)
    before = PackedGpsReplay(checked(stage['beforeMetadata']), checked(stage['beforeBinary']))
    after = PackedGpsReplay(checked(stage['stagedMetadata']), checked(stage['stagedBinary']))
    return proposal, stage, before, after


def prepare(spec, manifest, output):
    proposal, stage, before, after = validate(spec)
    if output.exists():
        raise ValueError('Preserve existing audit attempts')
    changed = {p['id'] for p in proposal['patches']}
    if set(stage['changedIds']) != changed:
        raise ValueError('Staged scope differs')
    bb = checked(stage['beforeBinary']).read_bytes()
    ab = checked(stage['stagedBinary']).read_bytes()
    if len(before.features) != 3473 or len(before.boundaries) != 2571 or len(after.boundaries) != 2571:
        raise ValueError('Complete catalogue universe differs')
    if [r['id'] for r in before.features] != [r['id'] for r in after.features] or before._country != after._country:
        raise ValueError('Identity order or country geometry differs')
    preservation = []
    for old in before.features:
        new = after.by_id[old['id']]
        allowed = {'offset', 'length'} | ({'bbox', 'areaKm2', 'sourceId'} if old['id'] in changed else set())
        if {k:v for k,v in old.items() if k not in allowed} != {k:v for k,v in new.items() if k not in allowed}:
            raise ValueError('Metadata identity/point/parent/selector changed: ' + old['id'])
        if old['id'] not in changed and old['hasBoundary']:
            a = bb[old['offset']:old['offset'] + old['length']]
            b = ab[new['offset']:new['offset'] + new['length']]
            if a != b:
                raise ValueError('Unaffected packed body changed: ' + old['id'])
            preservation.append({'id':old['id'], 'packedSliceSha256':hashlib.sha256(a).hexdigest()})
    original = read(checked(spec['originalProtectedGate']))
    bindings = [r['originalSourceBinding'] for r in original['protected323OriginalBindings']]
    protection = protected_bodies(after, ab, {'bindingCount':323, 'bindings':bindings})
    for reference in spec['additionalProtectedScopes']:
        scope = read(checked(reference))
        expected = from_wkb(checked(scope['expectedAdoptedGeometry']).read_bytes())
        ident = scope['id'] if 'id' in scope else scope['featureId']
        if not expected.equals(after.geometry(ident)):
            raise ValueError('Additional protected whole source changed')
        r = after.by_id[ident]
        protection.append({'officialCode':str(scope['officialCode']), 'id':ident,
                           'expectedAdoptedGeometry':scope['expectedAdoptedGeometry'],
                           'authoritativeReference':reference,
                           'packedCurrentSha256':hashlib.sha256(ab[r['offset']:r['offset']+r['length']]).hexdigest()})
    report = read(checked(spec['baselineReport']))
    mapdata = read(checked(spec['baselineMap']))
    full = set(report['summary']['explicitFullSourceBoundaryLocalityCodes'])
    geo_codes = {r['id']:str(r['code']) for r in mapdata['locations']}
    if (len(protection) != 325 or full != {r['officialCode'] for r in protection}
            or len(geo_codes) != 463 or len(set(geo_codes.values())) != 463
            or changed.intersection(geo_codes)):
        raise ValueError('Complete current accepted protection differs')
    oracle = load(checked(spec['oracle']))
    tm = Transformer.from_crs(4326, 32632, always_xy=True).transform
    ll = Transformer.from_crs(32632, 4326, always_xy=True).transform
    points, seen, cases, peers = [], {}, {}, {}
    def add(point, code, label):
        key = point['lat'], point['lng']
        provenance = {'officialCode':code, 'catalogueBody':label, 'sourceClass':point['sourceClass']}
        if key in seen:
            points[seen[key]]['sourceProvenance'].append(provenance)
        else:
            seen[key] = len(points)
            points.append({**point, 'sourceClass':label+'-'+point['sourceClass'],
                           'sourceProvenance':[provenance], 'controlId':None, 'controlCode':None})
    for patch in proposal['patches']:
        ident, code = patch['id'], str(patch['officialCode'])
        raw = from_wkb(checked(patch['rawSourceGeometry']).read_bytes())
        expected = from_wkb(checked(patch['geometry']).read_bytes())
        old, new = before.geometry(ident), after.geometry(ident)
        if not raw.is_valid or not expected.is_valid or not new.is_valid or not new.equals(expected):
            raise ValueError('Decoded source differs or invalid')
        raw_m, old_m, new_m = [transform(tm, g) for g in (raw, old, new)]
        if raw_m.hausdorff_distance(new_m) >= .1:
            raise ValueError('Canonical source exceeds original app-grid tolerance')
        incidents, observed = [], []
        left,bottom,right,top = raw.union(old).union(new).bounds
        for row in after.features:
            if not row['hasBoundary'] or row['id'] == ident:
                continue
            a,b,c,d = row['bbox']
            if c < left or a > right or d < bottom or b > top:
                continue
            peer = after.geometry(row['id'])
            if not peer.is_valid:
                raise ValueError('Invalid incident catalogue body')
            metric = transform(tm, peer)
            values = {}
            for label, body in [('raw',raw_m),('before',old_m),('after',new_m)]:
                overlap = body.intersection(metric)
                values[label] = overlap.area
                if not overlap.is_empty:
                    incidents.append((overlap,label+'-peer-overlap-supporting-only'))
            observed.append({'id':row['id'], 'geographicValidatedCode':geo_codes.get(row['id']),
                             'completeSourceValidated':geo_codes.get(row['id']) in full,
                             'intersectionSquareMeters':values})
        peers[code] = observed
        for label, metric, geographic, replay in [('raw',raw_m,raw,after),('app-grid',new_m,new,after),('live',old_m,old,before)]:
            for p in oracle.source_probes(metric,ll,geographic,replay.by_id[ident],incidents):
                add(p,code,label)
        cases[code] = {'id':ident, 'valid':True, 'equalFinalPatchCoordinates':True,
                       'featureId':ident, 'boundaryScope':'full-source-face', 'sourceScopeAccepted':True,
                       'freshNativeCoordinatesMatchPinnedSource':True, 'freshCanonicalGeometryMatches':True,
                       'sourceDeltaJustification':patch['qualification'],
                       'sourcePdf':patch['sourcePdf'], 'rawSourceGeometry':patch['rawSourceGeometry'],
                       'expectedAdoptedGeometry':patch['geometry'], 'sourceScopeReview':patch['sourceScopeReview'],
                       'metrics':{'rawSourceAreaM2':raw_m.area, 'adoptedAreaM2':new_m.area,
                                  'symmetricDifferenceAreaM2':raw_m.symmetric_difference(new_m).area,
                                  'hausdorffDistanceM':raw_m.hausdorff_distance(new_m)}}
    for row in before.features:
        points.append({'lat':row['lat'], 'lng':row['lng'], 'sourceClass':'all-current-manual-point-control',
                       'controlId':row['id'], 'controlCode':geo_codes.get(row['id']), 'sourceProvenance':[]})
        if row['hasBoundary']:
            p = before.geometry(row['id']).representative_point()
            points.append({'lat':p.y, 'lng':p.x, 'sourceClass':'all-current-body-interior-control',
                           'controlId':row['id'], 'controlCode':geo_codes.get(row['id']), 'sourceProvenance':[]})
    params = read(checked(spec['prayerParams']))
    eligible = [d for g in read(checked(spec['governorates']))['gouvernorats'] for d in g['delegations'] if str(d['id']) in params]
    if len(eligible) != 258 or any(d['id'] == 495 for d in eligible):
        raise ValueError('Original eligible prayer universe differs')
    for i,p in enumerate(points):
        p['id'] = p['name'] = 'sfax-family-' + str(i).zfill(6)
        p['expectedSourceId'] = nearest_source(eligible,p['lat'],p['lng'])
    output.mkdir()
    probes = put(output/'probes.json',{'probes':points})
    requests = put(output/'actual-jvm-requests.json',[{k:p[k] for k in ('name','lat','lng','expectedSourceId')} for p in points])
    gate = {'manifest':pin(manifest),'stage':spec['stage'],'protectedBodies':protection,
            'allUnaffectedPackedBodies':preservation,'allMetadataIdentityPointParentSelectorFieldsPreserved':True,
            'geographicControlCodes':sorted(geo_codes.values()),'completeSourceCodes':sorted(full),
            'decodedPatchChecks':cases,'currentOpposingClaims':peers,'probes':probes,'requests':requests,
            'sourceAuthor':'/root','actualReviewer':'/root/sfax_source_review','geographicCredit':0}
    gate_ref = put(output/'input-gate.json',gate)
    prepared = {'status':'PREPARED_SOURCE_SCOPE_STAGED_FULL_PROBES_NO_NUMERIC_ACCEPTANCE','inputGate':gate_ref,
                'probes':probes,'actualJvmRequests':requests,'probeCount':len(points),
                'beforeMetadata':stage['beforeMetadata'],'beforeBinary':stage['beforeBinary'],
                'afterMetadata':stage['stagedMetadata'],'afterBinary':stage['stagedBinary'],'geographicCredit':0}
    put(output/'prepared-jvm-inputs.json',prepared)
    print(json.dumps({'phase':'prepared','probeCount':len(points),'cases':len(cases),'protectedBodies':len(protection),
                      'unaffectedBodies':len(preservation),'geographicCredit':0}),flush=True)


def numeric(spec, manifest, output):
    proposal,stage,before,after = validate(spec)
    prepared = read(output/'prepared-jvm-inputs.json')
    check_tree(prepared)
    gate = read(checked(prepared['inputGate']))
    points = read(checked(prepared['probes']))['probes']
    full,geo = set(gate['completeSourceCodes']),set(gate['geographicControlCodes'])
    module = load(checked(spec['oracle']))
    oracles = {'before':module.IndependentCurrentOracle(before),'after':module.IndependentCurrentOracle(after)}
    exhaustive = numeric_exhaustive(spec['numericOriginal'])
    failures,disagreements,protected_changes,accepted_changes = [],[],[],[]
    changes,counts,totals = [],Counter(),Counter()
    tm = Transformer.from_crs(4326,32632,always_xy=True).transform
    raw = {p['id']:from_wkb(checked(p['rawSourceGeometry']).read_bytes()) for p in proposal['patches']}
    metric = {ident:transform(tm,g) for ident,g in raw.items()}
    started = datetime.now(timezone.utc).isoformat()
    with (output/'before-observations.jsonl').open('x',encoding='utf-8') as prior, (output/'after-observations.jsonl').open('x',encoding='utf-8') as successor:
        for i,p in enumerate(points):
            for j,mode in enumerate((None,5,20,50)):
                outcomes = {}
                for label,replay,stream in [('before',before,prior),('after',after,successor)]:
                    found = replay.find(p['lat'],p['lng'],mode)
                    winner = exhaustive(replay,p['lat'],p['lng'],mode)
                    predicted,near = oracles[label].find(p['lat'],p['lng'],mode)
                    row = {'probeOrdinal':i,'modeOrdinal':j,'id':p['id'],'lat':p['lat'],'lng':p['lng'],
                           'accuracyMeters':mode,'indexed':found,'exhaustiveWinnerId':winner,'oracle':predicted,
                           'oracleNearExactEdge':near,'expectedSourceId':p['expectedSourceId'],'sourceClass':p['sourceClass']}
                    stream.write(json.dumps(row,ensure_ascii=False,allow_nan=False)+'\n')
                    counts[label] += 1
                    if found['winnerId'] != winner:
                        failures.append({'catalogue':label,'probeOrdinal':i,'modeOrdinal':j})
                    if any(found[k] != predicted[k] for k in ('winnerId','candidateIds','qualifiedIds','suppressedIds')):
                        disagreements.append({'catalogue':label,'probeOrdinal':i,'modeOrdinal':j,'nearExactEdge':near})
                    outcomes[label] = found
                a,b = outcomes['before'],outcomes['after']
                if a != b:
                    change = {'probeOrdinal':i,'modeOrdinal':j,'before':a,'after':b,'sourceClass':p['sourceClass']}
                    changes.append(change)
                    if p['controlCode'] in full:
                        protected_changes.append(change)
                    if p['controlCode'] in geo:
                        accepted_changes.append(change)
                point = Point(p['lng'],p['lat']); ident = b['winnerId']
                if not p['controlId'] and ident in raw and raw[ident].contains(point):
                    if a['winnerId'] != ident and metric[ident].boundary.distance(transform(tm,point)) > 50:
                        totals['source_supported_wrong_to_correct'] += 1
                    else:
                        totals['supported_current_source_owner_retained'] += 1
                else:
                    totals['qualified_peer_edge_control_or_fallback_uncredited'] += 1
            for stream in (prior,successor):
                stream.flush();os.fsync(stream.fileno())
            if (i+1) % 1000 == 0:
                print(json.dumps({'completed':i+1,'total':len(points),'numericFailures':len(failures),
                                  'oracleDisagreements':len(disagreements),'acceptedControlChanges':len(accepted_changes)}),flush=True)
    totals.setdefault('source_supported_wrong_to_correct',0)
    check_tree(spec)
    result = {'status':'STAGED_FULL_SOURCE_NUMERIC_REQUIRES_JVM_AND_CONSUMER','manifest':pin(manifest),
              'inputGate':prepared['inputGate'],'probes':prepared['probes'],'actualJvmRequests':prepared['actualJvmRequests'],
              'probeCount':len(points),'counts':dict(counts),'indexedExhaustiveFailures':failures,
              'independentFormulaDisagreements':disagreements,'protectedCompleteControlChanges':protected_changes,
              'acceptedGeographicControlChanges':accepted_changes,'beforeJournal':pin(output/'before-observations.jsonl'),
              'afterJournal':pin(output/'after-observations.jsonl'),'changes':put(output/'before-after-changes.json',changes),
              'totals':dict(totals),'actualJvmExecuted':False,'geographicCredit':0,'payloadPid':os.getpid(),
              'startedAtUtc':started,'finishedAtUtc':datetime.now(timezone.utc).isoformat()}
    put(output/'report.json',result)
    print(json.dumps({k:result[k] for k in ('status','probeCount','counts','totals')},ensure_ascii=False),flush=True)
    return 1 if failures or disagreements or protected_changes or accepted_changes else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--phase',choices=('prepare','numeric'),required=True)
    args = parser.parse_args()
    spec = read(args.manifest)
    return prepare(spec,args.manifest,args.output) if args.phase == 'prepare' else numeric(spec,args.manifest,args.output)


if __name__ == '__main__':
    raise SystemExit(main())
