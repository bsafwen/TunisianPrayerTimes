"""One interior coordinate per changed source body; reuse actual current JVM classes.

No unrelated probe cohorts, preservation audits, disposable consumers or global
source reviews. Existing indexed/exhaustive and independent GEOS formulas run
only on these coordinates. Java is a separate directly guarded leaf phase.
"""
import argparse,importlib.util,json,sys
from datetime import datetime,timezone
from pathlib import Path
from shapely import from_wkb,set_precision
from shapely.geometry import MultiPolygon,Polygon
from shapely.ops import transform,unary_union
from pyproj import Transformer
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import active_control,read,checked
from scripts.locality_automation.stage_boundary_patch import pin
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.packed_gps_replay import PackedGpsReplay
from scripts.locality_automation.run_solo_source_gps_audit import nearest_source,numeric_exhaustive
from scripts.locality_automation.verify_solo_jvm_audit_v2 import compare
from scripts.locality_automation.work_window_guard import parse_utc
from scripts.locality_automation.precision_artifact_policy_v1 import require_precision_artifact

def check_source_patch(patch,after,control,to_m):
    scope=read(checked(patch['sourceScopeReview']))
    if not scope['sourceScopeAccepted'] or scope['boundaryScope']!='full-source-face' or not scope['datedRosterMember'] or scope['sourcePdf']!=patch['sourcePdf'] or scope['geometry']!=patch['geometry']:raise ValueError('Source decision differs')
    raw=from_wkb(checked(patch['rawSourceGeometry']).read_bytes());grid=from_wkb(checked(patch['geometry']).read_bytes());decoded=after.geometry(patch['id'])
    delta=transform(to_m,raw).hausdorff_distance(transform(to_m,decoded))
    rounding={'rawToGridHausdorffM':delta,'metricMethod':'original GEOS vertex Hausdorff','maximumErrorM':.1}
    if delta>=.1 and scope.get('literalConstituentUnion'):
        parts=scope['constituentNativeFacts']
        expected=unary_union([from_wkb(checked(v['rawSourceGeometry']).read_bytes())for v in parts])
        if not raw.equals(expected)or not grid.equals(set_precision(expected,1e-6)):raise ValueError('Exact original constituent union differs')
        component_deltas=[transform(to_m,from_wkb(checked(v['rawSourceGeometry']).read_bytes())).hausdorff_distance(transform(to_m,from_wkb(checked(v['geometry']).read_bytes())))for v in parts]
        source_m,decoded_m=transform(to_m,raw),transform(to_m,decoded)
        source_outside=source_m.difference(decoded_m.buffer(.1));decoded_outside=decoded_m.difference(source_m.buffer(.1))
        if any(v>=.1 for v in component_deltas)or not source_outside.is_empty or not decoded_outside.is_empty:raise ValueError('Constituent union exceeds original 10cm rounding bound')
        rounding.update(metricMethod='Per-constituent original Hausdorff plus mutual 10cm filled-polygon domain enclosure for literal union; tiny sub-grid seam holes may close',componentRawToGridHausdorffM=component_deltas,sourceOutsideDecoded10cmBufferM2=source_outside.area,decodedOutsideSource10cmBufferM2=decoded_outside.area)
    elif delta>=.1:
        source_m,decoded_m=transform(to_m,raw),transform(to_m,decoded)
        rounding['entryOnlyArtifactPolicy']=require_precision_artifact(patch['officialCode'],patch,control,source_m.difference(decoded_m.buffer(.1)).area,decoded_m.difference(source_m.buffer(.1)).area)
    if not decoded.is_valid or not decoded.equals(grid):raise ValueError('Reviewed exact packed body differs')
    result={'id':patch['id'],'valid':True,'equalFinalPatchCoordinates':True,'boundaryScope':'full-source-face','sourceScopeAccepted':True,'sourcePdf':patch['sourcePdf'],'rawSourceGeometry':patch['rawSourceGeometry'],'expectedAdoptedGeometry':patch['geometry'],'sourceScopeReview':patch['sourceScopeReview'],**rounding}
    return grid,result

