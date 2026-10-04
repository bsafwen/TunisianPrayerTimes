"""Add one source-reviewed display override, preserving exact spatial assets.

No GPS replay is needed for an override-only asset. Its receipt records the exact
spatial byte invariance, zero new behavioral cases and zero geographic credit.
"""
import argparse,json,sys,os,copy
from pathlib import Path
from datetime import datetime,timezone
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.installer import install

def atomic(path,value):
 temporary=path.with_name(path.name+'.display-name.tmp');temporary.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');os.replace(temporary,path)

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--plan',type=Path,required=True);p.add_argument('--work',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True);a=p.parse_args();w=a.work.resolve();e=a.evidence.resolve();cp=w.parent/'control.json';c=read(cp);spec={k:c[k]for k in ['iteration','windowStartUtc','deadlineUtc']};spec.update(control=str(cp),owner='/root');active_control(spec);plan=read(a.plan)
 if plan['status']!='ROOT_ACCEPTED_HUMAN_MINISTRY_DISPLAY_CORRECTION':raise ValueError('Explicit reviewed display-name decision required')
 for ref in plan['sourcePins']:checked(ref)
 scope=read(checked(plan['acceptedSourceScope']));code=plan['officialCode'];ident=plan['id'];name=plan['nameAr']
 if scope['officialCode']!=code or scope['id']!=ident or not scope['sourceScopeAccepted']or code not in c['allFamilyOfficialCodes']:raise ValueError('Display override must bind an accepted source identity in this family')
 assets=R/'android-app/app/src/main/assets';catalogue=assets/'neighborhoods.json';binary=assets/'neighborhoods.bin';live=assets/'locality-display-names.json';row=next(r for r in read(catalogue)['features']if r['id']==ident)
 if row['name']!=plan['isieName']or not set([row['name'],name,*row['aliases']])<=set(plan['searchAliases']):raise ValueError('Exact former primary and all existing search forms must remain')
 runp=e/'work/isie-execution-20260926/run.json';hp=e/'work/saved-review-controller-20260920/running-handoff.json';runbytes=runp.read_bytes();handoffbytes=hp.read_bytes();run=read(runp);previous_ref=run['practicalProgress'];previous=read(checked(previous_ref));oldref=previous['nameEvidence'];old=read(checked(oldref));beforebytes=live.read_bytes();before=read(live)
 if before!=read(checked(old['after']))or pin(live)['sha256']!=previous['installedAssets'][live.name]['sha256']:raise ValueError('Live override baseline differs from the accepted frozen name evidence')
 if before['schemaVersion']!=1 or set(before)!={'schemaVersion','names'}or any(r['id']==ident for r in before['names']):raise ValueError('This addition helper requires an absent override and supported schema')
 added={'id':ident,'nameAr':name,'searchAliases':plan['searchAliases']};after=copy.deepcopy(before);after['names'].append(added)
 text=beforebytes.decode('utf-8');position=text.rfind(']');fragment=(','if before['names']else'')+json.dumps(added,ensure_ascii=False,separators=(',',':'));afterbytes=(text[:position]+fragment+text[position:]).encode('utf-8')
 if json.loads(afterbytes)!=after or len({r['id']for r in after['names']})!=len(after['names']):raise ValueError('Exact one-row addition or unique runtime identity differs')
 out=w/('display-name-'+code);out.mkdir();(out/'before-locality-display-names.json').write_bytes(beforebytes);(out/'locality-display-names.json').write_bytes(afterbytes)
 unchanged={}
 for asset in [catalogue,binary]:
  snapshot=out/('unchanged-'+asset.name);snapshot.write_bytes(asset.read_bytes());unchanged[asset.name]=pin(snapshot)
 delta={'id':ident,'officialCode':code,'action':'display_name','before':None,'after':added};qualification=plan['qualification'];now=datetime.now(timezone.utc).isoformat()
 proof=put(out/'name-source-delta-proof.json',{'status':'PASS_REVIEWED_SINGLE_DISPLAY_OVERRIDE_ADDITION','plan':pin(a.plan.resolve()),'acceptedSourceScope':plan['acceptedSourceScope'],'sourcePins':plan['sourcePins'],'delta':delta,'before':pin(out/'before-locality-display-names.json'),'after':pin(out/'locality-display-names.json'),'existingOverrideBytesPreservedByInsertion':True,'originalCatalogueAndPackedGeometry':unchanged,'geometryChanged':False,'otherOverrideRowsChanged':False,'runtimeParserShapeValidatedForAddedRow':True,'formerPrimarySearchable':row['name']in added['searchAliases'],'existingFrenchAndArabicAliasesPreserved':True,'sourceAuthor':'/root','actualReviewer':'/root','independentAgentReviewClaimed':False,'additionalGpsCases':0,'geographicCredit':0})
 names={'schemaVersion':1,'status':'STAGED_NAME_SEARCH_PATCH','previousNameEvidence':oldref,'before':pin(out/'before-locality-display-names.json'),'after':pin(out/'locality-display-names.json'),'displayRenames':old['displayRenames']+1,'aliasOnlyUpdates':old['aliasOnlyUpdates'],'changes':old['changes']+[delta],'deltaChanges':[delta],'deltaDisplayRenames':1,'deltaAliasOnlyUpdates':0,'countsQualification':'One effective display rename only; no geographic or installed-boundary credit. Historical name/search changes remain pinned.','rootNameSourceDeltaProof':proof,'unrelatedNamesPreserved':True,'geometryChanged':False,'metadataFrenchAliasesPreserved':True,'runtimeParserShapeValidated':True,'qualification':qualification}
 if 'retiredNames'in old:names['retiredNames']=old['retiredNames']
 nameref=put(out/'name-successor-report.json',names);dest='android-app/app/src/main/assets/locality-display-names.json';target=names['after'];expected=pin(live)['sha256'];files=[{'sourceSha256':target['sha256'],'destination':dest,'beforeSha256':expected}]
 validation=put(out/'validation.json',{'status':'READY_TO_INSTALL','qualification':qualification,'files':files,'issues':[],'unresolvedCaseIds':[],'nameProof':proof});manifest={'schemaVersion':1,'validation':validation,'files':[{'source':target,'destination':dest,'beforeSha256':expected}]};put(out/'manifest.json',manifest)
 result=install(manifest,str(R),str(out/'transaction'),[],None);installerref=put(out/'installer-receipt.json',result)
 if result['status']!='INSTALLED'or live.read_bytes()!=afterbytes or any((assets/n).read_bytes()!=checked(ref).read_bytes()for n,ref in unchanged.items()):raise ValueError('Single override installation or exact spatial bytes differ')
 # The unchanged spatial baseline is carried as an invariance proof, not rerun.
 inputs={'beforeJson':unchanged[catalogue.name],'afterJson':unchanged[catalogue.name],'beforeBin':unchanged[binary.name],'afterBin':unchanged[binary.name],'nameSourceDeltaProof':proof,'previousQualifiedGeometryGps':previous['gpsEvidence']}
 gpsref=put(out/'spatial-invariance-report.json',{'status':'PASS_OFFLINE_STAGED_NAME_ONLY_GPS','structuralFailures':[],'probeFailures':[],'decodedPatchChecks':{},'inputs':inputs,'probeCount':0,'lookupComparisons':0,'additionalBehavioralTestsExecuted':0,'qualification':'Exact neighborhood metadata and packed geometry byte invariance. The existing accepted GPS baseline is retained; no GPS, prayer-source or saved-state behavior was rerun or newly asserted.','geographicCredit':0})
 practical={'schemaVersion':1,'status':'INSTALLED_SCOPED_PRACTICAL_CORRECTIONS','nameOnlyPackage':True,'appliedAtUtc':now,'qualification':qualification,'boundaryLocalityCodes':[],'boundaryLocalitiesCorrected':0,'fullSourceBoundaryLocalityCodes':[],'scopedBoundaryLocalityCodes':[],'boundaryScopeRecorded':True,'excludedFromDatedRosterCodes':[],'datedRosterMembershipRecorded':True,'boundaryMetadataChanges':[],'cumulativeBoundaryLocalityCodes':previous['cumulativeBoundaryLocalityCodes'],'previousPracticalProgress':previous_ref,'nameEvidence':nameref,'displayNamesImproved':names['displayRenames'],'officialSearchAliasesAdded':names['aliasOnlyUpdates'],'gpsEvidence':gpsref,'installerReceipt':installerref,'manifest':pin(out/'manifest.json'),'installedAssets':{p.name:pin(p)for p in [catalogue,binary,live]},'gpsProbeCount':0,'gpsLookupComparisons':0,'sourceSupportedImprovedComparisons':0,'wrongOwnerProbeFailures':0,'preservedHistoricalInputs':{'metadata':unchanged[catalogue.name],'binary':unchanged[binary.name]},'androidCompiled':False,'apkGenerated':False,'deviceVerification':False,'proxyChanged':False};practicalref=put(out/'practical-receipt.json',practical)
 if runp.read_bytes()!=runbytes or hp.read_bytes()!=handoffbytes:raise ValueError('Concurrent practical pointer edit; preserve installed receipt for reconciliation')
 (out/'before-run.json').write_bytes(runbytes);(out/'before-handoff.json').write_bytes(handoffbytes);handoff=read(hp)
 for value,path in [(run,runp),(handoff,hp)]:value['practicalProgress']=practicalref;value.setdefault('displayNameAmendments',{})[code]=proof;atomic(path,value)
 public=e/'work/locality-progress-dashboard';model=read(public/'task-report.json');m=read(public/'boundary-map.json');before_counts=copy.deepcopy(model['summary']);affected=0
 for filename in ['task-report.json','boundary-map.json']:(out/('before-'+filename)).write_bytes((public/filename).read_bytes())
 for r in [*m['locations'],*(r for b in m['batches']for r in b['locations'])]:
  if r['code']==code:r['name']=name;r['displayNameAmendment']=proof;affected+=1
 m['generatedAtUtc']=now;model['generatedAtUtc']=now;model.setdefault('displayNameAmendments',{})[code]=proof
 if 'locality-display-names.json'in m.get('currentAssets',{}):m['currentAssets']['locality-display-names.json']=pin(live)
 for ref in model['sourceFiles']:
  if ref.get('kind')=='pointer'and Path(ref['path']).resolve()in [runp.resolve(),hp.resolve(),cp.resolve()]:ref['sha256']=pin(Path(ref['path']))['sha256']
 sys.path.insert(0,str(public));import task_report_app as app
 original=(app.build_task_report,app.build_boundary_map,app.refresh_frina_investigation,sys.argv)
 try:app.build_task_report=lambda:model;app.build_boundary_map=lambda unused:m;app.refresh_frina_investigation=lambda:None;sys.argv=[str(public/'task_report_app.py'),'refresh'];app.main()
 finally:app.build_task_report,app.build_boundary_map,app.refresh_frina_investigation,sys.argv=original
 if model['summary']!=before_counts:raise ValueError('A display override cannot earn geographic or full-body credit')
 put(out/'publication-receipt.json',{'status':'INSTALLED_DISPLAY_OVERRIDE_AND_CURRENT_MAP_LABEL_NO_GEOGRAPHIC_CREDIT','name':name,'code':code,'sourceProof':proof,'practicalReceipt':practicalref,'affectedExistingMapRows':affected,'sourceOfficialNamePreserved':True,'exactSpatialAssetBytesPreserved':True,'additionalGpsChecks':0,'additionalGeographicCredit':0,'additionalCompleteBodyCredit':0,'additionalBoundaryCorrectionCredit':0,'privateGoogleConfigRead':False,'actualBrowserRenderingTested':False,'report':pin(public/'task-report.json'),'map':pin(public/'boundary-map.json'),'html':pin(public/'dashboard.html')});print(json.dumps({'displayName':name,'sourceName':row['name'],'code':code,'affectedExistingMapRows':affected,'geometryChanged':False,'newGeographicCredit':0},ensure_ascii=False))
if __name__=='__main__':main()
