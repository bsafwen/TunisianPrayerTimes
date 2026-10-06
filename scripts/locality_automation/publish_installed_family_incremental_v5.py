"""Publish one accepted family incrementally; no unrelated-location revalidation."""
import argparse
from copy import deepcopy
from datetime import datetime,timezone,timedelta
import json
from pathlib import Path
import sys

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put


def publish(work,evidence,receipt,family_slug,family_label,install_execution=None):
    control=read(work.parent/'control.json')
    spec={k:control[k]for k in ['iteration','windowStartUtc','deadlineUtc']}
    spec.update(control=str(work.parent/'control.json'),owner='/root');active_control(spec)
    public=evidence/'work/locality-progress-dashboard';sys.path.insert(0,str(public))
    import task_report_data as data
    import boundary_map_data as maps
    import task_report_app as app
    before=read(work/('before-'+family_slug+'-publication-task-report.json'))
    oldmap=read(work/('before-'+family_slug+'-publication-boundary-map.json'))
    practical_ref=pin(work/(family_slug+'-live-reviewed-install-v1/practical-receipt.json'))
    practical=read(checked(practical_ref));gps=read(checked(practical['gpsEvidence']))
    codes=practical['boundaryLocalityCodes']
    if codes!=control['approvedCycle'+str(control['iteration'])+'AcceptancePool']:raise ValueError('Current family scope differs')
    if any(v['id']=='practical-'+practical_ref['sha256'][:16]for v in before['validations']):raise ValueError('Batch was already published')
    new_codes=sorted(set(codes)-set(before['summary']['validatedLocationCodes']))
    upgraded_codes=sorted((set(codes)&set(before['summary']['validatedLocationCodes']))-set(before['summary']['explicitFullSourceBoundaryLocalityCodes']))
    corrected_codes=sorted(set(codes)&set(before['summary']['explicitFullSourceBoundaryLocalityCodes']))
    if set(new_codes)|set(upgraded_codes)|set(corrected_codes)!=set(codes) or set(practical['fullSourceBoundaryLocalityCodes'])!=set(codes):raise ValueError('Exact complete-body category partition required')
    incidents=read(work/(family_slug+'-retained-incidents-v1.json'))
    proved={r['code']for r in incidents.get('provedPriorBodyCorrections',[])}
    if set(corrected_codes)!=proved or proved!=set(control.get('currentCorrectionExistingCompleteCodes',[])):raise ValueError('Previously full corrections require exact source/staged-geometry proof')
    for correction in incidents.get('provedPriorBodyCorrections',[]):
        for key in ('priorAcceptedScope','freshOriginal','freshNative','freshAdopted','sourceReview'):checked(correction[key])
        if correction['newGeographicCredit']!=0 or correction['newFullBodyCredit']!=0:raise ValueError('Existing full correction cannot earn duplicate credit')
    registry=read(ROOT/'scripts/neighborhoods/official/arima-joufia-receivers-20260912/identity/current-official-sector-registry.json')
    names={str(row['sectorCode']):row['sectorAr']for row in registry['sectors']if str(row['sectorCode'])in codes}
    refs=[data.evidence(practical_ref['file'],practical_ref['sha256'],'Installed '+family_label+' boundary receipt','receipt'),
          data.evidence(practical['gpsEvidence']['file'],practical['gpsEvidence']['sha256'],'Completed target-only GPS replay','gps'),
          data.evidence(practical['installerReceipt']['file'],practical['installerReceipt']['sha256'],'Installation receipt','installation')]
    at=data.exact_time(practical['appliedAtUtc']);now=datetime.now(timezone.utc)
    validation={'id':'practical-'+practical_ref['sha256'][:16],'occurredAtUtc':at.isoformat(),
        'locationCodes':codes,'locationNames':[names[c]for c in codes],'kind':'installed_gps_scoped',
        'qualified':True,'qualification':practical['qualification'],'fullBoundaryClaim':False,
        'boundaryScopeKnown':True,'fullSourceBoundaryLocalityCodes':codes,'scopedBoundaryLocalityCodes':[],
        'unknownBoundaryScopeLocationCodes':[],'deviceVerification':False,'probeCount':gps['probeCount'],
        'lookupComparisons':gps['lookupComparisons'],'evidenceRefs':refs}
    model=deepcopy(before);model['generatedAtUtc']=now.isoformat();model['validations'].append(validation)
    summary=model['summary']
    for count,key in [('uniqueValidatedLocations','validatedLocationCodes'),('explicitFullSourceBoundaryLocationCount','explicitFullSourceBoundaryLocalityCodes'),('uniqueAcceptedSourceFaceLocations','acceptedSourceFaceLocationCodes')]:
        summary[key]=sorted(set(summary[key])|set(codes));summary[count]=len(summary[key])
    prior=read(checked(practical['previousPracticalProgress']))
    added_installed=set(practical['cumulativeBoundaryLocalityCodes'])-set(prior.get('cumulativeBoundaryLocalityCodes',prior['boundaryLocalityCodes']))
    # The published baseline also includes two installed historical corrections
    # outside this chain. Carry them through; count only the new chain delta.
    summary['uniqueInstalledCorrectionLocations']=before['summary']['uniqueInstalledCorrectionLocations']+len(added_installed)
    summary['latestPackageCorrectedLocationCount']=len(codes);summary['latestValidatedAtUtc']=at.isoformat()
    summary['historicalValidationStateReused']=True;summary['unrelatedLocationsRecheckedForThisPublication']=0
    baseline_pins=summary['verifiedPracticalBaselinePins']
    if baseline_pins:baseline_pins[0]['supersededAtUtc']=at.isoformat()
    baseline_pins.insert(0,{'receipt':practical_ref,'afterJson':gps['inputs']['afterJson'],'afterBin':gps['inputs']['afterBin'],'appliedAtUtc':at.isoformat(),'supersededAtUtc':None})
    model['limits'].append('This '+family_label+'-only incremental publication reuses earlier accepted report/map rows without rechecking unrelated locations. One interior point per changed body, using the existing four accuracy modes; actual current JVM classes reused. Source/native/paired/legal review is root only under the no-subagent instruction.')
    for ref in model['sourceFiles']:
        if ref.get('kind')=='pointer':ref['sha256']=data.sha(Path(ref['path']))
    hours={h['hour']:h for h in model['hours']};series={h['hour']:h for h in model['cumulativeSeries']}
    execution_path=install_execution or work/(family_slug+'-live-install.execution.json')
    execution=read(execution_path)
    if execution['status']!='COMPLETED' or execution['exitCode']!=0 or execution['timedOut']:raise ValueError('Actual installation phase did not complete')
    output_arg=execution['argv'][execution['argv'].index('--output')+1]
    output_path=Path(output_arg)
    if not output_path.is_absolute():output_path=Path(execution['cwd'])/output_path
    if output_path.resolve()!=checked(practical_ref).parent.resolve():raise ValueError('Execution receipt is not the installed practical package')
    start=data.exact_time(control['windowStartUtc']);end=data.exact_time(execution['finishedAtUtc'])
    validation_hour=data.hour_key(at);cursor=data.exact_time(max(hours)).replace(minute=0,second=0,microsecond=0)
    while cursor<=now:
        key=data.hour_key(cursor)
        if key not in hours:
            hours[key]={'hour':key,'label':data.hour_label(key),'validatedLocationCount':0,'validatedLocationCodes':[],
                'validationIds':[],'newValidatedLocationCount':0,'newValidatedLocationCodes':[],
                'repeatValidatedLocationCount':0,'repeatValidatedLocationCodes':[],'revalidatedWithinHourLocationCodes':[],
                'additionalWithinHourValidationCount':0,'fullSourceBoundaryLocationCount':0,'fullSourceBoundaryLocalityCodes':[],
                'scopedBoundaryLocationCount':0,'scopedBoundaryLocalityCodes':[],'unknownBoundaryScopeLocationCodes':[],
                'historicalSourceAcceptanceCount':0,'sourceOnlyAcceptanceIds':[],'taskCount':0,'locationGroups':[]}
        hour=hours[key];left=data.exact_time(key);right=left+timedelta(hours=1)
        duration=max(0,(min(end,right)-max(start,left)).total_seconds())
        if duration:
            task={'taskId':family_slug+'-cycle'+str(control['iteration']),'title':family_label+': '+str(len(codes))+' complete source boundaries',
                'locationCodes':codes,'locationNames':[names[c]for c in codes],'status':'complete',
                'startedAtUtc':start.isoformat(),'endedAtUtc':end.isoformat(),'durationKnown':True,
                'durationSeconds':(end-start).total_seconds(),'recordedDurationSeconds':(end-start).total_seconds(),
                'timeInHourSeconds':round(duration,3),'timeInHourKnown':True,'validationIds':[validation['id']],
                'resultLabel':'Validated complete source boundaries','resultShort':str(len(new_codes))+' additions, '+str(len(upgraded_codes))+' full-body upgrades and '+str(len(corrected_codes))+' existing-body corrections installed.',
                'result':'Original native sheets, adjoining-source facts, decree roster and useful actual emulator satellite views reviewed. Minimal target-only replay passed; no unrelated-location rechecks. Original transaction with backups. Shared elapsed clock includes source preparation and review.',
                'evidenceRefs':refs}
            hour['locationGroups'].append({'key':'+'.join(codes),'locationCodes':codes,'locationNames':[names[c]for c in codes],
                'sharedClock':True,'validatedLocationCodes':codes if key==validation_hour else[],'tasks':[task]})
            hour['taskCount']+=1
        if key==validation_hour:
            for field in ['validatedLocationCodes','fullSourceBoundaryLocalityCodes']:hour[field]=sorted(set(hour[field])|set(codes))
            hour['newValidatedLocationCodes']=sorted(set(hour['newValidatedLocationCodes'])|set(new_codes))
            hour['repeatValidatedLocationCodes']=sorted(set(hour['repeatValidatedLocationCodes'])|set(upgraded_codes)|set(corrected_codes))
            hour['repeatValidatedLocationCount']=len(hour['repeatValidatedLocationCodes'])
            hour['validatedLocationCount']=len(hour['validatedLocationCodes']);hour['newValidatedLocationCount']=len(hour['newValidatedLocationCodes'])
            hour['fullSourceBoundaryLocationCount']=len(hour['fullSourceBoundaryLocalityCodes']);hour['validationIds'].append(validation['id'])
        if key not in series:
            series[key]={'hour':key,'label':hour['label'],'validatedLocationCount':hour['validatedLocationCount'],
                'validatedLocationCodes':hour['validatedLocationCodes'],'newValidatedLocationCount':hour['newValidatedLocationCount'],
                'repeatValidatedLocationCount':hour['repeatValidatedLocationCount'],
                'cumulativeValidatedLocationCount':summary['uniqueValidatedLocations']if left>=data.exact_time(validation_hour)else before['summary']['uniqueValidatedLocations'],
                'cumulativeValidatedLocationCodes':summary['validatedLocationCodes']if left>=data.exact_time(validation_hour)else before['summary']['validatedLocationCodes'],
                'cumulativeInstalledCorrectionLocationCount':summary['uniqueInstalledCorrectionLocations']if left>=data.exact_time(validation_hour)else before['summary']['uniqueInstalledCorrectionLocations'],
                'cumulativeExplicitFullSourceBoundaryLocationCount':summary['explicitFullSourceBoundaryLocationCount']if left>=data.exact_time(validation_hour)else before['summary']['explicitFullSourceBoundaryLocationCount']}
        # This hour may already exist from a prior publication; update it too.
        if key==validation_hour:
            series[key].update(validatedLocationCount=hour['validatedLocationCount'],validatedLocationCodes=hour['validatedLocationCodes'],newValidatedLocationCount=hour['newValidatedLocationCount'],repeatValidatedLocationCount=hour['repeatValidatedLocationCount'],cumulativeValidatedLocationCount=summary['uniqueValidatedLocations'],cumulativeValidatedLocationCodes=summary['validatedLocationCodes'],cumulativeInstalledCorrectionLocationCount=summary['uniqueInstalledCorrectionLocations'],cumulativeExplicitFullSourceBoundaryLocationCount=summary['explicitFullSourceBoundaryLocationCount'])
        cursor+=timedelta(hours=1)
    model['hours']=sorted(hours.values(),key=lambda h:h['hour'],reverse=True);model['cumulativeSeries']=sorted(series.values(),key=lambda h:h['hour'])
    # Use the existing map renderer only on this twelve-location batch. Old map
    # rows are copied as published; no old geometry/evidence checks are invoked.
    original_loader=maps.load_json
    run_path=evidence/'work/isie-execution-20260926/run.json'
    def source_loader(path):
        value=original_loader(path)
        if Path(path).resolve()==run_path.resolve():
            value={**value,'resolvedBoundaryReviewNotes':[],'boundaryReviewNotes':[]}
        return value
    maps.load_json=source_loader
    try:family=maps.build_boundary_map({'generatedAtUtc':model['generatedAtUtc'],'validations':[validation],'summary':{'validatedLocationCodes':codes}},root=evidence)
    finally:maps.load_json=original_loader
    batch=family['batches'][0];batch['label']=family_label+' · '+str(len(new_codes))+' NEW · '+str(len(upgraded_codes))+' FULL upgrades · '+str(len(corrected_codes))+' corrected'
    batch['newLocationCodes']=new_codes;batch['fullScopeUpgradeCodes']=upgraded_codes;batch['sourceBodyCorrectionCodes']=corrected_codes
    old_by_code={r['code']:r for r in oldmap['locations']}
    for row in batch['locations']:
        row['newLocation']=row['code']in new_codes;row['fullScopeUpgrade']=row['code']in upgraded_codes;row['correctedExistingBody']=row['code']in corrected_codes
    for row in family['locations']:
        code=row['code'];row['newLocation']=code in new_codes;row['fullScopeUpgrade']=code in upgraded_codes;row['correctedExistingBody']=code in corrected_codes
        if code in old_by_code:
            old=old_by_code[code];row['history']=old['history']+row['history'];row['firstValidatedAtUtc']=old['firstValidatedAtUtc']
    merged={**oldmap,'generatedAtUtc':model['generatedAtUtc'],'latestBatchId':family['latestBatchId'],'currentAssets':family['currentAssets'],
        'uniqueLocationCount':oldmap['uniqueLocationCount']+len(new_codes),'locations':[r for r in oldmap['locations']if r['code']not in codes]+family['locations'],
        'batches':family['batches']+oldmap['batches'],'incrementalPublication':True,'unrelatedLocationsRechecked':0}
    merged['locations'].sort(key=lambda row:row['code'])
    merged['limits']=oldmap['limits']+['Previous accepted boundaries carried forward without revalidation. Latest '+family_label+' batch contains complete installed source bodies; NEW additions and FULL upgrades are separate.']
    original_build,original_map,original_frina,original_argv=app.build_task_report,app.build_boundary_map,app.refresh_frina_investigation,sys.argv
    try:
        app.build_task_report=lambda:model;app.build_boundary_map=lambda unused:merged
        app.refresh_frina_investigation=lambda:None
        sys.argv=[str(public/'task_report_app.py'),'refresh'];app.main()
    finally:
        app.build_task_report=original_build;app.build_boundary_map=original_map;app.refresh_frina_investigation=original_frina;sys.argv=original_argv
    put(receipt,{'status':'PUBLISHED_INCREMENTAL_SOURCE_BATCH_USING_STOCK_LOCK_AND_RENDERER',
        'locationCodes':codes,'newLocationCount':len(new_codes),'newLocationCodes':new_codes,'fullScopeUpgradeCodes':upgraded_codes,'sourceBodyCorrectionCodes':corrected_codes,'geographicCount':summary['uniqueValidatedLocations'],
        'completeBodyCount':summary['explicitFullSourceBoundaryLocationCount'],'installedCorrectionCount':summary['uniqueInstalledCorrectionLocations'],
        'practicalReceipt':practical_ref,'historicalResultsReusedWithoutRechecking':True,'unrelatedLocationChecks':0,
        'additionalBehavioralTestsExecuted':0,'fullCatalogueA4Rerun':False,'privateGoogleConfigRead':False,
        'actualBrowserRenderingTested':False,'outputs':[pin(public/name)for name in ['task-report.json','boundary-map.json','dashboard.html']],
        'publishedAtUtc':datetime.now(timezone.utc).isoformat()})

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for n in ['work','evidence','receipt']:p.add_argument('--'+n,type=Path,required=True)
    p.add_argument('--family-slug',required=True);p.add_argument('--family-label',required=True);p.add_argument('--install-execution',type=Path)
    args=p.parse_args();publish(args.work.resolve(),args.evidence.resolve(),args.receipt.resolve(),args.family_slug,args.family_label,args.install_execution.resolve() if args.install_execution else None)
