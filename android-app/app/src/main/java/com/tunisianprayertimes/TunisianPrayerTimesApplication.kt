package com.tunisianprayertimes

import android.app.Application
import android.util.Log
import com.tunisianprayertimes.adhkar.DhikrReminderScheduler
import com.tunisianprayertimes.platform.SharedAndroid
import com.tunisianprayertimes.platform.SilenceController
import com.tunisianprayertimes.platform.TimerScheduler
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TunisianPrayerTimesApplication : Application() {
    // Always dispatch updates: publication holds the date-store lock, while
    // scheduling has its own lock and may read the store from a worker thread.
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // SharedPreferences keeps listeners weakly; retain the subscription for the process lifetime.
    private var dhikrLocationSubscription: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        // Preferences, official Ramadan/Eid dates and the prayer-time and location loaders,
        // started the same way as in the TV app.
        SharedAndroid.init(this)
        AnalyticsTracker.applyRegionalCollection(this)
        SilenceController.init(this)
        TimerScheduler.init(this)
        // The reminders of «المراجعة» keep clear of the prayers, and a prayer ends a fresh point's wait.
        com.tunisianprayertimes.quran.hifz.HifzReminder.installPrayerSpans(this)
        refreshDhikrReminders()
        restoreManualSilenceTimer()
        dhikrLocationSubscription = PrefsManager.observeLocationSelection(this) { refreshDhikrReminders() }
        observeOfficialDateChanges()
    }

    private fun restoreManualSilenceTimer() {
        applicationScope.launch(Dispatchers.IO) {
            // A force-stop clears the end alarm of a timed manual silence, as a reboot does.
            runCatching { ManualSilenceScheduler.restoreTimer(this@TunisianPrayerTimesApplication) }
                .onFailure { Log.w("ManualSilence", "Could not restore the manual silence timer", it) }
        }
    }

    private fun refreshDhikrReminders() {
        applicationScope.launch(Dispatchers.IO) {
            try {
                // Rearm on process start: alarms can be dropped by force-stop or OEM
                // cleanup while the prefs still claim they are scheduled.
                DhikrReminderScheduler.refresh(this@TunisianPrayerTimesApplication, rearm = true)
            } catch (error: Exception) {
                Log.w("Adhkar", "Could not refresh dhikr reminders", error)
            }
            // The hifz reminders after prayers too: they follow the city's prayer times.
            runCatching { com.tunisianprayertimes.quran.hifz.HifzReminder.refresh(this@TunisianPrayerTimesApplication) }
                .onFailure { Log.w("Hifz", "Could not refresh hifz reminders", it) }
            runCatching { com.tunisianprayertimes.fasting.FastingReminder.refresh(this@TunisianPrayerTimesApplication) }
                .onFailure { Log.w("Fasting", "Could not refresh the fasting reminder", it) }
            runCatching { com.tunisianprayertimes.remembrance.DeathReminder.refresh(this@TunisianPrayerTimesApplication) }
                .onFailure { Log.w("Remembrance", "Could not refresh the remembrance reminder", it) }
        }
    }

    private fun observeOfficialDateChanges() {
        applicationScope.launch {
            OfficialIslamicDates.updates.map { announcements ->
                val year = HijrahDate.now().get(ChronoField.YEAR)
                val calendar = TunisianHijriCalendar(announcements)
                // Only changed event days affect scheduling; refreshed metadata and
                // announcements for a browsed historical year do not require a reset.
                Triple(calendar.month(year, 9).start, calendar.month(year, 10).start,
                    calendar.month(year, 12).start)
            }.distinctUntilChanged().collect {
                try {
                    // The pass stays on the main thread, in turn with the silence receiver; abroad, its days come first
                    // from a worker, as a cold process may scan a year of times for them (6.5).
                    if (!PrayerTimesRepository.isWarm(this@TunisianPrayerTimesApplication)) {
                        withContext(Dispatchers.Default) { runCatching { PrayerTimesRepository.warm(this@TunisianPrayerTimesApplication) } }
                    }
                    PrefsManager.applyRamadanIshaOverrideIfNeeded(this@TunisianPrayerTimesApplication)
                    SilenceScheduler.onOfficialDatesChanged(this@TunisianPrayerTimesApplication)
                    ScheduleRefreshCoordinator.syncWake(this@TunisianPrayerTimesApplication)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w("OfficialIslamicDates", "Could not refresh scheduling after a date correction", error)
                }
            }
        }
        // The fasting reminder also follows an announcement that keeps the estimated date: it lifts the day of
        // margin around Ramadan and the Eids, and an eve already under way is then still reminded before Fajr.
        applicationScope.launch {
            OfficialIslamicDates.updates.map { announcements ->
                val year = HijrahDate.now().get(ChronoField.YEAR)
                val calendar = TunisianHijriCalendar(announcements)
                listOf(9, 10, 12).map { month -> calendar.month(year, month).let { it.start to it.isEstimated } }
            }.distinctUntilChanged().collect {
                withContext(Dispatchers.IO) {
                    runCatching { com.tunisianprayertimes.fasting.FastingReminder.refresh(this@TunisianPrayerTimesApplication) }
                        .onFailure { Log.w("Fasting", "Could not refresh the fasting reminder after a date change", it) }
                }
            }
        }
    }
}
