"""Launch the prepared changed-location JVM command directly under the original guard.

One supervised Java leaf, unchanged cutoff and exclusive receipt; no shell,
detached descendants, recompilation, unrelated coordinates or acceptance.
"""
import argparse,json,sys
from pathlib import Path
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation import work_window_guard as guard

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--work',type=Path,required=True)
    p.add_argument('--family',required=True)
    a=p.parse_args();w=a.work.resolve()
    if Path.cwd().resolve()!=w:raise ValueError('Existing producer cwd required')
    c=read(w.parent/'control.json')
    s={k:c[k]for k in ['iteration','windowStartUtc','deadlineUtc']}
    s.update(control=str(w.parent/'control.json'),owner='/root');active_control(s)
    if a.family!=c['familySlug']:raise ValueError('Current finite family differs')
    binding=read(w/(a.family+'-minimum-audit-v1/jvm-binding.json'))
    argv=binding['argv']
    out=w/(a.family+'-minimum-audit-v1/actual-jvm.json')
    if not isinstance(argv,list)or any(type(v)is not str for v in argv):raise ValueError('Literal prepared argv required')
    if Path(argv[0]).name.lower()!='java.exe' or not Path(argv[0]).is_file() or argv[1]!='-cp':raise ValueError('Prepared Java leaf required')
    if Path(argv[-1]).resolve()!=out or out.exists():raise ValueError('Exclusive prepared result required')
    for ref in binding['retainedClasses']+binding['retainedRuntimeJars']+[binding['sourceHarness'],binding['preparedInputs']]:checked(ref)
    for ref in binding['currentAssets'].values():checked(ref)
    for entry in binding['currentCoreBindings']:
        if checked(entry['snapshot']).read_bytes()!=checked(entry['current']).read_bytes():raise ValueError('Compiled Kotlin core differs')
    guard_ref={'file':str(Path(guard.__file__).resolve()),'sha256':'d2fe7a733097536d022bcb5e8ebd5b0688bf1bb6adcd955d659fce052a226ade'}
    checked(guard_ref)
    receipt=w/(a.family+'-java-after.execution.json')
    result=guard.launch_guarded(c['safeSourceQaStartUtc'],c['deadlineUtc'],argv,120,receipt)
    checked(guard_ref)
    print(json.dumps({k:v for k,v in result.items()if k not in ['argv','recordOwner']}))
    return 0 if result['status']=='COMPLETED'and result.get('exitCode')==0 else 1
if __name__=='__main__':raise SystemExit(main())

