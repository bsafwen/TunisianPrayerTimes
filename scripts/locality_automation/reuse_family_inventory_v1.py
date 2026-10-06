"""Reuse a source inventory only after verifying its exact PDF binding."""
from scripts.locality_automation.run_sealed_boundary_queue import read, checked


def checked_inventory(ref, pdf_ref):
    checked(pdf_ref)
    inventory = read(checked(ref))
    original = inventory['sourcePdf']
    checked(original)
    if original['sha256'] != pdf_ref['sha256']:
        raise ValueError('Saved inventory belongs to a different original PDF')
    return ref


def source_inventory(availability, prior_cases):
    pdf = availability['selectedPdf']
    if availability.get('sourceInventory'):
        return checked_inventory(availability['sourceInventory'], pdf)
    candidates = [case for case in prior_cases
                  if case['sourcePdf']['sha256'] == pdf['sha256']]
    if not candidates:
        return None
    # The same byte-identical PDF can have several successive inventories.
    # Use the first fully verified prior binding; never accept a mismatched one.
    case = candidates[0]
    checked(case['sourcePdf'])
    return checked_inventory(case['sourceInventory'], pdf)
