import json
from pathlib import Path
E=Path(r'C:\Users\barou\Documents\Codex\2026-09-06\the-android-app-app-currently-allows');M=E/'work/isie-execution-20260926/medenine'
names=['five-new-source-face-proposal-v1','missing-source-acquisition-v2','extra-source-inventory-v1','ben-guerdane-source-face-review-v1','ben-guerdane-parent-alias-v1','525361-source-face-identity-hold-20260928-v1','525363-isie-mfull-successor-20260927-v1','525961-isie-mfull-successor-20260927-v1','central-mainland-cluster-review-v1','central-exception-review-v1','525154-isie-mfull-successor-20260928-v1','525251-source-identity-review-20260928-v1']
for name in names:
 p=M/name/'report.json'
 if not p.exists(): print(name,'NO_REPORT', [v.name for v in (M/name).iterdir()][:10]);continue
 v=json.loads(p.read_text(encoding='utf-8-sig'));print('\nREPORT',name,'KEYS',list(v))
 for k in ['status','counts','decision','holdReasons','failures','sourcePdf','sourceInventory','cases','rows','sources','proposals','results']:
  if k not in v:continue
  q=v[k]
  if isinstance(q,list):
   print(k,'LIST',len(q))
   for r in q[:16]:
    if isinstance(r,dict):print(json.dumps({key:val for key,val in r.items() if key in ['officialCode','code','officialName','officialParent','appId','status','sourcePdf','sourceInventory','sourceUrl','holdReasons','proposal','label','name','pdf','inventory','url','download','sourceTitle']},ensure_ascii=False)[:2500])
    else:print(str(r)[:150])
  else:print(k,json.dumps(q,ensure_ascii=False)[:1800])
