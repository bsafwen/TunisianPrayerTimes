package com.tunisianprayertimes.tv.data

import com.tunisianprayertimes.mosque.IqamahRule
import com.tunisianprayertimes.mosque.PrayerSettings
import java.time.LocalTime

/** How a prayer's iqamah time is derived. */
enum class IqamahMode { DELAY, FIXED_TIME }

/**
 * Per-prayer setting edited on the TV: the iqamah (minutes after the adhan, or a fixed clock
 * time) and [salahMinutes], how long the screen stays black while the congregation prays.
 * A fixed time with a negative hour or minute is unset.
 */
data class IqamahConfig(
    val mode: IqamahMode = IqamahMode.DELAY,
    val delayMinutes: Int = 10,
    val fixedHour: Int = -1,
    val fixedMinute: Int = -1,
    val salahMinutes: Int = 10,
) {
    fun toPrayerSettings(): PrayerSettings = PrayerSettings(
        iqamah = if (mode == IqamahMode.FIXED_TIME && fixedHour in 0..23 && fixedMinute in 0..59) {
            IqamahRule.FixedTime(LocalTime.of(fixedHour, fixedMinute))
        } else {
            IqamahRule.AfterAdhan(delayMinutes)
        },
        salahMinutes = salahMinutes,
    )

    companion object {
        fun from(settings: PrayerSettings): IqamahConfig = when (val rule = settings.iqamah) {
            is IqamahRule.AfterAdhan -> IqamahConfig(IqamahMode.DELAY, delayMinutes = rule.minutes, salahMinutes = settings.salahMinutes)
            is IqamahRule.FixedTime -> IqamahConfig(
                IqamahMode.FIXED_TIME, fixedHour = rule.time.hour, fixedMinute = rule.time.minute, salahMinutes = settings.salahMinutes,
            )
        }
    }
}
