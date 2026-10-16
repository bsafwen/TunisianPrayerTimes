"""The Morocco collector, offline (python -I scripts/collectors/test_ma.py). The research's saved pages serve where
test-data-local holds them (those tests skip without it); synthetic pages with made-up times serve everywhere else."""

import contextlib
import datetime as dt
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
import ma  # noqa: E402

RESEARCH = common.LOCAL_DATA / "research-2026-10-08" / "MA"
ARCHIVE = common.LOCAL_DATA / "research-2026-10-11" / "MA" / "wayback"
RABAT_FILE = RESEARCH / "skeptic_ma" / "v1.html"
HAVE_RESEARCH = RABAT_FILE.exists() and (ARCHIVE / "rabat_202412.txt").exists()
SKIP = "test-data-local's MA research pages are absent"
TODAY = dt.date(2026, 10, 16)
IDS = ("1", "2", "301")
RABI_II = "ربيع الآخر 1448"
JUMADA_I = "جمادى الأولى 1448"


def synthetic_page(vid="1", ids=IDS, month=RABI_II, days=3, fajr="05:00") -> bytes:
    """Shaped like horaire_hijri_2.php: the month's header, the locality selector with [vid] marked, the table."""
    options = "".join(f"<option value='index.php?ville={i}' {'selected=selected' if i == vid else ''} >مدينة {i}</option>"
                      for i in ids)
    head = "".join(f'<td class="wakt">{t}</td>' for t in ("الأيام", "ربيع الآخر", "شتنبر / أكتوبر", "الصبح", "الشروق",
                                                          "الظهر", "العصر", "المغرب", "العشاء"))
    rows = "".join(f'<tr align="center"><td>الأحد</td><td>{k + 1} </td><td>{13 + k} </td><td>{fajr}</td><td>06:30</td>'
                   f"<td>12:00 </td><td>\n15:30 </td><td>18:00</td><td> 19:30</td></tr>" for k in range(days))
    return ('<!DOCTYPE html><head><title>مواقيت الصلاة</title><style>#horaire{direction:rtl;}</style></head><body>'
            f'<div class="priere-section-month"><p class="first">\n{month}   \n</p><p> شتنبر / أكتوبر 2026 </p></div>'
            '<form id="MyForm" action="" method="post" name="horairep"><select name="ville" '
            f'onChange="location.href=this.options[this.selectedIndex].value;" style="">{options}</select></form>'
            f'<table id="horaire" cellspacing="0" cellpadding="0"><tr align="center">{head}</tr>{rows}</table>'
            "</body></html>").encode()


def fit_reads(path: Path) -> tuple[str, int]:
    """What fits/ma_habous/fit.py takes from a saved page (load_tables, restated): the locality, the last number of
    the file name, and the Gregorian month its header names first."""
    months = {"شتنبر": 9, "أكتوبر": 10}
    plain = re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " | ", path.read_text(encoding="utf-8", errors="replace")))
    header = re.search(r"([^\s|/]+)\s*/\s*([^\s|/]+)\s+(20\d\d)", plain)
    return re.findall(r"(\d+)", path.stem)[-1], months[header.group(1)]


class FakeSession(common.Session):
    """The real save() under a temporary root; fetch() answers [pages](id) (bytes, or an exception it raises) and
    records each id asked."""

    def __init__(self, root, pages):
        clock = (dt.datetime(2026, 10, 16, 8, 0, tzinfo=dt.timezone.utc) + dt.timedelta(seconds=s)
                 for s in itertools.count())
        super().__init__(root=Path(root), pause=0, now=lambda: next(clock))
        self.pages, self.asked = pages, []

    def fetch(self, url, *, data=None, headers=None, timeout=60.0):
        assert data is None and headers is None and url.startswith(ma.PAGE.format("")), url
        vid = url[len(ma.PAGE.format("")):]
        self.asked.append(vid)
        result = self.pages(vid)
        if isinstance(result, Exception):
            raise result
        return result


def run(session, today=TODAY):
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        count = ma.collect(session, today)
    return count, out.getvalue()


def saved_names(root) -> list[str]:
    folder = Path(root) / "ma"
    return sorted(p.relative_to(folder).as_posix() for p in folder.rglob("*.html"))


