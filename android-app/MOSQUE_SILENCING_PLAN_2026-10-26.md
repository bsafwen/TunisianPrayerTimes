# Mosque-aware automatic silencing — implementation plan

**Prepared: 2026-10-26. Revised: 2026-10-31. Owner decision: GO for implementation of the two-mode, existing-window feature. The owner explicitly deferred device/field, energy and wider-platform acceptance until after implementation; the previous Stage 0 NO-GO is historical evidence, not the current authorization gate. See the [implementation record](MOSQUE_SILENCING_IMPLEMENTATION_2026-10-31.md) for delivered scope and validation.**

The feature keeps **TIME_ONLY** and adds **TIME_AND_MOSQUE**, using enabled polygon areas including the forthcoming Tunisia polygons and private areas. Mosque location checks run **only inside the already configured prayer-silencing windows**. Use an offline polygon engine, a bounded presence state machine, one coordinator for all app silence requests, and a correctly typed location foreground service during permitted acquisition and observation. The combined mode never silently falls back to time only.

**Owner scope decision, 2026-10-31:** remove mosque-only operation. Reuse the existing enabled prayer windows and their configured start/end times without adding a preparation period, extending a window, or checking between disjoint windows. Mosque geofences and provider requests are active only during eligible open windows. Existing travel/locality updates remain a separate opt-in and cannot authorize mosque checks outside those windows. The October 28 report and archived pre-change diagnostic remain historical evidence for the broader prototype. The owner subsequently authorized adaptation of the isolated Stage 0 probe; the current report records that follow-up without production integration.

**Owner clarification, 2026-10-28:** Tunisia polygons will be ready after the plan; a few existing polygons may be used for testing, and this work must not focus on shipping them. The earlier private-only v1 assumption therefore does not exclude the forthcoming Tunisia input. Stage 0 uses three exact test shapes and synthetic cases; catalog readiness, rights and shipping are not go/no-go gates for this stage. Collection, publication and shared-service work are outside this task. Real-mosque field validation remains pending at the owner's explicit choice.

This revision adopts verified corrections rather than all of the review's recommendations. In particular, Play's single-feature rule concerns the **declaration**, not a one-feature quota for the app; a coarse geofence EXIT does not replace polygon evidence; GPS samples at one position do not establish mosque boundaries; and silent watchdogs must not all become alarm-clock alarms.

The [verification and finding dispositions](../work/mosques/silencing-plan-verification-20261027/verification.md) link the code audit, recomputed geometry, provenance/public-distribution checks and freshly fetched official sources. The [previous plan](../work/mosques/silencing-plan-verification-20261027/plan-before.md), [Claude review snapshot](../work/mosques/silencing-plan-verification-20261027/claude-review-before.md), and [earlier debate](../work/mosques/silencing-plan-debate-20261026T154404Z/judgment.md) remain evidence. Claude's original review is unchanged.

## 1. Verified baseline and terminology

The review used the working tree, including existing uncommitted background-location changes. These are integration prerequisites to reconcile during future implementation, not commits made by this task. Current source hashes are in [code-source-snapshot.json](../work/mosques/silencing-plan-verification-20261027/code-source-snapshot.json); the former snapshot is stale for `MainScreen.kt` and `AndroidManifest.xml`.

| Fact | Verified baseline and implication |
|---|---|
| Android support | minSdk 26, targetSdk 36; play-services-location 21.3.0. Prototype the combined mode on API 29+; API 26–28 retains existing time/manual behavior. This is a scoped DND-adapter choice, not an Android location limitation. Promote only API/OEM combinations that pass ownership and recovery checks. |
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
- **Confirmed visit:** an entry established from distinct fresh fixes acquired during the current eligible window session, with a finite holding/recovery contract. Its authority ends when that session's eligible windows close. It does not prove indoor position, prayer attendance, ownership or activity on a particular floor.
- **Request:** one app feature's desired silence contribution; **observed DND** is the phone's aggregate state and can differ from it.
- **Acquisition deadline, no-fix deadline and ambiguity hold** are separate bounds, each clipped by the existing window end as defined in section 3.

Keep the intentional scope of any accepted area, including disclosed courts and surrounding space. Do not silently tighten accepted rectangles to roofs. Closure alone does not invalidate an otherwise qualified mosque; an actual change of use or identity conflict needs its own assessment. Historical approvals and held candidates remain untouched.

## 2. Exact behavior and mode selection

Persist `AutoSilenceTrigger`: **`TIME_ONLY` or `TIME_AND_MOSQUE` only**. The existing `SilenceMode` already means endpoint/duration choice and must not be reused. Keep the master automatic switch independent.

Let **T** mean an existing enabled, non-dismissed prayer silence window currently applies, using its configured start/end and complete scheduler policy. Let **P** mean a currently valid confirmed visit established during the current window session under section 3. Assume master enabled, feature readiness satisfied, and no applicable suppression or higher-priority owner:

| T | P | TIME_ONLY | TIME_AND_MOSQUE |
|---|---|---|---|
| False | Not evaluated; no live visit | No window request | No request and no mosque location checks |
| True | False / not yet confirmed | Request | No request; permitted in-window checks only |
| True | True | Request | Request |

TIME_ONLY also preserves the existing bounded **already-active** 45-minute bridge, described precisely in section 6. That bridge cannot initiate silence, does not supply T or location-check authority to the combined mode, and is not limited to locality replanning.

“No request” releases only this automatic source. It never means force normal ringer mode, clear manual silence or turn off another system/app DND rule.

1. **TIME_ONLY:** preserve existing prayer settings, delayed starts, fixed/relative endpoints, occurrence dismissals, overlaps, place time zone, Ramadan/Eid/Jumu'a and overnight behavior. No new location requirement or upgrade prompt.
2. **TIME_AND_MOSQUE:** both conditions must hold together. At the start of new eligible window coverage after a gap, begin fresh acquisition even if the person was already inside the mosque; silence starts only after confirmation. Overlapping/adjoining windows may continue a still-valid session as defined below. Entry during an open window can activate; a confirmed exit or the end of all supporting open windows releases this request. An earlier visit or uncertain fix cannot supply presence at a later disjoint window's start.
3. **Combined-mode coverage:** activate only within user-enabled saved areas. Missing coverage means no supported match, not that no mosque exists. No automatic switch to TIME_ONLY abroad, on no-match, on degraded GPS or after permission denial.

