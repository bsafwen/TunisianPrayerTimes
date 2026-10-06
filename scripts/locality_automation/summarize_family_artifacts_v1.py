"""Build a bounded receipt-derived artifact index; diagnostic only, zero credit.

Uses the current producer's literal launch arguments and existing output paths
instead of guessing filenames or scanning the historical evidence tree.
"""
import argparse,json,sys
from pathlib import Path
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,active_control
from scripts.locality_automation.audit_reviewed_source_family import put

def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument('--work',type=Path,required=True);p.add_argument('--family',required=True)
 p.add_argument('--output',type=Path,required=True);a=p.parse_args();w=a.work.resolve()
 if Path.cwd().resolve()!=w:raise ValueError('Existing current producer cwd required')
 c=read(w.parent/'control.json');s={k:c[k]for k in ['iteration','windowStartUtc','deadlineUtc']}
 s.update(control=str(w.parent/'control.json'),owner='/root');active_control(s)
 if c['familySlug']!=a.family:raise ValueError('Current finite family required')
 output=a.output.resolve()
 if output.parent!=w or output.exists():raise ValueError('Exclusive same-producer output required')
 roles={'--output','--receipt','--manifest','--stage','--stage-report','--facts','--sources','--decisions','--early-check','--configuration','--preflight-receipt'}
 rows=[]
 for path in sorted(w.glob('*.launch.json')):
  launch=read(path);args=launch.get('arguments',[])
  if not isinstance(args,list):continue
  refs=[]
  for i,arg in enumerate(args[:-1]):
   if arg not in roles:continue
   target=Path(args[i+1]);target=target.resolve()if target.is_absolute()else(w/target).resolve()
   if not target.is_relative_to(w):continue
   refs.append({'role':arg,'path':str(target),'exists':target.exists(),'isDirectory':target.is_dir(),'file':pin(target)if target.is_file()else None})
  execution=path.with_name(path.name.removesuffix('.launch.json')+'.execution.json')
  q=read(execution)if execution.is_file()else{}
  rows.append({'launch':pin(path),'program':launch.get('program'),'phase':launch.get('phase'),'actualPaths':refs,'execution':pin(execution)if execution.is_file()else None,'status':q.get('status'),'exitCode':q.get('exitCode'),'startedAtUtc':q.get('startedAtUtc'),'finishedAtUtc':q.get('finishedAtUtc')})
 failures=[r['launch']for r in rows if r['exitCode']not in [None,0]]
 summary={'family':a.family,'launchesIndexed':len(rows),'existingPaths':sum(v['exists']for r in rows for v in r['actualPaths']),'failedLaunches':failures,'validationCredit':0,'additionalBehavioralChecks':0,'historicalTreeScanned':False}
 put(output,{'status':'DIAGNOSTIC_RECEIPT_DERIVED_ARTIFACT_INDEX','summary':summary,'launches':rows,'qualification':'Literal current-directory launch paths only; not an acceptance source, no private configuration read, no source or geometry changes.'})
 print(json.dumps(summary,ensure_ascii=False))
if __name__=='__main__':main()
