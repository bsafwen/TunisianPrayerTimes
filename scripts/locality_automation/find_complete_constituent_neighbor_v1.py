"""Search literal complete same-sheet unions for a finite declared-circle target.

This diagnostic preserves native faces and performs no acceptance, snapping,
registration waiver, or geometric repair. Final native review remains required.
"""
import argparse,json,sys
from pathlib import Path
import pymupdf
from shapely import from_wkb
from shapely.geometry import box
from shapely.ops import unary_union,polygonize_full
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,checked,pin,active_control
from scripts.locality_automation.find_complete_neighbor_face_v6 import source
from scripts.locality_automation.isie_candidate_comparison import _map_geometry
from scripts.locality_automation.audit_reviewed_source_family import put
p=argparse.ArgumentParser(description=__doc__)
for k in ['manifest','facts','output']:p.add_argument('--'+k,type=Path,required=True)
p.add_argument('--target',required=True);a=p.parse_args()
s=read(a.manifest);c=active_control(s);pool=c['approvedCycle'+str(c['iteration'])+'AcceptancePool']
if s['exactTargets']!=pool or a.target not in pool:raise ValueError('Exact finite target differs')
fact=read(a.facts)
if fact['officialCode']!=a.target or not fact.get('literalConstituentUnion'):raise ValueError('Declared union target required')
parts=fact['constituentNativeFacts'];footprints=[from_wkb(checked(r['sourceMetricGeometry']).read_bytes())for r in parts]
if len(parts)!=fact['expectedCircleCount']:raise ValueError('Complete circle count differs')
out=a.output.resolve();out.mkdir();matches=[];incident=[]
for case in s['cases']:
 with pymupdf.open(checked(case['sourcePdf']))as doc:pagebox=box(*doc[0].rect)
 inv=read(checked(case['sourceInventory']))['pages'][0];ref=next(r for r in inv['georeferences']if r['status']=='fitted')
 frame=_map_geometry(pagebox,ref);coverage=[frame.intersection(g).area/g.area for g in footprints]
 if not all(v>=.95 for v in coverage):continue
 incident.append({'sheet':case['officialCode'],'sourcePdf':case['sourcePdf'],'frameCoverage':coverage})
 inv,ref,lines=source(case);faces=list(polygonize_full(unary_union([v for ls in lines.values()for v in ls]))[0].geoms)
 selected=[]
 for n,g in enumerate(footprints):
  candidates=[]
  for i,h in enumerate(faces):
   if h.area<50 or not h.is_valid or not pagebox.covers(h):continue
   metric=_map_geometry(h,ref);iou=metric.intersection(g).area/metric.union(g).area
   if iou>=.9:candidates.append((i,h,iou))
  if len(candidates)!=1:break
  selected.append(candidates[0])
 if len(selected)!=len(footprints)or len({v[0]for v in selected})!=len(footprints):continue
 refs=[]
 for n,(i,h,iou)in enumerate(selected):
  path=out/(case['officialCode']+'-'+case['sourcePdf']['sha256'][:12]+'-circle-'+str(n+1)+'-page.wkb');path.write_bytes(h.wkb);refs.append(pin(path))
 matches.append({'sheet':case['officialCode'],'sourcePdf':case['sourcePdf'],'sourceInventory':case['sourceInventory'],'sourceUrl':case['sourceUrl'],'reviewedNativePageFaceSet':refs,'circleIds':[v+1 for v in range(len(parts))],'faceIndexes':[v[0]for v in selected],'ious':[v[2]for v in selected],'insideLabels':[[{'index':i,'text':l['text']}for i,l in enumerate(inv['labeledAreaLeads'])if h.covers(__import__('shapely').geometry.Point(l['centerPagePoints']))]for _,h,_ in selected]})
put(out/'report.json',{'target':a.target,'manifest':pin(a.manifest.resolve()),'facts':pin(a.facts.resolve()),'candidateCompleteSameSheetUnions':matches,'incidentCompleteFrames':incident,'sourceScopeAccepted':False,'credit':0})
print(json.dumps({'target':a.target,'candidateCompleteSameSheetUnions':matches,'incidentCompleteFrames':incident,'credit':0},ensure_ascii=False))
