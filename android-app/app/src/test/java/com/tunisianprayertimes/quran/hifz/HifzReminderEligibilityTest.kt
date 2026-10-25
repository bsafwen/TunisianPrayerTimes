package com.tunisianprayertimes.quran.hifz

import android.Manifest
import android.app.Application
import android.app.AlarmManager
import android.content.Intent
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.quran.QuranVerseReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/** Notification contents and persisted eligibility must agree with the reminder's prayer day. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HifzReminderEligibilityTest {
    @Test
    fun alreadyRemindedSpacedCheck_isNotAddedToLaterFreshReminder() = isolated { app ->
        val now = System.currentTimeMillis()
        val today = epochDay(now)
        val time = HifzWhen.At(12 * 60)
        HifzSettings.write(app, HifzSettings(times = HifzTimes(minutes = setOf(time.minute))))
        val spaced = point(10L, 1, today, now)
        val fresh = point(20L, 2, today, now).copy(fresh = 3)
        HifzRepository.restore(app, spaced)
        HifzRepository.restore(app, fresh)
        app.getSharedPreferences("quran_hifz", Context.MODE_PRIVATE).edit()
            .putLong("reminded_day", today).commit()

        invokeRemind(app, mapOf(today to setOf(time)), now)

        assertEquals("A later fresh reminder must not repeat the already-reminded spaced check",
            setOf(fresh.id), HifzReminder.postedIds(app))
        assertEquals(today, checkAskedDay(app, 10L))
    }

    @Test
    fun previousPrayerDaysIsha_doesNotPullInTodaysSpacedCheck() = isolated { app ->
        val today = todayEpochDay()
        val yesterday = today - 1
        val now = LocalDate.ofEpochDay(today).atTime(0, 32).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val time = HifzWhen.After(Prayer.ISHA)
        HifzSettings.write(app, HifzSettings(times = HifzTimes(setOf(Prayer.ISHA))))
        val overdue = point(10L, 1, yesterday, now)
        val justDueToday = point(20L, 2, today, now)
        HifzRepository.restore(app, overdue)
        HifzRepository.restore(app, justDueToday)
        app.getSharedPreferences("quran_hifz", Context.MODE_PRIVATE).edit()
            .putLong("reminded_day", yesterday - 1).commit()

        invokeRemind(app, mapOf(yesterday to setOf(time)), now)

        assertEquals("Yesterday's Isha may ask overdue points, but today's checks wait for today's own slot",
            setOf(overdue.id), HifzReminder.postedIds(app))
        assertEquals(yesterday, checkAskedDay(app, 10L))
    }

    @Test
    fun combinedPrayerDaySlots_includeTodaysChecksAndMarkOnlyTodayReminded() = isolated { app ->
        val today = todayEpochDay()
        val yesterday = today - 1
        val now = LocalDate.ofEpochDay(today).atTime(0, 32).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val isha = HifzWhen.After(Prayer.ISHA)
        val clock = HifzWhen.At(20)
        HifzSettings.write(app, HifzSettings(times = HifzTimes(setOf(Prayer.ISHA), setOf(clock.minute))))
        val overdue = point(10L, 1, yesterday, now)
        val dueToday = point(20L, 2, today, now)
        val dueTomorrow = point(30L, 3, today + 1, now)
        listOf(overdue, dueToday, dueTomorrow).forEach { HifzRepository.restore(app, it) }
        val prefs = app.getSharedPreferences("quran_hifz", Context.MODE_PRIVATE)
        prefs.edit().putLong("reminded_day", yesterday).commit()

        invokeRemind(app, mapOf(yesterday to setOf(isha), today to setOf(clock)), now)

        assertEquals(setOf(overdue.id, dueToday.id), HifzReminder.postedIds(app))
        assertEquals(today, checkAskedDay(app, 10L))
    }

    @Test
    fun previousPrayerDaysIsha_withOnlyTodaysChecks_postsNothing() = isolated { app ->
        val today = todayEpochDay()
        val now = LocalDate.ofEpochDay(today).atTime(0, 32).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val isha = HifzWhen.After(Prayer.ISHA)
        HifzSettings.write(app, HifzSettings(times = HifzTimes(setOf(Prayer.ISHA))))
        HifzRepository.restore(app, point(10L, 1, today, now))

        invokeRemind(app, mapOf(today - 1 to setOf(isha)), now)

        assertTrue(HifzReminder.postedIds(app).isEmpty())
        assertTrue(Shadows.shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
        assertEquals(-1L, checkAskedDay(app, 10L))
    }

    @Test
    fun alreadyRemindedChecks_withoutFreshPoints_postsNothing() = isolated { app ->
        val now = System.currentTimeMillis()
        val today = epochDay(now)
        val time = HifzWhen.At(12 * 60)
        HifzSettings.write(app, HifzSettings(times = HifzTimes(minutes = setOf(time.minute))))
        HifzRepository.restore(app, point(10L, 1, today, now))
        val prefs = app.getSharedPreferences("quran_hifz", Context.MODE_PRIVATE)
        prefs.edit().putLong("reminded_day", today).commit()

        invokeRemind(app, mapOf(today to setOf(time)), now)

        assertTrue(HifzReminder.postedIds(app).isEmpty())
        assertTrue(Shadows.shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
        assertEquals(today, checkAskedDay(app, 10L))
    }

    @Test
    fun freshPointsOwnSlot_doesNotConsumeSpacedChecksForTheDefaultSlot() = isolated { app ->
        val now = System.currentTimeMillis()
        val today = epochDay(now)
        val defaultTime = HifzWhen.At(12 * 60)
        val ownTime = HifzWhen.At(10 * 60)
        HifzSettings.write(app, HifzSettings(times = HifzTimes(minutes = setOf(defaultTime.minute))))
        val spaced = point(10L, 1, today, now)
        val fresh = point(20L, 2, today, now).copy(fresh = 3, times = HifzTimes(minutes = setOf(ownTime.minute)))
        HifzRepository.restore(app, spaced)
        HifzRepository.restore(app, fresh)
        val prefs = app.getSharedPreferences("quran_hifz", Context.MODE_PRIVATE)

        invokeRemind(app, mapOf(today to setOf(ownTime)), now)

        assertEquals(setOf(fresh.id), HifzReminder.postedIds(app))
        assertEquals(-1L, checkAskedDay(app, 10L))

        invokeRemind(app, mapOf(today to setOf(defaultTime)), now)

        assertEquals(setOf(spaced.id), HifzReminder.postedIds(app))
        assertEquals(today, checkAskedDay(app, 10L))
    }

    @Test
    fun blockedNotifications_leaveChecksEligibleForALaterSlot() = isolated { app ->
        val now = System.currentTimeMillis()
        val today = epochDay(now)
        val earlier = HifzWhen.At(10 * 60)
        val later = HifzWhen.At(12 * 60)
        HifzSettings.write(app, HifzSettings(times = HifzTimes(minutes = setOf(earlier.minute, later.minute))))
        val spaced = point(10L, 1, today, now)
        HifzRepository.restore(app, spaced)
        val prefs = app.getSharedPreferences("quran_hifz", Context.MODE_PRIVATE)
        val notifications = Shadows.shadowOf(app.getSystemService(NotificationManager::class.java))
        notifications.setNotificationsEnabled(false)
        try {
            invokeRemind(app, mapOf(today to setOf(earlier)), now)

            assertTrue(HifzReminder.postedIds(app).isEmpty())
            assertEquals(-1L, checkAskedDay(app, 10L))
        } finally {
            notifications.setNotificationsEnabled(true)
        }

        invokeRemind(app, mapOf(today to setOf(later)), now)

        assertEquals(setOf(spaced.id), HifzReminder.postedIds(app))
        assertEquals(today, checkAskedDay(app, 10L))
    }

    @Test
    fun dailyFreshPoint_dueToday_cannotJoinYesterdaysIsha() = isolated { app ->
        val today = todayEpochDay()
        val now = LocalDate.ofEpochDay(today).atTime(0, 32).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val isha = HifzWhen.After(Prayer.ISHA)
        HifzSettings.write(app, HifzSettings(times = HifzTimes(setOf(Prayer.ISHA))))
        val overdue = point(10L, 1, today - 1, now)
        val dailyFresh = point(20L, 2, today, now).copy(fresh = 2, times = HifzTimes(setOf(Prayer.ISHA)), oncePerDay = true)
        listOf(overdue, dailyFresh).forEach { HifzRepository.restore(app, it) }

        invokeRemind(app, mapOf(today - 1 to setOf(isha)), now)

        assertEquals(setOf(overdue.id), HifzReminder.postedIds(app))
        assertFalse(app.getSharedPreferences("quran_hifz", Context.MODE_PRIVATE).getString("daily_asked", "").orEmpty().contains("20:"))
    }

    @Test
    fun checksOutsideTheShownBatch_remainEligibleAtALaterSelectedSlot() = isolated { app ->
        val now = System.currentTimeMillis()
        val today = epochDay(now)
        val first = HifzWhen.At(600)
        val later = HifzWhen.At(720)
        HifzSettings.write(app, HifzSettings(times = HifzTimes(minutes = setOf(first.minute, later.minute))))
        val points = (1..8).map { point(it.toLong(), it, today, now) }
        points.forEach { HifzRepository.restore(app, it) }

        invokeRemind(app, mapOf(today to setOf(first)), now)
        val initial = HifzReminder.postedIds(app)
        assertEquals(HifzRoundSize, initial.size)
        invokeRemind(app, mapOf(today to setOf(later)), now + 2 * 60 * 60_000L)
        val remaining = HifzReminder.postedIds(app)

        assertEquals(3, remaining.size)
        assertTrue(initial.intersect(remaining).isEmpty())
        assertEquals(points.map { it.id }.toSet(), initial + remaining)
    }

    @Test
    fun gapDeferral_preservesOriginalSlot_andCoalescesNearbyTimes() = isolated { app ->
        val day = todayEpochDay()
        fun at(minute: Int) = LocalDate.ofEpochDay(day).atTime(minute / 60, minute % 60).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        HifzSettings.write(app, HifzSettings(times = HifzTimes(minutes = setOf(600, 620, 640, 800))))
        HifzRepository.restore(app, point(10L, 1, day, at(600)).copy(fresh = 3))

        HifzReminder.deliver(app, day, HifzWhen.At(600), at(600))

        val prefs = app.getSharedPreferences("quran_hifz", Context.MODE_PRIVATE)
        assertEquals("$day|at620|${at(620)}", prefs.getString("armed", null))
        val alarms = Shadows.shadowOf(app.getSystemService(AlarmManager::class.java))
        assertEquals(at(660), alarms.scheduledAlarms.single().triggerAtTime)
        HifzReminder.deliver(app, day, HifzWhen.At(620), at(660))
        assertEquals(2, HifzReminder.controlState(app, at(660)).alertsToday)
        assertEquals("$day|at800|${at(800)}", prefs.getString("armed", null))
    }

    @Test
    fun staleSingleDailySlot_waitsForTheNextSelectedSlot() = isolated { app ->
        val day = todayEpochDay()
        fun at(d: Long, minute: Int) = LocalDate.ofEpochDay(d).atTime(minute / 60, minute % 60).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        HifzSettings.write(app, HifzSettings(times = HifzTimes(minutes = setOf(600))))
        HifzRepository.restore(app, point(10L, 1, day, at(day, 600)).copy(fresh = 3, oncePerDay = true))

        HifzReminder.deliver(app, day, HifzWhen.At(600), at(day, 691))

        assertTrue(HifzReminder.postedIds(app).isEmpty())
        assertEquals(0, HifzReminder.controlState(app, at(day, 691)).alertsToday)
        assertEquals("${day + 1}|at600|${at(day + 1, 600)}", app.getSharedPreferences("quran_hifz", Context.MODE_PRIVATE).getString("armed", null))
    }

    @Test
    fun later_rechecksExposure_andDoesNotReintroduceAnsweredWork() = isolated { app ->
        val now = System.currentTimeMillis()
        val day = epochDay(now)
        val time = HifzWhen.At(600)
        HifzSettings.write(app, HifzSettings(times = HifzTimes(minutes = setOf(time.minute)), dailyAlertLimit = 3))
        val first = point(10L, 1, day, now)
        val second = point(20L, 2, day, now)
        listOf(first, second).forEach { HifzRepository.restore(app, it) }
        invokeRemind(app, mapOf(day to setOf(time)), now)
        assertTrue(HifzReminder.later(app))
        HifzLearning.recordExposure(app, first.verse, day)
        val snooze = HifzReminder.controlState(app).snoozedUntil!!

        HifzReminder.deliverSnoozed(app, snooze)

        assertEquals(setOf(second.id), HifzReminder.postedIds(app))
        assertEquals(2, HifzReminder.controlState(app, snooze).alertsToday)
        assertNull(HifzReminder.controlState(app, snooze).snoozedUntil)
    }

    @Test
    fun exhaustedBudget_hidesLater_andPauseDoesNotAlterPoints() = isolated { app ->
        val now = System.currentTimeMillis()
        val day = epochDay(now)
        val time = HifzWhen.At(600)
        HifzSettings.write(app, HifzSettings(times = HifzTimes(minutes = setOf(time.minute)), dailyAlertLimit = 1))
        val point = point(10L, 1, day, now)
        HifzRepository.restore(app, point)
        invokeRemind(app, mapOf(day to setOf(time)), now)
        val notification = Shadows.shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.single()
        assertFalse(notification.actions.any { it.title.toString().contains("30") })
        assertFalse(HifzReminder.later(app))

        HifzReminder.pauseToday(app)

        assertTrue(HifzReminder.controlState(app).pausedToday)
        assertEquals(listOf(point), HifzRepository.points.value)
        HifzReminder.resume(app)
        assertFalse(HifzReminder.controlState(app).pausedToday)
        assertEquals(1, HifzReminder.controlState(app).alertsToday)
    }

    @Test
    fun oldNotificationActions_cannotSnoozeOrPauseANewerNotification() = isolated { app ->
        val now = System.currentTimeMillis()
        val day = epochDay(now)
        val time = HifzWhen.At(600)
        HifzSettings.write(app, HifzSettings(times = HifzTimes(minutes = setOf(time.minute)), dailyAlertLimit = 5))
        HifzRepository.restore(app, point(10L, 1, day, now).copy(fresh = 3))
        val notifications = Shadows.shadowOf(app.getSystemService(NotificationManager::class.java))
        invokeRemind(app, mapOf(day to setOf(time)), now)
        val oldActions = notifications.allNotifications.single().actions.drop(1).map { Intent(Shadows.shadowOf(it.actionIntent).savedIntent) }
        invokeRemind(app, mapOf(day to setOf(time)), now + 60 * 60_000L)
        oldActions.forEach { HifzReminder.receive(app, it) }

        assertNull(HifzReminder.controlState(app).snoozedUntil)
        assertFalse(HifzReminder.controlState(app).pausedToday)
        assertEquals(setOf(10L), HifzReminder.postedIds(app))
        val latestPause = Intent(Shadows.shadowOf(notifications.allNotifications.single().actions.last().actionIntent).savedIntent)
        HifzReminder.clearReminder(app)
        HifzReminder.receive(app, latestPause)
        assertFalse(HifzReminder.controlState(app).pausedToday)
    }

    @Test
    fun quietHoursDropAnOvernightCatchUp_andKeepTheNextChosenMorningTime() = isolated { app ->
        val day = todayEpochDay()
        fun at(d: Long, minute: Int) = LocalDate.ofEpochDay(d).atTime(minute / 60, minute % 60).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        HifzSettings.write(app, HifzSettings(times = HifzTimes(minutes = setOf(420, 1335)), quietHours = true))
        HifzRepository.restore(app, point(10L, 1, day, at(day, 1335)).copy(fresh = 3))
        val prefs = app.getSharedPreferences("quran_hifz", Context.MODE_PRIVATE)
        prefs.edit().putString("armed", "$day|at1335|${at(day, 1335)}").commit()

        HifzReminder.deliver(app, day, HifzWhen.At(1335), at(day, 1335))

        assertTrue(HifzReminder.postedIds(app).isEmpty())
        assertEquals("${day + 1}|at420|${at(day + 1, 420)}", prefs.getString("armed", null))
        assertEquals(at(day + 1, 420), Shadows.shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.single().triggerAtTime)
    }

    @Test
    fun quietNotificationRefresh_doesNotSpendBudgetOrRestartTheGap() = isolated { app ->
        val now = System.currentTimeMillis()
        val day = epochDay(now)
        val time = HifzWhen.At(600)
        HifzSettings.write(app, HifzSettings(times = HifzTimes(minutes = setOf(time.minute))))
        val first = point(10L, 1, day, now)
        val second = point(20L, 2, day, now)
        listOf(first, second).forEach { HifzRepository.restore(app, it) }
        invokeRemind(app, mapOf(day to setOf(time)), now)
        HifzRepository.delete(app, first.id)

        HifzReminder::class.java.getDeclaredMethod("refreshLocked", Context::class.java, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(HifzReminder, app, now + 60_000L)

        assertEquals(setOf(second.id), HifzReminder.postedIds(app))
        assertEquals(1, HifzReminder.controlState(app, now + 60_000L).alertsToday)
        assertEquals(now, app.getSharedPreferences("quran_hifz", Context.MODE_PRIVATE).getLong("last_alert_at", -1))
    }

    @Test
    fun nextReminderLabels_respectGapBudgetAndPause() = isolated { app ->
        val day = todayEpochDay()
        fun at(d: Long, minute: Int) = LocalDate.ofEpochDay(d).atTime(minute / 60, minute % 60).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val now = at(day, 605)
        HifzSettings.write(app, HifzSettings(times = HifzTimes(minutes = setOf(620, 780)), dailyAlertLimit = 3))
        val fresh = point(10L, 1, day, now).copy(fresh = 3)
        val prefs = app.getSharedPreferences("quran_hifz", Context.MODE_PRIVATE)
        prefs.edit().putLong("alert_day", day).putInt("alert_count", 1).putLong("last_alert_at", at(day, 600)).commit()

        val deferred = HifzReminder.nextAsk(app, fresh, null, now)!!
        assertEquals(at(day, 660), deferred.at)
        assertEquals(HifzWhen.At(660), deferred.time)
        assertEquals("اليوم الساعة${HifzReminder.NB}11:00", HifzReminder.slotLabel(deferred, day))
        assertEquals("اليوم الساعة${HifzReminder.NB}11:00", HifzReminder.dayFirstLabel(app, day, now))
        prefs.edit().putInt("alert_count", 3).commit()
        assertEquals(at(day + 1, 620), HifzReminder.nextAsk(app, fresh, null, now)!!.at)
        assertEquals(at(day + 1, 620), HifzReminder.firstQuestion(app, now)!!.at)
        prefs.edit().putInt("alert_count", 0).putLong("paused_day", day).commit()
        assertEquals(at(day + 1, 620), HifzReminder.nextAsk(app, fresh, null, now)!!.at)
    }

    @Test
    fun nextReminderLabels_respectQuietHoursAndDoNotPromiseAnImpossibleSlot() = isolated { app ->
        val day = todayEpochDay()
        fun at(d: Long, minute: Int) = LocalDate.ofEpochDay(d).atTime(minute / 60, minute % 60).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val now = at(day, 1260)
        val settings = HifzSettings(times = HifzTimes(minutes = setOf(420, 1335)), quietHours = true)
        HifzSettings.write(app, settings)
        val fresh = point(10L, 1, day, now).copy(fresh = 3)
        assertEquals(at(day + 1, 420), HifzReminder.nextAsk(app, fresh, null, now)!!.at)
        assertEquals(at(day + 1, 420), HifzReminder.firstQuestion(app, now)!!.at)
        HifzSettings.write(app, settings.copy(times = HifzTimes(minutes = setOf(1380))))
        assertNull(HifzReminder.nextAsk(app, fresh, null, now))
        assertTrue(HifzReminder.pointSentence(app, fresh, now).contains("حين يتاح موعد وفق إعدادات التذكير"))
    }

    @Test
    fun spacedPointLabel_respectsItsOwnServedDay() = isolated { app ->
        val day = todayEpochDay()
        fun at(d: Long, minute: Int) = LocalDate.ofEpochDay(d).atTime(minute / 60, minute % 60).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val now = at(day, 600)
        HifzSettings.write(app, HifzSettings(times = HifzTimes(minutes = setOf(720))))
        val spaced = point(10L, 1, day, now)
        app.getSharedPreferences("quran_hifz", Context.MODE_PRIVATE).edit().putString("checks_asked", "10:$day").commit()

        assertTrue(HifzReminder.pointSentence(app, spaced, now, answerShown = false).contains("غدًا"))
    }

    private fun checkAskedDay(context: Context, id: Long): Long =
        context.getSharedPreferences("quran_hifz", Context.MODE_PRIVATE).getString("checks_asked", null).orEmpty()
            .split(',').firstOrNull { it.startsWith("$id:") }?.substringAfter(':')?.toLongOrNull() ?: -1L

    private fun point(id: Long, surah: Int, due: Long, now: Long) = HifzPoint(
        id = id,
        verse = QuranVerseReference(surah, 1),
        mistakes = listOf(HifzMistake(HifzKind.Word)),
        dueDay = due,
        createdAt = now - 2 * 60 * 60_000L,
        step = 1,
    )

    private fun invokeRemind(context: Context, slots: Map<Long, Set<HifzWhen>>, now: Long) {
        HifzReminder::class.java.getDeclaredMethod("remind", Context::class.java, Map::class.java, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(HifzReminder, context, slots, now)
    }

    private fun isolated(block: (Application) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        HifzRepository.load(app)
        val originalPoints = HifzRepository.points.value.toList()
        val reminderPrefs = app.getSharedPreferences("quran_hifz", Context.MODE_PRIVATE)
        val learningPrefs = app.getSharedPreferences("hifz_learning", Context.MODE_PRIVATE)
        val originalReminderPrefs = reminderPrefs.all.toMap()
        val originalLearningPrefs = learningPrefs.all.toMap()
        val around = HifzPrayerSpans.around
        val forWait = HifzPrayerSpans.forWait
        try {
            originalPoints.forEach { HifzRepository.delete(app, it.id) }
            reminderPrefs.edit().clear().commit()
            learningPrefs.edit().clear().commit()
            app.getSystemService(NotificationManager::class.java).cancelAll()
            HifzPrayerSpans.around = { emptyList() }
            HifzPrayerSpans.forWait = { emptyList() }
            block(app)
        } finally {
            HifzRepository.points.value.toList().forEach { HifzRepository.delete(app, it.id) }
            originalPoints.forEach { HifzRepository.restore(app, it) }
            HifzReminder.clearReminder(app)
            restorePreferences(reminderPrefs, originalReminderPrefs)
            restorePreferences(learningPrefs, originalLearningPrefs)
            HifzPrayerSpans.around = around
            HifzPrayerSpans.forWait = forWait
        }
    }

    private fun restorePreferences(preferences: SharedPreferences, snapshot: Map<String, *>) {
        val edit = preferences.edit().clear()
        snapshot.forEach { (key, value) ->
            when (value) {
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is String -> edit.putString(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        edit.commit()
    }
}
