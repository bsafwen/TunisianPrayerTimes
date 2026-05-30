package com.tunisianprayertimes

import java.util.Calendar
import kotlinx.serialization.Serializable
import kotlin.math.abs

val WAKE_SUPPORTED_PRAYERS: List<Prayer> = listOf(
    Prayer.FAJR,
    Prayer.DHUHR,
    Prayer.ASR,
    Prayer.MAGHRIB,
    Prayer.ISHA,
)

fun Prayer.supportsWakeAlarm(): Boolean = this in WAKE_SUPPORTED_PRAYERS

@Serializable
enum class WakeMainAlarmMode { FIXED_TIME, PRAYER_RELATIVE, FROM_NOW }

const val WAKE_RECURRING_LOOKAHEAD_DAYS: Int = 7

@Serializable
enum class WakeScheduleDay { MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY }

val ALL_WAKE_SCHEDULE_DAYS: Set<WakeScheduleDay> = setOf(
    WakeScheduleDay.MONDAY,
    WakeScheduleDay.TUESDAY,
    WakeScheduleDay.WEDNESDAY,
    WakeScheduleDay.THURSDAY,
    WakeScheduleDay.FRIDAY,
    WakeScheduleDay.SATURDAY,
    WakeScheduleDay.SUNDAY,
)

fun Collection<WakeScheduleDay>.normalizedWakeScheduleDays(): Set<WakeScheduleDay> {
    val orderedDays = ALL_WAKE_SCHEDULE_DAYS.filterTo(linkedSetOf()) { day -> day in this }
    return orderedDays.ifEmpty { ALL_WAKE_SCHEDULE_DAYS }
}

fun Calendar.wakeScheduleDay(): WakeScheduleDay = when (get(Calendar.DAY_OF_WEEK)) {
    Calendar.MONDAY -> WakeScheduleDay.MONDAY
    Calendar.TUESDAY -> WakeScheduleDay.TUESDAY
    Calendar.WEDNESDAY -> WakeScheduleDay.WEDNESDAY
    Calendar.THURSDAY -> WakeScheduleDay.THURSDAY
    Calendar.FRIDAY -> WakeScheduleDay.FRIDAY
    Calendar.SATURDAY -> WakeScheduleDay.SATURDAY
    else -> WakeScheduleDay.SUNDAY
}

@Serializable
data class ClockTime(
    val hour: Int = 0,
    val minute: Int = 0,
) {
    init {
        require(hour in 0..23) { "Hour must be between 0 and 23." }
        require(minute in 0..59) { "Minute must be between 0 and 59." }
    }
}

@Serializable
data class PrayerRelativeOffset(
    val minutes: Int = 0,
) {
    val direction: OffsetDirection
        get() = if (minutes < 0) OffsetDirection.BEFORE else OffsetDirection.AFTER

    val absoluteMinutes: Int
        get() = abs(minutes)
}

@Serializable
data class WakeMainAlarmConfig(
    val mode: WakeMainAlarmMode = WakeMainAlarmMode.PRAYER_RELATIVE,
    val fixedTime: ClockTime = ClockTime(),
    val prayerOffset: PrayerRelativeOffset = PrayerRelativeOffset(),
    val oneOffOffsetMinutes: Int = 5,
    val oneOffTriggerAtMillis: Long = 0L,
)

@Serializable
enum class OffsetDirection {
    BEFORE,
    AFTER,
}

@Serializable
enum class RingtonePreset {
    DEFAULT_ALARM,
    DEFAULT_NOTIFICATION,
    DEFAULT_RINGTONE,
    ADHAN_ISLAM_SOBHI,
    ADHAN_OMAR_HISHAM_AL_ARABI,
    ADHAN_ABDULLAH_AL_ZAILI,
    ADHAN_MADINAH_MARWAN_QASSAS,
    ADHAN_NASSER_AL_QATAMI,
    CLASSIC_BEEP,
    GENTLE_CHIME,
    RAPID_PULSE,
    RAIN_SHOWER,
    OCEAN_SURF,
    FOREST_BREEZE,
    CUSTOM,
}

@Serializable
enum class MathDifficulty { EASY, INTERMEDIATE, HARD }

@Serializable
enum class WakeUpCheckType { MATH, WHACK_A_MOLE, GYROSCOPE_MAZE }

const val DEFAULT_AWAKE_CHECK_DELAY_MINUTES: Int = 3

@Serializable
data class WakeUpCheckStep(
    val type: WakeUpCheckType = WakeUpCheckType.MATH,
    val difficulty: MathDifficulty = MathDifficulty.EASY,
)

@Serializable
data class WakePlaybackOptions(
    val vibrationOnly: Boolean = false,
    val wakeUpCheckEnabled: Boolean = false,
    val wakeUpCheckType: WakeUpCheckType = WakeUpCheckType.MATH,
    val mathDifficulty: MathDifficulty = MathDifficulty.EASY,
    val wakeUpCheckSteps: List<WakeUpCheckStep> = emptyList(),
    val progressiveVolume: Boolean = true,
    val snoreTrackingEnabled: Boolean = false,
    val awakeCheckEnabled: Boolean = true,
    val awakeCheckDelayMinutes: Int = DEFAULT_AWAKE_CHECK_DELAY_MINUTES,
    val ringtone: RingtonePreset = RingtonePreset.DEFAULT_ALARM,
    val customRingtoneUri: String? = null,
) {
    /** Returns the effective steps list, falling back to the legacy single type+difficulty. */
    val effectiveWakeUpCheckSteps: List<WakeUpCheckStep>
        get() = wakeUpCheckSteps.ifEmpty { listOf(WakeUpCheckStep(wakeUpCheckType, mathDifficulty)) }
}

@Serializable
data class PrayerWakeSubAlarm(
    val id: String,
    val minutesOffset: Int,
    val direction: OffsetDirection,
    val playback: WakePlaybackOptions = WakePlaybackOptions(
        ringtone = RingtonePreset.ADHAN_MADINAH_MARWAN_QASSAS,
    ),
) {
    init {
        require(minutesOffset > 0) { "Sub-alarm offset must be positive." }
    }

    val signedOffsetMinutes: Int
        get() = if (direction == OffsetDirection.BEFORE) -minutesOffset else minutesOffset
}

@Serializable
data class PrayerWakeConfig(
    val id: String = "",
    val title: String = "",
    val prayer: Prayer,
    val enabled: Boolean = false,
    val mainAlarm: WakeMainAlarmConfig = WakeMainAlarmConfig(),
    val scheduledDays: Set<WakeScheduleDay> = ALL_WAKE_SCHEDULE_DAYS,
    val playback: WakePlaybackOptions = WakePlaybackOptions(),
    val subAlarms: List<PrayerWakeSubAlarm> = emptyList(),
    val silenceUntilAlarm: Boolean = false,
    val ringDuringSilenceWindow: Boolean = false,
) {
    init {
        require(prayer.supportsWakeAlarm()) {
            "Wake alarms are only supported for $WAKE_SUPPORTED_PRAYERS."
        }
    }
}

@Serializable
data class PrayerWakeStore(
    val alarms: List<PrayerWakeConfig> = emptyList(),
) {
    val configs: Map<Prayer, PrayerWakeConfig>
        get() = alarms.associateBy { config -> config.prayer }

    fun configFor(prayer: Prayer): PrayerWakeConfig? =
        alarms.lastOrNull { config -> config.prayer == prayer }

    fun alarmFor(id: String): PrayerWakeConfig? =
        alarms.firstOrNull { config -> config.id == id }
}