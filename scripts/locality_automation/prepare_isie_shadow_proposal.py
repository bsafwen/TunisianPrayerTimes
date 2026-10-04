"""Convert one pinned whole native face into an explicitly unaccepted proposal.

Use the existing production quantizer/packer and stock isolated staging helper.
No source authority, survey accuracy, independent QA or installation is asserted.
"""
import argparse
from datetime import datetime, timezone
import importlib.util
import json
from pathlib import Path
import sys

from pyproj import Transformer
from shapely import from_wkb, set_precision
from shapely.ops import transform

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import active_control, checked, pin, read
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree
from scripts.locality_automation.audit_prepared_osm_graphs import write_new
from scripts.locality_automation.work_window_guard import parse_utc


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    spec = read(args.manifest)
    control = active_control(spec)
    if datetime.now(timezone.utc) >= parse_utc(control["safeSourceQaStartUtc"]):
        raise ValueError("Source start cutoff reached")
    if args.output.exists() or spec["code"] not in control["approvedReconciliationPool"] or spec.get("credit") != 0:
        raise ValueError("Fresh finite unaccepted hypothesis required")
    check_tree(spec)
    packet = read(checked(spec["legacyPacket"]))
    row = next(row for row in packet["rows"] if row["officialCode"] == spec["code"])
    if row["decision"] != "GO_M_SOURCE" or row["acceptedWkb"] is None:
        raise ValueError("Preserve the original native-source hold")
    if row["officialCode"] == "425959":
        raise ValueError("Zalfan original invalid-path hold cannot be converted")
    native = read(checked(spec["nativeRecheck"]))
    fresh = next(item for item in native["rows"] if item["officialCode"] == spec["code"])
    if (fresh["freshSourceFaceMatchesFrozenFace"] is not True
            or fresh["sourcePdf"]["sha256"] != row["sourcePdf"]["sha256"]
            or fresh["registeredWkb"]["sha256"] != row["acceptedWkb"]["epsg32632"]["sha256"]
            or fresh["registration"]["fitMaxControlResidualMeters"] > .5
            or fresh["registration"]["leaveOneOutMaxResidualMeters"] > 1.5):
        raise ValueError("Pinned native face or original strict registration differs")
    neighbor = read(checked(spec["neighborLinework"]))
    if neighbor["primaryCode"] != spec["code"] or neighbor["credit"] != 0 or neighbor["independentQa"] is not False:
        raise ValueError("Root neighbor context differs")
    metadata = read(checked(spec["metadata"]))
    feature = next(item for item in metadata["features"] if item["id"] == row["appId"])
    if feature["name"] != row["officialName"] or feature["parentName"] != "معتمدية " + row["officialParent"]:
        raise ValueError("Literal current native-title identity or parent differs")
    protection = read(checked(spec["protectedLineage"]))
    if any(item["id"] == row["appId"] or item["officialCode"] == spec["code"] for item in protection["bindings"]):
        raise ValueError("This diagnostic cannot replace a protected accepted body")
    projected = from_wkb(checked(fresh["registeredWkb"]).read_bytes())
    if projected.is_empty or not projected.is_valid:
        raise ValueError("Invalid whole native face; no repair")
    raw = transform(Transformer.from_crs(32632, 4326, always_xy=True).transform, projected)
    loader = importlib.util.spec_from_file_location("original_shadow_production_packer", checked(spec["productionPacker"]))
    packer = importlib.util.module_from_spec(loader)
    loader.loader.exec_module(packer)
    if packer.SCALE != metadata["coordinateScale"]:
        raise ValueError("Production grid differs")
    grid = set_precision(raw, 1 / packer.SCALE)
    if (not raw.is_valid or not grid.is_valid or raw.is_empty or grid.is_empty
            or len(getattr(raw, "geoms", [raw])) != len(getattr(grid, "geoms", [grid]))
            or sum(len(p.interiors) for p in getattr(raw, "geoms", [raw]))
            != sum(len(p.interiors) for p in getattr(grid, "geoms", [grid]))):
        raise ValueError("Invalid or changed native components/inner roles after standard quantization")
    args.output.mkdir()
    raw_path, grid_path = args.output / "whole-source-wgs84.wkb", args.output / "whole-source-app-grid.wkb"
    raw_path.write_bytes(raw.wkb)
    grid_path.write_bytes(grid.wkb)
    qualification = ("UNACCEPTED ROOT SHADOW: complete frozen native ISIE electoral face, never an inferred clipped boundary. "
                     "Solo neighbor comparison and original internal registration are technical context only. "
                     "Original actor provenance, complete scope/identity/legal/neighbor/GPS/CAF/A4 acceptance remain pending; zero credit.")
    proposal = {"status": "UNACCEPTED_ROOT_ISOLATED_HYPOTHESIS_NO_INSTALLATION", "credit": 0,
        "baseCatalogMetadata": spec["metadata"], "baseCatalogBinary": spec["binary"],
        "sourceIdPrefix": "UNACCEPTED-ISIE-SHADOW", "reviewedDate": "2026-10-03",
        "manifest": pin(args.manifest), "nativeRecheck": spec["nativeRecheck"], "neighborLinework": spec["neighborLinework"],
        "patches": [{"id": row["appId"], "officialCode": row["officialCode"], "geometry": pin(grid_path),
                     "rawSourceGeometry": pin(raw_path), "sourcePdf": row["sourcePdf"],
                     "sourceProvider": "ISIE 2023 - unaccepted isolated root hypothesis",
                     "sourceUrl": "https://www.isie.tn/ar/الدوائر-المحلية/", "qualification": qualification,
                     "uncertainty": "Catalogue landing page only; exact saved PDF identified by its original local file/hash. Internal controls do not certify survey accuracy. No acceptance, live map update or installation."}]}
    proposal_reference = write_new(args.output / "proposal.json", proposal)
    print(json.dumps({"proposal": proposal_reference, "code": spec["code"], "credit": 0}))


if __name__ == "__main__":
    main()
