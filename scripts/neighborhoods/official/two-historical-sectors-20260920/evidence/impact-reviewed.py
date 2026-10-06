#!/usr/bin/env python3
'''READ-ONLY proposed two-sector impact projection. Writes only W/impact-facts.json and W/impact-summary.json.'''
import hashlib, json, math, struct
from pathlib import Path
from shapely import set_precision, wkt
from shapely.geometry import MultiPolygon, Point, Polygon, box, shape
from shapely.ops import transform, unary_union
from shapely.strtree import STRtree
from pyproj import Transformer

W = Path('C:/Users/barou/Documents/Codex/2026-09-06/the-android-app-app-currently-allows/work/two-historical-sector-scope-20260920').resolve()
ROOT = W.parents[1]
R = Path('C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes')
PIN = {
 'geomFacts': (W/'geometry-facts.json', '6b659d39e27c85659831a60ef2485e1f1b04d1b22b8ac761433fb0212e0f11bb'),
 'native': (W/'native-geometries.json', '342c232904d6a09517552c1b2059d22e309cb14fe35a8c8711057dab05b87429'),
 'addendum': (W/'source-scope-addendum.json', '56e2f15374b060281c256a4ed1f2151d15126918f265370c74f678752fb6af29'),
 'ettadhamen4': (W/'ettadhamen4.pdf', 'ba9d75ba467a055e07f96c810263e4502a9b3273697414303eab8edb19d11c38'),
 'hammam1': (W/'hammam-maarouf-riadh1.pdf', '974ebe24f0dee0a2701db768d6dd160554c9a492c5601b2978df6c710ad81025'),
 'hammam2': (W/'hammam-maarouf-riadh2.pdf', '377144831315b1197c2f4aefdc0f4aee2bf5db4ba20d7c99a24c401d70ff7eaf'),
 'ins2012': (ROOT/'work/official-codes/ins-2012.pdf', 'd11dc600430ffc31fb2bd50f903c2037b9337b52d6bc68b81778c3da9319b580'),
 'identity': (ROOT/'work/remaining-shortlist-source-review-20260920/seven-case-facts.json', 'def5ad67cdc8ccf0f132756c635d6f3b6841c2077bfecacc53938ea538379760'),
 'indexSrc': (ROOT/'work/five-held-local-official-evidence-20260920/isie-exact-parent-subsets.json', 'd006f86495628a407fd99e92b144243d159ba1473d38b9760eb6e67185bd29fe'),
 'index': (ROOT/'work/current-official-catalog/isie-local-boundary-index-links.json', '44fddae0a73f306f3f61dc305d8e27db51e83d57a1ff335fdad60aec5ead2651'),
 'meta': (R/'android-app/app/src/main/assets/neighborhoods.json', 'd396925c0a7adb85010fb1a2b3f839ffcea939bc12b59a0da6c04d23c57d79a4'),
 'bin': (R/'android-app/app/src/main/assets/neighborhoods.bin', 'bab9876668eb53f9fd27992242575b031acaa74c3f4e36cca7842289164fe444'),
 'coverage': (R/'scripts/neighborhoods/coverage.json', '82569c1f8111819ffc01090dbdee6ab9b21a0687ee940310829ffbba5532321a'),
 'generator': (R/'scripts/generate_neighborhoods.py', '722c90af0739d79714edb4df78cbfb38a08aa4801bf343113e0d3f74533ab50c'),
 'areas': (ROOT/'work/geo/osm-areas.json', '346a79dca9013b427cf7ce4e11fb31ca03fa575ee7a68c865701c463c504a1ce'),
}
P = {k:v[0] for k,v in PIN.items()}
TARGETS = {
 'osm:relation:7115584': {'oldName':'7 نوفمبر','newName':'التضامن 4','oldAliases':['7 Novembre']},
 'osm:relation:7113315': {'oldName':'العهد الجديد','newName':'حمام معروف الرياض','oldAliases':['El Ahd El Jadid']},
}
EXACT_PARENTS = {
 'osm:relation:7115584': {'parentId':'osm:relation:4156579','governorateId':'osm:relation:1435830','officialOutsideOriginalParentM2':405.4674913191327,'officialOutsideOriginalGovernorateM2':402.0129208057862},
 'osm:relation:7113315': {'parentId':'osm:relation:7150108','governorateId':'osm:relation:3152094','officialOutsideOriginalParentM2':312599.3935339133,'officialOutsideOriginalGovernorateM2':0.0},
}
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def load_json(p): return json.loads(p.read_text(encoding='utf-8'))
def check_pins():
 out={}
 for k,(p,h) in PIN.items():
  got=sha(p)
  assert got==h,(k,got,h)
  out[k]={'file':p.resolve().as_posix(),'sha256':got,'bytes':p.stat().st_size}
 return out
