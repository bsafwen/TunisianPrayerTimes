# Android app bug review

Reviewed on 13 October 2026 against the current working tree, including uncommitted and untracked Android source. HEAD was `03647f2fc574b0c3c87519619eb51533c2d9e197`.

This review identifies **14 actionable findings: 1 P1, 11 P2, and 2 P3**. Eleven were not documented in the previous report; three are still-present issues rechecked from `BUG_REPORT.md`. The highest priority is disabling a nap alarm without releasing its silence ownership, which can leave the phone silent after its alarm has been canceled.

During the initial review, no application code, tests, resources, repository configuration, or existing report was edited. Findings are confirmed by source and caller tracing; none was reproduced on a device during this review. Subsequent fixes are recorded under their findings. Numerical checks are identified where used. Passing compilation or unrelated tests does not establish physical alarm, audio, notification, or gesture behavior.

## Findings at a glance

P1 means a high-impact failure in an ordinary workflow. P2 means a functional defect with a specific trigger. P3 means a smaller visible correctness issue.

| ID | Priority | Finding | Previous report |
| --- | --- | --- | --- |
| B01 | P1 | Disabling a nap alarm from the list leaves its silence active | New |
| B02 | P2 | A vibration-only timer can start a ringtone during prayer silence | New |
| B03 | P2 | A disabled prayer window can suppress an awake check | New |
| B05 | P2 | Repeated reminder edits restore counts the user undid | New |
| B06 | P2 | Starting a drill from restored playback loses its repetition | New |
| B07 | P2 | Returning from a drill can mix two reciters' timings | New |
| B08 | P2 | Editing a Hifz point to both directions omits the reciprocal point | New |
| B09 | P2 | Restoring the listening position clears a completed drill's replay action | New |
| B10 | P3 | Library previews and existing search results keep the old wording | New |
| B11 | P3 | Switching tabs resets an explicitly selected historical date | New |
| K01 | P2 | A narrow timeline marker gap can crash the prayer screen | M2, still present |
| K02 | P2 | Qibla does not recognize a permission grant made in Settings on return | M13, still present |
| K03 | P2 | A dismissed prayer window later overrides the user's silent ringer setting | L28, residual case |

## Newly identified findings

### B01 Disabling a nap alarm from the list leaves its silence active

**Status: Fixed on 13 October 2026.** `wake/PrayerWakeRepository.saveWakeConfig` now releases the resolved alarm ID's silence ownership after a disabled configuration is successfully saved. It reuses `removeSilenceUntilAlarm`, preserving other pending timers and active prayer/manual silence. This covers the list switch and other save callers. Offline `:app:compileDebugKotlin` and `git diff --check` passed. Tests were not edited or rerun; physical-device behavior remains unverified.

**P1. Location:** `app/src/main/java/com/tunisianprayertimes/ui/MainScreen.kt:4623–4628`.

Create a future “from now” alarm with “silence until alarm” enabled, then turn that alarm off using its switch in the alarm list. The switch saves `enabled = false` and refreshes scheduling. It never calls `removeSilenceUntilAlarm`.

`wake/PrayerWakeRepository.kt:111–131` clears scheduling and awake-check metadata without releasing silence. `wake/WakeAlarmScheduler.kt:438–441` excludes the disabled alarm, so its pending ring is canceled while its ID remains in the silence-owner set. The canceled alarm can no longer restore audio. With no other action releasing that owner, the phone stays silenced past the former alarm time.

**Expected:** disabling the alarm releases the silence it owns while respecting other active silence owners. The editor's save path already performs this release at `ui/MainScreen.kt:1326–1328`; the list switch omits it. Existing disable tests check alarm cancellation but do not activate and verify nap silence.

### B02 A vibration-only timer can start a ringtone during prayer silence

**P2. Location:** `app/src/main/java/com/tunisianprayertimes/wake/AlarmAudioController.kt:39–47`.

Create a “from now” timer with vibration-only enabled and awake check disabled. Let it fire during an enabled prayer silence window and leave it running for more than two minutes.

`WakeAutoSilenceConflictController.kt:17–29` automatically selects conflict playback for that timer. `startWakeAlarm` processes `useAutoSilenceConflictPlayback` before it checks `vibrationOnly`. The conflict playback branch at `AlarmAudioController.kt:155–180` starts a ringtone after two minutes without consulting the vibration-only preference.

**Expected:** the saved vibration-only alarm stays inaudible. This timer mode bypasses the conflict acceptance dialog (`ui/WakeEditorSheet.kt:383–386,437–442`), so the user has not accepted a sound override. The two-minute escalation explanation elsewhere belongs to the separate awake-check option, which can be off in this reproduction. The vibration-only description promises no sound (`res/values/strings.xml:365–366`).

