# Abdelbaki HAMDOUNI exact-ID picker exclusion

The pinned residential-extra review classifies `osm:way:744642242` as a clear residential complex/building rather than a locality: its sole OSM identity is a person's full name with `landuse=residential`, without place or boundary identity. The change applies only to that stable ID.

The feature record is removed from shipped `neighborhoods.json`, which makes it unavailable to the static picker and GPS locality-label lookup. Its ID is added to both `neighborhoods.json.retiredLocalityIds` and `retired-localities.json`, and to the compiler's `final-exclusions.json` input. The original packed `neighborhoods.bin` is preserved byte-for-byte; the removed shape remains available in the pre-change backup and source review evidence. No display-name data or other feature rows changed.

Impact checks confirmed a one-entry picker reduction (3,375 to 3,374) and one feature-row reduction (3,474 to 3,473). The target representative and its entire polygon lie within sector `osm:relation:7169639` (سيدي بوزيد الغربية, delegation 628). A stale saved selection for the retired ID resolves to no locality ID/name/kind while retaining its delegation ID and GPS-origin flag; the delegation timetable source is unchanged.

Run `python install.py --stage-only` to reproduce and inspect candidate assets from the pinned before-state. Run `python install.py --install` to apply the same verified atomic update. The installer stops if source review, app asset, or saved-selection behavior pins drift.

The receipt and byte-for-byte backups are alongside this file. The source review SHA-256 is `f61b2b0ba1dfb7a0f1d556f62a070d7fdb9a3ceeedff3b143d42fa6b6bafffc2`.
