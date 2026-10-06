"""Pin a root-selected original native text span, including repeated-label bounds."""
import argparse,json,sys
from pathlib import Path
import pymupdf
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--plan',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();s=read(a.manifest);c=active_control(s);plan=read(a.plan)
 if not set(plan)<=set(c['approvedCycle'+str(c['iteration'])+'AcceptancePool']):raise ValueError('Outside finite scope')
 out=a.output.resolve();out.mkdir();cases=[]
 for code,t in plan.items():
  q=dict(next(q for q in s['cases']if q['officialCode']==code and'componentName'not in q));pdf=checked(q['sourcePdf']);old=checked(q['sourceInventory']);inv=read(old)
  with pymupdf.open(pdf)as doc:
   spans=[v for b in doc[0].get_text('dict')['blocks']if'lines'in b for l in b['lines']for v in l['spans']if v['bbox'][1]>100 and v['text']==t['nativeSpanText']and('nativeBounds'not in t or list(v['bbox'])==t['nativeBounds'])]
  if len(spans)!=1:raise ValueError('Selected original native span is not unique: '+code)
  v=spans[0];x0,y0,x1,y1=v['bbox'];lead={'text':v['text'],'boundsPagePoints':list(v['bbox']),'centerPagePoints':[(x0+x1)/2,(y0+y1)/2],'colors':[v['color']],'redText':True,'maxFontSize':v['size'],'originalNativeSpan':True}
  i=len(inv['pages'][0]['labeledAreaLeads']);inv['pages'][0]['labeledAreaLeads'].append(lead);inv['explicitNativeSpanSuccessor']={'priorInventory':pin(old),'sourcePdf':pin(pdf),'plan':pin(a.plan.resolve()),'newOwnLeadIndex':i,'nativeSpan':lead,'credit':0}
  ref=put(out/(code+'-inventory.json'),inv);q.update(sourceInventory=ref,diagnosticTargetLabelFace={'targetLabelIndex':i,'targetLabelText':lead['text']},reviewedInMapLabel=True,useAdministrativeFaces=True);cases.append(q)
  print(json.dumps({'code':code,'nativeOwnSpan':lead},ensure_ascii=False))
 put(out/'manifest.json',{**s,'cases':cases,'originalManifest':pin(a.manifest.resolve()),'credit':0})
if __name__=='__main__':main()
