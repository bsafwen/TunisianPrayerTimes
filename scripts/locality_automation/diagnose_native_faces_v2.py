"""Render original native face alternatives for a finite explicit review subset."""
import argparse,json,sys
from pathlib import Path
import pymupdf
from PIL import Image,ImageDraw
from shapely.geometry import LineString,Point,Polygon,box
from shapely.ops import polygonize_full,unary_union
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.isie_candidate_comparison import _map_geometry

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--codes',nargs='+',required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
 spec=read(a.manifest);control=active_control(spec);pool=control['approvedCycle'+str(control['iteration'])+'AcceptancePool']
 if not set(a.codes)<=set(pool):raise ValueError('Diagnostic subset outside active finite scope')
 out=a.output.resolve();out.mkdir();rows=[]
 for case in spec['cases']:
  c=case['officialCode'];tag=c+('-circle-'+str(case['declaredCircleIds'][0])if case.get('declaredCircleIds')else '')
  if c not in a.codes:continue
  inventory=read(checked(case['sourceInventory']))['pages'][0];reference=next(r for r in inventory['georeferences']if r['status']=='fitted')
  lines={k:[]for k in ['red','blue','black']}
  for q in inventory['nativePaths']:
   if q['relevantLineworkHint']and q['visibleStroke']and q['strokeFamily']in lines:
    for run in q['runs']:
     points=run['nativePagePoints'];is_frame=False
     if q['strokeFamily']=='black'and len(points)==5 and points[0]==points[-1]:
      rectangle=Polygon(points);is_frame=rectangle.is_valid and rectangle.equals(box(*rectangle.bounds))and rectangle.area>450000
     if len(points)>1 and not is_frame:lines[q['strokeFamily']].append(LineString(points))
  with pymupdf.open(checked(case['sourcePdf']))as doc:
   page=doc[0];rect=box(*page.rect);png=out/(tag+'-original.png');page.get_pixmap(matrix=pymupdf.Matrix(1.8,1.8),alpha=False).save(png)
  for family in ['red','connected']:
   faces=polygonize_full(unary_union(lines['red']if family=='red'else[v for ls in lines.values()for v in ls]))[0]
   for i,g in enumerate(faces.geoms):
    if g.area<50:continue
    metric=_map_geometry(g,reference);pagepath=out/(tag+'-'+family+'-'+str(i)+'-page.wkb');pagepath.write_bytes(g.wkb)
    fig=Image.open(png).convert('RGB');d=ImageDraw.Draw(fig)
    for ring in [g.exterior,*g.interiors]:d.line([(x*1.8,y*1.8)for x,y in ring.coords],fill=(0,210,0),width=5)
    render=out/(tag+'-'+family+'-'+str(i)+'.png');fig.save(render)
    rows.append({'code':c,'sourcePdf':case['sourcePdf'],'sourceInventory':case['sourceInventory'],'declaredCircleIds':case.get('declaredCircleIds',[]),'layer':family,'index':i,'pageGeometry':pin(pagepath),'render':pin(render),'areaKm2':metric.area/1e6,'wholeInsidePage':rect.covers(g),'bounds':g.bounds,'insideLabels':[(j,l['text'])for j,l in enumerate(inventory['labeledAreaLeads'])if g.covers(Point(l['centerPagePoints']))]})
 put(out/'report.json',{'candidates':rows,'credit':0,'limits':'Diagnostic original linework only; no registration, attribution or adoption acceptance.'})
 print(json.dumps([{k:r[k]for k in ['code','layer','index','areaKm2','wholeInsidePage','insideLabels']}for r in rows],ensure_ascii=False))
if __name__=='__main__':main()
