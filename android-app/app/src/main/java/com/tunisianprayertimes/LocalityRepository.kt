package com.tunisianprayertimes

import android.content.Context
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
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
    val searchPhrases: List<String> = emptyList(),
) {
    val normalizedName: String by lazy { normalizeLocalitySearch(name) }

    internal val compactName: String by lazy { normalizedName.replace(" ", "") }

    private val compactSearchPhrases: List<String> by lazy {
        (searchPhrases.ifEmpty { listOf(searchText) } + name + parentName)
            .map { normalizeLocalitySearch(it).replace(" ", "") }.filter { it.isNotEmpty() }.distinct()
    }

    internal val searchTokens: List<String> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        localitySearchTerms(normalizeLocalitySearch(searchText))
    }

    internal val fuzzySearchTokens: List<LocalityFuzzyToken> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        (searchTokens.asSequence() + compactSearchPhrases.asSequence())
            .filter { it.length >= MIN_FUZZY_TOKEN_LENGTH }
            .distinct()
            .mapNotNull { token ->
                val descriptor = localityFuzzyToken(token)
                if (descriptor.script == null) null else descriptor
            }
            .toList()
    }

    internal val numberedSearchTokens: Map<String, List<LocalityFuzzyToken>> by lazy {
        val numbered = mutableMapOf<String, MutableSet<String>>()
        // Keep aliases and administrative context as separate phrases. A
        // trailing UV4 alias must not attach its 4 to the following parent name.
        (searchPhrases.ifEmpty { listOf(searchText) } + name + parentName).distinct().forEach { phrase ->
            val normalized = normalizeLocalitySearch(phrase)
            listOf(normalized, normalized.replace(" ", "")).distinct().forEach { variant ->
                val tokens = localitySearchTerms(variant)
                tokens.forEachIndexed { index, number ->
                    if (number.isNotEmpty() && number.all { it.isDigit() }) {
                        listOfNotNull(tokens.getOrNull(index - 1), tokens.getOrNull(index + 1))
                            .filter { word -> word.none { it.isDigit() } }
                            .forEach { word -> numbered.getOrPut(number) { mutableSetOf() } += word }
                    }
                }
            }
        }
        numbered.mapValues { (_, words) -> words.map { localityFuzzyToken(it) } }
    }

    internal fun matchesSearchTerm(term: String): Boolean = when {
        // Keep the full number: 1 must not select a namesake numbered 12.
        term.all { it.isDigit() } -> term in searchTokens
        // Keep a joined label's number attached to it instead of matching a
        // number from unrelated parent context (UV5 must not find UV4 in المنزه 5).
        term.any { it.isDigit() } -> compactSearchPhrases.any { containsWholeNumberTerm(it, term) }
        else -> term in searchText || compactSearchPhrases.any { term in it }
    }

    internal fun prewarmSearchIndex() {
        searchTokens.size
        fuzzySearchTokens.size
        numberedSearchTokens.size
    }

    fun representsSelection(selectedId: String): Boolean = id == selectedId || selectedId in pickerMemberIds
}

private val combiningMarks = Regex("\\p{M}+")
private val formatCharacters = Regex("[\\p{Cf}]")
private val wordSeparators = Regex("[^\\p{L}\\p{N}]+")
private val letterNumberBoundary = Regex("(?<=\\p{L})(?=\\p{N})|(?<=\\p{N})(?=\\p{L})")

// Search-only tokenization; display-name normalization also identifies picker groups.
private fun localitySearchTerms(normalized: String): List<String> =
    normalized.replace(letterNumberBoundary, " ").split(' ').filter { it.isNotEmpty() }

private fun containsWholeNumberTerm(text: String, term: String): Boolean {
    var index = text.indexOf(term)
    while (index >= 0) {
        val end = index + term.length
        if ((!term.first().isDigit() || index == 0 || !text[index - 1].isDigit()) &&
            (!term.last().isDigit() || end == text.length || !text[end].isDigit())) return true
        index = text.indexOf(term, index + 1)
    }
    return false
}

