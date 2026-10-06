# Sousse Ain Rahma main-component clip — 2026-09-25

Provisional one-sided update retaining only the current Ain Rahma component that contains its existing representative, after subtracting a 0.1 m buffer of reviewed Selloum in EPSG:32632. The old Ain representative coordinate is preserved. The two detached slivers that have no overlap with the reviewed official Ain ring are removed.

Run `python install.py --stage-only` to recompile a review candidate against the exact pinned live assets and save `candidate-neighborhoods.json`, `candidate-neighborhoods.bin`, and a validation report beside the script. Run `python install.py --install` to repeat every precondition and install only after all checks pass. The installer writes a byte-for-byte backup and a receipt. It stops if live inputs or review evidence differ from their pins.

This candidate is provisional. Its derived source record sets `boundaryAccuracyVerified:false`; it does not replace Ain Rahma with the whole official ring. Evidence files are referenced by task-workspace path and SHA-256 in the source record and receipt.