### B03 A disabled prayer window can suppress an awake check

**P2. Location:** `app/src/main/java/com/tunisianprayertimes/wake/AwakeCheckSilencePolicy.kt:103–105`.

Keep automatic silence globally enabled but disable Asr silence individually. Dismiss an ordinary alarm before Asr so its awake check falls inside Asr's configured silence window. Keep the phone otherwise unsilenced and use an alarm without a silence override.

The historical overlap check builds configurations for every prayer without reading `PrefsManager.isPrayerSilenceEnabled`. It then calls the shared overlap calculator, which has no per-prayer enable information. `shouldCancel` returns true at lines 50–52 before checking whether any app silence is actually active. The awake check is canceled for a window that will never run.

**Expected:** an individually disabled window cannot suppress the check. The same missing enable information appears in `WakeAlarmScheduler.kt:461–462` and `ui/WakeEditorSheet.kt:3901–3903`, affecting conflict detection too. Removing entries from the map alone would not represent disabled windows: the shared `SilenceAlarmComputer.overlapForTrigger` substitutes default configurations for absent entries.

### B05 Repeated reminder edits restore counts the user undid

**P2. Location:** `app/src/main/java/com/tunisianprayertimes/adhkar/DhikrRepository.kt:304–307`.

Configure a single-dhikr reminder with three daily intervals, for example 08:00–10:00, 16:00–18:00, and 20:00–22:00. Count 40 in revision 1 while its target is higher than 40. Edit the reminder's target during the first interval. In the later revision 2 interval, the carried count is 40; undo it to 20, then edit again.

`ensureOccurrence` carries the maximum count from **all** replaced same-day revisions. Revision 3 consequently starts at `max(40, 20) = 40`, restoring 20 recitations explicitly undone in revision 2. A new target of 25 makes the subsequent occurrence immediately complete and prevents its reminder delivery.

**Expected:** the most recent applicable progress, including undo, survives the edit. `save` retains replaced occurrence records at lines 34–36, and `count` persists decrement operations at lines 476–485. The existing carry-forward test covers a single edit, not an undo followed by another edit.

### B06 Starting a drill from restored playback loses its repetition

**P2. Location:** `app/src/main/java/com/tunisianprayertimes/quran/audio/QuranPlaybackService.kt:221–224`.

Start a repeated Quran passage, pause it, swipe away its media notification, then start a Hifz drill before resuming ordinary playback. Swiping the paused notification saves playback and stops the service (`dismiss`, lines 746–760).

The controller still holds the repeated range and round, but the newly created service starts with its private `repeat = null`. The drill's `returnPlace` captures that private value instead of the restored controller state. During the drill, `persistPosition` writes this null repetition back to preferences (lines 1058–1061). Finishing or stopping the drill returns ordinary playback without the original repeated range or round.

**Expected:** a temporary drill preserves the listener's saved repetition. Merely pausing without ending the service is not sufficient to trigger this case; notification dismissal or process recreation supplies the new service.

### B07 Returning from a drill can mix two reciters' timings

**P2. Location:** `app/src/main/java/com/tunisianprayertimes/quran/audio/QuranPlaybackService.kt:1140–1145`.

Pause Hosary partway through a surah, start a drill, switch its reciter to Ayoub, then stop the drill or pause it and swipe its notification away.

Changing reciter replaces the service's `recordings` table. `returnState(back)` reads that current table while assigning the original reciter ID and original timestamp. It also registers the mismatched track for idle navigation. The displayed verse and duration are wrong; pressing previous/next while idle can then persist an Ayoub timestamp as a Hosary position (`QuranAudioController.kt:400–417`), moving subsequent playback to the wrong place.

**Expected:** restoration and idle navigation use the timing table belonging to `back.reciterId`.

**Bundled-data check:** in `assets/quran/audio/hosary/timings.json`, surah 2 verse 100 starts at 2,932,540 ms. One second later is 2,933,540 ms, which Ayoub's table assigns to verse 170. The mismatched duration is 5,851,088 ms instead of Hosary's 10,524,473 ms. These values were checked directly against the bundled JSON; the service sequence remains source-traced.

### B08 Editing a Hifz point to both directions omits the reciprocal point

**P2. Location:** `app/src/main/java/com/tunisianprayertimes/ui/QuranScreen.kt:2154–2156`.

Record a one-way Similar/Ending point from verse A to verse B, with no existing reverse point. Open its correction, choose **تعديل الموضع**, enable **قد أخلط في الاتجاهين**, and save.

`ui/HifzSheets.kt:418–419` promises review and advance notice at both verses; lines 484–486 save `bothWays = true`. The edit handler only updates the original point. Creating a reciprocal B-to-A point occurs solely in the recording path (`QuranScreen.kt:1035–1043`). Review selection and heads-up grouping use stored points' own verses; playback stop-before also checks only those verses.

