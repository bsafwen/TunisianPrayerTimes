# Dhikr reminder after long social-media or game sessions — Plan

Status: plan only, nothing is implemented. Written 2026-10-04 and revised 2026-10-06 after a review against the code (claims checked file by file, then attacked for detection errors, lifecycle races, privacy gaps and missing decisions). Target: `android-app/` on branch `codex`.

Working name in code: **usage nudge**. Draft name in the app: «تذكير أثناء التصفّح والألعاب».

**In short.** When the user has spent 30 minutes (adjustable) in the social-media apps and games they chose, the phone shows a banner with a short dhikr, and one tap opens it in the Adhkar reader.

- It is off by default. Switching it on shows an in-app explanation, then asks for the system's "Usage access".
- It reads the app in front from `UsageStatsManager`, using a cheap self-rearming alarm: no background service and no permanent notification.
- Nothing about the user's apps leaves the phone or goes into backups.
- It shows at most 3 reminders a day, rotates the dhikr, and offers «إيقاف اليوم».

---

## 1. Goal and principles

When the user has been in social-media apps or games for a long stretch, the phone shows a short dhikr they can say on the spot. One tap opens that dhikr in the Adhkar reader.

1. **A reminder, not a blocker.** Nothing is locked, delayed or covered. Every existing Islamic screen-time app found on Google Play is a blocker built on the Accessibility Service; this feature needs neither.
2. **Opt-in, with consent.** Nothing is read until the user has switched the feature on and accepted the in-app explanation. The system access alone is not enough: if someone grants Usage access from the phone's settings without going through the app, nothing happens.
3. **The dhikr is in the notification.** The user can fulfil the reminder by reading the banner, without leaving the app they are in.
4. **Everything stays on the phone.** No app names, durations or reminder times leave the device: not through analytics, logs or backup. No history is kept: the app remembers only the current session and today's count.
5. **Gentle by design.** At most a few reminders a day, varied text, no usage statistics, no wording that blames, and a one-tap «إيقاف اليوم».
6. **Thresholds are personal settings**, never presented as a religious prescription. `docs/adhkar-sources.md` already applies the same rule to targets and time windows.
7. **When in doubt, stay silent.** A reboot, a clock change, a lost alarm or missing data may delay or skip a reminder. They must never produce one that is not deserved.

## 2. What the user gets

| Step | Behaviour |
|---|---|
| Switch on | In the Adhkar tab, a new card «تذكير أثناء التصفّح والألعاب». Switching it on shows a short explanation of what is read and why, then opens the system "Usage access" page if it is not granted yet, then asks for notifications if they are off. |
| Apps | Ready to use: the usual social and short-video apps and all games are counted. The card shows a summary, and tapping it opens the settings, where the list can be edited. Messaging apps are listed but not counted by default. |
| Use the phone | After **30 minutes** (default) of continuous use of the counted apps, a banner appears with a short dhikr, for example «سُبْحَانَ اللَّهِ وَبِحَمْدِهِ، سُبْحَانَ اللَّهِ الْعَظِيمِ.» |
| Keep scrolling | The next reminder comes only after another full 30 minutes of continued use, with a different dhikr. There are never more than **3 a day** (default). |
| Tap the banner | The Adhkar tab opens on that dhikr in the reader. |
| «إيقاف اليوم» | A button on the notification silences the feature for the rest of the day, and for the session that is running at midnight. |
| Switch off | Everything stops. The app forgets the session, today's count and the consent, keeps only the chosen settings, and offers to withdraw Usage access in the phone's settings. |

"Continuous use" means time in the counted apps where breaks of up to 3 minutes (another app, or the screen off) do not start a new session. The break itself is not counted.

## 3. Decisions to confirm

These are product choices. Each has a recommendation, and the rest of the plan assumes it.

| # | Decision | Recommendation | Alternative |
|---|---|---|---|
| 1 | What "too much" means | Continuous session time in the counted apps | A daily total across the counted apps. It fires once, late in the day, and overlaps with the system's Digital Wellbeing timers. Kept as an optional extra in section 11 |
| 2 | Defaults and choices | 30 minutes and at most 3 reminders a day. Duration: a stepper from 15 to 120 minutes in steps of 5, with quick picks 15, 20, 30, 45, 60 and 90. Daily maximum: a stepper from 1 to 5 | — |
| 3 | Apps counted by default | A curated list of social and short-video apps, plus «جميع الألعاب» (every app the system classes as a game, including games installed later). Messaging apps (WhatsApp, Messenger, Telegram, Discord) are listed but off | Start with nothing selected |
| 4 | Choosing apps while switching on | No: the defaults apply, and the list is edited later from the card. This keeps switching on to at most two system steps | An app-picker step in the switch-on flow |
| 5 | Which dhikr | A rotation over five short catalog adhkar of praise and tahlil (section 5.3), editable by the user. Rotation slows the habituation seen with a repeated identical prompt. Istighfar and supplications for distress are left out of the default pool: after "30 minutes in TikTok" they read as blame | One fixed dhikr |
| 6 | Notification wording | Title in the app's own plain words (draft «استراحة ذكر»); body = the dhikr text. No verse or hadith in the title in v1 | A verified line such as «أَلَا بِذِكْرِ اللَّهِ تَطْمَئِنُّ الْقُلُوبُ» (الرعد 29 in the app's Qaloon numbering). It would need an entry in `docs/adhkar-sources.md` and the owner's approval |
| 7 | Vibration | One notification channel with a short vibration; the settings sheet links to the system channel page | A vibrating and a quiet channel chosen by an in-app switch, as scheduled reminders have |
| 8 | Analytics | Only the existing `permission_step_result` event, with `permission_type = "usage_access"` and `entry_point = "adhkar"`, fired from the switch-on flow. It reveals that a user enabled the feature, and nothing else about its use | No event at all |
| 9 | Listing installed apps | A `<queries>` element for launcher apps. The visible app picker and the game check use it | Curated `<package>` entries only: no picker for arbitrary apps and no game detection |
| 10 | Distribution | Same manifest for Play and GitHub builds (there are no product flavors today) | A flavor without the permission |
| 11 | What the reader asks after a tap | A new reading of that dhikr with its usual count, exactly as in the library (100 for `tahlil_ashr` and `subhanallah_bihamdih`, 1 for the others). The user can stop at any time | A reading with a goal of 1 |

## 4. Platform facts that shape the design

These were checked against Android and Google Play documentation and AOSP source on 2026-10-04. AOSP describes stock Android; manufacturers' builds can differ, which is what milestone M0 checks on a real phone.

**Detection**

- A third-party app can learn about other apps' usage only by polling `UsageStatsManager`, after the user enables "Usage access" in system settings. The push-style observers (`registerAppUsageObserver` and relatives) are system-only.
- `queryEvents()` is the method to use. `queryUsageStats()` returns whole-day buckets and cannot answer "the last 30 minutes".
- Event types by Android version:
  - 8.0–8.1 have only the foreground and background events (values 1 and 2).
  - 9 adds the screen and keyguard events.
  - 10 adds `ACTIVITY_STOPPED`, `DEVICE_SHUTDOWN` and `DEVICE_STARTUP`.
  - Values 1 and 2 keep their meaning on every version, so one code path serves all.
- Events are served from memory, so a query sees an app coming to the front within moments. Events of other apps are not filtered by package visibility.
- An event is stamped when it happens but stored after a short hop through a system thread. A query may therefore miss an event stamped a few milliseconds before the end of its range.
- An app that is still in front has no closing event. A long uninterrupted session produces one event and then nothing, so an empty result means "no change", not "nothing in front".
- The system also returns an empty result when the access was revoked, and (from Android 11) before the first unlock after boot. The reader must check both explicitly: "no data" must never be read as "zero usage".
- Usage is counted per package and per profile:
  - Facebook in a browser counts as the browser, and Shorts count as YouTube.
  - Apps in a work profile or private space are invisible.
  - Instant apps are reported under an obscured name.
  - An activity in picture-in-picture is paused, so a video playing in picture-in-picture does not count as being in front.
- Events are stamped with the wall clock. When the clock moves by more than about 2 seconds, the system also shifts the timestamps it has stored. A clock change therefore looks like usage, or replays usage, unless the app detects it.

**Background execution**

- WorkManager periodic work cannot run more often than every 15 minutes.
- Alarms of the non-wakeup types (`ELAPSED_REALTIME`, `RTC`) never wake a sleeping phone.
  - One that comes due while the screen is off is held by the system and released when the screen turns on. At the latest, it is released after a bounded delay if the processor happens to be awake.
  - A self-rearming non-wakeup alarm therefore costs nothing while the phone sleeps, and doubles as a "screen turned on" signal.
  - Its receiver can still run with the screen off, so it must check `PowerManager.isInteractive`.
