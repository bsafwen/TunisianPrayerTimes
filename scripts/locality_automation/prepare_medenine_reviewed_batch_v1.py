"""Freeze finite root source decisions, retaining the Qasr Jdid registration hold."""
from datetime import datetime,timezone
import json,sys
from pathlib import Path
from shapely import from_wkb
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import active_control,read,checked,pin
from scripts.locality_automation.audit_reviewed_source_family import put
E=Path(r'C:\Users\barou\Documents\Codex\2026-09-06\the-android-app-app-currently-allows');W=E/'work/locality-efficiency-cycles-20261001/root-cycle24'
control=read(W.parent/'control.json');spec={k:control[k]for k in ['iteration','windowStartUtc','deadlineUtc']};spec.update(control=str(W.parent/'control.json'),owner='/root');active_control(spec)
original=control['approvedCycle24AcceptancePool'];codes=[c for c in original if c!='525363']
if len(original)!=49 or len(codes)!=48:raise ValueError('Original finite remaining pool differs')
sources=read(W/'combined-native-source-review-v2.json');rows={r['officialCode']:r for r in sources['rows']};paired=read(W/'paired-source-facts-v2.json')
if any(p['overlapFractionSmaller']>.01 for p in paired['pairs']):raise ValueError('Unresolved material target overlap')
assets=R/'android-app/app/src/main/assets';byid={r['id']:r for r in read(assets/'neighborhoods.json')['features']};official={str(r['sectorCode']):r for r in read(R/'scripts/neighborhoods/touil-reviews/current-official-sector-registry.json')['sectors']}
scopes=W/'source-scopes';scopes.mkdir()
qualification='Complete literal ISIE 2023 imada source faces for 48 remaining Medenine entries, with INS and Decree 2023-590 identity. Root visual/native/neighbor review under the human no-subagent/minimum-testing instructions. Displaced annotations are qualified by paired original sheets and actual satellite context where ambiguous. Derjaoua uses its complete labelled face on the well-registered Sidi Makhlouf sheet, corroborated by Bedoui; its own-sheet registration failure remains preserved. Qasr Jdid remains excluded pending registration proof. Original unsnapped seams, gaps and conservative GPS conflicts/fallback retained; no survey exactness or universal GPS coverage claimed.'
historical=['525156-reciprocal-face-review-20260928-v1/source-face-review.json','525955-reciprocal-review-20260928-v1/source-face-adjudication.md','525456-535852-reciprocal-red-face-review-20260928-v1/review.json','525363-isie-geopdf-review-v1/proposal.md','525961-isie-geopdf-review-v1/proposal.md']
common={'sourceAuthor':'/root','actualReviewer':'/root','independentAgentReviewClaimed':False,'reviewedAtUtc':datetime.now(timezone.utc).isoformat(),'qualification':qualification,'nativeSourceReview':pin(W/'combined-native-source-review-v2.json'),'pairedSourceFacts':pin(W/'paired-source-facts-v2.json'),'rosterPdf':pin(E/'work/electoral-circle-semantics/decree2023-590.pdf'),'rosterPages':[69,70,71,72],'rosterVisuals':[pin(W/'medenine-roster'/('decree-page-'+str(p)+'.png'))for p in [69,70,71,72]],'legalRole':'Decree 2023-590 Annex 1 table A: each reviewed imada has its own electoral-circle row and one seat. INS code/name/parent binding is separately retained. Printed electoral scope is treated as the source whole imada face; this is not surveyed precision.','mapsVisuals':[pin(W/(n+'.png'))for n in ['google-medenine-sud-query','google-labba-query','google-labba-area']],'mapsRole':'Actual emulator satellite El Labba outline and city/road context corroborate the Labba side of the urban boundary. Google Medenine Sud result covers the delegation and is explicitly excluded as imada-boundary proof. No Google coordinates extracted.','historicalFacts':[pin(E/'work/isie-execution-20260926/medenine'/n)for n in historical], 'derjaouaNeighborManifest':pin(W/'525961-neighbor-525951-manifest.json'),'derjaouaSecondSource':pin(W/'derjaoua-neighbor-525955/native-source-review.json'),'retainedHold':rows['525363'],'sourceOnlyCredit':0}
commonref=put(W/'medenine-root-source-review.json',common)
special={
 '525151':'Medenine West printed center is displaced 1425.87 m outside its own West-sheet face; retain Bni Ghzeil own closed face.',
 '525154':'In-map lead 0 and original red face 0 identify 20 Mars; duplicate yellow-title lead is excluded.',
 '525156':'Reuse reciprocal Dakhlit Toujan 2 and Bahira source review: foreign neighbor annotations are displaced; original governorate/imada edges and whole own face retained.',
 '525157':'Whole own face uses the literal red and blue delegation boundary network; the blue edge partitions the red outline at the administrative border. No artificial separator or snapping.',
 '525251':'The complete urban face east of N19, south of the N1/northern imada edge and west of the Labba zigzag is the Medenine Sud imada. It is one exact original closed native red face; the own printed name is displaced into Labba by 1036.34 m. Paired 2 Mai, East and Labba own sheets exclude that urban face; Google Labba satellite outline supports the eastern/southern side. Delegation Google result excluded.',
 '525253':'Medenine Sud annotation is displaced into Labba, outside the 7.148 km2 Sud urban face; the same-sheet shared red zigzag and Google Labba outline resolve the side. Foreign Bassatine annotation does not introduce an interior administrative separator.',
 '525254':'Souitir foreign annotation is displaced; own Hassi Omar closed face and neighboring Souitir whole face retain the shared road separator.',
 '525259':'Hassi Medenine and Medenine North printed centers lie outside those owners\' own sheets; compare whole Amra/Sidi Makhlouf adjoining red/blue faces. Retain own Amra Jdida face.',
 '525357':'Douz East is a foreign annotation; same-sheet black governorate border remains on the face edge, with no internal black ownership partition. Own Bnia title/label/red whole perimeter governs.',
 '525360':'Bni Khedache annotation lies 446.72 m outside its own-sheet face; own Zemmour label and adjoining red road edge govern.',
 '525361':'Exact INS and electoral-roster entry is الحميمة. No asserted Ministry الحميمة/الحميمية variant equivalence.',
 '525452':'Ben Guerdane North annotation lies 55.77 m outside its own North-sheet face. Same-sheet N1 red road edge separates the two urban imadas.',
 '525453':'Whole Sayeh red/admin coastline face is identical to the complete connected native face. A 0.0802-page-point black spur remains disclosed; it does not partition the polygon into another administrative face.',
 '525455':'Tabaei printed center lies 10794.46 m outside its own-sheet face; foreign Tataouine Zahra annotation is across the literal governorate boundary context, with no interior black partition of the own whole Meamrats face.',
 '525456':'Reuse original paired Ameriya/El Morra review and same-sheet black governorate boundary attribution. Foreign El Morra identity remains qualified; whole literal own Ameriya face is retained.',
 '525461':'Foreign Hamadi Bou Tfaha/Bir Lahmar annotations are displaced; literal own Shehbania red perimeter and external black governorate boundary do not divide this own face internally.',
 '525955':'Sidi Makhlouf printed center is 2474.80 m outside its own face. Reuse reciprocal original review; the same-sheet neighboring unlabelled Sidi face matches its own sheet.',
 '525956':'Medenine North annotation lies 1207.50 m outside its own-sheet face. Own Amra red perimeter and C108 blue boundary remain literal.',
 '525961':'Complete labelled Derjaoua red face is recovered on passing-registration Sidi Makhlouf source 525951. A second passing Bedoui sheet confirms the whole face (IoU 0.995002; max discrepancy 19.13 m), retained as independent printed-sheet seam uncertainty. Own-source LOO 1.7828 m failure is preserved and is not waived.'}
