"""Stage a validated-only app catalogue without changing any retained polygon bytes.

Acceptance comes from the pinned existing report/map, never from this program.
Repacking copies approved slices verbatim and remaps derived indexes only.
"""
import argparse,copy,hashlib,json
from pathlib import Path

def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def pin(p):return {'file':str(p.resolve()),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()}
def put(p,v):p.write_text(json.dumps(v,ensure_ascii=False,separators=(',',':'))+'\n',encoding='utf-8')
def main():
 p=argparse.ArgumentParser(description=__doc__)
 for k in ['assets','map','report','main-catalog','output']:p.add_argument('--'+k,type=Path,required=True)
 a=p.parse_args();out=a.output.resolve();out.mkdir()
 assets=a.assets.resolve();names=['neighborhoods.json','neighborhoods.bin','retired-localities.json','locality-display-names.json']
 for n in names:(out/('before-'+n)).write_bytes((assets/n).read_bytes())
 m=read(a.map);report=read(a.report);before=read(assets/names[0]);blob=(assets/names[1]).read_bytes()
 assert m['currentAssets']['metadata']==pin(assets/names[0]) and m['currentAssets']['binary']==pin(assets/names[1])
 assert len(m['locations'])==report['summary']['explicitFullSourceBoundaryLocationCount']
 approved={r['id']:r for r in m['locations']};assert len(approved)==len(m['locations'])
 assert all(r['latestScope']=='full'and r['currentMatchesAccepted']for r in approved.values())
 assert {r['code']for r in approved.values()}==set(report['summary']['explicitFullSourceBoundaryLocalityCodes'])
 by={r['id']:r for r in before['features']};assert len(by)==len(before['features']) and set(approved)<=set(by)
 keep=[copy.deepcopy(r)for r in before['features']if r['id']in approved]
 assert len(keep)==len(approved) and all(r['kind']=='sector'and r['hasBoundary']for r in keep)
 after=copy.deepcopy(before);after['features']=keep;packed=bytearray(blob[:8]);assert blob[:4]==b'NPOL'
 for r in keep:
  old=by[r['id']];payload=blob[old['offset']:old['offset']+old['length']]
  assert len(payload)==old['length']
  r['offset']=len(packed);r['pickerGroupId']=r['id'];packed.extend(payload)
  assert bytes(packed[r['offset']:r['offset']+r['length']])==payload
  assert {k:v for k,v in r.items()if k not in ['offset','pickerGroupId']}=={k:v for k,v in old.items()if k not in ['offset','pickerGroupId']}
 country=before['country'];assert country['offset']+country['length']==len(blob)
 after['country']['offset']=len(packed);packed.extend(blob[country['offset']:]);assert packed[after['country']['offset']:]==blob[country['offset']:]
 new_index={r['id']:i for i,r in enumerate(keep)};remap={i:new_index[r['id']]for i,r in enumerate(before['features'])if r['id']in new_index}
 after['cells']={key:values for key,old_values in before['cells'].items()if(values:=[remap[i]for i in old_values if i in remap])}
 assert {i for values in after['cells'].values()for i in values}==set(range(len(keep)))
 after['conflicts']=[r for r in before['conflicts']if set(r['ids'])<=set(approved)]
 after['gpsConflictPolicies']=[r for r in before.get('gpsConflictPolicies',[])if set(r['ids'])<=set(approved)]
 after['sources']={k:v for k,v in before['sources'].items()if k in {r['sourceId']for r in keep}}
 after.update(catalogScope='validated-official-sectors',validatedLocationCount=len(keep))
 main=read(a.main_catalog);governors=read(assets/'gouvernorats.json')['gouvernorats']
 delegates={'delegation:'+str(d['id'])for g in governors for d in g['delegations']}
 retired=read(assets/names[2]);removed=set(by)-set(approved);retired_ids=(set(retired['retiredLocalityIds'])|removed|({r['id']for r in main['features']}-set(approved))|delegates)-set(approved)
 display=read(assets/names[3]);display['names']=[r for r in display['names']if r['id']in approved];localized={r['id']:r['nameAr']for r in display['names']}
 retired['retiredLocalityIds']=sorted(retired_ids)
 retired['reviewedNames']=[{'id':r['id'],'name':localized.get(r['id'],r['name']),'kind':'sector'}for r in keep]
 retired['replacements']=[{**r,'name':localized.get(r['replacementId'],by[r['replacementId']]['name']),'kind':'sector'}for r in retired.get('replacements',[])if r['replacementId']in approved]
 after['retiredLocalityIds']=sorted(retired_ids)
 put(out/names[0],after);(out/names[1]).write_bytes(packed);put(out/names[2],retired);put(out/names[3],display)
 receipt={'status':'STAGED_VALIDATED_ONLY_CATALOGUE_WITH_IDENTICAL_RETAINED_GEOMETRY_BYTES','acceptedMap':pin(a.map),'acceptedReport':pin(a.report),'mainCatalog':pin(a.main_catalog),'beforeAssets':{n:pin(assets/n)for n in names},'stagedAssets':{n:pin(out/n)for n in names},'validatedLocations':len(keep),'removedLegacyEntries':len(removed),'removedLegacyIds':sorted(removed),'removedDelegationPickerRows':len(delegates),'allValidatedIdsRetained':True,'everyRetainedPackedSliceByteIdentical':True,'countryBytesIdentical':True,'coordinatesAndNamesUnchanged':True,'allPickerGroupsUseOwnValidatedId':True,'derivedCellIndexesOnlyRemapped':True,'existingConflictsOnlyFilteredByRetainedIds':True,'oldSavedLocationsRetired':True,'geographicValidationCredit':0,'gpsProbes':0}
 put(out/'stage-receipt.json',receipt)
 print(json.dumps({k:receipt[k]for k in ['status','validatedLocations','removedLegacyEntries','removedDelegationPickerRows','everyRetainedPackedSliceByteIdentical','gpsProbes']}))
if __name__=='__main__':main()
