"""The Egypt collector, offline (python -I scripts/collectors/test_eg.py). The research's saved pages serve where
test-data-local holds them (those tests skip without it); a synthetic page with made-up times serves everywhere else."""

import contextlib
import datetime as dt
import html
import io
import itertools
import re
import sys
import tempfile
import unittest
import urllib.error
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402
import eg  # noqa: E402

PAGE_FILE = common.LOCAL_DATA / "research-2026-10-08" / "EG" / "eg" / "praytimes.html"
WAYBACK_FILE = common.LOCAL_DATA / "research-2026-10-11" / "EG" / "wayback" / "20250705181836.html"
SKIP = "test-data-local's EG research pages are absent"
TODAY = dt.date(2026, 10, 16)
TIMES = ("4:30 ص", "6:0 ص", "12:0 م", "3:30 م", "6:0 م", "7:30 م")     # made up, written as the page writes them


def synthetic_page(day: dt.date = TODAY, cities=("القاهرة", "الأسكندرية"), times=TIMES) -> bytes:
    """Shaped like praytimes.aspx: the GridView with its header row and one row per city."""
    head = "<tr><th>المدينة</th><th>التاريخ الميلادي</th><th>التاريخ الهجري</th>" + "<th>x</th>" * 6 + "</tr>"
    rows = "".join(f"<tr><td><font>{c}</font></td><td><font>{day.isoformat()}</font></td><td><font>4 جمادى الأولى 1448"
                   "</font></td>" + "".join(f"<td><font>{t} </font></td>" for t in times) + "</tr>" for c in cities)
    return (f'<html><body><form method="post" action="./praytimes.aspx"><table cellspacing="0" '
            f'id="placeholder1_GridView1" width="100%">{head}{rows}</table></form></body></html>').encode()


def fit_rows(content: bytes) -> dict:
    """fits/eg_egsa/fit.py's page_rows() and clock(), restated: date -> city -> the six minutes."""
    def clock(text):
        m = re.fullmatch(r"(\d{1,2}):(\d{1,2})\s*(ص|م)", text.strip())
        h, minute = int(m.group(1)), int(m.group(2))
        h += 12 if m.group(3) == "م" and h != 12 else 0
        return 60 * (0 if m.group(3) == "ص" and h == 12 else h) + minute
    table = re.search(r'id="placeholder1_GridView1".*?</table>', content.decode("utf-8", errors="replace"), re.S)
    out: dict = {}
    for tr in re.findall(r"<tr.*?</tr>", table.group(0), re.S):
        cells = [html.unescape(re.sub(r"<[^>]+>", "", c)).strip() for c in re.findall(r"<td.*?</td>", tr, re.S)]
        if len(cells) == 9:
            out.setdefault(dt.date.fromisoformat(cells[1]), {})[cells[0]] = [clock(c) for c in cells[3:]]
    return out


class FakeSession(common.Session):
    """The real save() under a temporary root; fetch() answers from [answers] in turn (bytes, or an exception it
    raises) and records each call."""

    def __init__(self, root, *answers):
        clock = (dt.datetime(2026, 10, 16, 8, 0, tzinfo=dt.timezone.utc) + dt.timedelta(seconds=s)
                 for s in itertools.count())
        super().__init__(root=Path(root), pause=0, now=lambda: next(clock))
        self.answers, self.calls = list(answers), []

    def fetch(self, url, *, data=None, headers=None, timeout=60.0):
        self.calls.append((url, data, headers))
        result = self.answers.pop(0) if len(self.answers) > 1 else self.answers[0]
        if isinstance(result, Exception):
            raise result
        return result


def run(session, today=TODAY):
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        count = eg.collect(session, today)
    return count, out.getvalue()


def saved_names(root) -> list[str]:
    folder = Path(root) / "eg"
    return sorted(p.relative_to(folder).as_posix() for p in folder.rglob("*.html"))


class RowsTest(unittest.TestCase):
    def test_rows(self):
        self.assertEqual([("القاهرة", TODAY), ("الأسكندرية", TODAY)], eg.table_rows(synthetic_page().decode()))
        self.assertEqual([], eg.table_rows("<html><body>Service Unavailable</body></html>"))

    def test_a_row_the_fit_could_not_read(self):
        with self.assertRaisesRegex(ValueError, "a row dated"):
            eg.table_rows(synthetic_page().decode().replace(TODAY.isoformat(), "16/10/2026", 1))
        with self.assertRaisesRegex(ValueError, "a row with the times"):
            eg.table_rows(synthetic_page(times=TIMES[:5] + ("--",)).decode())

    def test_names_and_contract(self):
        self.assertEqual("praytimes/praytimes_2026-10-16.html", eg.page_name(TODAY))
        self.assertEqual("praytimes-unparsed/praytimes_2026-10-16.html", eg.unparsed_name(TODAY))
        self.assertEqual(("eg", "EG", "daily"), (eg.NAME, eg.CC, eg.CADENCE))
        self.assertTrue(eg.DESCRIPTION.isascii())
        self.assertEqual("https://www.esa.gov.eg/praytimes.aspx", eg.URL)

    @unittest.skipUnless(PAGE_FILE.exists() and WAYBACK_FILE.exists(), SKIP)
    def test_research_pages(self):
        rows = eg.table_rows(PAGE_FILE.read_text(encoding="utf-8", errors="replace"))
        self.assertEqual((78, {dt.date(2026, 10, 8)}), (len(rows), {d for _, d in rows}))
        rows = eg.table_rows(WAYBACK_FILE.read_text(encoding="utf-8", errors="replace"))
        self.assertEqual({dt.date(2025, 7, 5)}, {d for _, d in rows})


