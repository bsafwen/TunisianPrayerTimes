#!/usr/bin/env python3
"""
Town extents for the zone radii M3 sets "from the town extents" (plan 8.8, 11): Kosovo's seven towns and the Preševo
Valley's three municipalities (`xk_bik`), and vaktija.ba's five Montenegrin towns (`bosnia_iz`).

The boundaries are OpenStreetMap's (© OpenStreetMap contributors, ODbL), read once through Nominatim's lookup by OSM id
with the collectors' Session (paced, the system proxy, TLS verified) and kept, never committed, in
test-data-local/research-2026-10-16/osm/ with a SHA-256 manifest. A town is its own administrative boundary (OSM's
town, city or settlement boundary); a municipality its municipal boundary. Two towns are only points in OSM (Podujevë
and Gusinje): their extent is estimated from the others (below).

A zone's radius is measured from the zone's own point:
- `circumscribed_km`: the farthest boundary vertex, so every point inside the town lies within it;
- `equal_area_km`: the radius of a disc of the boundary's area.

    python scripts/prayer_formula/fits/town_extents.py            # prints the extents
    python scripts/prayer_formula/fits/town_extents.py --fetch    # reads the boundaries (once; needs the network)
"""

from __future__ import annotations

import json
import math
import sys
import urllib.parse
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import common  # noqa: E402

DATA = common.LOCAL_DATA / "research-2026-10-16" / "osm"
KM_PER_DEG = 111.2

# file: {key: (OSM object, country, what it is)}. Ids from Nominatim's search and reverse lookups of 2026-10-16.
GROUPS = {
    "nominatim_lookup_balkan_towns.json": {
        # Kosovo's seven towns of the takvim's «Diferenca e vendeve» (Preševo in Serbia).
        "dragash": ("R8600255", "XK", "town"), "ferizaj": ("R8598644", "XK", "town"), "gjilan": ("R9046423", "XK", "town"),
        "prishtina": ("R19425413", "XK", "town"), "podujeve": ("N299447753", "XK", "town"),
        "vushtrri": ("R9186240", "XK", "town"), "presevo": ("R6654047", "RS", "town"),
        # The Preševo Valley: the municipalities of Preševo, Bujanovac and Medveđa, and their two other towns.
        "bujanovac": ("R6656122", "RS", "town"), "medvedja": ("R7204916", "RS", "town"),
        "presevo-municipality": ("R1630000", "RS", "municipality"),
        "bujanovac-municipality": ("R1629999", "RS", "municipality"),
        "medvedja-municipality": ("R2860306", "RS", "municipality"),
        # vaktija.ba's five Montenegrin towns.
        "pljevlja": ("W1255491789", "ME", "town"), "bijelo-polje": ("R12434568", "ME", "town"),
        "gusinje": ("N1927167879", "ME", "town"), "plav": ("W1255501570", "ME", "town"), "rozaje": ("R18371630", "ME", "town"),
    },
    "nominatim_lookup_valley_neighbours.json": {
        # The Serbian municipalities around the Valley (Kosovo and North Macedonia have their own defaults, 6.4).
        "vranje-city": ("R2190516", "RS", "neighbour"), "trgoviste-municipality": ("R2190449", "RS", "neighbour"),
        "lebane-municipality": ("R2860305", "RS", "neighbour"), "bojnik-municipality": ("R2860304", "RS", "neighbour"),
        "kursumlija-municipality": ("R2863768", "RS", "neighbour"),
    },
}
OBJECTS = {key: value for group in GROUPS.values() for key, value in group.items()}


