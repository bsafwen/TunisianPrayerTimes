"""Prepare exact in-map target label leads on finite adjoining indexed originals.
Diagnostic only; unchanged native review and root visual attribution required.
"""
import argparse,json,sys
from pathlib import Path
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.prepare_native_label_leads_v3 import norm
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--manifest',required=True,type=Path);p.add_argument('--targets',required=True,nargs='+');p.add_argument('--output',required=True,type=Path);a=p.parse_args()
s=read(a.manifest);c=active_control(s);pool=c['approvedCycle'+str(c['iteration'])+'AcceptancePool'];assert set(a.targets)<=set(pool);a.output.mkdir();rows=[]
for code in a.targets:
 originals=[r for r in s['cases']if r['officialCode']==code];assert len(originals)==1;original=originals[0];target=norm(original.get('componentName',original['officialName']))
 for src in s['cases']:
  if src['sourcePdf']['sha256']==original['sourcePdf']['sha256']:continue
  inv=read(checked(src['sourceInventory']))['pages'][0];leads=inv['labeledAreaLeads']
  ids=[i for i,v in enumerate(leads)if v['centerPagePoints'][1]>100 and target in {norm(v['text']),norm(v['text'][::-1])}]
  if len(ids)!=1:continue
  i=ids[0];case={**original,'sourcePdf':src['sourcePdf'],'sourceInventory':src['sourceInventory'],'sourceUrl':src['sourceUrl'],'selectedSourceFace':{'targetLabelIndex':i,'targetLabelText':leads[i]['text']},'neighborSourceOwnerCode':src['officialCode'],'reviewedInMapLabel':True,'originalOwnSourcePdf':original['sourcePdf'],'sourceAttributionRole':'Diagnostic exact in-map target caption on finite indexed neighbor. Requires actual root visual review and all unchanged native/registration gates.'}
  for key in ['diagnosticTargetLabelFace','reviewedNativePageFace','sourceContextOnly']:case.pop(key,None)
  path=a.output/(code+'-neighbor-'+src['officialCode']+'-manifest.json');put(path,{**s,'cases':[case],'credit':0})
  rows.append({'target':code,'sheet':src['officialCode'],'label':leads[i]['text'],'manifest':pin(path.resolve())})
put(a.output/'report.json',{'manifest':pin(a.manifest.resolve()),'candidates':rows,'credit':0,'sourceScopeAccepted':False})
print(json.dumps(rows,ensure_ascii=False))

