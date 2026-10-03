"""Compare complete stock CAF acceptance on authentic isolated ledger copies only."""
import argparse
from contextlib import nullcontext
from datetime import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys
import time
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts.locality_automation.run_sealed_boundary_queue import checked, pin, read
from scripts.locality_automation.packed_decode_cache_caf_v1 import reuse_caf_reader


def canonical(value, output):
    if isinstance(value, dict):
        result = {key: canonical(item, output) for key, item in value.items()}
        if isinstance(value.get('file'), str) and Path(value['file']).is_relative_to(output):
            actual = checked(value)
            normalized = canonical(read(actual), output)
            raw = json.dumps(normalized, sort_keys=True, ensure_ascii=False).encode('utf-8')
            result['sha256'] = hashlib.sha256(raw).hexdigest()
            if 'bytes' in result:
                result['bytes'] = len(raw)
        return result
    if isinstance(value, list):
        return [canonical(item, output) for item in value]
    if isinstance(value, str):
        return value.replace(str(output), '<ISOLATED_OUTPUT>')
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--mode', choices=('baseline', 'cache'), required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--compare', type=Path)
    args = parser.parse_args()
    manifest = read(args.manifest)
    for ref in manifest['liveEvidence'] + manifest['assets']:
        checked(ref)
    for key in ('caf', 'reader', 'decoder', 'decodeCache', 'runBefore', 'handoffBefore', 'gps'):
        checked(manifest[key])
    args.output.mkdir(exist_ok=False)
    run_path, handoff_path = args.output / 'run.json', args.output / 'handoff.json'
    run_before, handoff_before = checked(manifest['runBefore']).read_bytes(), checked(manifest['handoffBefore']).read_bytes()
    run_path.write_bytes(run_before)
    handoff_path.write_bytes(handoff_before)
    spec = importlib.util.spec_from_file_location('complete_stock_caf_benchmark', checked(manifest['caf']))
    caf = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(caf)
    fixed = datetime.fromisoformat(manifest['fixedUtc'])
    if fixed.tzinfo is None:
        raise ValueError('Aware fixture clock required')
    class FixedClock(datetime):
        @classmethod
        def now(cls, tz=None):
            return fixed.astimezone(tz) if tz else fixed.replace(tzinfo=None)
    caf.datetime = FixedClock
    context = (reuse_caf_reader(caf, manifest['caf'], manifest['reader'], manifest['decoder'])
               if args.mode == 'cache' else nullcontext(None))
    payload = SimpleNamespace(run=run_path, handoff=handoff_path, gps=checked(manifest['gps']),
                              output=args.output / 'recorded-proof', report_data=checked(manifest['reader']),
                              evidence_root=Path(manifest['evidenceRoot']))
    started = time.perf_counter()
    with context as statistics:
        caf.record(payload)
    seconds = time.perf_counter() - started
    assert (payload.output / 'before-run.json').read_bytes() == run_before
    assert (payload.output / 'before-handoff.json').read_bytes() == handoff_before
    run, handoff = read(run_path), read(handoff_path)
    prior = json.loads(run_before)['currentGeometryValidations']
    assert run['currentGeometryValidations'] == handoff['currentGeometryValidations']
    assert run['currentGeometryValidations'][:-1] == prior
    assert len(run['currentGeometryValidations']) == len(prior) + 1
    checked(run['currentGeometryValidations'][-1])
    documents = {'run.json': canonical(run, args.output), 'handoff.json': canonical(handoff, args.output)}
    for path in sorted(payload.output.glob('*.json')):
        documents['recorded-proof/' + path.name] = canonical(read(path), args.output)
    if args.compare and documents != read(args.compare / 'measurement.json')['entireCanonicalOutput']:
        raise ValueError('Entire isolated CAF/ledger output differs from baseline')
    for ref in manifest['liveEvidence'] + manifest['assets']:
        checked(ref)
    for key in ('caf', 'reader', 'decoder', 'decodeCache', 'runBefore', 'handoffBefore', 'gps'):
        checked(manifest[key])
    result = {'status': 'PASS_COMPLETE_STOCK_CAF_AND_A4_ISOLATED', 'mode': args.mode,
              'elapsedSeconds': seconds, 'cacheStatistics': statistics,
              'entireCanonicalOutput': documents, 'entireOutputCompared': bool(args.compare),
              'normalization': 'Fixture output path and recursively verified output-local reference hashes only; same fixed clock.',
              'payloadPid': os.getpid(), 'manifest': pin(args.manifest), 'liveWrites': False, 'newCredit': 0}
    (args.output / 'measurement.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({key: result[key] for key in ('status', 'mode', 'elapsedSeconds', 'cacheStatistics', 'entireOutputCompared')}))


if __name__ == '__main__':
    main()
