# Dokhania residential-to-sector picker merge

This dated change assigns only `osm:way:184043911`'s `pickerGroupId` to official sector `osm:relation:7126320`. The residential polygon is fully contained by that sector, and both share delegation 421. The sector keeps its stable identity as the canonical picker row; geometry, coordinates, names, aliases, delegation, prayer/timetable identity, offsets, and packed geometry remain unchanged.

No `locality-display-names.json` alias is added. The merged picker row becomes searchable as `الدخانية` because `groupPickerLocalities` concatenates search text from the residential member into the canonical sector row. The separately named, point-only hamlet `osm:node:7867290340` (`دوار الدخانية`) remains in its own picker group. A search for `الدخانية` can therefore legitimately return the official sector and that distinct named hamlet.

`install.py` pins the current catalog JSON, packed geometry BIN, and display-name JSON hashes; checks exact feature identities/order, containment bbox, same governorate/delegation, duplicate-name candidates, and the expected picker/search result; then changes the one catalog field. The BIN and display-name asset are read-only preconditions and must remain byte-identical. The byte-for-byte starting `neighborhoods.json` is saved as `before-neighborhoods.json`; `installed-receipt.json` records all before/after hashes and the picker/search proof.

Run `python install.py` from this directory. It stops if any pinned input or expected feature identity changes.
