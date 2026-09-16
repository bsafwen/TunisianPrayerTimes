from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime,timezone
from urllib.parse import quote
import hashlib,json,subprocess

D=Path(__file__).resolve().parent;T=D.parents[1]
R=Path('C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes')
def pin(p):
 p=Path(p).resolve();return {'file':p.as_posix(),'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'bytes':p.stat().st_size}
def read(p):return json.loads(Path(p).read_bytes())
meta=R/'android-app/app/src/main/assets/neighborhoods.json'
primary=T/'outputs/baddar-aitha-kharmia-essabala-name-source-review.json'
index=T/'work/current-official-catalog/isie-local-boundary-index-links.json'
registry=R/'scripts/neighborhoods/point-retention-reviews/current-official-sector-registry.json'
assert pin(meta)['sha256']=='992b1409c45567e83d153cd2b033a5ea1466413c8d3e0b6f3861d4fb0b9f3b41'
assert pin(primary)['sha256']=='b3c47138b89438ffb4e1b435e623fefe21b9590e61c9202f5bd2c280258466b0'
assert pin(index)['sha256']=='44fddae0a73f306f3f61dc305d8e27db51e83d57a1ff335fdad60aec5ead2651'
assert pin(registry)['sha256']=='23b3ee87c07971eb48b456799833f1996ade5d0bdc10d7bf6b0e3bf6798c9eec'
rows={x['sectorCode']:x for x in read(registry)['sectors']}
routes=[('arima','osm:relation:7100361','156053','العريمة','نابل 2/تاكلسة/العريمة.pdf'),('menzel-bouzelfa-joufia','osm:relation:7101605','156253','منزل بوزلفة الجوفية','نابل 2/منزل بوزلفة/منزل بوزلفة الجوفية.pdf')]
base='https://www.isie.tn/wp-content/uploads/2023/CartesCirconscriptionsElectoralesLocales2023/'
entries=read(index)
assert not (D/'retrieval-manifest.json').exists()
def get(route):
 key,raw,code,label,tail=route;url=quote(base+tail,safe=':/?=&%')
 matched=[e for e in entries if e['decodedPath'].endswith(tail)]
 assert len(matched)==1,matched
 body=D/(key+'.pdf');headers=D/(key+'.headers');assert not body.exists() and not headers.exists()
 started=datetime.now(timezone.utc).isoformat()
 p=subprocess.run(['curl.exe','--location','--compressed','--max-time','50','--fail-with-body','-D',str(headers),'-o',str(body),'-w','%{http_code}\n%{url_effective}\n',url],capture_output=True,text=True,encoding='utf8')
 lines=p.stdout.strip().splitlines();status=lines[0] if lines else None;effective=lines[1] if len(lines)>1 else None
 result={'key':key,'currentRawId':raw,'currentCode':code,'officialName':label,'officialRegistryRow':rows[code],'officialIndexEntry':matched[0],
  'requestedUrl':url,'effectiveUrl':effective,'retrievedAt':started,'exitCode':p.returncode,'httpStatus':status,'response':pin(body) if body.exists() else None,'headers':pin(headers) if headers.exists() else None,
  'stderr':p.stderr,'sourceByteProvenance':'Exact HTTP response body; curl decodes HTTP compression only. No document conversion or geometry alteration.',
  'success':p.returncode==0 and status=='200' and body.read_bytes().startswith(b'%PDF')}
 (D/(key+'-retrieval.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
 return result
with ThreadPoolExecutor(max_workers=2) as executor:records=list(executor.map(get,routes))
manifest={'status':'ORIGINAL_TWO_RECEIVING_OWN_MAP_RETRIEVAL_ONLY','scope':'Exactly Arima156053 and Menzel Bouzelfa Joufia156253; no further edge closure or change to the frozen thirteen package.',
 'baselineMetadata':pin(meta),'registry':pin(registry),'officialIndex':pin(index),'primaryPointIdentityReport':pin(primary),'records':records,
 'cacheReuseCheck':'Targeted filenames in task work and repository source trees found the Baddar primary HTML but no matching own-map original PDF; no broad content scan.',
 'retrievalConcurrency':2,'producer':pin(__file__)}
assert pin(meta)['sha256']==manifest['baselineMetadata']['sha256']
out=D/'retrieval-manifest.json';out.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
print(json.dumps({'manifest':pin(out),'records':[{'key':r['key'],'success':r['success'],'httpStatus':r['httpStatus'],'response':r['response']} for r in records]},ensure_ascii=False))