def collectors_module():
    """The collectors' plumbing (scripts/collectors/common.py), loaded under its own name beside the fits' common."""
    import importlib.util
    spec = importlib.util.spec_from_file_location("collectors_common", common.ROOT / "scripts" / "collectors" / "common.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def fetch() -> list[Path]:
    """Each group's lookup not saved yet, one request per group."""
    collectors = collectors_module()
    session = collectors.Session(root=DATA.parent)
    saved = []
    for file, objects in GROUPS.items():
        if (DATA / file).exists():
            continue
        url = "https://nominatim.openstreetmap.org/lookup?" + urllib.parse.urlencode(
            {"osm_ids": ",".join(v[0] for v in objects.values()), "format": "jsonv2", "polygon_geojson": 1,
             "polygon_threshold": 0.0001, "extratags": 1})
        content = session.fetch(url, headers={"Accept-Language": "en"})
        path = session.save("osm", file, content, url, "OpenStreetMap boundaries (ODbL) of the Balkan towns and municipalities "
                            "of plan 8.8, for the zone radii of xk_bik and bosnia_iz (fits/town_extents.py)")
        if path:
            saved.append(path)
    return saved


def load() -> dict[str, dict]:
    """key -> {"centre": (lat, lng), "rings": [[(lat, lng), ...], ...] (outer rings; none for a point), "population",
    "name", "osm"}."""
    by_id = {}
    for file in GROUPS:
        for r in json.loads((DATA / file).read_text(encoding="utf-8")):
            by_id[f"{r['osm_type'][0].upper()}{r['osm_id']}"] = r
    out = {}
    for key, (osm, cc, kind) in OBJECTS.items():
        r = by_id[osm]
        geo = r.get("geojson") or {}
        rings = []
        if geo.get("type") == "Polygon":
            rings = [geo["coordinates"][0]]
        elif geo.get("type") == "MultiPolygon":
            rings = [poly[0] for poly in geo["coordinates"]]
        out[key] = {"centre": (float(r["lat"]), float(r["lon"])), "rings": [[(y, x) for x, y in ring] for ring in rings],
                    "population": int((r.get("extratags") or {}).get("population", "0").replace(",", "") or 0),
                    "name": r.get("name") or r.get("display_name", "").split(",")[0], "osm": osm, "country": cc, "kind": kind}
    return out


def _xy(point, origin):
    return ((point[1] - origin[1]) * KM_PER_DEG * math.cos(math.radians(origin[0])), (point[0] - origin[0]) * KM_PER_DEG)


def km(a, b) -> float:
    x, y = _xy(a, b)
    return math.hypot(x, y)


def extent(point, rings) -> dict:
    """The boundary seen from [point]: the farthest vertex, the area and the equal-area radius (local plane, km)."""
    if not rings:
        return {"circumscribed_km": None, "area_km2": None, "equal_area_km": None}
    far, area = 0.0, 0.0
    for ring in rings:
        xy = [_xy(p, point) for p in ring]
        far = max(far, max(math.hypot(x, y) for x, y in xy))
        area += abs(sum(xy[i][0] * xy[i - 1][1] - xy[i - 1][0] * xy[i][1] for i in range(len(xy)))) / 2
    return {"circumscribed_km": far, "area_km2": area, "equal_area_km": math.sqrt(area / math.pi)}


def inside(point, rings) -> bool:
    """Even-odd rule over the outer rings."""
    lat, lng = point
    hit = False
    for ring in rings:
        for i in range(len(ring)):
            (y1, x1), (y2, x2) = ring[i - 1], ring[i]
            if (y1 > lat) != (y2 > lat) and lng < x1 + (lat - y1) * (x2 - x1) / (y2 - y1):
                hit = not hit
    return hit


def round_up(x: float, step: float = 0.5) -> float:
    return math.ceil(x / step - 1e-9) * step


def main(argv: list[str]) -> int:
    if "--fetch" in argv:
        for path in fetch():
            print(f"saved {path}")
    towns = load()
    print(f"{'key':24} {'osm':12} {'pop':>7}  centre               farthest  area km2  equal-area")
    for key, t in towns.items():
        e = extent(t["centre"], t["rings"])
        far = "-" if e["circumscribed_km"] is None else f"{e['circumscribed_km']:6.2f}"
        area = "-" if e["area_km2"] is None else f"{e['area_km2']:7.1f}"
        eq = "-" if e["equal_area_km"] is None else f"{e['equal_area_km']:6.2f}"
        print(f"{key:24} {t['osm']:12} {t['population']:7d}  {t['centre'][0]:.5f} {t['centre'][1]:.5f}  {far:>8} {area:>9} {eq:>10}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
