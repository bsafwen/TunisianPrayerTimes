"""Medenine specialization of minimal changed-batch publication verification."""
import runpy,sys
from pathlib import Path
# Retain every target-map, pin and aware-hour check; specialize only membership.
p=Path(__file__).with_name('verify_target_batch_publication_v2.py')
source=p.read_text(encoding='utf-8').replace("str(r['code']).startswith(('5256','5257','5258'))","str(r['code']) in c['allMedenineOfficialCodes']")
exec(compile(source,str(p),'exec'),{'__name__':'__main__','__file__':str(p)})
