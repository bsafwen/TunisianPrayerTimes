"""The Qatar collector, offline (python scripts/collectors/test_qa.py): the saved /cal pages when at hand, else
snippets."""

import datetime as dt
import importlib.util
import json
import sys
import tempfile
import unittest
import urllib.error
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402
import qa  # noqa: E402

PAGE_1011 = common.LOCAL_DATA / "research-2026-10-11" / "QA" / "cal" / "cal_20261011.html"
PAGE_1008 = common.LOCAL_DATA / "research-2026-10-08" / "QA" / "qa" / "cal.html"
FIT = common.ROOT / "scripts" / "prayer_formula" / "fits" / "qa" / "fit.py"
DOCS = "https://www.qatarch.com/public/uploads/orginal/documents/"


class FakeSession(common.Session):
    """common.Session saving under a temporary folder; fetch answers from [pages] (bytes, or an exception to raise)."""

    def __init__(self, root: Path, pages: dict):
        super().__init__(root=root, pause=0, now=lambda: dt.datetime(2026, 10, 16, 8, 0, tzinfo=dt.timezone.utc))
        self.pages = pages
        self.asked: list[str] = []

    def fetch(self, url, *, data=None, headers=None, timeout=60.0):
        self.asked.append(url)
        answer = self.pages.get(url)
        if answer is None:
            raise urllib.error.HTTPError(url, 404, "Not Found", {}, None)
        if isinstance(answer, BaseException):
            raise answer
        return answer


def saved_page(path: Path) -> bytes:
    if not path.exists():
        raise unittest.SkipTest(f"{path} is not at hand")
    return path.read_bytes()


def load_fit():
    """fits/qa/fit.py as a module, importing its own `common` (fits/common.py, not this folder's)."""
    if not FIT.exists():
        raise unittest.SkipTest(f"{FIT} is not at hand")
    path, mine = list(sys.path), sys.modules.pop("common")
    try:
        spec = importlib.util.spec_from_file_location("qa_fit_for_test", FIT)
        fit = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(fit)
        return fit
    finally:
        sys.modules["common"] = mine
        sys.path[:] = path


def snippet(mark: str = "16", cities=("3", "6", "13", "14", "15", "16"), pdfs=(), other: str = "04:44") -> bytes:
    """A /cal page in miniature: calData with the day [mark] as today, prayData for [cities] and one Gulf city (its
    Fajr [other]), links to [pdfs]."""
    pray = [{"cityId": int(c), "date_h": "5", "fajr": "04:20", "shrouq": "05:37", "thahr": "11:20", "aser": "02:40",
             "moghreb": "05:05", "ishaa": "06:35"} for c in cities]
    pray.append({"cityId": 9, "date_h": "5", "fajr": other, "shrouq": "06:20", "thahr": "12:09", "aser": "03:36",
                 "moghreb": "06:01", "ishaa": "07:31"})
    cal = {"year": "1448", "days": [{"month": 5, "days": [{"h": "1", "m": "12", "today": False},
                                                          {"h": "5", "m": mark, "today": True}]}]}
    links = "".join(f'<a  href="{DOCS}{name}"\n download="wall calendar {i}" >PDF</a>' for i, name in enumerate(pdfs))
    return (f"<html><body><script>\nvar calData =  {json.dumps(cal)}; \nvar prayData =  {json.dumps(pray)};\n"
            f"</script>{links}</body></html>").encode("utf-8")


class SavedPageTest(unittest.TestCase):
    def test_times_and_day_of_the_saved_pages(self):
        page = saved_page(PAGE_1011).decode("utf-8")
        data = qa.pray_data(page)
        self.assertTrue(qa.complete(data))
        self.assertEqual(13, len(data))
        self.assertEqual(["04:13", "05:30", "11:22", "14:42", "17:13", "18:43"], [data["3"][k] for k in qa.TIMES])
        self.assertEqual(11, qa.marked_day(page))
        self.assertEqual(dt.date(2026, 10, 11), qa.page_date(page, dt.date(2026, 10, 11)))
        # A run after 21:00 UTC on the 10th: Qatar is already on the 11th.
        self.assertEqual(dt.date(2026, 10, 11), qa.page_date(page, dt.date(2026, 10, 10)))
        self.assertIsNone(qa.page_date(page, dt.date(2026, 10, 1)))
        earlier = saved_page(PAGE_1008).decode("utf-8")
        self.assertEqual(dt.date(2026, 10, 8), qa.page_date(earlier, dt.date(2026, 10, 8)))

    def test_wall_calendars_of_the_saved_page(self):
        links = qa.wall_calendars(saved_page(PAGE_1011).decode("utf-8"))
        self.assertEqual([DOCS + "4)%20Rabia%20II%201448.pdf", DOCS + "3)%20Rabia%20I%201448.pdf"],
                         [u for u, _ in links])
        self.assertEqual(["pdfs/4_Rabia_II_1448.pdf", "pdfs/3_Rabia_I_1448.pdf"], [qa.pdf_name(u) for u, _ in links])
        self.assertIn("1448", links[0][1])

    def test_collect_saves_what_the_fit_reads(self):
        page = saved_page(PAGE_1011)
        saved_page(PAGE_1008)                           # the fit's cal_pages always reads it too
        fit = load_fit()
        pdf_1 = DOCS + "4)%20Rabia%20II%201448.pdf"
        pdf_2 = DOCS + "3)%20Rabia%20I%201448.pdf"
        with tempfile.TemporaryDirectory() as tmp:
            pages = {qa.URL: page, pdf_1: b"%PDF-1.7 one", pdf_2: b"%PDF-1.7 two"}
            session = FakeSession(Path(tmp), pages)
            self.assertEqual(3, qa.collect(session, dt.date(2026, 10, 10)))
            folder = Path(tmp) / "qa"
            self.assertEqual(page, (folder / "cal" / "cal_20261011.html").read_bytes())
            self.assertEqual(b"%PDF-1.7 one", (folder / "pdfs" / "4_Rabia_II_1448.pdf").read_bytes())
            rows = (folder / "MANIFEST.tsv").read_text(encoding="utf-8").splitlines()
            self.assertEqual(4, len(rows))
            self.assertIn("prayData of 2026-10-11", rows[1])
            self.assertIn("Doha 04:13 05:30 11:22 14:42 17:13 18:43", rows[1])
            # The fit reads the saved page under its date: Doha's six minutes, afternoon times on the 24-hour clock.
            cal = fit.cal_pages(folder / "cal")
            self.assertEqual([253, 330, 682, 882, 1033, 1123], cal[dt.date(2026, 10, 11)]["3"])
            # The next run: the same page is kept, the PDFs are not asked again.
            session = FakeSession(Path(tmp), pages)
            self.assertEqual(0, qa.collect(session, dt.date(2026, 10, 11)))
            self.assertEqual([qa.URL], session.asked)


