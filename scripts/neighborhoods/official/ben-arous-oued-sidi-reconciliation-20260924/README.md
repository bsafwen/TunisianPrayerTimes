# Oued Ezzit / Sidi Salem El Garsi live-catalog clip

**Status: installed as a provisional, narrow best-effort correction.** The Sidi Salem polygon is clipped only where it overlaps the official Oued Ezzit candidate outside the pinned Sidi Salem source ring. This removes `0.468824 km²`; a `0.006607 km²` source-supported junction mask is retained. The remaining pair overlap is `0.006602 km²`, where the app continues to suppress both labels through its existing conflict handling.

The clip uses paired ISIE 2023 locality maps. Their black governorate linework agrees within 9.1 m, and the GeoPDF control residuals are 0.17 m for Oued Ezzit and 0.22 m for Sidi Salem. The extracted Sidi Salem ring is still pending review as a complete boundary. It serves only as a preservation mask here, so this does not certify the entire Sidi Salem boundary or the remaining junction.

The pinned input snapshot is the live catalog immediately before this installation: 3,474 selectable features, 2,572 bounded features, 485 conflicts and 43 source records. The compressed inputs beside the builder preserve that exact catalog, including accepted exclusions, Tunis boundary replacements, Borj-Cedria clips and the Hammam Lif spelling correction. The earlier frozen builder omitted these newer accepted edits.

To stage and verify the same result without changing app assets, run:

```powershell
python scripts/neighborhoods/official/ben-arous-oued-sidi-reconciliation-20260924/apply_current_live_clip.py --output-dir C:\path\outside\the\repository\oued-sidi-stage
```

Add `--install` to install after the pinned source, exact-delta, geometry, GPS and conflict checks pass. The installed JSON and BIN hashes, input pins and checks are recorded in `current-live-installation-receipt.json`. The script changes only those two app assets; other polygon payloads, point rows, the country geometry and existing source records are verified as preserved.
