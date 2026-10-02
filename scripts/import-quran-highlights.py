#!/usr/bin/env python3
"""Measure verse highlight rectangles from the supplied Qaloun scans.

Offline only. Requires Pillow, NumPy and opencv-python-headless. Input index must
already include the visually verified Qaloun page corrections. No OCR or Hafs
coordinate data is used. Ordinary and final-verse templates come from source
images 2 and 1, with their digit interiors masked out before matching.
"""
from pathlib import Path
import argparse, concurrent.futures, hashlib, json, sys, time
import cv2
import numpy as np
from PIL import Image

cv2.setNumThreads(1)
def read(n):
 im=Image.open(base/f'page_{n:03}.webp').convert('RGB');return np.array(im.resize((round(im.width*1450/im.height),1450)))
def find(n):
 im=read(n);gray=cv2.cvtColor(im,cv2.COLOR_RGB2GRAY)
 h,w=gray.shape
 prof=(gray[int(h*.07):int(h*.94)]<75).mean(axis=0)
 # Inner frame's continuous dark rules.
 xs=np.where(prof>.62)[0]; left=xs[xs<w*.4].max()+4;right=xs[xs>w*.6].min()-3
 found=[]
 for ti,t in enumerate(templates):
  th,tw=t.shape[:2];mask=np.ones((th,tw),np.uint8)*255;mask[15:45,14:46]=0
  tt=cv2.cvtColor(t,cv2.COLOR_RGB2GRAY)
  res=cv2.matchTemplate(gray,tt,cv2.TM_CCOEFF_NORMED,mask=mask);res[~np.isfinite(res)]=0
  res[:round(h*.045)]=0;res[round(h*.95):]=0;res[:,:max(0,left-5)]=0;res[:,right-tw+6:]=0
  for i in range(70):
   _,v,_,p=cv2.minMaxLoc(res)
   if v<.67:break
   x,y=p;cx=x+tw/2;cy=y+th/2
   if not any(abs(cx-f['cx'])<30 and abs(cy-f['cy'])<30 for f in found):found.append(dict(x=x,y=y,w=tw,h=th,cx=cx,cy=cy,score=round(v,4),template=ti))
   res[max(0,y-35):y+36,max(0,x-35):x+36]=0
 found.sort(key=lambda f:(round(f['cy']/40),-f['cx']))
 return n,dict(width=w,height=h,left=int(left),right=int(right),markers=found)

