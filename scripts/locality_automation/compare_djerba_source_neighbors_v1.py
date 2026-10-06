"""Measure literal Djerba source-face adjacency and displaced annotation facts."""
import argparse, json, sys
from pathlib import Path
from shapely import wkb
from shapely.geometry import Point
from pyproj import Transformer
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import read, checked, pin
from scripts.locality_automation.audit_reviewed_source_family import put

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--source',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    data=read(a.source);rows={v['officialCode']:v for v in data['rows']}
    polys={c:wkb.loads(checked(v['sourceMetricGeometry']).read_bytes()) for c,v in rows.items()}
    project=Transformer.from_crs(4326,32632,always_xy=True).transform
    pairs=[]
    for c,d in [('525657','525659'),('525756','525659'),('525654','525656'),('525651','525652'),('525651','525654'),('525652','525653'),('525653','525658'),('525657','525658'),('525655','525656'),('525655','525751'),('525751','525752'),('525752','525753'),('525752','525754'),('525753','525754'),('525754','525757'),('525756','525757')]:
        x,y=polys[c],polys[d]
        pairs.append({'codes':[c,d],'overlapM2':x.intersection(y).area,'boundaryWithin5mLengthM':x.boundary.intersection(y.boundary.buffer(5)).length,'minimumBoundaryDistanceM':x.boundary.distance(y.boundary)})
    foreign=[]
    for c,d in [('525657','525659'),('525756','525659'),('525654','525656')]:
        for lab in rows[c]['insideLabels']:
            if lab['own']:continue
            point=Point(*project(lab['lng'],lab['lat']))
            foreign.append({'code':c,'printedNeighbor':d,'text':lab['text'],'centerInsideOwnSource':polys[c].contains(point),'centerInsideNeighborOwnSheetFace':polys[d].contains(point),'outsideNeighborDistanceM':point.distance(polys[d]),'insideSourceEdgeDistanceM':point.distance(polys[c].boundary)})
    put(a.output,{'status':'LITERAL_PAIRED_SOURCE_FACTS_NO_SNAPPING_OR_OWNERSHIP_EDIT','inputs':[pin(a.source)],'pairs':pairs,'foreignLabelCenters':foreign,'independentAgentReviewClaimed':False})
    print(json.dumps({'pairs':pairs,'foreignLabelCenters':foreign},ensure_ascii=False))
