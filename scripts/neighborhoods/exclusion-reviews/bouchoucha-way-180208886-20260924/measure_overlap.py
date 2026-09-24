"""Recompute the residential way's overlap with its two Bardo sector relations.

Run with the pinned Tunisia Geofabrik PBF as the positional argument. This
reports overlap relative to the residential way polygon; it does not validate
the completeness or accuracy of any boundary.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import osmium
from pyproj import Transformer
from shapely.geometry import LineString, Polygon
from shapely.ops import polygonize, transform, unary_union

WAY_ID = 180208886
RELATION_IDS = (7118028, 7118029)


class Relations(osmium.SimpleHandler):
    def __init__(self) -> None:
        super().__init__()
        self.rows: dict[int, dict] = {}

    def relation(self, relation) -> None:
        if relation.id in RELATION_IDS:
            self.rows[relation.id] = {
                "tags": dict(relation.tags),
                "members": [(member.type, member.ref, member.role)
                            for member in relation.members],
            }


class Ways(osmium.SimpleHandler):
    def __init__(self, required: set[int]) -> None:
        super().__init__()
        self.required = required
        self.rows: dict[int, dict] = {}

    def way(self, way) -> None:
        if way.id not in self.required:
            return
        points = [(node.location.lon, node.location.lat)
                  for node in way.nodes if node.location.valid()]
        self.rows[way.id] = {"tags": dict(way.tags), "points": points}


def main(pbf: Path) -> None:
    relation_reader = Relations()
    relation_reader.apply_file(str(pbf))
    if set(relation_reader.rows) != set(RELATION_IDS):
        raise ValueError("Pinned sector relations are missing from the source PBF")
    required = {WAY_ID}
    for relation in relation_reader.rows.values():
        required.update(ref for kind, ref, _ in relation["members"] if kind == "w")

    way_reader = Ways(required)
    way_reader.apply_file(str(pbf), locations=True)
    candidate = way_reader.rows.get(WAY_ID)
    if candidate is None:
        raise ValueError("Residential way is missing from the source PBF")
    tags = candidate["tags"]
    if (tags.get("landuse") != "residential"
            or tags.get("name:ar") != "بوشوشة"
            or tags.get("name:en") != "Bouchoucha"):
        raise ValueError("Residential way identity/tags differ from the reviewed evidence")

    candidate_polygon = Polygon(candidate["points"])
    if not candidate_polygon.is_valid or not candidate_polygon.exterior.is_ring:
        raise ValueError("Residential way does not form one valid closed polygon")
    project = Transformer.from_crs("EPSG:4326", "EPSG:32632", always_xy=True).transform
    candidate_m = transform(project, candidate_polygon)

    measurements = {}
    relation_tags = {}
    for relation_id in RELATION_IDS:
        relation = relation_reader.rows[relation_id]
        relation_tags[str(relation_id)] = relation["tags"]
        outer = []
        inner = []
        for kind, ref, role in relation["members"]:
            if kind != "w" or ref not in way_reader.rows:
                continue
            line = LineString(way_reader.rows[ref]["points"])
            if role == "outer":
                outer.append(line)
            elif role == "inner":
                inner.append(line)
            else:
                raise ValueError(f"Unexpected relation member role {role!r}")
        if inner:
            raise ValueError("Unexpected inner rings; this review expects only outer rings")
        polygons = list(polygonize(unary_union(outer)))
        sector = unary_union(polygons)
        if sector.is_empty or not sector.is_valid:
            raise ValueError(f"Relation {relation_id} did not form a valid polygon")
        sector_m = transform(project, sector)
        intersection_area = candidate_m.intersection(sector_m).area
        measurements[str(relation_id)] = {
            "sectorAreaKm2": sector_m.area / 1_000_000,
            "intersectionAreaKm2": intersection_area / 1_000_000,
            "candidateAreaOverlapPercent": 100 * intersection_area / candidate_m.area,
        }

    print(json.dumps({
        "sourcePbf": {
            "file": pbf.name,
            "sha256": hashlib.sha256(pbf.read_bytes()).hexdigest(),
        },
        "candidate": {
            "id": f"osm:way:{WAY_ID}",
            "tags": {key: tags[key] for key in ("landuse", "name", "name:ar", "name:en", "name:fr")
                     if key in tags},
            "closedNodeCount": len(candidate["points"]),
            "areaKm2": candidate_m.area / 1_000_000,
        },
        "method": {
            "projection": "EPSG:4326 to EPSG:32632",
            "sectorConstruction": "polygonize the source PBF outer member ways",
            "overlapDenominator": "residential way polygon area",
            "boundaryClaim": False,
        },
        "relations": relation_tags,
        "comparisons": measurements,
    }, ensure_ascii=True, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pbf", type=Path)
    main(parser.parse_args().pbf)
