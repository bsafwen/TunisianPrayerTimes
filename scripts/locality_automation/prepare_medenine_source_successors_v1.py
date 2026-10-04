"""Fix title-band selection and prepare explicit native recovery diagnostics."""
import json,sys
from pathlib import Path
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.isie_pdf_inventory import inspect
E=Path(r'C:\Users\barou\Documents\Codex\2026-09-06\the-android-app-app-currently-allows');M=E/'work/isie-execution-20260926/medenine';W=E/'work/locality-efficiency-cycles-20261001/root-cycle24'
s=read(W/'medenine-originals-manifest.json');active_control(s);old=read(W/'native-source-v1/native-source-review.json');retry={r['officialCode'] for r in old['rows'] if 'error' in r or r['officialCode'] in ['525157','525251','525453']}
rows=[r for r in s['cases'] if r['officialCode'] in retry]
for r in rows:
 if r['officialCode'] in ['525157','525453']:r['useAdministrativeFaces']=True
 if r['officialCode']=='525154':
  gate=read(M/'525154-isie-mfull-successor-20260928-v1/independent-gate.json');print('20MARS_LABEL',json.dumps(gate['labelIdentity'],ensure_ascii=False))
# INS and the actual legal roster spell الحميمة. Preserve the separate Ministry variant hold.
h=read(M/'525361-source-face-identity-hold-20260928-v1/decision.json');src=h['sourceFace']
rows.append({'officialCode':'525361','officialName':'الحميمة','officialParent':'بني خداش','appId':'osm:relation:7142965','sourcePdf':pin(E/src['pdf']['path']),'sourceInventory':pin(E/src['nativeInventory']['path']),'sourceUrl':'https://www.isie.tn/wp-content/uploads/2023/CartesCirconscriptionsElectoralesLocales2023/مدنين/بني خداش/الحميمة - (بني خداش).pdf','holdReasons':['Separate Ministry spelling equivalence unresolved; INS/ISIE exact official entry reviewed only.']})
for q in s['heldDownloadedSources']:
 pdf=checked(q['sourcePdf']);out=W/(q['insCode']+'-cached-download-inventory.json');put(out,inspect(pdf))
 r={'officialCode':q['insCode'],'officialName':q['ministryTitle'],'officialParent':'مدنين الشمالية','appId':'osm:relation:'+('7142971' if q['insCode']=='525151' else '7142935'),'sourcePdf':q['sourcePdf'],'sourceInventory':pin(out),'sourceUrl':q['indexedUrl'],'holdReasons':['Downloaded URL/body title mismatch unresolved']};rows.append(r)
put(W/'medenine-source-successor-manifest.json',{**s,'cases':rows})
print('retryCodes',[r['officialCode']for r in rows])
