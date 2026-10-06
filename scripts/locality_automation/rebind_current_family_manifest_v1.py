"""Reuse pinned original cases in a current finite family increment, without extraction."""
import argparse,sys,json
from pathlib import Path
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--manifest',type=Path,required=True);p.add_argument('--control',type=Path,required=True)
p.add_argument('--diagnostics',type=Path);p.add_argument('--choices',nargs='*',default=[])
p.add_argument('--output',type=Path,required=True);a=p.parse_args()
old=read(a.manifest);c=read(a.control);pool=c['approvedCycle'+str(c['iteration'])+'AcceptancePool']
s={**old,**{k:c[k]for k in ['iteration','windowStartUtc','deadlineUtc']},'control':str(a.control.resolve()),'owner':'/root','exactTargets':pool}
active_control(s);assert set(pool)<=set(old['exactTargets'])
registry={str(r['sectorCode']):r for r in read(R/'scripts/neighborhoods/touil-reviews/current-official-sector-registry.json')['sectors']}
cases=[r for r in old['cases']if r['officialCode']in pool]
assert len(cases)==len(pool) and len({r['officialCode']for r in cases})==len(pool)
for r in cases:
 ins=registry[r['officialCode']]
 assert ins['governorateCode']==c['governorateCode'] and r['officialName']==ins['sectorAr'] and r['officialParent']==ins['delegationAr']
 checked(r['sourcePdf']);checked(r['sourceInventory'])
for value in a.choices:
 code,layer,index=value.split('=');assert code in pool and layer=='red' and a.diagnostics
 candidate=[r for r in read(a.diagnostics)['candidates']if r['code']==code and r['layer']==layer and r['index']==int(index)]
 assert len(candidate)==1 and candidate[0]['wholeInsidePage'];d=candidate[0]
 checked(d['pageGeometry']);checked(d['render']);q=next(r for r in cases if r['officialCode']==code)
 q.update(reviewedNativePageFace=d['pageGeometry'],rootDiagnosticRender=d['render'])
s['cases']=cases;put(a.output,s)
print(json.dumps({'reusedOriginals':len(cases),'nativeExtractions':0,'sourceOnlyCredit':0}))
