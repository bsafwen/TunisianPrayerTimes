# Alarm User Experience Review

Date: 2026-05-27

This review covers the Android wake alarm experience in Tunisian Prayer Times: first-run education, the Alarms tab, alarm creation, the full-screen editor, scheduling reliability, ringing behavior, dismissal, wake-up checks, awake checks, and the current test surface.

## Executive Summary

The alarm feature is technically strong and already has the bones of a serious alarm product: prayer-relative alarms, fixed-time alarms, one-off timers, sub-alarms, custom sounds, progressive volume, wake-up challenges, post-dismiss awake checks, full-screen ringing, queued overlapping alarms, exact-alarm scheduling, and app-update/reboot recovery.

The main UX risk is that the feature exposes a lot of power before it establishes user trust. A user needs to know three things immediately: will this alarm ring, why or why not, and what will happen when I try to stop it? Today those answers are spread across permission side effects, hidden scheduler state, overlap warnings, runtime suppression logic, and advanced editor sections.

The highest-value improvements are:

1. Add an alarm readiness panel in the Alarms tab that shows exact alarm, notification, full-screen, battery, and silence-conflict status.
2. Make prayer-silence conflicts explicit and user-controllable instead of allowing runtime suppression to feel like a missed alarm.
3. Simplify the add/edit flow with progressive disclosure: schedule first, essentials second, advanced behavior collapsed.
4. Clarify the difference between a stop challenge and the later awake follow-up.
5. Add an emergency path for wake-up challenges so users are never trapped by a sensor issue, accessibility limitation, or overly hard challenge.
6. Improve the ringing screen with stronger context, snooze, queued-alarm awareness, and clearer stop states.
7. Expand UX tests around permissions, editor flows, lock-screen behavior, notification denial, challenge fallback, and one-off expiration.

## Current Experience

### Onboarding

The onboarding has a wake alarm intro step that explains the feature with text and an alarm illustration. It introduces prayer-relative alarms, fixed-time alarms, and from-now alarms.

Observed strengths:

- It gives a concrete Fajr example.
- It sets the right conceptual frame: these are wake alarms connected to prayer times, not only generic alarms.

Observed friction:

- It is passive. The user cannot create or preview an alarm from onboarding.
- It does not explain the permissions that make alarms trustworthy: exact alarms, notifications, full-screen display, and battery behavior.
- The screenshot has a lot of empty vertical space, so the alarm value proposition can feel small compared with the screen.

Relevant files:

- `android-app/app/src/main/java/com/tunisianprayertimes/ui/OnboardingScreen.kt`
- `android-app/app/src/main/res/values/strings.xml`
- `android-app/screenshots/phone-onboarding-5-wake-alarm.png`

### Alarms Tab

The main app has a dedicated Alarms tab. Empty state users see three quick presets:

- Prayer-relative alarm.
- Fixed-time alarm.
- One-off timer.

Once alarms exist, the tab shows:

- A next-alarm hero panel.
- An awake-check banner when the awake-check service is running.
- A list of alarm cards with enable switch, delete action, next trigger, and feature chips.
- A floating add button when the list is not empty.

Observed strengths:

- The empty state gives clear starting points instead of dropping users into a blank editor.
- The next-alarm hero is a strong trust anchor.
- Cards show useful feature chips for wake-up challenge, vibration-only, progressive volume, and sub-alarm count.
- The main add path avoids overcrowding prayer rows.

Observed friction:

- Alarm readiness is not visible. The tab does not clearly say whether exact alarm permission, notification permission, full-screen permission, or battery optimization status will affect the next alarm.
- Notification and full-screen permission prompts are reactive after saving or visiting the tab with a future alarm. That can feel like an interruption rather than a guided setup step.
- Exact alarm missing is handled through the general main permission banner, not through an alarm-specific blocked state.
- A stale one-off alarm can remain in storage until a scheduling sync deletes it, which can create a confusing empty-state/list combination.
- The enable switch lives only in the list. The editor itself does not expose enable/disable as a first-class control.

Relevant files:

- `android-app/app/src/main/java/com/tunisianprayertimes/ui/MainScreen.kt`
- `android-app/app/src/main/java/com/tunisianprayertimes/wake/WakeAlarmScheduler.kt`
- `android-app/app/src/main/java/com/tunisianprayertimes/ScheduleRefreshCoordinator.kt`

