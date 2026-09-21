"""Read-only joint replacement impact; native corrections to the bounded worker draft."""
import ast, hashlib, json, math, struct
from pathlib import Path
from shapely import set_precision
from shapely.geometry import Polygon, MultiPolygon, Point, shape, mapping
from shapely.ops import transform, unary_union
from shapely.strtree import STRtree
from pyproj import Transformer

W=Path(__file__).resolve().parent;F=W/'frozen'
TARGETS={'osm:relation:7189452':'zahret-medien','osm:relation:7189451':'zahret-medien-sud'}
SCALE=1000000
PLACES={'neighbourhood','quarter','suburb','city_district','village','hamlet','town'}
FINE_KINDS={'neighbourhood','quarter','suburb','city_district','residential','subdistrict','locality'}
def read(p):return json.loads(Path(p).read_text(encoding='utf-8'))
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def pin(p):return {'file':str(Path(p).resolve()),'sha256':sha(p),'bytes':Path(p).stat().st_size}
def save(name,data):
 with (W/name).open('x',encoding='utf-8',newline='\n') as f:json.dump(data,f,ensure_ascii=False,indent=2);f.write('\n')
assert sha(W/'frozen-context.json')=='92e77236546e2a82dda4b65fbeb53d624e1d87f28c0287a38232db6ebd88be30'
CTX=read(W/'frozen-context.json')
PINS={Path(r['snapshot']):r['sha256'] for r in CTX['readOnlyInputs']}
PINS.update({W/'frozen-context.json':'92e77236546e2a82dda4b65fbeb53d624e1d87f28c0287a38232db6ebd88be30',F/'source-targets-and-parents.json':'be9828dc3b3126235b317e3a7e83c8729f762e17d1cd3ceb2dd34e3f2a08f322',W/'decree-scope-addendum.json':'ca3711e4ccde56dabcd9c520fa5b62785379cf74cc03dc95233da1514dd01da4'})
def check_pins():
 for path,digest in PINS.items():assert sha(path)==digest,str(path)
 for row in CTX['readOnlyInputs']:assert sha(row['file'])==row['sha256'],row['file']
check_pins()
META=read(F/'neighborhoods.json');ROWS=META['features'];BYID={r['id']:r for r in ROWS};BROWS=[r for r in ROWS if r['hasBoundary']];BLOB=(F/'neighborhoods.bin').read_bytes()
assert len(ROWS)==3523 and len(BROWS)==2619 and BLOB[:8]==b'NPOL'+struct.pack('>i',1)
def unpack(row):
 raw=BLOB[row['offset']:row['offset']+row['length']];pos=0
 def take():
  nonlocal pos
  value=struct.unpack_from('>i',raw,pos)[0];pos+=4;return value
 polygons=[]
 for _ in range(take()):
  rings=[]
  for _ in range(take()):rings.append([(take()/SCALE,take()/SCALE) for _ in range(take())])
  polygons.append(Polygon(rings[0],rings[1:]))
 assert pos==len(raw)
 return polygons[0] if len(polygons)==1 else MultiPolygon(polygons)
OLD={r['id']:unpack(r) for r in BROWS};NATIVE=read(F/'prior-native-geometries.json');NEW={i:shape(NATIVE[key]) for i,key in TARGETS.items()}
COUNTRY=unpack(META['country']);Q={i:set_precision(g,1/SCALE) for i,g in NEW.items()}
for i in TARGETS:
 assert NEW[i].is_valid and not NEW[i].is_empty and COUNTRY.covers(NEW[i])
 assert Q[i].is_valid and not Q[i].is_empty and COUNTRY.covers(Q[i])
 assert all(g.covers(Point(BYID[i]['lng'],BYID[i]['lat'])) for g in [OLD[i],NEW[i],Q[i]])
for key in ['zahret-medien','zahret-medien-sud']:
 row=next(r for r in read(F/'prior-geometry-facts.json')['records'] if r['slug']==key)
 assert row['nativeGeometryValid'] and row['nativeContinuousExplicitlyClosed'] and row['fullyInsideViewport']
 assert len(row['activeClipChecks'])==2 and all(r['coversWholeTargetPath'] for r in row['activeClipChecks'])
tree=ast.parse((F/'generate_neighborhoods.py').read_text(encoding='utf-8'))
helpers=[node for node in tree.body if isinstance(node,ast.FunctionDef) and node.name in {'detect_conflicts','packed_geometry_bytes'}]
assert {n.name for n in helpers}=={'detect_conflicts','packed_geometry_bytes'}
exec(compile(ast.Module(body=helpers,type_ignores=[]),'<pinned-read-only-geometry-helpers>','exec'),globals())
fw=Transformer.from_crs(4326,32632,always_xy=True).transform
def utm(g):return transform(fw,g)
def area(g):return g.area/1e6
UOLD={i:utm(g) for i,g in OLD.items()}
UNEW={i:utm(g) for i,g in NEW.items()}
INDICES={r['id']:i for i,r in enumerate(BROWS)};tree=STRtree([OLD[r['id']] for r in BROWS]);cohort=set()
for i in TARGETS:
 for g in [OLD[i],NEW[i]]:cohort.update(int(j) for j in tree.query(g))
