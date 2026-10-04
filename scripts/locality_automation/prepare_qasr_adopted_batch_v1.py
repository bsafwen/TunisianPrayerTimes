"""Adopt only the exact final source face under the explicit human2m exception."""
import json,sys
from pathlib import Path
from datetime import datetime,timezone
import pymupdf
from shapely import from_wkb
from shapely.geometry import LineString,box
from shapely.ops import unary_union
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,checked,pin,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.isie_pdf_inventory import _path_runs
E=Path(r'C:\Users\barou\Documents\Codex\2026-09-06\the-android-app-app-currently-allows');C=E/'work/locality-efficiency-cycles-20261001';W=C/'root-cycle25';previous=C/'root-cycle24';c=read(C/'control.json');s={k:c[k]for k in ['iteration','windowStartUtc','deadlineUtc']};s.update(control=str(C/'control.json'),owner='/root');active_control(s)
exception=read(checked(c['cycle25RegistrationException']));decision=read(checked(exception['registrationDecision']))
if c['approvedCycle25AcceptancePool']!=['525363'] or exception['humanInstructionVerbatim']!='allow 2 meters' or exception['officialCode']!='525363' or exception['maximumHeldOutResidualM']!=2 or decision['heldOutMaxM']>2 or decision['fitResidualM']>.5:raise ValueError('Exact case-only human exception differs')
if not decision['originalNativePointsMatchInventory']or decision['sourceCoordinatesEdited']or decision['sourceFootprintReconstructed']:raise ValueError('Literal original native face differs')
ins=next(r for r in read(R/'scripts/neighborhoods/touil-reviews/current-official-sector-registry.json')['sectors']if str(r['sectorCode'])=='525363');assets=R/'android-app/app/src/main/assets';app=next(r for r in read(assets/'neighborhoods.json')['features']if r['id']==decision['id'])
if ins['sectorAr']!='القصر الجديد' or str(ins['delegationCode'])!='5253' or app['kind']!='sector':raise ValueError('Exact official identity differs')
native=from_wkb(checked(decision['nativePageGeometry']).read_bytes());raw=from_wkb(checked(decision['rawSourceGeometry']).read_bytes());grid=from_wkb(checked(decision['geometry']).read_bytes())
if not all(g.is_valid and not g.is_empty for g in [native,raw,grid]):raise ValueError('Literal source geometry invalid')
inv=read(checked(decision['sourceInventory']))['pages'][0];lines={'blue':[],'black':[]};matched=[]
with pymupdf.open(checked(decision['sourcePdf']))as doc:
 page=doc[0]
 if not box(*page.rect).covers(native):raise ValueError('Whole own source face extends outside the original page')
 drawings=page.get_drawings()
 for item in inv['nativePaths']:
  if item['relevantLineworkHint']and item['visibleStroke']and item['strokeFamily']in lines:
   runs,unsupported=_path_runs(drawings[item['drawingIndex']])
   if unsupported or [[list(p)for p in run]for run in runs]!=[r['nativePagePoints']for r in item['runs']]:raise ValueError('Original administrative path differs')
   lines[item['strokeFamily']].extend(LineString(run)for run in runs if len(run)>1);matched.append(item['drawingIndex'])
