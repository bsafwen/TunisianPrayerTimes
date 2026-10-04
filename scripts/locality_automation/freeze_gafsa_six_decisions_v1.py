"""Freeze six reviewed remaining Gafsa bodies and their incident-source facts."""
import json,sys
from pathlib import Path
from datetime import datetime,timezone
from shapely import from_wkb
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
W=Path.cwd();C=W.parent;E=C.parents[1];old=C/'root-cycle26';c=read(C/'control.json');s={k:c[k]for k in ['iteration','windowStartUtc','deadlineUtc']};s.update(control=str(C/'control.json'),owner='/root');active_control(s)
codes=['615456','615851','616156','616251','616252','616353'];sources=read(W/'constituent-source-v2/native-source-review.json');by={r['officialCode']:r for r in sources['rows']};old_rows=[read(checked(p['sourceScopeReview']))for p in read(old/'gafsa-proposal.json')['patches']];old_codes=read(old/'gafsa-incremental-publication-receipt.json')['locationCodes'];neighbors={r['officialCode']:from_wkb(checked(r['sourceMetricGeometry']).read_bytes())for r in old_rows if r['officialCode']in old_codes};new={v:from_wkb(checked(by[v]['sourceMetricGeometry']).read_bytes())for v in codes};allg={**neighbors,**new};pairs=[]
for a,g in new.items():
 for b,h in allg.items():
  if a==b or(b in new and b<a)or not g.envelope.buffer(5).intersects(h.envelope):continue
  area=g.intersection(h).area;shared=g.boundary.intersection(h.boundary.buffer(5)).length
  if area>1 or shared>10:pairs.append({'a':a,'b':b,'overlapM2':area,'sharedWithin5mM':shared,'overlapFractionSmaller':area/min(g.area,h.area)})
if any(v['overlapFractionSmaller']>.01 for v in pairs):raise ValueError('Material incident source overlap remains')
paired=put(W/'gafsa-six-incident-source-facts.json',{'pairs':pairs,'credit':0,'changedCodes':codes,'previousNeighborCoordinatesReused':True,'input':pin(W/'constituent-source-v2/native-source-review.json')})
decisions={
 '615456':'Displaced وادي أيلو annotation lies south of the complete own red ring. Root reviewed exact original urban perimeter and adjoining حي النور/المولى/العسالة source edges; use own red face, rejecting the initial frame-closed candidate.',
 '615851':'Decree page 67 groups المظيلة المركز circles 1 and 2 in one imada. Both literal complete faces recovered together on passing neighboring صهيب original. Root compared both own originals and the single neighboring source; exact same-sheet native union, no inter-sheet seam.',
 '616156':'Decree page 66 groups ماجورة circles 1 and 2 in one imada. Both own complete original faces individually pass registration/native/frame/administrative gates. Preserve their literal raw union with original registration overlap; no snapping, invented cut or area extension.',
 '616251':'Decree page 68 groups سيدي بوبكر circles 1 and 2 in one imada. Circle 1 uses exact native governorate edge; the أم الأقصاب 1 annotation within it is displaced as shown by its own complete western ring. Both own constituent faces pass; preserve literal union and native outer edges.',
 '616252':'Decree page 68 groups أم الأقصاب circles 1, 2 and 3 in one imada. All three own originals visually compared; the merged label centroid is outside the circle 1 ring. Explicit exact native ring used. Circle 3 follows exact red/governorate connected face. All three constituent gates pass; preserve original union without snapping.',
 '616353':'Decree page 68 groups عبد الصادق circles 1 and 2 in one imada. Both own originals reviewed and individually pass the original registration/native/frame/administrative gates. Preserve literal union, original outer edges and registration seams.'}
qualification='Complete published ISIE imada bodies: every constituent circle declared in decree 2023-590 accounted for, original native geometry and registration checked per source. Mdhila center uses both circles from one passing Sehib sheet; other grouped units retain literal unions of individually passing own-circle faces. Original registration seams remain; no snapping, surveyed precision, universal GPS coverage or separate agent review claimed.'
evidence=[pin(W/'constituent-source-v2/native-source-review.json'),pin(W/'constituent-review-plan-v2.json'),paired,pin(E/'work/electoral-circle-semantics/decree2023-590.pdf')]+[pin(old/p)for p in ['roster-atlas-v2/page-66.png','roster-atlas-v2/page-67.png','roster-atlas-v3/page-68.png','roster-atlas-v3/page-69.png']]
put(W/'gafsa-six-decisions.json',{'codes':codes,'decisions':decisions,'qualification':qualification,'commonEvidence':evidence,'legalRole':'Literal decree 2023-590 annex 1 table A grouping plus current INS code/name/parent. One imada can have two or three electoral-circle seats; require all declared source faces.','mapRole':'Google satellite corroboration is retained for ambiguous urban labels. Cross-PDF group geometry is the unsnapped union of passing original native circles.'})
put(W/'before-six-subset-control.json',c);c['approvedCycle27AcceptancePool']=codes;c['pendingSourceCodes']=['615758','616351'];c['lastControlUpdateUtc']=datetime.now(timezone.utc).isoformat();(C/'control.json').write_text(json.dumps(c,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'reviewed':codes,'materialIncidentPairs':0,'remaining':c['pendingSourceCodes']}))
