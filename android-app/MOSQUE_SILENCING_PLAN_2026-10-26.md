# Mosque-aware automatic silencing — implementation plan

**Prepared: 2026-10-26. Revised: 2026-10-27 after checking Claude's review; Stage 0 status updated 2026-10-28. The owner authorized Stage 0 only. The isolated prototype and Pixel checks are recorded in the [go/no-go report](MOSQUE_SILENCING_STAGE0_REPORT_2026-10-28.md): NO-GO for broader implementation on current evidence. Stages 1–4 have not begun.**

The feature should keep the existing time-only option and add two explicit location options using enabled polygon areas, including the forthcoming Tunisia polygons and private areas. Use an offline polygon engine, a bounded presence state machine, one coordinator for all app silence requests, and a correctly typed location foreground service during acquisition and active visits. A location mode never silently falls back to time only.

**Owner clarification, 2026-10-28:** Tunisia polygons will be ready after the plan; a few existing polygons may be used for testing, and this work must not focus on shipping them. The earlier private-only v1 assumption therefore does not exclude the forthcoming Tunisia input. Stage 0 uses three exact test shapes and synthetic cases; catalog readiness, rights and shipping are not go/no-go gates for this stage. Collection, publication and shared-service work are outside this task. Real-mosque field validation remains pending at the owner's explicit choice.

This revision adopts verified corrections rather than all of the review's recommendations. In particular, Play's single-feature rule concerns the **declaration**, not a one-feature quota for the app; a coarse geofence EXIT does not replace polygon evidence; GPS samples at one position do not establish mosque boundaries; and silent watchdogs must not all become alarm-clock alarms.

The [verification and finding dispositions](../work/mosques/silencing-plan-verification-20261027/verification.md) link the code audit, recomputed geometry, provenance/public-distribution checks and freshly fetched official sources. The [previous plan](../work/mosques/silencing-plan-verification-20261027/plan-before.md), [Claude review snapshot](../work/mosques/silencing-plan-verification-20261027/claude-review-before.md), and [earlier debate](../work/mosques/silencing-plan-debate-20261026T154404Z/judgment.md) remain evidence. Claude's original review is unchanged.

## 1. Verified baseline and terminology

The review used the working tree, including existing uncommitted background-location changes. These are integration prerequisites to reconcile during future implementation, not commits made by this task. Current source hashes are in [code-source-snapshot.json](../work/mosques/silencing-plan-verification-20261027/code-source-snapshot.json); the former snapshot is stale for `MainScreen.kt` and `AndroidManifest.xml`.

| Fact | Verified baseline and implication |
|---|---|
| Android support | minSdk 26, targetSdk 36; play-services-location 21.3.0. Prototype new location modes on API 29+; API 26–28 retains existing time/manual behavior. This is a scoped DND-adapter choice, not an Android location limitation. Promote only API/OEM combinations that pass ownership and recovery checks. |
| Prayer windows | Reuse the shared planner **through the Android scheduler's complete policy**: enabled prayers, dismissals, Ramadan Isha override and Eid windows. Keep start-inclusive/end-exclusive instants, place time zone, overlapping/overnight periods, Jumu'a and date-change rules. |
| Mosque code | `MosqueRepository` and `MosqueGeometry` exist but have no production caller. They are candidate matching, not a completed presence engine. |
| Research asset | 2,481 inventory records; 1,116 structurally eligible polygons; 60 automatic-silencing-eligible areas, split 54 analyst/agent-reviewed approximate, 4 user-approved approximate and 2 user-approved full-premises records. These flags do not establish distribution rights. |
| Current packaging/public state | The 3,065,312-byte research GeoJSON is in app assets with no packaging exclusion and is present on the public GitHub `codex` branch. Read-only inspection also found the exact current asset in an existing debug APK; no APK was built here. This revision does not remove it or rewrite history. A verified private archive and separation from production packaging are prerequisites before another build containing this research asset is shared. |
| Location and services | Existing travel permission/provider plumbing can be shared. Its coarser/older accepted fixes must never become mosque presence evidence or change prayer locality as a side effect. `SilenceGuardService` is specialUse, not a location service. |
| Existing repair | The six-hour battery-not-low worker repairs schedules; it cannot detect arrival/departure or provide timely release. |

Use these terms consistently:

- **Geometry-valid:** bounded, finite, topologically supported shape that passes storage validation.
- **User-enabled:** a geometry-valid private area the user deliberately permits to affect this phone.
- **Distribution-cleared:** source rights, notices and release review permit sharing a particular record. V1 does not turn research approvals into this status.
- **Confirmed visit:** an entry established from distinct fresh fixes, with a finite holding/recovery contract. It does not prove indoor position, prayer attendance, ownership or activity on a particular floor.
- **Request:** one app feature's desired silence contribution; **observed DND** is the phone's aggregate state and can differ from it.
- **Acquisition deadline, no-fix deadline, ambiguity hold and visit cap** are separate bounds defined in section 3.

Keep the intentional scope of any accepted area, including disclosed courts and surrounding space. Do not silently tighten accepted rectangles to roofs. Closure alone does not invalidate an otherwise qualified mosque; an actual change of use or identity conflict needs its own assessment. Historical approvals and held candidates remain untouched.

## 2. Exact behavior and mode selection

Persist `AutoSilenceTrigger`: `TIME_ONLY`, `TIME_AND_MOSQUE`, `MOSQUE_ONLY`. The existing `SilenceMode` already means endpoint/duration choice and must not be reused. Keep the master automatic switch independent.

Let **T** mean an enabled, non-dismissed prayer silence window currently applies, and **P** mean a currently valid confirmed visit under section 3. Assume master enabled, feature readiness satisfied, and no applicable suppression or higher-priority owner:

| T | P | TIME_ONLY | TIME_AND_MOSQUE | MOSQUE_ONLY |
|---|---|---|---|---|
| False | False | No request | No request | No request |
| False | True | No request | No request | Request |
| True | False | Request | No request | No request |
| True | True | Request | Request | Request |

TIME_ONLY also preserves the existing bounded **already-active** 45-minute bridge, described precisely in section 6. That bridge cannot initiate silence, does not supply T to the combined mode, and is not limited to locality replanning.

“No request” releases only this automatic source. It never means force normal ringer mode, clear manual silence or turn off another system/app DND rule.

