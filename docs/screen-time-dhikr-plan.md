# Dhikr reminder after long social-media or game sessions — Plan

Status: plan only, nothing is implemented. Written 2026-10-04 for `android-app/` (branch `codex`).

Working name in code: **usage nudge**. Draft name in the app: «تذكير أثناء التصفّح والألعاب».

---

## 1. Goal and principles

When the user has been in social-media apps or games for a long stretch, the phone shows a short dhikr they can say on the spot. One tap opens that dhikr in the Adhkar reader.

Principles the rest of the plan follows:

1. **A reminder, not a blocker.** Nothing is locked, delayed or covered. Every existing Islamic screen-time app found on Google Play is a blocker built on the Accessibility Service; this feature needs neither.
2. **Opt-in and off by default.** It needs a special system access (Usage access), so it starts only after the user switches it on and accepts an in-app explanation.
3. **The dhikr is in the notification.** The user can fulfil the reminder by reading the banner, without leaving the app they are in.
4. **Everything stays on the phone.** No app names, no durations and no counters leave the device: not to analytics, not to backup.
5. **Gentle by design.** A few reminders a day at most, varied text, no usage statistics, no guilt wording, and a one-tap «إيقاف اليوم».
6. **Thresholds are personal settings**, never presented as a religious prescription (same rule as `docs/adhkar-sources.md` applies to targets and time windows).

## 2. What the user gets

| Step | Behaviour |
|---|---|
| Switch on | In the Adhkar tab, a new card «تذكير أثناء التصفّح والألعاب». Switching it on shows an explanation of what is read and why, then opens the system "Usage access" page. |
| Choose apps | A list of installed apps with social and short-video apps and games preselected. The user confirms or edits it. A «جميع الألعاب» switch covers games installed later. |
| Use the phone | After **30 minutes** (default) of continuous use of the chosen apps, a banner appears with a short dhikr, for example «سُبْحَانَ اللَّهِ وَبِحَمْدِهِ، سُبْحَانَ اللَّهِ الْعَظِيمِ.» |
| Keep scrolling | The next reminder comes only after another full 30 minutes of continued use, with a different dhikr, and never more than **3 a day** (default). |
| Tap the banner | The Adhkar tab opens on that dhikr in the reader, with the counter. |
| «إيقاف اليوم» | A button on the notification silences the feature until tomorrow. |

"Continuous use" means time in the chosen apps where breaks of up to 3 minutes (another app, or the screen off) do not start a new session; the break itself is not counted.

## 3. Decisions to confirm

These are product choices. Each has a recommendation, and the plan below assumes it.

| # | Decision | Recommendation | Alternative |
|---|---|---|---|
| 1 | What "too much" means | Continuous session time in the chosen apps | A daily total across the chosen apps. Fires once, late in the day, and overlaps with the system's Digital Wellbeing timers. Kept as an optional extra in section 11 |
| 2 | Defaults | 30 minutes, at most 3 reminders a day. Choices: 15, 20, 30, 45, 60, 90 minutes; 1, 3 or 5 a day | — |
| 3 | Apps on by default | Installed apps from a curated social and short-video list, plus games. Messaging apps (WhatsApp, Messenger, Telegram, Discord) listed but off | Start with nothing selected |
| 4 | Which dhikr | A rotation of short catalog adhkar, editable by the user (section 5.3). Rotation slows the habituation seen with a repeated identical prompt | One fixed dhikr |
| 5 | Notification wording | Title in the app's own plain words (draft «استراحة ذكر»), body = the dhikr text. No verse or hadith in the title in v1 | A verified line such as «أَلَا بِذِكْرِ اللَّهِ تَطْمَئِنُّ الْقُلُوبُ» (الرعد 29 in the app's Qaloon numbering); needs an entry in `docs/adhkar-sources.md` and the owner's approval |
| 6 | Vibration | One notification channel with a short vibration; the settings sheet links to the system channel page | A vibrating and a quiet channel chosen by an in-app switch, as scheduled reminders do |
| 7 | Analytics | Only the existing `permission_step_result` event with `permission_type = "usage_access"`. Nothing about the feature's use | One coarse on/off event |
| 8 | Listing installed apps | A `<queries>` element for launcher apps, used by the visible app picker | Curated `<package>` entries only: no picker for arbitrary apps and no game detection |
| 9 | Distribution | Same manifest for Play and GitHub builds (there are no product flavors today) | A flavor without the permission |

## 4. Platform facts that shape the design

Checked against Android and Google Play documentation and AOSP source on 2026-10-04. AOSP describes stock Android; manufacturers' builds can differ, which is what milestone M0 checks on a real phone.

**Detection**

