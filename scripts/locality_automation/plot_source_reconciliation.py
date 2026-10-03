"""Render pinned unaccepted boundary differences as a standalone offline plot."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts.locality_automation.run_sealed_boundary_queue import checked, pin, read
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.patches import Patch, PathPatch
from matplotlib.path import Path as PlotPath
from pyproj import Transformer
from shapely.geometry import shape
from shapely.geometry.polygon import orient
from shapely.ops import transform


def polygons(body):
    if body.is_empty:
        return
    if body.geom_type == "Polygon":
        yield body
    elif hasattr(body, "geoms"):
        for child in body.geoms:
            yield from polygons(child)


def patch(poly, color):
    vertices, codes = [], []
    for ring in (orient(poly, sign=1.0).exterior, *orient(poly, sign=1.0).interiors):
        points = [[x / 1000, y / 1000] for x, y in ring.coords]
        vertices.extend(points)
        codes.extend([PlotPath.MOVETO] + [PlotPath.LINETO] * (len(points) - 2) + [PlotPath.CLOSEPOLY])
    return PathPatch(PlotPath(vertices, codes), facecolor=color, edgecolor="none", alpha=.8)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--proof", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists() or args.proof.exists():
        raise ValueError("Fresh plot and proof outputs required")
    spec = read(args.manifest)
    source_path, features_path = checked(spec["comparison"]), checked(spec["geojson"])
    source, feature_collection = read(source_path), read(features_path)
    annotation = spec.get("annotation", "")
    if annotation:
        checked(spec["annotationEvidence"])
    project = Transformer.from_crs(4326, 32632, always_xy=True).transform
    layers = {}
    for feature in feature_collection["features"]:
        props = feature["properties"]
        if props["geographicCredit"] != 0 or props["acceptedCurrentValidation"] is not False:
            raise ValueError("Pending geometry has acceptance credit")
        key = props["officialCode"], props["kind"]
        if key in layers:
            raise ValueError("Duplicate comparison layer")
        layers[key] = transform(project, shape(feature["geometry"]))
    rows = source["rows"]
    if len(rows) != 8 or len(layers) != 32:
        raise ValueError("This explicit finite eight-location plot scope differs")
    plt.rcParams.update({"font.size": 9, "axes.spines.top": False, "axes.spines.right": False})
    fig, axes = plt.subplots(2, 4, figsize=(16, 10), layout="constrained")
    for ax, row in zip(axes.flat, rows):
        code = row["officialCode"]
        if row["newCredit"] != 0 or row["geographicValidation"] is not False:
            raise ValueError("Pending row qualification changed")
        bodies = {kind: layers[(code, kind)] for kind in ("source_only_face", "current_installed_body",
                 "candidate_gain_unaccepted", "candidate_loss_unaccepted")}
        for kind, color in (("candidate_gain_unaccepted", "#42bf80"), ("candidate_loss_unaccepted", "#db65c9")):
            for poly in polygons(bodies[kind]):
                ax.add_patch(patch(poly, color))
        for kind, color, width, style in (("current_installed_body", "#222831", 1.1, "-"),
                ("source_only_face", "#1768c3", 1.0, "--")):
            for poly in polygons(bodies[kind]):
                for ring in (poly.exterior, *poly.interiors):
                    x, y = ring.xy
                    ax.plot([v / 1000 for v in x], [v / 1000 for v in y], color=color, lw=width, ls=style)
        ax.relim()
        ax.autoscale_view()
        ax.set_aspect("equal", adjustable="datalim")
        ax.margins(.06)
        ax.grid(color="#d6dce3", lw=.5, alpha=.6)
        metrics = row["metrics"]
        small = max(metrics["gainedSquareMeters"], metrics["lostSquareMeters"]) < 1000
        divisor, units, precision = (1, "m²", 1) if small else (1e6, "km²", 3)
        ax.set_title(f'{code}   |   IoU {metrics["intersectionOverUnion"]:.6f}\n'
                     f'+{metrics["gainedSquareMeters"]/divisor:.{precision}f} / '
                     f'-{metrics["lostSquareMeters"]/divisor:.{precision}f} {units}', loc="left")
        ax.ticklabel_format(useOffset=False, style="plain")
        ax.set_xlabel("UTM 32N easting (km)")
        ax.set_ylabel("Northing (km)")
    fig.suptitle("Eight pending ISIE candidates compared with installed boundaries\n"
                 "Source-only diagnostics · ZERO new validated locations · no geometry adoption"
                 + ("\n" + annotation if annotation else ""), fontsize=15)
    legend = [Line2D([0], [0], color="#222831", label="Current installed"),
              Line2D([0], [0], color="#1768c3", ls="--", label="ISIE source face"),
              Patch(facecolor="#42bf80", label="Proposed gain (unaccepted)"),
              Patch(facecolor="#db65c9", label="Proposed loss (unaccepted)")]
    fig.legend(handles=legend, loc="outside lower center", ncol=4, frameon=False)
    checked(spec["comparison"])
    checked(spec["geojson"])
    fig.savefig(args.output, dpi=160, facecolor="white")
    plt.close(fig)
    proof = dict(status="RENDERED_OFFLINE_PENDING_COORDINATE_PLOT", atUtc=datetime.now(timezone.utc).isoformat(),
        comparison=spec["comparison"], geojson=spec["geojson"], plot=pin(args.output), codes=[r["officialCode"] for r in rows],
        geographicCredit=0, browserContentRendered=False, networkRequestsPerformed=False, privateKeyRead=False)
    if annotation:
        proof.update(annotation=annotation, annotationEvidence=spec["annotationEvidence"])
    with args.proof.open("x", encoding="utf-8") as stream:
        json.dump(proof, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(json.dumps({"status": proof["status"], "plot": proof["plot"]}))


if __name__ == "__main__":
    main()
