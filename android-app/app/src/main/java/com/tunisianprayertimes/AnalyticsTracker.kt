package com.tunisianprayertimes

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.tunisianprayertimes.wake.PrayerWakeRepository
import com.tunisianprayertimes.wake.WakeTriggerPayload

object AnalyticsTracker {
    private const val PREFS_NAME = "privacy_safe_analytics"
    private const val KEY_ACTIVATION_LOGGED = "activation_logged"
    private const val KEY_DELEGATION_SOURCE = "delegation_source"
    private const val DELEGATION_SOURCE_DEFAULT = "default"

    fun installRamadanOverrideReporter(context: Context) {
        val appContext = context.applicationContext
        RamadanOverrideChecker.analyticsReporter = { report ->
            ramadanOverrideFetchResult(appContext, report)
        }
    }

    fun markDelegationSource(context: Context, source: String) {
        prefs(context).edit().putString(KEY_DELEGATION_SOURCE, source).apply()
    }

    fun activationCompletedIfNeeded(
        context: Context,
        dndGranted: Boolean,
        exactAlarmGranted: Boolean,
        batteryExempt: Boolean,
    ) {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_ACTIVATION_LOGGED, false)) return
        if (!dndGranted || !exactAlarmGranted || !batteryExempt) return

        log(
            context,
            "activation_completed",
            Bundle().apply {
                putFlag("dnd_granted", dndGranted)
                putFlag("exact_alarm_granted", exactAlarmGranted)
                putFlag("battery_exempt", batteryExempt)
                putString("delegation_source", prefs.getString(KEY_DELEGATION_SOURCE, DELEGATION_SOURCE_DEFAULT))
            },
        )
        prefs.edit().putBoolean(KEY_ACTIVATION_LOGGED, true).apply()
    }

    fun permissionStepResult(
        context: Context,
        permissionType: String,
        result: String,
        entryPoint: String,
    ) {
        log(
            context,
            "permission_step_result",
            Bundle().apply {
                putString("permission_type", permissionType)
                putString("result", result)
                putString("entry_point", entryPoint)
            },
        )
    }

    fun tabViewed(context: Context, tab: String) {
        if (tab.isBlank()) return
        log(
            context,
            "tab_viewed",
            Bundle().apply { putString("tab", tab) },
        )
    }

    fun autoSilenceStateChanged(context: Context, enabled: Boolean) {
        log(
            context,
            "auto_silence_state_changed",
            Bundle().apply {
                putFlag("enabled", enabled)
                putString("active_prayer_count_bucket", countBucket(silencePrayerCount(context)))
            },
        )
    }

    fun silenceConfigChanged(
        context: Context,
        prayer: Prayer,
        mode: SilenceMode,
        durationMinutes: Int,
        delayMode: DelayMode,
    ) {
        log(
            context,
            "silence_config_changed",
            Bundle().apply {
                putString("prayer", prayer.analyticsName())
                putString("mode", mode.name.lowercase())
                putString("duration_bucket", durationBucket(durationMinutes))
                putString("delay_mode", delayMode.name.lowercase())
            },
        )
    }

    fun manualSilenceStarted(
        context: Context,
        mode: ManualSilenceMode,
        durationMinutes: Int?,
    ) {
        log(
            context,
            "manual_silence_started",
            Bundle().apply {
                putString("mode", mode.name.lowercase())
                durationMinutes?.let { putString("duration_bucket", durationBucket(it)) }
            },
        )
    }

    fun phoneSilenced(
        context: Context,
        source: String,
        prayer: Prayer? = null,
    ) {
        log(
            context,
            "phone_silenced",
            Bundle().apply {
                putString("source", source)
                prayer?.let { putString("prayer", it.analyticsName()) }
            },
        )
    }

    fun wakeAlarmSaved(context: Context, config: PrayerWakeConfig) {
        log(
            context,
            "wake_alarm_saved",
            Bundle().apply {
                putString("prayer", config.prayer.analyticsName())
                putFlag("enabled", config.enabled)
                putString("main_mode", config.mainAlarm.mode.name.lowercase())
                putString("subalarm_count_bucket", countBucket(config.subAlarms.size))
                putFlag("has_wakeup_check", config.playback.wakeUpCheckEnabled)
                putFlag("progressive_volume", config.playback.progressiveVolume)
                putFlag("silence_until_alarm", config.silenceUntilAlarm)
                putFlag("vibration_only", config.playback.vibrationOnly)
                putFlag("awake_check", config.playback.awakeCheckEnabled)
            },
        )
    }

    fun wakeAlarmFired(context: Context, payload: WakeTriggerPayload) {
        persistWakeFireStartedAt(context, payload.eventId, System.currentTimeMillis())
        log(
            context,
            "wake_alarm_fired",
            Bundle().apply {
                putString("prayer", payload.prayer.analyticsName())
                putString("trigger_type", if (payload.isSubAlarm) "extra" else "main")
                putString("playback_type", playbackType(payload))
                putString("wakeup_check_type", wakeupCheckType(payload))
                putFlag("progressive_volume", payload.progressiveVolume)
            },
        )
    }

    fun wakeAlarmDismissed(
        context: Context,
        payload: WakeTriggerPayload,
        stopSource: String,
        wakeupCheckCompleted: Boolean,
    ) {
        val startedAtMillis = consumeWakeFireStartedAt(context, payload.eventId)
        val durationBucket = startedAtMillis?.let { start ->
            wakeDismissDurationBucket(System.currentTimeMillis() - start)
        } ?: "unknown"
        log(
            context,
            "wake_alarm_dismissed",
            Bundle().apply {
                putString("prayer", payload.prayer.analyticsName())
                putString("trigger_type", if (payload.isSubAlarm) "extra" else "main")
                putString("dismiss_duration_bucket", durationBucket)
                putFlag("wakeup_check_completed", wakeupCheckCompleted)
                putString("stop_source", stopSource)
            },
        )
    }

    fun scheduleRefreshResult(
        context: Context,
        source: String,
        result: String,
        silencePrayerCount: Int,
        wakeAlarmCount: Int,
    ) {
        log(
            context,
            "schedule_refresh_result",
            Bundle().apply {
                putString("source", source)
                putString("result", result)
                putString("silence_alarm_count_bucket", countBucket(silencePrayerCount))
                putString("wake_alarm_count_bucket", countBucket(wakeAlarmCount))
            },
        )
    }

    fun ramadanOverrideFetchResult(context: Context, report: RamadanOverrideChecker.FetchReport) {
        log(
            context,
            "ramadan_override_fetch_result",
            Bundle().apply {
                putLong("hijri_year", report.hijriYear.toLong())
                putString("result", report.result)
                putFlag("has_ramadan_start", report.hasRamadanStart)
                putFlag("has_eid_fitr", report.hasEidFitr)
                putFlag("has_eid_adha", report.hasEidAdha)
                putFlag("cache_used", report.cacheUsed)
            },
        )
    }

    fun qiblaComputeResult(
        context: Context,
        source: String,
        result: String,
        hasSensorSupport: Boolean,
    ) {
        log(
            context,
            "qibla_compute_result",
            Bundle().apply {
                putString("source", source)
                putString("result", result)
                putFlag("has_sensor_support", hasSensorSupport)
            },
        )
    }

    fun silencePrayerCount(context: Context): Int =
        if (PrefsManager.isEnabled(context)) Prayer.values().size else 0

    suspend fun enabledWakeAlarmCount(context: Context): Int =
        runCatching {
            PrayerWakeRepository(context.applicationContext)
                .getCurrentStore()
                .alarms
                .count { config -> config.enabled }
        }.getOrDefault(0)

    private fun playbackType(payload: WakeTriggerPayload): String = when {
        payload.vibrationOnly -> "vibration_only"
        payload.ringtone == RingtonePreset.CUSTOM -> "custom"
        payload.ringtone in setOf(
            RingtonePreset.DEFAULT_ALARM,
            RingtonePreset.DEFAULT_NOTIFICATION,
            RingtonePreset.DEFAULT_RINGTONE,
        ) -> "system"
        else -> "bundled"
    }

    private fun wakeupCheckType(payload: WakeTriggerPayload): String =
        if (payload.wakeUpCheckEnabled) payload.wakeUpCheckType.name.lowercase() else "none"

    private fun durationBucket(minutes: Int): String = when {
        minutes <= 0 -> "0"
        minutes <= 15 -> "1_15"
        minutes <= 30 -> "16_30"
        minutes <= 60 -> "31_60"
        minutes <= 90 -> "61_90"
        minutes <= 120 -> "91_120"
        else -> "over_120"
    }

    private fun wakeDismissDurationBucket(durationMillis: Long): String {
        val seconds = (durationMillis / 1_000L).coerceAtLeast(0L)
        return when {
            seconds <= 10 -> "0_10s"
            seconds <= 30 -> "11_30s"
            seconds <= 60 -> "31_60s"
            seconds <= 180 -> "1_3m"
            seconds <= 300 -> "3_5m"
            else -> "over_5m"
        }
    }

    private fun countBucket(count: Int): String = when {
        count <= 0 -> "0"
        count == 1 -> "1"
        count <= 2 -> "2"
        count <= 5 -> "3_5"
        else -> "6_plus"
    }

    private fun Prayer.analyticsName(): String = name.lowercase()

    private fun Bundle.putFlag(key: String, value: Boolean) {
        putLong(key, if (value) 1L else 0L)
    }

    private fun log(context: Context, eventName: String, params: Bundle) {
        FirebaseAnalytics.getInstance(context.applicationContext).logEvent(eventName, params)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun persistWakeFireStartedAt(context: Context, eventId: String, startedAtMillis: Long) {
        prefs(context).edit().putLong(wakeFireStartedAtKey(eventId), startedAtMillis).apply()
    }

    private fun consumeWakeFireStartedAt(context: Context, eventId: String): Long? {
        val prefs = prefs(context)
        val key = wakeFireStartedAtKey(eventId)
        if (!prefs.contains(key)) return null
        val value = prefs.getLong(key, 0L).takeIf { it > 0L }
        prefs.edit().remove(key).apply()
        return value
    }

    private fun wakeFireStartedAtKey(eventId: String): String = "wake_fire_started_at_${eventId.hashCode()}"
}