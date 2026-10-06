"""Prepare a reviewable final-entry decision; no gate change or publication."""
import json,sys
from pathlib import Path
from datetime import datetime,timezone
import numpy as np,pymupdf,pypdf
from PIL import Image,ImageDraw
from pyproj import Transformer
from shapely.geometry import LineString,Point
from shapely.ops import unary_union,polygonize_full,transform
from shapely import set_precision
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,checked,pin,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.isie_pdf_inventory import _georeferences,_path_runs
from scripts.locality_automation.isie_candidate_comparison import _map_geometry,_name_match
E=Path(r'C:\Users\barou\Documents\Codex\2026-09-06\the-android-app-app-currently-allows');W=E/'work/locality-efficiency-cycles-20261001/root-cycle24';c=read(W.parent/'control.json');s={k:c[k]for k in ['iteration','windowStartUtc','deadlineUtc']};s.update(control=str(W.parent/'control.json'),owner='/root');active_control(s)
case=next(r for r in read(W/'medenine-originals-manifest.json')['cases']if r['officialCode']=='525363');pdf=checked(case['sourcePdf']);inv=read(checked(case['sourceInventory']))['pages'][0];out=W/'qasr-final-decision-v1';out.mkdir()
with pymupdf.open(pdf)as doc:
 page=doc[0];reference=next(r for r in _georeferences(pypdf.PdfReader(pdf).pages[0],page)if r['status']=='fitted');drawings=page.get_drawings();lines=[];matches=[]
 for item in inv['nativePaths']:
  if item['relevantLineworkHint']and item['visibleStroke']and item['strokeFamily']=='red':
   runs,unsupported=_path_runs(drawings[item['drawingIndex']])
   if unsupported:raise ValueError('Unsupported native red path')
   if [[list(p)for p in run]for run in runs]!=[r['nativePagePoints']for r in item['runs']]:raise ValueError('Original native points differ')
   lines.extend(LineString(run)for run in runs if len(run)>1);matches.append(item['drawingIndex'])
 own=[l for l in inv['labeledAreaLeads']if _name_match(l['text'],case['officialName'])and l['centerPagePoints'][1]>100]
 faces=[p for p in polygonize_full(unary_union(lines))[0].geoms if any(p.covers(Point(l['centerPagePoints']))for l in own)]
 if len(faces)!=1:raise ValueError('No unique own closed native red face')
 face=faces[0];metric=_map_geometry(face,reference);raw=transform(Transformer.from_crs(32632,4326,always_xy=True).transform,metric);grid=set_precision(raw,1e-6)
 for name,g in [('native-page',face),('metric',metric),('raw',raw),('grid',grid)]:
  with (out/(name+'.wkb')).open('xb')as f:f.write(g.wkb)
 original=out/'original.png';page.get_pixmap(matrix=pymupdf.Matrix(1.8,1.8),alpha=False).save(original)
