import unittest
from xml.etree import ElementTree as ET

from scripts.locality_automation.audit_prepared_osm_graphs import graph_assembly


def fixture():
    root = ET.Element('osm')
    for ident, point in {'1': (0, 0), '2': (4, 0), '3': (4, 4), '4': (0, 4),
                         '5': (1, 1), '6': (2, 1), '7': (2, 2), '8': (1, 2)}.items():
        ET.SubElement(root, 'node', id=ident, lon=str(point[0]), lat=str(point[1]))
    for ident, nodes in {'11': ('1', '2', '3'), '12': ('1', '4', '3'),
                         '13': ('5', '6', '7', '8', '5')}.items():
        way = ET.SubElement(root, 'way', id=ident)
        for node in nodes:
            ET.SubElement(way, 'nd', ref=node)
    relation = ET.SubElement(root, 'relation', id='100')
    for ref, role in (('12', 'outer'), ('11', 'outer'), ('13', 'inner')):
        ET.SubElement(relation, 'member', type='way', ref=ref, role=role)
    ET.SubElement(relation, 'member', type='node', ref='1', role='admin_centre')
    return root, relation


class PreparedGraphTests(unittest.TestCase):
    def test_reversed_shared_endpoints_inner_role_and_nonboundary_member(self):
        root, _ = fixture()
        body, facts = graph_assembly(ET.tostring(root), '100')
        self.assertEqual(body.area, 15)
        self.assertEqual(facts['boundaryWayCount'], 3)
        self.assertEqual(facts['originalSegmentCount'], 8)
        self.assertEqual(facts['innerRingCount'], 1)
        self.assertEqual(facts['nonBoundaryMembersPreserved'][0]['role'], 'admin_centre')
        self.assertFalse(facts['inferredConnectorsAdded'])

    def test_duplicate_way_rejected(self):
        root, relation = fixture()
        ET.SubElement(relation, 'member', type='way', ref='11', role='outer')
        with self.assertRaisesRegex(ValueError, 'Duplicate boundary'):
            graph_assembly(ET.tostring(root), '100')

    def test_missing_native_node_rejected(self):
        root, _ = fixture()
        root.remove(next(node for node in root.findall('node') if node.attrib['id'] == '2'))
        with self.assertRaisesRegex(ValueError, 'Missing literal way node'):
            graph_assembly(ET.tostring(root), '100')

    def test_open_endpoint_graph_rejected(self):
        root, relation = fixture()
        relation.remove(next(member for member in relation.findall('member') if member.attrib['ref'] == '12'))
        with self.assertRaisesRegex(ValueError, 'unclosed source endpoint'):
            graph_assembly(ET.tostring(root), '100')

    def test_nested_boundary_relation_rejected(self):
        root, relation = fixture()
        ET.SubElement(root, 'relation', id='101')
        ET.SubElement(relation, 'member', type='relation', ref='101', role='outer')
        with self.assertRaisesRegex(ValueError, 'Nested boundary'):
            graph_assembly(ET.tostring(root), '100')

    def test_self_crossing_source_ring_rejected_without_repair(self):
        root, _ = fixture()
        way = next(item for item in root.findall('way') if item.attrib['id'] == '13')
        for child in list(way):
            way.remove(child)
        for node in ('5', '7', '6', '8', '5'):
            ET.SubElement(way, 'nd', ref=node)
        with self.assertRaisesRegex(ValueError, 'Invalid native ring'):
            graph_assembly(ET.tostring(root), '100')


if __name__ == '__main__':
    unittest.main()
