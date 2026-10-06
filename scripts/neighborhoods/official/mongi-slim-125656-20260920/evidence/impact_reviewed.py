#!/usr/bin/env python3
'''READ-ONLY one-sector impact projection. Writes only W/impact-facts.json and W/impact-summary.json.'''
import hashlib, json, struct
from pathlib import Path
from shapely import set_precision
from shapely.geometry import GeometryCollection, MultiPolygon, Point, Polygon, box, shape
from shapely.ops import transform, unary_union
from pyproj import Transformer
W=Path('C:/Users/barou/Documents/Codex/2026-09-06/the-android-app-app-currently-allows/work/remaining-mongi-teboulba-20260920').resolve()
R=Path('C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes').resolve()
CFG=json.loads((W/'package-config.json').read_text(encoding='utf-8'))
assert Path(CFG['workRoot']).resolve()==W
assert Path(CFG['repoRoot']).resolve()==R
TARGET=CFG['targetId']
def sha(p): return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def load_json(p): return json.loads(Path(p).read_text(encoding='utf-8'))
PIN={k:(Path(v['file']).resolve(),v['sha256']) for k,v in CFG['pins'].items()}
def check_pins():
 out={}
 for k,(p,h) in PIN.items():
  got=sha(p); assert got==h,(k,got,h,p.as_posix())
  out[k]={'file':p.as_posix(),'sha256':got,'bytes':p.stat().st_size}
 return out
PINS=check_pins()
META=load_json(PIN['metadata'][0])
ROWS=META['features']; ROW_BY_ID={r['id']:r for r in ROWS}; TROW=ROW_BY_ID[TARGET]
for k,v in CFG['currentRaw'].items(): assert TROW.get(k)==v,(k,TROW.get(k),v)
assert TROW.get('hasBoundary') is True
BROWS=[r for r in ROWS if r.get('hasBoundary')]
BLOB=PIN['binary'][0].read_bytes(); SCALE=META['coordinateScale']; assert SCALE==1000000
DIAG=load_json(PIN['native'][0]); assert DIAG['targetId']==TARGET
assert Path(DIAG['source']['file']).resolve()==PIN['pdf'][0]
assert DIAG['source']['sha256']==PIN['pdf'][1]
assert Path(DIAG['receipt']['file']).resolve()==PIN['receipt'][0]
assert DIAG['receipt']['sha256']==PIN['receipt'][1]
NATIVE=shape(DIAG['nativeGeometryWgs84']); assert NATIVE.is_valid and not NATIVE.is_empty and NATIVE.geom_type in ('Polygon','MultiPolygon')
ROUNDED=set_precision(NATIVE,1/SCALE); assert ROUNDED.is_valid and not ROUNDED.is_empty and ROUNDED.geom_type in ('Polygon','MultiPolygon')
FW=Transformer.from_crs(4326,32632,always_xy=True); BW=Transformer.from_crs(32632,4326,always_xy=True)
def utm(g): return transform(FW.transform,g)
def wgs(g): return transform(BW.transform,g)
def unpack(row):
 raw=BLOB[row['offset']:row['offset']+row['length']]; pos=0
 def take():
  nonlocal pos
  n=struct.unpack_from('>i',raw,pos)[0]; pos+=4; return n
 polys=[]
 for _ in range(take()):
  rings=[]
  for _ in range(take()):
   pts=[(take()/SCALE,take()/SCALE) for _ in range(take())]
   rings.append(pts)
  polys.append(Polygon(rings[0],rings[1:]))
 assert pos==len(raw),(row['id'],pos,len(raw))
 return MultiPolygon(polys)
OLD=unpack(TROW); assert OLD.is_valid and not OLD.is_empty
OLD_U=utm(OLD); NATIVE_U=utm(NATIVE); ROUNDED_U=utm(ROUNDED)
def pair_metrics(a,b):
 inter=a.intersection(b); union=a.union(b); aa=a.area; bb=b.area; ia=inter.area; ua=union.area
 return {'aAreaKm2':aa/1e6,'bAreaKm2':bb/1e6,'intersectionKm2':ia/1e6,'iou':(ia/ua) if ua else 0.0,'aCoveredByB':(ia/aa) if aa else 0.0,'bCoveredByA':(ia/bb) if bb else 0.0,'aOutsideBKm2':a.difference(b).area/1e6,'bOutsideAKm2':b.difference(a).area/1e6,'hausdorffMeters':float(a.hausdorff_distance(b)),'centroidDistanceMeters':float(a.centroid.distance(b.centroid))}
