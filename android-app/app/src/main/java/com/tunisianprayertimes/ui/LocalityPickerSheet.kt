package com.tunisianprayertimes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.Gouvernorat
import com.tunisianprayertimes.Locality
import com.tunisianprayertimes.LocalityKindClass
import com.tunisianprayertimes.LocalityPickerCatalog
import com.tunisianprayertimes.LocalitySearchQuery
import com.tunisianprayertimes.R
import com.tunisianprayertimes.localityKindClass
import com.tunisianprayertimes.rankLocalities
import com.tunisianprayertimes.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private class LocalitySearchRequest(
    val catalog: LocalityPickerCatalog,
    val query: LocalitySearchQuery,
    val governorateNames: Map<Int, String>,
)

/**
 * One header and its rows: a governorate's localities or a country's cities, with the quality of its best match (0 a
 * whole name, 1 a name's start, 2 another direct match, 3 a typo away). The sheet orders sections by it, so an exact
 * city abroad comes before a Tunisian place found only as a typo; at equal quality Tunisia comes first.
 */
private sealed interface PickerSection {
    val quality: Int
}

private class GovernorateSection(val governorateId: Int, val name: String, val rows: List<Locality>, override val quality: Int) : PickerSection

private class CountrySection(val name: String, val rows: List<com.tunisianprayertimes.PlaceCatalogue.Row>, override val quality: Int) : PickerSection

private class LocalityGroupResult(
    val request: LocalitySearchRequest,
    val groups: List<GovernorateSection>,
)

/** The catalogue cities found for [query] in [cities], so a result is never shown for another query. */
private class CityGroupResult(
    val cities: com.tunisianprayertimes.CitySearch.Index,
    val query: String,
    val groups: List<CountrySection>,
)

