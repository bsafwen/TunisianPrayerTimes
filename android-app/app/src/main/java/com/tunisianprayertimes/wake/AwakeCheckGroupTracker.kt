package com.tunisianprayertimes.wake

import android.content.Context
import java.util.concurrent.TimeUnit

/** Wait for every extra tied at the latest time before starting the shared awake check. */
internal object AwakeCheckGroupTracker {
    private const val PREFS = "awake_check_group_dismissals"
    private const val COMPLETED = "#completed"
    private val RETAIN_FOR_MILLIS = TimeUnit.DAYS.toMillis(8)

    @Synchronized
    fun isFinalDismissal(context: Context, payload: WakeTriggerPayload): Boolean {
        if (payload.awakeCheckGroupSize <= 1) return true
        val alarmId = wakeAlarmIdFromEventId(payload.eventId) ?: return true
        val key = "$alarmId:${payload.resolvedOccurrenceAtMillis()}"
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dismissed = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        if (COMPLETED in dismissed) return false
        dismissed += payload.eventId
        val complete = dismissed.size >= payload.awakeCheckGroupSize
        if (complete) dismissed += COMPLETED
        val cutoff = System.currentTimeMillis() - RETAIN_FOR_MILLIS
        val editor = prefs.edit().putStringSet(key, dismissed)
        prefs.all.keys
            .filter { oldKey -> oldKey != key && oldKey.substringAfterLast(':').toLongOrNull()?.let { it < cutoff } == true }
            .forEach(editor::remove)
        editor.commit()
        return complete
    }

    @Synchronized
    fun clearAlarm(context: Context, alarmId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        prefs.all.keys.filter { key -> key.startsWith("$alarmId:") }.forEach(editor::remove)
        editor.apply()
    }
}