admin={k:unary_union(v).intersection(native.buffer(-.5)).length for k,v in lines.items()}
if any(admin.values()):raise ValueError('Unresolved interior administrative separator')
qualification='Exact complete own ISIE 2023 القصر الجديد imada face, INS525363/Bni Khedache. Explicit human instruction "allow 2 meters" permits this entry-only held-out registration ceiling2m; actual1.963756m and all-control fit0.490939m. Original1.5m failure is preserved. No source coordinates edited, geometry repaired or other gate relaxed. Actual emulator Google satellite outline corroborates overall scope without supplying coordinates. Retain unsnapped seams and conservative GPS conflicts/fallback; no survey precision claimed.'
common=put(W/'medenine-root-source-review.json',{'qualification':qualification,'sourceAuthor':'/root','actualReviewer':'/root','independentAgentReviewClaimed':False,'reviewedAtUtc':datetime.now(timezone.utc).isoformat(),'humanException':c['cycle25RegistrationException'],'priorRootReview':pin(previous/'qasr-final-decision-v1/REVIEW-v2.md'),'originalNativeDecision':pin(previous/'qasr-final-decision-v1/decision-v2.json'),'rosterPdf':pin(E/'work/electoral-circle-semantics/decree2023-590.pdf'),'rosterVisual':pin(previous/'medenine-roster/decree-page-70.png'),'legalRole':'Original Decree2023-590 Annex1 tableA names القصر الجديد among the thirteen Bni Khedache imadas, one electoral-circle row/seat each. INS supplies exact code/name/parent binding.','mapVisual':pin(previous/'google-qasr-arabic-query.png'),'mapRole':'Selected Ksar El Jedid/القصر الجديد dotted satellite outline corroborates overall scope; no Google coordinates or registration measurements extracted.','officialRefetch':pin(previous/'qasr-official-refetch-v2/receipt.json'),'completeNeighborSearch':pin(previous/'qasr-complete-neighbor-search-v2/report.json'),'sourceOnlyCredit':0})
scope={**decision,'sourceScopeAccepted':True,'boundaryScope':'full-source-face','datedRosterMember':True,'expectedAdoptedGeometry':decision['geometry'],'actualReviewer':'/root','sourceAuthor':'/root','independentAgentReviewClaimed':False,'sourceMethod':'complete_original_red_face','wholeNativeFaceInsidePage':True,'nativeInteriorAdminLengthsPagePoints':admin,'originalAdministrativeDrawingIndexesMatched':matched,'registration':{'fitResidualM':decision['fitResidualM'],'looMaxM':decision['heldOutMaxM'],'originalUsualCeilingM':1.5,'originalUsualGatePassed':False,'explicitCaseExceptionCeilingM':2.0,'humanException':c['cycle25RegistrationException']},'proposedExceptionApplied':True,'commonSourceReview':common,'decisionExplanation':qualification,'sourceOnlyGeographicCredit':0}
out=W/'source-scopes';out.mkdir();ref=put(out/'525363-source-scope.json',scope)
patch={'id':decision['id'],'officialCode':'525363','geometry':decision['geometry'],'rawSourceGeometry':decision['rawSourceGeometry'],'sourcePdf':decision['sourcePdf'],'sourceUrl':decision['sourceUrl'],'sourceScopeReview':ref,'boundaryScope':'full-source-face','datedRosterMember':True,'sourceProvider':'ISIE2023 exact own imada map; INS identity; explicit human2m registration exception','qualification':qualification,'uncertainty':'Actual held-out registration1.963756m under case-specific human2m ceiling; preserve the original1.5m failure and source seams. No surveyed exactness.'}
for name in ['task-report.json','boundary-map.json']:
 with (W/('before-medenine-publication-'+name)).open('xb')as f:f.write((E/'work/locality-progress-dashboard'/name).read_bytes())
put(W/'medenine-proposal.json',{'status':'SOURCE_SCOPE_REVIEWED_REQUIRES_TARGET_GPS','reviewedDate':'2026-10-04','sourceIdPrefix':'isie-complete-medenine-final','baseCatalogMetadata':pin(assets/'neighborhoods.json'),'baseCatalogBinary':pin(assets/'neighborhoods.bin'),'qualification':qualification,'patches':[patch],'rootReview':common,'unrelatedLocationsRechecked':0,'independentAgentReviewClaimed':False})
print(json.dumps({'proposed':['525363'],'heldOutMaxM':decision['heldOutMaxM'],'caseSpecificHumanMaximumM':2,'otherGatesUnchanged':True,'credit':0}))