### Alarm Creation And Editing

The add flow creates a default enabled alarm and opens a full-screen editor. Defaults are sensible:

- Prayer-relative preset: Fajr, 20 minutes before prayer.
- Fixed-time preset: next rounded time roughly 30 minutes from now.
- Timer preset: 15 minutes from now.
- Default ringtone: bundled Adhan Madinah Marwan Qassas.

The editor includes:

- Preview of the next trigger.
- Main schedule section.
- Mode picker for prayer-relative, fixed-time, or from-now.
- Sound controls and ringtone picker.
- Vibration-only and progressive volume controls.
- Wake-up challenge controls with math, whack-a-mole, and gyroscope maze.
- Awake-check toggle.
- Sub-alarm editor with per-sub-alarm sound customization.
- Silence-until-alarm option for from-now alarms.
- Prayer-silence overlap warning.

Observed strengths:

- The preview makes the configured time concrete before saving.
- The overlap warning is valuable because prayer auto-silence can affect alarm behavior.
- The editor supports both simple and advanced alarm needs.
- Sub-alarm sound customization is powerful.

Observed friction:

- The editor is dense. A new user sees schedule, playback, wake-up check, awake check, sub-alarms, and delete/cancel/save all in one pass.
- Advanced features are presented as peers to the core schedule, which increases cognitive load.
- The naming of wake-up check and awake check is easy to confuse.
- Awake check defaults to enabled in the shared model, so a user may stop an alarm and later be surprised by a follow-up prompt, vibration, or ringtone.
- There is no custom alarm label in the UI even though the model has a `title` field. Fixed-time and timer alarms therefore feel generic in the list.
- There is no enable switch inside the editor, even though the preview can show a disabled state.
- Some UI still uses text/emoji-style symbols for core actions instead of consistent icon buttons.

Relevant files:

- `android-app/app/src/main/java/com/tunisianprayertimes/ui/WakeEditorSheet.kt`
- `multiplatform/shared/src/commonMain/kotlin/com/tunisianprayertimes/WakeModels.kt`

### Ringing, Dismissal, And Follow-Up

When a wake alarm fires, the receiver logs analytics, may release silence-until-alarm, starts foreground playback, shows a notification with a full-screen intent, and launches `WakeAlertActivity`.

The full-screen alert shows:

- Alarm title.
- Alarm time.
- Content text.
- Optional wake-up challenge.
- Disabled stop button until challenge steps complete.

The runtime also supports:

- Removing notification stop action when wake-up check is enabled.
- Queueing overlapping alarms behind an active challenge.
- Recording dismissal analytics.
- Scheduling an awake check after dismissal when enabled.

Observed strengths:

- The app avoids a common alarm bug: notification action cannot bypass a configured wake-up check.
- Queueing overlapping alarms is safer than letting concurrent alarm screens fight each other.
- The full-screen experience is straightforward and hard to miss.
- The awake check creates a second safety net after dismissal.

Observed friction:

- There is no snooze. Alarm apps train users to expect a controlled snooze option, especially for wake-up use cases.
- If a wake-up challenge is too hard or unavailable, the user has no explicit emergency path.
- The full-screen UI does not clearly distinguish main alarm vs sub-alarm, nor does it show how many queued alarms are waiting.
- Challenge games can be difficult on lock screen, low dexterity, broken sensors, or accessibility services.
- Awake check starts as a quiet notification, then vibrates, then rings. That is useful, but it needs clearer setup copy and runtime context because it can feel like a second unexpected alarm.

Relevant files:

- `android-app/app/src/main/java/com/tunisianprayertimes/wake/WakeAlarmReceiver.kt`
- `android-app/app/src/main/java/com/tunisianprayertimes/wake/WakePlaybackService.kt`
- `android-app/app/src/main/java/com/tunisianprayertimes/wake/WakeAlertActivity.kt`
- `android-app/app/src/main/java/com/tunisianprayertimes/wake/WakeDismissalCoordinator.kt`
- `android-app/app/src/main/java/com/tunisianprayertimes/wake/AwakeCheckService.kt`

### Reliability Model

The implementation uses exact alarms, `setAlarmClock` for main wake triggers, exact idle alarms for sub-alarms, a repair alarm after the last trigger or at midnight, boot/app-update/time-change rescheduling, and dedicated persistence in DataStore.

Observed strengths:

