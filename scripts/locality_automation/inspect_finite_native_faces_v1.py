"""Render bounded native face diagnostics without accepting source or geometry."""
import argparse,json,sys
from pathlib import Path
import pymupdf,numpy as np
from PIL import Image,ImageDraw
from shapely.geometry import Point,box
from shapely.ops import unary_union,polygonize_full
from pyproj import Transformer
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.find_complete_neighbor_face_v4 import source
from scripts.locality_automation.isie_candidate_comparison import _map_geometry

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--codes',nargs='+',required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();s=read(a.manifest);c=active_control(s)
 if not set(a.codes)<=set(s['exactTargets']):raise ValueError('Outside finite targets')
 a.output.mkdir();report=[]
 for code in a.codes:
  case=next(q for q in s['cases']if q['officialCode']==code);inv,ref,lines=source(case);facts=[]
  with pymupdf.open(checked(case['sourcePdf']))as doc:
   page=doc[0];pix=page.get_pixmap(matrix=pymupdf.Matrix(1.8,1.8),alpha=False);original=Image.frombytes('RGB',(pix.width,pix.height),pix.samples);frame=box(*page.rect)
  for layer,network in [('red',lines['red']),('connected',[unary_union(lines[k])for k in lines])]:
   im=original.copy();draw=ImageDraw.Draw(im)
   for index,g in enumerate(polygonize_full(unary_union(network))[0].geoms):
    if g.area<50:continue
    path=a.output/(code+'-'+layer+'-'+str(index)+'-page.wkb');path.write_bytes(g.wkb)
    labels=[{'index':i,'text':l['text']}for i,l in enumerate(inv['labeledAreaLeads'])if g.covers(Point(l['centerPagePoints']))]
    admin={k:unary_union(lines[k]).intersection(g.buffer(-.5)).length for k in ['blue','black']}
    facts.append({'layer':layer,'index':index,'pageGeometry':pin(path),'areaM2':_map_geometry(g,ref).area,'wholeInsidePage':frame.covers(g),'insideLabels':labels,'interiorAdmin':admin})
    for ring in [g.exterior,*g.interiors]:draw.line([(x*1.8,y*1.8)for x,y in ring.coords],fill=(0,180,0),width=3)
    q=g.representative_point();draw.rectangle((q.x*1.8-3,q.y*1.8-3,q.x*1.8+33,q.y*1.8+18),fill='white');draw.text((q.x*1.8,q.y*1.8),str(index),fill='black')
   im.save(a.output/(code+'-'+layer+'.png'))
  design=np.column_stack((ref['pageControls'],np.ones(4)));projection=Transformer.from_crs(4326,32632,always_xy=True);targets=np.asarray([projection.transform(lon,lat)for lat,lon in ref['geographicControlsLatLon']]);loo=[float(np.linalg.norm(design[i]@np.linalg.solve(np.delete(design,i,0),np.delete(targets,i,0))-targets[i]))for i in range(4)]
  row={'code':code,'name':case['officialName'],'sourcePdf':case['sourcePdf'],'registrationDiagnostic':{'fitM':ref['maxControlResidualMeters'],'looM':max(loo)},'faces':facts,'credit':0};report.append(row);print(json.dumps({'code':code,'registrationDiagnostic':row['registrationDiagnostic'],'faces':[{'layer':f['layer'],'index':f['index'],'km2':round(f['areaM2']/1e6,3),'insidePage':f['wholeInsidePage'],'labels':f['insideLabels'],'admin':f['interiorAdmin']}for f in facts]},ensure_ascii=False))
 put(a.output/'report.json',{'rows':report,'manifest':pin(a.manifest.resolve()),'status':'DIAGNOSTIC_ONLY_REQUIRES_ROOT_ATTRIBUTION_AND_NATIVE_ADMISSION','credit':0})
if __name__=='__main__':main()
