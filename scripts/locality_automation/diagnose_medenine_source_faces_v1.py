"""Render complete native face alternatives; no automatic selection or credit."""
import json,sys
from pathlib import Path
import pymupdf
from PIL import Image,ImageDraw
from shapely.geometry import LineString,Point,box
from shapely.ops import polygonize_full,unary_union,transform
from pyproj import Transformer
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.isie_candidate_comparison import _map_geometry
E=Path(r'C:\Users\barou\Documents\Codex\2026-09-06\the-android-app-app-currently-allows');W=E/'work/locality-efficiency-cycles-20261001/root-cycle24';spec=read(W/'medenine-source-successor-manifest-v2.json');active_control(spec)
out=W/'native-face-alternatives';out.mkdir();rows=[]
for case in spec['cases']:
 c=case['officialCode']
 if c not in ['525154','525251','525453','525956','525258']:continue
 inventory=read(checked(case['sourceInventory']))['pages'][0];reference=next(r for r in inventory['georeferences'] if r['status']=='fitted')
 lines={k:[]for k in ['red','blue','black']}
 for p in inventory['nativePaths']:
  if p['relevantLineworkHint'] and p['visibleStroke'] and p['strokeFamily']in lines:
   for run in p['runs']:
    if len(run['nativePagePoints'])>1:lines[p['strokeFamily']].append(LineString(run['nativePagePoints']))
 with pymupdf.open(checked(case['sourcePdf']))as doc:
  page=doc[0];rect=box(*page.rect);png=out/(c+'-original.png');page.get_pixmap(matrix=pymupdf.Matrix(1.8,1.8),alpha=False).save(png)
 for family in ['red','connected']:
  faces=polygonize_full(unary_union(lines['red'] if family=='red' else [v for ls in lines.values()for v in ls]))[0]
  for i,g in enumerate(faces.geoms):
   if g.area<50:continue
   metric=_map_geometry(g,reference);raw=transform(Transformer.from_crs(32632,4326,always_xy=True).transform,metric)
   pagepath=out/(c+'-'+family+'-'+str(i)+'-page.wkb');pagepath.write_bytes(g.wkb)
   fig=Image.open(png).convert('RGB');d=ImageDraw.Draw(fig);d.line([(x*1.8,y*1.8)for x,y in g.exterior.coords],fill=(0,210,0),width=6)
   render=out/(c+'-'+family+'-'+str(i)+'.png');fig.save(render)
   rows.append({'code':c,'layer':family,'index':i,'pageGeometry':pin(pagepath),'render':pin(render),'areaKm2':metric.area/1e6,'wholeInsidePage':rect.covers(g),'bounds':g.bounds,'insideLabels':[(j,l['text'])for j,l in enumerate(inventory['labeledAreaLeads'])if g.covers(Point(l['centerPagePoints']))]})
put(out/'report.json',{'candidates':rows,'credit':0});print(json.dumps(rows,ensure_ascii=False))
