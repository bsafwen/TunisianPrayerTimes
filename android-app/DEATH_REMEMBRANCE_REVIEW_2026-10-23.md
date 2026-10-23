# Review of تذكير بالموت

**23 October 2026**

The feature currently appears as **«ذكر الموت»** in **«عباداتي»**. Its strongest idea is the connection between reflection and a practical act of kindness: a short religious passage, its reference, and something useful to do for other people today. Keep that focus.

The main improvements should make the feature more trustworthy and easier to follow: request notification permission before treating delivery as available, make turning off or pausing reliable during an active delivery, and open the day's content when a notification is tapped.

For the content itself, broaden the daily themes to include repentance, hope, presence in prayer, accountability, gratitude, and supplication for the deceased. The content proposals below include Arabic titles, reflection questions, practical actions, and four new sourced hadith selections.

## Scope and evidence

This review covers the current Android working tree, including its uncommitted feature files, against repository HEAD `876a345c3`. It examines the daily card, Arabic copy, content catalog, settings sheet, notification navigation, scheduler, integration points, and existing tests.

Findings below distinguish behavior established by source code, a possible concurrency interleaving, and product recommendations. A standalone Java probe checked the weekly day-selection algorithm. No application code was changed, no build or repository test suite was run, and no device UI, sound, TalkBack, or OEM delivery behavior was exercised. Religious authenticity and interpretation were not independently audited; the content assessment uses the repository's documented provenance and the text presented by the app.

## Current experience

| Area | Current behavior |
| --- | --- |
| Daily content | An always-present card follows Qibla and fasting in «عباداتي». Its 20 entries alternate Qur'an and hadith over a 20-day cycle. |
| Reading | The card displays the day's passage, a reference, and an app-authored good deed. Qur'an uses the shared mushaf renderer and readable accessibility text. |
| Notifications | Disabled by default. Enabling uses twice a week unless another frequency was previously saved. Daily and weekly options are available. |
| Delivery window | A time is drawn between one hour after sunrise and 45 minutes before Maghrib, using the selected prayer location. Prayer and active in-app Qur'an reading can defer it. |
| Exceptions | Eid or possibly-Eid days are excluded. A detected mismatch between the phone and saved prayer location skips that day's due reminder. |
| Control | The user can turn notifications off or pause for 30 days. The notification itself also offers the pause action. |
| Notification content | The title is the anchor hadith's phrase; the body is the day's suggested deed. Delivery is explicitly silent. Tapping currently opens settings and the explanation of the feature. |

Evidence: [card and settings][ui-card], [content catalog][catalog], [scheduler defaults and controls][scheduler-controls], [daylight selection][scheduler-window], [notification][scheduler-post], and [Worship layout][worship].

## What works well

**The advice gives remembrance a concrete purpose.** The catalog connects reflection to kindness, reconciliation, charity, returning rights, and avoiding hurtful speech. The quotation and its reference precede the advice, and there is no score, streak, or completion pressure. Preserve this restraint. [Content catalog][catalog]

**Notification control is considerate.** Opt-in delivery, a silent notification, a moderate default cadence, and an explicit pause are good starting choices. Prayer, reading, daylight, and Eid checks show care about context. These are implemented safeguards; their physical behavior still needs the focused checks below. [Controls][scheduler-controls], [delivery checks][scheduler-refresh]

**The source groundwork is useful.** The repository records source links, text hashes, excerpt conventions, and Qaloun verse numbering. The UI reuses the mushaf text instead of maintaining a second Qur'an transcription. Existing tests check catalog integrity and rotation. Preserve these foundations when changing the presentation. [Provenance][provenance], [catalog tests][catalog-tests], [mushaf renderer][mushaf-ui]

**Recovery has several entry points.** The feature refreshes at process startup, after system clock/boot events, after prayer schedule changes, and when an in-app Qur'an reading session ends. It also schedules an inexact backup, an exact alarm when permitted, and periodic repair work. These mechanisms improve recovery opportunities; they do not establish guaranteed delivery on every phone. [Startup][startup], [system refresh][boot], [schedule refresh][schedule-refresh], [reading release][reading-release], [alarm and repair logic][scheduler-refresh]

## Findings to address first

### 1 Permission handling can promise delivery and consume a reminder before it is allowed

**P2 functional and status defect · Established by source flow**

Turning the reminder on immediately saves `enabled=true`, starts a background refresh, and shows **«سيصلك التذكير…»**. Notification permission is requested afterward. The permission callback only refreshes the UI.

