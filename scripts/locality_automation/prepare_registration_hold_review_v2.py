"""Prepare finite, explicitly held source boundaries for human review. Never accept."""
import argparse,json,sys
from pathlib import Path
from datetime import datetime,timezone
import numpy as np,pypdf,pymupdf
from PIL import Image,ImageDraw
from pyproj import Transformer
from shapely import from_wkb,set_precision
from shapely.geometry import LineString,Polygon,box,mapping
from shapely.ops import polygonize_full,unary_union,transform
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.isie_pdf_inventory import _georeferences,_path_runs
from scripts.locality_automation.isie_candidate_comparison import _map_geometry

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--plan',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
 plan=read(a.plan);control=active_control(plan);codes=[r['officialCode']for r in plan['cases']]
 if not codes or len(set(codes))!=len(codes)or not set(codes)<=set(control['pendingSourceCodes']):raise ValueError('Only explicit pending unique entries permitted')
 out=a.output.resolve();out.mkdir();rows=[];features=[];ll=Transformer.from_crs(32632,4326,always_xy=True).transform;project=Transformer.from_crs(4326,32632,always_xy=True)
 for case in plan['cases']:
  parts=[];raws=[]
  for i,component in enumerate(case['components'],1):
   pdf=checked(component['sourcePdf']);inventory=read(checked(component['sourceInventory']))['pages'][0];native=from_wkb(checked(component['nativeFace']).read_bytes())
   with pymupdf.open(pdf)as doc:
    page=doc[0];ref=next(r for r in _georeferences(pypdf.PdfReader(pdf).pages[0],page)if r['status']=='fitted')
    if ref['crsEpsg']!=32632 or ref['controlCount']!=4:raise ValueError('Original four-control reference required')
    design=np.column_stack((ref['pageControls'],np.ones(4)));targets=np.asarray([project.transform(lon,lat)for lat,lon in ref['geographicControlsLatLon']]);loo=[float(np.linalg.norm(design[n]@np.linalg.solve(np.delete(design,n,0),np.delete(targets,n,0))-targets[n]))for n in range(4)]
    lines={k:[]for k in ['red','blue','black']};frames=[];drawings=page.get_drawings()
    for item in inventory['nativePaths']:
     if not item['relevantLineworkHint']or not item['visibleStroke']or item['strokeFamily']not in lines:continue
     runs,unsupported=_path_runs(drawings[item['drawingIndex']])
     if unsupported or len(runs)!=len(item['runs']):raise ValueError('Original native path unsupported or differs')
     for n,points in enumerate(runs):
      if [list(q)for q in points]!=item['runs'][n]['nativePagePoints']:raise ValueError('Original native points differ')
      frame=False
      if item['strokeFamily']=='black'and len(points)==5 and points[0]==points[-1]:
       rect=Polygon(points);frame=rect.is_valid and rect.equals(box(*rect.bounds))and rect.area>page.rect.width*page.rect.height*.8
       if frame:frames.append(rect.boundary)
      if len(points)>1 and not frame:lines[item['strokeFamily']].append(LineString(points))
    merged={k:unary_union(v)for k,v in lines.items()};faces=polygonize_full(merged['red'])[0]
    if sum(g.equals_exact(native,0)for g in faces.geoms)!=1 or not native.is_valid:raise ValueError('Selected held face is not an exact unique original red face')
    if not box(*page.rect).covers(native)or any(native.boundary.intersection(f).length>.01 for f in frames):raise ValueError('Held face uses frame or leaves original printed page')
    admin={k:float(g.intersection(native.buffer(-.5)).length)for k,g in merged.items()if k!='red'}
    if any(admin.values()):raise ValueError('Interior native administrative divider requires separate review')
    metric=_map_geometry(native,ref);raw=transform(ll,metric);raws.append(raw)
    png=out/(case['officialCode']+'-part-'+str(i)+'.png');page.get_pixmap(matrix=pymupdf.Matrix(1.8,1.8),alpha=False).save(png)
   image=Image.open(png).convert('RGB');draw=ImageDraw.Draw(image)
   for ring in [native.exterior,*native.interiors]:draw.line([(x*1.8,y*1.8)for x,y in ring.coords],fill=(0,180,0),width=5)
   image.save(png)
   fit=float(ref['maxControlResidualMeters']);held=max(loo)
   parts.append({'sourcePdf':pin(pdf),'sourceInventory':pin(checked(component['sourceInventory'])),'nativeFace':pin(checked(component['nativeFace'])),'sourceUrl':component['sourceUrl'],'circle':component['circle'],'fitResidualM':fit,'heldOutMaxM':held,'standingFitLimitM':.5,'standingHeldOutLimitM':1.5,'registrationHeld':fit>.5 or held>1.5,'sourceAreaKm2':metric.area/1e6,'originalNativePointsMatchSavedInventory':True,'wholeNativeFaceInsidePage':True,'usesPrintedFrame':False,'nativeInteriorAdminLengthsPagePoints':admin,'render':pin(png)})
  if not any(r['registrationHeld']for r in parts):raise ValueError('A registration-hold review requires an actual held part')
  raw=unary_union(raws);grid=set_precision(raw,1e-6)
  if raw.is_empty or not raw.is_valid or not grid.is_valid:raise ValueError('Literal held union invalid')
  row={'officialCode':case['officialCode'],'name':case['name'],'status':'HOLD_REQUIRES_HUMAN_REGISTRATION_EXCEPTION_AND_TARGET_QA','sourceScopeAccepted':False,'credit':0,'parts':parts,'requestedEntryOnlyLimits':case['requestedEntryOnlyLimits'],'GoogleObservation':case['GoogleObservation'],'remainingChecks':['Explicit human registration exception','Root final source admission','Only these changed entries: packed geometry, GPS/prayer/saved-state JVM replay, guarded installation and publication']};rows.append(row)
  features.append({'type':'Feature','properties':{'officialCode':case['officialCode'],'name':case['name'],'status':row['status'],'accepted':False},'geometry':mapping(grid)})
 report={'preparedAtUtc':datetime.now(timezone.utc).isoformat(),'plan':pin(a.plan.resolve()),'rows':rows,'credit':0,'qualification':'Review-only preview. Exact original red faces; literal union without snapping. Original registration holds remain. No asset or acceptance ledger mutation.'}
 (out/'review-facts.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');(out/'pending-boundaries.geojson').write_text(json.dumps({'type':'FeatureCollection','features':features},ensure_ascii=False),encoding='utf-8')
 md=['# '+plan['familyName']+': pending registration decisions','','These previews are unaccepted; no new locations or corrections are credited. The standing limits are fit residual <=0.5m and held-out maximum <=1.5m. Prior entry-specific exceptions do not apply to these sources.','','| Entry | Original part | Fit residual m | Held-out maximum m | Requested entry-only limit |','|---|---|---:|---:|---|']
 for r in rows:
  for part in r['parts']:md.append('|'+r['name']+' ('+r['officialCode']+')|'+str(part['circle'])+'|'+format(part['fitResidualM'],'.6f')+'|'+format(part['heldOutMaxM'],'.6f')+'|'+json.dumps(r['requestedEntryOnlyLimits'])+'|')
 md+=['','The requested limits describe PDF coordinate registration, not GPS accuracy or a claim of surveyed boundary accuracy. Exact native boundary vectors and all other checks remain. Only the changed entries would receive the minimal final replay after approval.']
 for r in rows:
  md+=['','## '+r['name'],'',r['GoogleObservation']['description']]
  if r['GoogleObservation'].get('image'):md+=['','Actual emulator satellite capture: [image](<'+str(checked(r['GoogleObservation']['image'])).replace('\\','/')+'>).']
  for part in r['parts']:md+=['','[Original ISIE PDF]('+part['sourceUrl']+')','', '![Exact original red face; green overlay is the pending perimeter](<'+part['render']['file'].replace('\\','/')+'>)']
 for ref in plan['neighborSearchReports']:checked(ref)
 md+=['',plan['neighborReviewDescription'],'','[Pending GeoJSON](pending-boundaries.geojson) and [pinned facts](review-facts.json) are review artifacts only.']
 (out/'REVIEW.md').write_text('\n'.join(md)+'\n',encoding='utf-8');print(json.dumps({'pending':codes,'nativePartsCompared':sum(len(r['parts'])for r in rows),'credit':0,'review':str(out/'REVIEW.md')}))
if __name__=='__main__':main()
