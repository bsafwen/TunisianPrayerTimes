# Ramadan And Aid Date Corrections

This app uses the device's Hijri calendar as a fallback, but Ramadan and Aid dates should follow the official Tunisian announcement when it differs from the algorithmic calendar. Corrections are done by publishing a yearly override JSON file, not by changing app code for every lunar-year announcement.

## What The Override Controls

The remote override can correct three official dates for a Hijri year:

- `ramadanStart`: the Gregorian date of 1 Ramadan.
- `eidFitrDate`: the Gregorian date of Aid el-Fitr, 1 Shawwal.
- `eidAdhaDate`: the Gregorian date of Aid el-Adha, 10 Dhul Hijja.

The Android app fetches the file from GitHub Pages:

```text
https://bsafwen.github.io/TunisianPrayerTimes/ramadan-override-{hijriYear}.json
```

The source file lives in the repository GitHub Pages folder:

```text
docs/ramadan-override-{hijriYear}.json
```

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
- `RamadanDetector` decides whether the app should behave as Ramadan-active.
- `MainScreen` asks `RamadanOverrideChecker` whether to show the Aid el-Fitr and Aid el-Adha prayer rows.
- `PrefsManager.applyRamadanIshaOverrideIfNeeded` uses `RamadanDetector` to apply the one-time Ramadan Isha silence duration bump.

If the override has `ramadanStart`, Ramadan is considered active from that date until `eidFitrDate` if known. If `eidFitrDate` is not known yet, the app assumes a 30-day Ramadan until the official Aid el-Fitr date is published.

The app also keeps the existing Ramadan buffer behavior: the day before Ramadan and Aid el-Fitr day are treated as Ramadan-active. This keeps the Ramadan UI and Isha silence behavior available around boundary days.

## Aid El-Fitr Correction

When the Tunisian authorities announce Aid el-Fitr:

1. Open or create `docs/ramadan-override-{hijriYear}.json`.
2. Set `eidFitrDate` to the official Gregorian date.
3. Update `lastUpdated`.
4. Publish the `docs/` folder through the normal GitHub Pages deployment.

The app will then use the corrected date for:

- Showing the Aid el-Fitr prayer row.
- Computing whether the displayed day is Aid el-Fitr.
- Ending the Ramadan-active period at the correct official date.
- Predicting Aid el-Adha drift until the Aid el-Adha date itself is announced.

The Aid el-Fitr row is shown from two days before Aid through Aid morning. On the Aid day itself, it disappears after Dhuhr when the displayed day is today.

## Aid El-Adha Correction

When the Tunisian authorities announce Aid el-Adha:

1. Open `docs/ramadan-override-{hijriYear}.json` for the same Hijri year.
2. Set `eidAdhaDate` to the official Gregorian date.
3. Update `lastUpdated`.
4. Publish the updated JSON to GitHub Pages.

Before `eidAdhaDate` is known, the app estimates Aid el-Adha from the algorithmic 10 Dhul Hijja date plus the latest known drift. The drift is taken from `eidFitrDate` when available, or from `ramadanStart` otherwise. Once `eidAdhaDate` is set, it takes precedence over the drift estimate.

The Aid el-Adha row follows the same visibility rule as Aid el-Fitr: visible from two days before Aid through Aid morning, then hidden after Dhuhr on Aid day when today is being displayed.

## Ramadan Start Correction

When Ramadan start is announced:

1. Open or create `docs/ramadan-override-{hijriYear}.json`.
2. Set `ramadanStart` to the official Gregorian date.
3. Leave `eidFitrDate` and `eidAdhaDate` as `null` until announced.
4. Update `lastUpdated`.
5. Publish the file to GitHub Pages.

This lets the app correct the Ramadan banner and the one-time Isha silence duration behavior without shipping a new Android release.

## Polling Windows

The app does not poll all year. It starts polling only near dates where an official announcement may change the calendar:

- Ramadan start: late Shaaban and the first two algorithmic Ramadan days.
- Aid el-Fitr: from the real 29th Ramadan when `ramadanStart` is known, or from algorithmic 28 Ramadan as a fallback.
- Aid el-Adha: late Dhul Qidah through early Dhul Hijja, adjusted by any known Ramadan/Aid el-Fitr drift.

Fetched data is cached in app preferences under `ramadan_override_json`, so users keep the last successful correction even if the next launch is offline.

## Automated Detection Runner

The repository includes a GitHub Actions workflow that can prepare these JSON corrections automatically:

```text
.github/workflows/check-islamic-dates.yml
scripts/detect_tunisian_lunar_dates.py
```

The workflow runs several evening checks during the relevant announcement months, but the script exits without doing network work unless the current approximate Hijri date is close to one of the announcement windows:

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

When direct `meteo.tn` evidence clearly derives the date, the runner can validate it without asking DeepSeek. Otherwise, the runner sends only titles, snippets, source names, and URLs to DeepSeek, and asks for strict JSON extraction. DeepSeek is not treated as the source of truth; it only extracts claims from the gathered source material.

For `meteo.tn`, the runner also fetches the official crescent visibility pages directly instead of relying only on search snippets. These pages are treated as primary official astronomy evidence. For Ramadan, if the INM report says the crescent becomes visible after sunset on a given Gregorian date, the runner derives the first fasting day as the following Gregorian day. For Aid el-Adha, the runner derives 1 Dhul Hijja as the day after official Dhul Hijja crescent visibility, then derives Aid el-Adha as 10 Dhul Hijja. Page publication dates and imsakiyya publication dates are not treated as event dates unless the page explicitly says they are the relevant Hijri day.

The script updates `docs/ramadan-override-{hijriYear}.json` only when validation passes:

- The detector reports `high` confidence.
- The selected Gregorian date is close to the expected Hijri event date.
- The claim is an announced official decision, not a prediction.
- Official `meteo.tn` crescent visibility reports can be accepted as official astronomy evidence when they clearly imply the date.
- Evidence comes from an official source, or from at least two distinct trusted Tunisian news sources that report the same official announcement.
- No conflicting announced dates are found.
- An existing non-null JSON date is never overwritten automatically.

When a valid update is detected, the workflow opens or updates a pull request instead of pushing to the default branch. The PR body includes the selected date, confidence, validation result, and evidence summary. A human should still review the sources before merging.

No DeepSeek call happens on user devices. The Android app only fetches the final small JSON file from GitHub Pages.

## Validation Checklist

After changing an override file:

1. Confirm the JSON is valid and contains the correct `hijriYear`.
2. Open the raw GitHub Pages URL and verify the published file returns HTTP 200.
3. Verify `ramadanStart`, `eidFitrDate`, and `eidAdhaDate` are either ISO dates or `null`.
4. Run the shared tests that cover Ramadan detection, drift, polling, and Aid visibility.
5. In the Android app, verify the Ramadan badge and Aid rows on the corrected dates.

Useful test targets:

```bash
cd multiplatform
./gradlew :shared:test
```

For Android-only changes that touch the UI or preferences behavior:

```bash
cd android-app
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 17.0.9-amzn >/dev/null
./gradlew :app:testDebugUnitTest
```

## When A Code Change Is Needed

Most date corrections should only update the JSON file. Change code only when the correction model itself changes, for example if the app needs to support another officially announced date, a different polling window, or a different visibility rule for Aid rows.

When changing code, update the tests in the shared module first around these behaviors:

- Override parsing with null and non-null dates.
- Ramadan detection with delayed and early official starts.
- 29-day and 30-day Ramadan endings.
- Aid el-Fitr and Aid el-Adha visibility windows.
- Drift calculation from Ramadan start or Aid el-Fitr.