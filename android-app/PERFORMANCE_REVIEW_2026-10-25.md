# Performance review, 2026-10-25

What users can feel, feature by feature. 22 reviewers read the code (one per feature slice); each finding then went to a skeptic told to refute it. 130 of 136 findings survived (6 refuted). The slow paths were then measured on the Pixel 6 (Android 16).

**How the phone was measured.** The `.dev` debug build is debuggable, so it runs interpreted and inflates UI timings 3 to 5 times. For realistic numbers I also built a throwaway copy of the same variant with R8 on, not debuggable but profileable, into the scratchpad. The project's build files were not touched (an `--init-script` override). Numbers marked *release-like* come from that build. Waits set by timeouts (location) are the same on both builds.

## Items 1–4 fixed (same day, uncommitted)

Same phone, same release-like build recipe, before → after:

| Flow | Before | After |
|---|---|---|
| «حدّد موقعي» indoors | result at 8.0–8.6 s, no spinner | result at 1.1–1.3 s, spinner while it works |
| Cold start, CPU in the first 9 s (Tunisia) | 4.1 s (2.6 s on background threads) | 2.0 s (1.0 s) |
| Native memory 3 s after launch | 279 MB (PSS 475 MB) | 21 MB (PSS 189 MB); settled PSS 145 MB (was 239) |
| First Quran-tab open, catalog ready | ~2.5 CPU-s (verse taps dead) | ~0.23 CPU-s |
| Cold start in Mecca | 1.0 s, first frame frozen 0.86 s, main thread 1.1 CPU-s | content in ~0.4 s, no long frame; first launch after an install shows a blank screen ~0.75 s while the scan runs in the background |
| Place change to Mecca (locate) | re-plan and scan on the main thread | no long frame |

What changed:
1. **Locate:** the button uses a new wait (`awaitPreciseEnough`). A GPS fix or a live fix within 50 m answers at once; after fused and network, GPS gets 2 s, not 8 s. A spinner shows meanwhile. The Qibla refinement keeps the full GPS wait (`detectCurrentLocation` unchanged).
2. **Abroad:** `ValidityGuard` runs one scan per verdict and keeps verdicts across launches. They are cleared on every install, keyed by the serialized method. A new place's first days are computed off the main thread before it is stored. The main screen waits for them on a blank screen once per process. `onPlaceChanged` runs off the main thread, one at a time; only the latest pick or lookup is stored.
3. **Quran:** the whole-text conversion is lazy, and `QuranMushafText.word` runs a regex only where it can match. The output is identical for all 77,877 words.
4. **Picker:** the name folding uses code-point loops, identical on 57,996 names. The preparation starts 3 s after the prayer tab shows, or when the sheet opens; the sheet shows a spinner until its names are ready.

Tests: 118 related tests pass, including a new `PreciseLocationWaitTest`. The same 4 locality/locator tests fail as on a clean HEAD: their expectations predate the 2026-10-19 data update. Shared module: 560 of 561 pass; `DhikrCitationsTest` fails because of today's citation work.

## Second round: each fix validated against its feature, then kept

The owner's rule for this round: every fix is checked against the feature it touches before it stays. Old and new builds were compared on the Pixel 6 with the same inputs.

| Fix | How it was validated | Result |
|---|---|---|
| 1. Locate | 5,000 random arrival sequences, old rule against new. A/B on the Pixel with mock fixes in two Tunis neighbourhoods | New rule never loses a fix and is never slower; it answers differently only with a fix of the last 30 s. Same stored place as the old build at both points |
| 2. Abroad verdicts | 120 towns in 15 countries, 480 days: fresh scan vs verdict read back from the store; 4 concurrent callers | Identical times (67 verdicts, one where the guard acts); one scan for the 4 callers |
| 3. Quran | Old vs new converter on all 77,877 words; pinned SHA test; 3 verse taps on the Pixel | Identical words; pixel-identical highlights and verse cards |
| 4. Picker | Old vs new folding on 57,996 names; 8 typed searches on the Pixel | Identical; identical rows on both builds |
| 5. Scheduling off the main thread | `dumpsys alarm` and ringer/DND on both builds: cold start, a prayer's silence switched off and on, manual silence on/off, silencing inside a window, a double tap on «إيقاف الإسكات» from vibrate | Identical alarms and states. A double tap now restores vibrate; the old build silenced again |
| 6. Images | Screenshots of the prayer hero and the Adhkar cards on both builds | Same image (mean pixel difference 0.08 and 0.33); no placeholder on an early visit |
| 7. Background loops | CPU while in the background for 60 s; the countdown after returning | 3.3 s → 1.0 s on debug (0.19 s release-like); countdown correct on return |

