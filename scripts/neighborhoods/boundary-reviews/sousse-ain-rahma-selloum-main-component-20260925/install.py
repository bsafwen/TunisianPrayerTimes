#!/usr/bin/env python3
"""Rebuild, validate, and optionally install the provisional Ain Rahma clip."""
from __future__ import annotations

from collections import defaultdict
import hashlib
import json
import math
import os
from pathlib import Path
import struct
import sys
from datetime import datetime, timezone

from shapely import set_precision
from shapely.geometry import Point, Polygon, MultiPolygon, shape
from shapely.ops import transform, unary_union

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
ASSETS = REPO / "android-app/app/src/main/assets"
CATALOG = ASSETS / "neighborhoods.json"
PACKED = ASSETS / "neighborhoods.bin"
TASK = Path(r"C:\Users\barou\Documents\Codex\2026-09-06\the-android-app-app-currently-allows")
EVIDENCE = TASK / "work/official-imada-evidence-20260924-v2/01-sousse/boundary-risk/selloum-ain-rahma"
AIN_META = TASK / "work/hammamet-frontier11-official-maps-20260919/ain-rahma-metadata.json"
AIN_PDF = TASK / "work/hammamet-frontier11-official-maps-20260919/ain-rahma.pdf"
SOURCE_ID = "derived-sousse-ain-rahma-minus-selloum-main-component-20260925"
AIN_ID = "osm:relation:7105253"
SELL_ID = "osm:relation:7105249"
BASE_HASHES = {
    "neighborhoods.json": "e3ff4c5543b023b8846d61dcf3031b0e4504598b9227a02a50a441ca4d8339e4",
    "neighborhoods.bin": "a8745844d40d2314c3cd14bb4ca295593d1263ad84c13da8e521dc361211e45b",
}
SOURCE_HASHES = {
    "review.json": "19174435e6da199789228bd73cc1af839b6b60c4abaa4c63e49a884f11c2f929",
    "followup-20260925.json": "73a79aaee471ec4e2b9ce7c8e8de2c2b248c48ffb965b28de07e86605fda76fc",
    "ain-rahma-metadata.json": "4db735e2d6604f6612ff2796b7a2be7ad880b644ffc88d76bb0aabd655dd64a3",
    "ain-rahma.pdf": "18dc3ead58d3ea1ba96c9c30287fc944872df80a81560d8f3497b41ad1d3283c",
    "generate_neighborhoods.py": "ae3cde00bb6929cf19254bd42f66d77c1decadb370a46148983e4a3752f67d56",
    "prayer-source-coordinates.json": "3b1ea927202e5674324271c1f45db0813167377d80aaf517619916e1bb89cf3d",
}
STAGED_JSON = HERE / "candidate-neighborhoods.json"
STAGED_BIN = HERE / "candidate-neighborhoods.bin"
REPORT = HERE / "validation.json"
BACKUP_JSON = HERE / "before-neighborhoods.json"
BACKUP_BIN = HERE / "before-neighborhoods.bin"
RECEIPT = HERE / "installed-receipt.json"

