"""The Yemen collector, offline (python scripts/collectors/test_ye.py): a saved answer when at hand, else snippets."""

import contextlib
import datetime as dt
import io
import json
import sys
import tempfile
import unittest
import urllib.error
import urllib.parse
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402
import ye  # noqa: E402

SAVED = common.LOCAL_DATA / "official" / "ye" / "d20260521.json"
EMPTY = b'{"message":{"doc":null,"locations":[]}}'


class FakeSession(common.Session):
    """common.Session saving under a temporary folder; fetch answers through [answer](url) (bytes, or an exception
    to raise)."""

    def __init__(self, root: Path, answer):
        super().__init__(root=root, pause=0, now=lambda: dt.datetime(2026, 10, 16, 8, 0, tzinfo=dt.timezone.utc))
        self.answer = answer
        self.asked: list[str] = []

    def fetch(self, url, *, data=None, headers=None, timeout=60.0):
        self.asked.append(url)
        self.headers = headers
        result = self.answer(url)
        if isinstance(result, BaseException):
            raise result
        return result


def answer_of(day: dt.date, season: int = 4) -> bytes:
    """An answer shaped as the endpoint's, two locations."""
    doc = {"name": day.isoformat(), "fetch_date": day.isoformat(), "api_updated_at": f"{day} 23:30:10.165330",
           "hijri_date": "1 / 5 / 1448", "gregorian_date": f"{day.day} / {day.month} / {day.year}", "location_count": 2,
           "prayer_time_count": 2, "season": season, "day_of_week": 1}
    locations = [{"location_name": "Sanaa", "location_id": 1, "fajr": "4:42:00", "sunrise": "5:54:00",
                  "duhur": "12:01:00", "asr": "3:12:00", "maghrib": "5:57:00", "isha": "6:51:00", "amsek": "4:42:00"},
                 {"location_name": "Aden", "location_id": 2, "fajr": "4:39:00", "sunrise": "5:52:00",
                  "duhur": "11:56:00", "asr": "3:08:00", "maghrib": "5:52:00", "isha": "6:45:00", "amsek": "4:39:00"}]
    return json.dumps({"message": {"doc": doc, "locations": locations}}, ensure_ascii=False).encode("utf-8")


def asked_date(url: str) -> dt.date:
    return dt.date.fromisoformat(urllib.parse.parse_qs(urllib.parse.urlsplit(url).query)["fetch_date"][0])


def run(session, today: dt.date):
    """collect(), and the summary line it prints (kept off the test output)."""
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        try:
            return ye.collect(session, today), out.getvalue()
        except RuntimeError as error:
            error.printed = out.getvalue()
            raise


class AnswerTest(unittest.TestCase):
    def test_saved_answer(self):
        if not SAVED.exists():
            self.skipTest(f"{SAVED} is not at hand")
        doc = ye.answer(SAVED.read_bytes(), dt.date(2026, 5, 21))
        self.assertEqual((3, 31), (doc["season"], doc["locations"]))
        self.assertEqual("General Authority of Awqaf and Guidance (Sanaa), Tebyan panel JSON, 31 locations, season 3, "
                         "api_updated_at 2026-05-21 23:30:24.902297", ye.note(doc))
        self.assertIsNone(ye.answer(SAVED.read_bytes(), dt.date(2026, 5, 22)))     # another date's doc

    def test_empty_and_broken_answers(self):
        day = dt.date(2026, 6, 15)
        self.assertIsNone(ye.answer(EMPTY, day))
        self.assertIsNone(ye.answer(b'{"message":{}}', day))
        self.assertIsNone(ye.answer(b'{"message":null}', day))
        self.assertIsNone(ye.answer(b"{}", day))
        with self.assertRaises(ValueError):
            ye.answer(b"<html>502 Bad Gateway</html>", day)

    def test_address(self):
        self.assertEqual("https://awqaf.gov.ye/api/method/blue_theme.blue_theme.page.tebyan_prayer_panel."
                         "tebyan_prayer_panel.get_page_data?fetch_date=2026-05-21", ye.date_url(dt.date(2026, 5, 21)))

    def test_wanted_dates_and_spans(self):
        have = {"d20260521.json", "d20260523.json"}
        days = ye.wanted(dt.date(2026, 5, 25), lambda name: name in have)
        self.assertEqual(dt.date(2026, 5, 22), days[0])
        self.assertEqual(dt.date(2026, 6, 25), days[-1])                   # today + 31
        self.assertEqual(36 - 2, len(days))
        self.assertNotIn(dt.date(2026, 5, 23), days)
        june = [dt.date(2026, 6, d) for d in (15, 16, 17, 28)]
        self.assertEqual("2026-06-15..2026-06-17, 2026-06-28", ye.spans(june))


