"""Bounded current Medenine inventory, without rechecking accepted geometries."""
import json
from pathlib import Path
R=Path(__file__).resolve().parents[2]
E=Path(r'C:\Users\barou\Documents\Codex\2026-09-06\the-android-app-app-currently-allows')
def read(p): return json.loads(p.read_text(encoding='utf-8-sig'))
reg=read(R/'scripts/neighborhoods/touil-reviews/current-official-sector-registry.json')
print('REGISTRY_KEYS',list(reg))
rows=reg.get('sectors',reg.get('rows',[]))
if not rows: raise ValueError('registry rows key unknown')
rows=[r for r in rows if str(r.get('sectorCode','')).startswith('52')]
summary=read(E/'work/locality-progress-dashboard/task-report.json')['summary']
full=set(summary['explicitFullSourceBoundaryLocalityCodes']); geo=set(summary['validatedLocationCodes'])
comp=read(E/'work/isie-execution-20260926/governorate-face-comparator-v3/medenine-all-cached-v1/report.json')
cases={r['officialCode']:r for r in comp['cases']}
print('FIRST_REGISTRY',rows[:1]);print('CASE_KEYS',list(next(iter(cases.values()))))
for r in rows:
 c=r['sectorCode']; q=cases.get(c,{})
 if c not in full: print(json.dumps({'code':c,'name':r['sectorAr'], 'parent':r['delegationAr'], 'full':c in full,'geo':c in geo,'candidate':q.get('status'),'holds':q.get('holdReasons'),'appId':q.get('appId'),'cached':bool(q.get('sourcePdf'))},ensure_ascii=False))
print('TOTALS',len(rows),sum(r['sectorCode'] in full for r in rows),sum(r['sectorCode'] in geo for r in rows),len(cases))