- A third-party app can learn about other apps' usage only by polling `UsageStatsManager` after the user enables "Usage access" in system settings. The push-style observers (`registerAppUsageObserver` and relatives) are system-only.
- `queryEvents()` is the method to use. `queryUsageStats()` returns whole-day buckets and cannot answer "the last 30 minutes".
- Event types by Android version: 8.0–8.1 have only foreground and background events (values 1 and 2); 9 adds screen and keyguard events; 10 adds `ACTIVITY_STOPPED`, `DEVICE_SHUTDOWN` and `DEVICE_STARTUP`. Values 1 and 2 keep their meaning on every version, so one code path serves all.
- Events are served from memory, so an app's foreground event is visible to a query within moments. Events of other apps are not filtered by package visibility.
- An app that is still in the foreground has no closing event: the app's own logic closes its span at "now", and only if the screen is on. A long uninterrupted session produces one event and then nothing, so an empty result means "no change", not "nothing in front".
- An empty result is also what the system returns when the access was revoked, or (from Android 11) before the first unlock after boot. The reader must check both explicitly; "no data" must never be read as "zero usage".
- Usage is per package and per profile. Facebook in a browser counts as the browser; Shorts count as YouTube; apps in a work profile or private space are invisible; instant apps are reported under an obscured name.

**Background execution**

- WorkManager periodic work cannot run more often than every 15 minutes.
- Alarms of the non-wakeup types (`ELAPSED_REALTIME`, `RTC`) never wake a sleeping phone. One that comes due while the screen is off is held by the system and released when the screen turns on (or, at the latest, after a bounded delay if the processor happens to be awake). A self-rearming non-wakeup alarm therefore costs nothing while the phone sleeps and doubles as a "screen turned on" signal. Its receiver can still run with the screen off, so it must check `PowerManager.isInteractive`.
- **Standby-bucket quotas are the binding limit.** An app in the background is limited to 10, 2 or 1 alarm deliveries an hour (working set, frequent, rare), counted across all its alarms that are not alarm-clock or exact-while-idle alarms — non-wakeup and plain exact alarms included. A frequent tick could use up that allowance and delay the app's own fallback alarms.
- An app **exempt from battery optimisation** has no such quota and may always set exact alarms. This app requires that exemption in onboarding, so it is the normal case; the user can still withdraw it later.
- An inexact alarm set a few minutes ahead may be delivered up to 75% of that delay late; only an exact alarm lands on the minute.
- A permanent foreground service would be the most reliable watcher, and is what blocker apps use. It is rejected here: it needs a permanent notification and a Play Console declaration with a demo video, its policy test ("cannot be deferred") does not fit a reminder that may arrive a few minutes late, and `SilenceGuardService` documents that this app avoids all-day foreground services.

**Notification**

- A heads-up banner needs a channel created with `IMPORTANCE_HIGH`. The app sets importance once, at creation; afterwards only the user can change it.
- Stock Android shows the banner over full-screen apps. It is not shown under Do Not Disturb, for a while after the user swipes one of the app's banners away, or when a manufacturer's game mode blocks banners (Samsung, OnePlus, Xiaomi, Pixel Game Dashboard). An app cannot detect or override these. The notification still lands in the shade.
- A full-screen intent is not available for this use (Play limits it to alarms and calls) and would only produce a banner on an unlocked phone anyway.

**Policy**

- `PACKAGE_USAGE_STATS` has no Play Console declaration form. The data is "personal and sensitive" under the User Data policy, which requires a privacy-policy entry and an **in-app prominent disclosure with affirmative consent shown before the access is requested**, even when processing is on-device only.
- Data safety form: data that is only processed on the device is not "collected", so no new entry — provided nothing reaches analytics, crash reports or a server.
- `QUERY_ALL_PACKAGES` needs a declaration and does not fit this use. A `<queries>` launcher intent needs none, but must back user-facing functionality: here, the app picker.
- An `AccessibilityService` is ruled out: monitoring apps are named as not being accessibility tools.
- **GitHub builds on Android 15 and later**: an app installed from a downloaded APK has Usage access listed among the "restricted settings". Its switch is greyed out until the user allows restricted settings for the app (section 5.7). Play installs are not affected, nor is Android 14 and earlier. An app cannot detect this state or open the screen that lifts it.

## 5. Design

### 5.1 Detection: from usage events to a session

All of this is plain Kotlin with no Android types, in `adhkar/UsageNudgeModels.kt`, so it is covered by ordinary JUnit tests.

**Input.** `UsageSample(type, packageName, className, timeMillis)` — a copy of the four fields read from each `UsageEvents.Event`. A thin reader, `UsageEventsReader.read(context, fromMillis, toMillis): List<UsageSample>?`, is the only code that touches `UsageStatsManager`. It returns `null` for "no data": access missing (checked through `AppOpsManager`, since the system answers a revoked access with an empty result), user not unlocked since boot (`UserManager.isUserUnlocked`), or an exception. An empty list means "no new events". It keeps only the event types listed below, and queries half-open ranges `[from, to)` so that the next read starts exactly where this one ended.

**Folding events into foreground time.**

