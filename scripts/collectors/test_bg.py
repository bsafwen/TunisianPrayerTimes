"""The Bulgaria collector, offline (python -I scripts/collectors/test_bg.py)."""

import datetime as dt
import itertools
import sys
import tempfile
import unittest
import urllib.error
import urllib.parse
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import bg  # noqa: E402
import common  # noqa: E402

TODAY = dt.date(2026, 10, 16)
# The «Град» list of 2026-10-16, in the page's order.
CITIES = ["Айтос", "Балчик", "Благоевград", "Бургас", "Бяла", "Варна", "Велики Преслав", "Велико Търново", "Велинград",
          "Горна Оряховица", "Гоце Делчев", "Добрич", "Исперих", "Каварна", "Каолиново", "Карлово", "Карнобат", "Кнежа",
          "Котел", "Крумовград", "Кубрат", "Кърджали", "Ловеч", "Мадан", "Монтана", "Никопол", "Нова Загора",
          "Нови пазар", "Пазарджик", "Плевен", "Пловдив", "Провадия", "Разград", "Русе", "Свищов", "Силистра", "Ситово",
          "Сливен", "Смолян", "София", "Стара Загора", "Твърдица", "Търговище", "Харманли", "Хасково", "Шумен",
          "Якоруда", "Ямбол"]
MONTH_NAMES = ["Януари", "Февруари", "Март", "Април", "Май", "Юни", "Юли", "Август", "Септември", "Октомври", "Ноември",
               "Декември"]
DEFAULT = "София"


def day_times(city, day):
    """Six times of a city's own: shifted by its place in the list, an hour back from the 25th (the change of clock)."""
    shift = CITIES.index(city) + day - (60 if day >= 25 else 0)
    return [f"{(m + shift) // 60}:{(m + shift) % 60:02d}" for m in (348, 436, 802, 998, 1158, 1239)]


def page(city=DEFAULT, *, month=10, days=31, method="post", charset="utf-8", values=True, form=True, extra=""):
    """Shaped like grandmufti.bg's «Времена за намаз»: the header's widget for today, the two lists, the month's table."""
    def option(value, label, chosen):
        attributes = (f' value="{value}"' if values else "") + (" selected" if chosen else "")
        return f"<option{attributes}>{label}</option>"

    cities = "".join(option(i, c, c == city) for i, c in enumerate(CITIES, 1))
    months = "".join(option(i, m, i == month) for i, m in enumerate(MONTH_NAMES, 1))
    lists = (f'<label>Град</label> <select name="grad" onchange="this.form.submit()">{cities}</select> '
             f'<label>Месец</label> <select name="mesec" onchange="this.form.submit()">{months}</select>')
    if form:
        lists = (f'<form method="{method}" action="/bg/home/vremena-za-namaz.html#namaz">'
                 f'<input type="hidden" name="option" value="com_namaz">{extra}{lists}</form>')
    rows = "".join(f"<tr><td>{d}</td>" + "".join(f"<td>{t}</td>" for t in day_times(city, d)) + "</tr>\n"
                   for d in range(1, days + 1))
    html = f"""<!DOCTYPE html>
<html><head><meta http-equiv="Content-Type" content="text/html; charset={charset}"><title>Времена за намаз</title>
<script>var today = "1 5:00 6:00 7:00 8:00 9:00 10:00";</script></head><body>
<div class="today">Зора <b>6:05</b> Изгрев <b>7:33</b> Обяд <b>13:17</b> Следобяд <b>16:19</b> Залез <b>18:52</b>
Нощ <b>20:13</b> <span>Петък 16 Октомври 2026</span></div>
<div class="namaz">{lists}<h3>Времената за молитва</h3><table>
<tr><th>Ден</th><th>Зора</th><th>Изгрев</th><th>Обяд</th><th>Следобяд</th><th>Залез</th><th>Нощ</th></tr>
{rows}</table></div>
<footer><a href="https://sky.icnhost.net:2096/">Поща</a></footer></body></html>"""
    return html.encode(charset)


