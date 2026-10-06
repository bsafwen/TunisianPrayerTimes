"""Stage exact current JVM inputs or seal a complete reviewed-family replay.

Each Java invocation is launched separately by the original direct UTC guard.
This program never launches a child or installs assets.
"""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import active_control, read, checked, pin
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree
from scripts.locality_automation.audit_reviewed_source_family import put, validate
from scripts.locality_automation.verify_solo_jvm_audit_v2 import compare
from scripts.locality_automation.work_window_guard import parse_utc


def stage(spec, manifest, output):
    active_control(spec)
    check_tree(spec)
    prepared = read(checked(spec['preparedInputs']))
    check_tree(prepared)
    old_binding = read(checked(spec['priorBinding']))
    original = read(checked(old_binding['manifest']))
    check_tree(original)
    preparation = read(checked(original['geometryPreparation']))
    runtime = read(checked(original['runtimeSourceReview']))
    classes = list(preparation['currentClassPins'].values()) + runtime['compiledGeometryRuntimeFiles']
    if len(classes) != 65:
        raise ValueError('Original complete current JVM class scope differs')
    for reference in classes + preparation['runtimeJarPins']:
        checked(reference)
    for item in original['currentCoreBindings']:
        if checked(item['snapshot']).read_bytes() != checked(item['current']).read_bytes():
            raise ValueError('Current Kotlin core differs from retained compiled classes')
    old = read(checked(original['priorJavaReceipt']))
    argv = old['argv']
    if old['status'] != 'COMPLETED' or old['exitCode'] != 0 or argv[1] != '-cp' or Path(argv[3]).resolve() != checked(original['javaAdapter']).resolve():
        raise ValueError('Original actual Java execution binding differs')
    requests = checked(prepared['actualJvmRequests'])
    points = read(checked(prepared['probes']))['probes']
    if read(requests) != [{k:p[k] for k in ('name','lat','lng','expectedSourceId')} for p in points] or len(points) != prepared['probeCount']:
        raise ValueError('Exact ordered JVM probe requests differ')
    output.mkdir()
    for mode in ('before','after'):
        out = output/mode
        assets = out/'assets'
        assets.mkdir(parents=True)
        saved = {}
        for name,ref in [('neighborhoods.json',prepared[mode+'Metadata']),('neighborhoods.bin',prepared[mode+'Binary']),
                         ('locality-display-names.json',original['names']),('gouvernorats.json',original['governorates'])]:
            path = assets/name
            with path.open('xb') as stream:
                stream.write(checked(ref).read_bytes())
            saved[name] = pin(path)
            if saved[name]['sha256'] != ref['sha256']:
                raise ValueError('Staged JVM asset copy differs')
        result = out/'actual-jvm.json'
        command = [argv[0],'-cp',argv[2],argv[3],str(assets),str(requests),'reviewed-family-'+mode,str(result)]
        put(out/'jvm-input-binding.json',{'status':'PINNED_REVIEWED_SOURCE_STAGED_JVM_INPUTS_NO_ACCEPTANCE',
            'catalogMode':mode,'preparedInputs':spec['preparedInputs'],'currentAssets':saved,'retainedClasses':classes,
            'retainedRuntimeJars':preparation['runtimeJarPins'],'currentCoreBindings':original['currentCoreBindings'],
            'sourceHarness':original['javaAdapter'],'argv':command,'resultPath':str(result),'geographicCredit':0})
    print(json.dumps({'beforeAfterPrepared':True,'probesPerCatalogue':len(points),'classes':len(classes),'actualJvmExecuted':False}))


