"""Compare proposed source bodies with pinned completed family neighbor geometry only."""
import argparse,json,sys
from pathlib import Path
from pyproj import Transformer
from shapely import from_wkb
from shapely.ops import transform
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.packed_gps_replay import PackedGpsReplay
p=argparse.ArgumentParser();p.add_argument('--manifest',type=Path,required=True);p.add_argument('--facts',type=Path,required=True);p.add_argument('--baseline',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();s=read(a.manifest);active_control(s);baseline=read(a.baseline)
assets=R/'android-app/app/src/main/assets';replay=PackedGpsReplay(assets/'neighborhoods.json',assets/'neighborhoods.bin');claims=read(R/'scripts/neighborhoods/touil-reviews/original-pbf-source-facts.json')['administrativeCodeClaims'];byid={x['id']:x for x in replay.boundaries};to_m=Transformer.from_crs(4326,32632,always_xy=True).transform;neighbors={}
for code in baseline['alreadyCompleteFamilyCodes']:
 ids=[i for i in claims.get(code,[])if i in byid and byid[i]['kind']=='sector']
 if len(ids)!=1:raise ValueError('Completed neighbor identity ambiguous: '+code)
 neighbors[code]=transform(to_m,replay.geometry(ids[0]))
pairs=[]
for row in read(a.facts)['rows']:
 g=from_wkb(checked(row['sourceMetricGeometry']).read_bytes())
 for code,h in neighbors.items():
  if not g.envelope.buffer(5).intersects(h.envelope):continue
  area=g.intersection(h).area;shared=g.boundary.intersection(h.boundary.buffer(5)).length
  if area>1 or shared>10:pairs.append({'a':row['officialCode'],'completedNeighbor':code,'overlapM2':area,'overlapFractionSmaller':area/min(g.area,h.area),'sharedWithin5mM':shared})
result={'sourceFacts':pin(a.facts.resolve()),'completedBaseline':pin(a.baseline.resolve()),'existingMetadata':pin(assets/'neighborhoods.json'),'existingBinary':pin(assets/'neighborhoods.bin'),'pairs':pairs,'materialPairs':[r for r in pairs if r['overlapFractionSmaller']>.01],'unrelatedBehaviorChecks':0,'credit':0};put(a.output,result);print(json.dumps({'incidentPairs':len(pairs),'materialPairs':result['materialPairs'],'credit':0}))