patches=[]
for c in codes:
 r=rows[c];ins=official[c];app=byid[r['id']]
 if str(ins['delegationCode'])!=c[:4]or app['kind']!='sector':raise ValueError('Identity differs '+c)
 raw=from_wkb(checked(r['rawSourceGeometry']).read_bytes());grid=from_wkb(checked(r['geometry']).read_bytes())
 if not raw.is_valid or not grid.is_valid or not r['wholeNativeFaceInsidePage']or not r['originalNativePointsMatchSavedInventory']or r['registration']['fitResidualM']>.5 or r['registration']['looMaxM']>1.5:raise ValueError('Native geometry/registration held '+c)
 admin=r['nativeInteriorAdminLengthsPagePoints']
 if any(admin.values()):
  if c!='525453' or admin['blue']!=0 or admin['black']>=.1:raise ValueError('Unresolved internal administrative edge '+c)
  prior=next(x for x in read(W/'native-source-v1/native-source-review.json')['rows']if x['officialCode']==c)
  if not grid.equals(from_wkb(checked(prior['geometry']).read_bytes())):raise ValueError('Sayeh complete native administrative face differs')
 explanation='Own in-map label, printed title/parent, original whole native perimeter and INS/electoral roster confirmed in root visual review. '+special.get(c,'No unresolved foreign-label or interior administrative partition remains.')
 decision={**r,'sourceScopeAccepted':True,'boundaryScope':'full-source-face','datedRosterMember':True,'expectedAdoptedGeometry':r['geometry'],'sourceAuthor':'/root','actualReviewer':'/root','independentAgentReviewClaimed':False,'commonSourceReview':commonref,'decisionExplanation':explanation,'sourceOnlyGeographicCredit':0}
 if c=='525961':decision.update(sourceSheetOwnerCode='525951',sourceSheetRole='Complete labelled adjoining imada on original Sidi Makhlouf map',ownSourceRegistrationHoldPreserved=True)
 ref=put(scopes/(c+'-source-scope.json'),decision)
 patches.append({'id':r['id'],'officialCode':c,'geometry':r['geometry'],'rawSourceGeometry':r['rawSourceGeometry'],'sourcePdf':r['sourcePdf'],'sourceUrl':r['sourceUrl'],'sourceScopeReview':ref,'boundaryScope':'full-source-face','datedRosterMember':True,'sourceProvider':'ISIE 2023 imada map; INS official identity','qualification':qualification,'uncertainty':'Literal native face rounded once to app 1e-6 grid; original registered seams retain conservative GPS conflict/fallback. Derjaoua separate sheets differ up to 19.13 m. No surveyed exactness.'})
