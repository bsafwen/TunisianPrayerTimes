"""Correct only ordinary conflict strictness in the pinned independent oracle.

The original oracle treats tolerance-close interior points as strict interiors
when suppressing conflicting candidates. Android explicitly excludes its
1e-10-degree edge tolerance in ordinary strict containment. All candidate,
accuracy, clearance, country and tie-break computations remain original.
"""
from shapely.geometry import Point


ORDINARY_EDGE_TOLERANCE_DEGREES = 1e-10


def strict_ordinary(geometry, point):
    return geometry.contains(point) and geometry.boundary.distance(point) > ORDINARY_EDGE_TOLERANCE_DEGREES


class OrdinaryEdgeOracleV2:
    def __init__(self, original):
        self.original = original
        self.replay = original.replay
        self.by_id = {row["id"]: index for index, row in self.replay.boundaries.items()}

    def find(self, lat, lon, accuracy):
        result, near = self.original.find(lat, lon, accuracy)
        if accuracy is not None:
            return result, near
        candidates = {self.by_id[ident] for ident in result["candidateIds"]}
        point, suppressed = Point(lon, lat), set()
        strict = {}
        def inside(index):
            if index not in strict:
                strict[index] = strict_ordinary(self.original.geoms[index], point)
            return strict[index]
        for first, second in self.replay.conflicts:
            if first in candidates and second in candidates and inside(first) and inside(second):
                suppressed.update((first, second))
        available = candidates - suppressed
        winner = min(available, key=lambda i: (self.replay.boundaries[i]["areaKm2"], self.replay.boundaries[i]["id"])) if available else None
        return {"winnerId": self.replay.boundaries[winner]["id"] if winner is not None else None,
            "candidateIds": result["candidateIds"], "qualifiedIds": result["qualifiedIds"],
            "suppressedIds": sorted(self.replay.boundaries[i]["id"] for i in suppressed)}, near
