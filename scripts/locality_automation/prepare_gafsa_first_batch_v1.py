"""Freeze the reviewed unambiguous Gafsa subset; retain every unresolved case."""
import json,sys
from pathlib import Path
from datetime import datetime,timezone
from shapely import from_wkb
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
W=Path.cwd();C=W.parent;E=C.parents[1];c=read(C/'control.json');s={k:c[k]for k in ['iteration','windowStartUtc','deadlineUtc']};s.update(control=str(C/'control.json'),owner='/root');active_control(s)
held=['615456','615758','615851','616156','616251','616252','616351','616353']
codes=[v for v in c['allFamilyOfficialCodes']if v not in held];sources=read(W/'native-source-v4/native-source-review.json');by={r['officialCode']:r for r in sources['rows']}
if len(codes)!=68:raise ValueError('Gafsa clear subset count differs')
geoms={v:from_wkb(checked(by[v]['sourceMetricGeometry']).read_bytes())for v in codes};pairs=[]
for a,g in geoms.items():
 for b,h in geoms.items():
  if b<=a or not g.envelope.buffer(5).intersects(h.envelope):continue
  area=g.intersection(h).area;shared=g.boundary.intersection(h.boundary.buffer(5)).length
  if area>1 or shared>10:pairs.append({'a':a,'b':b,'overlapM2':area,'sharedWithin5mM':shared,'overlapFractionSmaller':area/min(g.area,h.area)})
if any(v['overlapFractionSmaller']>.01 for v in pairs):raise ValueError('Material source attribution overlap remains')
paired=put(W/'gafsa-clear-paired-source-facts.json',{'pairs':pairs,'credit':0,'input':pin(W/'native-source-v4/native-source-review.json')})
decisions={v:'Root reviewed original literal own imada face, adjoining sheets and decree row; exact INS code and parent. Foreign printed labels are displaced according to their separate own source faces. No printed frame, guessed cut or coordinate stitching adopted.'for v in codes}
for v in ['615352','615453','615458']:decisions[v]='Target label is displaced outside its own complete red face. Root reviewed the exact red ring and adjoining own-title sheets; initial label-centered neighbor selection rejected and preserved. '+('Actual Google satellite Cité Ennour dotted outline corroborates the central urban road perimeter.'if v=='615458'else 'The own red ring follows the printed urban roads; adjacent rings corroborate attribution.')
decisions['615255']='Literal native red and governorate/delegation boundary face, excluding the 3486.77 m2 sliver beyond the native administrative boundary. Ministry/ISIE القرية is parent-qualified to INS القربة. Original black and blue edges retained.'
qualification='Complete literal published ISIE 2023 Gafsa imada faces; root original, adjoining, native and decree review under the user no-subagent and minimum-test instructions. Only single-circle imadas in this batch. Registered thin gaps/overlaps and conservative GPS fallback retained; no survey precision, universal GPS coverage or separate agent review claimed.'
evidence=[pin(W/'native-source-v4/native-source-review.json'),paired,pin(E/'work/electoral-circle-semantics/decree2023-590.pdf')]+[pin(W/p)for p in ['roster-atlas-v2/page-66.png','roster-atlas-v2/page-67.png','roster-atlas-v3/page-68.png','roster-atlas-v3/page-69.png','google-hay-nour.png']]
plan={'codes':codes,'decisions':decisions,'qualification':qualification,'commonEvidence':evidence,'legalRole':'Decree 2023-590 annex 1 table A pages 66-69 reviewed. Multi-circle imadas held separately until every constituent circle is accounted for. INS official registry binds current name, code and parent.','mapRole':'Actual emulator Google satellite search حي النور قفصة تونس, Cité Ennour outline; useful corroboration of displaced target label. No browser authentication claim.'}
put(W/'gafsa-clear-decisions.json',plan)
put(W/'before-clear-subset-control.json',c)
c['approvedCycle'+str(c['iteration'])+'AcceptancePool']=codes;c['pendingSourceCodes']=held;c['sourceIntakeNextAction']='Accept 68 reviewed single-circle bodies; resolve eight held cases in a successor family increment.';c['lastControlUpdateUtc']=datetime.now(timezone.utc).isoformat()
(C/'control.json').write_text(json.dumps(c,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'clear':len(codes),'held':held,'materialSourcePairs':0}))
