"""Rank complete original native faces against an explicitly diagnostic footprint.

No identity, registration, or source-scope acceptance follows from similarity.
"""
import argparse,json,sys
from pathlib import Path
import pymupdf
from shapely import from_wkb
from shapely.geometry import Point,box
from shapely.ops import transform,unary_union,polygonize_full
from pyproj import Transformer
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.find_complete_neighbor_face_v4 import source
from scripts.locality_automation.isie_candidate_comparison import _map_geometry
from scripts.locality_automation.audit_reviewed_source_family import put
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--target',required=True);p.add_argument('--footprint',type=Path,required=True);p.add_argument('--footprint-crs',type=int,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();s=read(a.manifest);active_control(s)
 if a.target not in s['exactTargets']:raise ValueError('Outside finite targets')
 original=from_wkb(a.footprint.read_bytes());g=transform(Transformer.from_crs(a.footprint_crs,32632,always_xy=True).transform,original);a.output.mkdir();results=[]
 for c in s['cases']:
  inv,ref,lines=source(c)
  with pymupdf.open(checked(c['sourcePdf']))as doc:frame=box(*doc[0].rect)
  if not _map_geometry(frame,ref).intersects(g):continue
  for layer,network in [('red',lines['red']),('connected',[unary_union(lines[k])for k in lines])]:
   for i,f in enumerate(polygonize_full(unary_union(network))[0].geoms):
    if f.area<50 or not frame.covers(f):continue
    m=_map_geometry(f,ref);intersection=m.intersection(g).area;iou=intersection/m.union(g).area
    if iou<.3:continue
    path=a.output/(c['officialCode']+'-'+layer+'-'+str(i)+'-page.wkb');path.write_bytes(f.wkb)
    results.append({'sheet':c['officialCode'],'layer':layer,'faceIndex':i,'pageGeometry':pin(path.resolve()),'iouWithDiagnosticFootprint':iou,'footprintCoverage':intersection/g.area,'sourceAreaKm2':m.area/1e6,'sourcePdf':c['sourcePdf'],'sourceInventory':c['sourceInventory'],'sourceUrl':c['sourceUrl'],'insideLabels':[{'index':n,'text':v['text']}for n,v in enumerate(inv['labeledAreaLeads'])if f.covers(Point(v['centerPagePoints']))]})
 results.sort(key=lambda r:r['iouWithDiagnosticFootprint'],reverse=True);put(a.output/'report.json',{'target':a.target,'manifest':pin(a.manifest.resolve()),'diagnosticFootprint':pin(a.footprint.resolve()),'footprintCrs':a.footprint_crs,'completeFaceCandidates':results,'status':'DIAGNOSTIC_ONLY_REQUIRES_IDENTITY_VISUAL_AND_UNCHANGED_NATIVE_GATES','credit':0});print(json.dumps([{'sheet':r['sheet'],'layer':r['layer'],'face':r['faceIndex'],'iou':r['iouWithDiagnosticFootprint'],'labels':r['insideLabels']}for r in results[:10]],ensure_ascii=False))
if __name__=='__main__':main()
