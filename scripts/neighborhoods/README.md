# Offline neighborhood labels and independent prayer locations

When GPS is requested, `DelegationLocator` uses one device coordinate pair for two
independent lookups:

1. `NeighborhoodIndex` finds containing named areas, excludes candidates whose
   peer boundaries conflict at that point, and returns the smallest remaining
   area for display. It uses only bundled polygon metadata and packed geometry.
2. `GouvernoratRepository.findNearestDelegation` chooses the nearest available
   meteo.tn location by Haversine distance from the **actual GPS coordinates**.
   Administrative boundaries and the display name do not constrain this choice.

The selection is saved atomically. A background move can change the neighborhood
without changing the prayer source; the UI observes preferences and refreshes the
name in that case. If the prayer source changes while the screen is open, the
displayed prayer times also refresh from the saved selection. This observation
does not write preferences or schedule duplicate alarms. No coordinate history is
saved. Selecting a place manually uses
that place's representative point to choose the nearest available timetable.

The picker retains governorate sections, search aliases and the selected-place
highlight. Search metadata is prepared off the UI thread while the main screen
is visible; the original delegation rows are immediately available. Browsing
never loads the 5.48 MB polygon binary or recalculates the nearest timetable for
every row. Available rows are cached against the current source IDs/coordinates,
and exact nearest-source mapping runs once for the selected row.

## Sources and reproducible build

Source: OpenStreetMap contributors, distributed by Geofabrik, snapshot
2026-09-05T20:22:06Z. Download:

https://download.geofabrik.de/africa/tunisia-260905.osm.pbf

SHA-256: `7edc8fa6fc00635c4507ab3718552c015533210b46c4d95099b43a76f21939fd`

