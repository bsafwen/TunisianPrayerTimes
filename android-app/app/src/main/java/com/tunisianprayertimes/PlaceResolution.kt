package com.tunisianprayertimes

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tunisianprayertimes.adhkar.DhikrReminderScheduler
import com.tunisianprayertimes.method.CountryMethodDefaults
import com.tunisianprayertimes.place.CountryResolver
import com.tunisianprayertimes.place.LatLngE5
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.place.Regions
import com.tunisianprayertimes.place.ZoneResolver
import com.tunisianprayertimes.place.ZoneSource
import java.time.ZoneId
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A fix outside the Tunisia outline (plan 6.8): its country from the catalogue's rows and the network, its zone,
 * its administrative values and elevation, stored rounded to 0.01°, and a «قرب X» label. And the one step every
 * stored change of place goes through, [onPlaceChanged].
 */
object PlaceResolution {
    private const val TAG = "PlaceResolution"
    private const val LOCATION_CHANNEL = "location"
    private const val LOCATION_NOTIFICATION_ID = 7_301
    private const val LABEL_KM = 30.0
    private const val ELEVATION_KM = 40.0
    private const val MOVE_KM = 3.0

    /** 6.8: a place change clears a silence dismissal only where it moves that prayer's time by more than this. */
    private const val DISMISSAL_MOVE_MINUTES = 45L

    /** East Jerusalem's governorate, approximated by its city's area (8.8, Q1): its clock follows the phone's when Israeli or West Bank time. */
    private val JERUSALEM = LatLngE5.of(31.7767, 35.2345)
    private const val JERUSALEM_KM = 15.0

