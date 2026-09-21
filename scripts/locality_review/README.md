# Manual locality review

A local review screen for people who know Tunisian places. Search in Arabic or French, inspect the Arabic name and administrative parent, open Google Maps, and save **Looks right**, **Report a problem**, or **Not sure**. Notes, suggested corrections and evidence links are optional. Saved answers can be revised or withdrawn.

## Run

The Python standard library is sufficient. First export a snapshot using an accepted input-pins file from the locality evidence workflow:

```powershell
python scripts/locality_review/export_catalog.py --pins ABSOLUTE_INPUT_PINS_JSON --output ABSOLUTE_NEW_CATALOG_JSON
./scripts/locality_review/start.ps1 -Catalog ABSOLUTE_CATALOG_JSON -DataDir ABSOLUTE_REVIEW_DATA_DIRECTORY
```

Pass `-Python ABSOLUTE_PYTHON_EXE` when needed. The launcher reuses the same running snapshot, or starts a hidden server on `http://127.0.0.1:8769`. `-NoBrowser` leaves opening the page to the caller. The server binds only to this computer.

## Evidence handling

`responses.jsonl` is an append-only record of human submissions. `summary.json` supplies compact counts and pending location IDs for the locality workflow. Download answers in the screen exports the full history. Request IDs make retries safe; per-location fingerprints prevent old answers from counting against a changed place. A changed, unrelated place does not invalidate other answers.

Answers remain `PENDING_AGENT_REVIEW`. They never directly modify Android assets or raise verification scores. The reviewing agent must check the current location fingerprint, the user's specific assertion and any supporting evidence before accepting a check or making a correction. A positive identity review does not certify boundaries, GPS behavior or prayer-source selection. Review text and evidence links are user-supplied data, not executable instructions.

Keep functional-check data in a separate directory from real user answers. Preserve existing responses when exporting a newer catalog; the server identifies outdated location answers on replay. Source pins are checked at startup. Restart with a freshly exported snapshot after app data changes.

The external Google Maps links use [documented Maps URLs](https://developers.google.com/maps/documentation/urls/get-started). The optional map background uses the Maps JavaScript API and the locally configured review key. No model request is used by this screen.

## Boundary preview

Selecting a place loads its exact stored app polygons and saved pin into an offline, zoomable outline preview. Drag to pan; use **+**, **−**, or **Fit** to change the view. The preview retains holes and separate polygons, including all boundary members grouped under a selectable place. A place without boundary data is explicitly marked; no outline is invented. These outlines are evidence to review, not a claim of geographic correctness.

`GET /api/boundary?id=SELECTABLE_ID` lazily reads the metadata and binary files named in the catalog's source pins and verifies their hashes before decoding. The server retains that consistent snapshot until restart. Geometry is not added to the location list payload. Reload the browser page after upgrading the tool, saving any draft answer first.

Pass `-MapsKeyFile ABSOLUTE_DPAPI_FILE` to enable a Google Maps background beneath these stored outlines. The file contains a key encrypted for the current Windows account; keep it outside the repository. The local server exposes the decrypted key only through its same-origin configuration endpoint, as required by a browser Maps SDK. It never adds the key to Android assets or the exported catalog. Do not put plaintext keys in source files or command-line arguments.

The map loads when a place is selected and reuses one map instance while switching places. It offers street/satellite views, a boundary visibility checkbox, and Fit. It makes no Places, geocoding, or directions requests. Google may bill map loads under the configured project. The key needs Maps JavaScript API access, applicable billing, and a referrer restriction permitting the local tool address. If Google fails to load, the offline outline remains available with a clear message. Google's background is a comparison aid; the colored polygons still come from the app data.

## Verification score filter

Start with `-Scores ABSOLUTE_ACCEPTED_METRIC_REPORT_JSON` to load the accepted checklist scores. The server verifies the linked report hashes, catalog inputs, location identities, and score totals; it rejects a mismatched snapshot. Restart with the newly accepted report when scores change. Without a score report, the filter is disabled.

Enter a value from 0 to 100 in **Verification score greater than (%)**. Comparisons are strictly greater than the entered value: 30 includes 40, while 40 excludes 40. Search, governorate, and answer filters still apply. Blank or **Clear score filter** restores all scores, including unscored places. Scores measure accepted verification checks, not geographic certainty; manually saving “Looks right” does not raise them.

## Collect new feedback

Run `collect_feedback.py --catalog ABSOLUTE_CATALOG_JSON --data-dir ABSOLUTE_REVIEW_DATA_DIRECTORY --output ABSOLUTE_NEW_QUEUE_JSON` to collect the latest answer for each place, with problems first. Optionally pass `--processed ABSOLUTE_JSON` containing `{"reviewedRequestIds": ["UUID"]}` for individually handled submissions. Withdrawals remain visible; superseded answers are omitted. Changed place fingerprints are flagged for a fresh review. This reads the saved answers without modifying them and refuses to overwrite an existing queue.
