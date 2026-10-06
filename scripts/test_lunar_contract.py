"""The publisher produces exactly the payload consumed by the shared app tests."""
import datetime as dt
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import detect_tunisian_lunar_dates as detector


class PublisherAppContractTest(unittest.TestCase):
    def test_committed_announcements_form_valid_lunar_months_across_years(self):
        root = Path(__file__).resolve().parents[1]
        records = sorted((root / "data/official-islamic-dates").glob("*.json"))
        self.assertTrue(records, "The app must retain its bundled official announcements")
        anchors = []
        for path in records:
            with self.subTest(file=path.name):
                self.assertRegex(path.name, r"^\d{4}\.json$")
                year = int(path.stem)
                record = json.loads(path.read_text(encoding="utf-8"))
                detector.validate_override_record(record, year)
                for event in detector.EVENTS.values():
                    value = record.get(event.override_field)
                    if value is not None:
                        start = dt.date.fromisoformat(value) - dt.timedelta(days=event.hijri_day - 1)
                        anchors.append((year * 12 + event.hijri_month, start, f"{year}.{event.override_field}"))
        detector.validate_anchor_spacing(anchors)

    def test_successive_announcements_match_app_contract_fixture(self):
        fixture = Path(__file__).resolve().parents[1] / "test-data/islamic-calendar/1447.json"
        expected = json.loads(fixture.read_text(encoding="utf-8"))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for event_key, date_field, updated_field in (
                ("ramadan_start", "ramadanStart", "ramadanStartUpdated"),
                ("eid_fitr", "eidFitrDate", "eidFitrUpdated"),
                ("eid_adha", "eidAdhaDate", "eidAdhaUpdated"),
            ):
                announced_at = dt.datetime.fromisoformat(expected[updated_field])

                class AnnouncementClock(dt.datetime):
                    @classmethod
                    def now(cls, tz=None):
                        return announced_at.astimezone(tz)

                with patch.object(detector.dt, "datetime", AnnouncementClock):
                    self.assertTrue(detector.update_override_file(
                        root, detector.EVENTS[event_key], 1447, expected[date_field], False,
                    ))
            published = json.loads(detector.official_dates_path(root, 1447).read_text(encoding="utf-8"))
            self.assertEqual(expected, published)


if __name__ == "__main__":
    unittest.main()
