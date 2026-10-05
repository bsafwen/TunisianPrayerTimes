"""Preserve cached facts while adding exact original blue administrative vertices.

Older inventory color classification returned other for blue delegation paths.
This finite source-only successor records fresh vertices, retains original pins,
and requires the normal native registration/frame/administrative gates afterward.
"""
import argparse,json,sys
from pathlib import Path
import pymupdf
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.isie_pdf_inventory import _path_runs
from scripts.locality_automation.audit_reviewed_source_family import put

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--manifest',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();s=read(a.manifest);c=active_control(s)
 pool=set(c['approvedCycle'+str(c['iteration'])+'AcceptancePool'])
 baseline_path=Path(s['control']).resolve().parent/('root-cycle'+str(c['iteration']))/'window-baseline.json'
 baseline=read(baseline_path)
 if baseline['capturedAtUtc']!=c['windowStartUtc'] or baseline['family']!=c['familySlug']:raise ValueError('Current producer baseline differs from control')
 context=set(baseline['alreadyCompleteFamilyCodes'])
 if any(v['officialCode']not in pool and not(v.get('sourceContextOnly')and v['officialCode']in context)for v in s['cases']):raise ValueError('Outside finite source or pinned completed neighbor context')
 out=a.output.resolve();out.mkdir();cache={};cases=[];facts=[]
 for case in s['cases']:
  sha=case['sourcePdf']['sha256'];q=dict(case)
  if sha not in cache:
   pdf=checked(case['sourcePdf']);old=checked(case['sourceInventory']);inv=read(old);added=[]
   with pymupdf.open(pdf)as doc:
    for page in inv['pages']:
     drawings=doc[page['pageIndex'] if 'pageIndex'in page else 0].get_drawings()
     for item in page['nativePaths']:
      rgb=item.get('strokeColor')or[]
      blue_admin=len(rgb)==3 and rgb[0]<=.1 and rgb[1]<=.55 and rgb[2]>=.75 and item['boundaryLayerNameHint']
      if not blue_admin or not item['visibleStroke']:continue
      drawing=drawings[item['drawingIndex']];runs,unsupported=_path_runs(drawing)
      if unsupported or len(runs)!=len(item['runs']):raise ValueError('Unsupported or changed original blue path')
      for points,saved in zip(runs,item['runs']):
       if len(points)!=saved['vertices']or list(points[0])!=saved['startPagePoints']or list(points[-1])!=saved['endPagePoints']:raise ValueError('Original blue drawing metadata changed')
       if 'nativePagePoints'in saved and saved['nativePagePoints']!=[list(v)for v in points]:raise ValueError('Previously saved blue vertices changed')
       saved['nativePagePoints']=[list(v)for v in points]
      item['strokeFamily']='blue';item['relevantLineworkHint']=True;item['relevanceReasons']=list(dict.fromkeys(item.get('relevanceReasons',[])+['exact original blue administrative stroke']))
      added.append(item['drawingIndex'])
   inv['blueAdministrativeSuccessor']={'originalInventory':pin(old),'sourcePdf':pin(pdf),'addedDrawingIndices':added,'otherInventoryFactsPreserved':True,'credit':0}
   ref=put(out/(sha+'-inventory.json'),inv);cache[sha]=ref;facts.append({'sourcePdf':pin(pdf),'inventory':ref,'blueDrawingIndices':added})
  q['sourceInventory']=cache[sha];q['useAdministrativeFaces']=True;cases.append(q)
 put(out/'manifest.json',{**s,'cases':cases,'blueAdministrativeInventorySuccessor':pin(a.manifest.resolve()),'credit':0})
 put(out/'augmentation-receipt.json',{'sources':facts,'originalManifest':pin(a.manifest.resolve()),'zeroSourceOnlyCredit':True,'nativeRegistrationAndOtherGatesUnchanged':True})
 print(json.dumps({'originals':len(cache),'finiteCases':len(cases),'bluePathsAdded':sum(len(v['blueDrawingIndices'])for v in facts),'credit':0}))
if __name__=='__main__':main()