If today's selected time has already passed but the daylight window remains open, that background refresh can attempt delivery while permission is still pending. The scheduler calls `post()` and then records `posted_day` without checking whether app or channel notifications are allowed. Android blocks ordinary notifications when permission is denied, and new Android 13+ installations begin with notifications disabled. Accepting permission afterward does not undo the consumed day. [Android notification permission behavior](https://developer.android.com/develop/ui/views/notifications/notification-permission)

There is also a visible inconsistency after refusal: the main card still says **«يصلك تذكير…»** because its status checks only enabled and paused preferences. A useful blocked-notification warning already exists, but it appears only inside the settings sheet.

**Recommended change:** keep the user's enabled preference separate from delivery readiness. Check app and channel permission before counting a day as delivered. Refresh after permission is granted or notification settings become permissive, retaining eligibility only within the current daylight window. Show the blocked state and its repair action on the main card as well.

Suggested blocked copy: **«التذكير مفعّل، لكن إشعاراته متوقّفة في إعدادات الهاتف.»** Pair it with **«السماح بالإشعارات»**.

**Acceptance:** enable daily reminders after the selected time on a fresh installation, leave the permission dialog open, then grant permission. One reminder can still appear inside the valid window. Refusing permission or blocking the channel produces an honest status, and repeated refreshes do not create a backlog.

Sources: [enable and permission sequence][ui-switch], [main-card status][ui-status], [unconditional delivery bookkeeping][scheduler-refresh], [existing permission warning][notification-warning].

### 2 An in-flight delivery can appear after turning off or pausing

**P2 concurrency defect · Possible interleaving established by source, not reproduced on a device**

The refresh path uses a lock, but setting enabled/paused state and cancelling the visible notification happen outside that lock. This permits the following sequence:

1. A refresh reads enabled state and selects a due reminder.
2. The user turns the feature off or pauses it; the action updates preferences and cancels the existing notification.
3. The earlier refresh posts a new notification using the state it already read.
4. The later refresh cancels alarms and returns for the disabled or paused state, without removing that newly posted notification.

The notification can remain until its midnight timeout. For a user who just asked the feature to stop, that is a direct failure of the control.

**Recommended change:** serialize state changes and notification cancellation with delivery, so a completed off/pause action prevents older work from posting afterward. Keep expensive scheduling work off the UI thread.

**Acceptance:** deliberately stop a refresh immediately before posting, issue disable or pause, then release it. Once the user action completes, no notification remains and no obsolete delivery can appear. Cover disable and pause separately.

Sources: [enable and cancellation][scheduler-controls], [pause][scheduler-pause], [locked refresh and post][scheduler-refresh], [notification timeout][scheduler-post].

### 3 Tapping a reminder opens its settings instead of its daily reading

**P2 navigation mismatch · Established by source**

The notification presents the day's deed. Tapping it opens the reminder settings sheet, containing frequency choices, pause controls, and the fixed anchor hadith under **«لماذا نذكر الموت؟»**. That sheet does not contain the day's passage or deed.

The daily content remains on the underlying card, below the Qibla and fasting sections. The notification therefore leads away from the content that prompted the tap.

**Recommended change:** open a focused view of the relevant daily passage, reference, and deed. Keep **«إعدادات التذكير»** as a secondary action. Pass the notification's date or content ID so the opened content corresponds to what the reader tapped, including around a date change.

Also give the card an explicit settings affordance. Its current row labelled **«التذكير»** opens settings while the switch inside it toggles delivery; a clear **«إعدادات التذكير»** button would make the two actions easier to discover.

**Acceptance:** tap notifications for both a Qur'an day and a hadith day, with the app closed and already open. The matching passage and deed are immediately visible, and settings remain one obvious action away.

Sources: [notification intent][scheduler-post], [navigation routing][navigation], [sheet request][ui-request], [daily card and settings contents][ui-card].

## Product and content improvements

### 4 Status wording should describe eligibility rather than guarantee an arrival

**P3 clarity improvement · Current wording and scheduling behavior established by source**

The pause message says **«ويعود يوم…»**, but the stored date is the first eligible day after 30 days. That day may not be selected for weekly delivery, may be excluded as possibly Eid, or may have no usable daylight window. Similarly, **«يصلك تذكير مرتين في الأسبوع»** describes the selected cadence more strongly than the scheduler can guarantee after exceptions.

Travel-related suppression and an unavailable sunrise/Maghrib window are also invisible in the main-card status. A user can see an enabled promise while the scheduler has no deliverable reminder.

