"""Exact neighbor-caption discovery with one verified inventory read per source.

Diagnostic only. Optional parent-qualified literal forms expand discovery, never
identity or acceptance. Every candidate still requires the existing native and
registration reviewer, actual root visual attribution, and final source gates.
"""
import argparse,json,sys
from pathlib import Path
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.prepare_native_label_leads_v3 import norm

def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument('--manifest',required=True,type=Path)
 p.add_argument('--targets',required=True,nargs='+')
 p.add_argument('--output',required=True,type=Path)
 p.add_argument('--parent-qualified',action='store_true')
 a=p.parse_args();s=read(a.manifest);c=active_control(s)
 pool=c['approvedCycle'+str(c['iteration'])+'AcceptancePool']
 if not set(a.targets)<=set(pool) or len(a.targets)!=len(set(a.targets)):
  raise ValueError('Unique targets must belong to the unchanged finite pool')
 a.output.mkdir();rows=[];cache={};reads=0
 for src in s['cases']:
  ref=src['sourceInventory'];key=(ref['file'],ref['sha256'])
  if key not in cache:
   inv=read(checked(ref));reads+=1
   cache[key]=inv['pages'][0]['labeledAreaLeads']
 for code in a.targets:
  originals=[r for r in s['cases']if r['officialCode']==code]
  if len(originals)!=1:raise ValueError('Exact target source binding is not unique')
  original=originals[0];target=norm(original.get('componentName',original['officialName']))
  forms={target}
  if a.parent_qualified:
   parent=norm(original['officialParent']);forms|={target+parent,parent+target}
  for src in s['cases']:
   if src['sourcePdf']['sha256']==original['sourcePdf']['sha256']:continue
   ref=src['sourceInventory'];leads=cache[(ref['file'],ref['sha256'])]
   ids=[i for i,v in enumerate(leads)if v['centerPagePoints'][1]>100 and forms.intersection({norm(v['text']),norm(v['text'][::-1])})]
   if len(ids)!=1:continue
   i=ids[0];case={**original,'sourcePdf':src['sourcePdf'],'sourceInventory':ref,'sourceUrl':src['sourceUrl'],'selectedSourceFace':{'targetLabelIndex':i,'targetLabelText':leads[i]['text']},'neighborSourceOwnerCode':src['officialCode'],'reviewedInMapLabel':True,'originalOwnSourcePdf':original['sourcePdf'],'sourceAttributionRole':'Diagnostic exact in-map target caption on finite indexed neighbor. Requires actual root visual review and all unchanged native/registration gates.'}
   for key in ['diagnosticTargetLabelFace','reviewedNativePageFace','sourceContextOnly']:case.pop(key,None)
   path=a.output/(code+'-neighbor-'+src['officialCode']+'-manifest.json')
   put(path,{**s,'cases':[case],'credit':0})
   rows.append({'target':code,'sheet':src['officialCode'],'label':leads[i]['text'],'manifest':pin(path.resolve())})
 put(a.output/'report.json',{'manifest':pin(a.manifest.resolve()),'candidates':rows,'credit':0,'sourceScopeAccepted':False,'verifiedInventoryReads':reads,'parentQualifiedLiteralForms':a.parent_qualified})
 print(json.dumps({'candidates':len(rows),'verifiedInventoryReads':reads,'parentQualifiedLiteralForms':a.parent_qualified,'credit':0}))

if __name__=='__main__':main()