def polygon_parts(g):
 if isinstance(g,Polygon): return [g]
 return [part for member in getattr(g,'geoms',[]) for part in polygon_parts(member)]
def coverage_for(name,new_u,new_wgs):
 gain=new_u.difference(OLD_U); loss=OLD_U.difference(new_u); oldbb=box(*OLD.bounds); newbb=box(*new_wgs.bounds); recs=[]; local_all=[]; local_sectors=[]
 for row in BROWS:
  if row['id']==TARGET: continue
  rb=box(*row['bbox'])
  if not (rb.intersects(oldbb) or rb.intersects(newbb)): continue
  g=utm(unpack(row)); local_all.append(g)
  if row.get('kind')=='sector': local_sectors.append(g)
  recs.append({'id':row['id'],'name':row.get('name'),'kind':row.get('kind'),'beforeM2':float(g.intersection(OLD_U).area),'afterM2':float(g.intersection(new_u).area),'gainM2':float(g.intersection(gain).area),'lossM2':float(g.intersection(loss).area)})
 other_all=unary_union(local_all) if local_all else GeometryCollection()
 other_sectors=unary_union(local_sectors) if local_sectors else GeometryCollection()
 cov={'variant':name,'oldAreaKm2':float(OLD_U.area/1e6),'variantAreaKm2':float(new_u.area/1e6),'gainKm2':float(gain.area/1e6),'lossKm2':float(loss.area/1e6),'oldOutsideOtherSectorsKm2':float(OLD_U.difference(other_sectors).area/1e6),'oldOutsideAllRemainingPolygonsKm2':float(OLD_U.difference(other_all).area/1e6),'variantOutsideOtherSectorsKm2':float(new_u.difference(other_sectors).area/1e6),'variantOutsideAllRemainingPolygonsKm2':float(new_u.difference(other_all).area/1e6),'lossNotCoveredByAnyRemainingPolygonKm2':float(loss.difference(other_all).area/1e6),'lossNotCoveredByOtherSectorKm2':float(loss.difference(other_sectors).area/1e6),'gainAlreadyClaimedByAllRemainingPolygonsKm2':float(gain.intersection(other_all).area/1e6),'gainAlreadyClaimedByOtherSectorsKm2':float(gain.intersection(other_sectors).area/1e6),'potentialNewGap':'lossNotCoveredByAnyRemainingPolygonKm2','possiblePeerConflict':'gainAlreadyClaimedByOtherSectorsKm2','nonPeerOverlapIsNotAConflict':True}
 return cov,recs
NATIVE_COVERAGE,NATIVE_INTERSECTIONS=coverage_for('native',NATIVE_U,NATIVE)
ROUNDED_COVERAGE,ROUNDED_INTERSECTIONS=coverage_for('rounded',ROUNDED_U,ROUNDED)
target_union=box(min(OLD.bounds[0],NATIVE.bounds[0],ROUNDED.bounds[0]),min(OLD.bounds[1],NATIVE.bounds[1],ROUNDED.bounds[1]),max(OLD.bounds[2],NATIVE.bounds[2],ROUNDED.bounds[2]),max(OLD.bounds[3],NATIVE.bounds[3],ROUNDED.bounds[3]))
CONFLICTS=[]
for row in BROWS:
 if row['id']==TARGET or row.get('kind')!='sector': continue
 if not box(*row['bbox']).intersects(target_union): continue
 peer_wgs=unpack(row); oi=utm(peer_wgs.intersection(OLD)); ni=utm(peer_wgs.intersection(NATIVE)); ri=utm(peer_wgs.intersection(ROUNDED))
 om=float(oi.area); nm=float(ni.area); rm=float(ri.area)
 if max(om,nm,rm)<=0: continue
 inter=max([x for x in (oi,ni,ri) if not x.is_empty and x.area>0],key=lambda x:x.area)
 pt=wgs(max(polygon_parts(inter),key=lambda x:x.area).representative_point())
 CONFLICTS.append({'peerId':row['id'],'peerName':row.get('name'),'oldM2':om,'nativeM2':nm,'roundedM2':rm,'oldKm2':om/1e6,'nativeKm2':nm/1e6,'roundedKm2':rm/1e6,'nativeMinusOldM2':nm-om,'roundedMinusNativeM2':rm-nm,'roundedMinusOldM2':rm-om,'sample':{'lat':float(pt.y),'lng':float(pt.x)}})
