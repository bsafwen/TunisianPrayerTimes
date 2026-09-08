# Official Islamic Date Corrections

This app uses the device's Hijri calendar as a fallback, but Ramadan and Aid dates should follow the official Tunisian announcement when it differs from the algorithmic calendar. Corrections are done by publishing a yearly official-date JSON file, not by changing app code for every lunar-year announcement.

## What The Override Controls

The remote file can correct three official dates for a Hijri year:

- `ramadanStart`: the Gregorian date of 1 Ramadan.
- `eidFitrDate`: the Gregorian date of Aid el-Fitr, 1 Shawwal.
- `eidAdhaDate`: the Gregorian date of Aid el-Adha, 10 Dhul Hijja.

The Android app fetches the file from GitHub Pages:

```text
https://bsafwen.github.io/TunisianPrayerTimes/data/official-islamic-dates/{hijriYear}.json
```

The canonical source file lives with the other app data, not in the GitHub Pages webroot:

```text
data/official-islamic-dates/{hijriYear}.json
```

The Pages deployment also publishes a generated legacy copy at `https://bsafwen.github.io/TunisianPrayerTimes/ramadan-override-{hijriYear}.json` so already-released app versions keep working.

Example:

```json
{
  "hijriYear": 1447,
  "ramadanStart": "2026-02-19",
  "eidFitrDate": "2026-03-20",
  "eidAdhaDate": null,
  "lastUpdated": "2026-04-06T12:00:00Z"
}
```

Use Gregorian ISO dates in `YYYY-MM-DD` format. Keep unknown dates as `null` until the official announcement is available.

## How The App Uses The Dates

The correction flow is implemented in the shared Kotlin module:

- `RamadanOverrideChecker` fetches, parses, caches, and persists the yearly JSON override.
- `OfficialIslamicDates` retains announcements by Hijri year and publishes updates to the calendar and prayer UI.
- `TunisianHijriCalendar` converts civil dates and resolves month boundaries for both the calendar and Ramadan/Aid behavior.
- `RamadanDetector` decides whether the app should behave as Ramadan-active.
- `MainScreen` asks `RamadanOverrideChecker` whether to show the Aid el-Fitr and Aid el-Adha prayer rows.
- `PrefsManager.applyRamadanIshaOverrideIfNeeded` uses `RamadanDetector` to apply the one-time Ramadan Isha silence duration bump.

If the override has `ramadanStart`, that date is 1 Ramadan. An announced `eidFitrDate` establishes 1 Shawwal and the end of Ramadan. Until it is announced, Shawwal starts on an estimated boundary derived from Umm al-Qura and the known correction, with a valid 29- or 30-day Ramadan.

The app also keeps the existing Ramadan buffer behavior: the day before Ramadan and Aid el-Fitr day are treated as Ramadan-active. This keeps the Ramadan UI and Isha silence behavior available around boundary days.

## Hijri Calendar Integration

The Android date header and picker use the same `TunisianHijriCalendar` snapshot as Ramadan detection and Aid date getters. Official records anchor 1 Ramadan, 1 Shawwal, and 1 Dhul Hijja (nine days before Aid el-Adha). The Gregorian day remains the selection identity and the key for prayer-time data.

Different announcements can have different offsets from Umm al-Qura. The resolver reconciles month starts while keeping every month contiguous and 29 or 30 days long. Unannounced starts follow the latest accepted offset within that year; estimates return toward the baseline at the next year boundary while preserving valid month lengths. Contradictory or implausibly misplaced anchors are not used as confirmed month starts.

The UI labels confirmed Tunisian month starts. The resolver still tracks whether a date is estimated internally; a provisional day 30 remains estimated until the next month's start is confirmed. The Ramadan-active buffer does not change the actual date labels: the day before Ramadan remains in Shaaban, and Aid el-Fitr remains 1 Shawwal.

Calendar navigation loads records for the viewed Hijri year and adjacent years on a background dispatcher. Announcements update the header, picker, and Aid rows while preserving the selected Gregorian day and viewed Hijri month. Aid prayer defaults use sunrise on the resolved Aid date in that selected year.

