"""The Libya collector, offline (python -I scripts/collectors/test_ly.py). The research's saved answers serve where
test-data-local holds them (those tests skip without it); synthetic answers with made-up times serve everywhere else."""

import contextlib
import datetime as dt
import io
import itertools
import json
import sys
import tempfile
import unittest
import urllib.error
import urllib.parse
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402
import ly  # noqa: E402

RESEARCH = common.LOCAL_DATA / "research-2026-10-08" / "LY" / "ly"
HOME_FILE, CITIES_FILE, IFTA_FILE = (RESEARCH / n for n in ("awqaf_home.html", "cities_20261008.txt", "ifta_pt.json"))
HAVE_RESEARCH = all(p.exists() for p in (HOME_FILE, CITIES_FILE, IFTA_FILE))
SKIP = "test-data-local/research-2026-10-08/LY is absent"
TODAY = dt.date(2026, 10, 16)
LOCATION = b'{"value":{"latitude":1.5,"longitude":2.5},"isSuccess":true}'   # made up: the research kept no copy


def awqaf_answer(code: str, day: dt.date = TODAY) -> bytes:
    """An answer shaped like the endpoint's, with made-up times."""
    row = {"city_en": code, "month": str(day.month), "day": str(day.day), "emsak": "04:50", "fajr": "05:00",
           "shorouk": "06:30", "dohr": "12:00", "aser": "15:30", "magreb": "18:00", "esha": "19:30"}
    return json.dumps({"success": True, "data": [row]}, separators=(",", ":")).encode()


def ifta_answer(first: dt.date = TODAY, days: int = 8) -> bytes:
    """An answer shaped like api.ifta.ly's, with made-up times."""
    rows = [{"day": d.day, "month": d.month, "imsak": "04:50:00", "fajr": "05:00:00", "sunrise": "06:30:00",
             "dhuhr": "12:00:00", "asr": "15:30:00", "maghrib": "18:00:00", "isha": "19:30:00"}
            for d in (first + dt.timedelta(days=k) for k in range(days))]
    return json.dumps({"value": rows, "isSuccess": True, "isFailure": False}, separators=(",", ":")).encode()


def home_page(codes=ly.CITIES, ajax=ly.AJAX) -> bytes:
    """The homepage's two parts the collector reads: the widget's ajax_object and its city selector."""
    options = "".join(f"<option value='{c}'>{c}</option>" for c in codes)
    return (f"<html><script>var ajax_object = {json.dumps({'ajax_url': ajax})};</script>"
            f'<select id="prayer-times-city" class="uk-select">{options}</select></html>').encode()


def site(home=None, answers=awqaf_answer, ifta=None, location=LOCATION, down=()):
    """A fake of the three sources. [answers](code) gives a city's Awqaf answer; the cities in [down] always fail."""
    home = home_page() if home is None else home
    ifta = ifta_answer() if ifta is None else ifta

    def answer(url, data):
        if url == ly.HOME:
            return home
        if url == ly.AJAX:
            return urllib.error.URLError("refused") if data["city"] in down else answers(data["city"])
        if url == ly.IFTA:
            return ifta
        if url == ly.IFTA_LOCATION:
            return location
        return urllib.error.HTTPError(url, 404, "Not Found", {}, None)
    return answer


class FakeSession(common.Session):
    """The real save() under a temporary root; fetch() answers from [answer] (bytes, or an exception it raises) and
    records each call."""

    def __init__(self, root, answer):
        clock = (dt.datetime(2026, 10, 16, 8, 0, tzinfo=dt.timezone.utc) + dt.timedelta(seconds=s)
                 for s in itertools.count())
        super().__init__(root=Path(root), pause=0, now=lambda: next(clock))
        self.answer, self.calls = answer, []

    def fetch(self, url, *, data=None, headers=None, timeout=60.0):
        self.calls.append((url, data, headers))
        result = self.answer(url, data)
        if isinstance(result, Exception):
            raise result
        return result


