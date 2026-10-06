"""Show exact reviewed native perimeters, including repeated-code constituents."""
import argparse,json,sys
from pathlib import Path
from PIL import Image,ImageDraw
from shapely import from_wkb
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,checked,pin,active_control
from scripts.locality_automation.audit_reviewed_source_family import put
p=argparse.ArgumentParser();p.add_argument('--manifest',type=Path,required=True);p.add_argument('--facts',type=Path,required=True);p.add_argument('--codes',nargs='+',required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();s=read(a.manifest);active_control(s)
if not set(a.codes)<=set(s['exactTargets']):raise ValueError('Outside finite reviewed scope')
rows={r['officialCode']:r for r in read(a.facts)['rows']};parts=[(c,n,r)for c in a.codes for n,r in enumerate(rows[c].get('constituentNativeFacts',[rows[c]]),1)];a.output.mkdir();files=[]
for start in range(0,len(parts),4):
 canvas=Image.new('RGB',(2400,1800),'white');caption=ImageDraw.Draw(canvas)
 for j,(code,n,r)in enumerate(parts[start:start+4]):
  im=Image.open(checked(r['originalRender'])).convert('RGB');d=ImageDraw.Draw(im);g=from_wkb(checked(r['nativePageGeometry']).read_bytes())
  for ring in [g.exterior,*g.interiors]:d.line([(x*1.8,y*1.8)for x,y in ring.coords],fill=(0,220,0),width=5)
  im.thumbnail((1195,860));x=j%2*1200;y=j//2*900+30;canvas.paste(im,(x,y));caption.text((x+4,y-23),code+' part '+str(n),fill='black')
 f=a.output/('reviewed-'+str(start//4+1)+'.png');canvas.save(f);files.append(pin(f))
put(a.output/'receipt.json',{'facts':pin(a.facts.resolve()),'codes':a.codes,'nativeParts':len(parts),'images':files,'credit':0});print(json.dumps({'images':len(files),'credit':0}))
