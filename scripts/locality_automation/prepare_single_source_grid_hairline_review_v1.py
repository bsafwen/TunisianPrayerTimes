"""Prepare an exact single-source grid hairline decision, without accepting it."""
import argparse,json,sys
from pathlib import Path
import pymupdf
from PIL import Image,ImageDraw
from shapely import from_wkb
from shapely.ops import transform
from pyproj import Transformer
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.registration_policy_v2 import require_registration
from scripts.locality_automation.native_administrative_policy_v1 import require_native_administration
from scripts.locality_automation.audit_reviewed_source_family import put
p=argparse.ArgumentParser(description=__doc__)
for k in ['manifest','facts','diagnostic','neighbor-search','google-image','output']:p.add_argument('--'+k,type=Path,required=True)
p.add_argument('--code',required=True);a=p.parse_args();s=read(a.manifest);c=active_control(s)
assert a.code in s['exactTargets'] and a.code in c['pendingSourceCodes']
r=next(v for v in read(a.facts)['rows']if v['officialCode']==a.code);d=read(a.diagnostic)
assert 'error'not in r and r['wholeNativeFaceInsidePage'] and r['originalNativePointsMatchSavedInventory']
require_registration(a.code,r['sourcePdf'],r['registration'],c);require_native_administration(a.code,r,c)
native=from_wkb(checked(r['nativePageGeometry']).read_bytes());raw=from_wkb(checked(r['rawSourceGeometry']).read_bytes());grid=from_wkb(checked(r['geometry']).read_bytes())
assert all(g.is_valid and not g.is_empty for g in [native,raw,grid])
project=Transformer.from_crs(4326,32632,always_xy=True).transform;b=transform(project,grid);original=transform(project,raw)
outside=original.difference(b.buffer(.1)).area;added=b.difference(original.buffer(.1)).area
assert .1<original.hausdorff_distance(b) and 0<outside<=.01 and added==0
assert abs(outside-d['sourceOutsideGrid10cmBufferM2'])<1e-10 and d['sourcePdf']==r['sourcePdf']
out=a.output.resolve();out.mkdir();x,y=d['worstNativePagePoint'];clip=pymupdf.Rect(x-12,y-12,x+12,y+12)
with pymupdf.open(checked(r['sourcePdf']))as doc:
 page=doc[0];clip&=page.rect;page.get_pixmap(matrix=pymupdf.Matrix(24,24),clip=clip,alpha=False).save(out/'original-closeup.png')
 page.get_pixmap(matrix=pymupdf.Matrix(1.8,1.8),alpha=False).save(out/'original-page.png')
im=Image.open(out/'original-closeup.png').convert('RGB');draw=ImageDraw.Draw(im)
for ring in [native.exterior,*native.interiors]:draw.line([((u-clip.x0)*24,(v-clip.y0)*24)for u,v in ring.coords],fill=(0,190,0),width=2)
im.save(out/'native-closeup.png')
facts={'officialCode':a.code,'name':r['officialName'],**{k:r[k]for k in ['sourcePdf','sourceInventory','sourceUrl','nativePageGeometry','sourceMetricGeometry','rawSourceGeometry','geometry','registration']},'existingMaximumHausdorffM':.1,'measuredHausdorffM':original.hausdorff_distance(b),'measuredSourceOutsideDecoded10cmM2':outside,'measuredDecodedOutsideSource10cmM2':added,'geometryValid':grid.is_valid,'sourceScopeAccepted':False,'credit':0,'requestedEntryOnlyPrecisionArtifactLimitM2':.01,'originalFacts':pin(a.facts.resolve()),'diagnostic':pin(a.diagnostic.resolve()),'neighborSearch':pin(a.neighbor_search.resolve()),'googleImage':pin(a.google_image.resolve()),'originalCloseup':pin(out/'original-closeup.png'),'nativeCloseup':pin(out/'native-closeup.png'),'fullPage':pin(out/'original-page.png')}
put(out/'review-facts.json',facts)
md=['# '+r['officialName']+': exact app-grid hairline decision','','Unaccepted preview; no validation or installation credit.','','The exact valid source has an extremely thin retracing feature at the northwestern red/black boundary junction. The standard app coordinate grid collapses this feature. Maximum perimeter distance: '+format(facts['measuredHausdorffM'],'.6f')+'m. Source area beyond the rounded polygon 10cm collar: '+format(outside,'.9f')+'m2 (about '+format(outside*10000,'.1f')+'cm2). Added area beyond the source 10cm collar: zero. This is separate from PDF registration, which passes unchanged.','','Requested entry-only exception: retain the exact PDF/raw/native source evidence and accept this identified grid artifact with at most0.01m2 loss outside the10cm collar and zero added outside area. All other source, identity, administrative, topology and minimum target-only checks remain. The rule is bound to these exact source/raw/grid hashes and cannot transfer to another entry.','','Root viewed the adjoining Kesra/Louza/Qarya North/Qarya South originals. The finite whole-face replacement search found none. Actual named Google El Foudhoul satellite view supports the footprint and context; it is not a survey.','','[Original ISIE PDF]('+r['sourceUrl']+')','','![Original close-up](original-closeup.png)','','![Exact native outline in green](native-closeup.png)','','[Full original page](original-page.png)','','[Actual satellite capture](<'+str(a.google_image.resolve()).replace('\\','/')+'>)']
(out/'REVIEW.md').write_text('\n'.join(md)+'\n',encoding='utf-8');print(json.dumps({'officialCode':a.code,'heldHairlineM':facts['measuredHausdorffM'],'sourceOutside10cmM2':outside,'addedOutside10cmM2':added,'credit':0}))

