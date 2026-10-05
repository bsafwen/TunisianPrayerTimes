"""Bind exact indexed URLs to checked cached PDFs and finite fetch receipts.

Reuses cached native inventories. No source selection, acceptance or credit.
"""
import argparse
import hashlib
import json
from pathlib import Path
import sys
from urllib.parse import unquote
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('phase',choices=['plan','merge']);p.add_argument('--work',type=Path,required=True)
    p.add_argument('--evidence',type=Path,required=True);p.add_argument('--governorate-name')
    p.add_argument('--cache-queue',type=Path,action='append',default=[])
    p.add_argument('--inventory-directory',type=Path,action='append',default=[])
    a=p.parse_args();w=a.work.resolve();e=a.evidence.resolve();c=read(w.parent/'control.json');slug=c['familySlug']
    s={k:c[k]for k in ['iteration','windowStartUtc','deadlineUtc']};s.update(control=str(w.parent/'control.json'),owner='/root');active_control(s)
    plan_path=w/(slug+'-availability-plan-v2.json')
    if a.phase=='plan':
        assert a.governorate_name
        idx=e/'work/current-official-catalog/isie-local-boundary-index-links.json';rows=[]
        for v in read(idx):
            bits=unquote(v['url']).split('/CartesCirconscriptionsElectoralesLocales2023/')[-1].split('/')
            if len(bits)==3 and bits[0]==a.governorate_name:
                rows.append({'indexText':v['text'],'url':v['url'],'delegationPath':bits[1],
                    'pdfFilename':bits[2],'availability':'uncached','selectedPdf':None})
        assert rows and len({r['url']for r in rows})==len(rows)
        cache={};manifest_pins={};queue_pins=[]
        for queue in a.cache_queue:
            queue_pins.append(pin(queue))
            for case in read(queue)['cases']:
                for q in case.get('cachedPdfs',[]):
                    pdf={'file':q['file'],'sha256':q['sha256']};checked(pdf)
                    manifest=Path(q['manifest']);data=read(manifest)
                    assert isinstance(data,list)
                    matches=[r for r in data if r.get('sha256')==pdf['sha256'] and r.get('url')]
                    for r in matches:
                        key=unquote(r['url']);cache.setdefault(key,[]).append(pdf)
                        manifest_pins[str(manifest)]=pin(manifest)
        for row in rows:
            hits=cache.get(unquote(row['url']),[])
            if hits:
                assert len({q['sha256']for q in hits})==1
                row.update(selectedPdf=hits[0],availability='cached_valid')
                for directory in a.inventory_directory:
                    inv=directory/(hits[0]['sha256']+'.json')
                    if inv.exists() and read(inv)['sourcePdf']['sha256']==hits[0]['sha256']:
                        row['sourceInventory']=pin(inv);break
        missing=[r for r in rows if not r['selectedPdf']];manifests=[]
        for start in range(0,len(missing),20):
            sources=[{'key':'url-'+hashlib.sha256(r['url'].encode()).hexdigest()[:12],'url':r['url']}for r in missing[start:start+20]]
            manifests.append(put(w/('fetch-plan-'+str(start//20+1)+'.json'),{**s,'sources':sources,'sourceIndex':pin(idx),'credit':0}))
        put(plan_path,{**s,'isieSources':rows,'fetchManifests':manifests,'sourceIndex':pin(idx),
            'cacheQueues':queue_pins,'cacheManifests':list(manifest_pins.values()),'credit':0})
        print(json.dumps({'indexed':len(rows),'cached':len(rows)-len(missing),'cachedInventories':sum('sourceInventory'in r for r in rows),'missing':len(missing),'fetchGroups':len(manifests)}))
    else:
        plan=read(plan_path);receipts=[];fetched=[]
        for i,ref in enumerate(plan['fetchManifests'],1):
            checked(ref);path=w/('fetch-group-'+str(i)+'-v1/report.json');q=read(path);checked(q['manifest'])
            receipts.append(pin(path));fetched.extend(q['sources'])
        rows=plan['isieSources']
        for row in rows:
            hits=[q for q in fetched if q['url']==row['url']]
            if not hits:continue
            assert len(hits)==1;q=hits[0]
            if q['status']=='FETCHED_REQUIRES_ORIGINAL_REVIEW':
                checked(q['pdf']);row.update(selectedPdf=q['pdf'],availability='fetched_valid')
            else:row.update(availability='unknown',fetchObservation=q)
        put(w/(slug+'-cached-availability-v2.json'),{**plan,'isieSources':rows,'plan':pin(plan_path),'fetchReceipts':receipts,'credit':0})
        print(json.dumps({'available':sum(bool(r['selectedPdf'])for r in rows),'unknown':[r['pdfFilename']for r in rows if not r['selectedPdf']],'credit':0},ensure_ascii=False))

if __name__=='__main__':main()
