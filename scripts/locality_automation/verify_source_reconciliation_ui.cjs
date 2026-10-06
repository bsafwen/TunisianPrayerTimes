// Offline functional tests only. No browser, renderer, requests or API keys.
const fs=require('fs'),vm=require('vm'),assert=require('assert'),crypto=require('crypto');
const [input,expected,output]=process.argv.slice(2);
if(!input||!expected||!output)throw Error('HTML, SHA256 and fresh proof output required');
const raw=fs.readFileSync(input),digest=crypto.createHash('sha256').update(raw).digest('hex');
assert.equal(digest,expected);assert(!fs.existsSync(output));
const html=raw.toString('utf8');assert(!/<script[^>]+src=/i.test(html));
const script=html.match(/<script>([\s\S]*?)<\/script>/)[1];
const nodes=new Map(),checks=[],buttons=[];
function node(id){return {id,children:[],attrs:{},dataset:{},listeners:{},checked:true,textContent:'',innerHTML:'',
 classList:{active:false,toggle(name,on){this.active=on}},append(n){this.children.push(n);if(n.tag==='button')buttons.push(n)},
 replaceChildren(){this.children=[]},setAttribute(k,v){this.attrs[k]=String(v)},
 addEventListener(k,fn){this.listeners[k]=fn},setPointerCapture(){},
 createSVGPoint(){return {x:0,y:0,matrixTransform(){return {x:this.x,y:this.y}}}},getScreenCTM(){return {inverse(){return {}}}}};}
for(const kind of ['source_only_face','current_installed_body','candidate_gain_unaccepted','candidate_loss_unaccepted']){
 const n=node(kind);n.dataset.kind=kind;checks.push(n);
}
const document={getElementById(id){if(!nodes.has(id))nodes.set(id,node(id));return nodes.get(id)},
 createElement(tag){const n=node('new');n.tag=tag;return n},createElementNS(ns,tag){const n=node('svg-new');n.tag=tag;return n},
 querySelectorAll(selector){if(selector==='.place')return buttons;if(selector==='[data-kind]')return checks;if(selector==='[data-kind]:checked')return checks.filter(n=>n.checked);throw Error('Unexpected selector '+selector)}};
const context={document,console,Math,JSON,Set,Map};vm.createContext(context);
vm.runInContext(script+'\nglobalThis.qa={DATA,select,paths,coords};',context,{timeout:10000});
const {DATA,select}=context.qa;assert.equal(buttons.length,DATA.rows.length);assert.equal(DATA.rows.length,8);
const svg=document.getElementById('map');let pathCount=0,coordinatePairs=0;
for(const row of DATA.rows){
 select(row.officialCode);assert.equal(buttons.filter(b=>b.classList.active).length,1);
 assert(document.getElementById('title').textContent.includes(row.name));
 const own=DATA.features.filter(f=>f.properties.officialCode===row.officialCode);
 assert.equal(own.length,4);assert.equal(svg.children.length,own.filter(f=>f.geometry.coordinates.length).length);
 for(const feature of own){
  assert.equal(feature.properties.geographicCredit,0);assert.equal(feature.properties.acceptedCurrentValidation,false);
  assert(['Polygon','MultiPolygon'].includes(feature.geometry.type));
  const d=context.qa.paths(feature.geometry),pairs=Array.from(d.matchAll(/[ML]([-+0-9.eE]+),([-+0-9.eE]+)/g),m=>[Number(m[1]),-Number(m[2])]);
  const points=context.qa.coords(feature.geometry);assert.equal(pairs.length,points.length);
  points.forEach((p,i)=>{assert.equal(pairs[i][0],p[0]);assert.equal(pairs[i][1],p[1]);coordinatePairs++});pathCount++;
 }
 const before=svg.attrs.viewBox.split(' ').map(Number);document.getElementById('plus').onclick();
 const after=svg.attrs.viewBox.split(' ').map(Number);assert(Math.abs(after[2]-before[2]*.75)<1e-12);
 document.getElementById('fit').onclick();assert.equal(svg.attrs.viewBox,before.join(' '));
 for(const checkbox of checks){checkbox.checked=false;checkbox.listeners.change();assert.equal(svg.children.length,3);checkbox.checked=true;checkbox.listeners.change();assert.equal(svg.children.length,4)}
}
// A missing or stale source/feature cannot gain credit from a view control.
assert(DATA.rows.every(r=>r.newCredit===0&&r.geographicValidation===false));
assert.equal(crypto.createHash('sha256').update(fs.readFileSync(input)).digest('hex'),expected);
const result={status:'PASS_OFFLINE_COMPARISON_COORDINATES_SELECTION_ZOOM_AND_LAYERS',inputSha256:expected,
 locations:DATA.rows.length,pathCount,coordinatePairs,payloadPid:process.pid,
 geographicCredit:0,browserRenderingVerified:false,networkRequestsPerformed:false,privateKeyRead:false};
fs.writeFileSync(output,JSON.stringify(result,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify(result));
