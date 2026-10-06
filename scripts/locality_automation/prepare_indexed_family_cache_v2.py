"""Reuse exact ISIE index partitions, including numbered governorate districts."""
import sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT))
from scripts.locality_automation import prepare_indexed_family_cache_v1 as original

if __name__=='__main__':
    source=Path(original.__file__).read_text(encoding='utf-8')
    source=source.replace("p.add_argument('--governorate-name', required=True)", "p.add_argument('--governorate-name', required=True, action='append')")
    source=source.replace("bits[0]==a.governorate_name", "bits[0] in a.governorate_name")
    assert "action='append'" in source and 'bits[0] in a.governorate_name' in source
    exec(compile(source,original.__file__,'exec'),{'__name__':'__main__','__file__':original.__file__})
