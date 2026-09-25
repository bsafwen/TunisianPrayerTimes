#!/usr/bin/env python3
"""Rebuild and optionally install the Nabeul delegation parent label correction."""
import argparse, hashlib, json, os, shutil, tempfile
from pathlib import Path
REPO = Path(__file__).resolve().parents[4]
CATALOG = REPO / "android-app/app/src/main/assets/neighborhoods.json"
BINARY = CATALOG.with_name("neighborhoods.bin")
PACKAGE = Path(__file__).resolve().parent
BACKUP_JSON = PACKAGE / "backups/before-neighborhoods.json"
BACKUP_BIN = PACKAGE / "backups/before-neighborhoods.bin"
CANDIDATE = PACKAGE / "candidate/neighborhoods.json"
RECEIPT = PACKAGE / "receipt.json"
EXPECTED_JSON = "d0bcd450fdb00f1328be3f8fb49872bf7d1718a6fa7b1a544b48bdf85cf9e977"
EXPECTED_BIN = "a34bdf230220f2a400aefc693f9f1055ad6d9ad59fd640f04c0418485a02d8cd"
IDS = ["osm:relation:7097006", "osm:relation:7096631", "osm:relation:7096632", "osm:relation:7097007"]
OLD = "معتمدية حمام الغزاز"
NEW = "معتمدية حمام الأغزاز"
def sha(data): return hashlib.sha256(data).hexdigest()
def semantic_changes(before, after, path=""):
    out=[]
    if type(before) is not type(after): return [(path,before,after)]
    if isinstance(before,dict):
        if before.keys()!=after.keys(): return [(path+".<keys>",sorted(before),sorted(after))]
        for k in before: out.extend(semantic_changes(before[k],after[k],f"{path}.{k}" if path else k))
    elif isinstance(before,list):
        if len(before)!=len(after): return [(path+".<length>",len(before),len(after))]
        for i,(a,b) in enumerate(zip(before,after)): out.extend(semantic_changes(a,b,f"{path}[{i}]"))
    elif before!=after: out.append((path,before,after))
    return out
def rowmap(doc): return {f["id"]:f for f in doc["features"]}
def atomic_replace(dst, data):
    fd,tmp=tempfile.mkstemp(prefix=dst.name+".",suffix=".tmp",dir=dst.parent)
    try:
        with os.fdopen(fd,"wb") as f: f.write(data); f.flush(); os.fsync(f.fileno())
        os.replace(tmp,dst)
    finally:
        if os.path.exists(tmp): os.unlink(tmp)
def main():
    ap=argparse.ArgumentParser(); ap.add_argument("--install",action="store_true",help="install candidate into live catalog after all pins pass")
    args=ap.parse_args()
    live=CATALOG.read_bytes(); live_bin=BINARY.read_bytes()
    if sha(live)!=EXPECTED_JSON: raise SystemExit(f"live JSON base mismatch: {sha(live)}")
    if sha(live_bin)!=EXPECTED_BIN: raise SystemExit(f"live BIN mismatch: {sha(live_bin)}")
    if sha(BACKUP_JSON.read_bytes())!=EXPECTED_JSON or sha(BACKUP_BIN.read_bytes())!=EXPECTED_BIN: raise SystemExit("byte-exact packaged backups do not match pinned base")
    before=json.loads(live.decode("utf-8")); edited=json.loads(live.decode("utf-8")); rows=rowmap(edited)
    if len(rows)!=len(edited["features"]): raise SystemExit("catalog feature IDs are not unique")
    for fid in IDS:
        f=rows.get(fid)
        if f is None: raise SystemExit("missing target feature: "+fid)
        if f.get("governorateId")!=350 or f.get("sourceId")!="osm" or f.get("parentName")!=OLD: raise SystemExit("target precondition mismatch: "+fid)
        f["parentName"]=NEW
    changes=semantic_changes(before,edited)
    expected=sorted((f"features[{next(i for i,x in enumerate(before['features']) if x['id']==fid)}].parentName",OLD,NEW) for fid in IDS)
    if sorted(changes)!=expected: raise SystemExit("semantic diff is not exactly four parentName updates: "+repr(changes))
    candidate=(json.dumps(edited,ensure_ascii=False,indent=2)+"\n").encode("utf-8")
    CANDIDATE.write_bytes(candidate)
    # Reparse staged output and enforce same semantic changes.
    staged=json.loads(CANDIDATE.read_text(encoding="utf-8"))
    if sorted(semantic_changes(before,staged))!=expected: raise SystemExit("candidate semantic verification failed")
    pre={fid:rows[fid].copy() for fid in IDS}
    for x in pre.values(): x["parentName"]=OLD
    receipt={"schemaVersion":1,"package":"nabeul-hammam-el-ghezaz-parent-labels-20260925","mode":"installed" if args.install else "staged","expectedBaseJsonSha256":EXPECTED_JSON,"expectedBaseBinSha256":EXPECTED_BIN,"candidateJsonSha256":sha(candidate),"targetIds":IDS,"oldParentName":OLD,"newParentName":NEW,"semanticChanges":expected,"untouchedChecks":["feature count/order/IDs","all geometry-derived feature fields including bbox, area, offset, length","sourceId","name and aliases/contextAliases","pickerGroupId","meteo delegationId","coordinates","conflicts, cells, source catalog metadata","neighborhoods.bin bytes"],"targetsBefore":pre,"targetsAfter":{fid:{k:v for k,v in staged["features"][next(i for i,x in enumerate(staged['features']) if x['id']==fid)].items() if k in ["id","name","parentName","sourceId","pickerGroupId","delegationId","lat","lng","bbox","areaKm2","offset","length","aliases","contextAliases"]} for fid in IDS}}
    if args.install:
        # Recheck live pins immediately before mutation; retain exact backups in package already verified above.
        if sha(CATALOG.read_bytes())!=EXPECTED_JSON or sha(BINARY.read_bytes())!=EXPECTED_BIN: raise SystemExit("live files changed during preflight; refusing install")
        atomic_replace(CATALOG,candidate)
        if sha(BINARY.read_bytes())!=EXPECTED_BIN: raise SystemExit("BIN unexpectedly changed")
        installed=json.loads(CATALOG.read_text(encoding="utf-8"))
        installed_changes=semantic_changes(before,installed)
        if sorted(installed_changes)!=expected: raise SystemExit("post-install semantic diff mismatch")
        receipt.update({"catalogJsonAfterSha256":sha(CATALOG.read_bytes()),"catalogBinAfterSha256":sha(BINARY.read_bytes()),"installed":True})
        RECEIPT.write_text(json.dumps(receipt,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    else:
        receipt.update({"catalogJsonAfterSha256":None,"catalogBinAfterSha256":None,"installed":False})
        (PACKAGE/"candidate/validation.json").write_text(json.dumps(receipt,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({"installed":args.install,"candidateSha256":sha(candidate),"semanticChanges":len(expected),"targetIds":IDS},ensure_ascii=False))
if __name__=="__main__": main()
