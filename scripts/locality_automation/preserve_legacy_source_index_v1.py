"""Persist exact already accepted legacy provenance for future map refreshes."""
import argparse,json,sys
from pathlib import Path
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
p=argparse.ArgumentParser(description=__doc__)
for k in ['manifest','receipt','index','output']:p.add_argument('--'+k,type=Path,required=True)
a=p.parse_args();c=active_control(read(a.manifest));r=read(a.receipt);assert r['status']=='PASS_REUSED_HISTORICAL_SOURCE_PROVENANCE'and r['credit']==0 and not r['newValidationClaim']
m=read(checked(r['enrichedMap']));rows={x['code']:x for x in m['locations']};idx=read(a.index)if a.index.exists()else{'schemaVersion':1,'role':'Original accepted legacy provenance only; no new validation credit','entries':{}};before=pin(a.index)if a.index.exists()else None
assert set(r['recoveredCodes'])<=set(c['allFamilyOfficialCodes'])
for code in r['recoveredCodes']:
 row=rows[code];ev=row['sourceGeometryEvidence'];proof=read(checked(ev['scopeEvidence']));key=ev['acceptanceReceipt']['sha256']+':'+code
 assert row['latestScope']=='full'and row['currentMatchesAccepted']and proof['officialCode']==code and proof['id']==row['id']and proof['credit']==0 and not proof['newValidationClaim']
 item={'officialCode':code,'id':row['id'],'geometryEvidence':ev,'recoveryReceipt':pin(a.receipt.resolve())}
 if key in idx['entries']:assert idx['entries'][key]==item
 else:idx['entries'][key]=item
tmp=a.index.with_name(a.index.name+'.tmp');put(tmp,idx);assert (pin(a.index)if a.index.exists()else None)==before;tmp.replace(a.index)
put(a.output,{'status':'PASS_PRESERVED_EXACT_LEGACY_SOURCE_INDEX','index':pin(a.index.resolve()),'recoveredCodes':r['recoveredCodes'],'recoveryReceipt':pin(a.receipt.resolve()),'credit':0});print(json.dumps({'legacySourceIndexEntries':len(idx['entries']),'credit':0}))
