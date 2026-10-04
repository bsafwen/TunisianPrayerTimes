"""Render the largest preserved native/current conflicts as an offline atlas.

This visualizes existing immutable evidence, with no geometry repair, acceptance
or Google/browser claims. Reported stage areas and projected plot areas remain
separate quantities.
"""
import argparse
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import active_control, checked, pin, read
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree
from scripts.locality_automation.audit_prepared_osm_graphs import write_new
from scripts.locality_automation.packed_gps_replay import PackedGpsReplay
from scripts.locality_automation.plot_source_reconciliation import polygons, patch
from pyproj import Transformer
from shapely.ops import transform
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.patches import Patch


def outline(ax, body, color, style, width):
    for poly in polygons(body):
        for ring in (poly.exterior, *poly.interiors):
            x, y = ring.xy
            ax.plot([value / 1000 for value in x], [value / 1000 for value in y],
                color=color, ls=style, lw=width)


def title_for(row):
    latin = next((value for value in row.get("aliases", [])
        if value.isascii() and any(character.isalpha() for character in value)), None)
    return (latin + " | " if latin else "") + row["id"].replace("osm:relation:", "relation ")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--proof", required=True, type=Path)
    args = parser.parse_args()
    spec = read(args.manifest)
    active_control(spec)
    check_tree(spec)
    if args.output.exists() or args.proof.exists() or spec.get("credit") != 0:
        raise ValueError("Fresh explicitly unaccepted plot required")
    diagnostic = read(checked(spec["topology"]))
    stage = read(checked(read(checked(diagnostic["manifest"]))["jointStage"]))
    check_tree(stage)
    if (diagnostic["credit"] != 0 or diagnostic["sourceScopeAccepted"] is not False
            or diagnostic["conflictsDiscardedOrClipped"] is not False
            or diagnostic["currentAssetsChanged"] is not False
            or diagnostic["newIncidentConflicts"] != stage["newIncidentConflicts"]):
        raise ValueError("Original complete conflict report changed or acquired authority")
    before = PackedGpsReplay(checked(stage["beforeMetadata"]), checked(stage["beforeBinary"]))
    after = PackedGpsReplay(checked(stage["stagedMetadata"]), checked(stage["stagedBinary"]))
    changed = set(diagnostic["changedIds"])
    lineage = read(checked(spec["protectedLineage"]))
    protected = {row["id"] for row in lineage["bindings"]}
    if len(protected) != 323 or protected & changed:
        raise ValueError("Protected accepted body set differs")
    comparisons = [row for row in diagnostic["newIncidentConflicts"]
        if len(set(row["ids"]) & changed) == 1 and row["reason"] == "overlapping_sectors"]
    comparisons.sort(key=lambda row: (-row["intersectionKm2"], row["ids"]))
    comparisons = comparisons[:6]
    if len(comparisons) != 6:
        raise ValueError("Exactly six largest native/unchanged-peer conflicts required")
    project = Transformer.from_crs(4326, 32632, always_xy=True).transform
    fig, axes = plt.subplots(2, 3, figsize=(17, 11), layout="constrained")
    rendered = []
    for ax, conflict in zip(axes.flat, comparisons):
        ident = next(item for item in conflict["ids"] if item in changed)
        peer = next(item for item in conflict["ids"] if item not in changed)
        old = transform(project, before.geometry(ident))
        proposed = transform(project, after.geometry(ident))
        neighbor = transform(project, before.geometry(peer))
        if before.decode_geometry(peer) != after.decode_geometry(peer):
            raise ValueError("Plotted unchanged peer geometry changed")
        overlap = proposed.intersection(neighbor)
        if overlap.is_empty:
            raise ValueError("Pinned conflict has no projected intersection")
        for poly in polygons(overlap):
            ax.add_patch(patch(poly, "#f17670"))
        outline(ax, old, "#a2a9b2", ":", 1.4)
        outline(ax, neighbor, "#19824e", "-", 1.4)
        outline(ax, proposed, "#1768c3", "--", 1.4)
        ax.relim(); ax.autoscale_view(); ax.margins(.06)
        ax.set_aspect("equal", adjustable="datalim")
        ax.ticklabel_format(useOffset=False, style="plain")
        ax.grid(color="#d6dce3", lw=.5, alpha=.6)
        ax.set_xlabel("UTM 32N easting (km)"); ax.set_ylabel("Northing (km)")
        badge = "PROTECTED accepted peer" if peer in protected else "Current peer (acceptance not inferred)"
        ax.set_title(title_for(after.by_id[ident]) + "\nvs " + title_for(before.by_id[peer])
            + f"\nStage overlap {conflict['intersectionKm2']:.3f} km² | {badge}", fontsize=10, loc="left")
        rendered.append({"nativeHypothesisId": ident, "unchangedCurrentPeerId": peer,
            "peerIsProtectedAccepted": peer in protected, "originalConflict": conflict,
            "plotProjectedIntersectionSquareMeters": overlap.area,
            "qualification": "Projected visualization measurement; separate from original stage metric. No official overlap or legal boundary assertion."})
    fig.suptitle("Largest conflicts: UNACCEPTED native hypotheses versus current neighbors\n"
        "Six pairs from 30 preserved incident conflicts · ZERO new validated locations", fontsize=17)
    fig.legend(handles=[Line2D([0],[0],color="#1768c3",ls="--",label="Proposed native face (unaccepted)"),
        Line2D([0],[0],color="#19824e",label="Unchanged current neighbor"),
        Line2D([0],[0],color="#a2a9b2",ls=":",label="Prior current body"),
        Patch(facecolor="#f17670",label="Overlap requiring reconciliation")],
        loc="outside lower center",ncol=4,frameon=False)
    check_tree(spec)
    fig.savefig(args.output,dpi=150,facecolor="white")
    plt.close(fig)
    result = {"status":"RENDERED_PINNED_UNACCEPTED_JOINT_CONFLICT_ATLAS", "manifest":pin(args.manifest),
        "plot":pin(args.output),"panels":rendered,"credit":0,"currentAssetsChanged":False,
        "conflictsClippedOrRepaired":False,"officialOverlapAsserted":False,
        "browserContentRendered":False,"networkRequestsPerformed":False,"privateKeyRead":False}
    write_new(args.proof,result)
    print({"status":result["status"],"panels":len(rendered),"plot":result["plot"],"credit":0})


if __name__ == "__main__":
    main()
