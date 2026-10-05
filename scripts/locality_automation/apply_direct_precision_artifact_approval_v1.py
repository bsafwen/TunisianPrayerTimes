"""Pin a direct human decision for one reviewed source/grid hairline only."""
import argparse,json,sys
from pathlib import Path
from datetime import datetime,timezone
from shapely import from_wkb
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.precision_artifact_policy_v1 import require_precision_artifact

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--instruction',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();s=read(a.manifest);c=active_control(s);cp=Path(s['control']);before=pin(cp);instruction=read(a.instruction);review=read(checked(instruction['review']));auth=instruction['authorization'];code=auth['officialCode']
 if auth['sourceRole']!='direct-user-instruction'or auth['action']!='entry-only-grid-hairline-artifact'or not auth['userStatement'].strip()or not auth['questionContext'].strip()or code not in c['pendingSourceCodes']or code not in s['exactTargets']or review['officialCode']!=code:raise ValueError('Exact pending direct user decision required')
 if auth['maxSourceOutsideDecoded10cmM2']!=.01 or auth['maxDecodedOutsideSource10cmM2']!=0 or review['measuredSourceOutsideDecoded10cmM2']>.01 or review['measuredDecodedOutsideSource10cmM2']!=0:raise ValueError('Review exceeds the narrow requested decision')
 entry={k:review[k]for k in ['sourcePdf','nativePageGeometry','rawSourceGeometry','geometry']}
 for k in entry:checked(entry[k])
 if any(not from_wkb(checked(entry[k]).read_bytes()).is_valid for k in ['nativePageGeometry','rawSourceGeometry','geometry']):raise ValueError('A reviewed geometry remains invalid')
 out=a.output.resolve();proof={'status':'EXPLICIT_DIRECT_USER_APPROVED_CODE_PDF_GRID_ARTIFACT','authorization':auth,'entry':entry,'review':instruction['review'],'directInstruction':pin(a.instruction.resolve()),'sourceNativeGeometryUnchanged':True,'allOtherGatesUnchanged':True,'credit':0,'recordedAtUtc':datetime.now(timezone.utc).isoformat()}
 with out.open('x',encoding='utf-8')as f:json.dump(proof,f,ensure_ascii=False,indent=2)
 ref=pin(out);c.setdefault('humanPrecisionArtifactExceptions',{})[code]=ref
 require_precision_artifact(code,entry,c,review['measuredSourceOutsideDecoded10cmM2'],review['measuredDecodedOutsideSource10cmM2'])
 temporary=cp.parent/(out.stem+'-control.tmp')
 with temporary.open('x',encoding='utf-8')as f:json.dump(c,f,ensure_ascii=False,indent=2)
 if pin(cp)!=before:raise ValueError('Concurrent control change')
 temporary.replace(cp)
 receipt=out.with_name(out.stem+'-receipt.json')
 with receipt.open('x',encoding='utf-8')as f:json.dump({'beforeControl':before,'afterControl':pin(cp),'approval':ref,'approvedCode':code,'ordinaryRoundingBoundMUnchanged':.1,'otherEntriesChanged':False,'credit':0},f,indent=2)
 print(json.dumps({'approvedCode':code,'exactGridShape':entry['geometry'],'credit':0}))
if __name__=='__main__':main()