**Expected:** saving both directions provides a review and advance notice anchored at B as promised. Comparison or drill content may include B, but this does not create B's missing review point. An independent second reviewer verified that no other path expands the edited flag into a reciprocal point.

### B09 Restoring the listening position clears a completed drill's replay action

**P2. Location:** `app/src/main/java/com/tunisianprayertimes/quran/audio/QuranPlaybackService.kt:1131–1134`.

Pause ordinary listening at a nonzero position, run a drill to completion, and allow the original listening position to restore.

`goBack` starts an asynchronous load and sets `finishedList = ended`. The media prepared callback then seeks to the saved nonzero position (lines 350–356). That internal `seek` clears `finishedList` at line 529 and publishes the cleared state. The drill-completed notice and **أعده** replay action disappear when restoration finishes (`ui/QuranScreen.kt:2582–2583`).

**Expected:** restoring a listening position preserves completion and replay until an intentional user action replaces them. This case needs a positive seek target; a saved position at the beginning can avoid the clearing branch.

### B10 Library previews and existing search results keep the old wording

**P3. Location:** `app/src/main/java/com/tunisianprayertimes/ui/AdhkarScreen.kt:847–854`.

Open salawat from Library, select the Abu Humayd wording in its reader, then dismiss the reader. The Library remains composed underneath it. Its memoized results do not include `state.variantChoices` in their keys, although `state.allEntries` resolves wording from that state.

The Library preview keeps the previous wording. An existing search such as **آل محمد** can also retain the entry even though the new wording lacks that phrase. Changing the query or a filter recomputes results and repairs the display.

**Expected:** changing wording immediately updates the underlying preview and existing search results. This is stale memoization, not permanently broken search.

### B11 Switching tabs resets an explicitly selected historical date

**P3. Location:** `app/src/main/java/com/tunisianprayertimes/ui/MainScreen.kt:2092–2103`.

Select yesterday or an earlier date in the prayer table, switch to another main tab, then return to Today. The selected date unexpectedly becomes today. A selected future date survives the same sequence.

The parent retains `prayerTableSelectedDate` at lines 565–567, but `PrayerSettingsCard` recreates `lastCurrentDayMillis` through `remember` at line 2071. Its first-composition recovery effect treats any past selected date as stale and replaces it. The process-recreation workaround therefore also runs on ordinary tab re-entry.

**Expected:** switching tabs preserves an explicitly selected historical date, as it already preserves a future selection.

## Previously reported issues confirmed still present

### K01 A narrow timeline marker gap can crash the prayer screen

**P2. Location:** `app/src/main/java/com/tunisianprayertimes/ui/MainScreen.kt:3347–3349`. Previous report: **M2**.

On a Friday, set the custom Jomoaa time close to Asr, for example ten minutes earlier, and place a wake alarm between those times. Open the prayer screen's day/night timeline for that date.

`gapBounds` reserves space around both neighboring markers. When that space exceeds the gap, `fitMarkerAngles` correctly returns null for `minAngle > maxAngle` (lines 3299–3300). The final fallback nevertheless passes those same invalid limits to `coerceIn`, throwing `IllegalArgumentException` during composition.

**Geometry check:** at the maximum 131 dp ring radius, each inset is about 5.423 degrees. Ten minutes spans only 2.5 degrees, producing a lower limit 5.423 degrees above the first marker and an upper limit 2.923 degrees below it. This is an empty range. Smaller radii increase the required inset.

**Expected:** crowded markers degrade gracefully without crashing. The current source retains the old report's failing fallback.

### K02 Qibla does not recognize a permission grant made in Settings on return

**P2. Location:** `app/src/main/java/com/tunisianprayertimes/ui/QiblaCard.kt:234,428–440`. Previous report: **M13**.

Deny location until the Qibla warning directs you to Android app settings. Grant location there and return to the still-existing Qibla screen.

`locationPermissionGranted` is read once with `remember` and updated by the runtime permission launcher's callback. Opening Settings uses a separate activity and does not invoke that callback. No resume handler refreshes this permission state or restarts location acquisition; `LaunchedEffect(Unit)` has already run. The screen still treats location permission as denied and uses its fallback or reports location needed.

**Expected:** on return from Settings, the existing Qibla screen recognizes the grant and obtains a location. Leaving and reopening the tab can recover by recreating its state.

### K03 A dismissed prayer window later overrides the user's silent ringer setting

**P2. Location:** `app/src/main/java/com/tunisianprayertimes/SilenceReceiver.kt:84–90`. Previous report: **L28**, explicitly acknowledged residual case.

Start with a user-selected silent ringer and ordinary DND settings. Let a prayer silence window begin, then dismiss that window early. The dismissal restores the previous silent ringer and clears the app's automatic-silence flag.

