"""Collect finite unaccepted source previews and prepare explicitly held registration plans."""
import argparse, json, sys
from pathlib import Path
R = Path(__file__).resolve().parents[2]; sys.path.insert(0, str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read, pin, checked, active_control
from scripts.locality_automation.audit_reviewed_source_family import put

p = argparse.ArgumentParser(description=__doc__)
for key in ['manifest', 'diagnostics', 'configuration', 'output-prefix']:
    p.add_argument('--'+key, type=Path, required=True)
p.add_argument('--reviews', nargs='+', type=Path, required=True)
a = p.parse_args(); s = read(a.manifest); c = active_control(s); cfg = read(a.configuration)
assert s['exactTargets'] == c['approvedCycle'+str(c['iteration'])+'AcceptancePool']
rows = {r['officialCode']:r for f in a.reviews for r in read(f)['rows']}
assert set(rows) == set(s['exactTargets']), 'Complete finite scope required'
good = [rows[code] for code in s['exactTargets'] if 'error' not in rows[code]]
prefix = str(a.output_prefix)
put(Path(prefix+'-view-facts.json'), {'rows':good, 'sourceScopeAccepted':False,
    'sourceOnlyCredit':0, 'inputReviews':[pin(f.resolve()) for f in a.reviews]})
put(Path(prefix+'-contact-codes.json'), {'codes':[r['officialCode'] for r in good if not r.get('constituentNativeFacts')]})
cases = []; diagnostics = read(a.diagnostics)['candidates']
for item in cfg['registrationHolds']:
    code = item['officialCode']; assert code in rows and 'error' in rows[code]
    assert 'held-out controls held' in rows[code]['error'], 'Only standing registration holds'
    original = [q for q in s['cases'] if q['officialCode'] == code]; assert len(original) == 1
    q = original[0]
    selected = [g for g in diagnostics if g['code'] == code and g['layer'] == 'red' and g['index'] == item['redFaceIndex']]
    assert len(selected) == 1 and selected[0]['wholeInsidePage']
    g = selected[0]; checked(g['pageGeometry']); checked(g['render'])
    checked(item['GoogleObservation']['image'])
    cases.append({'officialCode':code, 'name':q['officialName'],
        'components':[{'sourcePdf':q['sourcePdf'], 'sourceInventory':q['sourceInventory'],
        'sourceUrl':q['sourceUrl'], 'nativeFace':g['pageGeometry'], 'circle':1}],
        'requestedEntryOnlyLimits':item['requestedEntryOnlyLimits'], 'GoogleObservation':item['GoogleObservation']})
for ref in cfg['neighborSearchReports']: checked(ref)
plan = {**{k:v for k,v in s.items() if k!='cases'}, 'familyName':cfg['familyName'], 'cases':cases,
    'neighborSearchReports':cfg['neighborSearchReports'], 'neighborReviewDescription':cfg['neighborReviewDescription']}
put(Path(prefix+'-registration-plan.json'), plan)
print(json.dumps({'completeSourceCandidates':len(good), 'pendingRegistration':len(cases), 'credit':0}))
