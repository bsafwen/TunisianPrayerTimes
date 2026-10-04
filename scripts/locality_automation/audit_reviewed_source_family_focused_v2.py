"""Sfax-only behavioral replay with full structural preservation.

User-directed successor to the oversized family suite. Exact whole native/source
decisions and every unaffected physical body stay protected; unrelated locations
receive no new behavioral probe cohort. Original lookup/oracle functions remain.
"""
import argparse
import math
from pathlib import Path
import sys
from pyproj import Transformer
from shapely import from_wkb
from shapely.geometry import Point
from shapely.ops import transform

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT))
from scripts.locality_automation.audit_reviewed_source_family import put,validate,numeric
from scripts.locality_automation.run_sealed_boundary_queue import read,checked,pin
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree
from scripts.locality_automation.run_solo_source_gps_audit import nearest_source


def prepare(spec,manifest,output):
    proposal,stage,before,after=validate(spec)
    previous=read(checked(spec['previousPreparedInputs']));check_tree(previous)
    old_gate=read(checked(previous['inputGate']));check_tree(old_gate)
    if old_gate['stage']!=spec['stage'] or len(old_gate['protectedBodies'])!=325 or len(old_gate['allUnaffectedPackedBodies'])!=2559:
        raise ValueError('Complete structural/source protection not retained')
    changed={p['id']for p in proposal['patches']}
    changed_indexes={i for i,r in before.boundaries.items()if r['id']in changed}
    def invariant_cells(replay):
        return {k:tuple(i for i in v if i not in changed_indexes)for k,v in replay.cells.items()if any(i not in changed_indexes for i in v)}
    if invariant_cells(before)!=invariant_cells(after) or before.grid_size!=after.grid_size or before.scale!=after.scale:
        raise ValueError('Unchanged catalogue grid membership/order differs')
    foreign=lambda r:tuple(pair for pair in r.conflicts if not changed_indexes.intersection(pair))
    if foreign(before)!=foreign(after) or before.priority!=after.priority or before._country!=after._country:
        raise ValueError('Outside conflict policy or country differs')
    tm=Transformer.from_crs(4326,32632,always_xy=True).transform
    ll=Transformer.from_crs(32632,4326,always_xy=True).transform
    points=[];seen=set();coverage={}
    def add_xy(x,y,kind,code=None):
        lon,lat=ll(x,y);key=(lat,lon)
        if key in seen:return
        seen.add(key);points.append({'lat':lat,'lng':lon,'sourceClass':kind,'controlId':None,'controlCode':code,'sourceProvenance':[]})
    for patch in proposal['patches']:
        ident,code=patch['id'],patch['officialCode']
        current=after.geometry(ident);metric=transform(tm,current);old=transform(tm,before.geometry(ident))
        row=before.by_id[ident];p=transform(tm,Point(row['lng'],row['lat']))
        start=len(points);add_xy(p.x,p.y,'sfax-saved-selector',code)
        p=metric.representative_point();add_xy(p.x,p.y,'sfax-source-interior',code)
        left,bottom,right,top=metric.bounds
        for ix in range(4):
            for iy in range(4):
                p=Point(left+(ix+.5)*(right-left)/4,bottom+(iy+.5)*(top-bottom)/4)
                if metric.contains(p):add_xy(p.x,p.y,'sfax-interior-grid',code)
        segments=[]
        for poly in metric.geoms:
            for ring in [poly.exterior,*poly.interiors]:
                coords=list(ring.coords)
                for a,b in zip(coords,coords[1:]):
                    length=math.hypot(b[0]-a[0],b[1]-a[1])
                    if length:segments.append((length,a,b))
        # Fixed budget per locality, supplementing full vertex/clip/source checks
        # rather than repeating three nearly identical physical edge suites.
        for length,(x,y),(xx,yy) in sorted(segments,reverse=True)[:16]:
            nx,ny=-(yy-y)/length,(xx-x)/length;mx,my=(x+xx)/2,(y+yy)/2
            for distance in (0,-55,-25,-7,-3,-.5,.5,3,7,25,55):
                add_xy(mx+nx*distance,my+ny*distance,'sfax-major-edge-both-sides',code)
        for region,label in [(metric.difference(old),'sfax-added-region'),(old.difference(metric),'sfax-removed-region')]:
            parts=sorted([p for p in getattr(region,'geoms',[region])if p.geom_type=='Polygon'and not p.is_empty],key=lambda p:p.area,reverse=True)
            for poly in parts[:8]:
                p=poly.representative_point();add_xy(p.x,p.y,label,code)
        coverage[code]={'identity':ident,'probeCount':len(points)-start,'majorSegments':min(16,len(segments))}
    for conflict in read(checked(stage['stagedMetadata']))['conflicts']:
        if not changed.intersection(conflict['ids']):continue
        sample=conflict['sample'];p=transform(tm,Point(sample['lng'],sample['lat']))
        add_xy(p.x,p.y,'sfax-actual-conflict-sample')
        a,b=(transform(tm,after.geometry(i))for i in conflict['ids'])
        overlap=a.intersection(b)
        if not overlap.is_empty:
            p=overlap.representative_point();add_xy(p.x,p.y,'sfax-actual-overlap-interior')
    params=read(checked(spec['prayerParams']))
    eligible=[d for g in read(checked(spec['governorates']))['gouvernorats']for d in g['delegations']if str(d['id'])in params]
    if len(eligible)!=258:raise ValueError('Prayer source eligibility differs')
    for i,p in enumerate(points):
        p['id']=p['name']='sfax-focused-'+str(i).zfill(5)
        p['expectedSourceId']=nearest_source(eligible,p['lat'],p['lng'])
    output.mkdir()
    probes=put(output/'probes.json',{'probes':points})
    requests=put(output/'actual-jvm-requests.json',[{k:p[k]for k in ['name','lat','lng','expectedSourceId']}for p in points])
    gate={**old_gate,'manifest':pin(manifest),'previousStructuralGate':previous['inputGate'],'probes':probes,'requests':requests,
          'behavioralScope':'Sfax twelve targets, actual incident conflicts and changed regions only',
          'unrelatedFreshBehavioralProbeCount':0,'perLocationCoverage':coverage,
          'unchangedGridMembershipOrderConflictPolicyAndCountryVerified':True}
    gate_ref=put(output/'input-gate.json',gate)
    prepared={**previous,'inputGate':gate_ref,'probes':probes,'actualJvmRequests':requests,'probeCount':len(points),
              'behavioralScope':gate['behavioralScope'],'unrelatedFreshBehavioralProbeCount':0}
    put(output/'prepared-jvm-inputs.json',prepared)
    print({'phase':'focused-prepared','probes':len(points),'locations':12,'unrelatedBehavioralProbes':0,'structurallyProtectedBodies':325})


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--phase',choices=['prepare','numeric'],required=True)
    args=parser.parse_args();spec=read(args.manifest)
    return prepare(spec,args.manifest,args.output)if args.phase=='prepare'else numeric(spec,args.manifest,args.output)


if __name__=='__main__':raise SystemExit(main())