When the already-scheduled end-of-window broadcast arrives, `SilenceStatus.isPhoneSilenced` is true because the ringer is silent. The receiver calls `forceNormalMode`, overriding the user's original setting even though automatic silence is no longer active.

**Expected:** ending an already-dismissed app window preserves the user's restored silent setting. The prior fix and regression test cover vibration mode; the silent-ringer branch remains. This is not a newly discovered failure or a claim that the entire earlier fix is absent.

## Validation and coverage

Six subagents were used across two rounds, with up to three running alongside the primary reviewer. All six were explicitly configured for ultra reasoning. Reviews covered wake/silence/nap scheduling and ownership, Adhkar sessions and reminders, Quran playback and assets, Hifz state and UI, locality failures, and main-app integration. A separate pass challenged B08 for false positives.

The primary reviewer checked the reported code paths and the relevant previous report. Large UI files were reviewed selectively around behavior and state transitions; this was not an exhaustive line-by-line audit. Sensor accuracy, OEM background behavior, real notification delivery, audio focus and physical alarm behavior were not exercised on a device. No APK was generated or installed.

The existing offline unit test task compiled production Kotlin successfully. Its initial run stopped after a test-worker heap failure: **877 tests completed, 19 failed, one skipped**. That failure count includes the out-of-memory failure in `AdhkarReaderSwipeTest`.

The retry completed without a heap crash: **1,038 tests, 1,006 passed, 32 failed, none skipped**. It used a temporary Gradle init script outside the repository with a 2 GB test heap, a fresh worker every 25 test classes, and the required HTTP/HTTPS proxy properties. Project configuration was not changed. The command was `./gradlew.bat :app:testDebugUnitTest --offline --console=plain --init-script <temporary script>`, run from `android-app`.

The generated [unit test report](app/build/reports/tests/testDebugUnitTest/index.html) contains the individual assertions. Failure totals were also checked against the JUnit XML:

| Test suite | Failed |
| --- | ---: |
| AdhkarFlowTest | 2 |
| DelegationLocatorTest | 3 |
| LocalityRepositoryTest | 8 |
| MainActivityPendingIntentsTest | 1 |
| NeighborhoodRepositoryTest | 4 |
| QiblaCardTest | 1 |
| AlarmServiceSilenceGuardTest | 6 |
| AwakeCheckDisableTest | 1 |
| AwakeCheckSilenceSuppressionTest | 4 |
| WakeAlarmSilenceSuppressionTest | 2 |
| **Total** | **32** |

The assertions already examined should not all be treated as production bugs:

- Two `AdhkarFlowTest` failures expect **تم**, while the current notification action deliberately reads **أتممتُ الذكر**. The failing assertions concern labels.
- Fifteen locality/delegation/neighborhood failures concern retired picker IDs and grouping, updated exact labels, old polygon expectations, and one diagnostic conflict sample. Current assets contain 2,083 boundary entries and no `delegation:*` rows. These disagreements do not establish that the current real-world geographic mapping is wrong.
- `NeighborhoodRepositoryTest` stops at its old `>= 2700` count assertion before its polygon-interior loop. Those later geometry checks therefore remain unverified. Conflict sample 4065 is a metadata QA discrepancy; runtime parsing ignores its sample coordinates.
- `MainActivityPendingIntentsTest.awakeCheckNotificationUsesAwakeCheckIntent` fails because no foreground notification was created, before its intent assertion. The service can deliberately return early for silence/cancellation, and the test uses the real Application's asynchronous startup scheduling. The exact cause is unresolved; this does not establish a broken notification destination.
- `QiblaCardTest.switchingToTheRhumbLine_explainsTheDifferenceAndRemembersTheChoice` times out waiting for the expected bearing. Its root cause remains unverified and it is not evidence for K02, whose trigger is a Settings permission grant.
- Thirteen wake/awake-check assertions also fail. Six have demonstrably outdated cancellation fixtures: four in `AwakeCheckSilenceSuppressionTest` and two in `AlarmServiceSilenceGuardTest`. Their hand-built intents omit or mismatch occurrence/trigger metadata now required by cancellation guards. The other seven remain unresolved; real Application startup scheduling and the actual clock are possible fixture interactions, not proven explanations. These failures are not counted among the 14 findings.

The previous repository report records 30 failures across the same nine suites other than `MainActivityPendingIntentsTest` and `AwakeCheckDisableTest` (`BUG_REPORT.md:507–511`). That is historical evidence only: this review did not rerun a separate HEAD checkout or prove that every individual failure has the same cause.

These existing-test results are separate from B01–B11 and K01–K03 and predate the B01 fix recorded above. No new regression tests were added during the review or the B01 fix.
