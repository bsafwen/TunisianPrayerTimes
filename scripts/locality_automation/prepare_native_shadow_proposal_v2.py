"""Prepare a whole-source isolated hypothesis from finite frozen native inputs.

Requires the stricter original registration and exact complete face hashes and
the current named-layer replay. Neither neighbor/source acceptance nor a
different reviewer is inferred from these technical facts.
"""
import argparse
import csv
import json
from datetime import datetime, timezone
import importlib.util
from pathlib import Path
import sys

from pyproj import Transformer
from shapely import from_wkb, set_precision
from shapely.ops import transform

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import active_control, checked, pin, read
from scripts.locality_automation.audit_prepared_osm_graphs import write_new
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree
from scripts.locality_automation.work_window_guard import parse_utc


def literal_identity(row, feature, spec):
    if feature["parentName"] != "معتمدية " + row["officialParent"]:
        raise ValueError("Literal current source parent differs")
    if feature["name"] == row["officialName"]:
        return {"status": "LITERAL_NAMES_EQUAL", "currentName": feature["name"], "sourceName": row["officialName"]}
    # Explicit authoritative same-code bindings; no fuzzy text matching.
    with checked(spec["ministryMatrix"]).open(encoding="utf-8-sig", newline="") as stream:
        ministry = list(csv.DictReader(stream))
    if len(ministry) != 2084:
        raise ValueError("Complete eligible Ministry catalogue differs")
    matches = [m for m in ministry if m["frozenFunctionalExpectedId"] == feature["id"]]
    sectors = [s for s in read(checked(spec["insRegistry"]))["sectors"] if s["sectorCode"] == row["officialCode"]]
    if len(matches) != 1 or len(sectors) != 1:
        raise ValueError("Authoritative same-code alias is not unique")
    m, s = matches[0], sectors[0]
    if (m["name"] != row["officialName"] or m["delegation"] != row["officialParent"]
            or json.loads(m["identityCandidateCodes"]) != [row["officialCode"]]
            or s["sectorAr"] != feature["name"] or s["delegationAr"] != row["officialParent"]
            or s["governorateAr"] != m["governorate"]):
        raise ValueError("Original Ministry/INS/current literal code-name-parent binding differs")
    return {"status": "EXPLICIT_LITERAL_MINISTRY_INS_SAME_CODE_ALIAS_NO_FUZZY_MATCH", "officialCode": row["officialCode"],
        "sourceName": row["officialName"], "currentName": feature["name"], "parent": row["officialParent"],
        "id": feature["id"], "ministryCsvLine": m["ministryCsvLine"], "ministry": spec["ministryMatrix"],
        "ins": spec["insRegistry"], "insSourceRows": s["sourceRows"], "currentIdentityChanged": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    spec = read(args.manifest)
    ctl = active_control(spec)
    if (args.output.exists() or spec.get("credit") != 0 or spec["code"] not in ctl["approvedReconciliationPool"]
            or spec["code"] in ("425959", "425452")
            or datetime.now(timezone.utc) >= parse_utc(ctl["safeSourceQaStartUtc"])):
        raise ValueError("Fresh finite unaccepted shadow required; preserve native holds")
    check_tree(spec)
    code = spec["code"]
    row = next(r for r in read(checked(spec["nativeInput"]))["rows"] if r["officialCode"] == code)
    fresh = next(r for r in read(checked(spec["nativeReplay"]))["rows"] if r["officialCode"] == code)
    layers = next(r for r in read(checked(spec["layerReplay"]))["rows"] if r["officialCode"] == code)
    if (row["sourceGateDecision"] != "GO_M_SOURCE" or fresh["freshSourceFaceMatchesFrozenFace"] is not True
            or fresh["sourcePdf"]["sha256"] != row["sourcePdf"]["sha256"]
            or fresh["registeredWkb"]["sha256"] != row["legacySourceFace"]["sha256"]
            or layers["registeredWkb"] != fresh["registeredWkb"] or layers["holds"]
            or layers["independentQaPassed"] is not False or layers["credit"] != 0
            or fresh["registration"]["crsEpsg"] != 32632
            or fresh["registration"]["fitMaxControlResidualMeters"] > .5
            or fresh["registration"]["leaveOneOutMaxResidualMeters"] > 1.5):
        raise ValueError("Whole native source, exact layers or original strict registration differs")
    metadata = read(checked(spec["metadata"]))
    feature = next(r for r in metadata["features"] if r["id"] == row["appId"])
    identity = literal_identity(row, feature, spec)
    lineage = read(checked(spec["protectedLineage"]))
    if any(r["id"] == feature["id"] or r["officialCode"] == code for r in lineage["bindings"]):
        raise ValueError("Protected accepted source cannot be replaced by this hypothesis")
    ground = from_wkb(checked(fresh["registeredWkb"]).read_bytes())
    raw = transform(Transformer.from_crs(32632, 4326, always_xy=True).transform, ground)
    loader = importlib.util.spec_from_file_location("unchanged_native_shadow_packer", checked(spec["productionPacker"]))
    packer = importlib.util.module_from_spec(loader)
    loader.loader.exec_module(packer)
    if packer.SCALE != metadata["coordinateScale"]:
        raise ValueError("Production quantization grid differs")
    grid = set_precision(raw, 1 / packer.SCALE)
    def roles(body):
        polygons = getattr(body, "geoms", [body])
        return len(polygons), sum(len(p.interiors) for p in polygons)
    if (any(b.is_empty or not b.is_valid for b in (ground, raw, grid)) or roles(raw) != roles(grid)):
        raise ValueError("Whole source is invalid or loses components/inner roles; no repair")
    args.output.mkdir()
    raw_path, grid_path = args.output / "whole-source-wgs84.wkb", args.output / "whole-source-app-grid.wkb"
    raw_path.write_bytes(raw.wkb)
    grid_path.write_bytes(grid.wkb)
    proposal = {"status": "UNACCEPTED_ROOT_ISOLATED_HYPOTHESIS_NO_INSTALLATION", "credit": 0,
        "manifest": pin(args.manifest), "baseCatalogMetadata": spec["metadata"], "baseCatalogBinary": spec["binary"],
        "nativeReplay": spec["nativeReplay"], "nativeLayerReplay": spec["layerReplay"],
        "sourceIdPrefix": "UNACCEPTED-ISIE-SHADOW", "reviewedDate": "2026-10-03",
        "neighborSourceReviewPending": True, "independentQaPassed": False,
        "literalIdentityBinding": identity,
        "patches": [{"id": feature["id"], "officialCode": code, "geometry": pin(grid_path),
            "rawSourceGeometry": pin(raw_path), "sourcePdf": row["sourcePdf"],
            "sourceProvider": "ISIE 2023 - unaccepted isolated root hypothesis",
            "sourceUrl": "https://www.isie.tn/ar/الدوائر-المحلية/",
            "qualification": "UNACCEPTED ROOT SHADOW: complete frozen native electoral face. Strict internal registration and named-layer replay are technical preparation only. Original provenance, neighbor/source/identity/legal/GPS/CAF/A4 acceptance remain pending; zero credit.",
            "uncertainty": "Catalogue landing page only; exact saved PDF has its original local file/hash. No civil extent or survey accuracy inferred. Live assets and validated map are unchanged."}]}
    check_tree(spec)
    ref = write_new(args.output / "proposal.json", proposal)
    print({"proposal": ref, "code": code, "credit": 0})


if __name__ == "__main__":
    main()