def find_native(doc,tid):
 if tid=='osm:relation:7115584': return shape(doc['ettadhamen4.pdf'])
 assert tid=='osm:relation:7113315'
 return unary_union([shape(doc['hammam-maarouf-riadh1.pdf']),shape(doc['hammam-maarouf-riadh2.pdf'])])

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
FW=Transformer.from_crs(4326,32632,always_xy=True)
def utm(g): return transform(FW.transform,g)
def km2(g): return g.area/1e6
def polygon_parts(geometry):
 if isinstance(geometry,Polygon): return [geometry]
 return [part for member in getattr(geometry,'geoms',[]) for part in polygon_parts(member)]
def conflict_record(a,b,id1,id2):
 overlap=a.intersection(b)
 if overlap.is_empty or overlap.area<=0: return None
 point=next((p for part in sorted(polygon_parts(overlap),key=lambda x:(-x.area,x.bounds)) for p in [part.representative_point()] if a.contains(p) and b.contains(p)),None)
 if point is None: raise ValueError(f'No strictly interior conflict sample for {id1} / {id2}')
 return {'ids':sorted([id1,id2]),'reason':'overlapping_sectors','intersectionKm2':overlap.area*111.32**2*math.cos(math.radians(point.y)),'sample':{'lat':point.y,'lng':point.x}}
PINS=check_pins()
META=load_json(P['meta'])
ROWS=META['features']
assert len(ROWS)==3523,len(ROWS)
ROW_BY_ID={r['id']:r for r in ROWS}
BROWS=[r for r in ROWS if r.get('hasBoundary')]
assert len(BROWS)==2619,len(BROWS)
BLOB=P['bin'].read_bytes()
SCALE=META['coordinateScale']
CACHE=load_json(P['areas'])
AREAS={r['id']:r for r in CACHE['areas']}
SOURCE_NODES={r['id']:r for r in CACHE['nodes']}
NATIVE_DOC=load_json(P['native'])
OLD_WGS={}; NEW_WGS={}; ANCHORS={}
for tid,cfg in TARGETS.items():
 row=ROW_BY_ID[tid]
 assert row.get('hasBoundary')
 old=unpack(row); new=find_native(NATIVE_DOC,tid)
 assert new.is_valid and not new.is_empty and new.geom_type in ('Polygon','MultiPolygon')
 anchor=Point(row['lng'],row['lat'])
 assert old.covers(anchor),(tid,'old target does not cover current anchor')
 assert new.covers(anchor),(tid,'new native target does not cover current anchor')
 OLD_WGS[tid]=old; NEW_WGS[tid]=new
 ANCHORS[tid]={'lat':row['lat'],'lng':row['lng'],'oldCovers':True,'newCovers':True}
