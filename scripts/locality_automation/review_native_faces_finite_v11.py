"""Review unique pending body cases only, with the unchanged native reviewer."""
import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import read, active_control
from scripts.locality_automation.review_native_faces_finite_v10 import review


def validate_cases(spec):
    control = active_control(spec)
    pool = control['approvedCycle' + str(control['iteration']) + 'AcceptancePool']
    codes = [case['officialCode'] for case in spec['cases']]
    if spec['exactTargets'] != pool or not codes or not set(codes) <= set(pool):
        raise ValueError('Native review cases must be inside the current finite pending pool')
    if len(codes) != len(set(codes)):
        raise ValueError('Repeated body codes require the declared constituent-union reviewer')
    if any(case.get('supportingAlreadyAcceptedNeighbor') for case in spec['cases']):
        raise ValueError('Accepted neighbor context must not be extracted as a pending target')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    spec = read(args.manifest)
    validate_cases(spec)
    review(spec, args.output)


if __name__ == '__main__':
    main()
