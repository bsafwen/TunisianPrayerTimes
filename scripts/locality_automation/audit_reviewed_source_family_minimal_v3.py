"""Sfax-only behavioral replay with full structural preservation.

User-directed minimum successor to the oversized family suite. Exact whole native/source
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
        start=len(points)
        p=metric.representative_point();add_xy(p.x,p.y,'sfax-source-interior',code)
        for region,label in [(metric.difference(old),'sfax-largest-added-region'),(old.difference(metric),'sfax-largest-removed-region')]:
            parts=[p for p in getattr(region,'geoms',[region])if p.geom_type=='Polygon'and not p.is_empty]
            if parts:
                p=max(parts,key=lambda p:p.area).representative_point();add_xy(p.x,p.y,label,code)
        coverage[code]={'identity':ident,'probeCount':len(points)-start}
    # Only the largest incident overlap in each distinct conflict class.
    # Source topology and every unchanged body/grid/conflict remain checked above.
    classes={}
    for conflict in read(checked(stage['stagedMetadata']))['conflicts']:
        ids=set(conflict['ids'])
        if not changed.intersection(ids):continue
        a,b=(transform(tm,after.geometry(i))for i in conflict['ids'])
        overlap=a.intersection(b)
        if overlap.is_empty:continue
        kind='sfax-internal-source-seam'if ids.issubset(changed)else'sfax-outside-opposing-claim'
        if kind not in classes or overlap.area>classes[kind].area:classes[kind]=overlap
    for kind,overlap in classes.items():
        p=overlap.representative_point();add_xy(p.x,p.y,kind)
    params=read(checked(spec['prayerParams']))
    eligible=[d for g in read(checked(spec['governorates']))['gouvernorats']for d in g['delegations']if str(d['id'])in params]
    if len(eligible)!=258:raise ValueError('Prayer source eligibility differs')
    for i,p in enumerate(points):
        p['id']=p['name']='sfax-minimal-'+str(i).zfill(5)
        p['expectedSourceId']=nearest_source(eligible,p['lat'],p['lng'])
    output.mkdir()
    probes=put(output/'probes.json',{'probes':points})
    requests=put(output/'actual-jvm-requests.json',[{k:p[k]for k in ['name','lat','lng','expectedSourceId']}for p in points])
    gate={**old_gate,'manifest':pin(manifest),'previousStructuralGate':previous['inputGate'],'probes':probes,'requests':requests,
          'behavioralScope':'Minimum Sfax scope: one interior and largest added/removed region per target, largest overlap per conflict class',
          'unrelatedFreshBehavioralProbeCount':0,'perLocationCoverage':coverage,
          'unchangedGridMembershipOrderConflictPolicyAndCountryVerified':True}
    gate_ref=put(output/'input-gate.json',gate)
    prepared={**previous,'inputGate':gate_ref,'probes':probes,'actualJvmRequests':requests,'probeCount':len(points),
              'behavioralScope':gate['behavioralScope'],'unrelatedFreshBehavioralProbeCount':0}
    put(output/'prepared-jvm-inputs.json',prepared)
    print({'phase':'minimal-prepared','probes':len(points),'locations':12,'unrelatedBehavioralProbes':0,'structurallyProtectedBodies':325})


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--phase',choices=['prepare','numeric'],required=True)
    args=parser.parse_args();spec=read(args.manifest)
    return prepare(spec,args.manifest,args.output)if args.phase=='prepare'else numeric(spec,args.manifest,args.output)


if __name__=='__main__':raise SystemExit(main())
