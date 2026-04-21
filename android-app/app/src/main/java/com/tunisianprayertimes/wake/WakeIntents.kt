package com.tunisianprayertimes.wake

import android.content.Intent
import android.net.Uri
import com.tunisianprayertimes.OffsetDirection
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.RingtonePreset
import com.tunisianprayertimes.WakeMainAlarmMode

const val ACTION_STOP_WAKE_ALARM = "com.tunisianprayertimes.action.STOP_WAKE_ALARM"

const val EXTRA_EVENT_ID = "extra_event_id"
const val EXTRA_PRAYER = "extra_prayer"
const val EXTRA_EFFECTIVE_PRAYER = "extra_effective_prayer"
const val EXTRA_MAIN_ALARM_MODE = "extra_main_alarm_mode"
const val EXTRA_HOUR = "extra_hour"
const val EXTRA_MINUTE = "extra_minute"
const val EXTRA_RINGTONE = "extra_ringtone"
const val EXTRA_CUSTOM_RINGTONE_URI = "extra_custom_ringtone_uri"
const val EXTRA_VIBRATION_ONLY = "extra_vibration_only"
const val EXTRA_WAKE_UP_CHECK = "extra_wake_up_check"
const val EXTRA_PROGRESSIVE_VOLUME = "extra_progressive_volume"
const val EXTRA_SNORE_TRACKING_ENABLED = "extra_snore_tracking_enabled"
const val EXTRA_IS_SUBALARM = "extra_is_subalarm"
const val EXTRA_SUBALARM_ID = "extra_subalarm_id"
const val EXTRA_OFFSET_MINUTES = "extra_offset_minutes"
const val EXTRA_OFFSET_DIRECTION = "extra_offset_direction"
const val EXTRA_WAKE_UP_CHECK_LEFT_OPERAND = "extra_wake_up_check_left_operand"
const val EXTRA_WAKE_UP_CHECK_RIGHT_OPERAND = "extra_wake_up_check_right_operand"
const val EXTRA_WAKE_UP_CHECK_OPERATOR = "extra_wake_up_check_operator"
const val EXTRA_WAKE_UP_CHECK_ANSWER = "extra_wake_up_check_answer"

data class WakeUpCheckChallenge(
    val leftOperand: Int,
    val rightOperand: Int,
    val operatorSymbol: String,
    val answer: Int,
) {
    val prompt: String
        get() = "\u200E$leftOperand $operatorSymbol $rightOperand"

    fun matches(input: String): Boolean = input.trim().toIntOrNull() == answer
}

data class WakeTriggerPayload(
    val eventId: String,
    val prayer: Prayer,
    val effectivePrayer: Prayer,
    val mainAlarmMode: WakeMainAlarmMode = WakeMainAlarmMode.PRAYER_RELATIVE,
    val hour: Int,
    val minute: Int,
    val ringtone: RingtonePreset,
    val customRingtoneUri: String? = null,
    val vibrationOnly: Boolean,
    val wakeUpCheckEnabled: Boolean,
    val progressiveVolume: Boolean,
    val snoreTrackingEnabled: Boolean,
    val wakeUpCheckChallenge: WakeUpCheckChallenge? = null,
    val isSubAlarm: Boolean,
    val subAlarmId: String? = null,
    val offsetMinutes: Int? = null,
    val offsetDirection: OffsetDirection? = null,
)

fun wakeMainEventId(alarmId: String): String = "wake:main:$alarmId"

fun wakeSubAlarmEventId(alarmId: String, subAlarmId: String): String {
    require(alarmId.isNotBlank()) { "alarmId must not be blank." }
    require(subAlarmId.isNotBlank()) { "subAlarmId must not be blank." }
    return "wake:sub:$alarmId:$subAlarmId"
}

fun wakeEventRequestCode(eventId: String): Int = eventId.hashCode()

fun wakeEventUri(eventId: String): Uri = Uri.parse("tunisianprayertimes://wake/$eventId")

