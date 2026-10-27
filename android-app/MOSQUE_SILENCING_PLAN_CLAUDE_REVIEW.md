# Review of the mosque-aware silencing plan

**Plan reviewed:** [MOSQUE_SILENCING_PLAN_2026-10-26.md](MOSQUE_SILENCING_PLAN_2026-10-26.md) (448 lines, revised 2026-10-26).
**Reviewed:** 2026-10-27 by Claude. This file holds remarks only: no code, data, settings, repository or device was changed.

**How the plan was checked**

- **Code:** every claim about current code was read against the `codex` working tree, uncommitted changes included.
- **Geometry:** recomputed from the shipped asset with shapely 2.1, using local metres and `maximum_inscribed_circle`.
- **Android behaviour:** checked against developer.android.com and the AOSP 14, 15 and 16 sources.
- **Play policy:** the background-location page was fetched again on 2026-10-27.
- **Competitors:** a scan of popular apps and phone features (sources and gaps in §4).
- **Arabic copy:** checked with the arabic-native skill and the owner's wording rules.
- **Owner rules:** checked against the owner's standing rules in the project notes.

Nine independent reviewers produced 116 findings. I verified the decisive ones myself and merged the rest. What could not be verified is listed in §7. Line numbers refer to the plan.

---

## Summary

The plan is careful about what must never happen, and most of its statements about the current code are correct. As written, though, it cannot ship. Four problems block it:

1. **B1: silence ends in the middle of a prayer.** A visit is renewed only by fixes whose whole accuracy circle fits inside the area. With typical indoor accuracy that almost never happens, and the 120-second budget then ends the silence. On the 60 eligible areas, a 20 m fix passes on only 9 % of an area's surface (median). At 25 m, 22 of the 60 areas can never pass.
2. **B2: the bundled catalog would shrink to about 3 areas, and the full research file is already public.**
   - 57 of the 60 eligible areas were computed from screenshots of the Google Maps app, so the plan's own rights gate leaves about 3.
   - The complete 3 MB research asset is packaged in every build today.
   - It is also on the public GitHub repository (`origin/codex`).
3. **B3: Google Play accepts only one background-location feature per app.** The travel place update already needs that slot.
4. **B4: the DND design misses platform facts.**
   - A ringer write switches global DND on or off.
   - An `AutomaticZenRule` cannot be created without a configuration activity or condition provider.
   - Suspending and resuming a rule undoes the user's snooze.
   - The current code already has three DND defects that follow from these facts.

The plan also has these major problems:

- MOSQUE_ONLY has no limit in time or place, and it can mute a Fajr or suhoor alarm.
- Entry comes minutes after arrival.
- Several states have no exit.
- The integration table misses entry points and two concrete failure paths.
- The TIME_ONLY parity contract misdescribes today's wake behaviour.
- The plan conflicts with several of the owner's standing rules.
- About a quarter of the plan describes later phases.
- The Arabic copy needs rewriting.

**Recommendation:** rewrite it as a lean v1 plan (§5 of this review).

- The core of v1 is areas the user saves on the phone. There is no bundled catalog.
- Visit holding is asymmetric: strict to start, hard to lose.
- Stage 0 measures three unknowns only:
  - how often real indoor fixes pass the gate in real mosques;
  - whether a location service can start from a geofence or prayer alarm on the owner's phones;
  - how an app-owned DND rule behaves on Android 15/16 and on Android 14 or older.

Only the owner can make three decisions: the single Play feature (B3), what to do about the public copy of the asset (B2), and how the "no bundled mosques" rule applies to silencing areas (M6). This review decides everything else and gives the reasons.

### What the plan gets right

These parts should stay:

- **No silent fallback:** no automatic fall back to time-only (lines 81, 88, 443).
- **Polygons decide:** the polygon decides presence; a geofence circle only wakes the app.
- **Three separate states:** planned windows, the app's requested silence and the phone's actual DND are kept apart (line 195).
- **System snoozes respected:** the plan refuses to add a second `setInterruptionFilter` owner (lines 163, 169).
- **Privacy:**
  - checks run on the device;
  - no visit telemetry;
  - private areas are excluded from backup and device transfer (lines 221-225).
- **Honest timing:** no promise of exact release times (line 113).
- **Accurate facts:** most code facts in §1 are accurate. Confirmed:
  - `scheduleAllInternal` at line 184;
  - the 45-minute bridge and its official-date exclusion;
  - the fix gate: mock rejected, age at most 30 s, accuracy at most 30 m, accuracy + 2 m clearance;
  - minSdk 26, targetSdk 36 and play-services-location 21.3.0;
  - the 6-hour repair worker with battery-not-low;
  - backup rules that exclude only asset packs;
  - the `phone_silenced` event;
  - the record counts: 2,481 / 1,116 / 60, split 54 / 4 / 2.

---

## 1. Blocking remarks

### B1. A visit is lost after about two minutes of indoor fixes (§3, lines 69-70, 96-107, 125-126)

**What the plan says.**

- A fix counts as "inside" only if its whole accuracy circle plus 2 m fits inside one area (`MosqueRepository.kt:58`, `MosqueGeometry.kt:24-55`).
- A visit is renewed only by fresh confident-inside fixes.
- An uncertain fix holds a visit for 120 s, and "repeated poor fixes do not extend the budget" (line 107).
- When the budget runs out, the source is released (line 70) and the location service stops (line 126).

**The geometry.** Because the whole circle must fit, the accuracy that matters depends on each area's size, not on the 30 m ceiling. These figures come from the 60 areas eligible for automatic silencing:

| Reported accuracy | Areas where no fix can ever pass | Share of the area where a fix still passes (median over the 60) |
|---|---|---|
| 5 m | 0 / 60 | 61 % |
| 10 m | 0 / 60 | 39 % |
| 15 m | 2 / 60 | 21 % |
| 20 m | 4 / 60 | 9 % |
| 25 m | 22 / 60 | 2 % |
| 30 m | 36 / 60 | 0 % |

The largest circle that fits inside an area has a radius of 14.7 m in the smallest area, 30.3 m at the median and 68.6 m at most. Areas run from 930 to 20,185 m², with a median of about 4,100 m².