def prepare(w,control,pool):
    proposal=read(w/(FAMILY+'-proposal.json'));stage=read(STAGE_REPORT)
    if [r['officialCode']for r in proposal['patches']]!=pool:raise ValueError('Finite changed source pool differs')
    before=PackedGpsReplay(checked(stage['beforeMetadata']),checked(stage['beforeBinary']));after=PackedGpsReplay(checked(stage['stagedMetadata']),checked(stage['stagedBinary']))
    previous=w.parent/'root-cycle22';old=read(previous/'minimal-audit-manifest.json');binding=read(previous/'sfax-minimal-jvm-v3/after/jvm-input-binding.json')
    for ref in binding['retainedClasses']+binding['retainedRuntimeJars']+[binding['sourceHarness']]:checked(ref)
    for refs in binding['currentCoreBindings']:
        if checked(refs['snapshot']).read_bytes()!=checked(refs['current']).read_bytes():raise ValueError('Compiled current Kotlin differs')
    spec=importlib.util.spec_from_file_location('retained_independent_formula',checked(old['oracle']));oracle_module=importlib.util.module_from_spec(spec);spec.loader.exec_module(oracle_module)
    oracle=oracle_module.IndependentCurrentOracle.__new__(oracle_module.IndependentCurrentOracle);oracle.replay=after
    class Lazy(dict):
        def __init__(self,loader):super().__init__();self.loader=loader
        def __missing__(self,index):value=self.loader(index);self[index]=value;return value
    oracle.geoms=Lazy(lambda i:after.geometry(after.boundaries[i]['id']));oracle.rings=Lazy(lambda i:after.decode_geometry(after.boundaries[i]['id']))
    oracle.country=MultiPolygon([Polygon(p[0],p[1:])for p in after._country]);exhaustive=numeric_exhaustive(old['numericOriginal'])
    params=read(checked(old['prayerParams']));eligible=[d for g in read(checked(old['governorates']))['gouvernorats']for d in g['delegations']if str(d['id'])in params]
    points=[];checks={};observations=[];failures=[];gains=0
    to_m=Transformer.from_crs(4326,32632,always_xy=True).transform
    grids={}
    for patch in proposal['patches']:
        grid,result=check_source_patch(patch,after,control,to_m)
        grids[patch['officialCode']]=grid;checks[patch['officialCode']]=result
    for index,patch in enumerate(proposal['patches']):
        grid=grids[patch['officialCode']]
        p=grid.representative_point();name=FAMILY+'-minimum-'+patch['officialCode'];expected=nearest_source(eligible,p.y,p.x)
        point={'name':name,'id':name,'lat':p.y,'lng':p.x,'expectedSourceId':expected,'officialCode':patch['officialCode']};points.append(point)
        for mode_index,mode in enumerate((None,5,20,50)):
            actual=after.find(p.y,p.x,mode);ex=exhaustive(after,p.y,p.x,mode);predicted,near=oracle.find(p.y,p.x,mode);prior=before.find(p.y,p.x,mode)
            if actual['winnerId']!=ex or any(actual[k]!=predicted[k]for k in ['winnerId','candidateIds','qualifiedIds','suppressedIds']):failures.append({'code':patch['officialCode'],'mode':mode,'indexed':actual,'oracle':predicted,'exhaustive':ex})
            if actual['winnerId']==patch['id'] and prior['winnerId']!=patch['id']:gains+=1
            observations.append({'probeOrdinal':index,'modeOrdinal':mode_index,'id':name,'lat':p.y,'lng':p.x,'accuracyMeters':mode,'indexed':actual,'exhaustiveWinnerId':ex,'oracle':predicted,'oracleNearExactEdge':near,'expectedSourceId':expected,'beforeWinnerId':prior['winnerId']})
    out=w/(FAMILY+'-minimum-audit-v1');out.mkdir();requests=put(out/'requests.json',[{k:r[k]for k in ['name','lat','lng','expectedSourceId']}for r in points]);put(out/'points.json',points)
    journal=out/'after-observations.jsonl'
    with journal.open('x',encoding='utf-8')as f:
        for r in observations:f.write(json.dumps(r,ensure_ascii=False)+'\n')
    assets=out/'jvm-assets';assets.mkdir()
    for name,ref in [('neighborhoods.json',stage['stagedMetadata']),('neighborhoods.bin',stage['stagedBinary'])]:
        with (assets/name).open('xb')as f:f.write(checked(ref).read_bytes())
    for name in ['locality-display-names.json','gouvernorats.json']:
        with (assets/name).open('xb')as f:f.write((ROOT/'android-app/app/src/main/assets'/name).read_bytes())
    argv=binding['argv'][:4]+[str(assets),str(checked(requests)),FAMILY+'-minimum-after',str(out/'actual-jvm.json')]
    put(out/'jvm-binding.json',{**binding,'argv':argv,'resultPath':str(out/'actual-jvm.json'),'currentAssets':{name:pin(assets/name)for name in ['neighborhoods.json','neighborhoods.bin','locality-display-names.json','gouvernorats.json']},'priorBinding':pin(previous/'sfax-minimal-jvm-v3/after/jvm-input-binding.json'),'catalogMode':FAMILY+'-staged-after','preparedInputs':pin(w/(FAMILY+'-proposal.json'))})
    report={'status':'TARGET_ONLY_NUMERIC_PASS_REQUIRES_ACTUAL_JVM'if not failures else'TARGET_ONLY_NUMERIC_HOLD','proposal':pin(w/(FAMILY+'-proposal.json')),'stage':pin(STAGE_REPORT),'decodedPatchChecks':checks,'probeCount':len(points),'comparisons':len(observations),'probeFailures':failures,'journal':pin(journal),'requests':requests,'sourceSupportedImprovements':gains,'oracle':old['oracle'],'numericOriginal':old['numericOriginal'],'unrelatedLocationsRechecked':0,'newUnaffectedBodyTests':0,'pureJvmClassesReused':True,'geographicCredit':0}
    put(out/'numeric-report.json',report);print(json.dumps({'probes':len(points),'comparisons':len(observations),'failures':failures,'sourceSupportedImprovements':gains}));return 1 if failures else 0