- `setAlarmClock` for main alarms is the right mental model for user-visible wake alarms.
- Repair scheduling helps one-off and cross-midnight behavior self-heal.
- DataStore persistence is a good fit for structured alarm configs.
- Legacy persistence migration is tested.

Observed friction:

- `WakeAlarmScheduler.schedulingSnapshot` can detect missing exact-alarm permission and unscheduled state, but the Alarms tab does not turn that into a visible user status.
- Runtime suppression during app-controlled silence can make an alarm appear saved and scheduled while it does not ring.
- Periodic wake verify work is retained only to drain old work. That is reasonable technically, but the user-facing reliability story should depend on visible readiness and repair state, not hidden workers.

Relevant files:

- `android-app/app/src/main/java/com/tunisianprayertimes/wake/WakeAlarmScheduler.kt`
- `android-app/app/src/main/java/com/tunisianprayertimes/wake/WakeAlarmRepairReceiver.kt`
- `android-app/app/src/main/java/com/tunisianprayertimes/wake/WakeAlarmVerifyWorker.kt`
- `android-app/app/src/main/java/com/tunisianprayertimes/wake/PrayerWakeRepository.kt`
- `android-app/app/src/main/java/com/tunisianprayertimes/wake/PrayerWakePersistence.kt`

## Priority Recommendations

### P0: Add An Alarm Readiness Panel

Problem:

Users cannot tell whether the next alarm is actually ready to ring. The app tracks exact alarm, notification, full-screen, battery, and scheduled-event state, but the Alarms tab does not present those as an alarm health status.

Recommendation:

Add a compact panel near the top of the Alarms tab, above or below the next-alarm hero.

Suggested states:

- Ready: exact alarms granted, at least one future alarm scheduled, notification path available, full-screen allowed or not required.
- Needs action: exact alarm permission missing.
- Needs action: notifications denied on Android 13+.
- Recommended: full-screen permission missing on Android 14+.
- Recommended: battery optimization not exempt.
- Warning: next alarm overlaps prayer auto-silence.
- Warning: alarm saved but no future trigger can be computed.

User actions:

- Open exact-alarm settings.
- Request notification permission.
- Open full-screen alarm settings.
- Open battery optimization settings.
- Open affected alarm editor.

Implementation notes:

- Reuse `WakeAlarmScheduler.schedulingSnapshot` and extend it with user-facing issue objects.
- Keep DND and phone-state permission out of the default alarm readiness panel unless the alarm is configured with silence-until-alarm or overlaps app-controlled silence.
- The Today tab can keep its general permission banner, but the Alarms tab needs alarm-specific language.

Acceptance criteria:

- A user with a saved enabled alarm and missing exact-alarm permission sees a clear blocked state before the alarm time.
- A user with notifications denied sees a clear warning and action.
- A user on Android 14+ with full-screen intent disabled sees a clear lock-screen warning.
- The next-alarm hero does not imply readiness when scheduling is blocked.

### P0: Make Silence Conflicts Explicit And User-Controlled

Problem:

The receiver suppresses wake playback when app-controlled silence is active. The editor has an overlap warning, but the runtime outcome can still feel like a missed alarm.

Recommendation:

Turn silence conflict into an explicit alarm setting and status.

Suggested policy options:

- Ring anyway: wake alarms override app-controlled prayer silence.
- Respect prayer silence: wake alarms do not ring during app-controlled silence.
- Ask per conflict: show a fix action when a conflict is detected.

Recommended default:

- Ring anyway for wake alarms, especially prayer-relative Fajr alarms, unless the user explicitly chooses to respect silence.

If product policy must keep suppression:

- Show a persistent warning on the affected alarm card.
- Include the suppressed alarm in missed-alarm history.
- Notify after the silence window that an alarm was skipped because prayer silence was active.

Implementation notes:

- Promote the overlap warning from editor-only to list/hero status.
- Add a per-alarm `respectPrayerSilence` or `ringDuringPrayerSilence` property if policy needs to vary.
- If suppression remains, `WakeAlarmReceiver` should produce a user-visible event, not only a log.

Acceptance criteria:

- A configured alarm cannot silently fail because of app-controlled silence without the user seeing that risk beforehand.
- Alarm cards show conflict status without requiring the user to open the editor.
- Analytics distinguish rang, suppressed_by_prayer_silence, dismissed, and missed.

### P0: Add A Challenge Escape Hatch

