"""Offline Git fixtures for the data-only automation PR preparation path."""

from __future__ import annotations

import json
from pathlib import Path
import subprocess
import tempfile
import unittest

import prepare_islamic_date_pr as helper


def record(**changes):
    result = {
        "hijriYear": 1447,
        "ramadanStart": "2026-02-19",
        "eidFitrDate": None,
        "eidAdhaDate": None,
        "lastUpdated": "2026-02-18T20:00:00Z",
    }
    result.update(changes)
    return result


class PendingDateMergeTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.path = self.root / helper.DATA_DIRECTORY / "1447.json"
        self.command("init", "-q", "-b", "main")
        self.command("config", "user.name", "Fixture")
        self.command("config", "user.email", "fixture@example.invalid")
        self.command("config", "core.autocrlf", "false")
        self.script = self.root / "scripts" / "detector.py"
        self.script.parent.mkdir()
        self.script.write_text("trusted base code\n", encoding="utf-8")

    def command(self, *args):
        return subprocess.run(["git", "-C", str(self.root), *args], check=True,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.decode().strip()

    def write(self, data):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(json.dumps(data) + "\n", encoding="utf-8")

    def save(self, message):
        self.command("add", "-A")
        self.command("commit", "-qm", message)
        return self.command("rev-parse", "HEAD")

    def branches(self, ancestor, pending, base=None):
        if ancestor is not None:
            self.write(ancestor)
        self.save("ancestor")
        self.command("checkout", "-qb", "automation")
        if pending is None:
            self.path.unlink()
        else:
            self.write(pending)
        self.script.write_text("raise RuntimeError('PR code must never run')\n", encoding="utf-8")
        pending_sha = self.save("pending data and untrusted script")
        self.command("checkout", "-q", "main")
        if base is not None:
            self.write(base)
            self.save("main update")
        return pending_sha

    def read(self):
        return json.loads(self.path.read_text(encoding="utf-8"))

    def test_pending_event_and_reviewer_correction_survive_without_pr_code(self):
        ancestor = record(eidFitrDate="2026-03-20")
        proposed = record(eidFitrDate="2026-03-21", lastUpdated="2026-03-19T20:00:00Z")
        pending = self.branches(ancestor, proposed)
        helper.prepare(self.root, "HEAD", pending)
        self.assertEqual(proposed, self.read())
        self.assertEqual("trusted base code\n", self.script.read_text(encoding="utf-8"))
        self.assertEqual("main", self.command("branch", "--show-current"))
        self.assertFalse(helper.differs(self.root, pending))

    def test_independent_main_and_pending_events_merge_with_separate_timestamps(self):
        pending = self.branches(record(),
            record(eidFitrDate="2026-03-20", lastUpdated="2026-03-19T20:00:00Z"),
            record(eidAdhaDate="2026-05-27", lastUpdated="2026-05-17T20:00:00Z"))
        helper.prepare(self.root, "HEAD", pending)
        merged = self.read()
        self.assertEqual("2026-03-20", merged["eidFitrDate"])
        self.assertEqual("2026-05-27", merged["eidAdhaDate"])
        self.assertEqual("2026-02-18T20:00:00Z", merged["ramadanStartUpdated"])
        self.assertEqual("2026-03-19T20:00:00Z", merged["eidFitrUpdated"])
        self.assertEqual("2026-05-17T20:00:00Z", merged["eidAdhaUpdated"])
        self.assertEqual("2026-05-17T20:00:00Z", merged["lastUpdated"])

    def test_main_correction_wins_when_pending_did_not_change_that_event(self):
        ancestor = record(eidFitrDate="2026-03-20")
        pending = self.branches(ancestor,
            record(eidFitrDate="2026-03-20", eidAdhaDate="2026-05-27", lastUpdated="2026-05-17T20:00:00Z"),
            record(eidFitrDate="2026-03-21", lastUpdated="2026-03-20T20:00:00Z"))
        helper.prepare(self.root, "HEAD", pending)
        self.assertEqual("2026-03-21", self.read()["eidFitrDate"])
        self.assertEqual("2026-03-20T20:00:00Z", self.read()["eidFitrUpdated"])

    def test_explicit_unknown_provenance_survives_unrelated_event_merges(self):
        ancestor = record(ramadanStartUpdated=None)
        pending = self.branches(ancestor,
            record(ramadanStartUpdated=None, eidFitrDate="2026-03-20", lastUpdated="2026-03-19T20:00:00Z"),
            record(ramadanStartUpdated=None, eidAdhaDate="2026-05-27", lastUpdated="2026-05-17T20:00:00Z"))
        helper.prepare(self.root, "HEAD", pending)
        self.assertIn("ramadanStartUpdated", self.read())
        self.assertIsNone(self.read()["ramadanStartUpdated"])
        self.assertEqual("2026-03-19T20:00:00Z", self.read()["eidFitrUpdated"])
        self.assertEqual("2026-05-17T20:00:00Z", self.read()["eidAdhaUpdated"])

    def test_conflicting_corrections_fail_without_modifying_working_data(self):
        ancestor = record(eidFitrDate="2026-03-20", eidAdhaDate="2026-05-27")
        current = record(eidFitrDate="2026-03-20", eidAdhaDate="2026-05-26")
        pending = self.branches(ancestor,
            record(eidFitrDate="2026-03-20", eidAdhaDate="2026-05-28"), current)
        with self.assertRaisesRegex(ValueError, "conflicting edits to eidAdhaDate"):
            helper.prepare(self.root, "HEAD", pending)
        self.assertEqual(current, self.read())
        self.assertEqual("", self.command("status", "--porcelain"))

    def test_invalid_combined_month_length_fails_before_write(self):
        ancestor = record(ramadanStart="2026-02-18", eidFitrDate="2026-03-20")
        current = record(ramadanStart="2026-02-19", eidFitrDate="2026-03-20")
        pending = self.branches(ancestor,
            record(ramadanStart="2026-02-18", eidFitrDate="2026-03-19"), current)
        with self.assertRaisesRegex(ValueError, "29 or 30"):
            helper.prepare(self.root, "HEAD", pending)
        self.assertEqual(current, self.read())

    def test_independently_added_year_merges_unknown_and_known_fields(self):
        pending = self.branches(None, record(eidFitrDate="2026-03-20"), record(eidAdhaDate="2026-05-27"))
        helper.prepare(self.root, "HEAD", pending)
        self.assertEqual("2026-03-20", self.read()["eidFitrDate"])
        self.assertEqual("2026-05-27", self.read()["eidAdhaDate"])

    def test_semantically_identical_pending_data_does_not_require_another_push(self):
        proposed = record(eidFitrDate="2026-03-20")
        pending = self.branches(record(), proposed)
        helper.prepare(self.root, "HEAD", pending)
        self.path.write_text(json.dumps(proposed, indent=4) + "\n", encoding="utf-8")
        self.assertFalse(helper.differs(self.root, pending))
        self.assertTrue(helper.differs(self.root, "HEAD"))

    def test_local_data_edits_are_not_replaced(self):
        pending = self.branches(record(), record(eidFitrDate="2026-03-20"))
        local = record(eidAdhaDate="2026-05-27")
        self.write(local)
        with self.assertRaisesRegex(ValueError, "refusing to replace local edits"):
            helper.prepare(self.root, "HEAD", pending)
        self.assertEqual(local, self.read())

    def test_pending_file_deletion_is_preserved(self):
        pending = self.branches(record(), None)
        helper.prepare(self.root, "HEAD", pending)
        self.assertFalse(self.path.exists())
        self.assertFalse(helper.differs(self.root, pending))


if __name__ == "__main__":
    unittest.main()
