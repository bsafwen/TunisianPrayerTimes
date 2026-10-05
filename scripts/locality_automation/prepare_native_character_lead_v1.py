"""Pin a human-selected original character run when PDF text merges two labels.

This diagnostic inventory successor earns no acceptance or geometry credit.
Every character and its bounds come from one exact unique original red span.
"""
import argparse,json,sys
from pathlib import Path
import pymupdf
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--plan',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
 s=read(a.manifest);control=active_control(s);plan=read(a.plan)
 if not set(plan)<=set(control['approvedCycle'+str(control['iteration'])+'AcceptancePool']):raise ValueError('Outside finite target scope')
 a.output.mkdir();cases=[]
 for code,t in plan.items():
  c=dict(next(q for q in s['cases']if q['officialCode']==code));pdf=checked(c['sourcePdf']);prior=checked(c['sourceInventory']);inv=read(prior)
  with pymupdf.open(pdf)as doc:
   spans=[v for b in doc[0].get_text('rawdict')['blocks']if'lines'in b for l in b['lines']for v in l['spans']if ''.join(x['c']for x in v['chars'])==t['nativeSpanText'] and list(v['bbox'])==t['nativeBounds'] and v['bbox'][1]>100]
  if len(spans)!=1:raise ValueError('Exact original span is not unique: '+code)
  v=spans[0];text=''.join(x['c']for x in v['chars']);substring=t['nativeSubstring'];color=v['color'];rgb=((color>>16)&255,(color>>8)&255,color&255)
  if not substring or text.count(substring)!=1 or not(rgb[0]>150 and rgb[1]<100 and rgb[2]<100):raise ValueError('Unique original red character run required: '+code)
  offset=text.index(substring);chars=v['chars'][offset:offset+len(substring)]
  if any(x.get('synthetic')for x in chars):raise ValueError('Synthetic characters are not source labels')
  bounds=[min(x['bbox'][0]for x in chars),min(x['bbox'][1]for x in chars),max(x['bbox'][2]for x in chars),max(x['bbox'][3]for x in chars)]
  lead={'text':substring,'boundsPagePoints':bounds,'centerPagePoints':[(bounds[0]+bounds[2])/2,(bounds[1]+bounds[3])/2],'colors':[color],'redText':True,'maxFontSize':v['size'],'originalNativeCharacterRun':True}
  index=len(inv['pages'][0]['labeledAreaLeads']);inv['pages'][0]['labeledAreaLeads'].append(lead)
  inv['explicitNativeCharacterSuccessor']={'priorInventory':pin(prior),'sourcePdf':pin(pdf),'plan':pin(a.plan.resolve()),'newOwnLeadIndex':index,'originalSpanBounds':list(v['bbox']),'originalSpanText':text,'nativeCharacters':chars,'credit':0}
  c.update(sourceInventory=put(a.output/(code+'-inventory.json'),inv),diagnosticTargetLabelFace={'targetLabelIndex':index,'targetLabelText':substring},reviewedInMapLabel=True,useAdministrativeFaces=True);cases.append(c)
  print(json.dumps({'code':code,'lead':lead,'nativeCharacters':len(chars),'credit':0},ensure_ascii=False))
 put(a.output/'manifest.json',{**s,'cases':cases,'originalManifest':pin(a.manifest.resolve()),'credit':0})
if __name__=='__main__':main()