Problem:

When wake-up check is enabled, the stop button is disabled until the challenge is complete and notification stop is removed. That protects against sleepy dismissal, but it can trap users if a sensor fails, a game is inaccessible, the phone is on a table, or the challenge is too hard.

Recommendation:

Add an emergency path that is hard enough to prevent accidental use but possible enough to keep the app humane.

Options:

- Long-press emergency stop for 5 seconds after one failed minute.
- Secondary fallback to math if gyroscope is unavailable or no motion events arrive.
- Require device unlock for emergency stop.
- Require a typed phrase after a delay.

Implementation notes:

- Detect no gyroscope or unusable sensor state before showing gyroscope maze.
- Provide fallback challenge selection in `WakeAlertActivity`.
- Track `challenge_emergency_stop_used` analytics.

Acceptance criteria:

- A user can stop an alarm even if a sensor challenge cannot run.
- The emergency path is not visible as the primary action at first glance.
- Accessibility users are not blocked from stopping an alarm.

### P1: Simplify The Editor With Progressive Disclosure

Problem:

The editor is comprehensive but dense. It asks new users to process schedule, playback, wake-up challenge, awake check, sub-alarms, and deletion on one screen.

Recommendation:

Restructure the editor around three layers:

1. Essential: enabled switch, next trigger preview, schedule type, prayer/time/duration, save.
2. Behavior: sound, vibration, progressive volume, stop challenge, awake follow-up.
3. Advanced: sub-alarms, per-sub-alarm sound, silence-until-alarm, conflict policy.

Specific changes:

- Keep the schedule section open by default.
- Collapse sub-alarms by default when none exist.
- Collapse stop challenge details until enabled.
- Collapse awake follow-up details until enabled.
- Move delete into an overflow or bottom danger zone for persisted alarms only.
- Add one primary save affordance. Avoid top and bottom save buttons unless the top one becomes a sticky app bar action.

Acceptance criteria:

- A first-time user can create a prayer-relative Fajr alarm without scrolling through advanced controls.
- Existing advanced users can still reach every current option.
- Editor preview remains visible near the top.

### P1: Rename Stop Challenge And Awake Follow-Up

Problem:

The feature has two similarly named concepts:

- Wake-up check: challenge required before stopping the alarm.
- Awake check: follow-up after stopping the alarm.

Those are meaningfully different but easy to confuse.

Recommendation:

Use clearer product names:

- Stop challenge: the thing required to stop the alarm.
- Awake follow-up: the later check that confirms the user stayed awake.

Recommended copy model:

- Stop challenge: "Require a challenge before stopping."
- Awake follow-up: "Ask me again after X minutes. If I do not confirm, vibrate then ring."

Default recommendation:

- Keep progressive volume on by default.
- Consider defaulting awake follow-up off, or keep it on only if it is clearly shown in the confirmation summary before saving.
- Keep stop challenge off by default unless selected by a preset.

Acceptance criteria:

- The saved-alarm summary tells users whether stopping requires a challenge.
- The saved-alarm summary tells users whether a later follow-up will happen.
- No user can enable an awake follow-up without seeing the delay and escalation behavior.

### P1: Add Enable, Label, And Summary Controls Inside The Editor

Problem:

The editor can show disabled preview state, but it does not provide a visible enabled switch. The model has a `title`, but the UI does not expose it. Generic list titles make fixed-time and timer alarms harder to recognize.

Recommendation:

- Add an enabled switch in the editor header.
- Add an optional label field, with smart defaults:
  - "Before Fajr"
  - "Daily 06:30"
  - "Timer 15 min"
- Show a final summary before save for new alarms:
  - When it rings.
  - Whether it repeats.
  - Whether sound/vibration plays.
  - Whether a stop challenge is required.
  - Whether an awake follow-up is enabled.
  - Whether it can ring on the lock screen.

Acceptance criteria:

- A disabled alarm can be re-enabled from its editor.
- A user can tell two fixed-time alarms apart in the list.
- New alarm save confirms the most important behavior.

### P1: Improve The Ringing Screen

Problem:

The full-screen alarm is functional but sparse. During a real wake-up moment, users need context and a safe action set.

Recommendation:

Improve the ringing screen with:

- Clear alarm label.
- Main alarm vs sub-alarm indicator.
- Prayer context when relevant: "20 min before Fajr" or "Fajr at 04:10".
- Snooze action with bounded options, such as 5 minutes and "until prayer" when prayer-relative.
- Queued alarm count when another alarm is waiting.
- Visible reason stop is disabled when a challenge is required.
- Clear completion transition after challenge steps.

Snooze rules:

- Snooze should not bypass a required stop challenge if the user selected strict mode.
- A separate setting can decide whether snooze is allowed before completing a challenge.
- Snooze should be bounded so it cannot fire after the target prayer unless the user explicitly allows that.

Acceptance criteria:

- A sleepy user can understand what alarm is ringing without reading detailed body text.
- A user with multiple alarms understands whether another alarm is queued.
- Snooze behavior is predictable and visible in analytics.

### P1: Clean Up One-Off Timer Lifecycle

Problem:

From-now alarms are one-off and are auto-deleted after their triggers are in the past during scheduling sync. Until that sync happens, the list can contain an expired alarm with no next trigger.

Recommendation:

- Remove expired one-off alarms immediately after dismissal if all triggers are complete.
- Show an "expired" state only if deletion fails or the user needs history.
- If history is useful, separate active alarms from recent alarm history.

Acceptance criteria:

- The empty state and list do not appear together because of stale one-off alarms.
- Dismissing a one-off alarm cleans up the list predictably.

### P2: Make Onboarding Action-Oriented

Problem:

The onboarding currently describes alarms but does not connect that explanation to a first successful setup.

Recommendation:

- Replace passive explanation with a small preview of the recommended first alarm: "Fajr, 30 min before" or the current default.
- Add a "Set this later" option rather than forcing setup.
- Explain alarm-critical permissions in the permissions step with outcome-based language.
- Link the onboarding completion to the Alarms tab when the user showed interest in wake alarms.

Acceptance criteria:

- A new user understands what to do after onboarding to create the first alarm.
- Permission asks feel connected to a concrete alarm outcome.

### P2: Present Sub-Alarms As A Timeline

Problem:

Sub-alarms are powerful but abstract. The current editor describes before/after and minutes, but it does not show the full sequence visually.

Recommendation:

Add a compact timeline preview:

- Sub-alarm 1: 10 min before.
- Main alarm: Fajr wake alarm.
- Sub-alarm 2: 5 min after.

This is especially useful when sub-alarms have different sounds or when the earliest next trigger is a sub-alarm rather than the main alarm.

Acceptance criteria:

- Users can see whether a sub-alarm happens before or after the main alarm without mental math.
- The next-alarm hero explains when the next trigger is a sub-alarm.

### P2: Add A Safe Test Alarm Mode

Problem:

Users need confidence that sound, vibration, full-screen display, and challenges work before relying on a wake alarm.

Recommendation:

Add a controlled test mode from the editor and readiness panel:

- Starts a low-stakes alarm after 10 seconds.
- Uses the selected sound and vibration settings.
- Exercises full-screen behavior when allowed.
- Shows the selected challenge.
- Does not persist as a real future alarm.

Acceptance criteria:

- A user can test alarm behavior without waiting until the real wake time.
- Test alarms are clearly labeled in analytics and do not pollute alarm history.

### P2: Improve Analytics For UX Decisions

Current analytics already track saved, fired, dismissed, wake-up check type, and schedule refresh results. Add product-facing events that answer UX questions:

- Alarm readiness issue shown.
- Readiness issue resolved.
- Alarm suppressed by app-controlled silence.
- Stop challenge started, completed, failed, emergency-stopped.
- Snooze used.
- Awake follow-up confirmed, ignored, escalated to vibration, escalated to ringtone.
- Notification permission denied after alarm save.
- Full-screen permission denied or unavailable.
- One-off alarm expired and auto-deleted.

Use these to decide whether defaults are working, especially awake follow-up and challenge difficulty.

## Proposed Target UX

### Empty Alarms Tab

Top area:

- Alarm readiness panel if anything needs attention, otherwise a compact "Ready to ring" status.
- Empty state title and a single primary add path.

Preset actions:

- Before prayer: defaults to Fajr, 20 or 30 minutes before.
- Fixed daily time.
- Timer from now.

After preset selection:

- Open the editor with only essentials expanded.
- Show the concrete next trigger immediately.

### Alarm Card

Each card should show:

