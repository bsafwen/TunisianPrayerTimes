"""Prepare finite map export/UI checks from actual pinned publication, offline.

Reuses the established UI sandbox and the exact map application JavaScript.
No browser, renderer, network, or private Google configuration is read.
"""
import argparse
import ast
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts.locality_automation.run_sealed_boundary_queue import checked, pin, read


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    args = parser.parse_args()
    spec = read(args.manifest)
    output = Path(spec["outputDirectory"])
    if not output.is_dir() or any(output.iterdir()):
        raise ValueError("Existing empty audit directory required")
    report = read(checked(spec["report"]))
    model_path = checked(spec["map"])
    model = read(model_path)
    baseline = read(checked(spec["baselineReport"]))
    codes = sorted(set(report["summary"]["validatedLocationCodes"]) - set(baseline["summary"]["validatedLocationCodes"]))
    if not codes or not set(codes) <= set(spec["allowedNewCodes"]):
        raise ValueError("New map audit code set is outside finite approval")
    rows = []
    for code in codes:
        batches = [b for b in model["batches"] if code in b["newLocationCodes"]]
        if len(batches) != 1:
            raise ValueError("New code is not first-accepted exactly once")
        batch = batches[0]
        row = next(r for r in batch["locations"] if r["code"] == code)
        if row["scope"] != "full" or not row["newLocation"] or batch["geometryAction"] != "unchanged_geometry_validation":
            raise ValueError("New accepted scope/action differs")
        rows.append({"code": code, "batchId": batch["id"]})
    (output / "boundary-map.json").write_bytes(model_path.read_bytes())
    tree = ast.parse(checked(spec["uiSource"]).read_text(encoding="utf-8-sig"))
    app_js = next(ast.literal_eval(node.value) for node in tree.body
                  if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == "MAP_JS" for t in node.targets))
    (output / "boundary-map.syntax.js").write_text(app_js, encoding="utf-8")
    harness = checked(spec["uiHarness"]).read_text(encoding="utf-8-sig")
    harness = harness[:harness.index("assert.match(context.window.__rosterQA")]
    harness = harness.replace("{rosterBadge,rosterNote,identityNote,popup,reviewPopup,reviewDetails,resolvedReviewDetails}",
        "{rosterBadge,rosterNote,identityNote,popup,reviewPopup,reviewDetails,resolvedReviewDetails,latestAdditionRows,latestAdditionCodes}")
    target = {"rows": rows, "totalCount": model["uniqueLocationCount"]}
    script = harness + "\nconst target=" + json.dumps(target) + ";\n" + r'''
assert.equal(element('map-batch').value,'view:latest-additions');
const latest=context.window.__rosterQA.latestAdditionRows;
assert.equal((element('map-location-list').innerHTML.match(/>NEW</g)||[]).length,latest.length);
(async()=>{
 for(const entry of target.rows){
  assert(context.window.__rosterQA.latestAdditionCodes.has(entry.code));
  element('map-batch').value=entry.batchId;element('map-batch').onchange();
  assert(element('map-location-list').innerHTML.includes('NEW'));
  assert(element('map-location-list').innerHTML.includes('data-map-code="'+entry.code+'"'));
  element('map-export').onclick();const txt=await downloads.pop().text(),geo=JSON.parse(txt);
  assert.equal(geo.features.length,1);assert.equal(geo.features[0].properties.code,entry.code);
  assert.equal(geo.features[0].properties.scope,'full');assert.equal(geo.features[0].properties.geometryRewritten,false);
  fs.writeFileSync(path.join(root,entry.code+'.geojson'),txt);
  element('map-kml').onclick();fs.writeFileSync(path.join(root,entry.code+'.kml'),await downloads.pop().text());
 }
 element('map-batch').value='all';element('map-batch').onchange();
 assert.equal((element('map-location-list').innerHTML.match(/data-map-code=/g)||[]).length,target.totalCount);
 element('map-search').value=target.rows[0].code;element('map-search').oninput();
 assert.equal((element('map-location-list').innerHTML.match(/data-map-code=/g)||[]).length,1);
 element('map-pin').checked=true;mainMap.events.click({latlng:{lat:35.5,lng:9.7}});
 element('map-issue-note').value='QA fixture';element('map-save-issue').onclick();element('map-issues-export').onclick();
 const issues=JSON.parse(await downloads.pop().text());assert.equal(issues.issues.length,1);assert.equal(issues.issues[0].viewId,'all');
 const result={status:'PASS_FINITE_NEW_MAP_UI_EXPORT_FILTER_ISSUES',payloadPid:process.pid,
  newCodes:target.rows.map(r=>r.code),latestAdditionCount:latest.length,totalCount:target.totalCount,
  filtersAndIssueExportPassed:true,privateKeyRead:false,networkRequestsPerformed:false,actualBrowserAuthOrRenderingVerified:false};
 fs.writeFileSync(path.join(root,'ui-result.json'),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
})().catch(e=>{console.error(e);process.exitCode=1});
'''
    (output / "verify-map.cjs").write_text(script, encoding="utf-8")
    print(json.dumps({"status": "PREPARED_FINITE_MAP_AUDIT", "codes": codes, "script": pin(output / "verify-map.cjs")}))


if __name__ == "__main__":
    main()
