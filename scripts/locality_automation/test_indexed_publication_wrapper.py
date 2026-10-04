from contextlib import contextmanager
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

from scripts.locality_automation import refresh_report_with_task_source_index_v1 as wrapper


class IndexedPublicationWrapperTests(unittest.TestCase):
    def run_fixture(self, directory, fail=False, active=True):
        root = Path(directory)
        old_reader = lambda: 'old-reader'
        old_app = lambda: 'old-app'
        indexed = lambda: 'indexed-reader'
        reader = types.SimpleNamespace(build_task_report=old_reader)
        called = []
        def publish():
            called.append((sys.argv[:], app.build_task_report()))
            if fail:
                raise RuntimeError('original publisher failed')
        app = types.SimpleNamespace(build_task_report=old_app, main=publish)
        manifest = {'control': 'control', 'iteration': 18, 'files': [], 'assets': [], 'liveEvidence': [],
                    'reader': {'file': str(root / 'task_report_data.py')}, 'decodeCache': {},
                    'taskSourceIndex': {}, 'decoder': {}}
        control = {'phase': 'working' if active else 'paused', 'iteration': 18, 'acceptanceOwner': '/root',
                   'mapOwner': '/root', 'subagentsAllowed': False, 'safeMapStartUtc': 'safe', 'deadlineUtc': 'deadline'}
        @contextmanager
        def decode(*args):
            yield {'misses': 1}
        @contextmanager
        def index(*args):
            reader.build_task_report = indexed
            try:
                yield {'hits': 10, 'misses': 1, 'stabilityRechecks': 1}
            finally:
                reader.build_task_report = old_reader
        def read(path):
            if str(path) == 'control': return control
            if Path(path).name == 'task-report.json': return {'summary': {'uniqueValidatedLocations': 461}}
            return manifest
        receipt = root / 'receipt.json'
        before_argv = sys.argv
        with patch.object(sys, 'argv', ['wrapper', '--manifest', str(root / 'manifest.json'), '--receipt', str(receipt)]), \
             patch.object(wrapper, 'read', side_effect=read), \
             patch.object(wrapper, 'checked', side_effect=lambda ref: Path(ref.get('file', root))), \
             patch.object(wrapper, 'pin', side_effect=lambda path: {'file': str(path), 'sha256': 'fixture'}), \
             patch.object(wrapper, 'check_window', return_value={'status': 'ALLOWED'}) as gate, \
             patch.object(wrapper, 'reuse_reader_packed_slices', side_effect=decode), \
             patch.object(wrapper, 'indexed_task_source_paths', side_effect=index), \
             patch.object(wrapper.importlib, 'import_module', side_effect=lambda name: app if name == 'task_report_app' else reader):
            original_argv = sys.argv
            if not active:
                with self.assertRaises(ValueError): wrapper.main()
                self.assertEqual(called, [])
                gate.assert_not_called()
            elif fail:
                with self.assertRaises(RuntimeError): wrapper.main()
                self.assertFalse(receipt.exists())
            else:
                wrapper.main()
                self.assertEqual(json.loads(receipt.read_text())['newCredit'], 0)
            self.assertIs(sys.argv, original_argv)
            self.assertIs(app.build_task_report, old_app)
            self.assertIs(reader.build_task_report, old_reader)
        self.assertIs(sys.argv, before_argv)
        if active:
            self.assertEqual(called, [([str(root / 'task_report_app.py'), 'refresh'], 'indexed-reader')])
            gate.assert_called_once_with('safe', 'deadline', 420)

    def test_stock_command_and_bindings_restored_after_success(self):
        with tempfile.TemporaryDirectory() as directory: self.run_fixture(directory)

    def test_publisher_failure_restores_bindings_and_does_not_claim_receipt(self):
        with tempfile.TemporaryDirectory() as directory: self.run_fixture(directory, fail=True)

    def test_paused_window_refuses_publication_before_import_or_gate(self):
        with tempfile.TemporaryDirectory() as directory: self.run_fixture(directory, active=False)


if __name__ == '__main__':
    unittest.main()
