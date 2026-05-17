package com.tunisianprayertimes.wake

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tunisianprayertimes.OffsetDirection
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerWakeConfig
import com.tunisianprayertimes.PrayerWakeSubAlarm
import com.tunisianprayertimes.PrayerWakeStore
import com.tunisianprayertimes.WAKE_SUPPORTED_PRAYERS
import com.tunisianprayertimes.WakeMainAlarmConfig
import com.tunisianprayertimes.WakePlaybackOptions
import com.tunisianprayertimes.supportsWakeAlarm
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
        val element = prayerWakeJson.parseToJsonElement(encoded)
        val keys = element.jsonObject.keys

        when {
            "alarms" in keys -> prayerWakeJson.decodeFromJsonElement<PrayerWakeStore>(element).normalized()
            "configs" in keys -> decodeLegacyPrayerWakeStore(element).normalized()
            else -> PrayerWakeStore()
        }
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

private fun decodeLegacyPrayerWakeStore(element: JsonElement): PrayerWakeStore {
    val configs = runCatching { element.jsonObject["configs"]?.jsonObject }
        .getOrNull()
        ?: return PrayerWakeStore()

    val decodedConfigs = configs.entries
        .mapNotNull { (prayerName, configElement) ->
            val prayer = runCatching { Prayer.valueOf(prayerName) }.getOrNull()
                ?: return@mapNotNull null
            val config = runCatching { decodeLegacyPrayerWakeConfig(configElement) }.getOrNull()
                ?: return@mapNotNull null

            prayer to config
        }
        .filter { (prayer, _) -> prayer.supportsWakeAlarm() }
        .sortedBy { (prayer, _) ->
            WAKE_SUPPORTED_PRAYERS.indexOf(prayer).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE
        }

    return PrayerWakeStore(
        alarms = decodedConfigs.mapIndexed { index, (prayer, config) ->
            PrayerWakeConfig(
                id = generatedAlarmId(prayer, index),
                prayer = prayer,
                enabled = config.enabled,
                mainAlarm = config.mainAlarm,
                playback = config.playback,
                subAlarms = config.subAlarms,
            )
        },
    )
}

private data class LegacyPrayerWakeConfigValues(
    val enabled: Boolean = false,
    val mainAlarm: WakeMainAlarmConfig = WakeMainAlarmConfig(),
    val playback: WakePlaybackOptions = WakePlaybackOptions(),
    val subAlarms: List<PrayerWakeSubAlarm> = emptyList(),
)

private fun decodeLegacyPrayerWakeConfig(element: JsonElement): LegacyPrayerWakeConfigValues {
    val config = element.jsonObject
    val mainAlarm = config["mainAlarm"]?.let { alarmElement ->
        prayerWakeJson.decodeFromJsonElement<WakeMainAlarmConfig>(alarmElement)
    } ?: WakeMainAlarmConfig()
    val playback = config["playback"]?.let { playbackElement ->
        prayerWakeJson.decodeFromJsonElement<WakePlaybackOptions>(playbackElement)
    } ?: WakePlaybackOptions()
    val subAlarms = config["subAlarms"]?.jsonArray
        ?.mapNotNull { subAlarmElement ->
            runCatching {
                prayerWakeJson.decodeFromJsonElement<PrayerWakeSubAlarm>(subAlarmElement)
            }.getOrNull()
        }
        .orEmpty()

    return LegacyPrayerWakeConfigValues(
        enabled = config["enabled"]?.jsonPrimitive?.booleanOrNull ?: false,
        mainAlarm = mainAlarm,
        playback = playback,
        subAlarms = subAlarms,
    )
}