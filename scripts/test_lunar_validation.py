import copy
import dataclasses
import datetime as dt
import json
import unittest
from unittest.mock import patch

import detect_tunisian_lunar_dates as lunar


class LunarValidationTests(unittest.TestCase):
    def candidate(self, text, *, candidate_id="a1", tier="official", domain="mufti.tn",
                  published="2026-02-18", provider="google_news", title="بلاغ دار الإفتاء التونسية"):
        return lunar.ArticleCandidate(
            candidate_id, "Dar al-Ifta Tunisia", domain, tier, provider,
            title, f"https://{domain}/{candidate_id}", published, text,
        )

    def decision(self, quote, *, date="2026-02-19", key="ramadan_start", source_id="a1"):
        return {
            "event": key, "hijriYear": 1447, "selectedDate": date, "confidence": "high",
            "reason": "Grounded announcement", "conflicts": [],
            "claims": [{
                "sourceId": source_id, "gregorianDate": date, "certainty": "announced",
                "isOfficialAnnouncement": True, "authorityMentioned": "Mufti",
                "quote": quote,
            }],
        }

    def validate(self, decision, candidates, key="ramadan_start"):
        return lunar.validate_decision(decision, lunar.EVENTS[key], 1447, candidates)

    def test_actual_arabic_announcement_is_accepted(self):
        text = "أعلن مفتي الجمهورية أن يوم الخميس 19 فيفري 2026 هو غرة شهر رمضان."
        result = self.validate(self.decision(text), [self.candidate(text)])
        self.assertTrue(result["accepted"], result)
        self.assertEqual(text, result["evidence"][0]["quote"])

    def test_french_announcement_with_accents_is_accepted(self):
        text = "Le Mufti annonce que le Ramadan débutera jeudi 19 février 2026."
        result = self.validate(self.decision(text), [self.candidate(text, tier="state_news", domain="tap.info.tn")])
        self.assertTrue(result["accepted"], result)

    def test_arabic_digits_and_diacritics_are_normalized(self):
        source = "أَعْلَنَ مُفتي الجمهورية أن غُرّة رمضان يوم ١٩ فيفري ٢٠٢٦."
        quote = "أعلن مفتي الجمهورية أن غرة رمضان يوم 19 فيفري 2026"
        result = self.validate(self.decision(quote), [self.candidate(source)])
        self.assertTrue(result["accepted"], result)

    def test_fabricated_quote_is_rejected(self):
        candidate = self.candidate("أعلن مفتي الجمهورية موعد تحري هلال رمضان.")
        self.assertFalse(self.validate(self.decision("أعلن المفتي غرة رمضان يوم 19 فيفري 2026"), [candidate])["accepted"])

    def test_real_quote_does_not_ground_a_different_claimed_date(self):
        text = "أعلن مفتي الجمهورية أن غرة رمضان يوم 19 فيفري 2026."
        self.assertFalse(self.validate(self.decision(text, date="2026-02-20"), [self.candidate(text)])["accepted"])

    def test_quote_containing_only_a_date_is_not_event_evidence(self):
        text = "أعلن مفتي الجمهورية عن اجتماع يوم 19 فيفري 2026 لدراسة غرة رمضان."
        self.assertFalse(self.validate(self.decision("19 فيفري 2026"), [self.candidate(text)])["accepted"])

    def test_trimmed_prediction_and_negation_cannot_be_laundered(self):
        quote = "عيد الفطر يوم 20 مارس 2026"
        for prefix in ("لن يكون ", "من المتوقع أن يكون ", "فلكيا سيكون ", "لم يعلن المفتي أن "):
            with self.subTest(prefix=prefix):
                candidate = self.candidate(prefix + quote)
                result = self.validate(self.decision(quote, date="2026-03-20", key="eid_fitr"), [candidate], "eid_fitr")
                self.assertFalse(result["accepted"], result)

    def test_actual_tomorrow_from_feed_publication_is_supported(self):
        text = "أعلن مفتي الجمهورية أن غدا أول أيام شهر رمضان."
        result = self.validate(self.decision(text), [self.candidate(text)])
        self.assertTrue(result["accepted"], result)

    def test_tomorrow_cannot_use_a_missing_date_or_gdelt_seen_date(self):
        text = "أعلن مفتي الجمهورية أن غدا أول أيام شهر رمضان."
        for candidate in (self.candidate(text, published=None), self.candidate(text, provider="gdelt")):
            with self.subTest(candidate=candidate):
                self.assertFalse(self.validate(self.decision(text), [candidate])["accepted"])

    def test_weekday_and_missing_year_can_use_feed_publication(self):
        for text in (
            "أعلن مفتي الجمهورية أن الخميس هو غرة رمضان.",
            "أعلن مفتي الجمهورية أن غرة رمضان يوم 19 فيفري.",
        ):
            with self.subTest(text=text):
                result = self.validate(self.decision(text), [self.candidate(text)])
                self.assertTrue(result["accepted"], result)

    def test_news_requires_an_authority_in_actual_source_text(self):
        text = "عيد الفطر يوم 20 مارس 2026."
        candidate = self.candidate(text, tier="state_news", domain="tap.info.tn", title="موعد العيد")
        decision = self.decision(text, date="2026-03-20", key="eid_fitr")
        self.assertFalse(self.validate(decision, [candidate], "eid_fitr")["accepted"])

    def test_two_distinct_news_domains_can_corroborate(self):
        text = "أعلن مفتي الجمهورية أن غرة رمضان يوم 19 فيفري 2026."
        candidates = [
            self.candidate(text, tier="trusted_news", domain="mosaiquefm.net"),
            self.candidate(text, candidate_id="a2", tier="trusted_news", domain="lapresse.tn"),
        ]
        decision = self.decision(text)
        decision["claims"].append(dict(decision["claims"][0], sourceId="a2"))
        result = self.validate(decision, candidates)
        self.assertTrue(result["accepted"], result)
        candidates[1] = dataclasses.replace(candidates[1], source_domain="mosaiquefm.net")
        self.assertFalse(self.validate(decision, candidates)["accepted"])

    def test_foreign_country_announcement_does_not_set_tunisia_date(self):
        text = "أعلن المفتي أن عيد الفطر في السعودية يوم 20 مارس 2026."
        candidate = self.candidate(text, tier="state_news", domain="tap.info.tn")
        self.assertFalse(self.validate(self.decision(text, date="2026-03-20", key="eid_fitr"), [candidate], "eid_fitr")["accepted"])

    def test_cropped_foreign_authority_still_rejects(self):
        text = "أعلن مفتي السعودية أن عيد الفطر سيكون يوم 20 مارس 2026."
        quote = "عيد الفطر سيكون يوم 20 مارس 2026"
        candidate = self.candidate(text, tier="state_news", domain="tap.info.tn")
        self.assertFalse(self.validate(self.decision(quote, date="2026-03-20", key="eid_fitr"), [candidate], "eid_fitr")["accepted"])

    def test_announcement_date_is_not_mistaken_for_relative_event_date(self):
        text = "Le 19 mars 2026, le mufti a annoncé que l’Aïd el-Fitr sera célébré demain."
        candidate = self.candidate(text, published="2026-03-19", tier="state_news", domain="tap.info.tn")
        wrong = self.decision(text, date="2026-03-19", key="eid_fitr")
        self.assertFalse(self.validate(wrong, [candidate], "eid_fitr")["accepted"])
        focused = self.decision("l’Aïd el-Fitr sera célébré demain", date="2026-03-20", key="eid_fitr")
        result = self.validate(focused, [candidate], "eid_fitr")
        self.assertTrue(result["accepted"], result)

    def test_reported_conflicts_block_publication(self):
        text = "أعلن مفتي الجمهورية أن غرة رمضان يوم 19 فيفري 2026."
        decision = self.decision(text)
        decision["conflicts"] = ["An official source instead says 20 February."]
        self.assertFalse(self.validate(decision, [self.candidate(text)])["accepted"])

    def test_omitted_conflicting_source_is_still_detected(self):
        text = "أعلن مفتي الجمهورية أن غرة رمضان يوم 19 فيفري 2026."
        conflicting = "أعلن مفتي الجمهورية أن غرة رمضان يوم 20 فيفري 2026."
        result = self.validate(self.decision(text), [self.candidate(text), self.candidate(conflicting, candidate_id="a2")])
        self.assertFalse(result["accepted"], result)
        self.assertIn("Conflicting", result["rejection_reason"])

    def test_malformed_model_fields_fail_closed_without_crashing(self):
        text = "أعلن مفتي الجمهورية أن غرة رمضان يوم 19 فيفري 2026."
        base = self.decision(text)
        malformed = [None, [], "not an object"]
        for field, value in (
            ("hijriYear", []), ("hijriYear", None), ("hijriYear", True),
            ("selectedDate", "2026-02-30"), ("selectedDate", {}),
            ("claims", {}), ("claims", [None]), ("conflicts", {}), ("conflicts", [None]),
        ):
            decision = copy.deepcopy(base)
            decision[field] = value
            malformed.append(decision)
        for field, value in (("sourceId", []), ("gregorianDate", "2026-02-30"), ("quote", {})):
            decision = copy.deepcopy(base)
            decision["claims"][0][field] = value
            malformed.append(decision)
        for decision in malformed:
            with self.subTest(decision=decision):
                self.assertFalse(self.validate(decision, [self.candidate(text)])["accepted"])

    def test_ambiguous_multiple_dates_in_one_quote_are_rejected(self):
        text = "غرة رمضان يوم 19 فيفري 2026 أو يوم 20 فيفري 2026."
        self.assertFalse(self.validate(self.decision(text), [self.candidate(text)])["accepted"])

    def test_announced_dhul_hijja_start_can_ground_eid_adha(self):
        text = "أعلن مفتي الجمهورية أن غرة شهر ذي الحجة يوم 18 ماي 2026."
        result = self.validate(self.decision(text, date="2026-05-27", key="eid_adha"), [self.candidate(text)], "eid_adha")
        self.assertTrue(result["accepted"], result)

    def test_actual_meteo_derivation_survives_validation(self):
        candidate = self.candidate(
            "تصبح الرؤية ممكنة يوم 18 فيفري 2026 بعد غروب الشمس.",
            title="المعهد الوطني للرصد الجوي: رؤية هلال رمضان 1447",
            domain="meteo.tn", provider="meteo_direct",
        )
        decision = lunar.deterministic_decision_from_candidates(lunar.EVENTS["ramadan_start"], 1447, [candidate])
        self.assertIsNotNone(decision)
        result = self.validate(decision, [candidate])
        self.assertTrue(result["accepted"], result)

    def test_meteo_model_date_must_equal_independently_derived_date(self):
        candidate = self.candidate(
            "تصبح الرؤية ممكنة يوم 18 فيفري 2026 بعد غروب الشمس.",
            title="المعهد الوطني للرصد الجوي: رؤية هلال رمضان 1447",
            domain="meteo.tn", provider="meteo_direct",
        )
        decision = lunar.deterministic_decision_from_candidates(lunar.EVENTS["ramadan_start"], 1447, [candidate])
        decision["selectedDate"] = decision["claims"][0]["gregorianDate"] = "2026-02-20"
        self.assertFalse(self.validate(decision, [candidate])["accepted"])

    def test_meteo_cannot_bypass_derivation_as_an_announcement(self):
        text = "غرة رمضان يوم 19 فيفري 2026."
        candidate = self.candidate(text, domain="meteo.tn", provider="meteo_direct")
        self.assertFalse(self.validate(self.decision(text), [candidate])["accepted"])

    def test_synthetic_candidate_and_unknown_source_cannot_be_evidence(self):
        text = "أعلن مفتي الجمهورية أن غرة رمضان يوم 19 فيفري 2026."
        candidate = self.candidate(text)
        unknown = self.decision(text, source_id="invented-source")
        self.assertFalse(self.validate(unknown, [candidate])["accepted"])
        synthetic = dataclasses.replace(candidate, provider="meteo_direct_derived")
        self.assertFalse(self.validate(self.decision(text), [synthetic])["accepted"])

    def test_malformed_message_content_raises_value_error(self):
        for value in (None, {}, [], 1447):
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    lunar.parse_json_object(value)

    def test_malformed_provider_envelope_is_rejected_without_network(self):
        response = unittest.mock.MagicMock()
        response.__enter__.return_value.read.return_value = json.dumps({"choices": []}).encode()
        with patch.object(lunar.urllib.request, "urlopen", return_value=response):
            with self.assertRaises(ValueError):
                lunar.ask_deepseek("dummy-key", lunar.EVENTS["ramadan_start"], 1447, dt.date(2026, 2, 18), [])


if __name__ == "__main__":
    unittest.main()
