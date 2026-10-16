# المراجعة — UX review, 15 October 2026

The flow already supports recall, revealing the answer, self-assessment, corrective practice, and an unaided retry. The biggest remaining weaknesses are continuity between those steps, precision of the correction, and whether progress accurately describes what the learner recalled independently.

This review contains **12 prioritized findings**, followed by a proposed journey and implementation order. It distinguishes observed device behavior, source-confirmed defects, and design recommendations.

## Evidence and scope

- Reviewed the current working tree, including existing uncommitted changes, with two independent source reviews.
- Walked through the installed development app on the connected OnePlus CPH2747: version 2.144, code 155. Source findings refer to the current workspace; identical version labels alone do not prove an identical source build.
- Checked the home screen, scheduled recall and answer, standalone correction, practice failure, unaided retry, successful practice summary, verse search, and word-error recording. Scheduled-review answers were not submitted. Practice answers exercised the mode that leaves schedules unchanged. The recording draft was cancelled.
- Checked correction at the user's font scale of 1.15 and temporarily at 1.5; restored 1.15.
- The before/after notebook contents matched exactly: **13 records, including their schedules and review history, preserved**. No application source was edited or APK installed. No build or automated tests were run for this review. Audio timing, sound output, notifications, TalkBack, and cold-process recovery were not exercised.
- Screenshots and UI hierarchies are saved in [the evidence folder](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/build/ux-review-20261015). Evidence in this build folder is local and may be removed by a clean build.

## Findings

### 1. “Practice later” does not preserve the unfinished practice

**Fix first · Functional defect · Source-confirmed**

Fail a point in the first batch, choose **«أتدرّب لاحقًا · التالي»**, then continue with **«تابع مراجعات اليوم»**. The next batch creates a new review state. The daily numerical tally survives, but the IDs awaiting practice do not. Meanwhile, the failed point has moved to tomorrow, or two days later after hesitation. The final retry list therefore omits earlier unfinished practice. Leaving the review has the same continuity problem.

The record itself is not deleted. What is lost is the actionable promise of “later.” A learner should not have to remember which points still need work or find them manually in the notebook.

**Change:** retain pending practice across batches and exits. Show **«أكمل التدريب — موضعان»** on the home screen, separately from scheduled checks. Completing practice must not advance the scheduled check twice.

**Acceptance:** fail in batch 1, defer, finish batch 2, leave and reopen. The first failure remains available for practice until completed or explicitly dismissed.

Sources: [review state initialization](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ui/QuranScreen.kt:453), [starting a new batch](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ui/QuranScreen.kt:1115), [retry uses the current failed IDs](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ui/HifzReview.kt:852), [failure scheduling](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/quran/hifz/Hifz.kt:236).

### 2. A correction can reveal a later answer without affecting its “unaided” result

**Fix first · Learning correctness defect · Source-confirmed scenario**

Suppose both directions of a similar pair are due. Revealing A displays B in full; the drill can also play B. When B is asked later, the assistance state only knows about help on the current card. B can receive normal independent credit even though its answer was just exposed. Separating the two cards in the list does not remove this exposure.

The device confirmed that revealing a correction displays the other passage. A complete reciprocal-pair grading sequence was not run against the user's notebook.

**Change:** either collect both attempts before displaying the comparison, or track every answer exposed during a session and classify later attempts on those verses as practice. Apply the same rule to audio and future-verse reveals.

**Acceptance:** reveal or play B while correcting A, then attempt B. B cannot advance as a fresh unaided scheduled check in that session.

Sources: [comparison exposes the other verse](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ui/HifzSheets.kt:1008), [current-card assistance](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ui/HifzReview.kt:251), [grading](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ui/HifzReview.kt:273), [batch ordering](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/quran/hifz/Hifz.kt:264), [alternating drill](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/quran/hifz/HifzText.kt:560).

### 3. The correction does not show the exact word or ending clearly enough

**Fix first · Functional presentation defect · Device and source confirmed**

