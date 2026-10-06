#!/usr/bin/env python3
"""Install the reviewed seven-row Mahdia display-parent delta with pinned rollback."""
from __future__ import annotations
import copy, hashlib, json, os, re, tempfile
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
ASSETS = ROOT / "android-app/app/src/main/assets"
CATALOG = ASSETS / "neighborhoods.json"
PACKED_BIN = ASSETS / "neighborhoods.bin"
PACKAGE = Path(__file__).resolve().parent
BACKUPS = PACKAGE / "backups"
RECEIPT = PACKAGE / "install-receipt.json"
IMPACT = PACKAGE / "impact.md"
BASE_JSON_SHA256 = "8e065ceec567aaa943d3ea30dc00ab46758c56e3b4856970496afd46c364cb98"
BASE_BIN_SHA256 = "a34bdf230220f2a400aefc693f9f1055ad6d9ad59fd640f04c0418485a02d8cd"
TARGETS = {
    "osm:relation:7152268": ("معتمدية رجيش", "معتمدية المهدية"),
    "osm:relation:7152267": ("معتمدية رجيش", "معتمدية المهدية"),
    "osm:relation:7152197": ("معتمدية الشابة", "معتمدية قصور الساف"),
    "osm:relation:7152185": ("معتمدية البرادعة", "معتمدية قصور الساف"),
    "osm:relation:7152182": ("معتمدية البرادعة", "معتمدية قصور الساف"),
    "osm:relation:7152200": ("معتمدية البرادعة", "معتمدية قصور الساف"),
    "osm:relation:7152199": ("معتمدية البرادعة", "معتمدية قصور الساف"),
}

def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()

def file_sha(path: Path) -> str:
    return sha(path.read_bytes())

def spans(text: str, features: list[dict]) -> dict[str, tuple[int,int]]:
    p=text.find('"features"')
    if p<0: raise SystemExit("Missing features")
    p=text.find("[",p)+1; dec=json.JSONDecoder(); out={}
    for feat in features:
        while p<len(text) and text[p].isspace(): p+=1
        start=p; obj,end=dec.raw_decode(text,p); fid=obj.get("id")
        if fid!=feat.get("id") or fid in out: raise SystemExit(f"Feature order/id mismatch at {fid}")
        out[fid]=(start,end); p=end
        while p<len(text) and text[p].isspace(): p+=1
        if p<len(text) and text[p]==",": p+=1
    return out

