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
saved. Selecting a place manually uses a reviewed named settlement point when
one is explicitly linked in the curation manifest, otherwise the polygon's
interior representative point, to choose the nearest available timetable.
The compiler excludes sources with no complete bundled month: a CSV containing
only day numbers and blank prayer columns cannot receive a precomputed mapping.
Each complete month must contain every calendar day, six valid times per day and
their chronological order. Runtime eligibility checks the requested month again.
The availability count and rejected source IDs are recorded in `coverage.json`.

`prayer-source-coordinates.json` preserves the original and reviewed coordinates
for 28 reviewed timetable settlement references, including named OSM nodes,
source versions, corroborating records and the review date. These points identify
the settlement used for nearest-timetable selection; they are not certified INM
prayer-calculation coordinates. Earlier phone map views checked settlement context
only. Bargou and Kalaa Sghira use the exact named OSM village points corroborated
by published ministry/municipal location evidence; the source review records the
limits of that corroboration. The Bargou boundary remains a separate pending
source review and the Kalaa correction does not certify the full town extent.
The generator validates the bundled coordinates and identities against this
manifest before doing any geometry work, and stops if a reviewed point has been
reverted or changed. Update the asset and its reviewed evidence together when a
source needs a further correction. `coverage.json` records the manifest checksum
and corrected source IDs.

The picker retains governorate sections, search aliases and the selected-place
highlight. Search metadata is prepared off the UI thread while the main screen
is visible; the original delegation rows are immediately available. Browsing
never loads the polygon binary or recalculates the nearest timetable for
every row. Available rows are cached against the current source IDs/coordinates,
and exact nearest-source mapping runs once for the selected row.

Each raw locality retains its polygon and ID for GPS and saved selections. The
compiler adds `pickerGroupId` to collapse duplicate display rows when current
names or aliases match in the same governorate and their polygons have positive
area overlap. Points require actual containment, or identical coordinates, kind
and administrative context for duplicate point records; nearby homonyms, shared
borders and distinct numbered localities do not establish a match. Administrative sectors
are preferred as canonical rows. A town can join an original `delegation:ID` row
only when its name, containing administrative delegation and original timetable
location agree, and the town's representative point independently selects that
same available timetable as its nearest source. The compiler retains each real locality's source ID and geometry
while reducing duplicate display choices. Current counts and every group member
are recorded in `coverage.json`.

An additional conservative rule groups identical current primary names under one
containing sector even when its residential parts are disjoint. It requires the
same governorate and parent context, a unique six-digit source sector code, and
full containment of every member of the combined existing groups in both source
and bundled geometry. Another intersecting sector, a different primary name, or
an existing original-delegation group prevents this merge. Aliases and spelling
normalization cannot establish this additional match. The sector is the canonical
display row; all raw IDs, polygons and prayer mappings remain intact.

This currently joins the two residential parts of Dkhila under sector `7142945`,
code `525353`. Its identity as الدخيلة / Eddekhila under بني خداش was reviewed
against the INS RGPH2024 sector registry (population workbook row 60892; housing
row 2994; households row 2988). Geometry, name and code agreement identify the
grouping candidate; they do not certify the polygon borders. Source changes still
require review through the curation checksum. The Ettwaycha village chain does
not meet these sector-anchor criteria and stays separate.