OLD_UTM={tid:utm(g) for tid,g in OLD_WGS.items()}
NEW_UTM={tid:utm(g) for tid,g in NEW_WGS.items()}
CURRENT_GEOMS={r['id']:unpack(r) for r in BROWS}
INTERSECTIONS={}; COVERAGE={}
for tid in TARGETS:
 old=OLD_UTM[tid]; new=NEW_UTM[tid]
 gain=new.difference(old); loss=old.difference(new)
 oldbb=box(*OLD_WGS[tid].bounds); newbb=box(*NEW_WGS[tid].bounds)
 recs=[];local_all=[];local_sectors=[]
 for row in BROWS:
  if row['id']==tid: continue
  rb=box(*row['bbox'])
  if not (rb.intersects(oldbb) or rb.intersects(newbb)): continue
  g=utm(CURRENT_GEOMS[row['id']])
  local_all.append(g)
  if row['kind']=='sector':local_sectors.append(g)
  recs.append({'id':row['id'],'name':row.get('name'),'kind':row.get('kind'),'beforeM2':g.intersection(old).area,'afterM2':g.intersection(new).area,'gainM2':g.intersection(gain).area,'lossM2':g.intersection(loss).area})
 INTERSECTIONS[tid]=recs
 OTHER_ALL_U=unary_union(local_all);OTHER_SECTORS_U=unary_union(local_sectors)
 COVERAGE[tid]={'oldAreaKm2':km2(old),'newAreaKm2':km2(new),'gainKm2':km2(gain),'lossKm2':km2(loss),'oldOutsideOtherSectorsKm2':km2(old.difference(OTHER_SECTORS_U)),'oldOutsideAllRemainingPolygonsKm2':km2(old.difference(OTHER_ALL_U)),'newOutsideOtherSectorsKm2':km2(new.difference(OTHER_SECTORS_U)),'newOutsideAllRemainingPolygonsKm2':km2(new.difference(OTHER_ALL_U)),'lossNotCoveredByAnyRemainingPolygonKm2':km2(loss.difference(OTHER_ALL_U)),'lossNotCoveredByOtherSectorKm2':km2(loss.difference(OTHER_SECTORS_U)),'gainAlreadyClaimedByAllRemainingPolygonsKm2':km2(gain.intersection(OTHER_ALL_U)),'gainAlreadyClaimedByOtherSectorsKm2':km2(gain.intersection(OTHER_SECTORS_U)),'potentialNewGap':'lossNotCoveredByAnyRemainingPolygonKm2','possiblePeerConflict':'gainAlreadyClaimedByOtherSectorsKm2','nonPeerOverlapIsNotAConflict':True}
SECTOR_ROWS=[r for r in BROWS if r.get('kind')=='sector']
SECTOR_GEOMS=[set_precision(unpack(r),0) for r in SECTOR_ROWS]
TREE=STRtree(SECTOR_GEOMS)
def incident_conflicts(a,tid):
 out=[]
 for j in sorted(int(i) for i in TREE.query(a)):
  row=SECTOR_ROWS[j]
  if row['id']==tid: continue
  rec=conflict_record(a,SECTOR_GEOMS[j],tid,row['id'])
  if rec: out.append(rec)
 return sorted(out,key=lambda c:c['ids'])
OLD_CONFLICTS={tid:incident_conflicts(set_precision(OLD_WGS[tid],0),tid) for tid in TARGETS}
AFTER_CONFLICTS={tid:incident_conflicts(set_precision(NEW_WGS[tid],0),tid) for tid in TARGETS}
STORED_INCIDENT=[c for c in (META.get('conflicts') or []) if any(t in c.get('ids',[]) for t in TARGETS)]
STORED_SETS={tuple(sorted(c['ids'])) for c in STORED_INCIDENT}
COMPUTED_SETS={tuple(sorted(c['ids'])) for recs in OLD_CONFLICTS.values() for c in recs}
assert STORED_SETS==COMPUTED_SETS,('stored incident conflict id sets differ',STORED_SETS,COMPUTED_SETS)
MEMBERSHIP={}
for tid in TARGETS:
 changes=[]
 for row in ROWS:
  pt=Point(row['lng'],row['lat']); before=OLD_WGS[tid].covers(pt); after=NEW_WGS[tid].covers(pt)
  if before!=after: changes.append({'id':row['id'],'name':row.get('name'),'kind':row.get('kind'),'beforeInside':before,'afterInside':after})
 MEMBERSHIP[tid]=changes
def replaces(s,pairs):
 out=s
 for old,new in pairs: out=out.replace(old,new)
 return out
