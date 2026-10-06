"""Recover legacy accepted source provenance without revalidating or changing bodies.

Only latest, full, unchanged current family bodies are eligible. Three explicit
historical GPS evidence schemas are supported. Every other schema fails closed.
The output is a provenance-enriched map copy; publication is a separate action.
"""
import argparse,json,sys
from pathlib import Path
from copy import deepcopy
from shapely import from_wkb,to_wkb
from shapely.geometry import shape
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,checked,pin,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.packed_gps_replay import PackedGpsReplay
p=argparse.ArgumentParser(description=__doc__)
for k in ['manifest','baseline','map','report','output']:p.add_argument('--'+k,type=Path,required=True)
a=p.parse_args();s=read(a.manifest);c=active_control(s);b=read(a.baseline);m=read(a.map);t=read(a.report);prior=set(b['alreadyCompleteFamilyCodes'])
assert set(b['allFamilyOfficialCodes'])==set(c['allFamilyOfficialCodes'])
live=PackedGpsReplay(checked(m['currentAssets']['metadata']),checked(m['currentAssets']['binary']))
vs={v['id']:v for v in t['validations']};original=deepcopy(m);proofs={};cache={};w=Path.cwd();out=w/(a.output.stem+'-pins');out.mkdir()
for r in m['locations']:
 if r['code']not in prior or r['sourceGeometryEvidence']:continue
 code=r['code'];ident=r['id'];assert r['latestScope']=='full'and r['currentMatchesAccepted']is True
 v=vs[r['latestBatchId']];assert code in v['fullSourceBoundaryLocalityCodes']and v['qualified']is True and v['kind']=='installed_gps_scoped'
 if v['id']not in cache:
  ref=next(x for x in v['evidenceRefs']if x['kind']=='receipt');ref={'file':ref['path'],'sha256':ref['sha256']};i=read(checked(ref));g=read(checked(i['gpsEvidence']));q=read(checked(g['inputs']['proposal']))
  assert i['status']=='INSTALLED_SCOPED_PRACTICAL_CORRECTIONS' and g['status'].startswith('PASS_OFFLINE_STAGED_')and not g['structuralFailures']and not g['probeFailures']
  after=PackedGpsReplay(checked(g['inputs']['afterJson']),checked(g['inputs']['afterBin']))
  cache[v['id']]=(ref,i,g,{x['officialCode']:x for x in q['patches']},after)
 ref,i,g,patches,after=cache[v['id']];patch=patches[code]
 assert patch['id']==ident and patch['boundaryScope']=='full-source-face' and code in i['fullSourceBoundaryLocalityCodes']
 check=g['decodedPatchChecks'][code];assert check['valid']and check['equalFinalPatchCoordinates']
 adopted=from_wkb(checked(patch['geometry']).read_bytes());assert adopted.equals(after.geometry(ident))and adopted.equals(live.geometry(ident))and adopted.equals(shape(r['geometry']))
 checked(patch['sourcePdf']);inputs=g['inputs'];provenance=[]
 evidence=inputs.get('sourceEvidence',{}).get(ident)
 if evidence:
  assert evidence['id']==ident and evidence['officialCode']==code and evidence['geometryCRS']=='EPSG:4326'and evidence['contextScope']=='complete-own-source-context'
  raw=evidence['sourceGeometry'];checked(raw);provenance=evidence['provenance'];[checked(x)for x in provenance]
 elif inputs.get('independentSourceReplay'):
  replay=read(checked(inputs['independentSourceReplay']))
  assert replay['status'] in ['PASS_SOUTH26_FRESH_ORIGINAL_NATIVE_REPLAY','PASS_INDEPENDENT_TUNIS12_NATIVE_SOURCE_REPLAY']
  found=[x for x in replay['rows']if x['officialCode']==code];assert len(found)==1;z=found[0]
  assert z['id']==ident and z['sourcePdf']['sha256']==patch['sourcePdf']['sha256']and z['valid']
  if replay['status']=='PASS_INDEPENDENT_TUNIS12_NATIVE_SOURCE_REPLAY':
   assert replay['failures']==[] and z['freshNativeCoordinatesMatch'] is True and z['freshRegisteredCoordinatesMatch'] is True
  else:assert z['freshNativePointsMatch']and z['rawSourceCoordinatesEqual']
  checked(z['sourceInventory']);raw=z['rawSourceGeometry'];checked(raw);provenance=[inputs['independentSourceReplay'],z['sourceInventory']]
  if z.get('priorOriginalSourceScopeEvidence'):provenance.append(z['priorOriginalSourceScopeEvidence'])
  [checked(x)for x in provenance]
 elif inputs.get('sourceEvidence',{}).get('source17Manifest'):
  mr=inputs['sourceEvidence']['source17Manifest'];recipes=read(checked(mr));found=[x for x in recipes['features']if x['officialCode']==code];assert len(found)==1;z=found[0]
  assert z['appId']==ident and z['sourcePdf']['sha256']==patch['sourcePdf']['sha256'];checked(z['sourceScopeReview'])
  pre=read(checked(inputs['independentSourcePreflight']));assert not pre['failures'];pf=[x for x in pre['rows']if x['code']==code];assert len(pf)==1;pf=pf[0]
  assert pf['id']==ident and pf['passed']and pf['nativeValid']and pf['identityRosterMatches']and pf['sourcePdfSha256']==patch['sourcePdf']['sha256']and pf['canonicalVsFreshSymDiffDegree2']==0
  if z['nativeInventory']:checked(z['nativeInventory']);assert pf['freshPointsMatchCached']is True
  else:assert isinstance(z['nativeSelection']['rawPdfStrokeOperatorIndex'],int)and pf['layer']=='raw selected PDF stroke'and z['nativeSelection']['valid']and z['nativeSelection']['closed']
  geom=shape(z['canonicalSourceGeometryWgs84']);assert geom.is_valid and not geom.is_empty
  path=out/(code+'-historical-canonical-source.wkb');path.write_bytes(to_wkb(geom));raw=pin(path);provenance=[mr,z['sourceScopeReview'],inputs['independentSourcePreflight'],inputs['independentOriginalsAnchorProof']];[checked(x)for x in provenance]
 else:raise ValueError('Unsupported historical accepted source schema: '+code)
 assert from_wkb(checked(raw).read_bytes()).is_valid
 proof=put(out/(code+'-provenance.json'),{'status':'REUSED_HISTORICAL_ACCEPTED_WHOLE_SOURCE_PROVENANCE','officialCode':code,'id':ident,'acceptedInstallation':ref,'acceptedGps':i['gpsEvidence'],'acceptedProposal':inputs['proposal'],'historicalSourceProvenance':provenance,'sourcePdf':patch['sourcePdf'],'rawSourceGeometry':raw,'expectedAdoptedGeometry':patch['geometry'],'currentGeometryEqualsHistoricalInstalled':True,'newValidationClaim':False,'datedRosterClaimAdded':False,'credit':0})
 e={'displayedGeometryRole':'retained accepted whole source with original legacy qualification','rawSourceGeometry':raw,'rawGeometryCrs':'EPSG:4326','expectedAdoptedGeometry':patch['geometry'],'adoptedGeometryCrs':'EPSG:4326','scopeEvidence':proof,'qualification':patch['qualification'],'acceptanceReceipt':ref,'sourcePdf':patch['sourcePdf'],'historicalProvenanceReused':True};r['sourceGeometryEvidence']=e;proofs[code]=e
for batch in m['batches']:
 for r in batch['locations']:
  if r['code']in proofs and r['geometry']==next(x['geometry']for x in m['locations']if x['code']==r['code']):r['sourceGeometryEvidence']=proofs[r['code']]
for x,y in zip(original['locations'],m['locations']):assert {k:v for k,v in x.items()if k!='sourceGeometryEvidence'}=={k:v for k,v in y.items()if k!='sourceGeometryEvidence'}
assert {k:v for k,v in original.items()if k not in ['locations','batches']}=={k:v for k,v in m.items()if k not in ['locations','batches']}
put(a.output,m);put(out/'receipt.json',{'status':'PASS_REUSED_HISTORICAL_SOURCE_PROVENANCE','inputs':[pin(x.resolve())for x in [a.manifest,a.baseline,a.map,a.report]],'recoveredCodes':sorted(proofs),'enrichedMap':pin(a.output.resolve()),'newValidationClaim':False,'coordinatesTimesCountsHistoryUnchanged':True,'additionalGpsChecks':0,'credit':0})
print(json.dumps({'recoveredLegacySources':len(proofs),'additionalGpsChecks':0,'credit':0}))
