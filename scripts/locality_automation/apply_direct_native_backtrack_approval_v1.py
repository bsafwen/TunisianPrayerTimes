"""Pin one direct-user decision for the exact original Hammam Ghezaz corner."""
import argparse,json,sys
from datetime import datetime,timezone
from pathlib import Path
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.native_administrative_policy_v2 import require_native_administration

def main():
 p=argparse.ArgumentParser(description=__doc__)
 for key in ('manifest','instruction','output'):p.add_argument('--'+key,type=Path,required=True)
 a=p.parse_args();s=read(a.manifest);c=active_control(s);cp=Path(s['control']);before=pin(cp)
 instruction=read(a.instruction);auth=instruction['authorization'];review=read(checked(instruction['review']));code=review['officialCode']
 assert code in s['exactTargets']and code in c['pendingSourceCodes']
 assert auth['sourceRole']=='direct-user-instruction'and auth['action']=='entry-only-exact-native-backtrack'
 assert auth['userStatement'].strip()and auth['questionContext'].strip()
 assert auth['officialCode']==code and auth['maximumBlueInteriorLengthPagePoints']==review['requestedMaximumBlueInteriorLengthPagePoints']==.581
 assert review['credit']==0 and review['wholeOriginalBoundaryUnchanged']and review['registrationDefaultUnchanged']
 f=read(checked(review['sourceFacts']))
 proof=put(a.output,{'status':'EXPLICIT_DIRECT_USER_APPROVED_EXACT_NATIVE_BACKTRACK','officialCode':code,'authorization':auth,'sourcePdf':review['sourcePdf'],'nativePageGeometry':review['nativePageGeometry'],'exactBlueIntrusionWkt':review['exactBlueIntrusionWkt'],'maximumBlueInteriorLengthPagePoints':.581,'measuredBlueInteriorLengthPagePoints':review['measuredBlueInteriorLengthPagePoints'],'blackInteriorMustStayZero':True,'sourceInsetPagePoints':.5,'wholeOriginalBoundaryUnchanged':True,'registrationDefaultUnchanged':True,'review':instruction['review'],'directInstruction':pin(a.instruction.resolve()),'recordedAtUtc':datetime.now(timezone.utc).isoformat(),'credit':0})
 c.setdefault('humanNativeBacktrackExceptions',{})[code+':'+f['sourcePdf']['sha256']]=proof
 assert require_native_administration(code,f,c)==proof
 wrong={**f,'nativeInteriorAdminLengthsPagePoints':{**f['nativeInteriorAdminLengthsPagePoints'],'blue':f['nativeInteriorAdminLengthsPagePoints']['blue']+.01}}
 try:require_native_administration(code,wrong,c)
 except ValueError as exc:negative=str(exc)
 else:raise AssertionError('Additional intrusion was accepted')
 temp=cp.parent/(a.output.stem+'-control.tmp');put(temp,c);assert pin(cp)==before;temp.replace(cp)
 put(a.output.with_name(a.output.stem+'-receipt.json'),{'status':'PASS_EXACT_APPROVED_CORNER_ONLY','approval':proof,'beforeControl':before,'afterControl':pin(cp),'alteredIntrusionRejected':negative,'originalPolicyUnchanged':pin(R/'scripts/locality_automation/native_administrative_policy_v1.py'),'newPolicy':pin(R/'scripts/locality_automation/native_administrative_policy_v2.py'),'credit':0})
 print(json.dumps({'approvedCode':code,'maximumExactBlueIntrusion':.581,'alteredIntrusionRejected':True,'credit':0}))

if __name__=='__main__':main()
