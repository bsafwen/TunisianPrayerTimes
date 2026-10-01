package com.tunisianprayertimes.platform

import android.content.Context
import android.util.Log
import com.tunisianprayertimes.*

/**
 * Prayer times computed on the device with INM's formula from the bundled
 * [InmPrayerTimes.PARAMS_ASSET], so every supported year works offline.
 */
actual object PrayerDataLoader {
    private const val TAG = "PrayerDataLoader"

    private var appContext: Context? = null

    @Volatile
    private var source: InmPrayerTimes? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** The shared formula source, parsed once from the bundled parameters. */
    fun prayerTimes(context: Context): InmPrayerTimes {
        source?.let { return it }
        return synchronized(this) {
            source ?: try {
                context.assets.open(InmPrayerTimes.PARAMS_ASSET).bufferedReader().use {
                    InmPrayerTimes.fromParamsJson(it.readText())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load ${InmPrayerTimes.PARAMS_ASSET}: ${e.message}")
                InmPrayerTimes(emptyMap())
            }.also { source = it }
        }
    }

    private fun prayerTimes(): InmPrayerTimes =
        prayerTimes(appContext ?: error("PrayerDataLoader not initialized. Call init(context) first."))

    actual fun hasPrayerData(delegationId: Int, year: Int, month: Int): Boolean =
        prayerTimes().hasPrayerData(delegationId, year, month)

    actual fun loadPrayerTimes(delegationId: Int, year: Int, month: Int): List<DayPrayerTimes> =
        prayerTimes().loadPrayerTimes(delegationId, year, month)

    actual fun loadDayPrayerTimes(delegationId: Int, year: Int, month: Int, day: Int): DayPrayerTimes? =
        prayerTimes().loadDayPrayerTimes(delegationId, year, month, day)
}
