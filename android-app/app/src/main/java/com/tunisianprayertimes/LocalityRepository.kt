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
    val normalizedName = normalizeLocalitySearch(name)

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

internal fun searchLocalities(localities: List<Locality>, query: String): List<Locality> {
    val normalized = normalizeLocalitySearch(query)
    val terms = normalized.split(' ').filter { it.isNotEmpty() }
    if (terms.isEmpty()) return localities
    return localities.filter { locality -> terms.all { it in locality.searchText } }
        .sortedByDescending {
            when {
                it.normalizedName == normalized -> 3
                it.normalizedName.startsWith(normalized) -> 2
                terms.all { term -> term in it.normalizedName } -> 1
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

/** Merge only compiler-confirmed matches; homonyms elsewhere remain separate choices. */
internal fun groupPickerLocalities(localities: List<Locality>): List<Locality> =
    localities.groupBy { it.pickerGroupId ?: it.id }.map { (groupId, members) ->
        val canonical = members.firstOrNull { it.id == groupId } ?: members.first()
        if (members.size == 1) canonical else canonical.copy(
            searchText = members.joinToString(" ") { it.searchText },
            pickerMemberIds = members.flatMapTo(mutableSetOf()) { it.pickerMemberIds + it.id },
        )
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
    private data class AvailableCatalog(val sources: List<SourceKey>, val localities: List<Locality>)
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

    fun loadAvailable(context: Context, available: List<Delegation>): List<Locality> {
        val sources = available.map { SourceKey(it.id, it.lat, it.lng) }
            .sortedWith(compareBy<SourceKey> { it.id }.thenBy { it.lat }.thenBy { it.lng })
        availableCatalog?.takeIf { it.sources == sources }?.let { return it.localities }
        return groupPickerLocalities(filterAvailableLocalities(loadAll(context), available)).also {
            availableCatalog = AvailableCatalog(sources, it)
        }
    }

    fun selected(context: Context): Locality? {
        val id = PrefsManager.getLocalityId(context) ?: return null
        val delegationId = PrefsManager.getDelegationId(context)
        // GPS chooses the timetable from the actual point, which can differ from
        // the representative point used when manually selecting a polygon's name.
        return loadAll(context).find { it.id == id }?.copy(delegationId = delegationId)
    }
}
