"""Bounded native neighbor-face search for any finite held source target.

Candidate diagnostics earn no credit and never bypass source registration.
Use a pinned existing producer manifest, target code and fresh output directory.
"""
import argparse,json,sys
from pathlib import Path
import pymupdf
from shapely.geometry import LineString,Point,Polygon,box
from shapely.ops import unary_union,polygonize_full
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,checked,pin,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.isie_candidate_comparison import _map_geometry,_name_match
def source(case):
 with pymupdf.open(checked(case['sourcePdf']))as doc:page_rect=tuple(doc[0].rect)
 inv=read(checked(case['sourceInventory']))['pages'][0];ref=next(r for r in inv['georeferences']if r['status']=='fitted');lines={k:[]for k in ['red','blue','black']}
 for p in inv['nativePaths']:
  if p['relevantLineworkHint']and p['visibleStroke']and p['strokeFamily']in lines:
   for run in p['runs']:
    points=run['nativePagePoints']
    if len(points)<=1:continue
    if p['strokeFamily']=='black'and len(points)==5 and points[0]==points[-1]:
     rectangle=Polygon(points)
     if rectangle.is_valid and rectangle.equals(box(*rectangle.bounds))and rectangle.area>box(*page_rect).area*.8:continue
    lines[p['strokeFamily']].append(LineString(points))
 return inv,ref,lines
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--target',required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();spec=read(a.manifest);control=active_control(spec)
 allowed=control.get('approvedCycle'+str(control['iteration'])+'AcceptancePool',[])+control.get('pendingSourceCodes',[])
 if a.target not in allowed or a.target not in spec['exactTargets']:raise ValueError('Target is outside finite authorized source scope')
 target=next(r for r in spec['cases']if r['officialCode']==a.target);inv,ref,lines=source(target);diagnostic=target.get('diagnosticTargetLabelFace',{})
 if diagnostic.get('targetLabelIndex')is not None:
  label=inv['labeledAreaLeads'][diagnostic['targetLabelIndex']]
  if label['text']!=diagnostic['targetLabelText']:raise ValueError('Pinned diagnostic label differs')
  own=[label]
 else:own=[l for l in inv['labeledAreaLeads']if _name_match(l['text'],target['officialName'])and l['centerPagePoints'][1]>100]
 faces=[g for g in polygonize_full(unary_union(lines['red']))[0].geoms if any(g.covers(Point(l['centerPagePoints']))for l in own)]
 if not faces:faces=[g for g in polygonize_full(unary_union([g for family in lines.values()for g in family]))[0].geoms if any(g.covers(Point(l['centerPagePoints']))for l in own)]
 if len(faces)!=1:raise ValueError('Diagnostic own face requires an explicit unique original in-map label')
 footprint=_map_geometry(faces[0],ref);out=a.output.resolve()
 if not out.parent.is_dir():raise ValueError('Existing producer parent required')
 out.mkdir();incident=[];matches=[];rejected_off_page=[]
 for case in spec['cases']:
  if case['officialCode']==a.target:continue
  inv,reference,ls=source(case)
  with pymupdf.open(checked(case['sourcePdf']))as doc:page_box=box(*doc[0].rect);frame=_map_geometry(page_box,reference)
  if not frame.intersects(footprint):continue
  coverage=frame.intersection(footprint).area/footprint.area;incident.append({'sheet':case['officialCode'],'pageFootprintTargetCoverage':coverage})
  if coverage<.95:continue
  for layer,network in [('red',ls['red']),('connected',[unary_union(ls[k])for k in ['red','blue','black']])]:
   for index,g in enumerate(polygonize_full(unary_union(network))[0].geoms):
    if g.area<50:continue
    metric=_map_geometry(g,reference);intersection=metric.intersection(footprint).area;iou=intersection/metric.union(footprint).area
    if iou<.9:continue
    if not page_box.covers(g):
     rejected_off_page.append({'sheet':case['officialCode'],'layer':layer,'faceIndex':index,'reason':'complete native face extends outside original page'});continue
    f=out/(case['officialCode']+'-'+layer+'-'+str(index)+'-page.wkb');f.write_bytes(g.wkb)
    matches.append({'sheet':case['officialCode'],'layer':layer,'faceIndex':index,'pageGeometry':pin(f),'areaM2':metric.area,'iouWithHeldDiagnosticOwnFace':iou,'targetCoverage':intersection/footprint.area,'sourcePdf':case['sourcePdf'],'sourceInventory':case['sourceInventory'],'sourceUrl':case['sourceUrl'],'insideLabels':[{'index':n,'text':l['text']}for n,l in enumerate(inv['labeledAreaLeads'])if g.covers(Point(l['centerPagePoints']))]})
 result={'status':'DIAGNOSTIC_CANDIDATES_REQUIRE_NATIVE_VISUAL_AND_REGISTRATION_REVIEW','target':a.target,'manifest':pin(a.manifest),'heldOwnRegistrationNotAccepted':True,'searchFootprintAreaM2':footprint.area,'incidentSheetFrames':incident,'candidateCompleteFaces':matches,'rejectedOffPageCandidates':rejected_off_page,'originalOuterMapFramesExcluded':True,'sourceScopeAccepted':False,'credit':0}
 put(out/'report.json',result);print(json.dumps({'target':a.target,'incidentFrames':incident,'candidateWholeFaces':matches,'rejectedOffPageCandidates':rejected_off_page},ensure_ascii=False))
if __name__=='__main__':main()
