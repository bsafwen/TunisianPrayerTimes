"""Pin selected original decree pages under the current finite family control."""
import argparse,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--work',type=Path,required=True);p.add_argument('--pdf',type=Path,required=True);p.add_argument('--pages',type=int,nargs='+',required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();w=a.work.resolve();c=read(w.parent/'control.json');s={k:c[k]for k in ['iteration','windowStartUtc','deadlineUtc']};s.update(control=str(w.parent/'control.json'),owner='/root');active_control(s)
 if not a.pages or len(a.pages)>8 or len(set(a.pages))!=len(a.pages)or min(a.pages)<1:raise ValueError('Finite unique original page numbers required')
 put(a.output.resolve(),{**s,'pdf':pin(a.pdf.resolve()),'pages':a.pages,'credit':0});print('Original roster manifest pinned; no acceptance')
if __name__=='__main__':main()
