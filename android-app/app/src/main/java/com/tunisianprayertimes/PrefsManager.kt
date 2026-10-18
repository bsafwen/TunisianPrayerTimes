package com.tunisianprayertimes

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.app.NotificationManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.tunisianprayertimes.method.CountryMethodDefaults
import com.tunisianprayertimes.method.PrayerMethodChoice
import com.tunisianprayertimes.place.CountryResolver
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.place.Region
import com.tunisianprayertimes.place.Regions
import com.tunisianprayertimes.place.ZoneResolver
import com.tunisianprayertimes.place.ZoneSource
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.json.Json
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField

enum class ManualSilenceMode { UNTIL_STOPPED, DURATION, UNTIL_PRAYER }

data class SavedLocationSelection(
    val delegationId: Int,
    val localityId: String?,
    val name: String?,
    val kind: String?,
    val fromGps: Boolean,
    val manualReferenceId: String? = null,
)

object PrefsManager {
    private const val PREFS_NAME = "prayer_silence_prefs"
    private const val KEY_DELEGATION_ID = "delegation_id"
    private const val KEY_LOCALITY_ID = "locality_id"
    private const val KEY_LOCALITY_NAME = "locality_name"
    private const val KEY_LOCALITY_KIND = "locality_kind"
    private const val KEY_LOCATION_FROM_GPS = "location_from_gps"
    private const val KEY_MANUAL_REFERENCE_ID = "manual_reference_id"
    private const val KEY_ENABLED = "silence_enabled"
    private const val KEY_FIRST_LAUNCH = "first_launch_done"
    private const val KEY_AUTO_SILENCE_ACTIVE = "auto_silence_active"
    private const val KEY_AUTO_SILENCE_PREVIOUS_RINGER_MODE = "auto_silence_previous_ringer_mode"
    private const val KEY_AUTO_SILENCE_PREVIOUS_INTERRUPTION_FILTER = "auto_silence_previous_interruption_filter"
    private const val KEY_MANUAL_SILENCE_ACTIVE = "manual_silence_active"
    private const val KEY_MANUAL_SILENCE_PREVIOUS_RINGER_MODE = "manual_silence_previous_ringer_mode"
    private const val KEY_MANUAL_SILENCE_PREVIOUS_INTERRUPTION_FILTER = "manual_silence_previous_interruption_filter"
    private const val KEY_MANUAL_SILENCE_MODE = "manual_silence_mode"
    private const val KEY_MANUAL_SILENCE_USES_DURATION = "manual_silence_uses_duration"
    private const val KEY_MANUAL_SILENCE_DURATION_MINUTES = "manual_silence_duration_minutes"
    private const val KEY_MANUAL_SILENCE_ENDS_AT_MILLIS = "manual_silence_ends_at_millis"
    private const val KEY_MANUAL_SILENCE_TARGET_PRAYER = "manual_silence_target_prayer"
    private const val KEY_CALL_RECEIVED_DURING_SILENCE = "call_received_during_silence"
    private const val KEY_CALL_END_VIBRATION_ENABLED = "call_end_vibration_enabled"
    private const val KEY_AUTO_LOCATION_UPDATE = "auto_location_update"
    private const val KEY_AUTO_SILENCE_DISMISSED_UNTIL_MILLIS = "auto_silence_dismissed_until_millis"
    private const val KEY_AUTO_SILENCE_DISMISSED_PRAYER = "auto_silence_dismissed_prayer"
    private const val KEY_QIBLA_METHOD = "qibla_method"
    private const val KEY_FAJR_ALARM_SUGGESTION_DISMISSED = "fajr_alarm_suggestion_dismissed"
    private const val DEFAULT_DELEGATION_ID = 615 // Tunis
    private const val DEFAULT_MANUAL_SILENCE_DURATION_MINUTES = 30

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getDelegationId(context: Context): Int {
        return prefs(context).getInt(KEY_DELEGATION_ID, DEFAULT_DELEGATION_ID)
    }

    fun setDelegationId(context: Context, id: Int) {
        val settings = prefs(context)
        // Choosing a delegation confirms the place (6.8): no «تأكيد الموقع» banner after it.
        val edit = settings.edit().putInt(KEY_DELEGATION_ID, id).removePlaceAbroad().remove(KEY_PLACE_PROVISIONAL)
        if (getDelegationId(context) != id) {
            edit.remove(KEY_LOCALITY_ID).remove(KEY_LOCALITY_NAME).remove(KEY_LOCALITY_KIND).remove(KEY_LOCATION_FROM_GPS)
                .remove(KEY_MANUAL_REFERENCE_ID)
        }
        edit.apply()
    }

    // --- The place (plan 6.8): a Tunisian delegation, or a point abroad ---

    private const val KEY_PLACE_KIND = "place_kind"
    private const val KEY_PLACE_LAT_E5 = "place_lat_e5"
    private const val KEY_PLACE_LNG_E5 = "place_lng_e5"
    private const val KEY_PLACE_ELEVATION_M = "place_elevation_m"
    private const val KEY_PLACE_ZONE_ID = "place_zone_id"
    private const val KEY_PLACE_ZONE_SOURCE = "place_zone_source"
    private const val KEY_PLACE_COUNTRY = "place_country"
    private const val KEY_PLACE_ADMIN1 = "place_admin1"
    private const val KEY_PLACE_ADMIN2 = "place_admin2"
    private const val KEY_PLACE_CATALOGUE_ID = "place_catalogue_id"
    private const val KEY_PLACE_NAME_SNAPSHOT = "place_name_snapshot"
    private const val KEY_PLACE_PROVISIONAL = "place_provisional"
    private const val PLACE_KIND_COORDINATES = "coordinates"
    private val PLACE_KEYS = listOf(
        KEY_PLACE_KIND, KEY_PLACE_LAT_E5, KEY_PLACE_LNG_E5, KEY_PLACE_ELEVATION_M, KEY_PLACE_ZONE_ID, KEY_PLACE_ZONE_SOURCE,
        KEY_PLACE_COUNTRY, KEY_PLACE_ADMIN1, KEY_PLACE_ADMIN2, KEY_PLACE_CATALOGUE_ID, KEY_PLACE_NAME_SNAPSHOT,
    )
    private const val KEY_REGION_ARAB_LEAGUE_OPEN = "region_arab_league_open"
    private const val KEY_REGION_EUROPE_OPEN = "region_europe_open"
    private const val KEY_ADHKAR_PLACE_FLAGS = "adhkar_place_flags"
    private const val KEY_PICK_PLACE_ON_START = "pick_place_on_start"
    private val choiceJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** A delegation is stored: the place abroad goes, so readers fall back to the delegation. */
    private fun SharedPreferences.Editor.removePlaceAbroad(): SharedPreferences.Editor {
        PLACE_KEYS.forEach { remove(it) }
        return this
    }

