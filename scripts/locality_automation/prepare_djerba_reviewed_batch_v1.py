"""Freeze the root-reviewed Djerba source decisions and one coherent proposal."""
from datetime import datetime,timezone
import argparse, json, sys
from pathlib import Path
from shapely import from_wkb
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import active_control,read,checked
from scripts.locality_automation.stage_boundary_patch import pin
from scripts.locality_automation.audit_reviewed_source_family import put

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--work',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True);a=p.parse_args();w=a.work.resolve();e=a.evidence.resolve()
    control=read(w.parent/'control.json');spec={k:control[k]for k in ['iteration','windowStartUtc','deadlineUtc']};spec.update(control=str(w.parent/'control.json'),owner='/root');active_control(spec)
    codes=control['approvedCycle23AcceptancePool'];sources=read(w/'native-source-v2/native-source-review.json');paired=read(w/'paired-source-facts.json')
    rows={r['officialCode']:r for r in sources['rows']};assets=ROOT/'android-app/app/src/main/assets';catalog=read(assets/'neighborhoods.json');byid={r['id']:r for r in catalog['features']}
    registry=read(ROOT/'scripts/neighborhoods/touil-reviews/current-official-sector-registry.json');official={str(r['sectorCode']):r for r in registry['sectors']}
    scopes=w/'source-scopes';scopes.mkdir()
    qualification='Complete literal own ISIE 2023 imada faces for fourteen Djerba entries. Root visual/native/paired-source review under the user\'s no-subagent and minimum-testing instructions. Original independent Fatou measurement and human Souani annotation interpretation reused. Riadh labels on Walg/Mai sheets are displaced: Riadh\'s own sheet shares the mapped road edges and excludes these centers by over 1 km. Retain original unsnapped faces, registered thin overlaps/gaps and conservative GPS conflict/fallback behavior. No survey exactness, universal GPS coverage, Android UI or separate current agent review claimed.'
    common={'sourceAuthor':'/root','actualReviewer':'/root','independentAgentReviewClaimed':False,'sourceRole':'Root direct original/paired source review, authorized without subagents','reviewedAtUtc':datetime.now(timezone.utc).isoformat(),'qualification':qualification,
        'nativeSourceReview':pin(w/'native-source-v2/native-source-review.json'),'pairedSourceFacts':pin(w/'paired-source-facts.json'),
        'rosterPdf':pin(e/'work/electoral-circle-semantics/decree2023-590.pdf'),'rosterPages':[70,71],
        'rosterVisuals':[pin(w/'djerba-decree-page70/decree-page-70.png'),pin(w/'djerba-decree-visual-3/decree-page-71.png')],
        'legalRole':'Decree 2023-590 Annex 1 table A: each Djerba imada has one matching local electoral-circle row and one seat. Code/name/parent binding comes from INS registry. The roster does not prove surveyed GIS precision.',
        'mapsVisuals':[pin(w/'google-walg-selected.png'),pin(w/'google-walg-detail.png'),pin(w/'google-elmay-detail.png')],
        'mapRole':'Actual emulator satellite corroboration of Oualegh east of C117 and El May at the C117/C209 road crossing; camera images do not constitute Google administrative-boundary proof.',
        'fatouHistoricalMeasurement':pin(e/'work/isie-execution-20260926/medenine/525654-independent-m-gate-20260928-v1/report.md'),
        'souaniHumanInterpretation':pin(e/'work/isie-execution-20260926/medenine/525658-hachane-label-interpretation-v1/receipt.json')}
    commonref=put(w/'djerba-root-source-review.json',common)
    patches=[]
    for code in codes:
        r=rows[code];ins=official[code];row=byid[r['id']]
        if str(ins['delegationCode'])!=code[:4] or row['kind']!='sector':raise ValueError('Official target identity differs')
        for k in ['sourcePdf','sourceInventory','rawSourceGeometry','geometry','nativePageGeometry','sourceMetricGeometry','originalRender']:r[k]=pin(checked(r[k]))
        raw=from_wkb(checked(r['rawSourceGeometry']).read_bytes());grid=from_wkb(checked(r['geometry']).read_bytes())
        if not raw.is_valid or not grid.is_valid or not r['wholeNativeFaceInsidePage'] or not r['originalNativePointsMatchSavedInventory'] or any(r['nativeInteriorAdminLengthsPagePoints'].values()):raise ValueError('Native complete-face facts held: '+code)
        explanation='Own title, parent context and complete native boundary confirmed by the original rendered sheet and INS/Decree roster.'
        if code in ['525657','525756']:explanation+=' Foreign Riadh annotation resolved by paired own Riadh sheet, shared road boundaries and actual satellite corroboration; no guessed separator or label-centered cut.'
        if code=='525654':explanation+=' Reuse historical independent Fatou source gate: Sedghian label straddles the shared edge; do not move the literal boundary.'
        if code=='525658':explanation+=' Reuse human interpretation that Hachan label is misplaced on Souani sheet; retain exact source ring.'
        decision={**r,'sourceScopeAccepted':True,'boundaryScope':'full-source-face','datedRosterMember':True,'expectedAdoptedGeometry':r['geometry'],'sourceAuthor':'/root','actualReviewer':'/root','independentAgentReviewClaimed':False,'commonSourceReview':commonref,'decisionExplanation':explanation,'sourceOnlyGeographicCredit':0}
        ref=put(scopes/(code+'-source-scope.json'),decision)
        patches.append({'id':r['id'],'officialCode':code,'geometry':r['geometry'],'rawSourceGeometry':r['rawSourceGeometry'],'sourcePdf':r['sourcePdf'],'sourceUrl':r['sourceUrl'],'sourceScopeReview':ref,'boundaryScope':'full-source-face','datedRosterMember':True,'sourceProvider':'ISIE 2023 own imada map; INS official identity','qualification':qualification,'uncertainty':'Literal published native face, rounded once to app 1e-6 grid. Printed/registered seams retain conservative GPS conflict/fallback; no surveyed exactness.'})
    for name in ['task-report.json','boundary-map.json']:
        src=e/'work/locality-progress-dashboard'/name
        with (w/('before-djerba-publication-'+name)).open('xb')as f:f.write(src.read_bytes())
    proposal={'status':'SOURCE_SCOPE_REVIEWED_REQUIRES_TARGET_GPS','reviewedDate':'2026-10-04','sourceIdPrefix':'isie-complete-djerba','baseCatalogMetadata':pin(assets/'neighborhoods.json'),'baseCatalogBinary':pin(assets/'neighborhoods.bin'),'qualification':qualification,'patches':patches,'rootReview':commonref,'unrelatedLocationsRechecked':0,'independentAgentReviewClaimed':False}
    put(w/'djerba-proposal.json',proposal);print(json.dumps({'proposedCodes':codes,'rootReview':commonref,'sourceOnlyCredit':0}))