for name in ['task-report.json','boundary-map.json']:
 with (W/('before-medenine-publication-'+name)).open('xb')as f:f.write((E/'work/locality-progress-dashboard'/name).read_bytes())
put(W/'medenine-proposal.json',{'status':'SOURCE_SCOPE_REVIEWED_REQUIRES_TARGET_GPS','reviewedDate':'2026-10-04','sourceIdPrefix':'isie-complete-medenine','baseCatalogMetadata':pin(assets/'neighborhoods.json'),'baseCatalogBinary':pin(assets/'neighborhoods.bin'),'qualification':qualification,'patches':patches,'rootReview':commonref,'unrelatedLocationsRechecked':0,'independentAgentReviewClaimed':False})
control['cycle24OriginalRemainingPool']=original;control['approvedCycle24AcceptancePool']=codes;control['cycle24RetainedSourceHolds']=[{'code':'525363','reason':'Own original held-out registration 1.9638 m exceeds 1.5 m; neighboring labelled faces are partial coverage and not full target replacement.'}];control['pendingSourceCodes']=['525363'];control['lastControlUpdateUtc']=datetime.now(timezone.utc).isoformat()
(W.parent/'control.json').write_text(json.dumps(control,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({'proposed':len(codes),'held':['525363'],'credit':0}))
