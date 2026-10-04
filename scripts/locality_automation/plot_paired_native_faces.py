"""Plot pinned paired ISIE diagnostics as uninstalled hypotheses."""
import argparse
from pathlib import Path
import sys

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from pyproj import CRS
from shapely import from_wkb

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree
from scripts.locality_automation.isie_candidate_comparison import Catalog
from scripts.locality_automation.run_sealed_boundary_queue import checked, pin, read
from scripts.locality_automation.audit_prepared_osm_graphs import write_new


def draw(axis, geom, color, label, dashed=False, fill=False):
    polygons = [geom] if geom.geom_type == "Polygon" else list(getattr(geom, "geoms", []))
    for i, polygon in enumerate(polygons):
        if polygon.geom_type != "Polygon":
            continue
        x, y = polygon.exterior.xy
        axis.plot(x, y, color=color, lw=1.5, linestyle="--" if dashed else "-", label=label if i == 0 else None)
        if fill:
            axis.fill(x, y, color=color, alpha=.45)
        for ring in polygon.interiors:
            axis.plot(*ring.xy, color=color, lw=1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    spec = read(args.manifest)
    if args.output.exists() or spec.get("credit") != 0 or len(spec["pairs"]) > 3:
        raise ValueError("Fresh finite zero-credit plot required")
    check_tree(spec)
    fig, axes = plt.subplots(len(spec["pairs"]), 2, figsize=(13, 5*len(spec["pairs"])), squeeze=False)
    results = []
    for i, ref in enumerate(spec["pairs"]):
        report = read(checked(ref))
        if report["credit"] != 0 or report["sourceScopeAccepted"] or report["geometryChanged"]:
            raise ValueError("Only unchanged unaccepted source diagnostics may be plotted")
        pair = read(checked(report["manifest"]))
        check_tree(pair)
        rows = pair["rows"]
        catalog = Catalog(checked(pair["metadata"]), checked(pair["binary"]))
        source = [from_wkb(checked(r["registeredWkb"]).read_bytes()) for r in rows]
        installed = [catalog.geometry(r["appId"], CRS.from_epsg(32632)) for r in rows]
        for j, bodies in enumerate(((source[0], installed[1]), tuple(source))):
            ax = axes[i, j]
            draw(ax, installed[0], "#999999", "Current primary", dashed=True)
            draw(ax, bodies[0], "#1263a5", "Original primary source face")
            draw(ax, bodies[1], "#238951", "Current neighbor" if j == 0 else "Original neighbor source face")
            draw(ax, bodies[0].intersection(bodies[1]), "#d14739", "Intersection", fill=True)
            ax.set_aspect("equal")
            area = bodies[0].intersection(bodies[1]).area/1e6
            ax.set_title(f"{rows[0]['officialCode']} / {rows[1]['officialCode']} — {'mixed sources' if j == 0 else 'paired original sheets'}\nIntersection {area:.6f} km²", fontsize=11)
            ax.legend(fontsize=8, loc="best")
            ax.ticklabel_format(style="plain", useOffset=False)
            ax.set_xlabel("EPSG:32632 easting (m)")
            ax.set_ylabel("Northing (m)")
        results.append({"report": ref, "codes": [r["officialCode"] for r in rows], "credit": 0})
    fig.suptitle("UNINSTALLED SOURCE DIAGNOSTICS — paired electoral-map faces; every seam retained", fontsize=13)
    fig.tight_layout(rect=(0, 0, 1, .97))
    args.output.mkdir()
    image = args.output / "paired-source-atlas.png"
    fig.savefig(image, dpi=150)
    plt.close(fig)
    check_tree(spec)
    write_new(args.output / "report.json", {"status": "PINNED_PAIRED_SOURCE_ATLAS_NO_ACCEPTANCE", "manifest": pin(args.manifest), "image": pin(image), "pairs": results, "credit": 0, "googleMapsRendered": False, "assetsChanged": False})
    print(str(image))


if __name__ == "__main__":
    main()
