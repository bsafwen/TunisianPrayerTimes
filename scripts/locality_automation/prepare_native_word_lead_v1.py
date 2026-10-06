"""Preserve an explicitly selected original PDF word run as a native label lead."""
import argparse,json,sys
from pathlib import Path
import pymupdf
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.family_work_v1 import norm
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--plan',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();s=read(a.manifest);c=active_control(s);plan=read(a.plan)
 if not set(plan)<=set(c['approvedCycle'+str(c['iteration'])+'AcceptancePool']):raise ValueError('Outside finite scope')
 out=a.output.resolve();out.mkdir();cases=[]
 for code,t in plan.items():
  q=dict(next(q for q in s['cases']if q['officialCode']==code and 'componentName'not in q));pdf=checked(q['sourcePdf']);old=checked(q['sourceInventory']);inv=read(old)
  with pymupdf.open(pdf)as doc:
   words=doc[0].get_text('words');chosen=[]
   for index,expected in t['words']:
    hits=[v for v in words if list(v[5:8])==index and v[4]==expected]
    if len(hits)!=1:raise ValueError('Original native word differs')
    chosen.append(hits[0])
  text=''.join(v[4]for v in chosen)
  if norm(text)!=norm(q['officialName'])or any(v[1]<=100 for v in chosen):raise ValueError('Own original map name differs')
  bbox=[min(v[0]for v in chosen),min(v[1]for v in chosen),max(v[2]for v in chosen),max(v[3]for v in chosen)]
  lead={'text':text,'boundsPagePoints':bbox,'centerPagePoints':[(bbox[0]+bbox[2])/2,(bbox[1]+bbox[3])/2],'redText':True,'originalNativeWordRun':chosen}
  i=len(inv['pages'][0]['labeledAreaLeads']);inv['pages'][0]['labeledAreaLeads'].append(lead);inv['explicitNativeWordSuccessor']={'priorInventory':pin(old),'sourcePdf':pin(pdf),'plan':pin(a.plan.resolve()),'newOwnLeadIndex':i,'nativeWordLead':lead,'credit':0}
  ref=put(out/(code+'-inventory.json'),inv);q.update(sourceInventory=ref,diagnosticTargetLabelFace={'targetLabelIndex':i,'targetLabelText':text},reviewedInMapLabel=True,useAdministrativeFaces=True);cases.append(q)
  print(json.dumps({'code':code,'nativeOwnWordLead':lead},ensure_ascii=False))
 put(out/'manifest.json',{**s,'cases':cases,'originalManifest':pin(a.manifest.resolve()),'credit':0})
if __name__=='__main__':main()