internal fun normalizeLocalitySearch(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(combiningMarks, "")
        .replace("ـ", "")
        .replace('ى', 'ي')
        .replace(formatCharacters, "")
        .lowercase(Locale.ROOT)
        .replace(wordSeparators, " ")
        .trim()

private const val MIN_FUZZY_TOKEN_LENGTH = 4
private const val MAX_FUZZY_EDITS_PER_ROW = 2

internal enum class LocalityFuzzyScript { ARABIC, LATIN }

internal data class LocalityFuzzyToken(
    val text: String,
    val folded: String,
    val script: LocalityFuzzyScript?,
    val editLimit: Int = 0,
)

private fun foldLocalityFuzzyToken(token: String): String =
    token.replace('ة', 'ه').replace('ی', 'ي').replace('ک', 'ك').replace("œ", "oe").replace("æ", "ae")

private fun localityFuzzyToken(token: String, editLimit: Int = 0): LocalityFuzzyToken =
    LocalityFuzzyToken(
        text = token,
        folded = foldLocalityFuzzyToken(token),
        script = localityFuzzyScript(token),
        editLimit = editLimit,
    )

private fun localityFuzzyScript(token: String): LocalityFuzzyScript? {
    var script: LocalityFuzzyScript? = null
    for (ch in token) {
        if (!ch.isLetter()) return null
        val code = ch.code
        val current = when {
            ch in 'a'..'z' -> LocalityFuzzyScript.LATIN
            code in 0x00C0..0x00FF ||
                code in 0x0100..0x017F ||
                code in 0x0180..0x024F ||
                code in 0x1E00..0x1EFF -> LocalityFuzzyScript.LATIN
            code in 0x0600..0x06FF ||
                code in 0x0750..0x077F ||
                code in 0x08A0..0x08FF -> LocalityFuzzyScript.ARABIC
            else -> return null
        }
        if (script == null) script = current
        else if (script != current) return null
    }
    return script
}

private fun maxFuzzyEditsForTerm(term: String): Int = when {
    term.length >= 8 -> 2
    term.length >= MIN_FUZZY_TOKEN_LENGTH -> 1
    else -> 0
}

private fun boundedOptimalStringAlignmentDistance(
    a: String,
    b: String,
    maxEdits: Int,
    checkCancellation: () -> Unit,
): Int {
    if (a == b) return 0
    if (maxEdits <= 0) return maxEdits + 1
    val aLength = a.length
    val bLength = b.length
    if (abs(aLength - bLength) > maxEdits) return maxEdits + 1
    var previousPrevious = IntArray(bLength + 1)
    var previous = IntArray(bLength + 1) { it }
    var current = IntArray(bLength + 1)
    for (i in 1..aLength) {
        checkCancellation()
        current[0] = i
        var rowMinimum = current[0]
        for (j in 1..bLength) {
            val substitutionCost = if (a[i - 1] == b[j - 1]) 0 else 1
            var value = minOf(
                previous[j] + 1,
                current[j - 1] + 1,
                previous[j - 1] + substitutionCost,
            )
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                value = minOf(value, previousPrevious[j - 2] + 1)
            }
            current[j] = value
            if (value < rowMinimum) rowMinimum = value
        }
        if (rowMinimum > maxEdits) return maxEdits + 1
        val swap = previousPrevious
        previousPrevious = previous
        previous = current
        current = swap
    }
    return previous[bLength]
}

private data class FuzzyLocalityMatch(
    val locality: Locality,
    val edits: Int,
    val primaryRank: Int,
    val originalIndex: Int,
)

private fun primaryLocalityRank(locality: Locality, query: LocalitySearchQuery): Int = when {
    locality.normalizedName == query.normalized || locality.compactName == query.compact -> 3
    locality.normalizedName.startsWith(query.normalized) -> 2
    query.terms.all { term -> term in locality.normalizedName } -> 1
    else -> 0
}

private fun fuzzyTermCost(
    term: LocalityFuzzyToken,
    tokens: List<LocalityFuzzyToken>,
    remainingEdits: Int,
    checkCancellation: () -> Unit,
): Int? {
    val termScript = term.script ?: return null
    if (term.editLimit <= 0) return null
    val termLimit = minOf(term.editLimit, remainingEdits)
    var best = term.editLimit + 1
    for (candidate in tokens) {
        if (candidate.script != termScript) continue
        if (candidate.folded == term.folded) return 0
        if (termLimit <= 0) continue
        if (abs(candidate.folded.length - term.folded.length) > termLimit) continue
        val distance = boundedOptimalStringAlignmentDistance(
            a = term.folded,
            b = candidate.folded,
            maxEdits = termLimit,
            checkCancellation = checkCancellation,
        )
        if (distance < best) best = distance
    }
    return if (best <= termLimit) best else null
}