OLD_NAME_ROWS=[]
for row in ROWS:
 hits=[]; parent=row.get('parentName','') or ''; aliases=row.get('contextAliases',[]) or []
 for tid,cfg in TARGETS.items():
  names=[cfg['oldName']]+list(cfg['oldAliases'])
  if any(n in parent for n in names) or any(any(n in a for n in names) for a in aliases): hits.append(tid)
 if not hits: continue
 pairs=[]
 for tid in hits:
  cfg=TARGETS[tid]; pairs.append((cfg['oldName'],cfg['newName']))
 proposed_parent=replaces(parent,pairs); proposed_aliases=sorted({replaces(a,pairs) for a in aliases})
 per_target={}
 for tid in hits:
  old=OLD_WGS[tid]; new=NEW_WGS[tid]; pt=Point(row['lng'],row['lat']); oldu=OLD_UTM[tid]; newu=NEW_UTM[tid]
  item={'targetId':tid,'currentPointContainedOld':old.covers(pt),'currentPointContainedNew':new.covers(pt)}
  if row.get('hasBoundary'):
   gu=utm(unpack(row))
   item['currentPackedFootprint']={'coveredByOld':oldu.covers(gu),'coveredByNew':newu.covers(gu),'intersectionOldM2':gu.intersection(oldu).area,'intersectionNewM2':gu.intersection(newu).area,'wholeGeometryCrossesUnresolved':(gu.intersects(oldu) or gu.intersects(newu)) and not (oldu.covers(gu) and newu.covers(gu))}
  src=AREAS.get(row['id'])
  if src is None and row['id'] in SOURCE_NODES:
   sn=SOURCE_NODES[row['id']];src={'geometry':{'type':'Point','coordinates':[sn['lng'],sn['lat']]}}
  if src and isinstance(src.get('geometry'),dict):
   sg=shape(src['geometry'])
   if isinstance(sg,Point): item['source']={'geometryType':'Point','insideOld':old.covers(sg),'insideNew':new.covers(sg)}
   else:
    sgu=utm(sg)
    item['source']={'geometryType':sg.geom_type,'coveredByOld':oldu.covers(sgu),'coveredByNew':newu.covers(sgu),'intersectionOldM2':sgu.intersection(oldu).area,'intersectionNewM2':sgu.intersection(newu).area}
  per_target[tid]=item
 OLD_NAME_ROWS.append({'currentRaw':row,'matchingTargetIds':hits,'matchingParentName':parent,'matchingContextAliases':aliases,'proposedParentName':proposed_parent,'proposedContextAliases':proposed_aliases,'requiresFinalCurationAliasPolicy':True,'arabicContextOnlyProposedFrenchLegacyAliasPreservedForAssessment':True,'mutatedCurrentRow':False,'containment':per_target,'representativePointAloneNotGenerationProof':True})
NEEDED_IDS=set()
for cfg in EXACT_PARENTS.values(): NEEDED_IDS.add(cfg['parentId']); NEEDED_IDS.add(cfg['governorateId'])
PARENT_GOV={}
for tid,cfg in EXACT_PARENTS.items():
 PARENT_GOV[tid]={}
 for role,gid,expected in (('parent',cfg['parentId'],cfg['officialOutsideOriginalParentM2']),('governorate',cfg['governorateId'],cfg['officialOutsideOriginalGovernorateM2'])):
  src=AREAS.get(gid)
  if not src: PARENT_GOV[tid][role]={'id':gid,'missing':True}; continue
  gu=utm(shape(src['geometry']))
  PARENT_GOV[tid][role]={'id':gid,'tags':src.get('tags'),'compactExpectedOfficialOutsideOriginalM2':expected,'oldPackedOutsideM2':OLD_UTM[tid].difference(gu).area,'newNativeOutsideM2':NEW_UTM[tid].difference(gu).area,'newNativeMinusCompactExpectedM2':NEW_UTM[tid].difference(gu).area-expected}
AFFECTED=set(TARGETS)
for recs in INTERSECTIONS.values():
 for rec in recs:
  if rec['gainM2']>0 or rec['lossM2']>0 or rec['beforeM2']!=rec['afterM2']: AFFECTED.add(rec['id'])
for changes in MEMBERSHIP.values():
 for c in changes: AFFECTED.add(c['id'])
for rec in OLD_NAME_ROWS: AFFECTED.add(rec['currentRaw']['id'])
for recs in OLD_CONFLICTS.values():
 for c in recs: AFFECTED.update(c['ids'])
for recs in AFTER_CONFLICTS.values():
 for c in recs: AFFECTED.update(c['ids'])
