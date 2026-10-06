"""The scoped adapter preserves original rejection gates and exact loader pins."""
import importlib.util
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from scripts.locality_automation.packed_decode_cache_caf_v1 import reuse_caf_reader
from scripts.locality_automation.run_sealed_boundary_queue import pin


class CafAdapterContracts(unittest.TestCase):
    def fixture(self, root):
        caf_path = root / 'caf.py'
        caf_path.write_text('def record(args):\n    if not args.allowed:\n        raise ValueError("original source gate")\n    module_spec.loader.exec_module(module)\n    return "tail"\n', encoding='utf-8')
        reader_path, decoder_path = root / 'reader.py', root / 'decoder.py'
        reader_path.write_text('# pinned reader\n', encoding='utf-8')
        decoder_path.write_text('# pinned decoder\n', encoding='utf-8')
        spec = importlib.util.spec_from_file_location('fixture_stock_caf', caf_path)
        caf = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(caf)
        calls = []
        caf.module = SimpleNamespace()
        caf.module_spec = SimpleNamespace(loader=SimpleNamespace(exec_module=lambda module: calls.append('loaded')))
        return caf, pin(caf_path), pin(reader_path), pin(decoder_path), calls

    def test_original_gate_runs_and_record_is_restored_after_exception(self):
        with tempfile.TemporaryDirectory() as directory:
            caf, caf_ref, reader_ref, decoder_ref, calls = self.fixture(Path(directory))
            original = caf.record
            with self.assertRaisesRegex(ValueError, 'original source gate'):
                with reuse_caf_reader(caf, caf_ref, reader_ref, decoder_ref):
                    caf.record(SimpleNamespace(allowed=False, report_data=Path(reader_ref['file'])))
            self.assertEqual(calls, [])
            self.assertIs(caf.record, original)

    def test_wrong_reader_path_rejects_before_any_dynamic_code_executes(self):
        with tempfile.TemporaryDirectory() as directory:
            caf, caf_ref, reader_ref, decoder_ref, calls = self.fixture(Path(directory))
            original = caf.record
            with reuse_caf_reader(caf, caf_ref, reader_ref, decoder_ref):
                with self.assertRaisesRegex(ValueError, 'differs from exact pin'):
                    caf.record(SimpleNamespace(allowed=True, report_data=Path(directory) / 'other-reader.py'))
            self.assertEqual(calls, [])
            self.assertIs(caf.record, original)


if __name__ == '__main__':
    unittest.main()
