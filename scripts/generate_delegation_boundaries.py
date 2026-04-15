#!/usr/bin/env python3

import json
import math
import re
import sys
import urllib.request
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parent.parent
GOUVERNORATS_PATH = REPO_ROOT / "android-app" / "app" / "src" / "main" / "assets" / "gouvernorats.json"
OUTPUT_PATH = REPO_ROOT / "android-app" / "app" / "src" / "main" / "assets" / "delegation_boundaries.json"

GEOB_META_URL = "https://www.geoboundaries.org/api/current/gbOpen/TUN/ADM2/"

ARABIC_DIRECTION_WORDS = {
    "الشمالية",
    "الجنوبية",
    "الشرقية",
    "الغربية",
    "المدينة",
}

LATIN_DIRECTION_WORDS = {
    "north",
    "south",
    "east",
    "west",
    "nord",
    "sud",
    "est",
    "ouest",
    "city",
}

SUBDISTRICT_SUFFIXES = {
    "الرياض",
    "جوهرة",
    "سيدي",
    "عبد",
    "الحميد",
}

SPECIAL_LATIN_TO_APP = {
    "carthage": "قرطاج",
    "bou salem": "بوسالم",
    "el hencha": "الحنشة",
    "menzel chaker": "منزل شاكر",
    "kerkennah": "قرقنة العطايا",
    "balta bouawene": "بلطة بوعوان",
}

SPECIAL_ARABIC_TO_APP = {
    "دوز الجنوبية": "دوز",
    "تطاوين الجنوبية": "تطاوين",
    "القلعة الخصباء": "القلعة الخصبة",
    "قفصة الشمالية": "قفصة",
    "قبلي الجنوبية": "قبلي",
    "سليانة الجنوبية": "سليانة",
    "سيدي بوزيد الشرقية": "سيدي بوزيد",
    "قابس الجنوبية": "قابس",
    "صفاقس الجنوبية": "صفاقس",
    "صفاقس الغربية": "صفاقس",
    "مدنين الشمالية": "مدنين",
    "القيروان الجنوبية": "القيروان",
    "القصرين الجنوبية": "القصرين",
    "باجة الجنوبية": "باجة",
    "الكاف الشرقية": "الكاف",
    "الكاف الغربية": "الكاف",
    "وادي اللیل": "واد الليل",
    "حي التضامن": "التظامن",
    "جبل الجلود": "جبل جلود",
    "الكبارية": "جبل جلود",
    "الصمار": "السمار",
    "مدنين االجنوبية": "مدنين",
    "قبلي الشمالية": "قبلي",
    "صفاقس المدينة": "صفاقس",
    "سوسة المدينة": "سوسة",
    "سوسة جوهرة": "سوسة",
}

UNTRUSTED_SPECIAL_LATIN = {"kerkennah"}


def normalize_arabic(value: str) -> str:
    value = value.strip()
    replacements = {
        "أ": "ا",
        "إ": "ا",
        "آ": "ا",
        "ى": "ي",
        "ؤ": "و",
        "ئ": "ي",
        "ة": "ه",
        "\u202c": "",
        "\u202b": "",
        "\u200f": "",
        "\u200e": "",
        "\u061c": "",
        "\ufeff": "",
    }
    for old, new in replacements.items():
        value = value.replace(old, new)
    value = re.sub(r"\s+", " ", value)
    return value


def normalize_latin(value: str) -> str:
    value = value.strip().lower()
    value = value.replace("â", "a").replace("é", "e").replace("è", "e")
    value = value.replace("\u202c", "").replace("\u202b", "")
    value = re.sub(r"\s+", " ", value)
    return value


SPECIAL_ARABIC_TO_APP = {
    normalize_arabic(key): normalize_arabic(value)
    for key, value in SPECIAL_ARABIC_TO_APP.items()
}

SPECIAL_LATIN_TO_APP = {
    normalize_latin(key): normalize_arabic(value)
    for key, value in SPECIAL_LATIN_TO_APP.items()
}


def strip_direction_words(value: str) -> str:
    tokens = normalize_arabic(value).split()
    filtered = [token for token in tokens if token not in ARABIC_DIRECTION_WORDS]
    return " ".join(filtered).strip()


def strip_latin_direction_words(value: str) -> str:
    tokens = normalize_latin(value).split()
    filtered = [token for token in tokens if token not in LATIN_DIRECTION_WORDS]
    return " ".join(filtered).strip()