Android builds package the canonical `data/official-islamic-dates/*.json` records as generated assets, so existing announcements are available on the first offline launch. The legacy cache is migrated before importing those assets. Per-year caches track freshness separately in `ramadanStartUpdated`, `eidFitrUpdated`, and `eidAdhaUpdated`; a partial update retains both a missing event's known date and its original timestamp. Files without those optional keys use `lastUpdated` for their supplied events; an explicit null event timestamp preserves unknown provenance. Missing or unavailable remote years retain their cached dates and calculated estimates.

The open calendar retries on resume and restored connectivity. Its Today marker follows midnight and clock/time-zone changes, while an in-progress selection and viewed month remain stable. Accepted event-day changes also refresh Android scheduling for the lifetime of the app process. Obsolete Aid alarms are cancelled, valid upcoming and active windows are retained, and queued Aid deliveries are checked against the corrected date.

## Aid El-Fitr Correction

When the Tunisian authorities announce Aid el-Fitr:

1. Open or create `data/official-islamic-dates/{hijriYear}.json`.
2. Set `eidFitrDate` to the official Gregorian date.
3. Update `lastUpdated`.
4. Merge to `main`; the Pages workflow publishes the canonical data path and legacy compatibility copy.

The app will then use the corrected date for:

- Showing the Aid el-Fitr prayer row.
- Computing whether the displayed day is Aid el-Fitr.
- Ending the Ramadan-active period at the correct official date.
- Predicting Aid el-Adha drift until the Aid el-Adha date itself is announced.

The Aid el-Fitr row is shown from two days before Aid through Aid morning. On the Aid day itself, it disappears after Dhuhr when the displayed day is today.

## Aid El-Adha Correction

When the Tunisian authorities announce Aid el-Adha:

1. Open `data/official-islamic-dates/{hijriYear}.json` for the same Hijri year.
2. Set `eidAdhaDate` to the official Gregorian date.
3. Update `lastUpdated`.
4. Merge to `main`; the Pages workflow publishes the updated JSON.

Before `eidAdhaDate` is known, the app estimates Aid el-Adha from the algorithmic 10 Dhul Hijja date plus the latest known drift. The drift is taken from `eidFitrDate` when available, or from `ramadanStart` otherwise. Once `eidAdhaDate` is set, it takes precedence over the drift estimate.

The Aid el-Adha row follows the same visibility rule as Aid el-Fitr: visible from two days before Aid through Aid morning, then hidden after Dhuhr on Aid day when today is being displayed.

## Ramadan Start Correction

When Ramadan start is announced:

1. Open or create `data/official-islamic-dates/{hijriYear}.json`.
2. Set `ramadanStart` to the official Gregorian date.
3. Leave `eidFitrDate` and `eidAdhaDate` as `null` until announced.
4. Update `lastUpdated`.
5. Merge to `main`; the Pages workflow publishes the file to GitHub Pages.

This lets the app correct the Ramadan banner and the one-time Isha silence duration behavior without shipping a new Android release.

## Polling Windows

The background event poller runs only near dates where an official announcement may change the calendar:

- Ramadan start: late Shaaban and the first two algorithmic Ramadan days.
- Aid el-Fitr: from the real 29th Ramadan when `ramadanStart` is known, or from algorithmic 28 Ramadan as a fallback.
- Aid el-Adha: late Dhul Qidah through early Dhul Hijja, adjusted by any known Ramadan/Aid el-Fitr drift.

Each event's window is evaluated independently. A known Ramadan start does not stop Aid el-Fitr polling, and a missing Aid el-Fitr date cannot block Aid el-Adha polling. Only anchors accepted by the calendar count as confirmed or supply drift; rejected dates continue to qualify for polling during their event window.

Fetched data is cached by year under `official_islamic_dates_{hijriYear}`. The legacy `ramadan_override_json` record is migrated and retained for existing callers. Calendar browsing can also fetch the viewed year outside the event polling windows. Concurrent calendar requests are deduplicated by year: successful loads are refreshed hourly, failed loads can retry after one minute, and resume/reconnect requests can refresh after a five-second minimum gap. Foreground calendar checks run each minute; offline launches retain the last successful corrections.

