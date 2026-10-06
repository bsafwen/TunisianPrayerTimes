"""Install a proved missing entry after a filtered-catalogue deployment epoch.

Original immutable acceptance receipts retain their hashes. The explicit
rebase binds their former live pair to frozen before-assets, then to the
byte-preserving filtered deployment. Uses the stock CAS/backup/rollback installer.
"""
import argparse,json,os,sys
from pathlib import Path
from datetime import datetime,timezone
from shapely import from_wkb
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree
from scripts.locality_automation.installer import install
from scripts.locality_automation.stage_boundary_patch import decode
from scripts.locality_automation.registration_policy_v2 import require_registration
from scripts.locality_automation.native_administrative_policy_v2 import require_native_administration
def put(p,v):
    with p.open('x',encoding='utf-8')as f:json.dump(v,f,ensure_ascii=False,indent=2);f.write('\n')
    return pin(p)
def replace(p,v):
    tmp=p.with_name(p.name+'.new-entry-tmp')
    with tmp.open('x',encoding='utf-8')as f:json.dump(v,f,ensure_ascii=False,indent=2);f.write('\n');f.flush();os.fsync(f.fileno())
    os.replace(tmp,p)
def main():
    p=argparse.ArgumentParser(description=__doc__)
    for k in ['work','stage','gps','run','handoff','output','transaction']:p.add_argument('--'+k,type=Path,required=True)
    a=p.parse_args();w=a.work.resolve();assert Path.cwd().resolve()==w
    c=read(w.parent/'control.json');s={k:c[k]for k in ['iteration','windowStartUtc','deadlineUtc']};s.update(control=str(w.parent/'control.json'),owner='/root');active_control(s)
    stage=read(a.stage);gps=read(a.gps);check_tree(stage);check_tree(gps);proposal=read(checked(stage['proposal']));assert stage['status']=='STAGED_NEW_PROVED_OFFICIAL_ENTRY_REQUIRES_TARGET_GPS'
    codes=[p['officialCode']for p in proposal['patches']];assert codes==c['approvedCycle'+str(c['iteration'])+'AcceptancePool'] and len(codes)==1
    assert gps['status']=='PASS_OFFLINE_STAGED_TARGET_ONLY_ISIE_SOURCE_GPS' and not gps['structuralFailures'] and not gps['probeFailures'] and gps['probeCount']==1 and gps['lookupComparisons']==4
    assert gps['inputs']['proposal']==stage['proposal'] and gps['inputs']['afterJson']==stage['stagedMetadata'] and gps['inputs']['afterBin']==stage['stagedBinary']
    assert gps['inputs']['beforeJson']==stage['beforeMetadata'] and gps['inputs']['beforeBin']==stage['beforeBinary']
    patch=proposal['patches'][0];ident=patch['id'];scope=read(checked(patch['sourceScopeReview']));check_tree(scope)
    require_registration(codes[0],scope['sourcePdf'],scope['registration'],c);require_native_administration(codes[0],scope,c)
    assert scope['sourceScopeAccepted'] and scope['datedRosterMember'] and scope['boundaryScope']=='full-source-face' and scope['expectedAdoptedGeometry']==patch['geometry']
    assert scope['identityEvidence']['currentInsCode'] is None and scope['reviewedMinistryAndDecree']
    assert all(r['overlapFractionSmaller']<=.01 for r in stage['neighborPairs'])
    assert all(stage[k]for k in ['retainedRecordAndPackedBytesIdentical','retainedCellMembershipOrderIdentical','countryGeometryByteIdentical'])
    binding=read(checked(gps['inputs']['actualJvmBinding']))
    for b in binding['currentCoreBindings']:assert checked(b['snapshot']).read_bytes()==checked(b['current']).read_bytes()
    actual=read(checked(gps['inputs']['actualJvm']));assert actual['cases']==4 and not actual['failures'] and all(r['label']['id']==ident for r in actual['results'])
    before=read(checked(stage['beforeMetadata']));after=read(checked(stage['stagedMetadata']));bb=checked(stage['beforeBinary']).read_bytes();ab=checked(stage['stagedBinary']).read_bytes()
    assert len(after['features'])==len(before['features'])+1 and before['features']==after['features'][:-1] and after['features'][-1]['id']==ident
    assert bb[:before['country']['offset']]==ab[:before['country']['offset']] and bb[before['country']['offset']:]==ab[after['country']['offset']:]
    assert decode(ab,after['features'][-1],after['coordinateScale']).equals(from_wkb(checked(patch['geometry']).read_bytes()))
    before_run=a.run.read_bytes();before_handoff=a.handoff.read_bytes();run=read(a.run);handoff=read(a.handoff);previous_ref=run['practicalProgress'];previous=read(checked(previous_ref));previous_gps=read(checked(previous['gpsEvidence']))
    lineage=read(checked(stage['frozenDeploymentLineage']));deployment=read(checked(stage['currentDeployment']));deployed_stage=read(checked(deployment['stage']))
    assert lineage['status']=='FROZEN_HISTORICAL_INPUTS_MATCH_ORIGINAL_PINNED_HASHES' and lineage['acceptedIdsNamesCoordinatesTimesAndBoundaryBytesUnchanged']
    for key,field in [('neighborhoods.json','afterJson'),('neighborhoods.bin','afterBin')]:
        frozen=lineage['historicalInputs']['before-'+key];assert frozen['originalReference']['sha256']==frozen['immutableReference']['sha256']==previous_gps['inputs'][field]['sha256'];checked(frozen['immutableReference'])
        assert deployed_stage['stagedAssets'][key]['sha256']==stage['beforeAssets'][key]['sha256']
    for n,ref in stage['liveBeforeAssets'].items():checked(ref)
    a.output.mkdir();(a.output/'before-run.json').write_bytes(before_run);(a.output/'before-handoff.json').write_bytes(before_handoff)
    rebase=put(a.output/'deployment-rebase.json',{'status':'PROVED_FILTERED_CATALOGUE_EPOCH_REBASE','previousPracticalProgress':previous_ref,'previousGpsEvidence':previous['gpsEvidence'],'frozenDeploymentLineage':stage['frozenDeploymentLineage'],'filteredDeployment':stage['currentDeployment'],'currentBeforeAssets':stage['beforeAssets'],'oldReceiptsUnchanged':True,'legacyLocationsRestored':False,'credit':0})
    specs=[(ref,'android-app/app/src/main/assets/'+n,stage['liveBeforeAssets'][n]['sha256'])for n,ref in stage['stagedAssets'].items()if ref['sha256']!=stage['liveBeforeAssets'][n]['sha256']]
    validation=put(a.output/'validation.json',{'status':'READY_TO_INSTALL','qualification':proposal['qualification'],'files':[{'sourceSha256':r['sha256'],'destination':d,'beforeSha256':h}for r,d,h in specs],'issues':[],'unresolvedCaseIds':[],'gpsEvidence':pin(a.gps),'sourceScope':patch['sourceScopeReview'],'deploymentRebase':rebase})
    manifest={'schemaVersion':1,'validation':validation,'files':[{'source':r,'destination':d,'beforeSha256':h}for r,d,h in specs]};put(a.output/'manifest.json',manifest)
    result=install(manifest,str(R),str(a.transaction),[],None);result_ref=put(a.output/'installer-receipt.json',result);assert result['status']=='INSTALLED'
    assets={n:pin(Path(ref['file']))for n,ref in stage['liveBeforeAssets'].items()};assert all(assets[n]['sha256']==stage['stagedAssets'][n]['sha256']for n in assets)
    name_ref=put(a.output/'name-evidence.json',{'status':'RETAINED_NAMES_IDENTICAL_NEW_PROVED_ARABIC_METADATA_NAME','before':stage['beforeAssets']['locality-display-names.json'],'after':stage['stagedAssets']['locality-display-names.json'],'displayRenames':0,'aliasOnlyUpdates':0,'newEntryName':scope['officialName'],'newEntryIdentity':scope['identityEvidence']})
    prior_codes=previous.get('cumulativeBoundaryLocalityCodes',previous['boundaryLocalityCodes'])
    receipt={'schemaVersion':2,'status':'INSTALLED_SCOPED_PRACTICAL_CORRECTIONS','appliedAtUtc':datetime.now(timezone.utc).isoformat(),'qualification':proposal['qualification'],'boundaryLocalityCodes':codes,'boundaryLocalitiesCorrected':1,'fullSourceBoundaryLocalityCodes':codes,'scopedBoundaryLocalityCodes':[],'boundaryScopeRecorded':True,'excludedFromDatedRosterCodes':[],'datedRosterMembershipRecorded':True,'newEntryIds':[ident],'boundaryMetadataChanges':[],'cumulativeBoundaryLocalityCodes':sorted(set(prior_codes)|set(codes)),'previousPracticalProgress':previous_ref,'deploymentRebase':rebase,'displayNamesImproved':0,'officialSearchAliasesAdded':0,'nameEvidence':name_ref,'gpsEvidence':pin(a.gps),'installerReceipt':result_ref,'manifest':pin(a.output/'manifest.json'),'installedAssets':assets,'gpsProbeCount':1,'gpsLookupComparisons':4,'sourceSupportedImprovedComparisons':gps['totals']['source_supported_wrong_to_correct'],'wrongOwnerProbeFailures':0,'preservedHistoricalInputs':{'metadata':stage['beforeMetadata'],'binary':stage['beforeBinary']},'androidCompiled':False,'apkGenerated':False,'deviceVerification':False,'proxyChanged':False}
    receipt_ref=put(a.output/'practical-receipt.json',receipt)
    assert a.run.read_bytes()==before_run and a.handoff.read_bytes()==before_handoff,'Assets installed; concurrent ledger write requires reconciliation'
    run['installedCatalog']=assets['neighborhoods.json'];run['practicalProgress']=handoff['practicalProgress']=receipt_ref
    replace(a.run,run);replace(a.handoff,handoff)
    print(json.dumps({'installedNewEntries':1,'catalogueLocations':len(after['features']),'retainedEntriesByteIdentical':True,'currentInsCode':None,'receipt':receipt_ref}))
if __name__=='__main__':main()
