package com.tunisianprayertimes.platform

import android.content.Context
import com.tunisianprayertimes.OfficialIslamicDates
import com.tunisianprayertimes.RamadanOverrideChecker

/**
 * Starts the shared module on Android. The phone and TV apps both call it first in
 * Application.onCreate, so they read the same offline data in the same order.
 */
object SharedAndroid {

    /** Asset folder bundled from data/official-islamic-dates by the tunisianprayertimes.bundled-data plugin. */
    private const val OFFICIAL_DATES = "official-islamic-dates"

    fun init(context: Context) {
        val app = context.applicationContext
        Preferences.init(app)
        // Migrate before any packaged publication can replace the released app's legacy record.
        RamadanOverrideChecker.loadCachedOverrideIfNeeded()
        // The same announcements are available to calendars, boot receivers and schedules
        // even on a first launch without a connection.
        app.assets.list(OFFICIAL_DATES).orEmpty().filter { it.endsWith(".json") }.forEach { name ->
            runCatching {
                app.assets.open("$OFFICIAL_DATES/$name").bufferedReader().use { reader ->
                    OfficialIslamicDates.importJson(reader.readText())
                }
            }
        }
        PrayerDataLoader.init(app)
        GouvernoratLoader.init(app)
    }
}
