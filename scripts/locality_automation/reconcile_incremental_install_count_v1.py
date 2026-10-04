"""Paused administrative count reconciliation; no geometry or behavior replay."""
import argparse,json,os,sys
from copy import deepcopy
from datetime import datetime,timezone
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import read,checked
from scripts.locality_automation.stage_boundary_patch import pin
from scripts.locality_automation.audit_reviewed_source_family import put

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--work',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True);a=p.parse_args();w=a.work.resolve();e=a.evidence.resolve();cp=w.parent/'control.json';c=read(cp)
    if c['phase']!='paused'or c['acceptanceOwner']!='/root'or c.get('activeValidationPids')or not c['djerbaComplete']:raise ValueError('Paused sole-root completed-family administrative correction required')
    public=e/'work/locality-progress-dashboard';before=read(w/'before-djerba-publication-task-report.json');oldpub=read(w/'djerba-incremental-publication-receipt.json');practical=read(checked(oldpub['practicalReceipt']));prior=read(checked(practical['previousPracticalProgress']))
    added=set(practical['cumulativeBoundaryLocalityCodes'])-set(prior.get('cumulativeBoundaryLocalityCodes',prior['boundaryLocalityCodes']))
    if added!=set(oldpub['newLocationCodes']):raise ValueError('Correction delta differs from finite batch additions')
    count=before['summary']['uniqueInstalledCorrectionLocations']+len(added)
    old=read(public/'task-report.json');m=read(public/'boundary-map.json');model=deepcopy(old);now=datetime.now(timezone.utc)
    if count!=old['summary']['uniqueInstalledCorrectionLocations']+2:raise ValueError('Expected missing two historical installed counts only')
    for name in ['task-report.json','boundary-map.json','dashboard.html']:
        with (w/('before-count-reconciliation-'+name)).open('xb')as f:f.write((public/name).read_bytes())
    model['summary']['uniqueInstalledCorrectionLocations']=count;model['generatedAtUtc']=now.isoformat();hour=datetime.fromisoformat(practical['appliedAtUtc']).replace(minute=0,second=0,microsecond=0)
    for r in model['cumulativeSeries']:
        if datetime.fromisoformat(r['hour'])>=hour:r['cumulativeInstalledCorrectionLocationCount']=count
    model['limits'].append('Installed correction count carries forward the published baseline, including two historical corrections outside the practical chain, and adds only this batch\'s new chain codes. No historical location was revalidated.')
    sys.path.insert(0,str(public));import task_report_app as app
    oldbuild,oldmap,oldrefresh,oldargv=app.build_task_report,app.build_boundary_map,app.refresh_frina_investigation,sys.argv
    try:
        app.build_task_report=lambda:model;app.build_boundary_map=lambda unused:m;app.refresh_frina_investigation=lambda:None;sys.argv=[str(public/'task_report_app.py'),'refresh'];app.main()
    finally:app.build_task_report=oldbuild;app.build_boundary_map=oldmap;app.refresh_frina_investigation=oldrefresh;sys.argv=oldargv
    fresh=read(public/'task-report.json');mapfresh=read(public/'boundary-map.json')
    if fresh['summary']['uniqueInstalledCorrectionLocations']!=count or mapfresh!=m or any(r['cumulativeInstalledCorrectionLocationCount']!=count for r in fresh['cumulativeSeries']if datetime.fromisoformat(r['hour'])>=hour):raise ValueError('Count reconciliation output differs')
    newpub={**oldpub,'installedCorrectionCount':count,'precedingCountErrorPublication':pin(w/'djerba-incremental-publication-receipt.json'),'historicalInstalledCorrectionCountCarried':before['summary']['uniqueInstalledCorrectionLocations'],'newInstalledCorrectionCount':len(added),'newInstalledCorrectionCodes':sorted(added),'publicationAccountingCorrectedAtUtc':now.isoformat(),'outputs':[pin(public/name)for name in ['task-report.json','boundary-map.json','dashboard.html']]}
    pubref=put(w/'djerba-incremental-publication-receipt-v3.json',newpub)
    metrics=read(w/'window-final-metrics.json');metrics['finalCounts']['installedCorrections']=count;metrics['gains']['installedCorrections']=len(added);metrics['accountingCorrection']={'original':pin(w/'window-final-metrics.json'),'reason':'Two historical installed corrections absent from chain-only total were carried through from the published baseline. No geometry or behavioral rechecks.','correctedAtUtc':now.isoformat(),'publication':pubref};metrics['meaningfulImprovementsApplied'].append('Installed correction increments use the actual before/after chain code delta added to the published baseline, preserving historical corrections outside the chain. Applied and verified by count-only reconciliation.')
    metricref=put(w/'window-final-metrics-v2.json',metrics)
    oldreview=w.parent/'window-023-review.md';review=w.parent/'window-023-review-v2.md';text=oldreview.read_text(encoding='utf-8');text=text.replace('6 additional unique installed corrections','8 additional unique installed corrections').replace('483/351/296','483/351/298')
    text+='\n\nFinal accounting correction: the chain cumulative set excludes two historically installed corrections already included in the published baseline. Publisherv3 now retains baseline290 and adds the exact eight new chain codes, yielding298. This count-only paused reconciliation reran no geometry or behavioral checks and left the map data identical. Original count-error artifacts remain preserved. Authoritative publication:djerba-incremental-publication-receipt-v3.json; metrics:window-final-metrics-v2.json.\n'
    with review.open('x',encoding='utf-8')as f:f.write(text)
    outcome=read(w.parent/'window-023-final.json');outcome.update(finalCounts=metrics['finalCounts'],gains=metrics['gains'],metrics=metricref,review=pin(review),publication=pubref,originalCountErrorOutcome=pin(w.parent/'window-023-final.json'))
    finalref=put(w.parent/'window-023-final-v2.json',outcome)
    c['latestRefreshedCounts']=metrics['finalCounts'];c['latestMapPublication']=pubref;c['finalCycle23Review']=pin(review);c['finalCycle23Outcome']=finalref;c['cycle23Metrics']=metricref;c['lastControlUpdateUtc']=now.isoformat();c['latestAdministrativeAccountingCorrection']=pubref
    current=e/'CURRENT_WORKFLOW.md';current.write_text('# Window023 final accounting reconciled\n\nDjerba remains complete24/24; correct totals483 geographic /351 complete source bodies /298 unique installed corrections. Eight new chain corrections were added to baseline290, carrying two historical installed corrections outside the practical receipt chain. Count-only administrative fix applied while paused; zero geometry/behavior reruns and identical map data. Authoritative review:window-023-review-v2.md; outcome:window-023-final-v2.json; publication:root-cycle23/djerba-incremental-publication-receipt-v3.json; metrics:root-cycle23/window-final-metrics-v2.json. Timer remains PAUSED; WAIT. Use publisherv3 for future batches. Original wrong-count attempts preserved.\n\n'+current.read_text(encoding='utf-8'),encoding='utf-8');c['currentWorkflow']=pin(current)
    tmp=cp.with_name('control.count-reconciliation.tmp');tmp.write_text(json.dumps(c,ensure_ascii=False,indent=2),encoding='utf-8');os.replace(tmp,cp)
    with (e/'LOCALITY_VALIDATION_PROCESS.md').open('a',encoding='utf-8')as f:f.write('\n\nWindow023 accounting correction: use publish_installed_family_incremental_v3.py. Unique installed corrections must retain the published baseline, including historical corrections outside the practical chain, and add only the new before/after chain code delta. The complete current chain set alone is insufficient. Djerba actual delta8 plus baseline290 yields298. Count-only correction verified without any further location/geometry/behavior tests. Original publisherv2 count-error receipt and review remain historical, superseded by v3 publication and v2 final review/metrics.\n')
    print(json.dumps({'reconciled':True,'counts':metrics['finalCounts'],'addedInstalledCodes':sorted(added),'mapDataIdentical':True,'newGeometryOrBehaviorChecks':0,'phase':'paused'}))