## Automated Detection Runner

The repository includes a GitHub Actions workflow that can prepare these JSON corrections automatically:

```text
.github/workflows/check-islamic-dates.yml
scripts/detect_tunisian_lunar_dates.py
```

The workflow runs four checks every day of the year (17:30, 19:30, 21:30 and 23:30 UTC), so Ramadan and Aid remain covered as they drift through the Gregorian calendar. GitHub may disable scheduled workflows after 60 days of repository inactivity; if the Actions page shows an inactivity notice, use **Enable workflow** there to resume the schedule. The script exits without doing network work unless the current approximate Hijri date is close to one of the announcement windows:

- Ramadan start: late Shaaban through the first days of Ramadan.
- Aid el-Fitr: late Ramadan through the first days of Shawwal.
- Aid el-Adha: late Dhul Qidah through the first days of Dhul Hijja.

The approximate Hijri date is only a polling and validation-window helper. It is not an official source and must not be used to decide Ramadan or Aid dates by itself.

It can also be started manually from GitHub Actions with `workflow_dispatch`, choosing `auto`, `all`, `ramadan_start`, `eid_fitr`, or `eid_adha`. A manual run can pass a `today` value in `YYYY-MM-DD` format for testing a future window.

For older historical checks, the script supports `--use-gdelt` as an extra fallback source search. It is disabled by default because normal Google News and Bing News RSS queries are less noisy and avoid GDELT rate-limit failures.

The DeepSeek API key must be stored in GitHub Actions secrets as:

```text
DEEP
```

For local testing, the script reads `DEEP` from the shell environment first. If it is not set, it reads an ignored local `.env.local` file containing `DEEP=...`. This file is for developer machines only and is not used by the GitHub runner.

The script treats `meteo.tn` as the primary source for crescent visibility. If direct `meteo.tn` evidence is not enough, it falls back to generic Google News and Bing News RSS searches using Arabic and French Tunisia-focused announcement terms. The fallback is intentionally search-engine based instead of being scoped to a fixed list of news sites.

Discovered sources are still classified by trust tier before validation. Official or semi-official domains, such as Dar al-Ifta Tunisia, the Ministry of Religious Affairs, `meteo.tn`, and TAP, are preferred. Trusted Tunisian news domains discovered through search can support an official announcement, but generic search results do not become authoritative just because they were found.

When all credible gathered evidence consists of unambiguous `meteo.tn` reports agreeing on one derived date, the runner can validate it without asking DeepSeek. Additional official/news evidence or ambiguous reports go through the full extraction and conflict checks. The runner sends only titles, snippets, source names, and URLs to DeepSeek, and asks for strict JSON extraction. Returned quotes must occur in the supplied source text, and the claimed event/date must be supported by that evidence. DeepSeek is not treated as the source of truth; it only extracts claims from the gathered source material.

For `meteo.tn`, the runner also fetches the official crescent visibility pages directly instead of relying only on search snippets. These pages are treated as primary official astronomy evidence. For Ramadan, if the INM report says the crescent becomes visible after sunset on a given Gregorian date, the runner derives the first fasting day as the following Gregorian day. For Aid el-Adha, the runner derives 1 Dhul Hijja as the day after official Dhul Hijja crescent visibility, then derives Aid el-Adha as 10 Dhul Hijja. Page publication dates and imsakiyya publication dates are not treated as event dates unless the page explicitly says they are the relevant Hijri day.

The script updates `data/official-islamic-dates/{hijriYear}.json` only when validation passes:

- The detector reports `high` confidence.
- The selected Gregorian date is close to the expected Hijri event date.
- The claim is an announced official decision, not a prediction.
- Official `meteo.tn` crescent visibility reports can be accepted as official astronomy evidence when they clearly imply the date.
- Crescent visibility extraction requires affirmative evidence for one date. Negative, conditional, conflicting or ambiguous visibility statements cannot become confirmed dates.
- Evidence comes from an official source, or from at least two distinct trusted Tunisian news sources that report the same official announcement.
- No conflicting announced dates are found.
- The combined yearly dates and adjacent-year anchors can be connected by Hijri months of 29 or 30 days, matching the Android calendar's constraint.
- An existing non-null JSON date is never overwritten automatically.

