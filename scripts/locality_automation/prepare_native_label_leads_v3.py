"""Pin unique original red title spans or split lines without fabricating labels."""
import argparse,json,sys,unicodedata
from pathlib import Path
from urllib.parse import unquote,urlsplit
import pymupdf
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
def norm(text):
 text=unicodedata.normalize('NFKC',text)
 return ''.join(v for v in text if v.isalnum()).translate(str.maketrans('أإآىةیھ','ااايهيه'))
def red(v):return v['bbox'][1]>100 and v['size']>=10 and(v['color']>>16)>200 and(v['color']&255)<60
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--all-cases',action='store_true');a=p.parse_args();s=read(a.manifest);c=active_control(s);out=a.output.resolve();out.mkdir();cases=[];holds=[];plans=[]
 for q0 in s['cases']:
  q=dict(q0)
  if q.get('diagnosticTargetLabelFace')and not a.all_cases:cases.append(q);continue
  bits=unquote(urlsplit(q['sourceUrl']).path).split('/');title=q.get('componentName',q['officialName']);stem=bits[-1].removesuffix('.pdf').split(' - (')[0];parents={norm(q['officialParent']),norm(bits[-2])};names={norm(title),norm(stem)};targets=names|{x+y for x in names for y in parents}|{y+x for x in names for y in parents}
  pdf=checked(q['sourcePdf']);inv=read(checked(q['sourceInventory']));candidates={}
  with pymupdf.open(pdf)as doc:
   for b in doc[0].get_text('dict')['blocks']:
    for line in b.get('lines',[]):
     spans=line['spans'];groups=[[v]for v in spans if red(v)]
     if len(spans)>1 and all(red(v)for v in spans):groups.append(spans)
     for parts in groups:
      text=''.join(v['text']for v in parts)
      if norm(text)not in targets and norm(text[::-1])not in targets:continue
      key=tuple((v['text'],*v['bbox'])for v in parts);candidates[key]=parts
  if len(candidates)!=1:
   holds.append({'code':q['officialCode'],'component':q.get('componentName'),'matches':len(candidates),'candidates':[[{'text':v['text'],'bounds':list(v['bbox'])}for v in x]for x in candidates.values()]});cases.append(q);continue
  parts=next(iter(candidates.values()));bounds=[min(v['bbox'][0]for v in parts),min(v['bbox'][1]for v in parts),max(v['bbox'][2]for v in parts),max(v['bbox'][3]for v in parts)];text=''.join(v['text']for v in parts)
  lead={'text':text,'boundsPagePoints':bounds,'centerPagePoints':[(bounds[0]+bounds[2])/2,(bounds[1]+bounds[3])/2],'colors':list({v['color']for v in parts}),'redText':True,'maxFontSize':max(v['size']for v in parts),'originalNativeSpanParts':[{'text':v['text'],'bounds':list(v['bbox']),'color':v['color'],'size':v['size']}for v in parts]}
  index=len(inv['pages'][0]['labeledAreaLeads']);inv['pages'][0]['labeledAreaLeads'].append(lead);inv['explicitNativeLineSuccessor']={'priorInventory':q['sourceInventory'],'sourcePdf':q['sourcePdf'],'lead':lead,'credit':0}
  tag=q['officialCode']+('-'+str(len(plans))if q.get('componentName')else '')
  q.update(sourceInventory=put(out/(tag+'-inventory.json'),inv),diagnosticTargetLabelFace={'targetLabelIndex':index,'targetLabelText':text},reviewedInMapLabel=True,useAdministrativeFaces=True);cases.append(q);plans.append({'code':q['officialCode'],'component':q.get('componentName'),'nativeLead':lead})
 put(out/'manifest.json',{**s,'cases':cases,'originalManifest':pin(a.manifest.resolve()),'credit':0});put(out/'native-label-plan.json',{'plans':plans,'holds':holds,'nativePartsPreserved':True,'credit':0})
 print(json.dumps({'uniqueNativeTitlesPinned':len(plans),'cases':len(cases),'holds':holds,'credit':0},ensure_ascii=False))
if __name__=='__main__':main()
