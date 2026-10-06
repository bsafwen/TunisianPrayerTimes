"""Restore exact accepted native source links in one family's existing map rows."""
import argparse,json,sys
from copy import deepcopy
from pathlib import Path
from shapely import from_wkb
from shapely.geometry import shape
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.packed_gps_replay import PackedGpsReplay

def main():
 p=argparse.ArgumentParser(description=__doc__)
 for key in ('work','evidence','family','output'):p.add_argument('--'+key,required=True)
 p.add_argument('--completed-work',type=Path,action='append',required=True);a=p.parse_args()
 w=Path(a.work).resolve();e=Path(a.evidence).resolve();c=read(w.parent/'control.json')
 s={k:c[k]for k in ('iteration','windowStartUtc','deadlineUtc')};s.update(control=str(w.parent/'control.json'),owner='/root');active_control(s);assert c['familySlug']==a.family
 public=e/'work/locality-progress-dashboard';sys.path.insert(0,str(public))
 import boundary_map_data as maps
 import task_report_app as app
 model=read(public/'task-report.json');m=read(public/'boundary-map.json');before=deepcopy(m)
 live=PackedGpsReplay(checked(m['currentAssets']['metadata']),checked(m['currentAssets']['binary']))
 rows={r['code']:r for r in m['locations']};proofs={};receipts=[]
 for old in a.completed_work:
  ref=pin(old/(a.family+'-live-reviewed-install-v1/practical-receipt.json'));installed=read(checked(ref));gps=read(checked(installed['gpsEvidence']))
  assert installed['status']=='INSTALLED_SCOPED_PRACTICAL_CORRECTIONS'
  assert gps['status'].startswith('PASS_OFFLINE_STAGED_') and not gps['structuralFailures']and not gps['probeFailures']
  proposal=read(checked(gps['inputs']['proposal']));accepted=PackedGpsReplay(checked(gps['inputs']['afterJson']),checked(gps['inputs']['afterBin']))
  codes=set(installed['boundaryLocalityCodes'])
  assert codes==set(installed['fullSourceBoundaryLocalityCodes'])==set(gps['decodedPatchChecks'])
  assert codes<=set(c['allFamilyOfficialCodes'])
  for name,key in [('neighborhoods.json','afterJson'),('neighborhoods.bin','afterBin')]:assert installed['installedAssets'][name]['sha256']==gps['inputs'][key]['sha256']
  for patch in proposal['patches']:
   code=patch['officialCode'];row=rows[code];check=gps['decodedPatchChecks'][code];batch='practical-'+ref['sha256'][:16]
   assert code in codes and code not in proofs and check['valid']and check['equalFinalPatchCoordinates']
   assert row['latestScope']=='full'and row['currentMatchesAccepted']and row['id']==patch['id']and row['history'][-1]['batchId']==batch
   grid=from_wkb(checked(patch['geometry']).read_bytes())
   assert grid.equals(accepted.geometry(patch['id']))and grid.equals(live.geometry(patch['id']))and grid.equals(shape(row['geometry']))
   proofs[code]=maps.installed_native_geometry_evidence(patch,code,patch['id'],grid,ref,e)
  receipts.append(ref)
 for row in m['locations']:
  if row['code']in proofs:row['sourceGeometryEvidence']=proofs[row['code']]
 for batch in m['batches']:
  for row in batch['locations']:
   if row['code']in proofs and row['geometry']==rows[row['code']]['geometry']:row['sourceGeometryEvidence']=proofs[row['code']]
 # All changes must be source-link metadata, never coordinates, history, times or counts.
 for old,new in zip(before['locations'],m['locations']):
  assert {k:v for k,v in old.items()if k!='sourceGeometryEvidence'}=={k:v for k,v in new.items()if k!='sourceGeometryEvidence'}
 assert {k:v for k,v in before.items()if k not in ('locations','batches')}=={k:v for k,v in m.items()if k not in ('locations','batches')}
 for old,new in zip(before['batches'],m['batches']):
  assert {k:v for k,v in old.items()if k!='locations'}=={k:v for k,v in new.items()if k!='locations'}
  for x,y in zip(old['locations'],new['locations']):assert {k:v for k,v in x.items()if k!='sourceGeometryEvidence'}=={k:v for k,v in y.items()if k!='sourceGeometryEvidence'}
 put(w/'before-family-native-map-evidence-v1.json',before)
 original=app.build_task_report,app.build_boundary_map,app.refresh_frina_investigation,sys.argv
 try:
  app.build_task_report=lambda:model;app.build_boundary_map=lambda unused:m;app.refresh_frina_investigation=lambda:None;sys.argv=[str(public/'task_report_app.py'),'refresh'];app.main()
 finally:app.build_task_report,app.build_boundary_map,app.refresh_frina_investigation,sys.argv=original
 assert read(public/'boundary-map.json')==m and read(public/'task-report.json')['summary']==model['summary']
 put(Path(a.output),{'status':'PASS_FINITE_ACCEPTED_NATIVE_MAP_EVIDENCE_REUSED','codes':sorted(proofs),'acceptedInstallations':receipts,'map':pin(public/'boundary-map.json'),'nativeMapBuilder':pin(public/'boundary_map_data.py'),'coordinatesTimesCountsAndHistoryUnchanged':True,'newSourceExtractions':0,'additionalGpsChecks':0,'credit':0})
 print(json.dumps({'acceptedNativeSourceLinksRestored':len(proofs),'additionalGpsChecks':0,'credit':0}))

if __name__=='__main__':main()
