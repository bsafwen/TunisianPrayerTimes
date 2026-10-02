"""Explicit cwd/log API fixtures; no real review commands or network access."""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

from . import work_window_guard_jobs_v2 as guard


class GuardApiTests(unittest.TestCase):
    def test_concurrent_explicit_cwds_logs_and_inherited_proxy_do_not_change_globals(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            cwd_before, stdout_before, stderr_before = Path.cwd(), sys.stdout, sys.stderr
            proxy = {name: os.environ.get(name) for name in ('HTTP_PROXY', 'HTTPS_PROXY')}
            now = datetime.now(timezone.utc)
            safe, hard = guard._stamp(now + timedelta(seconds=10)), guard._stamp(now + timedelta(seconds=20))

            def run(index):
                child_cwd = root / str(index)
                child_cwd.mkdir()
                log, receipt = child_cwd / 'child.log', child_cwd / 'receipt.json'
                command = [sys.executable, '-c',
                           "import json,os,sys; print(json.dumps({'cwd':os.getcwd(),"
                           "'proxy':{n:os.environ.get(n) for n in ('HTTP_PROXY','HTTPS_PROXY')}}));"
                           "print('child stderr',file=sys.stderr)"]
                with log.open('wb') as handle:
                    result = guard.launch_guarded(safe, hard, command, record=receipt,
                                                  cwd=child_cwd, stdout=handle, stderr=handle)
                return child_cwd, log.read_text(), result

            with ThreadPoolExecutor(max_workers=2) as pool:
                results = list(pool.map(run, range(2)))
            for child_cwd, log, result in results:
                self.assertEqual('COMPLETED', result['status'])
                self.assertEqual(str(child_cwd), result['cwd'])
                observation = json.loads(next(line for line in log.splitlines() if line.startswith('{')))
                self.assertEqual(str(child_cwd), observation['cwd'])
                self.assertEqual(proxy, observation['proxy'])
                self.assertIn('child stderr', log)
            self.assertEqual(cwd_before, Path.cwd())
            self.assertIs(stdout_before, sys.stdout)
            self.assertIs(stderr_before, sys.stderr)
            self.assertEqual(proxy, {name: os.environ.get(name) for name in proxy})

    def test_shorter_command_timeout_stops_direct_child_and_keeps_receipt(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            receipt, marker = root / 'receipt.json', root / 'late.txt'
            now = datetime.now(timezone.utc)
            with (root / 'child.log').open('wb') as handle:
                result = guard.launch_guarded(
                    guard._stamp(now + timedelta(seconds=10)),
                    guard._stamp(now + timedelta(seconds=20)),
                    [sys.executable, '-c', "import pathlib,sys,time; time.sleep(5); pathlib.Path(sys.argv[1]).write_text('late')", str(marker)],
                    record=receipt, cwd=root, stdout=handle, stderr=handle, timeout_seconds=0.2,
                )
            self.assertEqual('DENIED', result['status'])
            self.assertEqual('command_timeout', result['reason'])
            self.assertTrue(result['timedOut'])
            self.assertIsNotNone(result['exitCode'])
            self.assertFalse(marker.exists())
            self.assertEqual(result, json.loads(receipt.read_text()))

    def test_invalid_api_setup_denies_before_reservation_or_spawn(self):
        with tempfile.TemporaryDirectory() as temp:
            record = Path(temp) / 'receipt.json'
            with patch.object(guard.subprocess, 'Popen') as popen:
                for timeout in (True, 0, -1, float('inf'), '1'):
                    with self.subTest(timeout=timeout), self.assertRaises(ValueError):
                        guard.launch_guarded('2030-01-01T00:00:00Z', '2030-01-01T00:10:00Z',
                                             ['fixture'], record=record, timeout_seconds=timeout)
                with self.assertRaises(ValueError):
                    guard.launch_guarded('2030-01-01T00:00:00Z', '2030-01-01T00:10:00Z',
                                         ['fixture'], record=record, cwd=Path(temp) / 'missing')
                popen.assert_not_called()
            self.assertFalse(record.exists())


if __name__ == '__main__':
    unittest.main()
