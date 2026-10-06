"""Find literal closed constituent faces on one original sheet; diagnostics only."""
import argparse,json,sys
from pathlib import Path
import pymupdf
from shapely import from_wkb
from shapely.geometry import LineString,Polygon,Point,box
from shapely.ops import unary_union,polygonize_full
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.isie_candidate_comparison import _map_geometry

def contents(case):
 inv=read(checked(case['sourceInventory']))['pages'][0];ref=next(r for r in inv['georeferences']if r['status']=='fitted');ls={k:[]for k in ['red','blue','black']}
 with pymupdf.open(checked(case['sourcePdf']))as doc:rect=box(*doc[0].rect)
 frame=None
 for p in inv['nativePaths']:
  if not p['relevantLineworkHint']or not p['visibleStroke']or p['strokeFamily']not in ls:continue
  for run in p['runs']:
   points=run['nativePagePoints'];is_frame=False
   if p['strokeFamily']=='black'and len(points)==5 and points[0]==points[-1]:
    q=Polygon(points);is_frame=q.is_valid and q.equals(box(*q.bounds))and q.area>rect.area*.8
    if is_frame:frame=q
   if len(points)>1 and not is_frame:ls[p['strokeFamily']].append(LineString(points))
 faces=polygonize_full(unary_union([v for values in ls.values()for v in values]))[0]
 return inv,ref,ls,rect,frame,faces

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--seeds',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();spec=read(a.manifest);control=active_control(spec);seed=read(a.seeds);pool=control['approvedCycle'+str(control['iteration'])+'AcceptancePool']
 if not set(seed['targets'])<=set(pool):raise ValueError('Diagnostic source target outside active finite scope')
 out=a.output.resolve();out.mkdir();unique={c['sourcePdf']['sha256']:c for c in spec['cases']};allfaces=[];seeds={}
 for target,parts in seed['targets'].items():
  seeds[target]=[]
  for r in parts:
   case=unique[r['sourcePdfSha256']];inv,ref,ls,rect,frame,faces=contents(case);g=from_wkb(checked(r['pageGeometry']).read_bytes());seeds[target].append((r,_map_geometry(g,ref)))
 for sha,case in unique.items():
  inv,ref,ls,rect,frame,faces=contents(case);footprint=_map_geometry(frame or rect,ref)
  for target,parts in seeds.items():
   coverage=[footprint.intersection(g).area/g.area for r,g in parts]
   if min(coverage)<.995:continue
   found=[]
   for part,expected in parts:
    candidates=[]
    for i,g in enumerate(faces.geoms):
     if g.area<50 or not rect.covers(g)or(frame is not None and(not frame.covers(g)or g.boundary.intersection(frame.boundary).length>.01)):continue
     metric=_map_geometry(g,ref);iou=metric.intersection(expected).area/metric.union(expected).area
     if iou<.97:continue
     f=out/(target+'-'+sha[:10]+'-'+part['part']+'-'+str(i)+'.wkb');f.write_bytes(g.wkb)
     candidates.append({'part':part['part'],'pageGeometry':pin(f),'faceIndex':i,'iou':iou,'areaM2':metric.area,'insideLabels':[(n,l['text'])for n,l in enumerate(inv['labeledAreaLeads'])if g.covers(Point(l['centerPagePoints']))]})
    if len(candidates)!=1:break
    found.append(candidates[0])
   if len(found)==len(parts):allfaces.append({'target':target,'sheetOwner':case['officialCode'],'sourcePdf':case['sourcePdf'],'sourceInventory':case['sourceInventory'],'sourceUrl':case['sourceUrl'],'pieces':found,'frameCoverage':coverage,'fitResidualM':ref['maxControlResidualMeters']})
 put(out/'report.json',{'status':'SAME_SHEET_CANDIDATES_REQUIRE_ROOT_VISUAL_NATIVE_REGISTRATION_REVIEW','candidates':allfaces,'manifest':pin(a.manifest.resolve()),'seeds':pin(a.seeds.resolve()),'credit':0})
 print(json.dumps([{**r,'pieces':[{k:p[k]for k in ['part','faceIndex','iou','areaM2','insideLabels']}for p in r['pieces']]}for r in allfaces],ensure_ascii=False))
if __name__=='__main__':main()
