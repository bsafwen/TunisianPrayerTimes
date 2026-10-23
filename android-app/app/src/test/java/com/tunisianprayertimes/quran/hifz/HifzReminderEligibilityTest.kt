package com.tunisianprayertimes.quran.hifz

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.quran.QuranVerseReference
import org.junit.Assert.assertEquals
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
        assertEquals(today, app.getSharedPreferences("quran_hifz", Context.MODE_PRIVATE).getLong("reminded_day", -1L))
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
        assertEquals(yesterday, app.getSharedPreferences("quran_hifz", Context.MODE_PRIVATE).getLong("reminded_day", -1L))
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
        assertEquals(today, prefs.getLong("reminded_day", -1L))
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
        assertEquals(-1L, app.getSharedPreferences("quran_hifz", Context.MODE_PRIVATE).getLong("reminded_day", -1L))
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
        assertEquals(today, prefs.getLong("reminded_day", -1L))
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
        assertEquals(-1L, prefs.getLong("reminded_day", -1L))

        invokeRemind(app, mapOf(today to setOf(defaultTime)), now)

        assertEquals(setOf(spaced.id), HifzReminder.postedIds(app))
        assertEquals(today, prefs.getLong("reminded_day", -1L))
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
            assertEquals(-1L, prefs.getLong("reminded_day", -1L))
        } finally {
            notifications.setNotificationsEnabled(true)
        }

        invokeRemind(app, mapOf(today to setOf(later)), now)

        assertEquals(setOf(spaced.id), HifzReminder.postedIds(app))
        assertEquals(today, prefs.getLong("reminded_day", -1L))
    }

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