def seal(spec, manifest, output):
    control = active_control(spec)
    check_tree(spec)
    numeric = read(checked(spec['numericReport']))
    check_tree(numeric)
    audit_spec = read(checked(numeric['manifest']))
    proposal,stage_report,before,after = validate(audit_spec)
    for field in ('indexedExhaustiveFailures','independentFormulaDisagreements','protectedCompleteControlChanges','acceptedGeographicControlChanges'):
        if numeric[field]:
            raise ValueError('Full numeric/oracle/protected-control gate held: '+field)
    count = numeric['probeCount'] * 4
    if numeric['counts'] != {'before':count,'after':count} or sum(numeric['totals'].values()) != count:
        raise ValueError('Complete numeric scope differs')
    joins = {}
    for mode in ('before','after'):
        binding_ref = spec['jvmBindings'][mode]
        binding = read(checked(binding_ref))
        check_tree(binding)
        execution_ref = spec['javaExecutions'][mode]
        receipt = read(checked(execution_ref))
        actual_ref = pin(Path(binding['resultPath']))
        actual = read(checked(actual_ref))
        if (receipt['status'] != 'COMPLETED' or receipt['exitCode'] != 0 or receipt['timedOut']
                or receipt['argv'] != binding['argv'] or receipt['processId'] != actual['processId']
                or not parse_utc(control['windowStartUtc']) <= parse_utc(receipt['startedAtUtc']) < parse_utc(control['safeSourceQaStartUtc'])
                or parse_utc(receipt['finishedAtUtc']) >= parse_utc(control['deadlineUtc'])):
            raise ValueError('Actual Java process/guard/window differs')
        if actual['status'] != 'PASS_ACTUAL_CURRENT_INDEX_GPS_SOURCE_AND_PERSISTENCE' or actual['failures'] or actual['cases'] != count or len(actual['results']) != count:
            raise ValueError('Actual current JVM full scope did not pass')
        observations = 0
        with checked(numeric[mode+'Journal']).open(encoding='utf-8') as stream:
            for i,line in enumerate(stream):
                row = json.loads(line)
                if row['probeOrdinal'] != i//4 or row['modeOrdinal'] != i%4 or not compare(row,actual['results'][i],i):
                    raise ValueError('Whole ordered current JVM label/prayer/persistence differs')
                observations += 1
        if observations != count:
            raise ValueError('Incomplete ordered journal')
        joins[mode] = {'binding':binding_ref,'execution':execution_ref,'actualJvm':actual_ref,
                       'wholeOrderedJournal':numeric[mode+'Journal'],'comparisons':count,'mismatches':0}
    gate = read(checked(numeric['inputGate']))
    check_tree(gate)
    join = put(output.with_name(output.stem+'-jvm-join.json'),{'status':'PASS_FULL_STAGED_BEFORE_AFTER_CURRENT_PURE_JVM_GPS_PRAYER_PERSISTENCE',
        'numericReport':spec['numericReport'],'catalogues':joins,'actualJvmComparisons':2*count,'mismatches':0,
        'geographicCredit':0,'fullAndroidUiOrGpsProviderServicesExecuted':False})
    inputs = {'proposal':audit_spec['proposal'],'beforeJson':stage_report['beforeMetadata'],'beforeBin':stage_report['beforeBinary'],
        'afterJson':stage_report['stagedMetadata'],'afterBin':stage_report['stagedBinary'],'numericReport':spec['numericReport'],
        'completeActualInputGate':numeric['inputGate'],'probes':numeric['probes'],'actualJVMJoin':join,
        'actualBeforeJvm':joins['before']['actualJvm'],'actualAfterJvm':joins['after']['actualJvm'],
        'actualBeforeJvmExecution':joins['before']['execution'],'actualAfterJvmExecution':joins['after']['execution'],
        'wholeOrderedBeforeJournal':numeric['beforeJournal'],'wholeOrderedAfterJournal':numeric['afterJournal'],
        'originalGeometricOracle':audit_spec['oracle'],'numericExhaustiveOriginal':audit_spec['numericOriginal'],
        'sourceScopes':{p['officialCode']:p['sourceScopeReview'] for p in proposal['patches']}}
    result = {'schemaVersion':1,'status':'PASS_OFFLINE_STAGED_REVIEWED_ISIE_WHOLE_IMADA_FULL_SOURCE_GPS',
        'structuralFailures':[],'probeFailures':[],'inputs':inputs,'decodedPatchChecks':gate['decodedPatchChecks'],
        'probeCount':numeric['probeCount'],'lookupComparisons':2*count,'totals':numeric['totals'],
        'actualCurrentJvmBeforeAfterLabelMatches':2*count,'currentJvmPrayerSourceAndManualPersistenceMismatches':0,
        'all3473MetadataIdentityPointParentAndSelectorFieldsPreserved':True,
        'unaffectedPackedBodiesByteIdentical':len(gate['allUnaffectedPackedBodies']),
        'currentFullSourceProtectionCount':len(gate['protectedBodies']),'wholeCatalogueStructuralPreservationAndSfaxBehaviorPassed':True, 'unrelatedFreshBehavioralProbeCount':0,
        'sourceAuthor':'/root','independentReviewer':'/root/sfax_source_review','currentOpposingClaims':gate['currentOpposingClaims'],
        'qualifiedOverlapPolicy':proposal['qualification'],'newCredit':0,'globalWrites':False,
        'limits':['Original complete own ISIE faces retained; literal source seams and opposing catalogue claims are unresolved.',
                  'GPS index/exhaustive/independent GEOS and actual current pure JVM labels, nearest prayer source and persistence passed.',
                  'Actual Android GPS services and Compose flows were not exercised; original installer/A4 still required.'],
        'completedAtUtc':datetime.now(timezone.utc).isoformat()}
    check_tree(inputs)
    put(output,result)
    print(json.dumps({'sealed':str(output),'codes':sorted(result['decodedPatchChecks']),'comparisons':2*count,'geographicCredit':0}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--phase',choices=('stage','seal'),required=True)
    args = parser.parse_args()
    return stage(read(args.manifest),args.manifest,args.output) if args.phase == 'stage' else seal(read(args.manifest),args.manifest,args.output)


if __name__ == '__main__':
    main()
