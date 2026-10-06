"""Verify that a completed requested family still has its accepted packed bodies.

Read-only status and coordinate comparison; no source revalidation, behavioral
replay, installation, publication refresh or new geographic credit.
"""
import argparse,json,sys
from datetime import datetime,timezone
from pathlib import Path
from shapely.geometry import shape
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked
from scripts.locality_automation.family_work_v3 import validate_identity
from scripts.locality_automation.packed_gps_replay import PackedGpsReplay

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--family',required=True);p.add_argument('--governorate-code',required=True)
    p.add_argument('--governorate-name',required=True)
    p.add_argument('--evidence',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();registry=R/'scripts/neighborhoods/touil-reviews/current-official-sector-registry.json'
    official=validate_identity(a.governorate_code,a.governorate_name,read(registry))
    codes={str(r['sectorCode'])for r in official};public=a.evidence/'work/locality-progress-dashboard'
    model=read(public/'task-report.json');m=read(public/'boundary-map.json')
    progress=model['completedFamilies'][a.family]
    assert progress['completeAcceptedEntries']==progress['officialEntries']==len(codes)and not progress['pendingCodes']
    assert codes<=set(model['summary']['explicitFullSourceBoundaryLocalityCodes'])
    current=R/'android-app/app/src/main/assets';meta=pin(current/'neighborhoods.json');binary=pin(current/'neighborhoods.bin')
    assert m['currentAssets']=={'metadata':meta,'binary':binary}
    replay=PackedGpsReplay(checked(meta),checked(binary));batches={b['id']:b for b in m['batches']}
    locations=[r for r in m['locations']if r['code']in codes]
    assert len(locations)==len(codes)and {r['code']for r in locations}==codes
    for row in locations:
        assert row['latestScope']=='full'and row['currentMatchesAccepted'] is True
        accepted=next(v for v in batches[row['latestBatchId']]['locations']if v['code']==row['code'])
        assert accepted['geometry']==row['geometry']
        assert replay.geometry(row['id']).equals(shape(accepted['geometry']))
    result={'status':'VERIFIED_ALREADY_COMPLETE_FAMILY_CURRENT_ASSETS','checkedAtUtc':datetime.now(timezone.utc).isoformat(),'family':a.family,'officialEntries':len(codes),'unchangedAcceptedBodies':len(locations),'pendingCodes':[],'currentAssets':{'metadata':meta,'binary':binary},'registry':pin(registry),'map':pin(public/'boundary-map.json'),'completionViewId':progress['reviewViewId'],'historicalSourceAndBehavioralReviewsReused':True,'additionalBehavioralTests':0,'newLocationCredit':0,'appAssetsModified':False}
    with a.output.open('x',encoding='utf-8')as f:json.dump(result,f,ensure_ascii=False,indent=2)
    print(json.dumps({k:v for k,v in result.items()if k not in ('registry','map','currentAssets')},ensure_ascii=False))

if __name__=='__main__':main()