- **Standby-bucket quotas are the binding limit.**
  - An app in the background gets 10, 2 or 1 alarm deliveries an hour (working set, frequent, rare).
  - The quota covers all of the app's alarms that are not alarm-clock or exact-while-idle alarms, including non-wakeup and plain exact alarms. In this app those are: the inexact backup copy that `DhikrReminderScheduler` sets next to every adhkar delivery, its inexact deliveries when exact alarms are refused, the inexact fallback of `AwakeCheckScheduler`, and the inexact manual-silence end alarm of `ManualSilenceScheduler`.
  - A frequent tick could use up that allowance and delay them.
- An app **exempt from battery optimisation** has no such quota, is exempt from the exact-alarm permission, and does not have its alarm windows stretched. This app requires that exemption in onboarding, so it is the normal case. The user can still withdraw it later, and no broadcast tells the app when that happens.
- Without the exemption, an inexact alarm set a few minutes ahead may be delivered up to 75% of that delay late.
- A permanent foreground service would be the most reliable watcher, and is what blocker apps use. It is rejected here:
  - it needs a permanent notification and a Play Console declaration with a demo video;
  - its policy test ("cannot be deferred") does not fit a reminder that may arrive a few minutes late;
  - `SilenceGuardService` documents that this app avoids all-day foreground services.
- `ELAPSED_REALTIME` alarms do not survive a reboot.

**Notification**

- A heads-up banner needs a channel created with `IMPORTANCE_HIGH`. The app sets the importance once, at creation; afterwards only the user can change it.
- A channel that does not exist yet reads as "blocked" in `DhikrReminderScheduler.notificationsEnabled`, so it must be created before any check.
- Stock Android shows the banner over full-screen apps. Even so, no banner appears:
  - under Do Not Disturb;
  - for a while after the user swipes one of the app's banners away;
  - when a manufacturer's game mode blocks banners (Samsung, OnePlus, Xiaomi, Pixel Game Dashboard).

  An app cannot detect or override these cases. The notification still lands in the shade.
- A full-screen intent is not available for this use (Play limits it to alarms and calls), and on an unlocked phone it would only produce a banner anyway.

**Policy**

- `PACKAGE_USAGE_STATS` has no Play Console declaration form.
- The data it gives, and the list of installed apps, are "personal and sensitive" under the User Data policy. That requires a privacy-policy entry and an **in-app prominent disclosure with affirmative consent, shown before the access is requested**, even when processing stays on the device. The disclosure must say what is accessed and how it is used.
- Data safety form: data that is only processed on the device is not "collected", so there is no new entry, provided nothing reaches analytics, crash reports or a server.
- `QUERY_ALL_PACKAGES` needs a declaration and does not fit this use. A `<queries>` launcher intent needs none, but must back user-facing functionality: here, the app picker.
- An `AccessibilityService` is ruled out: monitoring apps are named as not being accessibility tools.
- **GitHub builds on Android 15 and later.** An app installed from a downloaded APK has Usage access listed among the "restricted settings": its switch is greyed out until the user allows restricted settings for the app (section 5.7).
  - Play installs are not affected, and neither are Android 14 and earlier.
  - An app cannot detect this state or open the screen that lifts it.

## 5. Design

### 5.1 Detection: from usage events to a session

All of this is pure Kotlin, in `multiplatform/shared/src/commonMain/kotlin/com/tunisianprayertimes/adhkar/UsageNudgeComputer.kt`, next to the adhkar catalog. Phone-only pure logic already lives in the shared module (`WakeAlarmComputer`, `SilenceAlarmComputer`). Its tests run on the plain JVM, whereas the android-app unit-test task currently fails because of the bundled Quran assets (section 9). The functions take time, today's date and an `isCounted(packageName)` answer as parameters; they never call Android.

**Input.** `UsageSample(type, packageName, className, timeMillis)` is a copy of the four fields read from each `UsageEvents.Event`.

A thin reader in android-app, `UsageEventsReader.read(context, fromMillis, toMillis): List<UsageSample>?`, is the only code that touches `UsageStatsManager`.

- It returns `null` for "no data":
  - consent missing or stale (section 5.5);
  - access missing, checked through `AppOpsManager`, because the system answers a revoked access with an empty result;
  - user not unlocked since boot (`UserManager.isUserUnlocked`);
  - an exception.
- An empty list means "no new events".
- It keeps only the event types listed below.
- It queries the half-open range `[from, to)`.

**The app in front.** The fold tracks one *front*: the most recently resumed activity.

| Event | Effect |
|---|---|
| 1 (`ACTIVITY_RESUMED`) | This activity becomes the front, whatever was in front before. |
| 2 (`ACTIVITY_PAUSED`), 23 (`ACTIVITY_STOPPED`, Android 10+) | If it matches the front, nothing is in front from this time. Otherwise ignored. |
| 16, 17 (`SCREEN_NON_INTERACTIVE`, `KEYGUARD_SHOWN`), Android 9+ | Nothing is in front. |
| 26 (`DEVICE_SHUTDOWN`), 27 (`DEVICE_STARTUP`), Android 10+ | Nothing is in front. |
| any other type | Ignored. |

Why one front rather than a set of resumed activities:

- A missing pause event cannot leave an app "in front" forever: the next resume of anything replaces it.
- Moving between two screens of one app works whatever order the system logs the pause and the resume in, because the pause of the old screen no longer matches the front.
- In split screen, only the app resumed last is counted. This undercounts in a rare case and never invents usage.

A short gap between two screens of one app is a break of a second or so, absorbed by the session rule below.

The front is stored in one of three forms:

- a **counted** app, with its package and class;
- **other**, with no name stored;
- **nothing**.

The names of apps the user did not choose are never written anywhere. A change of the counted apps or of the duration resets the session (see "Resets"), which re-derives the front with the new counted set.

**Watched time** is time during which the front is a counted app. `isCounted` is false for this app's own two application ids (the release one and `.dev`).

**Settle margin.** Because an event can be stored a moment after it is stamped, the saved fold stops 10 seconds before "now".

1. The saved ledger's cursor is `now − 10 s`.
2. The events of the last 10 seconds are folded into a throwaway copy, used only to answer "who is in front now" and for the decision.
3. The next read starts at the saved cursor, so those events are read again and folded for good once they are older. Ranges never overlap, so nothing is counted twice.

**Session rule.** A watched span that starts more than 3 minutes after `lastWatchedEndMillis` starts a new session: `sessionStartMillis` moves and `watchedSinceNudgeMillis` returns to zero. A span that starts sooner extends the session. A screen-off period is a break like any other.

**The ledger.** A game can stay in front for two hours without a single event, so a fixed look-back window would miss its start. The monitor therefore keeps a small ledger, saved in its own file (section 5.5), and folds only the events since the last check:

```
UsageNudgeLedger(
  version,                  // format version; a mismatch triggers a reset
  cursorMillis,             // events before this wall-clock time are folded
  bootTimeAtCursor,         // currentTimeMillis − elapsedRealtime at the cursor
  front,                    // Counted(package, class) | Other | Nothing
  sessionStartMillis,       // current session, or 0
  lastWatchedEndMillis,     // when watched time last stopped, or 0
  watchedSinceNudgeMillis,  // watched time in this session since its start or the last reminder
  day, countToday,          // local date, and reminders posted on that date
  quietBeforeMillis,        // no reminder in a session that started before this time, or 0
  quietReason,              // why: Paused («إيقاف اليوم») or Maximum (today's maximum reached)
  retryAtMillis,            // a due reminder was put off until then (section 5.3), or 0
  lastDhikrId,              // for rotation
  lastPostedMillis,         // when the last reminder was posted; also read by the adhkar spacing rule
)
```

**Resets.** `currentTimeMillis − elapsedRealtime` stays constant except across a reboot or a clock change. A reset happens when:

- that value differs from `bootTimeAtCursor` by more than 2 seconds (a reboot, or the time or date was changed). Two seconds is also the threshold above which the system shifts the timestamps it has stored, so a smaller change shifts nothing;
- there is no ledger, its version is unknown, or the cursor is more than 12 hours old;
- the counted apps or the duration were changed.

A reset:

1. sets `sessionStartMillis`, `lastWatchedEndMillis`, `watchedSinceNudgeMillis` and `retryAtMillis` to zero;
2. keeps the **day fields**: `day`, `countToday`, `quietBeforeMillis`, `quietReason`, `lastDhikrId` and `lastPostedMillis`. Every case in this plan that keeps part of the ledger keeps exactly this set;
3. re-derives only the front, from the events since the later of 12 hours ago and the last boot: the last resume without a later close.

