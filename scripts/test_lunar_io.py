"""Offline regressions for source failures and publishing coherent official dates."""
import contextlib
import datetime as dt
import http.client
import io
import json
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

import detect_tunisian_lunar_dates as detector


class OfficialDateWriteTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.path = detector.official_dates_path(self.root, 1447)
        self.path.parent.mkdir(parents=True)
        self.record = {
            "hijriYear": 1447, "ramadanStart": "2026-02-19", "eidFitrDate": None,
            "eidAdhaDate": None, "lastUpdated": "2026-02-18T20:00:00Z",
        }
        self.save()

    def save(self):
        self.path.write_text(json.dumps(self.record), encoding="utf-8")

    def write_fitr(self, date, dry_run=False):
        return detector.update_override_file(self.root, detector.EVENTS["eid_fitr"], 1447, date, dry_run)

    def test_29_and_30_day_ramadan_are_publishable(self):
        for day in ("2026-03-20", "2026-03-21"):
            with self.subTest(day=day):
                self.save()
                self.assertTrue(self.write_fitr(day))
                self.assertEqual(json.loads(self.path.read_text())["eidFitrDate"], day)

    def test_28_and_31_day_ramadan_do_not_modify_file(self):
        before = self.path.read_bytes()
        for day in ("2026-03-19", "2026-03-22"):
            for dry_run in (True, False):
                with self.subTest(day=day, dry_run=dry_run):
                    with self.assertRaisesRegex(ValueError, "29 or 30"):
                        self.write_fitr(day, dry_run)
                    self.assertEqual(self.path.read_bytes(), before)

    def test_adha_must_fit_known_shawwal_start(self):
        self.record["eidFitrDate"] = "2026-03-20"
        self.save()
        before = self.path.read_bytes()
        with self.assertRaisesRegex(ValueError, "29 or 30"):
            detector.update_override_file(self.root, detector.EVENTS["eid_adha"], 1447, "2026-05-30", False)
        self.assertEqual(self.path.read_bytes(), before)

    def test_partial_updates_retain_event_freshness(self):
        self.write_fitr("2026-03-20")
        first = json.loads(self.path.read_text())
        detector.update_override_file(self.root, detector.EVENTS["eid_adha"], 1447, "2026-05-27", False)
        result = json.loads(self.path.read_text())
        self.assertEqual(result["ramadanStartUpdated"], self.record["lastUpdated"])
        self.assertEqual(result["eidFitrUpdated"], first["eidFitrUpdated"])
        self.assertIsNotNone(result["eidAdhaUpdated"])

    def test_explicit_unknown_provenance_is_retained(self):
        self.record["ramadanStartUpdated"] = None
        self.save()
        self.write_fitr("2026-03-20")
        self.assertIsNone(json.loads(self.path.read_text())["ramadanStartUpdated"])

    def test_existing_announced_date_is_never_replaced(self):
        self.write_fitr("2026-03-20")
        before = self.path.read_bytes()
        self.assertFalse(self.write_fitr("2026-03-20"))
        with self.assertRaisesRegex(ValueError, "refusing to overwrite"):
            self.write_fitr("2026-03-21")
        self.assertEqual(self.path.read_bytes(), before)

    def test_bad_record_fields_are_rejected_without_writing(self):
        for fields in ({"hijriYear": None}, {"hijriYear": "1447"}, {"ramadanStart": "2026-02-30"}):
            with self.subTest(fields=fields):
                candidate = {**self.record, **fields}
                with self.assertRaises(ValueError):
                    detector.validate_override_record(candidate, 1447)

    def test_cross_year_anchors_must_also_fit(self):
        # Last month of 1446 to Ramadan 1447 spans nine lunar months.
        previous = {"hijriYear": 1446, "eidAdhaDate": "2025-06-10"}
        detector.validate_override_record(previous, 1446)
        detector.official_dates_path(self.root, 1446).write_text(json.dumps(previous))
        self.record["ramadanStart"] = "2026-02-13"
        detector.validate_override_record(self.record, 1447)
        self.save()
        before = self.path.read_bytes()
        with self.assertRaisesRegex(ValueError, "29 or 30"):
            self.write_fitr("2026-03-15")
        self.assertEqual(self.path.read_bytes(), before)


