"""Freeze documented root visual decisions against a complete guarded native scope."""
import argparse, json, sys
from pathlib import Path
R = Path(__file__).resolve().parents[2]; sys.path.insert(0, str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read, checked, active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.registration_policy_v2 import require_registration
from scripts.locality_automation.native_administrative_policy_v1 import require_native_administration

p = argparse.ArgumentParser(description=__doc__)
for name in ['manifest', 'facts', 'early-check', 'configuration', 'output']:
    p.add_argument('--'+name, type=Path, required=True)
a = p.parse_args(); s = read(a.manifest); c = active_control(s); cfg = read(a.configuration)
codes = s['exactTargets']; rows = read(a.facts)['rows']; early = read(a.early_check)
assert codes == c['approvedCycle'+str(c['iteration'])+'AcceptancePool']
assert [r['officialCode'] for r in rows] == codes
assert not early['holds'] and not early['materialPairs'] and early['preparedBodies'] == len(codes)
assert cfg['actualReviewer'] == '/root' and cfg['rootActuallyViewedAllOriginalPerimeters'] is True
assert cfg['viewedNativeParts'] == sum(len(r.get('constituentNativeFacts', [r])) for r in rows)
assert set(cfg['decisionOverrides']) <= set(codes)
for ref in cfg['commonEvidence']: checked(ref)
for row in rows:
    assert 'error' not in row and row['wholeNativeFaceInsidePage'] and row['originalNativePointsMatchSavedInventory']
    require_native_administration(row['officialCode'], row, c)
    for part in row.get('constituentNativeFacts', [row]):
        require_registration(row['officialCode'], part['sourcePdf'], part['registration'], c)
decisions = {code:cfg['defaultDecision'] for code in codes}; decisions.update(cfg['decisionOverrides'])
assert all(text.strip() for text in decisions.values())
put(a.output, {'codes':codes, 'decisions':decisions, 'commonEvidence':cfg['commonEvidence'],
    'qualification':cfg['qualification'], 'legalRole':cfg['legalRole'], 'mapRole':cfg['mapRole']})
print(json.dumps({'rootDecisions':len(codes), 'nativeParts':cfg['viewedNativeParts'], 'sourceOnlyCredit':0}))