### Existing-window boundary

- Derive eligible intervals from the same scheduler/planner and per-prayer settings used by TIME_ONLY, including delayed starts, configured offsets/endpoints, dismissals, Ramadan, Eid, Jumu'a, place time zone and overnight windows. Use **start-inclusive/end-exclusive** membership of actual containing windows after occurrence-dismissal filtering. The future-prayer fallback in `currentSilenceWindowPrayer()` and the unfiltered planner end returned by `currentSilenceWindowEnd()` cannot alone define combined-mode eligibility or its deadline. This is the user's existing silencing interval, not a new interval around the adhan.
- Before any mosque provider request, geofence registration, retry, fix evaluation or activation, recheck master enabled, combined mode, prerequisites and T. Outside an eligible interval, perform no mosque acquisition, observation, cached-fix evaluation or departure tracking. A future schedule may be armed without acquiring location.
- There is no pre-window preparation allowance or bridging across a gap. The time-only 45-minute bridge, future-prayer lookup and pre-adhan travel check do not open a mosque-check interval. Pre-window fixes cannot confirm presence when a window starts, even if they would otherwise meet the age limit.
- Overlapping or exactly adjoining eligible windows may share one continuous observation session while support remains valid. An individual end callback releases only its expired support; continue only if another existing eligible window covers now. Never stretch a session across a positive gap to reach a later window.
- When the last supporting eligible window ends or is dismissed/disabled, invalidate the observation generation, cancel provider requests/retries and remove mosque geofences, clear live presence, and release the combined DND request. Late fixes/events cannot restart it. The next disjoint window requires fresh acquisition.
- Outside a window, schedule management and scoped DND cleanup/recovery may still run without requesting or evaluating location. Actual release remains subject to the measured Android execution limits in section 4; a pending cleanup journal never authorizes continued location checks.

| Event | Required transition |
|---|---|
| Eligible coverage opens after a gap, or combined mode is selected during a window | Start a new permitted acquisition session; no combined request until fresh strict entry succeeds. |
| Fresh strict inside evidence plus entry dwell inside the active window | Establish a visit, then submit the combined request while T remains true. |
| Fresh ambiguous evidence during a confirmed visit | Use bounded asymmetric holding; do not treat an inside-test failure alone as exit. |
| Two fresh confident-outside fixes | End the visit and release only dependent requests. |
| Approximate-only permission, precise-to-approximate downgrade, required access revoked or location switched off | Immediately invalidate affected location requests on reconciliation; show needs setup. There is no uncertainty grace for a known lost prerequisite. |
| DND access lost, master off or mode inactive | Invalidate the affected request; observe/report if the platform contribution cannot be changed without restored access. |
| Area disabled/deleted/revised or storage unavailable | Reevaluate affected visits against the current enabled geometry generation; preserve unrelated owners. Never fall through to an old larger shape. |
| Last supporting eligible window ends, is dismissed or is disabled | Release combined request even while inside; stop mosque observation/geofences and clear live presence. A nearby future window is not authority to continue. |

A missing trigger preference defaults to TIME_ONLY. An explicit unsupported/malformed value, including a removed experimental mode, is inactive and asks for a valid choice; it must not silently become TIME_ONLY. Defensive parsing is not a stored-data migration project. The recorded development-phase preference removes the proposed production migration subsystem, not runtime cleanup, reboot safety or input validation. Verify release state before a future implementation relies on the no-real-users assumption. A test phone with an old implicit DND mode may need that mode ended once in system Settings.

Mode changes are serialized. Transfer between already-valid requests without an audible gap; when changing from TIME_ONLY to an unconfirmed combined mode, stop the old time request. Show checking only during an eligible open window, waiting outside it, and needs setup when prerequisites are missing. Do not keep time-only silence secretly while acquiring. Offer deliberate manual silence. Disabling the master releases automatic sources without cancelling manual/nap ownership.

## 3. Presence geometry, states and provisional bounds

### Entry and the measured geometry constraint

Keep the current strict entry gate initially: reject mock fixes; missing/nonfinite/nonpositive accuracy; accuracy above 30 m; unknown monotonic age or age over 30 seconds; and any fix sampled before the current permitted window/acquisition session. The whole reported accuracy disk **plus 2 m** must lie inside the effective area and outside its holes. Reported accuracy is a probabilistic 68% radius, not a containment guarantee. Two temporal samples are distinct, not statistically independent under indoor multipath.

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

Use the following as an explicit **stage-0 candidate**, not validated release defaults. Every deadline is clipped by the existing eligible window coverage. The candidate lengthens ambiguity tolerance within that coverage without claiming that a stationary phone will always remain silent:

| Parameter | Initial prototype value / invariant |
|---|---|
| Entry dwell | Two distinct strict-inside fixes at least 15 seconds apart within one acquisition episode. Reject repeated/out-of-order monotonic timestamps. |
| Acquisition | At most 90 seconds total, including service/provider start, entry dwell and at most one short retry, and never beyond the existing window end. Later attempts need another legal in-window start event or deliberate in-window user action. If the remaining window cannot fit fresh entry evidence, remain inactive for its remainder. |
| Confident exit | Two distinct outside fixes at least 20 seconds apart, with an initial extra 5 m release margin. |
| Normal visit observation | Request about one update per 60 seconds only while an eligible window is open, with a bounded higher-accuracy check near an edge or after an in-window exit hint. Cadence/accuracy are requests, not guaranteed delivery. |
| No usable observation | Earlier of 5 minutes from last usable delivered observation and the existing window end. A running service or heartbeat without usable fixes is not proof of presence and does not renew this deadline. |
| Ambiguity hold | Earlier of 15 minutes from last strict-inside support and the existing window end. Fresh ambiguous fixes may demonstrate liveness but do not move this deadline. Confident outside evidence still exits earlier. |
| Outer session bound | The existing eligible window coverage is the outer bound; there is no independent three-hour visit policy. Only another existing window that actually covers now may continue support. No location work runs across a gap. |
| Suppression/rearm | “Skip this prayer period” and eligible wake exceptions dismiss the supporting occurrence(s) across re-entry/replans. Presence uncertainty expiry requires deliberate fresh in-window recheck, or confirmed departure followed by new entry during a remaining eligible window. At the end of window coverage clear live presence/rearm state; preserve occurrence dismissals for their existing scheduler lifetime. No six-hour departure-monitoring policy is needed. |

