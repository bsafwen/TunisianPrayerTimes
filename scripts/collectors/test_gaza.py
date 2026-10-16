"""The Gaza collector, offline (python scripts/collectors/test_gaza.py): Telegram preview snippets, and the saved
palwakf.ps page (its footer) when at hand."""

import datetime as dt
import sys
import tempfile
import unittest
import urllib.error
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402
import gaza  # noqa: E402

PALWAKF_PAGE = common.LOCAL_DATA / "research-2026-10-08" / "PS" / "ps" / "post4553.html"
RADIO = gaza.search_url("quranradiofm", "مواقيت")
MINISTRY = gaza.search_url("palwakfg", "مواقيت")
IMSAKIYA = gaza.search_url("palwakfg", "إمساكية")
TABLE_IMAGE = "https://cdn4.telesco.pe/file/AbC-dEf_123.jpg"
JPEG = b"\xff\xd8\xff\xe0" + b"\x00" * 16


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


def post(channel: str, pid: int, text: str, photos=(), grouped: bool = False) -> str:
    """One post of a Telegram web preview, in its markup (photos as (post id, URL))."""
    if grouped:
        wraps = "".join(f'<a class="tgme_widget_message_photo_wrap grouped_media_wrap blured js-message_photo" '
                        f"style=\"left:0px;top:0px;width:226px;height:340px;background-image:url('{u}')\" "
                        f'data-ratio="0.66" href="https://t.me/{channel}/{p}?single"></a>' for p, u in photos)
        wraps = f'<div class="tgme_widget_message_grouped_wrap js-message_grouped_wrap">{wraps}</div>'
    else:
        wraps = "".join(f'<a class="tgme_widget_message_photo_wrap 5404614410443553402 -5404614410443553402" '
                        f"href=\"https://t.me/{channel}/{p}\" style=\"width:800px;background-image:url('{u}')\">"
                        f'<div class="tgme_widget_message_photo" style="padding-top:125%"></div></a>'
                        for p, u in photos)
    return (f'<div class="tgme_widget_message_wrap js-widget_message_wrap"><div class="tgme_widget_message '
            f'text_not_supported_wrap js-widget_message" data-post="{channel}/{pid}" data-view="eyJjIjoxfQ">'
            f'<div class="tgme_widget_message_bubble">{wraps}'
            f'<div class="tgme_widget_message_text js-message_text" dir="auto">{text}</div>'
            f'<div class="tgme_widget_message_footer compact js-message_footer">'
            f'<span class="tgme_widget_message_views">1.2K</span>'
            f'<a class="tgme_widget_message_date" href="https://t.me/{channel}/{pid}"><time '
            f'datetime="2026-10-15T17:01:27+00:00" class="time">17:01</time></a></div></div></div></div>')


def preview(*posts: str) -> bytes:
    return ('<!DOCTYPE html><html><body><section class="tgme_channel_history js-message_history">'
            + "".join(posts) + "</section></body></html>").encode("utf-8")


def daily(day: int) -> str:
    return (f"مواقيت الصلاة لمحافظات غزة ليوم الجمعة<br/>الموافق {day} أكتوبر 2026م<br/>الفجر: 4:33<br/>"
            "الشروق: 5:55<br/>الظهر: 11:28<br/>العصر: 2:45<br/>المغرب: 5:03<br/>العشاء: 6:20")


TABLE_TEXT = "مواقيت الصلاة لشهر جمادى الأولى 1448هـ &amp; المحافظات"
FOOTER = ('<section class="footer"><ul class="list-unstyled list-inline">'
          + "".join(f'<li class="list-inline-item"><bdi> x <span id="{k}" data-prayer="{k}">{v}</span></bdi></li>'
                    for k, v in zip(gaza.WIDGET, ("5:13", "12:30", "3:49", "6:22", "7:38")))
          + "</ul></section>").encode("utf-8")


def pages(radio: bytes, ministry: bytes = None, site: bytes = FOOTER) -> dict:
    return {RADIO: radio, MINISTRY: ministry if ministry is not None else preview(), IMSAKIYA: preview(),
            TABLE_IMAGE: JPEG, gaza.SITE: site}