**Found and fixed during validation:**
- **Locate, stale fixes.** Fused could hand back a fix from minutes ago, from a place since left, and end the wait. The A/B showed it: the new build stored the previous point's neighbourhood. Now only a fix of the last 30 s ends the wait early, and the fused request on this path accepts no older one. The A/B then matched.
- **Locate, Qibla.** The Qibla's refinement near the Kaaba keeps the full GPS wait.
- **Locate, threshold.** «Good enough» is 30 m, not 50 m, so the neighbourhood name is kept near borders.
- **Scheduling, review findings.** A review of item 5 found 7 races. All are fixed:
  - ending a manual silence is one locked step;
  - a second tap waits for the first;
  - work started from the screen is not dropped if the screen goes;
  - the receiver's «force normal» skips a silence started meanwhile.

**Left alone on purpose:**
- **The search «flicker» (item 8).** The picker's code says it shows nothing rather than another query's results.
- **Whack-a-mole timing.** The challenges were tuned yesterday, with the HARD timing left to the owner.
- **The adhkar counter's `commit()`.** A persistence fix depends on it.
- **The reminder editor's year check.** It is cached, and making it asynchronous changes the form.
- **The Qibla needle tuning.**
- **A baseline profile.** It needs a benchmark module.

**Final numbers, release-like, this round:**
- **Cold start:** 1.9 CPU-s in the first 9 s (was 4.1).
- **Background CPU:** 0.19 s per minute.
- **Locate indoors:** 1.2 s (was 8.0–8.6).
- **Tab switches and returning to the app:** the same as the old build, measured alternately at the same moment.

Unit tests: everything that touches these fixes passes. Three failures are unrelated, and each fails the same way on a clean HEAD:
- the locality/locator expectations from before the 2026-10-19 data;
- `AlarmCoverageTest`, which depends on the clock and fails inside a silence window;
- `WakeScheduleDayTextTest`, where the JDK's locale data spells the day «الاثنين».

## Measured on the Pixel 6 (before the fixes)

| Flow | Result | Build |
|---|---|---|
| «حدّد موقعي» indoors | Usable 14 m fix after **1.0–1.1 s**; result shown after **8.0–8.6 s**; the button shows nothing meanwhile | both |
| «حدّد موقعي» with a fast fix (mock GPS) | Name updates **0.1 s** after the tap | release-like |
| Cold start, place in Tunisia | 0.33 s to first frame, then **4.1 CPU-s in the next 9 s**, 2.6 s of it on background threads | release-like |
| Cold start, place in Mecca (mock GPS) | **1.0 s** to first frame; the first frame alone takes **0.86 s**; main thread 1.1 CPU-s (0.46 s in Tunisia) | release-like |
| Cold start, place in France | 0.33 s; main thread +0.1 CPU-s | release-like |
| Memory 3 s after launch | Native heap **279 MB**, PSS 475 MB; 18 s later 98 MB and 239 MB | release-like |
| First Quran-tab open in a process | **~2.4 CPU-s** converting the whole mushaf text, **~1.8 CPU-s** of GC behind it; verse taps dead until done | release-like |
| Back to the prayer tab | One **~100 ms** frame each time (300–450 ms on debug) | release-like |
| Alarms / «عباداتي» / adhkar tab switch | Worst frame 57–85 ms | release-like |
| Return to the app and scroll at once | ~2 janky frames, worst 22 ms | release-like |
| Quran page turns | p95 ~20 ms, ~1 janky frame per swipe: fine | release-like |
| Adhkar reader (open, next, previous), alarm editor (open, scroll) | Smooth: p95 8–26 ms | debug |
| Place search typing | 32% janky frames, p90 42 ms | debug |

## Ranked: what users feel most

### 1. «حدّد موقعي» takes 8 s and gives no sign of life
- **Measured:** the phone had a 14 m fix after 1.0–1.1 s, but the result came at 8.0–8.6 s. With a fix that arrives at once, the name updates in 0.1 s, so the whole wait is the waiting policy.
- **Cause:** `findCurrentLocationInternal` (`DelegationLocator.kt:387-398`) starts fused + GPS + network + passive and calls `requests.awaitAll()`. Indoors GPS never answers, so every tap waits for its 8 s timeout. The button (`MainScreen.kt:1941-1967`) only disables its click: no spinner. Onboarding (`OnboardingScreen.kt:133`) goes through the same path.
- **Fix (S–M):** keep a best-so-far fix and return as soon as a live fix reaches about 50 m. When fused and network have both answered with 100 m or better, give GPS a short grace of about 2 s, not 8 s. Drop PASSIVE from the live sources and cancel GPS once satisfied. `awaitFirstMatching` (`DelegationLocator.kt:619`) and the Qibla card's pattern (`QiblaCard.kt:351-370`) already do most of this. Show a spinner on the button while `locating`.

