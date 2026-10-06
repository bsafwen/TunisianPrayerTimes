"""Prepare finite cached originals and case-specific unresolved source pins."""
import json,sys
from pathlib import Path
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
E=Path(r'C:\Users\barou\Documents\Codex\2026-09-06\the-android-app-app-currently-allows');C=E/'work/locality-efficiency-cycles-20261001';W=C/'root-cycle24';B=E/'work/isie-execution-20260926';M=B/'medenine'
c=read(C/'control.json');spec={k:c[k] for k in ['iteration','windowStartUtc','deadlineUtc']};spec.update(control=str(C/'control.json'),owner='/root');active_control(spec)
cases={r['officialCode']:r for r in read(B/'governorate-face-comparator-v3/medenine-all-cached-v1/report.json')['cases']}
for r in read(M/'ben-guerdane-source-face-review-v1/comparator-output/report.json')['cases']:cases[r['officialCode']]=r
for r in read(M/'five-new-source-face-proposal-v1/report.json')['rows']:
 if r['officialCode'] not in c['approvedCycle24AcceptancePool']:continue
 cases[r['officialCode']]={**r,'sourcePdf':pin(M/'five-new-source-face-proposal-v1'/r['sourcePdfPath']),'sourceInventory':pin(M/'five-new-source-face-proposal-v1'/r['sourceInventoryPath']),'selectedSourceFace':r['pagePin']}
# Original unresolved index downloads are preserved and rendered, never accepted by URL alone.
unresolved=[]
for r in read(M/'missing-source-acquisition-v2/report.json')['cases']:
 if r['insCode'] not in ['525151','525152']:continue
 unresolved.append({**r,'sourcePdf':pin(M/'missing-source-acquisition-v2'/r['pdfPath'])})
support=['525257','525258','525458','525460','525462'];targets=c['approvedCycle24AcceptancePool']
selected=[cases[v] for v in targets+support if v in cases and cases[v].get('sourcePdf')]
put(W/'medenine-originals-manifest.json',{**spec,'exactTargets':targets,'cases':selected,'unavailableCodes':[v for v in targets if v not in cases or not cases[v].get('sourcePdf')],'heldDownloadedSources':unresolved})
put(W/'cached-source-selection.json',{'targets':targets,'cachedCandidates':len(selected),'supportingNeighbors':support,'unavailableCodes':[v for v in targets if v not in cases or not cases[v].get('sourcePdf')],'unresolvedDownloadedSourceCodes':[r['insCode'] for r in unresolved],'credit':0})
print(json.dumps(read(W/'cached-source-selection.json')))