fun Intent.populateWakeTriggerPayload(
    eventId: String,
    prayer: Prayer,
    effectivePrayer: Prayer = prayer,
    mainAlarmMode: WakeMainAlarmMode = WakeMainAlarmMode.PRAYER_RELATIVE,
    hour: Int,
    minute: Int,
    ringtone: RingtonePreset,
    customRingtoneUri: String? = null,
    vibrationOnly: Boolean,
    wakeUpCheckEnabled: Boolean,
    progressiveVolume: Boolean,
    snoreTrackingEnabled: Boolean,
    wakeUpCheckChallenge: WakeUpCheckChallenge? = null,
    isSubAlarm: Boolean,
    subAlarmId: String? = null,
    offsetMinutes: Int? = null,
    offsetDirection: OffsetDirection? = null,
): Intent = apply {
    putExtra(EXTRA_EVENT_ID, eventId)
    putExtra(EXTRA_PRAYER, prayer.name)
    putExtra(EXTRA_EFFECTIVE_PRAYER, effectivePrayer.name)
    putExtra(EXTRA_MAIN_ALARM_MODE, mainAlarmMode.name)
    putExtra(EXTRA_HOUR, hour)
    putExtra(EXTRA_MINUTE, minute)
    putExtra(EXTRA_RINGTONE, ringtone.name)
    customRingtoneUri?.let { putExtra(EXTRA_CUSTOM_RINGTONE_URI, it) }
    putExtra(EXTRA_VIBRATION_ONLY, vibrationOnly)
    putExtra(EXTRA_WAKE_UP_CHECK, wakeUpCheckEnabled)
    putExtra(EXTRA_PROGRESSIVE_VOLUME, progressiveVolume)
    putExtra(EXTRA_SNORE_TRACKING_ENABLED, snoreTrackingEnabled)
    putExtra(EXTRA_IS_SUBALARM, isSubAlarm)
    subAlarmId?.let { putExtra(EXTRA_SUBALARM_ID, it) }
    offsetMinutes?.let { putExtra(EXTRA_OFFSET_MINUTES, it) }
    offsetDirection?.let { putExtra(EXTRA_OFFSET_DIRECTION, it.name) }
    wakeUpCheckChallenge?.let { challenge ->
        putExtra(EXTRA_WAKE_UP_CHECK_LEFT_OPERAND, challenge.leftOperand)
        putExtra(EXTRA_WAKE_UP_CHECK_RIGHT_OPERAND, challenge.rightOperand)
        putExtra(EXTRA_WAKE_UP_CHECK_OPERATOR, challenge.operatorSymbol)
        putExtra(EXTRA_WAKE_UP_CHECK_ANSWER, challenge.answer)
    }
}

fun Intent.populateWakeStopPayload(eventId: String): Intent = apply {
    putExtra(EXTRA_EVENT_ID, eventId)
}

fun Intent.toWakeTriggerPayload(): WakeTriggerPayload? {
    val eventId = getStringExtra(EXTRA_EVENT_ID) ?: return null
    val prayer = getStringExtra(EXTRA_PRAYER)
        ?.let { rawPrayer -> runCatching { Prayer.valueOf(rawPrayer) }.getOrNull() }
        ?: return null
    val effectivePrayer = getStringExtra(EXTRA_EFFECTIVE_PRAYER)
        ?.let { rawPrayer -> runCatching { Prayer.valueOf(rawPrayer) }.getOrNull() }
        ?: prayer
    val mainAlarmMode = getStringExtra(EXTRA_MAIN_ALARM_MODE)
        ?.let { rawMode -> runCatching { WakeMainAlarmMode.valueOf(rawMode) }.getOrNull() }
        ?: WakeMainAlarmMode.PRAYER_RELATIVE
    val ringtone = getStringExtra(EXTRA_RINGTONE)
        ?.let { rawRingtone -> runCatching { RingtonePreset.valueOf(rawRingtone) }.getOrNull() }
        ?: RingtonePreset.DEFAULT_ALARM
    val wakeUpCheckChallenge = if (
        hasExtra(EXTRA_WAKE_UP_CHECK_LEFT_OPERAND) &&
        hasExtra(EXTRA_WAKE_UP_CHECK_RIGHT_OPERAND) &&
        hasExtra(EXTRA_WAKE_UP_CHECK_ANSWER)
    ) {
        getStringExtra(EXTRA_WAKE_UP_CHECK_OPERATOR)?.let { operatorSymbol ->
            WakeUpCheckChallenge(
                leftOperand = getIntExtra(EXTRA_WAKE_UP_CHECK_LEFT_OPERAND, 0),
                rightOperand = getIntExtra(EXTRA_WAKE_UP_CHECK_RIGHT_OPERAND, 0),
                operatorSymbol = operatorSymbol,
                answer = getIntExtra(EXTRA_WAKE_UP_CHECK_ANSWER, 0),
            )
        }
    } else {
        null
    }
    val rawDirection = getStringExtra(EXTRA_OFFSET_DIRECTION)

    return WakeTriggerPayload(
        eventId = eventId,
        prayer = prayer,
        effectivePrayer = effectivePrayer,
        mainAlarmMode = mainAlarmMode,
        hour = getIntExtra(EXTRA_HOUR, 7),
        minute = getIntExtra(EXTRA_MINUTE, 0),
        ringtone = ringtone,
        customRingtoneUri = getStringExtra(EXTRA_CUSTOM_RINGTONE_URI),
        vibrationOnly = getBooleanExtra(EXTRA_VIBRATION_ONLY, false),
        wakeUpCheckEnabled = getBooleanExtra(EXTRA_WAKE_UP_CHECK, false),
        progressiveVolume = getBooleanExtra(EXTRA_PROGRESSIVE_VOLUME, false),
        snoreTrackingEnabled = getBooleanExtra(EXTRA_SNORE_TRACKING_ENABLED, false),
        wakeUpCheckChallenge = wakeUpCheckChallenge,
        isSubAlarm = getBooleanExtra(EXTRA_IS_SUBALARM, false),
        subAlarmId = getStringExtra(EXTRA_SUBALARM_ID),
        offsetMinutes = if (hasExtra(EXTRA_OFFSET_MINUTES)) getIntExtra(EXTRA_OFFSET_MINUTES, 0) else null,
        offsetDirection = rawDirection?.let { direction ->
            runCatching { OffsetDirection.valueOf(direction) }.getOrNull()
        },
    )
}

fun Intent.wakeEventId(): String? = getStringExtra(EXTRA_EVENT_ID)