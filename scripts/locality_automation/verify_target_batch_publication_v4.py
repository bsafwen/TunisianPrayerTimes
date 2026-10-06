"""Verify changed bodies and published accounting with a fresh output receipt.

Preserve the v3 verifier and its gates. Allow a named successor receipt for a
second publication check in the same producer directory; never replace proof.
"""
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
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--work',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True);p.add_argument('--family-slug',required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();w=a.work.resolve();e=a.evidence.resolve();slug=a.family_slug
    output=a.output.resolve()
    if output.parent!=w or output.exists():raise ValueError('Fresh receipt inside the existing producer directory required')
    c=read(w.parent/'control.json');s={k:c[k]for k in ['iteration','windowStartUtc','deadlineUtc']};s.update(control=str(w.parent/'control.json'),owner='/root');active_control(s)
    receipt=read(w/(slug+'-incremental-publication-receipt.json'));practical=read(checked(receipt['practicalReceipt']));gps=read(checked(practical['gpsEvidence']));proposal=read(checked(gps['inputs']['proposal']));public=e/'work/locality-progress-dashboard';report=read(public/'task-report.json');m=read(public/'boundary-map.json');batch=next(b for b in m['batches']if b['id']==m['latestBatchId']);bycode={r['code']:r for r in m['locations']}
    for asset,ref in practical['installedAssets'].items():checked(ref)
    current_id='practical-'+receipt['practicalReceipt']['sha256'][:16]
    accepted=next(b for b in m['batches']if b['id']==current_id and not b.get('presentationOnly'))
    if accepted['newLocationCodes']!=receipt['newLocationCodes']or accepted['fullScopeUpgradeCodes']!=receipt['fullScopeUpgradeCodes']:raise ValueError('Current accepted increment highlight accounting differs')
    if batch.get('presentationOnly'):
        ids=batch['sourceBatchIds']
        if len(set(ids))!=len(ids)or current_id not in ids:raise ValueError('Current increment missing from unique presentation sources')
        children=[next(b for b in m['batches']if b['id']==ident and not b.get('presentationOnly'))for ident in ids]
        added=sorted({code for b in children for code in b['newLocationCodes']})
        upgraded=sorted({code for b in children for code in b['fullScopeUpgradeCodes']})
        if added!=batch['newLocationCodes']or upgraded!=batch['fullScopeUpgradeCodes']or set(added)&set(upgraded):raise ValueError('Combined presentation accounting differs')
        visible={r['code']:r for r in batch['locations']}
        for child in children:
            for original in child['locations']:
                shown=visible[original['code']]
                if shown.get('originalAcceptedBatchId')!=child['id']or any(shown[k]!=v for k,v in original.items()):raise ValueError('Presentation does not preserve original accepted row')
    elif batch['id']!=current_id:raise ValueError('Latest visible accepted increment differs')
    for patch in proposal['patches']:
        row=bycode[patch['officialCode']];source=from_wkb(checked(patch['geometry']).read_bytes())
        if row['latestScope']!='full'or not shape(row['geometry']).equals(source)or not row['currentMatchesAccepted']:raise ValueError('Changed map body differs from accepted body')
    at=datetime.fromisoformat(practical['appliedAtUtc']).replace(minute=0,second=0,microsecond=0)
    series=next(r for r in report['cumulativeSeries']if datetime.fromisoformat(r['hour'])==at)
    if series['cumulativeValidatedLocationCount']!=receipt['geographicCount']or series['newValidatedLocationCount']<receipt['newLocationCount']:raise ValueError('Current-hour cumulative graph is stale')
    if report['summary']['uniqueValidatedLocations']!=receipt['geographicCount']or report['summary']['explicitFullSourceBoundaryLocationCount']!=receipt['completeBodyCount']or report['summary']['uniqueInstalledCorrectionLocations']!=receipt['installedCorrectionCount']:raise ValueError('Published count differs')
    family=[r for r in m['locations']if str(r['code'])in c['allFamilyOfficialCodes']]
    put(output,{'status':'VERIFIED_LATEST_TARGET_BODIES_MAP_HIGHLIGHTS_AND_HOUR_GRAPH','changedBodiesCompared':len(proposal['patches']),'newLocationCount':receipt['newLocationCount'],'fullScopeUpgrades':len(receipt['fullScopeUpgradeCodes']),'familyLocationCount':len(family),'familyCompleteBodyCount':sum(r['latestScope']=='full'for r in family),'earlierFamilyRowsReusedWithoutRevalidation':len(family)-len(proposal['patches']),'unrelatedLocationChecks':0,'additionalBehavioralProbes':0,'actualGoogleBrowserRenderingTested':False,'publication':pin(w/(slug+'-incremental-publication-receipt.json')),'helper':pin(Path(__file__))})
    print(json.dumps({'changedBodies':len(proposal['patches']),'familyLocations':len(family),'familyCompleteBodies':sum(r['latestScope']=='full'for r in family),'new':receipt['newLocationCount'],'upgrades':len(receipt['fullScopeUpgradeCodes']),'unrelatedChecks':0,'output':str(output)}))