    /** The saved place: the delegation of [getDelegationId], or the point abroad that [setPlace] stored. */
    fun getPlace(context: Context): PrayerPlace {
        val p = prefs(context)
        if (p.getString(KEY_PLACE_KIND, null) != PLACE_KIND_COORDINATES || !p.contains(KEY_PLACE_LAT_E5) || !p.contains(KEY_PLACE_LNG_E5)) {
            return PrayerPlace.Inm(getDelegationId(context))
        }
        val zone = ZoneResolver.parse(p.getString(KEY_PLACE_ZONE_ID, null), ZoneId.systemDefault())
        return PrayerPlace.Coordinates(
            latE5 = p.getInt(KEY_PLACE_LAT_E5, 0),
            lngE5 = p.getInt(KEY_PLACE_LNG_E5, 0),
            elevationM = if (p.contains(KEY_PLACE_ELEVATION_M)) p.getInt(KEY_PLACE_ELEVATION_M, 0) else null,
            zoneId = zone.id,
            zoneSource = runCatching { ZoneSource.valueOf(p.getString(KEY_PLACE_ZONE_SOURCE, "")!!) }
                .getOrDefault(ZoneSource.DEVICE),
            countryCode = p.getString(KEY_PLACE_COUNTRY, null),
            catalogueId = if (p.contains(KEY_PLACE_CATALOGUE_ID)) p.getLong(KEY_PLACE_CATALOGUE_ID, 0L) else null,
            admin1 = p.getString(KEY_PLACE_ADMIN1, null),
            admin2 = p.getString(KEY_PLACE_ADMIN2, null),
        )
    }

    /** The label of a place abroad, as it was when stored («قرب X» or the picked city). */
    fun getPlaceNameSnapshot(context: Context): String? = prefs(context).getString(KEY_PLACE_NAME_SNAPSHOT, null)

    /** True while the place is the first run's guess, until the user confirms it or a fix arrives (6.8). */
    fun isPlaceProvisional(context: Context): Boolean = prefs(context).getBoolean(KEY_PLACE_PROVISIONAL, false)

    fun setPlaceProvisional(context: Context, provisional: Boolean) {
        prefs(context).edit().putBoolean(KEY_PLACE_PROVISIONAL, provisional).apply()
    }

    /** «اختيار مدينة» in the onboarding (6.8): the main screen opens the picker once, on its first start. */
    fun requestPlacePickerOnStart(context: Context) {
        prefs(context).edit().putBoolean(KEY_PICK_PLACE_ON_START, true).apply()
    }

    fun consumePlacePickerOnStart(context: Context): Boolean {
        val settings = prefs(context)
        if (!settings.getBoolean(KEY_PICK_PLACE_ON_START, false)) return false
        settings.edit().remove(KEY_PICK_PLACE_ON_START).apply()
        return true
    }

    /**
     * Stores a place abroad in one edit (6.8). The Tunisian locality keys go with it, so no stale Tunisian
     * label or source repair can act on a place abroad; the delegation id stays for INM's readers.
     */
    fun setPlace(
        context: Context,
        place: PrayerPlace.Coordinates,
        label: String?,
        fromGps: Boolean,
        provisional: Boolean = false,
    ) {
        val edit = prefs(context).edit()
            .putString(KEY_PLACE_KIND, PLACE_KIND_COORDINATES)
            .putInt(KEY_PLACE_LAT_E5, place.latE5)
            .putInt(KEY_PLACE_LNG_E5, place.lngE5)
            .putString(KEY_PLACE_ZONE_ID, place.zoneId)
            .putString(KEY_PLACE_ZONE_SOURCE, place.zoneSource.name)
            .putBoolean(KEY_LOCATION_FROM_GPS, fromGps)
            .putBoolean(KEY_PLACE_PROVISIONAL, provisional)
            .remove(KEY_LOCALITY_ID).remove(KEY_LOCALITY_NAME).remove(KEY_LOCALITY_KIND).remove(KEY_MANUAL_REFERENCE_ID)
        fun putOrRemove(key: String, value: String?) { if (value == null) edit.remove(key) else edit.putString(key, value) }
        putOrRemove(KEY_PLACE_COUNTRY, place.countryCode)
        putOrRemove(KEY_PLACE_ADMIN1, place.admin1)
        putOrRemove(KEY_PLACE_ADMIN2, place.admin2)
        putOrRemove(KEY_PLACE_NAME_SNAPSHOT, label)
        place.elevationM?.let { edit.putInt(KEY_PLACE_ELEVATION_M, it) } ?: edit.remove(KEY_PLACE_ELEVATION_M)
        place.catalogueId?.let { edit.putLong(KEY_PLACE_CATALOGUE_ID, it) } ?: edit.remove(KEY_PLACE_CATALOGUE_ID)
        edit.apply()
    }

    /** The method choice of a place's country (6.4), `method_choice_<CC>`; null until one is stored. */
    fun getMethodChoice(context: Context, place: PrayerPlace.Coordinates): PrayerMethodChoice? {
        val key = CountryMethodDefaults.choiceKey(place.countryCode, place.admin1)
        val stored = prefs(context).getString(key, null) ?: return null
        return runCatching { choiceJson.decodeFromString(PrayerMethodChoice.serializer(), stored) }.getOrNull()
    }

    fun setMethodChoice(
        context: Context,
        place: PrayerPlace.Coordinates,
        choice: PrayerMethodChoice,
    ) {
        val key = CountryMethodDefaults.choiceKey(place.countryCode, place.admin1)
        prefs(context).edit().putString(key, choiceJson.encodeToString(PrayerMethodChoice.serializer(), choice)).apply()
    }

    /**
     * The edited Jumu'a time of a place's country (6.9, A18): a mosque time, so stored per country, Tunisia
     * keeping today's keys. Null when not edited: the A18 default then applies.
     */
    fun getJumuaTime(context: Context, place: PrayerPlace): LocalTime? = when (place) {
        is PrayerPlace.Inm -> {
            val hour = getJomoaaTimeHour(context)
            val minute = getJomoaaTimeMinute(context)
            if (hour in 0..23 && minute in 0..59) LocalTime.of(hour, minute) else null
        }
        is PrayerPlace.Coordinates ->
            prefs(context).getString(jumuaKey(place), null)?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
    }

    /** Sets a fixed Jumu'a time, or with null the A18 default; either way Jumu'a no longer follows Asr. */
    fun setJumuaTime(context: Context, place: PrayerPlace, time: LocalTime?) {
        when (place) {
            is PrayerPlace.Inm -> setJomoaaTime(context, time?.hour ?: -1, time?.minute ?: -1)
            is PrayerPlace.Coordinates -> {
                val edit = prefs(context).edit()
                if (time == null) edit.remove(jumuaKey(place)) else edit.putString(jumuaKey(place), time.toString())
                edit.apply()
            }
        }
        prefs(context).edit().remove(jumuaBeforeAsrKey(place)).apply()
    }

