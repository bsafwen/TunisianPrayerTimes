"""Bind explicitly reviewed original red faces by entry and declared circle.

Preserves all source/roster/native facts; the unchanged constituent reviewer
must still verify every source. No inference, acceptance or validation credit.
"""
import argparse,json,sys
from pathlib import Path
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for k in ['manifest','plan','diagnostics','output']:p.add_argument('--'+k,type=Path,required=True)
    p.add_argument('--choices',nargs='+',required=True);a=p.parse_args()
    s=read(a.manifest);c=active_control(s);plan=read(a.plan);diag=read(a.diagnostics)['candidates'];keys=set();selected={}
    if s['exactTargets']!=c['approvedCycle'+str(c['iteration'])+'AcceptancePool']:raise ValueError('Finite scope differs')
    for choice in a.choices:
        key,layer,index=choice.split('=');code,n=key.split(':');n=int(n);index=int(index)
        if layer!='red' or code not in s['exactTargets']or(code,n)in keys:raise ValueError('Choice outside scope or duplicate')
        keys.add((code,n))
        base=[q for q in s['cases']if q['officialCode']==code and q.get('declaredCircleIds')==[n]]
        if len(base)!=1:raise ValueError('Declared original not unique')
        q=base[0];hits=[g for g in diag if g['code']==code and g['layer']=='red'and g['index']==index and g['declaredCircleIds']==[n]and g['sourcePdf']==q['sourcePdf']and g['sourceInventory']==q['sourceInventory']]
        if len(hits)!=1 or not hits[0]['wholeInsidePage']:raise ValueError('Exact original face is not unique and complete')
        g=hits[0];checked(g['pageGeometry']);checked(g['render'])
        selected[(code,n)]={**q,'reviewedNativePageFace':g['pageGeometry'],'rootDiagnosticRender':g['render']}
    groups=[]
    for group in plan['cases']:
        if group['officialCode']not in {k[0]for k in keys}:continue
        expected={(group['officialCode'],n)for n in group.get('expectedCircleIds',list(range(1,group['expectedCircleCount']+1)))}
        if not expected<=keys:raise ValueError('Every declared constituent must be explicitly selected')
        parts=[selected[(group['officialCode'],q['declaredCircleIds'][0])]for q in group['components']]
        if [q['sourcePdf']for q in parts]!=[q['sourcePdf']for q in group['components']]:raise ValueError('Original roster changes')
        groups.append({**group,'components':parts})
    if sum(len(g['components'])for g in groups)!=len(keys):raise ValueError('Unaccounted choice')
    put(a.output,{**plan,'cases':groups,'originalPlan':pin(a.plan.resolve()),'diagnostics':pin(a.diagnostics.resolve()),'credit':0})
    print(json.dumps({'groups':len(groups),'explicitNativeParts':len(keys),'credit':0}))
