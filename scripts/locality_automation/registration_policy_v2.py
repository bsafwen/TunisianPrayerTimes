"""Read exact-code/PDF direct-user registration approvals; preserve v1 proofs."""
import math
from scripts.locality_automation.run_sealed_boundary_queue import read, checked
from scripts.locality_automation.registration_policy_v1 import registration_limits as legacy_limits


def registration_limits(code, pdf, control):
    default = {'fitResidualM': .5, 'looMaxM': 1.5, 'humanException': None}
    ref = control.get('humanRegistrationExceptions', {}).get(code)
    if ref is None:
        return default
    proof = read(checked(ref))
    if proof.get('status') != 'EXPLICIT_DIRECT_USER_APPROVED_CODE_PDF_REGISTRATION_LIMITS':
        return legacy_limits(code, pdf, control)
    auth = proof['authorization']
    entries = proof['entries']
    codes = [row['officialCode'] for row in entries]
    if (auth['sourceRole'] != 'direct-user-instruction'
            or auth['action'] != 'entry-only-held-out-registration-limit'
            or not auth['userStatement'].strip() or not auth['questionContext'].strip()
            or len(codes) != len(set(codes)) or sorted(codes) != sorted(auth['officialCodes'])
            or not proof['exactOriginalBoundaryUnchanged'] or not proof['allOtherGatesUnchanged']
            or proof['credit'] != 0):
        raise ValueError('Exact scoped direct-user registration approval required')
    checked(proof['review'])
    for row in entries:
        # This successor changes only held-out registration, never fitting,
        # native geometry, administrative line admission, or global defaults.
        if (row['fitResidualLimitM'] != .5 or row['looLimitM'] != auth['looLimitM']
                or not math.isfinite(row['looLimitM']) or row['looLimitM'] < 1.5):
            raise ValueError('Approval must preserve fitting and exact authorized held-out limit')
    matches = [row for row in entries if row['officialCode'] == code and row['sourcePdf']['sha256'] == pdf['sha256']]
    if not matches:
        return default
    if len(matches) != 1 or checked(matches[0]['sourcePdf']) != checked(pdf):
        raise ValueError('Exception must bind one exact original PDF')
    return {'fitResidualM': .5, 'looMaxM': matches[0]['looLimitM'], 'humanException': ref}


def require_registration(code, pdf, registration, control):
    limits = registration_limits(code, pdf, control)
    if registration['fitResidualM'] > limits['fitResidualM'] or registration['looMaxM'] > limits['looMaxM']:
        raise ValueError('Unwaived original registration held: ' + code)
    return limits