    /**
     * Jumu'a held [minutes] before each Friday's Asr (about one Tunisian user in ten: their mosque prays an hour before
     * Asr), per country like the fixed time; null when Jumu'a has a fixed time or the default.
     */
    fun getJumuaMinutesBeforeAsr(context: Context, place: PrayerPlace): Int? =
        prefs(context).getInt(jumuaBeforeAsrKey(place), -1).takeIf { it >= 0 }

    /** Makes Jumu'a follow Asr, [minutes] before it, in place of a fixed time. */
    fun setJumuaMinutesBeforeAsr(context: Context, place: PrayerPlace, minutes: Int) {
        setJumuaTime(context, place, null)
        prefs(context).edit().putInt(jumuaBeforeAsrKey(place), minutes).apply()
    }

    private fun jumuaKey(place: PrayerPlace.Coordinates) =
        "jomoaa_time_" + (place.countryCode?.let(CountryResolver::normalize) ?: "ZZ")

    private fun jumuaBeforeAsrKey(place: PrayerPlace) = when (place) {
        is PrayerPlace.Inm -> "jomoaa_before_asr_minutes"
        is PrayerPlace.Coordinates -> "jomoaa_before_asr_" + (place.countryCode?.let(CountryResolver::normalize) ?: "ZZ")
    }

