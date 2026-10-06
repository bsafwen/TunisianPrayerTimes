"""Verify refined projection independently with indexed original-vertex lookup."""
import argparse,json,sys,time
from pathlib import Path
import numpy as np
from pyproj import Transformer
from shapely import from_wkb,points,distance,STRtree
from shapely.ops import transform
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--prior-facts',type=Path,required=True);p.add_argument('--refined-review',type=Path,required=True);p.add_argument('--code',required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();started=time.monotonic();s=read(a.manifest);active_control(s)
 if a.code not in s['exactTargets']:raise ValueError('Outside finite source scope')
 old=read(a.prior_facts);rows=read(a.refined_review)['rows']
 if len(rows)!=1 or old['officialCode']!=a.code or rows[0]['officialCode']!=a.code:raise ValueError('Exact single target required')
 new=rows[0]
 for k in ['sourcePdf','sourceInventory','nativePageGeometry','sourceMetricGeometry','registration','registrationPolicy','nativeInteriorAdminLengthsPagePoints']:
  if old[k]!=new[k]:raise ValueError('An original source reference or gate changed')
 for k in ['sourcePdf','sourceInventory','nativePageGeometry','sourceMetricGeometry','geometry','rawSourceGeometry']:checked(new[k])
 metric=from_wkb(checked(new['sourceMetricGeometry']).read_bytes());raw=from_wkb(checked(new['rawSourceGeometry']).read_bytes());grid=from_wkb(checked(new['geometry']).read_bytes())
 if not metric.is_valid or not raw.is_valid or not grid.is_valid or raw.is_empty or grid.is_empty:raise ValueError('Original or converted topology held')
 inverse=Transformer.from_crs(4326,32632,always_xy=True).transform;back=transform(inverse,raw)
 def all_ring_coords(g):
  parts=[g]if g.geom_type=='Polygon'else list(g.geoms)
  return np.asarray([q for part in parts for ring in [part.exterior,*part.interiors]for q in ring.coords])
 sample_points=points(all_ring_coords(back));original_points=points(all_ring_coords(metric))
 worst_sample=float(np.max(distance(metric.boundary,sample_points)));_,d=STRtree(sample_points).query_nearest(original_points,all_matches=False,return_distance=True);missing=float(np.max(d))
 if len(d)!=len(original_points)or worst_sample>1e-8 or missing>1e-8:raise ValueError('An original point is missing or an added sample leaves the exact source edge')
 proof={'actualReviewer':'/root','independentAgentReviewClaimed':False,'algorithm':'Inverse-project every refined coordinate; vectorized distance to original perimeter plus STRtree nearest lookup for every original vertex. Independent of forward segmentization.','originalVerticesChecked':len(original_points),'samplesChecked':len(sample_points),'maxSampleDistanceM':worst_sample,'maxOriginalVertexMissingDistanceM':missing,'sourcePinsAndAllGatesUnchanged':True,'priorFacts':pin(a.prior_facts.resolve()),'refinedReview':pin(a.refined_review.resolve()),'elapsedSeconds':time.monotonic()-started,'credit':0}
 with a.output.open('x',encoding='utf-8')as f:json.dump(proof,f,indent=2)
 print(json.dumps(proof))
if __name__=='__main__':main()