### 2. With a place in Saudi Arabia (and other official-zone countries), every launch freezes for about 1 s
- **Measured:** with a mock fix in Mecca, cold start went from 0.33 s to 1.0 s and the first frame took 0.86 s. This hits Umrah and Hajj travellers, the first users abroad.
- **Cause:** `ValidityGuard.acts` (`shared: place/ValidityGuard.kt:28`) scans a whole year (about 1,460 engine runs with VSOP87) the first time a year is used. It runs inside `remember {}` blocks during composition (`MainScreen.kt` 2089-2091, 2217-2219, 2315, 2389, 2709-2717). Results are only cached in memory, so every process pays again. On a mid-range phone this is likely 2–3 s.
- **Same family:** picking a city abroad, or a GPS fix abroad, runs `PlaceResolution.onPlaceChanged` on the main thread (`MainScreen.kt:2033`, `1825`): cache reset, zone JSON parse, full reschedule, a 366-day check per adhkar reminder. Estimated 0.3–2 s freeze, not measured.
- **Fix (M):** compute the displayed prayer days off the main thread (`produceState` on `Dispatchers.Default`, keep the previous rows while loading). Deduplicate in-flight scans in `ValidityGuard`. Persist verdicts with a stable key (not `method.hashCode()`, which changes between processes). Warm the current year in the background. Make `onPlaceChanged` main-safe and run it from an app-lifetime scope, one call at a time.

### 3. First Quran-tab open: verse taps do nothing for 2–3 s
- **Measured:** about 2.4 CPU-s in `QuranMushafText.words` (called from `QuranCatalog.<init>`), plus about 1.8 CPU-s of GC from the ICU regex matchers.
- **Cause:** `displayEntries` (`QuranRepository.kt:46-55`) eagerly converts all 77,877 words, about 10 regex operations each. Until the catalog exists, `verseAt` returns null, so a verse tap toggles the toolbars, and with «مراجعة» on the page stays under the cover and spinner. The first play waits for the same catalog (`QuranPlaybackService.kt:323`).
- **Fix (M):** make `displayEntries` and `verseFragments` lazy, so a page converts only its own verses. In `QuranMushafText`, replace the `drop` Regex with a char filter and guard each regex with a cheap `contains`/`startsWith` check. The output must stay identical: the existing whole-text test against `mushaf_text.py` guards that.

### 4. Every launch burns about 2.5 CPU-s preparing the place picker nobody opened
- **Measured:** 2.6 of 4.1 CPU-s after launch are on background threads. About 52% is `LocalityRepository.preparePicker` and about 37% is `CitySearch.Index` (26,600 city names), dominated by `Regex.replace`. Each regex matcher has a native peer, which explains the 279 MB native peak and the GC churn. This competes with the first seconds of use and drains the battery on every launch.
- **Cause:** `LocationPickerCard` starts both `produceState` blocks at first composition (`MainScreen.kt:1710-1718`). `preparePicker` runs twice at launch: once for the initial delegation list, then again when `loadAllDelegations` returns a different list.
- **Fix (S–M):** build both indexes when the sheet first opens (show the list at once, and results once ready), or once after the first frame on a low-priority thread. Replace the normalisation regexes (`LocalityRepository.kt:87-90`, `CitySearch.kt:17-18`) with char loops.

### 5. Silence and alarm scheduling runs on the main thread, often twice
- **Where:** every resume (`MainScreen.kt:498-521`), every settings tap, slider release and alarm save (`rescheduleIfEnabled`, `MainScreen.kt:568-577`). These call `SilenceScheduler.scheduleAll` and `WakeAlarmScheduler.scheduleAll`, about 130–180 binder calls per run, on Main. `rescheduleIfEnabled` then bumps `refreshTick`, which runs the whole resume sync again. The Application's date collector (`TunisianPrayerTimesApplication.kt:72-90`) does it on Main before the first frame.
- **Also:** «إعادة الصوت» calls `SilenceModeController.restoreState`, which does `Thread.sleep(150)` on the main thread (`SilenceModeController.kt:51`).
- **Measured:** mild on the Pixel 6 (about 2 janky frames per return); heavier on low-end phones.
- **Fix (M):** one single-threaded background dispatcher for all scheduling. Skip a re-arm when a fingerprint (times, rules, permissions, wake-store version) has not changed. Do not bump `refreshTick` from `rescheduleIfEnabled`. Move `restoreState` callers to that dispatcher; keep its synchronous sleep there.

### 6. Oversized images decoded on the main thread
- `prayer_hero_background.webp` (1440×600) sits in `res/drawable/`, so Android treats it as mdpi. On the Pixel 6 it decodes to a 3780×1575 bitmap, **24 MB**, on the start tab. **Fix (S): move the file to `res/drawable-nodpi/`**, as the adhkar scenes already are: 3.5 MB, no visual change.
- The adhkar morning and evening scenes (1350×1165 PNG, 6.3 MB each decoded) are decoded again on the main thread at every adhkar-tab visit (`AdhkarVisualCards.kt:65`). **Fix (S):** decode once per process, off the main thread, at card size, and reuse.

### 7. The prayer tab itself is heavy
- **Measured:** one frame of about 100 ms each time you come back to it.
- **Cause:** a non-lazy column; prayer times computed in composition (`prayerOccurrences`, `upcomingPrayers`); the night silhouette paths; several `textMeasurer.measure` calls per prayer row. The day overview rebuilds about 220 paths every 5 s even off-screen (`MainScreen.kt:2727`). The 1 Hz silence poll keeps running in the background (`MainScreen.kt:560`).
- **Fix (M):** move the time computations out of composition; keep paths in `remember` or a cached `drawWithCache`; stop the 5 s and 1 s loops when not visible (`repeatOnLifecycle`).