def server(ignore=False, broken=()):
    """The site as the tests suppose it: the posted «Град» choice gives that city's page."""
    def answer(url, data):
        if data is None:
            return page()
        fields = urllib.parse.parse_qs(data.decode("ascii"))
        city = CITIES[int(fields["grad"][0]) - 1]
        if ignore:
            return page()
        return page(city, month=int(fields["mesec"][0]), days=0 if city in broken else 31)
    return answer


class FakeSession(common.Session):
    """The real save() under a temporary root; fetch() answers from [answer] and records the calls."""

    def __init__(self, root, answer):
        clock = (dt.datetime(2026, 10, 16, 8, 0, tzinfo=dt.timezone.utc) + dt.timedelta(minutes=m)
                 for m in itertools.count())
        super().__init__(root=Path(root), pause=0, now=lambda: next(clock))
        self.answer = answer
        self.calls = []

    def fetch(self, url, *, data=None, headers=None, timeout=60.0):
        self.calls.append((url, data, headers))
        return self.answer(url, data)


def saved_files(root):
    folder = Path(root) / "bg" / "2026" / "10"
    return sorted(p.name for p in folder.iterdir()) if folder.exists() else []


class NamesTest(unittest.TestCase):
    def test_official_transliteration(self):
        expected = {"Айтос": "aytos", "Ямбол": "yambol", "София": "sofia", "Провадия": "provadia",
                    "Велико Търново": "veliko-tarnovo", "Кърджали": "kardzhali", "Горна Оряховица": "gorna-oryahovitsa",
                    "Гоце Делчев": "gotse-delchev", "Свищов": "svishtov", "Нови пазар": "novi-pazar", "Бяла": "byala",
                    "Хасково": "haskovo", "Шумен": "shumen", "Пазарджик": "pazardzhik", "Търговище": "targovishte",
                    "Якоруда": "yakoruda"}
        self.assertEqual(expected, {city: bg.slug(city) for city in expected})

    def test_file_names(self):
        names = bg.file_names([(str(i), c) for i, c in enumerate(CITIES, 1)], TODAY)
        self.assertEqual(("2026/10/aytos_2026-10.html", "2026/10/yambol_2026-10.html"), (names[0], names[-1]))
        self.assertEqual(48, len(set(names)))
        self.assertTrue(all(name.isascii() for name in names))
        self.assertEqual(["2026/10/varna_2026-10.html", "2026/10/varna-7_2026-10.html"],
                         bg.file_names([("6", "Варна"), ("7", "Варна")], TODAY))
        self.assertEqual("2026/10/default_2026-10.html", bg.default_name(TODAY))