For this prototype, a **usable holding observation** is a distinct, nonmock, precise-permission fix sampled and delivered in the current permitted window session, with valid monotonic age at most 30 seconds and finite positive reported accuracy at most 100 m. Only strict-quality fixes at or below 30 m can establish confident inside/outside. A 30–100 m observation demonstrates liveness only: it cannot start a visit, prove an exit or renew the 15-minute ambiguity deadline. The 100 m ceiling is another provisional measurement parameter. Service heartbeats, stale/repeated fixes and worse observations do not reset the five-minute no-observation timer. No observation extends the configured prayer-window end.

Before clipping to the window, the ambiguity budget must exceed at least three requested observation intervals; acquisition must fit startup, dwell and the permitted retry; exit dwell must be shorter than the observation/ambiguity bounds. A nearer window end takes priority over completing any dwell. Do not weaken entry, enlarge areas or renew deadlines from coarse/ambiguous callbacks to make a pilot pass. The 15-minute/5-minute candidates can still release during worship inside a long configured window; measure that failure as well as silence after departure. Very long user-configured windows still need sustained energy measurements. If the observed tradeoff is unacceptable on an offered device, do not release the combined mode there. A richer movement-assisted policy is deferred rather than adding activity-recognition permission or assuming every phone has a significant-motion sensor.

| State | Silence authority and exit |
|---|---|
| IDLE / WAITING_FOR_WINDOW | No combined request. Outside eligible windows there are no mosque geofences/provider requests or presence evaluations. Inside a window, only permitted bounded triggers/checks may start acquisition. |
| ACQUIRING | No combined request. Fresh strict entry succeeds, acquisition deadline/failure returns to IDLE/needs manual action, or prerequisite/window loss stops the session. |
| VISIT_CONFIRMED | Presence is valid only while T remains true. Refresh support from valid in-window evidence; leave on confident exit, window end, stop or prerequisite loss. |
| VISIT_UNCERTAIN / EXITING | Only the existing in-window visit can persist. Retain clipped deadlines; finish exit on outside dwell; restore confirmed state only with strict support. Window end takes priority. |
| SUPPRESSED | No combined request for dismissed occurrence(s), including after process recreation. If no other eligible window remains, remove mosque monitoring and wait for a later eligible window. No departure tracking outside windows. |
| INACTIVE_NEEDS_SETUP / REARM | No combined request. Explain missing permission/support or expired observation/ambiguity bound. Allow fresh recheck only inside an eligible window; otherwise show the waiting state. |

No-fix or ambiguity-hold expiry moves the unresolved in-window visit to needs rearm. A fresh strict-inside callback by itself must not immediately restart it. Rearm requires deliberate foreground action with fresh entry evidence, or confirmed departure followed by new entry, while an eligible window still covers now; the stopped observation service cannot erase this barrier. The barrier does not require tracking a visit across an out-of-window gap. A later disjoint eligible window starts a new acquisition session, with no inherited presence and with existing occurrence dismissals still respected.

Persist only current desired state/deadlines, supporting prayer occurrence(s), suppression and the active window/session generation, with a boot marker and geometry generation. Use monotonic age/dwell; never compare elapsed time across boots. Reboot or process recovery cannot recreate a visit from an old “inside” flag. Recompute current eligible window membership first; outside it, only invalidate/clean stale ownership. Reject callbacks from obsolete window, mode, permission, boot, request or geometry generations.

## 4. Execution, permissions, geofences and release recovery

Choose a **location foreground service** for the proposed locked-screen acquisition/visit cadence during an eligible window. Declare the location service type and `FOREGROUND_SERVICE_LOCATION`, with a quiet ongoing notification, an occurrence-scoped stop action and lifetime clipped to the existing window coverage. Set finite provider-request durations and geofence expiration no later than the remaining permitted window lifetime, in addition to explicit cancellation and generation guards; a missed end callback must not leave an indefinite registration/request. Reconcile earlier closure caused by settings, dismissal, clock or schedule changes. The specialUse guard is not a substitute. A single visible-screen diagnostic need not start a service. Outside eligible windows, no new mosque observation service/provider request is authorized; measure native request/registration teardown separately from logical invalidation and do not claim instantaneous system cancellation when execution is unavailable.

