package com.tunisianprayertimes.ui

import android.content.Context
import com.tunisianprayertimes.ManualSilenceMode
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.R
import java.util.Calendar
import java.util.Locale

internal enum class PhoneSilenceReasonSource {
    AUTO_PRAYER,
    AUTO_PRAYER_UNKNOWN,
    MANUAL_UNTIL_STOPPED,
    MANUAL_UNTIL_TIME,
    MANUAL_UNTIL_PRAYER,
    WAKE_ALARM,
    EXTERNAL,
}

internal data class PhoneSilenceReason(
    val source: PhoneSilenceReasonSource,
    val prayer: Prayer? = null,
    val endsAtMillis: Long = -1L,
)

internal fun phoneStatusNoticeText(
    context: Context,
    isPhoneSilenced: Boolean,
    hasDnd: Boolean,
    silenceReason: PhoneSilenceReason?,
): String? {
    if (hasDnd && !isPhoneSilenced) return null

    return when {
        !hasDnd -> context.getString(R.string.phone_status_permission_short)
        silenceReason != null -> phoneSilenceReasonText(context, silenceReason)
        else -> context.getString(R.string.phone_status_silent_short)
    }
}

internal fun phoneSilenceReasonText(context: Context, reason: PhoneSilenceReason): String {
    return when (reason.source) {
        PhoneSilenceReasonSource.AUTO_PRAYER -> context.getString(
            R.string.phone_status_silent_auto_prayer,
            prayerName(context, requireNotNull(reason.prayer)),
        )
        PhoneSilenceReasonSource.AUTO_PRAYER_UNKNOWN -> context.getString(R.string.phone_status_silent_auto)
        PhoneSilenceReasonSource.MANUAL_UNTIL_STOPPED -> context.getString(R.string.phone_status_silent_manual)
        PhoneSilenceReasonSource.MANUAL_UNTIL_TIME -> context.getString(
            R.string.phone_status_silent_manual_until_time,
            formatTimeOfDay(reason.endsAtMillis),
        )
        PhoneSilenceReasonSource.MANUAL_UNTIL_PRAYER -> context.getString(
            R.string.phone_status_silent_manual_until_prayer,
            prayerName(context, requireNotNull(reason.prayer)),
        )
        PhoneSilenceReasonSource.WAKE_ALARM -> context.getString(R.string.phone_status_silent_wake_alarm)
        PhoneSilenceReasonSource.EXTERNAL -> context.getString(R.string.phone_status_silent_external)
    }
}

internal fun resolvePhoneSilenceReason(
    isPhoneSilenced: Boolean,
    autoSilenceActive: Boolean,
    activeAutoSilencePrayer: Prayer?,
    manualSilenceActive: Boolean,
    manualSilenceMode: ManualSilenceMode,
    manualTargetPrayer: Prayer,
    manualSilenceEndsAtMillis: Long,
    wakeSilenceUntilAlarmActive: Boolean,
    currentTimeMillis: Long,
): PhoneSilenceReason? {
    if (!isPhoneSilenced) return null

    if (manualSilenceActive) {
        return when (manualSilenceMode) {
            ManualSilenceMode.UNTIL_PRAYER -> PhoneSilenceReason(
                source = PhoneSilenceReasonSource.MANUAL_UNTIL_PRAYER,
                prayer = manualTargetPrayer,
            )
            ManualSilenceMode.DURATION -> if (manualSilenceEndsAtMillis > currentTimeMillis) {
                PhoneSilenceReason(
                    source = PhoneSilenceReasonSource.MANUAL_UNTIL_TIME,
                    endsAtMillis = manualSilenceEndsAtMillis,
                )
            } else {
                PhoneSilenceReason(PhoneSilenceReasonSource.MANUAL_UNTIL_STOPPED)
            }
            ManualSilenceMode.UNTIL_STOPPED -> PhoneSilenceReason(PhoneSilenceReasonSource.MANUAL_UNTIL_STOPPED)
        }
    }

    if (autoSilenceActive) {
        return if (activeAutoSilencePrayer != null) {
            PhoneSilenceReason(
                source = PhoneSilenceReasonSource.AUTO_PRAYER,
                prayer = activeAutoSilencePrayer,
            )
        } else {
            PhoneSilenceReason(PhoneSilenceReasonSource.AUTO_PRAYER_UNKNOWN)
        }
    }

    if (wakeSilenceUntilAlarmActive) {
        return PhoneSilenceReason(PhoneSilenceReasonSource.WAKE_ALARM)
    }

    return PhoneSilenceReason(PhoneSilenceReasonSource.EXTERNAL)
}

private fun prayerName(context: Context, prayer: Prayer): String = when (prayer) {
    Prayer.FAJR -> context.getString(R.string.prayer_fajr)
    Prayer.DHUHR -> context.getString(R.string.prayer_dhuhr)
    Prayer.ASR -> context.getString(R.string.prayer_asr)
    Prayer.MAGHRIB -> context.getString(R.string.prayer_maghrib)
    Prayer.ISHA -> context.getString(R.string.prayer_isha)
    Prayer.JOMOAA -> context.getString(R.string.prayer_jomoaa)
    Prayer.AID_FITR -> context.getString(R.string.prayer_aid_fitr)
    Prayer.AID_ADHA -> context.getString(R.string.prayer_aid_adha)
}

private fun formatTimeOfDay(targetTimeInMillis: Long): String {
    val calendar = Calendar.getInstance().apply { timeInMillis = targetTimeInMillis }
    return String.format(
        Locale.US,
        "%02d:%02d",
        calendar.get(Calendar.HOUR_OF_DAY),
        calendar.get(Calendar.MINUTE),
    )
}