class SelectorTest(unittest.TestCase):
    def test_reads_the_form(self):
        selector = bg.find_selector(page(extra='<input type="submit" name="go" value="OK">').decode("utf-8"))
        self.assertEqual("https://www.grandmufti.bg/bg/home/vremena-za-namaz.html#namaz", selector.action)
        self.assertEqual(("post", "grad", "mesec", "10"),
                         (selector.method, selector.city_field, selector.month_field, selector.chosen_month))
        self.assertEqual((48, ("1", "Айтос"), ("48", "Ямбол")), (len(selector.cities), selector.cities[0], selector.cities[-1]))
        # What the form sends as it stands, a submit button left out.
        self.assertEqual([("option", "com_namaz"), ("grad", "40"), ("mesec", "10")], selector.controls)
        self.assertEqual("11", bg.month_value(selector.months, 11))

    def test_post_request(self):
        selector = bg.find_selector(page().decode("utf-8"))
        self.assertEqual(("https://www.grandmufti.bg/bg/home/vremena-za-namaz.html", b"option=com_namaz&grad=1&mesec=10"),
                         bg.city_request(selector, "1", "10"))

    def test_get_request_in_the_page_charset(self):
        # A GET form in windows-1251 whose options have no value: a browser sends the names, in that charset.
        content = page(method="get", charset="windows-1251", values=False)
        charset = bg.page_charset(content)
        self.assertEqual("cp1251", charset)
        selector = bg.find_selector(content.decode(charset), charset=charset)
        url, body = bg.city_request(selector, "Айтос", bg.month_value(selector.months, 10))
        self.assertIsNone(body)
        self.assertEqual(bg.PAGE_URL + "?option=com_namaz&grad=%C0%E9%F2%EE%F1&mesec=%CE%EA%F2%EE%EC%E2%F0%E8", url)

    def test_charset_without_meta(self):
        undeclared = page().replace(b'<meta http-equiv="Content-Type" content="text/html; charset=utf-8">', b"")
        self.assertEqual("utf-8", bg.page_charset(undeclared))
        self.assertEqual("cp1251", bg.page_charset(undeclared.decode("utf-8").encode("cp1251")))
        self.assertEqual("utf-8", bg.page_charset(page().replace(b"charset=utf-8", b"charset=x-unknown")))

    def test_lists_outside_a_form_go_by_get_to_the_page(self):
        selector = bg.find_selector(page(form=False).decode("utf-8"))
        self.assertEqual(("get", [("grad", "40"), ("mesec", "10")]), (selector.method, selector.controls))
        self.assertEqual((bg.PAGE_URL + "?grad=6&mesec=10", None), bg.city_request(selector, "6", "10"))

    def test_no_city_list(self):
        self.assertIsNone(bg.find_selector("<select name='grad'><option>София</option></select>"))
        # A list without a name cannot be sent.
        self.assertIsNone(bg.find_selector(page().decode("utf-8").replace('name="grad" ', "")))

    def test_month_value(self):
        self.assertEqual("Октомври", bg.month_value([(m, m) for m in MONTH_NAMES], 10))
        self.assertEqual("9", bg.month_value([(str(i), f"m{i}") for i in range(12)], 10))
        self.assertIsNone(bg.month_value([("1", "x")], 10))


class TableTest(unittest.TestCase):
    def test_rows(self):
        rows = bg.table_rows(page("Варна").decode("utf-8"), 31)
        self.assertEqual(31, len(rows))
        self.assertEqual(("1", *day_times("Варна", 1)), rows[0])
        self.assertEqual(("25", *day_times("Варна", 25)), rows[24])
        # Another month's length is not this month's table.
        self.assertEqual([], bg.table_rows(page("Варна", days=30).decode("utf-8"), 31))
        self.assertEqual([], bg.table_rows(page("Варна").decode("utf-8"), 30))

    def test_stray_rows_around_the_table(self):
        # A day and six times elsewhere on the page (a widget for today) do not hide the month's table.
        stray = "<p><span>16</span> " + " ".join(f"<b>{t}</b>" for t in day_times(DEFAULT, 16)) + "</p>"
        text = page("Варна").decode("utf-8").replace('<div class="namaz">', stray + '<div class="namaz">')
        self.assertEqual(bg.table_rows(page("Варна").decode("utf-8"), 31), bg.table_rows(text, 31))
        self.assertIsNone(bg.page_problem(text, 31, "10"))

    def test_page_problem(self):
        self.assertIsNone(bg.page_problem(page("Варна").decode("utf-8"), 31, "10"))
        self.assertIn("no table", bg.page_problem(page("Варна", days=0).decode("utf-8"), 31, "10"))
        self.assertIn("no table", bg.page_problem(page("Варна", days=30).decode("utf-8"), 31, "10"))
        self.assertIn("month option '9'", bg.page_problem(page("Варна", month=9).decode("utf-8"), 31, "10"))


