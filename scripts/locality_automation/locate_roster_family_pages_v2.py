"""Locate exact family header text in an immutable cached roster, for visual review."""
import argparse,json,sys,unicodedata
from pathlib import Path
import pymupdf
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
def norm(s):return ''.join(c for c in unicodedata.normalize('NFKC',s)if c.isalnum())
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--work',type=Path,required=True);p.add_argument('--pdf',type=Path,required=True);p.add_argument('--name',required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();w=a.work.resolve();c=read(w.parent/'control.json');s={k:c[k]for k in ['iteration','windowStartUtc','deadlineUtc']};s.update(control=str(w.parent/'control.json'),owner='/root');active_control(s)
 pdf=a.pdf.resolve();ref=pin(pdf);matches=[];doc=pymupdf.open(pdf)
 for i,page in enumerate(doc):
  lines=page.get_text('text').splitlines();hits=[v for v in lines if norm(a.name)in norm(v)or norm(a.name[::-1])in norm(v)]
  if hits or norm(a.name)in norm(''.join(lines))or norm(a.name[::-1])in norm(''.join(lines)):matches.append({'pageZeroBased':i,'matchedTextLines':hits,'joinedPageTextMatch':True})
 if not matches:raise ValueError('No family text found; do not guess roster pages')
 result={**s,'pdf':ref,'familyName':a.name,'matchingPages':matches,'pageCount':len(doc),'method':'Exact normalized family name or reverse text order; only page candidates, actual full-page visual review required.','visualReviewed':False,'credit':0};put(a.output.resolve(),result);print(json.dumps(result,ensure_ascii=False))
if __name__=='__main__':main()

