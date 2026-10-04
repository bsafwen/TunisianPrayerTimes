"""Versioned exact-operation ring evaluator for isolated replay experiments.

Precompute query-independent segment values with the original Python arithmetic
and math.hypot. NumPy operations retain the original per-segment operation order;
no clearance reuse, geometry repair, tolerances or source gates are changed.
"""
import importlib.util
import math
from pathlib import Path
import numpy as np


class VectorRingMembership:
    def __init__(self, original):
        self.original = original
        self.cache = {}

    def __call__(self, ring, lat, lon, tolerance):
        # The packed decoder returns immutable tuples. Preserve original behavior
        # for mutable caller inputs rather than caching a stale coordinate array.
        if not isinstance(ring, tuple):
            return self.original(ring, lat, lon, tolerance)
        key = id(ring)
        saved = self.cache.get(key)
        if saved is None:
            if any(not isinstance(point, tuple) or any(type(value) not in (int, float) for value in point) for point in ring):
                return self.original(ring, lat, lon, tolerance)
            rows = []
            previous_x, previous_y = ring[0]
            for x, y in (*ring[1:], ring[0]):
                dx, dy = x - previous_x, y - previous_y
                rows.append((previous_x, previous_y, x, y, dx, dy,
                    min(previous_x, x), max(previous_x, x), min(previous_y, y), max(previous_y, y),
                    math.hypot(dx, dy)))
                previous_x, previous_y = x, y
            values = np.asarray(rows, dtype=np.float64).T.copy()
            values.setflags(write=False)
            # Keep the immutable tuple alive so an object-id cannot be reused.
            saved = (ring, values)
            self.cache[key] = saved
        elif saved[0] is not ring:
            raise ValueError("Ring cache identity changed")
        px, py, x, y, dx, dy, lo_x, hi_x, lo_y, hi_y, lengths = saved[1]
        cross = (lon - px) * dy - (lat - py) * dx
        on_edge = np.any((lo_x - tolerance <= lon) & (lon <= hi_x + tolerance)
            & (lo_y - tolerance <= lat) & (lat <= hi_y + tolerance)
            & (np.abs(cross) <= tolerance * lengths))
        crossing = (py > lat) != (y > lat)
        # The original short circuit never divides a horizontal segment by zero.
        intersections = dx[crossing] * (lat - py[crossing]) / dy[crossing] + px[crossing]
        inside = np.count_nonzero(lon < intersections) % 2 != 0
        return bool(inside), bool(on_edge)


def isolated_packed_module(path):
    """Load original decoder/class code in a private module namespace."""
    loader = importlib.util.spec_from_file_location("isolated_vector_packed_replay", Path(path))
    module = importlib.util.module_from_spec(loader)
    loader.loader.exec_module(module)
    module._ring_membership = VectorRingMembership(module._ring_membership)
    return module