Usage before a reset is never counted. If a counted app is in front, a new session starts at the moment of the reset. A forward clock jump, timestamps shifted by the system, a history with a gap around a reboot, a phone with no `DEVICE_*` events (Android 8 and 9), or a first run therefore never produce a reminder (principle 7).

The ledger is deleted whenever reading stops for good (section 5.5), so the next read always starts with a reset.

**Decision.** The decision is `decide(ledger, settings, nowMillis, today, inCountedAppNow): UsageNudgeDecision`, where `inCountedAppNow` = the front is counted and the screen is interactive. The rules, in this order:

| Result | When |
|---|---|
| `DoneForToday(next)` | `now < quietBeforeMillis`, or `countToday` has reached the daily maximum. In the second case, `quietBeforeMillis` becomes the next local midnight and `quietReason` becomes Maximum. `next` = local midnight, but no more than 1 hour ahead, so a clock or time-zone change corrects itself. |
| `Idle` | `sessionStartMillis < quietBeforeMillis`: a session that started on a done day stays quiet past midnight, until it ends. |
| `Remind` | `inCountedAppNow`, `watchedSinceNudgeMillis >= threshold`, and `retryAtMillis` has passed. |
| `CheckAt(t)` | `inCountedAppNow` and still below the threshold or the retry time: `t` = the moment the threshold is crossed if they stay, or `retryAtMillis` if later. |
| `CheckAt(t)` | A break inside a session that is still open (`now − lastWatchedEndMillis ≤ 3 min`): `t` = `lastWatchedEndMillis + 3 min`. Coming back within the break then gets an on-time reminder instead of waiting for the next idle tick. |
| `Idle` | Otherwise. |

`t` is always at least 60 seconds ahead. `day` and `countToday` roll over at local midnight, in the phone's current time zone.

The quiet rule is a time, not a marked session, so it works whatever ticks ran or did not run. A session that starts at 23:20 after a pause at 22:00 is quiet even though no tick saw it before midnight.

**A reminder counts as posted** once `notify()` has been called, even if it later times out, is swiped away or hidden by a game mode. When it is posted:

- `watchedSinceNudgeMillis` returns to zero, so the next reminder needs another full threshold;
- `countToday` goes up by one;
- `lastDhikrId` and `lastPostedMillis` are updated, and `retryAtMillis` returns to zero.

These are committed **before** `notify()` is called. If the process dies in between, one reminder is lost; none is ever duplicated.

A reminder that is put off is not counted and does not reset the session. It is shown at its retry time only if `Remind` holds then: the user is in a counted app, in the same session.

**Rotation.** `nextDhikr(pool, lastDhikrId, exists)` picks the next entry of the user's pool that still exists, never the same one twice in a row. With a pool of one, it repeats that entry. If nothing in the pool exists any more, it uses the default pool.

**Android 8 safeguard, only if M0 needs it.** On Android 8 there are no screen events. If M0 item 8 shows that no pause is logged when the screen turns off with an app in front, the screen-off time would count as usage. In that case a tick delivered more than a minute after it was due (it was held while the phone slept) closes the front at its due time.

**Constants** live in one object, `UsageNudgeTuning`, in the same file, so that M0 and M6 can adjust them in one place:

| Constant | Value |
|---|---|
| Break that ends a session | 3 min |
| Settle margin | 10 s |
| Clock or reboot tolerance (`bootTime` drift) | 2 s |
| Look-back to re-derive the front | 12 h |
| Idle tick | 10 min, window 2 min |
| Hot tick, minimum lead; tick after a screen-off delivery | 60 s |
| Done-for-today tick | next local midnight, at most 1 h |
| Retry when the end of the app's silence is unknown | 15 min |
| Spacing after another adhkar notification | 2 min (the existing adhkar rule) |
| Notification timeout | 60 min |
| Safety job, normal mode / poll job, reduced mode | 6 h / 15 min |
| Threshold | 15–120 min in steps of 5; default 30. The minimum must stay above the idle tick. |
| Daily maximum | 1–5; default 3 |

### 5.2 Monitor: what triggers the check

`adhkar/UsageNudgeMonitor.kt` in android-app is an `object` in the style of `DhikrReminderScheduler`. It takes explicit `nowMillis` and `nowElapsed` parameters, so tests can control time.

**One lock.** `UsageNudgeMonitor.lock` covers `tick`, `sync`, the «إيقاف اليوم» action and every ledger read-modify-write from the UI. The receiver, `Application.onCreate`, the worker and the UI can all reach the monitor at the same moment in one process. Without the lock, they could post twice or undo a pause.

There is no service and no permanent notification. The check (`tick`) is the same in both modes below; only its trigger differs.

**Reading conditions.** The monitor reads usage only when all of these hold:

- the feature is active: enabled, with consent of the current version (section 5.5);
- Usage access is granted;
- notifications are allowed for the app and for this channel. The channel is created first, by `UsageNudgeMonitor.ensureChannel`.

Otherwise it arms nothing. The next `sync`, on any resume of the app, picks up a newly granted permission.

**Normal mode: the app is exempt from battery optimisation.** A self-rearming non-wakeup alarm drives the check:

- **Idle tick.** While no counted app is in front: `setWindow(ELAPSED_REALTIME, now + 10 min, 2 min)`. It never wakes a sleeping phone, and a tick that came due with the screen off is released when the screen turns on.
- **Hot tick.** At the time given by `CheckAt`: `setExact(ELAPSED_REALTIME, t)`. An exempt app may always set exact alarms. As a guard against unusual builds, if `canScheduleExactAlarms()` is false or `setExact` throws `SecurityException`, it falls back to `setWindow(ELAPSED_REALTIME, t, 60 s)`. This is new code: no existing scheduler in the app uses `setWindow` or a non-wakeup alarm.
- **Done tick.** After `DoneForToday`: one tick at the given time.
- All ticks use one `PendingIntent` (an explicit broadcast to a new non-exported `UsageNudgeReceiver`, with its own action and data URI, and request code 0), so arming a tick replaces the previous one.
- **Record.** Every armed tick is recorded with its elapsed time, kind and `bootTime`. The record also holds the current mode (normal or reduced), written by `sync` and by `tick`, so a change of mode can be detected.

**Reduced mode: the exemption is missing.** The monitor sets no alarms at all, so it can never use up the allowance that the alarms listed in section 4 depend on. A periodic job runs `tick` every 15 minutes instead.

- A reminder can then be up to about 15 minutes late, and later still if the system has put the app in a restrictive standby bucket.
- `CheckAt` and retry times are not acted on: the next run decides.
- The card says so and links to the existing battery-exemption request.

**Periodic job.** There is one unique periodic WorkManager job, `adhkar_usage_nudge`, using `ExistingPeriodicWorkPolicy.UPDATE` like `SilenceVerifyWorker`, which is re-enqueued on every resume:

- in normal mode, every 6 hours, calling `sync` as a safety net, like `adhkar_schedule_repair`;
- in reduced mode, every 15 minutes, calling `tick`.

The worker reads the mode when it runs. The job is cancelled when the reading conditions fail.

**`sync(context, nowMillis, nowElapsed)`** decides nothing about usage and never queries events, so it is cheap and safe anywhere.

1. If the reading conditions fail: **stop reading**, as section 5.5 defines it. That means cancelling the alarm and the job, clearing the record, and deleting the ledger (all of it, or all but the day fields, depending on the case). The next read then starts with a reset, so time spent while access or notifications were missing is never counted.
2. Read the mode from `PowerManager.isIgnoringBatteryOptimizations`, store it in the record, and enqueue the job with that mode's period.
3. In reduced mode, cancel the alarm.
4. In normal mode:
   - if a tick is recorded for this boot and is still ahead, or overdue by less than its window, set it again at the **same** time;
   - otherwise arm an idle tick.

   `sync` never moves a pending tick later, so a process start or a resume cannot turn a hot tick into an idle one.

**`tick(context, nowMillis, nowElapsed)`**

1. Check the reading conditions. If one fails, do what `sync` step 1 does, and stop.
2. Compare the current mode with the recorded one. If it changed (the exemption was granted or withdrawn in system settings), switch: arm or cancel the alarm, change the job's period, and record the new mode.
3. In normal mode, arm the next idle tick, so that a crash later in the tick cannot break the chain.
4. If the screen is not interactive, stop without querying. In normal mode, first replace the idle tick with one 60 seconds ahead. Being non-wakeup, it is held until the screen turns on, so the next screen-on gets a check at once. This matters when a hot tick is delivered while the screen is briefly off.
5. Read the events since the cursor, apply the resets, fold with the settle margin, and save the ledger.
6. Act on `decide(...)`:
   - `CheckAt(t)` or `DoneForToday(t)`: in normal mode, replace the idle tick with a tick at `t`. In reduced mode, nothing.
   - `Remind`: apply the delivery rules of section 5.3, then post or put off. A reminder put off in normal mode replaces the idle tick with a tick at `retryAtMillis` (at least 60 seconds ahead).