class UTM32N:
    """WGS84 / EPSG:32632 forward and inverse transverse Mercator series."""
    a=6378137.0
    f=1/298.257223563
    e2=f*(2-f)
    ep2=e2/(1-e2)
    k0=0.9996
    lon0=math.radians(9.0)
    def forward(self, lon, lat, z=None):
        if hasattr(lon, "__iter__"):
            pts=[self.forward(x,y) for x,y in zip(lon,lat)]
            return ([p[0] for p in pts],[p[1] for p in pts]) if z is None else ([p[0] for p in pts],[p[1] for p in pts],z)
        lam,phi=math.radians(lon),math.radians(lat)
        sp,cp=math.sin(phi),math.cos(phi); tanp=math.tan(phi)
        n=self.a/math.sqrt(1-self.e2*sp*sp); t=tanp*tanp; c=self.ep2*cp*cp; aa=(lam-self.lon0)*cp
        e4=self.e2**2; e6=e4*self.e2
        m=self.a*((1-self.e2/4-3*e4/64-5*e6/256)*phi
          -(3*self.e2/8+3*e4/32+45*e6/1024)*math.sin(2*phi)
          +(15*e4/256+45*e6/1024)*math.sin(4*phi)-(35*e6/3072)*math.sin(6*phi))
        east=500000+self.k0*n*(aa+(1-t+c)*aa**3/6+(5-18*t+t*t+72*c-58*self.ep2)*aa**5/120)
        north=self.k0*(m+n*tanp*(aa*aa/2+(5-t+9*c+4*c*c)*aa**4/24+(61-58*t+t*t+600*c-330*self.ep2)*aa**6/720))
        return (east,north) if z is None else (east,north,z)
    def inverse(self, east, north, z=None):
        if hasattr(east, "__iter__"):
            pts=[self.inverse(x,y) for x,y in zip(east,north)]
            return ([p[0] for p in pts],[p[1] for p in pts]) if z is None else ([p[0] for p in pts],[p[1] for p in pts],z)
        x=east-500000; m=north/self.k0
        mu=m/(self.a*(1-self.e2/4-3*self.e2**2/64-5*self.e2**3/256))
        e1=(1-math.sqrt(1-self.e2))/(1+math.sqrt(1-self.e2))
        j1=3*e1/2-27*e1**3/32; j2=21*e1**2/16-55*e1**4/32; j3=151*e1**3/96; j4=1097*e1**4/512
        fp=mu+j1*math.sin(2*mu)+j2*math.sin(4*mu)+j3*math.sin(6*mu)+j4*math.sin(8*mu)
        sp,cp=math.sin(fp),math.cos(fp); t1=math.tan(fp)**2; c1=self.ep2*cp*cp
        n1=self.a/math.sqrt(1-self.e2*sp*sp); r1=self.a*(1-self.e2)/(1-self.e2*sp*sp)**1.5; d=x/(n1*self.k0)
        lat=fp-(n1*math.tan(fp)/r1)*(d*d/2-(5+3*t1+10*c1-4*c1*c1-9*self.ep2)*d**4/24+(61+90*t1+298*c1+45*t1*t1-252*self.ep2-3*c1*c1)*d**6/720)
        lon=self.lon0+(d-(1+2*t1+c1)*d**3/6+(5-2*c1+28*t1-3*c1*c1+8*self.ep2+24*t1*t1)*d**5/120)/cp
        return (math.degrees(lon),math.degrees(lat)) if z is None else (math.degrees(lon),math.degrees(lat),z)

def sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()

def jbytes(value):
    return (json.dumps(value,ensure_ascii=False,separators=(",",":"),allow_nan=False)+"\n").encode("utf-8")

def require(ok, text):
    if not ok: raise RuntimeError(text)

def decode(row, blob, scale):
    view=memoryview(blob)[row["offset"]:row["offset"]+row["length"]]; cur=0
    def integer():
        nonlocal cur
        val=struct.unpack_from(">i",view,cur)[0]; cur+=4; return val
    polys=[]
    for _ in range(integer()):
        rings=[]
        for _ in range(integer()): rings.append([(integer()/scale,integer()/scale) for _ in range(integer())])
        polys.append(Polygon(rings[0],rings[1:]))
    require(cur==len(view),f"packed slice mismatch: {row['id']}")
    return polys[0] if len(polys)==1 else MultiPolygon(polys)

def pair_map(records):
    return {tuple(sorted(row["ids"])):row for row in records}

def app_find(catalog,geometries,lat,lng):
    key=f"{math.floor(lat/catalog['gridSize'])}:{math.floor(lng/catalog['gridSize'])}"
    point=Point(lng,lat); found={}
    for i in catalog["cells"].get(key,[]):
        row=catalog["features"][i]
        if row.get("hasBoundary") and row["bbox"][0]<=lng<=row["bbox"][2] and row["bbox"][1]<=lat<=row["bbox"][3] and geometries[i].covers(point): found[row["id"]]=(i,row)
    ambiguous=set()
    for edge in catalog.get("conflicts",[]):
        a,b=edge["ids"]
        if a in found and b in found and geometries[found[a][0]].contains(point) and geometries[found[b][0]].contains(point): ambiguous.update((a,b))
    candidates=[row for key,(_,row) in found.items() if key not in ambiguous]
    return min(candidates,key=lambda row:(row["areaKm2"],row["id"])) if candidates else None

