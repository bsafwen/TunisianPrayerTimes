"""Pin finite indexed family originals for source comparison, without acceptance."""
import argparse,json,sys
from pathlib import Path
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.family_work_v1 import norm
def main():
 p=argparse.ArgumentParser();p.add_argument('--work',type=Path,required=True);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--circle-manifest',type=Path,required=True);p.add_argument('--availability',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();w=a.work.resolve()
 spec=read(a.manifest);active_control(spec)
 cases=spec['cases']+read(a.circle_manifest)['cases'];known={x['sourcePdf']['sha256']for x in cases}
 av=read(a.availability)['isieSources'];reg=read(R/'scripts/neighborhoods/touil-reviews/current-official-sector-registry.json')['sectors'];roster=read(w/'window-baseline.json')
 codes=set(roster['allFamilyOfficialCodes'])
 for v in av:
  if v['selectedPdf']['sha256']in known:continue
  name=v['pdfFilename'].removesuffix('.pdf').split(' - (')[0]
  matches=[x for x in reg if x['sectorCode']in codes and norm(x['delegationAr'])==norm(v['delegationPath'])and norm(x['sectorAr'])==norm(name)]
  if len(matches)!=1 or not v.get('sourceInventory'):raise ValueError('Unbound source context: '+v['indexText'])
  r=matches[0];checked(v['selectedPdf']);checked(v['sourceInventory'])
  cases.append({'officialCode':r['sectorCode'],'officialName':r['sectorAr'],'officialParent':r['delegationAr'],'sourcePdf':v['selectedPdf'],'sourceInventory':v['sourceInventory'],'sourceUrl':v['url'],'sourceContextOnly':True})
  known.add(v['selectedPdf']['sha256'])
 if len(cases)!=len(av)or len(known)!=len(av):raise ValueError('Indexed original context incomplete or duplicated')
 put(a.output.resolve(),{**spec,'cases':cases,'credit':0});print(json.dumps({'uniqueOriginals':len(cases),'credit':0}))
if __name__=='__main__':main()