- User label or smart generated label.
- Next trigger time.
- Recurrence type: daily, prayer-relative, one-off.
- Prayer context when relevant.
- Readiness/conflict state.
- Feature chips for sound/vibration, stop challenge, awake follow-up, sub-alarms.
- Enable switch.
- Edit tap target.
- Delete in overflow or a confirmation action.

### Editor

Header:

- Back/close.
- Alarm label.
- Enabled switch.
- Save.

Always visible:

- Next trigger preview.
- Schedule section.

Collapsed by default:

- Sound and vibration.
- Stop challenge.
- Awake follow-up.
- Sub-alarms.
- Advanced conflict policy.

Footer:

- Save for new or changed alarms.
- Delete only for persisted alarms.

### Ringing Screen

Always visible:

- Alarm label.
- Time.
- Main/sub-alarm status.
- Prayer context.
- Stop or challenge state.

Actions:

- Stop when allowed.
- Snooze when allowed.
- Emergency path after a delay when challenge is blocked or inaccessible.

After dismissal:

- If awake follow-up is enabled, show a brief confirmation: "I will ask again in 7 minutes."
- If the alarm was one-off, remove it or show a recent-history entry.

## Testing And Validation Plan

### Automated Tests To Add

- Compose test: empty Alarms tab shows readiness state and quick presets.
- Compose test: saving a new prayer-relative alarm shows a concrete next trigger.
- Compose test: disabled alarm can be enabled from editor.
- Compose test: editor progressive disclosure hides advanced sections by default.
- Compose test: exact-alarm missing state appears on Alarms tab.
- Robolectric test: scheduling snapshot maps to user-facing readiness issues.
- Robolectric test: one-off alarm is removed after final dismissal.
- Robolectric test: suppression policy produces a visible missed/suppressed state.
- Unit test: stop challenge fallback selection when gyroscope is unavailable.
- Unit test: awake follow-up delay and escalation copy are included in saved-alarm summary.

### Manual Device Matrix

Validate on at least:

- Android 12: exact alarm permission behavior.
- Android 13: notification permission denial and grant.
- Android 14: full-screen intent permission denial and grant.
- Locked screen and unlocked screen.
- Battery optimization enabled and disabled.
- Auto-silence active, manual silence active, and no silence active.
- Gyroscope unavailable or disabled sensor path.
- RTL layout with large font size.

### Success Metrics

- Users can create a first alarm without reading advanced settings.
- Users can tell whether the next alarm is ready to ring.
- Missed or suppressed alarm reports decrease.
- Permission-denied users know what to fix.
- Challenge-enabled dismissals complete without support complaints about being stuck.
- Awake follow-up usage has clear opt-in behavior and low surprise.

## Suggested Implementation Roadmap

### Phase 1: Trust And Safety

- Add Alarms tab readiness panel.
- Promote silence conflict state to alarm cards.
- Add challenge emergency fallback.
- Add analytics for suppressed and blocked alarm states.

### Phase 2: Setup Simplification

- Add editor enabled switch and label field.
- Collapse advanced editor sections.
- Rename wake-up check and awake check in UI copy.
- Add final save summary for new alarms.

### Phase 3: Runtime Polish

- Add snooze with bounded prayer-aware rules.
- Improve full-screen context for sub-alarms and queued alarms.
- Clean up one-off alarms immediately after completion.
- Add safe test alarm mode.

### Phase 4: Onboarding And Measurement

- Make onboarding action-oriented.
- Add readiness and challenge analytics.
- Expand Compose, Robolectric, and device validation around alarm UX.

## Open Product Decisions

1. Should wake alarms ring during prayer auto-silence by default?

Recommended answer: yes. A wake alarm is an explicit user commitment. If the app suppresses it, the user should have chosen that policy knowingly.

2. Should awake follow-up be on by default?

Recommended answer: either default it off, or keep it on only with a clear save summary and a visible delay selector. It is useful, but it can feel surprising after the first dismissal.

3. Should stop challenge be allowed to block all stop actions indefinitely?

Recommended answer: no. It should block easy dismissal but still have a delayed, intentional emergency path.

4. Should one-off timers live in the same list as daily/prayer alarms?

Recommended answer: yes for now, but show them as temporary and remove them immediately after completion. If history becomes valuable, introduce a separate recent-history area.

5. Should sub-alarms be exposed to every user?

Recommended answer: keep them, but treat them as advanced. They are valuable for power users and pre-Fajr routines, but they should not dominate first alarm creation.
