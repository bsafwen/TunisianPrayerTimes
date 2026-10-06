"""Replace only failed finite-scope facts with hash-identical original re-reviews."""
import argparse
from pathlib import Path
import sys

R = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read, pin, checked, active_control
from scripts.locality_automation.audit_reviewed_source_family import put

def main():
    p = argparse.ArgumentParser(description=__doc__)
    for key in ('manifest', 'original', 'resolved', 'output'):
        p.add_argument('--' + key, type=Path, required=True)
    a = p.parse_args()
    spec = read(a.manifest)
    c = active_control(spec)
    codes = c['approvedCycle' + str(c['iteration']) + 'AcceptancePool']
    assert spec['exactTargets'] == codes
    original = read(a.original)['rows']
    resolved = read(a.resolved)['rows']
    assert [r['officialCode'] for r in original] == codes
    replacements = {r['officialCode']: r for r in resolved}
    assert len(replacements) == len(resolved)
    assert set(replacements) == {r['officialCode'] for r in original if 'error' in r}
    cases = {r['officialCode']: r for r in spec['cases']}
    rows = []
    for old in original:
        code = old['officialCode']
        new = replacements.get(code, old)
        assert 'error' not in new
        assert new['sourcePdf'] == old['sourcePdf'] == cases[code]['sourcePdf']
        assert new['sourceInventory'] == cases[code]['sourceInventory']
        for key in ('sourcePdf', 'sourceInventory', 'nativePageGeometry',
                    'sourceMetricGeometry', 'rawSourceGeometry', 'geometry', 'originalRender'):
            checked(new[key])
        rows.append(new)
    put(a.output, {
        'status': 'SOURCE_FACTS_PREPARED_REQUIRES_VISUAL_ROLE_NEIGHBOR_REVIEW',
        'rows': rows, 'inputs': [pin(a.original.resolve()), pin(a.resolved.resolve())],
        'replacedFailedCodes': list(replacements), 'originalFailedAttemptsPreserved': True,
        'sourceActor': '/root', 'independentAgentReviewClaimed': False, 'credit': 0,
    })
    print({'preparedBodies': len(rows), 'resolvedHolds': len(replacements), 'credit': 0})

if __name__ == '__main__':
    main()