The stored error can identify specific words and the point where similar passages diverge. However, those marks are supplied only to the text fallback. When the normal mushaf images load, the learner sees whole-verse highlights. They must find the difference again themselves.

For the existing البقرة 262 / 266 ending record, both passages appeared, but neither ending was isolated as the key contrast. The instruction **«لاحظ خاتمة كل موضع»** asks the learner to perform the diagnostic work that the correction should support.

**Change:** preserve the mushaf context and add a compact, always-visible comparison of the precise differing fragments, labelled **«في هذا الموضع»** and **«في الموضع المشابه»**. For a word or vowel error, show the selected word prominently. Use verified Qaloon text and avoid inventing an explanation of the verse.

**Acceptance:** with scans available, the chosen word or differing ending remains visibly identified. The result must also work when image fallback is used.

Evidence: [correction](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/build/ux-review-20261015/04-correction.png), [scheduled answer](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/build/ux-review-20261015/03-review-answer.png). Sources: [focus passed only to fallback](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ui/HifzSheets.kt:1003), [word focus](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ui/HifzSheets.kt:1047), [image/fallback selection](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ui/QuranExcerpt.kt:144).

### 4. The review layout makes the essential comparison compete with controls

**Fix first · Layout and accessibility improvement · Device confirmed**

After a practice failure at font scale 1.15, the similar passage's ending fell below the visible comparison area while the instruction, repetition button, retry button, and “later” button occupied the lower part of the card. The learner can scroll, but must discover and operate an inner scrolling region while comparing it with the page behind the card. The title also wrapped awkwardly after Undo appeared.

At font scale 1.5, surrounding labels and controls enlarged, but the Qur'an image stayed at roughly the same width-based scale. More space was consumed without making the small Qur'anic words easier to inspect. The excerpt component has no direct enlargement interaction.

**Change:** give correction its own focused layout with one main scrolling surface. Keep the exact contrast above a compact action area; offer tap-to-enlarge for the passage. Use a short stage indicator such as **«تدريب · 1 من 5»**. Preserve an easy route to the mushaf page.

**Acceptance:** at scales 1.15 and 1.5, both target fragments and the next action are discoverable without hunting through separate scroll regions; the verse itself can be enlarged.

Evidence: [after an error](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/build/ux-review-20261015/07-practice-after-error.png), [large text](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/build/ux-review-20261015/15-correction-large-text.png). Sources: [two scrolling regions](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ui/HifzReview.kt:519), [fixed image sizing](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ui/QuranExcerpt.kt:227).

### 5. The learner's memory cue is hidden with reminder and management options

**Next · Information hierarchy improvement · Source-confirmed**

**«لماذا يلتبس؟»** and **«ما يساعدني على التذكّر»** sit behind the initially closed **«ملاحظتي والتذكير وخيارات الموضع»** control. An existing mnemonic can therefore be absent from the main correction view, alongside an available explanation of the confusion.

**Change:** show the existing mnemonic and useful comparison explanation immediately under the correction. Keep reminder configuration and deletion in the expandable management area. During an independent attempt, keep the mnemonic hidden until explicitly requested, counting that request as help.

**Acceptance:** opening a correction exposes the learner's saved cue without opening administrative options; opening a fresh recall does not expose it automatically.

Source: [collapsed management area and learning content](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ui/HifzSheets.kt:839).

### 6. After a hint, two different answers save the same outcome

**Next · Interaction defect · Source-confirmed**

Once help was used, the first grade becomes **«بمساعدة»**, while the second still says **«بتردد أو تلميح»**. Both produce the hesitation result. The prominent first button suggests a meaningful distinction that the application does not make.

**Change:** after help, offer **«تذكّرتها بالمساعدة»** and **«لم أتذكّرها»**, then lead into an unaided retry. Before help, retain three distinct outcomes. Give the initial self-assessment choices comparable visual weight so the green treatment does not suggest which answer is preferred.

**Acceptance:** every visible choice has a distinct meaning, and a helped attempt cannot be labelled independent.