### 8. Typing in search makes results flicker
- **Place picker:** the list is swapped for a spinner on every keystroke (`LocalityPickerSheet.kt:109`); late city results land above the visible list (`:151`); before the catalogues are ready it says "no results" (`:149`).
- **Quran search:** results blank on every keystroke (`QuranSearchUi.kt:83`); a superseded search can't be cancelled (`:112`).
- **«المراجعة»:** the verse finder (`HifzSheets.kt:947`) and the notebook search (`HifzHome.kt:470`) have the same issues.
- **Fix (S each):** keep the previous results visible until new ones arrive; debounce about 150 ms; make the search cancellable.

### 9. Smaller but clearly felt
- **Alarm editor with a phone ringtone:** the title is resolved with a `MediaPlayer.prepare()` in composition at every recomposition, and the players leak (`WakeRingtoneCatalog.kt:77`). The preview prepares on the main thread (`WakeRingtonePreviewPlayer.kt:21`). S.
- **Adhkar:**
  - The reminder editor rescans 366 days of times on the main thread for every day chip or offset step (`AdhkarReminderEditor.kt:157`). M.
  - Each counter tap rewrites the whole state with `commit()` and re-runs the reminder scheduler (`DhikrRepository.kt:487`). M.
- **Qibla:**
  - The needle updates at about 15 Hz with smoothing, so it steps and trails (`QiblaCard.kt:1689`).
  - «aligned» appears 2.5–4 s after the phone is held still (`:637`).
  - The sensors stay on while the card is scrolled away (`:1688`).
- **Wake challenge:** whack-a-mole counts a hit only when the finger lifts (`WhackAMoleGame.kt:220`). S.
- **No baseline profile:** after each install or update, app code runs interpreted or JIT until the cloud profile arrives. A Macrobenchmark-generated profile for startup and the five tabs helps every launch. M.

### Lower priority
- **Quran reader recomposition breadth:**
  - The static CompositionLocal gets a new lambda on every pass (`QuranScreen.kt:2098`).
  - Page-change side effects (`:509`, `:1055`).
  - The dock strip drag (`:2702`).
  - Download progress (`QuranDownloads.kt:56`).
  - Measured page turns are fine on the Pixel 6, so this mainly matters on low-end phones.
- **Quran audio:** the 10 Hz position poll repaints the dock and tab bar (`QuranDock.kt:96`); each verse step re-prepares the player (`QuranPlaybackService.kt:233`, `:683`).
- **Background:** before each prayer, high-accuracy location checks wake GPS that the background cannot use (`SilenceScheduler.kt:310`).

## A bug found while measuring (not performance)

With «تحديث الموقع تلقائيًا» on (the default), a place picked by hand snaps back to the GPS place within a second whenever the phone has a fix under 5 minutes old. I reproduced it twice on the Pixel (Tunis, then France).

Path:
1. Picking a place calls `onDelegationSelected`, which calls `rescheduleIfEnabled` (`MainScreen.kt:568`).
2. That bumps `refreshTick++`, which restarts `LaunchedEffect(refreshTick)` (`MainScreen.kt:498`).
3. The effect calls `DelegationLocator.updateDelegationFromLastLocation`, which stores the GPS place again.

A manual pick never turns auto-location off, so the next resume would revert it anyway.

## All verified findings, by feature

Impact is the skeptic's corrected value, not the reviewer's claim. Effort: S = small, M = medium, L = large. Paths are under `app/src/main/java/com/tunisianprayertimes/` unless marked `shared:` (the multiplatform module).

### «حدّد موقعي» (prayer tab and onboarding)

| Verified impact | Finding | Where | Effort |
|---|---|---|---|
| high | Main-screen locate button gives no feedback during the multi-second lookup | `ui/MainScreen.kt:1947` | S |
| medium | Locate waits for the slowest location source (about 8 s indoors) even when a good fix is already in hand | `DelegationLocator.kt:396` | M |
| low | Leaving the prayer tab during the lookup cancels it silently | `ui/MainScreen.kt:1767` | M |
| low | First locate in a process builds the 15 MB neighbourhood index only after the fix arrives (onboarding pays the JSON parse too) | `DelegationLocator.kt:268` | S |
| low | A fix abroad: the place-change work runs on the main thread and freezes the UI | `ui/MainScreen.kt:1825` | S |
| low | A fix outside Tunisia loads the whole 15 MB neighbourhood index just to answer 'not in Tunisia' | `DelegationLocator.kt:96` | S |
| low | After a Tunisian fix, alarm rescheduling runs on the UI thread before the new name draws | `ui/MainScreen.kt:1774` | S |

### Cold start and resume

