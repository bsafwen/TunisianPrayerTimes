package com.tunisianprayertimes

import android.content.Context
import java.text.Normalizer
import java.util.Locale
import org.json.JSONObject

data class Locality(
    val id: String,
    val name: String,
    val parentName: String,
    val governorateId: Int,
    val delegationId: Int,
    val searchText: String,
    val lat: Double? = null,
    val lng: Double? = null,
    val hasBoundary: Boolean = false,
    val kind: String = "delegation",
    val pickerGroupId: String? = null,
    val pickerMemberIds: Set<String> = emptySet(),
) {
    val normalizedName: String by lazy { normalizeLocalitySearch(name) }

    fun representsSelection(selectedId: String): Boolean = id == selectedId || selectedId in pickerMemberIds
}

private val combiningMarks = Regex("\\p{M}+")
private val formatCharacters = Regex("[\\p{Cf}]")
private val wordSeparators = Regex("[^\\p{L}\\p{N}]+")

internal fun normalizeLocalitySearch(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(combiningMarks, "")
        .replace("ـ", "")
        .replace('ى', 'ي')
        .replace(formatCharacters, "")
        .lowercase(Locale.ROOT)
        .replace(wordSeparators, " ")
        .trim()

/** Search input parsed once so each governorate group filters without re-normalizing. */
internal class LocalitySearchQuery private constructor(
    val normalized: String,
    val terms: List<String>,
) {
    val isEmpty: Boolean get() = terms.isEmpty()

    companion object {
        fun parse(query: String): LocalitySearchQuery {
            val normalized = normalizeLocalitySearch(query)
            return LocalitySearchQuery(normalized, normalized.split(' ').filter { it.isNotEmpty() })
        }
    }
}

internal fun searchLocalities(localities: List<Locality>, query: String): List<Locality> =
    searchLocalities(localities, LocalitySearchQuery.parse(query))

internal fun searchLocalities(localities: List<Locality>, query: LocalitySearchQuery): List<Locality> {
    if (query.isEmpty) return localities
    return localities.filter { locality -> query.terms.all { it in locality.searchText } }
        .sortedByDescending {
            when {
                it.normalizedName == query.normalized -> 3
                it.normalizedName.startsWith(query.normalized) -> 2
                query.terms.all { term -> term in it.normalizedName } -> 1
                else -> 0
            }
        }
}

internal fun withAvailablePrayerSource(locality: Locality, available: List<Delegation>): Locality? {
    val delegation = if (locality.lat != null || locality.lng != null) {
        val lat = locality.lat ?: return null
        val lng = locality.lng ?: return null
        if (!validCoordinates(lat, lng)) return null
        available.asSequence().filter { it.hasUsableCoordinates() }
            .minWithOrNull(compareBy<Delegation> { haversineKm(lat, lng, it.lat, it.lng) }.thenBy { it.id })
    } else available.firstOrNull { it.id == locality.delegationId }
    return delegation?.let { if (locality.delegationId == it.id) locality else locality.copy(delegationId = it.id) }
}

private fun Delegation.hasUsableCoordinates(): Boolean =
    lat != 0.0 && lng != 0.0 && validCoordinates(lat, lng)

/** Browsing only needs eligibility; calculate the nearest source once, when a row is selected. */
internal fun filterAvailableLocalities(localities: List<Locality>, available: List<Delegation>): List<Locality> {
    val availableIds = available.mapTo(mutableSetOf()) { it.id }
    val hasCoordinateSource = available.any { it.hasUsableCoordinates() }
    return localities.filter { locality ->
        if (locality.lat != null || locality.lng != null) {
            hasCoordinateSource && locality.lat != null && locality.lng != null &&
                validCoordinates(locality.lat, locality.lng)
        } else locality.delegationId in availableIds
    }
}

/** A named settlement is a more useful manual reference than its broader sector. */
private fun retainedPickerRepresentative(members: List<Locality>): Locality? =
    members.asSequence()
        .filter { !it.id.startsWith("delegation:") && it.lat != null && it.lng != null &&
            validCoordinates(it.lat, it.lng) }
        .minWithOrNull(compareBy<Locality> {
            when (it.kind) {
                "city", "town" -> 0
                "village" -> 1
                "hamlet" -> 2
                "suburb", "neighbourhood", "quarter", "city_district" -> 3
                "residential" -> 4
                "sector" -> 5
                "municipality" -> 6
                else -> 7
            }
        }.thenBy { it.id })

/** Merge only compiler-confirmed matches; homonyms elsewhere remain separate choices. */
internal fun groupPickerLocalities(localities: List<Locality>): List<Locality> =
    localities.groupBy { it.pickerGroupId ?: it.id }.map { (groupId, members) ->
        val canonical = members.firstOrNull { it.id == groupId } ?: members.first()
        if (members.size == 1) canonical else {
            // Keep the display identity, but select its timetable from a retained
            // locality point. Never substitute the prayer source's coordinates.
            val representative = if (canonical.id.startsWith("delegation:")) {
                retainedPickerRepresentative(members)
            } else null
            canonical.copy(
                lat = representative?.lat ?: canonical.lat,
                lng = representative?.lng ?: canonical.lng,
                searchText = members.joinToString(" ") { it.searchText },
                pickerMemberIds = members.flatMapTo(mutableSetOf()) { it.pickerMemberIds + it.id },
            )
        }
    }

/** Distinguishes the kinds the picker labels differently, so classification happens once. */
internal enum class LocalityKindClass {
    DELEGATION, SECTOR, MUNICIPALITY, TOWN, VILLAGE, HAMLET, NEIGHBORHOOD, RESIDENTIAL, AREA,
}

internal fun localityKindClass(kind: String): LocalityKindClass = when (kind) {
    "delegation" -> LocalityKindClass.DELEGATION
    "sector" -> LocalityKindClass.SECTOR
    "municipality" -> LocalityKindClass.MUNICIPALITY
    "town", "city" -> LocalityKindClass.TOWN
    "village" -> LocalityKindClass.VILLAGE
    "hamlet" -> LocalityKindClass.HAMLET
    "neighbourhood", "quarter", "suburb", "city_district" -> LocalityKindClass.NEIGHBORHOOD
    "residential" -> LocalityKindClass.RESIDENTIAL
    else -> LocalityKindClass.AREA
}

internal class LocalityPickerGroup(
    val governorateId: Int,
    val fallbackName: String,
    val rows: List<Locality>,
)

/** Query-independent picker data, prepared once per available-source snapshot off the main thread. */
internal class LocalityPickerCatalog(
    val localities: List<Locality>,
    val groups: List<LocalityPickerGroup>,
    val typeContextIds: Set<String>,
) {
    /**
     * The item to show first so the selected row sits directly under its sticky
     * header, without composing from the top and scrolling afterwards.
     */
    fun selectionScrollIndex(selectedId: String): Int {
        var index = 0
        groups.forEach { group ->
            val row = group.rows.indexOfFirst { it.representsSelection(selectedId) }
            if (row >= 0) return index + row
            index += group.rows.size + 1
        }
        return 0
    }

    companion object {
        val empty = LocalityPickerCatalog(emptyList(), emptyList(), emptySet())
    }
}

private const val GROUPS_KEY_SEPARATOR = '\u0000'

private fun localityGroupKey(locality: Locality): String =
    "${locality.governorateId}$GROUPS_KEY_SEPARATOR${locality.normalizedName}"

internal fun buildPickerCatalog(localities: List<Locality>, gouvernorats: List<Gouvernorat>): LocalityPickerCatalog {
    val rowsByGovernorate = LinkedHashMap<Int, MutableList<Locality>>()
    localities.forEach { locality ->
        rowsByGovernorate.getOrPut(locality.governorateId) { mutableListOf() } += locality
    }
    val orderedIds = gouvernorats.map { it.id } +
        rowsByGovernorate.keys.filter { id -> gouvernorats.none { it.id == id } }
    val groups = orderedIds.mapNotNull { id ->
        rowsByGovernorate[id]?.let { rows -> LocalityPickerGroup(id, rows.first().parentName, rows) }
    }
    return LocalityPickerCatalog(localities, groups, mixedKindGroupIds(localities))
}

/** A row only needs its kind label when its namesake group mixes administrative levels. */
private fun mixedKindGroupIds(localities: List<Locality>): Set<String> {
    class GroupKinds {
        var hasDelegation = false
        val kinds = mutableSetOf<LocalityKindClass>()
    }
    val byName = HashMap<String, GroupKinds>()
    localities.forEach { locality ->
        val kinds = byName.getOrPut(localityGroupKey(locality)) { GroupKinds() }
        if (locality.kind == "delegation") kinds.hasDelegation = true
        kinds.kinds += localityKindClass(locality.kind)
    }
    val mixed = HashSet<String>()
    byName.forEach { (key, kinds) -> if (kinds.hasDelegation && kinds.kinds.size > 1) mixed += key }
    if (mixed.isEmpty()) return emptySet()
    return localities.asSequence().filter { localityGroupKey(it) in mixed }
        .mapTo(mutableSetOf()) { it.id }
}

internal fun enrichLocalityCatalog(localities: List<Locality>, governors: List<Gouvernorat>): List<Locality> {
    val delegations = governors.flatMap { it.delegations }
    val sourcesById = delegations.associateBy { it.id }
    val governorsById = governors.associateBy { it.id }
    return localities.map { locality ->
        // A renamed/removed source or missing parent must not discard unrelated places.
        // The bundled catalog already stores its nearest source. Repair obsolete IDs,
        // but do not repeat thousands of nearest-neighbor searches just to browse names.
        val mapped = if (locality.delegationId in sourcesById) locality
            else withAvailablePrayerSource(locality, delegations) ?: locality
        val governor = governorsById[mapped.governorateId]
        val terms = listOfNotNull(
            mapped.searchText, mapped.name, mapped.parentName,
            governor?.nomAr, governor?.nomFr, governor?.nomEn
        )
        mapped.copy(searchText = normalizeLocalitySearch(terms.joinToString(" ")))
    }
}

object LocalityRepository {
    @Volatile private var cached: List<Locality>? = null
    private val catalogLock = Any()
    private data class SourceKey(val id: Int, val lat: Double, val lng: Double)
    private data class AvailableCatalog(val sources: List<SourceKey>, val picker: LocalityPickerCatalog)
    @Volatile private var availableCatalog: AvailableCatalog? = null
    internal data class ReviewedLocalityName(val name: String, val kind: String)
    internal data class ReviewedLocalityReplacement(val replacementId: String, val name: String, val kind: String)
    private data class SavedLocalityUpdates(
        val retiredIds: Set<String> = emptySet(),
        val reviewedNames: Map<String, ReviewedLocalityName> = emptyMap(),
        val replacements: Map<String, ReviewedLocalityReplacement> = emptyMap(),
    )
    @Volatile private var savedLocalityUpdates: SavedLocalityUpdates? = null
    private val savedLocalityUpdatesLock = Any()

    private fun savedLocalityUpdates(context: Context): SavedLocalityUpdates {
        savedLocalityUpdates?.let { return it }
        return synchronized(savedLocalityUpdatesLock) {
            savedLocalityUpdates ?: runCatching {
                // Only explicitly reviewed ID changes are read here. Keep
                // preference reads independent of full metadata and geometry.
                val json = JSONObject(context.assets.open("retired-localities.json").bufferedReader().use { it.readText() })
                require(json.getInt("schemaVersion") == 1)
                val values = json.getJSONArray("retiredLocalityIds")
                val retiredIds = buildSet {
                    for (index in 0 until values.length()) {
                        val id = values.get(index)
                        require(id is String && id.isNotBlank())
                        require(add(id))
                    }
                }
                val reviewedNames = buildMap {
                    if (json.has("reviewedNames")) {
                        val names = json.getJSONArray("reviewedNames")
                        for (index in 0 until names.length()) {
                            val row = names.getJSONObject(index)
                            val id = row.get("id")
                            val name = row.get("name")
                            val kind = row.get("kind")
                            require(id is String && id.isNotBlank() && id !in retiredIds)
                            require(name is String && name.isNotBlank())
                            require(kind is String && kind.isNotBlank())
                            require(put(id, ReviewedLocalityName(name, kind)) == null)
                        }
                    }
                }
                val replacements = buildMap {
                    if (json.has("replacements")) {
                        val entries = json.getJSONArray("replacements")
                        val targetIds = mutableSetOf<String>()
                        for (index in 0 until entries.length()) {
                            val row = entries.getJSONObject(index)
                            val id = row.get("id")
                            val replacementId = row.get("replacementId")
                            val name = row.get("name")
                            val kind = row.get("kind")
                            require(id is String && id.isNotBlank() && id in retiredIds)
                            require(replacementId is String && replacementId.isNotBlank() && replacementId !in retiredIds)
                            require(name is String && name.isNotBlank())
                            require(kind is String && kind.isNotBlank())
                            require(targetIds.add(replacementId))
                            require(reviewedNames[replacementId] == ReviewedLocalityName(name, kind))
                            require(put(id, ReviewedLocalityReplacement(replacementId, name, kind)) == null)
                        }
                    }
                }
                SavedLocalityUpdates(retiredIds, reviewedNames, replacements)
            }.getOrDefault(SavedLocalityUpdates()).also { savedLocalityUpdates = it }
        }
    }

    internal fun isRetired(context: Context, localityId: String): Boolean =
        localityId in savedLocalityUpdates(context).retiredIds

    internal fun reviewedName(context: Context, localityId: String): ReviewedLocalityName? =
        savedLocalityUpdates(context).reviewedNames[localityId]

    internal fun reviewedReplacement(context: Context, localityId: String): ReviewedLocalityReplacement? =
        savedLocalityUpdates(context).replacements[localityId]

    fun loadAll(context: Context): List<Locality> {
        cached?.let { return it }
        return synchronized(catalogLock) {
            cached ?: readCatalog(context).also { cached = it }
        }
    }

    private fun readCatalog(context: Context): List<Locality> {
        val governors = GouvernoratRepository.loadAll(context)
        val result = governors.flatMap { gov ->
            gov.delegations.map { d ->
                Locality("delegation:${d.id}", d.nomAr, d.nomAr, gov.id, d.id,
                    normalizeLocalitySearch("${d.nomAr} ${d.nomFr} ${d.nomEn} ${gov.nomAr} ${gov.nomFr} ${gov.nomEn}"))
            }
        }.toMutableList()
        // Browsing names does not read the polygon binary or validate its geometry.
        // The original delegations remain usable if the metadata asset is damaged.
        val extra = runCatching {
            enrichLocalityCatalog(NeighborhoodRepository.loadLocalities(context), governors)
        }.getOrDefault(emptyList())
        result += extra
        return result.toList()
    }

    fun loadAvailable(context: Context, available: List<Delegation>): List<Locality> =
        preparePicker(context, available).localities

    /**
     * Builds the query-independent picker data once per source snapshot. Call off the
     * main thread: opening the sheet then only renders the prepared groups.
     */
    internal fun preparePicker(context: Context, available: List<Delegation>): LocalityPickerCatalog {
        val sources = available.map { SourceKey(it.id, it.lat, it.lng) }
            .sortedWith(compareBy<SourceKey> { it.id }.thenBy { it.lat }.thenBy { it.lng })
        availableCatalog?.takeIf { it.sources == sources }?.let { return it.picker }
        // Group before filtering: a merged place can use another source even
        // when its canonical delegation has no complete timetable this month.
        val picker = buildPickerCatalog(
            filterAvailableLocalities(groupPickerLocalities(loadAll(context)), available),
            GouvernoratRepository.loadAll(context),
        )
        availableCatalog = AvailableCatalog(sources, picker)
        return picker
    }

    /** Saved manual groups use the same representative as a new picker selection. */
    fun manualSelection(context: Context, localityId: String): Locality? {
        val localities = loadAll(context)
        // A saved raw locality keeps its own ID and representative, including
        // when its name is currently displayed within a larger picker group.
        if (!localityId.startsWith("delegation:")) return localities.find { it.id == localityId }
        val members = localities.filter { (it.pickerGroupId ?: it.id) == localityId }
        return groupPickerLocalities(members).singleOrNull()?.takeIf { it.id == localityId }
    }

    fun selected(context: Context): Locality? {
        val id = PrefsManager.getLocalityId(context) ?: return null
        val delegationId = PrefsManager.getDelegationId(context)
        // GPS chooses the timetable from the actual point, which can differ from
        // the representative point used when manually selecting a polygon's name.
        return loadAll(context).find { it.id == id }?.copy(delegationId = delegationId)
    }
}
