"""Diagnose one held raw/grid geometry feature without changing either geometry or gates."""
import argparse,json,sys
from pathlib import Path
import numpy as np,pymupdf
from shapely import from_wkb
from shapely.geometry import Point
from shapely.ops import transform,nearest_points
from pyproj import Transformer
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
p=argparse.ArgumentParser(description=__doc__)
for k in ['manifest','facts','output']:p.add_argument('--'+k,type=Path,required=True)
p.add_argument('--code',required=True);a=p.parse_args()
s=read(a.manifest);c=active_control(s);assert a.code in s['exactTargets'] and a.code in c['pendingSourceCodes']
r=next(v for v in read(a.facts)['rows']if v['officialCode']==a.code);assert 'error'not in r
project=Transformer.from_crs(4326,32632,always_xy=True).transform
raw=transform(project,from_wkb(checked(r['rawSourceGeometry']).read_bytes()))
grid=transform(project,from_wkb(checked(r['geometry']).read_bytes()))
def vertices(g):
 for part in ([g]if g.geom_type=='Polygon'else g.geoms):
  for ring in [part.exterior,*part.interiors]:
   for xy in ring.coords:yield xy
def rings(g):
 return [len(v.interiors)for v in ([g]if g.geom_type=='Polygon'else g.geoms)]
worst=max((Point(xy).distance(grid.boundary),xy)for xy in vertices(raw))
outside=raw.difference(grid.buffer(.1));reverse=grid.difference(raw.buffer(.1))
metrics={'originalRawToGridHausdorffM':raw.hausdorff_distance(grid),'rawInteriorRingCounts':rings(raw),'gridInteriorRingCounts':rings(grid),'rawGridSymmetricDifferenceM2':raw.symmetric_difference(grid).area,'sourceOutsideGrid10cmBufferM2':outside.area,'gridOutsideSource10cmBufferM2':reverse.area,'sourceAndGridMutuallyWithin10cmFilledDomain':outside.is_empty and reverse.is_empty,'worstRawBoundaryVertexToGridBoundaryM':worst[0],'worstRawMetricPoint':list(worst[1]),'nearestGridBoundaryMetricPoint':list(nearest_points(Point(worst[1]),grid.boundary)[1].coords)[0]}
matrix=np.array(r['registration']['matrix']);pagepoint=np.linalg.solve(matrix[:2].T,np.array(worst[1])-matrix[2])
out=a.output.resolve();out.mkdir()
pdf=checked(r['sourcePdf'])
with pymupdf.open(pdf)as doc:
 page=doc[0];rect=pymupdf.Rect(pagepoint[0]-10,pagepoint[1]-10,pagepoint[0]+10,pagepoint[1]+10)&page.rect
 image=out/'worst-feature-original.png';page.get_pixmap(matrix=pymupdf.Matrix(12,12),clip=rect,alpha=False).save(image)
metrics.update(worstNativePagePoint=pagepoint.tolist(),originalClip=pin(image),originalFacts=pin(a.facts.resolve()),sourcePdf=r['sourcePdf'],sourceScopeAccepted=False,gateChanged=False,credit=0)
put(out/'report.json',metrics);print(json.dumps(metrics))