Elapsed usage always comes from event timestamps, never from counting ticks. A late or missing tick delays a reminder; it cannot distort the measured time.

**Who calls what**

| Caller | Call | Notes |
|---|---|---|
| `ScheduleRefreshCoordinator.syncAll` | `sync` | `BootReceiver` routes all six of its broadcasts there: boot, app update, time set, time zone, date change, exact-alarm permission change. The call sits in its own `try/catch` after the adhkar block, and stays out of `RefreshResult`, so the analytics of that path do not change. |
| `TunisianPrayerTimesApplication.onCreate` | `sync` | On `Dispatchers.IO`, beside `refreshDhikrReminders()`, in its own `try/catch`. |
| `MainScreen`'s `LaunchedEffect(refreshTick)` | `sync` | After the adhkar refresh, inside `withContext(Dispatchers.IO)` and `runCatching`, because that effect runs on the main thread and has no `try/catch`. It runs on every resume and after in-app permission results, once onboarding is done: that is when a newly granted access or exemption is noticed. |
| A settings change in the UI | `tick` | On `Dispatchers.IO`. A change of the counted apps or the duration resets the session first (section 5.1), so the new settings apply from a clean start. |
| `UsageNudgeReceiver` | `tick`, or the pause action | `goAsync` and `Dispatchers.IO`, as in `DhikrReminderReceiver`. |
| The periodic job | `sync` or `tick` | Depending on the mode. |

**Expected timing, normal mode.** A session is noticed within about 10 minutes of starting, and often at once, because a pending tick is released when the screen turns on. From then on, the crossing time is known exactly from the event timestamps, and a hot tick fires at it. With a threshold of 15 minutes or more, the reminder arrives on time.

**Cost.** In normal mode there is one broadcast every 10 minutes or so of screen-on time, and rarely one while the screen is off. Each tick may cold-start the process. A cold start runs `Application.onCreate`, and with it `DhikrReminderScheduler.refresh(rearm = true)`: that holds `schedulingLock` for a full scan, re-sets every adhkar alarm, and may prune history into `state_v2`. That is about six times an hour of screen-on time, where today it happens only when the app is started.

M0 measures it. If it is heavy, there are two options, both needing the owner's agreement:

- widen the idle tick to 15 minutes;
- limit the process-start re-arm of the adhkar scheduler to once an hour, keeping the re-arms at boot, on resume and in its 6-hour job.

### 5.3 The reminder notification

**Channel.** `adhkar_usage_nudge_v1`, name «تذكير أثناء التصفّح والألعاب», with the recipe of `adhkar_reminders_v2`:

- `IMPORTANCE_HIGH`, no sound, vibration `0, 250, 120, 250`, no badge.

It must be a separate channel, for two reasons:

- `DhikrReminderScheduler.refresh()` cancels every notification on the two adhkar channels whose tag is not a live occurrence;
- a separate channel lets the user mute this feature alone.

`UsageNudgeMonitor.ensureChannel` creates it at the start of every `tick` and `sync`, and the Adhkar card calls it on compose and on resume, as `AdhkarScreen` does for the adhkar channels. Otherwise, after a fresh install, the missing channel would read as "blocked".

**Content.**

- Title: the app's own words (decision 6). It names neither the app the user is in nor how long they have used it.
- Text: the dhikr text exactly as in the catalog, in `BigTextStyle`.
- Tap: opens the dhikr in the reader (section 5.4).
- One action: «إيقاف اليوم».
- Settings: `CATEGORY_REMINDER`, auto-cancel, small icon `ic_tab_adhkar`, a fresh `when`, and `setTimeoutAfter(60 min)`.
- `VISIBILITY_PRIVATE`, with a public version that shows only a neutral title («ذكر») and the dhikr. `setLocalOnly(true)`, so the reminder is not mirrored to a watch.
- Tag `adhkar_usage_nudge` with id 1. The previous reminder is cancelled before a new one is posted, so each one can show as a banner.

**Which dhikr.** A rotation over a pool of short catalog entries of praise and tahlil, all with reviewed sources:

| Id | Text |
|---|---|
| `kalimatan_khafifatan` | سُبْحَانَ اللَّهِ وَبِحَمْدِهِ، سُبْحَانَ اللَّهِ الْعَظِيمِ. |
| `tasbih_arba` | سُبْحَانَ اللَّهِ، وَالْحَمْدُ لِلَّهِ، وَلَا إِلَهَ إِلَّا اللَّهُ، وَاللَّهُ أَكْبَرُ. |
| `la_hawla_quwwata` | لَا حَوْلَ وَلَا قُوَّةَ إِلَّا بِاللَّهِ. |
| `tahlil_ashr` | لَا إِلَهَ إِلَّا اللَّهُ وَحْدَهُ لَا شَرِيكَ لَهُ، لَهُ الْمُلْكُ وَلَهُ الْحَمْدُ، وَهُوَ عَلَى كُلِّ شَيْءٍ قَدِيرٌ. |
| `subhanallah_bihamdih` | سُبْحَانَ اللَّهِ وَبِحَمْدِهِ |

Not in the default pool, but the user can add them: `istighfar_absolute`, `dhi_nun` (the supplication of one in distress) and `majlis_kaffara` (the expiation of a gathering). After a long session, their wording would read as blame or as a ruling on the session (principle 5).

Left to the owner: whether a short salawat entry should be added to the shared catalog. Today the only salawat entry is the long Ibrahimiyya form.

The pool is an editorial choice and is recorded with its date in `docs/adhkar-sources.md`. The user can change the selection to any catalog or personal dhikr without steps.

A separate finding, flagged as its own task: the catalog cites `kalimatan_khafifatan` to Bukhari 6406, whose wording has the two phrases in the reverse order.

**Delivery rules, in order.** A reminder that is due is posted unless one of these applies:

1. Notifications are blocked for the app or this channel. The reading conditions fail: nothing is posted, nothing is counted, the monitor stops, and the card shows the warning.
2. The app has the phone silenced (`SilenceStatus.isAppControlledSilenceActive`). The reminder is put off until `appSilenceEndsAt`, or 15 minutes when the end is unknown, through `retryAtMillis`.
3. Another adhkar notification was posted less than 2 minutes ago. The reminder is put off until that time plus 2 minutes.

In reduced mode, a reminder that is put off waits for the next periodic run.

**Spacing with scheduled reminders, without touching backups.** Today `DhikrReminderScheduler.receive` keeps the time of its last post as `lastAlert`, in its prefs file `adhkar_schedule_v2`. That file is backed up, so the usage reminder must **not** write its times there.

- The usage reminder keeps its own `lastPostedMillis` in its ledger, which is excluded from backup.
- The scheduler's spacing check reads the later of `lastAlert` and `lastPostedMillis`, through a small internal accessor.
- The usage reminder reads `lastAlert` through another accessor.
- Both sides do their check, post and write under a new small lock, `DhikrReminderScheduler.alertLock`.

**Lock order.** The three locks are always taken in one order, so they cannot deadlock:

- `receive` takes `schedulingLock`, then `alertLock`.
- The usage monitor takes `UsageNudgeMonitor.lock`, then `alertLock`. It never takes `schedulingLock`, which is held for a whole scan at every cold start.
- Nothing is acquired while `alertLock` is held. In particular, the `lastPostedMillis` accessor is a plain SharedPreferences read with no lock: the monitor writes that value only while holding `alertLock`, so the read is consistent. The monitor never calls `DhikrRepository`, whose writes take `schedulingLock`, while holding `alertLock`.

The user's own Do Not Disturb is left to the system: the notification is posted, and the system decides how to show it.

**«إيقاف اليوم».**

1. The action is a broadcast to `UsageNudgeReceiver`, with its own action and a data URI that carries the local date the reminder was posted.
2. It sets `quietBeforeMillis` to the local midnight that ends that date, and `quietReason` to Paused. It needs no fold of recent events: every session that started before that midnight is quiet. A press after midnight on a reminder from 23:55 therefore silences the session that was running then, not the whole new day.
3. It cancels the notification, because action buttons do not dismiss it on their own. In normal mode it arms the done tick; in reduced mode it sets no alarm.

On the card, «استئناف» is offered only when `quietReason` is Paused. It clears `quietBeforeMillis` and `quietReason`, then runs `tick`. Today's maximum has no undo: it ends at midnight.

### 5.4 Opening the dhikr from the notification