| Event | Effect |
|---|---|
| 1 (`ACTIVITY_RESUMED`) | The package is in the foreground from this time. |
| 2 (`ACTIVITY_PAUSED`) | The package leaves the foreground, unless another activity of the same package resumes within 2 seconds (moving between screens of one app). |
| 16, 17 (`SCREEN_NON_INTERACTIVE`, `KEYGUARD_SHOWN`), Android 9+ | Everything leaves the foreground. |
| 26, 27 (`DEVICE_SHUTDOWN`, `DEVICE_STARTUP`), Android 10+ | Open spans are dropped: their end is unknown. |
| any other type | Ignored. |

Several packages can be resumed at once (split screen, picture-in-picture), so the fold keeps a set of resumed packages, not a single one. Time counts as "watched" while at least one resumed package is a chosen app. A span still open at the end counts up to "now" only when `PowerManager.isInteractive` is true.

**Incremental, with a cursor.** A game can stay in the foreground for two hours without a single event, so a fixed look-back window would miss its start. The monitor therefore keeps a small ledger and folds only the events since the last check:

```
UsageNudgeLedger(
  cursorMillis,            // events up to here are folded
  resumed,                 // packages in the foreground at the cursor
  sessionStartMillis,      // current session, or 0
  lastWatchedEndMillis,    // when watched time last stopped
  watchedSinceNudgeMillis, // watched time in this session since the last reminder (or its start)
  day, countToday,         // reminders shown on that local date
  pausedDay,               // «إيقاف اليوم» was pressed on that date
  lastDhikrId,             // for rotation
)
```

The ledger is rebuilt from a 12-hour query when it has no cursor, when the cursor is more than 12 hours old, when the device has rebooted since, or when the wall clock moved backwards. A rebuild never produces a reminder for time before the feature was switched on.

**Session rule.** A watched span that starts more than 3 minutes after `lastWatchedEndMillis` starts a new session and resets `watchedSinceNudgeMillis`. Otherwise it extends the session.

**Decision.** `decide(ledger, settings, nowMillis, inWatchedAppNow): UsageNudgeDecision`

- `Remind` when the user is in a chosen app now and `watchedSinceNudgeMillis >= threshold`.
- `CheckAt(millis)` when the user is in a chosen app but below the threshold: the time at which the threshold will be crossed if they stay.
- `Idle` otherwise.

After a reminder is shown, `watchedSinceNudgeMillis` returns to zero, so the next one needs another full threshold of continued use.

### 5.2 Monitor: what wakes the check

`adhkar/UsageNudgeMonitor.kt`, an `object` in the style of `DhikrReminderScheduler`, with explicit `nowMillis` parameters for tests.

No service and no permanent notification. The check (`tick`) is the same in both modes below; what differs is what triggers it.

**Normal mode — the app is exempt from battery optimisation.** A self-rearming, non-wakeup alarm:

- **Idle tick**: while no chosen app is in front, an alarm of type `ELAPSED_REALTIME` about every 10 minutes. It never wakes a sleeping phone, and a tick that came due with the screen off is released when the screen turns on.
- **Hot tick**: when a chosen app is in front, one exact alarm (`setExact`, still non-wakeup) at the predicted crossing time from `CheckAt`. Exact alarms are always available to a battery-exempt app; the code still checks `canScheduleExactAlarms()` and falls back to `setWindow`, the shape of `AwakeCheckScheduler.schedule`.
- One `PendingIntent` (explicit broadcast to a new non-exported `UsageNudgeReceiver`, its own data URI, request code 0), so re-arming replaces the previous tick.

**Reduced mode — the exemption is missing.** No alarms at all, so the feature can never use up the alarm allowance that the app's fallback adhkar and awake-check alarms depend on. A unique periodic WorkManager job runs `tick` every 15 minutes. A reminder can then be up to about 15 minutes late, and later still if the system has put the app in a restrictive standby bucket. The card says so and links to the existing battery-exemption request.

**`tick(context, nowMillis)`**

1. In normal mode, re-arm the idle tick first, so a crash later in the tick cannot break the chain.
2. Feature off → cancel alarms and work; stop.
3. Usage access missing → stop (`sync` runs again when the app is opened, which is where the user grants it).
4. Screen not interactive → stop. No query.
5. Read events since the cursor, fold, save the ledger.
6. `decide(...)`:
   - `CheckAt(t)` → in normal mode, replace the idle tick with a hot tick at `t` (at least 60 seconds ahead). In reduced mode nothing: the next periodic run decides.
   - `Remind` → apply the delivery rules of section 5.3; post or postpone.

Elapsed usage always comes from event timestamps, never from counting ticks, so a late or missing tick delays a reminder but cannot distort the measured time.

**`sync(context)`** picks the mode from `PowerManager.isIgnoringBatteryOptimizations`, then arms or cancels according to the settings and the access. The exemption is read by the system when an alarm is set, so `sync` must run again after it is granted or withdrawn — the resume hook below covers that. Called from:

- `ScheduleRefreshCoordinator.syncAll` (boot, app update, time and timezone changes — `BootReceiver` already routes these), inside its own `try/catch` so a failure cannot affect silence or wake scheduling;
- `TunisianPrayerTimesApplication.onCreate`, beside `refreshDhikrReminders()`, on `Dispatchers.IO`;
- the `LaunchedEffect(refreshTick)` in `MainScreen`, which runs on every resume — the moment a newly granted access is noticed;
- every settings change from the UI.

