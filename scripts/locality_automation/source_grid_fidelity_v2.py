"""Use the unchanged ordinary grid gate and exact human-approved hairline policy."""
from shapely import from_wkb
from shapely.ops import transform
from scripts.locality_automation.run_sealed_boundary_queue import checked
from scripts.locality_automation.source_grid_fidelity_v1 import check_source_grid as ordinary_check
from scripts.locality_automation.precision_artifact_policy_v1 import require_precision_artifact

def check_source_grid(row,to_m,control):
    try:
        return ordinary_check(row,to_m)
    except ValueError as error:
        if str(error)!='Raw source exceeds original 10cm grid rounding bound':
            raise
        raw=from_wkb(checked(row['rawSourceGeometry']).read_bytes())
        grid=from_wkb(checked(row['geometry']).read_bytes())
        source_m,decoded_m=transform(to_m,raw),transform(to_m,grid)
        decision=require_precision_artifact(row['officialCode'],row,control,
            source_m.difference(decoded_m.buffer(.1)).area,
            decoded_m.difference(source_m.buffer(.1)).area)
        return {'rawToGridHausdorffM':source_m.hausdorff_distance(decoded_m),
                'maximumErrorM':.1,'metricMethod':'original GEOS vertex Hausdorff',
                'entryOnlyArtifactPolicy':decision}

