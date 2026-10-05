"""Pin unique original caption glyph groups, including spatially merged spans.

Number position may vary in extracted RTL text. Spatial gaps separate actual
printed labels; original character boxes/text are retained for visual review.
No inferred ownership, geometry changes, acceptance, or validation credit.
"""
import argparse,json,sys
from pathlib import Path
import pymupdf
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.prepare_native_label_leads_v3 import norm,red
from scripts.locality_automation.prepare_numbered_native_leads_v1 import split

def segments(span):
    groups=[];current=[]
    for char in span['chars']:
        if current:
            a=current[-1]['bbox'];b=char['bbox']
            gap=max(b[0]-a[2],a[0]-b[2],0)
            if gap>span['size']*.75: groups.append(current);current=[]
        current.append(char)
    if current: groups.append(current)
    return groups

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--codes',nargs='+');a=p.parse_args()
    s=read(a.manifest);c=active_control(s);out=a.output.resolve();out.mkdir();cases=[];holds=[];plans=[]
    for q0 in s['cases']:
        q=dict(q0)
        if a.codes and q['officialCode']not in a.codes: cases.append(q);continue
        title=q.get('componentName',q['officialName']).split(' - (')[0]
        word,digit=split(title);parent=norm(q['officialParent']);targets={word,word+parent,parent+word}
        candidates=[]
        with pymupdf.open(checked(q['sourcePdf']))as doc:
            for b in doc[0].get_text('rawdict')['blocks']:
                for line in b.get('lines',[]):
                    for span in line['spans']:
                        if not red(span):continue
                        for chars in segments(span):
                            text=''.join(v['c']for v in chars).strip();w,n=split(text);wr,nr=split(text[::-1])
                            if n!=digit or not ({w,wr}&targets):continue
                            bounds=[min(v['bbox'][0]for v in chars),min(v['bbox'][1]for v in chars),max(v['bbox'][2]for v in chars),max(v['bbox'][3]for v in chars)]
                            candidates.append({'text':text,'boundsPagePoints':bounds,'centerPagePoints':[(bounds[0]+bounds[2])/2,(bounds[1]+bounds[3])/2],'colors':[span['color']],'redText':True,'maxFontSize':span['size'],'originalNativeCharacters':chars,'spatialGapThresholdFontFraction':.75})
        if len(candidates)!=1:
            holds.append({'code':q['officialCode'],'component':q.get('componentName'),'matches':len(candidates)});cases.append(q);continue
        lead=candidates[0];inv=read(checked(q['sourceInventory']));i=len(inv['pages'][0]['labeledAreaLeads']);inv['pages'][0]['labeledAreaLeads'].append(lead)
        inv['nativeCharacterCaptionSuccessor']={'priorInventory':q['sourceInventory'],'sourcePdf':q['sourcePdf'],'lead':lead,'credit':0}
        tag=q['officialCode']+('-'+str(q['declaredCircleIds'][0])if q.get('declaredCircleIds')else '')
        q.update(sourceInventory=put(out/(tag+'-inventory.json'),inv),diagnosticTargetLabelFace={'targetLabelIndex':i,'targetLabelText':lead['text']},reviewedInMapLabel=True,useAdministrativeFaces=True);cases.append(q);plans.append({'code':q['officialCode'],'component':q.get('componentName'),'lead':lead})
    put(out/'manifest.json',{**s,'cases':cases,'originalManifest':pin(a.manifest.resolve()),'credit':0});put(out/'caption-segment-facts.json',{'plans':plans,'holds':holds,'credit':0})
    print(json.dumps({'uniqueOriginalCaptions':len(plans),'holds':holds,'credit':0},ensure_ascii=False))

if __name__=='__main__':main()
