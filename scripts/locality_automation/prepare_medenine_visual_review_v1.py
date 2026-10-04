"""Bounded source composites and incident source-neighbor measurements."""
import json,sys
from pathlib import Path
from PIL import Image,ImageDraw
from shapely import from_wkb
from shapely.geometry import Point
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
E=Path(r'C:\Users\barou\Documents\Codex\2026-09-06\the-android-app-app-currently-allows');W=E/'work/locality-efficiency-cycles-20261001/root-cycle24'
s=read(W/'medenine-originals-manifest.json');active_control(s)
rows={r['officialCode']:r for r in read(W/'native-source-v1/native-source-review.json')['rows']}
for r in read(W/'native-source-v2/native-source-review.json')['rows']:rows[r['officialCode']]=r
for r in rows.values():
 for k in ['rawSourceGeometry','geometry','sourceMetricGeometry','nativePageGeometry','originalRender']:
  if k in r:r[k]=pin(checked(r[k]))
put(W/'combined-native-source-review.json',{'rows':list(rows.values()),'credit':0,'actualSourceReviewer':'/root'})
valid={c:from_wkb(checked(r['sourceMetricGeometry']).read_bytes()) for c,r in rows.items() if 'sourceMetricGeometry'in r}
pairs=[]
for c,g in valid.items():
 for d,h in valid.items():
  if d<=c or not g.envelope.buffer(5).intersects(h.envelope):continue
  if c not in s['exactTargets'] and d not in s['exactTargets']:continue
  intersection=g.intersection(h);shared=g.boundary.intersection(h.boundary.buffer(5)).length
  if intersection.area>1 or shared>10:pairs.append({'a':c,'b':d,'overlapM2':intersection.area,'sharedWithin5mM':shared,'gapM':g.distance(h),'overlapFractionSmaller':intersection.area/min(g.area,h.area)})
put(W/'paired-source-facts.json',{'pairs':pairs,'qualification':'Literal native registered source comparisons only; no ownership decisions or geometry repair.','credit':0})
visual=W/'source-contact-sheets';visual.mkdir();ordered=[r for r in rows.values() if 'nativePageGeometry'in r and r['officialCode'] in s['exactTargets']]
for start in range(0,len(ordered),4):
 image=Image.new('RGB',(2000,1460),'white');draw=ImageDraw.Draw(image)
 for i,r in enumerate(ordered[start:start+4]):
  tile=Image.open(checked(r['originalRender'])).convert('RGB');tile.thumbnail((990,700));x=(i%2)*1000;y=(i//2)*730+28
  image.paste(tile,(x,y));draw.text((x+4,y-22),r['officialCode']+' '+r['sourceMethod'],fill='black')
 out=visual/('sources-'+str(start//4+1).zfill(2)+'.png');image.save(out)
print(json.dumps({'visualSheets':len(list(visual.iterdir())),'successfulSourceFacts':len(valid),'holds':[{'code':r['officialCode'],'error':r.get('error'),'outsidePage':not r.get('wholeNativeFaceInsidePage',True),'interiorAdmin':r.get('nativeInteriorAdminLengthsPagePoints')}for r in rows.values()if 'error'in r or not r.get('wholeNativeFaceInsidePage',True) or any(r.get('nativeInteriorAdminLengthsPagePoints',{}).values())],'materialOverlapPairs':[p for p in pairs if p['overlapFractionSmaller']>.02]},ensure_ascii=False))
