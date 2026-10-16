"""The collectors' shared plumbing, offline (python scripts/collectors/test_common.py)."""

import datetime as dt
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402
import run  # noqa: E402


class SaveTest(unittest.TestCase):
    def test_manifest_rows_and_repeats(self):
        with tempfile.TemporaryDirectory() as tmp:
            clock = iter([dt.datetime(2026, 10, 16, 8, 0, 0, tzinfo=dt.timezone.utc),
                          dt.datetime(2026, 10, 16, 8, 0, 5, tzinfo=dt.timezone.utc),
                          dt.datetime(2026, 10, 16, 9, 30, 0, tzinfo=dt.timezone.utc)])
            session = common.Session(root=Path(tmp), pause=0, now=lambda: next(clock))
            first = session.save("LY", "2026/10/ly_2026-10-16.html", b"<p>one</p>", "https://example.invalid/a", "today\tpage")
            self.assertEqual(Path(tmp) / "ly" / "2026" / "10" / "ly_2026-10-16.html", first)
            # The same bytes again: kept, no new row.
            self.assertIsNone(session.save("LY", "2026/10/ly_2026-10-16.html", b"<p>one</p>", "https://example.invalid/a"))
            # Other bytes under the same name: saved beside it with the fetch time.
            second = session.save("LY", "2026/10/ly_2026-10-16.html", b"<p>two</p>", "https://example.invalid/a")
            self.assertEqual("ly_2026-10-16-093000.html", second.name)
            rows = (Path(tmp) / "ly" / "MANIFEST.tsv").read_text(encoding="utf-8").splitlines()
            self.assertEqual("\t".join(common.MANIFEST_COLUMNS), rows[0])
            self.assertEqual(3, len(rows))
            cells = rows[1].split("\t")
            self.assertEqual(["2026/10/ly_2026-10-16.html", "10", common.sha256(b"<p>one</p>"), "2026-10-16T08:00:00Z",
                              "https://example.invalid/a", "today page"], cells)
            self.assertEqual([first, second], session.saved)

    def test_once_keeps_the_first_copy_of_a_name(self):
        with tempfile.TemporaryDirectory() as tmp:
            session = common.Session(root=Path(tmp), pause=0)
            self.assertIsNotNone(session.save("QA", "cal/cal_20261016.html", b"token 1", "u", once=True))
            self.assertIsNone(session.save("QA", "cal/cal_20261016.html", b"token 2", "u", once=True))
            self.assertEqual(b"token 1", (Path(tmp) / "qa" / "cal" / "cal_20261016.html").read_bytes())
            self.assertEqual(2, len((Path(tmp) / "qa" / "MANIFEST.tsv").read_text(encoding="utf-8").splitlines()))


class GapsTest(unittest.TestCase):
    def test_days_without_a_saved_file(self):
        import gaps
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "MANIFEST.tsv"
            manifest.write_text("\t".join(common.MANIFEST_COLUMNS) + "\n"
                                "a.html\t1\tx\t2026-10-14T08:00:00Z\tu\t\n"
                                "b.html\t1\tx\t2026-10-16T08:00:00Z\tu\t\n", encoding="utf-8", newline="")
            days = gaps.fetched_days(manifest)
            self.assertEqual({dt.date(2026, 10, 14), dt.date(2026, 10, 16)}, days)
            self.assertEqual([dt.date(2026, 10, 13), dt.date(2026, 10, 15)], gaps.gaps(days, dt.date(2026, 10, 17), 4))
            self.assertEqual(set(), gaps.fetched_days(Path(tmp) / "none.tsv"))


class RunnerTest(unittest.TestCase):
    def test_unknown_collector_is_refused(self):
        self.assertEqual(2, run.main(["--only", "xx"]))


if __name__ == "__main__":
    unittest.main()