def canonical_identity(row):
    return {k:v for k,v in row.items() if k not in ("bbox","areaKm2","offset","length","sourceId")}

def compile_candidate():
    json_raw=CATALOG.read_bytes(); bin_raw=PACKED.read_bytes()
    require(sha(json_raw)==BASE_HASHES[CATALOG.name] and sha(bin_raw)==BASE_HASHES[PACKED.name],"Live input hashes changed")
    require(bin_raw[:8]==b"NPOL\x00\x00\x00\x01","Unsupported packed binary")
    source_files={"review.json":EVIDENCE/"review.json","followup-20260925.json":EVIDENCE/"followup-20260925.json",
        "ain-rahma-metadata.json":AIN_META,"ain-rahma.pdf":AIN_PDF,
        "generate_neighborhoods.py":REPO/"scripts/generate_neighborhoods.py",
        "prayer-source-coordinates.json":REPO/"scripts/neighborhoods/prayer-source-coordinates.json"}
    source_raw={name:path.read_bytes() for name,path in source_files.items()}
    for name,raw in source_raw.items(): require(sha(raw)==SOURCE_HASHES[name],f"Pinned source changed: {name}")
    review,followup,metadata=(json.loads(source_raw[name].decode("utf-8")) for name in ("review.json","followup-20260925.json","ain-rahma-metadata.json"))
    require(followup["scopePrerequisite"]["featureId"]==AIN_ID and followup["scopePrerequisite"]["officialCode"]=="316156" and followup["ringPrerequisite"]["status"]=="supported_by_manual_visual_review","Official scope/ring prerequisite changed")
    require(review["officialScope"]["ainRahma"]["candidateStatus"]=="RING_GEOMETRY_EVIDENCE_ONLY_NOT_APPROVED_REPLACEMENT","Evidence review status changed")
    ring=metadata["candidateRings"][0]; require(ring["drawingIndex"]==341 and ring["isValid"] and ring["explicitlyClosed"],"Official ring source changed")

    sys.path.insert(0,str(REPO/"scripts"))
    from generate_neighborhoods import GRID,SCALE,detect_conflicts,packed_geometry_bytes
    require(sha(source_raw["generate_neighborhoods.py"])==SOURCE_HASHES["generate_neighborhoods.py"],"Production generator changed")
    catalog=json.loads(json_raw.decode("utf-8-sig")); rows=catalog["features"]
    require(catalog["coordinateScale"]==SCALE and catalog["gridSize"]==GRID,"Catalog precision changed")
    by_id={row["id"]:(i,row) for i,row in enumerate(rows)}
    require(len(by_id)==len(rows) and AIN_ID in by_id and SELL_ID in by_id,"Target identity missing or duplicated")
    ain_i,ain=by_id[AIN_ID]; sell_i,sell=by_id[SELL_ID]
    original_ain_rep=(ain["lat"],ain["lng"])
    require(ain["sourceId"]=="osm" and ain["kind"]=="sector" and ain["name"]=="عين الرحمة" and ain["hasBoundary"],"Ain identity changed")
    require(sell["sourceId"]=="isie-local-sectors-2023-hammamet-nine-neighbours" and sell["kind"]=="sector" and sell["name"]=="السلوم" and sell["hasBoundary"],"Selloum source identity changed")
    boundary_rows=[(i,row) for i,row in enumerate(rows) if row.get("hasBoundary")]
    require(rows[:len(boundary_rows)]==[row for _,row in boundary_rows],"Boundary rows no longer form prefix")
    geoms=[decode(row,bin_raw,SCALE) for _,row in boundary_rows]
    original_geoms=list(geoms)
    pos={row["id"]:n for n,(_,row) in enumerate(boundary_rows)}
    ain_geom=geoms[pos[AIN_ID]]; sell_geom=geoms[pos[SELL_ID]]
    rep=Point(ain["lng"],ain["lat"]); require(ain_geom.is_valid and sell_geom.is_valid and ain_geom.contains(rep),"Input geometry/representative invalid")
    conflicts_before=detect_conflicts([row for _,row in boundary_rows],geoms)
    pairs_before=pair_map(conflicts_before); target_pair=tuple(sorted((AIN_ID,SELL_ID)))
    require(target_pair in pairs_before,"Expected Ain/Selloum overlap no longer exists")
    disputed=pairs_before[target_pair]["sample"]; disputed_ll=Point(disputed["lng"],disputed["lat"])
    require(ain_geom.contains(disputed_ll) and sell_geom.contains(disputed_ll),"Recorded disputed sample no longer lies in both polygons")

    utm=UTM32N()
    class Forward: transform=utm.forward
    class Inverse: transform=utm.inverse
    to_utm,to_wgs=Forward(),Inverse()
    ain_m=transform(to_utm.transform,ain_geom); sell_m=transform(to_utm.transform,sell_geom)
    exclusion_m=sell_m.buffer(0.1)
    full_clip_ll=set_precision(transform(to_wgs.transform,ain_m.difference(exclusion_m)),1/SCALE)
    require(full_clip_ll.is_valid and not full_clip_ll.is_empty,"Full clip invalid after quantization")
    parts=list(full_clip_ll.geoms) if full_clip_ll.geom_type=="MultiPolygon" else [full_clip_ll]
    kept=[p for p in parts if p.contains(rep)]
    require(len(kept)==1 and kept[0].geom_type=="Polygon","Original representative must select exactly one Polygon component")
    candidate=kept[0]
    removed=[p for p in parts if p is not candidate]
    official_ll=shape(ring["geometry"]); require(official_ll.is_valid,"Official Ain ring invalid")
    official_m=transform(to_utm.transform,official_ll)
    removed_info=[]
    for p in removed:
        pm=transform(to_utm.transform,p)
        overlap=pm.intersection(official_m).area/1e6
        require(overlap<1e-9,"A dropped detached clip component overlaps official ring")
        removed_info.append({"areaKm2":pm.area/1e6,"officialOverlapKm2":overlap,"officialOverlapPercent":0.0,
            "representativePointLatLng":[p.representative_point().y,p.representative_point().x]})
    require(len(removed)==2,"Expected exactly the two previously reviewed detached slivers")
    candidate_m=transform(to_utm.transform,candidate)
    require(candidate.is_valid and candidate.geom_type=="Polygon" and candidate_m.is_valid and candidate_m.area>0,"Main component candidate invalid")
    require(candidate.contains(rep),"Original Ain representative was not retained")
    exclusion_ll=transform(to_wgs.transform,exclusion_m)
    residual= candidate.intersection(exclusion_ll).area
    require(residual<=1e-12,"Candidate overlaps Selloum 0.1m exclusion after quantization")
    require(not candidate.contains(disputed_ll),"Disputed sample remains in Ain")

    before_ain=canonical_identity(ain)
    candidate_packed=packed_geometry_bytes(candidate)
    original_payloads={row["id"]:bin_raw[row["offset"]:row["offset"]+row["length"]] for _,row in boundary_rows}
    # Only Ain geometry/source metadata changes; preserve its original representative coordinate.
    ain["sourceId"]=SOURCE_ID; ain["bbox"]=list(candidate.bounds)
    ain["areaKm2"]=candidate.area*111.32**2*math.cos(math.radians(ain["lat"]))
    geoms[pos[AIN_ID]]=candidate
    conflicts_after=detect_conflicts([row for _,row in boundary_rows],geoms)
    pairs_after=pair_map(conflicts_after)
    added=set(pairs_after)-set(pairs_before); removed_pairs=set(pairs_before)-set(pairs_after)
    require(not added and removed_pairs=={target_pair},f"Unexpected conflict delta; added={added}, removed={removed_pairs}")

    cells=defaultdict(list)
    for n,geom in enumerate(geoms):
        minx,miny,maxx,maxy=geom.bounds
        for y in range(math.floor(miny/GRID),math.floor(maxy/GRID)+1):
            for x in range(math.floor(minx/GRID),math.floor(maxx/GRID)+1): cells[f"{y}:{x}"].append(n)
    catalog["cells"]={key:cells[key] for key in sorted(cells)}; catalog["conflicts"]=conflicts_after
    before_semantics=[canonical_identity(r) for r in rows]
    # Add provisional source record only; keep all target identity, parents, coords, picker group and other semantics.
    require(SOURCE_ID not in catalog["sources"],"Derived source ID already exists")
    review_sha=sha(source_raw["review.json"]); followup_sha=sha(source_raw["followup-20260925.json"])
    evidence_root="work/official-imada-evidence-20260924-v2/01-sousse/boundary-risk/selloum-ain-rahma"
    source_record={"id":SOURCE_ID,"provider":"Derived geometry record","url":followup["ringPrerequisite"]["sourceMapUrl"],"sha256":followup_sha,
      "review":{"status":"provisional_best_effort_boundary_candidate","scopeKind":"osm_sector_main_component_minus_reviewed_isie_peer_buffer",
        "evidenceFileRoot":"taskWorkspace","evidenceFiles":[
          {"path":f"{evidence_root}/review.json","sha256":review_sha},
          {"path":f"{evidence_root}/followup-20260925.json","sha256":followup_sha},
          {"path":"work/hammamet-frontier11-official-maps-20260919/ain-rahma.pdf","sha256":sha(source_raw["ain-rahma.pdf"])},
          {"path":"work/hammamet-frontier11-official-maps-20260919/ain-rahma-metadata.json","sha256":sha(source_raw["ain-rahma-metadata.json"])}],
        "evidenceFile":f"{evidence_root}/followup-20260925.json","evidenceSha256":followup_sha,
        "scopeEvidence":{"path":f"{evidence_root}/followup-20260925.json#scopePrerequisite","sha256":followup_sha,"officialCode":"316156"},
        "maskEvidence":{"path":f"{evidence_root}/review.json#officialScope.selloum","sha256":review_sha,"featureId":SELL_ID,"sourceId":sell["sourceId"]},
        "sourceGeometryEvidence":{"osmCurrentFeatureId":AIN_ID,"osmOriginalSourceId":"osm",
          "officialScopeRingPdfSha256":followup["ringPrerequisite"]["pdfSha256"],"officialScopeRingMetadataSha256":sha(source_raw["ain-rahma-metadata.json"]),
          "officialScopeRingDrawingIndex":ring["drawingIndex"],
          "candidateFormula":"current Ain Rahma minus 0.1 m buffer of unchanged current Selloum in EPSG:32632; retain unique connected component containing the original Ain representative; inverse transform and set_precision(1e-6 degrees)",
          "retainedComponentCount":1,"droppedDetachedComponentCount":len(removed),"boundaryAccuracyVerified":False},
        "boundaryAccuracyVerified":False,
        "uncertainty":"Provisional one-sided overlap removal. Two detached clipped slivers with zero reviewed official-ring overlap were removed. The shared seam and boundary accuracy remain uncertified; this is not a whole-imada replacement."}}
    catalog["sources"][SOURCE_ID]=source_record

    # Rep preservation and unchanged feature semantics are exact checks; offset/area/bbox/source are intended fields.
    require((ain["lat"],ain["lng"])==original_ain_rep,"Representative coordinate changed")
    require(canonical_identity(ain)==before_semantics[ain_i],"Ain identity/picker/prayer semantics changed")
    for ix,(row,sem) in enumerate(zip(rows,before_semantics)):
        if ix!=ain_i: require(canonical_identity(row)==sem,"Untouched feature semantics changed: "+row["id"])
    new_blob=bytearray(bin_raw[:8])
    for n,(_,row) in enumerate(boundary_rows):
        payload=candidate_packed if row["id"]==AIN_ID else original_payloads[row["id"]]
        row["offset"],row["length"]=len(new_blob),len(payload); new_blob.extend(payload)
    country=catalog["country"]; country_payload=bin_raw[country["offset"]:country["offset"]+country["length"]]
    require(country_payload,"Country payload missing")
    catalog["country"]={"offset":len(new_blob),"length":len(country_payload)}; new_blob.extend(country_payload)
    new_blob=bytes(new_blob)
    require(new_blob[catalog["country"]["offset"]:catalog["country"]["offset"]+catalog["country"]["length"]]==country_payload,"Country payload changed")
    for _,row in boundary_rows:
        if row["id"]!=AIN_ID: require(new_blob[row["offset"]:row["offset"]+row["length"]]==original_payloads[row["id"]],"Untouched packed payload changed: "+row["id"])
    require([r["id"] for r in rows]==[r["id"] for r in json.loads(json_raw)["features"]],"Feature IDs/order changed")
    after_json=jbytes(catalog)
    # Replay production lookup for the disputed point using old and staged catalogs.
    before_cat=json.loads(json_raw.decode("utf-8-sig"))
    before_winner=app_find(before_cat,original_geoms,disputed["lat"],disputed["lng"])
    after_winner=app_find(catalog,geoms,disputed["lat"],disputed["lng"])
    require(after_winner is not None and after_winner["id"]==SELL_ID,"Disputed GPS winner is not Selloum")
    require(sell_geom.contains(disputed_ll) and not candidate.contains(disputed_ll),"Disputed GPS containment invariant failed")
    prayer_sha=sha(source_raw["prayer-source-coordinates.json"])
    require(prayer_sha==SOURCE_HASHES["prayer-source-coordinates.json"],"Prayer source coordinate data changed")
    # The catalog semantics used to choose prayer source points are unchanged: IDs/order, names, delegation IDs and every lat/lng coordinate remain fixed.
    original=json.loads(json_raw.decode("utf-8-sig"))
    prayer_fields=("id","name","kind","governorateId","delegationId","lat","lng")
    require(all({k:a.get(k) for k in prayer_fields}=={k:b.get(k) for k in prayer_fields} for a,b in zip(original["features"],rows)),"Prayer-relevant feature identity/coordinate changed")

    report={"schemaVersion":1,"status":"ALL_PRECONDITIONS_PASSED","stageOnly":True,"installationPerformed":False,
      "baseSha256":{CATALOG.name:sha(json_raw),PACKED.name:sha(bin_raw)},"sourcePinsSha256":{k:sha(v) for k,v in source_raw.items()},
      "target":{"id":AIN_ID,"sourceIdBefore":"osm","sourceIdAfter":SOURCE_ID,"originalRepresentativeLatLng":list(original_ain_rep),
        "representativeCoordinatePreserved":True,"candidateGeometryType":candidate.geom_type,"candidateIsValid":candidate.is_valid,
        "candidateComponentCount":1,"fullClipComponentCount":len(parts),"removedDetachedComponents":removed_info,
        "removedDetachedAreaKm2":sum(x["areaKm2"] for x in removed_info),"candidateAreaKm2":candidate_m.area/1e6,
        "bboxWgs84":list(candidate.bounds),"repInsideCandidate":candidate.contains(rep),"candidateExclusionMaskIntersectionDegree2":residual,
        "officialRingOverlapKm2":candidate_m.intersection(official_m).area/1e6},
      "catalog":{"featureCount":len(rows),"idsAndOrderUnchanged":True,"untouchedFeatureSemanticsPreserved":True,
        "untouchedPackedPayloadsPreserved":True,"countryPayloadPreserved":True,"bboxGridRebuilt":True,
        "conflictCountBefore":len(pairs_before),"conflictCountAfter":len(pairs_after),"addedConflictPairs":[list(p) for p in sorted(added)],
        "removedConflictPairs":[list(p) for p in sorted(removed_pairs)],"targetConflictBefore":pairs_before[target_pair],"targetConflictAfter":None},
      "gps":{"disputedSample":disputed,"beforeWinnerId":before_winner["id"] if before_winner else None,
        "afterWinnerId":after_winner["id"] if after_winner else None,"selloumContains":sell_geom.contains(disputed_ll),
        "ainCandidateContains":candidate.contains(disputed_ll)},
      "prayerSourceInvariance":{"prayerSourceCoordinatesSha256":prayer_sha,"featureIdsAndOrderUnchanged":True,
        "allFeatureLatLngAndDelegationIdentityFieldsUnchanged":True,"ainRepresentativePreserved":True,
        "note":"The reviewed prayer-source coordinate asset is byte-identical; every feature ID/name/kind/governorate/delegation/lat/lng used to select a prayer-source locality remains unchanged."},
      "lineage":{"sourceId":SOURCE_ID,"boundaryAccuracyVerified":False,"evidenceFiles":source_record["review"]["evidenceFiles"]}}
    require(not added and len(rows)==3474 and len(parts)==3 and len(removed)==2,"Final package safety checks failed")
    return json_raw,bin_raw,after_json,new_blob,report