CONFLICTS=sorted(CONFLICTS,key=lambda x:x['peerId'])
stored=[c for c in (META.get('conflicts') or []) if TARGET in (c.get('ids') or [])]
stored_sets={tuple(sorted(c['ids'])) for c in stored}
computed_old_sets={tuple(sorted([TARGET,c['peerId']])) for c in CONFLICTS if c['oldM2']>0}
assert stored_sets==computed_old_sets,('stored incident conflict id sets differ',stored_sets,computed_old_sets)
AREAS_DOC=load_json(PIN['areas'][0]); AREAS={r['id']:r for r in AREAS_DOC['areas']}
assert AREAS[TARGET]['tags']==CFG['originalSource']['tags']
ORIGINAL=shape(AREAS[TARGET]['geometry']); ORIGINAL_U=utm(ORIGINAL)
parent_id=CFG['parentId']; gov_id=CFG['governorateId']; assert parent_id in AREAS and gov_id in AREAS
PARENT={'id':parent_id,'tags':AREAS[parent_id].get('tags'),'comparisons':{name:pair_metrics(g,utm(shape(AREAS[parent_id]['geometry']))) for name,g in [('original',ORIGINAL_U),('currentPacked',OLD_U),('native',NATIVE_U),('rounded',ROUNDED_U)]}}
GOV={'id':gov_id,'tags':AREAS[gov_id].get('tags'),'comparisons':{name:pair_metrics(g,utm(shape(AREAS[gov_id]['geometry']))) for name,g in [('original',ORIGINAL_U),('currentPacked',OLD_U),('native',NATIVE_U),('rounded',ROUNDED_U)]}}
CHANGES=[]
for row in ROWS:
 pt=Point(row['lng'],row['lat']); b=OLD.covers(pt); n=NATIVE.covers(pt); r=ROUNDED.covers(pt)
 if not (b==n==r): CHANGES.append({'id':row['id'],'name':row.get('name'),'kind':row.get('kind'),'parentName':row.get('parentName'),'pickerGroupId':row.get('pickerGroupId'),'beforeInside':b,'afterNativeInside':n,'afterRoundedInside':r})
DEP_NAMES={CFG['currentRaw']['name']}|set(CFG['currentRaw'].get('aliases') or [])|{CFG['sourceNamePolicy']['nameAr'],CFG['sourceNamePolicy']['nameFr']}
DEPS=[]
for row in ROWS:
 if row['id']==TARGET: continue
 if row.get('parentName') in DEP_NAMES or any(a in DEP_NAMES for a in (row.get('contextAliases') or [])):
  DEPS.append({'id':row['id'],'name':row.get('name'),'kind':row.get('kind'),'parentName':row.get('parentName'),'contextAliases':row.get('contextAliases'),'pickerGroupId':row.get('pickerGroupId')})
AFFECTED={TARGET,parent_id,gov_id}
for recs in (NATIVE_INTERSECTIONS,ROUNDED_INTERSECTIONS):
 for rec in recs:
  if rec['gainM2']>0 or rec['lossM2']>0 or rec['beforeM2']!=rec['afterM2']: AFFECTED.add(rec['id'])
