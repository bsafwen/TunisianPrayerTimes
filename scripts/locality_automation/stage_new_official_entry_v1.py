"""Admit a missing Ministry/ISIE entry without assuming a census-code match.

Copies every retained record/slice verbatim, adds only the proved native face,
preserves the national mask and foreign conflict policies, and records a new
deployment epoch rather than altering historical receipts.
"""
import argparse,copy,csv,importlib.util,json,math,sys
from pathlib import Path
from datetime import datetime,timezone
from shapely import from_wkb,set_precision
from shapely.geometry import Point
from shapely.ops import transform
from pyproj import Transformer
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree
from scripts.locality_automation.registration_policy_v2 import require_registration
from scripts.locality_automation.native_administrative_policy_v2 import require_native_administration
from scripts.locality_automation.stage_boundary_patch import decode
def put(p,v):
    with p.open('x',encoding='utf-8',newline='\n')as f:json.dump(v,f,ensure_ascii=False,indent=2);f.write('\n')
    return pin(p)
def stage(plan_path,out):
    plan=read(plan_path);check_tree(plan);c=active_control(plan)
    facts=read(checked(plan['nativeFacts']));code=facts['officialCode'];ident=facts['id']
    assert c['approvedCycle'+str(c['iteration'])+'AcceptancePool']==[code]
    identity=plan['identity'];assert identity['catalogId']==ident and identity['sourceKey']==code
    assert identity['currentInsCode'] is None and identity['status']=='PROVED_MINISTRY_ISIE_IDENTITY_WITHOUT_CURRENT_CENSUS_CODE'
    csvrows=list(csv.DictReader(checked(identity['ministryCsv']).open(encoding='utf-8-sig',newline='')))
    matches=[r for r in csvrows if r['governorate']==identity['governorateAr'] and r['delegation']==facts['officialParent'] and r['name']==facts['officialName']]
    assert len(matches)==1 and plan['decreeCircleCount']==1 and plan['datedRosterMember'] is True
    require_registration(code,facts['sourcePdf'],facts['registration'],c)
    require_native_administration(code,facts,c)
    assert facts['originalNativePointsMatchSavedInventory'] and facts['wholeNativeFaceInsidePage']
    assert len(facts['insideLabels'])==1 and facts['insideLabels'][0]['own']
    raw=from_wkb(checked(facts['rawSourceGeometry']).read_bytes());grid=from_wkb(checked(facts['geometry']).read_bytes())
    assert raw.is_valid and grid.is_valid and grid.equals(set_precision(raw,1e-6))
    to_m=Transformer.from_crs(4326,32632,always_xy=True).transform
    assert transform(to_m,raw).hausdorff_distance(transform(to_m,grid))<.1
    deploy=read(checked(plan['currentDeployment']));lineage=read(checked(plan['frozenDeploymentLineage']))
    assert deploy['status']=='PUBLISHED_VALIDATED_ONLY_APP_CATALOGUE' and deploy['newBoundaryValidationCredit']==0
    assets=R/'android-app/app/src/main/assets';names=['neighborhoods.json','neighborhoods.bin','retired-localities.json','locality-display-names.json']
    for n in names:assert pin(assets/n)==deploy['installedAssets'][n]
    before=read(assets/names[0]);blob=(assets/names[1]).read_bytes();assert blob[:8]==b'NPOL\0\0\0\1'
    assert before['catalogScope']=='validated-official-sectors' and before['validatedLocationCount']==len(before['features'])
    assert ident not in {r['id']for r in before['features']}
    old=read(checked(plan['historicalIdentityCatalog']));row=copy.deepcopy(next(r for r in old['features']if r['id']==ident))
    assert row['name']==facts['officialName'] and row['governorateId']==identity['appGovernorateId'] and row['delegationId']==identity['appDelegationId']
    pairs=[];neighbors=[]
    for ref in plan['acceptedNeighbors']:
        s=read(checked(ref));g=from_wkb(checked(s['geometry']).read_bytes());r=next(r for r in before['features']if r['id']==s['id'])
        assert decode(blob,r,before['coordinateScale']).equals(g) and s['sourceScopeAccepted']
        h=transform(to_m,from_wkb(checked(s['rawSourceGeometry']).read_bytes()));m=transform(to_m,raw);area=m.intersection(h).area
        pairs.append({'target':code,'retained':s['officialCode'],'overlapM2':area,'overlapFractionSmaller':area/min(m.area,h.area),'gapM':m.distance(h)})
        assert pairs[-1]['overlapFractionSmaller']<=.01
        neighbors.append((r,g))
    bounds=grid.bounds
    incidental={r['id']for r in before['features']if r['bbox'][0]<=bounds[2]and r['bbox'][2]>=bounds[0]and r['bbox'][1]<=bounds[3]and r['bbox'][3]>=bounds[1]}
    assert incidental=={r['id']for r,g in neighbors},'Complete adjoining scope required'
    spec=importlib.util.spec_from_file_location('packer',R/'scripts/generate_neighborhoods.py');packer=importlib.util.module_from_spec(spec);spec.loader.exec_module(packer)
    payload=packer.packed_geometry_bytes(grid);decoded=decode(blob[:8]+payload,{'id':ident,'offset':8,'length':len(payload)},before['coordinateScale']);assert decoded.equals(grid)
    country=before['country'];assert country['offset']+country['length']==len(blob)
    out.mkdir();before_refs={}
    for n in names:(out/('before-'+n)).write_bytes((assets/n).read_bytes());before_refs[n]=pin(out/('before-'+n))
    scope={**facts,'sourceScopeAccepted':True,'boundaryScope':'full-source-face','datedRosterMember':True,'expectedAdoptedGeometry':facts['geometry'],'sourceAuthor':'/root','actualReviewer':'/root','independentAgentReviewClaimed':False,'identityEvidence':identity,'decisionExplanation':plan['decisionExplanation'],'qualification':plan['qualification'],'sourceOnlyGeographicCredit':0,'reviewedMinistryAndDecree':True}
    scope_ref=put(out/'source-scope.json',scope)
    patch={'id':ident,'officialCode':code,'officialName':facts['officialName'],'officialParent':facts['officialParent'],'identityEvidence':put(out/'identity-proof.json',{'bindings':[{'officialCodeUsedBySource':code,'catalogId':ident,'qualification':identity['qualification']}],'identity':identity}),'geometry':facts['geometry'],'rawSourceGeometry':facts['rawSourceGeometry'],'sourcePdf':facts['sourcePdf'],'sourceUrl':facts['sourceUrl'],'sourceScopeReview':scope_ref,'boundaryScope':'full-source-face','datedRosterMember':True,'sourceProvider':'ISIE 2023 native map; Ministry identity; decree 2023-590 single circle','qualification':plan['qualification'],'uncertainty':'Exact native outline rounded once to 1e-6 grid. Registered neighbor seams and the existing country mask retain conservative GPS fallback; no surveyed border accuracy claimed.'}
    w=out.parent;proposal_ref=put(w/(c['familySlug']+'-proposal.json'),{'status':'SOURCE_SCOPE_REVIEWED_REQUIRES_TARGET_GPS','patches':[patch],'qualification':plan['qualification'],'rootReview':pin(plan_path),'baseCatalogMetadata':before_refs[names[0]],'baseCatalogBinary':before_refs[names[1]],'newEntryAdmission':True})
    after=copy.deepcopy(before);source_id='isie-complete-'+c['familySlug']+'-'+code+'-20261006'
    label=facts['insideLabels'][0];row.update(sourceId=source_id,bbox=list(grid.bounds),areaKm2=transform(to_m,grid).area/1e6,lat=label['lat'],lng=label['lng'],offset=country['offset'],length=len(payload),pickerGroupId=ident)
    assert grid.contains(Point(row['lng'],row['lat']))
    row['officialIdentity']={'ministryRowId':identity['ministryRowId'],'currentInsCode':None,'sourceKey':code}
    after['features'].append(row);after['validatedLocationCount']=len(after['features'])
    after['country']['offset']+=len(payload);packed=blob[:country['offset']]+payload+blob[country['offset']:]
    after['sources'][source_id]={'id':source_id,'provider':patch['sourceProvider'],'url':patch['sourceUrl'],'sha256':patch['geometry']['sha256'],'review':{'reviewedDate':'2026-10-06','scope':plan['qualification'],'proposalSha256':proposal_ref['sha256'],'sourcePdfSha256':patch['sourcePdf']['sha256'],'uncertainty':patch['uncertainty']}}
    idx=len(before['features']);size=before['gridSize'];x1,y1,x2,y2=grid.bounds
    for y in range(math.floor(y1/size),math.floor(y2/size)+1):
        for x in range(math.floor(x1/size),math.floor(x2/size)+1):after['cells'].setdefault(f'{y}:{x}',[]).append(idx)
    incident=packer.detect_conflicts([row]+[r for r,g in neighbors],[grid]+[g for r,g in neighbors])
    incident=[r for r in incident if ident in r['ids']];after['conflicts']=sorted(before['conflicts']+incident,key=lambda r:r['ids'])
    retired=read(assets/names[2]);retired['retiredLocalityIds'].remove(ident);after['retiredLocalityIds'].remove(ident)
    retired['reviewedNames'].append({'id':ident,'name':row['name'],'kind':'sector'})
    display=read(assets/names[3]);assert ident not in {r['id']for r in display['names']}
    # No name override is needed: the authoritative Arabic name is native metadata.
    put(out/names[0],after);(out/names[1]).write_bytes(packed);put(out/names[2],retired);(out/names[3]).write_bytes((assets/names[3]).read_bytes())
    for a,b in zip(before['features'],after['features']):assert a==b and blob[a['offset']:a['offset']+a['length']]==packed[b['offset']:b['offset']+b['length']]
    assert packed[after['country']['offset']:]==blob[country['offset']:]
    assert {k:[i for i in v if i!=idx]for k,v in after['cells'].items()if any(i!=idx for i in v)}==before['cells']
    country_m=transform(to_m,decode(blob,country,before['coordinateScale']));metric=transform(to_m,raw)
    receipt={'status':'STAGED_NEW_PROVED_OFFICIAL_ENTRY_REQUIRES_TARGET_GPS','proposal':proposal_ref,'plan':pin(plan_path),'currentDeployment':plan['currentDeployment'],'frozenDeploymentLineage':plan['frozenDeploymentLineage'],'beforeMetadata':before_refs[names[0]],'beforeBinary':before_refs[names[1]],'stagedMetadata':pin(out/names[0]),'stagedBinary':pin(out/names[1]),'beforeAssets':before_refs,'liveBeforeAssets':{n:pin(assets/n)for n in names},'stagedAssets':{n:pin(out/n)for n in names},'changedIds':[ident],'retainedRecordAndPackedBytesIdentical':True,'retainedCellMembershipOrderIdentical':True,'countryGeometryByteIdentical':True,'newIncidentConflicts':incident,'neighborPairs':pairs,'retainedCount':len(before['features']),'addedCount':1,'afterCount':len(after['features']),'nationalMaskDifference':{'nativeOutsideExistingCountryM2':metric.difference(country_m).area,'fractionOfSource':metric.difference(country_m).area/metric.area,'countryMaskChanged':False,'qualification':'Keep the existing independent country exclusion mask; do not enlarge Tunisia or clip/alter the official native source coordinates.'},'credit':0}
    put(out/'stage-report.json',receipt)
    for name in ['task-report.json','boundary-map.json']:(w/('before-'+c['familySlug']+'-publication-'+name)).write_bytes((Path(plan['evidence'])/'work/locality-progress-dashboard'/name).read_bytes())
    put(w/(c['familySlug']+'-retained-incidents-v1.json'),{'status':'PASS_PINNED_RETAINED_FAMILY_INCIDENTS','pairs':pairs,'materialPairs':[],'provedPriorBodyCorrections':[],'historicalSourceAndGpsGatesReused':True,'acceptedNeighborEvidence':plan['acceptedNeighbors'],'credit':0})
    check_tree(receipt)
    print(json.dumps({'stagedAdded':1,'afterCount':len(after['features']),'neighborPairs':pairs,'countryMaskUnchanged':True,'credit':0}))
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--plan',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();stage(a.plan.resolve(),a.output.resolve())