cohort.update(INDICES[i] for i in TARGETS);cohort=sorted(cohort);CROWS=[BROWS[j] for j in cohort]
def incident(replacements):
 geometries=[replacements.get(r['id'],OLD[r['id']]) for r in CROWS]
 return [r for r in detect_conflicts(CROWS,geometries) if set(r['ids'])&set(TARGETS)]
BEFORE=incident({});AFTER=incident(NEW);PACKED=incident(Q)
STORED=[r for r in META['conflicts'] if set(r['ids'])&set(TARGETS)]
assert BEFORE==STORED,'Current incident conflicts do not reproduce exactly'
INTERSECTIONS={};COVERAGE={}
for i in TARGETS:
 old,new=UOLD[i],UNEW[i];gain=new.difference(old);loss=old.difference(new)
 remaining_before=[];remaining_after=[];sector_before=[];sector_after=[];records=[]
 for row in CROWS:
  j=row['id']
  if j==i:continue
  a=UOLD[j];b=UNEW.get(j,a)
  remaining_before.append(a);remaining_after.append(b)
  if row['kind']=='sector':sector_before.append(a);sector_after.append(b)
  records.append({'id':j,'name':row['name'],'kind':row['kind'],'geometryAlsoReplaced':j in TARGETS,'beforeIntersectionM2':old.intersection(a).area,'afterJointReplacementIntersectionM2':new.intersection(b).area,'gainAlreadyCoveredBeforeM2':gain.intersection(a).area,'lossStillCoveredAfterM2':loss.intersection(b).area})
 before_all=unary_union(remaining_before);after_all=unary_union(remaining_after);before_sectors=unary_union(sector_before);after_sectors=unary_union(sector_after)
 INTERSECTIONS[i]=records
 COVERAGE[i]={'oldAreaKm2':area(old),'newNativeAreaKm2':area(new),'gainKm2':area(gain),'lossKm2':area(loss),'oldOutsideOtherPolygonsKm2':area(old.difference(before_all)),'newOutsideOtherEffectivePolygonsKm2':area(new.difference(after_all)),'lossUncoveredByAnyEffectivePolygonKm2':area(loss.difference(after_all)),'lossUncoveredByEffectiveSectorsKm2':area(loss.difference(after_sectors)),'gainAlreadyCoveredByOldPolygonsKm2':area(gain.intersection(before_all)),'gainOverlapsEffectivePolygonsKm2':area(gain.intersection(after_all)),'gainOverlapsEffectiveSectorsKm2':area(gain.intersection(after_sectors))}
old_union=unary_union([UOLD[i] for i in TARGETS]);new_union=unary_union(list(UNEW.values()));other_union=unary_union([UOLD[r['id']] for r in CROWS if r['id'] not in TARGETS]);other_sectors=unary_union([UOLD[r['id']] for r in CROWS if r['id'] not in TARGETS and r['kind']=='sector'])
joint_gain=new_union.difference(old_union);joint_loss=old_union.difference(new_union)
JOINT={'oldUnionKm2':area(old_union),'newUnionKm2':area(new_union),'gainKm2':area(joint_gain),'lossKm2':area(joint_loss),'gainPreviouslyUncoveredAllLayersKm2':area(joint_gain.difference(other_union)),'lossUncoveredAllLayersKm2':area(joint_loss.difference(other_union)),'gainOverlapsUnchangedPolygonsKm2':area(joint_gain.intersection(other_union)),'lossUncoveredSectorLayerKm2':area(joint_loss.difference(other_sectors)),'method':'Joint union old pair versus new pair, with both targets removed from unchanged peers; no double counting or stale other-target geometry.'}
parents=read(F/'source-targets-and-parents.json');parea={r['tags'].get('ref:tn:codegeo'):r for r in parents['areas']};PARENTS={}
for i in TARGETS:
 PARENTS[i]={}
 for code,role in [('2153','delegation'),('21','governorate')]:
  row=parea[code];g=utm(shape(row['geometry']));PARENTS[i][role]={'id':row['id'],'code':code,'oldOutsideM2':UOLD[i].difference(g).area,'newOutsideM2':UNEW[i].difference(g).area,'sourceTags':row['tags']}
MEMBERSHIP={};CHILDREN=[];affected=set(TARGETS)
for i in TARGETS:
 changes=[]
 for row in ROWS:
  point=Point(row['lng'],row['lat']);before=OLD[i].covers(point);after=NEW[i].covers(point)
  if before!=after:changes.append({'id':row['id'],'name':row['name'],'kind':row['kind'],'before':before,'after':after,'pickerGroupId':row['pickerGroupId']});affected.add(row['id'])
 MEMBERSHIP[i]=changes
