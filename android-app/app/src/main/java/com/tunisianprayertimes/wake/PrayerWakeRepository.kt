package com.tunisianprayertimes.wake

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerWakeConfig
import com.tunisianprayertimes.PrayerWakeStore
import com.tunisianprayertimes.isRepeatingWakeAlarm
import com.tunisianprayertimes.supportsWakeAlarm
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class PrayerWakeRepository(private val context: Context) {

    val wakeStore: Flow<PrayerWakeStore> = context.prayerWakeDataStore.data
        .map { preferences -> decodePrayerWakeStore(preferences[prayerWakeStoreKey]) }
        .distinctUntilChanged()

    val wakeAlarms: Flow<List<PrayerWakeConfig>> = wakeStore
        .map { store -> store.alarms }
        .distinctUntilChanged()

    val wakeConfigs: Flow<Map<Prayer, PrayerWakeConfig>> = wakeStore
        .map { store -> store.configs }
        .distinctUntilChanged()

    suspend fun getCurrentStore(): PrayerWakeStore = wakeStore.first()

    suspend fun getWakeConfig(prayer: Prayer): PrayerWakeConfig? {
        require(prayer.supportsWakeAlarm()) {
            "Wake alarms are only supported for supported prayer rows."
        }
        return wakeStore.first().configFor(prayer)
    }

    suspend fun getWakeAlarm(id: String): PrayerWakeConfig? {
        require(id.isNotBlank()) { "Wake alarm id must not be blank." }
        return wakeStore.first().alarmFor(id)
    }

    suspend fun setWakeAlarmSkipOccurrence(id: String, occurrenceAtMillis: Long?): PrayerWakeConfig? {
        require(id.isNotBlank()) { "Wake alarm id must not be blank." }
        var updatedConfig: PrayerWakeConfig? = null
        context.prayerWakeDataStore.edit { preferences ->
            val current = decodePrayerWakeStore(preferences[prayerWakeStoreKey])
            val existing = current.alarmFor(id) ?: return@edit
            if (occurrenceAtMillis != null && !existing.isRepeatingWakeAlarm()) return@edit
            val replacement = existing.copy(skipNextOccurrenceAtMillis = occurrenceAtMillis)
            updatedConfig = replacement
            val updated = current.alarms.map { config ->
                if (config.id == id) replacement else config
            }
            preferences[prayerWakeStoreKey] = encodePrayerWakeStore(PrayerWakeStore(alarms = updated))
        }
        return updatedConfig
    }

    suspend fun saveWakeConfig(config: PrayerWakeConfig) {
        require(config.prayer.supportsWakeAlarm()) {
            "Wake alarms are only supported for supported prayer rows."
        }

        var awakeCheckEventIdsToCancel = emptySet<String>()
        var skippedAlarmIdToClear: String? = null
        var scheduledMetadataAlarmIdToClear: String? = null
        var awakeCheckGroupAlarmIdToClear: String? = null
        context.prayerWakeDataStore.edit { preferences ->
            val current = decodePrayerWakeStore(preferences[prayerWakeStoreKey])
            val resolvedId = config.id.takeIf { id -> id.isNotBlank() }
                ?: current.alarms.firstOrNull { existing -> existing.prayer == config.prayer }?.id
                ?: generatedAlarmId(config.prayer, current.alarms.size)

            val existing = current.alarmFor(resolvedId)
            val scheduleChanged = existing != null && (
                existing.prayer != config.prayer ||
                    existing.mainAlarm != config.mainAlarm ||
                    existing.repeatMode != config.repeatMode ||
                    existing.scheduledDays != config.scheduledDays ||
                    existing.subAlarms != config.subAlarms
                )
            val updatedConfig = config.copy(
                id = resolvedId,
                skipNextOccurrenceAtMillis = if (scheduleChanged) null else config.skipNextOccurrenceAtMillis,
            )
            if (existing != null && existing != updatedConfig) {
                scheduledMetadataAlarmIdToClear = resolvedId
            }
            if (scheduleChanged || !updatedConfig.enabled) {
                awakeCheckGroupAlarmIdToClear = resolvedId
            }
            if (scheduleChanged || !updatedConfig.enabled ||
                (existing?.skipNextOccurrenceAtMillis != null && updatedConfig.skipNextOccurrenceAtMillis == null)
            ) {
                skippedAlarmIdToClear = resolvedId
            }
            if (!updatedConfig.enabled) {
                awakeCheckEventIdsToCancel = buildSet {
                    add(wakeMainEventId(resolvedId))
                    val subAlarms = current.alarmFor(resolvedId)?.subAlarms.orEmpty() + updatedConfig.subAlarms
                    subAlarms.forEach { subAlarm -> add(wakeSubAlarmEventId(resolvedId, subAlarm.id)) }
                }
            }
            val updated = current.alarms.replaceOrAppend(updatedConfig)
            preferences[prayerWakeStoreKey] = encodePrayerWakeStore(PrayerWakeStore(alarms = updated))
        }
        // Awake checks use separate PendingIntents from the regular wake alarm schedule.
        skippedAlarmIdToClear?.let(WakeOccurrenceSkipRegistry::clearAlarm)
        scheduledMetadataAlarmIdToClear?.let { alarmId ->
            WakeAlarmScheduler.invalidateScheduledTriggerMetadata(context, alarmId)
        }
        awakeCheckGroupAlarmIdToClear?.let { alarmId -> AwakeCheckGroupTracker.clearAlarm(context, alarmId) }
        awakeCheckEventIdsToCancel.forEach { eventId -> AwakeCheckScheduler.cancel(context, eventId) }
    }

    suspend fun replaceWakeConfigs(configs: Collection<PrayerWakeConfig>) {
        val updated = configs.mapIndexed { index, config ->
            require(config.prayer.supportsWakeAlarm()) {
                "Wake alarms are only supported for supported prayer rows."
            }
            config.copy(
                id = config.id.takeIf { id -> id.isNotBlank() }
                    ?: generatedAlarmId(config.prayer, index),
            )
        }
            .distinctBy { config -> config.id }

        context.prayerWakeDataStore.edit { preferences ->
            preferences[prayerWakeStoreKey] = encodePrayerWakeStore(PrayerWakeStore(alarms = updated))
        }
    }

    suspend fun clearWakeConfig(prayer: Prayer) {
        require(prayer.supportsWakeAlarm()) {
            "Wake alarms are only supported for supported prayer rows."
        }

        context.prayerWakeDataStore.edit { preferences ->
            val current = decodePrayerWakeStore(preferences[prayerWakeStoreKey])
            val updated = current.alarms.filterNot { config -> config.prayer == prayer }
            if (updated.isEmpty()) {
                preferences.remove(prayerWakeStoreKey)
            } else {
                preferences[prayerWakeStoreKey] = encodePrayerWakeStore(PrayerWakeStore(alarms = updated))
            }
        }
    }

    suspend fun deleteWakeAlarm(id: String) {
        require(id.isNotBlank()) { "Wake alarm id must not be blank." }

        context.prayerWakeDataStore.edit { preferences ->
            val current = decodePrayerWakeStore(preferences[prayerWakeStoreKey])
            val updated = current.alarms.filterNot { config -> config.id == id }
            if (updated.isEmpty()) {
                preferences.remove(prayerWakeStoreKey)
            } else {
                preferences[prayerWakeStoreKey] = encodePrayerWakeStore(PrayerWakeStore(alarms = updated))
            }
        }
        WakeAlarmScheduler.removeSilenceUntilAlarm(context, id)
        WakeOccurrenceSkipRegistry.clearAlarm(id)
        WakeAlarmScheduler.invalidateScheduledTriggerMetadata(context, id)
        AwakeCheckGroupTracker.clearAlarm(context, id)
    }

    suspend fun clearAllWakeConfigs() {
        context.prayerWakeDataStore.edit { preferences ->
            preferences.remove(prayerWakeStoreKey)
        }
    }

    private fun generatedAlarmId(prayer: Prayer, index: Int): String =
        "${prayer.name.lowercase()}_${index + 1}"

    private fun List<PrayerWakeConfig>.replaceOrAppend(config: PrayerWakeConfig): List<PrayerWakeConfig> {
        val existingIndex = indexOfFirst { existing -> existing.id == config.id }
        if (existingIndex < 0) {
            return this + config
        }

        return toMutableList().apply {
            set(existingIndex, config)
        }
    }
}
