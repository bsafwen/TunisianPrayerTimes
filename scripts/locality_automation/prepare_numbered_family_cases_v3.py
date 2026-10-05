"""Prepare explicit original constituent-circle bindings under finite control."""
import argparse,json,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.family_work_v1 import norm
from scripts.locality_automation.isie_pdf_inventory import inspect

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--availability',type=Path,required=True);p.add_argument('--counts',nargs='+',required=True);p.add_argument('--unnumbered-first-code',action='append',default=[]);p.add_argument('--legal-pages',nargs='+',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();s=read(a.manifest);c=active_control(s);out=a.output.resolve();out.mkdir()
 av=read(a.availability)['isieSources'];official={r['sectorCode']:r for r in read(ROOT/'scripts/neighborhoods/touil-reviews/current-official-sector-registry.json')['sectors']};cat={v['id']:v for v in read(ROOT/'android-app/app/src/main/assets/neighborhoods.json')['features']};holds={v['code']:v for v in s['holds']};cases=[];groups=[]
 for value in a.counts:
  code,count=value.split(':');count=int(count);r=official[code];ids=holds[code]['appIds'];assert len(ids)==1 and code in s['exactTargets'] and 1<=count<=4
  components=[]
  for n in range(1,count+1):
   targets={norm(r['sectorAr']+' '+str(n))}
   if n==1 and code in a.unnumbered_first_code:targets.add(norm(r['sectorAr']))
   hits=[v for v in av if norm(v['delegationPath'])==norm(r['delegationAr'])and norm(v['pdfFilename'].removesuffix('.pdf').split(' - (')[0])in targets];assert len(hits)==1;v=hits[0];pdf=checked(v['selectedPdf'])
   inv=v.get('sourceInventory')or put(out/(code+'-'+str(n)+'-inventory.json'),inspect(pdf));checked(inv)
   q={'officialCode':code,'officialName':r['sectorAr'],'officialParent':r['delegationAr'],'appId':ids[0],'appMetadata':cat[ids[0]],'sourcePdf':pin(pdf),'sourceInventory':inv,'sourceUrl':v['url'],'componentName':v['pdfFilename'].removesuffix('.pdf'),'declaredCircleIds':[n],'useAdministrativeFaces':True,'identityBasis':'Unique current official code/app binding and exact original indexed parent-qualified constituent title. Complete circle roster separately confirmed from original decree pages.','unnumberedFirstCircleExplicitlyBound':n==1 and code in a.unnumbered_first_code}
   cases.append(q);components.append({'circle':n,'sourcePdf':pin(pdf),'sourceUrl':v['url']})
  groups.append({'officialCode':code,'expectedCircleCount':count,'components':components})
 put(out/'manifest.json',{**s,'cases':cases,'originalManifest':pin(a.manifest.resolve()),'credit':0});put(out/'declared-circle-roster.json',{'groups':groups,'legalPages':[pin(v.resolve())for v in a.legal_pages],'rootLegalVisualReviewRequired':True,'credit':0})
 print(json.dumps({'wholeUnits':len(groups),'constituentOriginals':len(cases),'credit':0}))

if __name__=='__main__':main()