Today every deep link into the Adhkar reader names a reminder rule or an occurrence, and always resolves to an occurrence. A reminder for a bare dhikr id needs one more field along that path:

- **`MainActivity.kt`.**
  - `MainTabNavigation.EXTRA_DHIKR_ID`;
  - a `dhikrId` field in `MainTabRequests.Request`, read in `accept()`, saved and restored with the other two ids under a new `STATE_DHIKR_ID` key (the request must survive "Don't keep activities");
  - `setContent` passes `requestedDhikrId = tabRequest.dhikrId` to `MainScreen`.
- **`MainActivityPendingIntents`.**
  - `adhkarDhikr(context, dhikrId)`, with a `USAGE_NUDGE_URI` in the file's style (`tunisianprayertimes://main/adhkar/usage-nudge/<id>`);
  - `EXTRA_DESTINATION = DESTINATION_ADHKAR` and the new extra;
  - `.addFlags(FLAG_ACTIVITY_CLEAR_TOP or FLAG_ACTIVITY_SINGLE_TOP)`, as `awakeCheck` does. Without them, a tap while the app is open stacks a second `MainActivity`.
- **`MainScreen`.** A new `requestedDhikrId` parameter beside the other request parameters, forwarded to `AdhkarScreen`.
- **`AdhkarScreen`.** A new `requestedDhikrId` parameter, added to the keys of the request-handling `LaunchedEffect`. Its branch comes **before** the occurrence lookup and follows the effect's existing pattern, not `openItems`:
  1. `withContext(Dispatchers.IO) { runCatching { repo.openSession(listOf(id), fresh = true) }.getOrNull() }`;
  2. then `consumedRequest = requestedReminderSequence`;
  3. then `readerReminderSource = null` and `readerId = opened`;
  4. when nothing opened (the dhikr was deleted): a snackbar «هذا الذكر لم يعد متاحًا. يمكنك القراءة من المكتبة.»

  `openItems` is not used here: it runs through `mutate()` and would lose the request if the activity is re-created midway. `consumedRequest` is set only after the lookup, so an interrupted request is retried. With `fresh = true` the retry creates a new reading, so an interruption between the two steps can leave one extra, empty reading of that dhikr. It is harmless, and history pruning removes it. A replayed request does not open anything, since `consumedRequest` already matches its sequence. The early return that ignores a request with no reminder id and no occurrence id must let a dhikr id through.

The session is a plain reading, with no occurrence and no marker of where it came from, so it does not touch the progress of scheduled reminders. It is saved like any library reading.

### 5.5 Settings and storage

```
UsageNudgeSettings(
  enabled,
  consentVersion, consentAtMillis,  // 0 when there is no consent
  packages: Set<String>,            // counted apps, chosen or from the curated list
  excludedPackages: Set<String>,    // games the user unticked while «جميع الألعاب» is on
  allGames: Boolean,
  thresholdMinutes, maxPerDay,
  dhikrIds: List<String>,
)
```

**Active** means `enabled && consentVersion == CURRENT_CONSENT_VERSION`. This pure function in the shared module is checked by `sync`, `tick` and `UsageEventsReader`. The version goes up when the disclosure text or the use of the data changes, so an old consent no longer counts.

The counted apps are `packages`, plus every game when `allGames` is on, minus `excludedPackages`, and never this app.

The first time the feature is switched on, after consent, `packages` is seeded with the whole curated social list, including apps that are not installed. Matching is a plain string comparison, so an app installed later is counted without any change.

**Two SharedPreferences files**, both excluded from backup (section 6):

- `adhkar_usage_nudge`: the settings. A small `object UsageNudgePrefs` modelled on `NapPrefs` handles it.
  - An observer in the style of `PrefsManager.observeLocationSelection` lets the Adhkar tab react to changes.
  - The listener is held weakly by Android, so the composable keeps the subscription in a `DisposableEffect`.
- `adhkar_usage_nudge_state`: the ledger and the armed-tick record. It is written on every tick, so it is kept apart from the observed settings.

It is deliberately **not** part of `DhikrState` and **not** a `DhikrReminder`:

- A `DhikrReminder` is a clock rule. `DhikrRepository.save` requires `validate()` to pass, `refresh()` arms window-based alarms for every enabled rule, and every new field counts as a schedule edit. A usage rule fits none of this.
- `state_v2` is one JSON string rewritten with `commit()` under the scheduler's lock on every change, and it is backed up.

**When reading stops:**

"Stopping reading" always cancels the alarm and the job, clears the armed-tick record, and deletes the ledger, so the next read starts with a reset. What it keeps depends on the case:

| Case | What is kept |
|---|---|
| Switching off | Only the ordinary settings: app list, duration, maximum, pool. The consent (`consentVersion`, `consentAtMillis`) and the whole ledger are deleted, and any reminder on screen is cancelled. Switching off and on again therefore also starts today's count afresh: that is the user's explicit choice. |
| Access revoked, or notifications blocked | The settings, the consent, and the ledger's day fields (section 5.1). A re-grant therefore cannot exceed today's maximum or repeat the last dhikr. |
| Consent version out of date, after an update | The settings and the day fields. The consent is cleared; the switch stays on and the card asks to confirm again. |

### 5.6 Which apps

`UsageNudgeApps` has two parts. The curated table is pure data in the shared module, so a test can check it; the lookups are in android-app.

- **Curated table.** Package names of social and short-video apps:
  - Facebook, Facebook Lite, Instagram, Instagram Lite, Threads;
  - TikTok in its regional and Lite variants;
  - YouTube, Snapchat, X, Reddit, Pinterest, Kwai, Likee, BIGO, Twitch.

  Messaging apps are listed but not counted by default. Each id is opened once on Google Play before it ships: several were verified during research, and the appendix lists them.
- **Games.** `ApplicationInfo.category == CATEGORY_GAME`, or the older `FLAG_IS_GAME`.
  - With «جميع الألعاب» on, the monitor resolves the packages of each batch of events before folding, and passes the answers in as `isCounted`.
  - Answers are kept in memory for the life of the process, keyed with the app's `lastUpdateTime`, and never written to storage: no inventory of the user's apps is saved.
  - A failed lookup is never cached: a package that cannot be seen is "not counted" for this tick and asked again on the next one. That happens when an app was just uninstalled, or with a game that has no launcher entry.
- `CATEGORY_SOCIAL` is **not** used to preselect, because by definition it also covers messaging and email. It only sorts the picker.
- **Picker list.** Launcher apps, through `PackageManager.queryIntentActivities(MAIN/LAUNCHER)`. Both this and the game check need the `<queries>` element of decision 9. No `QUERY_ALL_PACKAGES`.
- **No lookups before consent.** The app makes no `queryIntentActivities` or `getApplicationInfo` call on other packages until consent is recorded.

### 5.7 Permission, disclosure and consent

The closest existing pattern is the Adhkar tab's exact-alarm flow: an in-app explanation first, the system page second, and a re-check on `ON_RESUME`. The new access is **not** added to onboarding, whose permission step is mandatory and has no skip. It is **not** added to the Today tab's permission banner either, which means "the app is broken without this".

**Switching on.** Both copies of the card (Today page and «تذكيراتي» sheet) go through one function, `requestEnable()`.

1. The **disclosure dialog** appears every time the feature is switched on. Draft copy, to settle in the mockups:
   - Title: «معرفة التطبيق المفتوح ومدة استخدامه»
   - Body: «ليذكّرك التطبيق بعد مدة متواصلة في التطبيقات التي تختارها، يقرأ من نظام الهاتف، حتى وهو مغلق، أيَّ تطبيق مفتوح على الشاشة ومنذ متى، ويقرأ قائمة التطبيقات المثبّتة لتختار منها. يُستعمل ذلك للتذكير فقط، ويبقى على هاتفك: لا يُرسل إلى أي جهة ولا يُحفظ في النسخ الاحتياطي. يمكنك إيقاف الميزة وسحب الإذن متى شئت.»
   - Buttons: «موافق» and «لا، شكرًا». Back and tapping outside count as "no": the switch stays off and nothing is written.
2. **On «موافق»**, the app records `consentVersion` and `consentAtMillis`, sets `enabled`, and seeds the defaults the first time. It fires `permission_step_result` (`request_opened`) once. Then:
   - if Usage access is already granted, go to step 4;
   - otherwise open `openUsageAccessSettings(activity)`, a three-step chain with each step in `runCatching`:
     1. `Settings.ACTION_USAGE_ACCESS_SETTINGS` with a `package:` URI (Android 10+);
     2. the plain action;
     3. the app's details page.

     There is no Android 12 gate, unlike `openDhikrExactAlarmSettings`: the action exists on every supported version.
