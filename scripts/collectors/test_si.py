"""The Slovenia collector, offline (python -I scripts/collectors/test_si.py)."""

import calendar
import datetime as dt
import itertools
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402
import si  # noqa: E402

TODAY = dt.date(2026, 10, 16)
MONTH_NAMES = {10: "Oktober", 11: "November", 12: "December", 1: "Januar"}
PLACES = [("Celje", "celje", -2, -2, -3, -4, -4, -4), ("Jesenice", "jesenice", 3, 3, 2, 1, 1, 1),
          ("Koper", "koper", 2, 2, 3, 4, 4, 4), ("Ljubljana", "ljubljana", 0, 0, 0, 0, 0, 0),
          ("Maribor", "maribor", -4, -4, -5, -5, -5, -5), ("Novo mesto", "novo_mesto", -2, -2, -2, -2, -2, -2)]


def feed(months=((2026, 10), (2026, 11), (2026, 12)), places=PLACES, server_time=1792104024868, edit=None):
    """Shaped like www.vaktija.si/api/v2/feed of 2026-10-16 (its calendar and events cut short)."""
    times = []
    for year, month in months:
        days = [{"day": d, "weekday": (calendar.weekday(year, month, d) + 1) % 7, "weekdayName": "Čet", "jummah": False,
                 "fajr": "05:16", "sunrise": "06:55", "dhuhr": "12:54", "asr": "16:04", "maghrib": "18:48",
                 "isha": "20:15"} for d in range(1, calendar.monthrange(year, month)[1] + 1)]
        times.append({"year": year, "month": month, "name": f"{MONTH_NAMES[month]} {year}", "days": days})
    body = {"serverTime": server_time,
            "cities": [dict(zip(("name", "cityid") + si.PRAYERS, place)) for place in places],
            "calendar": [{"date": "2026-01-19T23:00:00.000Z", "time": 1767222000000, "weekday": 4, "day": 1,
                          "month": 0, "year": 2026, "hYear": 1447, "hMonth": 6, "hDay": 12}],
            "events": {"gregorian": [], "hijri": []},
            "times": times}
    if edit:
        edit(body)
    return json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


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


class FeedNoteTest(unittest.TestCase):
    def test_months_and_places(self):
        self.assertEqual("Ljubljana 2026-10, 2026-11, 2026-12; offsets of 5 places: Celje, Jesenice, Koper, Maribor, "
                         "Novo mesto", si.feed_note(feed()))

    def test_not_the_feed(self):
        def drop_isha(body):
            del body["times"][1]["days"][3]["isha"]

        def short_month(body):
            body["times"][0]["days"].pop()

        for bad in (b"<html><body>502 Bad Gateway</body></html>", b"\xff\xfe", b"[]", b"{}",
                    feed(edit=drop_isha), feed(edit=short_month), feed(places=PLACES[:3]),
                    feed(edit=lambda body: body["times"][2].update(month=13))):
            with self.subTest(bad=bad[:40]), self.assertRaises(ValueError):
                si.feed_note(bad)


class CollectTest(unittest.TestCase):
    def test_one_copy_a_month(self):
        with tempfile.TemporaryDirectory() as tmp:
            served = {"feed": feed()}
            session = FakeSession(tmp, lambda url, data: served["feed"])
            self.assertEqual(1, si.collect(session, TODAY))
            self.assertEqual([("https://www.vaktija.si/api/v2/feed", None, {"Accept": "application/json"})], session.calls)
            self.assertEqual(feed(), (Path(tmp) / "si" / "feed_2026-10.json").read_bytes())
            # Later in October (every copy's serverTime differs): the month is saved, nothing is fetched.
            served["feed"] = feed(server_time=1792450000000)
            self.assertEqual(0, si.collect(session, dt.date(2026, 10, 20)))
            self.assertEqual(1, len(session.calls))
            # November: its own copy, which no longer holds October.
            served["feed"] = feed(((2026, 11), (2026, 12), (2027, 1)), server_time=1793520000000)
            self.assertEqual(1, si.collect(session, dt.date(2026, 11, 1)))
            rows = (Path(tmp) / "si" / "MANIFEST.tsv").read_text(encoding="utf-8").splitlines()
            self.assertEqual(["feed_2026-10.json", "feed_2026-11.json"], [row.split("\t")[0] for row in rows[1:]])
            self.assertTrue(rows[2].split("\t")[5].startswith("Ljubljana 2026-11, 2026-12, 2027-01;"))

    def test_an_error_page_is_not_saved(self):
        with tempfile.TemporaryDirectory() as tmp:
            session = FakeSession(tmp, lambda url, data: b"<html><body>Service Unavailable</body></html>")
            with self.assertRaises(ValueError):
                si.collect(session, TODAY)
            self.assertFalse((Path(tmp) / "si").exists())

    def test_contract(self):
        self.assertEqual(("si", "si", "monthly"), (si.NAME, si.CC, si.CADENCE))
        self.assertTrue(si.DESCRIPTION.isascii())
        # The bare vaktija.si's certificate names only www.vaktija.si.
        self.assertTrue(si.URL.startswith("https://www.vaktija.si/"))


if __name__ == "__main__":
    unittest.main()
