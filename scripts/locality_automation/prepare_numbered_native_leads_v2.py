"""Pin unique real numbered captions for repeated constituent official codes."""
import argparse,json,sys
from pathlib import Path
import pymupdf
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.prepare_native_label_leads_v3 import red
from scripts.locality_automation.prepare_numbered_native_leads_v1 import split

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();s=read(a.manifest);active_control(s);out=a.output.resolve();out.mkdir();cases=[];seen=set()
 for q0 in s['cases']:
  q=dict(q0);target,number=split(q['componentName']);assert number and len(number)==1
  tag=q['officialCode']+'-'+number;assert tag not in seen;seen.add(tag)
  with pymupdf.open(checked(q['sourcePdf']))as doc:
   spans=[v for b in doc[0].get_text('dict')['blocks']for l in b.get('lines',[])for v in l['spans']if red(v)]
  hits=[v for v in spans if split(v['text'])[1]==number and(split(v['text'])[0]==target or split(v['text'][::-1])[0]==target)]
  if len(hits)!=1:raise ValueError('Numbered native caption is not unique: '+q['componentName'])
  v=hits[0];x0,y0,x1,y1=v['bbox'];lead={'text':v['text'],'boundsPagePoints':list(v['bbox']),'centerPagePoints':[(x0+x1)/2,(y0+y1)/2],'colors':[v['color']],'redText':True,'maxFontSize':v['size'],'originalNativeSpan':True};inv=read(checked(q['sourceInventory']));i=len(inv['pages'][0]['labeledAreaLeads']);inv['pages'][0]['labeledAreaLeads'].append(lead);inv['numberedNativeSpanSuccessor']={'priorInventory':q['sourceInventory'],'sourcePdf':q['sourcePdf'],'nativeSpan':lead,'componentName':q['componentName'],'credit':0};q.update(sourceInventory=put(out/(tag+'-inventory.json'),inv),diagnosticTargetLabelFace={'targetLabelIndex':i,'targetLabelText':lead['text']},reviewedInMapLabel=True,useAdministrativeFaces=True);cases.append(q)
 put(out/'manifest.json',{**s,'cases':cases,'originalManifest':pin(a.manifest.resolve()),'credit':0});print(json.dumps({'pinnedNumberedCaptions':len(cases),'uniqueConstituentOutputNames':True,'credit':0}))

if __name__=='__main__':main()
