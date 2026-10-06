# Nabeul Hammam El Ghezaz parent-label correction — 2026-09-25

This package proposes four parent label changes from `معتمدية حمام الغزاز` to `معتمدية حمام الأغزاز` for `osm:relation:7097006`, `7096631`, `7096632`, and `7097007`. The dated Ministry locality/delegation inventory and the INS registry both identify the relevant locality records under delegation code `1558` / حمام الأغزاز; the compact identity triage provides unique `ins_code` crosswalks for these app rows. This is a text-only parent label correction. It does not validate or change any boundary.

The package is pinned to the live catalog JSON and BIN hashes in `source-pins.json`. `backups/` contains byte-exact copies of both pre-install assets. The script refuses to run if the current assets differ from those pins or if any target field differs from its precondition.

To reproduce the candidate and validation without installation:

```powershell
python build_and_install.py
```

To install after reviewing the staged candidate (this task was authorized for the exact four-field correction):

```powershell
python build_and_install.py --install
```

The script verifies the semantic diff is exactly four `features[i].parentName` paths. It preserves names, aliases/context aliases, IDs, source IDs, picker groups, delegation/meteo IDs, coordinates, all geometry-derived fields, cell/conflict/source metadata, and the BIN bytes. See `candidate/validation.json` before install and `receipt.json` after install.

Known retained mismatch: `osm:relation:7096632` currently has `delegationId:466` even though its code-linked INS delegation is 1558; the requested parentName-only scope intentionally preserves this numeric field for separate review. `contextAliases` also retain their existing spelling because aliases were explicitly out of scope.
