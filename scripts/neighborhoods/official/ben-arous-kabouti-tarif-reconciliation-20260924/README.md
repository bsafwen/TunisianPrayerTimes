# Kabouti / Jebel Tarif seam clip

**Status: installed as a provisional seam correction.** The clip removes the broad recorded overlap from الكبوطي while retaining the small patch inside the pending ISIE Kabouti ring plus one app-grid unit. The retained patch remains unresolved, so the app suppresses both names there through its existing conflict rule.

The source is the native red ring on drawing 369 of the pinned Kabouti GeoPDF: 740 continuous segments, 741 coordinates, no raster tracing or geometry repair, with 0.407 m maximum GeoPDF control residual. The paired 2023 جبل طريف map supports placing the broad disputed area on the Tarif side of the shared linework. The Kabouti ring is used only as a local preservation mask; it is not installed as the full boundary, and the map fit does not establish current ground or legal accuracy.

The package pins the live catalog after the Oued Ezzit/Sidi Salem integration. It contains 3,474 features, 2,572 polygon features, 485 conflicts and 44 source records. The compressed baseline ensures a rebuild uses that exact current chain instead of an older catalog snapshot.

To regenerate the pinned source ring, then stage and verify without installing:

```powershell
& 'C:\path\to\work\geo\venv\Scripts\python.exe' scripts/neighborhoods/official/ben-arous-kabouti-tarif-reconciliation-20260924/build_preservation_source.py
python scripts/neighborhoods/official/ben-arous-kabouti-tarif-reconciliation-20260924/apply_current_live_clip.py --output-dir C:\path\outside\the\repository\kabouti-stage
```

Append `--install` only after reviewing the generated checks. The current installation receipt is `current-live-installation-receipt.json`. It records the source, builder, baseline and output hashes, exact row delta, GPS winners and conflict suppression result.