class SnippetTest(unittest.TestCase):
    def test_day_marked_across_a_month_end(self):
        self.assertEqual(dt.date(2026, 11, 1), qa.page_date(snippet("1").decode(), dt.date(2026, 10, 31)))
        self.assertEqual(dt.date(2026, 10, 31), qa.page_date(snippet("31").decode(), dt.date(2026, 11, 1)))
        self.assertEqual(dt.date(2027, 1, 1), qa.page_date(snippet("01").decode(), dt.date(2026, 12, 31)))

    def test_quoted_links(self):
        self.assertEqual(DOCS + "5)%20Jumada%20I%201448.pdf", qa.quote_url(DOCS + "5) Jumada I 1448.pdf"))
        self.assertEqual(DOCS + "5)%20Jumada%20I%201448.pdf", qa.quote_url(DOCS + "5)%20Jumada%20I%201448.pdf"))
        page = (snippet(pdfs=("5) Jumada I 1448.pdf",)).decode()
                + '<a href="https://www.qatarch.com/public/uploads/orginal/emsakia/emsakya.pdf">imsakiya</a>'
                + f'<a href="https://twitter.com/intent/tweet?url={DOCS}x.pdf">share</a>')
        self.assertEqual([(DOCS + "5)%20Jumada%20I%201448.pdf", "wall calendar 0")], qa.wall_calendars(page))

    def test_a_page_without_the_six_places_is_kept_apart_and_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            session = FakeSession(Path(tmp), {qa.URL: snippet(cities=("3", "6", "13", "14", "15"))})
            with self.assertRaisesRegex(RuntimeError, "six Qatari places"):
                qa.collect(session, dt.date(2026, 10, 16))
            self.assertTrue((Path(tmp) / "qa" / "cal-unparsed" / "cal_20261016.html").exists())
            self.assertFalse((Path(tmp) / "qa" / "cal").exists())

    def test_a_time_the_fit_cannot_read_keeps_the_page_apart(self):
        self.assertTrue(qa.complete(qa.pray_data(snippet().decode())))
        self.assertTrue(qa.complete(qa.pray_data(snippet(other="4:5").decode())))     # the fit's hm reads it
        self.assertFalse(qa.complete(qa.pray_data(snippet(other="").decode())))       # another city's row, but the
        self.assertFalse(qa.complete(qa.pray_data(snippet(other="04:44 AM").decode())))   # fit reads every row
        self.assertIsNone(qa.pray_data('var prayData = [{"fajr": "04:20"}];'))           # a row without its cityId

    def test_a_page_marking_another_day_is_kept_apart_and_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            session = FakeSession(Path(tmp), {qa.URL: snippet("20")})
            with self.assertRaisesRegex(RuntimeError, "marks day 20"):
                qa.collect(session, dt.date(2026, 10, 16))
            self.assertTrue((Path(tmp) / "qa" / "cal-unparsed" / "cal_20261016.html").exists())

    def test_a_failed_pdf_keeps_the_page_and_fails_the_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            pages = {qa.URL: snippet(pdfs=("6) Jumada II 1448.pdf", "7) Rajab 1448.pdf", "8) Shaban 1448.pdf")),
                     DOCS + "6)%20Jumada%20II%201448.pdf": urllib.error.URLError("timed out"),
                     DOCS + "7)%20Rajab%201448.pdf": b"<html>error</html>"}
            session = FakeSession(Path(tmp), pages)
            with self.assertRaisesRegex(RuntimeError, "6_Jumada_II_1448.pdf: URLError.*7_Rajab_1448.pdf: not a PDF"):
                qa.collect(session, dt.date(2026, 10, 16))
            self.assertTrue((Path(tmp) / "qa" / "cal" / "cal_20261016.html").exists())
            # At most MAX_PDFS new calendars a run: the third is left for the next one.
            self.assertEqual(1 + qa.MAX_PDFS, len(session.asked))


if __name__ == "__main__":
    unittest.main()
