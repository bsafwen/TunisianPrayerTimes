"""Close a completed finite family, using existing receipts rather than new tests."""
import argparse,json,sys,copy
from pathlib import Path
from datetime import datetime,timezone,timedelta
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.audit_reviewed_source_family import put

def time(value):return datetime.fromisoformat(value.replace('Z','+00:00'))

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--work',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True);p.add_argument('--child-work',type=Path,action='append',required=True);p.add_argument('--notes',type=Path,required=True);p.add_argument('--artifact-suffix',default='');a=p.parse_args();suffix=a.artifact_suffix;assert __import__('re').fullmatch(r'(?:-[a-z0-9]+)*',suffix);w=a.work.resolve();e=a.evidence.resolve();cp=w.parent/'control.json';c=read(cp);spec={k:c[k]for k in ['iteration','windowStartUtc','deadlineUtc']};spec.update(control=str(cp),owner='/root');active_control(spec);public=e/'work/locality-progress-dashboard';model=read(public/'task-report.json');m=read(public/'boundary-map.json');slug=c['familySlug'];progress=model['completedFamilies'][slug]
 if progress['pendingCodes']or progress['completeAcceptedEntries']!=len(c['allFamilyOfficialCodes']):raise ValueError('Family is not complete; this closer cannot claim completion')
 notes=read(a.notes);children=[f.resolve()for f in a.child_work];publications=[read(child/(slug+'-incremental-publication-receipt.json'))for child in children];replays=[read(child/(slug+'-minimum-audit-v1/actual-jvm.json'))for child in children];last=publications[-1];now=datetime.now(timezone.utc);start=time(c['windowStartUtc'])
 if any(r['failures']for r in replays):raise ValueError('An existing final target replay has failures')
 counts={'geographic':last['geographicCount'],'completeSourceBodies':last['completeBodyCount'],'installedCorrections':last['installedCorrectionCount']};gains={k:counts[k]-v for k,v in c['startedBaseline'].items()};events=[]
 for child in children:
  for path in child.glob('*.execution.json'):
   q=read(path)
   if q.get('startedAtUtc')and q.get('finishedAtUtc'):events.append({'file':pin(path),'start':q['startedAtUtc'],'end':q['finishedAtUtc'],'seconds':(time(q['finishedAtUtc'])-time(q['startedAtUtc'])).total_seconds(),'exitCode':q.get('exitCode'),'status':q.get('status'),'processId':q.get('processId'),'argv':q.get('argv',[]),'cwd':q.get('cwd')})
 intervals=sorted((max(start,time(r['start'])),min(now,time(r['end'])))for r in events if time(r['end'])>start);union=[]
 for left,right in intervals:
  if right<left:raise ValueError('Recorded process interval is reversed')
  if union and left<=union[-1][1]:union[-1]=(union[-1][0],max(right,union[-1][1]))
  else:union.append((left,right))
 wall=(now-start).total_seconds();active=sum((b-a).total_seconds()for a,b in union);failed=[r for r in events if r['exitCode']not in [0,None]]
 metrics={'status':'COMPLETED_FAMILY_PAUSED_AND_WAIT','closedAtUtc':now.isoformat(),'family':slug,'familyCompleteEntries':progress['completeAcceptedEntries'],'familyTotal':len(c['allFamilyOfficialCodes']),'counts':counts,'baseline':c['startedBaseline'],'gains':gains,'windowWallSeconds':wall,'qualifiedAdditionsPerWallHour':gains['geographic']*3600/wall,'targetCoordinates':sum(r['probeCount']for child in children for r in [read(child/(slug+'-minimum-audit-v1/numeric-report.json'))]),'actualJvmCases':sum(r['cases']for r in replays),'actualJvmFailures':0,'unrelatedLocationBehavioralTests':0,'fullCatalogueReruns':0,'sourceOnlyOrToolingCredit':0,'recordedLeafProcessCount':len(events),'recordedLeafProcessSecondsSum':sum(r['seconds']for r in events),'recordedLeafProcessIntervalUnionSeconds':active,'wallOutsideRecordedLeafSpansSeconds':wall-active,'spanQualification':'Direct guarded leaf spans only. Other wall time includes source inspection, code edits, human conversation, planning and waits; it is not proved idle or busy. Overlapping leaves are not double-counted in interval union.','preservedFailedLeafAttempts':[r['file']for r in failed],'publications':[pin(child/(slug+'-incremental-publication-receipt.json'))for child in children],'notes':pin(a.notes.resolve()),'privateGoogleKeyRead':False,'actualGoogleBrowserRenderingTested':False}
 put(w/(slug+'-completion-metrics.json'),metrics);put(w/'completed-window-real-process-spans.json',{'events':events,'intervalUnion':[[x.isoformat(),y.isoformat()]for x,y in union]})
 # Use actual recorded increment openings, not the full common window for every
 # batch. This only corrects report task timing; acceptance times/counts stay.
 openings={children[0].name:start}
 for r in events:
  if r['exitCode']==0 and any(Path(x).name in ['continue_family_increment_v1.py','continue_family_increment_v2.py']for x in r['argv']):
   previous=Path(r['cwd']);next_work=previous.parent/('root-cycle'+str(int(previous.name.removeprefix('root-cycle'))+1));openings[next_work.name]=time(r['start'])
 timing=[];modified_scope_tasks=0
 for child in children:
  pub=read(child/(slug+'-incremental-publication-receipt.json'));receipt=read(checked(pub['practicalReceipt']));ended=time(receipt['appliedAtUtc']);opened=openings.get(child.name,start);taskid=slug+'-cycle'+child.name.removeprefix('root-cycle');timing.append({'taskId':taskid,'start':opened.isoformat(),'end':ended.isoformat(),'basis':'Recorded guarded increment opening; first batch uses explicit overall window start.'})
  for hour in model['hours']:
   left=time(hour['hour']);right=left+timedelta(hours=1)
   for group in hour.get('locationGroups',[]):
    tasks=group.get('tasks',[])
    for task in tasks:
     if task.get('taskId')!=taskid:continue
     task.update(startedAtUtc=opened.isoformat(),endedAtUtc=ended.isoformat(),durationSeconds=(ended-opened).total_seconds(),recordedDurationSeconds=(ended-opened).total_seconds(),timeInHourSeconds=round(max(0,(min(ended,right)-max(opened,left)).total_seconds()),3),timeQualification='Recorded scope window, not per-location CPU time; source/QA/LLM/user interaction may share this interval.');modified_scope_tasks+=1
   affected=any(t.get('taskId')==taskid for g in hour.get('locationGroups',[])for t in g.get('tasks',[]))
   if affected:
    for group in hour['locationGroups']:group['tasks']=[t for t in group.get('tasks',[])if not(t.get('taskId')==taskid and t['timeInHourSeconds']==0 and not group.get('validatedLocationCodes'))]
    hour['locationGroups']=[g for g in hour['locationGroups']if g.get('tasks')or g.get('validatedLocationCodes')];hour['taskCount']=sum(len(g.get('tasks',[]))for g in hour['locationGroups'])
 if modified_scope_tasks<len(children):raise ValueError('Expected current-family timing tasks missing')
 put(w/'report-increment-timing-review.json',{'timing':timing,'modifiedScopeTasks':modified_scope_tasks,'zeroDurationGhostTasksRemoved':True,'countsAndAcceptanceTimesUnchanged':True,'additionalValidationCredit':0})
 review=['# '+slug.capitalize()+' completed; paused and waiting','','Completed '+now.isoformat()+'. '+str(progress['completeAcceptedEntries'])+'/'+str(len(c['allFamilyOfficialCodes']))+' complete source boundaries installed. Gains: '+str(gains['geographic'])+' geographic additions, '+str(gains['completeSourceBodies'])+' complete bodies, '+str(gains['installedCorrections'])+' unique installed boundary corrections. Final global counts '+str(counts)+'.','','Minimal verification: '+str(metrics['targetCoordinates'])+' changed-location coordinates, '+str(metrics['actualJvmCases'])+' actual current JVM cases, zero failures and zero unrelated-location behavioral tests. No new APK or Android UI claim. Source-only work, name corrections and tooling earn zero boundary additions.','','Elapsed window '+format(wall/60,'.2f')+' minutes; '+format(metrics['qualifiedAdditionsPerWallHour'],'.2f')+' qualified additions per elapsed hour. Recorded leaf span sum '+format(metrics['recordedLeafProcessSecondsSum'],'.3f')+'s; interval union '+format(active,'.3f')+'s. Unrecorded wall time is not classified as idle or productive.','','Applied improvements:']+['','\n'.join('- '+s for s in notes['appliedImprovements']),'','Avoidable errors and correction:','', '\n'.join('- '+s for s in notes['errorsAndCorrections']),'','Remaining limits:','', '\n'.join('- '+s for s in notes['limits']),'','The same timer must be PAUSED and verified before final handoff. No new family or automatic resume.']
 text='\n'.join(review)+'\n';(w/('SELF_EVALUATION'+suffix+'.md')).write_text(text,encoding='utf-8');(w.parent/('window-'+str(c['iteration']).zfill(3)+'-review'+suffix+'.md')).write_text(text,encoding='utf-8')
 c.update(phase='paused',familyGoalComplete=True,goalIncomplete=True,heldCodes=[],completedAtUtc=now.isoformat(),latestRefreshedCounts=counts,currentAcceptedCycleCodes=c['allFamilyOfficialCodes'],currentAcceptedCycleFullCount=len(c['allFamilyOfficialCodes']),pendingSourceCodes=[],sourceQaWorkers=[],activeValidationPids=[],reviewDecision=slug.capitalize()+' complete; improvements applied. PAUSED and WAIT for human instructions.',sourceIntakeNextAction='WAIT; no new locations or background workers.',dispatchPolicy='PAUSED; completed finite family, wait for human instructions.',lastControlUpdateUtc=now.isoformat(),latestCompletionMetrics=pin(w/(slug+'-completion-metrics.json')))
 c['worthwhileImprovementFound']=True;c['latestAppliedImprovements']=pin(a.notes.resolve());c['latestInstalledPracticalReceipt']=read(e/'work/isie-execution-20260926/run.json')['practicalProgress'];c['latestBoundaryPracticalReceipt']=last['practicalReceipt']
 companion=put(w/('current-actors-pids-dispatch-intake-final'+suffix+'.json'),{'owner':'/root','workers':[],'activeOwnedValidationPids':[],'phase':'paused-completed-family','observedEndedDirectLeafCount':len(events),'cutoffProcessObservation':notes['cutoffProcessObservation'],'completedFamily':slug,'noAutomaticResume':True});c['latestActorsPidsDispatchIntake']=companion;c['latestActors']=companion['file']
 wf=e/'CURRENT_WORKFLOW.md';wf.write_text('# '+slug.capitalize()+' complete —PAUSED and WAIT\n\n'+str(progress['completeAcceptedEntries'])+'/'+str(len(c['allFamilyOfficialCodes']))+' complete boundaries installed. Final global totals'+str(counts)+'. Gains'+str(gains)+'. Root only, no agents, no live owned validation jobs. Do not start another location or automatically resume. Same timer must be verified PAUSED.\n\nRead work/locality-efficiency-cycles-20261001/window-'+str(c['iteration']).zfill(3)+'-review.md and root-cycle'+str(c['iteration'])+'/'+slug+'-completion-metrics.json. Map defaults to combined '+slug+' group with all'+str(progress['currentWorkNewLocations'])+' NEW markers, preserving original child receipts/times/geometries. '+str(metrics['targetCoordinates'])+' target coordinates/'+str(metrics['actualJvmCases'])+' actual JVM cases; no unrelated tests.\n\nAll registration exceptions remain exact entry/PDF-specific proofs, with default fitting and all other gates unchanged. Old failures/holds remain. All host HTTP/HTTPS through127.0.0.1:8888; never web__run/private key reads/browser-preview bypass. This completes '+slug+' only; the overall Tunisia objective remains incomplete.\n\n'+wf.read_text(encoding='utf-8'),encoding='utf-8');c['currentWorkflow']=pin(wf);cp.write_text(json.dumps(c,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
 model['generatedAtUtc']=now.isoformat();m['generatedAtUtc']=now.isoformat()
 for ref in model['sourceFiles']:
  if ref.get('kind')=='pointer'and Path(ref['path']).resolve()==cp.resolve():ref['sha256']=pin(cp)['sha256']
 sys.path.insert(0,str(public));import task_report_app as app
 original=(app.build_task_report,app.build_boundary_map,app.refresh_frina_investigation,sys.argv)
 try:app.build_task_report=lambda:model;app.build_boundary_map=lambda unused:m;app.refresh_frina_investigation=lambda:None;sys.argv=[str(public/'task_report_app.py'),'refresh'];app.main()
 finally:app.build_task_report,app.build_boundary_map,app.refresh_frina_investigation,sys.argv=original
 put(w.parent/('window-'+str(c['iteration']).zfill(3)+'-final'+suffix+'.json'),{'status':'COMPLETED_FAMILY_PAUSED_AND_WAIT','metrics':pin(w/(slug+'-completion-metrics.json')),'review':pin(w/('SELF_EVALUATION'+suffix+'.md')),'control':pin(cp),'workflow':pin(wf),'counts':counts,'gains':gains,'timerVerificationRequired':True,'activeWorkers':[],'activeOwnedValidationPids':[]});print(json.dumps({'family':slug,'complete':progress['completeAcceptedEntries'],'counts':counts,'gains':gains,'targetCoordinates':metrics['targetCoordinates'],'actualJvmCases':metrics['actualJvmCases'],'phase':'paused'}))
if __name__=='__main__':main()

