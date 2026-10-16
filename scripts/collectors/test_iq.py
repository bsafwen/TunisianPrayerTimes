"""The Iraq collector, offline (python scripts/collectors/test_iq.py): the research's 2026 rows when at hand, else
snippets."""

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

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402
import iq  # noqa: E402

RESEARCH = common.LOCAL_DATA / "research-2026-10-08" / "IQ" / "iq" / "dws_2026.json"
NEW_API = "https://api.example-dws.iq/api/v2/prayer-times"


class FakeSession(common.Session):
    """common.Session saving under a temporary folder; fetch answers through [answer](url) (bytes, or an exception
    to raise)."""

    def __init__(self, root: Path, answer):
        super().__init__(root=root, pause=0, now=lambda: dt.datetime(2027, 1, 10, 8, 0, tzinfo=dt.timezone.utc))
        self.answer = answer
        self.asked: list[str] = []

    def fetch(self, url, *, data=None, headers=None, timeout=60.0):
        self.asked.append(url)
        result = self.answer(url)
        if isinstance(result, BaseException):
            raise result
        return result


def row_of(day: dt.date, closest: bool = False) -> bytes:
    """An answer shaped as the API's: [day]'s row, or the nearest 2026 row flagged is_closest."""
    date = dt.date(2026, 12, 31) if closest else day
    row = {"date": date.isoformat(), "fajr": "05:38", "sunrise": "07:05", "dhuhr": "12:11", "asr": "14:51",
           "maghrib": "17:09", "isha": "18:29", "is_today": False, "is_closest": closest}
    return json.dumps({"data": row}).encode("utf-8")


def asked_date(url: str) -> dt.date:
    return dt.date.fromisoformat(urllib.parse.parse_qs(urllib.parse.urlsplit(url).query)["date"][0])


def run(session, today: dt.date):
    """collect(), and the summary line it prints."""
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        try:
            return iq.collect(session, today), out.getvalue()
        except RuntimeError as error:
            error.printed = out.getvalue()
            raise


class RowTest(unittest.TestCase):
    def test_own_rows_and_dropped_rows(self):
        day = dt.date(2027, 3, 1)
        self.assertTrue(iq.own_row(row_of(day), day))
        self.assertFalse(iq.own_row(row_of(day, closest=True), day))
        self.assertFalse(iq.own_row(row_of(dt.date(2027, 3, 2)), day))
        for broken in (b'{"data": null}', b'{"message": "Server Error"}', b"<html>502</html>", b"[]"):
            with self.assertRaises(ValueError):
                iq.own_row(broken, day)

    def test_the_research_rows_read_as_own_rows(self):
        if not RESEARCH.exists():
            self.skipTest(f"{RESEARCH} is not at hand")
        rows = json.loads(RESEARCH.read_text(encoding="utf-8"))
        self.assertEqual(365, len(rows))
        for key, row in rows.items():
            self.assertTrue(iq.own_row(json.dumps({"data": row}).encode("utf-8"), dt.date.fromisoformat(key)), key)

    def test_days_order_and_names(self):
        self.assertEqual((365, 366), (len(iq.year_days(2027)), len(iq.year_days(2028))))
        order = iq.ask_order(iq.year_days(2027))
        self.assertEqual([dt.date(2027, m, 15) for m in range(1, 13)], order[:12])
        self.assertEqual(dt.date(2027, 1, 1), order[12])
        self.assertEqual("2027/dws_20270301.json", iq.file_name(dt.date(2027, 3, 1)))
        self.assertEqual("https://eldwanapp.jeeteak.com/api/prayer-times?date=2027-03-01",
                         iq.date_url(iq.API, dt.date(2027, 3, 1)))

    def test_addresses_in_the_site_bundle(self):
        page = ('<head><script type="module" crossorigin src="/assets/vendor-Ab12.js"></script>'
                '<script type="module" crossorigin src="/assets/index-DbFdmfS7.js"></script></head>')
        self.assertEqual(["https://dws.gov.iq/assets/index-DbFdmfS7.js", "https://dws.gov.iq/assets/vendor-Ab12.js"],
                         iq.script_urls(page))
        js = ('const Xe=Ue.create({baseURL:"https://eldwanapp.jeeteak.com/api",timeout:1e4});'
              'Xe.get("/prayer-times",{params:{date:t}});fetch("https://fonts.googleapis.com/css2?family=Cairo")')
        self.assertEqual([iq.API], iq.endpoints_in_script(js))
        self.assertEqual([NEW_API], iq.endpoints_in_script(f'const u="{NEW_API}?date="+d;'))
        self.assertEqual([], iq.endpoints_in_script('const b="https://cdn.example.com/api";'))