def seal(w,control,pool):
    out=w/(FAMILY+'-minimum-audit-v1');numeric=read(out/'numeric-report.json');binding=read(out/'jvm-binding.json');actual=read(out/'actual-jvm.json');execution=read(w/(FAMILY+'-java-after.execution.json'))
    count=numeric['probeCount']*4
    if numeric['probeFailures']or actual['failures']or actual['status']!='PASS_ACTUAL_CURRENT_INDEX_GPS_SOURCE_AND_PERSISTENCE'or actual['cases']!=count or len(actual['results'])!=count:raise ValueError('Target scope replay did not pass')
    if execution['status']!='COMPLETED'or execution['exitCode']!=0 or execution['timedOut']or execution['argv']!=binding['argv']or execution['processId']!=actual['processId']or not parse_utc(control['windowStartUtc'])<=parse_utc(execution['startedAtUtc'])<parse_utc(control['safeSourceQaStartUtc'])or parse_utc(execution['finishedAtUtc'])>=parse_utc(control['deadlineUtc']):raise ValueError('Actual Java process/guard differs')
    for name,ref in binding['currentAssets'].items():checked(ref)
    rows=[json.loads(line)for line in checked(numeric['journal']).read_text(encoding='utf-8').splitlines()]
    if len(rows)!=count or any(not compare(r,actual['results'][i],i)for i,r in enumerate(rows)):raise ValueError('Ordered actual JVM label/prayer/persistence mismatch')
    stage=read(checked(numeric['stage']));proposal=read(checked(numeric['proposal']))
    if sorted(numeric['decodedPatchChecks'])!=sorted(pool):raise ValueError('Sealed source pool differs')
    inputs={'proposal':numeric['proposal'],'beforeJson':stage['beforeMetadata'],'beforeBin':stage['beforeBinary'],'afterJson':stage['stagedMetadata'],'afterBin':stage['stagedBinary'],'numericReport':pin(out/'numeric-report.json'),'afterJournal':numeric['journal'],'actualJvmBinding':pin(out/'jvm-binding.json'),'actualJvm':pin(out/'actual-jvm.json'),'actualJvmExecution':pin(w/(FAMILY+'-java-after.execution.json')),'originalGeometricOracle':numeric['oracle'],'numericExhaustiveOriginal':numeric['numericOriginal'],'sourceScopes':{p['officialCode']:p['sourceScopeReview']for p in proposal['patches']}}
    put(w/(FAMILY+'-accepted-gps-report.json'),{'status':'PASS_OFFLINE_STAGED_TARGET_ONLY_ISIE_SOURCE_GPS','structuralFailures':[],'probeFailures':[],'inputs':inputs,'decodedPatchChecks':numeric['decodedPatchChecks'],'probeCount':numeric['probeCount'],'lookupComparisons':count,'totals':{'source_supported_wrong_to_correct':numeric['sourceSupportedImprovements']},'actualCurrentJvmComparisons':count,'sourceAuthor':'/root','technicalAuditActor':'/root','independentAgentReviewClaimed':False,'unrelatedLocationsRechecked':0,'unaffectedBodiesRevalidated':0,'fullCatalogueA4Rerun':False,'geographicCredit':0,'limits':['One interior point per changed source body, four existing accuracy modes; minimal target-only check at explicit user direction.','Complete source coordinates separately matched decoded staged bodies. Original thin seams, gaps and conservative conflict fallback retained.','Actual current pure JVM labels, nearest eligible prayer source and saved-state behavior checked. Android UI/GPS services not exercised.'],'completedAtUtc':datetime.now(timezone.utc).isoformat()})
    print(json.dumps({'sealed':True,'codes':pool,'actualJvmComparisons':count,'unrelatedLocationChecks':0}))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--family',required=True);p.add_argument('--work',type=Path,required=True);p.add_argument('--phase',choices=['prepare','seal'],required=True);p.add_argument('--stage-report',type=Path);a=p.parse_args();FAMILY=a.family;w=a.work.resolve();STAGE_REPORT=a.stage_report.resolve(strict=True)if a.stage_report else w/(FAMILY+'-stage-v1/stage-report.json');assert STAGE_REPORT.is_relative_to(w);c=read(w.parent/'control.json');s={k:c[k]for k in ['iteration','windowStartUtc','deadlineUtc']};s.update(control=str(w.parent/'control.json'),owner='/root');active_control(s)
    result=prepare(w,c,c['approvedCycle'+str(c['iteration'])+'AcceptancePool'])if a.phase=='prepare'else seal(w,c,c['approvedCycle'+str(c['iteration'])+'AcceptancePool']);raise SystemExit(result or 0)


