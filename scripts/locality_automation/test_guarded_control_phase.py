from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from scripts.locality_automation import guarded_control_phase as phase


class ControlPhaseTests(unittest.TestCase):
    def fixtures(self, directory, kind='source'):
        root = Path(directory)
        program = root / 'one_leaf.py'
        program.write_text('pass\n', encoding='utf-8')
        spec = {'phase': kind, 'owner': '/root', 'arguments': ['--input', 'path with spaces'],
                'program': {'file': str(program), 'sha256': 'fixture'}}
        control = {'mapOwner': '/root', 'mapMinimumRemainingSeconds': 420,
                   'safeSourceQaStartUtc': '2026-10-04T04:17:06.0000000+00:00',
                   'safeIntakeStartUtc': '2026-10-04T04:27:06.0000000+00:00',
                   'safeMapStartUtc': '2026-10-04T04:40:06.0000000+00:00',
                   'deadlineUtc': '2026-10-04T04:47:06.0000000+00:00'}
        return spec, control, root / 'exclusive.execution.json'

    def test_direct_original_guard_receives_literal_utc_and_python_leaf(self):
        with tempfile.TemporaryDirectory() as directory:
            for kind, (field, minimum) in phase.GATES.items():
                spec, control, receipt = self.fixtures(directory, kind)
                with patch.object(phase, 'active_control', return_value=control), \
                     patch.object(phase, 'checked', side_effect=lambda ref: Path(ref['file'])), \
                     patch.object(phase.guard, 'launch_guarded', return_value={'status': 'COMPLETED'}) as launch:
                    phase.launch(spec, receipt)
                    args = launch.call_args.args
                    self.assertEqual((args[0], args[1], args[3], args[4]),
                                     (control[field], control['deadlineUtc'], minimum, receipt))
                    self.assertEqual(args[2], [phase.sys.executable, '-X', 'utf8', '-B', spec['program']['file'], *spec['arguments']])

    def test_inactive_or_changed_window_cannot_start_original_guard(self):
        with tempfile.TemporaryDirectory() as directory:
            spec, _, receipt = self.fixtures(directory)
            with patch.object(phase, 'active_control', side_effect=ValueError('inactive')), \
                 patch.object(phase.guard, 'launch_guarded') as launch:
                with self.assertRaises(ValueError):
                    phase.launch(spec, receipt)
                launch.assert_not_called()

    def test_guard_or_payload_pin_failure_never_launches(self):
        with tempfile.TemporaryDirectory() as directory:
            spec, control, receipt = self.fixtures(directory)
            for where in ('guard', 'program'):
                def checked(ref):
                    if (where == 'guard' and ref['sha256'] == phase.GUARD_SHA256) or (where == 'program' and ref == spec['program']):
                        raise ValueError('changed pin')
                    return Path(ref['file'])
                with patch.object(phase, 'active_control', return_value=control), \
                     patch.object(phase, 'checked', side_effect=checked), \
                     patch.object(phase.guard, 'launch_guarded') as launch:
                    with self.assertRaises(ValueError):
                        phase.launch(spec, receipt)
                    launch.assert_not_called()

    def test_shell_payload_nonliteral_args_and_weakened_map_budget_are_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            for mutation in ('shell', 'args', 'budget'):
                spec, control, receipt = self.fixtures(directory, 'map')
                if mutation == 'shell': spec['program']['file'] = str(Path(directory) / 'launch.cmd')
                if mutation == 'args': spec['arguments'] = [object()]
                if mutation == 'budget': control['mapMinimumRemainingSeconds'] = 120
                with patch.object(phase, 'active_control', return_value=control), \
                     patch.object(phase, 'checked', side_effect=lambda ref: Path(ref['file'])), \
                     patch.object(phase.guard, 'launch_guarded') as launch:
                    with self.assertRaises(ValueError):
                        phase.launch(spec, receipt)
                    launch.assert_not_called()


if __name__ == '__main__':
    unittest.main()