/** A pause in typing before the cities are searched: a word typed quickly is searched once. */
private const val CITY_SEARCH_PAUSE_MS = 150L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun LocalityPickerSheet(
    catalog: LocalityPickerCatalog,
    gouvernorats: List<Gouvernorat>,
    selectedId: String,
    onDismiss: () -> Unit,
    onSelect: (Locality) -> Unit,
    // Catalogue cities of the open regions (6.8): country groups after the governorates, never snapped to a delegation.
    cities: com.tunisianprayertimes.CitySearch.Index = com.tunisianprayertimes.CitySearch.Index.EMPTY,
    selectedCityId: Long? = null,
    onSelectCity: (com.tunisianprayertimes.PlaceCatalogue.Row) -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val initialIndex = remember(catalog, selectedId) { catalog.selectionScrollIndex(selectedId) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val scrollGuard = rememberSheetScrollGuard(listState)
    val focusRequester = remember { FocusRequester() }
    val governorateNames = remember(gouvernorats) { gouvernorats.associate { it.id to it.nomAr } }
    val search = remember(query) { LocalitySearchQuery.parse(query) }
    val request = remember(catalog, search, governorateNames) {
        LocalitySearchRequest(catalog, search, governorateNames)
    }
    val preparedGroups = remember(catalog, governorateNames) {
        catalog.groups.map { group ->
            GovernorateSection(group.governorateId, governorateNames[group.governorateId] ?: group.fallbackName, group.rows, quality = 0)
        }
    }
    val searchResult by produceState<LocalityGroupResult?>(null, catalog, search, governorateNames) {
        value = null
        if (!search.isEmpty) {
            val groups = withContext(Dispatchers.Default) {
                val scope = this
                catalog.groups.mapNotNull { group ->
                    val ranked = rankLocalities(group.rows, search) { scope.ensureActive() }
                    if (ranked.isEmpty()) null
                    else GovernorateSection(
                        group.governorateId, governorateNames[group.governorateId] ?: group.fallbackName,
                        ranked.map { it.locality }, quality = ranked.minOf { it.quality },
                    )
                }
            }
            ensureActive()
            value = LocalityGroupResult(request, groups)
        }
    }
    val currentResult = searchResult?.takeIf { it.request === request }
    val groups = when {
        search.isEmpty -> preparedGroups
        currentResult != null -> currentResult.groups
        else -> emptyList()
    }
    val cityResult by produceState<CityGroupResult?>(null, cities, query) {
        if (query.isNotBlank()) delay(CITY_SEARCH_PAUSE_MS)
        val found = withContext(Dispatchers.Default) {
            val scope = this
            when {
                cities.isEmpty -> emptyList()
                query.isBlank() -> com.tunisianprayertimes.CitySearch.countryGroups(cities.rows).map { (name, rows) -> CountrySection(name, rows, quality = 0) }
                else -> cities.searchRanked(query) { scope.ensureActive() }
                    .groupBy { (row, _) -> com.tunisianprayertimes.CitySearch.countryName(com.tunisianprayertimes.place.CountryResolver.normalize(row.ref.countryCode)) }
                    .map { (name, ranked) -> CountrySection(name, ranked.map { it.first }, quality = ranked.minOf { it.second }) }
            }
        }
        value = CityGroupResult(cities, query, found)
    }
    // Only the cities found for what is typed now: until they come, the sheet waits rather than show another query's.
    val currentCities = cityResult?.takeIf { it.cities === cities && it.query == query }
    val cityGroups = currentCities?.groups.orEmpty()
    val isSearchPending = (!search.isEmpty && currentResult == null) || (!cities.isEmpty && currentCities == null && groups.isEmpty())
    // Best match first, wherever it is; a stable sort keeps Tunisia's governorates first at equal quality.
    val sections: List<PickerSection> = (groups + cityGroups).sortedBy { it.quality }
    LaunchedEffect(query, catalog) {
        listState.scrollToItem(if (query.isBlank()) catalog.selectionScrollIndex(selectedId) else 0)
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).navigationBarsPadding().imePadding()) {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, GoldLight),
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)
                        .focusRequester(focusRequester).testTag("locality_search"),
                    textStyle = TextStyle(fontSize = 15.sp, color = TextDark, textAlign = TextAlign.Start),
                    singleLine = true,
                    decorationBox = { inner ->
                        if (query.isEmpty()) Text(
                            stringResource(R.string.hint_search_delegation), fontSize = 15.sp, color = TextMuted,
                        )
                        inner()
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            if (isSearchPending) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            } else if (sections.isEmpty()) {
                Text(
                    stringResource(R.string.locality_no_results),
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    textAlign = TextAlign.Center, color = TextMuted, fontSize = 14.sp,
                )
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)
                    .nestedScroll(scrollGuard).testTag("locality_list")) {
                    sections.forEach { section -> when (section) {
                    is GovernorateSection -> {
                        val governorId = section.governorateId
                        val governorName = section.name
                        val rows = section.rows
                        stickyHeader(key = "governorate:$governorId") {
                            Text(
                                governorName, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = GreenPrimary,
                                modifier = Modifier.fillMaxWidth().background(BgCream)
                                    .padding(horizontal = 20.dp, vertical = 8.dp)
                                    .testTag("locality_governorate_$governorId"),
                            )
                        }
                        items(rows, key = { it.id }, contentType = { "locality" }) { locality ->
                            val selected = locality.representsSelection(selectedId)
                            val parent = locality.parentName.takeIf {
                                it.isNotBlank() && it != locality.name && it != governorName
                            }
                            val type = if (locality.id in catalog.typeContextIds) {
                                stringResource(localityKindLabel(locality.kind))
                            } else null
                            val subtitle = listOfNotNull(type, parent).joinToString(" · ")
                            Row(
                                modifier = Modifier.fillMaxWidth().testTag("locality_row_${locality.id}")
                                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(locality) })
                                    .background(if (selected) GoldLight.copy(alpha = 0.2f) else Color.Transparent)
                                    .padding(horizontal = 28.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        locality.name, fontSize = 15.sp,
                                        color = if (selected) GreenPrimaryDark else TextDark,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    )
                                    if (subtitle.isNotBlank()) {
                                        Text(subtitle, fontSize = 12.sp, color = TextMuted)
                                    }
                                }
                                if (selected) Icon(
                                    painterResource(R.drawable.ic_check), contentDescription = null,
                                    tint = GreenPrimary, modifier = Modifier.size(18.dp),
                                )
                            }
                            HorizontalDivider(color = Divider, modifier = Modifier.padding(horizontal = 20.dp))
                        }
                    }
                    is CountrySection -> {
                        val countryName = section.name
                        val rows = section.rows
                        stickyHeader(key = "country:$countryName") {
                            Text(
                                countryName, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = GreenPrimary,
                                modifier = Modifier.fillMaxWidth().background(BgCream).padding(horizontal = 20.dp, vertical = 8.dp),
                            )
                        }
                        items(rows, key = { "city:" + it.ref.id }, contentType = { "city" }) { row ->
                            val selected = row.ref.id == selectedCityId
                            Row(
                                modifier = Modifier.fillMaxWidth().testTag("city_row_${row.ref.id}")
                                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelectCity(row) })
                                    .background(if (selected) GoldLight.copy(alpha = 0.2f) else Color.Transparent)
                                    .padding(horizontal = 28.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        row.arabic.ifEmpty { row.name }, fontSize = 15.sp,
                                        color = if (selected) GreenPrimaryDark else TextDark,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    )
                                    if (row.arabic.isNotEmpty() && row.name != row.arabic) {
                                        Text(row.name, fontSize = 12.sp, color = TextMuted)
                                    }
                                }
                                if (selected) Icon(
                                    painterResource(R.drawable.ic_check), contentDescription = null,
                                    tint = GreenPrimary, modifier = Modifier.size(18.dp),
                                )
                            }
                            HorizontalDivider(color = Divider, modifier = Modifier.padding(horizontal = 20.dp))
                        }
                    }
                    } }
                }
            }
        }
    }
}

private fun localityKindLabel(kind: String): Int = when (localityKindClass(kind)) {
    LocalityKindClass.DELEGATION -> R.string.locality_kind_delegation
    LocalityKindClass.SECTOR -> R.string.locality_kind_sector
    LocalityKindClass.MUNICIPALITY -> R.string.locality_kind_municipality
    LocalityKindClass.TOWN -> R.string.locality_kind_town
    LocalityKindClass.VILLAGE -> R.string.locality_kind_village
    LocalityKindClass.HAMLET -> R.string.locality_kind_hamlet
    LocalityKindClass.NEIGHBORHOOD -> R.string.locality_kind_neighborhood
    LocalityKindClass.RESIDENTIAL -> R.string.locality_kind_residential
    LocalityKindClass.AREA -> R.string.locality_kind_area
}
