"""Pin root-reviewed exact native faces and measure pending registration; no credit."""
import sys,json
from pathlib import Path
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.isie_pdf_inventory import _georeferences
import pymupdf,pypdf,numpy as np
from pyproj import Transformer
w=Path.cwd();spec=read(w/'gabes-native-leads-v3/manifest.json');active_control(spec)
selected={'515551':('red',5),'515754':('red',0),'515755':('red',1)}
di=read(w/'gabes-held-native-diagnostics-v1/report.json')['candidates'];cases=[]
for case in spec['cases']:
 code=case['officialCode']
 if code not in selected:continue
 layer,index=selected[code];face=next(x for x in di if x['code']==code and x['layer']==layer and x['index']==index)
 cases.append({**case,'reviewedNativePageFace':face['pageGeometry'],'rootAttribution':'Root reviewed original own and adjoining ISIE sheets, selecting exact closed red face despite displaced caption. No altered coordinates, snapping or inferred cuts.'})
put(w/'gabes-explicit-faces-v1.json',{**spec,'cases':cases,'credit':0})
components=read(w/'gabes-numbered-captions-v2/manifest.json')['cases'];tchine=next(x for x in components if x['officialCode']=='515852'and x['declaredCircleIds']==[2]);put(w/'gabes-tchine2-v1.json',{**spec,'cases':[tchine]})
held=[next(x for x in spec['cases']if x['officialCode']==c)for c in ['515351','516151']]+[next(x for x in components if x['officialCode']=='515852'and x['declaredCircleIds']==[1])];rows=[]
proj=Transformer.from_crs(4326,32632,always_xy=True)
for case in held:
 with pymupdf.open(checked(case['sourcePdf']))as doc:
  ref=next(r for r in _georeferences(pypdf.PdfReader(checked(case['sourcePdf'])).pages[0],doc[0])if r['status']=='fitted')
 design=np.column_stack((ref['pageControls'],np.ones(4)));targets=np.asarray([proj.transform(lon,lat)for lat,lon in ref['geographicControlsLatLon']]);loo=[float(np.linalg.norm(design[n]@np.linalg.solve(np.delete(design,n,0),np.delete(targets,n,0))-targets[n]))for n in range(4)]
 rows.append({'officialCode':case['officialCode'],'name':case['officialName'],'sourcePdf':case['sourcePdf'],'sourceInventory':case['sourceInventory'],'fitResidualM':ref['maxControlResidualMeters'],'heldOutMaxM':max(loo),'credit':0})
put(w/'gabes-registration-measurements-v1.json',{'rows':rows,'credit':0});print(json.dumps(rows,ensure_ascii=False))
