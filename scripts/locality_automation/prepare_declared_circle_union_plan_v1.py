"""Join explicitly declared native-circle cases against a pinned legal roster."""
import argparse,json,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--roster',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();s=read(a.manifest);active_control(s);roster=read(a.roster);cases=[]
 for g in roster['groups']:
  parts=[q for q in s['cases']if q['officialCode']==g['officialCode']];assert len(parts)==g['expectedCircleCount']
  assert sorted(n for q in parts for n in q['declaredCircleIds'])==list(range(1,g['expectedCircleCount']+1))
  for q in parts:
   matches=[v for v in g['components']if v['circle']==q['declaredCircleIds'][0]];assert len(matches)==1 and matches[0]['sourcePdf']==q['sourcePdf'] and matches[0]['sourceUrl']==q['sourceUrl']
  cases.append({'officialCode':g['officialCode'],'expectedCircleCount':g['expectedCircleCount'],'components':parts})
 for ref in roster['legalPages']:checked(ref)
 put(a.output.resolve(),{'cases':cases,'legalEvidence':pin(a.roster.resolve()),'qualification':'Explicit complete decree circle roster; exact original native faces, literal union, no snapping or inferred completion.'});print(json.dumps({'completeDeclaredUnits':len(cases),'constituents':sum(len(v['components'])for v in cases),'credit':0}))

if __name__=='__main__':main()
