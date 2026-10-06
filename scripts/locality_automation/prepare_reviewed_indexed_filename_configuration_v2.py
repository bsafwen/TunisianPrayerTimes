"""Resolve explicitly reviewed indexed names to unique finite exact filenames.

Parent folder and exact base name/ordinal must match. No fuzzy identity, legal
inference, face assignment, geometry mutation or validation credit.
"""
import argparse,json,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.family_work_v1 import norm
from scripts.locality_automation.family_work_v3 import validate_identity

def resolve(rows,sources,cfg):
    counts=cfg['declaredCircleCounts'];aliases=cfg.get('indexedNameVariants',{});parents=cfg.get('indexedParentVariants',{})
    codes={r['sectorCode']for r in rows}
    if not set(counts)<=codes or not set(aliases)<=codes:raise ValueError('Review outside family')
    result={};used=set()
    for r in rows:
        code=r['sectorCode'];base=aliases.get(code,r['sectorAr']);parent=parents.get(r['delegationAr'],r['delegationAr']);names=[]
        for n in range(1,counts.get(code,1)+1):
            style=cfg.get('indexedCircleFilenameStyles',{}).get(code,'space-every-circle')
            if style not in ['space-every-circle','unmarked-first-parenthesized']:raise ValueError('Unsupported explicit circle filename style')
            suffix=(' '+str(n)if style=='space-every-circle'else(''if n==1 else ' ('+str(n)+')'))if code in counts else ''
            stem=base+suffix
            matches=[v for v in sources if norm(v['delegationPath'])==norm(parent) and norm(v['pdfFilename'].removesuffix('.pdf').split(' - (')[0])==norm(stem)]
            if len(matches)!=1:raise ValueError('Explicit name/ordinal not unique: '+code+' '+stem)
            v=matches[0]
            if v['url']in used:raise ValueError('Repeated source URL')
            used.add(v['url']);names.append(v['pdfFilename'])
        result[code]=names
    if used!={v['url']for v in sources}:raise ValueError('Incomplete indexed source scope')
    return result

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for key in ['manifest','availability','configuration','output']:p.add_argument('--'+key,type=Path,required=True)
    a=p.parse_args();spec=read(a.manifest);c=active_control(spec);cfg=read(a.configuration)
    if not cfg['rootLegalReviewNote']:raise ValueError('Actual root legal review required')
    rows=validate_identity(c['governorateCode'],cfg['governorateName'],read(ROOT/'scripts/neighborhoods/touil-reviews/current-official-sector-registry.json'))
    if {r['sectorCode']for r in rows}!=set(c['allFamilyOfficialCodes']):raise ValueError('Family scope differs')
    names=resolve(rows,read(a.availability)['isieSources'],cfg)
    put(a.output,{**cfg,'indexedFilenames':names,'reviewedConfiguration':pin(a.configuration.resolve()),'availability':pin(a.availability.resolve()),'credit':0})
    print(json.dumps({'exactFilenameBindings':sum(map(len,names.values())),'wholeUnits':len(names),'credit':0}))
