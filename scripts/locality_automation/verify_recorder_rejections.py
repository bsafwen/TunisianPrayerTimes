"""Confirm incomplete or stale reports cannot mutate isolated ledgers via the adapter."""
import argparse
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts.locality_automation.run_sealed_boundary_queue import checked, pin, read
from scripts.locality_automation import record_with_decode_reuse as adapter


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    original = read(args.manifest)
    run_bytes = checked(original['runBefore']).read_bytes()
    handoff_bytes = checked(original['handoffBefore']).read_bytes()
    source = read(checked(original['gps']))
    args.output.mkdir(exist_ok=False)
    rows, argv = [], sys.argv
    try:
        for name, expected in [('status', 'audit did not pass'), ('scope', 'full-source acceptance is incomplete'),
                               ('stale-pin', 'Pinned input changed')]:
            case = args.output / name
            case.mkdir()
            run, handoff, gps = case / 'run.json', case / 'handoff.json', case / 'gps.json'
            run.write_bytes(run_bytes)
            handoff.write_bytes(handoff_bytes)
            mutated = json.loads(json.dumps(source))
            if name == 'status':
                mutated['status'] = 'INCOMPLETE'
            elif name == 'scope':
                for row in mutated['decodedCurrentChecks'].values():
                    row['sourceScopeAccepted'] = False
            else:
                mutated['probeFailures'] = ['deliberate stale-input fixture']
            gps.write_text(json.dumps(mutated, ensure_ascii=False), encoding='utf-8')
            manifest = {**original, 'run': str(run), 'handoff': str(handoff), 'gps': pin(gps)}
            if name == 'stale-pin':
                manifest['gps']['sha256'] = original['gps']['sha256']
            manifest_path = case / 'manifest.json'
            manifest_path.write_text(json.dumps(manifest, ensure_ascii=False), encoding='utf-8')
            output, receipt = case / 'FORBIDDEN_ACCEPTANCE', case / 'FORBIDDEN_SUCCESS_RECEIPT.json'
            sys.argv = [adapter.__file__, '--manifest', str(manifest_path), '--cache-receipt', str(receipt),
                        '--run', str(run), '--handoff', str(handoff), '--gps', str(gps), '--output', str(output),
                        '--evidence-root', original['evidenceRoot'], '--report-data', original['reader']['file']]
            try:
                adapter.main()
            except ValueError as exc:
                error = str(exc)
                if expected not in error:
                    raise ValueError('Unexpected rejection: ' + error) from exc
            else:
                raise ValueError('Incomplete fixture was accepted: ' + name)
            if output.exists() or receipt.exists() or run.read_bytes() != run_bytes or handoff.read_bytes() != handoff_bytes:
                raise ValueError('Rejected fixture created acceptance or changed its ledgers')
            rows.append({'case': name, 'expectedRejection': expected, 'actualRejection': error,
                         'fixture': pin(manifest_path), 'noAcceptanceOutput': True, 'bothLedgersUnchanged': True})
    finally:
        sys.argv = argv
    for ref in original['liveEvidence'] + original['assets']:
        checked(ref)
    result = {'status': 'PASS_RECORDER_ADAPTER_RETAINS_INCOMPLETE_AND_STALE_REJECTIONS',
              'rows': rows, 'liveWrites': False, 'newCredit': 0}
    (args.output / 'proof.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'status': result['status'], 'cases': len(rows), 'newCredit': 0}))


if __name__ == '__main__':
    main()