class SourceFailureTests(unittest.TestCase):
    def test_search_timeout_and_disconnect_fall_through_to_next_source(self):
        rss = '<rss><channel><item><title>Tunisia Eid Fitr announced</title><link>https://mufti.tn/eid</link></item></channel></rss>'
        for error in (TimeoutError("timeout"), http.client.RemoteDisconnected("closed")):
            with self.subTest(error=type(error).__name__):
                with patch.object(detector, "collect_meteo_direct_candidates", return_value=[]), \
                     patch.object(detector, "search_feed_urls", return_value=[("one", "https://one"), ("two", "https://two")]), \
                     patch.object(detector, "fetch_text", side_effect=[error, rss]), contextlib.redirect_stderr(io.StringIO()):
                    result = detector.collect_candidates(detector.EVENTS["eid_fitr"], 1447, dt.date(2026, 3, 19), 36, False)
                self.assertEqual(len(result), 1)
                self.assertEqual(result[0].source_domain, "mufti.tn")

    def test_meteo_list_timeout_preserves_known_direct_urls(self):
        with patch.object(detector, "fetch_text", side_effect=TimeoutError("timeout")), contextlib.redirect_stderr(io.StringIO()):
            urls = detector.meteo_direct_urls(detector.EVENTS["eid_fitr"], 1447)
        self.assertIn("https://www.meteo.tn/ar/shaouel-moon-crescent-1447", urls)

    def test_meteo_page_timeout_continues_to_other_pages(self):
        with patch.object(detector, "meteo_direct_urls", return_value=["https://www.meteo.tn/one", "https://www.meteo.tn/two"]), \
             patch.object(detector, "fetch_text", side_effect=[TimeoutError("timeout"), "<h1>1447 شوال</h1>"]) as fetch, \
             contextlib.redirect_stderr(io.StringIO()):
            candidates = detector.collect_meteo_direct_candidates(detector.EVENTS["eid_fitr"], 1447)
        self.assertEqual(fetch.call_count, 2)
        self.assertEqual(len(candidates), 1)


class EventIsolationTests(unittest.TestCase):
    def test_model_failure_does_not_discard_another_valid_event(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            report = root / "report.md"
            args = SimpleNamespace(repo_root=folder, today="2026-05-17", event="all", skip_network=False,
                                   max_candidates=36, use_gdelt=False, env_file="unused", dry_run=False, pr_body=str(report))
            candidate = detector.ArticleCandidate("a1", "INM", "meteo.tn", "official", "fixture", "Title", "https://www.meteo.tn/fixture", None, "Text")
            validation = {"accepted": True, "selected_date": "2026-05-27", "confidence": "high", "evidence": []}
            with patch.object(detector, "parse_args", return_value=args), \
                 patch.object(detector, "target_events_for", return_value=[(detector.EVENTS["eid_fitr"], 1447), (detector.EVENTS["eid_adha"], 1447)]), \
                 patch.object(detector, "collect_candidates", return_value=[candidate]), \
                 patch.object(detector, "deterministic_decision_from_candidates", return_value=None), \
                 patch.object(detector, "load_deepseek_key", return_value="offline-fixture"), \
                 patch.object(detector, "ask_deepseek", side_effect=[RuntimeError("invalid response"), {}]), \
                 patch.object(detector, "validate_decision", return_value=validation), \
                 patch.dict(detector.os.environ, {"GITHUB_STEP_SUMMARY": ""}), \
                 contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(detector.main(), 0)
            stored = json.loads(detector.official_dates_path(root, 1447).read_text())
            self.assertIsNone(stored["eidFitrDate"])
            self.assertEqual(stored["eidAdhaDate"], "2026-05-27")
            self.assertIn("Detection failed: invalid response", report.read_text())
            self.assertIn("Validation: accepted", report.read_text())

    def test_failed_extraction_without_changes_reports_a_failed_run(self):
        with tempfile.TemporaryDirectory() as folder:
            args = SimpleNamespace(repo_root=folder, today="2026-05-17", event="eid_adha", skip_network=False,
                                   max_candidates=36, use_gdelt=False, env_file="unused", dry_run=False, pr_body=None)
            with patch.object(detector, "parse_args", return_value=args), \
                 patch.object(detector, "collect_candidates", return_value=[object()]), \
                 patch.object(detector, "deterministic_decision_from_candidates", return_value=None), \
                 patch.object(detector, "load_deepseek_key", return_value="offline-fixture"), \
                 patch.object(detector, "ask_deepseek", side_effect=TimeoutError("model timeout")), \
                 patch.dict(detector.os.environ, {"GITHUB_STEP_SUMMARY": ""}), \
                 contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(detector.main(), 1)
            self.assertFalse(detector.official_dates_path(Path(folder), 1447).exists())


if __name__ == "__main__":
    unittest.main()
