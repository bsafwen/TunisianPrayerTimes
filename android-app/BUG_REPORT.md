# android-app bug report

Date: 2026-10-11 (fifth revision: the 21 confirmed bugs fixed, their 22 tests pass, and H1-H3 checked again on the Pixel 6 with the fixed build)

## Scope and method

- **Target:** `android-app/`: main code under `app/src/main` (142 Kotlin files, about 52,000 non-blank lines), the Gradle build files, manifest, resources, bundled assets, and the release and capture scripts. Unit tests (`src/test`) and instrumented tests (`src/androidTest`) were not reviewed; one test was read as evidence for M3, and the silence tests were searched for midnight handling (M14).
- **Code state:** the working tree as it is on disk. It has many uncommitted and untracked changes, so some findings may already be fixed in uncommitted work. By file timestamps, the only `android-app` files written on the day of the review (besides this report) are Quran audio, Quran and Hifz screens, Hifz logic, `MainActivity.kt` and a few tests, last written between 09:30 and 13:30, before the first review pass started. None of the files cited by H1-H4 and M1-M14 was among them, so their line numbers were stable during the review.
- **Source edits:** revisions 1 to 3 changed no production source or build file. Revisions 1 and 2 were read-only. In revision 3 the app was built, several bugs were reproduced on a Pixel 6, and 22 new tests were added under `app/src/test/java/com/tunisianprayertimes/bugreport/` (see "Tests"). Revision 4 fixed the 21 confirmed findings in production code; no build file was changed. "Verified" means traced in the source; the "Pixel 6" and "Test" lines say when a bug was also reproduced, and a "Fixed" line says how it was fixed.
- **Line numbers** in the Location lines are those before the revision 4 fixes.
- **Method:** parallel read-only review passes over code slices, followed by my own checks. In the first revision I re-read the code behind every High and Medium finding and most Low ones. In the second I read most of the silence system, the Quran pack code, the analytics code and the wake mini-games directly. In the third I reproduced H1-H3 on the user's Pixel 6 and wrote a test for each finding a unit test could show. Many review passes returned no result, even after retries. Those areas are listed under "Coverage" and were **not** reviewed.
- **Paths:** source files are relative to `app/src/main/java/com/tunisianprayertimes/` unless the path starts with `app/`, `android-app/` or `multiplatform/`.
- **Line numbers** are the numbers the file-reading tool shows, which match an editor. Some line counts given to the review passes came from a counter that skips blank lines. The real counts are 7,062 for `ui/MainScreen.kt` (not 6,765), 4,056 for `ui/WakeEditorSheet.kt` (not 3,861) and 2,744 for `ui/QuranScreen.kt` (not 2,703).

Status:
- **Verified:** I traced the failure path in the source.
- **Reported:** found by a review pass. I confirmed the cited code exists, but did not re-trace the whole failure path.

Evidence lines under a finding:
- **Pixel 6:** the bug was reproduced on the user's Pixel 6 (Android 16), see "Reproduction on a Pixel 6".
- **Test:** a test in `bugreport/` asserts the correct behavior and failed because of the bug, see "Tests".
- **Fixed:** what revision 4 changed. The finding's test passes after the change.

Severity:
- **High:** the app leaves alarms, silence, or settings in a wrong state, or misbehaves visibly, in ordinary use.
- **Medium:** the same kind of failure, but it needs an unusual setting, sequence, or timing, or it is a crash with a rare trigger.
- **Low:** minor correctness issue, wasted work, or a developer-tool problem.

Several review passes rated some items Critical. None applies without an unusual setting or timing, so nothing is rated above High here.

## Summary

| Severity | Count |
|---|---|
| High | 4 |
| Medium | 14 |
| Low | 30 |

Most important:
1. After a reboot, a timed manual silence does not end on time: the phone stays silent until the app is opened (H1). Reproduced on the Pixel 6, and shown by two tests.
2. A nap alarm with "silent until alarm" set during a prayer window leaves the phone in DND (H2). Reproduced on the Pixel 6 with the real Isha window, and shown by two tests.
3. The ringtone preview keeps playing after the app is backgrounded (H3). Reproduced on the Pixel 6.
4. A tap on the silence slider can save a stale config and revert a setting (H4). Shown by a test.
5. A silence window that crosses midnight is ended at 00:01 (M14). Shown by a test.

Also worth attention: a wake alarm can be dropped in one specific combination (M1), and the Ramadan override stays on after Ramadan and deletes the user's Isha end offset (M3). The shared-sheet `+`/`-` bug (formerly H4) is now L30: the sheet cannot be opened from the app.

20 findings are shown by 22 tests, and 3 were reproduced on the Pixel 6 (see "Tests" and "Reproduction on a Pixel 6").

**Fixed in revision 4:** the 21 confirmed findings, meaning those shown by a test or reproduced on the Pixel 6: H1-H4, M1, M3, M5-M9, M11, M12, M14, L5, L12, L15, L20 and L28-L30. Their 22 tests pass, and none of the fixes adds a failure to the rest of the unit tests (see "Tests"). The three bugs reproduced on the Pixel 6 (H1-H3) were run again there with the fixed build, and all three now behave correctly, H1 also through a real reboot (see "Validation on the Pixel 6"). The other 27 findings (M2, M4, M10, M13 and 23 Low ones) are not fixed.

---

## High

