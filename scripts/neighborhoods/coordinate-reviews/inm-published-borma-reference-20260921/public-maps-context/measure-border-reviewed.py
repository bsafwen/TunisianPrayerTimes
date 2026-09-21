#!/usr/bin/env python3
import hashlib
import json
import math
from pathlib import Path

from pyproj import Geod, Transformer
from shapely.geometry import MultiPolygon, Point, Polygon, shape
from shapely.ops import nearest_points
from shapely.ops import transform as shapely_transform

EXPECTED_SOURCE_SHA256 = "346a79dca9013b427cf7ce4e11fb31ca03fa575ee7a68c865701c463c504a1ce"
SOURCE_REL = Path("geo") / "osm-areas.json"
REPORT_NAME = "border-measurement.json"
EXPECTED_PARENT_NAME = "borma-border-context-20260921"

ADMIN_LEVELS = {"2", "4", "6", "8"}
COUNTRY_ID_FORMS = {"osm:relation:192757", "relation:192757", "192757"}

COUNTRY_TAG_KEYS = (
    "admin_level",
    "name",
    "name:en",
    "name:ar",
    "name:fr",
    "ISO3166-1",
    "ISO3166-1:alpha2",
    "ref:INS",
)

ADMIN_TAG_KEYS = (
    "name:ar",
    "name:fr",
    "name",
    "name:en",
    "ref:INS",
)

POINTS = (
    ("current", 31.6916, 9.2043),
    ("proposed", 31.69, 9.211),
    ("catalogPoint", 31.6880101, 9.2186132),
)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


def normalize_admin_level(value):
    if value is None or isinstance(value, bool):
        return None
    return str(value).strip()


def tag_text(tags, key):
    value = tags.get(key)
    if isinstance(value, str):
        return value.strip()
    return value


def has_admin_level(tags, level: str) -> bool:
    return normalize_admin_level(tags.get("admin_level")) == level


def is_tunisia_country_tags(tags) -> bool:
    if not has_admin_level(tags, "2"):
        return False
    name = tag_text(tags, "name")
    name_en = tag_text(tags, "name:en")
    iso_1 = tag_text(tags, "ISO3166-1")
    iso_1_alpha2 = tag_text(tags, "ISO3166-1:alpha2")
    name_ok = name == "Tunisia" or name_en == "Tunisia"
    iso_1_ok = isinstance(iso_1, str) and iso_1.upper() == "TN"
    iso_1_alpha2_ok = isinstance(iso_1_alpha2, str) and iso_1_alpha2.upper() == "TN"
    return name_ok or iso_1_ok or iso_1_alpha2_ok


def country_id_match(value) -> bool:
    if isinstance(value, bool):
        return False
    if isinstance(value, int):
        return value == 192757
    if isinstance(value, float):
        return value.is_integer() and int(value) == 192757
    if isinstance(value, str):
        return value.strip() in COUNTRY_ID_FORMS
    return False


def geometry_from_geojson(value):
    if not isinstance(value, dict):
        raise ValueError("geometry is not a GeoJSON object")
    if value.get("type") == "Feature":
        value = value.get("geometry")
    if not isinstance(value, dict):
        raise ValueError("GeoJSON Feature has no geometry object")
    return shape(value)


def valid_polygonal_geometry(geom) -> bool:
    return (
        isinstance(geom, (Polygon, MultiPolygon))
        and not geom.is_empty
        and geom.is_valid
        and geom.area > 0
    )


def haversine_m(lat1, lon1, lat2, lon2):
    radius_m = 6371008.8
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)
    a = (
        math.sin(dphi / 2.0) ** 2
        + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2.0) ** 2
    )
    a = min(1.0, max(0.0, a))
    return 2.0 * radius_m * math.asin(math.sqrt(a))


