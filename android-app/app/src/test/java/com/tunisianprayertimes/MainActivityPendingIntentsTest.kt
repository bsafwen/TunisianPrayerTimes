package com.tunisianprayertimes

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.wake.AwakeCheckService
import com.tunisianprayertimes.wake.WakeAlarmScheduler
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MainActivityPendingIntentsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private class Sender(
        val name: String,
        val create: (Context) -> PendingIntent,
        val uri: String,
        val launchFlags: Int,
        val destination: String?,
    )

    private val awakeCheck = Sender(
        name = "awake check",
        create = MainActivityPendingIntents::awakeCheck,
        uri = MainActivityPendingIntents.AWAKE_CHECK_URI,
        launchFlags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        destination = MainTabNavigation.DESTINATION_ALARMS,
    )
    private val wakeAlarmClock = Sender(
        name = "wake alarm clock",
        create = MainActivityPendingIntents::wakeAlarmClock,
        uri = MainActivityPendingIntents.WAKE_ALARM_CLOCK_URI,
        launchFlags = Intent.FLAG_ACTIVITY_SINGLE_TOP,
        destination = null,
    )
    private val silenceAlarmClock = Sender(
        name = "silence alarm clock",
        create = MainActivityPendingIntents::silenceAlarmClock,
        uri = MainActivityPendingIntents.SILENCE_ALARM_CLOCK_URI,
        launchFlags = 0,
        destination = null,
    )
    private val silenceGuard = Sender(
        name = "silence guard",
        create = MainActivityPendingIntents::silenceGuard,
        uri = MainActivityPendingIntents.SILENCE_GUARD_URI,
        launchFlags = 0,
        destination = null,
    )
    private val senders = listOf(awakeCheck, wakeAlarmClock, silenceAlarmClock, silenceGuard)

    @Before
    fun setup() {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        Shadows.shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @Test
    fun everySenderHasItsOwnPendingIntentIdentity() {
        val intents = senders.associateWith { Shadows.shadowOf(it.create(context)).savedIntent }

        for ((sender, intent) in intents) {
            for ((other, otherIntent) in intents) {
                if (sender === other) continue
                assertFalse("${sender.name} shares a record with ${other.name}", intent.filterEquals(otherIntent))
            }
        }
    }

    @Test
    fun everySenderKeepsItsFlagsAndExtrasAfterTheOthersAreCreated() {
        // Sharing one record, a later sender inherits the first one's launch flags,
        // and FLAG_UPDATE_CURRENT replaces the extras every earlier sender posted.
        val created = senders.map { sender -> sender to sender.create(context) }

        created.forEach { (sender, pendingIntent) -> assertLaunches(sender, pendingIntent) }
    }

    @Test
    fun awakeCheckOpensAlarmsWithItsOwnFlagsWhenTheAlarmClockIntentAlreadyExists() {
        // AlarmManager keeps the wake alarm's AlarmClockInfo intent alive, so it is
        // usually created before the awake-check notification is posted.
        wakeAlarmClock.create(context)
        silenceAlarmClock.create(context)

        assertLaunches(awakeCheck, awakeCheck.create(context))
    }

    @Test
    fun otherSendersDoNotUpdateThePostedAwakeCheckRecord() {
        // On a device, FLAG_UPDATE_CURRENT from another sender would replace the posted
        // record's extras and drop the alarms destination. Robolectric only merges extras,
        // so check that no other sender gets the posted record back.
        val posted = awakeCheck.create(context)

        (senders - awakeCheck).forEach { other ->
            assertNotSame(other.name, posted, other.create(context))
        }
        assertLaunches(awakeCheck, posted)
    }

    @Test
    fun awakeCheckNotificationUsesAwakeCheckIntent() {
        val controller = Robolectric.buildService(
            AwakeCheckService::class.java,
            AwakeCheckService.intent(
                context = context,
                eventId = "wake:main:pending-intent-identity",
                ringtonePreset = null,
                customRingtoneUri = null,
            ),
        )
        val service = controller.create().get()
        service.onStartCommand(controller.intent, 0, 1)

        val notification = Shadows.shadowOf(service).lastForegroundNotification
        assertLaunches(awakeCheck, notification.contentIntent)
        controller.destroy()
    }

    @Test
    fun silenceGuardNotificationUsesSilenceGuardIntent() {
        val controller = Robolectric.buildService(SilenceGuardService::class.java)
        val service = controller.create().get()

        val notification = Shadows.shadowOf(service).lastForegroundNotification
        assertLaunches(silenceGuard, notification.contentIntent)
        controller.destroy()
    }

    @Test
    fun silenceAlarmClockInfoUsesSilenceAlarmClockIntent() {
        PrefsManager.setEnabled(context, true)

        SilenceScheduler.scheduleAll(context)

        val showIntents = scheduledShowIntents()
        assertTrue("expected an alarm-clock silence alarm", showIntents.isNotEmpty())
        showIntents.forEach { assertLaunches(silenceAlarmClock, it) }
    }

    @Test
    fun wakeAlarmClockInfoUsesWakeAlarmClockIntent() {
        val alarm = PrayerWakeConfig(
            id = "pending-intent-identity",
            prayer = Prayer.FAJR,
            enabled = true,
            mainAlarm = WakeMainAlarmConfig(
                mode = WakeMainAlarmMode.FIXED_TIME,
                fixedTime = ClockTime(8, 0),
            ),
        )

        WakeAlarmScheduler.scheduleAllInternal(context, Calendar.getInstance(), listOf(alarm))

        val showIntents = scheduledShowIntents()
        assertTrue("expected an alarm-clock wake alarm", showIntents.isNotEmpty())
        showIntents.forEach { assertLaunches(wakeAlarmClock, it) }
    }

    private fun scheduledShowIntents(): List<PendingIntent> {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        return Shadows.shadowOf(alarmManager).scheduledAlarms.mapNotNull { it.alarmClockInfo?.showIntent }
    }

    private fun assertLaunches(sender: Sender, pendingIntent: PendingIntent) {
        val shadow = Shadows.shadowOf(pendingIntent)
        val intent = shadow.savedIntent
        assertTrue(sender.name, shadow.isActivity)
        assertEquals(sender.name, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, shadow.flags)
        assertEquals(sender.name, MainActivity::class.java.name, intent.component?.className)
        assertNull(sender.name, intent.action)
        assertEquals(sender.name, sender.uri, intent.dataString)
        assertEquals(sender.name, sender.launchFlags, intent.flags)
        assertEquals(
            sender.name,
            sender.destination,
            intent.getStringExtra(MainTabNavigation.EXTRA_DESTINATION),
        )
    }
}