`contextAliases` contains names and aliases of administrative parents established
by polygon containment or explicitly reviewed administrative membership. A source
`ref:tn:codegeo` must agree with full polygon containment before it can select a
parent; old codes cannot override geometry automatically. For example, the five
sectors transferred to Essaida still carry former Regueb codes in the source.
`catalog-curation.json` records their reviewed membership using the
[ISIE directory, page 54](https://www.isie.tn/wp-content/uploads/2022/06/Annuaire-codes-USSD-centres-de-Vote-en-Tunisie.pdf#page=54).
Thirteen other conflicting sector-parent pairs were checked against the same
directory before removing the generic code-priority rule; their individually
documented membership is retained despite conflicting source geometry.
It also establishes Jawhara (`135953`) under Mégrine (`1359`), consistent with the
[INS geographic code, page 43](https://www.ins.tn/sites/default/files/publication/pdf/code%20geographique%202012_1.pdf#page=43).
That metadata decision does not modify a boundary. The separately reviewed ISIE
overlay now supplies Jawhara's sector outline, as described below. Search never treats the
nearest prayer-time delegation as an administrative parent. Governorate names
come from the existing governorate catalog. A same-name parent is replaced in the
subtitle by its containing delegation, when available. `coverage.json` records
every grouped raw ID for audit.

The curation manifest pins the PBF checksum, each affected source ID and expected
tags, target administrative identities, reasons and supporting evidence. A source
change stops generation until these decisions are reviewed. Its full contents
and checksum are copied into `coverage.json`. Reviewed metadata is separate from
original source files; no curation rule rewrites a polygon or invents a locality coordinate.
Four `locality_context` decisions correct the parent labels of three Haf Oulad
Hamed records and Bir Tedrekhet to الدويرات. Each complete original and bundled
footprint is inside the reviewed official sector outline and outside the formerly
assigned sector. These decisions pin source tags, identity, map/decree evidence,
geometry and the complete prior feature, then apply only `parentName` and
`contextAliases` after grouping. Their official outlines remain context evidence.
The former western Chenini context rule is explicitly superseded by the complete
two-sector boundary and saved-point transition described below.

The separately reviewed Haf Oulad Hamed display association uses its existing
hamlet point as the canonical choice for the two named residential patches.
The source mapper created both patches in a survey-tagged edit, then refined
their vertices and added the same-named hamlet point in a second survey-tagged
edit. Exact source histories, edit contents and current PBF coordinates are
pinned. This establishes the bounded display association, not a full hamlet
outline or independently certified fieldwork. Its three raw records, Eddouiret
contexts and nearest prayer source 624 remain intact. This association runs only
after the existing context proofs have passed, so their original guards remain
unchanged.

Dar Chaabane's combined town record (`174739936`, دار شعبان الفهري) has a separate
reviewed display association with the existing `delegation:468` choice. It stays
distinct from imada `7103335` (دار شعبان), whose shorter name also appears as a
town alias. The current official registry and governorate city description
support the difference in scope. This explicit association preserves the town
choice's existing source 468 while retaining the raw residential representative
and its nearest source 623 for existing saved raw selections. It acknowledges
that the base point is outside the residential polygon; it does not relax the
existing spatial base-group rules or claim that polygon is a complete city
boundary. GPS still chooses its prayer source independently from the actual fix.

The manifest contains 69 reviewed administrative membership decisions. The latest
additions use the INS RGPH2024 sector registry, historical INS identities and ISIE
administrative evidence, with exact official codes and workbook rows retained in
each decision. Name-only matches to another sector do not establish a transfer.
The `name_tags` action, or `nameTags` on an administrative membership decision,
replaces the complete set of consumed name tags only after
all original names and source identity assertions have been checked. It runs before
parent context, search aliases and picker groups are built, so a rejected translation
cannot survive through an inherited sector name. Sector `7169624` (`435151`) now
uses its existing Arabic alias سيدي بوزيد and the INS2012 French Sidi Bouzid.
INS2012 page 107 and INS RGPH2024 distinguish it from sector `7169639` (`435162`),
سيدي بوزيد الغربية. The incorrect West aliases on `435151` are removed; the real
containing delegation remains Sidi Bouzid Ouest. Both source IDs and borders remain
separate, and this naming decision does not certify their geometry.
Eight further sector spelling corrections agree with three INS RGPH2024
workbooks and their existing named settlement points. They join eight duplicate
choices while retaining the old spellings as search aliases and preserving both
raw IDs. Ghidma's name correction shares its existing reviewed Faouar membership
decision. The evidence is retained in `name-reviews/` and in the curation manifest.
Each of these eight sectors explicitly links its existing point through
`manualPointId`. Generation requires matching current primary names and
containment in both effective source and quantized sector geometry; missing or changed
references fail generation. The named point supplies the manual choice's
coordinates, preserving the previous settlement-based timetable selection.
It does not alter a polygon or the geometry-derived area used to rank GPS labels.
For 21 sectors transferred to seven newer delegations that have no source
administrative polygon, `parentNames` supplies their explicitly reviewed ISIE
membership names. This adds context without fabricating a parent polygon. A
smaller locality inherits reviewed delegation context only when exactly one
reviewed sector fully contains it; its existing sector subtitle remains useful.
`curationApplications` records every direct and inherited use in `coverage.json`.
The five generic `TERRain vierge` land descriptions are excluded from both the
picker and GPS labels. Eight reviewed educational facilities, including explicitly
tagged schools/universities and three named institutions checked on the user's
phone, are also excluded where they have no independent place or administrative
identity. The road-access annotation مشكلة حق الطريق (`691401654`) and the
standalone SOS care campus in Mahrès (`315007832`) are also excluded after review
of their original tags, phone results and complete containing-locality coverage.
The campus has primary operator evidence identifying its institutional use.
These 15 exclusions are catalog scope decisions, not claims that the facilities do not
exist. Their IDs remain in `retiredLocalityIds` so saved labels can
be cleared without changing the user's prayer source. Other containing named
areas remain available as the GPS fallback. The generator writes the same ID list
to a tiny `retired-localities.json` asset for startup preference cleanup without
loading the full locality catalog.
Its optional `reviewedNames` entries come from validated `nameTags` decisions and
accepted official boundary identities. Saved manual and GPS selections receive these exact-ID name/kind fixes
on read without changing their prayer source or GPS origin. The tiny update index
is cached; missing or malformed data preserves the saved values.

The reviewed `preserve_point` action retains Sidi Frej point `475589717`,
Makarem point `1977214842` and the eight linked settlement points, their source coordinates and their already selectable
stable IDs after the official sector names are corrected. They can still enrich
the containing sector aliases and join the same picker groups, so saved
selections survive without extra visible rows.

Chenini and New Chenini retain their separate original sector and village IDs.
The two official whole-imada outlines correct the old Chenini village GPS label,
which the previous boundary incorrectly identified as شنني الجديدة. The eastern
village now has primary شنني الجديدة; its earlier generic شنني and Chenini names
remain searchable on that retained point. Each sector uses its own existing
village coordinate as `manualPointId`, producing two distinct picker choices and
a nearest eligible manual timetable of624 with the bundled sources. GPS prayer
times continue to use the actual device fix. Old manual sector selections can
therefore update from473 to624; old GPS selections preserve their saved source
until a new fix because the prior coordinate is not stored.

A byte-pinned `pointRetentionReview` permits this joint name/preservation change.
It checks exact original tags, coordinates, previous saved records and geometry,
the corresponding imported official sector, current INS identity and the exact
two-member result. Historical aliases on the renamed retained point are not
copied into a sector, preventing an accidental bridge between the two choices.
Neither point is moved or removed from the raw catalog. The tiny saved-name index
includes the eastern village and both imported sector identities.

Two further preserved settlement points explicitly join the original El Ksar
(`608`) and Souk El Ahad (`483`) choices through `pickerBaseDelegationId`.
The generator requires the exact source node ID and coordinates from the validated
prayer-source coordinate review, matching governorate and primary Arabic name,
and an independently nearest eligible timetable matching that original choice.
These rules cannot merge a nearby namesake or change the point's manual default.
The raw source IDs and search aliases remain available; the evidence is in
`scope-reviews/`. No polygon is changed by these links.

`reviewed-picker-groups.json` joins 95 reviewed original/locality display pairs,
covering 108 polygon records. These include المنزه, الرقاب and بئر الأحمر.
The original row supplies the single displayed choice; all raw IDs, search
aliases, coordinates, polygons and existing manual prayer defaults survive.
Saved locality selections still match through the retained group member IDs,
and GPS containment continues to use each original polygon.

The original 82 decisions and their evidence are unchanged. A separately pinned
review adds 12 pairs: four sectors with documented old-to-current administrative
codes, six specific sector-name or parent-label variants, Sousse's explicit city
identity, and El Aroussa. The last includes one name correction:
`osm:way:190914701` gains Arabic primary العروسة from its containing bilingual
coded sector and parent, while retaining El Aroussa and L'aroussa for search.
The small saved-name index also updates that existing raw selection.

Generation checks the complete existing locality and target group inventories,
exact current primary names with word boundaries, governorate, original point
containment in every unrounded and packed member polygon, and the same independently
nearest available prayer source for every member. Original PBF tags and both
geometry representations are pinned. Supporting coded identities or explicit
place tags must match the approved source evidence. All records are checked before
any groups change; no alias, proximity or transitive rule adds another member.
The 16 remaining cases from the original 110-pair review stay separate pending
further evidence. Added sector rules bind exact current INS records, source-code
versions, accepted administrative curation and per-ID spelling pairs without
broadening name normalization. Their original coded parent tags, geometry and
sector containment are checked against the source; containment of every other
group member is recorded separately, including the false result for Kalaat
Khasba's residential footprint. No current administrative border is invented
from a name or an old parent. El Aroussa's exact translation decision and its
source review are mandatory prerequisites, with all referenced evidence hashed.
Checkpoint hashes are retained as evidence; per-member checks allow unrelated
catalog changes without silently accepting changes to a reviewed member.

Bousalem town (`osm:way:172866902`) is separate from Bousalem North sector
(`osm:relation:7144493`); the sector's unqualified French alias does not make
the town and the smaller imada identical. The town footprint joins the existing
`delegation:499` choice using its exact absorbed town node `1837034352`, whose
coordinates already supply that reviewed settlement reference. The point lies
inside the original and packed town footprint. Its source identity and the
official planning document distinguish town from sector; the compiler checks
the complete separate-choice and base-association decisions together. No raw
point, border or timetable coordinate is added or changed.

The separate exact `localRecords` link joins Taouaicha's two residential
fragments under the existing `osm:way:312733902` picker row. Village polygon
`osm:way:969654331` contains both fragments in the original and packed data;
bilingual village node `osm:node:9843640664` connects الطوايشة and Ettwaycha.
All three geographic records survive. This reviewed display identity does not
certify the village boundary or narrow its governorate-only administrative
context: its source footprint intersects two sectors. Complete group inventories,
source identities, names, coordinates, geometry hashes and the shared nearest
prayer source are checked before the single group field changes.

A second reviewed local link joins Tijma's addressed residential fragment
`osm:way:379743528` to village point `osm:node:1997440362`. Both sources explicitly
name their locality address تيجمة, and the independent source review establishes
their shared village identity in Nouvelle Matmata. The generator pins their
Arabic/English names, exact source identities, coded containing context and
shared nearest timetable 1022. The point and the small residential polygon remain
separate geographic records; the point need not lie within that fragment and no
whole-village boundary is inferred. Existing Taouaicha and 94 original/locality
rules remain unchanged.

Sector `osm:relation:7154898` now uses its official sector name الحامة, with
حامة الجريد retained as a search alias and delegation context. The review links
old source code `625256` to current INS code `625651`, preserving the existing
membership correction and all former French aliases. The saved-name index updates
existing selections. Its polygon and the separate original timetable row `481`
remain unchanged. The الحامة 1/2 electoral maps support the name review only;
neither part is substituted for the whole imada.

Two further reviewed links join the residential fragments for الصفاية
(`osm:way:129895999`) and قصر الجوامع (`osm:way:377116351`) to their named
settlement points `osm:node:1432323476` and `osm:node:11230028572`. Primary
locality references and the source inventory support these identity decisions.
The generator pins each source, bilingual name, coded sector context, geometry
and shared nearest timetable (514 and 489). The point does not have to lie
inside its small residential fragment; no distance-only merge rule or larger
settlement polygon is introduced.

The three El Adbech residential footprints (`1265702650`, `1265702651`,
`189085992`) share the existing الأدباش picker choice `osm:way:189085992`.
This exact display association uses their recorded local-knowledge edit,
the bilingual source village and independent locality documentation. It
retains every polygon, name, coordinate, prayer source 1022 and per-member
administrative context, including the canonical governorate-only context.
The two rural patches have no shared address tag or whole-village outline;
their association is a documented review inference. The source village node
`1997440361` remains absorbed. Explicit source-group inventories, all source
tags and geometry hashes, edit/history files and primary documents are checked
before either picker group changes. No distance-based merging rule is added.

Both Ksar Jouamaa records use the primary Arabic spelling قصر الجوامع, retaining
قصر الجؤامع and Ksar Jouamaa as search aliases. The saved-name index updates
existing selections. The separately documented fortress remains a distinct
place, and its published coordinates are not used for the village.

Sector `165255` (`osm:relation:7113328`) uses the qualified name الزريبة القرية
from the current INS registry, historical INS directory and official ISIE map.
Its former short Arabic and French names remain searchable. The exact curation
and source evidence are in `name-reviews/`; the saved-name index updates existing
selections. This name correction leaves the older polygon and generic meteo
row `409` unchanged. If the separately reviewed official polygon is later
accepted, supersede this name-only curation in that same import so the official
identity supplies the name.

Yasmine Hammamet (`156659`) uses its documented Hammamet/Nabeul administrative
identity from the [INS directory, page 52](https://www.ins.tn/sites/default/files/publication/pdf/code%20geographique%202012_1.pdf#page=52).
Its source polygon conflicts with the source Sousse governorate border. The
manifest keeps that boundary discrepancy unresolved: a corrected governorate
label and a Google Maps place-name spot-check do not certify boundary vertices.

## Sources and reproducible build

`reviewed-boundaries.json` accepts 37 individually reviewed official ISIE 2023
sector footprints: all eight Mégrine sectors, thirteen identity corrections in
Ksar Hellal, Khmouda, Beni Khalled, Ariana/Soukra, Kerkennah, Zarzis and Sidi Bouzid,
four neighboring sectors in Beni Khalled, Thala and Foussana, and seven sectors
reviewed together around Bekalta, Chenini and New Chenini, plus Medina, Cheikh Idris
and Moutamar in Bizerte. Thirty-six replace
the corresponding legacy sector geometry while preserving its stable ID.
Mégrine Chaker 2 (`135956`) is added as `isie:sector:135956`. It is distinct from
Sidi Rezig 2 (`135958`). Menzel Mabrouk (`135954`) has its own official polygon.

The accepted GeoJSON files and decision evidence are in `official/`. Each feature
records its PDF URL/hash, geographic controls, original vector geometry and
identity review against three agreeing INS RGPH2024 workbooks. The loader pins
each file's bytes and original OSM identity, requires unique official codes after
replacement, and checks names, source provenance and whole-country containment.
It does not infer shapes from search results or copy old contradictory aliases.
The manifest also pins an individual administrative-scope review for every
accepted outline. Decree 590 of September 21, 2023 distinguishes whole-imada
electoral circles from circles that split or combine administrative areas.
Thirty-three outlines have individually reviewed whole-imada circle correspondences.
Bekalta North (`325951`) uses the exact union of both legally exhaustive northern
electoral districts. Its stable ID `7111933` now has the correct Bekalta North
identity; the incorrect Teboulba name is removed from its aliases. Fadhalin,
Ayaicha, Bekalta South, Bekalta East, Baghdadi and Hiboun are corrected together
with it, retaining their individual IDs and full source outlines.

The separate `aggregateScopeReview` requires the complete reviewed delegation
inventory, both exact named parts and their pinned PDFs, geometry hashes and
source file. It verifies that the accepted outline equals their unrounded set
union. Either part alone, an additional part or a changed source fails generation.
The ordinary whole-imada guard remains separate and retains all 25 prior reviews.
Adding a circle, changing a source file or changing an official code
requires a matching reviewed record; an electoral map title alone is insufficient.
Reviewed parent membership takes precedence over old administrative geometry;
smaller areas inherit it only through one fully containing reviewed sector.

The original vectors were georeferenced from each PDF's WGS84/UTM32N control
points. Jawhara needed an explicitly reviewed `make_valid` repair; both resulting
components, including its tiny triangle, survive compiler precision. Zelfan loses
only a retraced zero-area line, with exact source-coordinate area and segment
proofs retained. Foussana Ahwaz joins two source strokes at exactly matching
endpoints without any new connector. Foussana city uses its separate labeled
complete ring from the same page and shares an exact non-overlapping border.
The loader performs no repair, snapping, clipping or gap filling. Independent
page registration creates small overlaps and gaps. Existing conflict detection
records positive-area overlaps; it does not model a general uncertainty buffer
or certify labels beside a gap. These dated official electoral-sector maps do
not establish every informal neighborhood or exact current ground boundaries.
For the Bekalta North union, ordinary coordinate rounding preserves four hole
components but collapses a very thin branch of one hole. About 11.55 square metres
of that source gap become covered; the exterior changes about 0.062 metres.
The exact source parts and union, measured caveat and explicitly accepted packed
geometry hash are preserved in `official/`. No gap is manually filled, and the
remaining neighboring overlap strips keep the normal conflict handling.

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
python scripts/generate_neighborhoods.py /path/to/tunisia-260905.osm.pbf
```

The generator makes no network requests. The output files are
`android-app/app/src/main/assets/neighborhoods.json`, `neighborhoods.bin` and
`retired-localities.json`.
`coverage.json` records sources, counts, excluded features, point-only places, and
conflicting polygon pairs with sample coordinates inside their intersections.
Each feature has a `sourceId`; the metadata's `sources` registry retains its
provenance independently of the existing OSM `source` field. The compiler rejects
changed source bytes, duplicate IDs, missing names, unexpected feature counts,
non-WGS84 coordinates and invalid municipal polygons. It does not silently repair
municipal borders or replace missing records with inferred geometry.
Rerun it when updating the source extract or meteo.tn coordinate catalog.
Review the curation manifest before changing source snapshots. Ordinary app core
changes receive compilation checks; test execution and APK generation are left
for a requested release unless specifically requested sooner.

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


The uncoded Cheikh Idris clone `7152490` is excluded only with the three corrected
Bizerte sector outlines. The generated small saved-location index redirects that
exact ID to `7118632`, retaining the saved prayer source and GPS origin. The
generator checks its complete original identity, cloned geometry, unique official
sector identity and all three companion replacements together. Corrupt update
metadata preserves saved preferences.

The old-town suburb `256615867` remains a separate choice from Medina sector
`7118631`. Their shared French alias does not establish the same locality; about
47.46% of the suburb lies outside that sector. A reviewed `distinctPairs` entry
keeps both labels, aliases and complete polygons, preventing direct or indirect
regrouping. Kasba now has Medina context; Oued Laassal uses Bizerte Nord because
its full footprint is not contained in the corrected Moutamar sector.

## Coverage and limits

`coverage.json` gives the current generated totals by kind, source and governorate,
including every reviewed exclusion. The catalog combines OSM polygons, 21
archived Bouarada polygons and 37 accepted ISIE sector footprints. It includes
2,084 imada sectors alongside residential
areas, neighborhoods, generic localities, towns, suburbs, quarters, villages and
hamlets. These are different kinds of local areas, not uniformly defined
neighborhoods. Named locality polygons are eligible, while farmyards, generic
vacant-land descriptions and reviewed standalone facilities are excluded.

There are **191 recorded conflict pairs**, including 127 sector pairs
and 22 pairs within the archived Bouarada source. Importing separately registered
official outlines exposes additional disagreements against adjacent source polygons,
including remaining substantial overlaps with old neighbors; not all conflicts
are small registration strips. The reviewed Zaouiet Jdidi, Zelfan and Foussana
replacements resolve the three initial largest disagreements, but further
Rtybat and neighboring source reviews are still needed. The largest remaining
mixed-source disagreement is Chenini/Guermassa, about60 km². The two Chenini
outlines add eight conflict pairs and expose about29.11 km² of enclosed gaps
against older neighbors. These are retained rather than clipped or filled.
Both reviewed village coordinates have an unambiguous correct label; no current
catalog settlement point loses its polygon label. That evidence does not establish
that uncovered areas are uninhabited or that every regional boundary is correct.
Additional official neighboring outlines remain candidates for further review.
The Makarem/Al Amra disagreement is also unresolved; its proposed connected
boundary corrections remain outside the app.
These counts include very small positive-area intersections; they do not count
entirely unusable localities. The
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

The pre-import September 2026 audit found seven pairs of overlapping imada
polygons and 902 point-only localities. The official import now leaves 905
point-only places. Correcting sector identities exposes three point labels
previously absorbed into wrongly named sectors. The previously selectable Sidi
Frej and Makarem points remain available under their preserved IDs. Numbered place points are
not absorbed into an unnumbered parent through a loose alias. A differently named
containing area does not establish a point-only locality's own boundary.
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

Touil Ouled Zayed uses the existing reviewed named-settlement rule to show its
residential area and hamlet point as one choice. The point lies outside the
residential footprint; both remain available to geographic lookup with their
original coordinates and source 624. The separate peak is not a locality choice.

Ksar Ouled Soltane uses a reviewed two-patch settlement association. The original
source edits and explicit duplicate-label discussion support showing its two
residential areas once, under the southern area containing the absorbed village
point. Both current footprints and raw source 624 are retained. The surrounding
sector keeps source 1524. This display association does not certify a complete
village boundary or the history of every footprint vertex.

The official names Sahloul Jawhara (315356) and Er Remil (246153) replace
the ambiguous Sahloul and unsupported Ezzmil source labels. The latter also
updates Talala’s inherited parent label. Registry, decree, and georeferenced
map evidence are retained in `name-reviews/`. These are name corrections:
source shapes, coordinates, prayer defaults, and picker groups are preserved.
Er Remil’s published map does not expose a complete target outline, so it
is not used as a replacement polygon.
