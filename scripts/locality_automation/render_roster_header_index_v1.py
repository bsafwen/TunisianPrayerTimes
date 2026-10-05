"""Reusable visual index of scanned roster columns; no locality validation."""
import argparse,json,sys
from pathlib import Path
import pymupdf
from PIL import Image,ImageDraw
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--work',type=Path,required=True);p.add_argument('--pdf',type=Path,required=True);p.add_argument('--first',type=int,required=True);p.add_argument('--last',type=int,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();w=a.work.resolve();c=read(w.parent/'control.json');s={k:c[k]for k in ['iteration','windowStartUtc','deadlineUtc']};s.update(control=str(w.parent/'control.json'),owner='/root');active_control(s)
 ref=pin(a.pdf.resolve());a.output.mkdir();doc=pymupdf.open(checked(ref));records=[]
 if not 1<=a.first<=a.last<=len(doc)or a.last-a.first>100:raise ValueError('Finite original page range required')
 pages=list(range(a.first,a.last+1))
 for start in range(0,len(pages),10):
  sheet=Image.new('RGB',(1250,1870),'white');draw=ImageDraw.Draw(sheet)
  for i,n in enumerate(pages[start:start+10]):
   page=doc[n-1];clip=pymupdf.Rect(335,100,502,700)&page.rect;pix=page.get_pixmap(matrix=pymupdf.Matrix(1.48,1.48),clip=clip,alpha=False);tile=Image.frombytes('RGB',[pix.width,pix.height],pix.samples);x=(i%5)*250;y=(i//5)*935+25;sheet.paste(tile,(x,y));draw.text((x+8,y-20),'PDF PAGE '+str(n),fill='black')
  f=a.output/('headers-'+str(start//10+1)+'.png');sheet.save(f);records.append({'pages':pages[start:start+10],'image':pin(f.resolve())})
 put(a.output/'receipt.json',{'pdf':ref,'headerContacts':records,'cropPagePoints':[335,100,502,700],'qualification':'Scanned governorate/delegation column locator only; not a source body or full-page legal review.','credit':0});print(json.dumps({'sheets':len(records),'first':a.first,'last':a.last,'credit':0}))
if __name__=='__main__':main()