private fun fuzzyMatchCost(
    locality: Locality,
    query: LocalitySearchQuery,
    checkCancellation: () -> Unit,
    termCosts: MutableMap<String, Int>? = null,
): Int? {
    var totalEdits = 0
    for (term in query.fuzzyTerms) {
        if (locality.matchesSearchTerm(term.text)) {
            termCosts?.set(term.text, 0)
            continue
        }
        val remainingEdits = MAX_FUZZY_EDITS_PER_ROW - totalEdits
        if (remainingEdits < 0) return null
        val cost = fuzzyTermCost(
            term = term,
            tokens = locality.fuzzySearchTokens,
            remainingEdits = remainingEdits,
            checkCancellation = checkCancellation,
        ) ?: return null
        termCosts?.set(term.text, cost)
        totalEdits += cost
        if (totalEdits > MAX_FUZZY_EDITS_PER_ROW) return null
    }
    return totalEdits
}

/** Search input parsed once so each governorate group filters without re-normalizing. */
internal class LocalitySearchQuery private constructor(
    val normalized: String,
    val terms: List<String>,
    internal val fuzzyTerms: List<LocalityFuzzyToken>,
    internal val numberedWords: Map<String, List<LocalityFuzzyToken>>,
) {
    val isEmpty: Boolean get() = terms.isEmpty()
    internal val compact: String = normalized.replace(" ", "")

    companion object {
        fun parse(query: String): LocalitySearchQuery {
            val normalized = normalizeLocalitySearch(query)
            val terms = normalized.split(' ').filter { it.isNotEmpty() }
            val fuzzyTerms = terms.distinct().map { term ->
                localityFuzzyToken(term, maxFuzzyEditsForTerm(term))
            }
            val numberedWords = mutableMapOf<String, MutableList<LocalityFuzzyToken>>()
            terms.forEachIndexed { index, number ->
                if (number.all { it.isDigit() }) {
                    val words = listOfNotNull(terms.getOrNull(index - 1), terms.getOrNull(index + 1))
                        .filter { word -> word.none { it.isDigit() } }
                        .map { word -> localityFuzzyToken(word, maxFuzzyEditsForTerm(word)) }
                    if (words.isNotEmpty()) numberedWords.getOrPut(number) { mutableListOf() } += words
                }
            }
            return LocalitySearchQuery(normalized, terms, fuzzyTerms, numberedWords)
        }
    }
}

private fun numberedLocalityTermsMatch(
    locality: Locality,
    query: LocalitySearchQuery,
    allowFuzzy: Boolean,
    checkCancellation: () -> Unit,
    termCosts: Map<String, Int> = emptyMap(),
): Boolean = query.numberedWords.all { (number, words) ->
    val candidates = locality.numberedSearchTokens[number].orEmpty()
    words.any { word ->
        candidates.any { word.text in it.text } || (allowFuzzy && fuzzyTermCost(
            // Number affinity can only use edits already charged to this word.
            word, candidates, termCosts[word.text] ?: 0, checkCancellation,
        ) != null)
    }
}

internal fun searchLocalities(localities: List<Locality>, query: String): List<Locality> =
    searchLocalities(localities, LocalitySearchQuery.parse(query))

internal fun searchLocalities(localities: List<Locality>, query: LocalitySearchQuery): List<Locality> =
    searchLocalities(localities, query) { }