    /**
     * The developer switches of plan 2: each region stays closed until its milestones are done (the Arab League
     * at AM9, Europe, which also covers every other place outside Tunisia, at M9). Tunisia is always open. Every
     * milestone before the release is done, so a development build opens both by default (AM9, and M9's device
     * checks); a release build opens them once [REGIONS_OPEN_IN_RELEASE] is set for the release that ships both
     * regions (M9). The debug switches still close either one.
     */
    fun isRegionOpen(context: Context, region: Region): Boolean {
        val byDefault = REGIONS_OPEN_IN_RELEASE || (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return Regions.isOpen(
            region,
            arabLeagueOpen = prefs(context).getBoolean(KEY_REGION_ARAB_LEAGUE_OPEN, byDefault),
            europeOpen = prefs(context).getBoolean(KEY_REGION_EUROPE_OPEN, byDefault),
        )
    }

    /** M9 (plan 11): true in the release build that ships both regions. */
    val REGIONS_OPEN_IN_RELEASE = false

    fun setRegionOpen(context: Context, arabLeague: Boolean? = null, europe: Boolean? = null) {
        val edit = prefs(context).edit()
        arabLeague?.let { edit.putBoolean(KEY_REGION_ARAB_LEAGUE_OPEN, it) }
        europe?.let { edit.putBoolean(KEY_REGION_EUROPE_OPEN, it) }
        edit.apply()
    }

    fun getLocalityId(context: Context): String? = prefs(context).getString(KEY_LOCALITY_ID, null)
        ?.let { id ->
            LocalityRepository.reviewedReplacement(context, id)?.replacementId
                ?: id.takeUnless { LocalityRepository.isRetired(context, it) }
        }

    fun setLocality(context: Context, locality: Locality) {
        prefs(context).edit()
            .removePlaceAbroad()
            .remove(KEY_PLACE_PROVISIONAL)
            .putInt(KEY_DELEGATION_ID, locality.delegationId)
            .putString(KEY_LOCALITY_ID, locality.id)
            .putString(KEY_LOCALITY_NAME, locality.name)
            .putString(KEY_LOCALITY_KIND, locality.kind)
            .putBoolean(KEY_LOCATION_FROM_GPS, false)
            .putString(KEY_MANUAL_REFERENCE_ID, locality.manualReferenceId)
            .apply()
    }

    fun clearLocality(context: Context) {
        prefs(context).edit().remove(KEY_LOCALITY_ID).remove(KEY_LOCALITY_NAME)
            .remove(KEY_LOCALITY_KIND).remove(KEY_LOCATION_FROM_GPS).remove(KEY_MANUAL_REFERENCE_ID).apply()
    }

    /** The label and the timetable are independent results of the same GPS fix. */
    fun setGpsLocation(context: Context, result: DelegationLocationResult.Success) {
        prefs(context).edit()
            .removePlaceAbroad()
            .remove(KEY_PLACE_PROVISIONAL)
            .putInt(KEY_DELEGATION_ID, result.delegation.id)
            .putString(KEY_LOCALITY_ID, result.locality?.id)
            .putString(KEY_LOCALITY_NAME, result.locality?.name)
            .putString(KEY_LOCALITY_KIND, result.locality?.kind)
            .putBoolean(KEY_LOCATION_FROM_GPS, true)
            .remove(KEY_MANUAL_REFERENCE_ID)
            .apply()
    }

    fun getLocationSelection(context: Context): SavedLocationSelection {
        val values = prefs(context).all
        val selection = SavedLocationSelection(
            delegationId = values[KEY_DELEGATION_ID] as? Int ?: DEFAULT_DELEGATION_ID,
            localityId = values[KEY_LOCALITY_ID] as? String,
            name = values[KEY_LOCALITY_NAME] as? String,
            kind = values[KEY_LOCALITY_KIND] as? String,
            fromGps = values[KEY_LOCATION_FROM_GPS] as? Boolean ?: false,
            manualReferenceId = if (values[KEY_LOCATION_FROM_GPS] == true) null else values[KEY_MANUAL_REFERENCE_ID] as? String,
        )
        val localityId = selection.localityId ?: return selection
        // Apply only reviewed stable-ID label changes. GPS selections keep the
        // timetable chosen from their actual fix; no representative point is used.
        // Missing or damaged update metadata preserves the saved selection.
        val replacement = LocalityRepository.reviewedReplacement(context, localityId)
        val resolved = if (replacement != null) {
            selection.copy(
                localityId = replacement.replacementId,
                name = replacement.name,
                kind = replacement.kind,
            )
        } else if (LocalityRepository.isRetired(context, localityId)) {
            selection.copy(localityId = null, name = null, kind = null)
        } else {
            (LocalityRepository.reviewedName(context, localityId)?.let { reviewed ->
                selection.copy(name = reviewed.name, kind = reviewed.kind)
            }) ?: selection
        }
        // Localize last, by the final stable ID. A retired row or an absent
        // display-name row stays exactly as resolved above.
        val resolvedLocalityId = resolved.localityId ?: return resolved
        return LocalityDisplayNames.nameAr(context, resolvedLocalityId)
            ?.let { resolved.copy(name = it) }
            ?: resolved
    }

    fun observeLocationSelection(context: Context, onChanged: () -> Unit): () -> Unit {
        val settings = prefs(context)
        val keys = setOf(KEY_DELEGATION_ID, KEY_LOCALITY_ID, KEY_LOCALITY_NAME, KEY_LOCALITY_KIND, KEY_LOCATION_FROM_GPS, KEY_MANUAL_REFERENCE_ID) +
            PLACE_KEYS + KEY_PLACE_PROVISIONAL
        // One save changes several keys in one apply(), and the listener is called once per key,
        // all in one pass on the main thread. Post one callback for the whole batch instead.
        val mainHandler = Handler(Looper.getMainLooper())
        val notifyChange = Runnable { onChanged() }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in keys) {
                mainHandler.removeCallbacks(notifyChange)
                mainHandler.post(notifyChange)
            }
        }
        settings.registerOnSharedPreferenceChangeListener(listener)
        return {
            settings.unregisterOnSharedPreferenceChangeListener(listener)
            mainHandler.removeCallbacks(notifyChange)
        }
    }

    fun isEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ENABLED, true)
    }

    fun isFirstLaunch(context: Context): Boolean {
        return !prefs(context).getBoolean(KEY_FIRST_LAUNCH, false)
    }

    fun markFirstLaunchDone(context: Context) {
        prefs(context).edit().putBoolean(KEY_FIRST_LAUNCH, true).apply()
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isAutoSilenceActive(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_AUTO_SILENCE_ACTIVE, false)
    }

    fun markAutoSilenceActive(context: Context, previousRingerMode: Int, previousInterruptionFilter: Int) {
        prefs(context).edit()
            .putBoolean(KEY_AUTO_SILENCE_ACTIVE, true)
            .putInt(KEY_AUTO_SILENCE_PREVIOUS_RINGER_MODE, previousRingerMode)
            .putInt(KEY_AUTO_SILENCE_PREVIOUS_INTERRUPTION_FILTER, previousInterruptionFilter)
            .apply()
    }

    fun getAutoSilencePreviousRingerMode(context: Context): Int {
        return prefs(context).getInt(KEY_AUTO_SILENCE_PREVIOUS_RINGER_MODE, AudioManager.RINGER_MODE_NORMAL)
    }

    fun getAutoSilencePreviousInterruptionFilter(context: Context): Int {
        return prefs(context).getInt(
            KEY_AUTO_SILENCE_PREVIOUS_INTERRUPTION_FILTER,
            NotificationManager.INTERRUPTION_FILTER_ALL
        )
    }

    fun clearAutoSilenceState(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_AUTO_SILENCE_ACTIVE, false)
            .remove(KEY_AUTO_SILENCE_PREVIOUS_RINGER_MODE)
            .remove(KEY_AUTO_SILENCE_PREVIOUS_INTERRUPTION_FILTER)
            .apply()
    }

    fun getAutoSilenceDismissedUntilMillis(context: Context): Long {
        return prefs(context).getLong(KEY_AUTO_SILENCE_DISMISSED_UNTIL_MILLIS, 0L)
    }

    fun getAutoSilenceDismissedPrayer(context: Context): String? {
        return prefs(context).getString(KEY_AUTO_SILENCE_DISMISSED_PRAYER, null)
    }

    fun setAutoSilenceDismissed(context: Context, millis: Long, prayer: Prayer) {
        prefs(context).edit()
            .putLong(KEY_AUTO_SILENCE_DISMISSED_UNTIL_MILLIS, millis)
            .putString(KEY_AUTO_SILENCE_DISMISSED_PRAYER, prayer.name)
            .apply()
    }

    fun clearAutoSilenceDismissed(context: Context) {
        prefs(context).edit()
            .remove(KEY_AUTO_SILENCE_DISMISSED_UNTIL_MILLIS)
            .remove(KEY_AUTO_SILENCE_DISMISSED_PRAYER)
            .apply()
    }

    private const val KEY_AUTO_SILENCE_DISMISSALS = "auto_silence_dismissed_occurrences"

    /**
     * The occurrences of auto-silence the user dismissed (plan 6.9): each a prayer of a prayer day, so a
     * dismissal in Fajr(k)'s window does not stop Fajr(k + 1)'s.
     */
    fun getAutoSilenceDismissals(context: Context): Set<Pair<Prayer, java.time.LocalDate>> =
        prefs(context).getString(KEY_AUTO_SILENCE_DISMISSALS, null).orEmpty().split(',').mapNotNull { entry ->
            val (prayer, day) = entry.split('@').takeIf { it.size == 2 } ?: return@mapNotNull null
            val p = runCatching { Prayer.valueOf(prayer) }.getOrNull() ?: return@mapNotNull null
            val d = day.toLongOrNull()?.let(java.time.LocalDate::ofEpochDay) ?: return@mapNotNull null
            p to d
        }.toSet()

    /** Adds dismissed occurrences, dropping those of prayer days before [keepFrom]. */
    fun addAutoSilenceDismissals(context: Context, occurrences: Collection<Pair<Prayer, java.time.LocalDate>>, keepFrom: java.time.LocalDate) {
        val kept = (getAutoSilenceDismissals(context) + occurrences).filter { !it.second.isBefore(keepFrom) }
        prefs(context).edit()
            .putString(KEY_AUTO_SILENCE_DISMISSALS, kept.joinToString(",") { "${it.first.name}@${it.second.toEpochDay()}" })
            .apply()
    }

    fun clearAutoSilenceDismissals(context: Context) {
        prefs(context).edit().remove(KEY_AUTO_SILENCE_DISMISSALS).apply()
    }

    fun isManualSilenceActive(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_MANUAL_SILENCE_ACTIVE, false)
    }

    fun markManualSilenceActive(context: Context, previousRingerMode: Int, previousInterruptionFilter: Int) {
        prefs(context).edit()
            .putBoolean(KEY_MANUAL_SILENCE_ACTIVE, true)
            .putInt(KEY_MANUAL_SILENCE_PREVIOUS_RINGER_MODE, previousRingerMode)
            .putInt(KEY_MANUAL_SILENCE_PREVIOUS_INTERRUPTION_FILTER, previousInterruptionFilter)
            .apply()
    }

    fun getManualSilencePreviousRingerMode(context: Context): Int {
        return prefs(context).getInt(KEY_MANUAL_SILENCE_PREVIOUS_RINGER_MODE, AudioManager.RINGER_MODE_NORMAL)
    }

    fun getManualSilencePreviousInterruptionFilter(context: Context): Int {
        return prefs(context).getInt(
            KEY_MANUAL_SILENCE_PREVIOUS_INTERRUPTION_FILTER,
            NotificationManager.INTERRUPTION_FILTER_ALL
        )
    }

    fun clearManualSilenceState(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_MANUAL_SILENCE_ACTIVE, false)
            .remove(KEY_MANUAL_SILENCE_PREVIOUS_RINGER_MODE)
            .remove(KEY_MANUAL_SILENCE_PREVIOUS_INTERRUPTION_FILTER)
            .remove(KEY_MANUAL_SILENCE_ENDS_AT_MILLIS)
            .apply()
    }

    fun usesManualSilenceDuration(context: Context): Boolean {
        return getManualSilenceMode(context) == ManualSilenceMode.DURATION
    }

    fun setManualSilenceUsesDuration(context: Context, usesDuration: Boolean) {
        setManualSilenceMode(
            context,
            if (usesDuration) ManualSilenceMode.DURATION else ManualSilenceMode.UNTIL_STOPPED
        )
    }

    fun getManualSilenceMode(context: Context): ManualSilenceMode {
        val storedMode = prefs(context).getString(KEY_MANUAL_SILENCE_MODE, null)
            ?.let { raw -> runCatching { ManualSilenceMode.valueOf(raw) }.getOrNull() }
        if (storedMode != null) return storedMode

        return if (prefs(context).getBoolean(KEY_MANUAL_SILENCE_USES_DURATION, false)) {
            ManualSilenceMode.DURATION
        } else {
            ManualSilenceMode.UNTIL_STOPPED
        }
    }

    fun setManualSilenceMode(context: Context, mode: ManualSilenceMode) {
        prefs(context).edit()
            .putString(KEY_MANUAL_SILENCE_MODE, mode.name)
            .putBoolean(KEY_MANUAL_SILENCE_USES_DURATION, mode == ManualSilenceMode.DURATION)
            .apply()
    }

    fun getManualSilenceTargetPrayer(context: Context): Prayer {
        val storedPrayer = prefs(context).getString(KEY_MANUAL_SILENCE_TARGET_PRAYER, null)
            ?.let { raw -> runCatching { Prayer.valueOf(raw) }.getOrNull() }
            ?: Prayer.FAJR

        return if (storedPrayer in listOf(Prayer.FAJR, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA)) {
            storedPrayer
        } else {
            Prayer.FAJR
        }
    }

    fun setManualSilenceTargetPrayer(context: Context, prayer: Prayer) {
        prefs(context).edit()
            .putString(KEY_MANUAL_SILENCE_TARGET_PRAYER, prayer.name)
            .apply()
    }

    fun getManualSilenceDurationMinutes(context: Context): Int {
        return prefs(context).getInt(
            KEY_MANUAL_SILENCE_DURATION_MINUTES,
            DEFAULT_MANUAL_SILENCE_DURATION_MINUTES
        )
    }

    fun setManualSilenceDurationMinutes(context: Context, minutes: Int) {
        prefs(context).edit()
            .putInt(KEY_MANUAL_SILENCE_DURATION_MINUTES, minutes.coerceAtLeast(1))
            .apply()
    }

    fun getManualSilenceEndsAtMillis(context: Context): Long {
        return prefs(context).getLong(KEY_MANUAL_SILENCE_ENDS_AT_MILLIS, -1L)
    }

    fun setManualSilenceEndsAtMillis(context: Context, endsAtMillis: Long) {
        prefs(context).edit().putLong(KEY_MANUAL_SILENCE_ENDS_AT_MILLIS, endsAtMillis).apply()
    }

    fun clearManualSilenceEndsAt(context: Context) {
        prefs(context).edit().remove(KEY_MANUAL_SILENCE_ENDS_AT_MILLIS).apply()
    }

    fun markCallReceivedDuringSilence(context: Context) {
        prefs(context).edit().putBoolean(KEY_CALL_RECEIVED_DURING_SILENCE, true).apply()
    }

    fun clearCallReceivedDuringSilence(context: Context) {
        prefs(context).edit().putBoolean(KEY_CALL_RECEIVED_DURING_SILENCE, false).apply()
    }

    fun consumeCallReceivedDuringSilence(context: Context): Boolean {
        val hadCall = prefs(context).getBoolean(KEY_CALL_RECEIVED_DURING_SILENCE, false)
        if (hadCall) {
            clearCallReceivedDuringSilence(context)
        }
        return hadCall
    }

    fun isCallEndVibrationEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_CALL_END_VIBRATION_ENABLED, true)
    }

    fun setCallEndVibrationEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CALL_END_VIBRATION_ENABLED, enabled).apply()
    }

    fun isAutoLocationUpdateEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_AUTO_LOCATION_UPDATE, true)
    }

    fun setAutoLocationUpdateEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_LOCATION_UPDATE, enabled).apply()
    }

    fun getQiblaMethod(context: Context): QiblaMethod {
        return prefs(context).getString(KEY_QIBLA_METHOD, null)
            ?.let { raw -> runCatching { QiblaMethod.valueOf(raw) }.getOrNull() }
            ?: QiblaMethod.GreatCircle
    }

    fun setQiblaMethod(context: Context, method: QiblaMethod) {
        prefs(context).edit().putString(KEY_QIBLA_METHOD, method.name).apply()
    }

    /** The alarms tab's Fajr-alarm suggestion, once closed, never comes back. */
    fun isFajrAlarmSuggestionDismissed(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_FAJR_ALARM_SUGGESTION_DISMISSED, false)
    }

    fun setFajrAlarmSuggestionDismissed(context: Context) {
        prefs(context).edit().putBoolean(KEY_FAJR_ALARM_SUGGESTION_DISMISSED, true).apply()
    }

    private const val RAMADAN_ISHA_MINUTES = 90
    private const val KEY_RAMADAN_OVERRIDE_APPLIED = "ramadan_isha_override_hijri_year"
    // The user's own Isha values from before the override, restored once that Ramadan is over.
    // UNSET marks a key that was not set, so the restore removes it again.
    private const val KEY_RAMADAN_SAVED_ISHA_AFTER = "ramadan_isha_saved_after_minutes"
    private const val KEY_RAMADAN_SAVED_ISHA_END_OFFSET = "ramadan_isha_saved_end_offset"
    private const val UNSET = Int.MIN_VALUE
    private const val HIJRI_RAMADAN = 9

    private fun defaultAfterMinutes(prayer: Prayer): Int = when (prayer) {
        Prayer.FAJR -> 60
        Prayer.DHUHR -> 60
        Prayer.ASR -> 30
        Prayer.MAGHRIB -> 20
        Prayer.ISHA -> 30
        Prayer.JOMOAA -> 60
        Prayer.AID_FITR -> 60
        Prayer.AID_ADHA -> 60
    }

    fun getAfterMinutes(context: Context, prayer: Prayer): Int {
        return prefs(context).getInt("after_${prayer.name}", defaultAfterMinutes(prayer))
    }

    /**
     * One-time Ramadan override: if it's Ramadan and we haven't already applied the
     * override this Hijri year, bump Isha duration to 90 min (only if current value < 90).
     * The user's own Isha values are saved first and restored once that Ramadan is over.
     * After this, the user's changes are respected: an Isha edit drops the saved values,
     * so it is kept after Ramadan.
     */
    fun applyRamadanIshaOverrideIfNeeded(context: Context) {
        applyRamadanIshaOverrideIfNeeded(context, HijrahDate.now(), getPlace(context))
    }

    /**
     * Plan 6.10's order: (1) the restore, at every place, with nothing checked before it; (2) the bump, at a
     * Tunisian delegation only (A10), so a place abroad neither writes it nor records the year, and a return to
     * Tunisia later in the same Ramadan applies it then; (3) a place abroad reads the saved values ([getConfig]).
     */
    // Synchronized: the Application (main thread) and the scheduler can run this at once, and
    // a second override must not save the first one's 90 as the user's own value.
    @Synchronized
    internal fun applyRamadanIshaOverrideIfNeeded(context: Context, hijrahDate: HijrahDate, place: PrayerPlace = getPlace(context)) {
        val settings = prefs(context)
        val isRamadan = RamadanDetector.isRamadan(hijrahDate)
        val hijriYear = hijrahDate.get(ChronoField.YEAR)
        val appliedYear = settings.getInt(KEY_RAMADAN_OVERRIDE_APPLIED, -1)
        // Restore only after that Ramadan: a date before it (a clock or an official date
        // change) must not end an override that would then not be applied again this year.
        val afterAppliedRamadan = hijriYear > appliedYear ||
            (hijriYear == appliedYear && !isRamadan && hijrahDate.get(ChronoField.MONTH_OF_YEAR) > HIJRI_RAMADAN)
        if (afterAppliedRamadan) restoreIshaSavedBeforeRamadan(context)
        if (!isRamadan) return
        if (place !is PrayerPlace.Inm) return // the bump is Tunisian only (A10)
        if (appliedYear == hijriYear) return // already applied this Ramadan

        val afterKey = "after_${Prayer.ISHA.name}"
        val endOffsetKey = "end_offset_${Prayer.ISHA.name}"
        val edit = settings.edit().putInt(KEY_RAMADAN_OVERRIDE_APPLIED, hijriYear)
        if (getAfterMinutes(context, Prayer.ISHA) < RAMADAN_ISHA_MINUTES) {
            edit.putInt(KEY_RAMADAN_SAVED_ISHA_AFTER, settings.getInt(afterKey, UNSET))
                .putInt(KEY_RAMADAN_SAVED_ISHA_END_OFFSET, settings.getInt(endOffsetKey, UNSET))
                .putInt(afterKey, RAMADAN_ISHA_MINUTES)
                // An explicit adhan-relative end would otherwise keep overriding the
                // legacy duration this override relies on.
                .remove(endOffsetKey)
        }
        edit.apply()
    }

    private fun restoreIshaSavedBeforeRamadan(context: Context) {
        val settings = prefs(context)
        if (!settings.contains(KEY_RAMADAN_SAVED_ISHA_AFTER)) return
        val afterKey = "after_${Prayer.ISHA.name}"
        val endOffsetKey = "end_offset_${Prayer.ISHA.name}"
        val savedAfter = settings.getInt(KEY_RAMADAN_SAVED_ISHA_AFTER, UNSET)
        val savedEndOffset = settings.getInt(KEY_RAMADAN_SAVED_ISHA_END_OFFSET, UNSET)
        val edit = settings.edit()
            .remove(KEY_RAMADAN_SAVED_ISHA_AFTER)
            .remove(KEY_RAMADAN_SAVED_ISHA_END_OFFSET)
        if (savedAfter == UNSET) edit.remove(afterKey) else edit.putInt(afterKey, savedAfter)
        if (savedEndOffset == UNSET) edit.remove(endOffsetKey) else edit.putInt(endOffsetKey, savedEndOffset)
        edit.apply()
    }

    /** An Isha edit is the user's choice: drop the values saved by the Ramadan override so it is kept. */
    private fun SharedPreferences.Editor.dropIshaSavedBeforeRamadan(prayer: Prayer): SharedPreferences.Editor {
        if (prayer == Prayer.ISHA) remove(KEY_RAMADAN_SAVED_ISHA_AFTER).remove(KEY_RAMADAN_SAVED_ISHA_END_OFFSET)
        return this
    }

    fun setAfterMinutes(context: Context, prayer: Prayer, minutes: Int) {
        prefs(context).edit().putInt("after_${prayer.name}", minutes).dropIshaSavedBeforeRamadan(prayer).apply()
    }

    private fun defaultSilenceMode(prayer: Prayer): SilenceMode = when (prayer) {
        Prayer.DHUHR -> SilenceMode.FIXED_TIME
        Prayer.JOMOAA -> SilenceMode.FIXED_TIME
        Prayer.AID_FITR -> SilenceMode.DURATION
        Prayer.AID_ADHA -> SilenceMode.DURATION
        else -> SilenceMode.DURATION
    }

    fun getSilenceMode(context: Context, prayer: Prayer): SilenceMode {
        val default = defaultSilenceMode(prayer)
        val modeStr = prefs(context).getString("mode_${prayer.name}", default.name)
        return try { SilenceMode.valueOf(modeStr!!) } catch (_: Exception) { default }
    }

    fun setSilenceMode(context: Context, prayer: Prayer, mode: SilenceMode) {
        prefs(context).edit().putString("mode_${prayer.name}", mode.name).dropIshaSavedBeforeRamadan(prayer).apply()
    }

    fun getFixedTimeHour(context: Context, prayer: Prayer): Int {
        val default = if (prayer == Prayer.DHUHR || prayer == Prayer.JOMOAA) 13 else -1
        return prefs(context).getInt("fixed_hour_${prayer.name}", default)
    }

    fun getFixedTimeMinute(context: Context, prayer: Prayer): Int {
        val default = if (prayer == Prayer.DHUHR || prayer == Prayer.JOMOAA) 15 else -1
        return prefs(context).getInt("fixed_minute_${prayer.name}", default)
    }

    fun setFixedTime(context: Context, prayer: Prayer, hour: Int, minute: Int) {
        prefs(context).edit()
            .putInt("fixed_hour_${prayer.name}", hour)
            .putInt("fixed_minute_${prayer.name}", minute)
            .dropIshaSavedBeforeRamadan(prayer)
            .apply()
    }

    // --- Delay settings ---

    fun getDelayMinutes(context: Context, prayer: Prayer): Int {
        return prefs(context).getInt("delay_${prayer.name}", 0)
    }

    fun setDelayMinutes(context: Context, prayer: Prayer, minutes: Int) {
        prefs(context).edit().putInt("delay_${prayer.name}", minutes).dropIshaSavedBeforeRamadan(prayer).apply()
    }

    fun getDelayMode(context: Context, prayer: Prayer): DelayMode {
        val modeStr = prefs(context).getString("delay_mode_${prayer.name}", DelayMode.MINUTES.name)
        return try { DelayMode.valueOf(modeStr!!) } catch (_: Exception) { DelayMode.MINUTES }
    }

    fun setDelayMode(context: Context, prayer: Prayer, mode: DelayMode) {
        prefs(context).edit().putString("delay_mode_${prayer.name}", mode.name).dropIshaSavedBeforeRamadan(prayer).apply()
    }

    fun getDelayFixedHour(context: Context, prayer: Prayer): Int {
        return prefs(context).getInt("delay_fixed_hour_${prayer.name}", -1)
    }

    fun getDelayFixedMinute(context: Context, prayer: Prayer): Int {
        return prefs(context).getInt("delay_fixed_minute_${prayer.name}", -1)
    }

    fun setDelayFixedTime(context: Context, prayer: Prayer, hour: Int, minute: Int) {
        prefs(context).edit()
            .putInt("delay_fixed_hour_${prayer.name}", hour)
            .putInt("delay_fixed_minute_${prayer.name}", minute)
            .dropIshaSavedBeforeRamadan(prayer)
            .apply()
    }

    // --- Independent adhan-relative end offset (null = legacy duration end) ---

    private const val NO_END_OFFSET = Int.MIN_VALUE

    fun getEndOffsetMinutes(context: Context, prayer: Prayer): Int? {
        val stored = prefs(context).getInt("end_offset_${prayer.name}", NO_END_OFFSET)
        return stored.takeIf { it != NO_END_OFFSET }
    }

    fun setEndOffsetMinutes(context: Context, prayer: Prayer, minutes: Int?) {
        val edit = prefs(context).edit().dropIshaSavedBeforeRamadan(prayer)
        if (minutes == null) {
            edit.remove("end_offset_${prayer.name}")
        } else {
            edit.putInt("end_offset_${prayer.name}", minutes)
        }
        edit.apply()
    }

    /**
     * A prayer's window settings. At a place abroad, while a Tunisian Ramadan bump is in force, Isha reads
     * the user's own values saved before it (plan 6.10): the bump is Tunisian only, and the stored keys stay
     * untouched so a Tunisian place later in the same Ramadan still gets 90. A saved UNSET reads as the default.
     */
    fun getConfig(context: Context, prayer: Prayer, place: PrayerPlace = getPlace(context)): PrayerSilenceConfig {
        val settings = prefs(context)
        val savedBeforeRamadan = prayer == Prayer.ISHA && place is PrayerPlace.Coordinates && settings.contains(KEY_RAMADAN_SAVED_ISHA_AFTER)
        val afterMinutes = if (savedBeforeRamadan) {
            settings.getInt(KEY_RAMADAN_SAVED_ISHA_AFTER, UNSET).takeIf { it != UNSET } ?: defaultAfterMinutes(prayer)
        } else getAfterMinutes(context, prayer)
        val endOffsetMinutes = if (savedBeforeRamadan) {
            settings.getInt(KEY_RAMADAN_SAVED_ISHA_END_OFFSET, UNSET).takeIf { it != UNSET }
        } else getEndOffsetMinutes(context, prayer)
        return PrayerSilenceConfig(
            mode = getSilenceMode(context, prayer),
            afterMinutes = afterMinutes,
            fixedHour = getFixedTimeHour(context, prayer),
            fixedMinute = getFixedTimeMinute(context, prayer),
            delayMode = getDelayMode(context, prayer),
            delayMinutes = getDelayMinutes(context, prayer),
            delayFixedHour = getDelayFixedHour(context, prayer),
            delayFixedMinute = getDelayFixedMinute(context, prayer),
            endOffsetMinutes = endOffsetMinutes
        )
    }

    /**
     * A prayer's window as the planner reads it (plan 6.9): an unedited Dhuhr or Jumu'a start or end is unset,
     * since its default (D12, A18) depends on the place and the date, which [getConfig] does not have. An
     * endpoint is edited once any of its keys is stored. A fixed end carries the adhan offset it was saved at (D16).
     */
    fun getSilenceRule(context: Context, prayer: Prayer): com.tunisianprayertimes.silence.SilenceRule {
        val settings = prefs(context)
        val dhuhrOrJumua = prayer == Prayer.DHUHR || prayer == Prayer.JOMOAA
        val startKeys = listOf("delay_mode", "delay", "delay_fixed_hour", "delay_fixed_minute").map { "${it}_${prayer.name}" }
        val endKeys = listOf("mode", "after", "fixed_hour", "fixed_minute", "end_offset").map { "${it}_${prayer.name}" }
        val saved = settings.getInt(endSavedOffsetKey(prayer), Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
        return com.tunisianprayertimes.silence.SilenceRule.of(
            getConfig(context, prayer),
            startUnset = dhuhrOrJumua && startKeys.none(settings::contains),
            endUnset = dhuhrOrJumua && endKeys.none(settings::contains),
            savedEndOffsetMinutes = saved,
        )
    }

    private fun endSavedOffsetKey(prayer: Prayer) = "end_saved_offset_${prayer.name}"

    /** D16: the offset from the adhan a fixed end had when the user saved it, the end of a day where even the cap does not fit. */
    fun setEndSavedOffsetMinutes(context: Context, prayer: Prayer, minutes: Int?) {
        val edit = prefs(context).edit()
        if (minutes == null) edit.remove(endSavedOffsetKey(prayer)) else edit.putInt(endSavedOffsetKey(prayer), minutes)
        edit.apply()
    }

    /**
     * Saved adhkar reminders that no longer fit every day of the coming year at the place (6.9), by id, with the
     * problem their card shows; written after a change of place or method, cleared for a reminder saved again.
     */
    fun getAdhkarPlaceFlags(context: Context): Map<String, String> {
        val stored = prefs(context).getString(KEY_ADHKAR_PLACE_FLAGS, null) ?: return emptyMap()
        return runCatching {
            val json = org.json.JSONObject(stored)
            json.keys().asSequence().associateWith { json.getString(it) }
        }.getOrDefault(emptyMap())
    }

    fun setAdhkarPlaceFlags(context: Context, flags: Map<String, String>) {
        val edit = prefs(context).edit()
        if (flags.isEmpty()) edit.remove(KEY_ADHKAR_PLACE_FLAGS) else edit.putString(KEY_ADHKAR_PLACE_FLAGS, org.json.JSONObject(flags).toString())
        edit.apply()
    }

    fun clearAdhkarPlaceFlag(context: Context, reminderId: String) {
        val flags = getAdhkarPlaceFlags(context)
        if (reminderId in flags) setAdhkarPlaceFlags(context, flags - reminderId)
    }

    /** What a save does with one endpoint's keys (plan 6.9). */
    enum class EndpointSave {
        /** Untouched: the endpoint did not move. */
        KEEP,

        WRITE,

        /** Removed: a Dhuhr or Jumu'a endpoint moved back to its default follows the default again (D12, A18). */
        CLEAR,
    }

    /**
     * Publish the window together so readers never observe half of an edit. The editors write only the endpoint
     * that moved (6.9); a fixed end stores beside it its adhan offset on the day shown ([savedEndOffsetMinutes], D16).
     */
    fun setConfig(
        context: Context,
        prayer: Prayer,
        config: PrayerSilenceConfig,
        start: EndpointSave = EndpointSave.WRITE,
        end: EndpointSave = EndpointSave.WRITE,
        savedEndOffsetMinutes: Int? = null,
    ) {
        val edit = prefs(context).edit()
        val p = prayer.name
        when (end) {
            EndpointSave.KEEP -> Unit
            EndpointSave.WRITE -> {
                edit.putString("mode_$p", config.mode.name)
                    .putInt("after_$p", config.afterMinutes)
                    .putInt("fixed_hour_$p", config.fixedHour)
                    .putInt("fixed_minute_$p", config.fixedMinute)
                // Local val: cross-module properties are not smart-castable.
                val endOffsetMinutes = config.endOffsetMinutes
                if (endOffsetMinutes == null) edit.remove("end_offset_$p") else edit.putInt("end_offset_$p", endOffsetMinutes)
                if (savedEndOffsetMinutes == null) edit.remove(endSavedOffsetKey(prayer)) else edit.putInt(endSavedOffsetKey(prayer), savedEndOffsetMinutes)
            }
            EndpointSave.CLEAR -> listOf("mode_$p", "after_$p", "fixed_hour_$p", "fixed_minute_$p", "end_offset_$p", endSavedOffsetKey(prayer))
                .forEach(edit::remove)
        }
        when (start) {
            EndpointSave.KEEP -> Unit
            EndpointSave.WRITE -> edit.putString("delay_mode_$p", config.delayMode.name)
                .putInt("delay_$p", config.delayMinutes)
                .putInt("delay_fixed_hour_$p", config.delayFixedHour)
                .putInt("delay_fixed_minute_$p", config.delayFixedMinute)
            EndpointSave.CLEAR -> listOf("delay_mode_$p", "delay_$p", "delay_fixed_hour_$p", "delay_fixed_minute_$p").forEach(edit::remove)
        }
        if (start != EndpointSave.KEEP || end != EndpointSave.KEEP) edit.dropIshaSavedBeforeRamadan(prayer)
        edit.apply()
    }

    /**
     * The window the editors open on (plan 6.9): [getConfig] with the defaults of a Dhuhr or Jumu'a endpoint never
     * edited written out (D12, A18), so the editors show what the planner arms.
     */
    fun getEffectiveConfig(context: Context, prayer: Prayer, place: PrayerPlace = getPlace(context)): PrayerSilenceConfig {
        val rule = getSilenceRule(context, prayer)
        val defaults = com.tunisianprayertimes.silence.SilenceEndpoints.withDefaults(rule, prayer, inm = place is PrayerPlace.Inm)
        var config = getConfig(context, prayer, place)
        if (rule.start == com.tunisianprayertimes.silence.SilenceStart.Unset) {
            config = when (val start = defaults.start) {
                is com.tunisianprayertimes.silence.SilenceStart.Clock ->
                    config.copy(delayMode = DelayMode.FIXED_TIME, delayFixedHour = start.time.hour, delayFixedMinute = start.time.minute)
                is com.tunisianprayertimes.silence.SilenceStart.FromAdhan -> config.copy(delayMode = DelayMode.MINUTES, delayMinutes = start.minutes)
                com.tunisianprayertimes.silence.SilenceStart.Unset -> config
            }
        }
        if (rule.end == com.tunisianprayertimes.silence.SilenceEnd.Unset) {
            config = when (val end = defaults.end) {
                is com.tunisianprayertimes.silence.SilenceEnd.Clock ->
                    config.copy(mode = SilenceMode.FIXED_TIME, fixedHour = end.time.hour, fixedMinute = end.time.minute, endOffsetMinutes = null)
                is com.tunisianprayertimes.silence.SilenceEnd.FromAdhan -> config.copy(mode = SilenceMode.DURATION, endOffsetMinutes = end.minutes)
                is com.tunisianprayertimes.silence.SilenceEnd.FromStart ->
                    config.copy(mode = SilenceMode.DURATION, endOffsetMinutes = null, afterMinutes = end.minutes)
                com.tunisianprayertimes.silence.SilenceEnd.Unset -> config
            }
        }
        return config
    }

    fun observeConfigChanges(context: Context, prayer: Prayer, onChanged: () -> Unit): () -> Unit {
        val settings = prefs(context)
        val keys = listOf(
            "mode", "after", "fixed_hour", "fixed_minute", "delay_mode", "delay",
            "delay_fixed_hour", "delay_fixed_minute", "end_offset",
        ).mapTo(mutableSetOf()) { "${it}_${prayer.name}" }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in keys) onChanged()
        }
        settings.registerOnSharedPreferenceChangeListener(listener)
        return { settings.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    // --- Per-prayer silence enable (independent of the global auto-silence switch) ---

    private fun prayerEnabledKey(prayer: Prayer) = "enabled_${prayer.name}"

    fun isPrayerSilenceEnabled(context: Context, prayer: Prayer): Boolean {
        return prefs(context).getBoolean(prayerEnabledKey(prayer), true)
    }

    fun setPrayerSilenceEnabled(context: Context, prayer: Prayer, enabled: Boolean) {
        prefs(context).edit().putBoolean(prayerEnabledKey(prayer), enabled).apply()
    }

    fun observePrayerEnabledChanges(context: Context, prayer: Prayer, onChanged: () -> Unit): () -> Unit {
        val settings = prefs(context)
        val key = prayerEnabledKey(prayer)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == null || changedKey == key) onChanged()
        }
        settings.registerOnSharedPreferenceChangeListener(listener)
        return { settings.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    // --- Jomoaa custom time (defaults to -1 = use Dhuhr time) ---

    fun getJomoaaTimeHour(context: Context): Int {
        return prefs(context).getInt("jomoaa_time_hour", -1)
    }

    fun getJomoaaTimeMinute(context: Context): Int {
        return prefs(context).getInt("jomoaa_time_minute", -1)
    }

    fun setJomoaaTime(context: Context, hour: Int, minute: Int) {
        prefs(context).edit()
            .putInt("jomoaa_time_hour", hour)
            .putInt("jomoaa_time_minute", minute)
            .apply()
    }

    // --- Aid Fitr custom time (defaults to -1 = use Shuruk) ---

    fun getAidFitrTimeHour(context: Context): Int {
        return prefs(context).getInt("aid_fitr_time_hour", -1)
    }

    fun getAidFitrTimeMinute(context: Context): Int {
        return prefs(context).getInt("aid_fitr_time_minute", -1)
    }

    fun setAidFitrTime(context: Context, hour: Int, minute: Int) {
        prefs(context).edit()
            .putInt("aid_fitr_time_hour", hour)
            .putInt("aid_fitr_time_minute", minute)
            .apply()
    }

    // --- Aid Adha custom time (defaults to -1 = use Shuruk) ---

    fun getAidAdhaTimeHour(context: Context): Int {
        return prefs(context).getInt("aid_adha_time_hour", -1)
    }

    fun getAidAdhaTimeMinute(context: Context): Int {
        return prefs(context).getInt("aid_adha_time_minute", -1)
    }

    fun setAidAdhaTime(context: Context, hour: Int, minute: Int) {
        prefs(context).edit()
            .putInt("aid_adha_time_hour", hour)
            .putInt("aid_adha_time_minute", minute)
            .apply()
    }
}
