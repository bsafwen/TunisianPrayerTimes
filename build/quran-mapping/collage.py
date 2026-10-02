from PIL import Image,ImageDraw,ImageFont
from pathlib import Path
import json
out=Path(r'C:\Users\barou\Desktop\Workspace\TunisianPrayerTimes\build\quran-mapping')
src=Path(r'C:\Users\barou\Downloads\Quran_Qaloun_pages')
found=json.loads((out/'rules.json').read_text()); crops=[]
for n,ys in found:
 im=Image.open(src/f'page_{n:03d}.webp').convert('RGB');w,h=im.size
 ys=[y for y in ys if y>h*.06]
 groups=[]
 for y in ys:
  if not groups or y-groups[-1][-1]>240:groups.append([y])
  else:groups[-1].append(y)
 for g in groups:
  y=g[-1]; cr=im.crop((int(w*.1),max(int(h*.05),y-205),int(w*.92),y+8))
  cr.thumbnail((590,155));crops.append((n,cr))
print('title candidates',len(crops),'pages',len(set(n for n,cr in crops)))
for offset in range(0,len(crops),24):
 board=Image.new('RGB',(1260,12*175),'white');d=ImageDraw.Draw(board)
 for idx,(n,cr) in enumerate(crops[offset:offset+24]):
  x=(idx%2)*630;y=(idx//2)*175;d.text((x+8,y+8),str(n),fill='black',font=ImageFont.truetype('arial.ttf',24));board.paste(cr,(x+38,y+15))
 board.save(out/f'titles_{offset//24+1}.png')
(out/'title_pages.json').write_text(json.dumps([n for n,cr in crops]))