def geometry(n,d):
 im=read(n);gray=cv2.cvtColor(im,cv2.COLOR_RGB2GRAY);h,w=gray.shape;l=d['left'];r=d['right']
 # Long horizontal rules on source scans delimit chapter banners, robust to small skew.
 ink=(gray<100).astype(np.uint8)
 crop=ink[:,l+12:r-12]
 lines=cv2.HoughLinesP(crop,1,np.pi/1800,threshold=250,minLineLength=(r-l)*.68,maxLineGap=8) if n in starts else None
 ys=[]
 for ln in [] if lines is None else lines[:,0,:]:
  x1,y1,x2,y2=ln
  if abs(y2-y1)<15 and min(y1,y2)>50:ys.append((int(y1)+int(y2))/2)
 ys.sort();groups=[]
 for y in ys:
  if not groups or y-groups[-1][-1]>12:groups.append([y])
  else:groups[-1].append(y)
 rules=[float(np.median(g)) for g in groups]
 pairs=[(a,b) for a in rules for b in rules if 87<b-a<110]
 banners=[]
 for a,b in pairs:
  if not banners or a-banners[-1][0]>35:banners.append((a,b))
  elif abs(b-a-95)<abs(banners[-1][1]-banners[-1][0]-95):banners[-1]=(a,b)
 manual={384:[(1244,1419)],495:[(116,290)],565:[(1402,1575)],567:[(1412,1586)],584:[(123,296)],588:[(288,463)],596:[(138,312),(1102,1276)],597:[(134,308),(942,1116)],599:[(287,462),(1560,1735)]}
 if n in manual:
  scale=1450/Image.open(base/f'page_{n:03}.webp').height
  banners=[(a*scale,b*scale) for a,b in manual[n]]
 d['banners']=banners
 d['bannerOk']=len(banners)==len(starts.get(n,[]))
 # Marker rows are known body baselines; discover remaining rows through horizontal ink projection.
 markers=sorted(d['markers'],key=lambda m:m['cy']);mg=[]
 for m in markers:
  if not mg or m['cy']-np.mean([a['cy'] for a in mg[-1]])>30:mg.append([m])
  else:mg[-1].append(m)
 markerrows=[float(np.median([m['cy'] for m in g])) for g in mg]
 ordered=[m for g in mg for m in sorted(g,key=lambda m:-m['cx'])]
 d['markers']=ordered
 prof=ink[:,l+8:r-8].mean(axis=1).astype(np.float32)
 smooth=cv2.GaussianBlur(prof.reshape(-1,1),(1,21),4).ravel()
 allowed=np.ones(h,dtype=bool);allowed[:90]=False;allowed[1380:]=False
 for a,b in banners:allowed[max(0,int(a)-12):min(h,int(b)+28)]=False
 peaks=[]
 scores=smooth.copy();scores[~allowed]=0
 for y in markerrows:scores[max(0,int(y)-57):int(y)+58]=0
 for i in range(30):
  y=int(scores.argmax());v=float(scores[y])
  if v<.08:break
  peaks.append(float(y));scores[max(0,y-57):y+58]=0
 rows=sorted(markerrows+peaks)
 d['rows']=rows
 d['rowScores']=[round(float(smooth[round(y)]),3) for y in rows]
 # Basmalah rows immediately below every chapter banner, except At-Tawba.
 basmalah=[]
 for banner,ch in zip(banners,starts.get(n,[])):
  candidates=[i for i,y in enumerate(rows) if y>banner[1] and y<banner[1]+85]
  if ch!=9 and candidates:basmalah.append(candidates[0])
 d['basmalahRows']=basmalah
 d['expectedStarts']=starts.get(n,[])
 d['markerRowError']=any(min(abs(m['cy']-y) for y in rows)>15 for m in markers)
 return d


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--index', type=Path, default=Path('android-app/app/src/main/assets/quran/index.json'))
    parser.add_argument('--output', type=Path, default=Path('android-app/app/src/main/assets/quran/highlights.json'))
    parser.add_argument('--audit', type=Path, default=Path('scripts/quran-data/highlights-audit.json'))
    args = parser.parse_args()
    base = args.source
    index = json.loads(args.index.read_text(encoding='utf-8'))
    manifest = {str(n): [] for n in range(1, 604)}
    for entry in index['entries']:
        manifest[str(entry['page'])].append(entry)
    starts = {}
    for chapter in index['surahs']:
        starts.setdefault(chapter['page'], []).append(chapter['number'])
    a, b = read(2), read(1)
    templates = [a[171:230, 666:725], b[657:720, 327:389]]
    data = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        for n, detected in pool.map(find, range(1, 604)):
            expected = [e for e in manifest[str(n)] if e['ayah'] > 0 and e['isLastFragment']]
            assert len(detected['markers']) == len(expected), (n, len(detected['markers']), len(expected))
            data[str(n)] = detected
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        values = list(pool.map(lambda n: geometry(n, data[str(n)]), range(1, 604)))
    data = {str(n): d for n, d in enumerate(values, 1)}
    output={'version':1,'coordinateSystem':'normalized original scan','pages':[]};audit=[]
    for n in sorted(map(int,data)):
     d=data[str(n)];fragments=[e for e in manifest[str(n)] if e['ayah']>0];markers=d['markers'];ends=[e for e in fragments if e['isLastFragment']];assert len(markers)==len(ends),(n,len(markers),len(ends));assert d['bannerOk'],n
     rows=d['rows'];w=d['width'];h=d['height'];l=d['left'];r=d['right'];im=read(n);ink=(cv2.cvtColor(im,cv2.COLOR_RGB2GRAY)<220);forbidden=set(d['basmalahRows']);mi=0;row=0;x=r;verses=[];prevch=None
     headingStarts={}
     for ch,(_,bottom) in zip(starts.get(n,[]),d['banners']):
      ri=next(i for i,y in enumerate(rows) if y>bottom+28 and i not in forbidden)
      headingStarts[ch]=ri
     for e in fragments:
      ch=e['surah'];ayah=e['ayah']
      if ch!=prevch and ch in headingStarts:row=headingStarts[ch];x=r
      if e['isLastFragment']:
       m=markers[mi];mi+=1;erow=min(range(len(rows)),key=lambda i:abs(rows[i]-m['cy']));ex=m['x']
      else:erow=len(rows)-1;ex=l
      assert erow>=row,(n,ch,ayah,row,erow)
      rects=[]
      for ri in range(row,erow+1):
       if ri in forbidden:continue
       y=rows[ri];top=max(y-52,(rows[ri-1]+y)/2 if ri else 70);bottom=min(y+42,(y+rows[ri+1])/2 if ri+1<len(rows) else 1380)
       for a,b in d['banners']:
        if b<y:top=max(top,b+10)
        if a>y:bottom=min(bottom,a-8)
       left=ex if ri==erow else l;right=x if ri==row else r
       if right-left<3:continue
       # Tighten horizontal bounds to visible content; empty line tails produce no highlight.
       section=ink[int(top):int(bottom)+1,int(left):int(right)+1]
       occupied=np.where(section.sum(axis=0)>1)[0]
       if occupied.size==0:continue
       ll=max(left,left+int(occupied[0])-4);rr=min(right,left+int(occupied[-1])+5)
       if rr-ll<8:continue
       rects.append([round(ll/w,6),round(top/h,6),round(rr/w,6),round(bottom/h,6)])
      assert rects,(n,ch,ayah,'empty')
      verses.append({'surah':ch,'ayah':ayah,'rects':rects});row=erow;x=ex;prevch=ch
     output['pages'].append({'page':n,'verses':verses})
     audit.append({'page':n,'markers':len(markers),'rows':len(rows),'textRows':len(rows)-len(forbidden),'banners':len(d['banners'])})
    assert sum(len(p['verses']) for p in output['pages']) == 6215
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, ensure_ascii=False, separators=(',', ':')) + '\n', encoding='utf-8')
    for item in audit:
        n = item['page']
        item['minimumMarkerScore'] = min(m['score'] for m in data[str(n)]['markers'])
        item['sourceSha256'] = hashlib.sha256((base / f'page_{n:03}.webp').read_bytes()).hexdigest()
    evidence = {'version': 1, 'source': 'User-supplied Quran_Qaloun_pages',
        'normalizedHeight': 1450, 'minimumAcceptedMarkerScore': 0.67,
        'templateSourceCrops': [{'page': 2, 'xyxy': [666,171,725,230]}, {'page':1, 'xyxy':[327,657,389,720]}],
        'digitMaskXyxy': [14,15,46,45], 'manuallyReviewedBannerPages': [384,495,565,567,584,588,596,597,599],
        'pages': audit}
    args.audit.write_text(json.dumps(evidence, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'pages': len(output['pages']), 'verses': 6214, 'fragments': 6215,
        'rectangles': sum(len(v['rects']) for p in output['pages'] for v in p['verses'])}))
