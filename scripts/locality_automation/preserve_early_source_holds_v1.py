"""Carry early geometry holds into a successor facts file; never alter accepted facts."""
import argparse, json, sys
from pathlib import Path
R = Path(__file__).resolve().parents[2]; sys.path.insert(0, str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read, pin, active_control
from scripts.locality_automation.audit_reviewed_source_family import put

p = argparse.ArgumentParser(description=__doc__)
for k in ['manifest', 'early-check', 'output']:
    p.add_argument('--'+k, type=Path, required=True)
p.add_argument('--reviews', type=Path, nargs='+', required=True)
a = p.parse_args(); s = read(a.manifest); c = active_control(s)
codes = s['exactTargets']; assert codes == c['approvedCycle'+str(c['iteration'])+'AcceptancePool']
rows = {r['officialCode']: r for f in a.reviews for r in read(f)['rows']}
assert set(rows) == set(codes)
early = read(a.early_check); assert not early['materialPairs']
holds = {h['code']: h for h in early['holds']}; assert set(holds) <= set(codes)
assert {k for k,v in rows.items() if 'error' in v} <= set(holds)
out = []
for code in codes:
    row = rows[code]
    if code in holds and 'error' not in row:
        row = {'officialCode': code, 'error': holds[code].get('hold', holds[code].get('reason')),
               'rejectedOriginalNativeFacts': row, 'originalEarlyHold': holds[code],
               'sourceScopeAccepted': False, 'credit': 0}
        assert row['error']
    out.append(row)
assert all(out[i] == rows[k] for i,k in enumerate(codes) if k not in holds)
put(a.output, {'rows': out, 'originalReviews': [pin(f.resolve()) for f in a.reviews],
               'originalCompleteScopeCheck': pin(a.early_check.resolve()),
               'standingGatesUnchanged': True, 'sourceOnlyCredit': 0})
print(json.dumps({'ready': len(codes)-len(holds), 'preservedHolds': list(holds), 'credit': 0}))