class MonthTest(unittest.TestCase):
    def test_month_names(self):
        names = {"محرم": 1, "المحرم": 1, "صفر": 2, "ربيع الأول": 3, "ربيع الاول": 3, "ربيع الآخر": 4, "ربيع الثاني": 4,
                 "جمادى الأولى": 5, "جمادى الأول": 5, "جمادى الآخرة": 6, "جمادى الثانية": 6, "رجب": 7, "شعبان": 8,
                 "رمضان": 9, "شوال": 10, "ذو القعدة": 11, "ذي القعدة": 11, "ذو الحجة": 12, "ذي الحجة": 12,
                 "ربيـــــع الثانى": 4}
        for name, number in names.items():
            self.assertEqual(number, ma.hijri_month_number(name), name)
        for other in ("شتنبر", "أكتوبر", "ربيع", "الأيام", "مواقيت الصلاة"):
            self.assertIsNone(ma.hijri_month_number(other), other)

    def test_keywords_are_plain_arabic_letters(self):
        # No look-alike letter (Farsi yeh U+06CC, keheh U+06A9 ...) and no mark: the folded keywords must match the pages.
        for word in (ma._ARTICLE, *ma._PAIRS, *ma._FIRST, *ma._SECOND, *ma._ONE_WORD):
            self.assertTrue(all(0x0621 <= ord(c) <= 0x063A or 0x0641 <= ord(c) <= 0x064A for c in word), ascii(word))

    def test_month_folder(self):
        self.assertEqual((1448, 4), ma.hijri_month(synthetic_page().decode()))
        self.assertEqual("1448-04", ma.month_folder(synthetic_page().decode(), TODAY))
        self.assertEqual("1448-05", ma.month_folder(synthetic_page(month=JUMADA_I).decode(), TODAY))
        # A header without the year (the 2024 pages): the run's Gregorian month.
        self.assertIsNone(ma.hijri_month(synthetic_page(month="ربيع الآخر").decode()))
        self.assertEqual("2026-10", ma.month_folder(synthetic_page(month="ربيع الآخر").decode(), TODAY))

    @unittest.skipUnless(HAVE_RESEARCH, SKIP)
    def test_research_headers(self):
        self.assertEqual("1448-04", ma.month_folder(RABAT_FILE.read_text(encoding="utf-8", errors="replace"), TODAY))
        for name in ("rabat_202406.txt", "laayoune_202406.txt", "rabat_202412.txt"):
            page = (ARCHIVE / name).read_text(encoding="utf-8", errors="replace")
            self.assertEqual("2024-06", ma.month_folder(page, dt.date(2024, 6, 4)))


class PageTest(unittest.TestCase):
    def test_selector_and_table(self):
        page = synthetic_page(vid="2").decode()
        self.assertEqual({"1": "مدينة 1", "2": "مدينة 2", "301": "مدينة 301"}, ma.localities(page))
        self.assertEqual("2", ma.marked_id(page))
        self.assertEqual(3, len(ma.table_rows(page)))
        self.assertIsNone(ma.page_problem(page, "2"))
        self.assertEqual("the page of locality 2", ma.page_problem(page, "1"))
        self.assertTrue(ma.page_problem("<html><body>Erreur 503</body></html>", "1").startswith("no prayer table: Erreur"))
        # The 2024 selector: unquoted values on horaire_hijri_2.php, none marked.
        old = ('<select name="ville"><option value=horaire_hijri_2.php?ville=7 >x</option>'
               "<option value=horaire_hijri_2.php?ville=8>y</option></select>")
        self.assertEqual(({"7": "x", "8": "y"}, None), (ma.localities(old), ma.marked_id(old)))

    def test_names_and_contract(self):
        self.assertEqual("https://www.habous.gov.ma/prieres/horaire_hijri_2.php?ville=1", ma.page_url("1"))
        self.assertEqual("1448-04/ville_1.html", ma.file_name("1448-04", "1"))
        self.assertEqual(191, len(set(ma.KNOWN_IDS)))
        self.assertEqual(("1", "169", "301", "322"), (ma.KNOWN_IDS[0], ma.KNOWN_IDS[168], ma.KNOWN_IDS[169], ma.KNOWN_IDS[-1]))
        self.assertEqual(("ma", "MA", "monthly"), (ma.NAME, ma.CC, ma.CADENCE))
        self.assertTrue(ma.DESCRIPTION.isascii())

    def test_set_folder(self):
        rows = ma.table_rows(synthetic_page().decode())
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.assertEqual("1448-04", ma.set_folder(root, "1448-04", "1", rows))
            (root / "1448-04").mkdir()
            (root / "1448-04" / "ville_1.html").write_bytes(synthetic_page() + b"<!-- other bytes, same table -->")
            self.assertEqual("1448-04", ma.set_folder(root, "1448-04", "1", rows))
            (root / "1448-04" / "ville_1.html").write_bytes(synthetic_page(fajr="05:01"))
            self.assertEqual("1448-04/r2", ma.set_folder(root, "1448-04", "1", rows))

    @unittest.skipUnless(HAVE_RESEARCH, SKIP)
    def test_research_pages(self):
        page = RABAT_FILE.read_text(encoding="utf-8", errors="replace")
        names = ma.localities(page)
        self.assertEqual((191, set(ma.KNOWN_IDS)), (len(names), set(names)))
        self.assertEqual(("1", 30), (ma.marked_id(page), len(ma.table_rows(page))))
        self.assertIsNone(ma.page_problem(page, "1"))
        old = (ARCHIVE / "laayoune_202406.txt").read_text(encoding="utf-8", errors="replace")
        self.assertEqual((set(ma.KNOWN_IDS), "156"), (set(ma.localities(old)), ma.marked_id(old)))