class CollectTest(unittest.TestCase):
    def test_every_city_once_a_month(self):
        with tempfile.TemporaryDirectory() as tmp:
            session = FakeSession(tmp, server())
            self.assertEqual(48, bg.collect(session, TODAY))
            self.assertEqual(49, len(session.calls))
            self.assertEqual((bg.PAGE_URL, None, None), session.calls[0])
            self.assertEqual((bg.PAGE_URL, b"option=com_namaz&grad=1&mesec=10", {"Referer": bg.PAGE_URL}), session.calls[1])
            self.assertEqual(48, len(saved_files(tmp)))
            self.assertEqual(page("Варна"), (Path(tmp) / "bg" / "2026" / "10" / "varna_2026-10.html").read_bytes())
            row = (Path(tmp) / "bg" / "MANIFEST.tsv").read_text(encoding="utf-8").splitlines()[1].split("\t")
            self.assertEqual(["2026/10/aytos_2026-10.html", bg.PAGE_URL,
                              "Айтос (aytos), 2026-10; POST option=com_namaz&grad=1&mesec=10"], [row[0], row[4], row[5]])
            # Again in the month: only the default page is read.
            self.assertEqual(0, bg.collect(session, dt.date(2026, 10, 20)))
            self.assertEqual(50, len(session.calls))

    def test_a_stopped_run_resumes(self):
        with tempfile.TemporaryDirectory() as tmp:
            for name in bg.file_names([(str(i), c) for i, c in enumerate(CITIES, 1)], TODAY):
                if not name.startswith(("2026/10/sofia_", "2026/10/yambol_")):
                    (Path(tmp) / "bg" / name).parent.mkdir(parents=True, exist_ok=True)
                    (Path(tmp) / "bg" / name).write_bytes(b"saved before")
            session = FakeSession(tmp, server())
            # Sofia's table is the default page's: held until Yambol's differs.
            self.assertEqual(2, bg.collect(session, TODAY))
            self.assertEqual([None, b"option=com_namaz&grad=40&mesec=10", b"option=com_namaz&grad=48&mesec=10"],
                             [call[1] for call in session.calls])
            self.assertEqual(page(DEFAULT), (Path(tmp) / "bg" / "2026" / "10" / "sofia_2026-10.html").read_bytes())

    def test_an_ignored_choice_saves_only_the_default_page(self):
        with tempfile.TemporaryDirectory() as tmp:
            session = FakeSession(tmp, server(ignore=True))
            with self.assertRaisesRegex(RuntimeError, "not reaching the server"):
                bg.collect(session, TODAY)
            self.assertEqual(3, len(session.calls))
            self.assertEqual(["default_2026-10.html"], saved_files(tmp))

    def test_no_city_list_keeps_the_default_page(self):
        bare = b"<html><body><h3>Times</h3><table></table></body></html>"
        with tempfile.TemporaryDirectory() as tmp:
            session = FakeSession(tmp, lambda url, data: bare)
            with self.assertRaisesRegex(RuntimeError, "no city list"):
                bg.collect(session, TODAY)
            self.assertEqual(1, len(session.calls))
            self.assertEqual(bare, (Path(tmp) / "bg" / "2026" / "10" / "default_2026-10.html").read_bytes())

    def test_a_city_without_its_table_does_not_stop_the_others(self):
        with tempfile.TemporaryDirectory() as tmp:
            session = FakeSession(tmp, server(broken={"Варна"}))
            with self.assertRaisesRegex(RuntimeError, r"1 of 48 cities not saved \(47 saved\): varna: no table"):
                bg.collect(session, TODAY)
            self.assertEqual(47, len(saved_files(tmp)))
            self.assertNotIn("varna_2026-10.html", saved_files(tmp))

    def test_a_failing_site_is_left_after_three_cities(self):
        def answer(url, data):
            if data is None:
                return page()
            raise urllib.error.HTTPError(url, 403, "Forbidden", {}, None)

        with tempfile.TemporaryDirectory() as tmp:
            session = FakeSession(tmp, answer)
            with self.assertRaisesRegex(RuntimeError, r"3 of 48 cities not saved \(0 saved\): aytos: HTTPError"):
                bg.collect(session, TODAY)
            self.assertEqual(4, len(session.calls))
            self.assertEqual(["default_2026-10.html"], saved_files(tmp))

    def test_contract(self):
        self.assertEqual(("bg", "bg", "monthly"), (bg.NAME, bg.CC, bg.CADENCE))
        self.assertTrue(bg.DESCRIPTION.isascii())
        self.assertIn("unverified", bg.DESCRIPTION)


if __name__ == "__main__":
    unittest.main()
