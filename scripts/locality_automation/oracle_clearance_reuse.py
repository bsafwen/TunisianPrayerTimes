"""Serial call-scoped reuse of an unchanged oracle's pure clearance arithmetic.

No geometry, coordinates, arithmetic, comparison threshold, find result,
QA result, constructor, physical input read or acceptance gate is cached.
The exact immutable rings and typed query arguments identify each calculation.
Changing rings or the original radius constant forces a new calculation.
"""
from collections import OrderedDict
from contextlib import contextmanager
from functools import wraps
import math


def immutable_rings(value):
    return (type(value) is tuple and all(type(poly) is tuple and
            all(type(ring) is tuple and all(type(point) is tuple and len(point) == 2 and
                all(type(number) in (int, float) and math.isfinite(number) for number in point)
                for point in ring) for ring in poly) for poly in value))


@contextmanager
def reuse_clearance(oracle, max_entries=8192):
    if type(max_entries) is not int or max_entries <= 0:
        raise ValueError("Positive finite cache capacity required")
    if "clearance" in vars(oracle):
        raise ValueError("Oracle instance already has an explicit clearance override")
    original = oracle.clearance
    function = original.__func__
    cache, verified_rings = OrderedDict(), OrderedDict()
    stats = dict(hits=0, misses=0, bypasses=0, evictions=0, capacity=max_entries)

    @wraps(original)
    def wrapped(index, lat, lon, accuracy):
        if index not in oracle.rings:
            return original(index, lat, lon, accuracy)
        rings = oracle.rings[index]
        ident = id(rings)
        if ident not in verified_rings or verified_rings[ident] is not rings:
            if not immutable_rings(rings):
                stats["bypasses"] += 1
                return original(index, lat, lon, accuracy)
            verified_rings[ident] = rings
            if len(verified_rings) > max_entries:
                verified_rings.popitem(last=False)
        else:
            verified_rings.move_to_end(ident)
        arguments = (lat, lon, accuracy)
        if any(type(value) not in (int, float) or not math.isfinite(value) for value in arguments):
            stats["bypasses"] += 1
            return original(index, lat, lon, accuracy)
        radius = function.__globals__["MIN_MERIDIONAL_RADIUS_METERS"]
        key = (type(index), index, rings, tuple((type(value), value) for value in arguments), type(radius), radius)
        if key in cache:
            stats["hits"] += 1
            cache.move_to_end(key)
            return cache[key]
        stats["misses"] += 1
        # Original exceptions and arithmetic are preserved and never cached.
        result = original(index, lat, lon, accuracy)
        cache[key] = result
        if len(cache) > max_entries:
            cache.popitem(last=False)
            stats["evictions"] += 1
        return result

    oracle.clearance = wrapped
    try:
        yield stats
    finally:
        del oracle.clearance
        cache.clear()
        verified_rings.clear()
