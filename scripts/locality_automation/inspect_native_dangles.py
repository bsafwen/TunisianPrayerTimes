"""Locate held native red segments in untouched PDF crops and exact coordinates.

This diagnoses an existing hold. No line is removed or geometry repaired.
"""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sys

import pymupdf
from shapely import from_wkb
from shapely.ops import polygonize_full

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import active_control, checked, pin, read
from scripts.locality_automation.audit_prepared_osm_graphs import write_new
from scripts.locality_automation.audit_native_replay_layers import layer_lines
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree
from scripts.locality_automation.work_window_guard import parse_utc


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    spec = read(args.manifest)
    ctl = active_control(spec)
    if (args.output.exists() or spec.get("credit") != 0 or spec["code"] not in ctl["approvedReconciliationPool"]
            or datetime.now(timezone.utc) >= parse_utc(ctl["safeSourceQaStartUtc"])):
        raise ValueError("Fresh finite zero-credit native hold inspection required")
    check_tree(spec)
    row = next(r for r in read(checked(spec["layerAudit"]))["rows"] if r["officialCode"] == spec["code"])
    if not row["holds"]:
        raise ValueError("Inspect only an existing held source")
    page = read(checked(row["inventory"]))["pages"][0]
    face = from_wkb(checked(row["pageWkb"]).read_bytes())
    faces, cuts, dangles, invalid = polygonize_full(layer_lines(page, "red"))
    pieces = [line.intersection(face.buffer(-.05)) for line in dangles.geoms]
    pieces = [part for part in pieces if part.length > 0]
    if abs(sum(p.length for p in pieces) - row["interiorRedDangleLengthPagePoints"]) > 1e-8:
        raise ValueError("Held native segment evidence changed")
    args.output.mkdir()
    document = pymupdf.open(checked(row["sourcePdf"]))
    original = document[0]
    output = []
    for number, piece in enumerate(pieces):
        a, b, c, d = piece.bounds
        clip = pymupdf.Rect(a - 16, b - 16, c + 16, d + 16) & original.rect
        crop = args.output / f"original-native-crop-{number}.png"
        original.get_pixmap(matrix=pymupdf.Matrix(10, 10), clip=clip, alpha=False).save(crop)
        geometry = args.output / f"interior-native-segment-{number}.wkb"
        geometry.write_bytes(piece.wkb)
        # Preserve a vector locator separately; the source crop is untouched.
        output.append({"number": number, "lengthPagePoints": piece.length, "boundsPagePoints": list(piece.bounds),
            "nativeGeometry": pin(geometry), "originalCrop": pin(crop), "clipPagePoints": list(clip),
            "nativeWkt": piece.wkt})
    check_tree(spec)
    ref = write_new(args.output / "report.json", {"status": "PINNED_NATIVE_DANGLE_LOCATORS_ORIGINAL_HOLD_RETAINED",
        "manifest": pin(args.manifest), "sourcePdf": row["sourcePdf"], "heldRow": row,
        "pieces": output, "totalInteriorLengthPagePoints": sum(p.length for p in pieces),
        "geometryRepairApplied": False, "nativeLinesDiscarded": False, "originalHoldRetained": True,
        "independentQaPassed": False, "sourceScopeAccepted": False, "credit": 0})
    print(json.dumps({"report": ref, "pieces": len(output), "totalLengthPagePoints": sum(p.length for p in pieces), "credit": 0}))


if __name__ == "__main__":
    main()
