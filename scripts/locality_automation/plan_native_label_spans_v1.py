"""Find unique real red title spans; report ambiguous or split labels explicitly."""
import argparse,json,sys,unicodedata
from pathlib import Path
from urllib.parse import unquote,urlsplit
import pymupdf
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
def norm(value):
 value=unicodedata.normalize('NFKC',value)
 return ''.join(v for v in value if v.isalnum()).translate(str.maketrans('أإآىةی','ااايهي'))
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--all-cases',action='store_true');a=p.parse_args();s=read(a.manifest);active_control(s);plan={};holds=[]
 for q in s['cases']:
  if q.get('diagnosticTargetLabelFace')and not a.all_cases:continue
  title=q.get('componentName',q['officialName']);stem=unquote(urlsplit(q['sourceUrl']).path).split('/')[-1].removesuffix('.pdf').split(' - (')[0]
  targets={norm(title),norm(stem)}
  with pymupdf.open(checked(q['sourcePdf']))as doc:
   spans=[v for b in doc[0].get_text('dict')['blocks']if'lines'in b for line in b['lines']for v in line['spans']if v['bbox'][1]>100 and v['size']>=10 and(v['color']>>16)>200 and(v['color']&255)<60]
  hits=[v for v in spans if norm(v['text'])in targets or norm(v['text'][::-1])in targets]
  if len(hits)==1:
   v=hits[0];plan[q['officialCode']]={'nativeSpanText':v['text'],'nativeBounds':list(v['bbox'])}
  else:holds.append({'code':q['officialCode'],'title':title,'targets':sorted(targets),'hits':len(hits),'spans':[{'text':v['text'],'bounds':list(v['bbox']),'size':v['size'],'color':v['color']}for v in spans]})
 out=a.output.resolve();out.mkdir();put(out/'plan.json',plan);put(out/'holds.json',{'manifest':pin(a.manifest.resolve()),'holds':holds,'credit':0})
 print(json.dumps({'uniqueRealNativeSpans':len(plan),'holds':holds,'credit':0},ensure_ascii=False))
if __name__=='__main__':main()
