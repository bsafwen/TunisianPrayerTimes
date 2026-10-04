import unittest

import numpy as np
from shapely.affinity import affine_transform
from shapely.geometry import LineString

from scripts.locality_automation.compare_isie_neighbor_linework import (
    affine_coefficients, inverse_matrix, proximity,
)


class NeighborLineworkTests(unittest.TestCase):
    def test_rotation_translation_inverse_preserves_original_line(self):
        matrix = [[4.2, .013], [.021, -4.1], [480000, 3900000]]
        original = LineString([(20, 30), (150, 190), (300, 175)])
        registered = affine_transform(original, affine_coefficients(matrix))
        restored = affine_transform(registered, affine_coefficients(inverse_matrix(matrix)))
        self.assertTrue(np.allclose(original.coords, restored.coords, rtol=0, atol=1e-8))

    def test_proximity_counts_only_covered_line_once(self):
        target = LineString([(0, 0), (10, 0)])
        neighbor = LineString([(0, .5), (5, .5)])
        result = proximity(target, neighbor, 1)
        self.assertGreater(result['lengthWithinToleranceMeters'], 5)
        self.assertLess(result['lengthWithinToleranceMeters'], 6)
        self.assertLess(result['fractionWithinTolerance'], 1)
        self.assertIn('Diagnostic', result['qualification'])

    def test_invalid_tolerance_rejected(self):
        line = LineString([(0, 0), (1, 0)])
        for tolerance in (0, -1, float('inf'), float('nan')):
            with self.assertRaises(ValueError):
                proximity(line, line, tolerance)


if __name__ == '__main__':
    unittest.main()
