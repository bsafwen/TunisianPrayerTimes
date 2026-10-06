"""Store and retrieve actual visually confirmed decree pages, keyed by PDF hash.

This is a navigation index only. It never establishes roster membership or earns
source credit; the saved complete originals still require actual review.
"""
import argparse,json,sys
from pathlib import Path
from datetime import datetime,timezone
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
p=argparse.ArgumentParser(description=__doc__);p.add_argument('mode',choices=['confirm','lookup']);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--index',type=Path,required=True);p.add_argument('--governorate-code',required=True);p.add_argument('--family',required=True);p.add_argument('--render-dir',type=Path);p.add_argument('--review-note');p.add_argument('--receipt',type=Path,required=True);a=p.parse_args()
s=read(a.manifest);pdf=checked(s['pdf']);key=s['pdf']['sha256'];old=pin(a.index)if a.index.exists()else None
index=read(a.index)if old else {'schemaVersion':1,'role':'Navigation only; no legal/source acceptance','pdfs':{}}
if a.mode=='confirm':
 c=active_control(s)
 if c['governorateCode']!=a.governorate_code or c['familySlug']!=a.family or not a.review_note or not a.render_dir:raise ValueError('Explicit current family and actual visual review note required')
 pages=s['pages']
 if not pages or pages!=sorted(set(pages))or any(not isinstance(n,int)or n<=0 for n in pages):raise ValueError('Distinct one-based confirmed pages required')
 entry={'family':a.family,'governorateCode':a.governorate_code,'confirmedOneBasedPdfPages':pages,'completeOriginalPageRenders':[pin(a.render_dir/f'page-{n}.png')for n in pages],'actualReviewer':'/root','visualReviewNote':a.review_note,'sourceManifest':pin(a.manifest.resolve()),'credit':0}
 target=index['pdfs'].setdefault(key,{'sourcePdf':pin(pdf),'families':{}})
 if a.governorate_code in target['families']:raise ValueError('Confirmed family already indexed; preserve original entry')
 target['families'][a.governorate_code]=entry
 temp=a.index.with_name(a.index.name+'.tmp')
 with temp.open('x',encoding='utf-8')as f:json.dump(index,f,ensure_ascii=False,indent=2)
 if (pin(a.index)if a.index.exists()else None)!=old:raise ValueError('Concurrent navigation index change')
 temp.replace(a.index)
entry=read(a.index)['pdfs'][key]['families'][a.governorate_code]
if entry['family']!=a.family or entry['actualReviewer']!='/root':raise ValueError('Confirmed navigation identity differs')
for r in entry['completeOriginalPageRenders']:checked(r)
result={'status':'CONFIRMED_PAGE_NAVIGATION_ONLY','mode':a.mode,'index':pin(a.index),'priorIndex':old,'sourcePdf':pin(pdf),'family':a.family,'governorateCode':a.governorate_code,'pages':entry['confirmedOneBasedPdfPages'],'fullLegalReviewStillRequired':True,'credit':0,'atUtc':datetime.now(timezone.utc).isoformat()}
with a.receipt.open('x',encoding='utf-8')as f:json.dump(result,f,ensure_ascii=False,indent=2)
print(json.dumps({'mode':a.mode,'pages':result['pages'],'credit':0}))
