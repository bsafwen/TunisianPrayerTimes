"""Find finite original native-face candidates using an app point as a search hint.
Search hints are not boundary proof. Every candidate requires unchanged review.
"""
import argparse,json,sys
from pathlib import Path
import pymupdf
from shapely.geometry import Point,box
from shapely.ops import polygonize_full,unary_union,transform
from pyproj import Transformer
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.find_complete_neighbor_face_v6 import source
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.isie_candidate_comparison import _map_geometry
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--manifest',required=True,type=Path);p.add_argument('--targets',nargs='+',required=True);p.add_argument('--output',required=True,type=Path);a=p.parse_args()
s=read(a.manifest);c=active_control(s);assert set(a.targets)<=set(c['approvedCycle'+str(c['iteration'])+'AcceptancePool']);a.output.mkdir();rows=[]
project=Transformer.from_crs(4326,32632,always_xy=True).transform
targets={q['officialCode']:q for q in s['cases']if q['officialCode']in a.targets}
for src in s['cases']:
 inv,ref,ls=source(src)
 with pymupdf.open(checked(src['sourcePdf']))as doc:rect=box(*doc[0].rect)
 frame=_map_geometry(rect,ref)
 nearby=[(code,q,transform(project,Point(q['appMetadata']['lng'],q['appMetadata']['lat'])))for code,q in targets.items()if frame.covers(transform(project,Point(q['appMetadata']['lng'],q['appMetadata']['lat'])))]
 if not nearby:continue
 for layer,network in [('red',ls['red']),('connected',[g for family in ls.values()for g in family])]:
  for n,g in enumerate(polygonize_full(unary_union(network))[0].geoms):
   if g.area<50 or not rect.covers(g)or not g.is_valid:continue
   metric=_map_geometry(g,ref)
   for code,q,point in nearby:
    if not metric.covers(point):continue
    tag=code+'-on-'+src['officialCode']+'-'+layer+'-'+str(n);path=a.output/(tag+'-page.wkb');path.write_bytes(g.wkb)
    case={**q,'sourcePdf':src['sourcePdf'],'sourceInventory':src['sourceInventory'],'sourceUrl':src['sourceUrl'],'reviewedNativePageFaceSet':[pin(path.resolve())],'neighborSourceOwnerCode':src['officialCode'],'originalOwnSourcePdf':q['sourcePdf'],'sourceAttributionRole':'DIAGNOSTIC native candidate discovered by current app representative point; point and OSM are not boundary evidence. Requires root original/neighbor visual attribution and all unchanged registration/native/full-source gates.'}
    for k in ['diagnosticTargetLabelFace','selectedSourceFace','reviewedNativePageFace','sourceContextOnly']:case.pop(k,None)
    manifest=a.output/(tag+'-manifest.json');put(manifest,{**s,'cases':[case],'credit':0})
    rows.append({'target':code,'sheet':src['officialCode'],'layer':layer,'faceIndex':n,'areaKm2':metric.area/1e6,'insideLabels':[l['text']for l in inv['labeledAreaLeads']if g.covers(Point(l['centerPagePoints']))],'manifest':pin(manifest.resolve()),'sourcePdf':src['sourcePdf'],'hintRole':'search only','sourceScopeAccepted':False})
put(a.output/'report.json',{'status':'DIAGNOSTIC_SEARCH_HINT_ONLY','manifest':pin(a.manifest.resolve()),'candidates':rows,'credit':0})
print(json.dumps(rows,ensure_ascii=False))