Network timeouts and disconnects skip the affected source and continue to the remaining sources. Malformed model fields are rejected, and a failed event does not prevent checking other requested events. The report includes failures alongside any valid partial updates. With no valid update, extraction/provider failures cause a failed run so they remain visible in Actions.

When a valid update is detected, the workflow opens or updates an **open** pull request instead of pushing to the default branch. A previously merged or closed PR does not prevent creating a new one. Before detection, only pending official-date JSON is merged with the default branch; pending PR code is never executed. Nonoverlapping date edits and reviewer corrections survive later runs, while conflicting edits fail for human resolution. Unchanged pending data is not force-pushed repeatedly. Each written event keeps its own update timestamp, so announcing Adha does not change the freshness of retained Ramadan/Fitr dates. The PR body includes the selected date, confidence, validation result, source links and evidence summary. A human should still review the sources before merging.

No DeepSeek call happens on user devices. The Android app only fetches the final small JSON file from GitHub Pages.

Offline runner regression checks use `python -B -m unittest discover -s scripts -p "test_lunar*.py"`. They exercise extraction, evidence validation, source failures, calendar consistency and pending-PR merges without contacting external services or changing published dates.

The publisher and app share `test-data/islamic-calendar/1447.json` as a contract fixture. The Python test builds that exact payload through successive announcements. Shared JVM tests fetch/parse, cache, reload, and resolve it through the same calendar and Ramadan/Aid functions used by Android. The fixture's timestamps are fixed test inputs, not new announcements for publication.

Calendar regressions cover all supported baseline month boundaries, independently valid combinations of one-day corrections, adjacent years, impossible anchors, and provisional day 30 becoming either confirmed day 30 or day 1 of Shawwal. Cache tests use isolated storage, a fake clock and controlled requests to verify per-event freshness, legacy migration, restart recovery, retry timing and concurrent request deduplication.

Android Robolectric tests render the actual calendar on API 26 and 35 with Arabic RTL layout and 200% system text scaling. They check complete date labels, reachable controls in narrow/short windows, selection bounds, live announcement updates, and civil-date conversion around time-zone and daylight-saving boundaries. These tests do not require a phone or a release APK. Live source availability, GitHub publishing and physical-device rendering remain separate deployment/device checks.

The `Islamic Date Tests` workflow runs the offline Python suite on relevant pull requests and pushes, including date-only changes. It checks all committed announcement files for valid dates and compatible lunar months across years. The existing shared and Android test workflows run the app-side regressions; shared fixture changes also trigger the shared suite.

## Validation Checklist

After changing an official-date file:

1. Confirm the JSON is valid and contains the correct `hijriYear`.
2. Open the raw GitHub Pages URL and verify the published file returns HTTP 200.
3. Verify `ramadanStart`, `eidFitrDate`, and `eidAdhaDate` are either ISO dates or `null`.
4. Run the shared tests that cover Ramadan detection, drift, polling, and Aid visibility.
5. In the Android app, verify the Ramadan badge and Aid rows on the corrected dates.

Useful test targets:

```bash
cd multiplatform
./gradlew :shared:javaTest
```

For Android-only changes that touch the UI or preferences behavior:

```bash
cd android-app
./gradlew :app:testDebugUnitTest
```

To run just the added Android calendar checks:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.tunisianprayertimes.ui.HijriCalendarDatesTest' --tests 'com.tunisianprayertimes.ui.HijriCalendarUiTest'
```

On Windows, use `gradlew.bat` in place of `./gradlew`.

## When A Code Change Is Needed

Most date corrections should only update the JSON file. Change code only when the correction model itself changes, for example if the app needs to support another officially announced date, a different polling window, or a different visibility rule for Aid rows.

When changing code, update the tests in the shared module first around these behaviors:

- Override parsing with null and non-null dates.
- Ramadan detection with delayed and early official starts.
- 29-day and 30-day Ramadan endings.
- Aid el-Fitr and Aid el-Adha visibility windows.
- Drift calculation from Ramadan start or Aid el-Fitr.