Sources: [labels and controls](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ui/HifzReview.kt:739), [assisted-grade conversion](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ui/HifzReview.kt:276).

### 7. Full correction loses its direct retry action during a review

**Next · Flow inconsistency · Source-confirmed**

Outside a review, the correction sheet offers **«سمّع دون مساعدة»**. The same sheet opened over an active review removes that action. After studying or repeating there, the learner must close the sheet and find the retry action on the underlying card.

**Change:** once the initial grade has been recorded, let the correction sheet's recall button return directly to the current card's retest state. Before grading, lead back to self-assessment without creating an additional scheduled result.

**Acceptance:** correcting and retrying from the expanded sheet takes one explicit action and preserves the active session and its Undo behavior.

Source: [recall removed when over a review](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ui/QuranScreen.kt:2258).

### 8. Recording a weakness requires details the learner may not know yet

**Next · Capture limitation · Device and source confirmed**

The word-error flow requires selecting an exact word before saving. Someone who knows they stumbled in the verse but cannot locate the word cannot save an honest incomplete record. Similar-verse errors already allow uncertainty, making the difference harder to understand. Separately, skip destinations are limited to five verses ahead.

**Change:** permit **«لم أحدد الكلمة بعد»** and mark the record as needing detail. Let the learner refine it during correction. Use the existing verse finder for a skip destination outside the short suggestion list.

**Acceptance:** a learner can capture a known weakness immediately without choosing an inaccurate type or word; incomplete detail remains visible and editable.

Evidence: [word selection with disabled save](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/build/ux-review-20261015/14-record-word.png). Sources: [required word selection](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ui/HifzSheets.kt:455), [save validation](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ui/HifzSheets.kt:511), [skip options](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ui/HifzSheets.kt:467).

### 9. “Recite a passage” needs a clear task and endpoint

**Next · Product opportunity · Source-confirmed**

**«سمّع مقطعًا»** chooses the current page or another starting verse, then enables the reader's general verse mask. The instruction explaining how to check and record a difficulty is a temporary message. This is useful free practice, but it does not establish a bounded passage, a completion step, or a recap.

**Change:** offer a lightweight passage session: choose a page or start/end, show **«أنت تسمّع…»**, keep a visible way to record a difficulty, and finish with **«أنهيت التسميع»**. A recap can show the passages attempted and difficulties recorded without claiming automated correctness. Keep free practice available.

**Acceptance:** the user can tell where the attempt starts and ends, recover instructions after the temporary message disappears, and finish deliberately.

Source: [startReciting](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ui/QuranScreen.kt:917).

### 10. Progress reports activity more clearly than improvement

**Next · Feedback improvement · Device and source confirmed**

Home shows the latest result per point today, falling back to the last seven days only when today is empty. Starting today's work therefore changes the period being summarized. A point's correction shows its latest feedback and total slips, but not the sequence of dated outcomes. The learner cannot easily answer “Is this particular confusion improving?”

The practice summary also repeated the same success in two sentences during the observed one-point retry.

**Change:** keep a stable weekly summary alongside today. Add a short dated history per point and distinguish **first-attempt recall**, **recall after practice**, and **still awaiting practice**. Explain the next check in plain language. Avoid equating an immediate successful retry with durable mastery.

**Acceptance:** a point with failure → hesitation → independent recall has an understandable visible history; practising successfully does not rewrite its first-attempt result.

Evidence: [home](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/build/ux-review-20261015/01-home.png), [practice summary](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/build/ux-review-20261015/10-practice-summary.png). Sources: [today/weekly selection](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ui/HifzHome.kt:98), [point feedback](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ui/HifzSheets.kt:822), [summary](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ui/HifzReview.kt:815).

### 11. The home screen is too dense for both starting and finding work

**Later · Navigation improvement · Device confirmed**

At the user's normal scale, the first viewport contained the large daily card, two start actions, progress, notebook heading, three state chips, two filters, and only part of one notebook row. Useful preparation and help actions live below all records. As the notebook grows, finding a particular weakness becomes harder; it has filters but no direct search.

