"""Prepare concrete three-entry hold review and distinct constituent contact sheets."""
import sys,json
from pathlib import Path
import pymupdf
from PIL import Image,ImageDraw
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
w=Path.cwd();spec=read(w/'gabes-native-leads-v3/manifest.json');active_control(spec);numbered=read(w/'gabes-numbered-captions-v2/manifest.json')['cases'];out=w/'gabes-component-contacts-v1';out.mkdir();images=[]
for start in range(0,len(numbered),4):
 sheet=Image.new('RGB',(2200,1600),'white');draw=ImageDraw.Draw(sheet)
 for i,c in enumerate(numbered[start:start+4]):
  with pymupdf.open(checked(c['sourcePdf']))as doc:
   pix=doc[0].get_pixmap(matrix=pymupdf.Matrix(1.8,1.8),alpha=False);im=Image.frombytes('RGB',[pix.width,pix.height],pix.samples)
  im.thumbnail((1095,760));x=i%2*1100;y=i//2*800+30;sheet.paste(im,(x,y));draw.text((x+5,y-23),c['officialCode']+' circle '+str(c['declaredCircleIds']),fill='black')
 file=out/('components-'+str(start//4+1)+'.png');sheet.save(file);images.append(pin(file))
main=read(w/'gabes-held-native-diagnostics-v1/report.json')['candidates'];extra=read(w/'gabes-additional-native-diag-v1/report.json')['candidates'];measure=read(w/'gabes-registration-measurements-v1.json')['rows'];cases=[]
for r in measure:
 code=r['officialCode'];face=next(x for x in(main+extra)if x['code']==code and x['layer']=='red'and x['index']==0)
 cases.append({'officialCode':code,'name':r['name'],'components':[{'sourcePdf':r['sourcePdf'],'sourceInventory':r['sourceInventory'],'sourceUrl':next(c for c in(spec['cases']+numbered)if c['sourcePdf']['sha256']==r['sourcePdf']['sha256'])['sourceUrl'],'nativeFace':face['pageGeometry'],'circle':1}],'requestedEntryOnlyLimits':{'fitResidualM':.5,'looMaxM':2.0},'GoogleObservation':{'description':'Own and adjoining original boundaries are unambiguous. The hold concerns the PDF coordinate registration only. Google imagery is not used to certify submeter registration. For تشين this preview displays the held first circle; the independently gated second circle is pinned separately and the final body must include both.'}})
reports=[pin(w/('gabes-complete-neighbor-'+code+'-'+version+'/report.json'))for code,version in [('515351','v2'),('516151','v2'),('515852','v1')]]
put(w/'gabes-three-registration-plan-v1.json',{**spec,'familyName':'Gabès','cases':cases,'neighborSearchReports':reports,'neighborReviewDescription':'All 80 indexed original Gabès PDFs were checked for complete source replacements; no complete alternative face covers these held targets. Partial neighboring outlines were rejected.','componentContactSheets':images,'passingTchineSecondCircle':pin(w/'gabes-tchine2-native-v1/515852-source-facts.json')});print(json.dumps({'heldEntries':3,'componentContactSheets':images,'credit':0}))
