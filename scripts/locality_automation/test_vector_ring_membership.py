"""Adversarial segment/edge and hole behavior against the unchanged scalar code."""
import math
from pathlib import Path
import random
import unittest
from scripts.locality_automation import packed_gps_replay as original
from scripts.locality_automation.vector_ring_membership_v1 import VectorRingMembership, isolated_packed_module


class RingEquivalenceTest(unittest.TestCase):
    def test_closed_unclosed_repeated_and_exact_near_edges(self):
        vector = VectorRingMembership(original._ring_membership)
        base = ((8., 35.), (9., 35.), (9., 36.), (8., 36.))
        for ring in (base, base + (base[0],), base + (base[-1], base[0], base[0])):
            for epsilon in (0., 1e-10):
                for x, y in ((8.5, 35.5), (8., 35.), (9., 35.5), (7.9, 35.5),
                        (math.nextafter(9., math.inf), 35.5), (9. + 1e-10, 35.5), (9. - 1e-10, 35.5)):
                    self.assertEqual(original._ring_membership(ring, y, x, epsilon), vector(ring, y, x, epsilon))

    def test_deterministic_irregular_rings_and_nonzero_tolerance(self):
        random_source = random.Random(72017)
        vector = VectorRingMembership(original._ring_membership)
        for _ in range(12):
            points = sorted((random_source.uniform(0, 2 * math.pi), random_source.uniform(.05, .2)) for _ in range(31))
            ring = tuple((8.5 + radius * math.cos(angle), 35.5 + radius * math.sin(angle)) for angle, radius in points)
            ring += (ring[0],)
            for x, y in ring + tuple((random_source.uniform(8.2, 8.8), random_source.uniform(35.2, 35.8)) for _ in range(50)):
                for tolerance in (0., 1e-10, 1e-7):
                    self.assertEqual(original._ring_membership(ring, y, x, tolerance), vector(ring, y, x, tolerance))

    def test_original_outer_hole_and_multipolygon_semantics(self):
        module = isolated_packed_module(Path(original.__file__))
        outer = ((8., 35.), (9., 35.), (9., 36.), (8., 36.), (8., 35.))
        hole = ((8.2, 35.2), (8.4, 35.2), (8.4, 35.4), (8.2, 35.4), (8.2, 35.2))
        polygons = ((outer, hole), (tuple((x + 2, y) for x, y in outer),))
        for x, y in ((8., 35.), (8.3, 35.3), (8.2, 35.3), (8.6, 35.6), (10.5, 35.5), (7., 35.)):
            for include_boundary in (True, False):
                for tolerance in (0., 1e-10):
                    kwargs = {"include_boundary": include_boundary, "boundary_tolerance": tolerance}
                    self.assertEqual(original.contains_geometry(polygons, y, x, **kwargs), module.contains_geometry(polygons, y, x, **kwargs))

    def test_mutable_inputs_use_original_without_stale_cache(self):
        ring = [(8., 35.), (9., 35.), (9., 36.), (8., 36.)]
        vector = VectorRingMembership(original._ring_membership)
        vector(ring, 35.5, 8.5, 0.)
        ring[1] = (8.1, 35.)
        self.assertEqual(original._ring_membership(ring, 35.5, 8.5, 0.), vector(ring, 35.5, 8.5, 0.))
        self.assertEqual(vector.cache, {})


if __name__ == "__main__":
    unittest.main()
