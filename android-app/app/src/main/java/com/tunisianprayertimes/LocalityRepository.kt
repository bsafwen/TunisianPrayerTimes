package com.tunisianprayertimes

import android.content.Context
import java.text.Normalizer
import java.util.Locale

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
) {
    val normalizedName = normalizeLocalitySearch(name)
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
        val source = sourcesById[mapped.delegationId]
        val governor = governorsById[mapped.governorateId]
        val terms = listOfNotNull(
            mapped.searchText, mapped.name, mapped.parentName,
            source?.nomAr, source?.nomFr, source?.nomEn,
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
        return filterAvailableLocalities(loadAll(context), available).also {
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
