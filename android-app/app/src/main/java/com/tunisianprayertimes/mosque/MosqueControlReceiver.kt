package com.tunisianprayertimes.mosque

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.GeofencingEvent
import com.tunisianprayertimes.SilenceScheduler

/** Private PendingIntent receiver. A circle transition is a request to check, never polygon evidence. */
class MosqueControlReceiver : BroadcastReceiver() {
    companion object {
        const val DEADLINE = "com.tunisianprayertimes.mosque.DEADLINE"
        const val GEOFENCE = "com.tunisianprayertimes.mosque.GEOFENCE"
        const val SKIP = "com.tunisianprayertimes.mosque.SKIP"
    }
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SKIP -> MosqueSilenceMonitor.skip(context)
            DEADLINE -> MosqueSilenceMonitor.reconcile(context)
            GEOFENCE -> {
                val token = intent.data?.lastPathSegment ?: return
                if (!MosqueObservationService.validGeofenceToken(context, token)) return
                if (!MosqueSilenceMonitor.hasWindow(context)) { MosqueSilenceMonitor.reconcile(context); return }
                val event = GeofencingEvent.fromIntent(intent) ?: return
                if (event.hasError()) { MosqueSilenceMonitor.environmentChanged(context); return }
                // ENTER, EXIT and DWELL all request fresh, window-gated evidence. EXIT never clears DND.
                MosqueSilenceMonitor.reconcile(context, canStart = true)
            }
        }
    }
}