def maybe_strip_subdistrict_suffix(value: str) -> str:
    tokens = normalize_arabic(value).split()
    if len(tokens) >= 2 and tokens[0] == "سوسه":
        filtered = [token for token in tokens if token not in SUBDISTRICT_SUFFIXES]
        return " ".join(filtered).strip()
    return normalize_arabic(value)


def haversine_km(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    radius = 6371.0
    d_lat = math.radians(lat2 - lat1)
    d_lng = math.radians(lng2 - lng1)
    a = (
        math.sin(d_lat / 2) ** 2
        + math.cos(math.radians(lat1))
        * math.cos(math.radians(lat2))
        * math.sin(d_lng / 2) ** 2
    )
    return radius * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def ring_centroid(ring):
    points = ring if ring[0] == ring[-1] else ring + [ring[0]]
    area_twice = 0.0
    centroid_x = 0.0
    centroid_y = 0.0
    for (x1, y1), (x2, y2) in zip(points, points[1:]):
        cross = x1 * y2 - x2 * y1
        area_twice += cross
        centroid_x += (x1 + x2) * cross
        centroid_y += (y1 + y2) * cross
    if abs(area_twice) < 1e-12:
        xs = [point[0] for point in ring]
        ys = [point[1] for point in ring]
        return sum(xs) / len(xs), sum(ys) / len(ys), 0.0
    area = area_twice * 0.5
    return centroid_x / (6 * area), centroid_y / (6 * area), abs(area)


def normalize_ring(ring):
    if len(ring) >= 2 and ring[0] == ring[-1]:
        return ring[:-1]
    return ring


def geometry_centroid(geometry):
    polygons = [geometry["coordinates"]] if geometry["type"] == "Polygon" else geometry["coordinates"]
    total_area = 0.0
    centroid_x = 0.0
    centroid_y = 0.0
    for polygon in polygons:
        x, y, area = ring_centroid(polygon[0])
        total_area += area
        centroid_x += x * area
        centroid_y += y * area
    if total_area == 0.0:
        return polygons[0][0][0]
    return centroid_x / total_area, centroid_y / total_area


def point_on_segment(point_lng, point_lat, start_lng, start_lat, end_lng, end_lat):
    if start_lng == end_lng and start_lat == end_lat:
        return point_lng == start_lng and point_lat == start_lat
    cross = (point_lat - start_lat) * (end_lng - start_lng) - (point_lng - start_lng) * (end_lat - start_lat)
    if abs(cross) > 1e-10:
        return False
    dot = (point_lng - start_lng) * (end_lng - start_lng) + (point_lat - start_lat) * (end_lat - start_lat)
    if dot < 0:
        return False
    squared_length = (end_lng - start_lng) ** 2 + (end_lat - start_lat) ** 2
    return dot <= squared_length


def point_in_ring(point_lng, point_lat, ring):
    ring = normalize_ring(ring)
    inside = False
    for (lng1, lat1), (lng2, lat2) in zip(ring, ring[1:] + ring[:1]):
        if point_on_segment(point_lng, point_lat, lng1, lat1, lng2, lat2):
            return True
        intersects = ((lat1 > point_lat) != (lat2 > point_lat)) and (
            point_lng < (lng2 - lng1) * (point_lat - lat1) / ((lat2 - lat1) or 1e-20) + lng1
        )
        if intersects:
            inside = not inside
    return inside


def point_in_polygon(point_lng, point_lat, polygon):
    if not point_in_ring(point_lng, point_lat, polygon[0]):
        return False
    for hole in polygon[1:]:
        if point_in_ring(point_lng, point_lat, hole):
            return False
    return True


def point_in_geometry(point_lng, point_lat, geometry):
    if geometry["type"] == "Polygon":
        return point_in_polygon(point_lng, point_lat, geometry["coordinates"])
    if geometry["type"] == "MultiPolygon":
        return any(point_in_polygon(point_lng, point_lat, polygon) for polygon in geometry["coordinates"])
    raise ValueError(f"Unsupported geometry type: {geometry['type']}")


def compute_bbox(geometry):
    polygons = [geometry["coordinates"]] if geometry["type"] == "Polygon" else geometry["coordinates"]
    longitudes = []
    latitudes = []
    for polygon in polygons:
        for ring in polygon:
            for lng, lat in ring:
                longitudes.append(lng)
                latitudes.append(lat)
    return [min(longitudes), min(latitudes), max(longitudes), max(latitudes)]


def load_app_delegations():
    data = json.loads(GOUVERNORATS_PATH.read_text())
    delegations = []
    by_ar = {}
    by_lat = {}
    for gouvernorat in data["gouvernorats"]:
        for delegation in gouvernorat["delegations"]:
            item = {
                "id": delegation["id"],
                "nomAr": delegation["nomAr"],
                "nomFr": delegation["nomFr"],
                "nomEn": delegation["nomEn"],
                "lat": delegation["lat"],
                "lng": delegation["lng"],
            }
            delegations.append(item)
            by_ar[normalize_arabic(item["nomAr"])] = item
            by_lat[normalize_latin(item["nomFr"])] = item
            by_lat[normalize_latin(item["nomEn"])] = item
    return delegations, by_ar, by_lat


def load_geojson():
    metadata = json.load(urllib.request.urlopen(GEOB_META_URL))
    geojson = json.load(urllib.request.urlopen(metadata["simplifiedGeometryGeoJSON"]))
    return metadata, geojson


def resolve_by_name(shape_name, by_ar, by_lat):
    arabic = normalize_arabic(shape_name)
    if arabic in by_ar:
        return by_ar[arabic], True

    if arabic in SPECIAL_ARABIC_TO_APP:
        return by_ar.get(normalize_arabic(SPECIAL_ARABIC_TO_APP[arabic])), True

    latin = normalize_latin(shape_name)
    if latin in SPECIAL_LATIN_TO_APP:
        return by_ar.get(normalize_arabic(SPECIAL_LATIN_TO_APP[latin])), latin not in UNTRUSTED_SPECIAL_LATIN
    if latin in by_lat:
        return by_lat[latin], True

    stripped_arabic = strip_direction_words(shape_name)
    if stripped_arabic in by_ar:
        return by_ar[stripped_arabic], True

    stripped_sousse = maybe_strip_subdistrict_suffix(shape_name)
    if stripped_sousse in by_ar:
        return by_ar[stripped_sousse], True

    stripped_latin = strip_latin_direction_words(shape_name)
    if stripped_latin in SPECIAL_LATIN_TO_APP:
        return by_ar.get(normalize_arabic(SPECIAL_LATIN_TO_APP[stripped_latin])), stripped_latin not in UNTRUSTED_SPECIAL_LATIN
    if stripped_latin in by_lat:
        return by_lat[stripped_latin], True

    return None, False


def build_boundaries():
    app_delegations, by_ar, by_lat = load_app_delegations()
    metadata, geojson = load_geojson()

    boundaries = []
    unresolved = []

    for feature in geojson["features"]:
        geometry = feature["geometry"]
        shape_name = feature["properties"]["shapeName"]
        bbox = compute_bbox(geometry)

        candidate_ids = []
        resolved, trusted_resolution = resolve_by_name(shape_name, by_ar, by_lat)
        if resolved is not None:
            candidate_ids.append(resolved["id"])

        if not trusted_resolution:
            for delegation in app_delegations:
                if point_in_geometry(delegation["lng"], delegation["lat"], geometry):
                    candidate_ids.append(delegation["id"])

        if not candidate_ids:
            centroid_lng, centroid_lat = geometry_centroid(geometry)
            nearest = min(
                app_delegations,
                key=lambda delegation: haversine_km(centroid_lat, centroid_lng, delegation["lat"], delegation["lng"]),
            )
            candidate_ids.append(nearest["id"])
            unresolved.append((shape_name, nearest["nomAr"], haversine_km(centroid_lat, centroid_lng, nearest["lat"], nearest["lng"])))

        boundaries.append(
            {
                "shapeName": shape_name,
                "candidateDelegationIds": sorted(set(candidate_ids)),
                "bbox": [round(value, 6) for value in bbox],
                "geometry": geometry,
            }
        )

    result = {
        "source": {
            "provider": "GeoBoundaries",
            "dataset": metadata["boundaryID"],
            "downloadUrl": metadata["simplifiedGeometryGeoJSON"],
            "license": metadata["boundaryLicense"],
        },
        "boundaries": boundaries,
    }

    OUTPUT_PATH.write_text(json.dumps(result, ensure_ascii=False, separators=(",", ":")))

    print(f"Wrote {len(boundaries)} boundaries to {OUTPUT_PATH}")
    if unresolved:
        print("Fallback centroid mappings:")
        for shape_name, delegated_name, distance in sorted(unresolved, key=lambda item: item[2], reverse=True):
            print(f"  {distance:.3f} km | {shape_name} -> {delegated_name}")


if __name__ == "__main__":
    try:
        build_boundaries()
    except Exception as exc:
        print(f"Failed to generate boundaries: {exc}", file=sys.stderr)
        raise