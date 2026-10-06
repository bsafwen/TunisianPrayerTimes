"""Preserve native line gates with an exact directly approved backtrack exception."""
from shapely import from_wkb, from_wkt
from shapely.geometry import LineString
from shapely.ops import unary_union
from scripts.locality_automation.run_sealed_boundary_queue import read, checked
from scripts.locality_automation.native_administrative_policy_v1 import require_native_administration as original_gate


def require_native_administration(code, row, control):
    if row.get('constituentNativeFacts'):
        return {'constituentPolicies': [
            require_native_administration(code, part, control)
            for part in row['constituentNativeFacts']]}
    lengths = row['nativeInteriorAdminLengthsPagePoints']
    key = code + ':' + row['sourcePdf']['sha256']
    ref = control.get('humanNativeBacktrackExceptions', {}).get(key)
    if ref is None or not any(lengths.values()):
        return original_gate(code, row, control)
    proof = read(checked(ref))
    auth = proof['authorization']
    # This explicit human approval is limited to this exact duplicated source
    # corner. Neither a general tolerance nor a later source can inherit it.
    if (proof['status'] != 'EXPLICIT_DIRECT_USER_APPROVED_EXACT_NATIVE_BACKTRACK'
            or code != '155851'
            or row['sourcePdf']['sha256'] != '32d05526bb61fc2c0ba73cbf6f38930991e585b7d30f0fe61c7d431b7d04d77a'
            or proof['officialCode'] != code
            or proof['sourcePdf'] != row['sourcePdf']
            or auth['sourceRole'] != 'direct-user-instruction'
            or not auth['userStatement'].strip() or not auth['questionContext'].strip()
            or proof['maximumBlueInteriorLengthPagePoints'] != .581
            or proof['measuredBlueInteriorLengthPagePoints'] != 0.580231293436384
            or proof['sourceInsetPagePoints'] != .5
            or not proof['blackInteriorMustStayZero'] or lengths['black'] != 0
            or not proof['wholeOriginalBoundaryUnchanged']
            or not proof['registrationDefaultUnchanged'] or proof['credit'] != 0):
        raise ValueError('Exact directly approved native backtrack differs')
    checked(proof['review']); checked(proof['sourcePdf'])
    native = from_wkb(checked(row['nativePageGeometry']).read_bytes())
    if not native.equals_exact(from_wkb(checked(proof['nativePageGeometry']).read_bytes()), 0):
        raise ValueError('Approved whole original native boundary changed')
    inventory = read(checked(row['sourceInventory']))['pages'][0]
    blue = [LineString(run['nativePagePoints']) for path in inventory['nativePaths']
            if path['strokeFamily'] == 'blue' and path['visibleStroke'] and path['relevantLineworkHint']
            for run in path['runs'] if len(run['nativePagePoints']) > 1]
    intrusion = unary_union(blue).intersection(native.buffer(-.5))
    if (not intrusion.equals_exact(from_wkt(proof['exactBlueIntrusionWkt']), 0)
            or intrusion.length != lengths['blue']
            or intrusion.length != proof['measuredBlueInteriorLengthPagePoints']
            or intrusion.length > proof['maximumBlueInteriorLengthPagePoints']):
        raise ValueError('Additional or altered native blue intrusion')
    return ref
