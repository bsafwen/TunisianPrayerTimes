package com.tunisianprayertimes.quran.hifz

import android.app.Application
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.quran.QuranCatalog
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
        HifzPoint(id = verse.surah * 1000L + verse.ayah, verse = verse, kind = kind, other = other, slips = slips, step = step, dueDay = due, createdAt = 0L)

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
    fun repeats_comeAfterTheChosenPrayersUntilTheirLastDay() {
        // A day's reminders after prayers, in hours: Fajr 5, Dhuhr 13, Asr 16, Maghrib 19, Isha 21.
        val hours = mapOf(Prayer.FAJR to 5L, Prayer.DHUHR to 13L, Prayer.ASR to 16L, Prayer.MAGHRIB to 19L, Prayer.ISHA to 21L)
        val slotAt = { day: Long, time: HifzWhen -> day * 24 + hours.getValue((time as HifzWhen.After).prayer) }
        val prayers = point(HifzKind.Word, verse(2, 18)).copy(repeat = HifzRepeatPreset.Prayers.repeat, repeatUntil = 102L)
        val thrice = point(HifzKind.Ending, verse(2, 17)).copy(repeat = HifzRepeatPreset.Thrice.repeat, repeatUntil = 101L)
        val daily = point(HifzKind.Skip, verse(2, 68))
        val points = listOf(prayers, thrice, daily)
        // At 14 on day 100 the next reminder is after Asr, for the point brought back after every prayer only.
        assertEquals(HifzSlot(100L, HifzWhen.After(Prayer.ASR), 100L * 24 + 16), points.nextRepeat(100L, 100L * 24 + 14, slotAt))
        assertEquals(listOf(prayers), points.repeatingAt(100L, HifzWhen.After(Prayer.ASR)))
        // After Isha both come back; the daily one never does.
        assertEquals(setOf(prayers, thrice), points.repeatingAt(100L, HifzWhen.After(Prayer.ISHA)).toSet())
        assertFalse(daily.repeatsOn(100L))
        // On the last day of the longer plan only its point; after it, nothing.
        assertEquals(listOf(prayers), points.repeatingAt(102L, HifzWhen.After(Prayer.FAJR)))
        assertNull(points.nextRepeat(102L, 102L * 24 + 22, slotAt))
        // A prayer whose time is not known is skipped for the next one.
        assertEquals(
            HifzWhen.After(Prayer.MAGHRIB),
            points.nextRepeat(100L, 100L * 24 + 14) { day, time -> if (time == HifzWhen.After(Prayer.ASR)) null else slotAt(day, time) }?.time,
        )
        // A plan starts at its first reminder: after Isha when chosen at 20, after Fajr the next day when chosen at 22.
        assertEquals(HifzSlot(100L, HifzWhen.After(Prayer.ISHA), 100L * 24 + 21), firstRepeat(HifzRepeatPreset.Thrice.repeat, 100L, 100L * 24 + 20, slotAt))
        assertEquals(HifzSlot(101L, HifzWhen.After(Prayer.FAJR), 101L * 24 + 5), firstRepeat(HifzRepeatPreset.Prayers.repeat, 100L, 100L * 24 + 22, slotAt))
        assertNull(firstRepeat(HifzRepeat.None, 100L, 0L, slotAt))
        // «مرة في اليوم» is after Fajr only: chosen at 6, it starts the next morning.
        assertEquals(HifzSlot(101L, HifzWhen.After(Prayer.FAJR), 101L * 24 + 5), firstRepeat(HifzRepeatPreset.Once.repeat, 100L, 100L * 24 + 6, slotAt))
    }

    @Test
    fun repeats_findAnIshaThatEndsAfterMidnight_andNameAWeekAhead() {
        // In minutes: Isha's reminder at 00:30, after its silence that ends past midnight.
        val minutes = mapOf(Prayer.FAJR to 300L, Prayer.DHUHR to 780L, Prayer.ASR to 960L, Prayer.MAGHRIB to 1140L, Prayer.ISHA to 1470L)
        val slotAt = { day: Long, time: HifzWhen -> day * 1440 + minutes.getValue((time as HifzWhen.After).prayer) }
        val points = listOf(point(HifzKind.Word, verse(2, 18)).copy(repeat = HifzRepeatPreset.Prayers.repeat, repeatUntil = 102L))
        // Just after midnight on day 101, yesterday's Isha reminder is the next one, before Fajr.
        assertEquals(HifzSlot(100L, HifzWhen.After(Prayer.ISHA), 101L * 1440 + 30), points.nextRepeat(101L, 101L * 1440 + 10, slotAt))
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
    fun customPlans_mixPrayersAndClockTimes() {
        // In minutes: Fajr's reminder at 05:00, Isha's at 21:00; the reader's own times 13:00 and 22:30.
        val prayerAt = mapOf(Prayer.FAJR to 300L, Prayer.ISHA to 1260L)
        val slotAt = { day: Long, time: HifzWhen ->
            day * 1440 + when (time) {
                is HifzWhen.After -> prayerAt.getValue(time.prayer)
                is HifzWhen.At -> time.minute.toLong()
            }
        }
        val custom = HifzRepeat(setOf(Prayer.ISHA, Prayer.FAJR), setOf(1350, 780))
        assertEquals(4, custom.perDay)
        assertNull(HifzRepeatPreset.of(custom))
        assertEquals(listOf(HifzWhen.After(Prayer.FAJR), HifzWhen.After(Prayer.ISHA), HifzWhen.At(780), HifzWhen.At(1350)), custom.times)
        val points = listOf(point(HifzKind.Word, verse(2, 18)).copy(repeat = custom, repeatUntil = 101L))
        // At 10 the next is 13:00 by the clock, then Isha, then 22:30; past the last day, nothing.
        assertEquals(HifzSlot(100L, HifzWhen.At(780), 100L * 1440 + 780), points.nextRepeat(100L, 100L * 1440 + 600, slotAt))
        assertEquals(HifzSlot(100L, HifzWhen.After(Prayer.ISHA), 100L * 1440 + 1260), points.nextRepeat(100L, 100L * 1440 + 800, slotAt))
        assertEquals(HifzSlot(100L, HifzWhen.At(1350), 100L * 1440 + 1350), points.nextRepeat(100L, 100L * 1440 + 1300, slotAt))
        assertNull(points.nextRepeat(101L, 101L * 1440 + 1400, slotAt))
        assertEquals(points, points.repeatingAt(100L, HifzWhen.At(1350)))
        assertTrue(points.repeatingAt(100L, HifzWhen.At(1351)).isEmpty())
        // Chosen at 14, it starts at Isha.
        assertEquals(HifzSlot(100L, HifzWhen.After(Prayer.ISHA), 100L * 1440 + 1260), firstRepeat(custom, 100L, 100L * 1440 + 840, slotAt))
        // Stored as its times, and read back; a plan that is not one reads as none.
        assertEquals("FAJR,ISHA,at780,at1350", custom.encode())
        assertEquals(custom, HifzRepeat.decode(custom.encode()))
        assertEquals(HifzRepeat.None, HifzRepeat.decode(""))
        assertNull(HifzRepeat.decode("FAJR,at9999"))
        assertEquals(HifzRepeatPreset.Once, HifzRepeatPreset.of(HifzRepeat.decode("FAJR")!!))
        // In words, with the clock in western digits.
        assertEquals("بعد الفجر والعشاء وفي الساعة 13:00 و22:30", HifzReminder.repeatPhrase(custom))
        assertEquals("4 مرات في اليوم", HifzReminder.repeatShortPhrase(custom))
        assertEquals("بعد الفجر والظهر والعشاء", HifzReminder.repeatPhrase(HifzRepeatPreset.Thrice.repeat))
        assertEquals("في الساعة 08:05", HifzReminder.repeatPhrase(HifzRepeat(minutes = setOf(485))))
        assertEquals("بعد كل صلاة وفي الساعة 22:30", HifzReminder.repeatPhrase(HifzRepeat(HifzPrayers.toSet(), setOf(1350))))
        assertEquals("اليوم الساعة 13:00", HifzReminder.slotLabel(HifzSlot(100L, HifzWhen.At(780), 0L), 100L))
        assertEquals("مرتين في اليوم", HifzReminder.countPhrase(2))
        assertEquals("11 مرة في اليوم", HifzReminder.countPhrase(11))
        assertEquals("لمدة يومين", HifzReminder.daysPhrase(2))
        assertEquals("لمدة أسبوع", HifzReminder.daysPhrase(7))
        assertEquals("لمدة 10 أيام", HifzReminder.daysPhrase(10))
        assertEquals("أسبوعان", HifzReminder.daysLabel(14))
    }

    @Test
    fun repeatPlan_isSavedAndReadBack() {
        val planned = point(HifzKind.Word, verse(2, 18)).copy(slipWords = setOf(3), repeat = HifzRepeatPreset.Thrice.repeat, repeatUntil = 205L, repeatDays = 7)
        val custom = point(HifzKind.Ending, verse(2, 17)).copy(repeat = HifzRepeat(setOf(Prayer.ASR), setOf(1290)), repeatUntil = 210L, repeatDays = 12)
        val plain = point(HifzKind.Skip, verse(2, 68))
        assertEquals(listOf(planned, custom, plain), decodeHifzPoints(encodeHifzPoints(listOf(planned, custom, plain))))
        // A notebook written before plans existed reads as no plan.
        val old = JSONObject(encodeHifzPoints(listOf(plain)))
        assertFalse(old.getJSONArray("points").getJSONObject(0).has("repeat"))
        assertEquals(HifzRepeat.None, decodeHifzPoints(old.toString()).single().repeat)
        assertNull(decodeHifzPoints(old.toString()).single().repeatUntil)
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
        val again = p.graded(HifzGrade.Again, 100L, 0L)
        assertEquals(0, again.step)
        assertEquals(101L, again.dueDay)
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
    fun reviewUndo_keepsOnlyTheScheduleTheGradeChanged_andSurvivesAsText() {
        val held = point(HifzKind.Ending, verse(2, 17), step = 3).copy(masteredAt = null)
        val given = com.tunisianprayertimes.ui.HifzGiven.of(held, HifzGrade.Held)
        assertTrue(given.heldNow)
        assertEquals(given, com.tunisianprayertimes.ui.HifzGiven.decode(given.encode()))
        val mastered = point(HifzKind.Word, verse(2, 18), step = HifzMasteredStep).copy(masteredAt = 9L)
        val again = com.tunisianprayertimes.ui.HifzGiven.of(mastered, HifzGrade.Again)
        assertEquals(again, com.tunisianprayertimes.ui.HifzGiven.decode(again.encode()))
        assertEquals(9L, again.masteredAt)
        assertNull(com.tunisianprayertimes.ui.HifzGiven.decode("1|Nope|0|0||0"))
    }

    @Test
    fun gradeDetails_sayWhenTheVerseComesBack() {
        val fresh = point(HifzKind.Ending, verse(2, 17))
        assertEquals("دون تردد · تعود بعد 3 أيام", com.tunisianprayertimes.ui.hifzGradeDetail(fresh, HifzGrade.Held))
        assertEquals("صحيحة بعد تردد · تعود بعد يومين", com.tunisianprayertimes.ui.hifzGradeDetail(fresh, HifzGrade.Hesitated))
        assertEquals("أخطأت أو نسيت · تعود غدًا", com.tunisianprayertimes.ui.hifzGradeDetail(fresh, HifzGrade.Again))
        val last = point(HifzKind.Ending, verse(2, 17), step = 3)
        assertEquals("دون تردد · تثبت، وتعود بعد شهر للتأكد", com.tunisianprayertimes.ui.hifzGradeDetail(last, HifzGrade.Held))
        assertEquals("أخطأت أو نسيت · تعود غدًا · وتبدأ مددها من جديد", com.tunisianprayertimes.ui.hifzGradeDetail(last, HifzGrade.Again))
        val held = point(HifzKind.Ending, verse(2, 17), step = HifzMasteredStep)
        assertEquals("دون تردد · تعود بعد شهرين للتأكد", com.tunisianprayertimes.ui.hifzGradeDetail(held, HifzGrade.Held))
        assertEquals("صحيحة بعد تردد · تعود بعد يومين · تخرج من «ثبتت»", com.tunisianprayertimes.ui.hifzGradeDetail(held, HifzGrade.Hesitated))
        assertEquals("أخطأت أو نسيت · تعود غدًا · تخرج من «ثبتت»", com.tunisianprayertimes.ui.hifzGradeDetail(held, HifzGrade.Again))
        assertEquals("بعد أسبوعين", com.tunisianprayertimes.ui.hifzWhen(14L))
        assertEquals("بعد 12 يومًا", com.tunisianprayertimes.ui.hifzWhen(12L))
        assertEquals("المراجعة غدًا", com.tunisianprayertimes.ui.hifzReviewWhen(1L))
    }

    @Test
    fun todaysChecks_putPointsNeedingCareFirstAndKeepTheLimit() {
        val points = listOf(
            point(HifzKind.Skip, verse(2, 68), due = 99L),
            point(HifzKind.Ending, verse(2, 17), slips = 3, due = 100L),
            point(HifzKind.Similar, verse(4, 47), due = 101L),
            point(HifzKind.Word, verse(1, 4), due = 98L),
        )
        assertEquals(listOf(verse(2, 17), verse(1, 4), verse(2, 68)), points.dueToday(100L).map { it.verse })
        assertEquals(2, points.dueToday(100L, limit = 2).size)
        assertTrue(points[1].needsCare)
    }

    @Test
    fun notebook_survivesItsFile() {
        val original = listOf(
            point(HifzKind.Similar, verse(4, 47), other = verse(4, 115)).copy(slipWords = setOf(17), bothWays = true, key = "الترتيب الهجائي"),
            point(HifzKind.Word, verse(1, 4)).copy(hafs = true, masteredAt = 9L, headsUp = false),
        )
        assertEquals(original, decodeHifzPoints(encodeHifzPoints(original)))
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
        assertEquals("7 كلمات", hifzWordsLabel(7))
        assertEquals("17 كلمة", hifzWordsLabel(17))
        assertEquals("آيتان", hifzVersesLabel(2))
        assertEquals("68 آية", hifzVersesLabel(68))
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
        // Exact code points from the app's Qaloun text: the order of shadda and kasra matters.
        assertEquals("\u0623\u064e\u0648\u0652 \u0643\u064e\u0635\u064e\u064a\u0651\u0650\u0628\u0656", openings.printed(verse(2, 18), 2))
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
            point(HifzKind.Word, verse(2, 18)).copy(slipWords = setOf(3)),
        ).forEach { p ->
            val fix = hifzFix(catalog, p)!!
            assertEquals(p.kind.key, fix.wording.removeSuffix(" …"), fix.wordingWords.joinToString(" ") { fix.words.printed[it] })
        }
        // A word slip stresses the right word only.
        assertEquals(setOf(3), hifzFix(catalog, point(HifzKind.Word, verse(2, 18)).copy(slipWords = setOf(3)))!!.stressed)
    }

    @Test
    fun wordSlip_mayMissSeveralWords() {
        val catalog = catalog()
        // 4:47 has more than 17 words: 1 and 2 touch, 15 stands apart.
        val fix = hifzFix(catalog, point(HifzKind.Word, verse(4, 47)).copy(slipWords = setOf(15, 1, 2)))!!
        assertEquals(setOf(1, 2, 15), fix.stressed)
        assertEquals(setOf(1, 2, 15), fix.hidden)
        assertEquals(listOf(0, 1, 2, 3, 14, 15, 16), fix.wordingWords)
        val w = fix.words.printed
        assertEquals("${w[0]} ${w[1]} ${w[2]} ${w[3]} … ${w[14]} ${w[15]} ${w[16]}", fix.wording)
        assertTrue(fix.question, fix.question.startsWith("ما الكلمات المخفية"))
        // Saved and read back.
        assertEquals(listOf(fix.point), decodeHifzPoints(encodeHifzPoints(listOf(fix.point))))
    }

    @Test
    fun fix_saysWhyTheVersesAreConfused() {
        val catalog = catalog()
        val similar = hifzFix(catalog, point(HifzKind.Similar, verse(4, 47), other = verse(4, 115)))!!
        assertEquals(17, similar.partAt)
        assertTrue(similar.reasons.toString(), similar.reasons.first().startsWith("تتفقان في أول 17 كلمة"))
        // 67 verses lie between 47 and 115.
        assertTrue(similar.reasons.toString(), similar.reasons.contains("في السورة نفسها، بينهما 67 آية"))
        assertEquals((17 until similar.words.size).toSet(), similar.hidden)

        val ending = hifzFix(catalog, point(HifzKind.Ending, verse(2, 17), other = verse(2, 170)))!!
        assertEquals("تتفقان في 5 كلمات قبل الخاتمة", ending.reasons.first())
        assertEquals(5, ending.partAt)
        assertEquals(catalog.verse(2, 17)!!.text.split(' ').takeLast(3).joinToString(" "), ending.wording)

        val skip = hifzFix(catalog, point(HifzKind.Skip, verse(2, 68)))!!
        assertTrue(skip.reasons.toString(), skip.reasons.first().startsWith("67 و68 و69 تشترك في أول 7 كلمات"))
        assertNotNull(skip.before)
        assertNotNull(skip.after)
        assertNull(skip.hidden)
    }

    @Test
    fun reviewNeverShowsTheAnswerBeforeItIsAsked() {
        val catalog = catalog()
        // A word slip saved without its word asks for the whole verse instead.
        val word = hifzFix(catalog, point(HifzKind.Word, verse(2, 17)))!!
        assertEquals((0 until word.words.size).toSet(), word.hidden)
        assertTrue(word.question, word.question.startsWith("اقرأ الآية 17"))
        // A look-alike with no shared opening parts after the words they share in the middle.
        val similar = hifzFix(catalog, point(HifzKind.Similar, verse(2, 17), other = verse(2, 170)))!!
        assertTrue(similar.hidden!!.min() >= 1)
        assertFalse(similar.hidden!!.isEmpty())
    }

    @Test
    fun oneWordVerses_hideTheWholeVerse() {
        val catalog = catalog()
        // 89:1 is one word in the Qaloun index.
        assertEquals(1, hifzWords(catalog.verse(89, 1)!!.text).size)
        for (kind in listOf(HifzKind.Similar, HifzKind.Ending, HifzKind.Word)) {
            val fix = hifzFix(catalog, point(kind, verse(89, 1), other = verse(93, 1).takeIf { kind != HifzKind.Word }))!!
            assertEquals(setOf(0), fix.hidden)
        }
    }

    @Test
    fun endingsThatShareTheirLastWord_partOnTheWordThatDiffers() {
        val catalog = catalog()
        // 2:31 ends «...العليم الحكيم» and 2:128 «...العزيز الحكيم»: the slip is on «العليم».
        val fix = hifzFix(catalog, point(HifzKind.Ending, verse(2, 31), other = verse(2, 128)))!!
        assertEquals(fix.words.size - 2, fix.partAt)
        assertEquals((fix.words.size - 2 until fix.words.size).toSet(), fix.hidden)
        assertEquals("تتفقان في كلمتين قبل الخاتمة", fix.reasons.first())
    }

    @Test
    fun factsMatchTheText() {
        val catalog = catalog()
        val next = hifzFix(catalog, point(HifzKind.Similar, verse(2, 67), other = verse(2, 68)))!!
        assertTrue(next.reasons.toString(), "في السورة نفسها، متتاليتان" in next.reasons)
        val first = hifzFix(catalog, point(HifzKind.Skip, verse(2, 1)))!!
        assertEquals("ما أول آية في هذه السورة؟ قلها ثم اضغط «أظهر»", first.question)
        assertEquals("أول السورة", first.wordingLabel)
        assertEquals("105 آيات", hifzVersesLabel(105))
        assertEquals("آيتين", hifzVersesAfterLabel(2))
    }

    @Test
    fun drill_alternatesTheRightVerseAndTheLookAlike_neverTheSameTwiceInARow() {
        val catalog = catalog()
        val drill = hifzDrill(catalog, point(HifzKind.Similar, verse(4, 47), other = verse(4, 115)), HifzSettings())
        assertEquals(listOf(verse(4, 47), verse(4, 115), verse(4, 47), verse(4, 115), verse(4, 47)), drill.items.map { it.from })
        assertEquals(1, drill.rounds)
        assertEquals(HifzDrillTitle, drill.title)
        assertEquals(listOf("الصواب · النساء 47", "الشبيهة · النساء 115", "الصواب · النساء 47", "الشبيهة · النساء 115", "الصواب · النساء 47"), drill.labels)
        val skip = hifzDrill(catalog, point(HifzKind.Skip, verse(2, 68)), HifzSettings())
        assertEquals(List(3) { QuranRepeatRange(verse(2, 67), verse(2, 69), 1) }, skip.items)
        assertEquals(List(3) { "البقرة 67 ← 68 ← 69" }, skip.labels)
        assertTrue(drill.isValid)
        assertEquals(drill, QuranPlaylist.decode(drill.encode()))
        assertEquals("47 و115 بالتناوب · 5 تلاوات · مع مهلة للترديد", com.tunisianprayertimes.ui.hifzDrillLabel(point(HifzKind.Similar, verse(4, 47), other = verse(4, 115))))
        assertEquals("47 والأعراف 115 بالتناوب · 5 تلاوات",
            com.tunisianprayertimes.ui.hifzDrillLabel(point(HifzKind.Similar, verse(4, 47), other = verse(7, 115)), gap = 0f) { catalog.surahs[it - 1].name })
        assertEquals("الربط: 67 ← 68 ← 69 · 3 مرات · مع مهلة للترديد", com.tunisianprayertimes.ui.hifzDrillLabel(point(HifzKind.Skip, verse(2, 68)), 286))
    }

    @Test
    fun cue_isTheVerseBeforeOnceWithoutSilence_andOnePointsListenSaysSo() {
        val catalog = catalog()
        val cue = hifzCue(catalog, point(HifzKind.Start, verse(2, 30)), HifzSettings())!!
        assertEquals(listOf(QuranRepeatRange(verse(2, 29), verse(2, 29), 1)), cue.items)
        assertEquals(1, cue.rounds)
        assertEquals(0f, cue.gapFactor)
        assertTrue(cue.isValid)
        assertNull(hifzCue(catalog, point(HifzKind.Skip, verse(2, 1)), HifzSettings()))
        assertEquals(HifzListenOneTitle, hifzListen(catalog, listOf(point(HifzKind.Word, verse(2, 18))), HifzSettings())!!.title)
        assertEquals(HifzListenTitle, hifzListen(catalog, listOf(point(HifzKind.Word, verse(2, 18)), point(HifzKind.Word, verse(2, 19))), HifzSettings())!!.title)
    }

    @Test
    fun morningReminder_asksTheQuestion_neverShowsTheAnswer() {
        val catalog = catalog()
        val ending = hifzFix(catalog, point(HifzKind.Ending, verse(2, 17), other = verse(2, 170)))!!
        assertEquals("بماذا تختم؟", ending.digestPrompt)
        assertEquals("ما الآية بعد الآية 67؟", hifzFix(catalog, point(HifzKind.Skip, verse(2, 68)))!!.digestPrompt)
        assertEquals("ما أول آية في السورة؟", hifzFix(catalog, point(HifzKind.Skip, verse(2, 1)))!!.digestPrompt)
        assertEquals("كيف تبدأ؟", hifzFix(catalog, point(HifzKind.Start, verse(2, 30)))!!.digestPrompt)
        // No word of the answer is in the line.
        val hiddenWords = ending.hidden!!.map { ending.words.printed[it] }
        assertTrue(hiddenWords.none { it in ending.digestPrompt })
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
