"""Try full labelled native target faces on directly adjacent official sheets."""
import sys
from pathlib import Path
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.isie_candidate_comparison import _name_match
E=Path(r'C:\Users\barou\Documents\Codex\2026-09-06\the-android-app-app-currently-allows');W=E/'work/locality-efficiency-cycles-20261001/root-cycle24'
s=read(W/'medenine-originals-manifest.json');active_control(s);rows={r['officialCode']:r for r in s['cases']}
for target,neighbors in [('525363',['525351','525352','525353','525362']),('525961',['525951','525955','525960'])]:
 original=rows[target]
 for owner in neighbors:
  src=rows[owner];leads=read(checked(src['sourceInventory']))['pages'][0]['labeledAreaLeads'];ids=[i for i,v in enumerate(leads)if v['centerPagePoints'][1]>100 and _name_match(v['text'],original['officialName'])]
  if len(ids)!=1:print(target,owner,'no unique in-map own label',ids);continue
  i=ids[0];r={**original,'sourcePdf':src['sourcePdf'],'sourceInventory':src['sourceInventory'],'sourceUrl':src['sourceUrl'],'diagnosticTargetLabelFace':None,'selectedSourceFace':{'targetLabelIndex':i,'targetLabelText':leads[i]['text']},'neighborSourceOwnerCode':owner,'originalOwnSourcePdf':original['sourcePdf'],'sourceAttributionRole':'Complete labelled target face on an adjacent original official ISIE map. Original target sheet registration HOLD retained; neighbor original four-control gate must pass.'}
  put(W/(target+'-neighbor-'+owner+'-manifest.json'),{**s,'cases':[r]});print(target,owner,'candidate')