The default build also includes 21 original municipal polygons from the Commune
de Bouarada / OpenBaladiati: 20 neighborhoods and one industrial locality. Their
Arabic and French names, stable municipal IDs, and original boundaries come from
the [municipal dataset catalog](https://catalog.data.gov.tn/fr/dataset/quartiers-dans-la-zone-municipale-de-bouarada).
The original download is no longer available, so its
[June 18, 2018 archived copy](https://web.archive.org/web/20180618182551id_/http://openbaladiati.tn:80/dataset/a444cad1-9911-40b0-896c-ee070794132e/resource/0b6b7ff9-e6b2-4448-a9ff-7de6839a419a/download/repartition_quarties.geojson)
is preserved byte-for-byte as `sources/bouarada-20180618.geojson`.
SHA-256: `861f15e4dfd4ce83e6f670ed29560329b7ebee2ed6b09c5793cc2bac98c3e886`.
Its age is retained in source metadata; inclusion is not certification of present
municipal boundaries. `municipal-sources.json` records provenance, checksums,
counts, licenses and the industrial area's generic locality classification.

```
python -m pip install -r scripts/neighborhoods/requirements.txt
python -m unittest discover -s scripts/neighborhoods -p 'test_*.py'
python scripts/generate_neighborhoods.py /path/to/tunisia-260905.osm.pbf
```

The generator makes no network requests. The output files are
`android-app/app/src/main/assets/neighborhoods.json` and `neighborhoods.bin`.
`coverage.json` records sources, counts, excluded features, point-only places, and
conflicting polygon pairs with sample coordinates inside their intersections.
Each feature has a `sourceId`; the metadata's `sources` registry retains its
provenance independently of the existing OSM `source` field. The compiler rejects
changed source bytes, duplicate IDs, missing names, unexpected feature counts,
non-WGS84 coordinates and invalid municipal polygons. It does not silently repair
municipal borders or replace missing records with inferred geometry.
Rerun it when updating the source extract or meteo.tn coordinate catalog.

Changes to the source: reconstruct OSM ways/relations as polygons, repair invalid
geometry when possible, clip to Tunisia, quantize vertices to 0.000001 degrees
(about 0.1 m), derive representative points, merge matching multilingual place
aliases, calculate nearest prayer-source IDs for manual defaults, and build a
0.1-degree bounding-box grid. No buffers, Voronoi cells, bounding-box substitutes
or Google-derived geometry are used. The grid narrows candidate polygons; the
actual polygon, including its holes and all disconnected parts, decides containment.
Area estimates in square kilometers rank unambiguous containing polygons; stable
feature IDs break ties. Named OSM `place=locality` areas and named administrative
areas with an omitted administrative level are included as generic localities.
Farmyards, farmland, buildings, fitness stations and squares are not introduced
as neighborhoods. Current `loc_name` and `official_name` variants enrich search;
historical `old_name` values never become current locality labels.

## Boundary conflicts

The compiler retains source geometry and explicitly records ambiguous pairs:

- Any positive-area overlap between two sectors is conflicting: imadas are peers,
  including cases where one erroneous sector is wholly nested inside another.
- Fine area kinds (neighborhood, quarter, suburb, city district, residential,
  subdistrict, locality) conflict when their polygons cross rather than nest.
- Two broad places of the same kind (for example village/village) use the same
  crossing rule. A broad town/village/hamlet versus a fine area, or an imada versus
  a neighborhood, is a normal different hierarchy.
- Shared boundary lines have no positive area and are not conflict regions.

Overlap calculations and audit sample coordinates use floating precision on the
already quantized stored vertices. This preserves narrow real intersections and
avoids rounding an interior sample back onto a border. Sample points are checked
for strict containment in both original polygons; stored borders are unchanged.

At GPS points contained by both members of a conflict pair, neither member can
win the displayed label. Other valid finer or broader containing areas remain
eligible. At points outside the overlap, each original polygon remains usable.
This avoids silently choosing the smaller of two incompatible claims. It does
not fabricate a dividing line or certify either border. Manual context similarly
uses one unambiguous containing sector, delegation or governorate.

## Coverage and limits

`coverage.json` gives the current generated totals by kind, source and governorate.
The revised September 2026 catalog contains **2,741 polygons**: 2,720 from OSM
and 21 from Bouarada. The OSM change adds seven previously excluded named locality
polygons and excludes one area tagged as a farmyard. The dataset contains 2,084
imada sectors, 534 residential areas, 28 neighborhoods, eight generic localities,
21 towns, nine suburbs, seven quarters, 34 villages and 16 hamlets. These are
different kinds of local areas, not uniformly defined neighborhoods.

There are **71 recorded conflict pairs**, including all seven audited imada pairs
and 22 pairs within the archived Bouarada source. These counts include very small
positive-area intersections; they are not 71 entirely unusable localities. The
original polygon remains eligible outside its conflicting overlap region.

Point-only place records are available for manual search. The exact list is in
`coverage.json`; many explicitly tagged neighborhoods still have no mapped polygon.
They are not used to claim GPS containment. A containing imada or other mapped area can still provide a name
there. If no named polygon matches, the app displays “موقعك الحالي” and says the
neighborhood name is unavailable, while the nearest timetable still works.

This is all usable named area geometry under the generator's documented tags in
this snapshot, **not a guarantee of all Tunisian neighborhoods** or official border
accuracy. OSM is a community map; unmapped or point-only neighborhoods need actual
boundary data before the app can identify them precisely offline. Device GPS
accuracy also limits the result, especially near borders. The original expanded
country outline is only a fallback if the new bundled index cannot load.

## License and attribution

© OpenStreetMap contributors. The derived polygon/point database is distributed
under the [Open Database License 1.0](https://opendatacommons.org/licenses/odbl/1-0/).
Individual contents are covered by the
[Database Contents License](https://opendatacommons.org/licenses/dbcl/1-0/).
See [OpenStreetMap copyright and attribution](https://www.openstreetmap.org/copyright).
Keep these notices and
make the derived database available under ODbL when distributing it; the generated
metadata, geometry and generation script are included in this repository.

Bouarada source attribution: Commune de Bouarada / OpenBaladiati, "Liste des
quartiers dans la zone municipale de BOUARADA", archived June 18, 2018. The national
catalog's license metadata says `cc-by`, with **no version or license URL supplied**
(checked September 6, 2026); the compiler preserves that exact limitation and does
not assume a particular Creative Commons version. Retain municipal attribution,
the catalog and archive links, and the source's license identification. Changes
are coordinate quantization and integration into the attributed offline database;
the original GeoJSON is included unchanged. Neither the municipality nor OSM
contributors are claimed to endorse the app or certify its neighborhood labels.

## Validation

Unit tests cover real points in المنزه 9 أ, النصر 2 and بوشوشة; polygon holes,
multipart geometry, outside/invalid coordinates, fine-over-coarse polygon choice,
and every compiled polygon's interior point. GPS tests verify independent nearest
timetable selection, persistence, movement between بوشوشة and خزندار with the same
prayer source, refreshing the displayed Dhuhr time after a background move from
Menzah to Bouarada changes the source, and clearing a stale neighborhood when no
polygon is available.
The compiler's packed output is also checked against Shapely for geometry validity
and containment of all representative points.

### Independent reliability audit

Run `python scripts/neighborhoods/audit_catalog.py --output-dir /path/to/review`
to check the packaged geometry, coverage, grid, overlaps, and actual labels at
point-only places. Add `--pbf /path/to/tunisia.osm.pbf` to inspect source omissions
and ambiguous name links. The audit reads local files and does not update app data.

The September 2026 audit found seven pairs of overlapping imada polygons and 902
point-only localities without their own matching containing polygon. The current
selection returns 874 sector labels, 20 residential labels, two hamlet labels,
five town labels and one unavailable label at those coordinates. A differently
named containing area does not establish a point-only locality's own boundary.
All packed geometries pass topology checks, but this does not certify their
real-world names or boundaries. The country
outline includes marine territory; percentages using it must not be presented as
land coverage, population coverage, or neighborhood completeness.

The revised compiler records the sector overlaps and other peer conflicts for
runtime exclusion where claims overlap. Archived 2018 Bouarada geometry is now
included with its age, attribution and its own overlapping pairs retained.
Municipal Sousse sources (60 and 57 records) remain excluded: they have substantial
same-name boundary conflicts and unresolved redistribution terms. Archive dates,
item modification dates, and valid geometry are not a substitute for current local
validation and source provenance.
