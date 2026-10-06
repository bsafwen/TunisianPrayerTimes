"""Publish only proved legacy source links; preserve geometry, times and counts."""
import argparse,sys,json
from pathlib import Path
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
p=argparse.ArgumentParser(description=__doc__)
for k in ['manifest','receipt','evidence','output']:p.add_argument('--'+k,type=Path,required=True)
a=p.parse_args();active_control(read(a.manifest));r=read(a.receipt);assert r['status']=='PASS_REUSED_HISTORICAL_SOURCE_PROVENANCE'and r['credit']==0 and not r['newValidationClaim']and r['coordinatesTimesCountsHistoryUnchanged']
d=a.evidence/'work/locality-progress-dashboard';m=read(checked(r['enrichedMap']));before=read(checked(r['inputs'][2]));assert pin(d/'boundary-map.json')==r['inputs'][2]and pin(d/'task-report.json')==r['inputs'][3]
assert {k:v for k,v in before.items()if k not in ['locations','batches']}=={k:v for k,v in m.items()if k not in ['locations','batches']}
for x,y in zip(before['locations'],m['locations']):assert {k:v for k,v in x.items()if k!='sourceGeometryEvidence'}=={k:v for k,v in y.items()if k!='sourceGeometryEvidence'}
for x,y in zip(before['batches'],m['batches']):
 assert {k:v for k,v in x.items()if k!='locations'}=={k:v for k,v in y.items()if k!='locations'}
 for u,v in zip(x['locations'],y['locations']):assert {k:z for k,z in u.items()if k!='sourceGeometryEvidence'}=={k:z for k,z in v.items()if k!='sourceGeometryEvidence'}
model=read(d/'task-report.json');sys.path.insert(0,str(d));import task_report_app as app
original=app.build_task_report,app.build_boundary_map,app.refresh_frina_investigation,sys.argv
try:
 app.build_task_report=lambda:model;app.build_boundary_map=lambda unused:m;app.refresh_frina_investigation=lambda:None;sys.argv=[str(d/'task_report_app.py'),'refresh'];app.main()
finally:app.build_task_report,app.build_boundary_map,app.refresh_frina_investigation,sys.argv=original
assert read(d/'boundary-map.json')==m and read(d/'task-report.json')['summary']==model['summary']
put(a.output,{'status':'PASS_PUBLISHED_LEGACY_SOURCE_LINKS_ONLY','recoveryReceipt':pin(a.receipt.resolve()),'codes':r['recoveredCodes'],'map':pin(d/'boundary-map.json'),'coordinatesTimesCountsHistoryUnchanged':True,'additionalGpsChecks':0,'credit':0})
print(json.dumps({'sourceLinksPublished':len(r['recoveredCodes']),'additionalGpsChecks':0,'credit':0}))