def run(session, today=TODAY):
    """collect() with its printed lines captured: (count, output)."""
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        count = ly.collect(session, today)
    return count, out.getvalue()


def saved_names(root) -> list[str]:
    folder = Path(root) / "ly"
    return sorted(p.relative_to(folder).as_posix() for p in folder.rglob("*.json"))


def manifest_rows(root) -> dict[str, list[str]]:
    lines = (Path(root) / "ly" / "MANIFEST.tsv").read_text(encoding="utf-8").splitlines()[1:]
    return {line.split("\t")[0]: line.split("\t") for line in lines}


def month_with(root, name: str, content: bytes) -> None:
    folder = Path(root) / "ly" / "2026" / "10"
    folder.mkdir(parents=True, exist_ok=True)
    (folder / name).write_bytes(content)


class RequestTest(unittest.TestCase):
    def test_post_fields(self):
        self.assertEqual({"action": "get_prayer_times", "city": "Tripoli"}, ly.awqaf_form("Tripoli"))
        # What common.Session sends for it, as the widget's jQuery does.
        self.assertEqual("action=get_prayer_times&city=Tripoli", urllib.parse.urlencode(ly.awqaf_form("Tripoli")))

    def test_names(self):
        self.assertEqual("2026/10/ly_awqaf_2026-10-16_Tripoli.json", ly.awqaf_name(TODAY, "Tripoli"))
        self.assertEqual("2026/10/ly_ifta_2026-10-16.json", ly.ifta_name(TODAY))
        self.assertEqual("2026/10/ly_ifta_location_2026-10-16.json", ly.location_name(TODAY))

    def test_contract(self):
        self.assertEqual(("ly", "LY", "daily"), (ly.NAME, ly.CC, ly.CADENCE))
        self.assertTrue(ly.DESCRIPTION.isascii())
        self.assertEqual(22, len(set(ly.CITIES)))
        for url in (ly.HOME, ly.AJAX, ly.IFTA, ly.IFTA_LOCATION):
            self.assertTrue(url.startswith("https://"))


class HomeTest(unittest.TestCase):
    def test_selector_and_ajax_url(self):
        page = home_page(("Tripoli", "beda", "Tripoli")).decode()
        self.assertEqual(["Tripoli", "beda"], ly.cities_from_home(page))
        self.assertEqual(ly.AJAX, ly.ajax_url_from_home(page))
        escaped = 'var ajax_object = {"ajax_url":"https:\\/\\/awqaf.gov.ly\\/wp-admin\\/admin-ajax.php"};'
        self.assertEqual("https://awqaf.gov.ly/wp-admin/admin-ajax.php", ly.ajax_url_from_home(escaped))

    def test_what_is_not_followed(self):
        for url in ("https://example.invalid/wp-admin/admin-ajax.php", "http://awqaf.gov.ly/x/admin-ajax.php",
                    "https://awqaf.gov.ly/x/other.php"):
            self.assertIsNone(ly.ajax_url_from_home(home_page(ajax=url).decode()), url)
        self.assertIsNone(ly.ajax_url_from_home("<html>no widget</html>"))
        self.assertEqual([], ly.cities_from_home("<select id='other'><option value='Tripoli'>x</option></select>"))
        self.assertEqual([], ly.cities_from_home(home_page(("../x", "a b")).decode()))

    @unittest.skipUnless(HAVE_RESEARCH, SKIP)
    def test_research_homepage(self):
        page = HOME_FILE.read_text(encoding="utf-8", errors="replace")
        self.assertEqual(list(ly.CITIES), ly.cities_from_home(page))
        self.assertEqual(ly.AJAX, ly.ajax_url_from_home(page))


