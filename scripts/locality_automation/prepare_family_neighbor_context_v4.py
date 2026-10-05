"""Account for exact indexed originals, retaining explicitly unbound context.

An indexed map without a unique current INS unit stays in a separate source
ledger. It is never assigned an invented official code or an acceptance role.
"""
import argparse,json,sys
from pathlib import Path
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.family_work_v1 import norm
from scripts.locality_automation.isie_pdf_inventory import inspect

def main():
 p=argparse.ArgumentParser(description=__doc__)
 for name in ['work','manifest','circle-manifest','availability','output']:p.add_argument('--'+name,type=Path,required=True)
 p.add_argument('--unbound-index-text',action='append',default=[])
 a=p.parse_args();w=a.work.resolve();s=read(a.manifest);c=active_control(s)
 if w!=Path(s['control']).resolve().parent/('root-cycle'+str(c['iteration'])):raise ValueError('Existing current producer required')
 cs=read(a.circle_manifest);active_control(cs)
 if cs['exactTargets']!=s['exactTargets']:raise ValueError('Constituent finite scope differs')
 cases=s['cases']+cs['cases'];known={x['sourcePdf']['sha256']for x in cases}
 if len(known)!=len(cases):raise ValueError('Duplicated original bound context')
 av=read(a.availability)['isieSources'];reg=read(R/'scripts/neighborhoods/touil-reviews/current-official-sector-registry.json')['sectors']
 baseline=read(w/'window-baseline.json');codes=set(baseline['allFamilyOfficialCodes']);unbound=[];declared=set(a.unbound_index_text)
 if len(declared)!=len(a.unbound_index_text):raise ValueError('Duplicate explicit unbound declaration')
 for v in av:
  pdf=checked(v['selectedPdf'])
  if v['selectedPdf']['sha256']in known:continue
  name=v['pdfFilename'].removesuffix('.pdf').split(' - (')[0]
  matches=[r for r in reg if r['sectorCode']in codes and norm(r['delegationAr'])==norm(v['delegationPath'])and norm(r['sectorAr'])==norm(name)]
  inv=v.get('sourceInventory')
  if not inv:inv=put(w/(v['selectedPdf']['sha256']+'-context-inventory.json'),inspect(pdf))
  checked(inv)
  if not matches and v['indexText']in declared:
   unbound.append({'indexText':v['indexText'],'indexedParent':v['delegationPath'],'sourcePdf':v['selectedPdf'],'sourceInventory':inv,'sourceUrl':v['url'],'sourceContextOnly':True,'currentInsCodeAsserted':False,'appBindingAsserted':False,'scopeAcceptanceAllowed':False,'qualification':'Exact indexed source retained without a unique current INS unit; no invented identity or boundary credit.'})
  elif len(matches)==1 and v['indexText']not in declared:
   r=matches[0];cases.append({'officialCode':r['sectorCode'],'officialName':r['sectorAr'],'officialParent':r['delegationAr'],'sourcePdf':v['selectedPdf'],'sourceInventory':inv,'sourceUrl':v['url'],'sourceContextOnly':True})
  else:raise ValueError('Undeclared or conflicting source context: '+v['indexText'])
  known.add(v['selectedPdf']['sha256'])
 if {v['indexText']for v in unbound}!=declared or len(cases)+len(unbound)!=len(av)or len(known)!=len(av):raise ValueError('Exact indexed context accounting incomplete or duplicated')
 put(a.output.resolve(),{**s,'cases':cases,'unboundIndexedContext':unbound,'completeIndexedOriginalCount':len(av),'credit':0})
 print(json.dumps({'boundOriginals':len(cases),'unboundOriginals':len(unbound),'indexedOriginals':len(av),'credit':0}))

if __name__=='__main__':main()