3. **On the first `ON_RESUME` while the step is "awaiting access"**, re-read `usageAccessGranted(context)`. That is an `AppOpsManager` check of `OPSTR_GET_USAGE_STATS`; `MODE_DEFAULT` falls back to `checkSelfPermission`. Fire `granted` or `denied` once. Ordinary resumes do nothing.
   - **Not granted.** The card shows the access notice with «فتح الإعدادات».
   - **Restricted setting (GitHub builds, Android 15+).** If the access is still missing on Android 15 or later, the notice adds the steps in the order the system requires:
     1. On the Usage access page, tap this app's greyed-out line, so the system's "restricted setting" message appears.
     2. Open the app's info page, then the three-dot menu, then "Allow restricted settings", and confirm with the screen lock.
     3. Return to Usage access and switch it on.

     The menu item only exists after step 1. A button opens the app's info page (`ACTION_APPLICATION_DETAILS_SETTINGS`); the menu itself cannot be opened for the user. The Arabic wording is written against the labels a real Android 15 or 16 phone shows.
4. **Notifications.** If notifications are off for the app or the new channel, the notification dialog follows, with its own copy: the existing one speaks of «الأوقات التي اخترتها». It opens the settings of the new channel. Then the snackbar «فُعّل التذكير. يمكنك تعديل التطبيقات والمدة من البطاقة.», and `tick` runs.

**Sharing the prompt machinery with the adhkar reminders.** Today the notification dialog, its permission launcher and the settings opener serve only the two adhkar channels, and every way out leads to the exact-alarm prompt. They are generalised without changing their behaviour for adhkar:

- `openDhikrNotificationSettings` takes a channel id; the current `vibrate` form stays as a wrapper.
- The saveable `permissionPromptVibrate` becomes a saveable prompt target: adhkar with vibration, adhkar quiet, or usage nudge. The target chooses:
  - the dialog copy;
  - the channel checked and opened;
  - the next step: the exact-alarm prompt for adhkar, the snackbar for the usage nudge, which never touches `exactAlarmPromptAfterNotifications`.
- The launcher callback and the `ON_RESUME` branch read the target. The existing leaving-for-settings and `ON_PAUSE` handling is reused, not copied.

**One dialog at a time.** Today the only guard is `exactAlarmPrompt && !permissionPrompt`. The plan adds:

- a saveable `usagePromptStep` (disclosure, awaiting access, notifications, or none);
- the disclosure shows only when `!permissionPrompt && !exactAlarmPrompt`;
- the exact-alarm dialog also waits while `usagePromptStep` is the disclosure.

Every new flag is `rememberSaveable`, because a settings page re-creates the activity on the test phone.

**Switching off.** The steps of section 5.5 apply. If the access is still granted, a snackbar offers «سحب الإذن», which opens the Usage access page. Revoking it is the user's choice; the app stops reading either way.

**Later.** Access can be revoked at any time, and it is not restored by a backup. Every tick re-checks it, and the feature goes quiet without it.

### 5.8 UI

Per the working agreement, **mockups come first** (M2) and are approved before any Compose is written. They cover:

- every row of the card's state table;
- the disclosure and the restricted-setting help;
- the settings sheet, the app picker and the dhikr selection;
- the notification as it appears over an app.

**Card.** It sits on the Adhkar Today page as the last item, after the reminders list. It also sits at the end of the «تذكيراتي» sheet, after «تذكيرات مقترحة». At the end of both lists, its height can change when a notice appears without moving any row under the user's finger. That is the rule the sheet already follows for its suggestions.

- It is its own composable: `HomeReminderRow`, `DhikrReminderRow` and `AdhkarReminderSlot` are all typed around `DhikrReminder`.
- It takes a horizontal-padding parameter: 20 dp on the Today page, whose items pad themselves, and 0 in the sheet, whose list pads already.
- It uses a stable key. In the sheet it also uses `Modifier.animateItem()`, like the rows there; the Today page's rows do not animate, so neither does the card there.
- It shows the name, a one-line summary («بعد 30 دقيقة متواصلة · حتى 3 إشعارات يوميًا»), and an `AdhkarSwitch`. Tapping it opens the settings sheet.

States, with at most one notice at a time, in this order of priority:

| Condition | Switch | Notice |
|---|---|---|
| Off | off | none |
| On, consent out of date | on | «أكّد الموافقة لمتابعة التذكير», which opens the disclosure. It comes first because the disclosure must precede any request for access. |
| On, Usage access missing | on | the access notice, with the restricted-setting help on Android 15+ |
| On, notifications blocked | on | notification settings for the channel |
| On, paused (`quietReason` Paused, now before `quietBeforeMillis`, or the running session started before it) | on | «متوقف حتى الغد», with «استئناف» |
| On, today's maximum reached (`quietReason` Maximum, same times) | on | «اكتملت تذكيرات اليوم», with no button |
| On, battery optimisation on | on | «قد تتأخر التذكيرات», with a link to the battery-exemption request |
| On | on | none |

**Settings sheet.** A new file, on the scaffold of `DhikrRemindersSheet`, not the reminder editor: `ModalBottomSheet`, `DhikrSheetHeader`, and a list with `rememberSheetScrollGuard`. Changes apply at once: no draft, no save button, no discard prompt. It takes the screen's `SnackbarHostState` and draws its own `SnackbarHost` for it, as the existing sheets do. It contains:

- the apps («12 تطبيقًا وجميع الألعاب»);
- the duration before the reminder (a stepper with quick picks, no keyboard);
- the daily maximum;
- the adhkar;
- notification settings and a preview;
- a short note on what is not counted (browser use, sections inside an app such as Shorts, picture-in-picture, a work profile);
- the privacy sentence.

**Pickers.**

- **App picker.** A search field, «جميع الألعاب», then apps with icon and name, multi-select. With «جميع الألعاب» on, games appear ticked and can be unticked one by one (`excludedPackages`).
- **Dhikr selection.** Multi-select over catalog and personal adhkar, with the note «يتنوّع الذكر في كل مرة».

These are pickers inside the settings sheet. They use the editor's dialog pattern only if they turn out to need confirm and cancel.

Conventions to keep:

- Arabic literals in Kotlin, as in the rest of the Adhkar UI;
- «إشعار» for a notification and «تذكير» for the rule;
- Latin digits through `latinNumber`;
- everything inside `AdhkarTheme`.

Controls reused: `AdhkarCard`, `AdhkarSwitch`, `AdhkarSectionHeader`, `DhikrSheetHeader`, `rememberSheetScrollGuard`. `SettingRow`, `ToggleRow` and `StepButton` are private to `AdhkarReminderEditor.kt` today. They become `internal`, a visibility change only: no other function with these names exists in the app or the shared module (checked 2026-10-06).

## 6. Privacy, policy and store

