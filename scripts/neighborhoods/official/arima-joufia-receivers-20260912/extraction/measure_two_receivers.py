"""Bounded source-only two-receiver effects; no product imports or output assets."""
from pathlib import Path
import json,hashlib,struct,math,itertools
from shapely.geometry import shape,Polygon,MultiPolygon,Point,box
from shapely.ops import transform,unary_union
from pyproj import Transformer

D=Path(__file__).resolve().parent;T=D.parents[1];R=Path('C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes');A=R/'android-app/app/src/main/assets';C=R/'scripts/neighborhoods'
def read(p):return json.loads(Path(p).read_bytes())
def pin(p):
 p=Path(p).resolve();return {'file':p.as_posix(),'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'bytes':p.stat().st_size}
def save(p,o):
 assert not p.exists(),p
 p.write_text(json.dumps(o,ensure_ascii=False,indent=2)+'\n',encoding='utf8',newline='\n')
paths={'metadata':A/'neighborhoods.json','binary':A/'neighborhoods.bin','curation':C/'catalog-curation.json','picker':C/'reviewed-picker-groups.json','boundaries':C/'reviewed-boundaries.json','coverage':C/'coverage.json','governors':A/'gouvernorats.json','sourceCache':T/'work/geo/osm-areas.json','newBundle':D/'two-whole-native-source-polygons.geojson','sourceSelection':D/'two-own-map-source-selection-facts.json','frozenThirteenReadiness':T/'outputs/soliman-thirteen-native-source-stage-readiness-20260912.json','frozenThirteenPackage':T/'work/soliman-thirteen-native-source-stage-20260912/source-package-manifest.json','frozenThirteenBundle':T/'work/soliman-thirteen-native-source-stage-20260912/scripts/neighborhoods/official/soliman-thirteen-native-20260912/soliman-thirteen-whole-source-boundaries.geojson','priorThirteenFacts':T/'work/soliman-five-outer-own-map-source-review/five-receiver-eight-thirteen-cohort-facts.json','primaryPoints':T/'outputs/baddar-aitha-kharmia-essabala-name-source-review.json'}
inputs={k:pin(p) for k,p in paths.items()}
for k,h in {'metadata':'992b1409c45567e83d153cd2b033a5ea1466413c8d3e0b6f3861d4fb0b9f3b41','newBundle':'1cda298b6956e7dbde26a334ee9dae1d3457ac43a03f1f4abdc5441eed454427','sourceSelection':'0bb60e9953475b957cde85d7ba857cc3483b2ffcbde6106e08c559895e4098fa','frozenThirteenReadiness':'3ec47bb2007f8de140d897644ed0c15103b446cdce55aae7ad77278dff2c2306','frozenThirteenPackage':'171752fefd84e6be834e795140b2c53168984f08cd15aa384928b9c3b52ffddc','frozenThirteenBundle':'82c1c5e2e99194c59b075d2ca63a32cda82deb0babae19de0c210da987fe4857','primaryPoints':'b3c47138b89438ffb4e1b435e623fefe21b9590e61c9202f5bd2c280258466b0'}.items():assert inputs[k]['sha256']==h,(k,inputs[k])
m=read(paths['metadata']);rows={r['id']:r for r in m['features']};blob=paths['binary'].read_bytes();cache=read(paths['sourceCache']);areas={a['id']:a for a in cache['areas']}
fw=Transformer.from_crs(4326,32632,always_xy=True);iv=Transformer.from_crs(32632,4326,always_xy=True)
pr=lambda g:transform(fw.transform,g)
def unpack(r):
 b=blob[r['offset']:r['offset']+r['length']];pos=0
 def take():
  nonlocal pos
  v=struct.unpack_from('>i',b,pos)[0];pos+=4;return v
 polys=[]
 for _ in range(take()):
  rings=[]
  for _ in range(take()):rings.append([(take()/m['coordinateScale'],take()/m['coordinateScale']) for _ in range(take())])
  polys.append(Polygon(rings[0],rings[1:]))
 assert pos==len(b)
 return MultiPolygon(polys)
def pieces(g):
 if g.geom_type=='Polygon':return [g]
 return [p for x in getattr(g,'geoms',[]) for p in pieces(x)]
def components(g,n=10):
 return [{'areaM2':p.area,'boundsWgs84':list(transform(iv.transform,p).bounds),'representativeWgs84':dict(zip(('lng','lat'),iv.transform(p.representative_point().x,p.representative_point().y)))} for p in sorted(pieces(g),key=lambda p:p.area,reverse=True) if p.area>0][:n]
pt=lambda r:Point(*fw.transform(r['lng'],r['lat']))
def memberships(r,d):
 p=pt(r);return sorted(i for i,g in d.items() if g.covers(p))
features=read(paths['newBundle'])['features'];two={f['id']:pr(shape(f['geometry'])) for f in features}
thirteen={f.get('id',f['properties']['id']):pr(shape(f['geometry'])) for f in read(paths['frozenThirteenBundle'])['features']};assert len(thirteen)==13 and not(set(two)&set(thirteen))
fifteen={**thirteen,**two};old={i:pr(unpack(rows[i])) for i in fifteen}
changed=unary_union([old[i].symmetric_difference(two[i]) for i in two]);footprint=unary_union([old[i] for i in two]+list(two.values()));bounds=box(*transform(iv.transform,footprint).bounds)
local={}
for i,r in rows.items():
 if r['hasBoundary'] and box(*r['bbox']).intersects(bounds):
  g=pr(unpack(r))
  if g.intersects(footprint):local[i]=g
country=pr(unpack(m['country']));assert all(country.covers(g) for g in two.values())
currentsectors={i:g for i,g in local.items() if rows[i]['kind']=='sector'}
remainingafter15={i:g for i,g in currentsectors.items() if i not in fifteen}
contexts={i:pr(shape(a['geometry'])) for i,a in areas.items() if a['tags'].get('ref:tn:codegeo') in ['1560','1561','1562']}
pointfacts=[]
for rid,receiver,primarycode in [('osm:node:2173790702','osm:relation:7100361','1560'),('osm:node:2191295540','osm:relation:7101605','1562')]:
 r=rows[rid];p=pt(r);assert two[receiver].covers(p)
 pointfacts.append({'id':rid,'currentRaw':r,'sourceCacheRecord':next(n for n in cache['nodes'] if n['id']==rid),'currentPackedSectorMembership':memberships(r,currentsectors),'thirteenNativeMembership':memberships(r,thirteen),'twoReceiverNativeMembership':memberships(r,two),'remainingCurrentSectorMembershipAfterFifteen':memberships(r,remainingafter15),'candidateSectorBoundaryDistanceMeters':two[receiver].boundary.distance(p),'cachedAdministrativeContextMembership':[{'id':i,'tags':areas[i]['tags']} for i in memberships(r,contexts)],'qualifiedReceivingDelegationCode':primarycode,'preservePointNameCoordinatesPickerAndPrayerDefault':True})

# Only representatives whose membership changes under these two proposed replacements.
rawchanges=[]
for r in rows.values():
 a,b=memberships(r,{i:old[i] for i in two}),memberships(r,two)
 if a!=b:rawchanges.append({'currentRaw':r,'beforeTwoSectorIds':a,'afterTwoNativeSectorIds':b,'otherThirteenNativeIds':memberships(r,thirteen),'remainingCurrentSectorIds':memberships(r,remainingafter15)})
sourcechanges=[]
for r in cache['nodes']:
 a=memberships(r,{i:pr(shape(areas[i]['geometry'])) for i in two});b=memberships(r,two)
 if a!=b:sourcechanges.append({'sourceCacheRecord':r,'currentRaw':rows.get(r['id']),'beforeSourceSectorIds':a,'afterNativeSectorIds':b,'retainedContainingLocalityPolygons':memberships(r,{i:g for i,g in local.items() if rows[i]['kind']!='sector'}),'thirteenNativeIds':memberships(r,thirteen)})
intersections=[]
for i,g in local.items():
 a=g.intersection(changed).area
 if i not in two and a>0:intersections.append({'id':i,'currentRaw':rows[i],'changeFootprintIntersectionM2':a,'wholeFootprintM2':g.area,'twoNativeShares':{j:g.intersection(ng).area/g.area for j,ng in two.items() if g.intersection(ng).area>0},'thirteenNativeShares':{j:g.intersection(ng).area/g.area for j,ng in thirteen.items() if g.intersection(ng).area>0}})
affected=set(two)|{r['currentRaw']['id'] for r in rawchanges}|{r['id'] for r in intersections};groups={rows[i]['pickerGroupId'] for i in affected};closure=[r for r in rows.values() if r['pickerGroupId'] in groups]
def refs(o,path='$'):
 if isinstance(o,dict):
  if o.get('id') in affected or (isinstance(o.get('ids'),list) and any(i in affected for i in o['ids'])):return [{'path':path,'record':o}]
  return [x for k,v in o.items() for x in refs(v,path+'.'+k)]
 if isinstance(o,list):return [x for n,v in enumerate(o) for x in refs(v,f'{path}[{n}]')]
 return []
bindings={k:refs(read(paths[k])) for k in ['curation','picker','boundaries']}
cov=read(paths['coverage']);receipts=[r for r in cov['curationApplications'] if r.get('id') in affected or r.get('decisionId') in affected]

# What these two actual native receivers cover in the prior thirteen loss comparison.
old13=unary_union([old[i] for i in thirteen]);native13=unary_union(list(thirteen.values()));loss13=old13.difference(native13)
otherpolys=[]
bb13=box(*transform(iv.transform,loss13).bounds)
for i,r in rows.items():
 if i not in thirteen and r['hasBoundary'] and box(*r['bbox']).intersects(bb13):
  g=pr(unpack(r))
  if g.intersects(loss13):otherpolys.append(g)
uncovered13=loss13.difference(unary_union(otherpolys));largest=sorted(pieces(uncovered13),key=lambda p:p.area,reverse=True)[0]
prior=read(paths['priorThirteenFacts'])['scenarios'][-1]
assert abs(uncovered13.area-prior['lossUncoveredByAnyRetainedCurrentPolygonM2'])<0.05
receiverfacts=[]
for i,g in two.items():
 selected=next(r for r in read(paths['sourceSelection'])['records'] if r['id']==i)
 receiverfacts.append({'id':i,'code':selected['code'],'nameAr':selected['registryRow']['sectorAr'],'delegationCode':selected['registryRow']['delegationCode'],'currentRaw':rows[i],'nativeAreaM2':g.area,'currentPackedAreaM2':old[i].area,'oldRelativeGainM2':g.difference(old[i]).area,'oldRelativeLossM2':old[i].difference(g).area,'nativeOutsideCurrentCountryM2':g.difference(country).area,'receivesThirteenOldRelativeUncoveredLossM2':g.intersection(uncovered13).area,'receivesLargestOld156254EastLossComponentM2':g.intersection(largest).area,'nativeCachedDelegationShares':{j:g.intersection(cg).area/g.area for j,cg in contexts.items()},'nativeRepresentativeWgs84':dict(zip(('lng','lat'),iv.transform(g.representative_point().x,g.representative_point().y))),'sourceScope':selected['decreeWholeImadaScope']})
seams=[]
for i,g in two.items():
 for j,h in fifteen.items():
  if i==j or (j in two and j<i):continue
  overlap=g.intersection(h)
  if overlap.area>0:seams.append({'ids':[i,j],'nativeOverlapM2':overlap.area,'largestComponents':components(overlap,2)})
seams.sort(key=lambda r:r['nativeOverlapM2'],reverse=True)
out=D/'two-receiving-source-finite-effects.json'
result={'status':'QUALIFIED_TWO_RECEIVING_OWN_MAP_FINITE_EFFECTS_NO_IMPORT','inputs':inputs,'baselineMetadataSha256':inputs['metadata']['sha256'],'sourceIdentityAndPointFacts':pointfacts,'receivingSectors':receiverfacts,'rawRepresentativeMembershipChanges':rawchanges,'sourcePointMembershipChanges':sourcechanges,'retainedCurrentPolygonIntersections':sorted(intersections,key=lambda r:r['changeFootprintIntersectionM2'],reverse=True),'completeAffectedCurrentGroupClosure':closure,'directCurrentBindings':bindings,'relevantCurationReceipts':receipts,'actualNativeSeamsWithFrozenThirteenAndEachOther':seams,'thirteenOldCoverageComparison':{'uncoveredLossM2':uncovered13.area,'largestOld156254EastLoss':components(largest,1)[0],'bothNewReceiversCoverM2':unary_union(list(two.values())).intersection(uncovered13).area,'unreceivedRemainderM2':uncovered13.difference(unary_union(list(two.values()))).area},'preservation':{'frozenThirteenReadinessUnchanged':pin(paths['frozenThirteenReadiness'])==inputs['frozenThirteenReadiness'],'frozenThirteenPackageUnchanged':pin(paths['frozenThirteenPackage'])==inputs['frozenThirteenPackage'],'allPinnedInputsUnchanged':all(pin(paths[k])==v for k,v in inputs.items()),'noProductImportsGenerationOrAssetChanges':True},'qualifications':['Primary Baddar evidence and native Arima agree; the cached Soliman delegation footprint contradicts them and cannot be approved as its final context.','El Aitha is inside whole native Joufia, resolving the receiving-sector lead; preserve the inhabited hamlet as a distinct point.','Current timetable delegationId is a nearest prayer reference, not an administrative claim; no point or timetable field is changed here.','These are source-based membership facts and exact local effects, not a full fifteen-sector acceptance or a proposed extension of the frozen thirteen stage.','Changes relative to old OSM polygons are not themselves source defects. Native overlap seams are recorded without repair.','No additional receiving maps are fetched or inferred.'],'producer':pin(__file__)}
assert all(result['preservation'].values())
save(out,result)
print(json.dumps({'facts':pin(out),'points':pointfacts,'receivers':[{k:r[k] for k in ['id','nativeAreaM2','receivesThirteenOldRelativeUncoveredLossM2','receivesLargestOld156254EastLossComponentM2','nativeCachedDelegationShares']} for r in receiverfacts],'rawChanges':[r['currentRaw']['id'] for r in rawchanges],'sourceChanges':len(sourcechanges),'closure':len(closure),'seams':[{k:r[k] for k in ['ids','nativeOverlapM2']} for r in seams],'comparison':result['thirteenOldCoverageComparison']},ensure_ascii=False,indent=2))
