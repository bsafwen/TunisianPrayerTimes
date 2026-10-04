"""Run the unchanged full installer and report consumer on disposable copies.

The exact whole baseline report must first reproduce with its sole run-ledger
read routed to an authentic copy. No acceptance gate or live destination changes.
"""
import argparse
import contextlib
from datetime import datetime, timezone
import io
import json
from pathlib import Path
import sys
import time
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import active_control, checked, read, pin
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree
from scripts.locality_automation import install_reviewed_boundary_patch as consumer
from scripts.locality_automation.packed_decode_cache import reuse_reader_packed_slices
from scripts.locality_automation.isolated_consumer_preflight import inspect_workspace, save_diagnostic
from scripts.locality_automation.audit_reviewed_source_family import put


def exercise(spec, manifest, output):
    control = active_control(spec)
    check_tree(spec)
    evidence = Path(spec['evidenceRoot']).resolve(strict=True)
    sys.path.insert(0,str(evidence/'work/locality-progress-dashboard'))
    import task_report_data as reader
    reader_ref = pin(Path(reader.__file__))
    if reader_ref != spec['reader']:
        raise ValueError('Original complete A4 consumer differs')
    gps_path = checked(spec['gps'])
    gps = read(gps_path)
    check_tree(gps['inputs'])
    if gps['status'] != 'PASS_OFFLINE_STAGED_REVIEWED_ISIE_WHOLE_IMADA_FULL_SOURCE_GPS' or gps['structuralFailures'] or gps['probeFailures']:
        raise ValueError('Whole independent GPS gate did not pass')
    codes = set(gps['decodedPatchChecks'])
    if codes != set(control['approvedCycle22AcceptancePool']):
        raise ValueError('Consumer family differs from admitted pool')
    run_path,handoff_path = checked(spec['run']),checked(spec['handoff'])
    run_before,handoff_before = run_path.read_bytes(),handoff_path.read_bytes()
    consumer_ref = pin(Path(consumer.__file__))
    decoder_ref = pin(ROOT/'scripts/locality_automation/packed_gps_replay.py')
    output.mkdir()
    fake_repo = output/'workspace'
    assets = fake_repo/'android-app/app/src/main/assets'
    assets.mkdir(parents=True)
    asset_refs,dependencies = {},[]
    relative_files = ['android-app/app/src/main/assets/'+name for name in ('neighborhoods.json','neighborhoods.bin','locality-display-names.json')]
    relative_files += ['scripts/locality_automation/packed_gps_replay.py','scripts/locality_automation/multipart_pdf_evidence_v2.py']
    relative_files += [item['relativePath'] for item in spec['historicalDependencies']]
    for relative in relative_files:
        source = ROOT/relative
        ref = pin(source)
        declared = next((r['source'] for r in spec['historicalDependencies'] if r['relativePath'] == relative),None)
        if declared is not None and declared != ref:
            raise ValueError('Historical dependency differs')
        destination = fake_repo/relative
        destination.parent.mkdir(parents=True,exist_ok=True)
        with destination.open('xb') as stream:
            stream.write(checked(ref).read_bytes())
        if pin(destination)['sha256'] != ref['sha256']:
            raise ValueError('Disposable copy differs')
        if not relative.endswith(('assets/neighborhoods.json','assets/neighborhoods.bin')):
            dependencies.append({'relativePath':relative,'source':ref})
        if relative.startswith('android-app/app/src/main/assets/'):
            asset_refs[Path(relative).name] = ref
    fake_run,fake_handoff = output/'run.json',output/'handoff.json'
    fake_run.write_bytes(run_before);fake_handoff.write_bytes(handoff_before)
    clock = datetime.now(timezone.utc)
    original_loader = reader.load_json
    calls = []
    def route_ledger(path):
        if Path(path).resolve() == run_path.resolve():
            calls.append(str(fake_run))
            return original_loader(fake_run)
        return original_loader(path)
    def build(routed):
        old = reader.load_json
        try:
            if routed:
                reader.load_json = route_ledger
            ledger = original_loader(fake_run if routed else run_path)
            active_decoder = pin(Path(ledger['installedCatalog']['file']).parents[5]/'scripts/locality_automation/packed_gps_replay.py')
            if active_decoder['sha256'] != decoder_ref['sha256']:
                raise ValueError('Actual dynamically selected decoder differs')
            with reuse_reader_packed_slices(reader,reader_ref,active_decoder):
                return reader.build_task_report(now=clock,root=evidence,events_path=evidence/'work/locality-progress-dashboard/task-events.jsonl')
        finally:
            reader.load_json = old
    started = time.perf_counter()
    baseline,baseline_copy = build(False),build(True)
    put(output/'baseline-model.json',baseline)
    if baseline_copy != baseline or calls != [str(fake_run)]:
        raise ValueError('Complete baseline model not exactly reproduced')
    fields = {'uniqueValidatedLocations':'geographic','explicitFullSourceBoundaryLocationCount':'completeSourceBodies',
              'uniqueInstalledCorrectionLocations':'installedCorrections'}
    if any(baseline['summary'][field] != control['startedBaseline'][key] for field,key in fields.items()):
        raise ValueError('Current baseline counts changed')
    if baseline['summary']['validationIssues'] or baseline['summary']['reportingIssues']:
        raise ValueError('Original complete baseline consumer has issues')
    original_repo = consumer.REPO
    try:
        consumer.REPO = fake_repo
        with contextlib.redirect_stdout(io.StringIO()):
            consumer.apply(SimpleNamespace(run=fake_run,handoff=fake_handoff,gps=gps_path,
                                           output=output/'install-proof',transaction=output/'transaction'))
    finally:
        consumer.REPO = original_repo
    clock = datetime.now(timezone.utc)
    # Fail cheaply on the actual dynamic dependencies before full post-install A4.
    for filename,key in [('neighborhoods.json','afterJson'),('neighborhoods.bin','afterBin')]:
        if pin(assets/filename)['sha256'] != gps['inputs'][key]['sha256']:
            raise ValueError('Disposable installed asset differs from exact staged after pair')
    installed_ref = pin(output/'install-proof/practical-receipt.json')
    preflight = {'schemaVersion':1,'sourceRepository':str(ROOT),'workspaceRepository':str(fake_repo),
                 'requiredDependencies':dependencies,'runLedger':pin(fake_run),
                 'selectedDecoder':pin(fake_repo/'scripts/locality_automation/packed_gps_replay.py'),
                 'installedReceipt':installed_ref,'reportClockUtc':clock.isoformat()}
    preflight_ref = put(output/'preflight-manifest.json',preflight)
    diagnostic = inspect_workspace(preflight)
    diagnostic_ref = save_diagnostic(output/'preflight-result.json',diagnostic)
    if diagnostic['status'] != 'PASS_ISOLATED_CONSUMER_PREFLIGHT':
        raise ValueError('Exact disposable dependency preflight held')
    after = build(True)
    put(output/'full-a4-model-before-assertion.json',after)
    summary = after['summary']
    expected = {}
    for field,key,listfield in [('uniqueValidatedLocations','geographic','validatedLocationCodes'),
                                ('explicitFullSourceBoundaryLocationCount','completeSourceBodies','explicitFullSourceBoundaryLocalityCodes')]:
        union = set(baseline['summary'][listfield]) | codes
        if set(summary[listfield]) != union or summary[field] != len(union):
            raise ValueError('Original complete consumer code union differs')
        expected[key] = len(union)
    # Each admitted family code is new to geographic and correction sets.
    if codes.intersection(baseline['summary']['validatedLocationCodes']):
        raise ValueError('Family unexpectedly includes accepted locations')
    expected['installedCorrections'] = baseline['summary']['uniqueInstalledCorrectionLocations'] + len(codes)
    if (summary['uniqueInstalledCorrectionLocations'] != expected['installedCorrections']
            or summary['validationIssues'] or summary['reportingIssues'] or not summary['practicalChainVerified']):
        raise ValueError('Original full installation/report gates held')
    if run_path.read_bytes() != run_before or handoff_path.read_bytes() != handoff_before:
        raise ValueError('Live ledgers changed during disposable review')
    for ref in asset_refs.values():
        checked(ref)
    check_tree(spec)
    result = {'status':'PASS_COMPLETE_ORIGINAL_BOUNDARY_INSTALLER_AND_A4_ISOLATED','manifest':pin(manifest),
              'gps':spec['gps'],'originalConsumer':consumer_ref,'originalA4Reader':reader_ref,'originalDecoder':decoder_ref,
              'fullBaselineA4InputAdapterEqualityVerified':True,'singleRoutedInput':str(run_path),
              'isolatedRun':pin(fake_run),'isolatedHandoff':pin(fake_handoff),'isolatedOriginalPracticalReceipt':installed_ref,
              'fullA4Model':pin(output/'full-a4-model-before-assertion.json'),'preflightManifest':preflight_ref,
              'preflightResult':diagnostic_ref,'testedCounts':expected,'liveAssetsUnchanged':True,'liveLedgersUnchanged':True,
              'geographicCredit':0,'elapsedSeconds':time.perf_counter()-started,'noAcceptanceGateChanged':True,
              'completedAtUtc':datetime.now(timezone.utc).isoformat()}
    put(output/'verification.json',result)
    print(json.dumps({'status':result['status'],'testedCounts':expected,'elapsedSeconds':result['elapsedSeconds'],'liveWrites':False}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args = parser.parse_args()
    return exercise(read(args.manifest),args.manifest,args.output)


if __name__ == '__main__':
    main()
