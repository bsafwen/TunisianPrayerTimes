package com.tunisianprayertimes.wake

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tunisianprayertimes.OffsetDirection
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerWakeConfig
import com.tunisianprayertimes.PrayerWakeStore
import com.tunisianprayertimes.WAKE_SUPPORTED_PRAYERS
import com.tunisianprayertimes.WakeMainAlarmConfig
import com.tunisianprayertimes.WakeMainAlarmMode
import com.tunisianprayertimes.supportsWakeAlarm
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val Context.prayerWakeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "prayer_wake_store",
)

internal val prayerWakeStoreKey = stringPreferencesKey("prayer_wake_store_json")

internal val prayerWakeJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
}

internal fun decodePrayerWakeStore(encoded: String?): PrayerWakeStore {
    if (encoded.isNullOrBlank()) {
        return PrayerWakeStore()
    }

    return runCatching {
        prayerWakeJson.decodeFromString<PrayerWakeStore>(encoded).normalized()
    }.recoverCatching {
        prayerWakeJson.decodeFromString<LegacyPrayerWakeStore>(encoded).toCurrentStore().normalized()
    }.getOrDefault(PrayerWakeStore())
}

internal fun encodePrayerWakeStore(store: PrayerWakeStore): String =
    prayerWakeJson.encodeToString(store.normalized())

private fun PrayerWakeStore.normalized(): PrayerWakeStore = copy(
    alarms = alarms
        .filter { config -> config.prayer.supportsWakeAlarm() }
        .mapIndexed { index, config ->
            config.normalizedFor(
                id = config.id.trim().ifEmpty { generatedAlarmId(config.prayer, index) },
                prayer = config.prayer,
            )
        }
        .distinctBy { config -> config.id }
)

private fun PrayerWakeConfig.normalizedFor(
    id: String,
    prayer: Prayer,
): PrayerWakeConfig =
    copy(
        id = id,
        title = title.trim(),
        prayer = prayer,
        mainAlarm = mainAlarm.normalized(),
        playback = playback.normalized(),
        subAlarms = subAlarms
            .filter { it.minutesOffset > 0 }
            .distinctBy { it.id }
            .sortedBy { it.signedOffsetMinutes }
            .map { subAlarm ->
                subAlarm.copy(
                    playback = subAlarm.playback.normalized(),
                    direction = if (subAlarm.signedOffsetMinutes < 0) {
                        OffsetDirection.BEFORE
                    } else {
                        OffsetDirection.AFTER
                    },
                )
            },
    )

private fun com.tunisianprayertimes.WakePlaybackOptions.normalized(): com.tunisianprayertimes.WakePlaybackOptions =
    copy(customRingtoneUri = customRingtoneUri?.trim()?.takeIf { it.isNotEmpty() })

private fun WakeMainAlarmConfig.normalized(): WakeMainAlarmConfig = copy(
    oneOffOffsetMinutes = oneOffOffsetMinutes.coerceAtLeast(1),
    oneOffTriggerAtMillis = oneOffTriggerAtMillis.coerceAtLeast(0L),
)

private fun generatedAlarmId(prayer: Prayer, index: Int): String =
    "${prayer.name.lowercase()}_${index + 1}"

@Serializable
private data class LegacyPrayerWakeStore(
    val configs: Map<Prayer, LegacyPrayerWakeConfig> = emptyMap(),
)

@Serializable
private data class LegacyPrayerWakeConfig(
    val prayer: Prayer,
    val enabled: Boolean = false,
    val mainAlarm: LegacyWakeMainAlarmConfig = LegacyWakeMainAlarmConfig(),
    val playback: com.tunisianprayertimes.WakePlaybackOptions = com.tunisianprayertimes.WakePlaybackOptions(),
    val subAlarms: List<com.tunisianprayertimes.PrayerWakeSubAlarm> = emptyList(),
)

@Serializable
private data class LegacyWakeMainAlarmConfig(
    val mode: WakeMainAlarmMode = WakeMainAlarmMode.PRAYER_RELATIVE,
    val fixedTime: com.tunisianprayertimes.ClockTime = com.tunisianprayertimes.ClockTime(),
    val prayerOffset: com.tunisianprayertimes.PrayerRelativeOffset = com.tunisianprayertimes.PrayerRelativeOffset(),
)

private fun LegacyPrayerWakeStore.toCurrentStore(): PrayerWakeStore = PrayerWakeStore(
    alarms = configs.entries
        .filter { (prayer, _) -> prayer.supportsWakeAlarm() }
        .sortedBy { (prayer, _) ->
            WAKE_SUPPORTED_PRAYERS.indexOf(prayer).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE
        }
        .mapIndexed { index, (prayer, config) ->
            PrayerWakeConfig(
                id = generatedAlarmId(prayer, index),
                prayer = prayer,
                enabled = config.enabled,
                mainAlarm = WakeMainAlarmConfig(
                    mode = config.mainAlarm.mode,
                    fixedTime = config.mainAlarm.fixedTime,
                    prayerOffset = config.mainAlarm.prayerOffset,
                ),
                playback = config.playback,
                subAlarms = config.subAlarms,
            )
        },
)