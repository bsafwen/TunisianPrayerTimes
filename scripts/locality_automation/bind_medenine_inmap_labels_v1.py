"""Explicit unique in-map text bindings; title-band duplicates excluded."""
import sys,json
from pathlib import Path
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
E=Path(r'C:\Users\barou\Documents\Codex\2026-09-06\the-android-app-app-currently-allows');W=E/'work/locality-efficiency-cycles-20261001/root-cycle24'
sys.path.insert(0,str(E/'work/isie-execution-20260926/parent-qualified-label-matcher-v1'));from matcher import candidate_label_indices
s=read(W/'medenine-source-successor-manifest.json');active_control(s)
for r in s['cases']:
 labels=read(checked(r['sourceInventory']))['pages'][0]['labeledAreaLeads'];eligible=[(i,v['text'])for i,v in enumerate(labels)if v['centerPagePoints'][1]>100]
 d=r.get('diagnosticTargetLabelFace') or r.get('selectedSourceFace') or {}
 if d.get('targetLabelIndex') is not None:continue
 ids=candidate_label_indices([v for _,v in eligible],official_title=r['officialName'],expected_parent=r['officialParent'])
 if len(ids)==1:
  i=eligible[ids[0]][0];r['selectedSourceFace']={'targetLabelIndex':i,'targetLabelText':labels[i]['text']};print(r['officialCode'],i,labels[i]['text'])
put(W/'medenine-source-successor-manifest-v2.json',s)
