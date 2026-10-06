"""Typed entry points to existing audited helpers; no generated code execution."""
from pathlib import Path
from .common import pin, read_pin, pins_in, verify, read


def helper_job(config, name, config_file, ident, *, outputs=None, deps=()):
    h = config["helpers"][name]
    verify(h)
    cp = pin(config_file)
    data = read_pin(cp)
    inputs = [cp, {k: h[k] for k in ("file", "sha256")}, *pins_in(data)]
    if name == "extract":
        for key in ("sourceScript", "sourceAreas", "manifest"):
            inputs.append({"file": data[key], "sha256": data[key + "Sha256"]})
        for entry in read(data["manifest"]):
            inputs.append({"file": str(Path(data["directory"]) / entry["file"]), "sha256": entry["sha256"]})
    # Acquisition templates refer to fixed scripts/source areas in scalar fields.
    if name == "acquire":
        template = read_pin(data["extractionTemplate"])
        for key in ("sourceScript", "sourceAreas"):
            inputs.append({"file": template[key], "sha256": template[key + "Sha256"]})
    unique = {}
    for item in inputs:
        verify(item)
        key = str(Path(item["file"]).resolve())
        if key in unique and unique[key]["sha256"] != item["sha256"]:
            raise ValueError("Conflicting helper input fingerprint")
        unique[key] = {"file": key, "sha256": item["sha256"]}
    if outputs is None:
        if name == "acquire":
            outputs = [str(Path(data["directory"]) / p) for p in ("manifest.json", "extraction-config.json")]
        elif name == "extract":
            outputs = [data["output"], str(Path(data["directory"]) / "extraction-summary.json")]
        elif name == "append":
            outputs = [str(Path(data["outputDirectory"]) / "candidates.geojson")]
        elif name == "stage":
            outputs = [str(Path(data["outputDirectory"]) / p) for p in ("staging-report.json", "neighborhoods.json", "neighborhoods.bin")]
        elif "output" in data:
            outputs = [data["output"]]
        else:
            raise ValueError(f"Explicit outputs required for {name}")
    args = [config["python"], "-B", "-X", "utf8", h["file"]]
    args += [str(config_file)] if name == "extract" else ["--config", str(config_file)]
    return {"id": ident, "kind": h["kind"], "argv": args, "cwd": config["repo"],
            "inputs": list(unique.values()), "outputs": outputs, "deps": list(deps),
            "resources": [], "governorate": config["governorate"]}


def plan(config, jobs):
    return {"schemaVersion": 1, "governorate": config["governorate"], "jobs": jobs}