class CollectTest(unittest.TestCase):
    def test_backfill_then_only_the_new_dates(self):
        today = dt.date(2026, 6, 20)
        gaps = {dt.date(2026, 6, 15), dt.date(2026, 6, 16)}

        def endpoint(url):
            day = asked_date(url)
            if day == dt.date(2026, 6, 1):
                return urllib.error.URLError("timed out")
            return EMPTY if day > today or day in gaps else answer_of(day, 3)

        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp) / "ye"
            folder.mkdir()
            (folder / "d20260521.json").write_bytes(answer_of(dt.date(2026, 5, 21), 3))
            session = FakeSession(Path(tmp), endpoint)
            with self.assertRaisesRegex(RuntimeError, r"2026-06-01 \(URLError") as failure:
                run(session, today)
            self.assertEqual("ye: 61 dates asked, 27 saved, 33 empty (2026-06-15..2026-06-16, 2026-06-21..2026-07-21), "
                             "1 failed\n", failure.exception.printed)
            # 2026-05-21..07-21 (today + 31) is 62 dates; 05-21 has its file.
            self.assertEqual(61, len(session.asked))
            self.assertEqual({"Accept": "application/json"}, session.headers)
            asked = [asked_date(u) for u in session.asked]
            self.assertEqual((dt.date(2026, 5, 22), today + dt.timedelta(days=ye.AHEAD)), (asked[0], asked[-1]))
            self.assertEqual(sorted(asked), asked)
            files = sorted(f.name for f in folder.glob("d*.json"))
            # 2026-05-21..06-20 (31 dates): the one already there, 27 new, the failed one and the two gaps missing.
            self.assertEqual(28, len(files))
            self.assertNotIn("d20260601.json", files)
            self.assertNotIn("d20260615.json", files)
            rows = (folder / "MANIFEST.tsv").read_text(encoding="utf-8").splitlines()
            self.assertEqual(1 + 27, len(rows))
            self.assertTrue(rows[1].startswith("d20260522.json\t"))
            self.assertTrue(rows[1].endswith("\tGeneral Authority of Awqaf and Guidance (Sanaa), Tebyan panel JSON, "
                                             "2 locations, season 3, api_updated_at 2026-05-22 23:30:10.165330"))

            # The next run asks only the dates without a file: the failed one, the gaps and the days ahead.
            session = FakeSession(Path(tmp), lambda url: answer_of(asked_date(url), 4))
            self.assertEqual(1 + 2 + ye.AHEAD, run(session, today)[0])
            self.assertEqual(1 + 2 + ye.AHEAD, len(session.asked))
            self.assertIn("season 4", (folder / "MANIFEST.tsv").read_text(encoding="utf-8").splitlines()[-1])

    def test_a_run_stops_at_the_request_cap(self):
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(ye, "MAX_REQUESTS", 5):
            session = FakeSession(Path(tmp), lambda url: answer_of(asked_date(url)))
            # 2026-05-21..11-16 (today + 31) is 180 dates.
            self.assertEqual((5, "ye: 5 dates asked, 5 saved, 0 empty, 0 failed; 175 left for the next run\n"),
                             run(session, dt.date(2026, 10, 16)))
            self.assertEqual([dt.date(2026, 5, 21) + dt.timedelta(days=i) for i in range(5)],
                             [asked_date(u) for u in session.asked])


if __name__ == "__main__":
    unittest.main()