design=np.column_stack((reference['pageControls'],np.ones(4)));project=Transformer.from_crs(4326,32632,always_xy=True);targets=np.array([project.transform(lon,lat)for lat,lon in reference['geographicControlsLatLon']]);loo=[float(np.linalg.norm(design[i]@np.linalg.solve(np.delete(design,i,0),np.delete(targets,i,0))-targets[i]))for i in range(4)]
fig=Image.open(original).convert('RGB');d=ImageDraw.Draw(fig);d.line([(x*1.8,y*1.8)for x,y in face.exterior.coords],fill=(0,210,0),width=5);fig.save(out/'selected-native-face.png')
record={'status':'HOLD_REQUIRES_EXPLICIT_EXCEPTION_OR_NEW_REGISTRATION_SOURCE','officialCode':'525363','officialName':'القصر الجديد','id':case['appId'],'sourcePdf':case['sourcePdf'],'sourceInventory':case['sourceInventory'],'sourceUrl':case['sourceUrl'],'originalNativePointsMatchInventory':True,'nativeDrawingIndexes':matches,'sourceAreaM2':metric.area,'fitResidualM':reference['maxControlResidualMeters'],'heldOutResidualsM':loo,'heldOutMaxM':max(loo),'currentHeldOutMaximumM':1.5,'proposedExceptionMaximumM':2.0,'proposedExceptionApplied':False,'sourceScopeAccepted':False,'geographicCredit':0,'sourceCoordinatesEdited':False,'sourceFootprintReconstructed':False,'rawSourceGeometry':pin(out/'raw.wkb'),'geometry':pin(out/'grid.wkb'),'nativePageGeometry':pin(out/'native-page.wkb'),'sourceMetricGeometry':pin(out/'metric.wkb'),'originalRender':pin(original),'selectedNativeRender':pin(out/'selected-native-face.png'),'googleSelectedSatellite':pin(W/'google-qasr-arabic-query.png'),'officialRefetch':pin(W/'qasr-official-refetch-v2/receipt.json'),'neighborSearch':pin(W/'qasr-complete-neighbor-search-v1/report.json'),'priorDiagnosticProposal':pin(E/'work/isie-execution-20260926/medenine/525363-isie-geopdf-review-v1/proposal.md'),'atUtc':datetime.now(timezone.utc).isoformat()}
put(out/'decision.json',record)
text=f'''# Final Medenine case: القصر الجديد (525363)\n\n93 of94 official entries have accepted complete boundaries. The remaining entry has a clear own ISIE whole red face, area {metric.area/1e6:.6f} km². Fresh actual emulator Google Maps satellite search `القصر الجديد بني خداش مدنين تونس` selected **Ksar El Jedid / القصر الجديد** and showed a dotted outline corroborating the overall shape and named-place context. The English query returned mixed historic-place/school results and was excluded. Google imagery supplies no source coordinates or numerical registration proof.\n\n## Exact remaining hold\n\nThe original four embedded controls fit at {reference['maxControlResidualMeters']:.6f} m. Held-out corner residuals are {', '.join(f'{x:.6f}'for x in loo)} m, maximum {max(loo):.6f} m. Current acceptance rule is ≤1.5 m, so it fails by {max(loo)-1.5:.6f} m. Native path coordinates were compared directly with the cached inventory and are unchanged; this is a registration criterion hold, not a missing place, missing perimeter, ambiguous owner or failed GPS runtime.\n\nThe official PDF was refetched through127.0.0.1:8888 with the public proxy CA and verified TLS. Its SHA-256 is unchanged (`{case['sourcePdf']['sha256']}`). The first certificate-chain failure is preserved. A finite incident-neighbor search found no whole replacement face: even Dkhila covers only98.695% of the target footprint; other sheet frames cover smaller fractions. These are diagnostic partial views and are excluded as whole-body replacements.\n\n## Concrete possible exception, awaiting human direction\n\nA prior pinned diagnostic report proposed fit≤0.5m and held-out≤2m for ISIE four-control maps whose corner coordinates are serialized to five decimal places. It explicitly withheld installation. Five-decimal encoding can plausibly contribute to metre-scale residuals, but this does not prove rounding caused this failure. This case would pass the proposed2m limit by only {2-max(loo):.6f}m. Allowing this exception would preserve the exact source perimeter, retain all other native/identity/legal/topology/current-GPS gates, and record the reduced registration confidence. It would change this entry's1.5m acceptance rule; it has **not** been applied. The standing workflow says no gate weakening, so explicit human direction is required.\n\nReview `selected-native-face.png`, the actual Google image pinned in `decision.json`, and the original historical proposal. The adopted-body proposal remains diagnostic only; no location/complete-body/installation credit is recorded for525363. No unrelated locations were behavior-tested.\n'''
with (out/'REVIEW.md').open('x',encoding='utf-8')as f:f.write(text)
print(json.dumps({'held':True,'fitM':record['fitResidualM'],'looMaxM':max(loo),'areaKm2':metric.area/1e6,'exceptionApplied':False,'review':str(out/'REVIEW.md')}))