class ParseTest(unittest.TestCase):
    def test_search_address_as_the_research_had_it(self):
        self.assertEqual("https://t.me/s/quranradiofm?q=%D9%85%D9%88%D8%A7%D9%82%D9%8A%D8%AA", RADIO)

    def test_posts_texts_and_photos(self):
        page = preview(post("quranradiofm", 10350, daily(16)),
                       post("palwakfg", 6088, TABLE_TEXT, photos=((6088, TABLE_IMAGE),)),
                       post("palwakfg", 6090, "", photos=((6090, "https://cdn4.telesco.pe/file/a.jpg"),
                                                          (6091, "https://cdn4.telesco.pe/file/b.jpg")), grouped=True))
        items = gaza.posts(page.decode("utf-8"))
        self.assertEqual([10350, 6088, 6090], [p["id"] for p in items])
        self.assertIn("الفجر: 4:33", items[0]["text"])
        self.assertIn("1448هـ & المحافظات", items[1]["text"])
        self.assertEqual([(6088, TABLE_IMAGE)], items[1]["photos"])
        self.assertEqual([6090, 6091], [pid for pid, _ in items[2]["photos"]])

    def test_daily_posts(self):
        self.assertTrue(gaza.is_daily(gaza.plain(daily(16))))
        indic = gaza.plain(daily(16)).translate({ord(c): chr(0x0660 + int(c)) for c in "0123456789"})
        self.assertTrue(gaza.is_daily(indic))
        self.assertFalse(gaza.is_daily(gaza.plain(TABLE_TEXT)))

    def test_table_photos(self):
        items = gaza.posts(preview(
            post("quranradiofm", 10350, daily(16), photos=((10350, "https://cdn4.telesco.pe/file/card.jpg"),)),
            post("palwakfg", 6088, TABLE_TEXT, photos=((6088, TABLE_IMAGE),)),
            post("palwakfg", 6089, "مواقيت البث المباشر", photos=((6089, "https://cdn4.telesco.pe/file/c.jpg"),)),
            post("palwakfg", 6092, "إمساكية شهر رمضان", photos=((6092, "https://cdn1.cdn-telegram.org/file/d.png"),
                                                               (6092, "https://cdn1.cdn-telegram.org/file/e"),
                                                               (6092, "https://example.com/f.jpg"))),
        ).decode("utf-8"))
        self.assertEqual([("images/palwakfg_6088.jpg", TABLE_IMAGE, "https://t.me/palwakfg/6088"),
                          ("images/palwakfg_6092.png", "https://cdn1.cdn-telegram.org/file/d.png",
                           "https://t.me/palwakfg/6092"),
                          ("images/palwakfg_6092_2.jpg", "https://cdn1.cdn-telegram.org/file/e",
                           "https://t.me/palwakfg/6092")], gaza.table_photos(items))

    def test_footer_of_the_saved_page(self):
        if not PALWAKF_PAGE.exists():
            self.skipTest(f"{PALWAKF_PAGE} is not at hand")
        times = gaza.footer_times(PALWAKF_PAGE.read_text(encoding="utf-8"))
        self.assertEqual({"fajr": "5:13", "zohr": "12:30", "asr": "3:49", "maghrib": "6:22", "esha": "7:38"},
                         {k: times[k] for k in gaza.WIDGET})


class CollectTest(unittest.TestCase):
    def test_runs_save_new_posts_photos_once_and_the_site_daily(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp) / "ps" / "gaza"
            radio = preview(post("quranradiofm", 10349, daily(15)), post("quranradiofm", 10350, daily(16)))
            ministry = preview(post("palwakfg", 6088, TABLE_TEXT, photos=((6088, TABLE_IMAGE),)))
            session = FakeSession(Path(tmp), pages(radio, ministry))
            self.assertEqual(4, gaza.collect(session, dt.date(2026, 10, 16)))
            self.assertEqual(radio, (folder / "telegram" / "quranradiofm_mawaqit_20261016.html").read_bytes())
            self.assertTrue((folder / "telegram" / "palwakfg_mawaqit_20261016.html").exists())
            self.assertFalse((folder / "telegram" / "palwakfg_imsakiya_20261016.html").exists())   # no posts
            self.assertEqual(JPEG, (folder / "images" / "palwakfg_6088.jpg").read_bytes())
            self.assertTrue((folder / "palwakf" / "palwakf_ar_20261016.html").exists())
            rows = (Path(tmp) / "ps" / "MANIFEST.tsv").read_text(encoding="utf-8")
            self.assertIn("search mawaqit: 2 posts, 2 new, ids 10349-10350", rows)
            self.assertIn("photo of https://t.me/palwakfg/6088", rows)
            self.assertIn("footer: fajr 5:13 zohr 12:30 asr 3:49 maghrib 6:22 esha 7:38", rows)

            # The next day: the previews hold no new post (only their view counts changed) and the photo is saved.
            session = FakeSession(Path(tmp), pages(radio.replace(b"1.2K", b"1.3K"), ministry))
            self.assertEqual(1, gaza.collect(session, dt.date(2026, 10, 17)))
            self.assertNotIn(TABLE_IMAGE, session.asked)
            self.assertFalse((folder / "telegram" / "quranradiofm_mawaqit_20261017.html").exists())
            self.assertTrue((folder / "palwakf" / "palwakf_ar_20261017.html").exists())

            # A new radio post: that preview is saved again.
            radio = preview(post("quranradiofm", 10350, daily(16)), post("quranradiofm", 10360, daily(18)))
            session = FakeSession(Path(tmp), pages(radio, ministry))
            self.assertEqual(2, gaza.collect(session, dt.date(2026, 10, 18)))
            self.assertTrue((folder / "telegram" / "quranradiofm_mawaqit_20261018.html").exists())

    def test_failures_are_reported_after_the_rest_is_saved(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp) / "ps" / "gaza"
            given = pages(preview(post("quranradiofm", 10350, daily(16))), site=urllib.error.URLError("timed out"))
            given[MINISTRY] = urllib.error.HTTPError(MINISTRY, 502, "Bad Gateway", {}, None)
            session = FakeSession(Path(tmp), given)
            with self.assertRaisesRegex(RuntimeError, "palwakfg mawaqit: HTTPError.*palwakf.ps: URLError"):
                gaza.collect(session, dt.date(2026, 10, 16))
            self.assertTrue((folder / "telegram" / "quranradiofm_mawaqit_20261016.html").exists())

    def test_an_empty_radio_preview_or_a_bare_site_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp) / "ps" / "gaza"
            session = FakeSession(Path(tmp), pages(preview(), site=b"<html><footer></footer></html>"))
            with self.assertRaisesRegex(RuntimeError, "quranradiofm mawaqit: no posts.*footer's five times"):
                gaza.collect(session, dt.date(2026, 10, 16))
            self.assertTrue((folder / "telegram" / "quranradiofm_mawaqit_20261016.html").exists())
            self.assertTrue((folder / "palwakf" / "palwakf_ar_20261016.html").exists())


if __name__ == "__main__":
    unittest.main()