**Recommended change:** show a few understandable states: off, available, paused, notifications blocked, or needing a valid prayer location. Use **«التذكير متوقف مؤقتًا حتى [التاريخ]»** for pause eligibility and **«التكرار المختار: مرتان في الأسبوع»** for the frequency. Briefly explain that unsuitable days may be skipped. The main card does not need to expose the random delivery minute.

**Acceptance:** status stays accurate during a pause, on its expiry date, with blocked notifications, and when no valid delivery window exists. Returning to settings should refresh readiness.

Sources: [status wording][ui-status], [pause controls][ui-settings], [eligibility filters][scheduler-next], [travel suppression][scheduler-refresh].

### 5 Make the distinction between quotation and suggested action explicit

**P3 content clarity improvement**

The divider and typography already separate the passage from the deed, but the deed has no label identifying it as the app's suggestion. The repository explicitly describes these lines as editorial advice. A small **«اقتراح لليوم»** label would make that distinction clear without adding an explanation to every card.

A secondary **«المصدر»** action could expose the existing provenance and clarify Qur'an numbering when needed. The displayed references use the app's Qaloun convention: for example, **«البقرة 280»** corresponds to Hafs 2:281 in the repository's documented mapping. These differences should not be treated as citation errors or silently renumbered.

Keep the interface light: passage, reference, suggestion, then optional details. There is no need to display the full sourcing notes in the everyday reading flow.

**Acceptance:** a reader can distinguish the quoted text from app-authored advice. Source details preserve the displayed Qaloun passage and numbering and identify any deliberately excerpted hadith.

Sources: [card hierarchy][ui-card], [content model and editorial description][catalog], [documented numbering and excerpt conventions][provenance].

### 6 Twice-weekly delivery does not ensure a gap across week boundaries

**P3 cadence decision · Confirmed by a standalone algorithm probe**

The algorithm selects two non-adjacent days within each Monday-to-Sunday week. It draws the next week independently, so Sunday and the following Monday can both be selected.

Using the current algorithm, salt `0` selects **Sunday 29 November 2026** and **Monday 30 November 2026**. This is a property of the selected days, before delivery exceptions. It satisfies the implementation's explicit within-week guarantee, so it is not a broken weekly-count calculation.

**Recommended decision:** if “twice a week” is intended to feel spaced out, enforce a gap across the week boundary too. Otherwise retain the algorithm and avoid claiming that the reminders are always separated. Do not turn a skipped reminder into catch-up messages.

**Acceptance if spacing changes:** check adjacent weeks and year boundaries, while retaining deterministic scheduling across refreshes and the chosen frequency policy.

Sources: [day selection][scheduler-days], [existing within-week tests][day-tests].

## Content additions I would prioritize

**Content implementation on 23 October 2026:** the catalog now has 28 entries, with the original 20 retained and four new hadith paired with four generated Qaloun passages. Cards show a theme, a labelled practical suggestion, optional reflection on eight entries, and expandable source details. The broader introduction and more specific actions are implemented. The three shared catalog tests pass; source checks preserve all ten original hadith and verify the four new quotations. Android compilation and the Android mushaf test are blocked by an unrelated Hifz callback mismatch in `QuranScreen.kt:2912`. Notification scheduling and navigation findings above remain pending.

The original content's main weakness was that nearly every suggested deed pointed toward kindness to other people. That valuable theme narrowed passages that also speak about worship, repentance, accountability, hope, and the use of one's remaining life. For example, the Ibn Majah 4171 entry contained advice about prayer, yet its suggested action addressed only speech. The following rationale and sample wording record the proposal that guided the expanded [catalog][catalog].

I would first deepen those connections, then add a small number of texts covering the missing themes. Each card should help the reader understand one meaning and choose one relevant action.

A broader introduction could say:

> نتذكّر الموت لنراجع ما نقدّمه، ونرجع إلى الله، ونحفظ حقوق الناس، ونحسن العمل فيما بقي من أعمارنا.

The reflections and suggestions below are proposed app-authored wording. They are separate from the religious quotation and its explanation. A card can offer an optional **«سؤال للتأمل»** and one **«اقتراح لليوم»**, without asking the reader to record an answer or report private faults.

### 1 Repentance and returning to Allah

**Add an explicit theme.** The existing apology and repayment advice addresses other people's rights. There should also be space for returning to Allah after personal wrongdoing.