class CollectTest(unittest.TestCase):
    def test_a_day(self):
        page = synthetic_page()
        with tempfile.TemporaryDirectory() as tmp:
            session = FakeSession(tmp, page)
            count, out = run(session)
            self.assertEqual(1, count)
            self.assertEqual([(eg.URL, None, None)], session.calls)        # one plain GET
            self.assertEqual(["praytimes/praytimes_2026-10-16.html"], saved_names(tmp))
            saved = Path(tmp) / "eg" / "praytimes" / "praytimes_2026-10-16.html"
            self.assertEqual(page, saved.read_bytes())
            row = (Path(tmp) / "eg" / "MANIFEST.tsv").read_text(encoding="utf-8").splitlines()[1].split("\t")
            self.assertEqual(["praytimes/praytimes_2026-10-16.html", str(len(page)), common.sha256(page)], row[:3])
            self.assertEqual([f"GET {eg.URL}", "EGSA all-cities table of 2026-10-16: 2 cities"], row[4:])
            self.assertEqual("eg: 2026-10-16, 2 cities\n", out)
            # What the fit reads from it.
            self.assertEqual({TODAY: {"القاهرة": [270, 360, 720, 930, 1080, 1170],
                                      "الأسكندرية": [270, 360, 720, 930, 1080, 1170]}}, fit_rows(saved.read_bytes()))
            # The same bytes again: nothing new.
            self.assertEqual((0, "eg: 2026-10-16, 2 cities, already saved\n"), run(session))

    def test_the_page_is_named_by_the_date_it_prints(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = run(FakeSession(tmp, synthetic_page(day=dt.date(2026, 10, 17))))[1]
            self.assertEqual(["praytimes/praytimes_2026-10-17.html"], saved_names(tmp))
            self.assertEqual("eg: 2026-10-17, 2 cities\n", out)
            run(FakeSession(tmp, synthetic_page(day=dt.date(2026, 10, 12))))
            self.assertIn("praytimes/praytimes_2026-10-12.html", saved_names(tmp))

    @unittest.skipUnless(PAGE_FILE.exists() and WAYBACK_FILE.exists(), SKIP)
    def test_research_pages_are_saved_byte_for_byte(self):
        with tempfile.TemporaryDirectory() as tmp:
            run(FakeSession(tmp, PAGE_FILE.read_bytes()), dt.date(2026, 10, 8))
            run(FakeSession(tmp, WAYBACK_FILE.read_bytes()), dt.date(2025, 7, 6))
            self.assertEqual(["praytimes/praytimes_2025-07-05.html", "praytimes/praytimes_2026-10-08.html"],
                             saved_names(tmp))
            saved = (Path(tmp) / "eg" / "praytimes" / "praytimes_2026-10-08.html").read_bytes()
            self.assertEqual(PAGE_FILE.read_bytes(), saved)
            self.assertEqual(78, len(fit_rows(saved)[dt.date(2026, 10, 8)]))

    def test_a_failed_request_is_made_once_more(self):
        with tempfile.TemporaryDirectory() as tmp:
            session = FakeSession(tmp, urllib.error.URLError("timed out"), synthetic_page())
            self.assertEqual(1, run(session)[0])
            self.assertEqual(2, len(session.calls))
        with tempfile.TemporaryDirectory() as tmp:
            session = FakeSession(tmp, urllib.error.URLError("timed out"))
            with self.assertRaisesRegex(RuntimeError, "URLError"):
                run(session)
            self.assertEqual((2, []), (len(session.calls), saved_names(tmp)))

    def test_a_page_without_the_table_is_kept_apart_and_fails_the_run(self):
        down = b"<html><body><h1>Service Unavailable</h1></body></html>"
        with tempfile.TemporaryDirectory() as tmp:
            session = FakeSession(tmp, down)
            with self.assertRaisesRegex(RuntimeError, "no row in the all-cities table; the page was kept"):
                run(session)
            self.assertEqual(2, len(session.calls))
            self.assertEqual(["praytimes-unparsed/praytimes_2026-10-16.html"], saved_names(tmp))
        bad_row = synthetic_page(times=TIMES[:5] + ("--",))
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(RuntimeError, "a row with the times"):
                run(FakeSession(tmp, bad_row))
            self.assertEqual(["praytimes-unparsed/praytimes_2026-10-16.html"], saved_names(tmp))
        # A bad first answer and a good second one: only the good one is kept.
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(1, run(FakeSession(tmp, down, synthetic_page()))[0])
            self.assertEqual(["praytimes/praytimes_2026-10-16.html"], saved_names(tmp))


if __name__ == "__main__":
    unittest.main()
