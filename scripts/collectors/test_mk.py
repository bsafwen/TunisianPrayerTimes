"""The North Macedonia collector, offline (python -I scripts/collectors/test_mk.py)."""

import datetime as dt
import itertools
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402
import mk  # noqa: E402

TODAY = dt.date(2026, 10, 16)

# Shaped like bfi.mk's home page of 2026-10-16 (WordPress, theme "taqwa"): the theme's widget, then the BFI's block. The
# script's decoy must not be read.
PAGE = """<!DOCTYPE html>
<html lang="sq-AL"><head><meta charset="UTF-8"><title>BFI - Bashkësia Fetare Islame</title>
<script>var demo = "Koha (Shkup) Sabahu 1:11 AM Dreka 1:12 PM";</script></head>
<body><header><a href="#">SHQ</a> | <a href="#">MKD</a> | <a href="#">ENG</a></header>
<div class="prayer-times"><img src="https://bfi.mk/wp-content/themes/taqwa/assets/images/prayer-head-shp.png" alt="">
<h3>Kohët e Namazit</h3><ul>
<li>Sabahu <span>Koha: 5:58 AM</span></li><li>Dreka <span>Koha: 12:27 PM</span></li>
<li>Iqindia <span>Koha: 3:24 PM</span></li><li>Akshami <span>Koha: 6:01 PM</span></li>
<li>Jacia <span>Koha: 7:08 PM</span></li></ul></div>
<section class="namaz"><h4>Kohët e namazit</h4><table>
<tr><th>Namazi</th><th>Koha&nbsp;(Shkup)</th></tr>
<tr><td>Sabahu</td><td>5:57 AM</td></tr><tr><td>Dreka</td><td>12:27 PM</td></tr>
<tr><td>Iqindia</td><td>3:26 PM</td></tr><tr><td>Akshami</td><td>6:03 PM</td></tr>
<tr><td>Jacia</td><td>7:09 PM</td></tr></table></section>
<article><p>September 4, 2026</p><h2>BFI shënon Vitin e Ri Islam Hixhri 1448</h2></article>
</body></html>""".encode("utf-8")

BFI_TIMES = {"Sabahu": "5:57 AM", "Dreka": "12:27 PM", "Iqindia": "3:26 PM", "Akshami": "6:03 PM", "Jacia": "7:09 PM"}


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


def manifest(root):
    return (Path(root) / "mk" / "MANIFEST.tsv").read_text(encoding="utf-8").splitlines()


class BlockTest(unittest.TestCase):
    def test_reads_the_bfi_block_not_the_theme_widget(self):
        self.assertEqual(BFI_TIMES, mk.block_times(mk.page_text(PAGE)))

    def test_no_block(self):
        widget_only = PAGE.replace("Koha&nbsp;(Shkup)".encode(), b"Koha")
        self.assertIsNone(mk.block_times(mk.page_text(widget_only)))
        # A row whose time does not follow its name is not filled from elsewhere.
        self.assertIsNone(mk.block_times(mk.page_text(PAGE.replace(b"<td>Dreka</td>", b"<td>Dreka</td><td>-</td>"))))

    def test_24_hour_and_spelt_out_times(self):
        text = "Kohët e namazit Namazi Koha (Shkup) Sabahu 05:52 Dreka 12:28 Iqindia 15:31 Akshami 18:09 Jacia 19:16"
        self.assertEqual("19:16", mk.block_times(text)["Jacia"])
        self.assertEqual("7:09 p.m.", mk.block_times(text.replace("19:16", "7:09 p.m."))["Jacia"])

    def test_charset(self):
        meta = b'<meta http-equiv="Content-Type" content="text/html; charset=windows-1250">'
        self.assertEqual("cp1250", mk.page_charset(meta))
        self.assertEqual("utf-8", mk.page_charset(b"<html><body>no meta</body></html>"))
        self.assertEqual("utf-8", mk.page_charset(b'<meta charset="x-unknown-1">'))

    def test_file_name(self):
        self.assertEqual("2026/10/bfi_2026-10-16.html", mk.file_name(TODAY))


class CollectTest(unittest.TestCase):
    def test_saves_the_page_once_a_day(self):
        with tempfile.TemporaryDirectory() as tmp:
            session = FakeSession(tmp, lambda url, data: PAGE)
            self.assertEqual(1, mk.collect(session, TODAY))
            self.assertEqual([(mk.URL, None, None)], session.calls)
            self.assertEqual(PAGE, (Path(tmp) / "mk" / "2026" / "10" / "bfi_2026-10-16.html").read_bytes())
            row = manifest(tmp)[1].split("\t")
            self.assertEqual(["2026/10/bfi_2026-10-16.html", str(len(PAGE)), common.sha256(PAGE)], row[:3])
            self.assertEqual(mk.URL, row[4])
            self.assertEqual("Koha (Shkup) as shown at fetch time: Sabahu 5:57 AM, Dreka 12:27 PM, Iqindia 3:26 PM, "
                             "Akshami 6:03 PM, Jacia 7:09 PM", row[5])
            # The same bytes again the same day: nothing new. The next day: its own copy.
            self.assertEqual(0, mk.collect(session, TODAY))
            self.assertEqual(1, mk.collect(session, TODAY + dt.timedelta(days=1)))
            self.assertTrue((Path(tmp) / "mk" / "2026" / "10" / "bfi_2026-10-17.html").exists())
            self.assertEqual(3, len(manifest(tmp)))

    def test_a_page_without_the_block_is_kept_and_the_run_fails(self):
        changed = PAGE.replace("Koha&nbsp;(Shkup)".encode(), b"Ora")
        with tempfile.TemporaryDirectory() as tmp:
            session = FakeSession(tmp, lambda url, data: changed)
            with self.assertRaises(RuntimeError):
                mk.collect(session, TODAY)
            self.assertEqual(changed, (Path(tmp) / "mk" / "2026" / "10" / "bfi_2026-10-16.html").read_bytes())
            self.assertTrue(manifest(tmp)[1].split("\t")[5].startswith("NO 'Koha (Shkup)' block"))

    def test_contract(self):
        self.assertEqual(("mk", "mk", "daily"), (mk.NAME, mk.CC, mk.CADENCE))
        self.assertTrue(mk.DESCRIPTION.isascii())
        self.assertTrue(mk.URL.startswith("https://"))


if __name__ == "__main__":
    unittest.main()
