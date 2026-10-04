"""Verify only the latest changed bodies and their published accounting."""
import argparse,json,sys
from datetime import datetime
from pathlib import Path
from shapely import from_wkb
from shapely.geometry import shape
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import read,checked,active_control
from scripts.locality_automation.stage_boundary_patch import pin
from scripts.locality_automation.audit_reviewed_source_family import put
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--work',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True);p.add_argument('--family-slug',required=True);a=p.parse_args();w=a.work.resolve();e=a.evidence.resolve();slug=a.family_slug
    c=read(w.parent/'control.json');s={k:c[k]for k in ['iteration','windowStartUtc','deadlineUtc']};s.update(control=str(w.parent/'control.json'),owner='/root');active_control(s)
    receipt=read(w/(slug+'-incremental-publication-receipt.json'));practical=read(checked(receipt['practicalReceipt']));gps=read(checked(practical['gpsEvidence']));proposal=read(checked(gps['inputs']['proposal']));public=e/'work/locality-progress-dashboard';report=read(public/'task-report.json');m=read(public/'boundary-map.json');batch=next(b for b in m['batches']if b['id']==m['latestBatchId']);bycode={r['code']:r for r in m['locations']}
    for asset,ref in practical['installedAssets'].items():checked(ref)
    if batch['newLocationCodes']!=receipt['newLocationCodes']or batch['fullScopeUpgradeCodes']!=receipt['fullScopeUpgradeCodes']:raise ValueError('Latest visible highlight accounting differs')
    for patch in proposal['patches']:
        row=bycode[patch['officialCode']];source=from_wkb(checked(patch['geometry']).read_bytes())
        if row['latestScope']!='full'or not shape(row['geometry']).equals(source)or not row['currentMatchesAccepted']:raise ValueError('Changed map body differs from accepted body')
    at=datetime.fromisoformat(practical['appliedAtUtc']).replace(minute=0,second=0,microsecond=0)
    series=next(r for r in report['cumulativeSeries']if datetime.fromisoformat(r['hour'])==at)
    if series['cumulativeValidatedLocationCount']!=receipt['geographicCount']or series['newValidatedLocationCount']<receipt['newLocationCount']:raise ValueError('Current-hour cumulative graph is stale')
    if report['summary']['uniqueValidatedLocations']!=receipt['geographicCount']or report['summary']['explicitFullSourceBoundaryLocationCount']!=receipt['completeBodyCount']or report['summary']['uniqueInstalledCorrectionLocations']!=receipt['installedCorrectionCount']:raise ValueError('Published count differs')
    family=[r for r in m['locations']if str(r['code'])in c['allFamilyOfficialCodes']]
    put(w/(slug+'-publication-verification.json'),{'status':'VERIFIED_LATEST_TARGET_BODIES_MAP_HIGHLIGHTS_AND_HOUR_GRAPH','changedBodiesCompared':len(proposal['patches']),'newLocationCount':receipt['newLocationCount'],'fullScopeUpgrades':len(receipt['fullScopeUpgradeCodes']),'familyLocationCount':len(family),'familyCompleteBodyCount':sum(r['latestScope']=='full'for r in family),'earlierFamilyRowsReusedWithoutRevalidation':len(family)-len(proposal['patches']),'unrelatedLocationChecks':0,'additionalBehavioralProbes':0,'actualGoogleBrowserRenderingTested':False,'publication':pin(w/(slug+'-incremental-publication-receipt.json'))})
    print(json.dumps({'changedBodies':len(proposal['patches']),'familyLocations':len(family),'familyCompleteBodies':sum(r['latestScope']=='full'for r in family),'new':receipt['newLocationCount'],'upgrades':len(receipt['fullScopeUpgradeCodes']),'unrelatedChecks':0}))