| Verified impact | Finding | Where | Effort |
|---|---|---|---|
| medium | Every resume re-arms all silence and wake alarms on the main thread | `ui/MainScreen.kt:498` | M |
| low | No baseline profile: app code runs interpreted on cold start | `app/build.gradle.kts:25` | M |
| low | First composition parses JSON assets on the main thread (2 MB abroad) | `ui/MainScreen.kt:1720` | M |
| low | Every cold start eagerly parses about 17 MB of locality assets and keeps them in memory | `ui/MainScreen.kt:1710` | M |
| low | Application re-arms silences and wake alarms on Main before the first frame of every process | `TunisianPrayerTimesApplication.kt:80` | S |
| low | First launch composes the whole main screen behind the onboarding | `MainActivity.kt:196` | S |
| low | Synchronous system and Firebase calls in Application.onCreate and MainActivity.onCreate | `AnalyticsTracker.kt:21` | S |
| low | Main screen polls the silence state every second, even in the background | `ui/MainScreen.kt:560` | S |

### Prayer times, dates and places abroad

| Verified impact | Finding | Where | Effort |
|---|---|---|---|
| high | Year-long validity-guard scan runs on the main thread for places abroad | `shared: place/ValidityGuard.kt:28` | M |
| low | Cold alarm processes abroad redo the year scan before silencing | `SilenceReceiver.kt:38` | S |
| low | Official-zone JSON of up to 2 MB is parsed on the main thread on first use | `OfficialZoneAssets.kt:46` | S |
| low | Hijri calendar redoes 39 text layouts and every day cell on each tap | `ui/HijriCalendarDialog.kt:164` | S |
| low | Calendar row polls the network every minute for years with no announcement | `ui/CalendarLifecycle.kt:87` | S |

### Prayer tab cards

| Verified impact | Finding | Where | Effort |
|---|---|---|---|
| medium | Each row edit, and every resume, reschedules all silence and wake alarms on the main thread, twice for an edit | `ui/MainScreen.kt:568` | M |
| medium | The hero background, stored in res/drawable (treated as mdpi), is upscaled to a 3780x1575 bitmap of about 24 MB, decoded on the main thread | `ui/HeroCardSurface.kt:55` | S |
| low | The day overview recomposes and rebuilds about 220 Canvas paths every 5 s, even off-screen | `ui/MainScreen.kt:2727` | M |
| low | Abroad, the first composition parses an official-zone JSON asset of up to 2 MB on the main thread | `OfficialZoneAssets.kt:46` | M |
| low | Any single row change rebuilds every anchor and recomposes all 6-8 prayer rows (the date arrows too) | `ui/MainScreen.kt:2309` | S |
| low | A 1 Hz silence-state poll runs for the whole screen lifetime, on every tab and after Home | `ui/MainScreen.kt:560` | S |

### Silence editing

| Verified impact | Finding | Where | Effort |
|---|---|---|---|
| medium | Every slider release or editor Apply re-arms all silence alarms twice (plus wake alarms twice) on the main thread | `ui/MainScreen.kt:568` | M |
| low | Drag recomposes the slider on every pointer event, with a TextMeasurer cache too small for its labels | `ui/PrayerSilenceRangeSlider.kt:481` | M |
| low | Dhuhr/Jumu'a handle snaps back to its old position during the 365-day fit check after release | `ui/MainScreen.kt:4228` | S |
| low | Manual silence 'Stop' blocks the main thread with Thread.sleep(150) | `SilenceModeController.kt:51` | S |
| low | Each committed edit re-composes all 6-8 prayer rows and re-measures every label | `ui/MainScreen.kt:2309` | S |
| low | Extending the range past the track edge crawls at 5 minutes per second | `ui/PrayerTimelineModel.kt:138` | S |
| low | Silence state is polled every second on the main thread while the screen exists | `ui/MainScreen.kt:560` | S |

### Place picker and place search

| Verified impact | Finding | Where | Effort |
|---|---|---|---|
| high | Picking a city abroad re-plans everything on the main thread | `ui/MainScreen.kt:2033` | S |
| medium | Sheet opened before the catalogues are ready says 'no results' instead of loading | `ui/LocalityPickerSheet.kt:149` | M |
| medium | Every keystroke swaps the list for a spinner | `ui/LocalityPickerSheet.kt:109` | S |
| medium | Late city results land above the visible list | `ui/LocalityPickerSheet.kt:151` | S |
| none | 26,600-city search index is rebuilt every time the Prayer tab is shown — *checked on the Pixel: no measurable rebuild (+20 ms CPU over 3 returns); dropped* | `ui/MainScreen.kt:1716` | S |
| low | Picking a Tunisian place reschedules silences on the main thread as the sheet closes | `ui/MainScreen.kt:569` | S |
| low | First composition of the place header parses locality JSON on the UI thread | `ui/MainScreen.kt:1720` | S |

### Calculation method screens

