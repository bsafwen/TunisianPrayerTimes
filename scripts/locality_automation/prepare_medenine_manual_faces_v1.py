"""Freeze observed original labels and a native face requiring paired attribution."""
import sys
from pathlib import Path
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
E=Path(r'C:\Users\barou\Documents\Codex\2026-09-06\the-android-app-app-currently-allows');W=E/'work/locality-efficiency-cycles-20261001/root-cycle24'
s=read(W/'medenine-source-successor-manifest-v2.json');active_control(s)
selected=[]
for r in s['cases']:
 c=r['officialCode']
 if c not in ['525154','525251','525956','525258']:continue
 if c=='525154':r['selectedSourceFace']={'targetLabelIndex':0,'targetLabelText':'20 ( )ﻣدﻧﯾن اﻟﺷﻣﺎﻟﯾﺔ- ﻣﺎرس'}
 if c=='525956':r['selectedSourceFace']={'targetLabelIndex':5,'targetLabelText':'(فوﻠﺧﻣ يدﯾﺳ) - ةرﻣﻋ'}
 if c=='525258':r['selectedSourceFace']={'targetLabelIndex':0,'targetLabelText':'(ﺔﯾﺑوﻧﺟﻟا نﯾﻧدﻣ) - رﯾطﯾوﺳﻟا'}
 if c=='525251':
  r['reviewedNativePageFace']=pin(W/'native-face-alternatives/525251-red-1-page.wkb')
  r['selectionExplanation']='Diagnostic complete unlabeled urban face east of N19. Requires exact paired attribution against 2Mai/MedenineEast/Labba own sheets; printed MedenineSud label is inside Labba. Google query shows delegation scope and cannot identify this imada.'
 selected.append(r)
put(W/'medenine-source-successor-manifest-v3.json',{**s,'cases':selected})