A suitable new text is **Sahih Muslim 2759**, about the opportunity for repentance by night and day. Its wording and reference are available in [HadeethEnc's entry](https://hadeethenc.com/ar/browse/hadith/4318).

**Suggested title:** «الرجوع إلى الله»

**سؤال للتأمل:** «هل هناك خطأ تؤجّل تركه؟»

**اقتراح لليوم:** «استغفر الله، واترك ذلك الخطأ، واختر خطوة تعينك على عدم العودة إليه.»

The action is a starting point for reflection, not a complete statement of the conditions of repentance. Where another person's rights are involved, connect it to the rights theme rather than implying that words of forgiveness replace repair.

### 2 Hope and a good expectation of Allah

**Give hope its own place.** Hope is already present in the selected passage from النحل 32, but that entry's advice is about speaking kindly. Add a card whose main meaning is hope in Allah's mercy.

A suitable new text is **Sahih Muslim 2877**: «لا يموتن أحدكم إلا وهو يحسن بالله الظن». The [source entry](https://hadeethenc.com/ar/browse/hadith/3779) places this instruction in the Prophet's final illness and explains its particular relevance near death. Preserve that context in source details; an everyday card should invite hope and good action without suggesting that the reader's death is imminent.

**Suggested title:** «الرجاء في رحمة الله»

**سؤال للتأمل:** «كيف تبدأ من جديد وأنت ترجو رحمة الله؟»

**اقتراح لليوم:** «اسأل الله رحمته ومغفرته، وابدأ بخير تقدر عليه.»

Connect hope with returning to Allah and taking a constructive step.

### 3 Presence in prayer

**Enrich an existing entry first.** Ibn Majah 4171 already contains «فصل صلاة مودع». The current action uses the same report's advice about speech; a separate prayer-focused card would recover another central meaning without needing a new quotation.

**Suggested title:** «حضور القلب في الصلاة»

**سؤال للتأمل:** «ما الذي يشغل قلبك حين تقف للصلاة؟»

**اقتراح لليوم:** «أبعد ما يشغلك قبل صلاتك المقبلة، وتمهّل في قراءتك وركوعك وسجودك.»

This is more closely connected to the passage than another general instruction to treat people well. Keep the report's existing reference and source conventions. [Ibn Majah 4171 in the catalog][catalog]

### 4 A brief review of what one is preparing for the Hereafter

**Develop the existing الحشر 18 entry.** Its current action combines reviewing the day with apologizing. Add a balanced reflection that includes gratitude, correction, and a next step.

**Suggested title:** «ماذا قدّمت؟»

**سؤال للتأمل:** «ما الخير الذي تحمد الله على أن وفّقك إليه، وما الأمر الذي تحتاج إلى إصلاحه؟»

**اقتراح لليوم:** «اختر أمرًا واحدًا تريد إصلاحه، وحدّد أول خطوة تستطيع القيام بها.»

Keep this as a private reflection with a single next step. [الحشر 18 in the catalog][catalog]

### 5 Rights that need a concrete next step

**Make an existing strength more practical.** The catalog already includes Bukhari 2449 on wrongs and Bukhari 2738 on a written will. Give different cards distinct tasks: returning an entrusted item, acknowledging a debt, repairing a hurtful statement, or documenting a right.

**Suggested title:** «حقّ لا أريد أن أنساه»

**سؤال للتأمل:** «هل عندك أمانة أو حقّ لأحد لم تؤدّه بعد؟»

**اقتراح لليوم:** «اختر حقًّا واحدًا، وخذ خطوة لأدائه. وإن احتجت إلى وقت، فدوّنه حتى لا تنساه.»

For a separate speech-focused card, use: **«إن آذيت أحدًا بكلمة، ففكّر في اعتذار صادق يصلح ما أفسدته.»**

Keep documentation as a step toward fulfilling the right; it does not replace repayment. [Rights and will entries][catalog]

### 6 Gratitude for the time and ability available

**Add a more specific treatment of time.** The catalog repeatedly says not to postpone good. Help the reader identify an opportunity they actually have.

A useful addition is **Sahih al-Bukhari 6412**: «نعمتان مغبون فيهما كثير من الناس: الصحة والفراغ». Its [source entry](https://hadeethenc.com/ar/browse/hadith/5449) identifies health and free time as blessings whose value is often lost.

**Suggested title:** «نعمة أستعملها في خير»

**سؤال للتأمل:** «ما النعمة التي تستطيع أن تستعملها في خير الآن؟»

**اقتراح لليوم:** «احمد الله على نعمة تجدها في حياتك، واختر عملًا يناسب وقتك وقدرتك.»

Examples can vary between cards: learning something useful, helping with a household task, or making time for a relative. Avoid assuming that every reader is healthy, financially comfortable, or free of caring responsibilities.

### 7 A benefit that continues after one's life

**Expand the existing Muslim 1631 entry with accessible examples.** It already names ongoing charity, beneficial knowledge, and a righteous child who prays for the deceased. The current advice largely repeats those categories. A more concrete suggestion would help the reader begin.

**Suggested title:** «نفع يبقى بعدك»

**سؤال للتأمل:** «ما الشيء النافع الذي تستطيع أن تعلّمه لغيرك؟»

**اقتراح لليوم:** «علّم أحدًا مهارة نافعة تتقنها، أو ساعده على تعلّمها.»

Other cards could suggest supporting a useful learning resource or contributing to a continuing charitable project. Include opportunities that require no money and do not assume parenthood. Present these as practical examples, without guaranteeing a particular reward for every possible activity. [Muslim 1631 in the catalog][catalog]

### 8 Prayer for those who have died

**Add remembrance expressed through supplication.** This gives the reader a way to respond with mercy and prayer, alongside reflection on their own life.

A suitable source is the funeral supplication in **Sahih Muslim 963**. A short, explicitly labelled excerpt is **«اللهم اغفر له وارحمه»**; [the full source entry](https://hadeethenc.com/ar/browse/hadith/65560) preserves its funeral-prayer context and the remainder of the supplication.

**Suggested title:** «دعوة لمن سبقنا»

**سؤال للتأمل:** «من تذكره اليوم بدعوة ورحمة؟»

**اقتراح لليوم:** «ادعُ لمن مات من المسلمين بالمغفرة والرحمة.»

Offer a general prayer for deceased Muslims, with the original context available in source details. If the app adapts a supplication's pronouns, distinguish that adaptation from the verbatim source.

### Make existing advice more specific

These small replacements would improve the current catalog even before new religious texts are added:

| Current suggestion | Proposed wording |
| --- | --- |
| «اترك في كل من تلقاه اليوم أثرًا طيبًا.» | «اختر شخصًا تستطيع أن تخفّف عنه: استمع إليه، أو أعنه في أمر يحتاجه.» |
| «اجعل كلامك اليوم طيبًا مع كل من تحدّثه.» | «اختر كلمة شكر صادقة لشخص أحسن إليك.» |
| «لا تؤجّل إلى الغد خيرًا تقدر عليه اليوم.» | «اختر خيرًا تؤجّله، وابدأ بأول خطوة تستطيعها.» |
| «اجعل إحسانك إلى الناس خالصًا لوجه الله.» | «قدّم مساعدة ابتغاء وجه الله، من غير انتظار شكر أو ثناء.» |

These are alternative editorial prompts, not translations of the accompanying passages. Keep their thematic connection visible and rotate concrete examples instead of attaching a generic action to every quotation.

### Keep the expanded content coherent

I would start with **prayer, repentance, and hope**. Prayer can be strengthened immediately using the existing selection; repentance and hope provide the most useful new emphasis. Then improve the rights and lasting-benefit suggestions, followed by gratitude and prayer for the deceased.

Give every card a clear connection to mortality: what the reader is preparing, what they can repair while able, what they hope for from Allah, or what benefit may remain. That connection keeps the feature from becoming an unrelated collection of motivational advice.

Alternate themes as well as Qur'an and hadith. Avoid several consecutive delivered cards about debt, blame, or regret. The actual notification sequence matters because a weekly user may not see the intervening daily cards. This sequencing is an editorial choice, not a prescribed devotional program.

Use one manageable suggestion per card. A reflection question can be occasional; the main reading should remain brief. Avoid a fixed daily count of repentance, required journaling, completion badges, or wording such as **«قد تكون هذه آخر فرصة لك»**. Retain the seriousness of the quoted sources while making the app's own voice clear and measured.

The four new hadith references above were checked on their linked HadeethEnc pages. Before inclusion in the app catalog, prepare their exact Arabic text, attribution, any excerpt markers, and text-integrity checks using the repository's existing content process. Preserve Qaloun numbering for any additional Qur'an passages. The expanded sequence also needs to preserve the current verse/hadith alternation rather than simply appending four hadith entries.


## Recommended user journey

1. Open **«عباداتي» → «ذكر الموت»** and see the day's passage, its reference, and **«اقتراح لليوم»**.
2. Use **«تفعيل التذكير»** to opt in. The app requests permission and shows whether notifications are actually available.
3. Open **«إعدادات التذكير»** to choose frequency, pause, or resume.
4. Tap a notification to return directly to its passage and suggested deed.
5. Turn delivery off or pause it with immediate, reliable feedback.

Keep the practical, reflective tone and avoid completion scoring. As a separate optional improvement, consider allowing the daily card to be collapsed or hidden: disabling notifications currently leaves the full card visible. That is consistent with the present design, but it gives readers control over delivery only.

## Validation before treating delivery as reliable

The existing feature-specific Android tests check weekly counts and variation. Shared catalog tests check text hashes, references, rotation, and mushaf availability. These protect useful invariants, but they do not exercise the delivery lifecycle.

| Check | Expected outcome |
| --- | --- |
| Permission pending, denied, then granted | Honest card status; no premature consumed day; at most one valid reminder after permission becomes available. |
| Off or pause during a delivery | No notification survives a completed stop action and no stale work posts afterward. |
| Exact alarm, backup, and repair run close together | A single notification for the eligible day. |
| Prayer or Qur'an reading overlaps the selected time | Delivery waits until both holds end, or skips the day if the daylight cutoff is reached. |
| Sunrise/Maghrib boundary and delayed alarm | Nothing new posts outside the allowed window. An existing notification currently expires at midnight; decide separately whether earlier removal is desirable. |
| Reboot, app update, clock/timezone change, and prayer-location change | Schedule is recalculated without duplicate or stale-location delivery. |
| Eid, uncertain Eid date, travel mismatch, and missing prayer data | Suppression behaves as intended and the user-facing state remains understandable. |
| Notification navigation | The correct passage and deed open from both cold and warm app states. |
| Small screen, large text, and TalkBack | Passage, source, advice, switch, and settings remain usable; headings and actions are announced clearly. Check the current 12sp source/status text and nested row/switch interaction without presuming they fail. |
| Physical OnePlus delivery | Confirm silent sound/vibration behavior, OEM delay, notification dismissal, pause, and recovery while preserving existing user data. |

Sources: [Android day tests][day-tests], [shared content tests][catalog-tests], [mushaf coverage test][mushaf-tests], [UI semantics][ui-card].

## Suggested implementation order

First correct permission handling and stop/pause serialization. Then make notification taps open the relevant content and expose honest readiness on the card. For the editorial work, begin with the prayer, repentance, and hope cards, then make the existing actions more specific. Add the advice label and source details alongside those content improvements. Decide the cross-week spacing policy after the reliability and navigation issues are addressed.

[ui-status]: C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ui/DeathRemembranceUi.kt:82
[ui-switch]: C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ui/DeathRemembranceUi.kt:90
[ui-request]: C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ui/DeathRemembranceUi.kt:130
[ui-card]: C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ui/DeathRemembranceUi.kt:150
[ui-settings]: C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ui/DeathRemembranceUi.kt:207
[scheduler-controls]: C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/remembrance/DeathReminder.kt:84
[scheduler-pause]: C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/remembrance/DeathReminder.kt:115
[scheduler-days]: C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/remembrance/DeathReminder.kt:131
[scheduler-window]: C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/remembrance/DeathReminder.kt:151
[scheduler-next]: C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/remembrance/DeathReminder.kt:181
[scheduler-post]: C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/remembrance/DeathReminder.kt:210
[scheduler-refresh]: C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/remembrance/DeathReminder.kt:257
[catalog]: C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/multiplatform/shared/src/commonMain/kotlin/com/tunisianprayertimes/adhkar/DeathRemembranceTexts.kt:4
[provenance]: C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/docs/adhkar-sources.md:184
[worship]: C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ui/WorshipScreen.kt:30
[navigation]: C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ui/MainScreen.kt:610
[notification-warning]: C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ui/FastingUi.kt:425
[startup]: C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/TunisianPrayerTimesApplication.kt:52
[boot]: C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/BootReceiver.kt:16
[schedule-refresh]: C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ScheduleRefreshCoordinator.kt:51
[reading-release]: C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/quran/QuranReading.kt:71
[mushaf-ui]: C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ui/AdhkarMushaf.kt:25
[day-tests]: C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/test/java/com/tunisianprayertimes/remembrance/DeathReminderDaysTest.kt:15
[catalog-tests]: C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/multiplatform/shared/src/javaTest/kotlin/com/tunisianprayertimes/DeathRemembranceTextsTest.kt:29
[mushaf-tests]: C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/test/java/com/tunisianprayertimes/adhkar/MushafPassagesTest.kt:16

