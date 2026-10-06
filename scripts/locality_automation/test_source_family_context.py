"""Shared original member declarations must agree before neighbor joins."""
import unittest
from scripts.locality_automation.prepare_source_family_context import compare_member_sets


class MemberContextContracts(unittest.TestCase):
    def test_changed_common_node_or_way_is_reported_without_repair(self):
        left = {'nodes': {'1': ('9.1', '36.1')}, 'ways': {'2': ('1', '3')}}
        same = {'nodes': {'1': ('9.1', '36.1')}, 'ways': {'2': ('1', '3')}}
        self.assertEqual(compare_member_sets({'A': left, 'B': same}), [])
        changed = {'nodes': {'1': ('9.2', '36.1')}, 'ways': {'2': ('3', '1')}}
        conflicts = compare_member_sets({'A': left, 'B': changed})
        self.assertEqual({item['elementType'] for item in conflicts}, {'node', 'way'})
        self.assertEqual(left['nodes']['1'], ('9.1', '36.1'))


if __name__ == '__main__':
    unittest.main()