for row in ROWS:
 if row['id'] in TARGETS or row['kind']=='sector':continue
 g=OLD[row['id']] if row['hasBoundary'] else Point(row['lng'],row['lat'])
 memberships={i:{'oldPackedCoversWholeFootprint':OLD[i].covers(g),'newNativeCoversWholeFootprint':NEW[i].covers(g),'newPackedCoversWholeFootprint':Q[i].covers(g)} for i in TARGETS}
 exact_name_reference=any(row.get('parentName')==BYID[i]['name'] or BYID[i]['name'] in row.get('contextAliases',[]) for i in TARGETS)
 if exact_name_reference or any(v['oldPackedCoversWholeFootprint'] or v['newNativeCoversWholeFootprint'] for v in memberships.values()):
  CHILDREN.append({'currentRow':row,'memberships':memberships,'exactCurrentSectorNameReference':exact_name_reference});affected.add(row['id'])
for records in [BEFORE,AFTER,PACKED]:
 for row in records:affected.update(row['ids'])
for rows in INTERSECTIONS.values():
 for row in rows:
  if row['beforeIntersectionM2']!=row['afterJointReplacementIntersectionM2']:affected.add(row['id'])
groups=sorted({BYID[i]['pickerGroupId'] for i in affected if i in BYID});closure=[r for r in ROWS if r['pickerGroupId'] in groups]
QUANT={}
for i in TARGETS:
 g=Q[i];p=Point(BYID[i]['lng'],BYID[i]['lat']);a,b=UNEW[i],utm(g)
 QUANT[i]={'coordinateScale':SCALE,'packedGeometrySha256':hashlib.sha256(packed_geometry_bytes(g)).hexdigest(),'packedLength':len(packed_geometry_bytes(g)),'nativeValid':NEW[i].is_valid,'packedValid':g.is_valid,'nativeAndPackedContainCurrentAnchor':NEW[i].covers(p) and g.covers(p),'anchor':{'lat':p.y,'lng':p.x},'sourceBoundaryDistanceM':a.boundary.distance(utm(p)),'symmetricDifferenceM2':a.symmetric_difference(b).area,'hausdorffM':a.hausdorff_distance(b),'nativeHoles':sum(len(x.interiors) for x in ([NEW[i]] if isinstance(NEW[i],Polygon) else NEW[i].geoms)),'packedHoles':sum(len(x.interiors) for x in ([g] if isinstance(g,Polygon) else g.geoms))}
pair=list(TARGETS);PAIR={'oldOverlapM2':UOLD[pair[0]].intersection(UOLD[pair[1]]).area,'nativeOverlapM2':UNEW[pair[0]].intersection(UNEW[pair[1]]).area,'packedOverlapM2':utm(Q[pair[0]]).intersection(utm(Q[pair[1]])).area,'unchangedNativePaths':True}
qualifications={'sourceEdition':2023,'wholeImadaScope':pin(W/'decree-scope-addendum.json'),'notSurveyOrPresentDayCurrencyProof':True,'oldAndNewSourceDisagreementsRetained':True,'noNativeRepairSnapCutFill':True,'quantization':'Existing set_precision1e-6 and exact pinned encoder applied only to a separate copy.','geometryProposalOnly':'No generation, source, production, ledger, controls or devices modified.','nativeModelReviewMissingJortResolvedBySeparateVisualAddendum':True,'membershipIsFiniteDataEvidenceNotGenerationGuarantee':True}
facts={'status':'READ_ONLY_JOINT_AMDOUN_TWO_BOUNDARY_IMPACT','producer':pin(Path(__file__)),'inputs':[pin(p) for p in PINS],'targetRows':[BYID[i] for i in TARGETS],'jointCoverage':JOINT,'perTargetCoverage':COVERAGE,'targetPair':PAIR,'intersectionRecords':INTERSECTIONS,'conflicts':{'oldStoredAndRecomputed':BEFORE,'nativeJointReplacement':AFTER,'packedCopyJointReplacement':PACKED,'oldAllCatalogCount':len(META['conflicts']),'projectedPackedAllCatalogCount':len(META['conflicts'])-len(BEFORE)+len(PACKED),'cohortIds':[r['id'] for r in CROWS]},'rawRepresentativeMembershipChanges':MEMBERSHIP,'wholeFootprintChildConsumers':CHILDREN,'parents':PARENTS,'quantization':QUANT,'pickerClosure':{'groups':groups,'rows':closure,'noGroupingOrMetadataMutation':True},'qualifications':qualifications}
summary={'status':facts['status'],'jointCoverage':JOINT,'perTargetCoverage':COVERAGE,'targetPair':PAIR,'counts':{'oldIncidentConflicts':len(BEFORE),'newNativeIncidentConflicts':len(AFTER),'newPackedIncidentConflicts':len(PACKED),'projectedPackedAllCatalogConflicts':facts['conflicts']['projectedPackedAllCatalogCount'],'cohortPolygons':len(CROWS),'rawMembershipChanges':sum(len(v) for v in MEMBERSHIP.values()),'childConsumers':len(CHILDREN),'pickerClosureGroups':len(groups)},'quantization':QUANT,'parents':PARENTS}
check_pins();save('impact-facts.json',facts);save('impact-summary.json',summary);check_pins()
print(json.dumps({'facts':pin(W/'impact-facts.json'),'summary':pin(W/'impact-summary.json'),'counts':summary['counts'],'jointCoverage':JOINT},ensure_ascii=False))
