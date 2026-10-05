"""Merge disjoint finite native case manifests without changing facts or scope."""
import argparse,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--manifest',type=Path,action='append',required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();sources=[read(p)for p in a.manifest];base=sources[0];c=active_control(base);cases=[]
 for s in sources:
  active_control(s)
  if s['exactTargets']!=base['exactTargets']:raise ValueError('Original finite scope differs')
  cases.extend(s['cases'])
 codes=[v['officialCode']for v in cases]
 if len(codes)!=len(set(codes))or not set(codes)<=set(base['exactTargets']):raise ValueError('Unique target cases required')
 put(a.output.resolve(),{**base,'cases':cases,'mergedManifests':[pin(p.resolve())for p in a.manifest],'credit':0});print('Merged '+str(len(cases))+' disjoint finite source cases')
if __name__=='__main__':main()