Visible user action, geofence transitions and an exact alarm completing a user-requested action are documented candidate start routes, each additionally gated by an eligible window covering now. The existing window-start `setAlarmClock` callback is the concrete route to test for fresh acquisition while already inside. In the combined mode, this callback opens checks without immediately calling the legacy unconditional silence setter; confirmation is still required. No earlier location-start alarm is added. **FGS-start exemption and resource permission eligibility are separate checks.** Android 14+ can throw at service creation; background-created location observation needs the appropriate background grant as well as runtime precise access/location enabled. Catch permission/start failures and remain inactive. Arbitrary workers, retry timers and reboot callbacks do not inherit a start exemption. [FGS restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [location type](https://developer.android.com/develop/background-work/services/fgs/service-types#location).

For v1, bound the number of automatically monitored areas to the available capacity within **100 geofences per app per device user**, accounting for other registrations. Register the selected enabled areas within that cap only while eligible window coverage is open; do not add national relocation infrastructure. If the cap is reached, require a user choice of which areas to monitor and keep the others saved. A future larger working set needs its own visible coverage contract.

- During an eligible window, register enclosing circles with ENTER/EXIT, `INITIAL_TRIGGER_ENTER`, explicit responsiveness (initial request: 60 seconds), and optional DWELL with an initial 60-second loitering delay as a possible bounded retry. DWELL is not an extra compulsory delay after strict entry has succeeded, and none of these values guarantees delivery time.
- At the first existing window start after a gap, request fresh acquisition through the eligible start route and register the circles; do not wait for a new physical ENTER crossing. Someone already inside must be confirmable from fresh in-window fixes. An overlapping/adjoining occurrence may continue valid presence and update finite request/registration lifetimes only once its existing window covers now. A registration's initial-inside event is another trigger, not presence evidence. Entry later in the open window may trigger bounded acquisition.
- Remove mosque registrations and cancel observation/retries at the end of the last supporting eligible window. Keep no cheap mosque registration between disjoint windows. Ignore late ENTER/EXIT/DWELL or provider callbacks after invalidating their window generation, without requesting location.
- After reboot, `GEOFENCE_NOT_AVAILABLE`, app start or package replacement, reconcile desired registrations against the current window first: outside it, ensure removal; inside it, register only with valid prerequisites and acquire only through an eligible start route. A reboot callback does not itself authorize a location service start. Official loss cases include reinstall/app-data clear/Play-services data clear; data clear also removes saved areas, so no old configuration is presumed to survive. Play-services upgrades and ordinary process restarts are documented as automatically restored, not mandatory blanket-loss cases.
- Geofence EXIT requests fresh polygon exit evaluation only while an eligible window is open. It cannot directly substitute for the confidence contract. Failed acquisition may retry once inside its remaining permitted episode, on a later qualifying in-window event, or from an in-window user action; it must not become an infinite timer loop.

Geofence guidance describes average background delays around **2–3 minutes** and possible longer delays, including around six minutes after prolonged stillness. Acquisition adds delay; these figures are not upper bounds. Coarse alert radii commonly need 100–150 m, which is why circles only trigger polygon checks. [Geofencing guide](https://developer.android.com/develop/sensors-and-location/location/geofencing).

Ordinary background fixes may arrive only a few times per hour. Select explicit high-accuracy acquisition, fresh-entry maximum historical age zero, and bounded repeated updates during the service. Validate each result independently. Balanced priority does not guarantee a result fails, and a default ten-second-old cached result would not itself violate the old 30-second ceiling; the reason for explicit settings is a clear acquisition contract. Existing travel thresholds remain separate. [Background limits](https://developer.android.com/about/versions/oreo/background-location-limits), [FLP](https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient), [request builder](https://developers.google.com/android/reference/com/google/android/gms/location/CurrentLocationRequest.Builder).

A **deliberately user-started recheck inside an eligible window** may start a correctly typed service from visible UI and continue after Home/screen-off with valid foreground location access, stopping no later than that window coverage ends. Screen lock alone does not end its access. Outside a window, show when checks can next run and offer ordinary manual silence without location observation. A recheck cannot silently restart on another arrival without an eligible in-window route, and it is not a guaranteed Play-policy loophole. On non-GMS devices, initially offer only measured foreground/recheck support within the same window boundary; `LocationManager.addProximityAlert` remains a deferred experiment, not demonstrated background parity. [Location access](https://developer.android.com/develop/sensors-and-location/location/permissions).

Request precise foreground permission first, then one prominent all-use background disclosure and Settings access when the user chooses automatic operation while closed during the configured windows. Keep separate travel/mosque opt-ins. Setup/readiness checks may inspect settings outside a window without acquiring location. Readiness covers DND access and rule status, precise/background access as appropriate, enabled location, effective saved areas, valid recovery mechanism/exact access, notification visibility and actual battery restrictions. Explain Wi-Fi/scanning/network-location/connectivity dependencies where detectable; internet through Wi-Fi can suffice, so do not demand mobile data universally. Offline polygon calculation is not a guarantee of offline background discovery.

Use an in-session timer plus **one consolidated one-shot `setExactAndAllowWhileIdle` release backstop**, armed for the earliest applicable observation/ambiguity or existing-window end, where this user-visible critical release justifies exact access. Check `canScheduleExactAlarms`, share the app's idle-alarm allowance, and reconcile at every callback/resume. If the necessary recovery route is unavailable, do not begin an unattended automatic request; offer manual silence or a supported in-window alternative with its actual limits. Retain scoped cleanup authority until the owned contribution is verified released, even if the window has ended; cleanup needs no GPS. Revoking `SCHEDULE_EXACT_ALARM` stops the app and cancels its future exact alarms; an existing DND contribution can therefore outlast its logical window/deadline until a supported reconciliation runs. Permission observation cannot guarantee cleanup after that stop. This strategy is subject to stage-0 recovery measurement.

**Measured Pixel 6 limit, 2026-10-28:** revoking exact-alarm access through system Settings killed the diagnostic process and removed its pending expiry alarm while its DND rule remained active. Explicit scoped reconciliation removed that rule. Persist an observed loss of a required capability before considering permission return; a returning grant must not revive the previous request. A loss and restoration that occur entirely while the app cannot observe them cannot be inferred from the current grant alone. Force-stop likewise has no finite automatic-release guarantee: the supported fallback is the system Modes stop control or reconciliation when execution resumes, without replaying a global filter/ringer baseline. This is a required support limitation, not an accepted field release contract.

The actual reboot test advanced the Pixel boot counter from 259 to 260. A credential-encrypted, post-unlock boot receiver removed only the old diagnostic DND rule before any Activity launch. The separate old-boot location journal remained until app-launch reconciliation and was then rejected despite its elapsed deadline being numerically far ahead of the new uptime. Preserve boot identity and idempotence; do not promise pre-unlock cleanup from a credential-encrypted journal or restart observation from a boot callback. Two forced-Doze expiry deliveries succeeded after ordinary process death, but they do not establish a long-idle or shared-quota delivery bound.

Do not use `setAlarmClock` for every internal timeout: its purpose is a highly visible alarm-clock event, including system next-alarm UI, with higher resource cost. Idle-allowed alarms have shared quotas/batching; the documented nine-minute rate limit is **not a nine-minute delivery guarantee**. Do not turn alarms into location polling. Do not copy the worker's battery-not-low constraint onto mandatory release. Force-stop/OEM restrictions can prevent all cleanup execution; next-launch reconciliation and clear system stop controls remain necessary. The review's OnePlus “75% delay” figure is unverified here. [Alarm guide](https://developer.android.com/develop/background-work/services/alarms/schedule), [AlarmManager](https://developer.android.com/reference/android/app/AlarmManager), [Doze](https://developer.android.com/training/monitoring-device-state/doze-standby).

## 5. One coordinator and safe platform ownership

Introduce `SilenceRequestCoordinator` with **one serialization boundary used by every entry point**. The current scheduling dispatcher serializes some UI/sync work; scheduler/controller locks are different locks and do not already cover the whole lifecycle. The coordinator owns compact durable requests, first-owner baseline handling where applicable, suppressions, alarm decisions, deadlines and idempotent platform reconciliation. Save desired state before platform effects; handle a crash between the effect and bookkeeping. Do not build a general event-sourcing framework.

On Android 15+ for target 35+, legacy `setInterruptionFilter` affects an implicit package rule rather than directly restoring global DND. An aggregate observed filter is never a private baseline to replay. On API 26–34 the legacy global path has different behavior. [Android 15 change](https://developer.android.com/about/versions/15/behavior-changes-15#dnd-changes).

For the API 29+ prototype, use explicit app-owned `AutomaticZenRule` contributions per owner class: prayer, mosque, manual, silence-until-alarm. Give each a clear Arabic name and a valid enabled/exported configuration activity with the required action/localized metadata (or an appropriate condition provider); the current manifest has neither for such rules. A configuration activity/state updates are API 29; general enabled/disabled/removed status broadcasts start at API 30; rule-state getter, activated/deactivated status detail, builder and manual-invocation control require API 35. Choose supported version-specific constructors/types rather than calling newer APIs unguarded. Intended NONE behavior must be explicit. [NotificationManager](https://developer.android.com/reference/android/app/NotificationManager), [AutomaticZenRule](https://developer.android.com/reference/android/app/AutomaticZenRule).

The evaluator must gate TIME_AND_MOSQUE **before** submitting one combined automatic request to the mosque rule, and recheck its current window generation at the serialized platform effect. Independently activating prayer and mosque rules would merge as OR and violate the AND contract. TIME_ONLY uses the prayer rule; TIME_AND_MOSQUE uses only the combined rule when both predicates hold. There is no independent presence-only automatic request. Stable rule IDs/condition URIs and state updates are required: on API 29–34, `updateAutomaticZenRule` deactivates an active rule not backed by a condition provider, so routine metadata/definition refresh must not create a hidden end/restart cycle.

**Do not write global ringer mode in the new owned-rule path.** AOSP 14/15/16 couples external ringer changes to global DND: changing to SILENT from another ringer state can enable DND, and NORMAL/VIBRATE while DND is active can clear manual DND and snooze automatic rules. This is verified reference-platform behavior to check on OEMs, not a claim that every setter call on every phone changes DND. Validate effective call/notification/media/alarm behavior instead of introducing a ringer “fallback” that defeats ownership.

Respect system disabled/removed/snoozed rules and later user changes. Ordinary unchanged explicit-rule TRUE updates respect snooze; repeated legacy NONE calls, rule recreation and artificial FALSE/TRUE cycles can defeat it. Automated callbacks must not claim `SOURCE_USER_ACTION`. `setManualInvocationAllowed(false)` on API 35 prevents inappropriate manual activation, but is not a snooze-preservation solution. API 35's state getter alone is insufficient: Android 15 reference code can return raw condition state without snooze state, and Android 16 behavior is flag-dependent. Reconcile supported state/status events and persist explicit app suppression; unknown override state must not trigger a fresh activation or rule replacement. API 29–34 lacks equivalent observation, so require a demonstrated safe lifecycle before advertising the combined mode there. Multiple rules do not by themselves solve alarm exceptions or user intent.

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
- Existing place-change dismissal pruning retains an occurrence only if the resolved prayer time stays within 45 minutes. Preserve TIME_ONLY semantics; the combined mode needs explicit stable occurrence suppression and current-window reconciliation across replans rather than assuming this pruning already guarantees them.

### Policy chosen for the combined mode

This is a design choice for the combined mode, not a claim about current code or a requirement imposed by Android:

| Owner | Ordinary wake / awake-check | Eligible automatic exception |
|---|---|---|
| Manual | Preserve suppression while effective. | Do not remove manual ownership. |
| Silence-until-alarm / nap | Preserve matching-alarm release, pending IDs, original baseline and scoped pause/resume. | Preserve the same scoped lifecycle. |
| TIME_ONLY | Preserve the complete behavior above, including planned-window historical awake-check handling. | Preserve payload/runtime qualification, bridge fallback and occurrence dismissal. |
| TIME_AND_MOSQUE | Suppress only when a current combined request/event decision applies inside an eligible window; planned time alone does not prove mosque silence. Outside those windows this mode contributes no suppression. | Dismiss supporting occurrence(s) and release their combined request before permitted playback; re-entry/replan must not undo dismissal. No automatic FALSE/TRUE resumption after the alarm. |
| Independent system/app DND | Observe actual effective restrictions. | Never remove another owner's contribution or promise playback through it. |

The combined mode follows the existing prayer-silence periods and has no separate alarm policy outside them. Test origin, receiver, playback, escalation and follow-up decisions inside those periods. An awake-check gets no new authority of its own; retain its originating event's decision. A permitted wake still depends on other owners and actual platform audio state; external alarm apps are not automatically observed by this policy.

Use **“Skip this prayer period”** for the combined-mode stop action. Dismiss the supporting occurrence(s) through the existing prayer-period mechanism, release their combined contribution and stop monitoring if no other eligible window remains. The same dismissed occurrence cannot reactivate on re-entry, new fixes, process recreation or replanning; departure is not needed to keep it dismissed. A distinct non-dismissed occurrence follows its own existing window, with fresh acquisition if there was a gap. Do not promise “until you leave” or track departure between windows. A coarse EXIT within an eligible window remains a reevaluation hint. Uncertainty expiry cannot immediately restart the same in-window visit. Manual silence survives mosque exit; an unrelated or obsolete prayer-end callback cannot clear a still-supported combined request. No ordinary restore/force-normal fallback may bypass these owners.

## 7. Complete integration checklist

Paths are relative to the repository root; detailed current line evidence is in the code audit. Both starting and reading silence must use the coordinator's meaning.

| Source / consumer | Required work in a later implementation |
|---|---|
| `SilenceScheduler`, `SilenceReceiver` | Reuse existing start/end/replan policy; in combined mode open fresh acquisition at the actual start and end mosque monitoring when no eligible window covers now. Handle `cancelAll`, master/permission loss, obsolete end callbacks, midnight and official dates. Pre-adhan `ACTION_DELEGATION_CHECK` remains travel/locality-only and cannot initiate mosque checks. |
| `SilenceModeController`, `ManualSilenceScheduler`, `ui/MainScreen` | Automatic/manual/timed controls, manual expiry, restore button and readiness paths; release only owned requests. |
| `wake/WakeAlarmScheduler`, `nap/NapSilenceController` | Matching alarm, pending IDs, activate/pause/resume/remove and first-owner baseline; nap must never save a future mosque's NONE/SILENT as the original baseline. |
| `wake/WakeAutoSilenceConflictController`, `WakeAlarmReceiver`, `WakePlaybackService`, `AwakeCheckSilencePolicy` | Runtime/payload eligibility, receiver/playback recheck, follow-up/escalation and suppression lifecycle. |
| `ScheduleRefreshCoordinator`, application official-date callback, `BootReceiver`, `SilenceVerifyWorker` | One transaction boundary across lifecycle/repair paths, with feature-specific readiness at scheduling/evaluation and final platform guards. |
| `PhoneStateReceiver`, `SilenceGuardService` | Missed-call accounting and guard lifetime must see all effective owners. Location observation remains a distinct typed service. |
| `SilenceStatus`, `PhoneSilenceHeroState`, prayer/manual settings sheets | Distinguish planned periods, this app's request and observed phone state. Combined silence/observation ends no later logically than its existing supporting windows; show waiting outside them without evaluating a cached location. Include `SharedSilenceSettingsSheet`, `PrayerSilenceSection`, `PrayerSilenceEditorSheet`. |
| `quran/audio/QuranSilence`, `QuranPlaybackService` | NONE/media restrictions, actual rule state and mosque-specific explanation; no recitation start through unsupported silence. |
| `adhkar/DhikrReminderScheduler` | It already holds unknown-ended silence until refresh. Preserve that behavior; update ownership/end lookup and raw SILENT-ringer heuristic when the adapter stops ringer writes. |
| `wake/AlarmAudioController` | Forced alarm volume and restoration do not prove that USAGE_ALARM can bypass DND. Validate actual allowed playback. |
| `PrefsManager`, backup/transfer rules, manifest | Separate persistent user configuration from live ownership/private geometry; add typed service/configuration activity and version-gated APIs. |

The current parser ignores geometry of deliberately ineligible records; it is incorrect to say every bad inventory ring disables the catalog. Malformed **eligible** geometry or required metadata can abort the whole parse. Any later runtime fixture/set should isolate invalid records and be compact rather than loading embedded review evidence. No research catalog becomes a runtime fallback in v1.

## 8. Private editor, storage and source rights

V1 stores independent private IDs, names, validated geometry, enabled state and revisions. Save and enable are explicit. No base overlays, shared submissions or hidden imports are required. Keep draft recovery, undo/cancel, vertex/coordinate editing, negative longitudes inside LTR isolates, Western digits, Arabic RTL, 48 dp targets and large-font/accessibility support.

Support simple polygons/rectangles first. Preserve holes/multipolygons read-only if editing them is unsupported; never flatten them by saving. Validate finite WGS84 longitude/latitude, distinct vertices, ring closure, nonzero area, self-intersection, bounds/extent and resource limits at editor and storage boundaries. An initial 10–100,000 m² screen is a proposal, with an explicit legitimate-large-compound path; area alone must not admit a continent-spanning thin shape. Warn about roads/homes and intentional surrounding space without silently redrawing accepted geometry.

**A GPS fix is a draft center, not a boundary.** Do not ship “stand inside for a minute and we draw the mosque” or infer a rectangle from correlated indoor scatter. The user must be able to inspect/draw the actual intended area. The editor must remain usable by map/coordinate input outside silencing windows without starting mosque location acquisition; an optional fresh location-assisted center is limited to a permitted in-window check. An offline outline/coordinate editor needs a usability demonstration; if it is insufficient, use the previously preferred MapLibre Native renderer with a provider whose display, coordinate-derivation/export, attribution and caching terms cover the feature. Select and record that provider and its dated cost before implementing a map-backed editor; the renderer alone supplies neither tiles nor rights. Keep map display requests/privacy separate from offline containment. No pricing claim is carried forward from the old alternative comparison.

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
| Window-only location explanation | نتحقّق من وجودك داخل نطاق مسجد خلال فترات إسكات الصلاة فقط. |
| Waiting outside a window | يبدأ التحقق من موقعك عند بدء فترة إسكات الصلاة. |
| No enabled prayer period | فعّل فترة إسكات لصلاة واحدة على الأقل لبدء التحقق من موقعك. |
| Detection/exit limit | قد يتأخر بدء الإسكات أو إيقافه عدة دقائق، وقد يتعذّر التحقق من موقعك داخل بعض النطاقات. |
| Window limit | تنتهي متابعة الموقع بانتهاء فترة الإسكات. وقد نوقفها قبل ذلك إذا تأكّد خروجك من النطاق أو تعذّر التحقق من موقعك. |
| Alarm policy explanation | تسري إعدادات المنبّهات التي اخترتها لهذه الفترات. وقد يمنع إعداد آخر في هاتفك صوت المنبّه. |
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
| Automatic background access missing | للتحقق من موقعك والتطبيق مغلق خلال فترات الإسكات، اسمح باستخدام الموقع طوال الوقت. |
| Precise access missing | موقعك التقريبي لا يكفي للتحقق من وجودك داخل النطاق. فعّل الموقع الدقيق. |
| Location disabled | تحديد الموقع متوقف في هاتفك. |
| DND access missing | اسمح للتطبيق بالتحكم في «عدم الإزعاج» حتى نتمكن من إسكات هاتفك. |
| Settings / location action | فتح الإعدادات / فتح إعدادات الموقع في الهاتف |
| Diagnosed battery limitation | قد يتأخر التحقق من موقعك لأن توفير البطارية يقيّد التطبيق. |
| In-window recheck action | إعادة التحقق من الموقع |
| Session limitation | نتابع موقعك خلال فترة الإسكات الحالية فقط. وقد نوقف المتابعة إذا تأكّد خروجك من النطاق أو تعذّر التحقق من موقعك. |
| Named active contribution | الإسكات مفعّل في نطاق %1$s |
| Fallback active contribution | الإسكات المرتبط بالموقع مفعّل |
| Skip current prayer period | إيقاف الإسكات لهذه الفترة |
| Lost-support release, after actual release | أوقفنا الإسكات المرتبط بالموقع لأننا لم نعد نستطيع التحقق من وجودك داخل النطاق. |
| Stopped contribution | أوقفنا الإسكات المرتبط بالموقع. |
| Other DND still observed | أوقفنا الإسكات المرتبط بالموقع، لكن «عدم الإزعاج» ما زال مفعّلًا في هاتفك. |
| Observation expiry needs recheck, while a window remains open | توقّف التحقق من موقعك. أعد التحقق خلال فترة إسكات الصلاة. |
| Manual fallback | إسكات هاتفك يدويًا |
| Complex editing unsupported | لا يمكن تعديل هذا النطاق هنا لأن شكله معقّد. |
| Crossing edges | تتقاطع خطوط النطاق. حرّك النقاط حتى لا تتقاطع. |
| Excessive area | هذا النطاق أوسع من المسموح. ضيّقه حول المسجد وساحته ومرافقه. |
| Invalid saved mode | تعذّر قراءة وضع الإسكات المحفوظ. اختر الوضع من جديد. |
| Unsupported system | هذا الوضع غير متاح على إصدار النظام في هاتفك. |
| Continue / later | متابعة / لاحقًا |

Proposed **single all-use background disclosure**, conditional on both travel and mosque background uses remaining in the release:

> نستخدم بيانات موقعك، حتى عندما يكون التطبيق مغلقًا أو غير مستعمل، لميزتين تختار تفعيلهما: التحقق من وجودك داخل نطاق مسجد مفعّل وإسكات هاتفك خلال الفترات التي ضبطتها للصلاة، وتحديث مدينتك ومواقيت الصلاة عند السفر. نقارن موقعك بنطاقات المساجد على هاتفك، ولا نرسل سجلّ وجودك فيها.
>
> في الصفحة التالية اختر «السماح طوال الوقت».

Buttons: **متابعة** and **لاحقًا**. Match final wording to the actual permission screen/OS and shipped uses. The recheck action is available only during an eligible window; outside it, show waiting or missing enabled periods without acquiring/evaluating location. “Skip this prayer period” uses the existing occurrence dismissal, not a promise to track departure. Optional online-map requests require their own accurate explanation; this disclosure does not claim that all existing network location work sends nothing anywhere. No strings for submissions, published catalogs or conflicts ship in v1.

## 11. Implementation sequence and meaningful validation

The original 2026-10-27 revision performed static verification, numerical geometry analysis and official-source retrieval only. The separately authorized 2026-10-28 Stage 0 has an isolated debug prototype, 46 passing unit tests and measured Pixel 6 checks; see the [report](MOSQUE_SILENCING_STAGE0_REPORT_2026-10-28.md) for the exact passes, failed attempt, corrections and remaining gaps. Those results include the former three-mode/independent-visit-cap model. After the scope edit, the owner authorized the isolated 2026-10-31 follow-up. The standalone model/native components now exercise two modes and the existing-window boundary, with 63 passing JVM tests, 14 focused Pixel instrumentation checks and four passing accelerated native trials; see the [current report](MOSQUE_SILENCING_STAGE0_REPORT_2026-10-31.md) for their precise limits. The previous prototype was archived before changes and recorded October 28 results remain historical. These interval fixtures are not production prayer-scheduler integration or real-mosque field validation. Production integration, real-mosque field reliability, sustained battery cost and the other offered devices remain unproved.

| Stage | Work | Completion gate |
|---|---|---|
| 0 — bounded feasibility | Use a few test areas, not a production catalog or collection restart. Verify the two-mode policy, zero mosque checks outside existing windows, fresh acquisition at window start/late entry, bounded in-window holding/exit, legal service starts, explicit rule ownership/user stop, release and recovery. | A written go/no-go names API/OEM support, measured indoor fix yield, window-start latency, holding/exit failures and window-limited battery/service cost. Validate clipped provisional parameters and cleanup at end/gap; the earlier broader prototype passes do not establish this revised contract. |
| 1 — pure policy and coordination | Trigger evaluator, complete states, durable minimal requests, one serialization boundary, versioned platform adapter, all writers/readers/exception decisions. | Truth table, TIME_ONLY bridge/wake parity, owner overlap, stale-event and crash/permission tests pass. No independent setter/force-normal bypass remains. |
| 2 — location execution beta | Window-limited typed observation service/geofences, existing start/end callbacks, explicit permission paths, recovery watchdog, occurrence-scoped stop, readiness and quiet notifications. | Already-inside/window-start and later in-window arrival work in the offered environment; no pre-window/gap checks occur; last supporting window end cancels observation; permission/process/fix loss has measured bounded behavior; system stop/snooze is respected. |
| 3 — usable private editor and privacy | Draw/review/save/enable, accessible coordinates/vertices, draft/undo, validated storage, optional export; choose permitted map provider only if required by demonstrated usability. Separate research asset from production packaging in this future work. | The user can define the intended area without mistaking GPS scatter for a boundary; no unintended topology/expansion, evidence remains private/durable, backup/transfer and telemetry exclusions verified. |
| 4 — owner acceptance and release review | Focused logic tests plus authorized silent physical-device checks and an owner field checklist, with a small opt-in local diagnostic. | Measured supported behavior is acceptable; unresolved source/packaging, Play, ownership or release failures block release. Unmeasured cases are not passes. |

**Implementation pass, 2026-10-31:** stages 1–3 now have production source integration and a compile-checked private-area beta. The default TIME_ONLY path stays compatible until explicit rule enrollment; after enrollment all automatic/manual/wake contributions use the owned adapter. Stage 4 device/field/energy and store-release acceptance is intentionally pending. The offline outline/coordinate editor, forthcoming catalog ingestion, packaging/source-rights review and API/OEM support remain subject to the listed acceptance work. A source implementation is not a measured device pass. See [delivered behavior and handoff](MOSQUE_SILENCING_IMPLEMENTATION_2026-10-31.md).

Tests should check behavior and authority, not exact UI strings or mirror the implementation:

- **Policy/time:** the two-mode truth table, readiness loss, missing/invalid trigger, mid-window entry/exit, and fresh acquisition for someone already inside when the window opens. Test exact start/end boundaries, no inherited pre-window presence, late start delivery, overlapping/adjoining windows versus a positive gap, short windows, dismissals/settings changes, Friday/Eid/Ramadan, DST/time-zone changes and overnight windows. Preserve FROM_NOW wake behavior during TIME_ONLY's auto-active 45-minute bridge, including next-occurrence dismissal; separately test a genuinely null conflict lookup and inherited awake-check eligibility/historical cancellation. Verify that none of those bridge/travel paths authorize combined-mode location outside a real window.
- **Geometry/presence:** holes, touching/merged boundaries, multipart/concave shapes, circle coverage, old/mock/repeated/out-of-order fixes, uncertain-but-live versus no-fix cases within the window, failed acquisition/retry, observation/ambiguity expiry without immediate reentry, occurrence suppression/rearm, stale window generations and effective geometry revision. Clip every deadline at window end, reject late callbacks and establish fresh presence after a gap. Do not use a geofence circle as the expected polygon answer.
- **Location boundary:** record provider requests, geofence registrations/removals and fix evaluations. Require zero mosque acquisition/observation/evaluation outside eligible coverage, including the former preparation lead-in, mode changes outside a window, late callbacks, recovery, recheck/editor actions and future-prayer bridge lookups. At the last window end, validate provider cancellation, geofence removal and logical DND release separately from a delayed platform cleanup callback. Existing travel updates remain independent and cannot feed out-of-window mosque evaluation.
- **Owners/platform:** every matrix row and overlap, first baseline/nap transfer, master disable/cancelAll/force-normal replacement, system disabled/removed/snoozed rule, ringer coupling, actual NONE/ALARMS/PRIORITY behavior, permission loss/reboot/process crash, unavailable exact access and delayed watchdog. No test may clear an independent owner to manufacture success.
- **Device/field, only when separately authorized:** on the owner's available Pixel/OnePlus and explicitly supported versions, test already-inside acquisition at the actual configured start, in-window arrival while closed, pass-by, a visit longer than the hold bounds while its configured window remains open, locked-screen departure, window end without departure, network/provider loss, approximate permission, battery saver/Doze and recovery. Measure acquisition/holding cost over representative existing windows and the absence of mosque work between them. Debug-only location injection can exercise states; release builds retain mock rejection. A debug injection is not a measurement of indoor GPS reliability.
- **Silent validation:** follow the recorded requirement for no sound **and no vibration** during device checks; verify those settings before proceeding. DND/ringer/policy observations do not prove physical audio delivery, which must not be claimed without a separately authorized suitable check.
- **Usability/privacy:** RTL, Western digits/LTR negative coordinates, actual-size and large-font layout, vertex accessibility/TalkBack when performed, store-empty/no-match/uncertain distinction, all denial paths, no raw fix/area/visit analytics, backup/transfer exclusions and deliberate export preview. Do not infer an empty country from travel alone.

Keep validation proportionate: report actual trial counts and failures, representative latency/longest observed release, service duration and battery comparison with TIME_ONLY. No statistical national/OEM certification or impossible guaranteed timeout is implied. Zero observed owner-isolation failures is required in the specified checks; a detector that never activates cannot pass. Do not claim actual sound, field reliability or accessibility from compilation/static tests.

## 12. Deferred work and decisions remaining

Defer shared catalogs, national coverage, contributions/moderation, public website/accounts, signed downloads/replay protection, general imports, advanced topology/diff tools, navigation/online search, offline national basemaps and extra movement permissions. Original later-phase considerations remain in the archived plan; they are not v1 implementation requirements. Per-mosque prayer timetables are excluded by the recorded prayer-method scope, not merely a later feature.

The bounded [official product comparison](../work/mosques/silencing-plan-verification-20261027/scope-copy-findings.md) confirms time-window silencers, PrayZone's saved-mosque arrival claim, and Apple's user-configured location Focus schedules. These support explicit selected areas/manual control; listing claims do not prove exit reliability or polygons. Do not carry forward unsupported popularity rankings, universal absence claims, Samsung radius figures, vote counts or snippet-only complaint prevalence.

Remaining decisions are concrete: source/packaging and any public-history action; a permitted map provider if the private editor needs one; the actually demonstrated API/OEM envelope; measured hold/entry/release parameters; and the release's truthful Play declaration/approval. There is **no forced travel-versus-mosque policy choice** and no permission here to remove public data.

Stage 0's measured closeout assessment was NO-GO beyond the isolated prototype. The 2026-10-31 follow-up passes its two-mode/window-boundary component checks, including background window opening, clipped provider/geofence lifetimes, gap rejection, overlap continuation and a synthetic-presence DND release. The historical Pixel follow-up also records actual Modes stop, permission loss, short forced-Doze and post-unlock reboot cases. Neither set establishes indoor field reliability, a sustained battery budget, a shared-quota/long-idle bound or wider OS/OEM support. The deferred acceptance work is a few known-area in-window field traces, a controlled window-limited energy comparison and the unresolved support/recovery checks. The owner subsequently instructed: “these should not stop a GO for me. proceed with the implementation. we will test after you implement.” This authorizes production implementation of stages 1–3 and preparation of the stage 4 handoff; the preceding Stage 0 NO-GO remains a dated test assessment. Field/energy/device acceptance follows implementation. Catalog shipping and collection restart remain outside this work.