AFFECTED.update(NEEDED_IDS)
GROUPS=sorted({ROW_BY_ID[i].get('pickerGroupId') for i in AFFECTED if i in ROW_BY_ID and ROW_BY_ID[i].get('pickerGroupId')})
GROUP_CLOSURE=[r for r in ROWS if r.get('pickerGroupId') in GROUPS]
PROPOSAL={'targetIds':list(TARGETS),'changes':{tid:{'oldName':cfg['oldName'],'newName':cfg['newName'],'oldAliases':cfg['oldAliases'],'newGeometrySource':{'file':P['native'].resolve().as_posix(),'sha256':PIN['native'][1]},'currentAnchorPreserved':True,'currentRawRowUnchanged':True} for tid,cfg in TARGETS.items()},'onlyTheseTwoGeometryNameValuesProposed':True,'allOtherRowsUnchanged':True,'allOtherPolygonsUnchanged':True}
FACTS={'status':'READ_ONLY_PROPOSED_TWO_SECTOR_IMPACT_FACTS','analysisOnly':True,'networkDevicesApiTestsApkBuildControlLedgerProductionWrites':False,'inputs':PINS,'targets':TARGETS,'proposal':PROPOSAL,'anchors':ANCHORS,'targetResults':{tid:{'coverage':COVERAGE[tid],'intersectionRecordCount':len(INTERSECTIONS[tid]),'oldIncidentConflicts':OLD_CONFLICTS[tid],'afterPrepackingProjectionConflicts':AFTER_CONFLICTS[tid],'parentGovernorateDiscrepancy':PARENT_GOV[tid]} for tid in TARGETS},'intersectionRecords':INTERSECTIONS,'storedIncidentConflictComparison':{'storedIncidentIds':sorted(STORED_SETS),'oldComputedIncidentIds':sorted(COMPUTED_SETS),'idSetsMatch':True},'currentRawRepresentativeMembershipChanges':MEMBERSHIP,'oldNameReferenceRows':OLD_NAME_ROWS,'pickerGroupClosure':{'groups':GROUPS,'rows':GROUP_CLOSURE,'groupsMergedOrRetired':[],'pickerGroupsUnchanged':True},'qualifications':{'afterConflictsAndIntersections':'prepacking projection using untouched native geometry against current packed peers; not an exact generated/packed app prediction','wholeOsmExtentUnchanged':False,'legalWholeImadaEquivalence':False,'cachedIndexSameProviderEvidenceOnly':True,'nativePartSeamM2':288.3215669586554,'noSnapQuantizeBufferClipRepair':True,'representativePointAloneNotGenerationProof':True}}
OUT_FACTS=W/'impact-facts.json'; OUT_SUMMARY=W/'impact-summary.json'
assert OUT_FACTS.parent.resolve()==W and OUT_SUMMARY.parent.resolve()==W
if OUT_FACTS.exists() or OUT_SUMMARY.exists(): raise SystemExit('refusing to overwrite existing output')
SUMMARY={'status':FACTS['status'],'factsFile':OUT_FACTS.resolve().as_posix(),'summaryFile':OUT_SUMMARY.resolve().as_posix(),'counts':{'currentRawRows':len(ROWS),'currentBoundaryRows':len(BROWS),'intersectionRecords':sum(len(v) for v in INTERSECTIONS.values()),'oldIncidentConflicts':sum(len(v) for v in OLD_CONFLICTS.values()),'afterPrepackingProjectionConflicts':sum(len(v) for v in AFTER_CONFLICTS.values()),'currentRawMembershipChanges':sum(len(v) for v in MEMBERSHIP.values()),'oldNameReferenceRows':len(OLD_NAME_ROWS),'pickerGroups':len(GROUPS),'pickerClosureRows':len(GROUP_CLOSURE)},'metrics':{tid:COVERAGE[tid] for tid in TARGETS},'qualification':FACTS['qualifications']['afterConflictsAndIntersections']}
check_pins()
OUT_FACTS.write_text(json.dumps(FACTS,ensure_ascii=False,indent=2)+chr(10),encoding='utf-8')
OUT_SUMMARY.write_text(json.dumps(SUMMARY,ensure_ascii=False,indent=2)+chr(10),encoding='utf-8')
check_pins()
print(json.dumps(SUMMARY,ensure_ascii=False,indent=2))