class AnswerTest(unittest.TestCase):
    def test_awqaf_row(self):
        row = ly.awqaf_row(awqaf_answer("Jalo"), "Jalo")
        self.assertEqual(("Jalo", TODAY), (row["city_en"], ly.row_day(row, TODAY)))
        for bad in (b"0", b"<html>blocked</html>", b'{"success":false,"data":{"message":"no such city"}}',
                    b'{"success":true,"data":[]}'):
            with self.assertRaises(ValueError):
                ly.awqaf_row(bad, "Jalo")
        with self.assertRaisesRegex(ValueError, "the row of Jalo, not Hoon"):
            ly.awqaf_row(awqaf_answer("Jalo"), "Hoon")
        answer = json.loads(awqaf_answer("Jalo"))
        del answer["data"][0]["esha"]
        with self.assertRaisesRegex(ValueError, "no esha"):
            ly.awqaf_row(json.dumps(answer).encode(), "Jalo")

    def test_row_day(self):
        self.assertEqual(dt.date(2026, 12, 31), ly.row_day({"month": "12", "day": "31"}, dt.date(2027, 1, 1)))
        self.assertEqual(dt.date(2027, 1, 1), ly.row_day({"month": 1, "day": 1}, dt.date(2026, 12, 31)))
        self.assertEqual(TODAY, ly.row_day({"fajr": "05:00"}, TODAY))
        with self.assertRaises(ValueError):
            ly.row_day({"month": "8", "day": "1"}, TODAY)                       # not today's row
        with self.assertRaises(ValueError):
            ly.row_day({"month": "2", "day": "29"}, dt.date(2027, 3, 1))        # no such day near

    def test_ifta_rows(self):
        self.assertEqual(8, len(ly.ifta_rows(ifta_answer())))
        for bad in (b"{}", b"<html></html>", b'{"value":[],"isSuccess":true}', b'{"value":[{"day":1}],"isSuccess":true}',
                    b'{"isSuccess":false,"isFailure":true,"error":{"code":"x"}}'):
            with self.assertRaises(ValueError):
                ly.ifta_rows(bad)

    def test_lost_days(self):
        d = dt.date
        self.assertEqual([], ly.lost_days([], d(2026, 10, 16)))
        self.assertEqual([], ly.lost_days([d(2026, 10, 8), d(2026, 10, 16)], d(2026, 10, 16)))   # 10-08 holds 10-15
        self.assertEqual([d(2026, 10, 13), d(2026, 10, 14), d(2026, 10, 15)],
                         ly.lost_days([d(2026, 10, 1), d(2026, 10, 5)], d(2026, 10, 16)))

    @unittest.skipUnless(HAVE_RESEARCH, SKIP)
    def test_research_answers(self):
        day = dt.date(2026, 10, 8)
        lines = CITIES_FILE.read_bytes().splitlines()
        self.assertEqual(22, len(lines))
        for line, code in zip(lines, ly.CITIES):
            self.assertEqual(day, ly.row_day(ly.awqaf_row(line, code), day))
        rows = ly.ifta_rows(IFTA_FILE.read_bytes())
        self.assertEqual((8, day), (len(rows), ly.row_day(rows[0], day)))


