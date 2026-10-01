"""Bind an immutable proposal to a later catalog only if its local inputs stayed exact."""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def pin(path):
    return {"file": str(path.resolve()), "sha256": sha(path)}


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n",
                    encoding="utf-8", newline="\n")


def rebase(args):
    if args.output.exists():
        raise ValueError("Use a fresh successor directory")
    proposal = read(args.proposal)
    for path, field in ((args.old_json, "baseCatalogMetadata"), (args.old_bin, "baseCatalogBinary")):
        if sha(path) != proposal[field]["sha256"]:
            raise ValueError("Historical baseline does not match the sealed proposal")
    old, live = read(args.old_json), read(args.live_json)
    old_blob, live_blob = args.old_bin.read_bytes(), args.live_bin.read_bytes()
    for field in ("schemaVersion", "coordinateScale", "gridSize", "source"):
        if old[field] != live[field]:
            raise ValueError(f"Catalog format/source changed: {field}")
    if old_blob[:8] != live_blob[:8]:
        raise ValueError("Packed format changed")
    old_rows = {row["id"]: row for row in old["features"]}
    live_rows = {row["id"]: row for row in live["features"]}
    if set(old_rows) != set(live_rows):
        raise ValueError("Catalog identities changed")
    checked = []
    for patch in proposal["patches"]:
        a, b = old_rows[patch["id"]], live_rows[patch["id"]]
        if {k: v for k, v in a.items() if k != "offset"} != {k: v for k, v in b.items() if k != "offset"}:
            raise ValueError(f"Proposed locality record changed: {patch['id']}")
        if old_blob[a["offset"]:a["offset"]+a["length"]] != live_blob[b["offset"]:b["offset"]+b["length"]]:
            raise ValueError(f"Proposed locality geometry changed: {patch['id']}")
        if old["sources"][a["sourceId"]] != live["sources"][b["sourceId"]]:
            raise ValueError(f"Proposed locality source changed: {patch['id']}")
        checked.append(patch["id"])
    a, b = old["country"], live["country"]
    if ({k: v for k, v in a.items() if k != "offset"} != {k: v for k, v in b.items() if k != "offset"}
            or old_blob[a["offset"]:] != live_blob[b["offset"]:]):
        raise ValueError("Country geometry or metadata changed")
    args.output.mkdir(parents=True)
    # The successor uses immutable snapshots, so later installs cannot invalidate its inputs.
    json_copy, bin_copy = args.output / "baseline.json", args.output / "baseline.bin"
    json_copy.write_bytes(args.live_json.read_bytes())
    bin_copy.write_bytes(live_blob)
    report = {"status": "PASS_DISJOINT_CATALOG_SUCCESSOR", "originalProposal": pin(args.proposal),
              "oldMetadata": pin(args.old_json), "oldBinary": pin(args.old_bin),
              "newMetadata": pin(json_copy), "newBinary": pin(bin_copy),
              "exactLocalityIds": checked, "onlyOffsetsMayDiffer": True,
              "countryUnchanged": True, "sourceGeometryAndMetadataProposalUnchanged": True}
    write(args.output / "rebase-report.json", report)
    successor = copy.deepcopy(proposal)
    successor["baseCatalogMetadata"], successor["baseCatalogBinary"] = pin(json_copy), pin(bin_copy)
    successor["rebasedFrom"] = {"proposal": pin(args.proposal), "verification": pin(args.output / "rebase-report.json")}
    write(args.output / "proposal.json", successor)
    print(json.dumps({"proposal": pin(args.output / "proposal.json"), "exactLocalityCount": len(checked)}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("proposal", "old-json", "old-bin", "live-json", "live-bin", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    rebase(parser.parse_args())
