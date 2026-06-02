package com.tunisianprayertimes.wake

import android.content.Context
import android.util.Log
import com.tunisianprayertimes.ManualSilenceScheduler
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.PrayerSilenceConfig
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.SilenceAlarmComputer
import com.tunisianprayertimes.SilenceModeController
import com.tunisianprayertimes.SilenceScheduler
import com.tunisianprayertimes.SilenceStatus
import java.util.Calendar

internal object AwakeCheckSilencePolicy {
    private const val TAG = "AwakeCheckSilence"

    fun shouldCancelBeforeStart(
        context: Context,
        autoSilenceOverrideAllowed: Boolean,
        scheduledTriggerAtMillis: Long? = null,
    ): Boolean = shouldCancel(
        context = context,
        autoSilenceOverrideAllowed = autoSilenceOverrideAllowed,
        autoSilenceConflictPrayer = null,
        scheduledTriggerAtMillis = scheduledTriggerAtMillis,
        liftAutoSilence = false,
    )

    fun prepareForEscalation(
        context: Context,
        autoSilenceOverrideAllowed: Boolean,
        autoSilenceConflictPrayer: Prayer?,
    ): Boolean = !shouldCancel(
        context = context,
        autoSilenceOverrideAllowed = autoSilenceOverrideAllowed,
        autoSilenceConflictPrayer = autoSilenceConflictPrayer,
        scheduledTriggerAtMillis = null,
        liftAutoSilence = true,
    )

    private fun shouldCancel(
        context: Context,
        autoSilenceOverrideAllowed: Boolean,
        autoSilenceConflictPrayer: Prayer?,
        scheduledTriggerAtMillis: Long?,
        liftAutoSilence: Boolean,
    ): Boolean {
        ManualSilenceScheduler.syncExpiredTimer(context)
        if (!autoSilenceOverrideAllowed && scheduledTriggerAtMillis != null && wasScheduledDuringAutoSilence(context, scheduledTriggerAtMillis)) {
            return true
        }

        if (!SilenceStatus.isAppControlledSilenceActive(context)) {
            return false
        }

        if (PrefsManager.isManualSilenceActive(context)) {
            return true
        }

        if (WakeAlarmScheduler.isSilenceUntilAlarmActive(context)) {
            return true
        }

        if (!PrefsManager.isAutoSilenceActive(context)) {
            return true
        }

        if (!autoSilenceOverrideAllowed) {
            return true
        }

        if (!liftAutoSilence) {
            return false
        }

        val dismissedPrayer = SilenceScheduler.currentSilenceWindowPrayer(context)
            ?: autoSilenceConflictPrayer
        dismissedPrayer?.let { prayer ->
            PrefsManager.setAutoSilenceDismissed(
                context = context,
                millis = System.currentTimeMillis(),
                prayer = prayer,
            )
        }

        val lifted = SilenceModeController.disableAutoSilence(context)
        if (lifted) {
            Log.d(TAG, "Lifted auto-silence for awake follow-up")
        }
        return !lifted && SilenceStatus.isAppControlledSilenceActive(context)
    }

    private fun wasScheduledDuringAutoSilence(context: Context, triggerAtMillis: Long): Boolean {
        if (triggerAtMillis <= 0L || !PrefsManager.isEnabled(context)) {
            return false
        }

        val delegationId = PrefsManager.getDelegationId(context)
        val triggerDay = Calendar.getInstance().apply { timeInMillis = triggerAtMillis }
        val silenceConfigs: Map<Prayer, PrayerSilenceConfig> = Prayer.entries.associateWith { prayer ->
            PrefsManager.getConfig(context, prayer)
        }
        val jomoaaHour = PrefsManager.getJomoaaTimeHour(context)
        val jomoaaMinute = PrefsManager.getJomoaaTimeMinute(context)

        return (-1..0).any { dayOffset ->
            val prayerDay = (triggerDay.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, dayOffset)
            }
            val prayerTimes = PrayerTimesRepository.loadDayPrayerTimes(
                context = context,
                delegationId = delegationId,
                year = prayerDay.get(Calendar.YEAR),
                month = prayerDay.get(Calendar.MONTH) + 1,
                day = prayerDay.get(Calendar.DAY_OF_MONTH),
            ) ?: return@any false

            SilenceAlarmComputer.overlapForTrigger(
                triggerAtMillis = triggerAtMillis,
                prayerDay = prayerDay,
                prayerTimes = prayerTimes,
                configs = silenceConfigs,
                isFriday = prayerDay.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY,
                jomoaaHour = jomoaaHour,
                jomoaaMinute = jomoaaMinute,
            ) != null
        }
    }
}
