package com.tunisianprayertimes.tv.update

import android.content.Context
import com.tunisianprayertimes.tv.kiosk.EventLog

/** The Play build: no update code at all, as Google Play requires. */
object Updates {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context, log: EventLog): AppUpdater = PlayUpdates
}
