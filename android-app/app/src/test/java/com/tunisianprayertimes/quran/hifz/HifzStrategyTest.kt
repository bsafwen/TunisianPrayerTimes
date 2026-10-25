package com.tunisianprayertimes.quran.hifz

import android.app.Application
import android.content.Context
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.quran.QuranVerseReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HifzStrategyTest {
    private val dayMs = 86_400_000L
    private val today = 120L
    private val now = today * dayMs

    private fun point(ayah: Int = 18, fresh: Int = 3): HifzPoint = HifzPoint(
        id = 2_000L + ayah,
        verse = QuranVerseReference(2, ayah),
        mistakes = listOf(HifzMistake(HifzKind.Word)),
        dueDay = today,
        createdAt = now - 60 * dayMs,
        lastSlipAt = now - 60 * dayMs,
        fresh = fresh,
    )

    @Test
    fun automaticRecallTarget_letsOldFailuresExpireWithoutErasingTheirLifetimeCount() {
        val longAgo = point().copy(
            slips = 20,
            recentSlipTimes = listOf(now - 40 * dayMs, now - 31 * dayMs),
            lastSlipAt = now - 31 * dayMs,
        )

        assertEquals(3, longAgo.autoRecallsAfterSlip(now))
        val relapsed = longAgo.relapsed(today, now, askedToday = false)
        assertEquals(21, relapsed.slips)
        assertEquals(3, relapsed.autoRecalls)
        assertEquals(3, relapsed.fresh)
        assertEquals(now, relapsed.lastSlipAt)
        assertTrue(now in relapsed.recentSlipTimes)
    }

    @Test
    fun automaticRecallTarget_countsRecentFailuresAndTheNewFailure() {
        val firstRecent = point().copy(recentSlipTimes = listOf(now - 2 * dayMs))
        val repeatedRecently = firstRecent.copy(recentSlipTimes = listOf(now - 2 * dayMs, now - dayMs))

        assertEquals(4, firstRecent.autoRecallsAfterSlip(now))
        assertEquals(5, repeatedRecently.autoRecallsAfterSlip(now))
        assertEquals(3, repeatedRecently.autoRecallsAfterSlip(now + 31 * dayMs))
    }

    @Test
    fun anActiveFreshTarget_doesNotDecayWhenOldFailuresExpireOrTheSamePlanIsSaved() {
        val active = point(fresh = 4).copy(
            autoRecalls = 5,
            oncePerDay = false,
            lastSlipAt = now - 31 * dayMs,
            recentSlipTimes = listOf(now - 33 * dayMs, now - 32 * dayMs, now - 31 * dayMs),
            checks = listOf(HifzCheck(today - 30, HifzGrade.Held, now - 30 * dayMs)),
        )

        assertEquals(3, active.autoRecallsAfterSlip(now))
        assertEquals(5, active.autoRecalls)
        assertEquals(4, active.fresh)
        assertEquals(active, active.withPlan(HifzPlan(HifzPace.Auto, null, oncePerDay = false), today))
    }

    @Test
    fun masteredVerse_restartsWithTwoAutomaticRecallsEvenAfterManyEarlierFailures() {
        val recovered = point(fresh = 0).copy(
            slips = 20,
            step = HifzMasteredStep,
            masteredAt = now - dayMs,
            recentSlipTimes = listOf(now - 4 * dayMs, now - 3 * dayMs, now - 2 * dayMs),
            lastSlipAt = now - 2 * dayMs,
        )

        assertEquals(2, recovered.autoRecallsAfterSlip(now))
        val relapsed = recovered.relapsed(today, now, askedToday = false)
        assertEquals(2, relapsed.fresh)
        assertEquals(2, relapsed.autoRecalls)
        assertFalse(relapsed.mastered)
        assertNull(relapsed.masteredAt)
    }

    @Test
    fun explicitRecallPace_survivesARecordedSlipAndAFailedReview() {
        listOf(HifzPace.Light to 1, HifzPace.Intense to 5).forEach { (pace, expected) ->
            val original = point().copy(pace = pace, recentSlipTimes = listOf(now - dayMs))
            val recorded = original.relapsed(today, now, askedToday = false)
            val failedReview = original.graded(HifzGrade.Again, today, now)

            listOf(recorded, failedReview).forEach { changed ->
                assertEquals(pace, changed.pace)
                assertEquals(expected, changed.recalls)
                assertEquals(expected, changed.fresh)
            }
        }
    }

    @Test
    fun dailyLimit_isIndependentOfWhetherTheVerseOwnsItsReminderTimes() {
        val answered = point().copy(checks = listOf(HifzCheck(today, HifzGrade.Held, now - 60_000L)))
        val sharedDaily = answered.withPlan(HifzPlan(HifzPace.Auto, null, oncePerDay = true), today)
        val privateRepeatable = answered.copy(id = 2_028L, verse = QuranVerseReference(2, 28)).withPlan(
            HifzPlan(HifzPace.Auto, HifzTimes(minutes = setOf(600)), oncePerDay = false), today,
        )

        assertNull(sharedDaily.times)
        assertTrue(sharedDaily.daily)
        assertEquals(today + 1, sharedDaily.dueDay)
        assertFalse(privateRepeatable.daily)
        assertEquals(today, privateRepeatable.dueDay)
        assertEquals(HifzTimes(minutes = setOf(600)), privateRepeatable.times)
        assertEquals(listOf(sharedDaily, privateRepeatable), decodeHifzPoints(encodeHifzPoints(listOf(sharedDaily, privateRepeatable))))
    }

    @Test
    fun aNewSlip_keepsTheExplicitDailyLimitAndSharedSchedule() {
        val sharedDaily = point().copy(times = null, oncePerDay = true, pace = HifzPace.Light)
        val changed = sharedDaily.relapsed(today, now, askedToday = true)

        assertTrue(changed.daily)
        assertNull(changed.times)
        assertEquals(today + 1, changed.dueDay)
        assertEquals(HifzPace.Light, changed.pace)
    }

    @Test
    fun notebookRoundTrip_preservesRecentFailuresAndAnExplicitFalseDailyLimit() {
        val original = point().copy(
            times = HifzTimes(setOf(Prayer.FAJR)),
            oncePerDay = false,
            recentSlipTimes = listOf(now - 2 * dayMs, now - dayMs),
        )
        val restored = decodeHifzPoints(encodeHifzPoints(listOf(original))).single()

        assertEquals(original, restored)
        assertFalse(restored.daily)
    }

    @Test
    fun legacyNotebook_derivesDailyLimitFromItsPrivateTimesWithoutInventingHistory() {
        val oldPoints = listOf(
            point(18).copy(times = HifzTimes(setOf(Prayer.FAJR))),
            point(28).copy(times = HifzTimes(setOf(Prayer.FAJR, Prayer.ISHA))),
            point(38),
        )
        val old = JSONObject(encodeHifzPoints(oldPoints))
        val rows = old.getJSONArray("points")
        repeat(rows.length()) { index ->
            rows.getJSONObject(index).remove("oncePerDay")
            rows.getJSONObject(index).remove("recentSlipTimes")
        }
        val restored = decodeHifzPoints(old.toString())

        assertTrue(restored[0].daily)
        assertFalse(restored[1].daily)
        assertFalse(restored[2].daily)
        restored.forEach {
            assertNull(it.oncePerDay)
            assertTrue(it.recentSlipTimes.isEmpty())
        }
    }

    @Test
    fun aPrayerEndingSoon_doesNotShortenTheRecallOrExposureGap() {
        val previous = HifzPrayerSpans.around
        HifzPrayerSpans.around = { listOf(HifzPrayerSpan(Prayer.DHUHR, now + 5 * 60_000L, now + 20 * 60_000L)) }
        try {
            assertEquals(now + HifzFreshGapMs, hifzWaitEnd(now))
            val fresh = point().copy(lastSlipAt = now)
            assertFalse(fresh.isDue(today, now + HifzFreshGapMs - 1))
            assertTrue(fresh.isDue(today, now + HifzFreshGapMs))
            assertEquals(now + 10 * 60_000L + HifzFreshGapMs, fresh.freshDueAt(now + 10 * 60_000L))
        } finally {
            HifzPrayerSpans.around = previous
        }
    }

    @Test
    fun newSettings_startWithFajrOnlyAndManageableWorkloadDefaults() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("quran_hifz", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            val settings = HifzSettings.read(context)
            assertEquals(HifzTimesPreset.Once.times, settings.times)
            assertEquals(3, settings.dailyAlertLimit)
            assertFalse(settings.quietHours)
            assertEquals(1320, settings.quietStartMinute)
            assertEquals(360, settings.quietEndMinute)
            assertEquals(10, settings.dailyGoal)
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun legacySettings_withoutStoredTimesKeepTheExistingAllPrayerSchedule() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("quran_hifz", Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("reminder", false).putBoolean("markers", false).commit()
        try {
            val settings = HifzSettings.read(context)
            assertEquals(HifzTimesPreset.Prayers.times, settings.times)
            assertFalse(settings.reminder)
            assertFalse(settings.markers)
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun anExistingNotebookWithoutPreferences_keepsAndPersistsTheLegacySchedule() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("quran_hifz", Context.MODE_PRIVATE)
        val notebook = File(context.filesDir, "hifz/hifz.json")
        val original = notebook.takeIf { it.exists() }?.readBytes()
        prefs.edit().clear().commit()
        try {
            notebook.parentFile!!.mkdirs()
            notebook.writeText(encodeHifzPoints(listOf(point())))

            val settings = HifzSettings.read(context)
            assertEquals(HifzTimesPreset.Prayers.times, settings.times)
            assertEquals(1, prefs.getInt("strategy_version", 0))
            assertEquals(HifzTimesPreset.Prayers.times.encode(), prefs.getString("times", null))
            assertEquals(settings, HifzSettings.read(context))
        } finally {
            if (original != null) notebook.writeBytes(original)
            else if (notebook.exists()) assertTrue("Remove only this test's temporary notebook", notebook.delete())
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun workloadSettings_roundTripWithoutReplacingTheChosenSchedule() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("quran_hifz", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            val settings = HifzSettings(
                times = HifzTimes(setOf(Prayer.DHUHR), setOf(1080)),
                dailyAlertLimit = 2,
                quietHours = true,
                quietStartMinute = 1260,
                quietEndMinute = 420,
                dailyGoal = 15,
            )
            HifzSettings.write(context, settings)
            assertEquals(settings, HifzSettings.read(context))
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun aFullMixedRound_keepsSpaceForBothFreshAndSpacedQuestions() {
        val fresh = (1..6).map { point(it * 10) }
        val spaced = (7..10).map { point(it * 10, fresh = 0).copy(dueDay = today - 5) }
        val input = fresh + spaced

        listOf(input.dueToday(today, now, limit = 5), input.reviewBatch(today, now, limit = 5)).forEach { batch ->
            assertEquals(5, batch.size)
            assertEquals(3, batch.count { it.fresh > 0 })
            assertEquals(2, batch.count { it.fresh == 0 })
        }
    }

    @Test
    fun aChosenFreshPointOutsideTheFirstThree_usesAFreshPlaceInTheMixedRound() {
        val fresh = (1..6).map { point(it * 10) }
        val spaced = (7..10).map { point(it * 10, fresh = 0) }
        val selected = fresh.last()
        val batch = (fresh + spaced).reviewBatch(today, now, limit = 5, first = selected)

        assertEquals(selected, batch.first())
        assertEquals(5, batch.size)
        assertEquals(3, batch.count { it.fresh > 0 })
        assertEquals(2, batch.count { it.fresh == 0 })
    }

    @Test
    fun aChosenSpacedPointOutsideTheFirstTwo_usesASpacedPlaceInTheMixedRound() {
        val fresh = (1..6).map { point(it * 10) }
        val spaced = (7..10).map { point(it * 10, fresh = 0) }
        val selected = spaced.last()
        val batch = (fresh + spaced).reviewBatch(today, now, limit = 5, first = selected)

        assertEquals(selected, batch.first())
        assertEquals(5, batch.size)
        assertEquals(3, batch.count { it.fresh > 0 })
        assertEquals(2, batch.count { it.fresh == 0 })
    }

    @Test
    fun aSpacedAnswerCycle_usesTheNextCompatibleCheckAndStillRotatesItsMembers() {
        val fresh = (1..6).map { point(it * 10) }
        val first = point(70, fresh = 0).copy(mistakes = listOf(HifzMistake(HifzKind.Similar, QuranVerseReference(2, 80))))
        val second = point(80, fresh = 0).copy(mistakes = listOf(HifzMistake(HifzKind.Similar, first.verse)))
        val independent = point(90, fresh = 0)
        val cycleIds = setOf(first.id, second.id)
        val batch = (fresh + listOf(first, second, independent)).reviewBatch(today, now, limit = 5)

        assertEquals(5, batch.size)
        assertEquals(3, batch.count { it.fresh > 0 })
        assertEquals(2, batch.count { it.fresh == 0 })
        assertTrue(independent in batch)
        val selected = batch.single { it.id in cycleIds }

        val answered = selected.copy(checks = listOf(HifzCheck(today - 1, HifzGrade.Held, now - dayMs)))
        val rotated = (fresh + listOf(first, second, independent).map { if (it.id == selected.id) answered else it })
            .reviewBatch(today, now, limit = 5)
        assertEquals(5, rotated.size)
        assertEquals(3, rotated.count { it.fresh > 0 })
        assertTrue(independent in rotated)
        assertEquals(cycleIds - selected.id, rotated.filter { it.id in cycleIds }.map { it.id }.toSet())
    }

    @Test
    fun aSpacedAnswerCycleWithoutAnotherCheck_lendsTheBlockedPlaceToAFreshQuestion() {
        val fresh = (1..6).map { point(it * 10) }
        val first = point(70, fresh = 0).copy(mistakes = listOf(HifzMistake(HifzKind.Similar, QuranVerseReference(2, 80))))
        val second = point(80, fresh = 0).copy(mistakes = listOf(HifzMistake(HifzKind.Similar, first.verse)))
        val batch = (fresh + listOf(first, second)).reviewBatch(today, now, limit = 5)

        assertEquals(5, batch.size)
        assertEquals(4, batch.count { it.fresh > 0 })
        assertEquals(1, batch.count { it.fresh == 0 })
        assertEquals(1, batch.count { it.id == first.id || it.id == second.id })
    }

    @Test
    fun anUnderfilledCategory_lendsItsUnusedPlacesToTheOtherCategory() {
        listOf(2 to 6, 6 to 1, 6 to 0, 0 to 6).forEach { (freshCount, spacedCount) ->
            val fresh = (1..freshCount).map { point(it * 10) }
            val spaced = (1..spacedCount).map { point(100 + it * 10, fresh = 0) }
            val input = fresh + spaced
            val expectedFresh = if (spacedCount < 2) 5 - spacedCount else minOf(3, freshCount)

            listOf(input.dueToday(today, now, limit = 5), input.reviewBatch(today, now, limit = 5)).forEach { batch ->
                assertEquals(5, batch.size)
                assertEquals(expectedFresh, batch.count { it.fresh > 0 })
            }
        }
    }

    @Test
    fun aMixedRound_stillAsksALookAlikeBeforeItsAnswerCanBeShown() {
        val spaced = point(70, fresh = 0)
        val lookAlike = point(18).copy(mistakes = listOf(HifzMistake(HifzKind.Similar, spaced.verse)))
        val input = listOf(point(10), lookAlike, point(30), point(40), spaced, point(90, fresh = 0))
        val batch = input.reviewBatch(today, now, limit = 5)

        assertEquals(5, batch.size)
        assertTrue(spaced in batch)
        assertTrue(lookAlike in batch)
        assertTrue(batch.indexOf(spaced) < batch.indexOf(lookAlike))
    }

    @Test
    fun deliveredSpacedChecks_waitUntilTomorrowWhileAnUnservedSiblingCanUseTheNextSlot() {
        val served = point(18, fresh = 0)
        val unserved = point(28, fresh = 0)
        val times = HifzTimes(minutes = setOf(600, 900))
        val after = now + 700 * 60_000L
        val slotAt = { day: Long, time: HifzWhen -> day * dayMs + (time as HifzWhen.At).minute * 60_000L }
        fun next(points: List<HifzPoint>, asked: Map<Long, Long>) = points.nextReminder(
            times, today, after, remindedDay = today - 1, zone = ZoneOffset.UTC,
            checksAsked = asked, slotAt = slotAt,
        )

        assertEquals(
            HifzSlot(today + 1, HifzWhen.At(600), (today + 1) * dayMs + 600 * 60_000L),
            next(listOf(served), mapOf(served.id to today)),
        )
        assertEquals(
            HifzSlot(today, HifzWhen.At(900), now + 900 * 60_000L),
            next(listOf(served, unserved), mapOf(served.id to today)),
        )
        assertEquals(
            HifzSlot(today + 1, HifzWhen.At(600), (today + 1) * dayMs + 600 * 60_000L),
            next(listOf(served, unserved), mapOf(served.id to today, unserved.id to today)),
        )
    }
}
