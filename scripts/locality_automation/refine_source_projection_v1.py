"""Preserve an exact projected source perimeter while resolving projection chord crossings.

This only refines straight EPSG:32632 segments before nonlinear WGS84 conversion.
It never moves source vertices, repairs a source polygon, snaps neighbors, or accepts it.
"""
import argparse,json,sys
from pathlib import Path
import pymupdf,pypdf
from pyproj import Transformer
from shapely import from_wkb,segmentize,set_precision,is_valid_reason
from shapely.ops import transform
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import read,checked,pin,active_control
from scripts.locality_automation.isie_pdf_inventory import _georeferences
from scripts.locality_automation.isie_candidate_comparison import _map_geometry
from scripts.locality_automation.registration_policy_v2 import require_registration

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--facts',type=Path,required=True);p.add_argument('--code',required=True);p.add_argument('--max-segment-m',type=float,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
 s=read(a.manifest);c=active_control(s)
 if a.code not in s['exactTargets']or a.code not in c['pendingSourceCodes']or not 0<a.max_segment_m<=50:raise ValueError('Explicit pending target and bounded segment refinement required')
 r=read(a.facts)
 if r['officialCode']!=a.code or r.get('constituentNativeFacts'):raise ValueError('Exactly one original finite source body required')
 for key in ['sourcePdf','sourceInventory','originalRender','geometry','rawSourceGeometry','sourceMetricGeometry','nativePageGeometry']:checked(r[key])
 native=from_wkb(checked(r['nativePageGeometry']).read_bytes());metric=from_wkb(checked(r['sourceMetricGeometry']).read_bytes());old_raw=from_wkb(checked(r['rawSourceGeometry']).read_bytes())
 if not native.is_valid or not metric.is_valid or old_raw.is_valid:raise ValueError('Only a coordinate-conversion failure of valid original geometry may be refined')
 with pymupdf.open(checked(r['sourcePdf']))as doc:
  ref=next(v for v in _georeferences(pypdf.PdfReader(checked(r['sourcePdf'])).pages[0],doc[0])if v['status']=='fitted')
 if ref['crsEpsg']!=32632 or not _map_geometry(native,ref).equals_exact(metric,0):raise ValueError('Fresh exact native affine mapping differs')
 require_registration(a.code,r['sourcePdf'],r['registration'],c)
 forward=Transformer.from_crs(32632,4326,always_xy=True).transform;back=Transformer.from_crs(4326,32632,always_xy=True).transform
 dense=segmentize(metric,a.max_segment_m);fine=segmentize(metric,a.max_segment_m/2)
 hd=dense.hausdorff_distance(metric);difference=dense.symmetric_difference(metric).area
 if not dense.is_valid or hd>1e-8 or difference>1e-5:raise ValueError('Source perimeter was changed by refinement')
 raw=transform(forward,dense);fine_raw=transform(forward,fine)
 if not raw.is_valid or not fine_raw.is_valid:raise ValueError('Refined coordinate conversion still invalid')
 inverse=transform(back,raw);roundtrip=inverse.hausdorff_distance(metric)
 convergence=raw.hausdorff_distance(fine_raw)*111320
 if roundtrip>1e-8 or convergence>.01:raise ValueError('Inverse source fidelity or projection convergence failed')
 grid=set_precision(raw,1e-6)
 if not grid.is_valid or grid.is_empty:raise ValueError('Existing app-grid representation failed')
 out=a.output.resolve();out.mkdir()
 def save(name,g):
  f=out/name
  with f.open('xb')as stream:stream.write(g.wkb)
  return pin(f)
 old_refs={k:r[k]for k in ['rawSourceGeometry','geometry']};r['rawSourceGeometry']=save(a.code+'-raw.wkb',raw);r['geometry']=save(a.code+'-grid.wkb',grid)
 proof={'sourceFacts':pin(a.facts.resolve()),'originalFailure':is_valid_reason(old_raw),'maxSegmentM':a.max_segment_m,'comparisonSegmentM':a.max_segment_m/2,'sourceHausdorffM':hd,'sourceSymmetricDifferenceM2':difference,'inverseRoundTripHausdorffM':roundtrip,'projectionConvergenceBoundM':convergence,'originalSourceReferencesUnchanged':True,'allOriginalMetricVerticesRetained':True,'sourcePerimeterVerticesMoved':0,'nativeOrMetricGeometryRepaired':False,'snappingApplied':False,'originalCoordinateReferences':old_refs,'qualification':'Only extra collinear metric samples before nonlinear geographic projection. Original native and projected source shapes unchanged to floating-point roundoff; no source, registration, administration, or admission gate relaxed. Source-only credit zero.','credit':0}
 proof_path=out/'projection-refinement.json';proof_path.write_text(json.dumps(proof,indent=2),encoding='utf-8');r['coordinateProjectionRefinement']=pin(proof_path)
 (out/'native-source-review.json').write_text(json.dumps({'rows':[r],'sourceScopeAccepted':False,'credit':0},ensure_ascii=False,indent=2),encoding='utf-8')
 print(json.dumps({'code':a.code,'rawValid':raw.is_valid,'gridValid':grid.is_valid,'sourceHausdorffM':hd,'inverseRoundTripM':roundtrip,'convergenceBoundM':convergence,'credit':0}))
if __name__=='__main__':main()
