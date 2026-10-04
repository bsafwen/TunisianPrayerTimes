"""Reusable finite family window and cached-source preparation; no acceptance."""
import argparse, json, shutil, sys, unicodedata
from datetime import datetime, timezone, timedelta
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import read, pin, checked, active_control
from scripts.locality_automation.isie_pdf_inventory import inspect

def put(path, value):
    with path.open('x', encoding='utf-8') as f:
        json.dump(value, f, ensure_ascii=False, indent=2)

def norm(text):
    text = unicodedata.normalize('NFKC', text)
    return ''.join(c for c in text if c.isalnum()).translate(str.maketrans('أإآىة', 'ااايه'))

def paris_display(value):
    # EU DST display only; all launch decisions use the original aware UTC guard.
    def last_sunday(month):
        next_month=datetime(value.year,month+1,1,tzinfo=timezone.utc)
        last=next_month-timedelta(days=1)
        return (last-timedelta(days=(last.weekday()+1)%7)).replace(hour=1)
    offset=2 if last_sunday(3)<=value<last_sunday(10) else 1
    return value.astimezone(timezone(timedelta(hours=offset))).isoformat()

def opened(a):
    e=a.evidence.resolve(); cdir=e/'work/locality-efficiency-cycles-20261001'
    c=read(cdir/'control.json'); assert c['phase']=='paused'
    iteration=c['iteration']+1; w=cdir/('root-cycle'+str(iteration))
    if w.exists():
        assert set(x.name for x in w.iterdir()) <= {'before-control.json','launch_root_phase.py','open-failed-source-v1.py'}
    else:w.mkdir()
    shutil.copyfile(cdir/'control.json',w/'before-control.json')
    shutil.copyfile(cdir/('root-cycle'+str(c['iteration']))/'launch_root_phase.py',w/'launch_root_phase.py')
    reg=read(ROOT/'scripts/neighborhoods/touil-reviews/current-official-sector-registry.json')
    rows=[r for r in reg['sectors']if r['governorateCode']==a.governorate_code]
    summary=read(e/'work/locality-progress-dashboard/task-report.json')['summary']
    full=set(summary['explicitFullSourceBoundaryLocalityCodes']);codes=[r['sectorCode']for r in rows];pool=[v for v in codes if v not in full]
    now=datetime.now(timezone.utc).replace(microsecond=0);end=now+timedelta(hours=2)
    baseline={'geographic':summary['uniqueValidatedLocations'],'completeSourceBodies':summary['explicitFullSourceBoundaryLocationCount'],'installedCorrections':summary['uniqueInstalledCorrectionLocations']}
    c.update(phase='working',iteration=iteration,windowStartUtc=now.isoformat(),deadlineUtc=end.isoformat(),deadlineParis=paris_display(end),workHours=2,startedBaseline=baseline,latestRefreshedCounts=baseline,acceptanceOwner='/root',acceptanceActor='/root',mapOwner='/root',mapActor='/root',subagentsAllowed=False,activeValidationPids=[],sourceQaWorkers=[],pendingSourceCodes=pool,currentAcceptedCycleCodes=[],currentAcceptedCycleFullCount=0,coordinatorCompanions=str(w),intakeCompanions=str(w),latestActors=str(w/'current-actors-pids-dispatch-intake.json'),familySlug=a.family,governorateCode=a.governorate_code,allFamilyOfficialCodes=codes,acceptanceScope='Only remaining '+a.family+' bodies; no unrelated tests.',rootRole='Root-only source/neighbor review and minimum target replay.',reviewPolicy='Complete the authorized family; at the safety cutoff preserve and review gains, then wait if incomplete.',safeSourceQaStartUtc=(end-timedelta(minutes=30)).isoformat(),safeIntakeStartUtc=(end-timedelta(minutes=20)).isoformat(),safeMapStartUtc=(end-timedelta(minutes=7)).isoformat(),mapMinimumRemainingSeconds=420,cutoffTimerStatus='PENDING_VERIFICATION',cutoffAutomationStatus='PENDING',userAuthorizedAdaptiveCycles=False,lastControlUpdateUtc=now.isoformat())
    c['approvedCycle'+str(iteration)+'AcceptancePool']=pool
    put(w/'window-baseline.json',{'capturedAtUtc':now.isoformat(),'counts':baseline,'family':a.family,'allFamilyOfficialCodes':codes,'alreadyCompleteFamilyCodes':[v for v in codes if v in full],'remainingFamilyCodes':pool,'unrelatedLocationsChecked':0,'registry':pin(ROOT/'scripts/neighborhoods/touil-reviews/current-official-sector-registry.json')})
    put(w/'current-actors-pids-dispatch-intake.json',{'atUtc':now.isoformat(),'owner':'/root','workers':[],'ownedValidationPids':[],'dispatch':a.family+' only; no subagents','phase':'awaiting-timer-verification'})
    (cdir/'control.json').write_text(json.dumps(c,ensure_ascii=False,indent=2),encoding='utf-8')
    p=e/'CURRENT_WORKFLOW.md';p.write_text('# '+a.family+' active - window'+str(iteration)+'\n\nHuman authorization: finish '+a.family+'. Root only, no subagents. Finite '+str(len(pool))+' remaining entries; reuse already complete bodies. Deadline '+end.isoformat()+'. Reuse cached originals, exact native extraction, target-only staging, minimum JVM replay and incremental map/hour publication. Proxy 127.0.0.1:8888; no web__run/private key reads. The 2m exception remains only525363. Every engine phase uses existing original direct UTC guard and a fresh receipt.\n\n'+p.read_text(encoding='utf-8'),encoding='utf-8')
    print(json.dumps({'work':str(w),'iteration':iteration,'remaining':len(pool),'start':now.isoformat(),'deadline':end.isoformat(),'deadlineParis':c['deadlineParis'],'baseline':baseline}))

