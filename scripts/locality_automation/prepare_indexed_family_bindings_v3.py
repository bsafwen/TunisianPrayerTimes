"""Bind exact indexed filenames, retaining explicit reviewed numbered circles and variants."""
import argparse,json,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.family_work_v1 import norm
from scripts.locality_automation.family_work_v3 import validate_identity
from scripts.locality_automation.isie_pdf_inventory import inspect
from scripts.locality_automation.reuse_family_inventory_v1 import source_inventory

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for key in ['manifest','availability','baseline','legal-manifest','configuration','output-prefix']:p.add_argument('--'+key,type=Path,required=True)
    a=p.parse_args();s=read(a.manifest);c=active_control(s);cfg=read(a.configuration);w=Path.cwd()
    reg=read(ROOT/'scripts/neighborhoods/touil-reviews/current-official-sector-registry.json');validate_identity(c['governorateCode'],cfg['governorateName'],reg)
    registry={r['sectorCode']:r for r in reg['sectors']};base=read(a.baseline);allcodes=base['allFamilyOfficialCodes'];prior=set(base['alreadyCompleteFamilyCodes']);av=read(a.availability)['isieSources'];legal=read(a.legal_manifest)
    if set(allcodes)!=set(c['allFamilyOfficialCodes'])or s['exactTargets']!=c['approvedCycle'+str(c['iteration'])+'AcceptancePool']:raise ValueError('Finite official pool differs')
    if set(allcodes)-prior!=set(s['exactTargets'])or not cfg['rootLegalReviewNote']:raise ValueError('Prior/pending scope or actual legal review missing')
    checked(legal['pdf']);pages=[pin(Path(cfg['legalRenderDirectory'])/f'page-{n}.png')for n in legal['pages']]
    ids={r['officialCode']:r['appId']for r in s['cases']}
    for h in s['holds']:
        if len(h['appIds'])!=1:raise ValueError('Explicit unique current app ID required: '+h['code'])
        ids[h['code']]=h['appIds'][0]
    cat={r['id']:r for r in read(ROOT/'android-app/app/src/main/assets/neighborhoods.json')['features']}
    counts=cfg['declaredCircleCounts'];aliases=cfg.get('indexedNameVariants',{});parents=cfg.get('indexedParentVariants',{});filenames=cfg.get('indexedFilenames',{})
    if not set(counts)<=set(allcodes)or not set(aliases)<=set(allcodes)or not set(filenames)<=set(allcodes):raise ValueError('Explicit variant outside family')
    cases=[];bindings=[];used=set()
    for code in allcodes:
        r=registry[code];name=aliases.get(code,r['sectorAr']);parent=parents.get(r['delegationAr'],r['delegationAr'])
        for n in range(1,counts.get(code,1)+1):
            if code in filenames and len(filenames[code])!=counts.get(code,1):raise ValueError('Explicit filename/circle count differs')
            filename=filenames[code][n-1]if code in filenames else name+(f' ({n})'if n>1 else '')+'.pdf'
            hits=[v for v in av if norm(v['delegationPath'])==norm(parent)and norm(v['pdfFilename'])==norm(filename)]
            if len(hits)!=1:raise ValueError('Not uniquely indexed: '+code+' '+parent+' '+filename)
            v=hits[0];pdf=checked(v['selectedPdf']);sha=v['selectedPdf']['sha256']
            if sha in used:raise ValueError('One source PDF assigned more than once')
            used.add(sha);inv=source_inventory(v,s['cases'])
            if not inv:inv=put(w/(sha+'-explicit-inventory.json'),inspect(pdf))
            checked(inv)
            q={'officialCode':code,'officialName':r['sectorAr'],'officialParent':r['delegationAr'],'sourcePdf':pin(pdf),'sourceInventory':inv,'sourceUrl':v['url'],'useAdministrativeFaces':True,'componentName':filename.removesuffix('.pdf').split(' - (')[0],'identityBasis':'Exact current INS code/name/parent and unique app binding, exact parent-qualified indexed source or explicit root-reviewed variant, pinned original decree visual review; no fuzzy identity.'}
            if code in prior:q['sourceContextOnly']=True
            else:q.update(appId=ids[code],appMetadata=cat[ids[code]])
            if code in counts:q['declaredCircleIds']=[n]
            cases.append(q);bindings.append({'officialCode':code,'officialName':r['sectorAr'],'indexedParent':parent,'indexedFilename':filename,'declaredCircle':n if code in counts else None,'sourcePdf':pin(pdf),'sourceContextOnly':code in prior})
    if len(cases)!=len(av)or used!={v['selectedPdf']['sha256']for v in av}:raise ValueError('Complete indexed originals not accounted for')
    groups=[]
    for code,count in counts.items():
        parts=[q for q in cases if q['officialCode']==code]
        groups.append({'officialCode':code,'expectedCircleCount':count,'components':[{'circle':q['declaredCircleIds'][0],'sourcePdf':q['sourcePdf'],'sourceUrl':q['sourceUrl']}for q in parts]})
    prefix=str(a.output_prefix)
    put(Path(prefix+'-all-context.json'),{**s,'cases':cases,'holds':[],'unavailableCodes':[],'credit':0,'completeIndexedOriginalCount':len(cases)})
    put(Path(prefix+'-circle-roster.json'),{'groups':groups,'legalPages':pages,'rootLegalReviewNote':cfg['rootLegalReviewNote'],'rootLegalVisualReviewRequired':True,'credit':0})
    put(Path(prefix+'-receipt.json'),{'bindings':bindings,'wholeUnits':len(allcodes),'originals':len(cases),'acceptanceOriginals':sum(not q.get('sourceContextOnly',False)for q in cases),'reusedContextOriginals':sum(q.get('sourceContextOnly',False)for q in cases),'originalManifest':pin(a.manifest.resolve()),'configuration':pin(a.configuration.resolve()),'legalManifest':pin(a.legal_manifest.resolve()),'credit':0})
    print(json.dumps({'wholeUnits':len(allcodes),'originals':len(cases),'acceptanceBodies':len(s['exactTargets']),'circleTargetBodies':len(set(counts)&set(s['exactTargets'])),'credit':0}))

if __name__=='__main__':main()
