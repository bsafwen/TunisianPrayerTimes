"""Locate and render only the Djerba roster pages of the cached official decree."""
import argparse, re, unicodedata, json
from pathlib import Path
import pymupdf
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--pdf',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--pages',nargs='+',type=int);a=p.parse_args();a.output.mkdir()
    rows=[]
    with pymupdf.open(a.pdf) as doc:
        for i,page in enumerate(doc):
            text=page.get_text();flat=re.sub(r'\s','',unicodedata.normalize('NFKC',text))
            if a.pages:
                if i+1 not in a.pages:continue
            elif 'جربة' not in flat and 'ةبرج' not in flat:continue
            png=a.output/('decree-page-'+str(i+1)+'.png');page.get_pixmap(matrix=pymupdf.Matrix(1.8,1.8),alpha=False).save(png)
            rows.append({'page':i+1,'text':text,'render':str(png)})
    (a.output/'djerba-roster-pages.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(rows,ensure_ascii=False))