### H1. Timed manual silence is not re-armed after a reboot
- **Status:** Verified
- **Fixed:** `ManualSilenceScheduler.restoreTimer` ends a manual silence whose end has passed, or re-arms its end alarm. `BootReceiver.refreshSchedules` calls it before anything else, and `TunisianPrayerTimesApplication` calls it on every process start, which also covers a force-stop once the app runs again. Checked on the Pixel 6 after a force-stop and after a real reboot: the silence ended at its set time both times (see "Validation on the Pixel 6").
- **Pixel 6:** reproduced, with a force-stop instead of a reboot (both clear the app's alarms). At 19:55:26 a 2-minute manual silence was on, its end was stored for 19:57:23, and its end alarm was armed. After `am force-stop` the app had no alarms left. At 19:58:33 the phone was still in DND with the ringer silent (`zen_mode=2`, `mode_ringer=0`), and `manual_silence_active` was still true. Opening the app ended it (19:58:49).
- **Test:** `SilenceBugsTest.H1_bootRearmsAnUnexpiredTimedManualSilence` and `H1_bootEndsAManualSilenceThatExpiredDuringTheReboot` failed before the fix: `BootReceiver.refreshSchedules` neither re-arms the end alarm nor ends an expired silence.
- **Location:** `ManualSilenceScheduler.kt:23-43` and `:51-62`; `BootReceiver.kt:48-54`; `ScheduleRefreshCoordinator.kt:93-110`; `TunisianPrayerTimesApplication.kt:39-52`; `ui/MainScreen.kt:448-508` and `:978-1008`
- **Problem:** A timed or "until prayer" manual silence ends through one exact AlarmManager broadcast. Alarms are cleared by a reboot (and by force-stop), but the preferences still say manual silence is active, and the ringer and DND state normally persist. Nothing re-arms the end alarm. `BootReceiver` runs `HifzReminder.refresh`, `ScheduleRefreshCoordinator.syncAll` (Adhkar, prayer silence, wake alarms) and the Ramadan check, and none of them calls `ManualSilenceScheduler`. The shared `TimerScheduler` and `SilenceController` do not cover it either. `syncExpiredTimer`, which ends an expired manual silence, runs only from wake and awake-check paths and when the main screen opens (`MainScreen.kt:449`). If the app is opened before the end time, the end is handled by an in-app wait (`:497-508`) that lasts only while the screen stays open.
- **Impact:** After a reboot during a timed manual silence, the phone stays silent or in DND past the chosen end until the app is opened after that time, or a wake alarm runs. The Application already re-arms Adhkar and Hifz reminders on process start for this reason (`TunisianPrayerTimesApplication.kt:42-44`), but not the manual silence end.
- **Fix:** Re-arm an unexpired manual end from the boot and refresh paths and on process start, and restore immediately if the end has passed.

### H2. Nap silence ("silent until alarm") is saved and restored incorrectly around prayer silence windows
- **Status:** Verified
- **Fixed:** the three silences now share one saved state, and only the strongest one acts (manual over a prayer window over a nap). A silence that starts while another is on copies the state that one saved, not the current silenced state (`NapSilenceController.enableNapSilence`, `SilenceModeController.enableAutoSilence` and `setManualSilent`). A window or manual silence that ends while a nap is pending hands its saved state to the nap and keeps the phone silenced (`NapSilenceController.takeOverSilence`), so the nap's alarm restores the state from before any silence. Checked on the Pixel 6 with the real Asr window: the phone stayed silent until the nap alarm and then returned to normal, and the app's DND mode was left inactive (see "Validation on the Pixel 6").
- **Pixel 6:** reproduced with the installed `.dev` build and the real Isha silence window (19:16-19:46).
  - 19:16:27: a quick timer (vibration only, "silence the phone until the alarm rings", ringing at 19:49:17) was saved during the window. The app stored `nap_previous_ringer_mode=0` (silent) and `nap_previous_interruption_filter=3` (none), the window's silenced state.
  - 19:46:12: the window ended and the phone went back to normal (`zen_mode=0`) while the nap alarm was still pending, so the nap silence was lost.
  - 19:49:28: the alarm rang, and the app put the stored silenced state back (`zen_mode=1`, `mode_ringer=0`).
  - After the alarm was stopped, and one minute later, the phone stayed in DND with the ringer silent. No app flag was set, so nothing would restore it.
  - On Android 16 this shows in Settings > Modes as "عدم الإزعاج (أوقات الصلاة تونس)", active and managed by the app. `adb shell cmd notification set_dnd off` could not clear it; turning the mode off in Settings did.
- **Test:** `SilenceBugsTest.H2_napCreatedDuringPrayerWindowLeavesPhoneNormalAfterItRings` and `H2_napRingingDuringPrayerWindowLeavesPhoneNormalAfterWindow` failed before the fix: the ringer ends silent instead of normal.
- **Location:** `wake/WakeAlarmScheduler.kt:51-95` and `:129-130`; `nap/NapSilenceController.kt:10-49`; `SilenceModeController.kt:53-101`; `ui/MainScreen.kt:1319-1329`; `SilenceReceiver.kt:61-85`
- **Problem:** Silence-until-alarm is activated only for "from now" alarms (`ui/MainScreen.kt:1319-1323`). Saving one calls `activateSilenceUntilAlarm`, which starts the nap silence without checking whether auto or manual silence is already on, and `MainScreen.kt:1319-1329` does not check either. `enableNapSilence` stores the current ringer mode and DND filter as the state to restore, which during a prayer window is the already-silenced state. When the window ends, `disableAutoSilence` restores normal mode and the nap silence is lost while its alarm is still pending. When the alarm rings, `releaseSilenceForRingingAlarm` calls `disableNapSilence`, which restores the stored silenced state. Separately, `releaseSilenceForRingingAlarm` and `removeSilenceUntilAlarm` call `disableNapSilence` only when `shouldUseWakeSilence` is true (no auto and no manual silence), so a nap alarm that rings or is removed during a window leaves the nap state in place.
- **Impact:** A nap alarm created during a prayer silence window (for example right after Dhuhr) can leave the phone silent with DND on after the alarm. On the Pixel 6 it stayed on as a DND mode owned by the app until it was turned off in Settings > Modes. Each later window records that state as its "previous" state and restores it. The `forceNormalMode` fallback in `SilenceReceiver` (`:75-83`) runs only when the auto flag was already cleared, so the stuck state can last until the user fixes it.
- **Fix:** Do not capture or apply nap state while auto or manual silence owns the device, or track nap ownership separately and restore through one shared "previous state" record.

### H3. The ringtone preview keeps playing after the app is backgrounded
- **Status:** Verified
- **Fixed:** `WakeRingtoneSelector` (`ui/WakeEditorSheet.kt`) stops the preview on `ON_STOP` with `LifecycleEventEffect`, which also resets the button. Checked on the Pixel 6: after Home the preview stops within 3 s, and the button resets (see "Validation on the Pixel 6").
- **Pixel 6:** reproduced. A ringtone preview was started in the alarm editor and Home was pressed. At 19:08:03, about 21 seconds later, the launcher was in front and the app's `MediaPlayer` (uid 10651) was still `state:started` with `usage=USAGE_ALARM` in `dumpsys audio`. Back in the app, the button still read "إيقاف المعاينة" (stop preview).
- **Test:** none. The bug needs a running activity that goes to the background, so it was reproduced on the device instead of in a unit test.
- **Location:** `wake/WakeRingtonePreviewPlayer.kt:23` and `:62` (`isLooping = true`); `ui/WakeEditorSheet.kt:3330-3359` (stop paths) and `:1627-1633` (the only lifecycle observer, `ON_RESUME`)
- **Problem:** The preview loops, uses the alarm audio usage, and takes no audio focus. It stops on a user action, when the row is disabled, or when the composable is disposed (`DisposableEffect` at `:3348-3352`). Nothing stops it on `ON_PAUSE` or `ON_STOP`, and a stopped activity keeps its composition.
- **Impact:** Pressing Home or locking the phone while a tone previews keeps it looping until the user returns to the editor and stops it.
- **Fix:** Stop the preview on `ON_STOP` (for example with `LifecycleEventEffect`), and request transient audio focus.

### H4. A tap on the silence slider track can save a stale config
- **Status:** Verified
- **Fixed:** the slider reads `onCommitWindow` and `onEditEndpoint` through `rememberUpdatedState`, and every tap, drag-end, nudge and accessibility action uses those.
- **Test:** `SilenceUiBugsTest.H4_trackTapUsesTheLatestCommitCallback` failed before the fix: the tap commits through the first composition's callback (`[1]` instead of `[2]`). The test also showed the condition: the slider must already have had a touch (for example the tap on a handle that opens the endpoint editor), most likely because Compose starts the `pointerInput` coroutine, with the lambda of that moment, on the first touch. A first touch after the config change gets the new callback.
- **Location:** `ui/PrayerSilenceRangeSlider.kt:218-221`, `:574`, `:621`, `:645-648`; `ui/MainScreen.kt:5681-5686`
- **Problem:** The slider reads `window`, `config`, `canAdjust` and `onPreviewWindow` through `rememberUpdatedState`, but not `onCommitWindow`. The tap handler is installed with `pointerInput(canAdjust, prayerTime, scale, constraints.maxWidth)`, so its closure is replaced only when one of those keys changes, and `handleTrackTap` calls the `onCommitWindow` from the older composition. In `MainScreen.kt` that lambda builds the saved config from the `config` captured when it was created (`prayerTimelineConfigForWindow(validPrayerTime, config, candidate)`). If a rule mode or another config field changes through the editor or the shared sheet without changing the resolved window or the scale, the next track tap saves the old config with the new window. Dragging is not affected, because its gesture key includes `config` (`:621`).
- **Impact:** A saved rule mode (for example a fixed end) or another silence setting can silently revert.
- **Fix:** Read `onCommitWindow` through `rememberUpdatedState`, or add `config` and the callback to the `pointerInput` keys.

---

## Medium

### M1. A wake alarm can be dropped by a second silence check in the playback service
- **Status:** Verified (code path); needs an unusual combination
- **Fixed:** after lifting a prayer window for an alarm allowed to ring over it, `WakeAutoSilenceConflictController` pauses a pending nap's silence for that alarm (`WakeAlarmScheduler.pauseSilenceForRingingAlarm`), so the service finds no app silence and plays it. The nap's silence resumes when that alarm stops.
- **Test:** `WakeAndPrefsBugsTest.M1_alarmAllowedOverPrayerWindowIsPlayedWhileAnotherNapIsPending` failed before the fix: after the receiver's lift, the playback service drops the alarm (the queue stays empty).
- **Location:** `wake/WakeAlarmReceiver.kt:42-74`; `wake/WakePlaybackService.kt:56-69`; `wake/WakeAutoSilenceConflictController.kt:13-56`; `SilenceStatus.kt:21-30`; `wake/WakeAlarmScheduler.kt:116-119` and `:794`
- **Problem:** For an alarm that may ring over a prayer silence window (flagged when it is scheduled, or at fire time for "from now" alarms), the receiver lifts the auto-silence and then starts `WakePlaybackService`. The service repeats the lift, which now returns false because the auto flag is already cleared, and stops without playing if `isAppControlledSilenceActive` is true. That holds when another silence-until-alarm is still pending and the DND filter restored by the lift is still "none", which happens when the window started during that nap (the window recorded the nap's silenced state as its previous state, see H2).
- **Impact:** That alarm never rings and shows no notification.
- **Fix:** Pass the receiver's lift decision to the service, or skip the second check for payloads the receiver has already approved.

### M2. The night timeline throws when an alarm falls in a narrow gap between two markers
- **Status:** Verified (code and geometry); rare trigger
- **Location:** `ui/MainScreen.kt:3095-3104` (barrier markers), `:3282-3293` (`gapBounds`), `:3296-3315` (`fitMarkerAngles`), `:3341-3351` (fallback)
- **Problem:** The ring is a linear 24-hour dial (15 degrees per hour). Alarm markers keep a minimum angle from the neighbouring prayer, sunrise, sunset, night-third and Fajr markers, about 5.4 degrees at the full 131 dp radius. When two neighbouring markers are closer than twice that (about 43 minutes at full radius, more on narrower cards), `gapBounds` returns `minAngle > maxAngle`. `fitMarkerAngles` returns null for it, the merge loop reduces the drafts to one, and the last line calls `desiredAngleDegrees.coerceIn(minAngle, maxAngle)`, which throws `IllegalArgumentException` for an empty range, during composition.
- **Trigger:** Typical spacing between these markers is well above the limit. The input that can place two of them that close is the Friday Jomoaa time (a plain time picker, `ui/MainScreen.kt:2332-2335`), set close to Asr or sunrise, with a wake alarm between them that day.
- **Impact:** The screen that shows the night timeline crashes on that day.
- **Fix:** When `minAngle > maxAngle`, use a fixed angle, such as the gap midpoint, instead of `coerceIn`.

### M3. The Ramadan Isha override is never reverted and deletes the user's Isha end offset
- **Status:** Verified (code); intended lifetime not confirmed
- **Fixed:** `applyRamadanIshaOverrideIfNeeded` saves the user's Isha after-minutes and end offset before writing 90, and restores them the first time it runs after that Ramadan. Any Isha edit drops the saved values, so a change made during Ramadan is kept. Values already overwritten in earlier years cannot be recovered.
- **Test:** `WakeAndPrefsBugsTest.M3_ramadanIshaOverrideIsRevertedAfterRamadan` failed before the fix: after Ramadan, Isha stays at 90 minutes.
- **Location:** `PrefsManager.kt:384-426`; callers `SilenceScheduler.kt:178` and `TunisianPrayerTimesApplication.kt:65`; test `app/src/test/java/com/tunisianprayertimes/PrefsManagerTest.kt:183-200`
- **Problem:** The first time Ramadan is detected in a Hijri year, `applyRamadanIshaOverrideIfNeeded` writes Isha after-minutes as 90 when the stored value is below 90 (the default is 30), and deletes the Isha adhan-relative end offset. Nothing restores either value after Ramadan; the only other writer is the user's own edit (`PrefsManager.setConfig`, `:535-550`). The doc comment calls it a "one-time" override, but the older test `ishaDefaultDuringRamadan_returns90` expects `getAfterMinutes` to return 90 during Ramadan even after saving 30, which the current code does not do (that test would fail during Ramadan).
- **Impact:** Users who never changed Isha get a 90-minute post-Isha silence for the rest of the year, and a custom Isha end offset is removed each Ramadan without notice.
- **Fix:** Save the pre-Ramadan values and restore them when Ramadan ends, or apply 90 as an effective value without persisting it.

### M4. "Until prayer" manual silence can start from a stale target prayer
- **Status:** Verified
- **Location:** `ui/MainScreen.kt:407-417` (restore), `:906-918` (mode and chip changes), `:967-975` (start), `:6652-6672` (`resolveManualSilencePrayerEndMillis`)
- **Problem:** `manualTargetPrayer` is restored from preferences when the saved mode is "until prayer", and is re-resolved only when the user switches mode (`:909-913`). Nothing refreshes it as time passes. On start, `resolveManualSilencePrayerEndMillis` returns the next occurrence of that prayer, so a prayer that has already passed today resolves to tomorrow. The selected prayer is highlighted on the card (`:6460`), so the user can see it.
- **Impact:** Example: "until Asr" was used before. Opening the app at 18:00 and tapping Start silences the phone until tomorrow's Asr, about 21 hours, unless the user picks another prayer first.
- **Fix:** Re-resolve the default target to the next upcoming prayer when the card is shown or when Start is pressed.

### M5. Friday "until prayer" ignores a custom Jomoaa time
- **Status:** Verified (code); intended behavior not confirmed
- **Fixed:** the manual silence candidates come from `scheduledPrayers` for each day, with Friday's Jomoaa standing for the card's Dhuhr (`manualSilencePrayers` in `ui/MainScreen.kt`). The labels still say Dhuhr on Fridays.
- **Test:** `LogicBugsTest.M5_untilDhuhrOnFridayEndsAtTheCustomJomoaaTime` failed before the fix: with Jomoaa set to 13:15, "until Dhuhr" ends at Dhuhr's time, 63 minutes early. The test calls the private function through reflection.
- **Location:** `ui/MainScreen.kt:6636-6672`, `:2738-2749`; `multiplatform/shared/src/commonMain/kotlin/com/tunisianprayertimes/PrayerModels.kt:31-41`
- **Problem:** `resolveUpcomingManualSilencePrayer` and `resolveManualSilencePrayerEndMillis` build their candidates from `allPrayers()`, which has Dhuhr but no Jomoaa. Friday silence scheduling and the timeline use `scheduledPrayers(...)`, where Jomoaa replaces Dhuhr. With a custom Jomoaa after Dhuhr (the default Jomoaa time equals Dhuhr), the default target chosen between Dhuhr and Jomoaa is Asr, and "until Dhuhr" ends at the Dhuhr time, before the Friday prayer.
- **Fix:** Build the candidates from `scheduledPrayers(isFriday, jomoaaHour, jomoaaMinute)`.

### M6. Deleting an alarm leaves its awake check scheduled
- **Status:** Verified
- **Fixed:** `PrayerWakeRepository.deleteWakeAlarm` cancels the awake checks of the alarm and its extra alarms, except for a completed one-off removed by the expiry cleanup, whose last check must still run.
- **Test:** `WakeAndPrefsBugsTest.M6_deletingAnAlarmCancelsItsPendingAwakeCheck` failed before the fix: the awake-check alarm is still scheduled after the delete.
- **Location:** `wake/PrayerWakeRepository.kt:169-185` and `:117-133`; `wake/AwakeCheckReceiver.kt:30-82`; `wake/WakeDismissalCoordinator.kt:42`; `ui/MainScreen.kt:1349-1357`
- **Problem:** Dismissing an alarm schedules its awake check. `saveWakeConfig` and `stopWakeAlarm` cancel it, but `deleteWakeAlarm` and the delete handler in `MainScreen.kt` do not. When the check fires, `AwakeCheckReceiver` finds no config and starts the check, because a missing config is expected for completed one-off alarms (`:59-60`).
- **Impact:** An "are you awake?" alarm rings for an alarm the user deleted, if it is deleted while a check is pending.
- **Fix:** Cancel the alarm's awake check in the user-initiated delete path only, not in the expiry cleanup.

### M7. A paused silence-until-alarm is not re-applied while auto-silence is on
- **Status:** Verified
- **Fixed:** a prayer or manual silence no longer blocks `resumeSilenceUntilNextAlarm`: the nap silence is re-applied with that silence's saved state, and gets the phone back when that silence ends (see H2). The service also resumes after it drops a queued alarm.
- **Test:** `SilenceBugsTest.M7_pausedNapIsReappliedAfterPrayerWindowEnds` failed before the fix: after the window, the pending nap's silence is not active.
- **Location:** `wake/WakeAlarmScheduler.kt:97-119`; `wake/WakePlaybackService.kt:43` and `:147`
- **Problem:** `resumeSilenceUntilNextAlarm` returns early while auto or manual silence is on and leaves the pause marker set, so `isSilenceUntilAlarmActive` reports false. It is called only when the wake playback service stops.
- **Impact:** If a prayer window is active when one nap alarm's playback stops, the silence for another pending nap alarm is not re-applied afterwards.
- **Fix:** Retry the resume when the auto or manual silence ends.

### M8. Saving from an open editor writes back a stale snapshot
- **Status:** Verified (code path); needs a stop or skip while the editor is open
- **Fixed:** when the schedule is unchanged, `saveWakeConfig` keeps the skip and stop markers of the stored record, and it clears the skip registry only when the schedule changes or the alarm is disabled.
- **Test:** `WakeAndPrefsBugsTest.M8_savingAStaleEditorSnapshotKeepsTheStoppedOccurrence` failed before the fix: the stopped occurrence is null after the save.
- **Location:** `ui/WakeEditorSheet.kt:353` and `:446`; `wake/PrayerWakeRepository.kt:100-116` and `:128`; `wake/WakeAlarmScheduler.kt:231-266`
- **Problem:** The draft is `initialConfig.copy(...)`, where `initialConfig` was copied when the editor opened. `saveWakeConfig` takes the skip and stop markers from the config it is given, unless the schedule changed. If an occurrence is stopped while the editor is open (for example the alarm rings and is stopped), Save writes the old markers back and clears the skip registry.
- **Impact:** The stopped or skipped occurrence can come back, and its remaining extra alarms can ring again.
- **Fix:** Take the skip and stop markers from the stored record at save time.

### M9. A one-time prayer-relative alarm saved after midnight can be set a day late
- **Status:** Verified (code); needs an offset that reaches past midnight
- **Fixed:** `resolveOneTimeWakeTrigger` uses the preview's prayer days (`loadWakePreviewPrayerDays`, from yesterday), as the scheduler does.
- **Test:** `WakeAndPrefsBugsTest.M9_oneTimePrayerRelativeAlarmSavedAfterMidnightRingsTonight` failed before the fix: with the local clock at 00:10, an alarm due in 30 minutes is pinned 1,468 minutes ahead (run on 2026-10-10; the number depends on the day's Isha times). The test calls the private `resolveOneTimeWakeTrigger` through reflection.
- **Location:** `ui/WakeEditorSheet.kt:3868-3906` (day 0 start at `:3880`) and `:3781-3788` (preview, day -1 start); `multiplatform/shared/src/commonMain/kotlin/com/tunisianprayertimes/WakeAlarmComputer.kt:106-113` and `:195`
- **Problem:** The save path pins `oneOffTriggerAtMillis` using prayer days that start at day 0, while the preview and the scheduler start at day -1 (yesterday's prayer). For ONCE alarms the pinned trigger is what the computer uses. For example, "Isha + 3 hours" saved at 00:10 previews 00:30 today but is pinned to 00:30 tomorrow.
- **Impact:** The alarm rings about 24 hours later than the preview showed. Re-saving such an alarm in that window moves it again.
- **Fix:** Use the same day -1 window as the preview, for example by reusing `loadWakePreviewPrayerDays`.

### M10. Alarm list rows can show a stale "next ring"
- **Status:** Verified (code)
- **Location:** `ui/MainScreen.kt:4890-4904`
- **Problem:** Each row caches its next trigger with `remember(wakeAlarm, delegationId)`, which does not include `nowMillis`, while the list order is keyed on `nowMillis`. `PrayerWakeConfig` is a data class, so reloading an unchanged config does not invalidate the key.
- **Impact:** After a repeating alarm rings, its row keeps showing the past occurrence as the next ring, while the hero card is correct. It clears when the config changes or the Alarms tab leaves composition.
- **Fix:** Key the `remember` on `nowMillis` as the sort already does.

### M11. The overlap warning misses an earlier silence start on the next prayer
- **Status:** Verified
- **Fixed:** an UNSILENCE alarm keeps the silence while another enabled window, an Eid or carried-over one included, contains the current time (`SilenceScheduler.isOtherSilenceWindowActive`); that window's own end alarm ends it. The overlap warning also compares with the next prayer's window start, as well as its adhan.
- **Test:** `SilenceBugsTest.M11_unsilenceOfOnePrayerKeepsOverlappingWindowSilenced` failed before the fix: Maghrib's end restores normal mode while Isha's window is still active.
- **Location:** `ui/MainScreen.kt:5640-5648`; `SilenceScheduler.kt:274-277`; `SilenceReceiver.kt:75-76`
- **Problem:** The warning compares a window's end with the next prayer's adhan time (`window.endMinutes > nextMinute`). If the next prayer's silence starts before its adhan (negative start offset), the windows can overlap with no warning. The scheduler does not merge overlapping windows: each prayer's end alarm is scheduled on its own (`SilenceScheduler.kt:274-277`), and each UNSILENCE alarm calls `disableAutoSilence`, which restores normal mode without checking for another active window.
- **Impact:** The earlier end can cut the later window short.
- **Fix:** Compare with the next prayer's configured silence start, and skip the restore while another window is active.

### M12. A GPS fix in some border sectors turns prayer silencing off for hours
- **Status:** Verified (gate logic and data)
- **Fixed:** the GPS gate and `SilenceVerifyWorker` share one test, `isInsideTunisia` (`DelegationLocator.kt`): inside the widened border or the country outline. Fixes up to about 0.3° outside the outline now get the nearest Tunisian timetable instead of "outside Tunisia", without a locality name.
- **Test:** `LogicBugsTest.M12_fixInsideBorderSectorIsNotOutsideTunisia` failed before the fix: `resolveGpsLocation(33.305522, 7.765197)` returns `OutsideTunisia`, while `isInsideTunisiaBounds`, which the verify worker uses, counts the point as inside. By the app's own `neighborhoods.bin`, the point is inside the El Matrouha sector polygon (`osm:relation:7082336`, about 3.5 km from its edge) and about 1.6 km outside the country outline. The test expects such a fix to count as Tunisia, as the widened border does; if the chosen fix is to use the strict outline everywhere, the test has to change.
- **Location:** `DelegationLocator.kt:221-237` (gate at `:229-230`) and `:36-87` (the widened border polygon); `SilenceVerifyWorker.kt:106-130`; `ScheduleRefreshCoordinator.kt:70-73`; `ui/MainScreen.kt:1722-1731`
- **Problem:** A fix inside a sector polygon but outside the country outline returns `OutsideTunisia`, because the locator prefers the polygon index when it loads. The picker then cancels all silence alarms and sets the outside flag (`ui/MainScreen.kt:1722-1731`), which blocks rescheduling. Restoration waits for `SilenceVerifyWorker` (about every 6 hours), which tests only `isInsideTunisiaBounds` (`:129`): a coarse border polygon widened by about 0.3° for GPS error, while the index outline used by the gate has no margin. The review pass reported that one sector (`osm:relation:7082336`) extends past the outline, and decoding the bundled geometry confirms it (see Test).
- **Impact:** Prayer silencing can stay off for hours while the user sees "outside Tunisia".
- **Fix:** Use one Tunisia-membership test for the GPS gate and the verify worker, and clip sector polygons to the country outline.

### M13. Qibla location permission is not re-read after the user grants it in Settings
- **Status:** Verified
- **Location:** `ui/QiblaCard.kt:234` and `:381` (the only assignment), `:235`, `:413-431`
- **Problem:** `locationPermissionGranted` is set when the card is first composed and in the launcher callback only; there is no re-check on resume. After the user grants permission in Settings and returns, the card still says permission is required. `locationPermissionDeniedThisSession` is `rememberSaveable`, so it resets when the tab is left; the first banner tap after re-entry then goes to the launcher and shows a "denied" toast instead of opening Settings.
- **Fix:** Re-check the permission on `ON_RESUME`, and keep the "already denied" marker in preferences.

### M14. A silence window that crosses midnight is ended at 00:01
- **Status:** Verified (code)
- **Fixed:** `scheduleAllInternal` also evaluates yesterday's windows that end after midnight (`carriedOverWindows`), keeps their silence and sets their end alarm again; a reschedule just after that end sets today's alarm for the same prayer back. `currentSilenceWindowPrayer` and `currentSilenceWindowEnd` see these windows too. The shared `SilenceAlarmComputer` (desktop app) still looks at today's prayers only.
- **Test:** `SilenceBugsTest.M14_windowThatEndsAfterMidnightSurvivesTheMidnightReschedule` failed before the fix: the 00:01 run ends a window configured to last until 01:00.
- **Location:** `SilenceScheduler.kt:172-199` (only today's prayers are evaluated), `:243-277` (window check and end alarms), `:295-306` (restore when no window is active), `:321` and `:450-465` (midnight reschedule at 00:01), `:402-418` (alarms with the same identity replace each other), `:609-630` (`resolveUnsilenceTime`); `SilenceReceiver.kt:107-127`; `ui/PrayerTimelineModel.kt:147-173`; `ui/MainScreen.kt:2331` and `:5640-5648`; `multiplatform/shared/src/commonMain/kotlin/com/tunisianprayertimes/SilenceAlarmComputer.kt:43-95`; contrast `EidSilenceWindows.kt:23-42` and `:66-75`
- **Problem:** The editor and the scheduler both allow a window to end after midnight: a fixed end earlier than the start rolls to the next day (`resolveUnsilenceTime`, `resolvePrayerTimelineWindow`), an adhan-relative end can resolve past 1,440 minutes, and the overlap warning for Isha compares with tomorrow's Fajr. But `scheduleAllInternal` evaluates only today's prayers, and only Eid windows are carried over from the day before. At 00:01 a window that began yesterday is therefore not "in a window". The function ends the silence (`disableAutoSilence`), and it re-schedules today's end alarm for that prayer, which replaces the pending one because `setAlarmClock` treats the same `PendingIntent` as the same alarm. The 00:01 reschedule is set on every run, and any later refresh after midnight (opening the app, boot, a time change) does the same. The shared `SilenceAlarmComputer` model has the same shape, and the silence tests I searched have no case for a window that crosses midnight.
- **Trigger:** A custom Isha (or other late) window that ends after midnight, for example a fixed end of 01:00.
- **Impact:** The phone is un-silenced at 00:01, or at the first refresh after midnight, instead of at the configured end.
- **Fix:** In `scheduleAllInternal`, also evaluate the previous day's windows, as `EidSilenceWindows.hasNormalCarryOver` already does for the Eid path, and do not replace the end alarm of a window that is still active.

---

## Low

### L1. Re-selecting the current "From now" mode restarts its countdown
- **Status:** Verified; may be intended
- **Location:** `ui/WakeEditorSheet.kt:456-465` (`selectMainAlarmMode`), `:1023-1037`
- **Problem:** Every mode row, including the selected one, calls `onModeSelected`, and `selectMainAlarmMode` has no unchanged-mode check. Tapping "From now" on a one-off alarm with 20 of 30 minutes left sets the trigger to now plus the full offset, and Save persists it.
- **Fix:** Return early when `nextMode == mode`, if a restart is not intended.

### L2. Alarm volume stays at maximum when two alarm controllers overlap
- **Status:** Verified (code); needs both alarms ringing together
- **Location:** `wake/AlarmAudioController.kt:262-279`; used by `WakePlaybackService` and `AwakeCheckService`
- **Problem:** Each controller saves and restores `STREAM_ALARM` on its own (`savedAlarmVolume`). If the wake alarm saves the user's level, the awake check then saves the maximum, and the wake alarm stops first, the awake check restores the maximum.
- **Fix:** Use one reference-counted save and restore for the alarm stream.

### L3. A math answer can carry into the next alarm's challenge
- **Status:** Verified (key); the sequence is rare
- **Location:** `wake/WakeAlertActivity.kt:299`, `:438-442`, `:594-608`
- **Problem:** The answer state is `rememberSaveable(stepKey)` with `stepKey = completedSteps`, which is zero at the start of every alarm. If the displayed alarm changes while the composable stays in place, partly typed digits remain and are checked against the new challenge.
- **Fix:** Include the event id in the key.

### L4. Custom Eid prayer times persist across years
- **Status:** Verified; may be intended
- **Location:** `PrefsManager.kt:609-640`; `ui/MainScreen.kt:2204-2220`; `EidSilenceWindows.kt:55-62`
- **Problem:** The custom Eid times are stored without a date and are never cleared. They replace the computed time on every future Eid, and set that Eid's silence window. Eid moves about 11 days earlier each year.
- **Fix:** Store the date with the custom time, or ask again each year.

### L5. Switching an adhan-relative start to "Fixed" can move it a day
- **Status:** Verified (code); narrow
- **Fixed:** `withStartRule` converts a start to Fixed only when its clock time falls on the prayer's own day, the only kind of fixed start the scheduler can represent; otherwise the start stays adhan-relative. Tapping Fixed then does nothing, with no message.
- **Test:** `LogicBugsTest.L5_fixingAPreviousDayStartKeepsItOnThePreviousDay` failed before the fix: a start 7 hours before a 06:00 adhan (-60, 23:00 the day before) becomes 1380 (23:00 on the prayer's own day) after the switch to Fixed.
- **Location:** `ui/PrayerSilenceEditorSheet.kt:181-198`; `ui/PrayerTimelineModel.kt:115-128` and `:153-173`
- **Problem:** The resolved start can be negative (for example -60 for a 06:00 prayer whose start is 7 hours before adhan). `withStartRule` reduces it with `Math.floorMod` to 23:00 and stores it as a fixed clock time, which `resolvePrayerTimelineWindow` reads as 23:00 on the prayer's own day, so the start moves 24 hours later instead of staying on the previous day.
- **Fix:** Warn or block when the resolved start falls on the previous day.

### L6. A dragged handle cannot return exactly to its starting value
- **Status:** Verified
- **Location:** `ui/PrayerSilenceRangeSlider.kt:461-468`, `:996-1047`
- **Problem:** `silenceShiftedWindow` returns null when a move lands exactly on the base value (`if (newOffset == startOffset) null`), and `updateDrag` treats null as "keep the previous preview" (`?: current.window`). Dragging a handle away and back to its start therefore leaves it at the last non-null step (one step away in a slow drag, further after a fast move), and release commits that value.
- **Fix:** Return the base window for no-op candidates, and keep `current.window` only for rejected ones.

### L7. The Dhuhr row shows an active silence on Fridays that does not fire
- **Status:** Verified (code); intended behavior not confirmed
- **Location:** `ui/MainScreen.kt:2233-2241`; `multiplatform/shared/src/commonMain/kotlin/com/tunisianprayertimes/PrayerModels.kt:33-41`; `SilenceScheduler.kt:642`
- **Problem:** The list always shows both Dhuhr and Jomoaa. On Friday the scheduler uses `scheduledPrayers`, where Jomoaa replaces Dhuhr, so no Dhuhr silence is scheduled while the Dhuhr row shows an enabled switch and window.
- **Fix:** Hide or disable the Dhuhr row on Fridays, or label it.

### L8. The last live Qibla fix is restored as the current location with no age limit
- **Status:** Verified (code)
- **Location:** `ui/QiblaCard.kt:232-240`, `:309-313`, `:152`
- **Problem:** The process-wide cache of the last live fix (`cachedRealtimeQiblaLocation`) stores no time, and it is restored with `currentLocationIsLastKnown = false`, so the one-hour last-known limit at `:152` cannot apply to it. If the new lookup fails, the bearing stays based on the old position.
- **Fix:** Store the fix time with the cache and restore it as last-known only within the age limit.

### L9. Restoring a backup can re-apply another device's silence snapshot
- **Status:** Reported (medium confidence)
- **Location:** `app/src/main/res/xml/backup_rules.xml:6`; `app/src/main/res/xml/data_extraction_rules.xml`; `SilenceScheduler.kt:299`; `PrefsManager.kt:171-174`
- **Problem:** Backup and device transfer exclude only `assetpacks`, so `prayer_silence_prefs.xml` is included. It holds `auto_silence_active` and the captured ringer and DND state. A backup taken inside a window and restored on a phone with DND access can have the next sync apply the other phone's captured state. That state is normally just normal mode, so the effect is usually small.
- **Fix:** Exclude the silence snapshot from backup, or clear it on the first launch after a restore.

### L10. The capture script erases app data on whatever device it is given
- **Status:** Verified
- **Location:** `android-app/capture-screenshots.sh:18-21`, `:289-297`; `android-app/capture-7inch.sh:6`
- **Problem:** `pm clear` runs on the detected package, which falls back to the Play package, for the serial in `PHONE_SERIAL`, `TABLET_SERIAL` or `SERIAL`. The defaults are emulator serials, but nothing checks that an overridden serial is an emulator.
- **Fix:** Refuse serials that do not start with `emulator-`, and require a flag to clear a non-`.dev` package.

### L11. The silence-overlap card in the wake editor can never render
- **Status:** Verified
- **Location:** `ui/WakeEditorSheet.kt:700-703`, `:3759`, `:3940-3950`
- **Problem:** The card renders only when `warning.conflict == null`, but the only place a `WakeValidationWarning` is built always sets `conflict`. The recurring-only dialog (`:382-386`) excludes one-time and "from now" alarms, so those get no overlap warning. They still ring.
- **Fix:** Show the detail for non-blocking alarms, or delete the dead branch.

### L12. An extra alarm shows "same as main sound" when only the progressive volume differs
- **Status:** Verified
- **Fixed:** `soundMatches` compares `progressiveVolume` too.
- **Test:** `LogicBugsTest.L12_progressiveVolumeDifferenceIsNotTheSameSound` failed before the fix: `soundMatches` returns true for an extra alarm that differs from the main sound only in progressive volume.
- **Location:** `ui/WakeExtraAlarmRules.kt:126-138`; `ui/WakeEditorSheet.kt:2393`, `:2503`, `:2234`
- **Problem:** `withSoundOf` copies `progressiveVolume`, but `soundMatches` does not compare it. The sheet then hides the "use main sound" reset and drops the sound detail from the timeline, while the alarm uses its own volume ramp.
- **Fix:** Include `progressiveVolume` in `soundMatches`.

### L13. The alarm list polls every second while backgrounded
- **Status:** Verified
- **Location:** `ui/MainScreen.kt:4876-4881`
- **Problem:** A `LaunchedEffect` loop recomputes running state for every enabled alarm once per second for as long as the composition exists. It is not lifecycle-gated.
- **Fix:** Wrap it in `repeatOnLifecycle(STARTED)`, or run it only near an alarm window.

### L14. The "+" stepper truncates 9999 to 1000
- **Status:** Verified (editor side); negligible in practice
- **Location:** `ui/PrayerEndpointControls.kt:146`; `ui/PrayerSilenceEditorSheet.kt:215-226` (`take(4)`)
- **Problem:** `+` sends `current + 1` as text. At 9999 that is "10000", which the editor cuts to "1000". The four-digit field also makes the 10,080-minute limit unreachable.
- **Fix:** Allow five digits, or clamp.

### L15. The shared sheet's minute field refills with "0" when cleared
- **Status:** Verified (code and test); latent, because the app cannot open the shared sheet (see L30)
- **Fixed:** the shared sheet's minute fields show the typed text, so a cleared field stays empty and keeps its direction; Apply stays disabled until it holds a number.
- **Test:** `SilenceUiBugsTest.L15_clearedMinuteFieldStaysEmpty` failed before the fix: right after the field is cleared it shows "0". The test renders the sheet directly.
- **Location:** `ui/SharedSilenceSettingsSheet.kt:202`, `:211`, `:384`
- **Problem:** The shown value is derived from the parsed offset (`startOffset ?: 0`, `endOffset ?: 0`), not from the typed text, so clearing the field refills it with "0" at once and the user cannot retype from an empty field.
- **Fix:** Keep the typed text in local state.

### L16. The Fajr sheet's double-add guard is not saved
- **Status:** Verified (guard); the sequence is rare
- **Location:** `ui/FajrAlarmSuggestion.kt:283` (plain `remember`) and `:368-374`; `ui/MainScreen.kt:666`
- **Problem:** If the process is killed while the full-screen-intent settings screen is open after Add, the restored sheet reopens with `added == false`, and tapping Add again saves a second Fajr alarm.
- **Fix:** Save the flag with the parent's `fajrSetupOpen` state.

### L17. The compass "unstable" state lasts one heading sample
- **Status:** Verified
- **Location:** `ui/QiblaCard.kt:616-655`
- **Problem:** The effect is keyed on the live turn angle, which changes about 16 times per second. After it sets `Unstable` and waits 800 ms, the next sample cancels it, and the final branch (`:647-654`) overwrites the status with `Settling`.
- **Fix:** Run the timer in one long-lived effect that reads the latest angle through `rememberUpdatedState`.

### L18. Accelerometer and manual compass math run although the rotation vector always wins
- **Status:** Verified (code)
- **Location:** `ui/QiblaCard.kt:1581-1583`, `:1655-1671`, `:1696-1702`
- **Problem:** The reading is `rotationVectorReading ?: geomagneticRotationReading ?: manualCompassReading`, yet whenever the phone has a magnetic compass the accelerometer and magnetometer are registered, and the manual rotation-matrix math runs on every event, with its result unused while a rotation vector exists.
- **Fix:** Skip the accelerometer and the manual path when a rotation-vector sensor is present.

### L19. Paint and Typeface are rebuilt on every dial redraw
- **Status:** Verified (code)
- **Location:** `ui/QiblaCard.kt:967-970`
- **Problem:** The draw block allocates a new `Paint` and `Typeface.create(...)` on each heading sample, and the tick loop allocates boxed values.
- **Fix:** Hoist them out of the draw lambda and precompute tick geometry.

### L20. The location listener fires once per changed key, queuing repeated refreshes
- **Status:** Verified
- **Fixed:** `observeLocationSelection` posts one callback per batch of changed keys to the main thread, and unsubscribing drops a pending one.
- **Test:** `WakeAndPrefsBugsTest.L20_oneLocationSaveNotifiesTheListenerOnce` failed before the fix: one `setLocality` call notified the listener 5 times.
- **Location:** `PrefsManager.kt:141-149`; `TunisianPrayerTimesApplication.kt:35-52`
- **Problem:** One location save changes several keys in one `apply()`, and the listener runs once per key. Each run starts a full Adhkar and Hifz reminder refresh with no debounce.
- **Fix:** Coalesce location-triggered refreshes into one pending job.

### L21. The 150 ms `Thread.sleep` in the silence restore runs on the main thread in some paths
- **Status:** Verified (call sites); awake-check path reported
- **Location:** `SilenceModeController.kt:39-51`; `nap/NapSilenceController.kt:43-46`; `ui/MainScreen.kt:945-947`; `wake/AwakeCheckSilencePolicy.kt:89`; `wake/AwakeCheckService.kt:38` and `:156`
- **Problem:** `restoreState` and `disableNapSilence` block for 150 ms. The main-screen click handlers call them on the main thread, and the awake-check service uses a `Dispatchers.Main` scope for its escalation job, which the review pass reports as calling the policy.
- **Fix:** Move the restore off the main thread, or replace the sleep with a state check.

### L22. A retry inside SilenceReceiver can escape as an uncaught exception
- **Status:** Verified (structure); rare
- **Location:** `SilenceReceiver.kt:107-127`
- **Problem:** In the reschedule branch, the `catch` block calls `scheduleAll` again with no guard. If it throws, the exception escapes the `launch` block, which has no exception handler, and the process crashes.
- **Fix:** Wrap the retry in its own try and catch, or remove the duplicate call.

### L23. The capture script exits 0 after failed captures
- **Status:** Verified
- **Location:** `android-app/capture-screenshots.sh:373-382`
- **Problem:** `FAILED` is set when a device job fails but never read, and the script ends with `ls`. A failed capture still exits with status 0.
- **Fix:** After the wait loop, exit 1 when `FAILED` is true.

### L24. The capture script defaults to a macOS SDK path
- **Status:** Verified (line); consequence reported
- **Location:** `android-app/capture-screenshots.sh:12`
- **Problem:** `ANDROID_HOME` defaults to `$HOME/Library/Android/sdk`, which exists only on macOS. On Windows or Linux with `ANDROID_HOME` unset and `adb` not on PATH, the script cannot find `adb`.
- **Fix:** Check that `adb` is on PATH first, or probe the platform's default SDK location.

### L25. Bundled data with no production reader
- **Status:** Verified (no caller found in main code)
- **Location:** `app/src/main/assets/mosques/tunisia-mosques.geojson` (3.07 MB); `app/src/main/assets/delegation_boundaries.json` (0.68 MB); `mosque/MosqueRepository.kt:68`; `DelegationBoundaryRepository.kt:47`
- **Problem:** `MosqueRepository` and `DelegationBoundaryRepository.findDelegationForLocation` have no callers under `app/src/main/java`; only tests use them. The assets add about 3.7 MB to the APK.
- **Fix:** Remove the assets until a reader exists, or load them only in the feature that needs them.

### L26. The Hosary timings' per-surah `sha256` matches neither the MP3s nor the manifest
- **Status:** Reported (data pass)
- **Location:** `app/src/main/assets/quran/audio/hosary/timings.json`; `quran-assets/manifest.tsv`
- **Problem:** The per-surah `sha256` field in the Hosary timings matches neither the packaged MP3s nor the manifest. The data pass found it unused at runtime.
- **Fix:** Remove the field or regenerate it.

### L27. The neighborhoods license note claims data that is not in the features
- **Status:** Reported (data pass)
- **Location:** `app/src/main/assets/NEIGHBORHOODS-LICENSE.txt`
- **Problem:** The note says the 21 Bouarada polygons are integrated, but all 21 municipal ids are retired and absent from the features.
- **Fix:** Correct the note, or add the polygons.

### L28. The end-of-window fallback forces normal ringer mode and DND off
- **Status:** Verified; documented as a safety net
- **Fixed:** when the flag is already cleared, the receiver forces normal mode only if the phone is still silenced (`SilenceStatus.isPhoneSilenced`) and no nap silence owns it. A user who keeps the ringer on silent still gets normal mode at the end of a dismissed window.
- **Test:** `SilenceBugsTest.L28_endOfWindowFallbackKeepsTheUsersVibrateMode` failed before the fix: the end alarm turns the user's vibrate mode (1) into normal (2).
- **Location:** `SilenceReceiver.kt:61-85`; `SilenceModeController.kt:170-191` (`forceNormalMode`); `SilenceScheduler.kt:246-256`
- **Problem:** When an UNSILENCE alarm fires and the auto-silence flag is already cleared, `SilenceReceiver` calls `forceNormalMode`, which sets the ringer to normal and the interruption filter to "all" whatever the phone's current state is. The flag is already cleared when the user dismissed the silence in the app (the scheduler keeps the end alarm in that case) or a refresh ended it. A user who keeps the phone on vibrate and dismissed the silence has the ringer turned up at the end of the window. The code comment says this is deliberate, so that DND is never left on.
- **Fix:** Restore only when the phone is still silenced (DND "none" or the silent ringer), or restore a captured previous state when one exists.

### L29. The analytics silence count ignores the per-prayer settings
- **Status:** Verified
- **Fixed:** `silencePrayerCount` counts the prayers whose silence is enabled.
- **Test:** `WakeAndPrefsBugsTest.L29_analyticsSilenceCountCountsEnabledPrayers` failed before the fix: with only Fajr enabled, the count is 8.
- **Location:** `AnalyticsTracker.kt:251-252` (`silencePrayerCount`); used at `:81` and `:205`, and by `BootReceiver.kt`, `SilenceVerifyWorker.kt` and `wake/WakeAlarmVerifyWorker.kt`
- **Problem:** `silencePrayerCount` returns `Prayer.values().size` (8) whenever prayer silence is enabled, and 0 otherwise, so the `silence_alarm_count_bucket` and `active_prayer_count_bucket` analytics values are always "6_plus" or "0", whichever prayers the user turned on.
- **Fix:** Count the prayers for which `PrefsManager.isPrayerSilenceEnabled` is true.

### L30. `+` and `-` run backwards for "before adhan" in the shared silence sheet (latent)
- **Status:** Verified (code and test); latent, because the app cannot open the sheet. Formerly H4.
- **Fixed:** `+` and `-` step the shown minutes and keep the selected direction; at adhan there is no minute field, as in the endpoint editor.
- **Test:** `SilenceUiBugsTest.L30_sharedSheetPlusIncreasesTheShownMinutesBeforeAdhan` failed before the fix: with the start 10 minutes before adhan, `+` shows 9 instead of 11. The test renders the sheet directly.
- **Location:** `ui/SharedSilenceSettingsSheet.kt:374-397`; `ui/MainScreen.kt:2060`, `:2469`, `:2526-2558`
- **Problem:** The field shows `abs(offsetValue)` (`:384`), but the buttons add or subtract from the signed value: `onIncrease = { onOffsetChange((offsetValue + 1).toString()) }` (`:390`). With "before adhan" selected (offset -10), `+` gives -9, which shows as 9, and `-` gives -11, which shows as 11. Stepping through zero flips the direction to "after adhan". The single-endpoint editor (`ui/PrayerEndpointControls.kt:141-146`) steps the magnitude and is correct, so the two sheets disagree.
- **Why latent:** `MainScreen.kt` shows the sheet only while `sharedSheetOpen` is true (`:2469`). That state starts false (`:2060`) and is only ever set to false (`:2530`, `:2558`). No commit in the current branch's history sets it to true. On older branches (for example `codex-2`), commit `c5d382e5` (2026-09-18) added a `SharedSettingsAction(onClick = { sharedSheetOpen = true })` button, and `b10d32aa` removed it the same day. For the same reason the bug could not be reproduced on the Pixel 6.
- **Impact:** None while the sheet is unreachable. If the button comes back, this is a High bug again.
- **Fix:** Step the magnitude and re-apply the sign from the selected direction.

---

## Reproduction on a Pixel 6

- **Device:** the user's Pixel 6, Android 16 (SDK 36), time zone Europe/Paris, over wireless adb. The other connected devices were not used.
- **Build:** the `com.tunisianprayertimes.dev` build already on the phone (versionName 2.144, versionCode 155, installed 2026-10-10 13:31, signed with the local debug key). Nothing was installed. The installed `base.apk` has the same SHA-256 as the local `app/build/outputs/apk/debug/app-debug.apk`, and a local `assembleDebug` found that APK up to date with the current source. The Play build and the `.debug` build were not touched.
- **Before:** the `.dev` app's data was backed up, and the phone's state was recorded: DND off, ringer normal, alarm volume 7. The alarm volume was changed during the session.
- **Evidence:** `zen_mode` and `mode_ringer` from `settings get global`, `dumpsys audio`, `dumpsys alarm` and the app's preference files, read at the times given under each finding.

| Finding | Result on the Pixel 6 |
|---|---|
| H3 | Reproduced: the preview kept playing with the app in the background (19:08) |
| H2 | Reproduced with the real Isha window: after the nap alarm the phone stayed in DND with the ringer silent (19:16-19:52) |
| H1 | Reproduced with a force-stop in place of a reboot: the silence outlived its end until the app was opened (19:55-19:59) |
| L30, L15 | Not reproducible: the app cannot open the shared sheet |
| All others | Not tried. Most need a set time, date or sequence (a window that crosses midnight, Ramadan, a Friday, a pending awake check). H4 and 15 Medium and Low findings are covered by tests instead |

- **After:** the app data was restored from the backup, and the files the session created were deleted (`nap_prefs.xml`, `awake_check_group_dismissals.xml` and the wake DataStore file `prayer_wake_store.preferences_pb`). The app was opened and re-armed its 13 alarms for 2026-10-11. The app's DND mode was turned off in Settings > Modes. DND is off, the ringer is normal, and the alarm volume is back to 7. The ring and notification volumes read 1 of 7 at the end. I did not set them, but their level before the session was not recorded, so check them.
- **Android 16 notes:** `cmd media_session volume --stream 4 --set N` had no effect, so the alarm volume was set with the Settings slider. `cmd notification set_dnd off` did not clear the app's DND mode (`implicit_com.tunisianprayertimes.dev`); only Settings > Modes did.

## Validation on the Pixel 6 (revision 5)

On 2026-10-11 I installed the fixed build on the same Pixel 6 and ran the three scenarios reproduced in revision 3 again. All three now behave correctly.

- **Build:** `assembleDebug` of the revision 4 code, installed with `adb install -r` over the `.dev` app, which keeps its data. Same version (2.144, code 155); SHA-256 `408d2e6a…`, checked on the phone. The APK contains the new code (for example `restoreTimer`, `takeOverSilence`, `isOtherSilenceWindowActive`).
- **Before:** I copied the old APK (`4389a9a3…`) and the `.dev` app's data, then recorded the phone's state: DND off, ringer normal, the app's DND mode inactive, alarm volume 7, ring and notification volumes 1.
- **Evidence:** `settings get global zen_mode` / `mode_ringer`, `dumpsys audio`, `dumpsys alarm` (including its statistics, which restart at boot), the `events` log and the app's preference files, read at the times given.

| Finding | Before the fix (revision 3) | With the fix (2026-10-11) |
|---|---|---|
| H3 | 21 s after Home, the preview's `MediaPlayer` was still `state:started` | Playing in the foreground at 14:41:25 (`state:started`, button "إيقاف المعاينة"). After Home, no player for the app at 3 s and at 21 s, and nothing playing on the phone. Back through Recents, the editor was still open and the button read "تشغيل المعاينة" again |
| H1, force-stop | After a force-stop the end alarm was gone; the phone stayed silent until the app was opened | 3-minute silence from 14:44:44, end alarm 14:47:40. After `am force-stop` the app had no alarms. Reopening the app re-armed the end alarm for 14:47:40; I then went Home and killed the app process. At 14:48:11 the phone was back to normal and manual silence was off: the re-armed alarm had fired and started the app |
| H1, reboot | Not tried | 3-minute silence from 15:10:13, end 15:13:10; `adb reboot` at 15:10:25. The user unlocked at 15:11:05; at 15:11:49 the system started the app for `BootReceiver` (its only start since the boot, no screen opened), and the end alarm was re-armed by 15:11:55. The alarm statistics show it fired once, at about 15:13:10.5. At 15:20:00 the Asr window recorded the phone's previous state as ringer normal and DND off |
| H2 | The nap saved the window's silenced state (ringer silent, DND "none"); at the window end the phone went normal; after the nap alarm it stayed in DND, and the app's DND mode had to be turned off in Settings | Quick timer saved at 15:24:42 in the real Asr window (15:20-15:50): the nap saved ringer normal and DND off. At 15:50 the window ended and the phone stayed silent for the pending nap. At 15:53:48 the nap alarm rang and the phone returned to normal; after I stopped the alarm, and a minute later, the phone was normal and the app's DND mode inactive (`STATE_FALSE`) |

- **After:** I restored `prayer_silence_prefs.xml` from the copy (it then matched exactly), deleted the files the tests created (`nap_prefs.xml`, `awake_check_group_dismissals.xml`, `prayer_wake_store.preferences_pb`), and relaunched the app. It re-armed its alarms for the rest of the day (Maghrib 17:51-18:11, Isha 19:15-19:45). DND is off, the ringer is normal, and the app's DND mode is inactive; the alarm, ring and notification volumes are unchanged (7, 1, 1). The fixed build stays installed. The old APK and the data copy are kept on the computer, outside the repository, in case they are needed. I removed the temporary files from the phone.
- **Android 16 notes:** the reboot turned Wireless debugging off; it had to be turned back on on the phone before adb could reconnect. The boot broadcast arrived 44 s after the unlock.
- **Not re-checked on the device:** the other 18 fixed findings. Their scenarios need a set date or time (a window that crosses midnight, Ramadan, a Friday), a specific sequence, or the shared sheet the app cannot open. Their unit tests cover them.

## Tests

22 tests in `app/src/test/java/com/tunisianprayertimes/bugreport/` cover 20 findings. Each test asserts the correct behavior: it failed while its bug was present (revision 3) and passes now that the bug is fixed (revision 4). Setup steps that a test depends on are asserted first (some with a "Precondition" message), so a broken setup fails at a different line from the bug. The tests use the project's existing test dependencies (JUnit 4, Robolectric, Compose UI test).

| File | Findings | Setup |
|---|---|---|
| `BugReproSupport.kt` | none (helpers) | |
| `SilenceBugsTest.kt` | H1 (2 tests), H2 (2 tests), M7, M11, M14, L28 | Robolectric, SDK 33 |
| `WakeAndPrefsBugsTest.kt` | M1, M3, M6, M8, M9, L20, L29 | Robolectric, SDK 33 |
| `LogicBugsTest.kt` | M5, M12, L5, L12 | Robolectric, SDK 33 |
| `SilenceUiBugsTest.kt` | H4, L15, L30 | Compose UI test on Robolectric, SDK 35, Arabic (Tunisia), 360 x 800 dp |

**Gate (removed in revision 4).** In revision 3 the tests were skipped unless the environment variable `BUG_REPRO` was `1`, because CI (`.github/workflows/android-tests.yml`) runs `./gradlew testDebugUnitTest` on pushes and pull requests to `main`, and 22 failing tests would have turned it red. Revision 4 fixed all 22 and removed the gate, so they now run with the other unit tests, CI included, and guard against regressions.

**Run.** From `android-app` (on Windows, `gradlew.bat` with the same arguments):

```
./gradlew :app:testDebugUnitTest --tests "com.tunisianprayertimes.bugreport.*"
```

**Results.** Before the fixes (revision 3, run with `BUG_REPRO=1` at 20:11 on 2026-10-10), all 22 tests failed, each at the assertion for its bug, not at a precondition or in setup (table below). After the fixes (revision 4, 2026-10-11), all 22 pass.

| Test | Finding | Failure before the fix |
|---|---|---|
| `SilenceBugsTest.H1_bootRearmsAnUnexpiredTimedManualSilence` | H1 | no manual-silence end alarm after the boot refresh |
| `SilenceBugsTest.H1_bootEndsAManualSilenceThatExpiredDuringTheReboot` | H1 | manual silence still active (`expected:<false> but was:<true>`) |
| `SilenceBugsTest.H2_napCreatedDuringPrayerWindowLeavesPhoneNormalAfterItRings` | H2 | ringer silent instead of normal (`expected:<2> but was:<0>`) |
| `SilenceBugsTest.H2_napRingingDuringPrayerWindowLeavesPhoneNormalAfterWindow` | H2 | ringer silent instead of normal (`expected:<2> but was:<0>`) |
| `SilenceUiBugsTest.H4_trackTapUsesTheLatestCommitCallback` | H4 | the tap used the old callback (`expected:<[2]> but was:<[1]>`) |
| `WakeAndPrefsBugsTest.M1_alarmAllowedOverPrayerWindowIsPlayedWhileAnotherNapIsPending` | M1 | nothing playing (`expected:<wake:main:A> but was:<null>`) |
| `WakeAndPrefsBugsTest.M3_ramadanIshaOverrideIsRevertedAfterRamadan` | M3 | Isha duration after Ramadan `expected:<30> but was:<90>` |
| `LogicBugsTest.M5_untilDhuhrOnFridayEndsAtTheCustomJomoaaTime` | M5 | ends at 12:12, 63 minutes before the 13:15 Jomoaa |
| `WakeAndPrefsBugsTest.M6_deletingAnAlarmCancelsItsPendingAwakeCheck` | M6 | the awake check is still scheduled |
| `SilenceBugsTest.M7_pausedNapIsReappliedAfterPrayerWindowEnds` | M7 | the pending nap's silence is not active |
| `WakeAndPrefsBugsTest.M8_savingAStaleEditorSnapshotKeepsTheStoppedOccurrence` | M8 | stop marker cleared (`expected:<1792036800000> but was:<null>`) |
| `WakeAndPrefsBugsTest.M9_oneTimePrayerRelativeAlarmSavedAfterMidnightRingsTonight` | M9 | rings in 1,468 minutes instead of about 30 |
| `SilenceBugsTest.M11_unsilenceOfOnePrayerKeepsOverlappingWindowSilenced` | M11 | silence ended while Isha's window was active |
| `LogicBugsTest.M12_fixInsideBorderSectorIsNotOutsideTunisia` | M12 | `OutsideTunisia` |
| `SilenceBugsTest.M14_windowThatEndsAfterMidnightSurvivesTheMidnightReschedule` | M14 | silence ended by the 00:01 run instead of at 01:00 |
| `LogicBugsTest.L5_fixingAPreviousDayStartKeepsItOnThePreviousDay` | L5 | start `expected:<-60> but was:<1380>` |
| `LogicBugsTest.L12_progressiveVolumeDifferenceIsNotTheSameSound` | L12 | `soundMatches` returned true |
| `SilenceUiBugsTest.L15_clearedMinuteFieldStaysEmpty` | L15 | the cleared field shows "0" |
| `WakeAndPrefsBugsTest.L20_oneLocationSaveNotifiesTheListenerOnce` | L20 | 5 notifications instead of 1 |
| `SilenceBugsTest.L28_endOfWindowFallbackKeepsTheUsersVibrateMode` | L28 | ringer normal instead of vibrate (`expected:<1> but was:<2>`) |
| `WakeAndPrefsBugsTest.L29_analyticsSilenceCountCountsEnabledPrayers` | L29 | count 8 instead of 1 |
| `SilenceUiBugsTest.L30_sharedSheetPlusIncreasesTheShownMinutesBeforeAdhan` | L30 | the field shows "9" instead of "11" after `+` |

**Whole suite.** I compared the full `testDebugUnitTest` run with the same run on HEAD (commit `f9ef43cc`, without the fixes), checked out in a separate git worktree.
- With a 2 GB test heap, both run the same 1,024 tests and fail the same 30. The only difference is the 22 bug tests: skipped on HEAD, passing with the fixes.
- The 30 shared failures are in `LocalityRepositoryTest` (8), `NeighborhoodRepositoryTest` (4), `DelegationLocatorTest` (3), `AdhkarFlowTest` (2), `AlarmServiceSilenceGuardTest` (6), `AwakeCheckSilenceSuppressionTest` (4), `WakeAlarmSilenceSuppressionTest` (2) and `QiblaCardTest` (1). I did not investigate them.
- With the default 512 MB test heap, the test JVM runs out of memory on HEAD too: it spends its last stretch in about 1,600 full garbage collections at 510 of 512 MB, then crashes. A plain run therefore stops early, and the three wake and awake-check classes above never run. Raising `maxHeapSize` for unit tests in `app/build.gradle.kts` would fix this; I did not change it. For these runs I passed a temporary Gradle init script that sets `maxHeapSize = "2g"`.

**Limits.**
- Robolectric shadows stand in for AlarmManager, AudioManager and NotificationManager, so the tests show the app's own logic, not the platform's behavior. That is why H1 and H2 were also run on the device.
- H1's tests call `BootReceiver.refreshSchedules` directly; it is the method the boot broadcast runs (`BootReceiver.kt:29`).
- M5 and M9 call private top-level functions through reflection (`MainScreenKt.resolveManualSilencePrayerEndMillis` and `WakeEditorSheetKt.resolveOneTimeWakeTrigger`), because the logic sits in UI files. If those functions are renamed or their parameters change, the tests fail with `NoSuchMethodException` instead of an assertion.
- M9 and M11 set the default time zone so that the local clock reads a chosen time (`withLocalClockAt`), and use the bundled prayer times for the run date, so M9's exact number changes from day to day.
- H4's bug shows only when the slider has had a touch before the config change (see H4).
- M12 encodes one choice of fix (see M12).
- L15 and L30 render `SharedSilenceSettingsSheet` directly, because the app never opens it.
- Before the fix, M3 stopped at its first assertion (the Isha duration); it now passes that and the end-offset check.
- The H1 test that ends an expired silence turns prayer silence off. Otherwise a prayer window active at the real time of the run would silence the phone again and fail it.
- Revision 4 also changed 7 existing tests in `AlarmCoverageTest`, `SilenceStateTest` and `PhoneCallVibrationTest`. They fire SILENCE and UNSILENCE at the real clock, and with the M11 fix an UNSILENCE during another prayer's default window keeps the silence. They now use the delegation 99999, which has no prayer times and is already used elsewhere in those files. No assertion changed.

**Not tested** (28 findings):
- H3: needs an activity that goes to the background; reproduced on the device instead.
- M2, M4, M10, M13: state held inside large composables (the night timeline, the manual silence card, the alarm list) or a round trip through system Settings.
- L1, L4, L7: the behavior may be intended.
- L2, L3, L6, L8, L11, L13, L14, L16-L19, L21, L22: gestures, timing, sensors, process death, performance, dead code or fault injection; not attempted.
- L9, L10, L23, L24, L25-L27: backup rules, shell scripts and bundled data.

## Open questions (not verified)

- `SilenceGuardService.onCreate` calls `startForeground` unconditionally on a START_STICKY restart. This could miss the foreground-start deadline.
- Background-start rules for `startForegroundService` from alarm receivers (`WakeAlarmReceiver`, `AwakeCheckReceiver`) were not checked against platform rules.
- `WakeAlarmQueue` has no synchronization. `WakeAlarmScheduler.scheduleAll` runs on IO receiver coroutines and calls `discardSkippedOccurrence`, while UI code can modify the same queue. Possible race, not traced.
- `PhoneStateReceiver` is exported with a `PHONE_STATE` filter (`AndroidManifest.xml:122-128`). If another app could send that action, the only effect would be the missed-call flag, so the impact would be small.
- A vibration-only alarm combined with the gyroscope maze: the maze resets its hold timer on linear-acceleration spikes above 12 to 20 m/s², and the alarm's own vibration might trip them on some devices. With a working ringtone no vibration runs, so this needs a device test.
- Intended behavior for: the Friday Dhuhr row (L7), re-selecting "From now" (L1), and Eid times across years (L4).
- M1 is shown by a Robolectric test only. Its sequence (a prayer window that starts during one nap, then another alarm allowed to ring over the window) was not run on a device.
- H1 was reproduced with a force-stop, which clears the app's alarms as a reboot does. The fix was checked after both a force-stop and a real reboot (see "Validation on the Pixel 6"). Before the user unlocks the phone after a reboot, the app cannot run (its preferences are in credential-encrypted storage), so a manual silence can only end after the unlock.
- Whether the shared silence sheet (L15, L30) is meant to come back. If it is, L30 is High again.
- Choices made by the revision 4 fixes that you may want to change:
  - **H2:** if the user dismisses a prayer window or stops a manual silence while a nap alarm is pending, the phone stays silenced until that alarm, because the nap asked for silence. The main screen still shows the "normal mode restored" toast.
  - **M1:** an alarm allowed to ring over a prayer window also lifts another pending nap's silence while it rings.
  - **M3:** any Isha edit during Ramadan keeps 90 minutes after Ramadan, even a change to the start only, because the editor saves the whole window.
  - **M5:** on Fridays the card, the toast and the hero line still say Dhuhr while the silence ends at the Jomoaa time.
  - **M11:** the overlap warning compares with the earlier of the next adhan and the next window's start, so it can only add warnings.
  - **M12:** fixes up to about 0.3° outside the country outline, near the Algerian or Libyan border or at sea, now get the nearest Tunisian timetable instead of "outside Tunisia".
  - **L5:** tapping Fixed for a start on another day does nothing and shows no message; a message needs a new string resource.
  - **H3:** the preview still takes no audio focus.
- The 30 unit test failures shared with HEAD (see "Tests") include 12 in wake and awake-check silence suppression tests. They predate this work and were not investigated.

## Coverage

| Area | Coverage |
|---|---|
| Wake alarm scheduling, queue, repository, receivers | Reviewed by one pass (some files only in the regions cited); key paths re-checked (H2, M1, M6, M7) |
| Wake playback service, alert activity, audio controller, ringtone catalog, awake check | Reviewed in full by one pass; re-checked M1, M6, L2, L3 |
| `ui/WakeEditorSheet.kt` and its helpers | Reviewed in full (two passes: lines 1-2000 and 1828-4056) |
| Wake mini-games and extra-alarm UI | Gyroscope maze (generation, physics, sensor cleanup) and whack-a-mole read directly: no bugs found. The extra-alarm slider and next-alarm timeline are **not reviewed** (`WakeExtraAlarmRules` read in part) |
| Silence system | Mostly reviewed. `SilenceScheduler`, `SilenceReceiver`, `SilenceVerifyWorker`, `SilenceGuardService`, `SilenceModeController`, `ManualSilenceScheduler`, `ScheduleRefreshCoordinator`, `SilenceStatus`, `PhoneStateReceiver` and the nap files were read in full (H1, H2, M7, M11, M14, L28). `EidSilenceWindows` was read in part (lines 1-96) |
| App bootstrap and activities | Partly. `BootReceiver` and `OnboardingActivity` in full; `TunisianPrayerTimesApplication` lines 24-59; `MainActivity` and `MainActivityPendingIntents` read for wiring only (`MainActivity.kt` was edited on the day of the review) |
| `ui/MainScreen.kt` | Partly. Lines 2797-7062 were read in full by one pass. Lines 1-3400 were assigned to another pass whose report was cut off after four findings, so how much it read is unknown. Other passes and I read parts of lines 1-2796 for specific findings (for example 404-516, 895-1014, 1300-1363, 2196-2335) |
| Prayer silence UI (range slider, sections, endpoint controls, editor, shared sheet, hero) | Reviewed in full by one pass (H4, L4-L6, L14, L15, L30) |
| Qibla (`QiblaCard`, compass, direction, location, route illustration, method diagrams) | Reviewed in full by one pass |
| Method and calendar dialogs, Fajr suggestion, timeline model, calendar lifecycle, Hijri dates | Reviewed by one pass (`DateNavigationRow` partly); only L16 found |
| `ui/OnboardingScreen.kt` | Partly (three ranges read by the prayer-silence pass) |
| Adhkar core (`PrefsManager`, `DhikrModels`, receiver, repair worker, scheduler, repository) | Reviewed in full by one pass (M3, L20). I checked the scheduler's PendingIntent identity: each reminder uses a distinct data URI, so there is no collision |
| Adhkar UI (`AdhkarScreen`, `AdhkarReader`, `AdhkarReminderEditor`, dialogs) | **Not reviewed** |
| Quran playback (`QuranPlaybackService`, `QuranAudioController`, playlist, repeat, verse steps) | **Not reviewed** (pattern searches only) |
| Quran pack install (`quran/assets/*`, download worker) | Read directly in full: `QuranPackInstaller`, `CdnQuranPackSource`, `QuranPackDownloadWorker`, `QuranAssets` (with `PlayOrCdnPackSource`), `QuranPackLayout` and `PlayQuranPackSource`. No bugs found: the installer checks entry names, size and SHA-256, rejects zip-slip, and moves a pack into place with one rename. The two small interface files were not read |
| Quran index and search (`QuranLibrary`, `QuranRepository`, `QuranHighlights`) | Read only to check data-file contracts; `QuranSearch` not read |
| Hifz (`Hifz`, `HifzReminder`, `HifzText`, `HifzHome`, `HifzSheets`) | Mostly **not reviewed**; the `hidden` logic in `HifzText` was checked (no bug) |
| Quran UI (`QuranScreen`, dock, expanded player, index, downloads, reciter, repeat, search) | **Not reviewed** |
| Locality and location (`LocalityRepository`, `DelegationLocator`, `NeighborhoodRepository`, `GouvernoratRepository`) | `DelegationLocator` in full by the Qibla pass (M12); the others read for data contracts only. The locality picker sheet was not reviewed |
| Other helpers | `WorldMagneticModel` (no bug; documented valid to 2030), `AnalyticsTracker` (L29) and `PrayerTimesRepository` read in full. `mosque/*` not reviewed (unused, see L25) |
| Build files, manifest, resources, release and capture scripts | Reviewed (L9, L10, L23, L24) |
| Bundled data (JSON assets, geometry, Quran pack manifests) | Reviewed for structure and cross-references (L25-L27) |
| Unit tests and instrumented tests | **Not reviewed** (one test read as evidence for M3). Revision 3 added the `bugreport/` tests, see "Tests" |

Items marked **Not reviewed** were not covered because the review passes covering them returned no results, including after retries. They need a second pass before the report can be called complete.

## Revision history

### Revision 5

- Installed the fixed build on the Pixel 6 and ran H1, H2 and H3 again. All three now behave correctly; H1 was checked after a force-stop and after a real reboot (see "Validation on the Pixel 6").
- Restored the phone afterwards. The fixed build stays installed.

### Revision 4

- Fixed the 21 confirmed findings: H1-H4, M1, M3, M5-M9, M11, M12, M14, L5, L12, L15, L20 and L28-L30. Each has a "Fixed" line. No build file was changed.
- Removed the `BUG_REPRO` gate. All 22 bug tests pass and now run in every `testDebugUnitTest`, CI included.
- Made the H1 expired-silence test independent of the time of day, and moved 7 existing tests to the delegation without prayer times for the same reason (see "Tests").
- Compared the whole unit test suite with HEAD: the same 30 tests fail with and without the fixes, and the default 512 MB test heap runs out on HEAD too.
- The fixes were not re-checked on the Pixel 6.

### Revision 3

- Reproduced H1, H2 and H3 on the user's Pixel 6 with the installed `.dev` build, then restored the phone (see "Reproduction on a Pixel 6").
- Added 22 tests for 20 findings in `bugreport/`, skipped unless `BUG_REPRO=1` so that CI stays green (see "Tests"). With the gate on, all 22 fail at the assertion for their bug.
- Found that the app cannot open the shared silence sheet, so the old H4 (`+` and `-` backwards in that sheet) is now L30, and L15 is marked latent. The old H5 (stale slider tap) is now H4, verified by its test. New counts: 4 High, 14 Medium, 30 Low.
- M12: decoded the bundled geometry to confirm the data claim, and corrected the description of `isInsideTunisiaBounds` (a widened border polygon, not a box).
- Added the Android 16 behavior seen on the device to H2.

### Revision 2

- Read the rest of the silence system directly: `SilenceScheduler` in full, `SilenceVerifyWorker`, `SilenceGuardService` and the whole of `SilenceReceiver`. This found M14 (a window that crosses midnight is ended at 00:01) and L28 (the end-of-window fallback forces normal mode).
- Read the Quran pack download and install code, `AnalyticsTracker` (L29), `WorldMagneticModel`, both wake mini-games and `PhoneStateReceiver`. No bugs in the pack code, the maze or the magnetic model.
- Upgraded M11, L5, L8, L15, L18 and L19 from Reported to Verified, corrected the wording of L15, and updated the locations in M12.
- Updated the coverage table and the open questions.

### Revision 1

- Corrected the line-count note: the earlier 6,765 for `ui/MainScreen.kt` skipped blank lines, and the file has no carriage returns.
- Corrected the coverage table: `ui/MainScreen.kt` is partly covered, the wake playback and awake-check files were read in full, and the calendar files were reviewed.
- Re-rated on the evidence: the service-side silence check (now M1), the Ramadan override (M3), the stale "until prayer" target (M4) and the timeline crash (M2) moved from High to Medium. The stale slider tap (then H5, now H4) is now Verified rather than Reported. Ten items that were Medium moved to Low (L1 to L10), mostly because the trigger is narrow, the behavior may be intended, or the impact is small.
- Tightened triggers and impact for the manual-silence reboot (H1), the nap silence (H2), the service check (M1) and the timeline crash (M2), and corrected the drag-handle example (L6).
- Added L26 and L27 from the data pass. Corrected the Adhkar scheduler and repository coverage: the preferences pass read both in full.
