"""Zero interior admin lines, except an explicitly approved exact original endpoint."""
from shapely import from_wkb, from_wkt
from shapely.geometry import LineString
from shapely.ops import unary_union
from scripts.locality_automation.run_sealed_boundary_queue import read, checked


def require_native_administration(code, row, control):
    lengths = row['nativeInteriorAdminLengthsPagePoints']
    ref = control.get('humanNativeAdministrativeExceptions', {}).get(code)
    if ref is None:
        if any(lengths.values()):
            raise ValueError('Unwaived native administrative interior line: ' + code)
        return None
    proof = read(checked(ref))
    if (proof['status'] != 'EXPLICIT_DIRECT_USER_APPROVED_EXACT_NATIVE_ENDPOINT'
            or proof['officialCode'] != code
            or proof['authorization']['sourceRole'] != 'direct-user-instruction'
            or not proof['authorization']['userStatement'].strip()
            or proof['sourcePdf']['sha256'] != row['sourcePdf']['sha256']
            or checked(proof['sourcePdf']) != checked(row['sourcePdf'])
            or not proof['blackInteriorMustStayZero'] or lengths['black'] != 0
            or proof['maximumBlueInteriorLengthPagePoints'] != .01
            or proof['sourceInsetPagePoints'] != .5
            or not proof['registrationDefaultUnchanged'] or not proof['wholeOriginalBoundaryUnchanged']
            or proof['credit'] != 0):
        raise ValueError('Exact code/PDF native endpoint approval differs')
    checked(proof['review']); checked(proof['proposal'])
    native = from_wkb(checked(row['nativePageGeometry']).read_bytes())
    approved = from_wkb(checked(proof['nativePageGeometry']).read_bytes())
    if not native.equals_exact(approved, 0):
        raise ValueError('Original approved whole boundary changed')
    inventory = read(checked(row['sourceInventory']))['pages'][0]
    blue = [LineString(run['nativePagePoints'])
            for path in inventory['nativePaths']
            if path['strokeFamily'] == 'blue' and path['visibleStroke'] and path['relevantLineworkHint']
            for run in path['runs'] if len(run['nativePagePoints']) > 1]
    intrusion = unary_union(blue).intersection(native.buffer(-.5))
    expected = from_wkt(proof['exactBlueIntrusionWkt'])
    if (not intrusion.equals_exact(expected, 0)
            or intrusion.length != lengths['blue']
            or intrusion.length != proof['measuredBlueInteriorLengthPagePoints']
            or intrusion.length > proof['maximumBlueInteriorLengthPagePoints']):
        raise ValueError('Unapproved additional or different blue intrusion')
    return ref