internal fun searchLocalities(
    localities: List<Locality>,
    query: LocalitySearchQuery,
    checkCancellation: () -> Unit,
): List<Locality> {
    if (query.isEmpty) return localities
    val direct = mutableListOf<Locality>()
    val fuzzy = mutableListOf<FuzzyLocalityMatch>()
    localities.forEachIndexed { index, locality ->
        checkCancellation()
        if (query.terms.all { locality.matchesSearchTerm(it) } &&
            numberedLocalityTermsMatch(locality, query, allowFuzzy = false, checkCancellation)) {
            direct += locality
        } else {
            val termCosts = if (query.numberedWords.isEmpty()) null else mutableMapOf<String, Int>()
            val edits = fuzzyMatchCost(locality, query, checkCancellation, termCosts) ?: return@forEachIndexed
            if (!numberedLocalityTermsMatch(locality, query, allowFuzzy = true, checkCancellation,
                    termCosts.orEmpty())) return@forEachIndexed
            fuzzy += FuzzyLocalityMatch(
                locality = locality,
                edits = edits,
                primaryRank = primaryLocalityRank(locality, query),
                originalIndex = index,
            )
        }
    }
    val directSorted = direct.sortedByDescending { primaryLocalityRank(it, query) }
    val fuzzySorted = fuzzy.sortedWith(
        compareBy<FuzzyLocalityMatch> { it.edits }
            .thenByDescending { it.primaryRank }
            .thenBy { it.originalIndex },
    )
    return directSorted + fuzzySorted.map { it.locality }
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

private fun mergedPickerRow(
    canonical: Locality,
    members: List<Locality>,
    representative: Locality? = null,
): Locality = if (members.size == 1 && representative == null) canonical else canonical.copy(
    lat = representative?.lat ?: canonical.lat,
    lng = representative?.lng ?: canonical.lng,
    searchText = members.joinToString(" ") { it.searchText },
    searchPhrases = members.flatMap { it.searchPhrases.ifEmpty { listOf(it.searchText) } + it.name + it.parentName }.distinct(),
    pickerMemberIds = members.flatMapTo(mutableSetOf()) { it.pickerMemberIds + it.id },
)

/** Merge confirmed matches while keeping every official sector selectable by its own ID. */
internal fun groupPickerLocalities(localities: List<Locality>): List<Locality> =
    localities.groupBy { it.pickerGroupId ?: it.id }.flatMap { (groupId, members) ->
        val canonical = members.firstOrNull { it.id == groupId } ?: members.first()
        val representative = if (canonical.id.startsWith("delegation:")) {
            retainedPickerRepresentative(members)
        } else null
        if (canonical.kind == "sector" || canonical.id != groupId) {
            return@flatMap listOf(mergedPickerRow(canonical, members, representative))
        }
        val sectors = members.filter { it.kind == "sector" && it.hasBoundary }
        if (sectors.isEmpty()) {
            return@flatMap listOf(mergedPickerRow(canonical, members, representative))
        }
        // A same-name point/delegation and official sector were previously one
        // visible choice. Keep one row with the old reference point, but select
        // the exact sector ID. Other sectors retain their own names and IDs.
        val namedSector = sectors.firstOrNull { it.normalizedName == canonical.normalizedName }
        val included = members.filter { it !in sectors || it.id == namedSector?.id }
        val visible = mergedPickerRow(
            namedSector ?: canonical,
            included,
            if (namedSector != null) representative ?: canonical else representative,
        )
        listOf(visible) + sectors.filterNot { it.id == namedSector?.id }
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
    localities.forEach { it.prewarmSearchIndex() }
    val rowsByGovernorate = LinkedHashMap<Int, MutableList<Locality>>()
    localities.forEach { locality ->
        rowsByGovernorate.getOrPut(locality.governorateId) { mutableListOf() } += locality
    }
    val orderedIds = gouvernorats.map { it.id } +
        rowsByGovernorate.keys.filter { id -> gouvernorats.none { it.id == id } }
    val groups = orderedIds.mapNotNull { id ->
        rowsByGovernorate[id]?.let { rows -> LocalityPickerGroup(id, rows.first().parentName, rows) }
    }
    val typeContextIds = mixedKindGroupIds(localities).toMutableSet()
    val governorateNames = gouvernorats.associate { it.id to it.nomAr }
    // Disambiguate otherwise identical rows once, before filtering or composing the list.
    // These display labels do not merge the localities or change their selection identities.
    groups.forEach { group ->
        val governorateName = governorateNames[group.governorateId] ?: group.fallbackName
        group.rows.filterNot { it.id in typeContextIds }
            .groupBy { locality ->
                locality.name to locality.parentName.takeIf {
                    it.isNotBlank() && it != locality.name && it != governorateName
                }
            }
            .values.forEach { namesakes ->
                if (namesakes.map { localityKindClass(it.kind) }.distinct().size > 1) {
                    namesakes.mapTo(typeContextIds) { it.id }
                }
            }
    }
    return LocalityPickerCatalog(localities, groups, typeContextIds)
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
        mapped.copy(
            searchText = normalizeLocalitySearch(terms.joinToString(" ")),
            searchPhrases = (mapped.searchPhrases.ifEmpty { listOf(mapped.searchText) } + terms.drop(1)).distinct(),
        )
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
                LocalityDisplayNames.localize(context,
                    Locality("delegation:${d.id}", d.nomAr, d.nomAr, gov.id, d.id,
                        normalizeLocalitySearch("${d.nomAr} ${d.nomFr} ${d.nomEn} ${gov.nomAr} ${gov.nomFr} ${gov.nomEn}"),
                        searchPhrases = listOf(d.nomAr, d.nomFr, d.nomEn, gov.nomAr, gov.nomFr, gov.nomEn))
                )
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
        val delegation = members.firstOrNull { it.id == localityId } ?: return null
        return mergedPickerRow(delegation, members, retainedPickerRepresentative(members))
    }

    fun selected(context: Context): Locality? {
        val id = PrefsManager.getLocalityId(context) ?: return null
        val delegationId = PrefsManager.getDelegationId(context)
        // GPS chooses the timetable from the actual point, which can differ from
        // the representative point used when manually selecting a polygon's name.
        return loadAll(context).find { it.id == id }?.copy(delegationId = delegationId)
    }
}
