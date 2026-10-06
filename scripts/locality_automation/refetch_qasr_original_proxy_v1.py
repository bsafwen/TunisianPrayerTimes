"""One bounded official-source refetch through the mandatory machine proxy."""
import hashlib,json,sys
from datetime import datetime,timezone
from pathlib import Path
import requests
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,active_control,pin
from scripts.locality_automation.audit_reviewed_source_family import put
E=Path(r'C:\Users\barou\Documents\Codex\2026-09-06\the-android-app-app-currently-allows');W=E/'work/locality-efficiency-cycles-20261001/root-cycle24';c=read(W.parent/'control.json');s={k:c[k]for k in ['iteration','windowStartUtc','deadlineUtc']};s.update(control=str(W.parent/'control.json'),owner='/root');active_control(s)
case=next(r for r in read(W/'medenine-originals-manifest.json')['cases']if r['officialCode']=='525363');out=W/'qasr-official-refetch-v1';out.mkdir();session=requests.Session();session.trust_env=False;session.proxies={'http':'http://127.0.0.1:8888','https':'http://127.0.0.1:8888'}
record={'requestedUrl':case['sourceUrl'],'priorSourcePdf':case['sourcePdf'],'proxy':'127.0.0.1:8888','geographicCredit':0,'atUtc':datetime.now(timezone.utc).isoformat()}
try:
 response=session.get(case['sourceUrl'],timeout=(10,25));data=response.content;record.update(statusCode=response.status_code,finalUrl=response.url,contentType=response.headers.get('Content-Type'),bytes=len(data),sha256=hashlib.sha256(data).hexdigest())
 (out/'response-body.bin').write_bytes(data)
 if response.status_code==200 and data.startswith(b'%PDF'):
  pdf=out/'original.pdf';pdf.write_bytes(data);record.update(pdf=pin(pdf),sameAsCached=record['sha256']==case['sourcePdf']['sha256'],status='OFFICIAL_PDF_REFETCHED')
 else:record['status']='SOURCE_UNAVAILABLE_OR_NON_PDF'
except Exception as exc:record.update(status='SOURCE_REQUEST_FAILED',error=str(exc))
put(out/'receipt.json',record);print(json.dumps(record,ensure_ascii=False))