1. **TIME_ONLY:** preserve existing prayer settings, delayed starts, fixed/relative endpoints, occurrence dismissals, overlaps, place time zone, Ramadan/Eid/Jumu'a and overnight behavior. No new location requirement or upgrade prompt.
2. **TIME_AND_MOSQUE:** both conditions must hold together. Entry during an open window can activate; a confirmed exit or window end releases this request. A window opening can use an existing unexpired confirmed visit, including its explicitly bounded uncertain-hold state; an uncertain fix with no confirmed visit cannot start one.
3. **MOSQUE_ONLY:** operates independently of per-prayer start/end and enable switches. Keep those settings saved and explain that they do not schedule this mode. A prayer-end alarm cannot terminate this request. Visit bounds and the alarm-safety policy still apply.
4. **All location modes:** activate only within user-enabled saved areas. Missing coverage means no supported match, not that no mosque exists. No automatic switch to TIME_ONLY abroad, on no-match, on degraded GPS or after permission denial.

| Event | Required transition |
|---|---|
| Fresh strict inside evidence plus entry dwell | Establish a visit, then evaluate the selected trigger. |
| Fresh ambiguous evidence during a confirmed visit | Use bounded asymmetric holding; do not treat an inside-test failure alone as exit. |
| Two fresh confident-outside fixes | End the visit and release only dependent requests. |
| Approximate-only permission, precise-to-approximate downgrade, required access revoked or location switched off | Immediately invalidate affected location requests on reconciliation; show needs setup. There is no uncertainty grace for a known lost prerequisite. |
| DND access lost, master off or mode inactive | Invalidate the affected request; observe/report if the platform contribution cannot be changed without restored access. |
| Area disabled/deleted/revised or storage unavailable | Reevaluate affected visits against the current enabled geometry generation; preserve unrelated owners. Never fall through to an old larger shape. |
| Combined window ends | Release combined request even while inside; stop observation if no other current/near window needs it. |

A missing trigger preference defaults to TIME_ONLY. An explicit malformed/future-unrecognized value is inactive and asks for a valid choice; defensive parsing is not a stored-data migration project. The recorded development-phase preference removes the proposed production migration subsystem, not runtime cleanup, reboot safety or input validation. Verify release state before a future implementation relies on the no-real-users assumption. A test phone with an old implicit DND mode may need that mode ended once in system Settings.

Mode changes are serialized. Transfer between already-valid requests without an audible gap; when changing from TIME_ONLY to an unconfirmed location mode, the old time request stops and the UI shows checking/needs setup. Do not keep time-only silence secretly while acquiring. Offer deliberate manual silence. Disabling the master releases automatic sources without cancelling manual/nap ownership.

## 3. Presence geometry, states and provisional bounds

### Entry and the measured geometry constraint

Keep the current strict entry gate initially: reject mock fixes; missing/nonfinite/nonpositive accuracy; accuracy above 30 m; unknown monotonic age or age over 30 seconds. The whole reported accuracy disk **plus 2 m** must lie inside the effective area and outside its holes. Reported accuracy is a probabilistic 68% radius, not a containment guarantee. Two temporal samples are distinct, not statistically independent under indoor multipath.

The recomputation from the 60 research areas reproduces Claude's table. These are geometric possibilities, **not measured indoor success rates**:

| Reported accuracy | Areas where no position can pass entry | Median share of area where entry can pass |
|---|---|---|
| 5 m | 0 / 60 | 61.16% |
| 10 m | 0 / 60 | 39.11% |
| 15 m | 2 / 60 | 21.42% |
| 20 m | 4 / 60 | 9.24% |
| 25 m | 22 / 60 | 2.04% |
| 30 m | 36 / 60 | 0% |

Largest inscribed radius: minimum 14.745 m, median 30.316 m, maximum 68.599 m. Area: minimum 930.17 m², median 4,132.31 m², maximum 20,184.75 m². **24 areas can accept a 30 m fix somewhere**; the review's “none” statement is incorrect. Raw calculation/method and record-level provenance are in [dataset-evidence.json](../work/mosques/silencing-plan-verification-20261027/dataset-evidence.json).

Extend the API to distinguish confident inside, confident outside, near-edge/overlapping uncertainty, unusable fix and area-store unavailable. Outside means the reported uncertainty disk lies fully outside **all** supporting enabled areas with the release margin. Handle holes, concavity, multipart geometry and boundary contact explicitly.

For touching/overlapping private shapes representing one place, use one validated logical-area geometry consistently for entry/hold/exit. The editor should merge a supported simple shape or reject unsupported topology; no automatic “inside either component” approximation may stand in for disk coverage across their shared edge. Independent disjoint places remain independent candidates. Moving between confidently supported areas must not briefly restore audio. Preserve supporting IDs/revisions internally without a visit history.

### Asymmetric holding

The former rule that required another strict-inside fix within 120 seconds is withdrawn as the default visit contract. It can end a genuine long indoor visit without exit evidence. The replacement is **bounded asymmetric holding**: strict to start, fresh confident-outside evidence to establish exit, and separate finite handling of uncertain-but-arriving fixes versus complete loss of usable observation. Coarse geofence events can prompt checks; they neither renew a visit nor conclusively prove polygon exit.

Use the following as an explicit **stage-0 candidate**, not validated release defaults. The candidate lengthens ambiguity tolerance without claiming that a stationary phone will always remain silent:

| Parameter | Initial prototype value / invariant |
|---|---|
| Entry dwell | Two distinct strict-inside fixes at least 15 seconds apart within one acquisition episode. Reject repeated/out-of-order monotonic timestamps. |
| Acquisition | 90 seconds total, including service/provider start, entry dwell and at most one short retry inside the permitted session. Later attempts need another legal start event or deliberate user action. |
| Confident exit | Two distinct outside fixes at least 20 seconds apart, with an initial extra 5 m release margin. |
| Normal visit observation | Request about one update per 60 seconds, with a bounded higher-accuracy check near an edge or after an exit hint. Cadence/accuracy are requests, not guaranteed delivery. |
| No usable observation | 5 minutes from last usable delivered observation. A running service or heartbeat with no delivered usable fixes is not proof of continuing presence and does not renew this deadline. |
| Ambiguity hold | At most 15 minutes from last strict-inside support. Fresh ambiguous fixes may demonstrate liveness but do not move this deadline. Confident outside evidence still exits earlier. |
| MOSQUE_ONLY visit cap | 3 hours from confirmed entry; no silent renewal or immediate reacquisition while still in the same visit. Explicit continuation requires fresh strict entry evidence. TIME_AND_MOSQUE also ends at its supporting window end. |
| Suppression/rearm | A user stop or eligible wake exception suppresses the current visit. Confident exit then new entry or explicit re-enable ends suppression. After 6 hours without an established departure, move to inactive/needs rearm rather than silently starting again. |

