package com.tunisianprayertimes

import android.app.Application
import android.util.Log
import com.tunisianprayertimes.platform.GouvernoratLoader
import com.tunisianprayertimes.platform.PrayerDataLoader
import com.tunisianprayertimes.platform.Preferences
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

    override fun onCreate() {
        super.onCreate()
        Preferences.init(this)
        // Migrate before any packaged publication can replace the released app's legacy record.
        RamadanOverrideChecker.loadCachedOverrideIfNeeded()
        // The same announcements are available to the calendar, boot receivers,
        // and prayer scheduling even on a first launch without a connection.
        assets.list("official-islamic-dates").orEmpty().filter { it.endsWith(".json") }.forEach { name ->
            runCatching {
                assets.open("official-islamic-dates/$name").bufferedReader().use { reader ->
                    OfficialIslamicDates.importJson(reader.readText())
                }
            }
        }
        PrayerDataLoader.init(this)
        GouvernoratLoader.init(this)
        SilenceController.init(this)
        TimerScheduler.init(this)
        observeOfficialDateChanges()
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
