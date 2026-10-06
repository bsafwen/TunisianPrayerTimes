"""Measure completion against the Ministry inventory, not census queue size.

Identity/accounting only. Reuses accepted map memberships and proved explicit
non-census bindings; does not search, measure, revalidate or credit any polygon.
Unlinked Ministry rows remain explicit holds, even when the INS queue is empty.
"""
import argparse,json,sys
from pathlib import Path
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked
def audit(identity_report,accepted_map,extra_scopes,output):
    source=read(identity_report);accepted=read(accepted_map);by_id={r['id']:r for r in accepted['locations']};extra={}
    for path in extra_scopes:
        scope=read(path);identity=scope['identityEvidence'];checked(identity['ministryCsv'])
        assert scope['sourceScopeAccepted'] and scope['boundaryScope']=='full-source-face' and scope['id']==identity['catalogId'] and identity['currentInsCode']is None
        assert identity['status']=='PROVED_MINISTRY_ISIE_IDENTITY_WITHOUT_CURRENT_CENSUS_CODE' and scope['reviewedMinistryAndDecree']
        key=identity['ministryRowId'];assert key not in extra;extra[key]={'appId':scope['id'],'sourceKey':scope['officialCode'],'evidence':pin(path)}
    rows=[]
    for case in source['cases']:
        ident=case.get('appId');binding='confirmed existing census reconciliation'if case['identityClass']=='confirmed'else'pending'
        if case['officialRowId']in extra:ident=extra[case['officialRowId']]['appId'];binding='proved Ministry/ISIE non-census identity'
        location=by_id.get(ident)
        matched=case['identityClass']=='confirmed' or case['officialRowId']in extra
        full=matched and bool(location) and location['latestScope']=='full'and location['currentMatchesAccepted']is True
        rows.append({'ministryRowId':case['officialRowId'],'name':case['ministryName'],'parent':case['ministryParent'],'appId':ident,'binding':binding,'acceptedCompleteBoundaryPresent':bool(full)})
    assert len(rows)==source['officialTotal'] and len({r['ministryRowId']for r in rows})==len(rows)
    holds=[r for r in rows if not r['acceptedCompleteBoundaryPresent']]
    result={'status':'MINISTRY_FAMILY_COVERAGE_COMPLETE'if not holds else'MINISTRY_FAMILY_COVERAGE_INCOMPLETE','governorate':source['governorate'],'ministryEligibleRows':len(rows),'acceptedCompleteRows':len(rows)-len(holds),'censusRegistryRows':source['counts']['currentInsRegistryRows'],'nonCensusRowsWithProvedBinding':len(extra),'pendingMinistryRows':holds,'rows':rows,'inputs':{'identityReport':pin(identity_report),'acceptedMap':pin(accepted_map),'extraScopes':[pin(p)for p in extra_scopes]},'geographicValidationCredit':0,'newGpsProbes':0,'newSourceReviews':0,'rule':'An empty census queue alone never proves complete Ministry coverage.'}
    with output.open('x',encoding='utf-8')as f:json.dump(result,f,ensure_ascii=False,indent=2);f.write('\n')
    print(json.dumps({k:result[k]for k in ['status','ministryEligibleRows','acceptedCompleteRows','censusRegistryRows','geographicValidationCredit']}));return bool(holds)
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for k in ['identity-report','accepted-map','output']:p.add_argument('--'+k,type=Path,required=True)
    p.add_argument('--extra-scope',type=Path,action='append',default=[]);a=p.parse_args();raise SystemExit(audit(a.identity_report.resolve(),a.accepted_map.resolve(),[p.resolve()for p in a.extra_scope],a.output.resolve()))
