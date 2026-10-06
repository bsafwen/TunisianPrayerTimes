"""Conflicting identities and historical validations cannot become fresh leads."""
import csv
import json
from pathlib import Path
import tempfile
import unittest
from scripts.locality_automation.plan_cached_boundary_families import plan
from scripts.locality_automation.run_sealed_boundary_queue import pin


class FamilyPlannerContracts(unittest.TestCase):
    def fixture(self, root, *, source_code='215151', source_name='باجة', validated=False, duplicate=False):
        manifest = {}
        values = {
            'publication': {'summary': {'validatedLocationCodes': ['215151'] if validated else [],
                                       'validationIssues': [], 'sourceOnlyAuditIssues': [], 'reportingIssues': []}},
            'metadata': {'features': [{'id': 'osm:relation:1', 'name': 'باجة', 'parentName': 'معتمدية باجة الشمالية',
                                      'hasBoundary': True, 'kind': 'sector'}]},
            'originalOsmAreas': {'areas': [{'id': 'osm:relation:1', 'tags': {'boundary': 'administrative', 'admin_level': '6',
                                          'name:ar': source_name, 'ref:tn:codegeo': source_code}, 'geometry': {'type': 'Polygon'}}]},
            'insRegistry': {'sectors': [{'sectorCode': '215151', 'sectorAr': 'باجة', 'delegationAr': 'باجة الشمالية',
                                        'delegationCode': '2151', 'governorateAr': 'باجة'}]},
        }
        for key, value in values.items():
            path = root / (key + '.json')
            path.write_text(json.dumps(value, ensure_ascii=False), encoding='utf-8')
            manifest[key] = pin(path)
        target = {'identityCandidateCodes': '["215151"]', 'frozenFunctionalExpectedId': 'osm:relation:1',
                  'name': 'باجة', 'delegation': 'باجة الشمالية', 'governorate': 'باجة', 'ministryCsvLine': '1',
                  'currentRetrievalProofStatus': 'unchecked', 'ISIEPresenceStatus': 'unchecked', 'GooglePresenceStatus': 'unchecked'}
        filler = {**target, 'identityCandidateCodes': '[]', 'frozenFunctionalExpectedId': ''}
        rows = [target] + ([target] if duplicate else [])
        rows += [filler] * (2084 - len(rows))
        for key, data in [('functionalMinistryMatrix', rows),
                          ('recordInventory', [{'id': 'osm:relation:1', 'candidateCodes': '["215151"]'}])]:
            path = root / (key + '.csv')
            with path.open('w', newline='', encoding='utf-8') as stream:
                writer = csv.DictWriter(stream, fieldnames=list(data[0]))
                writer.writeheader()
                writer.writerows(data)
            manifest[key] = pin(path)
        return manifest

    def test_exact_match_remains_unchecked_no_credit(self):
        with tempfile.TemporaryDirectory() as directory:
            result = plan(self.fixture(Path(directory)), 12)
            self.assertEqual(result['candidateCount'], 1)
            self.assertFalse(result['sourcePoolAdmitted'])
            self.assertEqual(result['newCredit'], 0)
            self.assertEqual(result['selected'][0]['GooglePresenceStatus'], 'unchecked')

    def test_name_code_and_duplicate_binding_conflicts_are_excluded(self):
        for change, reason in [({'source_code': '215152'}, 'original_code_tag_conflict'),
                               ({'source_name': 'OTHER'}, 'literal_name_conflict'),
                               ({'duplicate': True}, 'nonunique_ministry_binding')]:
            with self.subTest(change=change), tempfile.TemporaryDirectory() as directory:
                result = plan(self.fixture(Path(directory), **change), 12)
                self.assertEqual(result['candidateCount'], 0)
                self.assertIn(reason, result['excludedReasons'])

    def test_previously_validated_code_never_becomes_new_lead(self):
        with tempfile.TemporaryDirectory() as directory:
            result = plan(self.fixture(Path(directory), validated=True), 12)
            self.assertEqual(result['candidateCount'], 0)
            self.assertEqual(result['excludedReasons']['already_geographically_validated'], 1)


if __name__ == '__main__':
    unittest.main()