| Verified impact | Finding | Where | Effort |
|---|---|---|---|
| medium | Method list runs each method's full-year validity guard and today's times on the main thread | `ui/PrayerMethodSection.kt:360` | M |
| medium | Every method-setting tap runs the whole schedule refresh on the main thread | `ui/PrayerMethodSection.kt:87` | S |
| low | Expanding «التفاصيل» composes about 15 diagrams and about 120 text measurements in one frame | `ui/PrayerTimeMethodDialog.kt:251` | M |
| low | Diyanet rows rebuild a full-year takdir table on every list or chooser open (Season cache of 12 thrashes) | `shared: engine/Season.kt:245` | S |
| low | «تفاصيل طريقة الحساب» recomputes on the main thread after browsing, and the year strip pops in and shifts the content | `ui/MethodExplainer.kt:80` | S |
| low | Diagram canvases are re-recorded (paths and text) on every ripple frame of the method dialog | `ui/PrayerTimeMethodExplorer.kt:189` | S |

### Quran: page rendering

| Verified impact | Finding | Where | Effort |
|---|---|---|---|
| high | Cold open: page covered or verse taps dead while 77,877 words go through ICU regexes | `quran/QuranRepository.kt:53` | M |
| medium | Every page turn re-runs the whole 2,640-line reader 3-4 times, twice during the animation | `ui/QuranScreen.kt:509` | M |
| low | Full-resolution 9.7 MB page bitmaps are uploaded to the GPU in the first frame of the swipe | `ui/QuranScreen.kt:3793` | M |
| low | No decoded-page cache: jumps decode 3 full pages at once before the target shows | `ui/QuranScreen.kt:3767` | M |
| low | «مراجعة» redraws the page every frame for 8 s after each erase | `ui/QuranScreen.kt:4057` | S |

### Quran: reader (part 1)

| Verified impact | Finding | Where | Effort |
|---|---|---|---|
| medium | Every page swipe recomposes the entire 2,640-line QuranReader 4-6 times, twice mid-gesture | `ui/QuranScreen.kt:509` | M |
| medium | Dragging the dock's verse strip recomposes the whole reader UI and all 3 pages on every touch frame | `ui/QuranScreen.kt:2702` | S |
| low | Every visit to the Quran tab re-decodes 3 full-resolution scans with no cache before the page shows | `ui/QuranScreen.kt:3793` | M |
| low | Static CompositionLocal gets a new lambda on every recomposition, so the whole reader subtree cannot skip | `ui/QuranScreen.kt:2098` | S |
| low | During each surah's opening, the reader recomposes 10 times a second for the playback clock | `ui/QuranScreen.kt:3582` | S |
| low | Each settled page writes and re-reads the hifz ledger on the main thread and re-plans reminders, even with an empty notebook | `ui/QuranScreen.kt:1055` | S |
| low | Night-brightness slider rebuilds the reader and re-applies window attributes twice per drag frame | `ui/QuranScreen.kt:1171` | S |
| low | Per-minute background loops run whether or not the review is open and while the app is in the background | `ui/QuranScreen.kt:727` | S |

### Quran: reader (part 2)

| Verified impact | Finding | Where | Effort |
|---|---|---|---|
| medium | A static CompositionLocal gets a new lambda on every pass, so the whole reader recomposes | `ui/QuranScreen.kt:2098` | S |
| medium | Download progress updates recompose the whole reader several times a second | `ui/QuranScreen.kt:2638` | S |
| low | Each page and verse looked at re-renders the reader 3 times and plans reminders | `ui/QuranScreen.kt:2355` | M |
| low | Every pass rebuilds each page's highlight lists, so page images can never skip | `ui/QuranScreen.kt:2233` | M |
| low | Dragging the dock's verse strip recomposes the reader and all 3 pages every frame | `ui/QuranScreen.kt:2702` | S |

### Quran: search and index

| Verified impact | Finding | Where | Effort |
|---|---|---|---|
| medium | Index and search stay dead for seconds on the first Quran-tab visit while the whole mushaf text is converted | `quran/QuranRepository.kt:53` | M |
| medium | Results, count and chapter chips blank out on every keystroke | `ui/QuranSearchUi.kt:83` | S |
| low | Fuzzy pass scans all 156k words with fresh arrays on most keystrokes of a specific phrase, and re-runs the whole search when nothing matches | `quran/QuranSearch.kt:218` | M |
| low | Each new keystroke waits for the superseded search to finish (collectLatest joins a non-cancellable search) | `ui/QuranSearchUi.kt:112` | S |
| low | Cross-verse pass rebuilds 1,324 page strings and walks every occurrence, even for single-word queries that cannot cross verses | `quran/QuranSearch.kt:207` | S |
| low | About 120 Regex compilations on the main thread per keystroke for the place rows | `ui/QuranSearchUi.kt:203` | S |

### Quran: audio, dock, downloads

| Verified impact | Finding | Where | Effort |
|---|---|---|---|
| medium | First play of a session waits for the whole Quran text catalog | `quran/audio/QuranPlaybackService.kt:323` | S |
| low | Playing from a verse of the surah already loaded rebuilds the MediaPlayer | `quran/audio/QuranPlaybackService.kt:233` | M |
| low | The position poll repaints the dock, the player and the tab bar 10 times a second | `ui/QuranDock.kt:96` | M |
| low | Dragging the dock strip recomposes the whole reader on every finger move | `ui/QuranDock.kt:542` | S |
| low | Each verse step is a loud seek: buttons stop responding and the highlight blinks | `quran/audio/QuranPlaybackService.kt:683` | S |
| low | The reader recomposes 10 times a second during every surah's opening | `ui/QuranScreen.kt:3582` | S |
| low | Download progress recomposes the whole reader every half second | `ui/QuranDownloads.kt:56` | S |
| low | The notification is rebuilt with fresh PendingIntents on the path from tap to sound | `quran/audio/QuranPlaybackService.kt:1107` | S |

