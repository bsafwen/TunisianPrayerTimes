"""Concurrent read-only phases must not inflate elapsed occupied process time."""
from datetime import datetime, timedelta, timezone
import unittest
from scripts.locality_automation.summarize_work_window import union_seconds, count_summary


class WindowMetricContracts(unittest.TestCase):
    def test_overlapping_process_spans_count_elapsed_once(self):
        start = datetime(2026, 10, 3, tzinfo=timezone.utc)
        spans = [(start, start + timedelta(seconds=10)),
                 (start + timedelta(seconds=5), start + timedelta(seconds=15)),
                 (start + timedelta(seconds=20), start + timedelta(seconds=25))]
        self.assertEqual(union_seconds(spans), 20)
        with self.assertRaisesRegex(ValueError, 'Negative'):
            union_seconds([(start + timedelta(seconds=1), start)])

    def test_source_only_task_totals_do_not_increase_geographic_count(self):
        result = count_summary({'summary': {'uniqueValidatedLocations': 10,
                              'explicitFullSourceBoundaryLocationCount': 7, 'uniqueInstalledCorrectionLocations': 4,
                              'verifiedSourceOnlyAcceptedLocationCount': 1000,
                              'validationIssues': [], 'sourceOnlyAuditIssues': [], 'reportingIssues': []}})
        self.assertEqual(result, {'geographic': 10, 'completeSourceBodies': 7, 'installedCorrections': 4})


if __name__ == '__main__':
    unittest.main()