**Change:** make home a compact launch point: today's batch, pending practice, and a clear entry to **«دفتر المواضع»**. Give the notebook its own search and filters. Move **«قبل التسميع»** beside the passage-session entry. Keep the daily batch size clear without displaying every management control before the first record.

**Acceptance:** a returning user can start, resume unfinished practice, or find a known point without first scrolling through unrelated content.

Evidence: [home viewport](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/build/ux-review-20261015/01-home.png). Sources: [home hierarchy](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ui/HifzHome.kt:145), [filters](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ui/HifzHome.kt:208), [footer actions](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ui/HifzHome.kt:245).

### 12. The optional reminder choice should be editable beside Save

**Later · Decision clarity improvement · Device and source confirmed**

Recording says reminders are optional, but the observed default was **after every prayer for three days**. The footer discloses that plan; it is not hidden. However, changing or declining it is inside a customization area that can be below the word choices and out of view.

**Change:** put an editable reminder choice beside the save action: **«في المراجعات فقط»** or **«ذكّرني…»**, with the chosen schedule visible. Keep detailed timing in the expanded editor. Preserve existing plans unless the user changes them.

**Acceptance:** the learner can save with scheduled checks only, or choose extra reminders, without searching through the rest of the form.

Evidence: [recording footer](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/build/ux-review-20261015/14-record-word.png). Sources: [collapsed customization](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ui/HifzSheets.kt:483), [default schedule](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/quran/hifz/Hifz.kt:509).

## Proposed journey

| Stage | Main content | Main action |
| --- | --- | --- |
| Today | Small batch, approximate duration, unfinished practice | ابدأ مراجعة اليوم / أكمل التدريب |
| Recall | Covered target with enough preceding context | أظهر وقارن |
| Self-assessment | Distinct outcomes; help already used is explicit | تذكّرتها / ترددت / لم أتذكّرها |
| Correction | Exact word or contrast, surrounding verse, personal cue | استمع وكرّر |
| Retry | Answer and cues hidden again | أظهر وقارن |
| Finish | First-attempt results, recovered points, pending practice, next check | أكمل التدريب / تم |

Every “later” action must leave a discoverable continuation. Keep the existing separation between immediate practice and scheduled recall. Retain the mushaf context, Arabic-first language, self-assessment, and Undo.

After a successful immediate retry, an optional mixed round can revisit repaired points after other material. This is a design proposal, not a claim of measured learning improvement. It should leave scheduled dates unchanged.

One small copy correction can accompany the work: home says **«ما يحتاج عناية أولًا»**, while selected batches are then sorted into mushaf order. Either preserve care-first ordering or say that care determines which points are selected. Sources: [home wording](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/ui/HifzHome.kt:156), [ordering](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/app/src/main/java/com/tunisianprayertimes/quran/hifz/Hifz.kt:267).

## Recommended implementation order

1. **Preserve learning continuity and reliable results:** findings 1, 2, 6, and 7. These can be addressed within the current structure.
2. **Make correction understandable and readable:** findings 3, 4, and 5. Validate long verses, similar endings, word/vowel errors, and larger text on the OnePlus.
3. **Reduce effort around the session:** findings 8–12. Add the bounded passage journey and stronger history after the core correction flow works well.

For validation, use isolated fixture records for reciprocal pairs and multi-batch failures. Confirm that pending practice survives exit, that exposure prevents false independent credit, and that immediate retry never advances the schedule twice. Recheck the user's existing notebook for preservation after any future implementation.

## Implementation follow-up — 15 October 2026

All twelve findings have been implemented. The original findings above remain as the review snapshot; their source line numbers describe that snapshot.

