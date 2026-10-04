"""Only adjacent sheets actually displaying the Qasr Jdid label are considered."""
import sys
from pathlib import Path
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.isie_candidate_comparison import _name_match
E=Path(r'C:\Users\barou\Documents\Codex\2026-09-06\the-android-app-app-currently-allows');W=E/'work/locality-efficiency-cycles-20261001/root-cycle24'
s=read(W/'medenine-originals-manifest.json');active_control(s);rows={r['officialCode']:r for r in s['cases']}
for r in read(W/'medenine-source-successor-manifest-v2.json')['cases']:rows[r['officialCode']]=r
target=rows['525363'];candidates=[]
for owner in ['525151','525156','525158','525351','525352','525353','525362']:
 src=rows[owner];inv=read(checked(src['sourceInventory']))['pages'][0];leads=inv['labeledAreaLeads'];ids=[i for i,v in enumerate(leads)if _name_match(v['text'],target['officialName']) and not(v['centerPagePoints'][0]>.78*inv['pageSizePoints'][0] and v['centerPagePoints'][1]<100)]
 if len(ids)!=1:continue
 i=ids[0];r={**target,'sourcePdf':src['sourcePdf'],'sourceInventory':src['sourceInventory'],'sourceUrl':src['sourceUrl'],'diagnosticTargetLabelFace':None,'selectedSourceFace':{'targetLabelIndex':i,'targetLabelText':leads[i]['text']},'neighborSourceOwnerCode':owner,'reviewedInMapLabel':True,'originalOwnSourcePdf':target['sourcePdf'],'sourceAttributionRole':'Adjacent original official source, explicit visibly in-map QasrJdid text; native full face and original registration still required.'}
 put(W/('525363-peer-'+owner+'-manifest.json'),{**s,'cases':[r]});candidates.append(owner)
print(candidates)
