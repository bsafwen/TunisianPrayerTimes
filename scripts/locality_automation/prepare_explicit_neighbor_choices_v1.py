"""Pin exact neighboring original faces for unchanged native-source review.

Choices are diagnostic candidates until root visually reviews adjoining sheets.
Original failed own sheets remain pinned; no acceptance or tolerance changes.
"""
import argparse,json,sys
from pathlib import Path
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for key in ['manifest','configuration','output']:p.add_argument('--'+key,type=Path,required=True)
    a=p.parse_args();s=read(a.manifest);c=active_control(s);cfg=read(a.configuration);cases=[]
    if s['exactTargets']!=c['approvedCycle'+str(c['iteration'])+'AcceptancePool']:raise ValueError('Exact finite scope differs')
    for choice in cfg['choices']:
        code=choice['target'];originals=[q for q in s['cases']if q['officialCode']==code]
        if code not in s['exactTargets']or len(originals)!=1:raise ValueError('Target not unique in finite scope')
        original=originals[0];report=read(checked(choice['report']))
        if report['target']!=code:raise ValueError('Candidate target differs')
        candidates=[q for q in report['candidateCompleteFaces']if q['sheet']==choice['sheet']and q['layer']=='red'and q['faceIndex']==choice['faceIndex']]
        if len(candidates)!=1:raise ValueError('Exact native candidate not unique')
        q=candidates[0]
        for k in ['sourcePdf','sourceInventory','pageGeometry']:checked(q[k])
        case={**original,'sourcePdf':q['sourcePdf'],'sourceInventory':q['sourceInventory'],'sourceUrl':q['sourceUrl'],'reviewedNativePageFace':q['pageGeometry'],'originalOwnSourcePdf':original['sourcePdf'],'neighborSourceOwnerCode':q['sheet'],'neighborCandidateReport':choice['report'],'sourceAttributionRole':'Exact full target red face on adjacent original ISIE sheet; requires root visual attribution and unchanged registration/native gates.'}
        for key in ['diagnosticTargetLabelFace','selectedSourceFace','rootDiagnosticRender']:case.pop(key,None)
        cases.append(case)
    if len({q['officialCode']for q in cases})!=len(cases):raise ValueError('Duplicate target')
    put(a.output,{**s,'cases':cases,'configuration':pin(a.configuration.resolve()),'credit':0})
    print(json.dumps({'explicitNeighborCandidates':len(cases),'credit':0}))
