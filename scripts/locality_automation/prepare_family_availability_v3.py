"""Exact finite ISIE partition availability; original hash/URL gates preserved."""
import sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT))
from scripts.locality_automation import prepare_family_availability_v2 as original

if __name__=='__main__':
    source=Path(original.__file__).read_text(encoding='utf-8')
    source=source.replace("p.add_argument('--governorate-name')", "p.add_argument('--governorate-name',action='append')")
    source=source.replace("bits[0]==a.governorate_name", "bits[0] in a.governorate_name")
    assert "action='append'" in source and 'bits[0] in a.governorate_name' in source
    exec(compile(source,original.__file__,'exec'),{'__name__':'__main__','__file__':original.__file__})
