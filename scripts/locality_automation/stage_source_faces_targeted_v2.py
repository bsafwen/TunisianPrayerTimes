"""Stage complete source faces, retiring only obsolete changed-pair OSM policy.
Original stage is retained unchanged in a nested original directory.
"""
import argparse,json,sys
from pathlib import Path
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.stage_source_faces_targeted_v1 import stage,read,pin,checked,write
from scripts.locality_automation.audit_reviewed_source_family import put
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--proposal',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
assert not a.output.exists();result=stage(a.proposal,a.output/'original');proposal=read(a.proposal);before=read(checked(result['beforeMetadata']));after=read(checked(result['stagedMetadata']));byid={r['id']:r for r in after['features']};patches={r['id']:r for r in proposal['patches']};removed=[];retained=[]
for policy in before.get('gpsConflictPolicies',[]):
 peer=[i for i in policy['ids']if i!=policy['gpsPreferredId']];assert len(peer)==1
 if byid[peer[0]]['sourceId']=='osm':retained.append(policy);continue
 assert set(policy['ids'])<=set(patches),'Only a pair fully covered by changed source patches may retire its OSM fallback'
 assert policy['osmPeerSourceId']=='osm' and policy['osmFallbackWhenMNotAccuracyQualified']is True
 for ident in policy['ids']:
  patch=patches[ident];scope=read(checked(patch['sourceScopeReview']))
  assert patch['boundaryScope']=='full-source-face' and scope['sourceScopeAccepted']is True and scope['datedRosterMember']is True
  assert scope['expectedAdoptedGeometry']==patch['geometry'] and scope['sourcePdf']==patch['sourcePdf']
 removed.append({'policy':policy,'reason':'Both pair members now have exact complete source boundaries; former OSM peer is replaced. Normal conflict/clearance behavior resumes.','scopeEvidence':[patches[i]['sourceScopeReview']for i in policy['ids']]})
after['gpsConflictPolicies']=retained
assert {k:v for k,v in after.items()if k!='gpsConflictPolicies'}=={k:v for k,v in read(checked(result['stagedMetadata'])).items()if k!='gpsConflictPolicies'}
write(a.output/'neighborhoods.json',after,compact=True)
proof=put(a.output/'retired-pair-policy-proof.json',{'originalStage':pin(a.output/'original/stage-report.json'),'retired':removed,'retained':retained,'coordinateChangesBeyondOriginalStage':False,'sourceGatesUnchanged':True,'credit':0})
result={**result,'stagedMetadata':pin(a.output/'neighborhoods.json'),'obsoleteChangedPairPolicies':proof}
write(a.output/'stage-report.json',result)
print(json.dumps({'changed':len(result['changedIds']),'retiredObsoletePairPolicies':len(removed),'retainedPolicies':len(retained),'originalStagePreserved':True,'credit':0}))