def main():
    if len(sys.argv)!=2 or sys.argv[1] not in ("--stage-only","--install"):
        raise SystemExit("usage: python install.py --stage-only|--install")
    mode=sys.argv[1]
    if mode=="--install" and RECEIPT.exists():
        receipt=json.loads(RECEIPT.read_text(encoding="utf-8"))
        current={CATALOG.name:sha(CATALOG.read_bytes()),PACKED.name:sha(PACKED.read_bytes())}
        if current==receipt.get("afterSha256"):
            print(json.dumps({"status":"already_installed","receipt":str(RECEIPT)},ensure_ascii=True)); return
        raise RuntimeError("An install receipt exists but live assets do not match it")
    original_json,original_bin,staged_json,staged_bin,report=compile_candidate()
    report["stageOnly"]=(mode=="--stage-only")
    report["installationPerformed"]=(mode=="--install")
    if mode=="--stage-only":
        STAGED_JSON.write_bytes(staged_json); STAGED_BIN.write_bytes(staged_bin)
        REPORT.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
        print(json.dumps({"status":"staged","candidateJsonSha256":sha(staged_json),"candidateBinSha256":sha(staged_bin),"report":str(REPORT)},ensure_ascii=True))
        return
    actual={CATALOG.name:sha(CATALOG.read_bytes()),PACKED.name:sha(PACKED.read_bytes())}
    expected_after={CATALOG.name:sha(staged_json),PACKED.name:sha(staged_bin)}
    require(actual==BASE_HASHES,"Live asset base changed before install")
    require(not BACKUP_JSON.exists() and not BACKUP_BIN.exists(),"Backup already exists; refusing to overwrite")
    require(STAGED_JSON.is_file() and STAGED_BIN.is_file() and sha(STAGED_JSON.read_bytes())==expected_after[CATALOG.name]
            and sha(STAGED_BIN.read_bytes())==expected_after[PACKED.name],"Review-stage output is missing or differs from fresh deterministic rebuild")
    BACKUP_JSON.write_bytes(original_json); BACKUP_BIN.write_bytes(original_bin)
    tmp_json=CATALOG.with_suffix(".json.sousse-tmp"); tmp_bin=PACKED.with_suffix(".bin.sousse-tmp")
    try:
        tmp_json.write_bytes(staged_json); tmp_bin.write_bytes(staged_bin)
        require(sha(tmp_json.read_bytes())==expected_after[CATALOG.name] and sha(tmp_bin.read_bytes())==expected_after[PACKED.name],"Temporary output verification failed")
        os.replace(tmp_json,CATALOG); os.replace(tmp_bin,PACKED)
    except Exception:
        if tmp_json.exists(): tmp_json.unlink()
        if tmp_bin.exists(): tmp_bin.unlink()
        # Restore both originals if a partial replacement occurred.
        CATALOG.write_bytes(original_json); PACKED.write_bytes(original_bin)
        raise
    after={CATALOG.name:sha(CATALOG.read_bytes()),PACKED.name:sha(PACKED.read_bytes())}
    require(after==expected_after,"Installed hashes differ from deterministic candidate")
    receipt={"schemaVersion":1,"installedAtUtc":datetime.now(timezone.utc).isoformat(),"status":"installed",
      "package":str(HERE),"baseSha256":BASE_HASHES,"afterSha256":after,
      "backupFiles":{"neighborhoods.json":str(BACKUP_JSON),"neighborhoods.bin":str(BACKUP_BIN)},
      "validationReport":str(REPORT),"sourceId":SOURCE_ID,"featureId":AIN_ID,
      "sourcePinsSha256":report["sourcePinsSha256"],"proof":report}
    RECEIPT.write_text(json.dumps(receipt,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    REPORT.write_text(json.dumps({**report,"installationPerformed":True,"afterSha256":after},ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({"status":"installed","afterSha256":after,"receipt":str(RECEIPT)},ensure_ascii=True))

if __name__=="__main__": main()
