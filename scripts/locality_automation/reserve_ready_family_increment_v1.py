"""Reserve a ready subset; preserve all held bodies and unchanged source gates."""
import argparse, json, sys
from datetime import datetime, timezone
from pathlib import Path
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.registration_policy_v2 import require_registration
from scripts.locality_automation.native_administrative_policy_v1 import require_native_administration
p=argparse.ArgumentParser(description=__doc__)
for k in ['manifest','early-check','output-prefix']:p.add_argument('--'+k,type=Path,required=True)
p.add_argument('--reviews',nargs='+',type=Path,required=True);a=p.parse_args()
s=read(a.manifest);c=active_control(s);cp=Path(s['control']);before=pin(cp)
key='approvedCycle'+str(c['iteration'])+'AcceptancePool';pool=c[key]
assert s['exactTargets']==pool
rows={r['officialCode']:r for f in a.reviews for r in read(f)['rows']}
assert set(rows)==set(pool)
early=read(a.early_check);assert not early['materialPairs']
held={h['code']:h['hold']for h in early['holds']}
assert set(held)=={code for code in pool if 'error'in rows[code]}
ready=[code for code in pool if code not in held];assert ready and held
assert early['preparedBodies']==len(ready)
for code in ready:
 r=rows[code];assert r['wholeNativeFaceInsidePage'] and r['originalNativePointsMatchSavedInventory']
 require_native_administration(code,r,c)
 for part in r.get('constituentNativeFacts',[r]):require_registration(code,part['sourcePdf'],part['registration'],c)
prefix=str(a.output_prefix)
put(Path(prefix+'-before-control.json'),c)
receipt=put(Path(prefix+'-reservation.json'),{'originalPool':pool,'readyCodes':ready,'held':held,
 'completeScopeCheck':pin(a.early_check.resolve()),'reviews':[pin(f.resolve())for f in a.reviews],
 'noSourceFactsOrHashesChanged':True,'standingGatesUnchanged':True,'heldBodiesNotAccepted':True,
 'sourceOnlyCredit':0,'reservedAtUtc':datetime.now(timezone.utc).isoformat()})
put(Path(prefix+'-manifest.json'),{**s,'exactTargets':ready,'cases':[r for r in s['cases']if r['officialCode']in ready],
 'deferredOriginalScope':receipt})
put(Path(prefix+'-facts.json'),{'rows':[rows[code]for code in ready],'sourceOnlyCredit':0,'reservation':receipt})
c[key]=ready;c['heldCodes']=list(held);c['currentReadyIncrementReservation']=receipt
c['sourceIntakeNextAction']='Install only reserved ready bodies; preserve exact unresolved held entries for a successor.'
temp=cp.parent/(Path(prefix).name+'-control.tmp');put(temp,c);assert pin(cp)==before;temp.replace(cp)
print(json.dumps({'ready':len(ready),'held':held,'credit':0}))
