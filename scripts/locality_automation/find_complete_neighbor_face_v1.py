"""Find complete native neighbor-sheet alternatives without changing a held gate.

The failed own registration is used only as a coarse diagnostic search footprint.
Each alternative still needs original-point, visual, legal and registration review.
"""
import json,sys
from pathlib import Path
from shapely.geometry import LineString,Point,box
from shapely.ops import unary_union,polygonize_full
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,checked,pin,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.isie_candidate_comparison import _map_geometry,_name_match
E=Path(r'C:\Users\barou\Documents\Codex\2026-09-06\the-android-app-app-currently-allows');W=E/'work/locality-efficiency-cycles-20261001/root-cycle24';c=read(W.parent/'control.json');s={k:c[k]for k in ['iteration','windowStartUtc','deadlineUtc']};s.update(control=str(W.parent/'control.json'),owner='/root');active_control(s)
spec=read(W/'medenine-originals-manifest.json');cases=spec['cases'];target=next(r for r in cases if r['officialCode']=='525363')
def source(case):
 inventory=read(checked(case['sourceInventory']))['pages'][0];reference=next(r for r in inventory['georeferences']if r['status']=='fitted')
 lines={k:[]for k in ['red','blue','black']}
 for p in inventory['nativePaths']:
  if p['relevantLineworkHint']and p['visibleStroke']and p['strokeFamily']in lines:
   for run in p['runs']:
    if len(run['nativePagePoints'])>1:lines[p['strokeFamily']].append(LineString(run['nativePagePoints']))
 return inventory,reference,lines
i,ref,ls=source(target);own=[l for l in i['labeledAreaLeads']if _name_match(l['text'],target['officialName'])and l['centerPagePoints'][1]>100]
red=polygonize_full(unary_union(ls['red']))[0];g=next(p for p in red.geoms if any(p.covers(Point(v['centerPagePoints']))for v in own));targetMetric=_map_geometry(g,ref)
out=W/'qasr-complete-neighbor-search-v1';out.mkdir();matches=[];incident=[]
for case in cases:
 if case['officialCode']=='525363':continue
 inv,reference,lines=source(case)
 # Only incident frame footprints are considered; no already accepted body tests.
 pagepoints=[v for family in lines.values()for line in family for v in line.coords]
 frame=_map_geometry(box(min(x for x,y in pagepoints),min(y for x,y in pagepoints),max(x for x,y in pagepoints),max(y for x,y in pagepoints)),reference)
 if not frame.intersects(targetMetric):continue
 coverage=frame.intersection(targetMetric).area/targetMetric.area;incident.append({'sheet':case['officialCode'],'pageFootprintTargetCoverage':coverage})
 if coverage<.95:continue
 for layer,network in [('red',lines['red']),('connected',[p for family in lines.values()for p in family])]:
  faces=polygonize_full(unary_union(network))[0]
  for j,p in enumerate(faces.geoms):
   if p.area<50:continue
   h=_map_geometry(p,reference);intersection=targetMetric.intersection(h).area;iou=intersection/targetMetric.union(h).area
   if iou<.9:continue
   native=out/(case['officialCode']+'-'+layer+'-'+str(j)+'-page.wkb');native.write_bytes(p.wkb)
   matches.append({'sheet':case['officialCode'],'layer':layer,'faceIndex':j,'pageGeometry':pin(native),'areaM2':h.area,'iouWithHeldDiagnosticOwnFace':iou,'ownTargetCoverage':intersection/targetMetric.area,'sourcePdf':case['sourcePdf'],'sourceInventory':case['sourceInventory'],'sourceUrl':case['sourceUrl'],'insideLabels':[{'index':n,'text':l['text']}for n,l in enumerate(inv['labeledAreaLeads'])if p.covers(Point(l['centerPagePoints']))]})
put(out/'report.json',{'target':'525363','heldOwnRegistrationNotAccepted':True,'searchFootprintAreaM2':targetMetric.area,'incidentSheetFrames':incident,'candidateCompleteFaces':matches,'sourceScopeAccepted':False,'credit':0})
print(json.dumps({'incidentFrames':incident,'candidateWholeFaces':matches},ensure_ascii=False))
