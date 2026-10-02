#!/usr/bin/env python3
"""Check complete Qaloun scan highlight coverage without image dependencies."""
import json
from collections import Counter
from pathlib import Path
root=Path(__file__).resolve().parent.parent
index=json.loads((root/'android-app/app/src/main/assets/quran/index.json').read_text(encoding='utf-8'))
data=json.loads((root/'android-app/app/src/main/assets/quran/highlights.json').read_text(encoding='utf-8'))
expected={(e['page'],e['surah'],e['ayah']) for e in index['entries'] if e['ayah']>0}
assert data['version']==1
assert [p['page'] for p in data['pages']]==list(range(1,604))
actual=[];rectangles=0
for page in data['pages']:
 assert page['verses'],page['page']
 for verse in page['verses']:
  identity=(page['page'],verse['surah'],verse['ayah']);actual.append(identity)
  assert verse['rects'],identity
  last_top=-1
  for rect in verse['rects']:
   assert len(rect)==4,(identity,rect)
   l,t,r,b=rect
   assert all(isinstance(c,(int,float)) and 0<=c<=1 for c in rect),(identity,rect)
   assert l<r and t<b,(identity,rect)
   assert t>=last_top,(identity,rect)
   last_top=t;rectangles+=1
assert len(actual)==len(set(actual))==6215
assert set(actual)==expected,{'missing':sorted(expected-set(actual)),'unexpected':sorted(set(actual)-expected)}
verse_counts=Counter((s,a) for _,s,a in actual)
assert len(verse_counts)==6214
assert [(ref,count) for ref,count in verse_counts.items() if count>1]==[((20,86),2)]
for page in data['pages']:
 assert 0 not in [v['ayah'] for v in page['verses']]
print(json.dumps({'pages':603,'verses':6214,'fragments':6215,'rectangles':rectangles,'complete':True}))
