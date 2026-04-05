package com.tunisianprayertimes.platform

import com.tunisianprayertimes.*

/**
 * Platform abstraction for loading prayer times from bundled CSV data.
 */
expect object PrayerDataLoader {
    fun hasPrayerData(delegationId: Int, year: Int, month: Int): Boolean
    fun loadPrayerTimes(delegationId: Int, year: Int, month: Int): List<DayPrayerTimes>
    fun loadDayPrayerTimes(delegationId: Int, year: Int, month: Int, day: Int): DayPrayerTimes?
}
