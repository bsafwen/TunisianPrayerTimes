"""Compiler checks: python -m unittest discover -s scripts/neighborhoods -p 'test_*.py'."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

from shapely import set_precision
from shapely.geometry import Point, Polygon, box, mapping

SPEC = importlib.util.spec_from_file_location('generate_neighborhoods', Path(__file__).parents[1] / 'generate_neighborhoods.py')
compiler = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(compiler)


class MunicipalSourceTest(unittest.TestCase):
    def fixture(self, root, geometries=None, properties=None):
        features = []
        for index, geometry in enumerate(geometries or [box(9.61, 36.34, 9.62, 36.35)]):
            features.append({'type': 'Feature', 'geometry': mapping(geometry), 'properties': properties or {
                'Quartier_id': f'cite_{index}', 'Quartier_Ar': 'حيّ السلام', 'Quartier_Fr': 'Cité Essalem'}})
        raw = json.dumps({'type': 'FeatureCollection', 'features': features}).encode()
        (root / 'source.geojson').write_bytes(raw)
        manifest = {'schemaVersion': 1, 'sources': [{'id': 'fixture', 'provider': 'Municipality',
                    'catalogUrl': 'https://example.org/catalog', 'url': 'https://example.org/source',
                    'license': 'CC-BY', 'file': 'source.geojson', 'sha256': hashlib.sha256(raw).hexdigest(),
                    'featureCount': len(features), 'idPrefix': 'municipal:fixture:'}]}
        path = root / 'manifest.json'
        path.write_text(json.dumps(manifest))
        return path

    def test_actual_archived_source_preserves_names_ids_and_license(self):
        areas, sources = compiler.load_municipal_sources(compiler.MUNICIPAL_MANIFEST)
        self.assertEqual(21, len(areas))
        first = areas[0]
        self.assertEqual('municipal:bouarada:cite_14', first['id'])
        self.assertEqual(['حيّ السلام', 'Cité Essalem'], compiler.names(first['tags']))
        self.assertEqual('bouarada-archive-2018', first['sourceId'])
        self.assertEqual('20180618182551', sources[first['sourceId']]['archiveTimestamp'])
        self.assertEqual('cc-by', sources[first['sourceId']]['catalogLicense'])
        self.assertEqual('locality', next(a for a in areas if a['id'].endswith(':cite_20'))['kind'])

    def test_rejects_changed_source_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = self.fixture(root)
            with (root / 'source.geojson').open('ab') as file:
                file.write(b' ')
            with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
                compiler.load_municipal_sources(path)

    def test_rejects_invalid_polygon_without_silent_repair(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.fixture(Path(directory), [Polygon([(9, 36), (10, 37), (9, 37), (10, 36), (9, 36)])])
            with self.assertRaisesRegex(ValueError, 'Invalid municipal polygon'):
                compiler.load_municipal_sources(path)

    def test_rejects_projected_coordinates(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.fixture(Path(directory), [box(500000, 4000000, 500100, 4000100)])
            with self.assertRaisesRegex(ValueError, 'longitude/latitude range'):
                compiler.load_municipal_sources(path)

    def test_rejects_duplicate_ids(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.fixture(Path(directory), [box(9, 36, 10, 37), box(10, 36, 11, 37)],
                                {'Quartier_id': 'same', 'Quartier_Ar': 'الحي'})
            with self.assertRaisesRegex(ValueError, 'Duplicate municipal locality ID'):
                compiler.load_municipal_sources(path)

    def test_rejects_missing_names(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.fixture(Path(directory), properties={'Quartier_id': 'unnamed'})
            with self.assertRaisesRegex(ValueError, 'locality name'):
                compiler.load_municipal_sources(path)


class ConflictTest(unittest.TestCase):
    def conflicts(self, first_kind, second_kind, a=None, b=None):
        first = {'id': 'a', 'kind': first_kind}
        second = {'id': 'b', 'kind': second_kind}
        return compiler.detect_conflicts([first, second], [a if a is not None else box(9, 36, 11, 38),
                                                          b if b is not None else box(10, 37, 12, 39)])

    def test_crossing_peers_are_conflicts_with_real_overlap_sample(self):
        result = self.conflicts('residential', 'neighbourhood')
        self.assertEqual(1, len(result))
        self.assertEqual(['a', 'b'], result[0]['ids'])
        self.assertGreater(result[0]['intersectionKm2'], 0)
        self.assertTrue(37 < result[0]['sample']['lat'] < 38)
        self.assertTrue(10 < result[0]['sample']['lng'] < 11)

    def test_boundary_touch_does_not_conflict(self):
        self.assertEqual([], self.conflicts('sector', 'sector', box(9, 36, 10, 37), box(10, 36, 11, 37)))

    def test_true_neighborhood_nesting_is_allowed(self):
        self.assertEqual([], self.conflicts('residential', 'neighbourhood', box(9, 36, 12, 39), box(10, 37, 11, 38)))

    def test_imada_nesting_is_an_invalid_partition(self):
        self.assertEqual('overlapping_sectors', self.conflicts('sector', 'sector',
                         box(9, 36, 12, 39), box(10, 37, 11, 38))[0]['reason'])

    def test_different_hierarchies_are_allowed(self):
        for first_kind in ('sector', 'town', 'village', 'hamlet'):
            with self.subTest(kind=first_kind):
                self.assertEqual([], self.conflicts(first_kind, 'neighbourhood'))

    def test_same_broad_kind_crossing_is_ambiguous(self):
        self.assertEqual(1, len(self.conflicts('village', 'village')))

    def test_narrow_grid_overlap_sample_is_not_snapped_onto_boundary(self):
        a = set_precision(Polygon([(0, 0), (5e-6, 0), (0, 5e-6), (0, 0)]), 1e-6)
        b = set_precision(box(2e-6, 2e-6, 8e-6, 8e-6), 1e-6)
        original_a, original_b = a.wkb, b.wkb
        result = self.conflicts('residential', 'neighbourhood', a, b)
        self.assertEqual(1, len(result))
        sample = result[0]['sample']
        point = Point(sample['lng'], sample['lat'])
        self.assertTrue(a.contains(point))
        self.assertTrue(b.contains(point))
        self.assertEqual(original_a, a.wkb)
        self.assertEqual(original_b, b.wkb)


class OsmTagsTest(unittest.TestCase):
    def test_missing_admin_level_retains_generic_locality(self):
        self.assertEqual('locality', compiler.area_kind({'boundary': 'administrative', 'name': 'حي'}))
        self.assertEqual('sector', compiler.area_kind({'boundary': 'administrative', 'admin_level': '6'}))

    def test_explicit_locality_polygon_excludes_farms_and_buildings(self):
        self.assertEqual('locality', compiler.area_kind({'place': 'locality'}))
        for extra in ({'building': 'yes'}, {'landuse': 'farmyard'}, {'landuse': 'farmland'}, {'leisure': 'fitness_station'}):
            with self.subTest(tags=extra):
                self.assertIsNone(compiler.area_kind({'place': 'locality', **extra}))
        self.assertIsNone(compiler.area_kind({'place': 'square'}))

    def test_aliases_do_not_replace_current_names_with_old_names(self):
        self.assertEqual(['الاسم الحالي', 'Local alias', 'Official name'], compiler.names({
            'name:ar': 'الاسم الحالي', 'loc_name:fr': 'Local alias', 'official_name': 'Official name',
            'old_name:ar': 'الاسم القديم'}))


if __name__ == '__main__':
    unittest.main()