    /** The network country (`TelephonyManager.networkCountryIso`), or null. */
    fun networkCountry(context: Context): String? = runCatching {
        (context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.networkCountryIso?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** The region of the phone's zone (ICU), "001" for a non-geographic zone. */
    fun zoneRegion(zoneId: String): String? = runCatching { android.icu.util.TimeZone.getRegion(zoneId) }.getOrNull()

    /**
     * The result of a fix outside the outline: a Tunisian delegation when the country step puts it in Tunisia, a
     * closed region (the fix is ignored), or the place abroad.
     */
    fun resolveAbroad(
        context: Context,
        lat: Double,
        lng: Double,
        network: String? = networkCountry(context),
        phoneZone: String = ZoneId.systemDefault().id,
    ): DelegationLocationResult {
        val catalogue = PlaceCatalogue.get(context)
        val stored = LatLngE5.of(round2(lat), round2(lng))
        val resolution = CountryResolver.resolve(stored, catalogue, network, zoneRegion(phoneZone))
        val cc = resolution.countryCode
        if (cc == "TN") {
            // Offshore near Djerba or Kerkennah: Tunisia has one method (D7), so its nearest delegation.
            val nearest = GouvernoratRepository.findNearestDelegation(context, lat, lng) ?: return DelegationLocationResult.NoDelegationFound
            return DelegationLocationResult.Success(nearest, null)
        }
        val sameCountryRows = catalogue.rowsWithin(stored, ZoneResolver.RADIUS_KM)
            .filter { cc == null || CountryResolver.normalize(it.first.ref.countryCode) == cc }
        val zoneId = ZoneResolver.resolve(
            stored, cc, catalogue, phoneZone,
            jerusalemGovernorate = cc == "PS" && CountryMethodDefaults.distanceKm(stored, JERUSALEM) <= JERUSALEM_KM,
        )
        val place = PrayerPlace.Coordinates(
            latE5 = stored.latE5,
            lngE5 = stored.lngE5,
            elevationM = elevationFor(catalogue, stored, cc, resolution.admin1),
            zoneId = zoneId,
            zoneSource = if (sameCountryRows.isEmpty()) ZoneSource.DEVICE else ZoneSource.NEAREST_CITY,
            countryCode = cc,
            admin1 = resolution.admin1,
            admin2 = resolution.admin2,
        )
        return DelegationLocationResult.Abroad(place, labelFor(catalogue, stored))
    }

    /**
     * A city picked in the catalogue (6.8): its own country, zone (the Jerusalem governorate's rule aside),
     * administrative values and, for an Arab or Réunion row, its own `dem`. Its label is its Arabic name.
     */
    fun placeFromCatalogue(context: Context, row: PlaceCatalogue.Row, phoneZone: String = ZoneId.systemDefault().id): PrayerPlace.Coordinates {
        val catalogue = PlaceCatalogue.get(context)
        val cc = CountryResolver.normalize(row.ref.countryCode)
        val resolution = CountryResolver.resolve(row.ref.point, catalogue, null, null, picked = row.ref)
        val zoneId = ZoneResolver.resolve(
            row.ref.point, cc, catalogue, phoneZone, picked = row.ref,
            jerusalemGovernorate = cc == "PS" && CountryMethodDefaults.distanceKm(row.ref.point, JERUSALEM) <= JERUSALEM_KM,
        )
        val ownDem = row.dem.takeIf { cc in Regions.ARAB_LEAGUE || cc == "RE" || (cc == "ES" && resolution.admin1 in setOf("CE", "ML")) }
        return PrayerPlace.Coordinates(
            latE5 = row.ref.point.latE5,
            lngE5 = row.ref.point.lngE5,
            elevationM = ownDem,
            zoneId = zoneId,
            zoneSource = ZoneSource.CATALOGUE,
            countryCode = cc,
            catalogueId = row.ref.id,
            admin1 = resolution.admin1,
            admin2 = resolution.admin2,
        )
    }

    /** The label of a picked city: its Arabic name, else its own. */
    fun catalogueLabel(row: PlaceCatalogue.Row): String = row.arabic.ifEmpty { row.name }

    /**
     * The `dem` of the nearest Arab catalogue row within 40 km for an Arab League place (Ceuta and Melilla
     * included), of the nearest Réunion row for a Réunion place (E16), else null (6.8).
     */
    private fun elevationFor(catalogue: PlaceCatalogue, point: LatLngE5, cc: String?, admin1: String?): Int? {
        val arab = cc != null && (cc in Regions.ARAB_LEAGUE || (cc == "ES" && admin1 in setOf("CE", "ML")))
        if (!arab && cc != "RE") return null
        return catalogue.rowsWithin(point, ELEVATION_KM)
            .firstOrNull { (row, _) -> row.dem != null && !row.ref.neighbourOnly && (if (cc == "RE") row.ref.countryCode == "RE" else row.ref.countryCode in Regions.ARAB_LEAGUE || row.ref.countryCode == "EH" || row.ref.countryCode == "ES") }
            ?.first?.dem
    }

    /** «قرب X» from the nearest listed catalogue city within 30 km, else null («موقعي الحالي» on screen). */
    fun labelFor(catalogue: PlaceCatalogue, point: LatLngE5): String? =
        catalogue.rowsWithin(point, LABEL_KM).firstOrNull { it.first.listed }?.first
            ?.let { row -> "قرب " + row.arabic.ifEmpty { row.name } }

    private fun round2(x: Double): Double = (x * 100).roundToLong() / 100.0

    /**
     * Plan 6.8's comparison: a semantic change is stored at any distance (the kind, the choice key, the zone,
     * an administrative value, the elevation, the mapped official zone), otherwise only a move of more than 3 km.
     */
    fun shouldStore(context: Context, saved: PrayerPlace, fix: PrayerPlace.Coordinates): Boolean {
        if (PrefsManager.isPlaceProvisional(context)) return true
        if (saved !is PrayerPlace.Coordinates) return true
        if (CountryMethodDefaults.choiceKey(saved.countryCode, saved.admin1) != CountryMethodDefaults.choiceKey(fix.countryCode, fix.admin1)) return true
        if (saved.zoneId != fix.zoneId || saved.admin1 != fix.admin1 || saved.admin2 != fix.admin2 || saved.elevationM != fix.elevationM) return true
        val year = java.time.LocalDate.now(fix.zone).year
        val times = PlaceTimesHolder.placeTimes(context)
        val savedZone = times.mapping(saved, PrayerTimesRepository.choice(context, saved), year)?.zone?.id
        val fixZone = times.mapping(fix, PrayerTimesRepository.choice(context, fix), year)?.zone?.id
        if (savedZone != fixZone) return true
        return CountryMethodDefaults.distanceKm(saved.point, fix.point) > MOVE_KM
    }

    /** What the notification and the reschedule compare: the kind, the choice key, the method and the mapped zone. */
    private data class Identity(val kind: String, val key: String?, val method: String?, val zone: String?, val zoneName: String?)

    private fun identity(context: Context, place: PrayerPlace): Identity {
        val coords = place as? PrayerPlace.Coordinates ?: return Identity("inm", null, null, null, null)
        val times = PlaceTimesHolder.placeTimes(context)
        val choice = PrayerTimesRepository.choice(context, coords)
        val mapped = runCatching { times.mapping(coords, choice, java.time.LocalDate.now(coords.zone).year) }.getOrNull()
        return Identity("coordinates", CountryMethodDefaults.choiceKey(coords.countryCode, coords.admin1), times.method(coords, choice).id,
            mapped?.zone?.id, mapped?.zone?.nameAr)
    }

    /**
     * The choice of the new place's key (6.4, 6.5): where the default follows the place and no method was picked
     * there, the default resolved again from the new point; a picked zone kept while the place can take it.
     */
    private fun refreshChoice(context: Context, place: PrayerPlace.Coordinates) {
        val saved = PrefsManager.getMethodChoice(context, place) ?: return
        val times = PlaceTimesHolder.placeTimes(context)
        var next = saved
        val defaults = times.defaults(place)
        // France, Croatia, Serbia, Russia and Palestine: the default follows the place inside the country (6.4).
        if (!saved.methodPicked && saved.methodId != com.tunisianprayertimes.method.PrayerMethod.CUSTOM_ID && defaults.resolvesOnMove) {
            val fallback = defaults.fallback
            if (fallback != saved.methodId) next = next.copy(methodId = fallback, zonePick = null)
        }
        val pick = next.zonePick
        if (pick is com.tunisianprayertimes.method.ZonePick.Zone &&
            runCatching { times.mapping(place, next, java.time.LocalDate.now(place.zone).year) }.getOrNull()?.zone?.id != pick.id
        ) next = next.copy(zonePick = null)
        if (next != saved) PrefsManager.setMethodChoice(context, place, next)
    }

    /**
     * After a stored change of place (6.8): the caches go, the choice of the new key is brought up to date,
     * everything is scheduled again (dhikr, silence, wake, hifz), a dismissal goes only where its time moved by more
     * than 45 min, every saved adhkar reminder is checked again over the year at the new place (6.9), and a change of
     * kind, choice key, method, mapped zone or label is notified once on the `location` channel, naming the new zone.
     */
    suspend fun onPlaceChanged(context: Context, old: PrayerPlace, new: PrayerPlace, oldLabel: String?, newLabel: String?) =
        // Off the main thread (a new place abroad may scan a year of times, and every reminder is checked over a year),
        // and one change at a time, so two quick picks never interleave their dismissals and flags.
        placeChanges.withLock { withContext(Dispatchers.Default) { replan(context, old, new, oldLabel, newLabel) } }

    private val placeChanges = Mutex()

    /** Where the screens start a change of place: leaving the tab must not cancel a re-plan half-way. */
    val changes = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private suspend fun replan(context: Context, old: PrayerPlace, new: PrayerPlace, oldLabel: String?, newLabel: String?) {
        val before = runCatching { identity(context, old) }.getOrNull()
        PrayerTimesRepository.invalidate()
        DhikrReminderScheduler.invalidatePrayerCache()
        AnalyticsTracker.applyRegionalCollection(context)  // analytics follow the new place's country (EU rules)
        if (new is PrayerPlace.Coordinates) runCatching { refreshChoice(context, new) }.onFailure { Log.w(TAG, "Could not refresh the method choice", it) }
        keepDismissalsWhoseTimesHeld(context, old, new)
        PrefsManager.clearAutoSilenceDismissed(context)
        runCatching { ScheduleRefreshCoordinator.syncAll(context) }.onFailure { Log.w(TAG, "Could not reschedule after a place change", it) }
        runCatching { DhikrReminderScheduler.recheckSavedReminders(context) }.onFailure { Log.w(TAG, "Could not re-check the adhkar reminders", it) }
        val after = runCatching { identity(context, new) }.getOrNull()
        val visible = before == null || after == null ||
            before.kind != after.kind || before.key != after.key || before.method != after.method || before.zone != after.zone ||
            (new is PrayerPlace.Coordinates && oldLabel != newLabel)
        val askMethod = new is PrayerPlace.Coordinates &&
            PlaceTimesHolder.placeTimes(context).defaults(new).chooser != com.tunisianprayertimes.method.ChooserKind.NONE &&
            PrefsManager.getMethodChoice(context, new)?.chooserAnswered != true
        if (visible) notifyPlaceChanged(context, newLabel, after?.zoneName, askMethod)
    }

    /**
     * 6.8: the dismissed occurrences whose time the new place moves by at most 45 min stay dismissed (a neighbouring
     * delegation never silences again a window the user ended); the others, and one the new place has no time for, go.
     */
    private fun keepDismissalsWhoseTimesHeld(context: Context, old: PrayerPlace, new: PrayerPlace) {
        val dismissed = PrefsManager.getAutoSilenceDismissals(context)
        if (dismissed.isEmpty()) return
        fun at(place: PrayerPlace, prayer: Prayer, day: java.time.LocalDate) =
            runCatching { PrayerTimesRepository.prayerOccurrences(context, day, place) }.getOrNull()?.firstOrNull { it.prayer == prayer }?.at
        val kept = dismissed.filter { (prayer, day) ->
            val before = at(old, prayer, day) ?: return@filter false
            val after = at(new, prayer, day) ?: return@filter false
            kotlin.math.abs(java.time.Duration.between(before, after).toMinutes()) <= DISMISSAL_MOVE_MINUTES
        }
        PrefsManager.clearAutoSilenceDismissals(context)
        if (kept.isNotEmpty()) PrefsManager.addAutoSilenceDismissals(context, kept, keepFrom = kept.minOf { it.second })
    }

    private fun notifyPlaceChanged(context: Context, label: String?, zoneName: String?, askMethod: Boolean = false) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(LOCATION_CHANNEL, context.getString(R.string.location_channel_name), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val where = label ?: context.getString(R.string.location_current_place)
        val text = if (zoneName != null) context.getString(R.string.location_changed_to_zone, where, zoneName)
        else context.getString(R.string.location_changed_to, where)
        val notification = NotificationCompat.Builder(context, LOCATION_CHANNEL)
            .setSmallIcon(R.drawable.mosque_silhouette)
            .setContentTitle(text)
            .apply { if (askMethod) setContentText(context.getString(R.string.method_chooser_title)) }
            .setAutoCancel(true)
            .setContentIntent(MainActivityPendingIntents.silenceAlarmClock(context))
            .build()
        runCatching { manager.notify(LOCATION_NOTIFICATION_ID, notification) }
    }

    /**
     * The place a skipped first run starts from (6.8), to be confirmed: Tunis when the phone looks Tunisian; else
     * the largest catalogue city of the network country in the phone's zone; else the largest in the phone's zone
     * when that zone is not Tunisia's; else Tunis. Never a city guessed across zones.
     */
    fun provisionalPlace(
        context: Context,
        network: String? = networkCountry(context),
        phoneZone: String = ZoneId.systemDefault().id,
        looksTunisian: Boolean = isPhoneLikelyInTunisia(context),
    ): Pair<PrayerPlace, String?> {
        val tunis = PrayerPlace.Inm(TUNIS_DELEGATION_ID) to null
        if (looksTunisian) return tunis
        val rows = PlaceCatalogue.get(context).listedRows().filter { it.ref.zoneId == phoneZone }
        val country = network?.uppercase()?.let(CountryResolver::normalize)
        val pick = country?.let { cc -> rows.filter { CountryResolver.normalize(it.ref.countryCode) == cc }.maxByOrNull { it.ref.population } }
            ?: rows.takeIf { phoneZone != PrayerPlace.TUNIS.id }?.maxByOrNull { it.ref.population }
            ?: return tunis
        return placeFromCatalogue(context, pick, phoneZone) to catalogueLabel(pick)
    }

    /** Tunis's delegation, the app's first place. */
    const val TUNIS_DELEGATION_ID = 615
}

/** The repository's [com.tunisianprayertimes.place.PlaceTimes], for the travel comparison. */
internal object PlaceTimesHolder {
    fun placeTimes(context: Context): com.tunisianprayertimes.place.PlaceTimes = PrayerTimesRepository.placeTimesFor(context)
}
