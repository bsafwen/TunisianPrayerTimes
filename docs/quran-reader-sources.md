# Qaloun Quran reader

The Android Quran tab reads the user's original `Quran_Qaloun_pages` WebP files offline. Its search index contains the Qaloun reading from [Quranpedia's مصحف قالون](https://quranpedia.net/surah/7/1), retrieved on 2026-10-02. The images remain the reading surface; no OCR text is displayed.

## Images and page numbering

All 621 supplied images and their `pages.json` manifest are bundled under `android-app/app/src/main/assets/quran/pages/`. Source image 1 contains al-Fatiha and the beginning of al-Baqarah, with printed folio 2. Source images 1–603 contain the Quran; their printed folios are 2–604. Images 604–621 are the 18 pages explaining the edition and recitation conventions. Printed page 1 therefore opens the first supplied image as well.

Every one of the 114 chapter title destinations was visually checked against the supplied images. The checked source-image map is preserved in `scripts/quran-data/qaloun-chapters.json`. The scan uses the later Madani count of 6,214 verses; Hafs verse numbers must not be applied to it. The unrelated `qaloon-app` text is used by the importer only for its chapter names, not its Quran text or verse numbering.

## Search and page destinations

`quran/index.json` includes 114 chapters and 6,328 searchable text fragments: 6,214 Qaloun verses, one additional fragment for a verse crossing a page, and 113 opening basmalahs. Search results display the Qaloun source wording without imposing another edition's verse numbers. Adjacent fragments on the same page are searched together so a phrase can cross a verse boundary.

Initial page destinations are obtained by aligning Qaloun words with the text locations in [Tanzil's page-boundary metadata](https://tanzil.net/res/text/metadata/quran-data.xml). Its printed-page boundaries agree with all 114 independently checked chapter starts, but four verse destinations differ from the supplied scan. The individually verified corrections are stored in `scripts/quran-data/qaloun-page-overrides.json`: the complete verses 4:44, 24:36, and 24:42 appear on source images 84, 353, and 354 respectively; 87:15 appears on image 591. The supplied scans, including their numbered verse medallions and the adjoining page openings, take precedence over the reference metadata.

Matching words are aligned in order; differing-length replacements may only inherit a page when their entire reference block is on that same page. The importer fails on any insertion/deletion that would require guessing between two reference pages. The alignment contains zero non-equal blocks spanning a reference page boundary. Each scan-specific correction also checks its expected pre-correction locations so that a changed reference cannot silently invalidate it.

An independent visual review also compared the actual opening words on source images 20, 40, 80, 120, 160, 200, 240, 280, 320, 360, 400, 440, 480, 520, 560, and 600 against the expected printed-page boundaries. All 16 matched, with no page-boundary discrepancies.

A subsequent complete scan pass detected the verse-end medallions on all 603 Quran images using the supplied edition's ordinary and final-verse ring designs. After the four individually reviewed page corrections, all 603 per-page counts match the index, with 6,214 detected verse-end medallions in total. This is a count and placement check; verse identities come from the Qaloun sequence and checked chapter starts, not from automated recognition of the printed digits.

Index schema version 2 preserves the Qaloun `ayah` number on every fragment. Opening basmalahs have `ayah: 0`; surah 9 has no such heading. `isFirstFragment` and `isLastFragment` distinguish the one verse that continues across scans: 20:86, on images 316–317. Image 316 ends with `فكذلك ألقى السامري` without a verse medallion; image 317 continues with `فأخرج لهم عجلا` and ends that verse at `فنسي` and medallion 86. Numbered verse medallions belong only to the final fragment. The catalog exposes complete verse text, all pages for a verse, the verses on a page, and individual fragments for playback selection, page following, and scan highlights. Its complete `verses` list includes the 113 basmalah headings as well as the 6,214 numbered verses.

[Tanzil's ordinary Arabic spelling](https://tanzil.net/download/) supplies search aliases for corresponding words with Uthmani spelling, such as `الكتاب`, `السماوات`, and `الصلاة`. These aliases are searched without replacing the displayed Qaloun wording. The importer restricts aliases to corresponding spelling differences; it does not turn the Qaloun reading `مَلِكِ يَوْمِ الدِّينِ` into `مَالِكِ يَوْمِ الدِّينِ`. Quranic marks, vowel marks, tatweel, alif/hamza carrier variants, and the Qaloun yeh shape are handled during search normalization.

Attribution and the complete Tanzil notice are bundled in `quran/SOURCES.txt`. The fetched input SHA-256 hashes are recorded in `scripts/quran-data/source-hashes.json`. The search index's metadata also identifies its sources.

## Verse highlight geometry

`quran/highlights.json` contains normalized rectangles measured directly from the supplied scans: all 603 Quran images, 6,214 numbered verses, and 6,215 page fragments. It includes both fragments of 20:86. The 114 chapter banners and 113 unnumbered basmalahs are excluded.

The offline generator normalizes image height to 1,450 pixels, detects the ordinary and final-verse medallion frames with digit-masked templates taken from source images 2 and 1, and groups their centers into reading rows from right to left. It requires the detected endpoint count on every page to agree with the Qaloun index. Text-row positions come from the image's ink projection; long horizontal rules identify chapter banners. Nine ambiguous banner pages use measured, visually reviewed source-image coordinates. Line rectangles are divided at the detected verse endpoints and trimmed to visible horizontal content.

`scripts/quran-data/highlights-audit.json` records every source image's SHA-256, per-page endpoint and row counts, lowest marker score, template crops, and manually reviewed banner pages. The observed minimum marker score is 0.7755, above the 0.67 acceptance threshold. A complete independent rebuild reproduced the delivered geometry exactly. Visual checks cover the first page, Ayat al-Kursi, At-Tawba without a basmalah, the cross-page verse, the unusual 16/17-row pages, and several pages with multiple chapters. These visual samples complement the complete automated count and bounds checks; the printed digits were not independently OCR-verified.

## Rebuild and verify

Use PowerShell 7 and Python 3.10 or newer from the repository root. The fetch script explicitly routes every request through the required machine proxy, `http://127.0.0.1:8888`; it does not modify machine proxy settings.

```powershell
./scripts/fetch-quran-index-sources.ps1
python scripts/import-quran-index.py --source work/quran-import/source --imlai work/quran-import/quran-simple.txt --metadata work/quran-import/quran-data.xml --chapter-map scripts/quran-data/qaloun-chapters.json --audit work/quran-import/alignment-audit.json
python scripts/verify-quran-index.py
python scripts/import-quran-highlights.py --source C:\Users\barou\Downloads\Quran_Qaloun_pages
python scripts/verify-quran-highlights.py
```

The highlight importer additionally requires Pillow, NumPy, and `opencv-python-headless`; it makes no network requests. The highlight verifier uses only Python's standard library and checks all page/verse identities, normalized bounds, nonempty rectangles, and the single cross-page verse.

The importer itself uses only local files. It checks all 114 chapter numbers, 6,214 verse numbers in sequence, all 603 Quran page destinations, monotonic word locations, and the manually checked chapter starts. The offline verification also checks all 621 image files, ordinary and vocalized word searches, a phrase spanning the Qaloun verse split in Ayat al-Kursi, first/last-page destinations, and preservation of the Qaloun reading in al-Fatiha.
