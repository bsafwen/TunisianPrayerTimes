package com.tunisianprayertimes.tv.data

import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.mosque.IqamahRule
import com.tunisianprayertimes.mosque.MosqueSchedule
import com.tunisianprayertimes.mosque.PrayerOverride
import com.tunisianprayertimes.mosque.PrayerSettings
import java.time.LocalTime

/** How a prayer's iqamah time is derived. */
enum class IqamahMode { DELAY, FIXED_TIME }

/**
 * Per-prayer setting edited on the TV: the iqamah (minutes after the adhan, or a fixed clock
 * time) and [salahMinutes], how long the screen stays black while the congregation prays.
 * A fixed time with a negative hour or minute is unset. [held] is false for a Jumu'a or an Eid
 * prayer the mosque does not hold; [khutbaMinutes] is Jumu'a's khutba length (0: the whole wait).
 */
data class IqamahConfig(
    val mode: IqamahMode = IqamahMode.DELAY,
    val delayMinutes: Int = 10,
    val fixedHour: Int = -1,
    val fixedMinute: Int = -1,
    val salahMinutes: Int = 10,
    val held: Boolean = true,
    val khutbaMinutes: Int = 0,
) {
    fun toPrayerSettings(): PrayerSettings = PrayerSettings(
        iqamah = if (mode == IqamahMode.FIXED_TIME && fixedHour in 0..23 && fixedMinute in 0..59) {
            IqamahRule.FixedTime(LocalTime.of(fixedHour, fixedMinute))
        } else {
            IqamahRule.AfterAdhan(delayMinutes)
        },
        salahMinutes = salahMinutes,
        held = held,
        khutbaMinutes = khutbaMinutes,
    )

    companion object {
        fun from(settings: PrayerSettings): IqamahConfig = when (val rule = settings.iqamah) {
            is IqamahRule.AfterAdhan -> IqamahConfig(
                IqamahMode.DELAY, delayMinutes = rule.minutes, salahMinutes = settings.salahMinutes, held = settings.held,
                khutbaMinutes = settings.khutbaMinutes,
            )
            is IqamahRule.FixedTime -> IqamahConfig(
                IqamahMode.FIXED_TIME, fixedHour = rule.time.hour, fixedMinute = rule.time.minute, salahMinutes = settings.salahMinutes,
                held = settings.held, khutbaMinutes = settings.khutbaMinutes,
            )
        }

        /**
         * The schedule the prayer flow runs on: every prayer's settings, Ramadan's changes, and the
         * minutes after the adhan kept behind a fixed time, which a stale fixed time falls back to.
         */
        fun schedule(configs: Map<Prayer, IqamahConfig>, ramadan: Map<Prayer, PrayerOverride>): MosqueSchedule =
            MosqueSchedule(configs.mapValues { it.value.toPrayerSettings() }, ramadan, configs.mapValues { it.value.delayMinutes })
    }
}
