"""Render exact pinned source PDF pages for offline visual review.

No annotation, clipping, geometry transformation, repair or acceptance. Every
output is fresh, with a physical source/output binding and zero credit.
"""
import argparse
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import active_control, checked, pin, read
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree
from scripts.locality_automation.audit_prepared_osm_graphs import write_new
import pymupdf


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    spec = read(args.manifest)
    active_control(spec)
    check_tree(spec)
    if args.output.exists() or spec.get("credit") != 0 or not spec["pages"]:
        raise ValueError("Fresh finite zero-credit source rendering required")
    if type(spec["dpi"]) is not int or not 72 <= spec["dpi"] <= 200:
        raise ValueError("Explicit bounded page resolution required")
    codes = [row["officialCode"] for row in spec["pages"]]
    if len(codes) != len(set(codes)) or len(codes) > 8:
        raise ValueError("Unique finite page scope required")
    args.output.mkdir()
    rendered = []
    for row in spec["pages"]:
        if not row["officialCode"].isdigit() or len(row["officialCode"]) != 6:
            raise ValueError("Explicit six-digit source code required")
        path = checked(row["sourcePdf"])
        with pymupdf.open(path) as document:
            if document.page_count != row["expectedPageCount"] or type(row["page"]) is not int:
                raise ValueError("Original page count differs")
            page = document[row["page"]]
            pixels = page.get_pixmap(dpi=spec["dpi"], alpha=False)
            png = args.output / (row["officialCode"] + "-original-page.png")
            with png.open("xb") as stream:
                stream.write(pixels.tobytes("png"))
            rendered.append({"officialCode": row["officialCode"], "sourcePdf": row["sourcePdf"],
                "page": row["page"], "pageCount": document.page_count, "dpi": spec["dpi"],
                "width": pixels.width, "height": pixels.height, "image": pin(png), "credit": 0})
        checked(row["sourcePdf"])
    check_tree(spec)
    result = {"status": "RENDERED_ORIGINAL_PINNED_SOURCE_PAGES_NO_ACCEPTANCE", "manifest": pin(args.manifest),
        "rows": rendered, "originalPixelsAnnotatedOrClipped": False, "geometryChanged": False,
        "sourceScopeAccepted": False, "independentQaPassed": False, "credit": 0,
        "browserContentRendered": False, "networkRequestsPerformed": False, "privateKeyRead": False}
    write_new(args.output / "report.json", result)
    print({"status": result["status"], "pages": len(rendered), "credit": 0})


if __name__ == "__main__":
    main()
