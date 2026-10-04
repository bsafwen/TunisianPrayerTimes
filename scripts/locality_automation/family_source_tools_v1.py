"""Finite official source fetch, source contact sheets and incident face facts."""
import argparse,json,sys
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime,timezone
import requests,pymupdf
from PIL import Image,ImageDraw
from shapely import from_wkb
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.fetch_finite_official_pdf_context_v2 import official_url
from scripts.locality_automation.family_work_v1 import put

def fetch(a):
    spec=read(a.manifest);active_control(spec);rows=spec['sources']
    if not rows or len(rows)>20 or len({r['key']for r in rows})!=len(rows):raise ValueError('Finite unique source requests required')
    for r in rows:official_url(r['url'])
    a.output.mkdir()
    def one(r):
        out={**r,'startedAtUtc':datetime.now(timezone.utc).isoformat(),'proxy':'http://127.0.0.1:8888','credit':0}
        try:
            with requests.Session()as session:
                session.trust_env=False;session.proxies={'http':'http://127.0.0.1:8888','https':'http://127.0.0.1:8888'}
                response=session.get(r['url'],timeout=(10,25),allow_redirects=False,verify=r'C:\Users\barou\.mitmproxy\mitmproxy-ca-cert.pem')
                out.update(httpStatus=response.status_code,finalUrl=response.url,tlsVerification=True)
                path=a.output/(r['key']+'-response.bin');path.write_bytes(response.content);out['response']=pin(path)
                if response.status_code!=200 or not response.content.startswith(b'%PDF-'):out['status']='UNKNOWN_NON_PDF_OR_UNAVAILABLE'
                else:
                    pdf=a.output/(r['key']+'-original.pdf');pdf.write_bytes(response.content)
                    with pymupdf.open(pdf)as doc:out['pageCount']=len(doc)
                    out.update(status='FETCHED_REQUIRES_ORIGINAL_REVIEW',pdf=pin(pdf))
        except Exception as ex:out.update(status='UNKNOWN_FETCH_FAILED',error=str(ex))
        out['finishedAtUtc']=datetime.now(timezone.utc).isoformat();return out
    with ThreadPoolExecutor(max_workers=4)as pool:records=list(pool.map(one,rows))
    put(a.output/'report.json',{'manifest':pin(a.manifest),'sources':records,'allNetworkThroughRequiredProxy':True,'credit':0})
    print(json.dumps([{'key':r['key'],'status':r['status'],'httpStatus':r.get('httpStatus')}for r in records]))

def contacts(a):
    spec=read(a.manifest);active_control(spec);rows=read(a.source)['rows'];cases=spec['cases'];a.output.mkdir()
    valid={r['officialCode']:from_wkb(checked(r['sourceMetricGeometry']).read_bytes())for r in rows if 'sourceMetricGeometry'in r}
    pairs=[]
    for c,g in valid.items():
        for d,h in valid.items():
            if d<=c or not g.envelope.buffer(5).intersects(h.envelope):continue
            area=g.intersection(h).area;shared=g.boundary.intersection(h.boundary.buffer(5)).length
            if area>1 or shared>10:pairs.append({'a':c,'b':d,'overlapM2':area,'sharedWithin5mM':shared,'gapM':g.distance(h),'overlapFractionSmaller':area/min(g.area,h.area)})
    put(a.output/'paired-source-facts.json',{'pairs':pairs,'input':pin(a.source),'credit':0,'qualification':'Native registered geometry only; no inferred ownership or snapping.'})
    bycode={r['officialCode']:r for r in rows}
    for start in range(0,len(cases),4):
        image=Image.new('RGB',(2000,1500),'white');draw=ImageDraw.Draw(image)
        for i,r in enumerate(cases[start:start+4]):
            source=checked(bycode[r['officialCode']]['originalRender']) if 'originalRender'in bycode.get(r['officialCode'],{}) else a.source.parent/(r['officialCode']+'-original.png')
            if not source.exists():
                with pymupdf.open(checked(r['sourcePdf']))as doc:doc[0].get_pixmap(matrix=pymupdf.Matrix(1.8,1.8),alpha=False).save(source)
            tile=Image.open(source).convert('RGB');tile.thumbnail((995,715));x=(i%2)*1000;y=(i//2)*750+30;image.paste(tile,(x,y));draw.text((x+4,y-23),r['officialCode']+' '+r['appId'],fill='black')
        image.save(a.output/('sources-'+str(start//4+1).zfill(2)+'.png'))
    print(json.dumps({'sheets':(len(cases)+3)//4,'validFaces':len(valid),'holds':[{'code':r['officialCode'],'error':r['error']}for r in rows if 'error'in r],'interiorAdmin':[{'code':r['officialCode'],'lengths':r['nativeInteriorAdminLengthsPagePoints']}for r in rows if any(r.get('nativeInteriorAdminLengthsPagePoints',{}).values())],'outsidePage':[r['officialCode']for r in rows if r.get('wholeNativeFaceInsidePage')is False],'materialPairs':[p for p in pairs if p['overlapFractionSmaller']>.01]},ensure_ascii=False))

def roster(a):
    a.output.mkdir();spec=read(a.manifest);active_control(spec)
    with pymupdf.open(checked(spec['pdf']))as doc:
        pages=spec['pages']
        for start in range(0,len(pages),8):
            image=Image.new('RGB',(2400,2400),'white');draw=ImageDraw.Draw(image)
            for i,n in enumerate(pages[start:start+8]):
                source=a.output/('page-'+str(n)+'.png');doc[n-1].get_pixmap(dpi=125,alpha=False).save(source);tile=Image.open(source);tile.thumbnail((590,1150));x=(i%4)*600;y=(i//4)*1200+35;image.paste(tile,(x,y));draw.text((x+5,y-25),'Page '+str(n),fill='black')
            image.save(a.output/('roster-'+str(start//8+1)+'.png'))
    print(json.dumps({'pages':pages,'credit':0}))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('phase',choices=['fetch','contacts','roster']);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--source',type=Path);p.add_argument('--output',type=Path,required=True);a=p.parse_args();a.output=a.output.resolve();a.manifest=a.manifest.resolve();a.source=a.source.resolve()if a.source else None;globals()[a.phase](a)