### «المراجعة»: screens and sheets

| Verified impact | Finding | Where | Effort |
|---|---|---|---|
| medium | Every reveal, grade and drill step recomposes the whole Quran tab with skipping turned off | `ui/QuranScreen.kt:2098` | S |
| medium | Notebook search reparses the query and renormalizes every verse for every point on the main thread | `ui/HifzHome.kt:470` | S |
| low | The answer's scan is decoded only after «أظهر», with a fresh full-page WebP decode per band | `ui/QuranExcerpt.kt:443` | M |
| low | Verse finder runs a full, uncancellable Quran search on every keystroke and blanks its results | `ui/HifzSheets.kt:947` | S |
| low | Look-alike suggestions are ranked on the main thread when «انتقلت إلى آية شبيهة» is tapped | `ui/HifzSheets.kt:532` | S |

### «المراجعة»: data layer

| Verified impact | Finding | Where | Effort |
|---|---|---|---|
| medium | Notebook search re-parses the query and rebuilds every point's searchable text on each keystroke | `ui/HifzHome.kt:470` | S |
| low | The look-alike index is built only when «المراجعة» first opens, normalizing all 77,877 words | `quran/hifz/HifzText.kt:155` | M |
| low | Every page turn writes the exposure ledger on the main thread, re-arms the reminder and recomposes the whole reader, even with an empty notebook | `ui/QuranScreen.kt:601` | S |
| low | The notebook sheet recomputes notebook-wide plans with uncached needsCare and O(n^2) answerSafeOrder on every recomposition | `quran/hifz/Hifz.kt:645` | S |
| low | Each reminder delivery in a cold process loads and converts the whole Quran text just to print surah names | `quran/hifz/HifzReminder.kt:843` | S |

### Adhkar

| Verified impact | Finding | Where | Effort |
|---|---|---|---|
| medium | Reminder editor rescans 366 days of prayer times on the main thread for every day chip or offset step | `ui/AdhkarReminderEditor.kt:157` | M |
| medium | Two full-size 1350x1165 hero PNGs decoded on the main thread every time the Today page composes | `ui/AdhkarVisualCards.kt:65` | S |
| low | Every counter tap rewrites the whole Adhkar state with commit() and reruns the reminder scheduler, serialized behind one mutex | `adhkar/DhikrRepository.kt:487` | M |
| low | Opening a reading waits for a full-state commit plus a full reminder reschedule, and after entering the tab also for a rearm of every alarm | `ui/AdhkarScreen.kt:158` | M |
| low | Swipe composes and lays out the neighbour dhikr only when the drag starts, and lays the text out again on landing | `ui/AdhkarReader.kt:326` | M |
| low | Library search re-normalizes every dhikr's full text with freshly compiled regexes on each keystroke, on the main thread | `ui/AdhkarScreen.kt:851` | S |
| low | Skip-window resolution reruns in composition on every counter tap for readings opened from a reminder | `ui/AdhkarScreen.kt:469` | S |

### Alarms tab

| Verified impact | Finding | Where | Effort |
|---|---|---|---|
| medium | Every alarm change reschedules all prayer silences and all wake alarms on the main thread | `ui/MainScreen.kt:569` | M |
| medium | Each alarm change bumps refreshTick and re-runs the whole on-resume sync a second time | `ui/MainScreen.kt:572` | S |
| low | Next rings and occurrence windows of every alarm are computed during composition, loading 9 prayer days twice per alarm | `ui/MainScreen.kt:2888` | S |
| low | The Alarms tab's 1-second poll keeps running after the app goes to the background | `ui/MainScreen.kt:2902` | S |

### Alarm editor

| Verified impact | Finding | Where | Effort |
|---|---|---|---|
| medium | Custom ringtone name is resolved by preparing a MediaPlayer on the main thread at every recomposition, and the player is leaked | `wake/WakeRingtoneCatalog.kt:77` | S |
| low | Save keeps the editor up until the DataStore write ends, then runs silence and wake rescheduling twice on the main thread | `ui/MainScreen.kt:1406` | M |
| low | Opening the editor composes the whole 3,700-line sheet in one frame, with no baseline profile for app code | `ui/WakeEditorSheet.kt:610` | M |
| low | Ringtone preview prepares the MediaPlayer synchronously on the main thread at each tap | `wake/WakeRingtonePreviewPlayer.kt:21` | S |
| low | The editor's first frame treats every existing alarm as new, then the title flips and the layout shifts | `ui/MainScreen.kt:1352` | S |
| low | Every edit, including sound-only edits, recomputes the ring preview and the silence check twice in composition on the main thread | `ui/WakeEditorSheet.kt:371` | S |

