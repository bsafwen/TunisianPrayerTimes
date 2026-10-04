"""Launch the exact pinned JVM argv directly under the original UTC guard."""
import argparse,json,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import read,checked,active_control
from scripts.locality_automation.work_window_guard import launch_guarded
from scripts.locality_automation.guarded_control_phase import GUARD_SHA256
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--control',type=Path,required=True);p.add_argument('--binding',type=Path,required=True);p.add_argument('--record',type=Path,required=True);a=p.parse_args()
    c=read(a.control);s={k:c[k]for k in ['iteration','windowStartUtc','deadlineUtc']};s.update(control=str(a.control.resolve()),owner='/root');active_control(s)
    checked({'file':str(ROOT/'scripts/locality_automation/work_window_guard.py'),'sha256':GUARD_SHA256});b=read(a.binding)
    for ref in b['retainedClasses']+b['retainedRuntimeJars']+[b['sourceHarness']]+list(b['currentAssets'].values()):checked(ref)
    r=launch_guarded(c['safeSourceQaStartUtc'],c['deadlineUtc'],b['argv'],120,a.record);print(json.dumps(r));raise SystemExit(0 if r['status']=='COMPLETED'and r['exitCode']==0 else 1)