def main():
    script_path = Path(__file__).resolve()
    base_dir = script_path.parent
    if base_dir.name != EXPECTED_PARENT_NAME:
        raise RuntimeError(
            f"Script parent directory must be {EXPECTED_PARENT_NAME}, got {base_dir.name}"
        )

    source_path = base_dir.parent / SOURCE_REL
    report_path = base_dir / REPORT_NAME

    if report_path.exists():
        raise FileExistsError(f"Refusing to overwrite existing report: {report_path}")
    if not source_path.is_file():
        raise FileNotFoundError(f"Source cache not found: {source_path}")

    script_sha256 = sha256_file(script_path)

    source_before = source_path.read_bytes()
    source_sha256_before = sha256_bytes(source_before)
    if source_sha256_before != EXPECTED_SOURCE_SHA256:
        raise RuntimeError(
            "Source cache SHA256 mismatch: "
            f"expected {EXPECTED_SOURCE_SHA256}, got {source_sha256_before}"
        )

    data = json.loads(source_before.decode("utf-8"))
    if not isinstance(data, dict):
        raise RuntimeError("Source JSON root must be an object")
    areas = data.get("areas")
    if not isinstance(areas, list):
        raise RuntimeError("Source JSON must contain an 'areas' array")
    areas_total = len(areas)

    country_id_matches = []
    country_valid_matches = []
    for row in areas:
        if not isinstance(row, dict):
            continue
        if country_id_match(row.get("id")):
            country_id_matches.append(row)
            tags = row.get("tags")
            if isinstance(tags, dict) and is_tunisia_country_tags(tags):
                country_valid_matches.append(row)

    if len(country_valid_matches) != 1:
        if not country_id_matches:
            raise RuntimeError("No area with accepted Tunisia country id forms found")
        raise RuntimeError(
            "Expected exactly one Tunisia country area; "
            f"found {len(country_valid_matches)} valid among {len(country_id_matches)} id matches"
        )

    country_row = country_valid_matches[0]
    country_tags = country_row.get("tags")
    if not isinstance(country_tags, dict):
        country_tags = {}

    try:
        country_geom = geometry_from_geojson(country_row.get("geometry"))
    except Exception as exc:
        raise RuntimeError("Selected country geometry is not valid GeoJSON") from exc
    if not valid_polygonal_geometry(country_geom):
        raise RuntimeError(
            "Selected country geometry must be a valid nonempty Polygon or MultiPolygon"
        )

    admin_areas = []
    invalid_admin_geometries = 0
    for row in areas:
        if not isinstance(row, dict):
            continue
        tags = row.get("tags")
        if not isinstance(tags, dict):
            continue
        level = normalize_admin_level(tags.get("admin_level"))
        if level not in ADMIN_LEVELS:
            continue
        try:
            geom = geometry_from_geojson(row.get("geometry"))
        except Exception:
            invalid_admin_geometries += 1
            continue
        if not valid_polygonal_geometry(geom):
            invalid_admin_geometries += 1
            continue
        admin_areas.append(
            {
                "id": row.get("id"),
                "tags": tags,
                "geom": geom,
                "admin_level": level,
            }
        )

    to_utm = Transformer.from_crs("EPSG:4326", "EPSG:32632", always_xy=True)
    from_utm = Transformer.from_crs("EPSG:32632", "EPSG:4326", always_xy=True)
    country_boundary_utm = shapely_transform(to_utm.transform, country_geom.boundary)

    def admin_match_object(area):
        tags = area["tags"]
        obj = {
            "id": area["id"],
            "admin_level": tags.get("admin_level"),
        }
        for key in ADMIN_TAG_KEYS:
            obj[key] = tags.get(key)
        return obj

    measurements = []
    for name, lat, lng in POINTS:
        point_wgs84 = Point(lng, lat)
        country_covers = bool(country_geom.covers(point_wgs84))

        x_utm, y_utm = to_utm.transform(lng, lat)
        point_utm = Point(x_utm, y_utm)
        distance_m = float(point_utm.distance(country_boundary_utm))
        nearest_pt_utm = nearest_points(point_utm, country_boundary_utm)[1]
        nearest_lng, nearest_lat = from_utm.transform(nearest_pt_utm.x, nearest_pt_utm.y)

        matches = []
        for area in admin_areas:
            if area["geom"].covers(point_wgs84):
                matches.append(admin_match_object(area))
        matches.sort(
            key=lambda item: (str(item.get("admin_level")), str(item.get("id")))
        )

        measurements.append(
            {
                "name": name,
                "lat": float(lat),
                "lng": float(lng),
                "country_covers": country_covers,
                "distance_to_country_boundary_m": distance_m,
                "nearest_boundary_point": {
                    "lat": float(nearest_lat),
                    "lng": float(nearest_lng),
                },
                "matching_admin_levels": matches,
            }
        )

    current_lat = POINTS[0][1]
    current_lng = POINTS[0][2]
    proposed_lat = POINTS[1][1]
    proposed_lng = POINTS[1][2]
    current_proposed_haversine_m = haversine_m(
        current_lat, current_lng, proposed_lat, proposed_lng
    )
    geod = Geod(ellps="WGS84")
    _, _, current_proposed_geod_m = geod.inv(
        current_lng, current_lat, proposed_lng, proposed_lat
    )

    country_bbox_wgs84 = [float(value) for value in country_geom.bounds]
    country_tags_subset = {key: country_tags.get(key) for key in COUNTRY_TAG_KEYS}

    report = {
        "report": "border-measurement",
        "source_id": "source476",
        "place": "El Borma",
        "status": "unresolved",
        "source_file": str(SOURCE_REL),
        "source_sha256": source_sha256_before,
        "source_sha256_expected": EXPECTED_SOURCE_SHA256,
        "script_sha256": script_sha256,
        "script_file": script_path.name,
        "geographicApproval": False,
        "boundaryVerified": False,
        "crs": {
            "geographic": "EPSG:4326",
            "projected": "EPSG:32632",
            "projection": "pyproj Transformer always_xy=True",
            "distance_method": "Euclidean distance in EPSG:32632 to the selected country boundary",
        },
        "method_note": (
            "Measurements use cached OSM area geometry. This is not an official surveyed boundary "
            "and no geographic approval is implied. Distances are numerical meters in EPSG:32632; "
            "nearest boundary points are inverse-projected to EPSG:4326. No conclusion is made about "
            "which country is truly correct."
        ),
        "country": {
            "id": country_row.get("id"),
            "tags_subset": country_tags_subset,
            "bbox_wgs84": country_bbox_wgs84,
            "bbox_order": "min_lng,min_lat,max_lng,max_lat",
        },
        "per_point_measurements": measurements,
        "current_proposed_distance_m": {
            "haversine_m": float(current_proposed_haversine_m),
            "geod_m": float(current_proposed_geod_m),
            "haversine_radius_m": 6371008.8,
            "geod_model": "WGS84",
        },
        "counts": {
            "areas_total": areas_total,
            "accepted_country_id_matches": len(country_id_matches),
            "valid_country_matches": len(country_valid_matches),
            "admin_areas_considered": len(admin_areas),
            "invalid_admin_geometries_skipped": invalid_admin_geometries,
            "matching_admin_levels_by_point": {
                item["name"]: len(item["matching_admin_levels"])
                for item in measurements
            },
        },
    }

    source_after = source_path.read_bytes()
    source_sha256_after = sha256_bytes(source_after)
    if source_sha256_after != source_sha256_before:
        raise RuntimeError("Source cache changed during computation; refusing to write report")
    report["source_unchanged"] = True
    report["source_sha256_after"] = source_sha256_after

    with open(report_path, "x", encoding="utf-8") as handle:
        json.dump(report, handle, indent=2, ensure_ascii=False)
        handle.write("\n")

    print(
        f"areas={areas_total} "
        f"country_id_matches={len(country_id_matches)} "
        f"valid_country={len(country_valid_matches)} "
        f"admin_areas={len(admin_areas)} "
        f"invalid_admin_geoms_skipped={invalid_admin_geometries}"
    )
    admin_counts = " ".join(
        f"{item['name']}={len(item['matching_admin_levels'])}" for item in measurements
    )
    print(f"admin_matches {admin_counts}")
    boundary_distances = " ".join(
        f"{item['name']}={item['distance_to_country_boundary_m']:.6f}"
        for item in measurements
    )
    print(f"country_boundary_distance_m {boundary_distances}")
    print(f"current_proposed_haversine_m={current_proposed_haversine_m:.6f}")
    print(f"current_proposed_geod_m={current_proposed_geod_m:.6f}")


if __name__ == "__main__":
    main()