class CollectTest(unittest.TestCase):
    def test_a_month(self):
        with tempfile.TemporaryDirectory() as tmp:
            session = FakeSession(tmp, lambda vid: synthetic_page(vid))
            count, out = run(session)
            self.assertEqual(3, count)
            self.assertEqual(list(IDS), session.asked)                      # Rabat first, then the selector's order
            self.assertEqual([f"1448-04/ville_{v}.html" for v in ("1", "2", "301")], saved_names(tmp))
            for vid in IDS:
                path = Path(tmp) / "ma" / "1448-04" / f"ville_{vid}.html"
                self.assertEqual(synthetic_page(vid), path.read_bytes())
                self.assertEqual((vid, 9), fit_reads(path))
            row = (Path(tmp) / "ma" / "MANIFEST.tsv").read_text(encoding="utf-8").splitlines()[3].split("\t")
            self.assertEqual(["1448-04/ville_301.html", ma.page_url("301"), "Habous, locality 301 (مدينة 301): month "
                              "1448-04, 3 days"], [row[0]] + row[4:])
            self.assertIn("ma: 1448-04: 3 localities, 0 already saved, 3 saved, 0 failed", out)
            self.assertIn("ma: the locality list changed (added none; removed 3, 4, 5,", out)
            # Later in the month: Rabat's page only.
            session.asked.clear()
            count, out = run(session)
            self.assertEqual((0, ["1"]), (count, session.asked))
            self.assertIn("ma: 1448-04: 3 localities, 3 already saved, 0 saved, 0 failed", out)

    @unittest.skipUnless(HAVE_RESEARCH, SKIP)
    def test_research_pages_are_saved_byte_for_byte(self):
        samples = {re.findall(r"\d+", p.stem)[-1]: p.read_bytes()
                   for p in [*RESEARCH.glob("skeptic_ma/v*.html"), *RESEARCH.glob("ma/x_*.html")]}
        rabat = samples["1"].decode("utf-8", errors="replace")

        def page(vid):
            if vid in samples:
                return samples[vid]
            marked = rabat.replace("value='index.php?ville=1'  selected=selected", "value='index.php?ville=1'")
            return marked.replace(f"value='index.php?ville={vid}'  ", f"value='index.php?ville={vid}'  selected=selected",
                                  1).encode("utf-8")
        with tempfile.TemporaryDirectory() as tmp:
            session = FakeSession(tmp, page)
            self.assertEqual(191, run(session)[0])
            self.assertEqual(list(ma.localities(rabat)), session.asked)     # Rabat first, then the selector's order
            names = saved_names(tmp)
            self.assertEqual(sorted(f"1448-04/ville_{v}.html" for v in ma.KNOWN_IDS), names)
            for vid, content in samples.items():
                path = Path(tmp) / "ma" / "1448-04" / f"ville_{vid}.html"
                self.assertEqual(content, path.read_bytes())
                self.assertEqual(vid, fit_reads(path)[0])

    def test_a_failing_locality_fails_the_run_and_the_next_run_resumes(self):
        down = {"2"}

        def pages(vid):
            return urllib.error.URLError("reset") if vid in down else synthetic_page(vid)
        with tempfile.TemporaryDirectory() as tmp:
            session = FakeSession(tmp, pages)
            with self.assertRaisesRegex(RuntimeError, "1 of 3 localities failed for 1448-04: 2: URLError"):
                run(session)
            self.assertEqual(["1", "2", "301", "2"], session.asked)        # asked once more at the end
            self.assertEqual(["1448-04/ville_1.html", "1448-04/ville_301.html"], saved_names(tmp))
            down.clear()
            session.asked.clear()
            self.assertEqual(1, run(session)[0])
            self.assertEqual(["1", "2"], session.asked)

    def test_rabats_page_failing(self):
        pages = {"2": synthetic_page("2"), "301": synthetic_page("301")}
        with tempfile.TemporaryDirectory() as tmp:
            session = FakeSession(tmp, lambda vid: pages.get(vid, urllib.error.URLError("reset")))
            with self.assertRaisesRegex(RuntimeError, "1 of 3 localities failed"):
                run(session)
            self.assertEqual(["1", "2", "1", "301", "1"], session.asked)      # the next id names the month
            self.assertEqual(["1448-04/ville_2.html", "1448-04/ville_301.html"], saved_names(tmp))
        with tempfile.TemporaryDirectory() as tmp:
            session = FakeSession(tmp, lambda vid: urllib.error.URLError("unreachable"))
            with self.assertRaisesRegex(RuntimeError, "no locality page could be read"):
                run(session)
            self.assertEqual(list(ma.KNOWN_IDS[:ma.GIVE_UP]), session.asked)

    def test_the_site_stops_answering(self):
        ids = tuple(str(i) for i in range(1, 13))
        with tempfile.TemporaryDirectory() as tmp:
            session = FakeSession(tmp, lambda vid: synthetic_page(vid, ids) if vid == "1" else urllib.error.URLError("x"))
            with self.assertRaisesRegex(RuntimeError, "not asked: the site stopped answering"):
                run(session)
            self.assertEqual(1 + 2 * ma.GIVE_UP, len(session.asked))

    def test_a_page_of_another_locality_is_not_saved(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(RuntimeError, "301: ValueError: the page of locality 1"):
                run(FakeSession(tmp, lambda vid: synthetic_page("1" if vid == "301" else vid)))
            self.assertEqual(["1448-04/ville_1.html", "1448-04/ville_2.html"], saved_names(tmp))

    def test_a_revised_table_starts_a_new_set(self):
        with tempfile.TemporaryDirectory() as tmp:
            run(FakeSession(tmp, lambda vid: synthetic_page(vid)))
            session = FakeSession(tmp, lambda vid: synthetic_page(vid, fajr="05:01"))
            self.assertEqual(3, run(session)[0])
            self.assertEqual(list(IDS), session.asked)
            self.assertEqual([f"1448-04/r2/ville_{v}.html" for v in ("1", "2", "301")],
                             [n for n in saved_names(tmp) if "/r2/" in n])
            for vid in IDS:
                self.assertEqual((vid, 9), fit_reads(Path(tmp) / "ma" / "1448-04" / "r2" / f"ville_{vid}.html"))
            self.assertEqual(synthetic_page("2"), (Path(tmp) / "ma" / "1448-04" / "ville_2.html").read_bytes())

    def test_the_next_month(self):
        with tempfile.TemporaryDirectory() as tmp:
            run(FakeSession(tmp, lambda vid: synthetic_page(vid)))
            self.assertEqual(3, run(FakeSession(tmp, lambda vid: synthetic_page(vid, month=JUMADA_I)))[0])
            self.assertEqual(3, sum(n.startswith("1448-05/ville_") for n in saved_names(tmp)))

    def test_the_month_changing_during_the_run_stops_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            session = FakeSession(tmp, lambda vid: synthetic_page(vid, month=RABI_II if vid == "1" else JUMADA_I))
            with self.assertRaisesRegex(RuntimeError, r"the month changed during the run \(1448-04 to 1448-05\)"):
                run(session)
            self.assertEqual(["1", "2"], session.asked)
            self.assertEqual(["1448-04/ville_1.html"], saved_names(tmp))

    def test_a_page_without_the_hijri_year(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(3, run(FakeSession(tmp, lambda vid: synthetic_page(vid, month="ربيع الآخر")))[0])
            self.assertEqual([f"2026-10/ville_{v}.html" for v in ("1", "2", "301")], saved_names(tmp))


if __name__ == "__main__":
    unittest.main()
