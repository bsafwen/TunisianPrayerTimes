"""Partition an already pinned complete native inventory without re-extraction.

Names come from current INS identity, circle membership from the root-reviewed
legal roster. This helper never assigns a polygon, changes a gate or earns credit.
"""
import argparse,json,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put

def partition(spec,roster,registry):
    pool=spec['exactTargets']
    if len(pool)!=len(set(pool)):raise ValueError('Duplicate exact target')
    official={str(r['sectorCode']):r for r in registry['sectors']}
    groups=roster['groups'];multi={g['officialCode']:g for g in groups}
    if len(multi)!=len(groups):raise ValueError('Duplicate declared circle group')
    seen=set();single=[];circle=[];identities={}
    for row in spec['cases']:
        code=row['officialCode'];ins=official[code]
        if row['officialName']!=ins['sectorAr']or row['officialParent']!=ins['delegationAr']:
            raise ValueError('Code/name/parent differs from current official identity: '+code)
        key=(code,row['sourcePdf']['sha256'])
        if key in seen:raise ValueError('Duplicate original for one unit')
        seen.add(key)
        identities[code]={'officialCode':code,'officialName':ins['sectorAr'],'officialParent':ins['delegationAr']}
        if row.get('sourceContextOnly'):
            if code in pool:raise ValueError('Pending target incorrectly marked reused')
            continue
        if code not in pool:raise ValueError('Acceptance source outside exact pool')
        if code in multi:
            ids=row.get('declaredCircleIds',[])
            matches=[q for q in multi[code]['components']if [q['circle']]==ids and q['sourcePdf']==row['sourcePdf']and q['sourceUrl']==row['sourceUrl']]
            if len(matches)!=1:raise ValueError('Exact declared circle/source binding differs: '+code)
            circle.append(row)
        else:
            if row.get('declaredCircleIds'):raise ValueError('Unexpected numbered circle')
            single.append(row)
    if set(r['officialCode']for r in single+circle)!=set(pool):raise ValueError('Incomplete exact target coverage')
    if len(single)!=len({r['officialCode']for r in single}):raise ValueError('Single body has multiple originals')
    for code,g in multi.items():
        if code not in pool:continue
        parts=[r for r in circle if r['officialCode']==code]
        ids=[r['declaredCircleIds'][0]for r in parts]
        if len(parts)!=g['expectedCircleCount']or sorted(ids)!=list(range(1,g['expectedCircleCount']+1)):
            raise ValueError('Incomplete numbered-circle group: '+code)
    return single,circle,[identities[c]for c in sorted(identities)]

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for key in ['manifest','roster','registry','output-prefix']:p.add_argument('--'+key,type=Path,required=True)
    a=p.parse_args();spec=read(a.manifest);active_control(spec)
    for row in spec['cases']:
        checked(row['sourcePdf']);checked(row['sourceInventory'])
    roster=read(a.roster)
    for ref in roster['legalPages']:checked(ref)
    single,circle,identities=partition(spec,roster,read(a.registry));prefix=str(a.output_prefix)
    outputs={}
    for kind,rows in [('single',single),('circle',circle)]:
        outputs[kind]=put(Path(prefix+'-'+kind+'.json'),{**spec,'cases':rows,'credit':0,'completeNeighborContext':pin(a.manifest.resolve())})
    receipt=put(Path(prefix+'-receipt.json'),{'status':'EXACT_EXISTING_NATIVE_CONTEXT_PARTITIONED','singleOriginals':len(single),'circleOriginals':len(circle),'identityIndex':identities,'manifest':pin(a.manifest.resolve()),'rootReviewedRoster':pin(a.roster.resolve()),'officialRegistry':pin(a.registry.resolve()),'outputs':outputs,'nativeInventoryReextractions':0,'newBehaviorChecks':0,'credit':0})
    print(json.dumps({'singleOriginals':len(single),'circleOriginals':len(circle),'receipt':receipt,'credit':0}))

if __name__=='__main__':main()
