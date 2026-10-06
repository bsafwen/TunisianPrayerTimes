"""Publish a byte-preserving validated catalogue; keep acceptance history unchanged."""
import argparse,hashlib,json,os,sys
from pathlib import Path
from datetime import datetime,timezone

ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT))
from scripts.locality_automation.pause_reviewed_family_holds_v1 import refresh
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def pin(p):return {'file':str(p.resolve()),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()}
def checked(r):
 p=Path(r['file']);assert pin(p)==r;return p
def save(p,v):p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def replace(p,body):
 tmp=p.with_name(p.name+'.validated-catalog.tmp')
 with tmp.open('xb')as f:f.write(body);f.flush();os.fsync(f.fileno())
 tmp.replace(p)
def main():
 p=argparse.ArgumentParser(description=__doc__)
 for k in ['stage','evidence','output']:p.add_argument('--'+k,type=Path,required=True)
 a=p.parse_args();stage=a.stage.resolve();w=Path.cwd();e=a.evidence.resolve();receipt=read(stage/'stage-receipt.json');out=a.output.resolve();assert not out.exists()
 assert receipt['status']=='STAGED_VALIDATED_ONLY_CATALOGUE_WITH_IDENTICAL_RETAINED_GEOMETRY_BYTES'
 for key in ['allValidatedIdsRetained','everyRetainedPackedSliceByteIdentical','countryBytesIdentical','allPickerGroupsUseOwnValidatedId','derivedCellIndexesOnlyRemapped','oldSavedLocationsRetired']:assert receipt[key]is True
 checked(receipt['acceptedMap']);checked(receipt['acceptedReport'])
 names=list(receipt['beforeAssets']);backups={}
 for n in names:
  target=checked(receipt['beforeAssets'][n]);checked(receipt['stagedAssets'][n])
  assert target.parent==(ROOT/'android-app/app/src/main/assets').resolve()
  backup=stage/('before-'+n);assert hashlib.sha256(backup.read_bytes()).hexdigest()==receipt['beforeAssets'][n]['sha256'];backups[n]=backup.read_bytes()
 journal=w/'publish-journal.json';assert not journal.exists();save(journal,{'status':'PREPARED','stage':pin(stage/'stage-receipt.json'),'written':[]})
 written=[]
 try:
  for n in names:
   replace(Path(receipt['beforeAssets'][n]['file']),Path(receipt['stagedAssets'][n]['file']).read_bytes());written.append(n);save(journal,{'status':'PUBLISHING','stage':pin(stage/'stage-receipt.json'),'written':written})
 except BaseException:
  for n in reversed(written):replace(Path(receipt['beforeAssets'][n]['file']),backups[n])
  save(journal,{'status':'ROLLED_BACK','written':written});raise
 deployed={n:pin(Path(receipt['beforeAssets'][n]['file']))for n in names}
 assert all(deployed[n]['sha256']==receipt['stagedAssets'][n]['sha256']for n in names)
 deployment={'status':'PUBLISHED_VALIDATED_ONLY_APP_CATALOGUE','publishedAtUtc':datetime.now(timezone.utc).isoformat(),'stage':pin(stage/'stage-receipt.json'),'installedAssets':deployed,'validatedLocations':receipt['validatedLocations'],'removedLegacyEntries':receipt['removedLegacyEntries'],'removedDelegationPickerRows':receipt['removedDelegationPickerRows'],'retainedBoundaryBytesIdentical':True,'newBoundaryValidationCredit':0,'apkInstalled':False}
 for key in ['frozenBeforeAssets','frozenAcceptedMap','frozenAcceptedReport']:
  if key in receipt:deployment[key]=receipt[key]
 save(out,deployment);save(journal,{'status':'ASSETS_PUBLISHED','deployment':pin(out)})
 public=e/'work/locality-progress-dashboard';m=read(checked(receipt['acceptedMap']));model=read(checked(receipt['acceptedReport']));original_summary=model['summary']
 for name in ['boundary-map.json','task-report.json']:
  with(w/('before-catalog-publication-'+name)).open('xb')as f:f.write((public/name).read_bytes())
 m['currentAssets']={'metadata':deployed['neighborhoods.json'],'binary':deployed['neighborhoods.bin']};m['validatedCatalogueDeployment']=pin(out);m['generatedAtUtc']=datetime.now(timezone.utc).isoformat()
 model['validatedCatalogueDeployment']=pin(out)
 save(public/'boundary-map.json',m);save(public/'task-report.json',model)
 cp=e/'work/locality-efficiency-cycles-20261001/control.json';c=read(cp);assert c['phase']=='paused'and c['cutoffTimerState']=='PAUSED_VERIFIED'
 with(w/'before-catalog-deployment-control.json').open('xb')as f:f.write(cp.read_bytes())
 c['latestValidatedAppCatalogDeployment']=pin(out);c['lastControlUpdateUtc']=datetime.now(timezone.utc).isoformat()
 wf=e/'CURRENT_WORKFLOW.md'
 with(w/'before-catalog-deployment-workflow.md').open('xb')as f:f.write(wf.read_bytes())
 wf.write_text('# Validated catalogue deployment — source validation remains PAUSED\n\nThe app now contains only2082 accepted complete official sector locations.1391 unvalidated legacy features and259 synthetic delegation picker rows are removed. Every retained boundary slice and country payload is byte-identical; only packing offsets, cells and display groups changed. Saved removed IDs are retired. Geographic/source counts, acceptance times and map coordinates do not change; no new validation credit. Read '+str(out)+' and its frozen before-assets. Future source work must rebase to these current assets and retain this byte-preservation lineage; do not restore the main-branch catalogue or rerun the broad legacy generator into live assets. App build/device installation is a separate human-authorized deployment task.\n\n'+wf.read_text(encoding='utf-8'),encoding='utf-8')
 c['currentWorkflow']=pin(wf);save(cp,c);refresh(public,cp)
 assert read(public/'task-report.json')['summary']==original_summary
 save(w/'catalogue-dashboard-publication-receipt.json',{'status':'PUBLISHED_CATALOGUE_LINEAGE_WITHOUT_NEW_VALIDATION','deployment':pin(out),'map':pin(public/'boundary-map.json'),'report':pin(public/'task-report.json'),'html':pin(public/'dashboard.html'),'historicalCountsTimesAndCoordinatesUnchanged':True,'sourceValidationPhase':'paused','additionalGpsChecks':0,'newGeographicCredit':0})
 print(json.dumps({'publishedValidatedLocations':deployment['validatedLocations'],'removedLegacyEntries':deployment['removedLegacyEntries'],'removedDelegationPickerRows':deployment['removedDelegationPickerRows'],'retainedGeometryBytesUnchanged':True,'sourceValidationPhase':'paused'}))
if __name__=='__main__':main()