For this prototype, a **usable holding observation** is a distinct, nonmock, precise-permission fix from the current session with valid monotonic age at most 30 seconds and finite positive reported accuracy at most 100 m. Only strict-quality fixes at or below 30 m can establish confident inside/outside. A 30–100 m observation demonstrates liveness only: it cannot start a visit, prove an exit or renew the 15-minute ambiguity deadline. The 100 m ceiling is another provisional measurement parameter. Service heartbeats, stale/repeated fixes and worse observations do not reset the five-minute no-observation timer.

The ambiguity budget must exceed at least three requested observation intervals; acquisition must fit startup, dwell and the permitted retry; exit dwell must be shorter than every hold bound. Do not weaken the entry gate, enlarge areas or renew deadlines from coarse/ambiguous callbacks to make a pilot pass. The 15-minute/5-minute/3-hour candidates can still release during worship; measure that failure as well as silence after departure. If neither failure is acceptable on an offered device, do not release its automatic location modes. A richer movement-assisted policy is deferred rather than adding activity-recognition permission or assuming every phone has a significant-motion sensor.

| State | Silence authority and exit |
|---|---|
| IDLE | No location request. Observe supported coarse triggers or deliberate checks. |
| ACQUIRING | No location request. Strict entry succeeds, deadline/failure returns to IDLE/needs manual action, or prerequisite loss becomes inactive. |
| VISIT_CONFIRMED | Presence is valid; selected trigger decides the request. Refresh support from valid evidence; leave on confident exit, window/cap, stop or prerequisite loss. |
| VISIT_UNCERTAIN / EXITING | Only the existing bounded visit can persist. Retain deadlines; finish exit on outside dwell; restore confirmed state only with strict support. |
| SUPPRESSED | No location request for that visit, including after process recreation. Keep cheap trigger/recovery handling; a stopped observation service must not be the only possible route out. |
| INACTIVE_NEEDS_SETUP / REARM | No location request. Explain missing permission/support or expired suppression/cap; require the specified setup/rearm action or supported new-visit evidence. |

No-fix, ambiguity-hold or visit-cap expiry moves the unresolved visit to needs rearm. A fresh strict-inside callback by itself must not immediately start the same visit again. Rearm requires a deliberate foreground action with fresh entry evidence, or confirmed departure followed by a new entry; do not discard the unresolved-visit barrier just because the observation service stopped.

Persist only current desired state/deadlines/suppression with a boot/session marker and geometry generation. Use monotonic age/dwell; never compare elapsed time across boots. Reboot or process recovery cannot recreate a visit from an old “inside” flag. Reject callbacks from obsolete mode, permission, boot, request or geometry generations.

## 4. Execution, permissions, geofences and release recovery

Choose a **location foreground service** for the proposed locked-screen acquisition/visit cadence. Declare the location service type and `FOREGROUND_SERVICE_LOCATION`, with a quiet ongoing notification, explicit stop action and limited lifetime. The specialUse guard is not a substitute. A single visible-screen diagnostic need not start a service.

