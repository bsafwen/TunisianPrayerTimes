"""Prepare parent-qualified source cases, reusing pinned native inventories."""
import argparse,json,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.family_work_v1 import norm
from scripts.locality_automation.isie_pdf_inventory import inspect

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--work',type=Path,required=True);p.add_argument('--availability',type=Path,required=True);a=p.parse_args();w=a.work.resolve();c=read(w.parent/'control.json');s={k:c[k]for k in ['iteration','windowStartUtc','deadlineUtc']};s.update(control=str(w.parent/'control.json'),owner='/root');active_control(s)
 av=read(a.availability);reg=read(ROOT/'scripts/neighborhoods/touil-reviews/current-official-sector-registry.json')['sectors'];official={r['sectorCode']:r for r in reg};claims=read(ROOT/'scripts/neighborhoods/touil-reviews/original-pbf-source-facts.json')['administrativeCodeClaims'];cat=read(ROOT/'android-app/app/src/main/assets/neighborhoods.json');byid={r['id']:r for r in cat['features']};pool=c['approvedCycle'+str(c['iteration'])+'AcceptancePool'];cases=[];holds=[];reused=0
 for code in pool:
  r=official[code];name=r['sectorAr'];parent=r['delegationAr'];stem=name.rstrip('12')
  matches=[v for v in av['isieSources']if norm(v['delegationPath'])==norm(parent)and norm(v['pdfFilename'].removesuffix('.pdf').split(' - (')[0])in{norm(name),norm(stem)}]
  appids=[i for i in claims.get(code,[])if i in byid and byid[i]['kind']=='sector']
  if len(appids)!=1:appids=[i for i,v in byid.items()if v['kind']=='sector'and norm(v['parentName'].removeprefix('معتمدية '))==norm(parent)and any(norm(n)in{norm(name),norm(stem)}for n in[v['name']]+v['aliases'])]
  if len(matches)!=1 or len(appids)!=1 or not matches[0].get('selectedPdf'):
   holds.append({'code':code,'name':name,'parent':parent,'sources':[{'name':v['indexText'],'url':v['url'],'availability':v['availability']}for v in matches],'appIds':appids,'reason':'Explicit original source/app identity or constituent scope needed'});continue
  v=matches[0];pdf=checked(v['selectedPdf'])
  if v.get('sourceInventory'):inventory=checked(v['sourceInventory']);reused+=1
  else:inventory=w/(code+'-original-inventory.json');put(inventory,inspect(pdf))
  leads=read(inventory)['pages'][0]['labeledAreaLeads'];own=[(i,l)for i,l in enumerate(leads)if l['centerPagePoints'][1]>100 and norm(l['text'])in{norm(stem),norm(stem[::-1]),norm(name),norm(name[::-1])}]
  case={'officialCode':code,'officialName':name,'officialParent':parent,'appId':appids[0],'sourcePdf':pin(pdf),'sourceInventory':pin(inventory),'sourceUrl':v['url'],'identityBasis':'Unique current official code claim where available; exact parent-qualified catalog name otherwise. Original captions and legal scope require root visual review.','appMetadata':byid[appids[0]]}
  if len(own)==1:case['diagnosticTargetLabelFace']={'targetLabelIndex':own[0][0],'targetLabelText':own[0][1]['text']}
  cases.append(case)
 put(w/(c['familySlug']+'-originals-manifest.json'),{**s,'exactTargets':pool,'cases':cases,'unavailableCodes':[h['code']for h in holds],'availability':pin(a.availability),'holds':holds,'credit':0})
 print(json.dumps({'prepared':len(cases),'reusedInventories':reused,'holds':holds,'credit':0},ensure_ascii=False))

if __name__=='__main__':main()
