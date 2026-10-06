"""Combine an exact finite set of unaccepted whole-source hypotheses unchanged.

This performs no geometry selection, repair, source acceptance or installation.
It permits a shared original staging pass to inspect incident dependencies.
"""
import argparse
from datetime import datetime, timezone
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.locality_automation.run_sealed_boundary_queue import active_control, checked, pin, read
from scripts.locality_automation.audit_prepared_osm_graphs import write_new
from scripts.locality_automation.install_reviewed_boundary_patch import check_tree
from scripts.locality_automation.work_window_guard import parse_utc


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    spec = read(args.manifest)
    ctl = active_control(spec)
    check_tree(spec)
    if args.output.exists() or spec.get("credit") != 0 or datetime.now(timezone.utc) >= parse_utc(ctl["safeSourceQaStartUtc"]):
        raise ValueError("Fresh root zero-credit joint staging preparation required")
    patches, codes, ids = [], set(), set()
    for reference in spec["proposals"]:
        original = read(checked(reference))
        check_tree(original)
        if (original["status"] != "UNACCEPTED_ROOT_ISOLATED_HYPOTHESIS_NO_INSTALLATION"
                or original["credit"] != 0 or len(original["patches"]) != 1
                or any(original[key][field] != spec[key][field]
                    for key in ("baseCatalogMetadata", "baseCatalogBinary") for field in ("file", "sha256"))):
            raise ValueError("Original individual hypothesis or exact live baseline differs")
        patch = original["patches"][0]
        code, ident = patch["officialCode"], patch["id"]
        if code in codes or ident in ids or code not in ctl["approvedReconciliationPool"]:
            raise ValueError("Duplicate identity or outside exact finite approved pool")
        codes.add(code)
        ids.add(ident)
        patches.append(patch)
    if len(patches) < 2 or codes != set(spec["codes"]):
        raise ValueError("Exact complete declared joint scope required")
    result = {"status": "UNACCEPTED_ROOT_JOINT_SOURCE_HYPOTHESIS_NO_INSTALLATION",
        "credit": 0, "baseCatalogMetadata": spec["baseCatalogMetadata"], "baseCatalogBinary": spec["baseCatalogBinary"],
        "sourceIdPrefix": "UNACCEPTED-ISIE-SHADOW", "reviewedDate": ctl["windowStartUtc"][:10],
        "manifest": pin(args.manifest), "originalIndividualProposals": spec["proposals"], "patches": patches,
        "geometryModifiedWhileCombining": False, "independentQaPassed": False, "sourceScopeAccepted": False,
        "qualification": "Joint topology diagnosis only. Every original whole-source patch is retained verbatim. No behavior validation, legal/neighbor ownership resolution, source acceptance or installation."}
    ref = write_new(args.output, result)
    print({"proposal": ref, "codes": sorted(codes), "credit": 0})


if __name__ == "__main__":
    main()
