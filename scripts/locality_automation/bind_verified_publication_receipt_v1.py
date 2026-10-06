"""Bind an existing verified publication receipt to the continuation's canonical name."""
import argparse,json,sys
from pathlib import Path
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--input',type=Path,required=True);p.add_argument('--family',required=True)
a=p.parse_args();w=Path.cwd();c=read(w.parent/'control.json')
s={k:c[k]for k in ['iteration','windowStartUtc','deadlineUtc']};s.update(control=str(w.parent/'control.json'),owner='/root');active_control(s)
assert a.family==c['familySlug']
q=read(a.input);assert q['status']=='VERIFIED_LATEST_TARGET_BODIES_MAP_HIGHLIGHTS_AND_HOUR_GRAPH'
checked(q['publication']);assert q['publication']==pin(w/(a.family+'-incremental-publication-receipt.json'))
output=w/(a.family+'-publication-verification.json')
with output.open('xb')as f:f.write(a.input.read_bytes())
assert output.read_bytes()==a.input.read_bytes()
put(w/(a.family+'-publication-canonical-binding.json'),{'originalVerification':pin(a.input.resolve()),'canonicalIdenticalReceipt':pin(output),'additionalBehavioralChecks':0,'credit':0})
print(json.dumps({'canonicalVerifiedReceipt':str(output),'additionalChecks':0,'credit':0}))
