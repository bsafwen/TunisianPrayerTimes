"""Exact input changes and original validation rejection must survive reuse."""
import struct
import importlib.util
from pathlib import Path
import unittest
from scripts.locality_automation import packed_gps_replay as replay
from scripts.locality_automation.packed_decode_cache import reuse_identical_packed_slices, PackedSliceDecodeCache


def triangle(point=1000000):
    values = [1, 1, 3, 0, 0, point, 0, 0, 1000000]
    chunk = struct.pack(">" + "i" * len(values), *values)
    return b"NPOL\0\0\0\1" + chunk, {"offset": 8, "length": len(chunk)}


class DecodeReuseContracts(unittest.TestCase):
    def test_identical_dynamic_modules_share_only_exact_immutable_slices(self):
        binary, row = triangle()
        cache = PackedSliceDecodeCache()
        modules = []
        for index in range(2):
            spec = importlib.util.spec_from_file_location("independent_dynamic_" + str(index), Path(replay.__file__))
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            modules.append((module, module._decode_record))
            cache.bind(module)
            actual = module._decode_record(binary, row, 1000000, "id")
            self.assertEqual(actual, replay._decode_record(binary, row, 1000000, "id"))
        self.assertEqual(cache.statistics["hits"], 1)
        self.assertEqual(cache.statistics["misses"], 1)
        cache.close()
        self.assertTrue(all(module._decode_record is original for module, original in modules))

    def test_identical_bytes_reuse_immutable_result_and_changed_bytes_do_not(self):
        binary, row = triangle()
        changed, _ = triangle(2000000)
        original = replay._decode_record
        expected = original(binary, row, 1000000, "same-id")
        with reuse_identical_packed_slices() as counts:
            first = replay._decode_record(binary, row, 1000000, "same-id")
            self.assertEqual(first, expected)
            self.assertIs(replay._decode_record(binary, row, 1000000, "same-id"), first)
            self.assertNotEqual(replay._decode_record(changed, row, 1000000, "same-id"), first)
            self.assertEqual(counts["hits"], 1)
            self.assertEqual(counts["misses"], 2)
        self.assertIs(replay._decode_record, original)

    def test_scale_identity_and_invalid_slice_cannot_hit_prior_cache(self):
        binary, row = triangle()
        with reuse_identical_packed_slices() as counts:
            first = replay._decode_record(binary, row, 1000000, "id")
            self.assertNotEqual(replay._decode_record(binary, row, 2000000, "id"), first)
            replay._decode_record(binary, row, 1000000, "other-id")
            with self.assertRaisesRegex(ValueError, "Invalid packed slice"):
                replay._decode_record(binary, {"offset": True, "length": row["length"]}, 1000000, "id")
            with self.assertRaisesRegex(ValueError, "Invalid packed slice"):
                replay._decode_record(binary, {"offset": 12, "length": row["length"]}, 1000000, "id")
            self.assertEqual(counts["hits"], 0)

    def test_changed_invalid_vertex_is_still_rejected_and_original_restored(self):
        binary, row = triangle()
        invalid, _ = triangle(181000000)
        original = replay._decode_record
        with self.assertRaisesRegex(ValueError, "Invalid vertex"):
            with reuse_identical_packed_slices():
                replay._decode_record(binary, row, 1000000, "id")
                replay._decode_record(invalid, row, 1000000, "id")
        self.assertIs(replay._decode_record, original)

    def test_changed_dependencies_and_nested_context_are_rejected(self):
        binary, row = triangle()
        original = replay._decode_record
        original_coordinate_check = replay.valid_coordinates
        with reuse_identical_packed_slices():
            with self.assertRaisesRegex(ValueError, "Nested"):
                with reuse_identical_packed_slices():
                    pass
            replay.valid_coordinates = lambda lat, lon: True
            try:
                with self.assertRaisesRegex(ValueError, "dependencies changed"):
                    replay._decode_record(binary, row, 1000000, "id")
            finally:
                replay.valid_coordinates = original_coordinate_check
        self.assertIs(replay._decode_record, original)


if __name__ == "__main__":
    unittest.main()
