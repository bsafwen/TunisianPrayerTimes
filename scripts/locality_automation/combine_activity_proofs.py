"""Join contiguous original activity append proofs for whole-window verification."""
import argparse
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts.locality_automation.run_sealed_boundary_queue import checked, pin, read


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    refs = read(args.manifest)['activityProofs']
    if len(refs) != 2:
        raise ValueError('Exactly the two original append proofs required')
    originals = [read(checked(ref)) for ref in refs]
    for proof in originals:
        if (proof['status'] != 'APPENDED_ORIGINAL_TASK_ACTIVITY_NO_REFRESH'
                or proof['newGeographicCredit'] != 0 or proof['ledgerOrAssetChanges'] is not False
                or proof['originalPublicationLockUsed'] is not True):
            raise ValueError('Original stock activity proof differs')
    if originals[0]['after'] != originals[1]['before']:
        raise ValueError('Activity append proof lineage is not contiguous')
    phases = [phase for proof in originals for phase in proof['phases']]
    if len({phase['taskId'] for phase in phases}) != len(phases):
        raise ValueError('A real task occurs in both activity proofs')
    result = {'status': 'APPENDED_ORIGINAL_TASK_ACTIVITY_NO_REFRESH',
              'qualification': 'Read-only combination of two original stock append proofs; no new append or fabricated clock.',
              'combinedProof': True, 'originalActivityProofs': refs,
              'before': originals[0]['before'], 'after': originals[-1]['after'], 'phases': phases,
              'originalLeafPids': [proof['actualLeafPid'] for proof in originals],
              'newGeographicCredit': 0, 'ledgerOrAssetChanges': False, 'sharedClocksNotLabor': True}
    for ref in refs: checked(ref)
    with args.output.open('x', encoding='utf-8') as stream:
        stream.write(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'status': 'COMBINED_ORIGINAL_CONTIGUOUS_ACTIVITY_PROOFS', 'tasks': len(phases)}))


if __name__ == '__main__':
    main()
