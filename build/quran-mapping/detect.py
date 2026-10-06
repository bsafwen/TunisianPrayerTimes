from PIL import Image, ImageDraw
import numpy as np
from pathlib import Path
src=Path(r'C:\Users\barou\Downloads\Quran_Qaloun_pages')
out=Path(r'C:\Users\barou\Desktop\Workspace\TunisianPrayerTimes\build\quran-mapping')
found=[]
for n in range(1,604):
 im=Image.open(src/f'page_{n:03d}.webp').convert('RGB'); w,h=im.size
 a=np.asarray(im.convert('L'))
 # Central continuous horizontal rules identify decorated surah banners.
 black=(a[:,int(w*.15):int(w*.85)]<70).mean(axis=1)
 ys=np.where((black>.82)&(np.arange(h)>h*.042)&(np.arange(h)<h*.93))[0]
 groups=[]
 for y in ys:
  if not groups or y-groups[-1][-1]>10:groups.append([y])
  else:groups[-1].append(y)
 # paired outer banner lines can be 120-190px apart; use all rules and view contact sheets.
 if groups:
  found.append((n,[round(sum(g)/len(g)) for g in groups]))
import json
(out/'rules.json').write_text(json.dumps(found))
print('Candidates',len(found)); print(found[:25])