class CollectTest(unittest.TestCase):
    def test_a_day(self):
        with tempfile.TemporaryDirectory() as tmp:
            session = FakeSession(tmp, site())
            count, out = run(session)
            self.assertEqual(24, count)
            # The month's first run reads the homepage, posts each city as the widget does, then asks Dar al-Ifta.
            self.assertEqual((ly.HOME, None, None), session.calls[0])
            self.assertEqual([(ly.AJAX, ly.awqaf_form(c), None) for c in ly.CITIES], session.calls[1:23])
            self.assertEqual([(ly.IFTA, None, ly.JSON_HEADERS), (ly.IFTA_LOCATION, None, ly.JSON_HEADERS)],
                             session.calls[23:])
            expected = [ly.awqaf_name(TODAY, c) for c in ly.CITIES] + [ly.ifta_name(TODAY), ly.location_name(TODAY)]
            self.assertEqual(sorted(expected), saved_names(tmp))
            self.assertEqual(awqaf_answer("beda"), (Path(tmp) / "ly" / ly.awqaf_name(TODAY, "beda")).read_bytes())
            rows = manifest_rows(tmp)
            self.assertEqual(f"POST {ly.AJAX} action=get_prayer_times city=beda", rows[ly.awqaf_name(TODAY, "beda")][4])
            self.assertEqual([f"GET {ly.IFTA}", "Dar al-Ifta, Tripoli: 8 days, 2026-10-16 to 2026-10-23"],
                             rows[ly.ifta_name(TODAY)][4:])
            self.assertIn("ly: Awqaf 22 of 22 cities read, api.ifta.ly 2026-10-16 to 2026-10-23; 24 file(s) saved", out)
            # Again the same day: no homepage (the month has its files), the same bytes, nothing new.
            session.calls.clear()
            self.assertEqual(0, run(session)[0])
            self.assertEqual(24, len(session.calls))
            self.assertNotIn(ly.HOME, [url for url, _, _ in session.calls])

    @unittest.skipUnless(HAVE_RESEARCH, SKIP)
    def test_research_answers_are_saved_byte_for_byte(self):
        day = dt.date(2026, 10, 8)
        lines = {json.loads(line)["data"][0]["city_en"]: line for line in CITIES_FILE.read_bytes().splitlines()}
        answer = site(home=HOME_FILE.read_bytes(), answers=lambda code: lines[code], ifta=IFTA_FILE.read_bytes())
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(24, run(FakeSession(tmp, answer), day)[0])
            for code, line in lines.items():
                self.assertEqual(line, (Path(tmp) / "ly" / ly.awqaf_name(day, code)).read_bytes())
            self.assertEqual(IFTA_FILE.read_bytes(), (Path(tmp) / "ly" / ly.ifta_name(day)).read_bytes())

    def test_a_failing_city_is_asked_again_at_the_end(self):
        asked = []

        def flaky(code):
            asked.append(code)
            return urllib.error.URLError("reset") if code == "Jalo" and asked.count("Jalo") == 1 else awqaf_answer(code)
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(24, run(FakeSession(tmp, site(answers=flaky)))[0])
            self.assertEqual((2, "Jalo"), (asked.count("Jalo"), asked[-1]))

    def test_a_city_still_failing_fails_the_run_after_the_rest_is_saved(self):
        with tempfile.TemporaryDirectory() as tmp:
            session = FakeSession(tmp, site(down={"Hoon"}))
            with self.assertRaisesRegex(RuntimeError, "Awqaf Hoon: URLError"):
                run(session)
            self.assertEqual(23, len(saved_names(tmp)))                # 21 cities, Dar al-Ifta, the location
            self.assertEqual(2, sum(data == ly.awqaf_form("Hoon") for _, data, _ in session.calls))

    def test_another_citys_row_is_not_saved(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(RuntimeError, "the row of Tripoli, not Ejdabya"):
                run(FakeSession(tmp, site(answers=lambda code: awqaf_answer("Tripoli"))))
            self.assertEqual([ly.awqaf_name(TODAY, "Tripoli")], [n for n in saved_names(tmp) if "awqaf" in n])

    def test_the_endpoint_moved(self):
        moved = "https://awqaf.gov.ly/newpath/admin-ajax.php"
        base = site(home=home_page(ajax=moved))

        def answer(url, data):
            if url == ly.AJAX:
                return urllib.error.HTTPError(url, 404, "Not Found", {}, None)
            return awqaf_answer(data["city"]) if url == moved else base(url, data)
        with tempfile.TemporaryDirectory() as tmp:
            month_with(tmp, "ly_awqaf_2026-10-01_Tripoli.json", b"{}")    # not the month's first run
            session = FakeSession(tmp, answer)
            with self.assertRaisesRegex(RuntimeError, "update ly.AJAX"):
                run(session)
            urls = [url for url, _, _ in session.calls]
            # GIVE_UP failures with nothing read: the rest is not asked there, and the homepage gives the new address.
            self.assertEqual([ly.AJAX] * ly.GIVE_UP + [ly.HOME], urls[:ly.GIVE_UP + 1])
            self.assertEqual(22, urls.count(moved))
            self.assertEqual(22, sum("awqaf_2026-10-16" in n for n in saved_names(tmp)))
            self.assertEqual(f"POST {moved} action=get_prayer_times city=Hoon",
                             manifest_rows(tmp)[ly.awqaf_name(TODAY, "Hoon")][4])

    def test_a_changed_selector_is_followed_and_fails_the_run(self):
        codes = list(ly.CITIES[:-1]) + ["Nalut"]
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(RuntimeError, "added Nalut; removed Hoon"):
                run(FakeSession(tmp, site(home=home_page(codes))))
            names = saved_names(tmp)
            self.assertIn(ly.awqaf_name(TODAY, "Nalut"), names)
            self.assertNotIn(ly.awqaf_name(TODAY, "Hoon"), names)

    def test_a_site_that_answers_nothing(self):
        with tempfile.TemporaryDirectory() as tmp:
            session = FakeSession(tmp, lambda url, data: urllib.error.URLError("unreachable"))
            with self.assertRaisesRegex(RuntimeError, "not asked: the endpoint answered nothing"):
                run(session)
            # Homepage, GIVE_UP posts, GIVE_UP more at the end, Dar al-Ifta twice, the location once.
            self.assertEqual(1 + 2 * ly.GIVE_UP + 2 + 1, len(session.calls))
            self.assertEqual([], saved_names(tmp))

    def test_dar_al_ifta_failing_fails_the_run_after_the_rest_is_saved(self):
        with tempfile.TemporaryDirectory() as tmp:
            session = FakeSession(tmp, site(ifta=b"<html>maintenance</html>"))
            with self.assertRaisesRegex(RuntimeError, "api.ifta.ly: ValueError: not JSON"):
                run(session)
            self.assertEqual(2, sum(url == ly.IFTA for url, _, _ in session.calls))
            self.assertEqual(23, len(saved_names(tmp)))

    def test_tripoli_days_that_no_answer_holds_are_printed(self):
        with tempfile.TemporaryDirectory() as tmp:
            month_with(tmp, "ly_ifta_2026-10-05.json", ifta_answer(dt.date(2026, 10, 5)))
            out = run(FakeSession(tmp, site()))[1]
            self.assertIn("Tripoli's days 2026-10-13 to 2026-10-15 are in no saved api.ifta.ly answer", out)
        with tempfile.TemporaryDirectory() as tmp:
            month_with(tmp, "ly_ifta_2026-10-09.json", ifta_answer(dt.date(2026, 10, 9)))
            self.assertNotIn("Tripoli's days", run(FakeSession(tmp, site()))[1])

    def test_the_location_is_saved_when_it_changes(self):
        moved = b'{"value":{"latitude":1.6,"longitude":2.5},"isSuccess":true}'
        with tempfile.TemporaryDirectory() as tmp:
            for day, location in ((TODAY, LOCATION), (TODAY + dt.timedelta(days=1), LOCATION),
                                  (TODAY + dt.timedelta(days=2), moved), (TODAY + dt.timedelta(days=3), LOCATION)):
                run(FakeSession(tmp, site(answers=lambda code, d=day: awqaf_answer(code, d), ifta=ifta_answer(day),
                                          location=location)), day)
            self.assertEqual([ly.location_name(TODAY + dt.timedelta(days=k)) for k in (0, 2, 3)],
                             [n for n in saved_names(tmp) if "location" in n])
            # An answer that is not JSON is not saved, and only printed.
            out = run(FakeSession(tmp, site(location=b"<html>error</html>")), TODAY + dt.timedelta(days=4))[1]
            self.assertIn("api.ifta.ly location not read", out)
            self.assertEqual(3, sum("location" in n for n in saved_names(tmp)))


if __name__ == "__main__":
    unittest.main()
