"""Register checked indexed originals and inventories in the existing cache.

No fetching, extraction, geometry mutation, acceptance or validation credit.
"""
import argparse,json,re,sys
from pathlib import Path
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put

def main():
 p=argparse.ArgumentParser(description=__doc__)
 for key in ('manifest','availability','evidence','output'):p.add_argument('--'+key,type=Path,required=True)
 a=p.parse_args();w=Path.cwd();s=read(a.manifest);c=active_control(s);e=a.evidence.resolve()
 assert c['acceptanceOwner']=='/root' and not c['subagentsAllowed'] and re.fullmatch('[a-z0-9-]+',c['familySlug'])
 av=read(a.availability);checked(av['sourceIndex']);indexed={r['url']for r in read(checked(av['sourceIndex']))}
 cases=s['cases'];urls={r['sourceUrl']for r in cases};assert urls=={r['url']for r in av['isieSources']} and urls<=indexed
 assert len(cases)==len(urls) and all(r['selectedPdf']for r in av['isieSources'])
 available={r['url']:r['selectedPdf']for r in av['isieSources']}
 output=a.output.resolve();assert output.parent==w and not output.exists()
 target=e/'work/locality-automation/batches'/(c['familySlug']+'-indexed-current')/('window-'+str(c['iteration']))
 assert not target.exists();inventory_dir=e/'work/isie-execution-20260926/pdf-inventories';assert inventory_dir.is_dir()
 rows=[];saved=[];reused=[]
 for q in cases:
  pdf=checked(q['sourcePdf']);assert q['sourcePdf']==available[q['sourceUrl']]
  with pdf.open('rb')as f:assert f.read(5)==b'%PDF-'
  source=checked(q['sourceInventory']);inv=read(source);checked(inv['sourcePdf']);assert inv['sourcePdf']['sha256']==q['sourcePdf']['sha256']
  dest=inventory_dir/(q['sourcePdf']['sha256']+'.json')
  if dest.exists():
   old=read(dest);checked(old['sourcePdf']);assert old['sourcePdf']['sha256']==q['sourcePdf']['sha256'];reused.append(pin(dest))
  else:
   with dest.open('xb')as f:f.write(source.read_bytes())
   assert dest.read_bytes()==source.read_bytes();saved.append(pin(dest))
  rows.append({'file':str(pdf),'sha256':q['sourcePdf']['sha256'],'url':q['sourceUrl'],'name':q.get('componentName',q['officialName']),'sourceManifest':pin(a.manifest.resolve())})
 target.mkdir(parents=True);manifest=target/'manifest.json';put(manifest,rows)
 assert len(read(manifest))==len(cases)
 put(output,{'status':'CHECKED_INDEXED_SOURCE_CACHE_REGISTERED_NO_CREDIT','sourceIndex':av['sourceIndex'],'sourceManifest':pin(a.manifest.resolve()),'availability':pin(a.availability.resolve()),'batchManifest':pin(manifest),'originalCount':len(rows),'inventoriesSaved':saved,'inventoriesReused':reused,'changedBoundaryCount':0,'validationCredit':0,'networkRequests':0,'historicalEvidenceOverwritten':False})
 print(json.dumps({'originalsRegistered':len(rows),'inventoriesSaved':len(saved),'inventoriesReused':len(reused),'validationCredit':0}))
if __name__=='__main__':main()
