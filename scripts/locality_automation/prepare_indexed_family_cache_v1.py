"""Reuse only exact indexed family URLs from original batch manifests.

Reads local metadata once, hashes only matching family PDFs, preserves original
manifest pins, and awards no validation credit. No network requests.
"""
import argparse, hashlib, json, sys
from pathlib import Path
from urllib.parse import unquote
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import read, pin, active_control
from scripts.locality_automation.audit_reviewed_source_family import put

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--work', type=Path, required=True)
    p.add_argument('--evidence', type=Path, required=True)
    p.add_argument('--governorate-name', required=True)
    a = p.parse_args(); w = a.work.resolve(); e = a.evidence.resolve()
    c = read(w.parent / 'control.json')
    s = {k:c[k] for k in ['iteration','windowStartUtc','deadlineUtc']}
    s.update(control=str(w.parent/'control.json'), owner='/root'); active_control(s)
    index = e/'work/current-official-catalog/isie-local-boundary-index-links.json'
    urls = set()
    for row in read(index):
        bits = unquote(row['url']).split('/CartesCirconscriptionsElectoralesLocales2023/')[-1].split('/')
        if len(bits)==3 and bits[0]==a.governorate_name: urls.add(unquote(row['url']))
    assert urls
    matched=[]; missing=[]; scans=0; checked_hash={}
    for manifest in sorted((e/'work/locality-automation/batches').glob('*/*/manifest.json')):
        scans+=1; rows=read(manifest)
        assert isinstance(rows,list), str(manifest)
        for row in rows:
            if unquote(row.get('url','')) not in urls: continue
            pdf=Path(row['file']); pdf=pdf if pdf.is_absolute() else manifest.parent/pdf
            pdf=pdf.resolve()
            if not pdf.is_file(): missing.append({'manifest':str(manifest),'file':str(pdf)}); continue
            digest=checked_hash.setdefault(str(pdf),hashlib.sha256(pdf.read_bytes()).hexdigest())
            if digest!=row['sha256']: raise ValueError('Cached PDF hash mismatch: '+str(pdf))
            matched.append({'file':str(pdf),'sha256':digest,'manifest':str(manifest),'url':row['url']})
    output=w/(c['familySlug']+'-indexed-cache-v1.json')
    put(output,{**s,'cases':[{'cachedPdfs':matched}],'sourceIndex':pin(index),
        'manifestMetadataCount':scans,'missingCacheFiles':missing,'credit':0})
    print(json.dumps({'indexed':len(urls),'cachedUrls':len({unquote(r['url']) for r in matched}),
        'matchedCacheReferences':len(matched),'manifestMetadataCount':scans,'missingCacheFiles':len(missing),'output':str(output),'credit':0}))

if __name__=='__main__': main()