def sources(a):
    w=a.work.resolve();e=a.evidence.resolve();c=read(w.parent/'control.json');spec={k:c[k]for k in ['iteration','windowStartUtc','deadlineUtc']};spec.update(control=str(w.parent/'control.json'),owner='/root');active_control(spec)
    av=read(a.availability);reg=read(ROOT/'scripts/neighborhoods/touil-reviews/current-official-sector-registry.json')['sectors'];official={r['sectorCode']:r for r in reg};claims=read(ROOT/'scripts/neighborhoods/touil-reviews/original-pbf-source-facts.json')['administrativeCodeClaims'];cat=read(ROOT/'android-app/app/src/main/assets/neighborhoods.json');byid={r['id']:r for r in cat['features']};pool=c['approvedCycle'+str(c['iteration'])+'AcceptancePool'];cases=[];holds=[]
    for code in pool:
        r=official[code];name=r['sectorAr'];parent=r['delegationAr'];stem=name.rstrip('12')
        matches=[s for s in av['isieSources']if norm(s['delegationPath'])==norm(parent) and norm(s['pdfFilename'].removesuffix('.pdf').split(' - (')[0]) in {norm(name),norm(stem)}]
        appids=[i for i in claims.get(code,[])if i in byid and byid[i]['kind']=='sector']
        if len(appids)!=1:
            appids=[i for i,v in byid.items()if v['kind']=='sector' and norm(v['parentName'].removeprefix('معتمدية '))==norm(parent) and any(norm(n) in {norm(name),norm(stem)} for n in [v['name']]+v['aliases'])]
        if len(matches)!=1 or len(appids)!=1 or not matches[0].get('selectedPdf'):
            holds.append({'code':code,'name':name,'parent':parent,'sources':[{'name':s['indexText'],'url':s['url'],'availability':s['availability']}for s in matches],'appIds':appids,'reason':'Requires explicit official-name/source/app binding or missing source'});continue
        s=matches[0];pdf=checked(s['selectedPdf']);cached=e/'work/isie-execution-20260926/pdf-inventories'/(s['selectedPdf']['sha256']+'.json')
        if cached.exists():inventory=cached
        else:inventory=w/(code+'-original-inventory.json');put(inventory,inspect(pdf))
        leads=read(inventory)['pages'][0]['labeledAreaLeads'];own=[(i,l)for i,l in enumerate(leads)if l['centerPagePoints'][1]>100 and norm(l['text'])in {norm(stem),norm(stem[::-1]),norm(name),norm(name[::-1])}]
        case={'officialCode':code,'officialName':name,'officialParent':parent,'appId':appids[0],'sourcePdf':pin(pdf),'sourceInventory':pin(inventory),'sourceUrl':s['url'],'identityBasis':'INS code claim where available; exact parent-qualified catalog name otherwise. Footnote-like terminal digits are candidates only, require visual official attribution.','appMetadata':byid[appids[0]]}
        if len(own)==1:case['diagnosticTargetLabelFace']={'targetLabelIndex':own[0][0],'targetLabelText':own[0][1]['text']}
        cases.append(case)
    put(w/(a.family+'-originals-manifest.json'),{**spec,'exactTargets':pool,'cases':cases,'unavailableCodes':[h['code']for h in holds],'availability':pin(a.availability),'holds':holds,'credit':0})
    print(json.dumps({'prepared':len(cases),'holds':holds,'credit':0},ensure_ascii=False))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('phase',choices=['open','sources']);p.add_argument('--family',required=True);p.add_argument('--evidence',type=Path,required=True);p.add_argument('--governorate-code');p.add_argument('--work',type=Path);p.add_argument('--availability',type=Path);a=p.parse_args();opened(a)if a.phase=='open'else sources(a)

