import unittest
from scripts.locality_automation.verify_solo_jvm_audit import compare


class SoloJvmAuditTests(unittest.TestCase):
    def sample(self):
        model = {'accuracyMeters': 20, 'expectedSourceId': 33, 'id': 'probe0', 'lat': 35.5, 'lng': 9.5,
                 'indexed': {'winnerId': 'own'}}
        actual = {'requestIndex': 0, 'name': 'probe0', 'lat': 35.5, 'lng': 9.5, 'accuracy': 20,
                  'label': {'id': 'own'}, 'expectedSourceId': 33, 'actualGpsSourceId': 33,
                  'actualPersistedSourceId': 33, 'sourceMatches': True,
                  'labelPersistenceMatches': True, 'manualReferenceCleared': True}
        return model, actual

    def test_agreeing_observation(self):
        model, actual = self.sample()
        self.assertTrue(compare(model, actual, 2))

    def test_success_flags_do_not_hide_wrong_label(self):
        model, actual = self.sample()
        actual['label']['id'] = 'wrong'
        self.assertFalse(compare(model, actual, 2))

    def test_source_persistence_and_coordinate_mismatch_rejected(self):
        for key, changed in (('actualPersistedSourceId', 44), ('actualGpsSourceId', 44),
                             ('lng', 9.51), ('accuracy', 50), ('requestIndex', 1)):
            model, actual = self.sample()
            actual[key] = changed
            self.assertFalse(compare(model, actual, 2), key)

    def test_null_label_is_preserved_as_nullable_outcome(self):
        model, actual = self.sample()
        model['indexed']['winnerId'] = None
        actual.pop('label')
        self.assertTrue(compare(model, actual, 2))


if __name__ == '__main__':
    unittest.main()
