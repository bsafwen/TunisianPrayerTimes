"""Pin explicitly reviewed native diagnostic faces without accepting a source."""
import argparse,json,sys
from pathlib import Path
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,checked,active_control
p=argparse.ArgumentParser(description=__doc__)
for k in ['manifest','diagnostics','output']:p.add_argument('--'+k,type=Path,required=True)
p.add_argument('--choices',nargs='+',required=True);a=p.parse_args()
s=read(a.manifest);c=active_control(s);pool=c['approvedCycle'+str(c['iteration'])+'AcceptancePool']
if s['exactTargets']!=pool:raise ValueError('Exact finite pool differs')
diag=read(a.diagnostics)['candidates'];cases=[]
for choice in a.choices:
 code,layer,number=choice.split('=');number=int(number)
 if code not in pool or layer!='red':raise ValueError('Explicit exact red choice outside finite pool')
 base=[r for r in s['cases']if r['officialCode']==code]
 candidates=[r for r in diag if r['code']==code and r['layer']==layer and r['index']==number]
 if len(base)!=1 or len(candidates)!=1:raise ValueError('Choice is not unique')
 candidate=candidates[0];checked(candidate['pageGeometry']);checked(candidate['render'])
 cases.append({**base[0],'reviewedNativePageFace':candidate['pageGeometry'],'rootDiagnosticRender':candidate['render']})
if len({r['officialCode']for r in cases})!=len(cases):raise ValueError('Duplicate code choice')
with a.output.open('x',encoding='utf-8')as f:json.dump({**s,'cases':cases,'sourceOnlyCredit':0},f,ensure_ascii=False,indent=2)
print(json.dumps({'explicitChoices':len(cases),'credit':0}))
