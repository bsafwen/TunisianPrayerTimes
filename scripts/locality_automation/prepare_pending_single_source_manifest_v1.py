"""Reuse a complete native context and select only pending single-circle units.

No geometry, source inventory, identity, gate or acceptance change. Keep the
original all-family context pinned and retain already accepted units as context.
"""
import argparse,json,sys
from pathlib import Path
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,active_control
from scripts.locality_automation.audit_reviewed_source_family import put

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--manifest',type=Path,required=True)
p.add_argument('--output',type=Path,required=True)
a=p.parse_args();s=read(a.manifest);c=active_control(s)
pool=c['approvedCycle'+str(c['iteration'])+'AcceptancePool']
if s['exactTargets']!=pool:raise ValueError('Exact current pending scope required')
bycode={q['officialCode']:q for q in s['cases']}
if len(bycode)!=len(s['cases'])or set(bycode)!=set(c['allFamilyOfficialCodes']):
    raise ValueError('Complete unique single-circle context required')
if any(q.get('declaredCircleIds')for q in s['cases']):
    raise ValueError('This selector only handles actually reviewed single-circle units')
selected=[bycode[code]for code in pool]
if any(q.get('sourceContextOnly')or 'appId'not in q for q in selected):
    raise ValueError('Pending units must have explicit app bindings')
prior=sorted(set(bycode)-set(pool))
if any(not bycode[code].get('sourceContextOnly')for code in prior):
    raise ValueError('Prior complete units must remain context only')
put(a.output.resolve(),{**s,'cases':selected,'completeOriginalContext':pin(a.manifest.resolve()),'previouslyAcceptedContextCodes':prior,'credit':0})
print(json.dumps({'pendingUnits':len(selected),'reusedContextUnits':len(prior),'credit':0}))
