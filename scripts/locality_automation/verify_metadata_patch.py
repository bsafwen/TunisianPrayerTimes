"""Verify source-reviewed identity metadata, offline search preservation and GPS stability."""
from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
import re
import sys
import unicodedata

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))
from scripts.locality_automation.install_reviewed_boundary_patch import checked, check_tree, pin, read, write
from scripts.locality_automation.packed_gps_replay import PackedGpsReplay


def normalized(value):
    text = unicodedata.normalize("NFKD", value)
    text = "".join(c for c in text if not unicodedata.category(c).startswith("M")
                   and unicodedata.category(c) != "Cf" and c != "ـ").replace("ى", "ي").lower()
    return " ".join("".join(c if unicodedata.category(c)[0] in "LN" else " " for c in text).split())


def identity(value):
    return normalized(value).replace(" ", "")


def search(row, override):
    return normalized(" ".join([row["name"], override.get("nameAr", ""), row["parentName"],
                                *row["aliases"], *row.get("contextAliases", []), *override.get("searchAliases", [])]))


def verify(args):
    if args.output.exists():
        raise ValueError("Use a fresh QA directory")
    stage, proposal = read(args.stage / "stage-report.json"), read(args.proposal)
    if stage["proposal"] != pin(args.proposal):
        raise ValueError("Stage and proposal differ")
    paths = {"beforeJson": checked(stage["beforeMetadata"]), "beforeBin": checked(stage["beforeBinary"]),
             "afterJson": checked(stage["stagedMetadata"]), "afterBin": checked(stage["stagedBinary"])}
    before, after = read(paths["beforeJson"]), read(paths["afterJson"])
    if (paths["beforeBin"].read_bytes() != paths["afterBin"].read_bytes()
            or {k: v for k, v in before.items() if k != "features"} != {k: v for k, v in after.items() if k != "features"}):
        raise ValueError("Spatial/source/index data changed")
    old, new = ({r["id"]: r for r in value["features"]} for value in (before, after))
    if set(old) != set(new) or len(old) != len(before["features"]):
        raise ValueError("Catalog identities changed or repeat")
    names_path = REPO / "android-app/app/src/main/assets/locality-display-names.json"
    overrides = {r["id"]: r for r in read(names_path)["names"]}
    changed, checks, source_reports, query_checks = {}, {}, {}, []
    for patch in proposal["patches"]:
        ident, code, values = patch["id"], patch["officialCode"], patch["proposedMetadata"]
        if set(values) != {"aliases"} or new[ident] != {**old[ident], **values}:
            raise ValueError("This verifier accepts source-reviewed alias-only changes")
        evidence_path = checked(patch["metadataEvidence"])
        evidence = read(evidence_path)
        check_tree(evidence["sourcePins"])
        rows = [r for r in evidence["rows"] if r["id"] == ident and r["officialCode"] == code]
        if len(rows) != 1:
            raise ValueError("Source identity binding is not unique")
        binding = rows[0]
        checked(binding["sourcePdf"])
        proof = binding["completeParentHierarchyProof"]
        if (binding["visualSourceIdentityVerdict"] != "PASS_METADATA_IDENTITY_ONLY"
                or not proof["otherNamesMatchExactlyAfterFormattingNormalization"]
                or sorted(identity(v) for v in proof["otherMinistryNames"]) != sorted(identity(v) for v in proof["otherINSNames"])
                or binding["unchangedOsmSourceCode"] != code):
            raise ValueError("Original map/parent identity proof is incomplete")
        for field, value in binding["currentBinding"].items():
            if old[ident].get(field) != value:
                raise ValueError("Identity evidence is not tied to the staged record")
        with checked(evidence["sourcePins"]["ministryRows"]).open(encoding="utf-8-sig", newline="") as stream:
            ministry = list(csv.DictReader(stream))
        registry = read(checked(evidence["sourcePins"]["registry"]))["sectors"]
        official = binding["ministryRows"][0]
        min_group = [r["name"] for r in ministry if identity(r["governorate"]) == identity(official["officialGovernorate"])
                     and identity(r["delegation"]) == identity(proof["datedMinistryParent"])]
        ins_group = [r["sectorAr"] for r in registry if identity(r["governorateAr"]) == identity(official["officialGovernorate"])
                     and identity(r["delegationAr"]) == identity(proof["INSParent"])]
        expected_min = [*proof["otherMinistryNames"], proof["remainingMinistryName"]]
        if sorted(identity(v) for v in min_group) != sorted(identity(v) for v in expected_min):
            raise ValueError("Declared complete Ministry parent roster is not complete")
        ins_codes = [r for r in registry if str(r["sectorCode"]) == code]
        if proof["legacyOnlyCode"]:
            if ins_codes or proof["remainingINSCode"] is not None:
                raise ValueError("Legacy-only code qualification is incorrect")
            expected_ins = proof["otherINSNames"]
        else:
            if len(ins_codes) != 1 or proof["remainingINSCode"] != code:
                raise ValueError("INS code binding is not unique")
            expected_ins = [*proof["otherINSNames"], ins_codes[0]["sectorAr"]]
        if sorted(identity(v) for v in ins_group) != sorted(identity(v) for v in expected_ins):
            raise ValueError("Declared complete INS parent roster is not complete")
        before_text, after_text = search(old[ident], overrides.get(ident, {})), search(new[ident], overrides.get(ident, {}))
        if not set(before_text.split()) <= set(after_text.split()) or not set(old[ident]["aliases"]) <= set(new[ident]["aliases"]):
            raise ValueError("Existing direct/fuzzy query tokens were discarded")
        for form in binding["candidateAliasesAccepted"]:
            terms = normalized(form).split()
            before_match, after_match = all(t in before_text for t in terms), all(t in after_text for t in terms)
            if not after_match or form not in new[ident]["aliases"]:
                raise ValueError("Reviewed official form is not directly searchable")
            query_checks.append({"code": code, "id": ident, "query": form, "directBefore": before_match, "directAfter": after_match})
        changed[ident] = values
        checks[code] = {"id": ident, "valid": True, "equalProposedMetadata": True,
                        "legacyCodeQualified": proof["legacyOnlyCode"], "geometryCertified": False}
        source_reports[code] = {"evidence": pin(evidence_path), "sourcePdf": binding["sourcePdf"]}
    for ident, row in old.items():
        if ident not in changed and new[ident] != row:
            raise ValueError("Unrelated metadata changed")
    sectors = [r for r in after["features"] if r["kind"] == "sector"]
    if len(sectors) != 2085 or len({r["id"] for r in sectors}) != 2085:
        raise ValueError("Offline sector selection structure changed")
    for row in sectors:
        effective = overrides.get(row["id"], {}).get("nameAr") or row["name"]
        if not re.search(r"[\u0600-\u06ff]", effective) or not any(re.search(r"[A-Za-z]", f) for f in [row["name"], *row["aliases"]]):
            raise ValueError("Arabic/French name availability is incomplete")
    before_replay, after_replay = (PackedGpsReplay(paths[a], paths[b]) for a, b in (("beforeJson", "beforeBin"), ("afterJson", "afterBin")))
    probes = []
    for ident in changed:
        row = old[ident]
        geometry = before_replay.geometry(ident)
        x1, y1, x2, y2 = row["bbox"]
        point = geometry.representative_point()
        for lat, lon in [(row["lat"], row["lng"]), (point.y, point.x), (y1, x1), (y1, x2), (y2, x1), (y2, x2), ((y1+y2)/2, (x1+x2)/2)]:
            results = {}
            for accuracy in (None, 5, 20, 50):
                a, b = before_replay.find(lat, lon, accuracy), after_replay.find(lat, lon, accuracy)
                if a != b:
                    raise ValueError("GPS winner/candidate/uncertainty behavior changed")
                results[str(accuracy)] = a
            probes.append({"id": ident, "lat": lat, "lng": lon, "results": results})
    args.output.mkdir(parents=True)
    write(args.output / "query-checks.json", {"checks": query_checks})
    write(args.output / "probes.json", {"probes": probes})
    report = {"status": "PASS_OFFLINE_STAGED_METADATA_GPS", "structuralFailures": [], "probeFailures": [],
              "inputs": {"proposal": pin(args.proposal), **{k: pin(v) for k, v in paths.items()},
                         "names": pin(names_path), "sourceIdentity": source_reports, "verifier": pin(Path(__file__)),
                         "queryChecks": pin(args.output / "query-checks.json"), "probes": pin(args.output / "probes.json")},
              "decodedPatchChecks": {}, "metadataPatchChecks": checks, "probeCount": len(probes),
              "lookupComparisons": len(probes)*4, "totals": {"source_supported_wrong_to_correct": 0},
              "officialAliasForms": len(query_checks), "newDirectQueryMatches": sum(not r["directBefore"] for r in query_checks),
              "offlineSelectableSectors": 2085, "allGeometryBytesIdentical": True,
              "limits": ["Alias identity/search verification only; no geographic boundary certification.",
                         "Galite keeps its existing legacy code without asserting modern INS code authority."]}
    write(args.output / "report.json", report)
    print(json.dumps({"status": report["status"], "aliases": len(query_checks), "probeCount": len(probes),
                      "lookupComparisons": len(probes)*4, "newDirectQueryMatches": report["newDirectQueryMatches"]}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("stage", "proposal", "output"):
        parser.add_argument("--"+name, type=Path, required=True)
    verify(parser.parse_args())
