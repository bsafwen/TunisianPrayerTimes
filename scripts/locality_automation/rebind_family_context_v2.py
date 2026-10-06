"""Rebind complete original context and prior captions without repeating extraction."""
import argparse,sys,json
from pathlib import Path
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--control',type=Path,required=True);p.add_argument('--overlay',type=Path,action='append',default=[]);p.add_argument('--diagnostics',type=Path);p.add_argument('--choices',nargs='*',default=[]);p.add_argument('--first-circle',nargs=2);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
old=read(a.manifest);c=read(a.control);pool=c['approvedCycle'+str(c['iteration'])+'AcceptancePool'];b=read(a.control.parent/('root-cycle'+str(c['iteration']))/'window-baseline.json')
assert set(pool)<=set(old['exactTargets']) and b['family']==c['familySlug']
s={**old,**{k:c[k]for k in ['iteration','windowStartUtc','deadlineUtc']},'control':str(a.control.resolve()),'owner':'/root','exactTargets':pool};active_control(s)
registry={r['sectorCode']:r for r in read(R/'scripts/neighborhoods/touil-reviews/current-official-sector-registry.json')['sectors']};cases={}
for q in old['cases']:
 ins=registry[q['officialCode']];assert ins['governorateCode']==c['governorateCode'] and ins['sectorAr']==q['officialName'] and ins['delegationAr']==q['officialParent'];checked(q['sourcePdf']);checked(q['sourceInventory']);key=(q['officialCode'],q['sourcePdf']['sha256']);assert key not in cases;cases[key]=dict(q)
for f in a.overlay:
 for q in read(f)['cases']:
  key=(q['officialCode'],q['sourcePdf']['sha256']);assert key in cases and q['sourcePdf']==cases[key]['sourcePdf'];checked(q['sourceInventory']);cases[key].update(q)
for q in cases.values():
 q['sourceContextOnly']=q['officialCode'] not in pool
 if q['sourceContextOnly']:assert q['officialCode'] in b['alreadyCompleteFamilyCodes']
 else:assert q.get('appId')
for value in a.choices:
 code,layer,index=value.split('=');assert code in pool and layer=='red' and a.diagnostics
 candidates=[q for q in read(a.diagnostics)['candidates'] if q['code']==code and q['layer']==layer and q['index']==int(index)];assert len(candidates)==1 and candidates[0]['wholeInsidePage'];d=candidates[0];checked(d['pageGeometry']);checked(d['render']);targets=[q for q in cases.values() if q['officialCode']==code];assert len(targets)==1 and targets[0]['sourcePdf']==d['sourcePdf'];targets[0].update(reviewedNativePageFace=d['pageGeometry'],rootDiagnosticRender=d['render'])
rows=list(cases.values())
if a.first_circle:
 code,circle=a.first_circle;assert code in pool;selected=[q for q in rows if q['officialCode']==code and q.get('declaredCircleIds')==[int(circle)]];assert len(selected)==1;rows=selected+[q for q in rows if q is not selected[0]]
assert set(pool)<=set(q['officialCode'] for q in rows)
put(a.output,{**s,'cases':rows,'originalContextManifest':pin(a.manifest.resolve()),'captionOverlays':[pin(f.resolve()) for f in a.overlay],'nativeExtractions':0,'credit':0})
print(json.dumps({'originalsReused':len(rows),'targetBodies':len(pool),'nativeExtractions':0,'credit':0}))
