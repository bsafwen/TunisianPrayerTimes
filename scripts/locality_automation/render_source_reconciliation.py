"""Build a compact offline visual review of exact source/current differences.

The accepted-boundary dashboard remains separate. This document contains only
unaccepted diagnostic faces, does not fetch tiles/API keys and adds no credit.
"""
import argparse
from datetime import datetime, timezone
import html
import json
from pathlib import Path
import sys

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))
from scripts.locality_automation.run_sealed_boundary_queue import checked, pin, read


TEMPLATE = r'''<!doctype html><html lang="en"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Pending boundary comparisons</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#f5f4ef;color:#1c3432;font:16px system-ui,sans-serif}
header{padding:20px 26px;background:#fff;border-bottom:1px solid #d5dfdc}h1{margin:0 0 6px;font-size:24px}
p{margin:8px 0}main{display:grid;grid-template-columns:300px 1fr;gap:16px;padding:18px;max-width:1600px;margin:auto}
aside,.panel{background:white;border:1px solid #d5dfdc;border-radius:12px;padding:16px}button,select{font:inherit;border:1px solid #a9bdb6;background:white;border-radius:6px;padding:8px;cursor:pointer}
#locations{display:grid;gap:6px}.place{text-align:start}.place.active{background:#daeae2;border-color:#4a7970}
.metric{display:flex;justify-content:space-between;gap:10px;padding:7px 0;border-bottom:1px solid #e6ece9}.muted{color:#63776f;font-size:13px}
.legend{display:flex;flex-wrap:wrap;gap:12px;align-items:center;margin:12px 0}.legend label{white-space:nowrap}
#map{width:100%;height:580px;background:#fafcf9;touch-action:none;border:1px solid #d8e1dc;cursor:grab}
.toolbar{display:flex;gap:8px;align-items:center;flex-wrap:wrap}.badge{background:#faead5;color:#7b4b10;border-radius:5px;padding:5px 8px;font-size:13px}
table{width:100%;border-collapse:collapse;font-size:14px}th,td{text-align:start;border-bottom:1px solid #e0e8e3;padding:7px}a{color:#24675d}
summary{cursor:pointer}img{width:100%;height:auto;border:1px solid #d8e1dc;margin-top:10px}
@media(max-width:850px){main{grid-template-columns:1fr}#locations{grid-template-columns:repeat(2,minmax(0,1fr))}#map{height:430px}}
</style><header><h1>Pending boundary comparisons</h1>
<p>Compare the ISIE source face with the installed boundary. <strong>These eight cases are pending; no new validation is recorded here.</strong></p>
<p class="muted">Offline coordinates · No basemap or Google authentication claim · Updated __STAMP__</p></header>
<main><aside><div id="locations"></div><div id="metrics"></div><p class="muted">Green and magenta show proposed changes. They have not been accepted or installed.</p></aside>
<section class="panel"><div class="toolbar"><strong id="title" dir="auto"></strong><span class="badge">Pending</span>
<button id="fit">Fit</button><button id="plus" aria-label="Zoom in">+</button><button id="minus" aria-label="Zoom out">−</button></div>
<div class="legend"><label><input type="checkbox" data-kind="source_only_face" checked> <span style="color:#1462bc">Blue: ISIE</span></label>
<label><input type="checkbox" data-kind="current_installed_body" checked> Black: installed</label>
<label><input type="checkbox" data-kind="candidate_gain_unaccepted" checked> <span style="color:#16824a">Green: proposed gain</span></label>
<label><input type="checkbox" data-kind="candidate_loss_unaccepted" checked> <span style="color:#b22e86">Magenta: proposed loss</span></label></div>
<svg id="map" role="img" aria-label="Unaccepted source and current boundary comparison"></svg>
<p id="coordinates" class="muted">Move over the map for coordinates. Drag to pan; use + and − to zoom.</p>
<h3>Neighbors touched by proposed gains</h3><table><thead><tr><th>Location</th><th>Area (km²)</th></tr></thead><tbody id="neighbors"></tbody></table>
<p class="muted">A touched neighbor is a reconciliation lead. Overlap does not establish ownership.</p>
<details id="pdf"><summary>Original ISIE page</summary><div id="pdfcontent"></div></details>
<details><summary>Source and registration limits</summary><p>Electoral faces do not certify present-day civil administrative extent. Cross-sheet intersections may reflect separate registrations. Existing source, neighbor, topology, GPS and acceptance gates remain required.</p></details>
</section></main><script>
'use strict';const DATA=__DATA__;
const ns='http://www.w3.org/2000/svg',svg=document.getElementById('map');
const colors={source_only_face:'#1462bc',current_installed_body:'#172523',candidate_gain_unaccepted:'#20a45d',candidate_loss_unaccepted:'#c33b95'};
let active=DATA.rows[0].officialCode,view=null,initial=null,drag=null;
const el=(name,attributes)=>{const n=document.createElementNS(ns,name);for(const[k,v]of Object.entries(attributes))n.setAttribute(k,v);return n};
const escapeText=value=>String(value).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
function coords(g){return g.coordinates.flat(g.type==='MultiPolygon'?2:1)}
function paths(g){if(!g.coordinates.length)return '';const polys=g.type==='MultiPolygon'?g.coordinates:[g.coordinates];return polys.map(p=>p.map(r=>r.map((q,i)=>(i?'L':'M')+q[0]+','+(-q[1])).join(' ')+'Z').join(' ')).join(' ')}
function redraw(){svg.replaceChildren();svg.setAttribute('viewBox',view.join(' '));
 const enabled=new Set([...document.querySelectorAll('[data-kind]:checked')].map(x=>x.dataset.kind));
 for(const f of DATA.features.filter(f=>f.properties.officialCode===active)){
  const k=f.properties.kind;if(!enabled.has(k))continue;const d=paths(f.geometry);if(!d)continue;
  const difference=k.startsWith('candidate_');svg.append(el('path',{d,fill:difference?colors[k]:'none','fill-opacity':difference?'.45':'1',stroke:colors[k],'stroke-width':difference?'0.5':k==='source_only_face'?'2.5':'1.5','vector-effect':'non-scaling-stroke','fill-rule':'evenodd'}));
 }}
function select(code){active=code;const row=DATA.rows.find(r=>r.officialCode===code),points=DATA.features.filter(f=>f.properties.officialCode===code&&!f.properties.kind.startsWith('candidate_')).flatMap(f=>coords(f.geometry));
 const xs=points.map(p=>p[0]),ys=points.map(p=>-p[1]),x0=Math.min(...xs),y0=Math.min(...ys),w=Math.max(...xs)-x0,h=Math.max(...ys)-y0;
 initial=[x0-w*.06,y0-h*.06,w*1.12,h*1.12];view=initial.slice();
 document.getElementById('title').textContent=row.name+' · '+code;document.querySelectorAll('.place').forEach(b=>b.classList.toggle('active',b.dataset.code===code));
 const metrics=[['Source/current overlap', (row.metrics.intersectionOverUnion*100).toFixed(4)+'%'],['Proposed gain',(row.metrics.gainedSquareMeters/1e6).toFixed(6)+' km²'],['Proposed loss',(row.metrics.lostSquareMeters/1e6).toFixed(6)+' km²'],['Unique gain touching neighbors',(row.uniqueGainedAreaIntersectingAnyCurrentNeighborSquareMeters/1e6).toFixed(6)+' km²'],['Boundary separation',row.metrics.boundaryHausdorffMeters.toFixed(3)+' m']];
 document.getElementById('metrics').innerHTML=metrics.map(([k,v])=>'<div class="metric"><span>'+escapeText(k)+'</span><strong>'+escapeText(v)+'</strong></div>').join('');
 document.getElementById('neighbors').innerHTML=row.currentNeighborIntersections.filter(n=>n.gainedAreaIntersectingNeighborSquareMeters>1).map(n=>'<tr><td dir="auto">'+escapeText(n.name)+'</td><td>'+(n.gainedAreaIntersectingNeighborSquareMeters/1e6).toFixed(6)+'</td></tr>').join('');
 const pdf=document.getElementById('pdfcontent');pdf.replaceChildren();if(row.originalPageUri){const im=document.createElement('img');im.src=row.originalPageUri;im.alt='Original ISIE page for '+row.name;pdf.append(im)}
 const link=document.createElement('a');link.href=row.sourcePdfUri;link.textContent='Open original PDF';pdf.append(link);document.getElementById('pdf').open=false;redraw();
}
for(const row of DATA.rows){const b=document.createElement('button');b.className='place';b.dataset.code=row.officialCode;b.dir='auto';b.textContent=row.name+' · '+row.officialCode;b.addEventListener('click',()=>select(row.officialCode));document.getElementById('locations').append(b)}
document.querySelectorAll('[data-kind]').forEach(x=>x.addEventListener('change',redraw));
function zoom(scale){view=[view[0]+view[2]*(1-scale)/2,view[1]+view[3]*(1-scale)/2,view[2]*scale,view[3]*scale];redraw()}
document.getElementById('plus').onclick=()=>zoom(.75);document.getElementById('minus').onclick=()=>zoom(4/3);document.getElementById('fit').onclick=()=>{view=initial.slice();redraw()};
function position(event){const p=svg.createSVGPoint();p.x=event.clientX;p.y=event.clientY;return p.matrixTransform(svg.getScreenCTM().inverse())}
svg.addEventListener('pointerdown',e=>{const p=position(e);drag={x:p.x,y:p.y,view:view.slice()};svg.setPointerCapture(e.pointerId)});
svg.addEventListener('pointermove',e=>{const p=position(e);document.getElementById('coordinates').textContent='Latitude '+(-p.y).toFixed(6)+' · Longitude '+p.x.toFixed(6);if(drag){view=[drag.view[0]+drag.x-p.x,drag.view[1]+drag.y-p.y,view[2],view[3]];redraw();drag.view=view.slice()}});
svg.addEventListener('pointerup',()=>drag=null);svg.addEventListener('pointercancel',()=>drag=null);select(active);
</script></html>'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    spec = read(args.manifest)
    report, geojson = read(checked(spec["report"])), read(checked(spec["geojson"]))
    if report["newGeographicCredit"] != 0 or report["sourceScopeAccepted"] is not False:
        raise ValueError("Only unaccepted diagnostic comparisons are supported")
    codes = [row["officialCode"] for row in report["rows"]]
    if len(codes) != len(set(codes)) or set(codes) != set(spec["codes"]):
        raise ValueError("Finite visual review codes differ")
    features = geojson["features"]
    if len(features) != len(codes) * 4 or any(f["properties"]["geographicCredit"] != 0
        or f["properties"]["acceptedCurrentValidation"] is not False for f in features):
        raise ValueError("Wrong diagnostic feature count/credit")
    page_by_code = {row["officialCode"]: row["page"] for row in spec["originalPages"]}
    for row in report["rows"]:
        row["sourcePdfUri"] = checked(row["sourcePdf"]).resolve().as_uri()
        if row["officialCode"] in page_by_code:
            row["originalPageUri"] = checked(page_by_code[row["officialCode"]]).resolve().as_uri()
    # Prevent embedded source strings from ending the script element.
    data = json.dumps(dict(rows=report["rows"], features=features), ensure_ascii=False,
                      allow_nan=False, separators=(",", ":")).replace("<", "\\u003c")
    output = TEMPLATE.replace("__STAMP__", html.escape(datetime.now(timezone.utc).isoformat())).replace("__DATA__", data)
    with args.output.open("x", encoding="utf-8", newline="\n") as stream:
        stream.write(output)
    print(json.dumps(dict(status="OFFLINE_UNACCEPTED_VISUAL_REVIEW_CREATED", output=pin(args.output),
                          codes=codes, geographicCredit=0)))


if __name__ == "__main__":
    main()
