from pathlib import Path
import json,hashlib,struct,math
import numpy as np
from pypdf import PdfReader
from shapely.geometry import shape,Polygon,Point,mapping
from shapely import set_precision
from shapely.ops import transform
from pyproj import Transformer

D=Path(__file__).resolve().parent;T=D.parents[1];R=Path('C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes')
def read(p):return json.loads(Path(p).read_bytes())
def pin(p):
 p=Path(p).resolve();return {'file':p.as_posix(),'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'bytes':p.stat().st_size}
def save(p,o):
 assert not p.exists(),p
 p.write_text(json.dumps(o,ensure_ascii=False,indent=2)+'\n',encoding='utf8',newline='\n')
def gh(g):return hashlib.sha256(json.dumps(mapping(g),separators=(',',':')).encode()).hexdigest()
sp=D/'two-own-map-source-selection-facts.json';fp=D/'two-receiving-source-finite-effects.json';bp=D/'two-whole-native-source-polygons.geojson'
assert pin(sp)['sha256']=='0bb60e9953475b957cde85d7ba857cc3483b2ffcbde6106e08c559895e4098fa'
assert pin(fp)['sha256']=='e420a0c7e3cd3aa3cb3147edfd105a77fa0934407c6b6ef366f55c81468930e7'
s=read(sp);f=read(fp);features={x['id']:x for x in read(bp)['features']}
fw=Transformer.from_crs(4326,32632,always_xy=True);iv=Transformer.from_crs(32632,4326,always_xy=True)
country=shape(next(a['geometry'] for a in read(s['sourceCache']['file'])['areas'] if a['id']=='osm:relation:192757'))
available=[d for g in read(f['inputs']['governors']['file'])['gouvernorats'] for d in g['delegations'] if d['id'] not in {r['id'] for r in read(f['inputs']['coverage']['file'])['rejectedPrayerSources']}];assert len(available)==258
def nearest(lat,lng):
 def km(d):
  a,b=math.radians(lat),math.radians(d['lat']);h=math.sin((b-a)/2)**2+math.cos(a)*math.cos(b)*math.sin(math.radians(d['lng']-lng)/2)**2
  return 12742*math.atan2(math.sqrt(h),math.sqrt(1-h))
 d=min(available,key=lambda d:(km(d),d['id']));return {'id':d['id'],'distanceKm':km(d)}
def packed(q):
 assert q.geom_type=='Polygon'
 b=bytearray(struct.pack('>ii',1,1+len(q.interiors)))
 for ring in [q.exterior]+list(q.interiors):
  b.extend(struct.pack('>i',len(ring.coords)))
  for x,y in ring.coords:b.extend(struct.pack('>ii',round(x*1000000),round(y*1000000)))
 return bytes(b)
diagnostics=[]
for sr in s['records']:
 rid=sr['id'];g=shape(features[rid]['geometry']);q=set_precision(g,0.000001)
 assert g.is_valid and q.is_valid and country.covers(g) and country.covers(q) and q.geom_type=='Polygon' and len(q.interiors)==0
 pr=next(r for r in f['sourceIdentityAndPointFacts'] if r['twoReceiverNativeMembership']==[rid]);r=pr['currentRaw'];point=Point(r['lng'],r['lat']);p=Point(*fw.transform(r['lng'],r['lat']))
 reader=PdfReader(sr['sourcePdf']['file']);vp=reader.pages[0]['/VP'][0].get_object();me=vp['/Measure'].get_object();bbox=[float(x) for x in vp['/BBox']];lpts=np.array([float(v) for v in me['/LPTS']]).reshape(-1,2);gpts=np.array([float(v) for v in me['/GPTS']]).reshape(-1,2)
 control=np.array([[bbox[0]+u*(bbox[2]-bbox[0]),bbox[1]+v*(bbox[3]-bbox[1]),1] for u,v in lpts]);metric=np.array([fw.transform(lon,lat) for lat,lon in gpts]);affine=np.linalg.lstsq(control,metric,rcond=None)[0]
 raw=np.array([[float(x),float(y),1] for x,y in sr['originalPath']['rawDecimalVertices']]);rawgeom=Polygon(raw@affine);native=transform(fw.transform,g);quantized=transform(fw.transform,q)
 a=np.array(native.exterior.coords);b=np.array(rawgeom.exterior.coords);assert a.shape==b.shape
 difference=np.linalg.norm(a-b,axis=1);residual=np.linalg.norm(control@affine-metric,axis=1)
 assert max(difference)<0.01 and max(residual)<0.3
 assert native.covers(p) and rawgeom.covers(p) and q.covers(point)
 anchor=q.representative_point()
 diagnostics.append({'id':rid,'sourceGeometrySha256':gh(g),'declaredCoordinateScale':1000000,'quantizedGeometrySha256':gh(q),'packedGeometrySha256':hashlib.sha256(packed(q)).hexdigest(),'packedBytes':len(packed(q)),'sourceEdges':len(g.exterior.coords)-1,'quantizedEdges':len(q.exterior.coords)-1,'sourceHoles':0,'quantizedHoles':0,'sourceAndQuantizedWithinFullCountry':True,'pointId':r['id'],'pointCoveredByNativeApiGeometry':True,'pointCoveredByOriginalDecimalControlGeometry':True,'pointCoveredAfterDeclaredCoordinateQuantization':True,'pointBoundaryDistanceMeters':{'nativeApi':native.boundary.distance(p),'originalDecimalControls':rawgeom.boundary.distance(p),'declaredCoordinateQuantization':quantized.boundary.distance(p)},'maxRawDecimalVsNativePipelineDisplacementMeters':float(max(difference)),'maxOriginalControlResidualMeters':float(max(residual)),'diagnosticPackedRepresentative':{'lat':anchor.y,'lng':anchor.x,'nearestPrayer':nearest(anchor.y,anchor.x)},'unchangedPointNearestPrayer':nearest(r['lat'],r['lng']),'sourceGeometryRemainsUnroundedAndUnchanged':True,'qualification':'This compares original-decimal and native API registration plus the declared serialization grid. It does not quantify map surveying error, device GPS uncertainty or legal parcel accuracy. No packed asset is written.'})
dp=D/'two-receiver-registration-quantization-diagnostic.json';save(dp,{'status':'SOURCE_ONLY_REGISTRATION_AND_DECLARED_PACKING_DIAGNOSTIC','sourceSelection':pin(sp),'bundle':pin(bp),'finiteFacts':pin(fp),'records':diagnostics,'producer':pin(__file__)})

prior=read(f['inputs']['priorThirteenFacts']['file'])['scenarios'][-1]['completeAffectedCurrentGroupClosure'];combined={r['id']:r for r in prior}
for r in f['completeAffectedCurrentGroupClosure']:
 if r['id'] in combined:assert combined[r['id']]==r
 combined[r['id']]=r
source_dir=T/'work/baddar-aitha-kharmia-essabala-source-review'
primary=[{'file':source_dir/'baddar-governorate-village-2016.html','sha256':'7b2b35ae9facfa169e4c132f05e11f7fb7c1e8d44ecf3c2dc5e557c9e75bab3b','date':'2016-10-13','finding':'Governorate visit explicitly describes Baddar village in Takelsa.','exactShortText':'إلى معتمدية تاكلسة يوم الخميس 13 أكتوبر 2016 بقرية بدار'},
 {'file':source_dir/'baddar-governorate-arima-2022.html','sha256':'c11bfbad49d0098ca907520839be9c629b079ca892b661bee88123f806d79f19','date':'2022-08-05','finding':'Governorate environmental inspection explicitly identifies El Arima (Baddar) in Takelsa.','exactShortText':'بمنطقة العريمة (بدّار) بمعتمدية تاكلسة من ولاية نابل'}]
for r in primary:
 p=r.pop('file');assert pin(p)['sha256']==r['sha256'];assert r['exactShortText'] in p.read_text(encoding='utf8');r['original']=pin(p)
out=T/'outputs/arima-joufia-receiving-own-map-source-review.json'
report={'status':'QUALIFIED_TWO_RECEIVING_OWN_MAPS_SUPPORT_CONTEXT_CORRECTION_PENDING_INDEPENDENT_SOURCE_REVIEW','baselineMetadataSha256':f['baselineMetadataSha256'],'sourceSelection':pin(sp),'finiteFacts':pin(fp),'registrationAndQuantizationDiagnostic':pin(dp),'nativeBundle':pin(bp),'retrievalManifest':s['retrievalManifest'],'originalExtractionInventory':s['extractionInventory'],'currentInputs':f['inputs'],
 'scope':'Exactly Arima7100361/156053 and Menzel Bouzelfa Joufia7101605/156253. Source-only receiving-map review; the frozen thirteen-boundary package is unchanged.',
 'sourceFindings':[{'id':r['id'],'code':r['code'],'nameAr':r['registryRow']['sectorAr'],'delegationCode':r['registryRow']['delegationCode'],'delegationName':r['registryRow']['delegationAr'],'originalPdf':r['sourcePdf'],'url':r['sourceUrl'],'selectedNativeDrawing':r['selectedDrawingIndex'],'originalEdgeCount':r['originalPath']['edgeCount'],'completeOwnMapPathPersonallyViewed':True,'insideBothClipsAndFullCountry':True,'decreeWholeImadaScope':r['decreeWholeImadaScope']} for r in s['records']],
 'pointDisposition':[{'id':'osm:node:2173790702','decision':'Preserve Baddar village; primary-supported parent Arima156053 within Takelsa1560. Reject final Soliman fallback.','currentFields':f['sourceIdentityAndPointFacts'][0]['currentRaw'],'ownSourceMembership':f['sourceIdentityAndPointFacts'][0],'primaryIndependentIdentity':primary,'boundaryQualification':'Point is about0.9m inside this native edge. Original-decimal reconstruction and declared1e-6 quantization preserve that hit, but primary place identity is necessary independent support; no GPS or survey100% claim.','integrationRequirement':'The cached Soliman delegation7145292 contains the point and disagrees with the two governorate primary pages. Derive qualified parent context from the reviewed official Arima/Takelsa identity; do not carry the cached Soliman context as verified. No coordinate change is warranted.'},
 {'id':'osm:node:2191295540','decision':'Preserve El Aitha hamlet; whole own map places the unchanged point in Menzel Bouzelfa Joufia156253.','currentFields':f['sourceIdentityAndPointFacts'][1]['currentRaw'],'ownSourceMembership':f['sourceIdentityAndPointFacts'][1],'integrationRequirement':'Correct the stale Keblia/Sud parent claim after whole-source acceptance, retaining the inhabited hamlet point and timetable458. This does not equate the hamlet to its complete receiving imada.'}],
 'finiteEffects':{'receiverMeasurements':f['receivingSectors'],'oldThirteenCoverageComparison':f['thirteenOldCoverageComparison'],'rawRepresentativeChanges':f['rawRepresentativeMembershipChanges'],'sourcePointChanges':f['sourcePointMembershipChanges'],'twoReceiverLocalClosureCount':len(f['completeAffectedCurrentGroupClosure']),'twoReceiverLocalClosure':f['completeAffectedCurrentGroupClosure'],'combinedWithFrozenThirteenClosureCount':len(combined),'newClosureRowsBeyondFrozenThirteen':[r for i,r in combined.items() if i not in {r['id'] for r in prior}],'directCurrentBindings':f['directCurrentBindings'],'relevantCurationReceipts':f['relevantCurationReceipts'],'actualNativeSeams':f['actualNativeSeamsWithFrozenThirteenAndEachOther'],'anchorDiagnostics':diagnostics},
 'recommendedNextAction':'Independent review of the exact two original outlines and close-edge Baddar interpretation, then separately integrate accepted receiver sources and truthful parent claims with the existing thirteen package. Preserve all names, distinct locality IDs, raw point coordinates and current nearest prayer defaults; separately audit actual packed anchors/context/source proof closure.',
 'limitations':['No source stage, manifest append, compiler edit or generation occurs in this task.','The two native receivers do not receive all old strips. Joufia receives13.0464km² of the16.1777km² largest old156254 east loss component. Remaining margins are reported, not inferred.','Actual official-map overlap seams are preserved and measured. No clip, snap, repair or old OSM strip is substituted.','The fifteen-member union is used only to identify local membership/closure effects; this is not a full coherent fifteen-source acceptance.','Canonical Joufia is supported by own map/decree/INS; old OSM الشمالية wording is not silently approved as a source-authoritative canonical name.','The prior thirteen source report remains historical. Its Baddar cached Soliman fallback is explicitly superseded as a final acceptance by this primary contradiction.'],
 'preservation':f['preservation'],'producer':pin(__file__)}
assert all(pin(p['file'])==p for p in f['inputs'].values())
save(out,report)
print(json.dumps({'report':pin(out),'diagnostic':pin(dp),'diagnosticRecords':diagnostics,'combinedClosure':len(combined)},ensure_ascii=False,indent=2))