for c in CONFLICTS: AFFECTED.add(c['peerId'])
for c in CHANGES: AFFECTED.add(c['id'])
for d in DEPS: AFFECTED.add(d['id'])
GROUPS=sorted({ROW_BY_ID[i].get('pickerGroupId') for i in AFFECTED if i in ROW_BY_ID and ROW_BY_ID[i].get('pickerGroupId')})
GROUP_CLOSURE=[r for r in ROWS if r.get('pickerGroupId') in GROUPS]
FACTS={'status':'READ_ONLY_ONE_SOURCE_IMPACT_FACTS','analysisOnly':True,'networkDevicesApiTestsApkBuildControlLedgerProductionWrites':False,'targetId':TARGET,'officialCode':CFG['officialCode'],'sourceId':CFG['sourceId'],'inputs':PINS,'targetCurrentRaw':TROW,'nativeDiagnostic':{'file':PIN['native'][0].as_posix(),'sha256':PIN['native'][1],'drawingIndex':DIAG['drawingIndex'],'source':DIAG['source'],'receipt':DIAG['receipt']},'comparisons':{'packedVsNative':pair_metrics(OLD_U,NATIVE_U),'packedVsRounded':pair_metrics(OLD_U,ROUNDED_U),'nativeVsRounded':pair_metrics(NATIVE_U,ROUNDED_U)},'coverage':{'packedVsNative':NATIVE_COVERAGE,'packedVsRounded':ROUNDED_COVERAGE},'intersectionRecords':{'packedVsNative':NATIVE_INTERSECTIONS,'packedVsRounded':ROUNDED_INTERSECTIONS},'incidentSectorConflicts':CONFLICTS,'storedIncidentConflictComparison':{'storedRecords':stored,'computedOldPairIdSets':sorted([list(x) for x in computed_old_sets]),'storedPairIdSets':sorted([list(x) for x in stored_sets]),'pairIdSetsMatch':True},'parentGovernorateOriginalSourceDiscrepancy':{'target':{'id':TARGET,'tags':AREAS[TARGET].get('tags')},'parent':PARENT,'governorate':GOV},'rawRepresentativeContainmentChanges':CHANGES,'currentChildContextDependencies':DEPS,'pickerGroupClosure':{'groups':GROUPS,'rows':GROUP_CLOSURE,'groupsMergedOrRetired':[],'pickerGroupsUnchanged':True},'proposedNameOrContextEdits':[],'noProposedNameContextEdits':True,'qualifications':{'afterConflictsAndIntersections':'Prepacking projection using untouched native geometry against current packed peers; not an exact generated/packed app prediction.','roundedProjection':'Separate normal app set_precision(1e-6) projection; not the native source artifact.','noSnapQuantizeBufferClipRepairForNativeSource':True,'allCatalogInvariantClaimed':False,'incidentPairDefinition':'Positive-area intersection in original straight lon/lat geometry, then projected to UTM for areas; prevents projection-induced changes in pair membership.','combinedGenerationLaterValidatesActualEffects':True,'representativePointAloneNotGenerationProof':True}}
OUT_FACTS=W/'impact-facts.json'; OUT_SUMMARY=W/'impact-summary.json'; assert OUT_FACTS.parent.resolve()==W and OUT_SUMMARY.parent.resolve()==W
if OUT_FACTS.exists() or OUT_SUMMARY.exists(): raise SystemExit('refusing to overwrite existing output')
SUMMARY={'status':FACTS['status'],'targetId':TARGET,'factsFile':OUT_FACTS.resolve().as_posix(),'summaryFile':OUT_SUMMARY.resolve().as_posix(),'counts':{'currentRawRows':len(ROWS),'currentBoundaryRows':len(BROWS),'intersectionRecordsNative':len(NATIVE_INTERSECTIONS),'intersectionRecordsRounded':len(ROUNDED_INTERSECTIONS),'incidentSectorConflicts':len(CONFLICTS),'storedIncidentConflicts':len(stored),'rawRepresentativeContainmentChanges':len(CHANGES),'currentChildContextDependencies':len(DEPS),'pickerGroups':len(GROUPS),'pickerClosureRows':len(GROUP_CLOSURE)},'comparisons':FACTS['comparisons'],'coverage':FACTS['coverage'],'qualification':FACTS['qualifications']['afterConflictsAndIntersections']}
check_pins()
OUT_FACTS.write_text(json.dumps(FACTS,ensure_ascii=False,indent=2)+chr(10),encoding='utf-8')
OUT_SUMMARY.write_text(json.dumps(SUMMARY,ensure_ascii=False,indent=2)+chr(10),encoding='utf-8')
check_pins()
print(json.dumps(SUMMARY,ensure_ascii=False,indent=2))
