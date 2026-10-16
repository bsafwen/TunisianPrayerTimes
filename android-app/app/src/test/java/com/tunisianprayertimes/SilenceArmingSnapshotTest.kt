package com.tunisianprayertimes

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField
import java.util.Calendar
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * The equivalence oracle of plan M0 (6.6, 9.1): the trigger instants of every SILENCE and UNSILENCE alarm
 * the scheduler arms at `Africa/Tunis`, scenario by scenario, recorded from today's code into
 * src/test/resources/silence-snapshots/. Request codes and the scheduler's own reschedule alarms are
 * plumbing and are not compared. After M5 the new scheduler must give the same instants, except the
 * expected differences of 9.1, which are listed per scenario in [EXPECTED_DIFFERENCES] and reviewed once.
 *
 * Record with `SILENCE_SNAPSHOT_RECORD=1` (only from the code M0 starts on).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class SilenceArmingSnapshotTest {
    private lateinit var context: Context
    private lateinit var alarms: ShadowAlarmManager
    private val previousZone = TimeZone.getDefault()

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("Africa/Tunis"))
        context = ApplicationProvider.getApplicationContext()
        alarms = Shadows.shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        Shadows.shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).setNotificationPolicyAccessGranted(true)
        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE).edit().clear()
            // The Ramadan bump is pinned off: these snapshots must not depend on the day they run.
            .putInt("ramadan_isha_override_hijri_year", HijrahDate.now().get(ChronoField.YEAR))
            .commit()
        PrefsManager.setEnabled(context, true)
        setOfficialDates(
            RamadanOverrideChecker.RamadanOverride(
                hijriYear = 1447,
                ramadanStart = LocalDate.of(2026, 2, 19),
                eidFitrDate = LocalDate.of(2026, 3, 20),
                eidAdhaDate = LocalDate.of(2026, 5, 27),
            ),
        )
    }

    @After
    fun tearDown() {
        setOfficialDates(null)
        TimeZone.setDefault(previousZone)
    }

    private fun setOfficialDates(value: RamadanOverrideChecker.RamadanOverride?) {
        val field = RamadanOverrideChecker::class.java.getDeclaredField("cachedOverride")
        field.isAccessible = true
        field.set(RamadanOverrideChecker, value)
    }

    private fun at(date: String, hour: Int, minute: Int): Calendar = Calendar.getInstance().apply {
        val d = LocalDate.parse(date)
        clear()
        set(d.year, d.monthValue - 1, d.dayOfMonth, hour, minute, 0)
    }

    /** Runs the scheduler at each instant in turn and returns the armed silences, one line each. */
    private fun armed(vararg runs: Calendar): List<String> {
        val lines = mutableListOf<String>()
        for (run in runs) {
            SilenceScheduler.scheduleAllInternal(context, run)
            val stamp = run.toInstant().atZone(ZoneId.of("Africa/Tunis")).toLocalDateTime()
            alarms.scheduledAlarms.mapNotNull { alarm ->
                val intent = Shadows.shadowOf(alarm.operation).savedIntent
                val action = intent.action?.substringAfterLast('.') ?: return@mapNotNull null
                if (action != "ACTION_SILENCE" && action != "ACTION_UNSILENCE") return@mapNotNull null
                val prayer = intent.getStringExtra("extra_prayer")
                "run $stamp\t${action.removePrefix("ACTION_")}\t$prayer\t${java.time.Instant.ofEpochMilli(alarm.triggerAtTime)}"
            }.sorted().forEach(lines::add)
        }
        return lines
    }

    private fun check(scenario: String, lines: List<String>) {
        System.getenv("SILENCE_SNAPSHOT_OUT")?.let { out -> File(out, "$scenario.tsv").apply { parentFile.mkdirs() }.writeText(lines.joinToString("\n", postfix = "\n")) }
        val file = File("src/test/resources/silence-snapshots/$scenario.tsv")
        if (System.getenv("SILENCE_SNAPSHOT_RECORD") == "1") {
            file.parentFile.mkdirs()
            file.writeText(lines.joinToString("\n", postfix = "\n"))
            return
        }
        val expected = file.readLines().filter { it.isNotBlank() }
        assertEquals("silence arming of $scenario", expected.joinToString("\n"), lines.joinToString("\n"))
    }

    @Test
    fun weekdayFromMidnight() = check("weekday-0005", armed(at("2026-03-10", 0, 5)))

    @Test
    fun weekdayInsideDhuhr() = check("weekday-1250", armed(at("2026-03-10", 12, 50)))

    @Test
    fun weekdayAfterIsha() = check("weekday-2300", armed(at("2026-03-10", 23, 0)))

    @Test
    fun fridayJumua() = check("friday-0005", armed(at("2026-03-13", 0, 5)))

    @Test
    fun fridayWithAnEditedJumuaTime() {
        PrefsManager.setJomoaaTime(context, 13, 0)
        check("friday-jumua-1300", armed(at("2026-03-13", 0, 5), at("2026-03-13", 13, 5)))
    }

    @Test
    fun ishaEndingAfterMidnight() {
        // A fixed Isha end at 00:30 rolls past midnight; the window survives the 00:01 reschedule.
        PrefsManager.setSilenceMode(context, Prayer.ISHA, SilenceMode.FIXED_TIME)
        PrefsManager.setFixedTime(context, Prayer.ISHA, 0, 30)
        check("isha-after-midnight", armed(at("2026-03-10", 23, 50), at("2026-03-11", 0, 1)))
    }

    @Test
    fun overlappingWindows() {
        // Asr's window runs past Maghrib's start: an UNSILENCE inside another open window.
        PrefsManager.setAfterMinutes(context, Prayer.ASR, 200)
        check("overlap-asr-maghrib", armed(at("2026-03-10", 0, 5), at("2026-03-10", 18, 30)))
    }

    @Test
    fun fixedStartAndEnd() {
        PrefsManager.setDelayMode(context, Prayer.FAJR, DelayMode.FIXED_TIME)
        PrefsManager.setDelayFixedTime(context, Prayer.FAJR, 5, 0)
        PrefsManager.setSilenceMode(context, Prayer.FAJR, SilenceMode.FIXED_TIME)
        PrefsManager.setFixedTime(context, Prayer.FAJR, 6, 30)
        PrefsManager.setEndOffsetMinutes(context, Prayer.MAGHRIB, 40)
        PrefsManager.setDelayMinutes(context, Prayer.MAGHRIB, 5)
        check("fixed-start-end", armed(at("2026-03-10", 0, 5)))
    }

    @Test
    fun disabledPrayer() {
        PrefsManager.setPrayerSilenceEnabled(context, Prayer.ASR, false)
        check("asr-disabled", armed(at("2026-07-21", 0, 5)))
    }

    @Test
    fun eidAlFitr() = check("eid-fitr-1447", armed(at("2026-03-19", 22, 0), at("2026-03-20", 0, 5)))

    @Test
    fun anotherDelegation() {
        PrefsManager.setDelegationId(context, 386)
        check("delegation-386-summer", armed(at("2026-07-21", 0, 5), at("2026-07-21", 21, 0)))
    }

    companion object {
        /**
         * The M0 recordings (silence-snapshots/m0/, today's code at the commit M0 started on) against the
         * scheduler of 6.9, reviewed once (2026-10-15) scenario by scenario. Every difference is one of plan
         * 9.1's expected kinds; the files in silence-snapshots/ are the reviewed result and the baseline now:
         * - the Dhuhr start at 12:40 Tunis time instead of the adhan (D12), in every scenario with a Dhuhr;
         * - every window of D+1 armed a day ahead (today only after D's last window ended);
         * - the start alarm of a window already open is cancelled instead of left armed in the past
         *   (friday-jumua-1300, overlap-asr-maghrib), which changes no silence;
         * - no other trigger instant changed: an Isha end at 00:30 saved before D16 stored its adhan offset keeps
         *   today's instant (the legacy roll of [com.tunisianprayertimes.silence.SilenceEndpoints]).
         */
        val EXPECTED_DIFFERENCES = listOf(
            "Dhuhr start 12:40 at Inm places (D12)",
            "D+1 armed a day ahead",
            "past starts of open windows cancelled",
        )
    }
}
