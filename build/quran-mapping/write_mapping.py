import json
from pathlib import Path
out=Path(r'C:\Users\barou\Desktop\Workspace\TunisianPrayerTimes\build\quran-mapping')
raw=json.loads((out/'title_pages.json').read_text())
# All 110 detected title crops visually audited in five contact sheets.
# Full source scans 585 and 602 verify the four skewed banners omitted by rule detection.
raw.insert(80,585)
raw[108:108]=[602,602,602]
assert len(raw)==114
assert all(a<=b for a,b in zip(raw,raw[1:]))
result={'source':'C:/Users/barou/Downloads/Quran_Qaloun_pages','indexing':'1-based source image file index; printed Quran folio is sourcePage + 1','verification':'Every surah title visually checked against supplied scans; sources 604-621 are appendices. Source585 and602 read in full because skew prevented line detection.','surahs':[{'number':i,'sourcePage':page,'printedPage':page+1} for i,page in enumerate(raw,1)]}
(out/'quran_qaloun_surahs.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(','.join(map(str,raw)))