| Item | Change |
|---|---|
| Backup | Exclude `adhkar_usage_nudge.xml` and `adhkar_usage_nudge_state.xml` (`domain="sharedpref"`) in `res/xml/backup_rules.xml` and in both sections of `data_extraction_rules.xml`. Today every prefs file is backed up. The usage reminder never writes to a backed-up file (section 5.3). |
| What is stored | The settings and consent, the current session (start, last watched time, counted front app), today's count, the pause, the last dhikr and post time, and the armed tick. There is no per-app history, no list of installed apps and no name of an app the user did not choose. Switching off deletes everything except the ordinary settings. |
| Analytics | Only decision 8. Nothing about app names, categories, minutes, counts or reminder times. Never fired from `tick`, `sync` or the job. Analytics cannot be switched off by the user today, so the rule is strict. |
| Logs | In every build: no package names, labels, durations or session times in `Log` calls, exception or `require`/`check` messages, intent extras, data URIs or Work input. `NameNotFoundException`, whose message is the package name, is caught where it happens and not logged. Diagnostics go behind `BuildConfig.DEBUG`. M3 adds a grep check. |
| Notification | Names neither the app in use nor a duration; private on the lock screen; not mirrored to watches (section 5.3). |
| Privacy policy (`docs/privacy-policy.html`) | Change the date. Reword the permissions intro to "some of which are optional", and add "Usage access (optional)" in the style of the Location entry: what is read (the app in front and since when, and the list of installed apps for the picker), that it is processed on the phone, and what is kept (the current session and today's count, deleted when the feature is switched off). Add "names of other apps and usage times" to what analytics never includes. Update the Notifications entry, which mentions only wake alarms, to cover adhkar and usage reminders. Say once that settings may be in the user's Android backup, except the usage-reminder files. In the data-deletion section, add switching the feature off and withdrawing Usage access. |
| `README.md` privacy section | Rewrite it. It says the app collects no analytics (it uses Firebase Analytics) and makes only an hourly GitHub request (it also downloads Quran files from Google Play and Cloudflare, and the policy says the date check runs once a day in Ramadan). Point to the policy. |
| `docs/adhkar-sources.md` | A dated editorial note for the reminder pool and the entries left out of it. A line stating that durations, app lists and daily limits are personal settings. |
| Play listing | Describe the feature in the description, since sensitive accesses must serve a feature promoted in the listing. |
| Data safety form | Review it once the items above are done; no new entry is expected because nothing leaves the device. |
| Manifest | `PACKAGE_USAGE_STATS` with `tools:ignore="ProtectedPermissions"` and a one-line comment like its neighbours; the `<queries>` element; the receiver. |

The Play Console steps are manual and are the owner's to do. The repo has no checklist for them, so they are listed in M5.

## 7. Files

**New**

| File | Content |
|---|---|
| `multiplatform/shared/.../adhkar/UsageNudgeComputer.kt` | `UsageSample`, settings and ledger types, `isActive`, the fold with the settle margin, resets, `decide`, rotation, the decision on keeping an armed tick, `UsageNudgeTuning`, the curated package table. Pure Kotlin. |
| `multiplatform/shared/src/commonTest/.../adhkar/UsageNudgeComputerTest.kt` | The logic tests of section 9. |
| `android-app/.../adhkar/UsageNudgePrefs.kt` | The two files, the observer. |
| `android-app/.../adhkar/UsageAccess.kt` | `usageAccessGranted`, `openUsageAccessSettings`, `UsageEventsReader`. |
| `android-app/.../adhkar/UsageNudgeApps.kt` | Game check with its in-memory cache, launcher app list. |
| `android-app/.../adhkar/UsageNudgeMonitor.kt` | `sync`, `tick`, the lock, alarms, periodic job, channel, posting. |
| `android-app/.../adhkar/UsageNudgeReceiver.kt` | Tick and «إيقاف اليوم» broadcasts. |
| `android-app/.../adhkar/UsageNudgeWorker.kt` | Calls `sync` in normal mode or `tick` in reduced mode. |
| `android-app/.../ui/AdhkarUsageNudge.kt` | Card, dialogs, settings sheet, pickers. |
| `android-app/app/src/test/.../adhkar/UsageNudgeMonitorTest.kt` | Robolectric tests of section 9. |
| `android-app/app/src/debug/AndroidManifest.xml` and a debug receiver | Debug-only and exported: sets the settings from adb (M3), with a threshold that may be as low as 1 minute. The debug source set holds only `res/values/strings.xml` today. |

**Changed**

| File | Change |
|---|---|
| `AndroidManifest.xml` | Permission, `<queries>`, receiver. |
| `res/xml/backup_rules.xml`, `data_extraction_rules.xml` | Exclusions. |
| `ScheduleRefreshCoordinator.kt` | `UsageNudgeMonitor.sync` in `syncAll`, in its own `try/catch`, outside `RefreshResult`. |
| `TunisianPrayerTimesApplication.kt` | `sync` at process start. |
| `MainActivity.kt` | `EXTRA_DHIKR_ID`, `Request.dhikrId`, `STATE_DHIKR_ID`, `accept()`, and the `setContent` argument. |
| `MainActivityPendingIntents.kt` | `USAGE_NUDGE_URI`, `adhkarDhikr(...)`. |
| `ui/MainScreen.kt` | `requestedDhikrId` parameter, forwarded; `sync` in the resume effect. |
| `ui/AdhkarScreen.kt` | `requestedDhikrId` parameter and the deep-link branch; the card on the Today page and in the sheet; the prompt target replacing `permissionPromptVibrate`; `usagePromptStep` and the dialog guards; `openDhikrNotificationSettings` with a channel id. |
| `ui/AdhkarReminderEditor.kt` | `SettingRow`, `ToggleRow`, `StepButton` become `internal`. |
| `adhkar/DhikrReminderScheduler.kt` | `alertLock`; `receive` takes it around its spacing check, post and write; the spacing check also reads the usage reminder's `lastPostedMillis`; an internal read of `lastAlert`; `notificationsEnabled` overload with a channel id. |
| `AnalyticsTracker.kt` call sites | `permissionStepResult(..., "usage_access", ..., "adhkar")` from the switch-on flow only. |
| Docs | As in section 6. |

## 8. Milestones

Each milestone ends in something that can be checked on its own. Work stays uncommitted on `codex` for the owner to commit.

**M0 — Device spike (throwaway debug code, not kept).** The documentation and AOSP answer most questions. This checks them on the OnePlus, whose maker is among the most aggressive at stopping background apps, and on emulators:

1. Tick cadence really achieved over an hour of use with the battery exemption, read from `dumpsys alarm`; how soon a pending tick fires after screen-on.
2. What one cold-start tick costs, including the adhkar `refresh(rearm = true)` it triggers (section 5.2, Cost).
3. The same hour in reduced mode (exemption withdrawn): how often the 15-minute job really runs.
4. Whether events of the app in front are returned at once by `queryEvents`; how late an event can be stored after its timestamp; and the order of pause and resume when moving between two screens of one app.
5. The banner over TikTok, YouTube and a game, with and without the phone's game mode.
6. `ApplicationInfo.category` of the installed social apps and games; whether the `<queries>` launcher intent lists every launcher app.
7. Whether a `package:` URI opens the app's own Usage access switch. On an Android 15+ device, whether an APK installed from the Files app shows the restricted-setting flow of section 5.7, and the labels to quote in the help text.
8. On Android 8.1 and 9 emulators: whether a background event is logged when the screen turns off with an app in front. This decides whether the Android 8 safeguard of section 5.1 is built.

Outcome: the constants of `UsageNudgeTuning` are confirmed or adjusted, the cost question is settled, and the help text of section 5.7 matches a real phone.

**M1 — Logic.** `UsageNudgeComputer.kt` and its tests in the shared module, all passing with `./gradlew :shared:javaTest`. No Android code. It can start alongside M0.

**M2 — Mockups.** HTML mockups of everything in section 5.8, with the Arabic copy. The owner approves them, together with decisions 2 to 8 and 11.

**M3 — Plumbing.** It can start while the mockups wait for approval. It covers:

- prefs, reader, monitor, receiver, worker, channel and notification;
- the adhkar spacing changes and the deep link;
- the manifest and the backup rules;
- the hooks in `syncAll`, `Application` and `MainScreen`;
- the debug receiver and the log grep check.

It is checked with adb, before any UI exists: grant the access from the shell, switch the feature on with a 1-minute threshold through the debug receiver, and watch the reminder arrive and open the reader.

**M4 — UI.** Card, dialogs, settings sheet, pickers, the prompt generalisation and guards, notices.

**M5 — Privacy and store.** Policy, README and sources doc. The owner reviews the Play listing text and the Data safety form before the release that carries the feature.

**M6 — Validation.** The device checklist of section 9 on the OnePlus, then tuning of the constants.

## 9. Tests and validation

**The android-app unit tests currently do not run.** The unit-test task builds a resource package of about 2.45 GB since the Quran audio was bundled, and fails. That affects every class in `android-app/app/src/test`, Robolectric or not, including the existing `AdhkarFlowTest`, `MainTabRequestsTest` and `MainActivityPendingIntentsTest`. M1 first checks whether this is still the case. Meanwhile:

- the logic tests live in the shared module, where they run;
- the android-app tests below are written and run once the task works again;
- the device checklist is the gate for everything that touches Android.

**Shared module (`UsageNudgeComputerTest`)**, run with `./gradlew :shared:javaTest` from `multiplatform/`:

- Thresholds and sessions:
  - one app in front for longer than the threshold, exactly at it, and just below;
  - moving between two counted apps; a short visit to another app; a break of 2 and of 4 minutes;
  - the screen off and on, inside and outside the break limit.
- Timing:
  - leaving for WhatsApp at 28:00 and coming back at 30:30 in a 30-minute session gives a check at 31:00 and a reminder at 32:30, not at the next idle tick;
  - the same with the screen off from 28:00 to 30:30.
- Front model:
  - two screens of one app, with the pause logged before and after the next resume;
  - a missing pause followed by another app's resume;
  - split screen; picture-in-picture (a paused activity);
  - a front still open at "now" with the screen off;
  - this app's two ids.
- Settle margin:
  - a pause stamped 10 ms before "now" that arrives on the next read is folded then;
  - a resume stamped just before the cursor is not lost;
  - no event is counted twice.
- Resets:
  - a reboot detected through `bootTime` on the Android 8 event set (a resume before the boot with no pause gives nothing after it);
  - the clock moved forward 4 hours while a counted app is in front: no reminder;
  - the clock moved back 5 minutes, less than the time since the last tick, and moved back 5 seconds: a reset in both cases, no event lost or counted twice;
  - a reminder followed by a reset 4 minutes later: no second reminder;
  - a cursor older than 12 hours; an unknown ledger version;
  - switching on while a counted app is in front;
  - access lost with a counted app in front, then re-granted 40 minutes later: a reset, with the day fields kept;
  - a change of the counted apps or the duration: a reset; the app in front, if no longer counted, is no longer stored.
- Decisions:
  - a second reminder only after a further full threshold;
  - the daily maximum, then midnight inside the same session: no reminder until a new session;
  - «إيقاف اليوم» at 23:58 in a continuous session: nothing at 00:00;
  - «إيقاف اليوم» at 22:00, then a new session from 23:20 with no tick before midnight: nothing at 00:00;
  - «إيقاف اليوم» pressed in a new session while the saved ledger still describes the previous one: the new session is quiet;
  - a press at 00:05 on a 23:55 reminder: only that session is silenced;
  - a reminder put off and shown later only if `Remind` still holds;
  - the ledger is committed before posting.
- Consent: enabled without consent, or with an old version, means not active.
- Rotation: never the same dhikr twice in a row, deleted entries skipped, a pool of one, an empty pool falling back to the default.
- Armed tick: a pending hot tick is kept by `sync`; an overdue one and one from a previous boot are replaced.
- Privacy: the serialised ledger contains no package outside the counted set.
- Curated table: no duplicates, and no messaging app among the defaults.

**Robolectric (`UsageNudgeMonitorTest`)**, in the style of `AdhkarFlowTest` and `SilenceVerifyWorkerTest`, covering:

- `sync` arming and cancelling; a hot tick followed by `sync` keeps its time;
- normal and reduced mode, the mode stored in the record, and a mode switch detected by `tick`;
- idle, hot and done ticks; a reminder put off arms a tick at its retry time; a tick delivered with the screen off re-arms 60 seconds ahead;
- `sync` with access missing deletes the ledger except its day fields;
- nothing posted without access, without consent, or with notifications blocked;
- the channel created before the check;
- reminders put off under app silence and after a recent adhkar notification;
- `adhkar_schedule_v2` unchanged after a usage reminder is posted;
- the channel, tag, visibility, action and deep-link extras of the posted notification;
- «إيقاف اليوم» cancelling the notification;
- the periodic job being unique;
- a failed game lookup not cached.

Events are fed with `ShadowUsageStatsManager.addEvent(...)`. Two traps:

- The shadow returns its events whatever the access state.
- Robolectric reports the app-op as allowed by default.

So the "no access" cases set the mode explicitly with `ShadowAppOpsManager.setMode(...)` and assert through `usageAccessGranted`.

**Existing tests to extend:**

- `MainTabRequestsTest`: the new id is accepted, saved and restored (with destination null after a re-creation), including a re-creation between `accept` and the opening of the reader.
- `MainActivityPendingIntentsTest`: the new URI, extras and flags.
- `AdhkarFlowTest` must stay green, in particular the 2-minute spacing cases, now under `alertLock`.

**From the shell.** Always pass `-s <serial>`; the debug build's id ends in `.dev`.

Grant the access without the settings page:

```bash
adb -s <serial> shell appops set com.tunisianprayertimes.dev GET_USAGE_STATS allow
```

Return the access to the fresh-install state:

```bash
adb -s <serial> shell appops set com.tunisianprayertimes.dev GET_USAGE_STATS default
```

Print the events of the last 24 hours that the system recorded for a package:

```bash
adb -s <serial> shell dumpsys usagestats com.zhiliaoapp.musically
```

**Device checklist (OnePlus, "Don't keep activities" on)**

1. Switching on from the Today card and from the sheet: disclosure, settings page, back. The activity is re-created and the flow continues to the notification dialog and the snackbar. No adhkar dialog appears at the same time.
2. Declining at each step:
   - «لا، شكرًا» leaves the switch off;
   - refusing the access leaves the notice;
   - revoking it later from the phone's settings: the card shows the notice, nothing crashes, nothing is posted.
3. Granting Usage access from the phone's settings without switching the feature on: nothing is read, nothing is posted.
4. 30 minutes in a counted app: a banner with a dhikr. A tap opens a new reading of that dhikr, even with the app already open, and without a second `MainActivity`. «إيقاف اليوم» stops it for the rest of the day; «استئناف» on the card restarts it.
5. Reaching today's maximum: the card shows «اكتملت تذكيرات اليوم» with no button; after «إيقاف اليوم» it shows «متوقف حتى الغد» with «استئناف».
6. Alternating between two counted apps; a 2-minute break; a 5-minute break.
7. During prayer auto-silence: no reminder. After it ends, with the session still going: a reminder.
8. A scheduled adhkar reminder at the same moment: no stacking, in either order.
9. Reboot, then use a counted app without opening this app: the reminder still arrives, counted from after the reboot.
10. Moving the clock forward an hour while in a counted app: no immediate reminder.
11. Withdrawing the battery exemption: the card shows the delay notice, and reminders still arrive (later). Granting it again: back to normal mode.
12. Switching off while a reminder is showing: it disappears, and the snackbar offers to withdraw the access. Switching on again shows the disclosure again.
13. A GitHub-style build installed from the Files app on an Android 15+ device: the restricted-setting help leads to a working switch.
14. Battery use of the app over a day with the feature on.

## 10. Known limits

These are stated in the settings sheet where the user needs them:

- Use inside a browser is not counted, and sections inside an app (Shorts, Reels) cannot be separated from the app.
- Apps in a work profile or private space are not seen.
- A video in picture-in-picture is not counted. In split screen, only the app used last counts.
- In games, a manufacturer's game mode or Do Not Disturb may hide the banner. The reminder then waits in the notification shade for up to an hour, and still counts toward the day's maximum.
- The reminder is approximate. It can be a few minutes late, and a quarter of an hour or more when the app is not exempt from battery optimisation.
- After the app is force-stopped, nothing runs until it is opened again.
- A GitHub build on Android 15 or later needs the extra "allow restricted settings" step before the access can be switched on.

## 11. Later, if wanted

- **Daily total**: one extra reminder when the day's total in the counted apps passes a limit. It would need a new consent version, since it keeps a running total.
- **Stronger mode**: a small card drawn over the app instead of a banner (`SYSTEM_ALERT_WINDOW`). This is a separate decision: its own permission, a clumsy grant page on Android 11+, and a Play policy check first.
- **Per-app durations**, or separate durations for social apps and games.
- **A prayer-time variant**: a reminder when a counted app is in front as the adhan time arrives.

## Appendix: sources and verification notes

- **Android**: `UsageStatsManager`, `UsageStatsService` and `UsageEvents.Event`; `AppOpsManager`; `AlarmManager` and `AlarmManagerService` (non-wakeup holding, minimum windows, standby quotas, exemptions); notification channels and heads-up behaviour; package visibility; foreground-service types and background-start limits; Enhanced Confirmation Mode. Sources: developer.android.com and AOSP source.
- **Google Play**: the User Data policy (prominent disclosure and consent), Data safety form help, the Package visibility policy, the Accessibility API policy, the foreground service declaration, and the policy announcements of April and July 2026.
- **Prior art**: Play listings of «اذكاري», Nafs, Dhikr Lock, Quran Screen, one sec and ScreenZen; Digital Wellbeing help.
- **Evidence**:
  - Grüning, Riedel and Lorenz-Spreen, PNAS 2023 (the one sec field study). Response to an identical prompt fell from 43% to about 33% in three weeks, then held. A message alone was not effective; an easy alternative action was.
  - Kovacs, Wu and Bernstein, CSCW 2018. Rotating interventions stay effective longer, and explaining the rotation halves the extra attrition.
- **Thresholds**: no source gives an evidence-based threshold in minutes, so the defaults in decision 2 are a design judgement.
- **Package ids confirmed on Google Play during research**: `com.facebook.katana`, `com.facebook.orca`, `com.instagram.android`, `com.instagram.barcelona`, `com.zhiliaoapp.musically`, `com.tiktok.lite.go`, `com.ss.android.ugc.tiktok.lite`, `com.kwai.video`, `kwai.lite.video`, `sg.bigo.live`, `sg.bigo.live.lite`, `com.supercell.clashofclans`, `com.supercell.clashroyale`, `com.supercell.brawlstars`, `com.ludo.king`, `com.mobile.legends`. All others are to be opened once before the table ships.
- **Texts**: the candidate verse of decision 6 is الرعد 29 in the app's Qaloon index (28 in the Hafs count). The pool reuses catalog entries unchanged, so it adds no new sourcing.