**Safety net.** In normal mode, a unique periodic WorkManager job every 6 hours (`adhkar_usage_nudge_repair`, `ExistingPeriodicWorkPolicy.KEEP`) calls `sync`, mirroring `adhkar_schedule_repair`. In reduced mode the 15-minute job plays that role. Both are cancelled when the feature is off.

**Expected timing, normal mode.** A session is noticed within about 10 minutes of starting, and often at once, because a pending tick is released when the screen turns on. Its crossing time is then known exactly from the event timestamps, so with a threshold of 15 minutes or more the reminder arrives on time. 15 minutes is therefore the smallest threshold offered.

**Cost.** In normal mode, one broadcast every 10 minutes or so of screen-on time, and a rare one while the screen is off. Each tick may cold-start the process, which also runs `Application.onCreate` (including the adhkar `refresh(rearm = true)`). M0 measures this; if it is heavy, the idle tick widens to 15 minutes.

### 5.3 The reminder notification

**Channel.** `adhkar_usage_nudge_v1`, name «تذكير أثناء التصفّح والألعاب», `IMPORTANCE_HIGH`, no sound, vibration `0, 250, 120, 250`, no badge — the recipe of `adhkar_reminders_v2`. It must be a separate channel for two reasons: `DhikrReminderScheduler.refresh()` cancels every notification on the two adhkar channels whose tag is not a live occurrence, and a separate channel lets the user mute this feature alone.

**Content.**

- Title: the app's own words (decision 5).
- Text: the dhikr text exactly as in the catalog, in `BigTextStyle`.
- Tap: opens the dhikr in the reader (section 5.4).
- One action: «إيقاف اليوم».
- `CATEGORY_REMINDER`, auto-cancel, `VISIBILITY_PUBLIC`, small icon `ic_tab_adhkar`, a fresh `when`, `setTimeoutAfter(15 min)`, tag `adhkar_usage_nudge` with id 1. The previous reminder is cancelled before a new one is posted, so each one can show as a banner.

**Which dhikr.** A rotation over a pool of short catalog entries; never the same one twice in a row. Proposed default pool, all existing entries with reviewed sources:

| Id | Text |
|---|---|
| `kalimatan_khafifatan` | سُبْحَانَ اللَّهِ وَبِحَمْدِهِ، سُبْحَانَ اللَّهِ الْعَظِيمِ. |
| `tasbih_arba` | سُبْحَانَ اللَّهِ، وَالْحَمْدُ لِلَّهِ، وَلَا إِلَهَ إِلَّا اللَّهُ، وَاللَّهُ أَكْبَرُ. |
| `istighfar_absolute` | أَسْتَغْفِرُ اللَّهَ وَأَتُوبُ إِلَيْهِ. |
| `la_hawla_quwwata` | لَا حَوْلَ وَلَا قُوَّةَ إِلَّا بِاللَّهِ. |
| `tahlil_ashr` | لَا إِلَهَ إِلَّا اللَّهُ وَحْدَهُ لَا شَرِيكَ لَهُ، لَهُ الْمُلْكُ وَلَهُ الْحَمْدُ، وَهُوَ عَلَى كُلِّ شَيْءٍ قَدِيرٌ. |
| `subhanallah_bihamdih` | سُبْحَانَ اللَّهِ وَبِحَمْدِهِ |
| `dhi_nun` | لَا إِلَهَ إِلَّا أَنْتَ، سُبْحَانَكَ، إِنِّي كُنْتُ مِنَ الظَّالِمِينَ. |

Left to the owner: whether `majlis_kaffara` (said when a gathering ends) belongs in the pool, and whether a short salawat entry should be added to the shared catalog — today the only salawat entry is the long Ibrahimiyya form. The pool is an editorial choice and is recorded with its date in `docs/adhkar-sources.md`. The user can change the selection (any catalog or personal dhikr without steps); entries that no longer exist are skipped.

**Delivery rules, in order.** A reminder that is due is posted unless:

1. `pausedDay` is today, or `countToday` has reached the daily maximum → nothing until tomorrow.
2. Notifications are blocked for the app or this channel → nothing; the card shows the warning.
3. The app has the phone silenced (`SilenceStatus.isAppControlledSilenceActive`) → check again at `appSilenceEndsAt`, or in 15 minutes when the end is unknown. The reminder is shown then only if the session is still going.
4. A scheduled adhkar reminder is showing, or one was posted less than 2 minutes ago (`lastAlert` in the scheduler's prefs) → check again in 10 minutes. The usage reminder records its own time in `lastAlert`, so a scheduled reminder that follows it is spaced by the existing rule.

The user's own Do Not Disturb is left to the system: the notification is posted and the system decides how to show it.

### 5.4 Opening the dhikr from the notification

Today every deep link into the Adhkar reader needs a reminder rule and an occurrence. A reminder for a bare dhikr id needs one more field along the same path:

- `MainTabNavigation.EXTRA_DHIKR_ID` and a `dhikrId` field in `MainTabRequests.Request`, saved and restored with the other two ids (the request must survive "Don't keep activities").
- `MainActivityPendingIntents.adhkarDhikr(context, dhikrId)` with its own data URI (`tunisianprayertimes://adhkar/usage-nudge/<id>`), `EXTRA_DESTINATION = DESTINATION_ADHKAR` and the new extra.
- `MainScreen` passes `requestedDhikrId` to `AdhkarScreen`.
- In `AdhkarScreen`, the request-handling `LaunchedEffect` gains a branch: when `requestedDhikrId` names a known dhikr, open it with `openItems(listOf(id))`, exactly as the library does. When it no longer exists, show a snackbar in the style of the existing ones. The early return that ignores requests with no reminder id and no occurrence id is updated to consider the new id.

The session it opens has no occurrence, so it does not touch the progress of scheduled reminders.

### 5.5 Settings and storage

`UsageNudgeSettings(enabled, consentAtMillis, packages: Set<String>, allGames: Boolean, thresholdMinutes, maxPerDay, dhikrIds: List<String>)`

Stored in its own SharedPreferences file, `adhkar_usage_nudge`, through a small `object UsageNudgePrefs` modelled on `NapPrefs`, with an observer in the style of `PrefsManager.observeLocationSelection` so the Adhkar tab reacts to changes. The ledger lives in the same file.

It is deliberately **not** part of `DhikrState` and **not** a `DhikrReminder`:

- A `DhikrReminder` is a clock rule. `DhikrRepository.save` requires `validate()` to pass, `refresh()` arms window-based alarms for every enabled rule, and every new field counts as a schedule edit. A usage rule fits none of this.
- `state_v2` is one JSON string rewritten with `commit()` under the scheduler's lock on every change. The ledger changes on every tick and must not contend with reminder scheduling.
- The file is excluded from backup (section 6), which must not affect the user's reminders and personal adhkar.

Bounds: threshold 15–120 minutes; daily maximum 1–5.

### 5.6 Which apps

`adhkar/UsageNudgeApps.kt`

- **Curated table** of package names for social and short-video apps (Facebook, Facebook Lite, Instagram, Instagram Lite, Threads, TikTok in its regional and Lite variants, YouTube, Snapchat, X, Reddit, Pinterest, Kwai, Likee, BIGO, Twitch) and, listed but not preselected, messaging apps. Matching is a string comparison against the usage events. Every id is opened once on Google Play before it ships; several were verified during research and the rest are marked in the appendix.
- **Games**: `ApplicationInfo.category == CATEGORY_GAME` or the older `FLAG_IS_GAME`. With «جميع الألعاب» on, a package is checked when it appears in an event (result cached), so newly installed games are covered.
- `CATEGORY_SOCIAL` is **not** used to preselect: by definition it also covers messaging and email. It only sorts the picker.
- **Picker list**: launcher apps through `PackageManager.queryIntentActivities(MAIN/LAUNCHER)`, which needs the `<queries>` element of decision 8. No `QUERY_ALL_PACKAGES`.

The app's own package is never counted.

### 5.7 Permission, disclosure and consent

The closest existing pattern is the Adhkar tab's exact-alarm flow: an in-app explanation first, the system page second, and a re-check on `ON_RESUME`. It is **not** added to onboarding (that step is mandatory and has no skip) and **not** to the Today tab's permission banner (which means "the app is broken without this").

1. The user switches the card on.
2. **Disclosure dialog** (draft, to settle in the mockups):
   - Title: «معرفة التطبيق المفتوح ومدة استخدامه»
   - Body: «ليذكّرك التطبيق بعد طول التصفّح، يحتاج إلى معرفة التطبيق المفتوح على الشاشة ومدة استخدامه. يقرأ ذلك من نظام الهاتف، ويستعمله للتذكير بالذكر فقط. تبقى هذه المعلومات على هاتفك ولا تُرسل إلى أي جهة.»
   - Buttons: «موافق، فتح الإعدادات» and «لا، شكرًا». Back and tapping outside count as "no".
3. On acceptance: store `consentAtMillis`, set `enabled`, open `Settings.ACTION_USAGE_ACCESS_SETTINGS` — with a `package:` URI on Android 10+, falling back to the plain action, then to the app's details page (the shape of `openDhikrExactAlarmSettings`).
4. On `ON_RESUME`: re-read `usageAccessGranted(context)` (`AppOpsManager` with `OPSTR_GET_USAGE_STATS`; `MODE_DEFAULT` falls back to `checkSelfPermission`). Granted → continue with the notification prompt for the new channel (the existing dialog, pointed at this channel), then the app list for confirmation. Not granted → the card shows a notice with «فتح الإعدادات».
5. **Restricted setting (GitHub builds, Android 15+).** If the access is still missing after the user comes back, on Android 15 or later the notice adds the steps, in the order the system requires: (1) on the Usage access page, tap this app's greyed-out line so the system's "restricted setting" message appears; (2) open the app's info page, then the three-dot menu, then "Allow restricted settings", and confirm with the screen lock; (3) return to Usage access and switch it on. The menu item only exists after step 1. A button opens the app's info page (`ACTION_APPLICATION_DETAILS_SETTINGS`); the menu itself cannot be opened for the user. The Arabic wording is written against the labels a real Android 15 or 16 phone shows.
6. Every flag of this flow is `rememberSaveable`: a settings page re-creates the activity on the test phone. The new dialog must not appear on the same resume as the notification or exact-alarm dialogs (existing guard: one dialog at a time).

Access can be revoked at any time and is not restored by a backup, so every tick re-checks it and the feature goes quiet without it.

### 5.8 UI

Per the working agreement, **mockups come first** (M2) and are approved before any Compose is written. The mockups cover: the card in its three states (off, on, needs access), the disclosure dialog, the settings sheet, the app picker, the dhikr selection, and the notification as it appears over an app.

Proposed structure:

- **Card** on the Adhkar Today page, after the reminders list, and the same card in the «تذكيراتي» sheet after the saved reminders (placing it after `items(listed)` avoids the sheet's scroll-position arithmetic). It is its own composable: `HomeReminderRow` and `AdhkarReminderSlot` are typed around `DhikrReminder`. It shows the name, a one-line summary («بعد 30 دقيقة متواصلة · حتى 3 إشعارات يوميًا»), an `AdhkarSwitch`, and when relevant a notice: access missing, notifications blocked, battery optimisation on (reminders may come late), or «متوقف حتى الغد».
- **Settings sheet** (new file, the scaffold of `DhikrReminderEditor`): apps; duration before the reminder (stepper with quick picks, no keyboard); daily maximum; adhkar; notification settings and preview; a short "what is not counted" note (browser use, sections inside an app such as Shorts, work profile); the privacy sentence.
- **App picker**: search, «جميع الألعاب», then apps with icon and name, multi-select.
- **Dhikr selection**: multi-select over catalog and personal adhkar, with the note «يتنوّع الذكر في كل مرة».

Conventions to keep: Arabic literals in Kotlin as in the rest of the Adhkar UI; «إشعار» for a notification and «تذكير» for the rule; Latin digits through `latinNumber`; everything inside `AdhkarTheme`; each sheet has its own `SnackbarHost`.

Controls reused: `AdhkarCard`, `AdhkarSwitch`, `AdhkarSectionHeader`, `DhikrSheetHeader`, `rememberSheetScrollGuard`. `SettingRow`, `ToggleRow` and `StepButton` are private to `AdhkarReminderEditor.kt` today; they become `internal` (a visibility change only, no behaviour change in the editor).

## 6. Privacy, policy and store

| Item | Change |
|---|---|
| Backup | Exclude `adhkar_usage_nudge.xml` (`domain="sharedpref"`) in `res/xml/backup_rules.xml` and in both sections of `data_extraction_rules.xml`. Today every prefs file is backed up, which would contradict "stays on the phone". |
| Analytics | Nothing about app names, categories, minutes or counts, ever. Analytics cannot be switched off by the user today, so the rule is strict (decision 7). |
| Logs | No package names in `Log` calls in release builds. |
| Privacy policy (`docs/privacy-policy.html`) | New date; add usage access to the permissions list and state that it is optional (the list says every permission is "required for core functionality"); describe what is read, that it is processed on the device, not stored as a history and not backed up; add "names of other apps and usage times" to what analytics never includes; mention the chosen-apps list among stored settings. |
| `README.md` privacy section | Rewrite: it says the app collects no analytics, which is already untrue. |
| `docs/adhkar-sources.md` | A dated editorial note for the reminder pool, and a line stating that durations, app lists and daily limits are personal settings. |
| Play listing | Describe the feature in the description: sensitive accesses must serve a feature promoted in the listing. |
| Data safety form | Review; no new entry is expected because nothing leaves the device. |
| Manifest | `PACKAGE_USAGE_STATS` with `tools:ignore="ProtectedPermissions"` and a one-line comment like its neighbours; the `<queries>` element; the receiver. |

The Play Console steps are manual and are the owner's to do; the repo has no checklist for them, so they are listed in M5.

## 7. Files

**New**

| File | Content |
|---|---|
| `adhkar/UsageNudgeModels.kt` | Settings, ledger, `UsageSample`, the fold, `decide`. No Android imports. |
| `adhkar/UsageNudgePrefs.kt` | Settings and ledger persistence, observer. |
| `adhkar/UsageAccess.kt` | `usageAccessGranted`, `openUsageAccessSettings`, `UsageEventsReader`. |
| `adhkar/UsageNudgeApps.kt` | Curated table, game detection, launcher app list. |
| `adhkar/UsageNudgeMonitor.kt` | `sync`, `tick`, alarms, periodic work, channel, posting. |
| `adhkar/UsageNudgeReceiver.kt` | Tick and «إيقاف اليوم» broadcasts (`goAsync` + `Dispatchers.IO`, as `DhikrReminderReceiver`). |
| `adhkar/UsageNudgeWorker.kt` | Calls `sync` (normal mode, every 6 hours) or `tick` (reduced mode, every 15 minutes). |
| `ui/AdhkarUsageNudge.kt` | Card, disclosure dialog, settings sheet, pickers. |
| Tests | `adhkar/UsageNudgeLogicTest.kt` (plain JUnit), `adhkar/UsageNudgeMonitorTest.kt` (Robolectric). |

**Changed**

| File | Change |
|---|---|
| `AndroidManifest.xml` | Permission, `<queries>`, receiver. |
| `res/xml/backup_rules.xml`, `data_extraction_rules.xml` | Exclusion. |
| `ScheduleRefreshCoordinator.kt` | `UsageNudgeMonitor.sync` in `syncAll`, isolated. |
| `TunisianPrayerTimesApplication.kt` | `sync` at process start. |
| `MainActivity.kt` | `EXTRA_DHIKR_ID`, `Request.dhikrId`, saved state. |
| `MainActivityPendingIntents.kt` | `adhkarDhikr(...)`. |
| `ui/MainScreen.kt` | Pass `requestedDhikrId`; `sync` in the resume effect. |
| `ui/AdhkarScreen.kt` | Deep-link branch; card in the Today page and the sheet; access re-check on resume; prompt chain. |
| `ui/AdhkarReminderEditor.kt` | `SettingRow`, `ToggleRow`, `StepButton` become `internal`. |
| `adhkar/DhikrReminderScheduler.kt` | Internal accessors for `lastAlert`; `notificationsEnabled` accepts a channel id (the current signature stays as a wrapper). |
| `AnalyticsTracker.kt` call sites | `permissionStepResult(..., "usage_access", ...)` only. |
| Docs | As in section 6. |

## 8. Milestones

Each milestone ends in something that can be checked on its own. Work stays uncommitted on `codex` for the owner to commit.

**M0 — Device spike (throwaway debug code, not kept).** The documentation and AOSP answer most questions; this checks them on the OnePlus (whose maker is among the most aggressive at stopping background apps) and on emulators:

1. Tick cadence really achieved over an hour of use with the battery exemption, read from `dumpsys alarm`; how soon a pending tick fires after screen-on; what one tick costs when the process is cold.
2. The same hour in reduced mode (exemption withdrawn): how often the 15-minute job really runs.
3. Events of the app in front are returned at once by `queryEvents`.
4. The banner over TikTok, YouTube and a game, with and without the phone's game mode.
5. `ApplicationInfo.category` of the installed social apps and games; the `<queries>` launcher intent lists every launcher app.
6. A `package:` URI opens the app's own Usage access switch. On an Android 15+ device, an APK installed from the Files app shows the restricted-setting flow of section 5.7, with the labels to quote in the help text.
7. On Android 8.1 and 9 emulators: a background event is logged when the screen turns off with an app in front.

Outcome: the cadence constants are confirmed or adjusted, and the help text of section 5.7 matches a real phone.

**M1 — Logic.** `UsageNudgeModels.kt` and its JUnit tests. No UI, no manifest change.

**M2 — Mockups.** HTML mockups of everything in section 5.8, with the Arabic copy. Owner's approval, including decisions 2–6.

**M3 — Plumbing.** Prefs, reader, monitor, receiver, worker, channel, notification, deep link, manifest, backup rules, hooks in `syncAll` / `Application` / `MainScreen`. Testable with adb: grant the access from the shell, set the threshold low in a debug build, watch the reminder arrive.

**M4 — UI.** Card, disclosure, settings sheet, pickers, prompt chain, notices.

**M5 — Privacy and store.** Policy, README, sources doc; the Play listing text and Data safety review by the owner before the release that carries the feature.

**M6 — Validation.** The device checklist of section 9, on the OnePlus, then tuning of the 3-minute break rule and the tick cadence.

## 9. Tests and validation

**Plain JUnit (`UsageNudgeLogicTest`)** — the main safety net, since it needs no Robolectric:

- one app in front for longer than the threshold; exactly at the threshold; just below;
- moving between two chosen apps; a short visit to another app; a break longer than 3 minutes;
- screen off and on inside and outside the break limit;
- activity changes inside one app (pause and resume within 2 seconds);
- split screen with one chosen and one other app;
- an open span with the screen off at "now";
- reboot markers; unknown event types; an empty and a `null` read;
- a cursor older than 12 hours; the wall clock set back;
- second reminder only after a further full threshold; daily maximum; «إيقاف اليوم»; day change at midnight;
- rotation never repeats the previous dhikr; a deleted dhikr is skipped;
- Android 8 event set (types 1 and 2 only).

**Robolectric (`UsageNudgeMonitorTest`)**, in the style of `AdhkarFlowTest` and `SilenceVerifyWorkerTest`: alarm armed and cancelled by `sync`; normal and reduced mode; idle and hot ticks; nothing posted without access or with notifications blocked; postponement under app silence and after a recent adhkar alert; channel, tag, action title and deep-link extras of the posted notification; the periodic work is unique. Events are fed with `ShadowUsageStatsManager.addEvent(...)`. Two traps: the shadow returns its events whatever the access state, and Robolectric reports the app-op as allowed by default, so the "no access" cases set the mode explicitly with `ShadowAppOpsManager.setMode(...)` and assert through `usageAccessGranted`. Whether the Robolectric suite currently runs with the bundled Quran assets is checked first; if it does not, these tests are written but the gate is the JUnit suite plus the device checklist.

**From the shell** (always with `-s <serial>`; the debug build's id ends in `.dev`):

```bash
adb -s <serial> shell appops set com.tunisianprayertimes.dev GET_USAGE_STATS allow
```

```bash
adb -s <serial> shell appops set com.tunisianprayertimes.dev GET_USAGE_STATS default
```

```bash
adb -s <serial> shell dumpsys usagestats com.zhiliaoapp.musically
```

The first grants the access without the settings page, the second returns it to the fresh-install state, and the third prints the events of the last 24 hours that the system recorded for a package.

**Existing tests to extend**: `MainTabRequestsTest` (the new id is accepted, saved and restored); `AdhkarFlowTest` must stay green, in particular the 2-minute spacing cases.

**Device checklist (OnePlus, "Don't keep activities" on)**

1. Enable: disclosure → settings page → back; the activity is re-created and the flow continues.
2. Decline at each step; revoke the access later from system settings: the card shows the notice, nothing crashes, nothing is posted.
3. 30 minutes in a chosen app → banner with a dhikr; tap → reader on that dhikr; «إيقاف اليوم» → nothing more that day.
4. Alternate between two chosen apps; take a 2-minute break; take a 5-minute break.
5. During prayer auto-silence: no reminder; after it ends with the session still going: reminder.
6. A scheduled adhkar reminder at the same moment: no stacking.
7. Reboot, then use a chosen app without opening this app: the reminder still arrives.
8. Battery use of the app over a day with the feature on.

## 10. Known limits

Stated in the settings sheet where the user needs them:

- Use inside a browser is not counted, and sections inside an app (Shorts, Reels) cannot be separated from the app.
- Apps in a work profile or private space are not seen.
- In games, a manufacturer's game mode or Do Not Disturb may hide the banner; the reminder then waits in the notification shade.
- The reminder is approximate: it can be some minutes late, and a quarter of an hour or more when the app is not exempt from battery optimisation.
- After the app is force-stopped, nothing runs until it is opened again.
- A GitHub build on Android 15 or later needs the extra "allow restricted settings" step before the access can be switched on.

## 11. Later, if wanted

- **Daily total**: one extra reminder when the day's total in the chosen apps passes a limit. The ledger can accumulate it at no extra cost.
- **Stronger mode**: a small card drawn over the app instead of a banner (`SYSTEM_ALERT_WINDOW`). A separate decision: its own permission, a clumsy grant page on Android 11+, and a Play policy check first.
- **Per-app durations**, or separate durations for social apps and games.
- **A prayer-time variant**: a reminder when a chosen app is in front as the adhan time arrives.

## Appendix: sources and verification notes

- Android: `UsageStatsManager`, `UsageEvents.Event`, `AppOpsManager`, `AlarmManager`, notification channels and heads-up behaviour, package visibility, foreground-service types and background-start limits (developer.android.com and AOSP source).
- Google Play: User Data policy (prominent disclosure and consent), Data safety form help, Package visibility policy, Accessibility API policy, Foreground service declaration, policy announcements of April and July 2026.
- Prior art: Play listings of «اذكاري», Nafs, Dhikr Lock, Quran Screen, one sec, ScreenZen; Digital Wellbeing help.
- Evidence: Grüning, Riedel and Lorenz-Spreen, PNAS 2023 (one sec field study: response to an identical prompt fell from 43% to about 33% in three weeks, then held; a message alone was not effective, an easy alternative action was); Kovacs, Wu and Bernstein, CSCW 2018 (rotating interventions stay effective longer; explaining the rotation halves the extra attrition).
- No source gives an evidence-based threshold in minutes: the defaults in decision 2 are a design judgement.
- Package ids confirmed on Google Play during research: `com.facebook.katana`, `com.facebook.orca`, `com.instagram.android`, `com.instagram.barcelona`, `com.zhiliaoapp.musically`, `com.tiktok.lite.go`, `com.ss.android.ugc.tiktok.lite`, `com.kwai.video`, `kwai.lite.video`, `sg.bigo.live`, `sg.bigo.live.lite`, `com.supercell.clashofclans`, `com.supercell.clashroyale`, `com.supercell.brawlstars`, `com.ludo.king`, `com.mobile.legends`. All others are to be opened once before the table ships.
- Texts: the candidate verse of decision 5 is الرعد 29 in the app's Qaloon index (28 in the Hafs count). The pool reuses catalog entries unchanged, so it adds no new sourcing.