def main() -> None:
    if file_sha(CATALOG)!=BASE_JSON_SHA256 or file_sha(PACKED_BIN)!=BASE_BIN_SHA256:
        raise SystemExit("Live JSON/BIN differs from pinned base; abort and rebase")
    src_bytes=CATALOG.read_bytes(); text=src_bytes.decode("utf-8"); src=json.loads(text)
    rows={x["id"]:x for x in src["features"]}
    if len(rows)!=len(src["features"]): raise SystemExit("Duplicate catalog feature IDs")
    for fid,(old,new) in TARGETS.items():
        row=rows.get(fid)
        if not row or row.get("kind")!="sector" or row.get("parentName")!=old:
            raise SystemExit(f"Unexpected target precondition: {fid}")
    locations=spans(text,src["features"]); edits=[]
    for fid,(old,new) in TARGETS.items():
        start,end=locations[fid]; raw=text[start:end]
        pat=re.compile(r'("parentName"\s*:\s*)'+re.escape(json.dumps(old,ensure_ascii=False)))
        changed,n=pat.subn(lambda m:m.group(1)+json.dumps(new,ensure_ascii=False),raw)
        if n!=1: raise SystemExit(f"Expected one parentName token in {fid}, found {n}")
        edits.append((start,end,changed))
    out=text
    for start,end,repl in sorted(edits,reverse=True): out=out[:start]+repl+out[end:]
    next_doc=json.loads(out); expected=copy.deepcopy(src)
    for fid,(_,new) in TARGETS.items(): next(x for x in expected["features"] if x["id"]==fid)["parentName"]=new
    if next_doc!=expected: raise SystemExit("Semantic diff exceeds seven parentName fields")
    if sha(src_bytes)==sha(out.encode("utf-8")): raise SystemExit("No content change")
    BACKUPS.mkdir(parents=True,exist_ok=True)
    backup_json=BACKUPS/"neighborhoods.json.before"; backup_bin=BACKUPS/"neighborhoods.bin.before"
    if backup_json.exists() or backup_bin.exists(): raise SystemExit("Refusing to overwrite an existing byte-exact backup")
    backup_json.write_bytes(src_bytes); backup_bin.write_bytes(PACKED_BIN.read_bytes())
    if file_sha(backup_json)!=BASE_JSON_SHA256 or file_sha(backup_bin)!=BASE_BIN_SHA256: raise SystemExit("Backup hash mismatch")
    receipt={
      "schemaVersion":1,"status":"installed","date":date.today().isoformat(),
      "scope":"Seven Mahdia parentName fields only; dated Ministry snapshot display-parent alignment, with cross-source parent conflicts retained as caveats.",
      "base":{"catalogPath":str(CATALOG),"catalogSha256":BASE_JSON_SHA256,"binPath":str(PACKED_BIN),"binSha256":BASE_BIN_SHA256},
      "after":{"catalogSha256":sha(out.encode("utf-8")),"binSha256":file_sha(PACKED_BIN)},
      "backups":{"catalogPath":str(backup_json),"catalogSha256":file_sha(backup_json),"binPath":str(backup_bin),"binSha256":file_sha(backup_bin)},
      "counts":{"targetRows":7,"parentNameFieldsChanged":7,"otherFeatureFieldsChanged":0,"geometryOrBoundaryChanges":0,"idsChanged":0,"delegationIdChanges":0,"pickerGroupIdChanges":0,"contextAliasesChanges":0,"packedBinChanged":False},
      "changes":[{"featureId":fid,"name":rows[fid]["name"],"parentNameBefore":old,"parentNameAfter":new,"delegationIdPreserved":rows[fid]["delegationId"],"pickerGroupIdPreserved":rows[fid]["pickerGroupId"],"contextAliasesPreserved":True,"coordinatesPreserved":True,"boundaryDataPreserved":True} for fid,(old,new) in TARGETS.items()],
      "checks":{"baseHashesVerified":True,"exactSevenFieldSemanticDiff":True,"byteExactBackupsVerified":True,"liveBaseRecheckedImmediatelyBeforeReplace":True,"binUnchanged":True,"prayerSourcesChanged":False}}
    impact=["# Mahdia display-parent update", "", "Installed exactly seven `parentName` fields from the staged Ministry-snapshot proposal. Current INS/app parent evidence conflicts remain documented; this is a display-context choice, not a boundary finding.", "", "| Locality | Feature ID | Before | After | Delegation ID preserved | Picker group preserved |", "|---|---|---|---:|---|"]
    for c in receipt["changes"]: impact.append(f"| {c['name']} | `{c['featureId']}` | {c['parentNameBefore']} | {c['parentNameAfter']} | {c['delegationIdPreserved']} | `{c['pickerGroupIdPreserved']}` |")
    impact += ["", "`contextAliases`, `pickerGroupId`, IDs, coordinates, boundary payload, and independent meteo `delegationId` are unchanged. The packed BIN is unchanged. Byte-exact pre-install catalog and BIN backups are in `backups/`.", "", f"Catalog before SHA-256: `{BASE_JSON_SHA256}`", f"Catalog after SHA-256: `{receipt['after']['catalogSha256']}`", f"BIN SHA-256 (unchanged): `{BASE_BIN_SHA256}`", ""]
    impact_text="\n".join(impact); receipt["impactPath"]=str(IMPACT); receipt["impactSha256"]=sha(impact_text.encode())
    if file_sha(CATALOG)!=BASE_JSON_SHA256 or file_sha(PACKED_BIN)!=BASE_BIN_SHA256: raise SystemExit("Live base drifted before replacement; abort")
    fd,tmp=tempfile.mkstemp(prefix="neighborhoods.json.",suffix=".tmp",dir=ASSETS)
    try:
        with os.fdopen(fd,"wb") as f: f.write(out.encode("utf-8")); f.flush(); os.fsync(f.fileno())
        os.replace(tmp,CATALOG)
    except Exception:
        Path(tmp).unlink(missing_ok=True); raise
    if file_sha(CATALOG)!=receipt["after"]["catalogSha256"] or file_sha(PACKED_BIN)!=BASE_BIN_SHA256: raise SystemExit("Post-install verification failed")
    IMPACT.write_text(impact_text,encoding="utf-8"); RECEIPT.write_text(json.dumps(receipt,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({"status":"installed","catalogBefore":BASE_JSON_SHA256,"catalogAfter":receipt["after"]["catalogSha256"],"binUnchanged":BASE_BIN_SHA256,"backupJson":str(backup_json),"backupBin":str(backup_bin),"rows":7},ensure_ascii=True,indent=2))

if __name__=="__main__": main()
