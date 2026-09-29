package com.tunisianprayertimes.tv.data

/** How a prayer's iqamah time is derived. */
enum class IqamahMode { DELAY, FIXED_TIME }

/**
 * Per-prayer iqamah setting: minutes after the adhan, or a fixed clock time.
 * A fixed time with a negative hour or minute is unset.
 */
data class IqamahConfig(
    val mode: IqamahMode = IqamahMode.DELAY,
    val delayMinutes: Int = 10,
    val fixedHour: Int = -1,
    val fixedMinute: Int = -1,
)
