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
        SilenceController.init(this)
        TimerScheduler.init(this)
        refreshDhikrReminders()
        dhikrLocationSubscription = PrefsManager.observeLocationSelection(this) { refreshDhikrReminders() }
        observeOfficialDateChanges()
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
    }
}
