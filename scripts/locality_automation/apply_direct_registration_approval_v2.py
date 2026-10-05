"""Record a pinned direct-human entry/PDF approval without changing default gates."""
import argparse,json,sys
from pathlib import Path
from datetime import datetime,timezone
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
from scripts.locality_automation.registration_policy_v2 import registration_limits
p=argparse.ArgumentParser();p.add_argument('--manifest',type=Path,required=True);p.add_argument('--instruction',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
a.manifest=a.manifest.resolve();a.instruction=a.instruction.resolve();a.output=a.output.resolve()
spec=read(a.manifest);c=active_control(spec);cp=Path(spec['control']);before=pin(cp);instruction=read(a.instruction);auth=instruction['authorization'];review=read(checked(instruction['review']));codes=auth['officialCodes']
if auth['sourceRole']!='direct-user-instruction'or auth['action']!='entry-only-held-out-registration-limit'or not auth['userStatement'].strip()or not auth['questionContext'].strip()or len(set(codes))!=len(codes)or not set(codes)<=set(c['pendingSourceCodes']):raise ValueError('Exact pending direct-user approval required')
rows={r['officialCode']:r for r in review['rows']};entries=[]
for code in codes:
 parts=[r for r in rows[code]['parts']if r['registrationHeld']]
 if len(parts)!=1:raise ValueError('One exact held PDF required per approved entry')
 r=parts[0]
 if r['fitResidualM']>.5 or r['heldOutMaxM']>auth['looLimitM']:raise ValueError('Approved limits do not cover unchanged registration')
 entries.append({'officialCode':code,'sourcePdf':r['sourcePdf'],'fitResidualLimitM':.5,'looLimitM':auth['looLimitM']})
proof=put(a.output,{'status':'EXPLICIT_DIRECT_USER_APPROVED_CODE_PDF_REGISTRATION_LIMITS','authorization':auth,'entries':entries,'review':instruction['review'],'directInstruction':pin(a.instruction.resolve()),'exactOriginalBoundaryUnchanged':True,'allOtherGatesUnchanged':True,'credit':0,'recordedAtUtc':datetime.now(timezone.utc).isoformat()})
for code in codes:c.setdefault('humanRegistrationExceptions',{})[code]=proof
for r in entries:
 if registration_limits(r['officialCode'],r['sourcePdf'],c)['looMaxM']!=auth['looLimitM']:raise ValueError('Proof validation failed')
temp=cp.parent/(a.output.stem+'-control.tmp');put(temp,c)
if pin(cp)!=before:raise ValueError('Concurrent control change')
temp.replace(cp)
put(a.output.with_name(a.output.stem+'-receipt.json'),{'beforeControl':before,'afterControl':pin(cp),'approval':proof,'approvedCodes':codes,'defaultFitUnchanged':True,'unrelatedEntriesChanged':False,'credit':0})
print(json.dumps({'approvedCodes':codes,'heldOutLimitM':auth['looLimitM'],'credit':0}))