class CollectTest(unittest.TestCase):
    def test_nothing_is_asked_before_2027(self):
        with tempfile.TemporaryDirectory() as tmp:
            session = FakeSession(Path(tmp), lambda url: row_of(asked_date(url)))
            count, printed = run(session, dt.date(2026, 10, 16))
            self.assertEqual((0, []), (count, session.asked))
            self.assertIn("from January 2027", printed)

    def test_a_year_with_a_dropped_row_and_retried_dates(self):
        tries: dict[dt.date, int] = {}

        def api(url):
            day = asked_date(url)
            tries[day] = tries.get(day, 0) + 1
            if day in (dt.date(2027, 2, 3), dt.date(2027, 7, 15)) and tries[day] == 1:
                return urllib.error.URLError("timed out")
            return row_of(day, closest=day == dt.date(2027, 12, 31))

        with tempfile.TemporaryDirectory() as tmp:
            session = FakeSession(Path(tmp), api)
            count, printed = run(session, dt.date(2027, 1, 10))
            self.assertEqual(364, count)
            self.assertEqual(365 + 2, len(session.asked))
            self.assertEqual([dt.date(2027, m, 15) for m in range(1, 13)], [asked_date(u) for u in session.asked[:12]])
            folder = Path(tmp) / "iq" / "2027"
            self.assertEqual(364, len(list(folder.glob("dws_*.json"))))
            self.assertFalse((folder / "dws_20271231.json").exists())
            self.assertEqual(row_of(dt.date(2027, 2, 3)), (folder / "dws_20270203.json").read_bytes())
            self.assertIn("364 saved, 1 dropped", printed)
            # A rerun asks only the date without a file, and no probe: the year is served.
            session = FakeSession(Path(tmp), lambda url: row_of(asked_date(url), closest=True))
            count, printed = run(session, dt.date(2027, 6, 1))
            self.assertEqual((0, [dt.date(2027, 12, 31)]), (count, [asked_date(u) for u in session.asked]))

    def test_a_year_not_served_yet_stops_after_the_probe(self):
        with tempfile.TemporaryDirectory() as tmp:
            session = FakeSession(Path(tmp), lambda url: row_of(asked_date(url), closest=True))
            with self.assertRaisesRegex(RuntimeError, "2027 is not served yet"):
                run(session, dt.date(2027, 1, 10))
            self.assertEqual(12, len(session.asked))
            self.assertFalse((Path(tmp) / "iq").exists())

    def test_dates_still_failing_fail_the_run_after_the_rest_is_saved(self):
        def api(url):
            day = asked_date(url)
            return urllib.error.HTTPError(url, 500, "Server Error", {}, None) if day.month == 4 and day.day < 3 \
                else row_of(day)

        with tempfile.TemporaryDirectory() as tmp:
            session = FakeSession(Path(tmp), api)
            with self.assertRaisesRegex(RuntimeError, "2 dates failed after 2 retries.*2027-04-01, 2027-04-02"):
                run(session, dt.date(2027, 1, 10))
            self.assertEqual(365 + 2 * iq.RETRIES, len(session.asked))
            self.assertEqual(363, len(list((Path(tmp) / "iq" / "2027").glob("dws_*.json"))))

    def test_the_site_bundle_gives_a_new_address_when_the_first_request_fails(self):
        bundle = f'const api="{NEW_API}";'.encode()

        def answer(url):
            if url.startswith(iq.API):
                return urllib.error.URLError("getaddrinfo failed")
            if url == iq.SITE:
                return b'<script type="module" src="/assets/index-Zz99.js"></script>'
            if url == "https://dws.gov.iq/assets/index-Zz99.js":
                return bundle
            return row_of(asked_date(url))

        with tempfile.TemporaryDirectory() as tmp:
            session = FakeSession(Path(tmp), answer)
            count, printed = run(session, dt.date(2027, 1, 10))
            self.assertEqual(365, count)
            self.assertEqual([iq.date_url(iq.API, dt.date(2027, 1, 15)), iq.SITE,
                              "https://dws.gov.iq/assets/index-Zz99.js", iq.date_url(NEW_API, dt.date(2027, 2, 15))],
                             session.asked[:4])
            self.assertIn(f"API now at {NEW_API}", printed)
            rows = (Path(tmp) / "iq" / "MANIFEST.tsv").read_text(encoding="utf-8")
            self.assertIn(NEW_API + "?date=2027-01-15", rows)


if __name__ == "__main__":
    unittest.main()