Visible user action, geofence transitions and an exact alarm completing a user-requested action are documented candidate start routes. The existing window-start `setAlarmClock` callback is a concrete route to test for TIME_AND_MOSQUE while already inside. **FGS-start exemption and resource permission eligibility are separate checks.** Android 14+ can throw at service creation; background-created location observation needs the appropriate background grant as well as runtime precise access/location enabled. Catch permission/start failures and remain inactive. Arbitrary workers, retry timers and reboot callbacks do not inherit a start exemption. [FGS restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [location type](https://developer.android.com/develop/background-work/services/fgs/service-types#location).

For v1, bound the number of automatically monitored private areas to the available capacity within **100 geofences per app per device user**, accounting for other registrations. Register all enabled areas within that cap; do not add national relocation infrastructure. If the cap is reached, require a user choice of which areas to monitor and keep the others saved. A future larger working set needs its own visible coverage contract.

- Register enclosing circles with ENTER/EXIT, `INITIAL_TRIGGER_ENTER`, explicit responsiveness (initial request: 60 seconds), and DWELL with an initial 60-second loitering delay as a possible retry/pass-by reduction for MOSQUE_ONLY. DWELL is not an extra compulsory delay after strict entry has already succeeded, and none of these values guarantees delivery time.
- In TIME_AND_MOSQUE, retain cheap registrations between prayer windows; allow acquisition within an initial 2-minute preparation period and at the actual start, and on entry during an open window. A visit confirmed in preparation may be used only if its support remains valid at activation. Stop observation after the last applicable/near window ends. Account for initial-inside events even when no physical boundary is crossed.
- Re-register after reboot and `GEOFENCE_NOT_AVAILABLE`, and reconcile desired registrations on app start/package replacement. Official loss cases include reinstall/app-data clear/Play-services data clear; data clear also removes saved areas, so no old configuration is presumed to survive. Play-services upgrades and ordinary process restarts are documented as automatically restored, not mandatory blanket-loss cases.
- Geofence EXIT requests a prompt fresh exit evaluation. It cannot directly substitute for the polygon/confidence contract. Failed acquisition may retry once inside its live permitted session, on a later DWELL/other qualifying event, or from a user action; it must not become an infinite timer loop.

Geofence guidance describes average background delays around **2–3 minutes** and possible longer delays, including around six minutes after prolonged stillness. Acquisition adds delay; these figures are not upper bounds. Coarse alert radii commonly need 100–150 m, which is why circles only trigger polygon checks. [Geofencing guide](https://developer.android.com/develop/sensors-and-location/location/geofencing).

Ordinary background fixes may arrive only a few times per hour. Select explicit high-accuracy acquisition, fresh-entry maximum historical age zero, and bounded repeated updates during the service. Validate each result independently. Balanced priority does not guarantee a result fails, and a default ten-second-old cached result would not itself violate the old 30-second ceiling; the reason for explicit settings is a clear acquisition contract. Existing travel thresholds remain separate. [Background limits](https://developer.android.com/about/versions/oreo/background-location-limits), [FLP](https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient), [request builder](https://developers.google.com/android/reference/com/google/android/gms/location/CurrentLocationRequest.Builder).

A **deliberately user-started session** may start a correctly typed service from visible UI and continue after Home/screen-off with its valid foreground location access. Define it separately from automatic arrival monitoring and stop at exit/cap. Screen lock alone does not end its access. It cannot silently restart on another arrival without an eligible route, and it is not a guaranteed Play-policy loophole. On non-GMS devices, initially offer supported foreground/session checks only; `LocationManager.addProximityAlert` is a possible later measured experiment, not demonstrated background parity. [Location access](https://developer.android.com/develop/sensors-and-location/location/permissions).

Request precise foreground permission first, then one prominent all-use background disclosure and Settings access when the user chooses automatic operation while closed. Keep separate travel/mosque opt-ins. Readiness covers DND access and rule status, precise/background access as appropriate, enabled location, effective saved areas, valid recovery mechanism/exact access, notification visibility and actual battery restrictions. Explain Wi-Fi/scanning/network-location/connectivity dependencies where detectable; internet through Wi-Fi can suffice, so do not demand mobile data universally. Offline polygon calculation is not a guarantee of offline background discovery.

Use an in-session timer plus **one consolidated one-shot `setExactAndAllowWhileIdle` release backstop** where this user-visible critical release justifies exact access. Check `canScheduleExactAlarms`, share the app's idle-alarm allowance, and reconcile at every callback/resume. If the necessary recovery route is unavailable, do not begin an unattended automatic request; show a manual/session alternative with its actual limits. Revoking `SCHEDULE_EXACT_ALARM` stops the app and cancels its future exact alarms; an existing DND contribution can therefore outlast its logical cap until a supported reconciliation runs. Permission observation cannot guarantee cleanup after that stop. This strategy is subject to stage-0 recovery measurement.

**Measured Pixel 6 limit, 2026-10-28:** revoking exact-alarm access through system Settings killed the diagnostic process and removed its pending expiry alarm while its DND rule remained active. Explicit scoped reconciliation removed that rule. Persist an observed loss of a required capability before considering permission return; a returning grant must not revive the previous request. A loss and restoration that occur entirely while the app cannot observe them cannot be inferred from the current grant alone. Force-stop likewise has no finite automatic-release guarantee: the supported fallback is the system Modes stop control or reconciliation when execution resumes, without replaying a global filter/ringer baseline. This is a required support limitation, not an accepted field release contract.

The actual reboot test advanced the Pixel boot counter from 259 to 260. A credential-encrypted, post-unlock boot receiver removed only the old diagnostic DND rule before any Activity launch. The separate old-boot location journal remained until app-launch reconciliation and was then rejected despite its elapsed deadline being numerically far ahead of the new uptime. Preserve boot identity and idempotence; do not promise pre-unlock cleanup from a credential-encrypted journal or restart observation from a boot callback. Two forced-Doze expiry deliveries succeeded after ordinary process death, but they do not establish a long-idle or shared-quota delivery bound.

Do not use `setAlarmClock` for every internal timeout: its purpose is a highly visible alarm-clock event, including system next-alarm UI, with higher resource cost. Idle-allowed alarms have shared quotas/batching; the documented nine-minute rate limit is **not a nine-minute delivery guarantee**. Do not turn alarms into location polling. Do not copy the worker's battery-not-low constraint onto mandatory release. Force-stop/OEM restrictions can prevent all cleanup execution; next-launch reconciliation and clear system stop controls remain necessary. The review's OnePlus “75% delay” figure is unverified here. [Alarm guide](https://developer.android.com/develop/background-work/services/alarms/schedule), [AlarmManager](https://developer.android.com/reference/android/app/AlarmManager), [Doze](https://developer.android.com/training/monitoring-device-state/doze-standby).

## 5. One coordinator and safe platform ownership

Introduce `SilenceRequestCoordinator` with **one serialization boundary used by every entry point**. The current scheduling dispatcher serializes some UI/sync work; scheduler/controller locks are different locks and do not already cover the whole lifecycle. The coordinator owns compact durable requests, first-owner baseline handling where applicable, suppressions, alarm decisions, deadlines and idempotent platform reconciliation. Save desired state before platform effects; handle a crash between the effect and bookkeeping. Do not build a general event-sourcing framework.

On Android 15+ for target 35+, legacy `setInterruptionFilter` affects an implicit package rule rather than directly restoring global DND. An aggregate observed filter is never a private baseline to replay. On API 26–34 the legacy global path has different behavior. [Android 15 change](https://developer.android.com/about/versions/15/behavior-changes-15#dnd-changes).

For the API 29+ prototype, use explicit app-owned `AutomaticZenRule` contributions per owner class: prayer, mosque, manual, silence-until-alarm. Give each a clear Arabic name and a valid enabled/exported configuration activity with the required action/localized metadata (or an appropriate condition provider); the current manifest has neither for such rules. A configuration activity/state updates are API 29; general enabled/disabled/removed status broadcasts start at API 30; rule-state getter, activated/deactivated status detail, builder and manual-invocation control require API 35. Choose supported version-specific constructors/types rather than calling newer APIs unguarded. Intended NONE behavior must be explicit. [NotificationManager](https://developer.android.com/reference/android/app/NotificationManager), [AutomaticZenRule](https://developer.android.com/reference/android/app/AutomaticZenRule).

The evaluator must gate TIME_AND_MOSQUE **before** submitting one combined automatic request to the mosque rule. Independently activating prayer and mosque rules would merge as OR and violate the AND contract. TIME_ONLY uses the prayer rule; MOSQUE_ONLY uses the mosque rule. Stable rule IDs/condition URIs and state updates are required: on API 29–34, `updateAutomaticZenRule` deactivates an active rule not backed by a condition provider, so routine metadata/definition refresh must not create a hidden end/restart cycle.

**Do not write global ringer mode in the new owned-rule path.** AOSP 14/15/16 couples external ringer changes to global DND: changing to SILENT from another ringer state can enable DND, and NORMAL/VIBRATE while DND is active can clear manual DND and snooze automatic rules. This is verified reference-platform behavior to check on OEMs, not a claim that every setter call on every phone changes DND. Validate effective call/notification/media/alarm behavior instead of introducing a ringer “fallback” that defeats ownership.

Respect system disabled/removed/snoozed rules and later user changes. Ordinary unchanged explicit-rule TRUE updates respect snooze; repeated legacy NONE calls, rule recreation and artificial FALSE/TRUE cycles can defeat it. Automated callbacks must not claim `SOURCE_USER_ACTION`. `setManualInvocationAllowed(false)` on API 35 prevents inappropriate manual activation, but is not a snooze-preservation solution. API 35's state getter alone is insufficient: Android 15 reference code can return raw condition state without snooze state, and Android 16 behavior is flag-dependent. Reconcile supported state/status events and persist explicit app suppression; unknown override state must not trigger a fresh activation or rule replacement. API 29–34 lacks equivalent observation, so require a demonstrated safe lifecycle before advertising location modes there. Multiple rules do not by themselves solve alarm exceptions or user intent.

The Stage 0 Pixel test now confirms actual system Modes **Stop** preserves an enabled rule's deactivation override through two ordinary unchanged TRUE updates. A separate actual Settings revocation of Modes access deleted the probe's rules before removing access; this matches the Android 16 Settings source and must not be conflated with a shell-only access toggle. These are measured API 36 behaviors, not a general API/OEM guarantee. See the [device follow-up and official references](../work/mosques/silencing-stage0-device-followup-20261028/official-reference-notes.md).

Three existing code hazards must be addressed in the future coordinator work:

1. `restoreState` writes the ringer before and after the filter, which can snooze other modes on the reference platform.
2. Replaying a saved non-ALL aggregate filter on Android 15+ can activate the app's implicit rule after its logical request ended. A later NORMAL/VIBRATE write can instead snooze it and other rules; the final result is not guaranteed by a saved filter or flag.
3. Repeated `enableAutoSilence` during replanning reasserts NONE and can undo a user's snooze. Restored live flags/baselines from backup create another unsupported-ownership risk.

These are code/platform findings, not newly observed device failures. Keep prayer timing and wake-policy parity while correcting platform ownership. No production stored-data migration system is required for the recorded development phase; future test-device setup must explicitly end any proven old app contribution before enrolling new rules. Do not assume “activate new then clear old” is safe on older global-DND APIs or that clearing the new rule clears the old implicit one.

NONE can suppress alarms, media and vibration. “DND is not ALL” does not by itself prove an alarm is blocked: ALARMS or a permissive PRIORITY policy can permit it. Check effective alarm policy/audio state and provide only permitted visual/vibration alternatives. Never clear another owner's rule merely to obtain ALL.

## 6. Wake, awake-check, manual and stop behavior

### Correct TIME_ONLY compatibility contract

Preserve actual code behavior documented in the [code audit](../work/mosques/silencing-plan-verification-20261027/code-findings.md):

- `scheduleAllInternal` can keep already-active automatic silence until an upcoming enabled window within 45 minutes. Ordinary replans, repair, midnight and manual-expiry paths can all take this bridge; `onOfficialDatesChanged` disables it for that scheduling pass.
- `currentSilenceWindowPrayer()` separately returns the next prayer within 45 minutes when the auto flag is active and no window contains now. That lookup has no official-date exclusion. A FROM_NOW runtime wake can therefore qualify during a bridge and dismiss the next occurrence through `dismissCurrentWindows`' fallback.
- Main wake delivery uses scheduled eligibility (`FROM_NOW || ringDuringSilenceWindow`) **and** conflict-payload/runtime qualification. A genuinely null runtime lookup with no qualifying conflict payload remains non-overriding.
- An awake-check inherits `autoSilenceOverrideAllowed`; with active automatic ownership and no manual/nap blocker, this flag can authorize lifting without a conflict prayer. Noneligible awake-checks also retain today's historical cancellation when their scheduled trigger falls in a **planned enabled window**, independently of dismissal/current DND.
- Existing place-change dismissal pruning retains an occurrence only if the resolved prayer time stays within 45 minutes. Preserve TIME_ONLY semantics; the new modes need explicit stable occurrence/visit suppression across their own replans rather than assuming this pruning already guarantees it.

### Policy chosen for the new modes

This is a design choice for the new modes, not a claim about current code or a requirement imposed by Android:

| Owner | Ordinary wake / awake-check | Eligible automatic exception |
|---|---|---|
| Manual | Preserve suppression while effective. | Do not remove manual ownership. |
| Silence-until-alarm / nap | Preserve matching-alarm release, pending IDs, original baseline and scoped pause/resume. | Preserve the same scoped lifecycle. |
| TIME_ONLY | Preserve the complete behavior above, including planned-window historical awake-check handling. | Preserve payload/runtime qualification, bridge fallback and occurrence dismissal. |
| TIME_AND_MOSQUE | Suppress only when the combined request/event decision applies; planned time alone does not prove mosque silence. | Dismiss supporting occurrence(s) and suppress this visit's automatic request; re-entry/replan must not undo the decision. |
| MOSQUE_ONLY | Presence alone must not extend ordinary prayer-time alarm suppression for hours: allow a configured ordinary wake outside an applicable enabled, non-dismissed prayer window by ending this visit's mosque request and durably suppressing the rest of the visit before playback. During an applicable window preserve the configured ordinary prayer-time suppression. | End the mosque request and suppress the remainder of this visit before playback; do not automatically FALSE/TRUE-resume it after the alarm. |
| Independent system/app DND | Observe actual effective restrictions. | Never remove another owner's contribution or promise playback through it. |

This avoids an undefined “always at night” exception and prevents a mosque-only visit from independently blocking the app's Fajr/suhoor alarms outside configured prayer-silence periods. Explain this new alarm scope before mode selection; test origin, receiver, playback, escalation and follow-up decisions. An awake-check gets no new authority of its own; retain its originating event's decision. A permitted wake still depends on other owners and actual platform audio state; external alarm apps are not automatically observed by this policy.

“Stop until you leave” releases only the location request, persists current-visit suppression and keeps a bounded route to departure/rearm. A coarse EXIT is a reevaluation hint. Cap/uncertainty expiry cannot immediately restart the same visit. “Skip this prayer period” remains occurrence-scoped across re-entry. Manual silence survives mosque exit; a valid mosque request survives an unrelated prayer-end callback. No ordinary restore/force-normal fallback may bypass these owners.

## 7. Complete integration checklist

Paths are relative to the repository root; detailed current line evidence is in the code audit. Both starting and reading silence must use the coordinator's meaning.

| Source / consumer | Required work in a later implementation |
|---|---|
| `SilenceScheduler`, `SilenceReceiver` | Start/end/replan, `cancelAll`, master/permission loss, stale prayer-end fallback, midnight, official dates and pre-adhan `ACTION_DELEGATION_CHECK`. Keep mosque fixes separate from travel fixes. |
| `SilenceModeController`, `ManualSilenceScheduler`, `ui/MainScreen` | Automatic/manual/timed controls, manual expiry, restore button and readiness paths; release only owned requests. |
| `wake/WakeAlarmScheduler`, `nap/NapSilenceController` | Matching alarm, pending IDs, activate/pause/resume/remove and first-owner baseline; nap must never save a future mosque's NONE/SILENT as the original baseline. |
| `wake/WakeAutoSilenceConflictController`, `WakeAlarmReceiver`, `WakePlaybackService`, `AwakeCheckSilencePolicy` | Runtime/payload eligibility, receiver/playback recheck, follow-up/escalation and suppression lifecycle. |
| `ScheduleRefreshCoordinator`, application official-date callback, `BootReceiver`, `SilenceVerifyWorker` | One transaction boundary across lifecycle/repair paths, with feature-specific readiness at scheduling/evaluation and final platform guards. |
| `PhoneStateReceiver`, `SilenceGuardService` | Missed-call accounting and guard lifetime must see all effective owners. Location observation remains a distinct typed service. |
| `SilenceStatus`, `PhoneSilenceHeroState`, prayer/manual settings sheets | Distinguish planned periods, this app's request and observed phone state. Do not invent a prayer end for a visit. Include `SharedSilenceSettingsSheet`, `PrayerSilenceSection`, `PrayerSilenceEditorSheet`. |
| `quran/audio/QuranSilence`, `QuranPlaybackService` | NONE/media restrictions, actual rule state and mosque-specific explanation; no recitation start through unsupported silence. |
| `adhkar/DhikrReminderScheduler` | It already holds unknown-ended silence until refresh. Preserve that behavior; update ownership/end lookup and raw SILENT-ringer heuristic when the adapter stops ringer writes. |
| `wake/AlarmAudioController` | Forced alarm volume and restoration do not prove that USAGE_ALARM can bypass DND. Validate actual allowed playback. |
| `PrefsManager`, backup/transfer rules, manifest | Separate persistent user configuration from live ownership/private geometry; add typed service/configuration activity and version-gated APIs. |

The current parser ignores geometry of deliberately ineligible records; it is incorrect to say every bad inventory ring disables the catalog. Malformed **eligible** geometry or required metadata can abort the whole parse. Any later runtime fixture/set should isolate invalid records and be compact rather than loading embedded review evidence. No research catalog becomes a runtime fallback in v1.

## 8. Private editor, storage and source rights

V1 stores independent private IDs, names, validated geometry, enabled state and revisions. Save and enable are explicit. No base overlays, shared submissions or hidden imports are required. Keep draft recovery, undo/cancel, vertex/coordinate editing, negative longitudes inside LTR isolates, Western digits, Arabic RTL, 48 dp targets and large-font/accessibility support.

Support simple polygons/rectangles first. Preserve holes/multipolygons read-only if editing them is unsupported; never flatten them by saving. Validate finite WGS84 longitude/latitude, distinct vertices, ring closure, nonzero area, self-intersection, bounds/extent and resource limits at editor and storage boundaries. An initial 10–100,000 m² screen is a proposal, with an explicit legitimate-large-compound path; area alone must not admit a continent-spanning thin shape. Warn about roads/homes and intentional surrounding space without silently redrawing accepted geometry.

**A GPS fix is a draft center, not a boundary.** Do not ship “stand inside for a minute and we draw the mosque” or infer a rectangle from correlated indoor scatter. The user must be able to inspect/draw the actual intended area. An offline outline/coordinate editor needs a usability demonstration; if it is insufficient, use the previously preferred MapLibre Native renderer with a provider whose display, coordinate-derivation/export, attribution and caching terms cover the feature. Select and record that provider and its dated cost before implementing a map-backed editor; the renderer alone supplies neither tiles nor rights. Keep map display requests/privacy separate from offline containment. No pricing claim is carried forward from the old alternative comparison.

Allow deliberate GeoJSON export through the document picker; no transmission, account or contribution destination is implied. General import, complex topology editing, a map-provider account and shared review are deferred.

**Verified research provenance:** 57 of 60 eligible areas have Google-image-derived geometry; one is unchanged OSM and two are OSM-derived rectangles. Three are **candidates for independent clearance**, not three automatically cleared records. The consumer Maps agreement is the relevant starting point for the Android-app screenshot workflow; Platform terms apply to a future SDK integration. The consumer other-mapping-dataset clause has a substitute/substantially-similar-service qualifier omitted by Claude. Copying/content restrictions and ODbL 4.4(d)'s incompatible-contents rule still require a specific lineage/rights assessment. Do not conclude from these texts alone that exactly 57 are unlawful or that every OSM candidate is distributable. [Maps additional terms](https://maps.google.com/help/terms_maps/), [ODbL](https://opendatacommons.org/licenses/odbl/1-0/).

Preserve source evidence, exact historical approvals, holds and agent-review identity in a durable private archive before any future packaging cleanup. Embedded local paths/Google URLs/proxy address and the complete research record do not belong in the production runtime asset. The builder/validator currently target the full research catalog; separating research generation from a small test fixture/runtime input is future work. **No asset move, deletion, relicensing, Git-history change or public takedown is performed by this plan revision.** Public-history handling requires a concrete separately authorized action.

## 9. Privacy and release requirements

Evaluate containment on the phone. No upload of raw fixes, routes, visits, attendance or area IDs to analytics/crash reports. `enableAutoSilence` logs `phone_silenced` as `auto_prayer`; nap enable/resume also logs. Do not reuse those setters unchanged for mosque entry/resume or send location-mode activation timestamps under a different name. Keep only current state necessary for operation/recovery, with expiry.

Exclude private shapes, live requests, suppression, exceptions, baselines and recovery journals from **both cloud backup and device transfer**; current rules exclude only asset packs. User settings may be backed up separately, but live audio ownership must not reappear on another phone. Deliberate export and opt-in local diagnostics need preview/redaction. A bounded local diagnostic can record accuracy/age/classification/lifecycle/release reason without a location trail or a stored attendance history; no automatic export.

Play's policy explicitly discusses apps with multiple background-location features while requiring **one qualifying core feature in the declaration**. Select the strongest truthful release feature, demonstrate it, and disclose **all actual background uses**, including travel updates if retained. There is no verified rule forcing travel to lose access for mosque silencing. Approval applies to the reviewed app and is not guaranteed by the existing permission or documentation; this is not blanket approval of any secondary use. Add every used FGS type to Console, with explanation and demonstration video; update store/privacy text. Keep feature-specific opt-ins alongside a coherent all-use disclosure. [Background location policy](https://support.google.com/googleplay/android-developer/answer/9799150?hl=en), [FGS declaration](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en).

## 10. Complete proposed v1 Arabic copy

Use simple Fusha, consistent “we/your phone,” and distinguish the phone's position from the chosen prayer city. Copy was checked with the repository's [Arabic-writing skill](../.claude/skills/arabic-native/SKILL.md). These are planned strings, not resource changes. Show a state only when its evidence supports it; safely format/isolate mosque names and durations. Do not infer attendance or promise that another DND owner will release.

| Purpose | Proposed text |
|---|---|
| Master / section | الإسكات التلقائي |
| Trigger question | متى نُسكت هاتفك؟ |
| Time only | في فترات الصلاة، أينما كنت |
| Time explanation | نُسكت هاتفك خلال الفترات التي ضبطتها لكل صلاة. |
| Combined | في فترات الصلاة وداخل نطاق مسجد |
| Combined explanation | نُسكت هاتفك خلال الفترات التي ضبطتها للصلاة عندما نتأكد من وجودك داخل نطاق مسجد حفظته. |
| Mosque only | داخل نطاق مسجد، في أي وقت |
| Mosque-only explanation | نُسكت هاتفك عندما نتأكد من وجودك داخل نطاق مسجد حفظته، ولو لم تكن الصلاة قريبة. |
| Retained settings | نحفظ فترات الإسكات كما هي، لكننا لا نستخدمها في هذا الوضع. |
| Detection/exit limit | قد يتأخر بدء الإسكات أو إيقافه عدة دقائق، وقد يتعذّر التحقق من موقعك داخل بعض النطاقات. |
| Visit limit, duration filled from final policy | أقصى مدة لهذا الإسكات %1$s. وقد نوقفه قبل ذلك عند تأكيد خروجك من النطاق أو تعذّر التحقق من موقعك. |
| Alarm-safety explanation | خارج فترات إسكات الصلاة، نوقف الإسكات المرتبط بالموقع عند حلول منبّهك في التطبيق. وقد يبقى هاتفك صامتًا بسبب إعداد آخر. |
| Areas | نطاقات المساجد |
| Add / edit / name | إضافة نطاق مسجد / تعديل النطاق / اسم المسجد |
| Save / cancel / delete / undo | حفظ / إلغاء / حذف النطاق / تراجع |
| Local storage, once exclusions verified | يبقى هذا النطاق على هاتفك. |
| Deliberate export | تصدير النطاق في ملف |
| Area enable switch | الإسكات في هذا النطاق |
| Scope explanation | قد يشمل هذا النطاق مساحة خارج المسجد، فيُسكت هاتفك فيها أيضًا. |
| Empty store | لم تحفظ أي نطاق مسجد بعد. |
| Fresh confident no-match | موقعك الحالي خارج نطاقات المساجد المفعّلة. |
| Uncertain | لا نعرف موقعك بدقة تكفي لنتأكد أنك داخل النطاق. |
| Checking | جارٍ التحقق من موقعك… |
| Automatic background access missing | للتحقق من موقعك والتطبيق مغلق، اسمح باستخدام الموقع طوال الوقت. |
| Precise access missing | موقعك التقريبي لا يكفي للتحقق من وجودك داخل النطاق. فعّل الموقع الدقيق. |
| Location disabled | تحديد الموقع متوقف في هاتفك. |
| DND access missing | اسمح للتطبيق بالتحكم في «عدم الإزعاج» حتى نتمكن من إسكات هاتفك. |
| Settings / location action | فتح الإعدادات / فتح إعدادات الموقع في الهاتف |
| Diagnosed battery limitation | قد يتأخر التحقق من موقعك لأن توفير البطارية يقيّد التطبيق. |
| User-started session action | بدء التحقق لهذا المسجد |
| Session limitation | نتابع موقعك حتى تغادر النطاق أو تنتهي المدة. لبدء مرة أخرى، افتح التطبيق. |
| Named active contribution | الإسكات مفعّل في نطاق %1$s |
| Fallback active contribution | الإسكات المرتبط بالموقع مفعّل |
| Stop current visit | إيقاف الإسكات حتى تغادر النطاق |
| Lost-support release, after actual release | أوقفنا الإسكات المرتبط بالموقع لأننا لم نعد نستطيع التحقق من وجودك داخل النطاق. |
| Stopped contribution | أوقفنا الإسكات المرتبط بالموقع. |
| Other DND still observed | أوقفنا الإسكات المرتبط بالموقع، لكن «عدم الإزعاج» ما زال مفعّلًا في هاتفك. |
| Cap/suppression needs rearm | انتهت متابعة هذا النطاق. ابدأ التحقق من جديد لتفعيل الإسكات فيه. |
| Manual fallback | إسكات هاتفك يدويًا |
| Complex editing unsupported | لا يمكن تعديل هذا النطاق هنا لأن شكله معقّد. |
| Crossing edges | تتقاطع خطوط النطاق. حرّك النقاط حتى لا تتقاطع. |
| Excessive area | هذا النطاق أوسع من المسموح. ضيّقه حول المسجد وساحته ومرافقه. |
| Invalid saved mode | تعذّر قراءة وضع الإسكات المحفوظ. اختر الوضع من جديد. |
| Unsupported system | هذا الوضع غير متاح على إصدار النظام في هاتفك. |
| Continue / later | متابعة / لاحقًا |

Proposed **single all-use background disclosure**, conditional on both travel and mosque background uses remaining in the release:

> يستعمل التطبيق بيانات موقعك حتى عندما يكون مغلقًا أو غير مستعمل، لميزتين تختار تفعيلهما: التحقق من وجودك داخل نطاق مسجد حفظته وتشغيل الإسكات الذي اخترته، وتحديث مدينتك ومواقيت الصلاة حين تسافر. نقارن موقعك بنطاقات المساجد على هاتفك، ولا نرسل سجلّ وجودك فيها.
>
> في الصفحة التالية اختر «السماح طوال الوقت».

Buttons: **متابعة** and **لاحقًا**. Match final wording to the actual permission screen/OS and shipped uses. Optional online-map requests require their own accurate explanation; this disclosure does not claim that all existing network location work sends nothing anywhere. No strings for submissions, published catalogs or conflicts ship in v1.

## 11. Implementation sequence and meaningful validation

The original 2026-10-27 revision performed static verification, numerical geometry analysis and official-source retrieval only. The separately authorized 2026-10-28 Stage 0 now has an isolated debug prototype, 46 passing unit tests and measured Pixel 6 checks; see the [report](MOSQUE_SILENCING_STAGE0_REPORT_2026-10-28.md) for the exact passes, failed attempt, corrections and remaining gaps. Production integration, real-mosque field reliability, sustained battery cost and the other offered devices remain unproved.

| Stage | Work | Completion gate |
|---|---|---|
| 0 — bounded feasibility | Use a debug-only synthetic/private area fixture, not a production catalog or collection restart. Prototype entry, long-visit holding/departure, legal service starts, explicit rule ownership/user stop, alarm release and crash recovery. | A written go/no-go names API/OEM support, measured indoor fix yield, holding/exit failures and battery/service cost. Validate the provisional parameters; the three questions in Claude's lean list are not the entire safety gate. |
| 1 — pure policy and coordination | Trigger evaluator, complete states, durable minimal requests, one serialization boundary, versioned platform adapter, all writers/readers/exception decisions. | Truth table, TIME_ONLY bridge/wake parity, owner overlap, stale-event and crash/permission tests pass. No independent setter/force-normal bypass remains. |
| 2 — location execution beta | Typed observation service, bounded saved-area geofences, explicit start/permission paths, recovery watchdog, user stop, readiness and quiet notifications. | Existing-inside/window-start and later-arrival paths work in the offered environment; permission/process/fix loss has the measured bounded behavior; system stop/snooze is respected. |
| 3 — usable private editor and privacy | Draw/review/save/enable, accessible coordinates/vertices, draft/undo, validated storage, optional export; choose permitted map provider only if required by demonstrated usability. Separate research asset from production packaging in this future work. | The user can define the intended area without mistaking GPS scatter for a boundary; no unintended topology/expansion, evidence remains private/durable, backup/transfer and telemetry exclusions verified. |
| 4 — owner acceptance and release review | Focused logic tests plus authorized silent physical-device checks and an owner field checklist, with a small opt-in local diagnostic. | Measured supported behavior is acceptable; unresolved source/packaging, Play, ownership or release failures block release. Unmeasured cases are not passes. |

Tests should check behavior and authority, not exact UI strings or mirror the implementation:

- **Policy/time:** truth table, readiness loss, missing/invalid trigger, mid-window entry/exit, uncertain visit at window opening, overlaps, Friday/Eid/Ramadan, DST/time-zone changes and overnight windows. Test a FROM_NOW wake during an auto-active 45-minute bridge, including next-occurrence dismissal; separately test a genuinely null conflict lookup and inherited awake-check eligibility/historical cancellation.
- **Geometry/presence:** holes, touching/merged boundaries, multipart/concave shapes, circle coverage, old/mock/repeated/out-of-order fixes, long uncertain-but-live versus no-fix cases, failed acquisition/retry, cap expiry without immediate reentry, suppression/rearm, stale generations and effective geometry revision. Do not use a geofence circle as the expected polygon answer.
- **Owners/platform:** every matrix row and overlap, first baseline/nap transfer, master disable/cancelAll/force-normal replacement, system disabled/removed/snoozed rule, ringer coupling, actual NONE/ALARMS/PRIORITY behavior, permission loss/reboot/process crash, unavailable exact access and delayed watchdog. No test may clear an independent owner to manufacture success.
- **Device/field, only when separately authorized:** on the owner's available Pixel/OnePlus and explicitly supported versions, test arrival while closed, initial-inside, pass-by, a visit longer than all hold bounds, locked-screen departure, network/provider loss, approximate permission, battery saver/Doze and recovery. Debug-only location injection can exercise states; release builds retain mock rejection. A debug injection is not a measurement of indoor GPS reliability.
- **Silent validation:** follow the recorded requirement for no sound **and no vibration** during device checks; verify those settings before proceeding. DND/ringer/policy observations do not prove physical audio delivery, which must not be claimed without a separately authorized suitable check.
- **Usability/privacy:** RTL, Western digits/LTR negative coordinates, actual-size and large-font layout, vertex accessibility/TalkBack when performed, store-empty/no-match/uncertain distinction, all denial paths, no raw fix/area/visit analytics, backup/transfer exclusions and deliberate export preview. Do not infer an empty country from travel alone.

Keep validation proportionate: report actual trial counts and failures, representative latency/longest observed release, service duration and battery comparison with TIME_ONLY. No statistical national/OEM certification or impossible guaranteed timeout is implied. Zero observed owner-isolation failures is required in the specified checks; a detector that never activates cannot pass. Do not claim actual sound, field reliability or accessibility from compilation/static tests.

## 12. Deferred work and decisions remaining

Defer shared catalogs, national coverage, contributions/moderation, public website/accounts, signed downloads/replay protection, general imports, advanced topology/diff tools, navigation/online search, offline national basemaps and extra movement permissions. Original later-phase considerations remain in the archived plan; they are not v1 implementation requirements. Per-mosque prayer timetables are excluded by the recorded prayer-method scope, not merely a later feature.

The bounded [official product comparison](../work/mosques/silencing-plan-verification-20261027/scope-copy-findings.md) confirms time-window silencers, PrayZone's saved-mosque arrival claim, and Apple's user-configured location Focus schedules. These support explicit selected areas/manual control; listing claims do not prove exit reliability or polygons. Do not carry forward unsupported popularity rankings, universal absence claims, Samsung radius figures, vote counts or snippet-only complaint prevalence.

Remaining decisions are concrete: source/packaging and any public-history action; a permitted map provider if the private editor needs one; the actually demonstrated API/OEM envelope; measured hold/entry/release parameters; and the release's truthful Play declaration/approval. There is **no forced travel-versus-mosque policy choice** and no permission here to remove public data.

Stage 0's current result is NO-GO for the rest of the plan. The Pixel follow-up closes the measured actual Modes-stop, permission-loss and short forced-Doze cases; two successful short expiry alarms do not establish quota exhaustion or a long-idle bound. The report records reboot recovery separately. Next work remains a few known-area field traces, sustained energy comparison and the unresolved OS/OEM/long-idle support limits, followed by an updated decision. The component checks do not authorize production integration, later stages, dataset cleanup, publication, messages or resumption of collection.
