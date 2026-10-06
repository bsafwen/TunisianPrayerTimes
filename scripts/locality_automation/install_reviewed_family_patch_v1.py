"""Require pinned retained-family incident checks before the existing installer.

The historical installer and every source/GPS/hash gate remain unchanged.
"""
import argparse,json,sys
from pathlib import Path
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.install_reviewed_boundary_patch import apply,check_tree

def preflight(w,family,incident):
    c=read(w.parent/'control.json')
    s={k:c[k]for k in ('iteration','windowStartUtc','deadlineUtc')}
    s.update(control=str(w.parent/'control.json'),owner='/root');active_control(s)
    assert c['familySlug']==family
    proposal=read(w/(family+'-proposal.json'))
    assert [r['officialCode']for r in proposal['patches']]==c['approvedCycle'+str(c['iteration'])+'AcceptancePool']
    review=read(checked(proposal['rootReview']))
    report=read(incident);check_tree(report)
    expected=[pin(w/('before-'+family+'-publication-boundary-map.json')),pin(w/'window-baseline.json'),review['nativeSourceReview'],pin(w/(family+'-stage-v1/stage-report.json'))]
    if report['status']!='PASS_PINNED_RETAINED_FAMILY_INCIDENTS'or report['materialPairs']or report['inputs']!=expected:
        raise ValueError('Exact retained-family incident evidence is required before installation')
    assert report['historicalSourceAndGpsGatesReused'] is True and report['credit']==0
    assert all(r['overlapFractionSmaller']<=.01 for r in report['pairs'])
    return {'status':'PASS_REQUIRED_FAMILY_PREINSTALL_EVIDENCE','incidentEvidence':pin(incident),'inputs':expected,'incidentComparisons':len(report['pairs']),'existingInstallerUnchanged':pin(Path(__import__('scripts.locality_automation.install_reviewed_boundary_patch',fromlist=['x']).__file__)),'additionalGpsChecks':0,'credit':0}

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--family',required=True);p.add_argument('--work',type=Path,required=True)
    p.add_argument('--incident-report',type=Path)
    p.add_argument('--preflight-only',action='store_true')
    p.add_argument('--preflight-receipt',type=Path,required=True)
    for key in ('gps','run','handoff','output','transaction'):
        p.add_argument('--'+key,type=Path,required=True)
    a=p.parse_args();w=a.work.resolve();assert Path.cwd().resolve()==w
    incident=a.incident_report.resolve()if a.incident_report else w/(a.family+'-retained-incidents-v1.json')
    result=preflight(w,a.family,incident)
    put(a.preflight_receipt,result)
    if a.preflight_only:print(json.dumps(result));return
    apply(a)

if __name__=='__main__':main()
