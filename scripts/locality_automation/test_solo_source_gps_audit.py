import hashlib
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from shapely.geometry import Polygon

from scripts.locality_automation.run_solo_source_gps_audit import nearest_source, protected_bodies


class SoloGpsAuditTests(unittest.TestCase):
    def test_nearest_uses_query_coordinates_and_numeric_id_tie(self):
        sources = [{'id': 22, 'lat': 35, 'lng': 10}, {'id': 11, 'lat': 35, 'lng': 10},
                   {'id': 33, 'lat': 36, 'lng': 10}]
        self.assertEqual(nearest_source(sources, 35, 10), 11)
        self.assertEqual(nearest_source(sources, 36, 10), 33)

    def lineage(self):
        polygon = Polygon([(0, 0), (1, 0), (1, 1), (0, 0)])
        binary = b'abcd'
        bindings = [{'officialCode': str(100000 + i), 'id': 'body:' + str(i),
                     'acceptedId': 'body:' + str(i),
                     'decodedWkbSha256': hashlib.sha256(polygon.wkb).hexdigest(),
                     'packedSliceSha256': hashlib.sha256(binary).hexdigest()} for i in range(323)]
        replay = SimpleNamespace(by_id={r['id']: {'offset': 0, 'length': 4} for r in bindings},
                                 geometry=lambda _: polygon)
        return replay, binary, {'bindingCount': 323, 'bindings': bindings}

    def test_stale_packed_body_rejected_before_historical_reference_reads(self):
        replay, binary, lineage = self.lineage()
        lineage['bindings'][0]['packedSliceSha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'body changed'):
            protected_bodies(replay, binary, lineage)

    def test_reassigned_accepted_id_rejected(self):
        replay, binary, lineage = self.lineage()
        lineage['bindings'][0]['acceptedId'] = 'different'
        with self.assertRaisesRegex(ValueError, 'accepted identity differs'):
            protected_bodies(replay, binary, lineage)

    def test_duplicate_protected_code_rejected(self):
        replay, binary, lineage = self.lineage()
        lineage['bindings'][1]['officialCode'] = lineage['bindings'][0]['officialCode']
        with self.assertRaisesRegex(ValueError, 'not unique'):
            protected_bodies(replay, binary, lineage)

    def test_original_optional_null_and_missing_gps_refs_remain_distinct(self):
        replay, binary, lineage = self.lineage()
        for row in lineage['bindings']:
            row['authoritativeReference'] = {'file': 'authoritative', 'sha256': 'pinned'}
        lineage['bindings'][0]['acceptedGpsReference'] = None
        lineage['bindings'][2]['acceptedGpsReference'] = {'file': 'gps', 'sha256': 'pinned'}
        with patch('scripts.locality_automation.run_solo_source_gps_audit.checked') as checked:
            proof = protected_bodies(replay, binary, lineage)
        self.assertIsNone(proof[0]['acceptedGpsReference'])
        self.assertNotIn('acceptedGpsReference', proof[1])
        self.assertEqual(proof[2]['acceptedGpsReference']['file'], 'gps')
        self.assertEqual(checked.call_count, 324)


if __name__ == '__main__':
    unittest.main()
