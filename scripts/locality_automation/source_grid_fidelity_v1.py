"""Use the established literal-union rounding gate for early source diagnostics.

This is the same per-constituent and mutual 10 cm polygon-domain gate in
audit_source_batch_minimum_v3.check_source_patch. It does not accept scope.
"""
from shapely import from_wkb,set_precision
from shapely.ops import transform,unary_union
from scripts.locality_automation.run_sealed_boundary_queue import checked

def check_source_grid(row,to_m):
 raw=from_wkb(checked(row['rawSourceGeometry']).read_bytes());grid=from_wkb(checked(row['geometry']).read_bytes())
 if not raw.is_valid or not grid.is_valid or raw.is_empty or grid.is_empty:raise ValueError('Invalid source/grid geometry')
 delta=transform(to_m,raw).hausdorff_distance(transform(to_m,grid))
 result={'rawToGridHausdorffM':delta,'maximumErrorM':.1,'metricMethod':'original GEOS vertex Hausdorff'}
 if delta>=.1 and row.get('literalConstituentUnion'):
  parts=row['constituentNativeFacts'];expected=unary_union([from_wkb(checked(v['rawSourceGeometry']).read_bytes())for v in parts])
  if not raw.equals(expected)or not grid.equals(set_precision(expected,1e-6)):raise ValueError('Exact original constituent union differs')
  component_deltas=[transform(to_m,from_wkb(checked(v['rawSourceGeometry']).read_bytes())).hausdorff_distance(transform(to_m,from_wkb(checked(v['geometry']).read_bytes())))for v in parts]
  source_m,decoded_m=transform(to_m,raw),transform(to_m,grid)
  source_outside=source_m.difference(decoded_m.buffer(.1));decoded_outside=decoded_m.difference(source_m.buffer(.1))
  if any(v>=.1 for v in component_deltas)or not source_outside.is_empty or not decoded_outside.is_empty:raise ValueError('Constituent union exceeds original 10cm rounding bound')
  result.update(metricMethod='Per-constituent original Hausdorff plus mutual 10cm filled-polygon domain enclosure for literal union; tiny sub-grid seam holes may close',componentRawToGridHausdorffM=component_deltas,sourceOutsideDecoded10cmBufferM2=source_outside.area,decodedOutsideSource10cmBufferM2=decoded_outside.area)
 elif delta>=.1:raise ValueError('Raw source exceeds original 10cm grid rounding bound')
 return result
