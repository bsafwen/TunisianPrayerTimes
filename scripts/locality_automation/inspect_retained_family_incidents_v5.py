"""Check retained incidents while proving every previously accepted changed body is corrected."""
import argparse,json,sys
from pathlib import Path
from shapely import from_wkb
from shapely.ops import transform
from pyproj import Transformer
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,checked,pin,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.packed_gps_replay import PackedGpsReplay

def main():
 p=argparse.ArgumentParser(description=__doc__)
 for key in ('manifest','baseline','before-map','facts','stage','early-check','output'):p.add_argument('--'+key,type=Path,required=True)
 p.add_argument('--accepted-installation',type=Path)
 p.add_argument('--legacy-provenance',type=Path)
 a=p.parse_args();s=read(a.manifest);c=active_control(s);b=read(a.baseline);codes=s['exactTargets']
 assert codes==c['approvedCycle'+str(c['iteration'])+'AcceptancePool']
 m=read(a.before_map);stage=read(a.stage);early=read(a.early_check)
 if a.legacy_provenance:
  recovery=read(a.legacy_provenance)
  assert recovery['status']=='PASS_REUSED_HISTORICAL_SOURCE_PROVENANCE' and recovery['credit']==0 and recovery['newValidationClaim'] is False and recovery['coordinatesTimesCountsHistoryUnchanged'] is True
  assert recovery['inputs'][2]==pin(a.before_map.resolve()) and recovery['inputs'][1]==pin(a.baseline.resolve())
  for ref in recovery['inputs']:checked(ref)
  enriched=read(checked(recovery['enrichedMap']))
  assert {k:v for k,v in m.items()if k not in ['locations','batches']}=={k:v for k,v in enriched.items()if k not in ['locations','batches']}
  assert len(m['locations'])==len(enriched['locations']) and len(m['batches'])==len(enriched['batches'])
  for x,y in zip(m['locations'],enriched['locations']):assert {k:v for k,v in x.items()if k!='sourceGeometryEvidence'}=={k:v for k,v in y.items()if k!='sourceGeometryEvidence'}
  for x,y in zip(m['batches'],enriched['batches']):
   assert {k:v for k,v in x.items()if k!='locations'}=={k:v for k,v in y.items()if k!='locations'} and len(x['locations'])==len(y['locations'])
   for u,v in zip(x['locations'],y['locations']):assert {k:z for k,z in u.items()if k!='sourceGeometryEvidence'}=={k:z for k,z in v.items()if k!='sourceGeometryEvidence'}
  m=enriched
 assert not early['holds']and not early['materialPairs']and early['preparedBodies']==len(codes)
 assert m['currentAssets']['metadata']['sha256']==stage['beforeMetadata']['sha256']
 assert m['currentAssets']['binary']['sha256']==stage['beforeBinary']['sha256']
 old=PackedGpsReplay(checked(stage['beforeMetadata']),checked(stage['beforeBinary']))
 after=PackedGpsReplay(checked(stage['stagedMetadata']),checked(stage['stagedBinary']))
 prior=set(b['alreadyCompleteFamilyCodes']);changed_prior=prior&set(codes)
 assert changed_prior==set(b['correctionExistingCompleteCodes'])==set(c['currentCorrectionExistingCompleteCodes'])
 assert prior-changed_prior==set(b['retainedCompletedCodes'])
 rows=[r for r in m['locations']if r['code']in prior]
 assert set(r['code']for r in rows)==prior and len(rows)==len(prior)
 facts={r['officialCode']:r for r in read(a.facts)['rows']};assert set(facts)==set(codes)
 to_m=Transformer.from_crs(4326,32632,always_xy=True).transform
 targets={code:from_wkb(checked(r['sourceMetricGeometry']).read_bytes())for code,r in facts.items()}
 installed_evidence={}
 if a.accepted_installation:
  installed=read(a.accepted_installation);assert installed['status']=='INSTALLED_SCOPED_PRACTICAL_CORRECTIONS'
  for key,stagekey in [('neighborhoods.json','beforeMetadata'),('neighborhoods.bin','beforeBinary')]:
   checked(installed['installedAssets'][key]);assert installed['installedAssets'][key]['sha256']==stage[stagekey]['sha256']
  gps=read(checked(installed['gpsEvidence']));proposal=read(checked(gps['inputs']['proposal']))
  assert str(gps['status']).startswith('PASS_OFFLINE_STAGED_')and gps['structuralFailures']==[]and gps['probeFailures']==[]
  assert set(gps['decodedPatchChecks'])==set(installed['boundaryLocalityCodes'])and all(x['valid']and x['equalFinalPatchCoordinates']for x in gps['decodedPatchChecks'].values())
  assert gps['inputs']['afterJson']['sha256']==stage['beforeMetadata']['sha256']and gps['inputs']['afterBin']['sha256']==stage['beforeBinary']['sha256']
  for patch in proposal['patches']:
   code=patch['officialCode'];assert code in prior and code in installed['fullSourceBoundaryLocalityCodes']and code not in installed_evidence
   scope=read(checked(patch['sourceScopeReview']))
   assert scope['officialCode']==code and scope['id']==patch['id']and scope['sourceScopeAccepted']is True and scope['datedRosterMember']is True and scope['boundaryScope']=='full-source-face'
   assert scope['rawSourceGeometry']==patch['rawSourceGeometry']and scope['expectedAdoptedGeometry']==patch['geometry']
   installed_evidence[code]={'scopeEvidence':patch['sourceScopeReview'],'rawSourceGeometry':patch['rawSourceGeometry'],'expectedAdoptedGeometry':patch['geometry'],'rawGeometryCrs':'EPSG:4326','adoptedGeometryCrs':'EPSG:4326','acceptedInstallation':pin(a.accepted_installation.resolve())}
  assert set(installed_evidence)==set(installed['boundaryLocalityCodes'])==set(installed['fullSourceBoundaryLocalityCodes'])
 pairs=[];refs=[];corrections=[]
 for r in rows:
  assert r['latestScope']=='full'and r['currentMatchesAccepted']is True
  e=r['sourceGeometryEvidence'] or installed_evidence.get(r['code']);assert e is not None,'Exact accepted source evidence required: '+r['code']
  checked(e['scopeEvidence'])
  raw=from_wkb(checked(e['rawSourceGeometry']).read_bytes());grid=from_wkb(checked(e['expectedAdoptedGeometry']).read_bytes())
  assert e['rawGeometryCrs']=='EPSG:4326'and e['adoptedGeometryCrs']=='EPSG:4326'
  assert grid.equals(old.geometry(r['id']))
  if r['code']in changed_prior:
   f=facts[r['code']];assert f['id']==r['id']and 'error'not in f
   g=from_wkb(checked(f['geometry']).read_bytes());assert g.equals(after.geometry(r['id']))
   assert f['wholeNativeFaceInsidePage']and f['originalNativePointsMatchSavedInventory']
   corrections.append({'code':r['code'],'id':r['id'],'priorAcceptedScope':e['scopeEvidence'],'freshOriginal':f['sourcePdf'],'freshNative':f['nativePageGeometry'],'freshAdopted':f['geometry'],'sourceReview':pin(a.facts.resolve()),'newGeographicCredit':0,'newFullBodyCredit':0})
   continue
  assert grid.equals(after.geometry(r['id']))
  h=transform(to_m,raw)
  for code,g in targets.items():
   if not g.envelope.buffer(10).intersects(h.envelope)or g.distance(h)>10:continue
   area=g.intersection(h).area
   pairs.append({'target':code,'retained':r['code'],'overlapM2':area,'overlapFractionSmaller':area/min(g.area,h.area),'gapM':g.distance(h)})
   refs.append(e['scopeEvidence'])
 material=[r for r in pairs if r['overlapFractionSmaller']>.01]
 put(a.output,{'status':'PASS_PINNED_RETAINED_FAMILY_INCIDENTS'if not material else'HOLD_MATERIAL_RETAINED_SOURCE_OVERLAP','inputs':[pin(a.before_map.resolve()),pin(a.baseline.resolve()),pin(a.facts.resolve()),pin(a.stage.resolve())],'completeChangedScopeEarlyCheck':pin(a.early_check.resolve()),'acceptedNeighborEvidence':refs,'acceptedInstallationEvidence':pin(a.accepted_installation.resolve())if a.accepted_installation else None,'pairs':pairs,'materialPairs':material,'provedPriorBodyCorrections':corrections,'retainedCodes':sorted(prior-changed_prior),'historicalSourceAndGpsGatesReused':True,'unrelatedLocationTests':0,'credit':0})
 print(json.dumps({'changedBodies':len(codes),'provedCorrections':len(corrections),'retainedBodies':len(prior-changed_prior),'incidentComparisons':len(pairs),'materialPairs':material,'credit':0}))
 return bool(material)
if __name__=='__main__':raise SystemExit(main())