### Extra alarms

| Verified impact | Finding | Where | Effort |
|---|---|---|---|
| medium | Save re-arms every silence and wake alarm on the main thread, twice | `ui/MainScreen.kt:568` | M |
| low | Phone ringtone titles prepare a MediaPlayer on every recomposition | `wake/WakeRingtoneCatalog.kt:77` | S |
| low | Ringtone preview creates and releases MediaPlayers on the UI thread | `wake/WakeRingtonePreviewPlayer.kt:21` | S |

### Ringing and wake challenges

| Verified impact | Finding | Where | Effort |
|---|---|---|---|
| medium | Every cold start re-arms all silence and wake alarms on the main thread, ahead of the alarm's service and screen | `TunisianPrayerTimesApplication.kt:83` | S |
| medium | Whack-a-mole counts a hit only when the finger lifts | `wake/WhackAMoleGame.kt:220` | S |
| low | Playback service prepares the ringtone synchronously on the main thread before launching the wake screen | `wake/AlarmAudioController.kt:199` | M |
| low | Receiver does config reads, analytics and silence work before handing the ring to playback, and the service repeats it | `wake/WakeAlarmReceiver.kt:45` | M |
| low | Maze recomposes and remeasures every frame, and a second tilt listener stays registered during the game | `wake/GyroscopeMazeGame.kt:627` | S |

### «عباداتي»: Qibla, fasting, remembrance

| Verified impact | Finding | Where | Effort |
|---|---|---|---|
| medium | Compass needle runs at about 15 Hz with per-event smoothing, so it steps and trails the phone | `ui/QiblaCard.kt:1689` | M |
| low | Every sensor event recomposes the whole Qibla card and restarts two effects | `ui/QiblaCard.kt:494` | M |
| low | Away from the saved place, the Qibla waits up to 10 s for a live fix, then gives up, ignoring last-known fixes older than 60 min | `ui/QiblaCard.kt:353` | M |
| low | The «aligned» confirmation comes 2.5-4 s after the phone is held still | `ui/QiblaCard.kt:637` | S |
| low | Four sensor streams stay on, and keep recomposing, while the Qibla card is scrolled out of view | `ui/QiblaCard.kt:1688` | S |

### Onboarding

| Verified impact | Finding | Where | Effort |
|---|---|---|---|
| medium | Onboarding locate waits for every live provider, so the GPS 8 s timeout sets the wait | `DelegationLocator.kt:387` | S |
| low | First fix pays a cold load of about 15 MB (6 MB JSON + 9 MB geometry) before the label appears | `DelegationLocator.kt:268` | M |
| low | First launch composes the whole MainScreen under the onboarding before onboarding can draw | `MainActivity.kt:196` | M |
| low | «ابدأ» with a skipped location parses the 26.7k-row city catalogue on the main thread | `ui/OnboardingScreen.kt:162` | S |
| low | Demo slider re-measures all its legend text on every recomposition during onboarding animations and drags | `ui/PrayerSilenceRangeSlider.kt:190` | S |

### Background scheduling and receivers

| Verified impact | Finding | Where | Effort |
|---|---|---|---|
| medium | Every process start reschedules silence and wake on the main thread, and the first resume does it again | `TunisianPrayerTimesApplication.kt:83` | M |
| medium | App resume and every settings tap reschedule silence and wake on the main thread, twice per tap | `ui/MainScreen.kt:568` | M |
| medium | Every silence restore sleeps 150 ms on the main thread, including straight after a tap | `SilenceModeController.kt:51` | S |
| low | Each location fix loads the 15 MB neighbourhood index just to name a neighbourhood, or to reject a fix abroad | `DelegationLocator.kt:268` | M |
| low | The dhikr scheduler rearms every reminder on many redundant triggers and holds the lock the count taps need | `adhkar/DhikrReminderScheduler.kt:441` | M |
| low | A place change found on app open runs the whole reschedule and a 365-day reminder recheck on the main thread | `DelegationLocator.kt:218` | S |
| low | Pre-prayer and periodic location checks wake the phone for a high-accuracy fix that the background is not allowed to get | `SilenceScheduler.kt:310` | S |

## Refuted by the skeptics

- Each extra-alarm change recomputes hero and silence warning in composition (`WakeEditorSheet.kt:371`)
- Draggable extra-alarm slider is not used anywhere (drag cost is moot today) (`WakeExtraAlarmSlider.kt:126`)
- Stop keeps the alarm sounding through main-thread bookkeeping and a service round trip (`WakeAlertActivity.kt:192`)
- Audio watchdogs poll the audio service from the UI thread for the whole ring (`SpeakerPlaybackRoute.kt:53`)
- Pinch zoom recomposes the page and restarts an effect every frame (`QuranScreen.kt:3814`)
- Every grade or edit re-encodes the whole notebook, fsyncs, reads it back and then runs a full reminder refresh on one serial thread (`Hifz.kt:968`)
