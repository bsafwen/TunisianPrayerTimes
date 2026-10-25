package com.tunisianprayertimes.quran.hifz

import com.tunisianprayertimes.ui.freshDone
import android.app.Application
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.quran.QuranCatalog
import com.tunisianprayertimes.quran.QuranMushafText
import com.tunisianprayertimes.quran.QuranPage
import com.tunisianprayertimes.quran.QuranSearchEntry
import com.tunisianprayertimes.quran.QuranSurah
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.audio.QuranPlaylist
import com.tunisianprayertimes.quran.audio.QuranPlaylistStep
import com.tunisianprayertimes.quran.audio.QuranRepeatRange
import com.tunisianprayertimes.quran.audio.after
import com.tunisianprayertimes.quran.audio.before
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HifzTest {
    private fun verse(surah: Int, ayah: Int) = QuranVerseReference(surah, ayah)

    private fun point(kind: HifzKind, verse: QuranVerseReference, other: QuranVerseReference? = null, step: Int = 0, slips: Int = 1, due: Long = 100L) =
        HifzPoint(id = verse.surah * 1000L + verse.ayah, verse = verse, mistakes = listOf(HifzMistake(kind, other)), slips = slips, step = step, dueDay = due, createdAt = 0L)

    /** The point's one mistake, changed. */
    private fun HifzPoint.mistake(change: HifzMistake.() -> HifzMistake) = copy(mistakes = listOf(mistakes.single().change()))

    /** The fix of the point's one mistake. */
    private fun hifzFix(catalog: QuranCatalog, point: HifzPoint) = hifzFix(catalog, point, point.mistakes.single())

    @Test
    fun ladder_goesThroughOneThreeSevenFourteenThenHolds() {
        var p = point(HifzKind.Ending, verse(2, 17))
        val days = mutableListOf<Long>()
        var today = 100L
        repeat(6) {
            p = p.graded(HifzGrade.Held, today, now = 5L)
            days += p.dueDay - today
            today = p.dueDay
        }
        assertEquals(listOf(3L, 7L, 14L, 30L, 60L, 60L), days)
        assertTrue(p.mastered)
        assertEquals(5L, p.masteredAt)
    }

    @Test
    fun oldNotebook_keepsItsScheduleWithoutInventingRecallHistory() {
        val original = point(HifzKind.Word, verse(2, 18), step = 2, slips = 4, due = 120L)
        val old = JSONObject(encodeHifzPoints(listOf(original)))
        old.getJSONArray("points").getJSONObject(0).remove("checks")

        val restored = decodeHifzPoints(old.toString()).single()
        assertEquals(original, restored)
        assertTrue(restored.checks.isEmpty())
        assertNull(restored.recentFeedback)
        assertTrue(restored.needsCare)
    }

    @Test
    fun grading_preservesRecentEvidenceAcrossNotebookSerialization() {
        val previous = (1L..60L).map { HifzCheck(100L + it, HifzGrade.Held, it) }
        val original = point(HifzKind.Word, verse(2, 18), step = 4).copy(checks = previous)
        val graded = original.graded(HifzGrade.Again, today = 161L, now = 61L)
        val expected = previous.drop(1) + HifzCheck(161L, HifzGrade.Again, 61L)

        assertEquals(expected, graded.checks)
        assertEquals(expected, decodeHifzChecks(encodeHifzChecks(graded.checks)))
        assertEquals(graded, decodeHifzPoints(encodeHifzPoints(listOf(graded))).single())
    }

    @Test
    fun careFollowsRecentDifficulty_andRequiresRecoveryAfterANewSlip() {
        val fresh = point(HifzKind.Start, verse(2, 18))
        val hesitant = fresh.graded(HifzGrade.Hesitated, 100L, 10L)
        assertFalse(hesitant.needsCare)
        val struggling = hesitant.graded(HifzGrade.Hesitated, 102L, 20L)
        assertTrue(struggling.needsCare)
        val improving = struggling.graded(HifzGrade.Held, 104L, 30L)
        assertTrue(improving.needsCare)
        val recovered = improving.graded(HifzGrade.Held, 107L, 40L)
        assertFalse(recovered.needsCare)

        // A newly recorded slip invalidates the old run of successes, including after a restart.
        val slippedAgain = recovered.copy(slips = recovered.slips + 1, step = 0, lastSlipAt = 50L)
        val restored = decodeHifzPoints(encodeHifzPoints(listOf(slippedAgain))).single()
        assertTrue(restored.needsCare)
        val firstRecovery = restored.graded(HifzGrade.Held, 108L, 60L)
        assertTrue(firstRecovery.needsCare)
        assertFalse(firstRecovery.graded(HifzGrade.Held, 111L, 70L).needsCare)
        assertTrue(fresh.graded(HifzGrade.Again, 100L, 10L).needsCare)
        // Two right answers at two reminders of one day count once: the care stays until another day's.
        val chronic = recovered.copy(slips = 5, step = 0, lastSlipAt = 80L, fresh = HifzFreshRecalls)
        val sameDay = chronic.graded(HifzGrade.Held, 112L, 90L).graded(HifzGrade.Held, 112L, 100L)
        assertTrue(sameDay.needsCare)
        assertFalse(sameDay.graded(HifzGrade.Held, 113L, 110L).needsCare)
    }

    @Test
    fun aSlip_isAskedAtEveryReminderUntilReadRightThreeTimes_thenItsChecksSpaceOut() {
        val gap = HifzFreshGapMs
        var p = point(HifzKind.Ending, verse(2, 17)).copy(fresh = HifzFreshRecalls, dueDay = 100L, lastSlipAt = 1_000L)
        // Not right after the slip, which is still in mind: once its gap has passed.
        assertFalse(p.isDue(100L, 1_000L + gap - 1))
        assertTrue(p.isDue(100L, 1_000L + gap))
        // A hesitation asks again at the next reminder, without counting.
        p = p.graded(HifzGrade.Hesitated, 100L, now = 10_000_000L)
        assertEquals(HifzFreshRecalls, p.fresh)
        assertEquals(100L, p.dueDay)
        assertFalse(p.isDue(100L, 10_000_000L + gap - 1))
        // Right twice: one more to go, still today.
        p = p.graded(HifzGrade.Held, 100L, now = 20_000_000L).graded(HifzGrade.Held, 100L, now = 30_000_000L)
        assertEquals(1, p.fresh)
        assertTrue(p.spacesNext)
        assertFalse(p.holdsNext)
        assertEquals(1, p.daysAfter(HifzGrade.Held))
        // The third: its checks space out from the next day, along the ladder.
        p = p.graded(HifzGrade.Held, 100L, now = 40_000_000L)
        assertEquals(0, p.fresh)
        assertEquals(0, p.step)
        assertEquals(101L, p.dueDay)
        assertFalse(p.isDue(100L, Long.MAX_VALUE))
        assertEquals(3, p.daysAfter(HifzGrade.Held))
        assertEquals(104L, p.graded(HifzGrade.Held, 101L, 50_000_000L).dueDay)
        // A failed check, later on, makes it fresh again, a slip counted.
        val failed = p.copy(step = HifzMasteredStep, masteredAt = 7L).graded(HifzGrade.Again, 130L, now = 60_000_000L)
        // A verse that had held is relearned faster: 2 right answers.
        assertEquals(2, failed.fresh)
        assertEquals(0, failed.step)
        assertNull(failed.masteredAt)
        assertEquals(130L, failed.dueDay)
        assertEquals(60_000_000L, failed.lastSlipAt)
        assertEquals(p.slips + 1, failed.slips)
        assertEquals(0, failed.daysAfter(HifzGrade.Again))
    }

    @Test
    fun aVerseLookedAt_waitsByItsPhase_aFreshPointAWhile_aSpacedCheckADay() {
        val fresh = point(HifzKind.Word, verse(2, 18)).copy(fresh = 2, dueDay = 100L, lastSlipAt = 0L)
        val check = point(HifzKind.Skip, verse(2, 68), due = 100L)
        val now = HifzFreshGapMs
        assertEquals(listOf(fresh, check), listOf(fresh, check).askable(100L, now))
        // Seen today: the spaced check is tomorrow's; the fresh point goes by when it was seen, not by the day.
        assertEquals(listOf(fresh), listOf(fresh, check).askable(100L, now, seenToday = setOf(fresh.verse, check.verse)))
        assertTrue(check.isDue(101L, now, seenToday = emptySet()))
        // Its answer seen a moment ago, in its fix or on the page, puts the fresh point's question a while after.
        val seen = mapOf(fresh.verse to now - 60_000L)
        assertEquals(listOf(check), listOf(fresh, check).askable(100L, now, seen))
        assertEquals(now - 60_000L + HifzFreshGapMs, fresh.freshDueAt(seen[fresh.verse]))
        assertTrue(listOf(fresh).askable(100L, now - 60_000L + HifzFreshGapMs, seen).isNotEmpty())
        // A new slip comes first in a full batch, ahead of older checks that need care.
        val older = (1..5).map { point(HifzKind.Word, verse(3, it), slips = 4, due = 90L) }
        assertEquals(fresh, (older + fresh).reviewBatch(100L, now, limit = 5).first { it.fresh > 0 })
        assertEquals(5, (older + fresh).reviewBatch(100L, now, limit = 5).size)
    }

    @Test
    fun theReminder_comesAfterAPrayerOnceAFreshPointIsDue_andOnceADayForTheChecks() {
        // A day's reminders after prayers, in minutes: Fajr 05:00, Dhuhr 13:00, Asr 16:00, Maghrib 19:00, Isha 20:30.
        val minutes = mapOf(Prayer.FAJR to 300L, Prayer.DHUHR to 780L, Prayer.ASR to 960L, Prayer.MAGHRIB to 1140L, Prayer.ISHA to 1230L)
        val min = 60_000L
        val slotAt = { day: Long, time: HifzWhen -> (day * 1440 + minutes.getValue((time as HifzWhen.After).prayer)) * min }
        val utc = java.time.ZoneOffset.UTC
        val times = HifzTimesPreset.Prayers.times
        val day = 100L
        // A slip at 12:50: Dhuhr at 13:00 is too soon after it, Asr asks it.
        val slip = point(HifzKind.Word, verse(2, 18)).copy(fresh = HifzFreshRecalls, dueDay = day, lastSlipAt = (day * 1440 + 770) * min)
        assertEquals(
            HifzSlot(day, HifzWhen.After(Prayer.ASR), slotAt(day, HifzWhen.After(Prayer.ASR))),
            listOf(slip).nextReminder(times, day, (day * 1440 + 771) * min, remindedDay = day, zone = utc, slotAt = slotAt),
        )
        // Answered at Maghrib 19:10: Isha at 20:30 is past its gap and asks it again.
        val answered = slip.copy(checks = listOf(HifzCheck(day, HifzGrade.Held, (day * 1440 + 1150) * min)))
        assertEquals(
            HifzWhen.After(Prayer.ISHA),
            listOf(answered).nextReminder(times, day, (day * 1440 + 1151) * min, remindedDay = day, zone = utc, slotAt = slotAt)?.time,
        )
        // A check due tomorrow comes at its first reminder, after Fajr; once that day was reminded, the day after.
        val check = point(HifzKind.Skip, verse(2, 68), due = day + 1)
        val evening = (day * 1440 + 1300) * min
        assertEquals(
            HifzSlot(day + 1, HifzWhen.After(Prayer.FAJR), slotAt(day + 1, HifzWhen.After(Prayer.FAJR))),
            listOf(check).nextReminder(times, day, evening, remindedDay = day, zone = utc, slotAt = slotAt),
        )
        val morning = (day * 1440 + 1440 + 400) * min
        assertEquals(
            HifzSlot(day + 2, HifzWhen.After(Prayer.FAJR), slotAt(day + 2, HifzWhen.After(Prayer.FAJR))),
            listOf(check).nextReminder(times, day + 1, morning, remindedDay = day + 1, zone = utc, slotAt = slotAt),
        )
        // Turned on in the afternoon, a check due today is not left for tomorrow.
        assertEquals(
            HifzWhen.After(Prayer.ASR),
            listOf(check.copy(dueDay = day)).nextReminder(times, day, (day * 1440 + 900) * min, remindedDay = day - 1, zone = utc, slotAt = slotAt)?.time,
        )
        // Its verse looked at today: the check is the next day's.
        assertEquals(
            day + 1,
            listOf(check.copy(dueDay = day)).nextReminder(times, day, (day * 1440 + 900) * min, remindedDay = day - 1, seenToday = setOf(check.verse), zone = utc, slotAt = slotAt)?.day,
        )
        // Nothing to ask, no reminder.
        assertNull(emptyList<HifzPoint>().nextReminder(times, day, 0L, remindedDay = -1L, zone = utc, slotAt = slotAt))
        // A prayer whose time is not known is skipped for the next one.
        assertEquals(
            HifzWhen.After(Prayer.MAGHRIB),
            listOf(slip).nextReminder(times, day, (day * 1440 + 771) * min, remindedDay = day, zone = utc) { d, time ->
                if (time == HifzWhen.After(Prayer.ASR)) null else slotAt(d, time)
            }?.time,
        )
    }

    @Test
    fun theReminder_findsAnIshaThatEndsAfterMidnight_andNamesAWeekAhead() {
        // In minutes: Isha's reminder at 00:30, after its silence that ends past midnight.
        val minutes = mapOf(Prayer.FAJR to 300L, Prayer.DHUHR to 780L, Prayer.ASR to 960L, Prayer.MAGHRIB to 1140L, Prayer.ISHA to 1470L)
        val min = 60_000L
        val slotAt = { day: Long, time: HifzWhen -> (day * 1440 + minutes.getValue((time as HifzWhen.After).prayer)) * min }
        val slip = point(HifzKind.Word, verse(2, 18)).copy(fresh = 1, dueDay = 100L, lastSlipAt = 0L)
        // Just after midnight on day 101, yesterday's Isha reminder is the next one, before Fajr.
        assertEquals(
            HifzSlot(100L, HifzWhen.After(Prayer.ISHA), (101L * 1440 + 30) * min),
            listOf(slip).nextReminder(HifzTimesPreset.Prayers.times, 101L, (101L * 1440 + 10) * min, remindedDay = 101L, zone = java.time.ZoneOffset.UTC, slotAt = slotAt),
        )
        // That Isha is day 100's: a check due on day 101 waits for day 101's Fajr.
        val check = point(HifzKind.Skip, verse(2, 68), due = 101L)
        assertEquals(
            HifzSlot(101L, HifzWhen.After(Prayer.FAJR), (101L * 1440 + 300) * min),
            listOf(check).nextReminder(HifzTimesPreset.Prayers.times, 100L, (100L * 1440 + 1300) * min, remindedDay = 100L, zone = java.time.ZoneOffset.UTC, slotAt = slotAt),
        )
        // 2026-10-08 is a Thursday: a week on, the name would read as today.
        val thursday = java.time.LocalDate.of(2026, 10, 8).toEpochDay()
        assertEquals("اليوم", HifzReminder.dayLabel(thursday, thursday))
        assertEquals("غدًا", HifzReminder.dayLabel(thursday + 1, thursday))
        assertEquals("السبت", HifzReminder.dayLabel(thursday + 2, thursday))
        assertEquals("الخميس القادم", HifzReminder.dayLabel(thursday + 7, thursday))
        assertEquals("الجمعة القادمة", HifzReminder.dayLabel(thursday + 8, thursday))
        // Two weeks or more ahead, with the date: «القادمة» would name the nearer Friday.
        assertEquals("الجمعة 23 أكتوبر", HifzReminder.dayLabel(thursday + 15, thursday))
    }

    @Test
    fun theReminderTimes_mixPrayersAndClockTimes() {
        // In minutes: Fajr's reminder at 05:00, Isha's at 21:00; the reader's own times 13:00 and 22:30.
        val prayerAt = mapOf(Prayer.FAJR to 300L, Prayer.ISHA to 1260L)
        val slotAt = { day: Long, time: HifzWhen ->
            (day * 1440 + when (time) {
                is HifzWhen.After -> prayerAt.getValue(time.prayer)
                is HifzWhen.At -> time.minute.toLong()
            }) * 60_000L
        }
        val utc = java.time.ZoneOffset.UTC
        val custom = HifzTimes(setOf(Prayer.ISHA, Prayer.FAJR), setOf(1350, 780))
        assertEquals(4, custom.count)
        assertNull(HifzTimesPreset.of(custom))
        assertEquals(listOf(HifzWhen.After(Prayer.FAJR), HifzWhen.After(Prayer.ISHA), HifzWhen.At(780), HifzWhen.At(1350)), custom.times)
        // At 10 the next is 13:00 by the clock, then Isha, then 22:30, then Fajr the next day.
        assertEquals(HifzSlot(100L, HifzWhen.At(780), (100L * 1440 + 780) * 60_000L), custom.firstAfter((100L * 1440 + 600) * 60_000L, zone = utc, slotAt = slotAt))
        assertEquals(HifzWhen.After(Prayer.ISHA), custom.firstAfter((100L * 1440 + 800) * 60_000L, zone = utc, slotAt = slotAt)?.time)
        assertEquals(HifzWhen.At(1350), custom.firstAfter((100L * 1440 + 1300) * 60_000L, zone = utc, slotAt = slotAt)?.time)
        assertEquals(HifzSlot(101L, HifzWhen.After(Prayer.FAJR), (101L * 1440 + 300) * 60_000L), custom.firstAfter((100L * 1440 + 1400) * 60_000L, zone = utc, slotAt = slotAt))
        // From a later day: its first time.
        assertEquals(HifzSlot(105L, HifzWhen.After(Prayer.FAJR), (105L * 1440 + 300) * 60_000L), custom.firstAfter(0L, 105L, utc, slotAt))
        // Stored as its times, and read back; none, or what is not a time, reads as nothing.
        assertEquals("FAJR,ISHA,at780,at1350", custom.encode())
        assertEquals(custom, HifzTimes.decode(custom.encode()))
        assertNull(HifzTimes.decode(""))
        assertNull(HifzTimes.decode("FAJR,at9999"))
        assertEquals(HifzTimesPreset.Once, HifzTimesPreset.of(HifzTimes.decode("FAJR")!!))
        // In words, with the clock in western digits.
        assertEquals("بعد الفجر والعشاء وفي الساعة\u00A013:00 و22:30", HifzReminder.timesPhrase(custom))
        assertEquals("بعد الفجر والظهر والعشاء", HifzReminder.timesPhrase(HifzTimesPreset.Thrice.times))
        assertEquals("كل يوم بعد الفجر", HifzReminder.timesPhrase(HifzTimesPreset.Once.times))
        assertEquals("في الساعة\u00A008:05", HifzReminder.timesPhrase(HifzTimes(minutes = setOf(485))))
        assertEquals("بعد كل صلاة وفي الساعة\u00A022:30", HifzReminder.timesPhrase(HifzTimes(HifzPrayers.toSet(), setOf(1350))))
        assertEquals("اليوم الساعة\u00A013:00", HifzReminder.slotLabel(HifzSlot(100L, HifzWhen.At(780), 0L), 100L))
        assertEquals("3\u00A0مرات", HifzReminder.recallsPhrase(3))
        assertEquals("مرتين", HifzReminder.recallsPhrase(2))
        assertEquals("مرة واحدة", HifzReminder.recallsPhrase(1))
    }

    @Test
    fun aFreshPoint_isSavedAndReadBack() {
        val fresh = point(HifzKind.Word, verse(2, 18)).mistake { copy(slipWords = setOf(3)) }.copy(fresh = 2)
        val plain = point(HifzKind.Skip, verse(2, 68))
        assertEquals(listOf(fresh, plain), decodeHifzPoints(encodeHifzPoints(listOf(fresh, plain))))
        // A point on the ladder writes nothing for it, and reads back as on the ladder.
        val old = JSONObject(encodeHifzPoints(listOf(plain)))
        assertFalse(old.getJSONArray("points").getJSONObject(0).has("fresh"))
        assertEquals(0, decodeHifzPoints(old.toString()).single().fresh)
    }

    @Test
    fun hesitationWaitsTwoDaysOneStepLower_andAnotherSlipStartsAgain() {
        val p = point(HifzKind.Skip, verse(2, 68), step = 3)
        val hesitated = p.graded(HifzGrade.Hesitated, 100L, 0L)
        assertEquals(102L, hesitated.dueDay)
        // One step lower: the next «أتقنتها» waits the 14 days again, and «ثبتت» is never earned on the 2-day re-check.
        assertEquals(2, hesitated.step)
        assertEquals(14, hesitated.daysAfter(HifzGrade.Held))
        assertFalse(hesitated.graded(HifzGrade.Held, 102L, 0L).mastered)
        val again = p.graded(HifzGrade.Again, 100L, 1_000L)
        assertEquals(0, again.step)
        assertEquals(100L, again.dueDay)
        // The slip came back: 4 right answers.
        assertEquals(4, again.fresh)
        assertEquals(30, p.daysAfter(HifzGrade.Held))
        assertTrue(p.holdsNext)
        // A held point that is hesitated on leaves «ثبتت» until it is held again; one held longer stays.
        val held = point(HifzKind.Ending, verse(2, 17), step = HifzMasteredStep).copy(masteredAt = 7L)
        assertNull(held.graded(HifzGrade.Hesitated, 100L, 0L).masteredAt)
        assertFalse(held.graded(HifzGrade.Hesitated, 100L, 0L).mastered)
        val longer = held.copy(step = HifzMasteredStep + 1)
        assertEquals(7L, longer.graded(HifzGrade.Hesitated, 100L, 0L).masteredAt)
        assertEquals(0, point(HifzKind.Word, verse(2, 18)).graded(HifzGrade.Hesitated, 100L, 0L).step)
    }

    @Test
    fun reviewEnd_saysWhereEachAnsweredPointStands_andTheLastRightAnswerAsAStep() {
        val catalog = catalog()
        val today = 100L
        val asked = point(HifzKind.Word, verse(2, 3)).copy(fresh = 2, checks = listOf(HifzCheck(today, HifzGrade.Held, 1L)))
        // Right answers given toward the plan, for the notebook's dots: one of three.
        assertEquals(1, asked.freshDone)
        assertEquals(
            "البقرة · الآية 3: بقي أن تتذكّرها مرتين، ونسألك عنها اليوم بعد المغرب.",
            com.tunisianprayertimes.ui.hifzRoundLine(catalog, asked, today, "اليوم بعد المغرب", "غدًا بعد الفجر").text,
        )
        // Asked once a day: tomorrow's first reminder.
        assertEquals(
            "البقرة · الآية 3: بقي أن تتذكّرها مرتين، ونسألك عنها غدًا بعد الفجر.",
            com.tunisianprayertimes.ui.hifzRoundLine(catalog, asked.copy(times = HifzTimes(setOf(Prayer.FAJR))), today, "اليوم بعد المغرب", "غدًا بعد الفجر").text,
        )
        // The third right answer of the day ends the daily questions: said as the step it is.
        val done = asked.copy(fresh = 0, step = 0, dueDay = today + 1)
        val step = com.tunisianprayertimes.ui.hifzRoundLine(catalog, done, today, "اليوم بعد المغرب", "غدًا بعد الفجر")
        assertEquals("البقرة · الآية 3: تذكّرتها 3\u00A0مرات دون مساعدة. نسألك عنها غدًا بعد الفجر، ثم تتباعد مواعيد مراجعتها.", step.text)
        assertTrue(step.milestone)
        assertEquals("غدًا بعد الفجر", step.next)
        // A spaced check passed: its next one, no step of its own.
        val spaced = done.copy(step = 1, dueDay = today + 3, checks = listOf(HifzCheck(today, HifzGrade.Held, 1L)))
        val later = com.tunisianprayertimes.ui.hifzRoundLine(catalog, spaced, today, "اليوم بعد المغرب", "غدًا بعد الفجر")
        assertFalse(later.milestone)
        assertNull(later.next)
    }

    @Test
    fun threeChoices_aForgottenOpeningIsAWordSlip_andALookAlikeIsOneSlipWhereverItParts() {
        val catalog = catalog()
        // Its first word forgotten: practised and heard from the verse before, as a forgotten start was.
        val opening = point(HifzKind.Word, verse(2, 30)).mistake { copy(slipWords = setOf(0)) }
        assertTrue(opening.atStart)
        assertEquals(QuranRepeatRange(verse(2, 29), verse(2, 30), 1), hifzPassage(opening))
        assertTrue(verse(2, 29) in opening.answerVerses())
        assertEquals(listOf(29, 30), com.tunisianprayertimes.ui.hifzDrillVerses(opening))
        // A word inside the verse: the verse alone.
        val inside = opening.mistake { copy(slipWords = setOf(3)) }
        assertFalse(inside.atStart)
        assertEquals(QuranRepeatRange(verse(2, 30), verse(2, 30), 1), hifzPassage(inside))
        assertFalse(verse(2, 29) in inside.answerVerses())
        // A look-alike recorded as an ending, said again from the look-alike list, is the same mistake; a word is not.
        val ending = HifzMistake(HifzKind.Ending, verse(4, 115))
        assertEquals(ending, listOf(ending).same(HifzKind.Similar, verse(4, 115)))
        assertNull(listOf(ending).same(HifzKind.Word))
        // The very look-alike first: one recorded without it is the same mistake only when none names it.
        val unknown = HifzMistake(HifzKind.Similar)
        val named = HifzMistake(HifzKind.Ending, verse(4, 115))
        assertEquals(named, listOf(unknown, named).same(HifzKind.Similar, verse(4, 115)))
        assertEquals(unknown, listOf(unknown, named).same(HifzKind.Similar, verse(2, 5)))
        // A verse the reader found: opening alike («يا أيها الذين آمنوا»), a look-alike; alike only at the end («إن الله
        // غفور رحيم»), an ending said wrong. Qaloun's numbering.
        assertEquals(HifzKind.Similar, com.tunisianprayertimes.ui.hifzLookAlikeKind(catalog, verse(2, 171), verse(2, 182)))
        assertEquals(HifzKind.Ending, com.tunisianprayertimes.ui.hifzLookAlikeKind(catalog, verse(2, 172), verse(2, 181)))
        // Three choices, the word first; an older kind shows as the choice it now belongs to.
        assertEquals(listOf("word", "lookalike", "skip"), com.tunisianprayertimes.ui.HifzSlipChoice.entries.map { it.key })
        assertEquals(com.tunisianprayertimes.ui.HifzSlipChoice.Word, com.tunisianprayertimes.ui.HifzSlipChoice.of(HifzKind.Start))
        assertEquals(com.tunisianprayertimes.ui.HifzSlipChoice.LookAlike, com.tunisianprayertimes.ui.HifzSlipChoice.of(HifzKind.Ending))
    }

    @Test
    fun gradeOutcomes_sayWhenTheVerseComesBack_onceWhenItIsTheSame() {
        val fresh = point(HifzKind.Ending, verse(2, 17))
        val slip = point(HifzKind.Ending, verse(2, 17)).copy(fresh = 2)
        val new = slip.copy(fresh = HifzFreshRecalls)
        // The same time on every answer is said once: each answer then says only what it does.
        val backs = HifzGrade.entries.map { com.tunisianprayertimes.ui.hifzGradeOutcome(new, it, "اليوم بعد المغرب", short = true) }
        assertEquals(setOf("اليوم بعد المغرب"), backs.map { it.back }.toSet())
        // The last right answer, at Isha, comes back at tomorrow's first reminder, as the others do.
        val atIsha = HifzGrade.entries.map { com.tunisianprayertimes.ui.hifzGradeOutcome(slip.copy(fresh = 1), it, "غدًا بعد الفجر", "غدًا بعد الفجر") }
        assertEquals(setOf("غدًا بعد الفجر"), atIsha.map { it.back }.toSet())
        // A spaced check's answers each come back at their own time.
        assertEquals(3, HifzGrade.entries.map { com.tunisianprayertimes.ui.hifzGradeOutcome(fresh, it, "اليوم بعد المغرب").back }.distinct().size)
        assertEquals("بعد أسبوعين", com.tunisianprayertimes.ui.hifzWhen(14L))
        assertEquals("بعد 12\u00A0يومًا", com.tunisianprayertimes.ui.hifzWhen(12L))
        assertEquals("تأخّرت مراجعتها منذ يومين", com.tunisianprayertimes.ui.hifzReviewWhen(-2L))
    }

    @Test
    fun theSmartPlan_asksMoreOfASlipThatComesBack_andLessOfAVerseThatHadHeld() {
        assertEquals(3, hifzAutoRecalls(slips = 1, heldBefore = false))
        assertEquals(4, hifzAutoRecalls(slips = 2, heldBefore = false))
        assertEquals(5, hifzAutoRecalls(slips = 3, heldBefore = false))
        assertEquals(2, hifzAutoRecalls(slips = 2, heldBefore = true))
        // Current mastery takes precedence over earlier difficulty.
        assertEquals(2, hifzAutoRecalls(slips = 4, heldBefore = true))
        // Each failed answer judges again: 3, then 4, then 5.
        var p = point(HifzKind.Word, verse(2, 18)).copy(fresh = 3)
        p = p.graded(HifzGrade.Again, 100L, 1_000L)
        assertEquals(4, p.fresh)
        p = p.graded(HifzGrade.Again, 100L, 2_000L)
        assertEquals(5, p.fresh)
        // The reader's choice wins: one right answer for a passing slip, the most for a hard one.
        assertEquals(1, p.copy(pace = HifzPace.Light).recalls)
        assertEquals(HifzMaxRecalls, point(HifzKind.Word, verse(2, 18)).copy(pace = HifzPace.Intense).recalls)
        // An explicit pace remains the reader's choice even when the slip returns.
        val light = point(HifzKind.Word, verse(2, 18)).copy(pace = HifzPace.Light, fresh = 1)
        assertEquals(HifzPace.Light, light.graded(HifzGrade.Again, 100L, 1_000L).pace)
        assertEquals(1, light.graded(HifzGrade.Again, 100L, 1_000L).fresh)
        assertEquals(1, light.recallsAfterAgain)
        val intense = point(HifzKind.Word, verse(2, 18)).copy(pace = HifzPace.Intense, fresh = 5)
        assertEquals(HifzPace.Intense, intense.graded(HifzGrade.Again, 100L, 1_000L).pace)
    }

    @Test
    fun aChangedPlan_keepsTheRightAnswersGiven() {
        // 3 asked, 1 given right since the slip: 2 left.
        val p = point(HifzKind.Word, verse(2, 18)).copy(fresh = 2, dueDay = 100L, lastSlipAt = 0L, checks = listOf(HifzCheck(99L, HifzGrade.Held, 5_000L)))
        assertEquals(1, p.rightSinceSlip)
        assertEquals(4, p.withPlan(HifzPlan(HifzPace.Intense, null), today = 100L).fresh)
        assertEquals(2, p.withPlan(HifzPlan(HifzPace.Auto, null), today = 100L).fresh)
        // One right answer is already enough: its questions end, its checks space out from tomorrow.
        val light = p.withPlan(HifzPlan(HifzPace.Light, null), today = 100L)
        assertEquals(0, light.fresh)
        assertEquals(101L, light.dueDay)
        // Trying the choices loses nothing: there and back keeps the right answer.
        assertEquals(4, p.withPlan(HifzPlan(HifzPace.Auto, null), today = 100L).withPlan(HifzPlan(HifzPace.Intense, null), today = 100L).fresh)
        // Once a day, asked today: tomorrow; back to every reminder: the next one; kept once a day, never sooner.
        val answered = p.copy(checks = listOf(HifzCheck(100L, HifzGrade.Held, 5_000L)))
        val once = HifzTimes(setOf(Prayer.FAJR))
        val daily = answered.withPlan(HifzPlan(HifzPace.Auto, once), today = 100L)
        assertEquals(101L, daily.dueDay)
        assertEquals(101L, daily.withPlan(HifzPlan(HifzPace.Intense, once), today = 100L).dueDay)
        assertEquals(100L, daily.withPlan(HifzPlan(HifzPace.Auto, null), today = 100L).dueDay)
        // On the ladder nothing is asked anew.
        assertEquals(0, point(HifzKind.Word, verse(2, 18), step = 2).withPlan(HifzPlan(HifzPace.Intense, once), today = 100L).fresh)
    }

    @Test
    fun aPointAskedAtOneTimeOfDay_isAskedOnceADay_atThatTime() {
        val min = 60_000L
        val minutes = mapOf(Prayer.FAJR to 300L, Prayer.DHUHR to 780L, Prayer.ASR to 960L, Prayer.MAGHRIB to 1140L, Prayer.ISHA to 1230L)
        val slotAt = { day: Long, time: HifzWhen -> (day * 1440 + minutes.getValue((time as HifzWhen.After).prayer)) * min }
        val utc = java.time.ZoneOffset.UTC
        val day = 100L
        // Recorded at 12:50, to be reminded after Asr only: the first question still comes today, after Asr.
        val slip = point(HifzKind.Word, verse(2, 18)).copy(fresh = 3, times = HifzTimes(setOf(Prayer.ASR)), dueDay = day, lastSlipAt = (day * 1440 + 770) * min)
        assertTrue(slip.daily)
        assertEquals(HifzWhen.After(Prayer.ASR), listOf(slip).nextReminder(HifzTimesPreset.Prayers.times, day, (day * 1440 + 771) * min, remindedDay = day, zone = utc, slotAt = slotAt)?.time)
        // Answered right at Asr: not again today, though the settings remind at Maghrib and Isha, but tomorrow after Asr.
        val answered = slip.graded(HifzGrade.Held, day, (day * 1440 + 970) * min)
        assertEquals(2, answered.fresh)
        assertEquals(day + 1, answered.dueDay)
        assertFalse(answered.isDue(day, (day * 1440 + 1200) * min))
        assertTrue(answered.isDue(day + 1, ((day + 1) * 1440 + 310) * min))
        assertEquals(
            HifzSlot(day + 1, HifzWhen.After(Prayer.ASR), slotAt(day + 1, HifzWhen.After(Prayer.ASR))),
            listOf(answered).nextReminder(HifzTimesPreset.Prayers.times, day, (day * 1440 + 971) * min, remindedDay = day, zone = utc, slotAt = slotAt),
        )
        assertEquals(1, answered.daysAfter(HifzGrade.Hesitated))
        // Asked at Asr and left unanswered: not again after Maghrib or Isha, but tomorrow after Asr.
        assertEquals(
            HifzSlot(day + 1, HifzWhen.After(Prayer.ASR), slotAt(day + 1, HifzWhen.After(Prayer.ASR))),
            listOf(slip).nextReminder(
                HifzTimesPreset.Prayers.times, day, (day * 1440 + 971) * min, remindedDay = day, zone = utc,
                dailyAsked = mapOf(slip.id to day), slotAt = slotAt,
            ),
        )
        // A failed answer, too, waits for tomorrow.
        assertEquals(day + 1, slip.graded(HifzGrade.Again, day, (day * 1440 + 970) * min).dueDay)
    }

    @Test
    fun thePlanSurvivesTheNotebookFile_andAnOlderOneReadsAsTheDefault() {
        val planned = point(HifzKind.Word, verse(2, 18)).copy(fresh = 5, pace = HifzPace.Intense, times = HifzTimes(setOf(Prayer.FAJR), setOf(1290)), autoRecalls = 4)
        assertEquals(listOf(planned), decodeHifzPoints(encodeHifzPoints(listOf(planned))))
        val plain = point(HifzKind.Word, verse(2, 19)).copy(fresh = 3)
        val json = encodeHifzPoints(listOf(plain))
        assertFalse(json.contains("pace") || json.contains("times") || json.contains("autoRecalls"))
        assertEquals(listOf(plain), decodeHifzPoints(json))
    }

    @Test
    fun todaysChecks_putPointsNeedingCareFirstAndKeepTheLimit() {
        val points = listOf(
            point(HifzKind.Skip, verse(2, 68), due = 99L),
            point(HifzKind.Ending, verse(2, 17), slips = 3, due = 100L),
            point(HifzKind.Similar, verse(4, 47), due = 101L),
            point(HifzKind.Word, verse(1, 4), due = 98L),
        )
        assertEquals(listOf(verse(2, 17), verse(1, 4), verse(2, 68)), points.dueToday(100L, now = 0L).map { it.verse })
        assertEquals(2, points.dueToday(100L, now = 0L, limit = 2).size)
        assertTrue(points[1].needsCare)
    }

    @Test
    fun aReviewBatch_asksOneOfAPairThatShowEachOther_theOneAnsweredLongestAgo() {
        val near = point(HifzKind.Similar, verse(2, 58), other = verse(7, 161), due = 99L)
            .copy(checks = listOf(HifzCheck(90L, HifzGrade.Held, 9_000L)))
        val far = point(HifzKind.Similar, verse(7, 161), other = verse(2, 58), due = 99L)
            .copy(checks = listOf(HifzCheck(80L, HifzGrade.Held, 8_000L)))
        val between = point(HifzKind.Word, verse(3, 7), due = 99L)
        val first = point(HifzKind.Word, verse(1, 4), due = 99L)
        // A look-alike pair both ways: each answer shows the other verse. The one answered longest ago is asked, the
        // other waits for the next reminder; the rest in mushaf order.
        assertEquals(listOf(first, between, far), listOf(near, between, far, first).reviewBatch(100L, 0L, limit = 5))
        // Answered now, it is the other one's turn.
        val farAnswered = far.copy(checks = listOf(HifzCheck(100L, HifzGrade.Held, 10_000L)))
        assertEquals(listOf(first, near, between), listOf(near, between, farAnswered, first).reviewBatch(100L, 0L, limit = 5))
        // Two points of one verse take turns too; the look-alike whose answer shows that verse comes after it.
        val sameVerse = point(HifzKind.Ending, verse(2, 58), due = 99L).copy(id = 1L)
        assertEquals(listOf(sameVerse, far), listOf(near, far, sameVerse).reviewBatch(100L, 0L, limit = 5))
    }

    @Test
    fun answerSafeOrder_keepsTheListButNeverAnAnswerBeforeTheQuestionItGivesAway() {
        // «مراجعة الدفتر» goes through the notebook as listed: only a point whose answer or question shows another
        // one's verse waits for it.
        val elsewhere = point(HifzKind.Word, verse(3, 5))
        val skip = point(HifzKind.Skip, verse(2, 21))
        val before = point(HifzKind.Word, verse(2, 20))
        assertEquals(listOf(elsewhere, before, skip), listOf(elsewhere, skip, before).answerSafeOrder())
        // Look-alikes confused both ways cannot both be asked first: the list's order stands.
        val a = point(HifzKind.Similar, verse(4, 1), other = verse(4, 9))
        val b = point(HifzKind.Similar, verse(4, 9), other = verse(4, 1))
        assertEquals(listOf(b, a), listOf(b, a).answerSafeOrder())
        // A point leading into a loop waits for it: its question, showing the end of the verse before, would give
        // away one of the loop's answers too.
        val leading = point(HifzKind.Start, verse(4, 2))
        assertEquals(listOf(a, leading, b), listOf(leading, a, b).answerSafeOrder())
    }

    @Test
    fun aReviewBatch_asksAPointBeforeThoseThatShowItsVerse() {
        // A look-alike far away links two neighbours: the verse before first, then the verse whose question shows its
        // end, then the look-alike whose answer shows that verse. All three are asked.
        val lookAlike = point(HifzKind.Similar, verse(2, 3), other = verse(2, 10), due = 99L)
        val before = point(HifzKind.Word, verse(2, 9), due = 99L)
        val shown = point(HifzKind.Word, verse(2, 10), due = 99L)
        assertEquals(listOf(before, shown, lookAlike), listOf(lookAlike, before, shown).reviewBatch(100L, 0L, limit = 5))
        // A forgotten start shows the verse before it, which never shows it back: that verse first, however recently
        // it was answered.
        val word = point(HifzKind.Word, verse(2, 4), due = 99L).copy(checks = listOf(HifzCheck(99L, HifzGrade.Held, 9_900L)))
        val start = point(HifzKind.Start, verse(2, 5), due = 99L).copy(checks = listOf(HifzCheck(96L, HifzGrade.Held, 9_600L)))
        assertEquals(listOf(word, start), listOf(start, word).reviewBatch(100L, 0L, limit = 5))
        // Neighbours of other kinds keep mushaf order, and all are asked.
        val a = point(HifzKind.Word, verse(3, 9), due = 99L).copy(checks = listOf(HifzCheck(99L, HifzGrade.Held, 9_900L)))
        val b = point(HifzKind.Ending, verse(3, 10), due = 99L)
        val c = point(HifzKind.Start, verse(3, 11), due = 99L)
        assertEquals(listOf(a, b, c), listOf(c, b, a).reviewBatch(100L, 0L, limit = 5))
        // A skip shows the verse before it, which it is linked to, and nothing after it, wherever the recitation went.
        val skip = point(HifzKind.Skip, verse(4, 20), due = 99L)
        assertTrue(skip.skipped && skip.shows(verse(4, 19)))
        assertFalse(skip.shows(verse(4, 21)))
        // Where it went, told before 22 October, is not read.
        assertEquals(skip.answerVerses(), skip.copy(mistakes = listOf(HifzMistake(HifzKind.Skip, verse(4, 23)))).answerVerses())
        // A question shows the end of the verse before it; a forgotten start's answer, the verse before it, not after.
        assertTrue(start.shows(verse(2, 4)))
        assertFalse(start.shows(verse(2, 6)))
        assertTrue(b.shows(verse(3, 9)))
        assertFalse(b.shows(verse(3, 11)))
    }

    @Test
    fun aReviewBatch_takesTurnsAlsoWithAPointRankedOutOfTheRound() {
        val others = listOf(5, 6, 8, 9).map { point(HifzKind.Word, verse(it, 1), slips = 2, due = 99L) }
        val ranked = point(HifzKind.Word, verse(2, 58), slips = 2, due = 99L).copy(checks = listOf(HifzCheck(90L, HifzGrade.Held, 9_000L)))
        val waiting = point(HifzKind.Ending, verse(2, 58), due = 99L).copy(id = 1L, checks = listOf(HifzCheck(80L, HifzGrade.Held, 1_000L)))
        // The sixth by rank, but answered longer ago than the one on its verse: it takes that one's place.
        assertEquals(listOf(waiting) + others, (others + ranked + waiting).reviewBatch(100L, 0L, limit = 5))
        val answered = waiting.copy(checks = listOf(HifzCheck(100L, HifzGrade.Held, 10_000L)))
        assertEquals(listOf(ranked) + others, (others + ranked + answered).reviewBatch(100L, 0L, limit = 5))
    }

    @Test
    fun aReviewBatch_takesBackAPointThatNoLongerConflicts() {
        val check = { at: Long -> listOf(HifzCheck(80L, HifzGrade.Held, at)) }
        val a = point(HifzKind.Word, verse(2, 20), due = 90L).copy(checks = check(30_000L))
        val p1 = point(HifzKind.Ending, verse(2, 20), other = verse(2, 21), due = 91L).mistake { copy(bothWays = true) }.copy(id = 1L, checks = check(20_000L))
        val p2 = point(HifzKind.Ending, verse(2, 21), other = verse(2, 20), due = 92L).mistake { copy(bothWays = true) }.copy(id = 2L, checks = check(10_000L))
        // p1 takes a's place, then p2 takes p1's: a, which only p1 conflicted with, comes back, before p2 which shows it.
        assertEquals(listOf(a, p2), listOf(a, p1, p2).reviewBatch(100L, 1_000_000L, limit = 5))
    }

    @Test
    fun aReviewBatch_fillsFreedPlacesByRank() {
        val check = { at: Long -> listOf(HifzCheck(80L, HifzGrade.Held, at)) }
        val a = point(HifzKind.Word, verse(2, 20), due = 90L).copy(checks = check(9_000L))
        val b = point(HifzKind.Ending, verse(2, 21), other = verse(2, 20), due = 91L).copy(checks = check(9_500L))
        val (c, d, e, f) = listOf(5, 6, 8, 9).mapIndexed { i, s -> point(HifzKind.Word, verse(s, 1), due = 92L + i) }
        val p = point(HifzKind.Ending, verse(2, 20), other = verse(2, 21), due = 96L).copy(id = 1L, checks = check(5_000L))
        val g = point(HifzKind.Word, verse(10, 1), due = 97L)
        // p, waiting longest, takes the places of a and b; the sixth by rank fills the second place, not the eighth.
        assertEquals(listOf(p, c, d, e, f), listOf(a, b, c, d, e, f, p, g).reviewBatch(100L, 1_000_000L, limit = 5))
    }

    @Test
    fun aReviewBatch_breaksALoopAtThePointAnsweredLast() {
        // Each shows the next one's verse: 2:10 shows 3:5, whose question shows 3:4, whose look-alike is 2:10.
        val a = point(HifzKind.Similar, verse(2, 10), other = verse(3, 5), due = 99L).copy(checks = listOf(HifzCheck(90L, HifzGrade.Held, 3_000L)))
        val b = point(HifzKind.Word, verse(3, 5), due = 99L).copy(checks = listOf(HifzCheck(90L, HifzGrade.Held, 1_000L)))
        val c = point(HifzKind.Similar, verse(3, 4), other = verse(2, 10), due = 99L).copy(checks = listOf(HifzCheck(90L, HifzGrade.Held, 2_000L)))
        assertEquals(listOf(c, b), listOf(a, b, c).reviewBatch(100L, 0L, limit = 5))
        // Then the one left out, answered longest ago, comes back.
        val bAnswered = b.copy(checks = listOf(HifzCheck(100L, HifzGrade.Held, 10_000L)))
        val cAnswered = c.copy(checks = listOf(HifzCheck(100L, HifzGrade.Held, 10_001L)))
        assertTrue(a in listOf(a, bAnswered, cAnswered).reviewBatch(100L, 0L, limit = 5))
    }

    @Test
    fun aReviewBatch_fromAChosenPoint_leavesOutWhatItsAnswerShows() {
        val chosen = point(HifzKind.Similar, verse(2, 58), other = verse(7, 161), due = 99L)
        val lookAlike = point(HifzKind.Word, verse(7, 161), due = 99L)
        val apart = point(HifzKind.Word, verse(3, 7), due = 99L)
        assertEquals(listOf(chosen, apart), listOf(lookAlike, apart, chosen).reviewBatch(100L, 0L, limit = 5, first = chosen))
    }

    @Test
    fun settingsThatAreNoMore_areDeletedFromStorage() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("quran_hifz", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().putInt("per_day", 8).putBoolean("heads_up", true).putBoolean("stop_before", true)
            .putBoolean("briefing", true).putFloat("drill_gap", 2f).putFloat("drill_speed", 0.75f).putLong("briefed_1005", 100L)
            .putBoolean("reminder", false).commit()
        assertFalse(HifzSettings.read(context).reminder)
        assertTrue(prefs.all.keys.none { it in setOf("per_day", "heads_up", "stop_before", "briefing", "drill_gap", "drill_speed", "briefed_1005") })
        assertEquals(HifzTimesPreset.Prayers.times, HifzSettings.read(context).times)
    }

    @Test
    fun nextAsk_isTheReminderThatWillAsk_andOnceADayWaitsForTheNextDayAskedNot() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("quran_hifz", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        HifzSettings.write(context, HifzSettings(times = HifzTimes(minutes = setOf(600, 1260))))
        val zone = java.time.ZoneId.systemDefault()
        val day = 20_000L
        fun at(d: Long, minute: Int) = java.time.LocalDate.ofEpochDay(d).atTime(minute / 60, minute % 60).atZone(zone).toInstant().toEpochMilli()
        val now = at(day, 9 * 60)
        val slip = point(HifzKind.Word, verse(2, 3), due = day).copy(fresh = 3, lastSlipAt = now - 60 * 60_000L)
        // Asked at every reminder: the next one, at 10:00.
        assertEquals(at(day, 600), HifzReminder.nextAsk(context, slip, null, now)!!.at)
        // Its verse seen at 09:30 is asked no sooner than 10:15: at 21:00.
        assertEquals(at(day, 1260), HifzReminder.nextAsk(context, slip, now + 30 * 60_000L, now)!!.at)
        // Asked once a day, and already asked by a reminder today: tomorrow's first.
        prefs.edit().putString("daily_asked", "${slip.id}:$day").commit()
        assertEquals(at(day + 1, 600), HifzReminder.nextAsk(context, slip.copy(times = HifzTimes(minutes = setOf(600))), null, now)!!.at)
        // Its own times, not the settings': at 21:00 only, it is asked at 21:00.
        prefs.edit().remove("daily_asked").commit()
        assertEquals(at(day, 1260), HifzReminder.nextAsk(context, slip.copy(times = HifzTimes(minutes = setOf(1260, 1320))), null, now)!!.at)
        // A point no longer fresh, or the reminder off: none.
        assertNull(HifzReminder.nextAsk(context, slip.copy(fresh = 0), null, now))
        HifzSettings.write(context, HifzSettings(reminder = false))
        assertNull(HifzReminder.nextAsk(context, slip, null, now))
        prefs.edit().clear().commit()
    }

    /** Fixed prayers for [block], then none again. */
    private fun withPrayers(vararg spans: HifzPrayerSpan, block: () -> Unit) {
        HifzPrayerSpans.around = { spans.toList() }
        try {
            block()
        } finally {
            HifzPrayerSpans.around = { emptyList() }
        }
    }

    @Test
    fun aPrayerInBetween_doesNotShortenTheRecallWait() {
        val min = 60_000L
        // Dhuhr from 13:00 to its reminder at 13:32, as minutes from the epoch's midnight.
        val dhuhr = HifzPrayerSpan(Prayer.DHUHR, 780 * min, 812 * min)
        withPrayers(dhuhr) {
            // Recorded at 12:50: still waits 45 minutes even when the prayer ends at 13:32.
            assertEquals(815 * min, hifzWaitEnd(770 * min))
            // Recorded once it had begun: the whole wait.
            assertEquals(785 * min + HifzFreshGapMs, hifzWaitEnd(785 * min))
            // Recorded long before: the wait is over first.
            assertEquals(700 * min + HifzFreshGapMs, hifzWaitEnd(700 * min))
            // A fresh point follows it, its answer seen last as well.
            val slip = point(HifzKind.Word, verse(2, 3), due = 0L).copy(fresh = 3, lastSlipAt = 770 * min)
            assertEquals(815 * min, slip.freshDueAt)
            assertFalse(slip.isDue(0L, 814 * min))
            assertTrue(slip.isDue(0L, 815 * min))
            assertEquals(790 * min + HifzFreshGapMs, slip.freshDueAt(790 * min))
            // Dhuhr's reminder is too early: choose the next selected slot.
            val slotAt = { _: Long, time: HifzWhen -> if (time == HifzWhen.After(Prayer.DHUHR)) 812 * min else 1000 * min }
            val times = HifzTimes(prayers = setOf(Prayer.DHUHR, Prayer.ASR))
            assertEquals(1000 * min, listOf(slip).nextReminder(times, 0L, 771 * min, -1L, slotAt = slotAt)!!.at)
        }
    }

    @Test
    fun noReminderComesDuringAPrayer() {
        val min = 60_000L
        // Far north, Maghrib's span ends in Isha's.
        withPrayers(HifzPrayerSpan(Prayer.MAGHRIB, 1100 * min, 1130 * min), HifzPrayerSpan(Prayer.ISHA, 1125 * min, 1160 * min)) {
            assertEquals(1000 * min, hifzOutsidePrayers(1000 * min))
            assertEquals(1160 * min, hifzOutsidePrayers(1110 * min))
            // At a span's end, it is over.
            assertEquals(1160 * min, hifzOutsidePrayers(1160 * min))
        }
    }

    @Test
    fun aClockTimeInAPrayer_comesAfterIt_andIsSaidSo() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("quran_hifz", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val zone = java.time.ZoneId.systemDefault()
        fun at(d: Long, minute: Int) = java.time.LocalDate.ofEpochDay(d).atTime(minute / 60, minute % 60).atZone(zone).toInstant().toEpochMilli()
        // 5 October 2024, a Saturday.
        val day = 20_001L
        HifzSettings.write(context, HifzSettings(times = HifzTimes(minutes = setOf(790, 900))))
        withPrayers(HifzPrayerSpan(Prayer.DHUHR, at(day, 780), at(day, 812))) {
            // 13:10 falls in Dhuhr: it comes at 13:32, said «بعد الظهر».
            assertEquals(at(day, 812), HifzReminder.slotAt(context, day, HifzWhen.At(790)))
            val slot = HifzSlot(day, HifzWhen.At(790), at(day, 812))
            assertEquals(Prayer.DHUHR, HifzReminder.heldAfter(slot))
            assertEquals("اليوم بعد الظهر", HifzReminder.slotLabel(slot, day))
            // 15:00 is its own.
            assertEquals("اليوم الساعة${HifzReminder.NB}15:00", HifzReminder.slotLabel(HifzSlot(day, HifzWhen.At(900), at(day, 900)), day))
            // A slip at 12:30 is asked at the moved 13:10, once Dhuhr is over.
            val slip = point(HifzKind.Word, verse(2, 3), due = day).copy(fresh = 3, lastSlipAt = at(day, 750))
            assertEquals(at(day, 812), HifzReminder.nextAsk(context, slip, null, at(day, 750))!!.at)
            // Its answer seen now: the same minimum separation, independent of prayer times.
            assertEquals("بعد 45${HifzReminder.NB}دقيقة", HifzReminder.waitPhrase(at(day, 770)))
            assertEquals("بعد 45${HifzReminder.NB}دقيقة", HifzReminder.waitPhrase(at(day, 900)))
        }
        prefs.edit().clear().commit()
    }

    @Test
    fun remindersThatComeTogether_ringOnceForAll() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val zone = java.time.ZoneId.systemDefault()
        fun at(d: Long, minute: Int) = java.time.LocalDate.ofEpochDay(d).atTime(minute / 60, minute % 60).atZone(zone).toInstant().toEpochMilli()
        val day = 20_001L
        // The settings' 13:32, and a slip's own 13:10, which Dhuhr moves to 13:32 too.
        val settings = HifzSettings(times = HifzTimes(minutes = setOf(812)))
        val own = point(HifzKind.Word, verse(2, 3), due = day).copy(fresh = 3, times = HifzTimes(minutes = setOf(790)))
        withPrayers(HifzPrayerSpan(Prayer.DHUHR, at(day, 780), at(day, 812))) {
            assertEquals(mapOf(day to setOf(HifzWhen.At(790), HifzWhen.At(812))), HifzReminder.timesComing(context, settings, listOf(own), day, HifzWhen.At(790), at(day, 812), at(day, 812)))
            assertEquals(mapOf(day to setOf(HifzWhen.At(812), HifzWhen.At(790))), HifzReminder.timesComing(context, settings, listOf(own), day, HifzWhen.At(812), at(day, 812), at(day, 812)))
            // An alarm held from 12:15 to Dhuhr's end takes in what comes on the way.
            val early = point(HifzKind.Word, verse(2, 4), due = day).copy(fresh = 3, times = HifzTimes(minutes = setOf(735)))
            assertEquals(mapOf(day to setOf(HifzWhen.At(735), HifzWhen.At(790), HifzWhen.At(812))), HifzReminder.timesComing(context, settings, listOf(own, early), day, HifzWhen.At(735), at(day, 735), at(day, 812)))
            // Alone, it is alone.
            assertEquals(mapOf(day to setOf(HifzWhen.At(735))), HifzReminder.timesComing(context, settings, listOf(own, early), day, HifzWhen.At(735), at(day, 735), at(day, 736)))
        }
        // Across midnight: Isha of the day ends at 00:32, where the next day's 00:20 waits too.
        val late = HifzSettings(times = HifzTimes(minutes = setOf(20)))
        withPrayers(HifzPrayerSpan(Prayer.ISHA, at(day, 23 * 60 + 50), at(day + 1, 32))) {
            assertEquals(at(day + 1, 32), HifzReminder.slotAt(context, day + 1, HifzWhen.At(20)))
            val slip = point(HifzKind.Word, verse(2, 5), due = day).copy(fresh = 3, times = HifzTimes(minutes = setOf(23 * 60 + 55)))
            assertEquals(
                mapOf(day to setOf(HifzWhen.At(23 * 60 + 55)), day + 1 to setOf(HifzWhen.At(20))),
                HifzReminder.timesComing(context, late, listOf(slip), day, HifzWhen.At(23 * 60 + 55), at(day + 1, 32), at(day + 1, 32)),
            )
        }
    }

    @Test
    fun aReminderDeliveredTwice_ringsOnce() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val prefs = app.getSharedPreferences("quran_hifz", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val clock = java.time.ZonedDateTime.now()
        val day = clock.toLocalDate().toEpochDay()
        val minute = clock.hour * 60 + clock.minute
        HifzSettings.write(app, HifzSettings(times = HifzTimes(minutes = setOf(minute))))
        // Asked at every reminder, its slip two hours ago: due at this minute's reminder.
        val slip = point(HifzKind.Word, verse(2, 3), due = day)
            .copy(id = 424_242L, fresh = 3, createdAt = System.currentTimeMillis() - 2 * 60 * 60_000L, lastSlipAt = System.currentTimeMillis() - 2 * 60 * 60_000L)
        HifzRepository.restore(app, slip)
        val shade = org.robolectric.Shadows.shadowOf(app.getSystemService(android.app.NotificationManager::class.java))
        try {
            HifzReminder.deliver(app, day, HifzWhen.At(minute))
            assertEquals(1, shade.allNotifications.size)
            // Opened, it goes; then the same alarm again, armed by a refresh that ran before it was shown, as the
            // app's start does when that alarm wakes it: it rings no second time.
            HifzReminder.clearReminder(app)
            HifzReminder.deliver(app, day, HifzWhen.At(minute))
            assertEquals(0, shade.allNotifications.size)
        } finally {
            HifzRepository.delete(app, slip.id)
            HifzReminder.clearReminder(app)
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun theAppsStart_whileItsAlarmDelivers_ringsOnce() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val prefs = app.getSharedPreferences("quran_hifz", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        // A reminder two minutes ago, its alarm still to come.
        val clock = java.time.ZonedDateTime.now().minusMinutes(2)
        val day = clock.toLocalDate().toEpochDay()
        val minute = clock.hour * 60 + clock.minute
        val at = HifzReminder.slotAt(app, day, HifzWhen.At(minute))!!
        HifzSettings.write(app, HifzSettings(times = HifzTimes(minutes = setOf(minute))))
        val slip = point(HifzKind.Word, verse(2, 3), due = day)
            .copy(id = 434_343L, fresh = 3, createdAt = at - 2 * 60 * 60_000L, lastSlipAt = at - 2 * 60 * 60_000L)
        HifzRepository.restore(app, slip)
        prefs.edit().putString("armed", "$day|at$minute|$at").putLong("shown_at", at - 60 * 60_000L).commit()
        val shade = org.robolectric.Shadows.shadowOf(app.getSystemService(android.app.NotificationManager::class.java))
        val alarms = org.robolectric.Shadows.shadowOf(app.getSystemService(android.app.AlarmManager::class.java))
        try {
            // The app's start refreshes first: the reminder is owed, armed again for now.
            HifzReminder.refresh(app)
            assertTrue(prefs.getString("armed", null)!!.startsWith("$day|at$minute|"))
            @Suppress("DEPRECATION")
            assertTrue(alarms.peekNextScheduledAlarm()!!.triggerAtTime <= System.currentTimeMillis() + 5_000L)
            // Its first alarm delivers it; the one the refresh armed comes after, and rings nothing.
            HifzReminder.deliver(app, day, HifzWhen.At(minute))
            assertEquals(1, shade.allNotifications.size)
            HifzReminder.clearReminder(app)
            HifzReminder.deliver(app, day, HifzWhen.At(minute))
            assertEquals(0, shade.allNotifications.size)
            assertFalse(prefs.getString("armed", null).orEmpty().startsWith("$day|at$minute|"))
        } finally {
            HifzRepository.delete(app, slip.id)
            HifzReminder.clearReminder(app)
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun notebook_survivesItsFile() {
        val original = listOf(
            point(HifzKind.Similar, verse(4, 47), other = verse(4, 115)).mistake { copy(slipWords = setOf(17), bothWays = true) }.copy(key = "الترتيب الهجائي"),
            point(HifzKind.Word, verse(1, 4)).copy(masteredAt = 9L),
            // A verse that went wrong two ways: its mistakes in the order told, each as it was told.
            point(HifzKind.Word, verse(2, 30)).mistake { copy(slipWords = setOf(0, 2), at = 5L) }
                .told(HifzKind.Skip, verse(2, 33), emptySet(), false, 7L),
        )
        assertEquals(original, decodeHifzPoints(encodeHifzPoints(original)))
        // A notebook from before one point per verse is not read: it is set aside, never half read.
        assertTrue(runCatching { decodeHifzPoints("{\"version\":1,\"points\":[]}") }.isFailure)
    }

    @Test
    fun words_keepMarksWithTheirWordAndCompareWithoutThem() {
        val words = hifzWords("وَمَنْ يُّشْرِكْ بِاللَّهِ فَقَدِ اِ۪فْتَرَىٰ")
        assertEquals(5, words.size)
        assertEquals("فقد", words.keys[3])
        assertEquals(hifzWords("فَقَدْ").keys, hifzWords("فَقَدِ").keys)
    }

    @Test
    fun overlap_findsOpeningClosingAndLongestRun() {
        val a = "a b c d e f".split(' ')
        val b = "a b x c d e y".split(' ')
        val overlap = hifzOverlap(a, b)
        assertEquals(2, overlap.prefix)
        assertEquals(0, overlap.suffix)
        assertEquals(3, overlap.run)
        assertEquals(2, overlap.runA)
        assertEquals(3, overlap.runB)
        val (onlyA, onlyB) = hifzDifferences(a, b)
        assertEquals(setOf(5), onlyA)
        assertEquals(setOf(2, 6), onlyB)
    }

    @Test
    fun labels_agreeWithTheNumber() {
        assertEquals("كلمة واحدة", hifzWordsLabel(1))
        assertEquals("كلمتين", hifzWordsLabel(2))
        assertEquals("7\u00A0كلمات", hifzWordsLabel(7))
        assertEquals("17\u00A0كلمة", hifzWordsLabel(17))
        assertEquals("آيتان", hifzVersesLabel(2))
        assertEquals("68\u00A0آية", hifzVersesLabel(68))
    }

    @Test
    fun lookAlikes_comeFromTheQalounText() {
        val catalog = catalog()
        val lookAlikes = HifzLookAlikes(catalog)
        // An-Nisa 47 and 115 share their first 17 words.
        val similar = lookAlikes.similar(verse(4, 47)).first()
        assertEquals(verse(4, 115), similar.verse)
        assertEquals(17, similar.overlap.prefix)
        // Al-Baqarah 17 and 170 share five words before their last one.
        val ending = lookAlikes.endings(verse(2, 17))
        assertTrue(ending.map { it.verse }.toString(), verse(2, 170) in ending.map { it.verse })
    }

    @Test
    fun openings_areTheFewestWordsNoOtherVerseShares() {
        val catalog = catalog()
        val openings = HifzOpenings(catalog)
        // «أو» alone opens other verses; «أو كصيب» opens only Al-Baqarah 18.
        assertEquals(2, openings.distinct(verse(2, 18)))
        // Exact code points in the mushaf spelling (QuranMushafText): the order of shadda and kasra is kept, and the
        // bundled text's open kasratan U+0656 is Unicode's U+08F2.
        assertEquals("\u0623\u064e\u0648\u0652 \u0643\u064e\u0635\u064e\u064a\u0651\u0650\u0628\u08f2", openings.printed(verse(2, 18), 2))
        // In Qaloun's reading «يكاد» also opens Maryam 91 and Ash-Shura 3, where Hafs reads «تكاد».
        assertEquals(2, openings.distinct(verse(2, 19)))
        assertEquals(1, openings.distinct(verse(2, 16)))
        // An-Nisa 47 and 115 share their first 17 words.
        assertEquals(18, openings.distinct(verse(4, 47)))
        // A verse said word for word elsewhere has no opening of its own: all of it.
        val refrain = (1..77).map { verse(55, it) }.first { v -> (1..77).count { openings.words(verse(55, it)) == openings.words(v) } > 1 }
        assertEquals(openings.words(refrain).size, openings.distinct(refrain))
        assertEquals(0, openings.distinct(verse(2, 999)))
        // The same words as the look-alike index compares.
        val lookAlikes = HifzLookAlikes(catalog)
        assertEquals(lookAlikes.words(verse(2, 20)), openings.words(verse(2, 20)))
    }

    @Test
    fun wordingWords_areTheWordsTheWordingShows() {
        val catalog = catalog()
        listOf(
            point(HifzKind.Ending, verse(2, 17), other = verse(2, 170)),
            point(HifzKind.Similar, verse(4, 47), other = verse(4, 115)),
            point(HifzKind.Skip, verse(2, 68)),
            point(HifzKind.Start, verse(2, 19)),
            point(HifzKind.Word, verse(2, 18)).mistake { copy(slipWords = setOf(3)) },
        ).forEach { p ->
            val fix = hifzFix(catalog, p)!!
            assertEquals(p.latest.kind.key, fix.wording.removeSuffix(" …"), fix.wordingWords.joinToString(" ") { fix.words.printed[it] })
        }
        // A word slip stresses the right word only.
        assertEquals(setOf(3), hifzFix(catalog, point(HifzKind.Word, verse(2, 18)).mistake { copy(slipWords = setOf(3)) })!!.stressed)
    }

    @Test
    fun wordSlip_mayMissSeveralWords() {
        val catalog = catalog()
        // 4:47 has more than 17 words: 1 and 2 touch, 15 stands apart.
        val fix = hifzFix(catalog, point(HifzKind.Word, verse(4, 47)).mistake { copy(slipWords = setOf(15, 1, 2)) })!!
        assertEquals(setOf(1, 2, 15), fix.stressed)
        assertEquals(listOf(0, 1, 2, 3, 14, 15, 16), fix.wordingWords)
        val w = fix.words.printed
        assertEquals("${w[0]} ${w[1]} ${w[2]} ${w[3]} … ${w[14]} ${w[15]} ${w[16]}", fix.wording)
        // Saved and read back.
        assertEquals(listOf(fix.point), decodeHifzPoints(encodeHifzPoints(listOf(fix.point))))
    }

    @Test
    fun fix_saysWhyTheVersesAreConfused() {
        val catalog = catalog()
        val similar = hifzFix(catalog, point(HifzKind.Similar, verse(4, 47), other = verse(4, 115)))!!
        assertEquals(17, similar.partAt)
        // 67 verses lie between 47 and 115.
        assertTrue(similar.reasons.toString(), similar.reasons.contains("في السورة نفسها، بينهما 67\u00A0آية"))

        val ending = hifzFix(catalog, point(HifzKind.Ending, verse(2, 17), other = verse(2, 170)))!!
        assertEquals(5, ending.partAt)
        assertEquals(catalog.verse(2, 17)!!.text.split(' ').takeLast(3).joinToString(" ") { QuranMushafText.word(it) }, ending.wording)

        // A skip's reasons are facts of the verse and of its link with the one before it, never of the verse after it.
        val skip = hifzFix(catalog, point(HifzKind.Skip, verse(2, 68)))!!
        assertTrue(skip.reasons.toString(), skip.reasons.none { "69" in it })
        val link = hifzFix(catalog, point(HifzKind.Skip, verse(2, 69)))!!
        assertTrue(link.reasons.toString(), link.reasons.first().startsWith("تشترك الآيتان 68 و69 في أول 7\u00A0كلمات"))
        assertNotNull(skip.before)
        assertNotNull(skip.after)
        // Where it went, told before 22 October, is not read: the same facts of the text around it.
        val far = hifzFix(catalog, point(HifzKind.Skip, verse(2, 68), other = verse(2, 70)))!!
        assertEquals(skip.reasons, far.reasons)
    }

    @Test
    fun endingsThatShareTheirLastWord_partOnTheWordThatDiffers() {
        val catalog = catalog()
        // 2:31 ends «...العليم الحكيم» and 2:128 «...العزيز الحكيم»: the slip is on «العليم».
        val fix = hifzFix(catalog, point(HifzKind.Ending, verse(2, 31), other = verse(2, 128)))!!
        assertEquals(fix.words.size - 2, fix.partAt)
    }

    @Test
    fun factsMatchTheText() {
        val catalog = catalog()
        val next = hifzFix(catalog, point(HifzKind.Similar, verse(2, 67), other = verse(2, 68)))!!
        assertTrue(next.reasons.toString(), "متتاليتان في السورة نفسها" in next.reasons)
        val first = hifzFix(catalog, point(HifzKind.Skip, verse(2, 1)))!!
        assertEquals("أول السورة", first.wordingLabel)
        assertEquals("105\u00A0آيات", hifzVersesLabel(105))
        assertEquals("آيتين", hifzVersesAfterLabel(2))
    }

    @Test
    fun drill_alternatesTheRightVerseAndTheLookAlike_neverTheSameTwiceInARow() {
        val catalog = catalog()
        val drill = hifzDrill(catalog, point(HifzKind.Similar, verse(4, 47), other = verse(4, 115)))
        assertEquals(listOf(verse(4, 47), verse(4, 115), verse(4, 47), verse(4, 115), verse(4, 47)), drill.items.map { it.from })
        assertEquals(1, drill.rounds)
        assertEquals(HifzDrillTitle, drill.title)
        val skip = hifzDrill(catalog, point(HifzKind.Skip, verse(2, 68)))
        // A skip is drilled with the verse before it, and no further: the join it went wrong at.
        assertEquals(List(3) { QuranRepeatRange(verse(2, 67), verse(2, 68), 1) }, skip.items)
        assertTrue(drill.isValid)
        // After the look-alike, the right verse is said from memory: the silence is measured on it.
        assertEquals(QuranRepeatRange(verse(4, 47), verse(4, 47), 1), drill.answer)
        assertNull(skip.answer)
        assertEquals(drill, QuranPlaylist.decode(drill.encode()))
        assertEquals(listOf(67, 68), com.tunisianprayertimes.ui.hifzDrillVerses(point(HifzKind.Skip, verse(2, 68))))
        // Where it went, told before 22 October, is not read: still the verse before and the verse.
        assertEquals(listOf(4, 5), com.tunisianprayertimes.ui.hifzDrillVerses(point(HifzKind.Skip, verse(2, 5), other = verse(2, 10))))
    }

    @Test
    fun cue_isTheVerseBeforeOnceWithoutSilence_andOnePointsListenSaysSo() {
        val catalog = catalog()
        val cue = hifzCue(catalog, point(HifzKind.Start, verse(2, 30)))!!
        assertEquals(listOf(QuranRepeatRange(verse(2, 29), verse(2, 29), 1)), cue.items)
        assertEquals(1, cue.rounds)
        assertEquals(0f, cue.gapFactor)
        assertTrue(cue.isValid)
        assertNull(hifzCue(catalog, point(HifzKind.Skip, verse(2, 1))))
        assertEquals(HifzListenOneTitle, hifzListen(catalog, listOf(point(HifzKind.Word, verse(2, 18))))!!.title)
        assertEquals(HifzListenTitle, hifzListen(catalog, listOf(point(HifzKind.Word, verse(2, 18)), point(HifzKind.Word, verse(2, 19))))!!.title)
    }

    @Test
    fun theQuestion_isTheWholeVerse_andSaysNothingOfWhatWentWrong() {
        val catalog = catalog()
        // Whatever went wrong, the verse is asked the same way, recited on from the one before, as in prayer.
        val asked = listOf(
            point(HifzKind.Ending, verse(2, 17), other = verse(2, 170)),
            point(HifzKind.Word, verse(2, 17)).mistake { copy(slipWords = setOf(3)) },
            point(HifzKind.Skip, verse(2, 17)).told(HifzKind.Similar, verse(2, 20), emptySet(), false, 1L),
        ).map { hifzQuestion(catalog, it) }.distinct()
        assertEquals(1, asked.size)
        // No word of the verse is in it.
        assertTrue(hifzWords(catalog.verse(2, 17)!!.text).printed.none { it in asked.single() })
    }

    @Test
    fun aVerseIsOnePoint_whateverWentWrongInIt() {
        val catalog = catalog()
        val first = point(HifzKind.Word, verse(2, 30)).mistake { copy(slipWords = setOf(0), at = 1L) }
        // The same mistake told again: still one, as told this time.
        val again = first.told(HifzKind.Word, null, setOf(0, 1), false, 2L)
        assertEquals(listOf(setOf(0, 1)), again.mistakes.map { it.slipWords })
        // Another way it went wrong: beside the first, and the one offered again next time.
        val skipped = again.told(HifzKind.Skip, null, emptySet(), false, 3L)
        // A skip names no other verse, whatever is passed.
        assertNull(again.told(HifzKind.Skip, verse(2, 33), emptySet(), false, 3L).mistakes.last().other)
        assertEquals(listOf(HifzKind.Word, HifzKind.Skip), skipped.mistakes.map { it.kind })
        assertEquals(HifzKind.Skip, skipped.latest.kind)
        // Practised and corrected as one, with the verse before.
        assertEquals(QuranRepeatRange(verse(2, 29), verse(2, 30), 1), hifzPassage(skipped))
        assertEquals(setOf(verse(2, 29), verse(2, 30)), skipped.answerVerses())
        assertEquals(listOf(29, 30), com.tunisianprayertimes.ui.hifzDrillVerses(skipped))
        assertEquals(2, hifzFixes(catalog, skipped).size)
        // A mistake corrected into one the verse has: the two are one.
        val merged = skipped.corrected(0, skipped.mistakes[0].copy(kind = HifzKind.Skip, slipWords = emptySet()))
        assertEquals(listOf(HifzKind.Skip), merged.mistakes.map { it.kind })
        // Two look-alikes: the drill alternates the verse with each, the verse first and last.
        val twice = point(HifzKind.Similar, verse(4, 47), other = verse(4, 115)).told(HifzKind.Ending, verse(4, 56), emptySet(), false, 5L)
        assertEquals(listOf(verse(4, 115), verse(4, 56)), twice.lookAlikes)
        assertEquals(
            listOf(verse(4, 47), verse(4, 115), verse(4, 47), verse(4, 56), verse(4, 47)),
            hifzDrill(catalog, twice).items.map { it.from },
        )
    }

    @Test
    fun recordingAVerseAgain_countsItsOnePoint() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val verse = verse(2, 255)
        try {
            val first = HifzRepository.record(app, verse, HifzKind.Word, slipWords = setOf(3), now = 1_000L)
            val second = HifzRepository.record(app, verse, HifzKind.Skip, now = 2_000L)
            assertEquals(first.id, second.id)
            assertEquals(listOf(second), HifzRepository.points.value.filter { it.verse == verse })
            // Another way it went wrong is the verse going wrong again: more right answers before its checks space out.
            assertEquals(2, second.slips)
            assertEquals(hifzAutoRecalls(2, heldBefore = false), second.recalls)
            assertEquals(listOf(HifzKind.Word, HifzKind.Skip), second.mistakes.map { it.kind })
        } finally {
            HifzRepository.points.value.filter { it.verse == verse }.forEach { HifzRepository.delete(app, it.id) }
        }
    }

    @Test
    fun playlist_walksItsItemsRoundAfterRound() {
        val one = QuranRepeatRange(verse(1, 1), verse(1, 1), 1)
        val list = QuranPlaylist("تدريب", listOf(one, one, one), listOf("a", "b", "c"), rounds = 2)
        assertEquals(QuranPlaylistStep.Item(1, 1), list.after(0, 1))
        assertEquals(QuranPlaylistStep.Item(0, 2), list.after(2, 1))
        assertEquals(QuranPlaylistStep.Finished, list.after(2, 2))
        assertEquals(QuranPlaylistStep.Item(2, 1), list.before(0, 2))
        assertEquals(QuranPlaylistStep.Item(0, 1), list.before(0, 1))
        assertFalse(list.copy(labels = listOf("a")).isValid)
    }

    private fun catalog(): QuranCatalog {
        val source = JSONObject(File("src/main/assets/quran/index.json").readText())
        val chapters = source.getJSONArray("surahs")
        val surahs = List(chapters.length()) { index ->
            val chapter = chapters.getJSONObject(index)
            QuranSurah(chapter.getInt("number"), chapter.getString("name"), chapter.getInt("page"), chapter.optInt("verseCount"))
        }
        val rows = source.getJSONArray("entries")
        val entries = List(rows.length()) { index ->
            val entry = rows.getJSONObject(index)
            QuranSearchEntry(
                surah = entry.getInt("surah"), ayah = entry.getInt("ayah"), text = entry.getString("text"), page = entry.getInt("page"),
                searchText = entry.optString("searchText", entry.getString("text")),
                isFirstFragment = entry.getBoolean("isFirstFragment"), isLastFragment = entry.getBoolean("isLastFragment"),
            )
        }
        return QuranCatalog((1..603).map { QuranPage(it, "", emptyList()) }, surahs, entries)
    }
}