| Finding | Implemented behavior |
| --- | --- |
| 1 | Pending practice is stored independently of scheduled checks and survives batches, leaving the review, and app restart. Home and session summaries offer continuation and explicit dismissal. |
| 2 | Visible correction/recording excerpts, revealed cues, and actually played audio contribute to a daily exposure record. Exposed answers are practice-only; they do not advance scheduled checks. Background playback preserves this record, concurrent writes merge it, and new-day review state is reset. |
| 3 | Selected words and contrasting fragments appear above the mushaf excerpts, including with scans available. |
| 4 | Review content and actions share one scroll region. Stage headers are shorter. Excerpts open in a full-screen viewer with enlargement controls and a scalable text alternative that preserves question masking. |
| 5 | Existing memory cues and verified comparison explanations are visible in correction; reminder and deletion settings remain separately expandable. |
| 6 | Helped attempts have two distinct choices. Self-assessment buttons have equal visual weight. |
| 7 | Full correction during a graded review offers a direct unaided retry without creating a second scheduled result. |
| 8 | An explicit unknown-word choice permits incomplete records, visibly marked for later refinement. Skip destinations can use the verse finder for any later verse in the same surah. |
| 9 | Passage practice has chosen start/end verses, persistent on-screen instructions, a difficulty-recording action, and a deliberate finish/recap. Temporary masking does not overwrite the user's reader preference. |
| 10 | Today and the last seven days remain visible together, based on first scheduled attempts. Each point has dated scheduled-check history. Immediate recovery stays distinct from scheduled recall. |
| 11 | Home provides compact task entries; the notebook has a separate view with search and filters. Preparation is beside passage recitation. |
| 12 | Checks-only and additional-reminder choices are directly beside Save. Existing reminder plans remain editable. |

Additional user feedback was incorporated:

- The hesitation-at-start case is now the single category **«نسيتها»**. Choosing it offers **«هذه الآية»** or **«الآية التي بعدها»**. The latter records the following verse and trains the connection from the verse just recited; existing `start` records retain their identity and history.
- Every recall question has preceding-verse context, including word/ending errors. The missing-image and accessibility paths show the preceding verse plus a clear covered-answer slot, rather than contextless dots. First-verse questions identify the surah.
- New save/Undo notices scroll into view rather than being inserted above the visible notebook/home list.

Validation evidence is in [the implementation evidence folder](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/build/ux-fixes-20261015/after). In particular:

- [Exact ending comparison](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/build/ux-fixes-20261015/after/06-correction.png).
- [Enlargement at 200%](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/build/ux-fixes-20261015/after/08-zoom-200.png).
- [Direct retry from full correction](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/build/ux-fixes-20261015/after/13-direct-retest.png).
- [Pending practice after a cold restart](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/build/ux-fixes-20261015/after/14-resumed-pending.png).
- [The merged forgotten-verse case selecting the following verse](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/build/ux-fixes-20261015/after/19-forgot-next.png).
- [Verse 41 with preceding context at font scale 1.5](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/build/ux-fixes-20261015/after/31-word-recall-context-large.png).
- [Bounded passage setup](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/build/ux-fixes-20261015/after/33-passage-setup.png) and [completion recap](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/build/ux-fixes-20261015/after/35-passage-summary.png).

The Kotlin build and 56 focused checks passed: 29 existing Hifz model checks, 22 review-state checks, and five persistence checks. Later wording/layout integration changes were compiled and checked on the OnePlus. Device checks cover the main flows and text scaling, not an empirical evaluation of memorization outcomes, screen-reader use, or timed notification delivery.

Final device verification: the development build was installed successfully, a temporary unknown-word record was saved with checks-only reminders, [its confirmation and Undo remained visible](C:/Users/barou/Desktop/Workspace/TunisianPrayerTimes/android-app/build/ux-fixes-20261015/after/41-save-notice-visible.png), and Undo restored the notebook exactly. All **13 pre-test records, schedules, and history matched the fresh pre-install copy**. Temporary practice/exposure state created during validation was removed; font scale was restored to **1.15**. The updated app was left on its review home screen. The first device pass caught a null-audio integration crash; filtering was moved before the screen-content lambda and subsequent cold launches passed.
