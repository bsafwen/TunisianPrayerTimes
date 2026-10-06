"""Apply explicit finite reviewed source/label overrides, preserving prior attempts."""
import argparse,sys,json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import read,checked,pin,active_control
from scripts.locality_automation.family_work_v1 import put
from scripts.locality_automation.isie_pdf_inventory import inspect
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--base',type=Path,required=True);p.add_argument('--plan',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();spec=read(a.base);active_control(spec);plan=read(a.plan);cases={r['officialCode']:r for r in spec['cases']};selected=[]
    for change in plan['cases']:
        code=change['officialCode'];assert code in spec['exactTargets'];case={**cases.get(code,{}),**change}
        if 'sourceInventory'not in case:
            path=a.output.parent/(code+'-successor-inventory.json');put(path,inspect(checked(case['sourcePdf'])));case['sourceInventory']=pin(path)
        inv=read(checked(case['sourceInventory']));leads=inv['pages'][0]['labeledAreaLeads']
        if 'targetLabelIndex'in change:
            i=change['targetLabelIndex'];case['diagnosticTargetLabelFace']={'targetLabelIndex':i,'targetLabelText':leads[i]['text']};case['reviewedInMapLabel']=True
        selected.append(case)
    put(a.output,{**spec,'cases':selected,'successorPlan':pin(a.plan),'originalManifest':pin(a.base),'credit':0});print(json.dumps({'prepared':[r['officialCode']for r in selected],'labels':{r['officialCode']:read(checked(r['sourceInventory']))['pages'][0]['labeledAreaLeads']for r in selected if r['officialCode']not in cases}},ensure_ascii=False))
