package com.tunisianprayertimes.tv.update

import android.content.Context
import com.tunisianprayertimes.tv.kiosk.EventLog

/** The GitHub build: updates itself from the TV releases on GitHub. */
object Updates {
    fun create(context: Context, log: EventLog): AppUpdater = GithubUpdater(context, log)
}
