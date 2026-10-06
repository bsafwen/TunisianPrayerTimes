"""Family entry point with mandatory name/code agreement before any mutation."""
import argparse,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT))
from scripts.locality_automation.family_work_v2 import opened,sources,norm
from scripts.locality_automation.run_sealed_boundary_queue import read
def validate_identity(code,name,registry):
    rows=[r for r in registry['sectors']if r['governorateCode']==code]
    if not rows or {norm(r['governorateAr'])for r in rows}!={norm(name)}:
        raise ValueError('Requested governorate name/code does not agree with current INS registry')
    return rows
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('phase',choices=['open','sources']);p.add_argument('--family',required=True);p.add_argument('--evidence',type=Path,required=True);p.add_argument('--governorate-code',required=True);p.add_argument('--governorate-name',required=True);p.add_argument('--work',type=Path);p.add_argument('--availability',type=Path);a=p.parse_args()
    validate_identity(a.governorate_code,a.governorate_name,read(ROOT/'scripts/neighborhoods/touil-reviews/current-official-sector-registry.json'))
    opened(a)if a.phase=='open'else sources(a)
