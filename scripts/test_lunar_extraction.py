import dataclasses
import datetime as dt
import unittest
from unittest.mock import patch

import detect_tunisian_lunar_dates as lunar


class MeteoExtractionTests(unittest.TestCase):
    def evidence(self, key: str, body: str):
        return lunar.derive_meteo_evidence(lunar.EVENTS[key], 1447, body)

    def candidate(self, key: str, body: str, candidate_id: str = "a1"):
        month = "رمضان" if key == "ramadan_start" else "ذو الحجة"
        return lunar.ArticleCandidate(
            candidate_id, "Institut National de la Meteorologie", "meteo.tn", "official",
            "meteo_direct", f"رؤية هلال {month} 1447", f"https://www.meteo.tn/{candidate_id}",
            "2026-05-15", body,
        )

    def test_positive_ramadan_preserves_the_source_quote(self):
        source = "المعهد الوطني للرصد الجوي، رؤية هلال رمضان 1447. تصبح الرؤية ممكنة يوم 18 فيفري 2026 بعد غروب الشمس."
        selected, quote = self.evidence("ramadan_start", source)
        self.assertEqual(dt.date(2026, 2, 19), selected)
        self.assertIn(quote, source)
        self.assertIn("18 فيفري 2026", quote)

    def test_positive_adha_uses_visibility_plus_ten_days(self):
        source = "المعهد الوطني للرصد الجوي 1447: يمكن رؤية هلال شهر ذو الحجة بعد غروب شمس يوم 17 ماي 2026."
        selected, quote = self.evidence("eid_adha", source)
        self.assertEqual(dt.date(2026, 5, 27), selected)
        self.assertIn(quote, source)

    def test_negative_and_conditional_statements_do_not_create_dates(self):
        cases = [
            ("eid_adha", "1447: لا يمكن رؤية هلال شهر ذو الحجة بعد غروب شمس يوم 16 ماي 2026."),
            ("eid_adha", "1447: ولا يمكن رؤية هلال شهر ذو الحجة بعد غروب شمس يوم 16 ماي 2026."),
            ("ramadan_start", "هلال رمضان 1447: لن تكون الرؤية ممكنة يوم 18 فيفري 2026."),
            ("ramadan_start", "هلال رمضان 1447: قد تصبح الرؤية ممكنة يوم 18 فيفري 2026."),
            ("ramadan_start", "هلال رمضان 1447: الرؤية ممكنة يوم 18 فيفري 2026 إلا في تونس."),
        ]
        for key, source in cases:
            with self.subTest(source=source):
                self.assertIsNone(self.evidence(key, source))

    def test_chart_caption_and_sunset_date_are_not_affirmative_evidence(self):
        source = "ذو الحجة 1447: خريطة إمكانية رؤية هلال شهر ذو الحجة بعد غروب شمس يوم 17 ماي 2026. صورة 3."
        self.assertIsNone(self.evidence("eid_adha", source))

    def test_two_possible_visibility_dates_are_ambiguous(self):
        source = "ذو الحجة 1447. يمكن رؤية الهلال يوم 16 ماي 2026. يمكن رؤية الهلال يوم 17 ماي 2026."
        self.assertIsNone(self.evidence("eid_adha", source))

    def test_negative_earlier_day_does_not_hide_later_affirmative_evidence(self):
        source = "ذو الحجة 1447. لا يمكن رؤية الهلال يوم 16 ماي 2026، وتصبح الرؤية ممكنة يوم 17 ماي 2026."
        self.assertEqual(dt.date(2026, 5, 27), self.evidence("eid_adha", source)[0])

    def test_same_day_positive_and_negative_evidence_is_rejected(self):
        source = "ذو الحجة 1447. لا يمكن رؤية الهلال يوم 17 ماي 2026. تصبح الرؤية ممكنة يوم 17 ماي 2026."
        self.assertIsNone(self.evidence("eid_adha", source))

    def test_unrelated_caption_date_is_not_used_as_latest_visibility(self):
        source = "ذو الحجة 1447. يمكن رؤية الهلال يوم 17 ماي 2026. صورة 3: بعد غروب شمس يوم 18 ماي 2026."
        self.assertEqual(dt.date(2026, 5, 27), self.evidence("eid_adha", source)[0])

    def test_other_month_and_wrong_year_are_not_target_evidence(self):
        self.assertIsNone(self.evidence("ramadan_start", "رمضان 1447. شوال. يمكن رؤية الهلال يوم 18 فيفري 2026."))
        self.assertIsNone(self.evidence("eid_adha", "ذو الحجة 1446. يمكن رؤية الهلال يوم 17 ماي 2026."))

    def test_deterministic_claim_cites_only_verbatim_source_text(self):
        body = "يمكن رؤية هلال شهر ذو الحجة بعد غروب شمس يوم 17 ماي 2026."
        candidate = self.candidate("eid_adha", body)
        decision = lunar.deterministic_decision_from_candidates(lunar.EVENTS["eid_adha"], 1447, [candidate])
        self.assertEqual("2026-05-27", decision["selectedDate"])
        self.assertEqual("a1", decision["claims"][0]["sourceId"])
        self.assertIn(decision["claims"][0]["quote"], candidate.title + " " + candidate.snippet)

    def test_title_and_body_cannot_be_joined_into_a_supporting_quote(self):
        candidate = self.candidate("ramadan_start", "تصبح الرؤية ممكنة يوم 18 فيفري 2026 بعد غروب الشمس.")
        decision = lunar.deterministic_decision_from_candidates(lunar.EVENTS["ramadan_start"], 1447, [candidate])
        self.assertIsNotNone(decision)
        self.assertIn(decision["claims"][0]["quote"], candidate.snippet)
        self.assertTrue(lunar.quote_is_in_candidate(decision["claims"][0]["quote"], candidate))

    def test_other_authoritative_sources_prevent_the_shortcut(self):
        meteo = self.candidate("eid_adha", "يمكن رؤية هلال شهر ذو الحجة بعد غروب شمس يوم 16 ماي 2026.")
        mufti = lunar.ArticleCandidate(
            "a2", "Dar al-Ifta Tunisia", "mufti.tn", "official", "google_news",
            "Tunisian Eid al-Adha 1447 announcement", "https://mufti.tn/eid", "2026-05-17",
            "Dar al-Ifta officially announces Eid al-Adha on 2026-05-27.",
        )
        self.assertIsNone(lunar.deterministic_decision_from_candidates(lunar.EVENTS["eid_adha"], 1447, [meteo, mufti]))

    def test_disagreeing_meteo_sources_prevent_the_shortcut(self):
        first = self.candidate("eid_adha", "يمكن رؤية هلال شهر ذو الحجة يوم 16 ماي 2026.")
        second = self.candidate("eid_adha", "يمكن رؤية هلال شهر ذو الحجة يوم 17 ماي 2026.", "a2")
        self.assertIsNone(lunar.deterministic_decision_from_candidates(lunar.EVENTS["eid_adha"], 1447, [first, second]))

    def test_legacy_synthetic_evidence_is_not_trusted(self):
        raw = self.candidate("eid_adha", "يمكن رؤية هلال شهر ذو الحجة يوم 17 ماي 2026.")
        synthetic = dataclasses.replace(raw, provider="meteo_direct_derived")
        self.assertIsNone(lunar.deterministic_decision_from_candidates(lunar.EVENTS["eid_adha"], 1447, [synthetic]))

    def test_collection_keeps_late_source_evidence_without_synthetic_records(self):
        quote = "تصبح الرؤية ممكنة يوم 18 فيفري 2026"
        page = "<h1>رؤية هلال رمضان 1447</h1><p>" + "معلومات عامة. " * 700 + f"</p><p>{quote}.</p>"
        with patch.object(lunar, "meteo_direct_urls", return_value=["https://www.meteo.tn/report"]), \
                patch.object(lunar, "fetch_text", return_value=page):
            candidates = lunar.collect_meteo_direct_candidates(lunar.EVENTS["ramadan_start"], 1447)
        self.assertEqual(1, len(candidates))
        self.assertEqual("meteo_direct", candidates[0].provider)
        self.assertIn(quote, candidates[0].snippet)

    def test_source_timeout_does_not_abort_other_candidates(self):
        page = "<h1>ذو الحجة 1447</h1><p>يمكن رؤية الهلال يوم 17 ماي 2026.</p>"
        with patch.object(lunar, "meteo_direct_urls", return_value=["https://www.meteo.tn/a", "https://www.meteo.tn/b"]), \
                patch.object(lunar, "fetch_text", side_effect=[TimeoutError("offline"), page]):
            candidates = lunar.collect_meteo_direct_candidates(lunar.EVENTS["eid_adha"], 1447)
        self.assertEqual(1, len(candidates))

    def test_source_discovery_rejects_lookalike_domains(self):
        page = '<a href="https://meteo.tn.example.com/report">رؤية هلال رمضان 1447</a>'
        with patch.object(lunar, "fetch_text", return_value=page):
            urls = lunar.meteo_direct_urls(lunar.EVENTS["ramadan_start"], 1447)
        self.assertFalse(any("example.com" in url for url in urls))


if __name__ == "__main__":
    unittest.main()
