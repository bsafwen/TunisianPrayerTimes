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


class PickerMetadataTest(unittest.TestCase):
    def feature(self, identifier, name='سيدي رزيق', kind='suburb', **extra):
        return {'id': identifier, 'name': name, 'aliases': [], 'kind': kind,
                'governorateId': 349, 'hasBoundary': True, **extra}

    def area(self, identifier, kind, name, polygon, **tags):
        return {'id': identifier, 'kind': kind, 'tags': {'name:ar': name, **tags}, 'shape': polygon}

    def test_containing_named_polygons_share_canonical_sector_without_geometry_changes(self):
        features = [self.feature('way'), self.feature('sector', kind='sector')]
        geometries = [box(1, 1, 2, 2), box(0, 0, 3, 3)]
        original = [geometry.wkb for geometry in geometries]
        groups = compiler.assign_picker_groups(features, geometries)
        self.assertEqual(['sector', 'sector'], [feature['pickerGroupId'] for feature in features])
        self.assertEqual([{'pickerGroupId': 'sector', 'ids': ['sector', 'way']}], groups)
        self.assertEqual(original, [geometry.wkb for geometry in geometries])

    def test_crossing_matching_aliases_group_with_positive_overlap(self):
        features = [self.feature('a', name='الرياض', aliases=['Mégrine Riadh']),
                    self.feature('b', name='Megrine Riadh')]
        compiler.assign_picker_groups(features, [box(0, 0, 2, 2), box(1, 1, 3, 3)])
        self.assertEqual(features[0]['pickerGroupId'], features[1]['pickerGroupId'])

    def test_adjacent_and_remote_homonyms_remain_separate(self):
        for second in (box(2, 0, 4, 2), box(20, 20, 22, 22)):
            features = [self.feature('a'), self.feature('b')]
            with self.subTest(bounds=second.bounds):
                self.assertEqual([], compiler.assign_picker_groups(features, [box(0, 0, 2, 2), second]))
                self.assertEqual(['a', 'b'], [feature['pickerGroupId'] for feature in features])

    def test_overlapping_homonyms_from_different_governorates_remain_separate(self):
        features = [self.feature('a'), self.feature('b', governorateId=350)]
        self.assertEqual([], compiler.assign_picker_groups(features, [box(0, 0, 2, 2), box(1, 1, 3, 3)]))

    def test_numbered_places_do_not_merge_through_a_shared_unnumbered_alias(self):
        features = [self.feature('a', name='النصر 1', aliases=['Ennasr']),
                    self.feature('b', name='النصر 2', aliases=['Ennasr']),
                    self.feature('c', name='النصر', aliases=['Ennasr'])]
        self.assertEqual([], compiler.assign_picker_groups(features, [box(0, 0, 2, 2)] * 3))

    def test_point_requires_containment_not_proximity(self):
        features = [self.feature('sector', kind='sector'),
                    self.feature('inside', hasBoundary=False, lat=1, lng=1),
                    self.feature('nearby', hasBoundary=False, lat=1, lng=2.000001)]
        compiler.assign_picker_groups(features, [box(0, 0, 2, 2)])
        self.assertEqual(['sector', 'sector', 'nearby'], [feature['pickerGroupId'] for feature in features])

    def test_alias_chain_cannot_bridge_distinct_places(self):
        features = [self.feature('a', name='Alpha', aliases=['Common']),
                    self.feature('b', name='Beta', aliases=['Common', 'Other']),
                    self.feature('c', name='Gamma', aliases=['Other'])]
        compiler.assign_picker_groups(features, [box(0, 0, 2, 2)] * 3)
        self.assertEqual(features[0]['pickerGroupId'], features[1]['pickerGroupId'])
        self.assertNotEqual(features[0]['pickerGroupId'], features[2]['pickerGroupId'])

    def test_overlap_chain_cannot_bridge_disjoint_same_name_places(self):
        features = [self.feature('a'), self.feature('b'), self.feature('c')]
        compiler.assign_picker_groups(features, [box(0, 0, 2, 2), box(1, 0, 3, 2), box(2.5, 0, 4, 2)])
        self.assertEqual(features[0]['pickerGroupId'], features[1]['pickerGroupId'])
        self.assertNotEqual(features[0]['pickerGroupId'], features[2]['pickerGroupId'])

    def test_same_name_parent_uses_containing_delegation_and_search_context(self):
        polygon = box(1, 1, 2, 2)
        obj = self.area('child', 'suburb', 'سيدي رزيق', polygon)
        sector = self.area('sector', 'sector', 'سيدي رزيق', box(0, 0, 3, 3), **{'name:fr': 'Sidi Rezig'})
        delegation = self.area('delegation', 'delegation', 'معتمدية مقرين', box(-1, -1, 4, 4),
                               **{'alt_name:fr': 'Mégrine'})
        governor = self.area('governor', 'governorate', 'ولاية بن عروس', box(-2, -2, 5, 5))
        parent, context, containing = compiler.administrative_context(obj, polygon, governor, [sector, delegation])
        self.assertEqual('معتمدية مقرين', parent)
        self.assertIn('Mégrine', context)
        self.assertIn('Sidi Rezig', context)
        self.assertEqual([delegation], containing)

    def test_crossing_polygon_is_not_parent_even_if_it_contains_representative_point(self):
        polygon = box(1, 1, 3, 3)
        obj = self.area('child', 'suburb', 'Child', polygon)
        sector = self.area('sector', 'sector', 'Misleading sector', box(0, 0, 2.5, 3))
        governor = self.area('governor', 'governorate', 'Governorate', box(-2, -2, 5, 5))
        parent, context, _ = compiler.administrative_context(obj, polygon, governor, [sector])
        self.assertEqual('Governorate', parent)
        self.assertNotIn('Misleading sector', context)

    def test_sector_overlap_is_not_an_administrative_ancestor(self):
        obj = self.area('child', 'sector', 'Child', box(1, 1, 2, 2))
        sector = self.area('sector', 'sector', 'Other sector', box(0, 0, 3, 3))
        governor = self.area('governor', 'governorate', 'Governorate', box(-2, -2, 5, 5))
        _, context, _ = compiler.administrative_context(obj, obj['shape'], governor, [sector])
        self.assertNotIn('Other sector', context)

    def test_sector_code_preserves_admin_membership_across_disputed_boundary(self):
        obj = self.area('jawhara', 'sector', 'الجوهرة', box(1, 1, 3, 3), **{'ref:tn:codegeo': '135953'})
        delegation = self.area('megrine', 'delegation', 'معتمدية مقرين', box(0, 0, 2, 3),
                               **{'ref:tn:codegeo': '1359', 'alt_name:fr': 'Mégrine'})
        governor = self.area('ben-arous', 'governorate', 'بن عروس', box(-2, -2, 5, 5),
                             **{'ref:tn:codegeo': '13'})
        self.assertFalse(delegation['shape'].covers(obj['shape']))
        parent, context, memberships = compiler.administrative_context(
            obj, obj['shape'], governor, [delegation])
        self.assertEqual('معتمدية مقرين', parent)
        self.assertIn('Mégrine', context)
        self.assertEqual([delegation], memberships)

    def test_missing_or_conflicting_admin_codes_keep_full_containment_fallback(self):
        delegate_shape = box(0, 0, 2, 3)
        for sector_code, parent_code, governor_code, duplicate in (
                ('', '1359', '13', False), ('135953', '', '13', False),
                ('135953', '1358', '13', False), ('135953', '1359', '14', False),
                ('135953', '1359', '13', True), ('135953', '1359', 'invalid', False)):
            with self.subTest(codes=(sector_code, parent_code, governor_code), duplicate=duplicate):
                obj = self.area('sector', 'sector', 'Sector', box(1, 1, 3, 3),
                                **{'ref:tn:codegeo': sector_code})
                delegation = self.area('delegate', 'delegation', 'Wrong context', delegate_shape,
                                       **{'ref:tn:codegeo': parent_code})
                governor = self.area('governor', 'governorate', 'Governorate', box(-2, -2, 5, 5),
                                     **{'ref:tn:codegeo': governor_code})
                parents = [delegation]
                if duplicate:
                    parents.append({**delegation, 'id': 'duplicate'})
                parent, context, _ = compiler.administrative_context(obj, obj['shape'], governor, parents)
                self.assertEqual('Governorate', parent)
                self.assertNotIn('Wrong context', context)

    def test_missing_sector_code_still_allows_actual_parent_containment(self):
        obj = self.area('sector', 'sector', 'Sector', box(1, 1, 2, 2))
        delegation = self.area('delegate', 'delegation', 'Containing delegation', box(0, 0, 3, 3))
        governor = self.area('governor', 'governorate', 'Governorate', box(-2, -2, 5, 5))
        parent, context, _ = compiler.administrative_context(obj, obj['shape'], governor, [delegation])
        self.assertEqual('Containing delegation', parent)
        self.assertIn('Containing delegation', context)

    def test_base_town_group_requires_admin_name_and_containment_in_same_governorate(self):
        town = self.feature('town', name='مقرين', kind='town', aliases=['Mégrine'], hasBoundary=False)
        area = self.area('admin', 'delegation', 'معتمدية مقرين', box(0, 0, 3, 3))
        governor = {'delegations': [{'id': 448, 'nomAr': 'مقرين', 'lat': 1, 'lng': 1}]}
        self.assertEqual('delegation:448', compiler.matching_base_delegation(town, Point(2, 2), [area], governor))
        self.assertIsNone(compiler.matching_base_delegation(town, Point(4, 4), [area], governor))
        self.assertIsNone(compiler.matching_base_delegation(town, Point(2, 2), [area], {'delegations': []}))
        wrong = self.area('wrong', 'delegation', 'معتمدية رادس', box(0, 0, 3, 3))
        self.assertIsNone(compiler.matching_base_delegation(town, Point(2, 2), [wrong], governor))


if __name__ == '__main__':
    unittest.main()