**Why indoor fixes will mostly fail.**

- Android defines reported accuracy as a 68 % radius.
- The geofencing guide recommends a radius of 100 m or more because of "the location accuracy of typical Wi-Fi networks".
- Doze stops Wi-Fi scans when a phone lies still with its screen off, which is how a phone sits during prayer.
- Indoors, under a concrete roof, fixes of 15-40 m are common, and none of the 60 areas can pass a 30 m fix anywhere. (This is not measured in Tunisian mosques yet; it is stage 0's first measurement.)

**Consequence.** During a Friday sermon, Tarawih or a lesson, most fixes will be "uncertain". After about 120 s the visit ends and the phone rings in the middle of the prayer. In practice this contradicts line 98 ("failure of the inside test alone must not count as an exit"): the sound comes back just as it would after a real exit. The plan says only that "small indoor mosques will sometimes not qualify" (line 105). It defers "long-visit uncertainty" to stage 0 (line 412) without giving another rule to test.

**Recommendation.** Make holding asymmetric. This is a design decision, not a number to tune later.

- **Entry stays strict:** keep the current gate and two distinct fixes.
- **Once a visit is confirmed, hold it while nothing shows the person left.** A poor fix is weak evidence of being indoors. Outdoors, fixes are good, so a real exit is quick to confirm.
- **Release on any of these:**
  - two confident-outside fixes, where the circle lies wholly outside every supporting area plus a margin;
  - an EXIT from the enclosing geofence;
  - detected movement followed by no confident-inside fix within a few minutes. Movement can come from an Activity Recognition transition (runtime `ACTIVITY_RECOGNITION` on Android 10+) or the significant-motion sensor (no permission).
- **Separate two cases:**
  - "Fixes arrive but are uncertain": hold the visit.
  - "No fixes at all" (the service died): the watchdog releases after a longer bound.
- **Bound every visit:** the window end in TIME_AND_MOSQUE, and a cap with a fresh confirmation in MOSQUE_ONLY (M1).
- **Measure entry first:** in stage 0, measure on the owner's phone how many indoor fixes pass the gate at a few real mosques. Decide any relaxation of entry from that number, for example "circle mostly inside over N fixes", and not before.
- **Show the numbers:** put the inscribed-radius distribution in the plan. It explains the gate better than "30 m".

### B2. The catalog that v1 relies on cannot ship, yet it has already been published (§1 line 38, §5 line 209, §8)

**Where the geometry comes from.** Of the 60 eligible areas, 57 have geometry computed from screen pixels of Google Maps satellite views. Their derivation fields read like `four_corner_visual_covering_rectangle_from_fixed_camera_GPS_screen_affine`, and the method is in `scripts/mosques/draw_fixed_rectangle.py` and `capture_fixed_view.py`.

- 59 of the 60 carry the attribution text "reviewed on Google Maps satellite imagery".
- Only 3 are independent of that imagery:
  - Errahma: an unchanged OSM way, 1118593012;
  - Sousse and Bourguiba: rectangles computed from OSM outlines plus 4 m (relations 1385664 and 17951421).
- The 57 include all four user-approved approximate areas and one of the two full-premises records.

So the "release-qualified subset" (line 209) would hold about 3 areas nationwide. The plan treats this as an open question by provenance class (lines 291-299), but the data in the asset already answer it.

**The wrong agreement is quoted first.**

- The rectangles were made with the consumer Google Maps Android app on an emulator, opened by a `geo:` intent and captured with `adb screencap` (`capture_fixed_view.py:42, 223-224`). They were not made with the Maps Platform.
- The consumer Additional Terms therefore apply. They forbid using Google Maps "to create or augment any other mapping-related dataset" and copying the content.
- The plan mentions these terms only in passing (line 289). The Platform clause §3.2.3(c) matters only for a future editor built on the SDK.

**The licence label is inaccurate.**

- The asset is labelled ODbL, and `LICENSE.txt` presents it as a database derived from OSM.
- ODbL §4.4(d) forbids adding incompatible contents to a derivative database.
- Each eligible feature joins OSM identity to geometry derived from Google imagery, so this is more than a matter of "deserving review" (line 261).

**It is already distributed.**

- The 3,065,312-byte asset holds 2,481 records, the 57 rectangles and about 575 KB of embedded review records. Those records include local paths, Google Maps search URLs and the developer's proxy address.
- It sits in `app/src/main/assets` with no exclusion, so every build packages it. No runtime code reads it yet.
- It has been committed since f9ef43cc1 (2026-10-11). That commit is on `origin/codex`, and `github.com/bsafwen/TunisianPrayerTimes` is public (checked with `gh` on 2026-10-27).
- The plan says publication needs its own authorization (line 447). The publication has already happened.

**The pipeline cannot produce a subset.**

- `build_dataset.py` always writes the full catalog to the app asset path.
- `validate_dataset.py` asserts that the asset holds the complete catalog, and the README calls the asset "the canonical generated catalog".

**The reviewers were agents.** The "analyst" and "independent" reviewers of the 54 areas were agent roles, not human field or GIS reviewers. A rights or quality reviewer needs to know that.

**Recommendation.**

- **Decide now: v1 ships no bundled catalog.** With about 3 areas that can be cleared, a catalog adds almost nothing. It would still pull the whole §8 rights gate into v1, and the owner's no-bundling rule too (M6). Areas the user saves become the core feature; this also makes the feature work abroad.
  - A catalog can return once areas are derived again from permitted sources: OSM edits from field survey, CDLA outlines, contributors' own surveys.
  - The collection's work on which mosques exist and where they are still helps that.
- **Move the research catalog out of the app module** before any other build is shared, whatever happens to this feature. Leave a small test fixture for `MosqueRepositoryTest`.
- **The public copy is the owner's call.** That includes the branch and its history. Rewriting public history is outward-facing and cannot be fully undone for existing clones, so I did nothing.
- **Archive the evidence first.** The scripts, `work/` and `data/` are all git-ignored, so the lineage evidence exists only on this machine. Store it durably and privately before any rights assessment.

### B3. Google Play accepts one background-location feature (§3 line 145, §10 disclosure)

**The policy** (page fetched again on 2026-10-27):

- "A developer may only declare one location-based feature that requires access to a location in the background, not multiple."
- "The inclusion of multiple features will result in an app's rejection."
- The disclosure must "List all of the app features that use location in the background".

**The conflict.** The travel place update (uncommitted work from 2026-10-25) already needs that one declaration; `docs/play-listing-sources.md` says "one feature". The plan adds a separate mosque disclosure (line 32 and lines 398-402) and never mentions the rule.

**Facts for the decision.**

- The travel update already works without background location: it sends an immediate notification on a time-zone change. This was verified on the Pixel 6 with the app killed on 2026-10-25.
- Mosque detection has no such path, because a geofence needs background location while the app is closed.
- So if the mosque modes ship, the logical choice is to declare mosque silencing and let travel use its no-permission path. Otherwise the location modes wait.
- Whether a second, undeclared use may stay listed in the disclosure should be confirmed with Play policy support.

**Either way:**

- write one disclosure text that lists every background use (§2, M8);
- add the Play Console foreground-service "location" declaration and video (required for apps targeting Android 14+) to the release prerequisites.

This is an outward-facing choice for the owner. Settle it before stage 0, because it decides whether background mosque detection can ship at all.

### B4. The DND ownership design misses platform facts (§4 lines 153-163, matrix lines 178-185)

These come from the AOSP sources (android14/15/16-release) and the `NotificationManager` reference. They have not been observed on devices yet.

1. **A ringer write is a global DND write.**
   - `setRingerMode(SILENT)` while DND is off turns on global Priority DND with system origin, which gets round the Android 15 restriction.
   - `setRingerMode(NORMAL or VIBRATE)` while any DND is on turns off manual DND and snoozes every active automatic rule. That includes the user's Bedtime and schedule modes and other apps' rules.
   - The plan treats the ringer as separate state (line 161).
   - **Fix:** write no ringer once an owned NONE rule exists. NONE already mutes the ring and notification streams.
2. **`addAutomaticZenRule` throws without an owner `ConditionProviderService` or an enabled configuration activity** (`ACTION_AUTOMATIC_ZEN_RULE`, API 29+). The manifest declares neither, so the rule cannot be created at all.
   - **Fix:** add an exported settings activity for the rule and list it in the integration table.
3. **Suspending and resuming undoes the user's snooze.**
   - `STATE_FALSE` clears a user override, and a later `STATE_TRUE` activates the rule.
   - The matrix's MOSQUE_ONLY "suspend for the alarm, then resume" (line 184) would therefore silence again a phone the user had snoozed.
   - If the user turned the rule on by hand, the app's FALSE is ignored, and the eligible alarm stays muted, because NONE mutes `USAGE_ALARM`.
   - **Fix:**
     - read the rule state before suspending (API 35);
     - save a user snooze as "stop until I leave";
     - build the rule with `setManualInvocationAllowed(false)`;
     - check that DND is really off before ringing, and use vibration or a visual alert if it is not.
4. **One rule or several needs deciding.**
   - With one shared rule, a snooze carries over from one owner to the next (manual, then prayer, then mosque).
   - One rule per owner class lets the platform's most-restrictive merge deliver "overlapping requests never restore" (line 170), and each rule can be snoozed alone.
   - Suggested types:
     - prayer windows: `TYPE_SCHEDULE_TIME`;
     - mosque: `TYPE_THEATER`, the documented type for silence requested at a location;
     - manual and nap: `TYPE_OTHER`.
   - Each rule gets an Arabic name, an Arabic trigger description and a tintable icon (API 35).
5. **What the app can observe depends on the API level.**
   - API 35+ can read the rule state and receives ACTIVATED/DEACTIVATED broadcasts. API 29-34 can do neither.
   - Below 35, `updateAutomaticZenRule` deactivates an active rule.
   - On 15+ the user can edit the rule's policy, so it may no longer be NONE. Status, Qur'an notices and wake decisions must read the actual filter.
6. **API 26-28.** `ConditionProviderService` is deprecated and is not bound on low-RAM devices running Android 10 or lower. `setAutomaticZenRuleState` starts at API 29.
   - **Decide:** offer the location modes on API 29+ only; on 26-28, TIME_ONLY keeps its current path.
7. **The legacy handoff (line 159) differs by API level.**
   - On API 26-34 there is no implicit rule: legacy writes go to the global manual rule, and `setInterruptionFilter(ALL)` snoozes every active rule, the new one included. "Activate the new rule, then retire the old one" fails there.
   - On 15+ the handoff is simple: there is one implicit rule per package, and `setInterruptionFilter(ALL)` deactivates only that rule.
   - Because of the dev phase (M6), no handoff is needed at all.

### Defects in the current code found while checking (independent of this feature)

These were found by reading the current code against AOSP. Each needs a device check (Pixel 6, Android 15/16).

- **The restore can snooze the user's other modes.** `restoreState` writes the ringer before the filter (`SilenceModeController.kt:48-53`, `NapSilenceController.kt:66-71`). On Android 15+, every restore at a prayer end, a manual end or `forceNormalMode` may snooze those modes, Bedtime among them.
- **The phone can stay in the app's own DND mode.**
  - The saved "previous" filter (`SilenceModeController.kt:84-88`) is restored with `setInterruptionFilter(previous)`.
  - On 15+, any value other than ALL activates the app's implicit rule instead of restoring anything. PRIORITY, saved because Bedtime was on when the window began, is one such value.
  - The phone can then stay in the app's DND mode after the window, with the app's flags already cleared.
- **The user's stop in Modes does not last.** Every replan inside a window calls `enableAutoSilence`, which calls `setInterruptionFilter(NONE)` (`SilenceScheduler.kt:208-216`). On 15+ that clears the user's snooze of the app's mode, so a user who stops it from the system Modes screen is silenced again on the next resume, replan or repair run.
- **A restore can bring back live silence state.** Every SharedPreferences file is backed up and device-transferred; only asset packs are excluded. That includes `auto_silence_active` and the saved ringer/filter, so a restore onto a new phone brings the silence state back with it.
- **The research asset is packaged and public** (B2).

---

## 2. Major remarks

### M1. MOSQUE_ONLY has no limit in time or place, and it can mute the morning alarm (lines 79-80, 124-126, 184)

**Problems.**

- **No maximum visit length.** The "observation budget" is undefined (line 126), next to "never run an all-day high-accuracy loop" in the same line.
- **Areas reach past the mosque.** The approximate areas include sidewalks, courts and "disclosed surrounding space", and 58 of the 60 are approximate. Neighbours, shopkeepers and caretakers inside a rectangle would be silenced for hours.
- **The morning alarm can be muted.** The matrix suppresses ordinary wake and awake-check alarms during a MOSQUE_ONLY visit, and NONE mutes alarms. Someone sleeping in the mosque during the last ten nights of Ramadan, or living inside an area, would miss the suhoor or Fajr alarm.
- **Silencing could happen anywhere the catalog reaches.** Under the plan it covers every catalog area at any hour. Every comparable product silences only places the user chose (§4): PrayZone's saved favourites, Samsung Place routines, Pixel Rules, iOS Focus.

**Recommendation.**

- Limit both location modes to the user's own saved areas (and, if a catalog ever ships, to areas the user picked from it). This also caps the geofence count.
- Set a hard cap per visit. When it runs out, require a fresh confident-inside fix or release the silence with a quiet notice.
- Let ordinary wake alarms ring during a MOSQUE_ONLY visit outside prayer windows, and always at night. Write the rule down and test it.

### M2. Entry comes minutes late, and the execution path is settled by the docs (lines 117-133, 139, 143)

- **Entry is too late for the common case.**
  - Official geofence latency is usually under 2 minutes, about 2-3 minutes under background limits, and up to 6 minutes after the phone has been still. Starting the service and taking two fixes 10-30 s apart adds to that.
  - People often arrive just before the iqama, so the phone can ring as the prayer starts.
  - Say this in plain UI wording next to a one-tap manual silence.
- **A location foreground service is required, not optional** (line 122 says "when that is the selected execution path").
  - A background app gets new locations "only a few times each hour".
  - `getCurrentLocation` "fails to return a location more often when invoked from the background", and Google points to a location foreground service as the workaround.
  - `CurrentLocationRequest` defaults to BALANCED priority (about 100 m) and a 10 s `maxUpdateAge`, so the defaults fail the gate. Use high accuracy with `maxUpdateAge` 0, and drop repeated fixes by `elapsedRealtimeNanos`.
  - A location service cannot be created from the background without `ACCESS_BACKGROUND_LOCATION`, and on Android 14+ that fails with an exception at creation. Catch it and show "needs setup".
- **TIME_AND_MOSQUE already has its trigger.** The window-start alarm uses `setAlarmClock` (`SilenceScheduler.kt:294`), and Android documents exact alarms as "not affected by foreground service launch restrictions".
  - The app also already runs a location check 45 minutes before the adhan (`ACTION_DELEGATION_CHECK`, `SilenceScheduler.kt:297-321`). The §1 table does not list it.
  - Line 129 is more cautious than the docs; still check this on the OnePlus.
- **The geofence settings need pinning.**
  - Use `INITIAL_TRIGGER_ENTER`; without it, someone already inside never gets ENTER.
  - Use EXIT as a cheap release backstop.
  - Use DWELL with a loitering delay of about 60 s for MOSQUE_ONLY, to skip pass-bys.
  - Set an explicit responsiveness value.
  - Register again after a reboot, an app start or update, an app data clear, a Play services data clear, and `GEOFENCE_NOT_AVAILABLE` (network location turned off). The plan names only reboot.
- **No working set is needed in v1.** With only saved areas, register all of them (the limit is 100) and drop the relocation machinery. That removes the "miss a visit during relocation" failure (line 131).
- **The health panel needs more items.** Add Wi-Fi or Wi-Fi scanning, Google Location Accuracy (network location) and mobile data. The geofencing guide warns that alerts "might never" arrive with Wi-Fi off.
- **Name the watchdog's alarm API** (line 113).
  - In Doze, `setExactAndAllowWhileIdle` fires at most once per nine minutes, and on the owner's OnePlus it gets a window of up to 75 % of its delay.
  - `setAlarmClock` is exact.
  - Use `setAlarmClock` for every backstop deadline: the visit cap, the manual-silence end and the wake exception. Today the manual end still uses `setExactAndAllowWhileIdle` (`ManualSilenceScheduler.kt:35`).
- **Devices without Play services.** Try `LocationManager.addProximityAlert` in stage 0 before calling them unsupported (line 127).

### M3. Several states have no defined transition (§2-§4)

- **No state table.** Add one: IDLE → ACQUIRING → VISIT (holding) → EXITING → IDLE, plus SUPPRESSED and EXCEPTION. Only ACQUIRING is named today.
- **A failed ACQUIRING is never retried** (line 122). The geofence ENTER has already fired, and a person sitting still produces no new transition, so one failed start indoors loses the whole visit.
  - Define a few bounded retries, for example on DWELL.
  - End in a "missed" state that shows a quiet manual-silence prompt.
- **"Stop for this visit" has no guaranteed end** (line 167). It lasts until a confident exit, yet line 126 stops the service when the visit ends, so the exit may never be seen.
  - End it at the first of: a confident exit, a geofence EXIT, N hours, or the end of the occurrence.
  - Define "meaningful exit".
- **Foreground-only mode is defined wrongly.** Line 189 releases presence when the "observation lifetime" ends, which is when the user locks the phone to pray. Android's docs say a running location service keeps access with the display off. Two options:
  - If B3 rules out background location, this is the only mosque mode left. Define it as "starts from the open app, then a location service follows the visit to the exit or the cap".
  - If background location ships, drop it and point users without the permission to manual silence.
- **A mid-window mode switch leaves an audible gap** (line 90). Switching from TIME_ONLY to a location mode mid-window forces a gap, because presence cannot be true yet. Either keep the TIME_ONLY request until the first acquisition resolves (bounded), or disclose the gap.
- **The tables miss rows** (lines 56-73):
  - DND access revoked;
  - master switch off;
  - mode inactive or needs setup, which turns T = true into Off (a deliberate change from time-only);
  - a window opening during an uncertain hold.
- **A permission downgrade is handled two ways.** Line 70 handles a precise-to-approximate downgrade by the uncertainty policy; line 108 handles it by immediate invalidation. Use immediate invalidation.
- **Adjacent areas.** Confidence is judged within one component, but exit against all of them (lines 82, 98). Someone at the shared edge of two touching private areas (a hall and a courtyard) is never confidently inside either and gets released. Either hold on the union, or have the editor merge touching areas.
- **The two modes treat an eligible alarm differently without a reason.** After the alarm, TIME_AND_MOSQUE dismisses the occurrence while MOSQUE_ONLY resumes (lines 183-184).
- **Numbers.**
  - Give a provisional value for every named parameter: the acquisition deadline, release margin, preparation lead, observation budget and watchdog period.
  - Give the invariants that link them:
    - budget ≥ 3 × check cadence;
    - acquisition deadline ≥ service start + entry dwell + one retry;
    - exit dwell < hold bound.
  - Lines 96 and 100 describe the same thresholds two different ways.

### M4. The integration table misses entry points and readers of the silence state (§1 lines 26-30)

**Entry points that start, end or pause app silence but are not listed:**

- `WakeAlarmScheduler`, the only caller of `NapSilenceController`, which also pauses and resumes it;
- MainScreen's restore and manual-silence buttons;
- `SilenceScheduler.cancelAll`, called from `syncSilence` when the master switch is off or a permission is missing;
- `onOfficialDatesChanged`, called from the Application class.

**Two concrete failures.** Some code reads the auto and manual flags directly, so it would not see a separate mosque request:

- `NapSilenceController.enableNapSilence` saves the current ringer and filter as its baseline when neither flag is set (`:20-30`). During a mosque silence that baseline is SILENT/NONE, so after the alarm the phone stays silent even after the user has left.
- `SilenceReceiver`'s UNSILENCE calls `forceNormalMode` when the phone is silenced and the auto flag is clear (`:96-102`). A prayer-end alarm can therefore end a silence that a mosque request owns.

**Other readers to list:**

| Reader | What it does |
|---|---|
| `PhoneStateReceiver` | missed-call flag |
| `WakeAlarmScheduler.shouldUseWakeSilence` | wake silence decision |
| `SilenceGuardService.shouldRun` | guard run condition |
| `WakeAutoSilenceConflictController` | wake conflicts |
| `QuranSilence` | NONE mutes `USAGE_MEDIA`, so recitation stops during every visit and needs a mosque-specific notice |
| `DhikrReminderScheduler` | holds nudges until a window end, which a visit does not have; `ringerSilent` reads `RINGER_MODE_SILENT`, which changes if ringer writes go |
| `AlarmAudioController` | forces the alarm stream volume |
| `SilenceStatus.appSilenceEndsAt` | returns a window end |
| `PrayerSilenceSection`, `PrayerSilenceEditorSheet`, `SharedSilenceSettingsSheet` | where the per-prayer settings actually live (not `PrayerTabSettingsSheet`) |

**Other gaps in the table:**

- **Serialization.** The "existing scheduling lane" (`ScheduleRefreshCoordinator.kt:25`) serializes only screen code and `syncAll`. Receivers, workers and wake paths call `SilenceScheduler` and `SilenceModeController` directly, guarded only by `@Synchronized`. The coordinator needs one lock that every entry point goes through.
- **Readiness checks are duplicated.** They live in the coordinator, `onOfficialDatesChanged`, `scheduleExactAlarm` and the audio setters. Split them all together.
- **`phone_silenced` fires inside `enableAutoSilence`.** Sending mosque activation through it would log mosque entries as `auto_prayer`. Say the coordinator must not do that.
- **Backup exclusions** must also cover the new request store, suppression and recovery state.
- **The mosque code is not wired yet.**
  - `MosqueRepository` has no production caller.
  - It parses the whole 3 MB file, and one bad inventory ring makes all 60 areas unavailable.
  - Any future asset should be compact: the 60 records need about 26 KB. Isolate per-record failures.
- **Uncommitted base.** The background-location pieces the table describes are uncommitted: the manifest permission, the `DelegationLocator` helpers and the untracked `BackgroundLocationDisclosure.kt`. Land them first.

### M5. The TIME_ONLY parity contract misdescribes today's wake behaviour (lines 54, 176, 182, 427)

- **The bridge does supply a prayer to wake alarms.** Line 176 says a FROM_NOW lookup with no current prayer "does not invent one during a bridge-only interval". It does:
  - `currentSilenceWindowPrayer()` returns the next window's prayer whenever auto-silence is on and that window starts within 45 minutes (`SilenceScheduler.kt:116-125`).
  - `WakeAutoSilenceConflictController` turns that into an override.
  - `dismissCurrentWindows` then dismisses the next occurrence through its "bridging case" fallback (`:157-170`).
  - Building what the plan describes would break TIME_ONLY, and the test at line 427 encodes the wrong expectation.
- **Awake-checks need only the flag.** The eligibility flag alone is the authority today: `AwakeCheckSilencePolicy` lifts any active auto-silence when `autoSilenceOverrideAllowed` is true (`WakeAlarmScheduler.kt:665`, `AwakeCheckSilencePolicy.kt:67-85`). No conflict prayer is needed.
- **Row 182 says suppression follows "effective" silence.** It actually follows the planned window, whether or not the occurrence was dismissed or DND is on (`AwakeCheckSilencePolicy.kt:88-95`).
- **The bridge is broader than "locality replanning".**
  - Every caller of `scheduleAll` gets it: the repair worker, the midnight reschedule, settings syncs and the manual scheduler. Only `onOfficialDatesChanged` turns it off.
  - `currentSilenceWindowPrayer` holds a second copy of the bridge with no official-date exclusion.
- **A place change can drop a dismissal.** Place-change pruning (`PlaceResolution.kt`, 45 minutes) can remove "Skip this prayer period" during a TIME_AND_MOSQUE visit.

**Recommendation:** describe the actual rules, keep them unchanged for TIME_ONLY, and decide separately what the new modes do.

### M6. Conflicts with the owner's standing rules

- **Dev phase, no migrations.** The app has no real users yet, and the rule is not to build migrations or legacy handling.
  - Drop:
    - the legacy-handoff release gate (line 159);
    - the handling of malformed, future and downgrade values (line 86);
    - the migration-interruption tests (lines 413, 427).
  - A missing trigger setting simply means TIME_ONLY.
  - One line is enough: test phones that ran an older build may need the old «أوقات الصلاة» DND mode ended once in Settings.
  - Condition: the coordinator ships before the first public release.
- **"No mosques bundled in the app, ever" (2026-10-12).**
  - The rule was given about prayer times. Its scale reason still applies, and its "how to apply" note forbids features that "ship mosque lists".
  - Neither the plan nor its debate mentions it.
  - B2's recommendation (no catalog in v1) avoids the question for now. Any later catalog needs the owner's explicit reading of the rule.
- **Tunisians who travel.** TIME_ONLY works abroad and the catalog is Tunisia-only, yet the plan never mentions travel.
  - With saved areas as the core, the location modes work everywhere.
  - When the travel flow detects a new country while a location mode is on, say that no mosques are saved there. Offer TIME_ONLY, but never switch to it automatically.
- **Research and decide.** The plan defers choices that the evidence settles:
  - the execution path (M2);
  - the watchdog alarm API (M2);
  - the API 26-28 adapter (B4);
  - rule granularity (B4);
  - the in-visit hold rule (B1);
  - the basemap (M7).

  Leave to stage 0 only what really needs measuring.
- **No competitor study.** The owner's rule dates from 2026-10-26, before the plan was written. A first study is in §4 of this review.
- **Testing in proportion.** Stage 4's frozen statistical pilot does not fit a solo dev-phase app whose owner tests on their own phone. It asks for trial counts, denominators and tail latencies across an API/OEM matrix, and the plan itself forbids visit telemetry, so those numbers would have to come from hand logs. Proportionate testing:
  - unit tests for the evaluator, the geometry and the owner matrix;
  - a Pixel 6 A/B for the coordinator and DND, using a debug-only fix-injection hook (release builds keep the mock rejection);
  - a field checklist for the owner, backed by an on-device diagnostic log: enter, stay longer than the hold bound, lock the screen, leave, pass the door, use approximate permission.

  Device checks stay silent: use the vibrationOnly seed and verify DND and the ringer through `dumpsys`, not by hearing rings.
- **Small items.**
  - Per-mosque prayer schedules (line 445) are excluded by the owner's rule, not merely deferred.
  - Show editor coordinates in Western digits inside LTR isolates, since longitudes abroad can be negative.
  - Test states (no-match versus uncertain), not exact strings.

### M7. A quarter of the plan describes later phases (§5 line 213, §7, §9, stages 6-7, §10)

About 105 of the 448 lines describe later phases:

- Google's pricing for an option the plan does not prefer;
- contribution channels;
- moderation roles, audit and attachments;
- reviewer-hour costs;
- replay protection for downloadable catalogs;
- stages 6-7;
- submission and conflict strings.

Move these to a separate later "shared mosque areas" plan. The retrieval details (lines 5, 17) belong in the evidence folder.

**The basemap.** The plan picks MapLibre but no tile provider, and whether people can draw at all depends on that choice. The logical v1 needs no basemap. Instead, a "save the mosque I'm in" flow:

- the user stands inside the mosque and taps "save";
- the app collects fixes for about a minute and proposes a rectangle around them with a margin;
- the user adjusts its corners on a simple outline preview.

This needs no tile provider, raises no question about imagery rights, works offline, and matches how comparable products work (users pick places). Add a map later, if users cannot place areas well.

### M8. Arabic copy (§10)

Owner rules applied:

- the app speaks as «نحن» and addresses the reader as «أنت»;
- verbs rather than nouns;
- no hedging labels and no internal concepts;
- «هاتفك» throughout.

Note that «الموقع» already means the chosen city in this app.

| Purpose | Plan | Suggested |
|---|---|---|
| Master switch (existing, `strings.xml:42`) | الإسكات التلقائي أثناء الصلاة | الإسكات التلقائي (the old label is false in mosque-only mode) |
| Trigger question | متى يُسكت الهاتف؟ | متى نُسكت هاتفك؟ |
| Time only | حسب الوقت فقط | في أوقات الصلاة، أينما كنت |
| … explanation | يُسكت الهاتف خلال الفترات التي ضبطتها للصلاة، أينما كنت. | نُسكت هاتفك في فترات الإسكات التي ضبطتها لكل صلاة. |
| Time and mosque | حسب الوقت وداخل نطاق مسجد | في أوقات الصلاة، إذا كنت في مسجد حفظته |
| … explanation | يُسكت الهاتف خلال الفترات التي ضبطتها للصلاة، بشرط أن يشير موقعك… | نُسكت هاتفك في فترة إسكات الصلاة ما دمت داخل نطاق المسجد، ونعيد الصوت إن خرجت قبل نهايتها. |
| Mosque only | داخل نطاق مسجد فقط | كلما كنت في مسجد حفظته |
| … explanation | يُسكت الهاتف عند التحقق من وجودك داخل نطاق مسجد محفوظ، في أي وقت. | نُسكت هاتفك ما دمت داخل نطاق المسجد، في أي ساعة، ونعيد الصوت حين تخرج منه. |
| Prayer settings kept | إعدادات أوقات الإسكات محفوظة، ولا تُستخدم في هذا الوضع. | فترات الإسكات لا تُستعمل في هذا الوضع، ونحفظها لك كما هي. |
| Save the mosque I'm in (new) | (none) | button «حفظ نطاق هذا المسجد», helper «اضغط وأنت داخل المسجد، وسنرسم نطاقه حول موقعك.» |
| Local save | حفظ على هذا الجهاز | حفظ |
| Local-only explanation | هذا التعديل خاص بهذا الجهاز. لن يُرسل إلا إذا اخترت إرساله للمراجعة. | يبقى هذا النطاق على هاتفك، ولا يصل إلى أحد إلا إذا صدّرته بنفسك. (ship only with the backup exclusion) |
| Submission | إرسال للمراجعة | v1: «تصدير النطاق في ملف» (v1 sends nothing for review) |
| Approximate badge | نطاق تقريبي | drop it (the owner rejected «تقريبية») |
| Approximate explanation | قد يشمل هذا النطاق بعض المساحة المحيطة بالمسجد. | يمتدّ هذا النطاق قليلًا حول المسجد، فقد نُسكت هاتفك وأنت قريب منه. |
| Uncertain position | الموقع غير دقيق بما يكفي للتحقق من وجودك داخل النطاق. | لا نعرف موقعك بدقة كافية لنتأكد أنك داخل نطاق المسجد. |
| No match | لا يوجد نطاق مسجد محفوظ يطابق موقعك الحالي. | لم تحفظ نطاق مسجد هنا. + «حفظ نطاق هذا المسجد» |
| Pending check | جارٍ التحقق من الموقع… | جارٍ التحقق من موقعك… |
| Degraded background | قد يتأخر التحقق في الخلفية بسبب إعدادات الهاتف. | Name the cause, e.g. «قد يتأخر إسكات هاتفك في المسجد لأن توفير البطارية يقيّد التطبيق.» + «فتح الإعدادات» |
| Stale-location release | تعذّر تحديث الموقع، فأُوقف الإسكات المرتبط بالموقع. | أعدنا الصوت لأننا لم نعد نتأكد أنك في المسجد. |
| Stop action | إيقاف الإسكات لهذه الزيارة | إيقاف الإسكات حتى تغادر المسجد |
| Other DND remains | أُوقف الإسكات الخاص بالتطبيق، وقد يبقى الهاتف صامتًا بسبب إعداد آخر. | أوقفنا الإسكات، لكن هاتفك ما زال صامتًا من إعداداته. (only when DND is actually seen on) |
| Private area | نطاق خاص مفعّل على هذا الجهاز | نطاق رسمته أنت، يعمل على هاتفك فقط |
| Local disable | تعطيل هذا النطاق على جهازي | a switch: «الإسكات في هذا المسجد» |
| Complex shape | عرض فقط: تحرير هذا النطاق غير مدعوم حاليًا. | لا يمكن تعديل هذا النطاق هنا لأن شكله معقّد. |
| Self-intersection | تتقاطع حدود النطاق. عدّل النقاط قبل الحفظ. | تتقاطع خطوط النطاق. حرّك النقاط حتى لا تتقاطع. |
| Too large | النطاق أكبر من الحد المسموح. ارسم مساحة المسجد ومرافقه فقط. | هذا النطاق أوسع من المسموح. ضيّقه حول المسجد وساحته ومرافقه. |
| Permission action | فتح إعدادات الموقع | فتح إعدادات الموقع في الهاتف |
| Unsupported saved mode, submitted, conflict, published version | (several) | Not needed in v1 (dev phase; no catalog; no submission channel) |

**Strings the plan requires but §10 lacks:**

| Purpose | Suggested |
|---|---|
| Latency under both location modes | قد يتأخر الإسكات دقائق بعد وصولك أو خروجك، وقد لا ننجح في التحقق داخل المساجد الصغيرة. |
| Needs setup | لن نُسكت هاتفك في المسجد قبل أن تسمح بالموقع طوال الوقت. |
| Precise location | موقعك التقريبي لا يكفي لنعرف أنك في المسجد. فعّل الموقع الدقيق. |
| Status reason and notification | الإسكات مفعل في %1$s (fallback «الإسكات مفعل في المسجد»), action «إيقاف الإسكات» |

The new alarm-scope explanation (line 176) can be written once M5 is decided.

**The background disclosure** (lines 398-402) names only the mosque feature, but Play requires it to list every background use (B3). The plan's text also uses passive and nominal forms («تُجرى هذه المطابقة»), adds reassurance filler («يمكنك إيقاف هذه الميزة في أي وقت») and drops the existing line about which option to pick. Suggested single text, replacing `background_location_body` as well:

> يستعمل التطبيق بيانات موقعك حتى عندما يكون مغلقًا أو غير مستعمل، لأمرين تختار تفعيلهما: أن نُسكت هاتفك ما دمت داخل نطاق مسجد حفظته، وأن نغيّر مدينتك ومواقيت الصلاة حين تسافر. نقارن موقعك بنطاقات المساجد على هاتفك نفسه، ولا نرسله إلى أحد.
>
> في الصفحة التالية اختر «السماح طوال الوقت».

Buttons: «متابعة» and «لاحقًا» (the app's existing dismiss label, instead of «ليس الآن»). Add the mosque use to the store description and the privacy policy too.

**For a native Tunisian reader:** in Tunisia «الزيارة» often means visiting a saint's shrine. The suggested stop label avoids the word; please confirm.

---

## 3. Minor remarks

| Plan line | Remark |
|---|---|
| 25 | T needs `SilenceScheduler.planner` plus `isDismissed`, the Ramadan Isha override and `EidSilenceWindows`. `SilenceWindowPlanner` alone does not produce it. |
| 26 | Reword: both call `SilenceModeController` directly. Only `SilenceModeController` and `NapSilenceController` write the ringer or the filter. |
| 38 | The source snapshot is stale: `MainScreen.kt` and `AndroidManifest.xml` have changed since it was taken. |
| 98, 104 | "The same area/union" is ambiguous; define it together with the adjacency rule (M3). |
| 104 | Two fixes 10-30 s apart in the same multipath environment are correlated, so they are not independent confirmation. |
| 123-126 | TIME_AND_MOSQUE: keep the cheap coarse geofences between windows, and stop observing when a window ends with no other window close. |
| 129 | Exact alarms are a documented route to start the service (M2); the plan is more cautious than the docs. |
| 145 | Add the Play foreground-service-type declaration (location, with a video) to the release prerequisites. |
| 155 | Scope the sentence: the restriction applies on Android 15+ devices only. On 8-14, `setInterruptionFilter` still changes global state, and ringer writes remain a back door on 15+ (B4). |
| 193 | List every readiness check (M4) and split them together. |
| 221 | `phone_silenced` is fired from inside `enableAutoSilence`, and `NapSilenceController` logs it on every resume. |
| 412 | Stage 0 uses "private test areas" before the editor exists. Say how they get in: a debug-only test file. |
| Throughout | Add a glossary. "Qualified", "eligible", "release-qualified" and "cleared" blur the three gates that line 40 keeps apart. "Observation budget" and "permitted observation lifetime" may be the same thing. "Meaningful exit" and "support envelope" are undefined. |

---

## 4. Competitor scan (required by the owner's rule of 2026-10-26)

**Most-installed Islamic apps.** Install figures are from androidrank on 2026-10-09:

- Muslim Pro: 100M+;
- Athan (IslamicFinder): 10M+;
- Prayer Now: 10M+;
- Al-Moazin Lite: 10M+;
- Athan Pro: 1M+.

None advertises silencing by mosque location. Where they silence automatically, they do it by time; Prayer Now, for example, silences during prayer times. Keeping TIME_ONLY as the default without an upgrade prompt is right.

**Dedicated time silencer.** Auto Silence at Prayer's Time (Codematics, 1M+, 4.5) sets From/To times per prayer and turns the ringer back on automatically. TIME_ONLY already matches it.

**Location silencers are tiny or new, and none uses polygons.**

- **PrayZone** (v1.0.1, May 2026) silences on arrival at mosques the user saved as favourites.
- **Mobiiworld's iOS Muslim Prayer App** silences "near a mosque and when it is prayer time".
  - It turns sound back on after a fixed period, not on leaving.
  - It warns about GPS battery drain.

**Phone features.**

- **Samsung Modes and Routines, Place trigger:**
  - a circle; users report minimums of 100-150 m or more;
  - turns settings back when the user leaves;
  - users say routines often run only partly.
- **Pixel Rules:** triggered by an address or a saved Wi-Fi network; needs continuous location.
- **iOS Focus:**
  - starts on arriving at a location;
  - manual DND can run "until I leave this location";
  - users complain that leaving does not trigger.
- **Stock Android Modes:** no location trigger.

**Signs of demand and failure.**

- A MasjidBox feature request with 76 votes asks for silence "within a 100ft radius" of a masjid.
- The open-source Prayer mode app (F-Droid) shows DND on/off notices. Its issue tracker reports Tarawih and Tahajjud silencing failing without notice (Feb-Mar 2026).

**Complaint themes:**

- triggers missed or only partly run;
- arrival works but leaving does not;
- radius too coarse;
- background GPS drains the battery;
- too many permissions.

**Copy:**

- places the user chose;
- sound returns on leaving;
- quiet on/off notices;
- a manual "silence until I leave" with a hard cap;
- time-only as the default.

**Avoid:**

- silencing in any place the app knows;
- turning sound back on after a fixed timer;
- continuous GPS;
- circles as the deciding geometry.

**Gaps in the scan.**

- Play listing pages and user reviews could not be fetched.
- Several Samsung and appfollow details come only from search snippets.
- Whether Al-Moazin, Salaat First or Athan (IslamicFinder) silence at all was not confirmed.

Before stage 4, read the Play reviews of PrayZone and Auto Silence at Prayer's Time by hand.

---

## 5. Suggested lean v1

1. **TIME_ONLY stays as it is** and remains the default. There is no migration code.
2. **Both location modes use the mosques the user saves.**
   - The «حفظ نطاق هذا المسجد» flow, corner adjustment on a simple preview, and GeoJSON export as the only portability.
   - No catalog until areas are derived again from permitted sources and the owner confirms how the bundling rule applies.
   - This works abroad too.
3. **Presence:** strict entry, asymmetric holding (B1), a cap per visit (M1), and a suppression with a defined end (M3).
4. **Execution:**
   - geofences on every saved area, with `INITIAL_TRIGGER_ENTER` and EXIT, and DWELL for MOSQUE_ONLY;
   - a check at the window-start `setAlarmClock` for TIME_AND_MOSQUE;
   - a location foreground service only while acquiring and during a visit;
   - `setAlarmClock` for every backstop deadline.
5. **Audio:**
   - one `SilenceRequestCoordinator` behind one lock that every entry point uses;
   - one `AutomaticZenRule` per owner class on API 29+, with a configuration activity and no ringer writes;
   - on API 26-28, TIME_ONLY only, on the current path;
   - fix the three current DND defects (§1) along the way.
6. **Play:** decide the single declared feature first (B3) and write one disclosure.
7. **UI:**
   - the trigger question;
   - the list of saved areas;
   - a health panel that includes Wi-Fi, network location and mobile data;
   - «إيقاف الإسكات حتى تغادر المسجد»;
   - the latency wording and manual silence.
8. **Validation:** unit tests, a Pixel 6 A/B with a debug-only fix-injection hook, and the owner's field checklist with an on-device diagnostic log.

**Stage 0 then measures only three things:**

- the share of indoor fixes that pass the gate at a few real mosques, on the owner's phone;
- whether a location service starts from a geofence and from the window alarm on the OnePlus and the Pixel;
- how the app's own DND rules behave (snooze, ringer coupling) on Android 15/16 and on one device with Android 14 or older.

**Into a separate later plan:**

- the catalog and the new derivation of its areas;
- a map-based editor;
- contributions and a website;
- downloadable catalogs.

---

## 6. Decisions only the owner can make

1. **Which single background-location feature to declare on Play (B3).**
2. **The public copy of the research asset (B2).** It is on the public `codex` branch and in its history; decide whether to remove it.
3. **The "no bundled mosques" rule (M6).** Does it cover silencing areas? This only matters once a catalog comes back.

---

## 7. What was not verified

- **DND behaviour** comes from reading AOSP 14/15/16 and the docs. Nothing was observed on a device, and OEM builds (One UI, OxygenOS) may differ.
- **Indoor accuracy in Tunisian mosques was not measured.** B1 is arithmetic on the shipped areas plus Android's documented accuracy figures.
- **The rights remarks are not legal advice.** They cover the Google consumer and Platform terms and ODbL §4.4(d).
- **Competitor data** comes partly from search snippets, and Play reviews were not read.
- **Play's view of the use case is unknown.** No official example says whether Play accepts mosque silencing as a core background-location feature.
- **Distribution was checked only on GitHub.** Whether a published APK or AAB contains the asset was not checked; only the GitHub branch was.
- **The connotation of «زيارة»** needs a Tunisian reader.
