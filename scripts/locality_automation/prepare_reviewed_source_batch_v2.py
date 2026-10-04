"""Freeze explicit root source decisions for a finite changed-location batch."""
import argparse,json,sys
from pathlib import Path
from datetime import datetime,timezone
from shapely import from_wkb
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import active_control,read,checked
from scripts.locality_automation.stage_boundary_patch import pin
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.registration_policy_v1 import require_registration

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--work',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True);p.add_argument('--family',required=True);p.add_argument('--sources',type=Path,required=True);p.add_argument('--decisions',type=Path,required=True);a=p.parse_args();w=a.work.resolve();e=a.evidence.resolve()
 c=read(w.parent/'control.json');spec={k:c[k]for k in ['iteration','windowStartUtc','deadlineUtc']};spec.update(control=str(w.parent/'control.json'),owner='/root');active_control(spec)
 codes=c['approvedCycle'+str(c['iteration'])+'AcceptancePool'];plan=read(a.decisions);rows={r['officialCode']:r for r in read(a.sources)['rows']}
 if plan['codes']!=codes or set(plan['decisions'])!=set(codes):raise ValueError('Explicit decision pool differs')
 assets=ROOT/'android-app/app/src/main/assets';byid={r['id']:r for r in read(assets/'neighborhoods.json')['features']};official={str(r['sectorCode']):r for r in read(ROOT/'scripts/neighborhoods/touil-reviews/current-official-sector-registry.json')['sectors']}
 for q in plan['commonEvidence']:checked(q)
 common=put(w/(a.family+'-root-source-review.json'),{'sourceAuthor':'/root','actualReviewer':'/root','independentAgentReviewClaimed':False,'reviewedAtUtc':datetime.now(timezone.utc).isoformat(),'qualification':plan['qualification'],'commonEvidence':plan['commonEvidence'],'legalRole':plan['legalRole'],'mapRole':plan.get('mapRole'),'decisionPlan':pin(a.decisions.resolve()),'nativeSourceReview':pin(a.sources.resolve())})
 scopes=w/'source-scopes';scopes.mkdir();patches=[]
 for code in codes:
  r=dict(rows[code]);ins=official[code];row=byid[r['id']]
  if str(ins['delegationCode'])!=code[:4]or row['kind']!='sector':raise ValueError('Official identity differs: '+code)
  for k in ['sourcePdf','sourceInventory','rawSourceGeometry','geometry','nativePageGeometry','sourceMetricGeometry','originalRender']:r[k]=pin(checked(r[k]))
  raw=from_wkb(checked(r['rawSourceGeometry']).read_bytes());grid=from_wkb(checked(r['geometry']).read_bytes())
  if not raw.is_valid or not grid.is_valid or not r['wholeNativeFaceInsidePage']or not r['originalNativePointsMatchSavedInventory']or any(r['nativeInteriorAdminLengthsPagePoints'].values()):raise ValueError('Native complete-face facts held: '+code)
  for part in r.get('constituentNativeFacts',[r]):require_registration(code,part['sourcePdf'],part['registration'],c)
  decision={**r,'sourceScopeAccepted':True,'boundaryScope':'full-source-face','datedRosterMember':True,'expectedAdoptedGeometry':r['geometry'],'sourceAuthor':'/root','actualReviewer':'/root','independentAgentReviewClaimed':False,'commonSourceReview':common,'decisionExplanation':plan['decisions'][code],'sourceOnlyGeographicCredit':0}
  ref=put(scopes/(code+'-source-scope.json'),decision)
  patches.append({'id':r['id'],'officialCode':code,'geometry':r['geometry'],'rawSourceGeometry':r['rawSourceGeometry'],'sourcePdf':r['sourcePdf'],'sourceUrl':r['sourceUrl'],'sourceScopeReview':ref,'boundaryScope':'full-source-face','datedRosterMember':True,'sourceProvider':'ISIE 2023 native map; INS official identity; decree 2023-590 circle grouping','qualification':plan['qualification'],'uncertainty':'Literal original native face, rounded once to app 1e-6 grid; registered gaps/overlaps retain conservative GPS fallback. No surveyed exactness.'})
 for name in ['task-report.json','boundary-map.json']:
  with(w/('before-'+a.family+'-publication-'+name)).open('xb')as f:f.write((e/'work/locality-progress-dashboard'/name).read_bytes())
 put(w/(a.family+'-proposal.json'),{'status':'SOURCE_SCOPE_REVIEWED_REQUIRES_TARGET_GPS','reviewedDate':datetime.now(timezone.utc).date().isoformat(),'sourceIdPrefix':'isie-complete-'+a.family,'baseCatalogMetadata':pin(assets/'neighborhoods.json'),'baseCatalogBinary':pin(assets/'neighborhoods.bin'),'qualification':plan['qualification'],'patches':patches,'rootReview':common,'unrelatedLocationsRechecked':0,'independentAgentReviewClaimed':False})
 print(json.dumps({'proposedCodes':codes,'sourceOnlyCredit':0}))
if __name__=='__main__':main()
