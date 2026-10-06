"""Check only changed-body intersections with pinned retained full family bodies.

Historical acceptance schemas differ; use the immutable raw/adopted references
already exposed by their accepted dashboard entries, never guess scope fields.
"""
import argparse,json,sys
from pathlib import Path
from shapely import from_wkb
from shapely.ops import transform
from pyproj import Transformer
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,checked,pin,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.packed_gps_replay import PackedGpsReplay

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for key in ('manifest','baseline','before-map','facts','stage','output'):
        p.add_argument('--'+key,type=Path,required=True)
    a=p.parse_args();s=read(a.manifest);c=active_control(s);b=read(a.baseline)
    codes=s['exactTargets'];assert codes==c['approvedCycle'+str(c['iteration'])+'AcceptancePool']
    m=read(a.before_map);stage=read(a.stage)
    assert m['currentAssets']['metadata']['sha256']==stage['beforeMetadata']['sha256']
    assert m['currentAssets']['binary']['sha256']==stage['beforeBinary']['sha256']
    old=PackedGpsReplay(checked(stage['beforeMetadata']),checked(stage['beforeBinary']))
    after=PackedGpsReplay(checked(stage['stagedMetadata']),checked(stage['stagedBinary']))
    prior=b['alreadyCompleteFamilyCodes'];rows=[r for r in m['locations']if r['code']in prior]
    assert set(r['code']for r in rows)==set(prior)and len(rows)==len(prior)
    to_m=Transformer.from_crs(4326,32632,always_xy=True).transform
    targets={r['officialCode']:from_wkb(checked(r['sourceMetricGeometry']).read_bytes())for r in read(a.facts)['rows']}
    assert set(targets)==set(codes)
    pairs=[];refs=[]
    for r in rows:
        assert r['latestScope']=='full'and r['currentMatchesAccepted'] is True
        e=r['sourceGeometryEvidence'];checked(e['scopeEvidence'])
        raw=from_wkb(checked(e['rawSourceGeometry']).read_bytes())
        grid=from_wkb(checked(e['expectedAdoptedGeometry']).read_bytes())
        assert grid.equals(old.geometry(r['id']))and grid.equals(after.geometry(r['id']))
        assert e['rawGeometryCrs']=='EPSG:4326'and e['adoptedGeometryCrs']=='EPSG:4326'
        h=transform(to_m,raw)
        for code,g in targets.items():
            if not g.envelope.buffer(10).intersects(h.envelope)or g.distance(h)>10:continue
            area=g.intersection(h).area
            pairs.append({'target':code,'retained':r['code'],'overlapM2':area,'overlapFractionSmaller':area/min(g.area,h.area),'gapM':g.distance(h)})
            refs.append(e['scopeEvidence'])
    material=[r for r in pairs if r['overlapFractionSmaller']>.01]
    put(a.output,{'status':'PASS_PINNED_RETAINED_FAMILY_INCIDENTS'if not material else'HOLD_MATERIAL_RETAINED_SOURCE_OVERLAP','inputs':[pin(a.before_map.resolve()),pin(a.baseline.resolve()),pin(a.facts.resolve()),pin(a.stage.resolve())],'acceptedNeighborEvidence':refs,'pairs':pairs,'materialPairs':material,'historicalSourceAndGpsGatesReused':True,'unrelatedLocationTests':0,'credit':0})
    print(json.dumps({'incidentComparisons':len(pairs),'materialPairs':material,'credit':0}))
    return bool(material)

if __name__=='__main__':raise SystemExit(main())
