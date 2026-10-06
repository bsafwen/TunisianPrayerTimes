"""Freeze and bind the current catalogue loader to an existing minimal JVM audit.

Preparation emits a direct compiler command; sealing requires its real guarded
receipt. Historical classes and evidence are never rewritten.
"""
import argparse,copy,json,sys
from pathlib import Path
R=Path(__file__).resolve().parents[2];sys.path.insert(0,str(R))
from scripts.locality_automation.run_sealed_boundary_queue import read,pin,checked,active_control
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree
def put(p,v):
    with p.open('x',encoding='utf-8')as f:json.dump(v,f,ensure_ascii=False,indent=2);f.write('\n')
    return pin(p)
def main():
    p=argparse.ArgumentParser();p.add_argument('--work',type=Path,required=True);p.add_argument('--phase',choices=['prepare','seal'],required=True);a=p.parse_args();w=a.work.resolve();c=read(w.parent/'control.json');s={k:c[k]for k in ['iteration','windowStartUtc','deadlineUtc']};s.update(control=str(w.parent/'control.json'),owner='/root');active_control(s)
    old_path=w.parent/'root-cycle22/sfax-minimal-jvm-v3/after/jvm-input-binding.json';old=read(old_path);out=w/'current-loader-jvm-v3'
    if a.phase=='prepare':
        out.mkdir();sources=[]
        for b in old['currentCoreBindings']:
            current=Path(b['current']['file']);snapshot=checked(b['snapshot']);sources.append({'current':pin(current),'oldSnapshot':pin(snapshot),'changed':current.read_bytes()!=snapshot.read_bytes()})
        changed=[b for b in sources if b['changed']];assert {Path(b['current']['file']).name for b in changed}=={'LocalityRepository.kt','NeighborhoodRepository.kt'},'Unexpected changed core set'
        source_bindings=[]
        for b in changed:
            src=out/Path(b['current']['file']).name;src.write_bytes(checked(b['current']).read_bytes());source_bindings.append({'snapshot':pin(src),'current':b['current']})
        dependency=R/'android-app/app/src/main/java/com/tunisianprayertimes/LocalityDisplayNames.kt'
        src=out/dependency.name;src.write_bytes(dependency.read_bytes());source_bindings.append({'snapshot':pin(src),'current':pin(dependency)})
        classes=out/'classes';classes.mkdir()
        cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
        jars=[]
        for group,name in [('org.jetbrains.kotlin','kotlin-compiler-embeddable'),('org.jetbrains.kotlin','kotlin-stdlib'),('org.jetbrains.kotlin','kotlin-script-runtime'),('org.jetbrains.kotlin','kotlin-reflect'),('org.jetbrains.intellij.deps','trove4j'),('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm'),('org.jetbrains','annotations')]:
            jars+=[j for j in (cache/group/name).rglob('*.jar')if not any(s in j.name for s in ['-sources','-javadoc','common-sources'])]
        assert any('kotlin-compiler-embeddable-2.2.10.jar'==j.name for j in jars)
        # Prefer the compiler's installed version when duplicate library versions exist.
        compiler_cp=';'.join(str(j)for j in sorted(jars,key=lambda j:('2.2.10'not in str(j),str(j))))
        cp=old['argv'][2]
        argv=[old['argv'][0],'-cp',compiler_cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(classes)]+[b['snapshot']['file']for b in source_bindings]
        harness=out/'ParallelGeometryPrayer.java';body=checked(old['sourceHarness']).read_text(encoding='utf-8');assert 'NeighborhoodGeometryActualKt.parseNeighborhoodLocalities(json)'in body
        harness.write_text(body.replace('NeighborhoodGeometryActualKt.parseNeighborhoodLocalities(json)','NeighborhoodRepositoryKt.parseNeighborhoodLocalities(json)').replace('retained sealed109cLocality model','freshly compiled current Locality and NeighborhoodRepository models'),encoding='utf-8')
        put(out/'compile-plan.json',{'argv':argv,'sourceBindings':source_bindings,'sourceHarness':pin(harness),'originalHarness':old['sourceHarness'],'compilerDependencies':[pin(j)for j in jars],'priorBinding':pin(old_path),'unchangedCoreBindings':[b for b in sources if not b['changed']]})
        print(json.dumps({'preparedCurrentLoader':True,'compilePlan':str(out/'compile-plan.json')}));return
    plan=read(out/'compile-plan.json');check_tree(plan);ex=read(w/'current-loader-compile-v3.execution.json');assert ex['status']=='COMPLETED'and ex['exitCode']==0 and ex['argv']==plan['argv'] and not ex['timedOut']
    for b in plan['sourceBindings']:assert checked(b['snapshot']).read_bytes()==checked(b['current']).read_bytes()
    binding=copy.deepcopy(old);binding['argv'][2]=str(out/'classes')+';'+old['argv'][2]
    binding['retainedClasses']=[pin(p)for p in (out/'classes').rglob('*.class')]+old['retainedClasses']
    assert len(binding['retainedClasses'])>len(old['retainedClasses'])
    fresh={Path(b['current']['file']).name:b for b in plan['sourceBindings']}
    for b in binding['currentCoreBindings']:
        if Path(b['current']['file']).name in fresh:b.update(fresh[Path(b['current']['file']).name])
        else:assert checked(b['snapshot']).read_bytes()==checked(b['current']).read_bytes()
    binding['currentCoreBindings'].append(fresh['LocalityDisplayNames.kt'])
    binding['sourceHarness']=plan['sourceHarness'];binding['argv'][3]=plan['sourceHarness']['file']
    binding.update(currentLoaderCompileExecution=pin(w/'current-loader-compile-v3.execution.json'),priorBinding=pin(old_path),status='PINNED_FRESH_CURRENT_LOADER_AND_INDEX_WITH_UNCHANGED_PREFS')
    put(out/'jvm-input-binding.json',binding);print(json.dumps({'sealedFreshLoader':True,'oldEvidenceUnchanged':True}))
if __name__=='__main__':main()
