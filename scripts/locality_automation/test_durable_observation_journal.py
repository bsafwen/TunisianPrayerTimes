import copy
import io
import unittest
from scripts.locality_automation.durable_observation_journal import persist_probe, validate_probe


class Stream(io.StringIO):
    def fileno(self):
        return 42


class DurableProbeTests(unittest.TestCase):
    def rows(self):
        return [{"probeOrdinal": 7, "id": "p7", "lat": 35., "lng": 9., "modeOrdinal": i,
                 "accuracyMeters": mode, "indexed": {"winnerId": None}}
                for i, mode in enumerate((None, 5, 20, 50))]

    def test_all_four_records_precede_barrier_and_every_classification(self):
        stream, order = Stream(), []
        def sync(fd):
            self.assertEqual(fd, 42)
            self.assertEqual(len(stream.getvalue().splitlines()), 4)
            order.append("durable")
        persist_probe(stream, self.rows(), lambda row: order.append(row["modeOrdinal"]), sync)
        self.assertEqual(order, ["durable", 0, 1, 2, 3])

    def test_failed_barrier_cannot_classify_any_record(self):
        classified = []
        def fail(fd):
            raise OSError("sync failed")
        with self.assertRaises(OSError):
            persist_probe(Stream(), self.rows(), classified.append, fail)
        self.assertEqual(classified, [])

    def test_missing_reordered_and_foreign_probe_rejected_before_write(self):
        variants = [self.rows()[:3], list(reversed(self.rows()))]
        altered = self.rows()
        altered[2]["lat"] = 35.1
        variants.append(altered)
        for rows in variants:
            stream = Stream()
            with self.assertRaises(ValueError):
                persist_probe(stream, rows, lambda row: self.fail("classification must not happen"), lambda fd: None)
            self.assertEqual(stream.getvalue(), "")

    def test_classification_failure_keeps_complete_durable_probe(self):
        stream, synced = Stream(), []
        def fail(row):
            raise RuntimeError("classification failed")
        with self.assertRaises(RuntimeError):
            persist_probe(stream, self.rows(), fail, synced.append)
        self.assertEqual(synced, [42])
        self.assertEqual(len(stream.getvalue().splitlines()), 4)


if __name__ == "__main__":
    unittest.main()
